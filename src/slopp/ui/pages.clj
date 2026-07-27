(ns slopp.ui.pages
  "Read-only store browser: server-rendered hiccup pages over the query
  surfaces — the D-web-html dogfood. Plain links, full-page renders, zero
  writes; rendering arbitrary store source through the escaper is a
  standing security exercise.

  Lives in slopp.ui.**, slopp's OWN webapp, and never in slopp.web.** —
  slopp.web is the framework every user's app is built on and ships in the
  slim jar, so an app page placed there would ride into every user's
  application. The dependency runs slopp.ui → slopp.web, never back.

  Pages hold hiccup and nothing else; the data they render is assembled by
  slopp.ui.model, which is where a static JSON sink would attach."
  (:require [rewrite-clj.node :as n]
            [slopp.store :as store]
            [slopp.web.html :as html] [slopp.web.css :as css] [garden.stylesheet :as gs] [slopp.ui.model :as model] [clojure.string :as str] [slopp.ui.views :as views] [slopp.api.artifacts :as artifacts] [slopp.ui.basepath :as bp] [slopp.web.contract :as contract]))

(defn- form-doc
  "A form's docstring, or nil — through `store/form-docstring`, which is the
  only thing that knows when index 2 is a docstring and when it is a `def`'s
  VALUE.

  It read index 2 directly and took any string it found, so
  `(def greeting \"hello\")` rendered \"hello\" as the form's documentation.
  Wrong-index reads do not throw; they return something plausible, which is
  why this class keeps surviving review."
  [e]
  (store/form-docstring (:node e)))

(defn ^{:web/read :browse/namespaces} namespaces-read
  "Read performer: `{:ns sym :forms n}` rows for every namespace, sorted."
  [{:keys [session]} _]
  (let [st (:store @session)]
    (mapv (fn [nsx] {:ns nsx :forms (count (filter :name (store/forms st nsx)))})
          (sort (keys (:namespaces st))))))

(defn ^{:web/read :browse/ns-outline} ns-outline-read
  "Read performer: one namespace's `{:name :doc}` form rows in store order
  plus the test namespaces covering it, or nil for an unknown namespace."
  [{:keys [session]} nsx]
  (let [st  (:store @session)
        sym (symbol (str nsx))]
    (when (contains? (:namespaces st) sym)
      {:ns sym
       :forms (into []
                    (keep (fn [e]
                            (when (:name e)
                              {:name (:name e) :doc (form-doc e)})))
                    (store/forms st sym))
       :tested-by (model/tests-covering st sym)})))

(defn ^{:web/read :browse/form-source} form-source-read
  "Read performer: one form's source text, or nil when the form is unknown."
  [{:keys [session]} {:keys [ns name]}]
  (let [st (:store @session)]
    (when-let [e (store/form-named st (symbol (str ns)) (symbol (str name)))]
      (n/string (:node e)))))

(defn- request-base
  "The path prefix this request arrived under, from the proxy's
  `X-Slopp-Base` header — `\"\"` when there is none.

  A header rather than configuration, because the prefix is a fact about THIS
  request, not about the project: the same server answers directly on its own
  port AND through a hub, and a configured value would be wrong for one of
  them."
  [req]
  (bp/normalize (get-in req [:headers "x-slopp-base"])))

(defn- document
  "The application document, served under path prefix `base` (\"\" = the root).

  This is the ONLY HTML the server produces. Every screen is the client
  rendering data from `/api/*`, so there is no per-page template here and no
  second renderer that could disagree with the browser's.

  It is deliberately EMPTY rather than server-rendered-then-hydrated. A
  pre-rendered shell would be a second implementation of every screen — the
  exact drift the `:cljc` views exist to prevent — and slopp's reviewer UI is
  a local tool, so first-paint latency is not the constraint that would
  justify it.

  Every url it emits is prefixed, and the mount point carries the base as
  `data-base` — which is how the CLIENT learns where it is mounted, for both
  the generated api wrappers and the router. A page cannot be asked to guess
  that, and behind the hub's proxy a bare `/js/main.js` resolves at the HUB,
  which does not serve it (D-ui-hub part 2)."
  [base]
  (let [b (bp/normalize base)]
    (html/html-response
     (html/page
      {:html/title "slopp"
       :html/head [[:link {:rel "stylesheet" :href (bp/prefixed b "/css/style.css")}]
                   [:script {:src (bp/prefixed b "/js/main.js") :defer true}]]}
      ;; the attribute appears only when there IS a prefix, so an app served at
      ;; the root emits the document it always did, byte for byte — the client
      ;; reads a missing attribute as no base
      [:div (cond-> {:id "app"} (seq b) (assoc :data-base b))]))))

