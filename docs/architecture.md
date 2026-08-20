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
7. Display transport, firmware integration, SoC services, and shell execution
   remain independent boundaries.
8. Interfaces represent external boundaries or multiple real implementations;
   they are not introduced only to move code between files.

MagicDesk does not register a competing task organizer, host applications in
surrogate activities, draw replacement captions, patch SystemUI, invoke `su`,
or require a Magisk module.

Dependencies point in one direction:

```text
activities and desktop UI
        |
controllers and session orchestration
        |
task, display, input, storage, and capture contracts
        |
Android shell adapters + selected platform and SoC backends
```

UI code does not select firmware implementations. Platform and SoC adapters
do not own desktop UI or session state. Runtime composition occurs only in the
registries documented below.

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

The pointer helper starts passively. The runtime first waits until its virtual
mouse is visible in EventHub, establishes the display associations, and only
then enables physical capture. Teardown reverses that order: the helper
releases every `EVIOCGRAB` and acknowledges completion before the routing
session removes its associations. A helper restart repeats the same protocol
instead of inheriting capture permission from a destroyed virtual device.
This ordering prevents physical and virtual cursor mappers from observing a
partially constructed or partially removed route.

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

### Keep WMShell desktop decorations enabled as a unit

Android's fullscreen **App Handle** and a freeform task's native caption are
different decorations, but the tested Nubia SystemUI creates both through its
WMShell desktop-window-decoration module. The handle is the small control at
the top of an otherwise fullscreen task; dragging it is a firmware-provided
way to enter desktop windowing. It is not a stale freeform caption and cannot
be removed through MagicDesk's task-local caption-inset repair.

Two configuration experiments establish the boundary on this firmware:

| Configuration at SystemUI startup | Fullscreen App Handle | Freeform mode | Native freeform caption |
| --- | --- | --- | --- |
| Desktop features enabled and device restrictions disabled | Shown | Works | Shown |
| `override_desktop_mode_features=0` | Hidden | Works | Not created |
| Device restrictions enforced | Hidden | Works | Not created |

These values are cached by SystemUI. Changing them for only a MagicDesk
session therefore requires restarting SystemUI both when entering and leaving
the session. That restart also rebuilds WMShell task repositories and briefly
removes system bars and decorations. It is unsafe during interrupted wired or
wireless projection and can disturb system dialogs, capture UI, and the phone
launcher. The narrower firmware flags for handle animation, hold-to-drag input,
input fixes, and immersive hiding do not disable the fullscreen handle while
retaining freeform captions.

MagicDesk consequently provisions native desktop decorations as one firmware
capability and accepts the fullscreen App Handle when the firmware couples the
two surfaces. It must not restart SystemUI at desktop-session boundaries,
patch or overlay SystemUI, or replace the native caption merely to hide that
handle. A future platform backend may expose a narrower supported control, but
absence of such a control is a cosmetic firmware limitation rather than a task
transition failure.

### Do not recreate application tasks through display 0

Do not use the phone display as a window-mode trampoline, force-stop a target
application, or add guessed sleeps to refresh fullscreen geometry. Those paths
can destroy an Activity and its user session. Use same-display transactions
and the client-preserving refresh described in
[Fullscreen transitions](fullscreen-transitions.md).

### Keep true-fullscreen tasks under one fullscreen parent

The active desktop task parent is freeform-oriented: it is the display's
default task area on external displays and the shell-owned session task area
on the phone. Reordering independent fullscreen roots there can make a task
inherit freeform mode during Alt+Tab, even when its final mode is repaired
afterward. MagicDesk therefore reparents a reordered stack of true-fullscreen
tasks into one organizer-owned fullscreen `TaskDisplayArea` nested under that
active parent. A lone application-driven fullscreen task stays directly under
the active parent: some projection displays remove their task-hosting virtual
display when that task is moved under an organizer-created parent.

The long-lived shell task observer owns that area. Switching only reorders
children inside the same parent; restoring a window releases that task to the
active desktop parent while it is hidden or still fullscreen. Application-
driven restores are completed in the observer before their result crosses
Binder. They use a hidden fullscreen-to-freeform mode boundary in the active
parent to rebuild native decoration without changing desktop sessions.

