package com.gl4a.activities;

import android.os.Bundle;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.gl4a.BaseActivity;
import com.gl4a.R;
import com.gl4a.fragment.LoadingFragmentBase;
import com.gl4a.widget.SwipeRefreshLayout;

public abstract class FragmentContainerActivity extends BaseActivity {
    private Fragment mFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final FragmentManager fm = getSupportFragmentManager();
        if (savedInstanceState == null) {
            mFragment = onCreateFragment();
            fm.beginTransaction().add(R.id.content_container, mFragment).commit();
        } else {
            mFragment = fm.findFragmentById(R.id.content_container);
        }

        if (mFragment instanceof SwipeRefreshLayout.ChildScrollDelegate) {
            setChildScrollDelegate((SwipeRefreshLayout.ChildScrollDelegate) mFragment);
        }
    }

    protected abstract Fragment onCreateFragment();

    protected Fragment getFragment() {
        return mFragment;
    }

    @Override
    public void onRefresh() {
        super.onRefresh();
        if (mFragment instanceof RefreshableChild) {
            ((RefreshableChild) mFragment).onRefresh();
        }
    }

    @Override
    public void onBackPressed() {
        if (mFragment instanceof LoadingFragmentBase
                && ((LoadingFragmentBase) mFragment).onBackPressed()) {
            return;
        }
        super.onBackPressed();
    }
}