(ns clj-grpc.client
  "Channels and calls, from the same methods map the server consumes.

      (def ch (channel \"localhost:8080\" {:plaintext true}))
      (invoke ch (:say-hello greeter/greeter-methods) request)

  Call shapes, by method type:
    :unary            request -> response (blocking)
    :server-streaming request -> lazy seq of responses (blocking iterator)
    :client-streaming -> {:send! (fn [msg]) :close! (fn []) :response promise}
    :bidi             observer-map -> {:send! ... :close! ...}; responses
                      arrive through the caller's {:on-next ...} map"
  (:require [clj-grpc.transport :as transport])
  (:import [io.grpc CallOptions ManagedChannel]
           [io.grpc.netty NettyChannelBuilder]
           [io.grpc.stub ClientCalls StreamObserver]
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
    :interceptors     [io.grpc.ClientInterceptor ...]
    :default-service-config  map, e.g. retry policy (enables retries when set)"
  ^ManagedChannel
  [target {:keys [plaintext transport keepalive idle-timeout-ms
                  max-inbound-message-size interceptors default-service-config]}]
  (let [unix?     (or (and (map? target) (:unix target))
                      (and (string? target) (.startsWith ^String target "unix://")))
        transport (transport/resolve-transport
                   (if (and unix? (nil? transport)) :epoll transport))
        builder   (if unix?
                    (NettyChannelBuilder/forAddress (transport/->address target))
                    (NettyChannelBuilder/forTarget ^String target))]
    (.channelType builder (transport/client-channel-type transport unix?))
    (.eventLoopGroup builder (transport/event-loop-group transport 0))
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
    (doseq [i interceptors] (.intercept builder ^"[Lio.grpc.ClientInterceptor;" (into-array io.grpc.ClientInterceptor [i])))
    (.build builder)))

(defn- call-options ^CallOptions [{:keys [deadline-ms wait-for-ready]}]
  (cond-> CallOptions/DEFAULT
    deadline-ms    (.withDeadlineAfter (long deadline-ms) TimeUnit/MILLISECONDS)
    wait-for-ready (.withWaitForReady)))

(defn- response-observer ^StreamObserver [{:keys [on-next on-error on-complete]}]
  (reify StreamObserver
    (onNext [_ msg] (when on-next (on-next msg)))
    (onError [_ t] (when on-error (on-error t)))
    (onCompleted [_] (when on-complete (on-complete)))))

(defn- request-observer-map [^StreamObserver req-obs]
  {:send!  (fn [msg] (.onNext req-obs msg))
   :close! (fn [] (.onCompleted req-obs))
   :error! (fn [t] (.onError req-obs t))})

(defn invoke
  "Call one method. The blocking shapes block; the streaming-in shapes return
  immediately with the request-side controls."
  ([ch method request] (invoke ch method request nil))
  ([^ManagedChannel ch {:keys [type method-descriptor]} request opts]
   (let [md   @method-descriptor
         copt (call-options opts)]
     (case type
       :unary
       (ClientCalls/blockingUnaryCall ch md copt request)

       :server-streaming
       (iterator-seq (ClientCalls/blockingServerStreamingCall ch md copt request))

       :client-streaming
       (let [response (promise)
             req-obs  (ClientCalls/asyncClientStreamingCall
                       (.newCall ch md copt)
                       (response-observer {:on-next #(deliver response %)
                                           :on-error #(deliver response %)}))]
         (assoc (request-observer-map req-obs) :response response))

       :bidi
       (let [req-obs (ClientCalls/asyncBidiStreamingCall
                      (.newCall ch md copt)
                      (response-observer request))]
         (request-observer-map req-obs))))))

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
  ([ch] (shutdown ch nil))
  ([^ManagedChannel ch {:keys [grace-ms]}]
   (.shutdown ch)
   (when grace-ms
     (when-not (.awaitTermination ch (long grace-ms) TimeUnit/MILLISECONDS)
       (.shutdownNow ch)))
   ch))
