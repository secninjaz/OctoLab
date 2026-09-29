package com.gl4a.widget;

import android.app.Activity;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.GroupActivity;
import com.gl4a.activities.RepositoryActivity;
import com.gl4a.adapter.GroupTreeRows;
import com.gl4a.gitlab.model.GitLabGroup;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.service.GitLabGroupService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.MembershipRoles;
import com.gl4a.utils.RxUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import androidx.lifecycle.ViewModel;

import io.reactivex.Single;

/**
 * Expandable tree of groups and projects in a LinearLayout, like GitLab web's group overview
 * and "Your groups" (#172). Shared by the group screen and the Groups drawer list so both
 * behave the same. Subgroups expand in place via the chevron, loading their subgroups and
 * projects beneath them; tapping a row opens the group or project.
 */
public class GroupTree implements View.OnClickListener {
    private static final int PAGE_SIZE = 100;

    /**
     * What the tree shows, kept in a ViewModel so rotating the screen rebuilds the same rows,
     * expanded groups and scroll position without reloading (#172).
     */
    public static class State extends ViewModel {
        List<?> roots;
        MembershipRoles roles;
        final Map<String, List<Object>> childrenByPath = new HashMap<>();
        final Set<String> expanded = new HashSet<>();
        public int scrollY;

        public boolean hasContent() {
            return roots != null;
        }

        public void clear() {
            roots = null;
            roles = null;
            childrenByPath.clear();
            expanded.clear();
            scrollY = 0;
        }
    }

    private final Activity mActivity;
    private final ViewGroup mContainer;
    private final State mState;

    private static class Node {
        final GitLabGroup group;      // null for a project
        final GitLabProject project;  // null for a group
        final int depth;
        View row;
        boolean expanded;
        boolean loading;
        final List<Node> children = new ArrayList<>();

        Node(GitLabGroup group, GitLabProject project, int depth) {
            this.group = group;
            this.project = project;
            this.depth = depth;
        }
    }

    public GroupTree(Activity activity, ViewGroup container, State state) {
        mActivity = activity;
        mContainer = container;
        mState = state;
    }

    /** Rebuilds the rows from the retained state, e.g. after rotation. */
    public void restore() {
        mContainer.removeAllViews();
        if (mState.roots != null) {
            insertRows(0, mState.roots, 0);
        }
    }

    private static final String PREF_SORT_BY_ACTIVITY = "group_tree_sort_by_activity";
    private static final int ACTIVITY_BATCH = 50;

    /** Remembered sort for group trees and the Groups list: name (default) or activity (#175). */
    public static boolean isSortByActivity(android.content.Context context) {
        return context.getSharedPreferences(com.gl4a.fragment.SettingsFragment.PREF_NAME,
                android.content.Context.MODE_PRIVATE).getBoolean(PREF_SORT_BY_ACTIVITY, false);
    }

    /** Checks the current sort in a menu inflated from R.menu.group_sort. */
    public static void prepareSortMenu(android.view.Menu menu, android.content.Context context) {
        android.view.MenuItem item = menu.findItem(isSortByActivity(context)
                ? R.id.group_sort_activity : R.id.group_sort_name);
        if (item != null) item.setChecked(true);
    }

    /** Handles a sort menu item; returns true if the sort changed and the tree must reload. */
    public static boolean onSortItemSelected(android.view.MenuItem item,
            android.content.Context context) {
        int id = item.getItemId();
        if (id != R.id.group_sort_name && id != R.id.group_sort_activity) return false;
        boolean byActivity = id == R.id.group_sort_activity;
        item.setChecked(true);
        if (byActivity == isSortByActivity(context)) return false;
        context.getSharedPreferences(com.gl4a.fragment.SettingsFragment.PREF_NAME,
                android.content.Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_SORT_BY_ACTIVITY, byActivity).apply();
        return true;
    }

    /**
     * Sorts groups by latest activity, newest first, like projects (#175). GitLab can't order
     * groups by activity, so a group's activity is its most recently active project at any
     * depth, fetched for up to 50 groups per GraphQL request (aliased group(fullPath:) fields);
     * falls back to one REST request per group. Groups without projects go last.
     */
    public static Single<List<GitLabGroup>> sortByActivity(List<GitLabGroup> groups,
            boolean force) {
        if (groups == null || groups.isEmpty()) {
            return Single.just(groups != null ? groups : new ArrayList<>());
        }
        return Single.fromCallable(() -> {
                    fillActivityViaGraphQL(groups);
                    return sortedByActivity(groups);
                })
                .onErrorResumeNext(error -> sortByActivityRest(groups, force));
    }

