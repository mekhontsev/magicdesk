# MagicDesk

MagicDesk is an open-source, DeX-style desktop environment for REDMAGIC
devices. It turns REDMAGIC external-display support into a practical desktop
workspace with native Android windows, a taskbar, Start menu, desktop
shortcuts, global keyboard controls, notifications, and phone-based touchpad
support.

MagicDesk is intended to be the REDMAGIC counterpart to Samsung DeX. It is not
a port of DeX and is not affiliated with Samsung. It builds on Android's own
desktop window manager and the external-display services already present in
REDMAGIC firmware.

> **Development note:** MagicDesk is a vibe-coded project, built primarily
> through iterative AI-assisted development and hands-on testing on real
> REDMAGIC hardware. Root access and undocumented vendor interfaces make
> independent review especially important.

> **Project status:** MagicDesk 1.0 is under active development. The current
> firmware verification is limited to the REDMAGIC 11 Pro profile listed
> below.

![MagicDesk running Termux and Golly in native desktop windows with the calendar panel open](docs/images/magicdesk-desktop.png)

## Why MagicDesk

REDMAGIC phones can drive an external display, but their stock interface does
not provide the complete desktop workflow available in Samsung DeX.
MagicDesk supplies that missing shell while continuing to use native Android
tasks and REDMAGIC's existing projection stack.

The result is a familiar desktop model:

- Android applications run in overlapping, resizable system windows.
- Root mode uses native WMShell captions for move, resize, snap, maximize,
  minimize, and close. Shizuku uses direct Android task transactions and the
  MagicDesk taskbar controls when WMShell desktop mode is unavailable.
- A persistent taskbar tracks real Android tasks and pinned applications.
- Start, desktop shortcuts, a desktop folder, task switching, and Show Desktop
  provide normal mouse-driven navigation.
- DeX-style global shortcuts manage windows without application-specific
  configuration.
- The phone can remain available as REDMAGIC's touchpad and text-input panel.
- Fullscreen applications and in-app fullscreen video use the entire external
  display.

MagicDesk does not emulate Android applications, host them inside custom views,
or replace Android's task organizer. Applications remain ordinary Android
tasks managed by Android's ActivityTaskManager and WindowOrganizer services.

## Highlights

### Desktop workspace

- Launch applications in Windowed or Fullscreen mode.
- Keep multiple overlapping windows visible and switch exact tasks from the
  taskbar or with `Alt+Tab`.
- Send an existing task between the phone and active external desktop from its
  context menu.
- Snap windows left or right, maximize them above the taskbar, or enter true
  fullscreen.
- Pin applications to the taskbar or place shortcuts on the desktop.
- Show files from a user-selected folder without copying or deleting them.
- Preserve one selected application and the last visible freeform window layout
  across Show Desktop operations.
- Store DPI, pins, shortcuts, folder access, and desktop settings separately for
  each external monitor.
- Use the phone's current static wallpaper, center-cropped for the external
  display.

### Desktop controls

- Start menu with application search and keyboard navigation.
- Open Tasks view with exact-task focus and close controls.
- Right-click context menus in MagicDesk and ordinary applications.
- Notification center with unread state, actions, dismissal, and transient
  notification popups.
- Calendar panel, battery and charging state, active keyboard-layout indicator,
  phone-screen control, and screenshot capture.
- Media-volume, connected audio-output, and REDMAGIC Touch Panel controls in
  the desktop Tools panel.
- Stock REDMAGIC bypass-charging control in Root or Shizuku mode, with the
  vendor service retaining its normal safety and disconnect handling.
- Capability-probed REDMAGIC fan, liquid-pump, temperature, and RPM monitoring
  in Root mode, with manual profiles and an optional temperature-driven fan
  curve.
- Automatic external-desktop startup and window-layout restoration through
  `Win+D`.
- REDMAGIC Touch Panel launch from MagicDesk's persistent phone notification.

### Physical input

