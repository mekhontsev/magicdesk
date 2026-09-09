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
shortcuts only while a particular IME is active. Android owns physical event
delivery, repeat, modifiers and keyboard layouts. The key-only
`DesktopShortcutService` handles desktop combinations before system policy;
it never requests accessibility window content or editor text.

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

### Route devices through Android

`DesktopInputSession` serializes ownership on one worker. Physical keyboards
and mice remain Android InputReader devices; MagicDesk does not read or forward
their event streams. `DesktopInputRoutingSession`, hosted by Shizuku, binds
their input locations to the desktop's stable display unique ID through
`FrameworkInputRoutingApi`. The API boundary supports Android 15 and newer.

A composite keyboard/mouse sharing one location receives one association.
`InputRoutingLease` journals previous runtime port and unique-ID associations
before changing either map. It restores only values still owned by the session,
preserves concurrent foreign assignments, and refuses to override static routes.
The durable journal is boot-scoped because Android runtime associations do not
survive reboot. Binder owner death releases routes; interrupted cleanup remains
retryable. Unknown or incomplete input inventory is an error, not an empty list.

Existing input-device callbacks reconcile hot-plugged locations. There is no
periodic input inventory query. A key-only Accessibility service receives only
confirmed desktop keyboard IDs, observed through device-generation callbacks.
It consumes MagicDesk combinations and dispatches them through the same
`DesktopOperations` and task-controller gateways as the UI. Ordinary key
events continue through Android unchanged. Service enablement has its own
shell-owned journal and preserves other Accessibility services.

Host registration alone does not start input. Preparation is published after
HOME draws, workspace ownership is configured and parked-task restoration
finishes. Routes are acquired first; the phone pointer's location can be
associated before its virtual device exists. `DesktopMouseBridge` then creates
one virtual relative mouse for the phone touchpad on external desktops.

Close invalidates input readiness before queuing teardown. The same worker
finishes any in-flight acquisition, destroys the phone pointer and restores
shortcut enablement and device associations before display removal. A stale
start completion cannot reopen input. Each session creates a fresh virtual
mouse; hardware mice keep their Android identities throughout.

MagicDesk uses one phone-side `MagicDeskTouchpadActivity` for every external
transport. `TouchpadPointerMotion` converts successive finger coordinates into
relative deltas. The shared native mouse relay forwards these deltas and button
state through its virtual pointer; Android owns pointer acceleration, cursor
visibility, hover shape, and window dragging. There is no additional motion
smoothing or acceleration loop. Explicit automation injects display-targeted
Android mouse events, separately from optional cursor observation.

A long press remains undecided until the finger either moves or is released.
Movement starts a primary-button drag; release without movement becomes a
secondary click. Two-finger movement scrolls, while a stationary two-finger
tap also becomes a secondary click. These decisions stay in the phone UI;
display-targeted event injection stays inside the shell UserService.

The user's Android IME connects directly to the focused desktop editor through
its normal `InputConnection`. `DisplayImePolicyController` temporarily applies
Android's fallback-to-default-display policy on external desktops. The shell
task-observer session owns this policy alongside the display configuration:
clear, close, and owner Binder death restore the previous policy if it still
has our value. Repeated configuration of the same display does not query or
write the policy. Phone desktop leaves display-0 policy unchanged.

The phone touchpad is an ordinary Activity with a non-focusable attached
`PopupWindow` containing its controls and touch surface. Android's
`INPUT_METHOD_NEEDED` mode allows the window to coexist with the phone IME.
Touchpad motion and clicks do not take editor focus from the external
application. System and IME insets constrain the usable touch surface. The
popup is attached only while the Activity is started and is dismissed on stop;
it cannot remain over another phone application. The Activity itself retains
normal focus and Back handling when no external editor is active.

Start initially focuses its search for hardware input without requesting the
software keyboard. Clicking the search field enables and explicitly requests
the IME. Other applications use their own editor's native show/hide behavior.
The chrome task permits focus only while a focusable panel or dialog is
requested; its transparent base window and taskbar remain non-focusable.
`DesktopPanelWindowController` acquires task focusability before attaching the
panel and releases it on dismissal, failed attachment, or host teardown.
Acknowledgements from a closed panel cannot attach a replacement. The existing
task command queue orders release before a following application launch; no
worker, polling loop, or UI-thread Binder wait is added. Leaving the task
permanently focusable lets its always-on-top priority block application focus
even with no focused child window. Conversely, making the
whole task non-focusable rejects a child panel's input connection even when
that child receives ordinary hardware key events.

No editor text is captured or relayed by MagicDesk. Composing text, selection,
deletion, editor actions, and Back-to-dismiss remain Android IME operations.
There is no extra keyboard, polling loop, or software-keyboard selection.

While an external desktop is owned, the runtime temporarily enables Android's
`show_ime_with_hard_keyboard` setting so the user can explicitly open the
phone keyboard even when a physical keyboard is connected. It remembers the
previous value and restores it on normal desktop teardown; no persistent
keyboard preference is imposed during setup.

### Keep vendor input APIs behind a capability boundary

MagicDesk does not package or link a Nubia binary library. The vendor surface
used for cursor diagnostics is the private Binder method
`IInputManager.getMousePosition` on RedMagic firmware.

The signature is resolved reflectively inside the shell UserService.
`PointerPosition` retains the source's display identity, or -1 when unknown.
Nubia's API exposes global coordinates without a display identity: these appear
as an unscoped observation, never as the requested desktop's position.
Display-targeted hover and positioned clicks use `DesktopPointerInjector` on
all platforms. They inject mouse events but do not reposition the hardware
cursor. Phone touchpad movement remains relative native input.
A missing optional package or method disables
the corresponding operation rather than changing unrelated device state. In
contrast, `libmagicdesk_uinput_bridge.so` is a MagicDesk-owned virtual mouse
helper compiled from repository C source by every local and CI build.
Its bounded stdin protocol carries relative motion, buttons, scrolling and
on-demand aggregate counters. EOF releases held buttons and destroys the
virtual device. Physical device recovery remains Android's responsibility.

### Do not draw replacement application captions

An application overlay cannot share a task's SurfaceControl leash or transition
atomically with WMShell. A separately drawn caption trails live movement,
maintains a different Z-order, and can leave controls above the wrong window.

MagicDesk instead keeps native WMShell captions visible. One persistent,
transparent `DesktopChromeActivity` supplies the application token for the
taskbar, Start, context menus, notification center, and desktop dialogs. The
shell launches this host in a root-level organizer area, a sibling of the
standard task workspace. Both the area and its task use `MULTI_WINDOW` and
`alwaysOnTop`; the task is non-floating and normally non-focusable, with empty bounds
that fill the area. The shell also disables the
ActivityRecord input sink.
All visible chrome is an ordinary bounded `TYPE_APPLICATION_PANEL` child
window, so empty parts of the display-sized host neither draw nor consume input.
The host never enters the freeform caption path. Its exported component is
protected by the framework
`MANAGE_ACTIVITY_TASKS` permission, so only the authorized shell runtime can
create it.

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
demote, bare-desktop presentation, desktop-workspace presentation, workspace
restore, and session restore operations. Activation carries exactly one target
task; workspace operations carry a named back-to-front plan. A multi-task
activation is rejected, not interpreted as a workspace restore.
`DesktopWorkspaceQueue` orders complete user intents on the controller Handler,
including their live snapshot, toggle decision, host-input preparation, and
shell acknowledgement. It replaces the separate Show/Restore queue. Requested
focus is kept separate from observed focus; the next click cannot use the
uncommitted target of its predecessor. Stop cancels the session's outstanding
intents without admitting late acknowledgements into the next session.
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
places it above the current fullscreen plane without raising peers covered by
that plane. The shell derives exposed freeforms from typed root hierarchy
order, stopping at the first fullscreen application or HOME rather than using
task-local `visible` flags across independent areas. UI panels send only the
selected task ID; their captured launch context never becomes a focus plan.
Neither operation changes task
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
| Task transfer boundary | `DesktopTaskTransfer` | Applies the freeform or fullscreen cross-display protocol for a running task |
| Phone desktop wallpaper policy | `ShellPhoneDesktopWallpaperPolicy` | Keeps the MagicDesk HOME surface visible below standard freeform tasks |
| Fullscreen topology | `ShellFullscreenTaskArea` | Owns per-task fullscreen planes on every desktop target |
| Hidden API stubs | `hidden-api-stubs/` | Compile-time signatures only; never packaged |
| Mouse helper | `native/magicdesk_uinput_bridge.c` | Binder-owned relative phone pointer |
| Kernel Fixes add-on | `io.github.mekhontsev.magicdesk.kernel` | Independent, manually launched, firmware-specific root fixes |

The main APK contains no `.ko`, kernel loader, root command path, or reference
to the add-on package. The two applications share a repository but have no
runtime integration and are not distributed through the same release path.

## Main Application Boundaries

### User-facing lifecycle

- `ControlActivity` and `PhoneControlPanelController` provide the compact phone
  control surface. They do not create taskbar, wallpaper, or app-catalog UI.
- `FileManagerActivity` is an ordinary resizable desktop task. The activity owns
  navigation and selection; `FileManagerView` renders them and routes user actions.
  `FileDirectoryReader` reads a complete listing on the existing Files worker;
  `FileManagerOperationController` owns
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
  tasks, appear in the launcher or taskbar pins, and share profile-scoped application window
  state. Settings is a singleton reusable task with compact centered default
  bounds. A single constrained, scrollable `SettingsView` uses the same dense
  visual language on phone and desktop. The phone opens it normally, while the
  desktop task controller launches the same Activity in a dedicated reusable
  freeform task.
- Diagnostics follows that same built-in-window path on a desktop. It therefore
  cannot replace the desktop host Activity or hide every application
  merely because a report was opened. Phone-side callers may still open the
  same Activity normally in their current task.
