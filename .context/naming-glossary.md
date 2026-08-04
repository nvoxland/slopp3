# Naming glossary — old → new

**Why this exists.** The restructure renames namespaces and config keys that
the written record already refers to by their old names. Dated records —
`findings-log.md`, the decision narratives in `decisions.md`, the release blog
— are **not rewritten**: a finding from 2026-07-16 says what was true on
2026-07-16, and editing it to match today's names makes it lie about when it
was learned. This table is what keeps them readable instead.

**The rule.** Present-tense claims about how the system IS get updated in
place. Past-tense records of what happened keep their original names, and
resolve here.

**Two limits on that rule, both found by hitting them.**

**A REUSED name cannot resolve here, and the table cannot tell you so.** This
is a lookup keyed by spelling, so it can hold `slopp.api → slopp.ops` or it
can describe today's `slopp.api`, not both. Phase 2 created exactly that: the
name meant the 322-form operation drawer until 2026-08-03 and means the
external API after it, so "resolve through the glossary" would send a reader
from a correct old record to the wrong current module. **When a name is
reused, its past-tense mentions must be disambiguated IN PLACE** — that is the
one case where a dated record gets edited, and what it gets is a parenthetical
("the module then called `slopp.api`, today's `slopp.ops`"), never a rewrite.
Three were fixed in `architecture.md` when the name came free; assume more
turn up, because nothing detects this.

**A vocabulary VALUE goes stale the same way its keys do.** Every row's
right-hand side is itself a name, and a later rename can retire it while the
row goes on pointing there. Two live instances the day phase 2 landed:
`slopp.ui-api → slopp.http-api` (retired three days after it was minted) and
`ui.port → slopp.api.port` (retired outright), each sending a reader from one
dead spelling to another. Nothing compares the two halves — a table whose job
is resolving names has no check that its own answers still resolve. Both
collapsed onto live names; when a rename retires something that appears as a
VALUE here, follow the chain.

**This file has a machine-readable twin: the store's `vocabulary` config.**
Every row below should also be `config_file {path "vocabulary" key <old> value
<new>}`, because two checks read that and nothing reads this. The
`retired-vocabulary` done-advisory scans store FORMS (keywords, requiring an
enumeration — a lone `:reads` is usually the still-valid marker), and
`bin/check-shipped-prose.sh` scans the shipped skills and `docs/` (exact
match, distinctive terms only, dated blog posts excluded). Adding the row here
and not there is how a rename keeps its blind spot.

## Namespaces