    private static void fillActivityViaGraphQL(List<GitLabGroup> groups) {
        com.gl4a.gitlab.service.GitLabGraphQLService service =
                ServiceFactory.getGraphQL(com.gl4a.gitlab.service.GitLabGraphQLService.class);
        for (int start = 0; start < groups.size(); start += ACTIVITY_BATCH) {
            List<GitLabGroup> batch = groups.subList(start,
                    Math.min(start + ACTIVITY_BATCH, groups.size()));
            StringBuilder query = new StringBuilder("query {");
            for (int i = 0; i < batch.size(); i++) {
                String path = pathOf(batch.get(i)).replace("\\", "").replace("\"", "");
                query.append(" g").append(i).append(": group(fullPath: \"").append(path)
                        .append("\") { projects(includeSubgroups: true, sort: ACTIVITY_DESC,"
                                + " first: 1) { nodes { lastActivityAt } } }");
            }
            query.append(" }");
            java.util.Map<String, Object> body = new HashMap<>();
            body.put("query", query.toString());
            retrofit2.Response<com.gl4a.gitlab.model.GitLabGroupActivityResponse> response =
                    service.getGroupActivity(body).blockingGet();
            com.gl4a.gitlab.model.GitLabGroupActivityResponse payload = response.body();
            if (!response.isSuccessful() || payload == null || payload.data == null
                    || (payload.errors != null && !payload.errors.isEmpty())) {
                throw new IllegalStateException("GraphQL group activity failed: HTTP "
                        + response.code());
            }
            for (int i = 0; i < batch.size(); i++) {
                com.gl4a.gitlab.model.GitLabGroupActivityResponse.Group g = payload.data.get("g" + i);
                batch.get(i).latestActivityAt = g != null && g.projects != null
                        && g.projects.nodes != null && !g.projects.nodes.isEmpty()
                        ? g.projects.nodes.get(0).lastActivityAt : null;
            }
        }
    }

    private static List<GitLabGroup> sortedByActivity(List<GitLabGroup> groups) {
        List<GitLabGroup> sorted = new ArrayList<>(groups);
        // ISO-8601 UTC timestamps sort correctly as strings.
        java.util.Collections.sort(sorted, (a, b) -> {
            if (a.latestActivityAt == null) return b.latestActivityAt == null ? 0 : 1;
            if (b.latestActivityAt == null) return -1;
            return b.latestActivityAt.compareTo(a.latestActivityAt);
        });
        return sorted;
    }

    /** Fallback when GraphQL is unavailable: one small REST request per group. */
    private static Single<List<GitLabGroup>> sortByActivityRest(List<GitLabGroup> groups,
            boolean force) {
        GitLabGroupService service = ServiceFactory.get(GitLabGroupService.class, force);
        return io.reactivex.Observable.fromIterable(groups)
                .flatMap(group -> service.getLatestProject(group.id)
                        .map(response -> {
                            List<GitLabProject> latest = response.body();
                            group.latestActivityAt = response.isSuccessful() && latest != null
                                    && !latest.isEmpty() ? latest.get(0).lastActivityAt() : null;
                            return group;
                        })
                        .onErrorReturnItem(group)
                        .toObservable(), 8)
                .toList()
                .map(GroupTree::sortedByActivity);
    }

    /**
     * A group's direct subgroups, then its direct projects, like GitLab web; each part by name
     * (default) or by latest activity (#175).
     */
    public static Single<List<Object>> loadChildren(String groupPath, boolean force,
            boolean byActivity) {
        GitLabGroupService service = ServiceFactory.get(GitLabGroupService.class, force);
        String encoded = Uri.encode(groupPath);
        Single<List<GitLabGroup>> subgroups = service.getSubgroupsByPath(encoded, 1, PAGE_SIZE)
                .map(ApiHelpers::throwOnFailure);
        if (byActivity) {
            subgroups = subgroups.flatMap(groups -> sortByActivity(groups, force));
        }
        return Single.zip(
                subgroups,
                service.getProjectsByPath(encoded, byActivity ? "last_activity_at" : "name",
                        byActivity ? "desc" : "asc", 1, PAGE_SIZE)
                        .map(ApiHelpers::throwOnFailure),
                (groups, projects) -> {
                    List<Object> result = new ArrayList<>();
                    if (groups != null) result.addAll(groups);
                    if (projects != null) result.addAll(projects);
                    return result;
                });
    }

