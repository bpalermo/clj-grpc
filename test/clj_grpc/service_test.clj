(ns clj-grpc.service-test
  "The service contract against the generated greeter fixture: shapes, types,
  and — with protoc's Java classes on this classpath — the pool-alignment
  property the marshallers depend on."
  (:require [acme.greeter.greeter :as g]
            [clojure.test :refer [deftest is testing]]
            [clj-grpc.service :as service])
  (:import [com.acme.greeter HelloRequest]
           [io.grpc MethodDescriptor$MethodType]))

(deftest service-shape
  (is (= "Greeter" (:name g/Greeter)))
  (is (= "acme.greeter.Greeter" (:full-name g/Greeter)))
  (is (= 4 (count (:methods g/Greeter)))))

(deftest methods-map-shape
  (is (= #{:say-hello :say-hello-many :collect-hellos :chat}
         (set (keys g/greeter-methods))))
  (is (= {:say-hello :unary
          :say-hello-many :server-streaming
          :collect-hellos :client-streaming
          :chat :bidi}
         (update-vals g/greeter-methods :type))))

(deftest grpc-method-descriptors
  (let [md @(:method-descriptor (:say-hello-many g/greeter-methods))]
    (is (= "acme.greeter.Greeter/SayHelloMany" (.getFullMethodName md)))
    (is (= MethodDescriptor$MethodType/SERVER_STREAMING (.getType md)))))

(deftest prototypes-align-with-the-namespace-pool
  (testing "the greeter file has no java_multiple_files but IS edition 2024, so
            protoc nests nothing: with the generated classes present, both the
            namespace's prototypes and the service's method prototypes must
            resolve to them — same pool, or parsed requests crash proto->X"
    (is (identical? (class g/HelloRequest-prototype)
                    (class (:input-prototype (:say-hello g/greeter-methods)))))
    (is (instance? HelloRequest
                   (:input-prototype (:say-hello g/greeter-methods))))))

(deftest unknown-service-throws
  (is (= :no-such-service
         (try (service/service g/file-descriptor "Nope")
              (catch clojure.lang.ExceptionInfo e
                (:clj-grpc/error (ex-data e)))))))
