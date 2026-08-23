(ns clj-grpc.e2e-test
  "The whole stack over real Netty: the generated greeter fixture served and
  called through every streaming shape, over TCP and a Unix domain socket.

  Non-shaded Netty IS the subject here, so nothing uses grpc's in-process
  transport."
  (:require [acme.greeter.greeter :as g]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clj-grpc.client :as client]
            [clj-grpc.health :as health]
            [clj-grpc.server :as server]
            [clj-grpc.transport :as transport])
  (:import [io.grpc.health.v1 HealthCheckRequest HealthGrpc]
           [java.nio.file Files]))

(defn- reply [text]
  (g/HelloReply->proto {:message text}))

(defn- request-name [req]
  (:name (g/proto->HelloRequest req)))

(def handlers
  {:say-hello
   (fn [req] (reply (str "Hello " (request-name req))))

   :say-hello-many
   (fn [req send!]
     (dotimes [i (:repeat-count (g/proto->HelloRequest req))]
       (send! (reply (str "Hello " (request-name req) " #" i)))))

   :collect-hellos
   (fn [respond!]
     (let [names (atom [])]
       {:on-next (fn [req] (swap! names conj (request-name req)))
        :on-complete (fn [] (respond! (reply (str "Hello " (count @names) " of you"))))}))

   :chat
   (fn [send! close!]
     {:on-next (fn [req] (send! (reply (str "Echo " (request-name req)))))
      :on-complete (fn [] (close!))})})

(def greeter-service {:service g/Greeter :handlers handlers})

(defn- with-server-and-channel
  "Run f with a started server and connected channel, over the given address
  and transport, cleaning both up."
  [{:keys [address transport target]} f]
  (let [srv (-> (server/server {:services [greeter-service]
                                :address address
                                :transport transport
                                :health true
                                :reflection true})
                server/start)
        target (or target (str "localhost:" (server/port srv)))
        ch (client/channel target {:plaintext true :transport transport})]
    (try
      (f srv ch)
      (finally
        (client/shutdown ch {:grace-ms 2000})
        (server/shutdown srv {:grace-ms 2000})))))

