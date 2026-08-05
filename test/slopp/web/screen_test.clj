(ns slopp.web.screen-test
  "Cover for the readout — and the tests are the decisions, not the plumbing.

  Every case here is a real view bug that got past a careful reviewer's own
  assertions, which is why each one asserts the DIFFERENCE from the obvious
  substitute rather than the output shape. A flatten would satisfy \"contains
  the words\" for most of these; what it loses is the boundary between them.

  The one addition to the lifted implementation — a region that is not on the
  screen refusing rather than scoping to nothing — has its own test, because
  its failure mode is a green suite over a blank page."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [slopp.web.screen :as screen]))

(deftest a-block-never-glues-to-the-text-around-it
  ;; THE founding bug, and the reason a naive flatten is not merely uglier but
  ;; blind. `(->> (tree-seq coll? seq v) (filter string?) (str/join " "))` —
  ;; the helper slopp-ui had copied into six deftests — renders
  ;; [:h1 "code"] [:p "3 modules"] as "code 3 modules". A WRONG SENTENCE IN THE
  ;; MIDDLE OF A RUN-ON LINE IS INVISIBLE, and `5 of thems foundation` sat on a
  ;; served page for as long as that sentence existed.
  (testing "a heading owns its line, and carries its level as a real tag"
    (is (= "<h1>code</h1>\n3 modules, 4 namespaces"
           (screen/of [:div [:h1 "code"] [:p "3 modules, 4 namespaces"]]))))
  (testing "inline tags DO join the line — the split is by tag, not by nesting"
    (is (= "the store has 12 forms"
           (screen/of [:p "the store has " [:strong "12"] " forms"]))))
  (testing "an unknown tag starts a line rather than joining one"
    ;; the safe direction: over-separated is readable, silently glued is not
    (is (= "one\ntwo" (screen/of [:div [:whatever "one"] [:aside "two"]])))))

(deftest what-a-reader-cannot-afford-to-lose
  (testing "a seq child is a FRAGMENT, and dropping it reads as an empty section"
    ;; three spellings, all ordinary hiccup, all meaning the same thing
    (is (= "<ul slopp:count=\"2\">\n  <li>a</li>\n  <li>b</li>\n</ul>"
           (screen/of (into [:ul] (for [x ["a" "b"]] [:li x])))))
    (is (= "a\nb" (screen/of [:div (for [x ["a" "b"]] [:p x])])))
    (is (= "a\nb" (screen/of [:div (list (list [:p "a"] [:p "b"]))]))))

  (testing "an :href always travels — where a thing points is half of every check"
    (is (= "<a href=\"/store\">Code</a>" (screen/of [:p [:a {:href "/store"} "Code"]]))))

  (testing "a :class NEVER travels — style is a claim a text readout cannot honour"
    ;; the overlay story lives in the svg census below, where class is
    ;; capability vocabulary rather than styling; everywhere else dropping it
    ;; is what makes sugar verifiable (:h1.big ≡ [:h1 {:class \"big\"}])
    (is (= "x" (screen/of [:p [:span {:class "tint-3"} "x"]]))))

  (testing "a list is COUNTED, and the tool's cap is a machine-visible tag"
    (let [big (into [:ul] (for [i (range 32)] [:li (str "row " i)]))]
      (is (= "<ul slopp:count=\"32\">" (first (screen/lines big {:list-head 3})))
          "32 rows is a wall a reader skims; the count is one line they cannot")
      (is (= ["  <slopp:elided count=\"29\"/>" "</ul>"]
             (vec (take-last 2 (screen/lines big {:list-head 3}))))
          "the truncation is a TAG, so an assertion can never be eaten silently")
      (is (= 34 (count (screen/lines big)))
          "and the TEST path elides nothing by default — a test's tokens are cheap, its false failure is not")))

  (testing "an svg is censused by CLASS and never descended"
    ;; a path is an edge in one place and a sketched box in another, so the tag
    ;; says nothing; `2 gap-w0, 1 gap-w4` IS the tint check, with no pixels
    (let [g [:svg {:class "module-graph"}
             [:path {:class "module-link"} "M 0 0 C 12 40, 88 60, 88 100"]
             [:g {:class "gap-w0"}] [:g {:class "gap-w0"}] [:g {:class "gap-w4"}]]]
      (is (= "<svg class=\"module-graph\">2 gap-w0, 1 gap-w4, 1 module-link</svg>"
             (screen/of g)))
      (is (not (re-find #"88 100" (screen/of g)))
          "coordinates are a screen of context that answers nothing"))))

(deftest a-region-that-is-not-there-REFUSES
  ;; The addition to slopp-ui's design, and it comes from their own near-miss:
  ;; a tint assertion matched its class pattern anywhere on the page, so it
  ;; claimed the diagram, checked a list, and stayed GREEN with the layout torn
  ;; out. A readout makes assertions easy to write and therefore easy to write
  ;; too broadly. Scoping is the fix, and a scope that silently returns nothing
  ;; is worse than no scope at all — every absence assertion downstream of it
  ;; passes, over a screen that may have rendered nothing.
  (let [screen [:div
                [:nav {:data-region "nav"} [:a {:href "/"} "Review"]]
                [:main {:data-region "main"}
                 [:h1 "code"]
                 [:svg {:class "module-graph"} [:g {:class "gap-w4"}]]]]]
    (testing "a region scopes to its own lines and nothing else"
      (is (= ["<h1>code</h1>" "<svg class=\"module-graph\">1 gap-w4</svg>"]
             (mapv str/triml (screen/lines screen {:region "main"}))))
      (is (= ["<a href=\"/\">Review</a>"]
             (mapv str/triml (screen/lines screen {:region "nav"})))))

    (testing "a region that is not on the screen THROWS, and names the ones that are"
      (let [e (try (screen/lines screen {:region "sidebar"}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "a missing region is a finding, never an empty scope")
        (is (str/includes? (ex-message e) "regions present: nav, main")
            "the list IS the answer to the question behind the mistake")))

    (testing "TWO regions under one name refuse like an ambiguous click"
      (let [dup [:div
                 [:section {:data-region "card"} [:p "first"]]
                 [:section {:data-region "card"} [:p "second"]]]
            e   (try (screen/lines dup {:region "card"}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "silently taking the first lets an assertion pass against the wrong pane")
        (is (str/includes? (ex-message e) "2 regions"))))

    (testing "and a screen with no regions at all says THAT, not the same message"
      (let [e (try (screen/lines [:div [:p "x"]] {:region "main"}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (str/includes? (ex-message e) ":data-region")
            "an app that has never addressed a pane needs the mechanism, not a list")))))

(deftest clicking-runs-the-apps-OWN-handler-on-the-jvm
  ;; THE founding case, and the bar the earlier designs failed. A contract where
  ;; the app hands back {:visit :click} makes the APP write the fake browser —
  ;; every project builds its own adapter, that adapter is the least-exercised
  ;; code in the project, and it is free to drift from the real browser path.
  ;; "the agent writes the driver" and "the agent writes the test" are the same
  ;; failure, and it is the failure this whole exercise exists to remove.
  ;;
  ;; So the browser dispatches, and the handler under it is the app's own —
  ;; an ordinary Clojure fn sitting in the tree, exactly as it sits there for
  ;; reagent. Nothing is injected, nothing is simulated, and the state it
  ;; changes is the state the real client would change.
  (let [state (atom {:n 0})
        page  {:state state
               :view  (fn [s]
                        [:div
                         [:p (str "n=" (:n s))]
                         [:button {:on-click #(swap! state update :n inc)} "Add"]])}
        b     (screen/open! page)]
    (testing "the document renders from state before anything happens"
      ;; the <button> tag is the readout saying the button is a button — a
      ;; reader deciding what to do next could not otherwise tell it from a
      ;; paragraph
      (is (= "n=0\n<button slopp:on=\"click (fn)\">Add</button>"
             (screen/of (screen/tree b)))))
    (testing "a click fires the app's handler and the document changes"
      (screen/click! b "Add")
      (is (= "n=1\n<button slopp:on=\"click (fn)\">Add</button>"
             (screen/of (screen/tree b))))
      (screen/click! b "Add")
      (is (= "n=2\n<button slopp:on=\"click (fn)\">Add</button>"
             (screen/of (screen/tree b)))
          "and the session KEEPS state, the way a browser does between clicks"))))

(deftest following-a-link-is-what-clicking-one-means
  ;; The one opinion a browser holds that an app cannot override. Everything
  ;; else about a url — which screen, which params, what loads — goes through
  ;; the page's own :navigate, which is ONE function and deliberately not a
  ;; router. slopp never learns what "/store" means.
  (let [state (atom {:at "/"})
        page  {:state    state
               :navigate (fn [s path] (assoc s :at path))
               :view     (fn [s]
                           [:div
                            [:h1 (:at s)]
                            [:a {:href "/store"} "Code"]])}
        b     (screen/open! page)]
    (testing "an href with no handler navigates through the app's own :navigate"
      (screen/click! b "Code")
      (is (= "<h1>/store</h1>\n<a href=\"/store\">Code</a>"
             (screen/of (screen/tree b)))))
    (testing "and a link can be clicked by its address as well as its label"
      (screen/visit! b "/")
      (screen/click! b "/store")
      (is (= "/store" (:at @state))))))

(deftest a-click-that-cannot-be-honest-REFUSES
  ;; Four ways a click can be dishonest, which a forgiving browser would
  ;; collapse into one silent no-op. The second is the afternoon-waster,
  ;; because the word IS on the screen and the reader can see it there —
  ;; and since clicks BUBBLE now, the refusal is a statement about the
  ;; element and everything above it, not one node's attrs.
  (let [page {:state (atom {})
              :view  (fn [_]
                       [:div
                        [:p "Save"]
                        [:button {:on-click (fn [_])} "Delete"]
                        [:button {:on-click (fn [_])} "Delete"]
                        [:a {:href "/only"} "Go"]])}
        b    (screen/open! page)
        msg  (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))]
    (testing "nothing says it — and the answer is what CAN be clicked"
      (let [m (msg #(screen/click! b "Nope"))]
        (is (str/includes? m "nothing on this screen says"))
        (is (str/includes? m "Delete")
            "the list is the answer to the question behind the mistake")))

    (testing "it is on the screen but nothing over it handles a click"
      (is (str/includes? (msg #(screen/click! b "Save"))
                         "neither it nor anything above it handles a click")
          "a different bug from 'not found', and it must not read as one"))

    (testing "two distinct controls say it — picking one is a guess"
      (is (str/includes? (msg #(screen/click! b "Delete"))
                         "picking one of them is a guess")))

    (testing "and a page with no :navigate refuses a visit rather than rendering nothing"
      (is (str/includes? (msg #(screen/visit! b "/x"))
                         "declares neither :navigate nor :web/routes")))))

(deftest a-server-rendered-app-needs-no-page-declaration-at-all
  ;; The case slopp.web is actually built for, and it must not be the awkward
  ;; one. A page mounted at a url, gone to directly — no SPA, no client router,
  ;; no state to speak of. The route table already exists, `dispatch/handle!`
  ;; is already callable in-process, and the browser should USE them rather
  ;; than ask an app to restate what the framework knows.
  ;;
  ;; So `open` takes the app's own ctx — the same map `slopp.web/serve!` runs
  ;; on — and a visit is a REAL request through the REAL pipeline. `:auth
  ;; :public` is here because that pipeline is default-deny and this fixture
  ;; would otherwise 401: the browser inherits every guarantee the served app
  ;; has, which is the argument for driving dispatch instead of the handler.
  (let [page (fn [body] {:status 200 :body body})
        ctx  {:web/routes
              [{:method :get :path "/" :auth :public :handler
                (fn [_] (page [:div [:h1 "Home"] [:a {:href "/about"} "About"]]))}
               {:method :get :path "/about" :auth :public :handler
                (fn [_] (page [:div [:h1 "About"]]))}
               {:method :get :path "/secret" :auth :authenticated :handler
                (fn [_] (page [:div [:h1 "Secret"]]))}]}
        b    (screen/open! ctx)]
    (testing "visiting a mounted path renders that page"
      (screen/visit! b "/")
      (is (= "<h1>Home</h1>\n<a href=\"/about\">About</a>"
             (screen/of (screen/tree b)))))

    (testing "and a link goes there, through the router — no :navigate anywhere"
      (screen/click! b "About")
      (is (= "<h1>About</h1>" (screen/of (screen/tree b)))))

    (testing "a path the app does not mount reads as the 404 it is"
      (screen/visit! b "/nope")
      (is (str/includes? (screen/of (screen/tree b)) "404")
          "the status is the finding — a blank screen would read as a broken page"))

    (testing "and the app's own auth policy applies, because this IS the pipeline"
      (screen/visit! b "/secret")
      (is (str/includes? (screen/of (screen/tree b)) "401")
          "an anonymous visit to a protected page is 401 here exactly as it is served"))))

(deftest the-scoped-assertion-is-the-shorter-one-to-write
  ;; A helper is only a helper if the RIGHT thing is the easy thing. The whole
  ;; failure this exercise came from is an assertion written too broadly —
  ;; matching a class pattern anywhere on the page, claiming the diagram,
  ;; checking a list, and staying green with the layout torn out. So the
  ;; region-scoped call is one argument, not one argument plus an options map.
  (let [page {:state (atom {})
              :view  (fn [_]
                       [:div
                        [:nav {:data-region "nav"} [:a {:href "/"} "Review"]]
                        [:main {:data-region "main"}
                         [:h1 "code"]
                         [:svg {:class "module-graph"} [:g {:class "gap-w4"}]]]])}
        b    (screen/open! page)]
    (testing "the page, when the page is what you mean"
      (is (str/includes? (screen/text b) "<a href=\"/\">Review</a>")))

    (testing "one region, in one argument — and it comes back dedented"
      (is (= "<h1>code</h1>\n<svg class=\"module-graph\">1 gap-w4</svg>"
             (screen/text b "main")))
      (is (= "<a href=\"/\">Review</a>" (screen/text b "nav"))))

    (testing "options still reach through — the overlay case is the census's job"
      (is (str/includes? (screen/text b "main" {:detail :prose}) "code")))

    (testing "and a region that is not there refuses rather than scoping to nothing"
      (is (thrown? clojure.lang.ExceptionInfo (screen/text b "sidebar"))))))

(deftest prose-drops-the-structure-and-keeps-the-sentences
  ;; Most assertions are "does it say X", and they pay for addresses, counts,
  ;; region markers and an svg census they never read. On a real page that is
  ;; most of the bytes.
  ;;
  ;; What it must NOT become is the naive flatten this feature exists to
  ;; replace: every string joined by spaces, where a wrong sentence hides in a
  ;; run-on line. So :prose drops the TAGS and keeps the LINE STRUCTURE — the
  ;; boundary between two sentences is the whole point. Prose is also the one
  ;; mode that never escapes: it makes no structural claims, so it has nothing
  ;; to be confused with.
  (let [page [:div
              [:main {:data-region "main"}
               [:h1 "code"]
               [:p "3 modules, 4 namespaces"]
               [:a {:href "/store"} "Code"]
               [:svg {:class "module-graph"} [:g {:class "gap-w4"}]]
               (into [:ul] (for [i (range 6)] [:li (str "row " i)]))]]]
    (testing "no region wrapper, no heading tag, no address, no census"
      ;; :list-head 3 is the TOOL's cap, passed explicitly — the test path
      ;; defaults to every row. The svg is named in WORDS: prose is unescaped,
      ;; so an angle-bracketed marker here would be the v1 flaw surviving in
      ;; the one mode where nothing could distinguish it from content.
      (is (= (str "code\n"
                  "3 modules, 4 namespaces\n"
                  "Code\n"
                  "svg module-graph\n"
                  "row 0\nrow 1\nrow 2\n"
                  "+3 more")
             (screen/of page {:detail :prose :list-head 3}))))

    (testing "a block still owns its line — this is not the flatten"
      (is (not (str/includes? (screen/of page {:detail :prose})
                              "code 3 modules"))
          "a wrong sentence in a run-on line is invisible, which is the whole point"))

    (testing "the truncation still SAYS so when a cap is asked for"
      (is (str/includes? (screen/of page {:detail :prose :list-head 3}) "+3 more")
          "a cap that went quiet would be a report lying about its own scope"))

    (testing "an svg still marks its place, in words, so a picture does not read as nothing"
      (is (str/includes? (screen/of page {:detail :prose}) "svg module-graph"))
      (is (not (str/includes? (screen/of page {:detail :prose}) "<svg"))
          "prose is unescaped, so brackets here would be v1's flaw surviving"))

    (testing "and it composes with region scoping"
      (is (str/starts-with? (screen/of page {:detail :prose :region "main"}) "code")))))

(deftest what-can-be-clicked-says-so
  ;; An agent looking at a screen is usually deciding what to do NEXT, and
  ;; "what can I click" was unanswerable: a [:button {:on-click f} "Add"]
  ;; rendered identically to [:p "Add"]. In v2 capability keeps the TAG —
  ;; a button is a <button>, a link is an <a href>, and a handler on any
  ;; element at all keeps that element's tag so slopp:on has a place to ride.
  (let [page [:div
              [:p "Save"]
              [:button {:on-click (fn [_])} "Add"]
              [:a {:href "/store"} "Code"]
              [:div {:on-click (fn [_])} [:span "Card"]]]]
    (testing "capability keeps the tag; a closure handler is honestly opaque"
      (is (= (str "Save\n"
                  "<button slopp:on=\"click (fn)\">Add</button>\n"
                  "<a href=\"/store\">Code</a>\n"
                  "<div slopp:on=\"click (fn)\">Card</div>")
             (screen/of page))))

    (testing "an unprefixed attr was on the page; the derived one is slopp:*"
      (is (not (str/includes? (screen/of page) "slopp:on=\"click (fn)\" href"))
          "href is the page's own fact and never doubles as the derived annotation"))

    (testing "prose stays prose"
      (is (= "Save\nAdd\nCode\nCard" (screen/of page {:detail :prose}))
          ":prose answers 'does it say X' and pays for nothing else"))))

(deftest typing-into-a-field-runs-the-apps-own-handler
  ;; Clicking alone is not a browser. A form is the other half of nearly every
  ;; screen, and an app whose filter box cannot be exercised headlessly is back
  ;; to a hand-built lookalike for exactly the interaction most likely to be
  ;; wrong.
  ;;
  ;; A field has no visible TEXT to name it by, so it is addressed the way a
  ;; person would: its placeholder, its name, its id, or its aria-label.
  (let [state (atom {:q ""})
        page  {:state state
               :view  (fn [s]
                        [:div
                         [:input {:placeholder "Filter"
                                  :on-change #(swap! state assoc :q (:value %))}]
                         [:p (str "showing " (:q s))]])}
        b     (screen/open! page)]
    (testing "the handler receives the value as DATA and the document changes"
      (screen/fill! b "Filter" "store")
      (is (str/includes? (screen/of (screen/tree b)) "showing store")))

    (testing "a field that is not there refuses, and says what can be filled"
      (let [m (try (screen/fill! b "Nope" "x") nil
                   (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (str/includes? m "Filter")
            "the list is the answer to the question behind the mistake")))

    (testing "and a field with no :on-change is a different bug from a missing one"
      (let [b2 (screen/open! {:state (atom {})
                             :view (fn [_] [:input {:placeholder "Inert"}])})
            m  (try (screen/fill! b2 "Inert" "x") nil
                    (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (str/includes? m "no :on-change"))))))

(deftest a-field-is-visible-and-says-it-can-be-filled
  ;; Worse than unmarked: an [:input] has no CHILDREN, so the first readout
  ;; showed nothing at all where a search box was. An agent reading that screen
  ;; would conclude the app has no filter.
  ;;
  ;; In v2 a field is an `<input>` with every attr fill! can address by —
  ;; the old format showed the FIRST addressing attr, and the review's lead
  ;; finding was its mirror (a field the screen denied while fill! drove it).
  ;; Fillability is the slopp:on annotation; a control state a browser shows
  ;; (value, checked) is an unprefixed attr because it was really on the page.
  (let [page [:form
              [:input {:placeholder "Filter" :value "store" :on-change (fn [_])}]
              [:input {:name "email" :on-change (fn [_])}]
              [:input {:type "checkbox" :aria-label "Agree" :on-change (fn [_])}]
              [:input {:placeholder "Inert"}]
              [:textarea {:name "notes" :on-change (fn [_])}]]]
    (testing "a field is VISIBLE, with its addresses, state, and handler"
      (is (= (str "<form>\n"
                  "  <input placeholder=\"Filter\" value=\"store\" slopp:on=\"change (fn)\"/>\n"
                  "  <input name=\"email\" slopp:on=\"change (fn)\"/>\n"
                  "  <input type=\"checkbox\" aria-label=\"Agree\" slopp:on=\"change (fn)\"/>\n"
                  "  <input placeholder=\"Inert\"/>\n"
                  "  <textarea name=\"notes\" slopp:on=\"change (fn)\"/>\n"
                  "</form>")
             (screen/of page))))

    (testing "an inert field shows WITHOUT slopp:on — that is the finding, not a gap"
      (is (str/includes? (screen/of page) "<input placeholder=\"Inert\"/>")))

    (testing "and prose keeps the field's name but drops the tags"
      (is (= "Filter\nemail\nAgree\nInert\nnotes" (screen/of page {:detail :prose}))))))

(deftest a-handler-runs-whichever-idiom-the-tree-uses
  ;; CHECKED against both libraries rather than inferred from one app, because
  ;; the first version of this supported one idiom and could not drive a real
  ;; app at all.
  ;;
  ;; Reagent: `[:button {:on-click (fn [e] …)}]` — a FUNCTION in the tree.
  ;; Replicant: `[:button {:on {:click …}}]` — a function OR DATA, and data
  ;; goes to one global dispatcher registered with `replicant.dom/set-dispatch!`.
  ;;
  ;; BOTH put the handler ON THE ELEMENT (bubbling — a click on text INSIDE
  ;; one reaching it — is DOM semantics and supported; what stays unsupported
  ;; is hand-rolled `document.addEventListener` delegation, which lives in
  ;; :cljs and never runs here).
  (let [seen  (atom [])
        state (atom {:n 0})
        page  {:state    state
               :dispatch (fn [action value] (swap! seen conj [action value]))
               :view     (fn [_]
                           [:div
                            [:button {:on-click #(swap! state update :n inc)} "Reagent"]
                            [:button {:on {:click #(swap! state update :n inc)}} "Fn"]
                            [:button {:on {:click [:like-video 7]}} "Data"]])}
        b     (screen/open! page)]
    (testing "a Reagent-style function on the element"
      (screen/click! b "Reagent")
      (is (= 1 (:n @state))))

    (testing "a function under Replicant's :on map"
      (screen/click! b "Fn")
      (is (= 2 (:n @state))))

    (testing "DATA under :on reaches dispatch VERBATIM, with no event invented"
      ;; slopp passes (action value) and nothing else. Handing over an event
      ;; MAP was the first design and it was wrong in the worst direction:
      ;; Replicant's real event map carries :replicant/dom-event and no :value,
      ;; so a handler reading (:value e) would have passed here and done
      ;; nothing in a browser. A test that green-lights production breakage is
      ;; worse than no test.
      (screen/click! b "Data")
      (is (= [[:like-video 7] nil] (last @seen))
          "the action verbatim — it is what a dispatcher switches on — and no value for a click"))

    (testing "all three read as clickable, and the DATA form says what it will do"
      (is (= (str "<button slopp:on=\"click (fn)\">Reagent</button>\n"
                  "<button slopp:on=\"click (fn)\">Fn</button>\n"
                  "<button slopp:on=\"click :like-video 7\">Data</button>")
             (screen/of (screen/tree b)))
          "a serializable action is the only handler shape a readout can report — scalar args included, since they are what tells two controls apart"))

    (testing "data with no :dispatch declared REFUSES, naming the gap"
      (let [b2 (screen/open! {:state (atom {})
                             :view  (fn [_] [:button {:on {:click [:boom]}} "X"])})
            m  (try (screen/click! b2 "X") nil
                    (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (str/includes? m ":dispatch")
            "silently doing nothing would be a click that reported success and changed nothing")))))

(deftest typing-runs-whichever-idiom-the-field-uses
  ;; The sibling of the click case, and it needs its own test because the two
  ;; idioms disagree about the EVENT NAME as well as the shape: Reagent apps
  ;; write :on-change, a Replicant :on map usually names :input. Trying one
  ;; would leave a field inert for half the ecosystem — and inert reads as a
  ;; bug in the app rather than in the reader.
  (let [seen  (atom nil)
        state (atom {:q "" :r ""})
        page  {:state    state
               :dispatch (fn [action value] (reset! seen [action value]))
               :view     (fn [s]
                           [:div
                            [:input {:placeholder "Reagent" :value (:q s)
                                     :on-change #(swap! state assoc :q (:value %))}]
                            [:input {:placeholder "Replicant" :value (:r s)
                                     :on {:input #(swap! state assoc :r (:value %))}}]
                            [:input {:placeholder "Data" :on {:input [:search]}}]])}
        b     (screen/open! page)]
    (testing "Reagent's :on-change"
      (screen/fill! b "Reagent" "abc")
      (is (= "abc" (:q @state))))

    (testing "Replicant's :on {:input …} as a function"
      (screen/fill! b "Replicant" "xyz")
      (is (= "xyz" (:r @state))))

    (testing "and as DATA — the action verbatim, the typed text as a SCALAR"
      ;; No event map. Replicant's real one has no :value: the typed text sits
      ;; behind (.. e -target -value), which is interop and cannot run on a JVM.
      ;; A handler written against an invented {:value v} would pass here and do
      ;; nothing in a browser, and that direction is the one worth refusing.
      (screen/fill! b "Data" "q")
      (is (= [[:search] "q"] @seen)))

    (testing "all three show as fillable, under the event name each one wrote"
      (is (= (str "<input placeholder=\"Reagent\" value=\"abc\" slopp:on=\"change (fn)\"/>\n"
                  "<input placeholder=\"Replicant\" value=\"xyz\" slopp:on=\"input (fn)\"/>\n"
                  "<input placeholder=\"Data\" slopp:on=\"input :search\"/>")
             (screen/of (screen/tree b)))))))

(deftest a-navigate-that-touches-its-own-atom-does-not-LIVELOCK
  ;; The bug slopp-ui hit, and it took a measurement from them to find because
  ;; every fixture here had a PURE :navigate. Theirs calls their SPA loop, which
  ;; swaps the same state atom — the ordinary shape for a real app.
  ;;
  ;; `visit!` called the app's :navigate INSIDE (swap! state nav path). swap!
  ;; retries its function whenever the CAS loses, so it requires a pure one: an
  ;; inner swap! on the same atom changes the value mid-computation, the outer
  ;; CAS fails, it retries, the inner swaps again — forever, at full CPU.
  ;;
  ;; It presents as a HANG, not an error. Theirs ran 127 seconds and then
  ;; reported "the run did not happen"; query_eval answered [] because the image
  ;; was pinned. Their own loop, measured outside the driver, is 1.4 ms.
  ;;
  ;; **The fixture has to CHANGE something on every pass**, and the first cut of
  ;; this test did not — it assoc'd a constant, and `assoc` returns the
  ;; identical map when the value is already there by identity, so the retry
  ;; converged and the test went green over a live livelock. A real navigate
  ;; mints something fresh each time (their nav/begin-load makes a load TOKEN),
  ;; which is precisely why theirs never converged and mine did.
  ;;
  ;; The general rule, and why this is a design bug rather than a typo:
  ;; **never call an app-supplied function inside swap!.** slopp cannot know
  ;; what an app's fn does, and swap!'s contract says it must do nothing.
  (let [st   (atom {:n 0})
        page {:state    st
              :view     (fn [s] [:p (str "at=" (:at s) " n=" (:n s))])
              ;; a real SPA loop: mutate the atom, mint something fresh, hand
              ;; back the new value
              :navigate (fn [_ path]
                          (swap! st #(-> % (update :n inc) (assoc :at path)))
                          @st)}
        s    (screen/open! page)
        f    (future (screen/visit! s "/store") :done)]
    (is (= :done (deref f 3000 :TIMED-OUT))
        "a :navigate that touches its own atom livelocked inside swap! — it presents as a hang, and a hang has no message")
    (is (= 1 (:n @st))
        "and it ran ONCE: a retry loop would have incremented this a great many times")
    (is (= "at=/store n=1" (screen/of (screen/tree s)))
        "and the navigation actually happened")))

(deftest an-action-shows-the-arguments-that-DISTINGUISH-it
  ;; Reported from a real screen: two buttons carrying [:docs/all true] and
  ;; [:docs/all false] both rendered the action kind alone, so the readout said
  ;; the rail had two identical controls. The kind alone is right where the
  ;; argument is an id and noise; it is wrong where the argument IS the whole
  ;; difference, and nothing in a tree tells you which.
  ;;
  ;; So: SCALARS travel, everything else is elided. An enum, a flag or a name
  ;; is what one button has and its neighbour does not; an entity passed whole
  ;; is the case that motivated showing only the kind, and it stays hidden.
  (let [page {:state    (atom {})
              :dispatch (fn [_ _])
              :view     (fn [_]
                          [:div
                           [:button {:on {:click [:docs/all true]}}  "expand all"]
                           [:button {:on {:click [:docs/all false]}} "collapse all"]
                           [:button {:on {:click [:like-video {:id 7 :title "x"}]}} "Like"]
                           [:button {:on {:click [:save]}} "Save"]])}]
    (testing "scalar arguments travel, because they are what tells two controls apart"
      (is (= (str "<button slopp:on=\"click :docs/all true\">expand all</button>\n"
                  "<button slopp:on=\"click :docs/all false\">collapse all</button>\n"
                  "<button slopp:on=\"click :like-video …\">Like</button>\n"
                  "<button slopp:on=\"click :save\">Save</button>")
             (screen/of (screen/tree (screen/open! page))))))))

(deftest the-structured-format-is-text-with-a-whitelisted-tag-channel
  ;; v2 contract, settled 2026-08-05: plain text stays plain; a tag keeps its
  ;; angle brackets only when it carries a fact an agent acts on or asserts
  ;; that its text alone does not say (interactive, enumerable, structural).
  ;; The provenance rule does the rest: an UNPREFIXED tag or attr was really
  ;; on the page; anything slopp derived is slopp:*-prefixed. This replaces
  ;; the invented marker syntax (`#`, `[click]`, `§`, `×N`) whose fatal flaw
  ;; was sharing an alphabet with page text.
  (let [view [:div {:data-region "main"}
              [:h1.page-title "orders"]
              [:p "3 open, " [:strong "1 overdue"]]
              [:p "docs: " [:a {:href "/docs"} "read me"]]
              [:input.search {:placeholder "filter orders" :on {:input [:orders/filter]}}]
              [:ul (for [i (range 5)] [:li (str "row " i)])]
              [:button {:disabled true :on {:click [:orders/expand true]}} "expand all"]
              [:svg {:class "chart"} [:path.bar] [:path.bar]]]
        s (screen/of view)]
    (testing "headings are real tags, not markdown"
      (is (str/includes? s "<h1>orders</h1>")))
    (testing "inline emphasis is text only — strong/em/span carry no brackets"
      (is (str/includes? s "3 open, 1 overdue")))
    (testing "a link keeps its tag and href, inline in its sentence"
      (is (str/includes? s "docs: <a href=\"/docs\">read me</a>")))
    (testing "a field shows its addressing attrs and its handler as slopp:on"
      (is (str/includes? s "<input placeholder=\"filter orders\" slopp:on=\"input :orders/filter\"/>")))
    (testing "a list carries its real count, and the test path elides nothing"
      (is (str/includes? s "<ul slopp:count=\"5\">"))
      (is (str/includes? s "<li>row 4</li>"))
      (is (str/includes? s "</ul>")))
    (testing "a disabled control says so, and a data action's scalar args travel"
      (is (str/includes? s "<button disabled slopp:on=\"click :orders/expand true\">expand all</button>")))
    (testing "an svg is censused by class, never descended"
      (is (str/includes? s "<svg class=\"chart\">2 bar</svg>")))
    (testing "a region is a slopp: wrapper — derived by the reader, so prefixed"
      (is (str/includes? s "<slopp:region name=\"main\">"))
      (is (str/includes? s "</slopp:region>")))
    (testing "class and id never reach the output, so sugar and plain spellings render identically"
      (is (= (screen/of [:div [:h1.big "t"]])
             (screen/of [:div [:h1 {:class "big"} "t"]]))))))

(deftest page-text-that-looks-like-syntax-cannot-be-confused-with-it
  ;; The old format's markers were plain text, so a page containing "# xyz" or
  ;; "[click]" was unfalsifiable — and the first real consumer renders CLOJURE
  ;; SOURCE, a domain made of #, [...] and angle brackets. v2 escapes & < > in
  ;; every text node and attr value; the only raw angle brackets in the output
  ;; are the reader's own tag channel.
  (let [s (screen/of [:div
                      [:p "# xyz"]
                      [:p "grep for [click] in the source"]
                      [:p "a < b & c > d"]
                      [:pre "(defn f [] \"<ul>\")"]])]
    (testing "old-syntax lookalikes are inert text now — no marker grammar exists to collide with"
      (is (str/includes? s "# xyz"))
      (is (str/includes? s "grep for [click] in the source")))
    (testing "angle brackets and ampersands in page text are escaped"
      (is (str/includes? s "a &lt; b &amp; c &gt; d")))
    (testing "pre content is verbatim lines, escaped, never squeezed"
      (is (str/includes? s "<pre>"))
      (is (str/includes? s "(defn f [] \"&lt;ul&gt;\")"))
      (is (str/includes? s "</pre>"))
      (is (not (str/includes? s "(defn f [] \"<ul>\")"))))
    (testing "a multi-line pre yields one real line per source line"
      (let [ls (mapv str/triml (screen/lines [:div [:pre "line1\nline2"]]))]
        (is (= ["<pre>" "line1" "line2" "</pre>"] ls))))))

(deftest clicking-behaves-like-a-browser-not-a-matcher
  ;; Three review findings, one root: target-node modeled clicks as label
  ;; matching, where a browser models them as events on a tree. A click on
  ;; text INSIDE a handled element fires that handler (bubbling — not
  ;; delegation, which stays unsupported); a disabled control fires nothing
  ;; and says why; an aria-label is an address for a control whose only
  ;; content is an icon — the same attr fill! already honours.
  (let [hits (atom [])
        page (fn [] {:state (atom {})
                     :view (fn [_]
                             [:div
                              [:div {:on {:click [:open-card]}}
                               [:span "icon"] [:span "Open"]]
                              [:button {:disabled true :on {:click [:save]}} "Save"]
                              [:button {:aria-label "close" :on {:click [:close]}} [:svg {:class "x"}]]])
                     :dispatch (fn [a _] (swap! hits conj a))})]
    (testing "a click on text inside a handled ancestor bubbles to it"
      (reset! hits [])
      (screen/click! (screen/open! (page)) "Open")
      (is (= [[:open-card]] @hits)))
    (testing "a disabled control refuses and runs nothing — production would not fire it"
      (reset! hits [])
      (let [e (try (screen/click! (screen/open! (page)) "Save") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "disabled"))
        (is (= [] @hits))))
    (testing "aria-label addresses an icon-only control"
      (reset! hits [])
      (screen/click! (screen/open! (page)) "close")
      (is (= [[:close]] @hits)))))

(deftest the-tree-is-read-the-way-the-libraries-run-it
  ;; Review findings F1/F4/F12/F13 share a root: the reader held a private
  ;; model of hiccup narrower than the one the libraries execute. A component
  ;; vector is CALLED (form-1) and called again when it returns a render fn
  ;; (form-2) — Reagent's own semantics, not a special case. A fragment
  ;; splices. Sugar id/class are attrs, so a sugared field is addressable.
  ;; And the label a screen shows is byte-identical to the label a click
  ;; accepts, because both come from the ONE text function.
  (testing "a fragment splices its children into the sentence"
    (is (= "a x b" (screen/of [:p "a " [:<> [:b "x"]] " b"]))))
  (testing "a component vector is called, as the libraries call it"
    (is (= "hello" (screen/of [:div [(fn [t] [:p t]) "hello"]]))))
  (testing "a form-2 component's returned render fn runs with the same args"
    (is (= "hi" (screen/of [:div [(fn [_] (fn [t] [:p t])) "hi"]]))))
  (testing "sugar id is an attr, so a sugared field is addressable and shown"
    (let [got (atom nil)
          ss  (screen/open! {:state (atom {})
                            :view (fn [_] [:div [:input#q {:on {:input [:q/set]}}]])
                            :dispatch (fn [_ v] (reset! got v))})]
      (is (str/includes? (screen/text ss nil) "<input id=\"q\""))
      (screen/fill! ss "q" "web")
      (is (= "web" @got))))
  (testing "the shown label IS the clickable label"
    (let [n  (atom 0)
          ss (screen/open! {:state n
                           :view (fn [_] [:div [:button {:on-click (fn [_] (swap! n inc))} "foo" [:span "bar"]]])})]
      (is (str/includes? (screen/text ss nil) ">foobar</button>"))
      (screen/click! ss "foobar")
      (is (= 1 @n)))))

(deftest a-script-step-that-cannot-run-says-which-and-why
  ;; The review's most-found bug: drive!'s :else reported (keys steps) — the
  ;; whole VECTOR — so every typo'd step died as "PersistentArrayMap cannot be
  ;; cast to Map$Entry", a refusal that answers nothing, in the interpreter
  ;; every screen tool script runs through. It was also the one form with no
  ;; direct test, which is how a broken refusal ships: fixtures agree with
  ;; their authors, and an untested path agrees with everybody.
  (let [page (fn [seen] {:state    (atom {})
                         :view     (fn [_] [:div
                                            [:input {:placeholder "q" :on {:input [:q/set]}}]
                                            [:button {:on {:click [:go]}} "Go"]])
                         :dispatch (fn [a v] (swap! seen conj [a v]))})
        msg  (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))]
    (testing "a typo'd step names ITS keys and the step vocabulary"
      (let [m (msg #(screen/drive! (screen/open! (page (atom []))) [{:vist "/x"}]))]
        (is (some? m) "a raw ClassCastException is not a refusal")
        (is (str/includes? m ":visit, :click or :fill"))
        (is (str/includes? m ":vist") "the offending step's own keys, not the script's")))
    (testing "a step naming two actions refuses — running one silently is a guess"
      (let [m (msg #(screen/drive! (screen/open! (page (atom []))) [{:visit "/a" :click "Go"}]))]
        (is (str/includes? m "one action"))))
    (testing "a :fill step with no :value refuses — typing nothing is not a step"
      (let [m (msg #(screen/drive! (screen/open! (page (atom []))) [{:fill "q"}]))]
        (is (str/includes? m ":value"))))
    (testing "steps that are not maps refuse readably"
      (let [m (msg #(screen/drive! (screen/open! (page (atom []))) "hi"))]
        (is (str/includes? m "steps"))))
    (testing "a good script runs in order and returns the session"
      (let [seen (atom [])
            s    (screen/drive! (screen/open! (page seen))
                                [{:fill "q" :value "web"} {:click "Go"}])]
        (is (= [[[:q/set] "web"] [[:go] nil]] @seen))
        (is (some? s))))))

(deftest a-page-that-cannot-open-says-so-at-open
  ;; Review F6: open's docstring says :state and :view are REQUIRED and the
  ;; code checked nothing — a page missing :state died later as "Cannot invoke
  ;; Future.get() because fut is null", and {:vew …} rendered a BLANK PAGE,
  ;; which sends a reader hunting a rendering bug in an app that was never
  ;; wired. Validation belongs at the constructor, where the mistake was made.
  (let [msg (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))]
    (testing "a page missing a required key is refused, naming it"
      (is (str/includes? (msg #(screen/open! {:view (fn [_] [:div])})) ":state"))
      (is (str/includes? (msg #(screen/open! {:state (atom {})})) ":view")))
    (testing "an unknown page key is refused — a typo'd :view is a blank page otherwise"
      (let [m (msg #(screen/open! {:state (atom {}) :vew (fn [_] [:div])}))]
        (is (some? m))
        (is (str/includes? m ":vew"))))
    (testing ":state must be something deref-and-reset can drive"
      (is (str/includes? (msg #(screen/open! {:state {} :view (fn [_] [:div])})) "atom")))
    (testing "a ctx passes through untouched — its shape is slopp.web's business"
      (is (some? (screen/open! {:web/routes []})))
      (is (some? (screen/open! {:web/routes [] :state (atom {}) :view (fn [_] [:div])}))
          "an app may be both, and the ctx's other keys are not page typos"))))

(deftest a-url-is-split-the-way-a-browser-sends-it
  ;; Review F3: visit! passed the raw path as :uri, and Ring's :uri never
  ;; contains "?" — so /search?q=web 404'd on a mounted route that works, and
  ;; every pagination or filter link in a real app read as a broken route.
  (let [ctx {:web/routes
             [{:method :get :path "/search" :auth :public :handler
               (fn [req] {:status 200
                          :body [:div [:h1 "Search"]
                                 [:p (or (:query-string req) "none")]]})}]}
        b   (screen/open! ctx)]
    (testing "query params reach the handler as :query-string, not a 404"
      (screen/visit! b "/search?q=web")
      (let [s (screen/of (screen/tree b))]
        (is (str/includes? s "Search"))
        (is (str/includes? s "q=web"))))
    (testing "a fragment is never sent — a browser strips it before the wire"
      (screen/visit! b "/search#top")
      (is (str/includes? (screen/of (screen/tree b)) "none"))))

  (let [page {:state (atom {:at "/"})
              :navigate (fn [s p] (assoc s :at p))
              :view  (fn [s] [:div [:h1 (:at s)]
                              [:a {:href "https://example.com"} "Docs"]
                              [:a {:href "#top"} "Top"]])}
        b    (screen/open! page)]
    (testing "an external url refuses — a headless session has nowhere to go"
      (let [m (try (screen/click! b "Docs") nil
                   (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (some? m) "handing https://… to a client router is wrong for both sides")
        (is (str/includes? m "leaves the app"))))
    (testing "a fragment-only href is a scroll, which is a no-op here"
      (screen/click! b "Top")
      (is (= "/" (:at @(:state (:app @b)))) "no navigation happened, and nothing threw"))))

(deftest a-handler-arity-is-read-off-the-function-correctly
  ;; Review F2: the probe looked for a DECLARED 1-param invoke. A rest-arg fn
  ;; compiles to RestFn (only doInvoke); with-meta wraps in an AFunction$1 —
  ;; which IS a RestFn of requiredArity 0. Both are valid one-arg handlers in
  ;; any browser, and both got "Wrong number of args (0)" — the manufactured
  ;; signature mismatch the reflection was chosen to avoid.
  (testing "a variadic handler receives the event"
    (let [got (atom ::never)
          b   (screen/open! {:state (atom {})
                            :view  (fn [_] [:button {:on-click (fn [e & _more] (reset! got e))} "Go"])})]
      (screen/click! b "Go")
      (is (map? @got))))
  (testing "a with-meta wrapped handler receives the event"
    (let [got (atom ::never)
          b   (screen/open! {:state (atom {})
                            :view  (fn [_] [:button {:on-click (with-meta (fn [e] (reset! got e)) {:why "meta"})} "Go"])})]
      (screen/click! b "Go")
      (is (map? @got))))
  (testing "the zero-arg shorthand still runs"
    (let [n (atom 0)
          b (screen/open! {:state n
                          :view  (fn [_] [:button {:on-click #(swap! n inc)} "Go"])})]
      (screen/click! b "Go")
      (is (= 1 @n)))))

(deftest filling-a-select-is-choosing-an-option
  ;; Review F11's driving half. A browser never lets you type into a <select>
  ;; — you choose among its options — so a fill! value no option carries is a
  ;; test asserting a flow production cannot produce. Refusing with the choice
  ;; list is the select's version of the click refusal listing what is
  ;; clickable. A checkbox has no text either way: its value is its checked
  ;; state, passed through as the boolean it is.
  (let [seen (atom nil)
        page {:state    (atom {})
              :dispatch (fn [a v] (reset! seen [a v]))
              :view     (fn [_]
                          [:div
                           [:select {:name "sort" :on {:change [:sort/set]}}
                            [:option {:value "name"} "By name"]
                            [:option {:value "age"} "By age"]]
                           [:input {:type "checkbox" :name "agree" :on {:change [:agree/set]}}]])}
        b    (screen/open! page)]
    (testing "choosing an option a select carries dispatches its value"
      (screen/fill! b "sort" "age")
      (is (= [[:sort/set] "age"] @seen)))
    (testing "a value no option carries refuses, listing the choices"
      (let [m (try (screen/fill! b "sort" "created") nil
                   (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (some? m) "a browser cannot produce this value, so a test must not")
        (is (str/includes? m "name"))
        (is (str/includes? m "age"))))
    (testing "a checkbox takes its checked state as a boolean"
      (screen/fill! b "agree" true)
      (is (= [[:agree/set] true] @seen)))))

(deftest the-pages-own-not-a-control-statements-are-honoured
  ;; slopp-ui's ask A, from a real page: an outline row links the same form
  ;; twice — the name, and a deliberately label-less skeleton anchor carrying
  ;; aria-hidden "true" so screen readers get ONE tab stop. A browser has no
  ;; ambiguity there: exactly one user-reachable control has that href. The
  ;; whitelist admitted <a> for being actionable and then discarded the
  ;; attribute saying it is not — and the refusal's suggested fix (label it)
  ;; was the accessibility bug their comment exists to prevent.
  (let [hits (atom [])
        page {:state    (atom {:at "/"})
              :navigate (fn [s p] (assoc s :at p))
              :dispatch (fn [a _] (swap! hits conj a))
              :view     (fn [_]
                          [:div
                           [:a {:href "/store/form/f1"} "rate"]
                           [:a {:href "/store/form/f1"
                                :aria-hidden "true" :tabindex "-1"}
                            [:pre "(defn rate [w z] …)"]]
                           [:button {:inert true :on {:click [:never]}} "Frozen"]])}
        b    (screen/open! page)]
    (testing "an aria-hidden duplicate does not make a click ambiguous"
      (screen/visit! b "/")
      (screen/click! b "/store/form/f1")
      (is (= "/store/form/f1" (:path @b))
          "one user-reachable control answers, exactly as in a browser"))
    (testing "the readout shows the statement, so a reader can tell the two apart"
      (is (str/includes? (screen/text b nil) "aria-hidden=\"true\"")))
    (testing "an inert control refuses like a disabled one — a browser delivers no events to it"
      (reset! hits [])
      (let [e (try (screen/click! b "Frozen") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "inert"))
        (is (= [] @hits))))))

(deftest the-options-map-is-the-last-entry-that-guessed
  ;; slopp-ui's ask B: open validates, drive! refuses, the tool refuses — and
  ;; text accepted anything. {:detial :prose} silently asserted against
  ;; STRUCTURED output, and most prose assertions pass there too, so the typo
  ;; never surfaces and the test checks something its author did not choose.
  (let [v [:div [:main {:data-region "main"} [:p "hello"]]]]
    (testing "an unknown option refuses, naming it and the vocabulary"
      (let [e (try (screen/lines v {:detial :prose}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "a guessed default is a wrong answer reported as success")
        (is (str/includes? (ex-message e) ":detial"))
        (is (str/includes? (ex-message e) ":detail"))))
    (testing "the removed :attrs option refuses too — it silently ignored"
      (is (thrown? clojure.lang.ExceptionInfo (screen/lines v {:attrs #{:class}}))))
    (testing "a :detail value outside its two words refuses"
      (is (thrown? clojure.lang.ExceptionInfo (screen/lines v {:detail :porse}))))
    (testing "the valid vocabulary still passes"
      (is (= "hello" (screen/of v {:detail :prose :region "main" :list-head 3}))))))

(deftest within-scopes-text-to-the-element-a-click-would-own
  ;; slopp-ui's ask: region is pane-grain, so a question about one ROW meant
  ;; regexing the whole pane — the exact too-broad assertion the region arity
  ;; exists to prevent, one level down. The fix is a unification, not an
  ;; addition: `:within` addresses by the click matcher's vocabulary (visible
  ;; text, href, aria-label) and resolves by its bubbling notion of the
  ;; OWNING element, then renders that subtree instead of clicking it. One
  ;; document, one addressing scheme.
  (let [page {:state (atom {})
              :dispatch (fn [_ _])
              :view  (fn [_]
                       [:main {:data-region "main"}
                        [:ul
                         [:li {:on {:click [:open :rate]}}
                          [:a {:href "/store/form/f1"} "rate"] " " [:span "[kg zone]"]]
                         [:li {:on {:click [:open :band]}}
                          [:a {:href "/store/form/f2"} "band-for"] " " [:span "[kg]"]]]])}
        b    (screen/open! page)]
    (testing "the subtree of the OWNING element — the row, not just the anchor"
      (is (= "rate [kg zone]" (screen/text b nil {:within "rate" :detail :prose}))))
    (testing "addressable by href too, exactly like a click"
      (is (str/includes? (screen/text b nil {:within "/store/form/f2"}) "band-for")))
    (testing "it composes with a region, and misses refuse listing what IS addressable"
      (is (= "rate [kg zone]" (screen/text b "main" {:within "rate" :detail :prose})))
      (let [e (try (screen/text b nil {:within "nope"}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "rate")
            "the list is the answer to the question behind the mistake"))))

  (testing "a disabled control can still be LOOKED at — the act-gates are the click's, not the address's"
    (let [b (screen/open! {:state (atom {})
                          :view (fn [_] [:div [:button {:disabled true :on-click (fn [_])} "Save"]])})]
      (is (str/includes? (screen/text b nil {:within "Save"}) "Save"))))

  (testing "aria-hidden content STAYS in text — it hides nothing visually, and a readout shows the screen"
    ;; decided on purpose (slopp-ui's flag): the click set and the text set
    ;; answer different questions and are allowed to differ. A sighted reader
    ;; sees that span; a readout claiming to show the screen must too.
    (is (= "decorative" (screen/of [:p [:span {:aria-hidden "true"} "decorative"]] {:detail :prose})))
    (is (str/includes? (screen/of [:div [:p "real"] [:span {:aria-hidden "true"} "decorative"]])
                       "decorative"))))

(deftest a-page-declares-what-runs-at-boot-and-open-runs-it
  ;; slopp-ui's <ul ×0>, root-caused by them: in a browser the entry point
  ;; runs at page load and STARTS the loads not tied to any screen; the
  ;; driver ran routes, views and handlers but never the entry point, so
  ;; boot-scoped data was structurally present and materially empty — with
  ;; nothing distinguishing "the app never asked" from "asked, not arrived".
  ;; The page contract gains :boot — (fn [state] state'), navigate's shape —
  ;; and open runs it once. (User decision, over route-declared loads, which
  ;; would have quietly made route-driven-everything the required
  ;; architecture — a forcing this project already declined once.)
  (testing "boot runs once at open, read-call-write like navigate"
    (let [page {:state (atom {:projects {:status :absent}})
                :boot  (fn [s] (assoc s :projects {:status :loading}))
                :view  (fn [s] [:div [:p (name (get-in s [:projects :status]))]])}
          b    (screen/open! page)]
      (is (= "loading" (screen/text b nil {:detail :prose}))
          "the screen shows the app ASKED — its loading state, not an absence")))
  (testing "a page without :boot is unchanged"
    (let [b (screen/open! {:state (atom {}) :view (fn [_] [:p "hi"])})]
      (is (= "hi" (screen/text b nil {:detail :prose})))))
  (testing "a :boot that is not callable refuses at open"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":boot"
                          (screen/open! {:state (atom {}) :view (fn [_] [:p "x"]) :boot 42})))))

(deftest a-select-speaks-in-both-modes
  ;; slopp-ui's pair, from their real project switcher. A: prose rendered the
  ;; literal word "select" — the tag NAME as page text, in the one mode with
  ;; no escaping; the v1 flaw class surviving exactly where <svg …> did. A
  ;; browser shows the SELECTED option's label and a screen reader announces
  ;; it, so that is what prose says (falling back to the first option). B:
  ;; disabled did not render on <option>, so a test could not tell
  ;; listed-and-disabled from listed-and-clickable — the select branch held a
  ;; PRIVATE attr list instead of reading kept-attrs, the same second-producer
  ;; shape the inline path had last round.
  (let [v [:div [:select {:on {:change [:proj/go]}}
                 [:option {:value "/a"} "slopp2"]
                 [:option {:value "/b" :disabled true} "older — not running"]]]]
    (testing "prose shows what a browser shows: the selected option's label, first by default"
      (is (= "slopp2" (screen/of v {:detail :prose}))))
    (testing "an explicitly selected option wins"
      (is (= "older — not running"
             (screen/of [:div [:select {}
                               [:option {:value "/a"} "slopp2"]
                               [:option {:value "/b" :selected true} "older — not running"]]]
                        {:detail :prose}))))
    (testing "disabled renders on an option, so listed-and-disabled is assertable"
      (is (str/includes? (screen/of v)
                         "<option value=\"/b\" disabled>older — not running</option>")))))
