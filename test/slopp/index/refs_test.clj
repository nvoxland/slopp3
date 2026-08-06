(ns slopp.index.refs-test
  "THE reference graph: every kind of reference — kondo-static, carrier
  positions, declarations — as ONE canonical record stream all tools
  consume. Producers normalize here; consumers never re-integrate."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.index.refs :as refs]
            [slopp.store :as store] [clojure.set :as set] [clojure.string :as str]))

(deftest the-graph-sees-every-reference-kind
  (let [st (-> (store/empty-store)
               (store/ingest 'g.core
                             (str "(ns g.core)\n\n"
                                  "(defn ^:entry-point serve \"S.\" [x] x)\n\n"
                                  "(defn helper \"H.\" [x] x)\n\n"
                                  "(defn ^:unused-ok spare \"P.\" [x] x)\n"))
               (store/ingest 'g.app
                             (str "(ns g.app (:require [g.core :as core]))\n\n"
                                  "(defn go \"G.\" [x] (core/helper x))\n\n"
                                  "(defn drive \"D.\" [sess]\n"
                                  "  (query-call sess 'g.core/helper 2))\n"))
               (store/ingest 'g.sneak
                             (str "(ns g.sneak)\n\n"
                                  "(defn s \"S.\" [x] (g.core/helper x))\n")))]
    (testing "static (required AND un-required qualified), carrier, declared"
      (let [rs (refs/refs-to st 'g.core/helper)
            by (fn [nsx] (set (map :via (filter #(= nsx (:from-ns %)) rs))))]
        (is (= #{:static :carrier} (by 'g.app)) (pr-str rs))
        (is (= #{:static} (by 'g.sneak)) "the gate-hole class is a static ref")))
    (testing "declarations are edges from the outside world"
      (let [r (first (refs/refs-to st 'g.core/serve))]
        (is (= :declared (:via r)))
        (is (= :entry-point (:marker r)))
        (is (= :external (:from-ns r))))
      (is (= :unused-ok (:marker (first (refs/refs-to st 'g.core/spare))))))
    (testing "records anchor to the owning FORM, not positions"
      (let [r (first (filter #(and (= :static (:via %)) (= 'g.app (:from-ns %)))
                             (refs/refs-to st 'g.core/helper)))]
        (is (= 'go (:name (store/form-by-id st (:from-form r)))))))
    (testing "self-references are excluded; unknown targets empty"
      (is (empty? (refs/refs-to st 'g.core/nope))))))

(deftest the-wire-codec-slims-references
  ;; canonical records are INTERNAL; the wire always carries the compact
  ;; form — grouped by target, self-describing qsyms, via-tagged only when
  ;; not the common :static. Names are the ONLY reference currency on the
  ;; wire (opaque ids fail unsafe: a mistyped id can silently resolve to a
  ;; real other reference; a mistyped name fails loudly).
  (let [st (-> (store/empty-store)
               (store/ingest 'w.core
                             (str "(ns w.core)\n\n"
                                  "(defn ^:entry-point helper \"H.\" [x] x)\n"))
               (store/ingest 'w.app
                             (str "(ns w.app (:require [w.core :as core]))\n\n"
                                  "(defn go \"G.\" [x] (core/helper x))\n\n"
                                  "(defn drive \"D.\" [sess]\n"
                                  "  (query-call sess 'w.core/helper 2))\n")))
        rs (refs/refs-to st 'w.core/helper)
        w  (refs/to-wire rs)]
    (testing "grouped and strictly slimmer"
      (is (= 'w.core/helper (:to w)))
      (is (= '[w.app/drive w.app/go]
             (vec (sort (concat (:from w) (keep :from (:tagged w))))))
          (pr-str w))
      (is (< (count (pr-str w)) (count (pr-str rs)))
          (str (count (pr-str w)) " vs " (count (pr-str rs)))))
    (testing "non-static references carry their tag; declarations show the dial"
      (is (some #(and (= 'w.app/drive (:from %)) (= :carrier (:via %)))
                (:tagged w)))
      (is (some #(= :entry-point (:marker %)) (:tagged w))))
    (testing "records anchor BOTH ends to forms (rewriters and post-conditions)"
      (let [r (first (filter #(= :static (:via %)) rs))]
        (is (:from-form r))
        (is (= 'helper (:name (store/form-by-id st (:to-form r)))))))))

(deftest self-references-never-count-any-producer
  ;; a form referencing ITSELF — statically OR through a carrier — is not
  ;; a reference (replacing the form covers it). The carrier producer
  ;; lacked this exclusion, so a form could keep ITSELF alive through a
  ;; carrier self-ref (eval-reproduced); drop-self is the uniform fix.
  (let [st (-> (store/empty-store)
               (store/ingest 'sr.core
                             (str "(ns sr.core)\n\n"
                                  "(defn loops \"L.\" [s] (query-call s 'sr.core/loops 1))\n\n"
                                  "(defn calls-self \"C.\" [x] (sr.core/calls-self x))\n\n"
                                  "(defn dead \"D.\" [x] x)\n")))]
    (is (empty? (refs/refs-to st 'sr.core/loops)) "carrier self-ref excluded")
    (is (empty? (refs/refs-to st 'sr.core/calls-self)) "qualified self-ref excluded")
    ;; a genuine CROSS-form reference still lands (exclusion is self-only)
    (let [st2 (store/ingest st 'sr.user
                            "(ns sr.user (:require [sr.core :as c]))\n(defn u [x] (c/dead x))\n")]
      (is (seq (refs/refs-to st2 'sr.core/dead))))))

(deftest walk-pruned-is-the-one-quote-aware-traversal
  (let [collect (fn [x] (vec (refs/walk-pruned
                              (fn [n] (when (and (symbol? n) (namespace n)) [n]))
                              x)))]
    (testing "yields qualified symbols, prunes quoted subtrees"
      (is (= '[a.b/c d.e/f]
             (collect '(defn g [] (a.b/c (quote x.y/z)) (when true d.e/f)))))
      (is (= '[]  (collect '(quote (a.b/c d.e/f)))))
      (is (= '[m.n/k m.n/v] (collect '{m.n/k m.n/v}))
          "maps: keys and vals both walked"))
    (testing "callers see SEQ nodes too (carrier-position extraction)"
      (is (= '[(query-call s (quote a.b/f))]
             (vec (refs/walk-pruned
                   (fn [n] (when (and (seq? n) (= 'query-call (first n))) [n]))
                   '(defn g [s] (query-call s (quote a.b/f))))))))
    (testing "and :tag hints on nodes"
      (is (= '[Foo]
             (vec (refs/walk-pruned
                   (fn [n] (when-let [t (:tag (meta n))] [t]))
                   '(defn g [^Foo x] x))))))))

(deftest refs-memoizes-on-the-immutable-store-value
  ;; refs is O(store) — repeatedly rebuilding the whole graph to answer
  ;; refs-to / unused / review on the SAME store value is waste. Memoize
  ;; on value identity (immutable store → a new value only on a write):
  ;; same value returns the identical vector; a changed store rebuilds.
  (let [st  (store/ingest (store/empty-store) 'mm.core
                          "(ns mm.core (:require [clojure.string :as s]))\n(defn f [x] (s/trim x))\n")
        a   (refs/refs st)
        b   (refs/refs st)]
    (is (identical? a b) "same store value → cached, not rebuilt")
    (let [st2 (store/ingest st 'mm.two
                            "(ns mm.two (:require [mm.core :as c]))\n(defn g [x] (c/f x))\n")
          c   (refs/refs st2)]
      (is (not (identical? a c)) "a changed store rebuilds")
      (is (some #(= 'mm.core (:to-ns %)) c) "and reflects the new reference"))))

(deftest cold-load-order-resolves-forward-refs
  ;; the arrangement a fresh load resolves top-to-bottom WITHOUT declares —
  ;; defs before their intra-ns callers (Kahn over the reference graph);
  ;; genuine mutual recursion is named as a :cycle (those need a declare).
  (testing "a simple forward ref reorders to defs-first"
    (let [st (store/ingest (store/empty-store) 'co.core
                           (str "(ns co.core)\n"
                                "(defn top \"T.\" [x] (helper x))\n"    ; uses helper (below)
                                "(defn helper \"H.\" [x] (inc x))\n"))
          r  (refs/cold-load-order st 'co.core)]
      (is (nil? (:cycle r)))
      ;; ns decl first, then helper before top
      (is (= '[co.core helper top]
             (mapv #(:name (store/form-by-id st %)) (:order r)))
          (pr-str r))))
  (testing "mutual recursion is reported as a cycle, not silently mis-ordered"
    (let [st (store/ingest (store/empty-store) 'co.rec
                           (str "(ns co.rec)\n"
                                "(defn ping \"P.\" [n] (when (pos? n) (pong (dec n))))\n"
                                "(defn pong \"P.\" [n] (when (pos? n) (ping (dec n))))\n"))
          r  (refs/cold-load-order st 'co.rec)]
      (is (= #{'co.rec/ping 'co.rec/pong} (set (:cycle r))) (pr-str r))))
  (testing "an already-ordered ns is unchanged"
    (let [st (store/ingest (store/empty-store) 'co.ok
                           "(ns co.ok)\n(defn a \"A.\" [x] x)\n(defn b \"B.\" [x] (a x))\n")
          r  (refs/cold-load-order st 'co.ok)]
      (is (nil? (:cycle r)))
      (is (= '[co.ok a b] (mapv #(:name (store/form-by-id st %)) (:order r)))))))

(deftest web-declarations-are-edges-from-the-outside-world
  (let [st (-> (store/empty-store)
               (store/ingest 'w.api
                             (str "(ns w.api)\n\n"
                                  "(defn ^{:web/method :get :web/path \"/api/users/:id\"} get-user \"U.\" [req] req)\n\n"
                                  "(defn ^{:web/effect :user/insert} insert-user! \"I.\" [ctx row] row)\n\n"
                                  "(defn ^{:web/read :user/by-id} user-by-id \"R.\" [ctx id] id)\n\n"
                                  "(defn plain \"P.\" [x] x)\n")))]
    (testing "an endpoint form is declared-invoked by the dispatcher"
      (let [r (first (refs/refs-to st 'w.api/get-user))]
        (is (= :declared (:via r)) (pr-str r))
        (is (= :web-endpoint (:marker r)))
        (is (= :external (:from-ns r)))))
    (testing "effect and read performers are declared-invoked by the interpreter"
      (is (= :web-effect (:marker (first (refs/refs-to st 'w.api/insert-user!)))))
      (is (= :web-read (:marker (first (refs/refs-to st 'w.api/user-by-id))))))
    (testing "an unmarked form gains no edge"
      (is (empty? (refs/refs-to st 'w.api/plain))))))

(deftest covered-by-honours-declared-coverage
  (let [st (-> (store/empty-store)
               (store/ingest 'cov.core "(ns cov.core)\n(defn dispatched [] 1)\n")
               (store/ingest 'cov.core-test
                             (str "(ns cov.core-test (:require [clojure.test :refer [deftest is]]))\n"
                                  "(deftest ^{:covers \"cov.core/dispatched — reached only via dispatch\"} dispatch-t\n"
                                  "  (is true))\n")))]
    (testing "a ^{:covers} marker on a deftest is a DECLARED coverage edge in THE graph"
      (let [r (first (filter #(= :covers (:marker %)) (refs/refs-to st 'cov.core/dispatched)))]
        (is (some? r) (pr-str (refs/refs-to st 'cov.core/dispatched)))
        (is (= :declared (:via r)))
        (is (= 'cov.core-test (:from-ns r)))
        (is (= 'dispatch-t (:from-var r)))))
    (testing "covered-by reports the declared test with :via :declared and no hop distance"
      (let [cb (refs/covered-by st {} 'cov.core/dispatched)
            by (into {} (map (juxt :test identity)) cb)]
        (is (= #{'cov.core-test/dispatch-t} (set (map :test cb))) (pr-str cb))
        (is (= #{:declared} (:via (by 'cov.core-test/dispatch-t))))
        (is (nil? (:hops (by 'cov.core-test/dispatch-t))))))
    (testing "declared coverage fuses with static and observed on the same form"
      (let [st+ (store/ingest st 'cov.reach
                              (str "(ns cov.reach (:require [cov.core :as c]))\n"
                                   "(defn r [] (c/dispatched))\n"))
            st2 (store/ingest st+ 'cov.reach-test
                              (str "(ns cov.reach-test (:require [clojure.test :refer [deftest is]] [cov.reach :as r]))\n"
                                   "(deftest static-t (is (= 1 (r/r))))\n"))
            cb  (into {} (map (juxt :test :via)) (refs/covered-by st2 {} 'cov.core/dispatched))]
        (is (= #{:declared} (cb 'cov.core-test/dispatch-t)) (pr-str cb))
        (is (= #{:static} (cb 'cov.reach-test/static-t)))))))

(deftest covered-by-fuses-observed-and-static-with-provenance
  (let [st (-> (store/empty-store)
               (store/ingest 'cov.core "(ns cov.core)\n(defn leaf [] 1)\n(defn mid [] (leaf))\n")
               (store/ingest 'cov.core-test
                             (str "(ns cov.core-test (:require [clojure.test :refer [deftest is]] [cov.core :as c]))\n"
                                  "(deftest direct-t (is (= 1 (c/leaf))))\n"
                                  "(deftest via-mid-t (is (= 1 (c/mid))))\n")))]
    (testing "static coverage: a deftest reaching the form within depth, with hop distance and :via :static"
      (let [cb (refs/covered-by st {} 'cov.core/leaf)
            by (into {} (map (juxt :test identity)) cb)]
        (is (= #{'cov.core-test/direct-t 'cov.core-test/via-mid-t} (set (map :test cb))) (pr-str cb))
        (is (= #{:static} (:via (by 'cov.core-test/direct-t))))
        (is (= 1 (:hops (by 'cov.core-test/direct-t))))
        (is (= 2 (:hops (by 'cov.core-test/via-mid-t))) "leaf is reached through mid at 2 hops")))
    (testing "depth bounds the transitive reach"
      (is (= #{'cov.core-test/direct-t}
             (set (map :test (refs/covered-by st {} 'cov.core/leaf :depth 1))))
          "at depth 1 only the direct test covers leaf"))
    (testing "observed evidence joins as :via :observed, unioned per test"
      (let [tmap {'cov.core-test/direct-t #{'cov.core/leaf}}
            cb   (into {} (map (juxt :test :via)) (refs/covered-by st tmap 'cov.core/leaf))]
        (is (= #{:observed :static} (cb 'cov.core-test/direct-t)) (pr-str cb))
        (is (= #{:static} (cb 'cov.core-test/via-mid-t)))))))

(deftest refs-by-target-agrees-with-the-scan-it-replaces
  ;; refs-to filtered the WHOLE reference graph on every call — 7,578 edges in
  ;; slopp's own store — so a page asking "who references this?" once per form
  ;; was quadratic in the store. refs-by-target groups the same records ONCE,
  ;; memoized on the store value exactly as refs is, and refs-to becomes a map
  ;; lookup.
  ;;
  ;; The contract that matters is that it is the same answer — same rows, same
  ;; order — for EVERY target in the graph. An index that disagrees with the
  ;; scan is a second producer of one relationship, which is the failure this
  ;; codebase keeps paying for; so the expected value here is the original
  ;; filtering algorithm written out, deliberately NOT refs-to.
  (let [st       (-> (store/empty-store)
                     (store/ingest 'bt.core
                                   "(ns bt.core)\n(defn f [x] x)\n(defn g [x] (f (f x)))\n")
                     (store/ingest 'bt.two
                                   "(ns bt.two (:require [bt.core :as c]))\n(defn h [x] (c/f (c/g x)))\n"))
        scan     (fn [qsym]
                   (vec (filter #(and (= (symbol (namespace qsym)) (:to-ns %))
                                      (= (symbol (name qsym)) (:to-name %)))
                                (refs/refs st))))
        targets  (distinct (map #(symbol (str (:to-ns %)) (str (:to-name %)))
                                (refs/refs st)))
        idx      (refs/refs-by-target st)]
    (is (seq targets) "fixture must actually produce references to index")
    (is (identical? idx (refs/refs-by-target st))
        "memoized on the store value, the same way refs is")
    (testing "every target agrees, index and scan"
      (doseq [q targets]
        (is (= (scan q) (get idx q)) (str "index disagrees with the scan for " q))
        (is (= (scan q) (refs/refs-to st q))
            (str "refs-to disagrees with the scan for " q))))
    (testing "an unreferenced target is absent from the index, and refs-to says []"
      (is (nil? (get idx 'bt.core/never-called)))
      (is (= [] (refs/refs-to st 'bt.core/never-called))))))

(deftest occurrences-of-is-the-one-set-a-rename-must-answer-to
  ;; THE reference graph is a graph of var/namespace references DISCOVERED BY
  ;; ANALYSIS. Everything else that names a thing — a string, a quoted require
  ;; symbol, a docstring mention, a -test sibling's own name, a register key,
  ;; a qualified KEYWORD — is not a "reference" under that model and had no
  ;; home, so each rename verb wired up a different subset. Measured on
  ;; slopp's own store before this landed: 158 string literals name a live
  ;; namespace, 13 of them load-bearing token strings across 12 sites (a
  ;; generated deps.edn main-ns, a child JVM's program text, a path assertion)
  ;; — every one invisible to ns_rename and unreported by it.
  ;;
  ;; Conservative rewriting is RIGHT. Silent conservative rewriting is not.
  (let [st  (-> (store/empty-store)
                (store/ingest 'oc.helper "(ns oc.helper)\n(defn h \"H.\" [] 1)\n")
                (store/ingest 'oc.helper-test
                              (str "(ns oc.helper-test (:require [oc.helper :as h]))\n"
                                   "(defn t \"T.\" [] (h/h))\n"))
                (store/ingest 'oc.user
                              (str "(ns oc.user (:require [oc.helper :as h]))\n"
                                   "(defn u \"Calls oc.helper for real.\" [] (h/h))\n"
                                   "(defn boot \"B.\" [] (require 'oc.helper) \"oc.helper\")\n"
                                   "(defn cfg \"C.\" [] {:oc.helper/mode :fast})\n"))
                (as-> s (first (store/record-module-tier s "oc.helper" :pure))))
        occ (refs/occurrences-of st 'oc.helper)
        by  (group-by :via occ)]
    (testing "the require clause — rewritable, and always was"
      (is (some #(= 'oc.user (:ns %)) (:require by)) (pr-str (:require by))))
    (testing "a quoted symbol in code is still a symbol to the CST rewrite"
      (is (some #(= 'boot (:form %)) (:symbol by)) (pr-str (:symbol by))))
    (testing "a namespace name inside a STRING — reported, never silently rewritten"
      (let [ss (:string by)]
        (is (seq ss))
        (is (every? #(false? (:rewritable %)) ss)
            "the whole point: these are what the symbol pass cannot reach")
        (is (some #(and (= 'boot (:form %)) (false? (:prose %))) ss)
            "a token string — a path, a main-ns, a require target — is LOAD-BEARING")
        (is (some #(and (= 'u (:form %)) (true? (:prose %))) ss)
            "a docstring mention is prose: wrong after a rename, not broken")))
    (testing "a QUALIFIED KEYWORD names the namespace and nothing rewrites it"
      ;; The silent member of the set. A keyword is not a reference, so the
      ;; symbol pass walks straight past `:oc.helper/mode` — and unlike a
      ;; broken token string, nothing turns red afterwards. The name simply
      ;; starts lying. Measured in anger during the slopp.api.telemetry →
      ;; slopp.read.telemetry rename: `:slopp.api.telemetry/calls` survived
      ;; intact in 3 forms across 5 sites, through a green full_check.
      (let [ks (:keyword by)]
        (is (seq ks) (pr-str occ))
        (is (some #(= 'cfg (:form %)) ks) (pr-str ks))
        (is (every? #(false? (:rewritable %)) ks)
            "deliberately NOT rewritten: a qualified keyword can be a wire or
             storage key an outside consumer already holds")
        (is (some #(= ":oc.helper/mode" (:text %)) ks)
            "the keyword itself, so the judgement can be made from the report")))
    (testing "the -test sibling names its subject — a convention, not a coincidence"
      (is (= 'oc.helper-test (:ns (first (:test-sibling by))))))
    (testing "a register key names the namespace too"
      (is (seq (:register by)) (pr-str occ)))
    (testing "nothing is claimed for a namespace nobody mentions"
      (is (= [] (refs/occurrences-of st 'oc.absent))))))

(deftest every-way-a-name-can-appear-is-DECLARED-and-every-DECLARATION-is-REAL
  ;; `occurrences-of` carried this vocabulary as a TABLE IN ITS DOCSTRING —
  ;; seven rows, accurate prose, and nothing that could grade it. Measured the
  ;; day it became data: the `:register` row named three registers and the
  ;; store had FIVE, so a `:module-roles` declaration was re-keyed by the
  ;; rename and invisible to the report that exists to say what the rename left
  ;; behind, and `:module-test-edges` was in neither.
  ;;
  ;; The rule copied from `crossings/kinds`, one layer in: a kind with no
  ;; producer must SAY it has none. A kind slopp cannot see emits no row, so
  ;; until it is written down, "nothing produces this" and "this store has none
  ;; of these" are the same observation — the absence-of-check /
  ;; absence-of-finding conflation moved up a level, out of a check's result
  ;; and into the vocabulary the check reports in.
  (let [st   (-> (store/empty-store)
                 (store/ingest 'mk.helper "(ns mk.helper)\n(defn h \"H.\" [] 1)\n")
                 (store/ingest 'mk.helper-test
                               (str "(ns mk.helper-test (:require [mk.helper :as h]))\n"
                                    "(defn t \"T.\" [] (h/h))\n"))
                 (store/ingest 'mk.user
                               (str "(ns mk.user (:require [mk.helper :as h]))\n"
                                    "(defn u \"Calls mk.helper for real.\" [] (h/h))\n"
                                    "(defn boot \"B.\" [] (require 'mk.helper) \"mk.helper\")\n"
                                    "(defn cfg \"C.\" [] {:mk.helper/mode :fast})\n"
                                    "(defn scan \"S.\" [s] (re-find #\"mk\\.helper\" s))\n"
                                    "(defn seed \"Sd.\" [] \"(ns mk.helper)\\n(defn q [] 1)\\n\")\n"))
                 (as-> s (first (store/record-module-tier s "mk.helper" :pure))))
        seen (set (map :via (refs/occurrences-of st 'mk.helper)))
        rows refs/mention-kinds
        mine (filter #(= 'occurrences-of (:producer %)) rows)]
    (testing "control: the fixture produced kinds at all"
      ;; every assertion below is satisfied by an empty set, and a fixture that
      ;; failed to build satisfies every absence assertion downstream of it
      (is (< 3 (count seen)) (str "the fixture must plant most of the vocabulary: " seen)))

    (testing "no VACUOUS row — every kind this producer claims, it produces"
      (let [claimed (set (map :kind (remove #(= :blind (:handling %)) mine)))]
        (is (= #{} (clojure.set/difference claimed seen))
            (str "declared and never produced — a row that describes nothing"
                 " reads exactly like coverage: " (clojure.set/difference claimed seen)))))

    (testing "no UNDECLARED kind — every :via produced has a row"
      (is (= #{} (clojure.set/difference seen (set (map :kind rows))))
          (str "produced with no row, so nothing states what a verb does with"
               " it: " (clojure.set/difference seen (set (map :kind rows))))))

    (testing "a kind with no producer must SAY it is blind"
      ;; the crossings rule, and the whole reason a registry beats a table: a
      ;; blind spot is a legitimate answer and an unstated one is not
      (doseq [r rows]
        (is (contains? #{:rewrite :report :blind} (:handling r))
            (str (:kind r) " must declare :handling: " (pr-str r)))
        (is (not (clojure.string/blank? (str (:what r))))
            (str (:kind r) " must say what the appearance IS: " (pr-str r)))
        (when (nil? (:producer r))
          (is (= :blind (:handling r))
              (str (:kind r) " names no producer, so it is blind whether or"
                   " not it says so: " (pr-str r))))
        (when (= :blind (:handling r))
          (is (not (clojure.string/blank? (str (:blind r))))
              (str (:kind r) " is blind and must state what is NOT covered —"
                   " that sentence is the only artifact a blind kind has: "
                   (pr-str r))))))))

(deftest a-regex-literal-spelling-the-name-is-an-OCCURRENCE
  ;; The kind that was declared BLIND, being made producible. A pattern is
  ;; data: no rename verb rewrites it, and a regex escapes its dots so even
  ;; the text sweep misses the spelling. The form goes on compiling and goes
  ;; on passing while searching for a string that can no longer occur — and
  ;; the two outcomes are not equal. A presence assertion turns red; an
  ;; ABSENCE assertion becomes permanently true, which is how a guard shipped
  ;; green over an empty search twice in this restructure.
  ;;
  ;; `rules/stale-pattern-check` already finds a pattern naming a namespace
  ;; that does not EXIST. That is the same defect one done later, and only
  ;; when the old name goes unused: a rename that frees a name for reuse
  ;; leaves a pattern matching the WRONG code, which no existence check can
  ;; see. This reports it at the moment the rename creates it.
  ;;
  ;; Measured before it was written: over a fixture with a regex naming a live
  ;; namespace, `occurrences-of` returned only the target's own `:ns-form` —
  ;; not even a `:string` row for the pattern text, because a regex node's
  ;; text is not a string token.
  (let [st  (-> (store/empty-store)
                (store/ingest 'rx.helper "(ns rx.helper)\n(defn h \"H.\" [] 1)\n")
                (store/ingest 'rx.guard
                              (str "(ns rx.guard)\n"
                                   "(defn leaks \"Names it in a pattern.\" [s]\n"
                                   "  (re-find #\"rx\\.helper\" s))\n")))
        by  (group-by :via (refs/occurrences-of st 'rx.helper))]
    (testing "the pattern is reported, with its text, so it can be judged"
      (let [rs (:regex by)]
        (is (seq rs) (str "a regex naming the target is an appearance: "
                          (pr-str (refs/occurrences-of st 'rx.helper))))
        (is (some #(= 'leaks (:form %)) rs) (pr-str rs))
        (is (some #(re-find #"helper" (str (:text %))) rs)
            (str "the pattern TEXT, because the judgement is whether it still"
                 " matches what it was written to find: " (pr-str rs)))))
    (testing "never rewritten — an escaped pattern is not a substitution"
      (is (every? #(false? (:rewritable %)) (:regex by)) (pr-str (:regex by))))
    (testing "and a pattern naming nothing in this store says nothing"
      (is (empty? (:regex (group-by :via (refs/occurrences-of st 'rx.absent))))
          "a name no pattern spells must not produce a row"))))

(deftest source-text-inside-a-string-is-its-own-kind-of-appearance
  ;; The second blind row, and the one with a shipped instance. `slopp.mcp-test`
  ;; builds scratch stores as
  ;;   (store/ingest st 'slopp.http-api.reads (str "(ns slopp.http-api.reads)\n\n" …))
  ;; and a rename rewrote the quoted SYMBOL — correctly, it is a token — while
  ;; the `(ns …)` inside the string one line below it stayed. The fixture then
  ;; ingested source declaring one namespace under the name of another, and both
  ;; tests stayed green because each asserts nil.
  ;;
  ;; A `:string` row was produced for it, so the appearance was never invisible.
  ;; What was invisible is that the text is CODE — the same weight as the symbol
  ;; beside it, and the only occurrence whose two halves are meant to agree. A
  ;; reader told "1 occurrence in a token string" ranks that below a path.
  (let [st  (-> (store/empty-store)
                (store/ingest 'ss.helper "(ns ss.helper)\n(defn h \"H.\" [] 1)\n")
                (store/ingest 'ss.fixture
                              (str "(ns ss.fixture)\n"
                                   "(defn build \"Ingests a scratch namespace.\" []\n"
                                   "  [(quote ss.helper) \"(ns ss.helper)\\n(defn q [] 1)\\n\"])\n"
                                   "(defn note \"Mentions ss.helper in prose.\" [] \"see ss.helper for why\")\n")))
        by  (group-by :via (refs/occurrences-of st 'ss.helper))]
    (testing "the source string is reported as SOURCE, not as an ordinary string"
      (let [ss (:string-source by)]
        (is (seq ss)
            (str "a string whose text DECLARES the target is a different"
                 " appearance from one that mentions it: " (pr-str by)))
        (is (some #(= 'build (:form %)) ss) (pr-str ss))))
    (testing "the quoted symbol beside it is still rewritable — that is the pair"
      ;; the two halves of one fixture, three tokens apart, and only one moves
      (is (some #(and (= 'build (:form %)) (true? (:rewritable %))) (:symbol by))
          (pr-str (:symbol by))))
    (testing "an ordinary prose mention is NOT promoted"
      (is (some #(and (= 'note (:form %)) (true? (:prose %))) (:string by))
          (str "only text that declares the namespace is source: " (pr-str (:string by)))))
    (testing "and source text is never rewritten — it is reported to be judged"
      (is (every? #(false? (:rewritable %)) (:string-source by))
          (pr-str (:string-source by))))))
