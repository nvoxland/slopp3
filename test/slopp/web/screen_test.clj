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
  (testing "a heading owns its line, and carries its level"
    (is (= "# code\n3 modules, 4 namespaces"
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
    (is (= "<ul ×2>\n  a\n  b" (screen/of (into [:ul] (for [x ["a" "b"]] [:li x])))))
    (is (= "a\nb" (screen/of [:div (for [x ["a" "b"]] [:p x])])))
    (is (= "a\nb" (screen/of [:div (list (list [:p "a"] [:p "b"]))]))))

  (testing "an :href always travels — where a thing points is half of every check"
    (is (= "Code [/store]" (screen/of [:p [:a {:href "/store"} "Code"]]))))

  (testing "a :class travels only when asked — noise for reading, the point for an overlay"
    (is (= "x" (screen/of [:p [:span {:class "tint-3"} "x"]])))
    (is (= "x {tint-3}" (screen/of [:p [:span {:class "tint-3"} "x"]] {:attrs #{:class}}))))

  (testing "a list is COUNTED, and the count survives the cap"
    (let [big (into [:ul] (for [i (range 32)] [:li (str "row " i)]))]
      (is (= "<ul ×32>" (first (screen/lines big)))
          "32 rows is a wall a reader skims; ×32 is one line they cannot")
      (is (= "  +29 more" (last (screen/lines big)))
          "and the truncation SAYS so, indented under its own list — a cap that stayed quiet would be a report lying about its scope")
      (is (= 33 (count (screen/lines big {:list-head nil})))
          "a test that wants every row removes the cap")))

  (testing "an svg is censused by CLASS and never descended"
    ;; a path is an edge in one place and a sketched box in another, so the tag
    ;; says nothing; `1 gap-w4, 2 gap-w0` IS the tint check, with no pixels
    (let [g [:svg {:class "module-graph"}
             [:path {:class "module-link"} "M 0 0 C 12 40, 88 60, 88 100"]
             [:g {:class "gap-w0"}] [:g {:class "gap-w0"}] [:g {:class "gap-w4"}]]]
      (is (= "<svg module-graph — 2 gap-w0, 1 gap-w4, 1 module-link>"
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
      (is (= ["# code" "<svg module-graph — 1 gap-w4>"]
             (mapv str/triml (screen/lines screen {:region "main"}))))
      (is (= ["Review [/]"]
             (mapv str/triml (screen/lines screen {:region "nav"})))))

    (testing "a region that is not on the screen THROWS, and names the ones that are"
      (let [e (try (screen/lines screen {:region "sidebar"}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "a missing region is a finding, never an empty scope")
        (is (str/includes? (ex-message e) "regions present: nav, main")
            "the list IS the answer to the question behind the mistake")))

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
        b     (screen/open page)]
    (testing "the document renders from state before anything happens"
      ;; `[click]` is the readout saying the button is a button — a reader
      ;; deciding what to do next could not otherwise tell it from a paragraph
      (is (= "n=0\nAdd [click]" (screen/of (screen/tree b)))))
    (testing "a click fires the app's handler and the document changes"
      (screen/click! b "Add")
      (is (= "n=1\nAdd [click]" (screen/of (screen/tree b))))
      (screen/click! b "Add")
      (is (= "n=2\nAdd [click]" (screen/of (screen/tree b)))
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
        b     (screen/open page)]
    (testing "an href with no handler navigates through the app's own :navigate"
      (screen/click! b "Code")
      (is (= "# /store\nCode [/store]" (screen/of (screen/tree b)))))
    (testing "and a link can be clicked by its address as well as its label"
      (screen/visit! b "/")
      (screen/click! b "/store")
      (is (= "/store" (:at @state))))))

(deftest a-click-that-cannot-be-honest-REFUSES
  ;; Four ways a click can be dishonest, which a forgiving browser would
  ;; collapse into one silent no-op. The second is the afternoon-waster,
  ;; because the word IS on the screen and the reader can see it there.
  (let [page {:state (atom {})
              :view  (fn [_]
                       [:div
                        [:p "Save"]
                        [:button {:on-click (fn [_])} "Delete"]
                        [:button {:on-click (fn [_])} "Delete"]
                        [:a {:href "/only"} "Go"]])}
        b    (screen/open page)
        msg  (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))]
    (testing "nothing says it — and the answer is what CAN be clicked"
      (let [m (msg #(screen/click! b "Nope"))]
        (is (str/includes? m "nothing on this screen says"))
        (is (str/includes? m "Delete")
            "the list is the answer to the question behind the mistake")))

    (testing "it is on the screen but nothing clickable says it"
      (is (str/includes? (msg #(screen/click! b "Save"))
                         "carries no :on-click, no :on {:click …} and no :href")
          "a different bug from 'not found', and it must not read as one"))

    (testing "two elements say it — picking one is a guess"
      (is (str/includes? (msg #(screen/click! b "Delete"))
                         "a click that picks one of them is a guess")))

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
        b    (screen/open ctx)]
    (testing "visiting a mounted path renders that page"
      (screen/visit! b "/")
      (is (= "# Home\nAbout [/about]" (screen/of (screen/tree b)))))

    (testing "and a link goes there, through the router — no :navigate anywhere"
      (screen/click! b "About")
      (is (= "# About" (screen/of (screen/tree b)))))

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
        b    (screen/open page)]
    (testing "the page, when the page is what you mean"
      (is (str/includes? (screen/text b) "Review [/]")))

    (testing "one region, in one argument"
      (is (= "# code\n<svg module-graph — 1 gap-w4>" (screen/text b "main")))
      (is (= "Review [/]" (screen/text b "nav"))))

    (testing "options still reach through, for the overlay case"
      (is (str/includes? (screen/text b "main" {:attrs #{:class}}) "gap-w4")))

    (testing "and a region that is not there refuses rather than scoping to nothing"
      (is (thrown? clojure.lang.ExceptionInfo (screen/text b "sidebar"))))))

(deftest prose-drops-the-structure-and-keeps-the-sentences
  ;; Most assertions are "does it say X", and they pay for addresses, counts,
  ;; region markers and an svg census they never read. On a real page that is
  ;; most of the bytes.
  ;;
  ;; What it must NOT become is the naive flatten this feature exists to
  ;; replace: every string joined by spaces, where a wrong sentence hides in a
  ;; run-on line. So :prose drops the STRUCTURE MARKERS and keeps the LINE
  ;; STRUCTURE — the boundary between two sentences is the whole point.
  (let [page [:div
              [:main {:data-region "main"}
               [:h1 "code"]
               [:p "3 modules, 4 namespaces"]
               [:a {:href "/store"} "Code"]
               [:svg {:class "module-graph"} [:g {:class "gap-w4"}]]
               (into [:ul] (for [i (range 6)] [:li (str "row " i)]))]]]
    (testing "no region marker, no heading hash, no address, no census"
      (is (= (str "code\n"
                  "3 modules, 4 namespaces\n"
                  "Code\n"
                  "<svg module-graph>\n"
                  "row 0\nrow 1\nrow 2\n"
                  "+3 more")
             (screen/of page {:detail :prose}))))

    (testing "a block still owns its line — this is not the flatten"
      (is (not (str/includes? (screen/of page {:detail :prose})
                              "code 3 modules"))
          "a wrong sentence in a run-on line is invisible, which is the whole point"))

    (testing "the truncation still SAYS so"
      (is (str/includes? (screen/of page {:detail :prose}) "+3 more")
          "a cap that went quiet would be a report lying about its own scope"))

    (testing "an svg still marks its place, so a picture does not read as nothing"
      (is (str/includes? (screen/of page {:detail :prose}) "<svg module-graph>")))

    (testing "and it composes with region scoping"
      (is (str/starts-with? (screen/of page {:detail :prose :region "main"}) "code")))))

(deftest what-can-be-clicked-says-so
  ;; An agent looking at a screen is usually deciding what to do NEXT, and
  ;; "what can I click" was unanswerable: a link carried its [href], and a
  ;; [:button {:on-click f} "Add"] rendered as bare `Add` — identical to
  ;; [:p "Add"]. The refusal message for clicking an unclickable label exists
  ;; precisely because that confusion is easy to have; the readout should
  ;; prevent it rather than explain it afterwards.
  ;;
  ;; `[click]` parallels the `[/href]` that was already there, so it needs no
  ;; new notation and greps as itself.
  (let [page [:div
              [:p "Save"]
              [:button {:on-click (fn [_])} "Add"]
              [:a {:href "/store"} "Code"]
              [:div {:on-click (fn [_])} [:span "Card"]]]]
    (testing "a handler makes an element say so"
      (is (= "Save\nAdd [click]\nCode [/store]\nCard [click]"
             (screen/of page))))

    (testing "an href already said it, and is not doubled up"
      (is (not (str/includes? (screen/of page) "/store] [click]"))
          "a link is clickable by being a link; two markers is noise"))

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
        b     (screen/open page)]
    (testing "the handler receives the value as DATA and the document changes"
      (screen/fill! b "Filter" "store")
      (is (str/includes? (screen/of (screen/tree b)) "showing store")))

    (testing "a field that is not there refuses, and says what can be filled"
      (let [m (try (screen/fill! b "Nope" "x") nil
                   (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (str/includes? m "Filter")
            "the list is the answer to the question behind the mistake")))

    (testing "and a field with no :on-change is a different bug from a missing one"
      (let [b2 (screen/open {:state (atom {})
                             :view (fn [_] [:input {:placeholder "Inert"}])})
            m  (try (screen/fill! b2 "Inert" "x") nil
                    (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (str/includes? m "no :on-change"))))))

(deftest a-field-is-visible-and-says-it-can-be-filled
  ;; Worse than unmarked: an [:input] has no CHILDREN, so the readout showed
  ;; nothing at all where a search box was. An agent reading that screen would
  ;; conclude the app has no filter — the same "renders as nothing" failure a
  ;; dropped fragment causes, and the reason `kids` flattens seqs.
  ;;
  ;; So a field renders as what you would call it, what it currently holds, and
  ;; whether typing can change anything — parallel to `Add [click]` and
  ;; `Code [/store]`, so the page reads in one vocabulary.
  (let [page [:form
              [:input {:placeholder "Filter" :value "store" :on-change (fn [_])}]
              [:input {:name "email" :on-change (fn [_])}]
              [:input {:type "checkbox" :aria-label "Agree" :on-change (fn [_])}]
              [:input {:placeholder "Inert"}]
              [:textarea {:name "notes" :on-change (fn [_])}]]]
    (testing "a field is VISIBLE, addressed by what a person would call it"
      (is (= (str "Filter=\"store\" [fill]\n"
                  "email [fill]\n"
                  "Agree (checkbox) [fill]\n"
                  "Inert\n"
                  "notes [fill]")
             (screen/of page))))

    (testing "an inert field shows WITHOUT [fill] — that is the finding, not a gap"
      (is (str/includes? (screen/of page) "\nInert\n")))

    (testing "and prose keeps the field but drops the markers"
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
  ;; BOTH put the handler ON THE ELEMENT. That is why there is no `.closest`
  ;; emulation here and no synthesised ancestor chain: the design that needed
  ;; them was supporting a hand-rolled document-delegation pattern that neither
  ;; library asks for.
  (let [seen  (atom [])
        state (atom {:n 0})
        page  {:state    state
               :dispatch (fn [action value] (swap! seen conj [action value]))
               :view     (fn [_]
                           [:div
                            [:button {:on-click #(swap! state update :n inc)} "Reagent"]
                            [:button {:on {:click #(swap! state update :n inc)}} "Fn"]
                            [:button {:on {:click [:like-video 7]}} "Data"]])}
        b     (screen/open page)]
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
      (is (= "Reagent [click]\nFn [click]\nData [click :like-video]"
             (screen/of (screen/tree b)))
          "a serializable action is the only handler shape a readout can report"))

    (testing "data with no :dispatch declared REFUSES, naming the gap"
      (let [b2 (screen/open {:state (atom {})
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
        b     (screen/open page)]
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

    (testing "all three show as fillable"
      (is (= "Reagent=\"abc\" [fill]\nReplicant=\"xyz\" [fill]\nData [fill]"
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
        s    (screen/open page)
        f    (future (screen/visit! s "/store") :done)]
    (is (= :done (deref f 3000 :TIMED-OUT))
        "a :navigate that touches its own atom livelocked inside swap! — it presents as a hang, and a hang has no message")
    (is (= 1 (:n @st))
        "and it ran ONCE: a retry loop would have incremented this a great many times")
    (is (= "at=/store n=1" (screen/of (screen/tree s)))
        "and the navigation actually happened")))
