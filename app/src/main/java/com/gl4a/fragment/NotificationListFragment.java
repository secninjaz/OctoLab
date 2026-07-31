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

    public interface ParentCallback {
        void setNotificationsIndicatorVisible(boolean visible);
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
        // Mark system notifications as seen whenever the user opens the notifications tab.
        com.gl4a.worker.NotificationsWorker.markNotificationsAsSeen(requireContext());
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
        updateEmptyState();
    }

    @Override
    protected boolean hasDividers() {
        return false;
    }

    @Override
    protected boolean hasCards() {
        return true;
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

        // Navigate using todo.project.id directly to avoid 404 when the token
        // lacks access to the project namespace via path resolution.
        if (todo.project != null && todo.project.id > 0 && todo.target != null) {
            long projectId = todo.project.id;
            String pns = todo.project.pathWithNamespace != null ? todo.project.pathWithNamespace : "";
            int slash = pns.lastIndexOf('/');
            String owner = slash >= 0 ? pns.substring(0, slash) : pns;
            String repo  = slash >= 0 ? pns.substring(slash + 1) : pns;
            markTodoAsDone(todo);
            if ("MergeRequest".equals(todo.type())) {
                startActivity(com.gl4a.activities.PullRequestActivity.makeIntent(
                        getActivity(), owner, repo, todo.target.iid));
            } else {
                com.gl4a.gitlab.model.GitLabIssue stub = new com.gl4a.gitlab.model.GitLabIssue();
                stub.iid = todo.target.iid;
                stub.title = todo.target.title != null ? todo.target.title : "";
                stub.projectId = projectId;
                startActivity(com.gl4a.activities.IssueActivity.makeIntent(
                        getActivity(), stub, projectId));
            }
            return;
        }

        // Fallback: open in browser (for todos without resolvable project)
        String url = todo.url();
        if (url != null) {
            com.gl4a.utils.IntentUtils.openInCustomTabOrBrowser(
                    getActivity(), android.net.Uri.parse(url));
            markTodoAsDone(todo);
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.notification_list_menu, menu);
        mMarkAllAsReadMenuItem = menu.findItem(R.id.mark_all_as_read);
        updateMenuItemVisibility();

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        switch (itemId) {
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
    public void markAsRead(NotificationHolder notificationHolder) {
        if (notificationHolder.notification != null) {
            markTodoAsDone(notificationHolder.notification);
        }
    }

    @Override
    public void unsubscribe(NotificationHolder notificationHolder) {
        if (notificationHolder.notification == null) return;
        GitLabTodo todo = notificationHolder.notification;
        GitLabTodoService service = ServiceFactory.get(GitLabTodoService.class, false);
        service.deleteTodo(todo.id())
                .map(ApiHelpers::mapToBooleanOrThrowOnFailure)
                .compose(RxUtils::doInBackground)
                .subscribe(
                        result -> Toast.makeText(getContext(),
                                R.string.unsubscribe_success, Toast.LENGTH_SHORT).show(),
                        error -> handleActionFailure("Deleting todo failed", error));
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
                    mAdapter.markAsRead(null, todo);
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
                    mAdapter.markAsRead(null, null);
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
                    if (mAdapter == null) return;
                    List<NotificationHolder> holders = buildHolders(todos);
                    mAdapter.clear();
                    mAdapter.addAll(holders);
                    mAdapter.notifyDataSetChanged();
                    setContentShown(true);
                    updateEmptyState();
                    updateMenuItemVisibility();
                    if (mFilterMode == MODE_UNREAD) {
                        mCallback.setNotificationsIndicatorVisible(
                                todos.stream().anyMatch(GitLabTodo::isUnread));
                    }
                }, this::handleLoadFailure);
    }

    /**
     * Groups todos by project and creates NotificationHolder rows matching
     * the existing adapter's expected structure.
     */
    private List<NotificationHolder> buildHolders(List<GitLabTodo> todos) {
        // The Todos API returns items sorted by created_at, not by project.
        // Inserting a header only on first-seen caused todos from Project A that
        // appeared after Project B's header to render under Project B visually.
        // Fix: collect all todos per project first, preserving the order of each
        // project's first appearance, then emit header + all todos per project.
        java.util.LinkedHashMap<Long, GitLabProject> projectOrder = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<Long, List<GitLabTodo>> todosByProject = new java.util.LinkedHashMap<>();

        for (GitLabTodo todo : todos) {
            GitLabProject project = todo.repository();
            long key = project != null && project.id() > 0 ? project.id() : 0L;
            if (!projectOrder.containsKey(key)) {
                projectOrder.put(key, project);
                todosByProject.put(key, new ArrayList<>());
            }
            todosByProject.get(key).add(todo);
        }

        List<NotificationHolder> result = new ArrayList<>();
        for (Map.Entry<Long, GitLabProject> entry : projectOrder.entrySet()) {
            GitLabProject project = entry.getValue();
            if (project != null) {
                result.add(new NotificationHolder(project));
            }
            for (GitLabTodo todo : todosByProject.get(entry.getKey())) {
                result.add(new NotificationHolder(todo));
            }
        }
        return result;
    }
}
