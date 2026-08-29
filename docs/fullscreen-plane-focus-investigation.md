# Fullscreen Plane Focus Investigation

Started: 2026-08-28

Status: the focused-window contract is understood on direct displays; the
remaining freeform and mixed-layer activation failures are unresolved. This
document records experiments only. Rejected runtime code must not remain in
production.

## Experiment Contexts

The window topology changed during this investigation. Results obtained with
the vendor-created `NubiaAppMirrorDisplay` must not be treated as limitations
of the current direct-display architecture.

- **Legacy mirror** means the application workspace lived on
  `NubiaAppMirrorDisplay` and inherited vendor mirror lifecycle behavior.
- **Direct display** means MagicDesk owns its organizer hierarchy directly on
  the simulated, wired, or wireless display selected by the user.
- **Unverified context** means the historical trace did not record enough
  topology data to classify the run. Such a result is a reason not to use the
  approach yet, not proof that it cannot work on a direct display.

The current production architecture uses direct displays. Before a legacy or
unverified approach is rejected permanently, it must be reproduced on that
topology. Conversely, a workaround whose only purpose was keeping the Nubia
mirror display alive must not be carried into the direct-display path.

## Invariant

On a non-phone desktop, every fullscreen task remains in its independent
organizer plane for its complete fullscreen residency. Activating an existing
freeform task above one or more fullscreen planes must:

- preserve every task's windowing mode, bounds, parent, and Activity instance;
- commit the requested back-to-front order atomically;
- make the target input window focused and route pointer and keyboard input to
  it;
- avoid a second raw transition, Activity launch, periodic poll, or timed retry;
- leave no transition performance session tied to a removed display.

`WINDOW-020-PREPARE` followed by `WINDOW-015` is the strict fullscreen-plane
reproducer. It now passes on a direct simulated display. Later steps extend the
same ownership model to mixed freeform/fullscreen stacks and independent
fullscreen planes. `FOCUS-002` remains a separate valid failure when the
focused window, rather than only `FocusedApplication`, stays on a peer task.

## Confirmed Boundary

On a direct organizer display, InputDispatcher can legitimately keep the
desktop host as `FocusedApplication` while its focused window belongs to the
requested application. A cold application launch demonstrated this state with
working pointer input, and the existing parser contract already distinguishes
it from stale window focus. Therefore application focus equality is diagnostic
information, not the completion condition for a desktop activation command.

The command is incomplete when the focused window stays on a peer task. That
is the state still reproduced by `FOCUS-002`; it is not accepted by the
production focus controller or the self-test.

A real Activity launch into the session area selects that area correctly.
An ordinary WCT reorder of an already resumed child does not necessarily call
`DisplayContent.setFocusedApp`, even when it changes the final hierarchy. The
remaining boundary is framework focused-window ownership for existing tasks
inside an organizer TaskDisplayArea; mixed plane/workspace ordering is a
second consumer of the same primitive.

The device currently has pre-existing transition performance sessions for
missing displays. Their count is a self-test baseline. Every experiment must
also pass `SELFTEST-SYSTEM-002` with zero newly created stale sessions.

## Experiment Results

Unless a subsection says **direct-display confirmed**, its negative result is
legacy or has insufficient topology evidence and must be revalidated before it
is used to reject a direct-display design.

### ActivityTaskManager `moveTaskToFront`

**Context: unverified.**

Calling `moveTaskToFront` after the physical WCT mixes application selection
with a second task transition. It can appear to repair a one-plane crossing,
does not reliably repair the two-plane reproducer, and one earlier run created
a transition performance session for a removed simulated display. It is not a
valid steady-state focus operation.

### A second focusability WCT

**Context: unverified.**

Applying physical order first, then making all fullscreen planes non-focusable
and reordering the freeform target in another direct WCT did not move the
InputDispatcher focus away from the old plane. Reasserting the target with
`includingParents=true` in that second WCT also failed. Besides being
ineffective, splitting one semantic activation across two topology commits
weakens the ownership model.

### ActivityTaskManager `setFocusedTask`

**Context: unverified.**

The firmware implementation calls `moveFocusableActivityToTop`, but it can
return without repairing the stale input owner when the target Activity is
already considered resumed. In the strict reproducer the typed task observer
reported the freeform task active, InputDispatcher remained on the old plane,
and focus convergence failed. No new stale transition session was observed in
that run. The later fullscreen-to-freeform events were cleanup operations, not
evidence that `setFocusedTask` changed their modes.

### ActivityTaskManager `setFocusedRootTask`

**Context: direct-display confirmed.**

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

**Context: staged variant direct-display confirmed; first variant
unverified.**

Adding `WindowContainerTransaction.startTask` after a WCT that had already
placed the target at the top did not repair input focus: ActivityTaskManager
optimized the selection of the already-top task into a no-op. The run created
no new stale transition session.

A staged variant reordered the target away and selected it with `startTask`
in the same WCT, while preserving its organizer area, mode, and bounds in
`ActivityOptions`. It still failed `WINDOW-020-PREPARE`: the task observer
selected the target while InputDispatcher retained the old owner. The
unstaged first attempt also proved that omitting current bounds lets
ActivityTaskManager replace them with its default freeform size.

### Making the desktop host ineligible

**Context: direct-display confirmed.**

Making either the host task or the organizer host area non-focusable before
application activation failed earlier at `FULLSCREEN-PLANE-EXIT-003`: a task
restored from its fullscreen plane did not receive front focus. A host-window
relayout that kept `FLAG_NOT_FOCUSABLE` set was harmless but did not make WMS
recompute focus. These variants are not retained.

### Timing and test exceptions

Additional sleeps, periodic polling, delayed retries, package-specific rules,
and accepting task-observer focus without InputDispatcher focus are rejected.
They hide the ownership mismatch rather than completing the user-visible
operation.

## Next Investigation

1. Fix `FOCUS-002` using focused-window ownership as the completion contract;
   do not require `FocusedApplication` to stop naming the desktop host.
2. Diagnose native snap and maximized bounds changes without adding timing
   exceptions to the test.
3. Reconcile mixed fullscreen/freeform visual order through the same typed
   workspace activation operation.
4. Re-run legacy or unverified experiments on a direct display only when they
   address one of those concrete failures.
5. Accept a solution only after simulated, phone, and wired self-tests pass
   unchanged and cleanup creates no stale transition session.
