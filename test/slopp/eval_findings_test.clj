(ns slopp.eval-findings-test
  "Fixes for what the symmetric eval surfaced (S-series)."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.api :as api]))

(deftest ^:external s1-non-compiling-forms-are-rejected-not-silently-committed
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 's1.core
                   (str "(ns s1.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f [x] x)\n"))
      (testing "an add whose form doesn't compile returns {:error}, nothing committed"
        (let [n (count (store/deltas (:store @sess)))
              r (api/add-form! sess 's1.core "(defn bad [] (undefined-fn 1))")]
          (is (:error r))
          (is (re-find #"compile" (:error r)))
          (is (= n (count (store/deltas (:store @sess)))))
          (is (not (re-find #"bad" (api/query-source sess 's1.core))))))
      (testing "the sonnet case: a test referencing an undefined fn is a loud error, not {:ok :ran 0}"
        (let [r (api/add-form! sess 's1.core "(deftest ghost-t (is (= 1 (ghost 1))))")]
          (is (:error r))))
      (testing "a replace that doesn't compile leaves the old form intact everywhere"
        (let [r (api/edit-replace! sess 's1.core 'f "(defn f [x] (nope x))")]
          (is (:error r))
          (is (re-find #"\(defn f \[x\] x\)" (api/query-source sess 's1.core)))
          (is (= [7] (api/query-eval sess "(s1.core/f 7)")))))
      (testing "a group with a non-compiling step commits nothing and the image stays faithful"
        (let [n (count (store/deltas (:store @sess)))
              r (api/edit-group! sess
                                 [{:action :replace :ns 's1.core :name 'f
                                   :source "(defn f [x] (* 2 x))"}
                                  {:action :add :ns 's1.core
                                   :source "(defn g [] (missing))"}]
                                 :prompt "should fail atomically")]
          (is (:error r))
          (is (= n (count (store/deltas (:store @sess)))))
          ;; first step's compile succeeded in the image before step 2 failed —
          ;; the image must be restored to match the (unchanged) store
          (is (= [7] (api/query-eval sess "(s1.core/f 7)")))))
      (finally (api/close! sess)))))

(deftest ^:external s2-forward-refs-rejected-at-write-time-and-move-reorders
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 's2.core "(ns s2.core)\n")
      (testing "S1 makes dependency order self-enforcing: a caller added before
                its callee is rejected on the spot (use (declare x) for mutual
                recursion)"
        (is (:error (api/add-form! sess 's2.core "(defn caller [x] (helper x))"))))
      (api/add-form! sess 's2.core "(defn helper [x] (* 2 x))")
      (api/add-form! sess 's2.core "(defn caller [x] (helper x))")
      (api/add-form! sess 's2.core "(defn util [] :u)")
      (testing "edit_move reorders the store (stylistic/structural ordering)"
        (let [r (api/move-form! sess 's2.core 'util :before 'helper
                                :prompt "group utils first")]
          (is (nil? (:error r)))
          (let [src ^String (api/query-source sess 's2.core)]
            (is (< (.indexOf src "util") (.indexOf src "helper"))))))
      (testing "a FRESH load respects the new order; everything still works"
        (api/restart! sess)
        (is (= [10] (api/query-eval sess "(s2.core/caller 5)")))
        (is (= [:u] (api/query-eval sess "(s2.core/util)"))))
      (testing "lineage records the :move"
        (is (contains? (set (map :op (api/query-lineage sess 's2.core 'util)))
                       :move)))
      (testing "validation"
        (is (:error (api/move-form! sess 's2.core 'nope :before 'caller)))
        (is (:error (api/move-form! sess 's2.core 'helper :before 'nope))))
      (finally (api/close! sess)))))

(deftest ^:external x3-image-loads-follow-dependency-order
  ;; 12 chained namespaces: >8 entries puts the store's ns map in hash order,
  ;; which used to drive restart loads -> silent half-loaded images (round 3).
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'x3.n1)
      (api/add-form! sess 'x3.n1 "(defn f1 [x] (inc x))")
      (doseq [i (range 2 13)]
        (let [ns-sym  (symbol (str "x3.n" i))
              prev    (str "x3.n" (dec i))]
          (api/module-dep! sess (str "x3.n" i) prev :prompt "chain link")
          (api/create-ns! sess ns-sym
                          :requires [(str "[" prev " :as p]")])
          (api/add-form! sess ns-sym
                         (format "(defn f%d [x] (p/f%d x))" i (dec i)))))
      (api/restart! sess)
      (testing "after restart, EVERY namespace in the 12-deep chain is live"
        ;; f2..f12 delegate down to f1 (a single inc): f12(1) = 2
        (is (= [2] (api/query-eval sess "(x3.n12/f12 1)"))))
      (finally (api/close! sess)))))

(deftest ^:external x2-rename-loads-the-definition-first
  ;; many cross-ns callers -> pre-fix, hash-ordered changeset loads could
  ;; reload a caller before the renamed def existed (destructive failure).
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'x2.m)
      (api/add-form! sess 'x2.m "(defn f [x] (* 2 x))")
      (doseq [i (range 1 10)]
        (let [ns-sym (symbol (str "x2.c" i))]
          (api/module-dep! sess (str "x2.c" i) "x2.m" :prompt "caller")
          (api/create-ns! sess ns-sym :requires ["[x2.m :as m]"])
          (api/add-form! sess ns-sym (format "(defn call%d [x] (m/f x))" i))))
      (let [r (api/rename! sess 'x2.m 'f 'g :prompt "x2 regression")]
        (is (nil? (:error r)))
        (is (= 10 (get-in r [:renamed :forms]))))
      (testing "image consistent immediately and after a fresh restart"
        (is (= [14] (api/query-eval sess "(x2.c9/call9 7)")))
        (api/restart! sess)
        (is (= [14] (api/query-eval sess "(x2.c9/call9 7)"))))
      (finally (api/close! sess)))))

(deftest ^:external reload-of-a-store-namespace-is-a-no-op-not-a-file-error   ; self-host eval finding
  ;; Store namespaces have no .clj on the classpath (loaded via load-ns!), so the
  ;; muscle-memory `(require 'the.ns :reload)` threw FileNotFoundException. In the
  ;; owned image there are no source files to reload, so query-eval strips
  ;; :reload/:reload-all — the require becomes the intended no-op.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'rl.core "(ns rl.core)\n(defn f [x] (inc x))\n")
      (testing "plain require of the loaded store ns works (baseline)"
        (is (= [4] (api/query-eval sess "(do (require 'rl.core) (rl.core/f 3))"))))
      (testing ":reload no longer errors — it's stripped in the image"
        (is (= [4] (api/query-eval sess "(do (require 'rl.core :reload) (rl.core/f 3))"))))
      (testing ":reload-all is stripped too"
        (is (= [4] (api/query-eval sess "(do (require 'rl.core :reload-all) (rl.core/f 3))"))))
      (finally (api/close! sess)))))

(deftest ^:external remove-require-is-symmetric
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'rr.core :requires ["[clojure.string :as str]"
                                               "[clojure.set :as cset]"])
      (let [r (api/remove-require! sess 'rr.core 'clojure.set)]
        (is (nil? (:error r)))
        (is (not (re-find #"clojure\.set" (api/query-source sess 'rr.core))))
        (is (re-find #"clojure\.string" (api/query-source sess 'rr.core))))
      (is (:error (api/remove-require! sess 'rr.core 'clojure.set)))  ; already gone
      (finally (api/close! sess)))))
