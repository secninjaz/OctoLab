package com.gl4a.utils;

import android.util.Log;

import com.gl4a.Gl4Application;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabProjectsByIdResponse;
import com.gl4a.gitlab.service.GitLabGraphQLService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Response;

/**
 * One user's role in a set of projects, plus the projects' details, from a single GraphQL query
 * (#233). GitLab answers wherever the viewer may see the project's members, admin or not, and
 * includes roles inherited from groups. Blocking: call from a background thread.
 */
public final class UserProjectRoles {
    private static final String QUERY = "query($ids: [ID!], $user: String) {"
            + " projects(ids: $ids, first: 100) { nodes {"
            + " id fullPath name nameWithNamespace description visibility starCount forksCount"
            + " avatarUrl archived"
            + " projectMembers(search: $user, relations: [DIRECT, INHERITED]) {"
            + " nodes { accessLevel { integerValue stringValue } user { username } } } } } }";

    /** project ID → project, as GraphQL describes it */
    public final Map<Long, GitLabProject> projects = new HashMap<>();
    /** project ID → the user's access level name ("MAINTAINER", …); missing if unknown */
    public final Map<Long, String> roles = new HashMap<>();

    private UserProjectRoles() {}

    private static final String GROUPS_QUERY = "query($ids: [ID!]) {"
            + " groups(ids: $ids, first: 100) { nodes {"
            + " id fullPath name avatarUrl visibility description } } }";

    /**
     * The details of groups known only by ID, in the given order (#236). Blocking; never
     * throws, groups that can't be read are left out.
     */
    public static java.util.List<com.gl4a.gitlab.model.GitLabGroup> loadGroups(
            java.util.List<Long> groupIds) {
        java.util.List<com.gl4a.gitlab.model.GitLabGroup> groups = new ArrayList<>();
        if (groupIds == null || groupIds.isEmpty()) return groups;
        try {
            List<String> ids = new ArrayList<>();
            for (long id : groupIds) ids.add("gid://gitlab/Group/" + id);
            Map<String, Object> variables = new HashMap<>();
            variables.put("ids", ids);
            Map<String, Object> body = new HashMap<>();
            body.put("query", GROUPS_QUERY);
            body.put("variables", variables);
            Response<com.gl4a.gitlab.model.GitLabGroupsByIdResponse> response =
                    ServiceFactory.getGraphQL(GitLabGraphQLService.class)
                            .getGroupsById(body).blockingGet();
            com.gl4a.gitlab.model.GitLabGroupsByIdResponse payload = response.body();
            if (!response.isSuccessful() || payload == null || payload.data == null
                    || payload.data.groups == null || payload.data.groups.nodes == null) {
                return groups;
            }
            Map<Long, com.gl4a.gitlab.model.GitLabGroup> byId = new HashMap<>();
            for (com.gl4a.gitlab.model.GitLabGroupsByIdResponse.Node node
                    : payload.data.groups.nodes) {
                byId.put(node.numericId(), node.toGroup());
            }
            for (long id : groupIds) {
                if (byId.containsKey(id)) groups.add(byId.get(id));
            }
        } catch (Exception e) {
            Log.d(Gl4Application.LOG_TAG, "Groups by ID unavailable", e);
        }
        return groups;
    }

    /** Never throws: on failure the result is simply empty (rows without role labels). */
    public static UserProjectRoles load(String username, Collection<Long> projectIds) {
        UserProjectRoles result = new UserProjectRoles();
        if (username == null || projectIds == null || projectIds.isEmpty()) return result;
        try {
            List<String> ids = new ArrayList<>();
            for (long id : projectIds) ids.add("gid://gitlab/Project/" + id);
            Map<String, Object> variables = new HashMap<>();
            variables.put("ids", ids);
            variables.put("user", username);
            Map<String, Object> body = new HashMap<>();
            body.put("query", QUERY);
            body.put("variables", variables);
            Response<GitLabProjectsByIdResponse> response =
                    ServiceFactory.getGraphQL(GitLabGraphQLService.class)
                            .getProjectsById(body).blockingGet();
            GitLabProjectsByIdResponse payload = response.body();
            if (!response.isSuccessful() || payload == null || payload.data == null
                    || payload.data.projects == null || payload.data.projects.nodes == null) {
                return result;
            }
            for (GitLabProjectsByIdResponse.Node node : payload.data.projects.nodes) {
                long id = node.numericId();
                if (id == 0) continue;
                result.projects.put(id, node.toProject());
                // search is fuzzy ("jay" also finds "jayb"): keep exact matches, highest role
                int best = -1;
                String bestName = null;
                if (node.projectMembers != null && node.projectMembers.nodes != null) {
                    for (GitLabProjectsByIdResponse.Member m : node.projectMembers.nodes) {
                        if (m.user == null || m.accessLevel == null
                                || !username.equalsIgnoreCase(m.user.username)) continue;
                        if (m.accessLevel.integerValue > best) {
                            best = m.accessLevel.integerValue;
                            bestName = m.accessLevel.stringValue;
                        }
                    }
                }
                if (bestName != null) result.roles.put(id, bestName);
            }
        } catch (Exception e) {
            Log.d(Gl4Application.LOG_TAG, "User project roles unavailable", e);
        }
        return result;
    }
}
