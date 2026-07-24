(ns slopp.api.done
  (:require [clojure.string :as str]
            [slopp.api.session :as session]
            [slopp.index :as index]
            [slopp.index.normalize :as normalize]
            [slopp.store.render :as render]
            [slopp.store :as store] [rewrite-clj.node :as n]))

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

(defn- require-specs
  "Every require spec (symbol or vector) in an ns form's sexpr."
  [ns-form]
  (for [c ns-form
        :when (and (seq? c) (= :require (first c)))
        spec (rest c)]
    spec))

(defn side-effect-required?
  "True when `ns-sym`'s require of `lib` carries the `^:side-effect` marker —
   a require the done-point kept because removing it broke verification (a
   load-bearing registration the reference graph can't see). Such a require is
   deliberately present, so it is NOT reported as unused and NOT re-tried."
  [st ns-sym lib]
  (boolean
   (when-let [e (store/form-named st ns-sym ns-sym)]
     (some (fn [spec]
             (let [l (if (vector? spec) (first spec) spec)]
               (and (= l lib) (:side-effect (meta spec)))))
           (require-specs (n/sexpr (:node e)))))))

(defn unused-requires
  "The requires of `ns-sym` that kondo reports unused and that are not already
   marked `^:side-effect` — the done-point's prune candidates. Each entry is
   `{:lib sym :marked \"^:side-effect …\"}`; `:marked` is the re-add form used
   if the empirical removal turns out to break verification. Pure over the
   store — the effectful try-remove-verify loop lives in `slopp.api`."
  [st ns-sym]
  (when-let [e (store/form-named st ns-sym ns-sym)]
    (let [flagged (into #{}
                        (keep (fn [f]
                                (when (= :unused-namespace (:type f))
                                  (some-> (re-find #"namespace (\S+) is required"
                                                   (:message f))
                                          second symbol)))
                              (index/lint (render/render-ns st ns-sym)
                                          (store/kondo-lang st ns-sym))))]
      (vec (for [spec (require-specs (n/sexpr (:node e)))
                 :let [lib (if (vector? spec) (first spec) spec)]
                 :when (and (symbol? lib)
                            (contains? flagged lib)
                            (not (:side-effect (meta spec))))]
             {:lib    lib
              :marked (str "^:side-effect "
                           (pr-str (if (vector? spec) spec [spec])))})))))

(defn marked-unused?
  "True when kondo finding `f` is an `:unused-namespace` for a require that
   carries the `^:side-effect` keep-marker — a require the done-point kept
   because removing it breaks a cold load. It is deliberately present, so the
   finding is suppressed: a kept require must not read as unused."
  [st ns-sym f]
  (boolean
   (and (= :unused-namespace (:type f))
        (when-let [lib (some-> (re-find #"namespace (\S+) is required" (:message f))
                               second symbol)]
          (side-effect-required? st ns-sym lib)))))

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
             f (index/lint src (store/kondo-lang st* ns-sym))
             :when (not (marked-unused? st* ns-sym f))]
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
