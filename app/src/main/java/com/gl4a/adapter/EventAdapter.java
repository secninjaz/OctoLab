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
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.RecyclerView;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.gl4a.R;
import com.gl4a.activities.UserActivity;
import com.gl4a.gitlab.model.GitLabEvent;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.StringUtils;

public class EventAdapter extends RootAdapter<GitLabEvent, EventAdapter.EventViewHolder> {

    private java.util.Map<Long, String> mProjectPaths = new java.util.HashMap<>();

    public EventAdapter(Context context) {
        super(context);
    }

    public void setProjectPaths(java.util.Map<Long, String> paths) {
        mProjectPaths = paths != null ? paths : new java.util.HashMap<>();
        notifyDataSetChanged();
    }

    @Override
    public EventViewHolder onCreateViewHolder(LayoutInflater inflater, ViewGroup parent,
            int viewType) {
        View v = inflater.inflate(R.layout.row_event, parent, false);
        EventViewHolder holder = new EventViewHolder(v);
        holder.ivGravatar.setOnClickListener(this);
        return holder;
    }

    @Override
    public void onBindViewHolder(EventViewHolder holder, GitLabEvent event) {
        GitLabUser actor = event.actor();

        AvatarHandler.assignAvatar(holder.ivGravatar, actor);
        holder.ivGravatar.setTag(actor);

        holder.tvActor.setText(ApiHelpers.getUserLoginWithType(mContext, actor));

        SpannableStringBuilder title = StringUtils.applyBoldTags(formatTitle(event));
        holder.tvTitle.setText(title);

        holder.tvCreatedAt.setText(StringUtils.formatRelativeTime(
                mContext, event.createdAt(), false));

        CharSequence content = formatDescription(event);
        holder.tvDesc.setText(content);
        holder.tvDesc.setVisibility(content != null ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.iv_gravatar) {
            GitLabUser actor = (GitLabUser) v.getTag();
            Intent intent = UserActivity.makeIntent(mContext, actor);
            if (intent != null) {
                mContext.startActivity(intent);
            }
        } else {
            super.onClick(v);
        }
    }

    @Override
    public boolean isCardStyle() {
        return true;
    }

    /**
     * Returns a human-readable title for the event, matching GitLab web's activity feed format.
     * GitLab events carry action_name + target_type; push events carry a push_data sub-object.
     */
    private String formatTitle(GitLabEvent event) {
        String action = event.actionName != null ? event.actionName : "";
        String targetType = event.targetType;
        // For push events target_title is the project name; for others it is the noteable title.
        String projectOrTitle = !TextUtils.isEmpty(event.targetTitle) ? event.targetTitle : "";
        // Resolved project path (e.g. "testg/octolab"), may be null if not yet loaded.
        String projectPath = mProjectPaths.get(event.projectId);

        if (event.pushData != null) {
            GitLabEvent.PushData pd = event.pushData;
            String ref = pd.ref != null ? pd.ref : "";
            // For push events targetTitle IS the project name — use path if available, else title.
            String proj = projectPath != null ? projectPath : projectOrTitle;
            if ("tag".equalsIgnoreCase(pd.refType)) {
                return "deleted".equals(pd.action)
                        ? mContext.getString(R.string.event_delete_tag_title, ref, proj)
                        : mContext.getString(R.string.event_create_tag_title, ref, proj);
            }
            if ("deleted".equals(pd.action)) {
                return mContext.getString(R.string.event_delete_branch_title, ref, proj);
            }
            if ("created".equals(pd.action)) {
                return mContext.getString(R.string.event_create_branch_title, ref, proj);
            }
            return mContext.getString(R.string.event_push_title, ref, proj);
        }

        if (targetType == null) {
            return toSentenceCase(action);
        }

        int iid = event.targetIid != null ? event.targetIid.intValue() : 0;

        switch (targetType) {
            case "Issue":
                switch (action) {
                    case "closed":   return mContext.getString(R.string.event_issues_close_title, iid, projectOrTitle);
                    case "reopened": return mContext.getString(R.string.event_issues_reopen_title, iid, projectOrTitle);
                    default:         return mContext.getString(R.string.event_issues_open_title,  iid, projectOrTitle);
                }
            case "MergeRequest":
                switch (action) {
                    case "closed":   return mContext.getString(R.string.event_pr_close_title,  iid, projectOrTitle);
                    case "merged":   return mContext.getString(R.string.event_pr_merge_title,  iid, projectOrTitle);
                    case "reopened": return mContext.getString(R.string.event_pr_reopen_title, iid, projectOrTitle);
                    default:         return mContext.getString(R.string.event_pr_open_title,   iid, projectOrTitle);
                }
            case "Note":
            case "DiscussionNote":
                return formatNoteTitle(action, event, projectPath);
            default:
                return toSentenceCase(action) + " " + targetType;
        }
    }

    private String formatNoteTitle(String action, GitLabEvent event, String projectPath) {
        com.gl4a.gitlab.model.GitLabComment note = event.note;
        if (note != null && note.noteableType != null && note.noteableIid > 0) {
            // Determine the noteable type label ("issue", "merge request", "snippet", etc.)
            String type;
            switch (note.noteableType) {
                case "Issue":        type = "issue";         break;
                case "MergeRequest": type = "merge request"; break;
                case "Snippet":      type = "snippet";       break;
                default:             type = note.noteableType.toLowerCase(); break;
            }
            // Build: "Commented on issue #33 – Issue title at testg/octolab"
            StringBuilder sb = new StringBuilder(toSentenceCase(action))
                    .append(" [b]").append(type).append(" #").append(note.noteableIid).append("[/b]");
            if (!TextUtils.isEmpty(event.targetTitle)) {
                sb.append(" – ").append(event.targetTitle);
            }
            if (projectPath != null) {
                sb.append(" at [b]").append(projectPath).append("[/b]");
            }
            return sb.toString();
        }
        // Fallback: no note detail available
        return TextUtils.isEmpty(event.targetTitle)
                ? toSentenceCase(action) + " a comment"
                : toSentenceCase(action) + ": " + event.targetTitle;
    }

    /**
     * Returns optional descriptive text shown below the title.
     */
    private CharSequence formatDescription(GitLabEvent event) {
        if (event.pushData != null) {
            GitLabEvent.PushData pd = event.pushData;
            if (pd.commitTitle != null) {
                return pd.commitTitle;
            }
            return null;
        }

        if (event.note != null && !TextUtils.isEmpty(event.note.body)) {
            return event.note.body;
        }

        if (!TextUtils.isEmpty(event.targetTitle)) {
            return event.targetTitle;
        }

        return null;
    }

    private static String toSentenceCase(String s) {
        if (TextUtils.isEmpty(s)) return "";
        String spaced = s.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /**
     * The Class EventViewHolder.
     */
    public static class EventViewHolder extends RecyclerView.ViewHolder {
        private EventViewHolder(View view) {
            super(view);
            ivGravatar = view.findViewById(R.id.iv_gravatar);
            tvActor = view.findViewById(R.id.tv_actor);
            tvTitle = view.findViewById(R.id.tv_title);
            tvDesc = view.findViewById(R.id.tv_desc);
            tvCreatedAt = view.findViewById(R.id.tv_created_at);
        }

        private final ImageView ivGravatar;
        private final TextView tvActor;
        private final TextView tvTitle;
        private final TextView tvDesc;
        private final TextView tvCreatedAt;
    }
}
