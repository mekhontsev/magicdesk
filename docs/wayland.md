# Embedded Wayland Runtime

## Status

The `wayland-runtime` module contains an experimental per-toplevel compositor and Android
runtime module. The APK packages the compositor and a separate host renderer.
**Linux graphics** manages X11 and Wayland sessions through the same controls;
Wayland opens each toplevel in an ordinary Android host. A nested compositor can
instead present a whole Linux desktop in a retained Android viewer. The shared
`graphics.*` automation commands also generate
the built-in CLI interface. Start and `.desktop` recipes select X11 or Wayland
through the shared graphical launch model; ordinary installed Termux entries
default to X11. A successful native test or APK build does
not establish application compatibility or support on every Android release.

Termux is an optional build environment, not a runtime dependency. The executor
library statically links its non-system dependencies and uses only Android system
libraries (`libc`, `libm`, `libdl`, `libandroid`, `liblog`). The host renderer uses Android's public
`ANativeWindow` API and does not link wlroots or load Termux libraries.

The launch integration shares the X11 execution model: Termux is an optional
client executor; shell clients use the explicitly authorized command identity,
while their compositor runs under MagicDesk's app UID, never an elevated renderer
UID. Termux clients use the compositor's private named socket, allowing each
program and its children to establish independent connections. UID-2000 shell
clients instead receive one connection FD. That FD belongs to one Wayland
connection, not a shareable endpoint for unrelated clients. Root clients use a
session-owned named endpoint with descriptor admission, described below.
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
not successful client exec.

Connection requests and handoff channels have deadlines and explicit cleanup.
Handoff completion callbacks run after cleanup and outside resource locks.
Cancelling a handoff cannot revoke an FD already received by the client;
the command/session owner still owns client cancellation. `WaylandSessions`
owns admission, pending transfers and runtime exit independently of its hosts.
Do not expose a shell client's private socket or raise the compositor UID to
bypass this boundary. Socketpair peer credentials
describe its creator and must not be mistaken for the launched client's identity.

### Root Guest Connections

An explicitly selected root executor owns one native broker for the session.
It publishes a named socket in the selected session directory; independent
chroot programs and child processes connect normally through `WAYLAND_DISPLAY`.
The app-private enclosing directory remains mode 0700. The session directory
is searchable for the guest's ordinary Linux users and may be bind-mounted by
the user's entry script. The compositor's own socket remains private.

`WaylandBroker` owns an authenticated local control connection and a bounded
startup deadline. Readiness requires receipt, reading and writable shared mapping
of an actual admitted memory buffer. The label comes from the authenticated compositor's own memfd,
including its current SELinux categories. The native helper validates the app
UID on its control and upstream connections. It never impersonates an Android
package, changes SELinux policy or elevates the compositor.

The shared native FD-stream transport has bounded queues, close-on-exec
descriptors, backpressure and single delivery across partial writes. The broker
accepts at most 32 client connections. Anonymous-buffer admission only changes
unlinked regular tmpfs objects matching the selected executor's own new-memfd
label; the destination is the compositor's buffer label. Other anonymous labels
are rejected, not rewritten. Linked files, pipes and DMA-BUFs are not relabelled.
Protocol bytes and FDs cross the broker; pixel contents are not copied by it.

Closing the session or losing the control channel releases the listener and
connections. Command completion does not close this session endpoint, so a
program that detaches from its launcher can retain its connection. Root access
does not guarantee a firmware permits descriptor admission: failed startup
remains explicit, without a different UID or system-policy fallback.

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
  that client connection, not other clients or the compositor. A whole-desktop
  viewer detaches without requesting client closure; the manager owns explicit
  session shutdown.
- `GraphicalApplicationLaunch` shares recipe reuse, profile-scoped application
  identity, Recent and Android placement between X11 and Wayland. The pending
  application host claims the first mapped toplevel; additional toplevels use
  `HostedWindowPresentation`. A recipe launch owns a dedicated session, which
  ends when its last window is destroyed. Whole-desktop recipes and sessions
  explicitly created in the manager remain retained until stopped. Deleting a shortcut forgets its launch
  history without closing live clients.
- The native compositor API knows no Java classes, packages, Binder authorization,
  Android tasks or placement policy. Its calls and callbacks are serialized on
  the owning event loop; strings and frame images are borrowed during callbacks.
