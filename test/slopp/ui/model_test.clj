(ns slopp.ui.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ui.model :as model]
            [slopp.store :as store]))

(defn- json-shaped?
  "True when `x` would survive a JSON round trip unchanged in structure:
  maps keyed by keywords, vectors (never lists or sets, which JSON cannot
  tell apart), and scalars. SYMBOLS are the one that matters — a qualified
  symbol reads back as a string and silently stops being a reference, so
  the models carry strings and the pages never have to know the difference."
  [x]
  (cond
    (map? x)    (and (every? keyword? (keys x)) (every? json-shaped? (vals x)))
    (vector? x) (every? json-shaped? x)
    (or (string? x) (number? x) (boolean? x) (keyword? x) (nil? x)) true
    :else false))

(deftest timeline-is-the-reviewer-landing-model
  ;; The landing page answers two questions in the order a reviewer asks
  ;; them: "what has been finished?" (milestones, newest first) and "what is
  ;; in flight?" (everything written since the newest one). Both are folds
  ;; over the delta log, so the fixture IS a delta log, written longhand.
  (let [st (assoc (store/empty-store)
                  :deltas
                  [{:id "d1" :op :add :ns 'demo.core :form-id "f1" :prompt "the first form"}
                   {:id "d2" :op :commit :target "d1" :status :green :at 1784900000000
                    :description "first milestone\nand a body line"}
                   {:id "d3" :op :add :ns 'demo.core :form-id "f2" :prompt "a second form"}
                   {:id "d4" :op :commit :target "d3" :status :green :at 1784900060000
                    :description "second milestone"}
                   {:id "d5" :op :replace :ns 'demo.core :form-id "f1" :prompt "sharpen it"}
                   {:id "d6" :op :add :ns 'demo.util :form-id "f3" :prompt "a helper"}])
        tl (model/timeline (atom {:store st}))]
    (testing "milestones read newest first — the reviewer's scan order"
      (is (= ["d4" "d2"] (mapv :commit (:milestones tl)))))
    (testing "each row carries a precomputed range, so the page has no logic"
      (is (= "d2..d4" (:range (first (:milestones tl))))
          "a milestone's range starts at the milestone before it")
      (is (nil? (:range (second (:milestones tl))))
          "the OLDEST milestone has nothing to diff against — absent, not empty"))
    (testing "descriptions are the title line, with the body counted not dropped"
      (is (= "second milestone" (:description (first (:milestones tl)))))
      (is (= "first milestone" (:description (second (:milestones tl)))))
      (is (= 1 (:more-lines (second (:milestones tl))))))
    (testing "the working set is everything written since the newest milestone"
      (is (= "d4" (get-in tl [:working :since])))
      (is (= 2 (get-in tl [:working :forms])))
      (is (= ["demo.core" "demo.util"] (get-in tl [:working :namespaces]))
          "namespaces arrive as STRINGS — a symbol does not survive JSON")
      (is (= ["sharpen it" "a helper"] (get-in tl [:working :prompts]))
          "the recorded asks, in the order they were made"))
    (testing "the whole model survives a JSON round trip"
      (is (json-shaped? tl) (pr-str tl)))))

(deftest change-view-groups-a-range-module-then-namespace-then-form
  ;; Wave 1 made components REAL namespace prefixes, so component and module
  ;; are now the same rung — the reviewer tree is module → namespace → form,
  ;; two rungs rather than the three the plan sketched before the rename.
  ;;
  ;; Fixture: ingest the FINAL state, so the store's forms and the reference
  ;; graph over them are real, then write the delta log longhand. `store/
  ;; ingest` re-mints form ids when it re-ingests a namespace, so ingesting
  ;; twice would read as delete-plus-add; the log is where a MODIFICATION is
  ;; expressed, and writing it out is what pins `:was` exactly.
  (let [s0      (store/ingest (store/empty-store) 'demo.a.core
                              "(ns demo.a.core)\n\n(defn hello [x] (inc x))\n")
        s1      (store/ingest s0 'demo.b.util
                              (str "(ns demo.b.util (:require [demo.a.core :as core]))\n\n"
                                   "(defn helper [] (core/hello 1))\n"))
        fid     (fn [ns- nm] (:id (store/form-named s1 ns- nm)))
        a-ns    (fid 'demo.a.core 'demo.a.core)
        hello   (fid 'demo.a.core 'hello)
        b-ns    (fid 'demo.b.util 'demo.b.util)
        helper  (fid 'demo.b.util 'helper)
        st      (assoc s1 :deltas
                       [{:id "d1" :op :ingest :ns 'demo.a.core
                         :form-ids [a-ns hello]
                         :sources {a-ns "(ns demo.a.core)" hello "(defn hello [x] x)"}}
                        {:id "c1" :op :commit :status :green :at 1784900000000
                         :description "baseline"}
                        {:id "d2" :op :replace :ns 'demo.a.core :form-id hello
                         :prompt "make hello increment"
                         :sources {hello "(defn hello [x] (inc x))"}}
                        {:id "d3" :op :ingest :ns 'demo.b.util
                         :form-ids [b-ns helper]
                         :sources {b-ns "(ns demo.b.util (:require [demo.a.core :as core]))"
                                   helper "(defn helper [] (core/hello 1))"}}
                        {:id "c2" :op :commit :status :green :at 1784900060000
                         :description "the work"}])
        cv      (model/change-view (atom {:store st}) "c1" "c2")
        by-form (into {} (for [m (:modules cv), n (:namespaces m), f (:forms n)]
                           [(:form f) f]))]
    (testing "the range is echoed back, so a page can title itself"
      (is (= "c1" (:from cv)))
      (is (= "c2" (:to cv))))
    (testing "grouped module → namespace → form, sorted at every rung"
      (is (= ["demo.a" "demo.b"] (mapv :module (:modules cv))))
      (is (= ["demo.a.core"] (mapv :ns (:namespaces (first (:modules cv))))))
      (is (= ["demo.b.util"] (mapv :ns (:namespaces (second (:modules cv))))))
      (is (= ["demo.b.util/demo.b.util" "demo.b.util/helper"]
             (mapv :form (:forms (first (:namespaces (second (:modules cv)))))))
          "forms sort by name, and an ns form is a change like any other"))
    (testing "counts at every rung, so a collapsed row still says how much is under it"
      (is (= 3 (:count cv)))
      (is (= 1 (:count (first (:modules cv)))))
      (is (= 2 (:count (second (:modules cv))))))
    (testing "a modified form carries its identity, status and recorded ask"
      (let [h (by-form "demo.a.core/hello")]
        (is (= :modified (:status h)))
        (is (= "make hello increment" (:why h)))
        (testing "the line diff is the same one history renders"
          (is (= ["(defn hello [x] x)"]
                 (mapv second (filter #(= :del (first %)) (:diff h)))))
          (is (= ["(defn hello [x] (inc x))"]
                 (mapv second (filter #(= :add (first %)) (:diff h))))))
        (testing "blast radius: who calls this form now"
          (is (= 1 (:callers h)) "demo.b.util/helper calls it"))))
    (testing "an added form has no was-side and no callers"
      (let [h (by-form "demo.b.util/helper")]
        (is (= :added (:status h)))
        (is (= 0 (:callers h)))
        (is (every? #(= :add (first %)) (:diff h)))))
    (testing "the whole model survives a JSON round trip"
      (is (json-shaped? cv) (pr-str cv)))))

(deftest form-view-is-the-deep-arrival-model
  ;; A form page is usually arrived at COLD, from a link — Debugger Canvas
  ;; called the failure the "lonely bubble". So the model carries the
  ;; breadcrumb (module, namespace), the callers ABOVE as a categorized
  ;; backlink card, and the callees BELOW with their signature and doc
  ;; INLINED: Code Bubbles measured two-thirds of its win as concurrent
  ;; visibility, not saved navigation, and a link is not visibility.
  (let [st     (-> (store/empty-store)
                   (store/ingest 'demo.a.core
                                 "(ns demo.a.core)\n\n(defn hello \"Adds one.\" [x] (inc x))\n")
                   (store/ingest 'demo.b.util
                                 (str "(ns demo.b.util (:require [demo.a.core :as core]))\n\n"
                                      "(defn helper [] (core/hello 1))\n")))
        sess   (atom {:store st})
        fid    (fn [ns- nm] (:id (store/form-named st ns- nm)))
        hello  (model/form-view sess (fid 'demo.a.core 'hello))
        helper (model/form-view sess (fid 'demo.b.util 'helper))]
    (testing "identity and breadcrumb — where am I, in one glance"
      (is (= "demo.a.core/hello" (:form hello)))
      (is (= "hello" (:name hello)))
      (is (= "demo.a.core" (:ns hello)))
      (is (= "demo.a" (:module hello)))
      (is (= (fid 'demo.a.core 'hello) (:form-id hello))))
    (testing "the form's own card, flattened — signature as text, not symbols"
      (is (= "[x]" (:sig hello)) "a signature is rendered, since JSON has no symbols")
      (is (= "Adds one." (:doc hello)))
      (is (re-find #"\(defn hello" (:source hello))))
    (testing "callers arrive as a CARD, categorized by how the edge was found"
      (let [static (first (filter #(= :static (:via %)) (:callers hello)))]
        (is (= 1 (:count static)))
        (is (= ["demo.b.util/helper"] (mapv :form (:forms static))))
        (is (= "demo.b" (:module (first (:forms static))))
            "a caller row carries its module, so the card reads as a map")
        (is (= (fid 'demo.b.util 'helper) (:form-id (first (:forms static))))
            "every edge links a permalink — names change, ids do not")))
    (testing "the graph is never presented as complete"
      (is (string? (:note hello)) "a standing honesty line about what a syntactic reader misses"))
    (testing "callees are INLINED, with the callee's own signature and doc"
      (let [callee (first (:callees helper))]
        (is (= "demo.a.core/hello" (:form callee)))
        (is (= "[x]" (:sig callee)))
        (is (= "Adds one." (:doc callee)))
        (is (= "demo.a" (:module callee)))))
    (testing "a leaf has no callees, and a root has no callers — absent, not empty rows"
      (is (empty? (:callees hello)))
      (is (empty? (:callers helper))))
    (testing "an unknown form id is nil, so the page can 404 rather than render blank"
      (is (nil? (model/form-view sess "f-nope"))))
    (testing "the whole model survives a JSON round trip"
      (is (json-shaped? hello) (pr-str hello))
      (is (json-shaped? helper) (pr-str helper)))))

(deftest change-view-refuses-a-range-that-names-nothing
  ;; A range arrives from a URL, so it is user input. "Nothing changed
  ;; between these two milestones" and "those are not delta ids" are
  ;; different answers, and a page that cannot tell them apart renders an
  ;; empty review where it owes a 404 (D-surface-honesty).
  (let [st   (assoc (store/empty-store)
                    :deltas [{:id "d1" :op :add :form-id "f1"}
                             {:id "d2" :op :add :form-id "f2"}])
        sess (atom {:store st})]
    (is (nil? (model/change-view sess "nope" "alsonope")))
    (is (nil? (model/change-view sess "d1" "alsonope")))
    (is (nil? (model/change-view sess nil "d2")))
    (testing "a real but empty range is a MAP with zero forms, not nil"
      (is (= 0 (:count (model/change-view sess "d2" "d2")))))))

(deftest timeline-caps-what-a-landing-page-shows
  ;; Found by serving slopp's own store: nineteen forms in flight rendered
  ;; nineteen full-length prompts, and one milestone whose title line is a
  ;; whole paragraph (no newline after the title) printed all of it. A
  ;; LANDING model has to be bounded — form-card already snips for exactly
  ;; this reason. Bounded here rather than in the page, so a JSON sink is
  ;; bounded too.
  (let [long-text (fn [n] (apply str (repeat n "and on ")))
        st (assoc (store/empty-store)
                  :deltas
                  (into [{:id "c1" :op :commit :status :green :at 1784900000000
                          :description (str "a title that runs on " (long-text 40))}]
                        (for [i (range 15)]
                          {:id (str "d" i) :op :add :ns 'demo.core :form-id (str "f" i)
                           :prompt (str "ask " i " " (long-text 40))})))
        tl (model/timeline (atom {:store st}))]
    (testing "a milestone title is capped, and says it was cut"
      (let [d (:description (first (:milestones tl)))]
        (is (<= (count d) 120) (str "was " (count d)))
        (is (re-find #"…$" d) d)))
    (testing "the working set shows a bounded number of asks and COUNTS the rest"
      (is (= 8 (count (get-in tl [:working :prompts]))))
      (is (= 7 (get-in tl [:working :more-prompts]))
          "absence is not the same as nothing more — the page must be able to say so")
      (is (every? #(<= (count %) 120) (get-in tl [:working :prompts])))
      (is (= 15 (get-in tl [:working :forms]))
          "the COUNT is exact; only the listing is capped"))
    (testing "under the cap there is no more-prompts key at all"
      (let [small (assoc (store/empty-store) :deltas
                         [{:id "d1" :op :add :ns 'demo.core :form-id "f1" :prompt "one ask"}])]
        (is (nil? (get-in (model/timeline (atom {:store small})) [:working :more-prompts])))))))

(deftest source-tokens-are-a-lossless-view-of-the-stored-cst
  ;; Every stored element carries a rewrite-clj node, so highlighting is a
  ;; walk over a TREE the store already has — no lexer, no dependency, no
  ;; client script. The property that makes it safe is losslessness:
  ;; concatenating the token text reproduces the source byte for byte, so
  ;; the tokenizer cannot silently drop, reorder or normalise what it
  ;; renders. Everything it cannot classify is plain text, never omitted.
  (let [src  (str "(defn greet\n"
                  "  \"Says hi.\"\n"
                  "  [nm]\n"
                  "  ;; a comment\n"
                  "  {:greeting (str \"hi \" nm) :n 42})\n")
        st   (store/ingest (store/empty-store) 'demo.core
                           (str "(ns demo.core)\n\n" src))
        v    (model/form-view (atom {:store st}) (:id (store/form-named st 'demo.core 'greet)))
        toks (:tokens v)
        cls  (fn [c] (mapv second (filter #(= c (first %)) toks)))]
    (testing "lossless: the tokens ARE the source"
      (is (= (:source v) (apply str (map second toks)))))
    (testing "each token is [class text], both strings — JSON has no keywords in arrays"
      (is (every? #(and (= 2 (count %)) (every? string? %)) toks) (pr-str (take 5 toks))))
    (testing "the classes the CST can tell apart without guessing"
      (is (some #{"\"Says hi.\""} (cls "string")))
      (is (some #{";; a comment\n"} (cls "comment"))
          "a comment node OWNS its terminating newline — that is what makes it lossless")
      (is (some #{":greeting"} (cls "keyword")))
      (is (some #{"42"} (cls "number")))
      (is (some #{"defn"} (cls "special")) "a definition head reads differently from a call")
      (is (some #{"("} (cls "delim"))))
    (testing "a string containing delimiters stays ONE token — this is a tree walk, not a regex"
      (is (some #{"\"hi \""} (cls "string"))))
    (testing "unclassifiable text is carried, never dropped"
      (is (some #{"nm"} (cls "text"))))))

(deftest form-view-takes-a-fidelity-and-names-the-ones-it-has
  ;; A form page will eventually render more than literal Clojure — a
  ;; labeled notation is the follow-up project. WHICH fidelity you are
  ;; looking at belongs in the URL from day one: adding it later means
  ;; every permalink already in the wild silently meant "whatever the
  ;; default became". Free now, expensive then, so it exists with ONE value.
  (let [st   (store/ingest (store/empty-store) 'demo.core
                           "(ns demo.core)\n\n(defn f \"D.\" [x] x)\n")
        sess (atom {:store st})
        fid  (:id (store/form-named st 'demo.core 'f))]
    (testing "the default is the fidelity we have, and the model names them all"
      (is (= "clojure" (:view (model/form-view sess fid))))
      (is (= ["clojure"] (:views (model/form-view sess fid)))
          "a page offers what EXISTS rather than hard-coding a list"))
    (testing "asking for it explicitly is the same page"
      (is (= (model/form-view sess fid) (model/form-view sess fid "clojure"))))
    (testing "a fidelity that does not exist is nil, not a silent fallback"
      (is (nil? (model/form-view sess fid "labeled"))
          "when labeled lands this starts working; until then it must not pretend")
      (is (nil? (model/form-view sess fid "garbage"))))
    (testing "an absent parameter is the default, since a bare permalink has none"
      (is (= "clojure" (:view (model/form-view sess fid nil)))))
    (testing "still JSON-shaped with the fidelity keys on board"
      (is (json-shaped? (model/form-view sess fid))))))
