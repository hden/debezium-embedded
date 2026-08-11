(ns debezium-embedded.lifecycle)

(defn- observation-kind [observation]
  (if (keyword? observation)
    observation
    (:observation observation)))

(defn- anomaly-observation? [observation]
  (contains? #{::run-submission-anomaly
               ::shutdown-anomaly
               ::consumer-anomaly
               ::acknowledgement-anomaly
               ::protocol-anomaly
               ::shutdown-unconfirmed}
             (observation-kind observation)))

(defn- next-phase [phase observation]
  (let [kind (observation-kind observation)]
    (cond
      (= phase ::stopped) ::stopped
      (= kind ::run-submission-anomaly) ::stopped
      (= kind ::run-cancelled) ::stopped
      (anomaly-observation? observation) (if (= phase ::ready) ::stopped ::stopping)
      (= kind ::completion-observed) ::stopped
      (= kind ::start-requested) (if (= phase ::ready) ::starting phase)
      (= kind ::stop-requested) (if (= phase ::ready) ::stopped ::stopping)
      (= kind ::polling-started) (if (= phase ::starting) ::capturing phase)
      (= kind ::polling-stopped) (if (= phase ::capturing) ::stopping phase)
      :else phase)))

(defn phase [observations]
  (reduce next-phase ::ready observations))

(defn admitting? [observations]
  (= ::capturing (phase observations)))

(defn- protocol-anomaly [observation]
  {:observation                  ::protocol-anomaly
   :cognitect.anomalies/category :cognitect.anomalies/fault
   :cognitect.anomalies/message  "Unexpected lifecycle observation"
   :observation/value            observation})

(defn- protocol-violation? [observations observation]
  (let [phase-before (phase observations)
        kind         (observation-kind observation)]
    (or (and (= kind ::completion-observed)
             (contains? #{::ready ::starting ::capturing} phase-before))
        (and (= phase-before ::stopped)
             (not= kind ::protocol-anomaly)))))

(defn append-observation [observations observation]
  (let [with-observation (conj observations observation)]
    (if (protocol-violation? observations observation)
      (conj with-observation (protocol-anomaly observation))
      with-observation)))

(defn primary-anomaly [observations]
  (some (fn [observation]
          (when (anomaly-observation? observation)
            (if (map? observation)
              (dissoc observation :observation)
              {:cognitect.anomalies/category :cognitect.anomalies/fault
               :cognitect.anomalies/message  "Lifecycle anomaly"})))
        observations))