- The Android server adapter checks the host UID, retains one Binder owner and
  stops on owner death. It integrates the Wayland event-loop FD with the Android
  Looper, without a polling timer. Startup announcements still require host-side
  UID, nonce, session and Binder validation before use.
- Hosts borrow outputs. Releasing an output does not close its Wayland client.
  Closing a window sends `xdg_toplevel.close`; client destruction is a separate
  observation. Closing the retained session stops the server.
- Frames cross Binder as retained HardwareBuffers and acquire fences, or sealed
  RGBA memfds when hardware storage is unavailable. Each output has one
  unacknowledged frame; its wlroots buffer stays locked until the presenter
  completes reading it. Credit is checked before
  rendering; a deferred update requests a redraw after acknowledgement. Stale
  acknowledgements cannot release a newer frame. An absent Android Surface
  disables its output and releases focus instead of continuing hidden animation.
- Each Android output owns a shared `HostedFramePresenter` worker and its buffer queue.
  Session control, input, connection deadlines and other outputs never wait for
  its `ANativeWindow` writes. Output closure releases the presenter independently
  from client/session closure. Window metadata crosses Binder only when changed;
  pixel-only commits do not rebuild the window catalog.
- A presentation generation identifies an output viewport and its retained
  Android Surface. Stale frames are acknowledged without presentation, and stale
  viewport failures cannot reject a newer request. `FramePresentation` completes
  a receipt only after a matching frame is queued to that Surface; it is not an
  Android compositor/scanout acknowledgement or a grant of input focus. Replacing
  a request cancels its old receipt. A Surface replacement requests fresh pixels
  even when geometry and client content have not changed.
- Native pointer and keyboard input have separate output owners. A keyboard-inert
  shell surface can accept pointer input without taking the application's keyboard.
  Focus transfer, output release, keyboard-policy revocation and unmap release the
  affected owner's held keys/buttons. Protocol-specific input uses evdev codes and
  a Wayland seat, not X11 button/key encodings.
  Pointer hit-testing uses the rendered scene and output dimensions, including
  when a client has not acknowledged a resize or retains a larger minimum size.

