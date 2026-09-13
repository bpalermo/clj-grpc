(ns clj-grpc.interceptor-test
  "Interceptors over real Netty: the call map on both sides, rejection,
  ordering with raw grpc interceptors mixed in, response metadata reaching
  the client, per-call scope, and the one caveat worth a test — the call
  context is a ThreadLocal."
  (:require [acme.greeter.greeter :as g]
            [clojure.test :refer [deftest is testing]]
            [clj-grpc.client :as client]
            [clj-grpc.context :as context]
            [clj-grpc.interceptor :as interceptor :refer [reject]]
            [clj-grpc.metadata :as metadata]
            [clj-grpc.names :as names]
            [clj-grpc.server :as server])
  (:import [io.grpc Channel ClientInterceptor Context Metadata ServerCallHandler
            ServerInterceptor Status$Code StatusRuntimeException]))

(defn- reply [text] (g/HelloReply->proto {:message text}))
(defn- request-name [req] (:name (g/proto->HelloRequest req)))

(defn- with-server
  "f gets the started server and a channel to it; both are torn down."
  [{:keys [handlers server-opts channel-opts]} f]
  (let [srv (-> (server/server (merge {:services [{:service g/Greeter :handlers handlers}]
                                       :address 0
                                       :health true
                                       :reflection false}
                                      server-opts))
                server/start)
        ch  (client/channel (str "localhost:" (server/port srv))
                            (merge {:plaintext true} channel-opts))]
    (try (f srv ch)
         (finally
           (client/shutdown ch {:grace-ms 2000})
           (server/shutdown srv {:grace-ms 2000})))))

(defn- unary [ch opts]
  (client/invoke ch (:say-hello g/greeter-methods) (g/HelloRequest->proto {:name "x"}) opts))

(defn- status-code ^Status$Code [^Throwable t]
  (.getCode (.getStatus ^StatusRuntimeException t)))

;; ---------------------------------------------------------------------------

