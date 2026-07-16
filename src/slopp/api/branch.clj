(ns slopp.api.branch
  (:require [clojure.java.io :as io]
            [slopp.api.session :as session]
            [slopp.db :as db]
            [slopp.edit :as edit]
            [slopp.image :as image]
            [slopp.repl :as repl]
            [slopp.store :as store]))

(defn merge-into-session!
  "Shared merge pipeline (m2 forks + m3 branches): replay `theirs` onto the
  session store (store/merge-logs), hot-load what arrived (new namespaces in
  dependency order, then changed forms through the compile gate), commit,
  persist, verify every touched namespace, and record ONE `:merge` delta."
  [session theirs from-label]
  (let [t0   (System/nanoTime)
        base (:store @session)
        r    (store/merge-logs base theirs :from from-label)]
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
              {:error (str "merge failed to compile: " load-err)})
          (let [[st'' mdelta] (store/record-merge st' from-label r)]
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
