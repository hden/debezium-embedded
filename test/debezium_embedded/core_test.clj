(ns debezium-embedded.core-test
  (:require [clojure.test :refer :all]
            [debezium-embedded.core :refer :all]))

(deftest factories
  (testing "create-configuration"
    (is (create-configuration {"foo" "bar"})))

  (testing "create-always-offset-commit-policy"
    (is (create-always-offset-commit-policy)))

  (testing "create-periodic-offset-commit-policy"
    (is (create-periodic-offset-commit-policy 10)))

  (testing "create-batch-consumer"
    (is (create-batch-consumer println)))

  (testing "create-completion-callback"
    (is (create-completion-callback println)))

  (testing "create-connector-callback"
    (is (create-connector-callback println)))

  (testing "create-engine"
    (let [config {}
          consumer (create-batch-consumer println)]
      (is (create-engine {:config config
                          :consumer consumer})))))
