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
    // GitLab's diff APIs don't return line counts, so count them from the unified diff;
    // these were hard-coded to 0, so every summary read "0 additions and 0 deletions" (#184).
    private transient int mAdditions = -1;
    private transient int mDeletions = -1;

    public int additions() { countLines(); return mAdditions; }
    public int deletions() { countLines(); return mDeletions; }
    public int changes() { return additions() + deletions(); }

    private void countLines() {
        if (mAdditions >= 0) return;
        int added = 0, removed = 0;
        if (diff != null) {
            for (String line : diff.split("\n", -1)) {
                if (line.startsWith("+++") || line.startsWith("---")) continue;
                if (line.startsWith("+")) added++;
                else if (line.startsWith("-")) removed++;
            }
        }
        mAdditions = added;
        mDeletions = removed;
    }
}
