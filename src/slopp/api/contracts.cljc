(ns slopp.api.contracts
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
   [:ns {:doc "the namespace's full name"} :string]
   [:forms {:doc (str "how many NAMED forms it holds — the ns form and anonymous"
                      " top-level forms are not counted")} :int]])

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
   [:name {:doc "the form's own name, unqualified"} :string]
   [:form-id {:doc (str "its stable address in the store — survives renames and"
                        " moves, which the name does not")} :string]
   [:kind {:doc "the defining head: defn, def, deftest, defmulti, ns …"} :string]
   [:sig {:doc (str "arglists, ONE STRING PER ARITY and unjoined, so a consumer can"
                    " stack a multi-arity the way source does. Joining is something"
                    " a reader can do and cannot undo. nil when the form has none —"
                    " a def has no signature at all")}
    [:maybe [:sequential :string]]]
   [:private? {:doc "true when the var is private"} :boolean]
   [:doc {:doc "the docstring's first line, nil when there is none"} [:maybe :string]]
   [:schema {:doc "the schema this form declares, nil when it declares none"} [:maybe :string]]
   [:mass {:doc (str "the form's size as a NODE COUNT over its sexpr — not lines and"
                     " not characters. Over the sexpr a docstring is one node, so"
                     " the body's structure dominates; by lines a 40-line docstring"
                     " over a 30-line body makes the documentation win")} :int]
   [:calls {:doc (str "the SAME-NAMESPACE forms this one calls, direct edges only,"
                      " sorted. From the reference graph, so a form reached through"
                      " a carrier position counts — resolved calls alone draw"
                      " dispatch targets as leaves, and those matter most. Empty"
                      " rather than absent: a leaf is an answer")}
    [:sequential :string]]
   [:callers-out {:doc (str "how many forms OUTSIDE this namespace call it, counting"
                            " PRODUCTION namespaces only — fan-in, the blast radius")}
    :int]
   [:callers-out-test {:doc (str "the same count over TEST namespaces, kept separate"
                                 " because one integer cannot be taken apart again."
                                 " Measured, not reasoned: as a single number it was"
                                 " ranking by test count — ten of twelve outside"
                                 " callers were deftests, and the entry point that IS"
                                 " the render sat fourth on its one real caller")}
    :int]
   [:effectful? {:doc (str "true when the form performs effects — what this form"
                           " DOES, as against the namespace's :tier, which is what"
                           " it is allowed to do")} :boolean]
   [:exported? {:doc "true when the form is part of its module's public surface"} :boolean]])

(def token
  "One syntax token: `[\"keyword\" \":web/path\"]`.

  A PAIR, not markup. The server walks the CST it already holds and sends
  classes and text; the client turns them into elements. That is the line the
  whole SPA rewrite is organised around — the server never decides what an
  element is, and the client never needs a lexer to find out.

  The invariant the model's specs pin: concatenating every `text` reproduces
  the source exactly, so a form renders from tokens alone."
  [:tuple :string :string])

(def form-request
  "`GET /api/form/:id` — what a caller SENDS.

  `:id` is interpolated into the path; `:view` and `:depth` travel as query
  parameters, and nothing here says so — the generated client reads the
  METHOD. `:web/request` means what the caller sends, and a GET sends a query
  string for the same reason a POST sends a body.

  It exists because without it the generated wrapper takes a params map and
  only the path ever reads from it, so `?depth=` answered correctly on the wire
  and was unreachable through the client — which pushes a consumer toward
  hand-rolling a fetch, the exact thing `direct-http` refuses and the typed
  client exists to prevent.

  Both modifiers are `:optional`, and that is the compatibility promise: a
  wrapper called with only `:id` sends no query string at all, which is the
  request every link written before these existed already made."
  [:map
   [:id {:doc (str "the form's stable id — interpolated into the PATH, not sent as a"
                   " query parameter, and the only required part of the address")} :string]
   [:view {:optional true
           :doc (str "the rendering fidelity to build the response at; travels as a"
                     " query parameter. Omit for the default — a wrapper called with"
                     " only :id sends no query string at all, which is the request"
                     " every link written before these existed already made")} :string]
   [:depth {:optional true
            :doc (str "how far to follow the call graph for :callers and :callees;"
                      " query parameter. Omit for the default")} :int]])

