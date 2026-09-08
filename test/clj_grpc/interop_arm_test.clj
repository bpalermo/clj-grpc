(ns clj-grpc.interop-arm-test
  "What the typed arm actually decodes, asserted rather than assumed.

  protoc-gen-clojure 0.6.0 types the read path, but guards it:

      (if (and (nil? opts) (instance? com.acme.greeter.HelloRequest msg))
        ...typed getters...
        ...codec/get-field...)

  so a prototype that is anything else — DynamicMessage, or clj-protobuf's
  compiled message — decodes through the codec instead. That does not fail.
  It quietly measures the very thing this arm exists to replace, and a soak
  run against it would report a null result that looks like a finding.

  The prototype comes from clj-grpc.service, which asks clj-protobuf's
  runtime for it, which picks the generated class only when that class is on
  the classpath. //test:fixtures_interop depends on //test/proto:greeter_java_proto
  for exactly that reason. This test fails if that ever stops being true.

  Only meaningful on the interop fixture, so it lives in a target compiled
  against it alone."
  (:require [acme.greeter.greeter :as g]
            [clj-protobuf.runtime :as rt]
            [clojure.test :refer [deftest is testing]])
  (:import [com.acme.greeter HelloReply HelloRequest]
           [com.google.protobuf DynamicMessage Message]
           [io.grpc MethodDescriptor$Marshaller]))

(deftest prototypes-are-the-generated-classes
  (testing "both directions: the arm's whole premise"
    (is (instance? HelloRequest (:input-prototype (:say-hello g/greeter-methods))))
    (is (instance? HelloReply (:output-prototype (:say-hello g/greeter-methods))))))

(deftest what-the-marshaller-hands-a-handler-takes-the-typed-path
  (let [md (deref (:method-descriptor (:say-hello g/greeter-methods)))
        ^MethodDescriptor$Marshaller m (.getRequestMarshaller md)
        req (g/HelloRequest->proto {:name "typed" :repeat-count 2})
        parsed (.parse m (.stream m req))]
    (testing "a request off the wire is the generated class, so proto->X takes
              its typed branch rather than falling back to the codec"
      (is (instance? HelloRequest parsed))
      (is (not (instance? DynamicMessage parsed))))
    (testing "and reads correctly through it"
      (is (= {:name "typed" :repeat-count 2}
             (select-keys (g/proto->HelloRequest parsed) [:name :repeat-count]))))))

(deftest a-foreign-message-throws-rather-than-decoding-quietly
  (testing "the feared failure mode cannot happen here, and this is why.

            The typed branch is guarded by instance?, so one might expect a
            foreign message to fall through to codec/get-field and be decoded
            slowly but correctly — silently measuring the codec this arm
            exists to replace. It does not: on a hinted fixture the field
            handles are specialized to the generated class, so the else
            branch casts too and throws. Between this and the prototype
            assertion above, a mixed-prototype setup fails loudly instead of
            producing a null result that looks like a finding.

            This pins a documented contract, not an accident. clj-protobuf's
            design.md, \"Descriptor pools: never mix them\", says generated fns
            read and write their own arm's messages and no other: a handle's
            FieldDescriptor belongs to its prototype's pool, and on a hinted
            namespace it also carries invokers over that concrete class, so
            the instance? guard picks between the typed and codec paths
            without making the fn polymorphic across arms. Crossing arms is
            invalid and throwing is the intended answer.

            The alternative was considered and rejected there: accepting a
            foreign message would mean re-resolving each field against that
            message's descriptor per call, on the hot path, to serve a case
            the design forbids — and it would restore exactly the
            silent-measurement risk above. So the risk is closed by design
            rather than by this arm's configuration."
    (let [req (g/HelloRequest->proto {:name "dynamic" :repeat-count 7})
          proto ^Message (rt/dynamic-message g/file-descriptor "HelloRequest")
          dyn (.parseFrom (.getParserForType proto) (.toByteArray ^Message req))]
      (is (instance? DynamicMessage dyn))
      (is (thrown? ClassCastException (g/proto->HelloRequest dyn))))))
