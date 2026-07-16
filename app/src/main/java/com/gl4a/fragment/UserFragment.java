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
package com.gl4a.fragment;

import android.content.Intent;
import android.os.Bundle;
import androidx.core.content.ContextCompat;

import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.GistListActivity;
import com.gl4a.activities.OrganizationMemberListActivity;
import com.gl4a.activities.RepositoryActivity;
import com.gl4a.activities.RepositoryListActivity;
import com.gl4a.activities.UserActivity;
import com.gl4a.gitlab.model.GitLabGroup;
import com.gl4a.gitlab.model.GitLabPage;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.service.GitLabGroupService;
import com.gl4a.gitlab.service.GitLabProjectService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.StringUtils;
import com.gl4a.widget.OverviewRow;
import com.vdurmont.emoji.EmojiParser;

import java.util.Collection;
import java.util.List;

import io.reactivex.Single;
import retrofit2.Response;

public class UserFragment extends LoadingFragmentBase implements
        OverviewRow.OnIconClickListener, View.OnClickListener {
    public static UserFragment newInstance(GitLabUser user) {
        UserFragment f = new UserFragment();

        Bundle args = new Bundle();
        args.putParcelable("user", user);
        f.setArguments(args);

        return f;
    }

    private static final int ID_LOADER_REPO_LIST = 0;
    private static final int ID_LOADER_ORG_LIST = 1;
    private static final int ID_LOADER_ORG_MEMBER_COUNT = 2;

    private GitLabUser mUser;
    private View mContentView;
    // GitLab has no follower/following concept; suppress the row
    private boolean mIsSelf;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUser = getArguments().getParcelable("user");
        mIsSelf = ApiHelpers.loginEquals(mUser, Gl4Application.get().getAuthLogin());
        setHasOptionsMenu(true);
    }

    @Override
    protected View onCreateContentView(LayoutInflater inflater, ViewGroup parent) {
        mContentView = inflater.inflate(R.layout.user, parent, false);
        return mContentView;
    }

    @Override
    public void onRefresh() {
        loadTopRepositories(true);
        loadGroupsIfUser(true);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        fillData();
        loadTopRepositories(false);
        loadGroupsIfUser(false);

        setContentShown(true);
    }

    private void fillData() {
        ImageView gravatar = mContentView.findViewById(R.id.iv_gravatar);
        AvatarHandler.assignAvatar(gravatar, mUser);

        OverviewRow joinDateRow = mContentView.findViewById(R.id.join_date_row);
        if (mUser.createdAt != null) {
            joinDateRow.setText(getString(R.string.user_created_at, mUser.createdAt));
            joinDateRow.setVisibility(View.VISIBLE);
        } else {
            joinDateRow.setVisibility(View.GONE);
        }

        // GitLab has no followers/following concept; hide those rows
        OverviewRow followersRow = mContentView.findViewById(R.id.followers_row);
        OverviewRow followingRow = mContentView.findViewById(R.id.following_row);
        followersRow.setVisibility(View.GONE);
        followingRow.setVisibility(View.GONE);

        OverviewRow membersRow = mContentView.findViewById(R.id.members_row);
        // Show members row for all users; GitLab groups are handled in the group section
        membersRow.setVisibility(View.GONE);

        // GitLab snippets instead of GitHub gists
        OverviewRow gistsRow = mContentView.findViewById(R.id.gists_row);
        gistsRow.setVisibility(View.VISIBLE);
        gistsRow.setText(getString(R.string.my_gists));
        gistsRow.setClickIntent(GistListActivity.makeIntent(getActivity(), mUser.login()));

        OverviewRow reposRow = mContentView.findViewById(R.id.repos_row);
        int repoCount = orZero(mUser.publicRepos());
        reposRow.setText(getResources().getQuantityString(R.plurals.repository, repoCount, repoCount));
        reposRow.setClickIntent(RepositoryListActivity.makeIntent(getActivity(), mUser.login(), false));

        // GitLab does not expose a "type" field with bot/org distinction on the user model
        OverviewRow typeRow = mContentView.findViewById(R.id.type_row);
        typeRow.setVisibility(View.GONE);

        TextView tvName = mContentView.findViewById(R.id.tv_name);
        if (StringUtils.isBlank(mUser.name())) {
            tvName.setText(ApiHelpers.getUserLogin(getActivity(), mUser));
        } else {
            tvName.setText(mUser.name());
        }

        fillTextView(R.id.tv_email, mUser.email());
        fillTextView(R.id.tv_website, mUser.blog());
        fillTextView(R.id.tv_company, mUser.company());
        fillTextView(R.id.tv_location, mUser.location());
        fillTextView(R.id.tv_bio, mUser.bio() != null ? EmojiParser.parseToUnicode(mUser.bio()) : null);
    }

    private static int orZero(Integer count) {
        return count != null ? count : 0;
    }

    private void fillTextView(int id, String text) {
        TextView view = mContentView.findViewById(id);
        if (!StringUtils.isBlank(text)) {
            view.setText(text);
            view.setVisibility(View.VISIBLE);
        } else {
            view.setVisibility(View.GONE);
        }
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        Intent intent = null;

        if (id == R.id.btn_repos) {
            intent = RepositoryListActivity.makeIntent(getActivity(), mUser.login(), false);
        } else if (view.getTag() instanceof GitLabProject) {
            intent = RepositoryActivity.makeIntent(getActivity(), (GitLabProject) view.getTag());
        } else if (view.getTag() instanceof GitLabGroup) {
            // Fix: groups are not users; navigate to the group's repository list instead of
            // UserActivity (which would call /users?username=<path> and crash on a 0-result list).
            GitLabGroup group = (GitLabGroup) view.getTag();
            intent = RepositoryListActivity.makeIntent(getActivity(), group.path, false);
        } else if (view.getTag() instanceof GitLabUser) {
            intent = UserActivity.makeIntent(getActivity(), (GitLabUser) view.getTag());
        }
        if (intent != null) {
            startActivity(intent);
        }
    }

    @Override
    public void onIconClick(OverviewRow row) {
        // No follow toggle in GitLab
    }

    private void fillTopRepos(Collection<GitLabProject> topRepos) {
        View progress = mContentView.findViewById(R.id.pb_top_repos);
        LinearLayout ll = mContentView.findViewById(R.id.ll_top_repos);
        ll.removeAllViews();

        LayoutInflater inflater = getLayoutInflater();

        if (topRepos != null) {
            for (GitLabProject repo : topRepos) {
                View rowView = inflater.inflate(R.layout.top_repo, null);
                rowView.setOnClickListener(this);
                rowView.setTag(repo);

                TextView tvTitle = rowView.findViewById(R.id.tv_title);
                tvTitle.setText(ApiHelpers.formatRepoName(getActivity(), repo));

                TextView tvDesc = rowView.findViewById(R.id.tv_desc);
                if (!StringUtils.isBlank(repo.description())) {
                    tvDesc.setVisibility(View.VISIBLE);
                    tvDesc.setText(EmojiParser.parseToUnicode(repo.description()));
                } else {
                    tvDesc.setVisibility(View.GONE);
                }

                TextView tvForks = rowView.findViewById(R.id.tv_forks);
                tvForks.setText(String.valueOf(repo.forksCount()));

                TextView tvStars = rowView.findViewById(R.id.tv_stars);
                tvStars.setText(String.valueOf(repo.stargazersCount()));

                ll.addView(rowView);
            }
        }

        View btnMore = getView().findViewById(R.id.btn_repos);
        if (topRepos != null && !topRepos.isEmpty()) {
            btnMore.setOnClickListener(this);
            btnMore.setVisibility(View.VISIBLE);
        } else {
            TextView hintView = (TextView) inflater.inflate(R.layout.hint_view, ll, false);
            hintView.setText(R.string.user_no_repos);
            ll.addView(hintView);
        }

        ll.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
    }

    private void fillGroups(List<GitLabGroup> groups) {
        ViewGroup llOrgs = mContentView.findViewById(R.id.ll_orgs);
        LinearLayout llOrg = mContentView.findViewById(R.id.ll_org);
        int count = groups != null ? groups.size() : 0;
        LayoutInflater inflater = getLayoutInflater();

        llOrg.removeAllViews();
        llOrgs.setVisibility(count > 0 ? View.VISIBLE : View.GONE);

        for (int i = 0; i < count; i++) {
            GitLabGroup group = groups.get(i);
            View rowView = inflater.inflate(R.layout.selectable_label_with_avatar, llOrg, false);

            // Fix: tag the row with the GitLabGroup object, not a synthetic GitLabUser.
            // onClick() will route GitLabGroup tags to RepositoryListActivity (group projects),
            // avoiding the crash from calling /users?username=<groupPath> on a group namespace.
            rowView.setOnClickListener(this);
            rowView.setTag(group);

            // For avatar display we still need a GitLabUser-compatible object
            GitLabUser groupUser = new GitLabUser();
            groupUser.id = group.id;
            groupUser.username = group.path;
            groupUser.name = group.name;
            groupUser.avatarUrl = group.avatarUrl;

            ImageView avatar = rowView.findViewById(R.id.iv_gravatar);
            AvatarHandler.assignAvatar(avatar, groupUser);

            TextView nameView = rowView.findViewById(R.id.tv_title);
            nameView.setText(group.name);

            llOrg.addView(rowView);
        }
    }

    private void loadTopRepositories(boolean force) {
        GitLabProjectService service = ServiceFactory.get(GitLabProjectService.class, force, 5);
        Single<Response<List<GitLabProject>>> observable;

        if (mIsSelf) {
            observable = service.listProjects(true, 1, 5, "last_activity_at", "desc", null, false);
        } else {
            // Fix: GitLab GET /users/:id/projects requires a numeric user ID, not a username string.
            // Use the GitLabUserService overload that accepts long userId.
            com.gl4a.gitlab.service.GitLabUserService userService = ServiceFactory.get(
                    com.gl4a.gitlab.service.GitLabUserService.class, force);
            observable = userService.getUserProjects(mUser.id, 1, 5);
        }

        observable.map(ApiHelpers::throwOnFailure)
                .compose(makeLoaderSingle(ID_LOADER_REPO_LIST, force))
                .doOnSubscribe(disposable -> {
                    mContentView.findViewById(R.id.pb_top_repos).setVisibility(View.VISIBLE);
                    mContentView.findViewById(R.id.ll_top_repos).setVisibility(View.GONE);
                })
                .subscribe(this::fillTopRepos, this::handleLoadFailure);
    }

    private void loadGroupsIfUser(boolean force) {
        GitLabGroupService service = ServiceFactory.get(GitLabGroupService.class, force);
        // When viewing self: GET /groups?owned=true lists the authenticated user's groups.
        // When viewing another user: GET /users/:id/groups lists that user's groups.
        Single<Response<List<GitLabGroup>>> request = mIsSelf
                ? service.listGroups(true, 1, 10)
                : service.getUserGroups(mUser.id, 1, 10);
        request.map(ApiHelpers::throwOnFailure)
                .compose(makeLoaderSingle(ID_LOADER_ORG_LIST, force))
                .subscribe(this::fillGroups, this::handleLoadFailure);
    }
}
