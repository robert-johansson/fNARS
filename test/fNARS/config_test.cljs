(ns fNARS.config-test
  (:require [cljs.test :refer [deftest testing is]]
            [fNARS.config :as config]))

(deftest test-read-config
  (testing "Reading configuration file"
    ;; Test reading the actual config.edn (should exist in project root)
    (let [result (config/read-config)]
      (is (map? result)))))
