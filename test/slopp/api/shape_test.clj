(ns slopp.api.shape-test
  "Cover for the shape answers the done-advisories are built on.

  Pure sexpr in, answer out, so every test here is in-image and instant. The
  thing worth knowing about them: the fixtures are REAL SITES from this store,
  including the ones that were false positives when a rule got this wrong.
  `ambiguous-index-reads` is tested against the defmethod dispatch read and
  the accessor that legitimately index position 2, not only against the bug —
  because the predicate's whole job is telling those apart, and the version
  that could not was withdrawn."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api.shape :as shape]))

(deftest return-keys-enumerates-a-statically-known-result-map
  (testing "a bare map literal in return position"
    (is (= #{:a :b} (shape/return-keys '(defn f [] {:a 1 :b 2})))))
  (testing "cond-> threading — base keys plus every (assoc :k …) clause"
    (is (= #{:a :b :c}
           (shape/return-keys
            '(defn f [x y] (cond-> {:a 1} x (assoc :b 2) y (assoc :c 3 :a 9)))))))
  (testing "assoc onto a known base"
    (is (= #{:a :b} (shape/return-keys '(defn f [] (assoc {:a 1} :b 2))))))
  (testing "through a let / do to the tail"
    (is (= #{:a} (shape/return-keys '(defn f [] (let [x 1] (do :ignored {:a x}))))))
    (is (= #{:status} (shape/return-keys '(defn f [] (when-let [x 1] {:status x})))))))

(deftest return-keys-bails-when-the-shape-is-not-bounded
  (testing "a bare variable / opaque call — cannot be bounded"
    (is (nil? (shape/return-keys '(defn f [x] x))))
    (is (nil? (shape/return-keys '(defn f [] (build-it))))))
  (testing "merge adds unknown keys — bail, do not under-report"
    (is (nil? (shape/return-keys '(defn f [m] (merge {:a 1} m))))))
  (testing "a cond-> step that is not (assoc …) could add anything — bail"
    (is (nil? (shape/return-keys '(defn f [x] (cond-> {:a 1} x (into {:b 2})))))))
  (testing "non-keyword keys cannot be enumerated"
    (is (nil? (shape/return-keys '(defn f [k] {k 1})))))
  (testing "multi-arity bails if ANY arity is unbounded, unions otherwise"
    (is (= #{:a :b} (shape/return-keys '(defn f ([] {:a 1}) ([x] {:a 1 :b x})))))
    (is (nil? (shape/return-keys '(defn f ([] {:a 1}) ([x] x)))))))

(deftest key-not-returned-flags-a-read-of-an-unreturned-key
  (let [full-ck '#{:status :unused-public :stale-unused-ok :lint}
        resolver {'external/full-check! full-ck}]
    (testing "the vacuous assertions from the assertions-that-cannot-fail incident"
      (let [caller '(deftest t
                      (let [r (external/full-check! sess)]
                        (is (empty? (:unused r)))
                        (is (empty? (:stale r)))
                        (is (= :green (:status r)))))
            fs (shape/key-not-returned caller resolver)]
        (is (= #{:unused :stale} (set (map :key fs))) (pr-str fs))
        (is (every? #(= 'external/full-check! (:callee %)) fs))))
    (testing "a key the callee DOES return is not flagged"
      (is (empty? (shape/key-not-returned
                   '(deftest t (let [r (external/full-check! sess)] (:status r)))
                   resolver))))
    (testing "an unresolvable callee (unknown return shape) is never flagged"
      (is (empty? (shape/key-not-returned
                   '(deftest t (let [r (mystery-call x)] (:whatever r)))
                   resolver))))
    (testing "a local bound to something other than a resolvable call is ignored"
      (is (empty? (shape/key-not-returned
                   '(deftest t (let [r {:a 1}] (:zzz r)))
                   resolver))))))

(deftest key-not-returned-ignores-deliberate-and-loud-shapes
  (let [resolver {'external/full-check! '#{:status}}
        run (fn [caller] (shape/key-not-returned caller resolver))]
    (testing "(nil? (:k r)) is a DELIBERATE absence check — not flagged"
      (is (empty? (run '(deftest t (let [r (external/full-check! sess)]
                                     (is (nil? (:unused r)))))))))
    (testing "(= v (:k r)) fails loudly on nil (red-first catches it) — not flagged"
      (is (empty? (run '(deftest t (let [r (external/full-check! sess)]
                                     (is (= 3 (:unused r)))))))))
    (testing "a bare read is not an assertion — only the silently-vacuous (empty? …) shape"
      (is (empty? (run '(deftest t (let [r (external/full-check! sess)]
                                     (println (:unused r))))))))))

(deftest assertions-added-counts-what-was-never-watched-fail
  ;; Red/green TDD is usually described as "write the test before the code".
  ;; The load-bearing part is narrower: *every assertion must be observed
  ;; failing at least once*. Adding an `is` to an already-green test skips
  ;; that, and nothing downstream notices — `(is (empty? (:unused r)))` where
  ;; the callee never returns `:unused` is `(empty? nil)`, green forever, and
  ;; it shipped here twice in one session.
  ;;
  ;; `key-not-returned` catches the specific vacuous SHAPE. This counts the
  ;; general case: assertions that were never watched fail, whatever they say.
  (let [t (fn [& body] (concat '(deftest t) body))]
    (testing "assertions added to an existing test are counted"
      (is (= 2 (shape/assertions-added
                (t '(is (= 1 1)))
                (t '(is (= 1 1)) '(is (= 2 2)) '(is (= 3 3)))))))
    (testing "a test that only CHANGED its assertions added none"
      ;; rewriting an assertion re-runs it; the count is what matters, because
      ;; a rewrite that goes green was watched go there
      (is (zero? (shape/assertions-added (t '(is (= 1 1))) (t '(is (= 2 2)))))))
    (testing "a test that REMOVED assertions is not negative"
      (is (zero? (shape/assertions-added (t '(is 1) '(is 2)) (t '(is 1))))))
    (testing "nested assertions count — testing blocks are where they hide"
      (is (= 1 (shape/assertions-added
                (t '(testing "a" (is 1)))
                (t '(testing "a" (is 1)) '(testing "b" (is 2)))))))
    (testing "`are` counts as one assertion form, and says so by not pretending"
      ;; an `are` expands to many; counting the expansion would need macro
      ;; knowledge this does not have. One is the honest count of what is
      ;; WRITTEN, and the advisory says "assertion form" for that reason.
      (is (= 1 (shape/assertions-added (t '(is 1)) (t '(is 1) '(are [x] (= x x) 1 2 3))))))
    (testing "a non-test form is not this rule's business"
      (is (zero? (shape/assertions-added '(defn f [] (is 1)) '(defn f [] (is 1) (is 2))))))))

(deftest ambiguous-index-reads-tells-the-measured-cases-apart
  ;; Every fixture here is a REAL site from the store, because the withdrawn
  ;; version of this rule failed on precision and not on concept: 4-5 false
  ;; positives out of 5. A rule about a bug class has to be judged on the
  ;; population that class actually has.
  (testing "the TRUE positive: reading a form's docstring by index"
    ;; slopp.ui.pages/form-doc, as it stood — it showed (def greeting "hello")
    ;; as documented "hello" on the reviewer page
    (is (seq (shape/ambiguous-index-reads
              '(defn form-doc [e]
                 (let [sx (n/sexpr (:node e))]
                   (let [d (nth sx 2 nil)] (when (string? d) d))))
              false))))
  (testing "FP 1: a defmethod's dispatch value legitimately lives at index 2"
    ;; three sites in this store — edit-replace!, delete-form!,
    ;; method-registrations — and index 2 cannot shift for a defmethod
    (is (= [] (shape/ambiguous-index-reads
               '(defn edit-replace! [e]
                  (let [s (n/sexpr (:node e))]
                    (when (= 'defmethod (first s)) (nth s 2))))
               false))))
  (testing "FP 2: ordinary list manipulation that never touches a store form"
    (is (= [] (shape/ambiguous-index-reads
               '(defn normalize [xs] (nth xs 2 nil))
               false))))
  (testing "FP 3: the accessor itself, which indexes 2 because it knows the rule"
    ;; flagging store/form-docstring would be flagging the fix
    (is (= [] (shape/ambiguous-index-reads
               '(defn form-docstring [node]
                  (let [s (form-sexpr node)] (nth s 2)))
               true))))
  (testing "and it reports WHAT it found, not just that it found something"
    (is (= ["(nth sx 2 nil)"]
           (shape/ambiguous-index-reads
            '(defn f [e] (let [sx (n/sexpr (:node e))] (nth sx 2 nil)))
            false)))))
