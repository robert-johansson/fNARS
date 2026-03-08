(ns fNARS.nar-test
  "Tests for the fNARS sensorimotor system.
   Tests truth functions, terms, stamps, inference, variables,
   the NAR API, parser, and shell."
  (:require [cljs.test :refer [deftest testing is are]]
            [fNARS.truth :as truth]
            [fNARS.term :as term]
            [fNARS.stamp :as stamp]
            [fNARS.event :as event]
            [fNARS.inference :as inference]
            [fNARS.narsese :as narsese]
            [fNARS.variable :as variable]
            [fNARS.priority-queue :as pq]
            [fNARS.table :as table]
            [fNARS.concept :as concept]
            [fNARS.memory :as memory]
            [fNARS.nar :as nar]
            [fNARS.parser :as parser]
            [fNARS.shell :as shell]
            [fNARS.nar-config :as nar-config]))

;; Helpers
(defn approx= [a b & [eps]]
  (< (js/Math.abs (- a b)) (or eps 0.001)))

;; === Truth Value Tests ===

(deftest test-w2c-c2w
  (testing "w2c and c2w are inverse"
    (is (approx= (truth/w2c 1.0 1.0) 0.5))
    (is (approx= (truth/c2w 0.5 1.0) 1.0))
    (is (approx= (truth/w2c (truth/c2w 0.9 1.0) 1.0) 0.9))))

(deftest test-truth-expectation
  (is (approx= (truth/truth-expectation {:frequency 1.0 :confidence 0.9}) 0.95))
  (is (approx= (truth/truth-expectation {:frequency 0.0 :confidence 0.9}) 0.05))
  (is (approx= (truth/truth-expectation {:frequency 0.5 :confidence 0.5}) 0.5)))

(deftest test-truth-revision
  (let [config {:horizon 1.0 :max-confidence 0.99}
        v1 {:frequency 1.0 :confidence 0.9}
        v2 {:frequency 1.0 :confidence 0.9}
        result (truth/truth-revision v1 v2 config)]
    (is (approx= (:frequency result) 1.0))
    (is (> (:confidence result) 0.9))))

(deftest test-truth-deduction
  (let [v1 {:frequency 1.0 :confidence 0.9}
        v2 {:frequency 1.0 :confidence 0.9}
        result (truth/truth-deduction v1 v2)]
    (is (approx= (:frequency result) 1.0))
    (is (approx= (:confidence result) 0.81))))

(deftest test-truth-induction
  (let [v1 {:frequency 1.0 :confidence 0.9}
        v2 {:frequency 1.0 :confidence 0.9}
        result (truth/truth-induction v1 v2 1.0)]
    (is (approx= (:frequency result) 1.0))
    ;; Induction: w2c(f2*c2*c1) = w2c(0.81) = 0.81/1.81 ≈ 0.4475
    (is (approx= (:confidence result) (/ 0.81 1.81)))))

(deftest test-truth-intersection
  (let [result (truth/truth-intersection {:frequency 0.8 :confidence 0.9}
                                         {:frequency 0.6 :confidence 0.8})]
    (is (approx= (:frequency result) 0.48))
    (is (approx= (:confidence result) 0.72))))

(deftest test-truth-eternalize
  (let [result (truth/truth-eternalize {:frequency 1.0 :confidence 0.9} 1.0)]
    (is (approx= (:frequency result) 1.0))
    (is (approx= (:confidence result) (/ 0.9 1.9)))))

(deftest test-truth-projection
  (let [v {:frequency 1.0 :confidence 0.9}
        result (truth/truth-projection v 10 15 0.8)]
    (is (approx= (:frequency result) 1.0))
    (is (approx= (:confidence result) (* 0.9 (js/Math.pow 0.8 5))))))

(deftest test-truth-goal-deduction
  (let [result (truth/truth-goal-deduction {:frequency 1.0 :confidence 0.9}
                                           {:frequency 1.0 :confidence 0.9})]
    (is (> (:confidence result) 0))))

;; === Term Tests ===

(deftest test-term-atomic
  (let [t (term/atomic-term :cat)]
    (is (= (term/term-root t) :cat))
    (is (= (term/term-complexity t) 1))))

(deftest test-term-compound
  (let [subj (term/atomic-term :cat)
        pred (term/atomic-term :animal)
        inh (-> (term/atomic-term term/inheritance)
                (term/override-subterm 1 subj)
                (term/override-subterm 2 pred))]
    (is (= (term/term-root inh) term/inheritance))
    (is (= (term/term-root (term/extract-subterm inh 1)) :cat))
    (is (= (term/term-root (term/extract-subterm inh 2)) :animal))
    (is (= (term/term-complexity inh) 3))))

(deftest test-term-equal
  (let [a (narsese/make-inheritance (term/atomic-term :cat) (term/atomic-term :animal))
        b (narsese/make-inheritance (term/atomic-term :cat) (term/atomic-term :animal))
        c (narsese/make-inheritance (term/atomic-term :dog) (term/atomic-term :animal))]
    (is (term/term-equal a b))
    (is (not (term/term-equal a c)))))

