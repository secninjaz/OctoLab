package com.gl4a.utils;

import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.gl4a.Gl4Application;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabCommit;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabTreeItem;
import com.gl4a.gitlab.service.GitLabGraphQLService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import retrofit2.Response;

/**
 * What a project's screen shows on opening, from GitLab in ONE GraphQL query (#350): the
 * branch's top-level files (About's shortcuts, Files' root), its first 25 commits with their
 * accounts (About's last commit, Commits' first page), the open merge requests count, the
 * languages and whether the user starred it. Each was its own request (with REST twice for
 * files and commits), which used up gitlab.com's limit on requests after a few projects.
 * <p>
 * The answer is kept for a minute per account, project and branch, and the tabs asking at the
 * same time share one request. Each part is null when GitLab didn't give it (an older GitLab,
 * an error, no access): the screen then asks as it did before, so it never shows less.
 */
public final class ProjectOverview {
    private static final long KEEP_MS = 60_000;
    // A refresh takes an answer only this new: asked for by the same refresh (#350)
    private static final long FRESH_MS = 5_000;
    // As many as REST gave on one page, so the lists are the same
    private static final int FILES = 100;
    public static final int COMMITS = 25;

    private static final String QUERY = "query($path: ID!, $ref: String!, $search: String) {"
            + " project(fullPath: $path) { id fullPath openMergeRequestsCount"
            + " languages { name share color }"
            + " repository {"
            + " tree(ref: $ref) {"
            + " trees(first: " + FILES + ") { nodes { name path sha } pageInfo { hasNextPage } }"
            + " blobs(first: " + FILES + ") { nodes { name path sha mode }"
            + " pageInfo { hasNextPage } }"
            + " submodules(first: " + FILES + ") { nodes { name path sha }"
            + " pageInfo { hasNextPage } } }"
            + " commits(ref: $ref, first: " + COMMITS + ") { nodes { sha shortId title message"
            + " authorName authorEmail authoredDate committerName committerEmail committedDate"
            + " webUrl author { id username name avatarUrl webUrl } }"
            + " pageInfo { hasNextPage } } } }"
            + " currentUser { starredProjects(search: $search, first: 100) {"
            + " nodes { id fullPath } pageInfo { hasNextPage } } } }";

    /** What GitLab answered; a null part is one to ask for as before. */
    public static final class Data {
        @Nullable public final List<GitLabTreeItem> rootFiles;
        @Nullable public final List<GitLabCommit> commits;
        public final boolean moreCommits;
        @Nullable public final Integer openMergeRequests;
        @Nullable public final List<Map<String, Object>> languages;
        @Nullable public final Boolean starred;

        Data(@Nullable List<GitLabTreeItem> rootFiles, @Nullable List<GitLabCommit> commits,
                boolean moreCommits, @Nullable Integer openMergeRequests,
                @Nullable List<Map<String, Object>> languages, @Nullable Boolean starred) {
            this.rootFiles = rootFiles;
            this.commits = commits;
            this.moreCommits = moreCommits;
            this.openMergeRequests = openMergeRequests;
            this.languages = languages;
            this.starred = starred;
        }

        /** A copy of the top-level files, for a screen to sort or keep. */
        @Nullable
        public List<GitLabTreeItem> rootFilesCopy() {
            return rootFiles != null ? new ArrayList<>(rootFiles) : null;
        }

        /** A copy of the first commits, for a screen to keep. */
        @Nullable
        public List<GitLabCommit> commitsCopy() {
            return commits != null ? new ArrayList<>(commits) : null;
        }

        boolean isEmpty() {
            return rootFiles == null && commits == null && openMergeRequests == null
                    && languages == null && starred == null;
        }
    }

    private static final Data NONE = new Data(null, null, false, null, null, null);

    private static final class Entry {
        final long at;
        final Single<Data> answer;

        Entry(long at, Single<Data> answer) {
            this.at = at;
            this.answer = answer;
        }
    }

    private static final Map<String, Entry> sEntries = new HashMap<>();
    // GitLabs whose GraphQL can't answer this query (fields it doesn't have): asked as before
    private static final Set<String> sUnsupported = new HashSet<>();

    private ProjectOverview() {
    }

    public static Single<Data> get(GitLabProject project, @Nullable String ref, boolean fresh) {
        return get(project.pathWithNamespace, project.id, ref, fresh);
    }