- `DesktopActivity` hosts external desktops, while
  `PhoneDesktopHomeActivity` is the dedicated primary-HOME host for a phone
  desktop.
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
  input-device routing, the phone pointer and shortcut filter, desktop text routing,
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
  phone-side input panel. Both use direct, immutable Activity PendingIntents
  with display-0 launch options, never service or broadcast trampolines.
  The touchpad action is offered only for a supported active external desktop;
  the Activity validates its target again before requesting phone input.
  Any visible phone application suspends automatic touchpad restoration using
  the existing task snapshot. Opening an app or Control Panel from the
  notification therefore leaves the touchpad behind it without cancelling the
  user's input request. Restoration resumes only on an exposed HOME or empty
  phone workspace; there is no component-specific Control Panel exception.
  Desktop Show/Restore remains a taskbar and `Win+D`
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
  waits. An event receives its cursor id atomically with publication, so
  concurrent producers cannot publish an older event behind a reader's cursor.
  `DesktopAutomationTaskEventTracker` derives task lifecycle, display,
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
  `127.0.0.1:8765`; stopping the runtime closes the listener, active and queued
  client sockets, workers, and backend. A closed transport cannot be restarted;
  enabling the runtime again creates a new transport with its own resources.
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
  `AndroidDesktopAction` gives each user-visible operation one semantic id and
  source independent from the UI, MCP, or App Function surface that requested
  it. `AndroidDesktopActionCatalog` owns the bounded set of public system
  actions and their typed parameters. `AndroidDesktopActionDispatcher` is only
  the asynchronous UI adapter; it does not implement a second launch policy.
  Content drops from Desktop and taskbar use this same adapter with a
  UI-owned `ContentRequestScope`. Closing that owner cancels queued deliveries;
  running deliveries release their source grant before scheduling a UI result.
  Resource release never depends on the callback running, and queued callbacks
  check their owner again on the UI thread. A release error after the gateway
  has returned does not replace its delivery result; the dispatcher records
  `CONTENT-GRANT-RELEASE-001` separately instead of suggesting that a committed
  action needs to be repeated. The existing executors retain their
  ordering; no additional worker or polling loop is created.
  `AndroidIntegrationRequest` owns Intent parsing and validation;
  raw Intent URIs are an input form rather than a parallel executor. Direct
  launches cross the Shizuku task-launch boundary as full Parcelable Intents,
  preserving `ClipData`, grants, and typed extras. Discovery and App
  Function framework calls have shell-side adapters, but desktop placement and
  task reuse still enter the production launch coordinator.
  `AndroidActivityResolution` distinguishes a real handler from Android's
  synthetic resolver without relying on an internal class name. Its typed
  `AndroidActivityAuthorization` independently evaluates enabled/exported
  state, same-package access, and permissions granted to the MagicDesk app.
  Shell is only a placement authority: denied app-identity access never crosses
  that boundary. Public handlers without a required permission take the direct
  shell path. Choosers, system resolvers, and allowed handlers requiring app
  identity use an immutable one-shot `PendingIntent` created by the app and
  sent by shell with the requested display, activity type, mode, and bounds.
  The token preserves app authorization and URI grants; shell contributes no
  target authority. A focused compatibility adapter owns the Android 15 and 16
  background-start option semantics for both creator and sender.
  Activity-result requests require an app Activity lifecycle. They retain the
  nested target in `AndroidActivityRelayStore`; shell receives only an opaque
  id and places the relay Activity. Relay ids use an atomic `ready -> claimed`
  lifecycle: Android task handoff may instantiate the relay Activity twice, but
  only the first instance can execute the payload. Claimed tokens remain in the
  same bounded store without retaining their Intent payload. The exported
  relay Activity requires `MANAGE_ACTIVITY_TASKS`, so only the same privileged
  task-launch boundary can consume those one-shot ids. Broadcast and service
  starts are developer-only because they have no visible UI.
- `AndroidLaunchSpec` keeps the task's semantic target separate from the
  Activity used to execute a launch. `AppTaskController` derives task reuse
  identity from the concrete component for direct Intent launches. System
  selection surfaces use package-scoped identity because their published
  launcher component may hand off to another Activity in the same package.
  Result relays always create a distinct transient task under the relay's own
  identity; they never normalize, move, or reuse an existing task belonging to
  the result target.
  Published shortcuts follow the same separation:
  Android may redirect their metadata Activity to another Activity in the same
  app, so both fresh-task observation and task reuse are package-scoped while
  execution remains bound to the shortcut id. The component observed on the
  created task, rather than the optional published metadata component, becomes
  the mode-guard identity. Direct fresh Intent tasks receive the concrete
  Intent; an exact reused Intent task receives it as a task action.
  `DesktopActivityLaunchResult` carries one closed outcome through the
  UI-thread boundary: an observed managed task, an explicitly unmanaged
  acceptance, or a definitive/indeterminate failure. MCP and Android
  integration callers then use `DesktopTaskLaunchObservation` to confirm that
  exact task's typed STANDARD/display/mode topology through the existing event
  journal and one-shot repository snapshots. Resolver and chooser tasks omit
  a final component assertion because the user's selection is not yet known.
  `DesktopLaunchPresentation` is the sole transport for mode, relative bounds,
  explicit `reuse`/`new` instance policy, and an optional exact task id.
  Instance policy is not inferred from raw Intent flags. Relative bounds use
  the shared `0..10000` work-area scale and are valid only for windowed
  launches. An exact task id always means reuse, requires its explicit current
  mode, and lets content drops and automation deliver to an already managed
  task without moving or resizing it. Initial bounds are invalid with an exact
  task. A missing or mismatched exact task is a failure and never falls back to
  creating another window.
- `AndroidActivityResultStore` owns the bounded lifecycle of document picker
  and other Activity results. Synchronous automation waits use `EventDrivenWaits`;
  UI owners subscribe to request readiness without occupying a worker while a
  user chooses a document. Notifications run outside the registry lock, including
  failure, discard, and eviction; subscribing after completion cannot miss the
  result. There is no result poller. A returned content grant is retained only
  while its terminal result is owned by the store or a claimed import.
  Claiming removes the result atomically from the bounded registry without
  revoking its grants; unrelated requests cannot evict an active import's access.
  `PersistedUriPermissions` tracks typed
  grant ownership independently of result JSON; consuming or evicting one
  result releases only permission flags no remaining result owns for that URI.
  Stored result data and every reader's projection own independent nested JSON
  objects. Consuming a result releases its grants before serializing the reply,
  so a response failure cannot strand already-consumed permission ownership.
  Files subscribes to the picker result, claims it, imports the selected URIs
  through its normal typed filesystem controller, and closes the claim after
  the copy completes. Because request ids are process-local,
  process startup releases result grants orphaned by an earlier process death.
- `AndroidActivityResultData` owns the bounded projection of returned Intent
  data, separate from request lifetime and grant ownership. URI addresses and
  identity fields are preserved exactly or rejected when oversized; they are
  never shortened into different identifiers. Grant acquisition uses the same
  validated URI snapshot exposed to consumers. Extras inspect at most 32 keys,
  including unsupported or unreadable entries. Optional data loss is reported
  as `extrasTruncated`; text prefixes retain complete UTF-16 surrogate pairs,
  and unsupported or non-finite numeric values do not discard later extras.
- `AndroidActivityCompatibilityHistory` records at most 64 Activity launches
  that already occurred. It keeps presentation, authorization, observed task
  topology, outcome, and only the URI scheme; full Intent extras and content
  URIs are excluded. The compatibility report, MCP, and developer Activity
  Explorer read this same process-local history. Activity Explorer resolves
  current exported handlers and launches them through the production gateway;
  it has no private task command or vendor component list.
  Nested diagnostic fields are copied when recorded; later edits to an
  operation response cannot modify the saved evidence. Result and event
  snapshots likewise never expose the registries' internal JSON objects.
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
  layout, application window and presentation state, settings, and display
  profiles. `DesktopLayoutStore`, `AppWindowStateStore`,
  `AppPresentationProfileStore`, `DesktopPreferences`, and
  `DisplayProfileStore` are narrow domain facades over that model.
  Updates mutate a private copy and publish it only after persistence succeeds.
  External file reloads read and publish under the same transaction lock, on
  the folder worker; the UI receives only the change notification, never a
  delayed snapshot that could replace a newer save.
  Its persisted schema accepts only the current format; an unsupported format
  starts from defaults instead of running an in-process data migration.
  `DesktopPlacementEngine` is the platform-independent collision and reflow
  policy.
- `DesktopPanelWindowController` provides consistent toggle, dismissal, and
  placement for desktop panels. It attaches ordinary
  `TYPE_APPLICATION_PANEL` windows and dialogs to the persistent
  `DesktopChromeActivity` token also used by the taskbar. There is no transient
  panel task or panel-specific organizer hierarchy.
- `DesktopInputController` handles shell UI input and delegates global physical
  shortcuts to the key-only Accessibility service.
- `DesktopRuntimeBridge` is the weak-reference, main-thread boundary through
  which services reach the active desktop. Host registration and display
  target changes are serialized into one immutable `DesktopSessionSnapshot`;
  the target may intentionally outlive an Activity during configuration
  recreation or external-display teardown.
- `DesktopLayoutController` owns WindowInsets, viewport, and taskbar geometry.
- `DesktopTaskSnapshotController` serializes task refresh generations and
  filters the taskbar model. During an active session, refresh reuses the
  controller's published display snapshot; it does not issue an independent
  raw query that can expose a transient Activity handoff to chrome policy.
  Unknown publication leaves visibility unchanged and remains unavailable.
  Replies requested before activation also recheck the current session.

### Application profiles

Application identity has four explicit levels:

- `AppProfile` is a resolved Android user id plus its stable user serial.
  Runtime task matching uses the id; durable references use the serial, which
  Android does not recycle when a profile is deleted.
- `AppIdentity` is a profile serial plus a package. `AppItem` retains both this
  durable identity and the resolved profile. `AppLaunchTarget` only describes
  an entry point (package, component, action); it is not a complete app identity.
- `AppReference` combines `AppIdentity` with an optional built-in tool entry.
  Files, Settings, and Console retain separate identities despite sharing one
  APK. Ordinary Android applications remain grouped by profile and package.
- `LaunchActivityIdentity` binds an entry point or a package-scoped system
  surface to an explicit user id before task lookup. Direct launches bind at
  the current-user ingress; shortcut and PendingIntent launches retain the
  publisher/creator user. Reuse and launch confirmation never match another
  user's task just because its package and component agree.

`HiddenTaskApi` reads `TaskInfo.userId`; `FrameworkTaskSnapshot` carries it over
Binder into `TaskRepository.TaskEntry`, including published copies and parked
task records. Missing framework identity remains `-1`, not user 0. These fields
reuse existing snapshots and add no profile polling. The diagnostics task list
and MCP task rows expose the observed user id.

Generated Android Desktop Entries retain `AppIdentity` in
`X-MagicDesk-AppIdentity`. `DesktopLaunchRequest` preserves it through command
expansion and integration preparation. The coordinator rejects a reference
outside the supported current profile before any Android or Exec action; it
does not reinterpret it as the current user's same-package application.
Portable Desktop Entries without this field are current-context launch
descriptions, not durable references to a particular Android profile.

This is an identity foundation, not multi-profile support. The launcher catalog
still enumerates only the current profile. Profile discovery/availability,
badged icons, work-profile quiet mode, Private Space policy, cross-profile URI
grants and launch permissions are not implemented. Before widening the catalog,
profile resolution and permission-aware launching must be extended together.
The catalog, taskbar pins, recent history and window state exchange typed
`AppReference` values; DPI and application actions exchange `AppIdentity`.
Persistence alone serializes them as stable keys. Unbound or malformed stored
keys are skipped, never assigned to the current profile. There is no
package-only compatibility lookup.

