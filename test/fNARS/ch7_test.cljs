(ns fNARS.ch7-test
  "Chapter 7: Identity Matching to Sample - generalization via variables.
   Tests that fNARS learns identity matching with A1/A2 and generalizes
   to novel A3/A4 via dependent variable introduction."
  (:require [fNARS.nar :as nar]
            [fNARS.term :as term]
            [fNARS.event :as event]
            [fNARS.truth :as truth]
            [fNARS.narsese :as narsese]
            [fNARS.shell :as shell]
            [fNARS.variable :as variable]
            [fNARS.memory :as memory]))

;; ============================================================
;; Helpers
;; ============================================================

(defn feed-lines [state lines]
  (reduce
    (fn [{:keys [state outputs]} line]
      (let [{:keys [state output]} (shell/process-input state line)]
        {:state state :outputs (conj outputs output)}))
    {:state state :outputs []}
    lines))

(defn get-execution [outputs]
  (some (fn [s]
          (when (and (string? s) (.includes s "EXE: "))
            (let [idx (.indexOf s "EXE: ")]
              (second (.split (.substring s idx) " ")))))
        outputs))

;; ============================================================
;; Conditions
;; ============================================================

(def training-conditions
  [{:sample "<A1 --> [sample]>. :|:" :left "<A1 --> [left]>. :|:" :right "<A2 --> [right]>. :|:" :op "^left"}
   {:sample "<A1 --> [sample]>. :|:" :left "<A2 --> [left]>. :|:" :right "<A1 --> [right]>. :|:" :op "^right"}
   {:sample "<A2 --> [sample]>. :|:" :left "<A1 --> [left]>. :|:" :right "<A2 --> [right]>. :|:" :op "^right"}
   {:sample "<A2 --> [sample]>. :|:" :left "<A2 --> [left]>. :|:" :right "<A1 --> [right]>. :|:" :op "^left"}])

(def novel-conditions
  [{:sample "<A3 --> [sample]>. :|:" :left "<A3 --> [left]>. :|:" :right "<A4 --> [right]>. :|:" :op "^left"}
   {:sample "<A3 --> [sample]>. :|:" :left "<A4 --> [left]>. :|:" :right "<A3 --> [right]>. :|:" :op "^right"}
   {:sample "<A4 --> [sample]>. :|:" :left "<A3 --> [left]>. :|:" :right "<A4 --> [right]>. :|:" :op "^right"}
   {:sample "<A4 --> [sample]>. :|:" :left "<A4 --> [left]>. :|:" :right "<A3 --> [right]>. :|:" :op "^left"}])

;; ============================================================
;; Training & Testing
;; ============================================================

(defn train-trial [state trial]
  (:state (feed-lines state [(:sample trial) (:left trial) (:right trial)
                              (str (:op trial) ". :|:") "G. :|:" "50"])))

(defn test-trial [state trial]
  (let [{:keys [state outputs]}
        (feed-lines state [(:sample trial) (:left trial) (:right trial) "G! :|:"])
        executed (get-execution outputs)
        {:keys [state]} (feed-lines state ["50"])]
    {:state state :correct? (= executed (:op trial)) :executed executed}))

(defn test-block [state conditions]
  (reduce
    (fn [{:keys [state correct]} trial]
      (let [{:keys [state correct?]} (test-trial state trial)]
        {:state state :correct (if correct? (inc correct) correct)}))
    {:state state :correct 0}
    conditions))

(defn has-dep-var-implication? [state]
  (some (fn [[_ concept]]
          (some (fn [[op-id table]]
                  (when (and table (pos? op-id))
                    (some #(variable/has-variable? (:term %) false true false)
                          (:items table))))
                (:precondition-beliefs concept)))
        (:concepts state)))

;; ============================================================
;; Main test
;; ============================================================

(defn test-identity-matching []
  (println "\n=== Chapter 7: Identity Matching to Sample ===")
  (let [state (-> (nar/nar-init)
                  (nar/nar-add-operation "^left" (fn [s _] s))
                  (nar/nar-add-operation "^right" (fn [s _] s)))
        state (assoc-in state [:config :motor-babbling-chance] 0.0)

        ;; Train: 5 blocks of all 4 conditions
        _ (println "  Training: 5 blocks x 4 conditions")
        state (loop [s state i 0]
                (if (>= i 5) s
                  (recur (reduce train-trial s training-conditions) (inc i))))

        has-var? (has-dep-var-implication? state)
        _ (println "  Variable implication formed:" has-var?)

        ;; Test novel A3/A4: 4 blocks
        _ (println "  Testing novel A3/A4 (generalization):")
        novel-results
        (reduce
          (fn [{:keys [state total]} _]
            (let [{:keys [state correct]} (test-block state novel-conditions)]
              {:state state :total (+ total correct)}))
          {:state state :total 0}
          (range 4))
        novel-correct (:total novel-results)
        novel-total 16
        novel-accuracy (/ novel-correct novel-total)]

    (println (str "    " novel-correct "/" novel-total " correct ("
                  (int (* 100 novel-accuracy)) "%)"))

    (let [pass-var? has-var?
          pass-novel? (> novel-accuracy 0.5)]
      (println "\n  Checks:")
      (println "    Variable implication:" (if pass-var? "PASS" "FAIL"))
      (println "    Novel accuracy > 50%:" (if pass-novel? "PASS" "FAIL")
               (str "(" (int (* 100 novel-accuracy)) "%)"))
      (and pass-var? pass-novel?))))

(defn run-all []
  (println "\n========================================")
  (println "Chapter 7: Identity Matching Tests")
  (println "========================================")
  (let [result (test-identity-matching)]
    (println "\n========================================")
    (println (str "Result: " (if result "PASS" "FAIL")))
    (println "========================================\n")))

(run-all)
