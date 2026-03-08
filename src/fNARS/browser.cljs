(ns fNARS.browser
  "Browser entry point for fNARS.
   Provides a web REPL backed by the full NAR engine."
  (:require [fNARS.nar :as nar]
            [fNARS.shell :as shell]
            [clojure.string :as str]))

(defonce !state (atom (nar/nar-init)))
(defonce !history (atom []))
(defonce !history-idx (atom -1))

(defn get-el [id] (.getElementById js/document id))

(defn append-output [text css-class]
  (let [output (get-el "output")
        div (.createElement js/document "div")]
    (set! (.-className div) css-class)
    (set! (.-textContent div) text)
    (.appendChild output div)
    (set! (.-scrollTop output) (.-scrollHeight output))))

(defn process-input [line]
  (let [line (str/trim line)]
    (when (seq line)
      (append-output (str ">> " line) "input-line")
      (swap! !history conj line)
      (reset! !history-idx -1)
      (let [{:keys [state output]} (shell/process-input @!state line)]
        (reset! !state state)
        (when (seq output)
          (doseq [line (str/split-lines output)]
            (append-output line
              (cond
                (str/starts-with? line "EXE:") "exe-line"
                (str/starts-with? line "Derived:") "derived-line"
                (str/starts-with? line "Answer:") "answer-line"
                (str/starts-with? line "Input:") "input-echo-line"
                :else "output-line"))))))))

(defn handle-submit []
  (let [input (get-el "input")
        text (.-value input)]
    (process-input text)
    (set! (.-value input) "")
    (.focus input)))

(defn handle-keydown [e]
  (let [input (get-el "input")]
    (case (.-key e)
      "Enter"
      (do (.preventDefault e)
          (handle-submit))
      "ArrowUp"
      (let [h @!history
            idx (min (inc @!history-idx) (dec (count h)))]
        (when (pos? (count h))
          (reset! !history-idx idx)
          (set! (.-value input) (nth h (- (count h) 1 idx)))))
      "ArrowDown"
      (let [h @!history
            idx (dec @!history-idx)]
        (if (neg? idx)
          (do (reset! !history-idx -1)
              (set! (.-value input) ""))
          (do (reset! !history-idx idx)
              (set! (.-value input) (nth h (- (count h) 1 idx))))))
      nil)))

(defn load-example [lines]
  (let [output (get-el "output")]
    (set! (.-innerHTML output) ""))
  (reset! !state (nar/nar-init))
  (doseq [line lines]
    (process-input line)))

(defn ^:export init []
  (let [input (get-el "input")
        btn (get-el "submit")]
    (.addEventListener input "keydown" handle-keydown)
    (.addEventListener btn "click" handle-submit)
    ;; Wire up example buttons
    (when-let [el (get-el "ex-deduction")]
      (.addEventListener el "click"
        (fn [_] (load-example ["<cat --> animal>. :|:"
                               "<animal --> being>. :|:"
                               "5"
                               "<cat --> being>?"]))))
    (when-let [el (get-el "ex-conditioning")]
      (.addEventListener el "click"
        (fn [_] (load-example ["*setopname 1 ^left"
                               "<{light} --> [on]>. :|:"
                               "^left. :|:"
                               "<good --> nar>. :|:"
                               "5"
                               "<{light} --> [on]>. :|:"
                               "^left. :|:"
                               "<good --> nar>. :|:"
                               "5"
                               "<{light} --> [on]>. :|:"
                               "^left. :|:"
                               "<good --> nar>. :|:"
                               "5"
                               "<{light} --> [on]>. :|:"
                               "<good --> nar>! :|:"]))))
    (when-let [el (get-el "ex-reset")]
      (.addEventListener el "click"
        (fn [_]
          (set! (.-innerHTML (get-el "output")) "")
          (reset! !state (nar/nar-init))
          (append-output "Reset." "output-line"))))
    (.focus input)
    (append-output "fNARS - Functional Non-Axiomatic Reasoning System" "output-line")
    (append-output "Type Narsese statements, numbers (for cycles), or *commands." "output-line")))
