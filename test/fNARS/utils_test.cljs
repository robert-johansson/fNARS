(ns fNARS.utils-test
  (:require [cljs.test :refer [deftest testing is]]
            [fNARS.utils :as utils]))

(deftest test-greet
  (testing "Greeting function"
    ;; Just verify it doesn't throw
    (utils/greet "Alice")))

(deftest test-process-file
  (testing "File processing"
    ;; Test with a file that exists
    (utils/process-file "config.edn")
    ;; Test with a file that doesn't exist
    (utils/process-file "missingfile.txt")))