    /** Replaces the tree with {@code roots} (groups and/or projects) at depth 0. */
    public void setRoots(List<?> roots, MembershipRoles roles) {
        mState.clear();
        mState.roots = roots;
        mState.roles = roles;
        restore();
    }

    private static String pathOf(GitLabGroup group) {
        return group.fullPath != null ? group.fullPath : group.path;
    }

    private List<Node> insertRows(int index, List<?> items, int depth) {
        List<Node> nodes = new ArrayList<>();
        LayoutInflater inflater = mActivity.getLayoutInflater();
        for (Object item : items) {
            Node node = item instanceof GitLabGroup
                    ? new Node((GitLabGroup) item, null, depth)
                    : new Node(null, (GitLabProject) item, depth);
            View row = inflater.inflate(R.layout.row_group_tree_item, mContainer, false);
            GroupTreeRows.bind(row, node.group, node.project, depth, true, mState.roles);
            View content = row.findViewById(R.id.row_content);
            content.setTag(node);
            content.setOnClickListener(this);
            if (node.group != null) {
                ImageView expand = row.findViewById(R.id.iv_expand);
                expand.setTag(node);
                expand.setOnClickListener(this);
            }
            node.row = row;
            mContainer.addView(row, index++);
            nodes.add(node);
            // Re-expand groups that were expanded before a rebuild, from cached children.
            if (node.group != null && mState.expanded.contains(pathOf(node.group))) {
                List<Object> children = mState.childrenByPath.get(pathOf(node.group));
                if (children != null) {
                    node.children.addAll(insertRows(index, children, depth + 1));
                    index = mContainer.indexOfChild(row) + 1 + countRows(node);
                    node.expanded = true;
                    ((ImageView) row.findViewById(R.id.iv_expand)).setRotation(0);
                }
            }
        }
        return nodes;
    }

    @Override
    public void onClick(View v) {
        Node node = (Node) v.getTag();
        if (v.getId() == R.id.iv_expand) {
            toggle(node);
        } else if (node.group != null) {
            mActivity.startActivity(GroupActivity.makeIntent(mActivity,
                    node.group.fullPath != null ? node.group.fullPath : node.group.path));
        } else {
            mActivity.startActivity(RepositoryActivity.makeIntent(mActivity, node.project));
        }
    }

    private void toggle(Node node) {
        if (node.loading) return;
        ImageView expand = node.row.findViewById(R.id.iv_expand);
        if (node.expanded) {
            removeDescendants(node);
            expand.setRotation(-90);
            return;
        }
        String path = pathOf(node.group);
        List<Object> cached = mState.childrenByPath.get(path);
        if (cached != null) {
            showChildren(node, cached);
            return;
        }
        node.loading = true;
        loadChildren(path, false, isSortByActivity(mActivity))
                .compose(RxUtils::doInBackground)
                .subscribe(children -> {
                    node.loading = false;
                    mState.childrenByPath.put(path, children);
                    showChildren(node, children);
                }, error -> {
                    node.loading = false;
                    Toast.makeText(mActivity, R.string.group_tree_loading_failed,
                            Toast.LENGTH_SHORT).show();
                });
    }

    private void showChildren(Node node, List<Object> children) {
        int index = mContainer.indexOfChild(node.row);
        if (index < 0) return;
        mState.expanded.add(pathOf(node.group));
        node.children.clear();
        node.children.addAll(insertRows(index + 1, children, node.depth + 1));
        node.expanded = true;
        ((ImageView) node.row.findViewById(R.id.iv_expand)).setRotation(0);
    }

    /** Rows currently shown beneath {@code node}, at any depth. */
    private static int countRows(Node node) {
        int count = 0;
        for (Node child : node.children) {
            count += 1 + countRows(child);
        }
        return count;
    }

    private void removeDescendants(Node node) {
        for (Node child : node.children) {
            removeDescendants(child);
            mContainer.removeView(child.row);
        }
        node.children.clear();
        node.expanded = false;
        if (node.group != null) {
            mState.expanded.remove(pathOf(node.group));
        }
    }
}
