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
            [slopp.git :as git])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.eclipse.jgit.api Git]))

(defn- temp-dir [nm]
  (str (Files/createTempDirectory nm (make-array FileAttribute 0))))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

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

(deftest clone-fetch-and-branches-over-smart-http
  (let [dir  (temp-dir "slopp-git-server")
        sess (api/open! {:dir dir})
        port (free-port)
        srv  (git/start-server! port {:dir dir})]
    (try
      (api/ingest! sess 'gs.core seed)
      (api/commit-point! sess "v1: f ships" :agent "alice")
      (api/edit-replace! sess 'gs.core 'f "(defn f [x] (+ 10 x))"
                         :prompt "flip" :agent "alice")
      (api/commit-point! sess "v2: flipped" :agent "alice")
      (let [clone-dir (temp-dir "slopp-git-clone")]
        (with-open [g (clone! port clone-dir)]
          (testing "the clone IS the store's rendered source, newest milestone"
            (is (= (api/query-source sess 'gs.core)
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
            (api/commit-point! sess "v3: coverage" :agent "alice")
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
        (api/branch! sess "feature")
        (api/edit-replace! sess 'gs.core 'f "(defn f [x] (int (+ x 10)))"
                           :prompt "feature work" :agent "bob")
        (api/commit-point! sess "feature: tweak" :agent "bob")
        (let [refs (-> (Git/lsRemoteRepository)
                       (.setRemote (clone-url port))
                       (.setHeads true)
                       (.call))]
          (is (contains? (set (map #(.getName %) refs))
                         "refs/heads/feature"))))
      (finally
        (git/stop-server! srv)
        (api/close! sess)))))

(deftest empty-store-clones-as-empty-repo
  (let [dir  (temp-dir "slopp-git-empty")
        sess (api/open! {:dir dir})
        port (free-port)
        srv  (git/start-server! port {:dir dir})]
    (try
      (api/ingest! sess 'gs.core seed)   ; content but NO milestones yet
      (let [clone-dir (temp-dir "slopp-git-empty-clone")]
        (with-open [g (clone! port clone-dir)]
          (is (nil? (-> g (.getRepository) (.resolve "HEAD"))))))
      (finally
        (git/stop-server! srv)
        (api/close! sess)))))

(deftest wip-refs-expose-unmilestoned-work
  ;; the protocol can't say "uncommitted changes" (that's a working-dir
  ;; feature) — the idiom is a synthetic ref: refs/heads/wip/<branch> holds
  ;; a throwaway commit of the LIVE store state whenever it differs from
  ;; the last milestone; tools diff origin/main..origin/wip/main
  (let [dir  (temp-dir "slopp-git-wip")
        sess (api/open! {:dir dir})
        port (free-port)
        srv  (git/start-server! port {:dir dir})
        url  (clone-url port)
        heads (fn []
                (into {} (map (fn [r] [(.getName r) (.name (.getObjectId r))]))
                      (-> (Git/lsRemoteRepository) (.setRemote url)
                          (.setHeads true) (.call))))]
    (try
      (api/ingest! sess 'gs.core seed)
      (api/commit-point! sess "v1: f ships" :agent "alice")
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
          (with-open [g (clone! port clone-dir)]
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
                (is (= (api/query-source sess 'gs.core)
                       (slurp (io/file clone-dir "src" "gs" "core.clj"))))))))
        (testing "more work moves the wip ref"
          (api/edit-replace! sess 'gs.core 'f "(defn f [x] (int (+ 10 x)))"
                             :prompt "still cooking" :agent "alice")
          (let [wip2 (get (heads) "refs/heads/wip/main")]
            (is (some? wip2))
            (is (not= wip1 wip2))))
        (testing "a milestone catches up — the wip ref disappears"
          (api/commit-point! sess "v2: f done" :agent "alice")
          (let [h (heads)]
            (is (nil? (get h "refs/heads/wip/main")))
            (is (not= (get h1 "refs/heads/main") (get h "refs/heads/main"))))))
      (finally
        (git/stop-server! srv)
        (api/close! sess)))))

(deftest wip-refs-are-read-only
  (let [dir  (temp-dir "slopp-git-wip-ro")
        sess (api/open! {:dir dir})
        port (free-port)
        srv  (git/start-server! port {:dir dir})]
    (try
      (api/ingest! sess 'gs.core seed)
      (api/commit-point! sess "v1" :agent "alice")
      (api/edit-replace! sess 'gs.core 'f "(defn f [x] (+ 10 x))"
                         :prompt "wip" :agent "alice")
      (let [clone-dir (temp-dir "slopp-wip-ro-clone")]
        (with-open [g (clone! port clone-dir)]
          (-> g (.checkout) (.setName "wip") (.setCreateBranch true)
              (.setStartPoint "origin/wip/main") (.call))
          (spit (io/file clone-dir "src" "gs" "core.clj")
                (str (slurp (io/file clone-dir "src" "gs" "core.clj"))
                     "\n(defn extra [x] x)\n"))
          (-> g (.add) (.addFilepattern ".") (.call))
          (-> g (.commit) (.setMessage "onto wip")
              (.setAuthor "Dana Dev" "dana@example.com")
              (.setCommitter "Dana Dev" "dana@example.com")
              (.setSign false) (.call))
          (let [cmd (.push g)
                _ (.setRefSpecs cmd ^java.util.List
                                (java.util.List/of
                                 (org.eclipse.jgit.transport.RefSpec.
                                  "refs/heads/wip:refs/heads/wip/main")))
                _ (.setForce cmd true)
                pr ^org.eclipse.jgit.transport.PushResult (first (.call cmd))
                upd (first (.getRemoteUpdates pr))]
            (is (not= org.eclipse.jgit.transport.RemoteRefUpdate$Status/OK
                      (.getStatus upd))
                (str (.getStatus upd)))
            (is (str/includes? (str (.getMessage upd)) "read-only")))))
      (finally
        (git/stop-server! srv)
        (api/close! sess)))))

(deftest real-git-cli-smoke
  ;; the actual `git` binary is the compatibility oracle; skip silently
  ;; when it isn't installed
  (let [git-bin (try (zero? (:exit (sh/sh "git" "--version")))
                     (catch Exception _ false))]
    (when git-bin
      (let [dir  (temp-dir "slopp-git-cli")
            sess (api/open! {:dir dir})
            port (free-port)
            srv  (git/start-server! port {:dir dir})]
        (try
          (api/ingest! sess 'gs.core seed)
          (api/commit-point! sess "v1: f ships" :agent "alice")
          (let [clone-dir (str (temp-dir "slopp-git-cli-clone") "/clone")
                res (sh/sh "git" "clone" (clone-url port) clone-dir)]
            (is (zero? (:exit res)) (:err res))
            (is (= (api/query-source sess 'gs.core)
                   (slurp (io/file clone-dir "src" "gs" "core.clj"))))
            (let [log (sh/sh "git" "-C" clone-dir "log" "--format=%s")]
              (is (str/includes? (:out log) "v1: f ships"))))
          (finally
            (git/stop-server! srv)
            (api/close! sess)))))))
