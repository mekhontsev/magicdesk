# Embedded Wayland Runtime

## Status

The `wayland` branch contains an experimental per-toplevel compositor and Android
runtime module. The APK packages the compositor and a separate host renderer.
There is not yet a Wayland tool, application launcher, host Activity, or MCP
workflow. A successful native test or APK build does not establish Android host
integration or support on every Android release.

Termux is the current build environment, not a runtime dependency. The executor
library statically links its non-system dependencies and requires only Android's
`libc.so`, `libm.so`, and `libdl.so`. The host renderer uses Android's public
`ANativeWindow` API and does not link wlroots or load Termux libraries.

The launch integration must retain the X11 execution model: Termux is an optional
client executor; shell clients use the explicitly authorized command identity,
while their compositor runs under MagicDesk's app UID, never an elevated renderer
UID. Those launch paths remain to be implemented and tested. Desktop, HOME and
root must not become prerequisites.

An app-UID compositor's private socket directory is not a shell-client launch
transport. The native `mdw_server_connect` API creates an already connected
client FD usable through `WAYLAND_SOCKET`; ownership of the returned CLOEXEC FD
passes to its caller. `WaylandSession.connect()` requests it asynchronously through
the UID-checked Binder owner; cancelled, expired and stale replies close their FD.
The caller owns a successfully completed descriptor and must close it or transfer
it to `WaylandClientLaunch`. That one-shot owner admits the selected client UID and
a 256-bit nonce, and sends one descriptor over an abstract Unix socket. Its helper
checks the sender UID, accepts exactly one FD, closes unrelated descriptors, and
exports `WAYLAND_SOCKET` before exec. The argument list must be launched through
the selected `CommandExecution`; this adapter neither chooses an executor nor
owns the client process. Successful FD transfer does not establish client exec.

Connection requests and handoff channels have deadlines and explicit cleanup.
Handoff completion callbacks run after cleanup and outside resource locks.
Cancelling a handoff cannot revoke an FD already received by the client;
the command/session owner still owns client cancellation. The application launcher
has not yet connected these adapters to its lifecycle. Do not broaden directory permissions or
raise the compositor UID to bypass this boundary. Socketpair peer credentials
describe its creator and must not be mistaken for the launched client's identity.

## Boundaries

- `hosted-runtime` supplies the shared executor-context adapter and server
  ownership/startup state machine. X11 and Wayland use the same implementation.
- The native compositor API knows no Java classes, packages, Binder authorization,
  Android tasks or placement policy. Its calls and callbacks are serialized on
  the owning event loop; strings and pixel pointers are borrowed during callbacks.
- The Android server adapter checks the host UID, retains one Binder owner and
  stops on owner death. It integrates the Wayland event-loop FD with the Android
  Looper, without a polling timer. Startup announcements still require host-side
  UID, nonce, session and Binder validation before use.
- Hosts borrow outputs. Releasing an output does not close its Wayland client.
  Closing a window sends `xdg_toplevel.close`; client destruction is a separate
  observation. Closing the retained session stops the server.
- Software frames cross Binder as sealed, tightly packed RGBA memfds, not byte
  arrays or Android surfaces. Each output has one unacknowledged frame and the
  host retains at most one latest frame. A skipped update requests a redraw after
  acknowledgement. Stale acknowledgements cannot release a newer frame.
- Native input belongs to one output lease. Focus transfer, output release and
  unmap release held keys/buttons. Protocol-specific input uses evdev codes and
  a Wayland seat, not X11 button/key encodings.

The current native slice covers xdg-toplevel discovery and metadata, software
rendering, configure/ack, frame callbacks, borrowed-output resize, pointer/key
input, focus release and graceful close. Popup nodes are composed with their
parent, but popup policy and multi-window behavior need more coverage. IME/text,
clipboard, drag-and-drop, density/fullscreen policy, launch identity, and GPU
buffer import are not complete. XWayland and full desktop environments are not
part of this slice.

## Build On The Phone

From the repository in Termux, use the existing Android SDK/JDK setup. Native
build tools include Clang, CMake, Ninja, make, pkg-config, Bison, Flex and
wayland-scanner. Meson can be installed in a build-local environment:

