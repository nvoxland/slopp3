# Running and shipping

`slopp.web` is the runtime half. It reads the same var metadata the write gates
enforced, so there is no second description of the surface to keep in sync.

## While you work, slopp can serve it for you

For many projects you do not start a server and do not write a `serve!` call
to get one: slopp boots a dedicated image for the app, loads the web surface
into it, and re-serves at every `done` point. `session_brief` reports the url
as `:app`.

It does not fit every app yet, and the exceptions are ordinary. Set
`dev.server` to `false` if you have `http.static.*` mounts (they are not served, so a
single-page app gets its API and a page with no JavaScript), if your app is a
server for something other than itself -- a hub or a proxy, which can never be
a managed server's subject -- or if something else already serves this
project's HTTP surface.

Handlers that take `:web/deps` are fine, provided you say how to build them:
mark one zero-arg function `^{:web/context true}` and slopp calls it, passing
the result as `:web/perform-ctx`. Exactly one per store, and slopp holds you to
it -- writing an endpoint that reads `:web/deps` into a store that declares no
builder is refused at the write, because nil dependencies either return a 500
or, worse, answer 200 with an empty body.

Note that the context does **not** survive a refresh -- every `done` boots a
fresh app image, so anything the builder allocates is new each time. Keep live
state elsewhere. A builder returning `{:registry (atom {})}` allocates a new
atom on every call; only a top-level `defonce` that the builder *references*
has any chance of outliving a reload.

Everything the launch needs is derived from the store -- which namespaces to
scan, the host, the port -- so there is no hand-kept list that can disagree
with the surface the gates enforced.

Three things follow from the design that are worth knowing up front:

- **The grain is `done`, not the write.** Mid-episode a store is
  intentionally incomplete, and a browser reloading after every form would
  show you a broken app most of the time. A red `done` still refreshes:
  looking at the app is part of finding out you were not finished.
- **A store that will not load leaves the previous version serving**, and
  reports why. "Always up" and "up to date" only conflict when a boot fails,
  and this is the answer to that conflict.
- **The app image carries your dependencies, not slopp's.** Code that only
  works because slopp happened to have a library on its classpath fails when
  you look at the page, rather than when someone deploys.

The address is derived from the store directory, so two projects on one
machine never collide. Set `http.port` to pin it.

## Serving it yourself

This is what a deployed build calls, and what the dev server calls for you.

```clj
(web/serve! {:web/namespaces ['shop.api 'shop.ui]
             :web/host "0.0.0.0"
             :web/port 8080})
```

`serve!` assembles the dispatch context from those namespaces -- the route
table and both performer vocabularies come off var metadata -- starts an
adapter, and returns a handle for `stop!`.

| Opt | Default | Means |
|---|---|---|
| `:web/namespaces` | -- | The namespaces to scan. Required. |
| `:web/adapter` | `:http-kit` | `:jdk` is the zero-dependency fallback. |
| `:web/host` | `127.0.0.1` | Localhost by default; widen deliberately. |
| `:web/port` | `8080` | |
| `:web/perform-ctx` | `nil` | Passed to every read and effect performer, and to the handler as `:web/deps`. |
| `:web/auth-config` | `nil` | The provider config identity resolves through. See [auth](auth.md). |
| `:web/routes` | `[]` | Extra route rows appended to the derived ones -- static mounts, anything programmatic. |
| `:web/max-body-bytes` | 1048576 | Request body cap. Thread the `http.max-body-bytes` capability in. |

The adapter is a value behind a one-function seam, which is what keeps the
server library a config key rather than a rewrite.

## Testing without a socket

```clj
(web/handle! (web/context {:web/namespaces ['shop.api]})
             {:request-method :get :uri "/api/orders/7"})
```

`handle!` runs the entire pipeline in-process -- identity, route, policy,
declared reads, handler, effect interpretation -- and returns the response map.
No port, no fixture, nothing to tear down.

The order is the guarantee: identity resolves, then the route matches (404),
then the policy runs (401 unauthenticated, 403 unauthorized), and only then is
the handler reachable. You cannot accidentally test a handler past its own auth.

For anything narrower, call the handler directly with a synthetic request:
`{:path-params {:id "7"} :web/reads {:order {...}}}` is a complete input,
because the framework is what fetched those reads.

## Seeing a page without starting anything

```clj
query_eval "(slopp.web/handle! (slopp.web/context {:web/namespaces ['shop.ui]})
                               {:request-method :get :uri \"/orders\"})"
```

The rendered HTML comes back in the response map. Under `slopp . --live` an
edited page hot-serves into the already-running server, so the browser loop is
F5 with no build step.

## Static assets

Binary files ride the files manifest, content-addressed:

```clj
file_put {path "public/logo.png" content "<base64>"
          encoding "base64" content-type "image/png"}
config_file {path "capabilities" key "http.static./assets" value "public"}
```

The mount key's tail is the URL prefix and the value is a path prefix on the
manifest, so that pair maps `GET /assets/logo.png` to `public/logo.png`. The
journal carries only the sha; the bytes live in a blob table. Declaring the
mount is also what stops `web-dangling-route-refs` failing every page that
links an asset, since the check joins declared routes with declared mounts.

The capability declares the mount; the serving side turns it into routes.

```clj
(web/serve! {:web/namespaces ['shop.api]
             :web/routes (static/mount-routes
                          {"/assets" "public"}
                          (static/file-or-resource-reader app-root))})
```

`mount-routes` takes `{url-prefix path-prefix}` plus a reader and returns route
rows serving a whole tree under the prefix. Path traversal is refused in the
route handler before the reader is ever called, and a rejected path is
indistinguishable from a missing one -- 404, never a leak.
`file-or-resource-reader` is the built-app reader: a file under the app root
first, then a classpath resource, so one reader covers both the jar and a
native binary carrying its own assets. slopp's own transport wires the same two
pieces against a store-backed reader, which is why an edited asset hot-serves
under `--live`.

## Shipping it

Set the entry point in the store, then build:

```clj
config_file {path "capabilities" key "app.name"    value "shop"}
config_file {path "capabilities" key "app.main"    value "shop.core/-main"}
config_file {path "capabilities" key "app.version" value "1.0.0"}
```

```sh
slopp --call build '{"dir":"/tmp/shop-out"}'
```

`build` materializes the store as ordinary files with a generated `deps.edn`,
the files manifest (real bytes for blobs), and the capabilities config as a
`key: value` file the app can read at startup. Given an entry point -- the
`main` argument, or `app.main` from the store -- it also emits a GraalVM
native-image recipe, with manifest assets copied onto the compile classpath via
`-H:IncludeResources` so the binary serves its own files.

Three ways to run the result:

- **`java -jar slopp.jar <store-dir>`** -- the store loader resolves the
  dependency manifest at boot and runs `app.main`. No build step at all.
- **An uberjar** built from the materialized tree, depending on the slim
  `io.github.nvoxland/slopp-web` artifact rather than the whole of slopp.
- **A native binary** from the emitted recipe.

!!! warning "The pin is what runs, and nothing checks it"
    A store that declares `io.github.nvoxland/slopp-web` runs **that** version,
    including under `java -jar slopp.jar` — the slopp process carries
    `slopp/web/**` in its own jar, and the declared coord still wins. So a fix
    to `slopp.web` in a newer slopp does not reach your app until the slim
    artifact is republished and you `deps_add` the new version. No surface
    reports that your pin is behind.

!!! warning "One known gap"
    `HEAD` requests do not route. Mapping HEAD onto GET is a small change and
    has not been made yet.
