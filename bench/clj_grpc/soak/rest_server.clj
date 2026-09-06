(ns clj-grpc.soak.rest-server
  "The REST comparison arm as a standalone server — the same Pedestal/Jetty/
  jsonista stack and echo semantics as the in-process arm in clj-grpc.bench,
  shaped for a container: $PORT (default 8080), a GET /healthz probe, and a
  :gen-class main.

      POST /hello {\"name\": \"world\"} -> {\"message\": \"Hello world\"}

  The soak harness (soak/) runs this against the gRPC arms under identical
  load; keeping the handler byte-for-byte equivalent to the benchmark's is
  what makes that comparison mean anything.

  Set $REST_EXECUTOR=virtual to dispatch request handling onto virtual
  threads (Jetty's I/O stays on platform threads) — the same
  I/O-thread-to-VT-handoff shape as the gRPC arms' default executor, so the
  soak can compare the two stacks under the SAME threading model and isolate
  handoff cost from protocol cost."
  (:require [clj-grpc.soak.metrics :as metrics]
            [io.pedestal.http :as phttp]
            [jsonista.core :as j])
  (:import [java.util.concurrent Executors]
           [org.eclipse.jetty.server Server]
           [org.eclipse.jetty.util.thread QueuedThreadPool])
  (:gen-class))

(def ^:private mapper (j/object-mapper {:decode-key-fn keyword}))

(defn- hello-handler
  "Decode the request, re-encode a reply. `payload` — the realistic tier's
  nested structure — is echoed back so both protocols pay the same decode
  and encode work per request; the tiny tier omits it and the reply omits it
  too, which keeps the tiny tier's bytes identical to the August campaign."
  [request]
  (let [{:keys [name payload]} (j/read-value (slurp (:body request)) mapper)]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (j/write-value-as-string
            (cond-> {:message (str "Hello " name)}
              (some? payload) (assoc :payload payload)))}))

(defn- healthz [_]
  {:status 200 :headers {"Content-Type" "text/plain"} :body "ok"})

(def ^:private routes
  #{["/hello" :post hello-handler :route-name :hello]
    ["/healthz" :get healthz :route-name :healthz]})

(defn- virtual-dispatch
  "Hand Jetty a virtual-threads executor: I/O and parsing stay on the pool's
  platform threads, application dispatch runs on a fresh virtual thread —
  the gRPC arms' default shape."
  [^Server server]
  (let [^QueuedThreadPool pool (.getThreadPool server)]
    (.setVirtualThreadsExecutor pool (Executors/newVirtualThreadPerTaskExecutor))
    server))

(defn- container-options
  "Jetty's transport and dispatch, from the environment.

  Pedestal's Jetty adapter serves HTTP/1.1 AND cleartext HTTP/2 (h2c, both the
  Upgrade path and prior-knowledge) on the same port by default. That default
  is exactly what the two REST arms of the switch ladder must NOT share: the
  HTTP/1.1 arm sets $H2C=off so it is provably h1-only, and the h2c arm
  states its stream budget with $H2C_MAX_STREAMS (Jetty's default is 128
  concurrent streams per connection, which is below what an open-loop client
  will try to open at the top of a ramp — an unstated cap turns into
  REFUSED_STREAM and gets scored as errors rather than as saturation).

  Built as one merged map rather than the conditional assoc it replaced: that
  shape could only ever hold ONE key, so asking for the virtual-thread
  executor silently dropped any transport setting, and vice versa."
  []
  (cond-> {:h2c? (not= "off" (System/getenv "H2C"))
           :max-streams (or (some-> (System/getenv "H2C_MAX_STREAMS") Long/parseLong) 128)}
    (= "virtual" (System/getenv "REST_EXECUTOR"))
    (assoc :configurator virtual-dispatch)))

(defn start
  "Start the server; returns the started Pedestal service map."
  [port]
  (let [options (container-options)]
    ;; Printed so a run's logs prove which transport the arm was really
    ;; serving; the k8s manifest says what was asked for, not what happened.
    (println (str "rest server transport: http/1.1"
                  (if (:h2c? options)
                    (str " + h2c (max-streams " (:max-streams options) ")")
                    " only")
                  (if (:configurator options) ", virtual-thread dispatch" ", platform-thread dispatch")))
    (-> {::phttp/routes routes
         ::phttp/type :jetty
         ::phttp/host "0.0.0.0"
         ::phttp/port port
         ::phttp/join? false
         ::phttp/container-options options}
        phttp/create-server
        phttp/start)))

(defn -main [& _]
  (let [port (or (some-> (System/getenv "PORT") Long/parseLong) 8080)]
    (metrics/start!)
    (start port)
    (println (str "rest server listening on port " port))
    @(promise)))
