# Signing Key Setup Instructions

## Step 1 — Store in GitLab CI/CD variables
Go to: Settings → CI/CD → Variables → Add variable

| Variable | Value | Protected | Masked |
|---|---|---|---|
| `KEYSTORE_BASE64` | (base64 from KEYSTORE_SECRETS.txt) | ✓ | ✓ |
| `KEYSTORE_PASSWORD` | (password from KEYSTORE_SECRETS.txt) | ✓ | ✓ |
| `KEY_ALIAS` | `octolab-release` | ✓ | ✗ |
| `KEY_PASSWORD` | (same as KEYSTORE_PASSWORD for PKCS12) | ✓ | ✓ |

## Step 2 — Store in GitHub Actions secrets (for mirror repo)
Go to: github.com/secninjaz/octolab → Settings → Secrets → Actions

Same 4 variables as above.

## Step 3 — Offline backup
1. Copy `octolab-release.jks` to encrypted USB drive or password manager vault
2. Store KEYSTORE_SECRETS.txt in same secure location
3. **Delete KEYSTORE_SECRETS.txt from this machine after storing**

## Step 4 — Delete keystore from repo directory
```bash
rm octolab-release.jks KEYSTORE_SECRETS.txt
```
The keystore must NEVER be committed. It lives only in CI secrets + offline backup.

---
⚠️ WARNING: If the keystore is lost, you cannot publish updates to existing app installs.
