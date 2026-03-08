(ns fNARS.test-runner
  (:require #?(:cljs [cljs.test :refer [run-tests]]
               :clj  [clojure.test :refer [run-tests]])
            [fNARS.nar-test]
            [fNARS.snapshot-test]))

(defn -main [& _args]
  (run-tests 'fNARS.nar-test
             'fNARS.snapshot-test))
