# Framework And Platform Adapters

[Architecture overview](architecture.md) | [Documentation](README.md)

Android-release compatibility and narrow firmware capabilities. These adapters do not own application policy.

- [Framework compatibility services](#framework-compatibility-services)
- [Platform services](#platform-services)
- [Physical Input](#physical-input)
- [Phone Screen And Touch Panel](#phone-screen-and-touch-panel)
- [Hardware Controls](#hardware-controls)
- [Device Setup And Recovery](#device-setup-and-recovery)

## Framework compatibility services

Android release differences and firmware differences are independent axes.
`FrameworkRuntime` resolves one process-wide framework profile and exposes
focused adapters rather than one broad compatibility utility.

- `FrameworkWindowingApi` is the only owner of hidden
  `WindowContainerTransaction` and token primitives. It resolves and caches
  construction, bounds, mode, ordering, parenting, visibility, focusability,
  density, orientation, task-start, and task-removal operations once.
- `FrameworkWindowingCompat` owns release-dependent meaning and polyfills,
  including requested-visible-types and caption-inset strategies. Transition
  code does not reflect optional signatures itself.
- `HiddenTaskApi` owns raw ActivityTaskManager task members and service access.
  `FrameworkTaskSnapshotSource` converts them into the parcelable
  `FrameworkTaskSnapshot` returned through typed AIDL to application policy and
  recovery code.
  Running-task queries omit application Intent extras at the framework boundary;
  component, data URI, categories, and flags remain available for task identity.
  Unused launch payloads must not consume the shared Binder buffer on every
  observation or explicit window command.
- `FrameworkInputSnapshotSource` is the only runtime owner of the bounded
  InputDispatcher dump used when no typed focus/cursor API exists.
- `FrameworkInputWindowObservationSource` is the shell-side owner of hidden
  `WindowInfosListener`. It exposes only commit generations: policy cannot
  inspect or reinterpret raw `InputWindowHandle` objects. Workspace focus
  waits on this SurfaceFlinger callback before taking its one-shot
  InputDispatcher snapshot.
- `FrameworkInputMethodCatalogApi` owns IME Binder discovery, current/enabled
  methods and subtypes, SafeList decoding and explicit subtype switching.
  `FrameworkKeyboardLayoutApi` owns hidden device identifiers, layout candidates,
  selection and verified writes. `HardwareKeyboardLayoutCommand` retains layout
  matching, deduplication and bounded cycling policy, using typed values only.
- `FrameworkActivityLaunchApi` owns hidden ActivityOptions setters and Activity
  launch signatures; `HiddenTaskApi` owns existing-task start and front operations.
  Launch policy still chooses mode, area, order and outcome handling. Ordinary
  options do not initialize the organizer; only an explicit area option resolves
  its token class. Optional app-side hints keep their existing failure handling.
- `FrameworkDesktopShellApi` owns WMShell help signatures, command encoding and
  repository dump acquisition syntax. Controllers choose the operation and
  transport, execute through their existing queues and verify postconditions.
  No raw command verb or framework member name crosses back into policy.

Repository isolation tests enforce these ownership rules. Version-specific
member names, WCT class lookups, raw task fields, direct input dumps, and text
production task queries cannot silently spread back into policy code.

`FrameworkTaskObservationSource` is the corresponding dynamic compatibility
service. It combines `TaskStackListener` wakeups with one bounded selected-
display snapshot every 150 ms while a desktop session is active, scanning at
most 16 tasks. One normalized `FrameworkTaskSnapshot` feeds stack reconciliation,
windowing-mode and bounds changes, immersive requests, caption-source
lifecycle, activity handoff protection, ownership reconciliation, and process
failure correlation. Consumers do not start their own polling loops or read
version-specific `TaskInfo` members.

Compatibility report generation may request one separate diagnostic snapshot
through `readDiagnosticTaskSnapshots`. That one-shot call adds task density and
dp configuration to the same typed model, then joins it with bounded launch
provenance and saved window state. It is never called by the 150 ms observer or
ordinary window operations.

Every observed facet records its provenance as `event`, `sampled`,
`event+sampled`, or `unavailable`. The periodic snapshot exists because even
the current framework does not reliably callback organizer-child Z-order,
native freeform bounds, or app-requested system-bar changes. It sleeps
indefinitely outside an active session and an explicit production operation can
wake it immediately; reconciliation reuses the same snapshot and adds no
second task query.

`DesktopTaskRuntime.observedTaskSnapshot` publishes the last complete app-side
repository observation for the active desktop, including its phone tasks,
before workspace filtering. Task Manager and explicit MCP task waits reuse
this publication instead of issuing a second periodic task query. Missing
readiness, a disconnected observer, or a stopped session invalidates it;
unknown is not an observed empty task list. Explicit user commands retain
their fresh repository reads.

Runtime timing has three explicit mechanisms:

- `EventDrivenWaits` wraps monitor waits released by a concrete callback or
  state publication. These waits consume no periodic CPU while idle.
- `BoundedStateAwaiter` owns polling only where the framework provides no
  reliable callback. Every call declares a semantic reason, deadline, and
  sample interval; self-tests use the same classification.
- `RuntimeDelays` owns intentional non-state pauses such as input gesture
  spacing, supervisor backoff, vendor command settling,
  watchdog ticks, and stream heartbeats.

Direct `Thread.sleep`, `SystemClock.sleep`, and `Object.wait` calls are rejected
outside these timing boundaries. Compatibility Diagnostics reports their
runtime counters and the last classified reason. The task observer's 150 ms
fallback remains separately visible in the framework runtime line because it
is a permanent active-session observation source, not a transition delay.
Input-window event registration, callback count, bounded waits, and timeouts
are reported separately as `inputWindowEvents`; they never share that polling
interval.

On frameworks that publish `TaskInfo.requestedVisibleTypes`, the task observer
uses it to correlate application-requested immersive state. Field presence alone
is insufficient: some Android 15 releases expose the field but always publish
`defaultVisible()` unless `enableFullyImmersiveInDesktop` is enabled. The
compatibility adapter checks the framework flag once on API 35/36, using the desktop flag
wrapper when available to retain its override semantics. It does not change
system feature flags. API 37 publishes these client insets unconditionally and
no longer exposes that flag; field presence and the debug profile still gate
observation. Before publishing the shell binding, the app reads the
public developer setting and passes its value (or explicit read failure) to
the shell runtime. Binding stores this snapshot without resolving windowing
APIs; the first windowing consumer performs the one-time detection. Hidden
flag inspection remains in the shell process. If
the wrapper's Settings read rejects the current Application's app-package/
shell-UID attribution, the adapter resolves that developer override using the
app's setting snapshot. A package-resource context does not change the
ContentResolver's attribution. The adapter preserves
the framework's raw flag and default-desktop/toggle semantics; it does not
replace the Application, modify framework caches, or grant hidden-API access
to the ordinary app process. Other probe failures remain unknown. The profile
is retained for the process lifetime, with no new task sampling or recurring
Binder calls.
An absent field, disabled publication, or unreadable flag
reports the observation as unavailable, with the reason in Diagnostics, rather
than as a synthetic non-immersive request. The task
listener and all other task state continue operating. A policy that needs to
distinguish app-requested fullscreen from an accidental activity handoff fails
open when this observation is unavailable and does not force a window mode.

Caption-inset handling selects the native exclusion operation when present.
On Android 15 it uses the older six-argument local InsetsSource operation; a
newer host running the Android 15 debug profile may bridge that semantic call
through the flags overload with flags set to zero. The source identity still
comes from the task and cleanup still uses the paired add/remove transactions.
The adapter does not register a competing display-insets controller or replace
SystemUI ownership.

Existing-task launch options use the same compatibility adapter for the optional
flexible-size hint. Frameworks without that method retain explicit launch mode,
bounds and parent; the Android 15 debug profile also omits the hint. An error
executing an available method is propagated, not treated as an absent capability.

`FrameworkDisplayCaptureApi` owns logical-display screenshots and pixel samples
through `IWindowManager.captureDisplay`. WindowManager resolves the logical ID
to the display layer tree, including virtual displays; the caller does not need
a physical-display token. The framework's bounded capture-listener wait is
classified as `DISPLAY_CAPTURE`. The adapter rounds frame scales upward only
when float precision would truncate an output pixel, and verifies the returned
bitmap dimensions before exposing it to callers. It resolves the capture argument
and listener family together: `ScreenCaptureInternal` when available, otherwise
`ScreenCapture`. Capture is on demand only. The shell service
uses a reliable pipe so MCP receives capture errors instead of an empty image.

`FrameworkTaskCaptureApi` decodes fresh task frames from
`HiddenTaskApi.takeTaskSnapshot(taskId)`. That adapter selects the older two-argument
ActivityTaskManager operation or the API-37 TaskSnapshotManager contract, always
requesting fresh pixels without cache updates. Modern snapshots supply their own
bitmap wrapper and buffer-release method; the retained raw-buffer getter can
return null and is not used for that contract.
It does not query or populate the Recent snapshot cache, initialize an organizer,
or fall back to display pixels. Android may refuse hidden tasks; the error stays
local to the capture. It validates real-image buffers, bounds allocations, and
releases HardwareBuffers and intermediate Bitmaps on success and failure.
`CapturePngPipe` owns PNG encoding and bitmap release for both capture paths.
Task-image dimensions may differ from logical task dimensions due to Android's
snapshot scale; both are exposed, and optional regions use source-image pixels.
The blocking framework request is classified as `TASK_CAPTURE`.

`MAGICDESK_FRAMEWORK_OVERRIDE=android15` is a debug-only semantic profile. It
can be combined with the independent `MAGICDESK_PLATFORM_OVERRIDE=android`
selection to test Android 15 framework behavior with the Standard Android
driver on newer vendor hardware. Release builds always detect the live
framework and platform. Future vendor fixtures are added at `PlatformDrivers`,
not as branches in the framework adapter or desktop runtime, and cannot claim
firmware APIs that the host does not expose.

## Platform services

MagicDesk ships one main APK from one codebase. New device support belongs in
runtime capability probes or a focused platform-driver implementation, while
shared desktop, task, window, and input behavior remains platform-independent.
Do not introduce per-model build variants or forks for differences that can be
isolated behind these boundaries.

- `PlatformDrivers` is the single process-start composition root. It always
  creates the Standard Android baseline, then may layer one detected
  `PlatformExtension` over it. `PlatformComponent` makes each override
  explicit: an extension can own projection without replacing windowing,
  pointer, input, phone UI, background work, audio, diagnostics, controls, launch
  targets, or runtime behavior. `ComposedPlatformDriver` uses that declaration
  as the source of truth and rejects a declared component with no
  implementation. `PlatformSelection` records the provider and detection
  evidence for every component. Hardware family names alone do not select a
  vendor implementation. A stock Nubia or REDMAGIC fingerprint or the
  `redmagic.app.manager` service selects the complete Nubia extension. On an
  AOSP-derived ROM for Nubia hardware, passive probes select only independently
  present projection, pointer, internal-audio, diagnostics,
  and hardware-control components; all others remain on the Standard Android
  baseline. The probes run under the ordinary application UID, do not require
  privileged access, and do not invoke the detected operations. In particular, absence
  of `redmagic.app.manager` keeps the vendor property writer out of Device
  Setup without suppressing unrelated APIs retained by a hybrid ROM.
  `PlatformDriver` exposes only existing variation points.
  `PlatformWindowingDriver` owns optional provisioning properties;
  `PlatformProjectionDriver` owns output modes, wireless-launch integration,
  and caption transport; the Android `PlatformPhoneUiDriver` exposes shared
  phone-panel power capability through `PhoneDisplayGuard`;
  `PlatformPointerDriver` owns optional read-only cursor observation. On Nubia
  firmware this is implemented by `NubiaDesktopPointerDriver` over the hidden
  global position query. Physical input
  routing itself stays in the shared Android implementation and uses standard
  input-location to display-unique-ID associations. Cursor observation is
  independent of device routing and shortcut filtering.
  `PlatformDiagnostics` contributes only the probes for the selected platform.
  A selected `SYSTEM_CONTROLS` provider identifies the platform integration,
  not every optional hardware control. Nubia cooling settings are read through
  one typed, read-only snapshot shared with the production controller; fan and
  pump control keys and effective state are reported independently.
- `InternalDisplayDesktopConfig` reads Android's live
  `config_canInternalDisplayHostDesktops` resource for compatibility reports.
  It is deliberately diagnostic rather than a launch gate: this resource
  describes the framework's standard internal-display desktop path, while a
  vendor or shell path may still host MagicDesk on display 0 when it is false.
  The actual phone-desktop behavior is verified by the same self-test used for
  other display targets.
- Implementations live in `platform.android` and `platform.nubia`. Shared
  runtime code does not import either implementation; `PlatformDrivers` is the
  single composition point. ZTE-branded devices are not assumed to expose
  Nubia services and use the standard Android driver unless a dedicated,
  verified platform implementation is added.
- Exact tested fingerprints and their confirmed scope live in the declarative
  `assets/compatibility/firmware-profiles.json` catalog, not in driver code.
  Updating confidence therefore cannot change runtime selection or behavior.
  `PlatformCapabilitySnapshot` records stable capability IDs, observed state,
  component provider, provider evidence, and bounded detail. A failed optional
  probe becomes `broken` for that capability instead of aborting the report.
- The human-readable compatibility report and its schema-versioned JSON block
  are generated from the same snapshot. The optional extended vendor probe is
  explicit, read-only, bounded, and never scans user files or installed apps.
  Manual checklist observations are keyed by exact fingerprint and display
  kind, so an OTA cannot inherit a previous firmware's result.
- `NubiaPlatformDriver` composes the Nubia/REDMAGIC implementations of those
  contracts and supplies the firmware's additional exported launch targets
  and hardware runtime. Common projection, input, phone-UI, setup, and
  diagnostics code does not select Nubia services or settings through feature
  booleans. Hardware controls remain an explicit optional platform capability.
  `GenericAndroidPlatformDriver` provides the Android 15 baseline: phone,
  simulated, and direct sessions on already connected secondary displays,
  using the two shared required freeform/resizable settings. It does not own the
  system projection transport, and its phone-UI, cursor-observation, output-mode,
  and hardware integrations fail closed. Its diagnostics omit vendor probes.
- Platform and display are independent axes. A platform declares which
  display kinds it supports, while the display driver owns the lifecycle of
  one session type. Do not create platform-by-display combination classes.
- SoC display services are a third independent axis.
  `SocDisplayModeBackends` is their sole composition point. The optional
  Qualcomm `IDisplayConfig` implementation augments mode discovery and exact
  timing selection when Android's public mode list is incomplete; its absence
  is inert. Binder descriptors and transactions remain inside `soc.qualcomm`,
  while platform projection code consumes only `SocDisplayModeBackend` data.
- `DesktopDisplayTarget` is the immutable identity of the active display
  environment. `DesktopRuntimeBridge` retains that target as one value so a
  display ID and its transport cannot become separate, stale state.
- `DesktopCompatibilityPolicy` is the immutable selection of seven optional
  shared mechanisms: input-focus repair, stale caption
  refresh, Activity handoff mode/bounds repair, phone-task isolation during
  wired/wireless sessions, retained phone-task recovery, stale phone freeform
  Recents cleanup, and Recents routing
  to the leased phone HOME. `PlatformFeatures.compatibilityDefaults` supplies
  recommendations only; `MagicDeskSettings` stores independent user overrides.
  The Android baseline recommends focus repair enabled; firmware extensions
  may recommend additional options. An explicit user disable takes precedence.
  The Compatibility settings section is available on every platform. Enabling
  an option neither grants privileges nor guarantees framework support.
  `DesktopSessionController` resolves the selection;
  `DesktopHomeRoleLease.prepare` persists it before HOME activation.
  Repeated Open, settings refresh and host recreation reuse
  it. The typed observer configuration carries that selection to shell;
  helper policy suppliers read only this session snapshot, never preferences.
  Explicit close captures its recovery decision before releasing HOME. Display
  removal retains its own decision; deferred local cleanup persists that
  decision with its pending marker. New preferences cannot rewrite old cleanup.
  No extra observer, poller or worker is introduced. Input commit verification,
  owned-task parking and ordinary session cleanup remain unconditional.
- `DesktopRuntimeBridge` is only the stable process-local facade.
  `DesktopSessionRegistry` admits one `DesktopWorkspaceRuntime` per logical
  display, with its own session policy. The workspace runtime retains its immutable local
  snapshot and weak host reference. `DesktopUiGateway` serializes admission
  and host attachment and dispatches explicitly display-addressed UI commands;
  it has no duplicate desktop host reference. A queued UI action validates the
  captured host before execution instead of following a replacement Activity.
- `DesktopDisplayDriver` has four implementations: phone, wired, wireless,
  and simulated. A driver owns environment-specific activation, launch-area
  policy, phone-screen and touchpad availability, capture support, and display
  removal semantics. Wired and wireless drivers consume Android's existing
  physical display directly; neither owns the transport lifecycle.
- `DesktopDisplayDrivers` is the only registry for resolving those drivers.
  `DesktopOperations` serializes public session transitions and delegates
  the selected target to the registry.
- `DesktopOperations` is the action facade used by activities
  and shortcuts. `DesktopSessionTransitionCoordinator` owns activation,
  close, and caption transport sequencing; `SerializedDesktopOperationQueue` provides the
  single ordered executor shared with shell settings and input policy. The
  facade owns neither transition flags nor an executor. Platform projection
  and feature contracts are injected into the coordinator, so a close cannot
  re-enter `DesktopOperations` through a display driver.
- Desktop shortcut and panel commands enter through `MagicDeskRuntime`. The
  runtime service is the availability and ownership boundary;
  `DesktopRuntimeBridge` remains the lower-level gateway that dispatches a
  command to the currently registered host on the main thread. Self-tests may
  address that gateway directly when the gateway itself is the subject under
  test.
- `PhoneDisplayGuard` owns an independent serialized phone-power operation and
  CPU work lease. Control Panel uses a phone-only owner; Desktop's external
  controls may explicitly attach an additional application-protection lease to
  their display. Only that display's close releases its associated power guard.
  The common guard publishes state through the runtime, not a desktop Activity.
- `ExternalDisplayController` discovers dynamic display IDs and fixes geometry.
- `DisplayInputSession` owns input routing and the virtual phone pointer;
  `DesktopShortcutService` filters desktop shortcuts, and
  `HardwareKeyboardLayoutController` owns layout selection.
- `PhoneTouchpadController` starts and repairs the phone touchpad for an owned
  external target whose display driver permits it. The shared transport checks
  virtual-mouse and routing readiness before delivering input.
- `RedmagicHardwareController` owns capability probing, stock fan/pump policy,
  monitoring, and baseline restoration.
- `DesktopNotificationListenerService` owns Android notification-listener state;
  `DesktopNotificationMapper` isolates framework-to-UI conversion.

Repositories perform package, task, and document queries. View controllers do
not construct arbitrary shell commands. Platform controllers do not construct
desktop panels. Keep this split when adding vendor-specific behavior.

## Physical Input

Hardware events travel through Android's physical devices and their explicit
display associations. Android owns repeat, modifiers, cursor motion,
acceleration, hover, dragging, secondary-button semantics and multitouch.
External touchscreens use the same `DisplayInputRoutingSession` and reversible
`InputRoutingLease` as keyboards and mice, including hotplug and cleanup.
Discovery joins EventHub nodes to InputReader's `TOUCH_SCREEN` classification
by EventHub ID, not name or Android device ID. The EventHub `TOUCH` class alone
also includes touchpads. Built-in panels, virtual touch devices and unclassified
touch nodes are not selected. A touchscreen disabled by a missing viewport is
still eligible for routing. The existing bounded input snapshot supplies this
inventory and the report's touchscreen count; no per-event forwarding or new
polling is involved. Hardware/firmware detection and usable input viewports
remain prerequisites, and firmware-static routes are not overwritten.

`DesktopShortcutService` consumes only MagicDesk shortcuts; each physical
keyboard has its own `KeyboardShortcutStateMachine`. Consumed key-down/up
pairs remain balanced across modifier release and repeated keys. Unplugging a
keyboard cancels its pending Alt+Tab selection.

Holding Win after Left/Right selects a snap sequence: Up/Down steps between
the top quarter, side half, and bottom quarter, stopping at either end.
Releasing Win, another shortcut, conflicting modifiers, or resetting the input
target ends the sequence. Standalone Win+Up/Down retain fullscreen/restore
semantics. The sequence is local to each keyboard; all resulting arrangements
use `DesktopTaskController`, also shared by MCP and CLI `arrange_task`.

`Ctrl+Space` uses `HardwareKeyboardLayoutController` to select the next
configured Android layout for connected physical keyboards and update the
taskbar label. No virtual keyboard identities or copied key streams are involved.
Initial synchronization and IME-change notifications resolve the current Android
subtype; saved taskbar values are output only. Switching also starts from that
live selection, skipping duplicate layouts exposed by different IMEs within a
bounded enumeration. An unresolved current subtype is an error, not a reason to
select a saved descriptor or the first language in the list. This adds no
periodic input query.

The taskbar keyboard menu reads a `HardwareKeyboardLayouts` snapshot through the
same privileged adapter without applying overrides or changing the IME. It shows
configured hardware layouts separately from enabled on-screen keyboards. An
explicit descriptor selection revalidates live choices and uses the same bounded
Android subtype cycle as Ctrl+Space; it never reports a different layout as the
requested one. Menu loading is generation-scoped and cannot reopen a dismissed
panel. No new service, root request or periodic enumeration is involved.

The phone touchpad emits relative movement and native buttons through its
session-owned virtual mouse. Its input location is independently associated
with the desktop. The shared `pointer_speed` setting is applied by Android,
not multiplied a second time in Java. Hardware touchpads recognized as native
touchpads use Android's separate touchpad-speed setting.

## Phone Screen And Touch Panel

MagicDesk uses the shell DisplayManager `power-off 0` contract. A heartbeat-owned
`PhoneDisplayGuard` probes and uses the platform's matching restore operation
(`power-on` on Android 15 or `power-reset` on Android 16) after normal or
abnormal teardown.

`DisplayPowerCommands` owns command discovery for both the guard and shell
diagnostics. It reads help and, when a command is omitted, probes argument
validation without a display ID. Diagnostics distinguish declared commands,
missing commands and probe errors; discovery never changes display power.
The guard publishes its process-local screen state through
`MagicDeskRuntime.refreshPlatformState`; UI command completions and runtime
notifications refresh the controls without a settings observer.

While display 0 is off, RedMagic's independent `cfreezer` applies a separate
screen-off policy. The inspected firmware exempts the selected HOME package;
that exemption does not extend to other desktop applications and ends when
Close returns HOME, before restoring phone power. See
`docs/nubia-vendor-audit.md` for the firmware evidence and verification scope.
The phone-power guard acquires shared background-work protection before turning
the phone display off, and releases it after restoration. `ShellBackgroundWork`
owns task observation and freezer sessions for MagicDesk and
application UIDs, so HOME release does not remove host protection prematurely.
Overlapping automation work retains its own claims. No persistent freezer whitelist
is installed. The optional Nubia phone-UI and background-work components are
detected from the existing service/method; diagnostics remain read-only.

`MagicDeskTouchpadActivity` is the common phone-side input panel for external
desktops. It remains an ordinary display-0 Activity and can be opened from the
phone notification or desktop controls. Its relative-motion path uses the
shared native mouse relay described above and does not require a firmware
absolute-position API.

Pointer speed uses Android's standard `Settings.System.pointer_speed` range and
is observed for changes made outside MagicDesk.

## Hardware Controls

Hardware monitoring reads firmware-exposed thermal values. Fan and liquid-pump
actions use the stock `NBFan` settings policy; bypass charging uses the stock
global setting observed by the vendor service. MagicDesk captures each original
value before its first write and restores only state it owns on System, exit,
or interrupted-session recovery.

The runtime takes one initial hardware snapshot. Repeated thermal and vendor
state reads run only while Quick controls is visible; closing or switching
away from that panel cancels the polling task without disabling controls or
discarding owned fan and pump state.

The main application does not write fan or pump sysfs nodes and does not claim
RPM data unavailable to shell UID 2000. Controls are capability-probed because
setting names and vendor services can change across firmware.

## Device Setup And Recovery

Device Setup requires Android 15+, a selected compatible platform driver, and
a live, authorized shell UserService. Every platform audits the two required
Android settings:

```text
Settings.Global enable_freeform_support = 1
Settings.Global force_resizable_activities = 1
```

`DeviceSetupManager` owns these common requirements; `PlatformWindowingDriver`
adds only optional firmware-specific configuration. A session owns its temporary
display windowing default and restores that separately.

On platforms that expose an explicit device-restriction writer, the corresponding
Device Setup value is clickable. `DesktopDecorationSettings` verifies the
confirmed old value, writes only this property through the platform, verifies
readback, and then requests one SystemUI process restart through the current
privileged service. Desktop and retained HOME ownership must be absent. Rejected
property writes never restart SystemUI. The operation does not change required
setup, rounded corners, the external-display global setting or reboot markers.
`SystemUiRestart` targets the observed process with `am crash`, records its
attempt before dispatch, and bounds recovery observation by a replacement PID
and a responsive WMShell endpoint. Missing recovery is an explicit failure, not
a retry or permission to reboot Android. A single-flight guard and persisted
one-minute attempt interval prevent repeated UI requests from creating a crash
loop; this is rate limiting, not a settling delay. Device Setup refreshes the
shared capability audit after the operation, including partial failures.

`SystemDesktopModeSetting` owns the optional Android global setting
`force_desktop_mode_on_external_displays`, exposed in **Settings > Android system**.
Android remains its only value store. The UI reads current state, confirms the
navigation-bar side effect, then writes and verifies the
value off the UI thread. Changes require shell access and no active/preparing
desktop or retained HOME lease. There is no session override, saved preference
copy, startup write, or required-setup reboot marker. The UI advises reconnecting
the external display; some firmware may require a restart. Neither is a startup
gate in MagicDesk. Close Desktop leaves the value unchanged;
Restore defaults removes the override. The flag affects external HOME, system
decorations and input policy, but does not prove correct physical-input routing.
Physical-input routing is owned by the shared Android input session.

`DesktopCompatibilitySettings.resetDefaults` coordinates the Settings reset:
verify the Android global-setting reset first, then clear compatibility
overrides through `MagicDeskSettings` and `DesktopStateStore`. Defaults remain
owned by the selected platform, not copied into user preferences. The combined
reset uses the same no-session requirement as the global switch and reports
partial persistence failures explicitly; it never resets required provisioning,
application presentation profiles, or other settings.

The Nubia/REDMAGIC platform additionally audits and recommends:

```text
persist.wm.debug.desktop_mode_enforce_device_restrictions = false
persist.wm.debug.desktop_use_rounded_corners = false
```

`DesktopWindowingProperties` owns these AOSP keys, boolean/absent validation,
readback and bounded failure diagnostics. A platform writer supplies only the
write transport; property semantics do not belong to a vendor. Reads use `getprop`
without privilege delegation. A successful command exit is not proof of a write:
Binder tools can return a permission exception in their reply with exit code zero.

Shell UID 2000 owns the global settings. On supported Nubia/REDMAGIC firmware,
`NubiaWindowingDriver` writes the two properties through `redmagic.app.manager`
using the already-authorized `ShellAccess` command service, whose common runner
bounds command duration and output. Newer firmware checks `WRITE_SECURE_SETTINGS`
before clearing Binder identity. No app-UID write, root retry or service startup
is attempted. Generic
Android never reads those properties as setup requirements or writes them.
Neither property is a Desktop prerequisite. Setup attempts them independently;
failed writes and resets produce diagnostic warnings without blocking the
remaining operation or Desktop entry. The driver reports verified changes,
not predicted writes, so a denied optional write alone cannot require reboot.
Diagnostics preserves the raw values and does not equate an empty value with
`false` or with unavailable window support. The desktop self-test verifies
actual window behavior.

Normal first-run UI exposes only the next required user action: connect and
authorize the selected privilege backend, prepare the device, restart, or start
MagicDesk. Display selection, individual setting values, firmware identity,
Diagnostics, and restoration remain in the manually opened **Device setup**
screen.

Desktop panels and dialogs use ordinary application windows and require no
display-over-other-apps permission. Their session-owned chrome host is excluded
from Recents and all MagicDesk application-task policy.

The boot ID marks configuration that still requires reboot. Required Android
settings are marked before their command because it can partially succeed;
optional properties are marked only after a verified change. MagicDesk never
reboots automatically and has no boot receiver. A successful audit after boot
enters the control panel without flashing setup UI.

**Restore defaults** is available independently of setup history. It stops the
runtime, normalizes stale phone desktop tasks, removes the three global
desktop-windowing overrides, attempts to clear the two allowlisted persistent
properties, and resets primary-display size/density/scaling overrides. Removing overrides
lets the firmware supply its defaults and remains usable after MagicDesk has
been uninstalled and installed again. Diagnostics and background audits never
authorize a runtime session or start services.
