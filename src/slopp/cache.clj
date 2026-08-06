(ns slopp.cache
  "THE memo. Every cache in a slopp store goes through `cached` or
  `cached-last`, and a hand-rolled atom is the thing this namespace exists to
  make unnecessary.

  Not because one atom is tidier than five. Because a hand-rolled memo is
  **invisible**: nothing can clear it between tests, nothing can say what the
  process is holding, and nothing can tell the tier gate that mutating it is
  an internal optimization rather than an effect on the world. That last one
  is the load-bearing part — \"is my memo semantically transparent?\" is an
  author's claim no tool can check, while \"does this write go through
  `slopp.cache`?\" is decidable. Measured on slopp before this existed: five
  hand-rolled caches across three namespaces, three different shapes, and an
  eviction policy buried in a `swap!`.

  What being visible buys, concretely:

  - **`without-caching!`** makes every call recompute, so a test proves the
    computation instead of the cache. A cached fn tested normally may be
    answering from an earlier test entirely.
  - **`reset-all!`** and **`registry`** turn \"what is this process holding?\"
    into a read.
  - **The key is a VALUE you pass**, so every term the result depends on sits
    in one place. Staleness is always an omitted term — slopp's own lint memo
    served findings computed under an old linter config until the config hash
    joined its key.

  Two strategies, and the second is not a stylistic variant. `cached` keys on
  VALUE. `cached-last` keys on IDENTITY and holds one entry, for keys too
  large to hash — the whole-store reference graph is memoized on the store
  itself, which is immutable, so same identity means same content by
  construction. Identity keying is WRONG for anything rebuilt per call: two
  `=` values that are not `identical?` miss every time and the cache silently
  never hits.

  `without-caching!` takes a thunk rather than wrapping a body because the
  dialect bans user macros (D4).")

(def ^:private ^{:ambient-ok "the blessed cache registry itself — one visible atom is exactly what lets without-caching reset it, tests count it, and the tier gate read caching as an internal optimization rather than an effect on the world"} caches
  "Every blessed cache, `{cache-id {key value}}`.

  ONE atom instead of one per memo. That is the whole point: a hand-rolled
  memo is invisible — nothing can reset it between tests, count it, or tell
  the tier gate that it is an internal optimization rather than an effect on
  the world. Measured on slopp before this existed: five hand-rolled caches
  across three namespaces, three different shapes, and one hand-rolled
  eviction policy buried in a `swap!`."
  (atom {}))

(def ^:private ^{:ambient-ok "the dial without-caching binds, so a test can prove the computation rather than prove the cache answering from an earlier call"} bypass?
  "When true, `cached` computes every time and stores nothing.

  A cache can hide a bug by answering from an earlier call, so a test that
  exercises a cached fn may be proving the CACHE rather than the computation.
  `without-caching` binds this for exactly that reason."
  (atom false))

(defn cached
  "Return the value for `key` in cache `cache-id`, computing it with `thunk`
  and storing it on a miss. THE blessed memo — every cache in a slopp store
  goes through this call.

  Why one construct rather than a hand-rolled atom per memo:

  - **Testable.** `reset-all!` clears every cache; `without-caching` makes
    every call recompute. A hand-rolled memo can serve one test an answer
    computed during another, so the test proves the cache, not the code.
  - **Mechanically recognizable.** The tier gate can tell that mutating THIS
    is an internal optimization rather than an effect on the world. That is
    a decidable question; \"is my memo semantically transparent?\" is an
    author's claim nothing can check.
  - **One eviction policy.** `:max` lives here instead of being hand-rolled
    per site (slopp's kondo memo cleared itself at 256 inside a `swap!`).
  - **The key is a VALUE you pass.** Staleness comes from a key that omits
    something the result depends on — slopp's lint memo silently served
    findings computed under an old linter config until the config hash was
    added to its key. Passing the key explicitly puts every term in one
    place instead of spreading them across comparison sites.

  `key` must be a value that is `=`-comparable and covers everything the
  result depends on."
  [cache-id key thunk]
  (if @bypass?
    (thunk)
    (let [hit (get-in @caches [cache-id key] ::miss)]
      (if (not= ::miss hit)
        hit
        (let [v (thunk)]
          (swap! caches update cache-id
                 (fn [m] (assoc (if (>= (count m) 512) {} m) key v)))
          v)))))

(defn reset-all!
  "Drop every cached entry. Returns nil.

  For test setup: a hand-rolled memo can serve one test a value computed
  during another, which is a test passing for a reason that has nothing to do
  with the code under test. Call this between tests that exercise cached
  computations."
  []
  (reset! caches {})
  nil)

(defn without-caching!
  "Call `thunk` with caching bypassed: every `cached` call recomputes and
  stores nothing. Returns the thunk's value.

  A function is NOT a macro here because slopp's dialect bans user macros
  (D4), so this takes a thunk rather than wrapping a body.

  The reason to reach for it: a cached computation tested normally may be
  answering from an earlier call, so the test proves the CACHE. Under this,
  the computation runs every time and a stale-key bug shows up as a wrong
  answer instead of hiding behind a hit."
  [thunk]
  (reset! bypass? true)
  (try (thunk)
       (finally (reset! bypass? false))))

(defn registry
  "`{cache-id entry-count}` for every live cache.

  A hand-rolled memo is invisible: nothing can say what the process is
  holding or which cache is growing. This makes the answer a read."
  []
  (into {} (map (fn [[k m]] [k (count m)])) @caches))

(defn cached-last
  "Memoize-LAST for `cache-id`, keyed on the IDENTITY of `key` (`identical?`),
  not its value. Holds one entry; a different identity replaces it.

  The second blessed strategy, and it is not a stylistic variant of `cached`.
  Some keys are too large to hash: slopp's whole-store reference graph is
  memoized on the STORE, and hashing that map on every call would cost more
  than the computation it saves. The store is immutable, so a new value
  appears only on a write — same identity means same content by construction,
  which makes identity a SOUND key here rather than a shortcut.

  Use `cached` unless the key is large and known-immutable. Identity keying is
  wrong for anything rebuilt per call: two `=` values that are not
  `identical?` will miss every time, so the cache silently never hits.

  A benign race under concurrent lines costs at most a rebuild, never a wrong
  answer."
  [cache-id key thunk]
  (if @bypass?
    (thunk)
    (let [[k v :as entry] (get @caches cache-id)]
      (if (and entry (identical? key k))
        v
        (let [v' (thunk)]
          (swap! caches assoc cache-id [key v'])
          v')))))