The compositor uses the [shared Vulkan/software renderer](graphics.md) through
wlroots' renderer/allocator interfaces, without a wlroots fork. Frame transport
and Android presentation remain separate from launch, input and placement policy.
Optional `linux-dmabuf` v3 admission supports explicit linear RGB allocations
through the [shared GPU importer](graphics.md#linux-buffer-import), without a
DRM node. SHM/software remains the compatibility path; GPU drivers are not a
startup requirement.

The native runtime covers xdg-toplevel discovery and metadata, GPU/software
rendering, configure/ack, frame callbacks, borrowed-output resize, pointer/key
input, focus release and graceful close. Popup nodes retain their parent's native scene
and grab lifetime. Managed API-35+ hosts can present the popup family outside the
parent's task crop through the shared opt-in
[dependent-surface host](shell-layout.md#application-relative-surfaces).
Ordinary subsurfaces remain in their owning tree; transient toplevels retain
separate Android hosts. Shell popup constraints and surface-family input geometry
are covered by native fixtures. Physical keys use the shared Android-to-evdev
mapping. XWayland is not enabled.

## Host Interaction

`HostedSurfaceView` owns Android input and IME lifecycle. The Wayland adapter
bridges `text-input-v3` preedit and committed UTF-8 text to the focused client.
Other text-input protocols are not implemented.
`HostedTextState` carries immutable surrounding text, cursor/selection and field
purpose/hints to the shared Android InputConnection. UTF-8 byte offsets are
converted at the Wayland boundary; Android receives UTF-16 offsets and appropriate
text, email, numeric or password editor flags. Unavailable context is distinct
from empty text. Client commits update context without restarting the keyboard
on each keystroke. Multiple Android connections borrow one editor composition;
discarding a candidate connection does not close the active editor. Enable
generations reject old editors; destructive edits additionally require the observed
text/selection revision, unaffected by cursor-rectangle commits. Client-declared
IME acknowledgements update selection without invalidating queued keyboard edits.
Surrounding-text deletion
preserves the active composition in the same protocol batch. Arbitrary remote
selection changes and composing regions are not fabricated when the protocol
cannot express them.

The guest caret rectangle travels with its editor context in normalized output
coordinates, including scene offsets and host density. The shared InputConnection
publishes an Android `CursorAnchorInfo` insertion marker through the rendered
viewport transform. Editor and layout events update it; unchanged video frames
do not. Unavailable glyph bounds and baselines remain unknown. The content host
uses the existing system-bar/IME safe area, so keyboard occlusion changes the
client viewport and the caret transform together.

Client enable/disable events update the Android text editor; clients without this
protocol retain physical-key input. Commits are split at UTF-8 boundaries to fit
Wayland messages. Oversized composition stays in Android until committed.
Cursor surfaces update Android's pointer image and hotspot rather than drawing a
second cursor in the framebuffer. Focus/output loss restores the Android default.
SHM cursors use direct pixel access; GPU cursors use the shared asynchronous
`MdgReadback`. Fence callbacks advance readback without blocking protocol dispatch.
New commits, focus changes and surface destruction cancel obsolete requests and
release their producer leases. Cursor storage is bounded to 256 by 256 pixels.

Each application output uses the shared `HostedUiScale` policy: Android density
divided by 160, retaining the fractional scale from 1 to 8 and limited by a logical window
offer of at least 600 on the short side and 800 on the long side where possible.
The offer excludes stable system bars and cutouts, not IME insets or client
constraints. Output scale, Android client limits and child placement use the
same result. Fractional scale exposes it to clients; preferred integer buffer
scale rounds upward independently of the logical interface scale.
Surface coordinates remain logical. Output rendering uses a uniform scale bounded
by the 4096-pixel buffer limit, also reported through `wl_output`; it does not clip
client geometry independently on each axis. Rendering, pointer input and IME caret
coordinates use this same output transform. The requested density is retained
independently, so resizing does not feed the presentation scale back into layout.
Fullscreen requests carry a native revision through
`HostedFullscreen` and the existing Android presentation gateway. A stale host
or acknowledgement cannot confirm a newer request. Neither feature changes
Android task-area ownership.

Client minimum/maximum dimensions and parent identity use the shared
`HostedWindowLayout` contract. Zero limits are unspecified. Native configure
uses the shared native `hosted_window_size` policy: preserve the host aspect ratio
within client limits, expanding the other axis when a minimum requires shrinking
the presented content. Incompatible limits retain aspect-fitted letterboxing.
This also applies after mapping; the original offered Android viewport is retained
for later density changes. `HostedContentLayout` centers a
size-limited client without enlarging it to fill the host. A transient toplevel
opens relative to its actual parent's Android host; managed windowed placement
includes Android decorations and is clamped to the workspace. Independent hosts
retain ordinary Android placement.

`HostedWindowCommands` handles shared maximization state and validated pointer
move/resize requests. It uses the existing Desktop task gateway, preserving
ordinary restore bounds and confirming observed Android geometry with the
request serial. One host owns each window's responses. Pointer grabs require
the matching seat, client surface and press serial; Android motion is coalesced
behind one outstanding bounds command. Focus loss or host closure cancels the
gesture. Viewport changes during the host-owned gesture retain the guest button
until its actual release. Without managed Desktop these requests do not create a workspace or
claim an Android task.

`HostedContentExchange` owns Android clipboard focus, drag lifecycle and URI
grants for both graphical protocols. `WaylandContentExchange` translates MIME
offers and `wl_data_device` events; `HostedContentTransfer` handles Android
providers and guest file access. Text, HTML, PNG and file URI lists reuse the
same payload model as X11. Same-session drags retain the original guest source
so Android does not insert a second copy. Offer IDs qualify completion and
cancellation, including transitions between hosts.

Native content I/O uses event-driven nonblocking pipes and sealed memfds, with
at most 16 transfers and 128 MiB per transfer. Each readiness callback moves at
most 64 KiB. The 30-second deadline cancels an unfinished transfer; it is not a
settling delay. Host provider I/O runs on bounded workers, independently of the
compositor event loop and Android main thread. Clipboard ownership is scoped to
the focused host, with source tagging to prevent feedback.

`HostedFileExchange`, `SharedFileNamespace` and `GuestFileBridge` are shared with
X11. A guest recipe binds a private session directory at `/tmp/magicdesk-wayland`
and starts the authenticated helper as the guest application's user. Import
paths refer to that shared directory; export opens the actual guest file, not
an identically named host path. One session retains one explicit file environment.
The PRoot recipe builder supplies these bindings; custom entry scripts own their
mounts and authorization. Root chroot recipes use the session broker and the
same guest file environment; the renderer remains under the app UID.

## Native Shell Surfaces

Integrated Linux shell components use the shared
[shell layout model](shell-layout.md). A session explicitly binds to a MagicDesk
workspace to contribute its panels and reservations. A nested Linux desktop
retains its own scope and cannot reserve space on its containing Android Desktop.
Native wlroots owns protocol validation, configure/ack, scene nodes and seat
state; the geometry model does not replace Android task planes.

The native API admits layer-shell only after its owner provides a shell event
consumer and explicitly creates a logical shell output. This output is distinct
from the borrowed render outputs. Shell and dependent render targets do not
advertise additional `wl_output` globals; application hosts publish their own
output geometry and scale. Layer surfaces use a separate catalog and
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
An explicitly hosted binding reconciles its catalog and family geometry through
the protocol-neutral `HostedShellWindows` owner. The Android adapters admit
keyboard-inert background/bottom surfaces inside HOME and top panels in chrome,
all on the selected Desktop's actual layout scope. Its separate presentation scope
applies existing fullscreen and reveal policy without unmapping the Linux panel
or releasing its reservation. Role or
host failure revokes that contribution and its reservations, not the session.
Catalog snapshots are published with their UI callbacks so rapid unmap/remap
retains lifecycle order. Top panels can request on-demand keyboard focus through
the chrome host's focus gate. **Linux graphics** exposes an explicit shell-workspace
selection; ordinary sessions do not enable layer-shell admission automatically.

An admitted workspace also supplies a `wlr-foreign-toplevel-management` catalog.
It contains that workspace's managed Android task hosts, including hosted Linux
applications, with opaque lifetime-bound handles. Activation, close, maximize,
unmaximize, fullscreen and exit-fullscreen requests go through the same task
gateway as MagicDesk's own controls. Minimize and unminimize map to Desktop
concealment and activation, preserving task mode, bounds and plane. Unbinding
closes handles and revokes requests without closing those applications.
Metadata/action processing is event-driven; pending wlroots idle notifications
are dispatched before flushing and waiting for the next protocol event.

`MdwView` owns a rendered surface family independently of its xdg or layer role.
Ordinary application outputs use wlroots scene rendering. Transparent shell
families use the public render-pass API with cached client textures and the output
swapchain, preserving premultiplied alpha rather than an opaque background. The
scene retains subsurface placement, damage events and hit-testing. There are no
additional per-buffer texture imports or pixel copies in this compositor pass;
frame transport uses the shared hosted graphics contract. Explicit shell/dependent
viewports use unit scale and no output transform; application outputs use their
host density. Client buffer transforms and viewports are
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
reserved strip. `Output.setSurface(Surface, WaylandViewport)` can include negative
paint extents without resizing the client; pointer hit-testing uses that same
viewport origin. Its future confirms submission of the corresponding pixels.
The size-based application adapter also configures the client at origin zero.

The protocol-neutral `HostedShellSurfaceView` consumes these receipts and exact
input geometry in a bounded Android child window. Its chrome surface lease
retains the panel across ordinary popup dismissal; host loss releases only its
borrowed output. Input is enabled after the matching layout/pixels and a cancellable
system input-region acknowledgement, not a render callback or a guessed delay.
Touchable regions alone do not establish cross-UID pass-through:
Android's obscuring-window check also considers window frames. The optional
trusted-overlay capability of the existing chrome host addresses that separate
boundary; see [shell layout](shell-layout.md#android-adapter). Linux surface
admission must request it explicitly and retain the frame/region checks above.
Ordinary application hosts retain their existing viewport policy.

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
See [Desktop entries](desktop-entries.md) for
the common format and argument rules.

Open **Linux graphics** from Start, create a session, and explicitly select X11
or Wayland and the client executor. Wayland startup commands open toplevels using
the manager's verified Android destination. The window picker can open an
existing toplevel; closing the manager retains the session. Both protocols offer
whole-desktop presentation. X11 also has a session-wide interface-scale control;
Wayland derives application scale from each Android host's density and size.

For a nested desktop, select **Nested Linux desktop** in the manager, or set
`X-MagicDesk-GraphicsMode=desktop` in a Wayland recipe. Supply a compositor
command with a Wayland backend and software rendering, for example Weston 16:
`weston --backend=wayland --renderer=pixman --shell=desktop-shell.so`.
The guest compositor owns its internal windows. Closing the Android viewer
retains the session; reopening it borrows the existing toplevel. This mode does
not itself install or start an arbitrary desktop environment. `graphics.start`
accepts `wholeDesktop=true`; `graphics.open_window` accepts window ID zero for
such a session. Ordinary Wayland sessions require a real native window ID.

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
pkg-config prefix and disables external wlroots backends, XWayland and wlroots'
GPU renderers. Composition uses MagicDesk's shared graphics backend.
`wayland-runtime/native-deps/CMakeLists.txt` owns the source versions.
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
The Android POSIX shared-memory adapter uses memfd. Read/write plus read-only FD pairs use an
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

`graphics.inspect_window` reads a bounded native surface family on the compositor
event loop, including popups and subsurfaces without opening a renderer output.
Related toplevels retain their own coordinate origins. Android host geometry and
workspace ownership come from the common session presentation registry. See the
[automation contract](automation.md) for identifiers, state predicates and
separate client, viewer and session close operations.

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
family geometry publication and revocation, premultiplied alpha in an Android
Surface, negative-origin viewport pixels/input, Surface replacement, keyboard-inert
pointer interaction, on-demand keyboard input, remapping and scope revocation:

```sh
am instrument --no-restart -w -e shell true \
  -e client /absolute/termux/path/to/build/wayland-portable/wayland-shell-test \
  io.github.mekhontsev.magicdesk/.WaylandRuntimeInstrumentation
```

Add `-e chrome_display DISPLAY_ID` to exercise the Android shell host on an
explicitly selected active Desktop. This fixture uses the production surface
lease, alpha pixels, negative viewport origin, exact input holes, immediate input
after admission, replaced receipts and borrowed-output cleanup. It does not start
Desktop or select a display implicitly.

The workspace variant uses the selected Desktop's actual layout scope and
automatic catalog-driven hosting instead of fixture-supplied window placement:

```sh
am instrument --no-restart -w -e workspace_display DISPLAY_ID \
  -e client /absolute/termux/path/to/build/wayland-portable/wayland-shell-test \
  io.github.mekhontsev.magicdesk/.WaylandRuntimeInstrumentation
```

It checks movement without redundant configure, exact input holes, unmap/remap,
unsupported keyboard-role rejection and removal of the workspace reservation.
An ordinary application must survive shell revocation and close independently.
On RM11/API 36 with service UID 2000, this workflow passed on the wired display.
Add `-e policy_task TASK_ID` for an existing managed freeform task to exercise the
production fullscreen/restore gateways and native Start conceal/reveal. The
fixture requires fresh output/input receipts on reveal and checks that the panel's
mapping, reservation and the application's fullscreen plane remain stable. This
variant also passed on RM11/API 36 with service UID 2000 on the wired display.

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
additional toplevels, Qt menu selection and dialog dismissal, protocol closure and
application-session shutdown after the last window. GTK also covers recipe reuse
without duplicate hosts, explicit new instances, reopening from independent
Recent, managed placement, fullscreen/restore and cancelling a close confirmation.
GTK clipboard text and file drag-and-drop pass in both directions with Android,
including PRoot Ubuntu and Alpine chroot recipes with file-content hash verification.
The chroot GTK client and guest-file helper run as UID 65534; the renderer stays
under the MagicDesk app UID. `tests/gtk-guest-content.py` provides text/email/PIN
fields, clipboard actions, file drag endpoints and a dependent dialog for these
checks. Same-session
drags between Android hosts insert once. The X11 regression covers clipboard and
files in both directions through the shared adapters. GTK menus accept pointer
input after a density change. These checks use RM11/API 36 and do not establish
API 34 device compatibility.

Weston with its Wayland backend, Pixman renderer and desktop shell presents a
nested desktop and terminal in Termux (16), PRoot Ubuntu (13) and Alpine chroot
(14, UID 65534). Closing and reopening its Android viewer retains the guest
session and terminal processes. This is software-compositor coverage, not validation of every
Linux desktop environment or hardware acceleration.

Native fixtures exercise text-input-v3 preedit/Unicode commit, snapshot-qualified
surrounding deletion with composition preservation, stale editor rejection, cursor pixels,
scale, stale fullscreen acknowledgements, bidirectional selections, native drag
delivery and bounded content-stream cancellation. `HostedInputInstrumentation`
exercises Android InputConnection composition/commit, UTF-16 surrounding text and
selection, field-purpose/privacy flags, overlapping Android connections and stale
editors alongside the shared pointer and cursor tests. This IME coverage is limited
to text-input-v3.

`HostedGuestEditorInstrumentation` exercises a real GTK3 or Qt guest through its Android
host: composition, Unicode correction, field switching, private PINs, caret geometry,
IME-inset delivery and a size-constrained dialog. Prepare an independent
non-phone display and a command launching `tests/gtk-guest-content.py` with the
session's Wayland socket available inside the guest, then run:

```sh
am instrument --no-restart -w -e display DISPLAY_ID -e command 'GUEST_LAUNCH_COMMAND' \
  io.github.mekhontsev.magicdesk/.HostedGuestEditorInstrumentation
```

With `-e ime true`, select the debug-only `HostedFixtureIme` first; the test uses
Android's real keyboard connection and visible IME insets. Restore the previously
selected keyboard afterwards. Without that argument it exercises controlled inset
delivery. The Qt fixture is `tests/toolkits/editor.qml` (`-e entryY 95`), launched
with `QT_QPA_PLATFORM=wayland` and `QT_WAYLAND_TEXT_INPUT_PROTOCOL=zwp_text_input_v3`.

The caller needs instrumentation permission. `--no-restart` retains the running
application and its display resources. The fixture closes its guest session and
hosts. The GTK script's `--window-controls` mode supplies client-side maximize,
move and resize controls for managed-window checks. On RM11/API 36, the PRoot GTK3
fixture passes the editor workflow and managed maximize/restore, move, resize and
parent-relative dialog placement/dismissal. The shared X11 host regression covers
a centered size-constrained GTK dialog and pointer-driven closure without Desktop.
The Qt fixture supplies two levels of transient dialogs; moving its nested dialog
between displays with different automatic scales verifies scale publication and input.

Qt 6.11.2's text-input-v3 client can omit the final `commit` after surrounding-text
deletion: its reselection handling clears `needsCommit`. The strict correction
stage exposes this limitation; MagicDesk does not consume uncommitted client state.
`-e correction false` runs the other editor stages and explicitly reports correction
as `NOT_TESTED`, not passed. See Qt's
[text-input-v3 client](https://github.com/qt/qtbase/blob/v6.11.2/src/plugins/platforms/wayland/qwaylandtextinputv3.cpp).

`tests/toolkits/interaction.qml` exercises Qt Quick through its Vulkan renderer:
animation, pointer input, text entry, menus and separate popup windows. On RM11/API
36, Qt 6 with Mesa Turnip submits linear DMA-BUFs successfully. Termux GTK4's
software renderer also presents correctly; its installed build has no Vulkan
renderer. Its GL/Zink renderer presents GPU content with the separate
[Mesa client-loader patch](graphics.md#client-gl-loaders). These toolkit/driver
results are not interchangeable with compositor GPU support.

Root-broker validation on RM11/API 36 with SELinux Enforcing covers Alpine
Weston terminals as root and UID 65534, independently launched and child
connections, a nested Weston desktop, and a client detached from its launcher.
The compositor remains the MagicDesk app UID. Normal session closure, cancellation
during startup and forced broker death release the owned processes and sockets;
broker loss produces an explicit session failure. The transport fixtures verify
partial writes, backpressure, descriptor ordering, EOF draining, ancillary
truncation and rejected-buffer cleanup. Anonymous-buffer fixtures check label
admission and rejection without modifying global policy. The tested root provider
is Magisk; other root providers, chroot GPU drivers and API-34 devices are outside
this coverage.

The API-36 shell runtime fixture passed with a Termux-UID compositor and an
app-UID Android presenter: typed catalog/configure exchange, alpha pixels,
family geometry with precise input holes and revocation, generation-qualified
viewport receipts and pixels after Surface replacement, pointer interaction while
application keyboard focus is retained, on-demand keyboard transfer, remapping
and scope release. The ordinary application runtime fixture also passed, including
family geometry publication, an unchanged viewport's fresh frame receipt and
cleanup. The chrome-host variant also passed on RM11/API 36 with privileged UID
2000, including the first injected click after input-region acknowledgement.
These checks do not establish automatic Linux panel UX or API-34 coverage.
