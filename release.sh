#!/bin/bash
# OctoLab release helper — builds APK, copies to releases/, and attaches to GitLab release

BASE="https://dev.taskmiles.com/api/v4"
# Token read from environment variable — never hardcode credentials in source
TOK="${GITLAB_TOKEN:-}"
if [ -z "$TOK" ]; then
  echo "Error: GITLAB_TOKEN environment variable not set"
  echo "Usage: GITLAB_TOKEN=glpat-xxx ./release.sh"
  exit 1
fi
PID=290

VERSION=$(grep 'versionName' app/build.gradle | head -1 | sed 's/.*"\(.*\)".*/\1/')
SRC="app/build/outputs/apk/debug/app-debug.apk"
APK_NAME="OctoLab-v${VERSION}-debug.apk"
DST="releases/${APK_NAME}"

mkdir -p releases
cp "$SRC" "$DST"
echo "Copied to: $DST ($(du -h "$DST" | cut -f1))"

# Upload to GitLab Generic Packages registry — accessible via PRIVATE-TOKEN auth
# Unlike project uploads (/-/project/.../uploads/...), package URLs work with API tokens
echo "Uploading to GitLab Packages registry..."
TAG="v${VERSION}"
PACKAGE_URL="$BASE/projects/$PID/packages/generic/releases/${VERSION}/${APK_NAME}"
PKG_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
  -H "PRIVATE-TOKEN: $TOK" \
  -H "Content-Type: application/octet-stream" \
  --upload-file "$DST" \
  "$PACKAGE_URL")

if [ "$PKG_STATUS" = "200" ] || [ "$PKG_STATUS" = "201" ]; then
  DOWNLOAD_URL="$PACKAGE_URL"
  echo "Uploaded to packages registry: $DOWNLOAD_URL"
  APK_SIZE=$(du -h "$DST" | cut -f1)
  RESULT=$(curl -sf -X POST -H "PRIVATE-TOKEN: $TOK" -H "Content-Type: application/json" \
    -d "{\"name\":\"${APK_NAME} (${APK_SIZE})\",\"url\":\"${DOWNLOAD_URL}\",\"link_type\":\"package\",\"filepath\":\"/${APK_NAME}\"}" \
    "$BASE/projects/$PID/releases/$TAG/assets/links" 2>/dev/null)
  if echo "$RESULT" | python3 -c "import sys,json; r=json.load(sys.stdin); print('Attached to release', r.get('id'))" 2>/dev/null; then
    echo "APK attached to release $TAG"
  else
    echo "Note: could not attach to release (tag $TAG may not exist yet)"
  fi
else
  echo "Package upload failed (HTTP $PKG_STATUS) — APK saved locally only"
fi
