package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.AttrRes;

import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.service.GitLabMergeRequestService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.SingleFactory;

import java.util.HashMap;
import java.util.Map;

import io.reactivex.Single;

/**
 * Edit or reply to a merge request inline comment.
 */
public class EditPullRequestCommentActivity extends EditCommentActivity {
    public static Intent makeIntent(Context context, String repoOwner, String repoName,
            long projectId, int prIid, long id, long replyToCommentId, String body,
            @AttrRes int highlightColorAttr) {
        if (id == 0L && replyToCommentId == 0L) {
            throw new IllegalStateException("Only editing and replying allowed");
        }
        Intent intent = new Intent(context, EditPullRequestCommentActivity.class)
                .putExtra("project_id", projectId)
                .putExtra("pr", prIid);
        return EditCommentActivity.fillInIntent(intent,
                repoOwner, repoName, id, replyToCommentId, body, highlightColorAttr);
    }

    /** Backwards-compatible overload — project ID resolved lazily. */
    public static Intent makeIntent(Context context, String repoOwner, String repoName,
            int prNumber, long id, long replyToCommentId, String body,
            @AttrRes int highlightColorAttr) {
        return makeIntent(context, repoOwner, repoName, -1L, prNumber,
                id, replyToCommentId, body, highlightColorAttr);
    }

    private long getProjectId() {
        return getIntent().getLongExtra("project_id", -1L);
    }

    private int getPrIid() {
        return getIntent().getIntExtra("pr", 0);
    }

    @Override
    protected Single<GitLabComment> createComment(String repoOwner, String repoName,
            String body, long replyToCommentId) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("body", body);
        final int mrIid = getPrIid();
        long projectId = getProjectId();

        Single<Long> projectIdSingle = projectId > 0
                ? Single.just(projectId)
                : SingleFactory.getProjectId(repoOwner, repoName);

        return projectIdSingle
                .flatMap(pid -> {
                    GitLabMergeRequestService service =
                            ServiceFactory.get(GitLabMergeRequestService.class, false);
                    return service.createComment(pid, mrIid, requestBody)
                            .map(ApiHelpers::throwOnFailure);
                });
    }

    @Override
    protected Single<GitLabComment> editComment(String repoOwner, String repoName,
            long commentId, String body) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("body", body);
        final int mrIid = getPrIid();
        long projectId = getProjectId();

        Single<Long> projectIdSingle = projectId > 0
                ? Single.just(projectId)
                : SingleFactory.getProjectId(repoOwner, repoName);

        return projectIdSingle
                .flatMap(pid -> {
                    GitLabMergeRequestService service =
                            ServiceFactory.get(GitLabMergeRequestService.class, false);
                    return service.editComment(pid, mrIid, commentId, requestBody)
                            .map(ApiHelpers::throwOnFailure);
                });
    }
}
