(ns slopp.api.crossings
  "The edges that LEAVE the store — Core 6's missing representation.

  `slopp.edit.refs` makes every edge INSIDE the store answerable: who calls
  what, what a rename touches, which tests cover a form. There has never been
  an equivalent for an edge that leaves — form data handed to garden, form
  metadata read as a route table, a contract turned into JSON for a browser,
  `.cljc` source handed to the ClojureScript compiler, a spec turned into
  generated code. So every exit was unverified by construction, and each grew
  an ad-hoc hand-written check or none, with no way to tell which. Fifteen of
  the sixteen frictions in one wave landed at a crossing.

  **This verifies nothing, on purpose.** Nothing here could: the far side is
  another system, and the checker that would know lives there and reports in
  that system's vocabulary. What it does is make the exits ENUMERABLE and make
  an exit with no checker SAY SO — because an exit nothing checks and an exit
  that does not exist are indistinguishable until one of them is written down.

  Two lists, and both halves are load-bearing: `kinds` (what crosses, to
  where, checked by what, blind to what) and `internal-markers` (what slopp
  owns that deliberately stays inside). Together they make classification
  total, which is what lets an unclassified marker be a finding rather than
  noise — an inventory that cannot notice a new exit describes the system it
  was written against, not the one you have."
  (:require [slopp.store :as store]))

