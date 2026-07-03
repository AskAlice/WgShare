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
| `core/ClipboardPushReceiver.kt` | Receives clipboard text pushed by the SwiftKey LSPosed hook |
| `ui/` | Jetpack Compose: History (default), Devices/fallback, Pair, Messages |
| `:xposed` module | LSPosed module: `ClipboardHook` (SwiftKey capture) + `KdeConnectInjector` (KDE Connect inject) |

## Background clipboard sync (LSPosed / SwiftKey)

Android 10+ only lets the **foreground app** or the **default IME** read the clipboard, so the
main app can't silently capture copies. The `:xposed` module works around this: SwiftKey is an IME,
so code injected into its process (via [LSPosed](https://github.com/LSPosed/LSPosed)) can read the
clipboard in the background.

```
SwiftKey process (Xposed hook)                     WgShare process              peers
  OnPrimaryClipChangedListener ──broadcast(text)──► ClipboardPushReceiver ──► Repo.onExternalClipboard ──► CLIPBOARD envelope ──► tailnet
```

- The hook uses two capture paths feeding one de-duplicated push:
  1. **Internal hook** — hooks SwiftKey's own "add clip to history" method
     (`w90.q#a(w90.x, nz.o0)`, text = field `w90.x#a`), verified against **SwiftKey 9.13.10.6**.
     Precise (honours SwiftKey's incognito / `clipboard_is_enabled` gating) but obfuscation-pinned;
     if a SwiftKey update renames these it silently no-ops.
  2. **Public listener** — a `ClipboardManager.OnPrimaryClipChangedListener`, version-proof, always
     registered, so sync keeps working even if path 1 breaks after a SwiftKey update.
- On each captured copy it sends an explicit broadcast
  (`dev.alice.wgshare.action.CLIPBOARD_PUSH`) to WgShare.
- The receiver is `exported` but guarded by the **signature-level** permission
  `dev.alice.wgshare.permission.CLIPBOARD_PUSH`, so only an APK signed with the *same key* as
  WgShare (i.e. this hook) can invoke it. Debug builds share the debug keystore, so this works
  out of the box; for release, sign both APKs with the same key.
- A `lastPush` de-dup in the hook prevents an infinite echo when a synced clip is written back to
  the local clipboard.

## Injecting into KDE Connect (primary sync)

Rather than reimplement device sync, WgShare hands each captured clip to the **local** KDE Connect
app, which emits a real `kdeconnect.clipboard` packet to every paired device. KDE Connect is
open-source and unobfuscated, so the hook is stable across versions.

```
WgShare ──broadcast(KDECONNECT_CLIP, text)──► KdeConnectInjector (inside org.kde.kdeconnect_tp)
                                                └─ KdeConnect.getInstance().getDevices()
                                                     └─ per paired+reachable device:
                                                          ClipboardPlugin.propagateClipboard(text)
```

- We pass the **text directly** (not via the system clipboard) because Android 10+ blocks
  background clipboard reads even for KDE Connect.
- The receiver is registered dynamically and guarded by the same signature-level
  `dev.alice.wgshare.permission.CLIPBOARD_PUSH`, so only WgShare (same signing key) can push.
- Enable the module's **KDE Connect** scope in LSPosed (already in `xposedscope`), alongside SwiftKey.
- `propagateClipboard` is `private` in KDE Connect; the hook invokes it via reflection
  (`XposedHelpers.callMethod`). Verified against the current `org.kde.kdeconnect.Plugins`
  `ClipboardPlugin` API; if KDE Connect renames it, the hook logs and no-ops (SwiftKey→history still works).

### Install (rooted, LSPosed)
1. Build and install both APKs: `./gradlew :app:installDebug :xposed:installDebug`.
2. Root device with LSPosed (Zygisk) installed.
3. In LSPosed → Modules, enable **WgShare Clipboard Hook** and tick **SwiftKey** *and*
   **KDE Connect** in its scope. Install KDE Connect and pair your desktop with it.
4. Force-stop SwiftKey + KDE Connect (or reboot) so the hooks load. Copy text anywhere → it lands in
   WgShare's history and syncs to your devices via KDE Connect.

### Install (rootless, LSPatch)
The release pipeline (below) also produces a **LSPatch-patched SwiftKey** with the hook embedded, so
no root is needed:
1. Install `wgshare-<ver>.apk`.
2. Uninstall stock SwiftKey, then install the split APKs from `swiftkey-lspatched-<ver>.zip`
   together (e.g. with `tiny-apk-installer`, or `adb install-multiple *.apk`).
3. Enable the patched SwiftKey as your keyboard. Copies sync automatically.

Because the patched SwiftKey is signed with the **same key** as WgShare, the sender holds the
`CLIPBOARD_PUSH` signature permission — the whole point of signing everything with one key.

### Re-deriving the internal hook symbols
The obfuscated names in path 1 are specific to a SwiftKey build. To refresh them after an update:

```zsh
apkeep -a com.touchtype.swiftkey -d apk-pure reverse/swiftkey            # downloads a .xapk
bsdtar -xf reverse/swiftkey/com.touchtype.swiftkey.xapk -C reverse/swiftkey/xapk
jadx --no-res --no-debug-info -d reverse/swiftkey/jadx-out reverse/swiftkey/xapk/com.touchtype.swiftkey.apk
rg -l 'clipboard_is_enabled|getPrimaryClip' reverse/swiftkey/jadx-out    # find the clip store class
```

Locate the class whose `a(item, source)` inserts into clip history (the `LocalClipboardItem` toString
gives it away) and update the `CLIP_*` constants in `ClipboardHook.kt`. The `/reverse/` dir is
git-ignored. The public-listener path needs no decompilation, so the module still functions without
this step.

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

One pipeline builds and signs all three artifacts with the **same key**, and patches SwiftKey via
[LSPatch](https://github.com/JingMatrix/LSPatch):

| Artifact | What |
|----------|------|
| `wgshare-<ver>.apk` | the app |
| `wgshare-clipboard-hook-<ver>.apk` | standalone module for rooted LSPosed |
| `swiftkey-lspatched-<ver>.zip` | SwiftKey with the hook embedded, for rootless LSPatch |

Versioning uses git tags: final releases `vX.Y.Z`, release candidates `vX.Y.Z-rc.N`. The
`scripts/build-release.sh` step is the single source of truth shared by CI and local runs; it
builds the app + module, converts the signing key to BKS (LSPatch's keystore format), downloads
`lspatch.jar`, fetches SwiftKey with `apkeep`, then patches + signs.

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
scripts/release-local.sh rc patch --no-patch   # app + module only (skip SwiftKey/LSPatch)
```

`scripts/version.sh resolve <mode> <bump>` prints the resolved `VER_NAME/VER_CODE/TAG` if you just
want the numbers. To run the actual GitHub Actions workflow locally instead, use
[`act`](https://github.com/nektos/act): `act workflow_dispatch -W .github/workflows/release.yml`
(needs Docker + the secrets above passed via `--secret-file`).

## Known limitations / notes

- **Clipboard read** — Android 10+ only lets an app read the clipboard while it
  is foreground, so in-app "Send clipboard" is a foreground button action. For
  silent background capture, install the `:xposed` SwiftKey hook (see above).
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
