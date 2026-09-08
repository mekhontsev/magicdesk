# Nubia Vendor Interface Audit

This is an inventory of the mechanisms used by MagicDesk, checked against
the current source on 2026-09-08. It covers shared compatibility
policies, active vendor integrations, their owners, and known limitations.
Test runs and their results belong in compatibility reports, not this inventory.

Android 15 / API 35 is the minimum baseline. Shared Android mechanisms include
hidden framework APIs used through the authorized Shizuku shell UserService
(UID 2000); they are not necessarily public application SDK APIs. Linux
uinput is also the shared phone-pointer transport. Conversely, a method in an `android.*`
class, a Settings key, or an ordinary Intent can still have vendor-defined
semantics.

Keep three questions separate: what behavior the desktop needs, what mechanism
implements it, and what enables it. A successful run with a compatibility
option enabled does not prove that the option is necessary. Source inspection
of one firmware does not establish behavior on every Nubia device.

## Firmware Scope

The maintainer device is RedMagic 11 Pro, `NX809J`, Android 16 / API 36,
build `20260204.221845`:

`REDMAGIC/NX809J-EEA/NX809J:16/BQ2A.250705.001-BP2A.250605.031.A3/20260204.221845:user/release-keys`

Exact fingerprints and confirmed scopes live in
[firmware-profiles.json](../app/src/main/assets/compatibility/firmware-profiles.json).
These profiles are diagnostic metadata, not executable device-selection rules.

## Selection And Ownership

`PlatformDrivers` composes the common Android baseline with focused extensions.
`NubiaFirmwareDetector` selects the complete Nubia extension for a matching
hardware family with `redmagic.app.manager` or an official firmware fingerprint.
On hybrid firmware it can select independently detected optional components.
An extension match does not prove that each optional control works.

`NubiaPlatformDriver` contributes compatibility defaults and the implementations
under `platform/nubia`. Display lifecycle remains in the shared display drivers;
Qualcomm output control belongs to `SocDisplayModeBackend`; task/window policy
remains behind the shared transition gateway. Android-release semantics belong
to `FrameworkRuntime` and its adapters.

### Shared Compatibility Policies

The six options are available on every platform in Settings' Compatibility
section. An unset preference follows the platform recommendation. A complete
Nubia extension recommends all six enabled; Standard Android recommends them
disabled. Partial extension selection supplies only the defaults associated
with its detected components.

The HOME lease captures the policy for each session. A settings edit applies
to the next session, not to live window operations. Diagnostics
distinguishes the active selection from the next-session selection and reports
actual input-routing and shortcut-filter readiness separately.

| Option | Nubia component supplying the default | Shared implementation and scope |
| --- | --- | --- |
| `FOCUS_REPAIR` | `WINDOWING` | Callback-driven focus reconciliation and post-command relayout/hierarchy repair. Task/input verification remains active without repair. |
| `CAPTION_REFRESH` | `WINDOWING` | Refresh stale application-client caption insets through framework window transactions. |
| `PHONE_TASK_ISOLATION` | `WINDOWING` | Phone-side launch/migration interception and freeform normalization during wired/wireless sessions. |
| `PHONE_TASK_RECOVERY` | `WINDOWING` | Reconcile phone task modes and retained WMShell desktop membership around session setup, cleanup, and display loss. |
| `STALE_RECENTS_CLEANUP` | `WINDOWING` | Remove matching orphaned phone freeform Recents entries during phone desktop observation. |
| `RECENTS_TO_HOME` | `PHONE_UI` | Route the system Recents Activity request to MagicDesk HOME while its session is active. |

These implementations do not depend on private Nubia input or window-control
APIs. Their default selection is firmware policy; portability alone does not
justify enabling every correction on every platform.

### Focus And Caption Repair

`ShellDesktopFocusController` retains task and input-window commit verification
with either value of `FOCUS_REPAIR`. Missing observations remain unknown and
an unconfirmed target fails the normal event-driven wait. Final HOME
focusability is ordinary workspace ownership, not optional repair.

Repair covers incomplete focus handoffs, including return to desktop HOME
after demoting the last fullscreen task. It is not a prerequisite for every
ordinary application switch.

