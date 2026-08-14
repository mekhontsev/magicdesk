# MagicDesk

MagicDesk is an open-source, DeX-style desktop environment for Android 15 and
newer. It turns a phone, tablet, or Android-reported secondary display into a
practical desktop workspace with native Android windows, a taskbar, Start menu,
desktop shortcuts, global keyboard controls, notifications, and optional
phone-based touchpad support.

MagicDesk started as the RedMagic counterpart to Samsung DeX and retains deep
integration with compatible RedMagic firmware. It is not a port of DeX and is
not affiliated with Samsung. The common desktop builds on Android's own task,
window, and external-display APIs; focused platform drivers add capabilities
that are not part of standard Android.

The compatibility goal is to support as many capable devices and firmware
versions as practical with one MagicDesk APK and one codebase. Runtime
capability probes and platform drivers isolate vendor differences instead of
creating separate device builds or forks.

> **Development note:** MagicDesk is a vibe-coded project, built primarily
> through iterative AI-assisted development and hands-on testing on real
> Android hardware. Its Shizuku integration and optional undocumented vendor
> interfaces make independent source review especially important.

> **Project status:** MagicDesk is under active development. The current
> firmware verification is limited to the RedMagic 11 Pro profile listed below.

![MagicDesk running terminal and graphical applications in native desktop windows with the calendar panel open](docs/images/magicdesk-desktop.png)

![MagicDesk running Termux and Firefox in overlapping native windows with the Hardware panel open](docs/images/magicdesk-multitasking.png)

## Why MagicDesk

Many Android devices can drive a secondary display, but their stock interface
does not provide the complete desktop workflow available in Samsung DeX.
MagicDesk supplies that missing shell while continuing to use native Android
tasks and the projection transport provided by the device firmware.

The result is a familiar desktop model:

- Android applications run in overlapping, resizable system windows with
  native WMShell decorations.
- A persistent taskbar tracks real Android tasks and pinned applications.
- Start, freely positioned desktop shortcuts, a global desktop folder, Android
  widgets, task switching, and Show Desktop provide normal mouse-driven
  navigation.
- DeX-style global shortcuts manage windows without application-specific
  configuration.
- On supported firmware, the phone can remain available as a touchpad and
  text-input panel.
- Fullscreen applications and in-app fullscreen video use the entire external
  display.

MagicDesk does not emulate Android applications, host them inside custom views,
or replace Android's task organizer. Applications remain ordinary Android
tasks managed by Android's ActivityTaskManager, WindowOrganizer, and WMShell.

## MagicDesk And Samsung DeX

This comparison is deliberately scoped. The MagicDesk column describes
behavior verified on a RedMagic 11 Pro (`NX809J`) running Android 16 and the
firmware build listed under **Currently verified**. Other RedMagic devices and
OTA versions may behave differently. Samsung DeX capabilities also vary by
Galaxy device and One UI version.

