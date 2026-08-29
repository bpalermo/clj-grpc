(ns clj-grpc.transport
  "Netty transport selection: epoll where available, NIO everywhere else, Unix
  domain sockets on epoll only.

  Non-shaded Netty 4.2, whose event-loop API is MultiThreadIoEventLoopGroup
  over an IoHandler factory — the 4.1-era EpollEventLoopGroup constructors are
  deprecated shells around the same thing.

  No Netty class appears in this namespace (or in server/client): everything
  Netty lives in clj-grpc.impl.netty, reached through requiring-resolve at
  first construction. That is the native-image contract — Clojure initializes
  referenced classes when a namespace loads, which under
  --initialize-at-build-time is image build time, where Netty's own metadata
  (correctly) forbids it. Deferring the load to first use makes it run time in
  both worlds. lazy_netty_test pins the contract."
  (:import [java.net InetSocketAddress SocketAddress]))

(set! *warn-on-reflection* true)

(defn- netty
  "Resolve a clj-grpc.impl.netty fn, loading the namespace on first use. All
  callers are construction-time paths; the var lookup cost is irrelevant."
  [fn-name]
  (requiring-resolve (symbol "clj-grpc.impl.netty" fn-name)))

(defn epoll-available? []
  ((netty "epoll-available?")))

(defn resolve-transport
  "Turn the :transport opt into a concrete choice. :auto prefers epoll and
  falls back to :nio; asking for :epoll where it is unavailable is an error
  with Netty's own diagnosis as the cause."
  [transport]
  (case (or transport :auto)
    :auto  (if (epoll-available?) :epoll :nio)
    :nio   :nio
    :epoll (if (epoll-available?)
             :epoll
             (throw (ex-info "epoll transport requested but unavailable"
                             {:clj-grpc/error :transport-unavailable
                              :transport :epoll}
                             ((netty "epoll-unavailability-cause")))))))

(defn event-loop-group
  "An event loop group for the transport, on daemon threads — see
  clj-grpc.impl.netty/event-loop-group for why daemon is non-negotiable."
  [transport threads]
  ((netty "event-loop-group") transport threads))

(defn unix
  "A Unix domain socket address. Serving or dialing one requires the epoll
  transport; the server/client builders validate that eagerly."
  ^SocketAddress [^String path]
  ((netty "unix-socket-address") path))

(defn unix-address? [addr]
  (and (some? addr)
       (not (instance? InetSocketAddress addr))
       ((netty "unix-address?") addr)))

(defn ->address
  "Coerce the :address / target forms to a SocketAddress: an existing
  SocketAddress passes through, {:unix path} and \"unix:///path\" become domain
  socket addresses, an integer is a port on all interfaces."
  ^SocketAddress [addr]
  (cond
    (instance? SocketAddress addr) addr
    (integer? addr) (InetSocketAddress. (int addr))
    (and (map? addr) (:unix addr)) (unix (:unix addr))
    (and (string? addr) (.startsWith ^String addr "unix://"))
    (unix (subs addr 7))
    :else (throw (ex-info (str "cannot interpret address: " (pr-str addr))
                          {:clj-grpc/error :bad-address :address addr}))))

(defn server-channel-type
  ^Class [transport unix?]
  ((netty "server-channel-class") transport unix?))

(defn client-channel-type
  ^Class [transport unix?]
  ((netty "client-channel-class") transport unix?))
