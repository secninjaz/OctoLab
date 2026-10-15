package com.gl4a.utils;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE redaction of the debug log for sharing (#278), like ntfy's censored logs: server
 * addresses, usernames, names and paths become consistent placeholders (server1.example.com,
 * user1, name1), so the log still reads. It runs once on the whole log when it's shared, never
 * per feature, and is safe by default for whatever future features log:
 * <ol>
 * <li>Web addresses keep only what's known to be safe (GitLab's API words, numbers, query
 * parameter names, a few known values); every other part is replaced.</li>
 * <li>Values the app knows (servers, usernames, display names, tokens) are replaced wherever
 * they appear, then patterns: IP addresses, email addresses, host names.</li>
 * <li>A final self-check searches the result for every known value and replaces any left.</li>
 * </ol>
 * The worst a new kind of log line can cause is too much hidden, never a leak. Plain Java, no
 * Android: it's unit tested on its own (LogRedactorTest), also in CI.
 */
public final class LogRedactor {
    /** The first line of a redacted log. */
    public static final String HEADER = "Redacted by OctoLab: server addresses, usernames, names"
            + " and paths are replaced with placeholders (server1.example.com, user1, name1);"
            + " numbers are kept. Tokens are never logged.";

    /** Hosts kept as they are: public, and needed to tell gitlab.com issues apart. */
    private static final Set<String> PUBLIC_HOSTS = new HashSet<>(Arrays.asList(
            "gitlab.com", "example.com"));

    /** Parts of web addresses that are GitLab's own words, not the user's names. */
    private static final Set<String> SAFE_WORDS = new HashSet<>(Arrays.asList(
            "api", "v4", "graphql", "-", "projects", "groups", "users", "user", "issues",
            "issue", "merge_requests", "merge_request", "notes", "discussions", "todos",
            "events", "repository", "commits", "commit", "branches", "tags", "tree", "files",
            "raw", "blob", "blobs", "compare", "pipelines", "pipeline", "jobs", "members",
            "all", "award_emoji", "labels", "milestones", "snippets", "search", "markdown",
            "uploads", "avatar", "diff", "diffs", "refs", "starred", "star", "unstar",
            "contributors", "languages", "statistics", "wikis", "releases", "environments",
            "deployments", "keys", "emails", "namespaces", "version", "subscribe",
            "unsubscribe", "time_stats", "closed_by", "related_merge_requests",
            "participants", "resource_label_events", "resource_state_events", "approvals",
            "approval_state", "changes", "versions", "merge", "rebase", "archive", "forks",
            "fork", "hooks", "protected_branches", "protected_tags", "subgroups",
            "descendant_groups", "shared_projects", "contributed_projects", "memberships",
            "followers", "following", "status", "preferences", "notification_settings",
            "oauth", "authorize", "assets", "images", "emoji", "emojis", "personal_access_tokens",
            "self", "license", "templates", "variables", "badges", "boards", "lists",
            "reorder", "move", "transfer", "export", "import", "edit", "new", "show",
            "activity", "dashboard", "explore", "help", "profile", "settings", "sign_in",
            "sign_out", "s", "uploads", "system", "appearance", "logo", "favicon.ico",
            "starrers", "resource_milestone_events", "resource_weight_events",
            "resource_iteration_events", "resource_label_event", "award_emojis", "reactions",
            "todo", "mark_as_done", "mark_as_todo", "time_estimate", "add_spent_time",
            "reset_time_estimate", "reset_spent_time", "user_agent_detail", "links",
            "blame", "submodules", "contributions", "pipeline_schedules", "artifacts", "trace",
            "retry", "cancel", "play", "approve", "unapprove", "commits_count", "context_commits",
            "draft_notes", "related_issues", "notes_count", "page", "per_page", "pages",
            // Common branch names: not private, and they keep the log readable
            "main", "master", "develop", "development", "trunk", "head"));

