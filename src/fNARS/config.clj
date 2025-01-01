(ns fNARS.config
  (:require [clojure.edn :as edn]
            [babashka.fs :as fs]))

(defn read-config []
  (if (fs/exists? "config.edn")
    (edn/read-string (slurp "config.edn"))
    {:default-message "Default configuration loaded."}))
