package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

/** GraphQL AwardEmoji node, as returned by note.awardEmoji.nodes and the award*emoji mutations. */
public class GitLabGraphQLAwardEmoji {
    @Json(name = "name") public String name; // emoji name, e.g. "thumbsup"
    @Json(name = "user") public GitLabGraphQLUser user;

    /** Converts to the REST-shaped GitLabReaction used by ReactionBar. */
    public GitLabReaction toGitLabReaction() {
        GitLabReaction reaction = new GitLabReaction();
        reaction.id = syntheticId(name, user != null ? user.username : null);
        reaction.name = name;
        reaction.user = user != null ? user.toGitLabUser() : null;
        return reaction;
    }

    /**
     * GraphQL's AwardEmoji type has no id field, but ReactionBar identifies reactions by id.
     * A user can award a given emoji to a note only once, so (name, username) is unique per note.
     * The id is only used locally; removal goes through awardEmojiRemove by name.
     */
    private static long syntheticId(String name, String username) {
        long id = ((name + ":" + username).hashCode() & 0x7fffffffL);
        return id != 0L ? id : 1L;
    }
}
