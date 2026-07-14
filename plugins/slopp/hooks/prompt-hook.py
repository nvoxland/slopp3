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
        c.close()
        if n:
            m = re.search(r':description "((?:[^"\\]|\\.)*)"', row[0]) if row else None
            desc = m.group(1) if m else "none yet"
            if len(desc) > 80:
                desc = desc[:80] + "…"
            print(f"[slopp] live store here: {n} namespaces; last milestone: "
                  f"{desc}. Orient with session_brief, work through the slopp "
                  f"tools (the store is the source, not the files).")
except Exception:
    pass
