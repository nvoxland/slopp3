(ns slopp.web.css
  (:require [garden.core :as garden]))

(defn- check-breakout
  "Refuse a string garden would render verbatim into a selector or value
  when it carries a CSS block-breakout character. `{`/`}` break out of the
  declaration block (garden generates them structurally); `<` breaks out of
  an inlined <style> element. `;` is NOT refused — data URIs
  (data:…;base64,…) use it legitimately, and without a `}` a stray `;` can
  only add a declaration to the same rule, not forge new ones."
  [s]
  (when (re-find #"[{}<]" s)
    (throw (ex-info (str "a CSS string may not contain { } or < — it can break "
                         "out of its declaration block; use structured garden "
                         "data, or serve a static .css asset for raw/vendored CSS: "
                         (pr-str s))
                    {:value s})))
  s)

(defn ^:export render
  "Garden rules → a minified CSS string. Every string in the rule data — a
  selector or a value — is validated against CSS block-breakout first
  ({ } <), because garden renders those strings verbatim (an interpolated
  string is otherwise an injection door). Structured garden values (units,
  colors, media queries) are safe. For raw or vendored CSS, serve a static
  .css asset instead. CSS as Clojure data is the stylesheet analogue of
  hiccup pages."
  [& rules]
  (doseq [s (filter string? (tree-seq coll? seq (vec rules)))]
    (check-breakout s))
  (or (apply garden/css {:pretty-print? false} rules) ""))

(defn ^:export css-response
  "Ring response serving rendered garden `rules` (a vector of rules) as
  text/css. :web/raw true — the adapters write the body verbatim. opts may
  carry :status and extra :headers; Content-Type stays ours. Serve it from
  a :get endpoint; a page's [:link {:href …}] to that path is then covered
  by the web-dangling-route-refs advisory like any other link."
  ([rules] (css-response rules nil))
  ([rules {:keys [status headers]}]
   {:status  (or status 200)
    :web/raw true
    :headers (merge headers {"Content-Type" "text/css; charset=utf-8"})
    :body    (apply render rules)}))