| Old | New | When / why |
|---|---|---|
| `slopp.ui-api.*` | `slopp.http-api.*` | named for its consumer (a UI); the consumer changed and the name was stranded. **Intermediate**, and it lived about three days — see the next row |
| `slopp.http-api.*` | `slopp.api.*` | **phase 2, 2026-08-03.** The external API takes the name it was always meant to have; `http-api` only ever existed because `slopp.api` was occupied by the 28-namespace drawer, and phase 1b emptied it. All five namespaces (`contracts`, `endpoints`, `model`, `reads`, `server`) plus their `-test` siblings, module and layer unchanged (7, beside `slopp.ops`). The transport protocol was never the subject: HTTP is how this API is reached, not what it is, and naming a piece for its transport is the same error as naming it for its consumer — R3, twice over |
| `slopp.api.artifacts` | `slopp.store.artifacts` | bytes live on disk, the store holds the sha and the recipe — that is the store's subject |
| `slopp.edit.refs` | `slopp.index.refs` | THE reference graph is derived, content-memoized and never stored: `slopp.index`'s genre, not the edit pipeline's. Side effect: the edit pipeline no longer touches `slopp.cache` at all |
| `slopp.api.crossings` | `slopp.index.crossings` | its pair — `refs` answers every edge INSIDE the store, `crossings` the edges that LEAVE it. Landed together so the pair is one module |
| `slopp.boot` | `slopp.kernel.boot` | **task #13, 2026-08-03.** The kernel became one module. `slopp.boot`, `slopp.rt` and the parity guard share a defining constraint — each has ZERO internal slopp requires, because the kernel must bootstrap before slopp exists and the guard must be callable where no store is loaded. That measured fact, not the "two live copies" argument, is what makes them a module: `slopp.kernel` is layer 0 with no outgoing edges |
| `slopp.rt` | `slopp.kernel.rt` | ditto. The file moved with it, `src/slopp/rt.clj` → `src/slopp/kernel/rt.clj`, which changes the RESOURCE path `image.repl/inject-rt!` slurps — so that read now tries `slopp/kernel/rt.clj` then `slopp/rt.clj`, for the same reason its `resolve` calls are feature-detected: a jar lags the store, and here it lags across a rename |
| `slopp.store.kernel` | `slopp.kernel.parity` | ditto, and it changes MODULE. It was never the store's subject — it is the guard that keeps the kernel's two copies honest, and it sat under `slopp.store` for the same hosting-not-subject reason `store.mine` did |
| `slopp.store.build` | `slopp.build` | not the store: the GraalVM native-image build target. Pure generators, zero internal requires, three callers in three different modules — shared layer-0 infrastructure |
| `slopp.bench` | `slopp.lab` | **R5, 2026-08-02.** The module was named for what two of its four namespaces DO, so the other two had nowhere to be — `slopp.store.mine` sat under storage because it reads `store.db`, which is a hosting relationship, not a subject. The shared property is the CALLER: a human, by hand. That is why none of the four has a caller or a test, and why the 1a′ audit read `store.mine` as rot and floated deleting it — the code could not say it was deliberate |
| `slopp.bench.benchmark` | `slopp.lab.benchmark` | ditto |
| `slopp.bench.evalseed` | `slopp.lab.evalseed` | ditto — 15 KB seeding slopp's own eval rounds, downloaded by every user and usable by none |
| `slopp.store.mine` | `slopp.lab.mine` | ditto, and it changes MODULE: a demand-mining CLI over provenance journals is an instrument, not storage |
| `slopp.http-api.heartbeat` | `slopp.hub` | registering and beating is HUB INTEGRATION, not part of the generic external API — every project on slopp talks to a hub, slopp itself included. `slopp.http-api.contracts/project-beat` came with it (→ `slopp.hub/project-beat`): the beat's shape describes the HUB's `POST /api/register`, so it was the one non-API contract in the API's registry |
| `slopp.api.rules` | `slopp.rules` | **phase 1b, module 2 of 4 (2026-08-02).** The CHECKS are their own module — the episode-scoped advisories, the registry that runs them, and the analysis they judge against. Not part of the operation surface that calls them; `slopp.api.shape`'s own docstring already said it (*"everything here answers a question some rule needs"*) |
| `slopp.api.rules.catalog` | `slopp.rules.catalog` | ditto — the catalog is prose for the registry |
| `slopp.api.rules.markers` | `slopp.rules.markers` | ditto — what the checks ask about a form |
| `slopp.api.breakage` | `slopp.rules.breakage` | ditto — "did this episode break a contract" is a question a rule asks |
| `slopp.api.schema` | `slopp.rules.schema` | ditto — the generative schema check |
| `slopp.api.shape` | `slopp.rules.shape` | ditto |
| `slopp.api.doctor` | `slopp.rules.doctor` | ditto — the legacy sweep asks every current rule of code that predates it |
| `slopp.api.currency` | `slopp.rules.currency` | ditto — "is the running image the code the store describes" is a check by comparison. This is the one 1a could never land in `slopp.image`: it reads the reference graph, which is above `image` no matter which module owns it |
| `slopp.api.attrs` | `slopp.rules.keywords` | ditto, and it changes the WORD. All five forms are the store's namespaced-keyword vocabulary plus the typo advisory built on it — nothing about "attributes". Coherent namespace, wrong name (1a′ finding) |
| `slopp.api.web` | `slopp.rules.web` | ditto, and the checks module is its ONLY legal home: its docstring argues a rule that refuses at the write and a report that lists the surface *"must agree, and they only can if they are one derivation"*, and it cannot join `slopp.webdev` without cycling against the write engine that requires it |
| `slopp.api.query` | `slopp.read.query` | **phase 1b, module 3 of 4 (2026-08-02).** Asking the store a question and telling it to change are different jobs, and the layer map now says so: `slopp.read` is layer 6, the operations that call it layer 7. The `query_*` front door |
| `slopp.api.history` | `slopp.read.history` | ditto — the store over TIME is a read: status-at/after, resolve-at, verify-at, lineage, plus the human renderings of each |
| `slopp.api.orient` | `slopp.read.orient` | ditto — `session_brief`, form cards, host warnings. The first thing a session reads |
| `slopp.api.telemetry` | `slopp.read.telemetry` | ditto, and it renames a live SESSION KEY with it: `:slopp.api.telemetry/calls` → `:slopp.read.telemetry/calls`, written by `slopp.mcp/handle!` and folded by `slopp.api/turn-end!`. `ns_rename` rewrites requires and qualified refs, not qualified KEYWORDS — this was the only stale one in the store, and it was stale for about four minutes |
| `slopp.api.modules` | `slopp.read.modules` | ditto — the module system's read side, against `slopp.edit.modules`'s write side. `production-manifest`'s scoped `^{:export "slopp.http-api"}` widened to `^:export` here: the regroup put its two callers in different subtrees, and a scoped export names exactly one |
| `slopp.api` | `slopp.ops` | **phase 1b, module 4 of 4 (2026-08-03).** 74 forms, 72 public — the plan called this a facade that would dissolve, on the measurement "0 exports, 13 out-edges". Both were wrong: `^:export` means nothing on a TWO-segment namespace, so "0 exports" is trivially true of any module root and was never evidence of delegation. It IS the agent-facing operation surface, and it becomes the root of the module its operations already belonged to |
| `slopp.api.session` | `slopp.ops.engine` | ditto — the WRITE ENGINE: image lifecycle, the rebasing commit, verification. Every operation hands it a pure transform. "session" reads as request state, which is why nobody would have found it; highest in-degree in the module |
| `slopp.api.external` | `slopp.ops.external` | ditto — the operation surface's IO face. Moved LAST of the seven, because it depends on all six others and moving it earlier would have pointed `slopp.ops → slopp.api` while `slopp.api → slopp.ops` already existed |
| `slopp.api.testrun` | `slopp.ops.testrun` | ditto — running the store's tests in processes outside this one |
| `slopp.api.branch` | `slopp.ops.branch` | ditto — a line is a view of the delta log, and branch orchestrates store + image + engine to make it one |
| `slopp.api.done` | `slopp.ops.done` | ditto — the episode boundary is an operation |
| `slopp.api.review` | `slopp.ops.review` | ditto — whole-codebase triage off the done-point's signals |
| `slopp.api.devserver` | `slopp.webdev.live` | **phase 3 (2026-08-03).** Web tooling gets its own module, named for the app TYPE so type #2 needs no rename (R6). `live` because R4 retires "dev server" — and the module keeps the `web` qualifier because a bare `slopp.live` would collide with slopp's own `--live` mode (the MCP server watching the store), which is a different thing from keeping a user's app up |
| `slopp.api.cljs` | `slopp.webdev.cljs` | ditto. The tail stays `cljs` on collision evidence, not taste: this namespace requires `slopp.web.client :as client` and `slopp.build :as build`, so `webdev.client` and `webdev.build` would each shadow a require it already holds. Platform is also what it IS — its own docstring says this is where `:cljs` code gets its only verification |
| `slopp.read.query/{query-history,query-changes,query-lineage,query-form-history,query-search-history,query-status-at,query-form-at,turn-intents,episode-boundary,episode-span,span-anchor,content-ops,fid-ns-at,label-ancestors}` | `slopp.read.history/*` | **phase 1b, the query split (2026-08-03).** The store over TIME was half in `history` (pure folds over the delta log) and half in `query` (the reads built on them). One subject, one namespace |
| `slopp.edit.modules/{per-form-write-gates,write-gate-names,write-gate-severities,rule-applies-to-platform?,gate-check,gate-refusal,rule-severity}` | `slopp.edit.gates/*` | **phase 1b, the gate split (2026-08-03).** The write-gate CHASSIS is not the module system — holding the registry inside one of the families it dispatches to is what made `slopp.edit.modules` a misnomer at 45 forms |
| `slopp.edit.modules/{canonical-tier,tier-declared?,tier-for,tier-report,tier-refusal,tier-order,late-ref-target-nses,layering-violations,tier-violations}` | `slopp.edit.tiers/*` | ditto — purity tiers and the layering they imply |
| `slopp.edit.modules/{web-*,generated-ns,client-signature}` | `slopp.edit.web/*` | ditto — the D-web write gates and their primitives. **Not `slopp.web`**, which is the layer-0 framework a user's app runs on and knows nothing about stores; these read a CANDIDATE store at write time. `module-of` being the first two segments is what makes that mechanical rather than a preference |
| `slopp.read.query/{query-references,query-deps,query-depends,query-impact,query-flow,callee-adjacency,coverage-view}` | `slopp.read.graph/*` | ditto — how forms REACH each other. The relationships live lower (`slopp.index.refs` IS the graph); this is the reading layer over them. Not a judgement call: partition `slopp.read.query` by the four kinds its own docstring named and every internal call falls INSIDE a cluster, with only the composite driver reads crossing — take those away and it is three disconnected components |
| `:slopp.api/dir`, `/keys`, `/agent-id`, `/warm-spare?`, `/async-image?`, `/branch-image-ttl-ms` | `:slopp.ops/*` | ditto, the tail — `open!`'s option keys, 71 uses. They named module `slopp.api`, which after module 4 held only the WEB TOOLING namespaces, so the keys pointed at code with nothing to do with opening a session. NOT dangling, which is why nothing complained: the module still existed. Same class as `:slopp.api.telemetry/calls` above, and the second time a rename's KEYWORD tail outlived its namespace. (Phase 3 emptied `slopp.api` entirely; phase 2 gives the name to the external API.) |

