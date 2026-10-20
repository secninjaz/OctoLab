OctoLab Privacy Policy
======================

What information do we collect?
--------------------------------
OctoLab does not store your GitLab username or password.
Instead, you log in using a Personal Access Token (PAT), which is a revokable
access token stored locally on your device, in Android's encrypted storage.

On your device only, OctoLab also keeps:
- your bookmarks
- your recent searches, for suggestions
- your Participating notifications, up to 1,000 per account
- a debug log, only if you turn it on in Settings → Debug (off by default, at most 500 entries)

What do we use your information for?
-------------------------------------
The access token(s) stored locally on your device are used solely for authenticating
your requests to your configured GitLab instance (gitlab.com or self-hosted). They
never leave your device except as authentication headers sent directly to your
configured GitLab instance.

**OctoLab has no servers or backend of its own.** All communication happens directly
between your device and the GitLab instance(s) you have configured. No data passes
through any SecNinjaz or third-party infrastructure. Your data never leaves the
path between your device and your GitLab instance.

Bookmarks are stored only on your device and are never transmitted anywhere.

Background checks for notifications contact only the GitLab instance(s) you have added.

Images and avatars in GitLab content (e.g. Gravatar avatars, images in descriptions) are
loaded from where they are hosted, as they are on GitLab's website.

The debug log never leaves your device unless you share it yourself. Access tokens are
never written to it. You can share a redacted copy, with server addresses, usernames,
names and project paths replaced by placeholders.

How do we protect your information?
-------------------------------------
All collected data is stored only on your local device and is never shared with any
third party. Access tokens are transmitted only to the GitLab instance you have
configured, as required for authentication.

Android's own backup, if you use it, can include your settings, bookmarks and recent
searches. It never includes your access tokens, your Participating notifications or the
debug log.

Your consent
-------------
By using the app, you consent to this privacy policy.

Changes to our Privacy Policy
-------------------------------
If we change this privacy policy, changes will be published in the app's release notes
and in the repository at https://github.com/secninjaz/OctoLab
