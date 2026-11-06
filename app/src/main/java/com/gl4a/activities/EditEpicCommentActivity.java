package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.AttrRes;

import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.service.GitLabEpicService;
import com.gl4a.utils.ApiHelpers;

import java.util.HashMap;
import java.util.Map;

import io.reactivex.Single;

/** Edit or create an epic comment (#312). */
public class EditEpicCommentActivity extends EditCommentActivity {
    public static Intent makeIntent(Context context, long groupId, long epicId, long id,
            String body, @AttrRes int highlightColorAttr) {
        Intent intent = new Intent(context, EditEpicCommentActivity.class)
                .putExtra("group_id", groupId)
                .putExtra("epic_id", epicId);
        return EditCommentActivity.fillInIntent(intent, null, null, id, 0L, body,
                highlightColorAttr);
    }

    private long getGroupId() {
        return getIntent().getLongExtra("group_id", -1L);
    }

    private long getEpicId() {
        return getIntent().getLongExtra("epic_id", -1L);
    }

    @Override
    protected Single<GitLabComment> createComment(String repoOwner, String repoName,
            String body, long replyToCommentId) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("body", body);
        return ServiceFactory.get(GitLabEpicService.class, false)
                .createComment(getGroupId(), getEpicId(), requestBody)
                .map(ApiHelpers::throwOnFailure);
    }

    @Override
    protected Single<GitLabComment> editComment(String repoOwner, String repoName,
            long commentId, String body) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("body", body);
        return ServiceFactory.get(GitLabEpicService.class, false)
                .editComment(getGroupId(), getEpicId(), commentId, requestBody)
                .map(ApiHelpers::throwOnFailure);
    }
}
