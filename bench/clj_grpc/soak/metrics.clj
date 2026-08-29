(ns clj-grpc.soak.metrics
  "A tiny Prometheus-text /metrics endpoint for the soak servers — heap and
  uptime, nothing else, from JDK built-ins only (com.sun.net.httpserver +
  Runtime), so it costs no dependency and works identically on the JVM and in
  a GraalVM native image (where the same counters report the Serial GC heap).

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
         "process_uptime_seconds " (/ (- (System/nanoTime) (or @start-nanos (System/nanoTime))) 1e9) "\n")))

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
