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

The `DEFAULT` topology generalizes MagicDesk 1.8's characterized two-plane
state to any number of fullscreen tasks. Each task enters its own
organizer-created ordering plane and retains that task/plane relationship for
its complete fullscreen residency. The plane's organizer leash also retains a
stable surface-order identity. Phone desktop instead keeps the host and every
application in its single persistent session parent.

Taskbar, task overview, MCP, and Alt+Tab use the same focus gateway. Once a task
has entered fullscreen, activation raises that task and its existing ordering
plane in one atomic WCT and does not change any task's mode, bounds, parent, or
hidden state. Phone desktop provides the same activation semantics by
reordering only children of its session parent; it does not create a second
organizer area. The focus WCT does not request BLAST draw synchronization and
is not repeated through `TRANSIT_TO_FRONT`.
The immutable external behavior and the phone ownership decision are recorded
in
[MagicDesk 1.8 fullscreen reference](fullscreen-1.8-reference.md).

## Window transition ownership target

Shell-side WCT submission and raw opening transition tokens have one owner.
Ordinary focus, reorder, windowing, bounds, and parent changes use an atomic WCT
without `startNewTransition`. A launch that must append its early freeform WCT
to the system opening transition retains the token until that continuation is
accepted. WMShell owns visual playback and completion; MagicDesk must not call
`finishTransition` merely because task mode already changed.

A synchronously hidden prepared task is revealed through a system-played
transition rather than a plain WCT. This is a surface-producing boundary:
WMShell must rebuild the task leash, native caption, and caption input window.
Steady-state activation and geometry changes never use this route.

Owned desktop display teardown passes one bounded quiescence gate. It waits for
both MagicDesk's pending opening continuations and WindowManager transition
performance sessions on that display, and requires a stable idle interval
before removing the display. Compatibility reports flag sessions that refer to
missing display IDs. The self-test checks this before mutation and after
cleanup. An already orphaned system session cannot be repaired safely by
another task transaction; restart `system_server` or reboot. Restarting
SystemUI may help on some builds but is not reliable after display removal.

## Activate and demote

Task selection is modeled as z-order, not as a window-state transition:

- `activate(target)` places the selected task at the front of its compatible
  hierarchy. A background task selected from the taskbar, task overview,
  Alt+Tab, or MCP follows this operation through `DesktopTaskController`.
- `demote(active)` rotates the foreground task behind the next MRU application.
  Selecting the already-active taskbar item uses this operation. If there is no
  peer, the desktop host comes to the front while the application remains live
  below it.

Both operations preserve the task's windowing mode, bounds, parent, and hidden
state. In particular, an occluded task is not minimized: a covered fullscreen
task becomes visible again as soon as the task above it leaves, and it remains
visible around a freeform task placed above it. A mixed stack is therefore
ordered as desktop host, fullscreen plane, and freeform windows. The concrete
parent hierarchy follows workspace ownership, but callers use the same
semantics and focus gateway.

`demote` is deliberately distinct from `show desktop`. The latter presents a
saved workspace as a user command; it does not define the behavior of clicking
an active application icon.

An orientation change can make Android report the saved freeform mode and
bounds before WMShell has recreated the task decoration. Orientation task
callbacks wake the shell observer immediately and route the task through the
same ownership-specific restore operation.

A task in a `DEFAULT` fullscreen plane exits through ActivityTaskManager's
existing-task launch path with its final freeform mode, display, and bounds. A
temporary HOME child keeps only that source plane structurally valid while the
framework selects the ordinary destination area; after the application task
has left, deleting the plane removes the temporary child with it. The backstop
does not exist during normal fullscreen focus and never becomes a desktop
layer. A phone task already belongs to the persistent session parent, so its
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
