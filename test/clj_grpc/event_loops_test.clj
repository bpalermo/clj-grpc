(ns clj-grpc.event-loops-test
  "Who owns the Netty event loop groups, and that they are actually released.

  grpc-netty shuts down only the group it created itself; a group passed to
  NettyChannelBuilder.eventLoopGroup or to NettyServerBuilder's boss/worker
  setters belongs to the caller, and Server.shutdown / ManagedChannel.shutdown
  leave it running. The library passes its own groups in all three places — it
  must, because it forces the channel class for epoll and UDS and the two have
  to agree — so ownership is answered per side: servers own theirs and release
  them at shutdown, channels share one group per transport for the life of the
  process.

  Measured in groups and in file descriptors, never in threads. Netty starts a
  group's loops lazily, one per loop first given work, so on a 20-core host
  each new channel started ~5 more threads inside WHATEVER group it used —
  identically whether that group was shared or fresh. A thread count therefore
  measures load, and would have passed before the fix. What actually grew was
  one group and ~120 descriptors per channel (EpollIoHandler opens an epoll, an
  event and a timer fd per loop, 2 × cores loops to a group), which puts a
  caller that reconnects by replacing the channel into EMFILE against the usual
  1024 limit inside ten channels."
  (:require [acme.greeter.greeter :as g]
            [clj-grpc.client :as client]
            [clj-grpc.server :as server]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]))

(def ^:private handlers
  {:say-hello (fn [req]
                (g/HelloReply->proto
                 {:message (str "Hello " (:name (g/proto->HelloRequest req)))}))})

(defn- live-groups
  "The distinct groups behind the live clj-grpc-* threads. Netty's
  DefaultThreadFactory names a thread <poolName>-<poolId>-<n>, and the
  library's poolName is clj-grpc-<transport>, so the first three segments name
  the group. poolId is a process-wide counter shared with every other Netty
  pool, so the library's ids are distinct but not contiguous."
  []
  (into #{}
        (comp (map #(.getName ^Thread %))
              (filter #(.startsWith ^String % "clj-grpc-"))
              (map #(re-find #"^clj-grpc-[a-z]+-\d+" %)))
        (keys (Thread/getAllStackTraces))))

(def ^:private fd-dir (java.io.File. "/proc/self/fd"))

(defn- open-fds
  "Descriptors held by this process, or nil off Linux. The sharpest signal
  here: a group costs ~3 × 2 × cores of them and releases them only on
  shutdownGracefully."
  []
  (when (.isDirectory fd-dir)
    (some-> (.list fd-dir) alength)))

(defn- quiesce
  "Wait until the live group set stops changing. Release is graceful and
  asynchronous — Netty waits for each loop to fall quiet — so without this a
  previous test's groups are still dying while this one takes its baseline."
  []
  (let [deadline (+ (System/currentTimeMillis) 15000)]
    (loop [prev nil]
      (let [now (live-groups)]
        (if (or (= prev now) (> (System/currentTimeMillis) deadline))
          now
          (do (Thread/sleep 250) (recur now)))))))

(defn- serve
  "A started server and a channel that has made one real call on it, so the
  loops on both sides have run and their threads exist to be found."
  []
  (let [srv (-> (server/server {:services [{:service g/Greeter :handlers handlers}]
                                :address 0})
                server/start)
        ch  (client/channel (str "localhost:" (server/port srv)) {:plaintext true})]
    (is (= "Hello leak-check"
           (:message (g/proto->HelloReply
                      (client/invoke ch (:say-hello g/greeter-methods)
                                     (g/HelloRequest->proto {:name "leak-check"})
                                     {:deadline-ms 10000}))))
        "the call must succeed, or no loop ran and nothing below is measuring")
    [srv ch]))

(deftest the-thread-names-identify-a-group
  (let [[srv ch] (serve)]
    (try
      (let [groups (live-groups)]
        (is (seq groups) "a served call must have started named loops")
        (is (every? #(re-matches #"clj-grpc-(epoll|nio)-\d+" %) groups)
            (str "live-groups must parse what DefaultThreadFactory writes, or "
                 "every count in this namespace is meaningless: " (sort groups))))
      (finally
        (client/shutdown ch {:grace-ms 2000})
        (server/shutdown srv {:grace-ms 2000})))))

(deftest channels-share-one-group-per-transport
  (let [[srv ch0] (serve)
        _ (quiesce)
        before (live-groups)
        fds-before (open-fds)]
    (try
      (testing "four more channels build no group of their own"
        (let [chans (doall (repeatedly 4 #(client/channel
                                           (str "localhost:" (server/port srv))
                                           {:plaintext true})))
              calls (map #(client/client % g/greeter-methods {:deadline-ms 10000}) chans)]
          (try
            (doseq [c calls]
              (is (= "Hello shared"
                     (:message (g/proto->HelloReply
                                ((:say-hello c) (g/HelloRequest->proto {:name "shared"})))))))
            (is (empty? (set/difference (live-groups) before))
                "each must reuse the group the first channel built")
            (when fds-before
              (is (< (- (open-fds) fds-before) 30)
                  (str "four groups' worth of epoll descriptors would be ~480; "
                       "grew by " (- (open-fds) fds-before))))
            (finally
              (doseq [c chans] (client/shutdown c {:grace-ms 2000}))))))
      (testing "and shutting a channel down leaves the shared group up"
        (is (= before (live-groups))
            "the group outlives any one channel, so the next opens none"))
      (finally
        (client/shutdown ch0 {:grace-ms 2000})
        (server/shutdown srv {:grace-ms 2000})))))

(deftest a-server-releases-its-own-loops
  (let [before (quiesce)
        fds-before (open-fds)]
    (testing "each server builds a boss and a worker group, and gives both back"
      (dotimes [_ 3]
        (let [[srv ch] (serve)]
          (is (<= 2 (count (set/difference (live-groups) before)))
              "a live server holds groups of its own, beyond the shared one")
          (client/shutdown ch {:grace-ms 2000})
          (server/shutdown srv {:grace-ms 2000}))))
    (let [left (set/difference (quiesce) before)]
      ;; At most the channels' shared group, and only if this test created it.
      ;; Before the fix, six server groups stayed for the life of the process.
      (is (<= (count left) 1)
          (str "three server lifetimes must leave no group of their own behind, "
               "found " (count left) ": " (sort left)))
      (when fds-before
        (is (< (- (open-fds) fds-before) 180)
            (str "a server's two groups cost ~123 descriptors, so three leaked "
                 "lifetimes read as ~370 over a shared group's 120; grew by "
                 (- (open-fds) fds-before)))))))
