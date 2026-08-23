(ns clj-grpc.health
  "The health service, as functions. The server builds and registers a
  HealthStatusManager by default; these set what it reports — which is what
  Kubernetes readiness and Knative probes read.

      (health/set-status! srv :serving)                 ; the overall service
      (health/set-status! srv \"acme.greeter.Greeter\" :not-serving)

  clj-grpc.server/shutdown already enters the terminal NOT_SERVING state
  before closing the listener, so rollout drain needs no code here."
  (:import [io.grpc.health.v1 HealthCheckResponse$ServingStatus]
           [io.grpc.protobuf.services HealthStatusManager]))

(set! *warn-on-reflection* true)

(def ^:private statuses
  {:serving         HealthCheckResponse$ServingStatus/SERVING
   :not-serving     HealthCheckResponse$ServingStatus/NOT_SERVING
   :unknown         HealthCheckResponse$ServingStatus/UNKNOWN
   :service-unknown HealthCheckResponse$ServingStatus/SERVICE_UNKNOWN})

(defn- manager ^HealthStatusManager [server]
  (or (:health server)
      (throw (ex-info "server was built with :health false"
                      {:clj-grpc/error :no-health-service}))))

(defn set-status!
  "Set the reported status for one service, or — 2-arity — for the overall
  server (the empty service name, which is what probes usually ask about).
  status: :serving | :not-serving | :unknown | :service-unknown."
  ([server status] (set-status! server "" status))
  ([server ^String service-name status]
   (let [s (or (statuses status)
               (throw (ex-info (str "unknown status " status)
                               {:clj-grpc/error :bad-status
                                :status status
                                :valid (keys statuses)})))]
     (.setStatus (manager server) service-name s)
     server)))

(defn clear-status!
  "Stop reporting a service; checks for it answer SERVICE_UNKNOWN."
  [server ^String service-name]
  (.clearStatus (manager server) service-name)
  server)
