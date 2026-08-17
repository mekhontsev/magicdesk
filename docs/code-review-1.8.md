# MagicDesk 1.8 Whole-Codebase Review

Reviewed: 2026-08-17
Baseline: `966bba7`
Audit head before this report: `49c6e03`

## Scope and method

This is an integrity review of the accumulated codebase, not a review of the
latest diff. Old and new production code were treated equally. The reviewed
surface contains 367 production Java files (83,081 lines), five debug-only Java
files (817 lines), 137 JVM test files (9,685 lines), 12 AIDL contracts, both
native input helpers, the separate kernel-fixes APK, manifests/resources,
Gradle logic, scripts, and CI workflows.

The review combined:

- ownership and lifecycle tracing for activities, services, Binder handles,
  listeners, executors, delayed callbacks, processes, file descriptors, input
  grabs, wake locks, and temporary system state;
- dependency-boundary checks for UI, orchestration, shell adapters, platform
  drivers, SoC drivers, and the separate kernel module;
- inspection of task/window/display/input state machines and their failure
  paths, including stale callbacks and service death;
- file/path/URI permission and TOCTOU review;
- lint, native compilation with `-Wall -Wextra -Werror`, the complete JVM
  suite, duplication scanning, manifests, packaging and CI/release scripts.

## Overall conclusion

No unresolved architectural defect requiring a rewrite was found. The intended
direction remains coherent: UI delegates to orchestration, orchestration owns
session state, shell adapters own privileged Android mechanics, and optional
platform/SoC adapters contain implementation-specific capability discovery.

The codebase is large because the product surface is large, not because one
generic framework has absorbed unrelated behavior. Several classes are large,
but the largest UI and self-test classes already delegate their stateful
subsystems. Splitting them only by line count would increase navigation and
lifecycle coupling without creating a stronger boundary.

The audit did find concrete old and new lifecycle, cancellation, persistence,
and cleanup defects. They were fixed in focused commits rather than hidden
behind new abstraction layers.

## Findings fixed

| Area | Defect | Resolution |
| --- | --- | --- |
| Runtime ownership | Task watcher, density, input and shell callbacks could outlive a superseded operation. | Generation/ownership checks and deterministic teardown in `7477b78`. |
| UI ownership | Folder, wallpaper, setup, diagnostics, recording and Files work could publish after its owner ended. | Lifecycle guards in `782f153`. |
| Deferred cleanup | Streams, observer handles, overlays, search and notification dispatch had close races. | Idempotent close and stale-generation rejection in `e68d626`. |
| Desktop callbacks | Launch, console, controls and pointer UI accepted stale completions. | Current-owner checks in `1b81c1a`. |
| Vendor adapters | Nubia hardware, input and capture adapters retained work/listeners across teardown races. | Explicit adapter lifecycle handling in `35f0e4c`. |
| Self-test state | Static observations and geometry stages could mix generations. | Bounded generation-scoped observations in `aa5afe8`. |
| Task panels | Alt-Tab, audio, notification and overview loads could overwrite newer state. | Superseded load rejection in `838b4f4`. |
| Shell commands | Shell argument quoting was duplicated in six call sites. | One `ShellCommandLine.quote` implementation in `696ac83`. |
| Temporary grants | Process-local shell file URI grants grew without a bound. | Access-ordered 256-entry cap in `709ab67`. |
| Capability startup | A transient desktop backend miss became a permanent negative result. | Retryable capability probe in `5830961`. |
| Service teardown | Runtime callbacks could run against partially destroyed audio/service state. | Ordered teardown and callback invalidation in `b9e4ee8`. |
| File operations | Cancel during STARTING could not cancel a request that had not received its remote ID. | Request generation invalidation and late remote cancellation in `517885b`. |
| Recording | Shell death left the controller reporting stale STARTING/RECORDING state. | Shell state listener and operation generations in `ffe28f9`. |
| Diagnostics memory | Event deduplication signatures grew for the process lifetime. | Bounded 256-entry signature set and test in `62151ea`. |
| Async snapshots | Old file/recording snapshots could be delivered after newer ones. | Identity/sequence checks in `5dd377c`. |
| URI imports | Desktop and Files duplicated provider metadata/copy logic; Files reopened by path. | Shared `ContentUriTransfer` and verified inode-bound destination in `2eefb72`. |
| API intent | A dead pre-minSdk branch and unexplained intentional lint exceptions obscured contracts. | Removed/documented in `d84511c`. |
| Adapter boundaries | The architecture test allowed one platform implementation to import another. | Cross-implementation import guard in `8c62781`. |
| Native input | Failed post-grab verification could return with the source still marked grabbed. | Atomic rollback in `ff45141`. |
| Kernel-fix UI | Runtime failures could strand the UI; a late callback could update a destroyed Activity. | Lifecycle generation and complete error mapping in `5c728fa`. |
| Diagnostic persistence | Three synchronous preference writes ignored failure. | Preserve required synchronous writes and log rejection in `4cea0e3`. |
| Kernel-fix storage | The separate APK did not explicitly forbid cloud/device transfer. | Explicit extraction policy in `dfae89d`. |
| Phone panel | A process queue retained an Activity and could use it after destruction. | Application-context fallback in `1acdc72`. |
| Self-test preparation | Closing Diagnostics before the test began left host observation active. | Preparation-only cancellation in `f94c45e`. |
| Debug probes | Failure could strand instrumentation, skip Binder unregister, or replace an active caption transport. | Guaranteed completion and original-state restoration in `0af8d19`. |
| Setup UI | Runtime failures outside `IOException` handling could leave startup/setup permanently busy. | Async-boundary recovery and diagnostics in `49c6e03`. |
| SoC probe compatibility | An incompatible Qualcomm Binder revision produced Android crash records instead of a normal unavailable-backend result. | Fail-closed app-process command handling in `da2cbfe`. |

## Architectural assessment

### Runtime and session ownership

`MagicDeskRuntimeService`, the runtime coordinators, immutable session
registry/snapshot, display-scoped task runtime, and serialized transition queue
form one ownership chain. Display drivers describe transport behavior but do
not close sessions by calling back into the facade. Process-lifetime queues are
intentional and do not retain activities. Activity-facing callbacks are either
weak, removable, generation-scoped, or now application-context based.

