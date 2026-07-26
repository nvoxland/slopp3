(ns slopp.ui.views
  (:require [clojure.string :as str] [slopp.ui.nsfilter :as nsfilter]))

(defn find-region
  "The hiccup subtree for pane `role` (`:nav/sections`, `:nav/local`,
  `:nav/detail`, `:main`), or nil when the shell omitted it.

  Panes carry `:data-region` and are found by it, so a caller — a test above
  all — addresses a pane by what it IS rather than by where it sits. Reaching
  into `[2 1 0]` breaks on every layout change and reports nothing about what
  actually broke."
  [hiccup role]
  (first (for [x (tree-seq coll? seq hiccup)
               :when (and (vector? x)
                          (map? (second x))
                          ;; (symbol …), not (name …) — name DROPS the namespace, so :nav/sections
                          ;; asked for "sections" while the markup wrote "nav/sections"
                          (= (str (symbol role)) (:data-region (second x))))]
           x)))

(def sections
  "The application's global sections, in bar order — ONE table, so every page
  shows the same navigation and agrees about which section it is in.

  `:prefix` is what `current-section` matches a path against. Each `:href` is
  a literal that `web-dangling-route-refs` joins against the served routes, so
  a section pointing at a path nothing serves fails `done` rather than 404ing
  in someone's browser."
  [{:label "Review"       :href "/"      :prefix ["/" "/change"]}
   {:label "Code"         :href "/store" :prefix ["/store"]}])

(defn current-section
  "The section `path` belongs to, or nil.

  Longest prefix wins, so `/store` beats the root. `\"/\"` matches only the
  root exactly — as a prefix it would match everything and make the first
  section permanently current.

  **Nil rather than a guess.** Marking a section active on a page that is not
  in it tells the reader something false, which is worse than telling them
  nothing."
  [path]
  (->> sections
       (keep (fn [s]
               (when-let [hit (->> (:prefix s)
                                   (filter (fn [p]
                                             (if (= "/" p)
                                               (= "/" path)
                                               (or (= p path)
                                                   (str/starts-with? path (str p "/"))))))
                                   (sort-by count)
                                   last)]
                 [(count hit) s])))
       (sort-by first)
       last
       second))

(defn marked-sections
  "`sections` with `:active?` on the one `path` is in — the shape `nav-links`
  wants, and the only thing a page should ever pass as `:nav/sections`. A page
  that assembles its own is how two pages come to disagree about where you
  are."
  [path]
  (let [cur (current-section path)]
    (mapv #(cond-> % (= (:label cur) (:label %)) (assoc :active? true))
          sections)))

(defn nav-links
  "A `<ul>` of links from `items` (`{:label :href :active?}`), or nil when
  there are none — so a caller can splice the result straight in and get
  nothing when there is nothing to show.

  The active item carries `class=\"active\"`. Every href is a LITERAL in the
  returned data, which is what keeps `web-dangling-route-refs` able to see
  navigation at all: it joins literal `:href`s against the served routes, and
  it caught a real shipped 404 doing exactly that."
  [items]
  (when (seq items)
    (into [:ul]
          (for [{:keys [label href active?]} items]
            [:li [:a (cond-> {:href href} active? (assoc :class "active"))
                  label]]))))

(defn ns-nav
  "The Code section's left PANE — the filter box and the namespace list,
  with `current` marked and only rows matching `needle` rendered.

  Filtering by NOT RENDERING, rather than by setting `display:none` on rows
  the server already sent. That is the one place the SPA is straightforwardly
  simpler: the filter becomes a pure function of state, checkable in-image,
  instead of a DOM walk that a re-render would undo.

  It also has to be this way. Re-rendering the app on navigation would
  discard both the typed text and any hidden-row state, so the needle lives
  in application state and comes back in — which is why the input carries
  `:value`.

  `#ns-filter`, `#ns-list` and `.ns-row` are kept as hooks: they are what the
  tests address, and dropping them once already broke the filter silently."
  ([namespaces current] (ns-nav namespaces current nil))
  ([namespaces current needle]
   [:div
    [:input {:id "ns-filter" :type "search" :autocomplete "off"
             :value (or needle "")
             :placeholder "filter namespaces…" :aria-label "filter namespaces"}]
    (into [:ul {:id "ns-list"}]
          (for [{:keys [ns]} namespaces
                :when (nsfilter/matches? needle (str ns))]
            [:li {:class "ns-row"}
             [:a (cond-> {:href (str "/store/ns/" ns)}
                   (= (str ns) (str current)) (assoc :class "active"))
              (str ns)]]))]))

