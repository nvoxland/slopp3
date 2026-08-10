(ns slopp.edit-test
  "Cover for `slopp.edit` — the pure edit layer.

  Store value in, store value out, so nearly everything here is in-image and
  instant. Two things are worth knowing about the assertions:

  The refusal MESSAGES are as much the subject as the return values. A gate
  that refuses correctly and says nothing useful costs a round trip every time
  it fires, so tests assert on what the message NAMES — the next call, the
  construct as written, the alias that would fix it.

  And the negative cases carry their weight: a hint that fires when nothing
  can supply it is worse than no hint, because it sends the next call
  somewhere real and useless."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.store.render :as render]
            [slopp.image.repl :as repl]
            [slopp.image :as image]
            [slopp.edit :as edit] [slopp.index.refs :as refs] [slopp.edit.hotload :as hotload] [clojure.string :as str]))

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
  (let [st  (store/ingest (store/empty-store) 'an.err
                          (str "(ns an.err)\n"
                               "(defn ok \"O.\" [x] x)\n"
                               "(defn broken \"B.\" [x]\n"
                               "  (nope-not-a-fn x))\n"))
        ;; derived, not hardcoded: rendering SYNTHESIZES the space between
        ;; forms, so a literal row here silently encodes one renderer version
        ;; and goes red on the next. What is under test is the translation.
        row (->> (str/split-lines (render/render-ns st 'an.err))
                 (keep-indexed (fn [i l] (when (str/includes? l "nope-not-a-fn") (inc i))))
                 first)]
    (testing "a located error resolves to form + snippet"
      (let [a (edit/anchor-error st (str "Syntax error compiling at (an/err.clj:" row ":3).\n"
                                         "Unable to resolve symbol: nope-not-a-fn"))]
        (is (= 'an.err/broken (:form a)) (pr-str a))
        (is (= "(nope-not-a-fn x))" (:at a)))))
    (testing "unlocatable text stays nil — the caller keeps the raw message"
      (is (nil? (edit/anchor-error st "something exploded, no coordinates")))
      (is (nil? (edit/anchor-error st nil))))))

(deftest compile-error-anchors-or-falls-back
  (let [st  (store/ingest (store/empty-store) 'ce.core
                          "(ns ce.core)\n(defn f \"F.\" [x] (boom x))\n")
        ;; derived: rendering synthesizes the space between forms, so a
        ;; literal row encodes one renderer version and goes red on the next
        row (->> (str/split-lines (render/render-ns st 'ce.core))
                 (keep-indexed (fn [i l] (when (str/includes? l "boom") (inc i))))
                 first)]
    (testing "resolvable coordinate → form + snippet, coordinate stripped"
      (let [r (edit/compile-error
               st (str "Syntax error compiling boom at (ce/core.clj:" row ":18).\n"
                       "Unable to resolve symbol: boom")
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

(deftest replace-refuses-renaming-onto-an-existing-form
  ;; rename! and :add both refuse a name collision, but the replace path only
  ;; checked stranded callers — a replace that RENAMED a form onto an existing
  ;; name silently landed two definitions of that name, cold-load passed
  ;; (redefinition is only a kondo warning), and every later name-addressed
  ;; edit refused as ambiguous with a cleanup hint that cannot fix it. Same
  ;; refusal as rename!, at the shared chokepoint.
  (let [s (store/ingest (store/empty-store) 'rr.core
                        "(ns rr.core)\n(defn a [] 1)\n(defn b [] 2)\n")]
    (testing "renaming onto an existing name refuses"
      (let [r (edit/replace-form s 'rr.core 'a "(defn b [] 99)")]
        (is (re-find #"already exists" (str (:error r))) (pr-str r))))
    (testing "an honest rename to a FRESH name still lands"
      (is (:store (edit/replace-form s 'rr.core 'a "(defn c [] 42)"))))
    (testing "replacing a form in place still lands"
      (is (:store (edit/replace-form s 'rr.core 'a "(defn a [] 42)"))))))

(deftest dialect-gate-closes-the-qualification-and-metadata-holes
  (let [s (ingest)]
    (testing "a clojure.core-qualified banned sym is refused like the bare one"
      (is (:error (edit/replace-form s 'demo 'add "(defn add [x] (clojure.core/eval x))")))
      (is (:error (edit/replace-form s 'demo 'add "(defn add [x] (clojure.core/read-string x))"))))
    (testing "eval-equivalents load-string/load-file/load-reader are banned"
      (is (:error (edit/replace-form s 'demo 'add "(defn add [s] (load-string s))")))
      (is (:error (edit/replace-form s 'demo 'add "(defn add [s] (load-file s))"))))
    (testing "defmacro is refused when qualified or nested, not just at the head"
      (is (:error (edit/replace-form s 'demo 'add "(clojure.core/defmacro add [x] x)")))
      (is (:error (edit/replace-form s 'demo 'add "(defn add [x] (defmacro m [y] y))"))))
    (testing "a banned sym smuggled into literal metadata is refused"
      (is (:error (edit/replace-form s 'demo 'add "(def add ^{:h eval} [1])"))))
    (testing "the SAFE reader (clojure.edn/read-string) still passes"
      (is (nil? (:error (edit/replace-form s 'demo 'add
                                           "(defn add [x] (clojure.edn/read-string x))")))))
    (testing "an ordinary var whose NAME collides but is a different ns still passes"
      (is (nil? (:error (edit/replace-form s 'demo 'add
                                           "(defn add [x] (my.lib/resolve x))")))))))

(deftest control-characters-in-source-are-refused
  (let [us  (str (char 31))                     ; unit separator, as \x1f decodes
        nul (str (char 0))]
    (testing "a raw control character refuses, naming the codepoint"
      (let [err (:error (edit/parse-form
                         (str "(defn f \"D.\" [] (re-find #\"[a-z" us "]\" \"x\"))")))]
        (is (some? err))
        (is (re-find #"(?i)control character" err))
        (is (re-find #"U\+001F" err) err)))
    (testing "the teaching names the transit decode and a way to spell it"
      (let [err (:error (edit/parse-form (str "(def x \"" nul "\")")))]
        (is (re-find #"(?i)escape" err) err)))
    (testing "tab, newline and return are legitimate whitespace, not control chars"
      (is (nil? (:error (edit/parse-form "(defn f \"D.\" []\n\t(+ 1 2))")))))
    (testing "an escaped \\u in the SOURCE TEXT is fine — it is printable"
      (is (some? (:node (edit/parse-form "(def x \"\\u001f\")")))))
    (testing "ordinary source is untouched"
      (is (some? (:node (edit/parse-form "(defn f \"D.\" [] 1)")))))))

(deftest a-missing-alias-names-the-require-to-add
  ;; The most frequent mechanical friction measured in a real session: ~8
  ;; writes refused with a bare `No such namespace: X`, each one followed by
  ;; `ns_add_require` and then a resend of the BYTE-IDENTICAL form. Three round
  ;; trips for a two-step slopp has all the information to collapse — and it
  ;; fired once more while this very test was being written.
  ;;
  ;; Every other structural refusal here names the next call: the module gate
  ;; gives the exact `module_dep`, the tier gate the exact `module_purity`.
  ;; This one repeated what the compiler said.
  ;;
  ;; The fixture names are FICTIONAL on purpose. It used to ingest the real
  ;; `slopp.store.kernel`, which made a unit test of a pure string function
  ;; hostage to a rename of production code — and when that rename came, the
  ;; quoted symbol moved while the `(ns …)` three tokens later, inside a
  ;; STRING, did not. The store held a fixture declaring one namespace under
  ;; the name of another.
  (let [st (-> (store/empty-store)
               (store/ingest 'some.kernel "(ns some.kernel)\n(defn f [] 1)\n")
               (store/ingest 'app.core "(ns app.core)\n(defn g [] 2)\n"))]
    (testing "a store namespace whose LAST SEGMENT is the alias"
      (let [h (#'edit/missing-alias-hint st "No such namespace: kernel" 'app.core)]
        (is (some? h))
        (is (str/includes? h "some.kernel"))
        (is (str/includes? h "ns_add_require"))
        (is (str/includes? h "app.core") "the require goes on the ns being WRITTEN")))
    (testing "a well-known clojure alias, which no store namespace can supply"
      (let [h (#'edit/missing-alias-hint st "No such namespace: str" 'app.core)]
        (is (some? h))
        (is (str/includes? h "clojure.string"))))
    (testing "an alias nothing can supply says NOTHING rather than guessing"
      ;; a wrong suggestion costs more than none: it sends the next call
      ;; somewhere real and useless
      (is (nil? (#'edit/missing-alias-hint st "No such namespace: zzz" 'app.core))))
    (testing "an unrelated compile error is left alone"
      (is (nil? (#'edit/missing-alias-hint st "Unable to resolve symbol: q" 'app.core))))
    (testing "ambiguity names every candidate instead of picking one"
      (let [st2 (store/ingest st 'other.kernel "(ns other.kernel)\n(defn h [] 3)\n")
            h   (#'edit/missing-alias-hint st2 "No such namespace: kernel" 'app.core)]
        (is (str/includes? h "some.kernel"))
        (is (str/includes? h "other.kernel"))))))

(deftest a-nested-reader-conditional-is-named-as-one-not-blamed-on-read-string
  ;; slopp-ui, 2026-08-06. `#?(:clj … :cljs …)` inside a fn body was refused
  ;; with "denylisted symbol used — read-string" against source containing no
  ;; such symbol. Three calls went into proving the obvious reading wrong.
  ;;
  ;; The cause is a gap between two correct pieces. A reader conditional
  ;; sexprs as `(read-string "#?…")`, and `dialect-check` HAS a dedicated arm
  ;; for exactly that — but the arm tests the form's own head, so it only ever
  ;; fired for a form that IS a reader conditional at top level. Nobody writes
  ;; that. Every real one is nested in a body, where it fell through to the
  ;; denylist arm and got blamed on the synthetic head of its own expansion.
  ;;
  ;; **A wrong-but-actionable message is worse than an unhelpful one**: it
  ;; reads as a finding, so the reader spends their next three calls
  ;; disproving it rather than solving anything. That is why this is a test
  ;; about the WORDS and not only about the refusal.
  ;;
  ;; The arm was written and never tested against a nested case, which is the
  ;; only shape it would ever meet.
  (let [msg (fn [src] (:error (edit/parse-form src)))]
    (testing "nested in a fn body — the shape anyone actually writes"
      (let [m (msg "(defn- probe \"P.\" [] #?(:clj \"jvm\" :cljs \"browser\"))")]
        (is (some? m) "still refused — this test is about the reason, not the verdict")
        (is (re-find #"(?i)reader conditional" (str m)) (pr-str m))
        (is (not (re-find #"read-string" (str m)))
            (str "the source contains no read-string; naming it sends the"
                 " reader after a symbol that is not there: " (pr-str m)))))

    (testing "deeper still, and in a splicing conditional"
      (doseq [src ["(defn f \"F.\" [x] (let [y (inc x)] #?(:clj y :cljs x)))"
                   "(defn g \"G.\" [] [1 #?@(:clj [2 3] :cljs [4])])"]]
        (let [m (msg src)]
          (is (some? m) src)
          (is (not (re-find #"read-string" (str m))) (str src " → " (pr-str m))))))

    (testing "the top-level shape the arm was written for still works"
      (is (re-find #"(?i)reader conditional"
                   (str (msg "#?(:clj 1 :cljs 2)")))))

    (testing "and an ORDINARY denylist hit still names its own symbol"
      ;; the control: the fix must not swallow the message it is narrowing
      (let [m (msg "(defn h \"H.\" [s] (eval s))")]
        (is (re-find #"eval" (str m)) (pr-str m))))))

(deftest a-tagged-literal-is-data-and-every-denylisted-symbol-teaches-its-way-out
  ;; ONE function, TWO hand-kept lists, and each had already cost.
  ;;
  ;; (1) A tagged literal sexprs as `(read-string "#…")`, and the recogniser
  ;; matched the one tag it was written for. Measured: `#inst "2020-01-01"` and
  ;; `#uuid "…"` — ordinary Clojure data literals, nothing to do with D3 — were
  ;; REFUSED, blaming `read-string`, the synthetic head of their own expansion
  ;; and a symbol the source does not contain. That is the exact bug the
  ;; comment above the reader-conditional arm describes, still live for every
  ;; tag but the one it was fixed for. The store round-trips both perfectly, so
  ;; the gate was the only obstacle.
  ;;
  ;; A tagged literal is DATA. The gate must no more read inside it for banned
  ;; symbols than it reads inside a string — except `#?`/`#?@`, which are
  ;; special precisely because they change what code is READ.
  ;;
  ;; (2) The escape hatch was taught in four hand-listed `cond` branches over a
  ;; 17-member denylist, with no `:else`. Measured: 10 of 17 taught nothing —
  ;; alter-var-root, binding, definline, eval, gen-class, intern, load-file,
  ;; load-reader, load-string, read-string. A refusal is read in fix-it mode
  ;; and one that names no way forward sends the reader to guess.
  (let [check #(edit/dialect-check (:node (edit/parse-one %)))]
    (testing "an ordinary tagged literal is admissible — it is data"
      (is (nil? (check "(defn f [] #inst \"2020-01-01\")")))
      (is (nil? (check "(defn g [] #uuid \"00000000-0000-0000-0000-000000000001\")"))))
    (testing "the reader conditional is still refused — by its TAG, both spellings"
      (is (str/includes? (str (check "(defn h [] #?(:clj 1 :cljs 2))"))
                         "reader conditionals"))
      (is (str/includes? (str (check "(defn h [] #?@(:clj [1 2]))"))
                         "reader conditionals")))
    (testing "a read-string the author actually WROTE is still refused"
      ;; the positive control on the pruning: it must not swallow real hits
      (is (str/includes? (str (check "(defn k [] (read-string \"x\"))"))
                         "read-string")))
    (testing "the denylist carries its own teaching, so there is no branch to forget"
      (is (map? @#'edit/banned-syms)
          "banned-syms is the one list: symbol -> how to get past it")
      (is (every? #(and (string? %) (seq %)) (vals @#'edit/banned-syms))
          "a denylisted symbol with no teaching is the gap this closes"))
    (testing "and every denylisted symbol's refusal names a way forward"
      (let [bare (for [sym  (keys @#'edit/banned-syms)
                       :let [msg (str (check (str "(defn probe [] [" sym "])")))]
                       :when (not (re-find #"\^:unsafe|carrier|RENAME|write a function|store/late-ref|instead" msg))]
                   sym)]
        (is (empty? bare)
            (str "denylisted symbols whose refusal teaches nothing: " (vec bare)))))))
