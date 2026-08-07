# MagicDesk Architecture

This document describes the implementation boundaries behind MagicDesk's
DeX-style desktop on REDMAGIC firmware. It is intended for contributors,
reviewers, and users diagnosing compatibility problems.

## Design Principles

MagicDesk follows these constraints:

1. Android applications remain real Android tasks.
2. The firmware's `ShellTaskOrganizer` and native window decorations remain in
   control of move, resize, snap, maximize, minimize, and close.
3. Runtime system access requires an authorized Shizuku UserService.
4. Device-specific operations are narrow, reversible, and checked before use.
5. Background work is event-driven where Android exposes an event source.
6. Optional kernel code stays outside the main APK.

MagicDesk does not register a competing task organizer, host applications in
surrogate activities, draw replacement captions, patch SystemUI, invoke `su`,
or require a Magisk module.

## Architecture Guardrails

Several plausible implementations conflict with REDMAGIC's secondary-display
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

REDMAGIC converts the external mouse's `BTN_RIGHT` to Android Back before an
application receives it. Shell UID 2000 cannot change the physical keymap, but
it can open external cursor devices read-only, acquire `EVIOCGRAB`, and create
a `BUS_VIRTUAL` pointer through `/dev/uinput`.

`ConsoleMouseBridge` discovers only EventHub devices marked
`CURSOR | EXTERNAL`. One native helper unions their capabilities, creates one
virtual pointer, grabs the sources, and forwards the complete stream unchanged.
REDMAGIC does not apply its Back conversion to the virtual device, so normal
Android secondary click reaches applications.

The keyboard bridge follows the same ownership model. Forwarding the complete
stream preserves key repeat, modifier state, hot-plug behavior, and the first
key after a layout change more reliably than synthetic one-key injection.

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
- `MagicDeskRuntimeService` owns the persistent notification and process-level
  runtime. There is no boot receiver; the user starts MagicDesk manually.

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
- `DesktopContentStore` stores global desktop content. `DisplayProfileStore`
  stores only display-specific DPI and geometry. `DesktopPlacementEngine` is
  the platform-independent collision and reflow policy.
- `OverlayPanelController` provides consistent toggle, dismissal, placement,
  and display-scoped overlay behavior.
- `DesktopInputController` handles shell UI input and delegates global physical
  shortcuts to the keyboard bridge.
- `DesktopRuntimeBridge` is the weak-reference, main-thread boundary through
  which services reach the active desktop.
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
- `ShellFreeformTaskCleanup` remembers freeform application tasks observed
  during the active desktop session. If one disappears, it verifies that no
  live task remains and removes only a Recents entry with the same task ID,
  package, and display. This prevents Nubia Quickstep from crashing while
  binding a stale `DesktopTaskView` without persistent recovery state or
  changes to unrelated Recents entries.
- `DesktopTaskController` orchestrates native task transitions.
- `DesktopWindowTransitionController` owns shortcut and immersive transitions.
- `DesktopTaskStateStore` persists freeform bounds and visible Z-order.
- `NativeWindowBoundsController` calculates snap, maximize, and restore bounds.
- `DesktopPhoneUiReconciler` repairs Nubia launcher state after display changes.
- `AppTaskController`, `WorkspaceAppController`, and `AltTabController`
  coordinate task actions, Show Desktop, restoration, and exact-task
  switching.

### Platform services

- `ConsoleModeSwitcher` serializes public session transitions.
- `ConsoleSessionController` owns activation and teardown.
- `ConsoleDisplayController` discovers dynamic display IDs and fixes geometry.
- `KeyboardShortcutWatcher`, `ConsoleMouseBridge`, and
  `HardwareKeyboardLayoutController` own physical input policy.
- `NubiaTouchpadController` starts and repairs REDMAGIC Touch Panel routing.
- `RedmagicHardwareController` owns capability probing, stock fan/pump policy,
  monitoring, and baseline restoration.
- `DesktopNotificationListenerService` owns Android notification-listener state;
  `DesktopNotificationMapper` isolates framework-to-UI conversion.

Repositories perform package, task, and document queries. View controllers do
not construct arbitrary shell commands. Platform controllers do not construct
desktop panels. Keep this split when adding vendor-specific behavior.

## Shizuku Runtime