The parent must be established before focusing a freeform task while another
MagicDesk task is fullscreen. Waiting until both tasks already report
fullscreen is too late: the focus transition can first demote the existing
fullscreen root in the active parent. The observer therefore moves existing
fullscreen peers under the dedicated child in a synchronous hierarchy
transaction, then performs normal `TO_FRONT` focus. If that focused task is
subsequently made fullscreen, its mode change and reparenting are committed
synchronously into the same parent before input focus is handed over.

Closing the final member deletes the area. Platforms without this organizer
capability use the ordinary focus path and never apply a delayed mode repair.

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
  process-level runtime without duplicating subsystem state. Other components
  use the process-local `MagicDeskRuntime` facade instead of depending on the
  Android Service implementation. The service attaches a package-private
  backend for its lifetime; absent-runtime calls have explicit safe defaults,
  and a stale service cannot detach a newer backend instance.
  `RuntimeDesktopSessionCoordinator` owns desktop-display identity, session
  removal, and phone-Home recovery. It consumes one immutable
  `DesktopSessionSnapshot` per decision, so the host display and the prepared
  display target cannot come from different lifecycle transitions.
  `RuntimeDesktopInputCoordinator` composes
  input-device discovery, keyboard and mouse bridges, desktop text routing,
  and software-keyboard policy. `RuntimeDesktopTaskCoordinator` owns the
  process-level `DesktopTaskController`, keeps task observation available
  while shell access is ready, and binds display-scoped task reconciliation to
  the active session snapshot. It implements the narrow `DesktopTaskRuntime`
  contract exposed through `MagicDeskRuntime`; callers do not locate a
  process-global active task controller. The optional non-reference-counted partial
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
- `DesktopWorkspaceController` composes the fixed Android `Desktop` directory,
  freedesktop folder, web, and application Desktop Entries, and Android widgets
  on one `DesktopGridLayout` surface. Desktop Entries remain real files and use
  the same drag, rename, delete, and placement path as every other desktop
  file.
- `DesktopFolderController` owns asynchronous desktop-file operations and the
  lifecycle of an event-driven observer. `ShellDesktopDirectory` constrains
  typed UserService operations to `/storage/emulated/0/Desktop` and owns its
  `FileObserver`. `DesktopWidgetController` owns the process-wide
  `AppWidgetHost` lifecycle and widget binding/configuration.
- `ShellFileSystem` is the separate, general filesystem boundary used by the
  built-in Files task. It intentionally accepts any absolute path available to
  the connected UserService identity; this broader contract is not reused by
  desktop metadata or automatic background work.
- `DesktopStateStore` is the single typed model for taskbar pins, global
  layout, application window state, settings, and display profiles.
  `DesktopLayoutStore`, `AppWindowStateStore`, `DesktopPreferences`, and
  `DisplayProfileStore` are narrow domain facades over that model.
  `DesktopPlacementEngine` is the platform-independent collision and reflow
  policy.
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
- `ShellWindowedTaskLauncher` owns every fresh windowed launch, independent of
  display type. It observes the new task through the persistent framework
  listener and joins mode and bounds to the task's original OPEN transition;
  an organizer-owned task area is an optional parent, not a separate launch
  implementation. The standalone shell command remains a diagnostic entry
  point and is not used by the application launch path.
- `ShellActivityStartController` is MagicDesk's single owner of Android's global
  activity-controller slot and dispatches starts to the external-migration and
  windowed-startup policies. `ShellWindowedTaskActivityGuard` follows only
  activity handoffs inside a task observed as freeform. If such a handoff
  changes that task to fullscreen without a client immersive request, it uses
  the last observed freeform bounds to restore the same task. User fullscreen,
  independent new-task launches, and application immersive requests are not
  corrected. The policy is event-driven and has no package allowlist or
  guessed startup delay.
- `ShellDesktopProcessFailureTracker` passively correlates framework crash and
  ANR callbacks with the latest typed task snapshot for the active desktop
  display. It preserves Android's normal crash/ANR response and reports only a
  bounded process summary, task/display context, and top activity; third-party
  stack traces and ANR process dumps do not cross into application diagnostics.
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
- `DesktopTaskController` orchestrates native task transitions as an instance
  owned exclusively by `RuntimeDesktopTaskCoordinator`. It contains no static
  active-controller reference; pure task classification helpers remain static.
  Destructive actions also cross this runtime boundary: task close combines
  survivor focus and removal in one transition, while package force-stop first
  commits the surviving desktop task and only then stops the package.
  Pre-focus host relayout is enabled only by the selected windowing driver.
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
- `DesktopTaskRuntimeRegistry` owns one transient state object per Android task
  ID. Bounds, maximize/restore, fullscreen, immersive, and startup-windowed
  transitions share that object instead of maintaining parallel controller
  maps. Removing a task invalidates late asynchronous callbacks atomically;
  stopping the bounds controller clears only its bounds fields and preserves
  live fullscreen/immersive ownership.
