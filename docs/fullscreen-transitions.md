# Fullscreen transitions

At the application-process boundary, window policy emits a typed
`DesktopWindowTransitionRequest` through `DesktopWindowTransitionGateway`.
The gateway maps semantic enter, restore, and close operations to the existing
shell task observer. If that backend declines a request, the caller retains its
established `TaskRepository` fallback. This layer adds observability and a
stable extension boundary; it does not add another transition implementation.

RedMagic Console Mode can retain a native desktop caption inset after a task
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

Under `DEFAULT` ownership, each fullscreen task enters its own
organizer-created ordering plane and retains that task/plane relationship for
its complete fullscreen residency. The plane's organizer leash retains a
stable surface-order identity, so selection can change z-order without an
application-visible lifecycle, mode, bounds, or parent change.

Phone desktop uses `DesktopTaskAreaPolicy.SESSION`. This is an ownership
decision rather than a vendor check: its persistent session area isolates the
desktop from Android HOME and the MagicDesk control panel and remains the sole
parent of the host, freeform tasks, managed fullscreen tasks, and
application-requested fullscreen tasks. It never creates independent
fullscreen planes or a second organizer hierarchy.

Taskbar, task overview, MCP, and Alt+Tab use the same focus gateway. Once a task
has entered fullscreen, activation raises that task and its existing ordering
plane in one atomic WCT and does not change any task's mode, bounds, parent, or
hidden state. Phone desktop provides the same activation semantics by
reordering only children of its session parent; it does not create a second
organizer area. The focus WCT does not request BLAST draw synchronization and
is not repeated through `TRANSIT_TO_FRONT`.

## Rejected approaches

- Moving fullscreen peers into one shared organizer area while switching can
  avoid a firmware mode-loss symptom, but the preparation reparents live tasks
  and can make browser-style applications leave immersive fullscreen.
- Keeping one task in the display parent and another in an organizer area can
  make a particular two-task order fast, but the accidental asymmetry does not
  provide stable ordering identities for an arbitrary number of tasks.
- Creating independent fullscreen planes inside a `SESSION` desktop adds a
  second hierarchy and breaks the session area's responsibility for isolating
  phone desktop tasks from HOME and the control panel.
- Using BLAST draw synchronization or a second `TRANSIT_TO_FRONT` for ordinary
  focus adds an unnecessary app-visible handoff; a stopped target can also
  leave the sync waiting for a surface that is not expected to draw.

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

An orientation change can make Android report the saved freeform mode and
bounds before WMShell has recreated the task decoration. Orientation task
callbacks wake the shell observer immediately and route the task through the
same ownership-specific restore operation.

A task in a `DEFAULT` fullscreen plane exits through ActivityTaskManager's
existing-task launch path with its final freeform mode, display, and bounds.
Each plane has one retained standard anchor task that keeps the source
hierarchy valid while the framework selects the ordinary destination area.
After the application leaves, the plane becomes a non-focusable idle slot and
is reused by a later fullscreen task. The anchor has a valid input channel for
the brief task-removal boundary, accepts no pointer input, and cannot own focus
while its plane is idle. A newly created anchor launches behind the current
foreground task, so its structural `OPEN` cannot race the application's
fullscreen entry or steal focus. Session teardown removes all owned planes and
anchors; if display removal has already migrated an anchor to display 0,
ownership is verified by both saved task ID and component before that task is
removed. A phone task already belongs to the persistent session parent, so its
restore changes only mode, bounds, and order. Both paths preserve the Activity
instance and avoid a display-0 trampoline.

`ShellPreparedTaskTransition` separately owns hidden preparation and reveal
for running-task display moves and freeform decoration repair outside the
per-plane exit path. At phone-session teardown, `ShellDesktopTaskArea` returns
its application tasks and host while the session's structural HOME child keeps
that area non-empty until framework deletion.

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
