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
package com.gl4a.fragment;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.NonNull;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import io.reactivex.schedulers.Schedulers;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.CollaboratorListActivity;
import com.gl4a.activities.ContributorListActivity;
import com.gl4a.activities.ForkListActivity;
import com.gl4a.activities.IssueListActivity;
import com.gl4a.activities.ReleaseListActivity;
import com.gl4a.activities.RepositoryActivity;
import com.gl4a.activities.StargazerListActivity;
import com.gl4a.activities.UserActivity;
import com.gl4a.activities.WatcherListActivity;
import com.gl4a.activities.WikiListActivity;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.service.GitLabProjectService;
import com.gl4a.gitlab.service.GitLabRepositoryService;
import com.gl4a.gitlab.service.GitLabMergeRequestService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.HtmlUtils;
import com.gl4a.utils.HttpImageGetter;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.StringUtils;
import com.gl4a.utils.UiUtils;
import com.gl4a.widget.IntentSpan;
import com.gl4a.widget.OverviewRow;
import com.vdurmont.emoji.EmojiParser;

import java.net.HttpURLConnection;
import java.util.Locale;
import java.util.Optional;

import io.reactivex.Single;
import retrofit2.Response;

public class RepositoryFragment extends LoadingFragmentBase implements
        OverviewRow.OnIconClickListener {
    public static RepositoryFragment newInstance(GitLabProject repository, String ref) {
        RepositoryFragment f = new RepositoryFragment();

        Bundle args = new Bundle();
        args.putParcelable("repo", repository);
        args.putString("ref", ref);
        f.setArguments(args);

        return f;
    }

    private static final int ID_LOADER_README = 0;
    private static final int ID_LOADER_MR_COUNT = 1;
    private static final int ID_LOADER_STARRING = 2;
    private static final int ID_LOADER_LANGUAGES = 3;
    private static final int ID_LOADER_LICENSE = 4;
    private static final int ID_LOADER_TOP_FILES = 5;
    private static final int ID_LOADER_LAST_COMMIT = 6;

    private static final String STATE_KEY_IS_README_EXPANDED = "is_readme_expanded";
    private static final String STATE_KEY_IS_README_LOADED = "is_readme_loaded";

    private GitLabProject mRepository;
    private View mContentView;
    private OverviewRow mWatcherRow;
    private OverviewRow mStarsRow;
    private String mRef;
    private HttpImageGetter mImageGetter;
    private WebView mReadmeView;
    private View mLoadingView;
    private TextView mReadmeTitleView;
    private Boolean mIsStarring = null;
    private boolean mIsReadmeLoaded = false;
    private boolean mIsReadmeExpanded = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRepository = getArguments().getParcelable("repo");
        mRef = getArguments().getString("ref");
        if (mRepository == null) {
            handleLoadFailure(new NullPointerException("Repository is null"));
            return;
        }
    }

    @Override
    protected View onCreateContentView(LayoutInflater inflater, ViewGroup parent) {
        mContentView = inflater.inflate(R.layout.repository, parent, false);
        mReadmeView = mContentView.findViewById(R.id.readme);
        mLoadingView = mContentView.findViewById(R.id.pb_readme);
        mReadmeTitleView = mContentView.findViewById(R.id.readme_title);
        setupReadmeWebView();
        return mContentView;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mImageGetter.destroy();
        mImageGetter = null;
    }

    @Override
    public void onRefresh() {
        if (mReadmeView != null) {
            mReadmeView.setVisibility(View.GONE);
        }
        if (mLoadingView != null && mIsReadmeExpanded) {
            mLoadingView.setVisibility(View.VISIBLE);
        }
        if (mContentView != null) {
            OverviewRow issuesRow = mContentView.findViewById(R.id.issues_row);
            issuesRow.setText(null);
            OverviewRow pullsRow = mContentView.findViewById(R.id.pulls_row);
            pullsRow.setText(null);
        }
        if (mIsStarring != null && mStarsRow != null) {
            mStarsRow.setText(null);
        }
        mIsStarring = null;
        if (mImageGetter != null) {
            mImageGetter.clearHtmlCache();
        }
        if (mIsReadmeLoaded) {
            loadReadme(true);
        }
        loadMergeRequestCount(true);
        loadStarringState(true);
        loadLanguages(true);
        loadLicense(true);
        loadBranchDetails(true);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mImageGetter = new HttpImageGetter(getActivity());
        fillData();
        setContentShown(true);

        if (savedInstanceState != null) {
            mIsReadmeExpanded = savedInstanceState.getBoolean(STATE_KEY_IS_README_EXPANDED, false);
            mIsReadmeLoaded = savedInstanceState.getBoolean(STATE_KEY_IS_README_LOADED, false);
        }

        if (mIsReadmeExpanded || mIsReadmeLoaded) {
            loadReadme(false);
        }
        loadMergeRequestCount(false);
        loadStarringState(false);
        loadLanguages(false);
        loadLicense(false);
        loadBranchDetails(false);

        updateReadmeVisibility();
    }

    @Override
    public void onResume() {
        super.onResume();
        mImageGetter.resume();
    }

    @Override
    public void onPause() {
        super.onPause();
        mImageGetter.pause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_KEY_IS_README_EXPANDED, mIsReadmeExpanded);
        outState.putBoolean(STATE_KEY_IS_README_LOADED, mIsReadmeLoaded);
    }

    public void setRef(String ref) {
        mRef = ref;
        getArguments().putString("ref", ref);

        if (mIsReadmeLoaded) {
            loadReadme(true);
        }
        if (mReadmeView != null) {
            mReadmeView.setVisibility(View.GONE);
        }
        if (mLoadingView != null && mIsReadmeExpanded) {
            mLoadingView.setVisibility(View.VISIBLE);
        }
        // The last commit and file shortcuts follow the selected branch (#271)
        if (mContentView != null) {
            loadBranchDetails(true);
        }
    }

    private static String ownerFromPath(com.gl4a.gitlab.model.GitLabProject repo) {
        String pwn = repo.pathWithNamespace;
        if (pwn != null && pwn.contains("/")) {
            return pwn.substring(0, pwn.lastIndexOf('/'));
        }
        if (repo.namespace != null) {
            return repo.namespace.path != null ? repo.namespace.path : repo.namespace.name;
        }
        return "";
    }

    private void fillData() {
        final com.gl4a.gitlab.model.GitLabUser ownerUser = mRepository.owner();
        // Use full path-derived owner so nested groups (e.g. "it/int") are included.
        final String owner = ownerFromPath(mRepository);
        // Use path (URL slug) not name() (display name) — display names may contain spaces
        // which break API calls when used as the project path identifier.
        final String name = mRepository.path != null ? mRepository.path : mRepository.name();

        TextView tvRepoName = mContentView.findViewById(R.id.tv_repo_name);
        IntentSpan repoSpan = new IntentSpan(tvRepoName.getContext(),
                context -> UserActivity.makeIntent(context, ownerUser));
        SpannableStringBuilder repoName = new SpannableStringBuilder();
        repoName.append(owner);
        repoName.append("/");
        repoName.append(name);
        repoName.setSpan(repoSpan, 0, owner.length(), 0);
        tvRepoName.setText(repoName);
        // The one shared project logo, as in every project list (#235)
        com.gl4a.utils.AvatarHandler.assignProjectLogo(
                mContentView.findViewById(R.id.iv_repo_logo), mRepository);

        fillTextView(R.id.tv_desc, 0, mRepository.description());
        fillTextView(R.id.tv_url, 0, mRepository.htmlUrl());

        OverviewRow forkParentRow = mContentView.findViewById(R.id.fork_parent_row);
        if (mRepository.isFork() && mRepository.parent() != null) {
            GitLabProject parent = mRepository.parent();
            forkParentRow.setVisibility(View.VISIBLE);
            forkParentRow.setText(getForkedFromTextWithHighlight(parent));
            forkParentRow.setClickIntent(RepositoryActivity.makeIntent(getActivity(), parent));
        } else {
            forkParentRow.setVisibility(View.GONE);
        }

        OverviewRow privateRow = mContentView.findViewById(R.id.private_row);
        if ("internal".equals(mRepository.visibility)) {
            privateRow.setVisibility(View.VISIBLE);
            privateRow.setText(getString(R.string.repo_type_internal));
            // Same icons as GitLab: shield for internal, globe for public, lock for private.
            privateRow.setIcon(androidx.core.content.ContextCompat.getDrawable(
                    getContext(), R.drawable.icon_internal));
        } else if ("public".equals(mRepository.visibility)) {
            privateRow.setVisibility(View.VISIBLE);
            privateRow.setText(getString(R.string.repo_type_public));
            privateRow.setIcon(androidx.core.content.ContextCompat.getDrawable(
                    getContext(), R.drawable.icon_public));
        } else {
            privateRow.setVisibility(mRepository.isPrivate() ? View.VISIBLE : View.GONE);
            privateRow.setText(getString(R.string.repo_type_private));
            privateRow.setIcon(androidx.core.content.ContextCompat.getDrawable(
                    getContext(), R.drawable.icon_private));
        }

        OverviewRow languageRow = mContentView.findViewById(R.id.language_row);
        // GitLab API does not surface language on project list; hide the row
        languageRow.setVisibility(View.GONE);

        boolean showOverviewRowDivider = forkParentRow.getVisibility() == View.VISIBLE
                || privateRow.getVisibility() == View.VISIBLE;
        mContentView.findViewById(R.id.repository_overview_row_divider)
                .setVisibility(showOverviewRowDivider ? View.VISIBLE : View.GONE);

        OverviewRow issuesRow = mContentView.findViewById(R.id.issues_row);
        issuesRow.setVisibility(mRepository.hasIssues() ? View.VISIBLE : View.GONE);
        issuesRow.setClickIntent(IssueListActivity.makeIntent(
                getActivity(), owner, name, mRepository.id()));

        OverviewRow pullsRow = mContentView.findViewById(R.id.pulls_row);
        pullsRow.setClickIntent(IssueListActivity.makeIntent(
                getActivity(), owner, name, mRepository.id(), true));

        OverviewRow forksRow = mContentView.findViewById(R.id.forks_row);
        forksRow.setText(getResources().getQuantityString(R.plurals.fork,
                mRepository.forksCount(), mRepository.forksCount()));
        forksRow.setClickIntent(ForkListActivity.makeIntent(getActivity(), owner, name));

        mStarsRow = mContentView.findViewById(R.id.stars_row);
        mStarsRow.setIconClickListener(this);
        mStarsRow.setClickIntent(StargazerListActivity.makeIntent(getActivity(), owner, name));

        // GitLab does not have a per-project subscription/watcher concept matching GitHub's;
        // hide the watchers row.
        mWatcherRow = mContentView.findViewById(R.id.watchers_row);
        mWatcherRow.setVisibility(View.GONE);

        if (!Gl4Application.get().isAuthorized()) {
            updateStargazerUi();
        }

        // Created on, as GitLab web shows it (#271)
        TextView createdOn = mContentView.findViewById(R.id.tv_created_on);
        java.util.Date created = StringUtils.parseIsoDate(mRepository.createdAt);
        if (created != null) {
            createdOn.setText(getString(R.string.repo_created_on,
                    java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG)
                            .format(created)));
            createdOn.setVisibility(View.VISIBLE);
        }

        mReadmeTitleView.setOnClickListener(view -> toggleReadmeExpanded());
        mContentView.findViewById(R.id.tv_contributors_label).setOnClickListener(this::onOtherInfoLabelClick);
        mContentView.findViewById(R.id.tv_releases_label).setOnClickListener(this::onOtherInfoLabelClick);

        GitLabProject.GitLabProjectPermissions permissions = mRepository.permissions;
        updateClickableLabel(R.id.tv_collaborators_label, permissions != null && permissions.canPush());
        updateClickableLabel(R.id.tv_wiki_label, mRepository.hasWiki());
        // GitLab does not expose a "discussions" flag on the project model
        updateClickableLabel(R.id.tv_discussions_label, false);
    }

    @NonNull
    private SpannableString getForkedFromTextWithHighlight(GitLabProject parent) {
        String forkedFromText = getString(R.string.forked_from, parent.fullName());
        SpannableString spannableString = new SpannableString(forkedFromText);
        ForegroundColorSpan colorSpan = new ForegroundColorSpan(
                UiUtils.resolveColor(getContext(), android.R.attr.textColorLink));
        spannableString.setSpan(colorSpan,
                forkedFromText.indexOf(parent.fullName()), forkedFromText.length(),
                Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        return spannableString;
    }

    private void updateClickableLabel(int id, boolean enable) {
        View view = mContentView.findViewById(id);
        if (enable) {
            view.setOnClickListener(this::onOtherInfoLabelClick);
            view.setVisibility(View.VISIBLE);
        } else {
            view.setVisibility(View.GONE);
        }
    }

    private void fillTextView(int id, int stringId, String text) {
        TextView view = mContentView.findViewById(id);

        if (!StringUtils.isBlank(text)) {
            if (stringId != 0) {
                view.setText(getString(stringId, text));
            } else {
                view.setText(EmojiParser.parseToUnicode(text));
            }
            view.setVisibility(View.VISIBLE);
        } else {
            view.setVisibility(View.GONE);
        }
    }

    private void updateStargazerUi() {
        mStarsRow.setText(getResources().getQuantityString(R.plurals.star,
                mRepository.stargazersCount(), mRepository.stargazersCount()));
        mStarsRow.setToggleState(mIsStarring != null && mIsStarring);
    }

    private void onOtherInfoLabelClick(View view) {
        int id = view.getId();

        if (id == R.id.tv_discussions_label) {
            IntentUtils.openInCustomTabOrBrowser(getActivity(),
                    Uri.parse(mRepository.htmlUrl() + "/-/issues"));
            return;
        }

        String owner = ownerFromPath(mRepository);
        String name = mRepository.path != null ? mRepository.path : mRepository.name();
        Intent intent = null;

        if (id == R.id.tv_contributors_label) {
            intent = ContributorListActivity.makeIntent(getActivity(), owner, name);
        } else if (id == R.id.tv_collaborators_label) {
            intent = CollaboratorListActivity.makeIntent(getActivity(), owner, name);
        } else if (id == R.id.tv_wiki_label) {
            intent = WikiListActivity.makeIntent(getActivity(), owner, name, null);
        } else if (id == R.id.tv_releases_label) {
            intent = ReleaseListActivity.makeIntent(getActivity(), owner, name);
        }

        if (intent != null) {
            startActivity(intent);
        }
    }

    @Override
    public void onIconClick(OverviewRow row) {
        if (row == mStarsRow && mIsStarring != null) {
            mStarsRow.setText(null);
            toggleStarringState();
        }
    }

    private void toggleReadmeExpanded() {
        mIsReadmeExpanded = !mIsReadmeExpanded;

        if (mIsReadmeExpanded && !mIsReadmeLoaded) {
            loadReadme(false);
        }

        updateReadmeVisibility();
    }

    private void updateReadmeVisibility() {
        mReadmeView.setVisibility(mIsReadmeExpanded && mIsReadmeLoaded ? View.VISIBLE : View.GONE);
        mLoadingView.setVisibility(
                mIsReadmeExpanded && !mIsReadmeLoaded ? View.VISIBLE : View.GONE);

        int drawableRes = mIsReadmeExpanded ? R.drawable.drop_up_arrow : R.drawable.drop_down_arrow;
        mReadmeTitleView.setCompoundDrawablesWithIntrinsicBounds(0, 0, drawableRes, 0);
    }

    private void loadReadme(boolean force) {
        Context context = getActivity();
        long id = mRepository.id();
        String repoOwner = ownerFromPath(mRepository);
        String repoName = mRepository.path != null ? mRepository.path : mRepository.name();
        String ref = mRef != null ? mRef : mRepository.defaultBranch();

        // GitLab: read README via raw file endpoint
        GitLabRepositoryService service = ServiceFactory.get(GitLabRepositoryService.class, force);
        String readmePath = "README.md";

        service.getRawFile(id, readmePath, ref)
                .map(response -> {
                    if (response.isSuccessful() && response.body() != null) {
                        return Optional.of(response.body().string());
                    }
                    return Optional.<String>empty();
                })
                .compose(RxUtils.mapFailureToValue(HttpURLConnection.HTTP_NOT_FOUND, Optional.<String>empty()))
                .flatMap(mdOpt -> {
                    if (!mdOpt.isPresent()) return io.reactivex.Single.just(mdOpt);
                    // Pre-embed images on IO thread (same approach as FileViewerActivity).
                    return io.reactivex.Single.fromCallable(() -> {
                        String processed = embedReadmeImages(mdOpt.get(), repoOwner, repoName, ref);
                        return Optional.of(processed);
                    }).subscribeOn(Schedulers.io());
                })
                .compose(makeLoaderSingle(ID_LOADER_README, force))
                .doOnSubscribe(disposable -> {
                    mIsReadmeLoaded = false;
                    updateReadmeVisibility();
                })
                .subscribe(readmeOpt -> {
                    if (readmeOpt.isPresent()) {
                        // Use the same showdown.js WebView renderer as the Files section.
                        String base64 = android.util.Base64.encodeToString(
                                readmeOpt.get().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                android.util.Base64.DEFAULT);
                        String html = com.gl4a.activities.WebViewerActivity
                                .generateReadmeHtml(getContext(), base64, repoOwner, repoName, ref, "");
                        mReadmeView.loadDataWithBaseURL(
                                "file:///android_asset/", html, "text/html", "utf-8", null);
                    } else {
                        mReadmeView.loadData(
                                "<p><i>" + getString(R.string.repo_no_readme) + "</i></p>",
                                "text/html", "utf-8");
                    }
                    mIsReadmeLoaded = true;
                    updateReadmeVisibility();
                }, this::handleLoadFailure);
    }

    private void loadMergeRequestCount(boolean force) {
        GitLabMergeRequestService service = ServiceFactory.get(GitLabMergeRequestService.class, force);
        long projectId = mRepository.id();

        service.listMergeRequests(projectId, "opened", null, null, 1, 1, null, null, null, false)
                .map(response -> {
                    if (!response.isSuccessful()) {
                        throw new com.gl4a.ApiRequestException(response);
                    }
                    // Use the X-Total header to get the real total count
                    String xTotal = response.headers().get("X-Total");
                    if (xTotal != null && !xTotal.isEmpty()) {
                        try { return Integer.parseInt(xTotal.trim()); } catch (NumberFormatException ignored) {}
                    }
                    // Fallback: count items in the response body
                    return response.body() != null ? response.body().size() : 0;
                })
                .compose(makeLoaderSingle(ID_LOADER_MR_COUNT, force))
                .subscribe(count -> {
                    int issueCount = mRepository.openIssuesCount;
                    OverviewRow issuesRow = mContentView.findViewById(R.id.issues_row);
                    issuesRow.setText(getResources().getQuantityString(
                            R.plurals.issue, issueCount, issueCount));

                    OverviewRow pullsRow = mContentView.findViewById(R.id.pulls_row);
                    pullsRow.setText(getResources().getQuantityString(
                            R.plurals.pull_request, count, count));
                }, this::handleLoadFailure);
    }

    // The licence name GitLab detected, for its shortcut (#271)
    private String mLicenseName;
    private java.util.List<com.gl4a.gitlab.model.GitLabTreeItem> mTopFiles;

    /** The languages bar and legend, from GitLab's languages with its colours (#271). */
    private void loadLanguages(boolean force) {
        if (mRepository.pathWithNamespace == null) return;
        java.util.Map<String, Object> variables = new java.util.HashMap<>();
        variables.put("path", mRepository.pathWithNamespace);
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("query", "query($path: ID!) { project(fullPath: $path) {"
                + " languages { name share color } } }");
        body.put("variables", variables);
        ServiceFactory.getGraphQL(com.gl4a.gitlab.service.GitLabGraphQLService.class)
                .rawQuery(body)
                .map(ApiHelpers::throwOnFailure)
                .map(RepositoryFragment::languagesOf)
                .compose(makeLoaderSingle(ID_LOADER_LANGUAGES, force))
                .subscribe(this::showLanguages, error -> showLanguages(
                        java.util.Collections.emptyList()));
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<java.util.Map<String, Object>> languagesOf(
            java.util.Map<String, Object> response) {
        Object data = response.get("data");
        Object project = data instanceof java.util.Map
                ? ((java.util.Map<String, Object>) data).get("project") : null;
        Object languages = project instanceof java.util.Map
                ? ((java.util.Map<String, Object>) project).get("languages") : null;
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        if (languages instanceof java.util.List) {
            for (Object language : (java.util.List<?>) languages) {
                if (language instanceof java.util.Map) {
                    result.add((java.util.Map<String, Object>) language);
                }
            }
        }
        return result;
    }

    private void showLanguages(java.util.List<java.util.Map<String, Object>> languages) {
        if (mContentView == null) return;
        View section = mContentView.findViewById(R.id.ll_languages);
        android.widget.LinearLayout bar = mContentView.findViewById(R.id.ll_language_bar);
        bar.removeAllViews();
        SpannableStringBuilder legend = new SpannableStringBuilder();
        for (java.util.Map<String, Object> language : languages) {
            Object name = language.get("name");
            Object share = language.get("share");
            if (!(name instanceof String) || !(share instanceof Number)) continue;
            int color;
            try {
                color = android.graphics.Color.parseColor(String.valueOf(language.get("color")));
            } catch (IllegalArgumentException e) {
                color = android.graphics.Color.GRAY;
            }
            float percent = ((Number) share).floatValue();
            View part = new View(getContext());
            part.setBackgroundColor(color);
            bar.addView(part, new android.widget.LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, percent));
            if (legend.length() > 0) legend.append("   ");
            int dot = legend.length();
            legend.append("\u25CF");
            legend.setSpan(new ForegroundColorSpan(color), dot, dot + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            legend.append(" ").append((String) name).append(" ")
                    .append(String.format(Locale.getDefault(), "%.1f%%", percent));
        }
        bar.setClipToOutline(true);
        ((TextView) mContentView.findViewById(R.id.tv_language_legend)).setText(legend);
        section.setVisibility(bar.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    /** The licence GitLab detected, named on its shortcut (#271). */
    @SuppressWarnings("unchecked")
    private void loadLicense(boolean force) {
        ServiceFactory.get(GitLabProjectService.class, force)
                .getProjectWithLicense(mRepository.id())
                .map(ApiHelpers::throwOnFailure)
                .map(project -> {
                    Object license = project.get("license");
                    Object name = license instanceof java.util.Map
                            ? ((java.util.Map<String, Object>) license).get("name") : null;
                    return name instanceof String ? (String) name : "";
                })
                .compose(makeLoaderSingle(ID_LOADER_LICENSE, force))
                .subscribe(name -> {
                    mLicenseName = name.isEmpty() ? null : name;
                    showFileShortcuts();
                }, error -> { });
    }

    /** The selected branch's top-level files (for the shortcuts) and last commit (#271). */
    private void loadBranchDetails(boolean force) {
        final String ref = mRef != null ? mRef : mRepository.defaultBranch();
        ServiceFactory.get(GitLabRepositoryService.class, force)
                .getTree(mRepository.id(), null, ref, false, 1, 100)
                .map(ApiHelpers::throwOnFailure)
                .compose(makeLoaderSingle(ID_LOADER_TOP_FILES, true))
                .subscribe(files -> {
                    mTopFiles = files;
                    showFileShortcuts();
                }, error -> { });
        ServiceFactory.get(com.gl4a.gitlab.service.GitLabCommitService.class, force)
                .listCommits(mRepository.id(), ref, null, 1, 1, null, null)
                .map(ApiHelpers::throwOnFailure)
                .map(commits -> {
                    if (commits.isEmpty()) {
                        throw new java.io.IOException("No commits on " + ref);
                    }
                    // The account GitLab links the commit to, as in commit lists (#244)
                    com.gl4a.utils.CommitAuthors.attach(new java.util.ArrayList<>(commits));
                    return commits.get(0);
                })
                .compose(makeLoaderSingle(ID_LOADER_LAST_COMMIT, true))
                .subscribe(this::showLastCommit, error -> {
                    if (mContentView != null) {
                        mContentView.findViewById(R.id.ll_last_commit).setVisibility(View.GONE);
                    }
                });
    }

    private void showLastCommit(com.gl4a.gitlab.model.GitLabCommit commit) {
        if (mContentView == null) return;
        View row = mContentView.findViewById(R.id.ll_last_commit);
        android.widget.ImageView avatar = mContentView.findViewById(R.id.iv_last_commit_avatar);
        // The commit lists' author avatar and tap to the profile (#239, #244)
        com.gl4a.utils.AvatarHandler.assignGitAuthorAvatar(avatar, commit.authorName,
                commit.authorEmail);
        avatar.setOnClickListener(v ->
                com.gl4a.utils.AvatarHandler.openGitAuthorProfile(getActivity(), commit));
        ((TextView) mContentView.findViewById(R.id.tv_last_commit_title)).setText(commit.title);
        String shortId = commit.shortId != null ? commit.shortId
                : commit.sha().substring(0, Math.min(8, commit.sha().length()));
        CharSequence when = StringUtils.formatRelativeTime(getContext(),
                commit.committedDate != null ? commit.committedDate : commit.createdAt, true);
        TextView meta = mContentView.findViewById(R.id.tv_last_commit_meta);
        meta.setText(getString(R.string.repo_last_commit_meta, shortId, when != null ? when : ""));
        // The commit icon before the short ID, as GitLab web's "-O- 37ec95d1"
        android.graphics.drawable.Drawable icon = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), R.drawable.commit).mutate();
        int size = Math.round(meta.getTextSize() * 1.2f);
        icon.setBounds(0, 0, size, size);
        icon.setTint(UiUtils.resolveColor(requireContext(), android.R.attr.textColorSecondary));
        meta.setCompoundDrawablesRelative(icon, null, null, null);
        meta.setCompoundDrawablePadding(Math.round(meta.getTextSize() * 0.3f));
        final String owner = ownerFromPath(mRepository);
        final String name = mRepository.path != null ? mRepository.path : mRepository.name();
        row.setOnClickListener(v -> startActivity(com.gl4a.activities.CommitActivity.makeIntent(
                getActivity(), owner, name, commit.sha())));
        row.setVisibility(View.VISIBLE);
    }

    /**
     * README, licence, CHANGELOG and CONTRIBUTING shortcuts for those on the selected branch,
     * as on GitLab web (#271). README scrolls to the README below; the others open the file.
     */
    private void showFileShortcuts() {
        if (mContentView == null || mTopFiles == null) return;
        com.google.android.material.chip.ChipGroup group =
                mContentView.findViewById(R.id.cg_file_shortcuts);
        group.removeAllViews();
        final String ref = mRef != null ? mRef : mRepository.defaultBranch();
        final String owner = ownerFromPath(mRepository);
        final String name = mRepository.path != null ? mRepository.path : mRepository.name();
        String readme = null, license = null, changelog = null, contributing = null;
        for (com.gl4a.gitlab.model.GitLabTreeItem file : mTopFiles) {
            if (!file.isFile() || file.name == null) continue;
            String lower = file.name.toLowerCase(Locale.ROOT);
            if (readme == null && lower.startsWith("readme")) readme = file.path;
            else if (license == null && (lower.startsWith("license")
                    || lower.startsWith("licence") || lower.startsWith("copying"))) {
                license = file.path;
            } else if (changelog == null && lower.startsWith("changelog")) changelog = file.path;
            else if (contributing == null && lower.startsWith("contributing")) {
                contributing = file.path;
            }
        }
        if (readme != null) {
            // Named as the file is ("README"), like the other shortcuts
            String label = readme.contains(".")
                    ? readme.substring(0, readme.lastIndexOf('.')) : readme;
            addShortcut(group, label, R.drawable.description, v -> {
                if (!mIsReadmeExpanded) toggleReadmeExpanded();
                View card = (View) mReadmeTitleView.getParent();
                if (mContentView instanceof androidx.core.widget.NestedScrollView) {
                    ((androidx.core.widget.NestedScrollView) mContentView)
                            .smoothScrollTo(0, ((View) card.getParent()).getTop());
                }
            });
        }
        if (license != null) {
            final String path = license;
            addShortcut(group, mLicenseName != null ? mLicenseName
                    : getString(R.string.repo_shortcut_license), R.drawable.license,
                    v -> startActivity(com.gl4a.activities.FileViewerActivity.makeIntent(
                            getActivity(), owner, name, ref, path)));
        }
        for (String path : new String[] { changelog, contributing }) {
            if (path == null) continue;
            String label = path.contains(".") ? path.substring(0, path.lastIndexOf('.')) : path;
            addShortcut(group, label, R.drawable.file,
                    v -> startActivity(com.gl4a.activities.FileViewerActivity.makeIntent(
                            getActivity(), owner, name, ref, path)));
        }
        group.setVisibility(group.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    /**
     * A shortcut pill: a plain label with an icon, not a Material chip, whose own touch
     * handling kept a swipe started on it from switching tabs (#271).
     */
    private void addShortcut(com.google.android.material.chip.ChipGroup group, String label,
            int icon, View.OnClickListener onClick) {
        Context context = group.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        TextView pill = new TextView(context);
        pill.setText(label);
        pill.setTextAppearance(android.R.style.TextAppearance_Small);
        pill.setTextColor(UiUtils.resolveColor(context, android.R.attr.textColorPrimary));
        pill.setBackgroundResource(R.drawable.shortcut_pill);
        pill.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int horizontal = Math.round(12 * density), vertical = Math.round(6 * density);
        pill.setPadding(horizontal, vertical, horizontal, vertical);
        android.graphics.drawable.Drawable drawable =
                androidx.core.content.ContextCompat.getDrawable(context, icon).mutate();
        int size = Math.round(18 * density);
        drawable.setBounds(0, 0, size, size);
        drawable.setTint(UiUtils.resolveColor(context, android.R.attr.textColorSecondary));
        pill.setCompoundDrawablesRelative(drawable, null, null, null);
        pill.setCompoundDrawablePadding(Math.round(6 * density));
        android.util.TypedValue ripple = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple,
                true);
        pill.setForeground(androidx.core.content.ContextCompat.getDrawable(context,
                ripple.resourceId));
        pill.setOnClickListener(onClick);
        group.addView(pill);
    }

    private void toggleStarringState() {
        GitLabProjectService service = ServiceFactory.get(GitLabProjectService.class, false);
        Single<Response<GitLabProject>> responseSingle = mIsStarring
                ? service.unstarProject(mRepository.id())
                : service.starProject(mRepository.id());
        responseSingle
                .map(response -> {
                    // 304 = already starred (POST /star when already starred), treat as success
                    // 404 = not starred (DELETE /star when not starred), treat as success
                    // 200/201 = operation performed
                    return response.isSuccessful() || response.code() == 304 || response.code() == 404
                            ? Boolean.TRUE : Boolean.FALSE;
                })
                .compose(RxUtils::doInBackground)
                .subscribe(result -> {
                    if (mIsStarring != null) {
                        mIsStarring = !mIsStarring;
                        mRepository.starCount += mIsStarring ? 1 : -1;
                        updateStargazerUi();
                    }
                }, error -> {
                    // Still toggle UI optimistically — most failures are idempotent
                    if (mIsStarring != null) {
                        mIsStarring = !mIsStarring;
                        updateStargazerUi();
                    }
                });
    }

    private void loadStarringState(boolean force) {
        if (!Gl4Application.get().isAuthorized()) {
            return;
        }
        // Query the project's starrers list and check if the current login appears.
        // GitLab has no dedicated "has the current user starred this project?" endpoint,
        // but the starrers list (per_page=100) is the most reliable available approach.
        String currentLogin = Gl4Application.get().getAuthLogin();
        if (currentLogin == null) {
            mIsStarring = false;
            updateStargazerUi();
            return;
        }
        GitLabProjectService service = ServiceFactory.get(GitLabProjectService.class, force);
        long projectId = mRepository.id();
        service.getStarrers(projectId, 1, 100)
                .map(response -> {
                    if (!response.isSuccessful() || response.body() == null) {
                        return false;
                    }
                    for (com.gl4a.gitlab.model.GitLabStarrer s : response.body()) {
                        if (s != null && s.user() != null
                                && currentLogin.equals(s.user().login())) {
                            return true;
                        }
                    }
                    return false;
                })
                .onErrorReturn(e -> false)
                .compose(makeLoaderSingle(ID_LOADER_STARRING, force))
                .subscribe(result -> {
                    mIsStarring = result;
                    updateStargazerUi();
                }, this::handleLoadFailure);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupReadmeWebView() {
        if (mReadmeView == null) return;
        android.webkit.WebSettings s = mReadmeView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setBuiltInZoomControls(false);
        s.setLoadsImagesAutomatically(true);
        mReadmeView.setBackgroundColor(0); // transparent
        mReadmeView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                com.gl4a.utils.IntentUtils.openLinkInternallyOrExternally(
                        (androidx.fragment.app.FragmentActivity) getActivity(), request.getUrl());
                return true;
            }
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                // Auth proxy for GitLab instance resources (belt-and-suspenders alongside
                // pre-embedding; covers any non-relative image URLs).
                android.net.Uri uri = req.getUrl();
                String instanceHost = android.net.Uri.parse(
                        Gl4Application.get().getInstanceUrl()).getHost();
                if (uri.getHost() == null || !uri.getHost().equalsIgnoreCase(instanceHost))
                    return null;
                try {
                    String tok = Gl4Application.get().getAuthToken();
                    okhttp3.OkHttpClient client = ServiceFactory.getImageHttpClient();
                    okhttp3.Response resp = client.newCall(new okhttp3.Request.Builder()
                            .url(uri.toString())
                            .header("PRIVATE-TOKEN", tok != null ? tok : "")
                            .build()).execute();
                    if (!resp.isSuccessful() || resp.body() == null) return null;
                    byte[] bytes = resp.body().bytes();
                    String ct = resp.header("Content-Type", "application/octet-stream");
                    if (ct != null && ct.contains(";")) ct = ct.substring(0, ct.indexOf(';')).trim();
                    return new WebResourceResponse(ct, null,
                            new java.io.ByteArrayInputStream(bytes));
                } catch (Exception e) { return null; }
            }
        });
        com.gl4a.activities.WebViewerActivity.addCommonJsInterfaces(mReadmeView, getActivity());
    }

    /** Pre-embed relative images in the markdown as base64 data URIs (IO thread). */
    private static String embedReadmeImages(String markdown, String owner, String repo, String ref) {
        String instanceUrl = Gl4Application.get().getInstanceUrl();
        String tok = Gl4Application.get().getAuthToken();

        java.util.regex.Pattern[] patterns = {
            // Markdown: ![alt](path)
            java.util.regex.Pattern.compile("!\\[([^\\]]*)]\\(([^)]+)\\)"),
            // HTML: <img src="path"> or <img src='path'>
            java.util.regex.Pattern.compile(
                    "(<img[^>]*\\ssrc=)[\"']([^\"']+)[\"']([^>]*>)",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
        };

        String result = markdown;
        // Pass 1: markdown images
        java.util.regex.Matcher m = patterns[0].matcher(result);
        java.lang.StringBuffer sb = new java.lang.StringBuffer();
        while (m.find()) {
            String alt = m.group(1), url = m.group(2).trim();
            if (!url.startsWith("http") && !url.startsWith("data:") && !url.startsWith("#")) {
                String data = fetchAsDataUri(buildUrl(instanceUrl, owner, repo, url, ref), tok);
                if (data != null) {
                    m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                            "![" + alt + "](" + data + ")"));
                    continue;
                }
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(0)));
        }
        m.appendTail(sb);
        result = sb.toString();

        // Pass 2: raw HTML img tags
        java.util.regex.Matcher m2 = patterns[1].matcher(result);
        java.lang.StringBuffer sb2 = new java.lang.StringBuffer();
        while (m2.find()) {
            String before = m2.group(1), url = m2.group(2).trim(), after = m2.group(3);
            if (!url.startsWith("http") && !url.startsWith("data:") && !url.startsWith("#")) {
                String data = fetchAsDataUri(buildUrl(instanceUrl, owner, repo, url, ref), tok);
                if (data != null) {
                    m2.appendReplacement(sb2, java.util.regex.Matcher.quoteReplacement(
                            before + "\"" + data + "\"" + after));
                    continue;
                }
            }
            m2.appendReplacement(sb2, java.util.regex.Matcher.quoteReplacement(m2.group(0)));
        }
        m2.appendTail(sb2);
        return sb2.toString();
    }

    private static String buildUrl(String instance, String owner, String repo,
            String path, String ref) {
        if (path.startsWith("./")) path = path.substring(2);
        return instance + "/api/v4/projects/"
                + android.net.Uri.encode(owner) + "%2F" + android.net.Uri.encode(repo)
                + "/repository/files/" + path.replace("/", "%2F")
                + "/raw?ref=" + android.net.Uri.encode(ref);
    }

    private static String fetchAsDataUri(String urlStr, String tok) {
        try {
            okhttp3.OkHttpClient client = ServiceFactory.getImageHttpClient();
            try (okhttp3.Response resp = client.newCall(new okhttp3.Request.Builder()
                    .url(urlStr).header("PRIVATE-TOKEN", tok != null ? tok : "")
                    .build()).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                byte[] bytes = resp.body().bytes();
                if (bytes.length == 0) return null;
                String ct = resp.header("Content-Type", "");
                if (ct == null || ct.startsWith("application/octet-stream")
                        || ct.startsWith("text/html")) {
                    ct = java.net.URLConnection.guessContentTypeFromName(urlStr);
                }
                if (ct == null) ct = "image/png";
                int semi = ct.indexOf(';');
                if (semi > 0) ct = ct.substring(0, semi).trim();
                return "data:" + ct + ";base64,"
                        + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            }
        } catch (Exception e) { return null; }
    }
}
