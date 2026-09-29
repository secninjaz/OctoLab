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
    public static List<GitLabComment> flatten(List<GitLabDiscussion> discussions) {
        List<GitLabComment> result = new ArrayList<>();
        for (GitLabDiscussion d : discussions) {
            if (d.notes == null) continue;
            for (int i = 0; i < d.notes.size(); i++) {
                GitLabComment note = d.notes.get(i);
                note.withThread(d.id, !d.individualNote && i > 0);
                result.add(note);
            }
        }
        return result;
    }
}