    /** Query values that are GitLab's own words. */
    private static final Set<String> SAFE_VALUES = new HashSet<>(Arrays.asList(
            "true", "false", "asc", "desc", "all", "opened", "closed", "merged", "locked",
            "pending", "done", "created_at", "updated_at", "last_activity_at", "name", "path",
            "id", "iid", "similarity", "priority", "label_priority", "milestone_due",
            "popularity", "commented", "created", "updated", "closed", "reopened", "pushed",
            "merged", "joined", "left", "destroyed", "expired", "approved", "created_by_me",
            "assigned_to_me", "simple", "issues", "merge_requests", "projects", "groups",
            "users", "notes", "commits", "blobs", "wiki_blobs", "milestones", "snippet_titles",
            "inline", "parallel", "text", "markdown", "json", "public", "internal", "private",
            "any", "none"));

    /** Top-level domains host names are recognised by in plain text. */
    private static final Set<String> TLDS = new HashSet<>(Arrays.asList(
            "com", "net", "org", "io", "dev", "app", "co", "de", "in", "uk", "eu", "fr", "nl",
            "ch", "at", "se", "no", "fi", "dk", "pl", "it", "es", "ru", "jp", "cn", "au", "ca",
            "us", "br", "info", "biz", "me", "tech", "ai", "cloud", "xyz", "site", "online",
            "gov", "edu", "local", "lan", "internal", "home", "corp", "intra", "localdomain",
            "test", "invalid", "onion", "eus", "space", "tools", "systems", "solutions"));

    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>()\\]\\[]+");
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\w.])");
    // At least 4 groups, so log times (20:11:00.170) aren't taken for one
    private static final Pattern IPV6 = Pattern.compile(
            "(?<![\\w:])(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}(?![\\w:.])");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");
    // Not followed by "." either, so Java names (java.net.UnknownHostException) aren't hosts
    private static final Pattern HOST = Pattern.compile(
            "(?<![\\w.@-])[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?![\\w.-])");

    /** The result: the redacted text, and what was replaced with what (not to be shared). */
    public static final class Result {
        public final String text;
        /** Original value → placeholder, in the order first replaced. */
        public final Map<String, String> replacements;

        Result(String text, Map<String, String> replacements) {
            this.text = text;
            this.replacements = replacements;
        }
    }

    private final Set<String> mServers = new HashSet<>();
    private final Set<String> mUsernames = new HashSet<>();
    private final Set<String> mNames = new HashSet<>();
    private final Set<String> mSecrets = new HashSet<>();
    /** Original → placeholder; also what the user is shown afterwards. */
    private final Map<String, String> mPlaceholders = new LinkedHashMap<>();
    private int mServerCount, mUserCount, mNameCount, mIpCount, mEmailCount;

    /** A server the app talks to, as an address or a host name; gitlab.com stays. */
    public LogRedactor addServer(String server) {
        String host = hostOf(server);
        if (host != null && !PUBLIC_HOSTS.contains(host)) mServers.add(host);
        return this;
    }

    /** An account: its username and display name, replaced wherever they appear. */
    public LogRedactor addAccount(String username, String displayName) {
        if (username != null && !username.trim().isEmpty()) mUsernames.add(username.trim());
        // A display name that's just the username is the username: shown as userN
        if (displayName != null && displayName.trim().length() > 1
                && (username == null || !displayName.trim().equalsIgnoreCase(username.trim()))) {
            mNames.add(displayName.trim());
        }
        return this;
    }

    /** A value that must never appear, e.g. a token; it's checked for even though not logged. */
    public LogRedactor addSecret(String secret) {
        if (secret != null && secret.length() >= 6) mSecrets.add(secret);
        return this;
    }

    public Result redact(String log) {
        String text = log;
        // 1. Secrets, before anything could split them
        for (String secret : mSecrets) {
            text = text.replace(secret, "***");
        }
        // 2. Web addresses: only the safe parts stay
        Matcher url = URL.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (url.find()) {
            url.appendReplacement(sb, Matcher.quoteReplacement(redactUrl(url.group())));
        }
        url.appendTail(sb);
        text = sb.toString();
        // 3. Known values in the rest of the text
        text = replaceKnown(text);
        // 4. Patterns
        text = replaceAll(text, EMAIL, v -> placeholder(v, () -> "email" + (++mEmailCount)
                + "@example.com"));
        text = replaceAll(text, IPV4, v -> placeholder(v, () -> "ip" + (++mIpCount)));
        text = replaceAll(text, IPV6, v -> placeholder(v, () -> "ip" + (++mIpCount)));
        text = replaceAll(text, HOST, v -> isHostName(v) ? server(v) : v);
        // 5. Self-check: nothing known may be left
        List<String> left = new ArrayList<>();
        for (String known : allKnown()) {
            if (containsIgnoreCase(text, known)) left.add(known);
        }
        if (!left.isEmpty()) {
            text = replaceKnown(text);
            for (String secret : mSecrets) {
                text = text.replace(secret, "***");
            }
            text = "[Redaction self-check: " + left.size() + " value(s) were replaced late]\n"
                    + text;
        }
        return new Result(HEADER + "\n" + text, Collections.unmodifiableMap(mPlaceholders));
    }

    private String redactUrl(String url) {
        int schemeEnd = url.indexOf("://") + 3;
        int pathStart = url.indexOf('/', schemeEnd);
        String authority = pathStart < 0 ? url.substring(schemeEnd)
                : url.substring(schemeEnd, pathStart);
        String rest = pathStart < 0 ? "" : url.substring(pathStart);
        // Never keep credentials in an address
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = "<credentials>@" + authority.substring(at + 1);
        String port = "";
        int colon = authority.lastIndexOf(':');
        if (colon > authority.lastIndexOf(']')) {
            port = authority.substring(colon);
            authority = authority.substring(0, colon);
        }
        String host = authority.substring(authority.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
        String prefix = authority.substring(0, authority.lastIndexOf('@') + 1);
        String newHost = PUBLIC_HOSTS.contains(host) ? host
                : IPV4.matcher(host).matches() ? placeholder(host, () -> "ip" + (++mIpCount))
                : server(host);

        String fragment = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            fragment = rest.substring(hash + 1);
            rest = rest.substring(0, hash);
            fragment = "#" + (fragment.matches("(note_)?\\d+") ? fragment : "<anchor>");
        }
        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = redactQuery(rest.substring(q + 1));
            rest = rest.substring(0, q);
        }
        // The path starts with "/" (or is empty): each part on its own
        StringBuilder newPath = new StringBuilder();
        if (!rest.isEmpty()) {
            for (String segment : rest.substring(1).split("/", -1)) {
                newPath.append('/').append(redactSegment(segment));
            }
        }
        return url.substring(0, schemeEnd) + prefix + newHost + port + newPath + query
                + fragment;
    }

    private String redactQuery(String query) {
        StringBuilder out = new StringBuilder("?");
        String[] params = query.split("&", -1);
        for (int i = 0; i < params.length; i++) {
            if (i > 0) out.append('&');
            String param = params[i];
            int eq = param.indexOf('=');
            if (eq < 0) {
                out.append(redactValue(param));
                continue;
            }
            out.append(param, 0, eq + 1).append(redactValue(param.substring(eq + 1)));
        }
        return out.toString();
    }

    /** A query value: kept if safe, "***" for anything token-like, else a placeholder. */
    private String redactValue(String value) {
        if (value.isEmpty() || value.equals("***")) return value;
        String decoded = decode(value);
        if (isSafe(decoded)) return value;
        return encode(redactPathLike(decoded));
    }

    private String redactSegment(String segment) {
        if (segment.isEmpty()) return segment;
        String decoded = decode(segment);
        if (isSafe(decoded)) return segment;
        return encode(redactPathLike(decoded));
    }

    /** "group/project" → "name1/name2"; each part consistently, usernames as userN. */
    private String redactPathLike(String value) {
        StringBuilder out = new StringBuilder();
        String[] parts = value.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append('/');
            String part = parts[i];
            if (part.isEmpty() || isSafe(part)) {
                out.append(part);
            } else if (mUsernames.contains(part)) {
                out.append(user(part));
            } else {
                out.append(placeholder(part, () -> "name" + (++mNameCount)));
            }
        }
        return out.toString();
    }

    private static boolean isSafe(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return SAFE_WORDS.contains(lower) || SAFE_VALUES.contains(lower)
                || value.matches("[-+]?\\d+([.,]\\d+)?")
                // Times GitLab is asked about, e.g. updated_after
                || value.matches("\\d{4}-\\d{2}-\\d{2}([T ][\\d:.]+(Z|[+-]\\d{2}:?\\d{2})?)?")
                || value.matches("[0-9a-f]{7,64}") // commit SHAs
                || value.matches("(note_)?\\d+");
    }

    private String replaceKnown(String text) {
        for (String server : longestFirst(mServers)) {
            text = replaceIgnoreCase(text, server, server(server));
        }
        for (String name : longestFirst(mNames)) {
            text = text.replace(name, placeholder(name, () -> "name" + (++mNameCount)));
        }
        for (String username : longestFirst(mUsernames)) {
            // As a word: "root" in "root cause" is replaced too; better too much than too little
            text = text.replaceAll("(?<![\\w.-])@?" + Pattern.quote(username) + "(?![\\w-])",
                    Matcher.quoteReplacement(user(username)));
        }
        return text;
    }

    private String server(String host) {
        return placeholder(host.toLowerCase(Locale.ROOT),
                () -> "server" + (++mServerCount) + ".example.com");
    }

    private String user(String username) {
        return placeholder(username, () -> "user" + (++mUserCount));
    }

    private interface Next {
        String get();
    }

    private String placeholder(String value, Next next) {
        String existing = mPlaceholders.get(value);
        if (existing != null) return existing;
        // A value already a placeholder stays
        if (mPlaceholders.containsValue(value)) return value;
        String created = next.get();
        mPlaceholders.put(value, created);
        return created;
    }

    private interface Replacer {
        String apply(String value);
    }

    private static String replaceAll(String text, Pattern pattern, Replacer replacer) {
        Matcher m = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** A host name in plain text: lower case labels, a known top-level domain, not public. */
    private boolean isHostName(String value) {
        if (PUBLIC_HOSTS.contains(value) || value.endsWith(".example.com")) return false;
        if (mPlaceholders.containsValue(value)) return false;
        String tld = value.substring(value.lastIndexOf('.') + 1);
        return TLDS.contains(tld) && !value.matches("[\\d.]+");
    }

    private List<String> allKnown() {
        List<String> all = new ArrayList<>(mServers);
        all.addAll(mUsernames);
        all.addAll(mNames);
        all.addAll(mSecrets);
        return all;
    }

    private boolean containsIgnoreCase(String text, String value) {
        if (mUsernames.contains(value)) {
            return Pattern.compile("(?<![\\w.-])" + Pattern.quote(value) + "(?![\\w-])")
                    .matcher(text).find();
        }
        return text.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
    }

    private static String replaceIgnoreCase(String text, String value, String replacement) {
        return Pattern.compile(Pattern.quote(value), Pattern.CASE_INSENSITIVE).matcher(text)
                .replaceAll(Matcher.quoteReplacement(replacement));
    }

    private static List<String> longestFirst(Set<String> values) {
        List<String> list = new ArrayList<>(values);
        Collections.sort(list, (a, b) -> b.length() - a.length());
        return list;
    }

    private static String hostOf(String server) {
        if (server == null) return null;
        String s = server.trim().toLowerCase(Locale.ROOT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int end = s.length();
        for (char c : new char[] { '/', ':', '?', '#' }) {
            int i = s.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        s = s.substring(0, end);
        return s.isEmpty() ? null : s;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }
}
