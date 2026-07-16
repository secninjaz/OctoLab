package com.gl4a.fragment;

import android.content.Intent;
import androidx.annotation.AttrRes;
import android.view.View;
import android.widget.TextView;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.EditIssueCommentActivity;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabIssue;
import com.gl4a.gitlab.service.GitLabIssueService;
import com.gl4a.model.TimelineItem;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;

import java.util.List;

import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import retrofit2.Response;

public class IssueFragment extends IssueFragmentBase {
    public static IssueFragment newInstance(String repoOwner, String repoName, GitLabIssue issue,
            boolean isCollaborator, IntentUtils.InitialCommentMarker initialComment) {
        IssueFragment f = new IssueFragment();
        f.setArguments(buildArgs(repoOwner, repoName, issue, isCollaborator, initialComment));
        return f;
    }

    public void updateState(GitLabIssue issue) {
        mIssue = issue;
        assignHighlightColor();
        reloadEvents(false);
    }

    @Override
    protected void bindSpecialViews(View headerView) {
        TextView tvPull = headerView.findViewById(R.id.tv_pull);
        // GitLab issues are not linked to MRs via diffUrl; hide the pull-link view
        tvPull.setVisibility(View.GONE);
    }

    @Override
    protected void assignHighlightColor() {
        if ("closed".equals(mIssue.state())) {
            setHighlightColors(R.attr.colorIssueClosed, R.attr.colorIssueClosedDark);
        } else {
            setHighlightColors(R.attr.colorIssueOpen, R.attr.colorIssueOpenDark);
        }
    }

    @Override
    public void onClick(View v) {
        super.onClick(v);
    }

    @Override
    protected Single<List<TimelineItem>> onCreateDataSingle(boolean bypassCache) {
        final int issueIid = mIssue.number();
        final long projectId = mIssue.projectId;
        final GitLabIssueService service = ServiceFactory.get(GitLabIssueService.class, bypassCache);

        return ApiHelpers.PageIterator
                .<GitLabComment>toSingle(page -> service.getComments(projectId, issueIid, "asc", (int) page, 100)
                        .map(response -> {
                            if (!response.isSuccessful() || response.body() == null) {
                                return retrofit2.Response.<com.gl4a.gitlab.model.GitLabPage<GitLabComment>>error(
                                        response.errorBody(), response.raw());
                            }
                            return retrofit2.Response.success(ApiHelpers.toPage(response));
                        }))
                .map(comments -> {
                    // Include system notes (mentions in commits, state changes, etc.)
                    // so the issue timeline matches GitLab web. System notes render with
                    // a distinct appearance via CommentViewHolder.isSystemNote().
                    return comments;
                })
                .compose(RxUtils.<GitLabComment, TimelineItem>mapList(
                        c -> new TimelineItem.TimelineComment(c)))
                .subscribeOn(Schedulers.io());
    }

    @Override
    public void editComment(GitLabComment comment) {
        @AttrRes int highlightColorAttr = "closed".equals(mIssue.state())
                ? R.attr.colorIssueClosed : R.attr.colorIssueOpen;
        // Pass mIssue.projectId so EditIssueCommentActivity does not have to do a redundant lookup.
        Intent intent = EditIssueCommentActivity.makeIntent(getActivity(), mRepoOwner, mRepoName,
                mIssue.projectId, mIssue.number(), comment.id(), comment.body(), highlightColorAttr);
        mEditLauncher.launch(intent);
    }

    @Override
    protected Single<Response<Void>> doDeleteComment(GitLabComment comment) {
        GitLabIssueService service = ServiceFactory.get(GitLabIssueService.class, false);
        return service.deleteComment(mIssue.projectId, mIssue.number(), comment.id());
    }

    @Override
    public int getCommentEditorHintResId() {
        return R.string.issue_comment_hint;
    }
}
