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
package com.gl4a.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.EditIssueCommentActivity;
import com.gl4a.activities.EditMergeRequestCommentActivity;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabIssue;
import com.gl4a.gitlab.model.GitLabMergeRequest;
import com.gl4a.gitlab.service.GitLabIssueService;
import com.gl4a.gitlab.service.GitLabMergeRequestService;
import com.gl4a.model.TimelineItem;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;
import com.gl4a.widget.MergeRequestBranchInfoView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.annotation.AttrRes;
import androidx.annotation.StringRes;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import retrofit2.Response;

/**
 * Conversation tab for a GitLab Merge Request.
 * Replaces the former PullRequestConversationFragment which used GitHub SDK types.
 * All PullRequest references have been updated to GitLabMergeRequest.
 */
public class PullRequestConversationFragment extends IssueFragmentBase {
    private static final int ID_LOADER_HEAD_REF = 1;

    private GitLabMergeRequest mMergeRequest;
    private boolean mHasLoadedHeadInfo;

    public static PullRequestConversationFragment newInstance(GitLabMergeRequest mr,
            String repoOwner, String repoName,
            GitLabIssue issue, boolean isCollaborator,
            IntentUtils.InitialCommentMarker initialComment) {
        PullRequestConversationFragment f = new PullRequestConversationFragment();

        // Use the caller-provided owner/repo (from PullRequestActivity.mRepoOwner/mRepoName)
        // rather than base.repo() which is often null/empty. An empty owner corrupts the
        // global currentProjectPath and breaks Phase 2 Markdown API rendering everywhere.
        if (android.text.TextUtils.isEmpty(repoOwner) || android.text.TextUtils.isEmpty(repoName)) {
            GitLabMergeRequest.GitLabMRBranch base = mr.base();
            repoOwner = base != null && base.repo() != null ? base.repo().owner().login() : "";
            repoName  = base != null && base.repo() != null ? base.repo().name() : "";
        }

        // When issue is null, derive a stub GitLabIssue from the MR so that
        // IssueFragmentBase.fillData() does not NPE on mIssue.user(), mIssue.createdAt(), etc.
        if (issue == null) {
            issue = buildStubIssueFromMR(mr);
        }

        Bundle args = buildArgs(repoOwner, repoName, issue, isCollaborator, initialComment);
        args.putParcelable("mr", mr);
        f.setArguments(args);

        return f;
    }

    /** Constructs a GitLabIssue stub populated from MR fields so the base class header renders. */
    private static GitLabIssue buildStubIssueFromMR(GitLabMergeRequest mr) {
        GitLabIssue stub = new GitLabIssue();
        stub.id = mr.id;
        stub.iid = mr.iid;
        stub.projectId = mr.projectId;
        stub.title = mr.title;
        stub.description = mr.description;
        stub.state = mr.state;
        stub.author = mr.author;
        stub.createdAt = mr.createdAt;
        stub.updatedAt = mr.updatedAt;
        stub.webUrl = mr.webUrl;
        stub.commentsCount = mr.commentsCount;
        return stub;
    }

    public void updateState(GitLabMergeRequest mr) {
        mMergeRequest = mr;
        assignHighlightColor();
        reloadEvents(false);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        mMergeRequest = getArguments().getParcelable("mr");
        super.onCreate(savedInstanceState);
        // If no GitLabIssue was passed (common for MR screens), synthesise a stub from
        // the MergeRequest so that IssueFragmentBase.fillData() does not NPE.
        if (mIssue == null && mMergeRequest != null) {
            mIssue = new GitLabIssue();
            mIssue.id = mMergeRequest.id;
            mIssue.iid = mMergeRequest.iid;
            mIssue.projectId = mMergeRequest.projectId;
            mIssue.title = mMergeRequest.title;
            mIssue.description = mMergeRequest.description;
            mIssue.state = mMergeRequest.state;
            mIssue.author = mMergeRequest.author;
            mIssue.createdAt = mMergeRequest.createdAt;
            mIssue.updatedAt = mMergeRequest.updatedAt;
            mIssue.webUrl = mMergeRequest.webUrl;
            mIssue.commentsCount = mMergeRequest.commentsCount;
            mIssue.confidential = false;
            // MR labels now have colour data (with_labels_details=true on MR endpoints).
            mIssue.labelNames = mMergeRequest.labelNames;
            mIssue.milestone = mMergeRequest.milestone;
            mIssue.assignees = mMergeRequest.assignees;
            mIssue.assignee = mMergeRequest.assignee;
        }
        setHasOptionsMenu(true);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.pull_request_fragment_menu, menu);

