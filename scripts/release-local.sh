#!/usr/bin/env bash
# Run the release pipeline locally with the same steps as CI (calls scripts/build-release.sh).
#
#   scripts/release-local.sh [rc|release] [major|minor|patch|none] [--tag] [--publish] [--no-patch]
#
# Defaults: mode=rc, bump=patch. Secrets/config are read from scripts/release.env (gitignored);
# see scripts/release.env.example. Flags:
#   --tag       create + push the git tag for the resolved version
#   --publish   create a GitHub release with the artifacts (needs `gh`; implies --tag)
#   --no-patch  build app + module only (skip SwiftKey/LSPatch)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

[ -f scripts/release.env ] && { set -a; . scripts/release.env; set +a; }

mode=rc; bump=patch; do_tag=0; do_pub=0
for a in "$@"; do case "$a" in
    rc|release) mode=$a ;;
    major|minor|patch|none) bump=$a ;;
    --tag) do_tag=1 ;;
    --publish) do_pub=1; do_tag=1 ;;
    --no-patch) export SKIP_PATCH=1 ;;
    *) echo "unknown arg: $a" >&2; exit 1 ;;
esac; done

eval "$(bash scripts/version.sh resolve "$mode" "$bump")"   # -> VER_NAME VER_CODE TAG PRERELEASE
echo "Resolved: $TAG (versionCode $VER_CODE, prerelease=$PRERELEASE)"

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "tag $TAG already exists" >&2; exit 1
fi

export VER_NAME VER_CODE
bash scripts/build-release.sh

if [ "$do_tag" = 1 ]; then
    echo "Tagging $TAG"
    git tag -a "$TAG" -m "Release $TAG"
    git push origin "$TAG"
fi

if [ "$do_pub" = 1 ]; then
    echo "Publishing GitHub release $TAG"
    pre=(); [ "$PRERELEASE" = true ] && pre=(--prerelease)
    gh release create "$TAG" dist/* --title "$TAG" --generate-notes "${pre[@]}"
fi
