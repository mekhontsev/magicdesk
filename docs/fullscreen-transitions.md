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

On wired, wireless, and simulated desktops, an application initially enters
immersive fullscreen in the ordinary display parent. A singleton remains
there. When focus must switch at least two fullscreen tasks, the shell
atomically places the complete stack under one temporary fullscreen parent.
This is the hierarchy invariant established by MagicDesk 1.8: reordering the
same tasks in Nubia's freeform-oriented area can demote a background task to
freeform. Phone desktop instead keeps the host and every application in its
single persistent session parent.

Taskbar, task overview, MCP, and Alt+Tab use the same focus gateway. The full
stack is supplied only while preparing missing children; subsequent operations
activate the selected child alone and do not change any task's mode, bounds, or
parent. Phone desktop provides the same activation semantics by reordering only
children of its session parent; it does not create a second organizer area.
The immutable external behavior and the phone ownership decision are recorded
in
[MagicDesk 1.8 fullscreen reference](fullscreen-1.8-reference.md).

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

An orientation change can make Android report the saved freeform mode and bounds
before WMShell has recreated the task decoration. Orientation task callbacks
wake the shell observer immediately; when system bars become visible again,
the same observer hides the task, establishes a real fullscreen-to-freeform
mode boundary, and reveals the same Activity at its saved bounds through the
normal WMShell transition. Detachment is required only for tasks owned by
MagicDesk's multi-window fullscreen parent. The application process is not in
the critical path. This restores the desktop surface and native caption without
using the phone display, restarting the application, or exposing the firmware's
partial freeform state.

The same shell-owned visibility boundary is shared by running-task display
moves and fullscreen repair through `ShellPreparedTaskTransition`. A task in
the ordinary display parent applies freeform mode and final bounds in place. A
task in a shared `DEFAULT` fullscreen parent is detached while applying its
final freeform geometry. A phone task already belongs to the persistent session
parent, so restore changes only its mode, bounds, and order. At phone-session
teardown, `ShellDesktopTaskArea` returns its application tasks and host while
the session's structural HOME child keeps that area non-empty until framework
deletion.

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
