(ns slopp.http.browse
  "Read-only store browser: server-rendered hiccup pages over the query
  surfaces — the D-web-html dogfood. Plain links, full-page renders, zero
  writes; rendering arbitrary store source through the escaper is a
  standing security exercise. Lives in slopp.http (the server module), NOT
  slopp.web.**, so it never rides the slim user jar."
  (:require [rewrite-clj.node :as n]
            [slopp.store :as store]
            [slopp.web.html :as html] [slopp.web.css :as css] [garden.stylesheet :as gs]))

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

(defn ^{:web/method :get :web/path "/store/style.css" :web/auth :public}
  store-stylesheet
  "GET /store/style.css — the browser's own styling as garden data (CSS as
  Clojure data, tracked like every other form). A safe GET; served text/css."
  [_req]
  (css/css-response
   [[:body {:font-family "system-ui, sans-serif" :line-height 1.5
            :max-width "50rem" :margin "2rem auto" :padding "0 1rem"}]
    [:h1 {:font-size "1.4rem"}]
    [:a {:color "#2a6"}]
    [:small {:color "#777"}]
    [:pre {:background "#f4f4f4" :padding "1rem" :border-radius "4px"
           :overflow-x "auto"}]
    [:code {:font-family "ui-monospace, monospace"}]
    (gs/at-media {:prefers-color-scheme :dark}
                 [:body {:background "#111" :color "#ddd"}]
                 [:pre {:background "#1c1c1c"}]
                 [:a {:color "#5c9"}]
                 [:small {:color "#999"}])]))

(defn- shell
  "html/page with the store-browser stylesheet linked — the one place the
  link literal lives, so every page joins /store/style.css."
  [title & body]
  (apply html/page
         {:html/title title
          :html/head [[:link {:rel "stylesheet" :href "/store/style.css"}]]}
         body))

(defn ^{:web/method :get :web/path "/store" :web/auth :public
        :web/reads {:namespaces [:browse/namespaces []]}}
  store-index-page
  "GET /store — the namespace index. `:public` deliberately: the co-hosted
  /call and /mcp endpoints on this server already expose strictly more
  than read-only source."
  [req]
  (html/html-response
   (shell "store"
     [:main
      [:h1 "namespaces"]
      [:ul
       (for [{:keys [ns forms]} (:namespaces (:web/reads req))]
         [:li [:a {:href (str "/store/ns/" ns)} (str ns)]
          (str " (" forms ")")])]])))

(defn ^{:web/method :get :web/path "/store/ns/:ns" :web/auth :public
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

(defn ^{:web/method :get :web/path "/store/source/:ns/:name" :web/auth :public
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
