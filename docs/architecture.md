# MagicDesk Architecture

This document describes the implementation boundaries behind MagicDesk's
desktop environment on Android with optional RedMagic integration. It is
intended for contributors, reviewers, and users diagnosing compatibility
problems.

## Design Principles

MagicDesk follows these constraints:

1. Android applications remain real Android tasks.
2. The firmware's `ShellTaskOrganizer` and native window decorations remain in
   control of move, resize, snap, maximize, minimize, and close.
3. Runtime system access requires an authorized Android shell UserService,
   currently bound through the official Shizuku API.
4. Device-specific operations are narrow, reversible, and checked before use.
5. Background work is event-driven where Android exposes an event source.
6. Optional kernel code stays outside the main APK.

MagicDesk does not register a competing task organizer, host applications in
surrogate activities, draw replacement captions, patch SystemUI, invoke `su`,
or require a Magisk module.

## Architecture Guardrails

Several plausible implementations conflict with RedMagic's secondary-display
stack. These constraints preserve behavior established through device testing.

### Keep physical input independent of the IME

Do not select an IME, hardcode Gboard or a project-specific keyboard, or enable
shortcuts only while a particular IME is active. Accessibility key filtering
is not a reliable display-wide contract for physical events routed through
Nubia's external desktop display.

Physical layout switching, repeat, and global shortcuts belong in the input
bridge. The user's on-screen IME remains an independent Android setting.

### Use WMShell instead of WindowReply

Nubia exposes private `ActivityClient` methods named
`toggleSwitchNormaltoHangWr`, `toggleSwitchHangtoNormalWr`,
`toggleSwitchFromFreeformWrtoFullScreen`, and
`toggleSwitchFromFullScreenToFreeformWr`. They participate in a vendor
allowlist and are not a general desktop contract for arbitrary applications.

The WMShell `DesktopTasksController` path accepts real task IDs, preserves task
identity, and supports applications outside that allowlist. Direct
ActivityTaskManager and WindowOrganizer transactions provide a bounded
same-display fallback when an individual WMShell operation is unavailable.

### Forward the external input stream, not individual events

RedMagic converts the external mouse's `BTN_RIGHT` to Android Back before an
application receives it. Shell UID 2000 cannot change the physical keymap, but
it can open external cursor devices read-only, acquire `EVIOCGRAB`, and create
a `BUS_VIRTUAL` pointer through `/dev/uinput`.

`DesktopMouseBridge` creates one stable virtual pointer for the external
desktop. When physical EventHub devices marked `CURSOR | EXTERNAL` are
present, the native helper grabs them and forwards motion, wheel, and button
state through that pointer. `BTN_RIGHT` is the deliberate exception: the
helper consumes the physical sequence and requests one display-targeted
Android secondary click, bypassing RedMagic's conversion to Back.

The keyboard bridge follows the same ownership model. Forwarding the complete
stream preserves key repeat, modifier state, hot-plug behavior, and the first
key after a layout change more reliably than synthetic one-key injection. It
creates one stable virtual keyboard per Android layout and switches the active
device only after the native stream is paused, the system layout is applied,
and the bridge is resumed. Newly connected sources are acquired only after
their keys and buttons return to a neutral state, so a wake press is never
split between the physical and virtual devices.
`DesktopInputRoutingSession` associates those virtual devices with a physical
display port for USB-C desktops or with the display unique ID for wireless and
virtual desktops. Only the USB-C path enables Nubia's Console-specific input
hooks.

Nubia's Touch Panel reads `app_mirror_displayid`, so it cannot target a
Miracast display without corrupting projection state. MagicDesk therefore uses
one phone-side `MagicDeskTouchpadActivity` for every external transport. Touch
motion is converted from a stable gesture origin into an absolute cursor
position through Nubia's input service. Android `VelocityTracker` selects
bounded acceleration steps without changing the gesture origin. MagicDesk
then injects hover or drag events with the common virtual pointer's device ID;
clicks and high-resolution scrolling use the same pointer. The cursor is shown
only after its first accepted position update, which avoids a stale image at a
firmware-selected startup coordinate.

A long press remains undecided until the finger either moves or is released.
Movement starts a primary-button drag; release without movement becomes a
secondary click. Two-finger movement scrolls, while a stationary two-finger
tap also becomes a secondary click. These decisions stay in the phone UI;
display-targeted event injection stays inside the shell UserService.

The software keyboard is a separate path. An invisible phone-side
`InputConnection` receives normal IME operations, including composing text,
commits, deletion, and key events. For desktop applications those operations
are forwarded to the focused vendor `IDisplayMirrorWindow`; MagicDesk-owned
overlay fields are handled locally. The focused mirror window is captured for
one explicit keyboard session and released when the keyboard closes, so text
input does not depend on polling or changing the user's selected IME.
While an external desktop is owned, the runtime temporarily enables Android's
`show_ime_with_hard_keyboard` setting so the user can explicitly open the
phone keyboard even when a physical keyboard is connected. It remembers the
previous value and restores it on normal desktop teardown; no persistent
keyboard preference is imposed during setup.

When MagicDesk's touchpad owns phone input, the task observer removes only an
automatically launched `cn.nubia.keymapcenter` `MirrorInputActivity` and then
reclaims the existing MagicDesk panel. It never disables, suspends, or
force-stops the vendor package, so the stock Touch Panel remains available
outside that session and devices without the component continue normally.

### Keep vendor input APIs behind a capability boundary

MagicDesk does not package or link a Nubia binary library. The vendor surface
used for desktop input consists of private Binder methods added to framework
interfaces on RedMagic firmware:

- `IInputManager.getMousePosition`, `setMousePosition`, and `sendMouseCmd`;
- `IDisplayManager.noteMirrorInputPanelStatus` and `getFocusMirrorWindow`;
- `IDisplayMirrorWindow` composing, text, deletion, and key dispatch methods;
- the wired-only `dumpsys display dmctrl inputSource` control.

These signatures are resolved reflectively inside the shell UserService and
are never exposed as a generic command surface. Diagnostics and the self-test
inspect the absolute-pointer, mirror-panel, and mirror-text signatures without
invoking them. The report also retains the last mirror-text runtime result from
an explicit keyboard session. No focused projected window is reported as not
tested rather than as missing firmware support. A missing optional package or
method disables the corresponding operation rather than changing unrelated
device state. In contrast,
`libmagicdesk_keyboard_bridge.so` and `libmagicdesk_uinput_bridge.so` are
MagicDesk-owned native helpers compiled from repository C sources by every
local and CI build.

### Do not draw replacement application captions

An application overlay cannot share a task's SurfaceControl leash or transition
atomically with WMShell. A separately drawn caption trails live movement,
maintains a different Z-order, and can leave controls above the wrong window.

MagicDesk instead keeps native WMShell captions visible by controlling Nubia's
external-layer privacy filter. MagicDesk overlays are reserved for shell-owned
UI such as the taskbar, Start menu, and notification center.

### Do not recreate application tasks through display 0

Do not use the phone display as a window-mode trampoline, force-stop a target
application, or add guessed sleeps to refresh fullscreen geometry. Those paths
can destroy an Activity and its user session. Use same-display transactions
and the client-preserving refresh described in
[Fullscreen transitions](fullscreen-transitions.md).

## Modules

