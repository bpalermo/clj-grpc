(ns clj-grpc.service-codec-test
  "The non-hinted arm of prototype resolution. No protoc Java classes are on
  this classpath, so the fixture's class hints resolve to nothing and both
  the namespace's prototypes and the service's method prototypes must come
  from the runtime's fallback — clj-protobuf's compiled codec, or
  DynamicMessage when -Dclj-protobuf.codec=dynamic asks for it. Either way
  they must be the SAME arm over the SAME pool, or inbound parsing lands on
  a message the generated proto->X fns cannot read.

  One namespace, two Bazel targets: the property is read once at namespace
  load, so each kill-switch position is its own JVM (jvm_flags on the
  target). The plain-clj leg runs the compiled position."
  (:require [acme.greeter.greeter :as g]
            [clojure.test :refer [deftest is testing]]
            [clj-protobuf.runtime :as rt])
  (:import [com.google.protobuf DynamicMessage]
           [io.grpc MethodDescriptor$Marshaller]))

(def ^:private dynamic?
  (= "dynamic" (System/getProperty "clj-protobuf.codec")))

(def ^:private position (if dynamic? "dynamic" "compiled"))

(defn- say-hello [] (:say-hello g/greeter-methods))

(deftest no-generated-classes-on-this-classpath
  (is (thrown? ClassNotFoundException
               (Class/forName "com.acme.greeter.HelloRequest"))
      "this target must run without //test/proto:greeter_java_proto; with the
       classes present the hinted arm wins and the codec setting is moot"))

(deftest prototypes-take-the-runtime's-arm
  (let [in  (:input-prototype (say-hello))
        out (:output-prototype (say-hello))
        ;; The reference arms, through the public API, whatever the setting.
        expected (if dynamic?
                   (rt/dynamic-message g/file-descriptor "HelloRequest")
                   (rt/compiled-message g/file-descriptor "HelloRequest"))]
    (testing (str "position: " position)
      (is (identical? (class expected) (class in)))
      (is (= dynamic? (instance? DynamicMessage in)))
      (is (= dynamic? (instance? DynamicMessage out)))
      (is (identical? (class g/HelloRequest-prototype) (class in))
          "the same arm the namespace's own prototypes took")
      (is (identical? (.getDescriptorForType g/HelloRequest-prototype)
                      (.getDescriptorForType in))
          "the same Descriptor instance: same pool"))))

(deftest inbound-parsing-lands-on-the-runtime's-arm
  (let [md @(:method-descriptor (say-hello))
        ^MethodDescriptor$Marshaller m (.getRequestMarshaller md)
        req    (g/HelloRequest->proto {:name "codec" :repeat-count 2})
        parsed (.parse m (.stream m req))]
    (testing (str "position: " position)
      (is (identical? (class req) (class parsed))
          "what the marshaller parses is what ->proto built")
      (is (= dynamic? (instance? DynamicMessage parsed)))
      (is (= {:name "codec" :repeat-count 2}
             (select-keys (g/proto->HelloRequest parsed) [:name :repeat-count]))
          "and the generated proto->X reads it"))))
