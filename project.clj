(defproject hello-clj "0.1.0-SNAPSHOT"
  :description "hello clojure"
  :url "https://github.com/legokichi/hello-clj"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.typed "0.4.3"]
                 [org.clojure/data.json "0.2.6"]
                 [com.microsoft.azure/azure-media "0.9.8"]
                 [com.microsoft.azure/azure-storage "6.1.0"] ]
  :plugins [[lein-typed "0.4.2"] ]
  :core.typed {:check [hello_clj.core]}
  :main hello-clj.core
  :repositories {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"}
  :injections [(require 'clojure.core.typed)
               (clojure.core.typed/install) ] )