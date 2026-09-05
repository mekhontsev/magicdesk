# Fullscreen transitions

At the application-process boundary, window policy emits a typed
`DesktopWindowTransitionRequest` through `DesktopWindowTransitionGateway`.
The gateway maps semantic enter and restore operations to the existing
shell task observer. If that backend declines a request, the caller retains its
established `TaskRepository` fallback. This layer adds observability and a
stable extension boundary; it does not add another transition implementation.

RedMagic external desktop windowing can retain a native caption inset after a task
changes from freeform to fullscreen. The task and application window already
have full-display bounds, but application content can still begin below a stale
`captionBar` inset and leave a black strip at the top.

`TaskFullscreenTransitionCommand` performs the transition on the task's current
display without recreating its Activity:

1. While the task is still freeform, read its task-local `captionBar` source ID
   from a bounded `dumpsys window` snapshot.
2. Set fullscreen mode, clear task bounds, exclude
   `WindowInsets.Type.captionBar()`, and bring the task to the front in one
   transition.
3. After WindowManager reports fullscreen mode, replace the captured source
   with an empty source carrying the same ID, then remove it. Both operations
   use synchronous WindowOrganizer callbacks.

The exact ID matters because the stale source is retained in the application's
existing `ViewRoot`, after WMShell has already removed its server-side source.
The empty replacement updates that client entry to `[0,0][0,0]`. Synchronizing
the replacement and removal separately prevents Nubia from coalescing both
operations before the client observes the empty state.

This preserves the process, task, Activity instance, display, and pixel
bounds. Caption repair does not modify density; the enclosing semantic
transition may carry an explicit application presentation density as described
below. The WindowManager dump is read only for an explicit fullscreen
transition, has strict time and output bounds, and is not part of a background
poller. If a firmware has no task-local caption source, the normal fullscreen
transition proceeds without the refresh.

Cross-display fullscreen return uses the same native `CHANGE` transaction as
freeform transfer. After hidden source preparation, one WCT starts the exact
existing task on the destination and applies mode, empty fullscreen bounds,
density, caption policy, and reveal. A raw root-task display move followed by a
sync reveal can leave the task leash at its old external freeform crop/position
even when both TaskInfo and application frames already report phone fullscreen
bounds. The native transition owns that surface lifecycle. Fullscreen return
uses `FrameworkWindowCommitBarrier` before a following phone launcher Intent;
it does not submit an additional focus transaction or manually reposition a
surface. This path is shared by phone Start, task return, and session parking.

When an application initiates immersive mode itself, the long-lived shell task
observer retains its freeform bounds and does not recreate the Activity. The
application's own insets request updates its client window; retrying or
rebuilding the Activity can discard transient state such as the browser's HTML
Fullscreen API session.

Each fullscreen task enters its own
organizer-created ordering plane and retains that task/plane relationship for
its complete fullscreen residency. The plane's organizer leash retains a
stable surface-order identity, so selection can change z-order without an
application-visible lifecycle, mode, bounds, or parent change.

The same topology is used on phone, simulated, wired, and wireless targets.
`PhoneDesktopHomeActivity` remains primary HOME in Android's default task area;
ordinary freeform tasks share the standard root workspace, while fullscreen
tasks use independent planes under that workspace. Display chrome uses one
transparent, non-focusable `MULTI_WINDOW` task in a root-level organizer area,
a sibling of the standard task workspace. Both the area and its task use
`MULTI_WINDOW` and `alwaysOnTop`: Android 15+ ignores the flag in fullscreen
mode. Empty task bounds fill the area without making the task floating.
Native DisplayArea ordering preserves chrome priority across application
launches and panel relayout, below system windows and IME. Only bounded child
application windows draw or receive input.

Chrome must not be nested among application root tasks. Android 15+
`ActivityStarter` calls `TaskDisplayArea.getRootTaskAbove`, which casts the
next sibling to `Task`. A chrome area there can abort a child Activity/result
launch after the framework has already changed the caller's configuration.
The root-level chrome area avoids that sibling list. Its effective native
priority requires the area's own `MULTI_WINDOW` mode; setting only its flag
while it inherits fullscreen is insufficient.

