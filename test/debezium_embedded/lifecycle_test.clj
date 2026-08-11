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
