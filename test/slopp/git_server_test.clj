(ns slopp.git-server-test
  "P4-m8 M2: the standalone git smart-HTTP server — any git client can clone
  and fetch a slopp store's milestones over http://127.0.0.1:<port>/slopp.git.
  Projection runs before every refs advertisement, so foreign commit points
  are served without a restart."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]
            [slopp.git.server :as server] [slopp.api.branch :as branch] [slopp.api.query :as query] [slopp.api.external :as external])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.eclipse.jgit.api Git]))

(defn- temp-dir [nm]
  (str (Files/createTempDirectory nm (make-array FileAttribute 0))))

(def seed
  (str "(ns gs.core (:require [clojure.test :refer [deftest is]]))\n"
       "\n"
       ";; served verbatim over the git protocol\n"
       "(defn f [x] (+ x 10))\n"
       "\n"
       "(deftest f-t (is (= 11 (f 1))))\n"))

(defn- clone-url [port] (str "http://127.0.0.1:" port "/slopp.git"))

(defn- clone! [port to]
  (-> (Git/cloneRepository)
      (.setURI (clone-url port))
      (.setDirectory (io/file to))
      (.call)))

(defn- log-messages [^Git g]
  (mapv #(.getFullMessage %) (-> g (.log) (.call))))

(deftest ^:external clone-fetch-and-branches-over-smart-http
  ;; 0 = the OS assigns a genuinely-free loopback port ATOMICALLY (#136), and
  ;; srv's :port/:url are the only ones that can be trusted — a port chosen
  ;; before binding is a guess another shard can win.
  (let [dir  (temp-dir "slopp-git-server")
        sess (external/open! {:slopp.api/dir dir})
        srv  (server/start-server! 0 {:dir dir})]
    (try
      (api/ingest! sess 'gs.core seed)
      (external/commit-point! sess "v1: f ships" :agent "alice")
      (api/edit-replace! sess 'gs.core 'f "(defn f [x] (+ 10 x))"
                         :prompt "flip" :agent "alice")
      (external/commit-point! sess "v2: flipped" :agent "alice")
      (let [clone-dir (temp-dir "slopp-git-clone")]
        (with-open [g (clone! (:port srv) clone-dir)]
          (testing "the clone IS the store's rendered source, newest milestone"
            (is (= (query/query-source sess 'gs.core)
                   (slurp (io/file clone-dir "src" "gs" "core.clj"))))
            (is (.exists (io/file clone-dir "deps.edn"))))
          (testing "history is the milestone chain, newest first, on main"
            (let [msgs (log-messages g)]
              (is (= 2 (count msgs)))
              (is (str/starts-with? (first msgs) "v2: flipped"))
              (is (str/starts-with? (second msgs) "v1: f ships"))
              (is (= "main" (.getBranch (.getRepository g))))))
          (testing "a milestone made AFTER the clone arrives by fetch"
            (api/edit-replace! sess 'gs.core 'f-t
                               "(deftest f-t (is (= 11 (f 1))) (is true))"
                               :prompt "more coverage" :agent "alice")
            (external/commit-point! sess "v3: coverage" :agent "alice")
            (-> g (.fetch) (.call))
            (let [tip (-> g (.getRepository)
                          (.resolve "refs/remotes/origin/main"))]
              (is (some? tip))
              (with-open [rw (org.eclipse.jgit.revwalk.RevWalk.
                              (.getRepository g))]
                (is (str/starts-with?
                     (.getFullMessage (.parseCommit rw tip))
                     "v3: coverage")))))))
      (testing "branches advertise as refs/heads/<name>"
        (branch/branch! sess "feature")
        (api/edit-replace! sess 'gs.core 'f "(defn f [x] (int (+ x 10)))"
                           :prompt "feature work" :agent "bob")
        (external/commit-point! sess "feature: tweak" :agent "bob")
        (let [refs (-> (Git/lsRemoteRepository)
                       (.setRemote (:url srv))
                       (.setHeads true)
                       (.call))]
          (is (contains? (set (map #(.getName %) refs))
                         "refs/heads/feature"))))
      (finally
        (server/stop-server! srv)
        (api/close! sess)))))

(deftest ^:external empty-store-clones-as-empty-repo
  (let [dir  (temp-dir "slopp-git-empty")
        sess (external/open! {:slopp.api/dir dir})
        srv  (server/start-server! 0 {:dir dir})]   ; 0 = OS-assigned, atomic (#136)
    (try
      (api/ingest! sess 'gs.core seed)   ; content but NO milestones yet
      (let [clone-dir (temp-dir "slopp-git-empty-clone")]
        (with-open [g (clone! (:port srv) clone-dir)]
          (is (nil? (-> g (.getRepository) (.resolve "HEAD"))))))
      (finally
        (server/stop-server! srv)
        (api/close! sess)))))

(deftest ^:external wip-refs-expose-unmilestoned-work
  ;; the protocol can't say "uncommitted changes" (that's a working-dir
  ;; feature) — the idiom is a synthetic ref: refs/heads/wip/<branch> holds
  ;; a throwaway commit of the LIVE store state whenever it differs from
  ;; the last milestone; tools diff origin/main..origin/wip/main
  (let [dir  (temp-dir "slopp-git-wip")
        sess (external/open! {:slopp.api/dir dir})
        
        ;; 0 = OS-assigned, atomic; srv's :port/:url are the only trustworthy
        ;; ones — a port chosen before binding is a guess a shard can win (#136)
        srv  (server/start-server! 0 {:dir dir})
        url  (:url srv)
        heads (fn []
                (into {} (map (fn [r] [(.getName r) (.name (.getObjectId r))]))
                      (-> (Git/lsRemoteRepository) (.setRemote url)
                          (.setHeads true) (.call))))]
    (try
      (api/ingest! sess 'gs.core seed)
      (external/commit-point! sess "v1: f ships" :agent "alice")
      (testing "a clean journal advertises NO wip ref"
        (is (nil? (get (heads) "refs/heads/wip/main"))))
      (api/edit-replace! sess 'gs.core 'f "(defn f [x] (+ 10 x))"
                         :prompt "not yet milestoned" :agent "alice")
      (let [h1 (heads)
            wip1 (get h1 "refs/heads/wip/main")]
        (testing "un-milestone'd work appears as wip/main"
          (is (some? wip1))
          (is (not= wip1 (get h1 "refs/heads/main"))))
        (let [clone-dir (temp-dir "slopp-wip-clone")]
          (with-open [g (clone! (:port srv) clone-dir)]
            (let [repo (.getRepository g)
                  wc   (with-open [rw (org.eclipse.jgit.revwalk.RevWalk. repo)]
                         (.parseCommit rw (.resolve repo
                                                    "refs/remotes/origin/wip/main")))]
              (testing "the wip commit chains on the milestone and holds the live render"
                (is (= (get h1 "refs/heads/main")
                       (.name (.getParent wc 0))))
                (is (str/starts-with? (.getFullMessage wc) "wip:"))
                (-> g (.checkout) (.setName "wip") (.setCreateBranch true)
                    (.setStartPoint "origin/wip/main") (.call))
                (is (= (query/query-source sess 'gs.core)
                       (slurp (io/file clone-dir "src" "gs" "core.clj"))))))))
        (testing "more work moves the wip ref"
          (api/edit-replace! sess 'gs.core 'f "(defn f [x] (int (+ 10 x)))"
                             :prompt "still cooking" :agent "alice")
          (let [wip2 (get (heads) "refs/heads/wip/main")]
            (is (some? wip2))
            (is (not= wip1 wip2))))
        (testing "a milestone catches up — the wip ref disappears"
          (external/commit-point! sess "v2: f done" :agent "alice")
          (let [h (heads)]
            (is (nil? (get h "refs/heads/wip/main")))
            (is (not= (get h1 "refs/heads/main") (get h "refs/heads/main"))))))
      (finally
        (server/stop-server! srv)
        (api/close! sess)))))

(deftest ^:external remote-is-read-only
  ;; the remote serves clone/fetch only — git-receive-pack is never advertised,
  ;; so any push is refused (edits arrive through slopp's write tools, not push).
  (let [dir  (temp-dir "slopp-git-ro")
        sess (external/open! {:slopp.api/dir dir})
        srv  (server/start-server! 0 {:dir dir})]   ; 0 = OS-assigned, atomic (#136)
    (try
      (api/ingest! sess 'gs.core seed)
      (external/commit-point! sess "v1" :agent "alice")
      (let [clone-dir (temp-dir "slopp-ro-clone")]
        (with-open [g (clone! (:port srv) clone-dir)]
          (spit (io/file clone-dir "src" "gs" "core.clj")
                (str (slurp (io/file clone-dir "src" "gs" "core.clj"))
                     "\n(defn extra [x] x)\n"))
          (-> g (.add) (.addFilepattern ".") (.call))
          (-> g (.commit) (.setMessage "onto main")
              (.setAuthor "Dana Dev" "dana@example.com")
              (.setCommitter "Dana Dev" "dana@example.com")
              (.setSign false) (.call))
          (testing "a push is refused — receive-pack is not advertised"
            (let [outcome (try
                            {:updates (-> ^org.eclipse.jgit.transport.PushResult
                                       (first (-> g (.push) (.call)))
                                          (.getRemoteUpdates))}
                            (catch Exception e {:threw (.getSimpleName (class e))}))]
              (is (or (:threw outcome)
                      (every? #(not= org.eclipse.jgit.transport.RemoteRefUpdate$Status/OK
                                     (.getStatus %))
                              (:updates outcome)))
                  (str "push must fail against a read-only remote: " outcome))))))
      (finally
        (server/stop-server! srv)
        (api/close! sess)))))

(deftest ^:external real-git-cli-smoke
  ;; the actual `git` binary is the compatibility oracle; skip silently
  ;; when it isn't installed
  (let [git-bin (try (zero? (:exit (sh/sh "git" "--version")))
                     (catch Exception _ false))]
    (when git-bin
      (let [dir  (temp-dir "slopp-git-cli")
            sess (external/open! {:slopp.api/dir dir})
            srv  (server/start-server! 0 {:dir dir})]   ; 0 = OS-assigned, atomic (#136)
        (try
          (api/ingest! sess 'gs.core seed)
          (external/commit-point! sess "v1: f ships" :agent "alice")
          (let [clone-dir (str (temp-dir "slopp-git-cli-clone") "/clone")
                res (sh/sh "git" "clone" (:url srv) clone-dir)]
            (is (zero? (:exit res)) (:err res))
            (is (= (query/query-source sess 'gs.core)
                   (slurp (io/file clone-dir "src" "gs" "core.clj"))))
            (let [log (sh/sh "git" "-C" clone-dir "log" "--format=%s")]
              (is (str/includes? (:out log) "v1: f ships"))))
          (finally
            (server/stop-server! srv)
            (api/close! sess)))))))
(deftest ^:external the-served-port-is-the-bound-one-not-the-requested-one
  ;; THE flake (observed once on a milestone gate 2026-07-16). Two bugs stacked:
  ;;
  ;; 1. free-port did (ServerSocket. 0), read the port, and CLOSED it — so the
  ;;    port was only ever a GUESS by the time anyone bound it. Worse, probed
  ;;    2026-07-17: that socket binds the WILDCARD 0.0.0.0, which does NOT
  ;;    conflict with 127.0.0.1:<port>. So free-port would happily hand back a
  ;;    port another shard's HttpServer was ACTIVELY SERVING.
  ;; 2. bind-localhost! then hits BindException and relocates to an ephemeral
  ;;    port — SILENTLY, and rightly so: a shared derived port must never block
  ;;    startup. But the test kept its stale `port` and cloned the abandoned
  ;;    one, reaching the other shard's server, or nothing once that shard
  ;;    stopped: TransportException "connection failed".
  ;;
  ;; start-server!'s docstring already carried the answer — "the actual bound
  ;; port is returned as :port" — and git-embedded-test already read it.
  ;;
  ;; No race needed to pin it: hold the LOOPBACK port, exactly as a rival
  ;; shard's running server does, and the divergence is deterministic.
  (let [dir  (temp-dir "slopp-git-port")
        sess (external/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! sess 'gs.core seed)
      (external/commit-point! sess "v1" :agent "alice")
      (let [hostage (java.net.ServerSocket.
                     0 50 (java.net.InetAddress/getByName "127.0.0.1"))]
        (try
          (let [taken (.getLocalPort hostage)
                srv   (server/start-server! taken {:dir dir})]
            (try
              (testing "the requested port was taken, so the server RELOCATED"
                (is (not= taken (:port srv))
                    "bind-localhost! must relocate rather than fail startup"))
              (testing "…and a clone against the BOUND port works. Trusting the
                        REQUESTED port is what produced the flake — that is the
                        port nothing is serving."
                (let [clone-dir (temp-dir "slopp-port-clone")]
                  (with-open [g (clone! (:port srv) clone-dir)]
                    (is (some? (.getRepository g)))
                    (is (.exists (io/file clone-dir "src"))))))
              (finally (server/stop-server! srv))))
          (finally (.close hostage))))
      (finally (api/close! sess)))))
