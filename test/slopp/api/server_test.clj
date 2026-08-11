(ns slopp.api.server-test
  "The project listener's two promises: it serves the CALLER's session (the
  reason it is not the MCP transport), and its address is derived rather than
  fixed, so two projects on one machine never fight for a port."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api.server :as server]
            [slopp.store :as store]
            [slopp.web :as slopp.web] [clojure.edn :as edn] [slopp.web.client :as web.client] [clojure.set :as set] [clojure.string :as str]))

(deftest ^:external ui-serve-serves-the-callers-own-session
  ;; The listener serves the CALLER's session rather than opening one. A
  ;; fresh session is not blank — :test-map and :observed persist and reload
  ;; — but it is BEHIND the session doing the work, and costs a second image
  ;; to be behind in. A page that showed the warranty as of the last write
  ;; instead of as of now would be wrong exactly when someone is watching it
  ;; change. The proof is a namespace that exists ONLY in the session handed
  ;; over: if the page can see it, the page is reading that session.
  ;;
  ;; (This used to be phrased against slopp.mcp.http/start-server!, which
  ;; opened a fresh one. That transport is retired; the reason stands on its
  ;; own, because it was always about staleness rather than about the other
  ;; server.)
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
                        (web.client/request
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
                      (try (web.client/request
                            {:http/url (str "http://127.0.0.1:" (:port a) "/store")})
                           nil
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))))
              "the evicted server is actually stopped, not merely forgotten —
               and asserting the port's :http/error rather than a bare
               IOException is the point: a stopped server and a server that
               said no are different facts, and only one of them is this")))
      (finally (server/stop!))))
  (testing "a port someone else holds is reported as a sentence, not a stack trace"
    (let [held (slopp.web/serve! {:web/namespaces [] :web/port 0})]
      (try
        (let [r (server/serve! (atom {:store (store/empty-store)}) (:port held))]
          ;; the same sentence every listener uses — slopp.web/bind-diagnosis writes
          ;; it once. Three listeners phrased this three ways ("is not available"
          ;; here, "is already in use" in the dev server, a raw BindException in
          ;; production), which is the disagreement this consolidates.
          (is (= (str "port " (:port held) " is already in use") (:error r)))
          (is (nil? (server/running)) "a failed bind leaves nothing tracked"))
        (finally (slopp.web/stop! held) (server/stop!))))))

(deftest the-ui-port-is-derived-from-the-dir-and-the-formula-is-frozen
  (testing "stable across calls, so a url that worked last session still does"
    (is (= (server/derived-port "/w/a") (server/derived-port "/w/a"))))
  (testing "inside the private range"
    (is (<= 49152 (server/derived-port "/w/a") 65535)))
  (testing "two projects on one machine get two ports — the collision a fixed
            default guaranteed, and the reason there is no port setting here
            at all any more"
    (is (not= (server/derived-port "/w/a") (server/derived-port "/w/b"))))
  ;; The salt was originally to dodge the git listener's port for the same
  ;; dir. That listener is gone, so this no longer separates it from
  ;; anything — it pins the formula instead, which is the property that
  ;; actually matters now: the derivation IS the address, so changing it
  ;; relocates every project's UI and strands every saved url.
  (testing "the formula is frozen — an unsalted hash is a DIFFERENT address"
    (is (not= (server/derived-port "/w/a")
              (+ 49152 (mod (hash "/w/a") 16384))))))

