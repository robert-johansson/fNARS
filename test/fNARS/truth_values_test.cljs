(ns fNARS.truth-values-test
  (:require [cljs.test :refer [deftest testing is]]
            [fNARS.truth-values :refer [f-rev f-exp f-cnv f-ded f-abd f-ind f-exe]]))

(deftest test-f-rev
  (is (= [1.2999999999999998 0.5882352941176471]
         (f-rev 1 0.5 2 0.3)))
  (is (= [1.0138248847926268 0.9601769911504424]
         (f-rev 4 0.1 1 0.96))))

(deftest test-f-exp
  (is (= 2.75 (f-exp 1.4 2.5)))
  (is (= 9.5 (f-exp 2 6))))

(deftest test-f-cnv
  (let [[f c] (f-cnv 1 2)]
    (is (= 1 f))
    (is (< (js/Math.abs (- c (/ 2 3))) 0.0001))))

(deftest test-f-ded
  (is (= [3 24] (f-ded 1 2 3 4))))

(deftest test-f-abd
  (let [[f c] (f-abd 1 2 3 4)]
    (is (= 3 f))
    (is (< (js/Math.abs (- c (/ 8 9))) 0.0001))))

(deftest test-f-ind
  (let [[f c] (f-ind 3 6 1 7)]
    (is (= 3 f))
    (is (< (js/Math.abs (- c (/ 42 43))) 0.0001))))

(deftest test-f-exe
  (let [[f c] (f-exe 3 6 1 7)]
    (is (= 1 f))
    (is (< (js/Math.abs (- c (/ 126 127))) 0.0001))))