(def neighbour-card
  "One form on the OTHER end of an edge — a caller or a callee, as a card.

  Declared once for both directions because it is one shape, and declared at
  all because `[:sequential :map]` is not a type: a bare `:map` validates any
  map, so the generated client checked every response against it and could
  never find anything. Reported by slopp-ui after a shape change went silent
  for weeks one endpoint over.

  The card INLINES what a reader needs in order to decide whether to follow
  the edge — signature, docstring, recorded why, coverage — because the
  failure this page exists to avoid is the lonely bubble: arriving cold at a
  form and having to make one request per neighbour just to learn which of
  them matters.

  **The optional four are OPTIONAL rather than `:maybe`, measured over 34 real
  cards.** A form with no docstring OMITS `:doc`; it does not send nil. Getting
  that backwards writes a contract that refuses valid data, which is the
  failure mode where the contract becomes the thing you route around."
  [:map
   [:form {:doc "the neighbour's qualified name"} :string]
   [:form-id {:doc "its stable address, for a permalink"} :string]
   [:ns {:doc "the namespace it lives in"} :string]
   [:module {:doc "that namespace's module"} :string]
   [:calls {:doc (str "how many edges run between it and the subject — a COUNT here,"
                      " unlike a form row's :calls, which lists same-namespace callee"
                      " NAMES. Same key, two documents, two meanings")} :int]
   [:warranty {:doc "what is known to have exercised it"}
    [:map [:covered {:doc "how many tests were OBSERVED running it"} :int]]]
   [:sig {:optional true :doc "its arglist as one string; absent when it has none"} :string]
   [:doc {:optional true :doc "its docstring's first line; absent when it has none"} :string]
   [:why {:optional true :doc "the recorded ask behind its last write; absent when none"} :string]
   [:via {:optional true
          :doc (str "how the edge was found — present on a CALLEE, absent on a caller"
                    " card because callers are grouped by it one level up")} :string]])

(def form-view
  "`GET /api/form/:id` — one form's permalink model.

  OPEN (malli maps are, by default) and deliberately so: this names the keys
  the client renders and lets `slopp.api.model/form-view` carry the rest of
  its card. A closed schema over a model this rich would be a contract that
  refuses valid data every time the model grew a field — the failure mode
  where the contract becomes the thing you route around."
  [:map
   [:form-id {:doc "the form's stable address — the permalink this view answers for"} :string]
   [:form {:doc "the form's qualified name, ns/name"} :string]
   [:name {:doc "the form's own name, unqualified"} :string]
   [:ns {:doc "the namespace it lives in"} :string]
   [:view {:doc (str "the rendering FIDELITY this response was built at, echoing"
                     " ?view= — so a consumer can tell which one it got rather than"
                     " assuming its request was honoured")} :string]
   [:views {:doc "every fidelity this form can be requested at"} [:sequential :string]]
   [:tokens {:doc (str "the form's source as [CLASS TEXT] PAIRS — first element the"
                       " syntax class (\"keyword\", \"string\", \"comment\"…), second the"
                       " literal text. Not markup: the server sends classes and text"
                       " and the client decides what element they become, so no"
                       " consumer needs a lexer. Concatenating every TEXT reproduces"
                       " the source exactly, which is what lets a form render from"
                       " these alone")}
    [:sequential token]]
   [:callers {:doc (str "who reaches this form, GROUPED BY HOW — a static call and a"
                        " declared reference are both callers and are not the same"
                        " evidence")}
    [:sequential [:map
                  [:via {:doc (str "HOW the edge was found: \"static\" is a call written"
                                   " in the code, \"carrier\" is a reference passed as a"
                                   " value (#'var, a late-ref), \"declared\" is a marker"
                                   " naming it. Grouped rather than summed because"
                                   " they are not the same evidence")} :string]
                  [:count {:doc "how many callers reach it that way"} :int]
                  [:forms {:doc "the caller cards reached this way"}
                   [:sequential neighbour-card]]]]]
   [:callees {:doc (str "the forms this one reaches, as cards — the other direction"
                        " of the graph. Each carries its own :via inline, where a"
                        " caller's sits on the group")}
    [:sequential neighbour-card]]
   [:note {:doc (str "the standing caveat on :callers and :callees, in words: the"
                     " edges come from a SYNTACTIC reader over the store, so they are"
                     " a FLOOR and not a census — a call reached through a binding or"
                     " built at runtime is not among them. Render it wherever the"
                     " edges are shown; a reader who takes a caller list for complete"
                     " draws the wrong conclusion from a short one")} :string]])