`ShellDesktopSurfaceOrder` owns only fullscreen-plane surfaces inside the
standard workspace. Chrome retains no organizer leash and requires no manual
layer assignment. Area-capable WCT operations configure its mode, priority,
and orientation policy once; task-only operations still target the host task.
Launches, mode transitions, and workspace commands do not append a chrome
commit. A failed plane
surface commit is not reported as a successful window operation. This adds no
task polling or independent input repair.
Before the first window or organizer-surface submission in a shell process,
`ShellWindowTransitionExecutor` connects to WindowManager's SurfaceFlinger
transaction queue through `WindowOrganizer.shareTransactionQueue`, matching
Android 15+ WMShell initialization. Framework-returned sync transactions and
explicit plane commits therefore share WM's apply token instead of an
independent process queue. Initialization is retained for the process lifetime;
failure rejects the operation rather than silently continuing with independent
ordering. Diagnostics reports `surfaceTransactionQueue=shared_with_wm` after
successful initialization. This does not replace hierarchy or input-focus
confirmation and does not add a worker, timer, or transaction retry.
On the phone display the taskbar child window also covers the stable lower
system-bar inset. It paints that portion with the taskbar background, while the
taskbar controls remain above the inset. When managed fullscreen policy conceals
the taskbar, the child window collapses to its reveal edge. An unrelated
foreground task removes it entirely. The transparent chrome host remains
structurally stable without covering fullscreen content.

Taskbar, task overview, MCP, and Alt+Tab use the same focus gateway.
Direct taskbar selection completes through the same controller action as
Alt release. Surface synchronization belongs to the shell transaction owner,
not to auxiliary UI windows or application-frame callbacks.
Releasing Alt commits the selected target and dismisses the picker before
submitting activation. The picker does not wait for the workspace acknowledgement;
a slow transition must neither keep the old picker visible nor dismiss a new
one opened during that transition. Releasing Alt while its snapshot is still
loading commits after the load without showing the picker. Activation retains
the same serialized command and input-focus confirmation as other entry points.
The app process emits a typed `DesktopWorkspaceCommand`: `ACTIVATE`, `DEMOTE`,
`PRESENT_DESKTOP`, `RESTORE_WORKSPACE`, or `RESTORE_SESSION`. `ACTIVATE` accepts
exactly one task; other operations carry an explicit back-to-front plan.
`ShellDesktopWorkspaceCoordinator` serializes those
commands for the configured display, completes the live fullscreen and mixed
workspace order from shell-owned topology, and applies the existing WCT path.
The app-side `DesktopWorkspaceQueue` serializes the entire user operation:
read the live snapshot, resolve activate/demote or Show/Restore, prepare host
input, submit, and acknowledge the committed result. It runs on the existing
controller Handler, with no worker or timer. Taskbar, Alt+Tab, overview, MCP,
and desktop presentation share it; Win+D has no separate queue. Pending focus
does not become observed focus when an operation is enqueued. Session stop
cancels pending callbacks and ignores acknowledgements from the old session.
The app-side order is built only from the shell-published desktop ownership
snapshot, and the shell rejects a target outside that ownership before any raw
focus fallback. This is significant on display 0, where phone tasks and the
desktop workspace share one physical display.

Once a task has entered fullscreen, activation raises that task and its
existing ordering plane in one atomic WCT and does not change any task's mode,
bounds, parent, or hidden state. Freeform tasks and the HOME host remain in the
ordinary root workspace on every target. Selecting an ordinary freeform task,
including one above a retained fullscreen plane, submits its complete ordering
WCT once as a system-played `TO_FRONT`. WMCore's transition assigns root surface
layers at the start and finish boundaries. A plain WCT synchronization callback
does not require that assignment: a wired reproduction confirmed Files first in
the task hierarchy and focused while its surface still remained below Golly.
The same policy applies to covered freeform tasks, whose surfaces must be
brought forward even when the previous snapshot says invisible.
Fullscreen-plane selection remains an atomic WCT with explicit plane surface
composition; newly established planes retain their launch boundary. Selection
policy lives in `ShellWindowTransitionExecutor` for ordinary and mixed
workspaces. The system transition replaces the freeform sync submission; it is
not a second focus/raise after applying the same WCT.

