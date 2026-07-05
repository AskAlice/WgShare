#!/usr/bin/env bash
# Builds the signed WgShare app, builds the ReVanced patch bundle (revanced/), then patches SwiftKey
# and KDE Connect with revanced-cli, signing everything with the SAME key so the CLIPBOARD_PUSH
# signature permission is honoured across all APKs. Single source of truth for CI
# (.github/workflows/release.yml) and local runs (scripts/release-local.sh).
#
# Required env:
#   VER_NAME, VER_CODE            version to stamp (see scripts/version.sh)
# Signing (required for a real release; if absent, builds debug-signed and skips patching):
#   KEY
#   KEYSTORE_PASS, KEY_ALIAS, KEY_PASSSTORE                      path to PKCS12/JKS keystore
# Optional:
#   SWIFTKEY_APK | SWIFTKEY_XAPK  prebuilt SwiftKey artifact; else downloaded via apkeep (apk-pure)
#   KDECONNECT_APK                prebuilt KDE Connect apk; else downloaded via apkeep (f-droid)
#   SKIP_SWIFTKEY=1 | SKIP_KDECONNECT=1   skip patching that app
#   PATCHES_RVP                   prebuilt patch bundle; else built from revanced/ (needs GH Packages auth)
#   REVANCED_CLI_JAR              local revanced-cli.jar; else downloaded from GitHub releases
#   REVANCED_CLI_TAG              pin revanced-cli release tag (default: latest)
#   SKIP_PATCH=1                  build the app only
#   OUT                           output dir (default: dist)
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

OUT=${OUT:-dist}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
: "${VER_NAME:?set VER_NAME}"; : "${VER_CODE:?set VER_CODE}"

GRADLE="./gradlew"; [ -x "$GRADLE" ] || GRADLE="gradle"
log() { echo "==> $*"; }
have_keystore() { [ -n "${KEYSTORE:-}" ] && [ -f "${KEYSTORE:-/nonexistent}" ]; }

mkdir -p "$OUT"

# --- 1. Build the WgShare app APK (signing env is read by app/build.gradle.kts) -----------------
log "Building WgShare app ($VER_NAME / $VER_CODE)"
if have_keystore; then export KEYSTORE KEYSTORE_PASS KEY_ALIAS KEY_PASS
else log "WARN: no keystore -> debug-signed build, patching will be skipped"; fi

"$GRADLE" --no-daemon -PverName="$VER_NAME" -PverCode="$VER_CODE" :app:assembleRelease

