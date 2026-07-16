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
package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.gl4a.R;
import com.gl4a.fragment.OrganizationMemberListFragment;

/**
 * Displays members of a GitLab group (equivalent to GitHub organization members).
 */
public class OrganizationMemberListActivity extends FragmentContainerActivity {
    public static Intent makeIntent(Context context, String groupPath) {
        return new Intent(context, OrganizationMemberListActivity.class)
                .putExtra("login", groupPath);
    }

    private String mGroupPath;

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return getString(R.string.members);
    }

    @Nullable
    @Override
    protected String getActionBarSubtitle() {
        return mGroupPath;
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mGroupPath = extras.getString("login");
    }

    @Override
    protected Fragment onCreateFragment() {
        return OrganizationMemberListFragment.newInstance(mGroupPath);
    }

    @Override
    protected Intent navigateUp() {
        // Navigate up to the group's repository list, not a user profile.
        // Group paths are not usernames; UserActivity would fail with "User not found".
        return RepositoryListActivity.makeIntent(this, mGroupPath, false);
    }
}
