# Privilege And Display Modes

MagicDesk treats system preparation, runtime privileges, and the desktop
display as independent session properties.

## Runtime Privileges

### Basic

Basic mode never invokes `su`. It provides the MagicDesk shell, Start menu,
taskbar pins, desktop shortcuts and files, notifications, calendar, and public
Android application launching.

The standard Android **Display over other apps** permission is requested for
the taskbar and shell overlays. A user can continue without it, with those
surfaces limited.

Device Setup links directly to Android Developer options when **Enable freeform
windows** or **Force activities to be resizable** is disabled. Both options are
available to an ordinary user on verified REDMAGIC firmware. MagicDesk checks
them again when the user returns; restart Android if Settings requests it.
These options improve Basic windowing but do not grant privileged task control
or bypass the firmware's desktop-device eligibility checks.

Public `ActivityOptions.setLaunchDisplayId()` and `setLaunchBounds()` are used
for new launches. Android may ignore requested bounds when desktop windowing is
not supported on the selected display. Basic mode cannot inspect or manipulate
arbitrary existing tasks, preserve their exact cross-display identity, provide
the global physical-input bridge, remap the right mouse button, or change
display geometry.

On the verified firmware, even a genuinely new task launched by the MagicDesk
application UID with explicit freeform windowing mode and bounds was normalized
to fullscreen. WMShell's general desktop Binder is delivered only to the
STATUS_BAR_SERVICE-protected system launcher and enforces
`MANAGE_ACTIVITY_TASKS` on every operation. The older Nubia `WindowReply`
intent path is restricted by a firmware application allowlist, so MagicDesk
does not present it as general Basic-mode window management.

On the verified REDMAGIC firmware, publicly launching a package that is already
running on another display can make the vendor framework stop that process and
create a new task on the target display. Basic mode cannot detect or prevent
that vendor action. Do not use Basic to move a stateful application such as a
terminal with live sessions between displays; Root or Shizuku task control is
required for exact task reuse.

### Shizuku

Shizuku mode uses the official `dev.rikka.shizuku` API and a bound UserService;
it does not use the deprecated `Shizuku.newProcess()` text protocol. The mode
is strict: a missing/stopped server, denied permission, or failed UserService
does not fall back to Root or Basic.

A Shizuku server started through ADB or wireless debugging runs the service as
Android shell UID 2000. On the verified firmware it can activate REDMAGIC
Console Mode, launch Touch Panel, correct external-display geometry and DPI,
reuse exact tasks, apply freeform/fullscreen `WindowContainerTransaction`
changes, register a live task listener, take screenshots, dim or restore the
phone display through the stock REDMAGIC controller, and control stock REDMAGIC
bypass charging. It can also lock the device, read the current static system
wallpaper, monitor the firmware's readable thermal zones, and request stock
intelligent/extreme fan or low/mid/fast liquid-pump profiles through Nubia's
`NBFan` policy. The UserService exposes finite commands plus lifecycle-bound
streams for task events and fail-open physical-input bridges.

Shizuku Device Setup writes the two public windowing settings as UID 2000. The
ordinary MagicDesk process then uses the verified REDMAGIC property service to
disable desktop device restrictions and rounded corners. That writer accepts
only the two hardcoded reviewed keys and boolean values. After the required
reboot, WMShell creates `DesktopTasksController`, and Shizuku uses its native
caption and task path. If a different firmware rejects provisioning or does not
expose the WMShell command, direct Android task transactions remain the bounded
fallback.

REDMAGIC can hide native caption layers through SurfaceFlinger option `1102`.
During a Shizuku Console session MagicDesk temporarily sets the option to
visible. It records lifecycle ownership and restores the latest value mirrored
by Nubia in `Settings.Global.cast_privacy_model`; an absent setting uses
Nubia's default privacy-enabled value.

Shell can read raw `/dev/input/event*`, acquire `EVIOCGRAB`, create
`/dev/uinput` devices, change physical-keyboard layouts, and register task
listeners. In Console Mode, MagicDesk forwards the complete keyboard stream
through an external virtual keyboard associated with the Console display. The
bridge consumes only MagicDesk shortcuts, preserves ordinary combinations and
key repeat, and pauses briefly during `Ctrl+Space` so the first subsequent key
uses the newly selected layout. `Win+L` calls WindowManager from the same
shell-UID UserService. For a physical mouse,
MagicDesk grabs the source before Nubia converts `BTN_RIGHT` to Back and
forwards the complete pointer stream through an internal virtual mouse. This
restores standard Android secondary-click behavior in both MagicDesk and
ordinary applications.
MagicDesk queries `IInputManager` for the selected layout of every enabled IME
subtype, matching Android's own layout-mapping model without reading private
InputManager files.

