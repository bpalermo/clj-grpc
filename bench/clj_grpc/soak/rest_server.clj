(ns clj-grpc.soak.rest-server
  "The REST comparison arm as a standalone server — the same Pedestal/Jetty/
  jsonista stack and echo semantics as the in-process arm in clj-grpc.bench,
  shaped for a container: $PORT (default 8080), a GET /healthz probe, and a
  :gen-class main.

      POST /hello {\"name\": \"world\"} -> {\"message\": \"Hello world\"}

  The soak harness (soak/) runs this against the gRPC arms under identical
  load; keeping the handler byte-for-byte equivalent to the benchmark's is
  what makes that comparison mean anything."
  (:require [io.pedestal.http :as phttp]
            [jsonista.core :as j])
  (:gen-class))

(def ^:private mapper (j/object-mapper {:decode-key-fn keyword}))

(defn- hello-handler [request]
  (let [{:keys [name]} (j/read-value (slurp (:body request)) mapper)]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (j/write-value-as-string {:message (str "Hello " name)})}))

(defn- healthz [_]
  {:status 200 :headers {"Content-Type" "text/plain"} :body "ok"})

(def ^:private routes
  #{["/hello" :post hello-handler :route-name :hello]
    ["/healthz" :get healthz :route-name :healthz]})

(defn start
  "Start the server; returns the started Pedestal service map."
  [port]
  (-> {::phttp/routes routes
       ::phttp/type :jetty
       ::phttp/host "0.0.0.0"
       ::phttp/port port
       ::phttp/join? false}
      phttp/create-server
      phttp/start))

(defn -main [& _]
  (let [port (or (some-> (System/getenv "PORT") Long/parseLong) 8080)]
    (start port)
    (println (str "rest server listening on port " port))
    @(promise)))