| `:spa-consequences` · `:stale-client` · `:inline-schema-dup` · `:generated-ns` | `:web-spa-consequences` · `:web-stale-client` · `:web-inline-schema-dup` · `:web-generated-ns` | **R6 in the rule catalog (2026-08-04).** 10 of 34 rules already carried a `web-` prefix and the convention was unenforced; these four were web-only under generic names. The check fns followed (`client-stale-check` → `web-stale-client-check` — that pair was inverted as well as unprefixed). Now pinned by `rules-test/a-rule-owned-by-an-app-type-is-named-for-it`: a rule IMPLEMENTED under an app type's namespace must carry that prefix, and one carrying it must be implemented there. The first three are DECLARED in the vocabulary; the fourth deliberately is not — see below |
| the five web checks in `slopp.rules` | `slopp.rules.web/*` | ditto, and it is what made the guard possible. `spa-consequences`, `client-stale`, `inline-schema-dup`, `web-public-mutation` and `dangling-route-refs` checks sat in the GENERIC rules namespace reading `:web/*` and calling `edit.web/client-signature`. A naming rule cannot see those — a check with no app-type namespace has nothing to disagree with — which is exactly why a hand audit found 2 of the 4 leaks. Moving them made the other two visible, and `slopp.rules` lost its `slopp.edit.web` require entirely |

