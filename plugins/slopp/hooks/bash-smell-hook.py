#!/usr/bin/env python3
"""Bash hook: when the working dir is a slopp store and a Bash command routes
around it, redirect the agent to the surface that answers the question.

TWO events, deliberately:

- PreToolUse DENIES raw store.db reads. sqlite3 against the store is the one
  smell with no legitimate in-session use: the journal is the record of truth,
  a direct read can see torn/uncommitted state, and query_store answers the
  same question over the immutable store VALUE. A block costs the agent
  nothing, while the old post-hoc nudge arrived after the tokens were already
  spent — eval9 measured an agent paying for three sqlite3 calls and getting
  one hindsight hint, because the 30-minute cooldown swallowed the rest.

- PostToolUse ADVISES on the others (git archaeology, file greps of source,
  shell evals). Those have legitimate uses — the git projection is real and a
  user may genuinely want it — so they nudge rather than block, keeping the
  anti-spam contract: one line, 30-minute cooldown per smell per store.

Silent on any failure: a hook that breaks a session is worse than a missed
hint."""
import json
import os
import re
import sys
import time

RAW_DB = r"sqlite3\s+[^|;&]*store\.db"

RAW_DB_REASON = (
    "[slopp] Reading .slopp/store.db with sqlite3 is blocked. The journal is "
    "the record of truth and a direct read can see torn or uncommitted state. "
    "The store answers this itself: "
    "query_store {code \"(fn [store] ...)\"} for read-only analysis over the "
    "immutable store VALUE (deltas, namespaces, metadata sweeps); "
    "report for milestones + per-form changes with their recorded asks, the "
    "verbatim user :intents, and :code (the follow-up that carries source); "
    "query_history {contains X} / {dead_ends true} for the asks and the "
    "scrapped explorations. "
    "NO MCP TOOLS AVAILABLE (server not connected)? Those same tools run "
    "one-shot from the shell: slopp --call query_store '{\"code\":\"...\"}', "
    "slopp --call report '{}'. That is the fallback — not sqlite3. "
    "If you truly need the raw file for store forensics, do it outside a "
    "slopp session."
)

SMELLS = [
    ("git-archaeology", r"\bgit\s+(-\S+\s+)*(log|diff|show|blame)\b",
     "[slopp] history lives in the store: query_changes {from \"start\"} gives "
     "every form's :was/:now across the lifetime (or {from \"last-commit\"}), "
     "format=text for line diffs; report {since} composes the summary — the "
     "git slopp branch is only a projection"),
    ("file-source", r"\b(cat|grep|rg|sed|awk|head|tail|less)\b[^|;&]*\.clj",
     "[slopp] the store is the source — query_search {pattern}, "
     "query_source {targets [{ns name}]}, or query_slice {ns name} read it; "
     ".clj files here may be stale projections"),
    ("shell-eval", r"\b(clojure|clj)\b[^|;&]*\s-e\b",
     "[slopp] query_eval runs code against the LIVE image (namespaces "
     "pre-loaded, no JVM spin-up)"),
]

COOLDOWN_S = 30 * 60


def deny(reason):
    json.dump({"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "deny",
        "permissionDecisionReason": reason}}, sys.stdout)


def advise(msg):
    json.dump({"hookSpecificOutput": {
        "hookEventName": "PostToolUse",
        "additionalContext": msg}}, sys.stdout)


def cooled_down(key):
    """True when this smell fired recently — stay quiet."""
    path = ".slopp/bash-hint-cooldowns.json"
    now = time.time()
    try:
        cools = json.load(open(path))
    except Exception:
        cools = {}
    if now - cools.get(key, 0) < COOLDOWN_S:
        return True
    cools[key] = now
    try:
        json.dump(cools, open(path, "w"))
    except Exception:
        pass
    return False


def main():
    try:
        d = json.load(sys.stdin)
        cmd = (d.get("tool_input") or {}).get("command", "")
        if not cmd or not os.path.exists(".slopp/store.db"):
            return
        event = d.get("hook_event_name") or ""

        if event == "PreToolUse":
            # the one hard rule — no cooldown; a block should teach every time
            if re.search(RAW_DB, cmd):
                deny(RAW_DB_REASON)
            return

        # PostToolUse: advisory smells (raw-db is handled by the deny above)
        hit = next(((k, m) for k, p, m in SMELLS if re.search(p, cmd)), None)
        if not hit:
            return
        key, msg = hit
        if not cooled_down(key):
            advise(msg)
    except Exception:
        pass


if __name__ == "__main__":
    main()