| Component | Path or package | Responsibility |
| --- | --- | --- |
| Main application | `io.github.mekhontsev.magicdesk` | Phone control, desktop shell, taskbar, setup, diagnostics, and runtime service |
| Hidden API stubs | `hidden-api-stubs/` | Compile-time signatures only; never packaged |
| Mouse helper | `native/magicdesk_uinput_bridge.c` | Binder-owned external-to-virtual pointer forwarding |
| Keyboard helper | `native/magicdesk_keyboard_bridge.c` | Binder-owned keyboard forwarding and shortcut interception |
| Kernel Fixes add-on | `io.github.mekhontsev.magicdesk.kernel` | Independent, manually launched, firmware-specific root fixes |

The main APK contains no `.ko`, kernel loader, root command path, or reference
to the add-on package. The two applications share a repository but have no
runtime integration and are not distributed through the same release path.

## Main Application Boundaries

### User-facing lifecycle

- `ControlActivity` and `PhoneControlPanelController` provide the compact phone
  control surface. They do not create taskbar, wallpaper, or app-catalog UI.
- `FileManagerActivity` is an ordinary resizable desktop task. Its view owns
  navigation and selection only; `FileManagerOperationController` owns
  lifecycle-bound remote operations and `FileManagerImportController` owns
  incoming Android URI drops. It has no vendor dependency.
- `CommandConsoleActivity` is an ordinary multi-instance desktop task. Every
  window owns one `ConsoleShellSession` and one persistent shell stream; no
  process-global terminal state is shared between Console windows.
- `SettingsActivity`, `SettingsView`, and `MagicDeskSettings` own persistent
  user-selected desktop behavior. They are separate from the transient System
  panel, which remains a quick control surface for the active session. Settings
  also provides the stable entry points for device setup, diagnostics, and
  About, keeping the phone control surface focused on session actions. The
  activity is exported only behind `MANAGE_ACTIVITY_TASKS`, allowing the shell
  launch backend to create its desktop task without exposing it to regular
  applications. `BuiltInDesktopAppCatalog` is the single allowlist that
  separates user-facing MagicDesk tasks such as Files and Settings from shell
  infrastructure. It also records whether an internal window can have multiple
  tasks, appear in the launcher or taskbar pins, and share package-level window
  state. Settings is a singleton reusable task with compact centered default
  bounds. A single constrained, scrollable `SettingsView` uses the same dense
  visual language on phone and desktop. The phone opens it normally, while the
  desktop task controller launches the same Activity in a dedicated reusable
  freeform task.
- `DesktopActivity` is the concrete desktop Activity.
  `DesktopShellActivity` composes controllers and forwards Android callbacks;
  it does not own every feature directly.
- `DeviceSetupActivity`, `DeviceSetupManager`, and `DeviceSetupView` own the
  one-time platform audit and provisioning flow.
- `ConsoleSeedActivity` is an exported but `MANAGE_ACTIVITY_TASKS`-protected
  transient visual seed used only when
  Nubia ignores a Mirror-to-desktop command while Android Home is the sole
  foreground task. It never appears in Recents and is removed after the real
  desktop host task is ready.
- `MagicDeskRuntimeService` composes the persistent notification and
  process-level runtime without duplicating subsystem state.
  `RuntimeDesktopSessionCoordinator` owns desktop-display identity, session
  removal, and phone-Home recovery. It consumes one immutable
  `DesktopSessionSnapshot` per decision, so the host display and the prepared
  display target cannot come from different lifecycle transitions.
  `RuntimeDesktopInputCoordinator` composes
  input-device discovery, keyboard and mouse bridges, desktop text routing,
  and software-keyboard policy. The optional non-reference-counted partial
  wake lock is held only while both its setting and a MagicDesk desktop
  session are active. It is released by the same service lifecycle. There is
  no boot receiver; the user starts MagicDesk manually. The notification body
  is a stable display-0 entry
  point to Phone Control Panel; its separate touchpad action opens the
  phone-side input panel. Desktop Show/Restore remains a taskbar and `Win+D`
  command rather than a state-dependent notification action.

### Desktop UI

- `StartMenuController`, `TaskbarController`, `TaskOverviewController`, and
  `NotificationCenterController` own the persistent desktop controls.
- `DesktopWorkspaceController` composes shortcuts, the fixed Android
  `Desktop` directory, and Android widgets on one `DesktopGridLayout` surface.
- `DesktopFolderController` owns asynchronous desktop-file operations and the
  lifecycle of an event-driven observer. `ShellDesktopDirectory` constrains
  typed UserService operations to `/storage/emulated/0/Desktop` and owns its
  `FileObserver`. `DesktopWidgetController` owns the process-wide
  `AppWidgetHost` lifecycle and widget binding/configuration.
- `ShellFileSystem` is the separate, general filesystem boundary used by the
  built-in Files task. It intentionally accepts any absolute path available to
  the connected UserService identity; this broader contract is not reused by
  desktop metadata or automatic background work.
- `DesktopStateStore` is the single typed model for persistent desktop content,
  taskbar pins, global layout, application window state, settings, and display
  profiles.
  `DesktopContentStore`, `DesktopLayoutStore`, `AppWindowStateStore`,
  `DesktopPreferences`, and `DisplayProfileStore` are narrow domain facades over
  that model. `DesktopPlacementEngine` is the platform-independent collision
  and reflow policy.
- `OverlayPanelController` provides consistent toggle, dismissal, placement,
  and display-scoped overlay behavior.
- `DesktopInputController` handles shell UI input and delegates global physical
  shortcuts to the keyboard bridge.
- `DesktopRuntimeBridge` is the weak-reference, main-thread boundary through
  which services reach the active desktop. Host registration and display
  target changes are serialized into one immutable `DesktopSessionSnapshot`;
  the target may intentionally outlive an Activity during configuration
  recreation or external-display teardown.
- `DesktopLayoutController` owns WindowInsets, viewport, and taskbar geometry.
- `DesktopTaskSnapshotController` serializes task refresh generations and
  filters the taskbar model.

### Tasks and windows

- `TaskRepository` reads exact tasks and performs narrow shell operations.
- `DesktopTaskWatcher` owns the application-side typed task-observer callback
  and immediate focus acknowledgements.
- `ShellTaskObserverManager` owns one Binder-scoped observer session inside the
  shell UserService. `ShellTaskObserver` registers the framework listener, and
  `ShellTaskStateMonitor` isolates the supplemental bounds/immersive polling.
- `ShellDesktopFocusController` handles a Nubia mirror-display defect where
  task focus changes but the InputDispatcher window remains stale. It reports
  only confirmed mismatches. The UI process then relayouts the existing,
  non-focusable desktop host across a committed frame, which makes WMS
  recompute its focused window without moving tasks or synthesizing input.
- `ShellFreeformTaskCleanup` remembers freeform application tasks observed
  during the active desktop session. If one disappears, it verifies that no
  live task remains and removes only a Recents entry with the same task ID,
  package, and display. This prevents Nubia Quickstep from crashing while
  binding a stale `DesktopTaskView` without persistent recovery state or
  changes to unrelated Recents entries.
- `DesktopTaskController` orchestrates native task transitions.
- `DesktopTaskParkingController` snapshots live managed tasks when an external
  desktop is closed, parks them on display 0 as fullscreen tasks, and restores
  only the same still-live task IDs when a later desktop host becomes ready.
  It preserves each task's desktop mode, relative bounds, visibility, and
  stacking order without relaunching tasks that Android or the user closed.
