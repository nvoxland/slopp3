(ns slopp.rules.doctor-test
  "Cover for the legacy sweep — and the fixtures are the whole validation.

  The subject's population is an ADOPTED store, and slopp's own store is clean
  on every class it detects (measured: zero unmanaged declares, zero duplicate
  names, zero unknown markers). So running it here proves nothing — a clean
  result is exactly what a broken detector produces.

  Each test therefore builds the legacy shape it is about, one per detector,
  and asserts that the detector fires AND that the finding names a fix. The
  clean-store case asserts `:scanned` alongside `:healthy`, because a healthy
  verdict over an empty scan is the vacuity every whole-store guard in this
  codebase has had to learn to refuse."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.store :as store]
            [slopp.rules.doctor :as doctor]))

(deftest every-detector-fires-on-the-legacy-shape-it-is-for
  ;; Measured before building: slopp's own store has ZERO unmanaged declares,
  ;; ZERO duplicate-named elements and three no-symbol forms that are all
  ;; legitimate. So this tool cannot be validated by running it here — a clean
  ;; result is what a broken detector also produces.
  ;;
  ;; The population is an ADOPTED store: `git_clone` / `import!` bring
  ;; arbitrary existing code in, and that code predates every rule slopp
  ;; enforces. These fixtures are that store in miniature, one shape each.
  (testing "an unmanaged (declare …) — the shape that cost the 30-minute detour"
    ;; the pipeline mints declares with ^{:auto-declare "why"}; a hand-written
    ;; one is invisible to it and no tool could reach it
    (let [st (store/ingest (store/empty-store) 'lg.core
                           "(ns lg.core)\n(declare later)\n(defn f [] (later))\n(defn later [] 1)\n")
          d  (doctor/diagnose st)]
      (is (= '[lg.core/later] (map :name (:unmanaged-declares d))))
      (is (string? (:fix (first (:unmanaged-declares d))))
          "a finding with no next call is a complaint")))
  (testing "two elements in one namespace defining the SAME name"
    (let [st (store/ingest (store/empty-store) 'lg.dup
                           "(ns lg.dup)\n(defn f \"one\" [] 1)\n(defn f \"two\" [] 2)\n")
          d  (doctor/diagnose st)]
      (is (= '[lg.dup/f] (map :name (:duplicate-names d))))))
  (testing "a marker that LOOKS official and does nothing"
    (let [st (store/ingest (store/empty-store) 'lg.mk
                           "(ns lg.mk)\n(defn ^:unusedok f \"F.\" [x] x)\n")
          d  (doctor/diagnose st)]
      (is (= [:unusedok] (map :marker (:unknown-markers d)))
          "a typo of ^:unused-ok waives nothing and reads as though it does")))
  (testing "a vocabulary row whose VALUE goes nowhere — the glossary sending a
            reader from one dead name to another"
    ;; The vocabulary is written through `config_file` and NO gate reads it, so
    ;; a row can point anywhere at all. Two consumers trust it without checking:
    ;; the `retired-vocabulary` rule over store forms, and
    ;; bin/check-shipped-prose.sh over the skills and docs/ — which is prose
    ;; that SHIPS. A row pointing at a name this store does not have is a
    ;; glossary entry that answers "where did it go?" with another dead end.
    (let [st (-> (store/ingest (store/empty-store) 'vc.core
                               "(ns vc.core)\n(defn f [] 1)\n")
                 (assoc-in [:config "vocabulary" :values]
                           {"vc.gone"  "vc.core"     ; resolves — a live namespace
                            "old-word" "new-word"    ; bare terms: unresolvable BY CONSTRUCTION, so skipped
                            "vc.a"     "vc.nowhere"  ; dead end
                            "vc.b"     "vc.gone"}))  ; chain — points at another row's KEY
          d  (doctor/diagnose st)
          by (into {} (map (juxt :row :why) (:vocabulary-dead-ends d)))]
      (is (= 4 (count (get-in st [:config "vocabulary" :values])))
          "the fixture's own control — a vocabulary that failed to attach
           satisfies every assertion below it")
      (is (= 2 (count (:vocabulary-dead-ends d))) (pr-str (:vocabulary-dead-ends d)))
      (is (= :unresolved (by "vc.a")) (pr-str by))
      (is (= :chained (by "vc.b"))
          (str "a value that is another row's key is reported as a CHAIN rather"
               " than merely unresolved — the fix differs: " (pr-str by)))
      (is (every? string? (map :fix (:vocabulary-dead-ends d)))
          "a finding with no next call is a complaint")))
  (testing "a name that lives ONLY in metadata is still a live name"
    ;; The first run of this check reported `slopp.api/agent-id ->
    ;; slopp.ops/agent-id` as unresolved. It was wrong: :slopp.ops/agent-id is
    ;; declared in the malli schema on slopp.ops.external/open!, and a schema
    ;; lives in METADATA — which `tree-seq coll? seq` walks straight past,
    ;; because a node's meta is not one of its children. The resolution blob
    ;; therefore had a hole shaped like every declared contract in the store.
    ;;
    ;; Both directions in one fixture on purpose: the silent case is only
    ;; evidence if the loud case fires in the same store.
    (let [st (-> (store/ingest (store/empty-store) 'vm.core
                               (str "(ns vm.core)\n"
                                    "(defn ^{:=> [:=> [:cat [:map [:vm.ctx/id :string]]] :any]}"
                                    " f [x] x)\n"))
                 (assoc-in [:config "vocabulary" :values]
                           {"vm.was"   "vm.ctx/id"    ; reachable ONLY through metadata
                            "vm.other" "vm.absent"})) ; reachable nowhere
          d  (doctor/diagnose st)]
      (is (= ["vm.other"] (map :row (:vocabulary-dead-ends d)))
          (str "the metadata-only name must stay quiet AND the absent one must"
               " fire — one without the other proves nothing: "
               (pr-str (:vocabulary-dead-ends d))))))
  (testing "a CLEAN store reports clean, and says what it looked at"
    (let [st (store/ingest (store/empty-store) 'lg.ok
                           "(ns lg.ok)\n(defn ^{:unused-ok \"surface\"} f \"F.\" [x] x)\n")
          d  (doctor/diagnose st)]
      (is (= [] (:unmanaged-declares d)))
      (is (= [] (:duplicate-names d)))
      (is (= [] (:unknown-markers d)))
      (is (= [] (:vocabulary-dead-ends d)))
      (is (true? (:healthy d)))
      (is (pos? (:scanned d))
          "a clean verdict over nothing is the vacuity every guard here has
           had to learn to refuse"))))
