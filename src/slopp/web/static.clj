(ns slopp.web.static)

(defn ^:export mount-routes
  "Route rows serving static assets: `mounts` = {url-prefix path-prefix}
  (`{\"/assets\" \"public\"}` maps GET /assets/app.css → (reader
  \"public/app.css\")); `reader` returns {:content <bytes|string>
  :content-type …} or nil. The handler answers a RAW response
  (`:web/raw true` + :headers Content-Type) the adapters write verbatim —
  no JSON wrapping. One-segment filenames for now (the router's declared
  param scope); assets are :public."
  [mounts reader]
  (vec
   (for [[url-prefix path-prefix] mounts]
     {:method :get
      :path (str url-prefix "/:file")
      :auth :public
      :handler (fn [req]
                 (if-let [{:keys [content content-type]}
                          (reader (str path-prefix "/"
                                       (:file (:path-params req))))]
                   {:status 200
                    :web/raw true
                    :headers (if content-type
                               {"Content-Type" (str content-type)}
                               {})
                    :body content}
                   {:status 404 :body {:error "no such asset"}}))})))

(def ^:private content-types
  "Extension → media type for the built-app reader (a store-backed reader
  gets the type from the manifest entry instead)."
  {"html" "text/html" "css" "text/css" "js" "text/javascript"
   "json" "application/json" "png" "image/png" "jpg" "image/jpeg"
   "jpeg" "image/jpeg" "gif" "image/gif" "svg" "image/svg+xml"
   "ico" "image/x-icon" "woff2" "font/woff2" "txt" "text/plain"
   "map" "application/json" "webp" "image/webp"})

(defn ^:export file-or-resource-reader
  "The BUILT-app reader for `mount-routes`: resolve `path` as a file under
  `root` first (a jar/deps.edn app run from its project dir), then as a
  CLASSPATH RESOURCE (a native binary carrying its assets via
  -H:IncludeResources). Returns {:content <bytes> :content-type <from the
  extension>} or nil. A live-store app uses a store-backed reader instead
  (slopp.http/start-server!)."
  [root]
  (fn [path]
    (let [ext  (let [i (.lastIndexOf (str path) ".")]
                 (when (pos? i) (subs (str path) (inc i))))
          typ  (get content-types ext)
          f    (java.io.File. (str root) (str path))
          from (fn [^java.io.InputStream in]
                 (with-open [in in
                             out (java.io.ByteArrayOutputStream.)]
                   (.transferTo in out)
                   {:content (.toByteArray out) :content-type typ}))]
      (cond
        (.isFile f) (from (java.io.FileInputStream. f))
        :else (when-let [r (.getResourceAsStream
                            (.getContextClassLoader (Thread/currentThread))
                            (str path))]
                (from r))))))
