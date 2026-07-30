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
changes, register a live task listener, take screenshots, and control stock
REDMAGIC bypass charging. The UserService exposes finite commands plus
lifecycle-bound streams for task events and fail-open physical-input bridges.

A clean Shizuku-only installation cannot disable the firmware's
`desktop_mode_enforce_device_restrictions` property. WMShell therefore does not
create `DesktopTasksController` or its system captions. MagicDesk falls back at
the capability boundary to direct Android task transactions; window launch,
fullscreen, restore, snap, minimize, focus, and close remain available through
the taskbar and other MagicDesk controls, but the native draggable caption is
absent. If the device was provisioned previously and WMShell's desktop command
is genuinely available to shell, MagicDesk can use that native path without
granting runtime root.

Shell can read raw `/dev/input/event*`, acquire `EVIOCGRAB`, create
`/dev/uinput` devices, change physical-keyboard layouts, and register task
listeners. In Console Mode, MagicDesk forwards the complete keyboard stream
through an external virtual keyboard associated with the Console display. The
bridge consumes only MagicDesk shortcuts, preserves ordinary combinations and
key repeat, and pauses briefly during `Ctrl+Space` so the first subsequent key
uses the newly selected layout. `Win+L` remains Root-only. For a physical mouse,
MagicDesk grabs the source before Nubia converts `BTN_RIGHT` to Back and
forwards the complete pointer stream through an internal virtual mouse. This
restores standard Android secondary-click behavior in both MagicDesk and
ordinary applications.
MagicDesk queries `IInputManager` for the selected layout of every enabled IME
subtype, matching Android's own layout-mapping model without reading private
InputManager files.

The keyboard and mouse bridges are active only while Shizuku Console Mode and
the corresponding physical device are present. Heartbeats make them fail open:
if the APK, UserService, or control stream disappears, each native helper
destroys its virtual device and releases every physical source within six
seconds. Input-device hot-plug restarts the bridges with a fresh device
inventory. Screenshots remain available from **Tools > Screenshot**; the panel
is synchronously detached and the capture is queued against display frames
rather than a fixed delay.

MagicDesk does not download, install, or start Shizuku. The user must install
the official manager, start its server, and grant MagicDesk. A Shizuku/Sui
service running as UID 0 is identified separately, but MagicDesk still exposes
only the bounded Shizuku command capability set in that runtime mode. Use Root
mode for the complete feature set.

### Root

Root mode enables exact task observation and transitions, Console Mode
automation, global shortcuts, physical input correction, display overrides,
screenshots, phone-screen controls, capability-probed REDMAGIC hardware
monitoring/control, bypass charging, and the optional separately installed
Kernel Fixes add-on. Fan and pump writes are never available to Basic or
Shizuku shell sessions.

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
The persistent properties remain Root-only: disabling device restrictions
enables the complete WMShell desktop path on this firmware, while disabling
rounded corners is a cosmetic consistency setting.

WMShell and ActivityTaskManager cache this configuration, so Device Setup asks
for a real reboot after changing the two global settings or the Root-only
properties. Shizuku cannot apply the persistent property changes. A device
configured previously with Root can later run MagicDesk in Shizuku mode
without granting root at runtime; a clean Shizuku installation uses direct
task transactions instead of WMShell desktop captions. Diagnostics reports
both the active backend and the effective provisioning state.

Launch overrides are available for development:

```sh
am start -n io.github.mekhontsev.magicdesk/.DeviceSetupActivity \
  --es io.github.mekhontsev.magicdesk.extra.PRIVILEGE_MODE basic \
  --es io.github.mekhontsev.magicdesk.extra.DISPLAY_TARGET primary
```
