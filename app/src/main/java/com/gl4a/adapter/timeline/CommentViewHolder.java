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

class CommentViewHolder
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
    private final TextView tvEditTimestamp;
    private final ImageView ivMenu;
    private final ReactionBar reactions;
    private final PopupMenu mPopupMenu;
    private final ReactionBar.AddReactionMenuHelper mReactionMenuHelper;

    private TimelineItem.TimelineComment mBoundItem;

    private final UiUtils.QuoteActionModeCallback mQuoteActionModeCallback;

    public interface Callback {
        boolean canAddReaction();
        boolean canQuote();
        void quoteText(CharSequence text);
        void addText(CharSequence text);
        boolean onMenItemClick(TimelineItem.TimelineComment comment, MenuItem menuItem);
        Single<List<GitLabReaction>> loadReactionDetails(TimelineItem.TimelineComment item, boolean bypassCache);
        Single<GitLabReaction> addReaction(TimelineItem.TimelineComment item, String content);
        Single<Boolean> deleteReaction(TimelineItem.TimelineComment item, long reactionId);
    }

    public CommentViewHolder(View view, HttpImageGetter imageGetter, String repoOwner,
            ReactionBar.ReactionDetailsCache reactionDetailsCache, Callback callback) {
        super(view);

        mContext = view.getContext();
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
        tvEditTimestamp = view.findViewById(R.id.tv_edit_timestamp);
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

    @Override
    public void bind(TimelineItem.TimelineComment item) {
        // If rebinding the same comment (scroll back up), keep WebView state as-is to
        // avoid the height-collapse-then-expand jump in RecyclerView.
        boolean sameItem = mBoundItem != null
                && mBoundItem.comment().id() == item.comment().id();
        mBoundItem = item;
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
        Date updatedAt = item.comment().updatedAtDate();

        tvExtra.setTag(user);

        AvatarHandler.assignAvatar(ivGravatar, user);
        ivGravatar.setTag(user);

        tvTimestamp.setText(StringUtils.formatRelativeTime(mContext, createdAt, true));
        if (createdAt == null || updatedAt == null || createdAt.equals(updatedAt) || item.getReviewComment() != null) {
            // Unlike issue comments, the update timestamp for commit comments also changes
            // when e.g. the line number changes due to the diff the comment was made on
            // becoming outdated. As we can't distinguish those updates from comment body
            // updates, hide the edit timestamp for all commit comments.
            tvEditTimestamp.setVisibility(View.GONE);
        } else {
            tvEditTimestamp.setText(StringUtils.formatRelativeTime(mContext, updatedAt, true));
            tvEditTimestamp.setVisibility(View.VISIBLE);
        }

        // Body — system notes route to SystemNoteViewHolder, not here.
        mImageGetter.bindMarkdown(tvDesc, item.comment().body(), item.comment().id());

        // Extra view
        SpannableStringBuilder userName = ApiHelpers.getUserLoginWithType(mContext, user, true);

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

        // Pre-warm the reaction details cache so icons are ready before the user opens
        // the three-dot > Add reaction submenu. Starts a fetch if cache is cold; no-op
        // if cache already has data for this comment.
        if (mReactionMenuHelper != null) {
            mReactionMenuHelper.startLoadingIfNeeded();
        }
        // Clear any viewer-reaction tinting from a previous binding before setting new state.
        reactions.setViewerReactedContents(java.util.Collections.emptySet());
        // Show cached reactions immediately; fetch from API if not yet loaded
        reactions.setReactions(item.comment().reactions());
        // Sync viewer state from cache immediately (no-op if cache has no entry for this item).
        reactions.refreshViewerStateFromCache();
        if (item.comment().reactions() == null) {
            mCallback.loadReactionDetails(item, false)
                    .subscribe(details -> {
                        if (!details.isEmpty()) {
                            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
                            String ownLogin = com.gl4a.Gl4Application.get().getAuthLogin();
                            java.util.Set<String> viewerReacted = new java.util.HashSet<>();
                            for (com.gl4a.gitlab.model.GitLabReaction r : details) {
                                counts.merge(r.name, 1, Integer::sum);
                                if (com.gl4a.utils.ApiHelpers.loginEquals(r.user(), ownLogin)) {
                                    viewerReacted.add(r.content());
                                }
                            }
                            com.gl4a.gitlab.model.GitLabReactions agg =
                                    com.gl4a.gitlab.model.GitLabReactions.builder()
                                    .plusOne(counts.getOrDefault("thumbsup", 0))
                                    .minusOne(counts.getOrDefault("thumbsdown", 0))
                                    .laugh(counts.getOrDefault("laughing", 0))
                                    .hooray(counts.getOrDefault("tada", 0))
                                    .heart(counts.getOrDefault("heart", 0))
                                    .confused(counts.getOrDefault("confused", 0))
                                    .rocket(counts.getOrDefault("rocket", 0))
                                    .eyes(counts.getOrDefault("eyes", 0))
                                    .build();
                            updateReactions(agg);
                            reactions.setViewerReactedContents(viewerReacted);
                        }
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
