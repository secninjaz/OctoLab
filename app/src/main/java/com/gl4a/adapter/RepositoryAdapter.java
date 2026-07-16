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

import com.gl4a.R;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.utils.ApiHelpers;
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
        holder.tvPrivate.setVisibility(repository.isPrivate() ? View.VISIBLE : View.GONE);
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
