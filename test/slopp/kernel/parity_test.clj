(ns slopp.kernel.parity-test
  "Cover for the kernel parity comparator.

  Same-package by necessity — the subject is package-private to
  `slopp.store.*` — and in-image by nature, since the comparison is pure.

  Every fixture here is a shape that actually happened: the two historical
  drifts (a whole public form missing, `:isolated` where the other said
  `:external`), the two false positives the comparator produced on its first
  real run (reader gensyms, re-wrapped docstrings), and the escape's own
  failure mode. A parity check is easy to tune by taste until it agrees with
  today's diff; grounding each case in a real incident is what keeps it a
  guard rather than a description."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.kernel.parity :as parity]))

(deftest kernel-parity-catches-the-two-drifts-that-actually-happened
  ;; `slopp.kernel.rt` and `slopp.kernel.boot` exist as BOTH a hand-maintained file on main
  ;; and a namespace in the store. The file serves a main-checkout dev run
  ;; (which is how the benchmarks execute); the store serves everything the
  ;; jar and the external tier touch. Nothing has ever compared them, and they
  ;; have drifted twice — both times found by hand, days apart:
  ;;
  ;;   1. `install-parent-watchdog!` existed only in the store. A whole public
  ;;      form missing from the file; nothing broke, by luck.
  ;;   2. The file still filtered the in-image runner on `:isolated` while the
  ;;      store had moved to `:external`, so a canonically-tagged external
  ;;      test was NOT filtered by the file copy — the exact false-green the
  ;;      rename existed to prevent.
  ;;
  ;; A store-wide rename cannot see a file, so every future rename has the
  ;; same blind spot. These two shapes are the whole specification.
  (testing "a form present in one copy and not the other"
    (let [file  "(ns k) (defn a [] 1)"
          store "(ns k) (defn a [] 1) (defn b! [] 2)"
          r     (parity/kernel-parity file store)]
      (is (false? (:ok r)))
      (is (= '[b!] (:store-only r)))
      (is (= [] (:file-only r)))))
  (testing "a body that differs — the :isolated/:external shape"
    (let [file  "(ns k) (defn pick [ts] (remove :isolated ts))"
          store "(ns k) (defn pick [ts] (remove :external ts))"
          r     (parity/kernel-parity file store)]
      (is (false? (:ok r)))
      (is (= '[{:name pick :what :body}] (:differing r)))))
  (testing "visibility is surface, so it is compared even though markers are not"
    (let [file  "(ns k) (defn ^:private a [] 1)"
          store "(ns k) (defn a [] 1)"
          r     (parity/kernel-parity file store)]
      (is (false? (:ok r)))
      (is (= '[{:name a :what :visibility}] (:differing r))))))

(deftest kernel-parity-tolerates-the-residual-and-refuses-to-pass-on-nothing
  (testing "form ORDER is not drift — the store orders by its own logic, the file by human grouping"
    ;; this is most of what `git diff main slopp/main -- src/slopp/kernel/rt.clj`
    ;; renders, as delete-here/add-there, and it is why the raw diff has never
    ;; been usable as the guard
    (let [file  "(ns k) (defn a [] 1) (defn b [] 2)"
          store "(ns k) (defn b [] 2) (defn a [] 1)"]
      (is (true? (:ok (parity/kernel-parity file store))))))
  (testing "store-only MARKERS are not drift — an injected kernel copy is never analyzed"
    ;; `^:entry-point` feeds unreferenced-form analysis, `^:ambient-ok` and
    ;; `^:unsafe` discharge store gates. None of them mean anything to a file
    ;; that gets slurped and eval'd into a child image, which is why identity
    ;; was never the invariant.
    (let [file  "(ns k) (defn observe [x] x) (def sink (atom nil))"
          store "(ns k) (defn ^:entry-point observe [x] x) (def ^:ambient-ok sink (atom nil))"]
      (is (true? (:ok (parity/kernel-parity file store))))))
  (testing "an empty side REFUSES — it must never pass on a population of zero"
    ;; the whole reason this repo's other own-store guard was worthless for
    ;; its entire life: it scanned an empty store and reported clean
    (is (:error (parity/kernel-parity "" "(ns k) (defn a [] 1)")))
    (is (:error (parity/kernel-parity "(ns k) (defn a [] 1)" "   ")))
    (is (:error (parity/kernel-parity "(ns k)" "(ns k)"))
        "an ns form alone is not a population — nothing would ever be compared"))
  (testing "identical copies pass, and say what they compared"
    (let [src "(ns k) (defn a [] 1) (defn- b [] 2)"
          r   (parity/kernel-parity src src)]
      (is (true? (:ok r)))
      (is (= {:file 2 :store 2} (:compared r))
          "the count of DEFINITIONS compared — a pass has to show its population"))))

(deftest kernel-parity-survives-reader-generated-names
  ;; Found by running the check against the two REAL copies the moment it
  ;; existed: it reported `observe`, `traced-run` and `install-parent-
  ;; watchdog!` as drifted, and all three were byte-identical on disk.
  ;;
  ;; The cause is that `n/sexpr` expands `#(…)` into `(fn* [p1__67039#] …)`
  ;; with a FRESH gensym every call, so two parses of one string are never
  ;; `=`. The tests above were green throughout, because none of their
  ;; fixtures contained an anonymous fn — the "a repro can be too minimal"
  ;; trap, from the inside: strip the subject down far enough and you strip
  ;; out the thing that breaks it.
  (testing "a source is equal to ITSELF even when it contains reader-generated names"
    (let [src "(ns k) (defn a [xs] (map #(inc %) xs)) (defn b [xs] (filter #(> % 2) xs))"]
      (is (true? (:ok (parity/kernel-parity src src))))))
  (testing "and real drift inside an anonymous fn is still caught"
    ;; the risk of canonicalizing names is that it hides a difference; this is
    ;; the assertion that says it does not
    (let [file  "(ns k) (defn a [xs] (map #(inc %) xs))"
          store "(ns k) (defn a [xs] (map #(dec %) xs))"]
      (is (false? (:ok (parity/kernel-parity file store))))
      (is (= '[{:name a :what :body}] (:differing (parity/kernel-parity file store))))))
  (testing "two DIFFERENT anonymous fns in one form stay distinguishable"
    ;; canonicalizing to a single placeholder would make these equal
    (let [file  "(ns k) (defn a [xs ys] [(map #(inc %) xs) (map #(dec %) ys)])"
          store "(ns k) (defn a [xs ys] [(map #(dec %) xs) (map #(inc %) ys)])"]
      (is (false? (:ok (parity/kernel-parity file store)))))))

(deftest kernel-parity-compares-prose-by-its-words-not-its-line-breaks
  ;; The two copies are indented differently — `^:reads (defn- open-conn` in
  ;; the file pushes its whole body ten columns right — so every docstring in
  ;; both kernels is wrapped differently. Failing on that would fail forever,
  ;; and a check that always fails is a check somebody turns off.
  ;;
  ;; But prose drift is real and has been caught here before: the store's rt
  ;; docstring once said "multimethods and macros are not instrumented" after
  ;; that had stopped being true, and it was the only non-cosmetic difference
  ;; in the 2026-07-17 audit. Words are the thing to compare; line breaks are
  ;; not.
  (testing "the same words, wrapped differently, are not drift"
    (let [file  "(ns k) (defn a\n  \"One sentence that\n  wraps here.\"\n  [] 1)"
          store "(ns k) (defn a\n  \"One sentence\n  that wraps here.\"\n  [] 1)"]
      (is (true? (:ok (parity/kernel-parity file store))))))
  (testing "different words ARE drift, even in prose"
    (let [file  "(ns k) (defn a \"macros are not instrumented.\" [] 1)"
          store "(ns k) (defn a \"macros are instrumented.\" [] 1)"]
      (is (false? (:ok (parity/kernel-parity file store))))))
  (testing "and the normalization does not reach outside strings"
    ;; a keyword or symbol that merely CONTAINS the other's name must not
    ;; collapse into it
    (let [file  "(ns k) (defn a [] (remove :isolated []))"
          store "(ns k) (defn a [] (remove :external []))"]
      (is (false? (:ok (parity/kernel-parity file store)))))))

(deftest kernel-parity-lets-a-difference-be-DECLARED-and-polices-the-declaration
  ;; The store's `watch-live!` docstring explains its own `^:unsafe` marker —
  ;; prose that is true of the store copy and meaningless in a file that gets
  ;; slurped into a child image. A real, permanent, correct difference.
  ;;
  ;; Without a way to say so, the check reds forever and somebody turns it
  ;; off. That is this repo's own standing rule — "zero current violations is
  ;; not sufficient grounds to make a rule blocking; the question is whether
  ;; every case it can fire on has a way OUT" — set here after
  ;; `breaking-changes` had to be dialled back for exactly this.
  ;;
  ;; So the escape is an argument, and it is REPORTED rather than silent: an
  ;; accepted difference stays visible in every run.
  (let [file  "(ns k) (defn a \"one\" [] 1) (defn b [] 2)"
        store "(ns k) (defn a \"another\" [] 1) (defn b [] 2)"]
    (testing "a declared difference passes, and still says it is there"
      (let [r (parity/kernel-parity file store '#{a})]
        (is (true? (:ok r)))
        (is (= '[a] (:accepted r)) "visible, not silenced")))
    (testing "declaring one difference does not accept the others"
      (let [r (parity/kernel-parity file (str store " (defn c [] 3)") '#{a})]
        (is (false? (:ok r)))
        (is (= '[c] (:store-only r)))))
    (testing "and the declaration polices itself"
      ;; the dial that fails with "remove the flag" when the flag is no longer
      ;; earning its keep — the same shape as ^:unused-ok
      (let [r (parity/kernel-parity file file '#{a})]
        (is (false? (:ok r)))
        (is (= '[a] (:stale-accepted r))
            "it accepts a difference that is no longer there — drop it")))))

(deftest an-accepted-name-that-is-missing-entirely-is-still-a-real-difference
  ;; The self-policing check asks "does this declaration still describe a
  ;; difference?" — and the first cut computed that from the ALREADY-FILTERED
  ;; difference lists, so a name accepted for being absent from one copy
  ;; looked like a declaration about nothing. The escape would have reported
  ;; the very case it exists to permit as an error asking you to remove it.
  (let [file  "(ns k) (defn a [] 1) (defn only-here [] 2)"
        store "(ns k) (defn a [] 1)"]
    (testing "accepting a form that exists in one copy only is honoured, not flagged stale"
      (let [r (parity/kernel-parity file store '#{only-here})]
        (is (true? (:ok r)))
        (is (= [] (:stale-accepted r [])))
        (is (= '[only-here] (:accepted r)))))
    (testing "the same in the other direction"
      (let [r (parity/kernel-parity store file '#{only-here})]
        (is (true? (:ok r)))
        (is (= [] (:stale-accepted r [])))))))
