(ns debezium-embedded.core
  (:require
   [camel-snake-kebab.core :as csk])
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

(deftype CaptureHandle [engine latest-event start-result completion event-dispatcher started? stop-requested? shutdown-issued? default-shutdown-timeout-ms]
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

(defn- fault [event message cause]
  {:event                        event
   :cognitect.anomalies/category :cognitect.anomalies/fault
   :cognitect.anomalies/message  message
   :debezium-embedded/cause      cause})

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

(defn latest-event [^CaptureHandle handle]
  @(.-latest-event handle))

(defn polling? [^CaptureHandle handle]
  (= ::polling-started (:event (latest-event handle))))

(defn- event-dispatcher [on-event]
  (when on-event
    (let [executor (ThreadPoolExecutor. 1 1 0 TimeUnit/MILLISECONDS
                                        (ArrayBlockingQueue. 64)
                                        (ThreadPoolExecutor$DiscardPolicy.))]
      {:emit     (fn [event]
                   (try
                     (.execute executor
                               ^Runnable
                               (fn []
                                 (try
                                   (on-event {::event       ::event-observed
                                              ::observation event})
                                   (catch Throwable _))))
                     (catch Throwable _)))
       :shutdown #(.shutdownNow executor)})))

(defn- emit! [emit event]
  (when emit
    (emit event)))

(defn- observe-callback! [latest-event emit event]
  (reset! latest-event event)
  (emit! emit event))

(defn- batch-consumer [emit consumer]
  (reify DebeziumEngine$ChangeConsumer
    (handleBatch [_ records committer]
      (try
        (consumer (mapv #(source-record->map (.record %)) records))
        (catch Throwable cause
          (let [event (fault ::consumer-failed "Change-event consumer failed" cause)]
            (emit! emit event)
            (throw cause))))
      (try
        (doseq [record records]
          (.markProcessed committer record))
        (.markBatchFinished committer)
        (catch Throwable cause
          (let [event (fault ::acknowledgement-failed
                             "Change-event acknowledgement failed"
                             cause)]
            (emit! emit event)
            (throw cause)))))))

(defn- completion-event [success message error]
  (if success
    {:event ::completed :success? true}
    (assoc (fault ::completed message error) :success? false)))

(defn- completion-callback [latest-event emit start-result completion]
  (reify DebeziumEngine$CompletionCallback
    (handle [_ success message error]
      (let [event (completion-event success message error)]
        (observe-callback! latest-event emit event)
        (deliver start-result event)
        (deliver completion event)))))

(defn- close-engine! [engine shutdown-issued?]
  (when (compare-and-set! shutdown-issued? false true)
    (.close ^Closeable engine)))

(defn- connector-callback [latest-event emit start-result stop-requested? request-shutdown!]
  (reify DebeziumEngine$ConnectorCallback
    (connectorStarted [_]
      (observe-callback! latest-event emit {:event ::connector-started}))
    (connectorStopped [_]
      (observe-callback! latest-event emit {:event ::connector-stopped}))
    (taskStarted [_]
      (observe-callback! latest-event emit {:event ::task-started}))
    (taskStopped [_]
      (observe-callback! latest-event emit {:event ::task-stopped}))
    (pollingStarted [_]
      (let [event {:event ::polling-started}]
        (observe-callback! latest-event emit event)
        (if @stop-requested?
          (try
            (request-shutdown!)
            (deliver start-result event)
            (catch Throwable cause
              (let [shutdown-failure (fault ::shutdown-failed "Engine shutdown failed" cause)]
                (emit! emit shutdown-failure)
                (deliver start-result shutdown-failure))))
          (deliver start-result event))))
    (pollingStopped [_]
      (observe-callback! latest-event emit {:event ::polling-stopped}))))

(defn create-engine [arg-map]
  (let [config                      (::config arg-map)
        consumer                    (::consumer arg-map)
        default-shutdown-timeout-ms (get arg-map ::default-shutdown-timeout-ms 2000)]
    (cond
      (nil? config) (incorrect "Missing Debezium configuration")
      (nil? consumer) (incorrect "Missing change-event consumer")
      (nil? (:offset.storage config)) (incorrect "Missing :offset.storage")
      :else
      (let [latest-event (atom nil)
            start-result (promise)
            completion   (promise)
            dispatcher   (event-dispatcher (::on-event arg-map))
            emit         (:emit dispatcher)
            stop-requested? (atom false)
            shutdown-issued? (atom false)
            engine-promise (promise)
            request-shutdown! #(close-engine! @engine-promise shutdown-issued?)
            engine       (-> (DebeziumEngine/create (ChangeEventFormat/of Connect))
                           (.using (map->properties config))
                           (.notifying (batch-consumer emit consumer))
                           (.using (completion-callback latest-event emit start-result completion))
                           (.using (connector-callback latest-event emit start-result stop-requested? request-shutdown!))
                           (.build))]
        (deliver engine-promise engine)
        (CaptureHandle. engine latest-event start-result completion dispatcher (atom false)
                        stop-requested? shutdown-issued? default-shutdown-timeout-ms)))))

(defn- completion-anomaly [event]
  (when-not (:success? event)
    (dissoc event :event :success?)))

(defn- start-anomaly [event]
  (if (= ::polling-started (:event event))
    nil
    (or (completion-anomaly event)
        {:cognitect.anomalies/category :cognitect.anomalies/unavailable
         :cognitect.anomalies/message  "Engine completed before polling started"})))

(defn start! [^CaptureHandle handle {:keys [executor]}]
  (let [executor (or executor (ForkJoinPool/commonPool))]
    (if-not (compare-and-set! (.-started? handle) false true)
      (incorrect "Engine has already been started")
      (try
        (.execute ^Executor executor ^Runnable (.-engine handle))
        (start-anomaly @(.-start-result handle))
        (catch Throwable cause
          (let [event (fault ::engine-submission-failed "Engine submission failed" cause)]
            (reset! (.-started? handle) false)
            (when @(.-stop-requested? handle)
              (deliver (.-start-result handle) event))
            (emit! (:emit (.-event-dispatcher handle)) event)
            event))))))

(defn- await-completion [^CaptureHandle handle timeout-ms]
  (let [event (deref (.-completion handle) timeout-ms ::timed-out)]
    (if (= ::timed-out event)
      (let [anomaly {:event                        ::shutdown-unconfirmed
                     :cognitect.anomalies/category :cognitect.anomalies/unavailable
                     :cognitect.anomalies/message  "Engine shutdown remained unconfirmed"}]
        (emit! (:emit (.-event-dispatcher handle)) anomaly)
        (dissoc anomaly :event))
      (completion-anomaly event))))

(defn stop! [^CaptureHandle handle {:keys [timeout-ms]}]
  (let [completion (.-completion handle)
        start-result (.-start-result handle)
        timeout-ms (or timeout-ms (.-default-shutdown-timeout-ms handle))]
    (cond
      (not @(.-started? handle)) nil
      (realized? completion) (completion-anomaly @completion)
      :else
      (try
        (reset! (.-stop-requested? handle) true)
        (if-not (realized? start-result)
          (let [event @start-result]
            (if (= ::polling-started (:event event))
              (do
                (close-engine! (.-engine handle)
                               (.-shutdown-issued? handle))
                (await-completion handle timeout-ms))
              (start-anomaly event)))
          (do
            (when (polling? handle)
              (close-engine! (.-engine handle)
                             (.-shutdown-issued? handle)))
            (await-completion handle timeout-ms)))
        (catch Throwable cause
          (let [event (fault ::shutdown-failed "Engine shutdown failed" cause)]
            (emit! (:emit (.-event-dispatcher handle)) event)
            (dissoc event :event)))))))
