# Releasing an APK

A tag publishes a release: `.github/workflows/release.yml` tests, builds a minified release APK,
signs it, and attaches it to a GitHub pre-release. Decision record: [ADR 0013](adr/0013-apk-releases.md).

## One-time setup: the signing key

Android only installs an update that is signed with the same key as the installed app, so make
one key and keep it. Without it, releases are signed with a throwaway debug key and every release
needs an uninstall first.

```bash
keytool -genkeypair -v -keystore yuvomi-release.jks -alias yuvomi \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Yuvomi for Android"
base64 -w0 yuvomi-release.jks > yuvomi-release.jks.b64   # macOS: base64 -i yuvomi-release.jks
```

Store `yuvomi-release.jks` and its password somewhere safe outside the repository. Then, in
*Settings → Secrets and variables → Actions → New repository secret*, add:

| Secret | Value |
|---|---|
| `YUVOMI_KEYSTORE_BASE64` | contents of `yuvomi-release.jks.b64` |
| `YUVOMI_KEYSTORE_PASSWORD` | the keystore password |
| `YUVOMI_KEY_ALIAS` | `yuvomi` |
| `YUVOMI_KEY_PASSWORD` | the key password (the same as the keystore password unless you chose otherwise) |

Delete `yuvomi-release.jks.b64` afterwards.

## Each release

1. Make sure `main` is green.
2. Try the release build on a device (it is minified, unlike the CI debug APK):
   build it as below, install it, sign in, open the overview and tasks, tick off a task.
3. Tag and push:

   ```bash
   git tag v0.2.0 && git push origin v0.2.0
   ```

   The tag must be `v<major>.<minor>.<patch>`, with minor and patch at most 99, and higher than the
   last release, because the version code is derived from it.
4. The release appears under *Releases* with `yuvomi-android-0.2.0.apk`. Its notes give the
   signing certificate's SHA-256, which stays the same from release to release with the real key.

## Building a signed release locally

```bash
YUVOMI_KEYSTORE_PATH=/path/to/yuvomi-release.jks \
YUVOMI_KEYSTORE_PASSWORD=... YUVOMI_KEY_ALIAS=yuvomi YUVOMI_KEY_PASSWORD=... \
./gradlew assembleRelease -Pyuvomi.versionName=0.2.0 -Pyuvomi.versionCode=200
# app/build/outputs/apk/release/app-release.apk
```

Without the key variables, `assembleRelease` gives `app-release-unsigned.apk`; add
`-Pyuvomi.debugSignRelease=true` to sign it with your local debug key instead.

## Installing

Open the release page on the phone, download the APK, open it, and allow the browser or file
manager to install apps when Android asks. Android 8.0 or newer.
