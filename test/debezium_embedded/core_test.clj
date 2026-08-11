(ns debezium-embedded.core-test
  (:require
   [clojure.test :refer [deftest is]]
   [debezium-embedded.core :as core]
   [debezium-embedded.lifecycle :as lifecycle]))

(defn- ephemeral-postgres-config []
  {:name                     "capture"
   :connector.class          "io.debezium.connector.postgresql.PostgresConnector"
   :database.hostname        "localhost"
   :database.port            "5432"
   :database.user            "postgres"
   :database.password        "postgres"
   :database.dbname          "postgres"
   :schema.include.list      "inventory"
   :topic.prefix             "test"
   :plugin.name              "pgoutput"
   :offset.storage           "org.apache.kafka.connect.storage.MemoryOffsetBackingStore"
   :offset.flush.interval.ms "0"
   :converter.schemas.enable "false"})

(defn- started-engine-trace []
  [::lifecycle/start-requested
   ::lifecycle/engine-submission-started
   ::lifecycle/engine-invocation-started])

(defn- capturing-trace []
  (into (started-engine-trace)
        [::lifecycle/connector-started
         ::lifecycle/polling-started]))

(deftest create-engine-requires-an-explicit-offset-store
  (let [result (core/create-engine {::core/config   {:name "capture"}
                                    ::core/consumer (constantly nil)})]
    (is (= :cognitect.anomalies/incorrect
           (:cognitect.anomalies/category result)))))

(deftest create-engine-requires-a-consumer
  (let [result (core/create-engine {::core/config
                                    {:name           "capture"
                                     :offset.storage "example.OffsetStore"}})]
    (is (= :cognitect.anomalies/incorrect
           (:cognitect.anomalies/category result)))))

(deftest create-engine-returns-an-opaque-closeable-handle
  (let [handle (core/create-engine
                 {::core/config   (ephemeral-postgres-config)
                  ::core/consumer (constantly nil)})]
    (is (instance? java.io.Closeable handle))
    (is (not (instance? Runnable handle)))))

(deftest start-submits-the-private-engine-through-the-supplied-executor
  (let [submitted (atom nil)
        executor  (reify java.util.concurrent.Executor
                    (execute [_ runnable]
                      (reset! submitted runnable)))
        handle    (core/create-engine {::core/config   (ephemeral-postgres-config)
                                       ::core/consumer (constantly nil)})]
    (is (nil? (core/start! handle {:executor executor})))
    (is (instance? Runnable @submitted))
    (is (false? (core/running? handle)))))

(deftest queued-engine-invocation-does-not-begin-after-stop
  (let [runs      (atom 0)
        submitted (atom nil)
        executor  (reify java.util.concurrent.Executor
                    (execute [_ runnable]
                      (reset! submitted runnable)))
        engine    (reify Runnable
                    (run [_] (swap! runs inc))
                    java.io.Closeable
                    (close [_]))
        handle    (debezium_embedded.core.CaptureHandle.
                    engine (atom []) (promise) nil 1)]
    (is (nil? (core/start! handle {:executor executor})))
    (core/stop! handle {:timeout-ms 1})
    (.run ^Runnable @submitted)
    (is (zero? @runs))
    (is (= 1 (count (filter #{::lifecycle/engine-invocation-cancelled}
                      @(.-observations handle)))))))

(deftest submission-failure-after-cancellation-is-diagnostic
  (let [handle-ref (atom nil)
        executor   (reify java.util.concurrent.Executor
                     (execute [_ _]
                       (core/stop! @handle-ref {})
                       (throw (ex-info "executor rejected" {}))))
        handle     (debezium_embedded.core.CaptureHandle.
                     nil (atom []) (promise) nil 1)]
    (reset! handle-ref handle)
    (is (nil? (core/start! handle {:executor executor})))
    (is (nil? (core/stop! handle {})))))

(deftest stop-before-start-needs-no-upstream-shutdown
  (let [handle (core/create-engine {::core/config   (ephemeral-postgres-config)
                                    ::core/consumer (constantly nil)})]
    (is (nil? (core/stop! handle {})))))

(deftest close-is-idempotent-after-completion
  (let [handle (debezium_embedded.core.CaptureHandle.
                 nil
                 (atom [::lifecycle/start-requested
                        ::lifecycle/engine-submission-started
                        ::lifecycle/engine-invocation-started
                        ::lifecycle/stop-requested
                        ::lifecycle/completion-observed])
                 (promise)
                 nil
                 2000)]
    (is (nil? (core/stop! handle {})))
    (is (nil? (.close handle)))))

