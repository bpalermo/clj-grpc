(ns clj-grpc.service
  "The service half of protoc-gen-clojure's generated-code contract.

  A generated file with a service emits exactly two calls here:

      (def Greeter (rts/service file-descriptor \"Greeter\"))
      (def greeter-methods (rts/methods-map Greeter))

  `service` derives everything else — method names, streaming shapes,
  request/response prototypes, and (lazily) grpc-java MethodDescriptors with
  protobuf marshallers — from the FileDescriptor. The methods map is the bridge
  both the server and client builders consume.

  Pool discipline, the one subtle thing here: request/response prototypes must
  live in the same descriptor pool — and on the same codec — as the generated
  namespace's message prototypes, or the generated proto->X fns crash on
  parsed messages (protobuf-java forbids cross-pool field access). So the
  service chooses nothing: it hands each method's request and response
  Descriptor — the instance the namespace's own FileDescriptor owns — to
  clj-protobuf.runtime/prototype, which derives the Java class hint by the
  emitter's own rule and picks the arm rt/message gave the namespace for
  that message: the generated class, its compiled codec, or DynamicMessage
  under -Dclj-protobuf.codec=dynamic. One rule, in one place, on both sides
  of the wire; being wrong costs the optimisation, never correctness."
  (:require [clj-protobuf.runtime :as rt]
            [clojure.string :as string])
  (:import [com.google.protobuf
            Descriptors$FileDescriptor
            Descriptors$MethodDescriptor
            Descriptors$ServiceDescriptor
            Message]
           [io.grpc MethodDescriptor MethodDescriptor$MethodType]
           [io.grpc.protobuf ProtoUtils]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Prototypes: the runtime's choice, not ours

(defn- prototype
  "The marshaller prototype for a method's request or response type: exactly
  what the generated namespace's own rt/message call yielded for the same
  message, so a request the marshaller parses is one the proto->X fns read.
  clj-grpc used to derive the class hint and build the fallback itself; with
  clj-protobuf's compiled codec that put inbound parsing on DynamicMessage's
  FieldSet path beside a compiled namespace, and its copy of the hint rule
  could drift (it did: nest_in_file_class = YES). rt/prototype (0.2.1) owns
  both. Same pool by construction: the Descriptor is the file's own."
  ^Message [desc]
  (rt/prototype desc))

;; ---------------------------------------------------------------------------
;; The service value

(defn- kebab [s]
  (keyword
   (-> s
       (string/replace #"([a-z0-9])([A-Z])" "$1-$2")
       (string/replace "_" "-")
       (string/lower-case))))

(defrecord Method
           [name              ; "SayHello", the proto name
            key               ; :say-hello
            type              ; :unary | :server-streaming | :client-streaming | :bidi
            ^Message input-prototype
            ^Message output-prototype
            method-descriptor]) ; delay of io.grpc.MethodDescriptor

(defrecord Service
           [name full-name
            ^Descriptors$FileDescriptor file-descriptor
            ^Descriptors$ServiceDescriptor service-descriptor
            methods])         ; vector of Method, declaration order

(defn- method-type [^Descriptors$MethodDescriptor md]
  (let [p (.toProto md)
        client? (.getClientStreaming p)
        server? (.getServerStreaming p)]
    (cond
      (and client? server?) :bidi
      client?               :client-streaming
      server?               :server-streaming
      :else                 :unary)))

(def ^:private grpc-method-type
  {:unary            MethodDescriptor$MethodType/UNARY
   :server-streaming MethodDescriptor$MethodType/SERVER_STREAMING
   :client-streaming MethodDescriptor$MethodType/CLIENT_STREAMING
   :bidi             MethodDescriptor$MethodType/BIDI_STREAMING})

(defn- build-method [^String service-full-name ^Descriptors$MethodDescriptor md]
  (let [in  (prototype (.getInputType md))
        out (prototype (.getOutputType md))
        type (method-type md)]
    (->Method
     (.getName md)
     (kebab (.getName md))
     type
     in
     out
     ;; A delay so building a Service never constructs grpc-java machinery a
     ;; caller that only wanted the shapes will not use.
     (delay
       (-> (MethodDescriptor/newBuilder)
           (.setType ^MethodDescriptor$MethodType (grpc-method-type type))
           (.setFullMethodName
            (MethodDescriptor/generateFullMethodName service-full-name (.getName md)))
           (.setRequestMarshaller (ProtoUtils/marshaller in))
           (.setResponseMarshaller (ProtoUtils/marshaller out))
           (.build))))))

(defn service
  "The service value for a service declared in `file-descriptor` — the whole
  generated-code contract for services."
  [^Descriptors$FileDescriptor fd ^String service-name]
  (let [^Descriptors$ServiceDescriptor sd
        (or (.findServiceByName fd service-name)
            (throw (ex-info (str "no service " service-name
                                 " in " (.getName fd))
                            {:clj-grpc/error :no-such-service
                             :service service-name
                             :file (.getName fd)})))
        full (.getFullName sd)]
    (->Service (.getName sd) full fd sd
               (mapv #(build-method full %) (.getMethods sd)))))

(defn methods-map
  "{:kebab-method-key Method, ...} — the bridge shape the server and client
  builders consume."
  [^clj_grpc.service.Service svc]
  (into {} (map (juxt :key identity)) (:methods svc)))
