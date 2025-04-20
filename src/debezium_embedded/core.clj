(ns debezium-embedded.core
  (:require [camel-snake-kebab.core :as csk])
  (:import (java.util Properties)
           (io.debezium.embedded EmbeddedEngine
                                 EmbeddedEngine$EngineBuilder)
           (io.debezium.engine DebeziumEngine$ChangeConsumer
                               DebeziumEngine$CompletionCallback
                               DebeziumEngine$ConnectorCallback)
           (org.apache.kafka.connect.source SourceRecord)
           (org.apache.kafka.connect.data Struct Field)))

(defn- map->properties [m]
  (let [props (Properties.)]
    (doseq [[k v] m]
      (.setProperty props (name k) (str v)))
    props))

(defn- struct->map [^Struct struct]
  (when struct
    (into {}
          (map (fn [^Field field]
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

(defn create-engine
  "Creates a new Debezium embedded engine instance.
  
  Arguments:
  - arg-map: A map containing the following keys:
    * :config - Configuration map for the Debezium engine
    * :consumer - Function to handle change events
    * :completion-callback - Optional callback for engine completion
    * :connector-callback - Optional callback for connector lifecycle events
  
  Returns:
  An instance of EmbeddedEngine ready to be started."
  [{:keys [config consumer]
    :as arg-map}]
  (let [^EmbeddedEngine$EngineBuilder builder (EmbeddedEngine$EngineBuilder.)]
    ;; Setting required parameters.
    (doto builder
      (.using ^Properties (map->properties (merge default-config config)))
      (.notifying ^DebeziumEngine$ChangeConsumer (create-batch-consumer consumer)))
    ;; Setting callbacks.
    (when-let [callback (get arg-map :completion-callback)]
      (.using builder ^DebeziumEngine$CompletionCallback (create-completion-callback callback)))
    (when-let [callback (get arg-map :connector-callback)]
      (.using builder ^DebeziumEngine$ConnectorCallback (create-connector-callback callback)))
    (.build builder)))

(defn running?
  "Checks if the given Debezium engine is currently running.
  
  Arguments:
  - engine: An instance of EmbeddedEngine
  
  Returns:
  true if the engine is running, false otherwise."
  [^EmbeddedEngine engine]
  (.isRunning engine))
