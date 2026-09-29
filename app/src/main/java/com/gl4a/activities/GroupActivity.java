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
import com.gl4a.gitlab.service.GitLabGroupService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.UiUtils;
import com.gl4a.widget.SwipeRefreshLayout;

import java.util.List;

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

    private String mGroupPath;
    private GitLabGroup mGroup;
    private View mRootView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.group);
        mRootView = findViewById(R.id.root);
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
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.browser) {
            String url = mGroup != null && mGroup.webUrl != null ? mGroup.webUrl
                    : com.gl4a.Gl4Application.get().getInstanceUrl() + "/" + mGroupPath;
            IntentUtils.launchBrowser(this, Uri.parse(url));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean canChildScrollUp() {
        return UiUtils.canViewScrollUp(mRootView);
    }

    @Override
    public void onRefresh() {
        mGroup = null;
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
        } else if (v.getTag() instanceof GitLabGroup) {
            startActivity(makeIntent(this, ((GitLabGroup) v.getTag()).fullPath));
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
        AvatarHandler.assignAvatarLogo(avatar, mGroup.name, mGroup.id, mGroup.avatarUrl);
        ((TextView) findViewById(R.id.tv_name)).setText(mGroup.name);
        ((TextView) findViewById(R.id.tv_path)).setText(mGroup.fullPath);
        TextView description = findViewById(R.id.tv_description);
        description.setVisibility(TextUtils.isEmpty(mGroup.description) ? View.GONE : View.VISIBLE);
        description.setText(mGroup.description);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getActionBarTitle());
        }
    }

    private void loadSubgroups(boolean force) {
        GitLabGroupService service = ServiceFactory.get(GitLabGroupService.class, force);
        service.getSubgroupsByPath(Uri.encode(groupPath()), 1, 100)
                .map(ApiHelpers::throwOnFailure)
                .compose(makeLoaderSingle(ID_LOADER_SUBGROUPS, force))
                // Non-fatal: the overview is still useful without the subgroup list.
                .subscribe(this::fillSubgroups, error -> fillSubgroups(null));
    }

    private void fillSubgroups(@Nullable List<GitLabGroup> subgroups) {
        ViewGroup container = findViewById(R.id.ll_subgroups);
        container.removeAllViews();
        int count = subgroups != null ? subgroups.size() : 0;
        findViewById(R.id.tv_subgroups_header).setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < count; i++) {
            GitLabGroup subgroup = subgroups.get(i);
            View row = inflater.inflate(R.layout.selectable_label_with_avatar, container, false);
            row.setTag(subgroup);
            row.setOnClickListener(this);
            AvatarHandler.assignAvatarLogo(row.findViewById(R.id.iv_gravatar),
                    subgroup.name, subgroup.id, subgroup.avatarUrl);
            ((TextView) row.findViewById(R.id.tv_title)).setText(subgroup.name);
            container.addView(row);
        }
    }
}
