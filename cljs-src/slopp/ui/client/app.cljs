(ns slopp.ui.client.app
  "The browser glue, and deliberately the thinnest namespace in the UI.

  This is the one place the JVM oracle cannot reach: `:cljs` never loads
  into the image, so its only verification is that it compiled. Everything
  that can be someone else's job is — routing is `views/route-for`, the
  whole render is `views/app-view`, filtering is `nsfilter/matches?`, and
  each of those is `:cljc` and covered by ordinary in-image tests.

  What is left here is what genuinely needs a browser: an atom, a Replicant
  mount, history, and fetches through the GENERATED typed client. When this
  namespace grows, the first question is which part of it belongs in
  `:cljc` — because that is the part that stopped being tested."
  (:require [replicant.dom :as r]
            [slopp.ui.client.api :as api]
            [slopp.ui.views :as views] [slopp.ui.client.sketch :as sketch]))

(defonce state
  ;; The whole application state: {:path :screen :params :data :namespaces
  ;; :filter :error}.
  ;;
  ;; ONE atom rendered by ONE pure function (views/app-view). There is
  ;; deliberately no per-screen component holding its own state — that is
  ;; what makes every screen reproducible from a map in an in-image test,
  ;; which is the property the whole :cljc split exists to buy.
  ;;
  ;; A comment rather than a docstring: `defonce` takes a name and an init
  ;; and nothing else, in both Clojure and ClojureScript.
  (atom {}))

(defn render!
  "Render the current state into the mount point.

  The WHOLE app, every time. Replicant diffs against the live DOM, so a full
  re-render costs the difference and not the page — which means no screen
  ever has to know what changed, and there is no incremental-update path
  that can disagree with the from-scratch one."
  []
  (when-let [root (js/document.getElementById "app")]
    (r/render root (views/app-view @state))))

(defn fetch-for
  "The promise of `screen`'s data, through the GENERATED typed client.

  Every call here is a wrapper slopp generated from that endpoint's declared
  `:web/response` — so the contract the server enforces is the contract this
  validates on arrival, and a drift between them is a finding rather than a
  shape that renders as nothing.

  Returns nil for a screen that needs no fetch."
  [screen params]
  (case screen
    :timeline (api/timeline)
    :change   (api/change {:range (:range params)})
    :code     (api/modules)
    :ns       (api/ns-outline {:ns (:ns params)})
    :source   (api/source {:ns (:ns params) :name (:name params)})
    :form     (api/form {:id (:id params)})
    nil))

(defn show!
  "Route `path`, render the loading state, then fetch and render the screen.

  `:data nil` is set BEFORE the fetch starts, deliberately. Leaving the
  previous screen's data on display under the new URL is the SPA failure
  mode that shows one thing while the address bar claims another — a
  moment of \"loading…\" is honest, a stale screen is not.

  The Code section also needs its module nav, which is a second fetch and is
  cached in state: it is the same nav on every Code screen, and re-fetching
  it per navigation would make the left pane flicker for no new information."
  [path push?]
  (let [{:keys [screen params]} (or (views/route-for path)
                                    {:screen nil :params {}})]
    (when push? (.pushState js/history nil "" path))
    (swap! state assoc :path path :screen screen :params params
           :data nil :error nil)
    (render!)
    (when (and (#{:code :ns :source} screen) (nil? (:modules @state)))
      (-> (api/modules)
          (.then (fn [idx] (swap! state assoc :modules (:modules idx)) (render!)))
          (.catch (fn [_] nil))))
    (when-let [p (fetch-for screen params)]
      (-> p
          (.then (fn [data]
                   ;; only if we are still on the screen that asked — a slow
                   ;; response must not overwrite a newer one the reader has
                   ;; already navigated to
                   (when (= path (:path @state))
                     ;; sketch ONCE, on arrival: rough.js is deterministic per
                     ;; seed, so re-deriving it each render would spend work to
                     ;; produce byte-identical paths
                     (let [data (if (and (= :code screen) (:picture data))
                                  (update data :picture assoc :sketch
                                          (sketch/paths-for (:picture data)))
                                  data)]
                       (swap! state assoc :data data))
                     (render!))))
          (.catch (fn [e]
                    (when (= path (:path @state))
                      (swap! state assoc :error (or (.-message e) "request failed"))
                      (render!))))))))

(defn ^:export main
  "Mount the app: wire the document, then route the URL we arrived on.

  Listeners are DELEGATED to the document rather than bound to elements,
  because Replicant replaces the DOM on every render — anything bound to an
  element would survive exactly one navigation.

  Only plain left-clicks on in-app links are intercepted. A middle-click or
  a modified click means open in a new tab, and a code browser is read in
  many tabs at once; an external link is left alone entirely. A path this
  app does not route falls through to the browser, so the SERVER gets to
  answer it — which is what keeps a genuinely wrong URL a 404 instead of an
  in-app screen claiming the page exists."
  []
  (js/document.addEventListener
   "click"
   (fn [e]
     (let [a (some-> (.-target e) (.closest "a"))
           href (some-> a (.getAttribute "href"))]
       (when (and href
                  (zero? (.-button e))
                  (not (or (.-metaKey e) (.-ctrlKey e)
                           (.-shiftKey e) (.-altKey e)))
                  (.startsWith href "/")
                  (views/route-for href))
         (.preventDefault e)
         (show! href true)))))
  (js/document.addEventListener
   "input"
   (fn [e]
     (when (= "ns-filter" (.-id (.-target e)))
       (swap! state assoc :filter (.-value (.-target e)))
       (render!))))
  (js/window.addEventListener
   "popstate"
   (fn [_] (show! (.-pathname js/location) false)))
  (show! (.-pathname js/location) false))

(defonce ^:unused-ok bootstrap
  ;; compile_client emits a :simple bundle whose top-level forms run when the
  ;; <script> loads, so the document starts the app with NO inline JS and the
  ;; page stays script-src-only. defonce guards a double-mount if the bundle
  ;; is ever evaluated twice.
  (if (= "loading" js/document.readyState)
    (js/document.addEventListener "DOMContentLoaded" (fn [_] (main)))
    (main)))
