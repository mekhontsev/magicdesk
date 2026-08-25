# MagicDesk 1.8 fullscreen reference

This document pins the fullscreen behavior that later focus changes must
preserve. The immutable reference is release tag `v1.8.0`, commit
`6415d128f0b27ca0774127fd890654d7f98c5895`.

The primary source is
`app/src/main/java/io/github/mekhontsev/magicdesk/ShellFullscreenTaskArea.java`
at that commit. Inspect it directly with:

```sh
git show v1.8.0:app/src/main/java/io/github/mekhontsev/magicdesk/ShellFullscreenTaskArea.java
```

## Established behavior

1. One fullscreen application task remains in the display's ordinary task
   area. `isFullscreenStack()` returns false for fewer than two tasks, and
   `beginAppFullscreen()` changes mode and bounds without reparenting.
2. A dedicated fullscreen task area is created only when a focus operation
   must switch a stack of at least two fullscreen application tasks.
3. Every member of that stack is set to fullscreen with empty bounds and is
   reparented into the same fullscreen task area before focus changes.
4. Reordering tasks inside that shared parent avoids Nubia resolving a
   background fullscreen task as freeform.
5. If the stack later has one survivor, that survivor remains in the shared
   parent until restore or removal. The now-empty area may remain available for
   observer reuse or cleanup. It exists because a multi-task stack existed
   earlier; a fresh singleton still starts outside it.

## Current deliberate extension

MagicDesk now sends taskbar, task overview, MCP, and Alt+Tab through the same
`DesktopTaskController` focus gateway. The gateway supplies the complete
fullscreen set only as hierarchy-preparation context. Once all tasks share the
correct parent, each activation focuses only the selected task. A steady-state
switch must not change any peer's windowing mode, bounds, or parent and must not
dispatch an application-visible multi-window transition.

## Phone ownership decision

The phone desktop uses `DesktopTaskAreaPolicy.SESSION`. Its persistent session
area is required to isolate the desktop from Android HOME and the MagicDesk
control panel. It is also the sole parent for the desktop host, freeform tasks,
managed fullscreen tasks, and application-requested fullscreen tasks.

Do not create another fullscreen sibling on the phone. The dedicated shared
fullscreen area solves a Nubia mode-loss defect observed on wired, wireless,
and simulated displays; that defect has not been observed on display 0. Phone
focus and Back handling must use the existing session area instead of adding a
second organizer hierarchy.

This is an ownership decision, not a vendor check: `SESSION` means one session
area, while `DEFAULT` may create the 1.8 shared fullscreen area. Do not infer
phone behavior from the external-display reference, and do not weaken the
external shared parent to simplify the phone path.

## Characterization coverage

The desktop self-test enforces the contract around `WINDOW-015` and
`WINDOW-020`:

- preparing two or more fullscreen peers creates one shared parent;
- external and simulated stacks put every fullscreen fixture in that parent;
- phone sessions keep every fixture in their one session parent;
- target switches retain task modes, parents, real input focus, and the
  browser-style immersive surface.

`SelfTestTaskStackInvariantAnalyzerTest` covers these decisions without an
Android device. The simulated and phone self-tests cover the real WMShell and
firmware transitions.