`DeviceSetupManager` accepts Shizuku after the bound
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
one-second heartbeat refreshes REDMAGIC's transient `cfreezer` state and
provides fail-open display restoration if ownership is lost.

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
transport-specific code stops at that boundary. Local startup retains its
launcher-navigation guard, then starts the same desktop host and controllers.

- `ConsoleSessionController` asks REDMAGIC firmware to turn a physical USB-C
  display into Nubia's virtual desktop display, applies its output profile, and
  enables the stock touchpad and wired input-routing path.
- Wireless startup opens the stock SmartCast/Miracast picker. Once Android
  reports a Wi-Fi presentation display, MagicDesk passes that display ID to
  the common desktop session without implementing a second discovery or
  streaming stack.
- An Android overlay display is used only by explicit contributor tests. It
  exercises the standard desktop Activity and task placement without adding a
  viewer or virtual-display product mode.

The runtime owns native caption visibility for any active secondary desktop.
Nubia's mouse and keyboard port association remains limited to its wired
Console display because Miracast and simulated displays do not expose the same
physical display port contract.

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
The script temporarily sets `overlay_display_devices`, starts the real
`DesktopActivity` on that display, verifies task placement, and restores the
previous setting on exit.

The built-in **Diagnostics > Run desktop self-test** follows the same display
model without requiring a host. A Binder-owned Shizuku stream holds the
temporary setting; closing the stream or losing its owner closes stdin, runs a
shell `trap`, and restores the prior value. The test then uses production
session and task controllers to verify the desktop viewport, a deterministic
freeform Activity, task-local native caption source and geometry,
display-targeted input, true fullscreen, restore, minimize, and cleanup. It
rechecks the caption after the fullscreen round trip and closes the desktop
task before removing the display so WMS
cannot migrate that task onto the phone launcher.

The desktop uses one `WindowMetrics`/WindowInsets viewport model on every
display. On display 0 it stays below Android system bars. A dedicated external
display normally reports zero system-bar insets and fills the panel. There is
no separate phone implementation of the desktop.

The taskbar is a display-scoped application overlay. It remains above freeform
tasks, hides for an unrelated true-fullscreen task, and returns for the desktop.
This also avoids tying shell visibility to Activity focus callbacks.

On display 0, Nubia Quickstep can crash while binding Recents to a desktop
group containing freeform tasks. Its `DesktopTaskView.bind()` creates task
containers without a title view, but `TaskView.setThumbnailOrientation()`
unconditionally asserts that the same title view is non-null. Current AOSP
Launcher3 intentionally permits that field to be absent; this is a vendor
integration defect rather than malformed task metadata.

Before the first local freeform task is created, the Shizuku UserService uses
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
navigation guard before exposing the phone launcher. Explicit Exit attempts
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

Each external monitor has a profile keyed by a hash of its DisplayPort EDID,
with a port/name/resolution fallback until EDID is available. Profiles store
DPI, sparse desktop-item placements, widget spans, and confirmed window
geometry. Files under `/storage/emulated/0/Desktop`, widget bindings, taskbar
pins, desktop shortcuts, the kept workspace application, and recent-app
history are global, so the same desktop content follows the user between the
phone, a tablet, and every monitor while adapting to each viewport.

## Desktop Surface And Widgets

`DesktopGridLayout` is a real `ViewGroup`, not a bitmap or remote task
container. Every shortcut, file, and `AppWidgetHostView` remains an ordinary
Android view with native accessibility and input behavior. Placements use
logical cells and row/column spans rather than pixels, so DPI or resolution
changes only reflow items that no longer fit.

Widget IDs are owned by Android's `AppWidgetHost` and are therefore global to
the MagicDesk installation. MagicDesk persists only their per-display
placement and size. Provider clicks remain native; widget movement is entered
explicitly from the context menu so drag handling cannot steal controls or
scroll gestures from the provider. Binding and optional configuration use the
system widget activities and do not depend on Shizuku.

Desktop filesystem operations cross one typed AIDL boundary instead of
interpolating filenames into shell commands. The UserService rejects paths
outside the fixed root, symbolic-link traversal, invalid names, and accidental
overwrite. Explicit physical deletion is recursive only after user
confirmation; removing an application shortcut or widget never deletes
application data. MagicDesk does not delete the Desktop directory or its
contents during Exit or uninstall.

