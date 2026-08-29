(ns example.echo.client
  "The example client: one call per streaming shape against a running example
  server.

      bazel run //examples:server &
      bazel run //examples:client                    ; localhost:8080
      bazel run //examples:client -- localhost:9090

  Each call fn takes the map `client/client` returns and speaks plain data;
  the streaming machinery (lazy seqs, promises, observer maps) stays inside.
  The e2e test runs `call-all` against the example server and asserts on the
  same values -main prints."
  (:require [clj-grpc.client :as client]
            [example.echo.echo :as echo]))

(defn say
  "unary — blocks, one reply."
  [calls text]
  (-> ((:say calls) (echo/EchoRequest->proto {:text text}))
      echo/proto->EchoReply
      :text))

(defn repeat-n
  "server streaming — the call returns a lazy seq of replies, one per message
  the server sent."
  [calls text n]
  (mapv (comp :text echo/proto->EchoReply)
        ((:repeat calls) (echo/RepeatRequest->proto {:text text :count n}))))

(defn summarize
  "client streaming — the call returns {:send! :close! :response}; stream the
  requests, close, then deref the response promise."
  [calls texts]
  (let [{:keys [send! close! response]} ((:summarize calls) nil)]
    (doseq [t texts]
      (send! (echo/EchoRequest->proto {:text t})))
    (close!)
    (-> (deref response 10000 ::timeout)
        echo/proto->SummaryReply
        (select-keys [:message-count :char-count]))))

(defn converse
  "bidi — replies arrive through the observer map passed in; the call returns
  {:send! :close!} for the request side."
  [calls texts]
  (let [replies (atom [])
        done (promise)
        {:keys [send! close!]}
        ((:converse calls) {:on-next #(swap! replies conj (:text (echo/proto->EchoReply %)))
                            :on-complete #(deliver done true)})]
    (doseq [t texts]
      (send! (echo/EchoRequest->proto {:text t})))
    (close!)
    (deref done 10000 ::timeout)
    @replies))

(defn call-all
  "Every shape once, results as data."
  [ch]
  (let [calls (client/client ch echo/echo-methods {:deadline-ms 10000})]
    {:say       (say calls "hello")
     :repeat    (repeat-n calls "again" 3)
     :summarize (summarize calls ["one" "two" "three"])
     :converse  (converse calls ["ping" "pong"])}))

(defn -main [& [target]]
  (let [target (or target "localhost:8080")
        ch (client/channel target {:plaintext true})]
    (try
      (doseq [[shape result] (call-all ch)]
        (println (name shape) "->" (pr-str result)))
      (finally
        (client/shutdown ch {:grace-ms 2000})))))
