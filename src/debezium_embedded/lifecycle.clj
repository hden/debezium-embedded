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
   :completion-observed? false
   :terminal-anomaly nil
   :protocol?   false
   :batches     {:admitted 0 :acknowledged 0}
   :connectors  {:started 0 :stopped 0}})

(defn- anomaly-value [observation]
  (when (anomaly-observation? observation)
    (if (map? observation)
      (dissoc observation :observation)
      {:cognitect.anomalies/category :cognitect.anomalies/fault
       :cognitect.anomalies/message  "Lifecycle anomaly"})))

(defn- completion-protocol-anomaly? [observation]
  (and (= ::protocol-anomaly (observation-kind observation))
       (= ::completion-observed (:observation/value observation))))

(defn- successful-terminal-observed? [projection]
  (or (= ::succeeded (:completion projection))
      (= ::cancelled (:engine projection))))

(defn- retain-terminal-anomaly [projection observation]
  (if (:terminal-anomaly projection)
    projection
    (assoc projection :terminal-anomaly (anomaly-value observation))))

(defn- record-anomaly [projection observation]
  (if (and (successful-terminal-observed? projection)
           (not (completion-protocol-anomaly? observation)))
    projection
    (retain-terminal-anomaly projection observation)))

(defn- always-allowed? [_projection _observation]
  true)

(defn- never-allowed? [_projection _observation]
  false)

(defn- connector-start-allowed? [projection _observation]
  (and (= ::invoked (:engine projection))
       (= ::not-started (:connector projection))))

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

(defn- record-engine-rejection [projection observation]
  (-> projection
      (assoc :phase ::stopped :engine ::rejected)
      (record-anomaly observation)))

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

(defn- record-terminal-shutdown-anomaly [projection observation]
  (-> projection
      (assoc :phase ::stopped)
      (assoc-in [:shutdown :failed?] true)
      (record-anomaly observation)))

(defn- record-stopping-shutdown-anomaly [projection observation]
  (-> projection
      (assoc :phase ::stopping)
      (assoc-in [:shutdown :failed?] true)
      (record-anomaly observation)))

(defn- record-terminal-anomaly [projection observation]
  (-> projection
      (assoc :phase ::stopped)
      (record-anomaly observation)))

(defn- record-stopping-anomaly [projection observation]
  (-> projection
      (assoc :phase ::stopping)
      (record-anomaly observation)))

(defn- record-completion [projection observation]
  (let [failed? (anomaly-observation? observation)]
    (cond-> (assoc projection
                   :phase ::stopped
                   :completion-observed? true
                   :completion (if failed? ::failed ::succeeded))
      failed? (record-anomaly observation))))

(defn- record-batch-admission [projection _observation]
  (update-in projection [:batches :admitted] inc))

(defn- record-batch-acknowledgement [projection _observation]
  (update-in projection [:batches :acknowledged] inc))

(defn- record-connector-start-evidence [projection _observation]
  (update-in projection [:connectors :started] inc))

(defn- record-connector-stop-evidence [projection _observation]
  (update-in projection [:connectors :stopped] inc))

(defn- record-completion-evidence [projection _observation]
  (assoc projection :completion-observed? true))

(def ^:private evidence-recorders
  {::batch-admitted        record-batch-admission
   ::batch-acknowledged    record-batch-acknowledgement
   ::connector-started     record-connector-start-evidence
   ::connector-stopped     record-connector-stop-evidence
   ::completion-observed   record-completion-evidence})

(defn- record-observation-evidence [projection observation]
  (if-let [recorder (get evidence-recorders (observation-kind observation))]
    (recorder projection observation)
    projection))

(defn- record-terminal-protocol-rejection [projection observation]
  (-> projection
      (assoc :phase ::stopped :protocol? true)
      (record-anomaly observation)))

(defn- record-stopping-protocol-rejection [projection observation]
  (-> projection
      (assoc :phase ::stopping :protocol? true)
      (record-anomaly observation)))

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
                         (interpretation always-allowed? retain-projection))
          (phase-entries active-phases [::batch-acknowledged]
                         (interpretation always-allowed? retain-projection))
          (phase-entries [::ready] [::start-requested]
                         (interpretation always-allowed? begin-starting))
          (phase-entries [::starting ::capturing ::stopping] [::start-requested]
                         (interpretation always-allowed? retain-projection))
          (phase-entries [::ready] [::stop-requested]
                         (interpretation always-allowed? stop-immediately))
          (phase-entries [::starting ::capturing ::stopping] [::stop-requested]
                         (interpretation always-allowed? begin-stopping))
          (phase-entries [::starting] [::connector-started]
                         (interpretation connector-start-allowed?
                                         record-connector-start))
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
  (let [projection     (record-observation-evidence projection observation)
        phase          (:phase projection)
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

(defn terminal-anomaly [observations]
  (:terminal-anomaly (interpret observations)))

(defn graceful-completion? [observations]
  (let [{:keys [completion-observed? terminal-anomaly batches connectors]}
        (interpret observations)]
    (and completion-observed?
         (nil? terminal-anomaly)
         (<= (:admitted batches) (:acknowledged batches))
         (<= (:started connectors) (:stopped connectors)))))