Files opened in another application are exposed through a non-exported,
read-only `ContentProvider` with a per-Intent URI grant. The receiving
application never receives Shizuku access or a raw privileged filesystem
handle. Directory changes are delivered by `FileObserver`; there is no folder
polling while the desktop is idle.

## External Desktop Activation

The stock REDMAGIC projection UI enters desktop mode through the vendor
DisplayManager extension:

```text
DisplayManager.setCmdToDisplay(1, physicalDisplayId, 0, null)
```

MagicDesk calls the same Binder method from shell UID 2000. It does not infer
state by writing `app_mirror_status` or hardcode a virtual display ID.

On **Start external desktop** or `Win+D`, MagicDesk:

1. resolves the current physical or virtual display;
2. creates a landscape transient seed only for the known Home-only Mirror
   state, avoiding Nubia's synchronous foreground-activity check during an
   orientation relaunch;
3. requests REDMAGIC desktop mode and waits for the real virtual display;
4. corrects portrait geometry and applies the display profile DPI;
5. creates or normalizes one display-sized MagicDesk multi-window host task;
6. removes the seed and focuses the desktop;
7. restores the last visible freeform window layout.

Requests are serialized and duplicate requests during transition are ignored.
With no external display, the shortcut cannot accidentally create a second
desktop on display 0.

### Output timing and fill policy

Before activation, the phone control panel reads Nubia's current and available
DisplayPort timings from `/sys/kernel/lcd_enhance/edid_modes`. The selector
shows the resolution and refresh rate advertised by the connected sink. A
saved timing is used only while it remains in that list; otherwise MagicDesk
chooses the highest native resolution, the highest refresh rate at that
resolution, and avoids a cinema-aspect duplicate when a normal timing exists.

Changing the physical timing uses Nubia's own sequence: write the selected
EDID mode, ask DisplayManager to refresh its physical displays, pulse HDMI HPD,
then wait for three stable observations of the requested mode. The physical
display id is resolved again afterward because the firmware can recreate it
during this transition. The operation runs before the virtual desktop is
requested, so no MagicDesk task is attached to a disappearing display.

**Fill display** maps to Nubia's projection-fit setting. MagicDesk temporarily
enables the vendor fit bypass while preparing the session, writes the fit and
resolution-class values consumed by the projection service, and restores the
previous bypass property afterward. Output timing changes real HDMI/DisplayPort
geometry; desktop DPI remains an independent per-monitor UI scale.

### Caption visibility

REDMAGIC wired privacy mode calls:

```text
SurfaceControl.setSFOption(1102, 1)
```

The firmware filters external layers whose names contain `Task=`. AOSP caption
layers are named `Caption of Task=<id>`, so captions can remain interactive but
become visually black or absent. During an external session MagicDesk sets
option `1102` to visible, records lifecycle ownership, and restores the latest
value mirrored by Nubia in `Settings.Global.cast_privacy_model` on mirror,
exit, or next-start recovery.

### Teardown

Switching to mirroring, physical display removal, and **Exit MagicDesk** share
one cleanup path:

- close display-scoped overlays and stop task observation;
- stop keyboard, mouse, and phone-display streams;
- restore caption privacy and display geometry ownership;
- restore vendor hardware settings changed by MagicDesk;
- normalize live freeform tasks on display 0 and remove dead Recent entries
  still retained by the current user's WMShell desktop repository, including
  entries left by earlier MagicDesk runs or restored after a reboot;
- recover Quickstep/Home when Nubia reparents its secondary launcher to
  display 0;
- stop the foreground runtime on explicit exit.

A `DisplayManager.DisplayListener` validates actual display lifecycle instead
of trusting only Nubia's global state values.

## Window Transitions

MagicDesk operates on exact task IDs. Windowed launches and restores use native
WMShell desktop transitions when available. Snap and maximize reserve the
MagicDesk taskbar; true fullscreen does not.

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

REDMAGIC can retain a stale caption inset after changing windowing mode. The
working same-display refresh captures the task-local caption source before the
transition, then synchronously replaces that exact client source with an empty
frame after fullscreen mode is established. It neither changes density nor
recreates the Activity. Details and rejected alternatives are in
[Fullscreen transitions](fullscreen-transitions.md).

## Physical Input

The keyboard helper consumes only the global MagicDesk combinations listed in
README. Ordinary key events preserve scan code, repeat value, modifier state,
and device identity through the virtual external keyboard.

