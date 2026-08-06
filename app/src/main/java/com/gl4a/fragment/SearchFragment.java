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

    public static final int SEARCH_TYPE_REPO = 0;
    public static final int SEARCH_TYPE_USER = 1;
    public static final int SEARCH_TYPE_CODE = 2;

    private static final int[][] HINT_AND_EMPTY_TEXTS = {
        { R.string.search_hint_repo, R.string.no_search_repos_found },
        { R.string.search_hint_user, R.string.no_search_users_found },
        { R.string.search_hint_code, R.string.no_search_code_found }
    };

    private static final String[] SUGGESTION_PROJECTION = {
            SuggestionsProvider.Columns._ID, SuggestionsProvider.Columns.SUGGESTION
    };
    private static final String SUGGESTION_SELECTION =
            SuggestionsProvider.Columns.TYPE + " = ? AND " +
                    SuggestionsProvider.Columns.SUGGESTION + " LIKE ?";
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
            mSelectedSearchType = savedInstanceState.getInt(STATE_KEY_SEARCH_TYPE, SEARCH_TYPE_REPO);
        } else {
            Bundle args = getArguments();
            mSelectedSearchType = args.getInt("search_type", SEARCH_TYPE_REPO);
            mQuery = args.getString("initial_search");
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.search, menu);

        mSearchType = (Spinner) menu.findItem(R.id.type).getActionView();
        mSearchType.setAdapter(new SearchTypeAdapter(mSearchType.getContext(), getActivity()));
        mSearchType.setOnItemSelectedListener(this);
        mSearchType.setSelection(mSelectedSearchType);

        SuggestionAdapter adapter = new SuggestionAdapter(getActivity());
        adapter.setFilterQueryProvider(this);

        mSearch = (SearchView) menu.findItem(R.id.search).getActionView();
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
            case SEARCH_TYPE_REPO: return makeRepoSearchSingle(page, bypassCache);
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
                final ContentResolver cr = getActivity().getContentResolver();
                new Thread() {
                    @Override
                    public void run() {
                        cr.delete(SuggestionsProvider.Columns.CONTENT_URI,
                                SuggestionsProvider.Columns.TYPE + " = ?",
                                new String[] { String.valueOf(type) });
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
                new String[] { String.valueOf(mSelectedSearchType), query + "%" }, SUGGESTION_ORDER);
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
        int[] hintAndEmptyTextResIds = HINT_AND_EMPTY_TEXTS[mSelectedSearchType];
        mSearch.setQueryHint(getString(hintAndEmptyTextResIds[0]));
        updateEmptyText(hintAndEmptyTextResIds[1]);
    }

    private void updateSelectedSearchType() {
        int newType = mSearchType.getSelectedItemPosition();
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

    private Single<Response<GitLabPage<Object>>> makeRepoSearchSingle(int page, boolean bypassCache) {
        GitLabSearchService service = ServiceFactory.get(GitLabSearchService.class, bypassCache);

        return service.searchProjects("projects", mQuery, page, 25)
                .<Response<GitLabPage<Object>>>map(response -> {
                    if (response.isSuccessful()) {
                        return toObjectPage(response);
                    }
                    // 422: scope has no results — return empty page
                    if (response.code() == 422) {
                        return Response.success(new GitLabPage<>());
                    }
                    return Response.error(response.errorBody(), response.raw());
                });
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
        private final LayoutInflater mInflater;
        private final LayoutInflater mPopupInflater;

        private final int[][] mResources = new int[][] {
            { R.string.search_type_repo, R.drawable.menu_search_repos, R.drawable.icon_repositories },
            { R.string.search_type_user, R.drawable.menu_search_users, R.drawable.search_users },
            { R.string.search_type_code, R.drawable.menu_search_code, R.drawable.search_code }
        };

        private SearchTypeAdapter(Context context, Context popupContext) {
            mContext = context;
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
