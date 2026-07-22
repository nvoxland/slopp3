# The store

Everything slopp knows lives in `<project>/.slopp/store.db`: a SQLite journal
in WAL mode holding the ordered forms of every namespace, the delta log that
produced them, the module and dependency manifests, tracked non-code files, and
branch snapshots.

Gitignore it. What you share is the [git projection](git.md).

## The journal is the record of truth

A durable commit is a conditional append -- deltas, the touched element rows,
and the id counter, in one transaction, applied only if the head still matches
the commit's base. The in-memory store is a cache that can only ever trail the
journal. There is no persist queue and no background flush: the append is the
persist.

A writer that loses the race refreshes and rebases. That is what makes the next
property work.

## Many servers, one store

Each agent's MCP client spawns its own server, and they all share the journal.
Before every tool call a server checks the journal's `data_version` and absorbs
anything foreign -- into its cache, its live image, and its trace map.

Work on different forms rebases and lands. Two writers racing on the *same*
form surface a `{:conflict}` to whichever one is stale. This is the normal
operating model, not a special multi-agent mode.

## Reading source

There are no `.clj` files, so reads go through the VFS, which renders a
namespace's forms back to lossless source (comments and whitespace included)
and memoizes the result.

| Question | Call |
|---|---|
| What is in this store at all? | `session_brief` |
| What is in this namespace? | `query_source {ns}` -- outline by default, `full: true` for everything |
| The source of specific forms | `query_source {targets [{ns name} ...]}` |
| The form I am about to edit, plus what it calls | `query_slice {ns name}` |
| Where is X mentioned? | `query_search {pattern}` |
| Everything, in one call | `query_project` |

`query_slice` is the read that matters most. It returns the full source of one
form plus interface cards -- signature, docstring, recorded reason, test
warranty -- for everything that form reaches. You do not need a callee's body to
call it; if an assumption is wrong the write turns red and names which of your
changes each failing test implicates.

On a giant form, `query_slice {ns name match window}` returns the neighbourhood
instead of the whole thing.

Re-reading is cheap on purpose: an unchanged view comes back as a small
`:unchanged` stub, so re-fetching beats carrying source around.

## Getting real files out

```sh
slopp --call build '{"dir":"/tmp/proj-out"}'
```

`build` materializes the store as ordinary files -- source, a generated
`deps.edn` from the dependency manifest, tracked files from the files manifest,
and the module manifest as a read-only `modules` file. With a `main` argument it
also emits a GraalVM native-image recipe.

Use it when outside tooling needs a tree: a native build, a one-off run under
some tool that insists on a classpath directory, or a look at what the
projection will contain. It is a one-way export -- nothing reconciles files back
into the store. Code comes back in through [`git_pull`](git.md), which merges at
form granularity.

## Non-code files

Some files genuinely need to ride the projection: a build script, a jar
manifest. Two mechanisms:

- `file_put {path content}` / `file_remove` / `file_list` / `file_get` --
  opaque files on the files manifest, with `file_history` giving them the same
  per-change history forms get.
- `config_file {path key value format}` -- *semantic* key/value config, stored
  per key with per-key history and serialized into the right format at
  projection time. `format: "manifest"` covers `META-INF/MANIFEST.MF`.

The rule of thumb: config the application itself consumes belongs in the
store; external-system config (CI workflows, READMEs, editor settings) belongs
on the human-owned git branch. See [dependencies and config](deps-and-config.md).

## What not to do

Do not open `store.db` with a SQLite client to answer a question. Every shape
in it has a tool, and the raw rows are a delta log -- reading them without the
fold gives you a wrong answer confidently.

For questions no canned query covers, `query_store "(fn [store] ...)"` runs a
read-only pure function over the immutable store value: counts, metadata
sweeps, custom aggregation. No effects, no interop, fully qualified symbols.