- `DesktopDisplayTaskState` owns the active controller's visible workspace,
  last visible Z-order, and fullscreen-transition freeze as one display-scoped
  value. It is cleared with that controller and is not process-global.
- `NativeWindowBoundsController` calculates snap, maximize, and restore bounds.
- `DesktopPhoneUiReconciler` repairs Nubia launcher state after display changes.
- `AppTaskController` and `AltTabController` coordinate task actions,
  Show Desktop, restoration, and exact-task
  switching. `AppTaskController` has one UI lifecycle for built-in and regular
  window launches. `AppShortcutRepository` reads the standard static shortcut
  metadata published by each launch activity without taking Android's HOME
  role. Only single-intent actions targeting the publisher's own package enter
  the menu; their original action, data, extras, and task flags are preserved.
  Dynamic shortcuts remain owned by the system launcher because Android does
  not expose them to an ordinary non-HOME application. Before dispatching an
  action, MagicDesk prepares the application's normal task in the selected
  desktop mode, then starts the published intent with that task's ID. This
  keeps trampoline activities inside the application task instead of treating
  a short-lived redirect task as the launched desktop window.
  `WindowedAppLauncher` owns fresh launch/reuse selection and
  delegates fresh launches to the active persistent shell task observer.
  `ExistingTaskController` performs only task discovery and normalization. A
  single `WindowedTaskLaunchLease` spans each operation so startup-window
  protection and phone-touchpad preservation cannot be entered twice by the
  launcher and reuse path.
- `ShellFullscreenTaskArea` owns the organizer-created fullscreen task area
  used for Alt+Tab between true-fullscreen tasks. Moving the stack under a
  fullscreen parent avoids the transient freeform state caused by reordering
  roots in the default desktop task area. It preserves an existing fullscreen
  peer before a mixed fullscreen/freeform focus operation and accepts the next
  explicit fullscreen transition synchronously; it must not wait for both
  tasks to become fullscreen first. A task is synchronously released to
  the active desktop parent while still fullscreen before any restore or snap
  command changes its mode. Application-requested immersive tasks remain
  directly under that parent and share only the observer's saved-bounds
  lifecycle.
  The area closes after its final tracked task leaves.
  Self-test checks `FULLSCREEN-ALT-TAB-001` through `003` and
  `FULLSCREEN-LIFECYCLE-001` through `006` verify both task modes, real input
  focus, single-task restore and close, direct fullscreen session launches,
  system-Back removal, survivor visibility, and abrupt display removal.
- Shared fullscreen commands perform caption-source repair only when requested
  by `PlatformWindowingDriver`. Phone freeform cleanup in self-tests follows
  the same platform policy. Shell input recovery calls the selected
  `PlatformPointerDriver`; the Nubia driver alone chooses its firmware-specific
  finger-tool hover event.

### Platform services

MagicDesk ships one main APK from one codebase. New device support belongs in
runtime capability probes or a focused platform-driver implementation, while
shared desktop, task, window, and input behavior remains platform-independent.
Do not introduce per-model build variants or forks for differences that can be
isolated behind these boundaries.

- `PlatformDrivers` selects one firmware platform for the process from an
  immutable `PlatformDevice` identity and a platform-owned firmware capability
  probe. Hardware family names alone do not select a vendor driver: for
  example, Nubia hardware running an AOSP-derived custom ROM uses the standard
  Android driver when both the vendor platform service and an official Nubia or
  REDMAGIC firmware fingerprint are absent. The service probe runs under the
  ordinary application UID and does not depend on Shizuku being ready; the
  fingerprint fallback keeps stock Nubia firmware independent of a
  RedMagic-named service. `PlatformDriver` exposes only existing variation
  points. `PlatformWindowingDriver` owns provisioning properties;
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
- SoC display services are a third independent axis.
  `SocDisplayModeBackends` is their sole composition point. The optional
  Qualcomm `IDisplayConfig` implementation augments mode discovery and exact
  timing selection when Android's public mode list is incomplete; its absence
  is inert. Binder descriptors and transactions remain inside `soc.qualcomm`,
  while platform projection code consumes only `SocDisplayModeBackend` data.
