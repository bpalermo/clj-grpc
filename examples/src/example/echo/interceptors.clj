(ns example.echo.interceptors
  "Three interceptors over the Echo service: what a fn of the call looks like
  on each side, and the two things people write interceptors for — knowing
  who called, and turning callers away.

  Server side, in the :interceptors vector of `server` (or the Knative
  preset — example.echo.server wires `request-log` by default):

      :interceptors [request-log (require-token \"secret\")]

  Client side, on the channel or per call:

      (client/channel target {:interceptors [(bearer \"secret\")]})
      (client/invoke ch method req {:interceptors [(bearer \"secret\")]})

  Nothing here is machinery: each is a plain fn that receives the call map
  and `next`, and clj-grpc.interceptor's docstring is the whole contract."
  (:require [clj-grpc.context :as context]
            [clj-grpc.interceptor :refer [reject]]
            [clj-grpc.metadata :as metadata]))

(defn request-log
  "Server. Prints one line per application call — method and peer — and
  stamps every response with an x-echo-served-by header. Health probes pass
  through the same chain, so :service is how they are told apart."
  [{:keys [service method peer] :as call} next]
  (when-not (= "grpc.health.v1.Health" service)
    (println (str "call " method " from " peer)))
  (next (assoc call :response-headers {"x-echo-served-by" "example.echo"})))

(defn require-token
  "Server. An interceptor that admits only calls carrying
  `authorization: Bearer <token>`, and tells the handler who they are: the
  handler reads (:user (context/call)). Anything else is refused before any
  handler runs, with the status and trailer HTTP clients expect."
  [token]
  (let [expected (str "Bearer " token)]
    (fn [call next]
      (if (= expected (metadata/header (:headers call) "authorization"))
        (next (assoc call :user "token-holder"))
        (reject :unauthenticated "missing or bad token"
                {"www-authenticate" "Bearer"})))))

(defn bearer
  "Client. Declares the authorization header on every call it wraps, and —
  given a callback — hands it the x-echo-served-by response header when the
  server answers."
  ([token] (bearer token nil))
  ([token on-served-by]
   (fn [call next]
     (next (cond-> (update call :headers assoc "authorization" (str "Bearer " token))
             on-served-by (assoc :on-headers
                                 (fn [md] (on-served-by (metadata/header md "x-echo-served-by")))))))))

(defn whoami
  "What a handler sees: the user the interceptor attached, and the peer.
  Not an interceptor — the other half of the contract, for the docs."
  []
  {:user (:user (context/call)) :peer (str (context/peer))})
