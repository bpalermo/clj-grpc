(ns clj-grpc.soak.metrics
  "A tiny Prometheus-text /metrics endpoint for the soak servers — heap,
  uptime, and the container's own cgroup CPU/memory — from JDK built-ins only
  (com.sun.net.httpserver + Runtime + plain file reads), so it costs no
  dependency and works identically on the JVM and in a GraalVM native image
  (where the same counters report the Serial GC heap).

  Started by the soak server mains on $METRICS_PORT (default 9090; set to
  \"off\" to disable). The soak harness scrapes it per window to put a real
  heap row next to the working-set numbers."
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(set! *warn-on-reflection* true)

;; nanoTime delta rather than RuntimeMXBean.getUptime: the MXBean path is the
;; kind of managed-runtime surface a native image supports only partially,
;; and this endpoint must work identically on both. Captured in start!, not at
;; namespace load — under native-image, namespace init runs at IMAGE BUILD,
;; and a build-time nanoTime baked into the heap makes uptime nonsense.
(defonce ^:private start-nanos (atom nil))

(defn- cgroup-file
  "The trimmed text of a cgroup v2 file, or nil when there is no such file —
  which is the case on a developer laptop, and must not be an error there."
  ^String [^String path]
  (let [f (java.io.File. path)]
    (when (.isFile f)
      (try (clojure.string/trim (slurp f)) (catch Throwable _ nil)))))

(defn- cpu-stat
  "`/sys/fs/cgroup/cpu.stat` as a map of keyword to long, or nil."
  []
  (when-let [text (cgroup-file "/sys/fs/cgroup/cpu.stat")]
    (into {}
          (keep (fn [line]
                  (let [[k v] (clojure.string/split line #"\s+")]
                    (when (and k v) [(keyword k) (Long/parseLong v)]))))
          (clojure.string/split-lines text))))

(defn- cgroup-gauges
  "CPU and memory of this container, from its own cgroup v2 files.

  Here because the cluster's Prometheus does not scrape cAdvisor: nothing
  outside the pod can say what a request cost in CPU. The load driver reads
  this endpoint before and after every ramp step, and the delta of
  `cgroup_cpu_usage_seconds_total` over the delivered count is the
  CPU-per-request that the switch ladder is measuring. `throttled` is the
  second saturation signal on a 1-CPU quota — a server can be delivering
  while already being held back. Best-effort: absent files (no cgroup v2, or
  not in a container) simply produce no lines."
  []
  (let [{:keys [usage_usec throttled_usec nr_throttled nr_periods]} (cpu-stat)
        memory (some-> (cgroup-file "/sys/fs/cgroup/memory.current") Long/parseLong)]
    (str (when usage_usec
           (str "# TYPE cgroup_cpu_usage_seconds_total counter\n"
                "cgroup_cpu_usage_seconds_total " (/ usage_usec 1e6) "\n"))
         (when throttled_usec
           (str "# TYPE cgroup_cpu_throttled_seconds_total counter\n"
                "cgroup_cpu_throttled_seconds_total " (/ throttled_usec 1e6) "\n"))
         (when nr_throttled
           (str "# TYPE cgroup_cpu_nr_throttled counter\n"
                "cgroup_cpu_nr_throttled " nr_throttled "\n"))
         (when nr_periods
           (str "# TYPE cgroup_cpu_nr_periods counter\n"
                "cgroup_cpu_nr_periods " nr_periods "\n"))
         (when memory
           (str "# TYPE cgroup_memory_current_bytes gauge\n"
                "cgroup_memory_current_bytes " memory "\n")))))

(defn- render []
  (let [rt (Runtime/getRuntime)
        total (.totalMemory rt)
        free (.freeMemory rt)]
    (str "# TYPE heap_used_bytes gauge\n"
         "heap_used_bytes " (- total free) "\n"
         "# TYPE heap_committed_bytes gauge\n"
         "heap_committed_bytes " total "\n"
         "# TYPE heap_max_bytes gauge\n"
         "heap_max_bytes " (.maxMemory rt) "\n"
         "# TYPE process_uptime_seconds gauge\n"
         "process_uptime_seconds " (/ (- (System/nanoTime) (or @start-nanos (System/nanoTime))) 1e9) "\n"
         (cgroup-gauges))))

(defn- handler ^HttpHandler []
  (reify HttpHandler
    (handle [_ exchange]
      (let [^HttpExchange ex exchange]
        (try
          (let [^String text (render)
                body (.getBytes text StandardCharsets/UTF_8)]
            (.set (.getResponseHeaders ex) "Content-Type" "text/plain; version=0.0.4")
            (.sendResponseHeaders ex 200 (alength body))
            (with-open [os (.getResponseBody ex)]
              (.write os body)))
          (catch Throwable t
            ;; Surface instead of letting httpserver eat it as an empty reply.
            (println (str "metrics handler failed: " t))
            (try (.sendResponseHeaders ex 500 -1) (catch Throwable _))
            (.close ex)))))))

(defn start!
  "Start the metrics server from $METRICS_PORT (default 9090, \"off\"
  disables). Daemon-threaded via the default executor; returns the server or
  nil. Never throws — a soak server must not die because its observability
  sidecar port is taken."
  []
  (let [setting (or (System/getenv "METRICS_PORT") "9090")]
    (reset! start-nanos (System/nanoTime))
    (when-not (= "off" setting)
      (try
        (let [port (Long/parseLong setting)
              server (HttpServer/create (InetSocketAddress. (int port)) 0)]
          (.createContext server "/metrics" (handler))
          (.start server)
          (println (str "metrics on port " port))
          server)
        (catch Exception e
          (println (str "metrics disabled: " (.getMessage e)))
          nil)))))