- `ShellExternalTaskMigrationGuard` intercepts launcher requests for a task
  hosted on an external desktop. It also observes already completed system
  moves, including `Alt+Tab`, and scans display 0 when protection starts and
  after task-stack changes. Every observed freeform task is normalized while
  an external session is active. This invariant applies to
  MagicDesk and third-party tasks alike, so Nubia Quickstep never receives
  phone-side freeform state from those transitions.
- `DesktopWindowTransitionController` owns shortcut and immersive transitions.
- `DesktopTaskStateStore` persists freeform bounds and visible Z-order.
- `NativeWindowBoundsController` calculates snap, maximize, and restore bounds.
- `DesktopPhoneUiReconciler` repairs Nubia launcher state after display changes.
- `AppTaskController`, `WorkspaceAppController`, and `AltTabController`
  coordinate task actions, Show Desktop, restoration, and exact-task
  switching. `WindowedAppLauncher` delegates task placement to a short-lived
  shell command without retaining another Activity.

### Platform services

MagicDesk ships one main APK from one codebase. New device support belongs in
runtime capability probes or a focused platform-driver implementation, while
shared desktop, task, window, and input behavior remains platform-independent.
Do not introduce per-model build variants or forks for differences that can be
isolated behind these boundaries.

- `PlatformDrivers` selects one firmware platform for the process from an
  immutable `PlatformDevice` identity. `PlatformDriver` exposes only existing
  variation points. `PlatformWindowingDriver` owns provisioning properties;
  `PlatformProjectionDriver` owns projection state, output modes, and caption
  transport; `PlatformPhoneUiDriver` owns phone-screen controls, input-panel
  guards, launcher reconciliation, and local-navigation policy;
  `PlatformPointerDriver` owns optional absolute-pointer integration;
  `PlatformInputRoutingDriver` owns firmware hooks layered over Android's
  standard input-device display associations; `PlatformTextInputDriver` owns
  optional projected-window IME forwarding; and
  `PlatformDiagnostics` contributes only the probes for the selected platform.
- Implementations live in `platform.android` and `platform.nubia`. Shared
  runtime code does not import either implementation; `PlatformDrivers` is the
  single composition point. ZTE-branded devices are not assumed to expose
  Nubia services and use the standard Android driver unless a dedicated,
  verified platform implementation is added.
- `NubiaPlatformDriver` composes the Nubia/REDMAGIC implementations of those
  contracts and supplies the firmware's additional exported launch targets
  and hardware runtime. Common projection, input, phone-UI, setup, and
  diagnostics code does not select Nubia services or settings through feature
  booleans. Hardware controls remain an explicit optional platform capability.
  `GenericAndroidPlatformDriver` provides the Android 15 baseline: phone,
  simulated, and direct sessions on already connected secondary displays,
  using the two standard freeform/resizable settings. It does not own the
  system projection transport, and its phone-UI, absolute-pointer, output-mode,
  and hardware integrations fail closed. Its diagnostics omit vendor probes.
- Platform and display are independent axes. A platform declares which
  display kinds it supports, while the display driver owns the lifecycle of
  one session type. Do not create platform-by-display combination classes.
- `DesktopDisplayTarget` is the immutable identity of the active display
  environment. `DesktopRuntimeBridge` retains that target as one value so a
  display ID and its transport cannot become separate, stale state.
- `DesktopDisplayDriver` has four implementations: phone, wired, wireless,
  and simulated. A driver owns environment-specific start/close behavior,
  launch-area policy, phone-screen and touchpad availability, and display
  removal semantics. Shared task, window, input, and desktop UI code remains
  transport-independent.
- `DesktopDisplayDrivers` is the only registry for resolving those drivers.
  `ConsoleModeSwitcher` serializes public session transitions and delegates
  the selected target to the registry.
- `ConsoleSessionController` owns the optional RedMagic wired Console
  activation path. Standard Android displays bypass it and enter the common
  desktop session directly.
- `ConsoleDisplayController` discovers dynamic display IDs and fixes geometry.
- `KeyboardShortcutWatcher`, `DesktopMouseBridge`, and
  `HardwareKeyboardLayoutController` own physical input policy.
- `PhoneTouchpadController` starts and repairs the phone touchpad when the
  selected platform provides absolute pointer positioning.
- `RedmagicHardwareController` owns capability probing, stock fan/pump policy,
  monitoring, and baseline restoration.
- `DesktopNotificationListenerService` owns Android notification-listener state;
  `DesktopNotificationMapper` isolates framework-to-UI conversion.

Repositories perform package, task, and document queries. View controllers do
not construct arbitrary shell commands. Platform controllers do not construct
desktop panels. Keep this split when adding vendor-specific behavior.

## Shell UserService Runtime

Shizuku is the current Binder transport, while Android shell UID 2000 or root
UID 0 is the capability identity. `DeviceSetupManager` accepts the connection
after the bound
`ShizukuCommandService` reports Android shell UID 2000 or root UID 0. Both use
the same service, commands, and feature set; there is no separate root, basic,
automatic, or fallback runtime branch.

`ShellAccess` owns the official UserService connection and an immutable
runtime snapshot. Shizuku Binder and permission events update the snapshot;
finite operations read it without repeating package, permission, version, and
UID probes. Explicit setup/diagnostic audits and command failures refresh it.
Finite operations use typed AIDL calls or bounded shell commands. Task events,
focus requests, and acknowledgements use a typed one-way AIDL callback. The
callback Binder owns the single task-observer session, so client death removes
the framework listener without a child `app_process` or textual protocol.

Other long-lived operations use `ParcelFileDescriptor` streams owned by an APK
Binder token:

- keyboard forwarding and shortcut events;
- mouse forwarding;
- phone-display power ownership.

The UserService links every long-lived helper to its APK owner token. Input
helpers block on real descriptor activity; Binder death, EOF, or explicit close
initiates bounded graceful cleanup before process termination. They do not use
periodic keepalives. `PhoneDisplayGuard` is the deliberate exception: its
one-second heartbeat refreshes RedMagic's transient `cfreezer` state and
provides fail-open display restoration if ownership is lost.

Every built-in Console window owns another lifecycle-bound stream to a single
`/system/bin/sh` process. `PersistentConsoleCommandExecutor` writes commands
and private marker records to that stream; `ConsoleShellSession` parses the
markers to track the current directory and completion status without opening a
new shell for each command. Closing the window, running `exit`, Binder death,
or stream failure ends that shell only. A failed stream is discarded rather
than silently changing to a different privilege backend.

`TaskStackListener` does not reliably report changes to app-requested system-bar
visibility or native freeform bounds on the verified firmware. While a desktop
session is active, `ShellTaskStateMonitor` therefore samples at 150 ms and scans
at most 16 tasks on the selected display. It reuses one task snapshot for both
checks and sleeps indefinitely before the observer is configured or after it is
closed.

Framework commands that need hidden signatures run from the shell UserService
through `app_process` with the main APK on the class path. `hidden-api-stubs`
exists only for compilation; it is not packaged in the APK.

## Display And Session Model

A `SessionProfile` stores only a display selection policy. Runtime display IDs
are never persisted as constants.

`DesktopDisplayTarget` describes a secondary display that is already ready for
desktop content. `DesktopSessionController` then focuses or creates the same
`DesktopActivity` task for wired, wireless, and simulated targets. The
transport-specific code stops at that boundary. `ConsoleModeSwitcher` also
owns the common target-aware close operation. Local startup retains its
launcher-navigation guard, then starts the same desktop host and controllers.

- On the standard Android profile, an already connected wired or wireless
  secondary display enters `DesktopSessionController` directly. Closing the
  desktop returns its application tasks to the phone but does not disconnect
  or reconfigure the system-owned transport.
