#!/usr/bin/env python3
"""The ideas/ backlog must not carry its own history, and its links must resolve.

WHY A CHECK AND NOT A SKILL LINE. `.claude/skills/ideas-backlog/SKILL.md` has
stated the move-to-done rule since 2026-08-06, correctly and in detail. On
2026-08-09 a sweep run BY someone reading that skill still left: two closed
cluster sections marked and unmoved, a finished cluster directory in the open
half, a "Still open" table where six of seven rows were green, a "Still open"
heading whose only two bullets had both shipped, and 21 broken links. The rules
were not the missing thing. This is `.context/design-disciplines.md` Core 4 --
the agent is an unreliable narrator, and the answer is a guarantee rather than
better prose -- and the friction-log skill's own trigger: an invented habit
("I always scan for X now") IS the bug report. That habit was an ad-hoc scan,
retyped three times, each version catching what the last one missed.

WHAT IT CHECKS, AND WHY EACH GRAIN. A finished record left in the open half is
the failure the split exists to prevent: a log where nine of ten items are
fixed scans as nine items of work. The first version of this scan looked at
HEADINGS only and reported clean over a table with six green rows and a section
with two green bullets -- a check computed over a proxy, reporting on the proxy
in the real thing's voice (Core 9). So it reads all three grains.

Link rot is a separate class and nothing was watching it at all: moving `X.md`
to `done/X.md` silently breaks every `ideas/X.md` reference, including ones
inside `done/` itself, which is the half people grep when asking "was this ever
addressed?"

NOT A CI LANE, deliberately -- unlike `check-shipped-prose.sh`. `ideas/` is
gitignored on purpose, so CI has nothing to look at. This exits 0 with a note
when the directory is absent, which is the normal state of a fresh clone.

EXEMPTION IS DECLARED BY THE FILE, not listed here. A file carrying
`<!-- backlog: analysis -->` is a reference document whose sections are status
annotations rather than worklist items, so its green headings stay put. Reading
the declaration keeps this script from becoming the hand-kept list that
`ideas/correspondence/` is about.

Usage: bin/check-ideas-backlog.py [ideas-dir]
"""

import os
import re
import sys

GREEN = "\U0001f7e2"
# A finished record does not always wear the marker. 2026-08-14: a heading
# reading "## RESOLVED 2026-08-14 by a route the options below all missed" sat
# in the open half through a clean run, because the scan looked for the EMOJI
# and the author had spelled it out instead. Same class as the table-row and
# bullet misses above -- a check computed over a proxy (the marker) reporting on
# the proxy in the real thing's voice. So the word counts too, and it is matched
# only where a verdict is DECLARED (start of the line, or right after the
# marker), never mid-sentence, or every entry describing what "resolved" would
# look like would fire.
# A DATE is what separates a verdict from prose. Without it this fires on
# "## Done looks like" (a section title in three GOAL files), "Closed-over
# state", "Done-advisories were..." and "- done note ..." -- eight false
# positives measured, and a checker that cries wolf gets skipped, which is
# worse than the miss it was written for.
FINISHED_WORD = re.compile(
    r"^\s*(?:[-*]\s*|#{2,3}\s*|\|\s*)?[*_]*(?:✅\s*)?[*_]*"
    r"(?:DONE|RESOLVED|FIXED|CLOSED|SHIPPED|LANDED)[*_]*[\s:,—-]+\d{4}-\d{2}-\d{2}",
    re.IGNORECASE,
)
ROOT = sys.argv[1] if len(sys.argv) > 1 else "ideas"
EXEMPT_MARK = "<!-- backlog: analysis -->"

HEADING = re.compile(r"^#{2,3} ")
BULLET = re.compile(r"^\s*[-*] ")
LINK = re.compile(r"`((?:\.\./|ideas/)[A-Za-z0-9_\-/\.]+\.md)`")


def md_files(base):
    for dirpath, _, names in os.walk(base):
        for n in sorted(names):
            if n.endswith(".md"):
                yield os.path.join(dirpath, n)


def declares_finished(line):
    """Does this line DECLARE a verdict (a finished word carrying a date)?

    A table row carries its verdict in whichever cell the table put it, not
    the first -- so cells are tested individually. The emoji check above needs
    no equivalent because it scans the whole line.
    """
    if FINISHED_WORD.match(line):
        return True
    if line.lstrip().startswith("|"):
        return any(FINISHED_WORD.match(c.strip()) for c in line.split("|"))
    return False


def points_at_done(line, nxt=""):
    """Does this line already say where the record went?"""
    return "done/" in line or "done/" in nxt


def main():
    if not os.path.isdir(ROOT):
        print(f"{ROOT}/ not present (gitignored by design) -- nothing to check")
        return 0

    findings = []
    open_files = [f for f in md_files(ROOT) if f"{os.sep}done{os.sep}" not in f]
    all_files = list(md_files(ROOT))

    # 1. A finished record still sitting in the open half, at any grain.
    for f in open_files:
        lines = open(f).read().split("\n")
        if EXEMPT_MARK in "\n".join(lines):
            continue
        for i, line in enumerate(lines):
            if GREEN not in line and not declares_finished(line):
                continue
            nxt = lines[i + 1] if i + 1 < len(lines) else ""
            if points_at_done(line, nxt):
                continue
            if HEADING.match(line):
                grain = "heading"
            elif line.startswith("| "):
                grain = "table row"
            elif BULLET.match(line):
                # a marker legend explains the vocabulary; it is not an item
                if "partial" in line and "open" in line:
                    continue
                grain = "bullet"
            else:
                continue
            findings.append(
                (f, i + 1, f"unmoved {grain}: a finished record in the open half",
                 line.strip()[:88])
            )

    # 2. Link rot, in BOTH halves -- done/ is the half people grep.
    for f in all_files:
        d = os.path.dirname(f)
        for i, line in enumerate(open(f).read().split("\n")):
            for m in LINK.findall(line):
                t = os.path.normpath(os.path.join(d, m)) if m.startswith("../") else m
                if not os.path.exists(t):
                    findings.append((f, i + 1, "broken link", m))

    # 3. A log whose every entry is a pointer: the whole file should have moved.
    for f in open_files:
        heads = [l for l in open(f).read().split("\n") if l.startswith("## ")]
        ptrs = [l for l in heads if "done/" in l]
        if heads and len(heads) == len(ptrs):
            findings.append(
                (f, 1, "every entry is a pointer -- move the whole file", f"{len(heads)} entries")
            )

    scanned = f"{len(all_files)} files ({len(open_files)} open)"
    if not findings:
        print(f"ideas-backlog: clean over {scanned}")
        return 0

    print(f"ideas-backlog: {len(findings)} finding(s) over {scanned}\n")
    for f, ln, what, detail in findings:
        print(f"  {f}:{ln}\n      {what}\n      {detail}")
    print("\nSee .claude/skills/ideas-backlog/SKILL.md for the move-to-done rule.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
