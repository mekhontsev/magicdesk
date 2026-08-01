# Shizuku And Display Modes

MagicDesk has one runtime backend: an official Shizuku UserService running as
Android shell UID 2000. Display selection is an independent session property.

## Runtime Contract

MagicDesk uses `dev.rikka.shizuku` API 13 and a bound UserService. It does not
use the deprecated `Shizuku.newProcess()` protocol, invoke `su`, or fall back to
an ordinary application-UID mode.

The backend is deliberately strict:

- Shizuku must be installed and running.
- The user must grant MagicDesk access.
- The connected UserService must report UID 2000.
- A root/Sui service reporting UID 0 is rejected.
- Losing Shizuku stops privileged runtime work instead of changing security
  boundaries silently.

The user normally starts Shizuku through wireless debugging or ADB. MagicDesk
does not install, start, or configure the Shizuku manager.

## Capability Boundary

On the verified firmware, shell UID 2000 can:

- activate REDMAGIC external desktop mode and launch Touch Panel;
- observe exact tasks and apply ActivityTaskManager, WindowOrganizer, and
  WMShell desktop transactions;
- configure display geometry and density and capture screenshots;
- reveal native WMShell captions while the desktop session is active;
- lock the phone and control the physical state of display 0;
- read the current static wallpaper;
- change physical-keyboard layouts;
- read and grab external input devices and create `/dev/uinput` devices;
- use stock REDMAGIC bypass-charging, fan, pump, and thermal interfaces.

These capabilities are represented by `RuntimeAccess`. UI code checks a named
capability instead of invoking commands directly. `PrivilegedCommandRunner`
has only the Shizuku transport, so an unavailable service cannot accidentally
promote the application to another backend.

## Input Streams

REDMAGIC routes physical input differently on its virtual desktop display.
MagicDesk therefore uses two lifecycle-bound native helpers:

- `libmagicdesk_keyboard_bridge.so` forwards physical keyboard events through
  a virtual external keyboard associated with the active desktop display. It
  preserves normal input and repeat, consumes only MagicDesk shortcuts, and
  coordinates `Ctrl+Space` with Android's configured keyboard layouts.
- `libmagicdesk_uinput_bridge.so` grabs only external cursor devices and
  forwards their complete pointer stream through a virtual mouse. This prevents
  REDMAGIC from converting `BTN_RIGHT` into Android Back.

The UserService sends heartbeats over owned streams. If the APK, UserService,
or stream disappears, each helper releases its physical devices and destroys
its virtual device. Input hot-plug restarts only the affected bridge with a new
device inventory.

Layout selection follows Android's enabled IME subtype order. MagicDesk never
selects an IME or hardcodes a language. An IME that keeps languages internally
but exposes only one Android subtype cannot support system-wide physical
layout cycling; changing the IME is the appropriate workaround.

## Phone Display Guard

While the external desktop remains active, MagicDesk can dim display 0 without
breaking external physical input. A shell helper owns DisplayManager's
`power-off 0` state and restores it with `power-reset 0`. It deliberately does
not set Nubia's `nubia_screen_off_tp` flag, because that flag lets the vendor
text-input panel wake the phone whenever an external application requests
input.

The same heartbeat marks MagicDesk's foreground service as active through
REDMAGIC's transient `cfreezer` API. Without that signal the firmware can
freeze the desktop HOME process while display 0 is off. Normal teardown clears
the state, and the vendor service expires it after an abnormal stop.

Physical power, MagicDesk's Wake action, switching to mirroring, display cable
removal, APK shutdown, and Shizuku death all restore normal DisplayManager
ownership. This is a fail-open guard, not a persistent screen policy.

## Display Targets

- **Primary** selects Android display 0. It supports tablets and development
  without an external monitor.
- **Current** keeps the desktop on the display where setup was opened.
- **External** selects the active REDMAGIC desktop display and falls back to
  Current when none exists.
- **Auto** prefers an active REDMAGIC desktop display, otherwise Current. In
  Mirror Mode it ignores the physical presentation display because REDMAGIC
  continues routing the pointer to the phone.

Display IDs are resolved at each transition and are never persisted as device
constants. Primary/Current operation does not activate REDMAGIC external
desktop mode, launch Touch Panel, or apply an external monitor profile.

## Device Setup

Device Setup audits and owns four desktop-windowing values:

```sh
settings put global enable_freeform_support 1
settings put global force_resizable_activities 1
setprop persist.wm.debug.desktop_mode_enforce_device_restrictions false
setprop persist.wm.debug.desktop_use_rounded_corners false
```

Shizuku writes the two global settings as shell UID 2000. The ordinary
MagicDesk process uses a verified REDMAGIC property service for the two
persistent properties. Its production wrapper accepts only those two keys,
boolean values, and restoration of a previously absent value; it verifies each
write with `getprop` and records the original before mutation.

WMShell and ActivityTaskManager cache these values. Device Setup records the
current boot ID and requires a real reboot after a change. **Restore previous
values** restores only values that MagicDesk owns.

MagicDesk has no boot receiver. Rebooting leaves the phone in its normal state
until the user launches MagicDesk manually.

## Optional Root Add-on

The separate `MagicDesk Kernel Fixes` APK is outside this runtime contract. It
has its own icon, requests root itself, and is never discovered or launched by
the main application. See [VITURE XR resolution fix](xr-resolution-fix.md).
