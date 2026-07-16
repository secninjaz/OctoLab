# Reproducible Builds

OctoLab aims to produce reproducible builds: anyone building from the same source tag should get a byte-for-byte identical **unsigned APK**. The signature on the published APK proves who signed it; the unsigned content equality proves the binary matches the source.

The signing key remains private. No key sharing is required for verification.

## Requirements

- JDK 21 (Temurin recommended)
- Android SDK with API 36 and Build Tools 36.0.0
- [`diffoscope`](https://diffoscope.org/) for comparison

## Build from source

```bash
git clone https://github.com/secninjaz/OctoLab.git
cd OctoLab
git checkout v1.0.9

echo 'ClientId="placeholder"' > app/client.properties
echo 'ClientSecret="placeholder"' >> app/client.properties

./gradlew assembleRelease --no-daemon
# Output: app/build/outputs/apk/release/app-release-unsigned.apk
```

## Verify the published APK

### Option 1 — diffoscope (user verification)

Compare your local build against the published signed APK. Differences should be in the signature block only, not in the app content:

```bash
diffoscope app/build/outputs/apk/release/app-release-unsigned.apk \
           OctoLab-v1.0.9-release.apk
```

If the only differences shown are inside `META-INF/` (the signature files), the build is reproducible.

### Option 2 — apksigcopier (F-Droid method)

[`apksigcopier`](https://github.com/obfusk/apksigcopier) copies the upstream signature onto your locally-built APK and verifies it. If the signature is valid, the unsigned content is identical:

```bash
pip install apksigcopier
apksigcopier patch OctoLab-v1.0.9-release.apk \
                   app/build/outputs/apk/release/app-release-unsigned.apk \
                   patched.apk
apksigner verify patched.apk && echo "✅ Content matches — build is reproducible"
```

## Verify the signing certificate

Independently confirm the published APK was signed by SecNinjaz:

```bash
apksigner verify --print-certs OctoLab-v1.0.9-release.apk
```

Expected SHA-256: `98:AB:5A:D0:EC:36:EF:8A:F4:FD:D9:C2:CD:37:95:9B:79:9D:FD:9C:D5:6A:34:AF:4D:CD:37:94:92:45:22:5E`

See [SECURITY.md](SECURITY.md) for full certificate details.

## Reproducibility status

| Factor | Status |
|--------|--------|
| All dependency versions pinned (no `SNAPSHOT`/`+`) | ✅ |
| No build timestamps in APK content | ✅ |
| Deterministic `versionCode` / `versionName` | ✅ |
| `apksigner` v2/v3 scheme (no timestamp in signature) | ✅ |
| Signing certificate fingerprint published | ✅ |
| Byte-for-byte unsigned APK verified | 🔄 Not yet formally tested — contributions welcome |

## References

- [F-Droid reproducible builds](https://f-droid.org/docs/Reproducible_Builds/)
- [apksigcopier](https://github.com/obfusk/apksigcopier)
- [diffoscope](https://diffoscope.org/)
- [reproducible-builds.org](https://reproducible-builds.org/)
