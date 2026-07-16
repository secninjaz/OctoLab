package com.gl4a.adapter.timeline;
import com.gl4a.github.model.IssueEvent;
import com.gl4a.github.model.Rename;
import com.gl4a.github.model.Team;
import com.gl4a.gitlab.model.GitLabIssue;
import com.gl4a.gitlab.model.GitLabEvent;
import com.gl4a.gitlab.model.GitLabUser;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.StringRes;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.TypefaceSpan;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.gl4a.R;
import com.gl4a.activities.CommitActivity;
import com.gl4a.activities.IssueActivity;
import com.gl4a.activities.PullRequestActivity;
import com.gl4a.activities.UserActivity;
import com.gl4a.model.TimelineItem;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.StringUtils;
import com.gl4a.widget.IntentSpan;
import com.gl4a.widget.TimestampToastSpan;


import java.util.ArrayList;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.gl4a.gitlab.model.GitLabIssueEventType;

class EventViewHolder
        extends TimelineItemAdapter.TimelineItemViewHolder<TimelineItem.TimelineEvent>
        implements View.OnClickListener {

    // GitLab commit URLs: <instance>/<namespace>/<project>/-/commit/<sha>
    private static final Pattern COMMIT_URL_REPO_NAME_AND_OWNER_PATTERN =
            Pattern.compile(".*?/([^/]+)/([^/]+)/-/commit");

    private final Context mContext;
    private final String mRepoOwner;
    private final String mRepoName;
    private final boolean mIsPullRequest;

    private final ImageView mAvatarView;
    private final ImageView mEventIconView;
    private final TextView mMessageView;
    private final View mAvatarContainer;

    public EventViewHolder(View itemView, String repoOwner, String repoName,
            boolean isPullRequest) {
        super(itemView);

        mContext = itemView.getContext();
        mRepoOwner = repoOwner;
        mRepoName = repoName;
        mIsPullRequest = isPullRequest;

        mAvatarView = itemView.findViewById(R.id.iv_gravatar);
        mEventIconView = itemView.findViewById(R.id.iv_event_icon);
        mMessageView = itemView.findViewById(R.id.tv_message);
        mAvatarContainer = itemView.findViewById(R.id.avatar_container);
        mAvatarContainer.setOnClickListener(this);
    }

    @Override
    public void bind(TimelineItem.TimelineEvent item) {
        GitLabUser user = item.event.assigner() != null
                ? item.event.assigner() : item.event.actor();
        AvatarHandler.assignAvatar(mAvatarView, user);
        mAvatarContainer.setTag(user);

        Integer eventIconResId = getEventIcon(item.event);
        if (eventIconResId != null) {
            mEventIconView.setImageResource(eventIconResId);
            mEventIconView.setVisibility(View.VISIBLE);
        } else {
            mEventIconView.setVisibility(View.GONE);
        }

        mMessageView.setText(formatEvent(item.event, user));
    }

    private Integer getEventIcon(IssueEvent event) {
        String e = event.event();
        if (GitLabIssueEventType.Closed.equals(e)) return R.drawable.issue_event_closed;
        if (GitLabIssueEventType.Reopened.equals(e)) return R.drawable.issue_event_reopened;
        if (GitLabIssueEventType.Merged.equals(e)) return R.drawable.issue_event_merged;
        if (GitLabIssueEventType.Referenced.equals(e)) return R.drawable.issue_event_referenced;
        if (GitLabIssueEventType.Assigned.equals(e) || GitLabIssueEventType.Unassigned.equals(e))
            return R.drawable.issue_event_person;
        if (GitLabIssueEventType.Labeled.equals(e) || GitLabIssueEventType.Unlabeled.equals(e))
            return R.drawable.issue_event_label;
        if (GitLabIssueEventType.Locked.equals(e)) return R.drawable.issue_event_locked;
        if (GitLabIssueEventType.Unlocked.equals(e)) return R.drawable.issue_event_unlocked;
        if (GitLabIssueEventType.Milestoned.equals(e) || GitLabIssueEventType.Demilestoned.equals(e))
            return R.drawable.issue_event_milestone;
        if (GitLabIssueEventType.Renamed.equals(e)) return R.drawable.issue_event_renamed;
        if (GitLabIssueEventType.CommentDeleted.equals(e) || GitLabIssueEventType.ReviewDismissed.equals(e))
            return R.drawable.timeline_event_dismissed_deleted;
        if (GitLabIssueEventType.HeadRefDeleted.equals(e) || GitLabIssueEventType.HeadRefRestored.equals(e)
                || GitLabIssueEventType.HeadRefForcePushed.equals(e)
                || GitLabIssueEventType.ConvertToDraft.equals(e)
                || GitLabIssueEventType.AutoMergeEnabled.equals(e)
                || GitLabIssueEventType.AutoSquashEnabled.equals(e)
                || GitLabIssueEventType.AutoRebaseEnabled.equals(e)
                || GitLabIssueEventType.AutoMergeDisabled.equals(e))
            return R.drawable.timeline_event_branch;
        if (GitLabIssueEventType.AddedToMergeQueue.equals(e) || GitLabIssueEventType.RemovedFromMergeQueue.equals(e))
            return R.drawable.timeline_event_merge_queue;
        if (GitLabIssueEventType.ReviewRequested.equals(e) || GitLabIssueEventType.ReadyForReview.equals(e))
            return R.drawable.timeline_event_review;
        if (GitLabIssueEventType.ReviewRequestRemoved.equals(e))
            return R.drawable.timeline_event_review_request_removed;
        if (GitLabIssueEventType.CrossReferenced.equals(e) || GitLabIssueEventType.Transferred.equals(e))
            return R.drawable.timeline_event_cross_referenced;
        return null;
    }

    private CharSequence formatEvent(final IssueEvent event, final GitLabUser user) {
        String textBase = null;
        int textResId = 0;
        String commitId = event.commitId();
        String commitUrl = event.commitUrl();
        String e = event.event();

        if (GitLabIssueEventType.Closed.equals(e)) {
            if (mIsPullRequest) {
                textResId = commitId != null
                        ? R.string.pull_request_event_closed_with_commit
                        : R.string.pull_request_event_closed;
            } else {
                textResId = commitId != null
                        ? R.string.issue_event_closed_completed_with_commit
                        : R.string.issue_event_closed_completed;
            }
        } else if (GitLabIssueEventType.Reopened.equals(e)) {
            textResId = mIsPullRequest
                    ? R.string.pull_request_event_reopened
                    : R.string.issue_event_reopened;
        } else if (GitLabIssueEventType.Merged.equals(e)) {
            textResId = commitId != null
                    ? R.string.pull_request_event_merged_with_commit
                    : R.string.pull_request_event_merged;
        } else if (GitLabIssueEventType.Referenced.equals(e)) {
            if (mIsPullRequest) {
                textResId = commitId != null
                        ? R.string.pull_request_event_referenced_with_commit
                        : R.string.pull_request_event_referenced;
            } else {
                textResId = commitId != null
                        ? R.string.issue_event_referenced_with_commit
                        : R.string.issue_event_referenced;
            }
        } else if (GitLabIssueEventType.Assigned.equals(e) || GitLabIssueEventType.Unassigned.equals(e)) {
            boolean isAssign = GitLabIssueEventType.Assigned.equals(e);
            String actorLogin = user != null ? user.login() : null;
            String assigneeLogin = event.assignee() != null ? event.assignee().login() : null;
            if (assigneeLogin != null && assigneeLogin.equals(actorLogin)) {
                if (isAssign) {
                    textResId = mIsPullRequest
                            ? R.string.pull_request_event_assigned_self
                            : R.string.issue_event_assigned_self;
                } else {
                    textResId = R.string.issue_event_unassigned_self;
                }
            } else {
                textResId = isAssign
                        ? R.string.issue_event_assigned
                        : R.string.issue_event_unassigned;
                textBase = mContext.getString(textResId,
                        getUserLoginWithBotSuffix(user),
                        getUserLoginWithBotSuffix(event.assignee()));
            }
        } else if (GitLabIssueEventType.Labeled.equals(e)) {
            textResId = R.string.issue_event_labeled;
        } else if (GitLabIssueEventType.Unlabeled.equals(e)) {
            textResId = R.string.issue_event_unlabeled;
        } else if (GitLabIssueEventType.Locked.equals(e)) {
            if (event.lockReason() == null) {
                textResId = R.string.issue_event_locked;
            } else {
                textBase = mContext.getString(R.string.issue_event_locked_with_reason,
                        getUserLoginWithBotSuffix(user), event.lockReason());
            }
        } else if (GitLabIssueEventType.Unlocked.equals(e)) {
            textResId = R.string.issue_event_unlocked;
        } else if (GitLabIssueEventType.Milestoned.equals(e) || GitLabIssueEventType.Demilestoned.equals(e)) {
            textResId = GitLabIssueEventType.Milestoned.equals(e)
                    ? R.string.issue_event_milestoned
                    : R.string.issue_event_demilestoned;
            // Fix: milestone may be null if the milestone was subsequently deleted.
            String milestoneTitle = event.milestone() != null
                    ? event.milestone().title()
                    : mContext.getString(R.string.deleted);
            textBase = mContext.getString(textResId,
                    getUserLoginWithBotSuffix(user), milestoneTitle);
        } else if (GitLabIssueEventType.Renamed.equals(e)) {
            // Fix: rename may be null if the API omits the rename object.
            Rename rename = event.rename();
            if (rename == null) {
                textResId = R.string.issue_event_renamed;
            } else {
                textBase = mContext.getString(R.string.issue_event_renamed,
                        getUserLoginWithBotSuffix(user), rename.from(), rename.to());
            }
        } else if (GitLabIssueEventType.Transferred.equals(e)) {
            textResId = R.string.issue_event_transferred;
        } else if (GitLabIssueEventType.ReviewRequested.equals(e) || GitLabIssueEventType.ReviewRequestRemoved.equals(e)) {
            if (event.requestedTeam() != null) {
                @StringRes int stringResId = GitLabIssueEventType.ReviewRequested.equals(e)
                        ? R.string.pull_request_event_team_review_requested
                        : R.string.pull_request_event_team_review_request_removed;
                textBase = mContext.getString(stringResId,
                        getUserLoginWithBotSuffix(event.reviewRequester()),
                        mRepoOwner + "/" + event.requestedTeam().name());
            } else {
                final String reviewerNames;
                if (event.requestedReviewers() != null) {
                    ArrayList<String> reviewers = new ArrayList<>();
                    for (GitLabUser reviewer : event.requestedReviewers()) {
                        reviewers.add(ApiHelpers.getUserLogin(mContext, reviewer));
                    }
                    reviewerNames = TextUtils.join(", ", reviewers);
                } else {
                    reviewerNames = ApiHelpers.getUserLogin(mContext, event.requestedReviewer());
                }
                @StringRes int stringResId = GitLabIssueEventType.ReviewRequested.equals(e)
                        ? R.string.pull_request_event_review_requested
                        : R.string.pull_request_event_review_request_removed;
                textBase = mContext.getString(stringResId,
                        getUserLoginWithBotSuffix(event.reviewRequester()), reviewerNames);
            }
        } else if (GitLabIssueEventType.ReviewDismissed.equals(e)) {
            String dismissalCommitId = event.dismissedReview().dismissalCommitId();
            if (dismissalCommitId != null) {
                commitId = dismissalCommitId;
                commitUrl = null;
                textResId = R.string.pull_request_event_review_dismissed_via_commit;
            } else {
                textResId = R.string.pull_request_event_review_dismissed;
            }
        } else if (GitLabIssueEventType.HeadRefDeleted.equals(e)) {
            textResId = R.string.pull_request_event_ref_deleted;
        } else if (GitLabIssueEventType.HeadRefRestored.equals(e)) {
            textResId = R.string.pull_request_event_ref_restored;
        } else if (GitLabIssueEventType.HeadRefForcePushed.equals(e)) {
            textResId = R.string.pull_request_event_ref_force_pushed;
        } else if (GitLabIssueEventType.AutoMergeDisabled.equals(e)) {
            textResId = R.string.pull_request_event_auto_merge_disabled;
        } else if (GitLabIssueEventType.AutoMergeEnabled.equals(e)) {
            textResId = R.string.pull_request_event_auto_merge_enabled;
        } else if (GitLabIssueEventType.AutoSquashEnabled.equals(e)) {
            textResId = R.string.pull_request_event_auto_squash_enabled;
        } else if (GitLabIssueEventType.AutoRebaseEnabled.equals(e)) {
            textResId = R.string.pull_request_event_auto_rebase_enabled;
        } else if (GitLabIssueEventType.AddedToMergeQueue.equals(e)) {
            textResId = R.string.pull_request_event_added_to_merge_queue;
        } else if (GitLabIssueEventType.RemovedFromMergeQueue.equals(e)) {
            textResId = R.string.pull_request_event_removed_from_merge_queue;
        } else if (GitLabIssueEventType.CommentDeleted.equals(e)) {
            textResId = R.string.pull_request_event_comment_deleted;
        } else if (GitLabIssueEventType.ConvertToDraft.equals(e)) {
            textResId = R.string.pull_request_event_convert_to_draft;
        } else if (GitLabIssueEventType.ReadyForReview.equals(e)) {
            textResId = R.string.pull_request_event_ready_for_review;
        } else if (GitLabIssueEventType.CrossReferenced.equals(e)) {
            textResId = mIsPullRequest
                    ? R.string.pull_request_event_mentioned
                    : R.string.issue_event_mentioned;
        } else {
            return null;
        }

        if (textBase == null) {
            textBase = mContext.getString(textResId, getUserLoginWithBotSuffix(user));
        }

        SpannableStringBuilder text = StringUtils.applyBoldTags(textBase);
        replaceCommitPlaceholder(text, commitId, commitUrl);
        StringUtils.replaceLabelPlaceholder(mContext, text, event.label());
        replaceBotPlaceholder(text);
        replaceSourcePlaceholder(text, event.source());
        replaceTimePlaceholder(text, event.createdAt());
        return text;
    }

    private void replaceCommitPlaceholder(SpannableStringBuilder text, String commitId, String commitUrl) {
        int pos = text.toString().indexOf("[commit]");
        if (commitId == null || pos < 0) {
            return;
        }
        // The commit might be in a different repo. The API doesn't provide
        // that information directly, so get it indirectly by parsing the URL
        String commitRepoOwner = mRepoOwner;
        String commitRepoName = mRepoName;
        if (commitUrl != null) {
            Matcher matcher = COMMIT_URL_REPO_NAME_AND_OWNER_PATTERN.matcher(commitUrl);
            if (matcher.find()) {
                commitRepoOwner = matcher.group(1);
                commitRepoName = matcher.group(2);
            }
        }
        boolean isCommitInDifferentRepo = !mRepoOwner.equals(commitRepoOwner) || !mRepoName.equals(commitRepoName);
        String shortCommitSha = commitId.substring(0, 7);
        String commitText = isCommitInDifferentRepo
                ? commitRepoOwner + "/" + commitRepoName + "@" + shortCommitSha
                : shortCommitSha;
        text.replace(pos, pos + 8, commitText);

        String finalRepoOwner = commitRepoOwner;
        String finalRepoName = commitRepoName;
        text.setSpan(new IntentSpan(mContext, context ->
                CommitActivity.makeIntent(context, finalRepoOwner, finalRepoName, commitId)), pos, pos + commitText.length(), 0);
        text.setSpan(new TypefaceSpan("monospace"), pos, pos + commitText.length(), 0);
    }

    private void replaceBotPlaceholder(SpannableStringBuilder text) {
        int pos = text.toString().indexOf("[bot]");
        if (pos >= 0) {
            text.delete(pos, pos + 5);
            StringUtils.addUserTypeSpan(mContext, text, pos, mContext.getString(R.string.user_type_bot));
        }
    }

    private void replaceSourcePlaceholder(SpannableStringBuilder text, IssueEvent.CrossReferenceSource referenceSource) {
        int pos = text.toString().indexOf("[source]");
        if (pos < 0) {
            return;
        }
        final GitLabIssue source = referenceSource.issue();
        var sourceRepoOwnerAndName = ApiHelpers.extractRepoOwnerAndNameFromIssue(source);
        String sourceRepoOwner = sourceRepoOwnerAndName.first;
        String sourceRepoName = sourceRepoOwnerAndName.second;
        boolean isSourceInDifferentRepo = !mRepoOwner.equals(sourceRepoOwner) || !mRepoName.equals(sourceRepoName);
        String sourceLabel = isSourceInDifferentRepo
                ? sourceRepoOwner + "/" + sourceRepoName + "#" + source.number()
                : "#" + source.number();
        text.replace(pos, pos + 8, sourceLabel);
        text.setSpan(new IntentSpan(mContext, context ->
                source.pullRequest() != null
                        ? PullRequestActivity.makeIntent(context, sourceRepoOwner, sourceRepoName, source.number())
                        : IssueActivity.makeIntent(context, sourceRepoOwner, sourceRepoName, source.number())
        ), pos, pos + sourceLabel.length(), 0);
    }

    private void replaceTimePlaceholder(SpannableStringBuilder text, Date time) {
        int pos = text.toString().indexOf("[time]");
        if (pos < 0) {
            return;
        }
        CharSequence formattedTime = time != null ? StringUtils.formatRelativeTime(mContext, time, true) : "";
        text.replace(pos, pos + 6, formattedTime);
        if (time != null) {
            text.setSpan(new TimestampToastSpan(time), pos, pos + formattedTime.length(), 0);
        }
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.avatar_container) {
            GitLabUser user = (GitLabUser) v.getTag();
            Intent intent = UserActivity.makeIntent(mContext, user);
            if (intent != null) {
                mContext.startActivity(intent);
            }
        }
    }

    private String getUserLoginWithBotSuffix(GitLabUser user) {
        if (user != null && user.login() != null) {
            return user.login();
        }
        return mContext.getString(R.string.deleted);
    }
}
