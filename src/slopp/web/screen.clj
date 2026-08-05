(ns slopp.web.screen
  "A rendered screen as structured text — hiccup in, readable lines out, so a
  UI can be reviewed and asserted on with no browser anywhere.

  **The other half already existed.** A `:cljc` wiring map with its effects
  passed in routes, fetches, derives and renders on a JVM today; driving an app
  headless was never the missing piece. What came out the far end was hiccup
  nobody could read, and that was the whole gap — which is why the review kept
  going back to screenshots for bugs that were plain wrong sentences.

  The obvious substitute is a flatten — every string in tree order, joined by
  spaces. Measured against a real screen it came out LONGER than the readout
  and carried less: class names and region names bleed into the prose, headings
  run into the paragraphs after them, and no address survives at all.

  **Two audiences, one output.** Ad hoc, [[of]] is what you read instead of
  opening a browser. In a test, [[lines]] is what you assert on, and it
  replaces the `(->> (tree-seq coll? seq v) (filter string?) (str/join \" \"))`
  helper that tends to get copied into every `deftest` that needs it — none of
  which separate a heading from the paragraph after it.

  **Depends on nothing but `clojure.string`** — no view, no app, no store. That
  is what let it move into the framework unchanged from the app it was built
  in, and it is what lets any project whose views are data use it the same way.
  The only structure it asks of an app is `:data-region` on its panes, which is
  markup an app already writes to address them.

  It sits beside `slopp.web.html` on purpose, and the pairing is where the name
  comes from: one renders hiccup for a BROWSER, this renders it for a READER.

  **Deliberately not `browser`**, which was the first instinct and is the one
  word this must not take. There is a real browser in this story — the review
  loop it replaces drove Chrome through Playwright — so \"did the browser check
  pass\" has to keep exactly one meaning. `browser` would also promise
  navigation and interaction that live nowhere near here: the navigating is the
  app's `:cljc` wiring, and the driving is the tool's.

  **`:clj`, not `:cljc`, deliberately.** A `:cljc` namespace is compiled into
  the client bundle, and a reading aid has no business in a user's shipped JS.

  **Not a screenshot, and it must not be sold as one.** Wrapping and contrast
  need pixels; see [[of]]."
  (:require [clojure.string :as str] [slopp.web.screen.hiccup :as hiccup] [slopp.web.dispatch :as dispatch] [slopp.web.screen.render :as render]))