- `DesktopDisplayTarget` is the immutable identity of the active display
  environment. `DesktopRuntimeBridge` retains that target as one value so a
  display ID and its transport cannot become separate, stale state.
- `DesktopRuntimeBridge` is only the stable process-local facade.
  `DesktopSessionRegistry` owns the immutable target/host snapshot, while
  `DesktopUiGateway` alone owns weak references to the live desktop Activity
  and dispatches UI commands. Session state therefore does not acquire UI
  behavior, and UI liveness cannot become a second session-state authority.
- `DesktopDisplayDriver` has four implementations: phone, wired, wireless,
  and simulated. A driver owns environment-specific activation, launch-area
  policy, phone-screen and touchpad availability, capture support, and display
  removal semantics. Closing and mirror transitions are deliberately absent
  from the drivers: one session coordinator combines the selected display
  target with the selected platform transport lifecycle.
- `DesktopDisplayDrivers` is the only registry for resolving those drivers.
  `ConsoleModeSwitcher` serializes public session transitions and delegates
  the selected target to the registry.
- `ConsoleSessionController` owns the optional RedMagic wired Console
  activation path. Standard Android displays bypass it and enter the common
  desktop session directly.
- `ConsoleModeSwitcher` remains the compatibility facade used by activities
  and shortcuts. `DesktopSessionTransitionCoordinator` owns activation,
  close, and mirror sequencing; `SerializedDesktopOperationQueue` provides the
  single ordered executor shared with shell settings and input policy. The
  facade owns neither transition flags nor an executor. Platform projection
  and feature contracts are injected into the coordinator, so a close cannot
  re-enter `ConsoleModeSwitcher` through a display driver.
- Desktop shortcut and panel commands enter through `MagicDeskRuntime`. The
  runtime service is the availability and ownership boundary;
  `DesktopRuntimeBridge` remains the lower-level gateway that dispatches a
  command to the currently registered host on the main thread. Self-tests may
  address that gateway directly when the gateway itself is the subject under
  test.
- Platform phone-UI adapters receive the active desktop display ID with a
  phone-screen request. They do not discover session state through
  `DesktopRuntimeBridge` and publish state changes through the runtime rather
  than reaching a desktop Activity.
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
through both the desktop task controller and mouse input. It also switches the
pair twice as true-fullscreen tasks and verifies that neither task becomes
freeform while the Alt+Tab panel is open or after focus changes. It restores and
closes one task, then verifies that the fullscreen survivor still receives real
injected text. Input assertions wait for the current InputDispatcher focus
state rather than a fixed transition delay. InputDispatcher frames are
normalized from the display's natural coordinates into its current rotation,
so the same PHONE scenario runs in portrait and landscape. The test also
requests the native horizontal resize cursor and verifies WMShell's transition
trace when that firmware trace is available.

The simulated target owns its display through a Binder-owned shell stream;
closing the stream or losing its owner closes stdin, runs a shell `trap`, and
restores the prior setting. Its test deliberately closes that lease once while
the desktop and a fullscreen fixture are still alive. It verifies that the
runtime and organizer-owned task area stop and that a surviving fixture is
never left freeform on display 0. The external target selects the existing
wired or wireless transport automatically and never treats the physical
display or its unrelated tasks as test-owned. If a wired test temporarily
enters desktop mode from mirror mode, cleanup restores mirror mode. An existing
Miracast transport remains connected. The phone target uses the normal local-
desktop navigation and cleanup path. Each target closes only the MagicDesk host
and test fixtures that it created. Cleanup closes the host before removing its
fixture tasks so SystemUI can reconcile live task IDs instead of retaining
references to tasks that the test already destroyed. The phone navigation
guard is released even when task reconciliation reports a failure; the pending
marker remains for a later recovery attempt.

