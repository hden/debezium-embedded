(ns debezium-embedded.lifecycle)

(defn- observation-kind [observation]
  (if (keyword? observation)
    observation
    (:observation observation)))

(defn- anomaly-observation? [observation]
  (or (contains? #{::engine-submission-anomaly
                   ::shutdown-anomaly
                   ::consumer-anomaly
                   ::acknowledgement-anomaly
                   ::protocol-anomaly
                   ::shutdown-unconfirmed}
                 (observation-kind observation))
      (and (map? observation)
           (contains? observation :cognitect.anomalies/category))))

(def ^:private initial-projection
  {:phase       ::ready
   :engine      ::not-invoked
   :connector   ::not-started
   :polling     ::not-started
   :shutdown    {:requests 0 :failed? false}
   :completion  ::pending
   :protocol?   false
   :batches     {:admitted 0 :acknowledged 0}})

(defn- always-allowed? [_projection _observation]
  true)

(defn- never-allowed? [_projection _observation]
  false)

(defn- connector-not-started? [projection _observation]
  (= ::not-started (:connector projection)))

(defn- connector-started? [projection _observation]
  (= ::started (:connector projection)))

(defn- initial-polling-ready? [projection _observation]
  (and (= ::invoked (:engine projection))
       (= ::started (:connector projection))
       (= ::not-started (:polling projection))))

(defn- retry-polling-ready? [projection observation]
  (and (initial-polling-ready? projection observation)
       (true? (get-in projection [:shutdown :failed?]))))

(defn- polling-started? [projection _observation]
  (= ::started (:polling projection)))

(defn- completion-pending? [projection _observation]
  (= ::pending (:completion projection)))

(defn- retain-projection [projection _observation]
  projection)

(defn- begin-starting [projection _observation]
  (assoc projection :phase ::starting))

(defn- stop-immediately [projection _observation]
  (assoc projection :phase ::stopped))

(defn- begin-stopping [projection _observation]
  (assoc projection :phase ::stopping))

(defn- record-engine-invocation [projection _observation]
  (assoc projection :engine ::invoked))

(defn- record-engine-cancellation [projection _observation]
  (assoc projection :phase ::stopped :engine ::cancelled))

(defn- record-engine-rejection [projection _observation]
  (assoc projection :phase ::stopped :engine ::rejected))

(defn- record-connector-start [projection _observation]
  (assoc projection :connector ::started))

(defn- record-connector-stop [projection _observation]
  (assoc projection :phase ::stopping :connector ::stopped))

(defn- record-initial-polling-start [projection _observation]
  (assoc projection :phase ::capturing :polling ::started))

(defn- record-retry-polling-start [projection _observation]
  (assoc projection :polling ::started))

(defn- record-polling-stop [projection _observation]
  (assoc projection :phase ::stopping :polling ::stopped))

(defn- record-shutdown-request [projection _observation]
  (update-in projection [:shutdown :requests] inc))

(defn- record-terminal-shutdown-anomaly [projection _observation]
  (-> projection
      (assoc :phase ::stopped)
      (assoc-in [:shutdown :failed?] true)))

(defn- record-stopping-shutdown-anomaly [projection _observation]
  (-> projection
      (assoc :phase ::stopping)
      (assoc-in [:shutdown :failed?] true)))

(defn- record-terminal-anomaly [projection _observation]
  (assoc projection :phase ::stopped))

(defn- record-stopping-anomaly [projection _observation]
  (assoc projection :phase ::stopping))

(defn- record-completion [projection observation]
  (assoc projection
         :phase ::stopped
         :completion (if (anomaly-observation? observation)
                       ::failed
                       ::succeeded)))

(defn- record-batch-admission [projection _observation]
  (update-in projection [:batches :admitted] inc))

(defn- record-batch-acknowledgement [projection _observation]
  (update-in projection [:batches :acknowledged] inc))

(defn- record-terminal-protocol-rejection [projection _observation]
  (assoc projection :phase ::stopped :protocol? true))

(defn- record-stopping-protocol-rejection [projection _observation]
  (assoc projection :phase ::stopping :protocol? true))

(defn- record-rejected-completion [projection observation]
  (assoc (record-completion projection observation) :protocol? true))

(defn- interpretation [guard update]
  {:guard guard :update update})

(defn- rejected-interpretation [guard update]
  {:guard guard :update update :protocol-violation? true})

(def ^:private active-phases
  [::ready ::starting ::capturing ::stopping])

(def ^:private benign-observation-kinds
  [::engine-submission-started
   ::shutdown-returned
   ::batch-handled
   ::record-acknowledgement-started
   ::record-acknowledged
   ::batch-acknowledgement-started])

(defn- phase-entries [phases observation-kinds entry]
  (for [phase phases
        observation-kind observation-kinds]
    [[phase observation-kind] entry]))

