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
    }

    private void fillData() {
        // Resolve owner display name: prefer namespace.name (display casing e.g. "testG")
        // over namespace.path (URL path e.g. "testg") for consistent on-screen display.
        final com.gl4a.gitlab.model.GitLabUser ownerUser = mRepository.owner();
        final String owner;
        if (mRepository.namespace != null && mRepository.namespace.name != null) {
            owner = mRepository.namespace.name;
        } else if (ownerUser != null && ownerUser.login() != null) {
            owner = ownerUser.login();
        } else {
            owner = "";
        }
        final String name = mRepository.name();

        TextView tvRepoName = mContentView.findViewById(R.id.tv_repo_name);
        IntentSpan repoSpan = new IntentSpan(tvRepoName.getContext(),
                context -> UserActivity.makeIntent(context, ownerUser));
        SpannableStringBuilder repoName = new SpannableStringBuilder();
        repoName.append(owner);
        repoName.append("/");
        repoName.append(name);
        repoName.setSpan(repoSpan, 0, owner.length(), 0);
        tvRepoName.setText(repoName);

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
        privateRow.setVisibility(mRepository.isPrivate() ? View.VISIBLE : View.GONE);

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

        com.gl4a.gitlab.model.GitLabUser ownerObj = mRepository.owner();
        String owner = (ownerObj != null && ownerObj.login() != null)
                ? ownerObj.login()
                : (mRepository.namespace != null ? mRepository.namespace.path : "");
        String name = mRepository.name();
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
        com.gl4a.gitlab.model.GitLabUser ownerUser2 = mRepository.owner();
        String repoOwner = (ownerUser2 != null && ownerUser2.login() != null)
                ? ownerUser2.login()
                : (mRepository.namespace != null ? mRepository.namespace.path : "");
        String repoName = mRepository.name();
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
