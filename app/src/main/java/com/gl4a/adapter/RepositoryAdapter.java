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
package com.gl4a.adapter;

import android.content.Context;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filterable;
import android.widget.TextView;

import android.widget.ImageView;

import com.gl4a.R;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.StringUtils;
import com.vdurmont.emoji.EmojiParser;

import java.util.Locale;

public class RepositoryAdapter extends RootAdapter<GitLabProject, RepositoryAdapter.ViewHolder>
        implements Filterable {
    /** Whose role a row's badge shows, if any (#233). */
    public interface RoleSource {
        /** The role in words ("Maintainer"), or null for no badge. */
        String roleLabel(Context context, GitLabProject project);
    }

    private RoleSource mRoleSource;

    public RepositoryAdapter(Context context) {
        super(context);
    }

    /** Turns on role badges; lists without a source show none. */
    public void setRoleSource(RoleSource source) {
        mRoleSource = source;
        notifyDataSetChanged();
    }

    /** The viewer's own role, from the same memberships as the Groups tree. */
    public static RoleSource viewerRoles(com.gl4a.utils.MembershipRoles roles) {
        return (context, project) -> roles.labelFor(context, project.pathWithNamespace);
    }

    /** Shows the viewer's roles once they're loaded (cached across lists for a while). */
    public void showViewerRoles() {
        com.gl4a.utils.MembershipRoles.loadCached()
                .compose(com.gl4a.utils.RxUtils::doInBackground)
                .subscribe(roles -> setRoleSource(viewerRoles(roles)), error -> { });
    }

    /** Another user's role, by project ID, as loaded by UserProjectRoles (role names). */
    public static RoleSource userRoles(java.util.Map<Long, String> rolesById) {
        return (context, project) -> com.gl4a.utils.MembershipRoles.labelForName(context,
                rolesById.get(project.id));
    }

    /** A project row outside a list (profile previews), the same as the list rows. */
    public static View inflateRow(LayoutInflater inflater, ViewGroup parent) {
        View v = inflater.inflate(R.layout.row_repo, parent, false);
        v.setTag(R.id.tv_title, new ViewHolder(v));
        return v;
    }

    public static void bindRow(Context context, View row, GitLabProject project, String role) {
        bind(context, (ViewHolder) row.getTag(R.id.tv_title), project, role);
    }

    @Override
    public ViewHolder onCreateViewHolder(LayoutInflater inflater, ViewGroup parent, int viewType) {
        View v = inflater.inflate(R.layout.row_repo, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, GitLabProject repository) {
        bind(mContext, holder, repository,
                mRoleSource != null ? mRoleSource.roleLabel(mContext, repository) : null);
    }

    private static void bind(Context context, ViewHolder holder, GitLabProject repository,
            String role) {
        // Shared with every other project logo in the app — see AvatarHandler.assignProjectLogo.
        AvatarHandler.assignProjectLogo(holder.ivAvatar, repository);
        holder.tvTitle.setText(ApiHelpers.formatRepoName(context, repository));
        holder.tvRole.setVisibility(role != null ? View.VISIBLE : View.GONE);
        holder.tvRole.setText(role);

        if (!StringUtils.isBlank(repository.description())) {
            holder.tvDesc.setVisibility(View.VISIBLE);
            holder.tvDesc.setText(EmojiParser.parseToUnicode(repository.description()));
        } else {
            holder.tvDesc.setVisibility(View.GONE);
        }

        if (repository.language() != null) {
            holder.tvLanguage.setVisibility(android.view.View.VISIBLE);
            holder.tvLanguage.setText(repository.language());
        } else {
            holder.tvLanguage.setVisibility(android.view.View.GONE);
        }
        holder.tvForks.setText(String.valueOf(repository.forksCount()));
        holder.tvStars.setText(String.valueOf(repository.stargazersCount()));
        // GitLab projects do not expose repository size via API; hide the field
        holder.tvSize.setVisibility(View.GONE);
        if ("internal".equals(repository.visibility)) {
            holder.tvPrivate.setVisibility(View.VISIBLE);
            holder.tvPrivate.setText(R.string.repo_type_internal);
            // Same icons as GitLab: shield for internal, globe for public, lock for private.
            holder.tvPrivate.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.internal_small, 0, 0, 0);
        } else if ("public".equals(repository.visibility)) {
            holder.tvPrivate.setVisibility(View.VISIBLE);
            holder.tvPrivate.setText(R.string.repo_type_public);
            holder.tvPrivate.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.public_small, 0, 0, 0);
        } else {
            // "private" or null — show Private label with its lock icon (existing behaviour)
            holder.tvPrivate.setVisibility(repository.isPrivate() ? View.VISIBLE : View.GONE);
            holder.tvPrivate.setText(R.string.repo_type_private);
            holder.tvPrivate.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.private_small, 0, 0, 0);
        }
        holder.tvFork.setVisibility(repository.isFork() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected boolean isFiltered(CharSequence filter, GitLabProject repo) {
        String lcFilter = filter.toString().toLowerCase(Locale.getDefault());
        String name = repo.name().toLowerCase(Locale.getDefault());
        return name.contains(lcFilter);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener,
            View.OnTouchListener {
        private ViewHolder(View view) {
            super(view);
            ivAvatar = view.findViewById(R.id.iv_avatar);
            tvTitle = view.findViewById(R.id.tv_title);
            tvDesc = view.findViewById(R.id.tv_desc);
            tvLanguage = view.findViewById(R.id.tv_language);
            tvForks = view.findViewById(R.id.tv_forks);
            tvStars = view.findViewById(R.id.tv_stars);
            tvSize = view.findViewById(R.id.tv_size);
            tvPrivate = view.findViewById(R.id.tv_private);
            tvFork = view.findViewById(R.id.tv_fork);
            tvRole = view.findViewById(R.id.tv_role);

            view.findViewById(R.id.attributes).setOnClickListener(this);
            view.findViewById(R.id.scrollView).setOnTouchListener(this);
        }

        private final ImageView ivAvatar;
        private final TextView tvTitle;
        private final TextView tvDesc;
        private final TextView tvLanguage;
        private final TextView tvForks;
        private final TextView tvStars;
        private final TextView tvSize;
        private final TextView tvPrivate;
        private final TextView tvFork;
        private final TextView tvRole;

        @Override
        public void onClick(View v) {
            // Workaround to make it possible to open repositories when clicking inside of
            // attributes ScrollView
            itemView.performClick();
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            return false;
        }
    }
}
