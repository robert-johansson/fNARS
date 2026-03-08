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

;; -- Typed Array Primitives --

(defn make-int-array
  "Create a zero-filled integer array of size n."
  [n]
  #?(:cljs (js/Int32Array. n)
     :clj  (int-array n)))

(defn aget-int
  "Get element at index i from an integer array."
  #?(:clj {:inline (fn [arr i] `(aget ~(with-meta arr {:tag 'ints}) (int ~i)))})
  [arr i]
  #?(:cljs (aget arr i)
     :clj  (aget ^ints arr (int i))))

(defn aset-int!
  "Set element at index i in an integer array (mutating)."
  [arr i v]
  #?(:cljs (aset arr i v)
     :clj  (aset ^ints arr (int i) (int v))))

(defn copy-int-array
  "Create a copy of an integer array."
  [arr]
  #?(:cljs (.slice arr)
     :clj  (java.util.Arrays/copyOf ^ints arr (alength ^ints arr))))

(defn int-array-hash
  "Compute a hash code for an integer array."
  [arr]
  #?(:cljs (let [n (.-length arr)]
             (loop [i 0 h (int 1)]
               (if (>= i n)
                 h
                 (recur (inc i)
                        (bit-or 0 (+ (* 31 h) (aget arr i)))))))
     :clj  (java.util.Arrays/hashCode ^ints arr)))

(defn int-array-equals
  "Check if two integer arrays have equal contents."
  [a b]
  #?(:cljs (let [n (.-length a)]
             (if (not= n (.-length b))
               false
               (loop [i 0]
                 (if (>= i n)
                   true
                   (if (== (aget a i) (aget b i))
                     (recur (inc i))
                     false)))))
     :clj  (java.util.Arrays/equals ^ints a ^ints b)))

(defn int-array-length
  "Get the length of an integer array."
  [arr]
  #?(:cljs (.-length arr)
     :clj  (alength ^ints arr)))

(defn int-array->seq
  "Convert an integer array to a seq."
  [arr]
  #?(:cljs (seq (js/Array.from arr))
     :clj  (seq arr)))
