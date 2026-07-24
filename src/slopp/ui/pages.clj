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

(defn ^{:web/method :get :web/path "/store/style.css" :web/auth :public :web/response :string :web/client false}
  store-stylesheet
  "GET /store/style.css — the browser's own styling as garden data (CSS as
  Clojure data, tracked like every other form). A safe GET; served text/css."
  [_req]
  (css/css-response
   [[:body {:font-family "system-ui, sans-serif" :line-height 1.5
            :max-width "50rem" :margin "2rem auto" :padding "0 1rem"}]
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
                 [:pre {:background "#1c1c1c"}]
                 [:a {:color "#5c9"}]
                 [:small {:color "#999"}]
                 [:nav {:color "#999"}]
                 [:.del {:color "#e88" :background "#2a1a1a"}]
                 [:.add {:color "#6cb" :background "#14241f"}]
                 [:article {:border-left-color "#333"}]
                 [:footer {:border-top-color "#333"}])]))

(defn- shell
  "html/page with the store-browser stylesheet linked — the one place the
  link literal lives, so every page joins /store/style.css. The compiled
  client bundle (slopp.client.nsview → /assets/cljs/main.js) rides here too;
  it self-starts and no-ops on pages without a #ns-filter box."
  [title & body]
  (apply html/page
         {:html/title title
          :html/head [[:link {:rel "stylesheet" :href "/store/style.css"}]
                      [:script {:src "/assets/cljs/main.js" :defer true}]]}
         body))

(defn ^{:web/method :get :web/path "/store" :web/auth :public :web/response :string :web/client false
        :web/reads {:namespaces [:browse/namespaces []]}}
  store-index-page
  "GET /store — the namespace index. `:public` deliberately: the co-hosted
  /call and /mcp endpoints on this server already expose strictly more
  than read-only source.

  Progressive enhancement: the compiled client filter (slopp.client.nsview,
  served at /assets/cljs/main.js) wires the #ns-filter box to the .ns-row
  items; with JS off the full list still renders server-side."
  [req]
  (html/html-response
   (shell "store"
     [:main
      [:h1 "namespaces"]
      [:input {:id "ns-filter" :type "search" :autocomplete "off"
               :placeholder "filter namespaces…" :aria-label "filter namespaces"}]
      [:ul {:id "ns-list"}
       (for [{:keys [ns forms]} (:namespaces (:web/reads req))]
         [:li {:class "ns-row"}
          [:a {:href (str "/store/ns/" ns)} (str ns)]
          (str " (" forms ")")])]])))

(defn ^{:web/method :get :web/path "/store/ns/:ns" :web/auth :public :web/response :string :web/client false
        :web/reads {:outline [:browse/ns-outline [:path-params :ns]]}}
  store-ns-page
  "GET /store/ns/:ns — one namespace's forms, each name linking its source."
  [req]
  (let [{:keys [ns forms] :as outline} (:outline (:web/reads req))]
    (if-not outline
      {:status 404 :body {:error "no such namespace"}}
      (html/html-response
       (shell (str ns)
         [:main
          [:p [:a {:href "/store"} "← all namespaces"]]
          [:h1 (str ns)]
          [:ul
           (for [{:keys [name doc]} forms]
             [:li [:a {:href (str "/store/source/" ns "/" name)} (str name)]
              (when doc [:small (str " — " doc)])])]])))))

(defn ^{:web/method :get :web/path "/store/source/:ns/:name" :web/auth :public :web/response :string :web/client false
        :web/reads {:source [:browse/form-source [:path-params]]}}
  store-source-page
  "GET /store/source/:ns/:name — one form's source in [:pre [:code]],
  through the escaper (arbitrary store text rendered safely IS the
  standing security dogfood)."
  [req]
  (let [{:keys [ns name]} (:path-params req)
        src (:source (:web/reads req))]
    (if-not src
      {:status 404 :body {:error "no such form"}}
      (html/html-response
       (shell (str ns "/" name)
         [:main
          [:p [:a {:href (str "/store/ns/" ns)} "← back"]]
          [:pre [:code src]]])))))

(defn ^{:web/read :ui/timeline} timeline-read
  "Read performer: the reviewer landing model — milestones plus the
  working set."
  [{:keys [session]} _]
  (model/timeline session))

(defn- plural
  "`n` with `word`, pluralised by adding an s. Enough for the counts these
  pages state, and it keeps \"1 forms\" — which reads as a bug in the data
  rather than in the sentence — out of the review."
  [n word]
  (str n " " word (when (not= 1 n) "s")))

