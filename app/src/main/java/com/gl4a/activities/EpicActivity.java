package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.fragment.app.FragmentManager;

import com.gl4a.BaseActivity;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.fragment.EpicFragment;
import com.gl4a.gitlab.model.GitLabIssue;
import com.gl4a.gitlab.service.GitLabEpicService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.BookmarkActions;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.UiUtils;
import com.gl4a.widget.BottomSheetCompatibleScrollingViewBehavior;
import com.google.android.material.appbar.AppBarLayout;

import java.util.Locale;

/**
 * An epic (#312), with the issue page's header and timeline: its state and title above, its
 * description, child items and comments below. Read and comment; editing is for later.
 */
public class EpicActivity extends BaseActivity {
    /** @param groupPath the epic's group, e.g. "parent/child" */
    @Nullable
    public static Intent makeIntent(Context context, String groupPath, int iid) {
        return makeIntent(context, groupPath, iid, null);
    }

    @Nullable
    public static Intent makeIntent(Context context, String groupPath, int iid,
            IntentUtils.InitialCommentMarker initialComment) {
        if (groupPath == null) return null;
        return new Intent(context, EpicActivity.class)
                .putExtra("group", groupPath)
                .putExtra("number", iid)
                .putExtra("initial_comment", initialComment);
    }

    private static final int ID_LOADER_EPIC = 0;

    private String mGroupPath;
    private int mNumber;
    private IntentUtils.InitialCommentMarker mInitialComment;
    private GitLabIssue mEpic;
    private ViewGroup mHeader;
    private EpicFragment mFragment;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.frame_layout);
        setContentShown(false);

        LayoutInflater inflater =
                LayoutInflater.from(new ContextThemeWrapper(this, R.style.HeaderTheme));
        mHeader = (ViewGroup) inflater.inflate(R.layout.issue_header, null);
        mHeader.setClickable(false);
        mHeader.setVisibility(View.GONE);
        addHeaderView(mHeader, false);

        setToolbarScrollable(true);
        loadEpic(false);
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mGroupPath = extras.getString("group");
        mNumber = extras.getInt("number");
        mInitialComment = extras.getParcelable("initial_comment");
        extras.remove("initial_comment");
    }

    @NonNull
    @Override
    protected String getActionBarTitle() {
        return getString(R.string.epic) + " &" + mNumber;
    }

    @Nullable
    @Override
    protected String getActionBarSubtitle() {
        return mGroupPath;
    }

    @Override
    protected AppBarLayout.ScrollingViewBehavior onCreateSwipeLayoutBehavior() {
        return new BottomSheetCompatibleScrollingViewBehavior();
    }

    @Override
    protected Intent navigateUp() {
        return EpicListActivity.makeIntent(this, mGroupPath);
    }

    private void showUi() {
        FragmentManager fm = getSupportFragmentManager();
        EpicFragment fragment = (EpicFragment) fm.findFragmentById(R.id.details);
        if (fragment == null) {
            fragment = EpicFragment.newInstance(mEpic, mInitialComment);
            fm.beginTransaction().add(R.id.details, fragment).commitAllowingStateLoss();
            mInitialComment = null;
        }
        mFragment = fragment;
        setChildScrollDelegate(fragment);

        TextView tvState = mHeader.findViewById(R.id.tv_state);
        boolean closed = "closed".equals(mEpic.state());
        tvState.setText(getString(closed ? R.string.closed : R.string.open)
                .toUpperCase(Locale.getDefault()));
        tvState.setTextColor(UiUtils.resolveColor(this,
                closed ? R.attr.colorIssueClosed : R.attr.colorIssueOpen));
        ((TextView) mHeader.findViewById(R.id.tv_title)).setText(mEpic.title());
        mHeader.setVisibility(View.VISIBLE);
        setContentShown(true);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.issue_menu, menu);
        // Closing, reopening and subscribing come later (#312 phase 2)
        menu.removeItem(R.id.issue_close);
        menu.removeItem(R.id.issue_reopen);
        menu.removeItem(R.id.subscription);
        if (mEpic == null) {
            menu.removeItem(R.id.browser);
            menu.removeItem(R.id.share);
            menu.removeItem(R.id.copy_number);
        }
        BookmarkActions.prepareMenu(this, menu, bookmarkTarget());
        return super.onCreateOptionsMenu(menu);
    }

    @Nullable
    private BookmarkActions.Target bookmarkTarget() {
        if (mEpic == null || mEpic.htmlUrl() == null) return null;
        return BookmarkActions.Target.epic(mEpic.htmlUrl(), mEpic.title(), mGroupPath, mNumber);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (BookmarkActions.onMenuItemSelected(this, item, bookmarkTarget())) {
            return true;
        }
        int itemId = item.getItemId();
        if (itemId == R.id.share) {
            IntentUtils.share(this, getString(R.string.share_epic_subject, mNumber,
                    mEpic.title(), mGroupPath), Uri.parse(mEpic.htmlUrl()));
            return true;
        } else if (itemId == R.id.browser) {
            IntentUtils.launchBrowser(this, Uri.parse(mEpic.htmlUrl()));
            return true;
        } else if (itemId == R.id.copy_number) {
            IntentUtils.copyToClipboard(this, getString(R.string.epic) + " &" + mNumber,
                    String.valueOf(mNumber));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRefresh() {
        mEpic = null;
        setContentShown(false);
        mHeader.setVisibility(View.GONE);
        if (mFragment != null) {
            getSupportFragmentManager().beginTransaction().remove(mFragment).commit();
            mFragment = null;
            setChildScrollDelegate(null);
        }
        supportInvalidateOptionsMenu();
        loadEpic(true);
        super.onRefresh();
    }

    @Override
    public void onBackPressed() {
        if (mFragment != null && mFragment.onBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    private void loadEpic(boolean force) {
        ServiceFactory.get(GitLabEpicService.class, force)
                .getEpic(Uri.encode(mGroupPath), mNumber)
                .map(ApiHelpers::throwOnFailure)
                .compose(makeLoaderSingle(ID_LOADER_EPIC, force))
                .subscribe(epic -> {
                    mEpic = epic;
                    // Its group's current path, e.g. after a move
                    String current = epic.groupPath();
                    if (current != null && !current.equals(mGroupPath)) {
                        mGroupPath = current;
                        if (getSupportActionBar() != null) {
                            getSupportActionBar().setSubtitle(mGroupPath);
                        }
                    }
                    showUi();
                    supportInvalidateOptionsMenu();
                }, this::handleLoadFailure);
    }

    @Nullable
    @Override
    protected Uri getActivityUri() {
        return mEpic != null && mEpic.htmlUrl() != null ? Uri.parse(mEpic.htmlUrl()) : null;
    }
}
