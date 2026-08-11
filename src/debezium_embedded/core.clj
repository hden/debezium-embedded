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

(defn- shutdown-anomaly [cause]
  {:observation                  ::lifecycle/shutdown-anomaly
   :cognitect.anomalies/category :cognitect.anomalies/fault
   :cognitect.anomalies/message  "Engine shutdown failed"
   :debezium-embedded/cause      cause})

(defn- append-observation! [observations observation]
  (loop []
    (let [before @observations
          after  (lifecycle/append-observation before observation)]
      (if (compare-and-set! observations before after)
        after
        (recur)))))

(defn- append-when! [observations allowed? observation]
  (loop []
    (let [before @observations]
      (cond
        (not (allowed? before)) false
        (compare-and-set! observations before
                          (lifecycle/append-observation before observation)) true
        :else (recur)))))

(defn- append-when-and-emit! [observations emit allowed? observation]
  (when (append-when! observations allowed? observation)
    (when emit (emit observations))
    true))

(defn- observe! [observations emit observation]
  (append-observation! observations observation)
  (when emit (emit observations)))

(defn- request-shutdown! [observations emit engine]
  (when (append-when-and-emit! observations emit
          #(= ::lifecycle/stopping (lifecycle/phase %))
          ::lifecycle/shutdown-request-started)
    (try
      (.close ^Closeable engine)
      (observe! observations emit ::lifecycle/shutdown-returned)
      (catch Throwable cause
        (observe! observations emit (shutdown-anomaly cause))))))

(defn- observed? [observations kind]
  (some #(= kind (if (keyword? %)
                   %
                   (:observation %)))
        observations))

