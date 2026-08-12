(ns slopp.api.endpoints-test
  "Tests for the JSON boundary, run through the REAL dispatcher.

  Two disciplines, both learned from failures. First, build the context from
  `server/served-namespaces` rather than a hand-picked subset: endpoints and
  their read performers live in different namespaces, so a context holding
  only `slopp.api.endpoints` answers 500 and tests nothing — and a bundle served by
  nothing once 404'd for two waves behind a 200 for the page.

  Second, validate every response against the SAME contract var the typed
  client is generated from. A handler that drifts from its declared shape is
  a red here rather than a surprise in someone's browser tab, which is the
  whole argument for declaring contracts at all."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [slopp.store :as store]
            [slopp.api.endpoints]
            [slopp.api.contracts :as contracts]
            [slopp.web :as slopp.web] [slopp.api.server :as server] [slopp.ops.external :as external] [slopp.ops :as ops] [cheshire.core :as json] [clojure.string :as str] [clojure.edn :as edn] [slopp.webdev.cljs :as cljs] [slopp.api.model :as model] [slopp.read.orient :as orient] [slopp.web.contract :as contract]))

(deftest the-api-answers-with-data-that-matches-its-contract
  ;; The whole argument for the REST shape, made testable: an endpoint is a
  ;; function of data, so its test is `=` on data with no mocks and no
  ;; browser — AND the response is validated against the SAME schema var the
  ;; generated client validates with. A handler that drifts from its declared
  ;; contract is a red here rather than a runtime surprise in someone's tab.
  (let [st  (-> (store/empty-store)
                (store/ingest 'demo.core
                              "(ns demo.core)\n\n(defn hello \"Says hi.\" [x] x)\n")
                (store/ingest 'demo.util "(ns demo.util)\n\n(defn undocumented [x] x)\n"))
        ;; the served list, not a hand-picked subset: the reads these
        ;; endpoints declare are performed by slopp.api.reads, so a context
        ;; holding only slopp.api.endpoints answers 500 and tests nothing real
        ctx (slopp.web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store st})}})
        GET (fn [uri] (slopp.web/handle! ctx {:request-method :get :uri uri}))]
    (testing "GET /api/namespaces — every namespace, sorted, as JSON data"
      ;; 2, not 1: the `ns` form is a top-level form in the store like any
      ;; other, which is slopp's model rather than an off-by-one. /store has
      ;; always counted it that way, and the API reusing the same read means
      ;; the two CANNOT disagree — which is the property being pinned here.
      (let [r (GET "/api/namespaces")]
        (is (= 200 (:status r)))
        (is (= [{:ns "demo.core" :forms 2} {:ns "demo.util" :forms 2}]
               (:body r)))
        (is (m/validate contracts/namespace-list (:body r))
            "the response has to satisfy the contract the client is generated from")
        (is (not (:web/raw r))
            "a JSON endpoint leaves encoding to the adapter — :web/raw is for
             bytes that must arrive untouched, like the compiled bundle")))
    (testing "GET /api/ns/:ns — one namespace's outline, in store order"
      (let [r (GET "/api/ns/demo.core")]
        (is (= 200 (:status r)))
        (is (= {:ns "demo.core"
                ;; nobody declared one, and undeclared IS external — so the
                ;; badge has three states, never four
                :tier "external"
                ;; :form-id is the row's ADDRESS — the outline links to the form
                ;; page with it, and that page is keyed by id because ids
                ;; survive an edit and names do not. Asserted EXACTLY: over a
                ;; fresh empty-store ingest the ids are deterministic, and they
                ;; are zero-based and store-wide rather than per-namespace.
                ;; Both facts were guessed wrong first and corrected by this
                ;; assertion, which is the argument for pinning the value
                ;; instead of checking that it is a string.
                :forms [{:name "demo.core" :form-id "f0" :kind "ns" :sig nil
                         :private? false :doc nil :schema nil
                         :mass 3 :calls [] :callers-out 0 :callers-out-test 0
                         :effectful? false :exported? false}
                        {:name "hello" :form-id "f1" :kind "defn" :sig ["[x]"]
                         :private? false :doc "Says hi." :schema nil
                         ;; (defn hello "Says hi." [x] x) — seven sexpr nodes,
                         ;; and the docstring is ONE of them however long it
                         ;; grows. That is the whole reason mass is not lines.
                         :mass 7 :calls [] :callers-out 0 :callers-out-test 0
                         :effectful? false :exported? false}]
                :tested-by []
                ;; the THINNESS counts, exact on this fixture: a bare
                ;; `(ns demo.core)` has no docstring, `store/ingest` records no
                ;; write prompt so both forms are :no-why, and there is no
                ;; session trace map — so :uncovered equals :forms, which is
                ;; the honest answer for a process that has run nothing rather
                ;; than a zero that would read as coverage.
                :gaps {:forms 2 :no-doc 1 :no-why 2 :uncovered 2}}
               (:body r))
            "nothing tests this fixture, and that reports as an empty list rather
             than a missing key — the page should be able to SAY untested")
        (is (m/validate contracts/ns-outline (:body r)))))
    (testing "a form with no docstring carries an explicit nil, not a missing key"
      ;; :maybe in the contract is the promise; this is the promise being kept
      (let [r (GET "/api/ns/demo.util")]
        (is (= {:ns "demo.util"
                :tier "external"
                ;; ids continue across the second ingest rather than restarting per
                ;; namespace — a form id is store-wide, which is what makes it
                ;; a permalink
                :forms [{:name "demo.util" :form-id "f3" :kind "ns" :sig nil
                         :private? false :doc nil :schema nil
                         :mass 3 :calls [] :callers-out 0 :callers-out-test 0
                         :effectful? false :exported? false}
                        {:name "undocumented" :form-id "f4" :kind "defn" :sig ["[x]"]
                         :private? false :doc nil :schema nil
                         :mass 6 :calls [] :callers-out 0 :callers-out-test 0
                         :effectful? false :exported? false}]
                :tested-by []
                ;; BOTH forms undocumented here, against demo.core's one — the
                ;; count moves with the fixture, which is what makes it a
                ;; measurement rather than a constant
                :gaps {:forms 2 :no-doc 2 :no-why 2 :uncovered 2}}
               (:body r)))
        (is (m/validate contracts/ns-outline (:body r)))))
    (testing "an unknown namespace is a 404, not an empty outline"
      ;; an empty :forms would say the namespace exists and is empty, which is
      ;; a different and false statement
      (let [r (GET "/api/ns/no.such.ns")]
        (is (= 404 (:status r)))
        (is (not (m/validate contracts/ns-outline (:body r))))))))

(deftest every-ui-namespace-is-actually-served
  ;; The failure this pins has already happened once: the compiled bundle was
  ;; served by nothing at all and 404'd on every page for two waves, behind a
  ;; 200 for the page itself. The cause was not a bug in any function — it was
  ;; a LIST that someone had to remember to add to. So the list is one var,
  ;; and this test is the reason it is one var.
  ;; What this used to assert — that the list equals a hardcoded pair — was
  ;; the SAME defect one level up. Add a third endpoint-bearing namespace,
  ;; forget the list, and the hardcoded pair still matches the unchanged list:
  ;; green, while the failure it claims to pin has happened. A test that
  ;; restates a list cannot notice the list falling behind the code.
  ;;
  ;; `server-test/the-served-list-is-checked-against-what-declares-endpoints`
  ;; does that job by DERIVING the set from vars carrying :web/path or
  ;; :web/read. What stays here is the part derivation cannot answer: that
  ;; serving the list actually routes, and that nothing else is served.
  (testing "serving that list actually routes the API — and a project serves
            NOTHING ELSE, which is the shape the split settled on"
    ;; the endpoints and the READ performers live in different namespaces, so
    ;; a list missing either half fails here rather than in a browser — and
    ;; missing the performer half is a 500, not a 404
    (let [st  (store/ingest (store/empty-store) 'demo.core "(ns demo.core)\n")
          ctx (slopp.web/context {:web/namespaces server/served-namespaces
                            :web/perform-ctx {:session (atom {:store st})}})
          get* (fn [uri] (:status (slopp.web/handle! ctx {:request-method :get
                                                    :uri uri})))]
      (is (= 200 (get* "/api/namespaces")))
      (is (= 200 (get* "/api/contracts")))
      ;; the pages moved out. Asserting their ABSENCE is the half worth
      ;; keeping: a page reappearing here would mean slopp had quietly grown a
      ;; second renderer alongside the hub's, which is the drift the :cljc
      ;; views were split out to prevent in the first place.
      (is (= 404 (get* "/")))
      (is (= 404 (get* "/store")))
      (is (= 404 (get* "/css/style.css")))
      (is (= 404 (get* "/js/main.js"))))))

(deftest ^:external every-screen-has-an-endpoint-that-matches-its-contract
  ;; After the SPA rewrite there is no server-rendered page to fall back on:
  ;; if an endpoint's data is wrong or its contract is a lie, the screen is
  ;; blank and the only witness is a browser. So every screen's data is
  ;; validated here against the SAME schema var the generated client uses.
  ;;
  ;; :external because a real session is needed — the timeline and change
  ;; models read the delta log, not just the store's namespaces.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'demo.core
                   (str "(ns demo.core)\n\n(defn hello \"Says hi.\" [x] x)\n\n"
                        ;; a real CALLER, because [:sequential …] over an empty
                        ;; list validates vacuously — with no caller the whole
                        ;; :callers half of the contract is never checked, and
                        ;; that half is where the wire types actually bite
                        "(defn greet [x] (hello x))\n")
                   :prompt "the demo form")
      (let [ctx (slopp.web/context {:web/namespaces server/served-namespaces
                              :web/perform-ctx {:session sess}})
            ;; THROUGH JSON, deliberately. In-image `handle!` hands back the
            ;; body as Clojure DATA — the adapter is what serializes — so a
            ;; keyword `:via` sails through a `[:via :string]` contract here
            ;; and reaches the browser as a string. Validating the pre-wire
            ;; map checks a value no client ever sees. Round-tripping makes
            ;; the assertion about what actually ARRIVES, and catches any
            ;; value JSON cannot carry at all.
            ;; :uri and :query-string are SEPARATE keys — the router matches on
            ;; :uri alone, so a "/x?y=z" shoved into :uri matches no route and
            ;; 404s. That made the unknown-fidelity assertion pass for
            ;; entirely the wrong reason: it never reached the handler.
            GET (fn [uri]
                  (let [[path qs] (str/split uri #"\?" 2)
                        r (slopp.web/handle! ctx (cond-> {:request-method :get :uri path}
                                             qs (assoc :query-string qs)))]
                    (cond-> r
                      (map? (:body r))
                      (assoc :body (json/parse-string
                                    (json/generate-string (:body r)) true)))))
            fid (:id (store/form-named (:store @sess) 'demo.core 'hello))]
        (testing "/api/timeline — the landing screen"
          (let [r (GET "/api/timeline")]
            (is (= 200 (:status r)))
            (is (m/validate contracts/timeline (:body r))
                (pr-str (m/explain contracts/timeline (:body r))))
            (is (some #{"demo.core"} (:namespaces (:working (:body r))))
                "the working set has to see the form just written")))
        (testing "/api/form/:id — the permalink screen"
          (let [r (GET (str "/api/form/" fid))]
            (is (= 200 (:status r)))
            (is (m/validate contracts/form-view (:body r))
                (pr-str (m/explain contracts/form-view (:body r))))
            (testing "tokens reproduce the source exactly — the client needs no lexer"
              (is (= (:source (:body r))
                     (apply str (map second (:tokens (:body r)))))))))
        (testing "/api/source/:ns/:name — source by the name the outline links"
          (let [r (GET "/api/source/demo.core/hello")]
            (is (= 200 (:status r)))
            (is (m/validate contracts/form-source (:body r)))))
        (testing "every not-found is a 404, never a blank screen"
          ;; in an SPA a wrong 200 renders as an empty pane, which reads as
          ;; "nothing here" rather than "you are lost"
          (is (= 404 (:status (GET "/api/form/nosuchid"))))
          ;; an unknown FIDELITY is the same answer as an unknown id, never a
          ;; quiet downgrade to the one that happens to exist — every
          ;; permalink already in the wild would otherwise silently come to
          ;; mean "whatever the default became"
          (is (= 404 (:status (GET (str "/api/form/" fid "?view=nosuchview")))))
          (is (= 200 (:status (GET (str "/api/form/" fid "?view=clojure")))))
          (is (= 404 (:status (GET "/api/source/demo.core/nope"))))
          (is (= 404 (:status (GET "/api/change/d1..d2"))))))
      (finally (ops/close! sess)))))

(deftest the-modules-endpoint-serves-a-contract-shaped-architecture
  (let [st  (-> (store/empty-store)
                (store/ingest 'demo.a.core "(ns demo.a.core)\n\n(defn hello [] 1)\n")
                (store/ingest 'demo.a.core-test
                              (str "(ns demo.a.core-test (:require [demo.a.core :as c]))\n\n"
                                   "(defn t [] (c/hello))\n"))
                (store/ingest 'demo.b.util
                              (str "(ns demo.b.util (:require [demo.a.core :as c]))\n\n"
                                   "(defn helper [] (c/hello))\n")))
        ctx (slopp.web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store st})}})
        res (slopp.web/handle! ctx {:request-method :get :uri "/api/modules"})]
    (testing "the route is served and its response satisfies the declared contract"
      ;; a violation fails HERE, at the boundary, rather than rendering wrong
      (is (= 200 (:status res))))
    (testing "it answers with the architecture, not a namespace dump"
      (let [body (:body res)]
        (is (= ["demo.a" "demo.b"] (mapv :module (:modules body))))
        (is (= 1 (:tests (first (:modules body)))))))
    (testing "and with FACTS rather than a drawing: the layering, which only
              the store can compute, and each row's dependencies, which are
              what let a consumer draw an edge at all. The 200 above is the
              real assertion here — the response is validated against the
              declared contract at the boundary, so a picture sneaking back in
              would fail there rather than render wrong somewhere."
      (let [body (:body res)]
        (is (not (contains? body :picture)))
        (is (= [["demo.a"] ["demo.b"]] (:layers body)))
        (is (= [[] ["demo.a"]] (mapv :deps (:modules body))))))))

