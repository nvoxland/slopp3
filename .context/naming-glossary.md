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

## Namespaces

| Old | New | When / why |
|---|---|---|
| `slopp.ui-api.*` | `slopp.http-api.*` | named for its consumer (a UI); the consumer changed and the name was stranded. **Intermediate** — phase 2 renames it again to `slopp.api.*` once the current occupant moves |
| `slopp.api.artifacts` | `slopp.store.artifacts` | bytes live on disk, the store holds the sha and the recipe — that is the store's subject |
| `slopp.edit.refs` | `slopp.index.refs` | THE reference graph is derived, content-memoized and never stored: `slopp.index`'s genre, not the edit pipeline's. Side effect: the edit pipeline no longer touches `slopp.cache` at all |
| `slopp.api.crossings` | `slopp.index.crossings` | its pair — `refs` answers every edge INSIDE the store, `crossings` the edges that LEAVE it. Landed together so the pair is one module |
| `slopp.store.build` | `slopp.build` | not the store: the GraalVM native-image build target. Pure generators, zero internal requires, three callers in three different modules — shared layer-0 infrastructure |
| `slopp.http-api.heartbeat` | `slopp.hub` | registering and beating is HUB INTEGRATION, not part of the generic external API — every project on slopp talks to a hub, slopp itself included. `slopp.http-api.contracts/project-beat` came with it (→ `slopp.hub/project-beat`): the beat's shape describes the HUB's `POST /api/register`, so it was the one non-API contract in the API's registry |

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
| `ui.port` | `slopp.api.port` | named for its consumer. Becomes an **output** in phase 2 — bind a free port, report the number |
| `ui.hub-port` | `slopp.hub.port` | the port slopp reaches OUT to, not one it serves |
| `http.enabled` | `web.enabled` | **the whole `http.*` family became `web.*` (2026-08-02, user decision).** `http` names the PROTOCOL; what a project opts into is the WEB feature — routing, auth, static mounts, the client build, ten of the thirty-four rules. A browser session TTL and an OIDC redirect are not HTTP-the-protocol. R6: an app type's support lives under that type's name, so app type #2 gets its own `<type>.enabled` without renaming this one |
| `http.port` | `web.port` | ditto — and `web.port` was already the name the plan and the docs used |
| `http.host` | `web.host` | ditto |
| `http.adapter` | `web.adapter` | ditto. The VALUE stays `http-kit`: that is a library name, not a key |
| `http.max-body-bytes` | `web.max-body-bytes` | ditto |
| `http.static.*` | `web.static.*` | ditto. Distinct from the `slopp.web.static` NAMESPACE, which keeps its `slopp.` prefix |

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
| "the UI hub" | "a hub" / "the hub". A hub is a role — one process per machine holding a registry fed by heartbeats. Rendering pages is what today's only hub (`slopp-ui`) happens to do with that registry, not what a hub is. `D-ui-hub` keeps its name as a decision ID |

Naming a piece for its consumer or its current contents is the bug this whole
restructure exists to fix (**R3**). Both change; that is what stranded
`ui.port` and what would have stranded `view`.
