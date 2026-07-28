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
    since been re-evaluated. `slopp.api.currency` reads the order.

  In-process bookkeeping only — no store, no IO, and deliberately no analysis,
  so the write paths can stamp without taking on a dependency.")

(defonce ^{:private true
           :doc "`{:seq n :armed? bool :forms {form-id {:hash h :seq n}}}`.

  `defonce` on purpose: reloading THIS namespace must not erase the record of
  what the image loaded. An erased registry reads as `:never-loaded` for every
  form in the store — the loudest possible false alarm, produced by the very
  mechanism meant to end false alarms.

  `:armed?` is the same concern one level up. A registry that has not been
  filled by a loader has no record, which is not a record of nothing, so it
  answers \"not measured\" until `arm!` says otherwise."}
  stamps
  (atom {:seq 0 :armed? false :forms {}}))

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
  "Record that `form-id`'s source was just evaluated into the image.

  Called by every path that puts a form in the image — the per-form hot-load
  and the whole-namespace load. Missing one is not a crash but a lie: that
  form reads as `:never-loaded` forever after.

  The `:seq` bump is what makes the derived-stale class visible. Re-evaluating
  a form moves it to the FRONT of the order, so a `def` that captured its
  value earlier is now behind its own input and can be named — which is
  exactly the case a source hash cannot see, because the stale form's own
  source never changed."
  [form-id src]
  (swap! stamps
         (fn [s]
           (let [n (inc (:seq s))]
             (-> s
                 (assoc :seq n)
                 (assoc-in [:forms form-id] {:hash (hash-of src) :seq n}))))))

(defn ^:export stamped
  "What the image loaded for `form-id` — `{:hash :seq}` — or nil if it never
  loaded it. Absence is a real answer here and must stay distinguishable from
  a match, so this returns nil rather than an empty map."
  [form-id]
  (get-in @stamps [:forms form-id]))

(defn forget!
  "Drop the stamps for `form-ids` — for forms leaving the image (a delete, a
  namespace removed). A stamp that outlives the form it describes would report
  a deleted form as loaded and current."
  [form-ids]
  (swap! stamps update :forms #(apply dissoc % form-ids)))

(defn ^:export forget-all!
  "Reset the registry — for a fresh image, where nothing is loaded yet.

  The registry describes ONE image. Carrying stamps across a rebuild would
  claim the new image holds what the old one did, which is the precise
  failure this whole mechanism exists to end.

  Resets `:armed?` too: between the reset and the loader's `arm!` the image is
  genuinely mid-construction, and a comparison run then would report every
  form not yet reached as missing."
  []
  (reset! stamps {:seq 0 :forms {} :armed? false}))

(defn ^:export snapshot
  "The whole registry as one value: `{form-id {:hash :seq}}` — or **nil** when
  the registry has not been armed.

  The nil is the important half, and it is this file's own instance of the
  rule it exists to enforce: *an empty record and no record must not share a
  representation.* A registry is only complete from the moment an image is
  built with the stamping in place. Before that — a process whose image
  predates this mechanism, a registry born mid-session — it holds a few forms
  out of thousands, and comparing that against the store would report the
  whole codebase as never-loaded. That is the loudest possible false alarm,
  produced by the machinery meant to end false alarms.

  So `arm!` is called by the loader once it has stamped everything it loaded,
  and until then this answers \"I have not looked\". `host-brief` and
  `host-warning` already distinguish that from \"I looked and found nothing\".

  Exists at all so the drift ANALYSIS can be a pure function of (store,
  stamps): the cases worth testing are trivial to state as data and painful to
  stage in a live process."
  []
  (let [s @stamps]
    (when (:armed? s) (:forms s))))

(defn ^:export arm!
  "Declare the registry COMPLETE — called by a loader once it has stamped
  everything it loaded into an image.

  Before this, `snapshot` answers nil and every currency surface reports
  \"not measured\". After it, an unstamped form is real news. Splitting the
  two is what keeps a partially-populated registry from reporting a whole
  codebase as never-loaded.

  Idempotent, and deliberately separate from `stamp!`: arming on the first
  stamp would make one hot-loaded form arm a registry holding nothing else."
  []
  (swap! stamps assoc :armed? true))
