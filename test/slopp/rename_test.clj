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
            [slopp.ops :as api] [slopp.read.query :as query] [slopp.ops.external :as external] [slopp.read.history :as history])
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
      (api/ingest! sess 'rdemo target)
      (api/test-run! sess 'rdemo)                ; build the trace map
      (testing "validation errors"
        (is (:error (api/rename! sess 'rdemo 'nope 'x)))
        (is (:error (api/rename! sess 'rdemo 'helper 'caller))))
      (let [r (api/rename! sess 'rdemo 'helper 'doubler :prompt "clearer name")]
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
          (is (= [11] (api/query-eval sess "(rdemo/caller 5)")))
          (is (= [nil] (api/query-eval sess "(resolve 'rdemo/helper)")))
          (is (= [10] (api/query-eval sess "(rdemo/trap (fn [x] 10))"))))
        (testing "lineage of the new name includes the rename delta"
          (is (contains? (set (map :op (history/query-lineage sess 'rdemo 'doubler)))
                         :rename))))
      (finally (api/close! sess)))))

(deftest ^:external rename-across-namespaces-and-restart
  (let [dir  (str (Files/createTempDirectory "slopp-rename-test"
                                             (make-array FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (api/ingest! sess 'liba "(ns liba)\n(defn helper [x] (* x 2))\n")
      (api/module-dep! sess "libb" "liba" :prompt "fixture edge")
      (api/ingest! sess 'libb (str "(ns libb\n  (:require [liba :as la]))\n"
                                   "(defn use-it [x] (la/helper x))\n"))
      (let [r (api/rename! sess 'liba 'helper 'twice :prompt "cross-ns")]
        (is (nil? (:error r)))
        (testing "alias-qualified reference in the other namespace is rewritten"
          (is (re-find #"defn twice" (query/query-source sess 'liba)))
          (is (re-find #"la/twice" (query/query-source sess 'libb))))
        (testing "the live image works across the rename"
          (is (= [10] (api/query-eval sess "(libb/use-it 5)")))))
      (finally (api/close! sess)))
    ;; a fresh session over the same dir: the rename persisted in both nses
    (let [sess2 (external/open! {:slopp.ops/dir dir})]
      (try
        (is (re-find #"defn twice" (query/query-source sess2 'liba)))
        (is (re-find #"la/twice" (query/query-source sess2 'libb)))
        (is (= [10] (api/query-eval sess2 "(libb/use-it 5)")))
        (is (= :rename (:op (last (filter #(= :rename (:op %))
                                          (store/deltas (:store @sess2)))))))
        (finally (api/close! sess2))))))

(deftest ^:external already-renamed-is-state-not-error
  (let [sess (external/open!)]
    (try
      (api/create-ns! sess 'ar.core :source "(ns ar.core)\n(defn old-name [] 1)\n")
      (is (nil? (:error (api/rename! sess 'ar.core 'old-name 'new-name :agent "t"))))
      (testing "the retried rename reports state instead of refusing"
        (let [r (api/rename! sess 'ar.core 'old-name 'new-name :agent "t")]
          (is (nil? (:error r)) (pr-str r))
          (is (:already (:renamed r)))))
      (finally (api/close! sess)))))

(deftest ^:external ns-rename-survives-reopen
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-nsren"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
        s1  (external/open! {:slopp.ops/dir dir})]
    (try
      (api/ingest! s1 'nr.old "(ns nr.old)\n(defn f [x] x)\n")
      (let [r (api/ns-rename! s1 "nr.old" "nr.new")]
        (is (nil? (:error r)) (pr-str r)))
      (finally (api/close! s1)))
    (let [s2 (external/open! {:slopp.ops/dir dir})]
      (try
        (testing "the rename PERSISTED — the old ns does not resurrect (eval9 sweep found both alive)"
          (is (nil? (get-in (:store @s2) [:namespaces 'nr.old]))
              (pr-str (keys (:namespaces (:store @s2)))))
          (is (some? (get-in (:store @s2) [:namespaces 'nr.new]))))
        (finally (api/close! s2))))))

(deftest ^:external renaming-replaces-unmap-the-old-var
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'gv.core "(ns gv.core)\n(defn alpha [x] x)\n(defn beta [x] x)\n")
      (testing "single replace-that-renames unmaps (eval9: ghost zone-t failed mid-sweep)"
        (api/edit-replace! sess 'gv.core 'alpha "(defn alpha2 [x] x)" :prompt "rn")
        (is (re-find #"nil"
                     (str (api/query-eval sess "(resolve 'gv.core/alpha)")))
            (pr-str (api/query-eval sess "(resolve 'gv.core/alpha)"))))
      (testing "group replace-that-renames unmaps too"
        (api/edit-group! sess [{:action :replace :ns 'gv.core :name 'beta
                                :source "(defn beta2 [x] x)"}]
                         :prompt "rn2")
        (is (re-find #"nil"
                     (str (api/query-eval sess "(resolve 'gv.core/beta)")))
            (pr-str (api/query-eval sess "(resolve 'gv.core/beta)"))))
      (finally (api/close! sess)))))

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
      (api/ingest! sess 'rq.core
                   (str "(ns rq.core)\n\n"
                        "(defn mk [] {:zkey-one 1 :zkey-two 2 :zkey-three 3})\n\n"
                        "(defn only-one [{:keys [zkey-two]}] zkey-two)\n\n"
                        "(defn mixed [{:keys [zkey-one zkey-two zkey-three]}]\n"
                        "  [zkey-one zkey-two zkey-three])\n\n"
                        "(defn direct [m] (:zkey-two m))\n"))
      (let [r (api/rename-sweep! sess ":zkey-two" ":rq/zkey-two"
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
        (is (= [2] (api/query-eval sess "(rq.core/only-one (rq.core/mk))")))
        (is (= [[1 2 3]] (api/query-eval sess "(rq.core/mixed (rq.core/mk))"))))
      (finally (api/close! sess)))))

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
      (api/ingest! sess 'hint.core
                   (str "(ns hint.core)\n\n"
                        "(defn mk [] {:zhint-one \"c\" :zhint-two 1})\n\n"
                        "(defn use-it [{:keys [^String zhint-one ^Long zhint-two]}]\n"
                        "  [zhint-one zhint-two])\n"))
      (let [r (api/rename-sweep! sess ":zhint-one" ":hint/zhint-one"
                                 :prompt "namespace a hinted key")]
        (is (nil? (:error r)) (pr-str r)))
      (let [src (query/query-source sess 'hint.core)]
        (testing "the MOVED symbol keeps its hint"
          (is (re-find #":hint/keys \[\^String zhint-one\]" src) src))
        (testing "the symbols left behind keep theirs"
          (is (re-find #":keys \[\^Long zhint-two\]" src) src)))
      (testing "and it still evaluates"
        (is (= [["c" 1]] (api/query-eval sess "(hint.core/use-it (hint.core/mk))"))))
      (finally (api/close! sess)))))

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
      (api/ingest! sess 'dr.core
                   (str "(ns dr.core)\n\n"
                        "(defn real [] {:dr/target 1})\n\n"
                        "(defn fixture []\n"
                        "  \"a :dr/target inside a string — data, not prose\")\n"))
      (let [r (api/rename-sweep! sess ":dr/target" ":dr/renamed" :dry-run true)]
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
              r      (api/rename-sweep! sess "dr" "renamed" :dry-run true)]
          (is (= '[[dr.core renamed.core]] (:renamed-namespaces r)) (pr-str r))
          (is (contains? (set (keys (:namespaces (:store @sess)))) 'dr.core)
              "the namespace must still exist after a preview")
          (is (= before (count (store/deltas (:store @sess))))
              "a dry run must append NO delta — the shape-level guarantee")))
      (testing "without dry-run it still writes"
        (let [r (api/rename-sweep! sess ":dr/target" ":dr/renamed"
                                   :prompt "for real")]
          (is (nil? (:error r)) (pr-str r))
          (is (re-find #":dr/renamed" (query/query-source sess 'dr.core)))))
      (finally (api/close! sess)))))

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
      (api/ingest! sess 'sd.core
                   (str "(ns sd.core)\n\n"
                        "(defn use-it [^String zsweep-target] zsweep-target)\n"))
      (let [r (api/edit-group! sess
                               [{:action :replace :ns 'sd.core :name 'use-it
                                 :source "(defn use-it [zsweep-target] zsweep-target)"}]
                               :prompt "a group op that loses a hint")]
        (is (= '[sd.core/use-it] (mapv :form (:drift r))) (pr-str r))
        (is (= :metadata-lost (:kind (first (:drift r)))) (pr-str r))
        (is (= '{zsweep-target "String"} (:detail (first (:drift r))))
            (str "the drift names WHICH hint went, on WHICH symbol: "
                 (pr-str (:drift r)))))
      (finally (api/close! sess)))))

(defn ^:unused-ok src-of
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
      (api/ingest! sess 'rq.core
                   (str "(ns rq.core)\n"
                        "(defn opts \"O.\" [{:keys [dir mode]}] [dir mode])\n"
                        "(defn ^:unused-ok a \"A.\" [] (opts {:dir \"x\" :mode :fast}))\n"
                        "(defn ^:unused-ok b \"B.\" [m] (opts m))\n"
                        "(defn ^:unused-ok c \"C.\" [] {:dir \"not an argument\"})\n"))
      (let [r (api/requalify-boundary-keys! sess 'rq.core 'opts
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
      (finally (api/close! sess)))))

(deftest ^:external rename-carries-qualified-prose-references
  ;; A qualified reference in a docstring is unambiguous — `a.b/c` can only
  ;; mean that var — so a rename can move it mechanically. Leaving it behind
  ;; is how documentation starts lying: no gate sees a var inside a string, so
  ;; the stale address ships and costs the next agent a failed call.
  ;; A BARE mention stays a :mentions hint — `zone` in prose may be a domain
  ;; word, and only a human can judge that.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'qp.core
                   (str "(ns qp.core)\n"
                        "(defn ^:unused-ok fee \"F.\" [x] x)\n"
                        "(defn ^:unused-ok teach\n"
                        "  \"call qp.core/fee for the rate; fee is also a domain word\"\n"
                        "  [x] (fee x))\n"))
      (let [r (api/rename! sess 'qp.core 'fee 'charge :prompt "fee -> charge")]
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
      (finally (api/close! sess)))))

(deftest ^:external move-and-ns-rename-carry-qualified-prose
  ;; The d9077 case itself: a form MOVES namespace and the prose keeps naming
  ;; its old address, so the guidance resolves to nothing. A move re-addresses
  ;; every form it touches, which is why the rewrite takes a map of renames.
  (let [sess (external/open!)]
    (try
      (testing "edit_move_forms: prose naming the pre-move address follows"
        (api/ingest! sess 'mv.src
                     (str "(ns mv.src)\n"
                          "(defn ^:unused-ok helper \"H.\" [x] x)\n"))
        (api/ingest! sess 'mv.doc
                     (str "(ns mv.doc)\n"
                          "(defn ^:unused-ok teach \"call mv.src/helper first\" [x] x)\n"))
        (let [r (api/move-forms! sess 'mv.src ["helper"] 'mv.dest
                                 :prompt "move helper out")]
          (is (nil? (:error r)) (pr-str r)))
        (let [src (query/query-source sess 'mv.doc)]
          (is (re-find #"mv\.dest/helper" src) src)
          (is (not (re-find #"mv\.src/helper" src))
              (str "prose kept the pre-move address: " src))))
      (testing "ns_rename: prose naming the old namespace follows"
        (api/ingest! sess 'nr.old
                     (str "(ns nr.old)\n"
                          "(defn ^:unused-ok calc \"C.\" [x] x)\n"))
        (api/ingest! sess 'nr.doc
                     (str "(ns nr.doc)\n"
                          "(defn ^:unused-ok teach \"see nr.old/calc for the rule\" [x] x)\n"))
        (let [r (api/ns-rename! sess 'nr.old 'nr.new :prompt "rename the ns")]
          (is (nil? (:error r)) (pr-str r)))
        (let [src (query/query-source sess 'nr.doc)]
          (is (re-find #"nr\.new/calc" src) src)
          (is (not (re-find #"nr\.old/calc" src))
              (str "prose kept the old namespace: " src))))
      (finally (api/close! sess)))))

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
      (api/ingest! sess 'rn.core "(ns rn.core)\n(defn f \"F.\" [] \"rn.core\")\n")
      (api/ingest! sess 'rn.core-test
                   (str "(ns rn.core-test (:require [rn.core :as c]\n"
                        "                           [clojure.test :refer [deftest is]]))\n"
                        "(deftest t (is (string? (c/f))))\n"
                        "(def opts \"Opts.\" {:rn.core/mode :fast})\n"))
      (let [r    (api/ns-rename! sess 'rn.core 'rn.renamed :prompt "regroup")
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
        (api/ingest! sess 'rn.clean "(ns rn.clean)\n(defn g \"G.\" [] 1)\n")
        (let [r (api/ns-rename! sess 'rn.clean 'rn.spotless :prompt "regroup")]
          (is (nil? (:left-behind r)) (pr-str r))))
      (finally (api/close! sess)))))
