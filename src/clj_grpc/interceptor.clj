(ns clj-grpc.interceptor
  "Interceptors as functions of the call, on both sides of the wire.

      ;; server: reject, enrich, or pass through
      (fn [call next]
        (if-let [user (verify (metadata/header (:headers call) \"authorization\"))]
          (next (assoc call :user user))
          (reject :unauthenticated \"missing or bad token\"
                  {\"www-authenticate\" \"Bearer\"})))

      ;; client: declare headers, watch the response
      (fn [call next]
        (next (-> call
                  (update :headers assoc \"authorization\" (str \"Bearer \" token))
                  (assoc :on-trailers (fn [status trailers] ...)))))

  Either goes in an :interceptors vector — `server`'s or `channel`'s, or per
  call in `invoke`'s opts — mixed freely with raw io.grpc interceptors. The
  vector runs in order on every path: [a b c] is a outermost, first in and
  last out, whether a is a fn or a ServerInterceptor. (grpc's builders run
  the last-registered interceptor outermost; registration reverses the
  vector so the declared order is the running order.)

  The server call map:

    :method             kebab keyword, :say-hello — the handlers-map key
    :service            \"acme.greeter.Greeter\"
    :full-method-name   \"acme.greeter.Greeter/SayHello\"
    :headers            the request io.grpc.Metadata, raw and mutable
    :authority          what the client sent as :authority, or nil
    :peer               the client's SocketAddress, or nil
    :deadline           io.grpc.Deadline, or nil
    :method-descriptor  :attributes  :server-call   — the grpc objects

  An interceptor returns (next call'), possibly with the map enriched — what
  it adds is what inner interceptors and, through clj-grpc.context, the
  handler see — or a rejection. Two keys given to `next` reach the wire
  instead of the map: :response-headers and :response-trailers, each a map
  or a Metadata, merged into what the handler's response carries. Health and
  reflection calls pass through the chain too; :service tells them apart.

  Rejection is a value, not an exception: (reject code description trailers)
  closes the call with that status before any handler runs, and the normal
  auth-failure path pays for no stack trace. Throwing works as it does in
  handlers — a StatusRuntimeException keeps its status and trailers,
  anything else becomes INTERNAL with the message.

  The client call map:

    :method :service :full-method-name :method-descriptor   as above
    :call-options       the io.grpc.CallOptions, replaceable
    :headers            a MAP of the outgoing headers declared so far
    :authority          the channel's
    :deadline           from the call options, or nil

  Headers are declared in the map and written when the call starts — grpc
  creates the outgoing Metadata after interceptors run — so an inner
  interceptor sees and may override what an outer one declared. The map
  given to `next` may add :on-headers (fn [metadata]) and
  :on-trailers (fn [status metadata]) to observe the response. Headers a raw
  interceptor adds at start are invisible here.

  Deliberately not here: hooks per message (that is the handler's shape),
  registration per method (io.grpc.ServerInterceptors/intercept on a
  service definition is one call away), and trailers set from inside a
  handler — the error path already carries them: throw
  (.asRuntimeException (status :not-found \"...\") trailers)."
  (:require [clj-grpc.context :as context]
            [clj-grpc.metadata :as metadata]
            [clj-grpc.names :as names])
  (:import [io.grpc CallOptions CallOptions$Key Channel ClientCall
            ClientCall$Listener ClientInterceptor Context Contexts
            ForwardingClientCall$SimpleForwardingClientCall
            ForwardingClientCallListener$SimpleForwardingClientCallListener
            ForwardingServerCall$SimpleForwardingServerCall Grpc Metadata
            MethodDescriptor ServerCall ServerCall$Listener ServerCallHandler
            ServerInterceptor Status Status$Code StatusRuntimeException]
           [java.util.concurrent ConcurrentHashMap]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Statuses and rejection

(def ^:private statuses
  "Keyword -> Status, derived from grpc's own enum so it cannot drift:
  :unauthenticated, :permission-denied, :invalid-argument, ..."
  (into {}
        (map (fn [^Status$Code c] [(names/kebab (.name c)) (Status/fromCode c)]))
        (Status$Code/values)))

(defn status
  "An io.grpc.Status from a keyword (:not-found), a Status$Code, or a Status,
  with an optional description."
  (^Status [code] (status code nil))
  (^Status [code description]
   (let [^Status s (cond
                     (instance? Status code) code
                     (instance? Status$Code code) (Status/fromCode code)
                     (keyword? code)
                     (or (statuses code)
                         (throw (ex-info (str "unknown status " code)
                                         {:clj-grpc/error :bad-status
                                          :status code
                                          :valid (sort (keys statuses))})))
                     :else
                     (throw (ex-info (str "not a status: " (pr-str code))
                                     {:clj-grpc/error :bad-status :status code})))]
     (if description (.withDescription s (str description)) s))))

(defrecord Rejection [^Status status ^Metadata trailers])

(defn reject
  "The value a server interceptor returns to close the call before any
  handler runs: a status (keyword, Status$Code or Status), a description, and
  trailers as a map or Metadata."
  ([code] (reject code nil nil))
  ([code description] (reject code description nil))
  ([code description trailers]
   (->Rejection (status code description) (metadata/metadata trailers))))

(defn- status-of ^Status [^Throwable t]
  (if (instance? StatusRuntimeException t)
    (.getStatus ^StatusRuntimeException t)
    (-> Status/INTERNAL
        (.withDescription (or (.getMessage t) (str (class t))))
        (.withCause t))))

(defn- trailers-of ^Metadata [^Throwable t]
  (or (when (instance? StatusRuntimeException t)
        (.getTrailers ^StatusRuntimeException t))
      (Metadata.)))

;; ---------------------------------------------------------------------------
;; The call maps

(def ^:private ^ConcurrentHashMap method-keys (ConcurrentHashMap.))

(defn- method-key
  "The kebab key for a method descriptor, memoized per full method name — the
  same derivation as the handlers map, so :method is that map's key."
  [^MethodDescriptor md]
  (let [full (.getFullMethodName md)]
    (or (.get method-keys full)
        (let [k (names/kebab (.getBareMethodName md))]
          (or (.putIfAbsent method-keys full k) k)))))

(defn- server-call-map [^ServerCall call ^Metadata headers]
  (let [md    (.getMethodDescriptor call)
        attrs (.getAttributes call)]
    {:method            (method-key md)
     :service           (.getServiceName md)
     :full-method-name  (.getFullMethodName md)
     :method-descriptor md
     :headers           headers
     :authority         (.getAuthority call)
     :peer              (when attrs (.get attrs Grpc/TRANSPORT_ATTR_REMOTE_ADDR))
     :attributes        attrs
     :deadline          (.getDeadline (Context/current))
     :server-call       call}))

(def ^:private ^CallOptions$Key headers-option
  "Carries the headers Clojure interceptors have declared so far, so an
  inner one sees the outer's. Written into the outgoing Metadata at start."
  (CallOptions$Key/create "clj-grpc.headers"))

(defn- client-call-map [^MethodDescriptor md ^CallOptions copts ^Channel ch]
  {:method            (method-key md)
   :service           (.getServiceName md)
   :full-method-name  (.getFullMethodName md)
   :method-descriptor md
   :call-options      copts
   :headers           (or (.getOption copts headers-option) {})
   :authority         (.authority ch)
   :deadline          (.getDeadline copts)})

;; ---------------------------------------------------------------------------
;; Server side

(defn- noop-listener ^ServerCall$Listener []
  (proxy [ServerCall$Listener] []))

(defn- with-response-metadata
  "The call with headers and trailers merged into what the handler sends.
  Direct delegate calls, never proxy-super: proxy-super mutates the proxy's
  method map for the duration of the call and is not thread-safe."
  ^ServerCall [^ServerCall call hdrs trls]
  (proxy [ForwardingServerCall$SimpleForwardingServerCall] [call]
    (sendHeaders [md]
      (when hdrs (metadata/merge-into! md hdrs))
      (.sendHeaders call ^Metadata md))
    (close [status md]
      (when trls (metadata/merge-into! md trls))
      (.close call ^Status status ^Metadata md))))

(defn- interceptor-fn? [f]
  (or (fn? f) (var? f)))

(defn server-interceptor
  "An io.grpc.ServerInterceptor from a (fn [call next]) — the identity on a
  ServerInterceptor, so a mixed :interceptors vector converts uniformly."
  ^ServerInterceptor [f]
  (cond
    (instance? ServerInterceptor f) f

    (interceptor-fn? f)
    (reify ServerInterceptor
      (interceptCall [_ call headers next]
        (let [^ServerCall call call
              ^Metadata headers headers
              ^ServerCallHandler next next
              started  (volatile! nil)
              m        (or (context/call) (server-call-map call headers))
              next-fn  (fn [{:keys [response-headers response-trailers] :as m'}]
                         (let [call' (if (or response-headers response-trailers)
                                       (with-response-metadata call response-headers response-trailers)
                                       call)
                               m'    (dissoc m' :response-headers :response-trailers)
                               l     (Contexts/interceptCall (context/call-context m') call' headers next)]
                           (vreset! started l)
                           l))]
          (try
            (let [ret (f m next-fn)]
              (cond
                (instance? ServerCall$Listener ret) ret

                (instance? Rejection ret)
                (if @started
                  (throw (IllegalStateException.
                          "interceptor rejected the call after calling next"))
                  (do (.close call ^Status (:status ret) ^Metadata (:trailers ret))
                      (noop-listener)))

                ;; next was called but its result not returned: the call has
                ;; its listener, use it rather than strand the client.
                @started @started

                :else
                (do (.close call
                            (.withDescription Status/INTERNAL
                                              "interceptor returned neither next's result nor a rejection")
                            (Metadata.))
                    (noop-listener))))
            (catch Throwable t
              (if @started
                ;; The call is started and has a listener; a second close
                ;; would throw inside grpc. Let it surface.
                (throw t)
                (do (.close call (status-of t) (trailers-of t))
                    (noop-listener))))))))

    :else
    (throw (IllegalArgumentException.
            (str "an interceptor must be a fn or an io.grpc.ServerInterceptor, got "
                 (pr-str f))))))

;; ---------------------------------------------------------------------------
;; Client side

(defn- callback-listener
  ^ClientCall$Listener [^ClientCall$Listener l on-headers on-trailers]
  (proxy [ForwardingClientCallListener$SimpleForwardingClientCallListener] [l]
    (onHeaders [md]
      (when on-headers (on-headers md))
      (.onHeaders l ^Metadata md))
    (onClose [s md]
      (when on-trailers (on-trailers s md))
      (.onClose l ^Status s ^Metadata md))))

(defn client-interceptor
  "An io.grpc.ClientInterceptor from a (fn [call next]) — the identity on a
  ClientInterceptor."
  ^ClientInterceptor [f]
  (cond
    (instance? ClientInterceptor f) f

    (interceptor-fn? f)
    (reify ClientInterceptor
      (interceptCall [_ method copts channel]
        (let [^MethodDescriptor method method
              ^CallOptions copts copts
              ^Channel channel channel
              m (client-call-map method copts channel)
              next-fn
              (fn [{:keys [headers call-options on-headers on-trailers]}]
                (let [^CallOptions copts' (or call-options copts)
                      ^CallOptions copts' (if (seq headers)
                                            (.withOption copts' headers-option headers)
                                            copts')
                      ^ClientCall call   (.newCall channel method copts')]
                  (if (or (seq headers) on-headers on-trailers)
                    (proxy [ForwardingClientCall$SimpleForwardingClientCall] [call]
                      (start [listener md]
                        (when (seq headers) (metadata/merge-into! md headers))
                        (let [^ClientCall$Listener l (if (or on-headers on-trailers)
                                                       (callback-listener listener on-headers on-trailers)
                                                       listener)]
                          (.start call l ^Metadata md))))
                    call)))
              ret (f m next-fn)]
          (if (instance? ClientCall ret)
            ret
            (throw (ex-info "a client interceptor must return the result of next"
                            {:clj-grpc/error :bad-interceptor-return
                             :method (:full-method-name m)}))))))

    :else
    (throw (IllegalArgumentException.
            (str "an interceptor must be a fn or an io.grpc.ClientInterceptor, got "
                 (pr-str f))))))
