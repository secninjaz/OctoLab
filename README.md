# OctoLab

<img src="octolab-logo/octolab-mark.svg" alt="OctoLab" width="120" align="right">

**OctoLab** is a free and open-source, unofficial GitLab client for Android. It works with [gitlab.com](https://gitlab.com) and self-hosted GitLab instances (Community or Enterprise Edition).

Built and maintained by [SecNinjaz](https://secninjaz.com).

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![F-Droid](https://img.shields.io/f-droid/v/com.secninjaz.octolab)](https://f-droid.org/packages/com.secninjaz.octolab)
[![Privacy Policy](https://img.shields.io/badge/Privacy-Policy-informational)](PRIVACY.md)

---

## Features

**Accounts**
- Personal Access Token (PAT) login (OAuth login planned)
- Multi-account: self-hosted and gitlab.com side by side

**Issues and merge requests**
- Browse, create, edit and comment on issues
- Filter by label, assignee or milestone
- View merge requests, comment and review diffs
- Subscribe to or unsubscribe from issues and merge requests

**To-dos and notifications**
- To-dos | Participating: your to-dos, and new comments on issues and merge requests you take part in
- Group by project, filter, mark as done or read
- Notifications for new to-dos and comments, with counts per account

**Activity**
- Your activity, your projects' and starred projects' activity

**Projects**
- Browse files, README, branches, tags, commits and releases
- Search a project's code
- Images, videos and files open in the app

**Also**
- Bookmarks for projects, groups, issues, merge requests, commits and snippets
- Snippets, groups, Explore and search
- Dark and light theme
- Markdown rendering: GFM tables, task lists, code blocks, images

No tracking, no analytics, no proprietary services.

---

## Screenshots

<p float="left">
  <img src="screenshots/03.png" width="180" alt="Project overview" />
  <img src="screenshots/08.png" width="180" alt="Issues list" />
  <img src="screenshots/07.png" width="180" alt="Diff viewer" />
</p>

---

## Installation

### F-Droid (recommended)
[<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="75">](https://f-droid.org/packages/com.secninjaz.octolab/)

OctoLab is available in the main [F-Droid](https://f-droid.org/packages/com.secninjaz.octolab/) repository. F-Droid builds it from source and checks that the build matches our release (a reproducible build), then ships our signed APK. Updates arrive through the F-Droid client, usually a few days after a release.

### Obtainium
[<img src="https://raw.githubusercontent.com/deckerst/common/main/assets/obtainium-badge-english.png" alt="Get it on Obtainium" height="75">](https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/secninjaz/OctoLab)

Fetches APKs directly from GitHub Releases and keeps the app updated automatically, as soon as a release is published.

### GitHub Releases
Download the latest signed APK from [Releases](https://github.com/secninjaz/OctoLab/releases).

All three use the same signing key, so you can switch between them without reinstalling.

---

## Building from Source

### Requirements
- Android Studio or Android SDK (API 36, Build Tools 36)
- Java 21
- A GitLab account (gitlab.com or self-hosted)

### Setup

1. Clone the repo:
   ```bash
   git clone https://github.com/secninjaz/octolab.git
   cd octolab
   ```

2. Create `app/client.properties` with your GitLab OAuth app credentials:
   ```
   ClientId="YOUR_GITLAB_CLIENT_ID"
   ClientSecret="YOUR_GITLAB_CLIENT_SECRET"
   ```
   To create an OAuth app: GitLab → User Settings → Applications → Add new application
   - Redirect URI: `gl4a://oauth`
   - Scopes: `api`, `read_user`, `read_repository`, `write_repository`, `openid`

3. Build:
   ```bash
   ./gradlew assembleDebug
   ```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Login

- **OAuth** — opens your GitLab instance in the browser to authorize
- **Access Token** — enter a Personal Access Token with `api` + `read_user` scopes
- **Instance URL** — defaults to `https://gitlab.com`, change to your self-hosted URL

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## AI-assisted development

This project uses [Claude Code](https://claude.ai/code) as a development assistant. The `.claude/` directory is intentionally excluded from version control — it contains local workflow settings specific to the development environment and does not affect the app's behaviour or security.

All code changes — including those suggested by AI — are reviewed, tested, and committed by a human maintainer. AI assistance does not replace code review, security scanning, or the project's standard release process (which includes SAST, dependency scanning, and MobSF static analysis on every release).

---

## Security

See [SECURITY.md](SECURITY.md) for how to report vulnerabilities.

---

## Privacy

OctoLab has no backend servers of its own. All communication happens directly between your device and the GitLab instance(s) you configure — no data passes through any SecNinjaz infrastructure.

See [PRIVACY.md](PRIVACY.md) for the full privacy policy.

---

## Standing on the Shoulders of Giants

OctoLab would not exist without the exceptional work of the [Octodroid](https://github.com/slapperwan/gh4a) team. Octodroid is a beautifully crafted, open-source GitHub client for Android — and it provided the entire foundation that OctoLab is built upon.

We are deeply grateful to the Octodroid contributors:

- **[slapperwan](https://github.com/slapperwan)** — creator and primary maintainer
- **[maniac103](https://github.com/maniac103)** — major contributor
- **[kageiit](https://github.com/kageiit)** — contributor
- **[Tunous](https://github.com/Tunous)** — contributor
- All other contributors who made Octodroid what it is

OctoLab is a GitLab port of Octodroid, maintained by [SecNinjaz](https://secninjaz.com). All original Octodroid code remains under the [Apache License 2.0](LICENSE). If you appreciate OctoLab, please also star and support the [Octodroid project](https://github.com/slapperwan/gh4a).

---

## License

[Apache License 2.0](LICENSE)

OctoLab is not affiliated with or endorsed by GitLab Inc. GitLab is a trademark of GitLab Inc.