### Windowing and tasks

Android tasks, WMShell transitions, native captions and task display areas
remain authoritative. Policy and mechanism are distinct: repositories/parsers
read state; task commands perform shell-side atomic work; controllers decide
when to invoke them. Polling remains only where Android exposes no usable
completion callback and is bounded by a verified final state. The fullscreen
task-area and cross-display paths are deliberately serialized because
concurrent ownership would be incorrect.

### Input

Physical keyboard/mouse capture, uinput devices, pointer injection, keyboard
layouts and platform routing have separate contracts. The C helpers deduplicate
multi-source key state, restore repeat settings, release grabs and virtual
devices on every exit path, and reconcile hot-plugged sources. Vendor pointer
and mirrored-text APIs remain optional adapters. The native bridge is compiled
with warnings as errors.

### Files, desktop and console

Desktop and Files share naming, context menus, keyboard commands, drag payloads,
URI transfer and shell file metadata where their semantics match. They retain
separate selection and destination-name policies. File providers are
non-exported, grants are tokenized and bounded, and writable shell files are
opened against verified device/inode metadata. Console alone accepts arbitrary
commands and owns a persistent shell session; no command execution leaks into
the file browser UI.

### Platform and SoC isolation

`PlatformDrivers` and `SocDisplayModeBackends` are the only composition
roots. Generic Android, Nubia and Qualcomm implementations do not import one
another. Vendor strings and Binder transactions remain in adapters; shared
code consumes only contracts and feature flags. The strengthened source test
now enforces future implementation-to-implementation isolation too.

### Diagnostics and self-test

Diagnostics are passive unless a workflow is explicitly run. Events, reports
and static self-test observations are bounded. The self-test framework shares
task-stack invariants across phone, simulated and external targets and performs
semantic state waits instead of using delays as success criteria. Large suites
remain sequential scenario owners; splitting them would duplicate fixture and
cleanup state.

### Build and packaging

The main APK, hidden-API compile stubs and root-only kernel-fixes APK remain
separate modules. CI tests/lints/builds on Linux and Windows, verifies APK
contents and signatures, rejects tracked local IDE files, and publishes only
verified signed development artifacts. Documentation-only changes correctly
skip APK builds.

## Duplication decisions

The measured Java clone rate was 0.52% (24 small clone groups across 72,472
scanner-visible lines). The meaningful content-URI duplication was removed.
The remaining repeated platform getter methods are explicit composition, task
command fragments execute in independent `app_process` domains, and panel
construction repetition does not yet share state or behavior. Introducing base
classes or a UI framework for those fragments would add coupling for little
reduction.

## Deliberately retained design

- The root Java package is broad, but moving hundreds of stable classes solely
  for directory aesthetics would create churn in AIDL, manifests, shell class
  names and tests. New external implementations already use dedicated
  `platform/` and `soc/` packages.
- `ShellAccess`, `ShizukuCommandService`, `FileManagerActivity`,
  `DesktopShellActivity` and the input self-test suite are large boundary or
  orchestration classes. Their delegates are real ownership boundaries; no
  further split was justified by this review.
- Hidden/private Android APIs are unavoidable for the product's task, display
  and input behavior. Calls are isolated behind shell/capability adapters and
  must fail closed or report diagnostics.
- Synchronous preference commits are retained only where a recovery/diagnostic
  journal must exist before the following system operation.
- The optional kernel module remains exact-kernel and exact-driver gated. It is
  not part of the main APK or generic platform path.

## Residual risks

1. Real-device self-tests remain the final contract for firmware APIs, WMShell
   transitions, input routing, capture and display removal. JVM tests cannot
   prove those vendor/system interactions.
2. `TaskDisplayAreaLaunchCommand`, `ShizukuCommandService`,
   `ShellTaskObserver` and the native bridges have high compatibility impact.
   Keep changes focused and require phone/simulated plus relevant physical
   display self-tests.
3. The root package is discoverability debt, not current dependency debt.
   Revisit only when a new independently owned subsystem creates a natural
   package boundary.
4. This audit did not run interactive UI/device self-tests; it intentionally
   avoided taking over the user's screen. Static checks, JVM tests, lint and
   builds are recorded below.

## Production Java ledger

Every production Java source is listed below. “Reviewed” means its contracts,
callers, lifecycle and subsystem interactions were included in this audit; it
does not claim that private Android firmware behavior can be proven off-device.

