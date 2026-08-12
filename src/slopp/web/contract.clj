(ns slopp.web.contract
  "Publishing the SHAPE of an app's own API, so something that is not this app
  can generate a typed client against it.

  This is what makes a client in a DIFFERENT codebase possible: the consumer
  reads a document instead of importing the producer's contracts namespace,
  and the two share no store. Everything here derives from VAR METADATA, like
  `slopp.web.routes` next door — which is what lets it answer identically from
  a live store, a jar and a native binary, and what lets it ship in the slim
  jar. Publishing a contract has to be something ANY slopp-web app can do; a
  version that only worked for an app whose code lives in a store would be the
  privilege it exists to remove.

  The price of deriving from vars rather than source: schema NAMES are gone by
  runtime — `^{:web/response contracts/timeline}` is evaluated at def time, so
  a schema referenced by name inlines into every endpoint that uses it. Names
  are a source-level convenience the wire never had."
  (:require [slopp.web.routes :as routes] [clojure.string :as str]))

(defn- undent
  "A docstring with its SOURCE INDENTATION removed, or nil.

  A docstring's first line begins right after the opening quote and every later
  line carries however far the form is indented in its file. That indentation
  is a fact about our formatting, and publishing it puts it in every consumer's
  copy of the contract — the same trap a multi-line schema `:doc` falls into,
  one grain up.

  Line and PARAGRAPH structure survive: only the common leading run is removed,
  so a blank-line break still separates paragraphs for a consumer that renders
  them. A consumer that would rather collapse it all to one line still can; the
  reverse is not available if we flatten it here."
  [s]
  (when s
    (let [[head & tail] (str/split-lines s)
          indents (for [l tail :when (seq (str/trim l))]
                    (count (re-find #"^[ \t]*" l)))
          n       (if (seq indents) (apply min indents) 0)]
      (str/trimr
       (str/join "\n" (cons (str/trim head)
                            (map #(if (>= (count %) n) (subs % n) (str/triml %))
                                 tail)))))))

(defn ^:export contract-document
  "The SHAPE of the API `ns-syms` serve, as data — what a consumer needs to
  generate a typed client without sharing a store.

  `{:slopp/contract-version 1
    :endpoints [{:method :get :path \"/x\" :name x
                 :handler my.app/x :doc \"GET /x — …\"
                 :auth :public :request nil :response […]}]}`

  `:auth` is the endpoint's `:web/auth` declaration verbatim — `:public`, or
  `[:group \"admin\"]`, or whatever an app declares. It is the cheapest key
  here to trust, because the auth write gate REFUSES an endpoint that declares
  none: unlike `:request`, it can never be nil-because-nobody-said, so a
  consumer never has to tell \"public\" from \"unknown\". Published as the
  VALUE rather than a boolean because who may call is what a reader wants;
  whether anyone may is not a question anybody asks.

  `:handler` is the qualified symbol, and it is here because `:name` alone does
  not RESOLVE. Measured on slopp's own nine endpoints, three of them —
  `ns-outline`, `search`, `timeline` — match more than one form by simple name,
  so a consumer linking to \"the form called `:name`\" points at the wrong one
  for a third of the surface and looks right doing it. A namespace and a name
  are plain var metadata, so this costs the document nothing it was protecting:
  `:form-id` would be store identity and is deliberately still absent.

  `:doc` is the handler's own docstring, DE-INDENTED and otherwise WHOLE. A
  consumer wanting one line takes the first sentence; one that ships only a
  first line cannot be un-truncated by a consumer that wants the rest.

  **So a handler's docstring is public API copy.** That is the price of not
  inventing a second prose field beside it — one fact with two homes can
  disagree, and this one cannot. Write it for the caller: an implementation
  note left in there ships to everyone generating a client.

  Derived from VAR METADATA, like every other route derivation here, so it
  answers identically from a live store, a jar, and a native binary — and so
  it ships in the slim jar. That is the whole point: publishing a contract is
  something any slopp-web app can do, not a privilege of the tool that happens
  to keep its code in a store.

  Schemas travel as VALUES, not source. `^{:web/response contracts/timeline}`
  is evaluated at def time, so by the time we see it the schema is already
  plain malli data — no store read, no source text on the wire, and no schema
  importer at the far end. The cost is that the author's schema NAMES are gone:
  a schema referenced by name inlines into every endpoint that uses it. Names
  are a source-level convenience the runtime never had.

  A missing schema is published as an explicit nil rather than an absent key,
  so a consumer can tell \"no body\" from \"I don't know\".

  `:web/client false` opts an endpoint OUT, the same exclusion the client
  generator honours — an HTML page is a `:web/path` form like any other, and a
  typed fetch wrapper over it would be nonsense."
  [ns-syms]
  {:slopp/contract-version 1
   :endpoints
   (vec (for [row  (routes/from-namespaces ns-syms)
              :let [m (meta (:handler row))]
              ;; a :web/spa var contributes catch-all rows pointing at the SAME
              ;; handler; they are one endpoint, so keep the declared path only
              :when (and (= (:path row) (str (:web/path m)))
                         (not (false? (:web/client m))))]
          {:method   (:method row)
           :path     (:path row)
           :name     (:name m)
           :handler  (symbol (str (:ns m)) (str (:name m)))
           :doc      (undent (:doc m))
           :auth     (:web/auth m)
           :request  (:web/request m)
           :response (:web/response m)}))})
