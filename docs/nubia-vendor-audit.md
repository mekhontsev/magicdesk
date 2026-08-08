# Nubia Vendor Interface Audit

This document records REDMAGIC firmware behavior that was verified separately
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

The ordinary-UID tests ran from MagicDesk's real application UID in the
`u:r:untrusted_app:s0` SELinux domain. Root was used only by the research
harness to install debug builds, start instrumentation, and inspect firmware.
Unless noted otherwise, the vendor calls themselves ran without elevated
runtime access. The production application performs privileged work only
through its Shizuku UserService; ordinary-UID results below explain why that
boundary exists.

## Confirmed Interfaces

| Interface | Ordinary app access | Finding | Production decision |
| --- | --- | --- | --- |
| `redmagic.app.manager` | Read and write | Its Binder accepts arbitrary system-property names without a permission check or key allowlist. | Production setup uses a closed two-property enum with boolean validation and read-after-write verification; never expose a generic property editor. |
| `IDisplayManager` Nubia extensions | Read and command | Mirror state and `setCmdToDisplay` calls are accepted from the app UID. | Production routes the complete Console transition through Shizuku so display, task, and input ownership share one lifecycle. |
| `IInputManager` Nubia mouse extensions | Shell read and command verified | `getMousePosition`, `setMousePosition`, and `sendMouseCmd` expose the firmware cursor viewport used by wired and wireless projection. | Production resolves the methods inside the Shizuku UserService and combines absolute position updates with display-targeted events from MagicDesk's virtual pointer. |
| `IDisplayManager` mirror-input extensions | Shell command verified | `noteMirrorInputPanelStatus` registers an input owner; `getFocusMirrorWindow` returns the currently focused projected window. | Registration is lifecycle-bound to input routing. The focused window is retained only for an explicit software-keyboard session. |
| `IDisplayMirrorWindow` | Shell command verified | The focused window accepts composing text, committed text, deletion, and key events. | A bounded phone-side `InputConnection` forwards standard IME operations without selecting or embedding an IME. |
| `SurfaceControl.setSFOption(1100/1102, ...)` | Write verified | The app UID can change wireless/wired privacy and caption visibility. No corresponding SurfaceFlinger getter was found. | Shizuku uses transport-aware lifecycle ownership and restores the separate preferences reported by Nubia's exported projection provider. |
| `MirrorInputService` | Exported, no permission | The explicit service accepts open/close input-panel and Touch Panel reasons; its `MirrorInputActivity` can automatically replace another phone input panel. | MagicDesk does not disable the package. While its own touchpad is active, it removes only the automatically created activity task and reclaims its existing panel. |
| `scenedecision` | Read and callback | Foreground, visible-task, small-window, temperature, media-scene, and game-classification data are exposed. | Retained as research and possible diagnostics, not a task source of truth. |
| `ZteScreenRefreshRate` | Binder accepted | The implementation selects `DisplayControl.getPhysicalDisplayIds()[0]`. | Do not present it as external-monitor refresh control. |
| `ColorfulLightService` | Binder discoverable; methods have no local permission check | It can preview and apply REDMAGIC lighting scenes. | Out of scope: it duplicates device settings and mutates unrelated hardware. |
| `VendorPowerManagerService` | Binder discoverable | The interface contains no callable methods. | No use. |
| `zte_backlight` | Read accepted | Current nits and normalized backlight are readable; setters also exist. | Use the stock REDMAGIC phone-screen controller instead of raw brightness writes. |

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
defaults apply. Earlier ordinary-UID experiments showed that this property path
alone is insufficient: exact task, input, and display ownership still requires
shell access.

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

A controlled ordinary-app cold-launch probe ran from MagicDesk UID 10615 after
a resizable test application had been force-stopped. It requested a new task,
explicit bounds, and `windowingMode=freeform`. ActivityTaskManager accepted the
launch without a `SecurityException`, but normalized the new task to
fullscreen. It retained the requested rectangle only as
`mLastNonFullscreenBounds`. This confirms that provisioning the desktop
properties is not enough to give an ordinary app native desktop task control.

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
removal do not prevent the live-task crash. For a local desktop, MagicDesk
therefore disables system Home and Recents through `IStatusBarService` while
freeform tasks are live. Nubia's gesture path was observed entering Quickstep
despite `DISABLE_RECENT`; adding `DISABLE_HOME` makes Quickstep's own overview
target treat Home as unavailable. The call is owned by Binder tokens, uses
shell's existing `android.permission.STATUS_BAR`, requires no polling, and is
released only after the display-0 task repository has been normalized.
External-display sessions do not use this guard.

