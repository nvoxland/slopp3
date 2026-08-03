(ns slopp.http-api.contracts
  "Wire contracts for the reviewer UI's JSON API — the shapes `/api/*`
  promises and the generated client validates against.

  `:cljc` on purpose, and the reason is the whole point of D-web-contracts:
  the server enforces these and the generated client enforces the SAME vars,
  so a drift is a write-time finding rather than a runtime surprise in a
  browser. A schema var that lived in a `:clj` namespace could not compile
  into the client, and `resolve-schema-ref` refuses one for exactly that.

  Plain malli DATA, so no require is needed to hold them — a schema is a
  vector, and only validating one needs malli.

  Everything crossing this boundary is JSON, which has no symbols: a
  namespace name is a `:string` here even though the store holds a symbol.
  Saying `:symbol` would describe the store rather than the wire, and the
  client would be validating against a shape that cannot arrive.")

(def namespace-row
  "One namespace on the wire: its name and how many named forms it holds."
  [:map
   [:ns :string]
   [:forms :int]])

(def namespace-list
  "`GET /api/namespaces` — every namespace, sorted.

  Composed from [[namespace-row]] rather than restating it: a schema var is
  an ordinary var, so this composition is a REAL reference edge, and changing
  the row shows up in its blast radius."
  [:sequential namespace-row])

(def form-row
  "One form in an outline: what it is called, what KIND of form it is, what it
  takes, whether it is private, its docstring's first line, any schema it
  declares, and the facts a consumer needs to RANK it against its neighbours.
  Enough to render the namespace INSTEAD of the source, which is the job this
  row exists for.

  `:maybe` on `:doc`, `:sig` and `:schema` because plenty of forms have none —
  a `def` has no signature at all — and a contract that could not say so would
  refuse legitimate data. `:private?` is a plain boolean rather than `:maybe`:
  an absent key and a public var render identically, and only one of them is a
  finding.

  `:sig` is a SEQUENTIAL, one string per arity, so a consumer can stack a
  multi-arity the way source stacks it. Joining them is something the reader
  can do and cannot undo, so the wire carries the separable form.

  The ranking half — `:mass`, `:calls`, `:callers-out`, `:effectful?`,
  `:exported?` — is FACTS and deliberately not a score. Weighting them into
  an importance number, and bucketing that number into perceptible steps, is
  drawing, and a consumer has to be able to tune it without a slopp release.
  Same split `module-index` makes by shipping layers rather than a laid-out
  picture.

  All five are required for the same reason `:private?` is. A `:calls` that
  is absent and a form that calls nothing draw identically; so do a missing
  `:callers-out` and a form nothing outside uses. Required is also what makes
  a field that stops being sent a red test here rather than a nil in someone
  else's pane — this contract is what the typed client is generated from, and
  `m/validate` passes an OPEN map, so a key the schema does not name is a key
  nothing checks."
  [:map
   [:name :string]
   [:kind :string]
   [:sig [:maybe [:sequential :string]]]
   [:private? :boolean]
   [:doc [:maybe :string]]
   [:schema [:maybe :string]]
   [:mass :int]
   [:calls [:sequential :string]]
   [:callers-out :int]
   [:callers-out-test :int]
   [:effectful? :boolean]
   [:exported? :boolean]])

(def ns-outline
  "`GET /api/ns/:ns` — one namespace's forms in store order, and what tests it.

  `:tested-by` is always present and empty rather than absent when nothing
  covers the namespace: an absent key and an untested namespace would render
  identically, and the second is a finding worth showing.

  `:tier` is the NAMESPACE's effective purity tier, most-specific declaration
  winning — a claim about what this namespace is ALLOWED to do, which is a
  different grain from a row's `:effectful?` (what that form actually does).
  Always present for the same reason `:tested-by` is: undeclared resolves to
  `\"external\"`, so there is no \"nobody said\" for an absent key to mean, and a
  consumer badging on it would otherwise have to invent a fourth state."
  [:map
   [:ns :string]
   [:tier [:enum "pure" "internal" "external"]]
   [:forms [:sequential form-row]]
   [:tested-by [:sequential :string]]])

(def token
  "One syntax token: `[\"keyword\" \":web/path\"]`.

  A PAIR, not markup. The server walks the CST it already holds and sends
  classes and text; the client turns them into elements. That is the line the
  whole SPA rewrite is organised around — the server never decides what an
  element is, and the client never needs a lexer to find out.

  The invariant the model's specs pin: concatenating every `text` reproduces
  the source exactly, so a form renders from tokens alone."
  [:tuple :string :string])

(def form-view
  "`GET /api/form/:id` — one form's permalink model.

  OPEN (malli maps are, by default) and deliberately so: this names the keys
  the client renders and lets `slopp.http-api.model/form-view` carry the rest of
  its card. A closed schema over a model this rich would be a contract that
  refuses valid data every time the model grew a field — the failure mode
  where the contract becomes the thing you route around."
  [:map
   [:form-id :string]
   [:form :string]
   [:name :string]
   [:ns :string]
   [:view :string]
   [:views [:sequential :string]]
   [:tokens [:sequential token]]
   [:callers [:sequential [:map
                           [:via :string]
                           [:count :int]
                           [:forms [:sequential :map]]]]]
   [:callees [:sequential :map]]
   [:note :string]])

(def timeline
  "`GET /api/timeline` — milestones newest first, plus the working set."
  [:map
   [:milestones [:sequential [:map
                              [:commit :string]
                              [:description :string]
                              [:range {:optional true} :string]]]]
   [:working [:map
              [:forms :int]
              [:namespaces [:sequential :string]]
              [:prompts [:sequential :string]]]]])

(def change-view
  "`GET /api/change/:range` — one milestone reviewed, grouped module then
  namespace, with a count at every rung.

  The diff arrives as LINES (`[\"-(defn f [])\" \"+(defn f [x])\"]`), not as
  rendered markup — the client decides that a `-` line is a `.del` element.
  Same discipline as [[token]]: the server sends what changed, never how it
  should look."
  [:map
   [:from :string]
   [:to :string]
   [:count :int]
   [:modules [:sequential [:map
                           [:module :string]
                           [:count :int]
                           [:namespaces [:sequential [:map
                                                      [:ns :string]
                                                      [:count :int]
                                                      [:forms [:sequential :map]]]]]]]]
   [:arc [:sequential :any]]])

(def form-source
  "`GET /api/source/:ns/:name` — one form's source text.

  Addressed by NAME because that is what a namespace outline knows, where
  [[form-view]] is addressed by the stable id. Both exist on purpose: the id
  is the permalink, the name is the path you arrive by."
  [:map
   [:ns :string]
   [:name :string]
   [:source :string]])

(def module-row
  "One module in the Code nav: its production namespaces, how many test
   namespaces fold into it, its declared purity tier, whether it was found to
   be foundation, and what it depends on.

   `:namespaces` holds production names only and `:tests` is a COUNT, not a
   list — a `-test` namespace is not a peer of the code it covers, and
   listing it at the same rung says otherwise. The count stays because zero
   is a finding.

   `:deps` is what makes a consumer able to DRAW this — an edge needs two
   ends, and a row that names only itself leaves the producer as the only
   thing that could ever assemble a diagram. Foundation-free, matching the
   layering: an edge into the substrate is not drawn, and `:foundation`
   already says which modules those are."
  [:map
   [:module :string]
   [:namespaces [:sequential :string]]
   [:tests :int]
   [:tier :string]
   [:foundation :boolean]
   [:deps [:sequential :string]]])

(def module-index
  "`GET /api/modules` — the Code landing: one row per module, the layering,
   and the cycles.

   No canvas. This carried a fully placed `module-picture` — boxes with
   coordinates, routed edges, an extent — until the reviewer UI became a
   separate project and demonstrated the cost: it ported a layout namespace
   and found nothing for it to do, because the layout had already happened
   here. An API that ships a drawing admits exactly one consumer.

   `:layers` is the compromise, and it is not one: a topological layering is
   ANALYSIS, it comes from the store's own module graph, and no consumer can
   recompute it. Placing boxes on those rungs is drawing, and every consumer
   should get to disagree about it.

   `:cycles` rides alongside rather than inside, because a cycle is a FINDING
   about the architecture, not a drawing instruction. On a tangled store it is
   the most useful thing on the screen, and a consumer that only wants the
   verdict should not have to read geometry to find it."
  [:map
   [:modules [:sequential module-row]]
   [:layers [:sequential [:sequential :string]]]
   [:cycles [:sequential [:sequential :string]]]])
