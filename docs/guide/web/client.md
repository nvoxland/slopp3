# ClojureScript client

Browser code is authored the way everything else is: forms in the store, edited
with the same tools, gated by the same dialect. What changes is the *platform* a
namespace targets, and what verifies it.

## Three platforms

```clj
module_platform {module "shop.contracts" platform ":cljc"
                 prompt "order schemas are shared by the server and the browser"}
ns_create {ns "shop.ui.dom" platform ":cljs" requires [...]}
```

| Platform | Renders as | Loads in the oracle | Verified by |
|---|---|---|---|
| `:jvm` (default) | `.clj` | yes | the test suite |
| `:cljc` | `.cljc` | yes, the `:clj` branch | the test suite |
| `:cljs` | `.cljs` under `cljs-src/` | never | the compiler |

Scope is a namespace path with most-specific-wins, exactly like
`module_purity`. `query_depends {modules true}` returns a `:platforms` map;
anything absent from it is `:jvm`.

A `:cljs` write lands `:unverified` with reason `:cljs-deferred-to-compile`.
That is honest rather than a gap: the JVM oracle cannot load `js/document`, so
the compiler is the gate for that code.

!!! tip "Keep the .cljs thin"
    A predicate, a schema, a state transition is platform-neutral. Put it in
    `.cljc` and the JVM oracle covers it for free -- write a `-test`, watch it
    go red, implement, green, exactly like Clojure. Reserve `.cljs` for the
    genuinely browser-bound edge: DOM reads, event handlers, `fetch` glue.
    That code is verified by "it compiled" and nothing more until browser tests
    exist, which they do not yet.

## Compiling

```clj
compile_client {}
```

Every `:cljc` and `:cljs` namespace compiles with the configured backend --
real ClojureScript, running on the JVM, no Node -- into one `:simple` bundle
recorded as a served blob. The default output path is `public/cljs/main.js`, so
a `http.static./assets` mount pointing at `public` serves it at
`/assets/cljs/main.js`:

```clj
config_file {path "capabilities" key "http.static./assets" value "public"}
```

Compile errors and analyzer warnings are anchored to the owning store form, by
name, with no file or line. Reference the bundle from a page's head, and let a
top-level `defonce` start it so the page needs no inline script:

```clj
:html/head [[:script {:src "/assets/cljs/main.js" :defer true}]]
```

```clj
(defonce _start (main))
```

`compile_client` is explicit, like `build`. For a tighter loop:

```clj
config_file {path "client" key "auto-compile" value "true"}
```

A write to a client namespace then returns `:client-recompiling` and schedules
a background compile (single-flight, coalescing), and a `--live` server serves
the fresh JS once it commits. Off by default.

The backend is a per-project setting -- `config_file {path "client" key
"compiler"}`, default `:clojurescript`. Other backends are a future
possibility from the same source.

!!! note "slopp provisions its own toolchain"
    There are two dependency configurations. **Yours** is the `deps_add`
    manifest: application libraries, delta-tracked, visible in `deps_list`.
    **slopp's** is the ClojureScript compiler and malli, injected at build
    time and versioned centrally with slopp. They never enter your manifest,
    never appear in `deps_list`, and never land as deltas in your history, so a
    slopp upgrade moves every store forward with no migration. Do not
    `deps_add` the compiler.

## The typed client is generated

Once endpoints declare their `:web/request` and `:web/response` -- which the
`web-endpoint-schema` gate requires -- slopp can write the browser side of the
contract for you:

```clj
generate_client {}
```

That writes a stored `:cljs` namespace (default `app.client.api`, or the
`client`/`generated-ns` config) holding one typed `fetch` wrapper per endpoint.
Each wrapper validates parameters on the way out and the response on the way
in, against the *same* schema var the server enforces.

```clj
(api/create-order! {:sku "A1" :qty 2})   ; returns a promise; a wrong shape
                                          ; throws before the request leaves
```

Rules of the road:

- **It is explicit.** Run `generate_client` after changing an endpoint's
  contract, like `compile_client`. A `stale-client` advisory at done time
  nudges you when a contract has drifted since the last generation. With
  `client`/`auto-compile` on, generating also refreshes the JS bundle.
- **Never hand-edit it.** Every wrapper carries `^{:generated "<endpoint>"}`
  and the `generated-ns` gate refuses edits, because the next generate would
  overwrite them. To take manual ownership of a wrapper, strip the marker.
- **It is still ordinary code.** `query_source` reads it, blast radius covers
  it, and because the wrappers reference schema *vars*, "change this schema ->
  every affected client call" falls out of the reference graph rather than a
  grep.
- **Schemas must be `.cljc`.** A schema var the client ships has to compile
  into the bundle *and* be the one the server validates. `generate_client`
  skips an endpoint whose schema is not shippable and names it in
  `:problems`. An `inline-schema-dup` advisory nudges a shape shared by two
  endpoints toward a named `.cljc` var.
- **Page endpoints opt out** with `:web/client false`. See [HTML and
  CSS](html.md).

## Sharing real logic

A malli schema in `.cljc` is verified by the JVM oracle here and compiled into
the browser bundle there, so one definition checks both sides of the wire. The
same goes for any pure transform: put it in `.cljc`, test it on the JVM, use it
in the browser.

Refactoring works on client code like any other -- `edit_rename`, `edit_move`,
`edit_extract` and `edit_move_forms` all handle `:cljs` forms, and clj-kondo
lints each form in its own platform's language, so `js/*` does not draw a false
unresolved-namespace finding.

One rough edge worth knowing: the `!`-effect warning fires on idiomatic
ClojureScript entry points, because `^:export main` touches the DOM. It is
advisory, not a refusal.
