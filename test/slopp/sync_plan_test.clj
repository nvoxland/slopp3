(ns slopp.sync-plan-test
  "Unit tests for the PURE side of git pull: ns-change-plan's form-granular
  3-way (remote wins where we're clean; both-touched → conflict; trivia-only
  → honest noop). The end-to-end pull is covered by the file-based
  slopp.sync-test (spawns sessions)."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.sync :as sync] [slopp.store.render :as store.render]))

(def base "(ns x.core)\n\n(defn f [x] (+ x 1))\n\n(defn g [x] x)\n")

(deftest noop-when-current-equals-remote
  (is (:noop (sync/ns-change-plan 'x.core base base base))))

(deftest remote-wins-where-we-are-clean
  (let [new (str "(ns x.core)\n\n(defn f [x] (+ x 2))\n\n(defn g [x] x)\n")
        plan (sync/ns-change-plan 'x.core base new base)]
    (testing "one replace step, remote content"
      (is (= [{:action :replace :ns 'x.core :name 'f
               :source "(defn f [x] (+ x 2))"}]
             (:steps plan))))
    (testing "order = the remote file's form order"
      (is (= '[x.core f g] (:order plan))))))

(deftest add-and-delete-flow-through
  (let [new  (str "(ns x.core)\n\n(defn f [x] (+ x 1))\n\n(defn h [x] (* 2 x))\n")
        plan (sync/ns-change-plan 'x.core base new base)]
    (testing "g deleted remotely, h added remotely"
      (is (= #{{:action :delete :ns 'x.core :name 'g}
               {:action :add :ns 'x.core :source "(defn h [x] (* 2 x))"}}
             (set (:steps plan)))))))

(deftest both-edited-is-a-conflict
  (let [new  (str "(ns x.core)\n\n(defn f [x] (+ x 2))\n\n(defn g [x] x)\n")
        cur  (str "(ns x.core)\n\n(defn f [x] (+ x 3))\n\n(defn g [x] x)\n")
        plan (sync/ns-change-plan 'x.core base new cur)]
    (is (:conflict plan))
    (is (re-find #"both sides edited f" (:conflict plan)))))

(deftest already-merged-is-clean
  ;; we already carry the remote's exact change → nothing to do
  (let [new (str "(ns x.core)\n\n(defn f [x] (+ x 2))\n\n(defn g [x] x)\n")]
    (is (:noop (sync/ns-change-plan 'x.core base new new)))))

(deftest anonymous-forms-conflict
  (let [new (str base "(println \"side effect\")\n")]
    (is (:conflict (sync/ns-change-plan 'x.core base new base)))))

(deftest trivia-only-change-is-an-honest-noop
  (let [new (str "(ns x.core)\n\n;; a new comment\n(defn f [x] (+ x 1))\n\n(defn g [x] x)\n")
        plan (sync/ns-change-plan 'x.core base new base)]
    (is (:noop plan))
    (is (:trivia plan))))

(deftest a-clone-takes-every-source-root-and-extension-the-projection-writes
  ;; slopp's own CI has been red for three weeks on this, reading as a
  ;; load-ORDER bug: `clone failed at slopp.api.endpoints … Could not locate
  ;; slopp/api/contracts.cljc on classpath`. Ordering was never wrong — clone!
  ;; sorts by `boot/dependency-order`. `slopp.api.contracts` was never INGESTED,
  ;; because `path-ns` matched `(?:src|test)/(.+)\.clj` and a `.cljc` file does
  ;; not match `\.clj`.
  ;;
  ;; Measured on slopp/main: 7 of 204 source files dropped — both `.cljc`
  ;; namespaces and all five instruments. The clone then reports `:namespaces
  ;; n` for what it decided to take, so a lossy import announces success with a
  ;; smaller number and nothing can tell. It failed at all only because a
  ;; survivor happened to require a casualty.
  ;;
  ;; The roots are already declared once, in `store.render/source-roots`, and
  ;; pinned in both directions against `source-path`. This regex was a second,
  ;; narrower answer to the same question.
  (testing "every root the projection writes into is taken"
    (doseq [[path expected]
            {"src/app/core.clj"          'app.core
             "test/app/core_test.clj"    'app.core-test
             "instruments/app/bench.clj" 'app.bench
             "src/app/shared.cljc"       'app.shared
             "src/deep/nested/thing.clj" 'deep.nested.thing}]
      (is (= expected (sync/path-ns path)) path)))

  (testing "and nothing else is — a remote's own files are not slopp's to ingest"
    (doseq [path ["build.clj" "deps.edn" "README.md" "src/app/core.txt"
                  "docs/guide/x.clj"]]
      (is (nil? (sync/path-ns path)) path)))

  (testing "the roots are DERIVED, so a new one is taken without editing this"
    ;; the positive control on the derivation: if path-ns went back to a
    ;; hand-written alternation, this is the assertion that notices
    (is (every? #(some? (sync/path-ns (str % "/app/core.clj")))
                (remove #{"cljs-src" "cljs-test"} store.render/source-roots))
        (str "every declared JVM source root must be recognised: "
             (pr-str store.render/source-roots)))))