| Capability | MagicDesk on the verified RedMagic 11 Pro | Samsung DeX |
| --- | --- | --- |
| Wired external desktop | Verified through USB-C DisplayPort and RedMagic Console Mode | Built into supported Galaxy devices |
| Wireless desktop | Verified through the stock SmartCast/Miracast picker and the common MagicDesk desktop session | Supported on compatible Miracast displays |
| Desktop on the device display | Runs the same desktop implementation directly on the phone or a tablet | Standalone DeX is limited to selected Galaxy tablets and Z TriFold devices |
| Native overlapping windows | Android freeform tasks with system WMShell captions | Native DeX windows |
| Window management | Resize, snap, maximize, minimize, true fullscreen, Show Desktop, exact task switching, and proportional window-layout restoration across displays | Resize, arrange, minimize, maximize, fullscreen, and task switching |
| Application launch policy | Explicit Auto, Windowed, and Fullscreen modes; Auto remembers the last explicit mode and window position | Resizable or fixed-size mode according to application compatibility |
| Keyboard and mouse | Physical layouts, repeat, application right click, hot-plug, and DeX-style global shortcuts | Integrated keyboard, mouse, and global shortcut support |
| Phone touchpad | One MagicDesk touchpad for wired and wireless sessions, with drag, right click, scrolling, text input, and built-in gesture help | Integrated DeX touchpad |
| Notifications and settings | Desktop notification center plus a MagicDesk System panel; not a complete Android Quick Settings replacement | System-integrated notifications and Quick Settings |
| Android widgets | Native widgets with placement, resize, and configuration | Desktop widgets are not currently supported |
| Desktop files | A real `/storage/emulated/0/Desktop` directory with file and folder operations | File workflows are primarily provided through My Files |
| Cross-app drag and drop | Global Android file drag-and-drop between the desktop and compatible application windows | Application drag-and-drop where supported |
| Capture | Screenshots and configurable recording of the selected display with internal audio | Samsung system screenshot and screen-recording tools; availability varies by device and software |
| Display controls | Sink-reported output modes, refresh rate, per-monitor DPI, identification, and Fill display | System-managed output behavior with device-dependent options |
| Device controls | RedMagic bypass charging, cooling fan, liquid pump, and temperature controls | No equivalent RedMagic hardware controls |
| Multiple workspaces | Deliberately not implemented | Up to four workspaces on selected Android 16 / One UI 8 devices |
| Setup and support | Open source; requires Shizuku, Device Setup, and one reboot; currently experimental and firmware-specific | Proprietary, built into supported Galaxy firmware, and product-supported by Samsung |

