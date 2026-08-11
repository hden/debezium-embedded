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

(defn- next-phase [phase observation]
  (let [kind (observation-kind observation)]
    (cond
      (= phase ::stopped) ::stopped
      (= kind ::engine-submission-anomaly) ::stopped
      (= kind ::engine-invocation-cancelled) ::stopped
      (= kind ::completion-observed) ::stopped
      (anomaly-observation? observation) (if (= phase ::ready) ::stopped ::stopping)
      (= kind ::start-requested) (if (= phase ::ready) ::starting phase)
      (= kind ::stop-requested) (if (= phase ::ready) ::stopped ::stopping)
      (= kind ::connector-stopped) (if (= phase ::ready) ::stopped ::stopping)
      (= kind ::polling-started) (if (= phase ::starting) ::capturing phase)
      (= kind ::polling-stopped) (if (= phase ::capturing) ::stopping phase)
      :else phase)))

(defn- protocol-anomaly [observation]
  {:observation                  ::protocol-anomaly
   :cognitect.anomalies/category :cognitect.anomalies/fault
   :cognitect.anomalies/message  "Unexpected lifecycle observation"
   :observation/value            observation})

(defn- protocol-violation? [phase-before seen-kinds observation]
  (let [kind         (observation-kind observation)
        seen?        #(contains? seen-kinds %)]
    (or (and (= kind ::completion-observed)
             (contains? #{::ready ::starting ::capturing} phase-before))
        (and (= kind ::connector-started)
             (or (not= phase-before ::starting)
                 (seen? ::connector-started)
                 (seen? ::connector-stopped)))
        (and (= kind ::connector-stopped)
             (or (not (seen? ::connector-started))
                 (seen? ::connector-stopped)))
        (and (= kind ::polling-started)
             (or (not (or (= phase-before ::starting)
                        (and (= phase-before ::stopping)
                             (seen? ::shutdown-anomaly))))
                 (not (seen? ::engine-invocation-started))
                 (not (seen? ::connector-started))
                 (seen? ::connector-stopped)
                 (seen? ::polling-started)))
        (and (= kind ::polling-stopped)
             (not= phase-before ::capturing))
        (and (= phase-before ::stopped)
             (not= kind ::protocol-anomaly)))))

(defn- phase-and-seen-kinds [observations]
  (loop [phase-before ::ready
         seen-kinds    #{}
         remaining    (seq observations)]
    (if-let [observation (first remaining)]
      (let [phase-after (next-phase phase-before observation)
            phase       (if (protocol-violation? phase-before seen-kinds observation)
                          (if (= phase-after ::stopped)
                            ::stopped
                            ::stopping)
                          phase-after)]
        (recur phase (conj seen-kinds (observation-kind observation))
               (next remaining)))
      [phase-before seen-kinds])))

(defn phase [observations]
  (first (phase-and-seen-kinds observations)))

(defn admitting? [observations]
  (= ::capturing (phase observations)))

(defn append-observation [observations observation]
  (let [[phase-before seen-kinds] (phase-and-seen-kinds observations)
        with-observation          (conj observations observation)]
    (if (protocol-violation? phase-before seen-kinds observation)
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
