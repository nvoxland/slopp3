(ns slopp.image
  "Bridge the store to the owned live image: load a namespace's forms straight
  from the CRDT into the running JVM (no disk, C1), and run its tests there,
  recording the green/red result as provenance (D5/D6, C4)."
  (:require [slopp.store.render :as render]
            [slopp.image.repl :as repl]
            [slopp.store :as store] [slopp.kernel.rt :as rt] [slopp.image.currency :as currency] [rewrite-clj.node :as n]))

(defn load-ns-into!
  "Evaluate `ns-sym`'s current source (rendered from the store) into `handle`
  and mark it in `*loaded-libs*`. Returns the compile error, or nil.

  The LOADING half of `load-ns!`, without the currency stamp — for an image
  that is not the oracle.

  `slopp.image.currency` is a single process-global registry answering \"does
  the image hold this form's current source\", and the image it means is THE
  oracle. `webdev.live` boots a second image on purpose (the oracle is
  cycled by ordinary editing, so it cannot host a running app), and stamping
  that image's loads would file them as the oracle's: every currency surface
  would then report forms as current in a process that never saw them.

  So the stamp is not incidental to loading, and neither is skipping it. If
  the registry ever becomes per-image, these two collapse back into one
  function and this docstring should go with them.

  Marks `*loaded-libs*` for the same reason `load-ns!` does: store
  namespaces have no classpath presence, so without it a later `:require`
  from another store namespace hits the classpath and fails.

  Skips a `:cljs` namespace (never loadable into a JVM) and returns nil."
  [handle store ns-sym]
  (when (store/jvm-loadable? store ns-sym)
    (let [res (repl/load-checked! handle
                                  (render/render-ns store ns-sym)
                                  (render/ns-path ns-sym (store/platform-for store ns-sym)))]
      (repl/eval! handle
                  (format "(dosync (commute (deref #'clojure.core/*loaded-libs*) conj '%s))"
                          ns-sym))
      (:err res))))

(defn load-ns!
  "Evaluate `ns-sym`'s current source into THE ORACLE and stamp it.

  `load-ns-into!` does the loading; this adds the `slopp.image.currency`
  stamp, which is the only difference and is oracle-specific — the registry
  is one process-global record of what the oracle holds. Use `load-ns-into!`
  for any other image.

  Stamps every form it loaded, the whole-namespace counterpart to
  `hot-load-form!`'s per-form stamp. Only on success: a namespace that failed
  to compile is not in the image. The `jvm-loadable?` guard is kept HERE as
  well as inside `load-ns-into!` — without it a skipped `:cljs` namespace
  would come back nil, read as success, and stamp forms the image never saw.

  There are THREE doors, not two. This one, `hot-load-form!`, and
  `api/ingest!` — which renders and calls `repl/load-checked!` itself rather
  than coming through here. This docstring used to say \"those two are the
  only doors\", which is how ingest went unstamped: an enumeration that reads
  as complete stops anyone counting. Every ingested namespace therefore
  reported as `:never-loaded` for the life of the session, and nothing
  derived from one could be judged stale at all.

  A door into the ORACLE that does not stamp reports its whole namespace as
  never-loaded, so if you add a fourth, stamp there too. A door into another
  image must NOT stamp — that is `load-ns-into!`, and the distinction is the
  image, not the caller's convenience."
  [handle store ns-sym]
  (when (store/jvm-loadable? store ns-sym)
    (let [err (load-ns-into! handle store ns-sym)]
      (when-not err
        (doseq [e (store/elements store ns-sym)]
          (currency/stamp! (:id e) (n/string (:node e)))))
      err)))

^:reads (defn test-run
  "Run `ns-sym`'s clojure.test tests in the live image; returns the summary map
  ({:test :pass :fail :error :type})."
  [handle ns-sym]
  (first (repl/eval! handle (format "(clojure.test/run-tests '%s)" ns-sym))))

(defn- drain-child-rt!
  "Move what slopp.kernel.rt did IN `handle`'s child onto whoever is tracing us (#126).

  rt is the only slopp code that executes in a child image — `repl/inject-rt!`
  is the sole place slopp code is evaled in, and the child otherwise loads its
  own store. So its calls are invisible to a caller that only wraps its OWN
  vars: measured 2026-07-17, slopp.kernel.rt/traced-run read 0 covering tests while 213
  exercised it from here.

  No sink means nobody is tracing — the MCP server's own images, overwhelmingly
  — and then this costs nothing at all, not even the round-trip. Resolve rather
  than call: an older rt (a lagging uberjar) has no drain, and a missing drain
  must degrade to no evidence, never to an error."
  [handle]
  (when-let [sink @rt/touched-sink]
    (when-let [syms (first (repl/eval!
                            handle
                            "(when-let [d (resolve 'slopp.kernel.rt/drain-self!)] (d))"))]
      (swap! sink into syms))))

^:reads (defn traced-test-run
  "Run `test-ns`'s tests in the image with form-tracing (slopp.kernel.rt): the fn
  vars of `test-ns`'s dependency CLOSURE are observed (item 2 — not every
  store namespace), so the result maps each test to the forms it exercised.
  `only` (a coll of plain test names) restricts which tests run. `test-ns`
  may be a collection — whole-project verification in ONE eval (F-3c1).
  Returns {:summary {...} :trace {test-sym #{form-sym ...}}}.

  **Non-JVM namespaces are dropped here, and this is load-bearing.** The image
  holds no `:cljs` namespace, and BOTH halves of the tracer walk `ns-interns`
  over what they are handed — `slopp.kernel.rt/traced-run` over the test namespaces,
  `slopp.kernel.rt/instrument!` over the targets. `ns-interns` THROWS on a namespace
  that does not exist, lazily, inside the run: the throw crosses the eval
  boundary as text, the caller destructures a nil `:summary`, and its `cond->`
  builds a map with no counts in it. One empty `:cljs` namespace was therefore
  enough to silence the entire in-image tier of `test_run {all true}` and
  `full_check` — reported green, having run nothing, in every store with client
  code. Filtered HERE because this is the layer holding the store, which is the
  only thing that knows a namespace's platform.

  Ships the closure's defmethod registrations along (#129,
  `store/method-registrations`) so a dispatched call records the METHOD's own
  form key, not just the multi's — the store is the only thing that knows
  which dispatch value lives in which form.

  Also drains what rt itself did in the child onto whoever is tracing US (#126)
  — this call is where `slopp.kernel.rt/traced-run` actually executes, so it is the
  only place that evidence can come from."
  [handle store test-ns & {:keys [only skip-integration?]}]
  (let [in-image? #(and (contains? (:namespaces store) %)
                        (not= :cljs (store/platform-for store %)))
        nses      (filterv in-image? (if (coll? test-ns) test-ns [test-ns]))
        targets   (into #{} (comp (mapcat #(store/ns-closure store %))
                                 (filter in-image?))
                        nses)
        methods   (vec (mapcat #(store/method-registrations store %) targets))]
    (if (empty? nses)
      ;; nothing in scope the image can hold. Zero tests is the honest answer
      ;; and evaling would be the dishonest one: `traced-run` would be handed
      ;; nil and throw the very exception this filter exists to prevent.
      {:summary {:test 0 :pass 0 :fail 0 :error 0 :type :summary} :trace {}}
      (let [result (first (repl/eval! handle
                                      (format "(slopp.kernel.rt/traced-run '%s '%s '%s %s '%s)"
                                              nses
                                              (vec (sort targets))
                                              (pr-str (some-> only vec))
                                              (boolean skip-integration?)
                                              (pr-str methods))))]
        (drain-child-rt! handle)
        result))))