(defn ^{:web/read :ui/client-js} client-js-read
  "Read performer: the compiled client bundle, or nil when nothing has been
  compiled.

  The bundle is an ARTIFACT — derived, so the store holds its sha and the
  recipe that rebuilds it while the bytes live in the content-addressed
  cache on disk. A cleared cache therefore serves an empty 204 rather than
  a 500: nothing is corrupt, the bytes are simply regenerable and absent.

  The recipe does NOT ride this response — it is a JS body, with nowhere
  honest to put one. It is reported where a caller can act on it: `build!`
  returns `:missing-artifacts` with a refill instruction per path, and
  `artifacts/fetch` carries the recipe directly. Recompiling is what fills
  this in."
  [{:keys [session]} _]
  (let [r (artifacts/fetch (:dir @session) (:store @session) "public/cljs/main.js")]
    (when-let [bs (:bytes r)]
      (String. ^bytes bs "UTF-8"))))

(defn ^{:web/method :get :web/path "/js/main.js" :web/auth :public
        :web/response :string :web/client false
        :web/reads {:js [:ui/client-js []]}}
  client-bundle
  "GET /js/main.js — the compiled client bundle, read from the files manifest
  where `compile_client` put it.

  A declared ENDPOINT rather than an `http.static.*` mount, because this is
  slopp's OWN UI: requiring a user to configure a static mount before slopp's
  reviewer page works would be asking them to wire up our plumbing. The mount
  mechanism stays what it is for — a user's own assets.

  `/js/`, not `/assets/cljs/`. The URL is an ADDRESS, not a description of
  the toolchain that produced the file; it said `cljs` because ClojureScript
  compiled it, which is exactly the kind of implementation detail that ends
  up in someone's bookmark. Nothing about serving JavaScript changes if the
  compiler does.

  204 when nothing has been compiled, so a store that never ran
  `compile_client` gets an empty body rather than a 404 the page cannot tell
  apart from a broken route."
  [req]
  ;; :web/raw — the adapters write the body VERBATIM. Without it the
  ;; dispatcher encodes the body as JSON and stamps application/json, which a
  ;; browser refuses to execute as a script: the bundle arrives, all 1.5MB of
  ;; it, and does nothing. Same reason css-response carries it.
  (if-let [js (:js (:web/reads req))]
    {:status 200 :web/raw true
     :headers {"Content-Type" "text/javascript; charset=utf-8"} :body js}
    {:status 204 :web/raw true
     :headers {"Content-Type" "text/javascript; charset=utf-8"} :body ""}))

(defn ^{:web/read :ui/timeline} timeline-read
  "Read performer: the reviewer landing model — milestones plus the
  working set."
  [{:keys [session]} _]
  (model/timeline session))

(defn ^{:web/method :get :web/path "/store" :web/auth :public
        :web/response :string :web/client false}
  store-root
  "GET /store — the same application document as `/`.

  It exists as its own route because `:web/spa [\"/store\"]` generates
  `/store/*spa-path`, which needs at least one segment below the prefix. The
  prefix ROOT is deliberately not covered by the fallback — serving the app
  at a prefix root should be an intent the app states, not something a
  catch-all quietly picks up.

  Two routes rather than one is the honest cost of that stance."
  [req]
  (document (request-base req)))

(defn ^{:web/method :get :web/path "/" :web/auth :public
        :web/response :string :web/client false
        :web/spa ["/store" "/change"]}
  app-document
  "GET / — and, through `:web/spa`, every client route beneath `/store` and
  `/change`.

  ONE endpoint where six server-rendered pages used to be. It answers with
  the same empty document every time; which screen appears is
  `views/route-for` reading the URL in the browser.

  `:web/spa` is what makes a deep link survive a refresh: `/store/form/f123`
  is real to the client router and meaningless to the server's, so without a
  declared fallback a reload would 404. Declared and SCOPED rather than a
  root catch-all — a path outside these prefixes still 404s, so a typo is
  still distinguishable from a page.

  Note the prefix ROOTS are not covered by the generated catch-alls
  (`/store/*` needs a segment below it), which is why `/store` itself is a
  separate route rather than a side effect of the fallback."
  [req]
  (document (request-base req)))

