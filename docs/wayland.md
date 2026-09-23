# Embedded Wayland Runtime

## Status

The `wayland-runtime` module contains an experimental per-toplevel compositor and Android
runtime module. The APK packages the compositor and a separate host renderer.
**Linux graphics** manages X11 and Wayland sessions through the same controls;
Wayland opens each toplevel in an ordinary Android host. The shared
`graphics.list/start/execute/stop/open_window` automation commands also generate
the built-in CLI interface. Start and `.desktop` recipes select X11 or Wayland
through the shared graphical launch model; ordinary installed Termux entries
default to X11. A successful native test or APK build does
not establish application compatibility or support on every Android release.

Termux is an optional build environment, not a runtime dependency. The executor
library statically links its non-system dependencies and requires only Android's
`libc.so`, `libm.so`, and `libdl.so`. The host renderer uses Android's public
`ANativeWindow` API and does not link wlroots or load Termux libraries.

The launch integration shares the X11 execution model: Termux is an optional
client executor; shell clients use the explicitly authorized command identity,
while their compositor runs under MagicDesk's app UID, never an elevated renderer
UID. Termux clients use the compositor's private named socket, allowing each
program and its children to establish independent connections. UID-2000 shell
clients instead receive one connection FD. That FD belongs to one Wayland
connection, not a shareable endpoint for unrelated clients. Root client
bootstrapping is not available yet; it fails explicitly without changing identity.
Desktop, HOME and root are not prerequisites for the Termux path.

An app-UID compositor's private socket directory is not a shell-client launch
transport. The native `mdw_server_connect` API creates an already connected
client FD usable through `WAYLAND_SOCKET`; ownership of the returned CLOEXEC FD
passes to its caller. `WaylandSession.connect()` requests it asynchronously through
the UID-checked Binder owner; cancelled, expired and stale replies close their FD.
The caller owns a successfully completed descriptor and must close it or transfer
it to `WaylandClientLaunch`. That one-shot owner admits the selected client UID and
a 256-bit nonce from an identity-sharing Android broadcast. The connection and
receipt cross Binder; no cross-UID Unix-socket connection is required.
`WaylandClientMain` runs under the explicitly selected executor package identity,
checks the descriptor sender UID, then replaces itself with the native helper.
The helper closes unrelated descriptors and exports `WAYLAND_SOCKET` before
exec. The argument list must be launched through the selected `CommandExecution`
with the installed APK as `CLASSPATH`; the transfer adapter neither chooses an
executor nor owns the client process. A receipt confirms descriptor delivery,
not successful client exec. Root client bootstrapping still needs its own
integration through the existing privileged command owner, not package impersonation.

Connection requests and handoff channels have deadlines and explicit cleanup.
Handoff completion callbacks run after cleanup and outside resource locks.
Cancelling a handoff cannot revoke an FD already received by the client;
the command/session owner still owns client cancellation. `WaylandSessions`
owns admission, pending transfers and runtime exit independently of its hosts.
Do not broaden directory permissions or
raise the compositor UID to bypass this boundary. Socketpair peer credentials
describe its creator and must not be mistaken for the launched client's identity.

## Boundaries

- `hosted-runtime` supplies the shared executor-context adapter and server
  ownership/startup state machine. X11 and Wayland use the same implementation.
- `HostedServerProcess` owns local app-UID process diagnostics and private-directory
  cleanup. `CommandExecution` retains the selected client executor. A stopped
  compositor disconnects GUI clients, but does not kill arbitrary background
  jobs in that executor.
- `GraphicalSessionsActivity` selects and controls sessions without borrowing
  their outputs. Selecting an existing session does not open its windows.
  `WaylandActivity` uses `HostedSurfaceView`, pointer gestures and the same
  `HostedWindowPresentation` placement/recovery owner as X11. Normal close sends
  `xdg_toplevel.close` and keeps the host for confirmation; force close disconnects
  that client connection, not other clients or the compositor.
- `GraphicalApplicationLaunch` shares recipe reuse, profile-scoped application
  identity, Recent and Android placement between X11 and Wayland. The pending
  application host claims the first mapped toplevel; additional toplevels use
  `HostedWindowPresentation`. A recipe launch owns a dedicated session, which
  ends when its last window is destroyed. Sessions explicitly created in the
  manager remain retained until stopped. Deleting a shortcut forgets its launch
  history without closing live clients.
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
  visible host retains at most one latest frame. Credit is checked before
  rendering; a deferred update requests a redraw after acknowledgement. Stale
  acknowledgements cannot release a newer frame. An absent Android Surface
  disables its output and releases focus instead of continuing hidden animation.