(def ^:private interpretation-table
  (into {}
        (concat
          (phase-entries active-phases benign-observation-kinds
                         (interpretation always-allowed? retain-projection))
          (phase-entries active-phases [::engine-invocation-started]
                         (interpretation always-allowed? record-engine-invocation))
          (phase-entries active-phases [::engine-invocation-cancelled]
                         (interpretation always-allowed? record-engine-cancellation))
          (phase-entries active-phases [::engine-submission-anomaly]
                         (interpretation always-allowed? record-engine-rejection))
          (phase-entries active-phases [::shutdown-request-started]
                         (interpretation always-allowed? record-shutdown-request))
          (phase-entries [::ready] [::shutdown-anomaly]
                         (interpretation always-allowed?
                                         record-terminal-shutdown-anomaly))
          (phase-entries [::starting ::capturing ::stopping] [::shutdown-anomaly]
                         (interpretation always-allowed?
                                         record-stopping-shutdown-anomaly))
          (phase-entries [::ready]
                         [::consumer-anomaly
                          ::acknowledgement-anomaly
                          ::shutdown-unconfirmed]
                         (interpretation always-allowed? record-terminal-anomaly))
          (phase-entries [::starting ::capturing ::stopping]
                         [::consumer-anomaly
                          ::acknowledgement-anomaly
                          ::shutdown-unconfirmed]
                         (interpretation always-allowed? record-stopping-anomaly))
          (phase-entries active-phases [::batch-admitted]
                         (interpretation always-allowed? record-batch-admission))
          (phase-entries active-phases [::batch-acknowledged]
                         (interpretation always-allowed? record-batch-acknowledgement))
          (phase-entries [::ready] [::start-requested]
                         (interpretation always-allowed? begin-starting))
          (phase-entries [::starting ::capturing ::stopping] [::start-requested]
                         (interpretation always-allowed? retain-projection))
          (phase-entries [::ready] [::stop-requested]
                         (interpretation always-allowed? stop-immediately))
          (phase-entries [::starting ::capturing ::stopping] [::stop-requested]
                         (interpretation always-allowed? begin-stopping))
          (phase-entries [::starting] [::connector-started]
                         (interpretation connector-not-started? record-connector-start))
          (phase-entries [::starting ::capturing ::stopping] [::connector-stopped]
                         (interpretation connector-started? record-connector-stop))
          (phase-entries [::starting] [::polling-started]
                         (interpretation initial-polling-ready?
                                         record-initial-polling-start))
          (phase-entries [::stopping] [::polling-started]
                         (interpretation retry-polling-ready?
                                         record-retry-polling-start))
          (phase-entries [::capturing ::stopping] [::polling-stopped]
                         (interpretation polling-started? record-polling-stop))
          (phase-entries [::stopping] [::completion-observed]
                         (interpretation completion-pending? record-completion))
          (phase-entries [::ready ::starting ::capturing ::stopped]
                         [::completion-observed]
                         (rejected-interpretation completion-pending?
                                                  record-rejected-completion))
          (phase-entries [::ready ::stopped] [::protocol-anomaly]
                         (interpretation always-allowed?
                                         record-terminal-protocol-rejection))
          (phase-entries [::starting ::capturing ::stopping] [::protocol-anomaly]
                         (interpretation always-allowed?
                                         record-stopping-protocol-rejection))
          (phase-entries [::ready ::stopped] [::unrecognized-observation]
                         (interpretation never-allowed?
                                         record-terminal-protocol-rejection))
          (phase-entries [::starting ::capturing ::stopping]
                         [::unrecognized-observation]
                         (interpretation never-allowed?
                                         record-stopping-protocol-rejection)))))

(defn- interpret-observation [projection observation]
  (let [phase          (:phase projection)
        interpretation (get interpretation-table
                            [phase (observation-kind observation)])
        fallback       (get interpretation-table
                            [phase ::unrecognized-observation])
        allowed?       (and interpretation
                            ((:guard interpretation) projection observation))
        update         (:update (if allowed? interpretation fallback))]
    {:projection (update projection observation)
     :protocol-violation? (or (:protocol-violation? interpretation)
                              (not allowed?))}))

(defn- interpret
  ([observations]
   (reduce interpret initial-projection observations))
  ([projection observation]
   (:projection (interpret-observation projection observation))))

(defn- protocol-anomaly [observation]
  {:observation                  ::protocol-anomaly
   :cognitect.anomalies/category :cognitect.anomalies/fault
   :cognitect.anomalies/message  "Unexpected lifecycle observation"
   :observation/value            observation})

(defn phase [observations]
  (:phase (interpret observations)))

(defn admitting? [observations]
  (= ::capturing (phase observations)))

(defn append-observation [observations observation]
  (let [projection       (interpret observations)
        interpretation   (interpret-observation projection observation)
        with-observation (conj observations observation)]
    (if (:protocol-violation? interpretation)
      (conj with-observation (protocol-anomaly observation))
      with-observation)))

(defn- anomaly-value [observation]
  (when (anomaly-observation? observation)
    (if (map? observation)
      (dissoc observation :observation)
      {:cognitect.anomalies/category :cognitect.anomalies/fault
       :cognitect.anomalies/message  "Lifecycle anomaly"})))

(defn- completion-protocol-anomaly? [observation]
  (and (= ::protocol-anomaly (observation-kind observation))
       (= ::completion-observed (:observation/value observation))))

(defn- successful-terminal-observation? [observation]
  (contains? #{::completion-observed ::engine-invocation-cancelled}
             (observation-kind observation)))

(defn terminal-anomaly [observations]
  (loop [remaining                   (seq observations)
         successful-terminal-seen?   false]
    (when-let [observation (first remaining)]
      (let [anomaly (anomaly-value observation)]
        (cond
          (and successful-terminal-seen?
               (not (completion-protocol-anomaly? observation)))
          (recur (next remaining) true)

          (completion-protocol-anomaly? observation)
          anomaly

          anomaly
          anomaly

          (successful-terminal-observation? observation)
          (recur (next remaining) true)

          :else
          (recur (next remaining) successful-terminal-seen?))))))

(defn graceful-completion? [observations]
  (let [counts (frequencies (map observation-kind observations))]
    (and (pos? (get counts ::completion-observed 0))
      (nil? (terminal-anomaly observations))
      (<= (get counts ::batch-admitted 0)
          (get counts ::batch-acknowledged 0))
      (<= (get counts ::connector-started 0)
          (get counts ::connector-stopped 0)))))
