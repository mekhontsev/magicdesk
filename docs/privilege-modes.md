# Shell Access And Display Modes

MagicDesk has one runtime privilege identity: an authorized Android shell,
normally the ADB-equivalent UID 2000. The current APK binds it through an
official Shizuku UserService. Shizuku is the Binder transport and lifecycle
owner, not the source of the shell capabilities described below. Display
selection is an independent session property.

## Runtime Contract

MagicDesk uses `dev.rikka.shizuku` API 13 and a bound UserService. It does not
invoke `su` or fall back to an ordinary application-UID mode.

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
Kernel Fixes APK remains outside the main application's runtime and release
boundary.

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
- associate input devices with displays and create a virtual phone pointer;
- use stock RedMagic bypass-charging, fan, pump, and thermal interfaces.

`ShellAccess` owns an immutable cached state. Binder-received, Binder-dead,
and permission-result events update that state; ordinary commands do not probe
the manager, permission, API version, and UID again. Device Setup and
Diagnostics can request an explicit fresh probe. A command failure also causes
one refresh before later work is allowed to continue.

Exact task observation runs directly inside the existing shell UserService.
The APK registers one typed AIDL callback; its Binder owns the corresponding
`TaskStackListener` and centralized `FrameworkTaskObservationSource`. Stopping
the desktop, losing the APK, or losing Shizuku unregisters the listener without
leaving a separate `app_process` behind.

## Input Streams

Physical keyboards and mice remain native Android input devices. The
shell-owned `DesktopInputRoutingSession` associates input locations with the
desktop display unique ID and journals their previous runtime associations.
Composite devices sharing a location share one association. Hot-plug callbacks
reconcile routes without reading or forwarding physical event streams.

`DesktopShortcutService` is a key-only Accessibility filter, independent of
the user's IME. Shizuku enables it for the desktop session and restores that
enablement on Close, preserving other services. It consumes desktop shortcuts
only from keyboards confirmed on the target display; ordinary input continues
through Android. No accessibility window content or editor text is requested.

`libmagicdesk_uinput_bridge.so` provides a virtual relative mouse solely for
the phone touchpad. Its location is associated before creation. EOF or Binder
owner death destroys that device; there is no idle keepalive.

One serialized input owner orders startup, hot-plug and teardown on every
platform. Close destroys the phone pointer and restores input associations
before display removal. Durable ownership journals make cleanup retryable after
process loss without overwriting unrelated system routes.

Layout selection follows Android's enabled IME subtype order. MagicDesk never
selects an IME or hardcodes a language. An IME that keeps languages internally
but exposes only one Android subtype cannot support system-wide physical
layout cycling; changing the IME is the appropriate workaround.

## Phone Display Guard

While the external desktop remains active, MagicDesk can dim display 0 without
breaking external physical input. A shell helper owns DisplayManager's
`power-off 0` state and restores it with the operation advertised by the
platform (`power-on` on Android 15 or `power-reset` on Android 16). Command
discovery is shared with diagnostics and probes only help or argument
validation without a display ID, leaving the screen state unchanged.

The same heartbeat marks MagicDesk and the application UIDs owning live tasks
on the desktop display as active through RedMagic's transient `cfreezer` API.
Without that signal the firmware can freeze those processes while display 0 is
off. Normal teardown clears the state, and the vendor service expires it after
an abnormal stop.

Physical power, MagicDesk's Wake action, display cable
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

Device Setup always audits and configures the two required Android
desktop-windowing values:

```sh
settings put global enable_freeform_support 1
settings put global force_resizable_activities 1
```

These are device-wide provisioning values, not session overrides. Close Desktop
does not clear them; Restore defaults does.

The optional `force_desktop_mode_on_external_displays` global value is exposed
under **Settings > Android system**, not required by Device Setup. A confirmed
change uses the connected shell service while no desktop session is owned and
verifies the resulting Android value. The UI warns about system navigation bars
and advises reconnecting the display; some firmware may require an Android
restart. Neither creates a mandatory setup gate.
There is no automatic reboot or preference reapplied at startup. This setting
affects external HOME, system decorations and input policy; it is independent
of the optional physical-input bridge and survives Close Desktop.

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
deletes the three global overrides, clears the two persistent properties, resets
the primary-display size/density/scaling overrides, and normalizes stale phone
desktop tasks. It intentionally restores firmware defaults rather than values
captured by an earlier MagicDesk installation.

MagicDesk has no boot receiver. Rebooting leaves the phone in its normal state
until the user launches MagicDesk manually.

## Optional Root Add-ons

The separate `MagicDesk Kernel Fixes` APK is outside this runtime contract. It
has its own icon, requests root itself, and is never discovered or launched by
the main application. See [VITURE XR resolution fix](xr-resolution-fix.md).
