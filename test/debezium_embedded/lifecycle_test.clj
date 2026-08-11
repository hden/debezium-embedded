(ns debezium-embedded.lifecycle-test
  (:require
   [clojure.test :refer [deftest is]]
   [debezium-embedded.lifecycle :as lifecycle]))

(defn- started-engine-trace []
  [::lifecycle/start-requested
   ::lifecycle/engine-submission-started
   ::lifecycle/engine-invocation-started])

(defn- capturing-trace []
  (into (started-engine-trace)
        [::lifecycle/connector-started
         ::lifecycle/polling-started]))

(deftest literal-traces-project-to-normal-phases
  (doseq [{:keys [trace expected-phase]}
          [{:trace          []
            :expected-phase ::lifecycle/ready}
           {:trace          [::lifecycle/start-requested]
            :expected-phase ::lifecycle/starting}
           {:trace          [::lifecycle/start-requested
                             ::lifecycle/engine-submission-started
                             ::lifecycle/engine-invocation-started
                             ::lifecycle/connector-started
                             ::lifecycle/polling-started]
            :expected-phase ::lifecycle/capturing}
           {:trace          [::lifecycle/start-requested
                             ::lifecycle/engine-submission-started
                             ::lifecycle/engine-invocation-started
                             ::lifecycle/connector-started
                             ::lifecycle/polling-started
                             ::lifecycle/stop-requested]
            :expected-phase ::lifecycle/stopping}
           {:trace          [::lifecycle/start-requested
                             ::lifecycle/engine-submission-started
                             ::lifecycle/engine-invocation-started
                             ::lifecycle/connector-started
                             ::lifecycle/polling-started
                             ::lifecycle/stop-requested
                             ::lifecycle/polling-stopped
                             ::lifecycle/connector-stopped
                             ::lifecycle/completion-observed]
            :expected-phase ::lifecycle/stopped}]]
    (is (= expected-phase (lifecycle/phase trace)))))

(deftest invalid-callbacks-retain-the-raw-fact-and-derive-one-protocol-anomaly
  (doseq [{:keys [prefix observation]}
          [{:prefix      [::lifecycle/start-requested
                          ::lifecycle/engine-submission-started
                          ::lifecycle/engine-invocation-started
                          ::lifecycle/connector-started]
            :observation ::lifecycle/connector-started}
           {:prefix      [::lifecycle/start-requested
                          ::lifecycle/engine-submission-started
                          ::lifecycle/engine-invocation-started
                          ::lifecycle/connector-started
                          ::lifecycle/polling-started]
            :observation ::lifecycle/polling-started}
           {:prefix      [::lifecycle/start-requested
                          ::lifecycle/engine-submission-started
                          ::lifecycle/engine-invocation-started]
            :observation ::lifecycle/connector-stopped}
           {:prefix      [::lifecycle/start-requested
                          ::lifecycle/engine-submission-started
                          ::lifecycle/engine-invocation-started]
            :observation ::lifecycle/polling-started}
           {:prefix      [::lifecycle/start-requested
                          ::lifecycle/engine-submission-started
                          ::lifecycle/engine-invocation-started
                          ::lifecycle/connector-started
                          ::lifecycle/polling-started]
            :observation ::lifecycle/completion-observed}]]
    (let [trace (lifecycle/append-observation prefix observation)]
      (is (= observation (nth trace (- (count trace) 2))))
      (is (= ::lifecycle/protocol-anomaly
             (:observation (last trace))))
      (is (= 1 (count (filter #(= ::lifecycle/protocol-anomaly
                                  (:observation %))
                             trace))))))
  (doseq [observation [::lifecycle/engine-submission-started
                      ::lifecycle/engine-invocation-started
                      ::lifecycle/connector-started
                      ::lifecycle/polling-started
                      ::lifecycle/stop-requested
                      ::lifecycle/polling-stopped
                      ::lifecycle/connector-stopped
                      ::lifecycle/completion-observed
                      ::lifecycle/engine-invocation-cancelled]]
    (let [trace (lifecycle/append-observation
                 [::lifecycle/start-requested
                  ::lifecycle/engine-submission-anomaly]
                 observation)]
      (is (= observation (nth trace (- (count trace) 2))))
      (is (= ::lifecycle/protocol-anomaly
             (:observation (last trace))))
      (is (= 1 (count (filter #(= ::lifecycle/protocol-anomaly
                                  (:observation %))
                             trace)))))))