`Ctrl+Space` queries `IInputManager` for layouts associated with enabled IME
subtypes, selects the next configured layout for every connected physical
keyboard, and updates the taskbar label. The bridge holds subsequent input only
until InputManager confirms the new layout, avoiding both a fixed delay and a
first character in the previous language.

The mouse helper forwards movement, wheels, buttons, and multitouch-derived
pointer events. It exists specifically because REDMAGIC consumes physical
`BTN_RIGHT` as Back. `Win+Backspace` remains the explicit system Back shortcut.

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

REDMAGIC's `nubia_screen_off_tp` path lets its text-input activity wake display
0 whenever an external text field receives focus. MagicDesk instead uses the
shell DisplayManager `power-off 0`/`power-reset 0` contract. A heartbeat-owned
`PhoneDisplayGuard` restores power after normal or abnormal teardown.

While display 0 is off, REDMAGIC's independent `cfreezer` can freeze even a
foreground-service HOME process. The same heartbeat refreshes the vendor's
transient `noteCpuFreezerUidWorking` state and clears it during restore. No
persistent freezer whitelist is installed.

Touch Panel remains a vendor activity on display 0. MagicDesk can launch it
from the phone notification or desktop controls and repairs its pointer
viewport after virtual-display geometry changes. It does not replace the
vendor touchpad implementation.

Nubia's separate `HostAssistPanel` is the small handle drawn over the external
desktop. While MagicDesk owns the session it asks the existing vendor observer
to remove that panel and immediately restores the observer's original
`tp_type_for_games` value. The phone-side ProjectionIcon is intentionally left
alone because it remains useful for switching projection modes. Pointer speed
uses Android's standard `Settings.System.pointer_speed` range and is observed
for changes made outside MagicDesk.

## Desktop Display Recording

MagicDesk resolves the active desktop's logical display to its physical display
ID and records either the phone or external REDMAGIC output with Android's
system `screenrecord --display-id` command. Internal audio uses the firmware's
`AUDIO_SOURCE_SYSTEM_RECORD` value `80`, the same source used by the stock ZTE
screen recorder and Game Highlights. The source is accepted by
`MediaRecorder`, but the audio HAL rejects it through `AudioRecord`; these APIs
are not interchangeable on the verified firmware.

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
keeps the stock vendor audio path usable for both supported Shizuku UIDs.

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

Device Setup requires a compatible ZTE/nubia Android 16+ device and a live,
authorized Shizuku UserService. It audits:

```text
Settings.Global enable_freeform_support = 1
Settings.Global force_resizable_activities = 1
persist.wm.debug.desktop_mode_enforce_device_restrictions = false
persist.wm.debug.desktop_use_rounded_corners = false
```

Shell UID 2000 owns the global settings. The two persistent properties are
written through the firmware's `redmagic.app.manager` Binder from the ordinary
APK UID. `NubiaDesktopPropertyManager` exposes a closed enum, permits only
boolean/absent values, and verifies every write.

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
history. The issue report includes firmware identity, displays, external input,
desktop settings, Shizuku UID/domain/capability probes, and MagicDesk-only
logcat. It excludes user files, accounts, notification content, clipboard, and
the installed-app catalog.

Compatibility probes are non-destructive: they inspect permissions and reject
invalid/null mutations after framework permission checks rather than changing
real input, display, or hardware state.

The manual desktop self-test combines those probes with reversible black-box
operations. APIs that can be checked without peripherals are reported as
PASS/WARN/FAIL. Transport and hardware behavior that cannot be inferred from
class, Binder-service, permission, or device-node presence is reported as
NOT TESTED rather than guessed. The last bounded result is included in the
normal compatibility report; no periodic self-test or diagnostic polling runs
in the background.

`CommandConsoleActivity` is an unexported, one-shot interface over the existing
`ShellAccess` connection. It displays the effective Shizuku UID, requires an
explicit first-run confirmation, combines stdout and stderr with the exit
status, and persists neither commands nor output.

## Rejected Experiments Worth Remembering

These results explain otherwise tempting implementation choices:

- A custom caption overlay cannot stay atomically attached to a task leash.
- Public freeform launch from an ordinary app UID is normalized to fullscreen
  on the verified firmware.
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