(deftest stop-does-not-block-an-asynchronous-completion-callback
  (let [observations (atom (capturing-trace))
        completion   (promise)
        callback     (#'debezium-embedded.core/completion-callback
                       observations nil completion)
        engine       (reify java.io.Closeable
                       (close [_]
                         (future
                           (#'debezium-embedded.core/observe!
                             observations nil ::lifecycle/connector-stopped)
                           (.handle callback true "completed" nil))))
        handle       (debezium_embedded.core.CaptureHandle.
                       engine observations completion nil 200)]
    (is (nil? (core/stop! handle {:timeout-ms 100})))
    (is (= ::lifecycle/stopped (lifecycle/phase @observations)))
    (is (some #{::lifecycle/shutdown-request-started} @observations))
    (is (some #{::lifecycle/shutdown-returned} @observations))))

(deftest shutdown-does-not-start-after-completion
  (let [closed       (atom 0)
        observations (atom (into (capturing-trace)
                                 [::lifecycle/stop-requested
                                  ::lifecycle/completion-observed]))
        engine       (reify java.io.Closeable
                       (close [_] (swap! closed inc)))]
    (#'debezium-embedded.core/request-shutdown! observations nil engine)
    (is (zero? @closed))))

(deftest anomaly-after-successful-completion-is-diagnostic
  (let [observations (atom (conj (into (capturing-trace)
                                   [::lifecycle/stop-requested
                                    ::lifecycle/connector-stopped
                                    ::lifecycle/completion-observed])
                             {:observation                  ::lifecycle/shutdown-anomaly
                              :cognitect.anomalies/category :cognitect.anomalies/fault}))
        handle       (debezium_embedded.core.CaptureHandle.
                       nil observations (doto (promise) (deliver true)) nil 1)]
    (is (nil? (core/stop! handle {})))))

(deftest event-hook-receives-wrapper-observations-asynchronously
  (let [events      (atom [])
        delivered?  (promise)
        dispatcher  (#'debezium-embedded.core/event-dispatcher
                      (fn [event]
                        (swap! events conj (::core/observation event))
                        (when (= 2 (count @events))
                          (deliver delivered? true))))
        observations (atom [])]
    (try
      (#'debezium-embedded.core/observe!
        observations (:emit dispatcher) ::lifecycle/start-requested)
      (#'debezium-embedded.core/observe!
        observations (:emit dispatcher) ::lifecycle/engine-submission-started)
      (is (true? (deref delivered? 1000 false)))
      (is (= [::lifecycle/start-requested
              ::lifecycle/engine-submission-started]
             @events))
      (finally
        ((:shutdown dispatcher))))))

(deftest polling-start-retries-a-rejected-startup-shutdown-once
  (let [observations (atom (into (started-engine-trace)
                                 [::lifecycle/connector-started
                                  ::lifecycle/stop-requested
                                  ::lifecycle/shutdown-anomaly]))
        retries      (atom 0)
        callback     (#'debezium-embedded.core/connector-callback
                       observations
                       nil
                       #(swap! retries inc))]
    (.pollingStarted callback)
    (.pollingStarted callback)
    (is (= 1 @retries))))

(deftest source-records-keep-the-existing-event-map-shape
  (let [record (org.apache.kafka.connect.source.SourceRecord.
                 {"server" "postgres"}
                 {"lsn" 42}
                 "test"
                 nil
                 nil)
        event  (#'debezium-embedded.core/source-record->map record)]
    (is (= {:lsn 42} (:offset event)))
    (is (nil? (:value event)))))

(deftest consumer-failure-does-not-acknowledge-a-batch
  (let [observations (atom (capturing-trace))
        acknowledgements (atom [])
        committer (reify io.debezium.engine.DebeziumEngine$RecordCommitter
                    (markProcessed [_ record]
                      (swap! acknowledgements conj [:record record]))
                    (markBatchFinished [_]
                      (swap! acknowledgements conj :batch))
                    (markProcessed [_ record _]
                      (swap! acknowledgements conj [:record record]))
                    (buildOffsets [_] nil))
        consumer (#'debezium-embedded.core/batch-consumer
                   observations
                   nil
                   (fn [_] (throw (ex-info "consumer failed" {}))))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (.handleBatch consumer [] committer)))
    (is (empty? @acknowledgements))
    (is (= :cognitect.anomalies/fault
           (:cognitect.anomalies/category
             (lifecycle/terminal-anomaly @observations))))))

(deftest handled-batch-is-finished-after-consumer-returns
  (let [observations (atom (capturing-trace))
        acknowledgements (atom [])
        committer (reify io.debezium.engine.DebeziumEngine$RecordCommitter
                    (markProcessed [_ _])
                    (markBatchFinished [_] (swap! acknowledgements conj :batch))
                    (markProcessed [_ _ _])
                    (buildOffsets [_] nil))
        consumer (#'debezium-embedded.core/batch-consumer observations nil (constantly nil))]
    (.handleBatch consumer [] committer)
    (is (= [:batch] @acknowledgements))))

(deftest stop-recorded-by-the-consumer-forbids-later-acknowledgement
  (let [observations     (atom (capturing-trace))
        acknowledgements (atom [])
        committer        (reify io.debezium.engine.DebeziumEngine$RecordCommitter
                           (markProcessed [_ record]
                             (swap! acknowledgements conj [:record record]))
                           (markBatchFinished [_]
                             (swap! acknowledgements conj :batch))
                           (markProcessed [_ record _]
                             (swap! acknowledgements conj [:record record]))
                           (buildOffsets [_] nil))
        consumer         (#'debezium-embedded.core/batch-consumer
                           observations
                           nil
                           (fn [_]
                             (#'debezium-embedded.core/observe!
                               observations nil ::lifecycle/stop-requested)))]
    (.handleBatch consumer [] committer)
    (is (empty? @acknowledgements))
    (is (false? (lifecycle/admitting? @observations)))))

(deftest acknowledgement-failure-is-not-a-consumer-failure
  (let [observations (atom (capturing-trace))
        committer    (reify io.debezium.engine.DebeziumEngine$RecordCommitter
                       (markProcessed [_ _])
                       (markBatchFinished [_] (throw (ex-info "acknowledgement failed" {})))
                       (markProcessed [_ _ _])
                       (buildOffsets [_] nil))
        consumer     (#'debezium-embedded.core/batch-consumer observations nil (constantly nil))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (.handleBatch consumer [] committer)))
    (is (= ::lifecycle/acknowledgement-anomaly
           (:observation (first (filter map? @observations)))))))
