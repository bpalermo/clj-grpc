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
  live in the same descriptor pool as the generated namespace's message
  prototypes, or the generated proto->X fns crash on parsed messages
  (protobuf-java forbids cross-pool field access). So prototypes are resolved
  exactly the way the emitter's hints are: a Java class name derived by the
  same rules protoc uses (only for java_multiple_files or edition-2024+
  top-level classes — the same subset the plugin hints), verified against the
  descriptor, silently falling back to DynamicMessage over the SAME
  FileDescriptor instance the namespace's own prototypes use. Both arms align;
  being wrong costs the optimisation, never correctness."
  (:require [clojure.string :as string])
  (:import [com.google.protobuf
            DescriptorProtos$FileDescriptorProto
            Descriptors$Descriptor
            Descriptors$FileDescriptor
            Descriptors$MethodDescriptor
            Descriptors$ServiceDescriptor
            DynamicMessage
            Message]
           [io.grpc MethodDescriptor MethodDescriptor$MethodType]
           [io.grpc.protobuf ProtoUtils]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Prototype resolution (mirrors clj-protobuf.runtime/message's hint machinery)

(def ^:private edition-2024-number
  (.getNumber com.google.protobuf.DescriptorProtos$Edition/EDITION_2024))

(defn- top-level-java-classes?
  [^DescriptorProtos$FileDescriptorProto fdp]
  (or (.getJavaMultipleFiles (.getOptions fdp))
      (and (= "editions" (.getSyntax fdp))
           (>= (.getNumber (.getEdition fdp)) edition-2024-number))))

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
      (let [pkg  (let [jp (.getJavaPackage (.getOptions fdp))]
                   (if (string/blank? jp) (.getPackage fdp) jp))
            path (loop [d desc, segs ()]
                   (if d
                     (recur (.getContainingType d) (cons (.getName d) segs))
                     segs))]
        (str pkg (when-not (string/blank? pkg) ".")
             (string/join "$" path))))))

(defn- prototype
  "A default instance for a Descriptor, hinted-class when it resolves and
  describes the same message, DynamicMessage over the same descriptor pool
  otherwise."
  ^Message [^Descriptors$Descriptor desc]
  (or (when-let [hint (java-class-hint desc)]
        (try
          (let [cls (Class/forName hint)
                m   (.getMethod cls "getDefaultInstance" (make-array Class 0))
                inst ^Message (.invoke m nil (make-array Object 0))]
            (when (= (.getFullName (.getDescriptorForType inst))
                     (.getFullName desc))
              inst))
          (catch Throwable _ nil)))
      (DynamicMessage/getDefaultInstance desc)))

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
  (let [sd (or (.findServiceByName fd service-name)
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