(def timeline
  "`GET /api/timeline` — milestones newest first, plus the working set."
  [:map
   [:milestones {:doc "milestones, NEWEST FIRST — render in the order given"}
    [:sequential [:map
                  [:commit {:doc (str "the milestone's delta id, e.g. d24976 — this"
                                      " store's own address for it, not a git sha")} :string]
                  [:description {:doc "what the milestone was recorded as achieving"} :string]
                  [:at {:doc "when it was recorded, formatted — \"2026-08-06 00:38\""} :string]
                  [:status {:doc (str "the verdict the milestone was recorded under,"
                                      " mirroring its done-point — \"green\" on every"
                                      " milestone in this store, and not declared as an"
                                      " enum because a red one is expressible and this"
                                      " store has never produced one to check against")} :string]
                  [:range {:optional true
                           :doc (str "from..to, the delta span this milestone covers,"
                                     " which is what /api/change/:range takes. Absent"
                                     " on the first milestone, which has no predecessor")} :string]
                  [:more-lines {:optional true
                                :doc (str "how many lines of :description were cut. Present"
                                          " only when it was capped, so its absence means"
                                          " you have the whole thing")} :int]
                  [:agent {:optional true
                           :doc "who recorded it; absent on milestones written before agents were tracked"} :string]
                  [:sha {:optional true
                         :doc (str "the git sha this milestone was projected to. Present"
                                   " only for milestones whose DELTA carries one — this"
                                   " model is a pure fold and never opens the projection"
                                   " to go looking")} :string]]]]
   [:working {:doc (str "the work since the newest milestone — what is written and"
                        " NOT yet milestoned. This is the field with no counterpart"
                        " in a git-shaped timeline: it is uncommitted work that is"
                        " nonetheless recorded, verified and addressable")}
    [:map
     [:since {:doc (str "the delta this set is measured AFTER — the newest milestone's"
                        " id, or \"log-start\" when there is no milestone yet. Every"
                        " other number here is relative to it, so a consumer showing"
                        " the counts without it is showing a figure with no baseline")} :string]
     [:forms {:doc "how many forms have been touched since :since"} :int]
     [:namespaces {:doc "the namespaces those forms are in"} [:sequential :string]]
     [:prompts {:doc (str "the recorded WHYs of those writes — the asks that produced"
                          " them, in order. Intent, not a commit message: nobody"
                          " wrote these to be read later. CAPPED; :forms is exact")}
      [:sequential :string]]
     [:more-prompts {:optional true
                     :doc (str "how many asks were left out of :prompts. Present only"
                               " when the list was capped, so its absence means you"
                               " have all of them")} :int]]]])