`CAPTION_REFRESH` addresses an application retaining a caption inset after
WMShell has removed the server-side source. `TaskRepository`, transition
commands, and `ShellTaskObserver` use shared caption capture/refresh operations;
`FrameworkTaskObservationSource` supplies conditional observation through the
existing task observer. `FrameworkWindowingCompat` owns Android 15 semantics.
The immediate transition and observer-triggered late-relayout paths preserve
the application client. Newer native caption exclusion alone does not establish
that client refresh is unnecessary.

This repair is independent of the compositor privacy filter described below.
One changes application insets; the other changes external layer visibility.

### Phone Recents And Task Isolation

The inspected MiFavor Launcher builds `DesktopTaskView` task containers with
a null title view, while `TaskView.setThumbnailOrientation()` asserts that the
view is non-null. Display-0 freeform tasks grouped through WMShell's desktop
repository can reach this crash.

MagicDesk's shared HOME lifecycle presents `PhoneHomeActivity` on display 0
during external sessions, and `PhoneDesktopHomeActivity` during phone desktop.
HOME ownership by itself does not normalize other tasks or clear WMShell
repository membership.

When selected, `ShellExternalTaskMigrationGuard` intercepts matching
`ACTION_MAIN` / `CATEGORY_LAUNCHER` requests using task/component matching and
Intent flags, and transfers the existing task to phone fullscreen. Its
observation path normalizes all observed display-0 freeform tasks during a
wired/wireless session, not just tasks cached from the desktop. It is disabled
for phone and simulated sessions. This broad policy is distinct from the
exact-task transfer already performed by `PhoneAppLauncher`. Notification
`PendingIntent` launches are not necessarily MAIN/LAUNCHER requests and cannot
be assumed to pass through this interceptor.

`PhoneDesktopTaskRecovery` reconciles retained desktop membership as well as
window modes. It can take a retained task through WMShell desktop entry and
native fullscreen exit to clear that membership. `ShellFreeformTaskCleanup`
separately targets matching orphaned phone Recents entries. These are broader
compatibility operations, not just cleanup of MagicDesk-owned surfaces.

`ShellPhoneOverviewRouter` resolves Android's `config_recentsComponentName`
and cancels that exact launch only while the app-side callback confirms an
active HOME lease. During an external session, the routed HOME selects Recent
in phone Start; during phone desktop it presents the desktop workspace.
Other navigation requests are left alone. Release of HOME ends routing before
task teardown completes.

Routing startup is independent of the main activity-start/task observer.
Missing Recents/HOME capabilities or preparation failure leave routing disabled
and report `TASK-OBSERVER-RUNTIME-001`, allowing ordinary system Recents through.
There is no background retry.

### Shared Display Preparation

`DisplayWindowingSession` prepares secondary displays before HOME activation
and restores changes after teardown or failed startup. Display 0 is excluded.
`FrameworkRuntime.displayWindowing()` owns the Android 15+ IWindowManager
boundary; durable ownership uses stable display identity and existing
display-added/shell-ready callbacks for reconnect recovery.

This is common session infrastructure, not a Nubia compatibility toggle.
Android exposes the effective default rather than the raw override, so
restoration uses the previous effective mode and preserves a different value
selected externally. See [Architecture](architecture.md) and
[Fullscreen Transitions](fullscreen-transitions.md) for the lifecycle contract.

## Active Vendor Interfaces

The access column distinguishes ordinary app-UID operations from work performed
by the authorized shell UserService. Capability detection and restoration
remain mandatory even for app-accessible methods.