`DesktopStateStore` stores pins, geometry and DPI; recent history keeps its
asynchronous SharedPreferences write path with a structured array of typed
references. No additional observer, timer or synchronous focus-time disk write
is introduced. Bounds callbacks carry `FrameworkTaskSnapshot`, so shell
observation does not need application-storage keys or profile serial lookup.
Unknown and unsupported task users cannot overwrite current-profile geometry
or receive its DPI. Application details, shortcuts and force-stop resolve the
explicit profile before dispatch; force-stop uses its resolved user id.

The Desktop directory remains a single shared MagicDesk workspace, not a
separate directory per application profile. File placement is path-based, while an Android application's profile is stored
inside its Desktop Entry. This does not grant access to another profile's
files or URIs. No Private Space permission or additional profile UI is declared
by this foundation.

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
- `ShellDesktopFocusController` verifies task and input commits on every
  platform. A missing task sample, inactive controller, or unconfirmed input
  target cannot acknowledge command success. The independent session option
  `FOCUS_REPAIR`, enabled by default on every platform, enables recovery when
  task focus changes but the InputDispatcher window remains stale. It reports
  only confirmed mismatches on the current input display. A remembered
  desktop task without a focused window is normal while the phone owns input;
  neither late task callbacks nor post-command repair may reclaim that focus.
  An unknown input display does not authorize repair. The existing one-shot
  input snapshot supplies this check without another poll or gesture monitor.
  The UI process then relayouts the existing,
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
  Its workspace queue covers activation, taskbar demotion, Alt+Tab, MCP focus,
  Show/Restore, and session workspace restoration, without a separate thread.
  Ordinary and mixed-workspace freeform selection submit one native `TO_FRONT`
  through `ShellWindowTransitionExecutor`, so WM assigns task surface layers
  together with the hierarchy. A plain WCT sync callback did not guarantee that
  layer assignment. Fullscreen-plane selection retains its atomic WCT and
  explicit organizer-surface composition; no second focus or raise is appended.
  Before the final plane composition, the native freeform phase passes
  the framework-owned transition/input barrier in `FrameworkWindowCommitBarrier`.
  This prevents its finish transaction from overwriting a plane demoted below
  HOME. The barrier is global and internally bounded, with duration diagnostics;
  command acknowledgement still requires the surface and input-focus checks.
  Ordinary task close uses Android's task lifecycle through `TaskRepository`.
  A topology-owned fullscreen plane close first commits survivor focus and then
  removes the background task, while package force-stop first commits the
  surviving desktop task and only then stops the package.
  Pre-focus preparation sets HOME's intended focusability on every platform;
  it does not pulse that state. Only confirmed stale-focus repair requests the
  optional relayout pulse. Callback-driven repair is not scheduled when that
  policy is disabled; command verification still uses the shared event source.
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
  display changes without overriding visible phone tasks. It raises an existing
  touchpad task before starting a replacement and treats restoration as pending
  until task observation reports the touchpad visible. Identical sampled task
  state never repeats the repair command; a changed phone-task observation can
  retry it without another poller. Phone apps, controls and the self-test phone
  guard all retain their foreground through this same task-visibility rule.
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
  `ShellTaskLauncher` explicitly requests `ACTIVITY_TYPE_STANDARD` for ordinary
  application launches. Direct and app-created PendingIntent launches retain
  their exact component identity through the shell boundary; selection
  surfaces and published shortcuts deliberately use package identity. The
  launcher snapshots task ids across every display before the start and may
  roll back an invalid identity or topology only for an id both reported
  by the framework's task-created callback and absent from that snapshot. An
  existing task moved from another display can therefore never be mistaken for
  a newly created task and removed.
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
  the same platform policy. Synthetic hover and clicks use standard
  display-targeted Android mouse events through the shell service.

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
  Running-task queries omit application Intent extras at the framework boundary;
  component, data URI, categories, and flags remain available for task identity.
  Unused launch payloads must not consume the shared Binder buffer on every
  observation or explicit window command.
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

`DesktopTaskRuntime.observedTaskSnapshot` publishes the last complete app-side
repository observation for the active desktop, including its phone tasks,
before workspace filtering. Task Manager and explicit MCP task waits reuse
this publication instead of issuing a second periodic task query. Missing
readiness, a disconnected observer, or a stopped session invalidates it;
unknown is not an observed empty task list. Explicit user commands retain
their fresh repository reads.

Runtime timing has three explicit mechanisms:

- `EventDrivenWaits` wraps monitor waits released by a concrete callback or
  state publication. These waits consume no periodic CPU while idle.
- `BoundedStateAwaiter` owns polling only where the framework provides no
  reliable callback. Every call declares a semantic reason, deadline, and
  sample interval; self-tests use the same classification.
- `RuntimeDelays` owns intentional non-state pauses such as input gesture
  spacing, supervisor backoff, vendor command settling,
  watchdog ticks, and stream heartbeats.

Direct `Thread.sleep`, `SystemClock.sleep`, and `Object.wait` calls are rejected
outside these timing boundaries. Compatibility Diagnostics reports their
runtime counters and the last classified reason. The task observer's 150 ms
fallback remains separately visible in the framework runtime line because it
is a permanent active-session observation source, not a transition delay.
Input-window event registration, callback count, bounded waits, and timeouts
are reported separately as `inputWindowEvents`; they never share that polling
interval.

On frameworks that publish `TaskInfo.requestedVisibleTypes`, the task observer
uses it to correlate application-requested immersive state. Field presence alone
is insufficient: some Android 15 releases expose the field but always publish
`defaultVisible()` unless `enableFullyImmersiveInDesktop` is enabled. The
compatibility adapter checks the framework flag once, using the desktop flag
wrapper when available to retain its override semantics. It does not change
system feature flags. An absent field, disabled publication, or unreadable flag
reports the observation as unavailable, with the reason in Diagnostics, rather
than as a synthetic non-immersive request. The task
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

Existing-task launch options use the same compatibility adapter for the optional
flexible-size hint. Frameworks without that method retain explicit launch mode,
bounds and parent; the Android 15 debug profile also omits the hint. An error
executing an available method is propagated, not treated as an absent capability.

`FrameworkDisplayCaptureApi` owns logical-display screenshots and pixel samples
through `IWindowManager.captureDisplay`. WindowManager resolves the logical ID
to the display layer tree, including virtual displays; the caller does not need
a physical-display token. The framework's bounded capture-listener wait is
classified as `DISPLAY_CAPTURE`. Capture is on demand only. The shell service
uses a reliable pipe so MCP receives capture errors instead of an empty image.

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
  pointer, input, phone UI, audio, diagnostics, controls, launch
  targets, or runtime behavior. `ComposedPlatformDriver` uses that declaration
  as the source of truth and rejects a declared component with no
  implementation. `PlatformSelection` records the provider and detection
  evidence for every component. Hardware family names alone do not select a
  vendor implementation. A stock Nubia or REDMAGIC fingerprint or the
  `redmagic.app.manager` service selects the complete Nubia extension. On an
  AOSP-derived ROM for Nubia hardware, passive probes select only independently
  present projection, pointer, internal-audio, diagnostics,
  and hardware-control components; all others remain on the Standard Android
  baseline. The probes run under the ordinary application UID, do not require
  Shizuku, and do not invoke the detected operations. In particular, absence
  of `redmagic.app.manager` keeps the vendor property writer out of Device
  Setup without suppressing unrelated APIs retained by a hybrid ROM.
  `PlatformDriver` exposes only existing variation points.
  `PlatformWindowingDriver` owns provisioning properties;
  `PlatformProjectionDriver` owns output modes, wireless-launch integration,
  and caption transport; `PlatformPhoneUiDriver` owns phone-screen power control;
  `PlatformPointerDriver` owns optional read-only cursor observation. On Nubia
  firmware this is implemented by `NubiaDesktopPointerDriver` over the hidden
  global position query. Physical input
  routing itself stays in the shared Android implementation and uses standard
  input-location to display-unique-ID associations. Cursor observation is
  independent of device routing and shortcut filtering.
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
  using the two shared required freeform/resizable settings. It does not own the
  system projection transport, and its phone-UI, cursor-observation, output-mode,
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
- `DesktopCompatibilityPolicy` is the immutable selection of six optional
  shared mechanisms: input-focus repair, stale caption
  refresh, phone-task isolation during wired/wireless sessions, retained
  phone-task recovery, stale phone freeform Recents cleanup, and Recents routing
  to the leased phone HOME. `PlatformFeatures.compatibilityDefaults` supplies
  recommendations only; `MagicDeskSettings` stores independent user overrides.
  The Android baseline recommends focus repair enabled; firmware extensions
  may recommend additional options. An explicit user disable takes precedence.
  The Compatibility settings section is available on every platform. Enabling
  an option neither grants privileges nor guarantees framework support.
  `DesktopSessionController` resolves the selection;
  `DesktopHomeRoleLease.prepare` persists it before HOME activation.
  Repeated Open, settings refresh and host recreation reuse
  it. The typed observer configuration carries that selection to shell;
  helper policy suppliers read only this session snapshot, never preferences.
  Explicit close captures its recovery decision before releasing HOME. Display
  removal retains its own decision; deferred local cleanup persists that
  decision with its pending marker. New preferences cannot rewrite old cleanup.
  No extra observer, poller or worker is introduced. Input commit verification,
  owned-task parking and ordinary session cleanup remain unconditional.
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
- `DesktopInputSession` owns input routing and the virtual phone pointer;
  `DesktopShortcutService` filters desktop shortcuts, and
  `HardwareKeyboardLayoutController` owns layout selection.
- `PhoneTouchpadController` starts and repairs the phone touchpad for an owned
  external target whose display driver permits it. The shared transport checks
  virtual-mouse and routing readiness before delivering input.
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

- input routing and shortcut service ownership;
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

The native relay owns both directions in one nonblocking poll loop, with
bounded input/output buffers and incremental control-frame decoding. A partial
frame or backpressure in one direction cannot block the other direction or
shutdown. Process signals wake the same poll owner; cleanup has a bounded
HUP-to-kill sequence for the owned shell group, not a separate worker thread.

`ConsoleTerminalSession` owns transport and terminal state for one window.
Its PTY-to-UI output buffer is bounded; a busy UI pauses the reader on a drain
event rather than dropping terminal bytes or growing an unbounded queue. Closing
the session releases that wait. Metadata requests use the same session writer,
with `TerminalRequestScope` completing every pending response when the session
closes, even if executor teardown discards its queued work. No additional thread
or periodic query is involved. A resize received while the transport opens is
applied to the PTY before sending input queued during startup.
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
production Android integration path; developer MCP exposes the same operations
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