(def change-view
  "`GET /api/change/:range` — one milestone reviewed, grouped module then
  namespace, with a count at every rung.

  The diff arrives as LINES (`[\"-(defn f [])\" \"+(defn f [x])\"]`), not as
  rendered markup — the client decides that a `-` line is a `.del` element.
  Same discipline as [[token]]: the server sends what changed, never how it
  should look."
  [:map
   [:from {:doc "the delta id this range starts AFTER — exclusive"} :string]
   [:to {:doc "the delta id this range ends at — inclusive, and usually a milestone"} :string]
   [:count {:doc "how many forms changed across the whole range"} :int]
   [:modules {:doc (str "the changes grouped module then namespace, with a count at"
                        " every rung so a consumer can render a collapsed tree"
                        " without summing anything itself")}
    [:sequential [:map
                  [:module {:doc "the module name"} :string]
                  [:count {:doc "forms changed in this module"} :int]
                  [:namespaces {:doc "the namespaces within this module that changed"}
                   [:sequential [:map
                                 [:ns {:doc "the namespace's full name"} :string]
                                 [:count {:doc "forms changed in this namespace"} :int]
                                 [:forms {:doc "one entry per changed form"}
                                  [:sequential
                                   [:map
                                    [:form {:doc "the form's qualified name"} :string]
                                    [:form-id {:doc "its stable address in the store"} :string]
                                    [:status {:doc "what happened to it in this range"}
                                     [:enum "added" "modified" "deleted"]]
                                    [:why {:optional true
                                           :doc (str "the recorded ask behind the change."
                                                     " Absent when the write carried none")} :string]
                                    [:callers {:doc (str "how many distinct forms call it NOW —"
                                                         " a floor, since the graph is a"
                                                         " syntactic reader")} :int]
                                    [:diff {:doc (str "the change as [CLASS TEXT] pairs — class is"
                                                      " \"same\", \"add\" or \"del\", text is the line."
                                                      " Never rendered markup: the server says what"
                                                      " changed, and a \"del\" line becoming a .del"
                                                      " element is yours")}
                                     [:sequential [:tuple :string :string]]]]]]]]]]]]
   [:arc {:doc (str "the range's RED/GREEN arc, oldest first: one entry per"
                    " verification recorded in the range. Zero failures throughout"
                    " means the work never went red — which for a range that ADDED"
                    " assertions is itself a finding, since a test nobody watched"
                    " fail is a test nobody has evidence for")}
    [:sequential [:map
                  [:delta {:doc "the delta that verification ran at"} :string]
                  [:fail {:doc "failures and errors, summed — zero is green"} :int]]]]])

(def form-source
  "`GET /api/source/:ns/:name` — one form's source text.

  Addressed by NAME because that is what a namespace outline knows, where
  [[form-view]] is addressed by the stable id. Both exist on purpose: the id
  is the permalink, the name is the path you arrive by."
  [:map
   [:ns {:doc "the namespace the form is in"} :string]
   [:name {:doc "the form's own name, unqualified"} :string]
   [:form-id {:doc (str "the form's stable store id — the address that survives a"
                        " rename, where this endpoint's own :ns/:name does not."
                        " It is HERE and deliberately not in the published contract"
                        " document: a form id exists only for a producer whose code"
                        " lives in a store, and this endpoint is already store-only,"
                        " so it costs the portable document nothing")} :string]
   [:source {:doc (str "the form's source text, RENDERED from the store rather than"
                       " read from a file — there is no file. Canonical formatting,"
                       " so it is the same text every caller gets")} :string]])

