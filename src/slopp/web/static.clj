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
