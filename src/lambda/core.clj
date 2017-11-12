(ns lambda.core
  (:gen-class
   :methods [^:static [handler [Object com.amazonaws.services.lambda.runtime.Context] Object]])
  (:import (com.amazonaws.services.lambda.runtime Context))
  (:require [clojure.data.json :as json]
            [clojure.string :as s]
            [clojure.java.io :as io]))

(defn -handler [^Object req ^Context ctx]
  "hello world")