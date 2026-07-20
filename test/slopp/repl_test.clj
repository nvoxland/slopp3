(ns slopp.repl-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.repl :as repl] [clojure.java.io :as io]))

(deftest ^:external owned-repl-eval-and-restart
  (let [h (repl/start!)]
    (try
      (testing "evaluates in the owned image"
        (is (= [3] (repl/eval! h "(+ 1 2)"))))
      (testing "definitions persist within the live image (the refresh model, D5)"
        (repl/eval! h "(def marker 41)")
        (is (= [42] (repl/eval! h "(inc marker)")))
        (is (= [true] (repl/eval! h "(some? (resolve 'marker))"))))
      (let [h2 (repl/restart! h)]
        (try
          (testing "restart yields a faithful, EMPTY image — marker is gone (D5 backstop)"
            (is (= [3] (repl/eval! h2 "(+ 1 2)")))
            (is (= [false] (repl/eval! h2 "(some? (resolve 'marker))"))))
          (finally (repl/stop! h2))))
      (finally (repl/stop! h)))))

(deftest inherent-deps-ride-every-image
  ;; malli + nrepl ship WITH slopp (inherent), merged into every image's -Sdeps
  ;; — NOT via the project manifest (deps_add), so they are unremovable and
  ;; centrally versioned. Image-tier only (the server runs on kernel deps).
  (let [sdeps (nth (#'slopp.repl/default-cmd nil) 2)]
    (is (re-find #"metosin/malli" sdeps))
    (is (re-find #"nrepl/nrepl" sdeps)))
  (testing "inherent deps win a colliding manifest entry (slopp controls versions)"
    (let [sdeps (nth (#'slopp.repl/default-cmd '{metosin/malli {:mvn/version "0.0.0"}}) 2)]
      (is (re-find #"0\.17\.0" sdeps))
      (is (not (re-find #"0\.0\.0" sdeps))))))

(deftest ^:external image-dies-with-its-parent
  ;; The leak fix, proven behaviorally. A shard JVM that dies abnormally
  ;; (OOM, SIGKILL, killed test_run) orphans its child image subprocesses —
  ;; they reparent to init and run forever as idle nREPL servers (118 stranded
  ;; over ~23h, observed). An image must instead notice its parent is gone and
  ;; exit itself.
  ;;
  ;; Spawn an intermediary JVM (the "parent") that boots a slopp image (the
  ;; grandchild), read the grandchild's PID, then destroyForcibly the parent —
  ;; a stand-in for SIGKILL, which no shutdown hook can catch. The grandchild
  ;; must be gone within a few seconds.
  (let [cp   (System/getProperty "java.class.path")
        code (str "(require 'slopp.repl)"
                  "(let [img (slopp.repl/start!)]"
                  "  (println \"IMGPID\" (.pid (:process img)))"
                  "  (flush)"
                  "  (Thread/sleep 60000))")
        pb   (doto (ProcessBuilder. ["java" "-cp" cp "clojure.main" "-e" code])
               (.redirectErrorStream true))
        proc (.start pb)
        rdr  (io/reader (.getInputStream proc))
        img-pid (loop []
                  (when-let [line (.readLine rdr)]
                    (if-let [m (re-find #"IMGPID (\d+)" line)]
                      (Long/parseLong (second m))
                      (recur))))]
    (try
      (is (some? img-pid) "the intermediary booted an image and reported its PID")
      (.destroyForcibly proc)                     ; SIGKILL the parent
      (.waitFor proc)
      (testing "the orphaned image self-terminates within a few seconds"
        (let [dead? (loop [tries 0]
                      (cond
                        (not (.isPresent (java.lang.ProcessHandle/of img-pid))) true
                        (> tries 80) false        ; 8s grace, then it leaked
                        :else (do (Thread/sleep 100) (recur (inc tries)))))]
          (is dead? (str "image " img-pid " outlived its dead parent — leaked"))))
      (finally
        ;; never leave a leaked image behind, even if the assertion failed
        (when img-pid
          (some-> (java.lang.ProcessHandle/of img-pid)
                  (.orElse nil)
                  (.destroyForcibly)))))))
