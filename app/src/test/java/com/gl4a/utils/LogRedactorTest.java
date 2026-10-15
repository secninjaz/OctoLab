package com.gl4a.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The debug log's redaction (#278). A canary account and server stand in for real ones: no
 * canary value may survive in any kind of log line, including kinds future features may add.
 * Plain JUnit, no Android: also run on its own in CI (test:redaction).
 */
public class LogRedactorTest {
    private static final String SERVER = "canary-git.internal.example-corp.net";
    private static final String USER = "canaryuser";
    private static final String NAME = "Canary Person";
    private static final String TOKEN = "canary-secret-0123456789";

    private static LogRedactor redactor() {
        return new LogRedactor()
                .addServer("https://" + SERVER)
                .addServer("https://gitlab.com")
                .addAccount(USER, NAME)
                .addSecret(TOKEN);
    }

    private static final String[] LINES = {
            "10-14 08:16:55.735 [API] GET https://" + SERVER
                    + "/api/v4/projects/344/issues?iids%5B%5D=1&with_labels_details=true"
                    + "&per_page=100 → 200",
            "10-14 08:18:08.802 [API] GET https://" + SERVER
                    + "/api/v4/projects/canary-group%2Fsecret-project → 200",
            "10-14 08:18:09.000 [API] GET https://" + SERVER + "/canary-group/secret-project"
                    + "/-/issues/12#note_345 → 200",
            "10-14 08:18:10.000 [API] GET https://" + SERVER + "/api/v4/users?username="
                    + USER + " → 200",
            "10-14 08:18:11.000 [API] GET https://gitlab.com/api/v4/projects/"
                    + "private-group%2Fprivate-repo/repository/tree?ref=feature-branch → 200",
            "10-14 20:34:55.324 [API] GET https://" + SERVER + "/api/v4/todos?state=pending"
                    + " failed: ConnectException — Failed to connect to " + SERVER
                    + "/192.168.119.227:443",
            "10-14 20:34:56.976 [API] GET https://" + SERVER + "/api/v4/todos failed:"
                    + " UnknownHostException — Unable to resolve host \"" + SERVER + "\"",
            "10-14 20:34:57.006 [Worker] Checking to-dos failed for " + USER
                    + ": UnknownHostException — Unable to resolve host \"gitlab.com\"",
            "10-14 20:35:00.000 [Worker] Comments [" + USER + "]: GitLab not reachable",
            "10-14 20:36:00.000 [UI] Opened profile of " + NAME + " (@" + USER + ")",
            "10-14 20:37:00.000 [API] GET https://" + USER + ":" + TOKEN + "@" + SERVER
                    + "/api/v4/user?private_token=" + TOKEN + " → 401",
            "10-14 20:38:00.000 [Future] something new mentions " + SERVER.toUpperCase()
                    + " and fe80:0:0:0:200:f8ff:fe21:67cf and someone@" + SERVER,
    };

    private static String log() {
        return String.join("\n", LINES);
    }

    @Test
    public void noCanaryValueSurvives() {
        String out = redactor().redact(log()).text.toLowerCase();
        for (String canary : new String[] { SERVER, USER, NAME.toLowerCase(), TOKEN,
                "canary", "secret-project", "canary-group", "private-repo", "private-group",
                "feature-branch", "192.168.119.227", "fe80", "someone@" }) {
            assertFalse("Left in the log: " + canary + "\n" + out, out.contains(canary));
        }
    }

    @Test
    public void keepsWhatMakesTheLogReadable() {
        String out = redactor().redact(log()).text;
        assertTrue(out, out.startsWith(LogRedactor.HEADER));
        assertTrue(out, out.contains("10-14 08:16:55.735 [API] GET https://server1.example.com"
                + "/api/v4/projects/344/issues?iids%5B%5D=1&with_labels_details=true"
                + "&per_page=100 → 200"));
        assertTrue(out, out.contains("/-/issues/12#note_345"));
        assertTrue(out, out.contains("https://gitlab.com/api/v4/projects/"));
        assertTrue(out, out.contains("UnknownHostException"));
        assertTrue(out, out.contains("Unable to resolve host \"gitlab.com\""));
        assertTrue(out, out.contains("Checking to-dos failed for user1"));
        // Log times aren't taken for addresses
        assertTrue(out, out.contains("20:34:55.324"));
    }

    @Test
    public void sameValueSamePlaceholder() {
        String out = redactor().redact(log()).text;
        int first = out.indexOf("server1.example.com");
        assertTrue(out, first >= 0);
        assertFalse(out, out.contains("server2.example.com"));
        assertTrue(out, out.contains("/api/v4/projects/name1%2Fname2"));
        assertTrue(out, out.contains("/name1/name2/-/issues/12"));
    }

    @Test
    public void listsWhatWasReplaced() {
        LogRedactor.Result result = redactor().redact(log());
        assertEquals("server1.example.com", result.replacements.get(SERVER));
        assertEquals("user1", result.replacements.get(USER));
    }

    @Test
    public void accountWithoutDisplayNameIsAUser() {
        LogRedactor.Result result = new LogRedactor()
                .addAccount("plain-user", "plain-user")
                .redact("Checking to-dos failed for plain-user");
        assertTrue(result.text, result.text.endsWith("failed for user1"));
        assertEquals("user1", result.replacements.get("plain-user"));
    }

    @Test
    public void commonBranchesAndGitLabWordsStay() {
        String out = new LogRedactor().redact("GET https://gitlab.com/api/v4/projects/5/"
                + "starrers?ref=main and /projects/5/issues/1/resource_milestone_events").text;
        assertTrue(out, out.contains("/starrers?ref=main"));
        assertTrue(out, out.contains("/resource_milestone_events"));
    }

    @Test
    public void javaNamesAreNotHosts() {
        String out = new LogRedactor().redact("failed: java.net.UnknownHostException at"
                + " com.gl4a.worker.NotificationsWorker").text;
        assertTrue(out, out.contains("java.net.UnknownHostException"));
        assertTrue(out, out.contains("com.gl4a.worker.NotificationsWorker"));
    }

    @Test
    public void unknownHostsInTextAreReplaced() {
        String out = new LogRedactor().redact("connecting to other.company.com now").text;
        assertFalse(out, out.contains("other.company.com"));
    }
}
