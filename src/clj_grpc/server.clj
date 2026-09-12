(ns clj-grpc.server
  "A gRPC server over non-shaded Netty, from a service value and plain
  functions.

      (-> (server {:services [{:service greeter/Greeter
                               :handlers {:say-hello (fn [req] ...)}}]
                   :port 8080})
          start)

  Handler shapes, by method type — requests and responses are protobuf
  Messages; compose with the generated proto->X / X->proto at the edges:

    :unary            (fn [request] response)
    :server-streaming (fn [request send!]) — call send! per message, return to
                      complete
    :client-streaming (fn [respond!]) -> {:on-next (fn [msg]) ...
                                          :on-complete (fn [])}
                      — call respond! once with the response, usually from
                      :on-complete
    :bidi             (fn [send! close!]) -> {:on-next ... :on-complete ...}
                      — send! per message, close! to finish

  A thrown exception in any handler becomes Status/INTERNAL with the message
  attached; throw an io.grpc.StatusRuntimeException to control the status.

  opts:
    :services     [{:service Service :handlers {kebab-key fn}} ...]
    :address      SocketAddress | port | {:unix path} | \"unix:///path\"
    :port         used when :address is absent; default $PORT, else 8080 —
                  the Knative convention
    :transport    :auto (default) | :epoll | :nio — UDS requires epoll
    :health       true (default) — grpc health service, wired for probes
    :reflection   false (default) — server reflection (v1)
    :executor     java.util.concurrent.Executor for handlers, or :direct to
                  run them ON the Netty event loop — measured ~29% off unary
                  latency, and a sharp edge: a handler that blocks on a direct
                  executor stalls the transport for every connection sharing
                  that loop. Opt in only for handlers that provably never
                  block.
    :interceptors [io.grpc.ServerInterceptor ...]
    :permit-keepalive {:time-ms n :without-calls bool} — the pings this server
                  ACCEPTS. gRPC's default permit is 5 minutes and calls-only;
                  a client pinging faster gets GOAWAY too_many_pings, so a
                  server whose clients keep connections warm (Knative, LBs)
                  must lower this to match. clj-grpc.knative pairs the two.
    :max-inbound-message-size bytes
    :inbound-credits n — for :client-streaming and :bidi handlers, ask the
                  transport for n messages at a time instead of grpc-java's
                  one per delivered message. Each request is a hop from the
                  handler's thread back to the event loop; measured on a
                  4-core host, batching them is +27–40% streamed messages per
                  second on the virtual-thread executor at −23–35% CPU per
                  message, and the batch size does not matter above a handful
                  (8, 32 and 128 measure the same). Backpressure is unchanged
                  in kind — the server still bounds what it has asked for — but
                  up to n messages may be in flight to a handler at once, so
                  a handler that must see exactly one at a time keeps the
                  default. Default: nil (grpc-java's auto-request).
    :tls          {:cert-chain File/path :private-key File/path}; absent means
                  h2c (plaintext HTTP/2), which is what Knative speaks

  Handlers run on virtual threads by default (:executor overrides): Clojure
  handlers block — that is the model — and grpc's default shared pool is sized
  for handlers that never do."
  (:require [clj-grpc.transport :as transport])
  ;; No Netty or grpc-netty type appears here: the NettyServerBuilder is
  ;; constructed inside clj-grpc.impl.netty (loaded via requiring-resolve at
  ;; first construction) and comes back as the generic ServerBuilder, on which
  ;; everything below is transport-agnostic. See transport.clj for why.
  (:import [io.grpc Server ServerBuilder ServerInterceptor
            ServerServiceDefinition Status StatusRuntimeException]
           [io.grpc.protobuf.services HealthStatusManager ProtoReflectionServiceV1]
           [io.grpc.stub ServerCalls ServerCalls$BidiStreamingMethod
            ServerCalls$ClientStreamingMethod ServerCalls$ServerStreamingMethod
            ServerCalls$UnaryMethod ServerCallStreamObserver StreamObserver]
           [java.io File]
           [java.util.concurrent Executor Executors TimeUnit]))

(set! *warn-on-reflection* true)

(defn- fail! [^StreamObserver obs ^Throwable t]
  (.onError obs
            (if (instance? StatusRuntimeException t)
              t
              (-> Status/INTERNAL
                  (.withDescription (or (.getMessage t) (str (class t))))
                  (.withCause t)
                  (.asRuntimeException)))))

(defn- observer-fns
  "Adapt the map a streaming-in handler returns to a StreamObserver."
  ^StreamObserver [{:keys [on-next on-error on-complete]} ^StreamObserver response-obs]
  (reify StreamObserver
    (onNext [_ msg]
      (try
        (when on-next (on-next msg))
        (catch Throwable t (fail! response-obs t))))
    (onError [_ t]
      (when on-error (on-error t)))
    (onCompleted [_]
      (try
        (when on-complete (on-complete))
        (catch Throwable t (fail! response-obs t))))))

(defn- with-credits
  "Batch the transport's inbound flow control for a streaming-in handler: ask
  for `credits` messages up front and `credits` more each time that many have
  been delivered, instead of grpc-java's one request per delivered message.
  Returns the handler's fns map, wrapped; identity when credits is nil or the
  observer is not a ServerCallStreamObserver."
  [fns ^StreamObserver obs credits]
  (if (and credits (instance? ServerCallStreamObserver obs))
    (let [^ServerCallStreamObserver sobs obs
          n       (long credits)
          seen    (java.util.concurrent.atomic.AtomicLong. 0)
          on-next (:on-next fns)]
      (.disableAutoRequest sobs)
      (.request sobs (int n))
      (assoc fns :on-next
             (fn [msg]
               (when on-next (on-next msg))
               (when (zero? (rem (.incrementAndGet seen) n))
                 (.request sobs (int n))))))
    fns))

(defn- call-handler [type handler {:keys [inbound-credits]}]
  (case type
    :unary
    (ServerCalls/asyncUnaryCall
     (reify ServerCalls$UnaryMethod
       (invoke [_ request obs]
         (let [^StreamObserver obs obs]
           (try
             (.onNext obs (handler request))
             (.onCompleted obs)
             (catch Throwable t (fail! obs t)))))))

    :server-streaming
    (ServerCalls/asyncServerStreamingCall
     (reify ServerCalls$ServerStreamingMethod
       (invoke [_ request obs]
         (let [^StreamObserver obs obs]
           (try
             (handler request (fn send! [msg] (.onNext obs msg)))
             (.onCompleted obs)
             (catch Throwable t (fail! obs t)))))))

    :client-streaming
    (ServerCalls/asyncClientStreamingCall
     (reify ServerCalls$ClientStreamingMethod
       (invoke [_ obs]
         (let [^StreamObserver obs obs
               respond! (fn [response]
                          (.onNext obs response)
                          (.onCompleted obs))]
           (try
             (observer-fns (with-credits (handler respond!) obs inbound-credits) obs)
             (catch Throwable t
               (fail! obs t)
               (observer-fns {} obs)))))))

    :bidi
    (ServerCalls/asyncBidiStreamingCall
     (reify ServerCalls$BidiStreamingMethod
       (invoke [_ obs]
         (let [^StreamObserver obs obs
               send!  (fn [msg] (.onNext obs msg))
               close! (fn [] (.onCompleted obs))]
           (try
             (observer-fns (with-credits (handler send! close!) obs inbound-credits) obs)
             (catch Throwable t
               (fail! obs t)
               (observer-fns {} obs)))))))))

(defn service-definition
  "A dynamic ServerServiceDefinition from a service value and a handlers map.
  Methods without a handler are omitted and answer UNIMPLEMENTED, which is
  gRPC's own semantics for them. opts: :inbound-credits, as for `server`."
  (^ServerServiceDefinition [svc] (service-definition svc nil))
  (^ServerServiceDefinition [{:keys [service handlers]} opts]
   (let [b (ServerServiceDefinition/builder ^String (:full-name service))]
     (doseq [{:keys [key type method-descriptor]} (:methods service)]
       (when-let [handler (get handlers key)]
         (.addMethod b @method-descriptor (call-handler type handler opts))))
     (.build b))))

(defn- default-port []
  (or (some-> (System/getenv "PORT") Long/parseLong) 8080))

(defn server
  "Build (without starting) a server. Returns {:server io.grpc.Server
  :health HealthStatusManager-or-nil :address SocketAddress}."
  [{:keys [services address port transport health reflection executor
           interceptors tls permit-keepalive max-inbound-message-size
           inbound-credits]
    :or {health true}}]
  (let [addr      (transport/->address (or address (default-port)))
        unix?     (transport/unix-address? addr)
        transport (transport/resolve-transport
                   (if (and unix? (nil? transport)) :epoll transport))
        _         (transport/server-channel-type transport unix?) ; eager UDS/nio validation
        ^ServerBuilder builder
        ((requiring-resolve 'clj-grpc.impl.netty/server-builder)
         addr {:transport transport :unix? unix?
               :permit-keepalive permit-keepalive})
        health-mgr (when health (HealthStatusManager.))
        owned-executor (when (nil? executor)
                         (Executors/newVirtualThreadPerTaskExecutor))]
    (if (= :direct executor)
      (.directExecutor builder)
      (.executor builder ^Executor (or executor owned-executor)))
    (when max-inbound-message-size
      (.maxInboundMessageSize builder (int max-inbound-message-size)))
    (when tls
      (.useTransportSecurity builder
                             (File. (str (:cert-chain tls)))
                             (File. (str (:private-key tls)))))
    (doseq [^ServerInterceptor i interceptors] (.intercept builder i))
    (when inbound-credits
      (when-not (and (integer? inbound-credits) (pos? inbound-credits))
        (throw (IllegalArgumentException.
                (str ":inbound-credits must be a positive integer, got " (pr-str inbound-credits))))))
    (doseq [svc services]
      (.addService builder (service-definition svc {:inbound-credits inbound-credits})))
    (when health-mgr (.addService builder (.getHealthService health-mgr)))
    (when reflection (.addService builder (ProtoReflectionServiceV1/newInstance)))
    {:server (.build builder)
     :health health-mgr
     :owned-executor owned-executor
     :address addr}))

(defn start [{:keys [^Server server] :as s}]
  (.start server)
  s)

(defn port [{:keys [^Server server]}]
  (.getPort server))

(defn shutdown
  "Graceful by default; :grace-ms bounds the drain, then forces. The health
  service (when present) enters its terminal NOT_SERVING state first, so
  load balancers stop routing before the listener closes — the drain order
  Kubernetes rollouts assume."
  ([s] (shutdown s nil))
  ([{:keys [^Server server ^HealthStatusManager health owned-executor] :as s}
    {:keys [grace-ms]}]
   (when health (.enterTerminalState health))
   (.shutdown server)
   (when grace-ms
     (when-not (.awaitTermination server (long grace-ms) TimeUnit/MILLISECONDS)
       (.shutdownNow server)))
   (when owned-executor
     (.shutdown ^java.util.concurrent.ExecutorService owned-executor))
   s))

(defn await-termination [{:keys [^Server server]}]
  (.awaitTermination server))
