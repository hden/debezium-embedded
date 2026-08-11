(ns debezium-embedded.lifecycle-test
  (:require
   [clojure.test :refer [deftest is]]
   [debezium-embedded.lifecycle :as lifecycle]))

(deftest normal-start-trace-becomes-capturing
  (is (= :debezium-embedded.lifecycle/ready
         (lifecycle/phase [])))
  (is (= :debezium-embedded.lifecycle/starting
         (lifecycle/phase [::lifecycle/start-requested])))
  (is (= :debezium-embedded.lifecycle/capturing
         (lifecycle/phase [::lifecycle/start-requested
                           ::lifecycle/run-submitted
                           ::lifecycle/polling-started]))))

(deftest stop-condition-cannot-return-to-capturing
  (let [trace [::lifecycle/start-requested
               ::lifecycle/run-submitted
               ::lifecycle/polling-started
               ::lifecycle/stop-requested
               ::lifecycle/polling-started]]
    (is (= :debezium-embedded.lifecycle/stopping
           (lifecycle/phase trace)))
    (is (false? (lifecycle/admitting? trace)))))

(deftest completion-from-capturing-retains-a-protocol-anomaly
  (let [trace (lifecycle/append-observation
                [::lifecycle/start-requested
                 ::lifecycle/run-submitted
                 ::lifecycle/polling-started]
                ::lifecycle/completion-observed)]
    (is (= ::lifecycle/completion-observed (nth trace 3)))
    (is (= :debezium-embedded.lifecycle/stopped
           (lifecycle/phase trace)))
    (is (= :cognitect.anomalies/fault
           (get-in (lifecycle/primary-anomaly trace)
                   [:cognitect.anomalies/category])))))

(deftest failed-submission-is-terminal
  (let [anomaly {:observation                  ::lifecycle/run-submission-anomaly
                 :cognitect.anomalies/category :cognitect.anomalies/fault
                 :debezium-embedded/cause      :submission-failed}]
    (is (= ::lifecycle/stopped
           (lifecycle/phase [::lifecycle/start-requested anomaly])))
    (is (= :submission-failed
           (:debezium-embedded/cause
             (lifecycle/primary-anomaly [::lifecycle/start-requested anomaly]))))))