(defn- exercise-all-shapes [_ ch]
  (let [calls (client/client ch g/greeter-methods {:deadline-ms 10000})]
    (testing "unary"
      (is (= "Hello world"
             (:message (g/proto->HelloReply
                        ((:say-hello calls) (g/HelloRequest->proto {:name "world"})))))))
    (testing "server streaming"
      (is (= ["Hello s #0" "Hello s #1" "Hello s #2"]
             (mapv (comp :message g/proto->HelloReply)
                   ((:say-hello-many calls)
                    (g/HelloRequest->proto {:name "s" :repeat-count 3}))))))
    (testing "client streaming"
      (let [{:keys [send! close! response]}
            ((:collect-hellos calls) nil)]
        (send! (g/HelloRequest->proto {:name "a"}))
        (send! (g/HelloRequest->proto {:name "b"}))
        (close!)
        (is (= "Hello 2 of you"
               (:message (g/proto->HelloReply (deref response 10000 ::timeout)))))))
    (testing "bidi"
      (let [replies (atom [])
            done (promise)
            {:keys [send! close!]}
            ((:chat calls) {:on-next #(swap! replies conj (:message (g/proto->HelloReply %)))
                            :on-complete #(deliver done true)})]
        (send! (g/HelloRequest->proto {:name "x"}))
        (send! (g/HelloRequest->proto {:name "y"}))
        (close!)
        (is (true? (deref done 10000 ::timeout)))
        (is (= ["Echo x" "Echo y"] @replies))))))

(deftest tcp-all-streaming-shapes
  (with-server-and-channel {:address 0} exercise-all-shapes))

(deftest nio-forced
  (testing "the :nio fallback serves the same traffic"
    (with-server-and-channel {:address 0 :transport :nio}
      (fn [_ ch]
        (let [calls (client/client ch g/greeter-methods {:deadline-ms 10000})]
          (is (= "Hello nio"
                 (:message (g/proto->HelloReply
                            ((:say-hello calls) (g/HelloRequest->proto {:name "nio"})))))))))))

(deftest uds-all-streaming-shapes
  (if-not (transport/epoll-available?)
    (println "SKIP: epoll unavailable, Unix domain socket test needs Linux")
    ;; Not TEST_TMPDIR: AF_UNIX paths cap at ~108 bytes and Bazel's tmp paths
    ;; blow far past that. The sandbox gives each test a private /tmp.
    (let [dir (Files/createTempDirectory
               (java.nio.file.Paths/get "/tmp" (make-array String 0))
               "uds"
               (make-array java.nio.file.attribute.FileAttribute 0))
          path (str dir "/grpc.sock")]
      (with-server-and-channel {:address {:unix path}
                                :target (str "unix://" path)}
        exercise-all-shapes))))

(deftest health-service-answers-and-transitions
  (with-server-and-channel {:address 0}
    (fn [srv ch]
      (let [stub (HealthGrpc/newBlockingStub ch)
            check #(str (.getStatus (.check stub (-> (HealthCheckRequest/newBuilder) (.build)))))]
        (is (= "SERVING" (check)))
        (health/set-status! srv :not-serving)
        (is (= "NOT_SERVING" (check)))
        (health/set-status! srv :serving)
        (is (= "SERVING" (check)))))))

(deftest handlers-run-on-virtual-threads-by-default
  (with-server-and-channel {:address 0}
    (fn [_ ch]
      (let [seen (promise)
            srv2 (-> (server/server
                      {:services [{:service g/Greeter
                                   :handlers {:say-hello
                                              (fn [req]
                                                (deliver seen (.isVirtual (Thread/currentThread)))
                                                (reply "vt"))}}]
                       :address 0})
                     server/start)
            ch2 (client/channel (str "localhost:" (server/port srv2)) {:plaintext true})]
        (try
          ((:say-hello (client/client ch2 g/greeter-methods {:deadline-ms 5000}))
           (g/HelloRequest->proto {:name "x"}))
          (is (true? (deref seen 5000 ::timeout)))
          (finally
            (client/shutdown ch2 {:grace-ms 1000})
            (server/shutdown srv2 {:grace-ms 1000})))))))

(deftest aggressive-keepalives-survive-when-permitted
  (testing "client pings far below gRPC's 5-minute default permit stay alive
            because the server grants the permit — the preset pairing"
    (let [srv (-> (server/server {:services [greeter-service]
                                  :address 0
                                  :permit-keepalive {:time-ms 100 :without-calls true}})
                  server/start)
          ch (client/channel (str "localhost:" (server/port srv))
                             {:plaintext true
                              :keepalive {:time-ms 150 :timeout-ms 1000 :without-calls true}})
          calls (client/client ch g/greeter-methods {:deadline-ms 5000})]
      (try
        (is (= "Hello a" (:message (g/proto->HelloReply ((:say-hello calls) (g/HelloRequest->proto {:name "a"}))))))
        (Thread/sleep 1200) ; several ping intervals on an idle connection
        (is (= "Hello b" (:message (g/proto->HelloReply ((:say-hello calls) (g/HelloRequest->proto {:name "b"}))))))
        (finally
          (client/shutdown ch {:grace-ms 1000})
          (server/shutdown srv {:grace-ms 1000}))))))

(deftest unimplemented-method-answers-unimplemented
  (with-server-and-channel {:address 0}
    (fn [srv ch]
      (let [srv2 (-> (server/server {:services [{:service g/Greeter
                                                 :handlers (select-keys handlers [:say-hello])}]
                                     :address 0})
                     server/start)
            ch2 (client/channel (str "localhost:" (server/port srv2)) {:plaintext true})
            calls (client/client ch2 g/greeter-methods {:deadline-ms 5000})]
        (try
          (is (thrown-with-msg? io.grpc.StatusRuntimeException #"UNIMPLEMENTED"
                                (doall ((:say-hello-many calls)
                                        (g/HelloRequest->proto {:name "n" :repeat-count 1})))))
          (finally
            (client/shutdown ch2 {:grace-ms 1000})
            (server/shutdown srv2 {:grace-ms 1000})))))))