        // GitLab does not support restoring a deleted source branch via API in the same way;
        // hide the delete/restore branch menu item.
        menu.removeItem(R.id.delete_branch);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRefresh() {
        if (mListHeaderView != null) {
            fillStatus(new ArrayList<>());
        }
        super.onRefresh();
    }

    @Override
    protected void bindSpecialViews(View headerView) {
        MergeRequestBranchInfoView branchContainer =
                headerView.findViewById(R.id.branch_container);
        if (branchContainer != null && mMergeRequest != null) {
            branchContainer.bind(mMergeRequest.head(), mMergeRequest.base());
            branchContainer.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void assignHighlightColor() {
        if (mMergeRequest == null) {
            setHighlightColors(R.attr.colorIssueOpen, R.attr.colorIssueOpenDark);
            return;
        }
        if (mMergeRequest.isMerged()) {
            setHighlightColors(R.attr.colorPullRequestMerged, R.attr.colorPullRequestMergedDark);
        } else if ("closed".equals(mMergeRequest.state())) {
            setHighlightColors(R.attr.colorIssueClosed, R.attr.colorIssueClosedDark);
        } else if (mMergeRequest.isDraft()) {
            setHighlightColors(R.attr.colorPullRequestDraft, R.attr.colorPullRequestDraftDark);
        } else {
            setHighlightColors(R.attr.colorIssueOpen, R.attr.colorIssueOpenDark);
        }
    }

    private void fillStatus(List<Object> statuses) {
        // GitLab pipeline status is loaded separately via pipeline API.
        // The commit_status_box widget is hidden when there are no statuses.
        View statusBox = mListHeaderView.findViewById(R.id.commit_status_box);
        if (statusBox != null) {
            statusBox.setVisibility(statuses.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    protected Single<List<TimelineItem>> onCreateDataSingle(boolean bypassCache) {
        if (mMergeRequest == null) {
            return Single.just(new ArrayList<>());
        }

        final long projectId = mMergeRequest.projectId;
        final int mrIid = mMergeRequest.iid;
        final GitLabMergeRequestService mrService =
                ServiceFactory.get(GitLabMergeRequestService.class, bypassCache);

        // Load MR notes (comments, first page) and map each to a TimelineItem.
        // TODO: implement full pagination once GitLabMergeRequestService.getComments is updated
        //       to return GitLabPage<GitLabComment>.
        Single<List<TimelineItem>> commentsSingle = mrService.getComments(projectId, mrIid, "asc", 1, 100)
                .map(ApiHelpers::throwOnFailure)
                .map(comments -> {
                    List<TimelineItem> items = new ArrayList<>();
                    for (com.gl4a.gitlab.model.GitLabComment c : comments) {
                        // Include system notes (mentions in commits, state changes) so MR
                        // timeline matches GitLab web.
                        items.add(new TimelineItem.TimelineComment(c));
                    }
                    return items;
                })
                .subscribeOn(Schedulers.io());

        return commentsSingle;
    }

    // ---- Override IssueFragmentBase API calls to use MR endpoints instead of Issue endpoints ----

    @Override
    public Single<?> onEditorDoSend(String comment) {
        com.gl4a.gitlab.service.GitLabMergeRequestService service =
                com.gl4a.ServiceFactory.get(com.gl4a.gitlab.service.GitLabMergeRequestService.class, false);
        java.util.Map<String, Object> req = new java.util.HashMap<>();
        req.put("body", comment);
        return service.createComment(mMergeRequest.projectId, mMergeRequest.iid, req)
                .map(com.gl4a.utils.ApiHelpers::throwOnFailure)
                .compose(com.gl4a.utils.RxUtils::doInBackground);
    }

    @Override
    public io.reactivex.Single<com.gl4a.gitlab.model.GitLabReaction> addReaction(
            com.gl4a.widget.ReactionBar.Item item, String content) {
        com.gl4a.gitlab.service.GitLabAwardEmojiService service =
                com.gl4a.ServiceFactory.get(com.gl4a.gitlab.service.GitLabAwardEmojiService.class, false);
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", mapContentToEmojiName(content));
        return service.addMergeRequestAwardEmoji(mMergeRequest.projectId, mMergeRequest.iid, body)
                .map(r -> {
                    if (!r.isSuccessful() || r.body() == null)
                        throw new RuntimeException("Failed to add reaction: " + r.code());
                    com.gl4a.gitlab.model.GitLabAwardEmoji e = r.body();
                    com.gl4a.gitlab.model.GitLabReaction reaction = new com.gl4a.gitlab.model.GitLabReaction();
                    reaction.id = e.id; reaction.name = e.name; reaction.user = e.user;
                    return reaction;
                })
                .compose(com.gl4a.utils.RxUtils::doInBackground);
    }

    @Override
    public io.reactivex.Single<Boolean> deleteReaction(
            com.gl4a.widget.ReactionBar.Item item, long reactionId) {
        com.gl4a.gitlab.service.GitLabAwardEmojiService service =
                com.gl4a.ServiceFactory.get(com.gl4a.gitlab.service.GitLabAwardEmojiService.class, false);
        return service.deleteMergeRequestAwardEmoji(mMergeRequest.projectId, mMergeRequest.iid, reactionId)
                .map(r -> r.isSuccessful() || r.code() == 404)
                .compose(com.gl4a.utils.RxUtils::doInBackground);
    }

    @Override
    public io.reactivex.Single<java.util.List<com.gl4a.gitlab.model.GitLabReaction>> loadReactionDetails(
            com.gl4a.widget.ReactionBar.Item item, boolean bypassCache) {
        com.gl4a.gitlab.service.GitLabAwardEmojiService service =
                com.gl4a.ServiceFactory.get(com.gl4a.gitlab.service.GitLabAwardEmojiService.class, bypassCache);
        return service.getMergeRequestAwardEmojis(mMergeRequest.projectId, mMergeRequest.iid, 1, 100)
                .map(response -> {
                    java.util.List<com.gl4a.gitlab.model.GitLabReaction> result = new java.util.ArrayList<>();
                    if (response.isSuccessful() && response.body() != null) {
                        for (com.gl4a.gitlab.model.GitLabAwardEmoji e : response.body()) {
                            com.gl4a.gitlab.model.GitLabReaction r = new com.gl4a.gitlab.model.GitLabReaction();
                            r.id = e.id; r.name = e.name; r.user = e.user;
                            result.add(r);
                        }
                    }
                    return result;
                })
                .compose(com.gl4a.utils.RxUtils::doInBackground);
    }

    @Override
    public io.reactivex.Single<java.util.List<com.gl4a.gitlab.model.GitLabReaction>> loadReactionDetails(
            com.gl4a.gitlab.model.GitLabComment comment, boolean bypassCache) {
        com.gl4a.gitlab.service.GitLabAwardEmojiService service =
                com.gl4a.ServiceFactory.get(com.gl4a.gitlab.service.GitLabAwardEmojiService.class, bypassCache);
        return service.getMergeRequestNoteAwardEmojis(
                        mMergeRequest.projectId, mMergeRequest.iid, comment.id(), 1, 50)
                .map(response -> {
                    java.util.List<com.gl4a.gitlab.model.GitLabReaction> result = new java.util.ArrayList<>();
                    if (response.isSuccessful() && response.body() != null) {
                        for (com.gl4a.gitlab.model.GitLabAwardEmoji e : response.body()) {
                            com.gl4a.gitlab.model.GitLabReaction r = new com.gl4a.gitlab.model.GitLabReaction();
                            r.id = e.id; r.name = e.name; r.user = e.user;
                            result.add(r);
                        }
                    }
                    return result;
                })
                .compose(com.gl4a.utils.RxUtils::doInBackground);
    }

    @Override
    public io.reactivex.Single<com.gl4a.gitlab.model.GitLabReaction> addReaction(
            com.gl4a.gitlab.model.GitLabComment comment, String content) {
        com.gl4a.gitlab.service.GitLabAwardEmojiService service =
                com.gl4a.ServiceFactory.get(com.gl4a.gitlab.service.GitLabAwardEmojiService.class, false);
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", mapContentToEmojiName(content));
        return service.addMergeRequestNoteAwardEmoji(
                        mMergeRequest.projectId, mMergeRequest.iid, comment.id(), body)
                .map(r -> {
                    if (!r.isSuccessful() || r.body() == null)
                        throw new RuntimeException("Failed to add reaction: " + r.code());
                    com.gl4a.gitlab.model.GitLabAwardEmoji e = r.body();
                    com.gl4a.gitlab.model.GitLabReaction reaction = new com.gl4a.gitlab.model.GitLabReaction();
                    reaction.id = e.id; reaction.name = e.name; reaction.user = e.user;
                    return reaction;
                })
                .compose(com.gl4a.utils.RxUtils::doInBackground);
    }

    @Override
    public io.reactivex.Single<Boolean> deleteReaction(
            com.gl4a.gitlab.model.GitLabComment comment, long reactionId) {
        com.gl4a.gitlab.service.GitLabAwardEmojiService service =
                com.gl4a.ServiceFactory.get(com.gl4a.gitlab.service.GitLabAwardEmojiService.class, false);
        return service.deleteMergeRequestNoteAwardEmoji(
                        mMergeRequest.projectId, mMergeRequest.iid, comment.id(), reactionId)
                .map(r -> r.isSuccessful() || r.code() == 404)
                .compose(com.gl4a.utils.RxUtils::doInBackground);
    }

    @Override
    public void editComment(GitLabComment comment) {
        final @AttrRes int highlightColorAttr = mMergeRequest != null && mMergeRequest.isMerged()
                ? R.attr.colorPullRequestMerged
                : "closed".equals(mMergeRequest != null ? mMergeRequest.state() : "")
                        ? R.attr.colorIssueClosed : R.attr.colorIssueOpen;

        // Pass projectId so EditMergeRequestCommentActivity can call the API without a lookup.
        long projectId = mMergeRequest != null ? mMergeRequest.projectId : -1L;
        int mrIid = mMergeRequest != null ? mMergeRequest.iid : 0;
        Intent intent = EditMergeRequestCommentActivity.makeIntent(
                getActivity(), mRepoOwner, mRepoName,
                projectId, mrIid,
                comment.id(), comment.body(), highlightColorAttr);
        mEditLauncher.launch(intent);
    }

    @Override
    protected Single<Response<Void>> doDeleteComment(GitLabComment comment) {
        GitLabMergeRequestService service =
                ServiceFactory.get(GitLabMergeRequestService.class, false);
        return service.deleteComment(
                mMergeRequest.projectId, mMergeRequest.iid, comment.id());
    }

    @Override
    public int getCommentEditorHintResId() {
        return R.string.pull_request_comment_hint;
    }

    @Override
    public void onConfirmed(String tag, Parcelable data) {
        super.onConfirmed(tag, data);
    }
}
