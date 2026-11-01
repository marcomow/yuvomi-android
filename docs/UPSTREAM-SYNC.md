# Staying in sync with upstream

How this app keeps up with [ulsklyc/yuvomi](https://github.com/ulsklyc/yuvomi) without breaking.
The decision is [ADR 0008](adr/0008-upstream-sync.md); this page is the manual.

## What upstream promises

From upstream's `MODULES.md` and `docs/SCOPE.md`:

- `/api/v1` is the contract. Breaking changes to it are named in the CHANGELOG.
- An operation that will change or go away is first named as **deprecated** in the CHANGELOG and
  keeps working unchanged for **at least 90 days** after that release.
- If an `/api/v2` ever ships, `/api/v1` stays served for 12 months after it.
- Undocumented response fields, the database and private server helpers are **not** part of the
  contract and may change in any release.

So the app reads only what the OpenAPI document or upstream's SPEC describes, and every spec in
`docs/spec/` names the fields it relies on.

## The pieces

| Piece | Where | What it does |
|---|---|---|
| Pin | `upstream/VERSION` | The upstream version the app is built and tested against. |
| Used operations | `upstream/operations-used.txt` | Every `METHOD /api/v1/path` the app calls. |
| Shipped string namespaces | `upstream/i18n-namespaces.txt` | Which top-level keys of the locale files ship with the app. |
| Sync script | `upstream/sync.sh [version]` | Clones upstream at `v<version>`, regenerates every snapshot below, moves the pin, writes a report. |
| OpenAPI snapshot | `upstream/openapi.json` | Built from upstream source with upstream's own `buildOpenApiSpec()`. Diffed in review, never used for code generation. |
| Strings | `app/src/main/assets/i18n/<locale>.json` | Upstream's 27 locales, shipped namespaces only. |
| Colours | `app/src/main/assets/tokens.json` | Public colour tokens from upstream's `tokens.css`, resolved, light and dark. |
| Weekly job | `.github/workflows/upstream-sync.yml` | Mondays 05:17 UTC: if upstream has a newer release, runs the sync and opens a draft PR. |
| Contract tests | `app/src/test/java/.../contract/UpstreamContractTest.kt`, job `contract` in `.github/workflows/android.yml` | The API client against a real upstream server in Docker. Skipped unless `YUVOMI_CONTRACT_URL` is set. |
| Runtime gate | `core/session/VersionGate.kt`, `UPSTREAM_MIN` in `app/build.gradle.kts` | Compares the server's version with the supported range. |
| Parity tracker | [`PARITY.md`](PARITY.md) | Which upstream modules are native, read-only, or in the browser. |

Requirements to run the sync locally: `git`, Node 22 or newer, Python 3. The sync never starts
upstream's server and never runs `npm install`; the OpenAPI builder uses Node built-ins only.

## The weekly sync PR

The job opens a draft PR named `Upstream sync: <old> → <new>`, labelled `upstream-sync`, plus
`breaking` when an operation the app uses disappeared or was marked deprecated. Its body is the
report from `upstream/scripts/report.py`:

1. **Operations this app uses:** missing, deprecated, or changed (which parts: parameters,
   request body, responses, description).
2. **Whole API:** counts of added, removed and changed operations, and the removed ones by name.
3. **Strings:** English keys added and removed in the shipped namespaces.
4. **CHANGELOG:** entries in the version range that mention the API, deprecations or removals,
   then every release heading in the range.

### Reviewing it

1. Read the report top to bottom. "All N operations are present and unchanged" plus no removed
   keys is the common case.
2. For each **changed** operation, read its diff in `upstream/openapi.json` and the matching
   CHANGELOG entry. Update the spec in `docs/spec/` and the model if a field the app reads moved.
3. For a **deprecated** operation, open an issue with the 90-day deadline from the release date.
4. For a **missing** operation, the feature using it is broken against the new version: fix
   before merging, or leave the pin where it is.
5. CI must be green, which includes the i18n key test and the contract tests against the new
   version's Docker image.
6. Update the "last checked" column of `PARITY.md` for the modules you looked at.
7. Merge. The merged pin is now the version the app claims to support.

Repository setting needed once: *Settings → Actions → General → Allow GitHub Actions to create
and approve pull requests*.

### Moving the pin by hand

```bash
./upstream/sync.sh 2.84.0     # or no argument to re-sync the current pin
git diff --stat
cat upstream/.sync-report.md
```

## Contract tests

The specs list the contract tests each operation needs. They run in CI against the real
upstream image at the pinned version:

```bash
docker run -d --name yuvomi -p 3000:3000 \
  -e SESSION_SECRET="$(openssl rand -hex 32)" -e DB_ENCRYPTION_KEY= \
  ghcr.io/ulsklyc/yuvomi:<pinned>
docker exec yuvomi node scripts/seed-demo.js --locale en
```

The seed is upstream's own: five users, all with the password `demo1234` (`alex` and `linda`
admins, `emma` and `leo` children with upstream's child start permissions, `maria` in the `other`
family role), plus tasks with categories, priorities, statuses, dates, several assignees, tags and
points. The tests sign in as those users through the app's real API client, so they cover the
cookie and CSRF handling as well as the response shapes.

The image does not ship `scripts/`, so the CI job copies upstream's `scripts/` folder from the
same tag into the container, seeds, and restarts it. To run the contract tests locally against
such a server:

```bash
YUVOMI_CONTRACT_URL=http://localhost:3000 ./gradlew testDebugUnitTest --tests 'io.github.marcomow.yuvomi.contract.*'
```

## Runtime version gate

| Server version | What the app does |
|---|---|
| Below the minimum supported version | Blocks after sign-in: "This server runs Yuvomi X. This app needs Y or newer." with "Open in browser". |
| From the minimum up to the pin | Normal. |
| Newer than the pin | Normal, plus a one-time quiet notice per server version: "This server is newer than the version this app was tested with." |

The **minimum supported version** starts at the pin (2.83.0) and only moves forward when the app
starts relying on something a newer upstream added. It is `upstreamMin` in `app/build.gradle.kts`;
the pin is read from `upstream/VERSION`. Both reach the app as `BuildConfig` fields.

Independently of the version, a `404` or `405` on an operation from `operations-used.txt` marks
that feature as unavailable for this session instead of retrying, as upstream's `MODULES.md`
advises third-party clients.

## Not automated yet

- **Module icons.** Upstream's module glyphs live in `public/nav-icons.js` as shape descriptions
  (`rect`, `circle`, `path` on a 24-unit grid). Converting them to Android vector drawables is a
  sync step to add with the app scaffold; until then the scaffold uses Material Symbols.
- **SPEC headings.** New headings in upstream's `docs/SPEC.md` are not flagged yet; the
  CHANGELOG section of the report covers them in practice.
