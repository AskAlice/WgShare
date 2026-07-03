#!/usr/bin/env bash
# Semver helper driven by git tags. Final releases are tagged `vX.Y.Z`; release candidates
# `vX.Y.Z-rc.N`. This is the single source of truth for versions in both CI and local builds.
set -euo pipefail

die() { echo "version.sh: $*" >&2; exit 1; }

# Latest final (non-rc) release tag, or 0.0.0 if none exist yet.
current() {
    git tag --list 'v[0-9]*.[0-9]*.[0-9]*' \
        | grep -Ev '\-rc\.' \
        | sed 's/^v//' \
        | sort -V \
        | tail -n1 \
        | grep . || echo "0.0.0"
}

# bump <semver> <major|minor|patch|none>
bump() {
    local ver=$1 part=${2:-patch} major minor patch
    IFS='.' read -r major minor patch <<<"$ver"
    case "$part" in
        major) major=$((major + 1)); minor=0; patch=0 ;;
        minor) minor=$((minor + 1)); patch=0 ;;
        patch) patch=$((patch + 1)) ;;
        none)  ;;
        *) die "unknown bump part: $part" ;;
    esac
    echo "$major.$minor.$patch"
}

# code <semver> -> monotonic integer versionCode (rc suffix ignored).
code() {
    local ver=${1%%-*} major minor patch
    IFS='.' read -r major minor patch <<<"$ver"
    echo $((major * 10000 + minor * 100 + patch))
}

# next-rc <target-semver> -> next rc number for that target (1-based).
next_rc() {
    local target=$1 n
    n=$(git tag --list "v${target}-rc.*" \
        | sed -E "s/^v${target}-rc\.//" \
        | sort -n | tail -n1 | grep . || echo 0)
    echo $((n + 1))
}

# resolve <mode:rc|release> <bump:major|minor|patch|none>
# Emits shell-eval-able KEY=VALUE lines consumed by build/release tooling.
resolve() {
    local mode=${1:-rc} part=${2:-patch} base target ver tag prerelease
    base=$(current)
    target=$(bump "$base" "$part")
    case "$mode" in
        release) ver=$target;                    tag="v${ver}";           prerelease=false ;;
        rc)      ver="${target}-rc.$(next_rc "$target")"; tag="v${ver}";   prerelease=true  ;;
        *) die "unknown mode: $mode (want rc|release)" ;;
    esac
    echo "VER_NAME=${ver}"
    echo "VER_CODE=$(code "$ver")"
    echo "TAG=${tag}"
    echo "PRERELEASE=${prerelease}"
}

cmd=${1:-resolve}; shift || true
case "$cmd" in
    current) current ;;
    bump)    bump "$@" ;;
    code)    code "$@" ;;
    next-rc) next_rc "$@" ;;
    resolve) resolve "$@" ;;
    *) die "usage: version.sh {current|bump <ver> <part>|code <ver>|next-rc <target>|resolve <mode> <bump>}" ;;
esac
