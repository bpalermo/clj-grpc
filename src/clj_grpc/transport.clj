(ns clj-grpc.transport
  "Netty transport selection: epoll where available, NIO everywhere else, Unix
  domain sockets on epoll only.

  Non-shaded Netty 4.2, whose event-loop API is MultiThreadIoEventLoopGroup
  over an IoHandler factory — the 4.1-era EpollEventLoopGroup constructors are
  deprecated shells around the same thing."
  (:import [io.netty.channel MultiThreadIoEventLoopGroup]
           [io.netty.channel.epoll
            Epoll EpollDomainSocketChannel EpollIoHandler
            EpollServerDomainSocketChannel EpollServerSocketChannel
            EpollSocketChannel]
           [io.netty.channel.nio NioIoHandler]
           [io.netty.channel.socket.nio NioServerSocketChannel NioSocketChannel]
           [io.netty.channel.unix DomainSocketAddress]
           [io.netty.util.concurrent DefaultThreadFactory]
           [java.net InetSocketAddress SocketAddress]))

(set! *warn-on-reflection* true)

(defn epoll-available? []
  (Epoll/isAvailable))

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
                             (Epoll/unavailabilityCause))))))

(defn event-loop-group
  "Daemon threads, deliberately — grpc's own default groups are daemon too. A
  non-daemon event loop pins the JVM after main returns, which surfaces as
  'tests pass, process never exits' the first time anything runs outside a
  harness that calls System/exit."
  ^MultiThreadIoEventLoopGroup [transport threads]
  (MultiThreadIoEventLoopGroup.
   (int (or threads 0))
   (DefaultThreadFactory. (str "clj-grpc-" (name transport)) true)
   (case transport
     :epoll (EpollIoHandler/newFactory)
     :nio   (NioIoHandler/newFactory))))

(defn unix
  "A Unix domain socket address. Serving or dialing one requires the epoll
  transport; the server/client builders validate that eagerly."
  ^DomainSocketAddress [^String path]
  (DomainSocketAddress. path))

(defn unix-address? [addr]
  (instance? DomainSocketAddress addr))

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
  (case transport
    :epoll (if unix? EpollServerDomainSocketChannel EpollServerSocketChannel)
    :nio   (if unix?
             (throw (ex-info "Unix domain sockets require the epoll transport"
                             {:clj-grpc/error :transport-unavailable
                              :transport :nio :address :unix}))
             NioServerSocketChannel)))

(defn client-channel-type
  ^Class [transport unix?]
  (case transport
    :epoll (if unix? EpollDomainSocketChannel EpollSocketChannel)
    :nio   (if unix?
             (throw (ex-info "Unix domain sockets require the epoll transport"
                             {:clj-grpc/error :transport-unavailable
                              :transport :nio :address :unix}))
             NioSocketChannel)))
