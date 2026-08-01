# MagicDesk Architecture

This document describes the implementation boundaries behind MagicDesk's
DeX-style desktop on REDMAGIC firmware. It is intended for contributors,
reviewers, and users diagnosing compatibility problems.

## Design Principles

MagicDesk follows these constraints:

1. Android applications remain real Android tasks.
2. The firmware's `ShellTaskOrganizer` and native window decorations remain in
   control of move, resize, snap, maximize, minimize, and close.
3. Runtime system access requires Shizuku running as shell UID 2000.
4. Device-specific operations are narrow, reversible, and checked before use.
5. Background work is event-driven where Android exposes an event source.
6. Optional root and kernel code stays outside the main APK.

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
| Mouse helper | `native/magicdesk_uinput_bridge.c` | Heartbeat-bound external-to-virtual pointer forwarding |
| Keyboard helper | `native/magicdesk_keyboard_bridge.c` | Heartbeat-bound keyboard forwarding and shortcut interception |
| Kernel Fixes add-on | `io.github.mekhontsev.magicdesk.kernel` | Independent, manually launched, firmware-specific root fixes |

The main APK contains no `.ko`, kernel loader, root command path, or reference
to the add-on package. The two applications share a repository and release
process, but no runtime integration.

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
  desktop HOME task is ready.
- `MagicDeskRuntimeService` owns the persistent notification and process-level
  runtime. There is no boot receiver; the user starts MagicDesk manually.

### Desktop UI

- `StartMenuController`, `TaskbarController`, `TaskOverviewController`,
  `NotificationCenterController`, and `DesktopItemsController` own desktop UI.
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
- `DesktopTaskWatcher` owns a bidirectional `TaskStackListener` stream and
  immediate focus acknowledgements.
- `DesktopTaskController` orchestrates native task transitions.
- `DesktopWindowTransitionController` owns shortcut and immersive transitions.
- `DesktopTaskStateStore` persists freeform bounds and visible Z-order.
- `NativeWindowBoundsController` calculates snap, maximize, and restore bounds.
- `DesktopPhoneUiReconciler` repairs Nubia launcher state after display changes.
- `AppTaskController`, `WorkspaceController`, and `AltTabController` coordinate
  task actions, Show Desktop, restoration, and exact-task switching.

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

`DeviceSetupManager` accepts Shizuku only after the bound
`ShizukuCommandService` reports UID 2000. There is no root, basic, automatic,
or fallback runtime branch.

`ShellAccess` owns the official UserService connection and an immutable
runtime snapshot. Shizuku Binder and permission events update the snapshot;
finite operations read it without repeating package, permission, version, and
UID probes. Explicit setup/diagnostic audits and command failures refresh it.
Finite operations use typed AIDL calls or bounded shell commands. Long-lived
operations use `ParcelFileDescriptor` streams owned by an APK Binder token:

- task events and focus commands;
- keyboard forwarding and shortcut events;
- mouse forwarding;
- phone-display power ownership.

The UserService links to the owner token and sends a one-second heartbeat to
owned helpers. Binder death, EOF, explicit close, or timeout initiates bounded
graceful cleanup before process termination. This ordering matters: immediate
`SIGKILL` can leave display power or grabbed input in an incorrect state until
the kernel closes the final descriptor.

Framework commands that need hidden signatures run from the shell UserService
through `app_process` with the main APK on the class path. `hidden-api-stubs`
exists only for compilation; it is not packaged in the APK.

## Display And Session Model

A `SessionProfile` stores only a display selection policy. Runtime display IDs
are never persisted as constants.

- A normal launch on display 0 opens the phone control panel.
- **Open desktop here** keeps a local desktop in the same task, producing one
  Recents card and supporting tablets.
- An external desktop is a fullscreen HOME activity on Nubia's virtual desktop
  display. This keeps MagicDesk behind native application windows without
  replacing the phone launcher.
- Phone control and external desktop are separate tasks and may coexist.

The desktop uses one `WindowMetrics`/WindowInsets viewport model on every
display. On display 0 it stays below Android system bars. A dedicated external
display normally reports zero system-bar insets and fills the panel. There is
no separate phone implementation of the desktop.

The taskbar is a display-scoped application overlay. It remains above freeform
tasks, hides for an unrelated true-fullscreen task, and returns for the desktop.
This also avoids tying shell visibility to Activity focus callbacks.

On display 0, Nubia Quickstep can crash while binding Recents to a desktop
group containing freeform tasks. A local session records pending cleanup and
converts its freeform tasks to fullscreen before exposing the phone launcher.
The marker survives process failure and is reconciled on the next manual start.

