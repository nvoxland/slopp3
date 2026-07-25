(ns slopp.image.repl-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.image.repl :as repl] [clojure.java.io :as io]))

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
  (let [sdeps (nth (#'slopp.image.repl/default-cmd nil) 2)]
    (is (re-find #"metosin/malli" sdeps))
    (is (re-find #"nrepl/nrepl" sdeps)))
  (testing "inherent deps win a colliding manifest entry (slopp controls versions)"
    (let [sdeps (nth (#'slopp.image.repl/default-cmd '{metosin/malli {:mvn/version "0.0.0"}}) 2)]
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
        code (str "(require 'slopp.image.repl)"
                  "(let [img (slopp.image.repl/start!)]"
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
  (let [ex (try (repl/start! {:slopp.image.repl/cmd ["sleep" "60"]
                              :slopp.image.repl/timeout-ms 500})
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

(deftest benign-load-noise?-distinguishes-warnings-from-errors
  (testing "a stderr chunk of only compiler WARNINGs is benign noise"
    (is (#'slopp.image.repl/benign-load-noise?
         "WARNING: abs already refers to: #'clojure.core/abs in namespace: garden.color, being replaced by: #'garden.color/abs\n"))
    (is (#'slopp.image.repl/benign-load-noise?
         "Reflection warning, foo.clj:3:5 - call to method size can't be resolved.\n")))
  (testing "a chunk with a real error is NOT benign, even mixed with a warning"
    (is (not (#'slopp.image.repl/benign-load-noise?
              "Syntax error compiling at (foo.clj:1:1).\nUnable to resolve symbol: qux")))
    (is (not (#'slopp.image.repl/benign-load-noise?
              "WARNING: harmless\nUnable to resolve symbol: qux"))))
  (testing "empty/nil stderr is not classified as benign (prior behavior preserved)"
    (is (not (#'slopp.image.repl/benign-load-noise? "")))
    (is (not (#'slopp.image.repl/benign-load-noise? nil)))))

(deftest eval-outcome-reads-status-not-the-stderr-stream
  (testing "a load-time WARNING on stderr is OUTPUT, not failure — values survive"
    (is (= {:values [3]
            :stderr "WARNING: abs already refers to: #'clojure.core/abs\n"}
           (#'slopp.image.repl/eval-outcome
            [{:err "WARNING: abs already refers to: #'clojure.core/abs\n"}
             {:value "3"}
             {:status ["done"]}]))))
  (testing "an eval-error IS failure, and keeps the stderr text that names the cause"
    (let [r (#'slopp.image.repl/eval-outcome
             [{:err "Execution error (ExceptionInfo) at (REPL:1).\nboom\n"}
              {:ex "clojure.lang.ExceptionInfo" :status ["eval-error"]}
              {:status ["done"]}])]
      (is (nil? (:values r)))
      (is (re-find #"boom" (:err r)))))
  (testing "an eval-error with no stderr still names the exception class"
    (is (= {:err "clojure.lang.ExceptionInfo"}
           (#'slopp.image.repl/eval-outcome
            [{:ex "clojure.lang.ExceptionInfo" :status ["eval-error"]}]))))
  (testing "a clean eval carries no :stderr key at all"
    (is (= {:values [7]}
           (#'slopp.image.repl/eval-outcome [{:value "7"} {:status ["done"]}]))))
  (testing "unreadable values ride through as strings, as before"
    (is (= {:values ["#object[Foo]"]}
           (#'slopp.image.repl/eval-outcome [{:value "#object[Foo]"}])))))

(deftest ^:external reset-returns-an-image-to-baseline-or-refuses
  ;; Measured: a fresh image costs ~830ms of Clojure+nREPL class loading, and
  ;; that runtime is IDENTICAL in every image. What differs is the store's
  ;; namespaces — one to three tiny ones for a test store, ~9ms to unmap and
  ;; reload. So the unit is about 90x too heavy for what it is asked to do.
  ;;
  ;; Clojure DOES give one root for the code half: `Namespace/namespaces` is a
  ;; static registry and `remove-ns` is the sweep. It gives no such root for
  ;; the classpath half — a jar cannot be un-added — which is why that case is
  ;; recorded as a fact and refused outright rather than reset.
  ;;
  ;; The whole safety of reuse rests on this fn being able to say NO.
  (let [img (repl/start! {})]
    (try
      (testing "a fresh image records what baseline means for it, and is clean"
        (is (seq (:nses (:baseline img))) "the namespace set, before any store code")
        (is (contains? (set (:nses (:baseline img))) 'slopp.rt)
            "rt is injected before the snapshot, so it survives every reset")
        (is (false? (first (repl/eval! img repl/dirty-probe)))))
      (testing "a tenant's namespaces are gone after reset"
        (repl/eval! img "(ns leak.core) (def marker 42) (defn f [] marker)")
        (is (= 42 (first (repl/eval! img "(deref (resolve 'leak.core/marker))")))
            "the tenant really is loaded")
        (is (some? (repl/reset-to-baseline! img)) "and the reset verifies clean")
        (is (nil? (first (repl/eval! img "(find-ns 'leak.core)")))
            "the next tenant must not be able to see it")
        (is (nil? (first (repl/eval! img "(resolve 'leak.core/marker)")))))
      (testing "reset is repeatable — a recycled image can be recycled again"
        (repl/eval! img "(ns leak.two) (def m 1)")
        (is (some? (repl/reset-to-baseline! img)))
        (is (nil? (first (repl/eval! img "(find-ns 'leak.two)")))))
      (testing "the image still WORKS after a reset — clean is not the same as dead"
        (is (= 3 (first (repl/eval! img "(+ 1 2)"))))
        (repl/eval! img "(ns leak.three) (def m 7)")
        (is (= 7 (first (repl/eval! img "(deref (resolve 'leak.three/m))")))
            "a fresh tenant loads normally afterwards")
        (repl/reset-to-baseline! img))
      (testing "an image that took a DEPENDENCY is refused forever after"
        ;; A jar cannot be unloaded, so such an image can never be baseline
        ;; again. Inferring this from the classloader silently MISSED — nREPL
        ;; does not mutate the loader the probe read — and two tests that were
        ;; green in isolation went red in the full suite. It is now the fact
        ;; add-libs! records, checked before anything is swept.
        (repl/eval! img "(intern 'user 'slopp-image-dirty true)")
        (is (nil? (repl/reset-to-baseline! img))
            "dirty is refused outright, not reset and hoped over"))
      (testing "an image with NO recorded baseline is refused"
        ;; nothing to verify against means nothing can be proven clean, and an
        ;; unproven image must never be handed to the next tenant
        (is (nil? (repl/reset-to-baseline! (dissoc img :baseline)))))
      (finally (repl/stop! img)))))

(deftest ^:external parked-images-are-keyed-by-their-classpath
  ;; The first cut treated "has dependencies" as permanently un-recyclable.
  ;; That is backwards: a classpath is STABLE — a project declares its deps
  ;; once and every session wants exactly those — so a dep-carrying image is
  ;; the common case, not the exceptional one. Refusing it meant reuse applied
  ;; only to stores with NO dependencies: slopp's own test fixtures, and no
  ;; real project at all.
  ;;
  ;; Exercised ONE AT A TIME on purpose. An earlier version parked two images
  ;; to compare keys side by side, which quietly made it a test of pool DEPTH
  ;; as well — and it broke the day the depth changed for reasons that had
  ;; nothing to do with keying.
  (repl/drain-parked!)
  (let [json '{org.clojure/data.json {:mvn/version "2.5.0"}}]
    (try
      (testing "a dep-free image is handed back only to a dep-free caller"
        (let [a (repl/start! {})]
          (is (true? (repl/park! a {})))
          (is (nil? (repl/unpark! json))
              "equality, not compatibility — an image carrying MORE than was
               asked for is not the environment that was requested")
          (let [got (repl/unpark! {})]
            (is (some? got) "and the matching caller gets it")
            (repl/stop! got))))
      (testing "a dep-carrying image recycles just as well, under its own key"
        (let [b (repl/start! {})]
          (is (true? (repl/park! b json)))
          (is (nil? (repl/unpark! {})) "a dep-free caller must not get it")
          (let [got (repl/unpark! json)]
            (is (some? got) "the whole point: deps do not forfeit reuse")
            (repl/stop! got))))
      (is (nil? (repl/unpark! {})) "an empty pool hands over nothing")
      (finally (repl/drain-parked!)))))
