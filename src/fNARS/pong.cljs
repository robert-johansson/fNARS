(ns fNARS.pong
  "Headless Pong for fNARS. Identical game physics to ONA's Pong_Test.h / Pong2_Test.h.
   Outputs per-tick CSV and periodic IMP (implication) dump lines."
  (:require [fNARS.nar :as nar]
            [fNARS.term :as term]
            [fNARS.event :as event]
            [fNARS.truth :as truth]
            [fNARS.shell :as shell]
            [fNARS.stamp :as stamp]
            [fNARS.decision :as decision]
            [clojure.string :as str]))

;; ============================================================
;; Implication dump
;; ============================================================

(defn- format-stamp [s]
  (str "[" (str/join "," (take-while pos? s)) "]"))

(defn dump-implications
  "Print IMP lines for all concepts with non-empty precondition-beliefs tables."
  [nar-state tick]
  (doseq [[concept-term concept] (:concepts nar-state)]
    (doseq [[op-id table] (:precondition-beliefs concept)]
      (doseq [imp (:items table)]
        (when (>= (:confidence (:truth imp)) 0.001)
          (println (str "IMP tick=" tick
                        " op=" op-id
                        " f=" (.toFixed (:frequency (:truth imp)) 6)
                        " c=" (.toFixed (:confidence (:truth imp)) 6)
                        " stamp=" (format-stamp (:stamp imp))
                        " term=\"" (shell/format-term (:term imp)) "\"")))))))

;; ============================================================
;; Game state helpers
;; ============================================================

(defn- clamp [lo hi x]
  (max lo (min hi x)))

;; ============================================================
;; Pong (2-state: ball_left / ball_right)
;; ============================================================

(defn pong-init-game
  "Initial game state for Pong."
  []
  {:ballX 25 :ballY 4 :batX 20 :batVX 0 :vX 1 :vY 1
   :bat-width 4 :szX 50 :szY 20
   :hits 0 :misses 0 :t 0})

