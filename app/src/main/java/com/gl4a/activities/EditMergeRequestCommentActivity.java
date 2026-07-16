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
 * Edit a merge request (pull request) comment via GitLab MR notes API.
 */
public class EditMergeRequestCommentActivity extends EditCommentActivity {
    public static Intent makeIntent(Context context, String repoOwner, String repoName,
            long projectId, int mrIid, long commentId, String body,
            @AttrRes int highlightColorAttr) {
        Intent intent = new Intent(context, EditMergeRequestCommentActivity.class)
                .putExtra("project_id", projectId)
                .putExtra("mr_iid", mrIid);
        return EditCommentActivity.fillInIntent(intent,
                repoOwner, repoName, commentId, 0L, body, highlightColorAttr);
    }

    /** Backwards-compatible overload — project ID resolved lazily. */
    public static Intent makeIntent(Context context, String repoOwner, String repoName,
            int mrIid, long commentId, String body, @AttrRes int highlightColorAttr) {
        return makeIntent(context, repoOwner, repoName, -1L, mrIid, commentId, body, highlightColorAttr);
    }

    private long getProjectId() {
        return getIntent().getLongExtra("project_id", -1L);
    }

    private int getMrIid() {
        return getIntent().getIntExtra("mr_iid", 0);
    }

    @Override
    protected Single<GitLabComment> createComment(String repoOwner, String repoName,
            String body, long replyToCommentId) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("body", body);
        final int mrIid = getMrIid();
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
        final int mrIid = getMrIid();
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