The native phase ends at `FrameworkWindowCommitBarrier`, using Android 15+'s
`IWindowManager.syncInputTransactions(true)` before returning to the topology
owner. WM waits for pending transitions/animations and input-window publication
using its own bounded event waits. The existing plane/chrome surface commit
then publishes the final workspace order, followed by input-focus confirmation.
This order matters because native finish-layer assignment places HOME below
normal roots, even when our hierarchy explicitly demotes a fullscreen plane
below HOME. Publishing that plane's negative layer before native finish lets
WM overwrite it and leaves the demoted application visible behind freeforms.

The plane owner retains the last committed placement relative to HOME together
with its plane order. A parked empty plane cannot infer the remaining surfaces'
placement from input focus: a freeform foreground can still have a fullscreen
background. Native selection also puts covered planes below HOME in the root
hierarchy, without replacing their explicitly composed surface order. Mixed
selection therefore retains that composed background when the hierarchy has
no foreground fullscreen task. Explicit desktop presentation commits planes
below HOME and clears that background. This replaces per-plane focus queries
at release; it introduces no additional observation or selection transaction.

The barrier is global, not display- or token-specific, and Android can return
at its internal deadline without a timeout result. It is not exposed as proof
that a particular transition succeeded: the existing surface acknowledgement
and input-focus checks remain required. Binder/API failure rejects the command
without another WCT fallback. Diagnostics records its mechanism, calls,
failures, and last duration separately as `windowCommitBarrier`; its reason is
`WINDOW_TRANSITION_COMMIT`. There is no new worker, poller, repeated plane
raise, or fixed post-transition sleep. No barrier runs during idle observation.

The coordinator captures typed task and SurfaceFlinger input-window event
generations before commit, requests one framework task sample, and waits for
both generations to advance. It then reads InputDispatcher once. A missing
input target receives the ownership-appropriate one-shot repair and one more
event-driven commit confirmation only while that display still owns input.
If the user has moved to another display, or the active input display is
unknown, reconciliation must not turn the missing desktop window into a
focus-stealing reorder or host relayout. `WindowInfosListener` is the primary commit
signal; the 150 ms framework task snapshot remains the separately documented
fallback for task facts that Android does not publish through callbacks. There
is no periodic input poll, and command success means both hierarchy order and
usable input focus have converged.

## Rejected approaches

- Moving fullscreen peers into one shared organizer area while switching can
  avoid a firmware mode-loss symptom, but the preparation reparents live tasks
  and can make browser-style applications leave immersive fullscreen.
- Keeping one task in the display parent and another in an organizer area can
  make a particular two-task order fast, but the accidental asymmetry does not
  provide stable ordering identities for an arbitrary number of tasks.
- Using BLAST draw synchronization for fullscreen-plane selection or stopped
  targets adds an unnecessary app-visible handoff and can leave the sync
  waiting for a surface that is not expected to draw. For ordinary freeform
  selection, merely applying the returned sync transaction also does not force
  root layer assignment. Use the native transition's surface lifecycle instead.
- Following an atomic reorder with `moveTaskToFront`, `setFocusedTask`, or
  `setFocusedRootTask` creates a second selection path and still does not
  reliably repair an input window left on a peer task.
- Relaunching an existing task or toggling plane focusability is not a focus
  primitive. Both approaches can leave InputDispatcher on the previous task;
  omitted launch bounds can also replace the user's freeform geometry.

## Window transition ownership

Shell-side WCT submission has one owner. Ordinary freeform focus submits one
system `TO_FRONT` through `startForShellAdoption`; fullscreen-plane selection
uses an atomic WCT. Neither path appends another task-focus submission. A cold
freeform launch starts behind the desktop host so its framework default state
is never exposed. Once the task ID is known, one complete WMShell `OPEN`
establishes mode, bounds, and front order. No raw opening token crosses that
launch boundary. This avoids a
race where the framework finishes its launch transition before MagicDesk tries
to append another transaction.

Application presentation uses the same WCT owner. Every surface-producing or
geometry transition carries one of three typed density states: unchanged,
inherit from the display, or an exact density resolved from the application's
saved scale and the target display density. A custom density is applied to the
task and, for fullscreen, its retained ordering plane in the same transition
that establishes mode, bounds, and parent. Focus and reorder commands use
unchanged. Moving a task away from the desktop and closing the session reset
owned overrides to inherit, so presentation state cannot leak into ordinary
phone use.

