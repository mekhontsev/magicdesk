# MagicDesk 1.8 fullscreen reference

This document records both the implementation and the characterized behavior
that later focus changes must preserve. The immutable source reference is
release tag `v1.8.0`, commit
`6415d128f0b27ca0774127fd890654d7f98c5895`.

Inspect the original implementation directly with:

```sh
git show v1.8.0:app/src/main/java/io/github/mekhontsev/magicdesk/ShellFullscreenTaskArea.java
```

## Literal 1.8 implementation

1. One fullscreen task remains in the display's ordinary task area.
2. A dedicated fullscreen task area is created when at least two fullscreen
   tasks must be switched.
3. The implementation can set those peers to fullscreen, clear their bounds,
   and place them under that shared parent before changing focus.
4. Reordering children of the organizer area avoids Nubia resolving a
   background fullscreen task as freeform.
5. A survivor can remain in that area until restore or removal.

These facts explain the original protection against Nubia's mode-loss bug, but
the literal shared-parent sequence is not by itself the UX contract.

## Characterized 1.8 behavior

The fast taskbar path was reproduced on real Nubia firmware. The first Alt+Tab
could place two tasks in the shared organizer area and make Firefox leave its
HTML Fullscreen session. After Firefox was made fullscreen again, it remained
in the ordinary display parent while its peer remained in the organizer area.
Taskbar switching then became immediate: WindowManager reported task/parent
z-order movement without pause, stop, mode, bounds, or parent changes visible
to either application.

That stable two-plane state is the behavioral reference. Reconstructing only
the literal shared-parent preparation can reproduce the Firefox regression
that the working 1.8 state avoided.

## Target generalization

Under `DEFAULT` ownership, generalize the characterized state to any number of
fullscreen tasks. Each fullscreen task needs a stable ordering plane from
fullscreen entry until freeform restore or close. The implementation may let
one task use the ordinary display parent, provided its ordering identity stays
stable and activation can order it atomically with dedicated planes.

Taskbar, task overview, MCP, and Alt+Tab enter the same
`DesktopTaskController` focus gateway. Activation raises only the selected task
and its existing plane in one atomic WCT. It must not change a peer's mode,
bounds, parent, or hidden state and must not start a raw WMShell transition.

The focus WCT must not use BLAST draw synchronization. A stopped target may
have `NO_SURFACE` until activation, causing a sync timeout. It must also not be
followed by a duplicate `TRANSIT_TO_FRONT`: a second application-visible
handoff is unnecessary once hierarchy and input target are committed atomically.

## Phone ownership

The phone desktop uses `DesktopTaskAreaPolicy.SESSION`. Its persistent session
area is required to isolate the desktop from Android HOME and the MagicDesk
control panel. It is also the sole parent for the desktop host, freeform tasks,
managed fullscreen tasks, and application-requested fullscreen tasks.

Do not create independent fullscreen planes on the phone. This is an ownership
decision, not a vendor check: `SESSION` means one session area, while
`DEFAULT` uses stable fullscreen ordering planes. Phone focus and Back handling
must use the existing session area instead of adding a second organizer
hierarchy.

## Characterization coverage

The desktop self-test checks the hierarchy around `WINDOW-015` and
`WINDOW-020`, and checks plane release through `FULLSCREEN-PLANE-EXIT-001`
through `004`:

- external and simulated fullscreen peers retain stable ordering parents;
- phone fullscreen peers remain in their one session parent;
- target switches retain mode, parent, input focus, and browser-style
  immersive state;
- a `DEFAULT` task returns from its plane to its original freeform parent and
  can repeat the complete enter/restore cycle;
- no stage exposes a desktop visibility gap.

`SelfTestTaskStackInvariantAnalyzerTest` checks the structural rules without an
Android device. Simulated, phone, and wired self-tests exercise the real
WindowManager and firmware paths. The implementation must keep the existing
simulated and phone suites at zero failures while this target topology is
introduced.
