(ns clj-grpc.soak.payload
  "The switch ladder's request bodies, written once so every arm is fed the
  same information.

  Two tiers. `tiny` is the August campaign's `{\"name\":\"world\"}` — kept
  byte-identical so the new numbers can be checked against the old ones.
  `realistic` carries a nested payload of strings, numbers and a repeated
  field, sized so its PROTOBUF encoding lands on a target byte count; the
  JSON encoding of the same value is whatever it is, and that difference is
  one of the things being measured, so it is reported rather than equalized.

  Each tier is written three ways: `<tier>.json` for the REST arms,
  `<tier>.pb` — the raw serialized HelloRequest, no gRPC frame, because the
  load generator adds the five-byte prefix itself — for the gRPC arms, and a
  line in SIZES.txt saying how big each came out, which goes verbatim into
  the results document so the wire-size ratio is on the record next to the
  throughput ratio.

  Usage (a build action; see //charts:bodies):
    bazel run //bench:payload_bodies -- OUT_DIR [TARGET_PB_BYTES]"
  (:require [acme.greeter.greeter :as g]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as j])
  (:gen-class))

(set! *warn-on-reflection* true)

(def ^:private items
  "Eight line items: enough that the repeated field is a real share of the
  message, few enough that strings still dominate, as they do in most APIs."
  (vec (for [i (range 8)]
         {:sku (format "SKU-%05d" (* 137 (inc i)))
          :qty (inc (mod (* 7 i) 5))
          :price (+ 9.99 (* 3.5 i))})))

(defn- realistic-with-body
  "The realistic tier with a `body` of `n` filler characters. Filler is
  ordinary ASCII prose-shaped text rather than a repeated byte, so neither
  codec gets an unrealistically compressible input."
  [n]
  (let [prose "the quick brown fox jumps over the lazy dog while the five boxing wizards jump quickly "
        body (subs (apply str (repeat (inc (quot n (count prose))) prose)) 0 n)]
    {:name "world"
     :payload {:id "ord_01J8ZK3V9Q6WQ0X5R2M7N4P8T1"
               :title "Order confirmation for a moderately complicated purchase"
               :body body
               :created-at 1756598400000
               :score 0.8731
               :items items}}))

(defn- pb-size
  "Bytes of the protobuf encoding of a HelloRequest map."
  ^long [m]
  (alength ^bytes (.toByteArray ^com.google.protobuf.Message (g/HelloRequest->proto m))))

(defn realistic
  "The realistic tier, with `body` sized so the protobuf encoding is as close
  to `target-bytes` as the fixed parts allow. Found by walking the filler
  length rather than computed, because varint lengths make the size a step
  function of the content and a formula would be wrong by a few bytes."
  [^long target-bytes]
  (let [fixed (pb-size (realistic-with-body 0))
        start (max 0 (- target-bytes fixed))]
    (loop [n start]
      (let [m (realistic-with-body n)
            size (pb-size m)]
        (cond
          (>= size target-bytes) m
          (> n (* 2 target-bytes)) m
          :else (recur (inc n)))))))

(defn tiers
  "Tier name to HelloRequest map."
  [^long target-bytes]
  {:tiny {:name "world"}
   :realistic (realistic target-bytes)})

(defn- json-bytes
  "The REST body: the same map, as JSON. Keys are the proto field names in
  snake_case, so a REST consumer sees the shape the proto declares."
  ^bytes [m]
  (letfn [(snake [k] (str/replace (name k) "-" "_"))
          (walk [v]
            (cond
              (map? v) (into {} (map (fn [[k x]] [(snake k) (walk x)])) v)
              (sequential? v) (mapv walk v)
              :else v))]
    (.getBytes ^String (j/write-value-as-string (walk m)) "UTF-8")))

(defn write!
  "Write every tier's files into `out-dir`; returns the SIZES.txt text."
  [out-dir ^long target-bytes]
  (let [dir (io/file out-dir)]
    (.mkdirs dir)
    (let [lines (for [[tier m] (sort-by key (tiers target-bytes))
                      :let [^bytes json (json-bytes m)
                            ^bytes pb (.toByteArray ^com.google.protobuf.Message (g/HelloRequest->proto m))]]
                  (do
                    (with-open [o (io/output-stream (io/file dir (str (name tier) ".json")))] (.write o json))
                    (with-open [o (io/output-stream (io/file dir (str (name tier) ".pb")))] (.write o pb))
                    (format "%-10s json=%-6d pb=%-6d json/pb=%.2f"
                            (name tier) (alength json) (alength pb)
                            (double (/ (alength json) (alength pb))))))
          text (str "# request body sizes in bytes, by tier (target pb bytes: " target-bytes ")\n"
                    (str/join "\n" lines) "\n")]
      (spit (io/file dir "SIZES.txt") text)
      text)))

(defn -main
  [& [out-dir target]]
  (when-not out-dir
    (binding [*out* *err*] (println "usage: payload OUT_DIR [TARGET_PB_BYTES]"))
    (System/exit 2))
  (print (write! out-dir (if target (Long/parseLong target) 1024)))
  (shutdown-agents))
