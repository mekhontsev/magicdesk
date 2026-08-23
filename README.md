# MagicDesk

MagicDesk is an open-source workstation environment for Android 15 and newer.
It turns a phone, tablet, or secondary display exposed by Android into a
practical workspace with native Android windows, a taskbar and Start menu,
desktop files and widgets, built-in Files, Console, and Task Manager,
notification controls, display capture, global keyboard controls, and an
optional phone-based touchpad and text-input panel.

Applications remain ordinary Android tasks managed by the system. MagicDesk
organizes those tasks into a coherent desktop and integrates them with the
device's files, displays, input, and authorized Android shell capabilities; it
does not stream applications, embed them in replacement views, or run a
separate guest operating system.

MagicDesk belongs to the same broad Android desktop category as Samsung DeX,
but it is an independent open-source environment rather than a port or clone.
The common implementation builds on Android's task, window, and display APIs;
dedicated platform and SoC drivers add capabilities that are not part of
standard Android.

The compatibility goal is to support as many capable devices and firmware
versions as practical with one MagicDesk APK and one codebase. Runtime
capability probes and platform drivers isolate vendor differences instead of
creating separate device builds or forks.

> **Development note:** MagicDesk is a vibe-coded project, built primarily
> through iterative AI-assisted development and hands-on testing on real
> Android hardware. Its privileged shell integration and optional undocumented
> vendor interfaces make independent source review especially important.

> **Project status:** MagicDesk is under active development. Complete
> maintainer hardware verification is currently limited to the RedMagic 11 Pro
> profile listed below.

![MagicDesk running terminal and graphical applications in native desktop windows with the calendar panel open](docs/images/magicdesk-desktop.png)

![MagicDesk running Termux and Firefox in overlapping native windows with the Hardware panel open](docs/images/magicdesk-multitasking.png)

## Workspace Model

Android already provides applications, system task management, and display
transports. MagicDesk connects those capabilities into a consistent workspace
that can run on the device itself or on a secondary display:

- Android applications use native WMShell windows and can be overlapped,
  resized, snapped, maximized, or made fullscreen.
- A persistent taskbar tracks real Android tasks and pinned applications,
  keeping additional items reachable through an icon-and-name overflow menu.
- Start, freely positioned desktop shortcuts, a global desktop folder, Android
  widgets, task switching, and Show Desktop provide familiar mouse-driven
  navigation.
- Desktop-style global shortcuts manage windows without application-specific
  configuration.
- The phone can remain available as MagicDesk's touchpad and text-input
  control panel.
- Built-in Files, Console, Task Manager, and Settings provide a coherent
  desktop workflow without requiring a separate shell or file manager.
- Optional local automation exposes typed desktop actions, semantic UI
  controls, bounded operation traces, state, events, and visual observation to
  MCP clients and, on Android 16+, a smaller action set
  to authorized system agents through App Functions. Direct Files and Console
  automation has its own disabled-by-default access gate.

## Highlights

### Windows and sessions

- Launch applications in Windowed or Fullscreen mode.
- Keep multiple overlapping windows visible and switch exact tasks from the
  taskbar or with `Alt+Tab`.
- Minimize windowed applications without closing them; applications that
  support background operation can continue behind the desktop.
- Send an existing task between the phone and active external desktop from its
  context menu without restarting the application.
- Snap windows left or right, maximize them above the taskbar, or enter true
  fullscreen.
- Request another task for a compatible application through **New window**.
  MagicDesk Files supports this directly; other Android applications may
  reject the request through their own activity launch mode.
- Pin applications to the taskbar or create standard `.desktop` application
  and quick-action shortcuts.
- Use an application's published quick actions, widget picker, and Android app
  information directly from its MagicDesk context menu.
- Preserve the last visible freeform window layout across Show Desktop.
- Keep live application tasks available when the external desktop is closed,
  then restore their desktop mode and layout when the desktop is started
  again. Tasks that Android or the user closed are never relaunched.
- Remember an application's explicit Windowed or Fullscreen choice and its last
  freeform position for subsequent Auto launches.

### Files and Console

- Use `/storage/emulated/0/Desktop` as the normal desktop filesystem: create,
  open, rename, and delete files or folders directly from the desktop, and
  drag files between the desktop and application windows that support
  Android's global drag-and-drop protocol. Files and folders can also be moved
  directly between the desktop and built-in Files; hold `Ctrl` while starting
  the drag to copy instead.
