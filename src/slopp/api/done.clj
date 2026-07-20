(ns slopp.api.done
  (:require [clojure.string :as str]
            [slopp.api.session :as session]
            [slopp.index :as index]
            [slopp.normalize :as normalize]
            [slopp.render :as render]
            [slopp.store :as store]))

(defn normalize-rewrites "Which of the episode's `changed` form ids the normalizer would actually
  rewrite, as `[{:form-id :form :node :applied}]` — pure, nothing committed.
  `:applied` names the conservative behavior-preserving rewrites found; forms
  with none are omitted, so an empty result means there is nothing to do."
  [changed st]
  (vec (for [fid changed
                            :let [e (store/form-by-id st fid)
                                  {:keys [node applied]} (normalize/normalize-form (:node e))]
                            :when (seq applied)]
                        {:form-id fid
                         :form    (symbol (str (store/ns-of-form-id st fid))
                                          (str (or (:name e) (:id e))))
                         :node    node
                         :applied applied})))

(defn anchored-lint
  "Kondo findings for every namespace the EPISODE TOUCHED, expressed as ANCHORS
  rather than coordinates: each row carries the owning `:form` and an `:at`
  snippet of the offending line, and `:row`/`:col` are dropped.

  Episode-scoped on purpose. A store-wide scan at every done point re-judges
  code this episode never touched, which is `full_check`'s job — done reminds
  the agent it exists rather than doing it unasked.

  Coordinates never cross the wire because they are meaningless to a
  form-addressed agent — and stale the moment anything above them shifts. A
  form plus a match-ready snippet stays true and is what the edit tools take."
  [session changed]
  (vec (for [ns-sym (distinct (map #(store/ns-of-form-id (:store @session) %)
                                   changed))
             :let [st*   (:store @session)
                   src   (render/render-ns st* ns-sym)
                   lines (vec (str/split-lines src))]
             f (index/lint src)]
         ;; anchors, not coordinates: the owning form + a match-ready
         ;; snippet; row/col never cross the wire
         (cond-> (-> f
                     (dissoc :row :col)
                     (assoc :ns ns-sym
                            :form (when-let [e (render/owner-form
                                                st* ns-sym
                                                (:row f) (:col f))]
                                    (symbol (str ns-sym)
                                            (str (or (:name e) (:id e)))))))
           (get lines (dec (:row f 0)))
           (assoc :at (str/trim (nth lines (dec (:row f)))))))))

(defn with-unused-gate "Fold the unused-public report into `lint` as ERROR-grade rows — dead public
  surface (`:unused-public`) and stale `^:unused-ok` markers on vars that ARE
  called now (`:stale-unused-ok`). Both directions gate, so the marker can
  never drift from the truth in either direction. Pure."
  [lint unused-rep]
  (into lint
                   (concat
                    (for [q (:unused unused-rep)]
                      {:level :error :type :unused-public
                       :ns (symbol (namespace q)) :form q
                       :message (str q " is public but NOTHING in the store"
                                     " calls it — delete it, or mark the name"
                                     " ^:unused-ok to declare it deliberate"
                                     " (external surface, runtime-resolved"
                                     " entry)")})
                    (for [q (:stale unused-rep)]
                      {:level :error :type :stale-unused-ok
                       :ns (symbol (namespace q)) :form q
                       :message (str q " carries ^:unused-ok but IS called now"
                                     " — remove the flag")}))))

(defn apply-normalization! "Commit `rewrites` as one `:normalize` changeset: hot-load the rewritten
  forms, then rebase-commit. Throws rather than returning data, deliberately —
  a normalization that will not compile, or a store that moved underneath the
  done-point, are both invariant violations rather than expected outcomes, and
  continuing past either would record a boundary over code the image never
  accepted."
  [rewrites st label agent session]
  (when (seq rewrites)
                   (let [changeset   (into {} (map (juxt :form-id :node)) rewrites)
                         main-ns     (store/ns-of-form-id st (:form-id (first rewrites)))
                         [st' _]     (store/apply-changeset st :normalize main-ns changeset
                                                            :prompt (or label "done normalization")
                                                            :agent agent)
                         touched     (distinct (map #(store/ns-of-form-id st' %) (keys changeset)))]
                     (when-let [err (:err (session/hot-load-all! session st' (keys changeset)))]
                       (throw (ex-info (str "normalization failed to compile: " err) {})))
                     (when-not (session/try-commit! session st st' (vec touched))
                       (throw (ex-info "store changed during done — retry" {}))))))
