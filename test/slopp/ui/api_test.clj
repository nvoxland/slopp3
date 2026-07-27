(ns slopp.ui.api-test
  "Tests for the JSON boundary, run through the REAL dispatcher.

  Two disciplines, both learned from failures. First, build the context from
  `server/served-namespaces` rather than a hand-picked subset: endpoints and
  their read performers live in different namespaces, so a context holding
  only `slopp.ui.api` answers 500 and tests nothing — and a bundle served by
  nothing once 404'd for two waves behind a 200 for the page.

  Second, validate every response against the SAME contract var the typed
  client is generated from. A handler that drifts from its declared shape is
  a red here rather than a surprise in someone's browser tab, which is the
  whole argument for declaring contracts at all."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [slopp.store :as store]
            [slopp.ui.api]
            [slopp.ui.contracts :as contracts]
            [slopp.web :as web] [slopp.ui.server :as server] [slopp.api.external :as external] [slopp.api :as api] [cheshire.core :as json] [clojure.string :as str] [clojure.edn :as edn] [slopp.api.cljs :as cljs]))

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
        ;; endpoints declare are performed by slopp.ui.pages, so a context
        ;; holding only slopp.ui.api answers 500 and tests nothing real
        ctx (web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store st})}})
        GET (fn [uri] (web/handle! ctx {:request-method :get :uri uri}))]
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
        (is (= {:ns "demo.core" :forms [{:name "demo.core" :doc nil}
                                        {:name "hello" :doc "Says hi."}]
                :tested-by []}
               (:body r))
            "nothing tests this fixture, and that reports as an empty list rather
             than a missing key — the page should be able to SAY untested")
        (is (m/validate contracts/ns-outline (:body r)))))
    (testing "a form with no docstring carries an explicit nil, not a missing key"
      ;; :maybe in the contract is the promise; this is the promise being kept
      (let [r (GET "/api/ns/demo.util")]
        (is (= {:ns "demo.util" :forms [{:name "demo.util" :doc nil}
                                        {:name "undocumented" :doc nil}]
                :tested-by []}
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
  (testing "the served list names both halves of a request"
    (is (= #{'slopp.ui.pages 'slopp.ui.api}
           (set server/served-namespaces))))
  (testing "serving that list actually routes both /api and the pages"
    ;; the endpoints and the READ performers live in different namespaces, so
    ;; a list missing either half fails here rather than in a browser — and
    ;; missing the performer half is a 500, not a 404
    (let [st  (store/ingest (store/empty-store) 'demo.core "(ns demo.core)\n")
          ctx (web/context {:web/namespaces server/served-namespaces
                            :web/perform-ctx {:session (atom {:store st})}})]
      (is (= 200 (:status (web/handle! ctx {:request-method :get
                                            :uri "/api/namespaces"}))))
      (is (= 200 (:status (web/handle! ctx {:request-method :get
                                            :uri "/store"})))))))

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
      (api/ingest! sess 'demo.core
                   (str "(ns demo.core)\n\n(defn hello \"Says hi.\" [x] x)\n\n"
                        ;; a real CALLER, because [:sequential …] over an empty
                        ;; list validates vacuously — with no caller the whole
                        ;; :callers half of the contract is never checked, and
                        ;; that half is where the wire types actually bite
                        "(defn greet [x] (hello x))\n")
                   :prompt "the demo form")
      (let [ctx (web/context {:web/namespaces server/served-namespaces
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
                        r (web/handle! ctx (cond-> {:request-method :get :uri path}
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
      (finally (api/close! sess)))))

(deftest the-modules-endpoint-serves-a-contract-shaped-architecture
  (let [st  (-> (store/empty-store)
                (store/ingest 'demo.a.core "(ns demo.a.core)\n\n(defn hello [] 1)\n")
                (store/ingest 'demo.a.core-test
                              (str "(ns demo.a.core-test (:require [demo.a.core :as c]))\n\n"
                                   "(defn t [] (c/hello))\n"))
                (store/ingest 'demo.b.util
                              (str "(ns demo.b.util (:require [demo.a.core :as c]))\n\n"
                                   "(defn helper [] (c/hello))\n")))
        ctx (web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store st})}})
        res (web/handle! ctx {:request-method :get :uri "/api/modules"})]
    (testing "the route is served and its response satisfies the declared contract"
      ;; a violation fails HERE, at the boundary, rather than rendering wrong
      (is (= 200 (:status res))))
    (testing "it answers with the architecture, not a namespace dump"
      (let [body (:body res)]
        (is (= ["demo.a" "demo.b"] (mapv :module (:modules body))))
        (is (= 1 (:tests (first (:modules body)))))
        (is (= 2 (count (:nodes (:picture body)))))
        (is (= [["demo.b" "demo.a"]]
               (mapv (juxt :from :to) (:edges (:picture body)))))))))

(deftest the-svg-export-is-a-standalone-file-not-a-fragment
  (let [st  (-> (store/empty-store)
                (store/ingest 'demo.a.core "(ns demo.a.core)\n\n(defn hello [] 1)\n")
                (store/ingest 'demo.b.util
                              (str "(ns demo.b.util (:require [demo.a.core :as c]))\n\n"
                                   "(defn helper [] (c/hello))\n")))
        ctx (web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store st})}})
        r   (web/handle! ctx {:request-method :get :uri "/api/modules.svg"})
        body (:body r)]
    (testing "served as a file a browser and a design tool both recognise"
      (is (= 200 (:status r)))
      (is (:web/raw r) "markup must arrive untouched, not JSON-wrapped")
      (is (= "image/svg+xml; charset=utf-8" (get (:headers r) "Content-Type"))))
    (testing "it is a whole SVG document, not a fragment"
      (is (string? body))
      (is (re-find #"^<svg " body))
      (is (re-find #"</svg>$" body)))
    (testing "it carries its own styling — class names with no CSS open as black boxes"
      (is (re-find #"<style>" body))
      (is (re-find #"module-node" body))
      (is (re-find #"prefers-color-scheme" body)
          "including dark mode, so the file adapts wherever it lands"))
    (testing "and it draws the actual store"
      (is (re-find #"demo\.a" body))
      (is (re-find #"demo\.b" body)))))

(deftest the-contract-endpoint-publishes-what-the-client-is-generated-from
  ;; The seam that lets the reviewer UI live in its OWN store: it consumes this
  ;; document instead of reading slopp's contracts namespace. So the property
  ;; that matters is not "some document is served" but "the schema published
  ;; for an endpoint IS the var that endpoint declares" — anything weaker and a
  ;; generated client would validate against a shape the server never promised.
  (let [ctx (web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store (store/empty-store)})
                                            :served-namespaces server/served-namespaces}})
        r   (web/handle! ctx {:request-method :get :uri "/api/contracts"})
        doc (edn/read-string (:body r))
        by-path (into {} (map (juxt :path identity)) (:endpoints doc))]

    (testing "EDN verbatim — JSON would flatten a keyword schema into a string"
      (is (= 200 (:status r)))
      (is (:web/raw r) "the body must arrive untouched by the adapter's encoder")
      (is (= "application/edn" (get-in r [:headers "Content-Type"]))))

    (testing "the document is versioned and lists the typed endpoints"
      (is (= 1 (:slopp/contract-version doc)))
      (is (= #{"/api/namespaces" "/api/ns/:ns" "/api/timeline" "/api/change/:range"
               "/api/form/:id" "/api/source/:ns/:name" "/api/modules"}
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
  ;; slopp.ui.contracts generates, from HTTP alone, a client equivalent to the
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
          (is (= #{"namespaces" "ns-outline" "timeline" "change" "form" "source" "modules"}
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
        (api/close! consumer)))))
