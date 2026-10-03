package com.gl4a.adapter.timeline;
import com.gl4a.gitlab.model.GitLabReaction;
import com.gl4a.gitlab.model.GitLabReactions;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabUser;

import android.content.Context;
import android.content.Intent;
import android.text.SpannableStringBuilder;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.activities.UserActivity;
import com.gl4a.model.TimelineItem;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.HttpImageGetter;
import com.gl4a.utils.StringUtils;
import com.gl4a.utils.UiUtils;
import com.gl4a.widget.ReactionBar;

import java.util.Date;
import java.util.List;

import androidx.annotation.Nullable;
import io.reactivex.Single;

public class CommentViewHolder
        extends TimelineItemAdapter.TimelineItemViewHolder<TimelineItem.TimelineComment>
        implements View.OnClickListener, ReactionBar.Item, ReactionBar.Callback,
        PopupMenu.OnMenuItemClickListener {

    private final Context mContext;
    private final HttpImageGetter mImageGetter;
    private final Callback mCallback;
    private final String mRepoOwner;

    private final ImageView ivGravatar;
    private final TextView tvDesc;
    private android.webkit.WebView mWvTable;
    private final TextView tvExtra;
    private final TextView tvTimestamp;
    private final TextView tvEdited;
    private final ImageView ivMenu;
    private final ReactionBar reactions;
    private final PopupMenu mPopupMenu;
    private final ReactionBar.AddReactionMenuHelper mReactionMenuHelper;

    private TimelineItem.TimelineComment mBoundItem;
    private final View mCard;
    private final ImageView mReplyAvatar;
    private final View mThreadToggle;
    private final ImageView mThreadChevron;
    private final android.view.ViewGroup mThreadAvatars;
    private final TextView mThreadToggleText;
    private final TextView mResolved;
    private final TextView mThreadContext;
    private final TextView mDiffFile;
    private final View mSnippetScroll;
    private final TextView mSnippet;
    private io.reactivex.disposables.Disposable mSnippetLoad;

    private final UiUtils.QuoteActionModeCallback mQuoteActionModeCallback;

    public interface Callback {
        boolean canAddReaction();
        boolean canQuote();
        void quoteText(CharSequence text);
        void addText(CharSequence text);
        boolean onMenItemClick(TimelineItem.TimelineComment comment, MenuItem menuItem);
        /** Whether "Reply" (add a note to this comment's thread) is offered (#123). */
        boolean canReplyToThread(TimelineItem.TimelineComment comment);
        /** Whether the comment's thread can be resolved or unresolved (#206). */
        boolean canResolveThread(TimelineItem.TimelineComment comment);
        boolean isThreadResolved(TimelineItem.TimelineComment comment);
        /** Whether this comment's thread shows only its first note (#179). */
        boolean isThreadCollapsed(TimelineItem.TimelineComment comment);
        void toggleThread(TimelineItem.TimelineComment comment);
        /** The code lines a diff thread is about, as GitLab web shows them (#179). */
        Single<List<com.gl4a.utils.DiffSnippetLoader.Line>> loadDiffSnippet(
                TimelineItem.TimelineComment comment);
        /** Whether a diff thread was started on an older version than the MR's current one. */
        boolean isOutdatedDiff(TimelineItem.TimelineComment comment);
        boolean isReplyThreadSelected(TimelineItem.TimelineComment comment);
        Single<List<GitLabReaction>> loadReactionDetails(TimelineItem.TimelineComment item, boolean bypassCache);
        Single<GitLabReaction> addReaction(TimelineItem.TimelineComment item, String content);
        Single<Boolean> deleteReaction(TimelineItem.TimelineComment item, long reactionId);
    }

    public CommentViewHolder(View view, HttpImageGetter imageGetter, String repoOwner,
            ReactionBar.ReactionDetailsCache reactionDetailsCache, Callback callback) {
        super(view);

        mContext = view.getContext();
        mCard = view.findViewById(R.id.card);
        mReplyAvatar = view.findViewById(R.id.iv_reply_avatar);
        mThreadToggle = view.findViewById(R.id.ll_thread_toggle);
        mThreadChevron = view.findViewById(R.id.iv_thread_chevron);
        mThreadAvatars = view.findViewById(R.id.ll_thread_avatars);
        mThreadToggleText = view.findViewById(R.id.tv_thread_toggle);
        mResolved = view.findViewById(R.id.tv_resolved);
        mThreadContext = view.findViewById(R.id.tv_thread_context);
        mDiffFile = view.findViewById(R.id.tv_diff_file);
        mSnippetScroll = view.findViewById(R.id.sv_diff_snippet);
        mSnippet = view.findViewById(R.id.tv_diff_snippet);
        mImageGetter = imageGetter;
        mCallback = callback;
        mRepoOwner = repoOwner;

        ivGravatar = view.findViewById(R.id.iv_gravatar);
        ivGravatar.setOnClickListener(this);
        tvDesc = view.findViewById(R.id.tv_desc);
        mWvTable = view.findViewById(R.id.wv_table);
        tvExtra = view.findViewById(R.id.tv_extra);
        tvExtra.setOnClickListener(this);
        tvTimestamp = view.findViewById(R.id.tv_timestamp);
        tvEdited = view.findViewById(R.id.tv_edited);
        reactions = view.findViewById(R.id.reactions);
        reactions.setCallback(this, this);
        reactions.setDetailsCache(reactionDetailsCache);
        ivMenu = view.findViewById(R.id.iv_menu);
        ivMenu.setOnClickListener(this);

        mPopupMenu = new PopupMenu(view.getContext(), ivMenu);
        mPopupMenu.getMenuInflater().inflate(R.menu.comment_menu, mPopupMenu.getMenu());
        mPopupMenu.setOnMenuItemClickListener(this);

        MenuItem reactItem = mPopupMenu.getMenu().findItem(R.id.react);
        if (Gl4Application.get().isAuthorized() && callback.canAddReaction()) {
            mPopupMenu.getMenuInflater().inflate(R.menu.reaction_menu, reactItem.getSubMenu());
            mReactionMenuHelper = new ReactionBar.AddReactionMenuHelper(view.getContext(),
                    reactItem.getSubMenu(), this, this, reactionDetailsCache);
        } else {
            reactItem.setVisible(false);
            mReactionMenuHelper = null;
        }

        mQuoteActionModeCallback = new UiUtils.QuoteActionModeCallback(tvDesc) {
            @Override
            public void onTextQuoted(CharSequence text) {
                mCallback.quoteText(text);
            }
        };
    }

    /**
     * Shows "Edited 2 days ago by Jay B" under a comment that was edited, like GitLab web
     * (#151). Only from GraphQL's edit details: REST's updated_at also changes when a note is
     * resolved or re-rendered, and on commit comments when their diff becomes outdated, so it
     * can't tell an edit apart.
     */
    public static void bindEdited(TextView view, GitLabComment comment) {
        GitLabUser editor = comment.lastEditedBy();
        Date editedAt = comment.lastEditedAtDate();
        if (editor == null || editedAt == null) {
            view.setVisibility(View.GONE);
            return;
        }
        Context context = view.getContext();
        String name = !StringUtils.isBlank(editor.name) ? editor.name : editor.username;
        view.setText(context.getString(R.string.comment_edited_by,
                StringUtils.formatRelativeTime(context, editedAt, true), name));
        view.setVisibility(View.VISIBLE);
    }

    @Override
    public void bind(TimelineItem.TimelineComment item) {
        // If rebinding the same comment (scroll back up), keep WebView state as-is to
        // avoid the height-collapse-then-expand jump in RecyclerView.
        boolean sameItem = mBoundItem != null
                && mBoundItem.comment().id() == item.comment().id();
        mBoundItem = item;
        // Replies of a collapsed thread take no space; the first note shows "N replies" (#179).
        GitLabComment threadNote = item.comment();
        boolean collapsed = threadNote.threadPosition() != GitLabComment.THREAD_NONE
                && mCallback.isThreadCollapsed(item);
        boolean replyRow = threadNote.threadPosition() == GitLabComment.THREAD_MIDDLE
                || threadNote.threadPosition() == GitLabComment.THREAD_LAST;
        setRowShown(!(replyRow && collapsed));
        if (replyRow && collapsed) {
            return;
        }
        bindThreadCard(threadNote, collapsed);
        bindThreadToggle(item, collapsed);
        bindResolved(threadNote);
        bindDiffContext(item, collapsed);
        if (!sameItem) {
            // Different comment: hide WebView but do NOT load about:blank — that triggers
            // an extra layout pass. The WebView keeps its previous content invisibly.
            if (mWvTable != null) {
                mWvTable.setVisibility(android.view.View.GONE);
            }
            tvDesc.setVisibility(android.view.View.VISIBLE);
        }

        GitLabUser user = item.getUser();
        Date createdAt = item.getCreatedAt();

        tvExtra.setTag(user);

        AvatarHandler.assignAvatar(ivGravatar, user);
        ivGravatar.setTag(user);

        tvTimestamp.setText(StringUtils.formatRelativeTime(mContext, createdAt, true));
        bindEdited(tvEdited, item.comment());

        // Body — system notes route to SystemNoteViewHolder, not here.
        // GitLab's stored rendering when loaded, as GitLab web shows it (#199)
        mImageGetter.bindMarkdown(tvDesc, item.comment().body(), item.comment().id(),
                item.comment().storedHtml());

        // Extra view
        // Display name in bold, like GitLab web on mobile, which shows no @username (#179).
        SpannableStringBuilder userName = new SpannableStringBuilder(
                user != null && !StringUtils.isBlank(user.name()) ? user.name()
                        : ApiHelpers.getUserNameWithType(mContext, user, true));
        userName.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0, userName.length(), 0);

        String association = getString(item);
        if (association != null) {
            StringUtils.addUserTypeSpan(mContext, userName, userName.length(), association);
        }

        tvExtra.setText(userName);

        if (mCallback.canQuote()) {
            tvDesc.setCustomSelectionActionModeCallback(mQuoteActionModeCallback);
        } else {
            tvDesc.setCustomSelectionActionModeCallback(null);
        }

        ivMenu.setTag(item);

        java.util.Set<String> viewerReacted = item.comment().viewerReactedContents();
        // Reaction details (with IDs, needed to remove a reaction) are only fetched when the
        // user opens the menu, unless the timeline load could not provide reactions (#162).
        if (mReactionMenuHelper != null && viewerReacted == null) {
            mReactionMenuHelper.startLoadingIfNeeded();
        }
        // Show reactions from the comment's own data, loaded with the timeline via GraphQL,
        // instead of fetching them per row while scrolling (#162).
        reactions.setViewerReactedContents(viewerReacted != null
                ? viewerReacted : java.util.Collections.emptySet());
        reactions.setReactions(item.comment().reactions());
        // A details-cache entry is newer (set after add/remove), so it overrides the loaded state.
        reactions.refreshViewerStateFromCache();
        if (item.comment().reactions() == null) {
            // REST fallback for servers without GraphQL. Store the result on the comment it
            // was requested for; only update this row if it still shows that comment.
            final GitLabComment requested = item.comment();
            mCallback.loadReactionDetails(item, false)
                    .subscribe(details -> {
                        requested.withReactionDetails(details,
                                Gl4Application.get().getAuthLogin());
                        if (mBoundItem != item) {
                            return;
                        }
                        reactions.setViewerReactedContents(requested.viewerReactedContents());
                        updateReactions(requested.reactions());
                    }, error -> { /* non-fatal */ });
        }

        String ourLogin = Gl4Application.get().getAuthLogin();
        boolean canEdit = ApiHelpers.loginEquals(user, ourLogin)
                || ApiHelpers.loginEquals(mRepoOwner, ourLogin);

        int position = item.getReviewComment() != null && item.getReviewComment().position() != null
                ? item.getReviewComment().position() : -1;

        Menu menu = mPopupMenu.getMenu();
        menu.findItem(R.id.edit).setVisible(canEdit);
        menu.findItem(R.id.delete).setVisible(canEdit);
        menu.findItem(R.id.view_in_file).setVisible(item.hasFilePatch() && position != -1);
        MenuItem replyItem = menu.findItem(R.id.reply);
        replyItem.setVisible(mCallback.canReplyToThread(item));
        replyItem.setTitle(mCallback.isReplyThreadSelected(item)
                ? R.string.reply_selected : R.string.reply);
        MenuItem resolveItem = menu.findItem(R.id.resolve_thread);
        resolveItem.setVisible(mCallback.canResolveThread(item));
        resolveItem.setTitle(mCallback.isThreadResolved(item)
                ? R.string.unresolve_thread : R.string.resolve_thread);
    }

    private void setRowShown(boolean shown) {
        itemView.setVisibility(shown ? View.VISIBLE : View.GONE);
        android.view.ViewGroup.LayoutParams lp = itemView.getLayoutParams();
        if (lp != null) {
            int height = shown ? android.view.ViewGroup.LayoutParams.WRAP_CONTENT : 0;
            if (lp.height != height) {
                lp.height = height;
                itemView.setLayoutParams(lp);
            }
        }
    }

    /** "▸ 7 replies · Last reply by Jay B · 3 weeks ago" / "▾ Hide replies", like GitLab web. */
    private void bindThreadToggle(TimelineItem.TimelineComment item, boolean collapsed) {
        GitLabComment.ThreadSummary summary = item.comment().threadSummary;
        boolean show = item.comment().threadPosition() == GitLabComment.THREAD_FIRST
                && summary != null;
        mThreadToggle.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        mThreadToggle.setOnClickListener(v -> mCallback.toggleThread(item));
        mThreadChevron.setRotation(collapsed ? -90 : 0);
        mThreadAvatars.removeAllViews();
        mThreadAvatars.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        if (collapsed) {
            int size = Math.round(18 * mContext.getResources().getDisplayMetrics().density);
            int overlap = Math.round(-4 * mContext.getResources().getDisplayMetrics().density);
            for (int i = 0; i < Math.min(3, summary.replyAuthors.size()); i++) {
                ImageView avatar = new ImageView(mContext);
                android.widget.LinearLayout.LayoutParams lp =
                        new android.widget.LinearLayout.LayoutParams(size, size);
                if (i > 0) lp.leftMargin = overlap;
                mThreadAvatars.addView(avatar, lp);
                AvatarHandler.assignAvatar(avatar, summary.replyAuthors.get(i));
            }
            StringBuilder text = new StringBuilder(mContext.getResources().getQuantityString(
                    R.plurals.thread_replies, summary.replyCount, summary.replyCount));
            if (summary.lastReplyAuthor != null) {
                text.append(" · ").append(mContext.getString(R.string.thread_last_reply,
                        displayName(summary.lastReplyAuthor)));
            }
            GitLabComment probe = new GitLabComment();
            probe.createdAt = summary.lastReplyAt;
            if (probe.createdAtDate() != null) {
                text.append(" · ").append(StringUtils.formatRelativeTime(
                        mContext, probe.createdAtDate(), true));
            }
            mThreadToggleText.setText(text);
        } else {
            mThreadToggleText.setText(R.string.thread_hide_replies);
        }
    }

    /** "Resolved 1 day ago by linsui" on a resolved thread, like GitLab web. */
    private void bindResolved(GitLabComment comment) {
        boolean show = comment.resolved && comment.resolvedBy != null
                && comment.threadPosition() != GitLabComment.THREAD_MIDDLE
                && comment.threadPosition() != GitLabComment.THREAD_LAST;
        mResolved.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            CharSequence when = comment.resolvedAtDate() != null
                    ? StringUtils.formatRelativeTime(mContext, comment.resolvedAtDate(), true) : "";
            mResolved.setText(mContext.getString(comment.resolvedByPush
                    ? R.string.thread_resolved_by_push : R.string.thread_resolved_by, when,
                    displayName(comment.resolvedBy)));
        }
    }

    /**
     * Diff threads: "started a thread on (an old version of) the diff", and, while expanded,
     * the file and the code lines the thread is about, like GitLab web (#179).
     */
    private void bindDiffContext(TimelineItem.TimelineComment item, boolean collapsed) {
        GitLabComment comment = item.comment();
        boolean diffThread = comment.diffPosition != null
                && comment.threadPosition() != GitLabComment.THREAD_MIDDLE
                && comment.threadPosition() != GitLabComment.THREAD_LAST;
        mThreadContext.setVisibility(diffThread ? View.VISIBLE : View.GONE);
        if (mSnippetLoad != null) {
            mSnippetLoad.dispose();
            mSnippetLoad = null;
        }
        mSnippetScroll.setVisibility(View.GONE);
        boolean showCode = diffThread && !collapsed;
        mDiffFile.setVisibility(showCode ? View.VISIBLE : View.GONE);
        if (!diffThread) return;
        mThreadContext.setText(mCallback.isOutdatedDiff(item)
                ? R.string.thread_on_old_diff : R.string.thread_on_diff);
        if (!showCode) return;
        GitLabComment.DiffPosition pos = comment.diffPosition;
        mDiffFile.setText(pos.newLine != null || pos.oldPath == null ? pos.newPath : pos.oldPath);
        mSnippetLoad = mCallback.loadDiffSnippet(item)
                .compose(com.gl4a.utils.RxUtils::doInBackground)
                .subscribe(lines -> {
                    if (mBoundItem != item || lines.isEmpty()) return;
                    // Rendered with this row's context so the diff colours follow the app theme.
                    mSnippet.setText(com.gl4a.utils.DiffSnippetLoader.render(mContext, lines));
                    mSnippetScroll.setVisibility(View.VISIBLE);
                }, error -> { /* the thread still shows without the code */ });
    }

    private String displayName(GitLabUser user) {
        return ApiHelpers.getUserDisplayName(mContext, user);
    }

    /** Whether this row is a reply inside a thread (drawn without a divider above, #123). */
    public boolean isThreadReply() {
        return mBoundItem != null && mBoundItem.comment().isThreadReply();
    }

    /**
     * Thread notes share one card, like GitLab web's discussion box (#179): the first note is
     * the card's top, replies its middle/bottom, with no gap between them. Replies show a small
     * avatar in the card header instead of one in the timeline column.
     */
    private void bindThreadCard(GitLabComment comment, boolean collapsed) {
        int position = comment.threadPosition();
        if (collapsed && position == GitLabComment.THREAD_FIRST) {
            // Only the first note is shown, so it's a complete card.
            position = GitLabComment.THREAD_NONE;
        }
        boolean reply = position == GitLabComment.THREAD_MIDDLE
                || position == GitLabComment.THREAD_LAST;
        mCard.setBackgroundResource(position == GitLabComment.THREAD_FIRST
                ? R.drawable.timeline_card_top
                : position == GitLabComment.THREAD_MIDDLE ? R.drawable.timeline_card_middle
                : position == GitLabComment.THREAD_LAST ? R.drawable.timeline_card_bottom
                : R.drawable.timeline_card);
        int gap = mContext.getResources().getDimensionPixelSize(R.dimen.timeline_row_gap);
        itemView.setPaddingRelative(itemView.getPaddingStart(),
                reply ? 0 : gap, itemView.getPaddingEnd(),
                position == GitLabComment.THREAD_FIRST || position == GitLabComment.THREAD_MIDDLE
                        ? 0 : gap);
        ivGravatar.setVisibility(reply ? View.INVISIBLE : View.VISIBLE);
        mReplyAvatar.setVisibility(reply ? View.VISIBLE : View.GONE);
        if (reply) {
            AvatarHandler.assignAvatar(mReplyAvatar, comment.user());
        }
    }

    @Nullable
    private String getString(TimelineItem.TimelineComment item) {
        String authorAssociation = item.comment().authorAssociation();
        if (authorAssociation == null) {
            return null;
        }
        switch (authorAssociation) {
            case "COLLABORATOR": return mContext.getString(R.string.collaborator);
            case "CONTRIBUTOR": return mContext.getString(R.string.contributor);
            case "FIRST_TIME_CONTRIBUTOR": return mContext.getString(R.string.first_time_contributor);
            case "FIRST_TIMER": return mContext.getString(R.string.first_timer);
            case "MEMBER": return mContext.getString(R.string.member);
            case "OWNER": return mContext.getString(R.string.owner);
            default: return null;
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.iv_menu:
                if (mReactionMenuHelper != null) {
                    mReactionMenuHelper.startLoadingIfNeeded();
                }
                mPopupMenu.show();
                break;
            case R.id.iv_gravatar: {
                GitLabUser user = (GitLabUser) v.getTag();
                Intent intent = UserActivity.makeIntent(mContext, user);
                if (intent != null) {
                    mContext.startActivity(intent);
                }
                break;
            }
            case R.id.tv_extra: {
                GitLabUser user = (GitLabUser) v.getTag();
                mCallback.addText(StringUtils.formatMention(mContext, user));
                break;
            }
        }
    }

    @Override
    public boolean onMenuItemClick(MenuItem menuItem) {
        TimelineItem.TimelineComment comment = (TimelineItem.TimelineComment) ivMenu.getTag();
        if (mReactionMenuHelper != null && mReactionMenuHelper.onItemClick(menuItem)) {
            return true;
        }
        return mCallback.onMenItemClick(comment, menuItem);
    }

    @Override
    public Object getCacheKey() {
        return mBoundItem.comment().id();
    }

    public void updateReactions(GitLabReactions reactions) {
        if (mBoundItem != null) {
            mBoundItem.setReactions(reactions);
        }
        this.reactions.setReactions(reactions);
        this.reactions.refreshViewerStateFromCache();
        if (mReactionMenuHelper != null) {
            mReactionMenuHelper.updateMenuItems();
        }
    }

    @Override
    public boolean canAddReaction() {
        return mCallback.canAddReaction();
    }

    @Override
    public Single<List<GitLabReaction>> loadReactionDetails(ReactionBar.Item item, boolean bypassCache) {
        return mCallback.loadReactionDetails(mBoundItem, bypassCache);
    }

    @Override
    public Single<GitLabReaction> addReaction(ReactionBar.Item item, String content) {
        return mCallback.addReaction(mBoundItem, content);
    }

    @Override
    public Single<Boolean> deleteReaction(ReactionBar.Item item, long reactionId) {
        return mCallback.deleteReaction(mBoundItem, reactionId);
    }
}