(defn lines
  "A rendered screen as a VECTOR of text lines — the checking face of [[of]],
  which joins these with newlines for reading.

  Two faces because there are two audiences and one of them was measurably
  badly served by a single string. A readout makes assertions easy to write and
  therefore easy to write TOO BROADLY: a real tint check matched its class
  pattern anywhere in the page, so it claimed the diagram, checked a list, and
  stayed green with the layout torn out. A whole-page `str/includes?` is one
  keystroke from asserting nothing in particular.

  Addressable lines make the NARROW assertion the easy one:

  ```clj
  (->> (lines view) (filter #(str/starts-with? (str/triml %) \"<svg\")) first)
  (lines view {:region \"main\"})
  ```

  Options — every one defaulted for READING and loosened for CHECKING:

  | key | default | reach for it when |
  |---|---|---|
  | `:detail` | `:structured` | `:prose` — sentences only, for \"does it say X\" |
  | `:list-head` | `3` | a test needs every row; `nil` removes the cap |
  | `:attrs` | `#{}` | checking an overlay — `#{:class}` renders `{tint}` |
  | `:region` | none | scoping to one pane; THROWS if it is not there |

  `:region` cuts the TREE before anything renders, so it composes with every
  other option and comes back at depth 0."
  ([hiccup] (lines hiccup nil))
  ([hiccup opts]
   (let [o (merge {:list-head 3 :attrs #{}} opts)
         t (if-let [r (:region o)] (hiccup/region hiccup r) hiccup)]
     (vec (remove nil? (render/emit t 0 o))))))

(defn of
  "A rendered screen as structured text — what a reader would see, at a size
  worth reading. Hiccup in, text out.

  ```clj
  (screen/of (views/page state))
  (screen/of pane {:attrs #{:class} :list-head nil})
  ```

  ```
  § main
    # code
    3 modules, 4 namespaces — 1 of them foundation
    <ul ×32>
      demo.web [/store/module/demo.web] 9 undocumented, of 12
      +31 more
    <svg module-graph — 3 module-link, 1 module-node gap-w4, 17 module-node gap-w0>
  ```

  **This is the half that did not exist.** Driving an app headless already
  worked — a `:cljc` wiring map with `:fetch`/`:render` passed in routes,
  fetches, derives and renders on a JVM with no browser anywhere. What came out
  the far end was hiccup nobody could read, so the review went back to
  screenshots for bugs that were plain wrong sentences.

  Options and the two-audience argument are on [[lines]], which this joins.
  Reach for `lines` in a test; reach for this to LOOK at a screen.

  **It is not a screenshot and must not be sold as one.** Two of the seven bugs
  that got past a careful reviewer needed pixels — a list wrapping over three
  lines, and a tint invisible against white — and no amount of structure in a
  text rendering reaches either. Wrapping and contrast are still eyes.

  Lifted from slopp-ui's `readout`, where it was built against a week of real
  view bugs. Depends on nothing but `clojure.string` — no view, no app, no
  store — which is what let it move here unchanged, and what lets any project
  whose views are data use it the same way."
  ([hiccup] (of hiccup nil))
  ([hiccup opts] (str/join "\n" (lines hiccup opts))))

(defn open
  "Open a headless browser over `app`. Two shapes, and an app may be both.

  **A server-rendered slopp.web app — its own ctx, nothing added:**

  ```clj
  (open {:web/routes rows …})   ; the same map slopp.web/serve! runs on
  ```

  A visit is then a real request through the real pipeline — routing, auth
  policy, declared reads, the handler, effects — and the document is what a
  browser would have received. Nothing is declared twice: the route table
  already exists, so the case `slopp.web` is actually built for is the case
  that needs no ceremony.

  **A client-state app — a page:**

  ```clj
  {:state (atom {:n 0})                  ; REQUIRED, the app's own state
   :view  (fn [state] …)                 ; REQUIRED, state -> hiccup
   :navigate (fn [state path] state')}   ; optional, client-side routing
  ```

  **Both, for a mounted page that carries client-side logic** — which is
  ordinary and not an SPA. The server render arrives through the routes; the
  `:view` re-renders after an event, and finds the dispatched document in its
  own state under `:slopp.web.browser/document`. Without a `:view` the document
  is simply static after load, which is exactly right for a page with no client
  logic and is not something to warn about.

  **slopp runs the browser; the app supplies the page.** That split is the
  design, and it is where three earlier drafts went wrong. Each had the app
  hand back a driver — `{:visit … :click …}`, or a path→hiccup function — and
  so made every project write its own fake browser. An adapter like that is the
  least-exercised code in a project and is free to drift from the path a real
  browser takes, so a test drives a lookalike and passes while the real screen
  is wrong. That is the bug this exists to kill; a design that reintroduces it
  one level up is not a fix."
  [app]
  (atom {:app app :path nil :document nil}))

(defn tree
  "The session's current document.

  With a `:view`, that is the app's view over the app's state, RE-DERIVED on
  every call rather than cached — which is what makes a handler's effect
  visible without the browser knowing anything happened. A real browser earns
  this with a render loop; here the view is a pure function of state, so
  reading IS re-rendering.

  Without one, it is whatever the last [[visit!]] received from the app's own
  routes. A mounted page with no client logic is static after load, and that is
  the correct answer rather than a limitation."
  [session]
  (let [{:keys [app document]} @session]
    (if-let [view (:view app)]
      (view @(:state app))
      document)))

(defn visit! 
  "Go to `path`. Returns the session.

  How a url resolves depends on what the app IS, and both answers are the
  app's own — slopp never learns what `/store` means:

  - **`:navigate`** — `(fn [state path] state')`, client-side routing. ONE
    function, deliberately not a router: which screen, which params, what to
    fetch, whether anything loads at all is the app's business.
  - **`:web/routes`** — a real request through `slopp.web.dispatch/handle!`:
    routing, auth policy, declared reads, the handler, effects. The document is
    what a browser would have received, and the app declared nothing extra to
    get it.

  `:navigate` wins where both exist, because an app that routes on the client
  is telling you a url change is a client event.

  **A non-hiccup body is rendered as its STATUS and its data**, never as a
  blank page. A 404 that read as an empty screen would send a reader looking
  for a rendering bug in a handler that was never reached.

  **An app with neither REFUSES rather than doing nothing.** An app without
  urls is legitimate, and visiting one is a mistake worth hearing about — a
  silent no-op reads as a page that navigated and rendered nothing, which is a
  bug report about the app rather than about the call."
  [session path]
  (let [{:keys [app]} @session]
    (cond
      (:navigate app)
      (swap! (:state app) (:navigate app) path)

      (:web/routes app)
      (let [resp (dispatch/handle! app {:request-method :get :uri path})
            body (:body resp)
            doc  (if (vector? body)
                   body
                   [:div [:p (str "HTTP " (:status resp))] [:pre (pr-str body)]])]
        (swap! session assoc :document doc)
        ;; a mounted page that ALSO has client logic: its :view re-renders from
        ;; state, so the server's document has to be reachable from there
        (when-let [st (:state app)] (swap! st assoc ::document doc)))

      :else
      (throw (ex-info (str "this app declares neither :navigate nor :web/routes,"
                           " so it has no urls — cannot visit " (pr-str path)
                           ". Open it on a slopp.web ctx, or add"
                           " :navigate (fn [state path] state') to the page")
                      {:path path})))
    (swap! session assoc :path path)
    session))

(defn click!
  "Click the element `target` names, running the app's own handler. Returns the
  session, so calls thread.

  `target` is an element's visible text (\"Add\") or its `:href` (\"/store\").
  Whatever the handler does to the app's state is simply true afterwards —
  [[tree]] re-derives, exactly as a re-render would.

  **Three ways a handler can be written, and all three are the app's own.**
  Checked against the libraries rather than inferred from one app:

  - `{:on-click (fn [e] …)}` — Reagent. Invoked here.
  - `{:on {:click (fn [e] …)}}` — Replicant, function form. Invoked here.
  - `{:on {:click [:action …]}}` — Replicant, DATA form. Handed to the page's
    `:dispatch`, which mirrors `replicant.dom/set-dispatch!`'s
    `(event-data handler-data)` arity. The handler data arrives VERBATIM,
    because that is what a dispatcher switches on.

  An `:href` with no handler NAVIGATES, through [[visit!]] and so through the
  page's own `:navigate`. That is the one opinion a browser holds that an app
  cannot override, and it is a browser's opinion rather than a framework's:
  following a link is what clicking one MEANS.

  **A function is called with one argument, an event map, unless it takes
  none.** Both spellings are everywhere in real code — `(fn [e] …)` and
  `#(swap! state …)` — and the arity is read off the function rather than
  discovered by catching `ArityException`, which would report a genuine arity
  bug INSIDE a handler as a signature mismatch and send the reader somewhere
  the defect is not.

  **Data with no `:dispatch` declared REFUSES.** A click that reported success
  and changed nothing is the worst answer available here."
  [session target]
  (let [node (hiccup/target-node (tree session) target)
        a    (hiccup/attrs node)
        ev   {:kind :click :target target :text (hiccup/text node) :href (:href a)}
        [how v] (hiccup/handler node :click)]
    (case how
      :fn   (if (some #(and (= "invoke" (.getName ^java.lang.reflect.Method %))
                            (= 1 (count (.getParameterTypes ^java.lang.reflect.Method %))))
                      (.getDeclaredMethods (class v)))
              (v ev)
              (v))
      :data (if-let [d (:dispatch (:app @session))]
              (d ev v)
              (throw (ex-info (str "clicking " (pr-str target) " carries handler DATA "
                                   (pr-str v) " and this page declares no :dispatch,"
                                   " so nothing would run. Add :dispatch (fn [event data] …)"
                                   " to the page — it is the same function"
                                   " replicant.dom/set-dispatch! takes")
                              {:target target :handler v})))
      (when (:href a) (visit! session (:href a))))
    session))

(defn text
  "What is on the screen right now, as readable text — the whole assertion
  surface in one call.

  ```clj
  (screen b)                       ; the page
  (screen b \"main\")                ; one region, and THROWS if it is not there
  (screen b \"main\" {:attrs #{:class}})
  ```

  `(screen b)` is `(slopp.web.screen/of (tree b))`, which is the line every
  test writes and the one worth not retyping. The REGION arity is the one that
  earns its place: a whole-page `str/includes?` is one keystroke from asserting
  nothing in particular, and that is not hypothetical — the bug that prompted
  this whole exercise was a tint check matching its pattern anywhere on the
  page, so it claimed the diagram, checked a list, and stayed green with the
  layout torn out.

  Naming the region makes the narrow assertion the SHORTER one to write, which
  is the only kind of discipline that survives contact with a deadline. And a
  region that is not on the screen refuses rather than scoping to nothing —
  otherwise every absence assertion downstream of it passes over a blank page."
  ([session] (of (tree session)))
  ([session region] (text session region nil))
  ([session region opts]
   (of (tree session) (assoc opts :region region))))

(defn fill!
  "Type `value` into the field `name` addresses, running the app's own handler.
  Returns the session, so calls thread.

  `name` is the field's `:placeholder`, `:name`, `:id` or `:aria-label` —
  whichever the app happens to have written, since requiring a particular one
  would make the framework's testability someone's markup decision.

  The same three handler shapes [[click!]] takes: `:on-change` as a function,
  `:on {:input …}` / `:on {:change …}` as a function, or as DATA handed to the
  page's `:dispatch`. Both event names are tried, because Reagent apps write
  `:on-change` and a Replicant `:on` map usually names `:input`.

  **A function receives an EVENT MAP, and that is a convention the app's real
  browser shell must match.** A ClojureScript handler reaching for
  `(-> e .-target .-value)` cannot run on a JVM at all — interop on a Clojure
  map does not resolve — so a portable handler reads `(:value e)` or
  `(get-in e [:target :value])`, and both spellings are carried here. Turning a
  `js/Event` into that map is the shell's job, which is exactly the boundary
  the `^:web/page` rule already draws: the wiring is portable, the effects are
  `:cljs`. Nothing else about a browser event is invented — no bubbling, no
  default to prevent, no focus."
  [session name value]
  (let [node    (hiccup/field (tree session) name)
        ev      {:kind :input :field name :value value :target {:value value}}
        [how v] (hiccup/input-handler node)]
    (case how
      :fn   (v ev)
      :data (if-let [d (:dispatch (:app @session))]
              (d ev v)
              (throw (ex-info (str "the field " (pr-str name) " carries handler DATA "
                                   (pr-str v) " and this page declares no :dispatch,"
                                   " so typing would change nothing. Add"
                                   " :dispatch (fn [event data] …) to the page")
                              {:field name :handler v})))
      nil)
    session))

(defn ^{:unused-ok "no store form can call it: the screen tool reaches it by requiring-resolve INSIDE the verification image, where the app's vars are, and a test driving a script calls it there too"} drive!
  "Run an ordered `steps` script against `session`. Returns the session.

  ```clj
  (drive! s [{:visit \"/store\"} {:fill \"Filter\" :value \"web\"} {:click \"Go\"}])
  ```

  Data rather than a call chain, so the same script can arrive from a tool, a
  test, or a file — and so the ONE interpreter is here, testable, rather than
  assembled as a string by whatever is driving. A generated call chain is a
  second producer of this behaviour and would drift from it.

  There is no session BETWEEN scripts on purpose. Filling a box and then
  clicking Search is one sequence or it is nothing, and a session held across
  calls makes the same call answer differently depending on what ran before
  it. The cost is re-running a script to take one more step; what it buys is
  that a screen you looked at is one you can pin, because the pin is the same
  script."
  [session steps]
  (doseq [{:keys [visit click fill value]} steps]
    (cond
      visit (visit! session visit)
      click (click! session click)
      fill  (fill! session fill value)
      :else (throw (ex-info (str "a step must name one of :visit, :click or"
                                 " :fill — got " (pr-str (keys (or steps {}))))
                            {:steps steps}))))
  session)
