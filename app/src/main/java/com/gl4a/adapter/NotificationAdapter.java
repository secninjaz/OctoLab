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

        void unsubscribe(NotificationHolder notificationHolder);
    }

    private final int mBottomMargin;
    private final Context mContext;
    private final OnNotificationActionCallback mActionCallback;

    public NotificationAdapter(Context context, OnNotificationActionCallback actionCallback) {
        super(context);
        mContext = context;
        mActionCallback = actionCallback;

        mBottomMargin = context.getResources().getDimensionPixelSize(R.dimen.card_margin);
    }

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
                : R.layout.row_notification;
        View v = inflater.inflate(layoutResId, parent, false);
        return new ViewHolder(v, mActionCallback);
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
            holder.tvTitle.setText(ApiHelpers.formatRepoName(mContext, item.repository));

            // Use assignAvatarForProject so the project's own uploaded avatar is shown.
            // The Todos API omits avatar_url from the project sub-object; the method
            // fetches it asynchronously via GET /projects/{id}.
            if (item.repository != null) {
                String projectName = item.repository.name != null ? item.repository.name : "";
                AvatarHandler.assignAvatarForProject(holder.ivAvatar, projectName, item.repository.id);
                // Keep the tag for click handling (opens RepositoryActivity).
                GitLabUser owner = item.repository.owner();
                if (owner == null) {
                    owner = new GitLabUser();
                    owner.id = item.repository.id;
                    owner.username = item.repository.path != null ? item.repository.path : item.repository.name;
                    owner.name = item.repository.name;
                }
                holder.ivAvatar.setTag(owner);
            }
            holder.ivAvatar.setAlpha(alpha);
            return;
        }

        holder.ivIcon.setAlpha(alpha);
        holder.tvTimestamp.setAlpha(alpha);
        holder.mPopupMenu.getMenu().findItem(R.id.mark_as_read).setVisible(!item.isRead());

        GitLabTodo todo = item.notification;
        int iconResId = getIconResId(todo.type());
        if (iconResId > 0) {
            holder.ivIcon.setImageResource(iconResId);
            holder.ivIcon.setVisibility(View.VISIBLE);
        } else {
            holder.ivIcon.setVisibility(View.INVISIBLE);
        }

        holder.tvReason.setText(formatActionText(todo));
        holder.tvTitle.setText(todo.title());
        holder.tvTimestamp.setText(StringUtils.formatRelativeTime(mContext,
                todo.createdAt, true));

        ViewGroup.MarginLayoutParams layoutParams =
                (ViewGroup.MarginLayoutParams) holder.vNotificationContent.getLayoutParams();
        int bottomMargin = item.isLastRepositoryNotification() ? mBottomMargin : 0;
        layoutParams.setMargins(0, 0, 0, bottomMargin);
        holder.vNotificationContent.setLayoutParams(layoutParams);

        holder.vBottomShadow.setVisibility(
                item.isLastRepositoryNotification() ? View.VISIBLE : View.GONE);
    }

    /** Formats the action line matching GitLab web: "Jay mentioned you in Issue #34" */
    private String formatActionText(com.gl4a.gitlab.model.GitLabTodo todo) {
        String authorName = todo.author != null ? todo.author.name() : null;
        if (authorName == null) authorName = "";

        String targetType = todo.targetType;
        String typeLabel = "Issue";
        if ("MergeRequest".equals(targetType)) typeLabel = "MR";
        else if ("Commit".equals(targetType)) typeLabel = "Commit";
        else if ("Epic".equals(targetType)) typeLabel = "Epic";

        String iidPart = (todo.target != null && todo.target.iid > 0)
                ? " #" + todo.target.iid : "";

        String action = todo.actionName != null ? todo.actionName : "";
        String verb;
        switch (action) {
            case "mentioned":           verb = "mentioned you in";       break;
            case "directly_addressed":  verb = "mentioned you in";       break;
            case "assigned":            verb = "assigned you to";        break;
            case "review_requested":    verb = "requested your review on"; break;
            case "approval_required":   verb = "requested your approval on"; break;
            case "build_failed":
                return "Build failed on " + typeLabel + iidPart;
            case "unmergeable":
                return typeLabel + iidPart + " cannot be merged";
            case "marked":
                return typeLabel + iidPart + " was marked";
            case "merge_train_removed":
                return typeLabel + iidPart + " was removed from the merge train";
            default:
                verb = action.replace('_', ' ');
        }
        return authorName + " " + verb + " " + typeLabel + iidPart;
    }

    private int getIconResId(String targetType) {
        if (targetType == null) return 0;
        return switch (targetType) {
            case SUBJECT_ISSUE -> R.drawable.issue;
            case SUBJECT_MERGE_REQUEST -> R.drawable.pull_request;
            case SUBJECT_COMMIT -> R.drawable.commit;
            case SUBJECT_RELEASE -> R.drawable.release;
            case SUBJECT_DISCUSSION -> R.drawable.discussion;
            default -> 0;
        };
    }

    public static class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener,
            PopupMenu.OnMenuItemClickListener {
        public ViewHolder(View view, OnNotificationActionCallback actionCallback) {
            super(view);
            mActionCallback = actionCallback;

            ivAction = view.findViewById(R.id.iv_action);
            ivAction.setOnClickListener(this);
            ivIcon = view.findViewById(R.id.iv_icon);
            tvReason = view.findViewById(R.id.tv_reason);
            tvTitle = view.findViewById(R.id.tv_title);
            tvTimestamp = view.findViewById(R.id.tv_timestamp);
            vNotificationContent = view.findViewById(R.id.v_notification_content);
            vBottomShadow = view.findViewById(R.id.v_bottom_shadow);
            ivAvatar = view.findViewById(R.id.iv_avatar);
            if (ivAvatar != null) {
                ivAvatar.setOnClickListener(this);
            }

            mPopupMenu = new PopupMenu(view.getContext(), ivAction);
            mPopupMenu.getMenuInflater().inflate(R.menu.notification_menu, mPopupMenu.getMenu());
            mPopupMenu.setOnMenuItemClickListener(this);
        }

        private final ImageView ivIcon;
        private final ImageView ivAction;
        private final ImageView ivAvatar;
        private final TextView tvReason;
        private final TextView tvTitle;
        private final TextView tvTimestamp;
        private final View vNotificationContent;
        private final View vBottomShadow;
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
                        mPopupMenu.show();
                    }
                    break;
                }
                case R.id.iv_avatar: {
                    Object tag = v.getTag();
                    if (tag instanceof GitLabUser) {
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
                    mActionCallback.unsubscribe(notificationHolder);
                    return true;
            }

            return false;
        }
    }
}