(def gaps
  "Where a subject is about to be THIN — counts, never rows.

  A form with no recorded why and no covering test renders identically to one
  with both, so a diagram cannot point at its own weak spots: silence reads the
  same as coverage. These are the four numbers that let a consumer tint the
  EXISTING layout, which is what makes an overlay toggleable — rows would
  reflow it and a reflow is a different picture, not the same picture with a
  finding on it.

  One schema for all three carriers (a module row, a module's namespace row, a
  namespace outline) so the same four numbers cannot be described three ways.

  `:no-doc` is every named form without a docstring, which is NOT the
  `missing-doc-warning` advisory — that one asks whether to NAG (public module
  surface only) and this asks whether a reader can learn what a form is without
  opening it. `:no-why` is the absent WRITE PROMPT, the ask that produced the
  form. `:uncovered` is measured against the SESSION's trace map, so a process
  that has run nothing reports everything uncovered rather than a zero that
  would read as coverage."
  [:map
   [:forms {:doc "how many named forms the subject holds — the denominator the other three are counted against"} :int]
   [:no-doc {:doc (str "of those, how many carry no docstring. NOT the missing-doc"
                       " advisory, which asks whether to nag about public module"
                       " surface — this asks whether a reader can learn what a"
                       " form is without opening it")} :int]
   [:no-why {:doc (str "how many have no recorded WHY — the write prompt, the ask"
                       " that produced the form. Absent means nobody said why it"
                       " exists, which no amount of reading the code recovers")} :int]
   [:uncovered {:doc (str "how many no test has been OBSERVED exercising, measured"
                          " against this session's trace — so a process that has"
                          " run nothing reports everything uncovered rather than a"
                          " zero that would read as coverage")} :int]])

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
   [:ns {:doc "the namespace's full name"} :string]
   [:tier {:doc (str "this NAMESPACE's effective purity tier, most-specific"
                     " declaration winning — what it is ALLOWED to do, which is a"
                     " different grain from a form row's :effectful? (what one form"
                     " actually does). Undeclared resolves to \"external\", so there"
                     " is no fourth state for a consumer to invent")}
    [:enum "pure" "internal" "external"]]
   [:forms {:doc (str "its forms in STORE order — the order they load in, which is"
                      " the order they were written and not alphabetical. A"
                      " consumer that sorts them loses the only ordering the store"
                      " has an opinion about")}
    [:sequential form-row]]
   [:tested-by {:doc (str "the test namespaces covering this one. Always present and"
                          " EMPTY rather than absent when nothing does: an absent key"
                          " and an untested namespace would render identically, and"
                          " the second is a finding worth showing")}
    [:sequential :string]]
   [:gaps {:doc "where this namespace is thin"} gaps]])

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
   [:module {:doc "the module name — a namespace's first two segments, e.g. slopp.index"} :string]
   [:namespaces {:doc (str "its PRODUCTION namespaces, sorted. Test namespaces are"
                           " not peers of the code they cover, so they are counted"
                           " in :tests rather than listed here")}
    [:sequential :string]]
   [:tests {:doc (str "how many -test namespaces fold into this module. A count"
                      " rather than an omission because zero is a finding")} :int]
   [:tier {:doc (str "the declared purity tier — what code in this module is"
                     " ALLOWED to do: pure, internal, or external")} :string]
   [:foundation {:doc (str "true when this module is substrate anything may depend"
                           " on. Edges INTO it are left out of :deps, so a drawn"
                           " graph is not a hairball of arrows into the basement")}
    :boolean]
   [:deps {:doc (str "the modules this one depends on, foundation excluded — the"
                     " other end of every edge, which is what lets a consumer draw"
                     " the graph instead of asking the producer for a picture")}
    [:sequential :string]]
   [:gaps {:doc "where this module is thin"} gaps]])

