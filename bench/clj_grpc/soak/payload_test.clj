(ns clj-grpc.soak.payload-test
  "The bodies the switch ladder feeds every arm carry the same information,
  and the realistic tier is the size it claims to be. If either stopped being
  true the campaign would be comparing two different workloads and calling
  the difference a protocol effect."
  (:require [acme.greeter.greeter :as g]
            [clj-grpc.soak.payload :as payload]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as j])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "payload" (make-array FileAttribute 0))))

(defn- snake->kebab
  "A JSON body read back with keyword keys, renamed the way the fixture's
  records name them, so the two sides can be compared as one map."
  [v]
  (cond
    (map? v) (into {} (map (fn [[k x]] [(keyword (.replace (name k) "_" "-")) (snake->kebab x)])) v)
    (sequential? v) (mapv snake->kebab v)
    :else v))

(defn- proto->map
  "A HelloRequest message decoded to a plain map with nils and empty
  collections dropped, so absent and unset compare equal."
  [^bytes pb]
  (letfn [(scrub [v]
            (cond
              (map? v) (into {} (keep (fn [[k x]]
                                        (let [x (scrub x)]
                                          (when-not (or (nil? x) (and (coll? x) (empty? x)))
                                            [k x]))))
                             v)
              (sequential? v) (mapv scrub v)
              :else v))]
    (scrub (into {} (g/proto->HelloRequest
                     (-> ^com.google.protobuf.Message g/HelloRequest-prototype
                         .newBuilderForType
                         (.mergeFrom pb)
                         .build))))))

(deftest every-tier-says-the-same-thing-in-json-and-protobuf
  (let [dir (temp-dir)]
    (payload/write! dir 1024)
    (doseq [tier ["tiny" "realistic"]]
      (testing tier
        (let [json (snake->kebab (j/read-value (io/file dir (str tier ".json"))
                                               (j/object-mapper {:decode-key-fn keyword})))
              proto (proto->map (Files/readAllBytes (.toPath (io/file dir (str tier ".pb")))))]
          ;; The proto side decodes with the fixture's enum/int defaults
          ;; scrubbed; the JSON side never had them. What remains must match
          ;; exactly, nested items included.
          (is (= (dissoc json :repeat-count :greeting)
                 (dissoc proto :repeat-count :greeting))
              (str tier ": JSON and protobuf bodies carry different information")))))))

(deftest the-tiny-tier-is-the-august-body
  (testing "byte-identical to what the August campaign sent, so the new numbers
            can be checked against the old ones"
    (let [dir (temp-dir)]
      (payload/write! dir 1024)
      (is (= "{\"name\":\"world\"}" (slurp (io/file dir "tiny.json")))))))

(deftest the-realistic-tier-is-the-size-it-claims
  (testing "the protobuf encoding lands on the target, within the few bytes a
            varint boundary can move it"
    (doseq [target [512 1024 4096]]
      (let [pb (.toByteArray ^com.google.protobuf.Message
                             (g/HelloRequest->proto (payload/realistic target)))
            size (alength pb)]
        (is (<= target size (+ target 16)) (str "target " target " gave " size " bytes"))))))

(deftest sizes-are-reported-for-the-record
  (let [dir (temp-dir)
        text (payload/write! dir 1024)]
    (is (re-find #"(?m)^tiny\s+json=\d+\s+pb=\d+" text))
    (is (re-find #"(?m)^realistic\s+json=\d+\s+pb=\d+" text))
    (is (= text (slurp (io/file dir "SIZES.txt"))))))