`TermuxCommandResultReceiver` owns each result callback, timeout, and one-shot
`PendingIntent` as one registration. Completion, cancellation, and timeout all
remove that registration and cancel its remaining resources. The callback
Intent uses a unique data URI, so a token retained by Termux across MagicDesk
process death cannot match a later request. `DesktopExecSessionTracker` records
each execution separately, including repeated launches of the same command;
late start acknowledgements cannot reopen a completed diagnostic session.

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
`DesktopHomeSurfaceRouter` atomically exposes exactly one primary HOME Activity
before the role is claimed. External targets use `PhoneHomeActivity` on display
0 and launch `DesktopActivity` through the typed Shizuku task API as the
`SECONDARY_HOME` task on the selected display. A phone target exposes
the dedicated `PhoneDesktopHomeActivity` as primary HOME, so Android creates
the desktop host directly in its standard task area without conflating it with
the external-display host component.

The lease is the only owner of HOME transitions and HOME-surface selection.
Normal close quiesces MagicDesk's HOME entry points, restores the previous
holder, and retains existing HOME surfaces through workspace teardown. It
disables those components before presenting the restored launcher; later
cleanup failure never reclaims HOME for MagicDesk.
Unexpected display loss releases a live lease through the same role boundary,
and a user-selected third-party HOME is never overwritten. If a new MagicDesk
process starts while still holding HOME, the startup guard disables its HOME
surfaces, discards the stale lease, and opens system HOME immediately without
waiting for Shizuku. Subsequent HOME admission checks the current active lease,
not a process-lifetime recovery flag, so a new explicit session can start in
that same process. One event-driven reconciliation clears a release record
left after HOME was already transferred before process loss. This recovery does
not add a runtime polling loop. `DesktopOperations` owns the common target-aware
close operation; transport-specific code stops at target preparation.

- An already connected wired or wireless secondary display enters
  `DesktopSessionController` directly on every platform. Closing the desktop
  returns its application tasks to the phone but does not disconnect or
  reconfigure the system-owned transport.
- The Nubia projection extension may configure physical HDMI timing and
  native caption visibility. These are independent capabilities and
  do not create or own a second logical display.
- Starting an external desktop requires an existing Android secondary display.
  A separate **Wireless** action is exposed only when the
  selected platform driver provides an available connection UI. Standard
  Android resolves `Settings.ACTION_CAST_SETTINGS` through PackageManager and
  opens it with ordinary application permissions; the manifest declares that
  query. The Nubia implementation opens SmartCast. Both return to Phone Control
  Panel after Android reports the Wi-Fi display, without starting the desktop
  implicitly. Cast-settings availability does not guarantee Miracast support;
  a real secondary display must still appear in the shared display catalog.
- Once Android reports a Wi-Fi display, MagicDesk passes that display ID to the
  common desktop session. It does not implement a second discovery or streaming
  stack.
- Display preparation and session ownership are separate. The phone panel
  selects a live `DesktopDisplayInfo` from `DesktopDisplayCatalog`, with source,
  unique identity, dimensions, support, and removal ownership. Secondary built-in
  screens remain explicitly unsupported until their HOME/input path is verified.
- `ShellVirtualDisplays` owns headless Android virtual-display tokens independently
  of HOME/tasks. Several displays may coexist, but only one desktop session runs
  at a time. Creation size and density are configurable; scrcpy captures an
  existing logical display without owning its lifecycle. Android 15 primitives
  and hidden flags belong to `FrameworkVirtualDisplayApi`. App-owner Binder death
  releases its display tokens. A callback-driven ImageReader supplies the
  output Surface required to keep the display ON on Android 15 and 16.
  Frames are discarded without pixel reads; there is no timer or per-display
  thread. Keeping a virtual display alive still has Android rendering costs.
- The optional phone-preview type uses Android's overlay adapter through
  `SimulatedDisplayLease`. The setting replaces the entire overlay set, so
  creation refuses an existing overlay instead of disturbing it. Headless
  displays have no such singleton limitation. The four existing session drivers
  remain; both virtual sources use the standard simulated-display path.
- Close Desktop restores HOME, input, and tasks without deleting any display.
  Explicit removal validates both runtime ID and unique identity, requires
  MagicDesk ownership, closes an active session on that display first, then
  waits for transition quiescence before releasing the display token. Wired,
  wireless, foreign virtual, and built-in screens cannot be removed this way.
  DisplayManager callbacks refresh the panel; catalog reads add no polling.

When a new external desktop task is ready and automatic touchpad opening is
enabled, `PhoneTouchpadController` opens `MagicDeskTouchpadActivity` on display 0
if that display driver permits it. No absolute-position API is required.

The runtime asks the selected platform to expose native captions for wired and
wireless desktops. The Nubia driver applies its matching privacy filter;
standard Android and simulated displays do not modify vendor SurfaceFlinger
state. Android associates input locations with stable display unique IDs on
every target. Simulated sessions exercise the same phone IME policy, shortcut
filter and virtual phone-pointer lifecycle as a physical desktop.
Virtual input remains scoped to the session and cleanup waits for
its removal before the test completes. The test inspects WMShell's caption and
resize input windows after a cross-display move, including their display ID,
frame, input channel, token, and
touchable region. Synthetic events target an explicit display; physical pointer
dragging additionally exercises InputReader's device associations.

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
Preparation and execution belong to `DesktopSelfTestLauncher`, not to an
Activity instance. Diagnostics observes immutable `DesktopSelfTestRunState`
progress through lifecycle-scoped invalidation callbacks and collects a full
report only outside an active run. The same progress is exposed through MCP.
For non-phone targets, `DesktopSelfTestGuardWindow` connects Diagnostics'
resume/stop events to the existing phone input guard. It reuses the report task
and hides it before phone-UI and Close-to-HOME assertions; there is no separate
guard Activity. Unexpected input remains recorded, while the Stop button
requests cancellation of the exact run. The harness restores the report only
after production cleanup and destination checks.
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

That scenario has a checked cleanup boundary independent from its assertions.
In a full run, a failed required application-fullscreen step skips only its
dependent checks. The harness closes its temporary fixtures and verifies the
primary window's restored mode, bounds and input focus before resuming the
other window tests. The task-stack observer sees a separate cleanup stage;
restoration and removal cannot be attributed to the prior fullscreen contract.
Fail-fast, cancellation and failed cleanup still unwind to the global finalizer.

The phone-to-desktop transfer probe captures its reference after the source
task has left the desktop and the production Show Desktop command has committed.
It compares the transfer against that settled destination, not a previous
workspace with the source window's shadow. Pixel tolerances and first-visible
freeform mode/bounds assertions remain the same.

The simulated self-test owns its fixture display through a Binder-owned shell stream;
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

Task placement has one shared policy rather than separate launch, reuse,
parking, or self-test routes. A running task is hidden and normalized as
fullscreen on its source display. One WMShell `CHANGE` transaction then combines
the existing-task launch on the destination with its fullscreen or freeform
mode, final bounds, density, caption policy, and reveal. Fullscreen clears the
source bounds and inherits phone density when returning to ordinary phone use.
Both modes share this transaction builder and failure restoration path; neither
uses a raw root-task display move followed by a separate reveal/focus operation.
WM owns the cross-display task leash as well as the task configuration, which
alone can report correct fullscreen bounds while an old surface crop remains.
Fullscreen return also uses the existing framework transition/input barrier
before a caller can reuse the task with its ordinary launcher Intent.
The source therefore never contains a freeform transfer state, while the first
visible destination state already has the final geometry and native
caption/input surfaces. The simulated driver
deliberately uses this same path to model external-display behavior without
connected hardware.

`PhoneDesktopHomeActivity` is primary HOME in Android's default task area.
Freeform applications remain standard root-workspace tasks above that HOME.
Its `singleTop` launch mode lets Android reuse HOME inside the standard HOME
root. Android may also create HOME in an organizer task area. Those instances
delegate navigation to the registered desktop host without creating another
desktop UI or session. Their separate HOME roots are non-focusable and forced
translucent, with Activity input sinks disabled through the shell task runtime.
Typed task-area identity keeps them out of application visibility policy. They
remain alive until their area is removed: finishing one while its area remains
would make Android immediately launch its replacement.
The application explicitly enables `OnBackInvokedCallback` in its manifest.
Without that opt-in Android 15 rejects callback registration, so Back would
finish HOME instead of invoking the desktop's existing Back handler.
Fullscreen applications use the same independent per-task planes as every
other target. The exact SystemUI desktop-wallpaper activity is suppressed only
while phone desktop is configured, because that AOSP surface exists to cover
Launcher and would otherwise cover MagicDesk's desktop icons as well. Taskbar
visibility follows fullscreen and auto-hide policy directly.

A cold freeform launch is staged behind the desktop host until Android assigns
the task ID, then one complete WMShell `OPEN` reveals its final mode, bounds,
and front order. The launch options explicitly classify the application task
as STANDARD, and the returned task must retain that type on the requested
display. A running cross-display task uses the same prepared transfer protocol.
Reusing a fullscreen task on the desktop display claims its exact
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
the explicit transfer scenario. The guard requires a visible desktop task at
committed stack boundaries. HOME visibility metadata may change while a
freeform fixture stays visible; that flag alone is not a surface failure.
Separate wallpaper and taskbar assertions check the rendered desktop.
No guard snapshots are taken
during normal desktop operation, and the guard uses neither polling nor timing
guesses. Android can deliver remote `onTaskMovedToFront` before the matching
visibility update; only a gap beginning at that callback may remain pending,
and it must resolve by the coalesced `onTaskStackChanged` callback or the test
stage boundary. Other visibility gaps fail immediately.

Before the first desktop input step, `TASKBAR-002` verifies both the host's
logical taskbar state and one pixel from the rendered taskbar surface. The
capture runs only inside the manually requested self-test and detects a panel
that is logically shown but composed below another surface. Closing the
isolated desktop session emits a lifecycle cancellation event; the test stops
at its next checkpoint and proceeds directly to cleanup instead of recording
failures against a session that no longer exists.

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
reserves the status and navigation bars and places its taskbar above the stable
navigation inset. Visibility changes do not move the desktop because geometry
uses the bars' ignoring-visibility insets. A dedicated external display
normally reports zero system-bar insets and fills the panel. The desktop
viewport provides separate control and surface bounds for the taskbar. On the
phone display the visible surface extends through the stable navigation inset,
so its application panel paints that inset as taskbar chrome even when a
managed fullscreen plane covers HOME. The taskbar controls retain their
ordinary height above the inset. On displays without a lower inset the two
bounds are identical. The attached application panel does not apply system-bar
or IME insets a second time. When managed fullscreen policy conceals the taskbar,
the bounded panel collapses to its reveal edge; an unrelated foreground task
removes the panel entirely. The transparent, non-input chrome host remains
structurally stable without leaving the taskbar backdrop over fullscreen content.
There is no separate phone implementation of the desktop.
IME visibility may keep an
auto-hiding taskbar logically presented, but it never moves the taskbar surface:
the keyboard temporarily covers the physical bottom edge instead of relocating
desktop chrome into the workspace.

