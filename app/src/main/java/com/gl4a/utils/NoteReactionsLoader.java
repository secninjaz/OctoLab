package com.gl4a.utils;

import android.util.Log;

import com.gl4a.Gl4Application;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabNoteReactionsResponse;
import com.gl4a.gitlab.model.GitLabNotesByIdResponse;
import com.gl4a.gitlab.model.GitLabReaction;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.service.GitLabGraphQLService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.Single;
import retrofit2.Response;

/**
 * Loads the award emoji and edit details of every note on an issue or MR in one paged GraphQL
 * query, so the timeline can show reactions (#162) and "Edited … by …" (#151) from loaded data
 * instead of fetching them per row while scrolling. REST's notes API has neither.
 */
public final class NoteReactionsLoader {
    /** A note's reactions and who last edited it (null editor: never edited). */
    public static final class NoteDetails {
        final List<GitLabReaction> reactions;
        final String lastEditedAt;
        final GitLabUser lastEditedBy;
        final String bodyHtml;

        NoteDetails(List<GitLabReaction> reactions, String lastEditedAt, GitLabUser lastEditedBy,
                String bodyHtml) {
            this.reactions = reactions;
            this.lastEditedAt = lastEditedAt;
            this.lastEditedBy = lastEditedBy;
            this.bodyHtml = bodyHtml;
        }
    }

    // bodyHtml: GitLab's stored rendering, shown as GitLab web does instead of rendering each
    // note again on the markdown API (#199)
    private static final String NOTE_FIELDS = "id bodyHtml"
            + " lastEditedAt lastEditedBy { username name }"
            + " awardEmoji { nodes { name user { username } } }";
    private static final String QUERY =
            "query($id: %s!, $after: String) {"
            + " noteable: %s(id: $id) {"
            + " notes(first: 100, after: $after) {"
            + " pageInfo { hasNextPage endCursor }"
            + " nodes { " + NOTE_FIELDS + " }"
            + " } } }";
    private static final int NOTES_PER_QUERY = 50;

    private NoteReactionsLoader() {}

    /**
     * Returns note ID → reactions and edit details for every note of the issue or MR with the
     * given global (non-iid) ID. Emits an empty map if GraphQL is unavailable or the query fails; notes
     * missing from the map fall back to the per-note REST fetch.
     */
    public static Single<Map<Long, NoteDetails>> load(boolean isMergeRequest,
            long globalId, boolean bypassCache) {
        return Single.fromCallable(() -> loadAllPages(isMergeRequest, globalId, bypassCache))
                .onErrorReturn(error -> {
                    Log.d(Gl4Application.LOG_TAG, "GraphQL note reactions unavailable", error);
                    return Collections.<Long, NoteDetails>emptyMap();
                });
    }

    /**
     * Like {@link #load}, for notes with no noteable to page through (commit comments, #159):
     * looks the notes up by ID, batched into aliased note(id:) fields in as few queries as
     * possible. Emits an empty map if GraphQL is unavailable or the query fails.
     */
    public static Single<Map<Long, NoteDetails>> loadForNotes(List<Long> noteIds) {
        return Single.fromCallable(() -> loadByIds(noteIds))
                .onErrorReturn(error -> {
                    Log.d(Gl4Application.LOG_TAG, "GraphQL note reactions unavailable", error);
                    return Collections.<Long, NoteDetails>emptyMap();
                });
    }

    private static Map<Long, NoteDetails> loadByIds(List<Long> noteIds) {
        GitLabGraphQLService service = ServiceFactory.getGraphQL(GitLabGraphQLService.class);
        Map<Long, NoteDetails> result = new HashMap<>();
        for (int start = 0; start < noteIds.size(); start += NOTES_PER_QUERY) {
            List<Long> batch = noteIds.subList(start,
                    Math.min(start + NOTES_PER_QUERY, noteIds.size()));
            StringBuilder query = new StringBuilder("query {");
            for (int i = 0; i < batch.size(); i++) {
                query.append(" n").append(i).append(": note(id: \"gid://gitlab/Note/")
                        .append(batch.get(i)).append("\") {")
                        .append(" ").append(NOTE_FIELDS).append(" }");
            }
            query.append(" }");
            Map<String, Object> body = new HashMap<>();
            body.put("query", query.toString());

            Response<GitLabNotesByIdResponse> response =
                    service.getNotesById(body).blockingGet();
            GitLabNotesByIdResponse payload = response.body();
            if (!response.isSuccessful() || payload == null
                    || (payload.errors != null && !payload.errors.isEmpty())
                    || payload.data == null) {
                throw new IllegalStateException("GraphQL note reactions query failed: HTTP "
                        + response.code());
            }
            for (GitLabNoteReactionsResponse.Note note : payload.data.values()) {
                if (note == null) continue;
                long noteId = parseNoteId(note.id);
                if (noteId > 0) result.put(noteId, toDetails(note));
            }
        }
        return result;
    }

