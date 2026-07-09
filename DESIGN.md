# DESIGN BRIEF — Agent-Native Codebase over a Semantic CRDT (working name: **slopp2**)

> **This is a greenfield project.** It is *not* built on the older `slopp` repo. `slopp` is prior art only — borrow one idea from it (the "deterministic choke point": every function is gated by `@examples` it must reproduce exactly), and otherwise ignore it.
>
> **How to use this file:** it is a self-contained design brief. Drop it in the project root (`slopp2/`) and start a fresh session in that directory. Nothing here depends on any external repo.

---

## 1. Thesis

Agents interact with code through a textual read/write interface that is (a) not semantic enough — grep and string-replace don't understand the code — and (b) loses the *"how"*: the operation and reasoning that produced each change aren't encoded in the artifact. This project builds a **vertically integrated, agent-native codebase** where:

- The agent thinks only about **code and how to mechanically change it** (a semantic operation API). It never sees the storage layer.
- Storage is a **CRDT over AST/semantic nodes** (not characters) — an internal implementation detail that provides concurrency, multi-agent/multi-branch, and a per-change provenance history for free.
- The system **owns a persistent live REPL** against the codebase, making the running program a first-class source of truth for verification and understanding.

The bet: a system where **one atomic unit — the top-level form — is shared across editing, storage/merge, hot-reload, verification, and provenance** produces a qualitatively faster and more trustworthy agent authoring loop than text-file editing can.

---

## 2. Confirmed decisions

- **Greenfield, standalone project.** No dependency on `slopp`.
- **CRDT over AST/semantic nodes, not text.** A "delta" is a semantic operation, not a keystroke. CRDT is invisible to the agent.
- **Language = a constrained, agent-only Clojure dialect — treated as the *final* language, not a throwaway prototype substrate.** Because all code is machine-authored, the dialect can *remove* what hurts machine-understanding (arbitrary user macros, unconstrained dynamism) and *require* what helps (Malli/spec contracts at form boundaries).
- **The system owns a persistent running REPL** (lean **JVM Clojure**, not cold Babashka — persistent process pays startup once and has richer live-image tooling). Used for: per-edit test execution tied into history, runtime introspection, and runtime `macroexpand` as the semantic-understanding layer.
- **Choke-point model retained (only idea borrowed from slopp):** each function carries `@examples`; the top-level form is the re-verification unit.
- **Real files still materialize on disk** (so external tools/CI keep working); the CRDT is the system-of-record behind them.

---

## 3. Why this language/architecture (the core argument)

### Three levels — keep distinct
- **L1 characters** — where DeltaDB / text CRDTs live. *We do not live here.*
- **L2 concrete syntax tree / s-expressions** — where the **CRDT lives**. (paredit, rewrite-clj, tree-sitter operate here.)
- **L3 semantic model** — bindings, references, arities, types — where the **agent thinks**.

### Homoiconicity collapses L1↔L2 (the cheap-substrate win)
In Clojure the text *is* the s-expr tree. `rewrite-clj` gives a lossless, whitespace/comment-preserving node tree + zipper API and prints back to real `.clj`. The node schema is tiny and uniform (list/vector/map/symbol/literal) vs. a static language's dozens–hundreds of node kinds. So the CRDT models a small, regular grammar instead of a full parser/printer.

### Unit coherence (the durable moat)
The **top-level form** is simultaneously:

| Layer | Unit |
|---|---|
| CRDT merge | top-level form |
| Hot-reload (`var` redefine) | one `defn` |
| Verification | run the form's `@examples` |
| Provenance | per-form delta |
| Agent edit | one form |

One atom, all the way down. In Go/Rust the reload+verify unit is a whole-package **build** (seconds) and there is no live mutable image. **The AST-CRDT storage half ports to any language via tree-sitter; the live loop does not — and the live loop is the thesis.**

### The running image is the L3 oracle
Don't infer semantics statically — *observe* them by executing (`@examples` supply inputs). `macroexpand` at runtime dissolves the "macros defeat static understanding" problem. The constrained dialect + required contracts make L3 *stronger* than vanilla Clojure or a static language, because it's purpose-built for machine understanding.

---

## 4. Competitive landscape (as of mid-2026)