**`generated-ns` is the measured limit of a spelling-keyed vocabulary.** It is
RETIRED as a rule name and LIVE as a config key (`client`/`generated-ns`, where
`generate_client` writes) — three legitimate shipped-prose mentions, in
`SKILL.md`, `docs/guide/web/client.md` and `docs/reference/config.md`.
Declaring it would flag all three. This is the same family as the
ordinary-English-word limit (`reads`, `effects`) and a distinct member: there
the term is too common to grep, here it is distinctive but means two things.
A `config_file {path "vocabulary"}` row is keyed by SPELLING and cannot tell
them apart, so the two rule-sense mentions were fixed by hand and the row was
not declared.

**R6, the rule the `slopp.webdev` rows follow:** no `slopp.*` surface may
assume a project is a WEB project. Support for an app TYPE lives in a module
named for that type, and the pattern must be replicable for type #2 **without
renaming type #1** — which is the whole reason the tooling module carries a
`web` qualifier instead of taking the generic name.

Two consequences worth stating, because both were learned the expensive way:

- **A generic surface reaching into an app-type module is a bug, not an edge
  to declare.** `slopp.ops.external/full-check!` called `devserver/behind` —
  the whole-store check asking a question that is not web-specific — and
  nothing complained, because both ends were in one module and layering is a
  MODULE-grain question. Now pinned twice:
  `slopp.modules-test/web-tooling-is-reached-only-by-the-transport` over the
  whole image, and `…/the-whole-store-check-names-no-app-type` over the
  surface that broke. `slopp.mcp` is the ONE exception, by role: it is the
  transport, so it reaches every module by construction.
