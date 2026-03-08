(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.fnars/fnars)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file "target/fnars.jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "lib/instaparse/src"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :ns-compile ['fNARS.core]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'fNARS.core}))
