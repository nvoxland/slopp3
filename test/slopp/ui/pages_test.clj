(ns slopp.ui.pages-test
  "The store browser through the PORTLESS pipeline: route → policy →
  declared reads → handler, against an in-memory fixture store. The
  escaping assertion is a SECURITY test — the browser renders arbitrary
  store source."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.web :as web] [slopp.ui.server :as server]))

(deftest the-document-is-the-only-html-the-server-renders
  ;; Six server-rendered pages collapsed into one document. What the server
  ;; still owes is small and worth stating exactly, because everything else
  ;; asserted here before is now a view test on hiccup data
  ;; (slopp.ui.views-test) or an endpoint test on JSON (slopp.ui.api-test).
  (let [st  (store/ingest (store/empty-store) 'demo.core
                          "(ns demo.core)\n\n(defn hello \"Says <hi> & more.\" [x] x)\n")
        ctx (web/context {:web/namespaces server/served-namespaces
                          :web/perform-ctx {:session (atom {:store st})}})
        GET (fn [uri] (web/handle! ctx {:request-method :get :uri uri}))]
    (testing "the document is a mount point and two assets — no screen in it"
      (let [r (GET "/")]
        (is (= 200 (:status r)))
        (is (re-find #"<div id=\"app\"></div>" (:body r)))
        (is (re-find #"<link href=\"/css/style\.css\" rel=\"stylesheet\">" (:body r)))
        (is (re-find #"<script defer src=\"/js/main\.js\"></script>" (:body r)))
        (testing "and NO server-rendered screen content"
          ;; the property the rewrite buys: ONE renderer, so there is no
          ;; server copy of a screen that can drift from the browser's
          (is (not (re-find #"demo\.core" (:body r)))))))
    (testing "every client route survives a refresh, via the declared fallback"
      ;; each of these is real to views/route-for and meaningless to the
      ;; server's router — without :web/spa a reload would 404
      (doseq [uri ["/store" "/store/ns/demo.core" "/store/form/f1"
                   "/store/source/demo.core/hello" "/change/d1..d2"]]
        (let [r (GET uri)]
          (is (= 200 (:status r)) uri)
          (is (re-find #"<div id=\"app\"></div>" (:body r)) uri))))
    (testing "a path outside every declared prefix still 404s"
      ;; the reason the fallback is SCOPED — an app that can never 404 has no
      ;; way left to tell a typo from a page
      (is (= 404 (:status (GET "/nonsense"))))
      (is (= 404 (:status (GET "/store-not-really")))))))

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
