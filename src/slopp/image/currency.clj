(ns slopp.image.currency
  "What the IMAGE actually loaded, per form — the record that makes
  \"is the running process the code the store describes?\" a COMPARISON.

  It existed as a counter before, and a counter answers a different question.
  `boot/watch-live!` tallies consecutive reload failures and
  `orient/code-deltas-since` counts deltas landed since boot; between them
  they can only observe *did a reload attempt throw*. That is wrong in both
  directions and both directions were paid for in one wave: three silent
  divergences that every check called green (a `def` that captured another
  form's value, var metadata that captured a schema's value, a generated
  namespace written to the store and never loaded) and one false alarm that
  sent a milestone through a fresh JVM because a failed reload was reported as
  \"the host still runs their previous code\" while the image was current.

  The fix is identity, not a louder label: the store gives every form an id, a
  hash and provenance, and the image gave its loaded forms none, so the
  question could not be asked. Every path that evaluates a form into the image
  stamps it here.

  Two fields, and both are load-bearing:

  - `:hash` answers *is this form's source the store's source* — it catches a
    form the store has moved past, and a form the store has that the image
    never loaded at all.
  - `:seq` is a monotonic stamp counter answering *what was evaluated AFTER
    what*. Source hashes cannot see the worst class: a form whose own source
    never changed, whose VALUE was captured at load time from a form that has
    since been re-evaluated. `slopp.rules.currency` reads the order.

  In-process bookkeeping only — no store, no IO, and deliberately no analysis,
  so the write paths can stamp without taking on a dependency.")

(defn ^:export new-registry
  "A fresh, unarmed record for ONE image. `slopp.image.repl/start!` mints one
  per image and hangs it on the handle.

  `{:seq n :armed? bool :forms {form-id {:hash h :seq n}}}`.

  This was a process-global `defonce` whose docstring explained that reloading
  THIS namespace must not erase what the image loaded. That concern belonged
  to the global and goes with it — a record on a handle is not touched by any
  namespace reload, and it is garbage when its image is. What replaces the
  `defonce` argument is a stronger one: a record that cannot outlive its image
  cannot be attributed to a different one.

  `:armed?` stays, and it guards the same confusion one level up: a record no
  loader has filled is not a record of nothing, so it answers \"not measured\"
  until `arm!` says otherwise. Absence of a check and absence of a finding
  must never share a representation — that is what this whole file is for,
  and it applies to the file itself."
  []
  (atom {:seq 0 :armed? false :forms {}}))

(defn- reg
  "`image`'s own record.

  Throws on a handle that has none rather than stamping nowhere. An image not
  minted by `repl/start!` is a bug at its construction site, and a silent
  no-op here would surface much later, somewhere else, as a whole namespace
  reading `:never-loaded` — which is precisely the shape of failure this
  registry exists to end."
  [image]
  (or (:currency image)
      (throw (ex-info (str "image has no currency record — every handle from "
                           "repl/start! carries one")
                      {:image-keys (vec (keys image))}))))

(defn ^:export hash-of
  "The identity of a form's SOURCE, as one value.

  One spelling, used by both the stamping side and the comparing side — two
  hashes computed two ways is how a comparison quietly starts reporting drift
  that is only a difference of method.

  In-process only, which is why an ordinary content hash is enough: the
  registry is never persisted and never compared across processes. A fresh
  process has an empty registry and loads everything, so there is nothing for
  a portable digest to buy."
  [src]
  (hash (str src)))

(defn ^:export stamp!
  "Record that `form-id`'s source was just evaluated into `image`.

  Called by every path that puts a form in an image — the per-form hot-load
  and the whole-namespace load. Missing one is not a crash but a lie: that
  form reads as `:never-loaded` forever after.

  The image is an ARGUMENT, which is the whole of this record's design. It
  used to be implied — the record was one process-global atom and the image it
  meant was the oracle — so a second image had to be kept out by every caller
  choosing a non-stamping loader. Correctness that depends on picking the
  right door is not correctness; the door is gone and the subject is named.

  The `:seq` bump is what makes the derived-stale class visible. Re-evaluating
  a form moves it to the FRONT of the order, so a `def` that captured its
  value earlier is now behind its own input and can be named — which is
  exactly the case a source hash cannot see, because the stale form's own
  source never changed. The counter is per-image too: one image's evaluation
  order says nothing about another's."
  [image form-id src]
  (swap! (reg image)
         (fn [s]
           (let [n (inc (:seq s))]
             (-> s
                 (assoc :seq n)
                 (assoc-in [:forms form-id] {:hash (hash-of src) :seq n}))))))

(defn ^:export stamped
  "What `image` loaded for `form-id` — `{:hash :seq}` — or nil if it never
  loaded it. Absence is a real answer here and must stay distinguishable from
  a match, so this returns nil rather than an empty map."
  [image form-id]
  (get-in @(reg image) [:forms form-id]))

(defn forget!
  "Drop `image`'s stamps for `form-ids` — for forms leaving it (a delete, a
  namespace removed). A stamp that outlives the form it describes would report
  a deleted form as loaded and current."
  [image form-ids]
  (swap! (reg image) update :forms #(apply dissoc % form-ids)))

(defn ^:export forget-all!
  "Empty `image`'s record — it holds nothing and has not been measured.

  ONE legitimate caller: `repl/reset-to-baseline!`, the single point where an
  image changes TENANT. Everywhere else a record that must be empty belongs to
  an image that is new, and `repl/start!` already minted it empty.

  That is exactly why a recycled image needs this and a fresh one does not:
  `reset-to-baseline!` hands the SAME handle to the next tenant, so without
  this the new tenant inherits stamps for a store it never loaded — the false
  green this record exists to prevent, arriving through the reuse path rather
  than through a second image.

  Resets `:armed?` too: between the reset and the loader's `arm!` the image is
  genuinely mid-construction, and a comparison run then would report every
  form not yet reached as missing."
  [image]
  (reset! (reg image) {:seq 0 :forms {} :armed? false}))

(defn ^:export snapshot
  "`image`'s whole record as one value: `{form-id {:hash :seq}}` — or **nil**
  when it has not been armed, and nil for a nil image, which mean the same
  thing: nobody has measured this.

  The nil is the important half, and it is this file's own instance of the
  rule it exists to enforce: *an empty record and no record must not share a
  representation.* A record is only complete from the moment an image is built
  with the stamping in place. Before that it holds a few forms out of
  thousands, and comparing that against the store would report the whole
  codebase as never-loaded — the loudest possible false alarm, produced by the
  machinery meant to end false alarms.

  So `arm!` is called by the loader once it has stamped everything it loaded,
  and until then this answers \"I have not looked\". `host-brief` and
  `host-warning` already distinguish that from \"I looked and found nothing\".

  Exists at all so the drift ANALYSIS can be a pure function of (store,
  stamps): the cases worth testing are trivial to state as data and painful to
  stage in a live process."
  [image]
  (when image
    (let [s @(reg image)]
      (when (:armed? s) (:forms s)))))

(defn ^:export arm!
  "Declare `image`'s record COMPLETE — called by a loader once it has stamped
  everything it loaded into that image.

  Before this, `snapshot` answers nil and every currency surface reports
  \"not measured\". After it, an unstamped form is real news. Splitting the
  two is what keeps a partially-populated record from reporting a whole
  codebase as never-loaded.

  Idempotent, and deliberately separate from `stamp!`: arming on the first
  stamp would make one hot-loaded form arm a record holding nothing else."
  [image]
  (swap! (reg image) assoc :armed? true))