(deftest terminal-results-preserve-the-observed-outcome
  (let [failed-completion {:observation                  ::lifecycle/completion-observed
                           :cognitect.anomalies/category :cognitect.anomalies/fault
                           :debezium-embedded/cause      :upstream-failure}
        late-anomaly      {:observation                  ::lifecycle/shutdown-anomaly
                           :cognitect.anomalies/category :cognitect.anomalies/fault
                           :debezium-embedded/cause      :late-shutdown-failure}
        rejection         {:observation                  ::lifecycle/engine-submission-anomaly
                           :cognitect.anomalies/category :cognitect.anomalies/fault
                           :debezium-embedded/cause      :submission-rejected}
        failed-trace      (lifecycle/append-observation
                           [::lifecycle/start-requested
                            ::lifecycle/engine-submission-started
                            ::lifecycle/engine-invocation-started
                            ::lifecycle/stop-requested]
                           failed-completion)
        successful-trace  (lifecycle/append-observation
                           [::lifecycle/start-requested
                            ::lifecycle/engine-submission-started
                            ::lifecycle/engine-invocation-started
                            ::lifecycle/connector-started
                            ::lifecycle/polling-started
                            ::lifecycle/stop-requested
                            ::lifecycle/polling-stopped
                            ::lifecycle/connector-stopped
                            ::lifecycle/completion-observed]
                           late-anomaly)
        cancelled-trace   (-> [::lifecycle/start-requested
                               ::lifecycle/engine-submission-started
                               ::lifecycle/engine-invocation-cancelled]
                              (lifecycle/append-observation rejection))
        graceful-trace    [::lifecycle/start-requested
                           ::lifecycle/engine-submission-started
                           ::lifecycle/engine-invocation-started
                           ::lifecycle/connector-started
                           ::lifecycle/polling-started
                           ::lifecycle/batch-admitted
                           ::lifecycle/batch-handled
                           ::lifecycle/batch-acknowledged
                           ::lifecycle/stop-requested
                           ::lifecycle/polling-stopped
                           ::lifecycle/connector-stopped
                           ::lifecycle/completion-observed]]
    (is (= :upstream-failure
           (:debezium-embedded/cause (lifecycle/terminal-anomaly failed-trace))))
    (is (nil? (lifecycle/terminal-anomaly successful-trace)))
    (is (nil? (lifecycle/terminal-anomaly cancelled-trace)))
    (is (true? (lifecycle/graceful-completion? graceful-trace)))))

(deftest normal-start-trace-becomes-capturing
  (is (= :debezium-embedded.lifecycle/ready
         (lifecycle/phase [])))
  (is (= :debezium-embedded.lifecycle/starting
         (lifecycle/phase [::lifecycle/start-requested])))
  (is (= :debezium-embedded.lifecycle/capturing
         (lifecycle/phase (capturing-trace)))))

(deftest stop-condition-cannot-return-to-capturing
  (let [trace (into (capturing-trace)
                    [::lifecycle/stop-requested
                     ::lifecycle/polling-started])]
    (is (= :debezium-embedded.lifecycle/stopping
           (lifecycle/phase trace)))
    (is (false? (lifecycle/admitting? trace)))))

(deftest completion-from-capturing-retains-a-protocol-anomaly
  (let [trace (lifecycle/append-observation
                (capturing-trace)
                ::lifecycle/completion-observed)]
    (is (= ::lifecycle/completion-observed (nth trace 5)))
    (is (= :debezium-embedded.lifecycle/stopped
           (lifecycle/phase trace)))
    (is (= :cognitect.anomalies/fault
           (get-in (lifecycle/terminal-anomaly trace)
                   [:cognitect.anomalies/category])))))

(deftest failed-submission-is-terminal
  (let [anomaly {:observation                  ::lifecycle/engine-submission-anomaly
                 :cognitect.anomalies/category :cognitect.anomalies/fault
                 :debezium-embedded/cause      :submission-failed}]
    (is (= ::lifecycle/stopped
           (lifecycle/phase [::lifecycle/start-requested anomaly])))
    (is (= :submission-failed
           (:debezium-embedded/cause
             (lifecycle/terminal-anomaly [::lifecycle/start-requested anomaly]))))))

