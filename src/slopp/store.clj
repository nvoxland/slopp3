(ns slopp.store
  "In-memory form store + append-only delta log — the system of record (C2/C3/C4).

  A namespace is an ordered sequence of *elements*: each element is either a
  semantic `:form` (a top-level sexpr, carrying a stable synthetic id + derived
  name + its rewrite-clj CST node) or a `:sep` (whitespace/comment/newline node
  kept only so rendering is lossless). Forms are the identified, versioned units;
  separators are incidental trivia (a Phase-1 simplification — a later model may
  attach leading trivia to the form it precedes).

  Ids are a monotonic counter here (single-agent Phase 1). Phase-4 multi-agent
  needs globally-unique ids (uuid / lamport)."
  (:require [clojure.string :as str]
            [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]
            [slopp.semver :as semver]))

(defn empty-store []
  ;; :dep-ns   lib → #{namespaces the dep provides} (M4 surface, for M3's
  ;;           external-call effect boundary)
  ;; :dep-pure #{qualified syms} the user has asserted pure (narrows M3)
  ;; :modules  module → #{declared dep modules} — fold of :module-edge
  ;;           deltas; {} from birth (enforcement always on), nil only in
  ;;           stores loaded from a pre-module db (open! adopts them)
  {:namespaces {} :deltas [] :next-id 0 :deps {} :dep-ns {} :dep-pure #{}
   :modules {}})

(defn now-ms
  "Epoch ms — the store's clock (public for the deep store packages)."
  [] (System/currentTimeMillis))

(defn late-ref
  "The BLESSED late-binding reference: a fn that resolves `qsym` at first
  call and delegates — for the narrow case where a static require would
  close a load cycle. This is the ONLY sanctioned home for a runtime-
  resolved var reference (the reference-carrier decision): the indexer
  reads the quoted symbol in this position as a REAL edge, so renames,
  moves, and the unused gate all see it — a naked requiring-resolve is
  invisible to all three."
  [qsym]
  (let [v (delay (requiring-resolve qsym))]
    (fn [& args] (apply @v args))))
(defn gen-id
  "Mint the next `prefix`-typed id → [id store'] — the store's id counter
  (public for the deep store packages; `alloc-id` is the external face)."
  [store prefix]
  (let [i (:next-id store)]
    [(str prefix i) (assoc store :next-id (inc i))]))

(defn alloc-id
  "Public id allocation (e.g. a `g<n>` group id shared by several deltas)."
  [store prefix]
  (gen-id store prefix))

(def ^:private def-heads
  "Head symbols whose second element names the form."
  '#{def defn defn- defmacro defmulti defmethod defrecord deftype
     defprotocol defonce deftest ns})

(defn form-symbol
  "The symbol a top-level form defines, or nil (anonymous/effectful top-levels).
  Sees through leading metadata (`^:unsafe`, `^{:integration true}`, …) so a
  marked form stays NAMED and addressable — without this, a `^:unsafe (defn …)`
  is a `:meta` node and would be anonymous."
  [node]
  (when (n/sexpr-able? node)
    (if (= :meta (n/tag node))
      (some-> (last (filter n/sexpr-able? (n/children node))) form-symbol)
      (when (= :list (n/tag node))
        (let [s (n/sexpr node)]
          (when (and (seq s) (symbol? (first s)) (contains? def-heads (first s)))
            (let [nm (second s)]
              (when (symbol? nm) nm))))))))

(defn name-of-source
  "The symbol a top-level form SOURCE string defines, or nil — the parse-back
  companion to `form-symbol` (used to resolve a form's NAME at a past delta,
  where names can differ from now via rename)."
  [source]
  (when source
    (some-> (first (filter n/sexpr-able?
                           (n/children (p/parse-string-all source))))
            form-symbol)))

(defn ingest
  "Parse `source` into `ns-sym`'s ordered elements, assigning a fresh id to each
  form, and append an `:ingest` delta. Returns the new store."
  [store ns-sym source & {:keys [agent]}]
  (let [nodes (n/children (p/parse-string-all source))]
    (loop [store store, nodes nodes, elements []]
      (if-let [node (first nodes)]
        (if (n/sexpr-able? node)
          (let [[id store] (gen-id store "f")]
            (recur store (rest nodes)
                   (conj elements {:id id :kind :form
                                   :name (form-symbol node) :node node})))
          (recur store (rest nodes)
                 (conj elements {:kind :sep :node node})))
        (let [[did store] (gen-id store "d")]
          (-> store
              (assoc-in [:namespaces ns-sym :elements] elements)
              (update :deltas conj
                      (cond-> {:id did :parent nil :op :ingest :ns ns-sym
                               :at (now-ms)
                               :form-ids (into [] (keep :id) elements)
                               ;; per-version content (C3/C4): history must be
                               ;; reconstructible from the log alone
                               :sources  (into {}
                                               (keep (fn [e]
                                                       (when (:id e)
                                                         [(:id e) (n/string (:node e))])))
                                               elements)}
                        agent (assoc :agent agent)))))))))

