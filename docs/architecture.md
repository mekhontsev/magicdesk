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
9. User actions, tests, MCP, and Android system agents share one typed
   automation gateway instead of duplicating task or session policy.

MagicDesk does not register a competing task organizer, host applications in
surrogate activities, draw replacement captions, patch SystemUI, invoke `su`,
or require a Magisk module.

Dependencies point in one direction:

```text
activities, desktop UI, and automation adapters
        |
shared automation gateway + controllers and session orchestration
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

`DesktopInputRelaySession` is the single lifecycle owner for the mouse helper,
keyboard helper, and Android display-routing lease. `DesktopMouseBridge`
creates one stable virtual pointer for every external desktop session and
routes it independently from physical input. The phone touchpad and automation
therefore use the same relative pointer transport on every supported Android
platform. A platform input-relay policy may additionally select physical
EventHub devices marked `CURSOR | EXTERNAL`; only then does the native helper
grab and forward their motion, wheel, and button state through that pointer.
On RedMagic, `BTN_RIGHT` is the deliberate exception: the helper consumes the
physical sequence and requests one display-targeted Android secondary click,
bypassing the firmware conversion to Back.

The pointer helper starts passively. The runtime first waits until its virtual
mouse is visible in EventHub. It then prepares any optional vendor pointer
viewport, establishes Android's display associations as the final InputReader
configuration change, and only then enables physical capture. Capture acquires
every neutral source immediately; it does not expose a first physical motion
report as an implicit vendor handshake. Hot-plugged sources enter the same
neutral-state protocol. Absolute-pointer preparation belongs to that same
routing transaction, but runs only when the selected pointer driver exposes
that capability. Nubia's oneway viewport command is issued
synchronously so capture cannot overtake the service-side request; firmware
may apply the accepted update while the desktop surface is becoming visible.
Teardown reverses that order: the helper
releases every `EVIOCGRAB` and acknowledges completion before the routing
session removes its associations. A helper restart repeats the same protocol
instead of inheriting capture permission from a destroyed virtual device.
After a desktop display is removed, the runtime finalizes the phone pointer
viewport from the external-ownership transition itself. Configuration broadcasts
are not used as an ordering barrier because firmware may deliver one before the
display ownership callback.
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
virtual desktops. `DesktopInputRelaySession` orders virtual-device readiness,
the routing lease, capture, source refresh, and reverse-order teardown;
`KeyboardShortcutWatcher` only decodes shortcuts outside that transport
lifecycle. There is no separate vendor input-panel owner.
`DesktopInputRelayPolicy` declares keyboard and mouse relay independently.
The routing session waits for and associates only the virtual device classes
selected by that policy; `PlatformPointerDriver` remains a separate capability
and is never inferred from relay availability.

MagicDesk uses one phone-side `MagicDeskTouchpadActivity` for every external
transport. Touch
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

### Keep vendor input APIs behind a capability boundary

MagicDesk does not package or link a Nubia binary library. The vendor surface
used for desktop input consists of private Binder methods added to framework
interfaces on RedMagic firmware:

- `IInputManager.getMousePosition`, `setMousePosition`, and `sendMouseCmd`;
- `IDisplayManager.getFocusMirrorWindow`;
- `IDisplayMirrorWindow` composing, text, deletion, and key dispatch methods;
- the wired-only `dumpsys display dmctrl inputSource` control.

These signatures are resolved reflectively inside the shell UserService and
are never exposed as a generic command surface. Diagnostics and the self-test
inspect the absolute-pointer and mirror-text signatures without invoking them.
The report also retains the last mirror-text runtime result from an explicit
keyboard session. No focused projected window is reported as not tested rather
than as missing firmware support. A missing optional package or method disables
the corresponding operation rather than changing unrelated device state. In
contrast,
`libmagicdesk_keyboard_bridge.so` and `libmagicdesk_uinput_bridge.so` are
MagicDesk-owned native helpers compiled from repository C sources by every
local and CI build.

### Do not draw replacement application captions

An application overlay cannot share a task's SurfaceControl leash or transition
atomically with WMShell. A separately drawn caption trails live movement,
maintains a different Z-order, and can leave controls above the wrong window.

MagicDesk instead keeps native WMShell captions visible. Application overlays
are reserved for transient shell-owned UI such as Start, context menus, and the
notification center. The persistent taskbar uses its bounded Activity plane.

The [Chrome custom-caption input investigation](chrome-custom-caption-investigation.md)
documents why a shell-side gesture-transfer or synthetic-click layer cannot
reliably repair a firmware caption that consumes application exclusion regions.

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

### Use one fullscreen topology on every desktop

Every configured target uses the same independent topology. The HOME host and
freeform applications remain in the display's ordinary root workspace.
Every fullscreen application is placed in its own organizer-created ordering
plane and retains that plane for its complete fullscreen residency, so focus
never reparents it during a fullscreen peer switch. The topology does not
branch on display kind or vendor.

On display 0 MagicDesk is the active HOME surface. Android's WMShell normally
starts `DesktopWallpaperActivity` above Launcher when its freeform mode is
used; that fullscreen activity exists specifically to hide Launcher. While a
phone desktop is active, the shell activity-start policy blocks only that
exact SystemUI component and removes one instance that may predate observer
configuration. Freeform tasks otherwise remain ordinary Android tasks in the
default root workspace, and the taskbar retains its own bounded plane.

Taskbar, task overview, MCP, and Alt+Tab submit the semantic target to the same
`DesktopTaskController` focus gateway. The shell resolves the complete live
fullscreen set, creates a plane for any fullscreen task not yet represented,
and atomically orders the selected task and its existing plane.
Steady-state switches do not change task mode, bounds, parent, or hidden state.
UI and automation controllers never construct their own fullscreen stack or
transition sequence.

The process boundary is `DesktopWorkspaceCommand`, with distinct activate,
demote, desktop presentation, workspace restore, and session restore
operations. The command carries a named back-to-front plan; it is never
interpreted as an operation merely from the shape of an integer list.
`ShellDesktopWorkspaceCoordinator` serializes the commands for the configured
display and is the sole adapter from logical workspace intent to
`ShellFullscreenTaskArea` and ordinary task ordering. The application retains
UX intent such as taskbar concealment and persisted restore state; shell owns
the live organizer topology and completes mixed fullscreen/freeform ordering
from framework task state.

Task selection has two explicit z-order operations:

- **Activate** brings a selected task to the front of its compatible desktop
  hierarchy. The task is effective foreground only when it is visible,
  focused, and has no managed application above it. Alt+Tab, task overview,
  taskbar selection of a background or covered task, and MCP focus all request
  this operation through the common gateway.
- **Demote** rotates the currently active task behind the next MRU application
  without minimizing or hiding it. Taskbar selection of the already-active
  task requests this operation. With no application peer, the desktop host is
  brought forward and the application remains live underneath it.

Occlusion is not minimization. A fullscreen task covered by another fullscreen
task or a freeform window remains fullscreen. Activating it moves the blockers
below its stable plane while preserving their mutual order; demoting it reveals
that previous stack without a repair transition. Activating a freeform task
places it above the current fullscreen plane. Neither operation changes task
mode, bounds, parent, or hidden state.

`ShellFullscreenTaskArea` and
`ShellFullscreenTaskPlanes` own plane creation, ordering, restore, and removal.
Each plane ignores child orientation requests: the desktop session owns the
viewport orientation, while Android may rotate or letterbox application content
inside the fixed fullscreen plane.
No delayed mode repair or fixed post-transition delay is involved. On affected
external firmware,
workspace command completion captures a task-sample generation and a
SurfaceFlinger input-window generation before submission. It then waits for
both event sources and performs one InputDispatcher check. A missing input
target gets one ownership-aware repair followed by one more event-driven
commit confirmation.

Application-driven restores are completed by the observer before their result
crosses Binder. A fullscreen task leaves its plane through
ActivityTaskManager's existing-task freeform launch path. Every plane contains
one retained standard anchor task, which keeps the source hierarchy valid while
framework root selection moves the application task. The now-idle plane is
made non-focusable and reused by a later fullscreen task, avoiding repeated
organizer creation and deletion. A new anchor launches behind the foreground
task; its structural opening therefore cannot race the application's
fullscreen entry or acquire user focus. Session teardown deletes the owned
planes and their anchors. If Android removes a display first and migrates an
anchor to the phone, the plane owner removes that exact task by saved ID and
component.

## Modules

| Component | Path or package | Responsibility |
| --- | --- | --- |
| Main application | `io.github.mekhontsev.magicdesk` | Phone control, desktop shell, taskbar, setup, diagnostics, and runtime service |
| Task transfer boundary | `DesktopTaskTransfer` | Moves running tasks directly between display root workspaces |
| Phone desktop wallpaper policy | `ShellPhoneDesktopWallpaperPolicy` | Keeps the MagicDesk HOME surface visible below standard freeform tasks |
| Fullscreen topology | `ShellFullscreenTaskArea` | Owns per-task fullscreen planes on every desktop target |
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
  window owns one `ConsoleTerminalSession`, one terminal emulator, and one
  lifecycle-bound PTY; no process-global terminal state is shared between
  Console windows.
- `SettingsActivity`, `SettingsView`, and `MagicDeskSettings` own persistent
  user-selected desktop behavior. They are separate from the transient System
  panel, which remains a quick control surface for the active session. Settings
  also provides the stable entry points for device setup, diagnostics, and
  About, keeping the phone control surface focused on session actions. The
  activity is exported only behind `MANAGE_ACTIVITY_TASKS`, allowing the shell
  launch backend to create its desktop task without exposing it to regular
  applications. `BuiltInDesktopAppCatalog` is the single allowlist that
  separates user-facing MagicDesk tasks such as Files, Settings, and
  Diagnostics from shell
  infrastructure. It also records whether an internal window can have multiple
  tasks, appear in the launcher or taskbar pins, and share package-level window
  state. Settings is a singleton reusable task with compact centered default
  bounds. A single constrained, scrollable `SettingsView` uses the same dense
  visual language on phone and desktop. The phone opens it normally, while the
  desktop task controller launches the same Activity in a dedicated reusable
  freeform task.
- Diagnostics follows that same built-in-window path on a desktop. It therefore
  cannot replace `DesktopActivity` in the host task or hide every application
  merely because a report was opened. Phone-side callers may still open the
  same Activity normally in their current task.
- `DesktopActivity` is the concrete desktop Activity.
  `DesktopShellActivity` composes controllers and forwards Android callbacks;
  it does not own every feature directly.
- `DeviceSetupActivity`, `DeviceSetupManager`, and `DeviceSetupView` own the
  one-time platform audit and provisioning flow.
- `MagicDeskRuntimeService` composes the persistent notification and
  process-level runtime without duplicating subsystem state. Other components
  use the process-local `MagicDeskRuntime` facade instead of depending on the
  Android Service implementation. The service attaches a package-private
  backend for its lifetime; absent-runtime calls have explicit safe defaults,
  and a stale service cannot detach a newer backend instance.
  `RuntimeDesktopSessionCoordinator` owns desktop-display identity, unexpected
  display removal, retained phone-task recovery, and one-shot HOME-lease
  reconciliation. It consumes one immutable
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

### Automation boundary

- `DesktopAutomationController` is the single typed action boundary for local
  automation. It validates JSON arguments, delegates to the existing session,
  task, window, capture, and UI controllers, and returns a uniform
  `DesktopAutomationResult`. It does not implement a second desktop policy.
- `DesktopAutomationStateReader` exposes immutable snapshots of runtime,
  displays, tasks, launchable applications, MagicDesk-owned UI, diagnostics,
  self-test state, and the actual input window above each focused application.
  `DesktopWindowObservation` joins that shell-owned input state with bounded
  crash/ANR state from the existing task observer, so a surviving
  `ActivityRecord` is not mistaken for a usable application behind a system
  error dialog. Task and application queries share bounded filtering and
  cursor pagination.
- `DesktopAutomationEventJournal` retains at most 256 process-local structured
  events and provides the condition variable used by event-driven automation
  waits. `DesktopAutomationTaskEventTracker` derives task lifecycle, display,
  focus, top-activity, mode, bounds, and visibility events from snapshots
  already delivered by `DesktopTaskWatcher`; it does not register another task
  observer. Compatibility reports include at most the newest 64 events within
  a 24 KiB section, without adding persistent telemetry.
- `DesktopWindowTransitionProvenance` correlates semantic MagicDesk requests,
  application immersive callbacks, and activity-handoff corrections with those
  existing mode-change events. Uncorrelated supported mode changes are labeled
  `framework-external`; no stack trace, timer, or additional observer is used.
- `DesktopAutomationUiRegistry` is populated by the controllers that own live
  desktop `View` objects. `DesktopUiGateway` is still the only bridge to the
  Activity and marshals snapshots and semantic actions onto the UI thread.
  Invoking an element delegates to its existing click or long-click listener;
  automation therefore cannot grow a second Start, taskbar, or menu policy.
- `DesktopAutomationTraceManager` defines a trace as a baseline in the same
  bounded event journal plus final state and task snapshots. It adds no task
  observer and no persistent log. Exact UI waits use journal notifications and
  a bounded recheck for `View` state changes that Android does not publish.
- `MagicDeskMcpRuntime` is owned by `MagicDeskRuntimeService`. When explicitly
  enabled, it starts one bounded Streamable HTTP server on literal
  `127.0.0.1:8765`; stopping the runtime closes the listener and workers.
  A user launch may first create the service in automation-only mode so an MCP
  client can connect before Shizuku is available. That mode owns only the
  foreground service and MCP transport. The same service is promoted in place
  after setup authorization; desktop, task, input, and platform runtimes are
  not initialized by the automation-only start.
  `MagicDeskMcpBackend` only maps MCP tools and resources to the shared action
  and state boundary. Developer input, self-test, force-stop, broadcast, and
  service tools require a separate setting and disappear when that setting is
  disabled.
- `AndroidIntegrationGateway` is the single application boundary for typed and raw
  Android intents, semantic URI/file/share operations, published shortcuts,
  notification `PendingIntent` actions, Activity results, and external App
  Functions. Desktop UI and MCP adapters both enter this boundary.
  `AndroidIntegrationRequest` owns Intent parsing and validation;
  raw Intent URIs are an input form rather than a parallel executor. Direct
  launches cross the Shizuku task-launch boundary as full Parcelable Intents,
  preserving `ClipData`, grants, and typed extras. Discovery and App
  Function framework calls have shell-side adapters, but desktop placement and
  task reuse still enter the production launch coordinator.
  `AndroidActivityResolution` distinguishes a real handler from Android's
  synthetic resolver without relying on an internal class name. Concrete
  handlers take the direct shell path unless chooser or result semantics need
  the app-identity relay; unresolved choices stay implicit and use that relay.
  Chooser and result requests retain nested targets in
  `AndroidActivityRelayStore`; shell receives
  only an opaque id and the app-identity relay satisfies Android 16 redirect
  hardening without serializing away grants or typed extras. Relay ids use an
  atomic `ready -> claimed` lifecycle: Android task handoff may instantiate the
  relay Activity twice, but only the first instance can execute the payload.
  Claimed tokens remain in the same bounded store without retaining their
  Intent payload. The exported
  relay Activity requires `MANAGE_ACTIVITY_TASKS`, so only the same privileged
  task-launch boundary can consume those one-shot ids. Broadcast and service
  starts are developer-only because they have no visible UI.
- `AndroidLaunchSpec` keeps the task's semantic target separate from the
  explicit Activity used to execute a launch. `AppTaskController` derives task
  reuse identity from the concrete component for direct Intent launches.
  Relayed concrete handlers use package-scoped task identity, so an existing
  application task is normalized before the separate relay delivers its
  action; the relay component never becomes the target task identity.
  Published shortcuts follow the same separation:
  Android may redirect their metadata Activity to another Activity in the same
  app, so both fresh-task observation and task reuse are package-scoped while
  execution remains bound to the shortcut id. The component observed on the
  created task, rather than the optional published metadata component, becomes
  the mode-guard identity. Direct fresh Intent tasks receive the concrete
  Intent; an exact reused Intent task receives it as a task action.
- Direct Files, shell, and Terminal automation has a second independent
  setting.
  `DesktopAutomationFileTools` delegates to the same typed `ShellFileSystem`
  service as built-in Files. `DesktopAutomationConsoleSessions` owns a bounded
  set of lifecycle-scoped `PersistentAutomationShellSession` instances and
  closes them with the MCP backend. These marker-delimited non-terminal shells
  exist only to return structured command output, exit status, and current
  directory to MCP; they are not a second user-facing Console implementation.
- `ConsoleTerminalRegistry` holds weak, process-local references to live
  user-facing Console windows. It exposes immutable task, display, PTY,
  dimensions, foreground-process, title, directory, viewport, and transcript
  state without owning an Activity or shell.
  `DesktopAutomationTerminalWindows` maps the gated MCP
  `terminal.*` tools onto that registry and the normal built-in-window launch
  path. Terminal input therefore reaches the real PTY directly instead of
  synthesizing pointer coordinates. Closing the MCP server closes only its
  marker-delimited headless sessions, never a user-owned Terminal window.
- `DesktopAutomationCapture` resolves the active display and asks the shell
  service for either one PNG pipe or one bounded pixel batch. Image bytes are
  returned as MCP image content and are never staged in a filesystem cache.
- `MagicDeskAppFunctionService` is the Android 16 system-agent adapter. Android
  protects it with `BIND_APP_FUNCTION_SERVICE`; resource gating disables the
  component on Android 15. It exposes only a small non-developer subset and
  executes it through `DesktopAutomationController`.
- App Functions never accept arbitrary shell commands. MCP exposes shell and
  broad filesystem operations only behind its explicit Files, shell, and
  Terminal setting. Transport authentication, optional tool gates, platform
  permissions, and action validation remain independent checks.

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
  `FrameworkTaskObservationSource` centralizes the supplemental task snapshot
  and its typed observations.
- `ShellWindowedTaskLauncher` owns every fresh windowed launch, independent of
  display type. It observes the new task through the persistent framework
  listener and joins mode and bounds to the task's original OPEN transition;
  every target launches into Android's standard root workspace. The standalone
  shell command remains a diagnostic entry point and is not used by the
  application launch path.
- `ShellActivityStartController` is MagicDesk's single owner of Android's global
  activity-controller slot and dispatches starts to the external-migration and
  windowed-startup policies. `ShellTaskActivityModeGuard` follows only
  activity handoffs inside a task observed as freeform. If such a handoff
  changes that task to fullscreen without a client immersive request, it uses
  the last observed freeform bounds to restore the same task. User fullscreen,
  independent new-task launches, and application immersive requests are not
  corrected. The policy is event-driven and has no package allowlist or
  guessed startup delay.
- `ShellProcessFailureTracker` passively correlates framework crash and
  ANR callbacks with the latest typed task snapshot for the active desktop
  display. It preserves Android's normal crash/ANR response and reports only a
  bounded process summary, task/display context, and top activity; third-party
  stack traces and ANR process dumps do not cross into application diagnostics.
- `ShellDesktopFocusController` handles a Nubia secondary-display defect where
  task focus changes but the InputDispatcher window remains stale. It reports
  only confirmed mismatches. The UI process then relayouts the existing,
  non-focusable desktop host across a committed frame, which makes WMS
  recompute its focused window without moving tasks or synthesizing input.
- `ShellFreeformTaskCleanup` remembers freeform application tasks observed
  during the active desktop session. If one disappears, it verifies that no
  live task remains and removes only a Recents entry with the same task ID,
  package, and display. This prevents stale `DesktopTaskView` entries without
  persistent recovery state or changes to unrelated Recents entries.
- `DesktopTaskController` orchestrates native task transitions as an instance
  owned exclusively by `RuntimeDesktopTaskCoordinator`. It contains no static
  active-controller reference; pure task classification helpers remain static.
  Ordinary task close uses Android's task lifecycle through `TaskRepository`.
  A topology-owned fullscreen plane close first commits survivor focus and then
  removes the background task, while package force-stop first commits the
  surviving desktop task and only then stops the package.
  Pre-focus host relayout is enabled only by the selected windowing driver.
- `DesktopTaskParkingController` continuously derives a lightweight workspace
  snapshot from the task state already read by `DesktopTaskController`; it does
  not run a second task poll. A normal desktop close refreshes that snapshot
  before external tasks are parked on display 0. Host replacement, vendor mode
  exit, and sudden display removal preserve the latest complete snapshot before
  session teardown, including when the disappearing display can no longer be
  queried. A later desktop host restores only the same still-live task IDs on
  external, simulated, or phone desktops. Mode, relative bounds, visibility,
  and stacking order survive without relaunching tasks Android or the user
  closed.
- `ShellExternalTaskMigrationGuard` intercepts launcher requests for a task
  hosted on an external desktop. It also observes already completed system
  moves, including `Alt+Tab`, and scans display 0 when protection starts and
  after task-stack changes. Every observed freeform task is normalized while
  an external session is active. This invariant applies to
  MagicDesk and third-party tasks alike, so display 0 never retains transient
  freeform state from those transitions.
- `DesktopWindowTransitionController` owns shortcut and immersive policy. It
  emits immutable `DesktopWindowTransitionRequest` values through
  `DesktopWindowTransitionGateway`; `DesktopTaskController` is the sole adapter
  from those semantic operations to the existing task watcher. A declined
  request completes with an explicit failure; active-session UI never bypasses
  fullscreen-plane ownership through a raw repository command.
  `ShellPreparedTaskTransition` remains the lower-level owner of hide,
  hierarchy change, reveal, and rollback, so platform extensions cannot fork
  the proven transition mechanics. Bounded routing counters in diagnostics
  distinguish accepted and declined gateway requests. Explicit raw MCP
  operations remain a separate developer surface and identify themselves as
  raw transitions.
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
- `PhoneTouchpadReconciler` keeps the requested phone touchpad visible after
  display changes without owning desktop-session policy.
- `AppTaskController` and `AltTabController` coordinate task actions,
  Show Desktop, restoration, and exact-task
  switching. `AppTaskController` has one UI lifecycle for built-in and regular
  window launches. `AppShortcutRepository` accepts only actions returned by
  Android's published shortcut service; static manifest parsing only enriches
  icons. Dynamic, pinned, cached, and manifest-published sources share one
  immutable action model. `ShellShortcutGateway` resolves a system
  `PendingIntent` under shell identity, while the visible app process sends it
  through `IActivityLaunchCallback` with the prepared display, bounds, and task
  options. The private shortcut Intent is never parsed or copied. Fresh launch
  observation and task reuse are package-scoped because the optional metadata
  Activity can redirect within its app; execution remains bound to the exact
  shortcut id.
  `WindowedAppLauncher` owns fresh launch/reuse selection and
  delegates fresh launches to the active persistent shell task observer.
  `ExistingTaskController` performs only task discovery and normalization. A
  single `WindowedTaskLaunchLease` spans each operation so startup-window
  protection and phone-touchpad preservation cannot be entered twice by the
  launcher and reuse path.
- `ShellFullscreenTaskArea` gives each fullscreen task one stable,
  independently ordered plane until it restores or closes.
  A cold fullscreen launch reserves an anchored plane first and supplies its
  token through `ActivityOptions.setLaunchTaskDisplayArea`, so the task's first
  observable parent and mode are already final. A live freeform task entering
  fullscreen uses the separate existing-task transition and preserves its
  Activity instance.
  Application-requested fullscreen uses the same topology without recreating
  its Activity.
  Self-test checks `FULLSCREEN-ALT-TAB-001` through `003` and
  `FULLSCREEN-LIFECYCLE-001` through `006` verify both task modes, real input
  focus, single-task restore and close, direct fullscreen launches,
  system-Back removal, survivor visibility and parent continuity, structural
  task isolation, inactive-area ordering, and abrupt display removal.
  `FULLSCREEN-MIXED-001` additionally verifies the durable
  fullscreen/freeform/fullscreen visual order through the production Alt+Tab
  and task-focus routes. `FULLSCREEN-PLANE-EXIT-001` through `004` additionally
  verify repeatable release to the original freeform parent, while the surface
  probe checks that the desktop remains rendered throughout the operation.
- Fullscreen commands perform caption-source repair only when requested
  by `PlatformWindowingDriver`. Phone freeform cleanup in self-tests follows
  the same platform policy. Shell input recovery calls the selected
  `PlatformPointerDriver`; the Nubia driver alone chooses its firmware-specific
  finger-tool hover event.

### Framework compatibility services

Android release differences and firmware differences are independent axes.
`FrameworkRuntime` resolves one process-wide framework profile and exposes
focused adapters rather than one broad compatibility utility.

- `FrameworkWindowingApi` is the only owner of hidden
  `WindowContainerTransaction` and token primitives. It resolves and caches
  construction, bounds, mode, ordering, parenting, visibility, focusability,
  density, orientation, task-start, and task-removal operations once.
- `FrameworkWindowingCompat` owns release-dependent meaning and polyfills,
  including requested-visible-types and caption-inset strategies. Transition
  code does not reflect optional signatures itself.
- `HiddenTaskApi` owns raw ActivityTaskManager task members and service access.
  `FrameworkTaskSnapshotSource` converts them into the parcelable
  `FrameworkTaskSnapshot` returned through typed AIDL. Application policy and
  recovery code no longer parse `cmd activity stack list` in production.
- `FrameworkInputSnapshotSource` is the only runtime owner of the bounded
  InputDispatcher dump used when no typed focus/cursor API exists.
- `FrameworkInputWindowObservationSource` is the shell-side owner of hidden
  `WindowInfosListener`. It exposes only commit generations: policy cannot
  inspect or reinterpret raw `InputWindowHandle` objects. Workspace focus
  waits on this SurfaceFlinger callback before taking its one-shot
  InputDispatcher snapshot.

Repository isolation tests enforce these ownership rules. Version-specific
member names, WCT class lookups, raw task fields, direct input dumps, and text
production task queries cannot silently spread back into policy code.

`FrameworkTaskObservationSource` is the corresponding dynamic compatibility
service. It combines `TaskStackListener` wakeups with one bounded selected-
display snapshot every 150 ms while a desktop session is active, scanning at
most 16 tasks. One normalized `FrameworkTaskSnapshot` feeds stack reconciliation,
windowing-mode and bounds changes, immersive requests, caption-source
lifecycle, activity handoff protection, ownership reconciliation, and process
failure correlation. Consumers do not start their own polling loops or read
version-specific `TaskInfo` members.

Compatibility report generation may request one separate diagnostic snapshot
through `readDiagnosticTaskSnapshots`. That one-shot call adds task density and
dp configuration to the same typed model, then joins it with bounded launch
provenance and saved window state. It is never called by the 150 ms observer or
ordinary window operations.

Every observed facet records its provenance as `event`, `sampled`,
`event+sampled`, or `unavailable`. The periodic snapshot exists because even
the current framework does not reliably callback organizer-child Z-order,
native freeform bounds, or app-requested system-bar changes. It sleeps
indefinitely outside an active session and an explicit production operation can
wake it immediately; reconciliation reuses the same snapshot and adds no
second task query.

Runtime timing has three explicit mechanisms:

- `EventDrivenWaits` wraps monitor waits released by a concrete callback or
  state publication. These waits consume no periodic CPU while idle.
- `BoundedStateAwaiter` owns polling only where the framework provides no
  reliable callback. Every call declares a semantic reason, deadline, and
  sample interval; self-tests use the same classification.
- `RuntimeDelays` owns intentional non-state pauses such as input gesture
  spacing, supervisor backoff, recording drain, vendor command settling,
  watchdog ticks, and stream heartbeats.

Direct `Thread.sleep`, `SystemClock.sleep`, and `Object.wait` calls are rejected
outside these timing boundaries. Compatibility Diagnostics reports their
runtime counters and the last classified reason. The task observer's 150 ms
fallback remains separately visible in the framework runtime line because it
is a permanent active-session observation source, not a transition delay.
Input-window event registration, callback count, bounded waits, and timeouts
are reported separately as `inputWindowEvents`; they never share that polling
interval.

On frameworks that expose `TaskInfo.requestedVisibleTypes`, the task observer
uses it to correlate application-requested immersive state. Android 15 does not
publish that field through `TaskInfo`; the observation is therefore unavailable
rather than being reported as a synthetic non-immersive request. The task
listener and all other task state continue operating. A policy that needs to
distinguish app-requested fullscreen from an accidental activity handoff fails
open when this observation is unavailable and does not force a window mode.

Caption-inset handling selects the native exclusion operation when present.
On Android 15 it uses the older six-argument local InsetsSource operation; a
newer host running the Android 15 debug profile may bridge that semantic call
through the flags overload with flags set to zero. The source identity still
comes from the task and cleanup still uses the paired add/remove transactions.
The adapter does not register a competing display-insets controller or replace
SystemUI ownership.

`MAGICDESK_FRAMEWORK_OVERRIDE=android15` is a debug-only semantic profile. It
can be combined with the independent `MAGICDESK_PLATFORM_OVERRIDE=android`
selection to test Android 15 framework behavior with the Standard Android
driver on newer vendor hardware. Release builds always detect the live
framework and platform. Future vendor fixtures are added at `PlatformDrivers`,
not as branches in the framework adapter or desktop runtime, and cannot claim
firmware APIs that the host does not expose.

### Platform services

MagicDesk ships one main APK from one codebase. New device support belongs in
runtime capability probes or a focused platform-driver implementation, while
shared desktop, task, window, and input behavior remains platform-independent.
Do not introduce per-model build variants or forks for differences that can be
isolated behind these boundaries.

- `PlatformDrivers` is the single process-start composition root. It always
  creates the Standard Android baseline, then may layer one detected
  `PlatformExtension` over it. `PlatformComponent` makes each override
  explicit: an extension can own projection without replacing windowing,
  pointer, input, phone UI, wallpaper, audio, diagnostics, controls, launch
  targets, or runtime behavior. `ComposedPlatformDriver` uses that declaration
  as the source of truth and rejects a declared component with no
  implementation. `PlatformSelection` records the provider and detection
  evidence for every component. Hardware family names alone do not select a
  vendor implementation. A stock Nubia or REDMAGIC fingerprint or the
  `redmagic.app.manager` service selects the complete Nubia extension. On an
  AOSP-derived ROM for Nubia hardware, passive probes select only independently
  present projection, pointer, mirrored-input, internal-audio, diagnostics,
  and hardware-control components; all others remain on the Standard Android
  baseline. The probes run under the ordinary application UID, do not require
  Shizuku, and do not invoke the detected operations. In particular, absence
  of `redmagic.app.manager` keeps the vendor property writer out of Device
  Setup without suppressing unrelated APIs retained by a hybrid ROM.
  `PlatformDriver` exposes only existing variation points.
  `PlatformWindowingDriver` owns provisioning properties;
  `PlatformProjectionDriver` owns output modes, wireless-launch integration,
  and caption transport; `PlatformPhoneUiDriver` owns phone-screen controls,
  launcher reconciliation, and local-navigation policy;
  `PlatformPointerDriver` owns optional absolute-pointer integration. On Nubia
  firmware this is implemented by `NubiaDesktopPointerDriver`, the MagicDesk
  pointer backend over the hidden vendor positioning API. Physical input
  routing itself stays in the shared Android implementation and uses standard
  port or unique-id display associations. `DesktopInputRelayPolicy`, carried
  by `PlatformFeatures`, independently selects complete keyboard and mouse
  relays; it does not imply absolute-pointer support.
  `PlatformTextInputDriver` owns optional projected-window IME forwarding; and
  `PlatformDiagnostics` contributes only the probes for the selected platform.
  A selected `SYSTEM_CONTROLS` provider identifies the platform integration,
  not every optional hardware control. Nubia cooling settings are read through
  one typed, read-only snapshot shared with the production controller; fan and
  pump control keys and effective state are reported independently.
- `InternalDisplayDesktopConfig` reads Android's live
  `config_canInternalDisplayHostDesktops` resource for compatibility reports.
  It is deliberately diagnostic rather than a launch gate: this resource
  describes the framework's standard internal-display desktop path, while a
  vendor or shell path may still host MagicDesk on display 0 when it is false.
  The actual phone-desktop behavior is verified by the same self-test used for
  other display targets.
- Implementations live in `platform.android` and `platform.nubia`. Shared
  runtime code does not import either implementation; `PlatformDrivers` is the
  single composition point. ZTE-branded devices are not assumed to expose
  Nubia services and use the standard Android driver unless a dedicated,
  verified platform implementation is added.
- Exact tested fingerprints and their confirmed scope live in the declarative
  `assets/compatibility/firmware-profiles.json` catalog, not in driver code.
  Updating confidence therefore cannot change runtime selection or behavior.
  `PlatformCapabilitySnapshot` records stable capability IDs, observed state,
  component provider, provider evidence, and bounded detail. A failed optional
  probe becomes `broken` for that capability instead of aborting the report.
- The human-readable compatibility report and its schema-versioned JSON block
  are generated from the same snapshot. The optional extended vendor probe is
  explicit, read-only, bounded, and never scans user files or installed apps.
  Manual checklist observations are keyed by exact fingerprint and display
  kind, so an OTA cannot inherit a previous firmware's result.
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
  removal semantics. Wired and wireless drivers consume Android's existing
  physical display directly; neither owns the transport lifecycle.
- `DesktopDisplayDrivers` is the only registry for resolving those drivers.
  `DesktopOperations` serializes public session transitions and delegates
  the selected target to the registry.
- `DesktopOperations` remains the compatibility facade used by activities
  and shortcuts. `DesktopSessionTransitionCoordinator` owns activation,
  close, and caption transport sequencing; `SerializedDesktopOperationQueue` provides the
  single ordered executor shared with shell settings and input policy. The
  facade owns neither transition flags nor an executor. Platform projection
  and feature contracts are injected into the coordinator, so a close cannot
  re-enter `DesktopOperations` through a display driver.
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
- `ExternalDisplayController` discovers dynamic display IDs and fixes geometry.
- `DesktopInputRelaySession` owns external input transport and routing;
  `KeyboardShortcutWatcher` decodes shortcuts, and
  `HardwareKeyboardLayoutController` owns layout selection.
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

Every built-in Console window owns a lifecycle-bound `TerminalTransport`, one
native PTY relay, and one interactive shell. `ShellPtyHandle` hosts
`/system/bin/sh` through the UserService and binds its stream to the APK
owner's Binder token. `TermuxPtyTransport` asks Termux's documented
`RUN_COMMAND` service to host the same relay under the Termux UID and connects
it to the window through an authenticated loopback stream. Both transports
create a session leader and controlling terminal, forward terminal bytes,
apply `TIOCSWINSZ`, expose the shell PID, and resolve `/proc/<pid>/cwd` within
the process's own security domain. Closing the window, running `exit`, service
death, or stream failure ends only that PTY and shell. A failed transport is
discarded rather than silently changing privilege or execution backend.

`ConsoleTerminalSession` owns transport and terminal state for one window.
The pinned Termux `terminal-emulator` module parses escape sequences and models
the main screen, alternate screen, cursor, colors, and scrollback. MagicDesk
does not use Termux app session, JNI, or rendering code. Its own
`ConsoleTerminalView` and `MagicDeskTerminalRenderer` provide Android input,
mouse reporting, selection, clipboard operations, resize, and Canvas drawing.
The native relay has a small framed control protocol for input, resize, and
working-directory requests. The Binder transport exposes raw output from its
owned descriptor; the loopback transport frames output and metadata so one
authenticated socket remains the complete ownership boundary.

`AndroidClipboardGateway` is the only direct `ClipboardManager` boundary.
Console selection and terminal copy/paste callbacks, compatibility reports,
logs, paths, settings, and automation all use its typed text operations.
Termux-backed Console windows therefore share the same Android system
clipboard as shell-backed Console and ordinary Android applications; MagicDesk
does not maintain a terminal clipboard mirror. Sensitive MCP connection data
is marked for protected Android clipboard previews. Clipboard access is
request-driven and has no listener, history, or polling loop.

`AndroidContentPayload` is the immutable content contract shared by clipboard,
Android share/view Intents, external drag-and-drop, Files, and Desktop. It
preserves bounded URI items, declared MIME types, text/HTML, sensitivity, and
origin without carrying executable clipboard Intents.
`AndroidContentIntentAdapter` is the only payload-to-Intent/ClipData serializer.
Read grants travel in both `ClipData` and Intent flags, so the selected
application receives the same content that MagicDesk classified. Clipboard
**Open** and **Share** are explicit desktop actions and launch through the
production Android integration path; developer MCP exposes the same operations
without adding another executor. Ordinary state and diagnostics contain only
counters and metadata.

`DesktopContentReceiverActivity` is the exported **Save to MagicDesk Desktop**
share target. Because an exported Activity can be invoked explicitly, it asks
for user confirmation before writing anything. Accepted URI content is copied
while the incoming grant is alive; accepted plain text becomes a UTF-8 desktop
file. It does not retain incoming payloads, watch the clipboard, or start an
idle service.

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
then opened through the ordinary Termux Console path. There is no session
poller, and closing the Console closes only that tmux client. The public Termux
command boundary does not transfer the PTY stream of an ordinary Termux app
session, so those sessions remain owned by the Termux UI.

`ShellExecutionEnvironment` defines the common execution profile used by the
PTY relay, marker-delimited MCP shells, background shell Desktop Entries, and
one-shot shell commands. It removes inherited Termux process variables and
provides stable `HOME`, `TMPDIR`, XDG directories, Android-system `PATH`,
locale, and shell identity values under UID-specific
`/data/local/tmp/magicdesk-{shell,root}` runtime directories. Interactive
transports add `xterm-256color` and
true-color metadata; non-interactive commands use `TERM=dumb`. This shared
profile is the only insertion point for future Android-native command bundles.
Shell and root identities use independent top-level runtime directories so a
root-backed Shizuku session cannot leave ownership that breaks a later
shell-backed session.

`TaskStackListener` does not reliably report changes to app-requested system-bar
visibility, native freeform bounds, or organizer-child ordering. The centralized
`FrameworkTaskObservationSource` supplies these observations as described in
Framework compatibility services; no policy consumer owns an additional task
poll.

Framework commands that need hidden signatures run from the shell UserService
through `app_process` with the main APK on the class path. `hidden-api-stubs`
exists only for compilation; it is not packaged in the APK.

## Display And Session Model

A `SessionProfile` stores only a display selection policy. Runtime display IDs
are never persisted as constants.

`DesktopDisplayTarget` identifies a phone, wired, wireless, or simulated display
that is ready for desktop content. Starting any desktop first acquires one
persisted `DesktopHomeRoleLease`: MagicDesk temporarily becomes the package-wide
Android HOME holder and remembers the previous role state plus the complete
target. Android may have a working HOME surface while the role has no explicit
holder; that empty state is valid and is restored by removing MagicDesk rather
than selecting a launcher on the user's behalf.
`DesktopHomeSurfaceRouter` atomically exposes exactly one primary HOME alias
before the role is claimed. External targets use `PhoneHomeActivity` on display
0 and launch `DesktopActivity` through the typed Shizuku task API as the
`SECONDARY_HOME` task on the selected display. A phone target exposes
`DesktopActivity` itself as primary HOME, so Android creates the desktop host
directly in its standard task area without a second phone HOME task.

The lease is the only owner of HOME transitions and HOME-surface selection.
Normal close restores the previous holder and the default alias state before
session teardown; later cleanup failure never reclaims HOME for MagicDesk.
Unexpected display loss releases a live lease through the same role boundary,
and a user-selected third-party HOME is never overwritten. If a new MagicDesk
process starts while still holding HOME, the startup guard disables its HOME
surfaces, discards the stale lease, and opens system HOME immediately without
waiting for Shizuku. One event-driven reconciliation clears a release record
left after HOME was already transferred before process loss. This recovery does
not add a runtime polling loop. `DesktopOperations` owns the common target-aware
close operation; transport-specific code stops at target preparation.

- An already connected wired or wireless secondary display enters
  `DesktopSessionController` directly on every platform. Closing the desktop
  returns its application tasks to the phone but does not disconnect or
  reconfigure the system-owned transport.
- The Nubia projection extension may configure physical HDMI timing, output
  fill, and native caption visibility. These are independent capabilities and
  do not create or own a second logical display.
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
state. Android associates input by physical port on wired displays and by
stable display unique ID on Miracast and simulated displays. Simulated sessions deliberately exercise
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
  the connected Android secondary display. Its position in the task stack separates
  visible windows from resumed minimized windows without replacing the phone
  launcher.
- Phone control and external desktop are separate tasks and may coexist.

Contributors can run `scripts/smoke-simulated-display.sh` from a host with ADB.
The script starts the debug self-test Activity, so it uses the same display
driver, owned overlay lease, desktop session, window suite, and cleanup as the
built-in simulated self-test without replacing the running app process.

The built-in **Diagnostics > Run desktop self-test** runs the same bounded core
on a selected simulated, external, or phone display. A desktop session must be
closed when the test starts; an already connected secondary display is allowed.
Its explicit isolated session policy suppresses saved-workspace restore
and persistence on every display driver. A scoped orientation lease locks the
phone at its current rotation and restores the exact previous auto/locked mode
through the common finalizer. The target owner prepares the session once, while
the common core derives bounds from the actual viewport, adopts any larger minimum window size
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
injected text. A mixed-stack phase places a third freeform task between two
fullscreen peers, selects it through Alt+Tab, and verifies from the rendered
surface that the most recently selected fullscreen peer remains its background
while the older peer stays underneath. It then selects both fullscreen peers
through the production focus route and verifies their rendered colors. The
root-workspace freeform window remains above that background. Input
assertions wait for the current InputDispatcher focus state rather than a fixed
transition delay. InputDispatcher frames are normalized from the display's
natural coordinates into its current rotation, so the same PHONE scenario runs
in portrait and landscape. The test also
requests the native horizontal resize cursor and verifies WMShell's transition
trace when that firmware trace is available.

The application-fullscreen phase keeps one application-owned immersive task
and two MagicDesk-managed fullscreen peers alive together. It activates each
peer through the common single-task focus gateway, returns to the immersive
task, and verifies distinct stable planes, all three task modes, real input
focus, the application's immersive marker, and the rendered fullscreen
surface. This
catches a repeated hierarchy rebuild and an implementation that works only for
a pair of tasks. `WINDOW-015` and `WINDOW-020` identify these
application-fullscreen hierarchy checks.

The simulated target owns its display through a Binder-owned shell stream;
closing the stream or losing its owner closes stdin, runs a shell `trap`, and
restores the prior setting. Its test deliberately closes that lease once while
the desktop and a fullscreen fixture are still alive. It verifies that the
runtime and owned display stop and that a surviving fixture is
never left freeform on display 0. The external target selects the existing
wired or wireless display automatically and never treats the physical display
or its unrelated tasks as test-owned. An existing Miracast transport remains
connected. The phone target uses the normal local-
desktop navigation and cleanup path. Each target closes only the MagicDesk host
and test fixtures that it created. Cleanup closes the host before removing its
fixture tasks so SystemUI can reconcile live task IDs instead of retaining
references to tasks that the test already destroyed. The phone navigation
guard is released even when task reconciliation reports a failure; the pending
marker remains for a later recovery attempt.

Task placement has one direct-root policy rather than separate launch, reuse,
parking, or self-test routes. A running task is hidden and prepared as
fullscreen, its root crosses the display boundary, and a target-local freeform
transition reveals the final bounds. This gives WMShell an authoritative mode
boundary on the destination so caption surfaces and input windows acquire the
correct display. The simulated driver deliberately uses this same path to model
external-display behavior without connected hardware.

Phone `DesktopActivity` is primary HOME in Android's default task area.
Freeform applications remain standard root-workspace tasks above that HOME.
Fullscreen applications use the same independent per-task planes as every
other target. The exact SystemUI desktop-wallpaper activity is suppressed only
while phone desktop is configured, because that AOSP surface exists to cover
Launcher and would otherwise cover MagicDesk's desktop icons as well. Taskbar
visibility follows fullscreen and auto-hide policy directly.

A cold freeform launch is staged behind the desktop host until Android assigns
the task ID, then one complete WMShell `OPEN` reveals its final mode, bounds,
and front order. A running cross-display task uses the same direct-root
transfer. Reusing a fullscreen task on the desktop display claims its exact
task ID in shell-owned desktop topology before the freeform transaction, so
display-0 phone-task normalization cannot reverse the requested transition.
A direct fullscreen launch is attached to its independent plane
before the operation returns. Reused fullscreen tasks cross the same plane
attachment boundary before their launch action runs. All paths use explicit
display IDs and never
depend on display names, package exceptions, or timing guesses. The shell still
reports desktop task ownership so phone teardown can distinguish desktop tasks
from unrelated display-0 fullscreen tasks; this classification does not own or
reparent their hierarchy.

The shell task observer exposes an optional self-test guard. While a test is
active, every task callback captures a bounded `getAllTasks()` snapshot tagged
with the current test stage. A pure analyzer checks the desktop host, fixture
display and windowing mode, HOME visibility, one-way task transitions, and
windowed/fullscreen visibility continuity. It receives only the selected
display ID and desktop-host task ID; it does not branch on a display kind,
display number or vendor. The analyzer requires every
simultaneous fullscreen fixture to have a distinct feature ID, exactly one
anchor in that plane, and the same parent throughout focus switches. A fixture may
leave the selected display only in fullscreen mode during
the explicit transfer scenario. A visible freeform fixture with a
hidden desktop host is an error on every target; this also detects a native
desktop area taking ownership of the phone screen. No snapshots are taken
during normal desktop operation, and the guard uses neither polling nor timing
guesses. Android can deliver remote `onTaskMovedToFront` before the matching
visibility update; only a gap beginning at that callback may remain pending,
and it must resolve by the coalesced `onTaskStackChanged` callback or the test
stage boundary. Other visibility gaps fail immediately.

`SelfTestTaskStackInvariantAnalyzerTest` exercises these structural rules
without an Android device. Simulated, phone, and wired self-tests exercise the
same assertions against real WindowManager and firmware paths. All targets
verify parent continuity, mode, input focus, browser-style immersive state, and
the absence of desktop visibility gaps without weakening assertions by display
kind.

A separate one-shot launch probe captures the first
`onTaskMovedToFront` configuration, so the test distinguishes a true initial
freeform launch from a fullscreen task that is corrected after it becomes
visible. The same probe verifies a direct fullscreen-phone to
freeform-external move.

Self-test fixture launches also carry an explicit visual role. Primary,
secondary, and transition fixtures use stable red, green, and blue surfaces,
respectively, so a person watching the test can identify which task flashed,
moved, or disappeared. Color is diagnostic presentation only; window and input
assertions do not depend on the palette.

The desktop uses one `WindowMetrics`/WindowInsets viewport model on every
display. A phone desktop is an explicitly selected primary HOME session: it
reserves the status bar, requests transient navigation bars, and owns the
physical bottom edge for its taskbar. A temporarily revealed navigation bar
overlays the stable desktop geometry instead of moving it. A dedicated external
display normally reports zero system-bar insets and fills the panel. The
taskbar plane receives its final bounds from the desktop viewport; its attached
application panel does not apply system-bar or IME insets a second time. There
is no separate phone implementation of the desktop. IME visibility may keep an
auto-hiding taskbar logically presented, but it never moves the taskbar plane:
the keyboard temporarily covers the physical bottom edge instead of relocating
desktop chrome into the workspace.

The wallpaper is a full-display backdrop outside the inset-aware desktop
content layer. Status-bar and viewport changes therefore reposition icons and
windows without rescaling the wallpaper. The wallpaper controller center-crops
the source once into a physical-display-sized frame; the view uses a fixed
top-left image matrix, so a transient system-bar inset cannot recrop that frame
when HOME loses focus. Wallpaper readiness is published only after the selected
bitmap reaches a committed frame; reload generations discard stale callbacks
without a settling delay. On the phone display, an opaque desktop-chrome
backdrop covers the reserved status-bar inset above the wallpaper. Android can
therefore keep its normal transparent status bar for HOME and freeform tasks
without exposing a bright wallpaper strip above snapped windows.

The taskbar is a regular fullscreen Activity inside a narrow organizer-owned
task-display area. The area is bounded to the taskbar geometry, is not
focusable, and remains above ordinary application task areas. This gives the
taskbar normal application-window treatment, so a foreground application that
suppresses non-system overlays cannot suppress it. The Activity is fullscreen
relative to its bounded parent and therefore never receives a freeform caption.
The shell disables that Activity's Android 15+ ActivityRecord input sink, so
only the taskbar window's bounded touch region receives input and pointer events
outside the panel continue to the desktop and application windows.
Auto-hide keeps the parent geometry stable, makes the hidden taskbar window
non-touchable, and resizes the application panel containing the taskbar View to
its reveal edge. The same window therefore owns visible taskbar input and
hidden-edge hover without forwarding synthetic events. It adds no polling and
keeps the input frame aligned with the visible edge. The organizer retains its
task-display-area surface leash and assigns it a layer above the independently
layered fullscreen planes. That single shell-owned surface order keeps both the
visible panel and hidden reveal edge above application input regions.
The taskbar hides for an unrelated true-fullscreen task and returns for the
desktop. Chrome policy reads the complete physical display snapshot before
workspace ownership filtering, while task lists and window operations remain
limited to session-owned tasks. A foreign foreground task disables both the
panel and its reveal edge; managed fullscreen tasks retain edge reveal. Its
shared controller measures the actual task viewport on every display and
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
unconditionally assumes that view is present. Current AOSP Launcher3 permits
the field to be absent; this is a vendor integration defect rather than
malformed task metadata.

While a desktop session is active, MagicDesk owns Android's HOME role. An
external session uses `PhoneHomeActivity` as the phone navigation surface; a
phone session uses `DesktopActivity` as primary HOME. The task layer enforces a
separate invariant: no application task may remain freeform on display 0 after
migration or teardown. `ShellExternalTaskMigrationGuard` normalizes
system-driven moves during an external session, and
`PhoneDesktopTaskRecovery` reconciles live tasks with WMShell's retained desktop
repository after phone-desktop close or external-display loss.

The Nubia Overview router may remain registered while task teardown is still
finishing, but it cancels the firmware Recents launch only after the app-side
callback confirms an `ACTIVE` HOME lease. The lease enters `RELEASING` before
HOME is transferred on normal close, failed start, self-test cleanup, or
unexpected display loss. Recents therefore returns to the system launcher at
the HOME ownership boundary rather than at the end of task cleanup; the check
runs only for an attempted Recents launch and adds no background work.

Task snapshots and windowing commands issued through `TaskRepository` share a
single `TaskCommandQueue` with phone-task recovery. Recovery observes the
local-session generation before every mutation. A request to open a newer phone
desktop therefore cancels stale cleanup before that desktop is launched, while
ordinary taskbar operations cannot interleave with recovery commands.

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
the same request onto a regular Files Activity. Neither context reimplements
request resolution or backend selection. The coordinator deliberately leaves
the established WMShell transition controllers unchanged.

`DesktopApplicationRepository` is the single catalog adapter for executable
entries. Start consumes the already loaded Desktop files, while Open With can
load the same bounded catalog through the shell service. Both receive the same
immutable shortcut and source path and delegate it to
`DesktopLaunchCoordinator`. The terminal-application editor only validates a
form and writes a normal entry through `DesktopEntryFile`; it does not create a
second application registry or execution path. Its `%f`/`%F` and `MimeType`
fields consequently drive Start launches, Open With, and drag-and-drop without
surface-specific command logic.

`DesktopExecRunner` owns the execution-backend boundary. Android shell is the
default backend;
`X-MagicDesk-ExecBackend=termux` selects Termux explicitly. Unknown backend
names invalidate the entry instead of silently running a command in the wrong
environment. `Terminal=true` opens the built-in Console with either a
UserService-backed Android shell PTY or a Termux-hosted PTY. PTY transport is an
implementation detail of the backend and therefore requires no additional
Desktop Entry format or migration.

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

Backend capabilities describe background, terminal, working-directory, and
completion-result support. `DesktopExecSessionTracker` keeps only a bounded
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
selection state. `FileOperationClipboard` holds process-local shell paths and
an explicit `COPY` or `MOVE` intent; it is not a text clipboard and is never
persisted. `FileClipboardInterop` is the only bridge to Android. A selection
containing readable ordinary files is additionally published as read-only
`content://` items. Directories, symbolic links, and selections larger than
the bounded Android publication limit remain internal because Android has no
portable directory-clipboard contract and clipboard Binder payloads must stay
bounded. Files and Desktop can also paste content copied by another Android
application. URI items are imported as files; plain text becomes a UTF-8
`.txt` file (or `.html` when HTML is the only representation). External
consumers always see copy semantics; only MagicDesk can
complete the internal move. A completed move clears only its own generation
and the matching Android URI clip, so an older operation cannot discard a
newer selection or unrelated clipboard data.

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
second file-association database. The same dialog can include executable
Desktop Entries from the MagicDesk desktop when their standard `MimeType`
list matches and `Exec` accepts a file or URI field code. These command
profiles are one-time launch targets: they never enter Android's preferred
activity record and therefore cannot be selected with **Always**.

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
API instead of parsing shell completion output. Optional Termux integration
uses Termux's documented `RUN_COMMAND` intent and permission; it is not
required by Files. Files can launch a new Termux-backed Console at its current
shared directory. MagicDesk atomically installs a versioned native relay from
the APK through `RUN_COMMAND_STDIN`; a random per-window token authenticates
the relay's loopback connection before any terminal bytes are accepted.
MagicDesk does not mirror or mutate the Termux application's own PTY registry.
When tmux is installed, its independent session registry is queried only by an
explicit tmux picker or automation request.
Optional Termux:X11 integration uses the same permission boundary. MagicDesk
intercepts the ordinary default launch of the exported Termux:X11 viewer, then
prepares it through the same `AppTaskController` path as any other application.
It starts the configured X server command only after that task is ready, or
uses Termux:X11's loopback handshake to reconnect the prepared viewer to an
existing server with the same explicit `:N` display argument. A disappearing
listener falls through to the configured startup command instead of turning a
failed reconnect into a successful no-op. The viewer therefore remains a
single Android task governed
by normal window state, focus, taskbar, and session parking. There is no
separate Tools action, fixed startup delay, or duplicate server process.
MagicDesk neither embeds the GPL-licensed X server nor models individual X11
client windows as Android tasks. Closing or parking the viewer does not claim
ownership of the independently running X server.

