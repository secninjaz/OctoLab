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
import com.gl4a.gitlab.model.GitLabIssue;

import android.content.Context;

import com.gl4a.R;

public class RepositoryIssueAdapter extends IssueAdapter {
    // Rows from here don't say where they're from (a group's own epics, #312)
    private String mHomePath;

    public RepositoryIssueAdapter(Context context) {
        super(context);
    }

    public void setHomePath(String path) {
        mHomePath = path;
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, GitLabIssue issue) {
        super.onBindViewHolder(holder, issue);

        // GitLab webUrl: https://host/namespace/project/-/issues/{iid}
        //                https://host/namespace/project/-/merge_requests/{iid}
        //                https://host/groups/group/subgroup/-/epics/{iid}
        String repoDisplay = "";
        if (issue.isEpic()) {
            String group = issue.groupPath();
            if (group != null) repoDisplay = group;
        } else if (issue.webUrl != null) {
            try {
                java.util.List<String> segs =
                        android.net.Uri.parse(issue.webUrl).getPathSegments();
                // segs: [namespace, project, -, issues/merge_requests, iid]
                if (segs.size() >= 2) repoDisplay = segs.get(0) + "/" + segs.get(1);
            } catch (Exception ignored) {}
        }
        if (repoDisplay.equals(mHomePath)) repoDisplay = "";
        android.text.SpannableStringBuilder number = new android.text.SpannableStringBuilder(
                issue.referencePrefix() + issue.number()
                + (repoDisplay.isEmpty() ? "" : " on " + repoDisplay));
        if (issue.isEpic()) {
            // An epic's dates on its number's line, as GitLab web lists them (#312): the
            // milestone's slot on the right is too narrow for a range
            String dates = com.gl4a.utils.StringUtils.formatShortDateRange(mContext,
                    issue.startDate, issue.dueDate);
            if (dates != null) {
                number.append(" \u00b7 ");
                android.graphics.drawable.Drawable clock =
                        androidx.core.content.ContextCompat.getDrawable(mContext,
                                R.drawable.due_small);
                if (clock != null) {
                    int at = number.length();
                    number.append("\u2009 ");
                    number.setSpan(new com.gl4a.widget.CenteredIconSpan(clock), at, at + 1, 0);
                }
                number.append(dates);
            }
            // One line; a long one loses its end, never the number
            holder.tvNumber.setMaxLines(1);
            holder.tvNumber.setEllipsize(android.text.TextUtils.TruncateAt.END);
        } else {
            holder.tvNumber.setMaxLines(Integer.MAX_VALUE);
            holder.tvNumber.setEllipsize(null);
        }
        holder.tvNumber.setText(number);
    }
}