- `ConsoleSessionController` is the managed RedMagic extension: it asks the
  firmware to turn a physical USB-C output into Nubia's virtual desktop
  display, applies its output profile, and enables the wired input-routing
  path. The corresponding driver also owns the return to mirror mode.
- Starting an external desktop requires an existing Android secondary display.
  A separate **Wireless** action is exposed only when the
  selected platform driver provides a verified connection UI. The Nubia
  implementation opens SmartCast and returns to Phone Control Panel after
  Android reports the Wi-Fi display; it does not start the desktop implicitly.
  Standard Android has no assumed picker because a generic cast-settings
  activity does not guarantee Miracast display projection.
- Once Android reports a Wi-Fi display, MagicDesk passes that display ID to the
  common desktop session. It does not implement a second discovery or streaming
  stack.
- An Android overlay display is used only by explicit contributor tests. It
  exercises the standard desktop Activity and task placement without adding a
  viewer or virtual-display product mode.

When a new wired or wireless desktop task is ready and the selected platform
provides absolute pointer positioning, `PhoneTouchpadController` opens
`MagicDeskTouchpadActivity` on display 0.

The runtime asks the selected platform to expose native captions for wired and
wireless desktops. The Nubia driver applies its matching privacy filter;
standard Android and simulated displays do not modify vendor SurfaceFlinger
state. Nubia's mouse and keyboard port association remains limited to its
wired Console display because Miracast and simulated displays do not expose the
same physical display port contract. Simulated sessions deliberately exercise
the same phone IME policy, keyboard watcher, and virtual input lifecycle as a
real desktop. Virtual input remains scoped to the session and cleanup waits for
its removal before the test completes. The test inspects WMShell's caption and
resize input windows after a cross-display move, including their display ID,
frame, input channel, token, and
touchable region. Nubia's absolute-pointer API has no viewport for Android
overlay displays, so actual pointer drag remains a real-display compatibility
check.

- A normal launch on display 0 opens the phone control panel.
- **Open desktop here** uses a dedicated task excluded from Recents. The phone
  control panel remains MagicDesk's only Recents card, while the desktop uses
  the same host model on tablets, phones, and external displays.
- An external desktop is a display-sized standard multi-window activity on
  Nubia's virtual desktop display. Its position in the task stack separates
  visible windows from resumed minimized windows without replacing the phone
  launcher.
- Phone control and external desktop are separate tasks and may coexist.

Contributors can run `scripts/smoke-simulated-display.sh` from a host with ADB.
The script invokes the debug lifecycle instrumentation, so it uses the same
display driver, owned overlay lease, desktop session, window suite, and cleanup
as the built-in simulated self-test.

The built-in **Diagnostics > Run desktop self-test** runs the same bounded core
on a selected simulated, external, or phone display. A desktop session must be
closed when the test starts; mirror mode and a physically connected display are
allowed. The target owner prepares the session once, while the common core
derives bounds from the actual viewport, adopts any larger minimum window size
enforced by WMShell, and uses production session and task controllers to verify
a freeform Activity, task-local native
caption source and geometry, display-targeted application input, native caption
and resize input handles, true fullscreen, restore, minimize, and cleanup. It
then opens two independent editor fixtures, uses the native caption menu to
place them on the left and right halves, and verifies keyboard focus transfer
through both the desktop task controller and mouse input. Input assertions wait
for the current InputDispatcher focus state rather than a fixed transition
delay. The test also requests the native horizontal resize cursor and verifies
WMShell's transition trace when that firmware trace is available.

The simulated target owns its display through a Binder-owned shell stream;
closing the stream or losing its owner closes stdin, runs a shell `trap`, and
restores the prior setting. The external target selects the existing wired or
wireless transport automatically and never treats the physical display or its
unrelated tasks as test-owned. If a wired test temporarily enters desktop mode
from mirror mode, cleanup restores mirror mode. An existing Miracast transport
remains connected. The phone target uses the normal local-desktop navigation
and cleanup path. Each target closes only the MagicDesk host and test fixtures
that it created. Cleanup closes the host before removing its fixture tasks so
SystemUI can reconcile live task IDs instead of retaining references to tasks
that the test already destroyed. The phone navigation guard is released even
when task reconciliation reports a failure; the pending marker remains for a
later recovery attempt.

Phone and simulated-display cold launches create a short-lived shell-owned
`TaskDisplayArea` beside the target display's default task area. The new
Activity is launched there with its original launcher Intent, freeform mode,
and bounds, then synchronously reparented to the default area before the empty
temporary area is deleted. Production launches and self-test fixtures share
this display policy.

Nubia's wired and Miracast desktop displays use their default task area
directly. Cold launches enter that area through a WMShell launch transition
with freeform mode and bounds in their `ActivityOptions`. An existing task is
moved by a single `WindowContainerTransaction` that combines `startTask`, the
target display, freeform mode, bounds, caption state, and the visible WMShell
transition. Its first state on the external display is therefore the requested
window rather than a fullscreen intermediate state. Moving a task back to the
phone retains the ordinary fullscreen `move-stack` behavior. These paths use
explicit display IDs and never depend on display names, package exceptions, or
timing guesses.

A one-shot shell-UID `TaskStackListener` is registered only around self-test
fixture transitions. It captures the first `onTaskMovedToFront` configuration,
so the test distinguishes a true initial freeform launch from a fullscreen task
that is corrected after it becomes visible. The same probe verifies a direct
fullscreen-phone to freeform-external move. It is inactive during normal
desktop operation.

The desktop uses one `WindowMetrics`/WindowInsets viewport model on every
display. On display 0 it stays below Android system bars. A dedicated external
display normally reports zero system-bar insets and fills the panel. There is
no separate phone implementation of the desktop.

The taskbar is a display-scoped application overlay. It remains above freeform
tasks, hides for an unrelated true-fullscreen task, and returns for the desktop.
When automatic hiding is enabled, the same existing pointer-edge state machine
reveals it without introducing a second overlay or polling loop, and window
placement uses the full viewport. IME and other forced-visible policy still
take precedence. This also avoids tying shell visibility to Activity focus
callbacks.

`Win+D` gives the live desktop-host focus state precedence over the cached task
snapshot. A newly opened system activity can therefore never make a stale
"no visible app" snapshot select Restore; MagicDesk exposes the taskbar and
raises the desktop host first. Once the watcher confirms that state, the next
`Win+D` can restore the previously visible freeform stack normally.

The phone touchpad startup preference is evaluated once after a newly
created external desktop becomes ready. It does not disable manual opening or
change the existing requested/visible lifecycle used to preserve an open
panel across task transitions.

On display 0, Nubia Quickstep can crash while binding Recents to a desktop
group containing freeform tasks. Its `DesktopTaskView.bind()` creates task
containers without a title view, but `TaskView.setThumbnailOrientation()`
unconditionally asserts that the same title view is non-null. Current AOSP
Launcher3 intentionally permits that field to be absent; this is a vendor
integration defect rather than malformed task metadata.

Before the first local freeform task is created, the shell UserService uses
`IStatusBarService.disable(DISABLE_HOME | DISABLE_RECENT, token, package)` to
make the stock Home/Overview gesture unavailable for the lifetime of that
local desktop. Disabling Recents alone is insufficient on this firmware: its
gesture path can still enter Quickstep while `DISABLE_RECENT` is set, whereas
the combined state makes Quickstep treat Home itself as disabled. The service
token is automatically cleared by SystemUI if the UserService dies, and a
separate owner token releases it if the MagicDesk process dies. MagicDesk's
taskbar, Recent tab, and `Alt+Tab` remain the navigation UI. This guard is not
used on an external display.

