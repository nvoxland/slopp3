(ns slopp.api.branch
  (:require [clojure.java.io :as io]
            [slopp.api.session :as session]
            [slopp.db :as db]
            [slopp.edit :as edit]
            [slopp.image :as image]
            [slopp.repl :as repl]
            [slopp.store :as store] [slopp.store.merge :as merge] [clojure.string :as str]))

(defn merge-into-session!
  "Shared merge pipeline (m2 forks + m3 branches): replay `theirs` onto the
  session store (store/merge-logs), hot-load what arrived (new namespaces in
  dependency order, then changed forms through the compile gate), commit,
  persist, verify every touched namespace, and record ONE `:merge` delta."
  [session theirs from-label]
  (let [t0   (System/nanoTime)
        base (:store @session)
        r    (merge/merge-logs base theirs :from from-label)]
    (cond
      (nil? (:fork-point r))
      {:error "stores share no history — not a fork/branch of this project"}

      (and (zero? (:merged r)) (empty? (:conflicts r)))
      {:merged 0 :conflicts [] :note "already converged — nothing to merge"}

      :else
      (let [st'      (:store r)
            load-err (or ;; cold-load first (S1b): two individually-legal lines can
                      ;; interleave into a forward ref — refuse before the
                      ;; image is touched
                      (edit/cold-load-errors
                       st'
                       (filter #(contains? (:namespaces st') %)
                               (distinct
                                (concat (keep :ns (drop (count (store/deltas base))
                                                        (store/deltas st')))
                                        (:new-nses r)))))
                      ;; new namespaces first, dependency order
                      (some (fn [ns-sym]
                              (when (contains? (set (:new-nses r)) ns-sym)
                                (image/load-ns! (:image @session) st' ns-sym)))
                            (store/ns-dependency-order st'))
                      ;; then every changed form (compile gate, heals)
                      (:err (session/hot-load-all! session st'
                                           (:changed-form-ids r))))]
        (if load-err
          (do (session/fresh-image! session)
              (edit/compile-error st' load-err "merge failed to compile: "))
          (let [[st'' mdelta] (merge/record-merge st' from-label r)]
            (if-not (session/try-commit! session base st''
                                 (vec (distinct
                                       (concat (keep :ns (drop (count (store/deltas base))
                                                               (store/deltas st'')))
                                               (:new-nses r)))))
              {:conflict {:reason "store changed during merge — retry"}}
              (let [new-deltas   (drop (count (store/deltas base))
                                       (store/deltas st''))
                    touched-nses (vec (distinct
                                       (concat (keep :ns new-deltas)
                                               (:new-nses r))))
                    edited       (into #{}
                                       (keep (fn [id]
                                               (when-let [e (store/form-by-id st'' id)]
                                                 (symbol (str (store/ns-of-form-id st'' id))
                                                         (str (or (:name e) (:id e)))))))
                                       (:changed-form-ids r))
                    verify-nses  (vec (remove #{'*session*} touched-nses))
                    summary      (when (seq verify-nses)
                                   (session/run-verification! session verify-nses nil
                                                      :edited edited))]
                (when summary
                  (session/commit-appended! session
                                    #(store/record-verification % verify-nses summary)
                                    []))
                (session/with-ms
                  (cond-> {:merged     (:merged r)
                           :conflicts  (:conflicts r)
                           :merge-delta (:id mdelta)}
                    (seq (:new-nses r)) (assoc :new-nses (:new-nses r))
                    (seq (:notes r))    (assoc :notes (:notes r))
                    summary             (assoc :test summary))
                  t0)))))))))

(defn line-dir
  "Where a branch line persists in a durable session."
  [dir nm]
  (str (io/file dir ".slopp" "branches" nm)))

(defn snapshot-to-conn!
  "Full-store snapshot into a (fresh) branch db: every delta + all elements."
  [conn store]
  (let [ds   (store/deltas store)
        nses (vec (keys (:namespaces store)))]
    (doseq [d (butlast ds)] (db/persist! conn store d []))
    (when-let [d (last ds)] (db/persist! conn store d nses))))

(defn delete-dir! [^java.io.File f]
  (when (.exists f)
    (doseq [^java.io.File c (reverse (file-seq f))] (.delete c))))

^:reads (defn load-line
  "An inactive line's {:store :conn}: from memory, or lazily from its branch
  db in a durable session. nil if unknown."
  [session nm]
  (let [{:keys [lines dir]} @session]
    (or (get lines nm)
        (when (and dir (.exists (io/file (line-dir dir nm) ".slopp" "store.db")))
          (let [c (db/open! (line-dir dir nm))]
            {:store (db/load-store c) :conn c})))))

(defn boot-line-image!
  "A fresh image loaded with `store` (consumes the warm spare when ready).
  Returns {:image handle} or {:error msg}."
  [session store]
  (let [spare (:spare @session)
        img   (session/image-with-deps! spare (:deps store))]
    (when spare
      (swap! session assoc :spare nil)
      (session/start-spare! session))
    (if-let [err (some #(image/load-ns! img store %)
                       (store/ns-dependency-order store))]
      (do (repl/stop! img)
          {:error (str "branch image failed to load: " err)})
      {:image img})))
(defn ^:export merge!
  "Phase 4 m2: merge a DIVERGED COPY of this project back into the live
  session. A 'fork' is just a copied project dir edited by its own slopp
  server; `other-dir` is that copy. Their delta-log suffix replays onto our
  store: different-form work lands, identical changes converge, same-form
  divergence returns `:conflicts` (ours kept, theirs surfaced — resolve by
  hand with edit_replace_form)."
  [session other-dir]
  (let [f    (io/file (str other-dir))
        db-f (io/file f ".slopp" "store.db")]
    (cond
      (not (.isAbsolute f))
      {:error "merge needs an ABSOLUTE project-dir path"}

      (not (.exists db-f))
      {:error (str "no slopp store under " other-dir)}

      :else
      (let [conn   (db/open! (str f))
            theirs (try (db/load-store conn)
                        (finally (.close ^java.sql.Connection conn)))]
        (merge-into-session! session theirs (str other-dir))))))
(defn ^:export branch!
  "Phase 4 m3: create branch `nm` from the CURRENT line's state and switch to
  it — O(1), the store is a value; the image is already correct (identical
  content). Durable sessions snapshot the line under .slopp/branches/<nm>."
  [session nm]
  (let [nm (str nm)
        {:keys [branch lines dir]} @session]
    (cond
      (str/blank? nm)
      {:error "branch needs a name"}

      (= nm "main")
      {:error "main is the trunk — branch FROM it"}

      (or (= nm branch)
          (contains? lines nm)
          (and dir (.exists (io/file (line-dir dir nm)))))
      {:error (str "branch " nm " already exists")}

      :else
      ;; claim the name atomically: in-process via the lines map, and
      ;; cross-process via mkdir (fails if the dir exists)
      (let [[old _] (swap-vals! session
                                (fn [s]
                                  (if (or (= nm (:branch s))
                                          (contains? (:lines s) nm))
                                    s
                                    (update s :lines assoc nm ::claimed))))]
        (if (or (= nm (:branch old)) (contains? (:lines old) nm))
          {:error (str "branch " nm " already exists")}
          (let [bdir (when dir (io/file (line-dir dir nm)))]
            (when bdir (.mkdirs (.getParentFile bdir)))
            (if (and bdir (not (.mkdir bdir)))
              (do (swap! session update :lines dissoc nm)   ; release the claim
                  {:error (str "branch " nm " already exists")})
              (let [line-id (str (java.util.UUID/randomUUID))
                    conn    (when dir
                              (doto (db/open! (line-dir dir nm))
                                (snapshot-to-conn! (:store @session))
                                (db/set-line-id! line-id)))]
                (swap! session
                       (fn [s]
                         (-> s
                             (update :lines dissoc nm)      ; claim → active
                             (update :lines assoc (:branch s)
                                     {:store (:store s) :conn (:db s)})
                             (assoc :branch nm :db conn
                                    :store (assoc (:store s) :line-id line-id)
                                    :data-version (some-> conn db/data-version)))))
                {:branch nm :from branch :id line-id}))))))))
(defn ^:export branch-switch!
  "Checkout with LINE-OWNED images (m4): the outgoing line PARKS its image
  intact (its REPL state included — inactive lines are immutable, so a parked
  image stays in step by construction); the target ADOPTS its parked image if
  it still has one, else BOOTS a fresh one on demand (the warm spare makes
  that cheap). Parked images retire after the session's idle TTL. The trace
  map resets — it described the other line."
  [session nm]
  (let [nm (str nm)]
    (if (= nm (:branch @session))
      {:switched nm :note "already on it"}
      (if-let [target (load-line session nm)]
        (let [adopted (:image target)
              booted  (when-not adopted
                        (boot-line-image! session (:store target)))]
          (if (:error booted)
            booted
            (do (swap! session
                       (fn [s]
                         (-> s
                             (update :lines assoc (:branch s)
                                     {:store     (:store s)
                                      :conn      (:db s)
                                      :image     (:image s)
                                      :last-used (System/currentTimeMillis)})
                             (update :lines dissoc nm)
                             (assoc :branch nm
                                    :db (:conn target)
                                    :store (:store target)
                                    :data-version (some-> (:conn target)
                                                          db/data-version)
                                    :image (or adopted (:image booted))
                                    :test-map {}))))
                (cond-> {:switched nm}
                  adopted       (assoc :adopted true)
                  (not adopted) (assoc :booted true)))))
        {:error (str "no branch named " nm)}))))
(defn ^:export branch-merge!
  "Merge branch `nm` into the CURRENT line (switch to main first to merge
  down). Same engine and semantics as fork merges, iterated merges included;
  the branch survives and can keep going."
  [session nm]
  (let [nm (str nm)]
    (if (= nm (:branch @session))
      {:error "cannot merge a branch into itself — switch to the target line first"}
      (if-let [target (load-line session nm)]
        (let [res (merge-into-session! session (:store target)
                                       (str "branch:" nm "#"
                                            (or (:line-id (:store target))
                                                "legacy")))]
          ;; lazily-opened conn is only needed for reading here
          (when (and (:conn target)
                     (not (contains? (:lines @session) nm)))
            (.close ^java.sql.Connection (:conn target)))
          res)
        {:error (str "no branch named " nm)}))))
(defn ^:export branch-delete!
  "Drop branch `nm` (never the one you are on). Durable sessions also remove
  its .slopp/branches dir."
  [session nm]
  (let [nm (str nm)
        {:keys [branch lines dir]} @session]
    (cond
      (= nm branch)
      {:error "cannot delete the branch you are on"}

      (not (or (contains? lines nm)
               (and dir (.exists (io/file (line-dir dir nm))))))
      {:error (str "no branch named " nm)}

      :else
      (do (some-> (get-in lines [nm :image]) repl/stop!)
          (some-> (get-in lines [nm :conn])
                  ^java.sql.Connection (.close))
          (swap! session update :lines dissoc nm)
          (when dir (delete-dir! (io/file (line-dir dir nm))))
          {:deleted nm}))))
(defn ^:export query-branches
  "Every line in the repo: the current one, in-memory lines, and (durable)
  on-disk branches not yet loaded this session."
  [session]
  (let [{:keys [branch lines dir store]} @session
        on-disk (when dir
                  (let [bdir (io/file dir ".slopp" "branches")]
                    (when (.exists bdir)
                      (map #(.getName ^java.io.File %)
                           (filter #(.isDirectory ^java.io.File %)
                                   (.listFiles bdir))))))
        info    (fn [nm st line]
                  (cond-> {:name nm}
                    st (assoc :head   (:id (last (store/deltas st)))
                              :deltas (count (store/deltas st)))
                    (:line-id st) (assoc :id (:line-id st))
                    (:image line) (assoc :image :parked)))]
    {:current  branch
     :branches (vec (concat
                     [(assoc (info branch store nil) :image :live)]
                     (for [[nm line] (sort-by key lines)]
                       (info nm (:store line) line))
                     (for [nm (sort (remove (set (conj (keys lines) branch))
                                            (or on-disk [])))]
                       {:name nm})))}))
