# MagicDesk

MagicDesk is an open-source Android workspace: native multi-window Desktop,
Files, shell and Termux terminals, and permission-controlled automation.
Tools and automation can run without Desktop; a display can exist without
either a desktop session or a connected viewer.

The APK requires **Android 14+**. Managed **Desktop requires Android 15+**.
Privileged operations use [Shizuku](https://github.com/RikkaApps/Shizuku);
root is not required.

MagicDesk's core is vendor-independent. Windows, physical input routing, phone
IME integration, HOME ownership, displays, files and automation use shared
Android mechanisms, including hidden framework APIs through the authorized
shell service. Optional firmware and SoC adapters add narrowly scoped features;
they do not define a separate desktop implementation.

This is not a promise that every Android device works identically. Framework
capabilities, external video support and firmware defects still matter.
See [tested coverage and limitations](docs/compatibility.md) and the
[API-level contract](docs/runtime-api-levels.md). Android 14 device validation
is pending; the native helpers currently target ARM64.

[Latest release](https://github.com/mekhontsev/magicdesk/releases/latest) |
[Development APK](https://github.com/mekhontsev/magicdesk/releases/download/development/MagicDesk-development.apk) |
[Community](https://t.me/magicdesk_android) |
[Getting started](docs/getting-started.md)

This documentation describes the current development code. The stable APK may
not yet include every feature below.

![MagicDesk with native application windows and the calendar panel](docs/images/magicdesk-desktop.png)

## Native Android Desktop

Applications remain real Android tasks. MagicDesk does not stream them into
replacement views or run a guest operating system. Android and WMShell own
their native captions, input, rendering and application lifecycle.

- Use Desktop on the phone, a wired monitor, an Android wireless display, or a
  MagicDesk-created virtual display.
- Resize, snap, maximize, restore and move tasks between displays. True
  fullscreen is separate from a maximized freeform window.
- Switch exact tasks with the taskbar, overview and Alt+Tab through one window
  controller. Selecting a task does not recreate its Activity.
- Use Start search, app actions, pins, shortcuts, widgets, notifications,
  media controls and capture tools.
- Set display density and application-specific interface scale. Per-app scale
  follows the managed task across window modes and is released on return to
  ordinary phone use.
- Keep a real Desktop folder with files, folders and editable `.desktop`
  launchers, plus bundled or custom wallpaper.

MagicDesk temporarily holds Android's HOME role during a desktop session and
returns the previous role state on Close. During an external session the phone
has its own Start surface: it launches phone apps in fullscreen and lists phone
recent tasks. Desktop Start remains separate and can be open at the same time.

Close records the managed workspace and returns surviving application tasks
to phone fullscreen. A later session restores the same still-live tasks, not
applications that Android or the user has closed. Only one managed Desktop
session is active at a time.

![MagicDesk with overlapping Termux and Firefox windows](docs/images/magicdesk-multitasking.png)

## Tools Without Desktop

Open tools directly from Phone Control Panel, on the phone or a selected
display. They use ordinary fullscreen Activities outside a managed session and
the existing window controller inside one. Opening a tool does not acquire
HOME or run Desktop setup.

### Files And Content

Files browses the filesystem available to the authorized shell identity.
It supports multiple windows, search, selection, copy, move, rename, delete,
properties, file handlers, Android document import, and drag and drop.

Files, Desktop, Console, Android clipboard and View/Share intents share one
content model. External apps receive scoped content-URI access to selected
files, never MagicDesk's shell identity. **Save to MagicDesk Desktop** accepts
Android shares after confirmation. File operations do not require Desktop.

The default workspace is `/storage/emulated/0/Desktop`. Display changes do not
create another Desktop folder. Profile-qualified app identities are implemented
in catalogs and state; full work-profile and Private Space support is not yet
implemented.

### Shell And Termux Sessions

Console provides a real interactive PTY with ANSI colors, scrollback, selection,
clipboard, terminal mouse reporting, resizing and alternate-screen programs.

- Android-shell sessions run under the authorized Shizuku identity.
- Termux sessions run under Termux's own UID, using its installed tools and
  documented external-command permission.
- Each retained session owns its shell and terminal state. Closing a window
  detaches its view; **Terminal sessions** reattaches it without restarting
  the shell. **End session** explicitly terminates it.
- Sessions survive window closure and Close Desktop, not MagicDesk process
  death or APK replacement.
- Optional tmux integration discovers, creates and attaches Termux tmux
  sessions. tmux is not required and is not installed automatically.

Multiple Termux-backed windows are ordinary Android tasks, not tabs inside the
Termux app or windows confined to an X11 server. MagicDesk cannot import an
ordinary Termux tab's PTY. Termux:X11 remains a separate optional viewer/server
integration.

![Independent Termux terminals running nvim and Midnight Commander](docs/images/magicdesk-termux-windows.png)

See [Workstation tools](docs/workstation-tools.md) and
[Desktop Entry files](docs/desktop-entries.md).

## MagicDesk On A Computer With scrcpy

Use MagicDesk together with **scrcpy** to view and control the Android workspace
from a computer. Desktop and applications execute on the Android device;
scrcpy supplies the computer-side display and input connection. A physical
monitor is not required: create a MagicDesk virtual display and view that
existing display with scrcpy.

The same arrangement can show independent fullscreen Files or terminal tools
without starting managed Desktop. scrcpy is a separate application with its
own connection requirements; an MCP connection does not provide its video stream.

### Display Resources

The display selector lists Android displays with their current identity,
dimensions, source and available actions. A connected wireless display can
exist before MagicDesk starts a session on it.

**Create display** offers a virtual display or a display with a phone preview,
with configurable dimensions and scale. Multiple headless virtual displays can
coexist; the Android preview adapter has a single shared configuration.
**Copy scrcpy command** supplies a viewer command for the selected display.

A viewer, display and Desktop session have independent lifetimes:

- Closing a viewer does not close Desktop or remove MagicDesk's display.
- **Close desktop** stops the session but keeps its display.
- **Remove display** removes only a MagicDesk-owned display, after closing any
  session on it and completing cleanup.
- MagicDesk does not remove physical, wireless, built-in or foreign virtual
  displays. Their connection is managed by Android and the external device.

Display creation and ordinary tool placement do not require WMShell Desktop.
Android 14 execution coverage remains pending; additional built-in screens on
dual-screen/foldable devices are not yet verified Desktop targets.

## Automation

The optional MCP server exposes the same services used by the UI:

- Device, runtime, display and task state, events and exact-operation waits.
- Desktop lifecycle, task focus, window transitions and semantic UI actions.
- Independent tool placement and retained terminal control.
- Android intents, handlers, shortcuts, Activity results and App Functions.
- Screen capture, clipboard and notification operations.
- File upload/download with bounded chunks and integrity checks.
- Same-package, same-signer MagicDesk APK updates, with an installer worker
  that survives replacement and allows the client to reconnect.
- Desktop self-tests with exact run IDs, live stages, results and cleanup state.

MCP can start before Shizuku is ready. Each command checks its own service
prerequisites; a reachable server is not proof that Desktop or shell operations
are available. Desktop commands still require Desktop where applicable.

Loopback and optional network listeners have separate tokens and grants.
The complete tool catalog stays visible when permissions change, so an AI client
does not need to reload its catalog merely because a grant was enabled.
Android 16+ App Functions expose a smaller system-agent action surface.

See [Automation and MCP](docs/automation.md) for configuration, permissions,
transfer/update protocols and test control.

## Requirements And Setup

| Use | Requirements beyond installing the APK |
| --- | --- |
| Control panel, Settings, MCP observation | Ordinary app access; explicitly enable MCP for clients |
| Files, Android shell, privileged capture and device actions | Running, authorized Shizuku and the operation's actual capabilities |
| Termux terminals | Termux, external commands enabled, MagicDesk's `RUN_COMMAND` permission |
| Owned virtual displays | Authorized Shizuku and working framework display APIs |
| Managed Desktop | Android 15+, authorized Shizuku, Desktop setup, working framework windowing |
| Wired/wireless output | Hardware and firmware that expose a usable Android secondary display |

1. Install MagicDesk and open Phone Control Panel.
2. For shell-backed features, install and start official Shizuku, then authorize
   MagicDesk. Termux is optional and has its own permission setup.
3. Open **Files**, **Console**, **Termux Console** or **Terminal sessions**
   directly, without starting Desktop.
4. For Desktop, open **Settings > Device setup**, complete the required changes,
   and reboot only when setup requests it. Restart Shizuku afterward as needed.
5. Select the phone, a connected display, or a display created through
   **Create display**, then press **Start desktop**.

**Show desktop** returns to the active workspace without starting another
session. **Close desktop** releases its temporary ownership while keeping the
tools runtime and owned display available. **Exit MagicDesk** also ends retained
terminals, closes built-in windows and stops the runtime. Neither action deletes
the Desktop folder.

[Getting started](docs/getting-started.md) covers updates, Termux, displays and
removal. [Compatibility](docs/compatibility.md) separates standard support,
device observations and optional vendor controls.

## Input And Optional Features

Physical mice and keyboards stay Android input devices, explicitly associated
with the selected Desktop display. Android handles acceleration, layout, repeat,
hover and right click. MagicDesk's key-only Accessibility service handles
desktop shortcuts only during the session.

The optional phone touchpad supplies one virtual relative mouse. The user's
normal Android IME connects directly to a focused external editor; MagicDesk
does not capture editor text, choose a replacement IME or relay it through a
vendor text bridge.

Output timing, phone-screen power, charging separation, cooling, thermal
readings and internal recording audio depend on separately probed capabilities.
Unsupported optional features do not disable unrelated tools or Desktop.
Shared compatibility policies can be selected in Settings on every vendor.

## Keyboard Shortcuts

| Shortcut | Action during Desktop |
| --- | --- |
| `Win+D` | Show bare desktop or restore its previous window layout |
| `Win+Up` | Move the active task to true fullscreen |
| `Win+Down` | Restore fullscreen/maximized task; press again to minimize |
| `Win+Left` / `Win+Right` | Snap to either half |
| `Alt+Tab` / `Alt+Shift+Tab` | Switch exact tasks |
| `Alt+F4` | Close active task |
| `Win+Backspace` | Send Android Back to the desktop display |
| `Win+L` | Lock phone |
| `Win+N` / `Win+Q` | Notifications / System panel |
| `Win+I` | MagicDesk Settings |
| `Win+Print Screen` | Capture desktop |
| `Win+Shift+Print Screen` | Start/stop recording |
| `Ctrl+Space` | Next configured physical-keyboard layout |
| `Win+/` | All shortcuts |

## Security

Shizuku authorizes privileged shell operations, not every UI action. Files and
Android-shell terminals use its connected identity; Termux uses its own.
The main APK does not acquire root, patch SystemUI or load a kernel module.

MCP is disabled by default and requires a bearer token. Each listener has
independent permissions for control, input/tests, content, file reads, file
writes, shell/terminals and APK updates. Authenticated observation is always
available. Shell and input grants allow broad device control, not a sandbox
limited to their neighboring permission categories.

Network MCP uses HTTP, not TLS. Enable it only on a trusted network or through a
protected VPN/tunnel; do not expose it directly to the Internet. Grant only the
access the client needs. Accepted actions may continue after a grant is revoked.

See [Privilege boundaries](docs/privilege-modes.md) and
[third-party notices](THIRD_PARTY_NOTICES.md).

## Diagnostics And Development

Reproduce a problem, open Diagnostics, and attach its complete compatibility
report and exact steps to an issue. Reports omit user files, account data,
notification contents and the installed-app catalog. Self-tests are explicit,
interactive checks, not background monitoring or a universal firmware guarantee.

The project uses JDK 17+, Android SDK/build-tools 37 and NDK
`27.3.13750724`:

```sh
./gradlew verifyDevelopment
```

Host builds do not replace device testing. Native helpers are currently ARM64;
API 34 native validation and other ABIs remain in the
[validation plan](docs/testing-backlog.md).

> **Development note:** MagicDesk is a vibe-coded project, built primarily through
> [iterative AI-assisted development](docs/ai-assisted-device-porting.md)
> and hands-on device testing. Independent review is especially important for
> privileged framework integration.

## Documentation

- [Getting started](docs/getting-started.md)
- [Workstation tools](docs/workstation-tools.md)
- [Architecture](docs/architecture.md)
- [Automation and MCP](docs/automation.md)
- [Runtime API levels](docs/runtime-api-levels.md)
- [Compatibility and issue reports](docs/compatibility.md)
- [Desktop Entry files](docs/desktop-entries.md)
- [Fullscreen transitions](docs/fullscreen-transitions.md)
- [Privilege boundaries](docs/privilege-modes.md)
- [Validation plan](docs/testing-backlog.md)
- [Nubia vendor interface audit](docs/nubia-vendor-audit.md)
- [Contributing](CONTRIBUTING.md)

## Project

- Author: [Dmitry Mekhontsev](https://github.com/mekhontsev)
- Community: [Telegram](https://t.me/magicdesk_android)
- Package: `io.github.mekhontsev.magicdesk`
- Minimum APK SDK: 34; managed Desktop: 35
- Target SDK: 37
- License: [MIT](LICENSE)
