package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.AttrRes;

import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.service.GitLabIssueService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.SingleFactory;

import java.util.HashMap;
import java.util.Map;

import io.reactivex.Single;

/** Edit or create an issue comment. */
public class EditIssueCommentActivity extends EditCommentActivity {
    public static Intent makeIntent(Context context, String repoOwner,
            String repoName, long projectId, int issueNumber, long id, String body,
            @AttrRes int highlightColorAttr) {
        Intent intent = new Intent(context, EditIssueCommentActivity.class)
                .putExtra("project_id", projectId)
                .putExtra("issue", issueNumber);
        return EditCommentActivity.fillInIntent(intent,
                repoOwner, repoName, id, 0L, body, highlightColorAttr);
    }

    /** Backwards-compatible overload — project ID will be resolved lazily via owner/repo. */
    public static Intent makeIntent(Context context, String repoOwner,
            String repoName, int issueNumber, long id, String body,
            @AttrRes int highlightColorAttr) {
        return makeIntent(context, repoOwner, repoName, -1L, issueNumber, id, body, highlightColorAttr);
    }

    private long getProjectId() {
        return getIntent().getLongExtra("project_id", -1L);
    }

    private int getIssueNumber() {
        return getIntent().getIntExtra("issue", 0);
    }

    @Override
    protected Single<GitLabComment> createComment(String repoOwner, String repoName,
            String body, long replyToCommentId) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("body", body);
        final int issueIid = getIssueNumber();
        long projectId = getProjectId();

        Single<Long> projectIdSingle = projectId > 0
                ? Single.just(projectId)
                : SingleFactory.getProjectId(repoOwner, repoName);

        return projectIdSingle
                .flatMap(pid -> {
                    GitLabIssueService service = ServiceFactory.get(GitLabIssueService.class, false);
                    return service.createComment(pid, issueIid, requestBody)
                            .map(ApiHelpers::throwOnFailure);
                });
    }

    @Override
    protected Single<GitLabComment> editComment(String repoOwner, String repoName,
            long commentId, String body) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("body", body);
        final int issueIid = getIssueNumber();
        long projectId = getProjectId();

        Single<Long> projectIdSingle = projectId > 0
                ? Single.just(projectId)
                : SingleFactory.getProjectId(repoOwner, repoName);

        return projectIdSingle
                .flatMap(pid -> {
                    GitLabIssueService service = ServiceFactory.get(GitLabIssueService.class, false);
                    return service.editComment(pid, issueIid, commentId, requestBody)
                            .map(ApiHelpers::throwOnFailure);
                });
    }
}
