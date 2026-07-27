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
  (:require [slopp.web.routes :as routes]))

(defn ^:export contract-document
  "The SHAPE of the API `ns-syms` serve, as data — what a consumer needs to
  generate a typed client without sharing a store.

  `{:slopp/contract-version 1
    :endpoints [{:method :get :path \"/x\" :name x :request nil :response […]}]}`

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
           :request  (:web/request m)
           :response (:web/response m)}))})
