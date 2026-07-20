(ns slopp.index
  "Static semantic index over rendered source via clj-kondo (content-fed through
  stdin — no disk, C1/C6): var definitions, references, the call graph, and the
  `!`-effect analysis (D6).

  `!`-effect (D6): a var is effectful iff it can transitively reach a known
  effectful primitive through the call graph — a sound, decidable check for
  first-order code (higher-order/effect-polymorphic cases are left to the live
  oracle, D5). The dialect requires the `!` in a var's name to match its computed
  effectfulness; mismatches are reported for the edit pipeline to flag/auto-fix."
  (:require [clj-kondo.core :as kondo] [slopp.index.derive :as derive]))

(def ^:private ^:ambient-ok kondo-cache
  "source-string -> {:analysis :findings} memo (bounded). ONE kondo pass
  per unique rendered ns feeds both analyze and lint — every write used
  to run kondo several times over identical source (pre/post warnings,
  affected lookups, AND a separate un-memoized lint pass over the
  unchanged base). Eval round 2 + the post-eval9 wall trace both showed
  these re-runs dominating per-write time on large namespaces."
  (atom {}))

(def ^:private ^:ambient-ok ns-source-hash
  "ns-sym -> hash of the last source we fed kondo for it.

  clj-kondo reads CROSS-NS facts (arities, var existence) from
  `.clj-kondo/.cache`, and WRITES that cache as a side effect of every lint —
  so a source's FINDINGS are not a function of that source alone, and memoizing
  them on source alone replays answers from before a callee moved. (Measured
  2026-07-16: `:analysis` IS cache-independent; only `:findings` are not.)

  This is the missing half of the key. Direct requires suffice: a dep's cache
  entry holds ITS OWN arities, which don't change when the dep's own deps do."
  (atom {}))
(defn- deps-fp
  "Fingerprint of what kondo currently knows about `requires` — the cross-ns
  half of `run-kondo`'s memo key. A dep we have never linted contributes nil
  (stable), which is the honest answer: its `.clj-kondo/.cache` entry, if any,
  predates this process and we cannot account for it."
  [requires]
  (let [known @ns-source-hash]
    (mapv known (sort requires))))
(def ^:ambient-ok kondo-cache-dir
  "Where kondo keeps its CROSS-NS facts (arities, var existence), or nil for
  kondo's own default.

  The default is the killer, which is why this exists: kondo resolves its cache
  from the PROCESS CWD. Probed 2026-07-17 from a cwd with no `.clj-kondo/` —
  exactly a user project's situation — the same source that yields
  `[:invalid-arity]` against this repo's cache yields `[]`. So every cross-ns
  finding worked only because slopp's own repo happens to have a `.clj-kondo/`
  beside it, and a user's `:carried` stale-caller gate silently did nothing,
  failing toward 'no findings'.

  `api/open!` points this at the store's own `.slopp/` so the facts live with
  the code they describe. A slopp-owned dir is self-sufficient — linting a
  namespace teaches it, which is how any kondo cache fills."
  (atom nil))
^:reads (defn- kondo-pass
  "ONE real kondo run over `source`, stored. Also records what this pass just
  taught kondo's cache about `source`'s OWN namespace — that side effect is
  what dependents' fingerprints read, so it must be tracked wherever a pass
  actually happens.

  Runs against `kondo-cache-dir` when set (#134). Unset means kondo resolves
  the cache from the process CWD, which is how cross-ns findings came to
  depend on a `.clj-kondo/` happening to sit next to the process."
  [source]
  (let [dir (some-> @kondo-cache-dir str)
        r (with-in-str source
            (kondo/run! (cond-> {:lint ["-"] :config derive/kondo-config}
                          dir (assoc :cache-dir dir))))
        a (:analysis r)
        requires (into #{} (map :to) (:namespace-usages a))
        own      (-> a :namespace-definitions first :name)
        v {:analysis a
           :ns       own
           :requires requires
           ;; the cross-ns state these FINDINGS are true under: WHICH cache,
           ;; and what it knew about this source's requires
           :cache-dir dir
           ;; the CONFIG is part of the world these findings are true under:
           ;; change a linter level and every memoized entry is stale. Without
           ;; this, editing kondo-config silently did nothing until restart.
           :cfg      (hash derive/kondo-config)
           :fp       (deps-fp requires)
           :findings (->> (:findings r)
                          (filter #(#{:warning :error} (:level %)))
                          (mapv #(select-keys % [:level :type :message :row :col])))}]
    (when own
      (swap! ns-source-hash assoc own (hash source)))
    (swap! kondo-cache (fn [c] (assoc (if (>= (count c) 256) {} c) source v)))
    v))
^:reads (defn- run-kondo
  "The memoized kondo pass: {:analysis ... :findings ...} for `source`, keyed
  on SOURCE — the honest key for `:analysis`, which is cache-INDEPENDENT
  (measured 2026-07-16: byte-identical with and without `.clj-kondo/.cache`).

  It must stay source-keyed: `edit.refs/static-refs` maps `analyze` over EVERY
  namespace, so keying analysis on the cross-ns fingerprint would re-analyze
  every dependent of any edited namespace — a pass costs ~285ms on a large one.

  `:findings` need a stricter key and get it in `lint`; reading them straight
  off this entry is what made the lint gate stale."
  [source]
  (or (get @kondo-cache source)
      (kondo-pass source)))
^:reads (defn lint
  "clj-kondo FINDINGS for `source` (warnings + errors: [{:level :type :message
  :row :col}]).

  Findings are NOT a function of `source` alone — kondo reads cross-ns facts
  (arities, var existence) from its cache, which other lints rewrite. So the
  memoized entry is only reusable while the world it was computed under still
  holds, which is three things:
  - the same CACHE (`:cache-dir`) — a different cache is a different world
    (#134);
  - the same CONFIG (`:cfg`) — a linter level change makes every prior
    finding stale, and without this term it silently did nothing until
    restart;
  - the same knowledge of this source's requires (`:fp` vs `deps-fp`);
  - a disk cache still reflecting this source's own namespace — a memo HIT
    skips the pass, and the pass is the side effect that teaches the cache.

  Any mismatch re-passes. `analyze` deliberately keeps the source-only key:
  `:analysis` is cache-INDEPENDENT (measured 2026-07-16) and `edit.refs`
  maps it over EVERY namespace."
  [source]
  (let [ent (run-kondo source)]
    (:findings
     (if (and (= (:cache-dir ent) (some-> @kondo-cache-dir str))
              (= (:cfg ent) (hash derive/kondo-config))
              (= (:fp ent) (deps-fp (:requires ent)))
              (= (get @ns-source-hash (:ns ent)) (hash source)))
       ent
       (kondo-pass source)))))

