package com.gl4a.activities;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.core.util.Pair;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.gl4a.BaseFragmentPagerActivity;
import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.db.BookmarksProvider;
import com.gl4a.fragment.CommitListFragment;
import com.gl4a.fragment.ContentListContainerFragment;
import com.gl4a.fragment.RepositoryEventListFragment;
import com.gl4a.fragment.RepositoryFragment;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.DownloadUtils;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;
import com.gl4a.gitlab.model.GitLabBranch;
import com.gl4a.gitlab.model.GitLabCommit;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabTag;
import com.gl4a.gitlab.service.GitLabProjectService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;

public class RepositoryActivity extends BaseFragmentPagerActivity implements
        CommitListFragment.ContextSelectionCallback,
        ContentListContainerFragment.CommitSelectionCallback {
    public static Intent makeIntent(Context context, GitLabProject repo) {
        return makeIntent(context, repo, null);
    }

    public static Intent makeIntent(Context context, GitLabProject repo, String ref) {
        // Prefer pathWithNamespace (e.g. "compliance/all-teams") to derive owner and repo path.
        // repo.name() returns the display name ("All Teams") which may contain spaces/capitals
        // and cannot be used as a URL path segment.
        String repoPath;
        String owner;
        if (repo.pathWithNamespace != null && repo.pathWithNamespace.contains("/")) {
            int slash = repo.pathWithNamespace.lastIndexOf('/');
            owner    = repo.pathWithNamespace.substring(0, slash);
            repoPath = repo.pathWithNamespace.substring(slash + 1);
        } else if (repo.path != null && !repo.path.isEmpty()) {
            repoPath = repo.path;
            com.gl4a.gitlab.model.GitLabUser ownerUser = repo.owner();
            if (ownerUser != null && ownerUser.login() != null) {
                owner = ownerUser.login();
            } else if (repo.namespace != null && repo.namespace.path != null) {
                owner = repo.namespace.path;
            } else {
                owner = "";
            }
        } else {
            repoPath = repo.name();
            owner = "";
        }
        return makeIntent(context, owner, repoPath, ref);
    }

    public static Intent makeIntent(Context context, String repoOwner, String repoName) {
        return makeIntent(context, repoOwner, repoName, null);
    }

    public static Intent makeIntent(Context context, String repoOwner, String repoName, String ref) {
        return makeIntent(context, repoOwner, repoName, ref, null, PAGE_REPO_OVERVIEW);
    }

    public static Intent makeIntent(Context context, String repoOwner, String repoName, String ref,
            String initialPath, int initialPage) {
        if (TextUtils.isEmpty(ref)) {
            ref = null;
        }
        return new Intent(context, RepositoryActivity.class)
                .putExtra("owner", repoOwner)
                .putExtra("repo", repoName)
                .putExtra("ref", ref)
                .putExtra("initial_path", initialPath)
                .putExtra("initial_page", initialPage);
    }

    private static final String STATE_KEY_SELECTED_REF = "selected_ref";

    private static final int ID_LOADER_REPO = 0;

    public static final int PAGE_REPO_OVERVIEW = 0;
    public static final int PAGE_FILES = 1;
    public static final int PAGE_COMMITS = 2;
    public static final int PAGE_ACTIVITY = 3;

    private static final int[] TITLES = new int[] {
        R.string.about, R.string.repo_files, R.string.commits, R.string.repo_activity
    };

    private String mRepoOwner;
    private String mRepoName;
    private ActionBar mActionBar;
    private int mInitialPage;
    private String mInitialPath;

    private GitLabProject mRepository;
    private List<GitLabBranch> mBranches;
    private List<GitLabBranch> mTags;
    private String mSelectedRef;

    private RepositoryFragment mRepositoryFragment;
    private ContentListContainerFragment mContentListFragment;
    private CommitListFragment mCommitListFragment;
    private RepositoryEventListFragment mActivityFragment;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mSelectedRef = savedInstanceState.getString(STATE_KEY_SELECTED_REF);
        }

        mActionBar = getSupportActionBar();
        mActionBar.setTitle(mRepoOwner + "/" + mRepoName); // placeholder until repo loads
        mActionBar.setDisplayHomeAsUpEnabled(true);

        setContentShown(false);

        loadRepository();
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mRepoOwner = extras.getString("owner");
        mRepoName = extras.getString("repo");
        mSelectedRef = extras.getString("ref");
        mInitialPage = extras.getInt("initial_page", -1);
        mInitialPath = extras.getString("initial_path");
        if (mRepoOwner != null && mRepoName != null) {
            Gl4Application.get().setCurrentProjectPath(mRepoOwner + "/" + mRepoName);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_KEY_SELECTED_REF, mSelectedRef);
    }

    @Override
    public void onCommitSelectedAsBase(GitLabCommit commit) {
        setSelectedRef(commit.sha());
    }

    @Override
    public boolean baseSelectionAllowed() {
        return true;
    }

    private void updateTitle() {
        // The repository may have been moved or renamed, so we want to make sure that
        // the title matches the current name of the repository
        mActionBar.setTitle(mRepository.displayName());

        mActionBar.setSubtitle(getCurrentRef());
        invalidateFragments();
    }

    private String getCurrentRef() {
        if (!TextUtils.isEmpty(mSelectedRef)) {
            return mSelectedRef;
        }
        return mRepository != null ? mRepository.defaultBranch() : "";
    }

    private String getBookmarkUrl() {
        if (mRepository == null) {
            return Gl4Application.get().getInstanceUrl() + "/" + mRepoOwner + "/" + mRepoName;
        }
        String url = mRepository.htmlUrl();
        String ref = getCurrentRef();
        return ref.equals(mRepository.defaultBranch()) ? url : url + "/-/tree/" + ref;
    }

    @Override
    protected int[] getTabTitleResIds() {
        return mRepository != null ? TITLES : null;
    }

    @Override
    protected Fragment makeFragment(int position) {
        switch (position) {
            case 0:
                return RepositoryFragment.newInstance(mRepository, mSelectedRef);
            case 1:
                Fragment f = ContentListContainerFragment.newInstance(mRepository,
                        mSelectedRef, mInitialPath);
                mInitialPath = null;
                return f;
            case 2:
                return CommitListFragment.newInstance(mRepository, mSelectedRef);
            case 3:
                return RepositoryEventListFragment.newInstance(mRepository);
        }
        return null;
    }

    @Override
    protected void onFragmentInstantiated(Fragment f, int position) {
        switch (position) {
            case 0: mRepositoryFragment = (RepositoryFragment) f; break;
            case 1: mContentListFragment = (ContentListContainerFragment) f; break;
            case 2: mCommitListFragment = (CommitListFragment) f; break;
            case 3: mActivityFragment = (RepositoryEventListFragment) f; break;
        }
    }

    @Override
    protected void onFragmentDestroyed(Fragment f) {
        if (f == mRepositoryFragment) {
            mRepositoryFragment = null;
        } else if (f == mContentListFragment) {
            mContentListFragment = null;
        } else if (f == mCommitListFragment) {
            mCommitListFragment = null;
        } else if (f == mActivityFragment) {
            mActivityFragment = null;
        }
    }

    @Override
    protected boolean fragmentNeedsRefresh(Fragment fragment) {
        if (fragment instanceof CommitListFragment && mCommitListFragment == null) {
            return true;
        } else if (fragment instanceof ContentListContainerFragment
                && mContentListFragment == null) {
            return true;
        } else if (fragment instanceof RepositoryFragment && mRepositoryFragment == null) {
            return true;
        } else if (fragment instanceof RepositoryEventListFragment && mActivityFragment == null) {
            return true;
        }
        return false;
    }

    @Override
    public void onRefresh() {
        mRepositoryFragment = null;
        mContentListFragment = null;
        mActivityFragment = null;
        mRepository = null;
        mBranches = null;
        mTags = null;
        clearRefDependentFragments();
        setContentShown(false);
        invalidateTabs();
        loadRepository();
        super.onRefresh();
    }

    @Override
    public void onBackPressed() {
        if (mContentListFragment != null) {
            if (getPager().getCurrentItem() == 1 && mContentListFragment.handleBackPress()) {
                return;
            }
        }
        super.onBackPressed();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.repo_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (mRepository == null) {
            menu.removeItem(R.id.ref);
            menu.removeItem(R.id.bookmark);
            menu.removeItem(R.id.zip_download);
        } else {
            MenuItem bookmarkAction = menu.findItem(R.id.bookmark);
            if (bookmarkAction != null) {
                bookmarkAction.setTitle(BookmarksProvider.hasBookmarked(this, getBookmarkUrl())
                        ? R.string.remove_bookmark
                        : R.string.bookmark);
            }
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    protected Intent navigateUp() {
        return UserActivity.makeIntent(this, mRepoOwner);
    }

    @Override
    public boolean displayDetachAction() {
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Uri url = IntentUtils.createBaseUriForRepo(mRepoOwner, mRepoName).build();
        switch (item.getItemId()) {
            case R.id.ref:
                loadOrShowRefSelection();
                return true;
            case R.id.share:
                IntentUtils.share(this, mRepoOwner + "/" + mRepoName, url);
                return true;
            case R.id.browser:
                IntentUtils.launchBrowser(this, url);
                return true;
            case R.id.search:
                String initialSearch = "repo:" + mRepoOwner + "/" + mRepoName + " ";
                startActivity(SearchActivity.makeIntent(this, initialSearch,
                        SearchActivity.SEARCH_TYPE_CODE, false));
                return true;
            case R.id.bookmark:
                String bookmarkUrl = getBookmarkUrl();
                if (BookmarksProvider.hasBookmarked(this, bookmarkUrl)) {
                    BookmarksProvider.removeBookmark(this, bookmarkUrl);
                } else {
                    BookmarksProvider.saveBookmark(this, mActionBar.getTitle().toString(),
                            BookmarksProvider.Columns.TYPE_REPO, bookmarkUrl, getCurrentRef(), true);
                }
                return true;
            case R.id.zip_download: {
                final String zipUrl = Uri.parse(mRepository.htmlUrl())
                        .buildUpon()
                        .appendPath("-")
                        .appendPath("archive")
                        .appendPath(getCurrentRef())
                        .appendPath(mRepoName + "-" + getCurrentRef() + ".zip")
                        .toString();
                DownloadUtils.enqueueDownloadWithPermissionCheck(this, zipUrl, "application/zip",
                        mRepoName + "-" + getCurrentRef() + ".zip", null);
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    private void showRefSelectionDialog() {
        BranchSelectionDialogFragment.newInstance(
                mBranches != null ? mBranches : new ArrayList<>(),
                mTags != null ? mTags : new ArrayList<>(),
                mSelectedRef,
                mRepository.defaultBranch())
                .show(getSupportFragmentManager(), "branchselection");
    }

    private void setSelectedRef(String selectedRef) {
        mSelectedRef = selectedRef;
        clearRefDependentFragments();
        updateTitle();
    }

    private void clearRefDependentFragments() {
        if (mRepositoryFragment != null) {
            mRepositoryFragment.setRef(mSelectedRef);
        }
        if (mContentListFragment != null) {
            mContentListFragment.setRef(mSelectedRef);
        }
        mCommitListFragment = null;
    }

    private void loadRepository() {
        // We always skip the cache in this case, since the project endpoint may change open issues count etc.
        boolean skipCache = true;
        GitLabProjectService service = ServiceFactory.get(GitLabProjectService.class, skipCache);
        service.getProjectByPath(URLEncoder.encode(mRepoOwner + "/" + mRepoName, StandardCharsets.UTF_8))
                .map(ApiHelpers::throwOnFailure)
                .compose(makeLoaderSingle(ID_LOADER_REPO, skipCache))
                .subscribe(result -> {
                    mRepository = result;
                    updateTitle();
                    invalidateTabs();
                    // Apply initial page selection first time the repo is loaded
                    if (mInitialPage >= PAGE_REPO_OVERVIEW && mInitialPage <= PAGE_ACTIVITY) {
                        getPager().setCurrentItem(mInitialPage);
                        mInitialPage = -1;
                    }
                    setContentShown(true);
                    supportInvalidateOptionsMenu();
                }, this::handleLoadFailure);
    }

    private void loadOrShowRefSelection() {
        if (mBranches != null) {
            showRefSelectionDialog();
        } else {
            GitLabProjectService projectService =
                    ServiceFactory.getForFullPagedLists(GitLabProjectService.class, false);
            long projectId = mRepository.id();

            Single<List<GitLabBranch>> branchSingle = projectService.getBranches(projectId, 1, 100)
                    .<List<GitLabBranch>>map(ApiHelpers::throwOnFailure)
                    .subscribeOn(Schedulers.io());
            // GitLab tags use GitLabTag, convert to GitLabBranch-compatible wrapper list
            Single<List<GitLabBranch>> tagSingle = projectService.getTags(projectId, 1, 100)
                    .<List<GitLabTag>>map(ApiHelpers::throwOnFailure)
                    .map(tags -> {
                        List<GitLabBranch> branchLike = new ArrayList<>();
                        for (GitLabTag t : tags) {
                            GitLabBranch b = new GitLabBranch();
                            b.name = t.name;
                            b.commit = t.commit;
                            branchLike.add(b);
                        }
                        return branchLike;
                    })
                    .subscribeOn(Schedulers.io());

            registerTemporarySubscription(Single.zip(branchSingle, tagSingle, Pair::create)
                    .compose(RxUtils::doInBackground)
                    .compose(RxUtils.wrapWithProgressDialog(this, R.string.loading_msg))
                    .subscribe(result -> {
                        mBranches = result.first;
                        mTags = result.second;
                        showRefSelectionDialog();
                    }, this::handleLoadFailure));
        }
    }

    public static class BranchSelectionDialogFragment extends DialogFragment {
        public static BranchSelectionDialogFragment newInstance(List<GitLabBranch> branches,
                List<GitLabBranch> tags, String selectedRef, String defaultBranch) {
            Bundle args = new Bundle();
            // Serialise as name strings since GitLabBranch is not Parcelable
            ArrayList<String> branchNames = new ArrayList<>();
            for (GitLabBranch b : branches) branchNames.add(b.name());
            ArrayList<String> tagNames = new ArrayList<>();
            for (GitLabBranch t : tags) tagNames.add(t.name());
            args.putStringArrayList("branches", branchNames);
            args.putStringArrayList("tags", tagNames);
            args.putString("selectedRef", selectedRef);
            args.putString("defaultBranch", defaultBranch);

            BranchSelectionDialogFragment f = new BranchSelectionDialogFragment();
            f.setArguments(args);
            return f;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            Bundle args = getArguments();
            List<String> branches = args.getStringArrayList("branches");
            List<String> tags = args.getStringArrayList("tags");
            String selectedRef = args.getString("selectedRef");
            String defaultBranch = args.getString("defaultBranch");

            final BranchAndTagAdapter adapter = new BranchAndTagAdapter(getContext(),
                    branches, tags);
            int current = -1, master = -1, count = adapter.getCount();

            for (int i = 0; i < count; i++) {
                String itemName = adapter.getItem(i);
                if (itemName.equals(selectedRef)) {
                    current = i;
                }
                if (itemName.equals(defaultBranch)) {
                    master = i;
                }
            }
            if (selectedRef == null && current == -1) {
                current = master;
            }

            final RepositoryActivity activity = (RepositoryActivity) getActivity();
            return new AlertDialog.Builder(activity)
                    .setCancelable(true)
                    .setTitle(R.string.repo_select_ref_dialog_title)
                    .setSingleChoiceItems(adapter, current, (dialog, which) -> {
                        activity.setSelectedRef(adapter.getItem(which));
                        dialog.dismiss();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .create();
        }
    }

    private static class BranchAndTagAdapter extends BaseAdapter {
        private final ArrayList<String> mItems;
        private final LayoutInflater mInflater;
        private final int mFirstTagIndex;

        public BranchAndTagAdapter(Context context, List<String> branches, List<String> tags) {
            mItems = new ArrayList<>();
            mItems.addAll(branches);
            mItems.addAll(tags);
            mFirstTagIndex = branches.size();
            mInflater = LayoutInflater.from(context);
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public String getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.row_branch, parent, false);
            }
            ImageView icon = convertView.findViewById(R.id.icon);
            TextView title = convertView.findViewById(R.id.title);

            icon.setImageResource(position >= mFirstTagIndex ? R.drawable.tag : R.drawable.branch);
            title.setText(mItems.get(position));

            return convertView;
        }
    }
}
