(ns clj-grpc.netty-alignment-test
  "Non-shaded grpc-netty demands hand-aligned Netty versions (grpc-java
  SECURITY.md is the pairing authority). Every netty artifact on the classpath
  must report the single pinned version; a grpc bump that forgets the deps.edn
  pin block fails here, in CI, instead of as a runtime linkage error in
  production."
  (:require [clojure.test :refer [deftest is testing]])
  (:import [io.netty.util Version]))

(def expected-netty-version "4.2.16.Final")

(deftest every-netty-artifact-reports-the-pinned-version
  (let [versions (Version/identify)]
    (is (pos? (count versions)) "netty artifacts identified")
    (doseq [[artifact ^Version v] versions]
      (testing artifact
        (is (= expected-netty-version (.artifactVersion v)))))))

(deftest grpc-netty-links
  (testing "NettyChannelBuilder loads and constructs against this Netty —
            the class that breaks first when versions drift"
    (is (some? (io.grpc.netty.NettyChannelBuilder/forTarget "localhost:1")))))
