#!/usr/bin/env python3
"""THE Obtainium links of OctoLab (#310): one place builds them, so every link (README, GitHub
and GitLab release notes) has the same, working form, and --check fails CI if one doesn't.

Form: https://apps.obtainium.imranr.dev/redirect?r=<the obtainium:// address, URL-encoded>.
Obtainium's site stopped accepting the unencoded address on redirect.html (2026-11), which
broke the README badge and the GitHub release notes while the GitLab ones, built here, worked.

  obtainium_link.py public              the public app, from its GitHub releases
  obtainium_link.py internal-release    the internal app (needs CI_PROJECT_URL, CI_PROJECT_NAMESPACE)
  obtainium_link.py internal-debug      the internal debug build (same)
  obtainium_link.py --check [files…]    fails if a link to Obtainium's site isn't in this form
"""
import json
import os
import re
import subprocess
import sys
import urllib.parse

SITE = "https://apps.obtainium.imranr.dev/redirect?r="
GITHUB = "https://github.com/secninjaz/OctoLab"


def link(obtainium_address):
    return SITE + urllib.parse.quote(obtainium_address, safe="")


def internal(debug):
    settings = {
        "fallbackToOlderReleases": True, "trackOnly": False,
        "versionExtractionRegEx": r"v(\d+\.\d+\.\d+-.+)" if debug else r"v(\d+\.\d+\.\d+)$",
        "matchGroupToUse": "1", "versionDetection": True, "releaseDateAsVersion": debug,
        "useVersionCodeAsOSVersion": False, "apkFilterRegEx": "debug" if debug else "",
        "invertAPKFilter": False, "autoApkFilterByArch": True, "appName": "", "appAuthor": "",
        "shizukuPretendToBeGooglePlay": False, "allowInsecure": False,
        "exemptFromBackgroundUpdates": False, "skipUpdateNotifications": False, "about": "",
        "refreshBeforeDownload": False,
    }
    config = {
        "id": "com.secninjaz.octolab.internal" + (".debug" if debug else ""),
        "url": os.environ["CI_PROJECT_URL"], "author": os.environ["CI_PROJECT_NAMESPACE"],
        "name": "OctoLab", "preferredApkIndex": 0, "additionalSettings": json.dumps(settings),
        "overrideSource": "GitLab",
    }
    return link("obtainium://app/" + json.dumps(config, separators=(",", ":")))


LINK = re.compile(r"https?://apps\.obtainium\.imranr\.dev/[^\s)\"'<>\]]*")


def check(files):
    if not files:
        files = subprocess.run(["git", "ls-files"], capture_output=True, text=True,
                               check=True).stdout.split()
    bad = []
    for name in files:
        if name.endswith("obtainium_link.py") or not os.path.isfile(name):
            continue
        try:
            text = open(name, encoding="utf-8").read()
        except (UnicodeDecodeError, OSError):
            continue
        for n, line in enumerate(text.splitlines(), 1):
            for url in LINK.findall(line):
                rest = url[len(SITE):] if url.startswith(SITE) else None
                if rest is None or "://" in rest or (rest and not rest.startswith("obtainium%3A")):
                    bad.append(f"{name}:{n}: {url[:100]}")
    if bad:
        print("Obtainium links not built by scripts/obtainium_link.py (#310):")
        print("\n".join(bad))
        print("Use: " + link("obtainium://add/" + GITHUB))
        return 1
    print("Obtainium links: OK")
    return 0


if __name__ == "__main__":
    arg = sys.argv[1] if len(sys.argv) > 1 else ""
    if arg == "public":
        print(link("obtainium://add/" + GITHUB))
    elif arg in ("internal-release", "internal-debug"):
        print(internal(arg == "internal-debug"))
    elif arg == "--check":
        sys.exit(check(sys.argv[2:]))
    else:
        print(__doc__)
        sys.exit(2)
