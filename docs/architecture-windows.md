# Application Windows And Transitions

[Architecture overview](architecture.md) | [Documentation](README.md)

Task observations, placement and transition ownership. [Fullscreen transitions](fullscreen-transitions.md) defines the topology and focus invariants.

- [Tasks and windows](#tasks-and-windows)
- [Window Transitions](#window-transitions)
- [Shared Task Placement](#shared-task-placement)
- [Workspace Activation And Adoption](#workspace-activation-and-adoption)

## Tasks and windows

- Application geometry policies and their shared executor are described in
  [Application window layout](window-layout.md). Floating resize, snap and
  maximize use the same workspace queue as layout preparation before activation.
  Temporary layout geometry is separate from saved positions and fullscreen
  residency; shell reservations and Android task-area topology retain their owners.
- `TaskRepository` reads exact tasks and performs narrow shell operations.
- `DesktopTaskWatcher` owns the application-side typed task-observer callback
  and immediate focus acknowledgements. Observer configuration runs on its
  existing worker, with copied geometry and latest-request cancellation shared
  with cleanup. The UI thread never waits for topology configuration: shell
  transitions can hold that topology while awaiting an application frame.
  External-task protection is applied with the configuration; the background
  close owner supersedes queued configuration before disabling protection.
- `ShellTaskObserverManager` owns one Binder-scoped observer session inside the
  shell UserService. `ShellTaskObserver` registers the framework listener, and
  `FrameworkTaskObservationSource` centralizes the supplemental task snapshot
  and its typed observations.
- `ShellTaskLauncher` owns fresh windowed launches, independent of display
  type. It observes the new task through the persistent framework listener.
  A task starts behind HOME until its identity is known; one complete native
  OPEN then establishes its freeform mode, bounds and visible order in Android's
  standard root workspace. Callers do not append to an expired opening token.
- `ShellActivityStartController` is MagicDesk's single owner of Android's global
  activity-controller slot and dispatches starts to the external-migration and
  windowed-startup policies. The session option `ACTIVITY_HANDOFF_REPAIR`
  enables `ShellTaskActivityModeGuard`. It correlates Activity starts with the
  existing typed task observer, preserving the task's selected mode and its
  freeform bounds captured before the start. A fullscreen/freeform round trip
  between samples can reset geometry without an observed mode change; this
  needs only the native task-resize operation owned by `HiddenTaskApi`, not
  another mode, focus or hierarchy transition. Repeated starts delivered to
  the same top Activity are correlated by changed bounds as well as mode/top
  changes. Ambiguous targets are not corrected. Explicit user mode changes,
  independent new-task launches and application immersive requests remain
  outside this policy. An unknown immersive observation does not authorize a
  correction. Completed handoffs release their bounds; failed bounds-only
  corrections do not retry during idle observation. There is no package
  allowlist, additional observer or guessed startup delay.
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
  before releasing managed tasks as independent fullscreen tasks on the same
  live display. Confirmed display loss returns them to display 0. Host replacement
  and sudden display removal preserve the latest complete snapshot before
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
- `NativeWindowBoundsController` owns freeform bounds requests and the shared
  restore history for native snap/maximize and MagicDesk shortcuts. Correcting
  Android's full-height bounds to reserve the taskbar preserves the ordinary
  window geometry. Restore uses that same history; subsequent native moves or
  resizes replace it instead of triggering a resize back to the work area.
  A bounds-command completion releases its pending state even if Android
  constrained the requested rectangle or the next observation missed it.
  Confirmed geometry is distinct from requested arrangement. The task retains
  its last observation with the display, native stable area and shell work area;
  a new command invalidates confirmation without forgetting the consumed sample.
  Repeated samples do not replay a native correction or undo a restoration.
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
  by the active session's `DesktopCompatibilityPolicy.Option.CAPTION_REFRESH` option.
  Phone freeform cleanup in self-tests follows the same session policy.
  Synthetic hover and clicks use standard
  display-targeted Android mouse events through the shell service.

## Window Transitions

MagicDesk operates on exact task IDs. Windowed launches and restores use native
WMShell desktop transitions when available. Snap and maximize reserve the
MagicDesk taskbar; true fullscreen does not.

Native caption controls remain opaque. A new full-height freeform observation
can trigger vertical work-area correction. A transition from confirmed full
work-area bounds to full native stable bounds, with both areas unchanged, is
adapted to restore the shared pre-maximize rectangle. This is a geometry policy,
not a caption-click callback; another external resize with identical geometry
is indistinguishable. Own pending commands, half snaps, visibility/mode changes
and changes of display or work area do not establish this restore sequence.
Explicit MCP and Linux maximize requests remain idempotent. Ordinary move and
resize observations do not submit a corrective transaction. Win+Down restores fullscreen first, then
the saved freeform geometry for an arranged window, and demotes an ordinary
window with no remaining restore history.

Explicit half- and quarter-window arrangements retain both the pre-snap geometry
and their requested rectangle in task runtime state. Observing that rectangle
does not erase restore history; a subsequent ordinary move or resize still
becomes authoritative. A rapid snap sequence during fullscreen exit keeps only
its latest target, applying it after the existing transition completes. It does
not create another fullscreen owner, timer, or observation source.

`FrameworkDesktopShellApi` reads complete command signatures from WMShell help
instead of branching on the Android version. Native conversion selects
`desktopmode moveToDesktop` or `moveTaskToDesk` only with an advertised one-task
signature; a required `deskId` is not guessed. Otherwise launches use the existing
`WindowContainerTransaction` path. Phone recovery uses the advertised
`moveTaskOutOfDesk` when the one-task entry operation is absent, preserving its
postconditions for fullscreen mode and repository cleanup. This does not create,
activate or remove Android desks, or alter MagicDesk's fullscreen-plane ownership.
Protocol parsing is immutable and does not execute commands. Native launch keeps
the status-bar passthrough transport; phone recovery keeps the window-shell
transport and its cancellation boundary. A failed available operation is not
retried through another transport or privilege identity.

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

MagicDesk temporarily owns Android's HOME role while any Desktop is active.
The primary `PhoneHomeActivity` keeps the same identity across workspace changes;
its content follows local Desktop residency.
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

## Shared Task Placement

Task placement has one shared policy rather than separate launch, reuse,
parking, or self-test routes. A running task is hidden and normalized as
fullscreen on its source display. One WMShell `CHANGE` transaction then combines
the existing-task launch on the destination with its fullscreen or freeform
mode, final bounds, density, caption policy, and reveal. Fullscreen clears the
source bounds and inherits phone density when returning to ordinary phone use.
Both modes share this transaction builder and failure restoration path; neither
uses a raw root-task display move followed by a separate reveal/focus operation.
Failure recovery passes the framework transition/input barrier before resolving
the task's current display; an expired acknowledgement does not cancel a move.
WM owns the cross-display task leash as well as the task configuration, which
alone can report correct fullscreen bounds while an old surface crop remains.
Fullscreen return also uses the existing framework transition/input barrier
before a caller can reuse the task with its ordinary launcher Intent.
The source therefore never contains a freeform transfer state, while the first
visible destination state already has the final geometry and native
caption/input surfaces. The simulated driver
deliberately uses this same path to model external-display behavior without
connected hardware.

`PhoneHomeActivity` is primary HOME in Android's default task area.
Freeform applications remain standard root-workspace tasks above that HOME.
Its `singleTop` launch mode lets Android reuse HOME inside the standard HOME
root. Android may also create HOME in an organizer task area. Those instances
delegate navigation to the registered desktop host without creating another
desktop UI or session. Delegate tasks are non-focusable and forced translucent,
with Activity input sinks disabled through the shell task runtime. Independent
delegate HOME roots receive the same policy. A root shared with desktop chrome
remains owned by `ShellDesktopChromeHost`; delegate setup must not change that
root's focus, translucency or order. The host resolves ownership by live typed
task/root/area identity, independently of the chrome task's activity type.
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

## Workspace Activation And Adoption

Returning to an already active desktop is display-scoped and does not restart
the session. `PRESENT_WORKSPACE` orders every managed fullscreen plane below
the HOME host and raises every live managed freeform task above it. On a phone
desktop, the control panel's **Show desktop** uses this operation. Android's
HOME navigation reveals the taskbar over managed or independent applications,
without changing their focus or ownership. The external-session touchpad exposes
the same operation for its target display, so its own phone task and every other display remain
untouched. `PRESENT_DESKTOP` remains the separate command that conceals all
application windows to expose bare wallpaper.

`DesktopLaunchPolicy` resolves managed launch presentation uniformly for apps,
Intents, published shortcuts and built-in hosts (including Linux graphics). The opt-in
phone fullscreen default is only a fallback for new windows on display 0.
Explicit requests, a reused task's mode, and saved per-application mode/bounds
take precedence. It does not change independent launch policy or existing
windows when the setting changes.

Intent and PendingIntent delivery into a prepared existing task carries its
current windowing mode and bounds alongside the task and display IDs. Delivery
preserves task-area ownership and does not reapply display launch defaults.

The control-panel toolbar offers **Start desktop**, or **Show desktop**
for the selected row's existing workspace. Another display can start its own workspace without
closing the current one. Start and Close operations are serialized.
**Close desktop** addresses only that row's workspace. Application placement
and input selection remain independent.

Desktop adopts ordinary freeform tasks on its display, including hidden windows
present at startup. The same shell ownership registry also claims explicitly
launched tasks and processes new freeform residency from the existing framework
observer before publishing immersive, mode and bounds events. Adoption changes
membership only, preserving geometry, focus and task hierarchy. SystemUI, HOME
and shell infrastructure are not application candidates. Fullscreen and PiP
tasks that have not been claimed remain independent. These rules are identical
on phone, simulated, wired and wireless workspaces. Only members participate in
the taskbar, Alt+Tab, mode guard and cleanup; whole-display diagnostics and chrome
policy also see independent tasks.

Explicit release blocks adoption for the selected tasks during the fullscreen
handoff. A monotonically sequenced observation must have begun after completion
before it may establish new freeform residency; an older in-flight read cannot
reclaim released tasks. Failed release restores membership through the same
registry. No additional sampler or settling delay is involved.

Task snapshots and windowing commands issued through `TaskRepository` share a
single `TaskCommandQueue` with phone-task recovery. Recovery checks session
ownership before every mutation. Removed-display recovery is cancelled when
that display acquires a new residency or a phone Desktop starts, without waiting
for its HOME host. Preparing an unrelated external workspace does not cancel it.
Broad ordinary-phone normalization never rewrites a live phone Desktop;
explicit Close still returns the closing workspace's tasks in fullscreen.
Ordinary taskbar operations cannot interleave with recovery commands.
