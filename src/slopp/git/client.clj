(ns slopp.git.client
  (:require [clojure.java.io :as io]
            [slopp.db :as db]
            [slopp.git :as git])
  (:import [org.eclipse.jgit.lib NullProgressMonitor ObjectId Repository] [org.eclipse.jgit.transport PushResult RefSpec RemoteRefUpdate Transport URIish UsernamePasswordCredentialsProvider]))

^:reads (defn ^:export remote-credentials
  "CredentialsProvider for token auth against an https remote (GitHub PAT /
  app token) — the `token` argument, else SLOPP_GIT_TOKEN / GIT_TOKEN env.
  Nil when no token is set (anonymous / filesystem remotes)."
  [token]
  (when-let [t (or token
                   (System/getenv "SLOPP_GIT_TOKEN")
                   (System/getenv "GIT_TOKEN"))]
    (UsernamePasswordCredentialsProvider. "x-access-token" ^String t)))

(defn ^:export fetch-remote!
  "Fetch `url`'s branches into `repo` under refs/remotes/origin/* (plus the
  source's own remote-tracking refs under refs/remotes/tracking/*, so a
  plain checkout works as a remote — its slopp branch may exist only as
  origin/slopp). Returns {:tip sha-or-nil} for `branch`. Used to seed a
  clone/import, and to bring a remote's objects in before a grafted push.
  Scheme-less urls are local paths — made absolute (an in-memory repo has no
  dir to resolve against)."
  [^Repository repo url & {:keys [token branch timeout]
                           :or {branch "main" timeout 30}}]
  (let [s   (str url)
        uri (URIish. ^String (if (re-find #"^[a-z+]+://" s)
                                s
                                (.getAbsolutePath (io/file s))))]
    (with-open [tn (Transport/open repo uri)]
      (.setTimeout tn (int timeout))   ; seconds; a dead socket must throw, not freeze
      (when-let [creds (remote-credentials token)]
        (.setCredentialsProvider tn creds))
      (.fetch tn NullProgressMonitor/INSTANCE
              [(RefSpec. "+refs/heads/*:refs/remotes/origin/*")
               (RefSpec. "+refs/remotes/origin/*:refs/remotes/tracking/*")])
      {:tip (or (some-> (.resolve repo (str "refs/remotes/origin/" branch)) (.name))
                (some-> (.resolve repo (str "refs/remotes/tracking/" branch)) (.name)))})))

(defn ^:export push-to-remote!
  "Push the projection to an external git remote `url` (filesystem path or
  http(s)). `:branch` = the LOCAL projection line (default \"main\", the
  store's main line); `:remote-branch` = the DEST ref name (default =
  branch) — mixed-ownership repos point it at the slopp-owned branch while
  humans keep main. Projects first; a cloned store fetches the remote's
  objects so its grafted chain is complete. Fast-forward only — a diverged
  remote is an honest :error, never a force. Returns
  {:pushed sha :status s :remote-branch b} | {:error msg}.

  `ctx` is an OPAQUE handle from `git/open-ctx!` — see `git/close-ctx!`."
  [ctx url
   & {:keys [token branch remote-branch timeout]
      :or {branch "main" timeout 30}}]
  (let [map-conn         (:slopp.git/map-conn ctx)
        ^Repository repo (:slopp.git/repo ctx)]
    (when-let [base (db/get-meta map-conn "git-base-sha")]
      (when-not (.has (.getObjectDatabase repo) (ObjectId/fromString base))
        (fetch-remote! repo url :token token :timeout timeout)))
    (git/ensure-projected! ctx)
    (let [rbranch (or remote-branch branch)
          src     (str "refs/heads/" branch)
          dst     (str "refs/heads/" rbranch)
          s       (str url)
          uri     (URIish. ^String (if (re-find #"^[a-z+]+://" s)
                                     s
                                     (.getAbsolutePath (io/file s))))]
      (if-let [tip (.resolve repo src)]
        (with-open [tn (Transport/open repo uri)]
          (.setTimeout tn (int timeout))   ; a dead socket must throw, not freeze
          (when-let [creds (remote-credentials token)]
            (.setCredentialsProvider tn creds))
          (let [rru    (RemoteRefUpdate. repo src dst false nil nil)
                ^PushResult res (.push tn NullProgressMonitor/INSTANCE [rru])
                ^RemoteRefUpdate upd (first (.getRemoteUpdates res))
                status (str (.getStatus upd))]
            (if (contains? #{"OK" "UP_TO_DATE"} status)
              {:pushed (.name tip) :status status :remote-branch rbranch}
              {:error (str "push rejected (" status ")"
                           (when-let [m (.getMessage upd)] (str ": " m))
                           (when (= status "REJECTED_NONFASTFORWARD")
                             " — the remote branch has history this store doesn't build on (pull first)"))})))
        {:error (str "nothing to push — no " src
                     " in the projection (no milestones yet?)")}))))
