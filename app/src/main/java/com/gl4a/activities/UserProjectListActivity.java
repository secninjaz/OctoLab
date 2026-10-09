package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.gl4a.R;
import com.gl4a.fragment.UserProjectListFragment;

/** The full list behind a profile's "Contributed projects" or "Member of" section (#232). */
public class UserProjectListActivity extends FragmentContainerActivity {
    public static final String TYPE_CONTRIBUTED = "contributed";
    public static final String TYPE_MEMBER_OF = "member_of";

    public static Intent makeIntent(Context context, long userId, String login, String type) {
        return new Intent(context, UserProjectListActivity.class)
                .putExtra("user_id", userId)
                .putExtra("login", login)
                .putExtra("type", type);
    }

    private long mUserId;
    private String mLogin;
    private String mType;

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return getString(TYPE_MEMBER_OF.equals(mType)
                ? R.string.user_member_of : R.string.user_contributed_projects);
    }

    @Nullable
    @Override
    protected String getActionBarSubtitle() {
        return mLogin;
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mUserId = extras.getLong("user_id");
        mLogin = extras.getString("login");
        mType = extras.getString("type");
    }

    @Override
    protected Fragment onCreateFragment() {
        return TYPE_MEMBER_OF.equals(mType)
                ? com.gl4a.fragment.MemberOfTreeFragment.newInstance(mUserId, mLogin)
                : UserProjectListFragment.newInstance(mUserId, mLogin);
    }
}
