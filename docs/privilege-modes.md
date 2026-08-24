# Shell Access And Display Modes

MagicDesk has one runtime privilege identity: an authorized Android shell,
normally the ADB-equivalent UID 2000. The current APK binds it through an
official Shizuku UserService. Shizuku is the Binder transport and lifecycle
owner, not the source of the shell capabilities described below. Display
selection is an independent session property.

## Runtime Contract

MagicDesk uses `dev.rikka.shizuku` API 13 and a bound UserService. It does not
use the deprecated `Shizuku.newProcess()` protocol, invoke `su`, or fall back to
an ordinary application-UID mode.

The runtime contract is deliberately strict:

- Shizuku must be installed and running.
- The user must grant MagicDesk access.
- The connected UserService must report Android shell UID 2000 or root UID 0.
- Both identities use the same commands and feature set; MagicDesk has no
  root-specific runtime branch.
- Losing Shizuku stops privileged runtime work instead of changing security
  boundaries silently.

The user normally starts Shizuku through wireless debugging or ADB. MagicDesk
does not install, start, or configure the Shizuku manager.

## Artifact Trust

Tagged releases and rolling development builds are signed with the same
certificate. Its SHA-256 fingerprint is:

```text
3A:F3:FE:F8:95:AC:BC:9C:B7:7B:FD:BB:7E:91:79:42:
95:70:72:14:97:E3:6E:C1:E4:19:68:C9:4B:52:99:50
```

The main APK contains no independent privilege-escalation path, kernel module,
or kernel-module loader. Optional local MCP automation is disabled by default,
binds only to loopback, and requires a generated bearer token. The separate
Display Fixes and Kernel Fixes APKs remain outside the main application's
runtime and release boundary.

## Capability Boundary

On the verified firmware, shell UID 2000 can:

- activate RedMagic external desktop mode and launch Touch Panel;
- observe exact tasks and apply ActivityTaskManager, WindowOrganizer, and
  WMShell desktop transactions;
- configure display geometry and density and capture screenshots;
- reveal native WMShell captions while the desktop session is active;
- lock the phone and control the physical state of display 0;
- read the current static wallpaper;
- browse and mutate every filesystem path available to shell through the
  built-in Files task, while sharing only individual capability URIs with
  ordinary Android applications;
- run user-entered commands in independent, lifecycle-bound `/system/bin/sh`
  sessions through built-in Console windows;
- install a user-confirmed APK through Android's shell package-manager command;
- change physical-keyboard layouts;
- read and grab external input devices and create `/dev/uinput` devices;
- use stock RedMagic bypass-charging, fan, pump, and thermal interfaces.

`ShellAccess` owns an immutable cached state. Binder-received, Binder-dead,
and permission-result events update that state; ordinary commands do not probe
the manager, permission, API version, and UID again. Device Setup and
Diagnostics can request an explicit fresh probe. A command failure also causes
one refresh before later work is allowed to continue.

Exact task observation runs directly inside the existing shell UserService.
The APK registers one typed AIDL callback; its Binder owns the corresponding
`TaskStackListener` and supplemental task-state monitor. Stopping the desktop,
losing the APK, or losing Shizuku unregisters the listener without leaving a
separate `app_process` behind.

## Input Streams

RedMagic routes physical input differently on its virtual desktop display.
MagicDesk therefore uses two lifecycle-bound native helpers:

- `libmagicdesk_keyboard_bridge.so` forwards physical keyboard events through
  a virtual external keyboard associated with the active desktop display. It
  preserves normal input and repeat, consumes only MagicDesk shortcuts, and
  coordinates `Ctrl+Space` with Android's configured keyboard layouts.
- `libmagicdesk_uinput_bridge.so` grabs only external cursor devices and
  forwards their complete pointer stream through a virtual mouse. This prevents
  RedMagic from converting `BTN_RIGHT` into Android Back.