- **The module boundary is what makes the rule checkable at all.** Three times
  in this restructure a drawer hid a violation from the check built to find
  it — the write engine's cljs coupling, this one, and the capability
  registry. The split is not tidiness; it is what turns an invisible R6
  violation into an ordinary layering finding.

## Removed — what an old record refers to that no longer exists

| Gone | What replaced it, if anything |
|---|---|
| `slopp.git.server`, "the git listener", "the embedded listener", "SERVER face" | Nothing. Serving the store to a git client AS a remote was removed (`D-git-push-pull-only`). Git is push/pull to a repo slopp does not own: `slopp.git` projects, `slopp.git.client` transports, `slopp.sync` orchestrates |
| `query_git`'s `:git-url` / `:url` / `:remote` | `query_git` answers about the external remote only — `:external` (`git-remote`/`git-base-sha`), or a refusal naming how to set one |
| `refs/heads/wip/<branch>`, `slopp.git/ensure-wip!` | Nothing. wip refs existed only to be advertised to a client cloning from the listener |
| `:git-server` / `:git-url` session keys | Nothing |

## Config keys

| Old | New | When / why |
|---|---|---|
| `ui.port` | *(retired — `slopp.api.server/derived-port`)* | named for its consumer, renamed to `slopp.api.port`, and then **RETIRED entirely in phase 2 (2026-08-03)**. The plan said "becomes an output — bind a free port, report the number", and that was half right in a way worth keeping straight: the key went, the derivation did not. `D-hub` records why the formula stays (it IS the address; a port that moves strands every saved url), so the port is unconfigured without being unpredictable. What made the knob removable is that nothing set it — the one external adopter never did, and `ui_serve {port}` already covers pinning one run. Retiring it also took `slopp.api → slopp.project` off the module graph: that key was the only reason a generic listener read a project's config |
| `ui.hub-port` | `slopp.hub.port` | the port slopp reaches OUT to, not one it serves |
| `http.enabled` | `web.enabled` | **the whole `http.*` family became `web.*` (2026-08-02, user decision).** `http` names the PROTOCOL; what a project opts into is the WEB feature — routing, auth, static mounts, the client build, ten of the thirty-four rules. A browser session TTL and an OIDC redirect are not HTTP-the-protocol. R6: an app type's support lives under that type's name, so app type #2 gets its own `<type>.enabled` without renaming this one |
| `http.port` | `web.port` | ditto — and `web.port` was already the name the plan and the docs used |
| `http.host` | `web.host` | ditto |
| `http.adapter` | `web.adapter` | ditto. The VALUE stays `http-kit`: that is a library name, not a key |
| `http.max-body-bytes` | `web.max-body-bytes` | ditto |
| `http.static.*` | `web.static.*` | ditto. Distinct from the `slopp.web.static` NAMESPACE, which keeps its `slopp.` prefix |
| `auth.*` | `web.auth.*` | **the whole auth family (2026-08-02).** Measured before moving it: every reader was `slopp.web.auth/config-from-values` or the `web-unknown-group` write gate — nothing generic read it, and three of the four providers (bearer, proxy-header, oidc) are HTTP mechanisms outright. Covers `providers`, `default-policy`, `session.ttl-seconds`, and the `static.*` / `bearer.*` / `proxy.*` / `oidc.*` patterns |
| `groups.*.members` | `web.auth.groups.*.members` | ditto, and under `auth` rather than beside it: a group exists to be named by `:web/auth [:group …]` |

