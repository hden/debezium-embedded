(ns debezium-embedded.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [debezium-embedded.core :refer [running? create-engine]]
            [promesa.core :as promesa])
  (:import (java.util.concurrent Executors ExecutorService)
           (io.debezium.embedded EmbeddedEngine)))

(deftest factories
  (testing "create-engine"
    (let [config {}
          consumer println]
      (is (create-engine {:config config
                          :consumer consumer})))))

(def ^:private ^ExecutorService thread-pool (Executors/newFixedThreadPool 1))

(deftest ^:integration postgres
  (testing "engine"
    (let [config {:name "debezium-engine"
                  :connector.class "io.debezium.connector.postgresql.PostgresConnector"
                  :database.hostname "localhost"
                  :database.port "5432"
                  :database.user "postgres"
                  :database.password "postgres"
                  :database.dbname "postgres"
                  :schema.include.list "inventory"
                  :topic.prefix "test"
                  :plugin.name "pgoutput"
                  :offset.storage "org.apache.kafka.connect.storage.MemoryOffsetBackingStore"
                  :offset.flush.interval.ms "0"
                  :converter.schemas.enable "false"}
          events (promesa/deferred)
          consumer (fn [records]
                     (promesa/resolve! events records))]
     (with-open [^EmbeddedEngine engine (create-engine {:config config
                                                       :consumer consumer})]
       (is (not (nil? engine)))
       (.execute thread-pool engine)
       (let [resolved-events (promesa/await events 2000)]
         (is (running? engine))
         (is (vector? resolved-events))
         (is (seq resolved-events)))))))
