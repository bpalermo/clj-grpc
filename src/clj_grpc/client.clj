(ns clj-grpc.client
  "Channels and calls, from the same methods map the server consumes.

      (def ch (channel \"localhost:8080\" {:plaintext true}))
      (invoke ch (:say-hello greeter/greeter-methods) request)

  Call shapes, by method type:
    :unary            request -> response (blocking)
    :server-streaming request -> lazy seq of responses (blocking iterator)
    :client-streaming -> {:send! (fn [msg]) :close! (fn []) :response promise
                          :ready? :request! :error!}
    :bidi             observer-map -> {:send! ... :close! ... :ready? ...};
                      responses arrive through the caller's {:on-next ...} map

  Flow control on the streaming-out shapes, the client mirror of the server's.
  `send!` returns whether the transport wants more: false means grpc-java is
  now buffering this call's messages because the peer is not reading them fast
  enough, and `.onNext` neither blocks nor refuses, so a producer faster than
  its consumer grows that buffer without bound. `:ready?` asks the same
  question without sending.

  THE FIRST ANSWER IS OFTEN FALSE AND DOES NOT MEAN BACKPRESSURE: a call's
  stream is not up at the moment `invoke` returns, and readiness is false
  until it is. A producer that reads that first false as a signal to stop
  stalls on its own first message. Wait for :on-ready, or simply send — a
  reply per request cannot outrun anything, and .onNext never refuses.

  To wait rather than poll, pass :on-ready in the observer map (:bidi) or in
  opts (:client-streaming); grpc calls it when a full call has drained. THE
  SAME RULE AS THE SERVER APPLIES: grpc serializes a call's callbacks, so the
  thread running one must never wait for another. A producer that wants to
  wait needs its own thread, parked on something :on-ready delivers to.

  Inbound credits, for a :bidi caller that wants to pace what it receives:
  :inbound-credits n in the observer map stops grpc requesting messages
  automatically and asks for n, after which `:request!` asks for more. Without
  it responses arrive as fast as the server sends them."
  (:require [clj-grpc.interceptor :as interceptor]
            [clj-grpc.transport :as transport])
  ;; No Netty type appears here: the NettyChannelBuilder is constructed inside
  ;; clj-grpc.impl.netty (loaded via requiring-resolve at first construction)
  ;; and comes back as the generic ManagedChannelBuilder. See transport.clj.
  (:import [io.grpc CallOptions Channel ClientInterceptor ClientInterceptors
            ManagedChannel ManagedChannelBuilder]
           [io.grpc.stub ClientCalls ClientCallStreamObserver
            ClientResponseObserver StreamObserver]
           [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

(defn channel
  "target: \"host:port\" | \"dns:///...\" | \"unix:///path\" | {:unix path}
  opts:
    :plaintext        true for h2c — what a Knative service speaks; default
                      true for unix targets, false otherwise
    :transport        :auto (default) | :epoll | :nio; UDS requires epoll
    :keepalive        {:time-ms n :timeout-ms n :without-calls bool} — keep the
                      connection warm through idle proxies (Knative activator)
    :idle-timeout-ms  channel idle timeout
    :max-inbound-message-size  bytes
    :interceptors     [f-or-ClientInterceptor ...] — fns of the call,
                      (fn [call next] ...), mixed with raw ClientInterceptors;
                      [a b c] runs a outermost. See clj-grpc.interceptor.
                      invoke's opts take the same key per call.
    :executor         Executor for callbacks, or :direct (same sharp edge as
                      the server, sharper: every channel in the process shares
                      one event loop group per transport, so a direct callback
                      that blocks stalls other channels' I/O too)
    :default-service-config  map, e.g. retry policy (enables retries when set)"
  ^ManagedChannel
  [target {:keys [plaintext transport keepalive idle-timeout-ms
                  max-inbound-message-size interceptors default-service-config
                  executor]}]
  (let [unix?     (or (and (map? target) (:unix target))
                      (and (string? target) (.startsWith ^String target "unix://")))
        transport (transport/resolve-transport
                   (if (and unix? (nil? transport)) :epoll transport))
        ^ManagedChannelBuilder builder
        ((requiring-resolve 'clj-grpc.impl.netty/channel-builder)
         target {:transport transport :unix? unix?})]
    (cond
      (= :direct executor) (.directExecutor builder)
      executor (.executor builder ^java.util.concurrent.Executor executor))
    (if (or plaintext unix? (nil? plaintext))
      (.usePlaintext builder)
      builder)
    (when-let [{:keys [time-ms timeout-ms without-calls]} keepalive]
      (when time-ms (.keepAliveTime builder (long time-ms) TimeUnit/MILLISECONDS))
      (when timeout-ms (.keepAliveTimeout builder (long timeout-ms) TimeUnit/MILLISECONDS))
      (when (some? without-calls) (.keepAliveWithoutCalls builder (boolean without-calls))))
    (when idle-timeout-ms (.idleTimeout builder (long idle-timeout-ms) TimeUnit/MILLISECONDS))
    (when max-inbound-message-size (.maxInboundMessageSize builder (int max-inbound-message-size)))
    (when default-service-config
      (-> builder
          (.defaultServiceConfig ^java.util.Map default-service-config)
          (.enableRetry)))
    ;; Reversed: the builder runs the last-registered interceptor outermost,
    ;; and the documented order is the vector's. Nothing at all when empty.
    (when (seq interceptors)
      (.intercept builder ^"[Lio.grpc.ClientInterceptor;"
                  (into-array ClientInterceptor
                              (map interceptor/client-interceptor (reverse interceptors)))))
    (.build builder)))

(defn- call-options ^CallOptions [{:keys [deadline-ms wait-for-ready]}]
  (cond-> CallOptions/DEFAULT
    deadline-ms    (.withDeadlineAfter (long deadline-ms) TimeUnit/MILLISECONDS)
    wait-for-ready (.withWaitForReady)))

(defn- response-observer
  "The caller's callbacks as a grpc observer, and a place to put the request
  stream. Always a ClientResponseObserver, for two reasons: beforeStart is the
  only point at which setOnReadyHandler and disableAutoRequestWithInitial may
  be called — grpc freezes the request stream immediately afterwards, and both
  then throw — and its argument is the documented way to reach the
  ClientCallStreamObserver, rather than casting whatever ClientCalls hands
  back. ClientCalls calls beforeStart from the listener adapter's constructor,
  before it starts the call and so before it returns, which is why `stream` is
  always delivered by the time the caller has the map."
  ^StreamObserver [{:keys [on-next on-error on-complete on-ready inbound-credits]}
                   stream]
  (reify ClientResponseObserver
    (beforeStart [_ rs]
      (let [^ClientCallStreamObserver rs rs]
        (deliver stream rs)
        (when on-ready (.setOnReadyHandler rs ^Runnable on-ready))
        (when inbound-credits
          (.disableAutoRequestWithInitial rs (int inbound-credits)))))
    (onNext [_ msg] (when on-next (on-next msg)))
    (onError [_ t] (when on-error (on-error t)))
    (onCompleted [_] (when on-complete (on-complete)))))

(defn- request-observer-map
  "The request side as fns. `send!` answers readiness, as the server's does."
  [^ClientCallStreamObserver req-obs]
  {:send!    (fn [msg] (.onNext req-obs msg) (.isReady req-obs))
   :close!   (fn [] (.onCompleted req-obs))
   :error!   (fn [t] (.onError req-obs t))
   :ready?   (fn [] (.isReady req-obs))
   :request! (fn [n] (.request req-obs (int n)))})

(defn- intercept-channel
  "The channel with per-call interceptors and declared headers in front of
  it — outermost relative to the channel's own. interceptForward keeps the
  vector's order as the running order, so no reversal here."
  ^Channel [^Channel ch interceptors headers]
  (ClientInterceptors/interceptForward
   ch
   ^java.util.List
   (mapv interceptor/client-interceptor
         (cond->> interceptors
           headers (cons (fn [call next]
                           (next (update call :headers merge headers))))))))

(defn invoke
  "Call one method. The blocking shapes block; the streaming-in shapes return
  immediately with the request-side controls.

  opts: :deadline-ms, :wait-for-ready, and per call
    :headers       a map of outgoing headers for this call
    :interceptors  [f-or-ClientInterceptor ...] for this call, outermost
                   relative to the channel's; see clj-grpc.interceptor
    :on-ready      :client-streaming only — the :bidi shape takes it in the
                   observer map, next to :on-next

  The streaming-in shapes return {:send! :close! :error! :ready? :request!},
  where send! answers whether the transport wants more; see this namespace's
  docstring for the whole flow-control contract."
  ([ch method request] (invoke ch method request nil))
  ([ch {:keys [type method-descriptor]} request {:keys [interceptors headers] :as opts}]
   (let [^Channel ch (if (or (seq interceptors) headers)
                        (intercept-channel ch interceptors headers)
                        ch)
         md   @method-descriptor
         copt (call-options opts)]
     (case type
       :unary
       (ClientCalls/blockingUnaryCall ch md copt request)

       :server-streaming
       (iterator-seq (ClientCalls/blockingServerStreamingCall ch md copt request))

       :client-streaming
       (let [response (promise)
             stream   (promise)]
         (ClientCalls/asyncClientStreamingCall
          (.newCall ch md copt)
          (response-observer {:on-next  #(deliver response %)
                              :on-error #(deliver response %)
                              :on-ready (:on-ready opts)}
                             stream))
         (assoc (request-observer-map @stream) :response response))

       :bidi
       (let [stream (promise)]
         (ClientCalls/asyncBidiStreamingCall
          (.newCall ch md copt)
          (response-observer request stream))
         (request-observer-map @stream))))))

(defn client
  "The whole service as a map of fns: {:say-hello (fn [request] ...), ...}."
  ([ch methods] (client ch methods nil))
  ([ch methods default-opts]
   (into {}
         (map (fn [[k m]]
                [k (fn call
                     ([request] (invoke ch m request default-opts))
                     ([request opts] (invoke ch m request (merge default-opts opts))))]))
         methods)))

(defn shutdown
  "Graceful by default; :grace-ms bounds the drain, then forces.

  Nothing to release beyond the channel: channels share one event loop group
  per transport for the life of the process, so shutting one down frees no
  threads and opening the next allocates none. See
  clj-grpc.impl.netty/shared-channel-groups for why that is the ownership
  split, and clj-grpc.server/shutdown for the half that does release loops."
  ([ch] (shutdown ch nil))
  ([^ManagedChannel ch {:keys [grace-ms]}]
   (.shutdown ch)
   (when grace-ms
     (when-not (.awaitTermination ch (long grace-ms) TimeUnit/MILLISECONDS)
       (.shutdownNow ch)))
   ch))
