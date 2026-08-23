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
  and keepalives hold the connection through idle proxies."
  (:require [clj-grpc.client :as client]
            [clj-grpc.server :as server]))

(defn server-opts
  "h2c on $PORT with health and reflection on. Merge overrides last."
  ([] (server-opts nil))
  ([overrides]
   (merge {:health true
           :reflection true}
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

(defn channel
  "clj-grpc.client/channel with the Knative preset applied."
  [target opts]
  (client/channel target (channel-opts opts)))
