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

This preserves the process, task, Activity instance, display, pixel bounds,
and density. The WindowManager dump is read only for an explicit fullscreen
transition, has strict time and output bounds, and is not part of a background
poller. If a firmware has no task-local caption source, the normal fullscreen
transition proceeds without the refresh.

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
tasks use independent planes under that workspace. The taskbar is display
chrome rather than an application task: its bounded organizer area shares the
default task container's ordering domain with freeform tasks and fullscreen
planes, and WindowManager keeps it above them through the standard
`alwaysOnTop` container property. Application tasks never enter that area, and
MagicDesk does not retain or assign its organizer surface layer directly.

Taskbar, task overview, MCP, and Alt+Tab use the same focus gateway. The app
process emits a typed `DesktopWorkspaceCommand`: `ACTIVATE`, `DEMOTE`,
`PRESENT_DESKTOP`, `RESTORE_WORKSPACE`, or `RESTORE_SESSION`. Its task IDs are
an explicit back-to-front physical plan, not an overloaded indication of the
requested behavior. `ShellDesktopWorkspaceCoordinator` serializes those
commands for the configured display, completes the live fullscreen and mixed
workspace order from shell-owned topology, and applies the existing WCT path.
The app-side order is built only from the shell-published desktop ownership
snapshot, and the shell rejects a target outside that ownership before any raw
focus fallback. This is significant on display 0, where phone tasks and the
desktop workspace share one physical display.

Once a task has entered fullscreen, activation raises that task and its
existing ordering plane in one atomic WCT and does not change any task's mode,
bounds, parent, or hidden state. Freeform tasks and the HOME host remain in the
ordinary root workspace on every target. The focus WCT does not request BLAST draw
synchronization and is not repeated through `TRANSIT_TO_FRONT`.

The coordinator captures typed task and SurfaceFlinger input-window event
generations before commit, requests one framework task sample, and waits for
both generations to advance. It then reads InputDispatcher once. A missing
input target receives the ownership-appropriate one-shot repair and one more
event-driven commit confirmation. `WindowInfosListener` is the primary commit
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
- Using BLAST draw synchronization or a second `TRANSIT_TO_FRONT` for ordinary
  focus adds an unnecessary app-visible handoff; a stopped target can also
  leave the sync waiting for a surface that is not expected to draw.
- Following an atomic reorder with `moveTaskToFront`, `setFocusedTask`, or
  `setFocusedRootTask` creates a second selection path and still does not
  reliably repair an input window left on a peer task.
- Relaunching an existing task or toggling plane focusability is not a focus
  primitive. Both approaches can leave InputDispatcher on the previous task;
  omitted launch bounds can also replace the user's freeform geometry.

## Window transition ownership

Shell-side WCT submission has one owner. Ordinary focus and reorder changes
use an atomic WCT without `startNewTransition`. A cold freeform launch starts
behind the desktop host so its framework default state is never exposed. Once
the task ID is known, one complete WMShell `OPEN` establishes mode, bounds, and
front order. No raw opening token crosses that launch boundary. This avoids a
race where the framework finishes its launch transition before MagicDesk tries
to append another transaction.

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
`ActivityOptions.setLaunchTaskDisplayArea` together with fullscreen mode. The
first task callback therefore exposes the final parent and mode; there is no
intermediate freeform root and no post-launch reparent. Intent and shortcut
launches share this path.

The desktop session owns viewport orientation. Every fullscreen plane ignores
child orientation requests, allowing Android to rotate or letterbox application
content without rotating the plane, desktop host, or taskbar.

A synchronously hidden prepared task is revealed through a system-played
transition rather than a plain WCT. This is a surface-producing boundary:
WMShell must rebuild the task leash, native caption, and caption input window.
Steady-state activation and geometry changes never use this route.

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
phone task follows the same direct-root restore. An explicit fullscreen close
hands focus to a surviving fullscreen sibling before removing the old task; an
ordinary freeform close follows Android's task lifecycle. Its restore changes
only mode, bounds, and order. These paths preserve the Activity instance and
avoid a display-0 trampoline.

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
