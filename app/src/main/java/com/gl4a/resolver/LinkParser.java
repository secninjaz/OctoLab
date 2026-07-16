package com.gl4a.resolver;

import android.content.Intent;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import android.text.TextUtils;

import com.gl4a.R;
import com.gl4a.activities.CommitActivity;
import com.gl4a.activities.CompareActivity;
import com.gl4a.activities.IssueActivity;
import com.gl4a.activities.IssueEditActivity;
import com.gl4a.activities.IssueListActivity;
import com.gl4a.activities.OrganizationMemberListActivity;
import com.gl4a.activities.PullRequestActivity;// MR compat
import com.gl4a.activities.ReleaseInfoActivity;
import com.gl4a.activities.ReleaseListActivity;
import com.gl4a.activities.RepositoryActivity;
import com.gl4a.activities.SearchActivity;
import com.gl4a.activities.UserActivity;
import com.gl4a.activities.WikiListActivity;
import com.gl4a.activities.home.HomeActivity;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses GitLab web URLs and maps them to in-app activities.
 *
 * GitLab URL scheme differs from GitHub:
 *   Issues:          /{owner}/{repo}/-/issues/{iid}
 *   Merge Requests:  /{owner}/{repo}/-/merge_requests/{iid}
 *   Commits:         /{owner}/{repo}/-/commit/{sha}
 *   Tree:            /{owner}/{repo}/-/tree/{ref}/{path}
 *   Blob:            /{owner}/{repo}/-/blob/{ref}/{path}
 *   Compare:         /{owner}/{repo}/-/compare/{from}...{to}
 *   Releases:        /{owner}/{repo}/-/releases[/{tagName}]
 *   Groups:          /groups/{groupPath}[-/members]
 */
public class LinkParser {

    /**
     * GitLab reserved top-level paths that are never user/group names.
     */
    private static final List<String> RESERVED_NAMES = Arrays.asList(
            "about", "admin", "api", "assets", "dashboard", "explore",
            "groups", "health_check", "help", "invites", "jwt", "login",
            "oauth", "profile", "projects", "runners", "search", "sessions",
            "signin", "signout", "signup", "snippets", "unsubscribes",
            "uploads", "users", "v2", "well-known"
    );

    private LinkParser() {
    }

    /**
     * Parses the specified {@code uri} and returns a result directing where to navigate.
     *
     * @return {@code null} to open in browser, a {@link ParseResult} with an intent or load task.
     */
    @Nullable
    public static ParseResult parseUri(FragmentActivity activity, @NonNull Uri uri,
            IntentUtils.InitialCommentMarker initialCommentFallback) {
        // Only handle URLs that belong to the configured GitLab instance.
        // External URLs (github.com, secninjaz.com, etc.) must open in the browser.
        String uriHost = uri.getHost();
        String instanceHost = android.net.Uri.parse(
                com.gl4a.Gl4Application.get().getInstanceUrl()).getHost();
        if (uriHost == null || !uriHost.equalsIgnoreCase(instanceHost)) {
            return null;
        }

        List<String> parts = new ArrayList<>(uri.getPathSegments());

        if (parts.isEmpty()) {
            return null;
        }

        String first = parts.get(0);
        if (RESERVED_NAMES.contains(first)) {
            // Handle a few top-level paths
            switch (first) {
                case "dashboard":
                    return new ParseResult(HomeActivity.makeIntent(activity, R.id.news_feed));
                case "groups":
                    return parseGroupLink(activity, uri, parts);
                case "explore":
                    return new ParseResult(HomeActivity.makeIntent(activity, R.id.my_repos));
                default:
                    return null;
            }
        }

        // Top-level single-segment shortcuts (logged-in user's feeds)
        switch (first) {
            case "notifications":
            case "todos":
                return new ParseResult(HomeActivity.makeIntent(activity, R.id.notifications));
            case "issues":
                return new ParseResult(HomeActivity.makeIntent(activity, R.id.my_issues));
            case "merge_requests":
                return new ParseResult(HomeActivity.makeIntent(activity, R.id.my_prs));
            case "snippets":
                return new ParseResult(HomeActivity.makeIntent(activity, R.id.my_gists));
        }

        String user = first;
        String repo = parts.size() >= 2 ? parts.get(1) : null;

        if (repo == null) {
            return parseUserLink(activity, uri, user);
        }

        // GitLab uses "/-/" prefix for resource paths inside a project
        // parts: [user, repo, "-", action, id, ...]
        int dashIndex = parts.indexOf("-");
        if (dashIndex == 2 && parts.size() >= 4) {
            String action = parts.get(3);
            String id = parts.size() >= 5 ? parts.get(4) : null;

            switch (action) {
                case "issues":
                case "work_items": // GitLab 18+ uses /-/work_items/{iid} for issues
                    return parseIssuesLink(activity, uri, user, repo, id, initialCommentFallback);
                case "merge_requests":
                    return parseMergeRequestLink(activity, uri, parts, user, repo, id,
                            initialCommentFallback);
                case "commit":
                    return parseCommitLink(activity, uri, user, repo, id, initialCommentFallback);
                case "tree":
                case "commits":
                    return parseTreeLink(activity, uri, parts, user, repo, action);
                case "blob":
                    return parseBlobLink(activity, uri, parts, user, repo);
                case "releases":
                    return parseReleaseLink(activity, parts, user, repo, id);
                case "compare":
                    return parseCompareLink(activity, user, repo, id);
                case "wiki":
                    return new ParseResult(WikiListActivity.makeIntent(activity, user, repo, null));
                case "members":
                    return new ParseResult(OrganizationMemberListActivity.makeIntent(activity, user));
            }
            return null;
        }

        // No "-" segment: plain /{user}/{repo}
        if (parts.size() == 2) {
            return new ParseResult(RepositoryActivity.makeIntent(activity, user, repo));
        }

        return null;
    }

