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
package com.gl4a.adapter;
import com.gl4a.adapter.timeline.CommentViewHolder;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabGraphQLAwardEmoji;
import com.gl4a.gitlab.model.GitLabGraphQLError;
import com.gl4a.gitlab.model.GitLabReaction;
import com.gl4a.gitlab.model.GitLabReactions;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.service.GitLabGraphQLService;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.UserActivity;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.HttpImageGetter;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.StringUtils;
import com.gl4a.utils.UiUtils;
import com.gl4a.widget.ReactionBar;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.reactivex.Single;

public class CommitCommentAdapter extends RootAdapter<GitLabComment, RecyclerView.ViewHolder>
        implements ReactionBar.Callback, ReactionBar.ReactionDetailsCache.Listener {

    private static final int VIEW_TYPE_COMMENT = CUSTOM_VIEW_TYPE_START;
    private static final int VIEW_TYPE_SYSTEM_NOTE = CUSTOM_VIEW_TYPE_START + 1;
    public interface OnCommentAction {
        void editComment(GitLabComment comment);
        void deleteComment(GitLabComment comment);
        void quoteText(CharSequence text);
        void addText(CharSequence text);
    }

    private final HttpImageGetter mImageGetter;
    private final OnCommentAction mActionCallback;
    private final String mRepoOwner;
    private final String mRepoName;
    private final ReactionBar.ReactionDetailsCache mReactionDetailsCache =
            new ReactionBar.ReactionDetailsCache(this);
    // GitLab's awardEmojiRemove mutation needs the emoji name, not the award emoji's own id
    // (which is all ReactionBar.Callback#deleteReaction gives us) — remember it here.
    private final Map<Long, String> mAwardEmojiNames = new HashMap<>();

    private final ViewHolder.Callback mHolderCallback = new ViewHolder.Callback() {
        @Override
        public boolean onCommentMenuItemClick(GitLabComment item, MenuItem menuItem) {
            switch (menuItem.getItemId()) {
                case R.id.edit:
                    mActionCallback.editComment(item);
                    return true;

                case R.id.delete:
                    mActionCallback.deleteComment(item);
                    return true;

                case R.id.share:
                    String subject = mContext.getString(R.string.share_commit_comment_subject,
                            item.id(), mRepoOwner + "/" + mRepoName);
                    IntentUtils.share(mContext, subject, Uri.parse(item.htmlUrl()));
                    return true;
            }
            return false;
        }

        @Override
        public void quoteText(CharSequence text) {
            mActionCallback.quoteText(text);
        }
    };

    public CommitCommentAdapter(Context context, String repoOwner, String repoName,
            OnCommentAction actionCallback) {
        super(context);
        mImageGetter = new HttpImageGetter(context);
        mRepoOwner = repoOwner;
        mRepoName = repoName;
        mActionCallback = actionCallback;
    }

    public void destroy() {
        mReactionDetailsCache.destroy();
        mImageGetter.destroy();
    }

    public void resume() {
        mImageGetter.resume();
    }

    public void pause() {
        mImageGetter.pause();
    }

    public Set<GitLabUser> getUsers() {
        final HashSet<GitLabUser> users = new HashSet<>();
        for (int i = 0; i < getCount(); i++) {
            final GitLabUser user = getItem(i).user();
            if (user != null) {
                users.add(user);
            }
        }
        return users;
    }

    @Override
    public void clear() {
        super.clear();
        mImageGetter.clearHtmlCache();
        mReactionDetailsCache.clear();
        mAwardEmojiNames.clear();
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.iv_gravatar) {
            GitLabUser user = (GitLabUser) v.getTag();
            Intent intent = UserActivity.makeIntent(mContext, user);
            if (intent != null) {
                mContext.startActivity(intent);
            }
        } else if (v.getId() == R.id.tv_extra) {
            GitLabUser user = (GitLabUser) v.getTag();
            mActionCallback.addText(StringUtils.formatMention(mContext, user));
        } else {
            super.onClick(v);
        }
    }

    @Override
    protected int getItemViewType(GitLabComment item) {
        return item.isSystemNote() ? VIEW_TYPE_SYSTEM_NOTE : VIEW_TYPE_COMMENT;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(LayoutInflater inflater, ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_SYSTEM_NOTE) {
            View v = inflater.inflate(R.layout.row_system_note, parent, false);
            return new SystemNoteViewHolder(v, mImageGetter);
        }
        View v = inflater.inflate(R.layout.row_timeline_comment, parent, false);
        ViewHolder holder = new ViewHolder(v, mHolderCallback, this, mReactionDetailsCache);
        holder.ivGravatar.setOnClickListener(this);
        holder.tvExtra.setOnClickListener(this);
        return holder;
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, GitLabComment item) {
        if (holder instanceof SystemNoteViewHolder) {
            ((SystemNoteViewHolder) holder).bind(item);
            return;
        }
        bindComment((ViewHolder) holder, item);
    }

    private void bindComment(ViewHolder holder, GitLabComment item) {
        final GitLabUser user = item.user();
        final Date createdAt = item.createdAtDate();

        holder.mBoundItem = item;

        AvatarHandler.assignAvatar(holder.ivGravatar, user);
        holder.ivGravatar.setTag(user);

        holder.tvTimestamp.setText(StringUtils.formatRelativeTime(mContext, createdAt, true));
        CommentViewHolder.bindEdited(holder.tvEdited, item);

        // System notes have no note id in some responses; fall back to createdAt so each
        // comment still gets its own ObjectInfo in HttpImageGetter.
        Object cacheKey = item.id() != 0 ? item.id()
                : (item.createdAt() != null ? item.createdAt() : System.identityHashCode(item));
        mImageGetter.bindMarkdown(holder.tvDesc, item.body(), cacheKey, item.storedHtml());

        final SpannableStringBuilder login = ApiHelpers.getUserNameWithType(mContext, user, true);
        holder.tvExtra.setText(login);
        holder.tvExtra.setTag(user);

        // Show reactions from the comment's own data, loaded with the comments via GraphQL,
        // the same way as issue/MR comments (CommentViewHolder). Reaction details (needed
        // to remove a reaction) are only fetched when the user opens the menu, unless the
        // load could not provide reactions.
        Set<String> viewerReacted = item.viewerReactedContents();
        if (holder.mReactionMenuHelper != null && viewerReacted == null) {
            holder.mReactionMenuHelper.startLoadingIfNeeded();
        }
        holder.reactions.setViewerReactedContents(viewerReacted != null
                ? viewerReacted : java.util.Collections.emptySet());
        holder.reactions.setReactions(item.reactions());
        // A details-cache entry is newer (set after add/remove), so it overrides the loaded state.
        holder.reactions.refreshViewerStateFromCache();

        String ourLogin = Gl4Application.get().getAuthLogin();
        MenuItem editMenuItem = holder.mPopupMenu.getMenu().findItem(R.id.edit);
        MenuItem deleteMenuItem = holder.mPopupMenu.getMenu().findItem(R.id.delete);
        MenuItem reactMenuItem = holder.mPopupMenu.getMenu().findItem(R.id.react);

        // Edit/delete for commit comments is a separate, not-yet-implemented feature —
        // leave those hidden. Reactions are supported via GraphQL, since GitLab's REST API
        // has no award-emoji endpoint for commit comment notes (see the Callback methods below).
        editMenuItem.setVisible(false);
        deleteMenuItem.setVisible(false);
        reactMenuItem.setVisible(true);
        holder.ivMenu.setVisibility(View.VISIBLE);
    }

    @Override
    public Single<List<GitLabReaction>> loadReactionDetails(ReactionBar.Item item, boolean bypassCache) {
        long noteId = ((ViewHolder) item).mBoundItem.id();
        Map<String, Object> body = new HashMap<>();
        body.put("query", "query { note(id: \"" + noteGid(noteId) + "\") { awardEmoji { nodes { "
                + "name user { id username name avatarUrl } } } } }");

        return ServiceFactory.getGraphQL(GitLabGraphQLService.class).queryNoteAwardEmoji(body)
                .map(ApiHelpers::throwOnFailure)
                .map(response -> {
                    throwOnGraphQLErrors(response.errors);
                    List<GitLabGraphQLAwardEmoji> nodes = response.nodes();
                    List<GitLabReaction> reactions = new ArrayList<>();
                    if (nodes != null) {
                        for (GitLabGraphQLAwardEmoji node : nodes) {
                            GitLabReaction reaction = node.toGitLabReaction();
                            mAwardEmojiNames.put(reaction.id(), reaction.name);
                            reactions.add(reaction);
                        }
                    }
                    return reactions;
                });
    }

    @Override
    public boolean canAddReaction() {
        return true;
    }

    @Override
    public Single<GitLabReaction> addReaction(ReactionBar.Item item, String content) {
        long noteId = ((ViewHolder) item).mBoundItem.id();
        String emojiName = mapContentToEmojiName(content);
        Map<String, Object> body = new HashMap<>();
        body.put("query", "mutation { result: awardEmojiAdd(input: { awardableId: \""
                + noteGid(noteId) + "\", name: \"" + emojiName + "\" }) { errors awardEmoji { "
                + "name user { id username name avatarUrl } } } }");

        return ServiceFactory.getGraphQL(GitLabGraphQLService.class).awardEmojiAdd(body)
                .map(ApiHelpers::throwOnFailure)
                .map(response -> {
                    throwOnGraphQLErrors(response.errors);
                    throwOnUserErrors(response.userErrors());
                    GitLabGraphQLAwardEmoji awardEmoji = response.awardEmoji();
                    if (awardEmoji == null) {
                        throw new IllegalStateException("awardEmojiAdd returned no award emoji");
                    }
                    GitLabReaction reaction = awardEmoji.toGitLabReaction();
                    mAwardEmojiNames.put(reaction.id(), reaction.name);
                    return reaction;
                });
    }

    @Override
    public Single<Boolean> deleteReaction(ReactionBar.Item item, long reactionId) {
        long noteId = ((ViewHolder) item).mBoundItem.id();
        String emojiName = mAwardEmojiNames.remove(reactionId);
        if (emojiName == null) {
            return Single.error(new IllegalStateException("Unknown reaction id " + reactionId));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("query", "mutation { result: awardEmojiRemove(input: { awardableId: \""
                + noteGid(noteId) + "\", name: \"" + emojiName + "\" }) { errors } }");

        return ServiceFactory.getGraphQL(GitLabGraphQLService.class).awardEmojiRemove(body)
                .map(ApiHelpers::throwOnFailure)
                .map(response -> {
                    throwOnGraphQLErrors(response.errors);
                    throwOnUserErrors(response.userErrors());
                    return true;
                });
    }

    private static String noteGid(long noteId) {
        return "gid://gitlab/Note/" + noteId;
    }

    private static void throwOnGraphQLErrors(List<GitLabGraphQLError> errors) {
        if (errors != null && !errors.isEmpty()) {
            throw new RuntimeException(errors.get(0).message);
        }
    }

    private static void throwOnUserErrors(List<String> errors) {
        if (errors != null && !errors.isEmpty()) {
            throw new RuntimeException(errors.get(0));
        }
    }

    /** Mirrors IssueFragmentBase#mapContentToEmojiName — duplicated locally since this
     * adapter doesn't share a base class with the issue/MR reaction implementations. */
    private static String mapContentToEmojiName(String content) {
        if (content == null) return "thumbsup";
        switch (content) {
            case "+1":      return "thumbsup";
            case "-1":      return "thumbsdown";
            case "laugh":   return "laughing";
            case "hooray":  return "tada";
            case "heart":   return "heart";
            case "confused":return "confused";
            case "rocket":  return "rocket";
            case "eyes":    return "eyes";
            default:        return content;
        }
    }

    @Override
    public void onReactionsUpdated(ReactionBar.Item item, GitLabReactions reactions) {
        ViewHolder holder = (ViewHolder) item;
        holder.mBoundItem = holder.mBoundItem.withReactions(reactions);
        holder.reactions.setReactions(reactions);
        holder.reactions.refreshViewerStateFromCache();
        if (holder.mReactionMenuHelper != null) {
            holder.mReactionMenuHelper.updateMenuItems();
        }
    }

    static class SystemNoteViewHolder extends RecyclerView.ViewHolder {
        private final android.widget.TextView tvNote;
        private final android.widget.TextView tvTimestamp;

        private final HttpImageGetter mImageGetter;

        SystemNoteViewHolder(View itemView, HttpImageGetter imageGetter) {
            super(itemView);
            tvNote = itemView.findViewById(R.id.tv_system_note);
            tvTimestamp = itemView.findViewById(R.id.tv_timestamp);
            mImageGetter = imageGetter;
        }

        void bind(GitLabComment item) {
            // System note bodies are markdown/HTML; render them like comments (#165).
            mImageGetter.bindMarkdown(tvNote, com.gl4a.adapter.timeline.TimelineItemAdapter
                    .systemNoteMarkdown(item.user(), item.body()), item.id(),
                    com.gl4a.adapter.timeline.TimelineItemAdapter.systemNoteHtml(item.user(),
                            item.storedHtml()));
            tvTimestamp.setText(StringUtils.formatRelativeTime(
                    itemView.getContext(), item.createdAtDate(), true));
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder implements
            View.OnClickListener, PopupMenu.OnMenuItemClickListener, ReactionBar.Item {
        private interface Callback {
            boolean onCommentMenuItemClick(GitLabComment comment, MenuItem item);
            void quoteText(CharSequence text);
        }

        private ViewHolder(View view, Callback callback,
                ReactionBar.Callback reactionCallback,
                ReactionBar.ReactionDetailsCache reactionDetailsCache) {
            super(view);
            mCallback = callback;

            ivGravatar = view.findViewById(R.id.iv_gravatar);
            tvDesc = view.findViewById(R.id.tv_desc);
            tvDesc.setCustomSelectionActionModeCallback(new UiUtils.QuoteActionModeCallback(tvDesc) {
                @Override
                public void onTextQuoted(CharSequence text) {
                    mCallback.quoteText(text);
                }
            });

            tvExtra = view.findViewById(R.id.tv_extra);
            tvTimestamp = view.findViewById(R.id.tv_timestamp);
            tvEdited = view.findViewById(R.id.tv_edited);
            ivMenu = view.findViewById(R.id.iv_menu);
            ivMenu.setOnClickListener(this);
            reactions = view.findViewById(R.id.reactions);
            reactions.setCallback(reactionCallback, this);
            reactions.setDetailsCache(reactionDetailsCache);

            mPopupMenu = new PopupMenu(view.getContext(), ivMenu);
            mPopupMenu.getMenuInflater().inflate(R.menu.comment_menu, mPopupMenu.getMenu());
            mPopupMenu.setOnMenuItemClickListener(this);

            MenuItem reactItem = mPopupMenu.getMenu().findItem(R.id.react);
            mPopupMenu.getMenuInflater().inflate(R.menu.reaction_menu, reactItem.getSubMenu());

            mReactionMenuHelper = new ReactionBar.AddReactionMenuHelper(view.getContext(),
                    reactItem.getSubMenu(), reactionCallback, this, reactionDetailsCache);
        }

        private final ImageView ivGravatar;
        private final TextView tvDesc;
        private final TextView tvExtra;
        private final TextView tvTimestamp;
        private final TextView tvEdited;
        final ImageView ivMenu;
        private final ReactionBar reactions;
        private final PopupMenu mPopupMenu;
        private final Callback mCallback;

        private final ReactionBar.AddReactionMenuHelper mReactionMenuHelper;
        protected GitLabComment mBoundItem;

        @Override
        public Object getCacheKey() {
            return mBoundItem.id();
        }

        @Override
        public void onClick(View v) {
            if (v.getId() == R.id.iv_menu) {
                if (mReactionMenuHelper != null) {
                    mReactionMenuHelper.startLoadingIfNeeded();
                }
                mPopupMenu.show();
            }
        }

        @Override
        public boolean onMenuItemClick(MenuItem menuItem) {
            if (mReactionMenuHelper != null && mReactionMenuHelper.onItemClick(menuItem)) {
                return true;
            }
            return mCallback.onCommentMenuItemClick(mBoundItem, menuItem);
        }
    }
}
