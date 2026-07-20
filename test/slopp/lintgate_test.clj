(ns slopp.lintgate-test
  "The lint gate: a write that INTRODUCES an error-level kondo finding is
  refused; pre-existing errors don't block. Pure — stores built with ingest."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.edit :as edit]
            [slopp.store :as store] [slopp.index :as index] [slopp.edit.lintgate :as lintgate]))

(defn- st [src] (store/ingest (store/empty-store) 'lg.core src))

(def clean "(ns lg.core)\n(defn f [x] x)\n(defn g [] (f 1))\n")
(def bad   "(ns lg.core)\n(defn f [x] x)\n(defn g [] (f 1 2))\n")

(deftest introducing-an-arity-error-is-refused
  (testing "an arity error in a form NOT being written CARRIES (REPL flow)"
    (let [r (lintgate/lint-refusals (st clean) (st bad) ['lg.core] [])]
      (is (nil? (:refuse r)) (pr-str r))
      (is (some #(re-find #"invalid-arity" (name (:type %))) (:carried r))
          (pr-str r))))
  (testing "the SAME error refuses when it is in the form being written"
    (let [g-fid (:id (store/form-named (st bad) 'lg.core 'g))
          r     (lintgate/lint-refusals (st clean) (st bad) ['lg.core] [g-fid])]
      (is (re-find #"in the form you are writing" (str (:refuse r))) (pr-str r))
      (is (re-find #"invalid-arity" (str (:refuse r)))))))

(deftest clean-writes-pass
  (is (nil? (lintgate/lint-refusals (st clean) (st clean) ['lg.core] []))))

(deftest pre-existing-errors-do-not-block
  (testing "base already has the error — the write is not the one to blame"
    (is (nil? (lintgate/lint-refusals (st bad) (st bad) ['lg.core] [])))))
^:unsafe (deftest cross-ns-arity-is-gated-without-a-clj-kondo-nearby
  ;; THE user-project case (#134). Calling ANOTHER namespace's fn with the
  ;; wrong arity is refused only if kondo knows that fn's arities — a CROSS-NS
  ;; fact, which it reads from a cache it resolves from the PROCESS CWD unless
  ;; told otherwise. slopp's own repo happens to have a .clj-kondo/ beside it;
  ;; a user's project does not, and neither does this test's runner (cwd = a
  ;; built temp dir). Probed 2026-07-17: the same source yields
  ;; [:invalid-arity] against a cache and [] without one — so the write was
  ;; ACCEPTED, silently, failing toward "clean".
  ;;
  ;; This passes ONLY because slopp names its own cache dir: nothing in this
  ;; cwd could supply lg.dep/f's arity.
  ;;
  ;; NOTE the gate's shape, checked at the call site rather than assumed:
  ;; rebased-write! passes ns-syms [ns-sym] — the WRITTEN ns alone. So :carried
  ;; means "new errors in forms you didn't write, in the ns you're writing",
  ;; and same-ns arity needs no cache. The cache is what makes calls OUT of the
  ;; linted ns checkable, and those land in :refuse.
  (reset! index/kondo-cache-dir
          (str (java.nio.file.Files/createTempDirectory
                "kondo-gate" (make-array java.nio.file.attribute.FileAttribute 0))))
  (let [base (-> (store/empty-store)
                 (store/ingest 'lg.dep "(ns lg.dep)\n\n(defn f \"F.\" [x] x)\n")
                 (store/ingest 'lg.use
                               (str "(ns lg.use (:require [lg.dep :as d]))\n\n"
                                    "(defn g \"G.\" [] (d/f 1))\n")))
        ;; teach the cache about lg.dep, exactly as writing lg.dep would
        _        (index/lint "(ns lg.dep)\n\n(defn f \"F.\" [x] x)\n")
        gid      (:id (store/form-named base 'lg.use 'g))
        [cand _] (store/replace-node base 'lg.use 'g
                                     (:node (edit/parse-form "(defn g \"G.\" [] (d/f 1 2 3))")))
        r        (lintgate/lint-refusals base cand '[lg.use] #{gid})]
    (testing "calling lg.dep/f with 3 args is REFUSED — the arity came from the
              cache slopp owns, not from anything beside the process"
      (is (:refuse r)
          (str "accepted a cross-ns arity error in a cwd with no .clj-kondo — "
               "which is every user project: " (pr-str r)))
      (is (re-find #"invalid-arity" (:refuse r)) (pr-str r)))))

