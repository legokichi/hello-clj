(ns hello-clj.core
  (:use [amazonica.aws.lambda])
  (:gen-class main true))

(defn -main
  [& args]
  (println "hello clojure"))