#!/usr/bin/env python3
"""Window-scoped transcript miner for slopp eval rounds.

Sums per-request token usage and the CONTROLLABLE layer (tool inputs +
tool results, chars/4) from Claude Code transcript JSONL files.

Two hard-won rules are built in (Q6 — an ad-hoc version silently summed
rounds sharing a transcript dir and corrupted a comparison):

  1. WINDOW SCOPING — pass --since/--until (epoch seconds) or point at
     explicit files; without a window, every session in the project dir
     is counted, including other rounds run from the same path.
  2. DEDUP BY MESSAGE ID — retries/branches repeat assistant messages;
     usage rows are counted once per message.id.

Usage:
  mine_transcripts.py FILE.jsonl [FILE...]           # explicit transcripts
  mine_transcripts.py --dir ~/.claude/projects/X \
      --since 1783970000 [--until 1783980000]        # mtime-scoped sweep
"""
import argparse
import glob
import json
import os
import sys


def mine(paths):
    seen = set()
    stats = {"api_calls": 0, "in": 0, "out": 0, "cache_read": 0,
             "cache_create": 0, "tool_in_chars": 0, "tool_out_chars": 0}
    for p in paths:
        with open(p) as f:
            for line in f:
                try:
                    d = json.loads(line)
                except json.JSONDecodeError:
                    continue
                m = d.get("message") or {}
                mid = m.get("id")
                u = m.get("usage")
                if u and mid and mid not in seen:
                    seen.add(mid)
                    stats["api_calls"] += 1
                    stats["in"] += u.get("input_tokens", 0)
                    stats["out"] += u.get("output_tokens", 0)
                    stats["cache_read"] += u.get("cache_read_input_tokens", 0)
                    stats["cache_create"] += u.get("cache_creation_input_tokens", 0)
                for c in (m.get("content") or []):
                    if not isinstance(c, dict):
                        continue
                    if c.get("type") == "tool_use":
                        stats["tool_in_chars"] += len(json.dumps(c.get("input", {})))
                    elif c.get("type") == "tool_result":
                        stats["tool_out_chars"] += len(json.dumps(c.get("content", "")))
    stats["controllable_tokens"] = (stats["tool_in_chars"] + stats["tool_out_chars"]) // 4
    return stats


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="*")
    ap.add_argument("--dir", help="transcript dir to sweep (mtime-scoped)")
    ap.add_argument("--since", type=float, help="min mtime (epoch s)")
    ap.add_argument("--until", type=float, help="max mtime (epoch s)")
    a = ap.parse_args()

    paths = list(a.files)
    if a.dir:
        if a.since is None:
            sys.exit("--dir requires --since: unscoped sweeps mix eval rounds "
                     "that share a run path (this exact bug corrupted round 3 "
                     "of eval7 — see benchmarks/results.md)")
        for p in glob.glob(os.path.join(os.path.expanduser(a.dir), "*.jsonl")):
            mt = os.path.getmtime(p)
            if mt >= a.since and (a.until is None or mt <= a.until):
                paths.append(p)
    if not paths:
        sys.exit("no transcripts matched")

    s = mine(paths)
    s["files"] = len(paths)
    print(json.dumps(s, indent=2))


if __name__ == "__main__":
    main()
