(ns slopp.ops-test
  "Tests for the operation surface as a SESSION sees it, rather than for any
  one function.

  `slopp.api` is where the store, the image and the filesystem meet, and the
  bugs that live here are bugs of composition: a materialization that serves
  two-day-old truth because nothing recorded what it was built from, a
  recycled session that can still see the previous tenant, an async boot that
  defers the connection along with the oracle. None of those are visible from
  inside a single function, so these tests open a real session, drive it
  through the public verbs, and assert on what it ends up holding.

  Mostly `^:external` for that reason. The narrower units — the artifact
  cache, history, deps, queries — have their own test namespaces under
  `slopp.api`; what lands here is what needs the whole thing running."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ops :as api] [slopp.ops.testrun :as testrun] [clojure.java.io :as io] [clojure.edn :as edn] [slopp.read.query :as query] [slopp.ops.external :as external] [slopp.store :as store] [clojure.java.shell] [slopp.image.repl :as repl] [slopp.store.artifacts :as artifacts] [slopp.boot :as boot] [clojure.string :as str] [slopp.image :as image] [slopp.ops.engine :as session] [slopp.project.capabilities :as caps])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest ^:external create-ns-modes
  (let [sess (external/open!)]
    (try
      (testing ":source lands a whole namespace in one verified call (folded-in ingest)"
        (let [r (api/create-ns! sess 'cn.core
                                :source "(ns cn.core)\n(defn f [x] (* 2 x))\n(defn g [x] (+ 1 x))\n"
                                :agent "alice")]
          (is (nil? (:error r)))
          (is (= 3 (:forms r)))
          (is (re-find #"defn f" (query/query-source sess 'cn.core)))
          (is (= [10] (api/query-eval sess "(cn.core/f 5)")))))
      (testing ":source carries provenance via :agent"
        (is (some #(= "alice" (:agent %))
                  (query/query-lineage sess 'cn.core 'f))))
      (testing ":requires still scaffolds an empty namespace"
        (let [r (api/create-ns! sess 'cn.util :requires ["[clojure.string :as str]"])]
          (is (nil? (:error r)))
          (is (re-find #"clojure.string" (query/query-source sess 'cn.util)))))
      (testing ":source and :requires are mutually exclusive"
        (is (:error (api/create-ns! sess 'cn.bad
                                    :source "(ns cn.bad)\n"
                                    :requires ["[clojure.string]"]))))
      (finally (api/close! sess)))))

(deftest ^:external operation-surface
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'demo
                   (str "(ns demo)\n"
                        "(defn add [x y] (+ x y))\n"
                        "(defn tainted [a] (swap! a inc))\n"))
      (testing "query.source renders current source from the store (VFS read)"
        (is (re-find #"defn add" (query/query-source sess 'demo))))
      (testing "query.symbol reports effectfulness (D6)"
        (is (false? (:effectful? (query/query-symbol sess 'demo 'add))))
        (is (true? (:effectful? (query/query-symbol sess 'demo 'tainted)))))
      (testing "query.references finds callers"
        ;; tainted is defined AFTER add — the caller must be the later form,
        ;; or the write is (correctly) refused by the cold-load gate (S1b)
        (let [r (api/edit-replace! sess 'demo 'tainted
                                   "(defn tainted [a] (add (swap! a inc) 1))"
                                   :prompt "call add")]
          (is (nil? (:error r)) (pr-str r)))
        (is (seq (query/query-references sess 'demo 'add))))
      (testing "a cycle (add calls tainted, which already calls add) AUTO-DECLARES"
        ;; mutual recursion has no legal order — the pipeline inserts a marked
        ;; declare instead of refusing; the agent writes none
        (let [r (api/edit-replace! sess 'demo 'add
                                   "(defn add [x y] (tainted (atom (+ x y))))"
                                   :prompt "call tainted")]
          (is (nil? (:error r)) (pr-str r))
          (is (re-find #":auto-declare" (query/query-source sess 'demo)))))
      (testing "query.eval asks the live image (the oracle)"
        (is (= [7] (api/query-eval sess "(+ 3 4)"))))
      (testing "edit.replace-form updates store + hot-reloads image"
        (let [r (api/edit-replace! sess 'demo 'tainted "(defn tainted [a] a)"
                                   :prompt "defang")]
          (is (nil? (:error r)))
          (is (= [42] (api/query-eval sess "(demo/tainted 42)")))))
      (testing "query.lineage shows provenance (ingest + replaces, with prompts)"
        (let [lin (query/query-lineage sess 'demo 'tainted)]
          (is (contains? (set (map :op lin)) :ingest))
          (is (contains? (set (map :op lin)) :replace))
          (is (some #(= "defang" (:prompt %)) lin))))
      (testing "build materializes .clj on demand (C1/C6 explicit build)"
        (let [dir (str (Files/createTempDirectory "slopp-build"
                                                  (make-array FileAttribute 0)))]
          (external/build! sess dir)
          (is (= (query/query-source sess 'demo) (slurp (str dir "/src/demo.clj"))))
          (is (.exists (clojure.java.io/file dir "deps.edn")))
          (testing "X4 guard: never into the running system, absolute only, no deps.edn clobber"
            (is (:error (external/build! sess ".")))
            (is (:error (external/build! sess (System/getProperty "user.dir"))))
            (spit (str dir "/deps.edn") "{:paths [\"src\"] :custom true}\n")
            (external/build! sess dir)
            (is (re-find #":custom" (slurp (str dir "/deps.edn")))))))
      (finally (api/close! sess)))))

(deftest parse-test-summary-reads-the-runner-line
  (testing "a green clojure.test summary"
    (is (= {:ran 46 :assertions 1200 :failures 0 :errors 0 :status :green}
           (testrun/parse-test-summary
            "Testing slopp.foo\n\nRan 46 tests containing 1200 assertions.\n0 failures, 0 errors.\n"))))
  (testing "a red summary (singular/plural both parse)"
    (let [r (testrun/parse-test-summary
             "Ran 5 tests containing 10 assertions.\n2 failures, 1 error.\n")]
      (is (= :red (:status r)))
      (is (= 2 (:failures r)))
      (is (= 1 (:errors r)))))
  (testing "no summary present -> nil"
    (is (nil? (testrun/parse-test-summary "boom — the JVM died before any test ran")))))

(deftest ^:external build-routes-test-namespaces-to-test-dir
  ;; a normal Clojure layout: production under src/, tests under test/, off the
  ;; default classpath (a :test alias makes them runnable).
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'proj.core "(ns proj.core)\n(defn f [x] (inc x))\n")
      (api/create-ns! sess 'proj.core-test
                      :requires ["[clojure.test :refer [deftest is]]"
                                 "[proj.core :as c]"])
      (api/add-form! sess 'proj.core-test "(deftest f-t (is (= 2 (c/f 1))))")
      (let [dir (str (Files/createTempDirectory "slopp-testdir"
                                                (make-array FileAttribute 0)))
            f   #(clojure.java.io/file dir %)]
        (external/build! sess dir)
        (testing "production ns under src/, test ns under test/ (not src/)"
          (is (.exists (f "src/proj/core.clj")))
          (is (.exists (f "test/proj/core_test.clj")))
          (is (not (.exists (f "src/proj/core_test.clj")))))
        (testing "deps.edn puts test/ on a runnable :test extra-path"
          (let [m (clojure.edn/read-string (slurp (f "deps.edn")))]
            (is (= ["src"] (:paths m)))
            (is (= ["test"] (get-in m [:aliases :test :extra-paths]))))))
      (finally (api/close! sess)))))

(deftest parse-test-failures-extracts-blocks
  (let [out (str "\nRunning tests in #{\"test\"}\n\nTesting foo.bar-test\n\n"
                 "FAIL in (my-test) (foo/bar_test.clj:12)\n"
                 "rush orders double\n"
                 "expected: (= 1 2)\n"
                 "  actual: (not (= 1 2))\n\n"
                 "ERROR in (other-test) (foo/bar_test.clj:20)\n"
                 "expected: nil\n"
                 "  actual: java.lang.ArithmeticException: boom\n"
                 " at foo (bar.clj:1)\n\n"
                 "Ran 5 tests containing 9 assertions.\n2 failures, 1 errors.\n")
        fs  (testrun/parse-test-failures out)]
    (testing "each FAIL/ERROR block becomes {:test :detail}"
      (is (= ["my-test" "other-test"] (mapv :test fs)))
      (is (re-find #"expected: \(= 1 2\)" (:detail (first fs))))
      (is (re-find #"boom" (:detail (second fs)))))
    (testing "blocks are capped and limited"
      (is (every? #(<= (count (:detail %)) 520) fs))
      (is (= 1 (count (testrun/parse-test-failures out :limit 1)))))))

(deftest ^:external inline-test-stores-build-a-runnable-suite
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'il.core
                   (str "(ns il.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f [x] (inc x))\n"
                        "(deftest f-t (is (= 2 (f 1))))\n"))
      (let [r (external/external-test-run! sess)]
        (is (= :green (:status r)) (pr-str r))
        (is (= 1 (:ran r)) (pr-str r)))
      (finally (api/close! sess)))))

(deftest ^:external build-routes-tests-through-the-trace-runner-when-present
  ;; #121: the external tier can only trace if the built project carries the
  ;; trace runner. PRESENCE in the store is the condition — a store without it
  ;; must still build a deps.edn that runs, so it stays on plain cognitect.
  (let [sess (external/open!)
        tmp  #(str (java.nio.file.Files/createTempDirectory
                    % (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (api/ingest! sess 'tb.core
                   (str "(ns tb.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f [x] (inc x))\n"
                        "(deftest f-t (is (= 2 (f 1))))\n"))
      (testing "no trace runner in the store — the build stays on cognitect"
        (let [dir (tmp "slopp-trace-build")]
          (external/build! sess dir)
          (let [d (slurp (java.io.File. dir "deps.edn"))]
            (is (re-find #"\"-m\" \"cognitect\.test-runner\"" d))
            (is (not (re-find #"slopp\.testmain" d))))))
      (testing "the store provides one — both aliases route through it"
        (api/create-ns! sess 'slopp.image.testmain
                        :source (str "(ns slopp.image.testmain \"Stub: presence is"
                                     " the condition build! reads.\")\n"
                                     "(defn -main [& _args] nil)\n"))
        (let [dir (tmp "slopp-trace-build2")]
          (external/build! sess dir)
          (let [d (slurp (java.io.File. dir "deps.edn"))]
            (is (= 2 (count (re-seq #"\"-m\" \"slopp\.image\.testmain\"" d))) d)
            (is (not (re-find #"\"-m\" \"cognitect\.test-runner\"" d))))))
      (finally (api/close! sess)))))

(deftest ^:external query-commits-rows-carry-title-lines
  ;; frictions #7: needing ONE sha fetched five multi-paragraph milestone
  ;; descriptions — the TOP rung already carried the whole story, inverting
  ;; the ladder. Rows carry the title line (+ :more-lines); the full prose
  ;; is one drill-down away via {commit "dN"}.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'qc.core "(ns qc.core)\n(defn ^:unused-ok f [x] x)\n")
      (external/done! sess :label "w")
      (external/commit-point! sess
                              "The title line\n\nThe body paragraph that must not ride the list.")
      (let [rows (api/query-commits sess)
            row  (first rows)]
        (testing "the list rung is title lines"
          (is (= "The title line" (:description row)) (pr-str row))
          (is (pos? (:more-lines row 0)) (pr-str row)))
        (testing "the drill-down rung is one full milestone"
          (let [full (api/query-commits sess :commit (:commit row))]
            (is (map? full))
            (is (re-find #"body paragraph" (:description full)) (pr-str full)))))
      (finally (api/close! sess)))))

(deftest ^:external ns-delete-retires-an-empty-unreferenced-namespace
  ;; frictions #10: there was NO ns deletion — a mistaken scaffold rode
  ;; every projection and build forever, plus lint noise from its unused
  ;; requires. Retirement mirrors creation: refuse while forms remain
  ;; (naming them), refuse while required (naming the requirers), then one
  ;; :ns-delete delta and the husk is gone from store, image, and rows.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'nd.gone "(ns nd.gone)\n(defn ^:unused-ok f [x] x)\n")
      (api/ingest! sess 'nd.user (str "(ns nd.user (:require [nd.gone :as g]))\n"
                                      "(defn ^:unused-ok h [x] x)\n"))
      (testing "forms remaining → refused, named"
        (let [r (api/delete-ns! sess 'nd.gone)]
          (is (re-find #"\bf\b" (str (:error r))) (pr-str r))
          (is (re-find #"edit_delete_form" (str (:error r))))))
      (api/delete-form! sess 'nd.gone 'f :prompt "clear the husk")
      (testing "still required → refused, requirer named"
        (let [r (api/delete-ns! sess 'nd.gone)]
          (is (re-find #"nd\.user" (str (:error r))) (pr-str r))
          (is (re-find #"ns_remove_require" (str (:error r))))))
      (let [rr (api/remove-require! sess 'nd.user 'nd.gone :prompt "unwire")]
        (is (nil? (:error rr)) (pr-str rr)))
      (testing "a self-named def still blocks deletion (structural, not by-name)"
        (api/ingest! sess 'nd.self "(ns nd.self)\n(def nd.self 1)\n")
        (let [r (api/delete-ns! sess 'nd.self)]
          (is (re-find #"still holds" (str (:error r))) (pr-str r))))
      (testing "empty and unreferenced → deleted everywhere, delta id returned"
        (let [r (api/delete-ns! sess 'nd.gone :prompt "retire the scaffold")]
          (is (= "nd.gone" (:deleted r)) (pr-str r))
          (is (string? (:delta r)) (pr-str r)))
        (is (nil? (get-in (:store @sess) [:namespaces 'nd.gone])))
        (is (= :ns-delete (:op (last (store/deltas (:store @sess)))))))
      (finally (api/close! sess)))))

(deftest await-image-is-a-noop-when-sync-and-surfaces-a-boot-failure-when-async
  ;; the async-boot contract: a synchronously-opened session has no
  ;; ready-promise, so await is instant; an async session whose background
  ;; boot FAILED surfaces that failure at await (not by hanging, not
  ;; silently) — the connection is already up, so the error rides the first
  ;; oracle call.
  (testing "no ready-promise (the sync default) → await returns immediately"
    (let [s (atom {:image :live})]
      (is (identical? s (api/await-image! s)))))
  (testing "a delivered :ok returns the session"
    (let [p (promise) s (atom {:image-ready p :image :live})]
      (deliver p :ok)
      (is (identical? s (api/await-image! s)))))
  (testing "a delivered boot error is rethrown at await"
    (let [p (promise) s (atom {:image-ready p})]
      (deliver p (ex-info "image boot failed" {}))
      (is (thrown-with-msg? Exception #"image boot failed" (api/await-image! s))))))

(deftest ^:external async-image-boot-defers-the-oracle-not-the-connection
  ;; the server-startup fix: with :async-image?, open! returns as soon as the
  ;; store VALUE is loaded (the MCP handshake can complete instantly) while
  ;; the image boots on a background thread. Reads work at once; the oracle
  ;; is awaited on first use. Modelled as the real concurrent scenario: a
  ;; second session opens async onto a first session's live store.
  (let [dir (str (System/getProperty "java.io.tmpdir") "/slopp-async-" (System/nanoTime))
        s1  (external/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! s1 'async.core "(ns async.core)\n(defn twice [x] (* 2 x))\n")
      (let [s (external/open! {:slopp.api/dir dir :slopp.api/async-image? true})]
        (try
          (testing "async mode arms a ready-promise; the store reads immediately"
            (is (some? (:image-ready @s)) "async mode set a ready-promise")
            (is (contains? (:namespaces (:store @s)) 'async.core))
            (is (seq (:project (api/session-brief s)))))
          (testing "await-image! brings the oracle up and it answers"
            (api/await-image! s)
            (is (some? (:image @s)))
            (is (= [10] (api/query-eval s "(async.core/twice 5)"))))
          (finally (api/close! s))))
      (finally
        (api/close! s1)
        (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest client-build-deps-injects-slopp-toolchain-for-client-stores
  ;; slopp self-provisions its client toolchain at BUILD time — never user
  ;; manifest deltas (D-web-contracts dogfood finding): malli into the runtime
  ;; channel, the configured compiler into the client channel — but only when the
  ;; store carries client code, so a non-client build stays byte-identical.
  (testing "a :cljs store gets malli (runtime) + the compiler (client)"
    (let [st (-> (store/empty-store)
                 (store/ingest 'app.view "(ns app.view)\n(defn ^:export main [] 1)\n"))
          st (first (store/record-module-platform st "app.view" :cljs))
          provided (external/client-build-deps st)]
      (is (contains? (:runtime provided) 'metosin/malli) (pr-str provided))
      (is (contains? (:client provided) 'org.clojure/clojurescript) (pr-str provided))))
  (testing "a non-client store injects nothing"
    (let [st (store/ingest (store/empty-store) 'app.core "(ns app.core)\n(defn f [] 1)\n")]
      (is (= {} (:runtime (external/client-build-deps st))))
      (is (= {} (:client (external/client-build-deps st)))))))

(deftest ^:external build-injects-slopp-client-toolchain-without-manifest-deps
  ;; the payoff of the two-config split: a store with client code and an EMPTY
  ;; user manifest still builds a deps.edn carrying slopp's compiler + malli.
  ;; The agent added nothing — slopp provisions its own plumbing at build time,
  ;; versioned centrally, with no :deps-add delta in the user's history.
  (let [sess (external/open!)
        dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-toolchain"
                   (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (api/create-ns! sess 'cbt.view
                      :source "(ns cbt.view)\n\n(defn ^:export main \"Entry.\" [] 1)\n"
                      :platform "cljs")
      (is (nil? (:error (external/build! sess dir))))
      (let [d (edn/read-string (slurp (io/file dir "deps.edn")))]
        (is (contains? (:deps d) 'metosin/malli) (pr-str d))
        (is (contains? (get-in d [:aliases :cljs :extra-deps]) 'org.clojure/clojurescript)
            (pr-str d)))
      (finally
        (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
          (rm! (io/file dir)))
        (api/close! sess)))))

(deftest ^:external build-stamps-the-head-it-was-materialized-from
  ;; Derived artifacts serve old truth silently: `uber` jarred a two-day-old
  ;; materialization and printed success. The mtime heuristic that first guarded
  ;; it was wrong twice over — a directory's mtime does not move when nested
  ;; files change, and a live session touches store.db constantly. slopp already
  ;; has the exact provenance token, the head delta id, so the materialization
  ;; states what it was built FROM and the check stops being a guess.
  (let [sess (external/open!)
        dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-stamp" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (api/create-ns! sess 'stamp.core :source "(ns stamp.core)\n\n(defn f \"F.\" [x] x)\n")
      (external/build! sess dir)
      (let [stamp (io/file dir ".slopp-head")
            head  (:id (last (store/deltas (:store @sess))))]
        (is (.exists stamp) "the materialization records its provenance")
        (is (= head (slurp stamp))
            "and it is exactly the head delta the store stood at"))
      (finally
        (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
          (rm! (io/file dir)))
        (api/close! sess)))))

(deftest ^:external prune-requires-drops-dead-and-keeps-load-bearing
  ;; The done-point prunes a require that is genuinely dead, but KEEPS (marked
  ;; ^:side-effect) one whose target registers something a cold load would lose
  ;; — the in-image suite can't see that break, because the registration is
  ;; already loaded in the live image. So the decision is static, not just the
  ;; in-image verdict: an orphaned registering target is load-bearing.
  (let [sess (external/open!)]
    (try
      (api/create-ns! sess 'pz.pure :source "(ns pz.pure)\n(defn g [] 1)\n")
      (api/create-ns! sess 'pz.reg
                      :source "(ns pz.reg)\n(defmulti area identity)\n(defmethod area :sq [_] 42)\n")
      (api/create-ns! sess 'pz.app
                      :source "(ns pz.app (:require [pz.pure :as p] [pz.reg :as r]))\n(defn f [] 1)\n")
      (let [r (api/prune-requires! sess 'pz.app :agent "t")]
        (testing "the dead pure require is pruned; the registering one is kept"
          (is (= '[pz.pure] (:pruned r)) (pr-str r))
          (is (= '[pz.reg]  (:kept r))   (pr-str r)))
        (testing "the ns form drops the dead require and marks the kept one"
          (let [ns-src (:source (query/query-brief sess 'pz.app 'pz.app))]
            (is (not (re-find #"pz\.pure" (str ns-src))) (str ns-src))
            (is (re-find #":side-effect" (str ns-src)) (str ns-src))
            (is (re-find #"pz\.reg" (str ns-src)) (str ns-src)))))
      (testing "a second prune is a no-op: the kept require is marked, not re-tried"
        (let [r2 (api/prune-requires! sess 'pz.app :agent "t")]
          (is (= [] (:pruned r2)) (pr-str r2))
          (is (= [] (:kept r2)) (pr-str r2))))
      (finally (api/close! sess)))))

(deftest ^:external a-recycled-session-cannot-see-the-previous-tenant
  ;; The isolation an image gives is the whole reason the external tier exists,
  ;; and reuse is only acceptable if it survives intact. The previous attempt
  ;; at cheaper images (the warm pool) also had to prove this, and its test —
  ;; pooled-open-stays-isolated — is the shape being repeated here.
  ;;
  ;; The :reuses assertion is the load-bearing one. Without it this test would
  ;; pass trivially the day recycling silently stopped happening, which is the
  ;; vacuous-guard failure this codebase has been bitten by before.
  (repl/drain-parked!)
  (let [a (external/open!)]
    (api/ingest! a 'tenant.one "(ns tenant.one)\n(defn secret \"S.\" [] 42)\n")
    (is (= 42 (first (repl/eval! (:image @a) "(tenant.one/secret)")))
        "the first tenant really did load into its image")
    (api/close! a))
  (let [b (external/open!)]
    (try
      (is (pos? (:reuses (:image @b) 0))
          "the second session must actually REUSE an image, or this proves nothing")
      (is (nil? (first (repl/eval! (:image @b) "(find-ns 'tenant.one)")))
          "and must not be able to see the previous tenant's namespaces")
      (is (nil? (first (repl/eval! (:image @b) "(resolve 'tenant.one/secret)"))))
      (api/ingest! b 'tenant.two "(ns tenant.two)\n(defn v \"V.\" [] 7)\n")
      (is (= 7 (first (repl/eval! (:image @b) "(tenant.two/v)")))
          "a recycled image is fully WORKING, not merely empty")
      (finally (api/close! b) (repl/drain-parked!)))))

(deftest ^:external store-health-counts-the-artifact-cache
  ;; store_health exists because uncounted bytes accumulate — a tree snapshot
  ;; reached 94% of a 344MB journal across 239 milestones with nothing
  ;; measuring it. Moving the compiled bundle out of the delta log and into a
  ;; directory no tool reported would have been that same mistake with a
  ;; better hiding place.
  (let [dir  (str (Files/createTempDirectory
                   "slopp-health" (make-array FileAttribute 0)))
        sess (external/open! {:slopp.api/dir dir})]
    (try
      (api/create-ns! sess 'health.core :source "(ns health.core)\n\n(defn f \"F.\" [x] x)\n")
      (let [kept   (artifacts/put! dir (.getBytes "referenced\n" "UTF-8")
                                   {:kind :build :tool "compile_client"})
            _      (artifacts/put! dir (.getBytes "left behind\n" "UTF-8") {:kind :build})
            _      (swap! sess update :store
                          #(first (store/record-artifact % "public/x.js" kept)))
            health (external/store-health sess)]
        (testing "the journal is still reported"
          (is (pos? (get-in health [:deltas :n]))))
        (testing "and so is the cache the journal no longer carries"
          (is (= 2 (get-in health [:artifacts :n])))
          (is (= (:bytes kept) (get-in health [:artifacts :live :bytes]))
              "measured against the SESSION's store, not an empty one")
          (is (= 1 (get-in health [:artifacts :orphaned :n]))
              "and the reclaimable half is called out separately")))
      (finally
        (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
          (api/close! sess)
          (rm! (io/file dir)))))))

(deftest ^:external build-reports-artifacts-it-could-not-materialize
  ;; The materialization loop swallowed misses: `when-let` on :bytes skipped
  ;; the file and the build returned {:built …} regardless. That is the
  ;; failure the recipe exists to make legible, reproduced one line under a
  ;; comment saying so — a cold clone would compile against a tree quietly
  ;; missing a file and hit it much later as something unrelated.
  (let [dir  (str (Files/createTempDirectory
                   "slopp-build-miss" (make-array FileAttribute 0)))
        out  (str (Files/createTempDirectory
                   "slopp-build-out" (make-array FileAttribute 0)))
        sess (external/open! {:slopp.api/dir dir})]
    (try
      (api/create-ns! sess 'miss.core :source "(ns miss.core)\n\n(defn f \"F.\" [x] x)\n")
      (let [entry (artifacts/put! dir (.getBytes "compiled bytes\n" "UTF-8")
                                  {:kind :build :tool "compile_client"})]
        (swap! sess update :store
               #(first (store/record-artifact % "public/cljs/main.js" entry)))
        (testing "cache hit: the file lands and the build stays quiet"
          (let [r (external/build! sess out)]
            (is (empty? (:missing-artifacts r)) (pr-str r))
            (is (.exists (io/file out "public/cljs/main.js")))))
        (testing "cache cleared: the build REPORTS the gap rather than omitting it silently"
          (.delete (artifacts/cache-file dir (:sha entry)))
          (.delete (io/file out "public/cljs/main.js"))
          (let [r (external/build! sess out)
                m (first (:missing-artifacts r))]
            (is (= 1 (count (:missing-artifacts r))) (pr-str r))
            (is (= "public/cljs/main.js" (:path m)))
            (is (re-find #"compile_client" (str (:refill m))) (pr-str m))
            (is (not (.exists (io/file out "public/cljs/main.js")))
                "the file really is absent — the report is describing reality"))))
      (finally
        (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
          (api/close! sess)
          (rm! (io/file dir))
          (rm! (io/file out)))))))

(deftest ^:external creating-a-namespace-that-shadows-a-classpath-one-warns
  ;; Found by bricking a real project. `slopp-ui` created `slopp.review.views`
  ;; holding two of its own views; a project's MCP server runs the FULL slopp
  ;; jar and `slopp.boot` loads store namespaces FIRST, so at the next boot
  ;; slopp's own `slopp.review.pages` died on `No such var: views/module-graph`.
  ;; The store was then unopenable by the only tool that could remove the
  ;; namespace again.
  ;;
  ;; It WARNS rather than refuses, and that distinction is the whole design:
  ;; overriding a slopp namespace is a supported capability, not an accident.
  ;; `slopp.image.testmain` is exactly how a store supplies its own trace
  ;; runner — `verification-test/external-tier-trace-absorbs-into-the-session`
  ;; does it on purpose — so a guard that refused would have broken a
  ;; documented extension point to prevent a naming mistake.
  (let [sess (external/open!)]
    (try
      (testing "a name slopp itself owns is created, and SAYS it will shadow"
        ;; `slopp.web.html` rather than the `slopp.review.views` of the incident:
        ;; that namespace does not exist here any more — the reviewer UI moved
        ;; out — and a fixture naming a namespace slopp no longer owns asserts
        ;; nothing while still passing today, because the CHECK is classpath
        ;; ownership. Pick one that is load-bearing and going nowhere.
        (let [r (api/create-ns! sess 'slopp.web.html :source "(ns slopp.web.html)\n")
              w (first (filter #(= :shadows-classpath-ns (:kind %)) (:warnings r)))]
          (is (nil? (:error r)) "overriding is legitimate — it must still be possible")
          (is (some? w) (pr-str r))
          (is (re-find #"slopp\.web\.html" (str (:message w)))
              "name it: the agent chose the name and has to know which one bites")
          (is (re-find #"(?i)shadow" (str (:message w))))))
      (testing "a dependency's namespace warns for the same reason"
        (let [r (api/create-ns! sess 'clojure.string :source "(ns clojure.string)\n")]
          (is (some #(= :shadows-classpath-ns (:kind %)) (:warnings r)) (pr-str r))))
      (testing "a name the project owns warns about nothing"
        ;; the root slopp-ui settled on, and the reason it is safe: `slopp-ui`
        ;; is a different SEGMENT from `slopp`, so it cannot collide
        (let [r (api/create-ns! sess 'slopp-ui.views :source "(ns slopp-ui.views)\n")]
          (is (nil? (:error r)) (pr-str r))
          (is (empty? (filter #(= :shadows-classpath-ns (:kind %)) (:warnings r)))
              (pr-str r))))
      (finally (api/close! sess)))))

(deftest a-whole-store-check-supersedes-a-stale-episode-verdict
  ;; friction 14. `done` reports :test-status :none whenever the episode's
  ;; changed forms have no covering tests — a rename, a docstring, a :cljs
  ;; edit. commit_point then reaches back to the last done that DID judge,
  ;; which can be arbitrarily old, and no amount of new work supersedes it:
  ;; each new done judges nothing either, so the store gets greener while the
  ;; milestone stays refused. full_check ALREADY records its verdict as a
  ;; :verify delta scoped :full-check; nothing read it.
  (let [red   (first (store/record-done (store/empty-store) "r"
                                        :findings {:test-status :red :failures 2}))
        write (store/record-verification red '[some.ns] {:status :green})
        full  (store/record-verification write '[a.b]
                                         {:status :green :scope :full-check
                                          :namespaces 3 :lint-errors 0})]
    (is (= :red (:test-status (api/last-judged-done red))))

    (testing "a per-write :verify is not a whole-store judgement"
      ;; record-verification lands on ordinary writes too, and those are
      ;; form-scoped. Only :scope :full-check judged the whole store.
      (is (= :red (:test-status (api/last-judged-done write)))))

    (testing "a green full_check supersedes the stale episode verdict"
      (is (= :green (:test-status (api/last-judged-done full))))
      (is (= :full-check (:scope (api/last-judged-done full)))))

    (testing "informational counts stay OUT of the verdict"
      ;; commit_point derives its refusal reason from whichever keys are
      ;; present and non-zero, so a namespace count would make a refusal say
      ;; "namespaces" as though that were the thing that fired.
      (is (nil? (:namespaces (api/last-judged-done full)))))

    (testing "and a later done supersedes the full_check in turn"
      (let [after (first (store/record-done full "r2"
                                            :findings {:test-status :red :failures 1}))]
        (is (= :red (:test-status (api/last-judged-done after))))))))

(deftest slopp-supplies-the-framework-to-stores-that-USE-it
  ;; D-framework-injection. A store does not declare io.github.nvoxland/slopp-web
  ;; and slopp supplies it, the way it already supplies nREPL and malli
  ;; (repl/inherent-deps) and the cljs compiler (client-build-deps, right above).
  ;; That removes the state slopp-ui was in for a day: pinned 0.1.3 while the fix
  ;; made FOR it sat in the host at 0.1.4.
  ;;
  ;; Part 2: what is supplied is FILES, not a coord. slopp-web is never
  ;; published, so a coord names something only the machine that ran
  ;; slim-install can resolve — portable in appearance and not in fact.
  ;;
  ;; The two CONDITIONS are unchanged by that, and each is load-bearing in a
  ;; different direction:
  ;;
  ;;   USES but does not DEFINE. slopp's own store CONTAINS slopp.web.*, so
  ;;   vendoring into it would put a second copy on the classpath ahead of the
  ;;   materialized one — `src` is the FIRST classpath entry, so the copy would
  ;;   win, and slopp would be testing its own shipped framework instead of the
  ;;   code being edited.
  ;;
  ;;   USES, not merely exists. A store with no web code needs nothing, and
  ;;   writing files into every image would cost every fixture boot.
  (let [uses (-> (store/empty-store)
                 (store/ingest 'app.web
                               (str "(ns app.web (:require [slopp.web :as web]))\n"
                                    "(defn handler \"H.\" [req] (web/handle! req))\n")))
        plain (-> (store/empty-store)
                  (store/ingest 'app.core "(ns app.core)\n(defn f [x] x)\n"))
        defines (-> (store/empty-store)
                    (store/ingest 'slopp.web "(ns slopp.web)\n(defn handle! [r] r)\n")
                    (store/ingest 'app.web
                                  (str "(ns app.web (:require [slopp.web :as web]))\n"
                                       "(defn g \"G.\" [r] (web/handle! r))\n")))
        files {"slopp/web.clj" "(ns slopp.web)"}]
    (testing "a store that USES the framework is given the FILES, not a coord"
      (is (= files (session/framework-injection uses files))))
    (testing "a store with no web code gets nothing"
      (is (nil? (session/framework-injection plain files))))
    (testing "and a store that DEFINES slopp.web gets nothing — vendoring there
              would shadow the very code being edited, since src wins"
      (is (nil? (session/framework-injection defines files))))
    (testing "a host that cannot supply the framework (a checkout, a clojure -M
              run) vendors nothing rather than half of it"
      (is (nil? (session/framework-injection uses nil)))
      (is (nil? (session/framework-injection uses {}))))))

(deftest ^:external build-supplies-the-framework-a-store-no-longer-declares
  ;; The build half of D-framework-injection. Once a store stops declaring
  ;; io.github.nvoxland/slopp-web, the BUILT app still has to get it — otherwise
  ;; removing the declaration produces an app with no framework at all, which is
  ;; why the halves are staged in this order.
  ;;
  ;; Part 2: it arrives as FILES, not a coord. slopp-web is never published to a
  ;; remote, so a coord in the generated deps.edn would name something only the
  ;; machine that ran slim-install can resolve — a build that ships broken to
  ;; anywhere else, and one that looks fine here.
  (let [sess (external/open!)
        dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-framework"
                   (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (api/create-ns! sess 'fw.app
                      :source (str "(ns fw.app (:require [slopp.web :as web]))\n\n"
                                   "(defn ^:export handler \"H.\" [req] (web/handle! req))\n"))
      (is (nil? (:error (external/build! sess dir))))
      (let [facade (io/file dir "src" "slopp" "web.clj")
            d      (edn/read-string (slurp (io/file dir "deps.edn")))]
        ;; nil when this suite runs from a checkout rather than a jar — there is
        ;; no framework to vendor and half of one would be worse than none.
        ;; Assert the CONTRACT that holds either way.
        (if (boot/framework-files)
          (testing "the framework is IN the tree, so the app needs no repository"
            (is (.exists facade) (str "expected " facade))
            (is (str/includes? (slurp facade) "(ns slopp.web")
                "the vendored file must be the real source, not a stub")
            (is (.exists (io/file dir "src" "slopp" "web" "router.clj"))
                "the whole framework travels, not just the facade"))
          (testing "nothing to vendor, so nothing is written"
            (is (not (.exists facade)))))
        (testing "and it is never a COORD — that would resolve only where
                  slim-install has run"
          (is (nil? (get-in d [:deps 'io.github.nvoxland/slopp-web]))
              (pr-str (:deps d))))
        (testing "but what the framework itself REQUIRES is declared, or the app
                  ships source it cannot load — the build path has the same hole
                  the image had, found by slopp-ui removing the coord"
          (when (boot/framework-files)
            (doseq [[lib _] (boot/framework-deps)]
              (is (contains? (:deps d) lib)
                  (str lib " missing from the built deps: " (pr-str (:deps d))))))))
      (finally
        (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
          (rm! (io/file dir)))
        (api/close! sess)))))

(deftest ^:external a-framework-using-store-survives-a-RESTART
  ;; Two bugs, one test, and the second was invisible to the first version of it.
  ;;
  ;; (1) Vendoring was wired into session creation alone, so every image AFTER
  ;; the first booted with an empty src/ — and `fresh-image!` is on the path of
  ;; restart, deps_add/deps_remove, branch switch, ns_rename and the D5
  ;; staleness heal.
  ;;
  ;; (2) Vendoring copies SOURCE and discards the pom. The framework's own
  ;; requires — garden, hiccup, cheshire, http-kit — came from that pom, so the
  ;; files landed correctly and then failed INSIDE them. Reported from slopp-ui:
  ;; "Could not locate garden/core.clj", raised at slopp.web.css.
  ;;
  ;; Both stayed invisible because a store still declaring the coord got source
  ;; AND deps from ~/.m2 regardless.
  ;;
  ;; So the fake framework REQUIRES something external, and the assertion is
  ;; that the store LOADS. slopp-ui's correction, and it is the whole point: the
  ;; "resolves from the image dir, no ~/.m2" property was fully satisfied at the
  ;; moment their store was unloadable. Where a file comes from is not whether
  ;; the framework works. An earlier cut of this test used a dependency-free
  ;; fake and passed green against exactly bug (2).
  (let [fake-web (str "(ns slopp.web (:require [garden.core :as garden]))\n"
                      "(defn handle! \"H.\" [r] (garden/css [:a {:x 1}]) r)\n")]
    (with-redefs [boot/framework-files (constantly {"slopp/web.clj" fake-web})
                  boot/framework-deps  (constantly '{garden/garden {:mvn/version "1.3.10"}})]
      (let [sess (external/open!)
            vendored? (fn [] (.exists (io/file (:dir (:image @sess))
                                               "src" "slopp" "web.clj")))]
        (try
          ;; into the store VALUE: a store cannot ingest code requiring a
          ;; framework its current image cannot load, and priming a RUNNING
          ;; image's dir does nothing — a JVM caches a relative classpath dir
          ;; that did not exist at launch. Which is also why vendoring has to
          ;; precede process start.
          (swap! sess update :store store/ingest 'fw.app
                 (str "(ns fw.app (:require [slopp.web :as web]))\n"
                      "(defn h \"H.\" [r] (web/handle! r))\n"))
          (testing "the store really does use the framework"
            (is (some? (session/framework-injection
                        (:store @sess) (boot/framework-files)))))
          (testing "a RESTART gives the new image the framework AND what the
                    framework itself requires — the store LOADS, which is the
                    property; the file being present is not"
            (api/restart! sess)
            (is (vendored?) "the restarted image booted without the framework")
            (is (nil? (image/load-ns! (:image @sess) (:store @sess) 'fw.app))
                "loading is the real property — a vendored file that cannot
                 resolve its own requires is a dead store"))
          (testing "and again — one image is a fluke, two is the property"
            (api/restart! sess)
            (is (vendored?))
            (is (nil? (image/load-ns! (:image @sess) (:store @sess) 'fw.app))))
          (finally (api/close! sess)))))))

(deftest ^:external a-built-web-app-RUNS-outside-slopp-entirely
  ;; SHAPE was never the question. Every build! test here asserted the tree
  ;; materializes — files present, deps.edn contains X — and all of them passed
  ;; while a built app died on its first require, because vendoring copies
  ;; source and the pom that carried garden/hiccup/cheshire/http-kit was
  ;; discarded with the coord.
  ;;
  ;; The consumer found it, by running the tree. That is the wrong dependency:
  ;; slopp-ui is ONE app, the next one will not report this well, and slopp's
  ;; correctness should not rest on a consumer happening to look. So slopp owns
  ;; a minimal web app of its own and RUNS it.
  ;;
  ;; A fresh JVM with no slopp on the classpath — `clojure -M` in the tree — is
  ;; the point: an image would prove nothing, since the image is the OTHER path
  ;; and vendors separately. This is what a user's deployment sees.
  ;;
  ;; The framework is FAKED, and the fake requires something external, because
  ;; this suite runs from a checkout where boot/framework-files is nil. A first
  ;; cut branched on that and skipped the run — leaving a behaviour test that
  ;; asserted shape in the only environment it ever executes in, which is the
  ;; defect it exists to catch. The stand-in has the property under test:
  ;; requires of its own that the built deps.edn must declare.
  (with-redefs [boot/framework-files
                (constantly
                 {"slopp/web/css.clj"
                  (str "(ns slopp.web.css (:require [garden.core :as garden]))\n"
                       "(defn css-response \"C.\" [rules]\n"
                       "  {:status 200 :body (garden/css rules)})\n")})
                boot/framework-deps
                (constantly '{garden/garden {:mvn/version "1.3.10"}})]
    (let [sess (external/open!)
          dir  (str (Files/createTempDirectory "slopp-runs"
                                               (make-array FileAttribute 0)))]
      (try
        ;; into the store VALUE: this session's image was booted on an empty
        ;; store and so vendored nothing, and create-ns! hot-loads — it would
        ;; fail on the require and the ns would never land. build! reads the
        ;; store, which is what is under test here.
        (swap! sess update :store store/ingest 'runs.app
               (str "(ns runs.app\n"
                    "  (:require [slopp.web.css :as css]))\n\n"
                    "(defn ^:export stylesheet \"S.\" []\n"
                    "  (css/css-response [[:body {:color \"red\"}]]))\n"))
        (is (nil? (:error (external/build! sess dir))))
        (testing "the framework source is IN the tree"
          (is (.exists (io/file dir "src" "slopp" "web" "css.clj"))))
        (testing "and what the framework itself requires is declared, or the
                  tree carries source it cannot load"
          (is (contains? (:deps (edn/read-string (slurp (io/file dir "deps.edn"))))
                         'garden/garden)))
        (testing "so it LOADS in a fresh JVM that has never heard of slopp —
                  the assertion shape assertions cannot make"
          (let [r (clojure.java.shell/sh
                   "clojure" "-M" "-e" "(require 'runs.app) (println :LOADED-OK)"
                   :dir dir)]
            (is (zero? (:exit r))
                (str "a built app must run outside slopp.\nexit " (:exit r)
                     "\nout: " (:out r) "\nerr: " (:err r)))
            (is (str/includes? (:out r) ":LOADED-OK")
                (str "out: " (:out r) "\nerr: " (:err r)))))
        (testing "and it FIRES — without the framework's deps the same tree
                  fails, which is the bug slopp-ui hit. A green run here proves
                  nothing unless the red one is reachable, and every defect this
                  wave was hidden by a check that could not fail"
          (let [dir2 (str (Files/createTempDirectory
                           "slopp-runs-nodeps" (make-array FileAttribute 0)))]
            (try
              (with-redefs [boot/framework-deps (constantly nil)]
                (external/build! sess dir2))
              (let [r (clojure.java.shell/sh
                       "clojure" "-M" "-e" "(require 'runs.app)" :dir dir2)]
                (is (not (zero? (:exit r)))
                    "vendored source with no deps declared must NOT load")
                (is (str/includes? (:err r) "garden")
                    (str "and it must fail on the framework's own require: "
                         (:err r))))
              (finally
                (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f)))
                          (.delete f))]
                  (rm! (io/file dir2)))))))
        (finally
          (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
            (rm! (io/file dir)))
          (api/close! sess))))))

(deftest ^:external the-warm-spare-is-ADOPTED-and-carries-the-framework
  ;; slopp-ui measured this and slopp should assert it — the same principle as
  ;; the build-and-run test. Their instrument, kept because it is better than
  ;; anything timing-based: compare the image JVM's ABSOLUTE start time against a
  ;; mark taken before the restart was requested.
  ;;
  ;;   negative → the JVM existed before I asked → a warmed spare was adopted
  ;;   positive → it booted during the restart → no spare
  ;;
  ;; Immune to round-trip latency, which is what makes uptime-based timing
  ;; useless here. Their before/after on the two jars: +2696 (fresh boot) then
  ;; -6288 / -11256 / -8643 (adopted), three for three.
  ;;
  ;; Adoption alone is worth nothing — an adopted image MISSING the framework
  ;; trades a restart bug for a subtler one, so this asserts both. That pairing
  ;; is the actual claim the one-door change makes.
  (with-redefs [boot/framework-files
                (constantly {"slopp/web.clj"
                             "(ns slopp.web)\n(defn handle! \"H.\" [r] r)\n"})
                boot/framework-deps (constantly nil)]
    (let [sess (external/open! {:slopp.api/warm-spare? true})]
      (try
        (swap! sess update :store store/ingest 'sp.app
               (str "(ns sp.app (:require [slopp.web :as web]))\n"
                    "(defn h \"H.\" [r] (web/handle! r))\n"))
        ;; First restart warms a spare from a store that NOW needs the framework.
        ;; The spare standing at open was warmed from an empty store and must be
        ;; discarded rather than adopted — that mismatch is its own hazard, and
        ;; asserting adoption on this restart would be asserting the bug.
        (api/restart! sess)
        ;; WAIT for the spare's JVM to exist before marking t0. start-spare!
        ;; returns a future, so the boot happens in the BACKGROUND — and their
        ;; samples were 6-11s apart by human pacing, which hid this. In a tight
        ;; loop the mark lands before the spare has launched, and a correctly
        ;; ADOPTED image then reads as +29ms: the right answer to the wrong
        ;; question. Deref blocks until the handle is real.
        (some-> (:spare @sess) deref)
        (let [t0 (System/currentTimeMillis)]
          (api/restart! sess)
          (let [started (first (repl/eval!
                                (:image @sess)
                                (str "(.getStartTime (java.lang.management."
                                     "ManagementFactory/getRuntimeMXBean))")))]
            (testing "the spare was ADOPTED — its JVM predates the request"
              (is (number? started) (pr-str started))
              (is (< started t0)
                  (str "image JVM started " (- started t0) "ms relative to the"
                       " restart request; negative means adopted, positive"
                       " means it booted during the restart and the spare was"
                       " lost")))))
        (testing "and the adopted image CARRIES the framework — adoption without
                  it is the worse bug, not the fix"
          (is (nil? (image/load-ns! (:image @sess) (:store @sess) 'sp.app))))
        (finally (api/close! sess))))))

(deftest ^:external capabilities-config-validates-at-write
  (let [sess (external/open!)]
    (try
      (testing "an unknown capability key is refused with teaching"
        (let [r (api/config-file! sess "capabilities" :key "web.prot" :value "8080"
                                  :prompt "typo'd key")]
          (is (re-find #"web\.prot" (str (:error r))) (pr-str r))
          (is (re-find #"query_capabilities" (str (:error r))) (pr-str r))))
      (testing "a bad value is refused with the type teaching"
        (let [r (api/config-file! sess "capabilities" :key "web.port" :value "banana"
                                  :prompt "bad port")]
          (is (re-find #"integer" (str (:error r))) (pr-str r))
          (is (nil? (get-in (:store @sess) [:config "capabilities" :values "web.port"]))
              "the refused value never landed")))
      (testing "a good value lands and takes effect"
        (let [r (api/config-file! sess "capabilities" :key "web.port" :value "7357"
                                  :prompt "real port")]
          (is (nil? (:error r)) (pr-str r))
          (is (= 7357 (caps/effective (:store @sess) "web.port")))))
      (testing "a wildcard-governed key is known, not alien"
        (let [r (api/config-file! sess "capabilities" :key "web.auth.groups.admin.members" :value "alice,bob"
                                  :prompt "a group")]
          (is (nil? (:error r)) (pr-str r))
          (is (= #{"alice" "bob"} (caps/effective (:store @sess) "web.auth.groups.admin.members")))))
      (testing "a key under an undeclared owner is refused like any unknown key"
        (let [r (api/config-file! sess "capabilities" :key "groups.admin.members" :value "alice"
                                  :prompt "the retired spelling")]
          (is (re-find #"is not a capability" (str (:error r))) (pr-str r))))
      (testing "unset returns to the default"
        (api/config-file! sess "capabilities" :key "web.port" :unset true
                          :prompt "back to default")
        ;; web.port's declared default is nil now — serve! owns the 8080 and the
      ;; dev server derives, so "returns to the default" means returns to unset
      (is (nil? (caps/effective (:store @sess) "web.port"))))
      (finally (api/close! sess)))))

(deftest ^:external config-writes-say-whether-anything-validated-them
  ;; `capabilities` is the ONLY path with a registry behind it. Every other
  ;; path — rules, gates, client — records the key and value as given and
  ;; returns a result indistinguishable from a validated one. Saying which is
  ;; which is D-surface-honesty at config grain.
  (let [sess (external/open!)]
    (try
      (testing "a capabilities write was checked against the registry"
        (let [r (api/config-file! sess "capabilities" :key "web.port" :value "7357"
                                  :prompt "real port")]
          (is (= [:registry] (:verified r)) (pr-str r))
          (is (= [] (:unverified r)) (pr-str r))))
      (testing "any other path is recorded UNVALIDATED, and says so"
        (let [r (api/config-file! sess "rules" :key "key-typos" :value "off"
                                  :prompt "quiet that rule")]
          (is (= [] (:verified r)) (pr-str r))
          (is (= [:schema] (:unverified r)) (pr-str r))
          (is (re-find #"capabilities" (str (:note r)))
              "the note must name the one path that IS validated")))
      (finally (api/close! sess)))))