(deftest test-term-has-atom
  (let [t (narsese/make-inheritance (term/atomic-term :cat) (term/atomic-term :animal))]
    (is (term/has-atom t :cat))
    (is (term/has-atom t :animal))
    (is (not (term/has-atom t :dog)))))

;; === Stamp Tests ===

(deftest test-stamp-make
  (let [s1 [1 2 3]
        s2 [4 5 6]
        merged (stamp/stamp-make s1 s2)]
    ;; Zip merge: 1, 4, 2, 5, 3, 6
    (is (= merged [1 4 2 5 3 6]))))

(deftest test-stamp-overlap
  (is (stamp/stamp-overlap? [1 2 3] [3 4 5]))
  (is (not (stamp/stamp-overlap? [1 2 3] [4 5 6]))))

(deftest test-stamp-equal
  (is (stamp/stamp-equal [1 2 3] [1 2 3]))
  (is (stamp/stamp-equal [1 2 3] [3 1 2]))
  (is (not (stamp/stamp-equal [1 2 3] [1 2 4]))))

;; === Event Tests ===

(deftest test-make-input-event
  (let [t (term/atomic-term :green)
        [ev counter] (event/make-input-event t event/event-type-belief
                                              truth/default-truth 100 1)]
    (is (= (:type ev) event/event-type-belief))
    (is (= (:occurrence-time ev) 100))
    (is (= (:stamp ev) [1]))
    (is (= counter 2))))

(deftest test-event-eternalized
  (let [t (term/atomic-term :green)
        [ev _] (event/make-input-event t event/event-type-belief
                                        truth/default-truth 100 1)
        eternal (event/event-eternalized ev 1.0)]
    (is (= (:occurrence-time eternal) truth/occurrence-eternal))
    (is (< (:confidence (:truth eternal)) (:confidence (:truth ev))))))

;; === Narsese Utility Tests ===

(deftest test-make-sequence
  (let [a (term/atomic-term :green)
        b (term/atomic-term :blue)
        {:keys [term success?]} (narsese/make-sequence a b)]
    (is success?)
    (is (= (term/term-root term) term/sequence*))
    (is (= (term/term-root (term/extract-subterm term 1)) :green))
    (is (= (term/term-root (term/extract-subterm term 2)) :blue))))

(deftest test-is-operation
  (let [op (term/atomic-term (keyword "^pick"))]
    (is (narsese/is-operation? op))))

(deftest test-get-precondition-without-op
  (let [precond (term/atomic-term :green)
        op (term/atomic-term (keyword "^pick"))
        {:keys [term]} (narsese/make-sequence precond op)
        without-op (narsese/get-precondition-without-op term)]
    (is (= (term/term-root without-op) :green))))

;; === Inference Tests ===

(deftest test-belief-intersection
  (let [config nar-config/default-config
        a (event/make-event {:term (term/atomic-term :green)
                             :type event/event-type-belief
                             :truth {:frequency 1.0 :confidence 0.9}
                             :stamp [1]
                             :occurrence-time 10
                             :creation-time 10})
        b (event/make-event {:term (term/atomic-term :blue)
                             :type event/event-type-belief
                             :truth {:frequency 1.0 :confidence 0.9}
                             :stamp [2]
                             :occurrence-time 11
                             :creation-time 11})
        result (inference/belief-intersection a b config)]
    (is (some? result))
    (is (= (term/term-root (:term result)) term/sequence*))))

(deftest test-belief-induction
  (let [config nar-config/default-config
        a (event/make-event {:term (term/atomic-term :green)
                             :type event/event-type-belief
                             :truth {:frequency 1.0 :confidence 0.9}
                             :stamp [1]
                             :occurrence-time 10
                             :creation-time 10})
        b (event/make-event {:term (term/atomic-term :reward)
                             :type event/event-type-belief
                             :truth {:frequency 1.0 :confidence 0.9}
                             :stamp [2]
                             :occurrence-time 11
                             :creation-time 11})
        result (inference/belief-induction a b config)]
    (is (some? result))
    (is (= (term/term-root (:term result)) term/temporal-implication))
    (is (approx= (:occurrence-time-offset result) 1.0))))

(deftest test-revision-and-choice
  (let [config nar-config/default-config
        a (event/make-event {:term (term/atomic-term :green)
                             :type event/event-type-belief
                             :truth {:frequency 1.0 :confidence 0.9}
                             :stamp [1]
                             :occurrence-time 10
                             :creation-time 10})
        b (event/make-event {:term (term/atomic-term :green)
                             :type event/event-type-belief
                             :truth {:frequency 1.0 :confidence 0.9}
                             :stamp [2]
                             :occurrence-time 10
                             :creation-time 10})
        {:keys [event revised?]} (inference/revision-and-choice a b 10 config)]
    ;; Same term, same time, no overlap -> revision
    (is revised?)
    (is (> (:confidence (:truth event)) 0.9))))

