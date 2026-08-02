(ns slopp.web.static
  "Serving BYTES rather than handlers: mount a URL prefix over a path prefix
  and answer whatever is under it, as a raw response the adapters write
  verbatim — no JSON wrapping, no handler var.

  The seam is the **reader**. `mount-routes` takes one, and where the bytes
  actually live is that reader's business: a built app resolves a file under
  its root and then a classpath resource (`file-or-resource-reader`, which is
  how a native binary carries its own assets); a live store looks the path up
  in a manifest instead. Same routes, same mounts, different reader — which is
  what lets `--live` and a shipped jar serve the identical asset tree.

  **The subject of this namespace is containment.** A static mount spans a
  whole subtree via the router's trailing catch-all, which removes the
  one-segment route that used to stop `..` by accident. So traversal is
  refused TWICE on purpose: in `mount-routes` before the reader is ever
  called, because a custom reader may check nothing at all, and again inside
  `file-or-resource-reader`, because a reader must defend itself rather than
  trust the route shape that happens to precede it. A rejected path is
  indistinguishable from a missing one — 404, never a leak.

  A trailing slash on a configured prefix is normalised away here, since a
  filesystem reader silently forgives `public//app.css` and a store-backed one
  does not — the same config would otherwise work in a built app and serve
  nothing under `--live`."
  (:require [clojure.string :as str]))

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
   (for [[url-prefix raw-prefix] mounts
         ;; the handler adds its own separator, so a prefix written `public/`
         ;; would ask the reader for `public//app.css`. A filesystem reader
         ;; normalises that away and a store-backed one does not — it looks the
         ;; string up in a manifest — so the same config would work in a built
         ;; app and serve nothing under --live.
         :let [path-prefix (str/replace (str raw-prefix) #"/+$" "")]]
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
