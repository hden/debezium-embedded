(ns debezium-embedded.core
  (:require
   [camel-snake-kebab.core :as csk]
   [debezium-embedded.lifecycle :as lifecycle])
  (:import
   (io.debezium.embedded Connect)
   (io.debezium.engine DebeziumEngine DebeziumEngine$ChangeConsumer DebeziumEngine$CompletionCallback DebeziumEngine$ConnectorCallback)
   (io.debezium.engine.format ChangeEventFormat)
   (java.io Closeable)
   (java.util Properties)
   (java.util.concurrent Executor ForkJoinPool)
   (org.apache.kafka.connect.data Field Struct)
   (org.apache.kafka.connect.source SourceRecord)))

(declare stop!)

(deftype CaptureHandle [engine observations completion default-shutdown-timeout-ms]
  Closeable
  (close [this]
    (when-let [anomaly (stop! this {})]
      (throw (ex-info "Unable to stop Debezium capture" anomaly)))))

(defn- incorrect [message]
  {:cognitect.anomalies/category :cognitect.anomalies/incorrect
   :cognitect.anomalies/message  message})

(defn- map->properties [config]
  (let [properties (Properties.)]
    (doseq [[key value] config]
      (.setProperty properties (name key) (str value)))
    properties))

(defn- struct->map [^Struct struct]
  (when struct
    (into {}
          (map (fn [^Field field]
                 (let [name  (.name field)
                       value (.get struct name)]
                   [(csk/->kebab-case-keyword name)
                    (if (instance? Struct value)
                      (struct->map value)
                      value)]))
               (.fields (.schema struct))))))

(defn- source-record->map [^SourceRecord record]
  {:offset (into {}
                 (map (fn [[key value]] [(csk/->kebab-case-keyword key) value])
                      (.sourceOffset record)))
   :value  (struct->map (.value record))})

(defn- consumer-anomaly [cause]
  {:observation                  ::lifecycle/consumer-anomaly
   :cognitect.anomalies/category :cognitect.anomalies/fault
   :cognitect.anomalies/message  "Change-event consumer failed"
   :debezium-embedded/cause      cause})

(defn- acknowledgement-anomaly [cause]
  {:observation                  ::lifecycle/acknowledgement-anomaly
   :cognitect.anomalies/category :cognitect.anomalies/fault
   :cognitect.anomalies/message  "Change-event acknowledgement failed"
   :debezium-embedded/cause      cause})

(defn- observe! [observations observation]
  (swap! observations lifecycle/append-observation observation))

(defn- batch-consumer [observations consumer]
  (reify DebeziumEngine$ChangeConsumer
    (handleBatch [_ records committer]
      (locking observations
        (when (lifecycle/admitting? @observations)
          (observe! observations ::lifecycle/batch-admitted)
          (try
            (consumer (mapv #(source-record->map (.record %)) records))
            (observe! observations ::lifecycle/batch-handled)
            (catch Throwable cause
              (observe! observations (consumer-anomaly cause))
              (throw cause)))
          (try
            (doseq [record records]
              (observe! observations ::lifecycle/record-acknowledgement-attempted)
              (.markProcessed committer record)
              (observe! observations ::lifecycle/record-acknowledged))
            (observe! observations ::lifecycle/batch-acknowledgement-attempted)
            (.markBatchFinished committer)
            (observe! observations ::lifecycle/batch-acknowledged)
            (catch Throwable cause
              (observe! observations (acknowledgement-anomaly cause))
              (throw cause))))))))

(defn- completion-callback [observations completion]
  (reify DebeziumEngine$CompletionCallback
    (handle [_ success message error]
      (let [observation (if success
                          ::lifecycle/completion-observed
                          {:observation                  ::lifecycle/completion-observed
                           :cognitect.anomalies/category :cognitect.anomalies/fault
                           :cognitect.anomalies/message  message
                           :debezium-embedded/cause      error})]
        (observe! observations observation)
        (deliver completion true)))))

(defn- connector-callback [observations]
  (reify DebeziumEngine$ConnectorCallback
    (connectorStarted [_]
      (observe! observations ::lifecycle/connector-started))
    (connectorStopped [_]
      (observe! observations ::lifecycle/connector-stopped))
    (pollingStarted [_]
      (observe! observations ::lifecycle/polling-started))
    (pollingStopped [_]
      (observe! observations ::lifecycle/polling-stopped))))

(defn create-engine [arg-map]
  (let [config                      (::config arg-map)
        consumer                    (::consumer arg-map)
        default-shutdown-timeout-ms (get arg-map ::default-shutdown-timeout-ms 2000)]
    (cond
      (nil? config) (incorrect "Missing Debezium configuration")
      (nil? consumer) (incorrect "Missing change-event consumer")
      (nil? (:offset.storage config)) (incorrect "Missing :offset.storage")
      :else
      (let [observations (atom [])
            completion   (promise)
            engine       (-> (DebeziumEngine/create (ChangeEventFormat/of Connect))
                           (.using (map->properties config))
                           (.notifying (batch-consumer observations consumer))
                           (.using (completion-callback observations completion))
                           (.using (connector-callback observations))
                           (.build))]
        (CaptureHandle. engine observations completion default-shutdown-timeout-ms)))))

(defn- fault [message cause]
  {:cognitect.anomalies/category :cognitect.anomalies/fault
   :cognitect.anomalies/message  message
   :debezium-embedded/cause      cause})

(defn start! [^CaptureHandle handle {:keys [executor]}]
  (let [observations (.-observations handle)
        engine       (.-engine handle)
        executor     (or executor (ForkJoinPool/commonPool))]
    (locking observations
      (if (not= ::lifecycle/ready (lifecycle/phase @observations))
        (incorrect "Engine cannot be started from its current lifecycle phase")
        (do
          (swap! observations conj ::lifecycle/start-requested)
          (try
            (.execute ^Executor executor ^Runnable engine)
            (swap! observations conj ::lifecycle/run-submitted)
            nil
            (catch Throwable cause
              (swap! observations conj {:observation                  ::lifecycle/run-submission-anomaly
                                        :cognitect.anomalies/category :cognitect.anomalies/fault
                                        :cognitect.anomalies/message  "Engine submission failed"
                                        :debezium-embedded/cause      cause})
              (fault "Engine submission failed" cause))))))))

(defn running? [^CaptureHandle handle]
  (lifecycle/admitting? @(.-observations handle)))

(defn stop! [^CaptureHandle handle {:keys [timeout-ms]}]
  (let [observations (.-observations handle)
        timeout-ms   (or timeout-ms (.-default-shutdown-timeout-ms handle))]
    (locking observations
      (if (= ::lifecycle/ready (lifecycle/phase @observations))
        (do
          (swap! observations conj ::lifecycle/stop-requested)
          nil)
        (do
          (swap! observations conj ::lifecycle/stop-requested)
          (try
            (.close ^Closeable (.-engine handle))
            (catch Throwable cause
              (swap! observations conj {:observation                  ::lifecycle/shutdown-anomaly
                                        :cognitect.anomalies/category :cognitect.anomalies/fault
                                        :cognitect.anomalies/message  "Engine shutdown failed"
                                        :debezium-embedded/cause      cause})))
          (if (deref (.-completion handle) timeout-ms ::timed-out)
            (lifecycle/primary-anomaly @observations)
            (let [anomaly {:cognitect.anomalies/category :cognitect.anomalies/unavailable
                           :cognitect.anomalies/message  "Engine shutdown remained unconfirmed"}]
              (swap! observations conj (assoc anomaly :observation ::lifecycle/shutdown-unconfirmed))
              anomaly)))))))
