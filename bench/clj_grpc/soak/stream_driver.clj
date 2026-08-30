(ns clj-grpc.soak.stream-driver
  "Bidi-echo capacity driver: S persistent Chat streams, aggregate message
  rate ramped per step, per-message latency measured client-side.

  Built after evaluating k6's stream support and rejecting it for this job
  (reflective JSON<->proto per message, no backpressure, per-stream not
  per-message latency): this driver is open-loop with an ABSOLUTE schedule —
  message n of a step is due at t0 + n/rate, and a late send goes out
  immediately rather than silently stretching the schedule — so coordinated
  omission cannot flatter the numbers. In-flight per stream is bounded: when
  the echo stream falls behind by more than INFLIGHT messages, sends are
  counted as deferred instead of queued, which is the knee signal.

  gRPC guarantees per-stream ordering, so echo k answers send k: a FIFO of
  send timestamps per stream gives exact per-message latency with no payload
  games.

  Runs from the coldstart deploy jar (it rides the soak-grpc-jvm image):

      java -cp app.jar clojure.main -m clj-grpc.soak.stream-driver

  env: TARGET_ADDR (host:port), STREAMS (20), RAMP (\"400 800 ...\" aggregate
  msg/s per step), STEP_SECONDS (120), INFLIGHT (256)."
  (:require [acme.greeter.greeter :as g]
            [clj-grpc.client :as client]
            [clojure.string :as str])
  (:import [java.util.concurrent ConcurrentLinkedQueue CountDownLatch TimeUnit]
           [java.util.concurrent.atomic AtomicLong AtomicLongArray]
           [java.util.concurrent.locks LockSupport])
  (:gen-class))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Latency histogram: fixed exponential buckets, lock-free recording.

(def ^:private bucket-count 44)
(def ^:private ^doubles bucket-bounds-ns
  ;; 0.05ms * 1.35^k — covers ~0.05ms to ~10s in 44 buckets.
  (double-array (map #(* 50000.0 (Math/pow 1.35 %)) (range bucket-count))))

(defn- record! [^AtomicLongArray hist ^long nanos]
  (loop [i (int 0)]
    (if (or (= i (dec bucket-count))
            (<= (double nanos) (aget ^doubles bucket-bounds-ns i)))
      (.incrementAndGet hist i)
      (recur (inc i)))))

(defn- percentile-ms [^AtomicLongArray hist ^double q]
  (let [total (loop [i (int 0) acc 0]
                (if (= i bucket-count) acc (recur (inc i) (+ acc (.get hist i)))))
        target (Math/ceil (* q (double total)))]
    (if (zero? total)
      0.0
      (loop [i (int 0) acc 0]
        (let [acc (+ acc (.get hist i))]
          (if (or (>= (double acc) target) (= i (dec bucket-count)))
            (/ (aget ^doubles bucket-bounds-ns i) 1e6)
            (recur (inc i) acc)))))))

;; ---------------------------------------------------------------------------

(defn- env [k default] (or (System/getenv k) default))

(defn- make-stream
  "One persistent bidi stream: returns {:send-raw! :close! :pending :received}."
  [ch method ^AtomicLongArray hist ^AtomicLong received ^CountDownLatch done]
  (let [pending (ConcurrentLinkedQueue.)
        controls (client/invoke ch method
                                {:on-next (fn [_reply]
                                            (when-some [^Long t0 (.poll pending)]
                                              (record! hist (- (System/nanoTime) (long t0)))
                                              (.incrementAndGet received)))
                                 :on-error (fn [t] (println "stream error:" (str t)) (.countDown done))
                                 :on-complete (fn [] (.countDown done))}
                                nil)]
    {:send-raw! (:send! controls)
     :close! (:close! controls)
     :pending pending}))

(defn- run-step!
  "Drive all streams at aggregate `rate` msg/s for `seconds`. Returns
  {:sent :deferred :received} counted over the step."
  [streams rate seconds max-inflight ^AtomicLong received]
  (let [n-streams (count streams)
        per-stream-rate (/ (double rate) n-streams)
        interval-ns (long (/ 1e9 per-stream-rate))
        req (g/HelloRequest->proto {:name "m"})
        sent (AtomicLong.) deferred (AtomicLong.)
        r0 (.get received)
        end-latch (CountDownLatch. n-streams)]
    (doseq [{:keys [send-raw! pending]} streams]
      (Thread/startVirtualThread
       (fn []
         (let [t0 (System/nanoTime)
               deadline (+ t0 (* seconds 1000000000))]
           (loop [n 0]
             (let [due (+ t0 (* n interval-ns))]
               (when (< due deadline)
                 (let [wait (- due (System/nanoTime))]
                   (when (pos? wait) (LockSupport/parkNanos wait)))
                 (if (< (.size ^ConcurrentLinkedQueue pending) max-inflight)
                   (do (.offer ^ConcurrentLinkedQueue pending (System/nanoTime))
                       (send-raw! req)
                       (.incrementAndGet sent))
                   (.incrementAndGet deferred))
                 (recur (inc n)))))
           (.countDown end-latch)))))
    (.await end-latch)
    ;; let in-flight echoes drain briefly before reading the step's counters
    (Thread/sleep 500)
    {:sent (.get sent) :deferred (.get deferred) :received (- (.get received) r0)}))

(defn -main [& _]
  (let [addr (env "TARGET_ADDR" "localhost:8080")
        n-streams (Long/parseLong (env "STREAMS" "20"))
        ramp (mapv #(Long/parseLong %) (str/split (env "RAMP" "400 800 1200 1600 2000 2400 2800 3200 3600 4000 4400 4800") #"\s+"))
        step-seconds (Long/parseLong (env "STEP_SECONDS" "120"))
        max-inflight (Long/parseLong (env "INFLIGHT" "256"))
        ch (client/channel addr {:plaintext true})
        method (:chat g/greeter-methods)
        received (AtomicLong.)
        done (CountDownLatch. n-streams)
        ;; One shared histogram, zeroed between steps: the streams are
        ;; persistent, so the array they capture must be too.
        hist (AtomicLongArray. bucket-count)
        streams (vec (repeatedly n-streams #(make-stream ch method hist received done)))]
    (println (format "streams=%d addr=%s inflight=%d step=%ds" n-streams addr max-inflight step-seconds))
    (println "| offered msg/s | sent/s | echoed/s | p50ms | p99ms | p999ms | deferred/s |")
    (println "|---|---|---|---|---|---|---|")
    (doseq [rate ramp]
      (dotimes [i bucket-count] (.set hist i 0))
      (let [{:keys [sent deferred received]} (run-step! streams rate step-seconds max-inflight received)
            h hist]
        (println (format "| %d | %.1f | %.1f | %.2f | %.2f | %.2f | %.1f |"
                         rate
                         (/ (double sent) step-seconds)
                         (/ (double received) step-seconds)
                         (percentile-ms h 0.50)
                         (percentile-ms h 0.99)
                         (percentile-ms h 0.999)
                         (/ (double deferred) step-seconds)))
        (flush)))
    (doseq [{:keys [close!]} streams] (close!))
    (.await done 10 TimeUnit/SECONDS)
    (client/shutdown ch {:grace-ms 2000})
    (println "STREAM-DRIVER DONE")
    (System/exit 0)))
