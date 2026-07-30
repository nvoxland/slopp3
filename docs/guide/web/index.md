# Web applications

A slopp web app is the same store, the same tools, and the same gates. What the
web support adds is a set of declarations slopp can check statically and
interpret at runtime, so that the parts people usually get wrong -- an
unsecured route, a link to a path nothing serves, a client that disagrees with
the server about a payload -- fail at the write instead of in production.

Nothing here exists until a store asks for it:

```clj
config_file {path "capabilities" key "http.enabled" value "true"}
```

The shape of it:

- **[Endpoints](endpoints.md)** -- an endpoint is a `defn` carrying its whole
  contract in name metadata. No route table, no macro. Reads are declared and
  fetched for you; writes come back as data the dispatcher interprets, so
  handlers stay testable without mocks.
- **[Auth and security](auth.md)** -- the policy is on the form and default-deny
  is enforced by a write gate. Providers and groups live in the capabilities
  config; the runtime bounds effects, redacts error bodies, and caps request
  sizes.
- **[HTML and CSS](html.md)** -- pages are hiccup-returning `defn`s and
  stylesheets are garden-returning `defn`s, so a UI merges, renames, and
  re-tests at component grain. Rendered links are indexed against the route
  table.
- **[ClojureScript client](client.md)** -- browser code lives in the same store
  under a declared platform, portable logic in `.cljc` gets the JVM oracle for
  free, and the typed `fetch` client is generated from the endpoint contracts
  rather than written twice.
- **[Running and shipping](running.md)** -- `slopp.web/serve!` for a port,
  `slopp.web/handle!` for a portless test of the entire pipeline, and
  `build` for a jar or a native binary that carries its own assets.

The design centre is a third-party application, not slopp's own endpoints.
slopp's MCP transport and its per-project API are built on the same machinery,
and so is the reviewer UI — which is a third-party application already: its own
repo, its own store, and no way into slopp's. That is the dogfood, not the
target.
