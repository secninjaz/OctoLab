package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.gl4a.R;
import com.gl4a.gitlab.model.GitLabComment;

import io.reactivex.Single;

/**
 * Edit or create a diff comment on a merge request.
 * TODO: Implement via GitLab MR discussion note API (inline note on diff).
 */
public class EditPullRequestDiffCommentActivity extends EditCommentActivity {
    public static Intent makeIntent(Context context, String repoOwner, String repoName,
            String commitId, String path, String line, int leftLine, int rightLine, int position,
            long id, String body, int pullRequestNumber, long replyToCommentId) {
        Intent intent = new Intent(context, EditPullRequestDiffCommentActivity.class)
                .putExtra("commit_id", commitId)
                .putExtra("path", path)
                .putExtra("line", line)
                .putExtra("left_line", leftLine)
                .putExtra("right_line", rightLine)
                .putExtra("position", position)
                .putExtra("pull_request_number", pullRequestNumber);
        return EditCommentActivity.fillInIntent(intent, repoOwner, repoName,
                id, replyToCommentId, body, R.attr.colorIssueOpen);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View header = getLayoutInflater().inflate(R.layout.edit_commit_comment_header, null);
        mEditorSheet.addHeaderView(header);

        TextView line = header.findViewById(R.id.line);
        Bundle extras = getIntent().getExtras();
        line.setText(extras.getString("line"));

        TextView title = header.findViewById(R.id.title);
        title.setText(getString(R.string.commit_comment_dialog_title, extras.getInt("left_line"),
                extras.getInt("right_line")));
    }

    @Override
    protected Single<GitLabComment> createComment(String repoOwner, String repoName,
            String body, long replyToCommentId) {
        // TODO: Implement GitLab MR diff note creation
        return Single.error(new UnsupportedOperationException("MR diff comment creation not yet implemented for GitLab"));
    }

    @Override
    protected Single<GitLabComment> editComment(String repoOwner, String repoName,
            long commentId, String body) {
        // TODO: Implement GitLab MR diff note edit
        return Single.error(new UnsupportedOperationException("MR diff comment edit not yet implemented for GitLab"));
    }
}