| Interface | Access used by MagicDesk | Production owner and purpose |
| --- | --- | --- |
| `redmagic.app.manager` property methods | Ordinary app UID | `NubiaDesktopPropertyManager`: two allowlisted desktop setup properties. |
| `edid_modes` / `hpd` | Shell, only when accessible | `NubiaHdmiModeController`: advertised physical HDMI timing selection. |
| `IInputManager.getMousePosition` | Shell | `NubiaDesktopPointerDriver`: read-only global cursor observation; display identity is unknown. |
| `SurfaceControl.setSFOption(1100/1102, ...)` | App-UID helper | `NubiaCaptionVisibilityManager`: lifecycle-owned external privacy/caption visibility. |
| `cfreezer.noteCpuFreezerUidWorking` | Shell | `PhoneDisplayGuardCommand`: transient screen-off protection for other desktop applications. |
| Stock fan/pump Settings keys | Shell writes and readback | `RedmagicHardwareController`: stock cooling policy and restoration. |
| `charge_separation_switch` | Settings observation and shell writes | `ChargeSeparationController`: stock bypass-charging control. |
| Theme properties and wallpaper cache | Read-only fallback | `NubiaWallpaperDriver`: obtain the selected theme wallpaper when the normal source has no file. |
| `MediaRecorder` source `80` | Capability probe and shared recording path | `NubiaAudioCaptureDriver`: optional internal audio capture. |
| SmartCast and Mora components | Explicit ordinary Intents | `WirelessDisplayController` and `RedmagicEntryPointCatalog`: optional vendor UI/catalog targets. |

## Desktop Provisioning

`NubiaWindowingDriver` uses `NubiaDesktopPropertyManager` for
`getSystemProperties(String, String)` and
`setSystemProperties(String, String)` on `RedMagicAppManagerService`.
The inspected service clears calling identity and directly accesses
`SystemProperties`, without a key allowlist or permission check.

MagicDesk confines that capability to:

- `persist.wm.debug.desktop_mode_enforce_device_restrictions`
- `persist.wm.debug.desktop_use_rounded_corners`

Setup writes `false`; Restore defaults clears both overrides. The wrapper
validates boolean/absent values and verifies writes. Shared setup also enables
`enable_freeform_support` and `force_resizable_activities` through shell access
on every platform. Firmware readiness includes these provisioning/reboot
requirements. Android's optional `force_desktop_mode_on_external_displays` is
controlled separately from ordinary MagicDesk Settings; it is not part of
firmware readiness or a Nubia API.

This grants configuration access, not arbitrary task ownership; WMShell and
window transactions still use the authorized shell runtime. The unrestricted
vendor setter must not become a generic property editor or automation surface.

## Input

Physical keyboards and mice use the shared Android display-routing session.
`FrameworkInputRoutingApi` binds input locations to display unique IDs; this
does not depend on Nubia APIs or compatibility defaults. The phone touchpad
has one separately routed virtual relative mouse. A key-only Accessibility
filter provides desktop shortcuts independently of the selected IME.

`NubiaDesktopPointerController` resolves `IInputManager.getMousePosition(Point)`
for read-only diagnostics. Its cached controller can belong to display 0, and
the API does not expose its display identity. `PointerPosition` therefore keeps
this observation unscoped; the report and MCP never assign it the requested
desktop's display ID. `positionAvailable` remains false for such an observation,
while the separate raw observation preserves its coordinates.

Coordinate hover/click automation uses shared display-targeted Android injection.
Ordinary physical and phone-touchpad movement uses the relative transport.

### System Routing And Native Motion

Explicit device associations and the system's default mouse display are
different routing paths. The inspected firmware's `InputManagerCallback`
falls back to display 0 when `mForceDesktopModeOnExternalDisplays` is false;
with that flag enabled it searches for an active freeform/external display.
That fallback also exists in
[AOSP Android 15](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/wm/InputManagerCallback.java).
Setting a display's default windowing mode to freeform does not itself enable
this global pointer-routing flag. MagicDesk explicitly associates its virtual
outputs instead. A cursor absent from the external screen is therefore not
by itself evidence of a vendor rendering defect.

The firmware's `WindowManagerService.SettingsObserver` observes this global
setting while Android is running. `InputManagerService.setDisplayViewportsInternal`
passes a freshly selected pointer display to native input after viewport changes,
including external-display reconnection. A reboot is not inherently required for
that path. Native `PointerChoreographer` gives an explicit device display priority
over the default; its separate `connectedDisplaysCursorEnabled` feature can also
replace the legacy default-display selection. These are distinct inputs to
routing, not interchangeable names for the developer setting.

