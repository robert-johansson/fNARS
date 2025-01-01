(ns fNARS.config-test
  (:require [clojure.test :refer :all]
            [fNARS.config :as config]
            [babashka.fs :as fs]
            [clojure.edn :as edn]))

(deftest test-read-config
  (testing "Reading configuration file"
    ;; Test when config.edn exists
    (with-redefs [fs/exists? (constantly true)
                  slurp (constantly "{:message \"Hello, world!\"}")]
      (let [result (config/read-config)]
        (is (= result {:message "Hello, world!"}))))

    ;; Test when config.edn does not exist
    (with-redefs [fs/exists? (constantly false)]
      (let [result (config/read-config)]
        (is (= result {:default-message "Default configuration loaded."}))))))

