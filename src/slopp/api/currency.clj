(ns slopp.api.currency
  "Is the running image the code the store describes? — answered by COMPARISON.

  `slopp.image.currency` records what each door into the image actually
  loaded. This reads that record against the store and names every form the
  image does not hold as the store describes it.

  It replaces a counter, and the difference is the whole point. A counter of
  reload failures can only observe *did a reload attempt throw*, which is
  wrong in both directions:

  - **It misses silent divergence.** Nothing threw and the image is stale
    anyway — a `def` whose value was captured from a form since re-evaluated,
    var metadata that captured a schema's value, a namespace written to the
    store and never loaded at all. Every one of those was green on every
    check slopp had.
  - **It invents staleness that is not there.** A reload that failed on a
    namespace the image nonetheless holds correctly was reported as \"the host
    still runs their previous code\", which cost a milestone a fresh JVM to
    work around a problem that did not exist.

  Three kinds, and they are ordered by how hard they were to see:

  - `:never-loaded` — the store has this form and the image has no record of
    ever loading it.
  - `:superseded` — the image loaded a different source than the store now
    holds.
  - `:derived-stale` — the form's own source is current, and its VALUE is not.
    Its value or its metadata was computed at load time from a form that has
    been re-evaluated since. This is the class no source comparison can see,
    and the one that published a stale API contract while every check was
    green.

  The analysis is PURE over (store, stamps). The registry is the only
  stateful part, deliberately, so the cases worth testing can be stated as
  data instead of staged in a live process."
  (:require [slopp.store :as store]
            [slopp.edit.refs :as refs]
            [slopp.image.currency :as currency]
            [rewrite-clj.node :as n]))

(defn- captures-at-load?
  "Does evaluating this form compute a VALUE that then stops tracking its
  inputs?

  Two shapes do, and between them they are the whole `:derived-stale` class:

  - `def` / `defonce` — the init expression runs once, at load. `(def tools
    (vec (concat env-tools [:b])))` holds whatever `env-tools` was THEN.
  - any form whose name carries metadata containing a SYMBOL — var metadata is
    evaluated, so `^{:web/response schema}` captured the schema's value and
    the name `schema` no longer exists at runtime.

  A `defn` body is deliberately NOT included, and that exclusion is what makes
  this usable: a body resolves its callees through their VARS at call time, so
  re-evaluating a callee is picked up for free. Without the distinction every
  ordinary write would report half the namespace stale, which is a warning
  nobody reads.

  A node that cannot be read as a sexpr (a reader conditional, a tagged
  literal) is treated as non-capturing rather than as an error: this is an
  advisory analysis, and refusing to answer would be worse than under-reporting
  a shape that is rare in the first place."
  [node]
  (let [sexpr (try (n/sexpr node) (catch Throwable _ nil))]
    (boolean
     (and (seq? sexpr)
          (or (contains? '#{def defonce} (first sexpr))
              (some symbol? (tree-seq coll? seq (vals (meta (second sexpr))))))))))

(defn drift-of
  "Every form the image does not hold as the store describes it — pure over a
  store value and a stamp map (`slopp.image.currency/snapshot`).

  Returns rows of `{:ns :form :why}`, plus `:behind` naming the form whose
  re-evaluation left this one holding an old value. `:why` is
  `:never-loaded`, `:superseded` or `:derived-stale`.

  `:cljs` namespaces are skipped: they are never loaded into the JVM oracle by
  design, so calling them absent would be reporting a deliberate rule as a
  fault.

  A form that already drifted by SOURCE is not also reported as derived-stale.
  Reloading it fixes both, and two rows for one form invites fixing it twice.

  Faithful, not defensive: given an empty stamp map it reports the whole store
  as `:never-loaded`, because that is what an empty record means. Callers that
  cannot distinguish \"no image\" from \"empty image\" — a test JVM, a process
  that never booted from a store — must not ask; `host-warning-now` gates on
  the kernel's boot record for exactly that reason."
  [store stamps]
  (let [els   (vec (for [ns-sym (sort (keys (:namespaces store)))
                         :when (store/jvm-loadable? store ns-sym)
                         e (store/elements store ns-sym)]
                     (assoc e :ns ns-sym)))
        source (fn [e] (currency/hash-of (n/string (:node e))))
        moved  (vec (for [e els
                          :let [got (get stamps (:id e))]
                          :when (or (nil? got) (not= (source e) (:hash got)))]
                      {:ns (:ns e) :form (:name e)
                       :why (if got :superseded :never-loaded)}))
        moved? (set (map (juxt :ns :form) moved))
        cap    (into {} (for [e els
                              :when (and (get stamps (:id e))
                                         (captures-at-load? (:node e)))]
                          [(:id e) e]))
        behind (vec (distinct
                     (for [r  (refs/refs store)
                           :let [g  (get cap (:from-form r))
                                 gs (get-in stamps [(:from-form r) :seq])
                                 fs (get-in stamps [(:to-form r) :seq])]
                           :when (and g gs fs (> fs gs)
                                      (not (moved? [(:ns g) (:name g)])))]
                       {:ns (:ns g) :form (:name g)
                        :why :derived-stale :behind (:to-name r)})))]
    (into moved behind)))

(defn ^:export drift
  "`drift-of` against the LIVE image's registry — what this process is actually
  holding, compared to what the store says. **nil** when the registry has not
  been armed, i.e. when nothing has measured this image.

  That nil travels: `host-brief` and `host-warning` read it as \"not looked\"
  and keep their cautious wording, rather than treating an absent record as a
  clean bill of health. An empty vector — armed, and nothing drifted — is the
  positive claim, and only a loader that stamped what it loaded can produce
  it."
  [store]
  (when-let [stamps (currency/snapshot)]
    (drift-of store stamps)))
