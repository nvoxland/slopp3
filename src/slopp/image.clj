(ns slopp.image
  "Bridge the store to the owned live image: load a namespace's forms straight
  from the CRDT into the running JVM (no disk, C1), and run its tests there,
  recording the green/red result as provenance (D5/D6, C4)."
  (:require [slopp.render :as render]
            [slopp.repl :as repl]
            [slopp.store :as store] [slopp.rt :as rt]))

(defn load-ns!
  "Evaluate `ns-sym`'s current source (rendered from the store) into the image,
  then mark it in `*loaded-libs*` — store namespaces have no classpath presence
  (C1 no-disk), so without the mark a later `(:require ns-sym)` from another
  store namespace would hit the classpath and fail."
  [handle store ns-sym]
  (let [res (repl/load-checked! handle
                                (render/render-ns store ns-sym)
                                (render/ns-path ns-sym))]
    (repl/eval! handle
                (format "(dosync (commute (deref #'clojure.core/*loaded-libs*) conj '%s))"
                        ns-sym))
    (:err res)))

^:reads (defn test-run
  "Run `ns-sym`'s clojure.test tests in the live image; returns the summary map
  ({:test :pass :fail :error :type})."
  [handle ns-sym]
  (first (repl/eval! handle (format "(clojure.test/run-tests '%s)" ns-sym))))

(defn- drain-child-rt!
  "Move what slopp.rt did IN `handle`'s child onto whoever is tracing us (#126).

  rt is the only slopp code that executes in a child image — `repl/inject-rt!`
  is the sole place slopp code is evaled in, and the child otherwise loads its
  own store. So its calls are invisible to a caller that only wraps its OWN
  vars: measured 2026-07-17, slopp.rt/traced-run read 0 covering tests while 213
  exercised it from here.

  No sink means nobody is tracing — the MCP server's own images, overwhelmingly
  — and then this costs nothing at all, not even the round-trip. Resolve rather
  than call: an older rt (a lagging uberjar) has no drain, and a missing drain
  must degrade to no evidence, never to an error."
  [handle]
  (when-let [sink @rt/touched-sink]
    (when-let [syms (first (repl/eval!
                            handle
                            "(when-let [d (resolve 'slopp.rt/drain-self!)] (d))"))]
      (swap! sink into syms))))
^:reads (defn traced-test-run
  "Run `test-ns`'s tests in the image with form-tracing (slopp.rt): the fn
  vars of `test-ns`'s dependency CLOSURE are observed (item 2 — not every
  store namespace), so the result maps each test to the forms it exercised.
  `only` (a coll of plain test names) restricts which tests run. `test-ns`
  may be a collection — whole-project verification in ONE eval (F-3c1).
  Returns {:summary {...} :trace {test-sym #{form-sym ...}}}.

  Ships the closure's defmethod registrations along (#129,
  `store/method-registrations`) so a dispatched call records the METHOD's own
  form key, not just the multi's — the store is the only thing that knows
  which dispatch value lives in which form.

  Also drains what rt itself did in the child onto whoever is tracing US (#126)
  — this call is where `slopp.rt/traced-run` actually executes, so it is the
  only place that evidence can come from."
  [handle store test-ns & {:keys [only skip-integration?]}]
  (let [targets (if (coll? test-ns)                 ; F-3c1: union of closures
                  (into #{} (mapcat #(store/ns-closure store %)) test-ns)
                  (store/ns-closure store test-ns))
        methods (vec (mapcat #(store/method-registrations store %) targets))
        result  (first (repl/eval! handle
                                   (format "(slopp.rt/traced-run '%s '%s '%s %s '%s)"
                                           (if (coll? test-ns) (vec test-ns) test-ns)
                                           (vec (sort targets))
                                           (pr-str (some-> only vec))
                                           (boolean skip-integration?)
                                           (pr-str methods))))]
    (drain-child-rt! handle)
    result))
