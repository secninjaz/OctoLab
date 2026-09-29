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

    /** A group's direct subgroups, then its direct projects, like GitLab web. */
    public static Single<List<Object>> loadChildren(String groupPath, boolean force) {
        GitLabGroupService service = ServiceFactory.get(GitLabGroupService.class, force);
        String encoded = Uri.encode(groupPath);
        return Single.zip(
                service.getSubgroupsByPath(encoded, 1, PAGE_SIZE).map(ApiHelpers::throwOnFailure),
                service.getProjectsByPath(encoded, 1, PAGE_SIZE).map(ApiHelpers::throwOnFailure),
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
        loadChildren(path, false)
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
