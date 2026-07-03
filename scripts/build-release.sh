#!/usr/bin/env bash
# Builds signed WgShare + clipboard-hook APKs, then patches SwiftKey with the hook via LSPatch,
# signing everything with the SAME key so the CLIPBOARD_PUSH signature permission is honoured.
# Single source of truth for both CI (.github/workflows/release.yml) and local runs
# (scripts/release-local.sh).
#
# Required env:
#   VER_NAME, VER_CODE            version to stamp (see scripts/version.sh)
# Signing (required for a real release; if absent, builds debug-signed and skips LSPatch):
#   KEYSTORE                      path to PKCS12/JKS keystore
#   KEYSTORE_PASS, KEY_ALIAS, KEY_PASS
# Optional:
#   SWIFTKEY_APK | SWIFTKEY_XAPK  prebuilt SwiftKey artifact; else downloaded via apkeep (apk-pure)
#   KDECONNECT_APK                prebuilt KDE Connect apk; else downloaded via apkeep (f-droid)
#   SKIP_KDECONNECT=1             don't patch KDE Connect (SwiftKey only)
#   LSPATCH_JAR                   local lspatch.jar (else downloaded from GitHub releases)
#   LSPATCH_TAG                   pin LSPatch release tag (default: latest)
#   BCPROV_VERSION                Bouncy Castle version for BKS conversion (default 1.78.1)
#   SKIP_PATCH=1                  build app + module only
#   OUT                           output dir (default: dist)
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

OUT=${OUT:-dist}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
BCPROV_VERSION=${BCPROV_VERSION:-1.78.1}
: "${VER_NAME:?set VER_NAME}"; : "${VER_CODE:?set VER_CODE}"

GRADLE="./gradlew"; [ -x "$GRADLE" ] || GRADLE="gradle"
log() { echo "==> $*"; }
have_keystore() { [ -n "${KEYSTORE:-}" ] && [ -f "${KEYSTORE:-/nonexistent}" ]; }

mkdir -p "$OUT"

# --- 1. Build the two app APKs (signing env is read by build.gradle.kts) ------------------------
log "Building WgShare + clipboard hook ($VER_NAME / $VER_CODE)"
if have_keystore; then export KEYSTORE KEYSTORE_PASS KEY_ALIAS KEY_PASS
else log "WARN: no keystore -> debug-signed build, LSPatch will be skipped"; fi

"$GRADLE" --no-daemon -PverName="$VER_NAME" -PverCode="$VER_CODE" \
    :app:assembleRelease :xposed:assembleRelease

app_apk=$(ls app/build/outputs/apk/release/*.apk | head -n1)
mod_apk=$(ls xposed/build/outputs/apk/release/*.apk | head -n1)
cp "$app_apk" "$OUT/wgshare-${VER_NAME}.apk"
cp "$mod_apk" "$OUT/wgshare-clipboard-hook-${VER_NAME}.apk"
log "app  -> $OUT/wgshare-${VER_NAME}.apk"
log "hook -> $OUT/wgshare-clipboard-hook-${VER_NAME}.apk"

if [ "${SKIP_PATCH:-0}" = "1" ] || ! have_keystore; then
    log "Skipping LSPatch step"
    ( cd "$OUT" && sha256sum ./*.apk > SHA256SUMS ) || true
    log "Done. Artifacts in $OUT"
    exit 0
fi

# --- 2. Convert signing key to BKS (LSPatch custom keystore format) -----------------------------
log "Converting keystore -> BKS"
bcprov="$WORK/bcprov.jar"
curl -fsSL -o "$bcprov" \
    "https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/${BCPROV_VERSION}/bcprov-jdk18on-${BCPROV_VERSION}.jar"
bks="$WORK/release.bks"
keytool -importkeystore -noprompt \
    -srckeystore "$KEYSTORE" -srcstorepass "$KEYSTORE_PASS" -srcalias "$KEY_ALIAS" -srckeypass "$KEY_PASS" \
    -destkeystore "$bks" -deststoretype BKS -deststorepass "$KEYSTORE_PASS" -destalias "$KEY_ALIAS" -destkeypass "$KEY_PASS" \
    -provider org.bouncycastle.jce.provider.BouncyCastleProvider -providerpath "$bcprov"

# --- 3. Obtain lspatch.jar ----------------------------------------------------------------------
jar=${LSPATCH_JAR:-}
if [ -z "$jar" ]; then
    log "Downloading lspatch.jar (${LSPATCH_TAG:-latest})"
    api="https://api.github.com/repos/JingMatrix/LSPatch/releases/${LSPATCH_TAG:+tags/$LSPATCH_TAG}"
    api=${api%/}; [ "${LSPATCH_TAG:-}" ] || api="https://api.github.com/repos/JingMatrix/LSPatch/releases/latest"
    url=$(curl -fsSL "$api" | grep -oE 'https://[^"]+lspatch[^"]*\.jar' | head -n1)
    [ -n "$url" ] || { echo "could not resolve lspatch.jar url" >&2; exit 1; }
    jar="$WORK/lspatch.jar"; curl -fsSL -o "$jar" "$url"
fi

# --- 4. Helpers: extract split APKs from a bundle, and LSPatch+sign an app ----------------------
# Prints the .apk paths contained in an artifact (single .apk, or split .xapk/.apks/.zip).
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

# patch_app <label> <out-dir-name> <apk...> : embeds the hook module, signs with our key, zips.
patch_app() {
    local label="$1" out="$2"; shift 2
    log "LSPatch $label: ${*##*/}"
    local pdir="$WORK/patched-$label"; mkdir -p "$pdir"
    java -jar "$jar" "$@" \
        -m "$OUT/wgshare-clipboard-hook-${VER_NAME}.apk" \
        -k "$bks" "$KEYSTORE_PASS" "$KEY_ALIAS" "$KEY_PASS" \
        -o "$pdir" -l 2 -f -v
    local dir="$OUT/$out"; rm -rf "$dir"; mkdir -p "$dir"
    cp "$pdir"/*.apk "$dir"/
    ( cd "$OUT" && zip -qr "${out}.zip" "$out" )
    log "patched $label -> $OUT/${out}.zip (install the split APKs together)"
}

# --- 5. Patch + sign SwiftKey (background clipboard capture) ------------------------------------
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
patch_app swiftkey "swiftkey-lspatched-${VER_NAME}" "${SK_APKS[@]}"

# --- 6. Patch + sign KDE Connect (clipboard injection -> its own device sync) -------------------
# KDE Connect is on F-Droid (open, no login); re-signing with our key also lets it hold the
# CLIPBOARD_PUSH signature permission. Override with KDECONNECT_APK, or SKIP_KDECONNECT=1.
if [ "${SKIP_KDECONNECT:-0}" != "1" ]; then
    if [ -n "${KDECONNECT_APK:-}" ]; then kc_artifact="$KDECONNECT_APK"
    else
        log "Downloading KDE Connect via apkeep (f-droid)"
        apkeep -a org.kde.kdeconnect_tp -d f-droid "$WORK"
        kc_artifact=$(ls "$WORK"/org.kde.kdeconnect_tp.* | head -n1)
    fi
    mapfile -t KC_APKS < <(extract_apks "$kc_artifact")
    patch_app kdeconnect "kdeconnect-lspatched-${VER_NAME}" "${KC_APKS[@]}"
else
    log "SKIP_KDECONNECT=1 -> not patching KDE Connect"
fi

( cd "$OUT" && sha256sum ./*.apk ./*.zip > SHA256SUMS )
log "Done. Artifacts in $OUT"