Samsung documents its current [wired, wireless, and standalone DeX
modes](https://www.samsung.com/us/support/answer/ANS10010217/), [keyboard and
mouse shortcuts](https://www.samsung.com/us/support/answer/ANS10003477/), and
[desktop widget limitation](https://www.samsung.com/us/support/answer/ANS10001972/).
MagicDesk aims for the same everyday Android desktop workflow, not a claim of
complete product parity: DeX remains more mature and deeply integrated, while
MagicDesk adds RedMagic-specific controls, desktop files, widgets, diagnostics,
and selected-display recording.

## Highlights

### Desktop workspace

- Launch applications in Windowed or Fullscreen mode.
- Keep multiple overlapping windows visible and switch exact tasks from the
  taskbar or with `Alt+Tab`.
- Minimize windowed applications without pausing them, so media and background
  work can continue behind the desktop.
- Send an existing task between the phone and active external desktop from its
  context menu without restarting the application.
- Snap windows left or right, maximize them above the taskbar, or enter true
  fullscreen.
- Pin applications to the taskbar or place shortcuts on the desktop.
- Use `/storage/emulated/0/Desktop` as the normal desktop filesystem: create,
  open, rename, and delete files or folders directly from the desktop, and
  drag files between the desktop and application windows that support
  Android's global drag-and-drop protocol.
- Add native Android widgets, move them on the desktop, and resize supported
  providers from their context menu.
- Preserve the last visible freeform window layout across Show Desktop.
- Keep desktop files, widgets, pins, shortcuts, and recent applications global
  across displays. Desktop-item and application-window positions use relative
  coordinates so the layout adapts to each display, while output mode, Fill
  display, and DPI remain per-monitor settings.
- Remember an application's explicit Windowed or Fullscreen choice and its last
  freeform position for subsequent Auto launches.
- Use the phone's current static wallpaper or choose a custom desktop wallpaper,
  center-cropped for the active display.

Desktop configuration is stored as an atomic, human-readable file at
`/storage/emulated/0/Desktop/.magicdesk/desktop.json`; an optional custom
wallpaper is stored beside it. The hidden directory is not shown as a desktop
item. Runtime state, diagnostics, and Recent history remain private to the app.
Android widget bindings remain system-managed and scoped to the installed app
and Android user; neither kind of state is written into the desktop folder.

### Desktop controls

- Start menu with application search and keyboard navigation.
- Open Tasks view with exact-task focus and close controls.
- Right-click context menus in MagicDesk and ordinary applications.
- Notification center with unread state, actions, dismissal, and transient
  notification popups.
- Calendar panel, battery state, active keyboard-layout indicator, screenshots,
  and optional phone-screen control.
- Configurable selected-display recording with internal audio when the
  firmware exposes the required capture and audio sources.
- Connected-display identification and per-monitor DPI. Platform extensions
  may also expose output resolution, refresh-rate selection, and **Fill
  display** for sinks that otherwise add letterboxing.
- Media-volume and connected audio-output controls, plus phone-touchpad control
  when absolute pointer positioning is available.
- Stock RedMagic bypass-charging, cooling-fan, liquid-pump, and temperature
  controls through the vendor's own policy services.
- Automatic external-desktop startup and window-layout restoration through
  `Win+D`.

### Physical input

The standard Android profile leaves system keyboard and mouse routing intact.
On firmware that misroutes external input, a platform extension can enable
MagicDesk's full input bridge to provide the following behavior:

- Native key repeat and physical keyboard layouts on the external display.
- `Ctrl+Space` cycles through layouts exposed to Android by the active input
  method as enabled keyboard subtypes, in system order. An IME that exposes
  only one subtype cannot provide system-wide physical-keyboard switching.
- `Alt+Tab` bypasses RedMagic's broken system Recents path while ordinary
  `Tab`, `Shift+Tab`, and `Ctrl+Tab` remain normal application input.
- Right click reaches Chrome, Firefox, MagicDesk, and other applications
  instead of being converted to Android Back by RedMagic firmware.
- Mouse hot-plug and multiple external keyboard or touchpad devices are
  handled without recreating the virtual devices or restarting applications.

## Requirements

MagicDesk requires:

- a device running Android 15 / API 35 or newer;
- the official Shizuku application with its server running;
- a one-time Device Setup and reboot to enable Android desktop windowing.

An external desktop requires either:

- a wired display output exposed to Android as a secondary display; or
- a system Miracast/wireless-display interface and a compatible receiver.

USB-C DisplayPort output is therefore not mandatory. Availability of wired and
wireless projection, the ability to host application tasks on the reported
display, physical input routing, and native window behavior depend on the
device firmware. **Open desktop here** runs the same desktop implementation on
the device display without an external display.

On the standard Android profile, MagicDesk opens an already connected
secondary display directly and leaves connection, disconnection, mirror mode,
and output timing to system settings. Compatible RedMagic firmware additionally
provides managed Console/SmartCast transitions, output controls, phone-screen
control, absolute pointer positioning, and hardware controls. Unsupported
optional integrations remain disabled instead of blocking the desktop.

MagicDesk does not run in a reduced fallback mode when Shizuku is stopped or
permission is denied. All privileged operations use the same Shizuku
UserService path, keeping runtime behavior predictable and reviewable.

**Currently verified:**

- RedMagic 11 Pro (`NX809J`)
- Android 16
- Firmware fingerprint:
  `REDMAGIC/NX809J-EEA/NX809J:16/BQ2A.250705.001-BP2A.250605.031.A3/20260204.221845:user/release-keys`

Other devices and OTA versions are treated as unverified. They can continue
after an explicit warning so compatibility reports can identify missing,
changed, or vendor-specific capabilities. Passing the baseline check does not
guarantee every feature works on different firmware.

See [Compatibility and issue reports](docs/compatibility.md) before reporting a
device-specific failure.

### Install and start Shizuku

1. Install Shizuku from the
   [official GitHub Releases](https://github.com/RikkaApps/Shizuku/releases).
2. Start its server using one of the methods supported by the official app.
3. For the standard wireless-debugging method, enable **Developer options**:
   open **Settings > About phone** and tap **Build number** seven times, then
   enable **Wireless debugging**. In Shizuku, select pairing through Wireless
   debugging. In Android's
   **Wireless debugging** screen, choose **Pair device with pairing code**,
   enter that code through the Shizuku notification, then press **Start** in
   Shizuku.
4. Confirm that Shizuku reports a running server before opening MagicDesk.
   Wireless-debugging and ADB startup must be repeated after every reboot;
   root-started Shizuku follows its own startup configuration.

Use the [official Shizuku setup guide](https://shizuku.rikka.app/guide/setup/)
for device-specific pairing and startup details.

## Getting Started

1. Complete **Install and start Shizuku** above and confirm that its server is
   running. Starting it from a computer through ADB is also supported.
2. Install MagicDesk from a tagged
   [GitHub Release](https://github.com/mekhontsev/magicdesk/releases) or build
   it from source.
3. Launch MagicDesk on the phone and allow it through Shizuku.
4. Press **Prepare device**. MagicDesk applies its app-specific permission and
   desktop-windowing configuration through Shizuku.
5. Reboot when requested. Android and WMShell cache part of the desktop
   configuration during startup.
6. Launch MagicDesk manually after reboot. It has no boot receiver and starts
   no service until the user opens it.

Notification access is optional. Grant it from Android settings to enable the
MagicDesk notification center and notification popups.

### Development builds

Every non-documentation push to `main` produces a release-signed development
APK. Download the latest build directly from the rolling prerelease:

[Download MagicDesk development build](https://github.com/mekhontsev/magicdesk/releases/download/development/MagicDesk-development.apk)

The link is public and remains the same after each build. Development builds
use the same signing certificate as tagged releases, but contain unreleased
code and may be less stable. The
[development release page](https://github.com/mekhontsev/magicdesk/releases/tag/development)
also provides the exact commit, checksum, and CI run. Per-run artifacts remain
available for 30 days on the
[Android CI workflow page](https://github.com/mekhontsev/magicdesk/actions/workflows/ci.yml?query=branch%3Amain)
for signed-in GitHub users.

The build number and short commit ID are included in the version shown by
**About** and in compatibility reports, for example
`1.6.1-dev.81.d552cc5`.

### Uninstalling

Before uninstalling MagicDesk, open **Device Setup > Restore defaults**, then
restart the phone. Android does not allow MagicDesk to perform this cleanup
automatically while it is being uninstalled.

If MagicDesk has already been removed, reinstall it, grant Shizuku access, run
**Restore defaults**, and restart. The action does not depend on saved setup
history. It removes the desktop-windowing overrides and resets the primary
display size, density, and scaling to platform defaults.

### Typical workflow

1. Optionally connect a physical keyboard, mouse, or combined touchpad device.
2. Launch MagicDesk on the phone.
3. For a wired session, connect a USB-C display. When the selected platform
   exposes output controls, optionally select an **Output mode** and enable
   **Fill display**, then select **Start external desktop** or press `Win+D`.
4. For a wireless session with no display connected, select **Start external
   desktop**, then choose a Miracast receiver in the system wireless-display
   interface. MagicDesk starts the desktop when Android reports the display.
5. Select **Close desktop** to close the MagicDesk session. A platform-managed
   transport also returns to mirroring or disconnects; a direct Android
   secondary display remains connected under system control. Select **Exit
   MagicDesk** to stop MagicDesk and its background services completely.

The initial external-display DPI is selected from the display resolution; for
1920-pixel-wide displays the recommendation is `160`. The DPI can be adjusted
in the **System** panel, opened from the taskbar battery indicator or with
`Win+Q`, and is remembered per monitor. **Reset** removes the MagicDesk density
override.

### Phone notification

MagicDesk keeps a persistent notification while it is running. It is useful
when no physical keyboard or mouse is connected:

- Tap the notification itself to perform the same context-sensitive action as
  `Win+D`: start the external desktop, show it, or restore the previous layout.
- Tap **Open touchpad** to launch or reopen the MagicDesk phone-side touchpad
  for the active wired or wireless desktop.

The full desktop can also run on display 0 through **Open desktop here**, which
supports tablets and allows development without an external monitor.

## Keyboard Shortcuts

| Shortcut | Action |
| --- | --- |
| `Win+D` | Start the external desktop, show it, or restore the previous window layout |
| `Win+Up` | Move the active task to true fullscreen |
| `Win+Down` | Restore fullscreen/maximized task to a window; press again to minimize |
| `Win+Left` / `Win+Right` | Snap the active task to either half of the desktop |
| `Alt+Tab` / `Alt+Shift+Tab` | Switch forward or backward through real Android tasks |
| `Alt+F4` | Close the active task |
| `Win+Backspace` | Send Android Back to the active display |
| `Win+L` | Lock the phone |
| `Win+N` | Toggle the notification center |
| `Win+Q` | Toggle the System panel |
| `Win+Print Screen` | Save the active display under `Pictures/Screenshots` |
| `Win+Shift+Print Screen` | Start or stop desktop recording with internal audio; save under `Movies/MagicDesk` |
| `Ctrl+Space` | Select the next configured physical-keyboard layout |
| `Win+/` | Show all MagicDesk shortcuts |
| `Escape` | Act as normal Escape in the active app and dismiss transient cross-application UI |

The unmodified Win key is deliberately unused. The taskbar language indicator
cycles the same configured layouts as `Ctrl+Space`.

## Privileges And Trust

MagicDesk uses the official `dev.rikka.shizuku` UserService API for external
desktop sessions, native task control, display density, screenshots,
phone-screen dimming, locking, wallpaper access, vendor cooling and bypass
charging, and physical input routing. MagicDesk does not independently acquire
elevated privileges.

The trust boundaries are deliberately narrow:

- The complete source and CI workflow are reviewable under the MIT license.
- The main APK declares no Internet permission and contains no independent
  privilege-escalation path, kernel module, or kernel-module loader.
- Shizuku is never downloaded, installed, or started by MagicDesk. The user
  controls the official manager and grants MagicDesk separately.
- The connected UserService identity is included in Diagnostics; every
  supported Shizuku startup method uses the same commands and feature set.
- The diagnostic Console executes only commands entered and confirmed by the
  user. Those commands are not restricted to MagicDesk's internal allowlists
  and have the effective privileges displayed by the Console.
- MagicDesk changes only the desktop settings required by the selected
  platform driver. Every platform uses the two documented Android windowing
  settings; supported Nubia/REDMAGIC firmware additionally uses two documented
  persistent properties. **Restore defaults** removes those overrides instead of
  guessing firmware values. The RedMagic property writer accepts only those
  two hardcoded boolean/absent properties.
- Projection, phone-screen and launcher integration, absolute-pointer access,
  optional firmware app entry points, and vendor diagnostics are selected
  through the same platform-driver boundary. The standard Android profile does
  not probe or invoke Nubia/REDMAGIC interfaces. ZTE-branded devices are not
  assumed to use Nubia firmware and therefore start with the standard profile.
- The system `ShellTaskOrganizer` remains the only task organizer.
- `libmagicdesk_uinput_bridge.so` and
  `libmagicdesk_keyboard_bridge.so` are rebuilt from their C sources in every
  build; no prebuilt input helper is checked in.
- Lifecycle-bound streams make input and display guards fail open: losing the
  APK or Shizuku releases grabbed devices and restores display power.
- Device and firmware mismatches are reported through structured Diagnostics
  codes instead of silently assuming compatibility.

Implementation details are in [Architecture](docs/architecture.md), the
runtime and display contract is in
[Shizuku and display modes](docs/privilege-modes.md), and verified firmware
behavior is recorded in the
[Nubia vendor interface audit](docs/nubia-vendor-audit.md).

## Diagnostics And Issues

Open **Tools > Diagnostics** to generate a copyable compatibility report. It
contains device and firmware identity, display/input information, desktop
settings, capability probes, recent structured MagicDesk errors, and
MagicDesk-only logcat entries. It excludes user files, accounts, notification
contents, and the installed-application list.

**Run desktop self-test** is a manual black-box check for contributors and
compatibility reports. With all MagicDesk desktop sessions closed, choose a
simulated, connected external, or phone display. The same bounded test adapts
its window positions to the selected viewport and accepts the minimum window
size enforced by that display's WMShell implementation. It exercises the production
desktop, freeform, fullscreen, minimize/restore, taskbar geometry, native
caption and resize input, native left/right placement, and keyboard focus
switching between two windows. It also recreates the desktop Activity and
checks hidden Android and RedMagic APIs that can be inspected safely.

The external target uses an already connected HDMI or Miracast display. If no
external display is present, MagicDesk opens the platform's wireless-display
settings and asks you to connect one before running the test again. A managed
wired display temporarily switched from mirror mode is restored afterward;
an existing direct secondary-display connection is left connected. Hardware
keyboard, mouse, and phone touchpad input remain explicitly **NOT TESTED**
because the automated test injects its own input. Native mouse resize-cursor
selection is checked when WMShell exposes a transition trace.

The simulated target owns a temporary 1920x1080 display through a
lifecycle-bound Shizuku stream. Its setting is restored when the test finishes
or its process disconnects. A one-shot shell task observer records each test
window's first front-state and reports transient fullscreen launches instead
of mistaking a later corrected state for the initial one.

Debug builds expose the same lifecycle check as an instrumentation regression:

```sh
am instrument -w --user 0 \
  io.github.mekhontsev.magicdesk/.DesktopLifecycleInstrumentation
```

**Diagnostics > Console** runs one-off shell commands through the authorized
Shizuku service. It shows the effective UID, requires confirmation before its
first command, and does not retain command history.

Include the report, exact reproduction steps, and whether the problem survives
a reboot when filing an issue.

## Build

Install JDK 17 or newer, Android SDK platform/build-tools 37, and Android NDK
27.3.13750724. If Gradle cannot locate the SDK, create an untracked
`local.properties`:

```properties
sdk.dir=/absolute/path/to/android-sdk
```

Build the debug APK:

```sh
./gradlew :app:assembleDebug
```

To exercise the Standard Android platform driver on Nubia/REDMAGIC development
hardware, build a debug APK with an explicit platform override:

```sh
./gradlew :app:assembleDebug -PMAGICDESK_PLATFORM_OVERRIDE=android
```

The override is ignored by release builds and is reported in Compatibility
Diagnostics. It changes only platform-driver selection; it does not use a
separate product flavor or source set.

On Termux, install `clang`; the build uses `$PREFIX/bin/clang`. On desktop
systems, Gradle finds a side-by-side NDK through the Android SDK;
`ANDROID_NDK_HOME` can override it. The native input helpers are compiled from
`native/magicdesk_uinput_bridge.c` and
`native/magicdesk_keyboard_bridge.c`.

See [Contributing](CONTRIBUTING.md) for IDE setup and verification commands.

## Releases And Signing

GitHub Actions lints and builds MagicDesk on every change. A `v*` tag runs the
signed release workflow, verifies the APK contents and certificate, publishes
its SHA-256 checksum, and creates a GitHub Release.

Official release APKs use this certificate SHA-256 fingerprint:

```text
3A:F3:FE:F8:95:AC:BC:9C:B7:7B:FD:BB:7E:91:79:42:
95:70:72:14:97:E3:6E:C1:E4:19:68:C9:4B:52:99:50
```

Maintainer signing setup and encrypted CI secret names are described in
[Architecture](docs/architecture.md#build-and-release-boundaries).

## Technical Documentation

- [Architecture](docs/architecture.md)
- [Shizuku and display modes](docs/privilege-modes.md)
- [Fullscreen transitions](docs/fullscreen-transitions.md)
- [Compatibility and issue reports](docs/compatibility.md)
- [Deferred validation backlog](docs/testing-backlog.md)
- [Contributing and IDE setup](CONTRIBUTING.md)

## Project

- Author: [Dmitry Mekhontsev](https://github.com/mekhontsev)
- Main package: `io.github.mekhontsev.magicdesk`
- Minimum SDK: 35
- Target SDK: 36
- License: [MIT](LICENSE)

Samsung DeX is a trademark of Samsung Electronics. Its name is used here only
to describe the desktop-product category and interaction model.