- Each Android output owns a `WaylandFramePresenter` worker and its buffer queue.
  Session control, input, connection deadlines and other outputs never wait for
  its `ANativeWindow` writes. Output closure releases the presenter independently
  from client/session closure. Window metadata crosses Binder only when changed;
  pixel-only commits do not rebuild the window catalog.
- Native pointer and keyboard input have separate output owners. A keyboard-inert
  shell surface can accept pointer input without taking the application's keyboard.
  Focus transfer, output release, keyboard-policy revocation and unmap release the
  affected owner's held keys/buttons. Protocol-specific input uses evdev codes and
  a Wayland seat, not X11 button/key encodings.
  Pointer hit-testing uses the rendered scene and output dimensions, including
  when a client has not acknowledged a resize or retains a larger minimum size.

The software engine callback, immutable-frame transport and Android presenter
are separate boundaries. Optional GPU buffer import and presentation can replace
the frame path without changing launch, input or Android placement policy. The
current renderer does not advertise DMA-BUF support. Software remains the
compatibility path; GPU drivers are not a startup requirement.

The current native slice covers xdg-toplevel discovery and metadata, software
rendering, configure/ack, frame callbacks, borrowed-output resize, pointer/key
input, focus release and graceful close. The standard data-device manager and
seat selection serve guest-to-guest clipboard requests; they do not publish or
read Android clipboard content. Popup nodes are composed with their
parent; shell popup constraints and surface-family input geometry are covered by
native fixtures. Android popup hosting and multi-window behavior need more coverage. IME/text,
clipboard, drag-and-drop, density/fullscreen policy, and GPU
buffer import are not complete. Android text input is explicitly unavailable
instead of accepting and discarding IME text. Physical keys use the shared
Android-to-evdev mapping. XWayland and full desktop environments are not part
of this slice.

## Native Shell Surfaces

The native API admits layer-shell only after its owner provides a shell event
consumer and explicitly creates a logical shell output. This output is distinct
from the borrowed render outputs. Layer surfaces use a separate catalog and
committed metadata callback, not `xdg_toplevel` application events. wlroots owns
protocol validation and configure acknowledgements; the host owns placement and
calls `mdw_shell_surface_configure`. Equal size configurations are suppressed;
remapping starts a fresh configure handshake.

Revoking the shell output closes its layer surfaces and borrowed outputs without
closing ordinary applications. The compositor bounds admitted layer surfaces to
32. Shell admission does not start Desktop or acquire HOME. `WaylandSession`
exposes a separate revocable shell binding, with immutable `WaylandShellSurface`
metadata across JNI/Binder. The main-thread `WaylandShellBinding` translates this
catalog into a caller-owned layout scope, including density and configure replies.
Replies are qualified by owner and committed revision; late events cannot revive
a released binding. Binding errors release only that integration, while compositor
failure releases the session. Application output ownership remains separate.
User-facing workspace selection and Android shell panel hosts are not implemented;
ordinary sessions do not enable layer-shell admission automatically.

`MdwView` owns a rendered surface family independently of its xdg or layer role.
Ordinary application outputs use wlroots scene rendering. Transparent shell
families use the public render-pass API with cached client textures and the output
swapchain, preserving premultiplied alpha rather than an opaque background. The
scene retains subsurface placement, damage events and hit-testing. There are no
additional per-buffer texture imports or pixel copies in this compositor pass;
the existing software frame export remains unchanged. Borrowed view outputs use
unit scale and no output transform; client buffer transforms and viewports are
applied during composition. Damage retirement prevents unchanged shell surfaces
from continuously submitting frames. Host protocol events are flushed before a
blocking dispatch, so idle scenes do not delay close, configure or input events.

`WaylandViewGeometry` publishes immutable paint bounds and exact input rectangles
for a rendered family, including popups and subsurfaces. Coordinates are relative
to the family's scene origin and may be negative. Geometry has its own revision
and callback across JNI/Binder: popup changes neither rebuild application catalogs
nor change panel reservations. Shell geometry is qualified by the binding owner;
destruction and scope revocation remove it. Native scene watches are attached once
per scene surface. Commit-time comparisons coalesce geometry changes into event-loop
idle work after wlroots listeners; pixel-only updates do not allocate notification
work or publish geometry. There is no geometry timer or pointer-motion query.

