package com.gl4a.gitlab.model;

import com.squareup.moshi.Json;

public class GitLabDiff {
    @Json(name = "diff") public String diff;
    @Json(name = "new_path") public String newPath;
    @Json(name = "old_path") public String oldPath;
    @Json(name = "a_mode") public String aMode;
    @Json(name = "b_mode") public String bMode;
    @Json(name = "new_file") public boolean newFile;
    @Json(name = "renamed_file") public boolean renamedFile;
    @Json(name = "deleted_file") public boolean deletedFile;
    /** True when the diff payload was too large for GitLab to return inline. */
    @Json(name = "collapsed") public boolean collapsed;
    @Json(name = "too_large") public boolean tooLarge;
    /** True for auto-generated files (GitLab 15.7+). */
    @Json(name = "generated_file") public boolean generatedFile;

    // GitHub GitHubFile compat
    public String filename() { return newPath; }
    public String previousFilename() { return oldPath; }
    public String patch() { return diff; }
    public String status() {
        if (newFile) return "added";
        if (deletedFile) return "removed";
        if (renamedFile) return "renamed";
        return "modified";
    }
    public int additions() { return 0; }
    public int deletions() { return 0; }
    public int changes() { return 0; }
}