- **Zed DeltaDB** (Nathan Sobo, June 2026) — the closest analog. CRDT "conflict-free replicated worktrees"; history as fine-grained **deltas** not commits; references anchored to deltas (survive refactors); bidirectional prompt↔code linkage. **But it is a character-level, language-blind text CRDT** — no symbol understanding, no refactoring detection, no query layer. A Zed-forum critic explicitly asks for "semantic units — objects, fields, references" instead of keystrokes: *that unmet ask is this project.*
- **Electric** — "agents as CRDT peers" (Yjs): agent emits constrained tool calls, infra lowers to CRDT ops. Confirms: LLM should emit *readable intent*, not CRDT ops.
- **Serena** (~25k stars) — LSP-over-MCP symbol nav + edit. Proves agents benefit from symbol tools; its community verdict is "obvious win, but setup friction keeps it from being a no-brainer" → **near-zero-config is a hard requirement.**
- **CODESTRUCT** — AST action space on SWE-Bench Verified: +1.2–5.0% Pass@1, **−12–38% tokens**; invalid-patch failures **46.6%→7.2%**. String-replace editing silently fails often.
- **Tree-CRDT prior art** — concurrent *moves* are the hard part (cycles/duplication) but solved & formally verified: Kleppmann move-op (Isabelle/HOL), Loro movable-tree, `codesandbox/crdt-tree`, `trvedata/move-op`.
- **Live-REPL-agent prior art (proof-of-pattern, not to adopt):** `bhauman/clojure-mcp`, `hugoduncan/mcp-clj`, `nrepl-mcp-server` — agent + live nREPL + structural s-expr edit + eval-to-verify. None have CRDT history, multi-agent, form-granular versioning, or provenance. *That gap is the whole project.*

---

## 5. What to build vs. skip

**Build (high confidence):**
- Semantic query layer: symbol/reference/graph queries + **history-aware** queries ("who touched this symbol, via which op, driven by which prompt"). This semantic×history combination is the novel core nobody has shipped.
- Small structural-write set (rename/extract/inline/move/change-signature) modeled on **paredit's vocabulary** (every op valid-tree→valid-tree) **plus a first-class semantic-patch primitive** — the latter is the *common* path, not a fallback (mechanical refactoring covers only ~10% of edits; best coordinated-rename tools cap ~48% F1).
- **Verification-provenance:** test execution as an MCP/CLI tool that records what was proven green at each delta.
- **Multi-agent-as-peer concurrency from day one** (cheap in CRDT, painful to retrofit).
- Keep real files on disk.

