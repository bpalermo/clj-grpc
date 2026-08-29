(ns clj-grpc.coldstart.server
  "The measured subject: a minimal greeter server in the Knative posture,
  started the way a container starts it — main, $PORT, health on. Set $UDS to
  a socket path to bind a Unix domain socket instead — epoll-only, which makes
  this binary double as the proof that the native image carries a working
  epoll JNI transport. Set $EXECUTOR=direct to run handlers on the Netty
  event loop instead of virtual threads — the soak's tail-latency
  confirmation arm (safe here because the echo handler provably never
  blocks)."
  (:require [acme.greeter.greeter :as g]
            [clj-grpc.server :as server])
  (:gen-class))

(defn -main [& _]
  (-> (server/server
       (cond-> {:services [{:service g/Greeter
                            :handlers {:say-hello
                                       (fn [req]
                                         (g/HelloReply->proto
                                          {:message (str "Hello " (:name (g/proto->HelloRequest req)))}))}}]}
         (System/getenv "UDS") (assoc :address {:unix (System/getenv "UDS")})
         (= "direct" (System/getenv "EXECUTOR")) (assoc :executor :direct)))
      server/start
      server/await-termination))
