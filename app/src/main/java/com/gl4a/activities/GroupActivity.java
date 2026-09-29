package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.gl4a.BaseActivity;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabGroup;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.service.GitLabGroupService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.MembershipRoles;
import com.gl4a.utils.UiUtils;
import com.gl4a.widget.GroupTree;
import com.gl4a.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Single;

/**
 * Group overview (#103): name, path, description, links to the group's projects and members,
 * and its subgroups. Opened for group namespaces, which used to be opened as users.
 */
public class GroupActivity extends BaseActivity implements
        View.OnClickListener, SwipeRefreshLayout.ChildScrollDelegate {
    /** @param groupPath full path, e.g. "fdroid" or "parent/child" */
    public static Intent makeIntent(Context context, String groupPath) {
        if (groupPath == null) return null;
        return new Intent(context, GroupActivity.class).putExtra("path", groupPath);
    }

    private static final int ID_LOADER_GROUP = 0;
    private static final int ID_LOADER_SUBGROUPS = 1;
    private static final int TREE_PAGE_SIZE = 100;

    private String mGroupPath;
    private GitLabGroup mGroup;
    private View mRootView;
    private GroupTree.State mTreeState;
    private GroupTree mTree;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.group);
        mRootView = findViewById(R.id.root);
        // Survives rotation, so the tree keeps its rows, expanded groups and scroll (#172).
        mTreeState = new androidx.lifecycle.ViewModelProvider(this).get(GroupTree.State.class);
        mTree = new GroupTree(this, findViewById(R.id.ll_subgroups), mTreeState);
        findViewById(R.id.tv_projects).setOnClickListener(this);
        findViewById(R.id.tv_members).setOnClickListener(this);
        setChildScrollDelegate(this);
        setContentShown(false);
        loadGroup(false);
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mGroupPath = extras.getString("path");
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return mGroup != null && mGroup.name != null ? mGroup.name : mGroupPath;
    }

    @Nullable
    @Override
    protected String getActionBarSubtitle() {
        return getString(R.string.group);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.group, menu);
        getMenuInflater().inflate(R.menu.group_sort, menu);
        GroupTree.prepareSortMenu(menu, this);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (GroupTree.onSortItemSelected(item, this)) {
            mTreeState.clear();
            loadSubgroups(true);
            return true;
        }
        if (item.getItemId() == R.id.group_sort_name
                || item.getItemId() == R.id.group_sort_activity) {
            return true;
        }
        if (item.getItemId() == R.id.browser) {
            String url = mGroup != null && mGroup.webUrl != null ? mGroup.webUrl
                    : com.gl4a.Gl4Application.get().getInstanceUrl() + "/" + mGroupPath;
            IntentUtils.launchBrowser(this, Uri.parse(url));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected Intent navigateUp() {
        String path = groupPath();
        int lastSlash = path != null ? path.lastIndexOf('/') : -1;
        // A subgroup's parent is always a group, never a user namespace.
        return lastSlash > 0
                ? makeIntent(this, path.substring(0, lastSlash))
                : getToplevelActivityIntent();
    }

    @Override
    public boolean canChildScrollUp() {
        return UiUtils.canViewScrollUp(mRootView);
    }

    @Override
    public void onRefresh() {
        mGroup = null;
        mTreeState.clear();
        setContentShown(false);
        loadGroup(true);
        super.onRefresh();
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.tv_projects) {
            startActivity(RepositoryListActivity.makeIntent(this, groupPath(), true));
        } else if (id == R.id.tv_members) {
            startActivity(OrganizationMemberListActivity.makeIntent(this, groupPath()));
        }
    }

    private String groupPath() {
        return mGroup != null && mGroup.fullPath != null ? mGroup.fullPath : mGroupPath;
    }

    private void loadGroup(boolean force) {
        GitLabGroupService service = ServiceFactory.get(GitLabGroupService.class, force);
        service.getGroupByPath(Uri.encode(mGroupPath))
                .map(ApiHelpers::throwOnFailure)
                .compose(makeLoaderSingle(ID_LOADER_GROUP, force))
                .subscribe(group -> {
                    mGroup = group;
                    bindGroup();
                    setContentShown(true);
                    loadSubgroups(force);
                }, this::handleLoadFailure);
    }

    private void bindGroup() {
        ImageView avatar = findViewById(R.id.iv_avatar);
        AvatarHandler.assignGroupLogo(avatar, mGroup);
        ((TextView) findViewById(R.id.tv_name)).setText(mGroup.name);
        ((TextView) findViewById(R.id.tv_path)).setText(mGroup.fullPath);
        TextView description = findViewById(R.id.tv_description);
        description.setVisibility(TextUtils.isEmpty(mGroup.description) ? View.GONE : View.VISIBLE);
        description.setText(mGroup.description);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getActionBarTitle());
        }
    }

    /** Tree data and the viewer's roles, cached together so badges survive rotation (#172). */
    private static class TreeData {
        final List<Object> children;
        final MembershipRoles roles;

        TreeData(List<Object> children, MembershipRoles roles) {
            this.children = children;
            this.roles = roles;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mTreeState.scrollY = mRootView.getScrollY();
    }

    private void loadSubgroups(boolean force) {
        if (!force && mTreeState.hasContent()) {
            // Rebuilt after rotation: same rows and expanded groups, no reload.
            findViewById(R.id.tv_subgroups_header).setVisibility(View.VISIBLE);
            mTree.restore();
            final int scrollY = mTreeState.scrollY;
            mRootView.post(() -> mRootView.scrollTo(0, scrollY));
            return;
        }
        GroupTree.loadChildren(groupPath(), force, GroupTree.isSortByActivity(this))
                .zipWith(MembershipRoles.load(), TreeData::new)
                .compose(makeLoaderSingle(ID_LOADER_SUBGROUPS, force))
                .subscribe(data -> {
                    findViewById(R.id.tv_subgroups_header)
                            .setVisibility(data.children.isEmpty() ? View.GONE : View.VISIBLE);
                    mTree.setRoots(data.children, data.roles);
                }, error -> {
                    // Non-fatal: the overview is still useful without the tree.
                    findViewById(R.id.tv_subgroups_header).setVisibility(View.GONE);
                });
    }
}