**Skip (low ROI):**
- Large refactoring catalog (agents won't reliably invoke 40 named refactorings).
- Making the LLM emit CRDT ops directly.
- A general node-level tree CRDT up front (see §6 dodge).
- Fighting the filesystem.

**Biggest risk:** adoption/install friction (Serena's lesson; Anthropic still ships grep-only). Near-zero-config is mandatory.

---

## 6. Prototype architecture (Phase-1, buildable)

```
   Agent (MCP) ──► Operation API (MCP tools)
                     query.*            (symbol / reference / graph / LINEAGE)
                     edit.rename/extract/... (paredit-style, valid-tree→valid-tree)
                     edit.semantic-patch     (LLM-driven; the common path)
                     test.run                (REPL-backed; result → history)
                        │  every op carries {prompt, agentId, parentDelta, opType}
                        ▼
   CRDT store (semantic nodes)  ── system of record
     • ordered sequence CRDT of identified top-level forms
     • each form: versioned value (or shallow s-expr CRDT where contended)
     • materializes real .clj files via rewrite-clj
                        │  on delta commit → incremental reindex + re-verify touched forms
                        ▼
   Semantic index (history-aware)         Live image (JVM Clojure, persistent nREPL)
     • clj-kondo/clojure-lsp → symbol+ref     • hot-redefine changed var
       graph; nodes ↔ producing deltas        • run form's @examples → green/red → history
     • query: symbol/ref/graph/lineage        • runtime macroexpand / introspection = L3 oracle
```

**The granularity dodge (key pragmatic move):** don't build a fully general tree CRDT. Namespace = ordered sequence CRDT of identified forms → concurrent edits to *different* forms never conflict (the common case). Same-form concurrent edits → surfaced conflict (arguably correct), or a shallow s-expr CRDT only where contention is measured. Reach for the Kleppmann move-op only for cross-form node moves (Phase 4).

---

## 7. Phased plan

- **Phase 0 — Dialect spec + spike.** Define the constrained dialect (see §8). Spike a persistent JVM Clojure nREPL the system owns + rewrite-clj round-trip to disk.
- **Phase 1 — Semantic read + live verify.** clj-kondo/clojure-lsp index; `query.symbol/references/graph`; `test.run` over the live image with results recorded. Lowest risk, immediate value.
- **Phase 2 — Semantic CRDT + provenance.** Form-sequence CRDT; every edit = a delta with `{prompt, agentId, parentDelta}`; files materialized via rewrite-clj; `query.lineage`. The differentiated core.
- **Phase 3 — Structural + semantic writes.** `edit.rename` first (measure vs. string-replace baseline), then extract/inline/move; `edit.semantic-patch` general path. Each edit hot-redefines + re-runs `@examples`; behavior-preserving refactors verified by execution (snapshot outputs → transform → re-run → diff).
- **Phase 4 — Multi-agent / branch.** Concurrent peers, presence, branch/merge; Kleppmann move-op for cross-form moves.

---

## 8. The next open decision — the constrained dialect spec

This ripples through everything (CRDT node schema, what the operation API can assume, how deep static L3 goes before deferring to the runtime oracle). Decide:
- **Allowed built-in forms** (fixed set: `defn/def/let/if/cond/case/->/fn/…`?).
- **User macros:** banned outright, or must ship a semantic descriptor (and/or rely on runtime `macroexpand`)?
- **Contracts:** Malli/spec **mandatory at every form boundary**, or only at `@examples` choke points?
- **Purity:** enforced how? (Load-bearing — clean hot-reload depends on pure/stateless code; state-migration is the classic reload pitfall.)

Nail this and the CRDT schema + operation API mostly fall out of it. **Recommended first task in the new session: draft this dialect spec.**

---

## 9. Honest counterweights (keep in view)
1. Runtime exploration is *sampling*, not proof — a rename found via tracing can miss an unexercised path. Mitigate with mandatory boundary contracts + a comprehensive generated test corpus; don't equate runtime-observed with compiler-proven.
2. Clean hot reload depends on **pure/stateless** code — make purity a hard rule.
3. "System owns the REPL" ⇒ persistent process ⇒ **JVM Clojure**, not cold Babashka.
4. Agent fluency in Clojure < TS/Python (adequate and improving; `@examples` verification gates mitigate).

---

## 10. Verification (how to prove the thesis early)
- **Phase 1:** agent answers "where is X referenced / run X's tests" without reading whole files; compare token cost vs. grep baseline; `test.run` results land in history.
- **Phase 2:** apply N deltas → reconstruct file == disk file; every node resolves to a producing delta + prompt; `query.lineage` returns the provenance chain.
- **Phase 3:** rename across cross-referenced forms → all refs updated AND `@examples` still green via the live image; behavior-diff shows preservation; compare correctness vs. an LLM string-replace baseline (expect the CODESTRUCT-style gap).
- **Phase 4:** two simulated agents edit different forms concurrently → clean merge, both forms' examples green.

---

## Sources
- Zed DeltaDB — https://zed.dev/blog/introducing-deltadb
- Agents as CRDT peers (Yjs) — https://electric.ax/blog/2026/04/08/ai-agents-as-crdt-peers-with-yjs
- Serena — https://github.com/oraios/serena ; landscape https://rywalker.com/research/code-intelligence-tools
- CODESTRUCT — https://arxiv.org/pdf/2604.05407 ; RefactorBench https://arxiv.org/html/2503.07832v1
- Tree-CRDT move op — https://martin.kleppmann.com/2021/10/07/crdt-tree-move-operation.html ; Loro https://loro.dev/blog/movable-tree ; JSON-CRDT moves https://arxiv.org/pdf/2311.14007
- Live-REPL-agent prior art — https://github.com/bhauman/clojure-mcp ; https://github.com/hugoduncan/mcp-clj
- rewrite-clj — https://github.com/clj-commons/rewrite-clj
