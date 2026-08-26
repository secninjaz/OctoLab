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
    public RepositoryAdapter(Context context) {
        super(context);
    }

    @Override
    public ViewHolder onCreateViewHolder(LayoutInflater inflater, ViewGroup parent, int viewType) {
        View v = inflater.inflate(R.layout.row_repo, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, GitLabProject repository) {
        // Use avatars already embedded in the project response (no extra API calls):
        // project's own avatar → immediate parent namespace avatar → full hierarchy walk.
        if (repository.avatarUrl != null && !repository.avatarUrl.isEmpty()) {
            AvatarHandler.assignAvatarLogo(holder.ivAvatar, repository.name(),
                    repository.id(), repository.avatarUrl);
        } else if (repository.namespace != null
                && repository.namespace.avatarUrl != null
                && !repository.namespace.avatarUrl.isEmpty()
                && "group".equals(repository.namespace.kind)) {
            // Group /uploads/ avatars return 401 without session cookie.
            // Use the API avatar endpoint which accepts PRIVATE-TOKEN.
            String groupAvatarUrl = com.gl4a.Gl4Application.get().getApiBaseUrl()
                    + "groups/" + repository.namespace.id + "/avatar";
            AvatarHandler.assignAvatarLogo(holder.ivAvatar,
                    repository.namespace.name != null ? repository.namespace.name : repository.name(),
                    repository.namespace.id, groupAvatarUrl);
        } else {
            // No avatar at project or immediate parent level — show project initials.
            // Intentionally capped at 2 levels (project → parent group); deeper ancestor
            // walk was deliberately omitted to keep the repo list fast and predictable.
            // If a future requirement needs grandparent-and-above fallback, re-enable:
            //   AvatarHandler.assignAvatarForProject(holder.ivAvatar,
            //           repository.name(), repository.id());
            // Note: group /uploads/ avatar URLs return 401 without session cookie.
            // The API avatar endpoint (api/v4/groups/:id/avatar) must be used instead —
            // see the fetchProjectAvatarUrl path in AvatarHandler for context.
            holder.ivAvatar.setImageDrawable(
                    new AvatarHandler.DefaultAvatarDrawable(repository.name(), null, true));
        }
        holder.tvTitle.setText(ApiHelpers.formatRepoName(mContext, repository));

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
        } else if ("public".equals(repository.visibility)) {
            holder.tvPrivate.setVisibility(View.VISIBLE);
            holder.tvPrivate.setText(R.string.repo_type_public);
        } else {
            // "private" or null — show Private label (existing behaviour)
            holder.tvPrivate.setVisibility(repository.isPrivate() ? View.VISIBLE : View.GONE);
            holder.tvPrivate.setText(R.string.repo_type_private);
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
