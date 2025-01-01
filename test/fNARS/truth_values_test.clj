(ns fNARS.truth-values-test
  (:require [clojure.test :refer :all]
            [fNARS.truth-values :refer :all]))

(deftest test-f-rev
  (is (= [1.2999999999999998 0.5882352941176471]
         (f-rev 1 0.5 2 0.3)))
  #_(is (= '()
           (run* [q] (f-rev [1 2] [2 0.6] q))))
  (is (= [1.0138248847926268 0.9601769911504424]
         (f-rev 4 0.1 1 0.96))))

(deftest test-f-exp
  (is (= 2.75 (f-exp 1.4 2.5)))
  (is (= 9.5 (f-exp 2 6))))

#_(deftest test-f-neg
    (is (= 0.2 (f-neg 1 2))))

(deftest test-f-cnv
  (is (= [1 2/3] (f-cnv 1 2))))

#_(deftest test-f-cnt
    (is (= [0 10/9] (f-cnt 3 5))))

(deftest test-f-ded
  (is (= [3 24] (f-ded 1 2 3 4))))

#_(deftest test-f-ana
    ()
    (is '([18 210]) (run* [q] (f-ana [3 5] [6 7] q))))

#_(deftest test-f-res
    (is '([7 32]) (run* [q] (f-res [7 8] [1 4] q))))

(deftest test-f-abd
  (is (= [3 8/9] (f-abd 1 2 3 4))))

(deftest test-f-ind
  (is (= [3 42/43] (f-ind 3 6 1 7))))

(deftest test-f-exe
  (is (= [1 126/127] (f-exe 3 6 1 7))))

#_(deftest test-f-com
    (is '() (run* [q] (f-com [7 2] [5 4] q)))
    (is '([5 8/9]) (run* [q] (f-com [1, 2], [5, 4] q))))

#_(deftest test-f-int
    (is '([5 8]) (run* [q] (f-int [1 2] [5 4] q))))

#_(deftest test-f-uni
    (is '([-9 16]) (run* [Q] (f-uni [3, 4], [6, 4], Q))))

#_(deftest test-f-dif
    (is '([-15 16]) (run* [Q] (f-dif [3, 4], [6, 4], Q))))

#_(deftest test-f-pnn
    (is '([-5 12]) (run* [Q] (f-dif [1, 6], [6, 2], Q))))

#_(deftest test-f-npp
    (is '([-5 -10]) (run* [Q] (f-npp [2, 1], [5, 2], Q))))

#_(deftest test-f-pnp
    (is '([-1 -10]) (run* [Q] (f-pnp [1, 5], [2, 2], Q))))

#_(deftest test-f-nnn
    (is '([3 -6]) (run* [Q] (f-pnp [-3, 2], [2, -1], Q))))