app_apk=$(ls app/build/outputs/apk/release/*.apk | head -n1)
cp "$app_apk" "$OUT/wgshare-${VER_NAME}.apk"
log "app -> $OUT/wgshare-${VER_NAME}.apk"

if [ "${SKIP_PATCH:-0}" = "1" ] || ! have_keystore; then
    log "Skipping ReVanced patch step"
    ( cd "$OUT" && sha256sum ./*.apk > SHA256SUMS ) || true
    log "Done. Artifacts in $OUT"; exit 0
fi

# --- 2. Build (or locate) the ReVanced patch bundle (.rvp) --------------------------------------
rvp=${PATCHES_RVP:-}
if [ -z "$rvp" ]; then
    log "Building ReVanced patches (revanced/) — needs GitHub Packages auth for the RV gradle plugin"
    ( cd revanced && { [ -x ./gradlew ] && G=./gradlew || G=gradle; "$G" --no-daemon -PverName="$VER_NAME" build; } )
    # Pick the patch bundle, never the -sources bundle.
    rvp=$(ls revanced/patches/build/libs/*.rvp 2>/dev/null | grep -v -- '-sources\.rvp' | head -n1)
fi
[ -n "$rvp" ] && [ -f "$rvp" ] || { echo "no .rvp patch bundle found (set PATCHES_RVP)" >&2; exit 1; }
log "patch bundle: $rvp"

# --- 3. Obtain revanced-cli ---------------------------------------------------------------------
cli=${REVANCED_CLI_JAR:-}
if [ -z "$cli" ]; then
    log "Downloading revanced-cli (${REVANCED_CLI_TAG:-latest})"
    if [ "${REVANCED_CLI_TAG:-}" ]; then
        api="https://api.github.com/repos/ReVanced/revanced-cli/releases/tags/${REVANCED_CLI_TAG}"
    else
        api="https://api.github.com/repos/ReVanced/revanced-cli/releases/latest"
    fi
    # Authenticate the API call when a token is available to avoid anonymous 403 rate limits.
    gh_tok=${GH_TOKEN:-${GITHUB_TOKEN:-${ORG_GRADLE_PROJECT_githubPackagesPassword:-}}}
    curl_auth=(); [ -n "$gh_tok" ] && curl_auth=(-H "Authorization: Bearer $gh_tok")
    url=$(curl -fsSL "${curl_auth[@]}" "$api" | grep -oE 'https://[^"]+revanced-cli[^"]*-all\.jar' | head -n1)
    [ -n "$url" ] || { echo "could not resolve revanced-cli jar url" >&2; exit 1; }
    cli="$WORK/revanced-cli.jar"; curl -fsSL -o "$cli" "$url"
fi

# revanced-cli (>=6) signs with a BouncyCastle BKS keystore, while the Android/Gradle app build
# needs PKCS12/JKS. Derive a BKS holding the SAME key so both sign with the same cert (the
# CLIPBOARD_PUSH signature permission checks the cert, not the keystore format). BC provider ships
# inside the cli jar.
SIGN_KS="$KEYSTORE"
if ! keytool -list -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" -storetype BKS \
        -provider org.bouncycastle.jce.provider.BouncyCastleProvider -providerpath "$cli" \
        >/dev/null 2>&1; then
    log "Converting keystore to BKS for revanced-cli signing"
    SIGN_KS="$WORK/sign.bks"
    keytool -importkeystore -noprompt \
        -srckeystore "$KEYSTORE" -srcstorepass "$KEYSTORE_PASS" -srcalias "$KEY_ALIAS" -srckeypass "$KEY_PASS" \
        -destkeystore "$SIGN_KS" -deststoretype BKS -deststorepass "$KEYSTORE_PASS" \
        -destalias "$KEY_ALIAS" -destkeypass "$KEY_PASS" \
        -provider org.bouncycastle.jce.provider.BouncyCastleProvider -providerpath "$cli"
fi

# --- 4. Helpers ---------------------------------------------------------------------------------
# Prints the .apk paths inside an artifact (single .apk or split .xapk/.apks/.zip).
extract_apks() {
    local artifact="$1" dir
    case "$artifact" in
        *.xapk|*.apks|*.zip)
            dir="$WORK/x_$(basename "$artifact")"; mkdir -p "$dir"
            unzip -o "$artifact" -d "$dir" >/dev/null
            ls "$dir"/*.apk ;;
        *.apk) echo "$artifact" ;;
        *) echo "unknown artifact: $artifact" >&2; return 1 ;;
    esac
}

# Picks the base APK from a split set (revanced-cli patches a single APK).
base_apk() {
    local a; for a in "$@"; do case "$(basename "$a")" in
        base.apk|*[!-]base.apk) echo "$a"; return ;; esac; done
    # fallback: largest apk (base is usually biggest)
    ls -S "$@" | head -n1
}

# patch_app <label> <out-name> <apk...> : patches the base APK with our key, bundles splits.
patch_app() {
    local label="$1" out="$2"; shift 2
    local base; base=$(base_apk "$@")
    log "revanced-cli patch $label: base=$(basename "$base")"
    local patched="$WORK/${label}-patched.apk"
    # -b bypasses RVP signature/provenance verification (our bundle is built + used locally/CI,
    # not fetched from the signed ReVanced repo).
    java -jar "$cli" patch -p "$rvp" -b -o "$patched" \
        --keystore "$SIGN_KS" --keystore-password "$KEYSTORE_PASS" \
        --keystore-entry-alias "$KEY_ALIAS" --keystore-entry-password "$KEY_PASS" \
        "$base"
    local dir="$OUT/$out"; rm -rf "$dir"; mkdir -p "$dir"
    cp "$patched" "$dir/base.apk"
    # carry untouched config splits (language/density/abi) for install alongside the patched base
    local a; for a in "$@"; do [ "$a" = "$base" ] || cp "$a" "$dir/"; done
    ( cd "$OUT" && zip -qr "${out}.zip" "$out" )
    log "patched $label -> $OUT/${out}.zip (install the APKs together)"
}

# --- 5. SwiftKey (background clipboard capture) --------------------------------------------------
if [ "${SKIP_SWIFTKEY:-0}" != "1" ]; then
    if [ -n "${SWIFTKEY_APK:-}" ]; then sk_artifact="$SWIFTKEY_APK"
    else
        sk_artifact=${SWIFTKEY_XAPK:-}
        if [ -z "$sk_artifact" ]; then
            log "Downloading SwiftKey via apkeep (apk-pure)"
            apkeep -a com.touchtype.swiftkey -d apk-pure "$WORK"
            sk_artifact=$(ls "$WORK"/com.touchtype.swiftkey.* | head -n1)
        fi
    fi
    mapfile -t SK_APKS < <(extract_apks "$sk_artifact")
    patch_app swiftkey "swiftkey-revanced-${VER_NAME}" "${SK_APKS[@]}"
fi

# --- 6. KDE Connect (clipboard injection -> its own device sync) --------------------------------
if [ "${SKIP_KDECONNECT:-0}" != "1" ]; then
    if [ -n "${KDECONNECT_APK:-}" ]; then kc_artifact="$KDECONNECT_APK"
    else
        log "Downloading KDE Connect via apkeep (f-droid)"
        apkeep -a org.kde.kdeconnect_tp -d f-droid "$WORK"
        kc_artifact=$(ls "$WORK"/org.kde.kdeconnect_tp.* | head -n1)
    fi
    mapfile -t KC_APKS < <(extract_apks "$kc_artifact")
    patch_app kdeconnect "kdeconnect-revanced-${VER_NAME}" "${KC_APKS[@]}"
fi

# Fail loudly if a patched bundle we expected is missing, so CI never ships/attaches without it.
[ "${SKIP_SWIFTKEY:-0}" = "1" ] || [ -f "$OUT/swiftkey-revanced-${VER_NAME}.zip" ] \
    || { echo "expected $OUT/swiftkey-revanced-${VER_NAME}.zip missing" >&2; exit 1; }
[ "${SKIP_KDECONNECT:-0}" = "1" ] || [ -f "$OUT/kdeconnect-revanced-${VER_NAME}.zip" ] \
    || { echo "expected $OUT/kdeconnect-revanced-${VER_NAME}.zip missing" >&2; exit 1; }

( cd "$OUT" && sha256sum ./*.apk ./*.zip 2>/dev/null > SHA256SUMS )
log "Done. Artifacts in $OUT ($(ls "$OUT"/*.zip 2>/dev/null | wc -l) patched bundle(s))"