The common relative input path still reaches firmware-native pointer code.
In the inspected `/system/lib64/libinputservice.so` (SHA-256
`da8eef8027af0542b2758da7e2f24164cffa446d63e2e781c3439c98a2f9886a`),
`MouseCursorController::move` at `0x15868` includes vendor sensitivity scaling
and an optional circular movement constraint absent from the corresponding
[AOSP Android 16 implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/libs/input/MouseCursorController.cpp).
The constraint applies when the mode field is `1`; it limits the new position
to a stored center and radius before viewport clipping. It affects coordinates,
not just cursor visibility, and operates below MagicDesk's virtual transport.

The inspected constructor at `0x15668` does not initialize the sensitivity,
mode, or radius fields at offsets `0x15c`, `0x160`, and `0x164`; the allocation
and enclosing constructors do not zero those fields either. This is a native
initialization risk, not confirmation that a particular incident used those
values. Association, viewport validity, controller position and constraint
state must be distinguished before attributing missing or stuck input to it.

## Physical Output And Caption Control

`NubiaHdmiModeController` writes an advertised timing to
`/sys/kernel/lcd_enhance/edid_modes` and pulses
`/sys/kernel/lcd_enhance/hpd`, restoring HPD even on failure. Shared output
preparation waits for the physical mode and resolves the connected display
before attaching the desktop.

