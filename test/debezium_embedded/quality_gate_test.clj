(ns debezium-embedded.quality-gate-test
  (:require
   [clojure.java.shell :refer [sh]]
   [clojure.test :refer [deftest is]]))

(defn- evaluate-report [report]
  (sh "sh" "scripts/check-crap-threshold" "30" :in report))

(def ^:private crap-report-header
  "CRAP Report\n===========\nFunction Namespace CC Cov% CRAP\n-----------------------------------\n")

(defn- crap-report [entry]
  (str crap-report-header entry "\n"))

(deftest quality-gate-accepts-scores-below-the-threshold
  (let [{:keys [exit]} (evaluate-report (crap-report "simple-fn sample.core 1 100.0% 1.0"))]
    (is (zero? exit))))

(deftest quality-gate-rejects-a-score-at-the-threshold
  (let [{:keys [exit out]} (evaluate-report (crap-report "risky-fn sample.core 10 60.0% 30.0"))]
    (is (= 1 exit))
    (is (re-find #"risky-fn" out))))
