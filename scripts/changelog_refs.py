#!/usr/bin/env python3
"""Release notes with the issue references of the site they're published on.

CHANGELOG.md entries end with their references, e.g.
  - The label filter works (#330, [GitHub #3](https://github.com/secninjaz/OctoLab/issues/3))
The internal tracker's numbers mean nothing on GitHub, where "#3" would even link to a
different issue; GitHub's issues aren't the internal tracker's. So:
  gitlab: keeps "#330", drops the GitHub links  -> "(#330)"
  github: keeps the GitHub links, drops "#330"  -> "([GitHub #3](…))"
A parenthesis left empty goes, with the space before it. Other parentheses stay as they are.

Usage: changelog_refs.py gitlab|github < notes.md > notes-for-site.md
"""
import re
import sys

GITHUB_REF = re.compile(r"^\[GitHub #\d+\]\(https://github\.com/[^)\s]+/issues/\d+\)$")
INTERNAL_REF = re.compile(r"^#\d+$")
# A parenthesis whose parts are all references: "(#330)", "(#330, [GitHub #3](…))"
REFS = re.compile(r" ?\(((?:#\d+|\[GitHub #\d+\]\([^)\s]+\))"
                  r"(?:, (?:#\d+|\[GitHub #\d+\]\([^)\s]+\)))*)\)")


def keep(site):
    wanted = GITHUB_REF if site == "github" else INTERNAL_REF

    def replace(match):
        parts = [p for p in match.group(1).split(", ") if wanted.match(p)]
        if not parts:
            return ""
        space = " " if match.group(0).startswith(" ") else ""
        return space + "(" + ", ".join(parts) + ")"
    return replace


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ("gitlab", "github"):
        sys.exit(__doc__)
    replace = keep(sys.argv[1])
    for line in sys.stdin:
        sys.stdout.write(REFS.sub(replace, line))


if __name__ == "__main__":
    main()
