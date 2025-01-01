(ns fNARS.core-test
  (:require [clojure.test :refer :all]
            [fNARS.core :as core]
            [fNARS.config :as config]
            [fNARS.utils :as utils]))

#_(deftest test-main
  (testing "-main function"
    ;; Test when a name is provided
    (with-redefs [config/read-config (constantly {:welcome-message "Welcome to fNARS!"})]
      (let [output (with-out-str (core/-main "Alice"))]
        (is (.contains output "Hello, Alice! Welcome to fNARS!"))
        (is (.contains output "Loaded configuration: {:welcome-message \"Welcome to fNARS!\"}"))))

    ;; Test when no name is provided
    (let [output (with-out-str (core/-main))]
      (is (.contains output "Usage: bb -m fNARS.core <name>")))))
