(ns slopp.ui.api-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [slopp.store :as store]
            [slopp.ui.api]
            [slopp.ui.contracts :as contracts]
            [slopp.web :as web] [slopp.ui.server :as server] [slopp.api.external :as external] [slopp.api :as api] [cheshire.core :as json] [clojure.string :as str]))

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
                                        {:name "hello" :doc "Says hi."}]}
               (:body r)))
        (is (m/validate contracts/ns-outline (:body r)))))
    (testing "a form with no docstring carries an explicit nil, not a missing key"
      ;; :maybe in the contract is the promise; this is the promise being kept
      (let [r (GET "/api/ns/demo.util")]
        (is (= {:ns "demo.util" :forms [{:name "demo.util" :doc nil}
                                        {:name "undocumented" :doc nil}]}
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
