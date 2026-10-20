package com.gl4a.utils;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.gl4a.R;
import com.gl4a.db.BookmarksProvider;

/**
 * THE bookmark handling for every screen that can be bookmarked (users, projects, groups,
 * issues, merge requests, commits, snippets): its menu item, saving and removing, the list's
 * icon and opening a bookmark (#263). A new kind (e.g. milestones, epics) is a type in
 * {@link BookmarksProvider.Columns}, an icon in {@link #iconFor} and a {@link Target} on its
 * screen.
 */
public final class BookmarkActions {
    private BookmarkActions() {
    }

    /** What a screen is bookmarked as. */
    public static final class Target {
        final int type;
        final String url;
        final String name;
        @Nullable final String extra;
        final long objectId;
        @Nullable final String avatarUrl;

        /**
         * @param url   the web address of what's shown, as GitLab gives it where possible; it
         *              identifies the bookmark and opens it in-app
         * @param name  the bookmark's title
         * @param extra a second line, e.g. the project and number of an issue
         */
        public Target(int type, String url, String name, @Nullable String extra) {
            this(type, url, name, extra, 0, null);
        }

        /**
         * For users, groups and projects: their ID and avatar address, for their picture in
         * the bookmark list (#265).
         */
        public Target(int type, String url, String name, @Nullable String extra, long objectId,
                @Nullable String avatarUrl) {
            this.type = type;
            this.url = url;
            this.name = name;
            this.extra = extra;
            this.objectId = objectId;
            this.avatarUrl = avatarUrl;
        }

        /** Its address, which identifies the bookmark. */
        public String url() {
            return url;
        }

        /**
         * An issue, as its screen and the To-dos and Participating rows (#289) bookmark it: by
         * GitLab's address for it, with its project and number below.
         */
        public static Target issue(String url, String title, String project, long iid) {
            return new Target(BookmarksProvider.Columns.TYPE_ISSUE, url, title,
                    project + "#" + iid);
        }

        /** A merge request, as {@link #issue} (#289). */
        public static Target mergeRequest(String url, String title, String project, long iid) {
            return new Target(BookmarksProvider.Columns.TYPE_MERGE_REQUEST, url, title,
                    project + "!" + iid);
        }

        /** A commit, with its project and short ID below, as {@link #issue} (#289). */
        public static Target commit(String url, @Nullable String title, String project,
                String sha) {
            String shortId = sha.length() > 8 ? sha.substring(0, 8) : sha;
            return new Target(BookmarksProvider.Columns.TYPE_COMMIT, url,
                    title != null ? title : shortId, project + "@" + shortId);
        }
    }

    /** Saves the bookmark, or removes it if it's saved; each says so in a toast. */
    public static void toggle(Context context, Target target) {
        if (BookmarksProvider.hasBookmarked(context, target.url)) {
            BookmarksProvider.removeBookmark(context, target.url);
        } else {
            BookmarksProvider.saveBookmark(context, target.name, target.type, target.url,
                    target.extra, target.objectId, target.avatarUrl, true);
        }
    }

    /**
     * Shows the screen's bookmark menu item as "Bookmark" or "Remove bookmark", hidden while
     * {@code target} is null (e.g. still loading). Call from onPrepareOptionsMenu.
     */
    public static void prepareMenu(Context context, Menu menu, @Nullable Target target) {
        MenuItem item = menu.findItem(R.id.bookmark);
        if (item == null) {
            return;
        }
        item.setVisible(target != null);
        if (target != null) {
            item.setTitle(BookmarksProvider.hasBookmarked(context, target.url)
                    ? R.string.remove_bookmark : R.string.bookmark);
        }
    }

    /**
     * Saves or removes the bookmark when its menu item was selected. Call from
     * onOptionsItemSelected; returns whether it handled the item.
     */
    public static boolean onMenuItemSelected(Activity activity, MenuItem item,
            @Nullable Target target) {
        if (item.getItemId() != R.id.bookmark || target == null) {
            return false;
        }
        toggle(activity, target);
        activity.invalidateOptionsMenu();
        return true;
    }

    /**
     * Opens a bookmark in-app with the current account, as links inside the app open.
     * Bookmarks are saved per account (#237), so there's no account to ask for: going through
     * the path for links from other apps asked whenever several accounts share a server.
     */
    public static void open(FragmentActivity activity, String url) {
        IntentUtils.openLinkInternallyOrExternally(activity, Uri.parse(url));
    }

