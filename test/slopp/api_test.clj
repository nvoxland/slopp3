(ns slopp.api-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api] [slopp.api.testrun :as testrun] [clojure.java.io :as io] [clojure.edn :as edn] [slopp.api.query :as query] [slopp.api.external :as external] [slopp.store :as store] [clojure.java.shell])
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
        (api/create-ns! sess 'slopp.testmain
                        :source (str "(ns slopp.testmain \"Stub: presence is the"
                                     " condition build! reads.\")\n"
                                     "(defn -main [& _args] nil)\n"))
        (let [dir (tmp "slopp-trace-build2")]
          (external/build! sess dir)
          (let [d (slurp (java.io.File. dir "deps.edn"))]
            (is (= 2 (count (re-seq #"\"-m\" \"slopp\.testmain\"" d))) d)
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
