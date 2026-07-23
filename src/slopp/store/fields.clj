(ns slopp.store.fields
  "The declarative registry of store FOLD-FIELDS and their delta ops — ONE
  entry per concept drives every cross-site behavior. Before this, adding a
  fold-field meant editing seven near-identical sites (empty-store,
  replay-delta, record-*, persist!, append!, load-store, merge-logs), and a
  forgotten site corrupted state SILENTLY — merge-logs' unknown-op default
  once dropped every config and file delta crossing a branch merge for three
  waves. The registry makes the miss structural instead: an op absent from
  every set here cannot cross a merge (merge-logs REFUSES, naming this ns)
  and full-reloads on foreign sync rather than guessing.")

(defn ^:export ^:legacy-ok canonical-tier
  "Canonical spelling of a purity tier: the retired :reads/:effects map to
  :internal/:external (D-tiers); canonical spellings pass through. THE one
  mapping — slopp.edit.modules/canonical-tier delegates here, and the
  :module-tier fold plus the db load normalize through it, so fold STATE can
  no longer carry retired vocabulary."
  [tier]
  ({:reads :internal :effects :external} tier tier))

(defn ^:export canonical-platform
  "Canonical spelling of a module's target PLATFORM — :jvm (Clojure on the JVM,
  the default), :cljc (portable: loads on the JVM AND compiles to JS), or :cljs
  (ClojureScript only — never loaded into the JVM oracle). Coerces a string or
  colon-prefixed spelling to the keyword so an MCP/JSON value round-trips (the
  deps-exclusions-as-strings lesson). The three-value validation happens at the
  api boundary (module-platform!), so this stays total for the fold/replay path."
  [platform]
  (let [s (name platform)
        s (if (= \: (first s)) (subs s 1) s)]
    (keyword s)))

