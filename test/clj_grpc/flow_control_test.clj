(ns clj-grpc.flow-control-test
  "The client's half of flow control, which the server has had all along.

  grpc exposes readiness and manual inbound credits on the
  ClientCallStreamObserver, but ClientCalls returns it typed as a plain
  StreamObserver and freezes it the moment the call starts, so the only
  supported way to configure it is a ClientResponseObserver's beforeStart.
  The client wires that for every streaming-in call, which is what makes
  :ready?, :request!, :on-ready and :inbound-credits reachable at all."
  (:require [acme.greeter.greeter :as g]
            [clj-grpc.client :as client]
            [clj-grpc.server :as server]
            [clojure.test :refer [deftest is testing]]))

(defn- reply [text] (g/HelloReply->proto {:message text}))
(defn- request-name [req] (:name (g/proto->HelloRequest req)))

(def ^:private handlers
  {:collect-hellos
   (fn [respond!]
     (let [names (atom [])]
       {:on-next (fn [req] (swap! names conj (request-name req)))
        :on-complete (fn [] (respond! (reply (str "Hello " (count @names) " of you"))))}))

   :chat
   (fn [send! close!]
     {:on-next (fn [req] (send! (reply (str "Echo " (request-name req)))))
      :on-complete (fn [] (close!))})})

(defn- with-channel [f]
  (let [srv (-> (server/server {:services [{:service g/Greeter :handlers handlers}]
                                :address 0})
                server/start)
        ch (client/channel (str "localhost:" (server/port srv)) {:plaintext true})]
    (try
      (f ch)
      (finally
        (client/shutdown ch {:grace-ms 2000})
        (server/shutdown srv {:grace-ms 2000})))))

(deftest send-answers-readiness-on-both-streaming-in-shapes
  (with-channel
    (fn [ch]
      (testing ":bidi"
        (let [ready (promise)
              call (client/invoke ch (:chat g/greeter-methods)
                                  {:on-next (fn [_])
                                   :on-ready #(deliver ready true)}
                                  {:deadline-ms 10000})]
          ;; The FIRST send can answer false, and routinely does: the stream is
          ;; not up yet, which is not backpressure. A caller that treats it as
          ;; such stalls on its own first message. What is guaranteed is that
          ;; the answer is a boolean rather than nil.
          (is (boolean? ((:send! call) (g/HelloRequest->proto {:name "a"})))
              "send! must answer readiness, not nil")
          (is (true? (deref ready 5000 false)) "the stream comes up")
          (is (true? ((:send! call) (g/HelloRequest->proto {:name "b"})))
              "once it has, a small message leaves the transport wanting more")
          (is (true? ((:ready? call))) ":ready? asks the same question without sending")
          ((:close! call))))
      (testing ":client-streaming"
        (let [ready (promise)
              call (client/invoke ch (:collect-hellos g/greeter-methods) nil
                                  {:deadline-ms 10000 :on-ready #(deliver ready true)})]
          (is (boolean? ((:send! call) (g/HelloRequest->proto {:name "a"}))))
          (is (true? (deref ready 5000 false)))
          (is (true? ((:ready? call))))
          ((:close! call))
          (is (= "Hello 1 of you"
                 (:message (g/proto->HelloReply (deref (:response call) 5000 nil))))
              "the response side is unchanged"))))))


(deftest on-ready-fires
  (with-channel
    (fn [ch]
      (testing ":bidi takes :on-ready in the observer map, beside :on-next"
        (let [ready (promise)
              call (client/invoke ch (:chat g/greeter-methods)
                                  {:on-next (fn [_])
                                   :on-ready #(deliver ready true)}
                                  {:deadline-ms 10000})]
          (is (true? (deref ready 5000 false))
              "grpc calls the handler when the call is ready to carry messages")
          ((:close! call))))
      (testing ":client-streaming takes it in opts, having no observer map"
        (let [ready (promise)
              call (client/invoke ch (:collect-hellos g/greeter-methods) nil
                                  {:deadline-ms 10000 :on-ready #(deliver ready true)})]
          (is (true? (deref ready 5000 false)))
          ((:close! call))
          (is (some? (deref (:response call) 5000 nil))))))))

(deftest inbound-credits-hold-responses-until-requested
  (with-channel
    (fn [ch]
      (let [seen (atom [])
            done (promise)
            call (client/invoke ch (:chat g/greeter-methods)
                                {:inbound-credits 1
                                 :on-next #(swap! seen conj (:message (g/proto->HelloReply %)))
                                 :on-complete #(deliver done true)}
                                {:deadline-ms 10000})]
        (doseq [n ["a" "b" "c"]]
          ((:send! call) (g/HelloRequest->proto {:name n})))
        ;; One credit was asked for, so exactly one echo may be delivered no
        ;; matter how many the server has already written.
        (Thread/sleep 1000)
        (is (= ["Echo a"] @seen)
            "without automatic requesting, grpc delivers only what was asked for")
        (testing "and the rest arrive once asked for"
          ((:request! call) 2)
          ((:close! call))
          (is (true? (deref done 5000 false)) "the call completed")
          (is (= ["Echo a" "Echo b" "Echo c"] @seen)))))))

(deftest streaming-in-calls-work-untouched-without-any-of-it
  (with-channel
    (fn [ch]
      (testing "no :on-ready, no :inbound-credits — the plain path still serves"
        (let [seen (atom [])
              done (promise)
              call (client/invoke ch (:chat g/greeter-methods)
                                  {:on-next #(swap! seen conj (:message (g/proto->HelloReply %)))
                                   :on-complete #(deliver done true)}
                                  {:deadline-ms 10000})]
          ((:send! call) (g/HelloRequest->proto {:name "x"}))
          ((:close! call))
          (is (true? (deref done 5000 false)))
          (is (= ["Echo x"] @seen)))))))
