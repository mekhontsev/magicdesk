# MagicDesk X11 Runtime

This is MagicDesk's Java and JNI implementation, not an upstream Android module.
It supports API 34+ and links `vendor/magicdesk-x11` as a native engine. It does
not depend on Desktop, HOME, Shizuku, root, upstream Java, Android signature stubs
or the standalone Termux:X11 APK. The selected Termux endpoint runs the server
and applications; MagicDesk's app UID renders their outputs.

## Ownership

`X11Session` serializes commands, output Surface lifetime and connection teardown
on one HandlerThread. Each connection has one Present consumer regardless of
output count. Callbacks are forwarded to the host's Executor; frame callbacks
describe availability/size, not every animation frame. Output closure releases
only that Surface. MagicDesk's application coordinator decides client/server
retention. Reconnect replaces the socket, not the server or applications.

`X11DataExchange` owns Java transfer requests and cancellation. Native X11 owns
selection/XDND negotiation. `X11FileExchange` runs in the executor process,
never under MagicDesk's shell/root service. Android clipboard focus, providers,
URI grants and drag gestures remain in the main app's hosted-content adapters.

`x11_jni.cpp` is the only JVM/native conversion layer. It converts Java strings,
callbacks, descriptors, Android keycodes and Surfaces to the fork's `embedded.h`
contract. Native window references are borrowed at the API boundary; the engine
retains its own reference until release is acknowledged. The native engine knows
no Java class or package. The APK contains one combined `libXlorie.so`; the fork
is compiled from source, not downloaded as a separately versioned binary.

## Server Startup

The main app registers its receiver before launching `X11Server` via app_process,
with the host APK as CLASSPATH. The explicit environment contains:

- `MAGICDESK_X11_EXECUTOR`: selected Termux package, verified against process UID;
- `MAGICDESK_X11_PACKAGE`: exact receiving host package;
- `MAGICDESK_X11_SESSION` and `MAGICDESK_X11_TOKEN`: pending-session identity and nonce;
- `MAGICDESK_X11_LIBRARY`: extracted native library path;
- `MAGICDESK_X11_CONTENT_DIR`: private imported-file directory;
- `MAGICDESK_X11_XSETTINGS`: whether this application session owns XSettings;
- `MAGICDESK_X11_HOST_WM`: enables the native EWMH fullscreen bridge for
  individually hosted applications; disabled for full Linux desktops;
- `TMPDIR` and `XKB_CONFIG_ROOT`: paths in the selected execution environment.

`X11ProcessContext` owns the API-34+ app_process Context setup. It does not load
the executor's Application or MagicDeskApplication. There is no alternate launcher
or fallback to another package/privilege identity.

`X11Server.ACTION` broadcasts carry named `server`, `session`, `token`, `phase`
and `display` extras. Android's attributed broadcast supplies the sender UID.
The host validates that UID, pending nonce and session before retaining the server
with its owner Binder. The server verifies the host UID on every Binder entry.
Only retention starts Xorg. `ddxReady` reports allocation/socket/input readiness;
only then may the host obtain its renderer connection and launch X clients.

Both ends have a 60-second admission deadline. Stop before start exits without
starting Xorg; stop during startup waits for ddxReady and requests normal Xorg
shutdown. Owner death uses the same shutdown path. File-copy cancellation does
not block it. Xauthority and `-nolisten tcp` remain the host launcher's policy.

## Verification

From the MagicDesk repository root:

```sh
./gradlew :app:assembleDebug :x11-runtime:testDebugUnitTest :x11-runtime:lintDebug
./gradlew -p x11-runtime/example :app:assembleDebug :app:lintDebug
sh scripts/verify-native.sh
```

The example is a test-only Android host for two outputs/servers. Start it with
a fresh `token` (at least 24 characters) and `leftSession`/`rightSession` extras;
matching values share a server. Optional `leftWindow`/`rightWindow` select XIDs,
otherwise the root screen. Start corresponding X11Server processes with the same
identities, example host package and selected executor package. The example uses
uncompressed APK libraries; its native library path is
`<installed-base.apk>!/lib/<abi>/libXlorie.so`. Never reuse live
production tokens. Re-deliver SINGLE_TOP with `token`, `session` and
`command=reconnect`, `recreate` or `stop`. Example destruction closes its servers;
production Activity destruction does not own retained sessions.

Verify independent startup, GIMP and whole-desktop output, concurrent sessions,
input/IME, clipboard and file drag, reconnect/output recreation under GPU load,
normal shutdown and owner-death socket cleanup. Build/Lint do not substitute for
Android 14 device coverage, which remains pending.
