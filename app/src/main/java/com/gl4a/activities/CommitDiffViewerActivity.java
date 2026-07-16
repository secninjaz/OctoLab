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
import android.net.Uri;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.service.GitLabCommitService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.SingleFactory;

import java.util.List;

import io.reactivex.Single;
import retrofit2.Response;

public class CommitDiffViewerActivity extends DiffViewerActivity<GitLabComment> {
    public static Intent makeIntent(Context context, String repoOwner, String repoName,
            String commitSha, String path, String diff, List<GitLabComment> comments,
            int highlightStartLine, int highlightEndLine, boolean highlightIsRight,
            IntentUtils.InitialCommentMarker initialComment) {
        return DiffViewerActivity.fillInIntent(new Intent(context, CommitDiffViewerActivity.class),
                repoOwner, repoName, commitSha, path, diff, comments, -1,
                highlightStartLine, highlightEndLine, highlightIsRight, initialComment);
    }

    @Override
    protected Intent navigateUp() {
        return CommitActivity.makeIntent(this, mRepoOwner, mRepoName, mSha);
    }

    @Override
    protected Uri createUrl(String lineId, long replyId) {
        Uri.Builder builder = IntentUtils.createBaseUriForRepo(mRepoOwner, mRepoName)
                .appendPath("-")
                .appendPath("commit")
                .appendPath(mSha);
        if (replyId > 0L) {
            builder.fragment("note_" + replyId);
        } else {
            builder.fragment("diff-" + ApiHelpers.sha256Of(mPath) + lineId);
        }
        return builder.build();
    }

    @Override
    protected boolean canReply() {
        return false;
    }

    @Override
    protected void openCommentDialog(long id, long replyToId, String line, int position,
            int leftLine, int rightLine, GitLabComment commitComment) {
        String body = commitComment == null ? "" : commitComment.body();
        Intent intent = EditDiffCommentActivity.makeIntent(this, mRepoOwner, mRepoName,
                mSha, mPath, line, leftLine, rightLine, position, id, body);
        mEditLauncher.launch(intent);
    }

    @Override
    public Single<Response<Void>> deleteCommentSingle(long id) {
        // GitLab commit comments are deleted via discussions/notes endpoints;
        // stub returns a completed response since the GitLabCommitService does not expose delete.
        return Single.error(new UnsupportedOperationException("Delete commit comment not yet implemented for GitLab"));
    }

    @Override
    protected Single<List<GitLabComment>> getCommentsSingle(boolean bypassCache) {
        GitLabCommitService service = ServiceFactory.get(GitLabCommitService.class, bypassCache);
        // Resolve project ID via owner/repo — SingleFactory.getProjectId handles this.
        return SingleFactory.getProjectId(mRepoOwner, mRepoName)
                .flatMap(projectId -> service.getCommitComments(projectId, mSha, 1, 100)
                        .map(ApiHelpers::throwOnFailure))
                .compose(RxUtils.<GitLabComment>filter(c -> c.position() != null));
    }
}
