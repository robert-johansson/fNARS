(ns fNARS.utils
  (:require ["fs" :as fs]))

(defn greet [name]
  (println (str "Hello, " name "! Welcome to fNARS.")))

(defn process-file [filename]
  (if (.existsSync fs filename)
    (println (str "Processing file: " filename))
    (println (str "File not found: " filename))))
