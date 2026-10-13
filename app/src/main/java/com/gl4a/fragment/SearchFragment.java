package com.gl4a.fragment;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.cursoradapter.widget.CursorAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.SearchView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FilterQueryProvider;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.FileViewerActivity;
import com.gl4a.activities.RepositoryActivity;
import com.gl4a.activities.UserActivity;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.adapter.SearchAdapter;
import com.gl4a.db.SuggestionsProvider;
import com.gl4a.gitlab.model.GitLabGroup;
import com.gl4a.gitlab.model.GitLabIssue;
import com.gl4a.gitlab.model.GitLabPage;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabSearchResult;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.service.GitLabProjectService;
import com.gl4a.gitlab.service.GitLabSearchService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.StringUtils;

import java.util.List;

import io.reactivex.Single;
import retrofit2.Response;

public class SearchFragment extends PagedDataBaseFragment<Object> implements
        SearchView.OnQueryTextListener, SearchView.OnCloseListener,
        SearchView.OnSuggestionListener, FilterQueryProvider,
        AdapterView.OnItemSelectedListener, SearchAdapter.Callback {

    public static SearchFragment newInstance(int initialType, String initialQuery,
            boolean startSearchImmediately) {
        SearchFragment f = new SearchFragment();
        Bundle args = new Bundle();
        args.putInt("search_type", initialType);
        args.putString("initial_search", initialQuery);
        args.putBoolean("do_initial_load", startSearchImmediately);
        f.setArguments(args);
        return f;
    }

    // Kept as stored in search history; the menu lists them in SPINNER_TYPES order
    public static final int SEARCH_TYPE_REPO = 0;
    public static final int SEARCH_TYPE_USER = 1;
    public static final int SEARCH_TYPE_CODE = 2;
    // Everything at once, like GitLab web, and the kinds it adds (#262)
    public static final int SEARCH_TYPE_ALL = 3;
    public static final int SEARCH_TYPE_GROUP = 4;
    public static final int SEARCH_TYPE_ISSUE = 5;
    public static final int SEARCH_TYPE_MERGE_REQUEST = 6;

    /** The search kinds in the menu's order. */
    private static final int[] SPINNER_TYPES = {
        SEARCH_TYPE_ALL, SEARCH_TYPE_REPO, SEARCH_TYPE_GROUP, SEARCH_TYPE_ISSUE,
        SEARCH_TYPE_MERGE_REQUEST, SEARCH_TYPE_USER, SEARCH_TYPE_CODE
    };

    /** Results of each kind the All search shows before its "Show all". */
    private static final int ALL_SECTION_SIZE = 5;

    private static int[] hintAndEmptyTextsFor(int type) {
        switch (type) {
            case SEARCH_TYPE_ALL:
                return new int[] { R.string.search_hint_all, R.string.no_search_results_found };
            case SEARCH_TYPE_GROUP:
                return new int[] { R.string.search_hint_group, R.string.no_search_groups_found };
            case SEARCH_TYPE_ISSUE:
                return new int[] { R.string.search_hint_issue, R.string.no_search_issues_found };
            case SEARCH_TYPE_MERGE_REQUEST:
                return new int[] { R.string.search_hint_merge_request,
                        R.string.no_search_merge_requests_found };
            case SEARCH_TYPE_USER:
                return new int[] { R.string.search_hint_user, R.string.no_search_users_found };
            case SEARCH_TYPE_CODE:
                return new int[] { R.string.search_hint_code, R.string.no_search_code_found };
            default:
                return new int[] { R.string.search_hint_repo, R.string.no_search_repos_found };
        }
    }

    private static int spinnerPositionOf(int type) {
        for (int i = 0; i < SPINNER_TYPES.length; i++) {
            if (SPINNER_TYPES[i] == type) return i;
        }
        return 0;
    }

    private static final String[] SUGGESTION_PROJECTION = {
            SuggestionsProvider.Columns._ID, SuggestionsProvider.Columns.SUGGESTION
    };
    private static final String SUGGESTION_SELECTION =
            SuggestionsProvider.Columns.TYPE + " = ? AND " +
                    SuggestionsProvider.Columns.SUGGESTION + " LIKE ? AND " +
                    SuggestionsProvider.Columns.ACCOUNT + " = ?";
    private static final String SUGGESTION_ORDER = SuggestionsProvider.Columns.DATE + " DESC";

    private static final String STATE_KEY_QUERY = "query";
    private static final String STATE_KEY_SEARCH_TYPE = "search_type";

    private SearchAdapter mAdapter;

    private Spinner mSearchType;
    private SearchView mSearch;
    private int mSelectedSearchType;
    private String mQuery;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        if (savedInstanceState != null) {
            mQuery = savedInstanceState.getString(STATE_KEY_QUERY);
            mSelectedSearchType = savedInstanceState.getInt(STATE_KEY_SEARCH_TYPE, SEARCH_TYPE_ALL);
        } else {
            Bundle args = getArguments();
            mSelectedSearchType = args.getInt("search_type", SEARCH_TYPE_ALL);
            mQuery = args.getString("initial_search");
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.search, menu);

        mSearchType = (Spinner) menu.findItem(R.id.type).getActionView();
        mSearchType.setAdapter(new SearchTypeAdapter(mSearchType.getContext(), getActivity()));
        mSearchType.setOnItemSelectedListener(this);
        mSearchType.setSelection(spinnerPositionOf(mSelectedSearchType));
        // android.widget.Spinner doesn't respond to colorControlNormal from AppCompat theme overlays.
        // Force-tint its background drawable (dropdown arrow) to the dark theme text colour.
        if (mSearchType.getBackground() != null) {
            int dark = com.gl4a.utils.UiUtils.resolveColor(requireActivity(), android.R.attr.textColorPrimary);
            androidx.core.graphics.drawable.DrawableCompat.setTint(
                    androidx.core.graphics.drawable.DrawableCompat.wrap(
                            mSearchType.getBackground().mutate()), dark);
        }

        SuggestionAdapter adapter = new SuggestionAdapter(getActivity());
        adapter.setFilterQueryProvider(this);

        mSearch = (SearchView) menu.findItem(R.id.search).getActionView();
        com.gl4a.utils.UiUtils.tintSearchViewIcons(mSearch);
        mSearch.setIconifiedByDefault(true);
        mSearch.requestFocus();
        mSearch.onActionViewExpanded();
        mSearch.setIconified(false);
        mSearch.setOnQueryTextListener(this);
        mSearch.setOnCloseListener(this);
        mSearch.setOnSuggestionListener(this);
        mSearch.setSuggestionsAdapter(adapter);
        if (mQuery != null) {
            mSearch.setQuery(mQuery, false);
        }

        updateSearchViewHint();

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_KEY_SEARCH_TYPE, mSelectedSearchType);
        outState.putString(STATE_KEY_QUERY, mQuery);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected RootAdapter<Object, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        mAdapter = new SearchAdapter(getActivity(), this);
        mAdapter.setMode(mSelectedSearchType);
        return mAdapter;
    }

    @Override
    protected boolean shouldDoInitialLoad(Bundle savedInstanceState) {
        return (savedInstanceState != null && mQuery != null)
                || getArguments().getBoolean("do_initial_load", true);
    }

    @Override
    protected Single<Response<GitLabPage<Object>>> loadPage(int page, boolean bypassCache) {
        if (TextUtils.isEmpty(mQuery)) {
            GitLabPage<Object> empty = new GitLabPage<>();
            empty.setItems(java.util.Collections.emptyList());
            return Single.just(Response.success(empty));
        }
        switch (mSelectedSearchType) {
            case SEARCH_TYPE_ALL: return makeAllSearchSingle(page, bypassCache);
            case SEARCH_TYPE_REPO: return makeRepoSearchSingle(page, bypassCache);
            case SEARCH_TYPE_GROUP: return makeGroupSearchSingle(page, bypassCache);
            case SEARCH_TYPE_ISSUE:
                return makeIssueSearchSingle("issues", page, bypassCache);
            case SEARCH_TYPE_MERGE_REQUEST:
                return makeIssueSearchSingle("merge_requests", page, bypassCache);
            case SEARCH_TYPE_USER: return makeUserSearchSingle(page, bypassCache);
            case SEARCH_TYPE_CODE: return makeCodeSearchSingle(page, bypassCache);
        }
        throw new IllegalStateException("Unexpected search type " + mSelectedSearchType);
    }

    @Override
    protected int getEmptyTextResId() {
        return 0; // updated dynamically
    }

    @Override
    public void onItemClick(Object item) {
        if (item instanceof SearchAdapter.Section) {
            // "Show all": that kind's own search, all its results (#262)
            mSearchType.setSelection(
                    spinnerPositionOf(((SearchAdapter.Section) item).searchType));
            return;
        }
        if (item instanceof GitLabGroup) {
            GitLabGroup group = (GitLabGroup) item;
            startActivity(com.gl4a.activities.GroupActivity.makeIntent(getActivity(),
                    group.fullPath != null ? group.fullPath : group.path));
            return;
        }
        if (item instanceof GitLabIssue) {
            // Issues and merge requests open in-app from GitLab's address for them
            String url = ((GitLabIssue) item).webUrl;
            if (url != null) {
                com.gl4a.utils.IntentUtils.openLinkInternallyOrExternally(requireActivity(),
                        Uri.parse(url));
            }
            return;
        }
        if (item instanceof GitLabProject) {
            startActivity(RepositoryActivity.makeIntent(getActivity(), (GitLabProject) item));
        } else if (item instanceof GitLabSearchResult.GitLabBlob) {
            openFileViewer((GitLabSearchResult.GitLabBlob) item, -1);
        } else if (item instanceof GitLabUser) {
            // Fix: GitLab /search?scope=users returns objects with username field.
            // UserActivity.makeIntent() returns null when user.login() is null (field-mapping mismatch).
            // Guard here to avoid NPE; the root cause is ensured by GitLabUser.login() -> username field.
            android.content.Intent intent = UserActivity.makeIntent(getActivity(), (GitLabUser) item);
            if (intent != null) {
                startActivity(intent);
            }
        }
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        mQuery = query;
        if (!StringUtils.isBlank(query)) {
            final ContentResolver cr = getActivity().getContentResolver();
            final ContentValues cv = new ContentValues();
            cv.put(SuggestionsProvider.Columns.TYPE, mSelectedSearchType);
            cv.put(SuggestionsProvider.Columns.SUGGESTION, query);
            cv.put(SuggestionsProvider.Columns.DATE, System.currentTimeMillis());
            cv.put(SuggestionsProvider.Columns.ACCOUNT, currentAccount());

            new Thread() {
                @Override
                public void run() {
                    cr.insert(SuggestionsProvider.Columns.CONTENT_URI, cv);
                }
            }.start();
        }
        loadResults();
        return true;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        mQuery = newText;
        return true;
    }

    @Override
    public boolean onClose() {
        if (mAdapter != null) {
            mAdapter.clear();
        }
        mQuery = null;
        return true;
    }

    @Override
    public boolean onSuggestionSelect(int position) {
        return false;
    }

    @Override
    public boolean onSuggestionClick(int position) {
        Cursor cursor = mSearch.getSuggestionsAdapter().getCursor();
        if (cursor.moveToPosition(position)) {
            if (position == cursor.getCount() - 1) {
                final int type = mSelectedSearchType;
                final String account = currentAccount();
                final ContentResolver cr = getActivity().getContentResolver();
                new Thread() {
                    @Override
                    public void run() {
                        cr.delete(SuggestionsProvider.Columns.CONTENT_URI,
                                SuggestionsProvider.Columns.TYPE + " = ? AND "
                                        + SuggestionsProvider.Columns.ACCOUNT + " = ?",
                                new String[] { String.valueOf(type), account });
                    }
                }.start();
            } else {
                mQuery = cursor.getString(1);
                mSearch.setQuery(mQuery, true);
            }
        }
        return true;
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        updateSelectedSearchType();
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        updateSelectedSearchType();
    }

    @Override
    public void onSearchFragmentClick(GitLabSearchResult.GitLabBlob result, int matchIndex) {
        openFileViewer(result, matchIndex);
    }

    @Override
    public Cursor runQuery(CharSequence query) {
        if (TextUtils.isEmpty(query)) {
            return null;
        }
        return getContext().getContentResolver().query(SuggestionsProvider.Columns.CONTENT_URI,
                SUGGESTION_PROJECTION, SUGGESTION_SELECTION,
                new String[] { String.valueOf(mSelectedSearchType), query + "%", currentAccount() },
                SUGGESTION_ORDER);
    }

    /** Search history is per account, the same key as bookmarks (#237). */
    private static String currentAccount() {
        String account = com.gl4a.Gl4Application.get().getAuthLogin();
        return account != null ? account : "";
    }

    private void openFileViewer(GitLabSearchResult.GitLabBlob result, int matchIndex) {
        // Look up the project by ID to obtain the correct owner/repo path for FileViewerActivity.
        GitLabProjectService projectService = ServiceFactory.get(GitLabProjectService.class, false);
        projectService.getProject(result.projectId)
                .map(ApiHelpers::throwOnFailure)
                .map(java.util.Optional::ofNullable)
                .onErrorReturnItem(java.util.Optional.empty())
                .subscribe(projectOpt -> {
                    if (!projectOpt.isPresent()) return;
                    GitLabProject project = projectOpt.get();
                    if (project.pathWithNamespace != null && project.pathWithNamespace.contains("/")) {
                        int slash = project.pathWithNamespace.lastIndexOf('/');
                        String owner = project.pathWithNamespace.substring(0, slash);
                        String repoName = project.pathWithNamespace.substring(slash + 1);
                        startActivity(FileViewerActivity.makeIntent(getActivity(),
                                owner, repoName, result.ref,
                                result.path != null ? result.path : result.filename));
                    }
                    // If project lookup failed or namespace missing, silently skip
                });
    }

    private void loadResults() {
        mSearch.clearFocus();
        onRefresh();
    }

    private void updateSearchViewHint() {
        int[] hintAndEmptyTextResIds = hintAndEmptyTextsFor(mSelectedSearchType);
        mSearch.setQueryHint(getString(hintAndEmptyTextResIds[0]));
        updateEmptyText(hintAndEmptyTextResIds[1]);
    }

    private void updateSelectedSearchType() {
        int position = mSearchType.getSelectedItemPosition();
        int newType = position >= 0 && position < SPINNER_TYPES.length
                ? SPINNER_TYPES[position] : SEARCH_TYPE_ALL;
        if (newType == mSelectedSearchType) {
            return;
        }
        mSelectedSearchType = newType;
        mAdapter.setMode(newType);

        updateSearchViewHint();
        updateEmptyState();
        resetSubject();

        mSearch.setQuery(mQuery, true);
    }

    private void updateEmptyText(@StringRes int emptyTextResId) {
        TextView emptyView = getView().findViewById(android.R.id.empty);
        emptyView.setText(emptyTextResId);
    }

    /**
     * Promotes a typed page to an Object page, preserving pagination headers read by
     * ApiHelpers.toPage() so subsequent pages beyond the first 25 results are fetched.
     */
    private static <T> Response<GitLabPage<Object>> toObjectPage(Response<List<T>> response) {
        GitLabPage<T> typed = ApiHelpers.toPage(response);
        GitLabPage<Object> page = new GitLabPage<>(
                (List<Object>) (List<?>) typed.items(),
                typed.currentPage(),
                typed.nextPage(),
                typed.totalPages(),
                typed.totalItems());
        return Response.success(page);
    }

    // The user's own projects and groups come first on page 1 and are left out of later pages,
    // which come from the whole instance (#262)
    private final java.util.Set<Long> mOwnProjectIds =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final java.util.Set<Long> mOwnGroupIds =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static <T> List<T> bodyOrEmpty(Response<List<T>> response) {
        return response.isSuccessful() && response.body() != null
                ? response.body() : java.util.Collections.emptyList();
    }

    private static Response<GitLabPage<Object>> objectPage(List<?> items, int page, int nextPage) {
        return Response.success(new GitLabPage<>(new java.util.ArrayList<Object>(items), page,
                nextPage, nextPage > 0 ? nextPage : page, items.size()));
    }

    /**
     * Projects: the user's own matching ones first, also by their group's path ("webmine"
     * finds the projects in the WebMine group), then the instance's search results without
     * those (#262). The instance's search matches a project's own name only and ranks
     * unrelated public projects above the user's.
     */
    private Single<Response<GitLabPage<Object>>> makeRepoSearchSingle(int page, boolean bypassCache) {
        GitLabSearchService service = ServiceFactory.get(GitLabSearchService.class, bypassCache);
        Single<Response<List<GitLabProject>>> others =
                service.searchProjects("projects", mQuery, page, 25);
        if (page > 1) {
            return others.<Response<GitLabPage<Object>>>map(response -> {
                if (!response.isSuccessful() && response.code() != 422) {
                    return Response.<GitLabPage<Object>>error(response.errorBody(), response.raw());
                }
                List<Object> items = new java.util.ArrayList<>();
                for (GitLabProject project : bodyOrEmpty(response)) {
                    if (!mOwnProjectIds.contains(project.id)) items.add(project);
                }
                int next = response.isSuccessful() ? ApiHelpers.toPage(response).nextPage() : 0;
                return objectPage(items, page, next);
            });
        }
        GitLabProjectService projects = ServiceFactory.get(GitLabProjectService.class, bypassCache);
        return Single.zip(
                projects.searchOwnProjects(mQuery, 1, 20).onErrorReturnItem(
                        Response.success(java.util.Collections.<GitLabProject>emptyList())),
                others,
                (own, rest) -> {
                    mOwnProjectIds.clear();
                    List<Object> items = new java.util.ArrayList<>();
                    for (GitLabProject project : bodyOrEmpty(own)) {
                        if (mOwnProjectIds.add(project.id)) items.add(project);
                    }
                    // 422: the scope has no results
                    if (!rest.isSuccessful() && rest.code() != 422 && items.isEmpty()) {
                        return Response.<GitLabPage<Object>>error(rest.errorBody(), rest.raw());
                    }
                    for (GitLabProject project : bodyOrEmpty(rest)) {
                        if (!mOwnProjectIds.contains(project.id)) items.add(project);
                    }
                    int next = rest.isSuccessful() ? ApiHelpers.toPage(rest).nextPage() : 0;
                    return objectPage(items, 1, next);
                });
    }

    /** Groups: the user's own matching ones first, then the others they can see (#262). */
    private Single<Response<GitLabPage<Object>>> makeGroupSearchSingle(int page, boolean bypassCache) {
        com.gl4a.gitlab.service.GitLabGroupService groups =
                ServiceFactory.get(com.gl4a.gitlab.service.GitLabGroupService.class, bypassCache);
        Single<Response<List<GitLabGroup>>> others =
                groups.searchGroups(mQuery, null, true, page, 25);
        if (page > 1) {
            return others.<Response<GitLabPage<Object>>>map(response -> {
                if (!response.isSuccessful()) {
                    return Response.<GitLabPage<Object>>error(response.errorBody(), response.raw());
                }
                List<Object> items = new java.util.ArrayList<>();
                for (GitLabGroup group : bodyOrEmpty(response)) {
                    if (!mOwnGroupIds.contains(group.id)) items.add(group);
                }
                return objectPage(items, page, ApiHelpers.toPage(response).nextPage());
            });
        }
        return Single.zip(
                groups.searchGroups(mQuery, 10, null, 1, 20).onErrorReturnItem(
                        Response.success(java.util.Collections.<GitLabGroup>emptyList())),
                others,
                (own, rest) -> {
                    mOwnGroupIds.clear();
                    List<Object> items = new java.util.ArrayList<>();
                    for (GitLabGroup group : bodyOrEmpty(own)) {
                        if (mOwnGroupIds.add(group.id)) items.add(group);
                    }
                    if (!rest.isSuccessful() && items.isEmpty()) {
                        return Response.<GitLabPage<Object>>error(rest.errorBody(), rest.raw());
                    }
                    for (GitLabGroup group : bodyOrEmpty(rest)) {
                        if (!mOwnGroupIds.contains(group.id)) items.add(group);
                    }
                    int next = rest.isSuccessful() ? ApiHelpers.toPage(rest).nextPage() : 0;
                    return objectPage(items, 1, next);
                });
    }

    private final java.util.Set<Long> mOwnIssueIds =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final java.util.Set<Long> mOwnMergeRequestIds =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * Issues, or merge requests with scope "merge_requests" (#262): the user's own matching
     * ones first, in any state, then the instance's search results without them. GitLab.com
     * fills the instance's first page with other people's matches (#264).
     */
    private Single<Response<GitLabPage<Object>>> makeIssueSearchSingle(String scope, int page,
            boolean bypassCache) {
        boolean mergeRequests = "merge_requests".equals(scope);
        java.util.Set<Long> ownIds = mergeRequests ? mOwnMergeRequestIds : mOwnIssueIds;
        GitLabSearchService service = ServiceFactory.get(GitLabSearchService.class, bypassCache);
        Single<Response<List<GitLabIssue>>> others = service.searchIssues(scope, mQuery, page, 25);
        if (page > 1) {
            return others.<Response<GitLabPage<Object>>>map(response -> {
                if (!response.isSuccessful() && response.code() != 422) {
                    return Response.<GitLabPage<Object>>error(response.errorBody(), response.raw());
                }
                List<Object> items = new java.util.ArrayList<>();
                for (GitLabIssue issue : bodyOrEmpty(response)) {
                    if (!ownIds.contains(issue.id)) items.add(issue);
                }
                int next = response.isSuccessful() ? ApiHelpers.toPage(response).nextPage() : 0;
                return objectPage(items, page, next);
            });
        }
        return Single.zip(ownIssuesOrMergeRequests(mergeRequests, bypassCache), others,
                (own, rest) -> {
                    ownIds.clear();
                    List<Object> items = new java.util.ArrayList<>();
                    for (GitLabIssue issue : own) {
                        if (ownIds.add(issue.id)) items.add(issue);
                    }
                    // 422: the scope has no results
                    if (!rest.isSuccessful() && rest.code() != 422 && items.isEmpty()) {
                        return Response.<GitLabPage<Object>>error(rest.errorBody(), rest.raw());
                    }
                    for (GitLabIssue issue : bodyOrEmpty(rest)) {
                        if (!ownIds.contains(issue.id)) items.add(issue);
                    }
                    int next = rest.isSuccessful() ? ApiHelpers.toPage(rest).nextPage() : 0;
                    return objectPage(items, 1, next);
                });
    }

    /**
     * The user's own issues or merge requests matching the search, in any state: ones they
     * created, are assigned to and, for merge requests, are reviewing; latest activity first
     * (#264).
     */
    private Single<List<GitLabIssue>> ownIssuesOrMergeRequests(boolean mergeRequests,
            boolean bypassCache) {
        List<java.util.Map<String, String>> filters = new java.util.ArrayList<>();
        filters.add(new java.util.HashMap<>(java.util.Collections.singletonMap(
                "scope", "created_by_me")));
        filters.add(new java.util.HashMap<>(java.util.Collections.singletonMap(
                "scope", "assigned_to_me")));
        String login = com.gl4a.Gl4Application.get().getAuthLogin();
        if (mergeRequests && login != null) {
            java.util.Map<String, String> reviewing = new java.util.HashMap<>();
            reviewing.put("scope", "all");
            reviewing.put("reviewer_username", login);
            filters.add(reviewing);
        }
        com.gl4a.gitlab.service.GitLabIssueService issues =
                ServiceFactory.get(com.gl4a.gitlab.service.GitLabIssueService.class, bypassCache);
        com.gl4a.gitlab.service.GitLabMergeRequestService mrs = ServiceFactory.get(
                com.gl4a.gitlab.service.GitLabMergeRequestService.class, bypassCache);
        List<Single<List<GitLabIssue>>> requests = new java.util.ArrayList<>();
        for (java.util.Map<String, String> filter : filters) {
            filter.put("search", mQuery);
            filter.put("state", "all");
            filter.put("order_by", "updated_at");
            filter.put("sort", "desc");
            Single<Response<List<GitLabIssue>>> call = mergeRequests
                    ? mrs.listMergeRequestsAsIssues(filter, 1, 20)
                    : issues.listIssuesWithParams(filter, 1, 20);
            requests.add(call.map(response -> bodyOrEmpty(response))
                    .onErrorReturnItem(java.util.Collections.<GitLabIssue>emptyList()));
        }
        return Single.zip(requests, results -> {
            java.util.Map<Long, GitLabIssue> byId = new java.util.LinkedHashMap<>();
            for (Object result : results) {
                for (Object issue : (List<?>) result) {
                    byId.putIfAbsent(((GitLabIssue) issue).id, (GitLabIssue) issue);
                }
            }
            List<GitLabIssue> own = new java.util.ArrayList<>(byId.values());
            // GitLab's ISO 8601 times in UTC sort as text
            java.util.Collections.sort(own, (a, b) -> {
                String ta = a.updatedAt, tb = b.updatedAt;
                if (ta == null || tb == null) return ta == null ? (tb == null ? 0 : 1) : -1;
                return tb.compareTo(ta);
            });
            return own;
        });
    }

    /**
     * Everything, like GitLab web: a section per kind with its first few results and a
     * "Show all" that opens that kind's search. A kind the instance can't search (e.g. code
     * without advanced search) is left out (#262).
     */
    private Single<Response<GitLabPage<Object>>> makeAllSearchSingle(int page, boolean bypassCache) {
        if (page > 1) {
            return Single.just(objectPage(java.util.Collections.emptyList(), page, 0));
        }
        List<Single<List<Object>>> sections = java.util.Arrays.asList(
                section(SEARCH_TYPE_GROUP, R.string.search_section_groups,
                        makeGroupSearchSingle(1, bypassCache)),
                section(SEARCH_TYPE_REPO, R.string.search_section_projects,
                        makeRepoSearchSingle(1, bypassCache)),
                section(SEARCH_TYPE_ISSUE, R.string.search_section_issues,
                        makeIssueSearchSingle("issues", 1, bypassCache)),
                section(SEARCH_TYPE_MERGE_REQUEST, R.string.search_section_merge_requests,
                        makeIssueSearchSingle("merge_requests", 1, bypassCache)),
                section(SEARCH_TYPE_USER, R.string.search_section_users,
                        makeUserSearchSingle(1, bypassCache)),
                section(SEARCH_TYPE_CODE, R.string.search_section_code,
                        makeCodeSearchSingle(1, bypassCache)));
        return Single.zip(sections, results -> {
            List<Object> items = new java.util.ArrayList<>();
            for (Object result : results) {
                items.addAll((List<?>) result);
            }
            return objectPage(items, 1, 0);
        });
    }

    /** A section of the All search: its header and first few results, or nothing (#262). */
    private static Single<List<Object>> section(int type, @StringRes int title,
            Single<Response<GitLabPage<Object>>> results) {
        return results
                .map(response -> {
                    List<Object> items = new java.util.ArrayList<>();
                    GitLabPage<Object> page = response.isSuccessful() ? response.body() : null;
                    if (page != null && page.items() != null && !page.items().isEmpty()) {
                        items.add(new SearchAdapter.Section(type, title));
                        items.addAll(page.items().subList(0,
                                Math.min(ALL_SECTION_SIZE, page.items().size())));
                    }
                    return items;
                })
                .onErrorReturnItem(new java.util.ArrayList<>());
    }

    private Single<Response<GitLabPage<Object>>> makeUserSearchSingle(int page, boolean bypassCache) {
        GitLabSearchService service = ServiceFactory.get(GitLabSearchService.class, bypassCache);

        return service.searchUsers("users", mQuery, page, 25)
                .map(response -> {
                    if (response.isSuccessful()) {
                        return toObjectPage(response);
                    }
                    return Response.<GitLabPage<Object>>error(response.errorBody(), response.raw());
                });
    }

    private Single<Response<GitLabPage<Object>>> makeCodeSearchSingle(int page, boolean bypassCache) {
        GitLabSearchService service = ServiceFactory.get(GitLabSearchService.class, bypassCache);

        return service.searchCode("blobs", mQuery, page, 25)
                .map(response -> {
                    if (response.isSuccessful()) {
                        return toObjectPage(response);
                    }
                    return Response.<GitLabPage<Object>>error(response.errorBody(), response.raw());
                });
    }

    private static class SearchTypeAdapter extends BaseAdapter implements SpinnerAdapter {
        private final Context mContext;
        private final Context mPopupContext;
        private final LayoutInflater mInflater;
        private final LayoutInflater mPopupInflater;

        // In SPINNER_TYPES order
        private final int[][] mResources = new int[][] {
            { R.string.search_type_all, R.drawable.menu_search, R.drawable.menu_search },
            { R.string.search_type_repo, R.drawable.menu_search_repos, R.drawable.icon_repositories },
            { R.string.search_type_group, R.drawable.icon_groups, R.drawable.icon_groups },
            { R.string.search_type_issue, R.drawable.icon_issues, R.drawable.icon_issues },
            { R.string.search_type_merge_request, R.drawable.icon_pull_request,
                    R.drawable.icon_pull_request },
            { R.string.search_type_user, R.drawable.menu_search_users, R.drawable.search_users },
            { R.string.search_type_code, R.drawable.menu_search_code, R.drawable.search_code }
        };

        private SearchTypeAdapter(Context context, Context popupContext) {
            mContext = context;
            mPopupContext = popupContext;
            mInflater = LayoutInflater.from(context);
            mPopupInflater = LayoutInflater.from(popupContext);
        }

        @Override
        public int getCount() {
            return mResources.length;
        }

        @Override
        public CharSequence getItem(int position) {
            return mContext.getString(mResources[position][0]);
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.search_type_small, null);
            }

            ImageView icon = convertView.findViewById(R.id.icon);
            icon.setImageResource(mResources[position][1]);
            // Force dark tint — Spinner toolbar context may resolve colorControlNormal as white.
            // Uses theme_text_primary (#1F1E24) directly for reliability.
            icon.setColorFilter(
                    com.gl4a.utils.UiUtils.resolveColor(mPopupContext, android.R.attr.textColorPrimary),
                    android.graphics.PorterDuff.Mode.SRC_IN);

            return convertView;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = mPopupInflater.inflate(R.layout.search_type_popup, null);
            }

            ImageView icon = convertView.findViewById(R.id.icon);
            icon.setImageResource(mResources[position][2]);

            TextView label = convertView.findViewById(R.id.label);
            label.setText(mResources[position][0]);

            return convertView;
        }
    }

    private class SuggestionAdapter extends CursorAdapter {
        private final LayoutInflater mInflater;

        public SuggestionAdapter(Context context) {
            super(context, null, false);
            mInflater = LayoutInflater.from(context);
        }

        @Override
        public Cursor swapCursor(Cursor newCursor) {
            if (newCursor != null && newCursor.getCount() > 0) {
                MatrixCursor clearRowCursor = new MatrixCursor(SUGGESTION_PROJECTION);
                clearRowCursor.addRow(new Object[] {
                        Long.MAX_VALUE,
                        mInflater.getContext().getString(R.string.clear_suggestions)
                });
                newCursor = new MergeCursor(new Cursor[] { newCursor, clearRowCursor });
            }
            return super.swapCursor(newCursor);
        }

        @Override
        public int getItemViewType(int position) {
            return isClearRow(position) ? 1 : 0;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            @LayoutRes int layoutResId = isClearRow(cursor.getPosition())
                    ? R.layout.row_suggestion_clear : R.layout.row_suggestion;
            return mInflater.inflate(layoutResId, parent, false);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            int columnIndex = cursor.getColumnIndexOrThrow(SuggestionsProvider.Columns.SUGGESTION);
            String suggestionText = cursor.getString(columnIndex);
            if (isClearRow(cursor.getPosition())) {
                bindClearSuggestionsRow(view, suggestionText);
            } else {
                bindSuggestionRow(view, suggestionText);
            }
        }

        private void bindSuggestionRow(View view, String suggestionText) {
            TextView textView = view.findViewById(R.id.suggestion_text);
            textView.setText(suggestionText);
            view.findViewById(R.id.select_suggestion_button).setOnClickListener(btn -> {
                mQuery = suggestionText;
                mSearch.setQuery(mQuery, false);
            });
        }

        private void bindClearSuggestionsRow(View view, String suggestionText) {
            TextView textView = (TextView) view;
            textView.setText(suggestionText);
        }

        private boolean isClearRow(int position) {
            return position == getCount() - 1;
        }
    }
}
