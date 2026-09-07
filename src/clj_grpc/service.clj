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
  service does not choose a prototype itself: it derives the Java class hint
  by the same rules the emitter hints with (only for java_multiple_files or
  edition-2024+ top-level classes — the same subset the plugin hints) and
  hands it, with the SAME FileDescriptor instance the namespace registered,
  to clj-protobuf.runtime/message — the exact call the generated namespace
  makes. Whatever arm the runtime picks (the generated class, its compiled
  codec, or DynamicMessage under -Dclj-protobuf.codec=dynamic) is then the
  arm on both sides of the wire; being wrong costs the optimisation, never
  correctness."
  (:require [clj-protobuf.runtime :as rt]
            [clojure.string :as string])
  (:import [com.google.protobuf
            DescriptorProtos$FileDescriptorProto
            Descriptors$Descriptor
            Descriptors$FileDescriptor
            Descriptors$MethodDescriptor
            Descriptors$ServiceDescriptor
            Message]
           [io.grpc MethodDescriptor MethodDescriptor$MethodType]
           [io.grpc.protobuf ProtoUtils]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Prototype resolution (the hint, by the emitter's rules; the choice, by the runtime)

(def ^:private edition-2024-number
  (.getNumber com.google.protobuf.DescriptorProtos$Edition/EDITION_2024))

(defn- top-level-java-classes?
  [^DescriptorProtos$FileDescriptorProto fdp]
  (or (.getJavaMultipleFiles (.getOptions fdp))
      (and (= "editions" (.getSyntax fdp))
           (>= (.getNumber (.getEdition fdp)) edition-2024-number))))

(defn- name-path
  "The message's names from the top-level type down: [\"Outer\" \"Inner\"]."
  [^Descriptors$Descriptor desc]
  (loop [d desc, segs ()]
    (if d
      (recur (.getContainingType d) (cons (.getName d) segs))
      segs)))

(defn- java-class-hint
  "The Java class protoc would generate for this message, by the same rules the
  emitter hints with: derived only when classes are top-level
  (java_multiple_files, or edition 2024+ where nest_in_file_class defaults NO);
  nested messages join with $. nil when underivable — pre-2024 file-class
  nesting rules are deliberately not reimplemented, same as the emitter."
  [^Descriptors$Descriptor desc]
  (let [file (.getFile desc)
        fdp  (.toProto file)]
    (when (top-level-java-classes? fdp)
      (let [pkg (let [jp (.getJavaPackage (.getOptions fdp))]
                  (if (string/blank? jp) (.getPackage fdp) jp))]
        (str pkg (when-not (string/blank? pkg) ".")
             (string/join "$" (name-path desc)))))))

(defn- prototype
  "The marshaller prototype for a Descriptor: exactly what the generated
  namespace's own rt/message call yields for the same message. Delegating
  rather than re-deriving is what keeps inbound parsing on the codec the
  proto->X fns read — with clj-protobuf's compiled codec, a locally built
  DynamicMessage would parse requests onto the FieldSet path while the
  namespace's own prototypes lived on the compiled one. The FileDescriptor
  passed is the descriptor's own, the instance the namespace registered, and
  the dotted lookup resolves back to this same Descriptor: same pool."
  ^Message [^Descriptors$Descriptor desc]
  (rt/message (.getFile desc)
              (string/join "." (name-path desc))
              (java-class-hint desc)))

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
