package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;

import com.gl4a.gitlab.model.GitLabComment;

import io.reactivex.Single;

/** Edit or create a commit comment. TODO: Implement via GitLab commit comment API. */
public class EditCommitCommentActivity extends EditCommentActivity {
    public static Intent makeIntent(Context context, String repoOwner, String repoName,
            String commitSha, long id, String body) {
        Intent intent = new Intent(context, EditCommitCommentActivity.class)
                .putExtra("commit", commitSha);
        return EditCommentActivity.fillInIntent(intent, repoOwner, repoName, id, 0L, body, 0);
    }

    @Override
    protected Single<GitLabComment> createComment(String repoOwner, String repoName,
            String body, long replyToCommentId) {
        return Single.error(new UnsupportedOperationException("Commit comment creation not yet implemented for GitLab"));
    }

    @Override
    protected Single<GitLabComment> editComment(String repoOwner, String repoName,
            long commentId, String body) {
        return Single.error(new UnsupportedOperationException("Commit comment edit not yet implemented for GitLab"));
    }
}