The UserService links each helper stream to an APK Binder owner. If the APK,
UserService, or stream disappears, EOF or Binder death releases the physical
devices and destroys the virtual device; idle helpers do not send keepalives.
During a live RedMagic Console Mode session, input hot-plug updates the
physical source descriptors inside the existing helpers. Their virtual device
identity remains stable, avoiding application configuration changes. A source
is grabbed only after it reaches a neutral key/button state, so a wake sequence
cannot be divided between Android and the virtual device.

The mouse helper remains passive until its virtual device is visible and the
UserService has associated the input route with the desktop display. During
teardown it acknowledges releasing the physical sources before those
associations are removed. This keeps exclusive capture and display routing
within one ordered lifecycle.

The standard Android platform does not start these routing helpers or grab
physical input devices. It leaves already-correct system input routing intact;
the full routing bridge is a platform capability, not a requirement of the
common desktop.

Layout selection follows Android's enabled IME subtype order. MagicDesk never
selects an IME or hardcodes a language. An IME that keeps languages internally
but exposes only one Android subtype cannot support system-wide physical
layout cycling; changing the IME is the appropriate workaround.

## Phone Display Guard

While the external desktop remains active, MagicDesk can dim display 0 without
breaking external physical input. A shell helper owns DisplayManager's
`power-off 0` state and restores it with the operation advertised by the
platform (`power-on` on Android 15 or `power-reset` on Android 16). It
deliberately does not set Nubia's `nubia_screen_off_tp` flag, because that flag
lets the vendor text-input panel wake the phone whenever an external
application requests input.

The same heartbeat marks MagicDesk and the application UIDs owning live tasks
on the desktop display as active through RedMagic's transient `cfreezer` API.
Without that signal the firmware can freeze those processes while display 0 is
off. Normal teardown clears the state, and the vendor service expires it after
an abnormal stop.

Physical power, MagicDesk's Wake action, switching to mirroring, display cable
removal, APK shutdown, and Shizuku death all restore normal DisplayManager
ownership. This is a fail-open guard, not a persistent screen policy.

## Display Targets

- **Primary** selects Android display 0. It supports tablets and development
  without an external monitor.
- **Current** keeps the desktop on the display where setup was opened.
- **External** selects the active external desktop display and falls back to
  Current when none exists.
- **Auto** prefers an active external desktop display, otherwise Current. A
  platform driver may exclude a physical mirror-only display when the firmware
  still routes its pointer to the phone.

Display IDs are resolved at each transition and are never persisted as device
constants. Primary/Current operation does not activate a managed external
desktop transport, launch a vendor input panel, or apply an external monitor
profile.

## Device Setup

Device Setup always audits and configures the two standard Android
desktop-windowing values:

```sh
settings put global enable_freeform_support 1
settings put global force_resizable_activities 1
```

The Nubia platform extension additionally manages two firmware properties:

```sh
setprop persist.wm.debug.desktop_mode_enforce_device_restrictions false
setprop persist.wm.debug.desktop_use_rounded_corners false
```

The connected shell UserService writes the global settings. On supported
firmware, the ordinary MagicDesk process uses a verified RedMagic property
service for the two persistent properties. Its production wrapper accepts only
those two keys and boolean/absent values, and verifies each write with
`getprop`.

WMShell and ActivityTaskManager cache these values. Device Setup records the
current boot ID and requires a real reboot after a change. **Restore defaults**
deletes the two global overrides, clears the two persistent properties, resets
the primary-display size/density/scaling overrides, and normalizes stale phone
desktop tasks. It intentionally restores firmware defaults rather than values
captured by an earlier MagicDesk installation.

MagicDesk has no boot receiver. Rebooting leaves the phone in its normal state
until the user launches MagicDesk manually.

## Optional Root Add-ons

The separate `MagicDesk Kernel Fixes` APK is outside this runtime contract. It
has its own icon, requests root itself, and is never discovered or launched by
the main application. See [VITURE XR resolution fix](xr-resolution-fix.md).

The separate `MagicDesk Display Fixes` APK follows the same isolation rule. It
requests direct `su` access, applies one capability-checked native display
timing operation, and completes without binding Shizuku or leaving a root
process.
It exists for firmware where shell UID 2000 cannot read the monitor's complete
mode list. See [Native Display Mode Helper](native-display-mode-helper.md).