The wallpaper is a full-display backdrop outside the inset-aware desktop
content layer. Status-bar and viewport changes therefore reposition icons and
windows without rescaling the wallpaper. The wallpaper controller center-crops
the source once into a physical-display-sized frame with `Bitmap.DENSITY_NONE`.
This pixel-sized frame must not inherit source or process density:
`BitmapDrawable` otherwise scales its intrinsic size again for the target
display, even with an identity image matrix. The view uses a fixed
top-left image matrix, so a transient system-bar inset cannot recrop that frame
when HOME loses focus. Wallpaper readiness is published only after the selected
bitmap reaches a committed frame; reload generations discard stale callbacks
without a settling delay. On the phone display, opaque desktop-chrome backdrops
cover the reserved status- and navigation-bar insets above the wallpaper.
Android can therefore keep normal system-bar behavior for HOME and freeform
tasks without exposing bright wallpaper strips around snapped windows.

The desktop chrome host is a translucent, normally non-focusable `MULTI_WINDOW` task in its
own root-level organizer area beside Android's standard task workspace. The
taskbar itself is a bounded child application window,
so a foreground application that suppresses non-system overlays cannot
suppress it. Empty task bounds fill the area without a freeform caption.
Both the area and host task are `alwaysOnTop` in `MULTI_WINDOW` mode:
Android 15+ ignores this flag in fullscreen mode. Native DisplayArea ordering
keeps chrome above the application workspace and below system windows and IME.
Keeping chrome outside the workspace also avoids the root-task sibling cast
in `ActivityStarter` during app-owned child/result launches.
The shell disables that Activity's Android 15+ ActivityRecord input sink, so
only the taskbar window's bounded touch region receives input and pointer events
outside the panel continue to the desktop and application windows.
Auto-hide keeps the non-touchable host geometry stable and resizes the
application panel containing the taskbar View to its reveal edge. The same
bounded window therefore owns visible taskbar input and
hidden-edge hover without forwarding synthetic events. It adds no polling and
keeps the input frame aligned with the visible edge. `ShellDesktopSurfaceOrder`
only orders fullscreen planes within the application workspace. Chrome's
framework priority handles window relayout without retaining its organizer
leash, manually setting a surface layer, or appending a transaction or wait to
application operations. Both the visible panel and hidden
reveal edge share this ordering policy rather than separate layer fixes in
taskbar clicks, Alt+Tab, overview, or MCP.
Start, context menus, notifications, and dialogs reuse this
same application token rather than creating another infrastructure task.
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
phone session uses `PhoneDesktopHomeActivity` as primary HOME. The task layer enforces a
separate invariant: no application task may remain freeform on display 0 after
migration or teardown. `ShellExternalTaskMigrationGuard` normalizes
system-driven moves during an external session, and
`PhoneDesktopTaskRecovery` reconciles live tasks with WMShell's retained desktop
repository after desktop close or external-display loss. Already migrated
phone tasks can still be indexed under the external display, so recovery
checks all repository groups against the live display-0 task snapshot. It does
not move tasks that remain on another display or revive unrelated external
entries. External Close runs this reconciliation before presenting restored
HOME, including when the physical monitor remains connected.

`PhoneHomeActivity` embeds `StartMenuContent`, the same contents used by the
desktop `StartMenuController` popup. Each host owns its own view, query, page,
selection and focus; both Starts can be visible at once. Phone Recent is
derived from ordinary display-0 tasks through `PhoneRecentApps`, not from
desktop history or only the applications launched by Start. It includes
launchable phone applications opened from notifications, filters HOME and
shell surfaces, and deduplicates application identities in snapshot order.
The external Start retains its independent desktop launch history. Phone HOME
requests a typed task snapshot only when resumed or when Recent is selected;
stopped instances discard pending results. An unavailable snapshot is an error,
not a fabricated empty history. The phone uses only application search, without
constructing the desktop file-search worker. The application catalog reuses
`LauncherAppRepository`; phone loading is asynchronous and invalidated by
`LauncherApps.Callback`, not by a timer. Grid capacity follows each panel's
measured viewport, including keyboard resizing.

Phone selection uses `PhoneAppLauncher`, outside desktop launch integrations,
saved bounds and density profiles. A one-shot typed task snapshot identifies
an existing external task only when there is no matching phone instance. Such
a task returns through `TaskRepository.moveTaskToDisplay` before the ordinary
display-0 launcher Intent is delivered; this avoids cross-area Intent reuse
inside Android's ActivityStarter. No duplicate task or alternate transition
protocol is introduced. The phone HOME also exposes phone controls, touchpad
and production Close. An explicit HOME launch releases the existing touchpad
request so its recovery mechanism cannot cover the requested phone Start.
It registers a display-0 automation surface without registering a desktop host.

The optional `ShellPhoneOverviewRouter` starts independently of the main
activity-start observer. When `RECENTS_TO_HOME` is enabled, routing becomes
active only after resolving the system Recents component and preparing its
existing tasks. Missing capabilities or preparation failures leave routing
disabled and report the reason through `TASK-OBSERVER-RUNTIME-001`; they do not
disable task observation or intercept the ordinary system Recents request.
There is no background retry.

The Overview router may remain registered while task teardown is still
finishing, but it cancels the firmware Recents launch only after the app-side
callback confirms an `ACTIVE` HOME lease. The lease enters `RELEASING` before
HOME is transferred on normal close, failed start, self-test cleanup, or
unexpected display loss. Recents therefore returns to the system launcher at
the HOME ownership boundary rather than at the end of task cleanup; the check
runs only for an attempted Recents launch and adds no background work.
After a user session relinquishes HOME, the close coordinator explicitly
presents the verified current role holder once task-session and display
teardown have completed. Role transfer remains the first close operation, but
the later presentation avoids asking Android to start HOME through an
organizer hierarchy that is being removed. It also prevents Android from
leaving the now-inactive `PhoneHomeActivity` task visible after the role itself
has already changed.
All MagicDesk HOME components are disabled outside a desktop session,
including their manifest defaults. Preparation enables the target primary surface
and, for an external session, `DesktopActivity` as `SECONDARY_HOME` in the same
component batch, before role acquisition and HOME presentation. Normal Close
returns the role first but keeps existing HOME surfaces alive through task
parking and host/display teardown. Only the final close phase disables the
components and clears the `RELEASING` lease, before presenting restored HOME.
Disabling a live Activity component itself starts Android CLOSE transitions;
it is not a harmless way to update role eligibility while parking tasks.
The runtime's existing start/close gate prevents recovery callbacks from
finalizing that lease concurrently. Existing HOME instances ignore new HOME
requests during release rather than destroying the host ahead of its owner.
Close, rollback, and session loss leave all three disabled;
neither primary nor secondary launcher choices may offer inactive MagicDesk.
A missing role holder
therefore reaches Android's launcher resolver without selecting inactive
MagicDesk again. Process-start recovery disables the surfaces before Shizuku
is needed, and detects both a stale lease and HOME resolution to MagicDesk,
not just `RoleManager.isRoleHeld`.
An isolated self-test closes its own fixtures through the production task
controller before calling the common Close-to-HOME coordinator. Workspace
isolation does not suppress restored HOME presentation. The harness verifies
the concrete primary HOME component on display 0 before restoring its report;
a secondary launcher from the same package is not an equivalent result. If the
display-removal suite already completed production display-loss recovery, the
expected phone destination is Control Panel instead.
The routed request launches an ordinary, package-scoped HOME Intent on display
0. During an external session it selects Recent within the existing phone
Start; during a phone-desktop session Android selects the desktop HOME and its
normal workspace presentation. There is no separate phone Overview Activity.
Managed tasks remain available through the desktop taskbar, task overview and
Alt+Tab. With `RECENTS_TO_HOME` disabled, the system retains its native gesture.
This common compatibility preference uses the platform provider's recommended
default and can be overridden by the user for the next session.

Returning to an already active desktop is display-scoped and does not restart
the session. `PRESENT_WORKSPACE` orders every managed fullscreen plane below
the HOME host and raises every live managed freeform task above it. On a phone
desktop, Android's HOME intent and a repeated **Open desktop here** request use
this operation; a foreign fullscreen phone task is left to Android's normal
HOME transition. The external-session touchpad exposes the same operation for
its target display, so its own phone task and every other display remain
untouched. `PRESENT_DESKTOP` remains the separate command that conceals all
application windows to expose bare wallpaper.

**Open desktop here** is a phone-target action. The control panel keeps it in a
stable location but disables it while an external desktop session is active;
the action boundary rejects the same conflicting request if it arrives through
an intent instead of the button. **Start external desktop** and wireless
connection actions are disabled while a phone desktop is active, with the same
guard repeated at their action boundaries. Switching targets therefore requires
closing the current desktop session first.

Task mode is not an ownership signal on display 0. MagicDesk claims a task
before submitting a desktop launch or window transition, and only claimed
tasks publish immersive, orientation, mode, and bounds changes to the desktop
window controller. This prevents a SystemUI launch that briefly reports
freeform from being restored or resized by MagicDesk. The active external
display remains an ownership boundary of its own, so every standard task
observed there is published regardless of mode.

Task snapshots and windowing commands issued through `TaskRepository` share a
single `TaskCommandQueue` with phone-task recovery. Recovery checks session
ownership before every mutation. Removed-display recovery is cancelled when a
new phone or external target is prepared, without waiting for its HOME host.
Ordinary taskbar operations cannot interleave with recovery commands.

Each desktop target has a profile keyed by its Android display identity, never
by the transient logical display ID. Profiles store only DPI and wired output
timing. Files and `.desktop` shortcuts under
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
adapter used by Desktop and Files. Each argument validates its 8192-character
path/URI limit at construction, including the escaped file URI; selection and
automation readers check the 128-item limit before materializing arguments.
Commands without field codes retain raw shell syntax, while expanded values
are tokenized and shell-quoted. Expansion writes directly into the bounded
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
State and wallpaper writes use an exclusively created temporary file in the
metadata directory for each operation. Overlapping Binder calls cannot remove
or publish each other's staged bytes; the last successful publication wins.
Writer and publication failures remove only that operation's temporary file.
Publication requests an atomic replacement, retaining a regular replacement
fallback for filesystems without atomic moves; this is not a power-loss
durability guarantee.
Metadata observation forwards changes to the published state or wallpaper
path, plus invalidation of the metadata directory itself. Temporary-file
events do not trigger a state reload or a wallpaper decode. The state reader
enforces its 2 MiB byte limit during streaming, not just through a prior file
size check; wallpaper transfers enforce their 64 MiB limit the same way.
The wallpaper writer owns its incoming descriptor before preparing the
metadata directory, so preparation failures release it as well.
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
Share URL extraction rejects source text exceeding 32 Ki UTF-16 code units
before copying or scanning it. Suggested titles and bounded clipboard reads
use `BoundedText` to retain complete surrogate pairs within their existing
code-unit budgets; title whitespace normalization remains a separate policy.

