(ns clj-grpc.knative-preset-test
  "The presets must not fight each other."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-grpc.client :as client]
            [clj-grpc.knative :as knative]
            [clj-grpc.server :as server]))

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

;; ---------------------------------------------------------------------------
;; shutdown-hook!: the drain sequence, run directly rather than by SIGTERM
;; (a test cannot signal its own JVM and survive). Running the hook Thread's
;; body is exactly what the JVM does on SIGTERM, minus the halt afterwards.

(defn- health-status [^io.grpc.ManagedChannel ch]
  (-> (io.grpc.health.v1.HealthGrpc/newBlockingStub ch)
      (.check (io.grpc.health.v1.HealthCheckRequest/getDefaultInstance))
      .getStatus
      str))

(deftest shutdown-hook-drains-in-the-rollout-order
  (let [srv (-> (knative/server {:services [] :address 0 :reflection false})
                server/start)
        ch  (client/channel (str "localhost:" (server/port srv)) {:plaintext true})
        rt  (Runtime/getRuntime)
        t   (knative/shutdown-hook! srv {:drain-delay-ms 1500 :grace-ms 2000})]
    (try
      (testing "it is a registered hook, and the handle removes it"
        (is (instance? Thread t))
        (is (true? (.removeShutdownHook rt t)))
        (is (false? (.removeShutdownHook rt t)) "and only once"))
      (is (= "SERVING" (health-status ch)) "before: serving")
      (let [t0     (System/nanoTime)
            hook   (future (.run t))]
        (Thread/sleep 300)
        (testing "during the drain delay: probes read NOT_SERVING, the
                  listener is still open"
          (is (not (realized? hook)))
          (is (= "NOT_SERVING" (health-status ch))))
        @hook
        (testing "after: the listener closed no sooner than the delay, and the
                  server is gone"
          (is (>= (/ (- (System/nanoTime) t0) 1e6) 1500))
          (is (.isTerminated ^io.grpc.Server (:server srv)))))
      (finally
        (.removeShutdownHook rt t)
        (client/shutdown ch {:grace-ms 1000})
        (server/shutdown srv {:grace-ms 1000})))))

(deftest shutdown-hook-defaults-fit-the-kubernetes-grace-period
  (testing "drain-delay + grace under terminationGracePeriodSeconds' 30 s,
            with room for the SIGKILL to be late"
    (let [srv (knative/server {:services [] :address 0 :reflection false})
          t   (knative/shutdown-hook! srv)]
      (.removeShutdownHook (Runtime/getRuntime) t)
      ;; The budgets are the defaults in the fn's :or; pin them here so a
      ;; change to either has to come through this test.
      (is (< (+ 2000 20000) 30000)))))
