# Releasing an APK

`.github/workflows/release.yml` tests, builds a minified release APK, signs it, and attaches it to
a GitHub pre-release. Decision records: [ADR 0013](adr/0013-apk-releases.md),
[ADR 0014](adr/0014-continuous-delivery.md).

## How a release happens

| What you do | What gets released |
|---|---|
| Merge a change to the app into `main` | The next patch version, e.g. `0.1.4` after `0.1.3` (the first one is `0.1.0`) |
| Merge docs only | Nothing |
| *Actions → Release → Run workflow* on `main`, version empty | The next patch version now |
| *Run workflow* with a version, e.g. `0.2.0` | That version; automatic releases continue from it (`0.2.1`) |
| Push a tag `v0.2.0` | That version |

*Run workflow* works from a phone's browser. In the GitHub mobile app, use *… → Open in browser*
if the app has no *Run workflow* button.

Versions are `<major>.<minor>.<patch>` with minor and patch at most 999, and each must be higher
than the last release, because Android refuses an update with a lower version code
(`major * 1000000 + minor * 1000 + patch`). Releases run one at a time in push order.

The release does not repeat the tests; it relies on the pull request's checks. To make sure
nothing reaches `main` without them, add a branch rule: *Settings → Rules → Rulesets → New branch
ruleset*, target `main`, *Require status checks to pass* with `build` and `contract`.

## One-time setup: the signing key

Needed for automatic releases to be useful, and needs a computer with a JDK (`keytool`). Without
it every release is signed with a throwaway key and must be uninstalled before the next one
installs; switching to the real key later means one last uninstall.

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

## Before a minor or major release

The APK is minified, unlike the CI debug APK, and can fail where the debug build does not. Before
announcing a version, install it, sign in, open the overview and tasks, and tick off a task. Its
release notes give the signing certificate's SHA-256, which stays the same from release to release
with the real key.

## Building a signed release locally

```bash
YUVOMI_KEYSTORE_PATH=/path/to/yuvomi-release.jks \
YUVOMI_KEYSTORE_PASSWORD=... YUVOMI_KEY_ALIAS=yuvomi YUVOMI_KEY_PASSWORD=... \
./gradlew assembleRelease -Pyuvomi.versionName=0.2.0 -Pyuvomi.versionCode=2000
# app/build/outputs/apk/release/app-release.apk
```

Without the key variables, `assembleRelease` gives `app-release-unsigned.apk`; add
`-Pyuvomi.debugSignRelease=true` to sign it with your local debug key instead.

## Installing

Open the release page on the phone, download the APK, open it, and allow the browser or file
manager to install apps when Android asks. Android 8.0 or newer.

Later versions install from within the app (ADR 0018): a banner and Settings, App updates show a newer
release, downloads it, checks it against the digest GitHub publishes for the asset and opens
Android's installer. This only works between builds signed with the same release key; an
install signed with the debug key has to be uninstalled once first.