(deftest the-contract-endpoint-publishes-what-the-client-is-generated-from
  ;; The seam that lets the reviewer UI live in its OWN store: it consumes this
  ;; document instead of reading slopp's contracts namespace. So the property
  ;; that matters is not "some document is served" but "the schema published
  ;; for an endpoint IS the var that endpoint declares" — anything weaker and a
  ;; generated client would validate against a shape the server never promised.
  (let [ctx (slopp.web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store (store/empty-store)})
                                            :served-namespaces server/served-namespaces}})
        r   (slopp.web/handle! ctx {:request-method :get :uri "/api/contracts"})
        doc (edn/read-string (:body r))
        by-path (into {} (map (juxt :path identity)) (:endpoints doc))]

    (testing "EDN verbatim — JSON would flatten a keyword schema into a string"
      (is (= 200 (:status r)))
      (is (:web/raw r) "the body must arrive untouched by the adapter's encoder")
      (is (= "application/edn" (get-in r [:headers "Content-Type"]))))

    (testing "the document is versioned and lists the typed endpoints"
      (is (= 1 (:slopp/contract-version doc)))
      ;; hand-kept ON PURPOSE: this is the control. Derived from the same
      ;; metadata the endpoint list comes from, it would compare a derivation
      ;; to itself and pass however wrong both were.
      (is (= #{"/api/change/:range" "/api/namespaces" "/api/source/:ns/:name" "/api/ns/:ns"
               "/api/timeline" "/api/modules" "/api/module/:m" "/api/form/:id"
               "/api/search"}
             (set (keys by-path)))))

    (testing "the published schema IS the var the endpoint declares"
      (is (= contracts/timeline (:response (by-path "/api/timeline"))))
      (is (= contracts/module-index (:response (by-path "/api/modules")))))

    (testing "a GET publishes an explicit nil request, not a missing key"
      (is (contains? (by-path "/api/timeline") :request))
      (is (nil? (:request (by-path "/api/timeline")))))

    (testing "pages, the bundle and the contract itself are not part of a TYPED contract"
      (is (not (contains? by-path "/")))
      (is (not (contains? by-path "/js/main.js")))
      (is (not (contains? by-path "/api/contracts"))))))

