(ns fNARS.truth-values)

(defn u-not
  [arg]
  (dec arg))

(defn u-and
  [& args]
  (reduce * args))

(defn u-or
  [& args]
  (- 1 (reduce * (map #(- 1 %) args))))

(defn f-rev
  [f1 c1 f2 c2]
  (let [f (/ (+ (* f1 c1 (- 1 c2))
                (* f2 c2 (- 1 c1)))
             (+ (* c1 (- 1 c2))
                (* c2 (- 1 c1))))
        c (/ (+ (* c1 (- 1 c2))
                (* c2 (- 1 c1)))
             (+ (* c1 (- 1 c2))
                (* c2 (- 1 c1))
                (* (- 1 c1) (- 1 c2))))]
    [f c]))

(defn f-exp
  [f c]
  (+ (* c (- f 0.5)) 0.5))

(defn f-ded
  [f1 c1 f2 c2]
  [(u-and f1 f2) (u-and f1 c1 f2 c2)])

(defn f-abd
  [f1 c1 f2 c2]
  (let [w+ (u-and f1 c1 f2 c2)
        w (u-and f1 c1 c2)
        f (/ w+ w)
        c (/ w (inc w))]
    [f c]))

(defn f-ind
  [f1 c1 f2 c2]
  (let [w+ (u-and f1 c1 f2 c2)
        w (u-and f2 c1 c2)
        f (/ w+ w)
        c (/ w (inc w))]
    [f c]))

(defn f-exe
  [f1 c1 f2 c2]
  (let [w+ (u-and f1 c1 f2 c2)
        w w+
        f 1
        c (/ w (inc w))]
    [f c]))

(defn f-cnv
  [f1 c1]
  (let [w+ (u-and f1 c1)
        w w+
        f 1
        c (/ w (inc w))]
    [f c]))
