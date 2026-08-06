(ns slopp.rename-test
  "The RE-ADDRESSING verbs — `edit_rename`, `ns_rename`, `rename_sweep`,
  `edit_requalify` — held to one standard: a name that moves must move
  everywhere it is a reference, and everywhere it is NOT a reference the verb
  must SAY SO.

  That second half is why these live together rather than beside each verb's
  own subject. Symbols are rewritten perfectly by the CST pass and everything
  else — a name inside a string, a `-test` sibling's own name, a qualified
  keyword, a register key — is left alone, correctly and silently. Silence
  reads as \"there was nothing to carry\", which is how a 16-namespace
  restructure broke four tests on a generated `deps.edn` main-ns nobody was
  told about. So the fixtures here deliberately plant the unrewritable cases,
  and the assertions are about the REPORT as much as the rewrite.

  `slopp.index.refs-test` owns the occurrence set itself
  (`refs/occurrences-of`, the one producer these verbs all read); this
  namespace owns what each verb does with it. Tier: `^:external` throughout —
  a rename rebuilds the image, so it needs a real session."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.ops :as ops] [slopp.read.query :as query] [slopp.ops.external :as external] [slopp.read.history :as history])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def target
  (str "(ns rdemo\n  (:require [clojure.test :refer [deftest is]]))\n"
       "(defn helper [x] (* x 2))\n"
       "(defn caller [x] (+ 1 (helper x)))\n"
       "(defn trap [helper] (helper 9))\n"       ; local param SHADOWS the var
       "(deftest caller-t (is (= 5 (caller 2))))\n"))