The reconnect command uses Termux's documented `RUN_COMMAND_PENDING_INTENT`
result channel. The result receiver is explicit, non-exported, one-shot, and
bounded by a timeout; long-running X11 startup and PTY commands remain
fire-and-forget and do not wait for process exit. The non-destructive status
probe runs through MagicDesk's shell service because Android hides socket
tables from the ordinary Termux app UID. Runtime status keeps the server
process, reconnect socket, requested display, and Android viewer task as
separate typed fields. The application integration
contributes its reconnect context action through
`DesktopLaunchIntegrationRegistry`, so desktop UI code contains no
Termux:X11 package branch.
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

On **Start external desktop** or `Win+D`, MagicDesk:

1. discovers Android's connected wired, wireless, or overlay display;
2. loads the profile keyed by that display's stable identity;
3. optionally applies a platform-specific physical output timing;
4. corrects geometry and applies the display profile DPI;
5. creates or normalizes one display-sized MagicDesk multi-window host task;
6. focuses the desktop and restores the last visible window layout.

The desktop target always contains the Android display that actually hosts the
tasks. MagicDesk does not create a vendor projection display, infer lifecycle
state from vendor settings, or return the physical transport to another mode
when the desktop closes.

Requests are serialized and duplicate requests during transition are ignored.
With no external display, the shortcut cannot accidentally create a second
desktop on display 0.

