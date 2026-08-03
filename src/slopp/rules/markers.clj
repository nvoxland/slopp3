(ns slopp.rules.markers
  "The marker VOCABULARY — every dial slopp gives meaning to, declared once.

  Deliberately not the detection. Whether a `^:unused-ok` is stale is a
  question only the unused gate can answer, and whether a `^:breaking-ok` still
  describes a narrowing only the breakage classifier can. What moves here is
  the part that was copied instead: which markers EXIST, what each waives,
  what its why should answer, and whether it is supposed to police itself.

  The evidence for splitting it out that way: four markers each hand-wrote
  their own stale check in four namespaces, and three more places kept their
  own hardcoded list of marker names to reason about them in bulk — two of
  those written the same day the missing registry was diagnosed.

  Its own drift guard (`undeclared`) is the part that matters. A registry is
  complete on the day it is written and describes nothing a year later; this
  one was already incomplete on day one, and `undeclared` found the three
  markers it had missed the moment it existed."
  (:require [slopp.store :as store]))

(def ^:export ^{:legacy-ok "the `:reads` below is the live FORM-LEVEL marker (^:reads on a read-only dep call), NOT the retired `:reads` TIER spelling that normalizes to :internal. AGENTS.md names this exact near-miss — a careless vocabulary sweep breaks correct guidance — and a registry of marker names is precisely where the two tokens collide."}
  marker-registry
  "Every marker slopp gives meaning to, declared ONCE.

  Four escape markers currently hand-wire their own detection, their own stale
  check and their own \"remove the flag\" message, in four different
  namespaces — `:unused-ok` in `api.modules/unused-report`, `:ambient-ok` in
  `rules/ambient-state-check`, `:breaking-ok` in `breakage/breaking-changes`,
  `:foreign-keys` in `edit.modules/namespaced-keys-refusal`. Each was written
  from scratch, and the fifth would have been too. Three MORE places hardcode
  a list of markers to reason about them in bulk.

  This is the declaration those seven read instead of each carrying their own
  copy. It does not execute anything: detection stays with the rule that owns
  it, because only that rule knows what \"still applies\" means. What moves
  here is the vocabulary — which markers exist, what each waives, what its why
  should answer, and whether it is supposed to police itself.

  Fields:

  - `:marker` — the metadata key, as written.
  - `:kind` — `:escape` waives a rule; `:declaration` asserts a fact slopp
    cannot derive (`^:entry-point` says the outside world calls this);
    `:internal` is machinery talking to itself.
  - `:discharges` — the rule key it waives, or nil for a declaration.
  - `:on` — `:name` (metadata on the defined symbol) or `:form` (metadata on
    the whole form, which the store's sexpr accessors unwrap away).
  - `:asks` — the question its WHY should answer. Present iff the marker
    should carry a reason, which drives `marker-why`.
  - `:self-polices?` — true when a marker that no longer applies is ITSELF a
    finding. The property that stops a dial drifting into decoration, and the
    one an unregistered fifth marker silently lacked."
  [{:marker :unused-ok    :kind :escape :discharges :unused-public  :on :name
    :asks "why is no caller expected?"                       :self-polices? true}
   {:marker :ambient-ok   :kind :escape :discharges :ambient-state  :on :name
    :asks "why does this global state belong at the top level?" :self-polices? true}
   {:marker :breaking-ok  :kind :escape :discharges :breaking-changes :on :name
    :asks "who downstream did you tell, and what broke?"     :self-polices? true}
   {:marker :foreign-keys :kind :escape :discharges :namespaced-keys-refusal :on :name
    :asks "whose map is it?"                                 :self-polices? true}
   {:marker :legacy-ok    :kind :escape :discharges nil       :on :name
    :asks "what makes this worth keeping as-is?"             :self-polices? false}
   ;; the reaches/is distinction, declared: this form IS the reaching, so raw
   ;; external contact is sanctioned HERE and refused everywhere else. Its
   ;; VALUE names the port first ("http — why"), which is what lets a second
   ;; port's rule be added without teaching the first one about it — each
   ;; polices only the markers whose port it owns, and ignores the rest rather
   ;; than calling them stale.
   {:marker :adapter     :kind :escape :discharges :direct-http :on :name
    :asks "which port does this adapt, and where is its fake?" :self-polices? true}
   ;; the escape that had to exist BEFORE bare-throw could be ratcheted from
   ;; advisory to error. Its old escape was the prose "or accept the throw",
   ;; which means nothing once a finding reds the done — and there are real
   ;; bare throws: satisfying a Java API contract, an InterruptedException, a
   ;; test that throws a non-ex-info precisely to prove it gets masked.
   {:marker :bare-throw-ok :kind :escape :discharges :bare-throw :on :name
    :asks "who requires this exact exception type, and why won't ex-info do?"
    :self-polices? true}
   {:marker :side-effect  :kind :declaration :discharges nil  :on :name
    :asks "what does requiring it register?"                 :self-polices? false}
   {:marker :entry-point  :kind :declaration :discharges :unused-public :on :name
    :asks "what invokes it from outside — CLI flag, wire tool, eval template?"
    ;; no stale symmetry, and the reason is worth keeping: the outside world
    ;; is unverifiable, so "nothing calls this any more" is not a fact slopp
    ;; can establish about an entry point.
    :self-polices? false}
   {:marker :covers       :kind :declaration :discharges nil  :on :name
    ;; the worked example the others should follow: its VALUE is already the
    ;; why, and has been since it shipped
    :asks nil :self-polices? false}
   {:marker :export       :kind :declaration :discharges nil  :on :name
    ;; deliberately asks NOTHING: its string value already means the subtree it
    ;; widens to, so a why needs a different shape and that is an open design
    ;; question rather than a thing this registry should pre-empt
    :asks nil :self-polices? false}
   {:marker :unsafe       :kind :escape :discharges :dialect  :on :form
    :asks "what obligation is the author taking on?"         :self-polices? false}
   {:marker :reads        :kind :escape :discharges :effect-naming :on :form
    :asks nil :self-polices? false}
   ;; The three below were found IN USE and undeclared by `undeclared` on its
   ;; first run against slopp's own store — the first cut of this registry was
   ;; already incomplete, which is the whole argument for asking the store
   ;; rather than trusting the list.
   {:marker :external     :kind :declaration :discharges nil :on :name
    ;; a test that spawns an image would recurse in-image; this declares the
    ;; tier. The inverse IS checked (spawning-tests-must-be-external), but from
    ;; the test's behaviour rather than from the marker, so it is not the
    ;; self-policing shape.
    :asks nil :self-polices? false}
   {:marker :live-handle  :kind :declaration :discharges nil :on :name
    ;; declares a fn that returns a map the session holds ACROSS calls, so a
    ;; change to its key shape has to rebuild the image rather than hand the
    ;; old handle to new code
    :asks nil :self-polices? false}
   {:marker :teach        :kind :declaration :discharges nil :on :name
    ;; the second worked example alongside :covers — its VALUE is prose
    ;; warning a reader of a trap the code cannot state (a bare `>` is
    ;; clojure.core/>, :uri excludes the query string). It already carries its
    ;; why by construction, which is why it asks for none.
    :asks nil :self-polices? false}
   {:marker :generated    :kind :internal :discharges nil     :on :name
    :asks nil :self-polices? false}
   {:marker :auto-declare :kind :internal :discharges nil     :on :form
    :asks nil :self-polices? false}])

(defn ^:export asking
  "Registry entries whose marker should carry a WHY *and* whose why can be
  seen — what `marker-why` runs over, instead of the inline map it started
  life with.

  Filtered to `:on :name` deliberately, and the gap it leaves is worth
  stating: `^:unsafe` is the most consequential dial in the system and its
  `:asks` is recorded here, but the marker sits on the FORM rather than the
  defined symbol, and the store's sexpr accessors unwrap that away. So the
  question is real and currently unaskable. `asking-unenforced` names exactly
  that set rather than letting it hide as an omission."
  []
  (vec (filter #(and (:asks %) (= :name (:on %))) marker-registry)))

(defn ^:export known?
  "True when `k` is a marker slopp gives meaning to.

  Three places answered this from their own hardcoded list before the
  registry existed, which is three chances to disagree about what slopp owns."
  [k]
  (contains? (into #{} (map :marker) marker-registry) k))

(defn ^:export asking-unenforced
  "Markers the registry says should carry a WHY that no check can currently
  ask for — today, the `:on :form` ones.

  A gap that is written down is a backlog item; a gap that is merely absent
  from a list is indistinguishable from a decision. `^:unsafe` is the whole
  set, and it is the dial where the reason matters most."
  []
  (vec (filter #(and (:asks %) (not= :name (:on %))) marker-registry)))

(defn ^:export in-use
  "The set of markers actually present on defined names anywhere in `st`.

  Reads NAME metadata only, which is the same limit `asking` names: a
  form-level `^:unsafe` is invisible here because the store's sexpr accessors
  unwrap the metadata wrapper before this can see it."
  [st]
  (into #{}
        (for [nsx (keys (:namespaces st))
              e   (store/forms st nsx)
              :let [s (store/form-sexpr (:node e))]
              :when (and s (symbol? (second s)))
              k   (keys (meta (second s)))]
          k)))

(defn ^:export undeclared
  "Markers `st` USES that the registry does not declare — the drift finding.

  This is the direction that matters. A registry can be complete on the day it
  is written and describe nothing a year later, and the only way to tell is to
  ask the store rather than the list.

  Scoped to slopp's own vocabulary by EXCLUSION rather than inclusion: Clojure's
  own metadata (`:private`, `:dynamic`, `:tag`, `:doc`…) is not slopp's to
  register, and neither is a user's. What remains is a marker that LOOKS like
  one of slopp's dials and is not — which is exactly the fifth-marker case this
  registry exists for."
  [st]
  (let [clojures #{:private :dynamic :macro :tag :doc :arglists :added
                   :deprecated :const :file :line :column :name :ns :inline
                   :inline-arities :test :redef :author :since :no-doc}
        ;; a namespaced key belongs to whoever owns the namespace — slopp's own
        ;; :web/* and :rule/* are classified in slopp.index.crossings, which asks a
        ;; different question about them (do they cross a boundary?)
        ours?    (fn [k] (and (simple-keyword? k)
                              (not (contains? clojures k))))]
    (into #{} (filter #(and (ours? %) (not (known? %)))) (in-use st))))
