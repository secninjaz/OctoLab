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
import android.os.Bundle;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.service.GitLabMergeRequestService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.SingleFactory;

import java.util.List;

import io.reactivex.Single;
import retrofit2.Response;

public class PullRequestDiffViewerActivity extends DiffViewerActivity<GitLabComment> {
    public static Intent makeIntent(Context context, String repoOwner, String repoName, int number,
            String commitSha, String path, String diff, List<GitLabComment> comments,
            int initialLine, int highlightStartLine, int highlightEndLine, boolean highlightIsRight,
            IntentUtils.InitialCommentMarker initialComment) {
        Intent intent = new Intent(context, PullRequestDiffViewerActivity.class)
                .putExtra("number", number);
        return DiffViewerActivity.fillInIntent(intent, repoOwner, repoName, commitSha, path,
                diff, comments, initialLine, highlightStartLine, highlightEndLine,
                highlightIsRight, initialComment);
    }

    private int mMergeRequestNumber;
    private long mProjectId = -1L;

    @Override
    protected void openCommentDialog(long id, long replyToId, String line, int position,
            int leftLine, int rightLine, GitLabComment commitComment) {
        String body = commitComment == null ? "" : commitComment.body();
        Intent intent = EditPullRequestDiffCommentActivity.makeIntent(this,
                mRepoOwner, mRepoName, mSha, mPath, line, leftLine, rightLine,
                position, id, body, mMergeRequestNumber, replyToId);
        mEditLauncher.launch(intent);
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mMergeRequestNumber = extras.getInt("number", -1);
    }

    @Override
    protected Single<List<GitLabComment>> getCommentsSingle(boolean bypassCache) {
        return SingleFactory.getProjectId(mRepoOwner, mRepoName)
                .doOnSuccess(id -> mProjectId = id)
                .flatMap(projectId -> {
                    GitLabMergeRequestService service =
                            ServiceFactory.get(GitLabMergeRequestService.class, bypassCache);
                    return service.getComments(projectId, mMergeRequestNumber, "asc", 1, 100)
                            .map(ApiHelpers::throwOnFailure);
                })
                .compose(RxUtils.<GitLabComment>filter(c -> c.position() != null));
    }

    @Override
    protected Uri createUrl(String lineId, long replyId) {
        Uri.Builder builder = IntentUtils.createBaseUriForRepo(mRepoOwner, mRepoName)
                .appendPath("-")
                .appendPath("merge_requests")
                .appendPath(String.valueOf(mMergeRequestNumber))
                .appendPath("diffs");
        if (replyId > 0L) {
            builder.fragment("note_" + replyId);
        } else {
            builder.fragment("diff-" + ApiHelpers.sha256Of(mPath) + lineId);
        }
        return builder.build();
    }

    @Override
    protected Intent navigateUp() {
        return PullRequestActivity.makeIntent(this, mRepoOwner, mRepoName, mMergeRequestNumber);
    }

    @Override
    protected boolean canReply() {
        return true;
    }

    @Override
    protected Single<Response<Void>> deleteCommentSingle(long id) {
        if (mProjectId < 0) {
            return Single.error(new IllegalStateException("Project ID not yet loaded"));
        }
        GitLabMergeRequestService service =
                ServiceFactory.get(GitLabMergeRequestService.class, false);
        return service.deleteComment(mProjectId, mMergeRequestNumber, id);
    }
}
