#!/usr/bin/env bash
# Regenerate everything this repository takes from upstream Yuvomi.
#
#   upstream/sync.sh            re-sync the pinned version (upstream/VERSION)
#   upstream/sync.sh 2.84.0     move the pin to 2.84.0 and sync
#
# Writes:
#   upstream/VERSION                     the pin
#   upstream/openapi.json                OpenAPI document built from upstream source
#   app/src/main/assets/i18n/*.json      shipped namespaces of every upstream locale
#   app/src/main/assets/tokens.json      colour tokens, light and dark
#   app/src/main/java/.../core/api/FriendlyErrorTable.kt  upstream's shared error sentences
#   $SYNC_REPORT (default: upstream/.sync-report.md, git-ignored)  PR body
#
# Needs git, node >= 22 and python3. Never runs upstream's server, only reads
# its source; the OpenAPI builder uses Node built-ins only.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
old_version="$(tr -d '[:space:]' < "$here/VERSION")"
new_version="${1:-$old_version}"
new_version="${new_version#v}"
report="${SYNC_REPORT:-$here/.sync-report.md}"
repo_url="${UPSTREAM_REPO_URL:-https://github.com/ulsklyc/yuvomi}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "upstream: cloning v$new_version"
GIT_LFS_SKIP_SMUDGE=1 git clone --quiet --depth 1 --branch "v$new_version" "$repo_url" "$work/upstream"

# Keep the previous snapshots for the report.
mkdir -p "$work/old"
[ -f "$here/openapi.json" ] && cp "$here/openapi.json" "$work/old/openapi.json"
[ -f "$root/app/src/main/assets/i18n/en.json" ] && cp "$root/app/src/main/assets/i18n/en.json" "$work/old/en.json"

node "$here/scripts/extract_openapi.mjs" "$work/upstream" "$here/openapi.json" "$new_version"
python3 -I "$here/scripts/extract_locales.py" "$work/upstream" "$here/i18n-namespaces.txt" "$root/app/src/main/assets/i18n"
python3 -I "$here/scripts/extract_tokens.py" "$work/upstream" "$root/app/src/main/assets/tokens.json"
python3 -I "$here/scripts/extract_friendly_errors.py" "$work/upstream" "$root/app/src/main/java/io/github/marcomow/yuvomi/core/api/FriendlyErrorTable.kt"

printf '%s\n' "$new_version" > "$here/VERSION"

# Core and Tasks in operations-used.txt, every other module in operations-used.d/.
cat "$here/operations-used.txt" "$here"/operations-used.d/*.txt > "$work/operations-used.txt"

python3 -I "$here/scripts/report.py" \
  --old-spec "$work/old/openapi.json" --new-spec "$here/openapi.json" \
  --used "$work/operations-used.txt" \
  --changelog "$work/upstream/CHANGELOG.md" \
  --old-version "$old_version" --new-version "$new_version" \
  --old-en "$work/old/en.json" --new-en "$root/app/src/main/assets/i18n/en.json" \
  > "$report"

echo "upstream: synced $old_version -> $new_version, report in $report"
