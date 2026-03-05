(ns fNARS.test-runner
  (:require [cljs.test :refer [run-tests]]
            [fNARS.truth-values-test]
            [fNARS.config-test]
            [fNARS.utils-test]
            [fNARS.core-test]
            [fNARS.nar-test]))

(defn -main [& _args]
  (run-tests 'fNARS.truth-values-test
             'fNARS.config-test
             'fNARS.utils-test
             'fNARS.core-test
             'fNARS.nar-test))
