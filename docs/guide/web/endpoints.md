# Endpoints

A slopp web app has no route table, no routing macro, and no template
directory. An endpoint is one `defn` whose name metadata carries the whole
contract, and the gates that check every other write check it too.

## Opt in once

```clj
config_file {path "capabilities" key "http.enabled" value "true"}
```

Every web rule, and `query_routes` itself, is inert until that is true. A store
that never opts in has no web surface and never sees a web refusal.

Capability keys are declared in a registry with a type, a default, and a doc
line, so a typo'd key or an ill-typed value is refused at the write rather than
doing nothing. `query_capabilities` lists them all with their effective values.
See [store configuration](../../reference/config.md#the-capabilities-file).

## An endpoint is a defn

```clj
(defn ^{:web/method   :get
        :web/path     "/api/orders/:id"
        :web/auth     [:group "staff"]
        :web/reads    {:order [:order/by-id [:path-params :id]]}
        :web/response shop.contracts/order}
  get-order
  "One order, by id."
  [req]
  {:status 200 :body (:order (:web/reads req))})
```

Request and response maps are Ring-shaped: `:request-method`, `:uri`,
`:headers`, `:body` in; `:status`, `:headers`, `:body` out. Everything slopp
adds is namespaced under `:web/`, so the shape a model already knows stays
intact and the additions are visibly slopp's.

| Metadata key | Means |
|---|---|
| `:web/method` | `:get`, `:post`, `:put`, `:patch`, `:delete`. |
| `:web/path` | The path, with `:name` segments arriving as `:path-params`. |
| `:web/auth` | The policy. Required -- see [auth](auth.md). |
| `:web/reads` | `{alias [<kind> <request-path>]}`. Fetched before the handler runs. |
| `:web/effects` | `[<kind> ...]` -- the effect kinds this endpoint is allowed to emit. |
| `:web/request` | Malli schema for the body. Required on `:post`/`:put`/`:patch`. |
| `:web/response` | Malli schema for the response. Required on every endpoint. |
| `:web/effectful` | `true` opts out of effects-as-data. The escape, not the default. |
| `:web/client` | `false` excludes the endpoint from the [generated client](client.md). |

Two markers go on *other* forms:

| Marker | On |
|---|---|
| `^{:web/read <kind>}` | A function that fetches one read kind: `(fn [ctx arg] ...)`. |
| `^{:web/effect <kind>}` | A function that performs one effect kind: `(fn [ctx & args] ...)`. |

These are declared edges in the reference graph, so endpoints and performers
never trip the dead-surface gate despite nothing in the store calling them.

## Reads in, effects out

The point of declaring reads and effects rather than performing them is that
the handler stays a function of data, and its test is an `=` on data with no
mocks anywhere.

```clj
;; the performers: the only forms that touch the database
(defn ^{:web/read :order/by-id :reads true} fetch-order [db id]
  (db/order db id))

(defn ^{:web/effect :order/insert} insert-order! [db row]
  (db/insert! db row))

;; the endpoint: declares both, performs neither
(defn ^{:web/method   :post
        :web/path     "/api/orders"
        :web/auth     :authenticated
        :web/effects  [:order/insert]
        :web/request  shop.contracts/new-order
        :web/response shop.contracts/order}
  create-order
  "Place an order."
  [req]
  (let [order (assoc (:body req) :owner (:web/sub (:web/identity req)))]
    {:status 201
     :body order
     :web/effects [[:order/insert order]]}))
```

The dispatcher fetches the declared reads, calls the handler, and interprets
the returned effects through the marked performers -- validating every kind
before running any of them, so a typo cannot leave a partial write. A unit test
calls `create-order` with a plain map and asserts on the returned
`:web/effects` vector; the write never happens.

Performers are ordinary functions and the ordinary rules apply: the one that
mutates is bang-named, the one that only reads carries `:reads` so it is not
flagged for calling into an opaque database library.

The escape ladder, in order of preference: declared reads -> a read performer
-> `:web/effectful true` on an endpoint in an `:external` namespace, with its
dependencies arriving as `:web/deps` on the request rather than as ambient
state.

## What the gates check

All of these are inert until `http.enabled`, and every one is severity-dialable
like any other [rule](../verification.md#rules).

| Rule | Refuses |
|---|---|
| `web-auth-refusal` | An endpoint with no `:web/auth`. Default-deny: `:public` is typed out, never implied. |
| `web-endpoint-schema` | A missing `:web/response`, or `:web/request` on a body method. |
| `web-route-collision` | A second owner for one method plus path. |
| `web-undeclared-effect` | A `:web/effects` kind no marked performer provides. |
| `web-unsafe-get` | A `:get`/`:head` endpoint that declares effects or reaches a mutation. |
| `web-unknown-group` | A `[:group "x"]` policy naming a group the capabilities config does not define. |
| `web-react-attrs` | `:className`, `:onClick` and friends in hiccup -- see [HTML and CSS](html.md). |

Two more fire at done time rather than at the write:

- `web-public-mutation` (advisory) asks about a changed `:public` endpoint that
  declares effects. A public signup or webhook is legitimate; the point is that
  it should be a decision.
- `web-dangling-route-refs` (error) fails a rendered link or form targeting a
  path nothing serves. See [HTML and CSS](html.md#links-are-checked).

## Reading the surface

```clj
query_routes {}
```

One call returns every endpoint -- method, path, auth policy, handler, declared
`:web/reads` and `:web/effects`, whether it carries a schema, and
`:rendered-by` (which forms link to it) -- plus the derived `:read-kinds` and
`:effect-kinds` vocabularies. It is the same derivation the write gates run, so
it cannot disagree with them.

Check it before claiming a path, before coining an effect kind, and before
writing a link.

!!! note "Test namespaces are fixtures"
    Endpoint-shaped forms in a `-test` namespace are excluded from the route
    rows. They neither report in `query_routes` nor claim a path, so a test can
    define whatever surface it needs to exercise.
