(ns slopp.api.artifacts
  "Derived files: the bytes live on disk, the store holds the sha and the
  recipe.

  `:files` is what the agent AUTHORED — versioned in the delta log, diffable,
  git-projected. `:artifacts` is what was downloaded or generated, and it
  carries no bytes at all. Measured cause: 30.47 MB of this store's journal
  was fifteen inline copies of one compiled bundle, re-appended on every
  build and carried across lines by every merge.

  The cache is content-addressed under `<dir>/.slopp/artifacts/<sha>`, so
  two paths with identical bytes cost one file and re-registering an
  unchanged artifact costs nothing.

  **A miss is recoverable, not fatal.** `resolve` returns the bytes when the
  cache has them and a RECIPE when it does not — it never shells out on its
  own. A read that silently triggers a download or a build is a surprise,
  and for a recipe like `compile_client` the caller is far better placed to
  decide than the reader is."
  (:require [clojure.java.io :as io]
            [slopp.store :as store]))

(defn- cache-dir
  "The directory `dir`'s artifacts live in.

  Split out of `cache-file` because measuring and pruning need the DIRECTORY,
  and a second expression computing the same path is how a sweep ends up
  looking somewhere the writer never wrote."
  [dir]
  (io/file (or (some-> dir str not-empty) ".") ".slopp" "artifacts"))

(defn ^:export cache-file
  "Where `sha`'s bytes live: `<dir>/.slopp/artifacts/<sha>`.

  Under `.slopp/` next to the store, so it is obviously project-local and
  obviously disposable — deleting it costs a re-fetch, never data. Keyed by
  SHA rather than by path, so identical bytes cost one file however many
  paths point at them, and re-registering an unchanged artifact is free.
  That sharing is why `prune-superseded!` checks the whole manifest before
  deleting anything.

  A nil `dir` — an in-memory session, which most of the suite uses — falls
  back to `.slopp/` relative to the working directory. It stays under a
  `.slopp` deliberately: that directory is already gitignored, and already
  where someone reclaiming space would think to check. The system temp
  directory would put artifacts somewhere nothing prunes and nothing knows
  about, which is a leak with extra steps.

  What it must NOT do is resolve to the filesystem root. `(io/file nil
  \".slopp\" sha)` yields `/.slopp/artifacts/<sha>`, which threw
  `FileNotFoundException` in three tests and would have been a permissions
  surprise anywhere it did not."
  [dir sha]
  (io/file (cache-dir dir) (str sha)))

(defn ^:export put!
  "Write `bytes` into `dir`'s cache and return the entry to register.

  The sha is computed HERE, from the bytes being written — a caller-supplied
  one would make the recorded sha a claim about the caller's intent rather
  than about the file. The returned map is exactly what
  `store/record-artifact` wants, so a producer never hand-assembles it and
  the two cannot drift.

  Writing is idempotent: the same bytes hash to the same name and rewrite
  the same file."
  [dir ^bytes bytes recipe & {:keys [content-type]}]
  (let [sha (store/sha256-of bytes)
        f   (cache-file dir sha)]
    (io/make-parents f)
    (with-open [out (io/output-stream f)]
      (.write out bytes))
    (cond-> {:sha sha :bytes (alength bytes) :recipe recipe}
      content-type (assoc :content-type content-type))))

(defn ^:export fetch
  "The bytes for artifact `path`, or how to get them back.

  `{:bytes <byte-array>}` on a verified cache hit. `{:missing path :recipe
  … :sha …}` when the cache has nothing, or has something whose sha does not
  match. Never downloads and never builds: the caller decides.

  The sha is checked on EVERY read, not only on write. A cache nothing
  verifies will eventually serve the wrong bytes without saying so, and that
  silent wrong answer is precisely what recording a sha is for. A mismatch
  reports as a miss, because the recipe is the way back either way.

  (Named `fetch` rather than `resolve`: the dialect gate denylists
  `resolve`, since references must go through carriers.)"
  [dir store path]
  (if-let [{:keys [sha recipe] :as entry} (get-in store [:artifacts (str path)])]
    (let [f (cache-file dir sha)]
      (if (.exists f)
        (let [bs (java.nio.file.Files/readAllBytes (.toPath f))]
          (if (= sha (store/sha256-of bs))
            {:bytes bs :entry entry}
            {:missing (str path) :sha sha :recipe recipe
             :why "cached bytes do not match the recorded sha"}))
        {:missing (str path) :sha sha :recipe recipe
         :why "not in the cache"}))
    {:error (str "no artifact registered at " (str path))}))

