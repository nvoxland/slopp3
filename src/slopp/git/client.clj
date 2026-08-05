(ns slopp.git.client
  "Talking OUT to somebody else's git: fetch a remote's objects in, push the
  projection out. The two operations slopp needs to interoperate with a repo
  it does not own.

  Direction is what distinguishes this from the rest of the module. `slopp.git`
  PROJECTS the store into git objects — an in-memory JGit repo built from the
  delta log — and this namespace carries those objects across a wire, over
  https with a token or over a filesystem path.

  Two rules, both about not lying to the remote:

  - **Fast-forward only.** A diverged remote is an honest `{:error …}` naming
    what happened and saying to pull first. There is no force path, because
    the store is not the authority on a repo it shares.
  - **The push is COMPLETE or it doesn't go.** A cloned store's grafted chain
    can reference a base object it has never held, so a missing base is
    fetched before projecting — a push that succeeds while leaving the remote
    unable to resolve its own history is worse than a refusal.

  `ctx` is an opaque handle from `git/open-ctx!`; the JGit repo inside it is
  shared with the projection, which is why it outlives any one operation."
  (:require [clojure.java.io :as io]
            [slopp.store.db :as db]
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

(defn ^:export push-refusal
  "The sentence for a rejected push, in the vocabulary of the CALLER that made
  it. `opts`: `:dst` the destination ref, `:mirror?` when the push never left
  this repo.

  One producer, because there are two callers and they mean different things
  by the same git status. `push-to-remote!` serves both
  [[slopp.sync/push!]] — a genuine external remote — and
  [[slopp.sync/publish-local!]], which pushes to `(str dir)`, THE CHECKOUT
  ITSELF, projecting main onto the local `slopp/<line>` mirror. No remote is
  involved in the second at any point.

  So a single non-fast-forward sentence said \"the remote branch has history
  this store doesn't build on (pull first)\" about a LOCAL ref, where pulling
  is not unhelpful but impossible — and it ran automatically on every
  milestone, while `git_push` answered its own case correctly. Reported by a
  consumer who read the confident wrong cause, went looking for a remote that
  had never existed, and nearly filed it as something else entirely.

  **A vague error would have been better than that one**, which is the thing
  worth keeping: a confident wrong diagnosis crowds out the correct one a
  sibling surface already produces. So an unrecognised status is passed through
  with git's own message rather than interpreted."
  [status message {:keys [mirror? dst]}]
  (str "push rejected (" status ")"
       (when (seq (str message)) (str ": " message))
       (when (= status "REJECTED_NONFASTFORWARD")
         (if mirror?
           (str " — the local mirror " dst " has commits this projection does"
                " not build on. Nothing was pushed anywhere: this is one repo"
                " talking to itself. The mirror is a PROJECTION of the store,"
                " so it can be reset deliberately (git branch -f) once you know"
                " what wrote it — a store revert and a second machine's"
                " projection both land here")
           " — the remote branch has history this store doesn't build on (pull first)"))))

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
   & {:keys [token branch remote-branch timeout mirror?]
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
              {:error (push-refusal status (.getMessage upd) {:mirror? mirror? :dst dst})})))
        {:error (str "nothing to push — no " src
                     " in the projection (no milestones yet?)")}))))
