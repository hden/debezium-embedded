(ns debezium-embedded.core
  (:import (java.util Properties)
           (io.debezium.embedded EmbeddedEngine$EngineBuilder)
           (io.debezium.engine DebeziumEngine$ChangeConsumer
                               DebeziumEngine$CompletionCallback
                               DebeziumEngine$ConnectorCallback)))

(defn- map->properties [m]
  (let [props (new Properties)]
    (doseq [[k v] m]
      (.setProperty props (name k) (str v)))
    props))

(defn- create-batch-consumer [f]
  (reify DebeziumEngine$ChangeConsumer
    (handleBatch [_ records committer]
      (f (into [] records))
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

(defn create-engine [{:keys [config consumer]
                      :as options}]
  (let [builder (new EmbeddedEngine$EngineBuilder)]
    ;; Setting required parameters.
    (doto builder
      (.using (map->properties config))
      (.notifying (create-batch-consumer consumer)))
    ;; Setting callbacks.
    (when-let [callback (get options :completion-callback)]
      (.using builder (create-completion-callback callback)))
    (when-let [callback (get options :connector-callback)]
      (.using builder (create-connector-callback callback)))
    (.build builder)))