    @Nullable
    private static ParseResult parseGroupLink(FragmentActivity activity, Uri uri,
            List<String> parts) {
        // /groups/{groupPath} or /groups/{groupPath}/-/members
        String groupPath = parts.size() >= 2 ? parts.get(1) : null;
        if (groupPath == null) return null;

        int dashIndex = parts.indexOf("-");
        if (dashIndex >= 2 && parts.size() > dashIndex + 1) {
            String action = parts.get(dashIndex + 1);
            if ("members".equals(action)) {
                return new ParseResult(OrganizationMemberListActivity.makeIntent(activity, groupPath));
            }
        }
        return new ParseResult(UserActivity.makeIntent(activity, groupPath));
    }

    private static ParseResult parseUserLink(FragmentActivity activity, @NonNull Uri uri,
            String user) {
        String tab = uri.getQueryParameter("tab");
        if (tab != null) {
            switch (tab) {
                case "projects":
                    return new ParseResult(new UserReposLoadTask(activity, uri, user, false));
                case "starred":
                    return new ParseResult(new UserReposLoadTask(activity, uri, user, true));
                default:
                    return new ParseResult(UserActivity.makeIntent(activity, user));
            }
        }
        return new ParseResult(UserActivity.makeIntent(activity, user));
    }

    @Nullable
    private static ParseResult parseIssuesLink(FragmentActivity activity, @NonNull Uri uri,
            String user, String repo, String id,
            IntentUtils.InitialCommentMarker initialCommentFallback) {
        if (StringUtils.isBlank(id)) {
            return new ParseResult(IssueListActivity.makeIntent(activity, user, repo));
        }
        if ("new".equals(id)) {
            return new ParseResult(IssueEditActivity.makeCreateIntent(activity, user, repo));
        }
        try {
            int issueNumber = Integer.parseInt(id);
            IntentUtils.InitialCommentMarker initialComment = generateInitialCommentMarker(
                    uri.getFragment(), "note_", initialCommentFallback);
            return new ParseResult(IssueActivity.makeIntent(activity, user, repo, issueNumber,
                    initialComment));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static ParseResult parseMergeRequestLink(FragmentActivity activity, @NonNull Uri uri,
            List<String> parts, String user, String repo, String id,
            IntentUtils.InitialCommentMarker initialCommentFallback) {
        if (StringUtils.isBlank(id)) {
            return new ParseResult(IssueListActivity.makeIntent(activity, user, repo, true));
        }

        int mrNumber;
        try {
            mrNumber = Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return null;
        }
        if (mrNumber <= 0) {
            return null;
        }

        // Check for diffs sub-page: /-/merge_requests/{iid}/diffs
        String subPage = parts.size() >= 6 ? parts.get(5) : null;
        int page = parseMergeRequestPage(subPage);

        DiffHighlightId diffId = extractDiffId(uri.getFragment(), "diff-");
        if (diffId != null) {
            return new ParseResult(new PullRequestDiffLoadTask(activity, uri, user, repo, diffId,
                    mrNumber, page));
        }

        IntentUtils.InitialCommentMarker initialDiffComment =
                generateInitialCommentMarkerWithoutFallback(uri.getFragment(), "note_");
        if (initialDiffComment != null) {
            return new ParseResult(new PullRequestDiffCommentLoadTask(activity, uri, user, repo,
                    mrNumber, initialDiffComment, page));
        }

        IntentUtils.InitialCommentMarker initialComment = generateInitialCommentMarker(
                uri.getFragment(), "note_", initialCommentFallback);
        return new ParseResult(PullRequestActivity.makeIntent(activity, user, repo,
                mrNumber, page, initialComment));
    }

    private static int parseMergeRequestPage(String subPage) {
        if (subPage == null) return -1;
        switch (subPage) {
            case "commits":
                return PullRequestActivity.PAGE_COMMITS;
            case "diffs":
                return PullRequestActivity.PAGE_FILES;
        }
        return -1;
    }

    @Nullable
    private static ParseResult parseCommitLink(FragmentActivity activity, @NonNull Uri uri,
            String user, String repo, String sha,
            IntentUtils.InitialCommentMarker initialCommentFallback) {
        if (StringUtils.isBlank(sha)) {
            return null;
        }
        DiffHighlightId diffId = extractDiffId(uri.getFragment(), "diff-");
        if (diffId != null) {
            return new ParseResult(new CommitDiffLoadTask(activity, uri, user, repo, diffId, sha));
        }

        IntentUtils.InitialCommentMarker initialComment =
                generateInitialCommentMarker(uri.getFragment(), "note_", initialCommentFallback);
        return new ParseResult(CommitActivity.makeIntent(activity, user, repo, sha, initialComment));
    }

    @NonNull
    private static ParseResult parseTreeLink(FragmentActivity activity, Uri uri,
            List<String> parts, String user, String repo, String action) {
        // parts: [user, repo, "-", "tree"/"commits", ref, ...path...]
        int page = "tree".equals(action)
                ? RepositoryActivity.PAGE_FILES
                : RepositoryActivity.PAGE_COMMITS;
        String refAndPath = parts.size() >= 5
                ? TextUtils.join("/", parts.subList(4, parts.size()))
                : repo;
        return new ParseResult(new RefPathDisambiguationTask(activity, uri, user, repo, refAndPath,
                page));
    }

    @Nullable
    private static ParseResult parseBlobLink(FragmentActivity activity, @NonNull Uri uri,
            List<String> parts, String user, String repo) {
        // parts: [user, repo, "-", "blob", ref, ...path...]
        if (parts.size() < 5) {
            return null;
        }
        String refAndPath = TextUtils.join("/", parts.subList(4, parts.size()));
        return new ParseResult(new RefPathDisambiguationTask(activity, uri, user, repo, refAndPath,
                uri.getFragment()));
    }

    @Nullable
    private static ParseResult parseReleaseLink(FragmentActivity activity, List<String> parts,
            String user, String repo, String tagName) {
        // /-/releases           → list
        // /-/releases/{tagName} → detail
        if (!TextUtils.isEmpty(tagName)) {
            return new ParseResult(ReleaseInfoActivity.makeIntent(activity, user, repo, tagName));
        }
        return new ParseResult(ReleaseListActivity.makeIntent(activity, user, repo));
    }

    @Nullable
    private static ParseResult parseCompareLink(FragmentActivity activity,
            String user, String repo, String id) {
        if (id == null) {
            return null;
        }
        String[] rangeParts = id.split("\\.\\.\\.");
        if (rangeParts.length != 2) {
            return null;
        }
        if (StringUtils.isBlank(rangeParts[0]) || StringUtils.isBlank(rangeParts[1])) {
            return null;
        }
        return new ParseResult(CompareActivity.makeIntent(activity, user, repo,
                rangeParts[0], rangeParts[1]));
    }

    private static IntentUtils.InitialCommentMarker generateInitialCommentMarkerWithoutFallback(
            String fragment, String prefix) {
        if (fragment == null || !fragment.startsWith(prefix)) {
            return null;
        }
        try {
            long commentId = Long.parseLong(fragment.substring(prefix.length()));
            return new IntentUtils.InitialCommentMarker(commentId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static IntentUtils.InitialCommentMarker generateInitialCommentMarker(
            String fragment, String prefix, IntentUtils.InitialCommentMarker fallback) {
        IntentUtils.InitialCommentMarker marker =
                generateInitialCommentMarkerWithoutFallback(fragment, prefix);
        return marker != null ? marker : fallback;
    }

    private static DiffHighlightId extractDiffId(String fragment, String prefix) {
        if (fragment == null || !fragment.startsWith(prefix)) {
            return null;
        }

        boolean right = false;
        int typePos = fragment.indexOf('L', prefix.length());
        if (typePos < 0) {
            right = true;
            typePos = fragment.indexOf('R', prefix.length());
        }

        String fileHash = typePos > 0
                ? fragment.substring(prefix.length(), typePos)
                : fragment.substring(prefix.length());
        if (typePos < 0) {
            return new DiffHighlightId(fileHash, -1, -1, false);
        }

        try {
            char type = fragment.charAt(typePos);
            String linePart = fragment.substring(typePos + 1);
            int startLine, endLine, dashPos = linePart.indexOf("-" + type);
            if (dashPos > 0) {
                startLine = Integer.parseInt(linePart.substring(0, dashPos));
                endLine = Integer.parseInt(linePart.substring(dashPos + 2));
            } else {
                startLine = Integer.parseInt(linePart);
                endLine = startLine;
            }
            return new DiffHighlightId(fileHash, startLine, endLine, right);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static class ParseResult {
        @Nullable
        public final Intent intent;

        @Nullable
        public final UrlLoadTask loadTask;

        public ParseResult(@NonNull UrlLoadTask loadTask) {
            this.intent = null;
            this.loadTask = loadTask;
        }

        public ParseResult(@NonNull Intent intent) {
            this.intent = intent;
            this.loadTask = null;
        }
    }
}
