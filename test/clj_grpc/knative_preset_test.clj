(ns clj-grpc.knative-preset-test
  "The presets must not fight each other."
  (:require [clojure.test :refer [deftest is]]
            [clj-grpc.knative :as knative]))

(deftest keepalive-pairing
  (let [server-permit (get-in (knative/server-opts) [:permit-keepalive :time-ms])
        client-ping   (get-in (knative/channel-opts) [:keepalive :time-ms])]
    (is (some? server-permit))
    (is (<= server-permit client-ping)
        "a server preset permitting less than the client preset pings is the
         GOAWAY too_many_pings bug this test exists to prevent")))

(deftest overrides-win
  (is (= 9999 (get-in (knative/server-opts {:permit-keepalive {:time-ms 9999}})
                      [:permit-keepalive :time-ms])))
  (is (false? (:reflection (knative/server-opts {:reflection false})))))
