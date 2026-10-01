package com.gl4a.utils;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;

import androidx.core.content.ContextCompat;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabDiff;
import com.gl4a.gitlab.service.GitLabCommitService;
import com.gl4a.gitlab.service.GitLabRepositoryService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.Single;

/**
 * The code lines a diff thread was started on, like GitLab web shows above the first comment
 * (#179): the last few lines of the diff hunk up to the commented line, with +/- markers.
 * Taken from the compare of the thread's base_sha..head_sha (cached per version, so several
 * threads on one version share a request); falls back to plain file lines at that version.
 */
public final class DiffSnippetLoader {
    private static final int CONTEXT_LINES = 4;
    private static final Map<String, Single<List<GitLabDiff>>> sCompares = new ConcurrentHashMap<>();
    private static final Map<String, List<Line>> sSnippets = new ConcurrentHashMap<>();

    private DiffSnippetLoader() {}

    /** One snippet line; rendered by {@link #render} with the caller's theme (#179). */
    public static class Line {
        final char type;   // '+', '-' or ' '
        final int oldNo, newNo;
        final String text;
        Line(char type, int oldNo, int newNo, String text) {
            this.type = type; this.oldNo = oldNo; this.newNo = newNo; this.text = text;
        }
    }

    /** The snippet lines; empty if they can't be loaded. Render with {@link #render}. */
    public static Single<List<Line>> load(String owner, String repo,
            GitLabComment.DiffPosition pos) {
        String path = pos.newLine != null ? pos.newPath : pos.oldPath;
        String key = pos.baseSha + ".." + pos.headSha + ":" + path + ":" + pos.oldLine + ":" + pos.newLine;
        List<Line> cached = sSnippets.get(key);
        if (cached != null) return Single.just(cached);
        return SingleFactory.getProjectId(owner, repo)
                .flatMap(projectId -> compare(projectId, pos)
                        .map(diffs -> fromDiffs(diffs, pos))
                        .onErrorReturnItem(new ArrayList<>())
                        .flatMap(lines -> !lines.isEmpty()
                                ? Single.just(lines) : fromFile(projectId, pos)))
                .doOnSuccess(lines -> { if (!lines.isEmpty()) sSnippets.put(key, lines); });
    }

    private static Single<List<GitLabDiff>> compare(long projectId, GitLabComment.DiffPosition pos) {
        String key = projectId + ":" + pos.baseSha + ".." + pos.headSha;
        Single<List<GitLabDiff>> single = sCompares.get(key);
        if (single == null) {
            single = ServiceFactory.get(GitLabCommitService.class, false)
                    .compareCommits(projectId, pos.baseSha, pos.headSha)
                    .map(ApiHelpers::throwOnFailure)
                    .map(compare -> compare.diffs != null
                            ? compare.diffs : new ArrayList<GitLabDiff>())
                    .cache();
            sCompares.put(key, single);
        }
        return single;
    }

    private static List<Line> fromDiffs(List<GitLabDiff> diffs,
            GitLabComment.DiffPosition pos) {
        for (GitLabDiff diff : diffs) {
            boolean match = pos.newLine != null
                    ? pos.newPath != null && pos.newPath.equals(diff.newPath)
                    : pos.oldPath != null && pos.oldPath.equals(diff.oldPath);
            if (!match || diff.diff == null) continue;
            List<Line> hunk = new ArrayList<>();
            int oldNo = 0, newNo = 0;
            for (String raw : diff.diff.split("\n", -1)) {
                if (raw.startsWith("@@")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)").matcher(raw);
                    if (m.find()) {
                        oldNo = Integer.parseInt(m.group(1));
                        newNo = Integer.parseInt(m.group(2));
                    }
                    hunk.clear();
                    continue;
                }
                if (raw.isEmpty() || raw.startsWith("\\")) continue;
                char type = raw.charAt(0);
                String text = raw.substring(1);
                Line line;
                if (type == '+') line = new Line('+', 0, newNo++, text);
                else if (type == '-') line = new Line('-', oldNo++, 0, text);
                else line = new Line(' ', oldNo++, newNo++, text);
                hunk.add(line);
                boolean target = pos.newLine != null
                        ? line.type != '-' && line.newNo == pos.newLine
                        : line.type != '+' && line.oldNo == pos.oldLine;
                if (target) {
                    return new ArrayList<>(hunk.subList(
                            Math.max(0, hunk.size() - 1 - CONTEXT_LINES), hunk.size()));
                }
            }
        }
        return new ArrayList<>();
    }

    private static Single<List<Line>> fromFile(long projectId,
            GitLabComment.DiffPosition pos) {
        boolean isNew = pos.newLine != null;
        String path = isNew ? pos.newPath : pos.oldPath;
        String ref = isNew ? pos.headSha : pos.baseSha;
        int target = isNew ? pos.newLine : (pos.oldLine != null ? pos.oldLine : 0);
        if (path == null || ref == null || target <= 0) return Single.just(new ArrayList<>());
        return ServiceFactory.get(GitLabRepositoryService.class, false)
                .getRawFile(projectId, android.net.Uri.encode(path), ref)
                .map(ApiHelpers::throwOnFailure)
                .map(body -> {
                    String[] lines = body.string().split("\n", -1);
                    List<Line> out = new ArrayList<>();
                    for (int n = Math.max(1, target - CONTEXT_LINES); n <= target && n <= lines.length; n++) {
                        out.add(new Line(' ', n, n, lines[n - 1]));
                    }
                    return out;
                })
                .onErrorReturnItem(new ArrayList<>());
    }

    /**
     * Renders lines with the same diff colours as the app's diff viewer, from the caller's
     * (themed) context, across the full line width.
     */
    public static CharSequence render(Context themedContext, List<Line> lines) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        int add = ContextCompat.getColor(themedContext, R.color.diff_add);
        int remove = ContextCompat.getColor(themedContext, R.color.diff_remove);
        int addNumber = ContextCompat.getColor(themedContext, R.color.diff_add_line_number);
        int removeNumber = ContextCompat.getColor(themedContext, R.color.diff_remove_line_number);
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            int start = text.length();
            int number = line.type == '-' ? line.oldNo : line.newNo;
            String prefix = String.format(java.util.Locale.ROOT, "%4d ", number);
            text.append(prefix).append(line.type).append(' ').append(line.text);
            if (i < lines.size() - 1) text.append('\n');
            if (line.type != ' ') {
                text.setSpan(new FullLineBackground(line.type == '+' ? add : remove),
                        start, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new BackgroundColorSpan(line.type == '+' ? addNumber : removeNumber),
                        start, start + prefix.length(), 0);
            }
        }
        return text;
    }

    /** Paints a diff line's background across the whole view width. */
    private static class FullLineBackground implements android.text.style.LineBackgroundSpan {
        private final int mColor;

        FullLineBackground(int color) {
            mColor = color;
        }

        @Override
        public void drawBackground(android.graphics.Canvas c, android.graphics.Paint p,
                int left, int right, int top, int baseline, int bottom, CharSequence text,
                int start, int end, int lineNumber) {
            int color = p.getColor();
            p.setColor(mColor);
            c.drawRect(left, top, right, bottom, p);
            p.setColor(color);
        }
    }
}
