/*
 * Copyright 2011 Azwan Adli Abdullah
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
import androidx.annotation.Nullable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.gl4a.BaseActivity;
import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabSnippet;
import com.gl4a.gitlab.service.GitLabSnippetService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.StringUtils;
import com.gl4a.utils.UiUtils;

import java.util.Map;

public class GistActivity extends BaseActivity implements View.OnClickListener {
    public static Intent makeIntent(Context context, long snippetId) {
        return new Intent(context, GistActivity.class)
                .putExtra("id", snippetId);
    }

    private static final int ID_LOADER_GIST = 0;

    private long mSnippetId;
    private GitLabSnippet mSnippet;
    // GitLab personal snippets do not have a star endpoint; stub the field
    private Boolean mIsStarred = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.gist);
        setContentShown(false);

        loadGist(false);
        loadStarredState(false);
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return getString(R.string.gist_title, mSnippetId);
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mSnippetId = extras.getLong("id");
    }

    @Override
    public void onRefresh() {
        mSnippet = null;
        mIsStarred = null;
        setContentShown(false);
        loadGist(true);
        super.onRefresh();
    }

    private void fillData(final GitLabSnippet snippet) {
        mSnippet = snippet;

        if (snippet.owner() != null) {
            getSupportActionBar().setSubtitle(snippet.owner().login());
        }

        TextView tvDesc = findViewById(R.id.tv_desc);
        tvDesc.setText(TextUtils.isEmpty(snippet.description())
                ? getString(R.string.gist_no_description) : snippet.description());

        TextView tvCreatedAt = findViewById(R.id.tv_created_at);
        tvCreatedAt.setText(StringUtils.formatRelativeTime(this, snippet.createdAt(), true));

        Map<String, GitLabSnippet.SnippetFile> files = snippet.files();
        if (files != null && !files.isEmpty()) {
            ViewGroup container = findViewById(R.id.file_container);
            LayoutInflater inflater = getLayoutInflater();

            container.removeAllViews();
            for (GitLabSnippet.SnippetFile snippetFile : files.values()) {
                TextView rowView = (TextView) inflater.inflate(R.layout.selectable_label,
                        container, false);

                rowView.setText(snippetFile.filename());
                rowView.setTextColor(UiUtils.resolveColor(this, android.R.attr.textColorPrimary));
                rowView.setOnClickListener(this);
                rowView.setTag(snippetFile);
                container.addView(rowView);
            }
        } else {
            findViewById(R.id.file_card).setVisibility(View.GONE);
        }

        findViewById(R.id.tv_private).setVisibility(snippet.isPublic() ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onClick(View view) {
        GitLabSnippet.SnippetFile file = (GitLabSnippet.SnippetFile) view.getTag();
        startActivity(GistViewerActivity.makeIntent(this, String.valueOf(mSnippetId), file.filename()));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gist_menu, menu);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean authorized = Gl4Application.get().isAuthorized();

        // GitLab snippets do not have a star API; hide the star menu item
        MenuItem starAction = menu.findItem(R.id.star);
        if (starAction != null) {
            starAction.setVisible(false);
        }

        if (mSnippet == null) {
            menu.removeItem(R.id.share);
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.share:
                String login = ApiHelpers.getUserLogin(this, mSnippet.owner());
                IntentUtils.share(this, getString(R.string.share_gist_subject, mSnippetId, login),
                        Uri.parse(mSnippet.htmlUrl()));
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected Intent navigateUp() {
        String login = mSnippet != null && mSnippet.owner() != null
                ? mSnippet.owner().login() : null;
        return login != null ? GistListActivity.makeIntent(this, login) : null;
    }

    private void loadStarredState(boolean force) {
        // GitLab personal snippets do not have a star endpoint; stub as no-op
    }

    private void loadGist(boolean force) {
        GitLabSnippetService service = ServiceFactory.get(GitLabSnippetService.class, force);
        service.getSnippet(mSnippetId)
                .map(ApiHelpers::throwOnFailure)
                .compose(makeLoaderSingle(ID_LOADER_GIST, force))
                .subscribe(result -> {
                    fillData(result);
                    setContentShown(true);
                    supportInvalidateOptionsMenu();
                }, this::handleLoadFailure);
    }
}