**The rule these two rows install, which is the durable part:** a capability
key's FIRST SEGMENT names its OWNER, and the vocabulary is
`slopp.api.capabilities/owners` — `slopp` (framework, reserved by R1), `app`
(any project), `web` (the web app type). `every-capability-key-declares-its-owner`
refuses a key belonging to nobody, so app type #2 declares its owner and its
keys under that segment instead of adding to a generic pool. That is R6
satisfied for this registry, and it is why `auth.*` moved rather than being
argued about: the honest place to say who owns a key is the key.

## Session and `session_brief` keys

| Old | New | When / why |
|---|---|---|
| `:ui-hub` | `:hub` | this project's own page on the hub, present only while one is answering |
| `:ui-hub-note` | `:hub-note` | the configured address that is silent, or the refusal |
| `:ui-hub-configured` | `:hub-configured` | where we BEAT — known immediately, true whether or not anyone listens |
| `:ui-heartbeat` | `:hub-heartbeat` | the beat's handle. It was never the UI's |

**These are the ones that bit.** The `ui-hub`→`hub` sweep rewrote 19 store
forms and did not touch the files, so `plugins/slopp/skills/slopp/SKILL.md`,
`plugins/slopp/skills/slopp-review/SKILL.md` and `docs/reference/tools.md`
went on documenting `:ui-hub` — a key that returns nil. An agent following the
SHIPPED skill would read the brief, find nothing, and report that no hub was
answering. Fixed 2026-08-02, along with a second error in the same paragraph:
`:hub` already ends in `/p/<slug>`, and slopp-review's template appended it a
second time. The store-prose guard could not see any of it; see
`ideas/restructure-wave-frictions.md` §9.

**R1, the rule these follow:** `slopp.` prefixes framework config keys and the
prefix is RESERVED — a user's app can never own a key under it. Project keys
(`web.*`, `app.*`) carry no prefix. R1 applies to config keys ONLY: not tool
names, not namespaces, not `session_brief` keys.

## Retired phrases (R4)

These name things that still exist; the phrase is what was retired.

| Retired | Say instead |
|---|---|
| "the dev server" | keeping a web project live in development — the mechanism is web tooling's, and it is `live` there |
| "the app server" | the web project's own server, on `web.port` |
| "the reviewer UI" / "reviewer API" | the external API (`slopp.api.*`) and its consumers. A viewing UI is ONE consumer, not the surface's identity |
| "the UI hub" | "a hub" / "the hub". A hub is a role — one process per machine holding a registry fed by heartbeats. Rendering pages is what today's only hub (`slopp-ui`) happens to do with that registry, not what a hub is |
| `D-ui-hub` | `D-hub`. The decision ID was left alone when the phrase was retired on 2026-07-27, on the rule that a dated record keeps its names — but a decision ID is not a dated record, it is an ADDRESS, and later entries in `decisions.md` had already started spelling it `D-hub` while no heading answered to that name. Two spellings for one decision, one of them dangling. Renamed 2026-08-04 |

Naming a piece for its consumer or its current contents is the bug this whole
restructure exists to fix (**R3**). Both change; that is what stranded
`ui.port` and what would have stranded `view`.