    /**
     * Shows a bookmark's picture: a user's avatar, a group's or project's logo on the logo
     * frame, as everywhere else in the app (#265), else its type's icon. A user, group or
     * project bookmarked before pictures were saved is looked up once and updated.
     */
    public static void bindIcon(android.widget.ImageView view, long bookmarkId, int type,
            String name, String url, long objectId, @Nullable String avatarUrl) {
        AvatarHandler.setLogoFrame(view, false);
        switch (type) {
            case BookmarksProvider.Columns.TYPE_USER:
            case BookmarksProvider.Columns.TYPE_GROUP:
            case BookmarksProvider.Columns.TYPE_REPO:
                if (objectId <= 0) {
                    lookUpPicture(view.getContext(), bookmarkId, type, url);
                }
                break;
        }
        if (type == BookmarksProvider.Columns.TYPE_USER && objectId > 0) {
            AvatarHandler.assignAvatar(view, name, objectId, avatarUrl);
        } else if (type == BookmarksProvider.Columns.TYPE_GROUP && objectId > 0) {
            com.gl4a.gitlab.model.GitLabGroup group = new com.gl4a.gitlab.model.GitLabGroup();
            group.id = objectId;
            group.name = name;
            group.avatarUrl = avatarUrl;
            AvatarHandler.setLogoFrame(view, true);
            AvatarHandler.assignGroupLogo(view, group);
        } else if (type == BookmarksProvider.Columns.TYPE_REPO && objectId > 0) {
            com.gl4a.gitlab.model.GitLabProject project =
                    new com.gl4a.gitlab.model.GitLabProject();
            project.id = objectId;
            project.name = name;
            project.avatarUrl = avatarUrl;
            AvatarHandler.setLogoFrame(view, true);
            AvatarHandler.assignProjectLogo(view, project);
        } else {
            int icon = iconFor(type);
            if (icon != 0) {
                view.setImageResource(icon);
            } else {
                view.setImageDrawable(null);
            }
        }
    }

    /** Bookmarks already looked up this session, so a failed lookup isn't repeated. */
    private static final java.util.Set<Long> sLookedUp =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * Finds the ID and avatar of a user, group or project bookmarked before they were saved,
     * by the path in its address, and stores them in the bookmark (#265).
     */
    private static void lookUpPicture(Context context, long bookmarkId, int type, String url) {
        if (!sLookedUp.add(bookmarkId)) {
            return;
        }
        java.util.List<String> segments = Uri.parse(url).getPathSegments();
        int dash = segments.indexOf("-");
        java.util.List<String> path = dash >= 0 ? segments.subList(0, dash) : segments;
        if (type == BookmarksProvider.Columns.TYPE_GROUP && !path.isEmpty()
                && "groups".equals(path.get(0))) {
            path = path.subList(1, path.size());
        }
        if (path.isEmpty()) {
            return;
        }
        String fullPath = android.text.TextUtils.join("/", path);
        final Context app = context.getApplicationContext();
        io.reactivex.Single<Object[]> lookup;
        if (type == BookmarksProvider.Columns.TYPE_USER) {
            lookup = com.gl4a.ServiceFactory.get(
                    com.gl4a.gitlab.service.GitLabUserService.class, false)
                    .searchUsers(path.get(path.size() - 1), 1, 1)
                    .map(ApiHelpers::throwOnFailure)
                    .map(users -> users.isEmpty() ? new Object[0]
                            : new Object[] { users.get(0).id(), users.get(0).avatarUrl() });
        } else if (type == BookmarksProvider.Columns.TYPE_GROUP) {
            lookup = com.gl4a.ServiceFactory.get(
                    com.gl4a.gitlab.service.GitLabGroupService.class, false)
                    .getGroupByPath(Uri.encode(fullPath))
                    .map(ApiHelpers::throwOnFailure)
                    .map(group -> new Object[] { group.id, group.avatarUrl });
        } else {
            lookup = com.gl4a.ServiceFactory.get(
                    com.gl4a.gitlab.service.GitLabProjectService.class, false)
                    .getProjectByPath(Uri.encode(fullPath))
                    .map(ApiHelpers::throwOnFailure)
                    .map(project -> new Object[] { project.id, project.avatarUrl });
        }
        lookup.subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .subscribe(result -> {
                    if (result.length < 2 || !(result[0] instanceof Long)
                            || (Long) result[0] <= 0) {
                        return;
                    }
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(BookmarksProvider.Columns.OBJECT_ID, (Long) result[0]);
                    cv.put(BookmarksProvider.Columns.AVATAR_URL, (String) result[1]);
                    app.getContentResolver().update(android.content.ContentUris.withAppendedId(
                            BookmarksProvider.Columns.CONTENT_URI, bookmarkId), cv, null, null);
                }, error -> android.util.Log.d("BookmarkActions",
                        "No picture found for bookmark " + bookmarkId, error));
    }

    /** The bookmark list's icon for a type. */
    @DrawableRes
    public static int iconFor(int type) {
        switch (type) {
            case BookmarksProvider.Columns.TYPE_USER: return R.drawable.user_bookmark;
            case BookmarksProvider.Columns.TYPE_REPO: return R.drawable.repo_bookmark;
            case BookmarksProvider.Columns.TYPE_GROUP: return R.drawable.icon_groups;
            case BookmarksProvider.Columns.TYPE_ISSUE: return R.drawable.issue;
            case BookmarksProvider.Columns.TYPE_MERGE_REQUEST: return R.drawable.pull_request;
            case BookmarksProvider.Columns.TYPE_COMMIT: return R.drawable.commit;
            case BookmarksProvider.Columns.TYPE_SNIPPET: return R.drawable.snippet_bookmark;
            default: return 0;
        }
    }
}
