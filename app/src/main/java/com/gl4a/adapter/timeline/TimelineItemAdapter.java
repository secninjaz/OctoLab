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

import java.util.ArrayList;
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
        /** Starts (or, if already selected, cancels) a reply to the comment's thread (#123). */
        void replyToThread(GitLabComment comment);
        /** Discussion id of the thread being replied to, or null. */
        String getSelectedReplyDiscussionId();
        /** The MR's current head commit, to tell threads on older versions (#179); null otherwise. */
        default String getMergeRequestHeadSha() { return null; }
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
        public Single<java.util.List<com.gl4a.utils.DiffSnippetLoader.Line>> loadDiffSnippet(
                TimelineItem.TimelineComment comment) {
            return com.gl4a.utils.DiffSnippetLoader.load(mRepoOwner, mRepoName,
                    comment.comment().diffPosition);
        }

        @Override
        public boolean isOutdatedDiff(TimelineItem.TimelineComment comment) {
            String head = mActionCallback.getMergeRequestHeadSha();
            GitLabComment.DiffPosition pos = comment.comment().diffPosition;
            return head != null && pos != null && pos.headSha != null && !head.equals(pos.headSha);
        }

        @Override
        public boolean isThreadCollapsed(TimelineItem.TimelineComment comment) {
            return TimelineItemAdapter.this.isThreadCollapsed(comment.comment());
        }

        @Override
        public void toggleThread(TimelineItem.TimelineComment comment) {
            String id = comment.comment().discussionId();
            if (id == null) return;
            if (isThreadCollapsed(comment)) {
                mCollapsedThreads.remove(id);
                mExpandedThreads.add(id);
            } else {
                mExpandedThreads.remove(id);
                mCollapsedThreads.add(id);
            }
            notifyThreadChanged(id);
        }

        @Override
        public boolean canReplyToThread(TimelineItem.TimelineComment comment) {
            // Offered on every comment of a thread, replies included (each posts into the
            // same thread), and on standalone comments, which start a thread.
            return !mLocked && comment.comment().discussionId() != null
                    && !comment.comment().isSystemNote();
        }

        @Override
        public boolean isReplyThreadSelected(TimelineItem.TimelineComment comment) {
            String selected = mActionCallback.getSelectedReplyDiscussionId();
            return selected != null && selected.equals(comment.comment().discussionId());
        }

        @Override
        public boolean onMenItemClick(TimelineItem.TimelineComment comment, MenuItem menuItem) {
            switch (menuItem.getItemId()) {
                case R.id.reply:
                    mActionCallback.replyToThread(comment.comment());
                    return true;

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

    // The project the notes belong to, for linking their references (#197)
    private String mProjectPath;

    /** The project the notes belong to, for linking their references (#197). */
    public void setProjectPath(String projectPath) {
        mProjectPath = projectPath;
        mImageGetter.setProjectPath(projectPath);
    }

    public void destroy() {
        mImageGetter.destroy();
        mReactionDetailsCache.destroy();
    }

    /**
     * Starts rendering every note's markdown ahead of scrolling, top first, with the same
     * text and cache keys the rows bind with, so rows don't change height as they scroll in
     * (#152). Label/milestone/state events are built locally and need no rendering.
     */
    public void prerenderMarkdown(List<TimelineItem> items) {
        List<android.util.Pair<Object, String>> notes = new ArrayList<>();
        for (TimelineItem item : items) {
            if (!(item instanceof TimelineItem.TimelineComment)) continue;
            TimelineItem.TimelineComment comment = (TimelineItem.TimelineComment) item;
            if (comment.comment().isSystemNote()) {
                if (comment.comment().eventInfo == null) {
                    notes.add(android.util.Pair.create(comment.comment().id(),
                            systemNoteMarkdown(comment.getUser(), comment.comment().body())));
                }
            } else {
                notes.add(android.util.Pair.create(comment.comment().id(),
                        comment.comment().body()));
            }
        }
        mImageGetter.prerenderMarkdown(notes);
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
                holder = new SystemNoteViewHolder(view, mImageGetter, this);
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

    // Threads the user expanded or collapsed; others follow GitLab web: resolved threads start
    // collapsed to their first note, unresolved ones expanded (#179).
    private final java.util.Set<String> mExpandedThreads = new java.util.HashSet<>();
    private final java.util.Set<String> mCollapsedThreads = new java.util.HashSet<>();

    boolean isThreadCollapsed(GitLabComment note) {
        String id = note.discussionId();
        if (id == null) return false;
        if (mExpandedThreads.contains(id)) return false;
        // The thread's state, not the note's: system notes in a thread aren't resolvable.
        return mCollapsedThreads.contains(id) || note.threadResolved || note.resolved;
    }

    /**
     * Whether the row at an adapter position is a reply or system note inside a collapsed
     * thread, which takes no space; the scrollbar counts it as such before it's laid out (#152).
     */
    public boolean isRowCollapsed(int position) {
        int index = position - getAdapterPositionForIndex(0);
        if (index < 0 || index >= getCount()) return false;
        TimelineItem item = getItem(index);
        if (!(item instanceof TimelineItem.TimelineComment)) return false;
        GitLabComment note = ((TimelineItem.TimelineComment) item).comment();
        int threadPosition = note.threadPosition();
        return threadPosition != GitLabComment.THREAD_NONE
                && threadPosition != GitLabComment.THREAD_FIRST && isThreadCollapsed(note);
    }

    /** Expands a thread, e.g. before scrolling to one of its replies from a link. */
    public void expandThread(String discussionId) {
        if (discussionId == null) return;
        mCollapsedThreads.remove(discussionId);
        if (mExpandedThreads.add(discussionId)) notifyThreadChanged(discussionId);
    }

    private void notifyThreadChanged(String discussionId) {
        // Data indices, converted to adapter positions: the header/footer rows aren't items.
        for (int i = 0; i < getCount(); i++) {
            TimelineItem item = getItem(i);
            if (item instanceof TimelineItem.TimelineComment && discussionId.equals(
                    ((TimelineItem.TimelineComment) item).comment().discussionId())) {
                notifyItemChanged(getAdapterPositionForIndex(i));
            }
        }
    }

    /** Whether a timeline row is a reply inside a comment thread (#123). */
    public static boolean isThreadReplyRow(RecyclerView.ViewHolder holder) {
        return holder instanceof CommentViewHolder && ((CommentViewHolder) holder).isThreadReply();
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
            String replyDiscussionId = mActionCallback.getSelectedReplyDiscussionId();
            if (replyDiscussionId != null) {
                // Replying to a thread: keep that whole thread fully visible (#123).
                return !replyDiscussionId.equals(tc.comment().discussionId());
            }
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

    /**
     * Markdown for a system note, prefixed with the author's name ("Ashish Gola assigned to
     * @tabish.khan") like GitLab web. Root-relative link targets such as "Compare with previous
     * version" are made absolute first: GitLab's markdown API resolves them against the
     * repository (/-/blob/main/...) rather than the instance (#165).
     */
    public static String systemNoteMarkdown(com.gl4a.gitlab.model.GitLabUser author,
            String body) {
        String markdown = body != null ? body : "";
        markdown = markdown.replace("](/",
                "](" + com.gl4a.Gl4Application.get().getInstanceUrl() + "/");
        if (author != null && author.name() != null && !author.name().isEmpty()) {
            // Author in bold, like GitLab web (#179).
            markdown = "**" + author.name().replace("*", "\\*") + "** " + markdown;
        }
        return markdown;
    }

    /**
     * Text for a resource event, like GitLab web: "Jay added [New App] [waiting-on-response]
     * labels and removed [bug] label", "set milestone to v1.4", "closed via commit abc12345".
     */
    static CharSequence eventText(android.content.Context context,
            com.gl4a.gitlab.model.GitLabUser user,
            com.gl4a.gitlab.model.GitLabComment.EventInfo info, String projectPath) {
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder();
        if (user != null) {
            // Author in bold, like other system notes and GitLab web (#179).
            text.append(com.gl4a.utils.ApiHelpers.getUserDisplayName(context, user));
            text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    0, text.length(), 0);
            text.append(' ');
        }
        switch (info.kind) {
            case "label":
                if (!info.added.isEmpty()) {
                    text.append(context.getString(R.string.event_added)).append(' ');
                    appendChips(context, text, info.added);
                    text.append(context.getResources().getQuantityString(
                            R.plurals.event_labels, info.added.size()));
                }
                if (!info.removed.isEmpty()) {
                    if (!info.added.isEmpty()) {
                        text.append(' ').append(context.getString(R.string.event_and)).append(' ');
                    }
                    text.append(context.getString(R.string.event_removed)).append(' ');
                    appendChips(context, text, info.removed);
                    text.append(context.getResources().getQuantityString(
                            R.plurals.event_labels, info.removed.size()));
                }
                break;
            case "milestone":
                text.append(context.getString(info.milestoneRemoved
                        ? R.string.event_milestone_removed : R.string.event_milestone_set,
                        info.milestone != null ? info.milestone : ""));
                break;
            default:
                String state = info.state != null ? info.state : "";
                text.append(state);
                if (info.sourceCommit != null && info.sourceCommit.length() >= 8) {
                    String shortSha = info.sourceCommit.substring(0, 8);
                    text.append(' ');
                    int start = text.length();
                    text.append(context.getString(R.string.event_via_commit, shortSha));
                    // The commit links to it, like on GitLab web (#197)
                    int shaStart = text.toString().indexOf(shortSha, start);
                    if (projectPath != null && shaStart >= 0) {
                        text.setSpan(new com.gl4a.widget.LinkSpan(
                                com.gl4a.Gl4Application.get().getInstanceUrl() + "/"
                                        + projectPath + "/-/commit/" + info.sourceCommit),
                                shaStart, shaStart + shortSha.length(), 0);
                    }
                }
                break;
        }
        return text;
    }

    private static void appendChips(android.content.Context context,
            android.text.SpannableStringBuilder text,
            java.util.List<com.gl4a.gitlab.model.GitLabLabel> labels) {
        for (com.gl4a.gitlab.model.GitLabLabel label : labels) {
            int start = text.length();
            text.append(label.name);
            text.setSpan(new com.gl4a.widget.IssueLabelSpan(context, label, true),
                    start, text.length(), 0);
            text.append(' ');
        }
    }

    /** Minimal view holder for GitLab system notes — no avatar, menu, or reactions. */
    static class SystemNoteViewHolder
            extends TimelineItemViewHolder<TimelineItem.TimelineComment> {
        private final android.widget.TextView tvNote;
        private final android.widget.TextView tvTimestamp;
        private final HttpImageGetter mImageGetter;

        private final TimelineItemAdapter mAdapter;
        private final android.view.View mDot;
        private final android.view.View mBody;

        SystemNoteViewHolder(android.view.View itemView, HttpImageGetter imageGetter,
                TimelineItemAdapter adapter) {
            super(itemView);
            tvNote = itemView.findViewById(R.id.tv_system_note);
            tvTimestamp = itemView.findViewById(R.id.tv_timestamp);
            mImageGetter = imageGetter;
            mAdapter = adapter;
            mDot = itemView.findViewById(R.id.sn_dot);
            mBody = itemView.findViewById(R.id.sn_body);
        }

        /**
         * A system note that is part of a thread is drawn inside the thread's card and
         * collapses with it, like GitLab web (#179). Returns false if it's hidden.
         */
        private boolean bindThreadPlacement(GitLabComment note) {
            int position = note.threadPosition();
            boolean inThread = position != GitLabComment.THREAD_NONE;
            boolean hidden = inThread && position != GitLabComment.THREAD_FIRST
                    && mAdapter.isThreadCollapsed(note);
            itemView.setVisibility(hidden ? android.view.View.GONE : android.view.View.VISIBLE);
            android.view.ViewGroup.LayoutParams lp = itemView.getLayoutParams();
            if (lp != null) {
                int height = hidden ? 0 : android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                if (lp.height != height) {
                    lp.height = height;
                    itemView.setLayoutParams(lp);
                }
            }
            if (hidden) return false;
            android.content.res.Resources res = itemView.getResources();
            int gap = res.getDimensionPixelSize(R.dimen.timeline_row_gap);
            int pad = Math.round(12 * res.getDisplayMetrics().density);
            if (inThread) {
                mDot.setVisibility(android.view.View.INVISIBLE);
                mBody.setBackgroundResource(position == GitLabComment.THREAD_FIRST
                        ? R.drawable.timeline_card_top
                        : position == GitLabComment.THREAD_LAST ? R.drawable.timeline_card_bottom
                        : R.drawable.timeline_card_middle);
                mBody.setPadding(pad, pad / 2, pad, pad / 2);
                tvNote.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        R.drawable.timeline_dot, 0, 0, 0);
                tvNote.setCompoundDrawablePadding(pad / 2);
                itemView.setPadding(itemView.getPaddingLeft(),
                        position == GitLabComment.THREAD_FIRST ? gap : 0,
                        itemView.getPaddingRight(),
                        position == GitLabComment.THREAD_LAST ? gap : 0);
            } else {
                mDot.setVisibility(android.view.View.VISIBLE);
                mBody.setBackground(null);
                mBody.setPadding(0, 0, 0, 0);
                tvNote.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
                itemView.setPadding(itemView.getPaddingLeft(), gap, itemView.getPaddingRight(), gap);
            }
            return true;
        }

        @Override
        public void bind(TimelineItem.TimelineComment item) {
            if (!bindThreadPlacement(item.comment())) {
                return;
            }
            // The body is markdown/HTML (lists, links, commit SHAs, references), so render
            // it like a comment (#165).
            if (item.comment().eventInfo != null) {
                // Resource event (#180): built locally, with GitLab-coloured label chips.
                mImageGetter.unbindView(tvNote);
                tvNote.setText(eventText(tvNote.getContext(), item.getUser(),
                        item.comment().eventInfo, mAdapter.mProjectPath));
            } else {
                mImageGetter.bindMarkdown(tvNote,
                        systemNoteMarkdown(item.getUser(), item.comment().body()),
                        item.comment().id());
            }
            java.util.Date createdAt = item.getCreatedAt();
            tvTimestamp.setText(com.gl4a.utils.StringUtils.formatRelativeTime(
                    itemView.getContext(), createdAt, true));
        }
    }
}
