(ns slopp.build-native-test
  "O4: build! with :main emits a GraalVM native-image recipe alongside the
  sources — a generated gen-class launcher, a :native deps alias, and an
  executable build script. The actual native-image compile needs GraalVM and
  minutes of wall time, so these tests assert the emitted recipe, not the
  compile (that path is exercised manually; see .context/operation-api.md)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]
            [slopp.build :as build])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest arg-style-t
  (testing "a single fixed arity of 1 receives the CLI args as ONE vector"
    (is (= :vector (build/arg-style {:fixed-arities #{1}}))))
  (testing "varargs and every other shape is applied -main style"
    (is (= :apply (build/arg-style {:varargs-min-arity 0})))
    (is (= :apply (build/arg-style {:fixed-arities #{2}})))
    (is (= :apply (build/arg-style {:fixed-arities #{1} :varargs-min-arity 1})))
    (is (= :apply (build/arg-style {:fixed-arities #{0 1}})))))

(deftest launcher-source-t
  (let [src (build/launcher-source 'calc.core/run-cli :vector)]
    (is (re-find #"\(ns native\.main" src))
    (is (re-find #":gen-class" src))
    (is (re-find #"\[calc\.core\]" src))
    (is (re-find #"\(calc\.core/run-cli \(vec args\)\)" src))
    (is (re-find #"shutdown-agents" src)))
  (is (re-find #"\(apply calc\.core/run-cli args\)"
               (build/launcher-source 'calc.core/run-cli :apply))))

(deftest recipe-content-t
  (testing "native deps.edn parses and carries the :native alias"
    (let [d (edn/read-string (build/deps-edn true))]
      (is (= ["src"] (:paths d)))
      (is (some? (get-in d [:aliases :native :extra-deps
                            'com.github.clj-easy/graal-build-time])))
      (is (some #{"-Dclojure.compiler.direct-linking=true"}
                (get-in d [:aliases :native :jvm-opts])))))
  (testing "plain deps.edn is unchanged without native"
    (is (= {:paths ["src"]} (edn/read-string (build/deps-edn false)))))
  (testing "the script AOT-compiles the launcher, then native-images it"
    (let [s (build/native-script "calc")]
      (is (re-find #"compile 'native\.main" s))
      (is (re-find #"native-image" s))
      (is (re-find #"--no-fallback" s))
      ;; graal-build-time's Feature is NOT auto-discovered (its jar carries no
      ;; META-INF/native-image properties) — the flag is load-bearing
      (is (re-find #"--features=clj_easy\.graal_build_time\.InitClojureClasses" s))
      (is (re-find #"-o \"calc\"" s)))))

(deftest build-native-t
  (let [sess (api/open!)
        dir  (str (Files/createTempDirectory "slopp-native"
                                             (make-array FileAttribute 0)))]
    (try
      (api/ingest! sess 'calc.core
                   (str "(ns calc.core)\n"
                        "(defn run-cli [args]\n"
                        "  (doseq [a args] (println a)))\n"))
      (testing "an unknown entry fn is rejected before anything is written"
        (is (:error (api/build! sess dir :main 'calc.core/nope)))
        (is (:error (api/build! sess dir :main 'nope.core/run-cli)))
        (is (:error (api/build! sess dir :main 'unqualified))))
      (testing "build! with :main emits src + launcher + executable script"
        (let [r (api/build! sess dir :main 'calc.core/run-cli)]
          (is (nil? (:error r)))
          (is (:built r))
          (is (= "calc" (get-in r [:native :binary])))
          (is (.exists (io/file dir "src" "calc" "core.clj")))
          ;; run-cli takes ONE seq of args — the launcher must pass a vector
          (is (re-find #"\(calc\.core/run-cli \(vec args\)\)"
                       (slurp (io/file dir "src" "native" "main.clj"))))
          (let [script (io/file dir "build-native.sh")]
            (is (.exists script))
            (is (.canExecute script)))
          (is (contains? (:aliases (edn/read-string
                                    (slurp (io/file dir "deps.edn"))))
                         :native))))
      (testing "rebuilding into the same dir (our own deps.edn) is allowed"
        (is (= "mycalc" (get-in (api/build! sess dir :main 'calc.core/run-cli
                                            :name "mycalc")
                                [:native :binary]))))
      (testing "a FOREIGN deps.edn blocks the native build (X4 no-clobber)"
        (let [dir3 (str (Files/createTempDirectory "slopp-foreign"
                                                   (make-array FileAttribute 0)))]
          (spit (io/file dir3 "deps.edn") "{:paths [\"lib\"]}\n")
          (is (:error (api/build! sess dir3 :main 'calc.core/run-cli)))
          (is (= "{:paths [\"lib\"]}\n" (slurp (io/file dir3 "deps.edn"))))))
      (testing "plain build! emits no native artifacts"
        (let [dir2 (str (Files/createTempDirectory "slopp-plain"
                                                   (make-array FileAttribute 0)))
              r    (api/build! sess dir2)]
          (is (:built r))
          (is (nil? (:native r)))
          (is (not (.exists (io/file dir2 "src" "native"))))
          (is (= {:paths ["src"]}
                 (edn/read-string (slurp (io/file dir2 "deps.edn")))))))
      (finally (api/close! sess)))))
