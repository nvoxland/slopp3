(ns slopp.web.router
  (:require [clojure.string :as str]))

(defn ^{:export "slopp.api"
        :teach "matches :uri ALONE — :query-string is a separate request key. A \"/x?y=z\" put in :uri matches no route and 404s, which looks exactly like the 404 you were testing for."}
  match
  "Match `method` + `uri` against `routes` (rows carrying :method :path
  :handler, the query_routes shape). Returns the matched row with
  `:path-params` merged ({:id \"42\"} for \"/api/users/:id\"), or nil.

  A `:x` segment captures ONE segment. A TRAILING `*x` captures the REMAINDER —
  one or more segments, slash-joined ({:path \"cljs/main.js\"} for
  \"/assets/*path\") — which is what lets a static mount serve a TREE; a `*`
  anywhere but last never matches. Precedence is fewest-captures-wins, and a
  catch-all ranks below BOTH a static segment and a single-segment capture, so
  adding one never steals an existing route. A trailing slash is tolerated.
  Pure — request data in, decision data out."
  [routes method uri]
  (let [segs   (fn [s] (vec (remove str/blank? (str/split (str s) #"/"))))
        u      (segs uri)
        cap?   #(str/starts-with? % ":")
        splat? #(str/starts-with? % "*")
        ;; a catch-all is the loosest possible match — rank it far below a
        ;; single capture so static > :one > *rest holds
        rank   (fn [ps] (+ (count (filter cap? ps))
                           (* 100 (count (filter splat? ps)))))
        row-match (fn [{:keys [path] :as row}]
                    (let [p (segs path)]
                      (when (if (some splat? p)
                              (>= (count u) (count p))
                              (= (count p) (count u)))
                        (loop [p p, u u, params {}]
                          (cond
                            (empty? p)
                            (when (empty? u)
                              (assoc row :path-params params ::captures (rank (segs path))))

                            ;; trailing catch-all: swallow the rest (>= 1 segment)
                            (splat? (first p))
                            (when (and (= 1 (count p)) (seq u))
                              (assoc row :path-params
                                     (assoc params (keyword (subs (first p) 1))
                                            (str/join "/" u))
                                     ::captures (rank (segs path))))

                            (cap? (first p))
                            (recur (rest p) (rest u)
                                   (assoc params (keyword (subs (first p) 1))
                                          (first u)))

                            (= (first p) (first u))
                            (recur (rest p) (rest u) params)

                            :else nil)))))]
    (some-> (->> routes
                 (filter #(= method (:method %)))
                 (keep row-match)
                 (sort-by ::captures)
                 first)
            (dissoc ::captures))))

(defn ^:export query-params
  "A request's `:query-string` as `{:key \"value\"}`, percent- and
  plus-decoded. `nil`/blank → `{}`.

  A bare key (`\"flag\"`) is PRESENT with an empty value, because present
  and empty is a different thing from absent and a handler has to be able
  to tell them apart. A pair whose key or encoding is malformed is dropped
  rather than thrown: a query string is arbitrary text from the network,
  and the response to garbage is a page, not a 500.

  The router's other half — matching a path and reading its parameters
  are the same question asked of the two halves of a URL. `handle!`
  assocs the result as `:query-params` on every request."
  [query-string]
  (if (str/blank? query-string)
    {}
    (let [decode (fn [s]
                   (try (java.net.URLDecoder/decode (str s) "UTF-8")
                        (catch Exception _ nil)))]
      (into {}
            (keep (fn [pair]
                    (let [[k v] (str/split pair #"=" 2)
                          k'    (decode k)
                          v'    (decode (or v ""))]
                      (when (and (seq k') v')
                        [(keyword k') v']))))
            (str/split query-string #"&")))))
