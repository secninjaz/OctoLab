package com.gl4a.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.adapter.NotificationAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabTodo;
import com.gl4a.model.NotificationHolder;
import com.gl4a.utils.ParticipatingInbox;
import com.gl4a.utils.RxUtils;
import com.gl4a.worker.NotificationsWorker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.Single;

/**
 * To-dos | Participating (#280): the active account's participating notifications, kept by
 * {@link ParticipatingInbox}, in the To-dos list's rows. Opening one opens the comment, as its
 * notification does, and marks it read; it can be marked read or deleted from its menu.
 * Nothing here talks to GitLab: GitLab keeps no record of these.
 */
public class ParticipatingListFragment extends LoadingListFragmentBase implements
        RootAdapter.OnItemClickListener<NotificationHolder>,
        NotificationAdapter.OnNotificationActionCallback {

    public static ParticipatingListFragment newInstance() {
        return new ParticipatingListFragment();
    }

    // The filter, as the To-dos tab's (#280): unread by default, for as long as the screen is open
    private static final int FILTER_UNREAD = 0;
    private static final int FILTER_ALL = 1;
    private static final int FILTER_ISSUES = 2;
    private static final int FILTER_MERGE_REQUESTS = 3;
    private int mFilter = FILTER_UNREAD;

    private NotificationAdapter mAdapter;
    // Project groups shown in full (#288), and the list, to go back to a collapsed group's header
    private final java.util.Set<Long> mExpanded = new java.util.HashSet<>();
    private RecyclerView mList;
    private MenuItem mMarkAllAsReadMenuItem;
    private final Runnable mOnInboxChanged = this::load;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onStart() {
        super.onStart();
        ParticipatingInbox.addListener(mOnInboxChanged);
        load();
    }

    @Override
    public void onStop() {
        ParticipatingInbox.removeListener(mOnInboxChanged);
        super.onStop();
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.participating_empty;
    }

    @Override
    protected boolean hasDividers() {
        return false;
    }

    @Override
    protected boolean hasCards() {
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        // "Group by project" may have been changed on the To-dos tab (#287)
        if (mGrouped != isGrouped()) load();
    }

    private boolean mGrouped = true;

    /** From the app, not this tab: a load's result may come after the tab is gone (#288). */
    private boolean isGrouped() {
        return com.gl4a.utils.FeedGrouping.isGrouped(Gl4Application.get(), login());
    }

    @Override
    public void onRefresh() {
        load();
    }

    @Override
    protected void onRecyclerViewInflated(RecyclerView view, LayoutInflater inflater) {
        super.onRecyclerViewInflated(view, inflater);
        mAdapter = new NotificationAdapter(getActivity(), this);
        mAdapter.setOnItemClickListener(this);
        mAdapter.setRemoveTitle(R.string.delete);
        view.setAdapter(mAdapter);
        mList = view;
        // The current project's header stays at the top (#288)
        com.gl4a.widget.StickyHeaders.attach(view, mAdapter::isHeaderAt, R.id.header_bar);
        // The feed's timeline line, not through project headers (#287)
        view.addItemDecoration(com.gl4a.widget.TimelineLineDecoration.forFeed(
                view.getContext(), mAdapter::isHeaderAt));
        updateEmptyState();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.participating_list_menu, menu);
        menu.findItem(R.id.group_by_project).setChecked(isGrouped());
        mMarkAllAsReadMenuItem = menu.findItem(R.id.mark_all_as_read);
        updateMenuItemVisibility();
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.group_by_project) {
            com.gl4a.utils.FeedGrouping.setGrouped(requireContext(), login(), !item.isChecked());
            item.setChecked(!item.isChecked());
            load();
            return true;
        }
        int filter = itemId == R.id.participating_filter_unread ? FILTER_UNREAD
                : itemId == R.id.participating_filter_all ? FILTER_ALL
                : itemId == R.id.participating_filter_issues ? FILTER_ISSUES
                : itemId == R.id.participating_filter_merge_requests ? FILTER_MERGE_REQUESTS
                : -1;
        if (filter >= 0) {
            mFilter = filter;
            item.setChecked(true);
            load();
            return true;
        }
        if (item.getItemId() == R.id.mark_all_as_read) {
            String login = login();
            if (login != null) {
                ParticipatingInbox inbox = inbox();
                inBackground(() -> inbox.markAllRead(login));
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onItemClick(NotificationHolder item) {
        if (item.notification == null) {
            GitLabProject project = item.repository;
            if (project != null && project.id > 0) {
                startActivity(com.gl4a.activities.RepositoryActivity.makeIntent(getActivity(),
                        project));
            }
            return;
        }
        GitLabTodo comment = item.notification;
        markRead(comment);
        // Opened as its notification opens it: at the comment
        if (comment.targetUrl != null && !comment.targetUrl.trim().isEmpty()) {
            com.gl4a.utils.IntentUtils.openLinkInternallyOrExternally(requireActivity(),
                    android.net.Uri.parse(comment.targetUrl));
        }
    }

    @Override
    public void markAsRead(NotificationHolder holder) {
        if (holder.notification != null) {
            markRead(holder.notification);
            return;
        }
        // A project's header: all of its comments
        List<GitLabTodo> comments = new ArrayList<>();
        for (int i = 0; i < mAdapter.getCount(); i++) {
            NotificationHolder h = mAdapter.getItem(i);
            if (h.notification != null && h.repository.equals(holder.repository)) {
                comments.add(h.notification);
            }
        }
        for (GitLabTodo comment : comments) {
            markRead(comment);
        }
    }

    /** "Delete", from the row's menu (no swiping: it took swipes meant to change tabs). */
    @Override
    public void remove(NotificationHolder holder) {
        if (holder.notification == null) return;
        String login = login();
        long noteId = -holder.notification.id;
        if (login == null) return;
        dismissNotification(holder.notification);
        ParticipatingInbox inbox = inbox();
        inBackground(() -> inbox.delete(login, noteId));
    }

    private void markRead(GitLabTodo comment) {
        String login = login();
        if (login == null) return;
        long noteId = -comment.id;
        dismissNotification(comment);
        ParticipatingInbox inbox = inbox();
        inBackground(() -> inbox.markRead(login, noteId));
    }

    /** Its notification, if still showing, goes, and isn't shown again (#275). */
    private void dismissNotification(GitLabTodo comment) {
        String login = login();
        if (login != null) {
            NotificationsWorker.cancelParticipating(requireContext(), login, comment.id);
        }
    }

    private ParticipatingInbox inbox() {
        return ParticipatingInbox.get(requireContext());
    }

    @Nullable
    private static String login() {
        return Gl4Application.get().getAuthLogin();
    }

    private void load() {
        String login = login();
        if (login == null || getContext() == null) return;
        android.content.Context context = requireContext().getApplicationContext();
        Single.fromCallable(() -> ParticipatingInbox.get(context).list(login))
                .compose(RxUtils::doInBackground)
                .subscribe(entries -> {
                    // The tab may be gone by now (switched screens): nothing to show it in (#288)
                    if (mAdapter == null || !isAdded()) return;
                    mAdapter.clear();
                    mGrouped = isGrouped();
                    mAdapter.setShowProject(!mGrouped);
                    mAdapter.addAll(mGrouped
                            ? com.gl4a.utils.FeedGroups.build(comments(filtered(entries)),
                                    mExpanded)
                            : ungrouped(filtered(entries)));
                    if (mCollapsedProject != null) {
                        com.gl4a.utils.FeedGroups.scrollToHeader(mList, mAdapter,
                                mCollapsedProject);
                        mCollapsedProject = null;
                    }
                    android.view.View view = getView();
                    android.widget.TextView empty = view != null
                            ? view.findViewById(android.R.id.empty) : null;
                    if (empty != null) {
                        empty.setText(mFilter == FILTER_ALL || entries.isEmpty()
                                ? R.string.participating_empty
                                : R.string.participating_empty_filtered);
                    }
                    mAdapter.notifyDataSetChanged();
                    setContentShown(true);
                    updateEmptyState();
                    updateMenuItemVisibility();
                }, this::handleLoadFailure);
    }

    private List<ParticipatingInbox.Entry> filtered(List<ParticipatingInbox.Entry> entries) {
        List<ParticipatingInbox.Entry> result = new ArrayList<>();
        for (ParticipatingInbox.Entry entry : entries) {
            String type = entry.comment.targetType;
            boolean keep;
            switch (mFilter) {
                case FILTER_UNREAD: keep = !entry.read; break;
                case FILTER_ISSUES: keep = "Issue".equals(type); break;
                case FILTER_MERGE_REQUESTS: keep = "MergeRequest".equals(type); break;
                default: keep = true;
            }
            if (keep) result.add(entry);
        }
        return result;
    }

    private void inBackground(Runnable work) {
        io.reactivex.Completable.fromRunnable(work)
                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .subscribe(() -> { }, error -> android.util.Log.d(Gl4Application.LOG_TAG,
                        "Participating list update failed", error));
    }

    private void updateMenuItemVisibility() {
        if (mMarkAllAsReadMenuItem != null && mAdapter != null) {
            mMarkAllAsReadMenuItem.setVisible(mAdapter.hasUnreadNotifications());
        }
    }

    @Override
    public void toggleGroup(NotificationHolder header) {
        if (com.gl4a.utils.FeedGroups.toggle(mExpanded, header)) {
            mCollapsedProject = header.repository;
        }
        load();
    }

    /** A group just collapsed: back to its header once the list is rebuilt (#288). */
    @Nullable private GitLabProject mCollapsedProject;

    /** The entries' comments, each with its read state, newest first. */
    private static List<GitLabTodo> comments(List<ParticipatingInbox.Entry> entries) {
        List<GitLabTodo> result = new ArrayList<>();
        for (ParticipatingInbox.Entry entry : entries) {
            entry.comment.state = entry.read ? "done" : "pending";
            result.add(entry.comment);
        }
        return result;
    }

    /** One timeline, newest first, each row naming its project (#287). */
    private static List<NotificationHolder> ungrouped(List<ParticipatingInbox.Entry> entries) {
        List<NotificationHolder> result = new ArrayList<>();
        for (ParticipatingInbox.Entry entry : entries) {
            entry.comment.state = entry.read ? "done" : "pending";
            result.add(new NotificationHolder(entry.comment));
        }
        return result;
    }

}
