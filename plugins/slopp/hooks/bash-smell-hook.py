#!/usr/bin/env python3
"""PostToolUse(Bash) smell hook: when the working dir is a slopp store and
a Bash command routes around it (raw sqlite, git archaeology, file greps
of source, ad-hoc clojure evals), inject a one-line redirection via
additionalContext (verified channel, 2026-07-14). Same anti-spam contract
as the in-band registry: one line, 30-minute cooldown per smell per
store, suggestions never blocks. Silent on any failure."""
import json
import os
import re
import sys
import time

SMELLS = [
    ("raw-db", r"sqlite3\s+\S*store\.db",
     "[slopp] raw store.db reads bypass the query surface — query_search / "
     "query_history / report answer these, and told-tracking makes re-asks free"),
    ("git-archaeology", r"\bgit\s+(-\S+\s+)*(log|diff|show|blame)\b",
     "[slopp] history lives in the store: query_history routes by args "
     "({ns name} a form's life, {contains} which asks touched X); "
     "report {since} composes the whole summary — the git slopp branch is a projection"),
    ("file-source", r"\b(cat|grep|rg|sed|awk|head|tail|less)\b[^|;&]*\.clj",
     "[slopp] the store is the source — query_search {pattern}, "
     "query_source {targets [{ns name}]}, or query_slice {ns name} read it; "
     ".clj files here may be stale projections"),
    ("shell-eval", r"\b(clojure|clj)\b[^|;&]*\s-e\b",
     "[slopp] query_eval runs code against the LIVE image (namespaces "
     "pre-loaded, no JVM spin-up)"),
]

COOLDOWN_S = 30 * 60


def main():
    try:
        d = json.load(sys.stdin)
        cmd = (d.get("tool_input") or {}).get("command", "")
        if not cmd or not os.path.exists(".slopp/store.db"):
            return
        hit = next(((k, m) for k, p, m in SMELLS if re.search(p, cmd)), None)
        if not hit:
            return
        key, msg = hit
        path = ".slopp/bash-hint-cooldowns.json"
        now = time.time()
        try:
            cools = json.load(open(path))
        except Exception:
            cools = {}
        if now - cools.get(key, 0) < COOLDOWN_S:
            return
        cools[key] = now
        try:
            json.dump(cools, open(path, "w"))
        except Exception:
            pass
        json.dump({"hookSpecificOutput": {"hookEventName": "PostToolUse",
                                          "additionalContext": msg}},
                  sys.stdout)
    except Exception:
        pass


if __name__ == "__main__":
    main()
