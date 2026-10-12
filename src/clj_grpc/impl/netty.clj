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

(def ^:private shared-channel-groups
  "One group per transport, shared by every channel in the process.

  A group handed to a grpc-netty builder belongs to the caller: grpc shuts
  down only the group it created itself, so ManagedChannel.shutdown leaves
  this one running. One group per channel therefore meant one group kept for
  the life of the process per channel ever opened — measured at ~120
  descriptors each on a 20-core host, since EpollIoHandler opens an epoll, an
  event and a timer fd per loop and a group holds 2 x cores loops. Ten
  channels is EMFILE against the usual 1024 limit. Sharing makes the cost
  constant, which is what grpc-netty's own default does too (one group behind
  SharedResourceHolder).

  One epoll group serves both channel classes; only the class differs between
  a TCP and a Unix domain channel. Delays, so no group — and no Netty class
  beneath one — is constructed until the first channel on that transport.

  Never released: the threads are daemon, so a process exits regardless, and
  there is no last-channel-closed moment to hang a release on that a caller
  could not immediately invalidate by opening another. Servers, whose groups
  are sized per server and die with them, own theirs instead — see
  server-builder."
  {:epoll (delay (event-loop-group :epoll 0))
   :nio   (delay (event-loop-group :nio 0))})

(defn- shared-channel-group [transport]
  (if-let [d (get shared-channel-groups transport)]
    @d
    (throw (ex-info (str "no event loop group for transport " transport)
                    {:clj-grpc/error :transport-unavailable
                     :transport transport}))))

(defn shutdown-event-loops!
  "Release groups the library created and owns. Graceful in Netty's sense:
  each loop stops once it has been quiet for the quiet period, so work still
  running when the call arrives finishes first. Returns immediately — the
  futures are dropped, and the threads are daemon, so nothing here can hold a
  process open.

  The quiet period is short (100 ms) because a caller who needs in-flight
  calls to finish passes :grace-ms to shutdown, which has already awaited
  termination by the time this runs; the 5 s cap only bounds the case where
  something is still busy."
  [groups]
  (doseq [g groups]
    (.shutdownGracefully ^io.netty.util.concurrent.EventExecutorGroup g
                         100 5000 java.util.concurrent.TimeUnit/MILLISECONDS)))

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
  "A NettyServerBuilder with everything transport-specific applied, plus the
  event loop groups it was given: {:builder ServerBuilder :event-loops [...]}.
  clj-grpc.server layers the transport-agnostic configuration onto the builder
  without touching a Netty type, and keeps the groups to release at shutdown —
  Server.shutdown does not, because they are the caller's.

  Servers do not share the channels' group: :worker-threads sizes this one per
  server, and a server's loops should die with it rather than outlive it for
  the life of the process."
  [^java.net.SocketAddress addr {:keys [transport unix? permit-keepalive
                                        worker-threads initial-flow-control-window]}]
  (let [boss   (event-loop-group transport 1)
        ;; 0 = Netty's default of 2 × cores, sized for loops that do the
        ;; work; under an off-loop executor they only do I/O.
        worker (event-loop-group transport (or worker-threads 0))
        b (doto (io.grpc.netty.NettyServerBuilder/forAddress addr)
            (.channelType (server-channel-class transport unix?))
            (.bossEventLoopGroup boss)
            (.workerEventLoopGroup worker))]
    (when initial-flow-control-window
      ;; grpc-netty keeps BDP auto-tuning on; this only sets where it starts.
      (.initialFlowControlWindow b (int initial-flow-control-window)))
    (when-let [{:keys [time-ms without-calls]} permit-keepalive]
      (when time-ms
        (.permitKeepAliveTime b (long time-ms)
                              java.util.concurrent.TimeUnit/MILLISECONDS))
      (when (some? without-calls)
        (.permitKeepAliveWithoutCalls b (boolean without-calls))))
    {:builder b :event-loops [boss worker]}))

(defn channel-builder
  "A NettyChannelBuilder for the target with transport wired, returned as the
  generic ManagedChannelBuilder; clj-grpc.client does the rest generically."
  ^io.grpc.ManagedChannelBuilder
  [target {:keys [transport unix?]}]
  (doto (if unix?
          (io.grpc.netty.NettyChannelBuilder/forAddress (transport/->address target))
          (io.grpc.netty.NettyChannelBuilder/forTarget ^String target))
    (.channelType (client-channel-class transport unix?))
    (.eventLoopGroup (shared-channel-group transport))))
