(ns slopp.ui.views-test
  "Tests for every screen, asserting on hiccup DATA.

  These replaced assertions that regexed server-rendered HTML strings. The
  properties are the same; reaching them is now cheaper and sharper, with no
  server and no browser in the loop — which is the payoff the `:cljc` view
  split was chosen for, and the reason to keep resisting anything that pulls
  rendering into `:cljs`.

  Two screens can only be tested here at all: LOADING and NOT-FOUND exist
  only once the server stops rendering pages, so nothing else observes
  them."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ui.views :as views] [clojure.string :as str]))

(deftest app-shell-lays-out-three-panes-from-data
  ;; The whole point of the :cljc discipline: this is an ORDINARY in-image
  ;; test asserting on hiccup DATA. No DOM, no browser, no cljs runner —
  ;; 0.5 ms rather than the 368 ms anything in the external tier costs. If a
  ;; view ever needs the external tier, the split is wrong.
  (let [v (views/app-shell
           {:nav/sections [{:label "Review" :href "/"}
                           {:label "Code" :href "/store" :active? true}]
                        :nav/local    [:div [:input {:id "ns-filter"}] [:p "left pane"]]
            :nav/detail   [[:h3 "Callers"] [:p "3 forms"]]}
           [:h1 "main content"])]
    (testing "all three panes are present, and the main content with them"
      (is (some? (views/find-region v :nav/sections)))
      (is (some? (views/find-region v :nav/local)))
      (is (some? (views/find-region v :nav/detail))))
    (testing "sections are links, and the active one is marked"
      (let [top (views/find-region v :nav/sections)
            hrefs (for [x (tree-seq coll? seq top)
                        :when (and (vector? x) (= :a (first x)))]
                    (:href (second x)))]
        (is (= ["/" "/store"] (vec hrefs)))
        ;; `some`, not `(str (for …))` — stringifying a lazy seq yields
        ;; "clojure.lang.LazySeq@ab2f7f25", which matches no regex and would
        ;; have made this assertion permanently meaningless either way
        (is (= "active"
               (some (fn [x] (when (and (vector? x) (map? (second x))
                                        (= "/store" (:href (second x))))
                               (:class (second x))))
                     (tree-seq coll? seq top)))
            "the current section has to be visibly current")))
        (testing "side panes ride through as given — neither is a link list"
      ;; The local pane carries a filter INPUT, which is why it cannot be link
      ;; data: forcing it through nav-links is exactly what dropped the box.
      (is (some #(= [:h3 "Callers"] %) (tree-seq coll? seq v)))
      (is (some #(= [:input {:id "ns-filter"}] %) (tree-seq coll? seq v))))
    (testing "an omitted pane is absent, not an empty box"
      ;; a form page has no section-local nav; rendering an empty rail would
      ;; take layout space and say nothing
      (let [bare (views/app-shell {:nav/sections [{:label "Review" :href "/"}]}
                                  [:h1 "x"])]
        (is (nil? (views/find-region bare :nav/local)))
        (is (nil? (views/find-region bare :nav/detail)))))))

(deftest sections-are-one-table-and-mark-where-you-are
  ;; Global nav has to be identical on every page, and the CURRENT section has
  ;; to be marked. Both fail the same way if each page builds its own list:
  ;; they drift, and the reader loses track of where they are.
  (testing "every section is a literal href the dangling-route gate can see"
    (doseq [{:keys [href label]} views/sections]
      (is (string? label))
      (is (re-matches #"/[a-z]*" href) (str label " -> " href))))
  (testing "a path picks its section by prefix, longest first"
    (is (= "Review" (:label (views/current-section "/"))))
    (is (= "Review" (:label (views/current-section "/change/d1..d2"))))
    (is (= "Code" (:label (views/current-section "/store"))))
    (is (= "Code" (:label (views/current-section "/store/ns/slopp.api"))))
    (is (= "Code" (:label (views/current-section "/store/form/f1")))))
  (testing "an unknown path selects nothing rather than guessing"
    ;; marking a section active on a page that is not in it is worse than
    ;; marking none — it tells the reader something false
    (is (nil? (views/current-section "/nowhere"))))
  (testing "marked sections carry exactly one active"
    (let [marked (views/marked-sections "/store/ns/slopp.api")]
      (is (= 1 (count (filter :active? marked))))
      (is (= "Code" (:label (first (filter :active? marked)))))
      (is (= (count views/sections) (count marked))))))

(deftest the-outline-view-renders-from-the-wire-shape
  ;; This is the view the SPA swap and the server render SHARE, so it takes
  ;; the shape that crosses the wire — strings, `:doc` possibly nil — rather
  ;; than the store's shape. If it took symbols, the server would render fine
  ;; and the client would render `nil`s, and only a browser would tell you.
  (let [v (views/ns-outline-main
           {:ns "demo.core"
            :forms [{:name "hello" :doc "Says hi."}
                    {:name "quiet" :doc nil}]})
        nodes (tree-seq coll? seq v)]
    (testing "the namespace names the pane, and each form links its source"
      (is (= ["/store/source/demo.core/hello" "/store/source/demo.core/quiet"]
             (vec (for [x nodes :when (and (vector? x) (= :a (first x)))]
                    (:href (second x))))))
      (is (some #(= [:h1 "demo.core"] %) nodes)))
    (testing "a docstring rides along, and its absence renders nothing at all"
      ;; not an empty <small>: a blank element is a visible gap that says a
      ;; doc exists and is empty, which is a different and false statement
      (is (some #(= [:small " — Says hi."] %) nodes))
      ;; scoped to the form list: the property is about a form with no
      ;; docstring, not about how many <small>s the whole page contains
      (is (= 1 (count (filter #(and (vector? %) (= :small (first %)))
                              (tree-seq coll? seq
                                        (first (filter #(and (vector? %)
                                                             (= :ul (first %)))
                                                       nodes))))))))))

(deftest the-client-router-owns-every-url-the-app-serves
  ;; With the server rendering gone, THIS is the routing table. A path it
  ;; cannot parse is a blank screen, so the mapping is pinned here rather
  ;; than discovered in a browser.
  (testing "each screen's URL resolves to its route and params"
    (is (= {:screen :timeline :params {}} (views/route-for "/")))
    (is (= {:screen :change :params {:range "d1..d2"}}
           (views/route-for "/change/d1..d2")))
    (is (= {:screen :code :params {}} (views/route-for "/store")))
    (is (= {:screen :ns :params {:ns "slopp.api"}}
           (views/route-for "/store/ns/slopp.api")))
    ;; a made-up ns/name on purpose: a fixture naming a REAL var reads as a
    ;; reference to it, so the stale-reference advisory fires when that var
    ;; moves even though nothing here depends on it
    (is (= {:screen :source :params {:ns "demo.core" :name "hello"}}
           (views/route-for "/store/source/demo.core/hello")))
    (is (= {:screen :form :params {:id "f123"}}
           (views/route-for "/store/form/f123"))))
  (testing "a trailing slash is the same screen, not a different one"
    ;; /store/ and /store are the same place to a reader, and a router that
    ;; disagrees produces a blank screen for a URL that looks right
    (is (= {:screen :code :params {}} (views/route-for "/store/"))))
  (testing "an unknown path resolves to NOTHING, never to a guess"
    ;; the SPA equivalent of a 404 — rendering the timeline for an unknown
    ;; URL would tell the reader they are somewhere they are not
    (is (nil? (views/route-for "/nonsense")))
    (is (nil? (views/route-for "/store/nonsense")))
    (is (nil? (views/route-for "/store/form")))
    (is (nil? (views/route-for "/store/source/only-one-segment"))))
  (testing "names with dots and bangs survive — they are ordinary in Clojure"
    (is (= {:screen :source :params {:ns "a.b.c" :name "swap!"}}
           (views/route-for "/store/source/a.b.c/swap!"))))
  (testing "every route the app links to is one this router can parse"
    ;; the two halves have to agree or a link goes nowhere; `sections` is the
    ;; global nav, so it is the one list that must round-trip
    (doseq [{:keys [href]} views/sections]
      (is (some? (views/route-for href)) href))))

(deftest the-app-renders-every-state-including-the-two-an-spa-invents
  ;; Server-rendered pages have no LOADING state and no client-side
  ;; NOT-FOUND — the server answers or it 404s. An SPA invents both, and
  ;; both render as a blank pane if nobody handles them. A blank pane is
  ;; indistinguishable from a screen whose content is empty, which is the
  ;; specific way an SPA lies to its reader.
  (letfn [(text [v] (str/join " " (filter string? (tree-seq coll? seq v))))]
    (testing "in flight: says so, rather than rendering an empty main"
      (let [v (views/app-view {:path "/" :screen :timeline :data nil})]
        (is (re-find #"loading" (text v)))
        (is (some? (views/find-region v :nav/sections))
            "the global bar stays up while the screen loads — it is not part of the fetch")))
    (testing "not found: names the path instead of guessing a screen"
      (let [v (views/app-view {:path "/nonsense" :screen nil})]
        (is (re-find #"not found" (text v)))
        (is (re-find #"/nonsense" (text v)))))
    (testing "an error renders as an error, not as a permanent spinner"
      ;; a failed fetch that leaves "loading…" on screen is worse than a
      ;; blank one — it promises something is still coming
      (let [v (views/app-view {:path "/" :screen :timeline :error "boom"})]
        (is (re-find #"error" (text v)))
        (is (not (re-find #"loading" (text v))))))
    (testing "the Code screens carry the left pane; the others do not"
      (let [nss [{:ns "demo.core" :forms 2}]
            of  (fn [screen params data]
                  (views/app-view {:path "/x" :screen screen :params params
                                   :data data :namespaces nss}))]
        (is (some? (views/find-region (of :ns {:ns "demo.core"}
                                          {:ns "demo.core" :forms []})
                                      :nav/local)))
        (is (nil? (views/find-region (of :timeline {} {:milestones [] :working {:forms 0}})
                                     :nav/local))
            "Review is not a section with local navigation")))
    (testing "only the form screen opens the detail rail, and only with data"
      (let [with (views/app-view {:path "/store/form/f1" :screen :form
                                  :data {:form "a/b" :ns "a" :module "a"
                                         :tokens [["text" "x"]] :warranty {:covered 0}
                                         :callers [] :callees [] :note "n"}})
            without (views/app-view {:path "/store/form/f1" :screen :form :data nil})]
        (is (some? (views/find-region with :nav/detail)))
        (is (nil? (views/find-region without :nav/detail))
            "an empty rail while loading would take layout space and say nothing")))))

(deftest the-screens-render-what-the-server-pages-used-to
  ;; These properties were asserted by regexing server-rendered HTML. They
  ;; matter just as much now, and they are cheaper and sharper here: hiccup
  ;; DATA, in-image, no server and no browser. This is the payoff the :cljc
  ;; split was chosen for.
  (letfn [(text  [v] (str/join " " (filter string? (tree-seq coll? seq v))))
          (hrefs [v] (vec (for [x (tree-seq coll? seq v)
                                :when (and (vector? x) (= :a (first x)))]
                            (:href (second x)))))]
    (testing "timeline: newest first, each milestone linking its own range"
      (let [v (views/timeline-main
               {:milestones [{:commit "c2" :description "the second" :range "c1..c2"}
                             {:commit "c1" :description "the first"}]
                :working {:since "c2" :forms 1 :namespaces ["demo.core"]
                          :prompts ["sharpen hello"]}})
            t (text v)]
        (is (< (.indexOf t "the second") (.indexOf t "the first"))
            "newest first — the reviewer's scan order")
        (is (= ["/change/c1..c2"] (hrefs v))
            "the oldest milestone has no range, so it links nowhere rather than
             to an empty one")
        (is (re-find #"sharpen hello" t) "the working set shows the recorded asks")))
    (testing "timeline: a clean working set says so instead of showing zero"
      (is (re-find #"clean"
                   (text (views/timeline-main {:milestones []
                                               :working {:since "c1" :forms 0
                                                         :namespaces [] :prompts []}})))))
    (testing "change: diff lines are classified by their marker"
      (let [v (views/change-main
               {:from "c1" :to "c2" :count 1
                :modules [{:module "demo" :count 1
                           :namespaces [{:ns "demo.core" :count 1
                                         :forms [{:form "demo.core/hello"
                                                  :form-id "f1"
                                                  :why "make hello increment"
                                                  :callers 1
                                                  :diff ["-(defn hello [x] x)"
                                                         "+(defn hello [x] (inc x))"]}]}]}]})
            classes (vec (for [x (tree-seq coll? seq v)
                               :when (and (vector? x) (= :span (first x)))]
                           (:class (second x))))]
        (is (= ["del" "add"] classes)
            "the client decides a minus line is a .del — the server sent lines")
        (is (= ["/store/form/f1"] (hrefs v)) "each form links its permalink")
        (is (re-find #"make hello increment" (text v)) "the recorded ask leads")
        (is (re-find #"1 caller" (text v)))))
    (testing "form: tokens become spans, and only classified ones do"
      (let [v (views/token-code [["delim" "("] ["special" "defn"] ["ws" " "]
                                 ["text" "hello"] ["delim" ")"]])
            spans (vec (for [x (tree-seq coll? seq v)
                             :when (and (vector? x) (= :span (first x)))]
                         (:class (second x))))]
        (is (= ["delim" "special" "delim"] spans)
            "whitespace and plain text stay BARE — a span per run would triple
             the markup for no colour")
        ;; the code element's own children, NOT tree-seq: tree-seq yields
        ;; attribute VALUES as well, so "delim" and "special" were being
        ;; counted as rendered text
        (is (= "(defn hello)"
               (apply str (map #(if (string? %) % (last %))
                               (second (second v)))))
            "and the text still concatenates back to the source")))
    (testing "form rail: callees inline their signature and doc"
      ;; the whole reason the rail exists — a link is not visibility
      (let [v (views/form-rail
               {:why "because" :warranty {:covered 3} :note "a floor, not a census"
                :callers [{:via "symbol" :count 1
                           :forms [{:form "demo.b/caller" :form-id "f9" :module "demo.b"}]}]
                :callees [{:form "demo.c/callee" :form-id "f7" :module "demo.c"
                           :sig "[x]" :doc "Adds one."}]})
            t (text v)]
        (is (re-find #"Adds one\." t) "the callee's doc appears on the caller's screen")
        (is (re-find #"\[x\]" t) "and its signature")
        (is (re-find #"3 covering tests" t))
        (is (re-find #"floor, not a census" t) "the honesty note rides along")
        (is (= ["/store/form/f9" "/store/form/f7"] (hrefs v))
            "every edge is an id — a name would break the moment it changes")))
    (testing "module nav: the filter selects by NOT rendering"
      (let [mods [{:module "demo" :namespaces ["demo.core"]
                   :tests 1 :tier "pure" :foundation false}
                  {:module "other" :namespaces ["other.thing"]
                   :tests 0 :tier "pure" :foundation false}]
            rows (fn [needle]
                   (vec (for [x (tree-seq coll? seq (views/module-nav mods nil needle))
                              :when (and (vector? x) (= :a (first x)))]
                          (last x))))]
        (is (= [] (rows nil))
            "no needle: modules are collapsed, so no namespace links yet")
        (is (= ["demo.core"] (rows "demo"))
            "a needle expands its module and drops the rest from the DOM")
        (is (= [] (rows "zzz")) "and a needle matching nothing renders no rows")
        (is (= "demo" (:value (second (first (filter #(and (vector? %)
                                                           (= :input (first %)))
                                                     (tree-seq coll? seq
                                                               (views/module-nav mods nil "demo")))))))
            "the box carries its value, or a re-render would erase what was typed")))))

(deftest module-graph-renders-the-picture-as-addressable-svg
  (let [picture {:width 400 :height 300
                 :nodes [{:module "demo.a" :layer 0 :x 10 :y 200 :w 100 :h 50}
                         {:module "demo.b" :layer 1 :x 10 :y 60 :w 100 :h 50}]
                 :band  [{:module "demo.lib" :x 10 :y 260 :w 100 :h 30}]
                 :edges [{:from "demo.b" :to "demo.a" :x1 60 :y1 110 :x2 60 :y2 200}]}
        svg   (views/module-graph picture)
        nodes (->> (tree-seq vector? seq svg)
                   (filter #(and (vector? %) (map? (second %))
                                 (get-in % [1 :data-module]))))]
    (testing "the canvas is sized by the picture, not by the view"
      (is (= :svg (first svg)))
      (is (= "0 0 400 300" (get-in svg [1 :viewBox]))))
    (testing "every box is an addressable element the client can bind to"
      (is (= #{"demo.a" "demo.b" "demo.lib"}
             (set (map #(get-in % [1 :data-module]) nodes)))))
    (testing "foundation members are marked, so CSS can treat the band differently"
      (let [cls (fn [m] (->> nodes
                             (filter #(= m (get-in % [1 :data-module])))
                             first (#(get-in % [1 :class]))))]
        (is (re-find #"foundation" (str (cls "demo.lib"))))
        (is (not (re-find #"foundation" (str (cls "demo.a")))))))
    (testing "every module is legible — its name appears as text"
      (let [texts (->> (tree-seq vector? seq svg)
                       (filter #(and (vector? %) (= :text (first %))))
                       (mapcat rest) (filter string?) set)]
        (is (every? texts ["demo.a" "demo.b" "demo.lib"]))))
    (testing "edges are drawn BEFORE nodes, so arrowheads do not sit on labels"
      (let [kids   (rest (drop-while (complement map?) svg))
            groups (filter vector? (tree-seq vector? seq svg))
            idx-of (fn [pred] (count (take-while (complement pred) groups)))]
        (is (seq kids))
        (is (< (idx-of #(= :path (first %)))
               (idx-of #(get-in % [1 :data-module]))))))))

(deftest module-nav-lists-modules-and-expands-only-where-it-should
  (let [modules [{:module "demo.a" :namespaces ["demo.a.core" "demo.a.util"]
                  :tests 2 :tier "pure" :foundation false}
                 {:module "demo.b" :namespaces ["demo.b.web"]
                  :tests 0 :tier "external" :foundation true}]
        ids     (fn [h] (->> (tree-seq vector? seq h)
                             (filter #(and (vector? %) (map? (second %))))
                             (keep #(get-in % [1 :id])) set))
        rows    (fn [h cls] (->> (tree-seq vector? seq h)
                                 (filter #(and (vector? %) (map? (second %))
                                               (= cls (get-in % [1 :class]))))))
        texts   (fn [h] (->> (tree-seq vector? seq h) (filter string?) set))
        says?   (fn [h re] (boolean (some #(re-find re %) (texts h))))]
    (testing "the hooks the client binds to survive the restructure"
      (let [h (views/module-nav modules nil nil)]
        (is (contains? (ids h) "ns-filter"))
        (is (contains? (ids h) "ns-list"))))
    (testing "collapsed by default: modules are rows, namespaces are not"
      (let [h (views/module-nav modules nil nil)]
        (is (= 2 (count (rows h "module-row"))))
        (is (zero? (count (rows h "ns-row"))))))
    (testing "test namespaces are counted, never listed"
      (let [h (views/module-nav modules nil nil)]
        (is (says? h #"2 tests"))
        (is (not-any? #(re-find #"-test" %) (texts h)))))
    (testing "the module holding the current namespace is expanded, and only it"
      (let [h (views/module-nav modules "demo.a.util" nil)]
        (is (= 2 (count (rows h "ns-row"))))
        (is (contains? (texts h) "demo.a.core"))
        (is (not (contains? (texts h) "demo.b.web")))))
    (testing "a filter match expands its module — otherwise search finds nothing behind a collapsed row"
      (let [h (views/module-nav modules nil "web")]
        (is (contains? (texts h) "demo.b.web"))
        (is (not (contains? (texts h) "demo.a.core")))))
    (testing "a module with no tests is visible as a finding, not hidden"
      (is (says? (views/module-nav modules nil nil) #"no tests")))))

(deftest ns-outline-names-the-tests-that-cover-the-namespace
  (letfn [(text  [v] (str/join " " (filter string? (tree-seq coll? seq v))))
          (hrefs [v] (vec (for [x (tree-seq coll? seq v)
                                :when (and (vector? x) (= :a (first x)))]
                            (:href (second x)))))]
    (testing "covering tests are named and linked — this is where the nav's demoted rows come back"
      (let [v (views/ns-outline-main
               {:ns "demo.a.core"
                :forms [{:name "hello" :doc nil}]
                :tested-by ["demo.a.core-test" "demo.far-test"]})]
        (is (re-find #"demo\.a\.core-test" (text v)))
        (is (re-find #"demo\.far-test" (text v)))
        (is (some #{"/store/ns/demo.a.core-test"} (hrefs v))
            "and each is a link you can follow, not just a name")))
    (testing "a namespace nothing covers SAYS so rather than showing an empty gap"
      (let [v (views/ns-outline-main
               {:ns "demo.b.util" :forms [{:name "helper" :doc nil}] :tested-by []})]
        (is (re-find #"(?i)no tests" (text v))
            "silence would look identical to a page that just doesn't show coverage")))))

(deftest module-graph-draws-sketch-paths-when-given-them-and-rects-when-not
  (let [picture {:width 400 :height 300
                 :nodes [{:module "demo.a" :layer 0 :x 10 :y 200 :w 100 :h 50}]
                 :band  [] :edges []}
        tags    (fn [v t] (->> (tree-seq vector? seq v)
                               (filter #(and (vector? %) (= t (first %))))))]
    (testing "with no sketch data it renders plain rects — this is the JVM path"
      (let [v (views/module-graph picture)]
        (is (= 1 (count (tags v :rect))))
        (is (zero? (count (filter #(= "sketch" (get-in % [1 :class]))
                                  (tags v :path)))))))
    (testing "given sketch paths for a module, it draws those instead of the rect"
      (let [v (views/module-graph
               (assoc picture :sketch {"demo.a" [{:d "M 0 0 L 10 10"}
                                                 {:d "M 1 1 L 11 11"}]}))]
        (is (zero? (count (tags v :rect))) "the rect is replaced, not layered under")
        (is (= ["M 0 0 L 10 10" "M 1 1 L 11 11"]
               (->> (tags v :path)
                    (filter #(= "sketch" (get-in % [1 :class])))
                    (mapv #(get-in % [1 :d])))))))
    (testing "a module with no sketch entry still gets its rect — partial data degrades"
      (let [v (views/module-graph (assoc picture :sketch {"other" [{:d "M 0 0"}]}))]
        (is (= 1 (count (tags v :rect))))))))

(deftest the-project-switcher-is-absent-without-a-hub-and-honest-with-one
  (testing "no hub in front means no project list means NO switcher — the
            dropdown degrades to nothing rather than to an empty control, and
            that is the ordinary single-project case, not a failure"
    (is (nil? (views/project-switcher [] "slopp2")))
    (is (nil? (views/project-switcher nil "slopp2"))))
  (let [ps [{:slug "slopp2" :name "slopp2" :available true}
            {:slug "invoices" :name "Invoices" :available false}]
        sw (views/project-switcher ps "slopp2")
        s  (pr-str sw)]
    (testing "one option per project, addressed by the hub's own /p/<slug>/"
      (is (str/includes? s "/p/slopp2/"))
      (is (str/includes? s "/p/invoices/")))
    (testing "the project you are looking at is the selected one"
      (is (str/includes? s ":selected true")))
    (testing "a project that stopped answering is still LISTED and says so,
              rather than vanishing from the control mid-session"
      (is (str/includes? s "Invoices"))
      (is (str/includes? s "not running")))))