The active shell observer removes orphaned entries as tasks disappear. Normal
local-desktop shutdown first converts remaining live freeform tasks to
fullscreen and removes verified orphaned Recents entries, then releases the
navigation guard. The shared phone recovery removes SystemUI's stranded
desktop wallpaper task and keeps the primary Home task underneath the
foreground control or Diagnostics window. Explicit Exit attempts
the same stateless reconciliation, including debris left by an older
MagicDesk process or a phone reboot, but it always completes at the user's
request. A failed reconciliation remains marked as pending and is retried by a
later runtime or **Restore defaults** operation.

Task snapshots and windowing commands issued through `TaskRepository` share a
single `TaskCommandQueue` with phone-recovery transitions. Recovery also
observes the local-session generation before every mutation. A request to open
a newer local desktop therefore cancels stale cleanup before that desktop is
launched, while ordinary taskbar operations cannot interleave with recovery
commands.

The firmware launcher is unusually destructive here: three crashes within
roughly two seconds invoke its `DataCleaner`, which deletes the launcher's
databases, preferences, and files. MagicDesk never edits launcher data.

Each desktop target has a profile keyed by its Android display identity, never
by the transient logical display ID. Profiles store only DPI, wired output
timing, and the Fill display policy. Files under
`/storage/emulated/0/Desktop`, system-managed widget bindings, taskbar pins,
desktop shortcuts, desktop-item placement, the kept workspace application,
application window state, and recent-app history are global across displays.
Desktop items and freeform windows store fixed-point relative anchors rather
than monitor pixels, so the same layout follows the user between the phone, a
tablet, and every monitor while adapting to each viewport.

Persistent desktop configuration has one source of truth:
`/storage/emulated/0/Desktop/.magicdesk/desktop.json`. The shell UserService
validates and atomically replaces this bounded JSON file; the same event-driven
folder observer reloads deliberate external edits without polling. The hidden
metadata directory is excluded from the desktop file model and cannot be
opened, renamed, or deleted through ordinary desktop-entry operations. Recent
history, active tasks, diagnostics, and setup/recovery state remain private
runtime state. Android widget bindings remain system-managed and scoped to the
installed app and Android user. Global layout data may contain opaque placement
keys for currently bound widgets, but those keys cannot bind or instantiate a
widget. Configuration can describe only package-default or explicit
package/activity launch targets with an optional action string; it cannot
supply commands, URIs, extras, categories, or Intent flags.

## Desktop Surface And Widgets

`DesktopGridLayout` is a real `ViewGroup`, not a bitmap or remote task
container. Every shortcut, file, and `AppWidgetHostView` remains an ordinary
Android view with native accessibility and input behavior. Placements use
logical cells and row/column spans rather than pixels, so DPI or resolution
changes only reflow items that no longer fit.

Widget IDs are owned by Android's `AppWidgetHost` and are therefore global to
the MagicDesk installation. MagicDesk persists their global logical placement
and cell span. Provider clicks remain native; widget movement is entered
explicitly from the context menu so drag handling cannot steal controls or
scroll gestures from the provider. Binding and optional configuration use the
system widget activities and do not depend on shell access.

The fixed Desktop surface and the general Files task use separate typed AIDL
contracts instead of interpolating filenames into shell commands.
`ShellDesktopDirectory` rejects paths outside its fixed root, symbolic-link
traversal, invalid names, and accidental overwrite. Removing an application
shortcut or widget never deletes application data, and MagicDesk does not
delete the Desktop directory or its contents during Exit or uninstall.
Desktop changes arrive through `FileObserver`; the fixed folder is not polled.

`ShellFileSystem` deliberately exposes the complete filesystem visible to the
connected UserService identity. Path validation requires normalized absolute
paths, protects the filesystem root from mutation, prevents recursive copies,
and treats symbolic links as links during copy and delete. Copy, move, and
recursive delete run on one operation executor only after an explicit user
action. Operations support cancellation and Binder-owner death; there is no
file-manager polling or idle worker loop. Name conflicts receive a numeric
suffix, so an interrupted copy never begins by deleting an existing target.
If cross-filesystem move cleanup fails after a complete copy, the destination
is retained rather than risking loss of both copies.

`FileManagerActivity` maps the selection model to the same typed operations
for toolbar commands, item context menus, and standard file-manager keyboard
shortcuts. Metadata displayed by Properties comes from the same `stat` result
used for capability identity checks. APK installation is the only package
operation: it is offered only for a selected APK, requires a confirmation that
shows the absolute path, and executes as the already-authorized UserService
identity.

`FileItemContextMenu` renders the same file/folder command model into the
desktop overlay and the Files popup. `ItemActivationPolicy` likewise owns the
shared single-click/double-click decision; selection remains local to each
surface. Desktop placement updates still use the fixed-folder API, while
general copy/move work remains in `ShellFileSystem`, so UI integration does not
widen the automatic desktop-filesystem boundary.

File rows receive ordinary Android pointer meta state. The keyboard bridge
forwards `Ctrl` and `Shift` immediately through its virtual keyboard so
modifier-click selection works consistently in Files and third-party apps;
`Alt` and `Meta` remain deferred while global shortcuts are classified. Files
and desktop files/folders use double-click to open by default, with one shared
optional single-click mode in Settings.

Files windows are separate Android tasks with independent navigation and
selection state. Their copy/cut buffer is shared only inside the MagicDesk
process and is never persisted or published to Android's text clipboard. A
completed move clears only the buffer generation from which it started, so a
new selection copied in another window cannot be discarded by an older
operation.

The current-folder name filter operates only on the already loaded page set.
It performs no recursive traversal, UserService request, polling, or idle work;
`Ctrl+F` changes only the local Files presentation.

Files opened or dragged into another application are exposed through the
non-exported `ShellFileProvider` and a process-lifetime capability URI. A grant
records the selected path and file identity; each open is performed again by
the UserService and accepted only when device and inode still match. Drag
grants are read-only, while an explicit open grants write only when the
UserService reported the file writable. The receiving application never
receives shell access, a raw privileged path, or the UserService Binder.
The in-task **Open with** dialog avoids Android ResolverActivity hiding the
desktop taskbar. It reads Android's current preferred handler. Its **Always**
action asks the shell UserService to write the same PackageManager preferred
activity record used by the system resolver; MagicDesk does not maintain a
second file-association database.

Incoming global Android URI drops are copied into the visible Files directory.
Incomplete imports are removed, conflicts gain a numeric suffix, and the
incoming drag grant is released. Cross-window import depends on the source
publishing an Android global drag session; private in-window drag gestures are
not visible to MagicDesk. For drags between MagicDesk's own Desktop and Files
windows, `FileDragPayload` keeps absolute paths in process-local state. That
typed path supports files and recursive folders without publishing privileged
paths or inventing directory content URIs; the default action is move and
holding `Ctrl` when the drag starts selects copy. Only ordinary files receive
temporary URIs for drops into other Android applications. The built-in Console
can be prefilled with the current directory. Optional Termux integration uses
Termux's documented `RUN_COMMAND` intent and permission; it is not required by
Files. The normalized directory path becomes a stable Termux shell name, and
the `no-shell-with-name` creation mode atomically selects that session or
creates it when absent. MagicDesk does not mirror Termux's session registry or
force an existing shell back to its original working directory.
Shell scripts can be handed to Console as a safely quoted initial command.
Console still requires its normal explicit Run action; opening a script from
Files never executes it automatically.

