(defproject hello-clj "0.1.0-SNAPSHOT"
  :description "hello clojure"
  :url "https://github.com/legokichi/hello-clj"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/data.json "0.2.6"]
                 [com.microsoft.azure/azure-media "0.9.8"]
                 [com.microsoft.azure/azure-storage "6.1.0"]
                 [spyscope "0.1.5"]
                 [org.clojure/tools.trace "0.7.9"] ]
  :main hello-clj.core
  :injections [
    (require 'spyscope.core)
    (require 'clojure.tools.trace) ] )