## Console And Caption Control

The Nubia `IDisplayManager` additions have no local permission checks for:

- `setCmdToDisplay`
- `getMirrorDisplayType`
- `getMirrorDisplayState`
- `getFocusMirrorWindow`
- `noteMirrorInputPanelStatus`
- `requestInputMethodChange`

The app UID successfully executed a no-op read transaction and the Console
command helper. The firmware command values observed in
`DisplayMirrorCtrl` are:

| Value | Observed purpose |
| --- | --- |
| 0 | Exit application mirror |
| 1 | Enter Console mode / mirror top activity |
| 2 | Alternate exit |
| 3 | Fit to display |
| 4 | Start GameBox |
| 5 | Stop GameBox |
| 6 | Go to GameBox |
| 7 | Continue a reused foreground task |
| 8 | Open Touch Panel |
| 9 | Toggle Touch Panel |
| 10 | Temporarily allow another component to change display mode |
| 11 | 3D fullscreen |
| 12 | 3D mouse display |

Values 4-7 and 11-12 are internal state-machine operations, not independent
public commands. MagicDesk must not call them without reproducing and
validating their complete surrounding transition.

The firmware uses SurfaceFlinger option `1100` for wireless privacy and `1102`
for wired privacy. Value `1` hides external layers whose names include `Task=`,
including native WMShell captions; value `0` reveals them. Ordinary app-process
invocations of these options succeed, but no SurfaceFlinger getter was found.
The exported `cn.nubia.touping.TouPingProvider` reports the independent current
preferences through `CALL_4_KEY12` (`CALL_4`, wireless) and `CALL_5_KEY3`
(`CALL_5`, wired). MagicDesk records transport ownership, temporarily writes
`0` only for the active transport, and restores the provider value on transport
change, mirror transition, normal teardown, or interrupted-session recovery.
It does not read another package's private files.

## Task-State Hints

`scenedecision` transaction 39 returned the foreground package, transaction 41
returned visible-task bundles, and transaction 42 returned the small-window
list. Callback flag `1` registered successfully from the untrusted app process
and immediately delivered event `2000` with:

- `stackId`
- `displayId`
- package and activity names
- UID and PID
- windowing mode

This was useful for evaluating a lower-privilege task catalog. It is not
authoritative:
after the external display was disconnected, the initial callback still
contained old tasks for removed display ids 14 and 15. Any consumer must:

1. filter entries against current public `DisplayManager` displays;
2. tolerate duplicate packages and stale stack ids;
3. treat callbacks as invalidatable hints;
4. fall back to public launch behavior rather than claiming exact task control.

Shizuku's `TaskStackListener` remains the correct source for exact task
observation and control.

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

Restoration has a non-obvious transition requirement. Replacing an active
manual fan request (`1`) directly with the previous automatic sentinel
(`-100` or `100`) leaves the manual request active on the verified firmware.
The pump policy behaves similarly. MagicDesk must first write the subsystem's
main/manual setting to `0`, then restore the saved mode/flow and original
main/manual value. Every command uses only the hardcoded keys above, validates
the read-back, and retains its ownership marker if restoration fails.

This path preserves Nubia's own thermal and safety policy and is the only
MagicDesk cooling-control backend. The discarded direct-node prototype is not
present in the main application.

## Phone Input-Panel Wake

When a mirrored application requests text input, `DisplayMirrorCtrl` starts
`MirrorInputService` with `reason=open_input_panel`. The service launches
`MirrorInputActivity`; its `onResume()` checks
`Settings.Global.nubia_screen_off_tp` and immediately calls
`RedMagicAppManager.openScreenOffTP(false)` when the phone is dimmed. No
reviewed `DisplayMirrorCtrl` command or setting disables only this automatic
input-panel path.