Normal application launch continues to reuse an existing task. The explicit
**New window** action instead requests `NEW_DOCUMENT | MULTIPLE_TASK` and then
tracks the exact returned task ID. Files supports this contract directly;
third-party activity launch modes remain authoritative and may reject the
request.

## External Desktop Activation

The stock RedMagic projection UI enters desktop mode through the vendor
DisplayManager extension:

```text
DisplayManager.setCmdToDisplay(1, physicalDisplayId, 0, null)
```

MagicDesk calls the same Binder method from shell UID 2000. It does not infer
state by writing `app_mirror_status` or hardcode a virtual display ID.

Nubia publishes `app_mirror_displayid` before the corresponding logical display
is visible through `cmd display`. Early input routing therefore recognizes the
configured Console target immediately, while lifecycle callers use
`waitForDesktopDisplay()` to require the stricter display-exists check. Keeping
those meanings separate prevents the native pointer route from being
misclassified during startup.

On **Start external desktop** or `Win+D`, MagicDesk:

1. resolves the current physical or virtual display;
2. creates a landscape transient seed only for the known Home-only Mirror
   state, avoiding Nubia's synchronous foreground-activity check during an
   orientation relaunch;
3. requests RedMagic desktop mode and waits for the real virtual display;
4. corrects portrait geometry and applies the display profile DPI;
5. creates or normalizes one display-sized MagicDesk multi-window host task;
6. removes the seed and focuses the desktop;
7. restores the last visible freeform window layout.

Requests are serialized and duplicate requests during transition are ignored.
With no external display, the shortcut cannot accidentally create a second
desktop on display 0.

### Output timing and fill policy

Before activation, the phone control panel reads Nubia's current and available
DisplayPort timings from `/sys/kernel/lcd_enhance/edid_modes`. It offers the
sink's native resolution and the resolution classes supported by Nubia's
desktop projection service, including every advertised refresh rate for those
resolutions. A saved timing is used only while it remains in that list;
otherwise MagicDesk chooses the highest native resolution, the highest refresh
rate at that resolution, and avoids a cinema-aspect duplicate when a normal
timing exists.

The vendor node is an optional capability rather than a desktop prerequisite.
If shell UID 2000 cannot open it, `NubiaHdmiModeController` caches that stable
firmware-level denial for the process lifetime and falls back to the modes
reported by Android `DisplayManager`. It applies a selected public mode through
`cmd display` where the firmware honors that API and clears a failed request
after a settlement timeout. Callers do not implement separate model checks or
retry a permanently denied node on every control-panel refresh.

Changing the physical timing uses Nubia's own sequence: write the selected
EDID mode, ask DisplayManager to refresh its physical displays, pulse HDMI HPD,
then wait for three stable observations of the requested mode. The physical
display id is resolved again afterward because the firmware can recreate it
during this transition. The operation runs before the virtual desktop is
requested, so no MagicDesk task is attached to a disappearing display.

Output timing and **Fill display** are stored in the same per-display profile
as DPI. **Fill display** maps to Nubia's projection-fit setting. MagicDesk
temporarily enables the vendor fit bypass while preparing the session, writes
the fit setting and, when applicable, the `1080P`, `1440P`, or `2160P`
resolution profile consumed by the projection service, and restores the
previous bypass property afterward. MagicDesk reproduces Nubia's EDID profile
selection instead of assigning these values by numeric range. A non-standard
native timing such as `1920x1200` is applied through Nubia's exact wired-mode
path after the Console display exists, so the projection service cannot replace
it with its `1080P` profile during startup. Modes below a 1080-pixel short edge
are not offered as alternatives because RedMagic desktop activation resets
them to 1080p; a lower mode remains available when it is the display's native
resolution.
Output timing changes real HDMI/DisplayPort geometry; desktop DPI remains an
independent per-monitor UI scale.

Selecting **System/native** relinquishes MagicDesk's Android display-mode
preference once, when changing away from an explicit MagicDesk timing. Later
desktop starts leave the mode selected by SmartCast or another system UI
untouched. This ownership distinction is persisted with the display profile,
so restarting MagicDesk cannot repeatedly clear a system-owned mode.

### Caption visibility

RedMagic uses separate privacy filters for wireless and wired projection:

```text
SurfaceControl.setSFOption(1100, wirelessPrivacy)
SurfaceControl.setSFOption(1102, wiredPrivacy)
```

The firmware filters external layers whose names contain `Task=`. AOSP caption
layers are named `Caption of Task=<id>`, so captions can remain interactive but
become visually black or absent. Nubia's exported projection provider reports
the current wireless and wired privacy preferences independently. During an
external session MagicDesk sets only the active transport's filter to visible,
records lifecycle ownership, and restores that transport's latest preference on
mirror, exit, transport change, or next-start recovery. Simulated displays do
not acquire this vendor state.

### Teardown

**Close desktop** first captures live managed application tasks and moves them
to display 0 in fullscreen mode. The in-memory parking record is consumed when
the next external desktop host becomes ready. Restoration matches both task ID
and package, so it never creates a replacement for a task Android closed. An
explicit **Exit MagicDesk** clears this record and closes built-in MagicDesk
windows instead.

Switching to mirroring, physical display removal, and **Exit MagicDesk** then
share the common cleanup path:

- close display-scoped overlays and stop task observation;
- stop keyboard, mouse, and phone-display streams;
- restore caption privacy and display geometry ownership;
- restore vendor hardware settings changed by MagicDesk;
- remember the owned display before Nubia can move its desktop host to display
  0, then normalize user tasks that WMShell still indexes under the removed
  wired or Miracast display;
- revive tasks that remain only in SystemUI's removed-display repository before
  normalizing them, instead of leaving an unavailable desktop entry behind;
- remove dead Recent entries retained by the current user's desktop repository
  and restore the phone control panel only after task cleanup completes;
- recover Quickstep/Home when Nubia reparents its secondary launcher to
  display 0;
- stop the foreground runtime on explicit exit.

A `DisplayManager.DisplayListener` validates actual display lifecycle instead
of trusting only Nubia's global state values.

## Window Transitions

MagicDesk operates on exact task IDs. Windowed launches and restores use native
WMShell desktop transitions when available. Snap and maximize reserve the
MagicDesk taskbar; true fullscreen does not.

The native transition probe reads WMShell help instead of branching on the
Android version. It selects Android 15's `desktopmode moveToDesktop` or Android
16's `desktopmode moveTaskToDesk` command when present, and otherwise uses the
direct `WindowContainerTransaction` path.

`AppWindowStateStore` keeps one stable record per package: the last explicit
Windowed or Fullscreen choice and, independently, the last confirmed freeform
bounds. Auto launch honors an explicit choice first and otherwise retains the
existing application-compatibility policy. The existing Shell task watcher
emits an event only when the top visible freeform bounds for a package change;
`AppWindowStateTracker` converts that event to relative bounds and coalesces a
completed move or resize into one state write. This adds no polling loop.
Bounds are resolved against the active desktop work area when a task is
launched, restored, or moved to another display.

