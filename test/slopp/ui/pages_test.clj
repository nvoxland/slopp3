(ns slopp.ui.pages-test
  "The store browser through the PORTLESS pipeline: route → policy →
  declared reads → handler, against an in-memory fixture store. The
  escaping assertion is a SECURITY test — the browser renders arbitrary
  store source."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ui.pages :as browse]
            [slopp.store :as store]
            [slopp.web :as web]))

(deftest the-pipeline-serves-the-browser-portlessly
  (let [st  (store/ingest (store/empty-store) 'demo.core
                          "(ns demo.core)\n\n(defn hello \"Says <hi> & more.\" [x] x)\n")
        ctx (web/context {:web/namespaces ['slopp.ui.pages]
                          :web/perform-ctx {:session (atom {:store st})}})]
    (testing "the index → namespace → source click-path"
      (let [r (web/handle! ctx {:request-method :get :uri "/store"})]
        (is (= 200 (:status r)))
        (is (true? (:web/raw r)))
        (is (re-find #"<a href=\"/store/ns/demo\.core\">demo\.core</a>" (:body r))))
      (let [r (web/handle! ctx {:request-method :get :uri "/store/ns/demo.core"})]
        (is (= 200 (:status r)))
        (is (re-find #"<a href=\"/store/source/demo\.core/hello\">hello</a>" (:body r))))
      (let [r (web/handle! ctx {:request-method :get :uri "/store/source/demo.core/hello"})]
        (is (= 200 (:status r)))
        (testing "SECURITY: arbitrary store source arrives escaped"
          (is (re-find #"&lt;hi&gt; &amp; more" (:body r)))
          (is (not (re-find #"<hi>" (:body r)))))))
    (testing "a missing namespace or form is a 404, not a blank page"
      (is (= 404 (:status (web/handle! ctx {:request-method :get :uri "/store/ns/no.pe"}))))
      (is (= 404 (:status (web/handle! ctx {:request-method :get
                                            :uri "/store/source/no.pe/x"})))))))

(deftest handlers-answer-404-as-data
  (testing "a nil read means not-found, answered as a data map"
    (is (= 404 (:status (browse/store-ns-page {:web/reads {}}))))
    (is (= 404 (:status (browse/store-source-page
                         {:web/reads {} :path-params {:ns "x" :name "y"}}))))))

(deftest the-index-page-carries-the-client-filter
  (let [st  (store/ingest (store/empty-store) 'demo.core "(ns demo.core)\n\n(defn f \"D.\" [x] x)\n")
        ctx (web/context {:web/namespaces ['slopp.ui.pages]
                          :web/perform-ctx {:session (atom {:store st})}})]
    (testing "the index page carries the filter box and the per-row hooks the cljs wires"
      (let [body (:body (web/handle! ctx {:request-method :get :uri "/store"}))]
        (is (re-find #"<input[^>]*id=\"ns-filter\"" body) body)
        (is (re-find #"<ul[^>]*id=\"ns-list\"" body) body)
        (is (re-find #"<li[^>]*class=\"ns-row\"" body) body)))
    (testing "every page links the compiled client bundle (a literal src the integrity check joins)"
      (doseq [uri ["/store" "/store/ns/demo.core"]]
        (is (re-find #"<script[^>]*src=\"/js/main\.js\""
                     (:body (web/handle! ctx {:request-method :get :uri uri})))
            uri)))))

(deftest the-browser-styles-itself-with-css-as-data
  (let [st  (store/ingest (store/empty-store) 'demo.core "(ns demo.core)\n\n(defn f \"D.\" [x] x)\n")
        ctx (web/context {:web/namespaces ['slopp.ui.pages]
                          :web/perform-ctx {:session (atom {:store st})}})]
    (testing "the stylesheet is served as text/css, CSS-as-data"
      (let [r (web/handle! ctx {:request-method :get :uri "/css/style.css"})]
        (is (= 200 (:status r)))
        (is (= "text/css; charset=utf-8" (get-in r [:headers "Content-Type"])))
        (is (re-find #"@media\(prefers-color-scheme:dark\)" (:body r)))))
    (testing "every page links the stylesheet (a literal href the integrity check joins)"
      (doseq [uri ["/store" "/store/ns/demo.core" "/store/source/demo.core/f"]]
        (is (re-find #"<link href=\"/css/style\.css\" rel=\"stylesheet\">"
                     (:body (web/handle! ctx {:request-method :get :uri uri})))
            uri)))))

(deftest the-landing-page-is-the-milestone-timeline
  ;; The reviewer's front door answers "what has been finished?" and "what
  ;; is in flight?" in that order. Every milestone row is a link into its
  ;; own change screen, addressed by the range the model precomputed — the
  ;; page does no arithmetic.
  (let [st  (assoc (store/ingest (store/empty-store) 'demo.core
                                 "(ns demo.core)\n\n(defn hello [x] x)\n")
                   :deltas
                   [{:id "d1" :op :add :ns 'demo.core :form-id "f1"}
                    {:id "c1" :op :commit :target "d1" :status :green :at 1784900000000
                     :description "the first milestone"}
                    {:id "d2" :op :add :ns 'demo.core :form-id "f2"}
                    {:id "c2" :op :commit :target "d2" :status :green :at 1784900060000
                     :description "the second milestone\nwith a body line"}
                    {:id "d3" :op :replace :ns 'demo.core :form-id "f1"
                     :prompt "sharpen hello"}])
        ctx (web/context {:web/namespaces ['slopp.ui.pages]
                          :web/perform-ctx {:session (atom {:store st})}})
        r   (web/handle! ctx {:request-method :get :uri "/"})]
    (is (= 200 (:status r)))
    (testing "milestones newest first, each linking its own change screen"
      (let [body (:body r)]
        (is (re-find #"<a href=\"/change/c1\.\.c2\">" body) body)
        (is (< (.indexOf body "the second milestone")
               (.indexOf body "the first milestone"))
            "newest first — the reviewer's scan order")
        (is (nil? (re-find #"/change/\.\." body))
            "the oldest milestone has nothing to diff against and is not a link")))
    (testing "the body of a long description is counted, not printed"
      (is (nil? (re-find #"with a body line" (:body r))))
      (is (re-find #"1 more line" (:body r)) (:body r)))
    (testing "the working set says what is in flight, with the recorded asks"
      (is (re-find #"since c2" (:body r)) (:body r))
      (is (re-find #"sharpen hello" (:body r)) (:body r)))
    (testing "the namespace index is one link away, not the front door"
      (is (re-find #"<a href=\"/store\">" (:body r)) (:body r)))))

(deftest the-change-page-reviews-one-milestone
  ;; Ingest the final state so the forms and the reference graph are real,
  ;; then write the delta log longhand — the log is where before-and-after
  ;; lives, the store only holds "now". (store/ingest re-mints form ids, so
  ;; ingesting twice would read as delete-plus-add.)
  (let [s1     (-> (store/empty-store)
                   (store/ingest 'demo.a.core
                                 "(ns demo.a.core)\n\n(defn hello [x] (inc x))\n")
                   (store/ingest 'demo.b.util
                                 (str "(ns demo.b.util (:require [demo.a.core :as core]))\n\n"
                                      "(defn helper [] (core/hello 1))\n")))
        fid    (fn [ns- nm] (:id (store/form-named s1 ns- nm)))
        a-ns   (fid 'demo.a.core 'demo.a.core)
        hello  (fid 'demo.a.core 'hello)
        b-ns   (fid 'demo.b.util 'demo.b.util)
        helper (fid 'demo.b.util 'helper)
        st     (assoc s1 :deltas
                      [{:id "d1" :op :ingest :ns 'demo.a.core :form-ids [a-ns hello]
                        :sources {a-ns "(ns demo.a.core)" hello "(defn hello [x] x)"}}
                       {:id "c1" :op :commit :status :green :at 1784900000000
                        :description "baseline"}
                       {:id "d2" :op :replace :ns 'demo.a.core :form-id hello
                        :prompt "make hello increment"
                        :sources {hello "(defn hello [x] (inc x))"}}
                       {:id "d3" :op :ingest :ns 'demo.b.util :form-ids [b-ns helper]
                        :sources {b-ns "(ns demo.b.util (:require [demo.a.core :as core]))"
                                  helper "(defn helper [] (core/hello 1))"}}
                       {:id "c2" :op :commit :status :green :at 1784900060000
                        :description "the work"}])
        ctx    (web/context {:web/namespaces ['slopp.ui.pages]
                             :web/perform-ctx {:session (atom {:store st})}})
        body   (:body (web/handle! ctx {:request-method :get :uri "/change/c1..c2"}))]
    (testing "the range titles the page and the count is stated up front"
      (is (re-find #"c1\.\.c2" body) body)
      (is (re-find #"3 forms" body) body))
    (testing "grouped module → namespace → form, with a count at every rung"
      (is (re-find #"<h2>demo\.a <small>1 form<" body) body)
      (is (re-find #"<h3>demo\.a\.core</h3>" body) body)
      (is (re-find #"<h2>demo\.b <small>2 forms<" body) body))
    (testing "each form links its permalink by ID, since names change and ids do not"
      (is (re-find (re-pattern (str "<a href=\"/store/form/" hello "\">")) body) body))
    (testing "the recorded ask is shown — the reviewer reads intent before code"
      (is (re-find #"make hello increment" body) body))
    (testing "the diff is marked up per line, not dumped as two sources"
      (is (re-find #"class=\"del\"[^>]*>-\(defn hello \[x\] x\)" body) body)
      (is (re-find #"class=\"add\"[^>]*>\+\(defn hello \[x\] \(inc x\)\)" body) body))
    (testing "blast radius rides along, and the graph is not claimed complete"
      (is (re-find #"1 caller" body) body)
      (is (re-find #"(?i)syntactic reader|floor, not a census" body) body))
    (testing "a range naming nothing is a 404, not a blank page"
      (is (= 404 (:status (web/handle! ctx {:request-method :get :uri "/change/nope..alsonope"}))))
      (is (= 404 (:status (web/handle! ctx {:request-method :get :uri "/change/garbage"})))))))

(deftest the-form-page-answers-a-cold-arrival
  ;; Arrived at from a link, with no surrounding context — the "lonely
  ;; bubble". The page owes: where am I (breadcrumb), who calls me (above),
  ;; what am I (source), and what do I call (below, with each callee's
  ;; signature and doc INLINED — a link is not visibility).
  (let [st     (-> (store/empty-store)
                   (store/ingest 'demo.a.core
                                 "(ns demo.a.core)\n\n(defn hello \"Adds one.\" [x] (inc x))\n")
                   (store/ingest 'demo.b.util
                                 (str "(ns demo.b.util (:require [demo.a.core :as core]))\n\n"
                                      "(defn helper [] (core/hello 1))\n")))
        fid    (fn [ns- nm] (:id (store/form-named st ns- nm)))
        ctx    (web/context {:web/namespaces ['slopp.ui.pages]
                             :web/perform-ctx {:session (atom {:store st})}})
        page   (fn [id] (web/handle! ctx {:request-method :get
                                          :uri (str "/store/form/" id)}))
        hello  (:body (page (fid 'demo.a.core 'hello)))
        helper (:body (page (fid 'demo.b.util 'helper)))]
    (testing "the breadcrumb says where this is, module and namespace both"
      (is (re-find #"<nav><a href=\"/store\">store</a> / demo\.a / " hello) hello)
      (is (re-find #"<a href=\"/store/ns/demo\.a\.core\">" hello) hello))
    (testing "signature and doc, before the source"
      (is (re-find #"\[x\]" hello) hello)
      (is (re-find #"Adds one\." hello) hello))
    (testing "the source is highlighted from the CST, so it reads as code"
      (is (re-find #"<span class=\"special\">defn</span>" hello) hello)
      (is (re-find #"<span class=\"string\">&quot;Adds one\.&quot;</span>" hello)
          "a docstring is ONE token — the tokenizer walks a tree, not text")
      (is (re-find #"<span class=\"delim\">\(</span>" hello) hello))
    (testing "callers are a CARD, grouped by the via that found each edge"
      (is (re-find #"(?i)static" hello) hello)
      (is (re-find (re-pattern (str "<a href=\"/store/form/" (fid 'demo.b.util 'helper)
                                    "\">demo\\.b\\.util/helper</a>"))
                   hello)
          hello))
    (testing "callees are INLINED with their own signature and doc, not just linked"
      (is (re-find #"demo\.a\.core/hello" helper) helper)
      (is (re-find #"Adds one\." helper)
          "the callee's doc appears on the CALLER's page — that is the whole point"))
    (testing "the graph is never presented as complete"
      (is (re-find #"(?i)floor, not a census|syntactic reader" hello) hello))
    (testing "an unknown id is a 404, not a blank page"
      (is (= 404 (:status (page "f-nope")))))))

(deftest the-form-page-carries-its-fidelity-in-the-url
  ;; The whole point of having the parameter now: a permalink says WHICH
  ;; rendering it is. A request for a fidelity that does not exist must
  ;; 404, not quietly serve the one that does — when the labeled notation
  ;; lands, an old `?view=labeled` link should start working, and until
  ;; then it must not pretend it already does.
  (let [st   (store/ingest (store/empty-store) 'demo.core
                           "(ns demo.core)\n\n(defn f \"D.\" [x] x)\n")
        ctx  (web/context {:web/namespaces ['slopp.ui.pages]
                           :web/perform-ctx {:session (atom {:store st})}})
        fid  (:id (store/form-named st 'demo.core 'f))
        get- (fn [qs] (web/handle! ctx (cond-> {:request-method :get
                                                :uri (str "/store/form/" fid)}
                                         qs (assoc :query-string qs))))]
    (is (= 200 (:status (get- nil))) "a bare permalink renders the default")
    (is (= 200 (:status (get- "view=clojure"))))
    (is (= (:body (get- nil)) (:body (get- "view=clojure")))
        "the default IS clojure, byte for byte")
    (is (= 404 (:status (get- "view=labeled"))))
    (is (= 404 (:status (get- "view=nonsense"))))
    (testing "a query string the page does not care about changes nothing"
      (is (= (:body (get- nil)) (:body (get- "utm_source=somewhere")))))))

(deftest the-client-bundle-is-served-as-something-a-browser-will-RUN
  ;; Two failures deep, this one. The bundle existed in the files manifest the
  ;; whole time; nothing mounted it, so /assets/cljs/main.js 404'd on every
  ;; page. Then, once served, it came back as application/json — 1.5MB of
  ;; JavaScript a browser will not execute. A 200 is not the bar; the bar is
  ;; that the script RUNS.
  (let [st  (-> (store/empty-store)
                (store/ingest 'demo.core "(ns demo.core)\n\n(defn f \"D.\" [x] x)\n")
                (as-> s (first (store/record-file-put s "public/cljs/main.js"
                                                     "console.log('hi');"))))
        ctx (web/context {:web/namespaces ['slopp.ui.pages]
                          :web/perform-ctx {:session (atom {:store st})}})
        r   (web/handle! ctx {:request-method :get :uri "/js/main.js"})]
    (is (= 200 (:status r)))
    (is (= "console.log('hi');" (:body r))
        "verbatim — not JSON-encoded, which is what :web/raw buys")
    (is (re-find #"javascript" (str (get-in r [:headers "Content-Type"])))
        (str "a browser refuses to execute a non-JS type: "
             (pr-str (get-in r [:headers "Content-Type"])))))
  (testing "a store that never compiled a client gets an empty body, not a 404"
    ;; a 404 here is indistinguishable from a broken route, and the page
    ;; cannot tell the difference either
    (let [ctx (web/context
               {:web/namespaces ['slopp.ui.pages]
                :web/perform-ctx {:session (atom {:store (store/empty-store)})}})
          r   (web/handle! ctx {:request-method :get :uri "/js/main.js"})]
      (is (= 204 (:status r)) (pr-str r)))))
