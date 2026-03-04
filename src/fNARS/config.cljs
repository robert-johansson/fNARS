(ns fNARS.config
  (:require [cljs.reader :as reader]
            ["fs" :as fs]))

(defn read-config []
  (if (.existsSync fs "config.edn")
    (reader/read-string (.toString (.readFileSync fs "config.edn")))
    {:default-message "Default configuration loaded."}))