`ShellFileSystem` deliberately exposes the complete filesystem visible to the
connected UserService identity. Path validation requires normalized absolute
paths, protects the filesystem root from mutation, prevents recursive copies,
and treats symbolic links as links during copy and delete. Copy, move, and
recursive delete run on one operation executor only after an explicit user
action. Operations support cancellation and Binder-owner death; there is no
file-manager polling or idle worker loop. Name conflicts receive a numeric
suffix, so an interrupted copy never begins by deleting an existing target.
URI imports from both Desktop and Files use the same provider-name validation
and shell-side name reservation. The UI does not enumerate a partial directory
page or maintain its own occupied-name set. Collision handling follows the
destination filesystem's case rules, with exclusive creation deciding races.
`FileTreeTransfer` owns the filesystem copy/move mechanics, independently of
Binder callbacks. Copies create files exclusively with `CREATE_NEW`; a target
that appears after name selection is a conflict, never an instruction to
truncate it. During an explicit copy, a transient list records the created
entries and their parent identities. Rollback visits only those entries in
reverse order, checks their filesystem keys and parents, and deletes directories
non-recursively. Replaced paths, unavailable identity, or foreign children leave
the affected partial result intact instead of risking unrelated data. The list
is discarded when the operation completes; it is not a persistent index.
If cross-filesystem move cleanup fails after a complete copy, the destination
is retained rather than risking loss of both copies.

`FileOperationCenter` owns copy, move, and delete at MagicDesk process scope.
Its `FileOperationState` binds callbacks to the originating request, including
before the remote operation ID is returned. Cancellation, disconnection, or a
new request cannot let late callbacks finish another operation or clear its
clipboard selection. The center owns Binder and UI dispatch; the state model
owns progress and terminal transitions without Android dependencies.
`ShellFileOperationHandle` binds cancellation to the originating UserService,
not whichever service is current when a delayed cancellation is delivered.
Files windows subscribe only to immutable progress snapshots, so closing the
window does not cancel a remote operation. The process Binder remains the
remote owner: process death still cancels work, and a disconnected shell turns
the active snapshot into a bounded failure rather than leaving a permanently
busy UI. Imports from external `content://` providers remain Activity-scoped
because their temporary drag permission belongs to that UI interaction.
`ContentRequestScope` owns queued requests on each UI's existing executor.
Closing it releases grants for work that never started and signals cancellation
to running work; a running import releases its grant only after leaving provider
I/O. Executor rejection and release failures also complete the request, and a
closed UI ignores late presentation callbacks. Completion carries both the
operation value and any failure: a grant-release exception cannot erase an
already committed copy or action. Subscribers run after release and outside
the owner's lock; a subscriber failure cannot alter the published completion
or prevent releasing other requests. There is no additional executor
or polling loop. Cancellation is checked before provider access, between chunks,
at EOF, and before accepting the completed output, including empty/text imports.
An already blocked provider read must still return before its worker can finish.
`ContentImportBatch` owns the immutable source list and shared per-item execution
for Files, Desktop, and the Android share receiver. Sources are captured before
queueing; `ContentUriTransfer` binds the provider/text copy operation to that
request. Result counts distinguish committed copies, failed items, and items
left incomplete or unattempted. Cancellation is separate from success and
preserves already committed files and earlier failures. A provider error does
not prevent subsequent items from being imported unless cancellation is also
requested. Files progress counts processed items, including failures, rather
than successful copies alone. Progress-delivery failures stop the batch without
discarding its copy/error counts. Finalization preserves those counts and any
earlier provider failure when resource release also fails. The batch adds no
executor or background work;
`DesktopFileRepository` only loads desktop entries and thumbnails.

`ShellFileCreation` binds a newly created ordinary file to its originating
UserService. Imports and `.desktop` entry creation share this boundary. Writes
verify the original device/inode; commit retains a complete result.
Provider imports close both input and output streams before commit, so an input
close failure still rolls back the uncommitted file instead of leaving a saved
file that the batch reports as failed.
Otherwise close requests an immediate, non-recursive removal after shell verifies
that the path still names that ordinary file. A mismatch leaves it untouched and
cleanup errors are attached to the original failure. This replaces path-only
asynchronous cleanup jobs in Files and Desktop; it is not a filesystem-wide
atomic transaction against concurrent external renames.

`FileManagerActivity` maps the selection model to the same typed operations
for toolbar commands, item context menus, and standard file-manager keyboard
shortcuts. Metadata displayed by Properties comes from the same `stat` result
used for capability identity checks. APK installation is the only package
operation: it is offered only for a selected APK, requires a confirmation that
shows the absolute path, and executes as the already-authorized UserService
identity.

`FileItemContextMenu` renders the same file/folder command model into the
desktop context panel and the Files popup. `ItemActivationPolicy` likewise owns the
shared single-click/double-click decision; selection remains local to each
surface. Desktop placement updates still use the fixed-folder API, while
general copy/move work remains in `ShellFileSystem`, so UI integration does not
widen the automatic desktop-filesystem boundary.

File rows receive ordinary Android pointer meta state. Android delivers
physical `Ctrl` and `Shift` directly, including modifier-click selection.
The shortcut filter consumes desktop commands without forwarding text. Files
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
bounded. `ShellFileGrantStore` makes the same all-or-nothing publication
decision for shell-file clipboard selections and drag-and-drop. It checks the
entire bounded selection before preparing URIs, rejects special filesystem
nodes, and registers a batch only after URI preparation succeeds. Failed
clipboard writes and refused drag starts discard their unpublished entries;
accepted transfers retain them for consumers that open files asynchronously.
The prepared selection carries each URI together with its existing file MIME
metadata. Clipboard and drag producers consume the same typed items, so drag
does not replace known file types with wildcards or require another provider
query. Desktop file drags preserve the metadata from their file snapshot too.
`ShellFileGrantStore.Preparation` reuses that bounded staging map for explicit
Android Open/Share requests. A URI can be placed in an Intent before it is
registered with the provider. These calls publish only after the entire action
and requested display are validated, so a bad final source or malformed launch
option cannot leave a partially registered selection or evict older entries.
Publication precedes execution, not its observation result: a launch timeout
does not prove that a recipient has stopped using the file. Writable access
is capped in the grant entry itself, and the Open Intent uses that effective
value rather than the requested flag.
Files and Desktop can also paste content copied by another Android
application. URI items are imported as files; plain text becomes a UTF-8
`.txt` file (or `.html` when HTML is the only representation). External
consumers always see copy semantics; only MagicDesk can
complete the internal move. A completed move clears only its own generation
and the matching Android URI clip, so an older operation cannot discard a
newer selection or unrelated clipboard data.

Directory pages use a total name tie-break after the requested sort key, so
unchanged files with equal sizes, dates, or case-insensitive names cannot shift
between pages merely because the filesystem enumerated them differently.
`FileDirectoryReader` captures each load's path and sort/filter options in one
immutable request and returns one immutable listing, including desktop-entry
metadata. It abandons superseded work before querying another page or reading
more desktop entries and checks cancellation after blocking reads. A page must
advance its offset and retain the same canonical directory path. Pagination is
not an atomic snapshot of concurrent external directory mutations. The activity
invalidates pending reads on navigation, shell loss, and destruction; failures
clear the backing listing and selection together, not just the visible rows.
`FileManagerStatus` keeps listing summaries separate from operation messages:
automatic refresh cannot erase an import result or failure with an item count.
Explicit refresh, navigation, filtering, or selection clears the message and
reveals the current summary. This uses the existing footer, with no timer.

The current-folder name filter operates only on the already loaded page set;
`Ctrl+F` changes only the local Files presentation. Recursive name search is a
separate explicit action. `ShellFileSystem` walks without following symbolic
links, returns bounded batches through a typed callback, and cancels on request
or Binder-owner death. Files and desktop Start share `FileManagerSearchController`;
each search has one `FileSearchRequest` identity, including callbacks received
before the Binder start reply. Cancellation rejects late replies and signals
the original service through a bound `ShellFileSearchHandle`. The cancellation
is a one-way Binder signal, so closing the UI worker cannot discard it. It
creates no persistent index or idle scanner. Each
Files window also owns a shell-side `FileObserver` for only its current
directory. Callback bursts are coalesced into one posted reload without a
polling interval or guessed delay; manual refresh remains available when a
filesystem cannot be observed. Both the callback and the posted reload retain
the observer's generation; closing it invalidates already queued events, so
an old directory cannot supersede a new navigation request.

Files opened or dragged into another application are exposed through the
non-exported `ShellFileProvider` and a process-local capability URI. The registry
retains at most 256 entries in access order; eviction or process exit expires
an entry, so these URIs are not durable file references. A grant
records the selected path and file identity; each open is performed again by
the UserService and accepted only when device and inode still match. Drag
grants are read-only, while an explicit open grants write only when the
UserService reported the file writable. The receiving application never
receives shell access, a raw privileged path, or the UserService Binder.
`ContentProviderFileAccess` gives both shell-grant and Desktop-file providers
the same cancellation and descriptor-transfer boundary. Cancellation is
checked before opening and after the Binder reply;
a descriptor returned after cancellation is closed, and cancellation retains
its Android exception type instead of becoming a file-not-found error. This
does not interrupt an already running Binder call.
The in-task **Open with** dialog avoids Android ResolverActivity hiding the
desktop taskbar. It reads Android's current preferred handler. Its **Always**
action asks the shell UserService to write the same PackageManager preferred
activity record used by the system resolver; MagicDesk does not maintain a
second file-association database. The same dialog can include executable
Desktop Entries from the MagicDesk desktop when their standard `MimeType`
list matches and `Exec` accepts a file or URI field code. These command
profiles are one-time launch targets: they never enter Android's preferred
activity record and therefore cannot be selected with **Always**.
`FileHandlerRepository` owns blocking PackageManager and desktop-entry discovery
and preferred-handler writes. `FileOpenWithController` uses the existing UI
owner's worker and a replaceable `ContentRequestScope`; a newer file request or
owner teardown discards queued work and stale callbacks. Only result presentation
and launcher callbacks run on the UI thread. While **Always** saves the selected
handler, selection buttons are disabled; dismissing the dialog prevents a late
launch. No dedicated thread or association cache is added.

