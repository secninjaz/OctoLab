# Changelog

All notable changes to OctoLab are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [1.3.0] - 2026-08-06

### Added
- Avatar disk cache: all user, email-based, and project avatars are persisted to disk on first load and shown instantly on subsequent app starts — no more initials flash. Background network fetch runs every session and crossfades only when the avatar has actually changed. All disk I/O is off the main thread.

### Fixed
- Reaction popup and three-dot Add/Remove reaction menu now load correctly when a reaction type has more than one reactor — a null `createdAt` comparator caused a crash that silently dismissed the popup (#121)
- Reaction chips now highlight with accent colour when the current user has reacted, both on issues and MR comments; chip state updates correctly after add/remove without requiring a refresh (#120)
- MR detail view reaction popup and chip tinting now work correctly — the MR body was fetching from the issue award emoji endpoint instead of the MR endpoint (#122)
- Account switcher side panel now shows avatars for all accounts on app start, not only the most recently active one — avatar URL is persisted on login and used for background fetch (#126)
- Repo > Merge Requests no longer returns HTTP 400 — project-level MR list now uses the correct `/projects/:id/merge_requests` endpoint; "merged" state tab works correctly (#124)
- Contributors, Collaborators, Wiki, Releases, Forks, Stars, and Files sections no longer return 404 for repositories nested more than one group deep — owner is now derived from `path_with_namespace` split on the last slash, works at any nesting depth (#125)

## [1.2.9] - 2026-08-03

### Fixed
- Notification "Mark as read" action button on a project's system notification now dismisses only that project's card — previously `cancelAll()` was called, removing every other project's notification from the shade simultaneously. Dismissing the group summary still clears all children.
- Mentioned and Participating tabs pagination restored in both My Issues and My MRs — pagination was disabled as a workaround for duplicate-item regression in v1.2.7. Fixed properly with a per-load `ConcurrentHashSet` that filters cross-page duplicates before they reach the list.

## [1.2.8] - 2026-08-03

### Fixed
- MR label colours in the Mentioned and Participating list tabs now show correctly — the enrichment was calling the issues endpoint with MR iids (silently ignored); switched to fetching all project labels and matching by name, consistent with the MR detail view fix in v1.2.7
- Bookmarks now scoped per account — all bookmarks were stored in a shared table with no account column, causing every account to see every other account's bookmarks; added `account` column (DB v5) and filter all read/write/check operations to the active login
- Tapping the project avatar or project name in the to-do list now opens the correct repository — the avatar was tagged with a `GitLabUser` stub using the project path as username, causing `UserActivity` to open and return a 404; fixed by tagging the avatar with `GitLabProject` and routing to `RepositoryActivity`

## [1.2.7] - 2026-08-03

### Added
- MR detail view: Reviewers section in the issue header, matching the Assignees row style — shows avatar, username, tappable to open profile
- Settings → Notification Worker section (visible when notifications are enabled): shows live worker state, last sync time, and a Sync Now button for immediate background poll

### Fixed
- Crash when opening the To-do list on gitlab.com accounts: relative avatar URLs (e.g. `/uploads/...`) now have the instance base URL prepended before being passed to OkHttp
- Project/group avatars: when a project has no avatar set, the app now walks up the namespace hierarchy (parent group → grandparent → root) until an avatar is found; initials shown only if nothing is set anywhere in the chain
- MR detail view: label colours now show correctly — the single-MR endpoint ignores `with_labels_details`; fixed by fetching project labels separately and matching by name
- MR detail view: labels, milestone, assignees, and reviewers were missing because the stub issue built in `newInstance` did not copy those fields from the MR
- Mentioned / Participating tabs: duplicate items appeared on scroll when pagination fired; fixed by increasing per-page to 100 and disabling pagination for these tabs (4 parallel calls × 100 already covers realistic mention volumes)
- Hint/placeholder text colour: unified to `#888888` for both light and dark themes, applied globally via `AppTheme` and `HeaderTheme`
- Issue creation form hint colour: `HeaderTheme` (used by the title input) now inherits `theme_hint` and `OctoLab.TextInputLayout` style so changes propagate from a single token
- Error text and icon colour in form fields: Material's default pinkish-purple replaced with `#C62828` (light) / `#FF5252` (dark), legible on both backgrounds

## [1.2.6] - 2026-07-31

### Fixed
- To-do list: items now correctly grouped under their own project header regardless of API sort order; items from Project A no longer appear under Project B's header
- To-do list: project avatars now load in header rows (fetched via `GET /projects/{id}` since Todos API omits `avatar_url`)
- @mention suggestions no longer show duplicate users — same person participating N times now appears only once (fixed `GitLabUser.equals`/`hashCode`)
- Markdown editor Preview tab background now matches app warm-white theme (`#FFFFF5`) instead of pure white (`#fafafa`)
- Placeholder/hint text colours: solid `#666666` (light) / `#AAAAAA` (dark) replace opacity-based values that were barely distinguishable from Material defaults
- MR label colours now show correctly in My MRs list and MR detail views (`with_labels_details=true` on MR endpoints)
- Commit comments: system notes (mentions, cross-references) now render as compact update rows matching the issue timeline style, instead of full comment rows

## [1.2.5] - 2026-07-30

### Fixed
- Notifications now enabled by default for every account from the moment login completes
- Notifications are polled for ALL logged-in accounts independently, each using their own token and instance URL
- Per-account notification seen/check timestamps — opening the notifications tab dismisses alerts for the active account only
- Tapping a notification from a different account now switches to that account before opening the notifications tab
- OctoLab monochrome icon replaces the OctoDroid octopus in the status bar; icon scaled 1.4× to fill the 24 dp slot
- Project avatar shown as the large icon in the notification card (fetched via `/projects/{id}` since Todos API omits it)
- Notification re-triggering on every app open fixed: `markNotificationsAsSeen` now updates per-account timestamps
- Notification action names are now natural English ("mentioned you", "assigned you", "requested your review", etc.)
- "Mark as read" action button added to each notification card
- `@mention` and `#reference` links in issue/MR bodies were silently non-clickable — `LinkSpan.onClick` cast directly to `FragmentActivity` which fails when views are inflated inside a `ContextThemeWrapper`; fixed by walking the wrapper chain
- After opening any MR, `@mentions` and `#refs` in all subsequent issue views also stopped rendering as links — `PullRequestConversationFragment` was writing an empty/wrong `currentProjectPath` globally, breaking the Phase 2 Markdown API context; fixed by passing the correct owner/repo from `PullRequestActivity`
- README hyperlinks in the WebView now render without underlines

## [1.2.4] - 2026-07-29

### Fixed
- Commit comments: cross-reference updates ("mentioned in issue #N") now render as minimal update rows (no avatar, no menu) matching the issue timeline style — previously showed as regular comment rows
- Commit comments: switched from `/comments` to `/discussions` API so the `system` flag is available; the old endpoint always returned `system=null`
- Commit comments: removed spurious edit-timestamp pencil icon (commits cannot be edited)
- Issue label colours now show in the issue list, detail view, and all My Issues tabs (Created, Assigned, Mentioned, Participating)
- Todos API and other endpoints always return labels as plain strings; added a Moshi adapter that handles both string and object label arrays so parsing no longer crashes
- Detail view label colours: the single-issue endpoint ignores `with_labels_details`; switched to the list endpoint with `iids[]` filter which does honour it
- Back-navigation crash from detailed issue: `GitLabLabel` Parcelable wrote null `Integer` fields without a null guard, causing NPE when Android saved instance state; fixed with `-1` sentinel
- Mentioned/Participating tabs: label colours now enriched after loading from Todos API by batch-fetching issues per project with `with_labels_details=true`

## [1.2.3] - 2026-07-28

### Fixed
- Commits now open correctly — fixed `NullPointerException` in `CommitActivity` when SHA was missing from the intent (caused the activity to crash and return to the previous screen)
- Commit link regex in Your Activity now correctly handles projects under nested groups (e.g. `group/subgroup/project`)
- Commit list item tap now passes the project ID directly so the activity skips the redundant project-path API lookup
- Commit list uses the URL-safe `path` field (not the display `name`) so project lookup works even when the project name contains capital letters
- Fixed `NullPointerException` crash in `CommitFragment.fillStatsFromDiffs` when diffs loaded before the fragment view was created — deferred to `onViewCreated`
- Commit author avatars now load correctly — GitLab commits API does not return a user object, so avatars are resolved by author email via `GET /users?search=email` (returns the actual uploaded profile picture) with Gravatar as fallback
- `/api/v4/avatar?email=` replaced with users search API for email-based avatar lookups — the avatar endpoint on self-hosted instances ignores uploaded profile pictures and always returns Gravatar
- Commit author avatar tap now opens the correct user profile — the resolved `GitLabUser` (id + username) is cached when the avatar loads; navigation uses the real user ID directly, avoiding the `searchUsers` crash that occurred when email was passed as username
- Comment posted on a commit now appears immediately in the app without requiring a manual refresh

## [1.2.2] - 2026-07-28

### Fixed
- Your Activity: fixed crash parsing GitLab event responses where `noteable_id` is null — added Moshi null→0 adapter for primitive `long` fields

## [1.2.1] - 2026-07-28

### Fixed
- MR Discussion: comments now post correctly via MR notes API (was using Issue notes API)
- MR Discussion: emoji reactions on MR body and comments now use MR award emoji endpoints
- My MRs → Show Closed: now includes merged MRs (state=merged) alongside rejected ones (state=closed) for Created, Assigned, and Reviews tabs

## [1.2.0] - 2026-07-28

### Added
- My MRs: Created / Assigned / Reviews / Mentioned tabs with proper pagination
- Reviews tab — `scope=reviews_for_me` for MRs where you are assigned as reviewer
- Mentioned tab for MRs — Todos API (`type=MergeRequest`, pending + done)
- MR items display `!` prefix (e.g. `!32`) in both home feed and My MRs lists

### Fixed
- MRs now open correctly — fixed `ClassCastException` (`PullRequestBranchInfoView` vs `MergeRequestBranchInfoView` in layout)
- Fixed `Fragment already added` crash when `invalidateTabs()` was called twice during MR load
- RxJava `UndeliverableException` no longer crashes the app when a network call completes after navigating away
- Repository names in home feed now show correct capitalisation and `namespace/project` format

## [1.1.9] - 2026-07-27

### Fixed
- TextInputLayout hint/placeholder colour: added `OctoLab.TextInputLayout` style with explicit `hintTextColor` so Material reads the correct colour
- Repository names in notification/to-do headers now use proper capitalisation (`nameWithNamespace` field)
- Markdown link underlines removed globally

### Changed
- Markdown links no longer show underlines app-wide

## [1.1.8] - 2026-07-27

### Added
- Settings → Debug: in-app log collector. Captures API calls (URLs with tokens stripped) and errors in a 500-entry ring buffer. Share via Android share sheet or clear at any time. Disabled by default.

### Fixed
- My Issues → Mentioned tab: now fetches both `mentioned` and `directly_addressed` todo actions; properly paginated and sorted by most recent notification date
- My Issues → Participating tab: fetches all todo action types; properly paginated and deduplicated by issue id
- Placeholder/hint text colour now follows the theme (Material `TextInputLayout` hint colour fixed via `colorOnSurface`)
- My Repositories: default sort order changed to most recently pushed first
- Notification/To-do row timestamp now always right-aligned; issue row username+timestamp row fills full width

### Changed
- `PRIVACY.md` (renamed from `Privacy.md`): clarifies that OctoLab has no backend servers and all communication is directly device-to-GitLab
- README: added Privacy section and badge

## [1.1.7] - 2026-07-24

### Fixed
- Light theme: CardView and notification/to-do row backgrounds now match the warm-white tone (#FFFFF5) via theme_surface instead of hardcoded library white
- Navigation drawer: Repositories icon now adapts correctly to light and dark themes (was two completely different icons with hardcoded fill colours)
- Comment reactions: emoji counts now load automatically from the award emoji API when an issue/MR is opened, and are cached so they survive navigation back and RecyclerView rebinds
- Debug APK signing: all CI debug builds now use the release keystore so Obtainium updates install without signature conflict

## [1.1.6] - 2026-07-24

### Fixed
- Light/Day theme: warm-white tone (#FFFFF5) applied globally to window background, card surfaces, and toolbar controls; primary text changed from pure black to soft dark (#222222)
- Dark/Night theme: surfaces and drawer remained dark; previous attempt leaked light values into night mode causing a crash on launch
- To-do list: repository section headers now display names in their actual capitalisation instead of ALL CAPS

### Changed
- Signed internal release APK now named `OctoLab-vX.X.X-internal-release.apk` for clarity
- Debug APK versionCode uses CI pipeline ID to guarantee chronological installs across builds
- Both Obtainium tracking links (internal-release and debug) now appear in every release description

## [1.1.5] - 2026-07-21

### Fixed
- Obtainium links in GitLab releases: replaced broken base64 format with correct URL-encoded JSON config including GitLab source override and architecture auto-detection
- Obtainium version tracking: stable release link uses `versionExtractionRegEx` matching only stable tags; debug link matches only test tags — each now tracks its own release type independently

### Changed
- CI: Test tags (`v*.*.*-*`) now automatically upload the debug APK and create a GitLab release without any manual steps
- CI: `security:gate` and `publish:github` now only trigger on stable tags (`v*.*.*`), no longer failing on test tags
- CI: `versionName` in `build.gradle` is stamped from the tag before building so the installed app version always matches the release tag

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