;; === Variable Tests ===

(deftest test-variable-predicates
  (is (variable/independent-var? :$1))
  (is (variable/dependent-var? :#1))
  (is (variable/query-var? :?1))
  (is (variable/variable? :$1))
  (is (not (variable/variable? :cat))))

(deftest test-unify
  (let [general (-> (term/atomic-term term/inheritance)
                    (term/override-subterm 1 (term/atomic-term :$1))
                    (term/override-subterm 2 (term/atomic-term :animal)))
        specific (narsese/make-inheritance (term/atomic-term :cat) (term/atomic-term :animal))
        result (variable/unify general specific)]
    (is (:success result))
    (is (= (term/term-root (get (:substitution result) :$1)) :cat))))

(deftest test-apply-substitute
  (let [general (-> (term/atomic-term term/inheritance)
                    (term/override-subterm 1 (term/atomic-term :$1))
                    (term/override-subterm 2 (term/atomic-term :animal)))
        sub {:$1 (term/atomic-term :cat)}
        result (variable/apply-substitute general sub)]
    (is (= (term/term-root (term/extract-subterm result 1)) :cat))))

(deftest test-normalize-variables
  (let [t (-> (term/atomic-term term/inheritance)
              (term/override-subterm 1 (term/atomic-term :$5))
              (term/override-subterm 2 (term/atomic-term :$5)))
        normalized (variable/normalize-variables t)]
    (is (= (get normalized 1) :$1))
    (is (= (get normalized 2) :$1))))

;; === Priority Queue Tests ===

(deftest test-pq-push-pop
  (let [pq (pq/pq-init 3)
        {:keys [pq]} (pq/pq-push pq 0.5 :a)
        {:keys [pq]} (pq/pq-push pq 0.8 :b)
        {:keys [pq]} (pq/pq-push pq 0.3 :c)
        result (pq/pq-pop-max pq)]
    (is (= (:item result) :b))
    (is (= (:priority result) 0.8))))

(deftest test-pq-eviction
  (let [pq (pq/pq-init 2)
        {:keys [pq]} (pq/pq-push pq 0.5 :a)
        {:keys [pq]} (pq/pq-push pq 0.8 :b)
        {:keys [pq evicted]} (pq/pq-push pq 0.9 :c)]
    ;; Should evict :a (lowest priority)
    (is (some? evicted))
    (is (= (:item evicted) :a))
    (is (= (pq/pq-count pq) 2))))

;; === Parser Tests ===

(deftest test-parse-simple-belief
  (let [result (parser/parse-narsese "<cat --> animal>.")]
    (is (some? result))
    (is (= (:type result) :belief))
    (is (= (term/term-root (:term result)) term/inheritance))))

(deftest test-parse-goal
  (let [result (parser/parse-narsese "<reward --> good>! :|:")]
    (is (some? result))
    (is (= (:type result) :goal))
    (is (= (:tense result) :present))))

(deftest test-parse-with-truth
  (let [result (parser/parse-narsese "<cat --> animal>. {0.8 0.7}")]
    (is (some? result))
    (is (approx= (:frequency (:truth result)) 0.8))
    (is (approx= (:confidence (:truth result)) 0.7))))

(deftest test-parse-sequence
  (let [result (parser/parse-narsese "(&/ <green --> seen> ^pick). :|:")]
    (is (some? result))
    (is (= (:tense result) :present))))

;; === NAR Integration Tests ===

(deftest test-nar-init
  (let [state (nar/nar-init)]
    (is (= (:current-time state) 1))
    (is (= (count (:concepts state)) 0))))

(deftest test-nar-add-belief
  (let [state (nar/nar-init)
        t (narsese/make-inheritance (term/atomic-term :cat) (term/atomic-term :animal))
        state (nar/nar-add-input-belief state t)]
    (is (> (:current-time state) 1))
    (is (pos? (count (:concepts state))))))

(deftest test-nar-add-operation
  (let [state (nar/nar-init)
        state (nar/nar-add-operation state "^pick" (fn [s _] s))]
    (is (= (count (:operations state)) 1))))

(deftest test-nar-cycles
  (let [state (nar/nar-init)
        t (narsese/make-inheritance (term/atomic-term :green) (term/atomic-term :seen))
        state (nar/nar-add-input-belief state t)
        state (nar/nar-cycles state 10)]
    (is (> (:current-time state) 10))))

;; === Shell Tests ===

(deftest test-shell-process-input
  (let [state (nar/nar-init)]
    (testing "numeric input runs cycles"
      (let [{:keys [state]} (shell/process-input state "5")]
        (is (> (:current-time state) 1))))

    (testing "reset command"
      (let [{:keys [state output]} (shell/process-input state "*reset")]
        (is (= output "Reset."))
        (is (= (count (:concepts state)) 0))))

    (testing "concepts command"
      (let [{:keys [output]} (shell/process-input state "*concepts")]
        (is (string? output))))))
