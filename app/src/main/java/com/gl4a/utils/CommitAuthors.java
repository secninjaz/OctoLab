package com.gl4a.utils;

import android.net.Uri;
import android.util.Log;

import com.gl4a.Gl4Application;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabCommit;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.service.GitLabGraphQLService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Response;

/**
 * Commits from REST carry only the author's git name and email. GitLab itself links each commit
 * to an account (also by private emails) and shows that on the web; this asks for it, one
 * GraphQL query per batch of commits, and sets {@link GitLabCommit#authorUser}, so commit
 * authors get the same avatar, name and tap as issue authors (#244). Blocking; never throws:
 * commits GitLab links to no account keep their email-only author.
 */
public final class CommitAuthors {
    private static final int BATCH = 50;

    private CommitAuthors() {}

    public static <T extends List<GitLabCommit>> T attach(T commits) {
        if (commits == null || commits.isEmpty()) return commits;
        String path = null;
        for (GitLabCommit c : commits) {
            String[] project = ApiHelpers.projectFromWebUrl(c.webUrl);
            if (project != null) {
                path = project[0] + "/" + project[1];
                break;
            }
        }
        if (path == null) return commits;
        for (int start = 0; start < commits.size(); start += BATCH) {
            attachBatch(path, commits.subList(start, Math.min(start + BATCH, commits.size())));
        }
        return commits;
    }

    private static void attachBatch(String path, List<GitLabCommit> commits) {
        try {
            link(path, commits);
        } catch (Exception e) {
            Log.d(Gl4Application.LOG_TAG, "Commit authors unavailable", e);
        }
    }

    /**
     * Sets the linked account on each of {@code commits} (one GraphQL query, up to 50), and
     * remembers it for their emails. Throws {@link AvatarHandler.RateLimitedException} when
     * GitLab says too many requests, so callers that look up many can wait (#322).
     */
    @SuppressWarnings("unchecked")
    static void link(String path, List<GitLabCommit> commits) throws Exception {
        StringBuilder query = new StringBuilder("query($p: ID!");
        StringBuilder fields = new StringBuilder();
        Map<String, Object> variables = new HashMap<>();
        variables.put("p", path);
        for (int i = 0; i < commits.size(); i++) {
            if (commits.get(i).authorUser != null || commits.get(i).id == null) continue;
            query.append(", $r").append(i).append(": String!");
            fields.append(" c").append(i).append(": commit(ref: $r").append(i)
                    .append(") { author { id username name avatarUrl webUrl } }");
            variables.put("r" + i, commits.get(i).id);
        }
        if (fields.length() == 0) return;
        query.append(") { project(fullPath: $p) { repository {").append(fields)
                .append(" } } }");
        Map<String, Object> body = new HashMap<>();
        body.put("query", query.toString());
        body.put("variables", variables);
        Response<Map<String, Object>> response = ServiceFactory
                .getGraphQL(GitLabGraphQLService.class).rawQuery(body).blockingGet();
        if (response.code() == 429) {
            throw new AvatarHandler.RateLimitedException(
                    AvatarHandler.retryAfterMs(response));
        }
        Map<String, Object> repository = child(child(child(response.body(), "data"),
                "project"), "repository");
        if (repository == null) return;
        for (int i = 0; i < commits.size(); i++) {
            Map<String, Object> author = child(child(repository, "c" + i), "author");
            if (author != null) {
                GitLabCommit commit = commits.get(i);
                commit.authorUser = toUser(author);
                // Every git author with this email shows that account too (#319)
                AvatarHandler.rememberGitAuthor(commit.authorEmail, commit.authorUser);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> map, String key) {
        Object value = map != null ? map.get(key) : null;
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static GitLabUser toUser(Map<String, Object> author) {
        GitLabUser user = new GitLabUser();
        String gid = (String) author.get("id");
        if (gid != null) {
            try {
                user.id = Long.parseLong(gid.substring(gid.lastIndexOf('/') + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        user.username = (String) author.get("username");
        user.name = (String) author.get("name");
        user.webUrl = (String) author.get("webUrl");
        String avatar = (String) author.get("avatarUrl");
        // GraphQL gives uploaded avatars as a path on the instance
        if (avatar != null && avatar.startsWith("/") && user.webUrl != null) {
            Uri web = Uri.parse(user.webUrl);
            avatar = web.getScheme() + "://" + web.getAuthority() + avatar;
        }
        user.avatarUrl = avatar;
        return user;
    }
}
