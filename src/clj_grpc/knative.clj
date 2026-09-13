(ns clj-grpc.knative
  "Presets, not machinery: the opts maps that make a gRPC service behave well
  on Knative, to merge with your own.

  Serving: Knative speaks h2c to the container — name the port `h2c` in the
  Service spec or the gateway will treat the traffic as HTTP/1.1:

      ports:
        - name: h2c
          containerPort: 8080

  The container port comes from $PORT (server-opts defaults to it), the health
  service answers the queue-proxy's probes, and reflection makes grpcurl
  debugging possible in a cluster.

  Calling: a scaled-to-zero revision puts the activator in the request path.
  wait-for-ready keeps the first call from failing while the pod comes up,
  and keepalives hold the connection through idle proxies.

  Probes and shutdown, and the liveness trap — readiness on the status the app
  declares, liveness on a name it never flips, and the budget rule holding
  both under terminationGracePeriodSeconds: docs/kubernetes.md."
  (:require [clj-grpc.client :as client]
            [clj-grpc.server :as server]))

(defn server-opts
  "h2c on $PORT with health and reflection on. Merge overrides last."
  ([] (server-opts nil))
  ([overrides]
   (merge {:health true
           :reflection true
           ;; Paired with channel-opts' 30s client pings: gRPC's DEFAULT
           ;; permit is 5 minutes, and a client pinging faster than the server
           ;; permits gets GOAWAY too_many_pings. Presets that fight each
           ;; other are worse than no presets.
           :permit-keepalive {:time-ms 30000 :without-calls true}}
          overrides)))

(defn channel-opts
  "The activator-in-path client posture: plaintext h2c, wait-for-ready,
  keepalives that survive idle proxies, and a retry policy for the connection
  churn scale-from-zero implies."
  ([] (channel-opts nil))
  ([overrides]
   (merge {:plaintext true
           :keepalive {:time-ms 30000 :timeout-ms 10000 :without-calls false}}
          overrides)))

(defn server
  "clj-grpc.server/server with the Knative preset applied."
  [opts]
  (server/server (server-opts opts)))

(defn shutdown-hook!
  "Drain on SIGTERM the way a rollout expects: install a JVM shutdown hook
  that runs clj-grpc.server/shutdown on `srv` — health NOT_SERVING at once,
  the listener closed after :drain-delay-ms, in-flight calls given :grace-ms,
  then forced. Returns the hook Thread; remove it with
  (.removeShutdownHook (Runtime/getRuntime) t). A hook is process-global and
  lives until exit, so anything that starts servers repeatedly — a test suite,
  a REPL — wants that handle.

  The two budgets, and the arithmetic Kubernetes holds them to:

    :drain-delay-ms  2000  wait before closing the listener. SIGTERM and the
                           pod's removal from its endpoints are concurrent,
                           so it can be handed new connections for a moment
                           after being told to stop; closing at once refuses
                           them. Probes read NOT_SERVING throughout.
    :grace-ms       20000  how long in-flight calls get after that.

  drain-delay-ms + grace-ms must stay under the pod's
  terminationGracePeriodSeconds (Kubernetes default 30 s) with room to spare,
  or SIGKILL arrives mid-drain and cuts exactly what this hook exists to
  finish. The defaults leave 8 s.

  In a GraalVM native image, hooks run on SIGTERM only with
  --install-exit-handlers, which this library's jar-shipped native-image
  config sets: without it the process just dies, looking from outside exactly
  like a server that drained. CI asserts the difference against the example
  binary.

  USE THIS WHEN THE SERVER IS THE ONLY THING THAT NEEDS DRAINING. If anything
  else does — a connection pool, an executor, a second server — write one hook
  and call clj-grpc.server/shutdown inside it, in order. JVM shutdown hooks
  start in unspecified order and run concurrently, so two hooks are a race,
  not a sequence: the pool closes under in-flight calls, intermittently,
  under load."
  ^Thread
  ([srv] (shutdown-hook! srv nil))
  ([srv {:keys [grace-ms drain-delay-ms]
         :or {grace-ms 20000 drain-delay-ms 2000}}]
   (let [t (Thread. ^Runnable
                    (fn []
                      (server/shutdown srv {:grace-ms grace-ms
                                            :drain-delay-ms drain-delay-ms}))
                    "clj-grpc-shutdown")]
     (.addShutdownHook (Runtime/getRuntime) t)
     t)))

(defn channel
  "clj-grpc.client/channel with the Knative preset applied."
  [target opts]
  (client/channel target (channel-opts opts)))
