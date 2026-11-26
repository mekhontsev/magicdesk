# MagicDesk

**An open-source Android workstation.**

MagicDesk combines native Android app windows, Linux graphical applications,
terminals and independent workspaces on multiple displays. Use the phone,
an external monitor, or a computer connected through scrcpy. Files, clipboard,
application launchers and automation connect the same tools whether or not a
managed Desktop is running.

**MagicDesk requires ARM64 Android 14+. Managed Desktop requires Android 15+.
Running Linux with Shroot requires a Linux 5.15+ host kernel.**

- **Android Desktop:** [set up Desktop](docs/getting-started.md#prepare-and-start-desktop),
  choose a display in Phone Control Panel and tap **Start desktop**. This uses
  Shizuku, a compatible provider such as Shevery, or direct root.
- **Linux inside MagicDesk:** open a **User** console to [install a distribution](#install-linux)
  and run its programs without Shizuku, Android root or managed Desktop.
- **Standalone Linux Capsule:** [package an installed distribution as an Android app](#a-linux-distribution-as-its-own-android-app).
  It gets its own Android UID, files and terminal and runs without MagicDesk or a VM.

[Latest release](https://github.com/mekhontsev/magicdesk/releases/latest) |
[Development APK](https://github.com/mekhontsev/magicdesk/releases/download/development/MagicDesk-development.apk) |
[Getting started](docs/getting-started.md) |
[Documentation](docs/README.md)

[Reddit r/MagicDesk](https://www.reddit.com/r/MagicDesk/) |
[Telegram community](https://t.me/magicdesk_android) |
[Support bot](https://t.me/MagicDeskSupportBot)

This README describes the selected branch. The stable APK may not include every
feature below; use its release notes for that version and the development APK
to try unreleased changes. See [downloads and channels](#downloads).

[Desktop](#native-android-desktop) |
[Terminals and tools](#tools-without-desktop) |
[Linux and Shroot](#linux-applications) |
[Displays](#displays-and-portable-workspaces) |
[Setup](#requirements-and-setup) |
[Support](#community-and-support)

[Watch the demo (1080p60)](https://youtu.be/zAHU_0M1ShU): Android and Linux apps
running locally on a REDMAGIC phone over HDMI, including Freeciv from
Ubuntu/PRoot and a full Xfce desktop.

![MagicDesk with native application windows and the calendar panel](docs/images/magicdesk-desktop.png)

## One Connected Workspace

- **Independent screens:** keep separate workspaces on the phone, external and
  virtual displays, each with its own applications, Start and taskbar.
- **Android and Linux together:** native Android tasks, individual X11/Wayland
  applications, whole Linux desktops and terminals share the workspace.
- **Tools without Desktop:** open Files, Start, terminals and Linux applications
  as ordinary Android windows without replacing your launcher or setting up
  managed Desktop.
- **Shared content:** use a real Desktop folder, clipboard, drag and drop,
  Android sharing and MIME-aware command launchers.
- **Customization:** per-app scale, native JSON themes, configurable panels,
  wallpapers and optional Linux shell components.
- **Automation:** the built-in CLI and authorized MCP clients use the same
  services as the interactive UI.

## Native Android Desktop

Applications remain real Android tasks. Android and WMShell own their rendering,
native captions, input and lifecycle. Shared Android mechanisms provide the core;
focused firmware and SoC adapters add optional capabilities.

Resize, snap, maximize, restore and move tasks between displays. True fullscreen
is separate from a maximized window. Taskbar, overview and Alt+Tab switch exact
tasks without recreating Activities. Start includes search, pins, app actions,
widgets, notifications, media controls and capture tools.

Every Start has a destination selector: **Current** launches on its own screen.
Choose app-default placement, **Window**, **Fullscreen**, or an explicitly
independent application. **Apps** opens the same Start without Desktop.
Windowed launch without Desktop requires Shell access and supporting firmware;
stock Nubia windowing requires managed Desktop. Opening an app does not
redirect mouse or keyboard input.

**Settings > Desktop > Try to become the Home app** is on by default. If disabled,
or Android refuses HOME, Desktop uses an ordinary fullscreen host and system
Home/Recents stay with the phone's launcher. When HOME is acquired, MagicDesk
restores the previous role after the last Desktop closes. **Close desktop** releases
surviving applications as independent fullscreen tasks on the same live display; it does not remove that
display or end retained terminals and graphics sessions. A disconnected direct
display returns its tasks to the phone.

![MagicDesk with overlapping Termux and Firefox windows](docs/images/magicdesk-multitasking.png)

### Per-App DPI

Choose **App scale > Custom** in an application's Desktop context menu.
**Interface scale** ranges from 50% to 200% of the display density; **System**
removes the override. Saved profiles also appear in
**Settings > Application profiles**.

Linux launchers expose **Interface scale** directly in their context menu for
User, Shell and Termux execution, with the same setting in **Linux graphics**.
Their scale also applies without managed Desktop.

The preference follows managed windows through snap, maximize and fullscreen.
Returning to ordinary phone use removes the active override but retains the
saved preference. This changes Android density, not monitor resolution, and
cannot create a layout the application does not implement.

## Native Appearance

Choose a bundled style in **Settings > Appearance**, then keep or cancel its
live preview. Configure panels, floating docks, Start layout, colors, fonts,
transparency, blur and motion, with optional per-workspace overrides.

Wallpapers support static images, animated WebP/GIF, silent video and configurable
AGSL shaders, with selectable cover or contain alignment. Import/export themes
as JSON or resource ZIPs. Styles apply to
MagicDesk, not third-party applications; Linux light/dark propagation and the
temporary Android-wide Desktop theme are separate controls.

The interface supports eight languages. **App language** changes MagicDesk's
language without changing Linux applications.

See [appearance and theme format](docs/appearance.md) and
[Linux appearance](docs/linux-appearance.md).

## Tools Without Desktop

Use **Apps** in Phone Control Panel to open tools on the phone or another allowed
display. The terminal button beside Settings opens the shared session picker;
**Apps > Terminal sessions** uses Start's selected destination.

### Files And Content

Files supports multiple windows, search, selection, copy, move, rename, delete,
properties, file handlers and Android document import under the selected shell
identity. The default Desktop folder is `/storage/emulated/0/Desktop`.

External applications receive scoped content-URI grants for selected files,
never the shell identity. Files, terminals, Desktop and Android sharing use the
same content model. Full work-profile and Private Space support remain unverified
or incomplete; profile-qualified identities alone do not establish that support.

### A Full Terminal, With Or Without Desktop

Interactive PTYs support editors, file managers and CLI tools, with ANSI colors,
scrollback, alternate screens and terminal mouse reporting. **User** (MagicDesk's
app UID), **Shell** (the authorized service) and **Termux** sessions share the
renderer, controls and automation API.

![Terminal graphics and the shared CLI](docs/images/magicdesk-terminal.png)

- Bundled JetBrains Mono Nerd Font, per-window size, pinch and Ctrl+wheel zoom.
- Static Sixel and inline Kitty graphics, including placeholders through tmux.
- Clickable links, titles, notifications, progress and OSC-marked command output.
- Persistent User/Shell command recall through bundled upstream mksh.
- Touch scrolling, selection handles, Android keyboard resizing and paragraph copy.
- One picker for retained User/Shell/Termux PTYs and discovered tmux sessions.
- Session files and folders from Android Share, the picker, camera or clipboard, with
  quoted paths for shell and supported guest sessions. Delete copies individually
  or end their session to remove them together.
- A text editor in the paperclip menu: insert text or attach it as a UTF-8 file.

Closing an ordinary terminal window detaches it; explicitly ending the session
terminates its programs. Closing a managed tmux window releases its client, not
the tmux server. Local PTYs do not survive MagicDesk process death or APK
replacement; independently running tmux sessions can be attached again.

Termux windows use Termux's own UID, packages and shell. Termux is optional for
ordinary Console, and MagicDesk cannot import an existing Termux tab's PTY.

![Independent Termux terminals](docs/images/magicdesk-termux-windows.png)

See [terminal integration](docs/terminal-integration.md) for protocols, history,
image support and session lifetime.

### Task Manager

**Applications** groups Android tasks, retained terminals, tmux and X11/Wayland
sessions. **Processes** shows CPU, resident memory, process trees and filtering.
Return to a window or end a session explicitly. Graphics sessions remain listed
without a viewer; their resource figures describe the server, not every guest
process. Available details depend on the service's actual permissions.

## Linux Applications

The embedded X11 server and Wayland compositor host individual applications or
whole desktops. Programs can come from Termux, Shroot, PRoot or a prepared
chroot. No separate Termux:X11 APK is needed.

![GIMP, Firefox and Task Manager](docs/images/magicdesk-x11.png)

### Linux With Shroot

Use a **User** console to run Linux under MagicDesk's own Android UID, without
Shizuku, Android root, Termux or a VM. A **Shell** console uses the authorized
command service, normally UID 2000. Each has its own library of distributions.
MagicDesk supplies the terminal, graphics, audio and Android file access;
[Shroot](https://github.com/mekhontsev/shroot) runs the Linux programs.

User mode can run more than command-line tools: Alpine under an ordinary app
UID runs k3s and Chromium with its sandbox enabled and software rendering.
Environments under one Android UID are not a security boundary for untrusted
code. To give a distribution its own Android UID, create a
[Linux Capsule](#a-linux-distribution-as-its-own-android-app).

#### Install Linux

Open MagicDesk, tap the terminal icon beside **Settings**, then choose
**New session > User** or **Shell**. The installer uses that console's Linux
library; use the same executor when resuming. Run these commands there, not in
Termux or inside Linux. No MagicDesk Desktop session is needed; see
[opening the Android shell console](docs/getting-started.md#open-the-android-shell-console)
for access setup and other terminal types.

Use `main` with the stable APK, `develop` with the development APK, or the
matching release tag with an older APK. Download the script before running it
so the installer can read your answers:

```sh
channel=main
magicdesk download "https://raw.githubusercontent.com/mekhontsev/magicdesk/$channel/scripts/install_linux.sh" ~/install_linux.sh
sh ~/install_linux.sh
```

**First launch:** once installation finishes, open your program or desktop from
**Apps**. For a login shell, choose **New session > Shroot environments** in the
console and select the environment.

To choose a **Linux user** or **Start system services** (systemd, OpenRC or
runit), long-press the Apps entry and choose **Launch options**. For a terminal,
select the environment first, then **Launch options**. These choices apply to
new sessions. See [services and containers](docs/shroot.md#services-and-containers).

If Linux processes unexpectedly disappear, tap **Process limit** in Phone
Control Panel to check whether Android restricts child processes.

Recipes include Debian, Ubuntu, Alpine, Fedora, Arch Linux ARM, openSUSE,
openKylin, Deepin, Manjaro ARM, Void, Armtix, NixOS and SteamOS ARM64.
See the [installer guide](docs/shroot.md#linux-installer) for desktop choices,
image sources, graphics and network settings.

**Update an existing environment:** updating the APK does not replace
`magicdesk-init` inside Linux. Download the matching installer as above, then
run these commands in the same **User** or **Shell** console used to install it:

```sh
shroot list
sh ~/install_linux.sh --name debian --resume
```

Replace `debian` with the name shown by `shroot list`. Resume refreshes the
recipe's helpers and reruns package setup without importing a new root filesystem.
Omit `--gui` to keep the installed desktop. Use the same command to
[resume an interrupted installation](docs/shroot.md#resume-an-interrupted-installation).

For a Kubernetes lab, run a Debian server and an Alpine agent on the same
phone. Start with the [MagicDesk k3s setup](docs/shroot.md#k3s).
For other workloads, see [what Shroot runs](https://github.com/mekhontsev/shroot#what-it-runs)
and the [distribution matrix](https://github.com/mekhontsev/shroot/blob/develop/tests/validation-matrix.md).

### Installed Apps In Start

Start discovers installed `.desktop` files; no manual shortcut is needed when
a package supplies one. Shroot entries come from registered environments.
For Termux applications, enable Termux external commands, grant MagicDesk
`RUN_COMMAND`, and install `xkeyboard-config` plus your graphical applications.
Use **Refresh** after installing packages.

Choose the destination and window mode as for Android applications. Launching an
existing entry returns to its window; **New window**, at the end of the context
menu, requests another instance for the selected Open action only,
subject to the application's rules.

X11 and Wayland share Android clipboard/file exchange, audio, interface scaling
and window hosting. Supported dialogs and popups can extend beyond their parent
in managed Desktop. An individual application's normal close preserves save
confirmation; force close is separate.

See [desktop entries](docs/desktop-entries.md), [audio](docs/audio.md),
[X11](docs/x11.md) and [Wayland](docs/wayland.md).

### Whole Linux Desktops Too

**Linux graphics** manages retained sessions and their viewers. X11 hosts a
window manager and screen; Wayland hosts a nested compositor. Closing a desktop
viewer retains the Linux session; **Stop** ends it. When a Linux desktop lives on
a virtual display, return through its display viewer in Android Recents rather
than a duplicate internal graphics window. Retained sessions are not saved
across MagicDesk process death or a phone reboot.

For a whole Linux desktop outside managed Desktop, select its display with
**Control input**. With MagicDesk's Accessibility key filter enabled, physical
keyboard chords such as **Alt+Tab**, **Alt+F4** and **Super/Win** go to Linux.
**Ctrl+Alt+Tab** remains the host display switch; power and volume remain Android
controls. Individual Linux windows inside managed Desktop keep MagicDesk's
window shortcuts.

**New command app** can create application, terminal or desktop launchers for
Termux/PRoot, Shroot, or an explicitly prepared root chroot. Custom entry scripts
must preserve the graphical and content-bridge contract.
See [custom launchers](docs/desktop-entries.md#custom-linux-entry-scripts).

### Using Your Existing Desktop Mode

Keep Samsung DeX or another compatible Android desktop shell and use MagicDesk
for Termux terminals and Linux applications. Open MagicDesk on that display,
choose **Apps**, and keep **Current** as the destination. Do not start MagicDesk
Desktop for this workflow. Android's host desktop manages the ordinary windows;
compatibility still depends on its display and input policy.

### Linux Apps In Your Android Launcher

Launch Mousepad, GIMP or Firefox directly from your usual **Android app drawer**,
alongside Android apps, with the Linux application's name and icon. This is not
just a home-screen shortcut: the app drawer opens the program directly.
MagicDesk launches the Linux application in its own Android window.

In **Apps** or Desktop's **Start**, long-press or right-click an installed Linux
application and choose **Add to Android launcher**. MagicDesk creates and signs a
small launcher APK locally; confirm its installation in Android. The APK is only
a launcher, not a copy of the Linux application or its environment.

Available on Android 14+ without managed Desktop. Ordinary Termux-hosted apps
need neither root nor Shizuku, and no MCP server is required. Other environments
retain their normal execution requirements.

Uninstall the launcher entry like any Android app; the Linux application remains
installed. If its source entry is removed, the launcher can no longer open it.
See [Android launcher exports](docs/desktop-entries.md#android-launcher-exports).

### A Linux Distribution As Its Own Android App

A **Linux Capsule** is a distribution you can install, launch and uninstall
like any other Android app. Unlike a launcher-only APK, it contains its own
Shroot runtime, terminal and private copy of the distribution. It uses the
phone's kernel and runs without MagicDesk, Shizuku or Android root.

To create one, open **Console > New session > Shroot environments**, select the
distribution and choose **Install Linux as an application**.

Android's UID, SELinux and seccomp protections apply to the Linux processes.
Even guest root cannot access another package's private data by default: this
is the real [Android application sandbox](https://source.android.com/docs/security/app-sandbox),
not merely Shroot's emulated guest permissions. Files and optional audio/graphics
connections require explicit grants; clients sharing an X11 server still share
its display authority.

For example, give a Linux image converter read-only access to a folder of photos
and write access to a separate output folder. It can read the originals and
write thumbnails that you can open directly in Android. The originals stay
read-only in Linux; there is nothing to import or export.

Choose [**Android folders**](docs/linux-applications.md#android-folders) to set
this up in a standalone Linux APK or a MagicDesk Shroot **User** or **Shell**
environment. New sessions mount your selections under `/mnt/android`.
Each folder defaults to read-only, and the choices are saved per environment.

An APK for offline work can be built **without Android network permissions**.
For a network-enabled environment, [network settings](docs/linux-applications.md#network-access)
can publish a Linux service on the phone or explicitly allow other devices to connect.

See the [Linux Capsules guide](docs/linux-applications.md) for installation, backup and
updates, temporary web access, and optional MCP automation using the guest's AT-SPI2 bus.

### Linux Graphics Acceleration

The optional Turnip recipe builds Mesa inside the Linux environment for
supported Adreno GPUs with accessible KGSL. Depending on the desktop profile,
the recipe also builds compatible KWin or Weston components. Zink supplies OpenGL
over Vulkan; the embedded X11/Wayland host presents the result through Android. This does
not replace Android's graphics driver or require managed Desktop.

Software rendering remains available. GPU admission uses capability probes,
not just the GPU name or kernel version. An APK update does not rebuild an
installed environment's Mesa: use the installer's resume option when updating
that stack. Hardware composition, video decoding and game compatibility are
separate capabilities. See [GPU setup](docs/shroot.md#graphics-drivers) and
[graphics integration](docs/graphics.md).

Flatpak and Snap GPU integration reuses that Mesa through local driver
extensions. An incompatible Flatpak runtime may need an explicitly requested
SDK build; the installer does not automatically build a second Mesa.
See [Flatpak GPU drivers](docs/graphics.md#flatpak-gpu-drivers).

Shroot graphical sessions also receive supported Android gamepads, including
buttons, axes, hotplug and rumble, through a launch-scoped bridge. Input follows
the focused host window. See [gamepad support and limits](docs/gamepads.md).

### Linux Shell Components

An explicitly bound graphical session can contribute X11 dock/desktop windows
or Wayland layer-shell panels to a managed Desktop. Supported panels share work
areas and can control managed Android/Linux tasks through foreign-toplevel
management. **Separate** releases the binding without stopping the session.

Whole Linux desktops keep their panels inside their viewer. See
[shell layout](docs/shell-layout.md) and the
[Waybar example](scripts/examples/waybar/README.md).

## Shizuku Or Direct Root

The default manager list is
`com.hamondev.shevery;moe.shizuku.privileged.api`.
MagicDesk selects the first installed package at startup; it does not switch
providers during a session. Existing saved lists are retained. Configure the
semicolon-separated list in **Settings > Integrations**, then Exit and reopen.
See [provider setup](docs/getting-started.md#install).

Rooted devices may instead select **Privileged service > Root (su)**.
Both transports start the same service. **Limits > Maximum access** chooses
Root, Shell (default UID 2000), or App only. Root does not elevate a shell
provider; Shell restricts a root-backed provider. App only skips privileged
startup while authorized Termux tools remain independent.

Backend, package-list and limit changes apply on the next app start. See
[privilege boundaries](docs/privilege-modes.md).

## MagicDesk On A Computer With scrcpy

Create a MagicDesk virtual display, then view that existing display from a
computer with scrcpy. Replace `3` with its displayed ID:

```sh
scrcpy --display-id=3 --mouse-bind=++++ --shortcut-mod=rctrl
```

Applications still run on Android; scrcpy supplies video/input and has its own
connection requirements. This also works with independent tools without Desktop.
See [display setup](docs/getting-started.md#choose-or-create-a-display).

## Displays And Portable Workspaces

Phone Control Panel lists physical, wireless and owned virtual displays.
Displays, viewers and Desktop sessions have independent lifetimes.

### Portable Workspaces And Parking

A direct Desktop lives on its monitor's Android display. A portable Desktop
lives on a retained virtual display, shown through a fullscreen viewer.

Choose **Start portable desktop here** on the connected output. Disconnecting
the monitor or choosing **Stop showing** parks that live workspace without
moving or relaunching its applications. Reconnect and use **Show another
display...** to show it again. **Control input** separately selects the source
for phone-connected keyboards and mice.

Parking is not persistence across app death. Do not use **Close desktop**,
**Remove display** or **Exit MagicDesk** to park a workspace.

### Viewing And Switching Screens

**Show another display...** shows a retained source on an output.
**Display Viewer** can also open as an ordinary application window.
With the Accessibility key filter active, **Ctrl+Alt+Tab** switches picture and
input together without moving applications. Ordinary viewer opening does not
claim input.

| Action | What remains |
| --- | --- |
| Close viewer / Stop showing | Source display, applications and Desktop |
| Close Desktop | Display, retained services and surviving independent app tasks |
| Remove owned display | Other displays and their sessions |
| Exit MagicDesk | Saved files/settings; owned live services and displays end |

Physical and foreign displays are never removed by MagicDesk. See
[getting started](docs/getting-started.md#choose-or-create-a-display).

## Automation

The optional MCP server and built-in `magicdesk` CLI share the UI's services:
state/events, display and window control, Android actions, terminals, graphics,
files, clipboard, themes, dialogs, notifications, APK updates and self-tests.
They work independently of Desktop where the operation permits it.

Linux sessions also expose [semantic UI automation](docs/automation.md#linux-scope)
through AT-SPI2, [typed D-Bus access](docs/automation.md#typed-d-bus), exact-argument
application launches and persistent guest consoles. Native Termux, Shroot and
PRoot use the same interface where guest services support it. Accessibility
activation has a separate opt-in; application flags remain explicit, and
application sandboxes are not disabled.

MCP is disabled by default. Local and optional network listeners have separate
tokens and grants; the full command catalog remains discoverable even when a
service is unavailable. The CLI needs neither an enabled MCP server nor Python.
Android App Functions and the external command API are separate opt-ins.

See [automation](docs/automation.md) and [external commands](docs/run-command.md).

## Downloads

- [Stable releases](https://github.com/mekhontsev/magicdesk/releases/latest):
  numbered APKs and their version-specific notes.
- [Development APK](https://github.com/mekhontsev/magicdesk/releases/download/development/MagicDesk-development.apk):
  rolling `develop` build published after required CI passes. It uses the normal
  package/signing identity, not MagicDeskTest, and can be less stable. It updates
  the installed MagicDesk rather than installing a separate application.

Development and external PRs target `develop`; verified releases use `main`.
See [contribution workflow](CONTRIBUTING.md#branches-and-releases).

## Requirements And Setup

| Use | Additional requirements |
| --- | --- |
| User console, settings, appearance and display inventory | Ordinary app access; no Shizuku, root or Shroot required |
| Termux terminals and graphics | Termux external commands, `RUN_COMMAND`; graphics also need XKB data |
| Files, Shell console, privileged capture and actions | Authorized shell/root service and operation-specific capabilities |
| Linux environments with Shroot | Linux 5.15+ host kernel with the [required capabilities](https://github.com/mekhontsev/shroot/blob/main/kernel-compatibility.md). User needs only app access; Shell needs the privileged service. |
| Managed Desktop | Android 15+, privileged service, setup and working framework windowing |
| External output | Hardware/firmware exposing a usable Android secondary display |

Install the APK, open Phone Control Panel and configure only the integrations
you need. Use **Apps** or the terminal picker for independent tools. For managed
Desktop, complete **Settings > Device setup**, then select the display and
**Start desktop**. Reboot only if setup requests it, and restart Shizuku afterward.

Follow [Getting started](docs/getting-started.md) for the complete sequence.
Android 14 has partial shared-service device coverage; Linux graphics on that
release still need verification. See [compatibility](docs/compatibility.md) and
[API boundaries](docs/runtime-api-levels.md).

## Input And Optional Features

**Control input** routes physical Android keyboards, mice and external
touchscreens independently of Desktop. Android retains layouts, repeat,
pointer acceleration, hover and multitouch. Touchscreens must be recognized
by Android; the phone's built-in touchscreen keeps its own routing.
The optional phone touchpad supplies a relative mouse; the normal Android IME
handles text. **Show keyboard on app display** requests the IME beside the app,
subject to firmware and keyboard support.

Desktop shortcuts and independent Linux-desktop key forwarding use the shared
key-only Accessibility service while their selected input target is active.
Android 14 external mouse routing needs a usable input viewport and matching
system pointer target; a visible cast display alone does not prove input support.

Phone-screen power, output timing, charge separation, cooling and recording audio
are separately probed optional capabilities. Their absence does not disable
unrelated tools.

## Keyboard Shortcuts

| Shortcut | Action during Desktop |
| --- | --- |
| `Win+D` | Show desktop / restore its previous layout |
| `Win+Up` | True fullscreen |
| `Win+Down` | Restore; press again to minimize |
| `Win+Left` / `Win+Right` | Snap to a half |
| Hold `Win` after Left/Right, then `Up` / `Down` | Corner, half or opposite corner |
| `Alt+Tab` / `Alt+Shift+Tab` | Switch exact tasks |
| `Ctrl+Alt+Tab` | Switch displayed screen and input |
| `Alt+F4` | Close active task |
| `Win+Backspace` | Android Back |
| `Win+L` | Lock phone |
| `Win+N` / `Win+Q` | Notifications / Quick controls |
| `Win+I` | Settings |
| `Win+Print Screen` | Screenshot |
| `Win+Shift+Print Screen` | Start/stop recording |
| `Ctrl+Space` | Next configured hardware-keyboard layout |
| `Win+/` | All shortcuts |

Pointer equivalents are available in taskbar/window context menus and Quick
controls. These are managed Desktop bindings; an independently controlled
[whole Linux desktop](#whole-linux-desktops-too) receives its own window shortcuts.

## Security

Use [private security reporting](https://github.com/mekhontsev/magicdesk/security/advisories/new)
for vulnerabilities, not public issues or the support bot.

Files and shell commands retain the selected service identity; Termux retains
its own. MagicDesk does not patch SystemUI or install a kernel module.
Shell and input automation grants allow broad control, not a category-isolated
sandbox. Accepted work may continue after a grant is revoked.

Network MCP uses HTTP, not TLS: use a trusted network or a protected tunnel,
never direct Internet exposure. External command access has its own disabled-by-
default opt-in and Android permission. Grant it only to trusted applications.

See [Security](SECURITY.md) and [Privilege boundaries](docs/privilege-modes.md).

## Community And Support

Use [r/MagicDesk](https://www.reddit.com/r/MagicDesk/) and
[Telegram](https://t.me/magicdesk_android) for discussions and user setups.
For reproducible defects, send complete Diagnostics output and exact steps to
[GitHub issues](https://github.com/mekhontsev/magicdesk/issues) or the support bot.

### AI-Assisted Support

[MagicDesk Support Bot](https://t.me/MagicDeskSupportBot) accepts reports and
follow-up test results in a private chat. GitHub Copilot can investigate a case,
ask questions and propose a test APK. Each candidate links its exact source,
build and checksum.

MagicDeskTest uses a separate package/signing key and is not an official release
or a verified fix. Installation is manual and merging still requires review.
Do not send secrets: generated source may contain report details. See
[Telegram support](docs/telegram-support.md) for submission and privacy rules.

## Diagnostics And Development

Reports omit user files, account data, notification contents and the installed
app catalog, but logs can contain filenames and package names. Review before
sending. Interactive self-tests certify their exact scenarios, not every firmware.

Build prerequisites and device checks are in [Contributing](CONTRIBUTING.md):

```sh
git submodule update --init --recursive
./gradlew verifyDevelopment
```

Native helpers currently target ARM64. Host builds do not replace device checks.

> Development uses iterative AI-assisted implementation and hands-on device
> testing. Independent review is especially important for privileged integration.
> See the [device-porting workflow](docs/ai-assisted-device-porting.md).

## Documentation

The [documentation index](docs/README.md) separates user guides, interfaces,
architecture and verification. Start with:

- [Getting started](docs/getting-started.md) and [workstation tools](docs/workstation-tools.md)
- [Linux installation and Shroot](docs/shroot.md)
- [Terminal integration](docs/terminal-integration.md)
- [X11](docs/x11.md), [Wayland](docs/wayland.md) and [graphics](docs/graphics.md)
- [Automation](docs/automation.md) and [desktop entries](docs/desktop-entries.md)
- [Architecture](docs/architecture.md) and [compatibility](docs/compatibility.md)
- [Device coverage](docs/testing-backlog.md) and [Shroot matrix](https://github.com/mekhontsev/shroot/blob/develop/tests/validation-matrix.md)

## Project

- Author: [Dmitry Mekhontsev](https://github.com/mekhontsev)
- Package: `io.github.mekhontsev.magicdesk`
- Minimum APK SDK: 34; managed Desktop: 35; target SDK: 37
- License: [GNU GPLv3](LICENSE); see [licensing](docs/licensing.md) and
  [third-party notices](THIRD_PARTY_NOTICES.md).
