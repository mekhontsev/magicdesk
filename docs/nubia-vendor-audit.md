# Nubia Vendor Interface Audit

This document records RedMagic firmware behavior that was verified separately
from MagicDesk's public feature contract. An available Binder transaction is
not automatically suitable for production use. Every integration still needs
a capability probe, a bounded input surface, explicit lifecycle ownership, and
a recovery path.

## Verified Device

- Model: `NX809J`
- Android: 16 / API 36
- Firmware build: `20260204.221845`
- Fingerprint:
  `REDMAGIC/NX809J-EEA/NX809J:16/BQ2A.250705.001-BP2A.250605.031.A3/20260204.221845:user/release-keys`

The **Ordinary app access** column describes calls available from MagicDesk's
application UID in the `u:r:untrusted_app:s0` SELinux domain. Production
privileged work runs through the authorized shell UserService; the distinction
keeps app-accessible vendor APIs from becoming a generic privileged surface.

## Community-Tested Firmware

- Model: `NX809J` / `NX809J-UN` variant
- Android: 16 / API 36
- Firmware build: `20260625.022314`
- Fingerprint:
  `REDMAGIC/NX809J-UN/NX809J:16/BQ2A.250705.001-BP2A.250605.031.A3/20260625.022314:user/release-keys`

The confirmed scope includes the required windowing configuration, shell UID
2000, task APIs, WMShell passthrough, relevant Nubia packages and display
signatures, desktop startup, external sizing, task recovery, Mora discovery,
output-mode selection, and external-display recording. This is a
community compatibility result, not the complete maintainer interface matrix.

## Confirmed Interfaces

| Interface | Ordinary app access | Finding | Production decision |
| --- | --- | --- | --- |
| `redmagic.app.manager` | Read and write | Its Binder accepts arbitrary system-property names without a permission check or key allowlist. | Production setup uses a closed two-property enum with boolean validation and read-after-write verification; never expose a generic property editor. |
| `IDisplayManager` Nubia extensions | Read and command | Display state and `setCmdToDisplay` calls are accepted from the app UID. | Production uses only the physical-output refresh command; Android's existing display remains the desktop target. |
| `IInputManager` Nubia mouse extensions | Shell read and command verified | `getMousePosition`, `setMousePosition`, and `sendMouseCmd` expose the firmware cursor viewport used by wired and wireless projection. | Production resolves the methods inside the Shizuku UserService and combines absolute position updates with display-targeted events from MagicDesk's virtual pointer. |
| `IDisplayManager` text-input extension | Shell command verified | `getFocusMirrorWindow` returns the currently focused projected window. | The focused window is retained only for an explicit software-keyboard session. |
| `IDisplayMirrorWindow` | Shell command verified | The focused window accepts composing text, committed text, deletion, and key events. | A bounded phone-side `InputConnection` forwards standard IME operations without selecting or embedding an IME. |
| `SurfaceControl.setSFOption(1100/1102, ...)` | Write verified | The app UID can change wireless/wired privacy and caption visibility. No corresponding SurfaceFlinger getter was found. | Shizuku uses transport-aware lifecycle ownership and restores the separate preferences reported by Nubia's exported projection provider. |
| `ZteScreenRefreshRate` | Binder accepted | The implementation selects `DisplayControl.getPhysicalDisplayIds()[0]`. | Do not present it as external-monitor refresh control. |
| `ColorfulLightService` | Binder discoverable; methods have no local permission check | It can preview and apply RedMagic lighting scenes. | Out of scope: it duplicates device settings and mutates unrelated hardware. |
| `VendorPowerManagerService` | Binder discoverable | The interface contains no callable methods. | No use. |
| `zte_backlight` | Read accepted | Current nits and normalized backlight are readable; setters also exist. | Use the stock RedMagic phone-screen controller instead of raw brightness writes. |

## Desktop Provisioning

`RedMagicAppManagerService` implements only these operations:

- `openScreenOffTP(boolean)`
- `setSystemProperties(String, String)`
- `getSystemProperties(String, String)`

The property methods clear the calling identity and directly call
`SystemProperties`. On the verified firmware, an untrusted MagicDesk process
temporarily changed
`persist.wm.debug.desktop_mode_enforce_device_restrictions`, read the changed
value, restored the original value in `finally`, and verified the restoration.

MagicDesk uses this to remove the clean-Shizuku setup gap:

1. Shizuku UID 2000 writes `enable_freeform_support` and
   `force_resizable_activities`.
2. The ordinary MagicDesk UID writes only the two reviewed
   `persist.wm.debug.desktop_*` keys through the vendor service.
3. Android is rebooted so WMShell and ActivityTaskManager rebuild from the
   resulting configuration.

The production wrapper accepts enum-like properties rather than caller-provided
keys, permits only boolean/absent values, and verifies every write. Setup writes
`false`; **Restore defaults** clears both persistent overrides so firmware
defaults apply. This property path provisions WMShell but does not provide
exact task, input, or display ownership; those operations require shell access.

