(ns fNARS.utils-test
  (:require [clojure.test :refer :all]
            [fNARS.utils :as utils]
            [babashka.fs :as fs]))

(deftest test-greet
  (testing "Greeting function"
    (with-out-str
      (let [output (with-out-str (utils/greet "Alice"))]
        (is (= output "Hello, Alice! Welcome to fNARS.\n"))))))

(deftest test-process-file
  (testing "File processing"
    ;; Test when file exists
    (with-redefs [fs/exists? (constantly true)]
      (let [output (with-out-str (utils/process-file "testfile.txt"))]
        (is (= output "Processing file: testfile.txt\n"))))

    ;; Test when file does not exist
    (with-redefs [fs/exists? (constantly false)]
      (let [output (with-out-str (utils/process-file "missingfile.txt"))]
        (is (= output "File not found: missingfile.txt\n"))))))
