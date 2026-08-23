(ns clj-grpc.coldstart.server
  "The measured subject: a minimal greeter server in the Knative posture,
  started the way a container starts it — main, $PORT, health on."
  (:require [acme.greeter.greeter :as g]
            [clj-grpc.server :as server])
  (:gen-class))

(defn -main [& _]
  (-> (server/server
       {:services [{:service g/Greeter
                    :handlers {:say-hello
                               (fn [req]
                                 (g/HelloReply->proto
                                  {:message (str "Hello " (:name (g/proto->HelloRequest req)))}))}}]})
      server/start
      server/await-termination))