```sh
python -m venv build/wayland-tools
build/wayland-tools/bin/pip install meson==1.9.1
./gradlew :wayland-runtime:assembleDebug :wayland-runtime:testDebugUnitTest :wayland-runtime:lintDebug
```

`magicDeskMeson` can explicitly select another Meson executable. The dependency
build downloads pinned archives with SHA-256 verification, uses an isolated
pkg-config prefix and disables external wlroots backends, XWayland and GPU
renderers. `wayland-runtime/native-deps/CMakeLists.txt` owns the source versions.
Only the build-machine scanner is reused. Non-system libraries are built as
position-independent static archives for API 34.

The only wlroots source replacement is the Android shared-memory adapter.
Rendering allocations use memfd. Read/write plus read-only FD pairs use an
immediately unlinked file in the explicitly provided private `XDG_RUNTIME_DIR`:
Android may deny reopening memfds through `/proc/self/fd`. There is no hardcoded
Termux directory or named shared-memory service.

The Gradle build packages dependency licenses with the runtime. libdrm's
per-file notices are preserved with its root source/header files because that
release has no aggregate license file. Packaging rejects an executor ELF with
RPATH/RUNPATH or dependencies outside the Android system allowlist.

Other build hosts currently require `-PmagicDeskWaylandRuntime=/path/to/prefix`,
pointing to the standalone arm64 API-34 runtime and licenses produced by this
build, including `libmagicdesk_wayland_client.so` (a packaged executable). Their
Android NDK builds the host renderer. Cross-building the dependency
stack with the NDK is not implemented or verified; missing runtime artifacts
fail explicitly instead of producing an incomplete APK.

## Verification

```sh
ctest --test-dir build/wayland-deps --output-on-failure
cmake -S wayland-runtime/src/main/cpp -B build/wayland-portable -G Ninja \
  -DCMAKE_BUILD_TYPE=Debug -DMDW_BUILD_JNI=ON \
  -DMDW_DEPENDENCY_PREFIX="$PWD/build/wayland-deps/prefix" \
  -DCMAKE_C_FLAGS=--target=aarch64-linux-android34 \
  -DCMAKE_SHARED_LINKER_FLAGS=-fno-termux-rpath \
  -DCMAKE_EXE_LINKER_FLAGS=-fno-termux-rpath
cmake --build build/wayland-portable
env -u LD_PRELOAD -u LD_LIBRARY_PATH XKB_CONFIG_ROOT="$PREFIX/share/X11/xkb" \
  ctest --test-dir build/wayland-portable --output-on-failure
```

The XKB path above supplies test keyboard data from the selected environment;
the runtime does not encode it. A standalone launch must provide accessible XKB
data explicitly, as with X11. Tests use isolated runtime directories and check
real client pixels, input delivery, output detach/reattach, graceful close,
socket cleanup, FD access rights and immutable frame transport. The client-helper
fixture also tests peer-UID rejection, malformed and multiple-FD replies, and
descriptor isolation across exec. Unit tests cover UID/nonce admission and replay.

The debug APK contains an Android `LocalSocket` handoff fixture. It uses the
production broker and native helper, including UID rejection, deadline expiry,
cleanup before completion callbacks and worker termination. It runs without
installing the APK or acquiring Desktop:

```sh
./gradlew :app:assembleDebug
install -m 444 app/build/outputs/apk/debug/app-debug.apk build/wayland-client-fixture.apk
env -u LD_PRELOAD -u LD_LIBRARY_PATH CLASSPATH="$PWD/build/wayland-client-fixture.apk" \
  timeout 35 /system/bin/app_process /system/bin \
  io.github.mekhontsev.magicdesk.wayland.ClientLaunchTestMain \
  "$PWD/build/wayland-deps/prefix/lib/libmagicdesk_wayland_client.so" \
  "$PWD/build/wayland-portable/wayland-client-test"
```

ART requires a read-only APK for dynamic loading. The fixture passed on API 36
under the Termux UID; it does not prove transfer between distinct Android UIDs.
Shutdown of the listening socket precedes close so a blocked accept is released.
Android Activity,
cross-UID Binder/FD transfer, input under real host focus changes, and actual
operation without Termux installed still require device validation.