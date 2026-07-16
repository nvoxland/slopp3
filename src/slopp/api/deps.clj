(ns slopp.api.deps
  (:require [slopp.db :as db]
            [slopp.deps :as deps]))

(defn analyze-dep!
  "Compute (or reuse the cached) API surface for `lib`@`coord` (M4) —
  best-effort: surface analysis must never fail a deps-add. Persists to the
  durable `dep_surface` cache when the session has a db; the process-level
  memo in `slopp.deps` covers ephemeral sessions. Returns the surface or nil."
  [session lib coord]
  (try
    (let [conn (:db @session)
          id   (deps/coord-key lib coord)]
      (if-let [cached (some-> conn (db/get-dep-surface id))]
        cached
        (let [jars (deps/dep-jars lib coord)                  ; resolve once
              surf (deps/surface jars)]
          (when conn
            (db/put-dep-surface! conn id surf)
            (db/put-dep-native! conn id (deps/native-verdict jars)))  ; M6
          surf)))
    (catch Throwable _ nil)))

(defn dep-native-verdict
  "The cached (or freshly-computed) GraalVM native-image verdict for a
  dependency (M6). Best-effort; nil on failure."
  [session lib coord]
  (let [conn (:db @session)
        id   (deps/coord-key lib coord)]
    (or (some-> conn (db/get-dep-native id))
        (try (deps/native-verdict (deps/dep-jars lib coord))
             (catch Throwable _ nil)))))

(def native-incompatible-deps
  "Dependencies KNOWN to break GraalVM native-image (extensible, deliberately
  tiny — a build refuses these without `:force`). Empty for now; a missing
  reachability manifest is only a WARN, not a hard incompatibility."
  #{})