(def module-detail
  "`GET /api/module/:m` — one module from the inside: its namespaces, how they
  depend on each other, and the edges that cross its boundary.

  The level below `module-index`, and it exists because that one stops exactly
  where a reader's question starts: `/api/modules` ships module→module `:deps`,
  so descending into a box on the diagram had no data behind it at all.

  Same split as the level above — ANALYSIS crosses, DRAWING does not.
  `:layers` is here for the identical reason it is there: a topological
  layering comes from the store's own graph and no consumer can recompute it,
  while placing boxes on those rungs is drawing.

  `:boundary` is the field this level cannot be read without. A descended
  diagram that shows only internal edges is context-free — you cannot tell a
  namespace that is the module's front door from one nothing outside touches,
  and those are different things to a reader. `:out` and `:in` name the
  OUTSIDE namespace and its module, so a consumer can draw them as labelled
  stubs on the frame without a second request.

  An internal edge appears in a namespace's `:deps` and NOT in `:boundary` —
  one arrow, one place. `-test` namespaces are excluded exactly as
  `module-index` excludes them: a test folds into the module it covers, so
  listing it puts two things at the same rung that are not peers."
  [:map
   [:module {:doc "the module name — a namespace's first two segments"} :string]
   [:tier {:doc "the module's declared purity tier — what its code is allowed to do"}
    [:enum "pure" "internal" "external"]]
   [:namespaces {:doc (str "its PRODUCTION namespaces. -test namespaces fold into"
                           " the module they cover, so listing them here would put"
                           " two things at one rung that are not peers")}
    [:sequential [:map
                  [:ns {:doc "the namespace's full name"} :string]
                  [:forms {:doc "how many named forms it holds"} :int]
                  [:tier {:doc (str "its own effective tier, most-specific"
                                    " declaration winning — which may be stricter"
                                    " than the module's")}
                   [:enum "pure" "internal" "external"]]
                  [:deps {:doc (str "the namespaces INSIDE this module it depends"
                                    " on. An edge that leaves the module is in"
                                    " :boundary instead — one arrow, one place")}
                   [:sequential :string]]
                  [:gaps {:doc "where this namespace is thin"} gaps]]]]
   [:boundary {:doc (str "the edges that CROSS the module's frame, which is what"
                         " makes a descended diagram readable: without it you cannot"
                         " tell the module's front door from a namespace nothing"
                         " outside touches, and those are different things")}
    [:map
     [:out {:doc "edges leaving this module"}
      [:sequential [:map
                    [:from {:doc "the namespace INSIDE this module that depends"} :string]
                    [:to {:doc "the outside namespace it depends on"} :string]
                    [:to-module {:doc (str "that namespace's module, so the stub can"
                                           " be labelled without a second request")} :string]]]]
     [:in {:doc "edges arriving from outside"}
      [:sequential [:map
                    [:from {:doc "the outside namespace that depends on us"} :string]
                    [:from-module {:doc "that namespace's module"} :string]
                    [:to {:doc "the namespace INSIDE this module it reaches"} :string]]]]]]
   [:layers {:doc (str "the topological layering of the namespaces WITHIN this"
                       " module, deepest first — the same analysis /api/modules"
                       " ships one level up, and drawing it is still yours")}
    [:sequential [:sequential :string]]]
   [:cycles {:doc (str "dependency cycles among this module's own namespaces, each"
                       " entry the namespaces caught in one. Empty is healthy")}
    [:sequential [:sequential :string]]]])

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
   [:modules {:doc "one row per module, sorted by name"} [:sequential module-row]]
   [:layers {:doc (str "the TOPOLOGICAL layering, deepest first: each entry is the"
                       " modules at that depth, and a module's dependencies are all"
                       " in earlier entries. This is ANALYSIS — the store's own"
                       " module graph, which no consumer can recompute — and it is"
                       " deliberately not a drawing: placing boxes on these rungs is"
                       " yours, and every consumer should get to disagree about it")}
    [:sequential [:sequential :string]]]
   [:cycles {:doc (str "dependency cycles, each entry the modules caught in one."
                       " Empty is the healthy case and the usual one. Alongside"
                       " :layers rather than inside it because a cycle is a FINDING"
                       " about the architecture, not a drawing instruction — on a"
                       " tangled store it is the most useful thing on the screen,"
                       " and a consumer that only wants the verdict should not have"
                       " to read geometry to find it")}
    [:sequential [:sequential :string]]]])

(def search-request
  "`GET /api/search` — what a caller SENDS: the query text and a row budget,
  both as query parameters.

  `:q` is `:optional`, and that is a statement about the SCREEN rather than a
  courtesy. A search page is reachable by URL, so a reader can land on it
  having asked nothing; the answer to that is the empty state, not a 400 and
  an error panel. `search-results` comes back the same shape either way, with
  zeroes.

  `:limit` is optional because there is a declared default
  (`model/search-limits`) and a declared ceiling. A caller that sends nothing
  gets the default; one that asks for more than the ceiling is clamped to it,
  and `:total` is counted before the cut either way, so \"showing 20 of 340\"
  cannot be made false by a limit the caller did not choose."
  [:map
   [:q {:optional true
        :doc (str "the text to search for. Absent is a legal ask and answers"
                  " with the empty state rather than a 400, because a search"
                  " page is reachable by URL")} :string]
   [:limit {:optional true
            :doc (str "how many rows to return. Clamped to a declared ceiling,"
                      " and :total is counted BEFORE the cut either way, so"
                      " 'showing 20 of 340' cannot be made false by a limit you"
                      " did not choose")} :int]])

