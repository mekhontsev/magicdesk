# Fullscreen Plane Focus Investigation

Started: 2026-08-28

Status: unresolved. This document records experiments only. Rejected runtime
code must not remain in production.

## Invariant

On a non-phone desktop, every fullscreen task remains in its independent
organizer plane for its complete fullscreen residency. Activating an existing
freeform task above one or more fullscreen planes must:

- preserve every task's windowing mode, bounds, parent, and Activity instance;
- commit the requested back-to-front order atomically;
- make the target application and its input window focused;
- avoid a second raw transition, Activity launch, periodic poll, or timed retry;
- leave no transition performance session tied to a removed display.

`WINDOW-020-PREPARE` followed by `WINDOW-015` is the strict self-test
reproducer. The first observed failure occurs while the test is preparing the
first peer: three existing tasks are still freeform children of the organizer
workspace and activation of the first task does not acquire input focus. The
assertion must not be weakened. Later steps extend the same ownership model to
mixed freeform/fullscreen stacks and independent fullscreen planes.

## Confirmed Boundary

The physical WCT can place a freeform task on top inside the organizer
workspace and the typed task observer can report that task active while
InputDispatcher still names the previous freeform task as the focused
application. The command is not complete in this state: pointer and keyboard
input still belong to the old task.

A real Activity launch into the session area selects that area correctly.
An ordinary WCT reorder of an already resumed child does not necessarily call
`DisplayContent.setFocusedApp`, even when it changes the final hierarchy. The
unresolved boundary is therefore framework focus ownership for existing tasks
inside an organizer TaskDisplayArea; mixed plane/workspace ordering is a
second consumer of the same primitive.

The device currently has pre-existing transition performance sessions for
missing displays. Their count is a self-test baseline. Every experiment must
also pass `SELFTEST-SYSTEM-002` with zero newly created stale sessions.

## Rejected Approaches

### ActivityTaskManager `moveTaskToFront`

Calling `moveTaskToFront` after the physical WCT mixes application selection
with a second task transition. It can appear to repair a one-plane crossing,
does not reliably repair the two-plane reproducer, and one earlier run created
a transition performance session for a removed simulated display. It is not a
valid steady-state focus operation.

### A second focusability WCT

Applying physical order first, then making all fullscreen planes non-focusable
and reordering the freeform target in another direct WCT did not move the
InputDispatcher focus away from the old plane. Reasserting the target with
`includingParents=true` in that second WCT also failed. Besides being
ineffective, splitting one semantic activation across two topology commits
weakens the ownership model.

### ActivityTaskManager `setFocusedTask`

The firmware implementation calls `moveFocusableActivityToTop`, but it can
return without repairing the stale input owner when the target Activity is
already considered resumed. In the strict reproducer the typed task observer
reported the freeform task active, InputDispatcher remained on the old plane,
and focus convergence failed. No new stale transition session was observed in
that run. The later fullscreen-to-freeform events were cleanup operations, not
evidence that `setFocusedTask` changed their modes.

### ActivityTaskManager `setFocusedRootTask`

The local firmware implementation differs materially from `setFocusedTask`.
It has no early return for an already resumed top Activity and does not create
a transition token. It calls `moveFocusableActivityToTop`; when physical root
order is already correct, that path appeared capable of updating
`DisplayContent.mFocusedApp` without launching the Activity. The strict
reproducer disproved that hypothesis: task observation selected the freeform
target but InputDispatcher focus remained on the previous plane. The run
created no new stale transition session, but it did not complete the command,
so the operation is not retained in production.

### Relaunching the existing task

Adding `WindowContainerTransaction.startTask` after a WCT that had already
placed the target at the top did not repair input focus: ActivityTaskManager
optimized the selection of the already-top task into a no-op. The run created
no new stale transition session.

A staged variant is still under test: the target is reordered away and then
selected by `startTask` in the same WCT, with its existing organizer area and
bounds supplied in `ActivityOptions`. The unstaged first attempt also proved
that omitting current bounds lets ActivityTaskManager replace them with its
default freeform size. This candidate is acceptable only if the strict test
proves unchanged parent, mode, bounds, application fullscreen state, and
transition cleanup.

### Timing and test exceptions

Additional sleeps, periodic polling, delayed retries, package-specific rules,
and accepting task-observer focus without InputDispatcher focus are rejected.
They hide the ownership mismatch rather than completing the user-visible
operation.

## Next Investigation

1. Finish the staged `startTask` experiment with current bounds and organizer
   area pinned in the same WCT.
2. If it succeeds for workspace-only focus, reuse one typed operation for the
   mixed plane/workspace path instead of duplicating selection logic.
3. Trace WindowManager focused-application and Activity lifecycle changes
   during the strict reproducer.
4. Accept a solution only after simulated, phone, and wired self-tests pass
   unchanged and cleanup creates no stale transition session.