(defn- find-execution
  "Find the first execution in NAR output. Returns op name string or nil."
  [output ops]
  (when-let [exe (first (filter #(= (:type %) :execution) output))]
    (:operation exe)))

(defn pong-tick
  "Run one pong tick. Returns [game-state nar-state].
   Game and NAR share the NAR's :rng-state, matching ONA's single global PRNG."
  [game nar]
  (let [{:keys [ballX ballY batX batVX vX vY bat-width szX szY hits misses t]} game
        ;; Determine and send belief
        belief (cond
                 (< batX ballX) "ball_right"
                 (< ballX batX) "ball_left"
                 :else nil)
        nar (if belief
              (nar/nar-add-input-belief nar (term/atomic-term (keyword belief)))
              nar)
        ;; Send goal
        nar (nar/nar-add-input-goal nar (term/atomic-term :good_nar))
        ;; Wall bouncing
        vX (cond (<= ballX 0) 1
                 (>= ballX (dec szX)) -1
                 :else vX)
        vY (cond (<= ballY 0) 1
                 (>= ballY (dec szY)) -1
                 :else vY)
        ;; Move ball
        ballX (+ ballX vX)
        ballY (+ ballY vY)
        ;; Hit/miss check
        [hits misses score nar]
        (if (== ballY 0)
          (if (<= (js/Math.abs (- ballX batX)) bat-width)
            [(inc hits) misses "hit"
             (nar/nar-add-input-belief nar (term/atomic-term :good_nar))]
            [hits (inc misses) "miss" nar])
          [hits misses "none" nar])
        ;; Ball reset (uses NAR's shared rng-state)
        [ballY ballX vX nar]
        (if (or (== ballY 0) (== ballX 0) (>= ballX (dec szX)))
          (let [[r1 nar] (decision/myrand nar)
                [r2 nar] (decision/myrand nar)
                [r3 nar] (decision/myrand nar)]
            [(+ (quot szY 2) (mod r1 (quot szY 2)))
             (mod r2 szX)
             (if (== (mod r3 2) 0) 1 -1)
             nar])
          [ballY ballX vX nar])
        ;; Check executions
        {:keys [output state]} (nar/nar-get-output nar)
        nar state
        execution (find-execution output #{"^left" "^right"})
        batVX (case execution
                "^left" -2
                "^right" 2
                batVX)
        ;; Move bat
        batX (clamp 0 (dec szX) (+ batX (* batVX (quot bat-width 2))))
        ;; Run 5 cycles
        nar (nar/nar-cycles nar 5)
        ;; Increment tick
        t (inc t)
        ratio (if (pos? (+ hits misses))
                (/ (double hits) (+ hits misses))
                0.0)]
    ;; Print CSV line
    (println (str t "," ballX "," ballY "," batX "," batVX ","
                  (or belief "none") ","
                  (if execution (subs execution 1) "none") ","
                  score "," hits "," misses "," (.toFixed ratio 6)))
    ;; Dump implications on score events or every 50 ticks
    (when (or (not= score "none") (== (mod t 50) 0))
      (dump-implications nar t))
    [{:ballX ballX :ballY ballY :batX batX :batVX batVX
      :vX vX :vY vY :bat-width bat-width :szX szX :szY szY
      :hits hits :misses misses :t t}
     nar]))

(defn run-pong
  "Run headless pong for n iterations."
  [iterations]
  (let [nar (-> (nar/nar-init)
                (nar/nar-add-operation "^left" (fn [s _] s))
                (nar/nar-add-operation "^right" (fn [s _] s)))]
    (println "tick,ballX,ballY,batX,batVX,belief,execution,score,hits,misses,ratio")
    (loop [game (pong-init-game)
           nar nar]
      (if (> (:t game) iterations)
        nil
        (let [[game nar] (pong-tick game nar)]
          (recur game nar))))))

;; ============================================================
;; Pong2 (3-state: ball_left / ball_right / ball_equal)
;; ============================================================

(defn pong2-init-game
  "Initial game state for Pong2."
  []
  {:ballX 25 :ballY 4 :batX 20 :batVX 0 :vX 1 :vY 1
   :bat-width 6 :szX 50 :szY 20
   :hits 0 :misses 0 :t 0})

(defn pong2-tick
  "Run one pong2 tick. Returns [game-state nar-state].
   Game and NAR share the NAR's :rng-state, matching ONA's single global PRNG."
  [game nar]
  (let [{:keys [ballX ballY batX batVX vX vY bat-width szX szY hits misses t]} game
        ;; Determine and send belief (3-state with batWidth margin)
        belief (cond
                 (<= batX (- ballX bat-width)) "ball_right"
                 (< (+ ballX bat-width) batX) "ball_left"
                 :else "ball_equal")
        nar (nar/nar-add-input-belief nar (term/atomic-term (keyword belief)))
        ;; Send goal
        nar (nar/nar-add-input-goal nar (term/atomic-term :good_nar))
        ;; Wall bouncing
        vX (cond (<= ballX 0) 1
                 (>= ballX (dec szX)) -1
                 :else vX)
        vY (cond (<= ballY 0) 1
                 (>= ballY (dec szY)) -1
                 :else vY)
        ;; Ball movement — t%2==-1 is dead code in C (never true for non-negative t)
        ;; so ballX never changes via physics
        ballY (+ ballY vY)
        ;; Hit/miss check
        [hits misses score nar]
        (if (== ballY 0)
          (if (<= (js/Math.abs (- ballX batX)) bat-width)
            [(inc hits) misses "hit"
             (nar/nar-add-input-belief nar (term/atomic-term :good_nar))]
            [hits (inc misses) "miss" nar])
          [hits misses "none" nar])
        ;; Ball reset (uses NAR's shared rng-state)
        [ballY ballX vX nar]
        (if (or (== ballY 0) (== ballX 0) (>= ballX (dec szX)))
          (let [[r1 nar] (decision/myrand nar)
                [r2 nar] (decision/myrand nar)
                [r3 nar] (decision/myrand nar)]
            [(+ (quot szY 2) (mod r1 (quot szY 2)))
             (mod r2 szX)
             (if (== (mod r3 2) 0) 1 -1)
             nar])
          [ballY ballX vX nar])
        ;; Check executions
        {:keys [output state]} (nar/nar-get-output nar)
        nar state
        execution (find-execution output #{"^left" "^right" "^stop"})
        batVX (case execution
                "^left" -3
                "^right" 3
                "^stop" 0
                batVX)
        ;; Move bat (wider clamp for pong2)
        batX (clamp (* (- bat-width) 2) (+ (dec szX) bat-width)
                    (+ batX (* batVX (quot bat-width 2))))
        ;; Run 5 cycles
        nar (nar/nar-cycles nar 5)
        ;; Increment tick
        t (inc t)
        ratio (if (pos? (+ hits misses))
                (/ (double hits) (+ hits misses))
                0.0)]
    ;; Print CSV line
    (println (str t "," ballX "," ballY "," batX "," batVX ","
                  belief ","
                  (if execution (subs execution 1) "none") ","
                  score "," hits "," misses "," (.toFixed ratio 6)))
    ;; Dump implications on score events or every 50 ticks
    (when (or (not= score "none") (== (mod t 50) 0))
      (dump-implications nar t))
    [{:ballX ballX :ballY ballY :batX batX :batVX batVX
      :vX vX :vY vY :bat-width bat-width :szX szX :szY szY
      :hits hits :misses misses :t t}
     nar]))

(defn run-pong2
  "Run headless pong2 for n iterations."
  [iterations]
  (let [nar (-> (nar/nar-init)
                (nar/nar-add-operation "^left" (fn [s _] s))
                (nar/nar-add-operation "^right" (fn [s _] s))
                (nar/nar-add-operation "^stop" (fn [s _] s)))]
    (println "tick,ballX,ballY,batX,batVX,belief,execution,score,hits,misses,ratio")
    (loop [game (pong2-init-game)
           nar nar]
      (if (> (:t game) iterations)
        nil
        (let [[game nar] (pong2-tick game nar)]
          (recur game nar))))))

;; ============================================================
;; Entry point
;; ============================================================

(defn -main [& args]
  (let [mode (or (first args) "pong")
        iterations (if (second args) (js/parseInt (second args)) 500)]
    (case mode
      "pong"  (run-pong iterations)
      "pong2" (run-pong2 iterations)
      (do (println (str "Unknown mode: " mode))
          (println "Usage: pong [pong|pong2] [iterations]")))))