(deftest ^:external a-consumer-generates-an-equivalent-client-from-the-published-contract
  ;; The fixed point the whole split rests on. A store that has never seen
  ;; slopp.api.contracts generates, from HTTP alone, a client equivalent to the
  ;; one local generation produces from the store. If this holds, the reviewer
  ;; UI can live in its own project; if it does not, the wire format is lossy
  ;; and nothing downstream is worth building.
  ;;
  ;; Two processes' worth of separation in one JVM: the producer serves over a
  ;; real socket, and the consumer is a genuinely separate session and store.
  (let [producer (atom {:store (store/empty-store)})
        consumer (external/open!)]
    (try
      (let [r   (server/serve! producer 0)
            out (cljs/generate-client-from!
                 consumer (str "http://127.0.0.1:" (:port r) "/api/contracts")
                 :ns 'demo.client.api)
            st  (:store @consumer)
            src (fn [ns- n] (str (store/form-named st ns- n)))]

        (testing "the same wrappers local generation produces, by name"
          (is (= #{"namespaces" "ns-outline" "timeline" "change" "form" "source"
                   "modules" "module" "search"}
                 (set (:wrappers out)))
              (pr-str out)))

        (testing "the schemas survived the wire VALUE for value"
          ;; not 'a schema is present' — the same schema, identical to the var
          ;; the server validates its own responses against.
          (is (str/includes? (src 'demo.client.contracts 'timeline-response)
                             (pr-str contracts/timeline)))
          (is (str/includes? (src 'demo.client.contracts 'modules-response)
                             (pr-str contracts/module-index))))

        (testing "both namespaces are born on the platform that can use them"
          (is (= :cljc (store/platform-for st 'demo.client.contracts))
              "the oracle verifies it AND the bundle compiles it")
          (is (= :cljs (store/platform-for st 'demo.client.api))))

        (testing "the client points at the generated schemas, not at inlined copies"
          (is (str/includes? (src 'demo.client.api 'timeline)
                             "demo.client.contracts/timeline-response")))

        (testing "an endpoint that opted out of the client stays out"
          (is (nil? (store/form-named st 'demo.client.api 'contract)))))
      (finally
        (server/stop!)
        (ops/close! consumer)))))

(deftest an-outline-row-says-what-a-form-TAKES-and-what-KIND-it-is
  ;; slopp-ui, 2026-08-02: their namespace pane is now laid out as SOURCE, and
  ;; that made the gap obvious — "the pane whose entire job is to be what you
  ;; read INSTEAD of the source cannot say what a function takes."
  ;;
  ;; The `:kind` half is the sharper complaint, and it is why a `def`'s value
  ;; vector must NOT read as a signature: a `def` and a two-arg `defn` drew
  ;; identically, so the pane made a false statement in the one shape that
  ;; makes it read as fact.
  ;;
  ;; `:sig` is a SEQUENTIAL, one string per arity, so a multi-arity stacks the
  ;; way source stacks it. Joining is something the consumer can do and cannot
  ;; undo, so the wire carries the separable form.
  (let [st  (store/ingest (store/empty-store) 'demo.shape
                          (str "(ns demo.shape)\n\n"
                               "(def geometry \"Constants.\" [1 2 3])\n\n"
                               "(defn draw \"Draws.\" ([p] p) ([p targets] [p targets]))\n\n"
                               "(defn- helper [x] x)\n\n"
                               "(defn ^{:malli/schema [:=> [:cat :int] :int]} inc-it\n"
                               "  [x] (inc x))\n"))
        ctx (slopp.web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store st})}})
        r    (slopp.web/handle! ctx {:request-method :get :uri "/api/ns/demo.shape"})
        rows (into {} (map (juxt :name identity)) (:forms (:body r)))]
    (is (= 200 (:status r)))
    (is (m/validate contracts/ns-outline (:body r))
        "the four new fields have to satisfy the contract the client is generated from")
    (testing "a def is not callable, and the row does not pretend otherwise"
      (is (= "def" (:kind (rows "geometry"))))
      (is (nil? (:sig (rows "geometry")))
          "a def's VALUE vector is not a signature — this is the confusion that
           drew geometry and a two-arg defn identically"))
    (testing "every arity, in order, each its own string"
      (is (= "defn" (:kind (rows "draw"))))
      (is (= ["[p]" "[p targets]"] (:sig (rows "draw")))))
    (testing "private is exactly the question a surface pane exists to answer"
      (is (true? (:private? (rows "helper"))))
      (is (false? (:private? (rows "draw")))
          "explicitly false rather than absent — a missing key and a public var
           would render the same, and one of them is a finding"))
    (testing "a declared schema, in the only place a reader would look for it"
      (is (= "[:=> [:cat :int] :int]" (:schema (rows "inc-it"))))
      (is (nil? (:schema (rows "draw")))))
    (testing "the ns form itself is a row like any other, and claims nothing"
      (is (= "ns" (:kind (rows "demo.shape"))))
      (is (nil? (:sig (rows "demo.shape")))))))

(deftest the-outline-carries-what-a-consumer-needs-to-RANK-a-namespace
  ;; slopp-ui, 2026-08-03: the namespace pane colours a definition by category
  ;; and badges its modifiers; the third channel — lightness — is meant to
  ;; carry how IMPORTANT a form is within its namespace.
  ;;
  ;; They asked for the FACTS and explicitly not an `:importance 0.82`, on the
  ;; argument D-hub part 5 already made about the laid-out `:picture`:
  ;; the call graph and the size of a form have one right answer and only the
  ;; store can see them; weighting them into a score, and bucketing that score
  ;; into perceptible steps, is drawing.
  ;;
  ;; `:callers-out` is the half that decides the shape. Fan-in alone ranks an
  ;; entry point LAST — nothing inside a view namespace calls its own
  ;; app-view — so the outbound count has to cross the namespace boundary.
  (let [st  (-> (store/empty-store)
                (store/ingest 'demo.rank
                              (str "(ns demo.rank)\n\n"
                                   "(defn plural [n w] (if (= 1 n) w (str w \"s\")))\n\n"
                                   "(defn ^:export app-view [xs] (plural (count xs) \"row\"))\n"))
                (store/ingest 'demo.client
                              (str "(ns demo.client (:require [demo.rank :as rank]))\n\n"
                                   "(defn main [] (rank/app-view []))\n"))
(store/ingest 'demo.rank-test
                              (str "(ns demo.rank-test\n"
                                   "  (:require [clojure.test :refer [deftest is]]\n"
                                   "            [demo.rank :as rank]))\n\n"
                                   "(deftest pluralises (is (= \"rows\" (rank/plural 2 \"row\"))))\n")))
        ctx (slopp.web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store st})}})
        r    (slopp.web/handle! ctx {:request-method :get :uri "/api/ns/demo.rank"})
        rows (into {} (map (juxt :name identity)) (:forms (:body r)))]
    (is (= 200 (:status r)))
    (is (m/validate contracts/ns-outline (:body r))
        "the five ranking fields have to satisfy the contract the typed client
         is generated from — that is what makes a missing one a red test here
         rather than a nil in someone else's pane")
    (testing "the graph, as edges, so the consumer owns the closure"
      (is (= ["plural"] (:calls (rows "app-view"))))
      (is (= [] (:calls (rows "plural")))))
    (testing "callers-out crosses the namespace, which is why an entry point ranks"
      (is (= 1 (:callers-out (rows "app-view"))) "demo.client/main")
      (is (= 0 (:callers-out (rows "plural")))
          "called here and from its own test, and neither is a production
           caller — carries the namespace, fans in at zero"))
    (testing "and a test caller is counted SEPARATELY, not folded in"
      ;; the number this split cost: run against slopp-ui.views while
      ;; callers-out was one integer, ten of the twelve cross-namespace
      ;; callers were deftests, so the field was ranking by test count.
      (is (= 1 (:callers-out-test (rows "plural"))) "demo.rank-test/pluralises")
      (is (= 0 (:callers-out-test (rows "app-view")))))
    (testing "mass is a positive node count, and the ns form is a row like any other"
      (is (pos? (:mass (rows "app-view"))))
      (is (pos? (:mass (rows "demo.rank")))))
    (testing "two badges, two facts"
      (is (true? (:exported? (rows "app-view"))))
      (is (false? (:exported? (rows "plural"))))
      (is (false? (:effectful? (rows "plural")))))))

(deftest the-outline-says-what-the-NAMESPACE-is-allowed-to-do
  ;; slopp-ui's ask, and the GRAIN is the whole point. `:effectful?` is a FORM
  ;; fact — what this definition actually does — and their user asked the right
  ;; question looking at a ⚡ badge: a tier is a claim about the NAMESPACE, so
  ;; it belongs beside :ns rather than repeated on every row.
  ;;
  ;; Both are worth showing because they disagree constantly: a namespace with
  ;; permission to do IO is mostly pure functions. `slopp-ui.hub` is their
  ;; worked case — tier :external, and 5 of its 23 forms effectful.
  (let [st  (-> (store/empty-store)
                (store/ingest 'demo.core
                              (str "(ns demo.core)\n\n"
                                   "(defn calc [x] (inc x))\n"))
                (store/ingest 'demo.quiet
                              (str "(ns demo.quiet)\n\n"
                                   "(defn hush [x] x)\n"))
                (store/record-module-tier "demo.core" :pure)
                first)
        ask (fn [nsx]
              (slopp.web/handle! (slopp.web/context
                            {:web/namespaces server/served-namespaces
                             :web/perform-ctx {:session (atom {:store st})}})
                           {:request-method :get :uri (str "/api/ns/" nsx)}))]
    (testing "a declared tier rides at the top level, as a string like :ns"
      (let [r (ask "demo.core")]
        (is (= 200 (:status r)))
        (is (= "pure" (:tier (:body r))) (pr-str (:body r)))
        (is (m/validate contracts/ns-outline (:body r))
            (pr-str (m/explain contracts/ns-outline (:body r))))))
    (testing "an UNDECLARED namespace resolves to external — never absent"
      ;; the consumer BADGES on this. An absent key would have to mean "nobody
      ;; said", and the tier vocabulary has no such value: undeclared IS
      ;; :external. Absence here would invent a fourth state in a UI.
      (let [r (ask "demo.quiet")]
        (is (= "external" (:tier (:body r))) (pr-str (:body r)))
        (is (m/validate contracts/ns-outline (:body r))
            (pr-str (m/explain contracts/ns-outline (:body r))))))))

(deftest an-outline-row-carries-the-ADDRESS-of-the-form-it-describes
  ;; slopp-ui, 2026-08-04: the outline links to /store/source/<ns>/<name>
  ;; "because that is the only address it can construct". Structure-first needs
  ;; it to link to the FORM page, and the form page is keyed by id — which is
  ;; right: an id is stable across edits and a name is not, which is the whole
  ;; reason form-view is addressed by one.
  ;;
  ;; So the row a consumer renders and the page it links to have to agree on
  ;; the address, and until now only one of them had one.
  (let [st  (-> (store/empty-store)
                (store/ingest 'demo.addr
                              (str "(ns demo.addr)\n\n"
                                   "(defn one [x] x)\n\n"
                                   "(defn two [x] (one x))\n")))
        ctx (slopp.web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store st})}})
        r    (slopp.web/handle! ctx {:request-method :get :uri "/api/ns/demo.addr"})
        rows (into {} (map (juxt :name identity)) (:forms (:body r)))]
    (is (= 200 (:status r)))
    (is (m/validate contracts/ns-outline (:body r))
        (pr-str (m/explain contracts/ns-outline (:body r))))
    (testing "every row carries an id, the ns form included"
      (is (= 3 (count rows)) (pr-str (keys rows)))
      (is (every? string? (map :form-id (vals rows))) (pr-str rows)))
    (testing "and the id ADDRESSES that form — the link resolves to the same name"
      ;; the half that makes this more than a non-nil check: an id that is
      ;; present but points at a sibling would satisfy every assertion above
      (doseq [[nm row] rows]
        (is (= nm (:name (model/form-view (atom {:store st}) (:form-id row))))
            (str nm " should address itself, got "
                 (pr-str (:form (model/form-view (atom {:store st}) (:form-id row))))))))))

(deftest a-module-can-be-DESCENDED-into-its-namespaces
  ;; slopp-ui, 2026-08-04 (blocking): /api/modules ships module→module :deps,
  ;; so "what does slopp.edit look like inside" — the obvious next click on a
  ;; box — had no data behind it at all.
  ;;
  ;; :boundary is the part they said they would not have thought to ask for a
  ;; week ago, and it is the part that makes the level readable: a descended
  ;; diagram that silently drops the edges LEAVING the module is context-free,
  ;; because you cannot tell a namespace that is the module's front door from
  ;; one nothing outside touches.
  ;;
  ;; THREE segments on purpose. `module-of` is the first two, so `sh.core`
  ;; would be its OWN module and there would be nothing to descend into — the
  ;; first spelling of this fixture 404'd for exactly that reason.
  (let [st  (-> (store/empty-store)
                (store/ingest 'sh.mod.core "(ns sh.mod.core)\n(defn base [x] x)\n")
                (store/ingest 'sh.mod.impl
                              (str "(ns sh.mod.impl (:require [sh.mod.core :as core]))\n"
                                   "(defn helper [x] (core/base x))\n"))
                (store/ingest 'sh.mod.door
                              (str "(ns sh.mod.door (:require [sh.mod.impl :as impl]))\n"
                                   "(defn ^:export enter [x] (impl/helper x))\n"))
                (store/ingest 'out.app
                              (str "(ns out.app (:require [sh.mod.door :as door]))\n"
                                   "(defn go [x] (door/enter x))\n"))
                (store/ingest 'sh.mod.core-test
                              (str "(ns sh.mod.core-test\n"
                                   "  (:require [clojure.test :refer [deftest is]]\n"
                                   "            [sh.mod.core :as core]))\n"
                                   "(deftest base-t (is (= 1 (core/base 1))))\n")))
        ctx (slopp.web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store st})}})
        r   (slopp.web/handle! ctx {:request-method :get :uri "/api/module/sh.mod"})
        b   (:body r)
        nss (into {} (map (juxt :ns identity)) (:namespaces b))]
    (is (= 200 (:status r)) (pr-str r))
    (is (m/validate contracts/module-detail b)
        (pr-str (m/explain contracts/module-detail b)))
    (testing "the production namespaces, and a -test one is not a peer of them"
      (is (= ["sh.mod.core" "sh.mod.door" "sh.mod.impl"] (mapv :ns (:namespaces b))))
      (is (nil? (nss "sh.mod.core-test"))
          "a test folds into the module it covers; listing it puts two things
           at the same rung that are not peers — module-index's own argument"))
    (testing "ns→ns edges WITHIN the module — the thing /api/modules cannot say"
      (is (= ["sh.mod.core"] (:deps (nss "sh.mod.impl"))))
      (is (= ["sh.mod.impl"] (:deps (nss "sh.mod.door"))))
      (is (= [] (:deps (nss "sh.mod.core")))))
    (testing "layering INSIDE the module, because only the store can compute it"
      (is (= [["sh.mod.core"] ["sh.mod.impl"] ["sh.mod.door"]] (:layers b)))
      (is (= [] (:cycles b))))
    (testing "the boundary: edges LEAVING, so a front door is distinguishable"
      (is (= [] (:out (:boundary b)))
          "nothing in module sh.mod reaches outside it — and sh.mod.impl→sh.mod.core,
           which IS a real edge, is absent because an INTERNAL edge is already
           in :deps and repeating it here would double every arrow"))
    (testing "and edges ARRIVING, which is what names the front door"
      (is (= [{:from "out.app" :from-module "out.app" :to "sh.mod.door"}]
             (:in (:boundary b)))))
    (testing "a module with no production namespaces is a 404, not an empty frame"
      (let [r (slopp.web/handle! ctx {:request-method :get :uri "/api/module/no.such"})]
        (is (= 404 (:status r)))
        (is (not (m/validate contracts/module-detail (:body r))))))))

(deftest a-caller-arrives-with-the-same-weight-as-a-callee
  ;; slopp-ui, 2026-08-04: "a callee arrives with its card — sig, doc, why,
  ;; warranty. A caller arrives as a name." The argument for inlining callee
  ;; cards rather than linking them is that a cold-arrived page has to be
  ;; answerable, and that argument is symmetric while the wire was not:
  ;; rendering callers with the same weight meant one extra /api/form/:id per
  ;; caller, which is exactly the N+1 the inlining exists to avoid.
  (let [st  (-> (store/empty-store)
                (store/ingest 'sym.core
                              (str "(ns sym.core)\n"
                                   "(defn ^{:why \"the shared spelling\"} base\n"
                                   "  \"Base case.\" [x] x)\n"))
                (store/ingest 'sym.caller
                              (str "(ns sym.caller (:require [sym.core :as core]))\n"
                                   "(defn ^{:why \"drives base\"} go \"Goes.\" [x] (core/base x))\n")))
        ctx (slopp.web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store st})}})
        fid (:id (store/form-named st 'sym.core 'base))
        r   (slopp.web/handle! ctx {:request-method :get :uri (str "/api/form/" fid)})
        b   (:body r)
        clr (first (:forms (first (:callers b))))]
    (is (= 200 (:status r)) (pr-str r))
    (is (m/validate contracts/form-view b)
        (pr-str (m/explain contracts/form-view b)))
    (testing "the caller is there at all — the control, so the fields below mean something"
      (is (= "sym.caller/go" (:form clr)) (pr-str (:callers b))))
    (testing "and it carries what a callee row carries, so no second request is needed"
      ;; :sig is UNWRAPPED for a single arity — a string, not a one-element
      ;; vector — which is the shape a callee row already has. Symmetry means
      ;; matching that, not inventing a second one.
      (is (= "[x]" (:sig clr)) (pr-str clr))
      (is (= "Goes." (:doc clr)))
      (is (map? (:warranty clr)) (pr-str clr))
      (is (int? (:covered (:warranty clr)))))
    (testing "the row IS the card, so the two cannot drift apart"
      ;; the assertion that outlives a field list: whatever `form-card` grows,
      ;; a caller row grows with it. `:why` in particular comes from the WRITE
      ;; PROMPT (store/prompt-by-form) rather than from ^{:why} metadata, so a
      ;; store/ingest fixture has none — listing fields would have pinned this
      ;; fixture's shape instead of the property.
      (let [card (dissoc (orient/form-card (atom {:store st}) 'sym.caller 'go) :form)]
        ;; KEYS, not values. `json-card` — the one symbol→string conversion the
        ;; whole model funnels through — is private to slopp.api.model, and
        ;; redoing it here would be a second derivation of exactly the thing it
        ;; exists to own. The values are spot-checked above; what this pins is
        ;; that no field of the card is DROPPED on the way to the row, which is
        ;; the part that would silently regress.
        (is (seq card) "the card is not empty, or this proves nothing")
        (is (every? #(contains? clr %) (keys card))
            (str "the row drops " (pr-str (remove #(contains? clr %) (keys card)))))))
    (testing "the id is still there — a card is added, nothing is traded for it"
      (is (string? (:form-id clr)))
      (is (= 1 (:calls clr)))
      (is (= "sym.caller" (:ns clr))))))

(deftest the-form-page-takes-a-DEPTH-off-the-query-string
  ;; The model half is pinned in slopp.api.model-test; this is the wire: that
  ;; ?depth= arrives, that its absence still means exactly what every link
  ;; written before the parameter existed meant, and that garbage falls back
  ;; instead of 404ing — an unknown FIDELITY has no right answer, a bad depth
  ;; has an obvious floor.
  (let [st  (-> (store/empty-store)
                (store/ingest 'wd.leaf "(ns wd.leaf)\n\n(defn tip [x] x)\n")
                (store/ingest 'wd.mid
                              (str "(ns wd.mid (:require [wd.leaf :as leaf]))\n\n"
                                   "(defn a [x] (leaf/tip x))\n"))
                (store/ingest 'wd.top
                              (str "(ns wd.top (:require [wd.mid :as mid]))\n\n"
                                   "(defn go [x] (mid/a x))\n")))
        ctx (slopp.web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store st})}})
        fid (:id (store/form-named st 'wd.top 'go))
        GET (fn [q] (slopp.web/handle! ctx {:request-method :get
                                      :uri (str "/api/form/" fid)
                                      :query-string q}))]
    (testing "no depth — unchanged, and that is the compatibility promise"
      (let [b (:body (GET nil))]
        (is (nil? (:graph b)))
        (is (m/validate contracts/form-view b)
            (pr-str (m/explain contracts/form-view b)))))
    (testing "depth=2 reaches the leaf one hop cannot see"
      (let [b (:body (GET "depth=2"))]
        (is (= 2 (:depth (:graph b))) (pr-str (:graph b)))
        (is (contains? (set (map :form (:nodes (:graph b)))) "wd.leaf/tip"))
        (is (m/validate contracts/form-view b)
            (pr-str (m/explain contracts/form-view b)))))
    (testing "garbage is the floor, not a 404 — an unreadable depth has a right answer"
      (let [r (GET "depth=banana")]
        (is (= 200 (:status r)))
        (is (nil? (:graph (:body r))))))
    (testing "an unknown FIDELITY is still a 404, because that one does not"
      (is (= 404 (:status (GET "view=nope&depth=2")))))))

(deftest a-module-rollup-sums-the-namespaces-that-row-LISTS
  ;; slopp-ui ask #3: counts so a box can be tinted by how thin it is, over
  ;; the identical layout. The property worth pinning is not the arithmetic —
  ;; it is that the rollup is over exactly the namespaces the row NAMES, so a
  ;; reader can check it against the rows below instead of taking it on faith.
  (let [st  (-> (store/empty-store)
                (store/ingest 'gp.mod.a "(ns gp.mod.a \"Has a purpose.\")\n(defn f \"Does.\" [x] x)\n")
                (store/ingest 'gp.mod.b "(ns gp.mod.b)\n(defn g [x] x)\n")
                (store/ingest 'gp.mod.a-test
                              (str "(ns gp.mod.a-test (:require [clojure.test :refer [deftest is]]\n"
                                   "                            [gp.mod.a :as a]))\n"
                                   "(deftest f-t (is (= 1 (a/f 1))))\n")))
        ctx (slopp.web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store st})}})
        mods (:body (slopp.web/handle! ctx {:request-method :get :uri "/api/modules"}))
        row  (first (filter #(= "gp.mod" (:module %)) (:modules mods)))
        det  (:body (slopp.web/handle! ctx {:request-method :get :uri "/api/module/gp.mod"}))]
    (is (some? row) (pr-str (map :module (:modules mods))))
    (testing "the row's counts are the sum over the namespaces it lists"
      ;; a  → ns documented, f documented        → 2 forms, 0 no-doc
      ;; b  → neither                            → 2 forms, 2 no-doc
      (is (= ["gp.mod.a" "gp.mod.b"] (:namespaces row)))
      (is (= {:forms 4 :no-doc 2 :no-why 4 :uncovered 4} (:gaps row))))
    (testing "a -test namespace contributes NOTHING to the counts"
      ;; it folds into this module and is counted under :tests, so folding it
      ;; into :gaps too would count one namespace twice in two vocabularies
      (is (= 1 (:tests row)))
      (is (= 4 (:forms (:gaps row))) "6 if the test namespace's forms leaked in"))
    (testing "and the DESCEND carries the same counts per namespace"
      ;; the rollup and the rows are one derivation, so the descend cannot
      ;; disagree with the level above it
      (let [by-ns (into {} (map (juxt :ns :gaps)) (:namespaces det))]
        (is (= {:forms 2 :no-doc 0 :no-why 2 :uncovered 2} (by-ns "gp.mod.a")))
        (is (= {:forms 2 :no-doc 2 :no-why 2 :uncovered 2} (by-ns "gp.mod.b")))
        (is (= (:gaps row)
               (reduce (fn [a m] (merge-with + a m))
                       {:forms 0 :no-doc 0 :no-why 0 :uncovered 0}
                       (vals by-ns)))
            "the module rollup IS the sum of its descend rows")))
    (testing "both bodies satisfy their contracts"
      (is (m/validate contracts/module-index mods)
          (pr-str (m/explain contracts/module-index mods)))
      (is (m/validate contracts/module-detail det)
          (pr-str (m/explain contracts/module-detail det))))))

(deftest the-search-endpoint-answers-200-even-when-there-is-nothing-to-say
  ;; The model half is pinned in slopp.api.model-test; this is the wire.
  ;;
  ;; The load-bearing case is the BLANK query. A search screen is reachable by
  ;; URL, so a reader can arrive having asked nothing — and the honest answer
  ;; to that is the empty state, not a 400 and an error panel in front of
  ;; someone who did nothing wrong. Unlike /api/module/:m there is no 404 here
  ;; at all: this endpoint has no subject that can fail to exist, only a
  ;; question that can go unanswered.
  (let [st  (-> (store/empty-store)
                (store/ingest 'invoice.api
                              (str "(ns invoice.api \"The API.\")\n\n"
                                   "(defn send-it \"Ships it.\" [x] x)\n"))
                (store/ingest 'inv.core
                              (str "(ns inv.core \"Invoice core.\")\n\n"
                                   "(defn invoice \"Makes one.\" [x] x)\n\n"
                                   "(defn total \"Sums an invoice.\" [xs] xs)\n")))
        ctx (slopp.web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store st})}})
        GET (fn [q] (slopp.web/handle! ctx {:request-method :get
                                      :uri "/api/search"
                                      :query-string q}))
        ok? (fn [b] (is (m/validate contracts/search-results b)
                        (pr-str (m/explain contracts/search-results b))))]
    (testing "a real query answers, contract-shaped, sorted"
      (let [r (GET "q=invoice")
            b (:body r)]
        (is (= 200 (:status r)))
        (ok? b)
        (is (= "invoice" (:query b)))
        (is (pos? (:total b)) (pr-str b))
        (is (= (:total b) (reduce + (vals (:totals b)))) (pr-str b))
        (is (= (mapv :rank (:hits b)) (vec (reverse (sort (mapv :rank (:hits b))))))
            "sorted by the producer, rendered in the order given")
        (is (= "inv.core/invoice" (:name (first (:hits b))))
            (str "an exact name match leads: " (pr-str (mapv :name (:hits b)))))))

    (testing "a BLANK or absent query is the empty state, not an error"
      (doseq [q [nil "" "q=" "q=%20%20"]]
        (let [r (GET q)]
          (is (= 200 (:status r)) (str "query-string " (pr-str q)))
          (ok? (:body r))
          (is (= 0 (:total (:body r))) (pr-str (:body r)))
          (is (= [] (:hits (:body r))) (pr-str (:body r))))))

    (testing "a query nothing matches is 200 too, and the same shape"
      (let [r (GET "q=zzznope")]
        (is (= 200 (:status r)))
        (ok? (:body r))
        (is (= 0 (:total (:body r))))))

    (testing "?limit= cuts the ROWS and never the counts"
      (let [full (:body (GET "q=invoice"))
            one  (:body (GET "q=invoice&limit=1"))]
        (ok? one)
        (is (= 1 (count (:hits one))))
        (is (= (:total full) (:total one))
            "\"showing 1 of N\" must not be able to lie about N")
        (is (= (:totals full) (:totals one)))))

    (testing "a garbage limit falls back to the default rather than refusing"
      ;; same stance as ?depth=banana on the form page: an unreadable row
      ;; budget has an obvious right answer, so there is nothing to 400 about
      (let [r (GET "q=invoice&limit=banana")]
        (is (= 200 (:status r)))
        (ok? (:body r))
        (is (pos? (:total (:body r))))))

    (testing "a limit past the ceiling is clamped, and :total stays honest"
      (let [b (:body (GET "q=invoice&limit=99999"))]
        (ok? b)
        (is (<= (count (:hits b)) (:max model/search-limits)))
        (is (= (:total b) (reduce + (vals (:totals b)))))))))