- Native key repeat and physical keyboard layouts on the external display.
- `Ctrl+Space` cycles through layouts configured in Android, in system order.
- In Root or Shizuku mode on the external desktop, `Alt+Tab` bypasses
  REDMAGIC's broken system Recents path while ordinary `Tab`, `Shift+Tab`, and
  `Ctrl+Tab` remain normal application input.
- Right click reaches Chrome, Firefox, MagicDesk, and other applications instead
  of being converted to Android Back by REDMAGIC firmware.
- Mouse hot-plug and multiple external keyboard or touchpad devices are handled
  without restarting MagicDesk.

## Compatibility

MagicDesk is intentionally REDMAGIC/ZTE-specific.

**Currently verified:**

- REDMAGIC 11 Pro (`NX809J`)
- Android 16
- Firmware fingerprint:
  `REDMAGIC/NX809J-EEA/NX809J:16/BQ2A.250705.001-BP2A.250605.031.A3/20260204.221845:user/release-keys`

**Baseline accepted by Device Setup:**

- A device identifying as ZTE, nubia, or REDMAGIC
- Android 16 / API 36 or newer
- USB-C DisplayPort output and REDMAGIC external-display support

Root is required for the complete REDMAGIC desktop experience. Basic mode can
run without root with reduced task, input, and display control.

Other models and OTA versions are treated as unverified. They can continue
after an explicit warning so that compatibility reports can identify missing or
changed vendor hooks. Passing the baseline check does not guarantee that every
feature works on a different firmware.

See [Compatibility and issue reports](docs/compatibility.md) before reporting a
device-specific failure.

## Getting Started

1. Install the MagicDesk APK from a tagged GitHub Release when available, or
   build it from source.
2. Launch MagicDesk on the phone.
3. Select **Auto**, **Basic**, **Shizuku**, or **Root** runtime privileges and
   choose the Primary, Current, External, or Auto display target.
4. In Root mode, grant root when Device Setup requests it. In Shizuku mode,
   install and start the official Shizuku manager, then grant MagicDesk access.
5. Review the settings Device Setup proposes and confirm the changes. Shizuku
   can enable the freeform and resizable-activity settings directly; Basic
   opens the corresponding Android Developer options.
6. Reboot when requested. Android and WMShell cache part of the desktop
   configuration during startup.
7. A normal launch on the phone opens the compact MagicDesk control panel.
   Select **Open desktop here** to use the full desktop on a tablet or directly
   on the phone.

MagicDesk starts with an external-display DPI of `192`. A different value can
be selected under **Start > Tools** and is remembered per monitor.

Notification access is optional. Grant it from Android settings to enable the
MagicDesk notification center and notification popups.

Use **Tools > Restore previous values** before uninstalling when the Android
desktop settings changed by MagicDesk should be restored. Android does not
notify an application before it is uninstalled.

## Typical Workflow

1. Connect the phone to an external display.
2. Optionally connect a physical keyboard, mouse, or combined touchpad device.
3. Launch MagicDesk on the phone.
4. Grant the selected Root or Shizuku access when requested.
5. Select **Start external desktop** in the phone control panel, or press
   `Win+D` on the physical keyboard.
6. To leave the external desktop, select **Switch to screen mirroring** in the
   phone control panel or desktop Tools. Select **Exit MagicDesk** instead to
   stop MagicDesk and its background services completely.

### Phone Notification

MagicDesk keeps a persistent notification on the phone. It is particularly
useful when no physical keyboard or mouse is connected:

- Tap the MagicDesk notification itself to perform the same context-sensitive
  action as `Win+D`: start the external desktop, show the desktop, or restore
  the previous window layout.
- Tap **Open touchpad** to launch or reopen REDMAGIC Touch Panel on the phone
  and control the external display from the touchscreen.

## Keyboard Shortcuts

