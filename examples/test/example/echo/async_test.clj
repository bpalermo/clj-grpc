(ns example.echo.async-test
  "The core.async example, round-tripped, plus the two claims the adapter rests
  on — because both are easy to write, easy to believe, and silent when wrong.

  A bidi echo cannot outrun its consumer, so a round trip alone would exercise
  none of the flow control. The second test makes the producer faster than the
  wire on purpose: it never reads the responses, so the transport fills, and
  the run is only correct if `send!` says so and :on-ready arrives to restart
  the pump. Without both, the producer either buffers without bound or parks
  forever."
  (:require [clojure.core.async :as a]
            [clojure.test :refer [deftest is testing]]
            [clj-grpc.client :as client]
            [clj-grpc.server :as server]
            [example.echo.async :as async]
            [example.echo.echo :as echo]))

(defn- with-server [handlers f]
  (let [srv (-> (server/server {:services [{:service echo/Echo :handlers handlers}]
                                :address 0
                                :inbound-credits 32})
                server/start)
        ch  (client/channel (str "localhost:" (server/port srv)) {:plaintext true})]
    (try (f ch)
         (finally
           (client/shutdown ch {:grace-ms 2000})
           (server/shutdown srv {:grace-ms 2000})))))

(deftest channels-round-trip
  (testing "the adapter's call body is a loop over a channel, and the wire is
            the same wire the plain handler produces"
    (with-server async/handlers
      (fn [ch]
        (let [replies (atom [])
              done    (promise)
              call    (client/invoke ch (:converse echo/echo-methods)
                                     {:on-next     #(swap! replies conj
                                                           (:text (echo/proto->EchoReply %)))
                                      :on-complete #(deliver done true)
                                      :on-error    #(deliver done %)})]
          ((:send! call) (echo/EchoRequest->proto {:text "ping"}))
          ((:send! call) (echo/EchoRequest->proto {:text "pong"}))
          ((:close! call))
          (is (true? (deref done 5000 :timeout)))
          (is (= ["you said: ping" "you said: pong"] @replies)))))))

(deftest a-producer-faster-than-its-consumer-waits-instead-of-buffering
  (testing "send! reports a full transport and :on-ready restarts the pump"
    (let [n        2000
          body     (apply str (repeat 4096 \x))   ; big enough to fill the window
          not-ready (promise)                     ; delivered when send! says false
          resumed   (promise)                     ; delivered when :on-ready wakes us
          sent      (atom 0)
          ;; A handler that ignores its input and floods: one request in, n
          ;; large replies out, through the same adapter the example uses.
          handlers  {:converse
                     (async/bidi-chan
                      (fn [in out]
                        (a/<!! in)
                        (dotimes [_ n]
                          (a/>!! out (echo/EchoReply->proto {:text body}))
                          (swap! sent inc)))
                      {:in-buf 32 :out-buf 8})}]
      (with-server
        (update handlers :converse
                (fn [h]
                  ;; Wrap the adapter's own fns map to observe what it saw,
                  ;; without changing what it does.
                  (fn [send! close!]
                    (let [fns (h (fn [msg]
                                   (let [ready (send! msg)]
                                     (when-not ready (deliver not-ready true))
                                     ready))
                                 close!)]
                      (update fns :on-ready
                              (fn [on-ready]
                                (fn [] (deliver resumed true) (on-ready))))))))
        (fn [ch]
          (let [received (atom 0)
                done     (promise)
                call     (client/invoke ch (:converse echo/echo-methods)
                                        {:on-next     (fn [_] (swap! received inc))
                                         :on-complete #(deliver done true)
                                         :on-error    #(deliver done %)})]
            ((:send! call) (echo/EchoRequest->proto {:text "go"}))
            (is (true? (deref done 30000 :timeout))
                "the call completes: parking on :on-ready is not a deadlock")
            (is (= n @received) "every message arrives exactly once")
            (is (= n @sent) "and the producer sent every one of them")
            (testing "the transport did fill, so the waiting path ran"
              (is (true? (deref not-ready 0 :never-full))
                  "send! returned false at least once")
              (is (true? (deref resumed 0 :never-resumed))
                  ":on-ready fired at least once"))))))))
