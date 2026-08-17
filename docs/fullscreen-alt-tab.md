# Fullscreen Alt+Tab

This document records an architecture decision, its rejected alternatives,
and the regression contract. Do not replace the dedicated fullscreen task
display area with an ordinary focus/reorder operation or a delayed fullscreen
repair without first updating the tests and the evidence recorded here.

## Decision

True-fullscreen tasks switched through MagicDesk Alt+Tab live in an
organizer-owned fullscreen `TaskDisplayArea`. The organizer is owned by the
long-lived shell task-observer session. The complete fullscreen stack is
reparented once; subsequent switches reorder it inside the same fullscreen
parent. Unsupported platforms fall back to the ordinary focus path without a
post-transition repair.

The permanent regression contract is `FULLSCREEN-ALT-TAB-001` through
`FULLSCREEN-ALT-TAB-003` and `FULLSCREEN-LIFECYCLE-001` through
`FULLSCREEN-LIFECYCLE-003` in the desktop self-test. It verifies both task
modes while the Alt+Tab panel is open, after each switch, and through real
injected text focus. It then restores one task to a window, closes it, and
proves that the surviving task remains fullscreen and accepts input.

## Goal

Switch between two true fullscreen tasks on a desktop display without either
task becoming freeform, even for a single intermediate frame. The final state
alone is not sufficient.

## Reproduction and confirmed model

- Two self-test activities are converted from bounded freeform windows to true
  fullscreen and then switched twice through the real MagicDesk Alt+Tab path.
- The failure is reproducible on a simulated desktop display: the task being
  exposed or brought forward becomes freeform. Some paths convert both tasks.
- The two activities are separate top-level fullscreen root tasks under the
  display's default task display area. They are not children of a shared
  fullscreen root.
- The default task display area is freeform-oriented. A hierarchy operation on
  one of its root tasks can make that task resolve its mode from the parent.
- Immediately before the failing switch, WMShell's desktop repository can have
  empty `activeTasks`, `visibleTasks`, and `freeformTasksInZOrder`. The tasks
  nevertheless remain in WindowManager's hierarchy.
- `persist.wm.debug.enter_desktop_by_default_on_freeform_display` was `false`
  in the later controlled experiments.
- The Alt+Tab overlay itself is not the cause. Modes remain fullscreen while
  the nonfocusable panel is open and change when the task switch is committed.

## Failed switching mechanisms

1. **Existing WMShell `TO_FRONT` stack focus.** The first controlled self-test
   converted the fullscreen pair to freeform after Alt+Tab completion.
2. **`ActivityTaskManagerService.setFocusedTask()`.** Framework inspection and
   a probe showed that it creates a `TO_FRONT` transition and reaches the same
   desktop activation path.
3. **`startActivityFromRecents()` with explicit display and fullscreen launch
   options.** Selecting the existing task through the Overview-style API still
   converted it to freeform.
4. **Keep the MagicDesk Alt+Tab panel open after focus.** The result did not
   change, excluding panel dismissal and desktop-host refocus as the cause.
5. **Inject native Android Alt+Tab.** The system shortcut did not switch the
   external display's task stack, so it cannot be delegated to Android.
6. **Move the current fullscreen task to the back.** The old top task remained
   fullscreen, but the newly exposed task resolved to freeform.
7. **Repair fullscreen immediately after `TO_BACK`.** This could restore the
   final mode, but was rejected because it necessarily contains the forbidden
   intermediate freeform state.
8. **Make the top task translucent before changing order.** A separate
   translucency phase caused the hidden task to appear through an `OPEN`
   transition and triggered the same conversion.
9. **Combine translucency and reorder in one WCT.** The atomic form did not
   prevent the hidden task's activation from being treated as an opening.
10. **Temporarily use `alwaysOnTop`.** Raising the selected fullscreen task by
    layer still produced an opening/activation and converted its mode.
11. **Minimal `TRANSIT_CHANGE` containing only `reorder(target)`.** Changing
    the transition type did not help; the hierarchy operation itself caused
    freeform inheritance.
12. **Explicit `setHidden` swap in one synchronous WCT.** Resuming the newly
    shown Activity generated an internal `OPEN`, and WMShell converted it.
13. **Create a common organized fullscreen root and reparent both tasks.** The
    root and fullscreen children were created successfully, but the child that
    became visible was still processed by the firmware's desktop policy and
    became freeform. The prototype was removed before later experiments.
14. **Set
    `persist.wm.debug.enter_desktop_by_default_on_freeform_display=false`.**
    This did not fix the switch. A later dump also proved that the desktop
    repository was empty, so default-entry policy was not the full cause.
15. **Use externally exposed `IDesktopMode` desk lifecycle operations.**
    `stash` is a deprecated no-op, `removeDesk` is destructive, and
    `moveTaskOutOfDesk` is blocked unless the experimental multi-desktop
    backend is enabled. None provides a safe temporary desk deactivation.
16. **Synchronous WCT reorder followed by explicit focus.** The explicit focus
    itself started another activation transition, so it was removed; the next
    experiment proved the reorder alone also fails.
17. **Synchronous WCT reorder without explicit focus.** Both tasks were already
    mode `5` immediately after `SyncWindowContainerTransaction.apply()`.
