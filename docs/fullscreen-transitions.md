# Fullscreen transitions

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
observer retains its freeform bounds while the task stays directly under the
active desktop parent. That parent is Android's default task area on external
displays and MagicDesk's shell-owned session area on the phone. The
application's own insets request updates its client window; retrying or
rebuilding the Activity can discard transient state such as the browser's HTML
Fullscreen API session. Keeping a lone immersive task out of the managed
multi-fullscreen child also preserves projection displays whose task host is
invalidated by that reparent operation.

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
moves and fullscreen repair through `ShellPreparedTaskTransition`. When a task
belongs to MagicDesk's fullscreen parent, a manual restore or snap reparents it
to the active desktop parent, applies freeform mode and final bounds, and
reveals its caption in one WMShell transition. A fullscreen peer stays under
the existing parent until its own restore or removal; this avoids a redundant
hierarchy change that can invalidate projection displays. A non-focusable
structural HOME task keeps the fullscreen parent alive and non-empty for the
desktop session; while it contains no application task, the whole parent
remains below the real desktop host. The phone fullscreen parent is placed
beside MagicDesk's persistent session area in Android's default task container,
and restored tasks return to the session area through its token. Phone,
simulated, and external displays use the same persistent parent, structural
task, shell observer, and task transitions.

At phone-session teardown, applications leave the fullscreen sibling first.
Framework deletion then removes the sibling together with its structural HOME
child in one locked operation before session tasks are returned to Android's
default task area. The session area retains both the real desktop host and its
own structural HOME while application tasks leave. During area deletion Nubia
reparents the standard host before removing the old area; the structural child
keeps that area non-empty until framework teardown removes it. MagicDesk
finishes the host only after organizer cleanup. Some WMS implementations cannot
calculate root-task priority while an empty child task area remains attached.

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
