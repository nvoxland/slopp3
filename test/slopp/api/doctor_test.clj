(ns slopp.api.doctor-test
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
            [slopp.api.doctor :as doctor]))

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
  (testing "a CLEAN store reports clean, and says what it looked at"
    (let [st (store/ingest (store/empty-store) 'lg.ok
                           "(ns lg.ok)\n(defn ^{:unused-ok \"surface\"} f \"F.\" [x] x)\n")
          d  (doctor/diagnose st)]
      (is (= [] (:unmanaged-declares d)))
      (is (= [] (:duplicate-names d)))
      (is (= [] (:unknown-markers d)))
      (is (true? (:healthy d)))
      (is (pos? (:scanned d))
          "a clean verdict over nothing is the vacuity every guard here has
           had to learn to refuse"))))
