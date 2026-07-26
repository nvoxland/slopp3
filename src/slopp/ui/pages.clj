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
            [slopp.web.html :as html] [slopp.web.css :as css] [garden.stylesheet :as gs] [slopp.ui.model :as model] [clojure.string :as str]))

(defn- form-doc
  "A form's docstring, read off the stored node's sexpr (nil when absent)."
  [e]
  (try
    (let [sx (n/sexpr (:node e))]
      (when (seq? sx)
        (let [d (nth sx 2 nil)]
          (when (string? d) d))))
    (catch Exception _ nil)))

(defn ^{:web/read :browse/namespaces} namespaces-read
  "Read performer: `{:ns sym :forms n}` rows for every namespace, sorted."
  [{:keys [session]} _]
  (let [st (:store @session)]
    (mapv (fn [nsx] {:ns nsx :forms (count (filter :name (store/forms st nsx)))})
          (sort (keys (:namespaces st))))))

(defn ^{:web/read :browse/ns-outline} ns-outline-read
  "Read performer: one namespace's `{:name :doc}` form rows in store order,
  or nil for an unknown namespace."
  [{:keys [session]} nsx]
  (let [st  (:store @session)
        sym (symbol (str nsx))]
    (when (contains? (:namespaces st) sym)
      {:ns sym
       :forms (into []
                    (keep (fn [e]
                            (when (:name e)
                              {:name (:name e) :doc (form-doc e)})))
                    (store/forms st sym))})))

(defn ^{:web/read :browse/form-source} form-source-read
  "Read performer: one form's source text, or nil when the form is unknown."
  [{:keys [session]} {:keys [ns name]}]
  (let [st (:store @session)]
    (when-let [e (store/form-named st (symbol (str ns)) (symbol (str name)))]
      (n/string (:node e)))))

(defn- document
  "The application document: head, an empty mount point, nothing else.

  This is the ONLY HTML the server produces. Every screen is the client
  rendering data from `/api/*`, so there is no per-page template here and no
  second renderer that could disagree with the browser's.

  It is deliberately EMPTY rather than server-rendered-then-hydrated. A
  pre-rendered shell would be a second implementation of every screen — the
  exact drift the `:cljc` views exist to prevent — and slopp's reviewer UI
  is a local tool, so first-paint latency is not the constraint that would
  justify it."
  []
  (html/html-response
   (html/page
    {:html/title "slopp"
     :html/head [[:link {:rel "stylesheet" :href "/css/style.css"}]
                 [:script {:src "/js/main.js" :defer true}]]}
    [:div {:id "app"}])))

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

(defn ^{:web/read :ui/client-js} client-js-read
  "Read performer: the compiled client bundle from the files manifest, where
  `compile_client` put it — or nil when nothing has been compiled."
  [{:keys [session]} _]
  ;; file-content returns the polymorphic ENTRY ({:content …}, plus
  ;; :content-type for a binary), never the bare string — one accessor so no
  ;; consumer branches on the shape. Take the content.
  (:content (store/file-content (:store @session) "public/cljs/main.js")))

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
  [_req]
  (document))

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
  [_req]
  (document))

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
