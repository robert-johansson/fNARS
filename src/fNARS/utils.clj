(ns fNARS.utils
  (:require [babashka.fs :as fs]))

(defn greet [name]
  (println (str "Hello, " name "! Welcome to fNARS.")))

(defn process-file [filename]
  (if (fs/exists? filename)
    (println (str "Processing file: " filename))
    (println (str "File not found: " filename))))
