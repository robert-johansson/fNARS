(ns fNARS.core
  "fNARS - Functional Non-Axiomatic Reasoning System.
   Interactive REPL entry point."
  (:require [fNARS.nar :as nar]
            [fNARS.shell :as shell]
            [fNARS.nar-config :as nar-config]
            ["readline" :as readline]))

(defn -main [& args]
  (println "fNARS - Functional Non-Axiomatic Reasoning System")
  (println "Sensorimotor ONA port (NAL 6-8)")
  (println "Type Narsese statements, numbers (for cycles), or *commands.")
  (println "")

  (let [state (atom (nar/nar-init))
        rl (.createInterface readline
             #js {:input js/process.stdin
                  :output js/process.stdout
                  :prompt ">> "})]

    (.on rl "line"
      (fn [line]
        (let [{:keys [state output]} (shell/process-input @state line)
              new-state state]
          (reset! state new-state)
          (when (seq output)
            (println output))
          (.prompt rl))))

    (.on rl "close"
      (fn []
        (println "\nBye.")
        (.exit js/process 0)))

    (.prompt rl)))
