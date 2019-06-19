(ns debezium-embedded.core
  (:import [io.debezium.config Configuration]
           [io.debezium.embedded.spi OffsetCommitPolicy]
           [io.debezium.embedded EmbeddedEngine
                                 EmbeddedEngine$ChangeConsumer
                                 EmbeddedEngine$CompletionCallback
                                 EmbeddedEngine$ConnectorCallback]))

(defn create-configuration [m]
  (Configuration/from m))

(defn create-always-offset-commit-policy []
  (OffsetCommitPolicy/always))

(defn create-periodic-offset-commit-policy [x]
  (let [config (create-configuration {"offset.flush.interval.ms" (str x)})]
    (OffsetCommitPolicy/periodic config)))

(defn create-batch-consumer [f]
  (reify EmbeddedEngine$ChangeConsumer
    (handleBatch [_ records committer]
      (f records committer)
      (.markBatchFinished committer))))

(defn mark-processed! [committer record]
  (.markProcessed committer record))

(defn create-completion-callback
  "Handle the completion of the embedded connector engine."
  [f]
  (reify EmbeddedEngine$CompletionCallback
    (handle [_ _ message error]
      (f error message))))

(defn create-connector-callback
  "Callback function which informs users about the various stages a connector goes through during startup."
  [f]
  (reify EmbeddedEngine$ConnectorCallback
    (connectorStarted [_]
      (f ::connector-started))
    (connectorStopped [_]
      (f ::connector-stopped))
    (taskStarted [_]
      (f ::task-started))
    (taskStopped [_]
      (f ::task-stopped))))

(defn create-engine [{:keys [config consumer] :as options}]
  (let [builder (EmbeddedEngine/create)]
    ;; Setting required parameters.
    (doto builder
      (.using (create-configuration config))
      (.notifying consumer))
    ;; Setting optional parameters.
    (doseq [key [:completion-callback :connector-callback :offset-commit-policy]]
      (when-let [callback (get options key)]
        (.using builder callback)))
    (.build builder)))