Android 16 blocks UID 2000 from changing the enabled state of
`cn.nubia.keymapcenter.mirror.MirrorInputService`, even though the shell package
holds `CHANGE_COMPONENT_ENABLED_STATE`. Shizuku therefore does not reuse the
Root component guard. Instead it invokes DisplayManager's shell-only
`power-off 0` operation and deliberately leaves `nubia_screen_off_tp=0`.
Nubia's input-panel activity then has no dimmed-panel state to cancel when an
external application requests text input. A heartbeat-bound helper owns this
physical display override and calls `power-reset 0` when the user restores the
screen, Console Mode exits, MagicDesk or Shizuku disappears, or its control
stream times out. The same heartbeat refreshes REDMAGIC `cfreezer`'s transient
service-working state; without it the firmware freezes MagicDesk's desktop HOME
process while display 0 is off. The state is cleared on normal restore and
expires inside the vendor service after an abnormal stop. Root mode retains the
narrower, already validated vendor component guard and stock REDMAGIC screen
transition.

The keyboard and mouse bridges are active only while Shizuku Console Mode and
the corresponding physical device are present. The shell-UID UserService
services their independent heartbeat streams and the optional phone-display
guard. If
the APK, UserService, or a control stream disappears, each helper fails open:
input helpers destroy their virtual devices and release every physical source,
while the display helper restores DisplayManager ownership. Input-device
hot-plug restarts only the affected bridge with a fresh device inventory.
Screenshots remain available from **Tools > Screenshot**; the panel is
synchronously detached and the capture is queued against display frames rather
than a fixed delay.

MagicDesk does not download, install, or start Shizuku. The user must install
the official manager, start its server, and grant MagicDesk. A Shizuku/Sui
service running as UID 0 is identified separately, but MagicDesk still exposes
only the bounded Shizuku command capability set in that runtime mode. Use Root
mode for the complete feature set.

### Root

Root mode enables exact task observation and transitions, Console Mode
automation, global shortcuts, physical input correction, display overrides,
screenshots, guarded phone-screen controls, capability-probed REDMAGIC hardware
monitoring/control, bypass charging, and the optional separately installed
Kernel Fixes add-on. Shizuku requests the firmware's stock cooling profiles
through `NBFan`; Root additionally writes the probed hardware nodes directly,
provides five explicit fan levels, and runs MagicDesk's temperature-driven fan
curve.

### Auto

Auto currently selects Root when `su` returns uid 0 and otherwise falls back to
Basic. It does not select or request Shizuku automatically, and it never
escalates a session explicitly started as Basic.

All privileged command entry points pass through `RuntimeAccess` and
`PrivilegedCommandRunner`. Selecting Basic therefore changes the actual
execution boundary rather than only disabling controls in the UI.

## Display Target

- **Primary** forces the desktop shell onto Android display 0. This is useful
  on tablets and for development without an external monitor.
- **Current** keeps the shell on the display where setup was opened.
- **External** selects an active Console or presentation display and falls back
  to Current when none exists. On REDMAGIC in Mirror Mode, a physical
  presentation display can render MagicDesk but the system pointer remains on
  the phone. Use External with an active Console display for an interactive
  desktop.
- **Auto** prefers an already-active Nubia Console display, otherwise Current.
  It deliberately ignores a physical presentation display while REDMAGIC is in
  Mirror Mode because the firmware continues routing its pointer to the phone.

Display ids are resolved for every launch and are never stored as device
constants. Primary mode does not activate Nubia Console Mode, launch Nubia
Touch Panel, turn off the phone display, or apply external-display DPI.

## System Provisioning

The runtime mode does not own or automatically restore the Android desktop
configuration. Device Setup audits these values separately:

```sh
settings put global enable_freeform_support 1
settings put global force_resizable_activities 1
setprop persist.wm.debug.desktop_mode_enforce_device_restrictions false
setprop persist.wm.debug.desktop_use_rounded_corners false
```

The first two values map to **Enable freeform windows** and **Force activities
to be resizable** in Android Developer options. Basic users can enable them
manually; Shizuku can apply and restore them through `WRITE_SECURE_SETTINGS`.
Root changes the persistent properties with `setprop`. Shizuku Device Setup
uses the firmware's unprotected REDMAGIC property service from the ordinary
application UID. The production wrapper exposes only the two hardcoded
properties above, accepts boolean values or restoration of an originally
absent value, verifies every write with `getprop`, and records the original
values before mutation.

WMShell and ActivityTaskManager cache this configuration, so Device Setup asks
for a real reboot after changing the two global settings or persistent
properties. A device configured previously with Root can later run MagicDesk
in Shizuku mode without granting root at runtime. Diagnostics reports both the
active backend and the effective provisioning state. See
[Nubia vendor interface audit](nubia-vendor-audit.md) for the verified
lower-privilege interfaces and their safety constraints.

Launch overrides are available for development:

```sh
am start -n io.github.mekhontsev.magicdesk/.DeviceSetupActivity \
  --es io.github.mekhontsev.magicdesk.extra.PRIVILEGE_MODE basic \
  --es io.github.mekhontsev.magicdesk.extra.DISPLAY_TARGET primary
```
