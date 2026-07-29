package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;
import java.util.List;

public class GitLabCommitDiscussion {
    @Json(name = "id") public String id;
    @Json(name = "notes") public List<GitLabComment> notes;
}
