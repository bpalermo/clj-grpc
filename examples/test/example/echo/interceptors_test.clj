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

(deftest a-handler-reads-the-call-and-a-client-times-it
  (let [say-whoami (fn [_]
                     (let [{:keys [user peer]} (interceptors/whoami)]
                       (echo/EchoReply->proto {:text (str user "@" peer)})))
        srv (echo-server/start {:address 0
                                :services [{:service echo/Echo
                                            :handlers (assoc echo-server/handlers :say say-whoami)}]
                                :interceptors [interceptors/request-log
                                               (interceptors/require-token "secret")]})
        timings (atom [])
        ch  (client/channel (str "localhost:" (server/port srv))
                            {:plaintext true
                             :interceptors [(interceptors/bearer "secret")
                                            (interceptors/timing #(swap! timings conj %))]})]
    (try
      (testing "the handler sees the user the interceptor attached, and the peer"
        (let [calls (client/client ch echo/echo-methods {:deadline-ms 10000})]
          (is (re-matches #"token-holder@/127\.0\.0\.1:\d+" (echo-client/say calls "x")))))
      (testing "timing saw every shape close OK, with a wall time"
        (echo-client/call-all ch)
        (let [by-method (into {} (map (juxt :method identity)) @timings)]
          (is (= #{:say :repeat :summarize :converse} (set (keys by-method))))
          (is (every? #(= "OK" (:status %)) @timings))
          (is (every? #(<= 0 (:ms %)) @timings))))
      (finally
        (client/shutdown ch {:grace-ms 2000})
        (server/shutdown srv {:grace-ms 2000})))))

(deftest a-header-propagates-through-a-handler-to-the-next-service
  (let [seen-downstream (atom nil)
        ;; B: records the request id it was handed
        b   (echo-server/start {:address 0
                                :interceptors [(fn [call next]
                                                 (reset! seen-downstream
                                                         (metadata/header (:headers call) "x-request-id"))
                                                 (next call))]})
        to-b (client/channel (str "localhost:" (server/port b))
                             {:plaintext true :interceptors [(interceptors/propagate "x-request-id")]})
        ;; A: its Say handler calls B's Say, from the callback thread
        b-calls (client/client to-b echo/echo-methods {:deadline-ms 10000})
        a   (echo-server/start {:address 0
                                :services [{:service echo/Echo
                                            :handlers (assoc echo-server/handlers
                                                             :say (fn [_] (echo/EchoReply->proto
                                                                           {:text (echo-client/say b-calls "via-a")})))}]})
        to-a (client/channel (str "localhost:" (server/port a)) {:plaintext true})]
    (try
      (let [calls (client/client to-a echo/echo-methods {:deadline-ms 10000
                                                         :headers {"x-request-id" "req-42"}})]
        (is (= "echo: via-a" (echo-client/say calls "x")))
        (is (= "req-42" @seen-downstream)
            "the id sent to A reached B, forwarded from inside A's handler"))
      (testing "and from outside any call the same channel declares nothing"
        (reset! seen-downstream :untouched)
        (echo-client/say b-calls "direct")
        (is (nil? @seen-downstream)))
      (finally
        (client/shutdown to-a {:grace-ms 2000})
        (client/shutdown to-b {:grace-ms 2000})
        (server/shutdown a {:grace-ms 2000})
        (server/shutdown b {:grace-ms 2000})))))
