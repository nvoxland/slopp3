# Working with git

The store is the source. What git tracks is a **projection**: at every
milestone slopp renders the store as ordinary `.clj` files and mints a
deterministic commit from it. The result is a repo you can browse, review, and
PR against like any other.

## slopp owns exactly one branch

Store config `git-branch`, default `slopp`. slopp pushes and pulls that branch
and never touches anything else.

`main` is human-owned. READMEs, docs, CI workflows, editor config, scripts --
normal git files, edited with your usual tools and committed by you. Merge
`slopp` into `main` (or cherry-pick) when you want the code side updated, and
merge `main` edits back for slopp to absorb.

This split is deliberate. slopp's per-write verification, history and reverts
protect **store code** only. A shell one-liner that clobbers a plain file has
no such safety net, so edit those carefully and lean on git for their history.

## The local mirror is automatic

In a git checkout, every `commit_point` mirrors the store's history into local
git as `slopp/<store-branch>` -- for example `slopp/main`. The repo durably
carries slopp history with zero ceremony, and you can inspect it with normal
git commands.

Publishing to a remote stays explicit.

## Push, pull, clone

```clj
git_push {url?}      ;; first url becomes the saved default; one-off urls never rewrite it
git_pull
git_clone {url dir}
git_conflicts
git_resolve {path}
```

- **`git_push`** sends your `slopp/<branch>` mirrors up from a checkout.
  Fast-forward only, never force. A fileless store (no `.git`) publishes its
  projection directly. Pushing onto a branch your working tree has checked out
  is refused -- the ref would move under you.
- **`git_pull`** fetches the remote's mirrors down and absorbs remote history
  by a 3-way merge **at form granularity**. The remote wins where your store is
  clean; anything both sides touched becomes a conflict. Your version stays
  live, the remote file is quarantined, and pushes are blocked until you
  resolve with the edit tools plus `git_resolve`. It is exactly git's model,
  one unit coarser.
- **`git_clone`** rebuilds a fileless store from a remote and grafts onto the
  remote's history so later pushes fast-forward.
- **`query_git`** and **`query_commits`** show the projection state and prove
  store/git alignment -- the branch head against the latest milestone's minted
  sha.

Two stores collaborating through one repo, including edits made directly in
GitHub's web editor, is a tested workflow, not a theoretical one.

## Local-only mode

Set `config git-remote "."` and slopp pushes into your own checkout's `.git` as
the `slopp` branch. You do all origin interaction with regular git. This is what
`slopp --main slopp.sync/-main import .` configures, and it is the friendliest
setup for a repo you clone normally and want to keep working on with git.

## Authorship

Milestones are stamped with the store's configured identity:

```clj
config {key "user.name"  value "Ada Lovelace"}
config {key "user.email" value "ada@example.com"}
```

The value `"<git>"` defers to your git config.

## CI for a slopp repo

GitHub only runs push-triggered workflows from the pushed ref's tree, so a
workflow living on `main` reaches the `slopp` branch through
`workflow_dispatch` or `schedule` with `checkout ref: slopp`.

The usual shape is checkout, then `import` (or check out the `slopp` branch
directly), then run the suite:

```yaml
- run: slopp --main slopp.sync/-main import .
- run: slopp --main slopp.sync/-main test .
```

slopp's own CI runs both faces of this: the suite straight from the repo's
files, and the pushed code importing *itself* into a fresh store -- every
namespace through every gate -- then running the store-built suite. See
[CLI and CI](cli.md).
