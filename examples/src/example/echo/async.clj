(ns example.echo.async
  "core.async over the bidi handler shape: channels in, channels out, with the
  transport's backpressure reaching both ends.

      bazel run //examples:async

  This is a consumer of clj-grpc, not part of it — core.async is an alias
  dependency of this example alone. The library's handler shapes are the whole
  interface, and about forty lines of adapter is the whole integration.

  Three things decide whether an integration like this is any good, and all
  three are visible below.

  ONE: no go blocks. core.async's dispatch pool is a handful of platform
  threads shared by every go block in the process; a blocking call inside one
  starves the rest. clj-grpc runs handlers on virtual threads precisely so
  that blocking is the cheap thing to do, so the call body blocks on <!!/>!!
  on a thread of its own. Channels are used here as queues with backpressure,
  not as a scheduler.

  TWO: inbound backpressure is already real, and the buffer must be sized for
  it. Blocking in :on-next is the signal — grpc asks for the next message only
  once :on-next returns, or, under :inbound-credits n, keeps at most n in
  flight. So in-buf is the credit count: smaller and the channel becomes the
  bottleneck instead of the transport, which gives back the reason for
  batching those flow-control hops in the first place.

  THREE: outbound backpressure needs the readiness pair. `send!` answers
  whether the transport wants more, and :on-ready fires when a full call has
  drained. The pump parks between the two. It must be a thread of its own:
  grpc serializes a call's callbacks, so a handler thread that waited for
  :on-ready while holding the callback would wait forever. :on-ready does
  nothing here but hand the signal over.

  The service is the example Echo, so //examples:async and //examples:client
  talk to each other — the adapter changes how the handler is written, not
  what is on the wire."
  (:require [clojure.core.async :as a]
            [clj-grpc.knative :as knative]
            [clj-grpc.server :as server]
            [example.echo.echo :as echo])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; The adapter. Nothing below this comment knows anything about Echo.

(defn bidi-chan
  "Adapt a channel-shaped function to clj-grpc's :bidi handler shape.

  f is (fn [in out]): take requests from `in` until it closes, put responses on
  `out`, close `out` to end the call. It runs on its own virtual thread, so it
  may block freely.

  opts:
    :in-buf   inbound buffer; match the server's :inbound-credits (default 32)
    :out-buf  outbound buffer — how far the producer may run ahead of the wire
              before it is made to wait (default 32)"
  ([f] (bidi-chan f {}))
  ([f {:keys [in-buf out-buf] :or {in-buf 32 out-buf 32}}]
   (fn [send! close!]
     (let [in    (a/chan in-buf)
           out   (a/chan out-buf)
           ;; Readiness, handed from the callback thread to the pump. A sliding
           ;; buffer of 1 because these are edges, not a queue: a signal that
           ;; arrives while the pump is awake must not accumulate, and two that
           ;; arrive before it wakes mean the same thing as one.
           ready (a/chan (a/sliding-buffer 1))]
       ;; The pump: the only thread that writes to the call.
       (Thread/startVirtualThread
        (fn []
          (try
            (loop []
              (if-let [msg (a/<!! out)]
                (do (when-not (send! msg)
                      ;; Full. Park until grpc says it has drained, rather than
                      ;; letting onNext buffer without bound.
                      (a/<!! ready))
                    (recur))
                (close!)))
            (catch Throwable t
              (a/close! in)
              (throw t)))))
       ;; The call body, on its own thread so that blocking in f cannot hold a
       ;; callback.
       (Thread/startVirtualThread
        (fn []
          (try (f in out)
               (finally (a/close! out)))))
       {;; Blocking here is the inbound backpressure: the transport asks for
        ;; more only once this returns.
        :on-next     (fn [msg] (a/>!! in msg))
        :on-complete (fn [] (a/close! in))
        :on-error    (fn [_] (a/close! in))
        ;; Runs on the callback thread. Hand the signal over and return.
        :on-ready    (fn [] (a/offer! ready true))}))))

;; ---------------------------------------------------------------------------
;; The service, written against channels.

(defn- converse
  "A reply per request, as a channel pipeline. The whole handler is a loop over
  a channel; the transport is somebody else's problem."
  [in out]
  (loop []
    (when-let [req (a/<!! in)]
      (a/>!! out (echo/EchoReply->proto
                  {:text (str "you said: " (:text (echo/proto->EchoRequest req)))}))
      (recur))))

(def handlers
  {;; The three non-bidi shapes are the plain ones from example.echo.server;
   ;; only :converse is worth writing over channels, because only it has a
   ;; producer that can outrun its consumer.
   :say
   (fn [req]
     (echo/EchoReply->proto {:text (str "echo: " (:text (echo/proto->EchoRequest req)))}))

   :repeat
   (fn [req send!]
     (let [{:keys [text] n :count} (echo/proto->RepeatRequest req)]
       (dotimes [i n]
         (send! (echo/EchoReply->proto {:text (str text " #" (inc i))})))))

   :summarize
   (fn [respond!]
     (let [texts (atom [])]
       {:on-next     (fn [req] (swap! texts conj (:text (echo/proto->EchoRequest req))))
        :on-complete (fn [] (respond! (echo/SummaryReply->proto
                                       {:message-count (count @texts)
                                        :char-count (reduce + 0 (map count @texts))})))}))

   :converse (bidi-chan converse {:in-buf 32 :out-buf 32})})

(defn start
  "Start the channel-shaped Echo server; opts merge over the Knative preset.
  :inbound-credits matches the adapter's :in-buf — see the namespace docstring."
  [opts]
  (-> (knative/server (merge {:services [{:service echo/Echo :handlers handlers}]
                              :inbound-credits 32}
                             opts))
      server/start))

(defn -main [& _]
  (let [srv (start {})]
    (knative/shutdown-hook! srv)
    (println "channel-shaped Echo on" (server/port srv) "— ^C to stop")
    (server/await-termination srv)))
