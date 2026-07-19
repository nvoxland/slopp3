(ns slopp.verification-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.repl :as repl]
            [slopp.api :as api] [slopp.api.testrun :as testrun] [slopp.testmain :as testmain] [slopp.rt :as rt] [slopp.store :as store]))

(def target
  (str "(ns vdemo\n  (:require [clojure.test :refer [deftest is]]))\n"
       "(defn add [x y] (+ x y))\n"
       "(defn mul [x y] (* x y))\n"
       "(deftest add-t (is (= 5 (add 2 3))))\n"
       "(deftest mul-t (is (= 6 (mul 2 3))))\n"))

(deftest ^:isolated tracing-maps-tests-to-forms
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'vdemo target)
      (let [res (api/test-run! sess 'vdemo)]
        (is (= 2 (:pass res))))
      (testing "each test maps to exactly the forms it exercises (D1 form-granularity)"
        (let [tmap (:test-map @sess)]
          (is (= #{'vdemo/add} (tmap 'vdemo/add-t)))
          (is (= #{'vdemo/mul} (tmap 'vdemo/mul-t)))))
      (finally (api/close! sess)))))

(deftest ^:isolated edit-runs-only-affected-tests
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'vdemo target)
      (api/test-run! sess 'vdemo)                          ; builds the trace map
      (testing "editing mul re-runs only mul-t (add-t is untouched by the edit)"
        (let [r (api/edit-replace! sess 'vdemo 'mul "(defn mul [x y] (* y x))"
                                   :prompt "commute")]
          (is (nil? (:error r)))
          (is (= ['vdemo/mul-t] (:affected r)))
          (is (= 1 (:test (:test r))))
          (is (= 1 (:pass (:test r))))))
      (testing "editing a test itself re-runs exactly that test"
        (let [r (api/edit-replace! sess 'vdemo 'mul-t
                                   "(deftest mul-t (is (= 8 (mul 2 4))))"
                                   :prompt "retarget")]
          (is (= ['vdemo/mul-t] (:affected r)))
          (is (= 1 (:pass (:test r))))))
      (testing "ingest itself seeds the trace map (W1): edits narrow immediately"
        (let [sess2 (api/open!)]
          (try
            (api/ingest! sess2 'vdemo target)
            (let [r (api/edit-replace! sess2 'vdemo 'mul "(defn mul [x y] (* y x))")]
              (is (= ['vdemo/mul-t] (:affected r)))
              (is (= 1 (:test (:test r)))))
            (finally (api/close! sess2)))))
      (finally (api/close! sess)))))

(deftest ^:isolated reload-signature-reds-still-heal              ; D5.1 belt-and-suspenders
  ;; Even when the red IS on an edited path (flip rule says "explained"), an
  ;; unbound-var-style failure smells like staleness and must cross-check.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'sig.core
                   (str "(ns sig.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn helper [x] (* 2 x))\n"
                        "(defn f [x] (helper x))\n"
                        "(deftest f-t (is (= 10 (f 5))))\n"))
      ;; poison: rip helper out of the image behind the store's back
      (repl/eval! (:image @sess) "(ns-unmap 'sig.core 'helper)")
      ;; editing f now hits the compile gate against the stale image; D5.1
      ;; heals it: fresh image, retried load, write proceeds
      (let [r (api/edit-replace! sess 'sig.core 'f "(defn f [x] (helper x))"
                                 :prompt "touch f while helper is stale")]
        (is (nil? (:error r)))
        (is (true? (:image-healed r)))
        (is (zero? (+ (:fail (:test r)) (:error (:test r))))))
      (finally (api/close! sess)))))

(deftest ^:isolated test-run-fresh-forces-a-cross-check
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'fr.core
                   (str "(ns fr.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f [x] x)\n(deftest f-t (is (= 1 (f 1))))\n"))
      (let [before (:port (:image @sess))
            res    (api/test-run! sess 'fr.core :fresh true)]
        (is (zero? (:fail res)))
        (is (not= before (:port (:image @sess)))))   ; image really was replaced
      (finally (api/close! sess)))))

(deftest ^:isolated test-run-only-targets-named-tests
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'vdemo target)
      (let [r (api/test-run! sess 'vdemo :only ['add-t])]
        (is (= 1 (:test r)))
        (is (= 1 (:pass r))))
      (finally (api/close! sess)))))

(deftest ^:isolated red-is-cross-checked-on-a-fresh-image
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'vdemo target)
      (testing "staleness flip: image drifts behind the store's back; NOTHING was
                edited, so the red is unexplained -> restart heals it"
        ;; poison the image only (the store is untouched) — the classic stale state
        (repl/eval! (:image @sess) "(in-ns 'vdemo) (def add (fn [x y] 999))")
        (let [res (api/test-run! sess 'vdemo)]
          (is (zero? (+ (:fail res) (:error res))))
          (is (true? (:staleness-detected res)))))
      (testing "genuine red (D5.1): assertion failure on the just-edited path is
                reported immediately — ONE run, no restart, no cross-check"
        (let [r (api/edit-replace! sess 'vdemo 'add "(defn add [x y] (- x y))"
                                   :prompt "break it")]
          (is (= 1 (:fail (:test r))))
          (is (= :genuine (:diagnosis (:test r))))
          (is (nil? (:fresh-confirmed (:test r))))
          (is (nil? (:staleness-detected (:test r))))
          (testing "the WHY is in the result (F1) — not lost to image stdout"
            (let [f (first (:failures (:test r)))]
              (is (= 'vdemo/add-t (:test f)))
              (is (= :fail (:type f)))
              (is (re-find #"\(= 5 \(add 2 3\)\)" (:expected f)))
              (is (= "(not (= 5 -1))" (:actual f)))))))
      (finally (api/close! sess)))))
(deftest ^:isolated red-results-name-the-implicated-forms
  ;; Rock 2: the system holds the trace map AND the delta — a red write
  ;; result says WHICH changed form each failing test exercises
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'im.core :source "(ns im.core (:require [clojure.test :refer [deftest is]]))\n(defn f [x] (inc x))\n(defn g [x] (dec x))\n(deftest f-t (is (= 2 (f 1))))\n(deftest g-t (is (= 0 (g 1))))\n")
      (api/test-run! sess nil)
      (let [r     (api/edit-replace! sess 'im.core 'f "(defn f [x] (+ x 2))"
                                     :prompt "break it" :agent "t")
            fails (get-in r [:test :failures])]
        (is (seq fails))
        (testing "the failing test names the changed form it exercises"
          (is (= ['im.core/f] (:implicated (first fails)))))
        (testing "narrowing kept the untouched test out of the run"
          (is (= 1 (count fails)))))
      (finally (api/close! sess)))))
(deftest ^:isolated trace-map-survives-sessions
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-trace"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
        s1  (api/open! {:dir dir})]
    (try
      (api/ingest! s1 'vdemo target)
      (api/test-run! s1 'vdemo)
      (is (seq (:test-map @s1)))
      (finally (api/close! s1)))
    (let [s2 (api/open! {:dir dir})]
      (try
        (testing "a fresh session on the same store starts with the trace warm (Q3)"
          (is (= #{'vdemo/add} (get (:test-map @s2) 'vdemo/add-t))
              (pr-str (:test-map @s2))))
        (testing "…so its first edit narrows instead of running everything"
          (let [r (api/edit-replace! s2 'vdemo 'mul "(defn mul [x y] (* y x))"
                                     :prompt "commute")]
            (is (= ['vdemo/mul-t] (:affected r)) (pr-str (select-keys r [:affected :test])))))
        (finally (api/close! s2))))))
(deftest failure-themes-cluster-root-causes
  (let [block  (fn [t msg]
                 (str "FAIL in (" t ") (x.clj:1)\n"
                      "expected: ok\n"
                      "  actual: (not (= 1 \"" msg "\"))\n"))
        out    (str (block "t1" "module a.b does not declare c.d")
                    (block "t2" "module e.f does not declare g.h")
                    (block "t3" "module i.j does not declare k.l")
                    (block "t4" "totally unrelated kaboom"))
        themes (#'testrun/failure-themes out)]
    (testing "a phrase shared by three failures becomes ONE theme"
      (is (= [{:phrase "does not declare" :tests 3}] themes) (pr-str themes)))
    (testing "below the threshold nothing clusters"
      (is (empty? (#'testrun/failure-themes (block "t9" "lone wolf failure")))))))
(deftest ^:isolated affected-slice-runs-only-reachable-tests
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-affected"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (api/open! {:dir dir})]
    (try
      (api/ingest! sess 'ia.core "(ns ia.core)\n(defn f \"F.\" [x] (inc x))\n")
      (api/ingest! sess 'ia.core-test
                   (str "(ns ia.core-test (:require [ia.core :as c]\n"
                        "                           [clojure.test :refer [deftest is]]))\n"
                        "(deftest f-t (is (= 2 (c/f 1))))\n"))
      (api/ingest! sess 'ib.core "(ns ib.core)\n(defn g \"G.\" [x] (dec x))\n")
      (api/ingest! sess 'ib.core-test
                   (str "(ns ib.core-test (:require [ib.core :as c]\n"
                        "                           [clojure.test :refer [deftest is]]))\n"
                        "(deftest g-t (is (= 0 (c/g 1))))\n"))
      (api/commit-point! sess "baseline")
      (testing "right after a milestone the slice is empty — and says so"
        (let [r (api/isolated-test-run! sess :affected true)]
          (is (zero? (:ran r)) (pr-str r))
          (is (re-find #"full gate" (str (:note r))))))
      (testing "changing ONE island runs only the tests that can reach it"
        (let [er (api/edit-replace! sess 'ia.core 'f "(defn f \"F.\" [x] (+ x 1))"
                                    :prompt "same behavior, new spelling")]
          (is (nil? (:error er)) (pr-str er))
          (is (nil? (:conflict er)) (pr-str er)))
        (let [r (api/isolated-test-run! sess :affected true)]
          (is (= 1 (:ran r)) (pr-str (dissoc r :output)))
          (is (= '[ia.core-test] (get-in r [:affected :selected])) (pr-str (:affected r)))
          (is (some #{'ia.core} (get-in r [:affected :changed-nses])))
          (is (= :green (:status r)))))
      (finally (api/close! sess)))))
(deftest ^:isolated parallel-isolated-runs-shard-and-merge
  ;; the full isolated suite is the wall-time king (~210s at repo scale,
  ;; run at every milestone) — sharding test nses across parallel JVMs
  ;; must return the same merged truth
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'pa.core
                   (str "(ns pa.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f \"F.\" [x] (inc x))\n"
                        "(deftest f-t (is (= 2 (f 1))))\n"))
      (api/ingest! sess 'pb.core
                   (str "(ns pb.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn g \"G.\" [x] (dec x))\n"
                        "(deftest g-t (is (= 0 (g 1))))\n"
                        "(deftest g2-t (is (= -1 (g 0))))\n"))
      (let [r (api/isolated-test-run! sess :parallel 2)]
        (is (= 3 (:ran r)) (pr-str (dissoc r :output)))
        (is (= 3 (:assertions r 0)) (pr-str (dissoc r :output)))
        (is (= :green (:status r)))
        (is (= 2 (:shards r))))
      (testing "a red in any shard surfaces with its details"
        (api/edit-replace! sess 'pb.core 'g "(defn g \"G.\" [x] (+ x 5))"
                           :prompt "breaks both g tests")
        (let [r (api/isolated-test-run! sess :parallel 2)]
          (is (= :red (:status r)))
          (is (pos? (:failures r 0)))
          (is (seq (:all-failing r)) (pr-str (dissoc r :output)))))
      (finally (api/close! sess)))))
(deftest auto-parallel-scales-with-work-and-cores
  ;; sharding only pays above real scale (each shard reloads the whole store);
  ;; default = auto, capped by cores; explicit overrides.
  (let [ap #'testrun/auto-parallel]
    (testing "small suites stay serial — boot overhead beats the gain"
      (is (= 1 (ap 2 8)))
      (is (= 1 (ap 7 8))))
    (testing "real scale shards, capped at 4 and by cores"
      (is (= 2 (ap 20 8)))
      (is (= 4 (ap 50 8)))
      (is (= 1 (ap 50 2)) "a 2-core box never over-parallelizes")
      (is (<= (ap 999 64) 4) "hard cap at 4"))))
(deftest ^:isolated dead-shards-retry-once
  ;; a shard whose JVM dies with NO parseable summary (fork pressure, OOM —
  ;; seen when the sharded suite nests JVM-spawning tests) is an
  ;; environment failure, not a test failure: test failures PARSE. Retry
  ;; exactly those shards once, serially, and merge honestly.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ds.one-test
                   (str "(ns ds.one-test (:require [clojure.test :refer [deftest is]]))\n\n"
                        "(deftest a-t (is (= 1 1)))\n"))
      (api/ingest! sess 'ds.two-test
                   (str "(ns ds.two-test (:require [clojure.test :refer [deftest is]]))\n\n"
                        "(deftest b-t (is (= 2 2)))\n"))
      (let [tries (atom {})
            fake  (fn [_alias _dir grp]
                    (let [n (get (swap! tries update (vec grp) (fnil inc 0))
                                 (vec grp))]
                      (if (= 1 n)
                        {:exit 137 :out "" :err "Killed"}  ; JVM death, no summary
                        {:exit 0 :err ""
                         :out "Ran 1 tests containing 1 assertions.\n0 failures, 0 errors."})))]
        (with-redefs-fn {#'testrun/run-shard! fake}
          #(let [r (api/isolated-test-run! sess :parallel 2)]
             (is (= :green (:status r)) (pr-str r))
             (is (= 2 (:ran r)))
             (is (= 2 (:shard-retries r)))
             (is (every? (fn [[_ n]] (= 2 n)) @tries)
                 "each dead shard retried exactly once"))))
      (finally (api/close! sess)))))
(deftest external-traces-merge-across-shards
  ;; #121: a sharded isolated run is N concurrent JVMs in ONE built dir, each
  ;; writing its own trace file. Reading the trace back = glob + merge.
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-trace-merge"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
        put (fn [suffix m]
              (spit (java.io.File. dir (str testmain/trace-file-prefix suffix ".edn"))
                    (pr-str m)))]
    (testing "no trace files — nil, never an empty-map claim of 'traced nothing'"
      (is (nil? (testrun/read-traces dir))))
    (put "a" '{a.core-test/one #{a.core/f a.core/g}})
    (put "b" '{a.core-test/two #{a.core/h}})
    (testing "every shard's trace is present"
      (is (= '{a.core-test/one #{a.core/f a.core/g}
               a.core-test/two #{a.core/h}}
             (testrun/read-traces dir))))
    (testing "a test seen by two shards UNIONS — a plain merge would drop half"
      (put "c" '{a.core-test/one #{a.core/z}})
      (is (= '#{a.core/f a.core/g a.core/z}
             (get (testrun/read-traces dir) 'a.core-test/one))))
    (testing "unrelated files in the built dir are ignored"
      (spit (java.io.File. dir "deps.edn") "{:paths [\"src\"]}")
      (is (= 2 (count (testrun/read-traces dir)))))))
(deftest ^:isolated external-tier-trace-absorbs-into-the-session
  ;; #121: ^:isolated tests only ever run out-of-process, so the external tier
  ;; is the ONLY place their form trace can come from. This drives the pipe
  ;; end to end — build routes through the store's runner, the runner writes a
  ;; trace beside the build, isolated-test-run! reads it back and absorbs it —
  ;; with a STUB runner, so it tests the wiring rather than the tracer.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'tt.core
                   (str "(ns tt.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f [x] (inc x))\n"
                        "(deftest f-t (is (= 2 (f 1))))\n"))
      ;; a runner that writes a known trace, then hands off to the real
      ;; cognitect main — exactly the delegate-don't-replace shape.
      (api/create-ns! sess 'slopp.testmain
                      :source (str "(ns slopp.testmain\n"
                                   "  \"Stub trace runner (test).\"\n"
                                   "  (:require [clojure.java.io :as io]))\n"
                                   "(def trace-file-prefix \"slopp-trace-\")\n"
                                   "^:unsafe (defn -main [& args]\n"
                                   "  (spit (io/file (str trace-file-prefix \"stub.edn\"))\n"
                                   "        (pr-str '{tt.core-test/f-t #{tt.core/f}}))\n"
                                   "  (apply (requiring-resolve 'cognitect.test-runner/-main) args))\n"))
      (let [r (api/isolated-test-run! sess)]
        (is (= :green (:status r)) (pr-str r))
        (testing "the verdict path is untouched — the runner WRAPS cognitect"
          (is (= 1 (:ran r)) (pr-str r))))
      (testing "the trace the external tier observed lands in the session"
        (is (= '#{tt.core/f} (get (:test-map @sess) 'tt.core-test/f-t))
            (pr-str (:test-map @sess))))
      (finally (api/close! sess)))))
(deftest ^:isolated child-image-rt-calls-reach-the-callers-trace
  ;; THE child-JVM blind spot (#126). Driving a child image runs slopp.rt THERE,
  ;; where the caller's var-wrapping cannot reach. Measured on the live store
  ;; 2026-07-17: slopp.rt/traced-run read 0 covering tests while 213 exercised it
  ;; through image/traced-test-run — and instrument! read 1. The 1 is the
  ;; dangerous one: 0 means "no information" and falls back to running the whole
  ;; closure, while 1 narrows to a single test and calls the result green.
  ;;
  ;; Stand in for the external runner's tracer: publish a sink the way testmain's
  ;; instrument! does around every isolated test, then drive a child image.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'vdemo target)
      (let [touched   (atom #{})
            originals (rt/instrument! [] touched)]
        (try
          (api/test-run! sess 'vdemo)
          (testing "rt that ran in the CHILD is attributed to the caller's test"
            (is (contains? @touched 'slopp.rt/traced-run) (pr-str @touched)))
          (finally (rt/restore! originals))))
      (finally (api/close! sess)))))
(deftest ^:isolated multimethod-tests-trace-to-the-method-forms
  ;; The whole point of the C-wave: a test exercising ONE method of a
  ;; multimethod produces evidence for THAT method's form (keyed by id — D8:
  ;; registrations define no name) plus the defmulti, and NOT for sibling
  ;; methods. Before this, MultiFns were structurally invisible (ifn? but not
  ;; fn?) and a multimethod-heavy project got zero narrowing evidence.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'shapes.core
                   (str "(ns shapes.core)\n\n"
                        "(defmulti area :shape)\n\n"
                        "(defmethod area :square [s] (* (:side s) (:side s)))\n\n"
                        "(defmethod area :circle [c] (* 3 (:r c) (:r c)))\n"))
      (api/ingest! sess 'shapes.core-test
                   (str "(ns shapes.core-test (:require [shapes.core :as c]\n"
                        "                               [clojure.test :refer [deftest is]]))\n\n"
                        "(deftest square-t (is (= 4 (c/area {:shape :square :side 2}))))\n\n"
                        "(deftest circle-t (is (= 12 (c/area {:shape :circle :r 2}))))\n"))
      (let [r (api/test-run! sess 'shapes.core-test)
            _ (is (= 2 (:pass r)) (pr-str r))
            st    (:store @sess)
            forms (store/forms st 'shapes.core)
            fkey  (fn [e] (symbol "shapes.core" (str (or (:name e) (:id e)))))
            [sq-form ci-form] (filter #(nil? (:name %)) forms)
            tmap  (:test-map @sess)
            sq-trace (tmap 'shapes.core-test/square-t)
            ci-trace (tmap 'shapes.core-test/circle-t)]
        (testing "each test records the defmulti it dispatched through"
          (is (contains? sq-trace 'shapes.core/area) (pr-str sq-trace))
          (is (contains? ci-trace 'shapes.core/area)))
        (testing "…and exactly ITS method's form, not the sibling's"
          (is (contains? sq-trace (fkey sq-form)) (pr-str sq-trace))
          (is (not (contains? sq-trace (fkey ci-form))))
          (is (contains? ci-trace (fkey ci-form)) (pr-str ci-trace))
          (is (not (contains? ci-trace (fkey sq-form))))))
      (finally (api/close! sess)))))
(deftest ^:isolated protocols-and-records-track-through-their-vars
  ;; The other half of the polymorphism wave — and it PINS A LIMIT found red
  ;; (2026-07-17): protocol method vars ARE wrapped, but a protocol call site
  ;; compiles an inline cache that hits the interface DIRECTLY when the target
  ;; implements it inline (the common case). g/perim on an inline-implementing
  ;; record NEVER fires the var, so the defprotocol form gets NO runtime
  ;; evidence — an earlier version of this test asserted it would, and the
  ;; oracle said no. Constructors are plain fns, so ->Sq evidence flows to Sq's
  ;; form (D8 names), and everything method-carrying refuses to narrow.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'geo.core
                   (str "(ns geo.core)\n\n"
                        "(defprotocol P \"Perimeter.\" (perim [x] \"P.\"))\n\n"
                        "(defrecord Sq [side]\n  P\n  (perim [_] (* 4 side)))\n"))
      (api/ingest! sess 'geo.core-test
                   (str "(ns geo.core-test (:require [geo.core :as g]\n"
                        "                            [clojure.test :refer [deftest is]]))\n\n"
                        "(deftest perim-t (is (= 8 (g/perim (g/->Sq 2)))))\n"))
      (is (= 1 (:pass (api/test-run! sess 'geo.core-test))))
      (testing "the defrecord form owns its constructors' evidence (D8 names)"
        (let [b (api/query-brief sess 'geo.core 'Sq)]
          (is (= '[geo.core-test/perim-t] (:covered-by b)) (pr-str b))))
      (testing "the defprotocol form has NO evidence — the inline cache bypassed
                its wrapped var. If this ever flips to covered, the tracer
                started seeing direct interface dispatch: update the narrowing
                rule in store/method-carrying? before trusting it"
        (let [b (api/query-brief sess 'geo.core 'P)]
          (is (nil? (:covered-by b)) (pr-str (:covered-by b)))))
      (testing "editing the record falls back to run-everything and says so —
                ->Sq evidence alone would under-select"
        (let [r (api/edit-replace! sess 'geo.core 'Sq
                                   "(defrecord Sq [side]\n  P\n  (perim [_] (+ side side side side)))"
                                   :prompt "same perimeter, different arithmetic")]
          (is (= :all (:affected r)) (pr-str (select-keys r [:affected])))
          (is (:untested r) "the per-write path admits it could not cover this")))
      (finally (api/close! sess)))))

(deftest ^:isolated restart-recovers-from-a-broken-image-handle
  ;; restart is the correctness backstop, and it had a bootstrapping
  ;; dependency on the thing most likely to be broken: it stopped the current
  ;; image (reading that handle) and could adopt a warm spare built under
  ;; older code. When a rename changed the handle's SHAPE, every image-touching
  ;; operation died — including undo AND restart — and only an out-of-process
  ;; MCP restart recovered it. Twice.
  ;;
  ;; A repair tool must not require the thing it repairs to be healthy.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'rr.core "(ns rr.core)\n(defn f [] 41)\n")
      (is (= [41] (api/query-eval sess "(rr.core/f)")))
      (testing "with a garbage image handle, restart still rebuilds"
        (swap! sess assoc :image {:bogus true} :spare nil)
        (is (some? (api/restart! sess)))
        (is (= [41] (api/query-eval sess "(rr.core/f)"))
            "the store reloads into a genuinely fresh image"))
      (finally (api/close! sess)))))

(deftest ^:isolated fallback-verifies-tests-that-reach-the-change
  ;; The "conservative full" fallback ran tests IN the touched PRODUCTION
  ;; namespaces — which contain none. On the real store,
  ;; test_run {ns "slopp.git"} runs 0 tests while FIFTEEN test namespaces
  ;; reach it. So every write lacking trace evidence — every multi-form
  ;; refactor especially — verified NOTHING while reporting a result, and the
  ;; biggest changes are the ones least likely to carry complete evidence.
  ;;
  ;; Naming cannot fix it: slopp.git is covered by slopp.git-projection-test,
  ;; not slopp.git-test. Only the require graph knows. The covering ns here is
  ;; deliberately NOT cv.core-test, so a naming heuristic still finds nothing.
  (let [sess (api/open!)]
    (try
      (is (nil? (:error (api/ingest! sess 'cv.core
                                     "(ns cv.core)\n(defn f [] 41)\n"))))
      (is (nil? (:error (api/ingest!
                         sess 'cv.core.probe-test
                         (str "(ns cv.core.probe-test\n"
                              "  (:require [clojure.test :refer [deftest is]]\n"
                              "            [cv.core :as c]))\n\n"
                              "(deftest covers-f (is (= 41 (c/f))))\n")))))
      (testing "a single-form write with NO trace evidence still verifies"
        (let [r (api/edit-replace! sess 'cv.core 'f "(defn f [] 41)"
                                   :prompt "empty trace map")]
          (is (pos? (:test (:test r)))
              (str "must run the covering test ns found via the graph: "
                   (pr-str (:test r))))))
      (testing "a GROUP write does too — this is where it mattered most"
        (let [r (api/edit-group! sess
                                 [{:action :replace :ns 'cv.core :name 'f
                                   :source "(defn f [] 41)"}]
                                 :prompt "group with no trace evidence")]
          (is (pos? (:test (:test r)))
              (str "a multi-form refactor must not verify nothing: "
                   (pr-str (:test r))))))
      (finally (api/close! sess)))))
