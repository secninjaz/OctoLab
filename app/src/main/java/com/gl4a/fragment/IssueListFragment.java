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

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.RecyclerView;

import android.text.TextUtils;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.IssueActivity;
import com.gl4a.adapter.IssueAdapter;
import com.gl4a.adapter.RepositoryIssueAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.gitlab.model.GitLabIssue;
import com.gl4a.gitlab.model.GitLabPage;
import com.gl4a.gitlab.service.GitLabIssueService;
import com.gl4a.gitlab.service.GitLabMergeRequestService;
import com.gl4a.gitlab.service.GitLabSearchService;
import com.gl4a.utils.ActivityResultHelpers;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.SingleFactory;

import io.reactivex.Single;
import retrofit2.Response;

public class IssueListFragment extends PagedDataBaseFragment<GitLabIssue> {
    private String mQuery;
    private String mSortMode;
    private String mOrder;
    private int mEmptyTextResId;
    private boolean mShowRepository;
    private String mIssueState;
    private String mScope;  // "assigned_to_me" | "created_by_me" | "all" | null for project-level
    private boolean mIsMR; // true for Merge Requests tab
    // Project-level mode fields
    private long mProjectId = -1L;
    private String mRepoOwner;
    private String mRepoName;
    private boolean mProjectMode; // true = use listIssues(projectId, ...) instead of personal feed
    private String mFilterLabel;
    private String mFilterMilestone;
    private String mFilterAssignee;