(deftest graceful-completion-requires-acknowledged-batches-and-stopped-connectors
  (is (true? (lifecycle/graceful-completion?
               (into (capturing-trace)
                     [::lifecycle/batch-admitted
                      ::lifecycle/batch-handled
                      ::lifecycle/batch-acknowledged
                      ::lifecycle/stop-requested
                      ::lifecycle/connector-stopped
                      ::lifecycle/completion-observed]))))
  (is (false? (lifecycle/graceful-completion?
                (into (capturing-trace)
                      [::lifecycle/batch-admitted
                       ::lifecycle/stop-requested
                       ::lifecycle/completion-observed])))))

(deftest polling-stop-after-stop-request-is-a-normal-shutdown-callback
  (let [trace (-> (capturing-trace)
                  (lifecycle/append-observation ::lifecycle/stop-requested)
                  (lifecycle/append-observation ::lifecycle/polling-stopped)
                  (lifecycle/append-observation ::lifecycle/connector-stopped)
                  (lifecycle/append-observation ::lifecycle/completion-observed))]
    (is (true? (lifecycle/graceful-completion? trace)))
    (is (nil? (lifecycle/terminal-anomaly trace)))))

(deftest failed-completion-is-terminal
  (let [failure {:observation                  ::lifecycle/completion-observed
                 :cognitect.anomalies/category :cognitect.anomalies/fault
                 :debezium-embedded/cause      :upstream-failure}
        trace   (lifecycle/append-observation
                  [::lifecycle/start-requested
                   ::lifecycle/engine-submission-started
                   ::lifecycle/engine-invocation-started
                   ::lifecycle/stop-requested]
                  failure)]
    (is (= :upstream-failure
           (:debezium-embedded/cause (lifecycle/terminal-anomaly trace))))))

(deftest anomaly-after-cancelled-invocation-is-diagnostic
  (let [trace [::lifecycle/start-requested
               ::lifecycle/engine-submission-started
               ::lifecycle/stop-requested
               ::lifecycle/engine-invocation-cancelled
               {:observation                  ::lifecycle/engine-submission-anomaly
                :cognitect.anomalies/category :cognitect.anomalies/fault}]]
    (is (nil? (lifecycle/terminal-anomaly trace)))))

(deftest duplicate-polling-start-is-a-protocol-anomaly
  (let [trace (lifecycle/append-observation
                (capturing-trace)
                ::lifecycle/polling-started)]
    (is (= ::lifecycle/stopping (lifecycle/phase trace)))
    (is (= :cognitect.anomalies/fault
           (:cognitect.anomalies/category (lifecycle/terminal-anomaly trace))))))

(deftest duplicate-and-reordered-connector-callbacks-are-protocol-anomalies
  (doseq [[observations observation]
          [[(conj (started-engine-trace) ::lifecycle/connector-started)
            ::lifecycle/connector-started]
           [(started-engine-trace)
            ::lifecycle/connector-stopped]]
          :let [trace (lifecycle/append-observation observations observation)]]
    (is (= ::lifecycle/stopping (lifecycle/phase trace)))
    (is (= :cognitect.anomalies/fault
           (:cognitect.anomalies/category (lifecycle/terminal-anomaly trace))))))

(deftest connector-stop-is-an-admission-barrier
  (let [trace (lifecycle/append-observation
                (conj (started-engine-trace) ::lifecycle/connector-started)
                ::lifecycle/connector-stopped)]
    (is (= ::lifecycle/stopping (lifecycle/phase trace)))
    (is (false? (lifecycle/admitting? trace)))
    (is (= ::lifecycle/stopping
           (lifecycle/phase (conj trace ::lifecycle/polling-started))))))

(deftest polling-cannot-start-before-the-connector
  (let [observations (started-engine-trace)
        trace        (lifecycle/append-observation observations
                                                   ::lifecycle/polling-started)]
    (is (= ::lifecycle/stopping
           (lifecycle/phase (conj observations ::lifecycle/polling-started))))
    (is (= :cognitect.anomalies/fault
           (:cognitect.anomalies/category (lifecycle/terminal-anomaly trace))))))

(deftest polling-cannot-start-before-engine-submission
  (let [trace (lifecycle/append-observation
                [::lifecycle/start-requested
                 ::lifecycle/connector-started]
                ::lifecycle/polling-started)]
    (is (= ::lifecycle/stopping (lifecycle/phase trace)))
    (is (false? (lifecycle/admitting? trace)))
    (is (= :cognitect.anomalies/fault
           (:cognitect.anomalies/category (lifecycle/terminal-anomaly trace))))))
