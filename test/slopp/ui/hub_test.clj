(ns slopp.ui.hub-test
  "What the hub promises: a beat both registers and keeps alive, the list it
  publishes matches the contract the picker validates against, a project that
  is not answering produces a PAGE rather than a failed fetch, and — over the
  real wire — a registered project's bytes arrive through the proxy unchanged."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [malli.core :as m]
            [slopp.ui.hub :as hub]
            [slopp.ui.registry :as reg]
            [slopp.ui.contracts :as contracts]
            [slopp.web :as web])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(deftest a-beat-registers-and-the-list-answers-the-contract
  (let [registry (atom {})
        req      (fn [body] {:web/deps {:registry registry} :body body})
        beat     {:name "slopp2" :dir "/w/slopp2" :url "http://127.0.0.1:52104/"
                  :pid 41 :version "0.4.0" :status "idle"}]
    (testing "a beat answers with the slug the project was given and the
              interval it should beat at — a project must not have to hold a
              second copy of either number"
      (let [{:keys [status body]} (hub/register! (req beat))]
        (is (= 200 status))
        (is (= "slopp2" (:slug body)))
        (is (= reg/beat-ms (:beat-ms body)))))
    (testing "the list endpoint answers the published contract"
      (let [{:keys [status body]} (hub/projects (req nil))]
        (is (= 200 status))
        (is (= 1 (count body)))
        (is (m/validate contracts/project-list body)
            (str "project-list contract: " (m/explain contracts/project-list body)))
        (is (true? (:available (first body))))
        (is (= "http://127.0.0.1:52104/" (:url (first body))))))
    (testing "a project with nothing to say about itself still validates —
              pid, version and status are the keys a beat may not know"
      (let [_ (hub/register! (req {:name "bare" :dir "/w/bare" :url "http://127.0.0.1:1/"}))
            {:keys [body]} (hub/projects (req nil))]
        (is (m/validate contracts/project-list body)
            (str "project-list contract: " (m/explain contracts/project-list body)))
        (is (= 2 (count body)))))
    (testing "deregistering drops it"
      (hub/deregister! (req {:dir "/w/bare"}))
      (is (= ["slopp2"] (mapv :name (:body (hub/projects (req nil)))))))))

(deftest an-unreachable-project-is-a-PAGE-not-a-failed-fetch
  (let [registry (atom {})
        call     (fn [f params]
                   (f {:web/deps {:registry registry} :path-params params}))]
    (testing "both paths are DECLARED endpoints, so the route-integrity check
              can see them: the picker's own /p/ links are joined against the
              served routes, and a proxy hidden in programmatic rows reads to
              that check as a dangling link"
      (is (= "/p/:slug" (:web/path (meta #'hub/project-root))))
      (is (= "/p/:slug/*path" (:web/path (meta #'hub/project-path))))
      (is (= :get (:web/method (meta #'hub/project-root)))))
    (testing "two endpoints, not one: the router's trailing catch-all needs at
              least one segment, so /p/<slug> alone would 404 — and that is
              the first link a human clicks from the picker"
      (is (not= #'hub/project-root #'hub/project-path)))
    (testing "a slug nobody holds is a 404 that says so in words"
      (let [r (call hub/project-path {:slug "ghost" :path "store"})]
        (is (= 404 (:status r)))
        (is (str/includes? (str (:body r)) "ghost"))))
    (swap! registry reg/beat {:name "slopp2" :dir "/w/s" :url "http://127.0.0.1:1/"} 0)
    (testing "a project that stopped beating answers 503 and NAMES itself — the
              human gets a page explaining that the project is not running,
              never a dead fetch in a console"
      (let [r (call hub/project-path {:slug "slopp2" :path "store"})]
        (is (= 503 (:status r)))
        (is (str/includes? (str (:body r)) "slopp2"))))
    (testing "the project root answers the same way as a path beneath it"
      (is (= 503 (:status (call hub/project-root {:slug "slopp2"}))))
      (is (= 404 (:status (call hub/project-root {:slug "ghost"})))))))

(defn- fetch!
  "GET `url` and return `{:status :body :content-type}` — raw text, because
  what the proxy must not do is re-encode anything."
  [url]
  (let [client (HttpClient/newHttpClient)
        resp   (.send client (.build (HttpRequest/newBuilder (URI. url)))
                      (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body (.body resp)
     :content-type (-> (.headers resp) (.firstValue "content-type") (.orElse ""))}))

(defn- beat!
  "POST a check-in to `hub-url`, exactly as a project's heartbeat does."
  [hub-url body]
  (let [client (HttpClient/newHttpClient)
        req    (-> (HttpRequest/newBuilder (URI. (str hub-url "api/register")))
                   (.header "Content-Type" "application/json")
                   (.POST (HttpRequest$BodyPublishers/ofString (json/generate-string body)))
                   (.build))]
    (json/parse-string (.body (.send client req (HttpResponse$BodyHandlers/ofString))) true)))

(deftest ^:external a-registered-projects-bytes-arrive-through-the-proxy-unchanged
  (let [project (web/serve!
                 {:web/namespaces []
                  :web/routes [{:method :get :path "/hello" :auth :public
                                :handler (fn [req]
                                           {:status 200 :web/raw true
                                            :headers {"Content-Type" "text/plain; charset=utf-8"}
                                            :body (str "from the project"
                                                       (some->> (:query-string req) (str " ?")))})}]
                  :web/host "127.0.0.1" :web/port 0})
        hub     (hub/serve! 0)]
    (try
      (testing "a beat over the wire comes back with the assigned slug and interval"
        (let [r (beat! (:url hub) {:name "toy" :dir "/w/toy"
                                   :url (str "http://127.0.0.1:" (:port project) "/")
                                   :status "idle"})]
          (is (= "toy" (:slug r)))
          (is (= reg/beat-ms (:beat-ms r)))))
      (testing "the proxy forwards the path and hands back the project's own
                bytes and content type — nothing is re-encoded, which is what
                lets one pipe carry a JS bundle, an SVG and JSON alike"
        (let [{:keys [status body content-type]}
              (fetch! (str (:url hub) "p/toy/hello"))]
          (is (= 200 status))
          (is (= "from the project" body))
          (is (str/includes? content-type "text/plain"))))
      (testing "the query string rides along — it is a separate request key,
                and dropping it silently breaks every filtered view"
        (is (= "from the project ?q=1"
               (:body (fetch! (str (:url hub) "p/toy/hello?q=1"))))))
      (testing "the picker lists the project it is fronting"
        (let [{:keys [status body]} (fetch! (:url hub))]
          (is (= 200 status))
          (is (str/includes? body "toy"))
          (is (str/includes? body "/p/toy/"))))
      (testing "a project that never registered is a page, not a stack trace"
        (let [{:keys [status body]} (fetch! (str (:url hub) "p/ghost/"))]
          (is (= 404 status))
          (is (str/includes? body "ghost"))))
      (finally
        (hub/stop! hub)
        (web/stop! project)))))