Files **Share** serializes the same `AndroidContentPayload` used by Desktop and
clipboard actions. **Import files** launches Android's `ACTION_OPEN_DOCUMENT`
surface as a managed STANDARD task. `FileManagerImportController` owns both the
pending picker and its eventual import on the Files window's existing worker.
The worker is free between launch completion and result delivery. Only one
picker can be pending per window; closing Files discards its request, including
a launch reply that arrives after teardown. Late Activity results cannot
recreate a discarded request. Result delivery atomically claims the result and
passes grant ownership to the import. The claim is closed if delivery fails or
the window closes before handoff; queued/running imports release it through
`ContentRequestScope`. Window closure or registry eviction cannot revoke access
underneath a running provider read. A failed launch response still exposes an allocated result
request id; an exception that prevents returning that id discards the request
inside the integration gateway. Dropping a file or other Android content
onto an application shortcut or a concrete taskbar instance enters the same
gateway and preserves the source grant until delivery completes.

Incoming global Android URI drops are copied into the visible Files directory.
Incomplete imports are removed when their recorded identity still matches,
conflicts gain a numeric suffix, and the incoming drag grant is released.
Cross-window import depends on the source
publishing an Android global drag session; private in-window drag gestures are
not visible to MagicDesk. For drags between MagicDesk's own Desktop and Files
windows, `FileDragPayload` keeps absolute paths in process-local state. That
typed path supports files and recursive folders without publishing privileged
paths or inventing directory content URIs; the default action is move and
holding `Ctrl` when the drag starts selects copy. Only a complete bounded
selection of readable ordinary files receives read-only URIs for drops into
other Android applications. Mixed selections are never exported as a subset.
Local-only selections use Android 15's `DRAG_FLAG_GLOBAL_SAME_APPLICATION`,
so files and folders can cross MagicDesk windows without exposing a label-only
drag to other applications. Files passes Android's actual drag-start result
back to the gesture owner. The built-in Console
can be prefilled with the current directory. Process-local file drags dropped
on its input insert normalized, shell-quoted paths but never run a command.
Console can open its current directory in Files, and selected output is treated
as a path only after `ShellFileSystem` verifies the resolved absolute target.
File completion lists the exact parent directory through the typed filesystem
API instead of parsing shell completion output. `ConsolePathText` uses the
same surrogate-safe prefix boundary when completing multiple names, so a
shared half-character cannot become a replacement shell path.
Optional Termux integration
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
The explicit **Run script** action in Files opens Console with a safely quoted
initial command. Console submits that authorized command once its PTY is ready.
Ordinary file opening uses the selected file handler and does not take the
Run script path.

Normal application launch continues to reuse an existing task. The explicit
**New window** action instead requests `NEW_DOCUMENT | MULTIPLE_TASK` and then
tracks the exact returned task ID. Files supports this contract directly;
third-party activity launch modes remain authoritative and may reject the
request.

## External Desktop Activation

On **Start external desktop**, MagicDesk:

1. discovers Android's connected wired, wireless, or overlay display;
2. loads the profile keyed by that display's stable identity;
3. optionally applies a platform-specific physical output timing;
4. corrects geometry and applies the display profile DPI;
5. creates or normalizes the display-sized MagicDesk fullscreen HOME host;
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

Secondary sessions share one display-default policy on every platform:
`SecondaryDisplayWindowing` prepares freeform before HOME activation through
`FrameworkRuntime.displayWindowing()` and the existing Shizuku Binder service.
Display 0 is untouched; explicit fullscreen task modes remain independent.
An unavailable or rejected default-mode request fails preparation.

`DisplayWindowingSession` captures and durably records the previous effective
mode only when it changes the display default. Close restores it after desktop
teardown, and failed startup releases the same ownership. An already-freeform
display requires neither a write nor a restoration entry. Android 15+'s getter
resolves an undefined override into framework policy, so restoration preserves
the previous effective mode, not the absence of a raw override. A different
mode selected by another owner is left intact.

WindowManager retains physical-display overrides after disconnection. Pending
restoration therefore uses the stable display identity, not the connection's
numeric id. Existing display-added and shell-ready callbacks recover interrupted
changes, skipping the active session. Virtual-display entries are discarded
only when the display is gone. There is no additional timer, task sampling,
worker, or idle Binder traffic. Diagnostics reports active display and pending
restoration count. This lifecycle is independent of optional input-focus repair.

### Output timing

Before activation, the phone control panel reads Nubia's current and available
DisplayPort timings from `/sys/kernel/lcd_enhance/edid_modes`. It offers all
valid advertised resolutions and refresh rates, with duplicate timings
normalized. A saved timing is used only while it remains in that list;
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

Changing the physical timing writes the selected EDID mode, pulses HDMI HPD,
then waits for three stable observations of the requested mode. HPD is restored
on failure so an interrupted mode change does not leave the connector disabled.
The physical display id is resolved again because the firmware can recreate it
during this transition. The operation runs before the desktop session starts,
so no MagicDesk task is attached to a disappearing display.

The per-display profile stores output timing independently from desktop DPI.
`PlatformProjectionDriver.prepareExternalDisplay` applies the selected physical
mode in one step before desktop activation. `WiredDisplayDriver` then resolves
the connected display again and starts the desktop on that settled target.
Output timing changes HDMI/DisplayPort geometry; desktop DPI changes UI scale.

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

`DesktopCloseMode` distinguishes Close to the control panel, Close to the
restored HOME (including the phone HOME surface), and full Exit.
Both Close destinations park tasks before releasing the desktop host; showing
the phone control panel is only a presentation choice. Exit skips parking
because its preceding return-tasks step has already moved applications home
and the saved workspace has been cleared.

Before any normal teardown mutation, `DesktopHomeRoleLease` restores and
verifies the exact HOME role state from session start: either the previous
holder or no explicit holder. Before transferring the role, the lease also
resolves that user-selected HOME package to a concrete `MAIN`/`HOME` Activity
through the shell PackageManager and persists its component, package version,
and static availability. This is a one-shot capability snapshot rather than a
launcher invocation or runtime compatibility probe. An unresolved or
unavailable Activity does not block HOME ownership or restoration, which
continues to use the role-holder package as its authoritative identity.
The lease enters `RELEASING` before that handoff so startup recovery can finish
an interrupted release without treating it as an active desktop. If MagicDesk
still owns HOME after process loss, the pre-Shizuku startup guard instead
disables its HOME surfaces and discards the lease immediately. During normal
close the role handoff does not disable Activity components: the `RELEASING`
record retains ownership of the remaining surface cleanup until the close
coordinator finishes task, host and display teardown. It then disables the
components and clears the lease. A later cleanup failure never claims HOME for
MagicDesk again. Unexpected display loss outside an explicit transition restores
the role and disables the surfaces without waiting for a UI callback.

Close is one-way even when a cleanup operation fails. A failed HOME handoff
does not skip input, task, and host release. Explicit display removal that cannot
pass its transition-quiescence gate leaves the display present, but its
desktop session stays closed rather than resumed. Close alone never removes
the display. Failures remain diagnostic
errors; they never reopen input routing or migration protection.

Physical display removal, **Close desktop**, and **Exit MagicDesk** share the
common cleanup path:

- hand HOME back to the package saved by the session lease;
- restore an active phone-display power guard before releasing input, even when
  the external display stays connected and the foreground runtime stays alive;
- release shortcut filtering, display associations, and the virtual phone pointer;
- keep HOME components enabled while parking tasks and removing the desktop host
  or owned display, then disable them before presenting the restored launcher;
- close display-scoped panel windows and stop task observation;
- stop phone-display streams;
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

Explicit Close owns both task parking and the subsequent phone-task
reconciliation; an expected display-removal callback does not enqueue a second
recovery. If the display was removed, Close also reconciles its retained
SystemUI entries within the existing bounded recovery, before returning its
result. All returned freeform user tasks are normalized to fullscreen on
display 0. Unexpected loss owns one cancellable recovery request, using task
events and the bounded display-removal watchdog only while migration is pending.
Success, cancellation, and failure are terminal, including an unavailable task
ID retained by SystemUI. Events caused by recovery itself cannot restart a
completed request, and its late callback cannot affect a newer request or session.

## Window Transitions

MagicDesk operates on exact task IDs. Windowed launches and restores use native
WMShell desktop transitions when available. Snap and maximize reserve the
MagicDesk taskbar; true fullscreen does not.

The native transition probe reads WMShell help instead of branching on the
Android version. It selects Android 15's `desktopmode moveToDesktop` or Android
16's `desktopmode moveTaskToDesk` command when present, and otherwise uses the
direct `WindowContainerTransaction` path.

`AppWindowStateStore` keeps one stable record per `AppReference`: the last explicit
Windowed or Fullscreen choice and, independently, the last confirmed freeform
bounds. Auto launch honors an explicit choice first and otherwise retains the
existing application-compatibility policy. The existing Shell task watcher
emits an event when a task\'s observed freeform bounds or identity change;
`AppWindowStateTracker` converts that event to relative bounds and coalesces a
completed move or resize into one state write. This adds no polling loop.
Bounds are resolved against the active desktop work area when a task is
launched, restored, or moved to another display.

`AppPresentationProfileStore` independently keeps an optional interface-scale
percentage per profile-scoped `AppIdentity`. An absent profile means System: the task
inherits its display density. A custom profile, including an explicit 100%, is
resolved from the active display density when a task is launched, moved,
restored, or changes window mode. The resulting exact task density is carried
by the same semantic command and applied in the same WCT as mode, bounds, and
parent changes. Fullscreen tasks apply the same value to their retained plane,
so moving between windowed and fullscreen does not change application scale.

`AppPresentationRuntimeController` applies profile edits to already running
tasks and reconciles a newly observed task once. It consumes the existing
typed 150 ms task snapshot and display context; it neither reads task state nor
starts a timer of its own. Attempts are keyed by task, profile-scoped application, and resolved
density, so an unsupported or rejected override cannot become a retry loop.
Observer reconnection, a profile edit, or a display-density change creates a
new bounded attempt. When a task leaves the desktop or the session closes,
MagicDesk clears its task and plane overrides to Android's inherited density.

MagicDesk temporarily owns Android's HOME role for the desktop session. The
selected component makes `PhoneHomeActivity` the phone navigation surface for
an external session or makes `PhoneDesktopHomeActivity` primary HOME for a
phone session.
The crash-recovery lease is an exact, versioned snapshot. An incomplete or
unsupported lease is discarded by startup recovery rather than interpreted as
state from an older MagicDesk build.
For an external session, `DesktopActivity` is launched and verified as the root
secondary HOME task on the selected desktop display. The desktop host is an
opaque, display-sized fullscreen Activity; it
does not need a force-translucent override or a post-launch window-mode repair.
The Activity becomes available to parked-task restoration after its first
rendered frame. HOME-role acquisition, root-task creation, and first-frame
readiness are separate lifecycle facts, so callers never infer host readiness
from an arbitrary delay or configuration retry.
The runtime admits one host task per session. Configuration recreation of that
same task is idempotent; a second live task is rejected without releasing the
registered host's taskbar, fullscreen planes, or other session resources.

