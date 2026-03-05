(ns plots.generate-data
  (:require [fNARS.mprf :as mprf]
            ["fs" :as fs]))

(let [args (drop 2 (js->clj js/process.argv))
      exp-names (if (seq args) args ["op1" "op2" "op3"])]
  (doseq [exp-name exp-names]
    (if-let [experiment (get mprf/experiments exp-name)]
      (do
        (println (str "Running " exp-name "..."))
        (let [{:keys [log]} (mprf/run-experiment experiment)
              csv (mprf/format-csv experiment log)
              filename (str "plots/" exp-name "_log.csv")]
          (fs/writeFileSync filename csv)
          (println (str "  Wrote " filename))
          (mprf/print-results experiment log)))
      (println (str "Unknown experiment: " exp-name)))))