Input publication is bounded to 512 rectangles. An over-complex region is explicitly
incomplete with no published input rectangles, not replaced by its bounding box.
Hosts must not capture input using incomplete geometry. Layer popups are constrained
against the logical shell output in family coordinates, independently of the panel's
reserved strip. The native output viewport can include negative paint extents without
resizing the client; pointer hit-testing uses that same viewport origin.

Android shell hosts still need to materialize this geometry, synchronize viewport
changes with presented frames, and apply exact Android touchable regions before
external panels can be exposed. Ordinary application hosts retain their existing
viewport policy.

## Implementation Plan

The target includes both individual applications in Android windows and a whole
Linux desktop in one Android host. The steps below are planned work, not current
capabilities. Whole-desktop feasibility is the next step, independently of GPU support.

1. **Whole-desktop feasibility.** Run a suitable nested Linux compositor with
   software rendering in one Android host. The guest compositor owns its internal
   windows. Verify pointer/key input, resize and viewer reopening without stopping
   the guest session. Record requirements for the tested compositor separately
   from support for other desktop environments; use the result to scope the
   whole-desktop implementation through the shared session and host owners.
2. **Everyday interaction.** Implement Android IME, bidirectional clipboard and
   one visible cursor through the shared host adapters and Wayland protocol
   backends. Complete density and fullscreen integration through existing
   presentation policy. Verify text composition, selection, focus changes,
   popups, close confirmations and resizing across GTK and Qt clients, with X11
   regression coverage. Validate managed recipe placement on a Desktop separately
   from independent Android hosts.
3. **Drag-and-drop.** Connect Wayland data offers and drag lifecycle to the shared
   content exchange. Verify text and file transfers in both directions, cancellation,
   URI-grant lifetime and guest filesystem translation for prepared proot/chroot
   environments. Keep container preparation outside the graphical runtime.
4. **Optional GPU path.** Add buffer import, synchronization and presentation behind
   the existing frame boundaries. Verify ownership, teardown and software fallback
   on supported devices before advertising hardware-buffer capabilities. GPU
   support must not change launch identity or become a startup requirement.

Root/chroot client bootstrap is a separate prerequisite for testing those
execution environments, not for the initial Termux application workflow. Extend
the existing command executor and authenticated connection boundary; keep the
compositor unprivileged and validate connections from independently launched
clients and child processes. Do not treat one inherited client FD as a reusable
endpoint for an entire desktop.