Closing the panel after launch is too late because the activity has already
woken the phone. Registering a panel token changes input routing but does not
suppress the launch. Android 16 also rejects UID 2000 changing the enabled
state of `MirrorInputService`. Suspending or repeatedly force-stopping the
entire `cn.nubia.keymapcenter` package would remove the user-requested Touch
Panel and is not used.

The firmware also exposes `cmd display power-off 0` and
`cmd display power-reset 0` to shell UID 2000. Unlike
`RedMagicAppManager.openScreenOffTP(true)`, `power-off` requests the physical
state directly and leaves `nubia_screen_off_tp=0`; `MirrorInputActivity`
therefore has no dimmed-panel flag to undo when a mirrored application asks
for text input. A local test confirmed that display 0 reached the committed
`OFF` state, `power-reset` restored the DisplayManager-owned state, and the
physical power button could still wake the phone. This is the preferred
mechanism used by the Shizuku wake guard. It remains lifecycle-owned and fail
open: a heartbeat-bound helper always issues `power-reset` when the MagicDesk
process, Shizuku service, or Console session ends. The helper is not restarted
after an unexpected failure, so it cannot turn a user-restored screen off
again.

The same firmware contains the Binder service `cfreezer`
(`com.zte.performance.cfreezer.ICpuFreezerManager`). Turning display 0 off
caused direct `am_freeze` events for MagicDesk even while ActivityManager
classified the process as TOP with a foreground service; `cmd activity
unfreeze --sticky` did not override this separate vendor freezer. The service's
`noteCpuFreezerUidWorking(uid, working, "service")` API is accessible to shell
UID 2000 and is the firmware's own transient protection for an executing
service. The display helper refreshes it with the existing heartbeat and clears
it after `power-reset`. If cleanup cannot run, `cfreezer` expires an unrefreshed
working state internally. A dynamic `setFrozenWhiteList` entry was also tested
successfully but rejected for production because abrupt helper termination
could leave that persistent entry behind.

Two apparent event sources are not sufficient by themselves. Nubia's
`zte_backlight` callback reports only calls to `setNit`, `setBacklight`, and
`setHbmMsg`; it is not a callback for physical display power. Likewise,
`DisplayManagerService.requestDisplayPower()` drives the primary display
device without replacing its logical power state, so a public
`DisplayListener` is not an authoritative ownership signal. The final Shizuku
guard was validated with real text focus, physical wake, process death,
UserService death, Console exit, and cable removal. Its heartbeat stream, not a
poll-only listener, owns restoration.

## Physical Input Findings

Nubia's Console input path reinjects physical-keyboard events through
`InputManagerService.injectInputEventWithDeviceId()` with flags
`0x08010000`. The `0x08000000` bit is Android's
`POLICY_FLAG_DISABLE_KEY_REPEAT`, which explains why held keys did not repeat
on the external display.

`DisplayMirrorCtrl.mIsMouseRightButtonToBack` initializes to `true`, and no
firmware assignment that disables it was found. Its right-button handler
converts secondary-button down/up into `KEYCODE_BACK`.

Registering a panel token through `noteMirrorInputPanelStatus` suppresses
Nubia's key reinjection only while the stock input panel reports text input.
It does not associate the physical keyboard with the external display.
MagicDesk's input-port association plus keyboard bridge is therefore still
required for correct target display, layout switching, shortcuts, and repeat.
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
  io.github.mekhontsev.magicdesk/.NubiaVendorProbeInstrumentation
```

The optional property test must be requested explicitly:

```sh
am instrument -w --user 0 \
  -e allow_mutation true \
  io.github.mekhontsev.magicdesk/.NubiaVendorProbeInstrumentation
```

That test accepts only the hardcoded
`desktop_mode_enforce_device_restrictions` property, reads and validates its
original boolean value, writes the opposite value, restores in `finally`, and
verifies the restored value. It also reveals caption layers through wireless
option `1100` and wired option `1102`, restoring each Nubia privacy preference
through the same lifecycle-owned production wrapper. It must not be expanded
into a generic mutation tool.