A live task entering an independent fullscreen plane is a surface-producing
boundary: MagicDesk first prepares the plane order, then uses
ActivityTaskManager's `moveTaskToFront` with fullscreen launch options and the
target task display area. Android creates the recognized transition and
WMShell receives the task leash; the Activity instance is preserved. This is
required on Nubia firmware, where a direct WCT updates an organized task's
logical fullscreen bounds but deliberately leaves its old freeform surface
crop in place.

A cold fullscreen launch has no existing surface to migrate. MagicDesk reserves
an anchored plane before starting the Activity and passes that plane through
`ActivityOptions.setLaunchTaskDisplayArea` together with fullscreen mode and
`ACTIVITY_TYPE_STANDARD`. The first task callback therefore exposes the final
parent, type, and mode; there is no intermediate freeform root and no
post-launch reparent. Intent and shortcut launches share this path. If the
observed topology violates that launch contract, rollback can remove only a
task confirmed by the task-created callback and absent from the global
pre-launch task snapshot; an existing or moved task is preserved.

The desktop session owns viewport orientation. Every fullscreen plane ignores
child orientation requests, allowing Android to rotate or letterbox application
content without rotating the plane, desktop host, or taskbar.

A synchronously hidden prepared task is revealed through a system-played
transition rather than a plain WCT. This is a surface-producing boundary:
WMShell must rebuild the task leash, native caption, and caption input window.
Freeform selection uses the same submission boundary with an ordering-only WCT;
it does not hide or reveal tasks through `setHidden`.

MagicDesk currently starts this narrow class of transitions directly in
WMCore through `WindowOrganizer.startNewTransition`. Because the call does not
pass through the in-process WMShell `Transitions.startTransition` wrapper, its
token is not present in SystemUI's local pending-transition registry. Current
WMShell versions adopt the token when `onTransitionReady` arrives, then play
and finish it through their normal handlers. The centralized
`startForShellAdoption` boundary records this ownership contract in code and
returns an opaque token for future experiments; production callers must not
finish that token themselves. Replacing this path requires preserving the
existing WCT, transition type, ordering, and surface-producing behavior.

Owned desktop display teardown passes one bounded quiescence gate. It waits for
WindowManager transition performance sessions on that display and requires a
stable idle interval before removing the display. Compatibility reports flag
sessions that refer to missing display IDs. The self-test records pre-existing
stale sessions as a warning and uses their counts as a baseline; cleanup fails
if the test creates any additional stale session, including a duplicate with
the same display and flags. An already orphaned system session cannot be
repaired safely by another task transaction; restart `system_server` or reboot.
Restarting SystemUI may help on some builds but is not reliable after display
removal.

## Activate and demote

Task selection is modeled as z-order, not as a window-state transition:

- `activate(target)` places the selected task at the front of its compatible
  hierarchy. A target is already foreground only when it is visible, focused,
  and no managed application is ordered above it. A background or covered task
  selected from the taskbar, task overview, Alt+Tab, or MCP follows this
  operation through `DesktopTaskController`.
- `demote(active)` rotates the foreground task behind the next MRU application.
  Selecting the already-active taskbar item uses this operation. If there is no
  peer, the desktop host comes to the front while the application remains live
  below it.

Both operations preserve the task's windowing mode, bounds, parent, and hidden
state. Occlusion is not minimization: a covered task remains live in the same
window state. Activating a covered fullscreen task orders its current blockers
below it, preserving their mutual order; demoting that fullscreen task reveals
the previous stack without a restore transition. Activating a freeform task
places it above the current fullscreen plane and other freeform peers. The
concrete parent hierarchy follows workspace ownership, but callers use the
same semantics and focus gateway.

Single-task selection never restores a captured panel or application stack.
Ownership adapters preserve the requested plan; they do not insert HOME as an
implicit separator. HOME appears in the plan only for an explicit workspace
operation, otherwise the live hierarchy determines the current background.
When demotion selects a fullscreen successor, freeform tasks before that HOME
separator are explicit blockers to lower in the same WCT, not entries to
discard. Raising only the fullscreen plane leaves their root/input surfaces
behind and can expose them again on the next single-task activation.
The shell retains only freeform tasks above the first opaque application or
HOME in the typed root hierarchy, even when covered tasks still report
`visible=true`. Selecting one covered freeform task raises that task, not its
covered peers. An explicit workspace restore can request multiple tasks and
their fullscreen background. Selecting a task from HOME leaves previously
covered fullscreen planes below HOME. These decisions use the command's
one-shot framework snapshot, not a new background observer.

