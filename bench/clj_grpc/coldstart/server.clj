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
                                         (let [{:keys [name payload]} (g/proto->HelloRequest req)]
                                           ;; The realistic tier's nested payload is
                                           ;; echoed back, so both protocols pay the same
                                           ;; decode+encode per request; the tiny tier
                                           ;; has none and the reply carries none.
                                           (g/HelloReply->proto
                                            (cond-> {:message (str "Hello " name)}
                                              (some? payload) (assoc :payload payload)))))
                                       ;; Bidi echo for the streaming capacity
                                       ;; arm: per-message cost with the
                                       ;; per-call machinery amortized away.
                                       :chat
                                       (fn [send! close!]
                                         {:on-next (fn [req]
                                                     (let [{:keys [name payload]} (g/proto->HelloRequest req)]
                                                       (send! (g/HelloReply->proto
                                                               (cond-> {:message name}
                                                                 (some? payload) (assoc :payload payload))))))
                                          :on-complete close!})}}]}
         (System/getenv "UDS") (assoc :address {:unix (System/getenv "UDS")})
         (= "direct" (System/getenv "EXECUTOR")) (assoc :executor :direct)))
      server/start
      server/await-termination))
