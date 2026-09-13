(ns clj-grpc.metadata
  "io.grpc.Metadata — gRPC's headers and trailers — as Clojure data, and back.

      (metadata {\"authorization\" \"Bearer t\" \"x-trace-bin\" some-bytes})
      (header md \"authorization\")          ; last value, or nil
      (metadata->map md)                     ; {\"authorization\" \"Bearer t\" ...}

  Keys are header names: a name ending in \"-bin\" is a binary header carrying
  byte arrays, anything else is ASCII text — gRPC's own rule, applied so a
  caller never touches a Metadata$Key. Names may be strings or keywords; the
  wire form is the lower-cased string.

  A Metadata stays a Metadata on the hot path. The call map an interceptor
  receives carries the request headers raw, because converting every header
  of every call into a map costs on each request and most interceptors read
  one name. Maps are for declaring headers, not for reading them."
  (:refer-clojure :exclude [key])
  (:import [io.grpc Metadata Metadata$AsciiMarshaller Metadata$BinaryMarshaller Metadata$Key]
           [java.util.concurrent ConcurrentHashMap]))

(set! *warn-on-reflection* true)

(def ^:private ^ConcurrentHashMap key-cache (ConcurrentHashMap.))

(defn key
  "The Metadata$Key for a header name: binary when the name ends in -bin,
  ASCII otherwise. Cached per name — grpc requires the same Key instance to
  read what was written."
  ^Metadata$Key [name]
  (let [^String n (clojure.core/name name)]
    (or (.get key-cache n)
        (let [^Metadata$Key k
              (if (.endsWith n Metadata/BINARY_HEADER_SUFFIX)
                (Metadata$Key/of n ^Metadata$BinaryMarshaller Metadata/BINARY_BYTE_MARSHALLER)
                (Metadata$Key/of n ^Metadata$AsciiMarshaller Metadata/ASCII_STRING_MARSHALLER))]
          (or (.putIfAbsent key-cache n k) k)))))

(defn header
  "The last value sent under `name`, or nil. nil-safe on the Metadata."
  [^Metadata md name]
  (when md (.get md (key name))))

(defn values
  "Every value sent under `name`, in order — headers may repeat. nil when none."
  [^Metadata md name]
  (when md (some-> (.getAll md (key name)) seq)))

(defn metadata->map
  "The Metadata as {name last-value}: what `header` would answer for every
  name present. Repeated headers collapse to their last value; use `values`
  for all of them."
  [^Metadata md]
  (if md
    (into {} (map (fn [^String n] [n (.get md (key n))])) (.keys md))
    {}))

(defn- put-all! [^Metadata md name v]
  (let [k (key name)]
    (if (sequential? v)
      (doseq [x v] (.put md k x))
      (.put md k v))))

(defn merge-into!
  "Write the headers in `m` — a map or another Metadata — into `md`, replacing
  each name wholesale: the names present in `m` end up with exactly the
  values `m` gives them, names absent from `m` are untouched. A vector value
  writes the header once per element. Returns md."
  ^Metadata [^Metadata md m]
  (if (instance? Metadata m)
    (let [^Metadata other m]
      (doseq [^String n (.keys other)]
        (let [k (key n)]
          (.discardAll md k)
          (doseq [x (.getAll other k)] (.put md k x)))))
    (doseq [[n v] m]
      (.discardAll md (key n))
      (put-all! md n v)))
  md)

(defn metadata
  "A Metadata from a map — or the Metadata itself, unchanged, so callers may
  pass either."
  ^Metadata [m]
  (if (instance? Metadata m)
    m
    (merge-into! (Metadata.) (or m {}))))
