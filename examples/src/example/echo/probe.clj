(ns example.echo.probe
  "Watch a server's health from the outside — the client half of what
  clj-grpc.health sets, and the observer for a graceful shutdown.

      bazel run //examples:probe -- localhost:8080

  Opens the health service's Watch stream and prints every status it reports,
  then exits when the server goes NOT_SERVING or the stream ends. Against a
  server draining under clj-grpc.knative/shutdown-hook! that is the order a
  rollout sees: SERVING, then NOT_SERVING the moment SIGTERM lands and before
  the listener closes. CI's native-image job asserts exactly that against the
  GraalVM binary, because a shutdown hook a native image did not run would
  look, from the outside, like a server that was simply killed."
  (:require [clj-grpc.client :as client])
  (:import [io.grpc.health.v1 HealthCheckRequest HealthCheckResponse HealthGrpc]))

(defn -main [& [target]]
  (let [target (or target "localhost:8080")
        ch     (client/channel target {:plaintext true})
        stub   (HealthGrpc/newBlockingStub ch)]
    (try
      (let [responses (.watch stub (HealthCheckRequest/getDefaultInstance))]
        (loop []
          (when (.hasNext responses)
            (let [status (str (.getStatus ^HealthCheckResponse (.next responses)))]
              (println "health ->" status)
              (flush)
              (when (not= status "NOT_SERVING")
                (recur))))))
      (catch io.grpc.StatusRuntimeException e
        (println "stream ended:" (str (.getStatus e)))
        (flush))
      (finally
        (client/shutdown ch {:grace-ms 1000})))))
