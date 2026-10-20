package com.gl4a.fragment;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.adapter.NotificationAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabTodo;
import com.gl4a.gitlab.service.GitLabTodoService;
import com.gl4a.model.NotificationHolder;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.RxUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Shows GitLab Todos (replaces GitHub NotificationThreads).
 */
public class NotificationListFragment extends LoadingListFragmentBase implements
        RootAdapter.OnItemClickListener<NotificationHolder>,
        ConfirmationDialogFragment.Callback,
        NotificationAdapter.OnNotificationActionCallback {

    public static final String EXTRA_INITIAL_REPO_OWNER = "initial_notification_repo_owner";
    public static final String EXTRA_INITIAL_REPO_NAME = "initial_notification_repo_name";

    public static NotificationListFragment newInstance() {
        return new NotificationListFragment();
    }

    private static final int ID_LOADER_TODOS = 0;

    // Filter modes — each maps to a specific state+action API call:
    // UNREAD  → state=pending, action=null  (all unread/pending items)
    // ALL     → state=null,    action=null  (everything)
    // others  → state=null,    action=X    (all items of that action type)
    private static final int MODE_UNREAD       = 0;
    private static final int MODE_ALL          = 1;
    private static final int MODE_ASSIGNED     = 2;
    private static final int MODE_MENTIONED    = 3;
    private static final int MODE_REVIEW       = 4;
    private static final int MODE_UNMERGEABLE  = 5;
    private static final int MODE_BUILD_FAILED = 6;

    private int mFilterMode = MODE_UNREAD;

    private NotificationAdapter mAdapter;
    private MenuItem mMarkAllAsReadMenuItem;
    private ParentCallback mCallback;
    // Guard: ensure the initial network load fires only once per fragment lifetime.
    private boolean mInitialLoadDone;
    // The to-dos last loaded, and whether they were shown grouped by project (#287)
    private List<GitLabTodo> mLastTodos;
    private boolean mGrouped = true;
    // Project groups shown in full (#288), and the list, to go back to a collapsed group's header
    private final java.util.Set<Long> mExpanded = new java.util.HashSet<>();
    private RecyclerView mList;

    public interface ParentCallback {
        void setNotificationsIndicatorVisible(boolean visible);

        /** GitLab's count of pending to-dos, for the To-dos tab (#280). */
        void setTodoCount(int count);

        /** One to-do was marked done. */
        void onTodoDone();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        if (!(context instanceof ParentCallback)) {
            throw new IllegalStateException("context must implement ParentCallback");
        }

        mCallback = (ParentCallback) context;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onStart() {
        super.onStart();
        // The list has been seen: no alerts for its to-dos, but notifications already showing
        // stay until their own to-do is done, tapped or swiped away. A notification tap passes
        // through this screen, which cleared all of them (#178)
        com.gl4a.worker.NotificationsWorker.markTodoListSeen(requireContext());
        if (!mInitialLoadDone) {
            mInitialLoadDone = true;
            setContentShown(false);
            loadTodos(false);
        }
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_notifications_found;
    }

    @Override
    public void onRefresh() {
        if (mAdapter != null) {
            mAdapter.clear();
        }
        setContentShown(false);
        loadTodos(true);
        updateMenuItemVisibility();
    }

    @Override
    protected void onRecyclerViewInflated(RecyclerView view, LayoutInflater inflater) {
        super.onRecyclerViewInflated(view, inflater);
        mAdapter = new NotificationAdapter(getActivity(), this);
        mAdapter.setOnItemClickListener(this);
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
    public void onResume() {
        super.onResume();
        // "Group by project" may have been changed on the Participating tab (#287)
        if (mLastTodos != null && mGrouped != isGrouped()) {
            showTodos(mLastTodos);
        }
    }

    /** From the app, not this tab: a load's result may come after the tab is gone (#288). */
    private boolean isGrouped() {
        return com.gl4a.utils.FeedGrouping.isGrouped(com.gl4a.Gl4Application.get(),
                com.gl4a.Gl4Application.get().getAuthLogin());
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
    public void onItemClick(NotificationHolder item) {
        if (item.notification == null) {
            // Repository header row
            GitLabProject project = item.repository;
            if (project != null) {
                startActivity(
                        com.gl4a.activities.RepositoryActivity.makeIntent(getActivity(), project));
            }
            return;
        }

        // item.notification is a GitLabTodo
        GitLabTodo todo = item.notification;

        if (todo.isCommit() && todo.targetUrl != null) {
            // Commit to-dos have no issue/MR target; the commit URL (with #note_N) routes
            // in-app through LinkParser (#166).
            markTodoAsDone(todo);
            com.gl4a.utils.IntentUtils.openLinkInternallyOrExternally(
                    getActivity(), android.net.Uri.parse(todo.targetUrl));
            return;
        }

        if (todo.project != null && todo.project.id > 0 && todo.target != null) {
            long projectId = todo.project.id;
            String pns = todo.project.pathWithNamespace != null ? todo.project.pathWithNamespace : "";
            int slash = pns.lastIndexOf('/');
            String owner = slash >= 0 ? pns.substring(0, slash) : pns;
            String repo  = slash >= 0 ? pns.substring(slash + 1) : pns;
            markTodoAsDone(todo);
            // Extract #note_XXXXX from targetUrl so detail view scrolls to the comment.
            String fragment = todo.targetUrl != null
                    ? android.net.Uri.parse(todo.targetUrl).getFragment() : null;
            com.gl4a.utils.IntentUtils.InitialCommentMarker initialComment =
                    com.gl4a.resolver.LinkParser.markerFromFragment(fragment);
            if ("MergeRequest".equals(todo.type())) {
                // Pass PAGE_CONVERSATION so the conversation tab is always selected
                // when navigating to a specific comment, avoiding the MR loading
                // the default tab and missing the scroll target.
                int initialPage = initialComment != null
                        ? com.gl4a.activities.PullRequestActivity.PAGE_CONVERSATION : -1;
                startActivity(com.gl4a.activities.PullRequestActivity.makeIntent(
                        getActivity(), owner, repo, todo.target.iid, initialPage, initialComment));
            } else {
                com.gl4a.gitlab.model.GitLabIssue stub = new com.gl4a.gitlab.model.GitLabIssue();
                stub.iid = todo.target.iid;
                stub.title = todo.target.title != null ? todo.target.title : "";
                stub.projectId = projectId;
                startActivity(com.gl4a.activities.IssueActivity.makeIntent(
                        getActivity(), owner, repo, projectId, todo.target.iid, initialComment));
            }
            return;
        }

        // Fallback: the to-do's URL, in-app where LinkParser knows it. Some to-dos have none
        // (e.g. GitLab's "Duo Core access granted"): opening the empty URL crashed (#212)
        String url = todo.url();
        if (url != null && !url.trim().isEmpty()) {
            com.gl4a.utils.IntentUtils.openLinkInternallyOrExternally(
                    getActivity(), android.net.Uri.parse(url));
            markTodoAsDone(todo);
        } else {
            android.widget.Toast.makeText(getActivity(), R.string.todo_nothing_to_open,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.notification_list_menu, menu);
        menu.findItem(R.id.group_by_project).setChecked(isGrouped());
        mMarkAllAsReadMenuItem = menu.findItem(R.id.mark_all_as_read);
        updateMenuItemVisibility();

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        switch (itemId) {
            case R.id.group_by_project:
                com.gl4a.utils.FeedGrouping.setGrouped(requireContext(),
                        com.gl4a.Gl4Application.get().getAuthLogin(), !item.isChecked());
                item.setChecked(!item.isChecked());
                if (mLastTodos != null) showTodos(mLastTodos);
                return true;
            case R.id.mark_all_as_read:
                ConfirmationDialogFragment.show(this, R.string.mark_all_as_read_question,
                        R.string.mark_all_as_read, null, "markallreadconfirm");
                return true;
            case R.id.todo_filter_unread:       mFilterMode = MODE_UNREAD;       item.setChecked(true); onRefresh(); return true;
            case R.id.todo_filter_all:          mFilterMode = MODE_ALL;          item.setChecked(true); onRefresh(); return true;
            case R.id.todo_action_assigned:     mFilterMode = MODE_ASSIGNED;     item.setChecked(true); onRefresh(); return true;
            case R.id.todo_action_mentioned:    mFilterMode = MODE_MENTIONED;    item.setChecked(true); onRefresh(); return true;
            case R.id.todo_action_review_requested: mFilterMode = MODE_REVIEW;   item.setChecked(true); onRefresh(); return true;
            case R.id.todo_action_unmergeable:  mFilterMode = MODE_UNMERGEABLE;  item.setChecked(true); onRefresh(); return true;
            case R.id.todo_action_build_failed: mFilterMode = MODE_BUILD_FAILED; item.setChecked(true); onRefresh(); return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void toggleGroup(NotificationHolder header) {
        boolean collapsed = com.gl4a.utils.FeedGroups.toggle(mExpanded, header);
        if (mLastTodos != null) showTodos(mLastTodos);
        if (collapsed && mList != null) {
            com.gl4a.utils.FeedGroups.scrollToHeader(mList, mAdapter, header.repository);
        }
    }

    @Override
    public void markAsRead(NotificationHolder notificationHolder) {
        if (notificationHolder.notification != null) {
            markTodoAsDone(notificationHolder.notification);
        }
    }

    @Override
    public void onConfirmed(String tag, Parcelable data) {
        if ("markallreadconfirm".equals(tag)) {
            markAllTodosAsDone();
        }
    }

    private void updateMenuItemVisibility() {
        if (mMarkAllAsReadMenuItem == null) {
            return;
        }
        mMarkAllAsReadMenuItem.setVisible(isContentShown() && mAdapter.hasUnreadNotifications());
    }

    private void markTodoAsDone(GitLabTodo todo) {
        if (!todo.isUnread()) {
            return;
        }
        GitLabTodoService service = ServiceFactory.get(GitLabTodoService.class, false);
        service.markAsDone(todo.id())
                .map(ApiHelpers::throwOnFailure)
                .compose(RxUtils::doInBackground)
                .subscribe(result -> {
                    todo.state = "done"; // so regrouping (#287) keeps it read
                    mAdapter.markAsRead(null, todo);
                    mCallback.onTodoDone();
                    updateMenuItemVisibility();
                    if (mFilterMode == MODE_UNREAD) {
                        mCallback.setNotificationsIndicatorVisible(mAdapter.hasUnreadNotifications());
                    }
                }, error -> handleActionFailure("Marking todo as done failed", error));
    }

    private void markAllTodosAsDone() {
        GitLabTodoService service = ServiceFactory.get(GitLabTodoService.class, false);
        service.markAllAsDone()
                .map(ApiHelpers::mapToBooleanOrThrowOnFailure)
                .compose(RxUtils::doInBackground)
                .subscribe(result -> {
                    if (mLastTodos != null) {
                        for (GitLabTodo t : mLastTodos) t.state = "done";
                    }
                    mAdapter.markAsRead(null, null);
                    mCallback.setTodoCount(0);
                    updateMenuItemVisibility();
                    if (mFilterMode == MODE_UNREAD) {
                        mCallback.setNotificationsIndicatorVisible(false);
                    }
                }, error -> handleActionFailure("Marking all todos as done failed", error));
    }

    private void loadTodos(boolean force) {
        GitLabTodoService service = ServiceFactory.get(GitLabTodoService.class, force);

        // Map filter mode to state + action API params.
        // Use direct subscription (no makeLoaderSingle) so each filter change
        // always fetches fresh data without caching.
        final String state;
        final String action;
        switch (mFilterMode) {
            case MODE_UNREAD:       state = "pending"; action = null;             break;
            case MODE_ALL:          state = null;      action = null;             break;
            case MODE_ASSIGNED:     state = null;      action = "assigned";       break;
            case MODE_MENTIONED:    state = null;      action = "mentioned";      break;
            case MODE_REVIEW:       state = null;      action = "review_requested"; break;
            case MODE_UNMERGEABLE:  state = null;      action = "unmergeable";    break;
            case MODE_BUILD_FAILED: state = null;      action = "build_failed";   break;
            default:                state = "pending"; action = null;             break;
        }

        // "All" and action-filtered modes need both pending+done merged.
        io.reactivex.Single<List<GitLabTodo>> todosSingle;
        if (state == null) {
            io.reactivex.Single<List<GitLabTodo>> p =
                    service.listTodos("pending", null, action, null, null, null, 1, 100)
                            .map(ApiHelpers::throwOnFailure);
            io.reactivex.Single<List<GitLabTodo>> d =
                    service.listTodos("done", null, action, null, null, null, 1, 100)
                            .map(ApiHelpers::throwOnFailure);
            todosSingle = io.reactivex.Single.zip(p, d, (pending, done) -> {
                List<GitLabTodo> merged = new java.util.ArrayList<>(pending);
                merged.addAll(done);
                merged.sort((a, b) -> {
                    if (a.createdAt == null || b.createdAt == null) return 0;
                    return b.createdAt.compareTo(a.createdAt);
                });
                return merged;
            });
        } else {
            todosSingle = service.listTodos(state, null, action, null, null, null, 1, 100)
                    .map(ApiHelpers::throwOnFailure);
        }

        todosSingle
                .compose(RxUtils::doInBackground)
                .subscribe(todos -> {
                    // The tab may be gone by now (switched screens): nothing to show it in (#288)
                    if (mAdapter == null || !isAdded()) return;
                    showTodos(todos);
                    setContentShown(true);
                    updateEmptyState();
                    updateMenuItemVisibility();
                    if (mFilterMode == MODE_UNREAD) {
                        mCallback.setNotificationsIndicatorVisible(
                                todos.stream().anyMatch(GitLabTodo::isUnread));
                        // All of them when fewer than a page: the exact count
                        if (todos.size() < 100) mCallback.setTodoCount(todos.size());
                    }
                }, this::handleLoadFailure);
    }

    /** Shows the to-dos grouped by project or as one timeline, as set (#287). */
    private void showTodos(List<GitLabTodo> todos) {
        mLastTodos = todos;
        mGrouped = isGrouped();
        List<NotificationHolder> holders;
        if (mGrouped) {
            holders = com.gl4a.utils.FeedGroups.build(todos, mExpanded);
        } else {
            holders = new ArrayList<>();
            for (GitLabTodo todo : todos) {
                holders.add(new NotificationHolder(todo));
            }
        }
        mAdapter.setShowProject(!mGrouped);
        mAdapter.clear();
        mAdapter.addAll(holders);
        mAdapter.notifyDataSetChanged();
        updateEmptyState();
    }

}
