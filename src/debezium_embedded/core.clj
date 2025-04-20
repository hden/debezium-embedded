(ns debezium-embedded.core
  (:require [camel-snake-kebab.core :as csk])
  (:import (java.util Properties)
           (io.debezium.embedded EmbeddedEngine
                                 EmbeddedEngine$EngineBuilder)
           (io.debezium.engine DebeziumEngine$ChangeConsumer
                               DebeziumEngine$CompletionCallback
                               DebeziumEngine$ConnectorCallback)
           (org.apache.kafka.connect.source SourceRecord)
           (org.apache.kafka.connect.data Struct)))

(defn- map->properties [m]
  (let [props (new Properties)]
    (doseq [[k v] m]
      (.setProperty props (name k) (str v)))
    props))

(defn- struct->map [^Struct struct]
  (when struct
    (into {}
          (map (fn [field]
                 (let [field-name (.name field)]
                   [(csk/->kebab-case-keyword field-name)
                    (let [value (.get struct field-name)]
                      (if (instance? Struct value)
                        (struct->map value)
                        value))]))
               (.fields (.schema struct))))))

(defn- source-record->map [^SourceRecord record]
  {:offset (into {} (map (fn [[k v]] [(csk/->kebab-case-keyword k) v]) (.sourceOffset record)))
   :value (struct->map (.value record))})

(defn- create-batch-consumer [f]
  (reify DebeziumEngine$ChangeConsumer
    (handleBatch [_ records committer]
      (f (into [] (map source-record->map) records))
      ;; mark all records as processed
      (doseq [record records]
        (.markProcessed committer record))
      (.markBatchFinished committer))))

(defn- create-completion-callback
  "Handle the completion of the embedded connector engine."
  [f]
  (reify DebeziumEngine$CompletionCallback
    (handle [_ _ message error]
      (f error message))))

(defn- create-connector-callback
  "Callback function which informs users about the various stages a connector goes through during startup."
  [f]
  (reify DebeziumEngine$ConnectorCallback
    (connectorStarted [_]
      (f ::connector-started))
    (connectorStopped [_]
      (f ::connector-stopped))
    (taskStarted [_]
      (f ::task-started))
    (taskStopped [_]
      (f ::task-stopped))))

(def default-config
  {:offset.storage "org.apache.kafka.connect.storage.MemoryOffsetBackingStore"
   :offset.flush.interval.ms "0"})

(defn create-engine [{:keys [config consumer]
                      :as arg-map}]
  (let [builder (new EmbeddedEngine$EngineBuilder)]
    ;; Setting required parameters.
    (doto builder
      (.using (map->properties (merge default-config config)))
      (.notifying (create-batch-consumer consumer)))
    ;; Setting callbacks.
    (when-let [callback (get arg-map :completion-callback)]
      (.using builder (create-completion-callback callback)))
    (when-let [callback (get arg-map :connector-callback)]
      (.using builder (create-connector-callback callback)))
    (.build builder)))

(defn running? [^EmbeddedEngine engine]
  (.isRunning engine))
