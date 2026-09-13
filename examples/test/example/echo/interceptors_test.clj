(ns example.echo.interceptors-test
  "The interceptor example, round-tripped: the bearer token admitted and the
  stamped response header captured on the client; the token's absence
  refused with the status and trailer a browser would expect."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-grpc.client :as client]
            [clj-grpc.metadata :as metadata]
            [clj-grpc.server :as server]
            [example.echo.client :as echo-client]
            [example.echo.echo :as echo]
            [example.echo.interceptors :as interceptors]
            [example.echo.server :as echo-server])
  (:import [io.grpc Status$Code StatusRuntimeException]))

(deftest token-admitted-token-missing-refused
  (let [srv (echo-server/start {:address 0
                                :interceptors [interceptors/request-log
                                               (interceptors/require-token "secret")]})
        target (str "localhost:" (server/port srv))
        served-by (promise)
        with-token (client/channel target {:plaintext true
                                           :interceptors [(interceptors/bearer "secret" #(deliver served-by %))]})
        without    (client/channel target {:plaintext true})]
    (try
      (testing "with the token, every shape serves and the response header comes back"
        (let [results (echo-client/call-all with-token)]
          (is (= "echo: hello" (:say results)))
          (is (= ["again #1" "again #2" "again #3"] (:repeat results)))
          (is (= {:message-count 3 :char-count 11} (:summarize results)))
          (is (= ["you said: ping" "you said: pong"] (:converse results)))
          (is (= "example.echo" (deref served-by 5000 :none)))))
      (testing "without it, refused before any handler with the trailer a browser expects"
        (let [calls (client/client without echo/echo-methods {:deadline-ms 10000})
              e (is (thrown? StatusRuntimeException (echo-client/say calls "hello")))]
          (is (= Status$Code/UNAUTHENTICATED (.getCode (.getStatus ^StatusRuntimeException e))))
          (is (= "Bearer" (metadata/header (.getTrailers ^StatusRuntimeException e) "www-authenticate")))))
      (finally
        (client/shutdown with-token {:grace-ms 2000})
        (client/shutdown without {:grace-ms 2000})
        (server/shutdown srv {:grace-ms 2000})))))
