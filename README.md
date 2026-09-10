# MagicDesk

**An open-source Android workstation.**

MagicDesk brings native Android app windows, independent Termux terminals, a
real file-based desktop, and programmable automation into one connected
workspace. Work directly on your phone, on an external display, or from a
computer through scrcpy.

Run Android apps and command-line tools side by side. Move content between
Files, terminals and Android apps. Let an authorized AI client use the same
services you use interactively. Desktop is one way to work with these tools,
not a requirement for using them.

The APK requires **Android 14+**. Managed **Desktop requires Android 15+**.
Privileged operations use [Shizuku](https://github.com/RikkaApps/Shizuku);
root is not required.

[Latest release](https://github.com/mekhontsev/magicdesk/releases/latest) |
[Development APK](https://github.com/mekhontsev/magicdesk/releases/download/development/MagicDesk-development.apk) |
[Getting started](docs/getting-started.md)

**Join the community: [Reddit r/MagicDesk](https://www.reddit.com/r/MagicDesk/) |
[Telegram](https://t.me/magicdesk_android)** |
[Support bot](https://t.me/MagicDeskSupportBot)

This documentation describes the current development code. The stable APK may
not yet include every feature below.

![MagicDesk with native application windows and the calendar panel](docs/images/magicdesk-desktop.png)

## One Connected Workspace

MagicDesk's strength is how its parts work together:

- **Android apps and command-line tools share a desktop.** Keep a browser,
  editor, file manager and several Termux terminals in separate native windows,
  with task switching, keyboard shortcuts and [per-app DPI](#per-app-dpi).
- **Files connect the tools.** The desktop is a real folder. Files, clipboard,
  drag and drop, Android sharing and command launchers work with the same
  content, so a file can move from a terminal workflow to an Android app without
  a separate export workspace.
- **Commands become applications.** A Termux or shell command saved as a
  `.desktop` file appears in Start, can accept dropped files and, with declared
  MIME types, becomes an **Open with** handler in Files. One definition serves
  all three entry points.
- **Work is not tied to an open desktop.** Files and terminals also run on their
  own. Close Desktop without ending retained terminal sessions, then reattach
  their windows. A virtual display can remain available to scrcpy independently
  of the desktop session.
- **The workspace is programmable.** Authorized MCP clients can inspect state,
  manage windows, work with files and terminals, invoke Android actions and
  compose workflows through the same services as the UI. Automation can also
  run without Desktop, locally or over an explicitly enabled network connection.

During an external desktop session, the phone remains useful in its own right:
its Start launches fullscreen phone apps independently, or it can serve as a
touchpad and show your normal Android keyboard for an external app.

## Native Android Desktop

Applications remain real Android tasks, with native captions, input, rendering
and application lifecycle owned by Android and WMShell.

The core is vendor-independent: windows, input, displays, files and automation
use shared Android mechanisms, including hidden framework APIs through Shizuku.
Optional firmware and SoC adapters add focused capabilities to the same
implementation. You keep your Android applications, system keyboard and Termux
environment.

- Use Desktop on the phone, a wired monitor, an Android wireless display, or a
  MagicDesk-created virtual display.
- Resize, snap, maximize, restore and move tasks between displays. True
  fullscreen is separate from a maximized freeform window.
- Switch exact tasks with the taskbar, overview and Alt+Tab through one window
  controller. Selecting a task does not recreate its Activity.
- Use Start search, app actions, pins, shortcuts, widgets, notifications,
  media controls and capture tools.
- Set display density and [per-app DPI](#per-app-dpi). Per-app scale
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

### Per-App DPI

**Give each Android app its own interface scale, not one compromise for the
whole screen.** Make a browser or file manager more compact to fit more content,
while keeping another app's text and controls larger. Other applications and
the taskbar keep their own scale. This changes the app's Android density, not
the monitor resolution or just the zoom of one web page.

Open an app's context menu in Desktop and choose **Application settings**.
Select **Custom** and adjust **Interface scale** from **50% to 200%** of the
display's density. Lower values make the interface smaller; higher values make
it larger. **System** removes the app-specific override. Saved custom profiles
are also available under **Settings > Application profiles**.

The setting applies to running managed windows and is remembered for later
Desktop sessions. It follows the app through freeform, snap, maximize and true
fullscreen, using the selected display's density as its baseline. When the app
returns to ordinary phone use, or Desktop closes, MagicDesk removes the active
density override without forgetting your saved Desktop preference.

Apps still choose their own layouts: reducing DPI can give an adaptive app
more logical space, but cannot create a tablet interface it does not implement.
Per-app DPI is a managed Desktop feature, not a system-wide override for an
app outside MagicDesk's session.

## Tools Without Desktop

Open Files or a terminal directly from Phone Control Panel, on the phone or a
selected display. No Desktop setup is needed: tools use ordinary fullscreen
Activities outside a managed session and the existing window controller inside
one. Opening a tool does not acquire HOME.

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

## Downloads

**[Latest release](https://github.com/mekhontsev/magicdesk/releases/latest)**
is the place to start for regular use. It contains the latest numbered official
release, its APK and release notes. Read the notes to see what changed and which
limitations apply; the rest of this README may also describe features still in
development.

**[Development APK](https://github.com/mekhontsev/magicdesk/releases/download/development/MagicDesk-development.apk)**
is the rolling build of `main`, published after its required CI checks pass.
Use it to try unreleased features and fixes before the next numbered release.
It uses the regular MagicDesk package and release signing certificate, not the
separate MagicDeskTest identity, and may be less stable than a numbered release.
Include its full version from Diagnostics when reporting a problem.

## Requirements And Setup

The APK requires **Android 14+**; managed **Desktop requires Android 15+**.
The native helpers currently target ARM64. Android 14 device validation is
pending. Windowing capabilities, external video support and firmware behavior
vary by device; see [tested coverage and limitations](docs/compatibility.md)
and the [API-level contract](docs/runtime-api-levels.md).

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

**[Getting started](docs/getting-started.md)** is the step-by-step guide from
installation to your first workspace. It explains Shizuku authorization,
independent Files and terminal tools, Termux permissions, display creation and
Desktop setup, as well as closing, updating and removing MagicDesk. Use it when
you need the actual setup sequence rather than the feature overview here.
[Compatibility](docs/compatibility.md) separates standard support, device
observations and optional vendor controls.

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
| `Win+N` / `Win+Q` | Notifications / Quick controls |
| `Win+I` | MagicDesk Settings |
| `Win+Print Screen` | Capture desktop |
| `Win+Shift+Print Screen` | Start/stop recording |
| `Ctrl+Space` | Next configured physical-keyboard layout |
| `Win+/` | All shortcuts |

## Security

Found a potential vulnerability? Use [private security reporting](https://github.com/mekhontsev/magicdesk/security/advisories/new),
not public issues or the support bot. See the [security policy](SECURITY.md).

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

## Community And Support

**[r/MagicDesk](https://www.reddit.com/r/MagicDesk/)** is the public subreddit
for MagicDesk discussions, announcements and user setups. Share your workspace,
compare experiences across devices, ask questions or propose improvements.
Each topic has its own discussion thread, so other users can find and build on
the same conversation.

**[Telegram community](https://t.me/magicdesk_android)** is the shared place
for release announcements, questions, device experiences and workflow ideas.
Use it to discuss how you use MagicDesk and what you would like to improve.
The support bot below is a separate private conversation for a specific report
and its follow-up questions; posting on Reddit or in the Telegram community
does not submit a bot case.

### AI-Assisted Support

**Report a problem and try a proposed fix from the same Telegram chat.**
[MagicDesk Support Bot](https://t.me/MagicDeskSupportBot) connects your
diagnostics to an AI-assisted development loop: GitHub Copilot can investigate
the report, ask follow-up questions and propose code changes. When a candidate
build succeeds, the bot sends you a **MagicDeskTest APK** to try. Send the
results back to continue the same case, without building the app yourself or
needing a GitHub account.

Join the [Telegram community](https://t.me/magicdesk_android), open the bot
privately, and follow `/start`. Send the complete Diagnostics report and steps
to reproduce, then `/submit`. Answers and test results also need `/submit`;
`/status` shows progress and what to do next.

Each APK comes with links to its exact source, changes and GitHub build, plus
its SHA-256. Sources and builds are published in the separate
[MagicDeskTest repository](https://github.com/mekhontsev/magicdesk-test-builds).
Follow the commit link in your APK message to inspect the proposed patch and
the sources for that particular build; the repository's `main` is the shared
test baseline, not your individual candidate. These are experimental support
builds, not another official release channel.

Reports and conversations go to a private support lab, but generated source
can contain report details: **do not send secrets or personal files**.

The service is experimental and processing capacity is limited. Test APKs use
a separate package and signing key; they are not official releases or verified
fixes. Installation is manual; merging a patch into MagicDesk requires
maintainer review. Do not run regular and test Desktop sessions together.
See [Telegram support](docs/telegram-support.md) for the steps, privacy and
testing precautions. [GitHub issues](https://github.com/mekhontsev/magicdesk/issues)
remain available for conventional bug reports.

## Diagnostics And Development

Reproduce a problem, open Diagnostics, and attach its complete compatibility
report and exact steps to a [bot case](docs/telegram-support.md) or GitHub issue.
Reports omit user files, account data, notification contents and the installed-app
catalog, but logs can contain filenames and package names: review before sending.
Self-tests are explicit, interactive checks, not background monitoring or a
universal firmware guarantee.

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
- [Telegram support and test builds](docs/telegram-support.md)
- [Desktop Entry files](docs/desktop-entries.md)
- [Fullscreen transitions](docs/fullscreen-transitions.md)
- [Privilege boundaries](docs/privilege-modes.md)
- [Validation plan](docs/testing-backlog.md)
- [Contributing](CONTRIBUTING.md)

## Project

- Author: [Dmitry Mekhontsev](https://github.com/mekhontsev)
- Community: [Reddit](https://www.reddit.com/r/MagicDesk/) and
  [Telegram](https://t.me/magicdesk_android)
- Package: `io.github.mekhontsev.magicdesk`
- Minimum APK SDK: 34; managed Desktop: 35
- Target SDK: 37
- License: [MIT](LICENSE)