Display discovery and display hosting are separate contracts. The shared
desktop-session path accepts only a ready `DesktopDisplayTarget`. That target
identifies the Android display which owns tasks and the profile stored for the
same output.
Phone, simulated, wired, wireless, UI, self-test, MCP, and App Functions starts
all converge on this boundary before the common session controller runs.
Normal starts use `DesktopSessionPolicy.USER`; diagnostics can select the
non-restoring, non-persisting `ISOLATED_SELF_TEST` policy without adding
display-specific restore exceptions.

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
during this transition. The operation runs before the desktop session starts,
so no MagicDesk task is attached to a disappearing display.

Output timing and **Fill display** are stored in the same per-display profile
as DPI. **Fill display** maps to Nubia's projection-fit setting. MagicDesk
temporarily enables the vendor fit bypass while preparing the session, writes
the fit setting and, when applicable, the `1080P`, `1440P`, or `2160P`
resolution profile consumed by the projection service, and restores the
previous bypass property afterward. MagicDesk reproduces Nubia's EDID profile
selection instead of assigning these values by numeric range. A non-standard
native timing such as `1920x1200` is applied through Nubia's exact wired-mode
path after the physical display exists. Modes below a 1080-pixel short edge
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

Some firmware hides the EDID node from shell UID 2000. MagicDesk records that
capability state in diagnostics and continues through the SoC backend or
Android's public display-mode list. If neither source exposes alternate
timings, the active physical mode remains usable but read-only.

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
records lifecycle ownership, and restores that transport's latest preference
on session exit, transport change, or next-start recovery. Simulated displays
do not acquire this vendor state.

