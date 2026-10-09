package com.gl4a.utils;

import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabGroup;
import com.gl4a.gitlab.model.GitLabMembership;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.service.GitLabUserService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Response;

/**
 * Everything a user is a member of, for their profile's "Member of" card and full list (#240):
 * their groups, their direct projects outside those groups, and their roles in all of it as
 * {@link MembershipRoles}, so items inside a group show the role inherited from it, as in the
 * Groups screen. GitLab only answers this for admins. Blocking: call in the background.
 */
public final class UserMemberships {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 5;

    public final List<GitLabGroup> groups = new ArrayList<>();
    /** Direct project memberships not inside one of {@link #groups} */
    public final List<GitLabProject> otherProjects = new ArrayList<>();
    /** All direct project memberships, as GitLab counts them */
    public int projectCount;
    public MembershipRoles roles;

    private UserMemberships() {}

    /** Throws if GitLab refuses (not an admin), so the caller can hide the section. */
    public static UserMemberships load(long userId, String login, boolean force) {
        GitLabUserService service = ServiceFactory.get(GitLabUserService.class, force);
        UserMemberships result = new UserMemberships();
        Map<String, String> rolesByPath = new HashMap<>();

        List<GitLabMembership> groupMemberships = all(service, userId, "Namespace");
        List<Long> groupIds = new ArrayList<>();
        Map<Long, Integer> groupLevels = new HashMap<>();
        for (GitLabMembership m : groupMemberships) {
            groupIds.add(m.sourceId);
            groupLevels.put(m.sourceId, m.accessLevel);
        }
        List<GitLabGroup> allGroups = UserProjectRoles.loadGroups(groupIds);
        for (GitLabGroup group : allGroups) {
            rolesByPath.put(group.fullPath, accessLevelName(groupLevels.get(group.id)));
        }
        // A group inside another of their groups shows within that one, not again as a root;
        // its own role still applies there through rolesByPath (#241)
        for (GitLabGroup group : allGroups) {
            if (!insideAny(group.fullPath, allGroups)) result.groups.add(group);
        }

        List<GitLabMembership> projectMemberships = all(service, userId, "Project");
        result.projectCount = projectMemberships.size();
        List<Long> projectIds = new ArrayList<>();
        for (GitLabMembership m : projectMemberships) projectIds.add(m.sourceId);
        // One GraphQL query per 100 projects (its page size)
        Map<Long, GitLabProject> projects = new HashMap<>();
        for (int i = 0; i < projectIds.size(); i += PAGE_SIZE) {
            projects.putAll(UserProjectRoles.load(login,
                    projectIds.subList(i, Math.min(i + PAGE_SIZE, projectIds.size()))).projects);
        }
        for (GitLabMembership m : projectMemberships) {
            GitLabProject project = projects.get(m.sourceId);
            if (project == null) continue;
            rolesByPath.put(project.pathWithNamespace, accessLevelName(m.accessLevel));
            if (!insideAny(project.pathWithNamespace, result.groups)) {
                result.otherProjects.add(project);
            }
        }
        result.roles = MembershipRoles.of(rolesByPath);
        return result;
    }

    private static List<GitLabMembership> all(GitLabUserService service, long userId,
            String type) {
        List<GitLabMembership> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            Response<List<GitLabMembership>> response =
                    service.getMemberships(userId, type, page, PAGE_SIZE).blockingGet();
            List<GitLabMembership> items = ApiHelpers.throwOnFailure(response);
            all.addAll(items);
            if (items.size() < PAGE_SIZE) break;
        }
        return all;
    }

    private static boolean insideAny(String path, List<GitLabGroup> groups) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        for (GitLabGroup group : groups) {
            if (group.fullPath != null && lower.startsWith(group.fullPath.toLowerCase() + "/")) {
                return true;
            }
        }
        return false;
    }

    /** A REST access level as GraphQL's name for it ("MAINTAINER", …); null if unknown. */
    public static String accessLevelName(Integer accessLevel) {
        if (accessLevel == null) return null;
        switch (accessLevel) {
            case 50: return "OWNER";
            case 40: return "MAINTAINER";
            case 30: return "DEVELOPER";
            case 20: return "REPORTER";
            case 15: return "PLANNER";
            case 10: return "GUEST";
            case 5: return "MINIMAL_ACCESS";
            default: return null;
        }
    }
}