(def search-results
  "`GET /api/search` — everything matching a query, ranked across all three
  grains at once.

  The DOOR. Every other read here answers a question a reader already knows
  how to ask; this is the one that finds the address, and without it `/store`
  opens on a module diagram with no way in.

  **`:rank` is one scale across kinds, not per-kind normalised.** That is the
  fact a consumer cannot derive and must be told, because it decides a layout:
  one ranked list of typed rows is only honest if a module at 0.9 really does
  beat a form at 0.5. The ladder is name-exact 1.0, name-prefix 0.9,
  name-substring 0.8, doc 0.5, why 0.4, source 0.2.

  **`:hits` arrive SORTED**, rank descending, and are meant to be rendered in
  the order given. A consumer re-deriving the sort is a second opinion on the
  one thing it asked this side to own, and it goes stale the first time the
  ladder changes.

  **`:matched` is data, not decoration.** A hit whose name says nothing about
  the query reads as a bug unless the row can say the docstring is what
  matched. It is also what lets `\"source\"` be included at all — unlabelled, a
  source hit looks like a ranking failure rather than the escape hatch it is.

  **`:totals` is per kind and counted BEFORE `:limit`**, which is the whole
  reason it is here rather than left to the consumer: a limited hit list
  cannot know how many modules matched beyond the cut. `:total` is that summed.
  Named beside `:total` rather than folded into it so the two cannot read as a
  typo for each other.

  **No `:address`, and that is deliberate.** The rows carry the component
  parts — `:kind`, `:name`, `:module`, `:ns`, `:form-id` — and the consumer
  builds its own URL. Emitting `/store/module/<m>` here would put a
  CONSUMER'S routing scheme in the producer: slopp would be asserting a fact
  about somebody else's app, in their units, with nothing on either side able
  to check it, and a route change over there would silently falsify strings
  over here. It is the same error as naming a port for its consumer. One
  producer of the scheme, and it is the side that owns the routes."
  [:map
   [:query {:doc "the query as received, echoed so a result can label itself"} :string]
   [:total {:doc (str "how many things matched in all, counted BEFORE :limit —"
                      " so a limited hit list can still say 'showing 20 of 340'")} :int]
   [:totals {:doc (str "the same count split by kind, also before :limit: a cut"
                       " hit list cannot know how many modules matched beyond"
                       " the cut, which is why this is not left to the consumer")}
    [:map
     [:modules {:doc "modules matching, before :limit"} :int]
     [:namespaces {:doc "namespaces matching, before :limit"} :int]
     [:forms {:doc "forms matching, before :limit"} :int]]]
   [:hits {:doc (str "the matches, SORTED by :rank descending and meant to be"
                     " rendered in the order given — re-deriving the sort is a"
                     " second opinion on the one thing this side was asked to own")}
    [:sequential
     [:map
      [:kind {:doc "which grain matched"} [:enum "module" "namespace" "form"]]
      [:name {:doc "the thing's own name, unqualified for a form"} :string]
      [:module {:optional true :doc "the module it belongs to; absent for a module hit"} :string]
      [:ns {:optional true :doc "the namespace it belongs to; absent above form grain"} :string]
      [:form-id {:optional true :doc "the form's stable address, for a form hit"} :string]
      [:sig {:optional true :doc "the form's arglists, one string per arity"} [:sequential :string]]
      [:doc {:optional true :doc "the form's own docstring, when it has one"} :string]
      [:why {:optional true :doc "the recorded intent of the last write to it"} :string]
      [:matched {:doc (str "WHICH text matched, and it is data rather than"
                           " decoration: a hit whose name says nothing about the"
                           " query reads as a bug unless the row can say the"
                           " docstring is what matched")}
       [:enum "name" "doc" "why" "source"]]
      [:rank {:doc (str "ONE scale across kinds, not normalised per kind — a"
                        " module at 0.9 really does beat a form at 0.5, which is"
                        " what makes a single mixed list honest. Name-exact 1.0,"
                        " name-prefix 0.9, name-substring 0.8, doc 0.5, why 0.4,"
                        " source 0.2")} :double]]]]])
