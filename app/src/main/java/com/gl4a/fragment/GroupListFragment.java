package com.gl4a.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.gl4a.BaseActivity;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabGroup;
import com.gl4a.gitlab.service.GitLabGroupService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.MembershipRoles;
import com.gl4a.utils.RxUtils;
import com.gl4a.widget.GroupTree;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Single;
import io.reactivex.disposables.Disposable;

/**
 * The user's top-level groups as an expandable tree, like GitLab web's "Your groups" (#172).
 * Uses the same GroupTree as the group screen.
 */
public class GroupListFragment extends Fragment implements BaseActivity.RefreshableChild,
        com.gl4a.widget.SwipeRefreshLayout.ChildScrollDelegate {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;

    public static GroupListFragment newInstance() {
        return new GroupListFragment();
    }

    private GroupTree mTree;
    private GroupTree.State mState;
    private View mScroll;
    private Disposable mLoad;

    /** Groups and the viewer's roles, loaded together so badges are always present. */
    private static class Data {
        final List<GitLabGroup> groups;
        final MembershipRoles roles;

        Data(List<GitLabGroup> groups, MembershipRoles roles) {
            this.groups = groups;
            this.roles = roles;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_group_tree, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mScroll = view.findViewById(R.id.scroll);
        // Survives rotation, so the tree keeps its rows, expanded groups and scroll (#172).
        mState = new androidx.lifecycle.ViewModelProvider(this).get(GroupTree.State.class);
        mTree = new GroupTree(requireActivity(), view.findViewById(R.id.ll_tree), mState);
        if (mState.hasContent()) {
            view.findViewById(R.id.progress).setVisibility(View.GONE);
            mTree.restore();
            final int scrollY = mState.scrollY;
            mScroll.post(() -> mScroll.scrollTo(0, scrollY));
        } else {
            load(false);
        }
    }

    @Override
    public boolean canChildScrollUp() {
        // Without this the drawer screen's pull-to-refresh fired whenever the tree was
        // scrolled down and the user dragged back up (#172).
        return mScroll != null && mScroll.canScrollVertically(-1);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mScroll != null && mState != null) mState.scrollY = mScroll.getScrollY();
    }

    @Override
    public void onDestroyView() {
        if (mLoad != null) mLoad.dispose();
        mTree = null;
        mScroll = null;
        super.onDestroyView();
    }

    @Override
    public void onRefresh() {
        if (mState != null) mState.clear();
        load(true);
    }

    private void load(boolean force) {
        View view = getView();
        if (view == null) return;
        view.findViewById(R.id.progress).setVisibility(View.VISIBLE);
        view.findViewById(R.id.tv_empty).setVisibility(View.GONE);
        // Not the previous list under a loading or error message (#308)
        view.findViewById(R.id.ll_tree).setVisibility(View.GONE);
        if (mLoad != null) mLoad.dispose();
        final boolean byActivity = GroupTree.isSortByActivity(requireContext());
        final String scope = scope(requireContext());
        final String query = mQuery;
        // Remembered while the app is open: back to Groups is instant; refresh reloads (#308)
        final String key = com.gl4a.Gl4Application.get().getAuthLogin() + "|" + scope + "|"
                + byActivity;
        Data cached = query == null && !force ? sCache.get(key) : null;
        if (cached != null) {
            show(cached);
            return;
        }
        final boolean withProjectGroups = query == null && SCOPE_MINE.equals(scope);
        // Whether your groups were shown before your projects' groups came (#320)
        final boolean[] shownFirst = new boolean[1];
        mLoad = Single.fromCallable(() -> query != null ? searchGroups(force, scope, query)
                        : loadAllGroups(force, byActivity, scope))
                .zipWith(force ? MembershipRoles.load() : MembershipRoles.loadCached(), Data::new)
                .compose(RxUtils::doInBackground)
                .flatMap(data -> {
                    // Your groups first, at once; your projects' groups a moment later (#308)
                    if (!withProjectGroups) return Single.just(data);
                    // (an empty first part isn't "no groups" yet: keep the spinner)
                    if (!data.groups.isEmpty()) {
                        show(data, false);
                        shownFirst[0] = true;
                    }
                    return Single.fromCallable(() -> new Data(
                                    withGroupsOfMyProjects(data.groups, force, byActivity),
                                    data.roles))
                            .compose(RxUtils::doInBackground);
                })
                .subscribe(data -> {
                    if (query == null) sCache.put(key, data);
                    // Your projects' groups added to the list already shown: what you opened
                    // meanwhile stays open (#320)
                    show(data, shownFirst[0]);
                }, error -> {
                    View v = getView();
                    if (v == null) return;
                    v.findViewById(R.id.progress).setVisibility(View.GONE);
                    TextView empty = v.findViewById(R.id.tv_empty);
                    empty.setText(R.string.group_tree_loading_failed);
                    empty.setVisibility(View.VISIBLE);
                });
    }

    /** Reloads after the sort was changed from the menu (#175). */
    public void onSortChanged() {
        if (mState != null) mState.clear();
        load(false);
    }

    /**
     * Which groups (#308): "mine" (default) = the groups you're a member of, plus the groups
     * your projects are in (a Guest on one project of a group sees that group); "owner" = the
     * groups you own. Public groups you have nothing to do with are in Explore, as on GitLab
     * web. Remembered per account.
     */
    public static final String SCOPE_MINE = "mine";
    public static final String SCOPE_OWNER = "owner";

    private static String scopeKey() {
        return "group_list_scope_" + com.gl4a.Gl4Application.get().getAuthLogin();
    }

    public static String scope(android.content.Context context) {
        String scope = context.getSharedPreferences(com.gl4a.fragment.SettingsFragment.PREF_NAME,
                android.content.Context.MODE_PRIVATE).getString(scopeKey(), SCOPE_MINE);
        return SCOPE_OWNER.equals(scope) ? SCOPE_OWNER : SCOPE_MINE;
    }

    public static void setScope(android.content.Context context, String scope) {
        context.getSharedPreferences(com.gl4a.fragment.SettingsFragment.PREF_NAME,
                android.content.Context.MODE_PRIVATE).edit().putString(scopeKey(), scope).apply();
    }

    // The search, as Projects has it (#309): null when not searching
    @Nullable private String mQuery;

    /** Searches your groups at any depth (null: back to the list) (#309). */
    public void search(@Nullable String query) {
        mQuery = query == null || query.trim().isEmpty() ? null : query.trim();
        if (mState != null) mState.clear();
        load(false);
    }

    /** Reloads after the filter was changed (#308). */
    public void onScopeChanged() {
        if (mState != null) mState.clear();
        load(false);
    }

    /**
     * Your groups at any depth whose name or path has {@code query}, and, for "yours", your
     * projects' groups that match: one request, plus your projects (#309).
     */
    private static List<GitLabGroup> searchGroups(boolean force, String scope, String query)
            throws Exception {
        GitLabGroupService service = ServiceFactory.get(GitLabGroupService.class, force);
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put(SCOPE_OWNER.equals(scope) ? "owned" : "min_access_level",
                SCOPE_OWNER.equals(scope) ? "true" : "10");
        params.put("search", query);
        List<GitLabGroup> groups = new ArrayList<>();
        List<GitLabGroup> batch = ApiHelpers.throwOnFailure(
                service.listGroupsAnyDepth(params, 1, PAGE_SIZE).blockingGet());
        if (batch != null) groups.addAll(batch);
        if (SCOPE_MINE.equals(scope)) {
            List<GitLabGroup> mine = withGroupsOfMyProjects(groups, force, false);
            String q = query.toLowerCase(java.util.Locale.ROOT);
            for (GitLabGroup g : mine) {
                if (groups.contains(g)) continue;
                String name = g.name != null ? g.name.toLowerCase(java.util.Locale.ROOT) : "";
                String path = g.fullPath != null ? g.fullPath.toLowerCase(java.util.Locale.ROOT) : "";
                if (name.contains(q) || path.contains(q)) groups.add(g);
            }
        }
        return groups;
    }

    private static List<GitLabGroup> loadAllGroups(boolean force, boolean byActivity,
            String scope) {
        GitLabGroupService service = ServiceFactory.get(GitLabGroupService.class, force);
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put(SCOPE_OWNER.equals(scope) ? "owned" : "min_access_level",
                SCOPE_OWNER.equals(scope) ? "true" : "10");
        List<GitLabGroup> groups = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<GitLabGroup> batch = ApiHelpers.throwOnFailure(
                    service.listGroupsWithParams(params, page, PAGE_SIZE).blockingGet());
            if (batch == null || batch.isEmpty()) break;
            groups.addAll(batch);
            if (batch.size() < PAGE_SIZE) break;
        }
        // Name order comes from the API; latest activity first on request (#175).
        return byActivity ? GroupTree.sortByActivity(groups, force).blockingGet() : groups;
    }

    /** Groups shown per account, filter and sort, while the app is open (#308). */
    private static final androidx.collection.LruCache<String, Data> sCache =
            new androidx.collection.LruCache<>(8);

    /**
     * {@code groups} plus the top-level groups of the projects you're a member of that aren't
     * already listed, e.g. testG for a Guest on testG/testp (#308). Your projects' pages and the
     * missing groups are asked for in parallel; name order, or appended when sorted by activity.
     */
    private static List<GitLabGroup> withGroupsOfMyProjects(List<GitLabGroup> groups,
            boolean force, boolean byActivity) throws Exception {
        java.util.Set<String> listed = new java.util.HashSet<>();
        for (GitLabGroup g : groups) {
            listed.add((g.fullPath != null ? g.fullPath : g.path).toLowerCase(java.util.Locale.ROOT));
        }
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("membership", "true");
        params.put("simple", "true");
        com.gl4a.gitlab.service.GitLabProjectService projects =
                ServiceFactory.get(com.gl4a.gitlab.service.GitLabProjectService.class, force);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            // The first page says how many there are; the rest at once
            retrofit2.Response<List<com.gl4a.gitlab.model.GitLabProject>> first =
                    projects.listProjectsWithParams(params, 1, PAGE_SIZE).blockingGet();
            List<com.gl4a.gitlab.model.GitLabProject> all =
                    new ArrayList<>(ApiHelpers.throwOnFailure(first));
            int pages = 1;
            try {
                String total = first.headers().get("X-Total-Pages");
                if (total != null) pages = Math.min(Integer.parseInt(total.trim()), MAX_PAGES);
            } catch (NumberFormatException ignored) {
            }
            List<java.util.concurrent.Future<List<com.gl4a.gitlab.model.GitLabProject>>> rest =
                    new ArrayList<>();
            for (int page = 2; page <= pages; page++) {
                final int p = page;
                rest.add(pool.submit(() -> ApiHelpers.throwOnFailure(
                        projects.listProjectsWithParams(params, p, PAGE_SIZE).blockingGet())));
            }
            for (java.util.concurrent.Future<List<com.gl4a.gitlab.model.GitLabProject>> f : rest) {
                all.addAll(f.get());
            }
            java.util.Set<String> missing = new java.util.LinkedHashSet<>();
            for (com.gl4a.gitlab.model.GitLabProject p : all) {
                if (p.namespace == null || !"group".equals(p.namespace.kind)) continue;
                String full = p.namespace.fullPath != null ? p.namespace.fullPath : p.namespace.path;
                if (full == null) continue;
                String top = full.split("/")[0];
                if (!listed.contains(top.toLowerCase(java.util.Locale.ROOT))) missing.add(top);
            }
            GitLabGroupService service = ServiceFactory.get(GitLabGroupService.class, force);
            List<java.util.concurrent.Future<GitLabGroup>> lookups = new ArrayList<>();
            for (String path : missing) {
                lookups.add(pool.submit(() -> {
                    try {
                        return ApiHelpers.throwOnFailure(service.getGroupByPath(
                                android.net.Uri.encode(path)).blockingGet());
                    } catch (Exception e) {
                        return null; // not visible to you after all: left out
                    }
                }));
            }
            List<GitLabGroup> result = new ArrayList<>(groups);
            List<GitLabGroup> extra = new ArrayList<>();
            for (java.util.concurrent.Future<GitLabGroup> f : lookups) {
                GitLabGroup g = f.get();
                if (g != null) extra.add(g);
            }
            if (extra.isEmpty()) return groups;
            result.addAll(extra);
            if (!byActivity) {
                java.util.Collections.sort(result, (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                        a.name != null ? a.name : "", b.name != null ? b.name : ""));
            }
            return result;
        } finally {
            pool.shutdown();
        }
    }

    private void show(Data data) {
        show(data, false);
    }

    /** @param keepOpen keep the groups opened in the list already shown (#320) */
    private void show(Data data, boolean keepOpen) {
        View view = getView();
        if (view == null || mTree == null) return;
        view.findViewById(R.id.progress).setVisibility(View.GONE);
        TextView empty = view.findViewById(R.id.tv_empty);
        empty.setText(mQuery != null ? R.string.no_search_groups_found
                : R.string.groups_none_mine);
        empty.setVisibility(data.groups.isEmpty() ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.ll_tree).setVisibility(View.VISIBLE);
        if (keepOpen) {
            mTree.updateRoots(data.groups, data.roles);
        } else {
            mTree.setRoots(data.groups, data.roles);
        }
    }
}
