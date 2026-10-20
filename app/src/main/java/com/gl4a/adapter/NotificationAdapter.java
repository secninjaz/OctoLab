package com.gl4a.adapter;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.gl4a.R;
import com.gl4a.activities.RepositoryActivity;
import com.gl4a.activities.UserActivity;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabTodo;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.model.NotificationHolder;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.StringUtils;

public class NotificationAdapter extends
        RootAdapter<NotificationHolder, NotificationAdapter.ViewHolder> {
    private static final int VIEW_TYPE_NOTIFICATION_HEADER = RootAdapter.CUSTOM_VIEW_TYPE_START + 1;
    public static final String SUBJECT_ISSUE = "Issue";
    public static final String SUBJECT_MERGE_REQUEST = "MergeRequest";
    public static final String SUBJECT_COMMIT = "Commit";
    public static final String SUBJECT_RELEASE = "Release";
    public static final String SUBJECT_DISCUSSION = "Discussion";

    public interface OnNotificationActionCallback {
        void markAsRead(NotificationHolder notificationHolder);

        /** The row menu's "Delete" (Participating, #280); shown when it has a title. */
        default void remove(NotificationHolder notificationHolder) {
        }

        /** A project header's ▾/▴: expand or collapse its group (#288). */
        default void toggleGroup(NotificationHolder header) {
        }
    }

    private final int mBottomMargin;
    private final Context mContext;
    private final OnNotificationActionCallback mActionCallback;

    public NotificationAdapter(Context context, OnNotificationActionCallback actionCallback) {
        super(context);
        mContext = context;
        mActionCallback = actionCallback;

        mBottomMargin = context.getResources().getDimensionPixelSize(R.dimen.card_margin);
        int surface = androidx.core.content.ContextCompat.getColor(context, R.color.theme_surface);
        mReadAvatarDim = (surface & 0x00FFFFFF) | 0x80000000;
    }

    /** The list's colour at half strength, over a read row's avatar (#285). */
    private final int mReadAvatarDim;

    public boolean hasUnreadNotifications() {
        for (int i = 0; i < getCount(); i++) {
            NotificationHolder item = getItem(i);

            if (item.notification != null && !item.isRead()) {
                return true;
            }
        }

        return false;
    }

    public boolean markAsRead(@Nullable GitLabProject repository,
            @Nullable GitLabTodo notification) {
        NotificationHolder previousRepoItem = null;
        int unreadNotificationsInSameRepoCount = 0;
        boolean hasReadEverything = true;

        boolean isMarkingSingleNotification = repository == null && notification != null;

        for (int i = 0; i < getCount(); i++) {
            NotificationHolder item = getItem(i);

            // Passing both repository and notification as null will mark everything as read
            if ((repository == null && notification == null)
                    || (repository != null && item.repository.equals(repository))
                    || (item.notification != null && item.notification.equals(notification))) {
                item.setIsRead(true);
            }

            // When marking single notification as read also mark the repository if it contained
            // only 1 unread notification
            if (isMarkingSingleNotification) {
                if (item.notification == null) {
                    if (previousRepoItem != null && unreadNotificationsInSameRepoCount == 0
                            && previousRepoItem.repository.equals(notification.repository())) {
                        previousRepoItem.setIsRead(true);
                    }
                    previousRepoItem = item;
                    unreadNotificationsInSameRepoCount = 0;
                } else if (!item.isRead()) {
                    unreadNotificationsInSameRepoCount += 1;
                }
            }

            if (item.notification != null && !item.isRead()) {
                hasReadEverything = false;
            }
        }

        // Additional check for the very last notification
        if (isMarkingSingleNotification && previousRepoItem != null
                && unreadNotificationsInSameRepoCount == 0
                && previousRepoItem.repository.equals(notification.repository())) {
            previousRepoItem.setIsRead(true);
        }

        notifyDataSetChanged();
        return hasReadEverything;
    }

    @Override
    protected ViewHolder onCreateViewHolder(LayoutInflater inflater, ViewGroup parent,
            int viewType) {
        int layoutResId = viewType == VIEW_TYPE_NOTIFICATION_HEADER
                ? R.layout.row_notification_header
                // THE feed row, as Your Activity uses it (#287)
                : R.layout.row_feed_item;
        View v = inflater.inflate(layoutResId, parent, false);
        return new ViewHolder(v, mActionCallback);
    }

    /** The row menu's removing item, e.g. "Delete" (#280); none when 0, as on To-dos (#289). */
    private int mRemoveTitle = 0;

    public void setRemoveTitle(int titleResId) {
        mRemoveTitle = titleResId;
    }

    /** Not grouped by project: each row names its project (#287). */
    private boolean mShowProject;

    public void setShowProject(boolean showProject) {
        mShowProject = showProject;
    }

    /** Whether the row at an adapter position is a project header (no timeline line, #287). */
    public boolean isHeaderAt(int adapterPosition) {
        return adapterPosition >= 0 && adapterPosition < getItemCount()
                && getItemViewType(adapterPosition) == VIEW_TYPE_NOTIFICATION_HEADER;
    }

    @Override
    protected int getItemViewType(NotificationHolder item) {
        if (item.notification == null) {
            return VIEW_TYPE_NOTIFICATION_HEADER;
        }
        return super.getItemViewType(item);
    }

    @Override
    protected void onBindViewHolder(ViewHolder holder, NotificationHolder item) {
        holder.ivAction.setTag(item);

        float alpha = item.isRead() ? 0.5f : 1f;
        holder.tvTitle.setAlpha(alpha);

        if (item.notification == null) {
            holder.ivAction.setVisibility(item.isRead() ? View.GONE : View.VISIBLE);
            if (holder.ivExpand != null) {
                // Kept in its column (above the rows' ⋮) even when there's nothing to expand
                holder.ivExpand.setVisibility(item.canExpand ? View.VISIBLE : View.INVISIBLE);
                holder.ivExpand.setImageResource(item.expanded
                        ? R.drawable.drop_up_arrow_white : R.drawable.drop_down_arrow_white);
                holder.ivExpand.setContentDescription(mContext.getString(item.expanded
                        ? R.string.feed_group_collapse : R.string.feed_group_expand));
                holder.ivExpand.setTag(item);
            }
            if (holder.headerBar != null) {
                holder.headerBar.setTag(item);
                holder.tvTitle.setTag(item.repository);
            }
            holder.tvTitle.setText(ApiHelpers.formatRepoName(mContext, item.repository));

            // The one shared project logo (#176); it fetches the avatar itself when the Todos
            // API's slim project data doesn't include it.
            if (item.repository != null) {
                AvatarHandler.assignProjectLogo(holder.ivAvatar, item.repository);
                // Tag with the project so the click handler opens RepositoryActivity,
                // not UserActivity (which would search for a user named after the project).
                holder.ivAvatar.setTag(item.repository);
            }
            holder.ivAvatar.setAlpha(alpha);
            return;
        }

        holder.ivAction.setVisibility(View.VISIBLE);
        GitLabTodo todo = item.notification;
        // What GitLab sends for the to-do, as Your Activity shows an event (#287): who, with
        // their avatar, what and where with links, the item's title and the comment
        GitLabUser author = todo.author;
        if (author != null) {
            AvatarHandler.assignAvatar(holder.ivGravatar, author);
        } else {
            holder.ivGravatar.setImageResource(R.drawable.default_avatar);
        }
        holder.ivGravatar.setTag(author);
        holder.tvActor.setText(author != null
                ? ApiHelpers.getUserNameWithType(mContext, author)
                : mContext.getString(R.string.app_name));
        holder.tvTimestamp.setText(StringUtils.formatRelativeTime(mContext,
                todo.createdAt, true));

        int iconResId = com.gl4a.utils.FeedTypeIcons.forTarget(todo.type());
        if (iconResId > 0) {
            holder.ivIcon.setImageResource(iconResId);
            holder.ivIcon.setVisibility(View.VISIBLE);
        } else {
            holder.ivIcon.setVisibility(View.GONE);
        }

        holder.tvTitle.setText(actionLine(todo));
        String title = todo.title();
        holder.tvSubtitle.setText(title);
        holder.tvSubtitle.setVisibility(title != null && !title.isEmpty()
                ? View.VISIBLE : View.GONE);
        String body = todo.body != null ? todo.body.trim() : "";
        boolean showBody = !body.isEmpty() && !body.equals(title);
        holder.tvDesc.setText(showBody ? body : null);
        holder.tvDesc.setVisibility(showBody ? View.VISIBLE : View.GONE);

        for (View view : new View[] { holder.tvActor,
                holder.tvTimestamp, holder.tvTitle, holder.tvSubtitle, holder.tvDesc }) {
            view.setAlpha(alpha);
        }
        // The avatar stays solid, dimmed towards the list's colour: half see-through, it
        // showed the timeline line through it (#285)
        if (item.isRead()) {
            holder.ivGravatar.setColorFilter(mReadAvatarDim,
                    android.graphics.PorterDuff.Mode.SRC_ATOP);
        } else {
            holder.ivGravatar.clearColorFilter();
        }
        // Only the type icon fades: its badge stays solid, not showing the avatar through (#288)
        holder.ivIcon.setImageAlpha(Math.round(255 * alpha));
        android.view.Menu menu = holder.mPopupMenu.getMenu();
        menu.findItem(R.id.mark_as_read).setVisible(!item.isRead());
        menu.findItem(R.id.unsubscribe).setVisible(
                com.gl4a.utils.FeedItemActions.canUnsubscribe(todo));
        menu.findItem(R.id.remove).setVisible(mRemoveTitle != 0);
        if (mRemoveTitle != 0) menu.findItem(R.id.remove).setTitle(mRemoveTitle);
    }

    /**
     * "mentioned you in Issue #243", the reference linking to the issue or merge request itself,
     * and, when not grouped by project, " · group/project" linking to it (#287).
     */
    private CharSequence actionLine(GitLabTodo todo) {
        android.text.SpannableStringBuilder line =
                new android.text.SpannableStringBuilder(formatActionText(todo, false).trim());
        String reference = referenceText(todo);
        String targetUrl = todo.target != null && todo.target.webUrl != null
                ? todo.target.webUrl
                : todo.targetUrl != null ? todo.targetUrl.replaceFirst("#.*$", "") : null;
        int at = reference.isEmpty() ? -1 : line.toString().lastIndexOf(reference);
        if (at >= 0 && targetUrl != null) {
            line.setSpan(new com.gl4a.widget.LinkSpan(targetUrl), at, at + reference.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        GitLabProject project = todo.repository();
        if (mShowProject && project != null) {
            String name = ApiHelpers.formatRepoName(mContext, project).toString();
            line.append(" \u00b7 ");
            int start = line.length();
            line.append(name);
            if (project.webUrl != null) {
                line.setSpan(new com.gl4a.widget.LinkSpan(project.webUrl), start, line.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return line;
    }

    /** "Issue #243", "MR #12", or a commit's short SHA, as the action line names the target. */
    private static String referenceText(GitLabTodo todo) {
        String typeLabel = typeLabel(todo.targetType);
        if (todo.isCommit() && todo.commitSha != null) {
            return typeLabel + " " + todo.commitSha.substring(0, Math.min(8,
                    todo.commitSha.length()));
        }
        return typeLabel.isEmpty() || todo.target == null || todo.target.iid <= 0 ? ""
                : typeLabel + " #" + todo.target.iid;
    }

    private static String typeLabel(String targetType) {
        if ("Issue".equals(targetType) || "WorkItem".equals(targetType)) return "Issue";
        if ("MergeRequest".equals(targetType)) return "MR";
        if ("Commit".equals(targetType)) return "Commit";
        if ("Epic".equals(targetType)) return "Epic";
        return "";
    }

    /** Formats the action line matching GitLab web: "Jay mentioned you in Issue #34" */
    private String formatActionText(com.gl4a.gitlab.model.GitLabTodo todo, boolean withAuthor) {
        // The author has their own line in the feed row (#287)
        String authorName = withAuthor && todo.author != null
                ? com.gl4a.utils.ApiHelpers.getUserDisplayName(mContext, todo.author) : "";

        String targetType = todo.targetType;
        // Only a known target is named; GitLab's own to-dos (e.g. Duo access) have none and
        // were labelled "Issue" (#212)
        String typeLabel = "";
        if ("Issue".equals(targetType) || "WorkItem".equals(targetType)) typeLabel = "Issue";
        else if ("MergeRequest".equals(targetType)) typeLabel = "MR";
        else if ("Commit".equals(targetType)) typeLabel = "Commit";
        else if ("Epic".equals(targetType)) typeLabel = "Epic";

        String iidPart = (todo.target != null && todo.target.iid > 0)
                ? " #" + todo.target.iid : "";
        if (todo.isCommit() && todo.commitSha != null) {
            iidPart = " " + todo.commitSha.substring(0, Math.min(8, todo.commitSha.length()));
        }

        String action = todo.actionName != null ? todo.actionName : "";
        String verb;
        switch (action) {
            case "mentioned":           verb = "mentioned you in";       break;
            case "directly_addressed":  verb = "mentioned you in";       break;
            case "assigned":            verb = "assigned you to";        break;
            case "review_requested":    verb = "requested your review on"; break;
            // A participating comment (#267, #280); GitLab's to-dos have no such action
            case "commented":           verb = "commented on";           break;
            case "approval_required":   verb = "requested your approval on"; break;
            case "build_failed":
                return "Build failed on " + typeLabel + iidPart;
            case "unmergeable":
                return typeLabel + iidPart + " cannot be merged";
            case "marked":
                return authorName + " marked " + typeLabel + iidPart + " as a to-do";
            case "merge_train_removed":
                return typeLabel + iidPart + " was removed from the merge train";
            default:
                verb = action.replace('_', ' ');
        }
        String target = typeLabel.isEmpty() ? "" : " " + typeLabel + iidPart;
        return (authorName + " " + verb + target).trim();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener,
            PopupMenu.OnMenuItemClickListener {
        public ViewHolder(View view, OnNotificationActionCallback actionCallback) {
            super(view);
            mActionCallback = actionCallback;

            ivAction = view.findViewById(R.id.iv_action);
            ivAction.setOnClickListener(this);
            tvTitle = view.findViewById(R.id.tv_title);
            ivExpand = view.findViewById(R.id.iv_expand);
            if (ivExpand != null) {
                ivExpand.setOnClickListener(this);
            }
            // A project header: the bar toggles the group, the name opens the project (#288)
            headerBar = view.findViewById(R.id.header_bar);
            if (headerBar != null) {
                headerBar.setOnClickListener(this);
                tvTitle.setOnClickListener(this);
            }
            ivAvatar = view.findViewById(R.id.iv_avatar);
            if (ivAvatar != null) {
                ivAvatar.setOnClickListener(this);
            }
            // The feed row's own views (#287); a project header has none of them
            ivIcon = view.findViewById(R.id.iv_type_icon);
            tvTimestamp = view.findViewById(R.id.tv_created_at);
            tvActor = view.findViewById(R.id.tv_actor);
            tvSubtitle = view.findViewById(R.id.tv_subtitle);
            tvDesc = view.findViewById(R.id.tv_desc);
            ivGravatar = view.findViewById(R.id.iv_gravatar);
            if (ivGravatar != null) {
                ivGravatar.setOnClickListener(this);
                // Links in the action line; the rest of the row still opens the item
                tvTitle.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                tvTitle.setClickable(false);
                tvTitle.setLongClickable(false);
                tvTitle.setFocusable(false);
            }

            mPopupMenu = new PopupMenu(view.getContext(), ivAction);
            mPopupMenu.getMenuInflater().inflate(R.menu.notification_menu, mPopupMenu.getMenu());
            mPopupMenu.setOnMenuItemClickListener(this);
        }

        private final ImageView ivIcon;
        private final ImageView ivAction;
        private final ImageView ivAvatar;
        private final ImageView ivExpand;
        private final View headerBar;
        private final ImageView ivGravatar;
        private final TextView tvTitle;
        private final TextView tvTimestamp;
        private final TextView tvActor;
        private final TextView tvSubtitle;
        private final TextView tvDesc;
        private final PopupMenu mPopupMenu;
        private final OnNotificationActionCallback mActionCallback;

        @Override
        public void onClick(View v) {
            switch (v.getId()) {
                case R.id.iv_action: {
                    NotificationHolder notificationHolder = (NotificationHolder) v.getTag();

                    if (notificationHolder.notification == null) {
                        mActionCallback.markAsRead(notificationHolder);
                    } else {
                        // Bookmark or Remove bookmark, from the bookmarks on the phone (#289)
                        com.gl4a.utils.BookmarkActions.Target target = com.gl4a.utils
                                .FeedItemActions.bookmarkTarget(notificationHolder.notification);
                        MenuItem bookmark = mPopupMenu.getMenu().findItem(R.id.bookmark);
                        bookmark.setVisible(target != null);
                        if (target != null) {
                            bookmark.setTitle(com.gl4a.db.BookmarksProvider.hasBookmarked(
                                    v.getContext(), target.url())
                                    ? R.string.remove_bookmark : R.string.bookmark);
                        }
                        mPopupMenu.show();
                    }
                    break;
                }
                case R.id.iv_expand:
                case R.id.header_bar: {
                    NotificationHolder header = (NotificationHolder) v.getTag();
                    if (header != null && header.canExpand) mActionCallback.toggleGroup(header);
                    break;
                }
                case R.id.tv_title:
                case R.id.iv_avatar:
                case R.id.iv_gravatar: {
                    Object tag = v.getTag();
                    if (tag instanceof GitLabProject) {
                        Intent intent = RepositoryActivity.makeIntent(v.getContext(),
                                (GitLabProject) tag);
                        v.getContext().startActivity(intent);
                    } else if (tag instanceof GitLabUser) {
                        GitLabUser user = (GitLabUser) tag;
                        Intent intent = UserActivity.makeIntent(v.getContext(), user);
                        if (intent != null) {
                            v.getContext().startActivity(intent);
                        }
                    }
                    break;
                }
            }
        }

        @Override
        public boolean onMenuItemClick(MenuItem item) {
            NotificationHolder notificationHolder = (NotificationHolder) ivAction.getTag();

            switch (item.getItemId()) {
                case R.id.mark_as_read:
                    mActionCallback.markAsRead(notificationHolder);
                    return true;
                case R.id.unsubscribe:
                    com.gl4a.utils.FeedItemActions.unsubscribe(itemView,
                            notificationHolder.notification);
                    return true;
                case R.id.bookmark: {
                    com.gl4a.utils.BookmarkActions.Target target = com.gl4a.utils
                            .FeedItemActions.bookmarkTarget(notificationHolder.notification);
                    if (target != null) {
                        com.gl4a.utils.BookmarkActions.toggle(itemView.getContext(), target);
                    }
                    return true;
                }
                case R.id.remove:
                    mActionCallback.remove(notificationHolder);
                    return true;
            }

            return false;
        }
    }
}
