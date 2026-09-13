(ns clj-grpc.names
  "Proto names to Clojure keys: the one derivation shared by the service layer
  (handler-map keys) and the interceptor call map (:method), so the two agree
  by construction rather than by two copies of a regex.

  Not under clj-grpc.impl on purpose: the native-image config marks that whole
  package run-time-initialized, and this is required at build time by every
  generated namespace."
  (:require [clojure.string :as string]))

(defn kebab
  "\"SayHello\" -> :say-hello, \"INVALID_ARGUMENT\" -> :invalid-argument."
  [s]
  (keyword
   (-> s
       (string/replace #"([a-z0-9])([A-Z])" "$1-$2")
       (string/replace "_" "-")
       (string/lower-case))))