(def field-registry
  "Store fold-field → persistence declaration. :init seeds empty-store;
  :meta-key names the db meta row persist!/append! write and load-store
  reads; :normalize (optional) canonicalizes the loaded value; :absent-nil?
  marks a field whose MISSING row is a meaningful nil (never defaulted on
  load, never written when nil — :modules' pre-module adoption marker).
  :blobs is table-backed (:storage :table): registered so empty-store seeds
  it and the inventory is complete, while the db keeps bespoke byte handling."
  {:deps         {:init {} :meta-key "deps"
                  :doc "lib → deps.edn coordinate (the Tier-1 manifest)"}
   :dep-ns       {:init {} :meta-key "dep-ns"
                  :doc "lib → #{namespaces the dep provides} (M4 surface)"}
   :dep-pure     {:init #{} :meta-key "dep-pure"
                  :doc "#{qualified syms} the user asserted pure (narrows M3)"}
   :modules      {:init {} :meta-key "modules" :absent-nil? true
                  :doc "module → #{declared dep modules}; nil only when loaded from a pre-module db (open! adopts)"}
   :module-tiers {:init {} :meta-key "module-tiers"
                  :normalize (fn [tiers]
                               (into {} (map (fn [[m t]] [m (canonical-tier t)])) tiers))
                  :doc "module → purity tier, canonical spellings only (D9)"}
:module-platforms {:init {} :meta-key "module-platforms"
                      :normalize (fn [pfs]
                                   (into {} (map (fn [[m p]] [m (canonical-platform p)])) pfs))
                      :doc "module → target platform :jvm/:cljc/:cljs (default :jvm, D-web-cljs)"}
   :files        {:init {} :meta-key "files"
                  :doc "path → text, or {:sha :bytes :content-type} for binary (bytes live in :blobs)"}
   :config       {:init {} :meta-key "config"
                  :doc "path → {:format :values {key value}} structured config (G9)"}
   :blobs        {:init {} :storage :table
                  :doc "sha256 → bytes, content-addressed (assets); merge = union, cleanup prunes"}})

(def ^:legacy-ok op-registry
  "Field-carrying delta op → {:field :fold :merge :sample :crossed}. :fold is
  THE fold — record-* (in-memory), replay-delta (foreign sync) and
  merge-logs' :replay strategy all call it, so the three can never drift.
  :merge :replay = path/key-grain last-writer-wins straight through the fold;
  :bespoke = merge-logs keeps a semantic arm (:deps-add version resolution,
  :module-edge set union). :sample (+ optional :sample-pre) are delta
  PAYLOADS and :crossed the post-merge assertion — slopp.store.fields-test
  GENERATES a round-trip case per op from them, so an op cannot be registered
  without proving it crosses a merge. (^:legacy-ok: the :module-tier sample
  carries a retired spelling ON PURPOSE — canonicalization must cross.)"
  {:deps-add     {:field :deps :merge :bespoke
                  :fold (fn [st d]
                          (-> st
                              (assoc-in [:deps (:lib d)] (:coord d))
                              (assoc-in [:dep-ns (:lib d)] (set (:namespaces d)))))
                  :sample {:op :deps-add :lib 'sample/lib
                           :coord {:mvn/version "1.0.0"} :namespaces ['sample.core]}
                  :crossed (fn [st] (= {:mvn/version "1.0.0"}
                                       (get-in st [:deps 'sample/lib])))}
   :deps-remove  {:field :deps :merge :replay
                  :fold (fn [st d]
                          (-> st
                              (update :deps dissoc (:lib d))
                              (update :dep-ns dissoc (:lib d))))
                  :sample-pre [{:op :deps-add :lib 'gone/lib
                                :coord {:mvn/version "0.1"} :namespaces []}]
                  :sample {:op :deps-remove :lib 'gone/lib}
                  :crossed (fn [st] (not (contains? (:deps st) 'gone/lib)))}
   :deps-pure    {:field :dep-pure :merge :replay
                  :fold (fn [st d]
                          (update st :dep-pure
                                  (fnil (if (:pure d) conj disj) #{}) (:sym d)))
                  :sample {:op :deps-pure :sym 'sample.core/f :pure true}
                  :crossed (fn [st] (contains? (:dep-pure st) 'sample.core/f))}
   :module-tier  {:field :module-tiers :merge :replay
                  ;; canonicalizes AT the fold: a legacy journal replaying
                  ;; :effects can no longer re-mint retired vocabulary into
                  ;; fold state (frictions #5)
                  :fold (fn [st d]
                          (assoc-in st [:module-tiers (:module d)]
                                    (canonical-tier (:tier d))))
                  ;; the sample uses a RETIRED spelling on purpose — crossing
                  ;; a merge must land the canonical one
                  :sample {:op :module-tier :module "sample.mod" :tier :effects}
                  :crossed (fn [st] (= :external
                                       (get-in st [:module-tiers "sample.mod"])))}
   :module-edge  {:field :modules :merge :bespoke
                  :fold (fn [st d]
                          (if (= :remove (:action d))
                            (let [deps (disj (get-in st [:modules (:from d)] #{})
                                             (:to d))]
                              (if (empty? deps)
                                (update st :modules dissoc (:from d))
                                (assoc-in st [:modules (:from d)] deps)))
                            (update-in st [:modules (:from d)]
                                       (fnil conj #{}) (:to d))))
                  :sample {:op :module-edge :from "sample.app" :to "sample.lib"
                           :action :add}
                  :crossed (fn [st] (contains? (get-in st [:modules "sample.app"] #{})
                                               "sample.lib"))}
   :file-put     {:field :files :merge :replay
                  ;; the entry derives from the DELTA (sha/bytes/content-type
                  ;; or :content) — blob BYTES never ride deltas; record-*
                  ;; assoc's them locally and merges union the :blobs table
                  :fold (fn [st d]
                          (assoc-in st [:files (:path d)]
                                    (if (:sha d)
                                      (cond-> {:sha (:sha d) :bytes (:bytes d)}
                                        (:content-type d)
                                        (assoc :content-type (:content-type d)))
                                      (:content d))))
                  :sample {:op :file-put :path "SAMPLE.md" :content "sample\n"}
                  :crossed (fn [st] (= "sample\n" (get-in st [:files "SAMPLE.md"])))}
   :file-remove  {:field :files :merge :replay
                  :fold (fn [st d] (update st :files dissoc (:path d)))
                  :sample-pre [{:op :file-put :path "GONE.md" :content "x\n"}]
                  :sample {:op :file-remove :path "GONE.md"}
                  :crossed (fn [st] (not (contains? (:files st) "GONE.md")))}
:module-platform {:field :module-platforms :merge :replay
                  :fold (fn [st d]
                          (assoc-in st [:module-platforms (:module d)]
                                    (canonical-platform (:platform d))))
                  :sample {:op :module-platform :module "sample.mod" :platform :cljs}
                  :crossed (fn [st] (= :cljs (get-in st [:module-platforms "sample.mod"])))}
   :config-put   {:field :config :merge :replay
                  :fold (fn [st d]
                          (-> st
                              (assoc-in [:config (:path d) :format] (:format d))
                              (assoc-in [:config (:path d) :values (:key d)]
                                        (:value d))))
                  :sample {:op :config-put :path "sample" :format :manifest
                           :key "k" :value "v"}
                  :crossed (fn [st] (= "v" (get-in st [:config "sample" :values "k"])))}
   :config-unset {:field :config :merge :replay
                  :fold (fn [st d]
                          (let [st (update-in st [:config (:path d) :values]
                                              dissoc (:key d))]
                            (if (empty? (get-in st [:config (:path d) :values]))
                              (update st :config dissoc (:path d))
                              st)))
                  :sample-pre [{:op :config-put :path "gone" :format :manifest
                                :key "k" :value "v"}]
                  :sample {:op :config-unset :path "gone" :key "k"}
                  :crossed (fn [st] (not (contains? (:config st) "gone")))}})

(def ^:export markers
  "No-content ops: replay-delta appends them (bookkeeping only) and
  merge-logs skips them — :verify/:done/:merge silently, the rest with a
  :skipped note (milestone markers deliberately do not travel; the open
  decision is frictions #9). A NEW marker op registers here, or foreign
  sync full-reloads on every sighting of it. Exported: session_brief reads
  it to exclude markers from the host's code-delta count (review host-F2)."
  #{:verify :done :merge :turn-begin :turn-end :commit :revert})

(def silent-markers
  "The marker subset merge-logs skips without a note — verification and
  merge bookkeeping whose absence from the receiving line is the norm."
  #{:verify :done :merge})

(def element-ops
  "Form/namespace CONTENT ops — the bespoke replay/merge machinery owns
  them (:trivia merges as a deliberate skip: cosmetic payload, form-id
  aliasing risk; :ns-delete removes an EMPTY namespace — replay dissocs or
  full-reloads, and the merge applies it only when the receiving side's
  copy is also empty)."
  #{:ingest :replace :add :delete :rename :normalize :move :trivia :ns-delete})

(defn fold
  "Apply the ONE registered fold for a field-carrying delta to `store` —
  fields only, no :deltas/:next-id bookkeeping (callers own that). Nil when
  the op carries no field (marker / element op / unknown)."
  [store d]
  (when-let [f (get-in op-registry [(:op d) :fold])]
    (f store d)))

(defn replay-merge-op?
  "True for ops merge-logs lands through the generic fold (path/key-grain
  last-writer-wins) rather than a semantic arm."
  [op]
  (= :replay (get-in op-registry [op :merge])))

(defn field-defaults
  "field → :init for every registered fold-field — empty-store's seed."
  []
  (into {} (map (fn [[k v]] [k (:init v)])) field-registry))

(defn ^:export meta-fields
  "The meta-row-persisted fields as [{:field :meta-key :init :absent-nil?
  :normalize}] — the db's ONE write and read loop (:storage :table fields
  excluded; the db keeps their bespoke handling)."
  []
  (into []
        (keep (fn [[k v]]
                (when (:meta-key v)
                  {:field k :meta-key (:meta-key v) :init (:init v)
                   :absent-nil? (boolean (:absent-nil? v))
                   :normalize (:normalize v)})))
        field-registry))
