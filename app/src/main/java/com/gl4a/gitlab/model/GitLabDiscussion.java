package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * A GitLab discussion (thread) from the issue/MR discussions API: its first note plus replies.
 * Standalone comments and system notes come back as single-note "individual" discussions.
 */
public class GitLabDiscussion {
    @Json(name = "id") public String id;
    @Json(name = "individual_note") public boolean individualNote;
    @Json(name = "notes") public List<GitLabComment> notes;

    /**
     * Flattens discussions into notes in timeline order, each thread's replies directly after
     * its first note, and tags every note with its discussion (#123).
     */
    /**
     * Like {@link #flatten}, with resource events (labels, milestone, state; #180) placed by
     * time among the threads, which stay together and sort by their first note.
     */
    public static List<GitLabComment> flattenWithEvents(List<GitLabDiscussion> discussions,
            List<GitLabComment> events) {
        List<List<GitLabComment>> units = new ArrayList<>();
        List<GitLabComment> notes = flatten(discussions);
        List<GitLabComment> current = null;
        for (GitLabComment note : notes) {
            if (current == null || !note.isThreadReply()) {
                current = new ArrayList<>();
                units.add(current);
            }
            current.add(note);
        }
        for (GitLabComment event : events) {
            List<GitLabComment> unit = new ArrayList<>();
            unit.add(event);
            units.add(unit);
        }
        // Stable sort: ISO-8601 UTC timestamps order correctly as strings.
        java.util.Collections.sort(units, (a, b) -> {
            String ta = a.get(0).createdAt, tb = b.get(0).createdAt;
            if (ta == null) return tb == null ? 0 : 1;
            if (tb == null) return -1;
            return ta.compareTo(tb);
        });
        List<GitLabComment> result = new ArrayList<>();
        for (List<GitLabComment> unit : units) result.addAll(unit);
        return result;
    }

    public static List<GitLabComment> flatten(List<GitLabDiscussion> discussions) {
        List<GitLabComment> result = new ArrayList<>();
        for (GitLabDiscussion d : discussions) {
            if (d.notes == null) continue;
            boolean thread = !d.individualNote && d.notes.size() > 1;
            for (int i = 0; i < d.notes.size(); i++) {
                GitLabComment note = d.notes.get(i);
                note.withThread(d.id, !d.individualNote && i > 0);
                note.setThreadPosition(!thread ? GitLabComment.THREAD_NONE
                        : i == 0 ? GitLabComment.THREAD_FIRST
                        : i == d.notes.size() - 1 ? GitLabComment.THREAD_LAST
                        : GitLabComment.THREAD_MIDDLE);
                result.add(note);
            }
            if (thread) {
                boolean resolved = false;
                for (GitLabComment note : d.notes) {
                    if (note.resolvable && note.resolved) resolved = true;
                }
                for (GitLabComment note : d.notes) note.threadResolved = resolved;
                GitLabComment.ThreadSummary summary = new GitLabComment.ThreadSummary();
                summary.replyCount = d.notes.size() - 1;
                GitLabComment last = d.notes.get(d.notes.size() - 1);
                summary.lastReplyAuthor = last.user();
                summary.lastReplyAt = last.createdAt;
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int i = 1; i < d.notes.size(); i++) {
                    GitLabUser u = d.notes.get(i).user();
                    if (u != null && seen.add(u.login())) summary.replyAuthors.add(u);
                }
                d.notes.get(0).threadSummary = summary;
            }
        }
        return result;
    }
}
