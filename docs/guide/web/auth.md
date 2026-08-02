# Auth and security

Authorization is declared on the endpoint and enforced by the dispatcher before
the handler is reachable. There is no middleware stack to get the order wrong
in, and no way to forget: an endpoint without `:web/auth` does not land.

## The policy grammar

```clj
:web/auth :public                       ; anyone, typed out on purpose
:web/auth :authenticated                ; any resolved identity
:web/auth [:group "staff"]              ; a named group
:web/auth [:any [:group "staff"] [:group "admin"]]
:web/auth [:all :authenticated [:group "billing"]]
```

Groups named in a policy must exist in the capabilities config, or
`web-unknown-group` refuses the write. A typo'd group silently denies forever
otherwise, which is the authorization version of a nil pun.

Every degenerate case denies: a `nil` policy, an empty `[:any]`, and an empty
`[:all]`. The last one mattered -- `(every? pred '())` is vacuously true, so an
empty conjunction would otherwise have authorized everyone.

## Identity

A resolved identity is `{:web/sub "alice" :web/groups #{"staff"} :web/provider
:bearer}`, or `nil` for anonymous. It arrives on the request as
`:web/identity`. Providers are tried in the order `auth.providers` lists them,
the first one to claim the request wins, and configured group membership
augments whatever the provider asserted.

```clj
config_file {path "capabilities" key "auth.providers" value "bearer,static"}
config_file {path "capabilities" key "groups.staff.members" value "alice,bob"}
```

| Provider | Config | Notes |
|---|---|---|
| `bearer` | `auth.bearer.tokens.<name>` = `{:secret "env:SHOP_TOKEN" :groups ["staff"]}` | Constant-time compare. |
| `static` | `auth.static.users.<name>` = `{:password-hash "pbkdf2$..." :groups ["staff"]}` | Basic auth. Salted PBKDF2. |
| `proxy-header` | `auth.proxy.trusted`, `auth.proxy.user-header`, `auth.proxy.groups-header` | Only honoured from a trusted `:remote-addr`. |
| `oidc` | `auth.oidc.issuer`, `auth.oidc.audience`, `auth.oidc.groups-claim` | Resource server. |

Secrets are `env:NAME` indirections, because the capabilities config is
projected into git. The capabilities gate refuses a credential literal.

Generate a password hash with the store's own image rather than pasting one in:

```clj
query_eval "(slopp.web.auth/hash-password \"correct horse battery staple\")"
```

`query_capabilities` is the authority on the full key list, including families
this table summarises.

### OIDC is the resource-server half

slopp validates RS256 JWTs an external identity provider minted -- JWKS lookup
by `kid`, `iss`/`exp`/`aud` checked, claims mapped to an identity. The browser
login flow stays the IdP's job or a proxy's.

!!! warning "An unset audience denies every token"
    `auth.oidc.audience` has no default. A resource server that accepts tokens
    minted for a different audience is a confused-deputy hole, so an unset
    audience fails closed rather than skipping the check.

## Row-level checks

Route policy answers "may this identity reach this endpoint". It cannot answer
"is this identity the owner of row 7". That is the handler's job:

```clj
(web/enforce (= (:web/sub (:web/identity req)) (:owner order)) "not your order")
```

`enforce` throws an `ex-info` carrying `{:web/status 403}`, which the dispatcher
maps to a 403 response. It is deliberately not bang-named: a throw mutates
nothing, and bang-naming it would falsely mark every pure handler doing an
ownership check as effectful. `(web/authorized? policy identity)` is the boolean
twin, for handlers that branch on permission rather than refuse.

slopp does not taint-track data flow, so nothing stops a handler from returning
another tenant's rows. That check remains yours.

## What the runtime enforces

The write gates prove a route's contract statically. These close the gaps a
static analyzer cannot see, and each one has a test modelling the hole:

- **Effects are bounded at runtime by the declaration.** A handler that
  computes its effects cannot emit a kind its route never declared, even when a
  performer for that kind exists. The static gates see only the handler body.
- **Error bodies are redacted.** An `ex-info` with `:web/status` surfaces its
  message plus only an explicit `:web/public` allowlist. Any other exception is
  a generic 500 with the detail logged server-side, never returned.
- **Bodies are bounded.** Both adapters read at most `:web/max-body-bytes`
  (default 1 MiB, from the `web.max-body-bytes` capability) and answer 413.
- **Static reads are contained.** Traversal is refused in the route handler
  before the reader is called, and the built-app reader re-checks that the
  canonical path stays under its root.
- **`web.host` defaults to `127.0.0.1`.** Widening the bind address is a
  deliberate edit.