Integrated Linux shell components use the separate work plan in the shared
[shell layout model](shell-layout.md#integration-work). A session must explicitly
bind to a MagicDesk workspace to contribute its panels and reservations. A nested
Linux desktop retains its own scope and cannot reserve space on its containing
Android Desktop. Native wlroots owns protocol validation, configure/ack, scene
nodes and seat state; the geometry model does not replace Android task planes.

Each step needs focused protocol tests and real Android-host workflows through
the same UI/MCP service owners. As work lands, remove completed items from this
plan and describe the resulting behavior in the relevant sections. Keep Status
and Verification aligned with current capabilities and actual device coverage.
This document describes the current design and remaining work, not a development
chronicle; implementation history belongs in Git.

## Session Controls

Installed Termux applications are discovered by Start. A Wayland recipe selects
its protocol explicitly:

```ini
[Desktop Entry]
Type=Application
Name=GTK Demo (Wayland)
Exec=env GDK_BACKEND=wayland gtk3-demo
Icon=gtk3-demo
Terminal=false
X-MagicDesk-Graphics=wayland
```

Place the entry in Termux's user applications directory, or select **Termux
graphics** and **Wayland** in the command shortcut editor. The normal launch
reuses a live recipe window; **New window** starts another session. Recent
retains the recipe and executor, not a generic Wayland host. The semantic recipe
key also supplies Android application-profile and saved-placement identity.
Wayland whole-desktop recipes and guest file environments fail explicitly until
their integration is implemented. See [Desktop entries](desktop-entries.md) for
the common format and argument rules.

Open **Linux graphics** from Start, create a session, and explicitly select X11
or Wayland and the client executor. Wayland startup commands open toplevels using
the manager's verified Android destination. The window picker can open an
existing toplevel; closing the manager retains the session. X11 additionally
offers its whole-desktop viewer and interface-scale control.

For example, with an installed GTK client in Termux:

```sh
magicdesk graphics.start --protocol wayland --backend termux --name GTK \
  --command 'GDK_BACKEND=wayland gtk3-demo'
magicdesk graphics.list
magicdesk graphics.open_window --sessionId SESSION_ID --windowId WINDOW_ID \
  --placement display --displayId 0
magicdesk graphics.stop --sessionId SESSION_ID
```

Start returns acceptance and a session ID, not readiness or client completion.
Use the live catalog and runtime events for observations. Server creation,
client execution and Android host placement are separate operations. MCP
requires its shell grant for execution, control for placement, and observe for
the catalog; none of those grants selects the execution UID.

## Build

The Gradle build compiles the pinned runtime on Linux with NDK 27.3.13750724,
or on the phone with Termux Clang. Both target ARM64/API 34 and use the same
Meson cross definition. Build tools include CMake 3.24+, Ninja, make, pkg-config,
Bison, Flex, a host C compiler and Expat development files for the host scanner.
Meson can be installed in a build-local environment:

```sh
python -m venv build/wayland-tools
build/wayland-tools/bin/pip install meson==1.9.1
./gradlew :wayland-runtime:assembleDebug :wayland-runtime:testDebugUnitTest :wayland-runtime:lintDebug
```

`magicDeskMeson` can explicitly select another Meson executable. The dependency
build downloads pinned archives with SHA-256 verification, uses an isolated
pkg-config prefix and disables external wlroots backends, XWayland and GPU
renderers. `wayland-runtime/native-deps/CMakeLists.txt` owns the source versions.
The protocol scanner is built from the same pinned Wayland source for the
build machine, separately from the target libraries. Its native pkg-config
search is separate from the private Android dependency prefix. Meson does not
run target executables while configuring Android libraries. Non-system
libraries are position-independent static archives for API 34.

wlroots is pinned upstream source, not a fork or an Android Gradle dependency.
`wlroots-android-shm.patch` selects the local Android shared-memory adapter while
retaining upstream's implementation on other platforms. `wlroots-android-libs.patch`
uses Bionic's clock functions in libc instead of requiring a separate librt.
Patch application is checked and idempotent; an incompatible upstream change
fails the build.
Rendering allocations use memfd. Read/write plus read-only FD pairs use an
immediately unlinked file in the explicitly provided private `XDG_RUNTIME_DIR`:
Android may deny reopening memfds through `/proc/self/fd`. There is no hardcoded
Termux directory or named shared-memory service.

The Gradle build packages dependency licenses with the runtime. libdrm's
per-file notices are preserved with its root source/header files because that
release has no aggregate license file. Packaging rejects an executor ELF with
RPATH/RUNPATH, non-ARM64 ELF files or dependencies outside the Android system
allowlist. APK checks require the executor, client helper and host renderer.

To export only the portable runtime libraries and licenses:

```sh
./gradlew :wayland-runtime:exportRuntime
```

The result is `wayland-runtime/build/runtime`. Windows builds consume this
directory through `-PmagicDeskWaylandRuntime=/path/to/runtime`; their NDK still
builds the Android host renderer. Linux/Termux may use the same explicit override.
The exported artifact contains no build-machine tools or pkg-config files.
CI builds the runtime on Linux and transfers it to the Windows job from the
same workflow run. Missing or invalid runtime artifacts fail the build instead
of producing an incomplete APK.

## Verification

```sh
ctest --test-dir wayland-runtime/build/dependencies --output-on-failure
cmake -S wayland-runtime/src/main/cpp -B build/wayland-portable -G Ninja \
  -DCMAKE_BUILD_TYPE=Debug -DMDW_BUILD_JNI=ON \
  -DMDW_DEPENDENCY_PREFIX="$PWD/wayland-runtime/build/dependencies/prefix" \
  -DMDW_WLR_PROTOCOL_DIR="$PWD/wayland-runtime/build/dependencies/wlroots-prefix/src/wlroots/protocol" \
  -DWAYLAND_SCANNER="$PWD/wayland-runtime/build/dependencies/host/bin/wayland-scanner" \
  -DCMAKE_C_FLAGS=--target=aarch64-linux-android34 \
  -DCMAKE_SHARED_LINKER_FLAGS=-fno-termux-rpath \
  -DCMAKE_EXE_LINKER_FLAGS=-fno-termux-rpath
cmake --build build/wayland-portable
env -u LD_PRELOAD -u LD_LIBRARY_PATH XKB_CONFIG_ROOT="$PREFIX/share/X11/xkb" \
  ctest --test-dir build/wayland-portable --output-on-failure
python -m unittest discover -s scripts/tests -p test_wayland_build.py
```

The CTests above execute Android binaries and run on the phone, not on the Linux
cross-build host. The build-boundary fixtures run on the host and reject wrong
architectures, loader search paths, extra shared-library dependencies and
unreadable ELF files. Cross-building does not provide API-34 device coverage.

The XKB path above supplies test keyboard data from the selected environment;
the runtime does not encode it. A standalone launch must provide accessible XKB
data explicitly, as with X11. Tests use isolated runtime directories and check
real client pixels, input delivery, output detach/reattach, graceful close,
socket cleanup, FD access rights and immutable frame transport. The client-helper
fixture also tests invalid inherited descriptors, exec failure and descriptor
isolation across exec. Unit tests cover UID/nonce admission and replay, and
render admission without consuming frame credit. The window fixture covers
minimum-size coordinate mapping, unchanged metadata, rendering backpressure and
hidden-output suspend/resume.
The shell fixture checks explicit admission, separate application/shell catalogs,
configure deduplication, transparent pixels, pointer interaction without keyboard
capture, key release on policy revocation, idle-frame suppression, unmap/remap and scope teardown without
application termination. The geometry fixture covers popup constraint adjustment,
negative paint extents, synchronized subsurface movement, input holes and bounded
region publication, viewport-aligned pointer delivery, and stable metadata during
pixel-only repaints. These native fixtures do not establish Android-host
shell integration.

The installed debug APK tests the production Binder handoff and native exec
helper with separate app and executor UIDs:

```sh
am instrument --no-restart -w -e wayland_fd true -e wayland_executor shell \
  io.github.mekhontsev.magicdesk/.ShellProbeInstrumentation
am instrument --no-restart -w -e wayland_fd true -e wayland_executor termux \
  io.github.mekhontsev.magicdesk/.ShellProbeInstrumentation
```

`WaylandRuntimeInstrumentation` launches the compositor and native window fixture
through the selected Termux endpoint, without Desktop or privileged-service
startup. It checks Android Surface pixels through ImageReader, pointer/key
delivery and release, window destruction and server shutdown:

```sh
am instrument --no-restart -w \
  -e client /absolute/termux/path/to/build/wayland-portable/wayland-window-test \
  io.github.mekhontsev.magicdesk/.WaylandRuntimeInstrumentation
```

Require an explicit `passed` result; instrumentation completion alone is not a
pass. Android Activity focus changes, general application compatibility, and
operation on API 34 still require device validation.

The shell variant drives a real layer-shell client through JNI/Binder and the
common layout adapter, using an isolated scope rather than Desktop. It checks
family geometry publication and revocation, premultiplied alpha in an Android Surface, keyboard-inert pointer interaction,
on-demand keyboard input, remapping and scope revocation:

```sh
am instrument --no-restart -w -e shell true \
  -e client /absolute/termux/path/to/build/wayland-portable/wayland-shell-test \
  io.github.mekhontsev.magicdesk/.WaylandRuntimeInstrumentation
```

On API 36, both UID-2000 and Termux-UID client handoffs passed. The runtime fixture
also passed compositor startup under the selected Termux UID, cross-UID frames
into an Android Surface, input delivery/release, client close and server exit.
These tests do not establish broad GTK/Qt compatibility or desktop-host UX.

The API-36 Android-host workflow also passed with Termux GTK3: software pixels,
pointer-driven tabs and menus, key input, child-process toplevels in separate
Android tasks, protocol close, and forced client disconnect with the compositor
retained. The X11 regression covered a GTK viewer, pointer input, retained
sessions after viewer/manager closure, and explicit session shutdown. Selecting
a session in the shared manager did not create a viewer. These are focused
workflows without Desktop, not a declaration of full GTK/Qt feature parity.

The shared recipe workflow on API 36 covers Termux GTK3 and Qt6 Designer launches,
additional toplevels, keyboard dismissal of a Qt dialog, protocol closure and
application-session shutdown after the last window. GTK also covers recipe reuse
without duplicate hosts, explicit new instances, and reopening from Start's
independent Recent list. The corresponding X11 GTK recipe still launches and
closes through the same coordinator. Qt popup interaction and close confirmation
need further validation; this coverage does not include managed Desktop placement
or an API-34 device.

The API-36 shell runtime fixture passed with a Termux-UID compositor and an
app-UID Android presenter: typed catalog/configure exchange, alpha pixels,
family geometry with precise input holes and revocation, pointer interaction while
application keyboard focus is retained, on-demand keyboard transfer, remapping
and scope release. The ordinary application runtime fixture also passed, including
family geometry publication and cleanup. These checks do not
establish Android chrome-host integration, Linux panel UX or API-34 coverage.
