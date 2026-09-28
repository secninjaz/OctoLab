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
 * Loads the award emoji of every note on an issue or MR in one paged GraphQL query, so the
 * timeline can show reactions from loaded data instead of fetching them per row while
 * scrolling (#162). REST's notes API does not include award emoji.
 */
public final class NoteReactionsLoader {
    private static final String QUERY =
            "query($id: %s!, $after: String) {"
            + " noteable: %s(id: $id) {"
            + " notes(first: 100, after: $after) {"
            + " pageInfo { hasNextPage endCursor }"
            + " nodes { id awardEmoji { nodes { name user { username } } } }"
            + " } } }";
    private static final int NOTES_PER_QUERY = 50;

    private NoteReactionsLoader() {}

    /**
     * Returns note ID → reactions for every note of the issue or MR with the given global
     * (non-iid) ID. Emits an empty map if GraphQL is unavailable or the query fails; notes
     * missing from the map fall back to the per-note REST fetch.
     */
    public static Single<Map<Long, List<GitLabReaction>>> load(boolean isMergeRequest,
            long globalId, boolean bypassCache) {
        return Single.fromCallable(() -> loadAllPages(isMergeRequest, globalId, bypassCache))
                .onErrorReturn(error -> {
                    Log.d(Gl4Application.LOG_TAG, "GraphQL note reactions unavailable", error);
                    return Collections.<Long, List<GitLabReaction>>emptyMap();
                });
    }

    /**
     * Like {@link #load}, for notes with no noteable to page through (commit comments, #159):
     * looks the notes up by ID, batched into aliased note(id:) fields in as few queries as
     * possible. Emits an empty map if GraphQL is unavailable or the query fails.
     */
    public static Single<Map<Long, List<GitLabReaction>>> loadForNotes(List<Long> noteIds) {
        return Single.fromCallable(() -> loadByIds(noteIds))
                .onErrorReturn(error -> {
                    Log.d(Gl4Application.LOG_TAG, "GraphQL note reactions unavailable", error);
                    return Collections.<Long, List<GitLabReaction>>emptyMap();
                });
    }

    private static Map<Long, List<GitLabReaction>> loadByIds(List<Long> noteIds) {
        GitLabGraphQLService service = ServiceFactory.getGraphQL(GitLabGraphQLService.class);
        Map<Long, List<GitLabReaction>> result = new HashMap<>();
        for (int start = 0; start < noteIds.size(); start += NOTES_PER_QUERY) {
            List<Long> batch = noteIds.subList(start,
                    Math.min(start + NOTES_PER_QUERY, noteIds.size()));
            StringBuilder query = new StringBuilder("query {");
            for (int i = 0; i < batch.size(); i++) {
                query.append(" n").append(i).append(": note(id: \"gid://gitlab/Note/")
                        .append(batch.get(i)).append("\") {")
                        .append(" id awardEmoji { nodes { name user { username } } } }");
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
                if (noteId > 0) result.put(noteId, toReactions(note));
            }
        }
        return result;
    }

    /** Stores the loaded reactions on each comment that has an entry in {@code reactions}. */
    public static List<GitLabComment> apply(List<GitLabComment> comments,
            Map<Long, List<GitLabReaction>> reactions) {
        String ownLogin = Gl4Application.get().getAuthLogin();
        for (GitLabComment comment : comments) {
            List<GitLabReaction> details = reactions.get(comment.id());
            if (details != null) {
                comment.withReactionDetails(details, ownLogin);
            }
        }
        return comments;
    }

    private static Map<Long, List<GitLabReaction>> loadAllPages(boolean isMergeRequest,
            long globalId, boolean bypassCache) throws Exception {
        GitLabGraphQLService service = ServiceFactory.getGraphQL(GitLabGraphQLService.class);
        String query = isMergeRequest
                ? String.format(QUERY, "MergeRequestID", "mergeRequest")
                : String.format(QUERY, "IssueID", "issue");
        String gid = (isMergeRequest ? "gid://gitlab/MergeRequest/" : "gid://gitlab/Issue/")
                + globalId;

        Map<Long, List<GitLabReaction>> result = new HashMap<>();
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
                    if (noteId > 0) result.put(noteId, toReactions(note));
                }
            }
            cursor = notes.pageInfo != null && notes.pageInfo.hasNextPage
                    ? notes.pageInfo.endCursor : null;
        } while (cursor != null);
        return result;
    }

    private static List<GitLabReaction> toReactions(GitLabNoteReactionsResponse.Note note) {
        List<GitLabReaction> reactions = new ArrayList<>();
        if (note.awardEmoji == null || note.awardEmoji.nodes == null) return reactions;
        for (GitLabNoteReactionsResponse.AwardEmoji emoji : note.awardEmoji.nodes) {
            GitLabReaction r = new GitLabReaction();
            r.name = emoji.name;
            r.user = emoji.user != null ? GitLabUser.create(emoji.user.username, 0) : null;
            reactions.add(r);
        }
        return reactions;
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