    /**
     * The overview of {@code fullPath} on {@code ref}. Never fails: without an answer, every
     * part is null. {@code fresh} (a refresh) takes only an answer asked for moments ago.
     */
    public static synchronized Single<Data> get(@Nullable String fullPath, long projectId,
            @Nullable String ref, boolean fresh) {
        Gl4Application app = Gl4Application.get();
        String instance = app.getInstanceUrl();
        if (TextUtils.isEmpty(fullPath) || TextUtils.isEmpty(ref)
                || sUnsupported.contains(instance)) {
            return Single.just(NONE);
        }
        String key = keyPrefix(fullPath) + ref;
        long now = SystemClock.elapsedRealtime();
        Entry entry = sEntries.get(key);
        if (entry != null && now - entry.at < (fresh ? FRESH_MS : KEEP_MS)) {
            return entry.answer;
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("path", fullPath);
        variables.put("ref", ref);
        variables.put("search", fullPath.substring(fullPath.lastIndexOf('/') + 1));
        Map<String, Object> body = new HashMap<>();
        body.put("query", QUERY);
        body.put("variables", variables);
        Entry[] mine = new Entry[1];
        Single<Data> answer = ServiceFactory.getGraphQL(GitLabGraphQLService.class)
                .rawQuery(body)
                .subscribeOn(Schedulers.io())
                .map(response -> parse(response, instance, fullPath, projectId))
                .onErrorReturn(error -> NONE)
                // Only an answer is kept: a failure is asked again next time
                .doOnSuccess(data -> {
                    if (data.isEmpty()) forget(key, mine[0]);
                })
                .cache();
        mine[0] = new Entry(now, answer);
        sEntries.put(key, mine[0]);
        return answer;
    }

    /** Forgets the project's answers (every branch), e.g. on pull to refresh. */
    public static synchronized void forget(@Nullable String fullPath) {
        if (TextUtils.isEmpty(fullPath)) return;
        String prefix = keyPrefix(fullPath);
        sEntries.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static synchronized void forget(String key, Entry entry) {
        if (sEntries.get(key) == entry) sEntries.remove(key);
    }

    private static synchronized void markUnsupported(String instance) {
        sUnsupported.add(instance);
    }

    private static String keyPrefix(String fullPath) {
        Gl4Application app = Gl4Application.get();
        return app.getInstanceUrl() + "|" + app.getAuthLogin() + "|"
                + fullPath.toLowerCase(Locale.ROOT) + "|";
    }

    @SuppressWarnings("unchecked")
    private static Data parse(Response<Map<String, Object>> response, String instance,
            String fullPath, long projectId) {
        Map<String, Object> body = response.body();
        if (!response.isSuccessful() || body == null) return NONE;
        Map<String, Object> data = child(body, "data");
        // The paths of the parts GitLab couldn't answer: those are asked for as before
        List<String> failed = new ArrayList<>();
        Object errors = body.get("errors");
        if (errors instanceof List) {
            for (Object error : (List<?>) errors) {
                Object path = error instanceof Map ? ((Map<String, Object>) error).get("path")
                        : null;
                failed.add(path instanceof List ? TextUtils.join("/", (List<?>) path) : "");
            }
            if (data == null && !failed.isEmpty()) {
                // A query this GitLab can't run at all (e.g. a field it doesn't have yet)
                markUnsupported(instance);
                return NONE;
            }
        }
        if (data == null) return NONE;

        Map<String, Object> project = child(data, "project");
        Map<String, Object> repository = child(project, "repository");

        List<GitLabTreeItem> files = failedAt(failed, "project/repository/tree") ? null
                : files(child(repository, "tree"));
        List<GitLabCommit> commits = null;
        boolean moreCommits = false;
        Map<String, Object> commitConnection = child(repository, "commits");
        if (!failedAt(failed, "project/repository/commits") && commitConnection != null
                && commitConnection.get("nodes") instanceof List) {
            commits = commits((List<?>) commitConnection.get("nodes"));
            moreCommits = Boolean.TRUE.equals(
                    child(commitConnection, "pageInfo") != null
                            ? child(commitConnection, "pageInfo").get("hasNextPage") : null);
        }
        Integer mergeRequests = null;
        Object count = project != null ? project.get("openMergeRequestsCount") : null;
        if (!failedAt(failed, "project/openMergeRequestsCount") && count instanceof Number) {
            mergeRequests = ((Number) count).intValue();
        }
        List<Map<String, Object>> languages = null;
        Object languageList = project != null ? project.get("languages") : null;
        if (!failedAt(failed, "project/languages") && languageList instanceof List) {
            languages = new ArrayList<>();
            for (Object language : (List<?>) languageList) {
                if (language instanceof Map) languages.add((Map<String, Object>) language);
            }
        }
        Boolean starred = failedAt(failed, "currentUser") ? null
                : starred(child(child(data, "currentUser"), "starredProjects"), fullPath,
                        projectId);
        return new Data(files, commits, moreCommits, mergeRequests, languages, starred);
    }

    private static boolean failedAt(List<String> failed, String path) {
        for (String f : failed) {
            if (f.isEmpty() || f.startsWith(path) || path.startsWith(f)) return true;
        }
        return false;
    }

    /**
     * The top-level files as REST's tree lists them (folders, files, submodules), or null when
     * there are more than REST gave on its page: REST is asked then, so the list is the same.
     */
    @Nullable
    private static List<GitLabTreeItem> files(@Nullable Map<String, Object> tree) {
        if (tree == null) return null;
        List<GitLabTreeItem> files = new ArrayList<>();
        String[][] kinds = { { "trees", "tree", "040000" }, { "blobs", "blob", null },
                { "submodules", "commit", "160000" } };
        for (String[] kind : kinds) {
            Map<String, Object> connection = child(tree, kind[0]);
            if (connection == null || !(connection.get("nodes") instanceof List)) return null;
            Map<String, Object> pageInfo = child(connection, "pageInfo");
            if (pageInfo == null || !Boolean.FALSE.equals(pageInfo.get("hasNextPage"))) {
                return null;
            }
            for (Object node : (List<?>) connection.get("nodes")) {
                if (!(node instanceof Map)) continue;
                Map<?, ?> entry = (Map<?, ?>) node;
                GitLabTreeItem item = new GitLabTreeItem();
                item.id = string(entry.get("sha"));
                item.name = string(entry.get("name"));
                item.path = string(entry.get("path"));
                item.type = kind[1];
                item.mode = kind[2] != null ? kind[2] : string(entry.get("mode"));
                files.add(item);
            }
        }
        return files.size() <= FILES ? files : null;
    }

    @SuppressWarnings("unchecked")
    private static List<GitLabCommit> commits(List<?> nodes) {
        List<GitLabCommit> commits = new ArrayList<>();
        for (Object node : nodes) {
            if (!(node instanceof Map)) continue;
            Map<String, Object> c = (Map<String, Object>) node;
            GitLabCommit commit = new GitLabCommit();
            commit.id = string(c.get("sha"));
            commit.shortId = string(c.get("shortId"));
            commit.title = string(c.get("title"));
            commit.message = string(c.get("message"));
            commit.authorName = string(c.get("authorName"));
            commit.authorEmail = string(c.get("authorEmail"));
            commit.authoredDate = string(c.get("authoredDate"));
            commit.committerName = string(c.get("committerName"));
            commit.committerEmail = string(c.get("committerEmail"));
            commit.committedDate = string(c.get("committedDate"));
            // REST's created_at is the commit date
            commit.createdAt = commit.committedDate;
            commit.webUrl = string(c.get("webUrl"));
            // The account GitLab links the commit to, as CommitAuthors sets it (#244)
            Map<String, Object> author = child(c, "author");
            if (author != null) {
                commit.authorUser = CommitAuthors.toUser(author);
                AvatarHandler.rememberGitAuthor(commit.authorEmail, commit.authorUser);
            }
            commits.add(commit);
        }
        return commits;
    }

    /** Whether the project is among the user's starred ones, null when that's not known. */
    @Nullable
    private static Boolean starred(@Nullable Map<String, Object> starredProjects,
            String fullPath, long projectId) {
        if (starredProjects == null || !(starredProjects.get("nodes") instanceof List)) {
            return null;
        }
        String gid = "gid://gitlab/Project/" + projectId;
        for (Object node : (List<?>) starredProjects.get("nodes")) {
            if (!(node instanceof Map)) continue;
            Object id = ((Map<?, ?>) node).get("id");
            Object path = ((Map<?, ?>) node).get("fullPath");
            if ((projectId > 0 && gid.equals(id))
                    || (path instanceof String && fullPath.equalsIgnoreCase((String) path))) {
                return true;
            }
        }
        // Not among the first 100 found: only "not starred" when there are no more
        Map<String, Object> pageInfo = child(starredProjects, "pageInfo");
        return pageInfo != null && Boolean.FALSE.equals(pageInfo.get("hasNextPage"))
                ? Boolean.FALSE : null;
    }

    @Nullable
    private static String string(Object value) {
        return value instanceof String ? (String) value : null;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(@Nullable Map<String, Object> map, String key) {
        Object value = map != null ? map.get(key) : null;
        return value instanceof Map ? (Map<String, Object>) value : null;
    }
}
