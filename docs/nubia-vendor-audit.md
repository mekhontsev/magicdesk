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

The tests ran from MagicDesk's real application UID in the
`u:r:untrusted_app:s0` SELinux domain. Root was used only by the test harness
to install the debug APK and start instrumentation. Unless noted otherwise,
the vendor calls themselves ran without Root or Shizuku.

## Confirmed Interfaces

| Interface | Basic app access | Finding | Production decision |
| --- | --- | --- | --- |
| `redmagic.app.manager` | Read and write | Its Binder accepts arbitrary system-property names without a permission check or key allowlist. | Shizuku setup uses a closed two-property enum with boolean validation and read-after-write verification; never expose a generic property editor. |
| `IDisplayManager` Nubia extensions | Read and command | Mirror state and `setCmdToDisplay` calls are accepted from the app UID. | Candidate for Basic Console activation after external-display validation. |
| `SurfaceControl.setSFOption(1102, ...)` | Write verified | The app UID can change wired privacy/caption visibility. No corresponding getter was found. | Shizuku uses lifecycle ownership and restores Nubia's `Settings.Global.cast_privacy_model` value. |
| `MirrorInputService` | Exported, no permission | The explicit service accepts open/close input-panel and Touch Panel reasons. | Prefer the stock entry point where its lifecycle is understood. |
| `scenedecision` | Read and callback | Foreground, visible-task, small-window, temperature, media-scene, and game-classification data are exposed. | Possible Basic task hints and diagnostics, not a source of truth. |
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

The production setter accepts enum-like operations rather than caller-provided
keys or values, rejects unexpected property values, records originals before
mutation, and verifies every write and restore. Basic mode could use the same
property path while asking the user to enable the two public Developer options
manually; that flow is not implemented yet.

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

A controlled Basic-mode cold-launch probe ran from MagicDesk UID 10615 after
Golly had been force-stopped. It requested a new task, explicit bounds, and
`windowingMode=freeform`. ActivityTaskManager accepted the launch without a
`SecurityException`, but normalized the new task to fullscreen and retained
the requested rectangle only as `mLastNonFullscreenBounds`. Provisioning the
desktop properties is therefore not enough to give an ordinary app native
desktop task control.

The firmware also contains the older Nubia `WindowReply` path. An intent
identifier ending in `_WindowReply` selects that policy, but support is
filtered through `/system/etc/zte_windowReply_control.xml`. Its force-support
list explains why selected applications such as Chrome, Gmail, and Telegram
can use Nubia floating windows while an arbitrary resizable application may
not. MagicDesk does not use `WindowReply` as a Basic fallback because it is a
vendor allowlist mechanism, not a general desktop contract.

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

The firmware's wired privacy path uses SurfaceFlinger option `1102`. Value `1`
hides external layers whose names include `Task=`, including native WMShell
captions; value `0` reveals them. A Basic app-process invocation of
`SurfaceControl.setSFOption(1102, 1)` succeeded. No SurfaceFlinger getter was
found, but Nubia's `WiredSettingsActivity` mirrors every user change to
`Settings.Global.cast_privacy_model`; an absent value corresponds to the
firmware's default `true`. Root can read NubiaProjectionScreen's private XML.
Shizuku records ownership, temporarily writes `0`, and restores the current
global value on mirror transition, normal teardown, or interrupted-session
recovery.

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

This is useful for a future Basic-mode task catalog. It is not authoritative:
after the external display was disconnected, the initial callback still
contained old tasks for removed display ids 14 and 15. Any consumer must:

1. filter entries against current public `DisplayManager` displays;
2. tolerate duplicate packages and stale stack ids;
3. treat callbacks as invalidatable hints;
4. fall back to public launch behavior rather than claiming exact task control.

Shizuku's `TaskStackListener` remains the correct source for exact task
observation and control.

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
The mouse bridge remains required to deliver `BTN_RIGHT` to applications
instead of Back.

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
verifies the restored value. It also reveals caption layers through option
`1102` and immediately restores Nubia's wired-privacy state through the same
lifecycle-owned production wrapper. It must not be expanded into a generic
mutation tool.
