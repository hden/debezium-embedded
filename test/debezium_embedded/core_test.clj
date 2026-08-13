(ns debezium-embedded.core-test
  (:require
   [clojure.test :refer [deftest is]]
   [debezium-embedded.core :as core]))

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

(defn- handle [engine latest-event completion]
  (debezium_embedded.core.CaptureHandle.
    engine latest-event completion nil (atom false) 100))

(deftest polling-is-derived-from-the-latest-debezium-callback
  (is (true? (core/polling?
               (handle nil (atom {:event ::core/polling-started}) (promise)))))
  (is (false? (core/polling?
                (handle nil (atom {:event ::core/polling-stopped}) (promise)))))
  (is (false? (core/polling? (handle nil (atom nil) (promise))))))

(deftest connector-callback-replaces-the-latest-event
  (let [latest-event (atom nil)
        callback     (#'core/connector-callback latest-event nil)]
    (.pollingStarted callback)
    (is (= {:event ::core/polling-started} @latest-event))
    (.pollingStopped callback)
    (is (= {:event ::core/polling-stopped} @latest-event))))

(deftest completion-replaces-a-polling-event
  (let [latest-event (atom {:event ::core/polling-started})
        completion   (promise)
        callback     (#'core/completion-callback latest-event nil completion)]
    (.handle callback true "completed" nil)
    (is (= {:event ::core/completed :success? true} (core/latest-event
                                                      (handle nil latest-event completion))))
    (is (false? (core/polling? (handle nil latest-event completion))))))

(deftest failed-completion-is-an-explicit-callback-fact
  (let [cause        (ex-info "upstream failed" {})
        latest-event (atom nil)
        completion   (promise)
        callback     (#'core/completion-callback latest-event nil completion)]
    (.handle callback false "connector failed" cause)
    (is (= {:event     ::core/completed
            :success?  false
            :cognitect.anomalies/category :cognitect.anomalies/fault
            :cognitect.anomalies/message  "connector failed"
            :debezium-embedded/cause      cause}
           @latest-event))))

(deftest create-engine-requires-an-explicit-offset-store
  (is (= :cognitect.anomalies/incorrect
         (:cognitect.anomalies/category
           (core/create-engine {::core/config   {:name "capture"}
                                ::core/consumer (constantly nil)})))))

(deftest create-engine-requires-a-consumer
  (is (= :cognitect.anomalies/incorrect
         (:cognitect.anomalies/category
           (core/create-engine {::core/config
                                {:name           "capture"
                                 :offset.storage "example.OffsetStore"}})))))

(deftest create-engine-returns-an-opaque-closeable-handle
  (let [handle (core/create-engine {::core/config   (ephemeral-postgres-config)
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
    (is (= :cognitect.anomalies/incorrect
           (:cognitect.anomalies/category (core/start! handle {:executor executor}))))))

(deftest rejected-submission-leaves-the-handle-retryable
  (let [submitted (atom nil)
        rejecting (reify java.util.concurrent.Executor
                    (execute [_ _]
                      (throw (ex-info "rejected" {}))))
        accepting (reify java.util.concurrent.Executor
                    (execute [_ runnable]
                      (reset! submitted runnable)))
        handle    (core/create-engine {::core/config   (ephemeral-postgres-config)
                                       ::core/consumer (constantly nil)})]
    (is (= :cognitect.anomalies/fault
           (:cognitect.anomalies/category (core/start! handle {:executor rejecting}))))
    (is (nil? (core/start! handle {:executor accepting})))
    (is (instance? Runnable @submitted))))

(deftest stop-before-start-is-a-no-op
  (let [closed (atom false)
        handle (handle (reify java.io.Closeable
                         (close [_] (reset! closed true)))
                       (atom nil)
                       (promise))]
    (is (nil? (core/stop! handle {})))
    (is (false? @closed))))

(deftest stop-waits-for-a-successful-debezium-completion
  (let [completion (promise)
        engine     (reify java.io.Closeable
                     (close [_]
                       (future (deliver completion {:event ::core/completed
                                                    :success? true}))))
        handle     (handle engine (atom {:event ::core/polling-started}) completion)]
    (reset! (.-started? handle) true)
    (is (nil? (core/stop! handle {:timeout-ms 100})))))

(deftest failed-completion-is-returned-as-an-anomaly
  (let [failure    {:event                        ::core/completed
                    :cognitect.anomalies/category :cognitect.anomalies/fault
                    :cognitect.anomalies/message  "connector failed"
                    :debezium-embedded/cause      :upstream}
        completion (doto (promise) (deliver failure))
        handle     (handle nil (atom failure) completion)]
    (reset! (.-started? handle) true)
    (is (= {:cognitect.anomalies/category :cognitect.anomalies/fault
            :cognitect.anomalies/message  "connector failed"
            :debezium-embedded/cause      :upstream}
           (core/stop! handle {})))))

(deftest unconfirmed-shutdown-is-observable-without-replacing-the-latest-callback
  (let [delivered?  (promise)
        dispatcher  (#'core/event-dispatcher #(deliver delivered? %))
        latest-event (atom {:event ::core/polling-started})
        handle      (debezium_embedded.core.CaptureHandle.
                      (reify java.io.Closeable
                        (close [_]))
                      latest-event
                      (promise)
                      dispatcher
                      (atom true)
                      1)]
    (try
      (is (= :cognitect.anomalies/unavailable
             (:cognitect.anomalies/category (core/stop! handle {}))))
      (is (= {::core/event       ::core/event-observed
              ::core/observation {:event                        ::core/shutdown-unconfirmed
                                  :cognitect.anomalies/category :cognitect.anomalies/unavailable
                                  :cognitect.anomalies/message  "Engine shutdown remained unconfirmed"}}
             (deref delivered? 1000 nil)))
      (is (= {:event ::core/polling-started} (core/latest-event handle)))
      (finally
        ((:shutdown dispatcher))))))

(deftest source-records-keep-the-existing-event-map-shape
  (let [record (org.apache.kafka.connect.source.SourceRecord.
                 {"server" "postgres"}
                 {"lsn" 42}
                 "test"
                 nil
                 nil)
        event  (#'core/source-record->map record)]
    (is (= {:lsn 42} (:offset event)))
    (is (nil? (:value event)))))

(deftest consumer-failure-does-not-acknowledge-a-batch
  (let [acknowledgements (atom [])
        committer        (reify io.debezium.engine.DebeziumEngine$RecordCommitter
                           (markProcessed [_ record]
                             (swap! acknowledgements conj [:record record]))
                           (markBatchFinished [_]
                             (swap! acknowledgements conj :batch))
                           (markProcessed [_ record _]
                             (swap! acknowledgements conj [:record record]))
                           (buildOffsets [_] nil))
        consumer         (#'core/batch-consumer nil (fn [_] (throw (ex-info "failed" {}))))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (.handleBatch consumer [] committer)))
    (is (empty? @acknowledgements))))

(deftest consumer-success-is-acknowledged-after-it-returns
  (let [events           (atom [])
        committer        (reify io.debezium.engine.DebeziumEngine$RecordCommitter
                           (markProcessed [_ _])
                           (markBatchFinished [_] (swap! events conj :acknowledged))
                           (markProcessed [_ _ _])
                           (buildOffsets [_] nil))
        consumer         (#'core/batch-consumer nil
                           (fn [_] (swap! events conj :consumed)))]
    (.handleBatch consumer [] committer)
    (is (= [:consumed :acknowledged] @events))))

(deftest event-hook-receives-observations-asynchronously
  (let [delivered? (promise)
        dispatcher (#'core/event-dispatcher
                     (fn [event]
                       (deliver delivered? event)))]
    (try
      ((:emit dispatcher) {:event ::core/polling-started})
      (is (= {::core/event       ::core/event-observed
              ::core/observation {:event ::core/polling-started}}
             (deref delivered? 1000 nil)))
      (finally
        ((:shutdown dispatcher))))))
