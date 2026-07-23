(ns slopp.web.static (:require [clojure.string :as str]))

(def ^:private content-types
  "Extension → media type for the built-app reader (a store-backed reader
  gets the type from the manifest entry instead)."
  {"html" "text/html" "css" "text/css" "js" "text/javascript"
   "json" "application/json" "png" "image/png" "jpg" "image/jpeg"
   "jpeg" "image/jpeg" "gif" "image/gif" "svg" "image/svg+xml"
   "ico" "image/x-icon" "woff2" "font/woff2" "txt" "text/plain"
   "map" "application/json" "webp" "image/webp"})

(defn ^:export mount-routes
  "Route rows serving static assets: `mounts` = {url-prefix path-prefix}
  (`{\"/assets\" \"public\"}` maps GET /assets/cljs/main.js → (reader
  \"public/cljs/main.js\")); `reader` returns {:content <bytes|string>
  :content-type …} or nil. The handler answers a RAW response
  (`:web/raw true` + :headers Content-Type) the adapters write verbatim —
  no JSON wrapping. Assets are :public.

  Serves a whole TREE under the prefix via the router's trailing catch-all —
  which is what a static mount is for, and what slopp's own default bundle path
  (public/cljs/main.js → /assets/cljs/main.js) needs.

  TRAVERSAL IS REFUSED HERE, before `reader` is ever called: spanning a subtree
  removes the one-segment route that used to contain `..` by accident, and a
  custom reader (a store-backed one, say) may do no checking at all. A rejected
  path is indistinguishable from a missing one — 404, never a leak."
  [mounts reader]
  (vec
   (for [[url-prefix path-prefix] mounts]
     {:method :get
      :path (str url-prefix "/*path")
      :auth :public
      :handler (fn [req]
                 (let [rel   (str (:path (:path-params req)))
                       safe? (and (seq rel)
                                  (not (str/starts-with? rel "/"))
                                  (not (str/includes? rel "\\"))
                                  (not-any? #{"" "." ".."} (str/split rel #"/")))]
                   (if-let [{:keys [content content-type]}
                            (when safe? (reader (str path-prefix "/" rel)))]
                     {:status 200
                      :web/raw true
                      ;; a store-backed reader supplies no type for a blob, and a script served
                      ;; with NO Content-Type is refused by strict MIME checking — fall
                      ;; back to the same extension table the built-app reader uses
                      :headers (if-let [t (or content-type
                                              (content-types
                                               (str/lower-case
                                                (last (str/split rel #"\.")))))]
                                 {"Content-Type" (str t)}
                                 {})
                      :body content}
                     {:status 404 :body {:error "no such asset"}})))})))

(defn ^:export file-or-resource-reader
  "The BUILT-app reader for `mount-routes`: resolve `path` as a file under
  `root` first (a jar/deps.edn app run from its project dir), then as a
  CLASSPATH RESOURCE (a native binary carrying its assets via
  -H:IncludeResources). Returns {:content <bytes> :content-type <from the
  extension>} or nil. A live-store app uses a store-backed reader instead
  (slopp.http/start-server!).

  CONTAINED: the resolved file's canonical path must stay under `root`, and
  a `..` traversal segment is refused outright (review W5) — the reader
  defends itself rather than trusting the caller's route shape, which the
  single-segment router constraint only accidentally provides."
  [root]
  (let [root-canon (.getCanonicalFile (java.io.File. (str root)))
        prefix     (str (.getPath root-canon) java.io.File/separator)]
    (fn [path]
      (let [ext  (let [i (.lastIndexOf (str path) ".")]
                   (when (pos? i) (subs (str path) (inc i))))
            typ  (get content-types ext)
            f    (.getCanonicalFile (java.io.File. root-canon (str path)))
            in-root? (or (= f root-canon)
                         (.startsWith (str (.getPath f)) prefix))
            traversal? (boolean (re-find #"(?:^|[/\\])\.\.(?:[/\\]|$)" (str path)))
            from (fn [^java.io.InputStream in]
                   (with-open [in in
                               out (java.io.ByteArrayOutputStream.)]
                     (.transferTo in out)
                     {:content (.toByteArray out) :content-type typ}))]
        (cond
          traversal? nil
          (and in-root? (.isFile f)) (from (java.io.FileInputStream. f))
          :else (when-let [r (.getResourceAsStream
                              (.getContextClassLoader (Thread/currentThread))
                              (str path))]
                  (from r)))))))
