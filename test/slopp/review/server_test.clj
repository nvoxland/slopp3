(ns slopp.review.server-test
  "The project listener's two promises: it serves the CALLER's session (the
  reason it is not the MCP transport), and its address is derived rather than
  fixed, so two projects on one machine never fight for a port."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.review.server :as server]
            [slopp.store :as store]
            [slopp.web :as web] [clojure.edn :as edn] [slopp.web.client :as client]))

(deftest ^:external ui-serve-serves-the-callers-own-session
  ;; The point of a second listener. slopp.mcp.http/start-server! opens a
  ;; FRESH session, which is not blank — :test-map and :observed persist and
  ;; reload — but is BEHIND the session doing the work, and costs a second
  ;; image to be behind in. The proof is a namespace that exists ONLY in the
  ;; session handed over: if the page can see it, the page is reading that
  ;; session.
  (let [st   (store/ingest (store/empty-store) 'demo.only.here
                           "(ns demo.only.here)\n\n(defn f [] 1)\n")
        sess (atom {:store st})]
    (try
      (let [r (server/serve! sess 0)]
        (testing "the BOUND port is reported, not the requested one"
          (is (pos? (:port r)) "port 0 means ephemeral — echoing 0 back would be a lie")
          (is (= (str "http://127.0.0.1:" (:port r) "/") (:url r))
              "the url lands on the reviewer's front door"))
        (testing "the served DATA comes from the handed-over session"
          ;; the document is an empty mount point now and carries no store
          ;; content at all, so the proof moved one layer down to the API the
          ;; client fetches. Same evidence: a namespace that exists ONLY in
          ;; this session appears, so this session is what is being served.
          (is (re-find #"demo\.only\.here"
                       (:http/body
                        (client/request
                         {:http/url (str "http://127.0.0.1:" (:port r)
                                         "/api/namespaces")}))))))
      (finally (server/stop!)))))

(deftest ^:external ui-serve-evicts-itself-and-names-a-taken-port
  ;; Two stances taken from Clerk, which learned both the hard way: never
  ;; port-hunt (the url you were told stops being the url that works), and
  ;; say "port N is not available" rather than surfacing a BindException.
  (let [sess (atom {:store (store/empty-store)})]
    (try
      (testing "serving again evicts the previous server — one UI, one port"
        (let [a (server/serve! sess 0)
              b (server/serve! sess 0)]
          (is (not= (:port a) (:port b)) "a second ephemeral bind is a different port")
          (is (= (:port b) (:port (server/running)))
              "the tracked server is the live one")
          (is (= :unreachable
                     (:http/error
                      (try (client/request
                            {:http/url (str "http://127.0.0.1:" (:port a) "/store")})
                           nil
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))))
              "the evicted server is actually stopped, not merely forgotten —
               and asserting the port's :http/error rather than a bare
               IOException is the point: a stopped server and a server that
               said no are different facts, and only one of them is this")))
      (finally (server/stop!))))
  (testing "a port someone else holds is reported as a sentence, not a stack trace"
    (let [held (web/serve! {:web/namespaces [] :web/port 0})]
      (try
        (let [r (server/serve! (atom {:store (store/empty-store)}) (:port held))]
          (is (= (str "port " (:port held) " is not available") (:error r)))
          (is (nil? (server/running)) "a failed bind leaves nothing tracked"))
        (finally (web/stop! held) (server/stop!))))))

(deftest the-ui-port-is-derived-from-the-dir-and-salted-away-from-the-git-listener
  (testing "stable across calls, so a url that worked last session still does"
    (is (= (server/derived-port "/w/a") (server/derived-port "/w/a"))))
  (testing "inside the private range"
    (is (<= 49152 (server/derived-port "/w/a") 65535)))
  (testing "two projects on one machine get two ports — the collision a fixed
            default guaranteed, and the reason ui.port now defaults to unset"
    (is (not= (server/derived-port "/w/a") (server/derived-port "/w/b"))))
  (testing "SALTED, so it does not land on the git listener's port for the
            same dir: one MCP process binds both, and an unsalted formula
            would make every project collide with itself"
    (is (not= (server/derived-port "/w/a")
              (+ 49152 (mod (hash "/w/a") 16384))))))

(deftest the-preferred-port-resolves-explicit-then-configured-then-derived
  (let [pinned (assoc-in (store/empty-store) [:config "capabilities" :values]
                         {"ui.port" "7400"})]
    (testing "an explicit request wins — ui_serve {port} still means that port"
      (is (= 9000 (server/preferred-port pinned "/w/a" 9000))))
    (testing "then the configured value, for someone who wants a fixed address"
      (is (= 7400 (server/preferred-port pinned "/w/a" nil))))
    (testing "then the derivation, which is the ordinary case: nothing is
              configured and nothing collides"
      (is (= (server/derived-port "/w/a")
             (server/preferred-port (store/empty-store) "/w/a" nil))))
    (testing "an ephemeral session has no dir to derive from, so it takes
              whatever port is free rather than refusing to serve"
      (is (= 0 (server/preferred-port (store/empty-store) nil nil))))))

(deftest ^:external a-real-server-publishes-its-own-contract
  ;; serve! is the only thing that knows which namespaces it serves, so that
  ;; list reaches the read performer through perform-ctx. Forget to thread it
  ;; and /api/contracts still answers 200 — with zero endpoints. A consumer
  ;; would generate an empty client from it and nothing would look broken
  ;; until a call that was never generated went missing.
  ;;
  ;; In-image tests cannot catch this: they build perform-ctx themselves, so
  ;; they pass whether or not the SERVER does. Only a real serve! can tell.
  (let [sess (atom {:store (store/empty-store)})]
    (try
      (let [r   (server/serve! sess 0)
            doc (edn/read-string
                 (:http/body
                  (client/request
                   {:http/url (str "http://127.0.0.1:" (:port r) "/api/contracts")})))
            paths (set (map :path (:endpoints doc)))]
        (is (= 1 (:slopp/contract-version doc)))
        (is (contains? paths "/api/timeline")
            "a server that forgot to thread its namespace list publishes nothing")
        (is (contains? paths "/api/modules")))
      (finally (server/stop!)))))
