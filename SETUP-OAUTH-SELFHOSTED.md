# Sign in with OAuth, and open links in the app, on your own GitLab instance

> **You don't need this guide to use OctoLab with your own GitLab.** The released app (F-Droid, Obtainium, GitHub) works with any self-hosted instance out of the box: on the sign-in screen, enter your instance's address and a Personal Access Token.
>
> This guide is only for two extras, which need a build of the app made for your instance:
> - **Sign in with OAuth** on your instance, in the browser, instead of with a token
> - **Open your instance's links in the app** by default (step 4)

The released app offers OAuth for gitlab.com only, because OAuth needs an application registered on each instance, and its ID built into the app. This guide uses the app's `internal` build, made for one self-hosted instance: you can sign in to it with OAuth, and its links open in the app. gitlab.com sign-in and tokens keep working in that build too.

## How it works

- OctoLab signs in with OAuth 2 and PKCE, as a public app: there is no client secret, so nothing secret is built into the app.
- The instance's address and the application's ID are given to the build, not written in the repository.
- The sign-in page opens in the browser; the app never sees the password.
- When GitLab answers, it sends the user back to the app at `<application id>://oauth`.

## 1. Register OctoLab on your instance

On your GitLab instance, add an application. Any of these works:

- For everyone on the instance (an administrator): **Admin area → Applications → Add new application**
- For a group: **Group → Settings → Applications**
- For yourself: **User settings → Applications → Add new application**

Fill it in:

| Field | Value |
|---|---|
| Name | `OctoLab` (any name; it's shown on the "Authorize" page) |
| Redirect URI | One per line, for each build you'll install (see below) |
| Confidential | **Unchecked** (the app is public and uses PKCE) |
| Scopes | `api` only |

Redirect URIs: the app's application ID, then `://oauth`. For the `internal` build, unchanged:

```
com.secninjaz.octolab.internal://oauth
com.secninjaz.octolab.internal.debug://oauth
```

If you change `applicationId` in `app/build.gradle` (for example, to install your fork alongside OctoLab), use your own: `<your application id>.internal://oauth`, and `<your application id>.internal.debug://oauth` for debug builds.

Save, and copy the **Application ID**. You don't need the secret.

## 2. Give the build your instance and the Application ID

The `internal` build reads two settings:

| Setting | Environment variable | or Gradle property | Value |
|---|---|---|---|
| Your instance's host | `INTERNAL_GITLAB_HOST` | `-PinternalGitlabHost=…` | e.g. `gitlab.example.com` (no `https://`) |
| The Application ID | `INTERNAL_OAUTH_CLIENT_ID` | `-PinternalOAuthClientId=…` | from step 1 |

In GitLab CI on the same instance, the host comes from GitLab's own `CI_SERVER_HOST`, so you only need to add the Application ID.

### In GitLab CI

In your project: **Settings → CI/CD → Variables → Add variable**

- Key: `INTERNAL_OAUTH_CLIENT_ID`
- Value: the Application ID
- Visibility: Visible or Masked. It isn't a secret, but masking keeps it out of job logs.
- Protected: only if you build from protected branches and tags only.

The repository's `.gitlab-ci.yml` builds the `internal` APKs (`assembleInternalDebug`, `assembleInternalRelease`); they pick the variable up.

If your pipeline runs on another GitLab than the one you sign in to, also add `INTERNAL_GITLAB_HOST`.

### In GitHub Actions or another CI

Add both as secrets or variables, `INTERNAL_GITLAB_HOST` and `INTERNAL_OAUTH_CLIENT_ID`, pass them to the build step as environment variables, and build an `internal` variant:

```bash
./gradlew assembleInternalRelease
```

### On your computer

```bash
./gradlew assembleInternalDebug \
    -PinternalGitlabHost=gitlab.example.com \
    -PinternalOAuthClientId=YOUR_APPLICATION_ID
```

The APK is at `app/build/outputs/apk/internal/debug/app-internal-debug.apk`.

For signed release builds, see [SIGNING_SETUP.md](SIGNING_SETUP.md).

## 3. Sign in

1. Install the `internal` APK.
2. On the sign-in screen, enter your instance's address.
3. Tap **Sign in with &lt;your instance&gt;**. Your instance's own sign-in page opens in the browser, then asks to authorize OctoLab.
4. After you authorize, you're back in the app, signed in.

The app renews its access automatically, and logging out revokes it on your instance.

## 4. Open your instance's links in the app

The `internal` build is set up to handle links to the host it was built for (step 2), as verified Android App Links. When your instance confirms the app, links to it open in OctoLab straight away, with no "Open with" question and nothing to turn on in Android's settings.

Your instance confirms the app with a file at `https://<your host>/.well-known/assetlinks.json` that names the app and its signing certificate. Android fetches it when the app is installed or updated.

### Get the signing certificate's fingerprint

The SHA-256 fingerprint of the key that signs your APKs. From the keystore:

```bash
keytool -list -v -keystore your-release.jks -alias your-alias | grep SHA256
```

or from a built APK:

```bash
apksigner verify --print-certs app-internal-release.apk | grep SHA-256
```

`apksigner` prints it without colons; the file needs it in upper case with a colon between each pair (`AA:BB:CC:…`).

Debug builds made on a computer are signed with that computer's debug key, which has its own fingerprint. If your CI signs debug builds with the release key, both use the same one.

### Write the file

One entry per build you install, with your application IDs and fingerprints:

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "com.secninjaz.octolab.internal",
      "sha256_cert_fingerprints": ["AA:BB:…:FF"]
    }
  },
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "com.secninjaz.octolab.internal.debug",
      "sha256_cert_fingerprints": ["AA:BB:…:FF"]
    }
  }
]
```

### Serve it from your instance

Android only accepts it over HTTPS, answered directly with status 200 (no redirect, no sign-in) and as `application/json`.

On a GitLab Linux package (Omnibus) install, with its own nginx, save the file as `/etc/gitlab/assetlinks.json` and add to `/etc/gitlab/gitlab.rb`:

```ruby
nginx['custom_gitlab_server_config'] = "location = /.well-known/assetlinks.json {\n  default_type application/json;\n  alias /etc/gitlab/assetlinks.json;\n}\n"
```

Then apply it:

```bash
sudo gitlab-ctl reconfigure
```

If something else sits in front of GitLab (your own nginx, Apache, Traefik, a load balancer), serve the file from there instead, at the same path.

Check it:

```bash
curl -i https://<your host>/.well-known/assetlinks.json
```

It should answer `200` with `content-type: application/json` and your file.

### Let Android check again

Android checks when the app is installed or updated. If you add the file after installing, reinstall or update the app, or ask Android to check again over USB:

```bash
adb shell pm verify-app-links --re-verify com.secninjaz.octolab.internal
adb shell pm get-app-links com.secninjaz.octolab.internal
```

`get-app-links` shows `verified` next to your host once it worked. Until then, you can turn the links on by hand: **Android settings → Apps → OctoLab → Open by default → Add link**.

## Troubleshooting

- **No "Sign in with …" button for your instance:** the build didn't get the Application ID, or the address you typed isn't the host it was built for. The host must match exactly (`gitlab.example.com`, not `www.gitlab.example.com`).
- **"The redirect URI included is not valid":** the application's Redirect URI doesn't match the installed build. Check the application ID of what you installed (debug builds end in `.debug`).
- **"Client authentication failed":** the application is marked Confidential. Uncheck it.
- **Your instance's links open in the browser, not the app:** see step 4. Check that the file answers 200 without a redirect, and that the application ID and fingerprint match the installed build.

## Notes

- One `internal` build is for one self-hosted instance. Other self-hosted instances keep Personal Access Token sign-in.
- The public `community` build (the releases) offers OAuth for gitlab.com only.