Task placement is selected by the display driver rather than by individual
launch call sites. Wired, wireless, and simulated desktops use the target
display's default task area. Drivers with root-task transfer move a running
task between displays while it is hidden and fullscreen, then reveal it through
a target-local freeform transition. This gives WMShell an authoritative mode
boundary on the destination so caption surfaces and input windows acquire the
correct display. The simulated driver deliberately uses this same path to
model external-display window behavior without connected hardware. The phone
desktop creates a shell-owned task area as the top child of Android's default
task container before launching its host and starts `DesktopActivity` directly
inside it. Keeping the session inside that container lets SystemUI place later
caption menus and other transient task decorations above it. It also avoids a
cross-root host transition that would resume and raise the phone control panel.
The fullscreen MagicDesk host is the bottom task in the session area. Its
freeform windows and lone fullscreen tasks are siblings above it; the managed
multi-fullscreen stack is a nested child of the same session area. Child
cleanup therefore releases its tasks back to the session parent before the
session itself reparents owned live tasks to Android's default area as
fullscreen. Android 16 may still create its native desktop wallpaper in
display 0's default area, but the session child remains above that task instead
of replacing the host. Production launches, existing-task moves, and self-test
fixtures resolve the same display policy.
The shell observer also reports whether the focused phone task belongs to the
session area. This gates the overlay taskbar without changing its normal
fullscreen or auto-hide policy: the taskbar disappears while an ordinary phone
task is brought forward through Android UI and returns with the desktop plane.
External-display taskbars are unaffected.

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

The shell task observer exposes an optional self-test guard. While a test is
active, every task callback captures a bounded `getAllTasks()` snapshot tagged
with the current test stage. A pure analyzer checks the desktop host, fixture
display and windowing mode, HOME visibility, one-way task transitions, and
windowed/fullscreen visibility continuity. A visible freeform fixture with a
hidden desktop host is an error on every target; this also detects a native
desktop area taking ownership of the phone screen. No snapshots are taken
during normal desktop operation, and the guard uses neither polling nor timing
guesses.

A separate one-shot launch probe captures the first
`onTaskMovedToFront` configuration, so the test distinguishes a true initial
freeform launch from a fullscreen task that is corrected after it becomes
visible. The same probe verifies a direct fullscreen-phone to
freeform-external move.

The desktop uses one `WindowMetrics`/WindowInsets viewport model on every
display. On display 0 it stays below Android system bars. A dedicated external
display normally reports zero system-bar insets and fills the panel. There is
no separate phone implementation of the desktop.

The taskbar is a display-scoped application overlay. It remains above freeform
tasks, hides for an unrelated true-fullscreen task, and returns for the desktop.
Its shared controller measures the actual task viewport on every display and
reserves one slot for an overflow menu when task or pin icons no longer fit.
Overflow entries retain the same exact-task actions and context targets as
their ordinary taskbar icons; screen drivers do not implement separate sizing
or task-switching behavior.
The phone desktop also exposes the hidden taskbar through a touch edge gesture.
It uses Android's configured edge and touch slop, is scoped to display 0, and
feeds an explicit reveal state into the shared controller. The taskbar is
dismissed by the next taskbar action or outside touch rather than by a timeout;
the blocked SystemUI Recents gesture is not intercepted or re-enabled.
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
timing, and the Fill display policy. Files and `.desktop` shortcuts under
`/storage/emulated/0/Desktop`, system-managed widget bindings, taskbar pins,
desktop-item placement, application window state, and recent-app history are
global across displays.
Desktop items and freeform windows store fixed-point relative anchors rather
than monitor pixels, so the same layout follows the user between the phone, a
tablet, and every monitor while adapting to each viewport.

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
in any directory shown by built-in Files. `Type=Link` holds a local folder URL.
`Type=Application` stores standard `Name`, `Icon`, and `Exec` fields plus a
full Android Intent URI and launch-mode metadata in `X-MagicDesk-*` keys. The
Intent URI preserves extras, categories, flags, actions, and explicit
components and always takes precedence over its executable `am start`
fallback, preventing duplicate launches. An entry without an Intent or a
default Android launch executes `Exec`.

Every launch surface converts the entry into one immutable
`DesktopLaunchRequest`. `DesktopLaunchCoordinator` owns the shared sequence of
capability validation, optional Android-task preparation, and command
delegation. `DesktopSessionLaunchContext` maps that sequence onto the live
desktop's existing `AppTaskController`; `StandaloneDesktopLaunchContext` maps
the same request onto a regular Files Activity. Neither context reimplements
request resolution or backend selection. The coordinator deliberately leaves
the established WMShell transition controllers unchanged.

