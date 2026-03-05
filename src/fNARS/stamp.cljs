(ns fNARS.stamp
  "Evidential base tracking matching ONA's Stamp.c.
   Stamps are vectors of evidence IDs, zip-merged, capped at STAMP_SIZE.")

(def ^:const stamp-size 10)

(defn stamp-make
  "Zip-merge two stamps, interleaving elements, capped at stamp-size.
   Matches C's Stamp_make exactly."
  [stamp1 stamp2]
  (let [n1 (count stamp1)
        n2 (count stamp2)]
    (loop [i 0 j 0 result [] s1-active true s2-active true]
      (if (or (>= (count result) stamp-size)
              (and (not s1-active) (not s2-active)))
        result
        (let [[result s1-active]
              (if (and s1-active (< i n1))
                [(conj result (nth stamp1 i)) true]
                [result false])
              result-after-s1 result
              [result s2-active]
              (if (and s2-active (< i n2) (< (count result-after-s1) stamp-size))
                [(conj result-after-s1 (nth stamp2 i)) true]
                [result-after-s1 (and s2-active (< i n2))])]
          (if (>= (count result) stamp-size)
            result
            (recur (inc i) j result s1-active s2-active)))))))

(defn stamp-overlap?
  "Check if two stamps share any evidence IDs.
   Matches C's Stamp_checkOverlap."
  [stamp1 stamp2]
  (let [s2-set (set stamp2)]
    (some s2-set stamp1)))

(defn stamp-equal
  "Check if two stamps contain the same evidence IDs (order-independent).
   Matches C's Stamp_Equal."
  [stamp1 stamp2]
  (= (set stamp1) (set stamp2)))

(defn stamp-has-duplicate
  "Check if a stamp has duplicate evidence IDs."
  [stamp]
  (not= (count stamp) (count (set stamp))))

(defn make-input-stamp
  "Create a stamp with a single evidence ID."
  [id]
  [id])
