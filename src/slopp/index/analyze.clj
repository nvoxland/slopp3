(ns slopp.index.analyze
  (:require [clj-kondo.core :as kondo]
            [slopp.cache :as cache]
            [slopp.index.derive :as derive]))

^:reads (defn ^:export analyze-with-locals
  "Like `analyze`, but including local-binding definitions and usages
  (`:locals` / `:local-usages`, linked by `:id`) — the basis for free-variable
  computation in structural extraction."
  [source]
  (:analysis
   (with-in-str source
     (kondo/run! {:lint ["-"]
                  :config {:analysis {:locals true} :output {:analysis true}}}))))

^:reads (defn ^:export analysis-pass
  "ONE kondo run over `source` for its `:analysis` ONLY, with the cache
  explicitly OFF — memoized on source, which is the honest key.

  Deliberately NOT the same pass as `kondo-pass`. The two want different
  worlds: `:findings` depend on cross-namespace facts (which is why they
  carry `:cache-dir` and `:fp`), while `:analysis` is a function of source
  alone. Sharing one cached pass made analysis IO, and IO in `analyze` is
  inherited by every namespace that calls it — the whole pure core reached
  through here.

  Measured before splitting: a warm-cache run and a `:cache false` run differ
  ONLY in `:fixed-arities` on cross-namespace var-USAGES. Nothing in slopp
  reads that — every arity reader (`query-outline`, `deps/surface`,
  `build/arg-style`) takes it from var-DEFINITIONS, which are same-source and
  unaffected. The cost is a second kondo run for sources that also get
  linted; the benefit is that the core layers."
  [source]
  (cache/cached ::analysis source
                (fn []
                  (:analysis
                   (with-in-str source
                     (kondo/run! {:lint ["-"]
                                  :config (assoc derive/kondo-config :cache false)}))))))

^:reads (defn ^:export analyze
  "clj-kondo's `:analysis` ({:var-definitions :var-usages
  :namespace-definitions :namespace-usages}) for `source`, plus `:var-quotes`
  (the `#'var` carrier positions) so the effect `call-graph` can tell a var HELD
  in data from a var CALLED.

  Runs its OWN cache-free pass (`analysis-pass`) rather than sharing
  `lint`'s. Analysis is a function of source alone; findings are not, and
  sharing the cached pass made every caller of `analyze` — which is most of
  the pure core — depend on kondo's on-disk cache."
  [source]
  (assoc (analysis-pass source)
         :var-quotes (derive/var-quote-positions source)))
