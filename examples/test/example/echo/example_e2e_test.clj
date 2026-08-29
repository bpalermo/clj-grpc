(ns example.echo.example-e2e-test
  "Keeps the example honest: the example server on an ephemeral port, the
  example client's calls against it, every streaming shape asserted — so
  `bazel test //...` fails if the example ever drifts from the library."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-grpc.client :as client]
            [clj-grpc.server :as server]
            [example.echo.client :as echo-client]
            [example.echo.server :as echo-server]))

(deftest example-round-trips-every-shape
  (let [srv (echo-server/start {:address 0})
        ch (client/channel (str "localhost:" (server/port srv)) {:plaintext true})]
    (try
      (let [results (echo-client/call-all ch)]
        (testing "unary"
          (is (= "echo: hello" (:say results))))
        (testing "server streaming"
          (is (= ["again #1" "again #2" "again #3"] (:repeat results))))
        (testing "client streaming"
          (is (= {:message-count 3 :char-count 11} (:summarize results))))
        (testing "bidi"
          (is (= ["you said: ping" "you said: pong"] (:converse results)))))
      (finally
        (client/shutdown ch {:grace-ms 2000})
        (server/shutdown srv {:grace-ms 2000})))))
