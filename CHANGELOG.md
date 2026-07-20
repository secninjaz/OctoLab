# Changelog

All notable changes to OctoLab are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [1.1.4] - 2026-07-20

### Fixed
- User profiles: repository count showed 0 for other users; now updated from actual loaded project data
- User profiles: activity section only loaded ~25 items and did not paginate; fixed by reading `X-Next-Page` response header
- User profiles: snippets row is now hidden for other users (GitLab has no API to list another user's snippets)
- User profiles: own snippets screen now correctly calls `GET /snippets` instead of a non-existent user-scoped endpoint
- File browser: file sizes showed 0B; now fetched via `HEAD /repository/files/:path` reading the `X-Gitlab-Size` header
- Avatar loading: added 3-tier fallback (direct URL → GitLab Avatar API → Gravatar MD5); removed broken bitmap decode that returned null silently when image dimensions were smaller than the max size threshold
- User lookup: profile loads now use numeric user ID directly; falls back to username search on 404
- Light theme: list backgrounds changed from pure white (#ffffff) to off-white (#F8F8F8)
- Privacy policy: removed all OctoDroid/GitHub references; updated to reflect OctoLab PAT-based GitLab authentication

## [1.1.3] - 2026-07-20

### Fixed
- Multi-account switching: community (release) build was incorrectly logging out both accounts when switching between self-hosted and gitlab.com. Root cause: R8/ProGuard was optimising methods in Gl4Application that manage per-account instance URL state. Added explicit ProGuard keep rules.

