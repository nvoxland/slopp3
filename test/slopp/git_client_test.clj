(ns slopp.git-client-test
  "Phase-1 git bridge: slopp as an in-memory git CLIENT. The projection is
  pushed to an external remote (a scratch bare repo — no network), fetched
  back into a fresh in-memory repo, and its tree read as text. Files exist on
  the REMOTE only; the local side never materializes a working tree."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]
            [slopp.git :as git] [slopp.git.client :as client])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.eclipse.jgit.api Git]
           [org.eclipse.jgit.storage.file FileRepositoryBuilder]))

(defn- temp-dir []
  (str (Files/createTempDirectory "slopp-git-client" (make-array FileAttribute 0))))

(defn- rm-rf! [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
    (.delete f)))

(def seed
  (str "(ns gc.core (:require [clojure.test :refer [deftest is]]))\n"
       "\n"
       "(defn f [x] (+ x 1))\n"
       "\n"
       "(deftest f-t (is (= 2 (f 1))))\n"))

(defn- bare-repo!
  "git init --bare, JGit-style; returns the repo dir path."
  [dir]
  (-> (Git/init) (.setBare true) (.setInitialBranch "main")
      (.setDirectory (io/file dir)) (.call) (.close))
  dir)

(deftest ^:external push-then-fetch-round-trips-the-projection
  (let [dir  (temp-dir)
        bare (bare-repo! (str (temp-dir) "/remote.git"))
        sess (api/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! sess 'gc.core seed)
      (api/commit-point! sess "v1: f ships" :agent "alice")
      (let [ctx (git/open-ctx! dir)]
        (try
          (testing "push lands the milestone tip in the bare remote"
            (let [r (client/push-to-remote! ctx bare)]
              (is (nil? (:error r)) (pr-str r))
              (is (string? (:pushed r)))
              (let [remote (-> (FileRepositoryBuilder.)
                               (.setGitDir (io/file bare)) (.build))]
                (try
                  (let [tip (.resolve remote "refs/heads/main")]
                    (is (some? tip))
                    (is (= (:pushed r) (.name tip)))
                    (testing "the remote tree is real files: ns + deps.edn"
                      (let [tree (git/tree-at remote (.name tip))]
                        (is (= (api/query-source sess 'gc.core)
                               (get tree "src/gc/core.clj")))
                        (is (contains? tree "deps.edn")))))
                  (finally (.close remote))))))

          (testing "re-push is a clean no-op (up to date, not an error)"
            (let [r (client/push-to-remote! ctx bare)]
              (is (nil? (:error r)) (pr-str r))))

          (testing "fetch reads the tip + tree back into a fresh in-memory repo"
            (let [repo (git/open-repo! nil)]
              (try
                (let [{:keys [tip]} (client/fetch-remote! repo bare)]
                  (is (string? tip))
                  (let [tree (git/tree-at repo tip)]
                    (is (= (api/query-source sess 'gc.core)
                           (get tree "src/gc/core.clj")))
                    (is (contains? tree "deps.edn"))))
                (finally (.close repo)))))
          (finally (git/close-ctx! ctx))))
      (finally
        (api/close! sess)
        (rm-rf! dir)
        (rm-rf! (io/file bare))))))

(deftest ^:external push-refuses-when-nothing-projected
  ;; no milestone → no refs/heads/main → an honest error, not an NPE
  (let [dir  (temp-dir)
        bare (bare-repo! (str (temp-dir) "/remote.git"))
        ctx  (git/open-ctx! dir)]
    (try
      (let [r (client/push-to-remote! ctx bare)]
        (is (:error r)))
      (finally
        (git/close-ctx! ctx)
        (rm-rf! dir)
        (rm-rf! (io/file bare))))))
(deftest ^:external dead-remotes-time-out
  ;; a half-dead HTTPS connection froze the server's serve thread for 40+
  ;; minutes mid-commit_point: JGit transports carried NO timeout, so one
  ;; wedged socket read blocked every tool. A dead remote must become a
  ;; fast exception the publish path degrades from.
  (let [srv (java.net.ServerSocket. 0)   ; accepts, never answers
        dir (temp-dir)
        ctx (git/open-ctx! dir)]
    (try
      (let [url (str "http://127.0.0.1:" (.getLocalPort srv) "/dead.git")
            f   (future (try (client/fetch-remote! (:slopp.git/repo ctx) url :timeout 2)
                             (catch Exception e {:threw (str e)})))
            r   (deref f 20000 ::wedged)]
        (is (not= ::wedged r)
            "the fetch must fail fast, never hang the calling thread")
        (is (:threw r) (pr-str r)))
      (finally
        (git/close-ctx! ctx)
        (rm-rf! dir)
        (.close srv)))))