(deftest the-preferred-port-is-derived-and-never-configured
  ;; `slopp.api.port` was a CAPABILITY until phase 2 (2026-08-03). The knob
  ;; went; the derivation stayed. That combination is easy to state backwards,
  ;; so: the number is an OUTPUT — nobody sets it — but it is the SAME output
  ;; on every restart, because the formula IS the address and a port that
  ;; moves strands whatever held the url (D-hub). Unconfigured, not unstable.
  ;;
  ;; The knob was removable because nothing used it: the one external adopter
  ;; sets three capability values and this was not among them, and
  ;; `ui_serve {port}` already covers what a pin was for — wanting a specific
  ;; address for one run.
  ;;
  ;; There is no "a stale value is ignored" case to assert here any more, and
  ;; that is the strongest form of the guarantee rather than a gap in it: the
  ;; fn takes no store, so no reader can be left behind for one caller. What
  ;; a removed capability still owes is that the REGISTRY stopped governing
  ;; the key, which `slopp.project.capabilities-test` asserts.
  (testing "an explicit request wins — ui_serve {port} still means that port"
    (is (= 9000 (server/preferred-port "/w/a" 9000))))
  (testing "otherwise the derivation, which is now the only ordinary case"
    (is (= (server/derived-port "/w/a")
           (server/preferred-port "/w/a" nil))))
  (testing "an ephemeral session has no dir to derive from, so it takes
            whatever port is free rather than refusing to serve"
    (is (= 0 (server/preferred-port nil nil)))))

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
                  (web.client/request
                   {:http/url (str "http://127.0.0.1:" (:port r) "/api/contracts")})))
            paths (set (map :path (:endpoints doc)))]
        (is (= 1 (:slopp/contract-version doc)))
        (is (contains? paths "/api/timeline")
            "a server that forgot to thread its namespace list publishes nothing")
        (is (contains? paths "/api/modules")))
      (finally (server/stop!)))))

(deftest the-served-list-is-checked-against-what-declares-endpoints
  ;; `served-namespaces`' docstring argues at length that the list must be
  ;; SINGULAR — it had two mounts once, and a literal repeated at both is how
  ;; a namespace ends up served by nobody. That argument is correct and it
  ;; solved half the problem: the DUPLICATION half. The remaining single list
  ;; can still fall behind the code it stands for, and nothing compared them.
  ;;
  ;; Found by slopp-ui, who took the "hand-kept list vs something derivable"
  ;; shape, applied it to their own store, hit the identical defect in their
  ;; own `served-namespaces`, and handed back the heuristic that finds these:
  ;; **look for prose arguing that a list should be singular.** That argument
  ;; is made by an author who has noticed the list is load-bearing — which is
  ;; exactly when the derivation gap gets written and not seen.
  ;;
  ;; Derived from the IMAGE rather than a store: loaded vars carry their own
  ;; metadata, so this needs no store read — which matters, because nothing
  ;; in this tier can open slopp's own store.
  (let [declares? (fn [nsx] (some #(let [m (meta %)]
                                     (or (:web/path m) (:web/read m)))
                                  (vals (ns-publics nsx))))
        candidates (->> (all-ns) (map ns-name)
                        ;; the prefix is DATA — a rename rewrites code and
                        ;; walks straight past a string, so this scan can come
                        ;; to match nothing. That is why the liveness check
                        ;; below is not decoration: phase 2 broke this literal
                        ;; and the check turned it red. A sibling guard without
                        ;; one shipped green against an empty search.
                        (filter #(str/starts-with? (str %) "slopp.api."))
                        ;; endpoint-shaped forms in tests are fixtures and
                        ;; claim no route — query_routes scopes the same way
                        (remove #(str/ends-with? (str %) "-test")))
        derived    (set (filter declares? candidates))
        listed     (set server/served-namespaces)]
    (testing "the scan found something — two empty sets agree"
      ;; the same trap as `crossings/unclassified-markers` returning empty
      ;; while a marker went unclassified for a week: a check whose population
      ;; can silently become zero reports success for the wrong reason
      (is (seq derived) (str "scanned " (count candidates) " namespaces")))
    (testing "and the list is exactly what declares an endpoint or a read performer"
      (is (= derived listed)
          (str "declares :web/path or :web/read but is not served: "
               (set/difference derived listed)
               " / served but declares neither: "
               (set/difference listed derived))))))
