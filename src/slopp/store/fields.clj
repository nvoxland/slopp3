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

(defn ^:export canonical-tier
  "Canonical spelling of a purity tier: the retired :reads/:effects map to
  :internal/:external (D-tiers); canonical spellings pass through. THE one
  mapping — slopp.edit.tiers/canonical-tier delegates here, and the
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

(defn ^:export canonical-role
  "Canonical spelling of a module's ROLE — :product (the default: the system
  runs this code and it ships) or :instrument (a HUMAN runs it by hand — a
  benchmark, a migration script, a mining CLI — so it is materialized outside
  `src/` and never reaches the jar, R5). Coerces a string or colon-prefixed
  spelling to the keyword so an MCP/JSON value round-trips, the same way
  `canonical-tier` and `canonical-platform` do. The two-value validation
  happens at the api boundary (module-role!), so this stays total for the
  fold/replay path."
  [role]
  (let [s (name role)
        s (if (= \: (first s)) (subs s 1) s)]
    (keyword s)))

(def ^:private symbol-coord-fields
  "Coord fields tools.deps requires to hold SYMBOLS. JSON has no symbol type,
   so an MCP-supplied coord spells them as strings. Grow this list rather than
   coercing at a call site — the point is that one place knows the types."
  [:exclusions])

(defn ^:export canonical-coord
  "Canonical spelling of a dependency COORD — the fields tools.deps wants as
   symbols (`symbol-coord-fields`) coerced from the strings an MCP/JSON caller
   can spell. The coord twin of `canonical-platform`, and the same lesson:
   coerce where the type is KNOWN, at the boundary.

   Coercing only in the PROJECTION left the store holding strings, and a string
   exclusion does not fail where you can see it — the live hot-add tolerates it
   and `make_classpath2` dies in the first fresh JVM with a truncated stack that
   never mentions exclusions. Total and idempotent, so the fold/replay path can
   call it on anything."
  [coord]
  (reduce (fn [c f]
            (cond-> c
              (seq (get c f))
              (update f (fn [xs] (mapv #(if (string? %) (symbol %) %) xs)))))
          coord
          symbol-coord-fields))

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
   ;; NOT :absent-nil? — an empty test-edge relation is a real answer (no
   ;; module has asked for one), unlike :modules, whose absence is the
   ;; pre-module adoption marker. A store that predates this field simply has
   ;; no test-only edges, which is exactly {}.
   :module-test-edges
                 {:init {} :meta-key "module-test-edges"
                  :doc "module → #{dep modules its -test namespaces may cross}; production may not, and a test edge is never a production edge (so never a cycle)"}
   :module-tiers {:init {} :meta-key "module-tiers"
                  :normalize (fn [tiers]
                               (into {} (map (fn [[m t]] [m (canonical-tier t)])) tiers))
                  :doc "module → purity tier, canonical spellings only (D9)"}
:module-platforms {:init {} :meta-key "module-platforms"
                      :normalize (fn [pfs]
                                   (into {} (map (fn [[m p]] [m (canonical-platform p)])) pfs))
                      :doc "module → target platform :jvm/:cljc/:cljs (default :jvm, D-web-cljs)"}
:module-roles {:init {} :meta-key "module-roles"
                  :normalize (fn [rs]
                               (into {} (map (fn [[m r]] [m (canonical-role r)])) rs))
                  :doc "module → :product (default — the system runs it, and it ships) or :instrument (a HUMAN runs it by hand: materialized OUTSIDE src/, so it never reaches the jar — R5)"}
:client-deps {:init {} :meta-key "client-deps"
                      :doc "lib → coord for BUILD-ONLY deps (the cljs compiler): routed to the :cljs alias, never hot-loaded into the oracle or shipped in the jar (D-web-cljs)"}
:js-deps {:init {} :meta-key "js-deps"
             :doc "name → {:version :format :global :file :sha :source-url :license} for VENDORED JavaScript: the bytes live in :files/:blobs, this is the declaration the cljs compiler and the page shell read"}
:artifacts {:init {} :meta-key "artifacts"
               :doc "path → {:sha :bytes :content-type :recipe} for DERIVED files — downloaded or generated. NO bytes: the sha says what the file must be and the recipe says how to get it back, so the journal stays small and a cache miss is recoverable rather than fatal. Authored files go in :files"}
   :files        {:init {} :meta-key "files"
                  :doc "path → text, or {:sha :bytes :content-type} for binary (bytes live in :blobs)"}
   :config       {:init {} :meta-key "config"
                  :doc "path → {:format :values {key value}} structured config (G9)"}
   :blobs        {:init {} :storage :table
                  :doc "sha256 → bytes, content-addressed (assets); merge = union, cleanup prunes"}})

(def op-registry
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
                          (if (= :remove (:action d))
                            (update st :module-tiers dissoc (:module d))
                            (assoc-in st [:module-tiers (:module d)]
                                      (canonical-tier (:tier d)))))
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
   ;; the TEST-ONLY twin, a separate relation rather than a flag inside
   ;; :modules — a module may declare that its fixtures cross an edge its
   ;; production code may not, and :modules must keep meaning production
   ;; edges alone because the cycle check, the layer view, store/module-path
   ;; and the projected `modules` file are all that graph. Identical
   ;; edge-grain CRDT, so it merges by the same union and needs no new
   ;; conflict story.
   :module-test-edge
   {:field :module-test-edges :merge :bespoke
    :fold (fn [st d]
            (if (= :remove (:action d))
              (let [deps (disj (get-in st [:module-test-edges (:from d)] #{})
                               (:to d))]
                (if (empty? deps)
                  (update st :module-test-edges dissoc (:from d))
                  (assoc-in st [:module-test-edges (:from d)] deps)))
              (update-in st [:module-test-edges (:from d)]
                         (fnil conj #{}) (:to d))))
    :sample {:op :module-test-edge :from "sample.app" :to "sample.lib"
             :action :add}
    :crossed (fn [st] (contains? (get-in st [:module-test-edges "sample.app"] #{})
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
                          (if (= :remove (:action d))
                            (update st :module-platforms dissoc (:module d))
                            (assoc-in st [:module-platforms (:module d)]
                                      (canonical-platform (:platform d)))))
                  :sample {:op :module-platform :module "sample.mod" :platform :cljs}
                  :crossed (fn [st] (= :cljs (get-in st [:module-platforms "sample.mod"])))}
:module-role {:field :module-roles :merge :replay
                 :fold (fn [st d]
                         (if (= :remove (:action d))
                           (update st :module-roles dissoc (:module d))
                           (assoc-in st [:module-roles (:module d)]
                                     (canonical-role (:role d)))))
                 :sample {:op :module-role :module "sample.mod" :role :instrument}
                 :crossed (fn [st] (= :instrument (get-in st [:module-roles "sample.mod"])))}
:client-dep-add {:field :client-deps :merge :replay
                  :fold (fn [st d]
                          (assoc-in st [:client-deps (:lib d)] (:coord d)))
                  :sample {:op :client-dep-add :lib 'sample/cljs :coord {:mvn/version "1.0.0"}}
                  :crossed (fn [st] (= {:mvn/version "1.0.0"} (get-in st [:client-deps 'sample/cljs])))}
:js-dep {:field :js-deps :merge :replay
            ;; name-grained like :module-edge, so two lines declaring different
            ;; libraries union rather than one clobbering the other
            :fold (fn [st d]
                    (if (= :remove (:action d))
                      (update st :js-deps dissoc (:name d))
                      (assoc-in st [:js-deps (:name d)] (:spec d))))
            :sample {:op :js-dep :name "sample-js"
                     :spec {:version "1.0.0" :format :iife :global "sampleJs"
                            :file "public/js/sample-js-1.0.0.js"}}
            :crossed (fn [st] (= "1.0.0" (get-in st [:js-deps "sample-js" :version])))}
:artifact-put {:field :artifacts :merge :replay
                  ;; the delta carries the SHA and the RECIPE, never the bytes —
                  ;; that is the whole point of the field existing
                  :fold (fn [st d]
                          (if (= :remove (:action d))
                            (update st :artifacts dissoc (:path d))
                            (assoc-in st [:artifacts (:path d)] (:entry d))))
                  :sample {:op :artifact-put :path "public/sample.js"
                           :entry {:sha "abc123" :bytes 12
                                   :recipe {:kind :build :tool "compile_client"}}}
                  :crossed (fn [st] (= "abc123" (get-in st [:artifacts "public/sample.js" :sha])))}
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
  it to exclude markers from the host's code-delta count (review host-F2).

  `:observe` is the second EVIDENCE citizen beside `:verify` — *these tests
  ran and this is what happened*, which a verification is not. It is
  bookkeeping for the same reason `:verify` is: it changes no code, so a host
  that has not loaded one is not behind."
  #{:verify :observe :done :merge :turn-begin :turn-end :commit :revert})

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