    /** Stores the loaded reactions and edit details on each comment that has an entry. */
    public static List<GitLabComment> apply(List<GitLabComment> comments,
            Map<Long, NoteDetails> details) {
        String ownLogin = Gl4Application.get().getAuthLogin();
        for (GitLabComment comment : comments) {
            NoteDetails note = details.get(comment.id());
            if (note != null) {
                comment.withReactionDetails(note.reactions, ownLogin);
                comment.withEditInfo(note.lastEditedAt, note.lastEditedBy);
                comment.withStoredHtml(note.bodyHtml);
            }
        }
        return comments;
    }

    private static Map<Long, NoteDetails> loadAllPages(boolean isMergeRequest,
            long globalId, boolean bypassCache) throws Exception {
        GitLabGraphQLService service = ServiceFactory.getGraphQL(GitLabGraphQLService.class);
        String query = isMergeRequest
                ? String.format(QUERY, "MergeRequestID", "mergeRequest")
                : String.format(QUERY, "IssueID", "issue");
        String gid = (isMergeRequest ? "gid://gitlab/MergeRequest/" : "gid://gitlab/Issue/")
                + globalId;

        Map<Long, NoteDetails> result = new HashMap<>();
        String cursor = null;
        do {
            Map<String, Object> variables = new HashMap<>();
            variables.put("id", gid);
            if (cursor != null) variables.put("after", cursor);
            Map<String, Object> body = new HashMap<>();
            body.put("query", query);
            body.put("variables", variables);

            Response<GitLabNoteReactionsResponse> response =
                    service.getNoteReactions(body).blockingGet();
            GitLabNoteReactionsResponse payload = response.body();
            // GraphQL reports query errors (e.g. unknown fields on older servers) with HTTP 200.
            if (!response.isSuccessful() || payload == null
                    || (payload.errors != null && !payload.errors.isEmpty())
                    || payload.data == null || payload.data.noteable == null
                    || payload.data.noteable.notes == null) {
                throw new IllegalStateException("GraphQL note reactions query failed: HTTP "
                        + response.code());
            }
            GitLabNoteReactionsResponse.Notes notes = payload.data.noteable.notes;
            if (notes.nodes != null) {
                for (GitLabNoteReactionsResponse.Note note : notes.nodes) {
                    long noteId = parseNoteId(note.id);
                    if (noteId > 0) result.put(noteId, toDetails(note));
                }
            }
            cursor = notes.pageInfo != null && notes.pageInfo.hasNextPage
                    ? notes.pageInfo.endCursor : null;
        } while (cursor != null);
        return result;
    }

    private static NoteDetails toDetails(GitLabNoteReactionsResponse.Note note) {
        List<GitLabReaction> reactions = new ArrayList<>();
        if (note.awardEmoji != null && note.awardEmoji.nodes != null) {
            for (GitLabNoteReactionsResponse.AwardEmoji emoji : note.awardEmoji.nodes) {
                GitLabReaction r = new GitLabReaction();
                r.name = emoji.name;
                r.user = emoji.user != null ? GitLabUser.create(emoji.user.username, 0) : null;
                reactions.add(r);
            }
        }
        GitLabUser editor = null;
        if (note.lastEditedBy != null) {
            editor = GitLabUser.create(note.lastEditedBy.username, 0);
            editor.name = note.lastEditedBy.name;
        }
        return new NoteDetails(reactions, note.lastEditedAt, editor, note.bodyHtml);
    }

    /** Extracts the numeric note ID from gid://gitlab/{Note,DiscussionNote,DiffNote}/123. */
    private static long parseNoteId(String gid) {
        if (gid == null) return -1;
        try {
            return Long.parseLong(gid.substring(gid.lastIndexOf('/') + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
