#!/usr/bin/env python3
"""UserPromptSubmit hook: capture the verbatim ask for slopp's turn
machinery, and inject a ~40-token micro-brief so agents never open with
the ls/git/README scouting ritual (the server already knows the answers).
stdout becomes prompt context; failures are silent — the hook must never
block a prompt."""
import json
import os
import re
import sqlite3
import sys

try:
    d = json.load(sys.stdin)
    os.makedirs(".slopp", exist_ok=True)
    with open(".slopp/pending-intent", "w") as f:
        json.dump({"session-id": d.get("session_id", ""),
                   "prompt": d.get("prompt", "")}, f)
except Exception:
    pass

try:
    if os.path.exists(".slopp/store.db"):
        c = sqlite3.connect("file:.slopp/store.db?mode=ro", uri=True)
        n = c.execute("SELECT COUNT(DISTINCT ns) FROM elements").fetchone()[0]
        row = c.execute("SELECT payload FROM deltas WHERE op='commit' "
                        "ORDER BY seq DESC LIMIT 1").fetchone()
        done_row = c.execute("SELECT payload FROM deltas WHERE op='done' "
                             "ORDER BY seq DESC LIMIT 1").fetchone()
        c.close()
        if n:
            m = re.search(r':description "((?:[^"\\]|\\.)*)"', row[0]) if row else None
            desc = m.group(1) if m else "none yet"
            if len(desc) > 80:
                desc = desc[:80] + "…"
            warn = ""
            if done_row and ":findings" in done_row[0]:
                fm = re.search(r":failures (\d+)", done_row[0])
                lm = re.search(r":lint-errors (\d+)", done_row[0])
                fails = int(fm.group(1)) if fm else 0
                lints = int(lm.group(1)) if lm else 0
                if fails or lints:
                    warn = (f" HEADS-UP: the last done-point left {fails} failing"
                            f" test(s) and {lints} lint error(s) — session_brief"
                            f" :last-done has details.")
            print(f"[slopp] live store here: {n} namespaces; last milestone: "
                  f"{desc}.{warn} Orient with session_brief, work through the slopp "
                  f"tools (the store is the source, not the files).")
except Exception:
    pass
