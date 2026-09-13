(ns clj-grpc.context
  "What a handler can know about the call it is serving, read from grpc's own
  io.grpc.Context — the one grpc attaches around every callback of a call, on
  the virtual-thread executor and on :direct alike. No handler signature
  changes; a handler that wants the peer asks for it:

      (fn [req]
        (log/info \"hello from\" (context/peer) \"as\" (:user (context/call)))
        ...)

  `call` is the map the server's Clojure interceptors built and enriched —
  see clj-grpc.interceptor for its keys. It exists whenever at least one
  Clojure interceptor is in the server's chain; with none, nothing pays for
  building it and `call` answers nil. `(fn [call next] (next call))` is the
  whole cost of opting in. `deadline` and `cancelled?` come straight from
  grpc's context and work either way.

  The Context is a ThreadLocal, attached by grpc on the thread that runs the
  callback. A thread the handler starts itself does not inherit it: wrap the
  work with `(.wrap (Context/current) f)` before handing it over, or read what
  is needed first and pass it along."
  (:require [clj-grpc.metadata :as metadata])
  (:import [io.grpc Context Context$Key Deadline]))

(set! *warn-on-reflection* true)

(def ^:private ^Context$Key call-key (Context/key "clj-grpc.call"))

(defn ^:no-doc call-context
  "The current Context with `m` as the call map — what the interceptor
  adapter attaches around the rest of the chain."
  ^Context [m]
  (.withValue (Context/current) call-key m))

(defn call
  "The call map, or nil outside a call or when no Clojure interceptor is
  installed."
  []
  (.get call-key))

(defn headers
  "The request headers, an io.grpc.Metadata — read with clj-grpc.metadata."
  []
  (:headers (call)))

(defn header
  "The last value of one request header, or nil."
  [name]
  (metadata/header (headers) name))

(defn method
  "The method's kebab key, :say-hello — the same key as the handlers map."
  []
  (:method (call)))

(defn service
  "The fully qualified service name, \"acme.greeter.Greeter\"."
  []
  (:service (call)))

(defn peer
  "The client's java.net.SocketAddress, or nil when the transport has none."
  []
  (:peer (call)))

(defn authority
  "The :authority the client sent, or nil."
  []
  (:authority (call)))

(defn deadline
  "The call's io.grpc.Deadline, or nil when the client set none. From grpc's
  context; needs no interceptor."
  ^Deadline []
  (.getDeadline (Context/current)))

(defn cancelled?
  "Whether the call has been cancelled — by the client, or by its deadline.
  From grpc's context; needs no interceptor. A long handler polls this to
  stop working for nobody."
  []
  (.isCancelled (Context/current)))
