(ns slopp.store-test
  "Tests for the element model and the delta log — what a namespace IS, and
  what the journal records about changing it.

  The through-line is that the log must be a complete account. A delta that
  does not carry what it changed leaves state recoverable only from the
  materialized rows, and everything built on the log — foreign replay, CRDT
  merge, time travel, the git projection — then quietly depends on something
  it cannot see. That is the failure comments had for most of slopp's life:
  stored positionally, recorded nowhere, and reachable only by snapshotting
  bytes.

  So these tests care less about return values than about whether a change
  SURVIVES a round trip through the log alone. `replay-delta` is the honest
  oracle for that, and a new op earns its place by replaying."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store] [rewrite-clj.parser :as p] [slopp.store.render :as render]))

(def src "(ns foo)\n\n(defn add [x y]\n  (+ x y))\n\n;; a comment\n(def z 1)\n")

(deftest ingest-extracts-forms
  (let [s (store/ingest (store/empty-store) 'foo src)
        forms (store/forms s 'foo)]
    (testing "one Form per top-level sexpr (whitespace/comments are separators)"
      (is (= 3 (count forms))))
    (testing "names derived from def*/ns head"
      (is (= '[foo add z] (mapv :name forms))))
    (testing "every form gets a unique synthetic id (C2)"
      (is (every? :id forms))
      (is (apply distinct? (map :id forms))))))

(deftest ingest-appends-a-delta
  (let [s (store/ingest (store/empty-store) 'foo src)
        ds (store/deltas s)]
    (is (= 1 (count ds)))
    (is (= :ingest (:op (first ds))))
    (is (= 'foo (:ns (first ds))))
    (is (= 3 (count (:form-ids (first ds)))))))

(deftest form-lookup-by-name
  (let [s (store/ingest (store/empty-store) 'foo src)]
    (is (= 'add (:name (store/form-named s 'foo 'add))))
    (is (nil? (store/form-named s 'foo 'missing)))))

(deftest anchored-add-inserts-before-the-anchor
  (let [base (store/ingest (store/empty-store) 'an.core
                           "(ns an.core)\n(defn early [] 1)\n(defn late [] 2)\n")
        [st d] (store/append-form base 'an.core
                                  (rewrite-clj.parser/parse-string "(defn mid [] 3)")
                                  :prompt "anchored" :before 'late)]
    (testing "lands between early and late (element order IS the store truth)"
      (is (= '[an.core early mid late]
             (mapv :name (store/forms st 'an.core)))))
    (testing "the delta records the anchor's form-id for replay"
      (is (= (:id (store/form-named base 'an.core 'late)) (:before d))))
    (testing "a foreign store replays the add into the SAME position"
      (is (= '[an.core early mid late]
             (mapv :name (store/forms (store/replay-delta base d) 'an.core)))))
    (testing "a missing anchor name returns nil (caller errors)"
      (is (nil? (store/append-form base 'an.core
                                   (rewrite-clj.parser/parse-string "(defn x [] 4)")
                                   :before 'nope))))))

(deftest reorder-to-realizes-a-target-order
  (let [base (store/ingest (store/empty-store) 'ro.core
                           "(ns ro.core)\n(defn c [] 3)\n(defn a [] 1)\n(defn b [] 2)\n")
        names #(mapv :name (store/forms % 'ro.core))]
    (testing "reorders to the requested sequence, ns decl first"
      (let [[st n] (store/reorder-to base 'ro.core '[ro.core a b c])]
        (is (= '[ro.core a b c] (names st)))
        (is (pos? n) "some moves happened")))
    (testing "an already-correct order needs no moves"
      (let [[st n] (store/reorder-to base 'ro.core '[ro.core c a b])]
        (is (= '[ro.core c a b] (names st)))
        (is (zero? n))))
    (testing "the moves are ordinary :move deltas (replay-tested in multiproc)"
      (let [[st n] (store/reorder-to base 'ro.core '[ro.core a b c])]
        (is (= n (count (filter #(= :move (:op %))
                                (drop (count (:deltas base)) (:deltas st)))))
            "each move is one :move delta")))))

(deftest form-symbols-reports-what-a-form-actually-defines
  ;; The store's premise was "one form ↔ one name", via form-symbol's (second s).
  ;; Probed against kondo 2026-07-17, it is wrong in BOTH directions:
  ;;
  ;;   (defmethod area :square ..)  defines NOTHING — `area` is its TARGET, and
  ;;                                forcing it into that name put three forms
  ;;                                named `area` in one ns. form-named returns
  ;;                                the FIRST, so the methods were unreachable by
  ;;                                every name-keyed tool, and
  ;;                                refs/cold-load-order silently DROPPED them.
  ;;   (defrecord R [x] ..)         defines R, ->R AND map->R — so ->R/map->R
  ;;                                were real public vars with no form: invisible
  ;;                                to form-named, :covered and the unused gate.
  ;;
  ;; No compound name can fix the first half: `:` is legal in a symbol, so
  ;; (defn area:square ..) is a real fn a user can write — and every ASCII
  ;; punctuation separator is likewise legal. The name space is flat and
  ;; user-owned; you cannot reserve a corner of it. The fix is to stop inventing
  ;; names for forms that define none.
  (let [syms #(store/form-symbols (p/parse-string %))]
    (testing "definitions — one name each"
      (is (= '#{f}    (syms "(defn f \"F.\" [x] x)")))
      (is (= '#{area} (syms "(defmulti area :shape)"))))
    (testing "REGISTRATIONS define nothing — the collision was invented, not inherent"
      (is (= #{} (syms "(defmethod area :square [s] (:side s))")))
      (is (= #{} (syms "(extend-type String P (m [_] 1))"))))
    (testing "and some definitions define SEVERAL — these vars had no form at all"
      (is (= '#{R ->R map->R} (syms "(defrecord R [x])")))
      (is (= '#{T ->T}        (syms "(deftype T [x])")))
      (is (= '#{P m n}        (syms "(defprotocol P \"P.\" (m [_] \"M.\") (n [_] \"N.\"))"))))
    (testing "metadata is seen through, as form-symbol already does"
      (is (= '#{f} (syms "^:unsafe (defn f \"F.\" [x] x)"))))))

(deftest names-address-a-form-and-registrations-collide-with-nothing
  ;; The payoff of form-symbols. Two bugs, both live before #128:
  ;;  1. ingest of a defmulti + 2 defmethods produced THREE forms named `area`.
  ;;     form-named returns the first, so the methods were unreachable — and the
  ;;     EDIT layer refuses duplicate names outright, so ingest was admitting a
  ;;     state the rest of slopp considered illegal.
  ;;  2. `dm.core/f4` printed in output (qform's (or (:name e) (:id e))) and
  ;;     query_source {name "f4"} could not fetch it back, because form-named
  ;;     filtered on :name. The id-fallback labelled forms it could not address.
  (let [dm (store/ingest (store/empty-store) 'dm.core
                         (str "(ns dm.core)\n\n(defmulti area :shape)\n\n"
                              "(defmethod area :square [s] 1)\n\n"
                              "(defmethod area :circle [c] 2)\n"))
        rc (store/ingest (store/empty-store) 'r.core
                         "(ns r.core)\n\n(defrecord R [x])\n")]
    (testing "the defmulti keeps `area` — it IS area, and callers reference it"
      (is (= '#{area} (:names (store/form-named dm 'dm.core 'area)))))
    (testing "the methods define nothing, so there is nothing to collide"
      (is (= ['#{dm.core} '#{area} #{} #{}]
             (mapv :names (store/forms dm 'dm.core)))))
    (testing "a registration is addressable by its form id — its only handle"
      (let [m (nth (store/forms dm 'dm.core) 2)]
        (is (= (:id m) (:id (store/form-named dm 'dm.core (symbol (:id m))))))))
    (testing "->R and map->R reach R's form — they are real public vars"
      (let [r (store/form-named rc 'r.core 'R)]
        (is (= (:id r) (:id (store/form-named rc 'r.core '->R))))
        (is (= (:id r) (:id (store/form-named rc 'r.core 'map->R))))))))

(deftest appended-forms-are-blank-line-separated
  ;; The expected strings are unchanged from when the elements themselves
  ;; carried the whitespace — the convention is the same, the supplier moved.
  ;; This asserted through a LOCAL concatenating renderer, which was a fifth
  ;; copy of the rule and went on agreeing with a model nothing else used.
  (let [base      (store/ingest (store/empty-store) 't.core
                                "(ns t.core)\n\n(defn a [] 1)\n")
        [s-b d-b] (store/append-form base 't.core (p/parse-string "(defn b [] 2)"))
        [s-c _]   (store/append-form s-b 't.core (p/parse-string "(defn c [] 3)")
                                     :before 'b)
        render    (fn [st] (render/render-ns st 't.core))]
    (testing "a tail-appended form gets a blank line before it (top-level convention)"
      (is (= "(ns t.core)\n\n(defn a [] 1)\n\n(defn b [] 2)\n" (render s-b))))
    (testing "an anchored insert is blank-line separated on both sides"
      (is (= "(ns t.core)\n\n(defn a [] 1)\n\n(defn c [] 3)\n\n(defn b [] 2)\n"
             (render s-c))))
    (testing "journal replay of the :add renders identically to the live append"
      (is (= (render s-b) (render (store/replay-delta base d-b)))))))

(deftest module-tier-delta-model
  (let [s0 (store/empty-store)]
    (testing "a fresh store declares no module tiers"
      (is (= {} (:module-tiers s0))))
    (let [[s1 d1] (store/record-module-tier s0 "app.core" :pure
                                            :agent "a" :prompt "keep the core pure")]
      (testing "record-module-tier appends a :module-tier delta and folds the tier"
        (is (= :module-tier (:op d1)))
        (is (= "app.core" (:module d1)))
        (is (= :pure (:tier d1)))
        (is (= '*session* (:ns d1)))
        (is (= "keep the core pure" (:prompt d1)))
        (is (= {"app.core" :pure} (:module-tiers s1))))
      (testing "replay reconstructs :module-tiers (foreign-sync stays cheap)"
        (let [replayed (store/replay-delta s0 d1)]
          (is (some? replayed))
          (is (= {"app.core" :pure} (:module-tiers replayed)))))
      (testing "re-declaring overwrites, and a retired spelling folds CANONICALLY"
        ;; the delta keeps the caller's :effects verbatim (history is honest);
        ;; fold STATE canonicalizes, so retired vocabulary cannot re-enter
        (let [[s2 d2] (store/record-module-tier s1 "app.core" :effects)]
          (is (= :effects (:tier d2)))
          (is (= {"app.core" :external} (:module-tiers s2)))
          (is (= {"app.core" :external} (:module-tiers (store/replay-delta s1 d2)))))))))

(deftest form-accessors-handle-every-shape
  ;; THE bug class of this project. Analyzers reached into a form by INDEX:
  ;; `(nth s 2)` for a def's value, which is where a DOCSTRING sits. So
  ;; ambient-def? never once fired on a documented global — every global anyone
  ;; had bothered to justify — and looked healthy for its entire life while
  ;; nine accumulated. A day later I made the same class of error in
  ;; contract-drift, binding a whitespace node instead of a symbol.
  ;;
  ;; A wrong index does not throw. It yields nil, nil is falsy, and the rule
  ;; simply does not fire. That is why this needs ONE tested accessor rather
  ;; than fifteen independent guesses, each silently wrong in its own way.
  (let [p rewrite-clj.parser/parse-string]
    (testing "def-init sees past an optional docstring"
      (is (= '(atom {}) (store/def-init (p "(def x (atom {}))"))))
      (is (= '(atom {}) (store/def-init (p "(def x \"why\" (atom {}))"))))
      (is (= '(atom {}) (store/def-init (p "(def ^:private ^:ambient-ok x \"why\" (atom {}))"))))
      (is (= 41 (store/def-init (p "(def x 41)")))))
    (testing "…and through a leading metadata wrapper, like form-symbol does"
      (is (= '(atom {}) (store/def-init (p "^:unsafe (def x \"why\" (atom {}))")))))
    (testing "form-docstring finds one only where a docstring can legally be"
      (is (= "why" (store/form-docstring (p "(def x \"why\" 1)"))))
      (is (= "why" (store/form-docstring (p "(defn f \"why\" [] 1)"))))
      (is (nil? (store/form-docstring (p "(def x 1)"))))
      (is (nil? (store/form-docstring (p "(defn f [] 1)"))))
      (testing "a lone string VALUE is not a docstring"
        (is (nil? (store/form-docstring (p "(def x \"just a value\")"))))))
    (testing "a non-def form yields nil rather than a plausible wrong answer"
      (is (nil? (store/def-init (p "(println 1)"))))
      (is (nil? (store/form-docstring (p "(println 1)")))))))

(deftest ns-delete-of-a-self-named-form-does-not-drop-the-namespace
  ;; review S-F2: a (def scratch 1) in ns `scratch` has form-name = the ns
  ;; symbol, so the by-NAME "empty?" test mistook it for the ns declaration
  ;; and dropped a namespace that still held a form (data loss). The decl
  ;; must be identified STRUCTURALLY.
  (let [st (store/ingest (store/empty-store) 'scratch "(ns scratch)\n\n(def scratch 1)\n")
        d  {:op :ns-delete :ns 'scratch :id "d99"
            :parent (:id (last (store/deltas st)))}]
    (testing "body-forms sees the def, not just the ns decl"
      (is (= 1 (count (store/body-forms st 'scratch))))
      (is (= 'scratch (:name (first (store/body-forms st 'scratch))))))
    (testing "replay treats the ns as NON-empty → full reload (nil), never a dissoc"
      (is (nil? (store/replay-delta st d))))
    (testing "a genuinely empty ns replays the dissoc"
      (let [st2 (store/ingest (store/empty-store) 'husk "(ns husk)\n")
            d2  {:op :ns-delete :ns 'husk :id "d99"
                 :parent (:id (last (store/deltas st2)))}
            r   (store/replay-delta st2 d2)]
        (is (some? r))
        (is (nil? (get-in r [:namespaces 'husk])))))))

(deftest module-platform-delta-model
  (let [s0 (store/empty-store)]
    (testing "a fresh store declares no module platforms"
      (is (= {} (:module-platforms s0))))
    (let [[s1 d1] (store/record-module-platform s0 "app.client" :cljs
                                                :agent "a" :prompt "browser code")]
      (testing "record-module-platform appends a :module-platform delta and folds it"
        (is (= :module-platform (:op d1)))
        (is (= "app.client" (:module d1)))
        (is (= :cljs (:platform d1)))
        (is (= '*session* (:ns d1)))
        (is (= "browser code" (:prompt d1)))
        (is (= {"app.client" :cljs} (:module-platforms s1))))
      (testing "replay reconstructs :module-platforms (foreign-sync stays cheap)"
        (let [replayed (store/replay-delta s0 d1)]
          (is (some? replayed))
          (is (= {"app.client" :cljs} (:module-platforms replayed)))))
      (testing "re-declaring overwrites"
        (let [[s2 d2] (store/record-module-platform s1 "app.client" :cljc)]
          (is (= :cljc (:platform d2)))
          (is (= {"app.client" :cljc} (:module-platforms s2)))
          (is (= {"app.client" :cljc} (:module-platforms (store/replay-delta s1 d2)))))))))

(deftest platform-for-most-specific-wins
  (let [s0 (store/empty-store)
        [s1 _] (store/record-module-platform s0 "app.client" :cljs)
        [s2 _] (store/record-module-platform s1 "app.client.shared" :cljc)]
    (testing "an undeclared namespace defaults to :jvm"
      (is (= :jvm (store/platform-for s0 'other.thing)))
      (is (= :jvm (store/platform-for s2 'app.server.core))))
    (testing "a module declaration governs its namespaces"
      (is (= :cljs (store/platform-for s2 'app.client.widget))))
    (testing "the MOST SPECIFIC declaration wins — a subtree overrides its module"
      (is (= :cljc (store/platform-for s2 'app.client.shared.schema))))
    (testing "jvm-loadable? is false only for :cljs"
      (is (store/jvm-loadable? s2 'app.server.core))
      (is (store/jvm-loadable? s2 'app.client.shared.schema))
      (is (not (store/jvm-loadable? s2 'app.client.widget))))))

(deftest client-deps-are-a-separate-build-only-manifest
  (let [s0 (store/empty-store)]
    (testing "a fresh store has no client deps"
      (is (= {} (:client-deps s0))))
    (let [[s1 d1] (store/record-client-dep s0 'org.clojure/clojurescript
                                           {:mvn/version "1.11.132"}
                                           :prompt "the cljs compiler")]
      (testing "record-client-dep folds into :client-deps"
        (is (= :client-dep-add (:op d1)))
        (is (= {'org.clojure/clojurescript {:mvn/version "1.11.132"}}
               (:client-deps s1))))
      (testing "it is BUILD-ONLY: it never enters the runtime :deps that ships"
        (is (= {} (:deps s1))))
      (testing "replay reconstructs it (foreign-sync stays cheap)"
        (is (= {'org.clojure/clojurescript {:mvn/version "1.11.132"}}
               (:client-deps (store/replay-delta s0 d1))))))))

(deftest prompt-by-form-is-the-last-ask-per-form
  ;; form-card's :why walked the whole delta log REVERSED, per form, to find
  ;; the most recent prompt naming that form. Once per card that is fine; once
  ;; per form on a page it is quadratic in the log (10,728 deltas here).
  ;; prompt-by-form folds the same answer in one forward pass.
  ;;
  ;; Two delta shapes carry form ids and BOTH count: :form-id (a single write)
  ;; and :form-ids (a group — one intent, several forms). The log is written
  ;; out longhand rather than driven through a write path so the orderings
  ;; that matter are pinned directly.
  (let [st  (assoc (store/empty-store)
                   :deltas
                   [{:id "d1" :op :add        :form-id  "f1" :prompt "first ask"}
                    {:id "d2" :op :replace    :form-id  "f1" :prompt "second ask"}
                    {:id "d3" :op :replace    :form-id  "f2"}
                    {:id "d4" :op :move-forms :form-ids ["f2" "f3"] :prompt "group ask"}
                    {:id "d5" :op :verify     :result   {}}
                    {:id "d6" :op :replace    :form-id  "f3"}])
        idx (store/prompt-by-form st)]
    (is (= "second ask" (get idx "f1"))
        "the LAST ask for a form wins — :why means the most recent intent")
    (is (= "group ask" (get idx "f2"))
        ":form-ids counts, not just :form-id — a group is one intent over many forms")
    (is (= "group ask" (get idx "f3"))
        "a later delta with no :prompt must not erase the recorded ask")
    (is (nil? (get idx "f-never-touched"))
        "a form nothing ever asked about is absent, not blank")))

(deftest prompt-by-form-ignores-housekeeping-writes
  ;; The pipeline OWNS form ordering — resolve-cold-load's own docstring
  ;; calls its two moves "silent to the agent". They were not silent in the
  ;; recorded intent: because prompt-by-form takes the LAST prompt naming a
  ;; form, an auto-reorder overwrote the author's ask. Measured on slopp's
  ;; own store before the fix: 142 of 1,898 forms with a recorded why
  ;; (7%) reported "auto-reorder: define before use" as theirs — on
  ;; form-card, query_slice's cards, and the reviewer UI alike.
  ;;
  ;; The discriminator is a MARK on the delta, not the op and not the
  ;; prompt text: edit_move is the same op with a real intent behind it.
  (testing "a marked housekeeping delta does not become a form's why"
    (let [st (assoc (store/empty-store)
                    :deltas
                    [{:id "d1" :op :add  :form-id "f1" :prompt "the authored ask"}
                     {:id "d2" :op :move :form-id "f1" :system true
                      :prompt "auto-reorder: define before use"}])]
      (is (= "the authored ask" (get (store/prompt-by-form st) "f1")))))
  (testing "an UNMARKED move still counts — edit_move carries a real intent"
    (let [st (assoc (store/empty-store)
                    :deltas
                    [{:id "d1" :op :add  :form-id "f1" :prompt "the authored ask"}
                     {:id "d2" :op :move :form-id "f1" :prompt "move it where it belongs"}])]
      (is (= "move it where it belongs" (get (store/prompt-by-form st) "f1")))))
  (testing "reorder-to marks what it writes"
    (let [st      (store/ingest (store/empty-store) 'demo.core
                                "(ns demo.core)\n\n(defn a [] (b))\n\n(defn b [] 1)\n")
          [st' n] (store/reorder-to st 'demo.core '[demo.core b a]
                                    :prompt "auto-reorder: define before use"
                                    :system true)]
      (is (pos? n) "the fixture must actually move something")
      (is (every? :system (filter #(= :move (:op %)) (store/deltas st'))))))
  (testing "deltas written BEFORE the mark existed are recognised by their prompt"
    ;; the log is append-only, so 142 already-written reorders cannot be
    ;; re-stamped. One constant, owned here and used by the one writer, so
    ;; this legacy clause cannot drift from the string it recognises.
    (let [st (assoc (store/empty-store)
                    :deltas
                    [{:id "d1" :op :add  :form-id "f1" :prompt "the authored ask"}
                     {:id "d2" :op :move :form-id "f1"
                      :prompt store/auto-reorder-prompt}])]
      (is (= "the authored ask" (get (store/prompt-by-form st) "f1"))))))

(deftest a-comment-belongs-to-the-form-it-describes
  ;; Whitespace between forms is RENDERING; comments are CONTENT. Today both
  ;; are `:sep` elements addressed by position, so a comment exists nowhere in
  ;; the delta log — which is the entire reason commit points carry a
  ;; byte-exact tree snapshot. Owned by a form, a comment is ordinary content:
  ;; it replays, it merges, and nothing has to preserve bytes to keep it.
  (let [st (store/ingest (store/empty-store) 'cm.core
                         "(ns cm.core)\n\n(defn f [] 1)\n")
        [st' d] (store/set-comment st 'cm.core 'f ";; why f is the way it is")]
    (testing "it renders directly above its form"
      (let [src (render/render-ns st' 'cm.core)]
        (is (re-find #";; why f is the way it is\n\(defn f \[\] 1\)" src) (pr-str src))))
    (testing "the DELTA carries it — this is what the journal was missing"
      (is (= :comment (:op d)))
      (is (= ";; why f is the way it is" (:text d)))
      (is (some? (:form-id d)) "anchored on the form's id, not a position"))
    (testing "a foreign replay reconstructs it from the log alone"
      (let [replayed (store/replay-delta st d)]
        (is (some? replayed) "the op must be replayable, not a full-reload fallback")
        (is (= (render/render-ns st' 'cm.core)
               (render/render-ns replayed 'cm.core)))))
    (testing "clearing it removes the comment and leaves the form alone"
      (let [[st'' _] (store/set-comment st' 'cm.core 'f "")]
        (is (not (re-find #"why f is" (render/render-ns st'' 'cm.core))))
        (is (re-find #"\(defn f \[\] 1\)" (render/render-ns st'' 'cm.core)))))
    (testing "code is refused — forms come in through the form verbs, which verify"
      (is (:error (store/set-comment st 'cm.core 'f ";; ok\n(def sneaky 1)"))))
    (testing "an unknown form is an error, not a silent no-op"
      (is (:error (store/set-comment st 'cm.core 'nope ";; x"))))))

(deftest rendering-synthesizes-the-space-between-forms
  ;; Whitespace between forms is a RENDERING decision, so the renderer makes
  ;; it. Stored `:sep` elements become inert here, which is what lets them be
  ;; deleted: nothing reads them, so nothing depends on what they held.
  ;;
  ;; This also normalizes. `place-form` gave a tail-appended form a SINGLE
  ;; newline, so slopp's own output had most forms jammed together —
  ;; slopp.ops.engine alone holds 33 single-newline separators against 11
  ;; blank-line ones. One rule everywhere is worth the 345 bytes it adds
  ;; across the whole store.
  (let [st  (store/ingest (store/empty-store) 'rs.core
                          "(ns rs.core)\n(defn a [] 1)\n\n\n\n(defn b [] 2)\n")
        [st' _] (store/set-comment st 'rs.core 'b ";; b is special")]
    (testing "one blank line between forms, whatever whitespace was stored"
      (is (= "(ns rs.core)\n\n(defn a [] 1)\n\n;; b is special\n(defn b [] 2)\n"
             (render/render-ns st' 'rs.core))))
    (testing "a comment sits directly above its form, inside the gap"
      (is (re-find #"\n\n;; b is special\n\(defn b" (render/render-ns st' 'rs.core))))
    (testing "the file ends with exactly one newline"
      (is (re-find #"\)\n\z" (render/render-ns st' 'rs.core))))
    (testing "an empty namespace renders empty, not a stray newline"
      (is (= "" (render/render-ns (store/empty-store) 'nope.core))))))

(deftest writes-never-create-separator-elements
  ;; Rendering supplies the space between forms, so nothing stores it. A
  ;; `:sep` element the renderer already ignores is a row that costs bytes
  ;; and answers no question — and it is HALF the element table. This pins
  ;; every write path at once, because the model is only simplified if all
  ;; of them agree: one holdout and the rows come back.
  (let [s0   (store/ingest (store/empty-store) 'w.core
                           "(ns w.core)\n\n(defn a [] 1)\n\n(defn b [] 2)\n")
        seps (fn [st] (filter #(= :sep (:kind %))
                              (get-in st [:namespaces 'w.core :elements])))]
    (testing "ingest"
      (is (empty? (seps s0))))
    (testing "append — at the tail, and anchored before a form"
      (let [[s1] (store/append-form s0 'w.core (p/parse-string "(defn c [] 3)"))
            [s2] (store/append-form s1 'w.core (p/parse-string "(defn d [] 4)")
                                    :before 'a)]
        (is (empty? (seps s2)))
        (is (= (str "(ns w.core)\n\n(defn d [] 4)\n\n(defn a [] 1)\n\n"
                    "(defn b [] 2)\n\n(defn c [] 3)\n")
               (render/render-ns s2 'w.core)))
        (testing "move and delete — neither has a trailing separator to carry"
          (let [[s3] (store/move-form s2 'w.core 'c 'a)
                [s4] (store/remove-form s3 'w.core 'b)]
            (is (empty? (seps s4)))
            (is (= "(ns w.core)\n\n(defn d [] 4)\n\n(defn c [] 3)\n\n(defn a [] 1)\n"
                   (render/render-ns s4 'w.core)))))))))

(deftest a-discarded-form-is-not-silently-dropped
  ;; `#_(...)` is CODE that the reader throws away, and rewrite-clj reports it
  ;; as non-sexpr-able — so it arrives as trivia, the same bucket as a blank
  ;; line. Once trivia stops being stored, anything not folded onto a form is
  ;; gone, and silently deleting a reader-discarded form is the one outcome
  ;; this design must not have. It is rare enough to be invisible and exactly
  ;; the kind of thing someone left as a note to themselves.
  ;;
  ;; So it folds like a comment: preserved verbatim above the form it
  ;; precedes, and it renders back out. Zero cases in slopp's own store, which
  ;; is why it needs a test rather than a measurement.
  (let [s  (store/ingest (store/empty-store) 'd.core
                         "(ns d.core)\n\n#_(defn old [] 1)\n(defn fresh [] 2)\n")
        el (first (filter #(= 'fresh (:name %))
                          (get-in s [:namespaces 'd.core :elements])))]
    (testing "the discard is owned by the form below it"
      (is (= "#_(defn old [] 1)" (:comment el))))
    (testing "and survives rendering"
      (is (= "(ns d.core)\n\n#_(defn old [] 1)\n(defn fresh [] 2)\n"
             (render/render-ns s 'd.core))))))

(deftest replay-covers-ingest-and-move
  ;; `replay-delta` returning nil means "I cannot do this — reload everything".
  ;; That was honest while the log was incomplete: `:ingest` predated
  ;; `:sources`/`:comments`, so the elements table was the only record of what
  ;; a namespace contained. It is not honest now, and the cost has changed:
  ;; a nil used to mean a slow reload, and it is about to mean a milestone
  ;; whose tree cannot be reconstructed — which is the whole basis for
  ;; deleting the stored snapshot.
  (let [base (store/empty-store)
        s1   (store/ingest base 'r.core
                           "(ns r.core)\n\n;; the helper\n(defn a [] 1)\n\n(defn b [] 2)\n")
        d1   (last (store/deltas s1))]
    (testing ":ingest replays from the log alone — order, sources and comments"
      (let [back (store/replay-delta base d1)]
        (is (some? back) "ingest must not force a reload")
        (is (= (render/render-ns s1 'r.core) (render/render-ns back 'r.core)))))
    (testing ":move replays to the same order the live write produced"
      (let [[s2 d2] (store/move-form s1 'r.core 'b 'a)
            back    (store/replay-delta s1 d2)]
        (is (some? back) "move must not force a reload")
        (is (= (render/render-ns s2 'r.core) (render/render-ns back 'r.core)))
        (is (= ['r.core 'b 'a]
               (mapv :name (get-in back [:namespaces 'r.core :elements]))))))))

(deftest replay-covers-the-changeset-ops
  ;; Four ops forced a full reload for no reason at all. Every one of them is
  ;; `apply-changeset`, which rewrites nodes BY FORM-ID wherever they live —
  ;; exactly what `:replace` already did. The relocation people assume these
  ;; carry rides separate `:add`/`:delete`/`:ingest` deltas, which always
  ;; replayed; only `:rename-ns` does something extra, and it is one rekey.
  ;;
  ;; Worth pinning rather than reasoning about: the delta names a SOURCE
  ;; namespace (`move-forms` records `from-ns`), so reading the shape and
  ;; guessing "these forms now live here" builds a plausible, wrong replay.
  (let [s0  (store/ingest (store/empty-store) 'cs.core "(ns cs.core)\n\n(defn a [] 1)\n")
        fid (:id (first (filter #(= 'a (:name %))
                                (get-in s0 [:namespaces 'cs.core :elements]))))]
    (testing "move-forms / extract-ns / module-extract are rewrites, like :replace"
      (doseq [op [:move-forms :extract-ns :module-extract]]
        (let [[s1 d] (store/apply-changeset s0 op 'cs.core
                                            {fid (p/parse-string "(defn a [] 99)")})
              back   (store/replay-delta s0 d)]
          (is (some? back) (str op " must not force a reload"))
          (is (= (render/render-ns s1 'cs.core) (render/render-ns back 'cs.core))))))
    (testing ":rename-ns rewrites AND rekeys the namespace"
      (let [[s1 d] (store/apply-changeset s0 :rename-ns 'cs.core
                                          {fid (p/parse-string "(defn a [] 2)")}
                                          :extra {:old 'cs.core :new 'cs.moved})
            s2     (update s1 :namespaces
                           (fn [m] (-> m (dissoc 'cs.core) (assoc 'cs.moved (get m 'cs.core)))))
            back   (store/replay-delta s0 d)]
        (is (some? back) ":rename-ns must not force a reload")
        (is (nil? (get-in back [:namespaces 'cs.core])) "the old name is gone")
        (is (= (render/render-ns s2 'cs.moved) (render/render-ns back 'cs.moved)))))))

(deftest folding-the-journal-reproduces-the-store
  ;; THE invariant. Once the git projection derives each milestone's tree by
  ;; folding the log, "the journal is a complete account" stops being a design
  ;; slogan and becomes the thing a push depends on. An op that does not
  ;; replay is a milestone whose bytes cannot be reconstructed.
  ;;
  ;; Run against slopp's own 13,000-delta journal while the stored `:tree`
  ;; snapshots still existed — the one chance to check a reconstruction
  ;; against an independent record of the same thing — it found the gap this
  ;; suite could not: 30 comments that lived in the elements table and in NO
  ;; delta, because `fold-comments` migrates at LOAD and a load writes
  ;; nothing. Every test was green; the journal was still incomplete.
  ;;
  ;; This is the synthetic standing version. It is weaker than that check by
  ;; construction — it only covers the ops it exercises — so a NEW op earns
  ;; its place here as well as in `replay-delta`.
  (let [s0     (store/ingest (store/empty-store) 'j.core
                             "(ns j.core)\n\n;; the seed\n(defn a [] 1)\n\n(defn b [] 2)\n")
        [s1 _] (store/append-form s0 'j.core (p/parse-string "(defn c [] 3)"))
        [s2 _] (store/append-form s1 'j.core (p/parse-string "(defn d [] 4)") :before 'b)
        fid    (:id (first (filter #(= 'a (:name %))
                                   (get-in s2 [:namespaces 'j.core :elements]))))
        [s3 _] (store/apply-changeset s2 :replace 'j.core
                                      {fid (p/parse-string "(defn a [] 100)")})
        [s4 _] (store/set-comment s3 'j.core 'c ";; added later")
        [s5 _] (store/move-form s4 'j.core 'c 'a)
        [s6 _] (store/remove-form s5 'j.core 'b)
        [s7 _] (store/set-comment s6 'j.core 'a "")
        s8     (store/ingest s7 'j.other "(ns j.other)\n\n(defn z [] 9)\n")
        folded (reduce (fn [s d] (when s (store/replay-delta s d)))
                       (store/empty-store)
                       (store/deltas s8))]
    (testing "every delta replays — a nil here is an unreconstructible milestone"
      (is (some? folded)))
    (testing "and the result renders identically, namespace for namespace"
      (is (= (set (keys (:namespaces s8))) (set (keys (:namespaces folded)))))
      (doseq [n (keys (:namespaces s8))]
        (is (= (render/render-ns s8 n) (render/render-ns folded n))
            (str n " does not survive a journal round trip"))))
    (testing "including the comment lifecycle — set, carried, and cleared"
      (is (= ";; added later"
             (:comment (first (filter #(= 'c (:name %))
                                      (get-in folded [:namespaces 'j.core :elements]))))))
      (is (nil? (:comment (first (filter #(= 'a (:name %))
                                         (get-in folded [:namespaces 'j.core :elements])))))))))
