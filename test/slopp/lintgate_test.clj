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

(deftest a-red-first-test-may-name-an-arity-that-does-not-exist-yet
  ;; Four instances across two stores: twice here on 2026-08-04 (adding a
  ;; parameter to store.render/source-path and to build/deps-edn) and twice in
  ;; slopp-ui, most recently views/hub-picker gaining a `now`.
  ;;
  ;; A test calling a var that does NOT EXIST is the red-first case and lands
  ;; stubbed. A test calling an EXISTING var at a NEW arity is the SAME
  ;; statement about the same not-yet-written code, and was refused. The
  ;; workaround — land the arity ignoring its new argument, write the test,
  ;; then implement — costs a write and leaves a signature that lies about
  ;; what it does; an agent in a hurry skips the middle step and never sees
  ;; red, which is the one outcome red-first exists to prevent.
  ;;
  ;; :invalid-arity already implies the var is KNOWN — an unknown one is
  ;; :unresolved-var, which this same set stopped blocking for this same
  ;; reason. So the only condition added is that the caller is a test.
  (let [mk   (fn [ns-sym src] (store/ingest (store/empty-store) ns-sym src))
        good "(ns %s)\n\n(defn f [x] x)\n\n(defn t [] (f 1))\n"
        bad  "(ns %s)\n\n(defn f [x] x)\n\n(defn t [] (f 1 2))\n"
        for-ns (fn [ns-sym]
                 (let [b (mk ns-sym (format good (str ns-sym)))
                       c (mk ns-sym (format bad (str ns-sym)))]
                   (lintgate/lint-refusals
                    b c [ns-sym] [(:id (store/form-named c ns-sym 't))])))]
    (testing "from a -test namespace the write lands instead of being refused"
      (let [r (for-ns 'lg.spec-test)]
        (is (nil? (:refuse r)) (pr-str r))
        (testing "and it SAYS so, naming the form that drove the new arity"
          ;; silence would be worse than the refusal: the agent would believe
          ;; the call was fine and read the ArityException as a bug
          (is (= 1 (count (:red-first-arity r))) (pr-str r))
          (is (= 'lg.spec-test/t (:form (first (:red-first-arity r)))) (pr-str r))
          (is (re-find #"expects" (str (:message (first (:red-first-arity r)))))
              (pr-str r)))))
    (testing "the SAME source in a production namespace still refuses"
      ;; the discriminating half — without it, a gate that had simply stopped
      ;; refusing anything satisfies the assertions above
      (let [r (for-ns 'lg.prod)]
        (is (re-find #"in the form you are writing" (str (:refuse r))) (pr-str r))
        (is (re-find #"invalid-arity" (str (:refuse r))) (pr-str r))
        (is (nil? (:red-first-arity r)) (pr-str r))))))
