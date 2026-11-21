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

Local remote-transition and passive input-window probes ran as UID 2000 on
Nubia NX809J, Android 16 / API 36, with unscaled fixtures. They establish
properties of that local path, not Honor's animation selection or
scaled-surface behavior.
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

Honor's native manual resize can scale content rather than resize its logical
layout. This is not a corner-specific contract: the inspected positioner accepts
edge bitmasks, and native gesture handling also includes movement, cancellation
and transitions to other window modes. MagicDesk does not replace these gestures
or native captions. No verified per-task switch between native scaling and
ordinary resizing is implemented. That limitation is separate from acceptance
of explicit snap/fullscreen/restore commands. Hosted Linux clients must also
preserve the distinction between Activity `WindowMetrics` and display-space
task bounds.

### Native Resize Observation

The supplied `HnCaptionWindowDecorViewModel` selects among
`HnScaledResizeTaskPositioner`, `GridResizeTaskPositioner` and
`FluidResizeTaskPositioner`. Its debug force-resize selector is a process-static
system property, not a scoped per-window control. Its existence does not justify
changing global firmware policy.

The scaled positioner's start/move/end/cancel callbacks are internal SystemUI
calls. During movement it changes surface scale; release can also start a
rebound animation to an allowed size. A pointer release is therefore not proof
that the final geometry has been committed. Its local drag listeners and
animation-performance notifications are not an external resize subscription.
The task scale can be published before the local rebound animator completes.
Neither `IHnFreeformEx.getIsFreeformAnimRunning` nor `isInAnimation` establishes
completion of that animator: their inspected owners track other animation state.

