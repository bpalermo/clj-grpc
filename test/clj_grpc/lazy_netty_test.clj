(ns clj-grpc.lazy-netty-test
  "The native-image contract, pinned on the JVM.

  Two halves. First: requiring the API namespaces must not load the Netty leaf
  — under native-image the API namespaces initialize at build time and the
  leaf must not, so an eager require would put Netty class initialization back
  at image build, which Netty's own metadata rejects. Second: no source file
  outside the leaf may mention a Netty package at all, because any direct
  reference — an :import, a class hint used for interop, a fully-qualified
  call — re-creates the build-time initialization the leaf exists to prevent.

  Each half is enforced by the CI leg that can see it: the laziness check
  needs a JVM where nothing else has constructed a server yet, which is the
  Bazel leg (one namespace per test JVM); the source scan needs the source
  tree, which is the plain-clj leg (tests run from the repo root). Each half
  skips cleanly where it cannot run, and the CI matrix runs both legs on
  every change."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clj-grpc.client :as client]
            [clj-grpc.server :as server]
            [clj-grpc.transport :as transport]))

(deftest netty-loads-at-first-construction-not-at-require
  (if (find-ns 'clj-grpc.impl.netty)
    (println "clj-grpc.impl.netty already loaded in this JVM;"
             "laziness half skipped (it runs in the Bazel leg, one ns per JVM)")
    (do
      (is (nil? (find-ns 'clj-grpc.impl.netty))
          "requiring transport/server/client must not load the Netty leaf")
      ;; TCP address coercion is pure JDK and must stay leaf-free too.
      (is (some? (transport/->address 0)))
      (is (false? (transport/unix-address? (transport/->address 0))))
      (is (nil? (find-ns 'clj-grpc.impl.netty))
          "address handling for TCP must not load the Netty leaf")
      ;; First construction is the loading moment.
      (is (keyword? (transport/resolve-transport nil)))
      (is (some? (find-ns 'clj-grpc.impl.netty))
          "first transport resolution loads the leaf"))))

(deftest netty-references-confined-to-the-leaf
  (let [src (io/file "src/clj_grpc")]
    (if-not (.isDirectory src)
      (println "source tree not visible from the test cwd;"
               "confinement half skipped (it runs in the plain-clj leg)")
      (doseq [^java.io.File f (file-seq src)
              :when (and (.isFile f)
                         (str/ends-with? (.getName f) ".clj")
                         (not (str/includes? (.getPath f) "impl")))]
        (let [code (->> (str/split-lines (slurp f))
                        (map #(first (str/split % #";" 2)))
                        (str/join "\n"))]
          (is (not (re-find #"io\.netty|io\.grpc\.netty" code))
              (str (.getPath f)
                   " references a Netty package outside clj-grpc.impl")))))))