### Teardown

**Close desktop** first captures live managed application tasks. External tasks
move to display 0 in fullscreen mode; phone-desktop tasks remain on display 0
and are normalized by the normal phone cleanup. The in-memory parking record is
consumed when the next desktop host becomes ready. Restoration matches both
task ID and package, so it never creates a replacement for a task Android
closed. The same record is captured from the latest observed task snapshot when
a display disappears or a desktop host is replaced before an explicit close can
query it. An explicit **Exit MagicDesk** clears this record and closes built-in
MagicDesk windows instead.

Before any normal teardown mutation, `DesktopHomeRoleLease` restores and
verifies the exact HOME role state from session start: either the previous
holder or no explicit holder.
The lease enters `RELEASING` before that handoff so startup recovery can finish
an interrupted release without treating it as an active desktop. If MagicDesk
still owns HOME after process loss, the pre-Shizuku startup guard instead
disables its HOME surfaces and discards the lease immediately. Once the previous
HOME is confirmed during normal close, the routing aliases and persisted lease
are restored immediately; a later task or display cleanup failure never claims
HOME for MagicDesk again. Unexpected display loss performs the same restoration
without waiting for a UI callback.

Physical display removal, **Close desktop**, and **Exit MagicDesk** share the
common cleanup path:

- hand HOME back to the package saved by the session lease;
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