| File | Area | Result |
| --- | --- | --- |
| `io/github/mekhontsev/magicdesk/AboutDialog.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/AltTabController.java` | Tasks / windowing | Fixed or hardened in 838b4f4; reviewed after change. |
| `io/github/mekhontsev/magicdesk/AppItem.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/AppLaunchTarget.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/AppLogViewerActivity.java` | Console / tools | Fixed or hardened in e68d626; reviewed after change. |
| `io/github/mekhontsev/magicdesk/AppProcessCommand.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/AppTaskController.java` | Tasks / windowing | Fixed or hardened in 1b81c1a; reviewed after change. |
| `io/github/mekhontsev/magicdesk/AppWindowState.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/AppWindowStateStore.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/AppWindowStateTracker.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/BoundedProcessRunner.java` | Console / tools | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/BuiltInDesktopAppCatalog.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/BuiltInWindowLauncher.java` | Tasks / windowing | Fixed or hardened in 1b81c1a; reviewed after change. |
| `io/github/mekhontsev/magicdesk/BuiltInWindowRegistry.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/CalendarPanelController.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/CaptureDiagnostics.java` | Diagnostics / provisioning | Fixed or hardened in 4cea0e3; reviewed after change. |
| `io/github/mekhontsev/magicdesk/CommandConsoleActivity.java` | Console / tools | Fixed or hardened in e68d626, 1b81c1a; reviewed after change. |
| `io/github/mekhontsev/magicdesk/CompatibilityDiagnostics.java` | Diagnostics / provisioning | Fixed or hardened in 62151ea, 4cea0e3; reviewed after change. |
| `io/github/mekhontsev/magicdesk/ConsoleCommandHistory.java` | Console / tools | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/ConsoleDisplayController.java` | Console / tools | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/ConsoleModeSwitcher.java` | Console / tools | Fixed or hardened in 696ac83; reviewed after change. |
| `io/github/mekhontsev/magicdesk/ConsolePathText.java` | Console / tools | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/ConsoleSeedActivity.java` | Console / tools | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/ConsoleSessionController.java` | Console / tools | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/ConsoleShellSession.java` | Console / tools | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/ConsoleTaskReturnCommand.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ContentUriTransfer.java` | Files / workspace | Fixed or hardened in 2eefb72; reviewed after change. |
| `io/github/mekhontsev/magicdesk/ContextTarget.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/ControlActivity.java` | Desktop UI | Fixed or hardened in 1b81c1a, 49c6e03; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DeferredContextDragGesture.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/DesktopActivity.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopAudioPanelController.java` | Desktop UI | Fixed or hardened in 838b4f4, b9e4ee8; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopCaptureTarget.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopContentStore.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopContextMenuController.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopControlsController.java` | Desktop UI | Fixed or hardened in 1b81c1a; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopCursorTraceProbe.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopDisplayDriver.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopDisplayDriverSupport.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopDisplayDrivers.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopDisplayFeatures.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopDisplayTarget.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopDisplayTaskState.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopFile.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopFileInfo.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopFileProvider.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopFileRepository.java` | Files / workspace | Fixed or hardened in 2eefb72; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopFileUri.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopFolderController.java` | Files / workspace | Fixed or hardened in 782f153; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopFolderShortcut.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopFolderShortcutFile.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopGridLayout.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopHostWindowController.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopInputController.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopInputDevice.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopInputDeviceDiscovery.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopInputRoutingOwnership.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopInputRoutingSession.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopItemViewFactory.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopLayoutController.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopLayoutStore.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopManagedTaskPolicy.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopMouseBridge.java` | Input | Fixed or hardened in 696ac83; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopNotificationListenerService.java` | Desktop UI | Fixed or hardened in e68d626; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopNotificationMapper.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopPathPolicy.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopPhoneUiReconciler.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopPlacement.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopPlacementEngine.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopPointerCommand.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopPointerInjector.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopPreferences.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopRuntimeBridge.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopScreenPolicy.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestActivity.java` | Self-test framework | Reviewed for bounded observation, cleanup and semantic state checks. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestCapabilityAudit.java` | Self-test framework | Reviewed for bounded observation, cleanup and semantic state checks. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestCleanup.java` | Self-test framework | Reviewed for bounded observation, cleanup and semantic state checks. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestComponents.java` | Self-test framework | Reviewed for bounded observation, cleanup and semantic state checks. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestController.java` | Self-test framework | Reviewed for bounded observation, cleanup and semantic state checks. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestDisplayRemovalSuite.java` | Self-test framework | Reviewed for bounded observation, cleanup and semantic state checks. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestFixtureState.java` | Self-test framework | Fixed or hardened in aa5afe8; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestGeometry.java` | Self-test framework | Fixed or hardened in aa5afe8; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestHostObserver.java` | Self-test framework | Fixed or hardened in aa5afe8; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestInputSuite.java` | Self-test framework | Fixed or hardened in aa5afe8; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestPhoneUiObserver.java` | Self-test framework | Fixed or hardened in aa5afe8; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestResult.java` | Self-test framework | Reviewed for bounded observation, cleanup and semantic state checks. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestSteps.java` | Self-test framework | Reviewed for bounded observation, cleanup and semantic state checks. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestTarget.java` | Self-test framework | Reviewed for bounded observation, cleanup and semantic state checks. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestTaskStackGuard.java` | Self-test framework | Reviewed for bounded observation, cleanup and semantic state checks. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestTasks.java` | Self-test framework | Reviewed for bounded observation, cleanup and semantic state checks. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestWindowSuite.java` | Self-test framework | Fixed or hardened in aa5afe8; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopSessionController.java` | Display / session runtime | Fixed or hardened in 696ac83; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopSessionRegistry.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopSessionSnapshot.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopSessionTransitionCoordinator.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopSessionWakeLock.java` | Display / session runtime | Fixed or hardened in d84511c; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopShellActivity.java` | Desktop UI | Fixed or hardened in 7477b78, 838b4f4; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopStateStore.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopSystemActionsController.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopTaskController.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopTaskDescription.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopTaskLaunchObserverCommand.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/DesktopTaskLaunchProbe.java` | Tasks / windowing | Fixed or hardened in 696ac83; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopTaskParkingController.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopTaskParkingRuntime.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopTaskRuntime.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopTaskRuntimeRegistry.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopTaskRuntimeState.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopTaskSnapshotController.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopTaskWatcher.java` | Tasks / windowing | Fixed or hardened in 7477b78; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopTaskbarRevealController.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopTaskbarVisibilityPolicy.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopTransitionGate.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopTransitionSurfaceProbe.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopUiFactory.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopUiGateway.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopViewport.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopWallpaperController.java` | Desktop UI | Fixed or hardened in 782f153; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DesktopWallpaperFileAction.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopWidgetContainer.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopWidgetController.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopWidgetPickerController.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopWindowTransitionController.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DesktopWorkspaceController.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DeviceLockCommand.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/DeviceSetupActivity.java` | Diagnostics / provisioning | Fixed or hardened in 782f153, 49c6e03; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DeviceSetupManager.java` | Diagnostics / provisioning | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DeviceSetupRuntimeController.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DeviceSetupView.java` | Diagnostics / provisioning | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DiagnosticsActivity.java` | Diagnostics / provisioning | Fixed or hardened in 782f153, f94c45e; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DisplayCapturePanelController.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DisplayCaptureSource.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DisplayDensityController.java` | Display / session runtime | Fixed or hardened in 7477b78; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DisplayDensityPolicy.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DisplayImePolicyController.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DisplayPixelProbe.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DisplayProfileController.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DisplayProfileStore.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DisplayRecordingController.java` | Display / session runtime | Fixed or hardened in 782f153, 35f0e4c, ffe28f9, 5dd377c; reviewed after change. |
| `io/github/mekhontsev/magicdesk/DisplayRecordingSettings.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/DisplayWindowingModeCommand.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/ExistingTaskController.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/ExternalTaskMigrationPolicy.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/FileDragPayload.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/FileIconResolver.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/FileItemContextMenu.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/FileKeyboardCommand.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/FileManagerActivity.java` | Files / workspace | Fixed or hardened in 782f153; reviewed after change. |
| `io/github/mekhontsev/magicdesk/FileManagerBackgroundContextMenu.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/FileManagerClipboard.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/FileManagerImportController.java` | Files / workspace | Fixed or hardened in 2eefb72; reviewed after change. |
| `io/github/mekhontsev/magicdesk/FileManagerLayoutMode.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/FileManagerOperationController.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/FileManagerSearchController.java` | Files / workspace | Fixed or hardened in e68d626; reviewed after change. |
| `io/github/mekhontsev/magicdesk/FileManagerView.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/FileOpenWithController.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/FileOperationCenter.java` | Files / workspace | Fixed or hardened in 517885b, 5dd377c; reviewed after change. |
| `io/github/mekhontsev/magicdesk/FilePropertiesFormatter.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/FileSizeFormatter.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/FileTreeDeletion.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/FloatingWindowController.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/FreeformTaskCleanupPolicy.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/FullscreenAppLauncher.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/GlobalDesktopPlacement.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/HardwareKeyboardLayoutCommand.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/HardwareKeyboardLayoutController.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/HiddenTaskApi.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/InputBridgeDiagnostics.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/InputMethodMenuController.java` | Input | Fixed or hardened in 696ac83; reviewed after change. |
| `io/github/mekhontsev/magicdesk/InputStateDump.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/ItemActivationPolicy.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/KeyboardLayoutPolicy.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/KeyboardShortcutStateMachine.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/KeyboardShortcutWatcher.java` | Input | Fixed or hardened in 696ac83; reviewed after change. |
| `io/github/mekhontsev/magicdesk/LauncherAppRepository.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/LauncherIconRenderer.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/LocalDesktopNavigationController.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/LocalDesktopSessionState.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/MagicDeskApplication.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/MagicDeskExitCoordinator.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/MagicDeskRuntime.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/MagicDeskRuntimeBackend.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/MagicDeskRuntimeService.java` | Display / session runtime | Fixed or hardened in b9e4ee8; reviewed after change. |
| `io/github/mekhontsev/magicdesk/MagicDeskSessionController.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/MagicDeskSessionHost.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/MagicDeskSettings.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/MagicDeskTouchpadActivity.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/MediaRecorderAudioRecorder.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/MediaTrackMuxer.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/MirrorInputEditText.java` | Input | Fixed or hardened in d84511c; reviewed after change. |
| `io/github/mekhontsev/magicdesk/NativeDesktopController.java` | Desktop UI | Fixed or hardened in 5830961; reviewed after change. |
| `io/github/mekhontsev/magicdesk/NativeWindowBoundsController.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/NotificationCenterController.java` | Desktop UI | Fixed or hardened in 838b4f4; reviewed after change. |
| `io/github/mekhontsev/magicdesk/OverlayPanelController.java` | Desktop UI | Fixed or hardened in e68d626; reviewed after change. |
| `io/github/mekhontsev/magicdesk/PackageNameValidator.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/PersistentConsoleCommandExecutor.java` | Console / tools | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/PhoneControlPanelController.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/PhoneControlPanelLauncher.java` | Desktop UI | Fixed or hardened in 1acdc72; reviewed after change. |
| `io/github/mekhontsev/magicdesk/PhoneDesktopTaskRecovery.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/PhoneDesktopTaskRecoveryCommand.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/PhoneDisplayDriver.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/PhoneHomeComponents.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/PhoneHomeRecoveryController.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/PhoneTaskGuardDiagnostics.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/PhoneTouchpadController.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/PlatformAudioCaptureDriver.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/PlatformDevice.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/PlatformDiagnostics.java` | Diagnostics / provisioning | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/PlatformDriver.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/PlatformDrivers.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/PlatformFeatures.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/PlatformInputRoutingDriver.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/PlatformPhoneUiDriver.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/PlatformPointerDriver.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/PlatformProjectionDriver.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/PlatformSupportLevel.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/PlatformSystemControls.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/PlatformTextInputDriver.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/PlatformWallpaperDriver.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/PlatformWindowingDriver.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/PointerEdgeRevealState.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/PointerSpeedPanelController.java` | Input | Fixed or hardened in 1b81c1a; reviewed after change. |
| `io/github/mekhontsev/magicdesk/PreferredFileHandlerCommand.java` | Files / workspace | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/RecordingAudioMode.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/RelativeWindowBounds.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/RuntimeDesktopInputCoordinator.java` | Input | Fixed or hardened in 7477b78; reviewed after change. |
| `io/github/mekhontsev/magicdesk/RuntimeDesktopSessionCoordinator.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/RuntimeDesktopTaskCoordinator.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/RuntimeDisplayCoordinator.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/RuntimeInputCoordinator.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/SelfTestTaskStackInvariantAnalyzer.java` | Self-test framework | Reviewed for bounded observation, cleanup and semantic state checks. |
| `io/github/mekhontsev/magicdesk/SelfTestTaskStackReport.java` | Self-test framework | Reviewed for bounded observation, cleanup and semantic state checks. |
| `io/github/mekhontsev/magicdesk/SerializedDesktopOperationQueue.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/SessionProfile.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/SettingsActivity.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/SettingsView.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/ShellAccess.java` | Shell / hidden Android adapter | Fixed or hardened in 7477b78; reviewed after change. |
| `io/github/mekhontsev/magicdesk/ShellCommandLine.java` | Shell / hidden Android adapter | Fixed or hardened in 696ac83; reviewed after change. |
| `io/github/mekhontsev/magicdesk/ShellDesktopDirectory.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellDesktopFocusController.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellDesktopFolderHandle.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellDirectoryObserverHandle.java` | Shell / hidden Android adapter | Fixed or hardened in e68d626; reviewed after change. |
| `io/github/mekhontsev/magicdesk/ShellDisplayRecordingSession.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellExternalTaskMigrationGuard.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellFileAdapter.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellFileGrantStore.java` | Shell / hidden Android adapter | Fixed or hardened in 709ab67; reviewed after change. |
| `io/github/mekhontsev/magicdesk/ShellFileInfo.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellFileNamePolicy.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellFilePage.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellFilePathPolicy.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellFileProvider.java` | Shell / hidden Android adapter | Fixed or hardened in 709ab67; reviewed after change. |
| `io/github/mekhontsev/magicdesk/ShellFileSystem.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellFreeformTaskCleanup.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellFullscreenTaskArea.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellInputRoutingHandle.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellPackageInstaller.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellScriptLauncher.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellSelfTestTaskStackGuard.java` | Self-test framework | Reviewed for bounded observation, cleanup and semantic state checks. |
| `io/github/mekhontsev/magicdesk/ShellServiceConnection.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellStreamHandle.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellTaskObserver.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellTaskObserverHandle.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellTaskObserverManager.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellTaskStateMonitor.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellTaskUidReader.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShellTransientTaskBoundsController.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShizukuCapabilityProbe.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShizukuCommandService.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/ShortcutCatalog.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/ShortcutHelpController.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/SimulatedDisplayDriver.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/SimulatedDisplayLease.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/SocDisplayModeBackend.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/SocDisplayModeBackends.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/StartMenuController.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/StartSearchController.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/SurfaceFlingerOptionCommand.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/SyncWindowContainerTransaction.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/SystemBarInsets.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/SystemMonitorReader.java` | Console / tools | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/SystemMonitorRepository.java` | Console / tools | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/SystemMonitorSnapshot.java` | Console / tools | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/SystemPanelController.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/SystemProcessSnapshot.java` | Console / tools | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/SystemUiDesktopRepositoryParser.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/SystemWallpaperReader.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/TaskCaptionInsetsCommand.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/TaskCaptionInsetsRefresher.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/TaskCaptionRenderCommand.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/TaskCaptionStructureCommand.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/TaskCaptionSurfaceCommand.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/TaskClientPreservingFullscreenTransitionCommand.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/TaskCommandQueue.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/TaskControlCommand.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/TaskDisplayAreaHandle.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/TaskDisplayAreaLaunchCommand.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/TaskFocusCommands.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/TaskFullscreenMoveCommand.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/TaskFullscreenTransitionCommand.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/TaskInputWindowParser.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/TaskLocalInsetsSourceParser.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/TaskManagerActivity.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/TaskOverviewController.java` | Tasks / windowing | Fixed or hardened in 838b4f4; reviewed after change. |
| `io/github/mekhontsev/magicdesk/TaskRepository.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/TaskStackParser.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/TaskWindowingCommand.java` | Shell / hidden Android adapter | Reviewed for quoting, bounded waits, Binder/resource cleanup and capability failure. |
| `io/github/mekhontsev/magicdesk/TaskbarController.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/TaskbarOverflowController.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/TaskbarOverflowPolicy.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/TermuxIntegration.java` | Console / tools | Fixed or hardened in d84511c; reviewed after change. |
| `io/github/mekhontsev/magicdesk/TouchEdgeRevealState.java` | Model / policy / utility | Reviewed for immutability, normalization and bounded state. |
| `io/github/mekhontsev/magicdesk/TouchpadHelpContent.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/TouchpadPointerMotion.java` | Input | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/WindowedAppLauncher.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/WindowedTaskLaunchLease.java` | Tasks / windowing | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/WiredDisplayDriver.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/WirelessDisplayDriver.java` | Display / session runtime | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/WorkspaceAppController.java` | Desktop UI | Reviewed with its subsystem lifecycle and ownership; no additional defect found. |
| `io/github/mekhontsev/magicdesk/platform/android/GenericAndroidAudioCaptureDriver.java` | Generic platform adapter | Reviewed against the platform contract; no vendor implementation dependency. |
| `io/github/mekhontsev/magicdesk/platform/android/GenericAndroidInputRoutingDriver.java` | Generic platform adapter | Reviewed against the platform contract; no vendor implementation dependency. |
| `io/github/mekhontsev/magicdesk/platform/android/GenericAndroidPhoneUiDriver.java` | Generic platform adapter | Reviewed against the platform contract; no vendor implementation dependency. |
| `io/github/mekhontsev/magicdesk/platform/android/GenericAndroidPlatformDiagnostics.java` | Generic platform adapter | Reviewed against the platform contract; no vendor implementation dependency. |
| `io/github/mekhontsev/magicdesk/platform/android/GenericAndroidPlatformDriver.java` | Generic platform adapter | Reviewed against the platform contract; no vendor implementation dependency. |
| `io/github/mekhontsev/magicdesk/platform/android/GenericAndroidPointerDriver.java` | Generic platform adapter | Reviewed against the platform contract; no vendor implementation dependency. |
| `io/github/mekhontsev/magicdesk/platform/android/GenericAndroidProjectionDriver.java` | Generic platform adapter | Reviewed against the platform contract; no vendor implementation dependency. |
| `io/github/mekhontsev/magicdesk/platform/android/GenericAndroidTextInputDriver.java` | Generic platform adapter | Reviewed against the platform contract; no vendor implementation dependency. |
| `io/github/mekhontsev/magicdesk/platform/android/GenericAndroidWallpaperDriver.java` | Generic platform adapter | Reviewed against the platform contract; no vendor implementation dependency. |
| `io/github/mekhontsev/magicdesk/platform/android/GenericAndroidWindowingDriver.java` | Generic platform adapter | Reviewed against the platform contract; no vendor implementation dependency. |
| `io/github/mekhontsev/magicdesk/platform/nubia/ChargeSeparationController.java` | Nubia platform adapter | Fixed or hardened in 35f0e4c; reviewed after change. |
| `io/github/mekhontsev/magicdesk/platform/nubia/ConsoleDisplayCommand.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/ConsoleModeState.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/InternalAudioSourceCapability.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/LocalDesktopHostRecoveryPolicy.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaAudioCaptureDriver.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaCapabilityProbe.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaCaptionVisibilityManager.java` | Nubia platform adapter | Fixed or hardened in 4cea0e3, 0af8d19; reviewed after change. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaConsoleModeController.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaCpuFreezerWorkingState.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaDesktopPropertyManager.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaExternalDisplayModeController.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaHdmiModeController.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaHostAssistPanelController.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaInputRoutingDriver.java` | Nubia platform adapter | Fixed or hardened in 35f0e4c; reviewed after change. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaMirrorInputPanelGuard.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaMirrorTextInputDriver.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaMouseController.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaPhoneUiDriver.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaPlatformDiagnostics.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaPlatformDriver.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaPointerDriver.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaPointerPositionGuard.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaProjectionDriver.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaSystemControls.java` | Nubia platform adapter | Fixed or hardened in 35f0e4c; reviewed after change. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaWallpaperDriver.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaWindowingDriver.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/PhoneDisplayGuard.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/PhoneDisplayGuardCommand.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/RedmagicEntryPointCatalog.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/RedmagicHardwareController.java` | Nubia platform adapter | Fixed or hardened in 35f0e4c; reviewed after change. |
| `io/github/mekhontsev/magicdesk/platform/nubia/RedmagicHardwarePanelController.java` | Nubia platform adapter | Fixed or hardened in 35f0e4c; reviewed after change. |
| `io/github/mekhontsev/magicdesk/platform/nubia/RedmagicHardwareSnapshot.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/RedmagicSettingsNamespace.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/SystemNavigationGuard.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/WirelessDisplayCommand.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/platform/nubia/WirelessDisplayController.java` | Nubia platform adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |
| `io/github/mekhontsev/magicdesk/soc/qualcomm/QualcommDisplayConfigBridge.java` | SoC adapter | Hardened in `da2cbfe`; incompatible Binder revisions now fail closed without an Android crash record. |
| `io/github/mekhontsev/magicdesk/soc/qualcomm/QualcommDisplayModeBackend.java` | SoC adapter | Reviewed as an optional capability adapter; failure remains inert or diagnostic. |

## Debug instrumentation ledger

Debug entry points are compiled only into debug builds. Their exported surface,
failure completion, state restoration and shell/vendor cleanup were reviewed
separately from production behavior.

| File | Area | Result |
| --- | --- | --- |
| `io/github/mekhontsev/magicdesk/DebugSelfTestActivity.java` | ADB self-test entry | Hardened in `0af8d19`; setup-audit failure now closes the transparent entry Activity. |
| `io/github/mekhontsev/magicdesk/DesktopLifecycleInstrumentation.java` | Lifecycle instrumentation | Hardened in `0af8d19`; an unexpected runtime failure now completes instrumentation with a failed report. |
| `io/github/mekhontsev/magicdesk/ShizukuProbeInstrumentation.java` | Shell capability instrumentation | Reviewed for Binder-listener removal, bounded waits and deterministic completion. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaSceneCallbackProbeCommand.java` | Nubia callback probe | Hardened in `0af8d19`; unregister now runs before a failed process exit. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaVendorProbeInstrumentation.java` | Nubia vendor instrumentation | Hardened in `0af8d19`; mutating probes restore the original caption ownership and unexpected failures complete the run. |

## JVM test ledger

| File | Area | Result |
| --- | --- | --- |
| `com/android/internal/inputmethod/InputMethodSubtypeSafeList.java` | Input | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/AppLaunchTargetTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/AppProcessCommandTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/AppWindowStateStoreTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/BoundedProcessRunnerTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/BuiltInDesktopAppCatalogTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/CaptureDiagnosticsTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/CompatibilityDiagnosticsTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/ConsoleCommandHistoryTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/ConsoleDisplayControllerTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/ConsolePathTextTest.java` | Files | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/ConsoleShellSessionTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopControlsControllerTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopCursorTraceProbeTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopDisplayDriversTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopDisplayTargetTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopDisplayTaskStateTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopFileRepositoryTest.java` | Files | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopFolderShortcutFileTest.java` | Files | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopInputDeviceDiscoveryTest.java` | Input | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopInputRoutingOwnershipTest.java` | Architecture/contract | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopManagedTaskPolicyTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopPathPolicyTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopPlacementEngineTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopPreferencesTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopScreenPolicyTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestCapabilityAuditTest.java` | Self-test policy | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestCleanupTest.java` | Self-test policy | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestGeometryTest.java` | Self-test policy | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestPhoneUiObserverTest.java` | Self-test policy | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestResultTest.java` | Self-test policy | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestTargetTest.java` | Self-test policy | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopSelfTestTasksTest.java` | Self-test policy | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopSessionRegistryTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopSessionSnapshotTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopSessionWakeLockTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopStateStoreTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopTaskControllerTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopTaskLaunchProbeTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopTaskParkingControllerTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopTaskRuntimeRegistryTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopTaskbarVisibilityPolicyTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopTransitionGateTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopTransitionSurfaceProbeTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopViewportTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopWallpaperFileActionTest.java` | Files | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DesktopWindowTransitionControllerTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DeviceSetupOverlayPolicyTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DeviceSetupWindowingPolicyTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DiagnosticsActivityManifestTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DisplayDensityPolicyTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DisplayPixelProbeTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DisplayProfileControllerTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/DisplayRecordingSettingsTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/ExternalTaskMigrationPolicyTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/FileDragPayloadTest.java` | Files | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/FileManagerClipboardTest.java` | Files | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/FileManagerContractTest.java` | Architecture/contract | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/FileManagerImportControllerTest.java` | Files | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/FileManagerLayoutModeTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/FileSizeFormatterTest.java` | Files | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/FileTreeDeletionTest.java` | Files | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/FreeformTaskCleanupPolicyTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/FullscreenAppLauncherTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/GlobalDesktopPlacementTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/HardwareKeyboardLayoutCommandTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/HardwareKeyboardLayoutControllerTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/ImmersiveRequestStateTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/InputBridgeDiagnosticsTest.java` | Input | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/ItemActivationPolicyTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/KeyboardLayoutPolicyTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/KeyboardShortcutStateMachineTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/MagicDeskExitCoordinatorTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/MagicDeskRuntimeTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/MediaTrackMuxerTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/NativeDesktopControllerTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/PersistentConsoleCommandExecutorTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/PhoneControlPanelLauncherTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/PhoneDesktopTaskRecoveryPolicyTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/PhoneHomeComponentsTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/PhoneHomeRecoveryControllerTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/PhoneTaskGuardDiagnosticsTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/PlatformDriversTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/PlatformProjectionDriverTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/PlatformSourceIsolationTest.java` | Architecture/contract | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/PointerEdgeRevealStateTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/RelativeWindowBoundsTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/RuntimeDesktopInputCoordinatorTest.java` | Input | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/RuntimeDesktopSessionCoordinatorTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/RuntimeDesktopTaskCoordinatorTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/RuntimeOwnershipIsolationTest.java` | Architecture/contract | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/SelfTestTaskStackInvariantAnalyzerTest.java` | Self-test policy | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/SessionProfileTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/SettingsActivityManifestTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/ShellAccessSnapshotTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/ShellCommandLineTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/ShellCommandResultTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/ShellFileNamePolicyTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/ShellFilePathPolicyTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/ShellFileSystemTest.java` | Files | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/ShellTransientTaskBoundsControllerTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/SimulatedDisplayLeaseTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/SystemMonitorReaderTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/SystemMonitorRepositoryTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/SystemUiDesktopRepositoryParserTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/TaskCaptionInsetsRefresherTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/TaskCaptionRenderCommandTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/TaskCaptionStructureCommandTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/TaskCaptionSurfaceCommandTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/TaskCommandQueueTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/TaskControlCommandTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/TaskDisplayAreaLaunchCommandTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/TaskFocusCommandsTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/TaskInputWindowParserTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/TaskLocalInsetsSourceParserTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/TaskRepositoryCommandTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/TaskStackParserTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/TaskbarOverflowPolicyTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/TermuxIntegrationTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/TouchEdgeRevealStateTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/TouchpadPointerMotionTest.java` | Input | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/platform/android/GenericAndroidWindowingDriverTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/platform/nubia/ChargeSeparationControllerTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/platform/nubia/DeviceSetupFirmwareProfileTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/platform/nubia/InternalAudioSourceCapabilityTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/platform/nubia/LocalDesktopHostRecoveryPolicyTest.java` | Pure policy/parser | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaCaptionVisibilityManagerTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaDesktopPropertyManagerTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaExternalDisplayModeControllerTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaHdmiModeControllerTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaWallpaperDriverTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/platform/nubia/NubiaWindowingDriverTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/platform/nubia/PhoneDisplayGuardTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/platform/nubia/RedmagicEntryPointCatalogTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/platform/nubia/RedmagicHardwareSnapshotTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/platform/nubia/RedmagicSettingsNamespaceTest.java` | Component unit | Reviewed; included in the passing JVM suite. |
| `io/github/mekhontsev/magicdesk/soc/qualcomm/QualcommDisplayConfigBridgeTest.java` | Runtime/windowing | Reviewed; included in the passing JVM suite. |

## AIDL ledger

| File | Area | Result |
| --- | --- | --- |
| `io/github/mekhontsev/magicdesk/DesktopFileInfo.aidl` | Binder contract | Transaction ordering, parcelable ownership and owner-token cleanup reviewed; covered by contract/build checks. |
| `io/github/mekhontsev/magicdesk/IDesktopFolderObserverCallback.aidl` | Binder contract | Transaction ordering, parcelable ownership and owner-token cleanup reviewed; covered by contract/build checks. |
| `io/github/mekhontsev/magicdesk/IFileOperationCallback.aidl` | Binder contract | Transaction ordering, parcelable ownership and owner-token cleanup reviewed; covered by contract/build checks. |
| `io/github/mekhontsev/magicdesk/IFileSearchCallback.aidl` | Binder contract | Transaction ordering, parcelable ownership and owner-token cleanup reviewed; covered by contract/build checks. |
| `io/github/mekhontsev/magicdesk/IShellDirectoryObserverCallback.aidl` | Binder contract | Transaction ordering, parcelable ownership and owner-token cleanup reviewed; covered by contract/build checks. |
| `io/github/mekhontsev/magicdesk/IShizukuCommandService.aidl` | Binder contract | Transaction ordering, parcelable ownership and owner-token cleanup reviewed; covered by contract/build checks. |
| `io/github/mekhontsev/magicdesk/ITaskObserverCallback.aidl` | Binder contract | Transaction ordering, parcelable ownership and owner-token cleanup reviewed; covered by contract/build checks. |
| `io/github/mekhontsev/magicdesk/SelfTestTaskStackReport.aidl` | Binder contract | Transaction ordering, parcelable ownership and owner-token cleanup reviewed; covered by contract/build checks. |
| `io/github/mekhontsev/magicdesk/ShellFileInfo.aidl` | Binder contract | Transaction ordering, parcelable ownership and owner-token cleanup reviewed; covered by contract/build checks. |
| `io/github/mekhontsev/magicdesk/ShellFilePage.aidl` | Binder contract | Transaction ordering, parcelable ownership and owner-token cleanup reviewed; covered by contract/build checks. |
| `io/github/mekhontsev/magicdesk/SystemMonitorSnapshot.aidl` | Binder contract | Transaction ordering, parcelable ownership and owner-token cleanup reviewed; covered by contract/build checks. |
| `io/github/mekhontsev/magicdesk/SystemProcessSnapshot.aidl` | Binder contract | Transaction ordering, parcelable ownership and owner-token cleanup reviewed; covered by contract/build checks. |

## Native, kernel and supporting source ledger

| File | Area | Result |
| --- | --- | --- |
| `.github/workflows/ci.yml` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `.github/workflows/release.yml` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `.editorconfig` | Repository policy | Reviewed for consistent text/indentation defaults. |
| `.gitignore` | Repository policy | Reviewed against tracked IDE, signing and generated artifacts. |
| `app/lint.xml` | Static-analysis policy | Reviewed; suppression is limited to the intentionally full-bleed launcher assets. |
| `app/src/debug/AndroidManifest.xml` | Debug manifest | Reviewed for exported debug entry-point protection and instrumentation isolation. |
| `app/src/main/AndroidManifest.xml` | Main manifest | Reviewed for exported components, permissions, URI grants, services and task/window declarations. |
| `hidden-api-stubs/src/main/java/android/app/IActivityController.java` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `hidden-api-stubs/src/main/java/android/app/TaskStackListener.java` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `hidden-api-stubs/src/main/java/android/view/InputChannel.java` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `hidden-api-stubs/src/main/java/android/view/InputMonitor.java` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `kernel-fixes/src/main/AndroidManifest.xml` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `kernel-fixes/src/main/java/io/github/mekhontsev/magicdesk/kernel/KernelFixesActivity.java` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `kernel-fixes/src/main/java/io/github/mekhontsev/magicdesk/kernel/XrResolutionFix.java` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `kernel-fixes/src/main/res/mipmap-hdpi/ic_launcher.png` | Kernel-fix APK asset | Reviewed through resource packaging and lint. |
| `kernel-fixes/src/main/res/mipmap-mdpi/ic_launcher.png` | Kernel-fix APK asset | Reviewed through resource packaging and lint. |
| `kernel-fixes/src/main/res/mipmap-xhdpi/ic_launcher.png` | Kernel-fix APK asset | Reviewed through resource packaging and lint. |
| `kernel-fixes/src/main/res/mipmap-xxhdpi/ic_launcher.png` | Kernel-fix APK asset | Reviewed through resource packaging and lint. |
| `kernel-fixes/src/main/res/mipmap-xxxhdpi/ic_launcher.png` | Kernel-fix APK asset | Reviewed through resource packaging and lint. |
| `kernel-fixes/src/main/res/raw/dp_mode_reset.ko` | Gated kernel artifact | Package isolation and SHA-256 compatibility gate reviewed. |
| `kernel-fixes/src/main/res/values/strings.xml` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `kernel-fixes/src/main/res/values/styles.xml` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `kernel-fixes/src/main/res/xml/data_extraction_rules.xml` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `kernel/xr-resolution-fix/.gitignore` | Kernel build policy | Reviewed; ignores only generated module build products. |
| `kernel/xr-resolution-fix/CHECKSUMS` | Kernel compatibility gate | Reviewed against the packaged module and expected vendor driver. |
| `kernel/xr-resolution-fix/Makefile` | Kernel build configuration | Reviewed; builds only the scoped `dp_mode_reset` module. |
| `kernel/xr-resolution-fix/dp_mode_reset.c` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `native/magicdesk_input_sources.c` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `native/magicdesk_input_sources.h` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `native/magicdesk_keyboard_bridge.c` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `native/magicdesk_uinput_bridge.c` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `scripts/build-xr-resolution-fix.sh` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `scripts/smoke-simulated-display.sh` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `scripts/start-next-version.sh` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |
| `scripts/verify-apks.sh` | Supporting source | Reviewed for lifecycle, privilege boundary, packaging and failure cleanup. |

## Resource ledger

| File | Area | Result |
| --- | --- | --- |
| `drawable/ic_desktop_file_archive.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_desktop_file_document.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_desktop_file_image.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_desktop_file_media.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_desktop_file_pdf.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_desktop_file_text.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_desktop_folder.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_desktop_folder_link.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_file_back.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_file_console.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_file_copy.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_file_cut.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_file_delete.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_file_forward.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_file_new_window.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_file_open_with.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_file_paste.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_file_properties.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_file_refresh.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_file_rename.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_file_sort.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_file_up.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_keyboard.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_magicdesk.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_notifications.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_phone_screen_off.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_phone_screen_on.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_show_desktop.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `drawable/ic_touchpad.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `mipmap-hdpi/ic_launcher.png` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `mipmap-mdpi/ic_launcher.png` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `mipmap-xhdpi/ic_launcher.png` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `mipmap-xxhdpi/ic_launcher.png` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `mipmap-xxxhdpi/ic_launcher.png` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `values/strings.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `values/styles.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |
| `xml/data_extraction_rules.xml` | Android resource | Reviewed through manifest/resource inspection and Android lint. |

## Build/config ledger

| File | Area | Result |
| --- | --- | --- |
| `app/build.gradle` | Build configuration | Reviewed for reproducibility, signing boundaries, SDK/NDK/JDK compatibility and module isolation. |
| `build.gradle` | Build configuration | Reviewed for reproducibility, signing boundaries, SDK/NDK/JDK compatibility and module isolation. |
| `gradle/native-helpers.gradle` | Build configuration | Reviewed for reproducibility, signing boundaries, SDK/NDK/JDK compatibility and module isolation. |
| `gradle/release-signing.gradle` | Build configuration | Reviewed for reproducibility, signing boundaries, SDK/NDK/JDK compatibility and module isolation. |
| `gradle.properties` | Build configuration | Reviewed for reproducibility, signing boundaries, SDK/NDK/JDK compatibility and module isolation. |
| `hidden-api-stubs/build.gradle` | Build configuration | Reviewed for reproducibility, signing boundaries, SDK/NDK/JDK compatibility and module isolation. |
| `kernel-fixes/build.gradle` | Build configuration | Reviewed for reproducibility, signing boundaries, SDK/NDK/JDK compatibility and module isolation. |
| `settings.gradle` | Build configuration | Reviewed for reproducibility, signing boundaries, SDK/NDK/JDK compatibility and module isolation. |
| `gradle/wrapper/gradle-wrapper.properties` | Build configuration | Reviewed for reproducibility, signing boundaries, SDK/NDK/JDK compatibility and module isolation. |
| `gradle/wrapper/gradle-wrapper.jar` | Build bootstrap | Tracked wrapper binary; the downloaded Gradle distribution is pinned by SHA-256. |
| `gradlew` | Build entry point | Standard generated wrapper; distribution integrity is pinned by SHA-256. |
| `gradlew.bat` | Build entry point | Standard generated Windows wrapper; exercised by the Windows CI job. |

## Verification

The audit's final verification command is:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :kernel-fixes:lintDebug :kernel-fixes:assembleDebug --no-daemon
```

It covers production and debug Java compilation, native helper compilation,
the complete JVM suite, both Android lint targets and both APK builds.
Interactive self-tests are intentionally excluded from this code-only audit.

Final result: 484 JVM tests passed with zero failures, both debug APKs built,
both native helpers rebuilt with warnings treated as errors, and
`scripts/verify-apks.sh` accepted the main/kernel package boundary. Android
lint reported no errors. Remaining warnings are the intentional private API
adapter surface, the non-resizeable phone touchpad, wording suggestions,
toolchain-version notices, and the isolated kernel artifact packaging noted
under residual risks above.

Post-review device validation ran the same external-display self-test against
both HDMI and Miracast. Each run completed with 88 passes, one expected warning
for the shell-inaccessible Nubia `edid_modes` file, zero failures and complete
cleanup. The runs covered caption structure/rendering, direct freeform launch,
phone-to-desktop migration, focus, native snap, maximized/fullscreen Alt+Tab,
desktop-surface stability and phone-UI isolation.
