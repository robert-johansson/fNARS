(ns fNARS.platform
  "Platform abstraction layer for JS/JVM interop.
   All JS interop in the core engine goes through these functions."
  (:refer-clojure :exclude [abs #?@(:cljs [imul])]))

(defn abs [x]
  #?(:cljs (js/Math.abs x)
     :clj  (Math/abs (double x))))

(defn pow [base exp]
  #?(:cljs (js/Math.pow base exp)
     :clj  (Math/pow (double base) (double exp))))

(defn trunc [x]
  #?(:cljs (js/Math.trunc x)
     :clj  (long x)))

(defn round [x]
  #?(:cljs (js/Math.round x)
     :clj  (Math/round (double x))))

(defn parse-float [s]
  #?(:cljs (js/parseFloat s)
     :clj  (parse-double s)))

(defn parse-int [s]
  #?(:cljs (js/parseInt s)
     :clj  (parse-long s)))

(defn imul
  "32-bit integer multiply (matches JS Math.imul)."
  [a b]
  #?(:cljs (js/Math.imul a b)
     :clj  (unchecked-multiply-int (int a) (int b))))

(defn format-decimal
  "Format a number to n decimal places."
  [x n]
  #?(:cljs (.toFixed x n)
     :clj  (format (str "%." n "f") (double x))))

(defn slurp-file
  "Read an entire file as a string. Node.js/JVM only."
  [filepath]
  #?(:cljs (str (.readFileSync (js/require "fs") filepath "utf8"))
     :clj  (clojure.core/slurp filepath)))

(defn exit
  "Exit the process with the given status code."
  [code]
  #?(:cljs (.exit js/process code)
     :clj  (System/exit code)))