(defn elements
  "All elements of `ns-sym` in order (forms + separators)."
  [store ns-sym]
  (get-in store [:namespaces ns-sym :elements]))

(defn forms
  "Ordered semantic forms of `ns-sym` (separators dropped)."
  [store ns-sym]
  (filterv #(= :form (:kind %)) (elements store ns-sym)))

(defn form-named
  "The form in `ns-sym` defining symbol `nm`, or nil."
  [store ns-sym nm]
  (first (filter #(= nm (:name %)) (forms store ns-sym))))

(defn form-by-id
  "The form anywhere in the store with the given id, or nil."
  [store id]
  (->> (:namespaces store)
       (mapcat (comp :elements val))
       (filter #(= id (:id %)))
       first))

(defn deltas [store] (:deltas store))

(defn replace-node
  "Replace the CST node of the form named `nm` in `ns-sym`, keeping its stable id
  (C2/O1 whole-form replace); append a `:replace` delta carrying `prompt`.
  Returns [store' delta], or nil if no such form."
  [store ns-sym nm node & {:keys [prompt op group agent] :or {op :replace}}]
  (let [elems (get-in store [:namespaces ns-sym :elements])
        idx   (first (keep-indexed
                      (fn [i e] (when (and (= :form (:kind e)) (= nm (:name e))) i))
                      elems))]
    (when idx
      (let [elem     (nth elems idx)
            new-elem (assoc elem :node node :name (form-symbol node))
            [did store] (gen-id store "d")
            delta    (cond-> {:id did :parent (:id (last (:deltas store)))
                              :op op :ns ns-sym :form-id (:id elem) :prompt prompt
                              :at (now-ms)
                              :sources {(:id elem) (n/string node)}}
                       group (assoc :group group)
                       agent (assoc :agent agent))]
        [(-> store
             (assoc-in [:namespaces ns-sym :elements] (assoc elems idx new-elem))
             (update :deltas conj delta))
         delta]))))

(defn append-form
  "Add a new form to `ns-sym` with a fresh id; ONE `:add` delta. Default:
  appended at the tail (newline-separated). `:before <form-name>` anchors it
  immediately before that form instead — the delta records the anchor's
  form-ID so foreign replay converges on the same position. Returns
  [store' delta]; nil when the namespace — or the named anchor — doesn't
  exist."
  [store ns-sym node & {:keys [prompt group agent before]}]
  (when-let [elems (get-in store [:namespaces ns-sym :elements])]
    (let [anchor-idx (when before
                       (first (keep-indexed
                               (fn [i e] (when (and (= :form (:kind e))
                                                    (= before (:name e))) i))
                               elems)))]
      (when (or (nil? before) anchor-idx)
        (let [needs-nl?    (and (nil? anchor-idx)
                                (seq elems)
                                (not (str/ends-with? (n/string (:node (peek elems))) "\n")))
              [fid store]  (gen-id store "f")
              [did store'] (gen-id store "d")
              form-elem    {:id fid :kind :form :name (form-symbol node) :node node}
              new-elems    (if anchor-idx
                             (into (conj (subvec elems 0 anchor-idx)
                                         form-elem
                                         {:kind :sep :node (n/newlines 1)})
                                   (subvec elems anchor-idx))
                             (cond-> elems
                               needs-nl? (conj {:kind :sep :node (n/newlines 1)})
                               true      (conj form-elem
                                               {:kind :sep :node (n/newlines 1)})))
              delta        (cond-> {:id did :parent (:id (last (:deltas store)))
                                    :op :add :ns ns-sym :form-id fid :prompt prompt
                                    :at (now-ms)
                                    :sources {fid (n/string node)}}
                             anchor-idx (assoc :before (:id (nth elems anchor-idx)))
                             group (assoc :group group)
                             agent (assoc :agent agent))]
          [(-> store'
               (assoc-in [:namespaces ns-sym :elements] new-elems)
               (update :deltas conj delta))
           delta])))))

(defn remove-form
  "Remove the form named `nm` from `ns-sym` (plus its immediately following
  separator, so no doubled blank line remains); ONE `:delete` delta. Returns
  [store' delta], or nil if no such form."
  [store ns-sym nm & {:keys [prompt group agent]}]
  (let [elems (get-in store [:namespaces ns-sym :elements])
        ;; a STRING nm removes by form id (anonymous forms, e.g. declares)
        hit?  (if (string? nm)
                (fn [e] (= nm (:id e)))
                (fn [e] (and (= :form (:kind e)) (= nm (:name e)))))
        idx   (first (keep-indexed (fn [i e] (when (hit? e) i)) elems))]
    (when idx
      (let [fid          (:id (nth elems idx))
            drop-next?   (and (< (inc idx) (count elems))
                              (= :sep (:kind (nth elems (inc idx)))))
            new-elems    (into (subvec elems 0 idx)
                               (subvec elems (+ idx (if drop-next? 2 1))))
            [did store'] (gen-id store "d")
            delta        (cond-> {:id did :parent (:id (last (:deltas store)))
                                  :op :delete :ns ns-sym :form-id fid :name nm
                                  :removed-source (n/string (:node (nth elems idx)))
                                  :prompt prompt :at (now-ms)}
                           group (assoc :group group)
                           agent (assoc :agent agent))]
        [(-> store'
             (assoc-in [:namespaces ns-sym :elements] new-elems)
             (update :deltas conj delta))
         delta]))))

(defn move-form
  "Move the form named `nm` (with its trailing separator) to just before the
  form named `before-nm` (S2 — fixes append-only forward references). ONE
  `:move` delta. Returns [store' delta], or nil if either form is missing."
  [store ns-sym nm before-nm & {:keys [prompt group agent]}]
  (let [elems  (get-in store [:namespaces ns-sym :elements])
        idx-of (fn [es n]
                 (first (keep-indexed
                         (fn [i e] (when (and (= :form (:kind e)) (= n (:name e))) i))
                         es)))
        i (idx-of elems nm)
        j (idx-of elems before-nm)]
    (when (and i j (not= nm before-nm))
      (let [unit-end  (if (and (< (inc i) (count elems))
                               (= :sep (:kind (nth elems (inc i)))))
                        (+ i 2)
                        (inc i))
            unit      (subvec elems i unit-end)
            without   (into (subvec elems 0 i) (subvec elems unit-end))
            j'        (idx-of without before-nm)
            new-elems (-> (subvec without 0 j')
                          (into unit)
                          (into (subvec without j')))
            [did store'] (gen-id store "d")
            delta (cond-> {:id did :parent (:id (last (:deltas store)))
                           :op :move :ns ns-sym
                           :form-id (:id (nth elems i)) :before before-nm
                           :prompt prompt :at (now-ms)}
                    group (assoc :group group)
                    agent (assoc :agent agent))]
        [(-> store'
             (assoc-in [:namespaces ns-sym :elements] new-elems)
             (update :deltas conj delta))
         delta]))))

(defn- ns-requires
  "Store namespaces required by `ns-sym`'s ns form."
  [store ns-sym]
  (when-let [e (form-named store ns-sym ns-sym)]
    (let [s (n/sexpr (:node e))]
      (for [clause s
            :when (and (seq? clause) (= :require (first clause)))
            spec (rest clause)
            :let [lib (if (vector? spec) (first spec) spec)]
            :when (contains? (:namespaces store) lib)]
        lib))))

(defn ns-closure
  "`ns-sym` plus every store namespace it transitively requires — the scope a
  test run in `ns-sym` can reach (item 2: instrumenting ALL namespaces made
  per-write verification cost grow with total store size)."
  [store ns-sym]
  (loop [seen #{} frontier [ns-sym]]
    (if-let [n (first frontier)]
      (if (seen n)
        (recur seen (subvec frontier 1))
        (recur (conj seen n)
               (into (subvec frontier 1) (ns-requires store n))))
      seen)))

(defn ns-dependency-order
  "Every store namespace, dependencies first (X3): image loads MUST use this —
  a plain (keys (:namespaces store)) goes hash-ordered past 8 entries, which
  silently half-loaded 12-namespace images in eval round 3. Deterministic:
  ties break by sorted name; cycles fall back to sorted remainder."
  [store]
  (let [deps (into {}
                   (map (fn [n] [n (set (ns-requires store n))]))
                   (keys (:namespaces store)))]
    (loop [result [], remaining (vec (sort (keys deps))), done #{}]
      (if (empty? remaining)
        result
        (if-let [ready (first (filter #(every? done (deps %)) remaining))]
          (recur (conj result ready)
                 (vec (remove #{ready} remaining))
                 (conj done ready))
          (into result remaining))))))

(defn ns-of-form-id
  "The namespace whose elements contain the form with `id`, or nil."
  [store id]
  (some (fn [[ns-sym {:keys [elements]}]]
          (when (some #(= id (:id %)) elements) ns-sym))
        (:namespaces store)))

(defn apply-changeset
  "Coordinated multi-form edit (e.g. rename): replace several forms' nodes —
  possibly across namespaces — as ONE delta. `changeset` = {form-id new-node}.
  `extra` is merged into the delta (e.g. {:old .. :new ..}). Returns
  [store' delta]."
  [store op ns-sym changeset & {:keys [prompt extra agent]}]
  (let [[did store'] (gen-id store "d")
        delta (merge (cond-> {:id did :parent (:id (last (:deltas store)))
                              :op op :ns ns-sym :at (now-ms)
                              :form-ids (vec (sort (keys changeset)))
                              :sources  (into {} (map (fn [[fid node]]
                                                        [fid (n/string node)]))
                                              changeset)
                              :prompt prompt}
                       agent (assoc :agent agent))
                     extra)
        store' (reduce-kv
                (fn [st ns-key {:keys [elements]}]
                  (assoc-in st [:namespaces ns-key :elements]
                            (mapv (fn [e]
                                    (if-let [node (get changeset (:id e))]
                                      (assoc e :node node :name (form-symbol node))
                                      e))
                                  elements)))
                store' (:namespaces store'))]
    [(update store' :deltas conj delta) delta]))

(defn record-done
  "Append a `:done` boundary delta — a unit-of-work marker (and the
  close of `agent`'s episode). `:findings` is the done-processing verdict
  ({:test-status :failures :lint-errors ...}) riding the delta so history
  and the next session's brief can surface what the episode left behind.
  Returns [store' delta-id]."
  [store label & {:keys [agent findings]}]
  (let [[did store'] (gen-id store "d")]
    [(update store' :deltas conj
             (cond-> {:id did :parent (:id (last (:deltas store)))
                      :op :done :ns '*session* :at (now-ms)}
               label    (assoc :label label)
               agent    (assoc :agent agent)
               findings (assoc :findings findings)))
     did]))

(defn record-commit
  "Append a `:commit` MILESTONE marker (P4-m7) — a named pointer at `target`
  (a delta id, normally the head the done-point just produced) with a human-facing
  `description`. The important-done grain above turns; git's annotated
  tag, inside the journal. `extra` merges op-specific payload into the delta
  (P4-m8: `:tree` rendered-source snapshot, `:git-sha` import identity) —
  it must not carry the core keys (:id :op :ns :parent :at :description
  :target). Returns [store' delta]."
  [store description & {:keys [agent target status extra]}]
  (let [[did store'] (gen-id store "d")
        delta (cond-> {:id did :parent (:id (last (:deltas store)))
                       :op :commit :ns '*session* :at (now-ms)
                       :description description
                       :target (or target (:id (last (:deltas store))))}
                agent  (assoc :agent agent)
                status (assoc :status status)
                extra  (as-> d (merge extra d)))]
    [(update store' :deltas conj delta) delta]))

(defn record-deps-add
  "Append a `:deps-add` delta declaring external dependency `lib` at `coord`
  (a deps.edn coordinate map, e.g. `{:mvn/version \"1.2.3\"}`), and materialize
  it into the store's `:deps` manifest. A tracked delta (not a pure marker):
  it rides history / branches / merge / foreign-sync like every write.
  Returns [store' delta]."
  [store lib coord & {:keys [agent prompt namespaces]}]
  (let [[did store'] (gen-id store "d")
        delta (cond-> {:id did :parent (:id (last (:deltas store)))
                       :op :deps-add :ns '*session* :at (now-ms)
                       :lib lib :coord coord}
                (seq namespaces) (assoc :namespaces (vec namespaces))
                agent  (assoc :agent agent)
                prompt (assoc :prompt prompt))]
    [(-> store' (update :deltas conj delta)
         (assoc-in [:deps lib] coord)
         (assoc-in [:dep-ns lib] (set namespaces)))
     delta]))

(defn record-deps-remove
  "Append a `:deps-remove` delta dropping `lib` from the manifest.
  Returns [store' delta]."
  [store lib & {:keys [agent prompt]}]
  (let [[did store'] (gen-id store "d")
        delta (cond-> {:id did :parent (:id (last (:deltas store)))
                       :op :deps-remove :ns '*session* :at (now-ms)
                       :lib lib}
                agent  (assoc :agent agent)
                prompt (assoc :prompt prompt))]
    [(-> store' (update :deltas conj delta)
         (update :deps dissoc lib)
         (update :dep-ns dissoc lib))
     delta]))

(defn record-deps-pure
  "Append a `:deps-pure` delta marking qualified `sym` pure (`pure?` true) or
  un-pure (false) — the narrowing of M3's effectful-by-default boundary (the
  author asserts this dep var has no effect slopp should track).
  Returns [store' delta]."
  [store sym pure? & {:keys [agent prompt]}]
  (let [[did store'] (gen-id store "d")
        delta (cond-> {:id did :parent (:id (last (:deltas store)))
                       :op :deps-pure :ns '*session* :at (now-ms)
                       :sym sym :pure (boolean pure?)}
                agent  (assoc :agent agent)
                prompt (assoc :prompt prompt))]
    [(update (update store' :deltas conj delta)
             :dep-pure (fnil (if pure? conj disj) #{}) sym)
     delta]))

(defn record-turn
  "Append a turn marker (:turn-begin carries the VERBATIM user ask — the root
  intent of everything that follows; :turn-end closes the bracket, stable or
  not). Returns [store' delta]."
  [store kind & {:keys [agent intent user note]}]
  (let [[did store'] (gen-id store "d")
        delta (cond-> {:id did :parent (:id (last (:deltas store)))
                       :op kind :ns '*session* :at (now-ms)}
                agent  (assoc :agent agent)
                intent (assoc :intent intent)
                user   (assoc :user user)
                note   (assoc :note note))]
    [(update store' :deltas conj delta) delta]))

(defn- id-num [id]
  (some->> (re-find #"\d+$" (str id)) Long/parseLong))

(defn- bump-next-id [store d]
  (update store :next-id
          (fnil max 0)
          (long (inc (or (id-num (:id d)) 0)))
          (long (inc (apply max 0 (keep id-num
                                        (concat (when (:form-id d) [(:form-id d)])
                                                (:form-ids d))))))))

(defn replay-delta
  "Apply a FOREIGN delta from the SAME journal (linear history — ids are
  authoritative, nothing remaps) onto a trailing cached store. Returns the
  advanced store, or nil when this op needs a full reload (e.g. :ingest —
  the elements table has the writer's exact trivia; rebuild from there)."
  [store d]
  (let [with-d (fn [st] (bump-next-id (update st :deltas conj d) d))]
    (case (:op d)
      (:verify :done :merge :turn-begin :turn-end :commit)
      (with-d store)

      ;; manifest deltas carry state — reconstruct :deps/:dep-ns/:dep-pure
      :deps-add    (with-d (-> store
                               (assoc-in [:deps (:lib d)] (:coord d))
                               (assoc-in [:dep-ns (:lib d)] (set (:namespaces d)))))
      :deps-remove (with-d (-> store
                               (update :deps dissoc (:lib d))
                               (update :dep-ns dissoc (:lib d))))
      :deps-pure   (with-d (update store :dep-pure
                                   (fnil (if (:pure d) conj disj) #{}) (:sym d)))
      :file-put    (with-d (assoc-in store [:files (:path d)] (:content d)))
      :file-remove (with-d (update store :files dissoc (:path d)))

      :config-put
      (with-d (-> store
                  (assoc-in [:config (:path d) :format] (:format d))
                  (assoc-in [:config (:path d) :values (:key d)] (:value d))))

      :module-edge
      (with-d
        (if (= :remove (:action d))
          (let [deps (disj (get-in store [:modules (:from d)] #{}) (:to d))]
            (if (empty? deps)
              (update store :modules dissoc (:from d))
              (assoc-in store [:modules (:from d)] deps)))
          (update-in store [:modules (:from d)] (fnil conj #{}) (:to d))))

      :config-unset
      (with-d
        (let [st (update-in store [:config (:path d) :values] dissoc (:key d))]
          (if (empty? (get-in st [:config (:path d) :values]))
            (update st :config dissoc (:path d))
            st)))

      :trivia
      (with-d
        (update-in store [:namespaces (:ns d) :elements]
                   (fn [elems]
                     (let [end   (or (when (:before d)
                                       (first (keep-indexed
                                               (fn [i e] (when (= (:before d) (:id e)) i))
                                               elems)))
                                     (count elems))
                           start (loop [i end]
                                   (if (and (pos? i) (= :sep (:kind (nth elems (dec i)))))
                                     (recur (dec i))
                                     i))
                           seps  (mapv (fn [nd] {:kind :sep :node nd})
                                       (n/children (p/parse-string-all (:text d))))]
                       (into (into (subvec elems 0 start) seps)
                             (subvec elems end))))))

      (:replace :rename :normalize)
      (with-d
        (reduce-kv
         (fn [st fid src]
           (let [ns-sym (ns-of-form-id st fid)]
             (if-not ns-sym
               st                                  ; unknown form: ignore
               (update-in st [:namespaces ns-sym :elements]
                          (fn [elems]
                            (mapv (fn [e]
                                    (if (= fid (:id e))
                                      (let [node (p/parse-string src)]
                                        (assoc e :node node
                                               :name (form-symbol node)))
                                      e))
                                  elems))))))
         store (:sources d)))

      :add
      (let [ns-sym (:ns d)
            fid    (:form-id d)
            src    (get (:sources d) fid)]
        (if-not (get-in store [:namespaces ns-sym])
          nil                                     ; ns unknown → full reload
          (with-d
            (update-in store [:namespaces ns-sym :elements]
                       (fn [elems]
                         (let [node       (p/parse-string src)
                               form-elem  {:id fid :kind :form
                                           :name (form-symbol node) :node node}
                               ;; anchored add (:before = anchor form-id):
                               ;; same position as the writer; gone → append
                               anchor-idx (when (:before d)
                                            (first (keep-indexed
                                                    (fn [i e] (when (= (:before d) (:id e)) i))
                                                    elems)))]
                           (if anchor-idx
                             (into (conj (subvec elems 0 anchor-idx)
                                         form-elem
                                         {:kind :sep :node (n/newlines 1)})
                                   (subvec elems anchor-idx))
                             (let [needs-nl? (and (seq elems)
                                                  (not (str/ends-with?
                                                        (n/string (:node (peek elems)))
                                                        "\n")))]
                               (cond-> elems
                                 needs-nl? (conj {:kind :sep :node (n/newlines 1)})
                                 true      (conj form-elem
                                                 {:kind :sep
                                                  :node (n/newlines 1)}))))))))))

      :delete
      (let [ns-sym (:ns d)
            fid    (:form-id d)]
        (with-d
          (update-in store [:namespaces ns-sym :elements]
                     (fn [elems]
                       (if-let [idx (first (keep-indexed
                                            (fn [i e] (when (= fid (:id e)) i))
                                            elems))]
                         (let [drop-next? (and (< (inc idx) (count elems))
                                               (= :sep (:kind (nth elems (inc idx)))))]
                           (into (subvec elems 0 idx)
                                 (subvec elems (+ idx (if drop-next? 2 1)))))
                         elems)))))

      ;; :ingest / :move / anything unknown → full reload
      nil)))

(defn sources-at
  "The {form-id source-text} content view as of delta `at-id` (inclusive;
  nil = before any delta). Reconstructed from the log — powers episode
  diffs."
  [store at-id]
  (if (nil? at-id)
    {}
    (reduce (fn [acc d]
              (let [acc (merge acc (:sources d))
                    acc (if (= :delete (:op d)) (dissoc acc (:form-id d)) acc)]
                (if (= at-id (:id d)) (reduced acc) acc)))
            {}
            (:deltas store))))

(defn record-verification
  "Append a `:verify` delta recording a test-run result against `ns-sym` — 'what
  was proven green at this point' (C4, D5/D6 verification-provenance)."
  [store ns-sym result]
  (let [parent (:id (last (:deltas store)))
        [did store] (gen-id store "d")]
    (update store :deltas conj
            {:id did :parent parent :op :verify :ns ns-sym :at (now-ms)
             :result result})))

;; --- Phase 4 m2: the CRDT merge -------------------------------------------

(defn suffix-touched
  "Form ids touched by content deltas in a delta seq."
  [ds]
  (into #{}
        (mapcat (fn [d]
                  (concat (when (:form-id d) [(:form-id d)])
                          (:form-ids d)
                          (keys (:sources d)))))
        ds))

(defn qform
  "form-id → qualified symbol (ns/name), reading `store` first and
  `fallback-store` for forms that no longer exist there (public for the
  deep merge package — conflict rows name forms from either side)."
  [store ns-sym fid fallback-store]
  (let [e (or (form-by-id store fid) (form-by-id fallback-store fid))]
    (symbol (str ns-sym) (str (or (:name e) fid)))))

(defn modules-cycle
  "A dependency cycle in a module manifest ({module #{deps}}) as a module
  path [a b ... a], or nil when the graph is acyclic. Pure DFS
  three-coloring; deterministic order."
  [manifest]
  (letfn [(visit [state path m]
            (case (get state m)
              :done [state nil]
              :in   [state (subvec path (.indexOf ^java.util.List path m))]
              (let [[state cyc]
                    (reduce (fn [[st c] d]
                              (if c [st c] (visit st (conj path d) d)))
                            [(assoc state m :in) nil]
                            (sort (get manifest m #{})))]
                [(assoc state m :done) cyc])))]
    (loop [state {}
           ms (sort (keys manifest))]
      (when-let [m (first ms)]
        (let [[state cyc] (visit state [m] m)]
          (if cyc cyc (recur state (rest ms))))))))
(defn module-path
  "The shortest dependency path from module `from` to module `to` through
  `manifest` ({module #{deps}}) as [from ... to], or nil when unreachable.
  BFS, deterministic. An edge a→b CLOSES a cycle iff (module-path m b a)
  exists — the local check module_dep uses, so a pre-existing (adopted)
  cycle elsewhere never blocks an unrelated declaration."
  [manifest from to]
  (loop [frontier [[(str from)]]
         seen #{(str from)}]
    (when-let [path (first frontier)]
      (let [cur (peek path)]
        (if (= cur (str to))
          path
          (let [nexts (remove seen (sort (get manifest cur #{})))]
            (recur (into (vec (rest frontier))
                         (map #(conj path %))
                         nexts)
                   (into seen nexts))))))))
(defn module-layers
  "Topological LAYERS of a module manifest ({module #{deps}}) — the
  architecture at a glance: layer 0 depends on nothing, layer n's deepest
  dep sits at n-1. Cycles are CONDENSED first (Kosaraju SCC), so members
  of a dependency cycle share one layer instead of poisoning the picture;
  multi-member components also return under :cycles. Modules appearing
  only as deps (declaring nothing) are layer 0. Pure; deterministic.
  Returns {:layers [[module ...] ...] :cycles [[member ...] ...]}."
  [manifest]
  (let [nodes (vec (sort (into (set (keys manifest))
                               (mapcat identity)
                               (vals manifest))))
        succs (fn [m] (sort (get manifest m #{})))
        preds (reduce (fn [acc [m ds]]
                        (reduce #(update %1 %2 (fnil conj #{}) m) acc ds))
                      {} manifest)
        ;; Kosaraju pass 1: finish order
        [_ order] (reduce (fn dfs1 [[seen order :as st] m]
                            (if (contains? seen m)
                              st
                              (let [[seen order]
                                    (reduce dfs1 [(conj seen m) order] (succs m))]
                                [seen (conj order m)])))
                          [#{} []] nodes)
        ;; pass 2: components on the transpose, reverse finish order
        [assigned comps]
        (loop [ms (reverse order) assigned {} comps []]
          (if-let [m (first ms)]
            (if (assigned m)
              (recur (rest ms) assigned comps)
              (let [members (loop [stack [m] mem #{}]
                              (if-let [x (peek stack)]
                                (let [stack (pop stack)]
                                  (if (or (contains? mem x) (assigned x))
                                    (recur stack mem)
                                    (recur (into stack (sort (get preds x #{})))
                                           (conj mem x))))
                                mem))]
                (recur (rest ms)
                       (reduce #(assoc %1 %2 (count comps)) assigned members)
                       (conj comps (vec (sort members))))))
            [assigned comps]))
        comp-deps (fn [ci] (into #{}
                                 (comp (mapcat succs) (map assigned) (remove #{ci}))
                                 (nth comps ci)))
        ;; condensation is a DAG — memoized layer recursion is safe
        layers (reduce (fn layer-of [memo ci]
                         (if (contains? memo ci)
                           memo
                           (let [ds   (comp-deps ci)
                                 memo (reduce layer-of memo ds)]
                             (assoc memo ci (if (empty? ds)
                                              0
                                              (inc (apply max (map memo ds))))))))
                       {} (range (count comps)))
        maxl   (reduce max 0 (vals layers))]
    {:layers (vec (for [i (range (inc maxl))]
                    (vec (sort (mapcat (fn [[ci l]] (when (= l i) (nth comps ci)))
                                       layers)))))
     :cycles (vec (filter #(> (count %) 1) comps))}))
(defn replace-trivia
  "Replace the ENTIRE trivia run (comments/whitespace `:sep` elements)
  immediately before form `anchor` (a form name; nil = the run after the
  LAST form) in `ns-sym` with `text`. The text is normalized to start and
  end with a newline (empty = a single newline, i.e. delete the gap); text
  containing any CODE form is refused. ONE `:trivia` delta carrying the
  anchor's form-id, so foreign replay converges. Returns [store' delta] or
  {:error msg}."
  [store ns-sym anchor text & {:keys [prompt agent]}]
  (let [elems (get-in store [:namespaces ns-sym :elements])]
    (if-not elems
      {:error (str "no namespace " ns-sym)}
      (let [aidx (when anchor
                   (first (keep-indexed
                           (fn [i e] (when (and (= :form (:kind e))
                                                (= anchor (:name e))) i))
                           elems)))]
        (if (and anchor (nil? aidx))
          {:error (str "no form named " anchor " in " ns-sym)}
          (let [norm  (if (str/blank? (str text))
                        "\n"
                        (cond-> (str text)
                          (not (str/starts-with? (str text) "\n")) (->> (str "\n"))
                          (not (str/ends-with? (str text) "\n"))   (str "\n")))
                nodes (n/children (p/parse-string-all norm))]
            (if (some n/sexpr-able? nodes)
              {:error "trivia only — the text contains a code form (use edit_add_form for forms)"}
              (let [end   (or aidx (count elems))
                    start (loop [i end]
                            (if (and (pos? i) (= :sep (:kind (nth elems (dec i)))))
                              (recur (dec i))
                              i))
                    seps  (mapv (fn [nd] {:kind :sep :node nd}) nodes)
                    [did store'] (gen-id store "d")
                    delta (cond-> {:id did :parent (:id (last (:deltas store)))
                                   :op :trivia :ns ns-sym :at (now-ms)
                                   :text norm}
                            aidx   (assoc :before (:id (nth elems aidx)))
                            prompt (assoc :prompt prompt)
                            agent  (assoc :agent agent))]
                [(-> store'
                     (assoc-in [:namespaces ns-sym :elements]
                               (into (into (subvec elems 0 start) seps)
                                     (subvec elems end)))
                     (update :deltas conj delta))
                 delta]))))))))
(defn record-file-put
  "Track a NON-CODE file (README, .github workflows, …) on the store's
  `:files` manifest ({path → text}) — these ride every projected tree, so
  they survive slopp pushes. ONE state-carrying `:file-put` delta (replay
  reconstructs the manifest incrementally, like :deps-add). Returns
  [store' delta]."
  [store path text & {:keys [prompt agent]}]
  (let [[did store'] (gen-id store "d")
        delta (cond-> {:id did :parent (:id (last (:deltas store)))
                       :op :file-put :ns '*session* :at (now-ms)
                       :path (str path) :content (str text)}
                prompt (assoc :prompt prompt)
                agent  (assoc :agent agent))]
    [(-> store'
         (assoc-in [:files (str path)] (str text))
         (update :deltas conj delta))
     delta]))
(defn record-file-remove
  "Drop `path` from the `:files` manifest. ONE `:file-remove` delta.
  Returns [store' delta]."
  [store path & {:keys [prompt agent]}]
  (let [[did store'] (gen-id store "d")
        delta (cond-> {:id did :parent (:id (last (:deltas store)))
                       :op :file-remove :ns '*session* :at (now-ms)
                       :path (str path)}
                prompt (assoc :prompt prompt)
                agent  (assoc :agent agent))]
    [(-> store'
         (update :files dissoc (str path))
         (update :deltas conj delta))
     delta]))
(defn file-history
  "Every tracked version of manifest file `path`, oldest first:
  [{:delta :op :at :agent :prompt :bytes}] (bytes absent on remove) — the
  file counterpart of query_form_history, read straight off the delta log."
  [store path]
  (into []
        (keep (fn [d]
                (case (:op d)
                  :file-put    (when (= (str path) (:path d))
                                 {:delta (:id d) :op :file-put :at (:at d)
                                  :agent (:agent d) :prompt (:prompt d)
                                  :bytes (count (:content d))})
                  :file-remove (when (= (str path) (:path d))
                                 {:delta (:id d) :op :file-remove :at (:at d)
                                  :agent (:agent d) :prompt (:prompt d)})
                  nil)))
        (:deltas store)))
(defn file-at
  "Manifest file `path`'s content as of delta `at-id` (inclusive), or nil
  (absent / removed / unknown delta) — the file counterpart of query_form_at."
  [store path at-id]
  (let [upto (reduce (fn [acc d]
                       (let [acc (conj acc d)]
                         (if (= at-id (:id d)) (reduced acc) acc)))
                     [] (:deltas store))]
    (when (= at-id (:id (peek upto)))
      (reduce (fn [cur d]
                (cond
                  (and (= :file-put (:op d)) (= (str path) (:path d)))
                  (:content d)

                  (and (= :file-remove (:op d)) (= (str path) (:path d)))
                  nil

                  :else cur))
              nil upto))))
(defn record-module-edge
  "Declare (or retract) ONE module dependency edge — the CRDT grain of the
  module manifest: concurrent edge declarations touch disjoint state and
  merge as a set union, and each edge carries its own why (:prompt) in the
  journal instead of vanishing into a file diff. `action` is :add or
  :remove. Returns [store' delta]."
  [store from to action & {:keys [prompt agent]}]
  (let [[did store'] (gen-id store "d")
        delta (cond-> {:id did :parent (:id (last (:deltas store)))
                       :op :module-edge :ns '*session* :at (now-ms)
                       :from (str from) :to (str to) :action action}
                prompt (assoc :prompt prompt)
                agent  (assoc :agent agent))
        fold  (fn [st]
                (if (= :remove action)
                  (let [deps (disj (get-in st [:modules (str from)] #{}) (str to))]
                    (if (empty? deps)
                      (update st :modules dissoc (str from))
                      (assoc-in st [:modules (str from)] deps)))
                  (update-in st [:modules (str from)] (fnil conj #{}) (str to))))]
    [(-> store' fold (update :deltas conj delta)) delta]))
(defn record-config-put
  "Set one KEY of the structured config file at `path` (format `fmt`, e.g.
  :manifest) — the non-code analog of a form edit: the store holds SEMANTIC
  key/values with per-key history; the projection serializes them into the
  file format. ONE state-carrying `:config-put` delta. Returns [store' delta]."
  [store path fmt k v & {:keys [prompt agent]}]
  (let [[did store'] (gen-id store "d")
        delta (cond-> {:id did :parent (:id (last (:deltas store)))
                       :op :config-put :ns '*session* :at (now-ms)
                       :path (str path) :format fmt
                       :key (str k) :value (str v)}
                prompt (assoc :prompt prompt)
                agent  (assoc :agent agent))]
    [(-> store'
         (assoc-in [:config (str path) :format] fmt)
         (assoc-in [:config (str path) :values (str k)] (str v))
         (update :deltas conj delta))
     delta]))
(defn record-config-unset
  "Remove one key from the config file at `path` (the whole entry when the
  last key goes). ONE `:config-unset` delta. Returns [store' delta]."
  [store path k & {:keys [prompt agent]}]
  (let [[did store'] (gen-id store "d")
        delta (cond-> {:id did :parent (:id (last (:deltas store)))
                       :op :config-unset :ns '*session* :at (now-ms)
                       :path (str path) :key (str k)}
                prompt (assoc :prompt prompt)
                agent  (assoc :agent agent))
        drop-key (fn [st]
                   (let [st (update-in st [:config (str path) :values]
                                       dissoc (str k))]
                     (if (empty? (get-in st [:config (str path) :values]))
                       (update st :config dissoc (str path))
                       st)))]
    [(-> store' drop-key (update :deltas conj delta))
     delta]))
(defn render-config
  "Serialize a config entry {:format f :values {k v}} to its file text.
  Formats: :manifest (sorted `K: V` lines). Unknown format → ex-info —
  new formats add a case here (the serializer is the whole contract)."
  [{:keys [format values] :as entry}]
  (case format
    :manifest (apply str (map (fn [[k v]] (str k ": " v "\n"))
                              (sort-by key values)))
    (throw (ex-info (str "no serializer for config format " format)
                    {:entry entry}))))