(defn ^:export cache-stats
  "What `dir`'s artifact cache costs on disk, split by whether `store` still
  points at it.

  `{:n :bytes :live {:n :bytes} :orphaned {:n :bytes}}`. Cheap — `File.length`
  only, nothing is read or hashed.

  This exists because moving bytes out of the journal does not make them
  free, it makes them UNCOUNTED, and `store_health` was built precisely
  because uncounted bytes accumulate: a tree snapshot reached 94% of a 344MB
  journal across 239 milestones with nothing measuring it. The 30MB this
  field removed from the delta log would have landed straight back in a
  directory no tool reported.

  `:orphaned` is the number worth acting on — bytes no manifest entry claims,
  which is what a crashed session or a removed artifact leaves behind. A
  missing directory reads as empty rather than as an error, because a cache
  that was never written and a cache that was cleared are the same situation."
  [dir store]
  (let [live?  (into #{} (keep :sha) (vals (:artifacts store)))
        files  (filter #(.isFile ^java.io.File %)
                       (or (.listFiles (cache-dir dir)) []))
        tally  (fn [fs] {:n     (count fs)
                         :bytes (reduce + 0 (map #(.length ^java.io.File %) fs))})
        by-use (group-by #(contains? live? (.getName ^java.io.File %)) files)]
    (merge (tally files)
           {:live     (tally (get by-use true []))
            :orphaned (tally (get by-use false []))})))

(defn ^:export prune-superseded!
  "Delete `sha`'s cache file if `store` no longer references it. Returns the
  bytes reclaimed, or 0.

  Called with the store AS IT IS AFTER a registration, and the sha the
  registration replaced. That moment is the only one where the answer is
  unambiguous: every compile leaves its predecessor behind, and fifteen of
  them is 33MB of bytes nothing will ever read again.

  It checks the WHOLE manifest, not just the path that changed, because the
  cache is content-addressed — two paths with identical bytes share one file,
  and dropping it for one would drop it for both.

  Deliberately targeted rather than a sweep of everything unreferenced. A
  sweep driven by an EMPTY store would erase a real cache, and in-memory
  sessions carrying no `:dir` fall back to the working directory's `.slopp`
  — so a broad prune wired into the ordinary path would let a test delete the
  bytes of the project it is running inside. `cache-stats` reports what is
  orphaned; reclaiming it is a decision, not a side effect."
  [dir store sha]
  (let [f (when (seq (str sha)) (cache-file dir sha))]
    (if (or (nil? f)
            (not (.exists f))
            (some #(= sha (:sha %)) (vals (:artifacts store))))
      0
      (let [n (.length f)]
        (.delete f)
        n))))

(defn ^:export refill-instruction
  "How to get `path`'s bytes back, as a sentence naming the call to make.

  `fetch` reports the recipe, which is provenance — true, but not actionable
  unless you already know how the file got there. The distance between
  `{:kind :download :npm \"roughjs@4.6.6\"}` and knowing to call `js_dep` is
  nothing to whoever wrote the recipe and everything to whoever hits the miss
  later. slopp's refusals name their next call; a miss should too.

  It does NOT run anything. Refilling a `:build` artifact is a tool call, and
  the tools require this namespace — so the instruction can live here and the
  execution cannot. That is a layering fact, not a stage: the caller is also
  much better placed to decide whether now is the moment to spend a compile.

  An unrecognised recipe reports itself rather than guessing. A refill path
  that invents a plausible wrong call is worse than one that admits it does
  not know."
  [path recipe]
  (case (:kind recipe)
    :build
    (str "run " (or (:tool recipe) "the tool that generated it")
         " to regenerate " path)

    :download
    (str "re-vendor " path ": fetch "
         (or (:npm recipe) "the package")
         (when-let [p (:npm-path recipe)] (str "'s " p))
         (when-let [i (:integrity recipe)] (str " (integrity " i ")"))
         " and re-declare it with js_dep {source <the downloaded file>}"
         " — declaring is vendoring, so one call does both")

    (str "no known way to refill " path
         (if recipe
           (str " — unrecognised recipe " (pr-str recipe))
           " — no recipe was recorded"))))
