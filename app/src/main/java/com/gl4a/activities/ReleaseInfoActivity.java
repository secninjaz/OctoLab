/*
 * Copyright 2014 Danny Baumann
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.gl4a.BaseActivity;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.adapter.ReleaseAssetAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.gitlab.model.GitLabRelease;
import com.gl4a.gitlab.service.GitLabReleaseService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.DownloadUtils;
import com.gl4a.utils.HttpImageGetter;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.SingleFactory;
import com.gl4a.utils.StringUtils;
import com.gl4a.utils.UiUtils;
import com.gl4a.widget.SwipeRefreshLayout;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class ReleaseInfoActivity extends BaseActivity implements
        View.OnClickListener, SwipeRefreshLayout.ChildScrollDelegate,
        RootAdapter.OnItemClickListener<GitLabRelease.Asset>,
        RootAdapter.OnItemLongClickListener<GitLabRelease.Asset> {

    public static Intent makeIntent(Context context, String repoOwner, String repoName,
            String tagName) {
        return new Intent(context, ReleaseInfoActivity.class)
                .putExtra("owner", repoOwner)
                .putExtra("repo", repoName)
                .putExtra("tag", tagName);
    }

    private static final int ID_LOADER_RELEASE = 0;

    private GitLabRelease mRelease;
    private String mRepoOwner;
    private String mRepoName;
    private String mTagName;

    private View mRootView;
    private HttpImageGetter mImageGetter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.release);

        mRootView = findViewById(R.id.root);
        mImageGetter = new HttpImageGetter(this);
        setChildScrollDelegate(this);

        setContentShown(false);
        loadRelease(false);
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return getString(R.string.release_title);
    }

    @Nullable
    @Override
    protected String getActionBarSubtitle() {
        return mRepoOwner + "/" + mRepoName;
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mRepoOwner = extras.getString("owner");
        mRepoName = extras.getString("repo");
        mTagName = extras.getString("tag");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.release, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.browser && mRelease != null) {
            // GitLab release web URL: <instance>/<owner>/<repo>/-/releases/<tagName>
            Uri uri = IntentUtils.createBaseUriForRepo(mRepoOwner, mRepoName)
                    .appendPath("-")
                    .appendPath("releases")
                    .appendPath(mRelease.tagName())
                    .build();
            IntentUtils.launchBrowser(this, uri);
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
        mRelease = null;
        setContentShown(false);
        mImageGetter.clearHtmlCache();
        loadRelease(true);
        super.onRefresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mImageGetter.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mImageGetter.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mImageGetter.destroy();
    }

    @Override
    protected Intent navigateUp() {
        return ReleaseListActivity.makeIntent(this, mRepoOwner, mRepoName);
    }

    private void handleReleaseReady() {
        String name = mRelease.name();
        if (TextUtils.isEmpty(name)) {
            name = mRelease.tagName();
        }
        getSupportActionBar().setTitle(name);
        invalidateOptionsMenu();
        fillData();
    }

    private void fillData() {
        ImageView gravatar = findViewById(R.id.iv_gravatar);
        if (mRelease.author() != null) {
            AvatarHandler.assignAvatar(gravatar, mRelease.author());
            gravatar.setOnClickListener(this);
        } else {
            // CI/pipeline-created releases may have no author; show a default avatar.
            gravatar.setImageDrawable(
                    new AvatarHandler.DefaultAvatarDrawable(null, null));
            gravatar.setOnClickListener(null);
        }

        TextView details = findViewById(R.id.tv_releaseinfo);
        String detailsText = getString(R.string.release_details,
                ApiHelpers.getUserLogin(this, mRelease.author()),
                StringUtils.formatRelativeTime(this, mRelease.publishedAt(), true));
        StringUtils.applyBoldTagsAndSetText(details, detailsText);

        TextView releaseType = findViewById(R.id.tv_releasetype);
        if (mRelease.isPrerelease()) {
            releaseType.setText(R.string.release_type_prerelease);
        } else {
            releaseType.setText(R.string.release_type_final);
        }

        TextView tag = findViewById(R.id.tv_releasetag);
        tag.setText(getString(R.string.release_tag, mRelease.tagName()));
        tag.setOnClickListener(this);

        TextView body = findViewById(R.id.tv_release_notes);
        String releaseMarkdown = mRelease.body();
        if (!TextUtils.isEmpty(releaseMarkdown)) {
            mImageGetter.bindMarkdown(body, releaseMarkdown, mRelease.tagName().hashCode());
        } else {
            body.setText(R.string.release_no_releasenotes);
        }

        // GitLab releases: custom links take priority; source archives (zip/tar.gz) as fallback
        List<GitLabRelease.Asset> customAssets = mRelease.assets();
        List<GitLabRelease.Source> sources = mRelease.sourceAssets();
        boolean hasAssets = (customAssets != null && !customAssets.isEmpty())
                || (sources != null && !sources.isEmpty());

        if (hasAssets) {
            RecyclerView downloadsList = findViewById(R.id.download_list);
            ReleaseAssetAdapter adapter = new ReleaseAssetAdapter(this);
            if (customAssets != null && !customAssets.isEmpty()) {
                adapter.addAll(customAssets);
            } else if (sources != null) {
                // Convert sources to Asset-compatible display items
                for (GitLabRelease.Source s : sources) {
                    GitLabRelease.Asset a = new GitLabRelease.Asset();
                    a.name = s.name() + " archive";
                    a.url = s.browserDownloadUrl();
                    adapter.add(a);
                }
            }
            adapter.setOnItemClickListener(this);
            adapter.setOnItemLongClickListener(this);
            downloadsList.setLayoutManager(new LinearLayoutManager(this));
            downloadsList.setNestedScrollingEnabled(false);
            downloadsList.setAdapter(adapter);
        } else {
            findViewById(R.id.downloads).setVisibility(View.GONE);
        }
    }

    @Override
    public void onItemClick(GitLabRelease.Asset item) {
        DownloadUtils.enqueueDownloadWithPermissionCheck(this, item);
    }

    @Override
    public boolean onItemLongClick(GitLabRelease.Asset item) {
        String label = "Release asset " + item.name();
        IntentUtils.copyToClipboard(this, label, item.browserDownloadUrl());
        Snackbar.make(getRootLayout(), R.string.link_copied, Snackbar.LENGTH_SHORT).show();
        return true;
    }

    @Override
    public void onClick(View v) {
        Intent intent = null;

        switch (v.getId()) {
            case R.id.tv_releasetag:
                intent = RepositoryActivity.makeIntent(this,
                        mRepoOwner, mRepoName, mRelease.tagName());
                break;
            case R.id.iv_gravatar:
                if (mRelease.author() != null) {
                    intent = UserActivity.makeIntent(this, mRelease.author());
                }
                break;
        }

        if (intent != null) {
            startActivity(intent);
        }
    }

    private void loadRelease(boolean force) {
        SingleFactory.getProjectId(mRepoOwner, mRepoName)
                .flatMap(projectId -> {
                    GitLabReleaseService service =
                            ServiceFactory.get(GitLabReleaseService.class, force);
                    return service.getRelease(projectId, mTagName)
                            .map(ApiHelpers::throwOnFailure);
                })
                .compose(makeLoaderSingle(ID_LOADER_RELEASE, force))
                .subscribe(result -> {
                    mRelease = result;
                    handleReleaseReady();
                    setContentShown(true);
                }, this::handleLoadFailure);
    }
}
