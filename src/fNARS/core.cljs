(ns fNARS.core
  (:require [fNARS.config :refer [read-config]]
            [fNARS.utils :refer [process-file]]))

(defn -main [& args]
  (let [name (first args)
        config (read-config)
        welcome-message (:welcome-message config)]
    (if name
      (do
        (println (str "Hello, " name "! " welcome-message))
        (println "Loaded configuration:" config))
      (println "Usage: nbb -m fNARS.core <name>"))))
