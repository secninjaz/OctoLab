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
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.gl4a.R;
import com.gl4a.fragment.SearchFragment;

public class SearchActivity extends FragmentContainerActivity {
    public static final int SEARCH_TYPE_REPO = SearchFragment.SEARCH_TYPE_REPO;
    public static final int SEARCH_TYPE_USER = SearchFragment.SEARCH_TYPE_USER;
    public static final int SEARCH_TYPE_CODE = SearchFragment.SEARCH_TYPE_CODE;
    public static final int SEARCH_TYPE_ALL = SearchFragment.SEARCH_TYPE_ALL;

    public static Intent makeIntent(Context context, String initialSearch,
            int searchType, boolean startSearchImmediately) {
        return makeIntent(context)
                .putExtra("initial_search", initialSearch)
                .putExtra("search_type", searchType)
                .putExtra("search_immediately", startSearchImmediately);
    }

    /** Searching one project's code on a branch (#295). */
    public static Intent makeProjectCodeIntent(Context context, long projectId,
            String projectName, String ref) {
        return makeIntent(context, null, SEARCH_TYPE_CODE, false)
                .putExtra("project_id", projectId)
                .putExtra("project_name", projectName)
                .putExtra("ref", ref);
    }

    public static Intent makeIntent(Context context) {
        return new Intent(context, SearchActivity.class);
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return getString(R.string.search);
    }

    @Override
    protected Fragment onCreateFragment() {
        Intent intent = getIntent();
        // Everything by default, like GitLab web (#262)
        int searchType = intent.getIntExtra("search_type", SEARCH_TYPE_ALL);
        String initialQuery = intent.getStringExtra("initial_search");
        boolean startSearchImmediately = intent.getBooleanExtra("search_immediately", true);

        SearchFragment fragment =
                SearchFragment.newInstance(searchType, initialQuery, startSearchImmediately);
        long projectId = intent.getLongExtra("project_id", 0);
        if (projectId > 0) {
            fragment.getArguments().putLong("project_id", projectId);
            fragment.getArguments().putString("project_name",
                    intent.getStringExtra("project_name"));
            fragment.getArguments().putString("ref", intent.getStringExtra("ref"));
        }
        return fragment;
    }
}