The inspected Android 16/17 task listeners and Honor task snapshots do not
publish the internal `Task.mDragResizing` lifecycle. No usable external
begin/end/cancel subscription has been established through the inspected Honor
interfaces. Standard
[`IDecorViewGestureListener`](https://android.googlesource.com/platform/frameworks/base/+/8aa6f2119eef24e944750fe74bf13e4dddbded75/core/java/android/view/IDecorViewGestureListener.aidl)
reports DecorView gesture interception, not a task-resize lifecycle.

The actual caption receiver is `HnDragResizeInputListener`. Its
`isHandlingDragResize` state is local to SystemUI, not exposed by
`IHnFreeformEx`. The additional `ITaskStackListenerEx` reports display and task
lifecycle, not resize gestures. `getMultiWinGestureStatus` reads a secure
setting, while `HnSystemStatusManager` observes application/audio/service scenes;
neither is a per-task native resize subscription.

#### Passive Input-Window Evidence

`WindowInfosListener`, already owned by
`FrameworkInputWindowObservationSource`, can observe committed input regions
without intercepting motion events. A disposable local fixture on an offscreen
1280x900 display confirms that the native `TaskInputSink` expands on corner
press, remains expanded while held, and returns to its normal region after
release. The logical task changes from 640x550 to 720x598. A press/release
without movement also restores the region. Injected `ACTION_CANCEL` produces
the same region lifecycle and commits the resized bounds in this fixture; the
region alone cannot distinguish release from cancellation. The attempted edge
gesture did not engage native resizing, so edge coverage remains unverified.
Every fixture removes its task and display; it does not acquire HOME or Desktop.

This is not a portable gesture detector. In the supplied Honor implementation,
the sink surface is cropped to task bounds, its region is reset by ordinary
decoration relayout (including unchanged geometry), and template gestures omit
the drag-region update. An unchanged or restored region is therefore not proof
of an ended gesture. Surface transforms are useful geometry evidence, but a
matching transform is not proof that an animator has relinquished ownership.

The production correction uses task-scale commits, not these input regions.
Passive pointer observation remains a separate candidate: its permission,
input-delivery effects and final-animation boundary need verification. Do not
replace native input ownership or parse SystemUI logs as the production protocol.

#### Indirect Completion Candidate

In the inspected scaled positioner, ordinary movement updates the surface and
local gesture state. `onDragPositioningEnd` publishes the current scale through
a WCT. A changed task scale can therefore trigger a one-shot geometry check
using the existing task observer; a separate raw-input subscription is not
necessarily required to recognize this candidate.

This publication is not an animation-completion acknowledgement. The rebound
path starts a local animator, publishes scale, and later applies final bounds
from its finish callback. In a bottom/right resize, the final logical bounds
can equal the original bounds, so a second distinct bounds event is not
guaranteed. A temporarily matching input transform and the global WM transition
barrier do not establish that this local animator has finished. The saved-bounds
path in `HnFreeFormAssistant` updates private records, not an externally
subscribable completion event. Native grid resizing and mode-changing gestures
must not be classified solely from the ordinary scaled-positioner path.

The existing observer carries the previous and current typed geometry in the
same notification. `NativeWindowScaleCorrection` admits a changed scale only
within the same visible task/display/freeform residency and unchanged logical
dimensions and density. New tasks, unknown geometry, mode/display changes and
pending MagicDesk geometry commands do not qualify. The existing command queue
orders one correction; the shell executor waits at the WM barrier and rechecks
the captured geometry and Binder task identity before submission. There is no
inactivity timer or new task sampler.

This bounded attempt is enabled for testing through **Firmware window geometry**.
It is not proof of local rebound-animation completion. A late SystemUI finish
may supersede it; target-device acceptance must include rebound and subsequent
gestures. Failures are diagnostic, not a reason to keep rewriting the window.

Range checks are not a substitute for that boundary. SystemUI's positioner
can adjust its scale range for grid layout and task geometry; the legacy
ActivityTaskManager range is not a complete description of those decisions.
`getHnFreeformParams()` exposes caption sizes and corner radius, not the live
resize range. The external `IHnFreeformListener` publishes freeform visibility,
not per-task resize completion.

#### Shared Correction Contract

Correction belongs to the ordinary geometry transition used by snap commands,
not a separate surface writer or a custom resize mode. Keep the committed
display-space footprint and request the pre-gesture scale through
`FrameworkWindowGeometry.plan(task, footprint, retainedScale)`. This converts
logical dimensions while retaining position; it does not force scale 1 or
change interface density. Repeated gestures can use the same operation.

Changing scale has a stronger firmware contract than retaining it:

- The inspected WCT handler sets `HwTaskEx.mFreeformScale` in WindowManager.
  `sendPcScreenTaskScale` only handles the PC-assistant path; it is not a general
  synchronization with SystemUI.
- SystemUI separately retains `HnRunningFreeformTaskInfo.getCurrentScale()`.
  Both the next native gesture and decoration surface updates use this value.
  The inspected ordinary `CHANGE` observer and task-info callback do not copy
  the new task scale into that retained value.
- `HnFreeformManager.getService()` exposes `IHnFreeformEx`, including
  `notifyFreeformScaleChange(taskId, scale)` and
  `getFreeformStackScale(taskId)`. The notification updates the retained value,
  but is a one-way Binder call. Its return is not an acknowledgement, and a
  following synchronous query must not be assumed to serialize with it.
  Permission, acknowledgement and ordering relative to the WCT still require
  validation on the target firmware.

The inspected `getHnFreeformEx()` gate admits system app-ID 1000 or a caller
whose package is a system application or platform-signed. It is not an
unconditional shell grant, but it does not inherently exclude UID 2000:
`com.android.shell` can qualify through its package. The package lookup and
returned Binder must be checked under the actual executor. Do not make root
or a system-UID switch a prerequisite.

Any scale-changing submission must reconcile these owners through the focused
Honor adapter, including explicit failure and rollback behavior. Retaining the
current scale must not acquire additional Binder calls. Geometry-only success
in a unit fixture does not validate this firmware contract or the completion
signal. Do not bypass it by repeatedly rewriting bounds until they stick.

`TaskWindowingCommand` prepares the complete WCT before requesting the SystemUI
scale. `TaskScaleSynchronization` observes acknowledgement, revalidates the task,
submits through the common `CHANGE` owner and verifies WM geometry. Failure
compensates the decoration cache to the observed WM scale, rather than blindly
undoing a transaction that may already have committed. Removed, moved or replaced
tasks cannot receive compensation. Unchanged-scale placement retains its existing
path without the extra peer calls. The one-way acknowledgement wait is bounded;
timeout rejects submission and does not mean that a native animation finished.

Focused tests cover repeated footprint conversion, candidate admission,
delayed acknowledgement, unchanged scale, timeout, denied access, stale identity
and compensation before/after WM commit. Local unscaled-device checks do not
validate the Honor service's permission or scaled surface/input result.

Honor's `hnfreeform freeform` shell command is not an alternative completion
protocol. Its inspected helper centers the task at a firmware-selected scale
and submits bounds; it neither preserves the requested origin/retained scale
nor acknowledges native-gesture completion.

Conversion from content scaling to logical resize must retain the
pre-gesture scale and committed visible footprint, handle cancellation according
to the native outcome, distinguish mode/display changes, and submit through the
existing geometry owner. It must not infer completion from inactivity, add
another task polling loop, or treat
every observed bounds/scale change as a manual resize. A proven shared Android
observation belongs in the framework layer; firmware-only evidence belongs in
the focused platform adapter. Custom decorations or a separate user resize
mode are not required by this design.

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

## Simultaneous Freeform Windows

The inspected `HwMultiWindowManager.limitForegroundFreeformStackCount` counts
ordinary mode-5 windows as well as Honor freeforms. Above the firmware limit
it can move a root behind the workspace and make its Activity invisible without
closing it. An active custom task profile with `isLimitedByQuantity=false`
is excluded from both the count and the demotion pass.

MagicDesk requests that exemption for ordinary managed freeform tasks on an
external Desktop, using a task-specific WCT. It leaves existing custom and
takeover profiles alone. It does not change the system limit, take over the
window, reorder peers, or apply this policy to phone windows. The compatibility
report shows the firmware's observed `freeformMultiplicity` for each freeform
task; `custom-exempt` is stronger evidence than a successful WCT submission.
Honor SystemUI skips saved native freeform bounds and some off-screen drag
relocation for custom windows. MagicDesk still owns its saved placement.
Simultaneous visibility on a physical Honor display still needs device confirmation.

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
remote firmware behavior. `NativeWindowScaleCorrectionTest` and
`TaskScaleSynchronizationTest` cover release admission and the two-owner commit.
The integrated build passed all 3,895 app unit tests. On NX809J / Android 16,
simulated Desktop completed with 157 PASS / 0 FAIL and phone Desktop with
141 PASS / 0 FAIL; warnings covered pre-existing transition state and framework
ownership logs. Both runs completed cleanup. These unscaled-device results are
regression coverage, not Honor gesture acceptance. Further device acceptance
must include:

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