| Shortcut | Action |
| --- | --- |
| `Win+D` | Start the external desktop, show it, or restore the previous window layout |
| `Win+Up` | Move the active task to true fullscreen |
| `Win+Down` | Restore fullscreen/maximized task to a window; press again to minimize |
| `Win+Left` / `Win+Right` | Snap the active task to either half of the desktop |
| `Alt+Tab` / `Alt+Shift+Tab` | Switch forward or backward through real Android tasks |
| `Alt+F4` | Close the active task |
| `Win+Backspace` | Send Android Back to the external display |
| `Win+L` | Lock the phone |
| `Win+N` | Toggle the notification center |
| `Win+Print Screen` | Save the external display under `Pictures/Screenshots` |
| `Ctrl+Space` | Select the next configured physical-keyboard layout |
| `Win+/` | Show all MagicDesk shortcuts |
| `Escape` | Act as normal Escape in the active app; also dismiss MagicDesk panels and cross-application transient UI |

The unmodified Win key is deliberately unused. Root and Shizuku both provide
global desktop shortcuts in Console Mode. Shizuku keeps `Win+L` on Android's
normal input path because locking the phone is a Root-only operation; use the
phone's lock control instead. The taskbar language indicator cycles the same
configured layouts as `Ctrl+Space`. **Screenshot** captures the external
display without leaving the Tools panel in the image.

## Phone And External Display Controls

The phone control panel provides the daily session actions without loading the
desktop application catalog:

- Open the full desktop on the phone or tablet display
- Start the external desktop or switch to screen mirroring
- Open REDMAGIC Touch Panel
- Wake or dim the phone display
- Device Setup, Diagnostics, and clean MagicDesk exit

The desktop taskbar Tools panel additionally provides:

- Start the external desktop or switch to screen mirroring
- Open REDMAGIC Touch Panel
- Open the phone control panel
- Wake or dim the phone display
- External-display DPI selection
- External-display screenshot
- Media volume, mute, output monitoring, and sound settings
- REDMAGIC bypass charging when supported by the stock firmware
- REDMAGIC CPU/GPU/skin/battery temperatures, fan RPM, fan profiles, and
  liquid-pump profiles when compatible control nodes are detected
- Device Setup and Diagnostics
- Optional Kernel Fixes entry
- Clean MagicDesk exit

**Exit MagicDesk** stops its foreground service and input watcher, restores
the physical mouse mapping and phone-side services it temporarily changed, and
returns the phone to its normal launcher state. If MagicDesk changed the fan
or pump, it independently restores each affected subsystem to the values
captured before its first write. The same restoration runs when the runtime
stops; after an interrupted process, any remaining baseline is recovered at
the next manual MagicDesk start.

## Privileges And Trust

Root remains necessary for the complete feature set because Android does not
expose low-level physical input control and all REDMAGIC hardware hooks to an
ordinary third-party application. Basic mode never invokes `su` and keeps the
desktop shell, public application launching, desktop content, notifications,
and calendar with explicit limitations. Strict Shizuku mode uses the official
Shizuku UserService API. A server started through ADB or wireless debugging
runs MagicDesk commands as Android shell UID 2000 and enables REDMAGIC Console
Mode startup, Touch Panel launch, exact task observation, freeform/fullscreen
window operations, display density, screenshots, phone-screen dimming, bypass
charging, and physical-keyboard layout control from both `Ctrl+Space` and the
taskbar.
System WMShell captions require device provisioning unavailable to a clean
Shizuku-only installation; taskbar window controls remain available. Shizuku
also corrects REDMAGIC's physical-right-button-to-Back conversion through a
lifecycle-bound virtual mouse bridge and provides global desktop shortcuts
through an equivalent virtual-keyboard bridge. It cannot suppress Nubia's
phone-side text-input panel, so focusing a text field can wake a phone dimmed
in Shizuku mode. Fan/pump controls, kernel fixes, and `Win+L` remain Root-only.

The trust boundaries are deliberately narrow:

- The complete source and CI workflow are reviewable under the MIT license.
- The main APK declares no Internet permission.
- Shizuku is never installed or started by MagicDesk. The user installs the
  official manager, starts its server, and grants MagicDesk separately.