The unrestricted vendor setter is a firmware security weakness. MagicDesk
must not turn it into a general-purpose command, exported component, intent
extra, diagnostics field, or user-editable text box.

## Desktop Task-Control Boundary

WMShell registers
`com.android.wm.shell.desktopmode.IDesktopMode` through `ShellController`, but
does not publish it through Android's service manager. SystemUI passes the
Binder to MiFavor Quickstep in the initialization bundle delivered to
`TouchInteractionService`; that exported service is protected by
`android.permission.STATUS_BAR_SERVICE`. Every remote desktop operation then
calls `enforceCallingPermission(android.permission.MANAGE_ACTIVITY_TASKS)`,
including task conversion, showing desktop apps, and launch transitions.

An ordinary app-UID launch with explicit bounds and
`windowingMode=freeform` is accepted without a `SecurityException` but
normalized to fullscreen; Android retains the rectangle only as
`mLastNonFullscreenBounds`. Provisioning desktop properties therefore does not
give an ordinary application native desktop task control.

The firmware also contains the older Nubia `WindowReply` path. An intent
identifier ending in `_WindowReply` selects that policy, but support is
filtered through `/system/etc/zte_windowReply_control.xml`. Its force-support
list explains why selected applications such as Chrome, Gmail, and Telegram
can use Nubia floating windows while an arbitrary resizable application may
not. MagicDesk does not use `WindowReply` as a fallback because it is a vendor
allowlist mechanism, not a general desktop contract.

## Phone Recents Defect

MiFavor Launcher combines two incompatible Launcher3 assumptions. Its
`DesktopTaskView.bind()` constructs `TaskContainer` objects with a null
`titleView`, while `TaskView.setThumbnailOrientation()` immediately applies a
Kotlin non-null assertion to `getTitleView()`. SystemUI places every display-0
freeform task in `DesktopUserRepositories`, and `RecentTasksController` groups
those tasks into the `DesktopTaskView` that reaches this crash.

Task bounds, affinity, `excludeFromRecents`, and repository cleanup after task
removal do not prevent the live-task crash. MagicDesk avoids coupling desktop
lifecycle to that launcher: it temporarily owns Android's HOME role for every
desktop session. External sessions present `PhoneHomeActivity` on display 0;
phone sessions make `PhoneDesktopHomeActivity` the primary HOME surface. The
previous HOME holder is restored before task cleanup, which then removes any
live or retained display-0 freeform state without extending HOME ownership.

## Physical Output And Caption Control

The Nubia `IDisplayManager` additions have no local permission checks for:

- `setCmdToDisplay`
- `getFocusMirrorWindow`
- `requestInputMethodChange`

`DisplayMirrorCtrl` command 10 temporarily allows the physical HDMI mode to be
refreshed after a timing change. MagicDesk uses only this command. It does not
invoke the vendor commands that create, switch, or destroy projection modes.

The firmware uses SurfaceFlinger option `1100` for wireless privacy and `1102`
for wired privacy. Value `1` hides external layers whose names include `Task=`,
including native WMShell captions; value `0` reveals them. Ordinary app-process
invocations of these options succeed, but no SurfaceFlinger getter was found.
The exported `cn.nubia.touping.TouPingProvider` reports the independent current
preferences through `CALL_4_KEY12` (`CALL_4`, wireless) and `CALL_5_KEY3`
(`CALL_5`, wired). MagicDesk records transport ownership, temporarily writes
`0` only for the active transport, and restores the provider value on transport
change, normal teardown, or interrupted-session recovery.
It does not read another package's private files.

## Stock Cooling Policy

The system application `/system/priv-app/NBFan/NBFan.apk` runs as
`cn.nubia.fan`. Its exported `FanService` is bound by `system_server`; the
service observes a small group of `Settings.System` values and performs the
protected fan and liquid-pump writes itself. Shell UID 2000 cannot write the
underlying sysfs/procfs nodes, but it can safely request the same stock policy
states:

| Setting | Verified values used by MagicDesk |
| --- | --- |
| `fan_state_of_manual` | `0` off, `1` enabled, `-100`/`100` stock automatic sentinels |
| `fan_state_of_mode` | `1` intelligent, `0` extreme |
| `liquid_cooling_main_switch` | `0` off, `1` enabled, `-100`/`100` stock automatic sentinels |
| `liquid_cooling_flow_speed_mode` | `low`, `mid`, `fast` |

`Settings.Global.game_fan_off_on` and
`Settings.System.liquid_cooling_off_on` report the resulting effective state.
They are monitoring outputs, not MagicDesk control inputs.

The standard capability report reads these settings through one bounded,
read-only `/system/bin/settings` snapshot in the existing Shizuku service
identity. It does not use a MagicDesk package-attributed `ContentResolver`
from UID 2000. The report keeps the platform-level system-controls provider,
the discovered fan/pump control namespace, and the readable effective state
as separate observations. A selected Nubia provider therefore does not by
itself claim that cooling control is present on a particular model.

