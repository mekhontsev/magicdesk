# Shared Service Implementation

[Architecture overview](architecture.md) | [Documentation](README.md)

Privileged execution, retained Linux graphics and recording lifetimes. These services are independent of managed Desktop.

- [Embedded Linux Graphics](#embedded-linux-graphics)
- [Privileged Service Runtime](#privileged-service-runtime)
- [Desktop Display Recording](#desktop-display-recording)
- [Desktop Entry Execution](#desktop-entry-execution)

## Embedded Linux Graphics

X11 and Wayland use a shared launch model, retained-session controls, Android
hosts and graphics backend. `GraphicalSessions` supplies the common catalog and
commands; protocol owners retain their native window identities and lifetimes.
The [X11](x11.md), [Wayland](wayland.md), [graphics](graphics.md) and
[shell-layout](shell-layout.md) documents define their focused contracts.

`X11Sessions` retains independently owned X servers and lazy
native renderers. Ordinary `X11Activity` windows borrow outputs. A whole-session
viewer retains its server when closed; an individual-client host requests X11
window closure, and an application-owned server ends after its last window.
Admission requires the captured server UID
and a session nonce before Xorg starts. The server's owner Binder ties its
lifetime to the MagicDesk process, while Xauthority isolates X clients.
No Desktop coordinator or HOME lease is initialized by this shared tool.
`X11Execution` owns server bootstrap: Termux uses RUN_COMMAND, while Shell uses
an app-UID process and explicitly staged XKB data. `CommandExecution` captures
the selected client executor once; it never changes identity on failure. Its
owned shell commands reuse `ShellCommandSession`/`ShellCommandExecutor`, also
used by MCP consoles, rather than starting untracked background processes.
`OperationResources` handles completion-before-registration and cancellation;
completed resources are removed, and dependents close before their server.
Android placement goes through `ToolApplications`.
`WaylandSessions` owns the corresponding compositor admission, client transports
and toplevel catalog. Termux clients connect to a private named socket; Shell
clients default to a connection FD, and prepared root guests use a session-owned
named-socket broker. Explicit guest-runtime recipes select routed admission for
either protocol: `ShellUnixEndpoint` accepts connections under the captured
executor UID, `GraphicalSocketEndpoint` transfers owned descriptors, and
`HostedSocketAdmission` delivers them to the protocol loop. Binder carries FD
admission, not protocol bytes or shared buffers. X11 still requires its cookie.
These owners neither elevate renderers nor proxy socket IO.
The server's event loop owns wlroots state, while borrowed
outputs and their Android presenters have independent lifetimes. Nested-desktop
viewers retain their compositor after closing, like X11 whole-screen viewers.
Read-only Termux and guest `.desktop` catalogs feed shared Start content and
MCP/CLI discovery through `ApplicationCatalog`. `DesktopEntrySource` separates
their authorities from arbitrary file access: Termux uses its selected service;
guest discovery reads the image account's XDG paths through the native inode
namespace. Guest keys include immutable environment identities, and launches
resolve exact catalog entries into ordinary `LinuxLaunchRecipe` values.
Guest snapshots refresh explicitly; the shared bounded bitmap decoder handles
PNG icons without guest execution or menu-time file IO.
Native supervisors own guest launch records across all entry points. On-demand
list/stop adapters validate lifetime locks and pidfds, not cached Android process
state. The native publication layer similarly owns import/backup staging leases;
the environment library owns names, dependency cleanup and download staging.
`HostedApplicationStartup` starts graphical sessions before admitting their mapped
clients through normal Android launch requests. `GraphicalApplicationLaunch`
reuses retained recipe/window identities;
the native window model owns X relationships, never Android task topology.
Clipboard and copy drag-and-drop reuse the shared Android content boundary;
the fork owns native selection/XDND negotiation, while MagicDesk owns the Java
transactions, focus and URI grants. The local `x11-runtime` module owns Java,
Binder bootstrap, executor context and JNI. The native fork exposes `embedded.h`
with opaque connections, borrowed native windows, owned descriptors and callbacks;
it has no Java classes, Android application, Gradle modules or JNI dependency.
`GraphicalSessionsActivity` selects and controls X11 and Wayland sessions; `X11Activity` hosts
client/desktop viewers. `X11HostBinding` scopes the viewer's borrowed
output, content exchange, subscriptions, density and fullscreen responder;
closing/recreating that binding does not own the retained server. Density,
clipboard, size and fullscreen keep their independent ownership policies.
Read-only window-family inspection uses the native model's existing membership
policy. `X11Session` correlates bounded replies on its connection thread and
cancels pending reads on reconnect, disconnect or close. `WaylandSession` uses
bounded event-loop inspection replies over the existing scene watches.
`HostedWindowPresentation` publishes live Android host associations from its
session-owned registry. `AutomationGraphicsInspection` serializes either native
family and the common host snapshot with the content grant. Neither inspection nor host association
acquires an output, queries privileged tasks or changes focus. The native
snapshot, Android host observation and screenshot are separate observations.
The manager never acquires an output,
clipboard ownership or density ownership. `HostedSurfaceView` owns Android
Surface/input/IME lifecycle through `HostedSurfaceOutput`; `X11SurfaceOutput`
owns X11 input encoding. `HostedViewport` owns the aspect-fit coordinate transform
without imposing a guest protocol's clipping or coordinate quantization.
`HostedContentExchange` owns Android clipboard focus,
drag gesture lifetime and URI grants through `HostedContentBackend`;
`X11ContentExchange` and `WaylandContentExchange` own their respective protocol
offers and drag transactions. `HostedContentTransfer` streams Android payloads
through the selected guest namespace; `hosted-runtime` owns seekable content
descriptors, file staging and the authenticated guest-file bridge. These host
contracts do not impose X11's session-global density or root-window model on
Wayland. `HostedWindowOwners` qualifies per-native-window command responders;
each protocol confirms requests through its own revision/acknowledgement rules.
Protocol execution remains separate from the common graphics backend and frame
presentation; X11 wire state and Wayland protocol objects do not enter Android policy.
`HostedWindowLayout` carries client size limits and parent identity in protocol
units. `HostedContentLayout` fits constrained content inside Android's inset-safe
area; `ToolApplications` computes managed placement with Android decorations.
Transient windows center on their parent; size-limited windows without a parent
center in the work area. Unbounded parentless windows retain normal launch placement.
`HostedWindowSizing` fits a new managed host once its own caption insets arrive,
including when launched from a fullscreen host, and reconciles subsequent client
size-limit publications. Layout/catalog events and one outstanding bounds command
drive this policy; no timer or guessed settling interval is used. A manual size
change relinquishes automatic fitting, while movement preserves the new center.
Both protocol Activities allow sizes below Android's default freeform minimum.
Independent Android placement is unchanged.
`HostedWindowCommands` routes supported client maximize and pointer
move/resize requests through `DesktopTaskController`, with one outstanding bounds
command and latest-motion coalescing. It never creates Desktop or changes task
area ownership. Bounds changes use the persistent privileged service's typed
Binder operation and `HiddenTaskApi`'s ATM resize semantics.
`HostedTextState` supplies optional normalized caret geometry to
the shared InputConnection; Android cursor-anchor coordinates use the same
rendered viewport transform as pointer input. A logical editor owns composition
and cursor subscriptions independently of Android's candidate InputConnections.
Closing one transport does not discard another connection's composition; editor
replacement invalidates all its transports. Text/selection revisions qualify
destructive edits independently of caret geometry. Protocol-declared IME
acknowledgements do not invalidate queued keyboard commands; external text changes
can refresh Android's context. Private guest text is excluded from Android
surrounding-text snapshots.
Individual outputs use `hosted_window_size` for aspect-compatible client geometry
within minimum/maximum limits. X11 supplies validated pixel hints through the
native server's host sizing callback; Wayland applies the same policy in logical
units. The original host offer and requested density remain independent of the
constrained size and presentation scale. Fixed-size content remains aspect-fitted;
the policy has no per-frame allocation or Java callback. Wayland bounds its buffer
allocation by uniform rendering scale, shared with input and caret mapping.
Protocol adapters own hint decoding. Managed individual hosts lend supported
dependent families to `HostedFamilyWindows` outside the parent task crop; placement,
scale and exact input admission use the shared shell host. Independent hosts and
unavailable external presentation retain one aspect-fitted family canvas shared
by rendering and input. The shared startup coordinator gives declared splash
screens and startup dialogs temporary client-sized hosts, independent of the
main application's saved presentation. Activities borrow exact native windows;
they neither start Linux commands nor select a replacement main window. A role
change can replace a temporary host without closing the native client. Root outputs leave Linux
window placement to its window manager. Window titles and bounded EWMH icons
flow through the existing X catalog into Android task descriptions and
`BuiltInWindowRegistry.PresentationSource`. Taskbar/overview/picker presentation
can vary per window. The Android component/profile still identifies the host;
the associated launch recipe distinguishes hosted window state without a second
store or task observer.
Dedicated application sessions also forward EWMH fullscreen requests and
versioned host acknowledgements. `X11WindowManagement` separates client requests
from confirmed state and window catalog metadata. Java and the native embedding
API expose named commands; overloaded numeric fields exist only in the private
native wire format. Command arguments use native stack values, and the connection
queue remains the single writer. Callbacks publish metadata changes, not frames.
`HostedFullscreen` owns ordinary Android
immersive presentation, and `BuiltInWindowRegistry.ImmersiveSource` supplies
explicit local intent to the existing Desktop reconciler independently of
firmware insets-observation support. Only Desktop-owned tasks participate in
that reconciliation; no host opens a Desktop or directly manipulates task areas.
Both protocols publish client fullscreen requests through the same host contract;
native acknowledgement and close semantics remain protocol-specific.

`HostedUiScale` selects a shared X11/Wayland application scale from Android
density and current window metrics. The fractional ratio density/160 is bounded
to 1-8 and to a minimum logical offer of 600 on the short side and 800 on the long
side. Stable system-bar/cutout insets are excluded; IME, rendered buffers and client
size constraints are not inputs. This is an initial toolkit policy, independent
of aspect-fit presentation. `HostedUiScale.adjust` applies the shared 50-200%
launcher preference after this automatic calculation. Wayland uses the adjusted
result for output scale, client limits and child placement. Integer toolkit/buffer scales are selected at each protocol
boundary without replacing the logical scale. Integrated shell surfaces retain the layout scope's scale.
`X11Density` selects one scale owner among a session's Android hosts and converts
the adjusted scale to X11 DPI at 96 per unit. Activity configuration and focus callbacks update it;
there is no display/task polling. `GraphicalPresentationPreferences` uses profile-private
storage keyed by executor identity and desktop-entry path (Termux package or
captured Shell service UID), without Desktop's state-store prerequisite. Start,
the session manager and `graphics.set_scale` use the same store and propagate
changes to matching live X11 and Wayland sessions. An empty launcher identity
keeps the override local to that ad-hoc session. The manager does not become a
host density owner when changing the preference.
The fork owns XSettings serialization, selection
lifetime and RandR publication on the X server thread. Whole Linux desktops keep
their own toolkit settings manager; Android focus/topology is unchanged.

## Privileged Service Runtime

### Startup Selection

`IntegrationPackage` reads ordered, semicolon-separated Shizuku manager and
Termux package lists once at application startup. It selects the first installed
package in each list. Settings can save a new list or restore its defaults.
These bootstrap preferences live in app-private storage and
are readable before the privileged service connects, independently of shell-backed Desktop state.
Changes take effect in the next process, without reconciling active services or
terminals. **Exit MagicDesk** always ends the process after normal Desktop and
shared-service cleanup; reopening applies all saved startup settings. It does
not depend on which setting changed. UI, automation and command providers share
this selection. Diagnostics
exposes the selected package and configured list. Selection is not persisted
separately and does not change while the process is running. There is no runtime
failover when the selected provider is stopped or denies authorization.

The Shizuku manager package selects discovery and manager UI, not the Binder
endpoint. A compatible authorized server can be ready without that manager
installed. The normal API/UserService and UID checks remain authoritative.

### Identity And Runtime Access

`ShellBackend` selects the process-start transport, independently from the
`RuntimeLimits` access ceiling (Root, Shell or App only). Limits also independently
disable Termux integration or managed Desktop, without hiding MCP commands or
promoting independent services. Active values are immutable for the process;
configured values take effect after full Exit and reopen. Capability reporting
retains the distinction between policy denial and missing installation/permission.
`DisabledShellServiceLauncher` does not construct a transport in App-only mode.
`ShellServiceLauncher` owns process creation, permission UI and Binder delivery.
`ShizukuServiceLauncher` is the only production caller of the Shizuku API.
`ShellProcessLauncher` starts the same `ShellCommandService` through `su`, or
through a one-shot `ShellServiceBootstrap` when Shizuku supplies UID 0 and the
user requested UID 2000. Normal Shizuku UID 2000 binding stays direct.

The native `magicdesk_service_launcher` establishes the selected identity
before starting ART; it never changes credentials of an existing multithreaded
service. `FrameworkPrivilegedProcessApi` creates an Application context without
running `MagicDeskApplication` or HOME recovery. It acquires the app's provider
as an external process, not as an AMS-registered Activity. The one-use handoff
verifies the expected PID, UID, nonce and APK build. `ShellServiceProcess` links
the command service to the app's Binder lifetime; only an update worker can
survive replacement. No root broker remains to execute application operations
in a restricted session. See [Privilege boundaries](privilege-modes.md).

### Connection And Helper Lifetime

`ShellServiceConnection` owns a single binding attempt and publishes its actual
verified UID, not the launcher's UID. An initialization failure cannot publish
readiness or create an automatic retry loop. Cancelling a binding releases its
owner and prevents late callbacks from replacing the next service.

`ShellAccess` owns this connection and an immutable runtime snapshot.
Binder and permission events update the snapshot;
finite operations read it without repeating package, permission, version, and
UID probes. Explicit setup/diagnostic audits and command failures refresh it.
Finite operations use typed AIDL calls or bounded shell commands. Task events,
focus requests, and acknowledgements use a typed one-way AIDL callback. The
callback Binder owns one display's task-observer subscription, so client death
removes only that listener without a child `app_process` or textual protocol.

Other long-lived operations use `ParcelFileDescriptor` streams owned by an APK
Binder token:

- input routing and shortcut service ownership;
- mouse forwarding;
- phone-display power ownership.

The UserService links every long-lived helper to its APK owner token. Input
helpers block on real descriptor activity; Binder death, EOF, or explicit close
initiates bounded graceful cleanup before process termination. They do not use
periodic keepalives. `PhoneDisplayGuard` retains its one-second heartbeat only
for fail-open display restoration if ownership is lost. Application working-state
renewal belongs to the shared background-work owner described below.

### Background Work

`ShellBackgroundWork` owns explicit `IBackgroundWorkLease` lifetimes independently
of HOME, Desktop and virtual-display allocation. Automation leases have a service-
enforced deadline; the phone-power guard uses a Binder-owned lifetime. A CPU wake
lock retains scheduled cleanup, and an optional borrowed virtual-display power
reference shares the existing presentation power owner without attaching a Viewer.
Closing the lease, its display or its Binder owner releases all resources. A
parked display alone owns no work lease. `AutomationAwakeLease` adapts the same
bounded service to `device.keep_awake(displayId=...)`; ordinary phone mode keeps
its public-API, no-shell path.

`BackgroundWorkProtection` combines overlapping claims into one transient session
per UID. It includes MagicDesk explicitly, without assuming HOME exemption, and
retains observed application UIDs for the work interval. `FrameworkTaskObservationSource`
provides event-only application ownership subscriptions with an initial typed UID
snapshot; this path does not initialize Desktop's sampler or window organizer.
There is no periodic task query. `PlatformBackgroundWork` supplies optional firmware
hints; the Android baseline adds none. Nubia's focused component renews its transient
working state once per second through `RuntimeDelays.WORKING_STATE_REFRESH`, only
while claims exist. Deadline callbacks are lifetime timers, not readiness waits.

### PTY Transports And Shell

Every retained Console session owns a lifecycle-bound `TerminalTransport`, one
native PTY relay, and one interactive shell. `ShellPtyHandle` hosts
the packaged mksh through the UserService and binds its stream to the APK
owner's Binder token. `TermuxPtyTransport` asks Termux's documented
`RUN_COMMAND` service to host the same relay under the Termux UID and connects
it to the session through an authenticated loopback stream. Both transports
create a session leader and controlling terminal, forward terminal bytes,
apply `TIOCSWINSZ`, expose the shell PID, and resolve `/proc/<pid>/cwd` within
the process's own security domain. Ending the session, running `exit`, service
death, or stream failure ends that PTY and its UNIX-session jobs, including
foreground and background process groups. A failed transport is
discarded rather than silently changing privilege or execution backend.

`AndroidShell` resolves `libmagicdesk_mksh.so` from the installed APK's native
directory. `ShellExecutionEnvironment` uses it for PTYs, non-PTY command streams,
background Desktop Entries and one-shot commands. Helpers receive that path
explicitly; they do not choose a shell from ambient environment variables.
The selected identity's tool directory provides `sh` and `mksh` symlinks to this
one executable. Generated CLI scripts have its absolute shebang. Text executables
without a shebang re-enter mksh through `/proc/self/exe`; explicit interpreter
paths remain authoritative. No `/system/bin/sh` fallback is used.
`TerminalShellIntegration` configures mksh's native persistent history in the
selected identity's state directory, without exporting history variables to
child shells. Command storage, locking and compaction belong to mksh, not the
PTY relay or terminal emulator. The APK includes an unmodified, pinned upstream
build with history enabled; see [Android shell](../native/android-shell/README.md).
Noninteractive commands do not load the interactive rc or record command history.
No shared-startup capability probe, Termux shell replacement or guest-shell
override is introduced; an unavailable interpreter fails the requested command.

The relay handshake publishes a `PtyEndpoint` (PID, process start ticks and slave
device) captured at PTY creation. The registry exposes that identity only while
the session remains ready and live. `PtyPeerOutput` performs bounded, one-shot
slave writes using the same native helper under the PTY's existing execution
identity. `TmuxPanes` adds on-demand default-server discovery and verifies pane
membership before using that same writer. Termux relays and peer writes execute
the helper directly from the installed APK's native directory under the Termux
UID. RUN_COMMAND carries its path and launch arguments, never the binary in
Intent extras; helper growth therefore does not consume Binder's transaction
budget. No persistent output
worker, alternate renderer path, privilege fallback or replay mechanism exists.
`DesktopAutomationPtyOutput` adapts both paths for the shared MCP/CLI executor;
delivery receipts distinguish complete, partial and unconfirmed writes.

Termux service resolution is restricted to the selected package and the
standard `com.termux.RUN_COMMAND` action. A unique exported service must retain
the command/result protocol and a permission MagicDesk supports. A renamed
custom permission is reported as incompatible, not bypassed. Shell paths use
the protocol's `$PREFIX/` expansion; shell startup uses Termux's own
`HOME`/`PREFIX` environment. Absolute initial directories use the selected
application's Android data directory and Termux's `files/home` layout, including
the current Android user.

The native relay owns both directions in one nonblocking poll loop, with
bounded input/output buffers and incremental control-frame decoding. A partial
frame or backpressure in one direction cannot block the other direction or
shutdown. Process signals wake the same poll owner; cleanup has a bounded
HUP-to-kill sequence for the owned UNIX session, not a separate worker thread.
The shell leader remains unreaped until cleanup finishes, reserving its session
ID. Session membership is inspected only during teardown; an early shell exit
does not leave HUP-ignoring jobs alive. Both graceful and forced shutdown wait
for every owned member to stop executing before reporting successful cleanup;
delivery of SIGKILL or exit of the leader alone is not that acknowledgement.
Independently sessionized processes, including tmux servers, remain outside
this ownership boundary.

### Console Sessions And Input

`ConsoleTerminalSession` owns transport and terminal state independently of a window.
The registry releases sessions on explicit termination, shell EOF or runtime exit.
Closing Desktop does not terminate these independent sessions. Process death or
APK replacement is not a terminal persistence mechanism; tmux inside Termux is
available when longer-lived processes are required. An attach request for an
expired session fails instead of silently creating another shell.
Its PTY-to-UI output buffer is bounded; a busy UI pauses the reader on a drain
event rather than dropping terminal bytes or growing an unbounded queue. Closing
the session releases that wait. Metadata requests use the same session writer,
with `TerminalRequestScope` completing every pending response when the session
closes, even if executor teardown discards its queued work. No additional thread
or periodic query is involved. A resize received while the transport opens is
applied to the PTY before sending input queued during startup.
The registry delegates its optional Activity/View binding to
`TerminalWindowAttachment`. Replacement revokes the previous surface and leaves
its window in an empty selection state; a stale Activity cannot detach a
replacement. Attachment generations remain observable by automation. Notification throttling and cleanup
belong to `TerminalNotifications.Session`, including while the PTY has no window.
Neither component owns or replaces the terminal transport.
The local [`terminal-emulator`](../terminal-emulator/README.md) module, based on
Termux v0.118.3, parses escape sequences and models the main screen, alternate
screen, cursor, colors, and scrollback. It owns terminal semantics and their
upstream regression tests, independently of transport, windows, and Desktop.
The terminal does not use Termux app session, JNI, or rendering code. Its own
`ConsoleTerminalView` adapts Android gestures, mouse reporting, layout and frame
scheduling. `ConsoleTerminalInputConnection` owns Android IME composition with
the View's attachment validity check. `TerminalViewport` owns measured grid
dimensions, cell transforms, selection and local history, independently of Android.
Local scrollback has a row anchor plus a pixel offset, shared by touch dragging,
inertial scrolling, high-resolution wheel input, hit testing, and selection handles.
The `MagicDeskTerminalRenderer` consumes a `TerminalFrame`, not a live emulator.
A frame borrows only visible cell rows for synchronous drawing on the emulator's
owning thread, and freezes palette, cursor and immutable placement metadata once
for all render passes. It is not an asynchronous snapshot or a second transcript.
Moving rows are explicit presentation snapshots; image rasters are always shared.
The renderer clips the viewport and draws both partial boundary rows with the
same translation for text and graphics. New output preserves the history anchor;
returning to live output or switching buffers resets the offset. Application-owned
scrolling still receives discrete mouse-wheel or key input. Separately, the
emulator's attached `ScrollListener` publishes explicit vertical region edits
before mutation. The application-layer `TerminalScrollRegion` reconciles moving
cells and fixed repaints without Android drawing or a clock. It does not change
the parser's state. `TerminalScrollAnimation` owns fractional presentation and a
region-height-bounded deque of outgoing-row Pictures. Its `TerminalScrollMotion`
preserves velocity when more committed scroll distance arrives and follows the
target with exact critically damped motion, independently of rendering cadence.
Its response derives from the view's scroll-command cadence, seeded by the
display frame interval, rather than a fixed per-packet duration. Gesture completion
ends cadence tracking and lets the outstanding distance catch up promptly.
It stops without overshoot or predicted output. Presentation never delays parsing,
changes the grid, infers scrolling from repaints, or identifies applications.
Buffer writes are observed separately from the atomic scroll transport/blanking.
A region-bounded presentation grid transports text independently from in-place
repaints. Incoming cells and text restored at displaced repaint positions update
this moving grid; redundant writes leave its motion unchanged. Available writes
are reconciled before drawing or the next transport, not at PTY packet boundaries.
A region-bounded viewport snapshot also identifies complete rows restored in
place, keeping adjacent fixed rows together. Frames without new writes do not
repeat this reconciliation. Only actual
in-place differences draw at fixed cell positions, without a swept-path mask.
Cell snapshots do not own live command markers or copy image rasters. The grid
and its freshness/origin metadata are discarded with the animation. Freshness
describes only cells blanked by the current edit, never previously blank cells
transported from earlier edits during a fling.
All content outside the declared region stays fixed. Ordinary streaming output
remains immediate. Detach unregisters the listener, while buffer/geometry changes
and direct interaction discard transient presentation. Only active gestures,
scroll animations, or content changes request redraws.
The Activity delegates toolbar/layout/status presentation to
`ConsoleTerminalWindow` and explicit copy/paste, links, command history, image
exports and Files actions to `ConsoleTerminalActions`. Content actions share one
window-owned worker; closing the window rejects late work without ending the PTY.
The Activity retains launch, permission and lifecycle orchestration.

Each terminal `InputConnection` remains valid until Android closes it or the
View's session attachment changes. Another connection factory call does not
revoke the connection currently used by the IME. An attachment token prevents
late input after detach/rebind, including reattachment to the same retained PTY;
closing one connection cannot invalidate another. This lifetime is independent
of keyboard language, input method, Desktop, and terminal backend.
OSC metadata belongs to the emulator/session, not the attached Activity.
`TerminalCommandHistory` retains bounded buffer-owned boundaries; hyperlink
attributes travel with rendered cells. `TerminalNotifications` owns the Android
channel and per-session notifications, with a user-initiated Activity entry
returning through `ToolApplications`. `TerminalShellIntegration` supplies owned
startup hooks without modifying user dotfiles. See [terminal integration](terminal-integration.md).

### Console Presentation

`ConsolePreferences` stores new-window font defaults in app-private preferences;
the View owns its current sp size and the Activity saves that window value.
Pinch and Ctrl+wheel are local presentation actions, never terminal mouse input.
Display/font configuration changes and size adjustments recreate renderer metrics
using Android's `TypedValue.applyDimension`. The existing session resize path
receives changes to the grid or cell metrics, without replacing the PTY.
The resource font family supplies four real JetBrains Mono Nerd Font Mono faces;
`MagicDeskTerminalRenderer` derives one shared integer-pixel grid from them.
`TerminalCellGeometry` owns only geometric glyph presentation on that grid.
Font loading and rendering are app-layer responsibilities, not emulator, transport
or Termux configuration. Font and geometry licensing is in `THIRD_PARTY_NOTICES.md`.
Static terminal graphics follow the same session/view boundary. `SixelDecoder`
and `KittyGraphicsDecoder` decode bounded terminal input into `TerminalImage`;
`TerminalGraphics` owns images and buffer-scoped placements. Buffer operations
update placement coordinates and clips, while Kitty Unicode placeholders move
as text cells. `AndroidTerminalImages` supplies PNG decoding and one native bitmap
per raster, shared by attached views. Rendering never owns the session's image
lifetime or adds a periodic redraw loop. Graphics use the same PTY on either
backend and do not introduce a Desktop, Termux-app or file-access prerequisite.
Explicit image actions encode the selected raster once on a worker;
`GeneratedContentProvider` grants read-only access to finite-lived app-cache files.
The shared Android content gateway owns Open/Share placement. Files accepts a
single incoming content item for destination selection and its existing transactional
import, rather than giving the terminal a second file-save implementation.
The native relay has a small framed control protocol for input, resize, and
working-directory requests. The Binder transport exposes raw output from its
owned descriptor; the loopback transport frames output and metadata so one
authenticated socket remains the complete ownership boundary.

### Clipboard And Content

`AndroidClipboardGateway` is the only direct `ClipboardManager` boundary.
Console selection and terminal copy/paste callbacks, compatibility reports,
logs, paths, settings, and automation all use its typed text operations.
Termux-backed Console windows therefore share the same Android system
clipboard as shell-backed Console and ordinary Android applications; MagicDesk
does not maintain a terminal clipboard mirror. Sensitive MCP connection data
is marked for protected Android clipboard previews. Clipboard access is
request-driven outside focused guest hosts. `HostedContentExchange` subscribes through
the gateway only while its Android window has focus; no clipboard history or
polling loop is introduced. Session-origin tags prevent clipboard feedback.

`AndroidContentPayload` is the immutable content contract shared by clipboard,
Android share/view Intents, external drag-and-drop, Files, and Desktop. It
preserves bounded URI items, declared MIME types, text/HTML, sensitivity, and
origin without carrying executable clipboard Intents.
The embedded X11 content adapter reuses this contract for text, HTML, PNG and
file selections. The native runtime owns X11 selection/XDND negotiation, while
the Android host owns focus, drag gestures and provider grants. Payloads stream
through owned file descriptors; the X server opens/imports files under the
selected Termux UID rather than borrowing shell/root access. Session-private
imports and finite-lived Android exports have distinct cleanup owners. See
[X11 content exchange](x11.md#clipboard-and-drag-and-drop) for formats and bounds.
Incoming Share parsing inspects at most 64 entries from each of `ClipData`
and `EXTRA_STREAM`, retaining at most 64 distinct URIs across both. Limiting
only the final result would still allow an arbitrarily long duplicate list
to be traversed on the receiver's UI thread. The payload records truncation
when either inspection or retention is capped; clip text, HTML, and sensitivity
survive the merge. Locally produced drag URI lists are rejected before traversal
if they exceed the same publication limit.
MIME selection considers every URI: a mixed or partially unknown selection
stays `*/*`, and a clip-wide type list is never treated as the type of its
first file. `AndroidContentMimeTypes` keeps source declarations separate from
the derived `ClipDescription` and computes the Intent type once per payload.
When all URI types are unknown, a uniform source declaration can supply their
type; generated wildcard placeholders must not overwrite that declaration on
this or the next transfer. This fallback never narrows an explicitly ambiguous
source declaration. Merging Share extras retains source declarations, not generated
text/URI transport metadata. This policy is shared by clipboard, drag, and
Intent conversion and performs no provider query or background work.
**Open** accepts one URI, or a text link when no URI files are
present; multiple files cannot redirect that action to a link in their text.
`AndroidContentPayload` serializes `ClipData`; `AndroidContentIntentAdapter`
builds user-facing View/Share Intents from the same payload.
Read grants travel in both `ClipData` and Intent flags, so the selected
application receives the same content that MagicDesk classified. Clipboard
**Open** and **Share** are explicit desktop actions and launch through the
production Android integration path; content-authorized MCP exposes the same operations
without adding another executor. Dropping content on an application or its
taskbar instance uses the same payload and gateway; an existing task id is an
explicit presentation target rather than an inferred package reuse. Ordinary
state and diagnostics contain only counters and metadata.

`DesktopContentReceiverActivity` is the exported **Save to MagicDesk Desktop**
share target. Because an exported Activity can be invoked explicitly, it asks
for user confirmation before writing anything. Accepted URI content is copied
while the incoming grant is alive; accepted plain text becomes a UTF-8 desktop
file. It does not retain incoming payloads, watch the clipboard, or start an
idle service.

### Foreground Processes And Tmux

`TerminalTransport` also has an optional foreground-process capability. The
Termux relay resolves the PTY foreground process group with `tcgetpgrp()` and
reports a bounded executable name from its own `/proc` security domain. Console
refreshes this metadata after terminal interaction and when Open tasks is shown;
continuous output is throttled to avoid turning metadata into a polling load.
Open tasks combines the executable with the terminal's OSC title, while shell
names retain the `Console` or `Termux Console` identity. Missing metadata falls
back to the static application label and never affects the PTY byte stream.

Some vendor task managers can grant `RUN_COMMAND` while separately blocking
Termux's foreground service through an Auto-launch policy. That refusal is a
transport failure, not an empty terminal: Console keeps the selected Termux
backend, renders actionable guidance in the terminal, and records the original
firmware exception in compatibility diagnostics. It never substitutes the
shell UserService because that would silently change the command environment
and privilege boundary.

`TmuxSessionProvider` is an optional layer above `TermuxPtyTransport`, not a
third transport. An explicit toolbar or MCP request invokes one bounded
`RUN_COMMAND` query under the Termux UID. The typed parser distinguishes an
absent tmux executable from an empty tmux server, validates session ids and
names, and constructs quoted attach or create commands. A selected session is
then opened through the ordinary Termux Console path with explicit tmux identity.
`TerminalSessions` merges local terminals and server sessions using live tmux
client PIDs; `TerminalSessionsDialog` is shared by the control panel's header,
Start's Terminal sessions entry, its context menu and both console toolbars. The
header and context menu target their own screen; Start's application list retains
its selected launch destination. Opening the picker does not allocate a PTY. An empty picker
still offers creation. A console switches the single `TerminalWindowAttachment`
in its existing task; transfer releases the former host into an empty selection state.
Recreation restores a retained identity without replaying commands or reclaiming
a session transferred to another task. PTY exit releases the binding, not the
window. Reopening a live client from outside a console presents its existing window; closing
a managed tmux window releases only that client's controlling PTY. Ordinary
terminals retain their PTY on window close. Recreation and stale Activity cleanup
cannot disconnect a replacement view. There is no session poller. The public Termux
command boundary does not transfer the PTY stream of an ordinary Termux app
session, so those sessions remain owned by the Termux UI.

`TermuxCommandResultReceiver` owns each result callback, timeout, and one-shot
`PendingIntent` as one registration. Completion, cancellation, and timeout all
remove that registration and cancel its remaining resources. The callback
Intent uses a unique data URI, so a token retained by Termux across MagicDesk
process death cannot match a later request. `DesktopExecSessionTracker` records
each execution separately, including repeated launches of the same command;
late start acknowledgements cannot reopen a completed diagnostic session.

### Command Environment

`ShellExecutionEnvironment` defines the common execution profile used by the
PTY relay, marker-delimited MCP shells, background shell Desktop Entries, and
one-shot shell commands. It removes inherited Termux process variables and
provides stable `HOME`, `TMPDIR`, XDG directories, Android-system `PATH`,
locale, and shell identity values. Shell `HOME` is `/data/local/tmp/magicdesk`;
root `HOME` is `/data/local/tmp/magicdesk-root/home`. XDG directories follow
the selected home. Tools, temporary files and Shroot storage remain under
UID-specific `/data/local/tmp/magicdesk-{shell,root}` runtime directories. Interactive
transports add `xterm-256color` and
true-color metadata and an owned Android-shell `ENV` startup file. Its two-line
prompt puts the current path and nonzero exit status above the short `$`/`#`
input line; prompt evaluation preserves the command's exit status. Termux's
shell configuration is independent and unchanged. Non-interactive commands use
`TERM=dumb` without that startup file. This shared
profile is the only insertion point for future Android-native command bundles.
Shell and root identities use independent top-level runtime directories so a
root-backed service session cannot leave ownership that breaks a later
shell-backed session.

`MagicDeskCli` dispatches local `download` before inspecting the automation
catalog or connecting to the inherited command channel. `DownloadCommand` owns
CLI arguments and throttled terminal progress; `HttpDownload` owns streaming,
optional SHA-256 verification and atomic destination replacement. `HttpGet`
shares connection bounds and redirect validation with `GuestOciRegistry`, whose
HTTPS-only endpoint policy, registry authentication and content-addressed cache
remain OCI-owned. Downloads run under the caller's UID without app initialization,
Desktop, Termux, MCP or guest-runtime capabilities.

### Framework Calls

`TaskStackListener` does not reliably report changes to app-requested system-bar
visibility, native freeform bounds, or organizer-child ordering. The centralized
`FrameworkTaskObservationSource` supplies these observations as described in
Framework compatibility services; no policy consumer owns an additional task
poll.

Framework commands that need hidden signatures run from the shell UserService
through `app_process` with the main APK on the class path. `hidden-api-stubs`
exists only for compilation; it is not packaged in the APK.

## Desktop Display Recording

MagicDesk resolves the active desktop's logical display to its physical display
ID and records it with Android's system `screenrecord --display-id` command.
Video capture is a platform-independent baseline and does not depend on an
internal-audio backend. The Nubia driver can additionally use the firmware's
`SYSTEM_RECORD_MODE` source `80`, the same source used by the stock ZTE screen
recorder and Game Highlights. The source is accepted by `MediaRecorder`, but
the audio HAL rejects it through `AudioRecord`; these APIs are not
interchangeable on the verified firmware.

Internal audio is an optional platform capability. The Nubia driver passively
asks the framework whether source `80` is valid and reads its diagnostic name;
this check does not construct a recorder or capture sound. In `Auto`, audio is
attempted only when the framework declares the source. The Standard Android
driver and firmware without a declared backend make `Auto` record video without
sound; the user-selected microphone remains platform-independent. A future
platform can add internal audio by implementing the same driver contract
without changing the display-recording session.

The Capture panel stores a global audio mode, resolution scale (`100%`, `75%`,
or `50%`), and H.264 bitrate (`4`-`40 Mbps`). `Auto` permits the selected
platform backend to record internal audio and falls back to video-only;
`Microphone` uses Android's standard `MediaRecorder.AudioSource.MIC`; `No
audio` never constructs an audio recorder. Native resolution omits
`screenrecord`'s `--size` option; scaled output preserves the physical display
aspect ratio and uses even dimensions for encoder compatibility. The defaults
remain `Auto`, native resolution, and `20 Mbps`.

A privileged service is an `app_process` with an Application context but no
bound `ActivityThread.AppBindData`. Android 16's `MediaRecorder(Context)` passes
`ActivityThread.currentPackageName()` into JNI, where a null value aborts the
entire process. `MediaRecorderAudioRecorder` temporarily supplies the matching
MagicDesk or `com.android.shell` application identity only while constructing
the recorder, then immediately restores the prior ActivityThread state. This
shared recorder supports both the standard microphone and platform-provided
audio sources.

When available, audio starts before video so their measured monotonic start
times can be aligned. `Auto` treats internal audio as optional and falls back
to video-only if its backend cannot start. An explicitly selected microphone
must start successfully; later stop, validation, or mux failures still preserve
the completed H.264 video rather than losing the entire recording.
The video shell wrapper also watches its UserService PID and sends `SIGINT` to
`screenrecord` if that owner disappears. Temporary tracks live under
`Movies/MagicDesk/.recording` with `.nomedia`; successful audio capture is
muxed with the video into one MP4, while video-only capture publishes the
original screenrecord file directly. Only the finished file is indexed. The
video start time is measured when the encoder first writes output rather than
when the process is forked, avoiding a firmware-observed startup error of
roughly 100 ms.

## Desktop Entry Execution

Persistent desktop UI configuration has one source of truth:
`/storage/emulated/0/Desktop/.magicdesk/desktop.json`. The shell UserService
validates and atomically replaces this bounded JSON file; the same event-driven
folder observer reloads deliberate external edits without polling. The hidden
metadata directory is excluded from the desktop file model and cannot be
opened, renamed, or deleted through ordinary desktop-entry operations. Recent
history, active tasks, diagnostics, and setup/recovery state remain private
runtime state. Android widget bindings remain system-managed and scoped to the
installed app and Android user. Global layout data may contain opaque placement
keys for currently bound widgets, but those keys cannot bind or instantiate a
widget. Application and folder shortcuts are not embedded in this JSON state.
They are bounded freedesktop Desktop Entry files parsed by `DesktopEntryFile`
in any directory shown by built-in Files. Encoders, the parser, and stream I/O
share a 64 KiB UTF-8 byte limit, including escaping and metadata. Oversized
entries are rejected before creating a destination file. `Type=Link` holds a
local folder URL or an HTTP(S) URL; web addresses must fit their length limit
after ASCII encoding as well, so normalization remains valid on reread.
`DesktopEntry` owns display-name validation for every entry type and encoder;
empty or NUL-containing names are rejected before persistence. Display names
are not filesystem paths: filename sanitization remains in `DesktopEntryFile`.
`Type=Application` stores standard `Name`, `Icon`, and `Exec` fields plus one
typed Android descriptor and launch-mode metadata in `X-MagicDesk-*` keys. A
generic Android launch uses a full Intent URI, preserving extras, categories,
flags, actions, and explicit components. An application action instead stores
only `X-MagicDesk-AppShortcut`; it is resolved against Android's current
published shortcut service when opened. An entry without an Android descriptor
executes `Exec`.

Every launch surface converts the entry into one immutable
`DesktopLaunchRequest`. `DesktopLaunchCoordinator` owns the shared sequence of
capability validation, optional Android-task preparation, and command
delegation. `DesktopSessionLaunchContext` maps that sequence onto the live
desktop's existing `AppTaskController`; `StandaloneDesktopLaunchContext` maps
the same request onto ordinary Android placement. Its caller may be an Activity
or the application context; neither automation nor background launch requires
a foreground Files or Start window. `ApplicationEntryLauncher` shares captured
destination validation, presentation defaults and dispatch between Start and
automation. Neither context reimplements
request resolution, backend selection or WMShell transition policy.

`DesktopApplicationRepository` is the single catalog adapter for executable
entries. Start consumes the already loaded Desktop files, while Open With can
load the same bounded catalog through the shell service. Both receive the same
immutable shortcut and source path and delegate it to
`DesktopLaunchCoordinator`. The command-application editor only validates a
form and writes a normal entry through `DesktopEntryFile`; it does not create a
second application registry or execution path. Its `%f`/`%F` and `MimeType`
fields consequently drive Start launches, Open With, and drag-and-drop without
surface-specific command logic.

`LinuxEnvironmentPicker` selects an installed PRoot environment, a user-owned
entry script, a named Shroot environment or an explicitly prepared guest store.
Only PRoot selection makes a dialog-scoped `proot-distro list --quiet`
request through the captured Termux endpoint. `LinuxLaunchRecipe` shares user,
working-directory and terminal/application/desktop presentation across these
adapters and builds a normal `.desktop` command, not a runtime/container registry.
Entry scripts can also use the existing shell executor without Termux. Graphical
shell recipes specify an explicit host-visible XKB directory; terminal recipes
do not require it. The same `.desktop` and Recent models carry that choice.
Custom entry scripts own guest setup, mounts and any explicit authorization;
MagicDesk neither acquires root for them nor persists passwords. `TermuxDesktopEntries`
publishes the complete file without replacing a different existing entry in
Termux's user XDG applications directory, using RUN_COMMAND rather than shell
filesystem access. The existing catalog, launch coordinator, PTY/X11 owners
and Recent storage then handle the entry. Guest working directories never become
host `Path` fields; graphical wrappers retain dynamic X11 authorization and own
their D-Bus/runtime-directory lifetime through `LinuxGraphicalEnvironment`.
Guest recipes use the distribution's standard D-Bus session policy with an
abstract listen address; ordinary Linux entry recipes retain the standard
transport. No daemon is embedded in the APK. No startup scan or Desktop prerequisite
is introduced. The catalog marks deletable user shortcuts from their storage
location, never from untrusted file metadata. Deletion uses the captured Termux
endpoint, refuses package-owned files and symbolic links, and removes matching
source/package recipes from both Recent scopes. Retained graphical sessions forget
that launch recipe without stopping their clients or server.

The [Shroot runtime](shroot.md) is a native-only component under
`native/shroot`. App-side immutable launch plans select a store without
creating a global distribution owner. Several environments can use the same
or different methods concurrently. Lazy content-addressed executable staging
and explicit child-process capability probes keep new kernel requirements out
of shared startup. A launch-owned native supervisor mediates selective seccomp
TRACE/USER_NOTIF operations. Its namespace worker owns inode metadata and explicit
sealed-image admission, accepting ordinary file notifications directly and
task-affine adapter RPC through the same engine. Guest signal handlers remain application-owned.
These executables are never loaded into ART and do not own Android windows.
`GuestEnvironmentLibrary` owns a shell-identity catalog of named writable stores,
sealed images and shared layer dependencies. `GuestOciRegistry` supplies verified
public OCI downloads through Java HTTPS; `GuestImageTool` invokes only offline
native image operations. Management runs in a short-lived Java child, while the
original CLI shell retains PTY/graphics descriptors and execs native launches.
No Termux, external downloader, Docker daemon or Desktop session is required.
Catalog mutation and native store lifetime locks protect explicit removal and GC;
ordinary execution retains the existing process-tree guardian.
Wayland connection mode belongs to the recipe, not its UID;
an inherited FD works independently of whether the selected executor is root.

`DesktopExecRunner` owns the execution-backend boundary. Android shell is the
default backend;
`X-MagicDesk-ExecBackend=termux` selects Termux explicitly. `GraphicalLaunchOptions`
selects X11 or Wayland presentation plus keyboard data, not a third executor. It
is handled by `HostedApplicationStartup` before generic command delegation.
Both protocols share recipe reuse, Recent identity and Android
placement through `GraphicalSessions`. Terminal recipes omit graphical options. Unknown backend
names invalidate the entry instead of silently running a command in the wrong
environment. `Terminal=true` opens the built-in Console with either a
UserService-backed Android shell PTY or a Termux-hosted PTY.

An explicit composite request with both an Android target and `Exec` first
prepares the normal Android task, then runs its companion command. The
coordinator does not supply implicit commands or rewrite them according to
the application's package. Ordinary application icons and generated default
shortcuts launch Android only.

`DesktopExecTemplate` expands the supported Desktop Entry file, URI, name,
icon, and source-file field codes. `DesktopLaunchArguments` remains independent
of Android UI classes; `DesktopDragLaunchArguments` is the drag-and-drop
adapter used by Desktop and Files. Each argument validates its 8192-character
path/URI limit at construction, including the escaped file URI; selection and
automation readers check the 128-item limit before materializing arguments.
Shell/Termux commands without field codes retain raw shell syntax, while expanded
values are tokenized and shell-quoted. `X-MagicDesk-ExecSyntax=argv` explicitly
retains literal argument parsing for non-graphical Linux recipes. X11 recipes always use desktop-entry
argument parsing, requiring explicit `sh -c` for shell syntax. Expansion writes directly into the bounded
4096-character command, checking inserted fields and quoting overhead as it
goes instead of building a potentially much larger intermediate argument list.
`Path` is
validated by `DesktopExecWorkingDirectory` and transported through
`DesktopExecSpec` to either Console, the shell process, or Termux. For a
one-shot shell command, directory preparation aborts the process if `cd`
fails, before any part of the user script can run in a different directory.

Backend capabilities describe background, terminal, working-directory, and
completion-result support. `DesktopExecSessionTracker` keeps only a bounded
observational state for delegated commands. It provides stable IDs and
diagnostics but does not own, kill, or recreate external Termux commands.