(defn plural
  "`n` with `word`, pluralised by adding an s. Enough for the counts these
  screens state — \"1 forms\" is the kind of thing that reads as a bug in the
  data rather than a slip in the copy."
  [n word]
  (str n " " word (when (not= 1 n) "s")))

(defn token-code
  "A `[[class text] …]` token stream as hiccup.

  Whitespace and unclassified text are emitted BARE — a span per character
  run of ordinary code would triple the markup for no colour — so only what
  is actually distinguished carries an element.

  This is where the SPA line falls: the server walks the CST it already has
  and sends PAIRS, and the decision that a `\"keyword\"` token is a
  `[:span {:class \"keyword\"}]` is made here. No lexer ships to the browser
  and no markup ships from the server."
  [tokens]
  [:pre [:code
         (for [[cls text] tokens]
           (if (#{"ws" "text"} cls) text [:span {:class cls} text]))]])

(defn route-for
  "Parse an in-app `path` into `{:screen :params}`, or nil.

  With server-rendered pages gone this IS the application's routing table,
  which is why it is a pure `:cljc` function: it is checkable by ordinary
  in-image tests instead of only in a browser, where a mis-parse shows up
  as a blank screen.

  **An unknown path is nil, never a default screen.** Falling back to the
  timeline would tell the reader they are somewhere they are not — the SPA
  version of answering 200 for a page that does not exist. The caller turns
  nil into a not-found screen, and the SERVER still 404s paths outside the
  declared `:web/spa` prefixes, so a typo fails at whichever layer sees it
  first."
  [path]
  (let [trimmed (if (and (> (count path) 1) (str/ends-with? path "/"))
                  (subs path 0 (dec (count path)))
                  path)
        segs    (vec (remove str/blank? (str/split trimmed #"/")))]
    (case segs
      []        {:screen :timeline :params {}}
      ["store"] {:screen :code :params {}}
      (cond
        (and (= 2 (count segs)) (= "change" (first segs)))
        {:screen :change :params {:range (segs 1)}}

        (and (= 3 (count segs)) (= ["store" "ns"] (subvec segs 0 2)))
        {:screen :ns :params {:ns (segs 2)}}

        (and (= 3 (count segs)) (= ["store" "form"] (subvec segs 0 2)))
        {:screen :form :params {:id (segs 2)}}

        (and (= 4 (count segs)) (= ["store" "source"] (subvec segs 0 2)))
        {:screen :source :params {:ns (segs 2) :name (segs 3)}}

        :else nil))))

(defn app-shell
  "The three-pane application shell: global sections across the top,
  section-local navigation on the left, in-page detail on the right, content
  in the middle. Pure — data in, hiccup DATA out.

  `:nav/sections` is link DATA (`marked-sections`), because global navigation
  is the one thing that must be identical on every page. `:nav/local` and
  `:nav/detail` are HICCUP, because a pane is a pane: the Code section's left
  carries a filter box as well as links, and a form's right rail carries
  callers and covering tests as content. Forcing them through a link list is
  what dropped the filter box and silently broke it.

  **An omitted pane is ABSENT, not empty.** A form page has no section-local
  navigation; rendering an empty box would take layout space and say nothing.
  The grid collapses to whichever panes exist.

  Lives in `slopp.ui`, deliberately NOT in `slopp.web`: three panes is this
  application's design, and a framework carrying an opinion about navigation
  has stopped being a framework."
  [{:nav/keys [sections local detail]} & content]
  (cond-> [:div {:class "app"}
           [:header {:data-region "nav/sections"} (nav-links sections)]]
    (some? local)  (conj [:nav {:data-region "nav/local"} local])
    true           (conj (into [:main {:data-region "main"}] content))
    (seq detail)   (conj (into [:aside {:data-region "nav/detail"}] detail))))

(defn ns-outline-main
  "The Code section's MAIN pane for one namespace: its name and its forms,
  each linking its source.

  Takes the WIRE shape — `{:ns \"…\" :forms [{:name \"…\" :doc nil-or-string}]}`,
  exactly what `GET /api/ns/:ns` returns — rather than the store's shape.
  That is the whole point of it being one function: the server renders it
  into the page, and the client renders the SAME function into the same pane
  after a fetch, so a click and a refresh cannot show different things.

  If it took the store's shape (symbols, store-side keys) the server would
  render correctly and the client would render nils, and only a browser
  would ever tell you."
  [{:keys [ns forms]}]
  [:div
   [:h1 (str ns)]
   (into [:ul]
         (for [{:keys [name doc]} forms]
           [:li [:a {:href (str "/store/source/" ns "/" name)} (str name)]
            ;; no empty <small> when there is no doc: a blank element is a
            ;; visible gap that says a docstring exists and is empty
            (when doc [:small (str " — " doc)])]))])

(defn timeline-main
  "The Review section's main pane: what is in flight, then what has been
  finished. Takes `GET /api/timeline`'s shape.

  Every milestone row links its own change screen through the range the
  MODEL computed, so this stays a template — the oldest milestone has no
  range and renders as plain text rather than a link to nowhere."
  [{:keys [milestones working]}]
  [:div
   [:h1 "review"]
   [:section
    [:h2 "in flight"]
    (if (zero? (:forms working))
      [:p "nothing since " [:code (str (:since working))]
       " — the working set is clean"]
      [:div
       [:p (str (plural (:forms working) "form") " in "
                (plural (count (:namespaces working)) "namespace")
                " since " (:since working))]
       (into [:ul] (for [p (:prompts working)] [:li p]))
       (when-let [n (:more-prompts working)]
         [:p [:small (str "… and " (plural n "more ask"))]])])]
   [:section
    [:h2 "milestones"]
    (into [:ol]
          (for [m milestones]
            [:li
             (if (:range m)
               [:a {:href (str "/change/" (:range m))} (:description m)]
               [:span (:description m)])
             " "
             [:small (str (:at m))
              (when-let [n (:more-lines m)]
                (str " · " (plural n "more line")))]]))]])

(defn change-main
  "The Review section's main pane for one milestone. Takes
  `GET /api/change/:range`'s shape.

  The diff arrives as LINES and the leading `-`/`+` classifies each one —
  the server sends what changed, the client decides that a minus line is a
  `.del`. Same discipline as [[token-code]], and the reason neither screen
  needs the server to render markup.

  Classified with `starts-with?` rather than by comparing the first
  character: `(first \"line\")` is a CHAR on the JVM and a one-character
  STRING in ClojureScript, so a char comparison is a `:cljc` trap that
  passes every in-image test and does nothing in a browser."
  [{:keys [from to modules] total :count}]
  [:div
   [:h1 (str from ".." to)]
   [:p (plural total "form")]
   (into [:div]
         (for [m modules]
           (into [:section {:id (:module m)}
                  [:h2 (:module m) " " [:small (plural (:count m) "form")]]]
                 (for [n (:namespaces m)]
                   (into [:div [:h3 (:ns n)]]
                         (for [f (:forms n)]
                           [:article
                            [:h4 [:a {:href (str "/store/form/" (:form-id f))}
                                  (:form f)]]
                            (when (:why f) [:p [:em (:why f)]])
                            [:pre
                             (into [:code]
                                   (for [line (:diff f)]
                                     [:span {:class (cond
                                                      (str/starts-with? line "-") "del"
                                                      (str/starts-with? line "+") "add"
                                                      :else nil)}
                                      line "\n"]))]
                            [:p [:small (plural (:callers f) "caller")]]])))))) ])

(defn form-main
  "The Code section's main pane for one form. Takes `GET /api/form/:id`'s
  shape.

  Laid out for a COLD arrival from a link: the breadcrumb says where this
  is, then the signature, the doc, and the source."
  [{:keys [form ns module tokens sig doc]}]
  [:div
   [:nav [:a {:href "/store"} "code"] " / " module " / "
    [:a {:href (str "/store/ns/" ns)} ns]]
   [:h1 form]
   (when sig [:p [:code sig]])
   (when doc [:p doc])
   (token-code tokens)])

(defn form-rail
  "The Code section's right RAIL for one form: what you consult while
  reading it — the recorded ask, the warranty, who calls it, and what it
  calls with each signature and doc INLINED.

  Returns a VECTOR of sections, which is what `app-shell` splices into the
  rail.

  The inlining is the point and it is why the rail exists: a link is not
  visibility, and the reason to open this screen is almost always to read
  the form WITH the things it reaches. Stacking callers above and callees
  below meant scrolling away from the form to see either.

  `:via` arrives as a STRING — it is a keyword in the store and JSON has no
  keywords, so this renders it directly rather than calling `name` on it."
  [{:keys [why warranty callers callees note]}]
  [[:section
    [:h3 "warranty"]
    [:p [:small (plural (:covered warranty) "covering test")]]
    (when why [:p [:em why]])]
   (into [:section [:h3 "callers"]]
         (if (empty? callers)
           [[:p [:small "nothing in this store calls it"]]]
           (for [g callers]
             [:div
              [:h4 (str (:via g)) " " [:small (plural (:count g) "form")]]
              (into [:ul]
                    (for [c (:forms g)]
                      [:li (if (:form-id c)
                             [:a {:href (str "/store/form/" (:form-id c))} (:form c)]
                             [:span (:form c)])
                       " " [:small (:module c)]]))])))
   (into [:section [:h3 "callees"]]
         (if (empty? callees)
           [[:p [:small "it calls nothing else in this store"]]]
           (for [c callees]
             [:article
              [:h4 (if (:form-id c)
                     [:a {:href (str "/store/form/" (:form-id c))} (:form c)]
                     [:span (:form c)])
               " " [:small (:module c)]]
              (when (:sig c) [:p [:code (:sig c)]])
              (when (:doc c) [:p (:doc c)])])))
   [:footer [:small note]]])

(defn source-main
  "The Code section's main pane for one form addressed by NAME. Takes
  `GET /api/source/:ns/:name`'s shape.

  The source arrives as a string and is a text node here, which is what
  escapes it. Serving arbitrary store source safely is the standing security
  dogfood, and moving the render into the browser does not retire it — it
  concentrates it in the one place that must never build markup by
  concatenation."
  [{:keys [ns name source]}]
  [:div
   [:nav [:a {:href "/store"} "code"] " / "
    [:a {:href (str "/store/ns/" ns)} ns]]
   [:h1 name]
   [:pre [:code source]]])

(defn code-index-main
  "The Code section's main pane before a namespace is chosen. Takes
  `GET /api/namespaces`'s shape.

  Deliberately thin: the namespace list is the LEFT PANE, because it is
  navigation that persists as you move between namespaces. Repeating it in
  the middle would make the same list appear twice on the same screen."
  [namespaces]
  [:div
   [:h1 "code"]
   [:p (str (plural (count namespaces) "namespace") ", "
            (plural (reduce + 0 (map :forms namespaces)) "form")
            " — pick one on the left.")]])

(defn app-view
  "The WHOLE page as hiccup, from application state. Pure, `:cljc`, and the
  only thing the browser glue calls.

  `state` is `{:path :screen :params :data :namespaces :error}`. Keeping the
  entire render in one pure function is what makes an SPA testable at all:
  every screen — including LOADING and NOT-FOUND, the two that only exist
  once the server stops rendering — is an ordinary in-image assertion on
  hiccup data rather than something you can only see in a browser.

  `:data` nil means the fetch is still in flight. That state has to render
  something, because the alternative is a blank pane that looks exactly like
  a screen with no content."
  [{:keys [path screen params data namespaces error] filter-text :filter}]
  (let [code?  (#{:code :ns :source} screen)
        local  (when code? (ns-nav (or namespaces []) (:ns params) filter-text))
        main   (cond
                 error          [:div [:h1 "error"] [:p (str error)]]
                 (nil? screen)  [:div [:h1 "not found"]
                                 [:p "no screen answers " [:code (str path)]]]
                 (nil? data)    [:div [:p [:small "loading…"]]]
                 :else (case screen
                         :timeline (timeline-main data)
                         :change   (change-main data)
                         :code     (code-index-main data)
                         :ns       (ns-outline-main data)
                         :source   (source-main data)
                         :form     (form-main data)))
        detail (when (and (= :form screen) data (nil? error))
                 (form-rail data))]
    (app-shell (cond-> {:nav/sections (marked-sections (or path "/"))}
                 local  (assoc :nav/local local)
                 detail (assoc :nav/detail detail))
               main)))
