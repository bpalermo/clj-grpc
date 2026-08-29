(ns example.echo.server
  "The example server: the generated Echo service on clj-grpc, one plain
  function per method.

      bazel run //examples:server

  Serves h2c on $PORT (default 8080) with the Knative preset, so health and
  reflection are on and grpcurl works against it:

      grpcurl -plaintext localhost:8080 list

  Each handler's shape follows its method's streaming type — the full contract
  is the clj-grpc.server docstring; this file is one worked instance of every
  shape. Requests and responses are protobuf Messages: the generated
  proto->X / X->proto fns are the edges, plain data is the middle."
  (:require [clj-grpc.knative :as knative]
            [clj-grpc.server :as server]
            [example.echo.echo :as echo]))

(defn- reply [text]
  (echo/EchoReply->proto {:text text}))

(def handlers
  "One entry per method, keyed by the kebab-cased method name."
  {;; unary — (fn [request] response)
   :say
   (fn [req]
     (reply (str "echo: " (:text (echo/proto->EchoRequest req)))))

   ;; server streaming — (fn [request send!]); send! per message, return to
   ;; complete the stream
   :repeat
   (fn [req send!]
     (let [{:keys [text] n :count} (echo/proto->RepeatRequest req)]
       (dotimes [i n]
         (send! (reply (str text " #" (inc i)))))))

   ;; client streaming — (fn [respond!]) -> {:on-next ... :on-complete ...};
   ;; call respond! once with the response, here from :on-complete
   :summarize
   (fn [respond!]
     (let [texts (atom [])]
       {:on-next (fn [req]
                   (swap! texts conj (:text (echo/proto->EchoRequest req))))
        :on-complete (fn []
                       (respond! (echo/SummaryReply->proto
                                  {:message-count (count @texts)
                                   :char-count (reduce + 0 (map count @texts))})))}))

   ;; bidi — (fn [send! close!]) -> {:on-next ... :on-complete ...};
   ;; send! per message, close! to finish
   :converse
   (fn [send! close!]
     {:on-next (fn [req]
                 (send! (reply (str "you said: " (:text (echo/proto->EchoRequest req))))))
      :on-complete (fn [] (close!))})})

(def echo-service
  {:service echo/Echo :handlers handlers})

(defn start
  "Start the example server; opts merge over the Knative preset. Returns the
  started server — the e2e test starts one on an ephemeral port with
  {:address 0} and reads the bound port back."
  [opts]
  (-> (knative/server (merge {:services [echo-service]} opts))
      server/start))

(defn -main [& _]
  (let [srv (start nil)]
    (println (str "echo server listening on port " (server/port srv)))
    (server/await-termination srv)))
