package com.gl4a.utils;

import android.content.Context;
import android.util.Log;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabMembershipsResponse;
import com.gl4a.gitlab.service.GitLabGraphQLService;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.reactivex.Single;
import retrofit2.Response;

/**
 * The current user's role in each group and project they belong to, for role badges (#172).
 * GitLab's REST list endpoints don't include the viewer's role, so all memberships are loaded
 * once via GraphQL. Items without a direct membership inherit the role of their nearest
 * member ancestor group, as GitLab does.
 */
public final class MembershipRoles {
    private static final String QUERY =
            "query($groupsAfter: String, $projectsAfter: String) { currentUser {"
            + " groupMemberships(first: 100, after: $groupsAfter) {"
            + " pageInfo { hasNextPage endCursor }"
            + " nodes { group { fullPath } accessLevel { stringValue } } }"
            + " projectMemberships(first: 100, after: $projectsAfter) {"
            + " pageInfo { hasNextPage endCursor }"
            + " nodes { project { fullPath } accessLevel { stringValue } } } } }";
    private static final int MAX_PAGES = 20;

    /** full path → GraphQL access level ("OWNER", "MAINTAINER", …) */
    private final Map<String, String> mRoles;

    private MembershipRoles(Map<String, String> roles) {
        mRoles = roles;
    }

    /**
     * Another user's roles, from their memberships: full path → access level name
     * ("MAINTAINER"). Items inside a group inherit its role, as for the viewer (#240).
     */
    public static MembershipRoles of(Map<String, String> rolesByPath) {
        Map<String, String> roles = new HashMap<>();
        for (Map.Entry<String, String> e : rolesByPath.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                roles.put(e.getKey().toLowerCase(), e.getValue());
            }
        }
        return new MembershipRoles(roles);
    }

    private static MembershipRoles sCached;
    private static String sCachedLogin;
    private static long sCachedAt;
    private static final long CACHE_MS = 5 * 60 * 1000;

    /**
     * {@link #load()}, reused for 5 minutes for the same account, so that every project list
     * can show role badges without loading all memberships again (#233).
     */
    public static synchronized Single<MembershipRoles> loadCached() {
        String login = Gl4Application.get().getAuthLogin();
        if (sCached != null && login != null && login.equals(sCachedLogin)
                && System.currentTimeMillis() - sCachedAt < CACHE_MS) {
            return Single.just(sCached);
        }
        return load();
    }

    private static synchronized void remember(MembershipRoles roles) {
        if (!roles.mRoles.isEmpty()) {
            sCached = roles;
            sCachedLogin = Gl4Application.get().getAuthLogin();
            sCachedAt = System.currentTimeMillis();
        }
    }

    /** Emits empty roles (no badges) if GraphQL is unavailable or the query fails. */
    public static Single<MembershipRoles> load() {
        return Single.fromCallable(MembershipRoles::loadAll)
                // A fresh load also refreshes what loadCached() hands out (#242)
                .doOnSuccess(MembershipRoles::remember)
                .onErrorReturn(error -> {
                    Log.d(Gl4Application.LOG_TAG, "Membership roles unavailable", error);
                    return new MembershipRoles(Collections.emptyMap());
                });
    }

    private static MembershipRoles loadAll() {
        GitLabGraphQLService service = ServiceFactory.getGraphQL(GitLabGraphQLService.class);
        Map<String, String> roles = new HashMap<>();
        String groupsAfter = null, projectsAfter = null;
        boolean moreGroups = true, moreProjects = true;
        for (int page = 0; page < MAX_PAGES && (moreGroups || moreProjects); page++) {
            Map<String, Object> variables = new HashMap<>();
            variables.put("groupsAfter", groupsAfter);
            variables.put("projectsAfter", projectsAfter);
            Map<String, Object> body = new HashMap<>();
            body.put("query", QUERY);
            body.put("variables", variables);

            Response<GitLabMembershipsResponse> response =
                    service.getMemberships(body).blockingGet();
            GitLabMembershipsResponse payload = response.body();
            if (!response.isSuccessful() || payload == null
                    || (payload.errors != null && !payload.errors.isEmpty())
                    || payload.data == null || payload.data.currentUser == null) {
                throw new IllegalStateException("GraphQL memberships query failed: HTTP "
                        + response.code());
            }
            GitLabMembershipsResponse.CurrentUser user = payload.data.currentUser;
            if (moreGroups) {
                add(roles, user.groupMemberships, true);
                groupsAfter = nextCursor(user.groupMemberships);
                moreGroups = groupsAfter != null;
            }
            if (moreProjects) {
                add(roles, user.projectMemberships, false);
                projectsAfter = nextCursor(user.projectMemberships);
                moreProjects = projectsAfter != null;
            }
        }
        return new MembershipRoles(roles);
    }

    private static void add(Map<String, String> roles,
            GitLabMembershipsResponse.Connection connection, boolean groups) {
        if (connection == null || connection.nodes == null) return;
        for (GitLabMembershipsResponse.Node node : connection.nodes) {
            GitLabMembershipsResponse.Namespace ns = groups ? node.group : node.project;
            if (ns != null && ns.fullPath != null && node.accessLevel != null) {
                roles.put(ns.fullPath.toLowerCase(), node.accessLevel.stringValue);
            }
        }
    }

    private static String nextCursor(GitLabMembershipsResponse.Connection connection) {
        return connection != null && connection.pageInfo != null
                && connection.pageInfo.hasNextPage ? connection.pageInfo.endCursor : null;
    }

    /** Display label for the role at {@code fullPath}, direct or inherited; null if none. */
    public String labelFor(Context context, String fullPath) {
        if (fullPath == null) return null;
        String path = fullPath.toLowerCase();
        while (true) {
            String role = mRoles.get(path);
            if (role != null) return label(context, role);
            int slash = path.lastIndexOf('/');
            if (slash <= 0) return null;
            path = path.substring(0, slash);
        }
    }

    /** A GraphQL access level name ("MAINTAINER", …) in words; null for none (#233). */
    public static String labelForName(Context context, String role) {
        return role != null ? label(context, role) : null;
    }

    private static String label(Context context, String role) {
        switch (role) {
            case "OWNER": return context.getString(R.string.role_owner);
            case "MAINTAINER": return context.getString(R.string.role_maintainer);
            case "DEVELOPER": return context.getString(R.string.role_developer);
            case "REPORTER": return context.getString(R.string.role_reporter);
            case "GUEST": return context.getString(R.string.role_guest);
            case "PLANNER": return context.getString(R.string.role_planner);
            case "MINIMAL_ACCESS": return context.getString(R.string.role_minimal_access);
            default: return null;
        }
    }

}