The MagicDesk desktop itself is a visually opaque, display-sized standard
Activity in fullscreen mode. Its task is marked force-translucent through the
same `WindowContainerTransaction`, which changes WindowManager occlusion
semantics without changing the rendered surface. Covered applications remain
`RESUMED`. The fullscreen transition excludes the caption inset and refreshes
the client so Nubia's stale caption surface does not occupy the top of the
display. The host is not an Android HOME activity: HOME stops tasks placed
behind it on this firmware. `DesktopHostWindowController` performs and verifies
this normalization whenever a desktop task is created or moved.

Task order around this host is the desktop visibility boundary. Freeform tasks
above it are visible windows; tasks below it are minimized even though Android
keeps them `RESUMED`. Minimizing reorders the active task below the host and
then focuses the next visible task, or the host when no window remains. This
preserves media and background work without a timer, lifecycle spoofing, or a
custom window layer.

Application-requested immersive mode is reported by the task watcher. MagicDesk
hides its shell and lets the same Activity enter true fullscreen. Leaving
immersive mode restores the prior desktop geometry.

RedMagic can retain a stale caption inset after changing windowing mode. The
working same-display refresh captures the task-local caption source before the
transition, then synchronously replaces that exact client source with an empty
frame after fullscreen mode is established. It neither changes density nor
recreates the Activity. Details and rejected alternatives are in
[Fullscreen transitions](fullscreen-transitions.md).

## Physical Input

The keyboard helper consumes only the global MagicDesk combinations listed in
README. Ordinary key events preserve scan code, modifier state, and device
identity through the virtual external keyboard.

RedMagic disables evdev repeat on physical keyboards, then reinjects keys for
the external display with Android's `POLICY_FLAG_DISABLE_KEY_REPEAT`. While a
source is exclusively captured, the bridge temporarily enables kernel repeat
on that source and translates each repeat into a complete release/press cycle.
This keeps kernel timing without a polling thread or software timer and survives
Nubia's reinjection path. The original evdev repeat values are restored before
the source is released; the virtual keyboard does not generate a second repeat
stream.

`Ctrl+Space` queries `IInputManager` for layouts associated with enabled IME
subtypes, selects the next configured layout for every connected physical
keyboard, and updates the taskbar label. The bridge holds subsequent input only
until InputManager confirms the new layout, avoiding both a fixed delay and a
first character in the previous language.

The mouse helper forwards physical movement, wheels, and buttons. It exists
specifically because RedMagic consumes physical `BTN_RIGHT` as Back.
`Win+Backspace` remains the explicit system Back shortcut. The phone touchpad
uses Nubia's absolute mouse-position API for motion and the same virtual pointer
for clicks and scrolling. A shell-injected click queries the vendor's current
pointer position at dispatch time, so a hardware mouse and the phone touchpad
share one authoritative location. Its velocity curve matches the stock Touch
Panel and re-anchors whenever the acceleration factor changes, avoiding
accumulated relative-motion error. Physical keyboards and pointing devices may
be connected or removed while the session is active; the runtime updates their
routes without recreating the desktop or phone touchpad for keyboard-only
configuration changes.

Both helpers keep their virtual devices alive for the complete Console session.
InputManager inventory changes replace only the physical source descriptors,
so Android does not deliver keyboard/navigation configuration changes to every
foreground application. This matters for older SDL applications that cannot
safely recreate their rendering state during an input hot-plug.

A newly opened source is captured only after `EVIOCGKEY` reports a neutral
state both before and after `EVIOCGRAB`. Until then Android receives the whole
physical key or button sequence and the helper discards its duplicate copy.
This prevents a wake key from being split so that Android sees key-down while
only the virtual device receives key-up. No timing threshold is involved.

## Phone Screen And Touch Panel

RedMagic's `nubia_screen_off_tp` path lets its text-input activity wake display
0 whenever an external text field receives focus. MagicDesk instead uses the
shell DisplayManager `power-off 0` contract. A heartbeat-owned
`PhoneDisplayGuard` probes and uses the platform's matching restore operation
(`power-on` on Android 15 or `power-reset` on Android 16) after normal or
abnormal teardown.

While display 0 is off, RedMagic's independent `cfreezer` can freeze even a
foreground-service HOME process. The same heartbeat refreshes the vendor's
transient `noteCpuFreezerUidWorking` state for MagicDesk and the application
UIDs owning live tasks on the desktop display. It removes stale task entries
and clears the remaining state during restore. No persistent freezer whitelist
is installed.

`MagicDeskTouchpadActivity` is the common phone-side input panel for wired and
wireless desktops. It remains an ordinary display-0 Activity and can be opened
from the phone notification or desktop controls. Android's public
`VelocityTracker` supplies gesture speed; the vendor input service supplies
absolute cursor placement on the active desktop viewport.

Nubia's separate `HostAssistPanel` is the small handle drawn over the external
desktop. While MagicDesk owns the session it asks the existing vendor observer
to remove that panel and immediately restores the observer's original
`tp_type_for_games` value. The phone-side ProjectionIcon is intentionally left
alone because it remains useful for switching projection modes. Pointer speed
uses Android's standard `Settings.System.pointer_speed` range and is observed
for changes made outside MagicDesk.

## Desktop Display Recording

MagicDesk resolves the active desktop's logical display to its physical display
ID and records either the phone or external RedMagic output with Android's
system `screenrecord --display-id` command. Internal audio uses the firmware's
`AUDIO_SOURCE_SYSTEM_RECORD` value `80`, the same source used by the stock ZTE
screen recorder and Game Highlights. The source is accepted by
`MediaRecorder`, but the audio HAL rejects it through `AudioRecord`; these APIs
are not interchangeable on the verified firmware.

Internal-audio recording is an explicit platform capability. The Standard
Android driver leaves it disabled instead of probing source `80`; screenshots
remain available independently. A future platform can expose recording only
after supplying and verifying its own internal-audio backend.

The Capture panel stores a global resolution scale (`100%`, `75%`, or `50%`)
and H.264 bitrate (`4`-`40 Mbps`). Native resolution omits `screenrecord`'s
`--size` option; scaled output preserves the physical display aspect ratio and
uses even dimensions for encoder compatibility. The default remains native
resolution at `20 Mbps`.

A Shizuku UserService is an `app_process` with an Application context but no
bound `ActivityThread.AppBindData`. Android 16's `MediaRecorder(Context)` passes
`ActivityThread.currentPackageName()` into JNI, where a null value aborts the
entire process. `InternalAudioRecorder` temporarily supplies the matching
MagicDesk or `com.android.shell` application identity only while constructing
the recorder, then immediately restores the prior ActivityThread state. This
keeps the stock vendor audio path usable for both supported service identities.

Audio starts before video, so an unsupported audio source cannot leave an
orphan screen recorder. The video shell wrapper also watches its UserService
PID and sends `SIGINT` to `screenrecord` if that owner disappears. Temporary
tracks live under `Movies/MagicDesk/.recording` with `.nomedia`; successful
capture normalizes each track's timestamps against measured monotonic start
times, muxes H.264 and AAC into one MP4, and indexes only the finished file.
The video start time is measured when the encoder first writes output rather
than when the process is forked, avoiding a firmware-observed startup error of
roughly 100 ms.

## Hardware Controls

Hardware monitoring reads firmware-exposed thermal values. Fan and liquid-pump
actions use the stock `NBFan` settings policy; bypass charging uses the stock
global setting observed by the vendor service. MagicDesk captures each original
value before its first write and restores only state it owns on System, exit,
or interrupted-session recovery.

The runtime takes one initial hardware snapshot. Repeated thermal and vendor
state reads run only while the System panel is visible; closing or switching
away from that panel cancels the polling task without disabling controls or
discarding owned fan and pump state.

