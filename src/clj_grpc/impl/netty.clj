(ns clj-grpc.impl.netty
  "Every Netty and grpc-netty construction in the library, quarantined.

  This namespace exists for GraalVM native-image. Clojure initializes the
  classes a compiled namespace references when the namespace loads, and under
  --initialize-at-build-time namespace loading happens during image build —
  where Netty's own native-image metadata forbids initializing its
  native-library and Unsafe-touching classes, correctly. The API namespaces
  (transport, server, client) therefore reference this one only through
  requiring-resolve at first construction, never :require, and the library's
  META-INF/native-image config marks the clj_grpc.impl package
  initialize-at-run-time and registers this namespace's init class for the
  runtime require. Net effect: Netty initializes at first server/channel
  construction — run time in both worlds — and requiring the API namespaces
  stays Netty-free. lazy_netty_test pins both halves of that contract.

  Class names are written fully qualified rather than :import'ed on purpose:
  imports are interned by the namespace loader through Class.forName, and at
  run time in a native image every such name would need a reflection entry.
  Fully-qualified interop compiles to direct method references; only classes
  used as *values* (the channel classes below) go through Class.forName, and
  exactly those are registered in reflect-config.json."
  (:require [clj-grpc.transport :as transport]))

(set! *warn-on-reflection* true)

(defn epoll-available? []
  (io.netty.channel.epoll.Epoll/isAvailable))

(defn epoll-unavailability-cause ^Throwable []
  (io.netty.channel.epoll.Epoll/unavailabilityCause))

(defn event-loop-group
  "Daemon threads, deliberately — grpc's own default groups are daemon too. A
  non-daemon event loop pins the JVM after main returns, which surfaces as
  'tests pass, process never exits' the first time anything runs outside a
  harness that calls System/exit."
  [transport threads]
  (io.netty.channel.MultiThreadIoEventLoopGroup.
   (int (or threads 0))
   (io.netty.util.concurrent.DefaultThreadFactory.
    (str "clj-grpc-" (name transport)) true)
   (case transport
     :epoll (io.netty.channel.epoll.EpollIoHandler/newFactory)
     :nio   (io.netty.channel.nio.NioIoHandler/newFactory))))

(defn unix-socket-address ^java.net.SocketAddress [^String path]
  (io.netty.channel.unix.DomainSocketAddress. path))

(defn unix-address? [addr]
  (instance? io.netty.channel.unix.DomainSocketAddress addr))

(defn server-channel-class
  ^Class [transport unix?]
  (case transport
    :epoll (if unix?
             io.netty.channel.epoll.EpollServerDomainSocketChannel
             io.netty.channel.epoll.EpollServerSocketChannel)
    :nio   (if unix?
             (throw (ex-info "Unix domain sockets require the epoll transport"
                             {:clj-grpc/error :transport-unavailable
                              :transport :nio :address :unix}))
             io.netty.channel.socket.nio.NioServerSocketChannel)))

(defn client-channel-class
  ^Class [transport unix?]
  (case transport
    :epoll (if unix?
             io.netty.channel.epoll.EpollDomainSocketChannel
             io.netty.channel.epoll.EpollSocketChannel)
    :nio   (if unix?
             (throw (ex-info "Unix domain sockets require the epoll transport"
                             {:clj-grpc/error :transport-unavailable
                              :transport :nio :address :unix}))
             io.netty.channel.socket.nio.NioSocketChannel)))

(defn server-builder
  "A NettyServerBuilder with everything transport-specific applied, returned
  as the generic ServerBuilder; clj-grpc.server layers the transport-agnostic
  configuration on top without touching a Netty type."
  ^io.grpc.ServerBuilder
  [^java.net.SocketAddress addr {:keys [transport unix? permit-keepalive]}]
  (let [b (doto (io.grpc.netty.NettyServerBuilder/forAddress addr)
            (.channelType (server-channel-class transport unix?))
            (.bossEventLoopGroup (event-loop-group transport 1))
            (.workerEventLoopGroup (event-loop-group transport 0)))]
    (when-let [{:keys [time-ms without-calls]} permit-keepalive]
      (when time-ms
        (.permitKeepAliveTime b (long time-ms)
                              java.util.concurrent.TimeUnit/MILLISECONDS))
      (when (some? without-calls)
        (.permitKeepAliveWithoutCalls b (boolean without-calls))))
    b))

(defn channel-builder
  "A NettyChannelBuilder for the target with transport wired, returned as the
  generic ManagedChannelBuilder; clj-grpc.client does the rest generically."
  ^io.grpc.ManagedChannelBuilder
  [target {:keys [transport unix?]}]
  (doto (if unix?
          (io.grpc.netty.NettyChannelBuilder/forAddress (transport/->address target))
          (io.grpc.netty.NettyChannelBuilder/forTarget ^String target))
    (.channelType (client-channel-class transport unix?))
    (.eventLoopGroup (event-loop-group transport 0))))
