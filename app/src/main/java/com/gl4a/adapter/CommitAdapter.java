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
import android.content.Intent;
import android.graphics.Typeface;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.gl4a.R;
import com.gl4a.activities.UserActivity;
import com.gl4a.gitlab.model.GitLabCommit;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.StringUtils;
import com.vdurmont.emoji.EmojiParser;

public class CommitAdapter extends RootAdapter<GitLabCommit, CommitAdapter.ViewHolder> {
    public CommitAdapter(Context context) {
        super(context);
    }

    @Override
    public ViewHolder onCreateViewHolder(LayoutInflater inflater, ViewGroup parent, int viewType) {
        View v = inflater.inflate(R.layout.row_commit, parent, false);
        ViewHolder holder = new ViewHolder(v);
        holder.ivGravatar.setOnClickListener(this);
        return holder;
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, GitLabCommit commit) {
        AvatarHandler.assignAvatar(holder.ivGravatar, commit.author());
        holder.ivGravatar.setTag(commit);

        String message = commit.commit().message();
        if (message == null) message = "";
        int pos = message.indexOf('\n');
        if (pos > 0) {
            message = message.substring(0, pos);
        }
        message = EmojiParser.parseToUnicode(message);

        holder.tvDesc.setText(message);
        String sha = commit.sha();
        holder.tvSha.setText(sha != null && sha.length() >= 10 ? sha.substring(0, 10) : sha);
        holder.ivDescriptionIndicator.setVisibility(pos > 0 ? View.VISIBLE : View.GONE);

        // GitLab commit API does not return a comment count inline; hide the field
        holder.tvComments.setVisibility(View.GONE);

        holder.tvExtra.setText(ApiHelpers.getAuthorName(mContext, commit));
        GitLabCommit.GitLabGitUser gitAuthor = commit.commit().author();
        if (gitAuthor != null && gitAuthor.date() != null) {
            holder.tvTimestamp.setText(
                    StringUtils.formatRelativeTime(mContext, gitAuthor.date(), true));
        } else {
            holder.tvTimestamp.setText("");
        }
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.iv_gravatar) {
            GitLabCommit commit = (GitLabCommit) v.getTag();
            // commit.author() returns the resolved user when available, otherwise a synthetic
            // user with username=email — UserActivity.searchUsers handles both cases.
            Intent intent = UserActivity.makeIntent(mContext, commit.author());
            if (intent != null) {
                mContext.startActivity(intent);
            }
        } else {
            super.onClick(v);
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private ViewHolder(View view) {
            super(view);
            tvSha = view.findViewById(R.id.tv_sha);
            tvSha.setTypeface(Typeface.MONOSPACE);

            tvDesc = view.findViewById(R.id.tv_desc);
            tvExtra = view.findViewById(R.id.tv_extra);
            tvTimestamp = view.findViewById(R.id.tv_timestamp);
            tvComments = view.findViewById(R.id.tv_comments);

            ivGravatar = view.findViewById(R.id.iv_gravatar);
            ivDescriptionIndicator = view.findViewById(R.id.iv_description_indicator);
        }

        private final ImageView ivGravatar;
        private final TextView tvDesc;
        private final TextView tvExtra;
        private final TextView tvTimestamp;
        private final TextView tvSha;
        private final TextView tvComments;
        private final ImageView ivDescriptionIndicator;
    }
}
