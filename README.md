# slopp

An **agent-native codebase**: code lives in a store (SQLite-backed delta log),
not files; the top-level form is the unit of editing, storage, hot-reload,
verification, and provenance; a live JVM image runs the code continuously and
verifies every write the moment it lands. Agents address code as
`namespace/form` — no files, paths, or line numbers.

- Design + decisions: `.context/` (start with `architecture.md`, `decisions.md`)
- Agent workflow guide: `skills/slopp/SKILL.md`
- Benchmarks & baselines: `benchmarks/results.md`

## Requirements

Clojure CLI + Java 21+. (`SLOPP_CLOJURE` env var overrides the `clojure`
launcher path if it's somewhere unusual.)

## Connect from Claude Code

Project-scope config ships in this repo (`.mcp.json`); Claude Code picks it
up automatically and the tools appear as `mcp__slopp__*`. For another
project, register manually:

```bash
claude mcp add slopp -- clojure -M -m slopp.mcp /path/to/project-store
```

The optional last argument is the store directory (durable session at
`<dir>/.slopp/store.db`); omit for an ephemeral session.

## Connect from Codex

Add to `~/.codex/config.toml`:

```toml
[mcp_servers.slopp]
command = "clojure"
args = ["-M", "-m", "slopp.mcp", "/path/to/project-store"]
```

Repo-level agent instructions live in `AGENTS.md` (Codex reads it
automatically); point the agent at `skills/slopp/SKILL.md` before its first
tool call.

## CLI / scripting (HTTP transport)

The same tool dispatch over localhost HTTP — for shell scripts, debugging,
and harness evals:

```bash
clojure -M -m slopp.http 7357 /path/to/project-store &
curl -s -X POST localhost:7357/call \
  -d '{"name":"query_namespaces","arguments":{}}'
curl -s localhost:7357/metrics    # per-call payload sizes
```

## Multi-agent: a server per agent, ONE shared store (recommended)

Every Claude Code / Codex instance spawns its OWN slopp server against the
same project dir — zero shared infrastructure, and it's just the normal
`.mcp.json`:

```json
{"mcpServers": {"slopp": {"command": "clojure",
                          "args": ["-M", "-m", "slopp.mcp", "/abs/path/to/project"]}}}
```

The SQLite journal is the record: commits are conditional appends (losers
rebase; same-form races surface `{:conflict ...}`), and every server absorbs
the others' work before each tool call — cache, live image, and all. Each
agent gets a PRIVATE checkout: branch_create / branch_switch are per-server
state, so one agent lives on `feature` while another keeps `main` green,
sharing branch storage under `.slopp/branches/`.

### Automatic turn provenance (recommended hooks)

Real servers refuse writes without an open turn (the journal must know the
user's ask). Wire it so the model never has to relay its own instructions —
per agent workspace, `.claude/settings.json`:

```json
{"hooks": {
  "UserPromptSubmit": [{"hooks": [{"type": "command",
    "command": "cd /abs/slopp-repo && clojure -M -m slopp.turn /abs/project hook-begin alice"}]}],
  "Stop": [{"hooks": [{"type": "command",
    "command": "cd /abs/slopp-repo && clojure -M -m slopp.turn /abs/project hook-end alice"}]}]}}
```

The hook reads Claude Code's JSON from stdin and appends the turn marker
(with the VERBATIM prompt) straight to the journal; the agent's server
absorbs it before its next tool call.

## Multi-agent: many clients, ONE server (alternative)

The HTTP server also speaks native MCP at `/mcp` (streamable HTTP). Point
any number of Claude Code / Codex instances at the SAME server and they
share one store and one live image — concurrent edits to different forms
all land (no locks); a same-form race surfaces `{:conflict ...}` to the
loser ("re-read and retry"):

```bash
clojure -M -m slopp.http 7357 /path/to/project-store &
```

```json
// each agent's .mcp.json
{"mcpServers": {"slopp": {"type": "http", "url": "http://localhost:7357/mcp"}}}
```

Give each agent an identity by passing `agent` on write calls (e.g.
`{"agent": "alice"}`) — every delta records who did what, and
`query_history` shows it.

## Development

```bash
clojure -M:test        # full suite (spawns real child JVM images; minutes)
clojure -M -m slopp.benchmark   # sample-app benchmark (see .context/dogfooding.md)
```