(defn- retry-shutdown? [observations]
  (and (= ::lifecycle/stopping (lifecycle/phase observations))
       (observed? observations ::lifecycle/shutdown-anomaly)
       (= 1 (count (filter #{::lifecycle/polling-started} observations)))
       (< (count (filter #{::lifecycle/shutdown-request-started} observations)) 2)))

(defn- event-dispatcher [on-event]
  (when on-event
    (let [executor (ThreadPoolExecutor. 1 1 0 TimeUnit/MILLISECONDS
                                        (ArrayBlockingQueue. 64)
                                        (ThreadPoolExecutor$DiscardPolicy.))
          next-index (atom 0)
          draining?  (atom false)]
      (letfn [(drain! [observations]
                (when (compare-and-set! draining? false true)
                  (loop []
                    (let [trace @observations
                          index @next-index]
                      (if (< index (count trace))
                        (do
                          (try
                            (.execute executor
                                      ^Runnable
                                      (fn []
                                        (try
                                          (on-event {:debezium-embedded.core/event :debezium-embedded.core/observation-recorded
                                                     :debezium-embedded.core/observation (nth trace index)})
                                          (catch Throwable _))))
                            (catch Throwable _))
                          (reset! next-index (inc index))
                          (recur))
                        (do
                          (reset! draining? false)
                          (when (< @next-index (count @observations))
                            (drain! observations))))))))]
        {:emit     drain!
         :shutdown #(.shutdownNow executor)}))))

(defn- batch-consumer [observations emit consumer]
  (reify DebeziumEngine$ChangeConsumer
    (handleBatch [_ records committer]
      (when (append-when-and-emit! observations emit lifecycle/admitting?
              ::lifecycle/batch-admitted)
        (try
          (consumer (mapv #(source-record->map (.record %)) records))
          (observe! observations emit ::lifecycle/batch-handled)
          (catch Throwable cause
            (observe! observations emit (consumer-anomaly cause))
            (throw cause)))
        (loop [remaining-records (seq records)]
          (if-let [record (first remaining-records)]
            (when (append-when-and-emit! observations emit lifecycle/admitting?
                    ::lifecycle/record-acknowledgement-started)
              (try
                (.markProcessed committer record)
                (catch Throwable cause
                  (observe! observations emit (acknowledgement-anomaly cause))
                  (throw cause)))
              (observe! observations emit ::lifecycle/record-acknowledged)
              (recur (next remaining-records)))
            (when (append-when-and-emit! observations emit lifecycle/admitting?
                    ::lifecycle/batch-acknowledgement-started)
              (try
                (.markBatchFinished committer)
                (observe! observations emit ::lifecycle/batch-acknowledged)
                (catch Throwable cause
                  (observe! observations emit (acknowledgement-anomaly cause))
                  (throw cause))))))))))

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

(defn- connector-callback [observations emit retry-shutdown!]
  (reify DebeziumEngine$ConnectorCallback
    (connectorStarted [_]
      (observe! observations emit ::lifecycle/connector-started))
    (connectorStopped [_]
      (observe! observations emit ::lifecycle/connector-stopped))
    (pollingStarted [_]
      (observe! observations emit ::lifecycle/polling-started)
      (when (retry-shutdown? @observations)
        (retry-shutdown!)))
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
            engine-ref   (atom nil)
            retry-shutdown! #(when-let [engine @engine-ref]
                               (request-shutdown! observations emit engine))
            engine       (-> (DebeziumEngine/create (ChangeEventFormat/of Connect))
                           (.using (map->properties config))
                           (.notifying (batch-consumer observations emit consumer))
                           (.using (completion-callback observations emit completion))
                           (.using (connector-callback observations emit retry-shutdown!))
                           (.build))]
        (reset! engine-ref engine)
        (CaptureHandle. engine observations completion dispatcher default-shutdown-timeout-ms)))))

(defn- fault [message cause]
  {:cognitect.anomalies/category :cognitect.anomalies/fault
   :cognitect.anomalies/message  message
   :debezium-embedded/cause      cause})

(defn- request-stop! [observations]
  (loop []
    (let [before          @observations
          phase-before    (lifecycle/phase before)
          invocation-started? (observed? before ::lifecycle/engine-invocation-started)
          stop-requested? (observed? before ::lifecycle/stop-requested)]
      (cond
        (= phase-before ::lifecycle/stopped) {:outcome :stopped}
        stop-requested? {:outcome :already-requested}
        :else
        (let [after-stop (lifecycle/append-observation before ::lifecycle/stop-requested)
              cancel?    (and (not= phase-before ::lifecycle/ready)
                              (not invocation-started?))
              after      (if cancel?
                           (lifecycle/append-observation after-stop
                                                         ::lifecycle/engine-invocation-cancelled)
                           after-stop)]
          (if (compare-and-set! observations before after)
            {:outcome (cond
                        (= phase-before ::lifecycle/ready) :not-started
                        cancel? :cancelled
                        :else :shutdown-required)
             :stop-appended? true}
            (recur)))))))

(defn- engine-invocation-runnable [engine observations emit]
  (reify Runnable
    (run [_]
      (if (append-when-and-emit! observations emit
            #(= ::lifecycle/starting (lifecycle/phase %))
            ::lifecycle/engine-invocation-started)
        (.run ^Runnable engine)))))

(defn start! [^CaptureHandle handle {:keys [executor]}]
  (let [observations (.-observations handle)
        emit         (:emit (.-event-dispatcher handle))
        engine       (.-engine handle)
        executor     (or executor (ForkJoinPool/commonPool))]
    (if-not (append-when-and-emit! observations emit
              #(= ::lifecycle/ready (lifecycle/phase %))
              ::lifecycle/start-requested)
      (incorrect "Engine cannot be started from its current lifecycle phase")
      (if-not (append-when-and-emit! observations emit
                #(= ::lifecycle/starting (lifecycle/phase %))
                ::lifecycle/engine-submission-started)
        nil
        (try
          (.execute ^Executor executor
                    ^Runnable (engine-invocation-runnable engine observations emit))
          nil
          (catch Throwable cause
            (let [anomaly {:observation                  ::lifecycle/engine-submission-anomaly
                           :cognitect.anomalies/category :cognitect.anomalies/fault
                           :cognitect.anomalies/message  "Engine submission failed"
                           :debezium-embedded/cause      cause}]
              (observe! observations emit anomaly)
              (when-not (observed? @observations
                          ::lifecycle/engine-invocation-cancelled)
                (fault "Engine submission failed" cause)))))))))

(defn running? [^CaptureHandle handle]
  (lifecycle/admitting? @(.-observations handle)))

(defn stop! [^CaptureHandle handle {:keys [timeout-ms]}]
  (let [observations (.-observations handle)
        emit         (:emit (.-event-dispatcher handle))
        timeout-ms   (or timeout-ms (.-default-shutdown-timeout-ms handle))
        {:keys [outcome stop-appended?]} (request-stop! observations)]
    (when (and stop-appended? emit)
      (emit observations))
    (when (= outcome :shutdown-required)
      (request-shutdown! observations emit (.-engine handle)))
    (if (contains? #{:not-started :cancelled :stopped} outcome)
      (lifecycle/terminal-anomaly @observations)
      (if (deref (.-completion handle) timeout-ms ::timed-out)
        (or (lifecycle/terminal-anomaly @observations)
            (when-not (lifecycle/graceful-completion? @observations)
              {:cognitect.anomalies/category :cognitect.anomalies/fault
               :cognitect.anomalies/message  "Engine completion lacks graceful-shutdown evidence"}))
        (let [anomaly {:cognitect.anomalies/category :cognitect.anomalies/unavailable
                       :cognitect.anomalies/message  "Engine shutdown remained unconfirmed"}]
          (observe! observations emit (assoc anomaly :observation ::lifecycle/shutdown-unconfirmed))
          anomaly)))))
