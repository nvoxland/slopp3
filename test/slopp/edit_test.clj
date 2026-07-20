(ns slopp.edit-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.render :as render]
            [slopp.repl :as repl]
            [slopp.image :as image]
            [slopp.edit :as edit] [slopp.edit.refs :as refs] [slopp.edit.hotload :as hotload]))

(def src "(ns demo)\n(defn add [x y]\n  (+ x y))\n(def z 1)\n")

(defn- ingest [] (store/ingest (store/empty-store) 'demo src))

^:unsafe (deftest strip-image-reload-removes-reload-only-inside-requires
  (testing ":reload / :reload-all are stripped from require/use forms"
    (is (not (re-find #":reload" (edit/strip-image-reload "(require 'foo :reload)"))))
    (is (not (re-find #":reload" (edit/strip-image-reload "(require '[a :as b] :reload-all)"))))
    (is (not (re-find #":reload" (edit/strip-image-reload "(use 'foo :reload)"))))
    (testing "the require itself survives (still evaluable)"
      (is (re-find #"require" (edit/strip-image-reload "(require 'foo :reload)")))
      (is (re-find #"foo" (edit/strip-image-reload "(require 'foo :reload)")))))
  (testing "nested inside a (do ...) is still reached"
    (is (not (re-find #":reload"
                      (edit/strip-image-reload "(do (require 'foo :reload) (foo/bar))")))))
  (testing "a :reload keyword OUTSIDE a require is preserved (no over-stripping)"
    (is (re-find #":reload" (edit/strip-image-reload "{:reload true}")))
    (is (re-find #":reload" (edit/strip-image-reload "(assoc m :reload 1)"))))
  (testing "code with nothing to strip is returned intact"
    (is (= [1 2] (read-string (str "[" (edit/strip-image-reload "1 2") "]"))))))

(deftest replace-form-happy-path
  (let [s (ingest)
        r (edit/replace-form s 'demo 'add "(defn add [x y] (* x y))"
                             :prompt "make it multiply")]
    (testing "no error; delta recorded with prompt (provenance)"
      (is (nil? (:error r)))
      (is (= :replace (:op (:delta r))))
      (is (= "make it multiply" (:prompt (:delta r)))))
    (testing "rendered source reflects the change; other forms untouched"
      (is (re-find #"\(\* x y\)" (render/render-ns (:store r) 'demo)))
      (is (re-find #"\(def z 1\)" (render/render-ns (:store r) 'demo))))
    (testing "form identity is stable across the edit (C2)"
      (is (= (:id (store/form-named s 'demo 'add))
             (:id (store/form-named (:store r) 'demo 'add)))))))

(deftest metadata-mutation-is-banned
  ;; D3: runtime metadata mutation defeats analysis — slopp reads markers
  ;; (^:export, ^:unsafe, ^:reads, :malli/schema, ^:auto-declare) straight off
  ;; the STORED node, so metadata must be SOURCE-only truth. with-meta/vary-meta
  ;; return NEW values (no reference is mutated) and stay legal; only the
  ;; in-place mutators alter-meta!/reset-meta! are cut.
  (testing "alter-meta! is rejected (D3)"
    (let [err (:error (edit/parse-form "(defn f [] (alter-meta! #'x assoc :foo 1))"))]
      (is err)
      (is (re-find #"metadata" (str err)) "the refusal teaches SOURCE-only metadata")))
  (testing "reset-meta! is rejected (D3)"
    (is (:error (edit/parse-form "(defn f [] (reset-meta! #'x {:foo 1}))"))))
  (testing "with-meta / vary-meta return new values and are NOT banned"
    (is (nil? (:error (edit/parse-form "(defn f [x] (with-meta x {:foo 1}))"))))
    (is (nil? (:error (edit/parse-form "(defn f [x] (vary-meta x assoc :foo 1))")))))
  (testing "^:unsafe bypasses the metadata-mutation ban"
    (is (nil? (:error (edit/parse-form "^:unsafe (defn f [] (alter-meta! #'x assoc :foo 1))"))))))

(deftest replace-form-rejects-non-dialect
  (let [s (ingest)]
    (testing "D4: user macros banned"
      (is (:error (edit/replace-form s 'demo 'add "(defmacro add [x] x)"))))
    (testing "D3: denylisted forms rejected"
      (is (:error (edit/replace-form s 'demo 'add "(defn add [x] (eval x))"))))
    (testing "must be exactly one top-level form"
      (is (:error (edit/replace-form s 'demo 'add "(defn add [x] x) (def oops 1)"))))
    (testing "unknown form name"
      (is (:error (edit/replace-form s 'demo 'nope "(defn nope [] 1)"))))))

(deftest replace-form-flags-effect-violation
  (let [s (ingest)
        r (edit/replace-form s 'demo 'add "(defn add [a] (swap! a inc))")]
    (testing "D6: effectful body under a non-! name -> warning + suggested fix"
      (is (nil? (:error r)))
      (is (some #(= 'demo/add (:var %)) (:warnings r)))
      (is (some #(= "add!" (:suggest %)) (:warnings r))))))

(deftest ^:external apply-replace-hot-reloads
  ;; red -> edit -> hot-reload -> green in the image + a provenance delta.
  ;; (Verification orchestration — affected tests, diagnostics — is api-level;
  ;; see slopp.verification-test.)
  (let [target (str "(ns demo2\n"
                    "  (:require [clojure.test :refer [deftest is]]))\n"
                    "(defn add [x y] (+ x y))\n"
                    "(deftest t (is (= 6 (add 2 3))))\n")  ; expects 6, add gives 5 -> red
        s (store/ingest (store/empty-store) 'demo2 target)
        h (repl/start!)]
    (try
      (image/load-ns! h s 'demo2)
      (testing "initially red (add 2 3 = 5, test expects 6)"
        (is (= 1 (:fail (image/test-run h 'demo2)))))
      (let [r (hotload/apply-replace! {:store s :image h} 'demo2 'add
                                   "(defn add [x y] (+ x y 1))" :prompt "off-by-one")]
        (testing "the edit hot-reloads: image reflects the redefinition, tests green"
          (is (nil? (:error r)))
          (is (= [6] (repl/eval! h "(demo2/add 2 3)")))
          (is (= 0 (:fail (image/test-run h 'demo2)))))
        (testing "the :replace delta is recorded with its prompt"
          (let [d (last (store/deltas (:store (:system r))))]
            (is (= :replace (:op d)))
            (is (= "off-by-one" (:prompt d))))))
      (finally (repl/stop! h)))))
(deftest compile-errors-speak-anchors
  ;; agents can't consume file:line (reads are name-addressed, edits are
  ;; anchor-addressed) — the boundary translates VFS coordinates into the
  ;; owning FORM plus a match-ready snippet.
  (let [st (store/ingest (store/empty-store) 'an.err
                         (str "(ns an.err)\n"
                              "(defn ok \"O.\" [x] x)\n"
                              "(defn broken \"B.\" [x]\n"
                              "  (nope-not-a-fn x))\n"))]
    (testing "a located error resolves to form + snippet"
      (let [a (edit/anchor-error st "Syntax error compiling at (an/err.clj:4:3).\nUnable to resolve symbol: nope-not-a-fn")]
        (is (= 'an.err/broken (:form a)) (pr-str a))
        (is (= "(nope-not-a-fn x))" (:at a)))))
    (testing "unlocatable text stays nil — the caller keeps the raw message"
      (is (nil? (edit/anchor-error st "something exploded, no coordinates")))
      (is (nil? (edit/anchor-error st nil))))))
(deftest compile-error-anchors-or-falls-back
  (let [st (store/ingest (store/empty-store) 'ce.core
                         "(ns ce.core)\n(defn f \"F.\" [x] (boom x))\n")]
    (testing "resolvable coordinate → form + snippet, coordinate stripped"
      (let [r (edit/compile-error
               st "Syntax error compiling boom at (ce/core.clj:2:18).\nUnable to resolve symbol: boom"
               "form failed to compile: ")]
        (is (= 'ce.core/f (:form r)))
        (is (re-find #"boom" (:at r)))
        (is (re-find #"Unable to resolve" (:error r)) "message survives")
        (is (not (re-find #"\.clj:\d" (:error r))) "coordinate gone")
        (is (not (re-find #" at\b" (:error r))) "the dangling 'at' goes too")))
    (testing "no coordinate → raw fallback keeps the message"
      (let [r (edit/compile-error st "something broke, no location"
                                  "form failed to compile: ")]
        (is (nil? (:form r)))
        (is (= "form failed to compile: something broke, no location" (:error r)))))))

(deftest keyword-refs-see-literals-and-destructuring
  ;; Keywords are the last "N point-fixes standing in for one abstraction".
  ;; rename_sweep learned about destructuring, then query-flow learned it
  ;; separately, and everything else stayed blind — because the reference
  ;; GRAPH has never modelled a keyword at all.
  ;;
  ;; A key read via {:ns/keys [x]} appears NOWHERE as a token: it is computed
  ;; from the directive's namespace plus the symbol's name. Measured on the
  ;; real store, query_depends on :slopp.git/map-conn returned six rows and
  ;; silently omitted four consumers — every module-boundary fn that
  ;; destructures it.
  ;;
  ;; A SIBLING index rather than rows in `refs`: a keyword has no defining
  ;; form, so it cannot carry :to-form, and forcing it into the var record
  ;; would let the keyword :a.b/c collide with a var a.b/c in every
  ;; var-oriented consumer.
  (let [st (-> (store/empty-store)
               (store/ingest 'kr.core
                             (str "(ns kr.core)\n\n"
                                  "(defn mk [] {:kr/conn 1 :plain 2})\n\n"
                                  "(defn literal [m] (:kr/conn m))\n\n"
                                  "(defn destructured [{:kr/keys [conn]}] conn)\n\n"
                                  "(defn bare [{:keys [plain]}] plain)\n\n"
                                  "(defn quoted [] '(:kr/conn ignored))\n")))
        by-kw (group-by :kw (refs/keyword-refs st))
        froms (fn [kw] (set (map :from-var (get by-kw kw))))]
    (testing "literal occurrences are edges, attributed to their form"
      (is (contains? (froms :kr/conn) 'literal))
      (is (contains? (froms :kr/conn) 'mk)))
    (testing "a DESTRUCTURED key is an edge too — the whole point"
      (is (contains? (froms :kr/conn) 'destructured)))
    (testing "the edge says HOW, so a consumer can tell them apart"
      (is (= #{:literal :destructuring}
             (set (map :via (get by-kw :kr/conn))))))
    (testing "an unqualified destructured key resolves unqualified"
      (is (contains? (froms :plain) 'bare)))
    (testing "quoted data is pruned, like every other producer"
      (is (not (contains? (froms :kr/conn) 'quoted))))))

(deftest denylist-hit-in-binding-position-explains-itself
  ;; The gate matches symbol NAMES anywhere in the form, so a parameter named
  ;; `binding` is refused though a local can no more invoke clojure.core/binding
  ;; than a local named `map` invokes map. Fixing that properly needs scope
  ;; tracking; until then the refusal must not read as "you used binding".
  (let [check #(edit/dialect-check (:node (edit/parse-one %)))]
    (testing "still refused — the gate is not weakened"
      (let [e (check "(defn f [binding] (when (map? binding) binding))")]
        (is (some? e) (pr-str e))
        (testing "but it names the CAUSE and the one-word fix"
          (is (re-find #"LOCAL" (str e)) (str e))
          (is (re-find #"RENAME" (str e)) (str e)))))
    (testing "a real call in a form that never binds the name says no such thing"
      (let [e (check "(defn f [] (binding [*out* nil] (prn 1)))")]
        (is (some? e) (pr-str e))
        (is (not (re-find #"LOCAL" (str e))) (str e))))))
