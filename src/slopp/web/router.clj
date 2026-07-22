(ns slopp.web.router
  (:require [clojure.string :as str]))

(defn match
  "Match `method` + `uri` against `routes` (rows carrying :method :path
  :handler, the query_routes shape). Returns the matched row with
  `:path-params` merged ({:id \"42\"} for \"/api/users/:id\"), or nil. A
  `:x` segment captures one segment; a static segment beats a capture when
  both match (fewest captures wins); a trailing slash is tolerated. Pure —
  request data in, decision data out."
  [routes method uri]
  (let [segs (fn [s] (vec (remove str/blank? (str/split (str s) #"/"))))
        u    (segs uri)
        cap? #(str/starts-with? % ":")
        row-match (fn [{:keys [path] :as row}]
                    (let [p (segs path)]
                      (when (= (count p) (count u))
                        (loop [p p, u u, params {}]
                          (cond
                            (empty? p)
                            (assoc row :path-params params
                                   ::captures (count (filter cap? (segs path))))

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
