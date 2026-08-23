(ns clj-grpc.bench-smoke-test
  "Both benchmark arms serve, answer, and agree — without paying criterium."
  (:require [clj-grpc.bench :as bench]
            [clojure.test :refer [deftest is]]))

(deftest arms-agree
  (let [grpc (#'bench/start-grpc)
        rest (#'bench/start-rest)]
    (try
      (doseq [[size payload] bench/payloads]
        (is (= ((:call grpc) payload) ((:call rest) payload))
            (str (name size) " payload answers agree")))
      (finally
        (#'bench/stop-grpc grpc)
        (#'bench/stop-rest rest)))))