18. **`reorder(token, true, true)` for a fullscreen-only stack.** The branch
    received exactly the two fullscreen fixture tasks, but including parents
    still converted both tasks.
19. **`reorder(token, true)` without `includingParents`.** Avoiding parent
    reorder did not change the result.
20. **Set fullscreen mode and empty bounds for both tasks in the same WCT as
    reorder.** WCT applies configuration changes before hierarchy operations;
    the later reorder resolved the tasks back to freeform.
21. **Start the same reorder/configuration WCT as `TRANSIT_CHANGE`.** The result
    remained freeform, confirming that transition type is not sufficient.
22. **Nubia `ActivityTaskManagerService.removeFreeformForWR(taskId)`.** This
    vendor method calls `Task.moveTaskToBack()`. In the clean test it moved the
    visible fullscreen task back, left that old task fullscreen, and exposed
    the target as freeform. It is vendor-specific and may also remove special
    tasks, so it is unsuitable even as a fallback.

## Diagnostic dead ends and invalid runs

These runs did not evaluate a switching mechanism and must not be cited as its
result.

1. A run performed while the phone was briefly locked stopped during setup and
   left a simulated session. It was cleaned up and repeated unlocked.
2. Waiting for WMShell to become idle while the Alt+Tab overlay was open could
   not complete because the overlay participated in the transition.
3. The first pre-panel idle probe parsed a full SystemUI dump that ShellAccess
   truncated before the relevant block. Restricting the command to the
   `ShellTransitions` block fixed that diagnostic error.
4. A valid run later observed both transition tracks idle before focus, yet
   `TO_FRONT` still converted the tasks. Therefore waiting for idle is not a
   solution.
5. A subsequent build again failed its strict `ShellTransitions idle` text
   predicate and spent two full step timeouts without calling the fullscreen
   focus branch. The artificial wait was removed.
6. Two vendor-probe runs were interrupted manually and produced no result.
7. Switching airplane mode invalidated MagicDesk's Shizuku binder connection.
   The self-test then repeated 10-second binding timeouts, and report generation
   repeated them again. The apparent hang was unrelated to fullscreen focus.
8. After a cold MagicDesk restart restored the binder connection, the clean
   vendor-probe run produced the result recorded in item 22 above.
9. Two initial dedicated-TDA runs bypassed the experimental path because its
   guard compared the fixture process package with the task's Activity
   component package. They repeated the existing reorder failure and provide
   no result for the TDA mechanism.

## Framework constraints discovered

- Configuration changes in a WCT are applied before hierarchy operations, so
  `setWindowingMode(FULLSCREEN)` cannot override freeform mode inherited during
  a later reorder in the same transaction.
- `TaskOrganizer.createRootTask()` creates an organizer-owned root. On this
  firmware, organizer disposal removes every root with
  `mCreatedByOrganizer`, regardless of `removeWithTaskOrganizer`. A persistent
  root therefore requires a long-lived owner.
- A short-lived `app_process` command cannot safely own that root. If a future
  root-based design is used, ownership belongs in MagicDesk's existing
  long-lived shell command service and must include cleanup when the desktop
  display disappears.

## Confirmed mechanism

- MagicDesk creates an organizer-owned fullscreen `TaskDisplayArea` beneath
  the selected display and keeps its organizer alive in the existing shell
  task-observer service.
- On the first fullscreen Alt+Tab, the complete bottom-to-top fullscreen stack
  is reparented into that area. Later switches only reorder tasks inside the
  same fullscreen parent, so no task inherits freeform mode from the default
  display area.
- The hierarchy transaction is applied synchronously. Only after the tasks are
  under the fullscreen parent does MagicDesk use the normal task-focus path to
  synchronize Activity and InputDispatcher focus. The same focus operation is
  unsafe while the tasks are still roots of the freeform-oriented default task
  area.
- Restoring or snapping a task first reparents that task to the display's
  default task area while it is still fullscreen. The ordinary window command
  runs only after that release. Closing the active task first focuses the
  topmost live survivor through the same fullscreen-area mechanism and waits
  until it is visible. It then removes the old, already-background task through
  one synchronous WCT. Task removal uses the same tracked lifetime, and the
  organizer-owned area is deleted when its last task leaves.
- A complete simulated self-test switched two true fullscreen tasks in both
  directions. Both remained `mode=fullscreen` while the Alt+Tab panel was
  visible, after each switch, and while real injected typing verified that
  input focus followed the selected task.
- The same complete test passed on a physical VITURE wired display at
  1920x1200. Cleanup restored firmware mirroring, removed the desktop display
  and left no fullscreen task area behind.
- Self-test cleanup deleted the area and its simulated display without leaving
  a `MagicDesk fullscreen stack` container or stale task behind.
- The lifecycle self-test restored and closed one task while its peer remained
  in the fullscreen area, verified there was no task-stack visibility gap, then
  injected text into the survivor. A separate simulated-display test removed
  the live display without normal session cleanup and verified that the runtime
  stopped, the fullscreen area vanished, and the fixture was either removed or
  migrated to display 0 as fullscreen.
- If task-display-area creation is unavailable on another platform, MagicDesk
  falls back to its previous stack-focus path instead of attempting a delayed
  fullscreen repair.

## Remaining validation

- Repeat the fullscreen Alt+Tab regression test on a wireless physical
  display.
