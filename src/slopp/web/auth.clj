(ns slopp.web.auth
  (:require [clojure.string :as str] [clojure.edn :as edn]))

(defn sha256-hex
  "SHA-256 of `s` as lowercase hex — the v1 password hash for the static
  provider (JDK-only; swap for bcrypt/argon2 when a store's threat model
  demands it — the config carries a hash either way)."
  [s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                   (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(defn- secret-value
  "Resolve a configured secret: \"env:NAME\" reads through `getenv` (a map
  or fn — the seam that keeps resolution testable and the config free of
  literals); anything else is the value itself."
  [v getenv]
  (let [v (str v)]
    (if (str/starts-with? v "env:")
      (some-> (getenv (subs v 4)) str)
      v)))

(defn- augment-groups
  "The identity's groups plus every configured group (`:auth/groups`,
  {group-name #{subs}}) that lists the subject."
  [{:web/keys [sub] :as identity} groups-config]
  (update identity :web/groups
          (fnil into #{})
          (for [[g members] groups-config
                :when (contains? (set members) sub)]
            g)))

(defn- bearer-identity
  "Authorization: Bearer <token> against `:auth/bearer` ({name {:secret
  :groups}}), secrets env-indirect. nil = no claim."
  [config req getenv]
  (let [h (get-in req [:headers "authorization"] "")]
    (when (str/starts-with? h "Bearer ")
      (let [tok (subs h 7)]
        (some (fn [[nm {:keys [secret groups]}]]
                (when (and (seq tok) (= tok (secret-value secret getenv)))
                  {:web/sub nm :web/groups (set groups)
                   :web/provider :bearer}))
              (:auth/bearer config))))))

(defn- static-identity
  "Authorization: Basic base64(user:pass) against `:auth/static`
  ({user {:password-hash :groups}}). nil = no claim."
  [config req _getenv]
  (let [h (get-in req [:headers "authorization"] "")]
    (when (str/starts-with? h "Basic ")
      (let [decoded (try (String. (.decode (java.util.Base64/getDecoder)
                                           (subs h 6)) "UTF-8")
                         (catch Exception _ nil))
            [user pass] (when decoded (str/split decoded #":" 2))
            {:keys [password-hash groups]} (get (:auth/static config) user)]
        (when (and password-hash pass
                   (= password-hash (sha256-hex pass)))
          {:web/sub user :web/groups (set groups)
           :web/provider :static})))))

(defn- proxy-identity
  "Identity headers from a TRUSTED upstream only (`:auth/proxy` {:trusted
  #{addrs} :user-header :groups-header}): the cheapest bridge to any
  external identity system, safe exactly because trust is pinned to the
  remote address. nil = no claim."
  [config req _getenv]
  (let [{:keys [trusted user-header groups-header]} (:auth/proxy config)
        user (get-in req [:headers (str user-header)])]
    (when (and (contains? (set trusted) (:remote-addr req))
               (seq (str user)))
      {:web/sub (str user)
       :web/groups (into #{}
                         (remove str/blank?)
                         (str/split (str (get-in req [:headers (str groups-header)] ""))
                                    #","))
       :web/provider :proxy-header})))

(defn ^:export resolve-identity
  "Resolve a request into `{:web/sub :web/groups :web/provider}` or nil
  (anonymous — the policy layer's default-deny takes it from there). Walks
  `:auth/providers` in declared order; the FIRST provider claiming the
  request wins; configured group membership (`:auth/groups`) augments
  whatever the provider asserted. `:getenv` (map or fn, default
  System/getenv) is the seam env-indirect secrets resolve through."
  [config req & {:keys [getenv] :or {getenv #(System/getenv %)}}]
  (let [provider-fn {:bearer bearer-identity
                     :static static-identity
                     :proxy-header proxy-identity}]
    (some-> (some (fn [p]
                    (when-let [f (provider-fn p)]
                      (f config req getenv)))
                  (:auth/providers config))
            (augment-groups (:auth/groups config)))))

(defn ^:export config-from-values
  "Parse the `capabilities` config's {key value} STRINGS into the runtime
  auth config `{:auth/providers [...] :auth/bearer {...} :auth/static {...}
  :auth/proxy {...} :auth/groups {...}}` — ONE parser, used by slopp's own
  serving (store values) and by a built app (the rendered capabilities
  file). Entry values are EDN, read with the SAFE reader; unparseable
  entries are skipped rather than thrown."
  [values]
  (let [edn* (fn [s] (try (edn/read-string (str s))
                          (catch Exception _ nil)))
        csv  (fn [s] (into [] (remove str/blank?) (map str/trim (str/split (str s) #","))))]
    (reduce-kv
     (fn [cfg k v]
       (let [k (str k)]
         (cond
           (= k "auth.providers")
           (assoc cfg :auth/providers (mapv keyword (csv v)))

           (str/starts-with? k "auth.bearer.tokens.")
           (assoc-in cfg [:auth/bearer (subs k 19)] (edn* v))

           (str/starts-with? k "auth.static.users.")
           (assoc-in cfg [:auth/static (subs k 18)] (edn* v))

           (= k "auth.proxy.trusted")
           (assoc-in cfg [:auth/proxy :trusted] (set (csv v)))

           (= k "auth.proxy.user-header")
           (assoc-in cfg [:auth/proxy :user-header] (str v))

           (= k "auth.proxy.groups-header")
           (assoc-in cfg [:auth/proxy :groups-header] (str v))

           :else
           (if-let [[_ g] (re-matches #"groups\.([^.]+)\.members" k)]
             (assoc-in cfg [:auth/groups g] (set (csv v)))
             cfg))))
     {}
     values)))
