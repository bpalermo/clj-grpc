(ns clj-grpc.coldstart.measure
  "Time-to-first-RPC for a cold server process — the number Knative
  scale-from-zero actually pays.

  Spawns the server the way a container runtime would (a fresh process with
  $PORT), then hammers say-hello with short deadlines from an already-warm
  client until the first success; reports spawn-to-first-success. The prober
  JVM is warm on purpose: the subject is the server's cold start, not ours.

  Arms are argument vectors handed to ProcessBuilder, so the same harness
  measures a plain jar, a CDS-restored jar, and a native binary."
  (:require [acme.greeter.greeter :as g]
            [clj-grpc.client :as client])
  (:import [java.lang ProcessBuilder$Redirect]
           [java.net ServerSocket]))

(defn- free-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- first-rpc-ms
  "Spawn cmd with PORT=port; poll until say-hello answers; kill; return ms."
  [cmd port]
  (let [pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.redirectOutput ProcessBuilder$Redirect/DISCARD)
             (.redirectErrorStream true))]
    (.put (.environment pb) "PORT" (str port))
    (let [t0 (System/nanoTime)
          proc (.start pb)
          ch (client/channel (str "localhost:" port) {:plaintext true})
          call (:say-hello (client/client ch g/greeter-methods
                                          {:deadline-ms 250 :wait-for-ready true}))
          req (g/HelloRequest->proto {:name "cold"})]
      (try
        (loop []
          (when-not (.isAlive proc)
            (throw (ex-info "server process died before serving" {:cmd cmd})))
          (let [ok (try (some? (call req)) (catch Throwable _ false))]
            (if ok
              (/ (double (- (System/nanoTime) t0)) 1e6)
              (recur))))
        (finally
          (client/shutdown ch {:grace-ms 500})
          (.destroy proc)
          (.waitFor proc))))))

(defn measure-arm
  "Median of n cold starts."
  [label cmd n]
  (let [runs (vec (for [_ (range n)]
                    (first-rpc-ms cmd (free-port))))
        sorted (sort runs)
        median (nth sorted (quot n 2))]
    (println (format "| %s | %.0f ms | %.0f–%.0f ms |"
                     label median (first sorted) (last sorted)))
    {:label label :median median :runs runs}))

(defn -main
  "args: label1 cmd1... '--' label2 cmd2... ('--' ...). Each segment is one
  arm: a label followed by the command vector."
  [& args]
  (let [arms (->> (partition-by #{"--"} args)
                  (remove #{'("--")})
                  (map (fn [[label & cmd]] [label (vec cmd)])))]
    (println "| arm | median time-to-first-RPC | range |")
    (println "|---|---|---|")
    (doseq [[label cmd] arms]
      (measure-arm label cmd 5))
    (shutdown-agents)))
