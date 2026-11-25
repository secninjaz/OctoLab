#!/usr/bin/env python3
"""F-Droid's "What's new" for a version, from CHANGELOG.md (#347).

Writes metadata/en-US/changelogs/<versionCode>.txt: that version's section, with the GitHub
references only (as on GitHub, see changelog_refs.py), as plain text: links reduced to their
text, backticks removed, headings as "Added:", items as "• …". F-Droid shows at most 500
characters: whole items, first each section's first one, then the rest in order, a "…" when
some are left out, and always a link to GitHub's releases, every version's changes.

Usage: fdroid_changelog.py <versionName> <versionCode>
"""
import os
import re
import sys

sys.dont_write_bytecode = True  # no __pycache__ in scripts/
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from changelog_refs import REFS, keep  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIMIT = 500 - 1  # the file's final newline counts too
LINK = re.compile(r"\[([^\]]+)\]\([^)\s]+\)")


def section(text, version):
    """The lines under "## [version]", up to the next version."""
    lines, found = [], False
    for line in text.splitlines():
        if line.startswith("## ["):
            if found:
                break
            found = line.startswith("## [%s]" % version)
            continue
        if found:
            lines.append(line)
    if not found:
        sys.exit("No section for %s in CHANGELOG.md" % version)
    return lines


def plain(line):
    return LINK.sub(r"\1", line).replace("`", "").strip()


def blocks(lines):
    """"Added:", "• …" lines, references filtered for GitHub."""
    replace = keep("github")
    out = []
    for line in lines:
        line = REFS.sub(replace, line.rstrip())
        if line.startswith("### "):
            out.append(plain(line[4:]) + ":")
        elif line.startswith("- "):
            out.append("• " + plain(line[2:]))
        elif line.strip() and out and out[-1].startswith("• "):
            out[-1] += " " + plain(line)  # a wrapped item
    return out


REPO = "github.com/secninjaz/OctoLab"
SHORTEST = 60  # a shortened item keeps at least this much


def trim(lines, version):
    """What fits in LIMIT, always ending with the link to all changes: first each section's
    first item, then the rest in order; whole items only, sections in their order."""
    # The releases page: every version's changes, this one first
    link = "All changes: %s/releases" % REPO
    sections = []  # [heading, [items]]
    for line in lines:
        if line.startswith("• ") and sections:
            sections[-1][1].append(line)
        else:
            sections.append([line, []])
    # Which items to keep: the first of each section, then the others, while they fit
    order = [(s, 0) for s in range(len(sections)) if sections[s][1]]
    order += [(s, i) for s in range(len(sections)) for i in range(1, len(sections[s][1]))]
    kept = set()

    def render(chosen):
        out = []
        for s, (heading, items) in enumerate(sections):
            shown = [items[i] for i in range(len(items)) if (s, i) in chosen]
            if shown:
                out.append(heading)
                out.extend(shown)
        if len(chosen) < len(order):
            out.append("…")
        out.append(link)
        return "\n".join(out)

    # Every section with its first item: when they don't all fit, the longest of those
    # items is shortened at a word, with "…", until they do (never below SHORTEST)
    firsts = [pick for pick in order if pick[1] == 0]
    while len(render(set(firsts))) > LIMIT:
        s_i = max(firsts, key=lambda pick: len(sections[pick[0]][1][0]))[0]
        item = sections[s_i][1][0]
        if len(item) <= SHORTEST:
            break
        sections[s_i][1][0] = item[:-2].rsplit(" ", 1)[0].rstrip(",;") + "…"
    for pick in order:
        if len(render(kept | {pick})) <= LIMIT:
            kept.add(pick)
    text = render(kept)
    if not kept:  # one very long first item: cut at a word, then the link
        first = sections[0][1][0] if sections and sections[0][1] else lines[0]
        room = LIMIT - len("\n…\n" + link) - 1
        text = first[:room].rsplit(" ", 1)[0] + "…\n" + link
    return text


def main():
    if len(sys.argv) != 3 or not sys.argv[2].isdigit():
        sys.exit(__doc__)
    version, code = sys.argv[1], sys.argv[2]
    with open(os.path.join(ROOT, "CHANGELOG.md"), encoding="utf-8") as f:
        lines = blocks(section(f.read(), version))
    if not lines:
        sys.exit("The section for %s is empty" % version)
    out_dir = os.path.join(ROOT, "metadata", "en-US", "changelogs")
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, code + ".txt")
    with open(path, "w", encoding="utf-8") as f:
        f.write(trim(lines, version) + "\n")
    print(path)


if __name__ == "__main__":
    main()
