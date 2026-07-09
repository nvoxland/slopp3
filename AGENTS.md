# Agent instructions (Codex and other AGENTS.md-reading harnesses)

This repo is **slopp** — an agent-native codebase system. Two distinct modes
of working here:

## 1. Working ON slopp (this repo's own Clojure code)

Read `CLAUDE.md` and the `.context/` knowledge base — those rules apply to
you too: read the relevant `.context/<subsystem>.md` before touching code,
update it in the same commit, red/green TDD always, full suite via
`clojure -M:test`, and never credit an AI in commit messages.

## 2. Working THROUGH slopp (authoring code in a slopp store)

Connect the MCP server (see README for Codex `config.toml` / Claude Code
`.mcp.json` recipes) and read `skills/slopp/SKILL.md` FIRST — it teaches the
efficient workflow: orient with `query_namespaces`/`query_outline` (never
read whole sources to orient), write one form at a time with an intent
`prompt`, use `edit_group` for multi-form changes, `edit_rename` for renames,
trust the verification attached to every write, and `checkpoint` when a unit
of work is done (it normalizes, lints, and marks the boundary).