All phone, simulated, wired, and wireless sessions use one taskbar topology.
Its transparent chrome host is an `alwaysOnTop`, normally non-focusable `MULTI_WINDOW`
task in a dedicated root-level organizer area beside Android's default task
container. The area itself also uses `MULTI_WINDOW` and `alwaysOnTop`.
Freeform tasks remain direct children of the default task area, while managed
fullscreen tasks retain separate organizer areas under that same ordering
parent. `ShellDesktopSurfaceOrder` composes fullscreen planes; native DisplayArea
priority keeps the separate chrome area above the workspace across relayout.

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
exact task, display, required geometry, and resolved presentation density. The
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

Hardware events travel through Android's physical devices and their explicit
desktop display associations. Android owns repeat, modifiers, cursor motion,
acceleration, hover, dragging and secondary-button semantics.
`DesktopShortcutService` consumes only MagicDesk shortcuts; each physical
keyboard has its own `KeyboardShortcutStateMachine`. Consumed key-down/up
pairs remain balanced across modifier release and repeated keys. Unplugging a
keyboard cancels its pending Alt+Tab selection.

`Ctrl+Space` uses `HardwareKeyboardLayoutController` to select the next
configured Android layout for connected physical keyboards and update the
taskbar label. No virtual keyboard identities or copied key streams are involved.

The phone touchpad emits relative movement and native buttons through its
session-owned virtual mouse. Its input location is independently associated
with the desktop. The shared `pointer_speed` setting is applied by Android,
not multiplied a second time in Java. Hardware touchpads recognized as native
touchpads use Android's separate touchpad-speed setting.

## Phone Screen And Touch Panel

MagicDesk uses the shell DisplayManager `power-off 0` contract. A heartbeat-owned
`PhoneDisplayGuard` probes and uses the platform's matching restore operation
(`power-on` on Android 15 or `power-reset` on Android 16) after normal or
abnormal teardown.

`DisplayPowerCommands` owns command discovery for both the guard and shell
diagnostics. It reads help and, when a command is omitted, probes argument
validation without a display ID. Diagnostics distinguish declared commands,
missing commands and probe errors; discovery never changes display power.
The guard publishes its process-local screen state through
`MagicDeskRuntime.refreshPlatformState`; UI command completions and runtime
notifications refresh the controls without a settings observer.

While display 0 is off, RedMagic's independent `cfreezer` applies a separate
screen-off policy. The inspected firmware exempts the selected HOME package;
that exemption does not extend to other desktop applications and ends when
Close returns HOME, before restoring phone power. See
`docs/nubia-vendor-audit.md` for the firmware evidence and verification scope.
The same heartbeat refreshes the vendor's transient
`noteCpuFreezerUidWorking` state for other application UIDs owning live tasks
on the desktop display. It excludes MagicDesk's UID, including its entries in
task snapshots, and relies on the firmware's HOME exemption for the host.
There is no separate working-state request for MagicDesk during Close.
The helper retains the accumulated desktop-app UID set for the screen-off
interval, then clears it during restore. No persistent
freezer whitelist is installed. The optional Nubia phone-UI component is
detected from this service and method; diagnostics inspect the same API without
changing any UID's working state.

`MagicDeskTouchpadActivity` is the common phone-side input panel for external
desktops. It remains an ordinary display-0 Activity and can be opened from the
phone notification or desktop controls. Its relative-motion path uses the
shared native mouse relay described above and does not require a firmware
absolute-position API.

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
a live, authorized shell UserService. Every platform audits the two required
Android settings:

```text
Settings.Global enable_freeform_support = 1
Settings.Global force_resizable_activities = 1
```

`DeviceSetupManager` owns these common requirements; `PlatformWindowingDriver`
adds only firmware-specific provisioning. A session owns its temporary display
windowing default and restores that separately.

`SystemDesktopModeSetting` owns the optional Android global setting
`force_desktop_mode_on_external_displays`, exposed in **Settings > Android system**.
Android remains its only value store. The UI reads current state, confirms the
navigation-bar side effect, then writes and verifies the
value off the UI thread. Changes require shell access and no active/preparing
desktop or retained HOME lease. There is no session override, saved preference
copy, startup write, or required-setup reboot marker. The UI advises reconnecting
the external display; some firmware may require a restart. Neither is a startup
gate in MagicDesk. Close Desktop leaves the value unchanged;
Restore defaults removes the override. The flag affects external HOME, system
decorations and input policy, but does not prove correct physical-input routing.
Physical-input routing is owned by the shared Android input session.

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

Desktop panels and dialogs use ordinary application windows and require no
display-over-other-apps permission. Their short-lived host task is excluded
from Recents and all MagicDesk application-task policy.

The boot ID marks configuration that still requires reboot. MagicDesk never
reboots automatically and has no boot receiver. A successful audit after boot
enters the control panel without flashing setup UI.

**Restore defaults** is available independently of setup history. It stops the
runtime, normalizes stale phone desktop tasks, removes the three global
desktop-windowing overrides, clears the two allowlisted persistent properties,
and resets primary-display size/density/scaling overrides. Removing overrides
lets the firmware supply its defaults and remains usable after MagicDesk has
been uninstalled and installed again. Diagnostics and background audits never
authorize a runtime session or start services.

## Diagnostics

`CompatibilityDiagnostics` records stable error codes with bounded local
history. A bounded set of the 256 most recent distinct signatures suppresses
repetitions, including interleaved events. An evicted signature can be recorded
again. Exact
duplicates left by earlier process runs are also collapsed when the report is
built. The issue report includes firmware identity, displays, external input,
desktop settings, Shizuku UID/domain/capability probes, and MagicDesk-only
logcat. It excludes user files, accounts, notification content, clipboard, and
the installed-app catalog.

Input diagnostics are event-driven lifecycle counters: startup attempts,
ready sessions, refresh failures and the last routing display. The virtual mouse
keeps aggregate protocol and write-error counters; the shortcut service reports
connection state, routed device IDs and command counts. Neither records key
codes or typed text. At the start of explicit compatibility-report
generation, MagicDesk requests one native statistics frame and one bounded
`FrameworkInputSnapshotSource` snapshot. The resulting report compares owned
MagicDesk ports with current InputManager associations and records the observed
cursor observation with its source-supplied display identity (unknown when not
provided), without refreshing the viewport or attempting pointer recovery.
No diagnostic input polling runs during normal desktop use. The
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
decodes its bundled `drawable-nodpi/desktop_wallpaper.webp` resource. MagicDesk Files offers **Set as
desktop wallpaper** only for local image files. The selected file is reopened
through its verified device/inode identity, decoded far enough to validate the
image, and atomically copied to
`/storage/emulated/0/Desktop/.magicdesk/wallpaper`;
selecting **Use MagicDesk wallpaper** removes that override. An unavailable or
undecodable custom image falls back to the last valid custom cache or the
bundled background and
records one compatibility event per distinct failure instead of changing
desktop session state.
Confirmed absence of the custom file clears its cache and selects the bundled
background. A solid-color emergency frame is used only if the bundled resource
cannot be decoded. Wallpaper source selection belongs to the shared desktop UI;
the shell boundary only reads and writes the optional Desktop file. The desktop
folder observer owns custom wallpaper change notifications.
The existing `wallpaper_rendered` event records the selected source (`bundled`,
`custom`, or `fallback`), bitmap/drawable/view dimensions, and density after the
frame commits. Bundled artwork provenance is documented in [Artwork](artwork.md).
Each background load owns a unique temporary cache file. The existing load
generation cancels superseded work before provider reads, between transfer
chunks, and before cache publication and rendering. Cancellation does not
trigger fallback or a compatibility failure, and an already decoded but
unused image is recycled. Temporary cache allocation failure still permits
cached or bundled wallpaper. No extra worker, timer, or polling loop is added.

`CommandConsoleActivity` is a permission-protected, multi-instance desktop task
over a selected `TerminalTransport`. Each Activity owns one independent
interactive PTY, terminal emulator, current-directory state, and selectable
scrollback. Android-shell and Termux transports share this complete UI and
session layer. Input is a byte stream rather than discrete command jobs, so shell
editing, signals, ANSI output, alternate-screen applications, and terminal
mouse protocols retain their normal semantics. Running `exit` or closing the
Activity closes that shell. Commands supplied by explicit Files and Desktop
actions are safely quoted and sent after the PTY becomes ready.

`TaskManagerActivity` consumes the active session's published task snapshot;
outside a session it requests a snapshot on open or explicit refresh. Its
process statistics retain their own UI refresh cadence, without repeatedly
querying tasks. It owns no task-stack parser or windowing policy. Focus, task
close, and explicit
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
- Every configured desktop uses standard-workspace freeform tasks and
  independent per-task fullscreen planes. Session cleanup drains every owned
  plane and its structural anchor.
- Every desktop target hosts the taskbar in one root-level, always-on-top
  organizer area beside the standard workspace. Its chrome task accepts focus
  only for a requested focusable panel or dialog; application tasks never enter
  that area.
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

Every main-app build compiles two native helpers from source: the virtual mouse
and PTY transport. CI verifies that the main APK contains both
and no `.ko`, and that the Kernel Fixes APK contains exactly the reviewed module
and no main-app native helper.

Host regression support under `app/src/testSupport/java` uses the JDK compiler
to execute selected production method bodies against controlled dependencies.
It is compiled separately against the JDK and added only to the unit-test
classpath, never an APK. These deterministic fixtures complement, but cannot
replace, the Android device self-tests.

`scripts/verify-native.sh` builds and runs Linux host fixtures against the real
native sources. PTY fixtures exercise bidirectional backpressure, partial
frames, metadata and shutdown; virtual-pointer fixtures replace only device I/O
to exercise motion, buttons, scrolling, protocol validation and write errors.
They use bounded subprocess lifetimes and a temporary directory, without
physical input access. Linux CI runs them in addition to Gradle verification.

The kernel module itself is not compiled in normal Android CI. Rebuilding it
requires the exact upstream kernel source, config, symbol versions, and guarded
script documented in [VITURE XR resolution fix](xr-resolution-fix.md).

Push CI builds signed development APKs with a unique version suffix and
publishes the main-app artifact. Pull requests and manual CI runs build unsigned
release variants without signing secrets. Both routes run
`scripts/verify-apks.sh` to enforce the main and Kernel Fixes package boundaries.

For a `v*` tag, the release workflow loads signing credentials through
`gradle/release-signing.gradle`, signs only the main MagicDesk APK, verifies its
certificate and package boundary, emits a SHA-256 file, and publishes that APK
as the tagged release. The firmware-specific Kernel Fixes APK is not a tagged
release artifact. Local debug builds never require release secrets.