`DesktopExecRunner` owns the execution-backend boundary. Android shell is the
default backend;
`X-MagicDesk-ExecBackend=termux` selects Termux explicitly. Unknown backend
names invalidate the entry instead of silently running a command in the wrong
environment. `Terminal=true` opens shell commands in the built-in Console and
foreground Termux commands in a named Termux session. A future PTY-backed
Console remains an implementation of the shell backend and therefore does not
require another Desktop Entry format or migration.

`DesktopLaunchIntegrationRegistry` is intentionally a small in-process list,
not a plugin framework. An integration recognizes an Android companion target,
contributes its default `DesktopExecSpec`, and may prepare that command before
delegation. The coordinator contains no Termux:X11 package checks. A composite
request with both an Android target and `Exec` first prepares the normal
Android task, then runs its companion command.

`DesktopExecTemplate` expands the supported Desktop Entry file, URI, name,
icon, and source-file field codes. `DesktopLaunchArguments` remains independent
of Android UI classes; `DesktopDragLaunchArguments` is the drag-and-drop
adapter used by Desktop and Files. Commands without field codes retain raw
shell syntax, while expanded values are tokenized and shell-quoted. `Path` is
validated once and transported through `DesktopExecSpec` to either Console,
the shell process, or Termux.

Backend capabilities describe background, terminal, working-directory, result,
and terminal-host support. `DesktopExecSessionTracker` keeps only a bounded
observational state for delegated commands. It provides stable IDs and
diagnostics but does not own, kill, or recreate external Termux or X11
processes.

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
Built-in Files uses the same `DesktopEntryFile` parser outside the fixed
desktop root, so a `.desktop` shortcut can be kept and opened from an ordinary
folder without introducing a second shortcut model.

`AddWebShortcutActivity` is an explicit Android Share target rather than a
launcher-shortcut interceptor. It accepts only validated HTTP(S) URLs, asks the
user to confirm the display name, and writes the same standard `Type=Link`
Desktop Entry consumed by Desktop and Files. Opening that entry resolves the
current Android browser and then uses the normal desktop application-launch
path; when Android still needs the user to choose a browser, its resolver is
opened on the same display.

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

`FileOperationCenter` owns copy, move, and delete at MagicDesk process scope.
Files windows subscribe only to immutable progress snapshots, so closing the
window does not cancel a remote operation. The process Binder remains the
remote owner: process death still cancels work, and a disconnected shell turns
the active snapshot into a bounded failure rather than leaving a permanently
busy UI. Imports from external `content://` providers remain Activity-scoped
because their temporary drag permission belongs to that UI interaction.

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

The current-folder name filter operates only on the already loaded page set;
`Ctrl+F` changes only the local Files presentation. Recursive name search is a
separate explicit action. `ShellFileSystem` walks without following symbolic
links, returns bounded batches through a typed callback, and cancels on request
or Binder-owner death. It creates no persistent index or idle scanner. Each
Files window also owns a shell-side `FileObserver` for only its current
directory. Callback bursts are coalesced into one posted reload without a
polling interval or guessed delay; manual refresh remains available when a
filesystem cannot be observed.

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
can be prefilled with the current directory. Process-local file drags dropped
on its input insert normalized, shell-quoted paths but never run a command.
Console can open its current directory in Files, and selected output is treated
as a path only after `ShellFileSystem` verifies the resolved absolute target.
File completion lists the exact parent directory through the typed filesystem
API instead of parsing shell completion output. Optional Termux integration uses
Termux's documented `RUN_COMMAND` intent and permission; it is not required by
Files. The normalized directory path becomes a stable Termux shell name, and
the `no-shell-with-name` creation mode atomically selects that session or
creates it when absent. MagicDesk does not mirror Termux's session registry or
force an existing shell back to its original working directory.
Optional Termux:X11 integration uses the same permission boundary. MagicDesk
intercepts the ordinary default launch of the exported Termux:X11 viewer, then
prepares it through the same `AppTaskController` path as any other application.
It starts the configured X server command only after that task is ready, or
uses Termux:X11's loopback handshake to reconnect the prepared viewer to an
existing server. The viewer therefore remains a single Android task governed
by normal window state, focus, taskbar, and session parking. There is no
separate Tools action, fixed startup delay, or duplicate server process.
MagicDesk neither embeds the GPL-licensed X server nor models individual X11
client windows as Android tasks. Closing or parking the viewer does not claim
ownership of the independently running X server.
A `Type=Application` entry with a Termux:X11 Android package, no Android Intent,
an `Exec` command, and `X-MagicDesk-ExecBackend=termux` uses the same lifecycle
with the entry's command and requested window mode. The ordinary Start icon
continues to use the global command from Settings. Desktop Entry files are the
launch-preset representation; MagicDesk does not maintain a parallel X11
profile database. Creating the default Termux:X11 desktop shortcut captures
the current Settings command in that file, so later global changes do not
silently alter an existing preset.
The user-visible file format and examples are documented in
[Desktop Entry files](desktop-entries.md).
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
immersive mode restores the prior desktop geometry. Per-task transition state
distinguishes entry from restoration so a firmware-driven early return to
nominal freeform cannot complete or submit the restore twice. When that early
return omits WMShell's native decoration, the task is hidden, passed through a
real mode boundary, and revealed with its saved bounds; the Activity instance
and display stay unchanged.

