package com.gl4a.utils;

import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabTodo;
import com.gl4a.model.NotificationHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * THE grouping of To-dos and Participating by project (#287, #288): a header per project,
 * newest group first, and under it, unless expanded, all its unread items topped up with its
 * newest read ones to {@link #COLLAPSED_SIZE} in total; nothing when none is unread. The
 * header's ▾/▴ expands and collapses the group.
 */
public final class FeedGroups {
    public static final int COLLAPSED_SIZE = 3;

    /**
     * Expands or collapses a header's group in {@code expanded}; returns whether it collapsed,
     * so the list can go back to the header (call {@link #scrollToHeader} after rebuilding).
     */
    public static boolean toggle(Set<Long> expanded, NotificationHolder header) {
        long key = projectKey(header.repository);
        if (expanded.remove(key)) return true;
        expanded.add(key);
        return false;
    }

    /**
     * Back to a project's header after its group collapsed, so the reader isn't lost: when it
     * was off screen or under the pinned header (collapsed from there). A header in full view
     * stays where it is (#290).
     */
    public static void scrollToHeader(androidx.recyclerview.widget.RecyclerView list,
            com.gl4a.adapter.RootAdapter<NotificationHolder, ?> adapter, GitLabProject project) {
        long key = projectKey(project);
        for (int i = 0; i < adapter.getCount(); i++) {
            NotificationHolder holder = adapter.getItem(i);
            if (holder.notification == null && projectKey(holder.repository) == key) {
                androidx.recyclerview.widget.RecyclerView.LayoutManager lm =
                        list.getLayoutManager();
                if (lm instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                    androidx.recyclerview.widget.LinearLayoutManager linear =
                            (androidx.recyclerview.widget.LinearLayoutManager) lm;
                    int position = adapter.getAdapterPositionForIndex(i);
                    // Where it was before the list was rebuilt: the groups above are unchanged
                    int first = linear.findFirstCompletelyVisibleItemPosition();
                    int last = linear.findLastCompletelyVisibleItemPosition();
                    boolean inView = first != androidx.recyclerview.widget.RecyclerView.NO_POSITION
                            && position >= first && position <= last;
                    if (!inView) linear.scrollToPositionWithOffset(position, 0);
                }
                return;
            }
        }
    }

    private FeedGroups() {
    }

    /** The project a header or item belongs to, as the groups key it. */
    public static long projectKey(GitLabProject project) {
        return project != null && project.id > 0 ? project.id : 0L;
    }

    /**
     * @param items    newest first, each with its read state ({@link GitLabTodo#isUnread})
     * @param expanded the projects whose groups show everything
     */
    public static List<NotificationHolder> build(List<GitLabTodo> items, Set<Long> expanded) {
        Map<Long, GitLabProject> projects = new LinkedHashMap<>();
        Map<Long, List<GitLabTodo>> byProject = new LinkedHashMap<>();
        for (GitLabTodo item : items) {
            GitLabProject project = item.repository();
            long key = projectKey(project);
            if (!byProject.containsKey(key)) {
                projects.put(key, project);
                byProject.put(key, new ArrayList<>());
            }
            byProject.get(key).add(item);
        }

        List<NotificationHolder> result = new ArrayList<>();
        for (Map.Entry<Long, GitLabProject> entry : projects.entrySet()) {
            List<GitLabTodo> group = byProject.get(entry.getKey());
            List<GitLabTodo> shown = new ArrayList<>();
            int unread = 0;
            for (GitLabTodo item : group) {
                if (item.isUnread()) unread++;
            }
            // Without a project there's no header to expand it from: all of it
            boolean isExpanded = expanded.contains(entry.getKey()) || entry.getValue() == null;
            int readShown = 0;
            for (GitLabTodo item : group) {
                if (isExpanded || item.isUnread()) {
                    shown.add(item);
                } else if (unread > 0 && unread + readShown < COLLAPSED_SIZE) {
                    shown.add(item);
                    readShown++;
                }
            }
            if (entry.getValue() != null) {
                NotificationHolder header = new NotificationHolder(entry.getValue());
                header.setIsRead(unread == 0);
                header.expanded = isExpanded;
                header.canExpand = isExpanded || shown.size() < group.size();
                result.add(header);
            }
            for (GitLabTodo item : shown) {
                result.add(new NotificationHolder(item));
            }
        }
        return result;
    }
}
