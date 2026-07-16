package com.gl4a.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabTodo;

public class NotificationHolder {
    @Nullable
    public final GitLabTodo notification;

    @NonNull
    public final GitLabProject repository;

    private boolean mLastRepositoryNotification;
    private boolean mRead;

    public NotificationHolder(@NonNull GitLabProject repository) {
        notification = null;
        this.repository = repository;
    }

    public NotificationHolder(@NonNull GitLabTodo notification) {
        this.notification = notification;
        // project may be null for group-level or global todos — use an empty placeholder
        GitLabProject p = notification.repository();
        if (p == null) {
            p = new GitLabProject();
            p.name = notification.type() != null ? notification.type() : "GitLab";
            p.pathWithNamespace = p.name;
        }
        repository = p;
        mRead = !notification.isUnread();
    }

    public boolean isLastRepositoryNotification() {
        return mLastRepositoryNotification;
    }

    public void setIsLastRepositoryNotification(boolean value) {
        mLastRepositoryNotification = value;
    }

    public boolean isRead() {
        return mRead;
    }

    public void setIsRead(boolean value) {
        mRead = value;
    }
}
