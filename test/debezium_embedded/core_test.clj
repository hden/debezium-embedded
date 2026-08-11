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

(deftest stop-before-start-needs-no-upstream-shutdown
  (let [handle (core/create-engine {::core/config   (ephemeral-postgres-config)
                                    ::core/consumer (constantly nil)})]
    (is (nil? (core/stop! handle {})))))

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
  (let [observations (atom [::lifecycle/start-requested
                            ::lifecycle/run-submitted
                            ::lifecycle/polling-started])
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
                   (fn [_] (throw (ex-info "consumer failed" {}))))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (.handleBatch consumer [] committer)))
    (is (empty? @acknowledgements))
    (is (= :cognitect.anomalies/fault
           (:cognitect.anomalies/category
             (lifecycle/primary-anomaly @observations))))))

(deftest handled-batch-is-finished-after-consumer-returns
  (let [observations (atom [::lifecycle/start-requested
                            ::lifecycle/run-submitted
                            ::lifecycle/polling-started])
        acknowledgements (atom [])
        committer (reify io.debezium.engine.DebeziumEngine$RecordCommitter
                    (markProcessed [_ _])
                    (markBatchFinished [_] (swap! acknowledgements conj :batch))
                    (markProcessed [_ _ _])
                    (buildOffsets [_] nil))
        consumer (#'debezium-embedded.core/batch-consumer observations (constantly nil))]
    (.handleBatch consumer [] committer)
    (is (= [:batch] @acknowledgements))))

(deftest acknowledgement-failure-is-not-a-consumer-failure
  (let [observations (atom [::lifecycle/start-requested
                            ::lifecycle/run-submitted
                            ::lifecycle/polling-started])
        committer    (reify io.debezium.engine.DebeziumEngine$RecordCommitter
                       (markProcessed [_ _])
                       (markBatchFinished [_] (throw (ex-info "acknowledgement failed" {})))
                       (markProcessed [_ _ _])
                       (buildOffsets [_] nil))
        consumer     (#'debezium-embedded.core/batch-consumer observations (constantly nil))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (.handleBatch consumer [] committer)))
    (is (= ::lifecycle/acknowledgement-anomaly
           (:observation (first (filter map? @observations)))))))
