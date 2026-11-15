# Honor Vendor Interface Audit

This inventory covers MagicDesk's Honor window-geometry integration, inspected
firmware interfaces and unresolved transition constraints. It distinguishes
production behavior, static firmware evidence and local prototype coverage.
It is not a device certification or an experiment log.

- [Evidence Scope](#evidence-scope)
- [Selection And Ownership](#selection-and-ownership)
- [Active Interfaces](#active-interfaces)
- [Geometry And Native Gestures](#geometry-and-native-gestures)
- [Resize Animation](#resize-animation)
- [Remote Transition Boundary](#remote-transition-boundary)
- [Verification Requirements](#verification-requirements)

## Evidence Scope

The supplied Honor framework/SystemUI inspection and resize reports concern
Android 17 / API 37, firmware `11.0.0.117`. The unresolved animation is visible
in feedback for MagicDesk `2.3-dev.252.194afed`. This scope does not establish
behavior on other Honor firmware or every configuration of that build.

Local remote-transition probes ran as UID 2000 on Nubia NX809J, Android 16 /
API 36, with unscaled fixtures. They establish properties of that local
submission path, not Honor's animation selection or scaled-surface behavior.
Unit fixtures cover the adapter contract independently of either device.

The APK minimum remains API 34 and managed Desktop requires API 35. Honor's
interface detection does not change these boundaries; see
[runtime API levels](runtime-api-levels.md).

## Selection And Ownership

`PlatformDrivers` composes the Android baseline with `HonorPlatformDriver` when
the task-scale interface is available. Selection does not use manufacturer or
model names. The extension contributes only `PlatformComponent.WINDOWING`;
it does not replace shared display, input, HOME or background-work services.

- [HonorWindowingDriver](../app/src/main/java/io/github/mekhontsev/magicdesk/platform/honor/HonorWindowingDriver.java)
  owns firmware task-scale reads and transaction-local scale writes.
- [FrameworkWindowGeometry](../app/src/main/java/io/github/mekhontsev/magicdesk/FrameworkWindowGeometry.java)
  owns display/logical coordinate conversion and explicit placement plans.
- `FrameworkTaskSnapshotSource` publishes normalized task geometry through the
  existing shared observer. There is no separate vendor polling loop.
- `TaskWindowingCommand` and `ShellWindowTransitionExecutor` retain transaction
  submission and the system-owned surface lifecycle. Geometry does not authorize
  task activation or focus changes.

**Settings > Compatibility > Firmware window geometry** controls this adapter
after restarting MagicDesk. It appears only when the firmware read interface is
detected. It is not an animation switch and does not change system settings.
The preference reaches the privileged service and command helpers; unscaled
Android placement retains its native path.

HOME acquisition/fallback, physical-input routing and independent freeform
launch remain shared policies, not Honor-specific workarounds. See
[compatibility](compatibility.md) and
[framework ownership](architecture-framework.md).

## Active Interfaces

Production task operations use the already-authorized shell service; a passive
read-interface match does not prove that a write or complete transition works.

| Interface | Production use | Failure semantics |
| --- | --- | --- |
| `TaskInfo.hnTaskInfoEx.mFreeformScale` | Read scale from an existing task snapshot, without another Binder request. | Missing/invalid scale remains unknown, not an invented scale of 1. |
| `WCT.getHnWindowContainerTransactionEx()` | Resolve the firmware transaction adapter. | An unavailable writer fails the requested adapted geometry operation. |
| `setUseShellCalculateBounds(WCT, token, false)` | Keep explicit bounds caller-calculated instead of accepting firmware default placement. | Both geometry setters must resolve before writing. |
| `setFreeformScale(WCT, token, scale)` | Append scale to the same WCT as logical bounds. | Rejected writes are reported, not silently treated as unscaled success. |

Diagnostics includes logical and display-space bounds, scale availability and
the adapter's read/write status. Detection uses the actual interface shape;
it does not require another APK, a system-property override or stopping Honor's
desktop service.

## Geometry And Native Gestures

Freeform content dimensions and the visible window footprint are different
coordinate spaces. Shared placement, snap, restore, persistence and transfer
consume display-space bounds. The adapter converts them to logical dimensions
and retains the task scale in the same transaction. Rollback restores captured
logical bounds and scale instead of converting with a destination's scale.

Adapted placement waits for the existing transition boundary before reading
current scale. A newly placed freeform task may acquire its scale only when its
first transition completes; explicit placement can finalize its footprint once.
There is no continuous geometry-repair loop. Ordinary launches without a
confirmed task token remain dispatch-only.

Honor's native corner gesture can scale content rather than resize its logical
layout. MagicDesk does not replace that gesture or native captions. No verified
per-task switch between native scaling and ordinary resizing is implemented.
That limitation is separate from acceptance of explicit snap/fullscreen/restore
commands. Hosted Linux clients must also preserve the distinction between
Activity `WindowMetrics` and display-space task bounds.

## Resize Animation

Reported half/quarter placement can briefly enlarge a window before reaching
correct final bounds. The reported scale is approximately `0.5576`; the visible
overshoot is consistent with its inverse, approximately `1.79`.

In the supplied SystemUI, the independent `CHANGE` branch with a snapshot uses
`DefaultTransitionHandler.startBoundsChangeAnimation`. It constructs
`SizeChangeAnimation(startBounds, endBounds, 1.0f, 1.0f)` for 400 ms and writes
surface matrices without the task scale. This is a strong candidate explanation,
not proof of the handler selected during the remote-device reproduction.
Correct final bounds and input do not validate intermediate frames.

### Inspected Controls

No verified parameter-only per-resize opt-out was found through the current
submission boundary:

- `WindowOrganizer.startNewTransition(type, WCT)` creates a transition with zero
  standard flags and no remote-handler argument. The inspected WCT has no
  per-task animation opt-out.
- Honor `setUseTransitionAnim(false)` writes vendor change flag `0x200`, not
  standard `NO_ANIMATION` (`0x40000`). The inspected consumer skips transition
  type 1020 / change mode 4, not the generic bounds animation. The separate
  native-desktop/miniform predicate in `HwTaskEx.isAbortTransit` does not
  establish applicability to an ordinary `CHANGE`.
- Standard `NO_ANIMATION` is checked early enough, but its writer,
  `Transition.setNoAnimation`, is internal to WindowManager. `Change.isSkipAnimation`
  is checked after bounds-animation creation. An overall type of `NONE` or
  `TO_FRONT` does not eliminate a participant's `CHANGE` mode and snapshot.
- Honor `setTransitionFlags` uses a separate vendor namespace. Its
  `TRANSIT_FLAG_IN_PARENT_REMOTE_TRANSITION` (`1`) takes an early finish path,
  skipping `setupStartState` and normal ready notification. It assumes a parent
  animation owner, not a standalone no-animation request.
- Snapshot suppression depends on special display/application/split state;
  no ordinary per-resize control was found. Vendor types 2203 (template layout)
  and 3008 (shortcut/prestart) are not established ordinary-resize contracts.
- `setBoundsChangeTransaction` defers caller-supplied surface work until a resize
  frame. It neither supplies the task surface handle nor transfers ownership of
  decoration/crop/input state. Framework task surface sizing skips organized
  tasks; an empty transaction cannot repair stale geometry by itself.

These are bounded static findings, not proof that every firmware API is incapable
of a scoped opt-out. No flag-only workaround is implemented. Do not substitute
global animation settings, unrelated transition types or repeated bounds repairs;
retain the [submission constraints](fullscreen-transitions.md#submission-constraints).

## Remote Transition Boundary

### Access And Scope

`IShellTransitions` is created inside SystemUI and delivered to the configured
system Recents service during `onInitialize`; acquiring HOME is not equivalent
to receiving that interface. The inspected Honor SystemUI follows this route.
`ShellController.createExternalInterfaces` invalidates the previous interfaces,
so impersonating Recents is not a safe way to obtain the Binder.

On the local Nubia, read-only UID 2000 discovery found no registered
Shell-transitions Binder endpoint or extension on `activity_task`, `window`,
`statusbar` or `launcherapps`, although SystemUI's dump lists the internal
interface. Inspected shell commands and Honor controllers expose no usable
geometry-only remote submission. This does not rule out other firmware APIs.

Remote registration uses filters, not a binding to one exact WM transition
token. Task/activity, launch-cookie and mode predicates do not uniquely identify
our resize. Once selected, a remote owns completion; it cannot simply decline
an unrelated transition back to the default handler. Binder access alone would
not settle concurrency or ownership of non-target participants.

Honor's `startFreeformTask` delegates through `HnFreeformCoordinator` to
`startActivityFromRecents`, replacing supplied launch bounds with calculated
defaults. `resizeStack` requires the separate legacy
`inHwMultiStackWindowingMode` path. Neither provides ordinary freeform
geometry-only submission. The inspected `requestToWmShellDesktop` handler
delegates to volume control, not arbitrary Shell operations.

### Local Prototype Coverage

An immediate `IRemoteTransition` handler can receive an existing-task transition
through `ActivityOptions.makeRemoteTransition` plus launch bounds, submitted by
`moveTaskToFront` or `startActivityFromRecents`. The local transition is
`TO_FRONT` with a `CHANGE` participant. Remote options nested in `WCT.startTask`
alongside `WCT.setBounds`, submitted through `startNewTransition`, did not select
that callback.

The scale-1 fixture on private offscreen displays at 1280x900/160 DPI covers final
bounds, rendered pixels, acknowledged pointer/key input, coalesced no-op
cancellation, rapid resize and task removal during resize. Surface work must
reuse `ShellWindowTransitionExecutor.prepareSurfaceTransactions()` and WM's
shared transaction queue. These checks are not managed-Desktop self-tests.

The two-window fixture demonstrates the blocker: `moveTaskToFront` with remote
options activates a background task despite `setAvoidMoveToFront()`. Its
participant carries `MOVE_TO_TOP` and the peer loses focus. A pre-submission
focus check cannot make that operation geometry-only. Native `resizeTask`
creates a system `CHANGE` without a remote-handler argument.

An explicit launch remote can also receive unrelated phone-task participants;
task-specific submission does not guarantee task-exclusive transition contents.
The prototype does not establish live-owner death recovery, managed fullscreen
plane preservation or Honor scale handling. Its identity matrix is valid only
for the local unscaled fixture.

## Verification Requirements

No production remote resize handler or animation compatibility toggle is
implemented. It requires an accessible, scoped geometry-only submission that
preserves focus, ordering, scale and system surface/input finalization. Do not
replace SystemUI, its task organizer or its Recents connection to obtain it.

Adapter fixtures are in `HonorWindowingDriverTest`, `TaskWindowGeometryTest` and
`FrameworkWindowGeometryTest`. They cover interface selection, invalid scale,
transaction-local writes, coordinate conversion and placement ownership, not
remote firmware behavior. Further device acceptance must include:

- Half/quarter placement, fullscreen/restore and phone/external transfer, with
  intermediate-frame evidence as well as final geometry and actual input.
- Background-window resize without activation, rapid commands and unrelated
  transition participants.
- Cancellation, remote-owner death and complete cleanup before claiming a
  production remote handler.
- Applicable phone, simulated and wired self-tests through production paths;
  another device's passing test does not establish Honor compatibility.

Disposable evidence resides under `build/honor-feedback-20261114-1703/`,
`build/honor-framework-20261114/`, `build/honor-systemui-20261114/` and
`build/remote-transition-probe/`, including `shell-remote-access/`. These local
artifacts contain reports, firmware extracts and probe receipts; they are not
tracked or distributed and may be removed by build cleanup. This audit retains
the qualifying conclusions and safety constraints, not private reports or
step-by-step run history.