(deftest the-source-endpoint-hands-back-the-id-it-resolved
  ;; slopp-ui asked for this and argued AGAINST the version that would have been
  ;; easier to say yes to: `:form-id` in the published contract document. They
  ;; ruled that out twice themselves — a form id exists only for a producer whose
  ;; code lives in a store, so a portable document carrying one is the privilege
  ;; `slopp.web.contract` exists to remove.
  ;;
  ;; This endpoint is already store-only: it answers with stored source and
  ;; pretends nothing about portability. So the identity costs the published
  ;; document nothing and closes the one hop a consumer cannot make — from the
  ;; NAME the contract publishes (`:handler`) to the id-addressed page that
  ;; carries callers, callees and warranty.
  (let [st  (store/ingest (store/empty-store) 'src.demo
                          "(ns src.demo)\n\n(defn rate [kg] (* kg 2))\n")
        ctx (slopp.web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store st})}})
        r   (slopp.web/handle! ctx {:request-method :get :uri "/api/source/src.demo/rate"})
        b   (:body r)]
    (testing "the form's own id comes back with its source"
      (is (= 200 (:status r)) (pr-str r))
      (is (= (:id (store/form-named st 'src.demo 'rate)) (:form-id b))
          (str "the id the endpoint resolved, not a name a consumer must resolve: "
               (pr-str b))))
    (testing "the name-addressed answer still says which form it answered for"
      (is (= "src.demo" (:ns b)))
      (is (= "rate" (:name b)))
      (is (re-find #"\(\* kg 2\)" (:source b))))
    (testing "and it validates against the contract that declares it"
      (is (m/validate contracts/form-source b) (pr-str b)))
    (testing "an unknown form is still a 404, not a body with a nil id"
      (is (= 404 (:status (slopp.web/handle! ctx {:request-method :get
                                            :uri "/api/source/src.demo/nope"})))))))

(deftest every-key-a-response-SENDS-is-a-key-its-contract-DECLARES
  ;; The half `web-unconstrained-contract` cannot see. That rule finds a schema
  ;; POSITION that constrains nothing (`[:sequential :map]`, `:any`). This is
  ;; the other direction: a fully-typed `[:map …]` that simply omits entries the
  ;; handler is really sending. `m/validate` passes an OPEN map, so those keys
  ;; are checked by nothing, generated into no client, and documented nowhere —
  ;; and unlike a bare `:map` there is no token in the schema to notice.
  ;;
  ;; Measured when this was written: `/api/timeline` sent EIGHT keys per
  ;; milestone and declared three — `:at :status :agent :sha :more-lines` all
  ;; arrived unannounced, and `:working` sent `:since`, the anchor every other
  ;; number in that map is relative to. Every other endpoint was already clean,
  ;; which is why this is a guard and not a project.
  (let [st  (-> (store/empty-store)
                (store/ingest 'ek.core "(ns ek.core)\n\n(defn rate [kg] (* kg 2))\n"))
        ctx (slopp.web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store st :test-map {}})}})
        get* (fn [uri] (:body (slopp.web/handle! ctx {:request-method :get :uri uri})))
        ;; walk a value against its schema, collecting keys the schema does not
        ;; name. Only descends where the schema does, so an undeclared subtree
        ;; is reported once at its own key rather than as everything inside it.
        undeclared
        (fn undeclared [schema v path]
          (cond
            (and (map? v) (vector? schema) (= :map (first schema)))
            (let [entries (into {} (for [e (remove map? (rest schema))] [(first e) (last e)]))]
              (concat (for [k (keys v) :when (not (contains? entries k))] (conj path k))
                      (mapcat (fn [[k sub]] (undeclared sub (get v k) (conj path k)))
                              (select-keys entries (keys v)))))

            (and (sequential? v) (vector? schema) (= :sequential (first schema)))
            (mapcat #(undeclared (last schema) % path) v)

            :else nil))
        gaps (fn [uri schema] (vec (distinct (undeclared schema (get* uri) []))))]

    (testing "the endpoints this namespace can drive send exactly what they declare"
      (is (= [] (gaps "/api/timeline" contracts/timeline)))
      (is (= [] (gaps "/api/modules" contracts/module-index)))
      (is (= [] (gaps "/api/ns/ek.core" contracts/ns-outline)))
      (is (= [] (gaps "/api/namespaces" contracts/namespace-list)))
      (is (= [] (gaps "/api/source/ek.core/rate" contracts/form-source))))

    (testing "and the walker can SEE an undeclared key — without this the five
              assertions above are five empty lists agreeing with each other"
      (is (= [[:milestones :at]]
             (vec (distinct (undeclared [:map [:milestones [:sequential [:map [:commit :string]]]]]
                                        {:milestones [{:commit "d1" :at "now"}]}
                                        []))))))))

