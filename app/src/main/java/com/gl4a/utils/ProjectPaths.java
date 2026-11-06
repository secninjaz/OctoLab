package com.gl4a.utils;

import androidx.annotation.Nullable;
import androidx.collection.LruCache;

import com.gl4a.Gl4Application;
import com.gl4a.gitlab.model.GitLabProject;

/**
 * THE project path for an ID in activity lists (#315), per server, for the whole session:
 * filled from project lists already loaded, so only unknown projects are looked up, once,
 * instead of each tab and each screen asking GitLab again for every project it shows.
 */
public final class ProjectPaths {
    private static final LruCache<String, String> sPaths = new LruCache<>(500);

    private ProjectPaths() {
    }

    private static String key(long projectId) {
        return Gl4Application.get().getInstanceUrl() + "|" + projectId;
    }

    @Nullable
    public static String get(long projectId) {
        return sPaths.get(key(projectId));
    }

    /** Keeps the path GitLab gave with a project, from any list or lookup. */
    public static void put(@Nullable GitLabProject project) {
        if (project == null || project.id <= 0) return;
        String path = project.pathWithNamespace != null ? project.pathWithNamespace
                : project.name;
        if (path != null) sPaths.put(key(project.id), path);
    }

    public static void putAll(@Nullable Iterable<GitLabProject> projects) {
        if (projects == null) return;
        for (GitLabProject project : projects) put(project);
    }
}
