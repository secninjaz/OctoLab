# Security Policy

## Supported Versions

| Version | Supported |
|---|---|
| Latest release | ✅ |
| Older releases | ❌ |

We support only the latest released version of OctoLab.

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Email: **octolab@secninjaz.com**

Include:
- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Any suggested fix (optional)

We will acknowledge receipt within 48 hours and aim to release a fix within 14 days for critical issues.

## Scope

- OctoLab Android application source code
- Authentication handling (OAuth2, PAT)
- Data storage and transmission

Out of scope: the GitLab instance itself (report those to your GitLab administrator or GitLab's own security team).

## Disclosure Policy

We follow responsible disclosure. Please allow us reasonable time to fix the issue before public disclosure.

## APK Signing Certificate

All release APKs are signed with the OctoLab release key. Verify any APK with:

```bash
apksigner verify --print-certs OctoLab-<version>-release.apk
```

Expected certificate fingerprints:

```
Owner:  CN=SecNinjaz, OU=OctoLab, O=SecNinjaz, L=India, ST=India, C=IN
SHA-1:  86:92:BA:FF:38:DC:8C:E5:92:41:9C:B6:4E:D5:F9:A4:79:DF:25:90
SHA-256: 98:AB:5A:D0:EC:36:EF:8A:F4:FD:D9:C2:CD:37:95:9B:79:9D:FD:9C:D5:6A:34:AF:4D:CD:37:94:92:45:22:5E
```

An APK with different fingerprints has not been signed by SecNinjaz and should not be trusted.