- MagicDesk changes only the documented desktop settings accepted in Device
  Setup and stores their previous values for restoration.
- The system `ShellTaskOrganizer` remains the only task organizer.
- Native input helpers `libmagicdesk_mouse_remap.so`,
  `libmagicdesk_uinput_bridge.so`, and `libmagicdesk_keyboard_bridge.so` are
  rebuilt from their C sources in every CI build; no prebuilt helper is checked
  in.
- The main APK contains no kernel module or kernel-module loader.
- Device and firmware mismatches are reported through structured Diagnostics
  codes instead of silently assuming compatibility.

The detailed root commands, vendor interfaces, lifecycle, and cleanup behavior
are documented in [Architecture](docs/architecture.md). The current runtime
and display boundaries are documented in
[Privilege and display modes](docs/privilege-modes.md).

## Optional Kernel Fixes

`MagicDesk Kernel Fixes` is a separately installed and separately identifiable
APK. MagicDesk shows its Tools entry only when the add-on has the expected
package and the same signing certificate as the main application.

The current add-on contains a reviewed REDMAGIC 11 Pro DisplayPort recovery
module for VITURE 3D EDID transitions. It validates the exact kernel, stock DP
driver, and packaged module before asking for confirmation and invoking
`insmod`. It is not required for the MagicDesk desktop.

The module is never compiled by normal Android CI. Its source, validated binary,
checksums, guarded rebuild procedure, and recovery boundary are documented in
[VITURE XR resolution fix](docs/xr-resolution-fix.md).

## Diagnostics And Issues

Open **Tools > Diagnostics** to generate a copyable compatibility report. It
contains:

- device, firmware, Android, and MagicDesk versions
- display and external-input information
- desktop settings and capability probes
- recent structured MagicDesk errors
- MagicDesk-only logcat entries

The report intentionally excludes user files, accounts, notification contents,
and the installed-application list. Fatal crashes are retained for the next
report.

Include this report, reproduction steps, and whether the problem survives a
reboot when filing an issue.

## Build

Install JDK 17, Android SDK platform/build-tools 37, and Android NDK r27 or
newer. If Gradle cannot locate the SDK, create an untracked
`local.properties`:

```properties
sdk.dir=/absolute/path/to/android-sdk
```

Build both debug APKs:

```sh
./gradlew :app:assembleDebug :kernel-fixes:assembleDebug
```

On Termux, install `clang`; the build uses `$PREFIX/bin/clang`. On conventional
Linux, set `ANDROID_NDK_HOME`. The native input helpers are always compiled
from `native/magicdesk_mouse_remap.c` and
`native/magicdesk_uinput_bridge.c`.

## Releases And Signing

GitHub Actions lints and builds both modules on every change. A `v*` tag runs
the signed release workflow, verifies APK contents and matching certificates,
publishes SHA-256 checksums, and creates a GitHub Release. The kernel-fixes APK
remains optional.

Official release APKs use this certificate SHA-256 fingerprint:

```text
3A:F3:FE:F8:95:AC:BC:9C:B7:7B:FD:BB:7E:91:79:42:
95:70:72:14:97:E3:6E:C1:E4:19:68:C9:4B:52:99:50
```

Maintainer signing setup and encrypted CI secret names are described in
[Architecture](docs/architecture.md#build-and-release-boundaries).

## Technical Documentation

- [Architecture](docs/architecture.md)
- [Fullscreen transitions](docs/fullscreen-transitions.md)
- [Compatibility and issue reports](docs/compatibility.md)
- [Deferred validation backlog](docs/testing-backlog.md)
- [VITURE XR resolution fix](docs/xr-resolution-fix.md)

## Project

- Version: 1.0
- Main package: `io.github.mekhontsev.magicdesk`
- Optional add-on package: `io.github.mekhontsev.magicdesk.kernel`
- Minimum SDK: 36
- Target SDK: 36
- License: [MIT](LICENSE)

Samsung DeX is a trademark of Samsung Electronics. Its name is used here only
to describe the desktop-product category and interaction model.
