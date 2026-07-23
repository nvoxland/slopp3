(ns slopp.http.browse-test
  "The store browser through the PORTLESS pipeline: route → policy →
  declared reads → handler, against an in-memory fixture store. The
  escaping assertion is a SECURITY test — the browser renders arbitrary
  store source."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.http.browse :as browse]
            [slopp.store :as store]
            [slopp.web :as web]))

(deftest the-pipeline-serves-the-browser-portlessly
  (let [st  (store/ingest (store/empty-store) 'demo.core
                          "(ns demo.core)\n\n(defn hello \"Says <hi> & more.\" [x] x)\n")
        ctx (web/context {:web/namespaces ['slopp.http.browse]
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
        ctx (web/context {:web/namespaces ['slopp.http.browse]
                          :web/perform-ctx {:session (atom {:store st})}})]
    (testing "the index page carries the filter box and the per-row hooks the cljs wires"
      (let [body (:body (web/handle! ctx {:request-method :get :uri "/store"}))]
        (is (re-find #"<input[^>]*id=\"ns-filter\"" body) body)
        (is (re-find #"<ul[^>]*id=\"ns-list\"" body) body)
        (is (re-find #"<li[^>]*class=\"ns-row\"" body) body)))
    (testing "every page links the compiled client bundle (a literal src the integrity check joins)"
      (doseq [uri ["/store" "/store/ns/demo.core"]]
        (is (re-find #"<script[^>]*src=\"/assets/cljs/main\.js\""
                     (:body (web/handle! ctx {:request-method :get :uri uri})))
            uri)))))

(deftest the-browser-styles-itself-with-css-as-data
  (let [st  (store/ingest (store/empty-store) 'demo.core "(ns demo.core)\n\n(defn f \"D.\" [x] x)\n")
        ctx (web/context {:web/namespaces ['slopp.http.browse]
                          :web/perform-ctx {:session (atom {:store st})}})]
    (testing "the stylesheet is served as text/css, CSS-as-data"
      (let [r (web/handle! ctx {:request-method :get :uri "/store/style.css"})]
        (is (= 200 (:status r)))
        (is (= "text/css; charset=utf-8" (get-in r [:headers "Content-Type"])))
        (is (re-find #"@media\(prefers-color-scheme:dark\)" (:body r)))))
    (testing "every page links the stylesheet (a literal href the integrity check joins)"
      (doseq [uri ["/store" "/store/ns/demo.core" "/store/source/demo.core/f"]]
        (is (re-find #"<link href=\"/store/style\.css\" rel=\"stylesheet\">"
                     (:body (web/handle! ctx {:request-method :get :uri uri})))
            uri)))))
