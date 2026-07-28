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
    public RepositoryIssueAdapter(Context context) {
        super(context);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, GitLabIssue issue) {
        super.onBindViewHolder(holder, issue);

        // GitLab webUrl: https://host/namespace/project/-/issues/{iid}
        //                https://host/namespace/project/-/merge_requests/{iid}
        boolean isMR = issue.webUrl != null && issue.webUrl.contains("/-/merge_requests/");
        String prefix = isMR ? "!" : "#";
        String repoDisplay = "";
        if (issue.webUrl != null) {
            try {
                java.util.List<String> segs =
                        android.net.Uri.parse(issue.webUrl).getPathSegments();
                // segs: [namespace, project, -, issues/merge_requests, iid]
                if (segs.size() >= 2) repoDisplay = segs.get(0) + "/" + segs.get(1);
            } catch (Exception ignored) {}
        }
        holder.tvNumber.setText(prefix + issue.number()
                + (repoDisplay.isEmpty() ? "" : " on " + repoDisplay));
    }
}