- Open the built-in **Files** window for the filesystem visible to the
  authorized Android shell identity. It supports path navigation, hidden
  files, sorting, selection, create, rename, permanent delete, copy, cut,
  paste, current-folder filtering, recursive name search, live directory
  updates, properties, external editors, and global file drag-and-drop.
  Conflicting copies receive a numeric suffix instead of silently replacing
  data. Desktop and Files items use the same context menu, activation
  preference, and process-local copy/cut buffer; Properties reports the actual
  owner and mode. Files uses the Android system default application when one
  exists, and its in-window **Open with** dialog can set the same system-wide
  default. Executable `.desktop` entries on the MagicDesk desktop can also
  appear there when their standard `MimeType` list matches the selected file;
  command entries remain explicit one-time choices. Multiple Files windows
  share the same process-local copy/cut buffer.
  Shell copy, move, and delete operations continue if their initiating Files
  window is closed; reopening Files reconnects to progress and cancellation.
  An APK can be installed or updated only after an explicit confirmation.
- Keep [freedesktop `.desktop` folder, website, and application shortcuts](https://specifications.freedesktop.org/desktop-entry/latest/)
  in the Desktop directory or any folder opened by Files. A browser's Android
  Share action can add an HTTP(S) page to the MagicDesk desktop. Android
  application shortcuts retain their full Intent parameters and an executable
  `am start` fallback. For command entries, MagicDesk executes the standard
  `Exec` field in Android shell by default; `Terminal=true` opens the command
  in Console, while `X-MagicDesk-ExecBackend=termux` selects Termux's documented
  command service instead. Executable shortcuts accept dropped files through
  `%f/%F/%u/%U`, and `Path` selects their working directory. The standard
  `MimeType` field can expose such a command in **Open with**. The supported
  fields, launch precedence, window modes, and examples are documented in
  [Desktop Entry files](docs/desktop-entries.md).
- Open the current Files directory in MagicDesk's built-in Console, or hand it
  to Termux when Termux is installed and its documented `RUN_COMMAND` access
  has been enabled. Reopening the same directory returns to its existing named
  Termux session without resetting that session's current state.
- When Termux and Termux:X11 are installed, launching Termux:X11 from
  **Start** also starts or reconnects its X server through Termux's documented
  command API. The viewer remains a normal desktop task with the same window
  placement and launch-mode behavior as other applications. Its startup
  command can be changed in **Settings**. A `.desktop` entry can combine a
  Termux `Exec` command with the Termux:X11 Android package to provide a named
  launch preset without introducing a separate profile database. Creating a
  desktop shortcut for Termux:X11 captures the current command in that file;
  launching its ordinary Start icon continues to use the live Settings value.
- Run a selected `.sh` file in Console from its context menu. Each Console
  window owns an independent interactive Android shell with PTY semantics,
  ANSI colors, scrollback, alternate-screen applications, direct hardware and
  software keyboard input, mouse reporting, selection, copy, and paste.
- Drop Files or Desktop items onto Console to insert safely quoted paths at the
  command cursor. Console can open its current directory in Files or reveal a
  selected output path after shell-side validation. Normal terminal keys,
  including `Tab` and `Ctrl+C`, are delivered directly to the shell or active
  terminal application.

### Desktop layout

- Add native Android widgets, move them on the desktop, and resize supported
  providers from their context menu.
- Keep desktop files, widgets, pins, shortcuts, and recent applications global
  across displays. Desktop-item and application-window positions use relative
  coordinates so the layout adapts to each display, while output mode, Fill
  display, and DPI remain per-monitor settings.
- Leave SmartCast or the system projection UI in control when output mode is
  set to **System / native**; later desktop starts do not overwrite that choice.
- Use the phone's current static wallpaper or set a local image as the custom
  desktop wallpaper directly from MagicDesk Files, center-cropped for the
  active display.

Desktop layout, settings, pins, and per-display configuration are stored as an
atomic, human-readable file at
`/storage/emulated/0/Desktop/.magicdesk/desktop.json`; an optional custom
wallpaper is stored beside it. Folder, website, and application shortcuts are
ordinary `.desktop` files rather than records in that JSON file. The hidden
directory is not shown as a desktop item. Runtime state, diagnostics, and
recent-application history remain private to the app. Android widget bindings
remain system-managed and scoped to the installed app and Android user. Neither
app-private runtime state nor system-managed widget bindings are written into
the desktop folder.

### Desktop controls

- Start menu with unified application, file, MagicDesk setting, and desktop
  action search, plus keyboard navigation. Desktop file results use the same
  bounded, shell-backed search contract as Files; Files retains recursive
  search from any visible directory.
- Open Tasks view with exact-task focus and close controls.
- Open **Task Manager** for running application tasks, live CPU and memory
  indicators, per-task focus and close, explicit force-stop, and a
  lifecycle-bound application log viewer filtered by Android UID.
- Right-click context menus in MagicDesk and ordinary applications.
- Notification center with unread state, actions, dismissal, and transient
  notification popups.
- Calendar panel, battery state, active keyboard-layout indicator, screenshots,
  and optional phone-screen control.
- Configurable selected-display recording with `Auto`, `Microphone`, and
  `No audio` modes. `Auto` uses a platform internal-audio backend when the
  firmware declares one and otherwise records video without sound.
- Connected-display identification and per-monitor DPI. Platform extensions
  may also expose output resolution, refresh-rate selection, and **Fill
  display** for sinks that otherwise add letterboxing.
- Media-volume and connected audio-output controls, plus phone-touchpad control
  when absolute pointer positioning is available.
- A dedicated **Settings** window for persistent behavior, including optional
  taskbar auto-hide, single-click file activation, automatic touchpad startup,
  keeping an active desktop session awake, and local automation access.
- Stock RedMagic bypass-charging, cooling-fan, liquid-pump, and temperature
  controls through the vendor's own policy services.
- Start or reveal the desktop and restore its hidden window layout with
  `Win+D`.

### Physical input

The standard Android profile leaves system keyboard and mouse routing intact.
On firmware that misroutes external input, a platform extension can enable
MagicDesk's full input bridge to provide the following behavior:

- Native key repeat and physical keyboard layouts on the external display.
- `Ctrl+Space` cycles through layouts exposed to Android by the active input
  method as enabled keyboard subtypes, in system order. An IME that exposes
  only one subtype cannot provide system-wide physical-keyboard switching.
- `Alt+Tab` switches exact desktop tasks without entering system Recents, while
  ordinary `Tab`, `Shift+Tab`, and `Ctrl+Tab` remain normal application input.
- Right click reaches applications as a secondary click instead of being
  converted to Android Back.
- Mouse hot-plug and multiple external keyboard or touchpad devices are
  handled without restarting the desktop session or applications.

## Requirements

MagicDesk requires:

- a device running Android 15 / API 35 or newer;
- the official Shizuku application with its server running;
- a one-time Device Setup and reboot to enable Android desktop windowing.

MagicDesk is not restricted to ZTE, nubia, or RedMagic devices. Devices without
a dedicated vendor integration use the Standard Android platform driver; the
available session types still depend on the desktop and display capabilities
exposed by their firmware.

An external desktop requires either:

- a wired display output exposed to Android as a secondary display; or
- a system Miracast/wireless-display interface and a compatible receiver.

USB-C DisplayPort output is therefore not mandatory. Availability of wired and
wireless projection, the ability to host application tasks on the reported
display, physical input routing, and native window behavior depend on the
device firmware. **Open desktop here** runs the same desktop implementation on
the device display without an external display.

On the Standard Android profile, MagicDesk opens an already connected
secondary display directly and leaves connection, disconnection, mirror mode,
and output timing to the system. Compatible RedMagic firmware additionally
provides a **Wireless** action backed by SmartCast, managed projection
transitions, output controls, phone-screen control, absolute pointer
positioning, and hardware controls. Unsupported optional integrations remain
disabled instead of blocking the desktop.

MagicDesk requires one live, authorized Android shell service for privileged
operations. The current APK binds that service through Shizuku; if the
transport is stopped or permission is denied, MagicDesk does not silently
change to an app-UID fallback. This keeps the effective identity and runtime
behavior predictable and reviewable.

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
4. Press **Prepare device**. MagicDesk applies its app-specific permissions and
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
`1.8.1-dev.123.abcdef0`.

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
   **System / native** removes a previously forced Android mode and lets the
   connected display select its native timing again.
4. For a wireless session, connect a Miracast receiver through the system UI
   or, on a supported platform, select **Wireless**. After the
   display appears in Phone Control Panel, select **Start external desktop**.
5. Select **Close desktop** to leave the desktop and preserve its live
   application workspace. Starting a desktop again restores tasks that are
   still alive, including their previous modes, layouts, visibility, and
   stacking order. The same state is retained when an external display is
   disconnected unexpectedly. A
   platform-managed transport also returns to mirroring; a direct Android
   secondary display remains connected under system control. Select **Exit
   MagicDesk** to close MagicDesk windows, clear the saved live session, and
   stop MagicDesk and its background services completely.

The initial external-display DPI is selected from the display resolution; for
1920-pixel-wide displays the recommendation is `160`. The DPI can be adjusted
in the **System** panel, opened from the taskbar battery indicator or with
`Win+Q`, and is remembered per monitor. **Reset** removes the MagicDesk density
override. Persistent MagicDesk behavior is configured in **Settings**, opened
from System, Tools, Phone Control Panel, or with `Win+I`.

### Phone notification

MagicDesk keeps a persistent notification while it is running, providing a
direct route back to its phone-side controls:

- Tap the notification itself to open Phone Control Panel on the phone.
- Tap **Open touchpad** to launch or reopen the MagicDesk phone-side touchpad
  for the active wired or wireless desktop.

The full desktop can also run on display 0 through **Open desktop here**, which
supports tablets and provides a complete workspace without an external monitor.

## Keyboard Shortcuts

| Shortcut | Action |
| --- | --- |
| `Win+D` | Start or reveal the selected desktop, or restore the previous window layout |
| `Win+Up` | Move the active task to true fullscreen |
| `Win+Down` | Restore fullscreen/maximized task to a window; press again to minimize |
| `Win+Left` / `Win+Right` | Snap the active task to either half of the desktop |
| `Alt+Tab` / `Alt+Shift+Tab` | Switch forward or backward through real Android tasks |
| `Alt+F4` | Close the active task |
| `Win+Backspace` | Send Android Back to the active display |
| `Win+L` | Lock the phone |
| `Win+N` | Toggle the notification center |
| `Win+Q` | Toggle the System panel |
| `Win+I` | Open MagicDesk settings |
| `Win+Print Screen` | Save the active display under `Pictures/Screenshots` |
| `Win+Shift+Print Screen` | Start or stop desktop recording with the configured audio mode and save it under `Movies/MagicDesk` |
| `Ctrl+Space` | Select the next configured physical-keyboard layout |
| `Win+/` | Show all MagicDesk shortcuts |
| `Escape` | Act as normal Escape in the active app and dismiss transient cross-application UI |

The unmodified Win key is deliberately unused. The taskbar language indicator
cycles the same configured layouts as `Ctrl+Space`.

## Shell Access and Trust

Privileged MagicDesk operations run under an authorized Android shell identity:
normally UID 2000, the same identity used by `adb shell`, or UID 0 when the
user deliberately supplied a root shell service. The current implementation
uses the official `dev.rikka.shizuku` UserService API as the Binder transport
and lifecycle owner for that identity. Shizuku does not define the Files or
Console feature set; the connected shell identity and firmware permissions do.
MagicDesk does not independently acquire elevated privileges.

The trust boundaries are deliberately narrow:

- The complete source and CI workflow are reviewable under the MIT license.
- The main APK contains no independent privilege-escalation path, kernel
  module, or kernel-module loader. Its optional MCP server is disabled by
  default, binds only to `127.0.0.1`, and requires a private bearer token; the
  APK does not expose a network-facing listener.
- Shizuku is never downloaded, installed, or started by MagicDesk. The user
  controls the official manager and grants MagicDesk separately.
- The connected shell identity is included in Diagnostics; every supported
  startup method uses the same commands and capability checks.
- The built-in Console executes only commands entered and run by the user.
  Those commands are not restricted to MagicDesk's internal allowlists and
  have the effective privileges displayed by the Console.
- Built-in Files performs typed filesystem operations under the same shell
  identity. Applications opened from Files receive only a temporary URI for
  the selected file; they do not inherit the shell identity or its filesystem
  access.
- Task Manager uses the existing task repository rather than a second task
  parser. Application log streams are explicitly opened by the user, filtered
  to the selected application UID, bounded in memory, and closed with their
  viewer window.
- MagicDesk changes only the desktop settings required by the selected
  platform driver. Every platform uses the two documented Android windowing
  settings; supported Nubia/REDMAGIC firmware additionally uses two narrowly
  scoped persistent system properties. **Restore defaults** removes those
  overrides instead of guessing firmware values. The RedMagic property writer
  accepts only those two hardcoded boolean/absent properties.
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
  APK or shell service releases grabbed devices and restores display power.
- Device and firmware mismatches are reported through structured Diagnostics
  codes instead of silently assuming compatibility.

Implementation details are in [Architecture](docs/architecture.md), the
runtime and display contract is in
[Shell access and display modes](docs/privilege-modes.md), and verified firmware
behavior is recorded in the
[Nubia vendor interface audit](docs/nubia-vendor-audit.md). MCP and Android
App Functions are described in [Automation](docs/automation.md).

## Diagnostics and Issues

Open **Tools > Diagnostics** to generate a copyable compatibility report. It
contains device and firmware identity, display/input information, desktop
settings, capability probes, recent structured MagicDesk errors, and
MagicDesk-only logcat entries. It excludes user files, accounts, notification
contents, and the installed-application list.

**Run desktop self-test** is a manual black-box check for contributors and
compatibility reports. The phone must be awake and unlocked, with all
MagicDesk desktop sessions closed. Choose a simulated, connected external, or
phone display. The same bounded test adapts its window positions to the
selected viewport and accepts the minimum window size enforced by that
display's WMShell implementation. It exercises the production desktop,
freeform, fullscreen, minimize/restore, taskbar geometry, native
caption and resize input, native left/right placement, and keyboard focus
switching between two windows. It verifies that Alt+Tab between two true
fullscreen tasks never converts either task to freeform, then restores and
closes one task while checking the fullscreen survivor's real input focus and
release from the now-unnecessary temporary fullscreen parent. It also verifies
direct fullscreen placement in the phone session and system-Back removal
without exposing Android HOME, recreates the desktop Activity, and checks
available hidden Android, platform, and SoC APIs that can be inspected safely.

While those stages run, an event-driven task-stack guard records the hierarchy
reported by Android callbacks. It detects intermediate display or windowing-
mode detours, a freeform task reaching the phone, an unexpected HOME task,
desktop-host visibility loss, and a gap where neither fullscreen task is
visible. Capability-gated in-memory pixel probes separately check the desktop
background during window launches and later task transitions, plus native
caption rendering, without writing screenshots to files.

The external target uses an already connected HDMI or Miracast display. If no
external display is present and the selected platform exposes a verified
connection UI, the test opens it and waits for Android to report the display.
Otherwise connect the display before starting the test. A managed wired
display temporarily switched from mirror mode is restored afterward; an
existing direct secondary-display connection is left connected. Hardware
keyboard, mouse, and phone touchpad input remain explicitly **NOT TESTED**
because the automated test injects its own input. Native mouse resize-cursor
selection is checked when WMShell exposes a transition trace.

The simulated target owns a temporary 1920x1080 display through a
lifecycle-bound shell-service stream. Its setting is restored when the test
finishes or its process disconnects. A one-shot launch probe records each test
window's first front-state and reports transient fullscreen launches instead
of mistaking a later corrected state for the initial one. The test also removes
the simulated display once while its desktop is live and verifies runtime,
task-area, and migrated-task cleanup.

Debug builds expose the same lifecycle check as an instrumentation regression:

```sh
am instrument -w --user 0 \
  io.github.mekhontsev.magicdesk/.DesktopLifecycleInstrumentation
```

They also allow the regular interactive self-test to be started directly over
ADB, without changing its preparation, checks, report, or cleanup path:

```sh
adb shell am start -n \
  io.github.mekhontsev.magicdesk/.DebugSelfTestActivity \
  --es target simulated
```

The accepted targets are `phone`, `simulated`, `wired`, and `wireless`.

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
`native/magicdesk_keyboard_bridge.c`. The PTY relay used by Console is built
from `native/magicdesk_pty_bridge.c`.

See [Contributing](CONTRIBUTING.md) for IDE setup and verification commands.

## Releases and Signing

GitHub Actions lints and builds MagicDesk after every non-documentation change.
A `v*` tag runs the signed release workflow, verifies the APK contents and
certificate, publishes its SHA-256 checksum, and creates a GitHub Release.

Official release APKs use this certificate SHA-256 fingerprint:

```text
3A:F3:FE:F8:95:AC:BC:9C:B7:7B:FD:BB:7E:91:79:42:
95:70:72:14:97:E3:6E:C1:E4:19:68:C9:4B:52:99:50
```

Maintainer signing setup and encrypted CI secret names are described in
[Architecture](docs/architecture.md#build-and-release-boundaries).

## Technical Documentation

- [Architecture](docs/architecture.md)
- [Desktop Entry files](docs/desktop-entries.md)
- [Shell access and display modes](docs/privilege-modes.md)
- [Fullscreen transitions](docs/fullscreen-transitions.md)
- [Compatibility and issue reports](docs/compatibility.md)
- [Validation matrix](docs/testing-backlog.md)
- [Contributing and IDE setup](CONTRIBUTING.md)

## Project

- Author: [Dmitry Mekhontsev](https://github.com/mekhontsev)
- Main package: `io.github.mekhontsev.magicdesk`
- Minimum SDK: 35
- Target SDK: 37
- License: [MIT](LICENSE)
- Third-party components: [notices and licenses](THIRD_PARTY_NOTICES.md)

Samsung DeX is a trademark of Samsung Electronics. Its name is used here only
to describe the desktop-product category and interaction model.