(deftest ^:external rename-coordinates-all-references
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'rdemo target)
      (ops/test-run! sess 'rdemo)                ; build the trace map
      (testing "validation errors"
        (is (:error (ops/rename! sess 'rdemo 'nope 'x)))
        (is (:error (ops/rename! sess 'rdemo 'helper 'caller))))
      (let [r (ops/rename! sess 'rdemo 'helper 'doubler :prompt "clearer name")]
        (is (nil? (:error r)))
        (let [src (query/query-source sess 'rdemo)]
          (testing "def + true references renamed"
            (is (re-find #"\(defn doubler \[x\]" src))
            (is (re-find #"\(\+ 1 \(doubler x\)\)" src)))
          (testing "the shadowed local is UNTOUCHED (string-replace would corrupt it)"
            (is (re-find #"\(defn trap \[helper\] \(helper 9\)\)" src))))
        (testing "one delta covers the def and the caller"
          (is (= :rename (:op (:delta r))))
          (is (= 2 (count (:form-ids (:delta r))))))
        (testing "only the affected test re-ran, green"
          (is (= ['rdemo/caller-t] (:affected r)))
          (is (= 1 (:pass (:test r))))
          (is (zero? (+ (:fail (:test r)) (:error (:test r))))))
        (testing "the image reflects the rename; the old var is gone"
          (is (= [11] (ops/query-eval sess "(rdemo/caller 5)")))
          (is (= [nil] (ops/query-eval sess "(resolve 'rdemo/helper)")))
          (is (= [10] (ops/query-eval sess "(rdemo/trap (fn [x] 10))"))))
        (testing "lineage of the new name includes the rename delta"
          (is (contains? (set (map :op (history/query-lineage sess 'rdemo 'doubler)))
                         :rename))))
      (finally (ops/close! sess)))))

(deftest ^:external rename-across-namespaces-and-restart
  (let [dir  (str (Files/createTempDirectory "slopp-rename-test"
                                             (make-array FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/ingest! sess 'liba "(ns liba)\n(defn helper [x] (* x 2))\n")
      (ops/module-dep! sess "libb" "liba" :prompt "fixture edge")
      (ops/ingest! sess 'libb (str "(ns libb\n  (:require [liba :as la]))\n"
                                   "(defn use-it [x] (la/helper x))\n"))
      (let [r (ops/rename! sess 'liba 'helper 'twice :prompt "cross-ns")]
        (is (nil? (:error r)))
        (testing "alias-qualified reference in the other namespace is rewritten"
          (is (re-find #"defn twice" (query/query-source sess 'liba)))
          (is (re-find #"la/twice" (query/query-source sess 'libb))))
        (testing "the live image works across the rename"
          (is (= [10] (ops/query-eval sess "(libb/use-it 5)")))))
      (finally (ops/close! sess)))
    ;; a fresh session over the same dir: the rename persisted in both nses
    (let [sess2 (external/open! {:slopp.ops/dir dir})]
      (try
        (is (re-find #"defn twice" (query/query-source sess2 'liba)))
        (is (re-find #"la/twice" (query/query-source sess2 'libb)))
        (is (= [10] (ops/query-eval sess2 "(libb/use-it 5)")))
        (is (= :rename (:op (last (filter #(= :rename (:op %))
                                          (store/deltas (:store @sess2)))))))
        (finally (ops/close! sess2))))))

(deftest ^:external already-renamed-is-state-not-error
  (let [sess (external/open!)]
    (try
      (ops/create-ns! sess 'ar.core :source "(ns ar.core)\n(defn old-name [] 1)\n")
      (is (nil? (:error (ops/rename! sess 'ar.core 'old-name 'new-name :agent "t"))))
      (testing "the retried rename reports state instead of refusing"
        (let [r (ops/rename! sess 'ar.core 'old-name 'new-name :agent "t")]
          (is (nil? (:error r)) (pr-str r))
          (is (:already (:renamed r)))))
      (finally (ops/close! sess)))))

(deftest ^:external ns-rename-survives-reopen
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-nsren"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
        s1  (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/ingest! s1 'nr.old "(ns nr.old)\n(defn f [x] x)\n")
      (let [r (ops/ns-rename! s1 "nr.old" "nr.new")]
        (is (nil? (:error r)) (pr-str r)))
      (finally (ops/close! s1)))
    (let [s2 (external/open! {:slopp.ops/dir dir})]
      (try
        (testing "the rename PERSISTED — the old ns does not resurrect (eval9 sweep found both alive)"
          (is (nil? (get-in (:store @s2) [:namespaces 'nr.old]))
              (pr-str (keys (:namespaces (:store @s2)))))
          (is (some? (get-in (:store @s2) [:namespaces 'nr.new]))))
        (finally (ops/close! s2))))))

(deftest ^:external renaming-replaces-unmap-the-old-var
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'gv.core "(ns gv.core)\n(defn alpha [x] x)\n(defn beta [x] x)\n")
      (testing "single replace-that-renames unmaps (eval9: ghost zone-t failed mid-sweep)"
        (ops/edit-replace! sess 'gv.core 'alpha "(defn alpha2 [x] x)" :prompt "rn")
        (is (re-find #"nil"
                     (str (ops/query-eval sess "(resolve 'gv.core/alpha)")))
            (pr-str (ops/query-eval sess "(resolve 'gv.core/alpha)"))))
      (testing "group replace-that-renames unmaps too"
        (ops/edit-group! sess [{:action :replace :ns 'gv.core :name 'beta
                                :source "(defn beta2 [x] x)"}]
                         :prompt "rn2")
        (is (re-find #"nil"
                     (str (ops/query-eval sess "(resolve 'gv.core/beta)")))
            (pr-str (ops/query-eval sess "(resolve 'gv.core/beta)"))))
      (finally (ops/close! sess)))))

(deftest ^:external sweep-requalifies-keys-destructuring
  ;; rename_sweep renames keyword LITERALS by text. A destructuring names its
  ;; keys as SYMBOLS inside a :keys vector, so a text sweep left it reading the
  ;; OLD unqualified key — code that compiles, passes the write gate, and reads
  ;; nil at runtime. That is the worst failure mode a refactor tool can have,
  ;; and it is what made namespacing a key a sixty-edit manual job.
  ;;
  ;; The fixture keys are deliberately nonsense (:zkey-one/:zkey-two): a sweep
  ;; rewrites keyword text INSIDE STRING LITERALS too, so a fixture naming a
  ;; real key gets rewritten by any later sweep of that key — the literal is
  ;; changed but the {:keys [...]} inside the same string is not, leaving the
  ;; fixture self-inconsistent. That happened here with :repo.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'rq.core
                   (str "(ns rq.core)\n\n"
                        "(defn mk [] {:zkey-one 1 :zkey-two 2 :zkey-three 3})\n\n"
                        "(defn only-one [{:keys [zkey-two]}] zkey-two)\n\n"
                        "(defn mixed [{:keys [zkey-one zkey-two zkey-three]}]\n"
                        "  [zkey-one zkey-two zkey-three])\n\n"
                        "(defn direct [m] (:zkey-two m))\n"))
      (let [r (ops/rename-sweep! sess ":zkey-two" ":rq/zkey-two"
                                 :prompt "namespace the key")]
        (is (nil? (:error r)) (pr-str r)))
      (let [src (query/query-source sess 'rq.core)]
        (testing "a sole key becomes a qualified :keys entry"
          (is (re-find #"\{:rq/keys \[zkey-two\]\}" src) src))
        (testing "a MIXED destructuring splits — unrenamed keys stay bare"
          (is (re-find #":keys \[zkey-one zkey-three\]" src) src)
          (is (re-find #":rq/keys \[zkey-two\]" src) src))
        (testing "literals and direct reads are renamed as before"
          (is (re-find #":rq/zkey-two 2" src) src)
          (is (re-find #"\(:rq/zkey-two m\)" src) src)))
      (testing "and it actually still reads the value at runtime"
        (is (= [2] (ops/query-eval sess "(rq.core/only-one (rq.core/mk))")))
        (is (= [[1 2 3]] (ops/query-eval sess "(rq.core/mixed (rq.core/mk))"))))
      (finally (ops/close! sess)))))

(deftest ^:external sweep-preserves-type-hints-when-requalifying
  ;; The first cut of requalify-keys rebuilt the :keys vector from `sexpr`,
  ;; which silently DROPPED type hints — {:keys [^Repository repo]} became
  ;; {:slopp.git/keys [repo]}, turning direct interop into reflection — and it
  ;; crashed outright on some hinted vectors. Both are one mistake: a refactor
  ;; must edit NODES, never round-trip through sexpr, or it discards
  ;; everything sexpr does not model (hints, comments, reader tags).
  ;;
  ;; Fixture keys are deliberately nonsense — see the sibling test: a sweep
  ;; rewrites keyword text inside STRING LITERALS, so a fixture naming a real
  ;; key is corrupted by any later sweep of it.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'hint.core
                   (str "(ns hint.core)\n\n"
                        "(defn mk [] {:zhint-one \"c\" :zhint-two 1})\n\n"
                        "(defn use-it [{:keys [^String zhint-one ^Long zhint-two]}]\n"
                        "  [zhint-one zhint-two])\n"))
      (let [r (ops/rename-sweep! sess ":zhint-one" ":hint/zhint-one"
                                 :prompt "namespace a hinted key")]
        (is (nil? (:error r)) (pr-str r)))
      (let [src (query/query-source sess 'hint.core)]
        (testing "the MOVED symbol keeps its hint"
          (is (re-find #":hint/keys \[\^String zhint-one\]" src) src))
        (testing "the symbols left behind keep theirs"
          (is (re-find #":keys \[\^Long zhint-two\]" src) src)))
      (testing "and it still evaluates"
        (is (= [["c" 1]] (ops/query-eval sess "(hint.core/use-it (hint.core/mk))"))))
      (finally (ops/close! sess)))))

(deftest ^:external sweep-dry-run-separates-string-hits-from-code
  ;; A sweep is store-wide and rewrites prose and STRING LITERALS as well as
  ;; code. Sweeping prose is the documented intent — but a fixture string is
  ;; not prose, and a :repo sweep silently rewrote the literal inside a test
  ;; fixture without touching the {:keys [...]} in that same string, leaving
  ;; the fixture self-inconsistent. It was caught only because done ran it.
  ;;
  ;; So the preview must SEPARATE string hits from code hits: those are the
  ;; ones a human has to eyeball. Without it I priced every sweep by hand with
  ;; query_store first, which is the tool's job, not mine.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'dr.core
                   (str "(ns dr.core)\n\n"
                        "(defn real [] {:dr/target 1})\n\n"
                        "(defn fixture []\n"
                        "  \"a :dr/target inside a string — data, not prose\")\n"))
      (let [r (ops/rename-sweep! sess ":dr/target" ":dr/renamed" :dry-run true)]
        (testing "nothing is written"
          (is (:dry-run r) (pr-str r))
          (is (re-find #":dr/target" (query/query-source sess 'dr.core))
              "the store must be untouched by a preview"))
        (testing "code hits and string hits are reported separately"
          (is (= '[dr.core/real] (mapv :form (:in-code r))) (pr-str r))
          (is (= '[dr.core/fixture] (mapv :form (:in-strings r))) (pr-str r))))
      (testing "a preview does not rename NAMESPACES either — that phase writes"
        ;; The FIRST cut of dry-run got this wrong and this test still passed,
        ;; because the fixture swept a keyword that matched no namespace name —
        ;; a green on a path it never touched. So assert the property, not the
        ;; instance: NO dry run appends a delta, whatever it matches.
        (let [before (count (store/deltas (:store @sess)))
              r      (ops/rename-sweep! sess "dr" "renamed" :dry-run true)]
          (is (= '[[dr.core renamed.core]] (:renamed-namespaces r)) (pr-str r))
          (is (contains? (set (keys (:namespaces (:store @sess)))) 'dr.core)
              "the namespace must still exist after a preview")
          (is (= before (count (store/deltas (:store @sess))))
              "a dry run must append NO delta — the shape-level guarantee")))
      (testing "without dry-run it still writes"
        (let [r (ops/rename-sweep! sess ":dr/target" ":dr/renamed"
                                   :prompt "for real")]
          (is (nil? (:error r)) (pr-str r))
          (is (re-find #":dr/renamed" (query/query-source sess 'dr.core)))))
      (finally (ops/close! sess)))))

(deftest ^:external a-sweep-that-loses-a-hint-says-so
  ;; The case that started it. A rename_sweep silently rebuilt a destructuring
  ;; and dropped ^Repository / ^java.sql.Connection from slopp.git/close-ctx!,
  ;; turning direct interop into reflection. It compiled, passed every gate,
  ;; and reported green — found only because I happened to re-read the form.
  ;;
  ;; requalify-keys no longer drops hints, so this drives the loss directly to
  ;; prove the REPORTING works: a sweep is a group write, and group writes
  ;; bypassed drift detection entirely until now.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'sd.core
                   (str "(ns sd.core)\n\n"
                        "(defn use-it [^String zsweep-target] zsweep-target)\n"))
      (let [r (ops/edit-group! sess
                               [{:action :replace :ns 'sd.core :name 'use-it
                                 :source "(defn use-it [zsweep-target] zsweep-target)"}]
                               :prompt "a group op that loses a hint")]
        (is (= '[sd.core/use-it] (mapv :form (:drift r))) (pr-str r))
        (is (= :metadata-lost (:kind (first (:drift r)))) (pr-str r))
        (is (= '{zsweep-target "String"} (:detail (first (:drift r))))
            (str "the drift names WHICH hint went, on WHICH symbol: "
                 (pr-str (:drift r)))))
      (finally (ops/close! sess)))))

(defn src-of
  "The rendered source of `ns-sym/nm` via `query-slice`, which nests it under
  `:target`. Reading it as `(:source r)` yields nil, and nil reaches `re-find`
  as an NPE about `this.text` rather than a readable failure."
  [session ns-sym nm]
  (get-in (query/query-slice session ns-sym nm) [:target :source]))

(deftest ^:external requalify-boundary-keys-does-arglist-and-call-sites-together
  ;; The capability require-namespaced-keys needs to be dischargeable: its last
  ;; violation has 60 call sites, and a store-wide keyword sweep is unsafe when
  ;; the key means more than one thing.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'rq.core
                   (str "(ns rq.core)\n"
                        "(defn opts \"O.\" [{:keys [dir mode]}] [dir mode])\n"
                        "(defn ^:unused-ok a \"A.\" [] (opts {:dir \"x\" :mode :fast}))\n"
                        "(defn ^:unused-ok b \"B.\" [m] (opts m))\n"
                        "(defn ^:unused-ok c \"C.\" [] {:dir \"not an argument\"})\n"))
      (let [r (ops/requalify-boundary-keys! sess 'rq.core 'opts
                                            :prompt "namespace the option keys")]
        (is (nil? (:error r)) (pr-str r))
        (testing "the keys are DERIVED, so half a contract cannot be namespaced"
          (is (= [:dir :mode] (:keys r)) (pr-str r)))
        (testing "the arglist destructuring moved"
          (is (re-find #"\{:rq\.core/keys \[dir mode\]\}" (src-of sess 'rq.core 'opts))
              (src-of sess 'rq.core 'opts)))
        (testing "the literal call site moved with it"
          (let [src (src-of sess 'rq.core 'a)]
            (is (re-find #":rq\.core/dir" src) src)
            (is (re-find #":rq\.core/mode" src) src)))
        (testing "a map that is nobody's argument is untouched"
          (is (re-find #"\{:dir \"not an argument\"\}" (src-of sess 'rq.core 'c))
              (src-of sess 'rq.core 'c)))
        (testing "the non-literal call site is NAMED, not silently skipped"
          (is (= '[rq.core/b] (:unknown-shape r)) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external rename-carries-qualified-prose-references
  ;; A qualified reference in a docstring is unambiguous — `a.b/c` can only
  ;; mean that var — so a rename can move it mechanically. Leaving it behind
  ;; is how documentation starts lying: no gate sees a var inside a string, so
  ;; the stale address ships and costs the next agent a failed call.
  ;; A BARE mention stays a :mentions hint — `zone` in prose may be a domain
  ;; word, and only a human can judge that.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'qp.core
                   (str "(ns qp.core)\n"
                        "(defn ^:unused-ok fee \"F.\" [x] x)\n"
                        "(defn ^:unused-ok teach\n"
                        "  \"call qp.core/fee for the rate; fee is also a domain word\"\n"
                        "  [x] (fee x))\n"))
      (let [r (ops/rename! sess 'qp.core 'fee 'charge :prompt "fee -> charge")]
        (is (nil? (:error r)) (pr-str r)))
      (let [src (query/query-source sess 'qp.core)]
        (testing "the QUALIFIED prose reference followed the rename"
          (is (re-find #"qp\.core/charge" src) src)
          (is (not (re-find #"qp\.core/fee" src))
              (str "a stale qualified reference survived: " src)))
        (testing "the code reference followed too (positional rename, unchanged)"
          (is (re-find #"\(charge x\)" src) src))
        (testing "the BARE domain word is left alone for a human to judge"
          (is (re-find #"fee is also a domain word" src) src)))
      (finally (ops/close! sess)))))

(deftest ^:external move-and-ns-rename-carry-qualified-prose
  ;; The d9077 case itself: a form MOVES namespace and the prose keeps naming
  ;; its old address, so the guidance resolves to nothing. A move re-addresses
  ;; every form it touches, which is why the rewrite takes a map of renames.
  (let [sess (external/open!)]
    (try
      (testing "edit_move_forms: prose naming the pre-move address follows"
        (ops/ingest! sess 'mv.src
                     (str "(ns mv.src)\n"
                          "(defn ^:unused-ok helper \"H.\" [x] x)\n"))
        (ops/ingest! sess 'mv.doc
                     (str "(ns mv.doc)\n"
                          "(defn ^:unused-ok teach \"call mv.src/helper first\" [x] x)\n"))
        (let [r (ops/move-forms! sess 'mv.src ["helper"] 'mv.dest
                                 :prompt "move helper out")]
          (is (nil? (:error r)) (pr-str r)))
        (let [src (query/query-source sess 'mv.doc)]
          (is (re-find #"mv\.dest/helper" src) src)
          (is (not (re-find #"mv\.src/helper" src))
              (str "prose kept the pre-move address: " src))))
      (testing "ns_rename: prose naming the old namespace follows"
        (ops/ingest! sess 'nr.old
                     (str "(ns nr.old)\n"
                          "(defn ^:unused-ok calc \"C.\" [x] x)\n"))
        (ops/ingest! sess 'nr.doc
                     (str "(ns nr.doc)\n"
                          "(defn ^:unused-ok teach \"see nr.old/calc for the rule\" [x] x)\n"))
        (let [r (ops/ns-rename! sess 'nr.old 'nr.new :prompt "rename the ns")]
          (is (nil? (:error r)) (pr-str r)))
        (let [src (query/query-source sess 'nr.doc)]
          (is (re-find #"nr\.new/calc" src) src)
          (is (not (re-find #"nr\.old/calc" src))
              (str "prose kept the old namespace: " src))))
      (finally (ops/close! sess)))))

(deftest ^:external ns-rename-reports-the-occurrences-it-did-not-rewrite
  ;; The dominant failure mode of a 16-namespace restructure: four tests broke
  ;; on namespace names living in STRINGS — a generated deps.edn main-ns, a
  ;; child JVM's program text, a hardcoded path — every one rewritten
  ;; correctly nowhere and reported nowhere. Plus the `-test` sibling, which
  ;; module_extract carries and ns_rename does not, so the two verbs disagree
  ;; about one relationship.
  ;;
  ;; And the quiet one: a QUALIFIED KEYWORD. A broken token string turns
  ;; something red; `:rn.core/mode` just starts naming a namespace that no
  ;; longer exists, through a green suite. Measured during
  ;; `slopp.api.telemetry` → `slopp.read.telemetry`, where
  ;; `:slopp.api.telemetry/calls` stood in 3 forms across 5 sites.
  ;;
  ;; The check runs AFTER the rename over the OLD name: whatever still names
  ;; it was, by construction, not rewritten. That is a reality check, not a
  ;; claim about what the changeset intended to do.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'rn.core "(ns rn.core)\n(defn f \"F.\" [] \"rn.core\")\n")
      (ops/ingest! sess 'rn.core-test
                   (str "(ns rn.core-test (:require [rn.core :as c]\n"
                        "                           [clojure.test :refer [deftest is]]))\n"
                        "(deftest t (is (string? (c/f))))\n"
                        "(def opts \"Opts.\" {:rn.core/mode :fast})\n"))
      (let [r    (ops/ns-rename! sess 'rn.core 'rn.renamed :prompt "regroup")
            left (:left-behind r)]
        (is (some? left) (str "the rename must say what it left: " (pr-str (keys r))))
        (testing "the string literal naming the old namespace"
          (is (some #(= 'f (:form %)) (:string left)) (pr-str left)))
        (testing "the -test sibling still carrying the old name"
          (is (some #(= 'rn.core-test (:ns %)) (:test-sibling left)) (pr-str left)))
        (testing "the qualified keyword — intact, green, and now wrong"
          (is (some #(= ":rn.core/mode" (:text %)) (:keyword left)) (pr-str left)))
        (testing "the note tells the reader these were NOT rewritten"
          (is (re-find #"(?i)not rewritten" (str (:note r))) (pr-str (:note r))))
        (testing "and singles keywords out as the class nothing catches later"
          (is (re-find #"(?i)keyword" (str (:note r))) (pr-str (:note r)))))
      (testing "a clean rename says nothing — absence means checked-and-none"
        (ops/ingest! sess 'rn.clean "(ns rn.clean)\n(defn g \"G.\" [] 1)\n")
        (let [r (ops/ns-rename! sess 'rn.clean 'rn.spotless :prompt "regroup")]
          (is (nil? (:left-behind r)) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external sweep-requalifies-only-the-destructuring-that-names-the-old-key
  ;; A `:keys` destructuring names its key as a SYMBOL, so the sweep has to
  ;; decide which ENTRY that symbol belongs to without the qualifier ever
  ;; being written beside it. It got that decision wrong in both directions
  ;; at once, out of one missing check: does this destructuring name the key
  ;; being renamed?
  ;;
  ;;   UNDER — `{:rkold/keys [ztarget]}` was not followed at all. The form's
  ;;   schema, docstring and all 64 call sites moved to the new key while the
  ;;   destructuring kept reading the old one.
  ;;   OVER  — `{:keys [ztarget]}` was requalified on the strength of the
  ;;   SYMBOL alone, changing which key it reads. Seven forms destructuring
  ;;   an unqualified `:dir` were rewritten to read `:slopp.ops/dir`.
  ;;
  ;; Both present as nil arriving silently through a green verification, which
  ;; is why the assertions that matter here are runtime VALUES and not source
  ;; matches: a positive control is the only thing that separates "reads the
  ;; right key" from "reads nothing and says so quietly".
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'rk.core
                   (str "(ns rk.core)\n\n"
                        "(defn mk-q [] {:rkold/ztarget 1 :rkold/zother 2})\n\n"
                        "(defn mk-u [] {:ztarget 9})\n\n"
                        "(defn split [{:rkold/keys [ztarget zother]}]\n"
                        "  [ztarget zother])\n\n"
                        "(defn wholesale [{:rkold/keys [ztarget]}] ztarget)\n\n"
                        "(defn bare [{:keys [ztarget]}] ztarget)\n"))
      (let [r (ops/rename-sweep! sess ":rkold/ztarget" ":rknew/ztarget"
                                 :prompt "move the key to its new owner")]
        (is (nil? (:error r)) (pr-str r))
        (testing "the STRUCTURAL half of the diff is named, not silent"
          (is (= '#{[rk.core split] [rk.core wholesale]}
                 (set (map (juxt :ns :form) (:requalified r))))
              (pr-str r))))
      (let [src (query/query-source sess 'rk.core)]
        (testing "a qualified destructuring SPLITS when only some members move"
          (is (re-find #":rkold/keys \[zother\]" src) src)
          (is (re-find #":rknew/keys \[ztarget\]" src) src))
        (testing "and requalifies wholesale when all of them do"
          (is (re-find #"\(defn wholesale \[\{:rknew/keys \[ztarget\]\}\]" src) src)
          (is (not (re-find #":rkold/keys \[\]" src)) src))
        (testing "an UNQUALIFIED destructuring names :ztarget — leave it alone"
          (is (re-find #"\(defn bare \[\{:keys \[ztarget\]\}\]" src) src)))
      (testing "and every one of them still reads its own key at runtime"
        (is (= [[1 2]] (ops/query-eval sess "(rk.core/split (rk.core/mk-q))")))
        (is (= [1] (ops/query-eval sess "(rk.core/wholesale (rk.core/mk-q))")))
        (is (= [9] (ops/query-eval sess "(rk.core/bare (rk.core/mk-u))"))))
      (finally (ops/close! sess)))))

(deftest ^:external a-sweep-that-cannot-move-a-destructuring-names-it
  ;; The one case the structural pass has to DECLINE. `{:lb/keys [zbefore]}`
  ;; binds the local `zbefore`, which the body reads; a rename of the key's
  ;; NAME rather than its qualifier could only be applied here by renaming
  ;; that binding — on the strength of a keyword, through every shadow in the
  ;; form. Declining is right.
  ;;
  ;; Declining SILENTLY is how the under-application shipped: a form whose
  ;; literals and docstring all moved, still destructuring the old key,
  ;; reading nil through a green verification. So the sweep says what it left,
  ;; and the check runs over the OLD key AFTER the write — whatever still
  ;; names it was, by construction, not rewritten.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'lb.core
                   (str "(ns lb.core)\n\n"
                        "(defn mk [] {:lb/zbefore 4})\n\n"
                        "(defn read-it [{:lb/keys [zbefore]}] zbefore)\n"))
      (let [r    (ops/rename-sweep! sess ":lb/zbefore" ":lb/zafter"
                                    :prompt "rename the key itself")
            left (:left-behind r)]
        (is (nil? (:error r)) (pr-str r))
        (testing "the literal moved, so the destructuring is now stranded"
          (is (re-find #":lb/zafter 4" (query/query-source sess 'lb.core))))
        (testing "and the sweep names the form it could not finish"
          (is (= [{:ns 'lb.core :form 'read-it :via :destructuring
                   :text "{:lb/keys [zbefore]}"}]
                 left)
              (pr-str r)))
        (testing "the note says these were NOT rewritten, and why"
          (is (re-find #"(?i)not rewritten" (str (:note r))) (pr-str (:note r)))
          (is (re-find #"(?i)local" (str (:note r))) (pr-str (:note r)))))
      (testing "a sweep that only requalifies leaves nothing behind — absence means checked-and-none"
        (ops/ingest! sess 'lb.clean
                     (str "(ns lb.clean)\n\n"
                          "(defn mk [] {:one/zkey 5})\n\n"
                          "(defn read-it [{:one/keys [zkey]}] zkey)\n"))
        (let [r (ops/rename-sweep! sess ":one/zkey" ":two/zkey"
                                   :prompt "move the key's owner")]
          (is (nil? (:left-behind r)) (pr-str r))
          (is (= [{:ns 'lb.clean :form 'read-it}] (:requalified r)) (pr-str r))
          (is (= [5] (ops/query-eval sess "(lb.clean/read-it (lb.clean/mk))")))))
      (finally (ops/close! sess)))))

(deftest ^:external ns-rename-names-the-aliases-it-stranded
  ;; The relationship no rewrite in this verb can reach. A rename rewrites the
  ;; lib symbol in every require clause and walks straight past the `:as` beside
  ;; it, so the caller keeps calling the moved code by its old module's name.
  ;; Twice in this restructure that alias then pointed at a name that had been
  ;; REUSED — `[slopp.webdev.cljs :as api.cljs]` while `slopp.api` was about to
  ;; mean something else entirely — which is worse than an alias naming nothing
  ;; and reads exactly the same in the source.
  ;;
  ;; Two renames of ONE fixture, because each is the other's control: the first
  ;; is the shape that must stay quiet (a namespace changing modules under the
  ;; same last segment — the ordinary case, and if it reported, every rename in
  ;; the restructure would have), and the second is the shape that must not.
  ;;
  ;; Both namespaces sit in module `ra.core` deliberately: `ingest!` runs the
  ;; module gate, so a caller one module over is REFUSED — and a fixture that
  ;; failed to build looks exactly like a report with nothing to say, which is
  ;; how the quiet half of this test first passed on a store of one namespace.
  ;; Hence the `:forms` assertions: they are the fixture's own control.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'ra.core.thing "(ns ra.core.thing)\n(defn f \"F.\" [] 1)\n")
      (ops/ingest! sess 'ra.core.caller
                   (str "(ns ra.core.caller (:require [ra.core.thing :as thing]))\n"
                        "(defn c \"C.\" [] (thing/f))\n"))
      (testing "changing MODULES under the same last segment strands nothing —
                `thing` still names what it calls"
        (let [r (ops/ns-rename! sess 'ra.core.thing 'ra.moved.thing :prompt "regroup")]
          (is (= 2 (:forms (:renamed r))) (pr-str r))
          (is (nil? (:alias (:left-behind r))) (pr-str r))))
      (testing "changing the last segment does strand it — and NOTHING else is
                left behind here, so the report exists only if the alias makes
                it exist"
        (let [r    (ops/ns-rename! sess 'ra.moved.thing 'ra.moved.other :prompt "regroup")
              rows (:alias (:left-behind r))]
          (is (= 2 (:forms (:renamed r))) (pr-str r))
          (is (seq rows) (pr-str r))
          (is (= 'ra.core.caller (:ns (first rows))))
          (is (= 'thing (:alias (first rows))))
          (is (= 'other (:suggest (first rows)))
              "the alias the convention would have produced")
          (testing "and the note names the verb that fixes it, not just the fact"
            (is (re-find #"ns_realias" (str (:note r))) (pr-str (:note r))))))
      (finally (ops/close! sess)))))

(deftest ^:external a-rename-leaves-no-name-keyed-register-naming-the-old-name
  ;; A register keyed by a NAME is the appearance no CST rewrite can reach: it
  ;; is not code, so the changeset walks past it, and the declaration is left
  ;; describing a namespace that no longer exists while the code that moved
  ;; goes ungated. `store/ns-grained-registers` exists because that failure is
  ;; SILENT — it cost fifteen orphans in one wave of deletions — but it covers
  ;; only the three NAMESPACE-grained registers. The two MODULE-grained ones
  ;; (`:modules`, `:module-test-edges`) are re-keyed by a hand-written arm in
  ;; `ns-rename!` that names `:modules` and not `:module-test-edges`, so which
  ;; failure you get depends on nothing a reader can see: whether the edge
  ;; happened to be test-only.
  ;;
  ;; The population is DERIVED from the store value — every map with STRING
  ;; keys is a name-keyed register, `:namespaces` being keyed by symbols — so a
  ;; sixth register is covered by EXISTING rather than by being remembered
  ;; here. Same reason the framework-leak guard derives its namespace list.
  (let [scan (fn [st nm]
               (into (sorted-map)
                     (for [[reg v] st
                           :when (and (map? v) (every? string? (keys v)))
                           :let  [hits (concat (filter #{nm} (keys v))
                                               (for [vs (vals v) :when (coll? vs)
                                                     x  vs        :when (= nm x)]
                                                 x))]
                           :when (seq hits)]
                       [reg (count hits)])))
        sess (external/open!)]
    (try
      (ops/ingest! sess 'nkr.core "(ns nkr.core)\n(defn f \"F.\" [] 1)\n")
      (ops/ingest! sess 'nkr.peer "(ns nkr.peer)\n(defn g \"G.\" [] 1)\n")
      (ops/module-tier!     sess "nkr.core" :pure       :prompt "fixture")
      (ops/module-platform! sess "nkr.core" :jvm        :prompt "fixture")
      (ops/module-role!     sess "nkr.core" :instrument :prompt "fixture")
      (ops/module-dep!      sess "nkr.core" "nkr.peer"  :prompt "fixture")
      ;; BOTH directions of the test-only relation: the old name is a KEY in one
      ;; and a VALUE in the other, and a re-key handling only keys is a
      ;; different bug from one handling neither.
      (ops/module-dep! sess "nkr.core" "nkr.peer" :test-only true :prompt "fixture")
      (ops/module-dep! sess "nkr.peer" "nkr.core" :test-only true :prompt "fixture")
      (let [before (scan (:store @sess) "nkr.core")]
        ;; the control, and it is the whole reason this is not vacuous: over an
        ;; empty population "nothing names the old name" is true by having
        ;; checked nothing. A fixture that failed to declare satisfies every
        ;; absence assertion downstream of it.
        (testing "control: the fixture actually populated the registers"
          (is (= 5 (count before))
              (str "every name-keyed register must hold the old name BEFORE the"
                   " rename, or the assertions below grade an empty set: "
                   (pr-str before))))
        (ops/ns-rename! sess 'nkr.core 'nkw.core :prompt "regroup")
        (let [st (:store @sess)]
          (testing "every register that named the old name now names the new one"
            (is (= before (scan st "nkw.core"))
                (str "the declarations must FOLLOW, at the same multiplicity —"
                     " a register that merely dropped the row leaves the moved"
                     " code ungated just as a stale one does. was: "
                     (pr-str before) " now: " (pr-str (scan st "nkw.core")))))
          (testing "and nothing anywhere still names the old one"
            (is (= {} (scan st "nkr.core"))
                (str "a declaration left naming a namespace that no longer"
                     " exists: " (pr-str (scan st "nkr.core")))))))
      (finally (ops/close! sess)))))
