package com.gl4a.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.gl4a.R;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.UserMemberships;
import com.gl4a.widget.GroupTree;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Single;
import io.reactivex.disposables.Disposable;

/**
 * Everything a user is a member of, as the Groups screen's tree with every group already
 * expanded (subgroups a few levels deep), so all their projects show under their groups,
 * followed by their direct projects elsewhere; all with their roles (#240). Admins only.
 */
public class MemberOfTreeFragment extends Fragment {
    /** Groups this deep and above open by themselves */
    private static final int EXPAND_DEPTH = 3;

    public static MemberOfTreeFragment newInstance(long userId, String login) {
        MemberOfTreeFragment f = new MemberOfTreeFragment();
        Bundle args = new Bundle();
        args.putLong("user_id", userId);
        args.putString("login", login);
        f.setArguments(args);
        return f;
    }

    private GroupTree.State mState;
    private GroupTree mTree;
    private NestedScrollView mScroll;
    private Disposable mLoad;

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
        // Survives rotation, like the Groups screen (#172)
        mState = new ViewModelProvider(this).get(GroupTree.State.class);
        mTree = new GroupTree(requireActivity(), view.findViewById(R.id.ll_tree), mState);
        if (mState.hasContent()) {
            view.findViewById(R.id.progress).setVisibility(View.GONE);
            mTree.restore();
            final int scrollY = mState.scrollY;
            mScroll.post(() -> mScroll.scrollTo(0, scrollY));
            return;
        }
        long userId = requireArguments().getLong("user_id");
        String login = requireArguments().getString("login");
        mLoad = Single.fromCallable(() -> UserMemberships.load(userId, login, false))
                .compose(RxUtils::doInBackground)
                .subscribe(memberships -> {
                    if (mTree == null) return;
                    view.findViewById(R.id.progress).setVisibility(View.GONE);
                    List<Object> roots = new ArrayList<>(memberships.groups);
                    roots.addAll(memberships.otherProjects);
                    if (roots.isEmpty()) {
                        showMessage(view, R.string.user_no_memberships);
                    } else {
                        mTree.setRoots(roots, memberships.roles, EXPAND_DEPTH);
                    }
                }, error -> {
                    if (mTree == null) return;
                    view.findViewById(R.id.progress).setVisibility(View.GONE);
                    showMessage(view, R.string.load_failure_explanation);
                });
    }

    /** The layout's own empty/error text, as the Groups screen uses it. */
    private void showMessage(View view, int textRes) {
        TextView empty = view.findViewById(R.id.tv_empty);
        empty.setText(textRes);
        empty.setVisibility(View.VISIBLE);
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
}
