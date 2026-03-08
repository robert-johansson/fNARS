(ns fNARS.test-runner
  (:require [cljs.test :refer [run-tests]]
            [fNARS.nar-test]
            [fNARS.snapshot-test]))

(defn -main [& _args]
  (run-tests 'fNARS.nar-test
             'fNARS.snapshot-test))
