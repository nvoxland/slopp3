(ns slopp.web.css
  "Stylesheets as Clojure DATA — the analogue of `slopp.web.html` for the
  other half of a page. Garden rules in, a minified CSS string or a ready
  `text/css` response out.

  Being data is the whole value, and it is also the whole exposure: garden
  renders any STRING it is handed verbatim into a selector or a value, so an
  interpolated string is an injection door. Hence two refusals, both measured
  rather than theorised:

  - **Block-breakout characters (`{`, `}`, `<`) in any string.** `;` is
    deliberately allowed — data URIs need it, and without a `}` a stray `;`
    can only add a declaration to the rule it is already in.
  - **A function anywhere in the rule data.** No function is meaningful CSS,
    and garden does not reject one — it stringifies it, so
    `clojure.core$_GT_@185af676` lands in a selector and the browser silently
    drops the rule. This SHIPPED: a bare `>` reaching for a child combinator
    reads as `clojure.core/>` and swallows the neighbouring selector, so
    `[:.app > :nav {:width \"16rem\"}]` rendered as `.app{width:16rem}` and the
    whole application container was 16rem wide for a wave — behind a 200 and
    valid-looking CSS.

  For raw or vendored CSS, don't fight the refusals: serve a static `.css`
  asset (`slopp.web.static`), which is the supported route for text this
  namespace deliberately will not vouch for."
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

(defn ^{:export true
        :teach "combinators: :.a>b (ONE keyword) is CHILD, [:.a [:b …]] (nesting) is DESCENDANT, [:.a :b …] (siblings) is a selector GROUP — a bare > is clojure.core/> and refuses."}
  render
  "Garden rules → a minified CSS string. Every string in the rule data — a
  selector or a value — is validated against CSS block-breakout first
  ({ } <), because garden renders those strings verbatim (an interpolated
  string is otherwise an injection door). Structured garden values (units,
  colors, media queries) are safe. For raw or vendored CSS, serve a static
  .css asset instead. CSS as Clojure data is the stylesheet analogue of
  hiccup pages.

  Also REFUSES a function anywhere in the rule data. There is no rule under
  which a function is meaningful CSS, and garden does not reject one — it
  stringifies it, so `clojure.core$_GT_@185af676` lands in a selector and the
  browser silently drops that rule. The way this happens in practice is a
  bare `>` reaching for a child combinator: it reads as `clojure.core/>`, and
  the neighbouring selector keyword is swallowed with it. That shipped here —
  `[:.app > :nav {:width \"16rem\"}]` rendered as `.app{width:16rem}`, so the
  whole application container was 16rem wide in a browser for a wave, behind
  a 200 and valid-looking CSS.

  The three combinators, since two of them look alike:
  `[:.app>nav …]` (one keyword) is CHILD, `[:.app [:nav …]]` (nesting) is
  DESCENDANT, and `[:.app :nav …]` (siblings) is a selector GROUP."
  [& rules]
  (doseq [x (tree-seq coll? seq (vec rules))]
    (cond
      (string? x) (check-breakout x)
      (or (fn? x) (var? x))
      (throw (ex-info (str "a function in CSS rule data — garden renders it into"
                           " the output instead of refusing, so the rule is lost"
                           " silently. A bare `>` is the usual cause: it reads as"
                           " clojure.core/>, not a child combinator. Write the"
                           " combinator inside ONE keyword (:.app>nav), nest for"
                           " a descendant ([:.app [:nav …]]), and remember that"
                           " sibling keywords are a GROUP, not a descendant.")
                      {:css/offender (pr-str x)}))))
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
