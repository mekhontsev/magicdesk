# Fullscreen transitions

REDMAGIC Console Mode can retain a native desktop caption inset after a task
changes from freeform to fullscreen. The task and application window already
have full-display bounds, but application content can still begin below a stale
`captionBar` inset and leave a black strip at the top.

`TaskFullscreenTransitionCommand` performs the transition on the task's current
display with two synchronous `WindowContainerTransaction` operations:

1. Set fullscreen mode, clear task bounds, exclude
   `WindowInsets.Type.captionBar()`, and apply inherited density plus one DPI
   in one transaction.
2. Bring the task to the front, then restore density inheritance with density
   `0` in the second transaction.

The density change recreates the client window so it consumes the updated
insets. It preserves the process, task, activity record, display, pixel bounds,
and final display density. Combining final geometry and temporary density in
the first transaction prevents a stale intermediate fullscreen frame. The
transition does not use an arbitrary timer.

Setting the task to its current effective DPI does not work. Android resolves
requested overrides before dispatching configuration changes, sees no effective
`CONFIG_DENSITY` difference, and does not relaunch the Activity. Bounds,
focusability, drag-resize, translucency, and other task or surface-only changes
also leave the existing client `ViewRoot` intact. A one-DPI difference reaches
the Activity configuration path and requests the required relaunch; the second
transaction then removes the temporary override.

When an application initiates immersive mode itself, MagicDesk uses
`TaskClientPreservingFullscreenTransitionCommand` to set fullscreen geometry
and `TaskCaptionInsetsCommand` to exclude the caption without changing density.
The application's own insets request updates its client window, while a
density refresh would relaunch the Activity and can discard transient state
such as the browser's HTML Fullscreen API session. When the application makes
system bars visible again, MagicDesk returns the task to its saved freeform
bounds and includes the caption inset.

The reverse transition includes the caption inset after returning the task to
freeform. Native WMShell desktop tasks also have the inset explicitly included
when they are created or restored.

## Constraints

- Resolve the current task and logical display IDs; display IDs change after
  reconnecting external hardware.
- Keep the task on the same display. Do not use the phone display as a
  transition trampoline.
- Do not stop the target application to refresh its window.
- `RootKeyboardShortcutWatcher` observes Linux input events but does not consume
  Android's copy of the same event. Keyboard ownership is separate from the
  fullscreen transition.