Node accessibility is separate from timing support. Shell UID 2000 is denied
on the maintainer firmware and the community Z80 profile. A stable permission
denial is cached and appears as a diagnostic observation. Fallback selection
uses the independently owned Qualcomm `SocDisplayModeBackend`, then Android
display modes. Qualcomm Binder behavior is SoC-specific, not a Nubia API.
Production does not require root. See [Output Timing](architecture.md#output-timing).

The vendor compositor uses option `1100` for wireless privacy and `1102` for
wired privacy. On the inspected firmware, value `1` hides external layers
whose names include `Task=`, including native captions; `0` reveals them.
These are vendor operations despite their location on `SurfaceControl`.

`NubiaCaptionVisibilityManager` invokes them through
`SurfaceFlingerOptionCommand`, an app-UID helper. There is no corresponding
SurfaceFlinger getter. The exported `cn.nubia.touping.TouPingProvider` supplies
separate preferences through `CALL_4_KEY12` (`CALL_4`, wireless) and
`CALL_5_KEY3` (`CALL_5`, wired). The manager journals transport ownership,
exposes layers for the active physical transport, and restores its saved
preference on transport change, teardown, or interrupted-session recovery.
Simulated sessions do not acquire that physical-transport override.

## Phone Screen Power

`NubiaPhoneUiDriver` delegates to `PhoneDisplayGuard` and its shell helper.
Power control uses Android display commands: `cmd display power-off 0` and
the restore operation discovered by shared `DisplayPowerCommands`.
The verified firmware supports `power-reset`; the resolver also supports
`power-on` when that is the available restore operation. Declaration probes
do not change power or UID protection.

The helper's heartbeat/watchdog owns restoration if MagicDesk, Shizuku, or
the session ends. It is active screen-off ownership, not idle desktop polling.
An unexpected helper failure does not automatically start another screen-off
request. Normal Close hands back HOME, restores the phone screen, then releases
input and tears down tasks/display. A restoration error is reported without
aborting remaining cleanup, including when the physical monitor stays connected.

The vendor-specific part is `cfreezer`,
`com.zte.performance.cfreezer.ICpuFreezerManager`, and
`noteCpuFreezerUidWorking(uid, working, "service")`.
`NubiaCpuFreezerWorkingState` uses its transient working-state protocol from
shell UID 2000. During screen-off, the existing heartbeat protects the union
of desktop application UIDs observed in task snapshots. A temporarily absent
task does not lose protection. MagicDesk's own UID is excluded, including when
its windows appear in those snapshots. Release restores phone power and clears
the accumulated protection; the firmware expires unrefreshed working state
if explicit cleanup cannot run.

The 2026-09-08 inspection of NX809J build `20260204.221845`
(`/system/framework/services.jar` SHA-256
`e80906b720ecc8d117c640ad916706217e08ed4853ae9879c7b4f904e89d95a8`)
found selected-HOME exemptions in `CpuFreezerManagerServiceV2`.
`CpuFreezerUtils.getLauncherPackageName` and `AppInfoUtils.isCurrentLauncher`
resolve MAIN/HOME; the cached identity refreshes on preferred-activity changes
and screen-off. MagicDesk relies on this exemption for its own package.
HOME does not protect other desktop application UIDs. The ordinary screen-state
check uses display 0; top/float-window exemptions in `CommonScreenChecker`
depend on the screen-on branch. External visibility alone therefore does not
establish protection for other apps.

## Stock Cooling Policy

`RedmagicHardwareController`, `RedmagicHardwareSettings`, and
`RedmagicSettingsNamespace` request stock fan/pump policy rather than writing
cooling nodes. On the verified firmware, `cn.nubia.fan` observes settings
and performs the protected hardware writes.

| Setting | Values used by MagicDesk |
| --- | --- |
| `fan_state_of_manual` | `0` off, `1` enabled, `-100`/`100` stock automatic sentinels |
| `fan_state_of_mode` | `1` intelligent, `0` extreme |
| `liquid_cooling_main_switch` | `0` off, `1` enabled, `-100`/`100` stock automatic sentinels |
| `liquid_cooling_flow_speed_mode` | `low`, `mid`, `fast` |

The verified control namespace is Settings.System; namespace discovery checks
System and Global rather than treating the selected provider as hardware proof.
`Settings.Global.game_fan_off_on` and
`Settings.System.liquid_cooling_off_on` report effective states separately.

Restoring an active manual request directly to an automatic sentinel can leave
manual policy active. Restoration first writes the main/manual setting to
`0`, then restores mode/flow and the original main/manual value. Commands use
fixed keys, validate readback, and retain ownership if restoration fails.

Diagnostics reads the settings through a bounded read-only shell snapshot using
the shell identity. Control availability and effective-state availability are
separate results. `NubiaHardwareNodes` and thermal readings feed
`RedmagicHardwareSnapshot` and the hardware panel; not every selected Nubia
device has these controls. Runtime monitoring and restoration belong to the
extension's existing hardware lifecycle.

## Optional Integrations

- **Bypass charging:** `ChargeSeparationController` detects
  `cn.zte.chargeseparation` and controls
  `Settings.Global.charge_separation_switch`. Enabling requires external power
  and sufficient battery charge; state uses observers and write readback.
- **Wallpaper:** `NubiaWallpaperDriver.openCurrentFallback` reads the selected
  theme through `persist.sys.theme_name`, `ro.vendor.build.def_theme_name`,
  or `ro.build.def_theme_name`, then opens `wallpaper1.jpg` or `wallpaper1.png`
  under `/data/resource-cache/cache/<theme>/wallpaper/`. Theme names are
  validated, access is read-only, and the fallback is used only when the normal
  wallpaper source has no file.
- **Internal audio:** `InternalAudioSourceCapability` probes vendor
  `MediaRecorder` source `80`; `NubiaAudioCaptureDriver` supplies it to the
  shared recorder. A declared source is not proof of successful recording;
  an unavailable declaration API remains unknown.
- **Wireless connection UI:** `WirelessDisplayController` launches SmartCast
  `cn.nubia.touping.HomeActivity` when available.
- **Mora:** `RedmagicEntryPointCatalog` declares
  `cn.nubia.redmagickyi.guide.activity.RedmagicStartActivity` with
  `intent.action.redmagickyi.main` as an optional launch target.

## Diagnostics And Debug Probe

`NubiaPlatformDiagnostics` and `NubiaCapabilityProbe` report selected
interfaces, settings, physical output and hardware state. A discovered method
or node is not by itself an enabled production control or a passed workflow.

`NubiaVendorProbeInstrumentation` is a debug-only explicit experiment.
Its default run is read-only apart from attempting a same-value Settings.Global
write that is expected to fail before mutation:

```sh
am instrument -w --user 0 \
  io.github.mekhontsev.magicdesk/.platform.nubia.NubiaVendorProbeInstrumentation
```

The optional mutation test requires `-e allow_mutation true`. It tests only
the allowlisted eligibility property, restores its original value in
`finally`, verifies restoration, and exercises wired/wireless caption
visibility through the lifecycle-owned production wrapper. It must not become
a generic mutation tool.