Restoration has a non-obvious transition requirement. Replacing an active
manual fan request (`1`) directly with the previous automatic sentinel
(`-100` or `100`) leaves the manual request active on the verified firmware.
The pump policy behaves similarly. MagicDesk must first write the subsystem's
main/manual setting to `0`, then restore the saved mode/flow and original
main/manual value. Every command uses only the hardcoded keys above, validates
the read-back, and retains its ownership marker if restoration fails.

This path preserves Nubia's own thermal and safety policy and is the only
MagicDesk cooling-control backend. The main application does not write cooling
nodes directly.

## Phone Screen Power

The firmware also exposes `cmd display power-off 0` and
`cmd display power-reset 0` to shell UID 2000. Unlike
`RedMagicAppManager.openScreenOffTP(true)`, `power-off` requests the physical
state directly and leaves vendor input-panel state untouched. A local test
confirmed that display 0 reached the committed
`OFF` state, `power-reset` restored the DisplayManager-owned state, and the
physical power button could still wake the phone. This is the preferred
mechanism used by the Shizuku wake guard. It remains lifecycle-owned and fail
open: a heartbeat-bound helper always issues `power-reset` when the MagicDesk
process, Shizuku service, or external desktop session ends. The helper is not restarted
after an unexpected failure, so it cannot turn a user-restored screen off
again.

The same firmware contains the Binder service `cfreezer`
(`com.zte.performance.cfreezer.ICpuFreezerManager`). Turning display 0 off
caused direct `am_freeze` events for MagicDesk even while ActivityManager
classified the process as TOP with a foreground service; `cmd activity
unfreeze --sticky` did not override this separate vendor freezer. The service's
`noteCpuFreezerUidWorking(uid, working, "service")` API is accessible to shell
UID 2000 and is the firmware's own transient protection for an executing
service. The display helper refreshes it with the existing heartbeat for
MagicDesk and every application UID that owns a live task on the desktop
display. It retains that union
for the screen-off interval because a briefly absent task must not freeze
shared desktop input, and refreshes the firmware mouse viewport after the
display power transition. All entries are cleared after `power-reset`. If
cleanup cannot run, `cfreezer` expires an unrefreshed working state internally.
MagicDesk never writes the persistent freezer whitelist because such an entry
could outlive an interrupted helper.

Two apparent event sources are not sufficient by themselves. Nubia's
`zte_backlight` callback reports only calls to `setNit`, `setBacklight`, and
`setHbmMsg`; it is not a callback for physical display power. Likewise,
`DisplayManagerService.requestDisplayPower()` drives the primary display
device without replacing its logical power state, so a public
`DisplayListener` is not an authoritative ownership signal. Self-test and
device coverage include real text focus, physical wake, process death,
UserService death, desktop exit, and cable removal. The heartbeat stream, not a
poll-only listener, owns restoration.

## Physical Input Findings

Nubia's stock projected-input path reinjects physical-keyboard events through
`InputManagerService.injectInputEventWithDeviceId()` with flags
`0x08010000`. The `0x08000000` bit is Android's
`POLICY_FLAG_DISABLE_KEY_REPEAT`, which explains why held keys did not repeat
on the external display.

`DisplayMirrorCtrl.mIsMouseRightButtonToBack` initializes to `true`, and no
firmware assignment that disables it was found. Its right-button handler
converts secondary-button down/up into `KEYCODE_BACK`.

MagicDesk's input-port association plus keyboard bridge is required for the
correct target display, layout switching, shortcuts, and repeat.
The mouse bridge remains required to keep physical motion and buttons on the
target display. It consumes `BTN_RIGHT` and asks the UserService to inject one
secondary click at the vendor-reported cursor position, preventing the
firmware from translating either edge of the physical sequence into Back.

For phone-side text input, `IDisplayManager.getFocusMirrorWindow()` returns an
`IDisplayMirrorWindow` Binder. Its text, composing-region, deletion, and key
methods are sufficient to mirror a standard Android `InputConnection` without
changing the selected IME. MagicDesk captures that Binder only after the user
requests the software keyboard and discards it when the keyboard closes.
The capability probe checks all required method signatures without requesting
a focused window. It records the last real keyboard-session result separately;
`no_focused_window` is a transient runtime state, not an API compatibility
failure.

## Debug Probe

The debug build contains `NubiaVendorProbeInstrumentation`. Its default run is
read-only apart from attempting a same-value `Settings.Global` write, which is
expected to fail before mutation:

```sh
am instrument -w --user 0 \
  io.github.mekhontsev.magicdesk/.platform.nubia.NubiaVendorProbeInstrumentation
```

The optional property test must be requested explicitly:

```sh
am instrument -w --user 0 \
  -e allow_mutation true \
  io.github.mekhontsev.magicdesk/.platform.nubia.NubiaVendorProbeInstrumentation
```

That test accepts only the hardcoded
`desktop_mode_enforce_device_restrictions` property, reads and validates its
original boolean value, writes the opposite value, restores in `finally`, and
verifies the restored value. It also reveals caption layers through wireless
option `1100` and wired option `1102`, restoring each Nubia privacy preference
through the same lifecycle-owned production wrapper. It must not be expanded
into a generic mutation tool.
