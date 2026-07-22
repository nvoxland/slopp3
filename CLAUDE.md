@AGENTS.md

## Claude Code specifics

Everything above is the shared instruction set, kept in `AGENTS.md` so every
harness reads one copy. Claude Code reads `CLAUDE.md` and not `AGENTS.md`, so
this file imports it. Put shared rules in `AGENTS.md`; only Claude-specific
wiring belongs below.

- **The slopp tools and skills come from the plugin** (`/plugin install
  slopp@slopp`), so `slopp`, `slopp-setup`, `slopp-style` and `slopp-review`
  are available without reading files — invoke them rather than opening
  `plugins/slopp/skills/**/SKILL.md`. Editing those files is a different
  matter: they SHIP, so treat a change to one as a product change.
- **Turns and identity are automatic here.** The plugin's prompt hook records
  the verbatim ask and derives identity from the session id; never call
  `turn_begin` unless a write is refused with `no open turn`.
- **`slopp-reader` is the comprehension subagent.** Delegate broad "how does X
  work / what would Y touch" questions to it so source dumps stay out of the
  main context.
- **The dev session runs a local jar**, not the pinned release:
  `SLOPP_JAR=$PWD/target/slopp.jar`, with `SLOPP_LIVE=1` set in
  `.claude/settings.json`. Rebuild it (`clojure -T:build uber`) only for
  kernel or dependency changes — store code hot-reloads.