(defn ^{:web/read :ui/change} change-read
  "Read performer: the review of one `from..to` range, or nil when the
  range is malformed or names deltas that do not exist — the page needs
  those to be the same answer, since both are a 404."
  [{:keys [session]} range-str]
  (let [[from to] (str/split (str range-str) #"\.\." 2)]
    (when (and (seq from) (seq to))
      (model/change-view session from to))))

(defn ^{:web/read :ui/form} form-view-read
  "Read performer: one form's page model by ID at the requested rendering
  FIDELITY. Addressed by BOTH halves of the URL — the id from the path,
  `?view=` from the query — so it is declared over the whole request
  rather than one segment.

  nil when no form has that id, or when the fidelity does not exist. Both
  are a 404, and neither is a reason to render the other thing."
  [{:keys [session]} {:keys [path-params query-params]}]
  (model/form-view session (str (:id path-params)) (:view query-params)))

(defn ^{:web/read :browse/modules} modules-read
  "Read performer: the architecture as module rows plus a drawable canvas.

  Named `:browse/modules` to sit beside `:browse/namespaces` — reads are
  addressed by VOCABULARY rather than by var, so any second representation
  of the architecture shares this one answer instead of re-deriving it."
  [{:keys [session]} _]
  (model/module-index session))

(def module-graph-styles
  "Appearance of the Code screen's module map.

  Deliberately muted. An architecture diagram earns its legibility from
  spacing, hierarchy and the edges it does NOT draw — colour spent on
  decoration makes a graph harder to read, not easier. So exactly two things
  are tinted: the foundation band, which should read as ground rather than
  as more boxes, and whatever the reader has hovered.

  Its own var because the main stylesheet is a hundred lines and adding a
  block should not mean retyping it."
  [[:.module-graph {:width "100%" :height "auto" :max-width "52rem"
                    :display "block" :margin "1.5rem auto"
                    :font-family "ui-monospace, monospace"}]
   [:.module-node
    [:rect {:fill "#fff" :stroke "#bbb" :stroke-width "1.5"}]
    [:text {:fill "#333" :font-size "15px"}]]
   ;; the band reads as ground: filled, borderless, quieter type
   [:.module-node.foundation
    [:rect {:fill "#eef1f4" :stroke "none"}]
    [:text {:fill "#667" :font-size "13px"}]]
   [:.module-edge {:fill "none" :stroke "#c4c4c4" :stroke-width "1.5"}]
   ;; sketch outlines are PATHS, not rects — without their own fill rule they
   ;; render as filled black blobs, the same failure as an unstyled export
   [:.module-node [:path.sketch {:fill "none" :stroke "#999" :stroke-width "1.4"}]]
   [:.module-node.foundation [:path.sketch {:stroke "#aab"}]]
   [:.module-node:hover [:path.sketch {:stroke "#2a6" :stroke-width "2"}]]
   [:.module-graph [:marker [:path {:fill "#c4c4c4"}]]]
   ;; hover is the whole interaction budget for now — real selection wants
   ;; the client to dim non-neighbours, which is a state concern, not a CSS one
   [:.module-node:hover
    [:rect {:stroke "#2a6" :stroke-width "2.5"}]]
   [:.module-row {:list-style "none" :margin "0.15rem 0"}]
   [:.module-head {:display "flex" :gap "0.5rem" :align-items "baseline"
                   :cursor "pointer" :padding "0.15rem 0"}]
   [:.module-name {:font-weight "600"}]
   [:.module-meta {:color "#888" :font-size "0.8rem"}]
   [:.module-tag {:color "#667" :font-size "0.7rem" :border "1px solid #ccd"
                  :border-radius "3px" :padding "0 0.3rem"}]
   [:.ns-row {:padding-left "0.9rem" :font-size "0.9rem"}]
   [:.finding {:border-left "3px solid #c93" :padding "0.4rem 0.9rem"
               :background "#fdf6ec" :margin "1rem 0"}]
   (gs/at-media {:prefers-color-scheme :dark}
                [:.module-node
                 [:rect {:fill "#1c1c1c" :stroke "#444"}]
                 [:text {:fill "#ccc"}]]
                [:.module-node.foundation
                 [:rect {:fill "#232733" :stroke "none"}]
                 [:text {:fill "#889"}]]
                [:.module-edge {:stroke "#555"}]
                [:.module-node [:path.sketch {:stroke "#777"}]]
                [:.module-node.foundation [:path.sketch {:stroke "#667"}]]
                [:.module-node:hover [:path.sketch {:stroke "#5c9"}]]
                [:.module-graph [:marker [:path {:fill "#555"}]]]
                [:.module-node:hover [:rect {:stroke "#5c9"}]]
                [:.module-meta {:color "#888"}]
                [:.module-tag {:color "#99a" :border-color "#445"}]
                [:.finding {:background "#241f14" :border-left-color "#a83"}])])

(defn ^{:web/method :get :web/path "/css/style.css" :web/auth :public :web/response :string :web/client false}
  store-stylesheet
  "GET /css/style.css — the browser's own styling as garden data (CSS as
  Clojure data, tracked like every other form). A safe GET; served text/css.

  Combinators, because two of the three look alike and getting them wrong
  shipped a broken layout: `:.app>nav` (one keyword) is CHILD, `[:.app [:nav]]`
  (nesting) is DESCENDANT, and `[:.app :nav]` (siblings) is a selector GROUP.
  A bare `>` is none of them — it reads as `clojure.core/>` and garden renders
  the function object, which `web.css/render` now refuses."
  [_req]
  (css/css-response
   [[:body {:font-family "system-ui, sans-serif" :line-height 1.5
            :margin 0 :padding 0}]
    ;; THE THREE PANES. Auto-sized outer columns rather than fixed ones, so a
    ;; page passing no :local or :detail leaves no gap where a pane would have
    ;; been — the shell omits the element and the grid simply has less in it.
    [:.app {:display "grid" :gap "0"
            :grid-template-columns "auto minmax(0, 1fr) auto"
            ;; an explicit second row, so the bar keeps its own height and the
            ;; panes take everything under it instead of sharing one row
            :grid-template-rows "auto minmax(0, 1fr)"
            :min-height "100vh"}]
    ;; THE GLOBAL BAR — spans every column, and reads as a bar: horizontal,
    ;; one line tall, its own background. Before this it was a vertical
    ;; bulleted list sitting in the first column, which made the app's global
    ;; navigation look like part of the left pane.
    [:header {:grid-column "1 / -1"
              :display "flex" :align-items "center" :gap "1.5rem"
              :height "3rem" :padding "0 1.25rem"
              :border-bottom "1px solid #ddd" :background "#fafafa"
              :font-size "0.95rem"}]
    [:header [:ul {:list-style "none" :margin 0 :padding 0
                   :display "flex" :gap "1.5rem"}]]
    [:header [:a {:text-decoration "none"}]]
    ;; CHILD, not descendant: a breadcrumb <nav> inside <main> must not pick
    ;; up left-pane styling.
    [:.app>nav {:border-right "1px solid #ddd" :padding "1rem"
                :width "16rem" :overflow-y "auto"}]
    [:.app>nav [:ul {:list-style "none" :margin 0 :padding 0}]]
    [:.app>nav [:li {:padding "0.15rem 0"}]]
    [:#ns-filter {:width "100%" :box-sizing "border-box"
                  :padding "0.35rem 0.5rem" :margin-bottom "0.75rem"
                  :border "1px solid #ccc" :border-radius "4px"
                  :font-size "0.9rem" :background "transparent"
                  :color "inherit"}]
    [:main {:padding "1.25rem" :max-width "60rem"}]
    [:aside {:border-left "1px solid #ddd" :padding "1rem" :width "20rem"
             :font-size "0.9rem" :overflow-y "auto"}]
    [:.active {:font-weight "600"}]
    ;; narrow: stack. Three independently-scrolling boxes on a phone is worse
    ;; than a long document.
    (gs/at-media {:max-width "60rem"}
                 [:.app {:grid-template-columns "minmax(0, 1fr)"}]
                 [:.app>nav {:width "auto" :border-right "none"
                             :border-bottom "1px solid #ddd"}]
                 [:aside {:width "auto" :border-left "none"
                          :border-top "1px solid #ddd"}])
    [:h1 {:font-size "1.4rem"}]
    [:h2 {:font-size "1.1rem" :margin-top "1.75rem"}]
    [:h3 {:font-size "1rem" :margin-bottom "0.25rem"}]
    [:h4 {:font-size "0.95rem" :margin-bottom "0.25rem"}]
    [:a {:color "#2a6"}]
    [:small {:color "#777"}]
    [:nav {:font-size "0.9rem" :color "#777"}]
    [:pre {:background "#f4f4f4" :padding "1rem" :border-radius "4px"
           :overflow-x "auto"}]
    [:code {:font-family "ui-monospace, monospace"}]
    ;; syntax classes: only what the CST can tell apart WITHOUT guessing, so
    ;; nothing here is coloured on a hunch (leaf-class carries the rest as text)
    [:.string {:color "#a50"}]
    [:.keyword {:color "#279"}]
    [:.number {:color "#279"}]
    [:.comment {:color "#888" :font-style "italic"}]
    [:.special {:color "#83d" :font-weight "600"}]
    [:.delim {:color "#999"}]
    ;; a diff line is a BLOCK so its marker column and its background line
    ;; up down the whole hunk, rather than hugging the text
    [:.del {:display "block" :color "#a33" :background "#fdeeee"}]
    [:.add {:display "block" :color "#178" :background "#eef7f2"}]
    [:article {:border-left "3px solid #ddd" :padding-left "0.9rem"
               :margin "1.25rem 0"}]
    [:footer {:margin-top "2.5rem" :border-top "1px solid #ddd"
              :padding-top "0.6rem"}]
    module-graph-styles
    (gs/at-media {:prefers-color-scheme :dark}
                 [:body {:background "#111" :color "#ddd"}]
                 [:header {:background "#191919" :border-bottom-color "#333"}]
                 [:.app>nav {:border-right-color "#333"}]
                 [:aside {:border-left-color "#333"}]
                 [:#ns-filter {:border-color "#444"}]
                 [:pre {:background "#1c1c1c"}]
                 [:a {:color "#5c9"}]
                 [:small {:color "#999"}]
                 [:nav {:color "#999"}]
                 [:.string {:color "#d94"}]
                 [:.keyword {:color "#7bd"}]
                 [:.number {:color "#7bd"}]
                 [:.comment {:color "#777"}]
                 [:.special {:color "#b9f"}]
                 [:.delim {:color "#666"}]
                 [:.del {:color "#e88" :background "#2a1a1a"}]
                 [:.add {:color "#6cb" :background "#14241f"}]
                 [:article {:border-left-color "#333"}]
                 [:footer {:border-top-color "#333"}])]))

(defn ^{:web/method :get :web/path "/api/modules.svg" :web/auth :public
        :web/response :string :web/client false
        :web/reads {:modules [:browse/modules []]}}
  modules-svg
  "GET /api/modules.svg — the module map as a standalone SVG file.

  Getting the diagram OUT is the point: an SVG opens in any browser and
  imports into Figma, Illustrator, Keynote and Docs, so this is the export
  that reaches the most places for the least new code.

  It renders the SAME `views/module-graph` the client does, so the file
  cannot drift from what is on the screen — the alternative, a second
  drawing routine for export, is how the two quietly diverge.

  The stylesheet is INLINED. An SVG that carries class names and no CSS
  opens as black rectangles, which reads as a broken export rather than an
  unstyled one; inlining also carries the dark-mode block, so the file
  adapts wherever it lands. `:web/raw` because the body is markup, not JSON,
  and `:web/client false` for the same reason — there is no typed wrapper to
  generate for a file download."
  [req]
  (let [svg (views/module-graph (:picture (:modules (:web/reads req))))]
    {:status 200
     :web/raw true
     :headers {"Content-Type" "image/svg+xml; charset=utf-8"}
     :body (html/render
            (into [(first svg) (second svg)
                   [:style [:html/raw (css/render module-graph-styles)]]]
                  (drop 2 svg)))}))

(defn ^{:web/read :ui/contract} contract-read
  "The shape of this app's own API, for a consumer that generates a typed
  client against it.

  The namespace list arrives through `perform-ctx` rather than being reached
  for here: only the SERVER knows what it serves, and a performer that imported
  that list would invert the dependency (slopp.ui.server already requires this
  namespace). It is data on the way in, like every other dep."
  [ctx _]
  (contract/contract-document (:served-namespaces ctx)))
