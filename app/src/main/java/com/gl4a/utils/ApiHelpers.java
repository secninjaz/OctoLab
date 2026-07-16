package com.gl4a.utils;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.Pair;

import com.gl4a.ApiRequestException;
import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabCommit;
import com.gl4a.gitlab.model.GitLabIssue;
import com.gl4a.gitlab.model.GitLabLabel;
import com.gl4a.gitlab.model.GitLabPage;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabUser;

import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.functions.Predicate;
import io.reactivex.subjects.BehaviorSubject;
import okhttp3.Headers;
import retrofit2.Response;

public class ApiHelpers {
    public static final int MAX_PAGE_SIZE = 100;

    public interface IssueState {
        String OPEN = "opened";
        String CLOSED = "closed";
        String MERGED = "merged";
        String UNMERGED = "unmerged";
    }

    public static final Comparator<GitLabComment> COMMENT_COMPARATOR = (lhs, rhs) -> {
        if (lhs.createdAt() == null) {
            return 1;
        }
        if (rhs.createdAt() == null) {
            return -1;
        }
        return lhs.createdAt().compareTo(rhs.createdAt());
    };

    public static String getAuthorName(Context context, GitLabCommit commit) {
        if (!TextUtils.isEmpty(commit.authorName)) {
            return commit.authorName;
        }
        GitLabUser author = commit.author();
        if (author != null && !TextUtils.isEmpty(author.login())) {
            return author.login();
        }
        return context.getString(R.string.unknown);
    }

    public static String getAuthorLogin(GitLabCommit commit) {
        GitLabUser author = commit.author();
        if (author != null) {
            return author.login();
        }
        return null;
    }

    public static String getCommitterName(Context context, GitLabCommit commit) {
        if (!TextUtils.isEmpty(commit.committerName)) {
            return commit.committerName;
        }
        return context.getString(R.string.unknown);
    }

    public static boolean authorEqualsCommitter(GitLabCommit commit) {
        if (!TextUtils.isEmpty(commit.authorEmail) && !TextUtils.isEmpty(commit.committerEmail)) {
            return TextUtils.equals(commit.authorEmail, commit.committerEmail);
        }
        return TextUtils.equals(commit.authorName, commit.committerName);
    }

    public static String getUserLogin(Context context, GitLabUser user) {
        if (user != null && user.login() != null) {
            return user.login();
        }
        return context.getString(R.string.deleted);
    }

    public static SpannableStringBuilder getUserLoginWithType(Context context, GitLabUser user) {
        return getUserLoginWithType(context, user, false);
    }

    public static SpannableStringBuilder getUserLoginWithType(Context context, GitLabUser user,
            boolean boldifyLogin) {
        final SpannableStringBuilder builder =
                new SpannableStringBuilder(getUserLogin(context, user));
        if (boldifyLogin) {
            builder.setSpan(new StyleSpan(Typeface.BOLD), 0, builder.length(), 0);
        }
        // GitLab does not have bot/mannequin user type distinctions; return login only
        return builder;
    }

    public static String formatRepoName(Context context, GitLabProject project) {
        if (project == null) return context.getString(R.string.deleted);
        // Prefer pathWithNamespace (e.g. "group/repo"), then name, then path
        if (!TextUtils.isEmpty(project.pathWithNamespace)) return project.pathWithNamespace;
        if (!TextUtils.isEmpty(project.name)) return project.name;
        if (!TextUtils.isEmpty(project.path)) return project.path;
        return context.getString(R.string.deleted);
    }

    public static int colorForLabel(GitLabLabel label) {
        return Color.parseColor("#" + label.color());
    }

    public static boolean userEquals(GitLabUser lhs, GitLabUser rhs) {
        if (lhs == null || rhs == null) {
            return false;
        }
        return loginEquals(lhs.login(), rhs.login());
    }

    public static boolean loginEquals(GitLabUser user, String login) {
        if (user == null) {
            return false;
        }
        return loginEquals(user.login(), login);
    }