(deftest the-call-reaches-every-handler-shape
  (let [seen     (atom {})
        observe! (fn [shape]
                   (swap! seen assoc shape
                          {:method    (context/method)
                           :service   (context/service)
                           :header    (context/header "x-test")
                           :peer?     (some? (context/peer))
                           :deadline? (some? (context/deadline))
                           :user      (:user (context/call))}))
        intercepted (atom nil)
        handlers
        {:say-hello      (fn [req] (observe! :unary) (reply (request-name req)))
         :say-hello-many (fn [req send!] (observe! :server-streaming) (send! (reply "1")))
         :collect-hellos (fn [respond!]
                           {:on-next     (fn [_] (observe! :client-streaming-next))
                            :on-complete (fn [] (observe! :client-streaming-complete)
                                           (respond! (reply "n")))})
         :chat           (fn [send! close!]
                           (observe! :bidi-body)
                           {:on-next     (fn [_] (observe! :bidi-next) (send! (reply "e")))
                            :on-complete (fn [] (close!))})}]
    (with-server
      {:handlers handlers
       :server-opts {:interceptors [(fn [call next]
                                      (reset! intercepted (select-keys call [:method :service :full-method-name]))
                                      (next (assoc call :user "bob")))]}}
      (fn [_ ch]
        (let [opts {:deadline-ms 10000 :headers {"x-test" "v"}}
              greeter (client/client ch g/greeter-methods opts)]
          ((:say-hello greeter) (g/HelloRequest->proto {:name "a"}))
          (doall ((:say-hello-many greeter) (g/HelloRequest->proto {:name "a" :repeat-count 1})))
          (let [{:keys [send! close! response]} ((:collect-hellos greeter) nil)]
            (send! (g/HelloRequest->proto {:name "a"})) (close!)
            (is (some? (deref response 5000 nil))))
          (let [done (promise)
                {:keys [send! close!]} ((:chat greeter) {:on-next (fn [_]) :on-complete #(deliver done true)})]
            (send! (g/HelloRequest->proto {:name "a"})) (close!)
            (is (true? (deref done 5000 false))))
          (testing "the interceptor saw the method the way the handlers map names it"
            ;; @intercepted holds the LAST call's view, which was :chat
            (is (= {:method :chat :service "acme.greeter.Greeter"
                    :full-method-name "acme.greeter.Greeter/Chat"}
                   @intercepted)))
          (doseq [shape [:unary :server-streaming :client-streaming-next
                         :client-streaming-complete :bidi-body :bidi-next]]
            (testing (str shape " reads the call through the context")
              (let [{:keys [service header peer? deadline? user]} (get @seen shape)]
                (is (= "acme.greeter.Greeter" service))
                (is (= "v" header) "the per-call header arrived")
                (is peer?)
                (is deadline? "the client's deadline is the call's context deadline")
                (is (= "bob" user) "enrichment by the interceptor reached the handler"))))
          (is (= :say-hello (:method (:unary @seen))))
          (is (= :chat (:method (:bidi-next @seen)))))))))

(deftest rejection-closes-before-the-handler
  (let [ran (atom 0)]
    (with-server
      {:handlers {:say-hello (fn [req] (swap! ran inc) (reply (request-name req)))}
       :server-opts {:interceptors
                     [(fn [call next]
                        (case (metadata/header (:headers call) "x-mode")
                          "reject" (reject :unauthenticated "no token" {"www-authenticate" "Bearer"})
                          "throw"  (throw (.asRuntimeException (interceptor/status :permission-denied "nope")))
                          "boom"   (throw (ex-info "kaboom" {}))
                          (next call)))]}}
      (fn [_ ch]
        (testing "a rejection value: status and trailers reach the client"
          (let [e (is (thrown? StatusRuntimeException (unary ch {:headers {"x-mode" "reject"}})))]
            (is (= Status$Code/UNAUTHENTICATED (status-code e)))
            (is (= "no token" (.getDescription (.getStatus ^StatusRuntimeException e))))
            (is (= "Bearer" (metadata/header (.getTrailers ^StatusRuntimeException e) "www-authenticate")))))
        (testing "a thrown StatusRuntimeException keeps its status"
          (is (= Status$Code/PERMISSION_DENIED
                 (status-code (is (thrown? StatusRuntimeException (unary ch {:headers {"x-mode" "throw"}})))))))
        (testing "any other throwable is INTERNAL with the message"
          (let [e (is (thrown? StatusRuntimeException (unary ch {:headers {"x-mode" "boom"}})))]
            (is (= Status$Code/INTERNAL (status-code e)))
            (is (= "kaboom" (.getDescription (.getStatus ^StatusRuntimeException e))))))
        (is (zero? @ran) "the handler never ran")
        (is (= "x" (:message (g/proto->HelloReply (unary ch nil)))) "and the pass-through path serves")
        (is (= 1 @ran))))))

(deftest vector-order-is-running-order-with-raw-interceptors-mixed
  (let [log (atom [])
        server-fn  (fn [k] (fn [call next] (swap! log conj k) (next call)))
        server-raw (reify ServerInterceptor
                     (interceptCall [_ call headers next]
                       (swap! log conj :raw)
                       (.startCall ^ServerCallHandler next call headers)))
        client-fn  (fn [k] (fn [call next] (swap! log conj k) (next call)))
        client-raw (reify ClientInterceptor
                     (interceptCall [_ m copts ch]
                       (swap! log conj :raw)
                       (.newCall ^Channel ch m copts)))]
    (with-server
      {:handlers {:say-hello (fn [req] (reply (request-name req)))}
       :server-opts  {:interceptors [(server-fn :a) server-raw (server-fn :b)]}
       :channel-opts {:interceptors [(client-fn :c) client-raw (client-fn :d)]}}
      (fn [_ ch]
        (unary ch nil)
        (is (= [:c :raw :d :a :raw :b] @log) "channel chain first-to-last, then the server's")
        (reset! log [])
        (unary ch {:interceptors [(client-fn :e)]})
        (is (= [:e :c :raw :d :a :raw :b] @log) "a per-call interceptor is outermost")))))

(deftest response-headers-and-trailers-reach-the-client
  (with-server
    {:handlers {:say-hello      (fn [req] (reply (request-name req)))
                :say-hello-many (fn [_ send!] (send! (reply "1")) (send! (reply "2")))}
     :server-opts {:interceptors [(fn [call next]
                                    (next (assoc call
                                                 :response-headers  {"x-served-by" "s"}
                                                 :response-trailers {"x-took" "1"})))]}}
    (fn [_ ch]
      (doseq [[shape call!] [[:unary #(unary ch %)]
                             [:server-streaming
                              #(doall (client/invoke ch (:say-hello-many g/greeter-methods)
                                                     (g/HelloRequest->proto {:name "x" :repeat-count 2}) %))]]]
        (testing (name shape)
          (let [hp (promise) tp (promise)
                watch (fn [call next]
                        (next (assoc call
                                     :on-headers  (fn [md] (deliver hp (metadata/header md "x-served-by")))
                                     :on-trailers (fn [s md] (deliver tp [(str (.getCode ^io.grpc.Status s))
                                                                          (metadata/header md "x-took")])))))]
            (call! {:interceptors [watch]})
            (is (= "s" (deref hp 5000 :none)))
            (is (= ["OK" "1"] (deref tp 5000 :none)))))))))

(deftest per-call-interceptors-apply-to-that-call-only
  (let [n (atom 0)]
    (with-server
      {:handlers {:say-hello (fn [req] (reply (request-name req)))}}
      (fn [_ ch]
        (unary ch {:interceptors [(fn [call next] (swap! n inc) (next call))]})
        (unary ch nil)
        (is (= 1 @n))))))

(deftest the-call-context-is-thread-local
  (let [spawned (promise) wrapped (promise)]
    (is (nil? (context/call)) "nothing outside a call")
    (with-server
      {:handlers {:chat (fn [send! close!]
                          (Thread/startVirtualThread
                           (fn [] (deliver spawned (context/call))))
                          (Thread/startVirtualThread
                           (.wrap (Context/current)
                                  ^Runnable (fn [] (deliver wrapped (some? (context/call))))))
                          {:on-next (fn [_] (send! (reply "e"))) :on-complete (fn [] (close!))})}
       :server-opts {:interceptors [(fn [call next] (next call))]}}
      (fn [_ ch]
        (let [done (promise)
              {:keys [close!]} (client/invoke ch (:chat g/greeter-methods)
                                              {:on-next (fn [_]) :on-complete #(deliver done true)} nil)]
          (close!)
          (is (true? (deref done 5000 false)))
          (is (nil? (deref spawned 5000 :none)) "a thread the handler starts does not inherit it")
          (is (true? (deref wrapped 5000 :none)) "unless the work is wrapped in the Context"))))))

(deftest empty-and-invalid-interceptor-vectors
  (doseq [v [nil []]]
    (with-server
      {:handlers {:say-hello (fn [req] (reply (request-name req)))}
       :server-opts {:interceptors v} :channel-opts {:interceptors v}}
      (fn [_ ch]
        (is (= "x" (:message (g/proto->HelloReply (unary ch {:interceptors v}))))))))
  (is (thrown? IllegalArgumentException
               (server/server {:services [] :address 0 :interceptors [42]})))
  (is (thrown? IllegalArgumentException
               (client/channel "localhost:1" {:plaintext true :interceptors [42]}))))

(deftest metadata-and-status-helpers
  (testing "map -> Metadata -> map, binary and repeated"
    (let [md (metadata/metadata {"x-a" "1" "x-r" ["a" "b"] "x-bin" (.getBytes "hi")})]
      (is (instance? Metadata md))
      (is (= "1" (metadata/header md "x-a")))
      (is (= "b" (metadata/header md "x-r")) "the last value")
      (is (= ["a" "b"] (vec (metadata/values md "x-r"))))
      (is (= "hi" (String. ^bytes (metadata/header md "x-bin"))))
      (is (= {"x-a" "1" "x-r" "b"} (dissoc (metadata/metadata->map md) "x-bin")))
      (is (identical? md (metadata/metadata md)) "a Metadata passes through")
      (metadata/merge-into! md {"x-r" "c"})
      (is (= ["c"] (vec (metadata/values md "x-r"))) "merge replaces a name wholesale")))
  (testing "every grpc status code has a keyword"
    (doseq [^Status$Code c (Status$Code/values)]
      (is (= c (.getCode (interceptor/status (names/kebab (.name c)))))))
    (is (= "why" (.getDescription (interceptor/status :not-found "why")))))
  (testing "an unknown keyword is the library's error shape"
    (is (= :bad-status (:clj-grpc/error (ex-data (try (interceptor/status :nope) (catch Exception e e))))))))