`demote` is deliberately distinct from `show desktop`. The latter presents a
saved workspace as a user command; it does not define the behavior of clicking
an active application icon.

`PRESENT_DESKTOP` captures the visible workspace once, orders that complete
stack below the HOME host in one semantic command, and conceals independent
fullscreen plane surfaces in the same operation. The direct surface
concealment remains necessary because affected firmware can reassert a
fullscreen plane after its hierarchy was demoted. `RESTORE_WORKSPACE` reveals
the captured stack and those retained planes through the normal topology
owner. Session startup is two-phase: freeform geometry and parked-task
residency are prepared first, then `RESTORE_SESSION` publishes one final
workspace order instead of focusing the host through a separate raw shell
route.

`PRESENT_WORKSPACE` is the non-toggle return-to-desktop operation. It keeps all
live managed freeform tasks above HOME while demoting every managed fullscreen
plane below HOME. The operation is scoped to the active desktop display and
does not change task mode, bounds, parent, or tasks on any other display.

An orientation change can make Android report the saved freeform mode and
bounds before WMShell has recreated the task decoration. Orientation task
callbacks wake the shell observer immediately and route the task through the
same ownership-specific restore operation.

A task in a fullscreen plane exits through ActivityTaskManager's
existing-task launch path with its final freeform mode, display, and bounds.
Each plane has one retained standard anchor task that keeps the source
hierarchy non-empty until the reparent transition commits and lets the idle
plane be reused without organizer deletion and recreation.
After the application leaves, the plane becomes a non-focusable idle slot and
is reused by a later fullscreen task. The anchor has a valid input channel for
the brief task-removal boundary and accepts no pointer input. An explicit close
makes the source plane non-focusable, selects the successor, and confirms input
focus before removing the now-background application task. Application-initiated
removal submits the same handoff from `onTaskRemovalStarted` without waiting
inside the framework callback; if the framework nevertheless reports anchor
focus, that focus callback immediately restores the invariant. Neither path
adds background polling. Explicit close retains the existing bounded
input-focus verification around its WCT handoff; application removal remains
callback-only. A newly created anchor launches behind the current foreground
task, so its structural `OPEN` cannot race the application's fullscreen entry
or steal focus. Session teardown removes all owned planes and anchors; if
display removal has already migrated an anchor to display 0, ownership is
verified by both saved task ID and component before that task is removed. A
phone task follows the same standard-workspace restore. An explicit fullscreen
close hands focus to a surviving fullscreen sibling before removing the old
task; an ordinary freeform close follows Android's task lifecycle. Its restore
changes only mode, bounds, and order. These paths preserve the Activity
instance and avoid a display-0 trampoline.

Task-removal focus reconciliation is armed by `onTaskRemovalStarted`, not
only by final `onTaskRemoved`: Android can need a resumed successor before
its native CLOSE transition becomes ready. The existing typed task observer
waits for the closing task to leave its snapshot, then considers the first
visible non-infrastructure surface. HOME is a valid successor and an opaque
boundary; a stale focused fullscreen task behind it must not override that
choice. An unrelated foreground phone task ends the search without desktop
focus repair. This reuses the existing input-focus reconciler and adds no
timer, polling source, HOME launch, or application selection command.

`ShellPreparedTaskTransition` separately owns hidden preparation and reveal
for running-task display moves and freeform decoration repair outside the
per-plane exit path. Phone-session teardown restores the previous HOME role and
normalizes desktop-owned display-0 tasks without deleting an application
organizer area. The primary HOME host is never part of plane cleanup.

The reverse transition includes the caption inset after returning the task to
freeform. Native WMShell desktop tasks also have the inset explicitly included
when they are created or restored.

## Constraints

- Resolve the current task and logical display IDs; display IDs change after
  reconnecting external hardware.
- Keep the task on the same display. Do not use the phone display as a
  transition trampoline.
- Do not stop the target application to refresh its window.
- Do not replace the synchronous source updates with asynchronous add/remove
  transactions; Nubia can merge them and retain the old client frame.
