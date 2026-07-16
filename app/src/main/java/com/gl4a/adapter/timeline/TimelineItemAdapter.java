package com.gl4a.adapter.timeline;
import com.gl4a.gitlab.model.GitLabReaction;
import com.gl4a.gitlab.model.GitLabReactions;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabUser;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.gl4a.R;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.model.TimelineItem;
import com.gl4a.utils.HttpImageGetter;
import com.gl4a.utils.IntentUtils;
import com.gl4a.widget.ReactionBar;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.reactivex.Single;

public class TimelineItemAdapter
        extends RootAdapter<TimelineItem, TimelineItemAdapter.TimelineItemViewHolder>
        implements ReactionBar.ReactionDetailsCache.Listener {

    private static final int VIEW_TYPE_COMMENT = CUSTOM_VIEW_TYPE_START + 1;
    private static final int VIEW_TYPE_EVENT = CUSTOM_VIEW_TYPE_START + 2;
    private static final int VIEW_TYPE_REVIEW = CUSTOM_VIEW_TYPE_START + 3;
    private static final int VIEW_TYPE_DIFF = CUSTOM_VIEW_TYPE_START + 4;
    private static final int VIEW_TYPE_REPLY = CUSTOM_VIEW_TYPE_START + 5;
    private static final int VIEW_TYPE_SYSTEM_NOTE = CUSTOM_VIEW_TYPE_START + 6;

    private final HttpImageGetter mImageGetter;
    private final String mRepoOwner;
    private final String mRepoName;
    private final int mIssueNumber;
    private final boolean mIsPullRequest;
    private final boolean mDisplayReviewDetails;
    private final ReactionBar.ReactionDetailsCache mReactionDetailsCache =
            new ReactionBar.ReactionDetailsCache(this);
    private final OnCommentAction mActionCallback;

    private boolean mDontClearCacheOnClear;
    private boolean mLocked;

    public interface OnCommentAction {
        void editComment(GitLabComment comment);
        void deleteComment(GitLabComment comment);
        void quoteText(CharSequence text);
        void addText(CharSequence text);
        void onReplyCommentSelected(long replyToId);
        long getSelectedReplyCommentId();
        String getShareSubject(GitLabComment comment);
        Single<List<GitLabReaction>> loadReactionDetails(GitLabComment comment, boolean bypassCache);
        Single<GitLabReaction> addReaction(GitLabComment comment, String content);
        Single<Boolean> deleteReaction(GitLabComment comment, long reactionId);
    }

    private final ReviewViewHolder.Callback mReviewCallback = new ReviewViewHolder.Callback() {
        @Override
        public boolean canQuote() {
            return !mLocked && mDisplayReviewDetails;
        }

        @Override
        public void quoteText(CharSequence text) {
            mActionCallback.quoteText(text);
        }
    };

    private final CommentViewHolder.Callback mCommentCallback = new CommentViewHolder.Callback() {
        @Override
        public boolean canAddReaction() {
            return !mLocked;
        }

        @Override
        public boolean canQuote() {
            return !mLocked;
        }

        @Override
        public void quoteText(CharSequence text) {
            mActionCallback.quoteText(text);
        }

        @Override
        public void addText(CharSequence text) {
            mActionCallback.addText(text);
        }

        @Override
        public boolean onMenItemClick(TimelineItem.TimelineComment comment, MenuItem menuItem) {
            switch (menuItem.getItemId()) {
                case R.id.edit:
                    mActionCallback.editComment(comment.comment());
                    return true;

                case R.id.delete:
                    mActionCallback.deleteComment(comment.comment());
                    return true;

                case R.id.share:
                    IntentUtils.share(mContext, mActionCallback.getShareSubject(comment.comment()),
                            Uri.parse(comment.comment().htmlUrl()));
                    return true;

                case R.id.view_in_file:
                    Intent intent = comment.makeDiffIntent(mContext);
                    if (intent != null) {
                        mContext.startActivity(intent);
                    }
                    return true;
            }

            return false;
        }

        @Override
        public Single<List<GitLabReaction>> loadReactionDetails(TimelineItem.TimelineComment item,
                boolean bypassCache) {
            return mActionCallback.loadReactionDetails(item.comment(), bypassCache);
        }

        @Override
        public Single<GitLabReaction> addReaction(TimelineItem.TimelineComment item, String content) {
            return mActionCallback.addReaction(item.comment(), content);
        }

        @Override
        public Single<Boolean> deleteReaction(TimelineItem.TimelineComment item, long reactionId) {
            return mActionCallback.deleteReaction(item.comment(), reactionId);
        }
    };

    private final ReplyViewHolder.Callback mReplyCallback = new ReplyViewHolder.Callback() {
        @Override
        public long getSelectedCommentId() {
            return mActionCallback.getSelectedReplyCommentId();
        }

        @Override
        public void reply(long replyToId) {
            mActionCallback.onReplyCommentSelected(replyToId);
            notifyDataSetChanged();
        }
    };

    public TimelineItemAdapter(Context context, String repoOwner, String repoName, int issueNumber,
            boolean isPullRequest, boolean displayReviewDetails, OnCommentAction callback) {
        super(context);
        mImageGetter = new HttpImageGetter(context);
        mRepoOwner = repoOwner;
        mRepoName = repoName;
        mIssueNumber = issueNumber;
        mIsPullRequest = isPullRequest;
        mDisplayReviewDetails = displayReviewDetails;
        mActionCallback = callback;
    }

    public void setLocked(boolean locked) {
        mLocked = locked;
        notifyDataSetChanged();
    }

    public void destroy() {
        mImageGetter.destroy();
        mReactionDetailsCache.destroy();
    }

    public void pause() {
        mImageGetter.pause();
    }

    public void resume() {
        mImageGetter.resume();
    }

    public void suppressCacheClearOnNextClear() {
        mDontClearCacheOnClear = true;
    }

    public Set<GitLabUser> getUsers() {
        final HashSet<GitLabUser> users = new HashSet<>();
        for (int i = 0; i < getCount(); i++) {
            GitLabUser user = getItem(i).getUser();
            if (user != null) {
                users.add(user);
            }
        }
        return users;
    }

    @Override
    public void clear() {
        super.clear();
        if (!mDontClearCacheOnClear) {
            mImageGetter.clearHtmlCache();
        }
    }

    @Override
    public void addAll(Collection<TimelineItem> objects) {
        mDontClearCacheOnClear = false;
        super.addAll(objects);
    }

    @Override
    public TimelineItemViewHolder onCreateViewHolder(LayoutInflater inflater, ViewGroup parent,
            int viewType) {
        View view;
        TimelineItemViewHolder holder;
        switch (viewType) {
            case VIEW_TYPE_COMMENT:
                view = inflater.inflate(R.layout.row_timeline_comment, parent, false);
                holder = new CommentViewHolder(view, mImageGetter, mRepoOwner,
                        mReactionDetailsCache, mCommentCallback);
                break;
            case VIEW_TYPE_EVENT:
                view = inflater.inflate(R.layout.row_timeline_event, parent, false);
                holder = new EventViewHolder(view, mRepoOwner, mRepoName, mIsPullRequest);
                break;
            case VIEW_TYPE_REVIEW:
                view = inflater.inflate(R.layout.row_timeline_review, parent, false);
                holder = new ReviewViewHolder(view, mImageGetter, mRepoOwner, mRepoName,
                        mIssueNumber, mDisplayReviewDetails, mReviewCallback);
                break;
            case VIEW_TYPE_DIFF:
                view = inflater.inflate(R.layout.row_timeline_diff, parent, false);
                holder = new DiffViewHolder(view, mRepoOwner, mRepoName, mIssueNumber);
                break;
            case VIEW_TYPE_REPLY:
                view = inflater.inflate(R.layout.row_timeline_reply, parent, false);
                holder = new ReplyViewHolder(view, mReplyCallback);
                break;
            case VIEW_TYPE_SYSTEM_NOTE:
                view = inflater.inflate(R.layout.row_system_note, parent, false);
                holder = new SystemNoteViewHolder(view);
                break;
            default:
                throw new IllegalArgumentException("viewType: Unknown timeline item type.");
        }
        return holder;
    }

    @Override
    protected int getItemViewType(TimelineItem item) {
        if (item instanceof TimelineItem.TimelineComment) {
            // System notes use a minimal layout — no avatar, menu, or reactions.
            if (((TimelineItem.TimelineComment) item).comment().isSystemNote()) {
                return VIEW_TYPE_SYSTEM_NOTE;
            }
            return VIEW_TYPE_COMMENT;
        }
        if (item instanceof TimelineItem.TimelineEvent) {
            return VIEW_TYPE_EVENT;
        }
        if (item instanceof TimelineItem.TimelineReview) {
            return VIEW_TYPE_REVIEW;
        }
        if (item instanceof TimelineItem.Diff) {
            return VIEW_TYPE_DIFF;
        }
        if (item instanceof TimelineItem.Reply) {
            return VIEW_TYPE_REPLY;
        }
        return super.getItemViewType(item);
    }

    @Override
    public void onBindViewHolder(TimelineItemViewHolder holder, TimelineItem item) {
        switch (getItemViewType(item)) {
            case VIEW_TYPE_SYSTEM_NOTE:
                //noinspection unchecked
                holder.bind(item);
                break;
            case VIEW_TYPE_COMMENT:
            case VIEW_TYPE_EVENT:
            case VIEW_TYPE_REVIEW:
            case VIEW_TYPE_DIFF:
            case VIEW_TYPE_REPLY:
                //noinspection unchecked
                holder.bind(item);
                holder.itemView.setAlpha(shouldFadeReplyGroup(item) ? 0.5f : 1f);
                break;
        }
    }

    @Override
    public void onReactionsUpdated(ReactionBar.Item item, GitLabReactions reactions) {
        CommentViewHolder holder = (CommentViewHolder) item;
        holder.updateReactions(reactions);
    }

    private boolean shouldFadeReplyGroup(TimelineItem item) {
        long replyCommentId = mActionCallback.getSelectedReplyCommentId();
        if (replyCommentId == 0) {
            return false;
        }
        if (item instanceof TimelineItem.Diff) {
            return ((TimelineItem.Diff) item).getInitialComment().id() != replyCommentId;
        }
        if (item instanceof TimelineItem.TimelineComment) {
            TimelineItem.TimelineComment tc = (TimelineItem.TimelineComment) item;
            if (tc.getParentDiff() != null) {
                return tc.getParentDiff().getInitialComment().id() != replyCommentId;
            }
            return tc.comment().id() != replyCommentId;
        }
        return false;
    }

    public static abstract class TimelineItemViewHolder<TItem extends TimelineItem> extends
            RecyclerView.ViewHolder {

        protected final Context mContext;

        public TimelineItemViewHolder(View itemView) {
            super(itemView);

            mContext = itemView.getContext();
        }

        public abstract void bind(TItem item);
    }

    /** Minimal view holder for GitLab system notes — no avatar, menu, or reactions. */
    static class SystemNoteViewHolder
            extends TimelineItemViewHolder<TimelineItem.TimelineComment> {
        private final android.widget.TextView tvNote;
        private final android.widget.TextView tvTimestamp;

        SystemNoteViewHolder(android.view.View itemView) {
            super(itemView);
            tvNote = itemView.findViewById(R.id.tv_system_note);
            tvTimestamp = itemView.findViewById(R.id.tv_timestamp);
        }

        @Override
        public void bind(TimelineItem.TimelineComment item) {
            // Prepend author name so it reads "Ashish Gola assigned to @tabish.khan"
            // matching GitLab web's system note format.
            com.gl4a.gitlab.model.GitLabUser author = item.getUser();
            String body = item.comment().body() != null ? item.comment().body() : "";
            if (author != null && author.name() != null && !author.name().isEmpty()) {
                tvNote.setText(author.name() + " " + body);
            } else {
                tvNote.setText(body);
            }
            java.util.Date createdAt = item.getCreatedAt();
            tvTimestamp.setText(com.gl4a.utils.StringUtils.formatRelativeTime(
                    itemView.getContext(), createdAt, true));
        }
    }
}