Each external monitor has a profile keyed by a hash of its DisplayPort EDID,
with a port/name/resolution fallback until EDID is available. Profiles store
DPI, desktop-folder URI, and confirmed window bounds/Z-order. Taskbar pins,
desktop shortcuts, and the recent-app history are global so the same workspace
entry points follow the user between the phone, a tablet, and every monitor.

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
2. creates the transient seed only for the known Home-only Mirror state;
3. requests REDMAGIC desktop mode and waits for the real virtual display;
4. corrects portrait geometry and applies the display profile DPI;
5. creates or normalizes one fullscreen MagicDesk HOME task;
6. removes the seed and focuses the desktop;
7. restores the last visible freeform window layout.

Requests are serialized and duplicate requests during transition are ignored.
With no external display, the shortcut cannot accidentally create a second
desktop on display 0.

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
- recover Quickstep/Home when Nubia reparents its secondary launcher to
  display 0;
- stop the foreground runtime on explicit exit.

A `DisplayManager.DisplayListener` validates actual display lifecycle instead
of trusting only Nubia's global state values.

## Window Transitions

MagicDesk operates on exact task IDs. Windowed launches and restores use native
WMShell desktop transitions when available. Snap and maximize reserve the
MagicDesk taskbar; true fullscreen does not.

Application-requested immersive mode is reported by the task watcher. MagicDesk
hides its shell and lets the same Activity enter true fullscreen. Leaving
immersive mode restores the prior desktop geometry.

REDMAGIC can retain a stale caption inset after changing windowing mode. The
working same-display refresh changes the task's density by one DPI and restores
it immediately through the same WindowContainerTransaction sequence. The
one-DPI pulse causes a real configuration refresh without routing through the
phone display or restarting the application. Details and rejected alternatives
are in [Fullscreen transitions](fullscreen-transitions.md).

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

## Hardware Controls

Hardware monitoring reads firmware-exposed thermal values. Fan and liquid-pump
actions use the stock `NBFan` settings policy; bypass charging uses the stock
global setting observed by the vendor service. MagicDesk captures each original
value before its first write and restores only state it owns on System, exit,
or interrupted-session recovery.

The main application does not write fan or pump sysfs nodes and does not claim
RPM data unavailable to shell UID 2000. Controls are capability-probed because
setting names and vendor services can change across firmware.

## Device Setup And Recovery

Device Setup requires a compatible ZTE/nubia Android 16+ device and a live
Shizuku UserService at UID 2000. It audits:

```text
Settings.Global enable_freeform_support = 1
Settings.Global force_resizable_activities = 1
persist.wm.debug.desktop_mode_enforce_device_restrictions = false
persist.wm.debug.desktop_use_rounded_corners = false
```

Shell UID 2000 owns the global settings. The two persistent properties are
written through the firmware's `redmagic.app.manager` Binder from the ordinary
APK UID. `NubiaDesktopPropertyManager` exposes a closed enum, permits only
boolean/absent values, verifies every write, and stores original values.

The boot ID marks configuration that still requires reboot. MagicDesk never
reboots automatically and has no boot receiver. A successful audit after boot
enters the control panel without flashing setup UI.

**Restore previous values** restores only values whose original state was
captured and whose ownership marker remains. Diagnostics and background audits
never authorize a runtime session or start services.

## Diagnostics

`CompatibilityDiagnostics` records stable error codes with bounded local
history. The issue report includes firmware identity, displays, external input,
desktop settings, Shizuku UID/domain/capability probes, and MagicDesk-only
logcat. It excludes user files, accounts, notification content, clipboard, and
the installed-app catalog.

Compatibility probes are non-destructive: they inspect permissions and reject
invalid/null mutations after framework permission checks rather than changing
real input, display, or hardware state.

## Rejected Experiments Worth Remembering

These results explain otherwise tempting implementation choices:

- A custom caption overlay cannot stay atomically attached to a task leash.
- Public freeform launch from an ordinary app UID is normalized to fullscreen
  on the verified firmware.
- Nubia `WindowReply` is allowlisted and cannot manage arbitrary packages.
- Moving a running task through display 0 can kill or recreate the application.
- Fixed sleeps around task transitions are both visible and race-prone.
- Setting a task to its current DPI does not refresh stale insets; a real
  one-DPI configuration pulse does.
- Accessibility key filtering does not reliably receive physical keys routed
  to the external desktop.
- Per-button mouse reinjection loses application context and pointer semantics;
  forwarding the complete grabbed source does not.
- Disabling or force-stopping Nubia's entire input package breaks Touch Panel;
  the DisplayManager phone-screen guard solves the wake problem at its source.
- A persistent vendor freezer whitelist is unnecessary and harder to clean up;
  the transient service-working heartbeat is sufficient.

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

Release signing is loaded from environment variables by
`gradle/release-signing.gradle`. GitHub Actions receives the encrypted keystore
and passwords from repository secrets, signs both release APKs, compares their
certificates, runs `scripts/verify-apks.sh`, emits SHA-256 files, and publishes
tagged artifacts. Local debug builds never require release secrets.