    private final ActivityResultLauncher<Intent> mIssueLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultHelpers.ActivityResultSuccessCallback(() -> super.onRefresh())
    );

    public static IssueListFragment newInstance(String query, String sortMode, String order,
            String state, int emptyTextResId, boolean showRepository) {
        return newInstance(query, sortMode, order, state, emptyTextResId, showRepository, "assigned_to_me", false);
    }

    public static IssueListFragment newInstance(String query, String sortMode, String order,
            String state, int emptyTextResId, boolean showRepository, String scope, boolean isMR) {
        IssueListFragment f = new IssueListFragment();

        Bundle args = new Bundle();
        args.putString("query", query);
        args.putString("sortmode", sortMode);
        args.putString("order", order);
        args.putInt("emptytext", emptyTextResId);
        args.putString("state", state);
        args.putBoolean("withrepo", showRepository);
        args.putString("scope", scope);
        args.putBoolean("isMR", isMR);
        args.putBoolean("project_mode", false);

        f.setArguments(args);
        return f;
    }

    /**
     * Creates a fragment that loads project-level issues via
     * GET /projects/:id/issues?state=...
     */
    public static IssueListFragment newInstanceForProject(
            long projectId, String repoOwner, String repoName,
            String sortMode, String order, String state, int emptyTextResId,
            boolean isMR, String filterLabel, String filterMilestone, String filterAssignee,
            String searchQuery) {
        IssueListFragment f = new IssueListFragment();
        Bundle args = new Bundle();
        args.putLong("project_id", projectId);
        args.putString("owner", repoOwner);
        args.putString("repo", repoName);
        args.putString("sortmode", sortMode);
        args.putString("order", order);
        args.putString("state", state);
        args.putInt("emptytext", emptyTextResId);
        args.putBoolean("isMR", isMR);
        args.putBoolean("project_mode", true);
        args.putString("filter_label", filterLabel);
        args.putString("filter_milestone", filterMilestone);
        args.putString("filter_assignee", filterAssignee);
        args.putString("query", searchQuery);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();
        mQuery = args.getString("query");
        mSortMode = args.getString("sortmode");
        mOrder = args.getString("order");
        mEmptyTextResId = args.getInt("emptytext");
        mIssueState = args.getString("state");
        mShowRepository = args.getBoolean("withrepo");
        mScope = args.getString("scope", "assigned_to_me");
        mIsMR = args.getBoolean("isMR", false);
        mProjectMode = args.getBoolean("project_mode", false);
        if (mProjectMode) {
            mProjectId = args.getLong("project_id", -1L);
            mRepoOwner = args.getString("owner");
            mRepoName = args.getString("repo");
            mFilterLabel = args.getString("filter_label");
            mFilterMilestone = args.getString("filter_milestone");
            mFilterAssignee = args.getString("filter_assignee");
        }
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        switch (mIssueState != null ? mIssueState : "") {
            case ApiHelpers.IssueState.CLOSED:
                setHighlightColors(R.attr.colorIssueClosed, R.attr.colorIssueClosedDark);
                break;
            case ApiHelpers.IssueState.MERGED:
                setHighlightColors(R.attr.colorPullRequestMerged,
                        R.attr.colorPullRequestMergedDark);
                break;
            default:
                setHighlightColors(R.attr.colorIssueOpen, R.attr.colorIssueOpenDark);
                break;
        }
    }

    @Override
    public void onItemClick(GitLabIssue issue) {
        if (mIsMR && issue.webUrl != null && issue.webUrl.contains("/-/merge_requests/")) {
            // Parse webUrl: https://host/[ns.../]project/-/merge_requests/iid
            try {
                java.util.List<String> segs =
                        android.net.Uri.parse(issue.webUrl).getPathSegments();
                int dashIdx = segs.indexOf("-");
                if (dashIdx >= 2) {
                    // Everything before the last segment before "-" is the namespace
                    String owner = android.text.TextUtils.join("/", segs.subList(0, dashIdx - 1));
                    String repo  = segs.get(dashIdx - 1);
                    startActivity(com.gl4a.activities.PullRequestActivity.makeIntent(
                            getActivity(), owner, repo, issue.iid));
                    return;
                }
            } catch (Exception ignored) {}
            // Fallback: open in custom tab
            com.gl4a.utils.IntentUtils.openLinkInternallyOrExternally(
                    getActivity(), android.net.Uri.parse(issue.webUrl));
        } else {
            mIssueLauncher.launch(IssueActivity.makeIntent(getActivity(), issue, issue.projectId));
        }
    }

    @Override
    protected RootAdapter<GitLabIssue, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        return mShowRepository
                ? new RepositoryIssueAdapter(getActivity())
                : new IssueAdapter(getActivity());
    }

    @Override
    protected int getEmptyTextResId() {
        return mEmptyTextResId;
    }

    @Override
    protected Single<Response<GitLabPage<GitLabIssue>>> loadPage(int page, boolean bypassCache) {
        if (mProjectMode) {
            // Project-level: use listIssues(projectId, state, ...) not personal feed endpoint
            final GitLabIssueService service = ServiceFactory.get(GitLabIssueService.class, bypassCache);
            String state = mIssueState != null ? mIssueState : "opened";
            if (mProjectId > 0) {
                return service.listIssues(mProjectId, state,
                        mFilterLabel, mFilterMilestone, mFilterAssignee, page, 25, mSortMode, mOrder, mQuery)
                        .map(response -> {
                            if (response.isSuccessful())
                                return Response.success(ApiHelpers.toPage(response));
                            return Response.<GitLabPage<GitLabIssue>>error(response.errorBody(), response.raw());
                        });
            } else {
                // Resolve projectId then load
                return SingleFactory.getProjectId(mRepoOwner, mRepoName)
                        .doOnSuccess(id -> mProjectId = id)
                        .flatMap(id -> service.listIssues(id, state,
                                mFilterLabel, mFilterMilestone, mFilterAssignee, page, 25, mSortMode, mOrder, mQuery))
                        .map(response -> {
                            if (response.isSuccessful())
                                return Response.success(ApiHelpers.toPage(response));
                            return Response.<GitLabPage<GitLabIssue>>error(response.errorBody(), response.raw());
                        });
            }
        } else if (mIsMR && !"mentioned".equals(mScope) && !"participating".equals(mScope)) {
            // Merge Requests tab: GET /merge_requests?scope=assigned_to_me
            final GitLabMergeRequestService mrService =
                    ServiceFactory.get(GitLabMergeRequestService.class, bypassCache);
            return mrService.listMyMergeRequests(mIssueState, mScope != null ? mScope : "assigned_to_me", page, 25)
                    .map(response -> {
                        if (!response.isSuccessful())
                            return Response.<GitLabPage<GitLabIssue>>error(response.errorBody(), response.raw());
                        // Convert MR list to issue list for display (reuse IssueAdapter)
                        java.util.List<GitLabIssue> issues = new java.util.ArrayList<>();
                        if (response.body() != null) {
                            for (com.gl4a.gitlab.model.GitLabMergeRequest mr : response.body()) {
                                GitLabIssue stub = new GitLabIssue();
                                stub.iid = mr.iid;
                                stub.title = mr.title;
                                stub.state = mr.state;
                                stub.author = mr.author;
                                stub.createdAt = mr.createdAt;
                                stub.updatedAt = mr.updatedAt;
                                stub.webUrl = mr.webUrl;
                                stub.commentsCount = mr.commentsCount;
                                stub.projectId = mr.projectId;
                                issues.add(stub);
                            }
                        }
                        return Response.success(ApiHelpers.toPage(
                                retrofit2.Response.success(issues, response.headers())));
                    });
        } else if ("mentioned".equals(mScope) || "participating".equals(mScope)) {
            // Mentioned / Participating via GitLab Todos API.
            // Strategy confirmed by Grok: standard offset pagination (page + per_page).
            // Mentioned  = action=mentioned + action=directly_addressed (both pending and done)
            // Participating = all action types (no action filter), both pending and done
            com.gl4a.gitlab.service.GitLabTodoService todoService =
                    ServiceFactory.get(com.gl4a.gitlab.service.GitLabTodoService.class, bypassCache);
            final String todoType = mIsMR ? "MergeRequest" : "Issue";
            final boolean isMentionedTab = "mentioned".equals(mScope);
            final String filterState = mIssueState != null ? mIssueState : "opened";
            final int PER_PAGE = 25;

            // onePage: fetch one (action, state) slice for the current page number.
            // Returns the response so we can inspect X-Next-Page for pagination.
            java.util.function.BiFunction<String, String,
                    Single<Response<java.util.List<com.gl4a.gitlab.model.GitLabTodo>>>>
                    onePage = (action, state) ->
                        todoService.listTodos(todoType, action, state, page, PER_PAGE)
                                .onErrorReturn(e -> retrofit2.Response.success(new java.util.ArrayList<>()));

            // Build the parallel call set:
            //   Mentioned    → 4 calls: (mentioned|directly_addressed) × (pending|done)
            //   Participating → 2 calls: (all actions) × (pending|done)
            final Single<Response<java.util.List<com.gl4a.gitlab.model.GitLabTodo>>>
                    s1, s2, s3, s4;
            if (isMentionedTab) {
                s1 = onePage.apply("mentioned",           "pending");
                s2 = onePage.apply("mentioned",           "done");
                s3 = onePage.apply("directly_addressed",  "pending");
                s4 = onePage.apply("directly_addressed",  "done");
            } else {
                s1 = onePage.apply(null, "pending");
                s2 = onePage.apply(null, "done");
                s3 = Single.just(retrofit2.Response.success(new java.util.ArrayList<>()));
                s4 = Single.just(retrofit2.Response.success(new java.util.ArrayList<>()));
            }

            return Single.zip(s1, s2, s3, s4,
                    (Response<java.util.List<com.gl4a.gitlab.model.GitLabTodo>> r1,
                     Response<java.util.List<com.gl4a.gitlab.model.GitLabTodo>> r2,
                     Response<java.util.List<com.gl4a.gitlab.model.GitLabTodo>> r3,
                     Response<java.util.List<com.gl4a.gitlab.model.GitLabTodo>> r4) -> {
                // Collect all todos from every sub-call
                java.util.List<com.gl4a.gitlab.model.GitLabTodo> allTodos = new java.util.ArrayList<>();
                int maxPageSize = 0; // track largest sub-response for next-page detection
                for (Response<java.util.List<com.gl4a.gitlab.model.GitLabTodo>> r
                        : new Response[]{r1, r2, r3, r4}) {
                    if (r.isSuccessful() && r.body() != null) {
                        allTodos.addAll(r.body());
                        maxPageSize = Math.max(maxPageSize, r.body().size());
                    }
                }
                // Deduplicate by issue.id; track most recent todo.createdAt for sort key
                java.util.Map<Long, String> latestDate = new java.util.HashMap<>();
                java.util.Map<Long, GitLabIssue> byId = new java.util.LinkedHashMap<>();
                for (com.gl4a.gitlab.model.GitLabTodo todo : allTodos) {
                    GitLabIssue issue = todo.targetIssue();
                    if (issue == null) continue;
                    // For MRs: treat "merged" as "closed" so merged MRs appear in the closed view
                    String issueState = issue.state;
                    if ("merged".equals(issueState)) issueState = "closed";
                    if (issueState != null && !filterState.equals(issueState)) continue;
                    String d = todo.createdAt != null ? todo.createdAt : "";
                    String prev = latestDate.get(issue.id);
                    if (prev == null || d.compareTo(prev) > 0) latestDate.put(issue.id, d);
                    byId.putIfAbsent(issue.id, issue);
                }
                java.util.List<GitLabIssue> issues = new java.util.ArrayList<>(byId.values());
                // Sort most recently notified first
                issues.sort((a, b) ->
                        latestDate.getOrDefault(b.id, "").compareTo(latestDate.getOrDefault(a.id, "")));
                // Pagination: if any sub-call returned a full page, assume there is a next page
                boolean hasMore = maxPageSize >= PER_PAGE;
                return Response.success(new GitLabPage<>(
                        issues, page, hasMore ? page + 1 : 0,
                        hasMore ? page + 2 : page, issues.size()));
            });
        } else if (mScope != null && !mScope.isEmpty()) {
            // Personal issues: GET /issues?scope=assigned_to_me&state=opened
            final GitLabIssueService service = ServiceFactory.get(GitLabIssueService.class, bypassCache);
            String state = mIssueState != null ? mIssueState : "opened";
            return service.listMyIssues(state, mScope, page, 25)
                    .map(response -> {
                        if (response.isSuccessful())
                            return Response.success(ApiHelpers.toPage(response));
                        return Response.<GitLabPage<GitLabIssue>>error(response.errorBody(), response.raw());
                    });
        } else {
            // Search: GET /search?scope=issues&search=query
            final GitLabSearchService service = ServiceFactory.get(GitLabSearchService.class, bypassCache);
            return service.searchIssues("issues", mQuery, page, 25)
                    .map(response -> {
                        if (response.isSuccessful())
                            return Response.success(ApiHelpers.toPage(response));
                        return Response.<GitLabPage<GitLabIssue>>error(response.errorBody(), response.raw());
                    });
        }
    }

    public static class SortDrawerHelper {
        private String mSortMode = "created_at";
        private boolean mSortAscending = false;

        private static final SparseArray<String[]> SORT_LOOKUP = new SparseArray<>();
        static {
            SORT_LOOKUP.put(R.id.sort_created_asc, new String[] { "created_at", "asc" });
            SORT_LOOKUP.put(R.id.sort_created_desc, new String[] { "created_at", "desc" });
            SORT_LOOKUP.put(R.id.sort_updated_asc, new String[] { "updated_at", "asc" });
            SORT_LOOKUP.put(R.id.sort_updated_desc, new String[] { "updated_at", "desc" });
            SORT_LOOKUP.put(R.id.sort_comments_asc, new String[] { "popularity", "asc" });
            SORT_LOOKUP.put(R.id.sort_comments_desc, new String[] { "popularity", "desc" });
        }

        public static int getMenuResId() {
            return R.menu.issue_list_sort;
        }

        public String getSortMode() {
            return mSortMode;
        }

        public String getSortOrder() {
            return mSortAscending ? "asc" : "desc";
        }

        public void setSortMode(String mode, String order) {
            if (findEntryIndex(mode, order) >= 0) {
                updateSortMode(mode, TextUtils.equals(order, "asc"));
            }
        }

        public void updateMenuCheckState(Menu menu) {
            int index = findEntryIndex(getSortMode(), getSortOrder());
            if (index >= 0) {
                menu.findItem(SORT_LOOKUP.keyAt(index)).setChecked(true);
            }
        }

        public boolean handleItemSelection(MenuItem item) {
            String[] value = SORT_LOOKUP.get(item.getItemId());
            if (value == null) {
                return false;
            }

            updateSortMode(value[0], TextUtils.equals(value[1], "asc"));
            return true;
        }

        protected void updateSortMode(String sortMode, boolean ascending) {
            mSortAscending = ascending;
            mSortMode = sortMode;
        }

        private int findEntryIndex(String mode, String order) {
            for (int i = 0; i < SORT_LOOKUP.size(); i++) {
                String[] value = SORT_LOOKUP.valueAt(i);
                if (TextUtils.equals(mode, value[0]) && TextUtils.equals(order, value[1])) {
                    return i;
                }
            }
            return -1;
        }
    }
}
