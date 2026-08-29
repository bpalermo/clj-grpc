(ns clj-grpc.coldstart.steady
  "Steady-state RPC performance of a server process, native vs JVM — the other
  half of the cold-start trade. Cold start measures the first RPC; this
  measures the ten-thousandth, after the JVM arm has had its JIT warmup and
  the native arm is running whatever the image builder froze.

  Same subject and spawn discipline as the cold-start harness: arms are
  argument vectors for ProcessBuilder, the server binds $PORT, and the prober
  is a warm JVM client in both arms so the server is the only variable.

  Per arm: wait for readiness, warm up over one channel, then measure
  sequential unary latency (median/p90/p99 over the measured calls) and
  32-way concurrent throughput on virtual threads."
  (:require [acme.greeter.greeter :as g]
            [clj-grpc.client :as client])
  (:import [java.lang ProcessBuilder$Redirect]
           [java.net ServerSocket]
           [java.util.concurrent CountDownLatch]))

(def ^:private warmup-calls 20000)
(def ^:private latency-calls 5000)
(def ^:private throughput-threads 32)
(def ^:private throughput-calls-per-thread 2000)

(defn- free-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- await-ready [port]
  (let [req (g/HelloRequest->proto {:name "ready?"})]
    (loop [n 0]
      (when (> n 600) (throw (ex-info "server never became ready" {:port port})))
      (let [ch (client/channel (str "localhost:" port) {:plaintext true})
            call (:say-hello (client/client ch g/greeter-methods {:deadline-ms 250}))
            ok (try (some? (call req)) (catch Throwable _ false))]
        (client/shutdown ch {:grace-ms 100})
        (when-not ok (Thread/sleep 50) (recur (inc n)))))))

(defn- percentile [sorted-ns p]
  (nth sorted-ns (min (dec (count sorted-ns))
                      (long (* p (count sorted-ns))))))

(defn- measure-arm [label cmd]
  (let [port (free-port)
        pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.redirectOutput ProcessBuilder$Redirect/DISCARD)
             (.redirectErrorStream true))]
    (.put (.environment pb) "PORT" (str port))
    (let [proc (.start pb)]
      (try
        (await-ready port)
        (let [ch (client/channel (str "localhost:" port) {:plaintext true})
              call (:say-hello (client/client ch g/greeter-methods {:deadline-ms 5000}))
              req (g/HelloRequest->proto {:name "steady"})]
          (try
            ;; Warmup: JIT for the JVM arm, connection/flow-control settling for
            ;; both. The native arm gets the same treatment for symmetry.
            (dotimes [_ warmup-calls] (call req))
            ;; Sequential latency.
            (let [samples (long-array latency-calls)]
              (dotimes [i latency-calls]
                (let [t0 (System/nanoTime)]
                  (call req)
                  (aset samples i (- (System/nanoTime) t0))))
              (let [sorted (vec (sort (vec samples)))
                    us #(/ (double (percentile sorted %)) 1e3)
                    ;; Concurrent throughput, virtual threads, shared channel.
                    total (* throughput-threads throughput-calls-per-thread)
                    start (CountDownLatch. 1)
                    done (CountDownLatch. throughput-threads)
                    t0* (atom nil)
                    _ (dotimes [_ throughput-threads]
                        (Thread/startVirtualThread
                         (fn []
                           (.await start)
                           (dotimes [_ throughput-calls-per-thread] (call req))
                           (.countDown done))))
                    _ (do (reset! t0* (System/nanoTime)) (.countDown start) (.await done))
                    calls-per-s (/ total (/ (- (System/nanoTime) @t0*) 1e9))]
                (println (format "| %s | %.0f µs | %.0f µs | %.0f µs | %,.0f calls/s |"
                                 label (us 0.5) (us 0.9) (us 0.99) calls-per-s))))
            (finally (client/shutdown ch {:grace-ms 1000}))))
        (finally
          (.destroy proc)
          (.waitFor proc))))))

(defn -main
  "args: label1 cmd1... '--' label2 cmd2... — same segments as the cold-start
  harness."
  [& args]
  (let [arms (->> (partition-by #{"--"} args)
                  (remove #{'("--")})
                  (map (fn [[label & cmd]] [label (vec cmd)])))]
    (println (format "| arm | unary p50 | p90 | p99 | %d-way throughput |"
                     throughput-threads))
    (println "|---|---|---|---|---|")
    (doseq [[label cmd] arms]
      (measure-arm label cmd))
    (shutdown-agents)))