MagicDesk temporarily owns Android's HOME role for the desktop session. The
selected alias makes `PhoneHomeActivity` the phone navigation surface for an
external session or makes `DesktopActivity` primary HOME for a phone session.
For an external session, `DesktopActivity` is launched and verified as the root
secondary HOME task on the selected desktop display. The desktop host is an
opaque, display-sized fullscreen Activity; it
does not need a force-translucent override or a post-launch window-mode repair.
The Activity becomes available to parked-task restoration after its first
rendered frame. HOME-role acquisition, root-task creation, and first-frame
readiness are separate lifecycle facts, so callers never infer host readiness
from an arbitrary delay or configuration retry.

Task order around this host is the desktop visibility boundary. Freeform tasks
above it are visible windows; tasks below it are minimized and follow Android's
normal background lifecycle. Minimizing reorders the active task below the
host and then focuses the next visible task, or the host when no window
remains. This requires no timer, lifecycle spoofing, or custom window layer.

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
moves. Independent fullscreen-plane exit instead uses the topology-owned
ActivityTaskManager path described above, because framework root selection
must remove the live task before its organizer plane is deleted. Higher-level
controllers retain lifecycle policy; interactive drag, resize, and focus never
pass through the prepared-state mechanism.

Above that executor, `DesktopWindowTransitionRequest` defines the semantic
operation (`enter-fullscreen`, application fullscreen, or freeform restore),
exact task, display, and required geometry. The
`DesktopWindowTransitionGateway` maps it to the active observer without
exposing observer methods to policy code. This boundary is platform-neutral:
firmware extensions may influence capabilities and preparation policy, but do
not implement a second fullscreen/restore state machine.

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