`ShellPreparedTaskTransition` is the single owner of the hidden preparation,
final reveal, and rollback transactions used by freeform rebuilds and task
moves. It also atomically detaches a task from MagicDesk's organizer-owned
fullscreen parent while restoring final freeform bounds. Higher-level
controllers retain lifecycle policy; interactive drag, resize, and focus never
pass through this prepared-state mechanism.

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

The mouse helper does not capture a physical source merely because its process
is alive. Capture begins only after the virtual pointer has appeared in
EventHub and the shared input-routing session reports ready. On shutdown, a
native acknowledgement confirms that all sources have been released before
that session closes. The acknowledgement is an ordering barrier, not a pacing
delay; a bounded timeout exists only for a failed helper.

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

A Shizuku UserService is an `app_process` with an Application context but no
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

`CommandConsoleActivity` is a permission-protected, multi-instance desktop task over the
existing `ShellAccess` connection. Each Activity owns one
`ConsoleShellSession`, a process-local command history, current-directory
state, and a selectable stdout/stderr transcript. The session uses one
long-lived `/system/bin/sh`; private marker records delimit commands and update
the prompt without being shown to the user. Running `exit` or closing the
Activity closes that shell. Initial commands supplied by Files are displayed
for review and are never executed automatically.

`TaskManagerActivity` consumes the existing `TaskRepository`; it does not own
another task-stack parser or windowing policy. Focus, task close, and explicit
force-stop therefore use the same validated operations as the taskbar. A log
action launches `AppLogViewerActivity`, whose lifecycle-bound owned stream runs
`logcat` with a numeric UID filter. The viewer keeps a bounded transcript and
closing it closes the remote process. Arbitrary command entry remains exclusive
to Console.

## Implementation Constraints

These constraints define the supported implementation paths:

- A custom caption overlay cannot stay atomically attached to a task leash.
- Public freeform launch from an ordinary app UID is normalized to fullscreen
  on the verified firmware.
- Custom task areas are limited to display drivers whose lifecycle explicitly
  supports them. The phone and simulated drivers own their respective session
  and transient areas; physical desktop drivers use their default task area.
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
- Phone-screen-off process protection uses only the transient vendor
  service-working heartbeat; no persistent freezer whitelist is installed.
- ZTE audio source `80` is a `MediaRecorder` path. Replacing it with
  `AudioRecord` fails in AudioFlinger even for a privileged UserService.

Additional vendor-level evidence is preserved in
[Nubia vendor interface audit](nubia-vendor-audit.md).

Maintenance follows the same ownership rules. New external implementations
belong in dedicated `platform/` or `soc/` packages; the broad root package is
split only when a new independently owned subsystem provides a real boundary.
Large shell, input, and Activity orchestration classes are divided by resource
ownership rather than file size. Private Android APIs remain isolated behind
capability-checked adapters and fail closed. Changes to task-display-area
launching, shell task observation, input bridges, or the UserService require
phone, simulated, and relevant physical-display self-tests because host-only
tests cannot prove firmware behavior.

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
