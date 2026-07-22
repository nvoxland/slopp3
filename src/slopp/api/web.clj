(ns slopp.api.web
  (:require [slopp.api.capabilities :as capabilities]
            [slopp.edit.modules :as modules] [slopp.web.router :as router] [slopp.store :as store] [slopp.render :as render] [clojure.string :as str] [rewrite-clj.node :as n]))

(defn endpoints
  "Every declared endpoint in the store — a `:web/path` form's route row:
  `{:handler :ns :name :form-id :method :path :auth :web/effects :web/reads
  :schema? :effectful?}` (slopp's own vocabulary keys stay namespaced —
  the same rule the request envelope follows). Built on the SAME traversal
  the write gates check (`modules/web-endpoint-rows`), so what query_routes
  shows is what the gates enforced. A pure function of the store value."
  [store]
  (mapv (fn [{:keys [ns name form-id meta]}]
          {:handler   (symbol (str ns) (str name))
           :ns        ns
           :name      name
           :form-id   form-id
           :method    (:web/method meta)
           :path      (str (:web/path meta))
           :auth      (:web/auth meta)
           :web/effects (:web/effects meta)
           :web/reads   (:web/reads meta)
           :schema?   (contains? meta :malli/schema)
           :effectful? (boolean (:web/effectful meta))})
        (modules/web-endpoint-rows store)))

(defn performers
  "The app-defined performer vocabulary for `marker-key` (`:web/effect` or
  `:web/read`): {kind → performer qsym}. Delegates to the SAME derivation
  the undeclared-effect gate checks (`modules/web-performers`)."
  [store marker-key]
  (modules/web-performers store marker-key))

(defn- link-refs
  "Route references in one form's SEXPR: literal attr maps carrying :href
  or :action. Root-relative string values are :exact; (str \"/lit\" …) with
  a root-relative literal first arg is :prefix; other dynamic values are
  :unresolved. Absolute URLs (scheme or //), anchors, and non-root-relative
  strings are not route references at all. :action takes its method from
  the same map's :method attr (default :get); :href is always :get."
  [sexpr]
  (for [m (filter map? (tree-seq coll? seq sexpr))
        attr [:href :action]
        :when (contains? m attr)
        :let [v (get m attr)
              method (if (= :action attr)
                       (let [mv (get m :method "get")]
                         (keyword (str/lower-case (if (keyword? mv) (name mv) (str mv)))))
                       :get)
              ref (cond
                    (string? v)
                    (when (and (str/starts-with? v "/")
                               (not (str/starts-with? v "//")))
                      {:kind :exact :path v})

                    (and (seq? v) (= 'str (first v)) (string? (second v))
                         (str/starts-with? (second v) "/")
                         (not (str/starts-with? (second v) "//")))
                    {:kind :prefix :path (second v)}

                    (nil? v) nil

                    :else {:kind :unresolved :value (pr-str v)})]
        :when ref]
    (assoc ref :attr attr :method method)))

(defn ui-route-refs
  "Every route REFERENCE the store's forms render: literal :href/:action
  attrs classified :exact / :prefix / :unresolved, each row carrying the
  qualified :form. A pure function of the forms (the keyword-inventory
  property) — correct on every branch, after every merge, at any revision.
  Test namespaces are fixtures; a form marked ^{:web/external-path \"why\"}
  declares its targets served elsewhere and is skipped whole."
  [store]
  (vec
   (for [nsx (sort (keys (:namespaces store)))
         :when (not (render/test-ns? nsx))
         e (store/forms store nsx)
         :when (:name e)
         :let [sx (try (n/sexpr (:node e)) (catch Exception _ nil))]
         :when (and sx
                    (not (and (seq? sx)
                              (:web/external-path (meta (second sx))))))
         ref (link-refs sx)]
     (assoc ref :form (symbol (str nsx) (str (:name e)))))))

(defn routes-report
  "The `query_routes` payload. `http.enabled` false → `{:enabled false
  :routes [] :note …}` — a store that never opted into HTTP has no web
  surface and no web rules (the adoption story). Enabled → every endpoint
  row (`endpoints`), each carrying `:rendered-by` (the forms whose
  `ui-route-refs` target it — exact refs through the router's matcher,
  prefix refs through the path pattern) when any do, plus the derived
  performer vocabularies (`:effect-kinds` / `:read-kinds`)."
  [store]
  (if-not (capabilities/effective store "http.enabled")
    {:enabled false :routes []
     :note (str "http.enabled is false — config_file {path \"capabilities\" "
                "key \"http.enabled\" value \"true\"} opts this store into HTTP")}
    (let [refs    (ui-route-refs store)
          renders (fn [row]
                    (->> refs
                         (filter (fn [{:keys [kind method path]}]
                                   (case kind
                                     :exact  (some? (router/match [row] method path))
                                     :prefix (str/starts-with? (str (:path row)) path)
                                     false)))
                         (map :form) distinct sort vec not-empty))]
      {:enabled true
       :routes (mapv #(if-let [r (renders %)] (assoc % :rendered-by r) %)
                     (endpoints store))
       :effect-kinds (set (keys (performers store :web/effect)))
       :read-kinds (set (keys (performers store :web/read)))})))

(defn dangling-route-refs
  "`ui-route-refs` joined against what the store actually serves: declared
  endpoints (through the router's matcher, so parameterized paths match),
  `http.static.*` mounts (an :exact path must map to a file that EXISTS on
  the manifest), and route/mount prefixes for :prefix refs. Returns
  `{:dangling [ref …] :unresolved [ref …]}` — dynamic refs are NAMED, never
  counted clean."
  [store]
  (let [refs   (ui-route-refs store)
        routes (endpoints store)
        mounts (into {}
                     (keep (fn [[k v]]
                             (when-let [[_ m] (re-matches #"http\.static\.(.+)" (str k))]
                               [m (str v)])))
                     (get-in store [:config "capabilities" :values]))
        static-file? (fn [path]
                       (some (fn [[url-prefix file-prefix]]
                               (and (str/starts-with? path (str url-prefix "/"))
                                    (some? (store/file-content
                                            store
                                            (str file-prefix "/"
                                                 (subs path (inc (count url-prefix))))))))
                             mounts))
        served? (fn [{:keys [kind method path]}]
                  (case kind
                    :exact  (boolean (or (router/match routes method path)
                                         (static-file? path)))
                    :prefix (boolean
                             (or (some #(str/starts-with? (str (:path %)) path) routes)
                                 (some (fn [[url-prefix _]]
                                         (str/starts-with? path (str url-prefix "/")))
                                       mounts)))
                    false))]
    {:dangling   (vec (remove served? (remove #(= :unresolved (:kind %)) refs)))
     :unresolved (filterv #(= :unresolved (:kind %)) refs)}))