(deftest every-path-parameter-is-declared-in-the-endpoints-request
  ;; slopp-ui, measuring the published document: `/api/form/:id` declares `:id`
  ;; in `:request`, while `/api/change/:range` and `/api/source/:ns/:name`
  ;; declare nil. One document, two conventions.
  ;;
  ;; It never cost them a screen — they derive the parameters from the PATH —
  ;; which is exactly why it went unreported for so long. It costs a consumer
  ;; that trusts `:request`, which generates a client that cannot be called.
  ;;
  ;; The convention is not new: `form`'s own docstring argues it ("declared
  ;; even though this is a GET with no body… without it the generated client
  ;; takes a params map that only the path reads from"). What was missing is
  ;; anything that could tell when an endpoint stopped following it.
  (let [doc      (contract/contract-document ['slopp.api.endpoints])
        declared (fn [schema]
                   ;; entry keys of a [:map [:k …] …], however the schema was
                   ;; named — the document carries VALUES, so by the time we
                   ;; see it the name is gone and it is plain data
                   (when (vector? schema)
                     (set (keep #(when (and (vector? %) (keyword? (first %)))
                                   (first %))
                                (rest schema)))))
        missing  (for [{:keys [path request]} (:endpoints doc)
                       :let [params (map #(keyword (subs % 1))
                                         (re-seq #":[a-zA-Z][a-zA-Z0-9-]*" path))
                             have   (declared request)
                             gap    (remove (or have #{}) params)]
                       :when (seq gap)]
                   {:path path :missing (vec gap) :declares (vec (or have []))})]
    (is (seq (filter #(re-find #"/:" (:path %)) (:endpoints doc)))
        "fixture: the surface HAS parameterised paths — an empty population
         would satisfy the assertion below while checking nothing")
    (is (= [] (vec missing))
        (str "every :param in a path must appear in that endpoint's :request, "
             "or a generated client cannot address it: " (pr-str (vec missing))))))
