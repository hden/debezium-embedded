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
   (java.util.concurrent ArrayBlockingQueue Executor ForkJoinPool ThreadPoolExecutor ThreadPoolExecutor$DiscardPolicy TimeUnit)
   (org.apache.kafka.connect.data Field Struct)
   (org.apache.kafka.connect.source SourceRecord)))

(declare stop!)

(deftype CaptureHandle [engine observations completion event-dispatcher default-shutdown-timeout-ms]
  Closeable
  (close [this]
    (let [result (stop! this {})]
      (when-let [shutdown (:shutdown (.-event-dispatcher this))]
        (shutdown))
      (when result
        (throw (ex-info "Unable to stop Debezium capture" result))))))

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

(defn- observe! [observations emit observation]
  (swap! observations lifecycle/append-observation observation)
  (when emit (emit observation)))

(defn- event-dispatcher [on-event]
  (when on-event
    (let [executor (ThreadPoolExecutor. 1 1 0 TimeUnit/MILLISECONDS
                                        (ArrayBlockingQueue. 64)
                                        (ThreadPoolExecutor$DiscardPolicy.))]
      {:emit     (fn [observation]
                   (.execute executor
                             ^Runnable
                             (fn [] (try (on-event {:debezium-embedded.core/event :debezium-embedded.core/observation-recorded
                                                    :debezium-embedded.core/observation observation})
                                         (catch Throwable _)))))
       :shutdown #(.shutdownNow executor)})))

(defn- batch-consumer [observations emit consumer]
  (reify DebeziumEngine$ChangeConsumer
    (handleBatch [_ records committer]
      (locking observations
        (when (lifecycle/admitting? @observations)
          (observe! observations emit ::lifecycle/batch-admitted)
          (try
            (consumer (mapv #(source-record->map (.record %)) records))
            (observe! observations emit ::lifecycle/batch-handled)
            (catch Throwable cause
              (observe! observations emit (consumer-anomaly cause))
              (throw cause)))
          (try
            (doseq [record records]
              (observe! observations emit ::lifecycle/record-acknowledgement-attempted)
              (.markProcessed committer record)
              (observe! observations emit ::lifecycle/record-acknowledged))
            (observe! observations emit ::lifecycle/batch-acknowledgement-attempted)
            (.markBatchFinished committer)
            (observe! observations emit ::lifecycle/batch-acknowledged)
            (catch Throwable cause
              (observe! observations emit (acknowledgement-anomaly cause))
              (throw cause))))))))

(defn- completion-callback [observations emit completion]
  (reify DebeziumEngine$CompletionCallback
    (handle [_ success message error]
      (let [observation (if success
                          ::lifecycle/completion-observed
                          {:observation                  ::lifecycle/completion-observed
                           :cognitect.anomalies/category :cognitect.anomalies/fault
                           :cognitect.anomalies/message  message
                           :debezium-embedded/cause      error})]
        (observe! observations emit observation)
        (deliver completion true)))))

(defn- connector-callback [observations emit]
  (reify DebeziumEngine$ConnectorCallback
    (connectorStarted [_]
      (observe! observations emit ::lifecycle/connector-started))
    (connectorStopped [_]
      (observe! observations emit ::lifecycle/connector-stopped))
    (pollingStarted [_]
      (observe! observations emit ::lifecycle/polling-started))
    (pollingStopped [_]
      (observe! observations emit ::lifecycle/polling-stopped))))

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
            dispatcher   (event-dispatcher (::on-event arg-map))
            emit         (:emit dispatcher)
            engine       (-> (DebeziumEngine/create (ChangeEventFormat/of Connect))
                           (.using (map->properties config))
                           (.notifying (batch-consumer observations emit consumer))
                           (.using (completion-callback observations emit completion))
                           (.using (connector-callback observations emit))
                           (.build))]
        (CaptureHandle. engine observations completion dispatcher default-shutdown-timeout-ms)))))

(defn- fault [message cause]
  {:cognitect.anomalies/category :cognitect.anomalies/fault
   :cognitect.anomalies/message  message
   :debezium-embedded/cause      cause})

(defn start! [^CaptureHandle handle {:keys [executor]}]
  (let [observations (.-observations handle)
        emit         (:emit (.-event-dispatcher handle))
        engine       (.-engine handle)
        executor     (or executor (ForkJoinPool/commonPool))]
    (locking observations
      (if (not= ::lifecycle/ready (lifecycle/phase @observations))
        (incorrect "Engine cannot be started from its current lifecycle phase")
        (do
          (observe! observations emit ::lifecycle/start-requested)
          (try
            (.execute ^Executor executor ^Runnable engine)
            (observe! observations emit ::lifecycle/run-submitted)
            nil
            (catch Throwable cause
              (observe! observations emit {:observation                  ::lifecycle/run-submission-anomaly
                                           :cognitect.anomalies/category :cognitect.anomalies/fault
                                           :cognitect.anomalies/message  "Engine submission failed"
                                           :debezium-embedded/cause      cause})
              (fault "Engine submission failed" cause))))))))

(defn running? [^CaptureHandle handle]
  (lifecycle/admitting? @(.-observations handle)))

(defn stop! [^CaptureHandle handle {:keys [timeout-ms]}]
  (let [observations (.-observations handle)
        emit         (:emit (.-event-dispatcher handle))
        timeout-ms   (or timeout-ms (.-default-shutdown-timeout-ms handle))]
    (locking observations
      (if (= ::lifecycle/ready (lifecycle/phase @observations))
        (do
          (observe! observations emit ::lifecycle/stop-requested)
          nil)
        (do
          (observe! observations emit ::lifecycle/stop-requested)
          (try
            (.close ^Closeable (.-engine handle))
            (catch Throwable cause
              (swap! observations conj {:observation                  ::lifecycle/shutdown-anomaly
                                        :cognitect.anomalies/category :cognitect.anomalies/fault
                                        :cognitect.anomalies/message  "Engine shutdown failed"
                                        :debezium-embedded/cause      cause})))
          (if (deref (.-completion handle) timeout-ms ::timed-out)
            (or (lifecycle/primary-anomaly @observations)
                (when-not (lifecycle/graceful-completion? @observations)
                  {:cognitect.anomalies/category :cognitect.anomalies/fault
                   :cognitect.anomalies/message  "Engine completion lacks graceful-shutdown evidence"}))
            (let [anomaly {:cognitect.anomalies/category :cognitect.anomalies/unavailable
                           :cognitect.anomalies/message  "Engine shutdown remained unconfirmed"}]
              (swap! observations conj (assoc anomaly :observation ::lifecycle/shutdown-unconfirmed))
              anomaly)))))))
