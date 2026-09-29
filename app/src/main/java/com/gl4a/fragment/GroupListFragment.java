package com.gl4a.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.gl4a.BaseActivity;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabGroup;
import com.gl4a.gitlab.service.GitLabGroupService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.MembershipRoles;
import com.gl4a.utils.RxUtils;
import com.gl4a.widget.GroupTree;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Single;
import io.reactivex.disposables.Disposable;

/**
 * The user's top-level groups as an expandable tree, like GitLab web's "Your groups" (#172).
 * Uses the same GroupTree as the group screen.
 */
public class GroupListFragment extends Fragment implements BaseActivity.RefreshableChild,
        com.gl4a.widget.SwipeRefreshLayout.ChildScrollDelegate {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;

    public static GroupListFragment newInstance() {
        return new GroupListFragment();
    }

    private GroupTree mTree;
    private GroupTree.State mState;
    private View mScroll;
    private Disposable mLoad;

    /** Groups and the viewer's roles, loaded together so badges are always present. */
    private static class Data {
        final List<GitLabGroup> groups;
        final MembershipRoles roles;

        Data(List<GitLabGroup> groups, MembershipRoles roles) {
            this.groups = groups;
            this.roles = roles;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_group_tree, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mScroll = view.findViewById(R.id.scroll);
        // Survives rotation, so the tree keeps its rows, expanded groups and scroll (#172).
        mState = new androidx.lifecycle.ViewModelProvider(this).get(GroupTree.State.class);
        mTree = new GroupTree(requireActivity(), view.findViewById(R.id.ll_tree), mState);
        if (mState.hasContent()) {
            view.findViewById(R.id.progress).setVisibility(View.GONE);
            mTree.restore();
            final int scrollY = mState.scrollY;
            mScroll.post(() -> mScroll.scrollTo(0, scrollY));
        } else {
            load(false);
        }
    }

    @Override
    public boolean canChildScrollUp() {
        // Without this the drawer screen's pull-to-refresh fired whenever the tree was
        // scrolled down and the user dragged back up (#172).
        return mScroll != null && mScroll.canScrollVertically(-1);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mScroll != null && mState != null) mState.scrollY = mScroll.getScrollY();
    }

    @Override
    public void onDestroyView() {
        if (mLoad != null) mLoad.dispose();
        mTree = null;
        mScroll = null;
        super.onDestroyView();
    }

    @Override
    public void onRefresh() {
        if (mState != null) mState.clear();
        load(true);
    }

    private void load(boolean force) {
        View view = getView();
        if (view == null) return;
        view.findViewById(R.id.progress).setVisibility(View.VISIBLE);
        view.findViewById(R.id.tv_empty).setVisibility(View.GONE);
        if (mLoad != null) mLoad.dispose();
        final boolean byActivity = GroupTree.isSortByActivity(requireContext());
        mLoad = Single.fromCallable(() -> loadAllGroups(force, byActivity))
                .zipWith(MembershipRoles.load(), Data::new)
                .compose(RxUtils::doInBackground)
                .subscribe(this::show, error -> {
                    View v = getView();
                    if (v == null) return;
                    v.findViewById(R.id.progress).setVisibility(View.GONE);
                    TextView empty = v.findViewById(R.id.tv_empty);
                    empty.setText(R.string.group_tree_loading_failed);
                    empty.setVisibility(View.VISIBLE);
                });
    }

    /** Reloads after the sort was changed from the menu (#175). */
    public void onSortChanged() {
        if (mState != null) mState.clear();
        load(false);
    }

    private static List<GitLabGroup> loadAllGroups(boolean force, boolean byActivity) {
        GitLabGroupService service = ServiceFactory.get(GitLabGroupService.class, force);
        List<GitLabGroup> groups = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<GitLabGroup> batch = ApiHelpers.throwOnFailure(
                    service.listMemberGroups(page, PAGE_SIZE).blockingGet());
            if (batch == null || batch.isEmpty()) break;
            groups.addAll(batch);
            if (batch.size() < PAGE_SIZE) break;
        }
        // Name order comes from the API; latest activity first on request (#175).
        return byActivity ? GroupTree.sortByActivity(groups, force).blockingGet() : groups;
    }

    private void show(Data data) {
        View view = getView();
        if (view == null || mTree == null) return;
        view.findViewById(R.id.progress).setVisibility(View.GONE);
        TextView empty = view.findViewById(R.id.tv_empty);
        empty.setText(R.string.no_groups_found);
        empty.setVisibility(data.groups.isEmpty() ? View.VISIBLE : View.GONE);
        mTree.setRoots(data.groups, data.roles);
    }
}