(def kinds
  "Every way store data LEAVES slopp's verification, as data.

  Each row says what crosses, what is waiting on the far side, which markers
  signal it, and — the load-bearing field — `:checked-by`, the surface that
  proves the far side agrees, or `nil` where nothing does. `:blind` states what
  the checker does NOT cover, because a checker named without its limits is
  the absence-of-check-reads-as-absence-of-finding conflation wearing a
  different hat.

  Registering a kind does not verify anything. It makes the exit ENUMERABLE,
  which is the thing that was missing: an exit with no checker and an exit
  that does not exist look identical until one of them is written down."
  [{:kind       :http/route
    :leaves     "a form's name metadata"
    :to         "the served route table, and from there HTTP"
    :markers    #{:web/path :web/method}
    :checked-by "web-dangling-route-refs ties every literal :href/:src to a
                 route; query_routes reads the same metadata the gates enforce"
    :blind      "the SERVED table is built from interned vars in the running
                 process, not from the store, so a route can outlive its
                 definition until the host reloads"}

   {:kind       :wire/json
    :leaves     "a declared request/response contract"
    :to         "JSON, and a browser that never sees Clojure data"
    :markers    #{:web/request :web/response}
    :checked-by "the dispatcher validates against the same schema var the
                 client ships"
    :blind      "an in-image test asserts on the PRE-WIRE value — a keyword
                 sails through a [:x :string] contract and arrives as a
                 string, so a test that does not serialize checks a shape no
                 client receives"}

   {:kind       :generated/client
    :leaves     "an endpoint's contract"
    :to         "generated ClojureScript nobody hand-edits"
    :markers    #{:generated}
    :checked-by "the stale-client advisory when a contract drifts from the
                 last generation; the generated-ns gate refuses hand edits"
    :blind      "generation is EXPLICIT, so between a contract change and the
                 next generate_client the two disagree by design"}

   {:kind       :schema/malli
    :leaves     "a value and the schema that describes it"
    :to         "malli, on both the JVM and in the browser bundle"
    :markers    #{:malli/schema}
    :checked-by "validation at the boundary, from the one .cljc var both
                 sides load"
    :blind      "a schema that is not .cljc cannot ship, and the endpoint is
                 SKIPPED rather than failing loudly"}

   {:kind       :web/vocabulary
    :leaves     "a declared read/effect KIND, resolved by name"
    :to         "a performer found by scanning namespaces the caller lists
                 by hand"
    :markers    #{:web/reads :web/effects :web/read :web/effect}
    :checked-by "web/context refuses at assembly when a declared kind has no
                 performer among its namespaces"
    :blind      nil}

   {:kind       :spa/client-routing
    :leaves     "a prefix declared as client-routed"
    :to         "a route table that lives in the browser"
    :markers    #{:web/spa}
    :checked-by nil
    :blind      "declaring it makes EVERY path under the prefix answer 200 and
                 moves not-found into the client. Nothing compares the
                 client's route table to the server's, and nothing says the
                 status codes changed — two existing tests caught it only by
                 asserting the old 404"}

   {:kind       :http/foreign-route
    :leaves     "a link to a path this store does not serve"
    :to         "somebody else's server"
    :markers    #{:web/external-path}
    :checked-by nil
    :blind      "the DECLARATION is the whole check: it stops
                 web-dangling-route-refs asking, and nothing confirms the
                 foreign server serves that path or still does. This is the
                 crossing that is honest about being one"}])

(def internal-markers
  "Markers slopp owns that are deliberately NOT crossings, and why each stays
  inside.

  Without this the classification is partial, and a partial classification is
  worse than none here: every internal marker reads as an exit nobody checks,
  and the one real hole drowns in five false ones. That precision failure is
  what got the `:positional-form-access` advisory withdrawn, so it is a named
  hazard rather than a hypothetical."
  {:web/auth      "a policy the dispatcher enforces in-process, on data that
                   never leaves"
   :web/effectful "declares the handler performs its own effects — a statement
                   about where effects run, not about anything crossing"
   :web/client    "a MODIFIER on the generated-client crossing (opt this
                   endpoint out), not an exit of its own"
   :rule/applies-to "the rule registry describing itself to itself"
   :rule/severity   "the rule registry describing itself to itself"})

(defn ^:export unclassified-markers
  "Markers slopp's own surfaces produce that neither `kinds` nor
  `internal-markers` claims — empty when the classification is total.

  The guard on the guard. `store-crossings` can only report a marker as
  unclassified if it appears in a STORE; this asks the same question of the
  vocabulary itself, so a marker slopp defines and no store has used yet still
  has to be decided about."
  []
  (let [owned (into (set (keys internal-markers)) (mapcat :markers) kinds)]
    (vec (sort (remove owned
                       [:web/path :web/method :web/auth :web/reads :web/effects
                        :web/read :web/effect :web/effectful :web/request
                        :web/response :web/client :web/spa :web/external-path
                        :malli/schema :rule/applies-to :rule/severity])))))

(defn ^:export store-crossings
  "The store's boundary exits: which crossing kinds it actually has, which of
  those nothing checks, and any marker no kind claims.

  Returns `{:crossings [...] :unchecked [...] :unclassified [...]}` — always
  all three keys, empty vectors when there is nothing to say, because an
  absent key would read as unexamined.

  - **`:crossings`** — the registered kinds this store reaches, each with the
    forms that reach it. A kind nothing here uses is simply absent; the
    registry is the vocabulary, not the finding.
  - **`:unchecked`** — of those, the ones with no `:checked-by`. This is the
    output that matters: `slopp.edit.refs` makes every edge INSIDE the store
    answerable, and there was no equivalent question for an edge leaving it,
    so each exit grew an ad-hoc check or none and nobody could tell which.
  - **`:unclassified`** — a `web/`, `malli/` or `rule/`-namespaced marker in
    use that no kind claims. This is what stops the registry rotting: an
    inventory that cannot notice a new exit describes the system it was
    written against, not the one you have.

  Scoped to slopp's OWN marker vocabulary on purpose. A user's namespaced
  metadata is theirs and means nothing to slopp, so treating it as an
  unclassified exit would bury the real finding in a store slopp knows
  nothing about."
  [st]
  (let [ours?  (fn [k] (and (qualified-keyword? k)
                            (contains? #{"web" "malli" "rule"} (namespace k))))
        owned  (into (set (keys internal-markers)) (mapcat :markers) kinds)
        marked (for [nsx  (keys (:namespaces st))
                     e    (store/forms st nsx)
                     :let [s (store/form-sexpr (:node e))]
                     :when (and s (symbol? (second s)))
                     k    (keys (meta (second s)))]
                 {:marker k :at (symbol (str nsx) (str (second s)))})
        hits   (group-by :marker marked)
        rows   (vec (for [{:keys [markers] :as k} kinds
                          :let [at (vec (sort (distinct (mapcat #(map :at (hits %))
                                                                markers))))]
                          :when (seq at)]
                      (assoc (dissoc k :markers) :at at)))]
    {:crossings    rows
     :unchecked    (vec (remove :checked-by rows))
     :unclassified (vec (sort-by (juxt :marker :at)
                                 (distinct (filter #(and (ours? (:marker %))
                                                         (not (owned (:marker %))))
                                                   marked))))}))

(defn ^:export finding
  "The `full_check` section for this store's boundary exits, or NIL when it
  has none worth saying.

  ADVISORY, and deliberately so. Every entry here is a hole someone already
  identified and wrote down — flipping the verdict on a standing documented
  gap would make `full_check` red forever, and a check that is always red is
  a check people stop running. What it buys instead is placement: the holes
  are named at the exact moment a whole-store green is about to be believed,
  which is the slot `:host-stale` occupies and works for the same reason.

  Nil rather than an empty section when there is nothing to report. The usual
  rule here runs the other way — an absent key reads as unmeasured — but this
  section is ABOUT holes, so 'no holes' and 'nothing to say' are the same
  statement, and printing it on every check of every store would be noise
  forever."
  [st]
  (let [{:keys [unchecked unclassified]} (store-crossings st)]
    (when (or (seq unchecked) (seq unclassified))
      (cond-> {:note (str "verification stops at the store's edge: "
                          (count unchecked) " exit kind(s) here have no checker"
                          (when (seq unclassified)
                            (str ", and " (count unclassified)
                                 " marker(s) belong to no exit kind at all"))
                          ". Nothing is wrong with the code — this names where"
                          " a mistake would not be caught, because an exit with"
                          " no check and an exit that does not exist look"
                          " identical otherwise")}
        (seq unchecked)    (assoc :unchecked (mapv #(select-keys % [:kind :to :blind :at])
                                                   unchecked))
        (seq unclassified) (assoc :unclassified unclassified)))))