Both helpers keep their virtual devices alive for the complete desktop session.
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

Pointer speed uses Android's standard `Settings.System.pointer_speed` range and
is observed for changes made outside MagicDesk.

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
the last routing display. The native relays also keep aggregate physical and
forwarded event counts, write failures, and the timestamp of the last mouse
motion or keyboard event. They do not emit per-event diagnostics, record key
codes, or retain typed text. At the start of explicit compatibility-report
generation, MagicDesk requests one native statistics frame and one bounded
`FrameworkInputSnapshotSource` snapshot. The resulting report compares owned
MagicDesk ports with current InputManager associations and records the observed
vendor pointer position without refreshing the viewport or attempting pointer
recovery. No diagnostic input polling runs during normal desktop use. The
report also states whether the optional desktop-session wake policy is enabled
and currently held.

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

Debug builds also expose this production path through `DebugSelfTestActivity`.
The smoke script starts that Activity and reads the normal bounded result file;
it does not replace the app process with instrumentation, so the runtime and an
enabled MCP server remain alive. It is intentionally not run by host-only CI.

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

`CommandConsoleActivity` is a permission-protected, multi-instance desktop task
over a selected `TerminalTransport`. Each Activity owns one independent
interactive PTY, terminal emulator, current-directory state, and selectable
scrollback. Android-shell and Termux transports share this complete UI and
session layer. Input is a byte stream rather than discrete command jobs, so shell
editing, signals, ANSI output, alternate-screen applications, and terminal
mouse protocols retain their normal semantics. Running `exit` or closing the
Activity closes that shell. Commands supplied by explicit Files and Desktop
actions are safely quoted and sent after the PTY becomes ready.

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
- Every configured desktop uses direct-root freeform tasks and independent
  per-task fullscreen planes. Session cleanup drains every owned plane and its
  structural anchor.
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

Normal CI builds unsigned release variants of the main and Kernel Fixes
applications and runs `scripts/verify-apks.sh` with their APKs to enforce
package boundaries.

For a `v*` tag, the release workflow loads signing credentials through
`gradle/release-signing.gradle`, signs only the main MagicDesk APK, verifies its
certificate and package boundary, emits a SHA-256 file, and publishes that APK
as the tagged release. The firmware-specific Kernel Fixes APK is not a tagged
release artifact. Local debug builds never require release secrets.
