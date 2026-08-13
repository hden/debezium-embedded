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

(defn- capture-handle [engine latest-event completion]
  (debezium_embedded.core.CaptureHandle.
    engine latest-event (promise) completion nil (atom false) (atom false) (atom false) 100))

(deftest polling-is-derived-from-the-latest-debezium-callback
  (is (true? (core/polling?
               (capture-handle nil (atom {:event ::core/polling-started}) (promise)))))
  (is (false? (core/polling?
                (capture-handle nil (atom {:event ::core/polling-stopped}) (promise)))))
  (is (false? (core/polling? (capture-handle nil (atom nil) (promise))))))

(deftest connector-callback-replaces-the-latest-event
  (let [latest-event (atom nil)
        callback     (#'core/connector-callback latest-event nil (promise) (atom false) (constantly nil))]
    (.taskStarted callback)
    (is (= {:event ::core/task-started} @latest-event))
    (.taskStopped callback)
    (is (= {:event ::core/task-stopped} @latest-event))
    (.pollingStarted callback)
    (is (= {:event ::core/polling-started} @latest-event))
    (.pollingStopped callback)
    (is (= {:event ::core/polling-stopped} @latest-event))))

(deftest completion-replaces-a-polling-event
  (let [latest-event (atom {:event ::core/polling-started})
        start-result (promise)
        completion   (promise)
        callback     (#'core/completion-callback latest-event nil start-result completion)]
    (.handle callback true "completed" nil)
    (is (= {:event ::core/completed :success? true} (core/latest-event
                                                      (capture-handle nil latest-event completion))))
    (is (false? (core/polling? (capture-handle nil latest-event completion))))))

(deftest failed-completion-is-an-explicit-callback-fact
  (let [cause        (ex-info "upstream failed" {})
        latest-event (atom nil)
        start-result (promise)
        completion   (promise)
        callback     (#'core/completion-callback latest-event nil start-result completion)]
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

(deftest start-waits-for-polling-and-rejects-a-duplicate-start
  (let [submitted (promise)
        executor  (reify java.util.concurrent.Executor
                    (execute [_ runnable]
                      (deliver submitted runnable)))
        handle    (capture-handle (reify Runnable (run [_])) (atom nil) (promise))
        callback  (#'core/connector-callback (.-latest-event handle)
                    nil
                    (.-start-result handle)
                    (.-stop-requested? handle)
                    (constantly nil))
        starting  (future (core/start! handle {:executor executor}))]
    (is (instance? Runnable (deref submitted 1000 nil)))
    (is (= :cognitect.anomalies/incorrect
           (:cognitect.anomalies/category (core/start! handle {:executor executor}))))
    (is (= ::pending (deref starting 100 ::pending)))
    (.pollingStarted callback)
    (is (nil? (deref starting 1000 ::timed-out)))
    (is (true? (core/polling? handle)))))

(deftest rejected-submission-leaves-the-handle-retryable
  (let [submitted (promise)
        rejecting (reify java.util.concurrent.Executor
                    (execute [_ _]
                      (throw (ex-info "rejected" {}))))
        accepting (reify java.util.concurrent.Executor
                    (execute [_ runnable]
                      (deliver submitted runnable)))
        handle    (capture-handle (reify Runnable (run [_])) (atom nil) (promise))]
    (is (= :cognitect.anomalies/fault
           (:cognitect.anomalies/category (core/start! handle {:executor rejecting}))))
    (let [starting (future (core/start! handle {:executor accepting}))]
      (is (instance? Runnable (deref submitted 1000 nil)))
      (deliver (.-start-result handle) {:event ::core/polling-started})
      (is (nil? (deref starting 1000 ::timed-out))))))

(deftest failed-start-is-returned-as-an-anomaly
  (let [submitted (promise)
        executor  (reify java.util.concurrent.Executor
                    (execute [_ runnable]
                      (deliver submitted runnable)))
        cause     (ex-info "connector failed" {})
        handle    (capture-handle (reify Runnable (run [_])) (atom nil) (promise))
        callback  (#'core/completion-callback (.-latest-event handle)
                    nil
                    (.-start-result handle)
                    (.-completion handle))
        starting  (future (core/start! handle {:executor executor}))]
    (is (instance? Runnable (deref submitted 1000 nil)))
    (.handle callback false "connector failed" cause)
    (is (= {:cognitect.anomalies/category :cognitect.anomalies/fault
            :cognitect.anomalies/message  "connector failed"
            :debezium-embedded/cause      cause}
           (deref starting 1000 ::timed-out)))))

(deftest completion-before-polling-is-returned-as-an-unavailable-start
  (let [submitted (promise)
        executor  (reify java.util.concurrent.Executor
                    (execute [_ runnable]
                      (deliver submitted runnable)))
        handle    (capture-handle (reify Runnable (run [_])) (atom nil) (promise))
        callback  (#'core/completion-callback (.-latest-event handle)
                    nil
                    (.-start-result handle)
                    (.-completion handle))
        starting  (future (core/start! handle {:executor executor}))]
    (is (instance? Runnable (deref submitted 1000 nil)))
    (.handle callback true "completed before polling" nil)
    (is (= {:cognitect.anomalies/category :cognitect.anomalies/unavailable
            :cognitect.anomalies/message  "Engine completed before polling started"}
           (deref starting 1000 ::timed-out)))))

(deftest stop-before-start-is-a-no-op
  (let [closed (atom false)
        handle (capture-handle (reify java.io.Closeable
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
        handle     (capture-handle engine (atom {:event ::core/polling-started}) completion)]
    (reset! (.-started? handle) true)
    (deliver (.-start-result handle) {:event ::core/polling-started})
    (is (nil? (core/stop! handle {:timeout-ms 100})))))

(deftest stop-during-startup-closes-after-polling-starts
  (let [completion   (promise)
        close-count  (atom 0)
        latest-event (atom nil)
        engine       (reify java.io.Closeable
                       (close [_]
                         (swap! close-count inc)
                         (deliver completion {:event ::core/completed
                                              :success? true})))
        handle       (capture-handle engine latest-event completion)
        callback     (#'core/connector-callback latest-event
                                                nil
                                                (.-start-result handle)
                                                (.-stop-requested? handle)
                                                #(#'core/close-engine! engine
                                                                       (.-shutdown-issued? handle)))
        stop-requested (promise)]
    (reset! (.-started? handle) true)
    (add-watch (.-stop-requested? handle) ::stop-requested
               (fn [_ _ _ requested?]
                 (when requested?
                   (deliver stop-requested true))))
    (try
      (let [stopping (future (core/stop! handle {:timeout-ms 1000}))]
        (is (true? (deref stop-requested 1000 false)))
        (is (zero? @close-count))
        (.pollingStarted callback)
        (is (nil? (deref stopping 1000 ::timed-out)))
        (is (= 1 @close-count)))
      (finally
        (remove-watch (.-stop-requested? handle) ::stop-requested)))))

(deftest stop-closes-when-polling-started-unblocks-its-wait
  (let [completion   (promise)
        close-count  (atom 0)
        latest-event (atom {:event ::core/polling-started})
        engine       (reify java.io.Closeable
                       (close [_]
                         (swap! close-count inc)
                         (deliver completion {:event ::core/completed
                                              :success? true})))
        handle       (capture-handle engine latest-event completion)
        stop-requested (promise)]
    (reset! (.-started? handle) true)
    (add-watch (.-stop-requested? handle) ::stop-requested
               (fn [_ _ _ requested?]
                 (when requested?
                   (deliver stop-requested true))))
    (try
      (let [stopping (future (core/stop! handle {:timeout-ms 1000}))]
        (is (true? (deref stop-requested 1000 false)))
        (deliver (.-start-result handle) {:event ::core/polling-started})
        (is (nil? (deref stopping 1000 ::timed-out)))
        (is (= 1 @close-count)))
      (finally
        (remove-watch (.-stop-requested? handle) ::stop-requested)))))

(deftest stop-during-startup-applies-its-timeout-after-polling-starts
  (let [completion   (promise)
        latest-event (atom nil)
        engine       (reify java.io.Closeable
                       (close [_]
                         (deliver completion {:event ::core/completed
                                              :success? true})))
        handle       (capture-handle engine latest-event completion)
        callback     (#'core/connector-callback latest-event
                                                nil
                                                (.-start-result handle)
                                                (.-stop-requested? handle)
                                                #(#'core/close-engine! engine
                                                                       (.-shutdown-issued? handle)))]
    (reset! (.-started? handle) true)
    (let [stopping (future (core/stop! handle {:timeout-ms 1}))]
      (Thread/sleep 50)
      (.pollingStarted callback)
      (is (nil? (deref stopping 1000 ::timed-out))))))

(deftest failed-completion-is-returned-as-an-anomaly
  (let [failure    {:event                        ::core/completed
                    :cognitect.anomalies/category :cognitect.anomalies/fault
                    :cognitect.anomalies/message  "connector failed"
                    :debezium-embedded/cause      :upstream}
        completion (doto (promise) (deliver failure))
        handle     (capture-handle nil (atom failure) completion)]
    (reset! (.-started? handle) true)
    (is (= {:cognitect.anomalies/category :cognitect.anomalies/fault
            :cognitect.anomalies/message  "connector failed"
            :debezium-embedded/cause      :upstream}
           (core/stop! handle {})))))

(deftest unconfirmed-shutdown-is-observable-without-replacing-the-latest-callback
  (let [received-event (promise)
        dispatcher     (#'core/event-dispatcher #(deliver received-event %))
        latest-event (atom {:event ::core/polling-started})
        handle      (debezium_embedded.core.CaptureHandle.
                      (reify java.io.Closeable
                        (close [_]))
                      latest-event
                      (promise)
                      (promise)
                      dispatcher
                      (atom true)
                      (atom false)
                      (atom false)
                      1)]
    (try
      (deliver (.-start-result handle) {:event ::core/polling-started})
      (is (= :cognitect.anomalies/unavailable
             (:cognitect.anomalies/category (core/stop! handle {}))))
      (is (= {::core/event       ::core/event-observed
              ::core/observation {:event                        ::core/shutdown-unconfirmed
                                  :cognitect.anomalies/category :cognitect.anomalies/unavailable
                                  :cognitect.anomalies/message  "Engine shutdown remained unconfirmed"}}
             (deref received-event 1000 nil)))
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
  (let [steps            (atom [])
        committer        (reify io.debezium.engine.DebeziumEngine$RecordCommitter
                           (markProcessed [_ _])
                           (markBatchFinished [_] (swap! steps conj :acknowledged))
                           (markProcessed [_ _ _])
                           (buildOffsets [_] nil))
        consumer         (#'core/batch-consumer nil
                           (fn [_] (swap! steps conj :consumed)))]
    (.handleBatch consumer [] committer)
    (is (= [:consumed :acknowledged] @steps))))

(deftest event-hook-receives-events-asynchronously
  (let [received-event (promise)
        dispatcher (#'core/event-dispatcher
                     (fn [event]
                       (deliver received-event event)))]
    (try
      ((:emit dispatcher) {:event ::core/polling-started})
      (is (= {::core/event       ::core/event-observed
              ::core/observation {:event ::core/polling-started}}
             (deref received-event 1000 nil)))
      (finally
        ((:shutdown dispatcher))))))