The main application does not write fan or pump sysfs nodes and does not claim
RPM data unavailable to shell UID 2000. Controls are capability-probed because
setting names and vendor services can change across firmware.

## Device Setup And Recovery

Device Setup requires Android 15+, a selected compatible platform driver, and
a live, authorized shell UserService. Every platform audits the two standard
Android settings:

```text
Settings.Global enable_freeform_support = 1
Settings.Global force_resizable_activities = 1
```

The Nubia/REDMAGIC platform additionally audits:

```text
persist.wm.debug.desktop_mode_enforce_device_restrictions = false
persist.wm.debug.desktop_use_rounded_corners = false
```

Shell UID 2000 owns the global settings. On supported Nubia/REDMAGIC firmware, the
two persistent properties are written through the firmware's
`redmagic.app.manager` Binder
from the ordinary APK UID. `NubiaDesktopPropertyManager` exposes a closed
enum, permits only boolean/absent values, and verifies every write. Generic
Android never reads those properties as setup requirements or writes them.

Normal first-run UI exposes only the next required user action: start Shizuku,
grant MagicDesk through Shizuku, prepare the device, restart, or start
MagicDesk. Display selection, individual setting values, firmware identity,
Diagnostics, and restoration remain in the manually opened **Device setup**
screen.

At the final start step, MagicDesk uses its already-authorized shell service to
set `SYSTEM_ALERT_WINDOW` for the fixed MagicDesk package and verifies the
result through `Settings.canDrawOverlays()`. No package name or operation comes
from user input. Android's public permission screen is offered only when this
bounded operation fails, and the failure is recorded as `OVERLAY-002`.

The boot ID marks configuration that still requires reboot. MagicDesk never
reboots automatically and has no boot receiver. A successful audit after boot
enters the control panel without flashing setup UI.

**Restore defaults** is available independently of setup history. It stops the
runtime, normalizes stale phone desktop tasks, removes the two global
desktop-windowing overrides, clears the two allowlisted persistent properties,
and resets primary-display size/density/scaling overrides. Removing overrides
lets the firmware supply its defaults and remains usable after MagicDesk has
been uninstalled and installed again. Diagnostics and background audits never
authorize a runtime session or start services.

## Diagnostics

`CompatibilityDiagnostics` records stable error codes with bounded local
history. An identical signature is recorded only once during a process
lifetime, including when unrelated events occur between repetitions. Exact
duplicates left by earlier process runs are also collapsed when the report is
built. The issue report includes firmware identity, displays, external input,
desktop settings, Shizuku UID/domain/capability probes, and MagicDesk-only
logcat. It excludes user files, accounts, notification content, clipboard, and
the installed-app catalog.

Input bridge diagnostics are event-driven lifecycle counters: startup attempts,
ready or pointer-only sessions, source-refresh failures, bridge anomalies, and
the last routing display. They do not poll, record key events, or retain typed
text. The report also states whether the optional desktop-session wake policy
is enabled and currently held.

Compatibility probes are non-destructive: they inspect permissions and reject
invalid/null mutations after framework permission checks rather than changing
real input, display, or hardware state.

The manual desktop self-test combines those probes with reversible black-box
operations on a simulated, connected external, or phone display. APIs that can
be checked without peripherals are reported as PASS/WARN/FAIL. The selected
wired or Miracast transport is recorded as exercised, while physical keyboard,
mouse, and Touch Panel input remain NOT TESTED because the automation injects
input. The last bounded result is included in the normal compatibility report;
no periodic self-test or diagnostic polling runs in the background.

Debug builds also expose this production path through
`DesktopLifecycleInstrumentation`. It reports the complete self-test result to
`am instrument`; it is intentionally not run by host-only CI.

Desktop wallpaper loading follows the same fail-open rule. By default MagicDesk
reads the current static system wallpaper. MagicDesk Files offers **Set as
desktop wallpaper** only for local image files. The selected file is reopened
through its verified device/inode identity, decoded far enough to validate the
image, and atomically copied to
`/storage/emulated/0/Desktop/.magicdesk/wallpaper`;
selecting **Use system wallpaper** removes that override. An unavailable or
undecodable image falls back to the last valid custom image, the system image,
the last valid cached system image, or MagicDesk's built-in background and
records one compatibility event per distinct failure instead of changing
desktop session state.

`CommandConsoleActivity` is an unexported, multi-instance desktop task over the
existing `ShellAccess` connection. Each Activity owns one
`ConsoleShellSession`, a process-local command history, current-directory
state, and a selectable stdout/stderr transcript. The session uses one
long-lived `/system/bin/sh`; private marker records delimit commands and update
the prompt without being shown to the user. Running `exit` or closing the
Activity closes that shell. Initial commands supplied by Files are displayed
for review and are never executed automatically.

## Rejected Experiments Worth Remembering

These results explain otherwise tempting implementation choices:

- A custom caption overlay cannot stay atomically attached to a task leash.
- Public freeform launch from an ordinary app UID is normalized to fullscreen
  on the verified firmware.
- Creating a custom task display area on Nubia's `NubiaAppMirrorDisplay`
  removes that vendor display; custom task areas remain limited to simulated
  displays where the complete lifecycle is verified.
- Nubia `WindowReply` is allowlisted and cannot manage arbitrary packages.
- Moving a running task through display 0 can kill or recreate the application.
- Fixed sleeps around task transitions are both visible and race-prone.
- Generic configuration changes cannot reliably refresh stale insets: some
  applications recreate while others handle the change in place. Refresh the
  exact task-local caption source instead.
- Asynchronous add/remove of the replacement inset source can be coalesced by
  Nubia before the client observes it; both stages require sync callbacks.
- Accessibility key filtering does not reliably receive physical keys routed
  to the external desktop.
- Per-button mouse reinjection loses application context and pointer semantics;
  forwarding the complete grabbed source does not.
- Disabling or force-stopping Nubia's entire input package breaks Touch Panel;
  the DisplayManager phone-screen guard solves the wake problem at its source.
- A persistent vendor freezer whitelist is unnecessary and harder to clean up;
  the transient service-working heartbeat is sufficient.
- ZTE audio source `80` is a `MediaRecorder` path. Replacing it with
  `AudioRecord` fails in AudioFlinger even for a privileged UserService.

Additional vendor-level evidence is preserved in
[Nubia vendor interface audit](nubia-vendor-audit.md).

## Build And Release Boundaries

The Gradle project has three modules:

- `app`: main MagicDesk APK;
- `hidden-api-stubs`: compile-only framework signatures;
- `kernel-fixes`: independent optional APK.

Every main-app build compiles the two native input helpers from source. CI must
verify that the main APK contains both helpers and no `.ko`, and that the
Kernel Fixes APK contains exactly the reviewed module and no input helper.

The kernel module itself is not compiled in normal Android CI. Rebuilding it
requires the exact upstream kernel source, config, symbol versions, and guarded
script documented in [VITURE XR resolution fix](xr-resolution-fix.md).

Normal CI builds unsigned release variants of both applications and runs
`scripts/verify-apks.sh` with both APKs to enforce their package boundaries.

For a `v*` tag, the release workflow loads signing credentials through
`gradle/release-signing.gradle`, signs only the main MagicDesk APK, verifies its
certificate and package boundary, emits a SHA-256 file, and publishes that APK
as the tagged release. The firmware-specific Kernel Fixes APK is not a tagged
release artifact. Local debug builds never require release secrets.
