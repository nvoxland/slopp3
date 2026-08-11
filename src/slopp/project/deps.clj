(ns slopp.project.deps
  "What a DEPENDENCY brings, cached: its public API surface, and its GraalVM
  native-image verdict.

  Both answers are expensive (resolve the coord, open the jars, walk them) and
  neither changes for a given `lib`@`coord`, so both memoize into the store's
  durable dep caches; the process-level memo in `slopp.index.deps` covers
  ephemeral sessions that have no db.

  **Every read here is best-effort and returns nil on failure — that is a
  stance, not missing error handling.** Surface analysis exists to make a
  `deps_add` more informative, so a dependency whose jars will not open must
  still be addable; failing the add would trade a real capability for a
  cosmetic one. The single place a verdict is allowed to BLOCK is a build, and
  only against `native-incompatible-deps` — which is empty, because a missing
  reachability manifest is a WARN and not an incompatibility."
  (:require [slopp.store.db :as db]
            [slopp.index.deps :as index.deps]))

(defn ^:export analyze-dep!
  "Compute (or reuse the cached) API surface for `lib`@`coord` (M4) —
  best-effort: surface analysis must never fail a deps-add. Persists to the
  durable `dep_surface` cache when the session has a db; the process-level
  memo in `slopp.deps` covers ephemeral sessions. Returns the surface or nil."
  [session lib coord]
  (try
    (let [conn (:db @session)
          id   (index.deps/coord-key lib coord)]
      (if-let [cached (some-> conn (db/get-dep-surface id))]
        cached
        (let [jars (index.deps/dep-jars lib coord)                  ; resolve once
              surf (index.deps/surface jars)]
          (when conn
            (db/put-dep-surface! conn id surf)
            (db/put-dep-native! conn id (index.deps/native-verdict jars)))  ; M6
          surf)))
    (catch Throwable _ nil)))

(defn ^:export dep-native-verdict
  "The cached (or freshly-computed) GraalVM native-image verdict for a
  dependency (M6). Best-effort; nil on failure."
  [session lib coord]
  (let [conn (:db @session)
        id   (index.deps/coord-key lib coord)]
    (or (some-> conn (db/get-dep-native id))
        (try (index.deps/native-verdict (index.deps/dep-jars lib coord))
             (catch Throwable _ nil)))))

(def ^:export native-incompatible-deps
  "Dependencies KNOWN to break GraalVM native-image (extensible, deliberately
  tiny — a build refuses these without `:force`). Empty for now; a missing
  reachability manifest is only a WARN, not a hard incompatibility."
  #{})