    public static boolean loginEquals(String user, String login) {
        return user != null && user.equalsIgnoreCase(login);
    }

    public static Uri normalizeUri(Uri uri) {
        if (uri == null || uri.getAuthority() == null) {
            return uri;
        }

        // Only normalize API links
        if (!uri.getPath().contains("/api/v4/") && !uri.getAuthority().contains("api.")) {
            return uri;
        }

        String path = uri.getPath()
                .replace("/api/v4/", "/")
                .replace("projects/", "")
                .replace("commits/", "commit/")
                .replace("merge_requests/", "merge_requests/");

        String authority = uri.getAuthority()
                .replace("api.", "");

        return uri.buildUpon()
                .path(path)
                .authority(authority)
                .build();
    }

    /**
     * Extracts the namespace (owner) and project path from a GitLabIssue's web URL.
     * GitLab issue web URLs follow the pattern: https://gitlab.com/namespace/project/-/issues/IID
     */
    public static Pair<String, String> extractRepoOwnerAndNameFromIssue(GitLabIssue issue) {
        String url = issue.webUrl;
        if (url != null) {
            // Strip trailing /-/issues/... to get namespace/project
            int issueIdx = url.indexOf("/-/issues");
            if (issueIdx > 0) {
                String path = url.substring(0, issueIdx);
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash > 0) {
                    String projectName = path.substring(lastSlash + 1);
                    String remainder = path.substring(0, lastSlash);
                    int secondSlash = remainder.lastIndexOf('/');
                    if (secondSlash >= 0) {
                        String namespace = remainder.substring(secondSlash + 1);
                        return Pair.create(namespace, projectName);
                    }
                }
            }
        }
        // Fallback: use project path directly if available
        return Pair.create("", "");
    }

    private final static char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    public static String sha256Of(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] messageDigest = digest.digest(input.getBytes());
            char[] hexChars = new char[messageDigest.length * 2];
            for (int i = 0; i < messageDigest.length; i++) {
                int b = messageDigest[i] & 0xFF;
                hexChars[i * 2] = HEX_CHARS[b >>> 4];
                hexChars[i * 2 + 1] = HEX_CHARS[b & 0x0F];
            }
            return new String(hexChars);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return "";
    }

    public static int getTotalPagesCount(GitLabPage<?> page) {
        int total = page.totalPages();
        return total > 0 ? total : 1;
    }

    public static <T> T throwOnFailure(Response<T> response) throws ApiRequestException {
        if (!response.isSuccessful()) {
            throw new ApiRequestException(response);
        }
        return response.body();
    }

    public static boolean mapToTrueOnSuccess(Response<Void> response) throws ApiRequestException {
        if (!response.isSuccessful()) {
            throw new ApiRequestException(response);
        }
        return true;
    }

    public static Boolean mapToBooleanOrThrowOnFailure(Response<Void> response)
            throws ApiRequestException {
        if (response.isSuccessful()) {
            return true;
        } else if (response.code() == HttpURLConnection.HTTP_NOT_FOUND) {
            return false;
        }
        throw new ApiRequestException(response);
    }

    /**
     * Converts a Retrofit Response<List<T>> into a GitLabPage<T> by reading the
     * GitLab pagination response headers (X-Page, X-Next-Page, X-Total-Pages, X-Total).
     * Services that return Single<Response<List<T>>> should call this in their loadPage()
     * mapping block so that PagedDataBaseFragment.load() can properly iterate pages.
     */
    public static <T> GitLabPage<T> toPage(Response<List<T>> response) {
        List<T> items = response.body() != null ? response.body() : Collections.emptyList();
        Headers headers = response.headers();
        int currentPage = parseIntHeader(headers, "X-Page", 1);
        int nextPage = parseIntHeader(headers, "X-Next-Page", 0);
        int totalPages = parseIntHeader(headers, "X-Total-Pages", 1);
        int totalItems = parseIntHeader(headers, "X-Total", items.size());
        return new GitLabPage<>(items, currentPage, nextPage, totalPages, totalItems);
    }

    private static int parseIntHeader(Headers h, String name, int defaultVal) {
        String val = h.get(name);
        if (val == null || val.isEmpty()) return defaultVal;
        try { return Integer.parseInt(val.trim()); } catch (NumberFormatException e) { return defaultVal; }
    }

    /**
     * A no-op GitLabPage with no items and no pagination metadata.
     */
    public static class DummyPage<T> extends GitLabPage<T> {
        public DummyPage() {
            super(new ArrayList<>(), 1, 0, 1, 0);
        }
    }

    /**
     * Adapts a source GitLabPage<U> to a GitLabPage<D> by mapping each item.
     */
    public static class PageAdapter<U, D> extends GitLabPage<D> {
        public PageAdapter(GitLabPage<U> page, Function<U, D> mapper) {
            super(mapItems(page.items(), mapper),
                    page.currentPage(),
                    page.nextPage(),
                    page.totalPages(),
                    page.totalItems());
        }

        private static <U, D> List<D> mapItems(List<U> items, Function<U, D> mapper) {
            if (items == null) return new ArrayList<>();
            List<D> result = new ArrayList<>(items.size());
            for (U item : items) {
                result.add(mapper.apply(item));
            }
            return result;
        }
    }

    public static class PageIterator<T> {
        public interface PageProducer<T> {
            Single<Response<GitLabPage<T>>> getPage(long page);
        }

        public static <T> Single<List<T>> toSingle(PageProducer<T> producer) {
            BehaviorSubject<Optional<Integer>> pageControl =
                    BehaviorSubject.createDefault(Optional.of(1));
            return pageControl
                    .concatMap(page -> {
                        if (!page.isPresent()) {
                            return Observable.<List<T>>empty()
                                    .doOnComplete(() -> pageControl.onComplete());
                        }
                        return producer.getPage(page.get())
                                .toObservable()
                                .compose(PageIterator::evaluateError)
                                .doOnNext(resultPage -> {
                                    Integer nextPage = resultPage.next();
                                    pageControl.onNext(nextPage != null && nextPage > 0
                                            ? Optional.of(nextPage) : Optional.empty());
                                })
                                .map(responsePage -> responsePage.items());
                    })
                    .toList()
                    .map(lists -> {
                        List<T> result = new ArrayList<>();
                        for (List<T> l : lists) {
                            result.addAll(l);
                        }
                        return result;
                    });
        }

        public static <T> Single<Optional<T>> first(PageProducer<T> producer,
                Predicate<T> predicate) {
            BehaviorSubject<Optional<Integer>> pageControl =
                    BehaviorSubject.createDefault(Optional.of(1));
            return pageControl
                    .concatMap(page -> {
                        if (!page.isPresent()) {
                            return Observable.<Optional<T>>empty()
                                    .doOnComplete(() -> pageControl.onComplete());
                        }
                        return producer.getPage(page.get())
                                .toObservable()
                                .compose(PageIterator::evaluateError)
                                .map(resultPage -> {
                                    for (T item : resultPage.items()) {
                                        if (predicate.test(item)) {
                                            return Pair.create(item, (Integer) null);
                                        }
                                    }
                                    Integer nextPage = resultPage.next();
                                    return Pair.create((T) null,
                                            nextPage != null && nextPage > 0 ? nextPage : null);
                                })
                                .doOnNext(resultOrNextPage ->
                                        pageControl.onNext(
                                                Optional.ofNullable(resultOrNextPage.second)))
                                .map(resultOrNextPage ->
                                        Optional.ofNullable(resultOrNextPage.first));
                    })
                    .filter(opt -> opt.isPresent())
                    .first(Optional.empty());
        }

        private static <T> Observable<GitLabPage<T>> evaluateError(
                Observable<Response<GitLabPage<T>>> upstream) {
            return upstream.map(response -> {
                throwOnFailure(response);
                return response.body();
            });
        }
    }
}
