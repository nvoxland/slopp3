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

(deftest read-port-times-out-on-a-silent-child
  ;; The deadline was only checked BETWEEN lines; .readLine blocked with no
  ;; bound, so a child that booted silently and hung never tripped the
  ;; timeout — open!, start-spare!, and close! (deref'ing the spare) all
  ;; wedged forever. The read itself must be bounded.
  (let [pipe (java.io.PipedWriter.)
        rdr  (java.io.BufferedReader. (java.io.PipedReader. pipe))]
    (try
      (let [f (future (try (#'repl/read-port rdr 300) (catch Exception e e)))
            r (deref f 3000 :hung)]
        (is (not= :hung r) "read-port blocked past its deadline")
        (is (instance? clojure.lang.ExceptionInfo r) (pr-str r))
        (is (re-find #"did not report a port" (str (ex-message r)))))
      (finally (.close pipe)))))

(deftest the-watchdog-boards-before-nrepl
  ;; The watchdog was installed by inject-rt! only after spawn, port-read,
  ;; connect, and rt load — so a parent killed during that window (or any
  ;; throw in it) left a JVM that nothing would ever reap, the exact class
  ;; d9279 closed. clojure.main treats -e as an init-opt, so the watchdog can
  ;; board on the child's own command line, before nrepl even starts.
  (let [cmd   (#'repl/default-cmd nil)
        e-idx (.indexOf ^java.util.List cmd "-e")
        m-idx (.indexOf ^java.util.List cmd "-m")]
    (is (nat-int? e-idx) (pr-str cmd))
    (is (< e-idx m-idx) "the watchdog -e must precede nrepl's -m")
    (is (re-find #"slopp-parent-watchdog" (str (nth cmd (inc e-idx))))
        (pr-str cmd))))

(deftest ^:external a-failed-boot-never-abandons-the-child-jvm
  ;; Any throw between spawn and watchdog install used to ABANDON a running
  ;; child: start! had no try/catch and never destroyed the process, and
  ;; nrepl.cmdline never reads stdin, so the orphan outlived even parent
  ;; death. The failure path owns the kill; the pid rides the ex-info so this
  ;; test can verify the child is actually gone.
  (let [ex (try (repl/start! {:slopp.repl/cmd ["sleep" "60"]
                              :slopp.repl/timeout-ms 500})
                nil
                (catch Exception e e))]
    (is (some? ex) "a portless child must fail the boot")
    (let [pid (:pid (ex-data ex))]
      (is (some? pid) (str "boot failure must carry the child pid: " ex))
      (when pid
        (loop [n 0]
          (let [oh    (java.lang.ProcessHandle/of pid)
                alive (and (.isPresent oh)
                           (.isAlive ^java.lang.ProcessHandle (.get oh)))]
            (cond
              (not alive) (is true)
              (< n 20)    (do (Thread/sleep 100) (recur (inc n)))
              :else       (is false "child still alive after a failed boot"))))))))
