(ns slopp.rt-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.rt :as rt]))
^:unsafe (deftest instrument-seam-records-and-restores
  ;; THE instrumentation seam (#121). traced-run had this loop inline; the
  ;; external trace runner needs the SAME wrapping around cognitect's runner
  ;; instead of around its own per-var loop. Extracted so there is exactly one
  ;; tracer — never two.
  (let [n (create-ns 'rt-probe.core)]
    (intern n 'twice (fn [x] (* 2 x)))
    (intern n 'not-a-fn 42)
    (let [touched   (atom #{})
          originals (rt/instrument! ['rt-probe.core] touched)]
      (try
        (testing "a wrapped fn still returns its value AND records itself"
          (is (= 4 ((ns-resolve n 'twice) 2)))
          (is (= '#{rt-probe.core/twice} @touched)))
        (testing "non-fn vars are left alone"
          (is (= 42 @(ns-resolve n 'not-a-fn))))
        (finally (rt/restore! originals))))
    (testing "restore! un-wraps — instrumentation is temporary"
      (let [touched (atom #{})]
        (is (= 4 ((ns-resolve n 'twice) 2)))
        (is (= #{} @touched))))))
^:unsafe (deftest self-instrument-catches-rts-own-calls
  ;; In a CHILD image, slopp.rt is the ONLY slopp code that runs (inject-rt! is
  ;; the sole place slopp code is evaled in; the child otherwise loads its own
  ;; store). Nothing there wraps rt, so its calls were invisible.
  ;;
  ;; The wrap must be installed at INJECTION time, not inside traced-run: a fn
  ;; already on the stack cannot record its own entry, which is exactly why
  ;; traced-run measured 0 covering tests while 213 exercised it.
  ;;
  ;; NOTE drain-self! records ITSELF (it is an rt fn, called in the child, on
  ;; the parent test's behalf) — that is honest, not noise, so nothing here
  ;; asserts the drained set is empty.
  (let [originals (rt/self-instrument!)]
    (try
      (testing "the installer records ITSELF — it is on the stack when the wrap
                goes on, so no wrapper can ever catch its entry. Left out it
                reads as covered by the one test that calls it directly, and a
                PARTIAL count is worse than none: zero falls back to running
                everything, one narrows to one test and says green."
        (is (contains? @rt/self-touched 'slopp.rt/self-instrument!)))
      (rt/qualified #'clojure.core/inc)
      (let [drained (rt/drain-self!)]
        (testing "an rt call made here is recorded against rt's own atom"
          (is (contains? drained 'slopp.rt/qualified) (pr-str drained))))
      (testing "the drain CLEARS — it runs once per eval, and re-reporting a
                call would inflate whichever test is traced next"
        (is (not (contains? (rt/drain-self!) 'slopp.rt/qualified))))
      (finally (rt/restore! originals))))
  (testing "restored — self-instrumentation is temporary like every other"
    (rt/qualified #'clojure.core/inc)
    (is (not (contains? (rt/drain-self!) 'slopp.rt/qualified)))))
^:unsafe (deftest nested-instrument-restores-the-outer-sink
  ;; The sink is how rt calls made in a CHILD image find the test being traced
  ;; (#126). instrument! calls NEST, always: traced-run instruments in-image and
  ;; testmain instruments once around the whole external run, and individual
  ;; tests — this file's probes — instrument again inside that. So restore! must
  ;; hand the sink BACK to the enclosing run rather than clear it. Clearing it
  ;; stops the drain for every later test in the shard, SILENTLY: a nil sink
  ;; just drains nowhere.
  ;;
  ;; There is ALWAYS an enclosing run here — this test is running under one — so
  ;; nothing below asserts the sink is nil. An earlier version of this test did,
  ;; and passed only because the bug it was meant to catch nulled the sink first.
  (let [enclosing @rt/touched-sink]
    (is (some? enclosing) "the run tracing THIS test owns the sink right now")
    (let [outer      (atom #{})
          outer-orig (rt/instrument! [] outer)]
      (try
        (is (identical? outer @rt/touched-sink))
        (let [inner      (atom #{})
              inner-orig (rt/instrument! [] inner)]
          (try
            (is (identical? inner @rt/touched-sink))
            (finally (rt/restore! inner-orig))))
        (testing "the inner restore hands the sink back to the OUTER run"
          (is (identical? outer @rt/touched-sink)))
        (finally (rt/restore! outer-orig))))
    (testing "…and the outer restore hands it back to what enclosed us"
      (is (identical? enclosing @rt/touched-sink)))))
