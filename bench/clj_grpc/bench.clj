(ns clj-grpc.bench
  "The RPC benchmark clj-protobuf's serialization benchmark cannot be: full
  round trips over real sockets, gRPC against REST.

  Arms, identical echo semantics (request carries a name, response says hello
  to it), both on loopback with persistent connections:

    :grpc  clj-grpc — generated records through clj-protobuf, unary call over
           h2c on non-shaded Netty
    :rest  Pedestal 0.8 on Jetty, jsonista on both sides, JDK HttpClient —
           the ordinary Clojure REST stack

  Payload sizes stress different costs: :small is framing-dominated, :large is
  bytes-dominated. Latency only — per-thread allocation counters are
  meaningless across an RPC's thread hops.

  bazel run //bench:run          ; full criterium
  bazel run //bench:run -- quick ; quick-bench
  bazel run //bench:run -- load  ; 32 concurrent callers, throughput mode —
                                 ; the lens that ranks executor choices"
  (:require [acme.greeter.greeter :as g]
            [clj-grpc.client :as client]
            [clj-grpc.server :as server]
            [criterium.core :as crit]
            [io.pedestal.http :as phttp]
            [jsonista.core :as j])
  (:import [java.net ServerSocket URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def payloads
  {:small  "world"
   :medium (apply str (repeat 1024 "m"))
   :large  (apply str (repeat 65536 "L"))})

;; ---------------------------------------------------------------------------
;; gRPC arm

(defn- start-grpc []
  (let [srv (-> (server/server
                 {:services [{:service g/Greeter
                              :handlers {:say-hello
                                         (fn [req]
                                           (g/HelloReply->proto
                                            {:message (str "Hello " (:name (g/proto->HelloRequest req)))}))}}]
                  :address 0
                  :health false})
                server/start)
        ch (client/channel (str "localhost:" (server/port srv)) {:plaintext true})
        calls (client/client ch g/greeter-methods {:deadline-ms 30000})]
    {:server srv :channel ch
     :call (fn [name]
             (-> ((:say-hello calls) (g/HelloRequest->proto {:name name}))
                 g/proto->HelloReply
                 :message))}))

(defn- stop-grpc [{:keys [server channel]}]
  (client/shutdown channel {:grace-ms 2000})
  (server/shutdown server {:grace-ms 2000}))

;; ---------------------------------------------------------------------------
;; REST arm

(def ^:private mapper (j/object-mapper {:decode-key-fn keyword}))

(defn- free-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- rest-handler [request]
  (let [{:keys [name]} (j/read-value (slurp (:body request)) mapper)]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (j/write-value-as-string {:message (str "Hello " name)})}))

(defn- start-rest []
  (let [port (free-port)
        srv (-> {::phttp/routes #{["/hello" :post rest-handler :route-name :hello]}
                 ::phttp/type :jetty
                 ::phttp/port port
                 ::phttp/join? false}
                phttp/create-server
                phttp/start)
        http-client (HttpClient/newHttpClient)
        uri (URI/create (str "http://localhost:" port "/hello"))]
    {:server srv
     :call (fn [name]
             (let [body (j/write-value-as-string {:name name})
                   req  (-> (HttpRequest/newBuilder uri)
                            (.header "Content-Type" "application/json")
                            (.POST (HttpRequest$BodyPublishers/ofString body))
                            (.build))
                   resp (.send http-client req (HttpResponse$BodyHandlers/ofString))]
               (:message (j/read-value ^String (.body resp) mapper))))}))

(defn- stop-rest [{:keys [server]}]
  (phttp/stop server))

;; ---------------------------------------------------------------------------

(defn- start-grpc-with [server-opts]
  (let [srv (-> (server/server
                 (merge {:services [{:service g/Greeter
                                     :handlers {:say-hello
                                                (fn [req]
                                                  (g/HelloReply->proto
                                                   {:message (str "Hello " (:name (g/proto->HelloRequest req)))}))}}]
                         :address 0
                         :health false}
                        server-opts))
                server/start)
        ch (client/channel (str "localhost:" (server/port srv)) {:plaintext true})
        calls (client/client ch g/greeter-methods {:deadline-ms 60000})]
    {:server srv :channel ch :call (:say-hello calls)}))

(defn- throughput
  "calls/second across `threads` platform threads hammering one channel."
  [call threads per-thread]
  (let [req (g/HelloRequest->proto {:name "load"})
        _ (dotimes [_ 2000] (call req))          ; warm
        t0 (System/nanoTime)
        workers (mapv (fn [_]
                        (.start (Thread/ofPlatform)
                                (fn [] (dotimes [_ per-thread] (call req)))))
                      (range threads))]
    (run! #(.join ^Thread %) workers)
    (let [secs (/ (double (- (System/nanoTime) t0)) 1e9)]
      (long (/ (* threads per-thread) secs)))))

(defn- run-load []
  (println "load mode: 32 platform threads x 3000 unary calls, one shared channel\n")
  (println "| server executor | calls/sec |")
  (println "|---|---|")
  (doseq [[label opts] [["virtual threads (default)" {}]
                        ["direct (event loop)" {:executor :direct}]]]
    (let [{:keys [server channel call]} (start-grpc-with opts)]
      (try
        (println (format "| %s | %,d |" label (throughput call 32 3000)))
        (finally
          (client/shutdown channel {:grace-ms 2000})
          (server/shutdown server {:grace-ms 2000}))))))

(def ^:private quick? (atom false))

(defn- mean-us [f]
  (let [result (if @quick? (crit/quick-benchmark* f {}) (crit/benchmark* f {}))]
    (/ (* 1e9 (double (first (:mean result)))) 1000.0)))

(defn -main [& args]
  (when (some #{"load"} args)
    (run-load)
    (System/exit 0))
  (when (some #{"quick"} args) (reset! quick? true))
  (println "clj-grpc RPC benchmark: gRPC (h2c, non-shaded Netty) vs REST (Pedestal/Jetty + JSON)")
  (println "loopback, persistent connections, sequential; mean round-trip latency.")
  (let [grpc (start-grpc)
        rest (start-rest)]
    (try
      ;; Sanity: identical answers before identical measurements.
      (assert (= ((:call grpc) "check") ((:call rest) "check")))
      (let [results (vec (for [[size payload] payloads]
                           (do (println " measuring" (name size))
                               {:size size
                                :grpc (mean-us #((:call grpc) payload))
                                :rest (mean-us #((:call rest) payload))})))]
        (println "\n| payload | gRPC (clj-grpc) | REST (Pedestal+JSON) |")
        (println "|---|---|---|")
        (doseq [{:keys [size grpc rest]} results]
          (println (format "| %s | %.1f µs | %.1f µs |" (name size) grpc rest)))
        (println "\nEDN:" (pr-str results)))
      (finally
        (stop-grpc grpc)
        (stop-rest rest)))))