(defn ^{:web/method :get :web/path "/" :web/auth :public :web/response :string :web/client false
        :web/reads {:timeline [:ui/timeline []]}}
  landing-page
  "GET / — the reviewer's front door. Two questions in the order they get
  asked: what is in flight, and what has been finished. Every milestone
  row links its own change screen through the range the MODEL computed, so
  this stays a template."
  [req]
  (let [{:keys [milestones working]} (:timeline (:web/reads req))]
    (html/html-response
     (shell "review"
       [:main
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
            [:ul (for [p (:prompts working)] [:li p])]
            (when-let [n (:more-prompts working)]
              [:p [:small (str "… and " (plural n "more ask"))]])])]
        [:section
         [:h2 "milestones"]
         [:ol
          (for [m milestones]
            [:li
             (if (:range m)
               [:a {:href (str "/change/" (:range m))} (:description m)]
               [:span (:description m)])
             " "
             [:small (:at m)
              (when-let [n (:more-lines m)]
                (str " · " (plural n "more line")))]])]]
        [:p [:a {:href "/store"} "browse namespaces →"]]]))))

(defn ^{:web/read :ui/change} change-read
  "Read performer: the review of one `from..to` range, or nil when the
  range is malformed or names deltas that do not exist — the page needs
  those to be the same answer, since both are a 404."
  [{:keys [session]} range-str]
  (let [[from to] (str/split (str range-str) #"\.\." 2)]
    (when (and (seq from) (seq to))
      (model/change-view session from to))))

(defn ^{:web/method :get :web/path "/change/:range" :web/auth :public
        :web/response :string :web/client false
        :web/reads {:change [:ui/change [:path-params :range]]}}
  change-page
  "GET /change/<from>..<to> — the review of one milestone. Module →
  namespace → form, and per form: the recorded ASK first (a reviewer reads
  intent before code), then the line diff, then the blast radius.

  The whole range is one path segment because a reviewer copies it as one
  thing; the read performer splits it."
  [req]
  (if-let [{:keys [from to count modules]} (:change (:web/reads req))]
    (html/html-response
     (shell (str from ".." to)
       [:main
        [:p [:a {:href "/"} "← review"]]
        [:h1 (str from ".." to)]
        [:p (plural count "form")]
        (for [m modules]
          [:section
           [:h2 (:module m) " " [:small (plural (:count m) "form")]]
           (for [n (:namespaces m)]
             [:div
              [:h3 (:ns n)]
              (for [f (:forms n)]
                [:article
                 [:h4 [:a {:href (str "/store/form/" (:form-id f))} (:form f)]
                  " " [:small (name (:status f))]]
                 (when (:why f) [:p [:em (:why f)]])
                 [:pre
                  [:code
                   (for [[op line] (:diff f)]
                     [:span {:class (name op)}
                      (str (case op :add "+" :del "-" " ") line "\n")])]]
                 [:small (plural (:callers f) "caller")]])])])
        [:footer
         [:small "call edges come from a syntactic reader over the store, so"
          " caller counts are a floor, not a census"]]]))
    {:status 404 :body {:error "no such range"}}))

(defn ^{:web/read :ui/form} form-view-read
  "Read performer: one form's page model by ID, or nil when no form has
  that id."
  [{:keys [session]} id]
  (model/form-view session (str id)))

(defn ^{:web/method :get :web/path "/store/form/:id" :web/auth :public
        :web/response :string :web/client false
        :web/reads {:view [:ui/form [:path-params :id]]}}
  form-page
  "GET /store/form/<id> — one form, by ID: names change and ids do not, so
  the id is the permalink.

  Laid out for a COLD arrival from a link. Breadcrumb first (where am I),
  callers ABOVE (who depends on this — the thing you need before you judge
  a change), the form itself, then callees BELOW with each one's signature
  and doc INLINED. The inlining is the point: a link is not visibility, and
  the reason to open this page is usually to read the form WITH what it
  calls, not instead of it."
  [req]
  (if-let [{:keys [form ns module source sig doc why warranty
                   callers callees note]} (:view (:web/reads req))]
    (html/html-response
     (shell form
       [:main
        [:nav [:a {:href "/store"} "store"] " / " module " / "
         [:a {:href (str "/store/ns/" ns)} ns]]
        [:h1 form]
        (when sig [:p [:code sig]])
        (when doc [:p doc])
        (when why [:p [:em why]])
        [:p [:small (plural (:covered warranty) "covering test")]]
        [:section
         [:h2 "callers"]
         (if (empty? callers)
           [:p [:small "nothing in this store calls it"]]
           (for [g callers]
             [:div
              [:h3 (name (:via g)) " " [:small (plural (:count g) "form")]]
              [:ul
               (for [c (:forms g)]
                 [:li (if (:form-id c)
                        [:a {:href (str "/store/form/" (:form-id c))} (:form c)]
                        [:span (:form c)])
                  " " [:small (:module c)]])]]))]
        [:pre [:code source]]
        [:section
         [:h2 "callees"]
         (if (empty? callees)
           [:p [:small "it calls nothing else in this store"]]
           (for [c callees]
             [:article
              [:h3 (if (:form-id c)
                     [:a {:href (str "/store/form/" (:form-id c))} (:form c)]
                     [:span (:form c)])
               " " [:small (:module c)]]
              (when (:sig c) [:p [:code (:sig c)]])
              (when (:doc c) [:p (:doc c)])]))]
        [:footer [:small note]]]))
    {:status 404 :body {:error "no such form"}}))
