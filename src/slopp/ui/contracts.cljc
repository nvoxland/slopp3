(ns slopp.ui.contracts
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
  "One form in an outline: its name, and its docstring's first line if it has
  one. `:maybe` because plenty of forms have no doc, and a contract that
  cannot say so would refuse legitimate data."
  [:map
   [:name :string]
   [:doc [:maybe :string]]])

(def ns-outline
  "`GET /api/ns/:ns` — one namespace's forms in store order, and what tests it.

  `:tested-by` is always present and empty rather than absent when nothing
  covers the namespace: an absent key and an untested namespace would render
  identically, and the second is a finding worth showing."
  [:map
   [:ns :string]
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
  the client renders and lets `slopp.ui.model/form-view` carry the rest of
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

(def placed-box
  "One placed box on the module canvas: which module, and where it sits.

  `:layer` is optional because foundation-band members do not have one —
  they sit BENEATH the layering rather than inside it, which is the point
  of banding them. Coordinates only; the view owns colour and stroke."
  [:map
   [:module :string]
   [:x :int] [:y :int] [:w :int] [:h :int]
   [:layer {:optional true} :int]])

(def graph-edge
  "One drawn dependency: `:from` depends on `:to`, with both endpoints already
   resolved to points on the two boxes. The client draws a path between them;
   it does not decide where an arrow attaches."
  [:map
   [:from :string] [:to :string]
   [:x1 :int] [:y1 :int] [:x2 :int] [:y2 :int]])

(def module-row
  "One module in the Code nav: its production namespaces, how many test
   namespaces fold into it, its declared purity tier, and whether it was
   found to be foundation.

   `:namespaces` holds production names only and `:tests` is a COUNT, not a
   list — a `-test` namespace is not a peer of the code it covers, and
   listing it at the same rung says otherwise. The count stays because zero
   is a finding."
  [:map
   [:module :string]
   [:namespaces [:sequential :string]]
   [:tests :int]
   [:tier :string]
   [:foundation :boolean]])

(def module-picture
  "The drawable module canvas: layered nodes, the foundation band beneath
   them, the edges worth drawing, and the extent that contains it all.

   Composed from [[placed-box]] and [[graph-edge]] rather than restating
   them, so the composition is a REAL reference edge."
  [:map
   [:nodes [:sequential placed-box]]
   [:band [:sequential placed-box]]
   [:edges [:sequential graph-edge]]
   [:width :int]
   [:height :int]])

(def module-index
  "`GET /api/modules` — the Code landing: one row per module plus the
   drawable canvas.

   `:cycles` rides alongside the picture rather than inside it because a
   cycle is a FINDING about the architecture, not a drawing instruction. On
   a tangled store it is the most useful thing on the screen, and a consumer
   that only wants the verdict should not have to read geometry to find it."
  [:map
   [:modules [:sequential module-row]]
   [:picture module-picture]
   [:cycles [:sequential [:sequential :string]]]])
