# WgShare

An Android **clipboard-history** app that captures every copy (even in the
background) and syncs it to your other devices. It doesn't reinvent device sync:
the **primary path feeds the captured clip into the local KDE Connect app**, which
already handles transport, pairing, SMS and notification mirroring. A built-in
**Tailscale peer transport is kept as a fallback** for talking to other WgShare
installs directly.

```
copy (any app)
  └─ SwiftKey IME hook ─► WgShare ─┬─► local KDE Connect ─► all its paired devices   (primary)
                                   └─► Tailscale peers (WgShare↔WgShare)              (fallback)
WgShare UI = searchable clipboard history (tap to re-copy).
```

## How it works

```
Device A  ──Tailscale (WireGuard + DERP, encrypted)──  Device B
   │  100.x.y.z (tailnet)                  │ 100.x.y.z (tailnet)
   └── TCP :8787 over the tailnet ─────────┘  (length-prefixed JSON envelopes)
```

1. **Prerequisite** — the [Tailscale](https://tailscale.com) app runs on every
   device, all logged into the **same tailnet**. Tailscale assigns each node a
   stable IPv4 in the CGNAT range `100.64.0.0/10` and handles NAT traversal.
2. **Identity** — on first launch each device mints a stable random `deviceId`
   and a display name; no key management (Tailscale owns the crypto).
3. **Pairing** — each device shows a QR containing `{name, deviceId, tsIp}`.
   Because Tailscale gives full node-to-node connectivity, **one scan is enough**:
   the scanner adds the peer, then fires a `PAIR` envelope back over the tailnet so
   the other side adds it too.
4. **Transport** — `PeerServer` binds our Tailscale IP on `:8787`; `sendEnvelope`
   dials peers at `tsIp:8787`. Binding to the tailnet IP keeps the listener off the
   plain LAN.
5. **Data** — `Envelope { MESSAGE | CLIPBOARD | PAIR }`. Incoming `CLIPBOARD`
   envelopes are written to the system clipboard; messages land in a local feed.

`net/Tailscale.kt` discovers the local `100.64.0.0/10` address; if Tailscale isn't
connected the app shows "Tailscale offline" and prompts the user to connect.

## Module layout

| Path | Responsibility |
|------|----------------|
| `model/Models.kt` | Identity, Peer, Message, Envelope, PairingPayload |
| `data/Store.kt` | DataStore persistence + stable `deviceId` minting |
| `net/Tailscale.kt` | Discovers the local Tailscale (`100.64.0.0/10`) IP |
| `net/Net.kt` | TCP peer server + client, length‑prefixed JSON framing |
| `pairing/Qr.kt` | QR encode (ZXing) / decode of `PairingPayload` |
| `clipboard/Clipboard.kt` | System clipboard read/write |
| `core/Repo.kt` | Wires identity + Tailscale + server + send/receive logic |
| `core/KdeConnect.kt` | Forwards captured clips to the local KDE Connect app (primary sync) |
| `service/SyncService.kt` | Foreground service keeping the listener alive |
| `core/ClipboardPushReceiver.kt` | Receives clipboard text pushed by the patched SwiftKey |
| `ui/` | Jetpack Compose: History (default), Devices/fallback, Pair, Messages |
| `revanced/` | Standalone ReVanced project: patches (SwiftKey, KDE Connect) + extensions (injected code) |

## Background clipboard sync (ReVanced patches)

Android 10+ only lets the **foreground app** or the **default IME** read the clipboard, so the main
app can't silently capture copies. Instead of runtime Xposed/LSPosed hooks, WgShare ships
**[ReVanced](https://github.com/ReVanced/revanced-patcher) patches** that *statically* rewrite the
SwiftKey and KDE Connect APKs at build time (patcher + extension DEX + `revanced-cli`), then sign
them with our key. No root, no LSPosed.

```
copy → patched SwiftKey (extension listener) ──broadcast(CLIPBOARD_PUSH)──► WgShare.ClipboardPushReceiver
                                                                              └─► history + KdeConnect.push
WgShare ──broadcast(KDECONNECT_CLIP)──► patched KDE Connect (extension receiver)
                                          └─ KdeConnect.getInstance().getDevices()
                                               └─ per paired+reachable device: ClipboardPlugin.propagateClipboard(text)
```

Layout of the standalone `revanced/` project (own Gradle build + settings plugin):

| Path | Role |
|------|------|
| `revanced/patches/…/SwiftKeyClipboardPatch.kt` | injects `ClipboardBroadcaster.install()` into SwiftKey startup |
| `revanced/patches/…/KdeConnectClipboardPatch.kt` | injects `KdeConnectInjector.install()` into KDE Connect startup |
| `revanced/extensions/swiftkey/…/ClipboardBroadcaster.java` | registers a clipboard listener, broadcasts each copy to WgShare |
| `revanced/extensions/kdeconnect/…/KdeConnectInjector.java` | receives WgShare's push, calls `ClipboardPlugin.propagateClipboard` |

- **SwiftKey** (IME) is patched to register a `ClipboardManager.OnPrimaryClipChangedListener` on
  startup and broadcast copies. Version-proof (public API), so no obfuscation-pinned symbols.
- **KDE Connect** (open-source, unobfuscated) is patched so its Application (`org.kde.kdeconnect.KdeConnect`)
  registers a guarded receiver and pushes text into `ClipboardPlugin.propagateClipboard(String)` (public;
  invoked via reflection since the extension has no compile-time dep on KDE Connect); KDE Connect then
  syncs it via its own transport/pairing (and its SMS + notifications). Verified against the F-Droid build.
- The broadcast is guarded by the **signature-level** permission `CLIPBOARD_PUSH`; because
  `revanced-cli` re-signs both apps with the **same key** as WgShare, the senders hold it.

### Building the patch bundle
The `revanced/` build needs **GitHub Packages auth** for the ReVanced gradle plugin + patcher
(`read:packages` token → `githubPackagesUsername`/`githubPackagesPassword`). Then:

```zsh
cd revanced && ./gradlew build      # -> revanced/patches/build/libs/*.rvp
```

### Install (rootless)
The release pipeline (below) patches + signs everything:
1. Install `wgshare-<ver>.apk`.
2. Uninstall stock SwiftKey, install the APKs from `swiftkey-revanced-<ver>.zip` together
   (`adb install-multiple *.apk`). Enable it as your keyboard.
3. Uninstall stock KDE Connect, install `kdeconnect-revanced-<ver>.zip`; pair your desktop.
4. Copy text anywhere → lands in WgShare history and syncs via KDE Connect.

### Refreshing patch anchors after an app update
Patches locate methods by fingerprint. If SwiftKey/KDE Connect change, decompile and adjust the
fingerprints in the patch files (the `/reverse/` dir is git-ignored):

```zsh
apkeep -a com.touchtype.swiftkey -d apk-pure reverse/swiftkey
apkeep -a org.kde.kdeconnect_tp  -d f-droid  reverse/kdeconnect
jadx --no-res -d reverse/swiftkey/jadx reverse/swiftkey/*.apk    # inspect onCreate anchors
```

## Build

Targets **Android 16 (API 36)**, min **Android 15 (API 35)**. Requires Android
Studio (Meerkat / 2024.3.1 Patch 1+, for AGP 8.13 / API 36) or a local Android SDK
with `local.properties` pointing at it (`sdk.dir=/path/to/Android/sdk`).

```zsh
gradle wrapper --gradle-version 8.13   # first time: fetches gradle-wrapper.jar
./gradlew :app:assembleDebug
./gradlew :app:installDebug            # to a connected device
```

At runtime, install and connect **Tailscale** on each device (same tailnet).

Opening the folder in Android Studio also generates the wrapper and syncs deps.

## Release pipeline

One pipeline builds and signs everything with the **same key**, and patches SwiftKey + KDE Connect
with [`revanced-cli`](https://github.com/ReVanced/revanced-cli) using our patch bundle:

| Artifact | What |
|----------|------|
| `wgshare-<ver>.apk` | the app |
| `swiftkey-revanced-<ver>.zip` | SwiftKey patched with the clipboard-capture patch |
| `kdeconnect-revanced-<ver>.zip` | KDE Connect patched with the clipboard-inject patch |

Versioning uses git tags: final releases `vX.Y.Z`, release candidates `vX.Y.Z-rc.N`. The
`scripts/build-release.sh` step is the single source of truth shared by CI and local runs; it
builds the app, builds the ReVanced patch bundle (`revanced/`), downloads `revanced-cli`, fetches
SwiftKey (apk-pure) + KDE Connect (f-droid) with `apkeep`, then patches + signs with our key.

### Signing key
Create one keystore, reused everywhere:

```zsh
keytool -genkeypair -v -keystore release.p12 -storetype PKCS12 \
  -alias wgshare -keyalg RSA -keysize 4096 -validity 10000
```

For CI, add repo secrets: `KEYSTORE_B64` (`base64 -w0 release.p12`), `KEYSTORE_PASS`, `KEY_ALIAS`,
`KEY_PASS`. Locally, copy `scripts/release.env.example` → `scripts/release.env` and fill it in
(both are git-ignored, along with `dist/` and any keystore).

### Run in CI
Actions → **Release** → *Run workflow*, choosing:
- **mode** — `rc` (tagged prerelease) or `release` (final semver)
- **bump** — `patch` / `minor` / `major` / `none`, applied to the latest final release

It resolves the version, builds/patches/signs, pushes the tag, and publishes a GitHub release
(prerelease for `rc`) with all artifacts + `SHA256SUMS`.

### Run locally
Same steps, same script:

```zsh
scripts/release-local.sh rc patch              # build + resolve next rc (dry, no tag)
scripts/release-local.sh rc patch --tag        # also create + push the tag
scripts/release-local.sh release minor --publish  # final release + GitHub release via gh
scripts/release-local.sh rc patch --no-patch   # app only (skip ReVanced patching)
```

`scripts/version.sh resolve <mode> <bump>` prints the resolved `VER_NAME/VER_CODE/TAG` if you just
want the numbers. To run the actual GitHub Actions workflow locally instead, use
[`act`](https://github.com/nektos/act): `act workflow_dispatch -W .github/workflows/release.yml`
(needs Docker + the secrets above passed via `--secret-file`).

## Known limitations / notes

- **Clipboard read** — Android 10+ only lets an app read the clipboard while it
  is foreground, so in-app "Send clipboard" is a foreground button action. For
  silent background capture, install the ReVanced-patched SwiftKey (see above).
- **NAT traversal** — handled by Tailscale (direct WireGuard where possible, DERP
  relay fallback). Devices don't need to be on the same LAN, but both must be on
  the same tailnet and connected.
- **Dependency on Tailscale** — the app doesn't embed `tsnet`/`libtailscale`; it
  relies on the installed Tailscale app owning the `100.64.0.0/10` interface and
  addresses peers by their tailnet IP. If Tailscale is off, WgShare is offline.
- Tailscale already authenticates + encrypts node-to-node; an app-layer token
  could be layered on `Envelope` for defense-in-depth.

## Security

End-to-end encryption is provided by Tailscale / WireGuard (Curve25519 +
ChaCha20‑Poly1305). WgShare stores only a random `deviceId`, peer list and message
history in app-private DataStore; the peer listener binds the tailnet IP so it
isn't exposed on the plain LAN.
