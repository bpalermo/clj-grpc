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
            [clj-grpc.server :as server]
            [clj-grpc.soak.metrics :as metrics])
  (:gen-class))

(defn -main [& _]
  (metrics/start!)
  (-> (server/server
       (cond-> {:services [{:service g/Greeter
                            :handlers {:say-hello
                                       (fn [req]
                                         (g/HelloReply->proto
                                          {:message (str "Hello " (:name (g/proto->HelloRequest req)))}))
                                       ;; Bidi echo for the streaming capacity
                                       ;; arm: per-message cost with the
                                       ;; per-call machinery amortized away.
                                       :chat
                                       (fn [send! close!]
                                         {:on-next (fn [req]
                                                     (send! (g/HelloReply->proto
                                                             {:message (:name (g/proto->HelloRequest req))})))
                                          :on-complete close!})}}]}
         (System/getenv "UDS") (assoc :address {:unix (System/getenv "UDS")})
         (= "direct" (System/getenv "EXECUTOR")) (assoc :executor :direct)))
      server/start
      server/await-termination))
