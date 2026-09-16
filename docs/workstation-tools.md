# Workstation Tools

Files, Console, Termux and embedded X11 sessions are shared tools, not
Desktop-owned services.
The APK baseline is Android 14; managed Desktop requires Android 15. Tools open
as ordinary fullscreen Activities on the phone or selected display outside a
session, and as managed windows on its active display. Service requirements and
unverified API boundaries are in [Runtime API levels](runtime-api-levels.md).
Implementation boundaries are in [Architecture](architecture.md).

## Desktop Workspace

`/storage/emulated/0/Desktop` is the normal desktop filesystem. Files and
folders created there are visible as desktop items and can be opened, renamed,
deleted, moved, or dragged to compatible application windows.

Desktop layout and per-display configuration are stored atomically in:

```text
/storage/emulated/0/Desktop/.magicdesk/desktop.json
```

An optional custom wallpaper is stored beside that file. The hidden
`.magicdesk` directory is not rendered as a desktop item. Application runtime
state, diagnostics, recent applications, and Android widget bindings remain
outside the Desktop directory.

Desktop files, pins, shortcuts and managed application history are shared
across displays. Each logical workspace has its own Android widget host and
bindings; closing another workspace does not stop its widget updates. File
layout is shared, while widget placements refer to their own IDs. Display
configuration and DPI belong to each display's profile, independently of which
output currently shows it.

Application identities and saved app state include an Android profile serial.
This is a foundation for future profile support, not a work-profile or Private
Space launcher. Only the current profile is supported. The Desktop folder is
one shared workspace, not a directory per application profile.

The desktop supports:

- freely positioned files, folders, shortcuts, and Android widgets;
- native global drag and drop;
- folder, web, Android application, and command `.desktop` entries;
- shared file activation and context menus with built-in Files;
- the bundled MagicDesk wallpaper or a custom image selected in Files.

Use **Set as desktop wallpaper** on an image in Files to replace the background.
**Use MagicDesk wallpaper** in the desktop context menu restores the bundled
background. These actions do not change the phone's system wallpaper.

## Files

Built-in Files browses the filesystem visible to MagicDesk's authorized shell
identity. It supports:

- path and parent navigation;
- list and grid presentation;
- hidden files, sorting, filtering, and recursive name search;
- multi-selection, including `Ctrl` and `Shift` selection;
- create, rename, permanent delete, copy, cut, and paste;
- file properties with actual owner, mode, size, and timestamps;
- live directory updates and multiple independent Files windows;
- Android global drag and drop between Files, Desktop, and compatible apps;
- external editors and an in-window **Open with** chooser;
- user-confirmed APK installation or update.

Desktop and Files share one process-local, generation-safe copy/cut operation
buffer and the same item context-menu implementation. A bounded selection of
ordinary readable files is also published to Android's system clipboard as
temporary read-only content URIs, and Files or Desktop can import URI items
copied by another Android application. Directories, symbolic links, and large
selections remain internal because Android has no portable directory-clipboard
contract. Hold `Ctrl` when starting an internal drag to copy instead of move.
Conflicting copies receive a numeric suffix rather than silently replacing
existing data.

On Android 14, local-only drags such as directories stay in their source window.
Android 15+ permits those selections to cross MagicDesk windows using the
same-application drag flag. Exportable file-URI drags have their own grant path.

Desktop **Paste** and Files **Paste** also accept Android clipboard content.
Provider URI items are copied into the selected folder; plain text is saved as
a UTF-8 text file. The Desktop context menu can explicitly open a clipboard
file or web link and can share the current text/files through Android's chooser.
These actions inspect the clipboard only when invoked and do not maintain a
clipboard listener or history.

Android's share sheet exposes **Save to MagicDesk Desktop**. The receiver asks
for confirmation, then materializes shared provider content or text in the
Desktop folder while incoming URI permissions remain valid.

Long-running shell copy, move, and delete operations outlive their initiating
Files window. Reopening Files reconnects to their progress and cancellation
state.

Files uses Android's system default application when one exists. Its **Open
with** dialog can write the same system preferred-handler record. Executable
`.desktop` entries may also appear as one-time handlers when their standard
`MimeType` list matches the selected file.

Files shares an individual selection with another Android application through
a temporary content URI. The receiving application does not inherit
MagicDesk's shell identity or unrestricted filesystem access.

## Desktop Entry Files

MagicDesk supports a bounded freedesktop-compatible `.desktop` subset for:

- folders and web links;
- Android applications and published shortcuts;
- Android shell commands;
- Termux commands;
- embedded X11 applications and whole Linux desktops;
- composite Android viewer and external-process launches.

Entries can select a working directory, launch mode, execution backend, MIME
types, and standard file arguments such as `%f`, `%F`, `%u`, and `%U`.
Dropping files onto a compatible executable entry supplies those arguments.

**New terminal app** is available from the Desktop and Files background menus
and from the Console toolbar. The Console variant preserves that terminal's
backend and current working directory. Executable files and shell scripts also
offer **Add as terminal app** in the shared file context menu. MagicDesk writes
the result as a normal `Type=Application` file under the Desktop directory;
there is no parallel application database.

Command applications from the Desktop directory appear in Start and Start
search. A file-argument field enables drag-and-drop. Combining it with a
matching `MimeType` list also exposes the same entry in Files' **Open with**
chooser, so all three launch surfaces share one descriptor and one launch
pipeline.

See [Desktop Entry files](desktop-entries.md) for the complete field contract,
launch precedence, validation, and examples.

## Console

Every retained Console session owns an independent process attached to a real
pseudo-terminal. A window is its optional presentation, not its lifetime owner.
Closing an ordinary window detaches it; **Terminal sessions** can reattach the
same PTY.
**End session**, shell exit, transport failure or runtime exit terminates it.
Sessions survive Close Desktop but not MagicDesk process death or APK replacement.
Managed tmux connections have a different close contract: only the client PTY
ends, while tmux retains its session and programs.
The built-in backend runs `/system/bin/sh` with the authorized
shell identity; the optional Termux backend runs the user's configured Termux
shell and installed tools. Both provide:

- ANSI and true-color output;
- scrollback and alternate-screen applications;
- direct hardware and software keyboard input;
- terminal mouse reporting;
- text selection, copy, and paste;
- live terminal resize;
- `Tab`, `Ctrl+C`, and other normal terminal keys;
- current-directory tracking and a direct action to open that directory in
  Files.

The shared renderer also supports Nerd Font glyphs, clickable links, shell-marked
command history, static Sixel/Kitty images and fractional local-history scrolling.
See [Terminal integration](terminal-integration.md) for protocols, touch selection,
image export and the limits of smooth scrolling inside terminal applications.

Swipe with one finger or use the mouse wheel to scroll. In the ordinary shell,
this reads scrollback; in a mouse-aware application such as tmux, it sends wheel
events rather than dragging a selection. Alternate-screen applications without
mouse reporting receive arrow keys. Hold a finger before dragging to select
text locally, then use Copy. Hardware mouse selection keeps its normal meaning.
Shift+mouse wheel and Shift+PageUp/PageDown read the Console's own scrollback
without sending input to the terminal application.

**Settings > Console > Default font size** chooses the size for new windows,
from 8 to 40 sp (14 sp by default). An existing window keeps its own size; use
its **Font size** toolbar action, pinch with two fingers, or hold Ctrl while
scrolling the mouse wheel. Reset in the window's size dialog selects the current
default. The override survives Activity recreation and does not change other
windows or stored defaults. Fonts use Android's sp conversion, including the
current display density and accessibility text scaling. A change resizes the
existing terminal grid and PTY without restarting shell or tmux.

Both shell-backed and Termux-backed Console windows use Android's system text
clipboard. Clipboard contents are read only for an explicit Paste action; no
background synchronization or clipboard history is maintained.

The collapse button hides the Console toolbar for that window. A compact
restore button remains at the terminal's top-right corner; a newly opened
Console starts with its toolbar visible. `Ctrl+Shift+M` toggles the same toolbar,
following the Konsole menu-bar convention.

Files and Desktop items can be dropped onto Console to insert safely quoted
paths at the command cursor. A selected absolute output path can be revealed
in Files after shell-side validation.

Android-shell PTY commands, background shell Desktop Entries, and MCP shell commands share
one sanitized Android shell environment with stable `HOME`, `PATH`, temporary,
and XDG directories. The shell UID and permissions are shown in Console and
Diagnostics.

The explicit **Run script** context action opens Console and submits the quoted
command once its PTY is ready. Ordinary file opening uses the selected handler
and does not execute the script through this action.

## Termux

**Settings > Integrations** accepts a compatible Termux application's package
name, defaulting to `com.termux`. The Shizuku manager package is configurable in
the same section. Both choices apply only after restarting the MagicDesk
process; saving a choice does not switch active connections or terminals.
There is no built-in list of forks. A Termux fork must retain the standard
`RUN_COMMAND` protocol and a supported service permission. Changing the package
name cannot make an incompatible command API compatible.

When Termux is installed and external application commands are enabled,
MagicDesk can open an independent Termux-backed Console at the current Files
directory. It uses the same renderer, input, resize, selection, drag-and-drop,
current-directory tracking, task lifecycle, and MCP `terminal.*` operations as
the Android-shell Console. Multiple sessions own independent shells; closing a
window detaches only its presentation.

When the optional `tmux` package is installed inside Termux, **Terminal sessions**
combines retained terminals and tmux sessions in one list, without duplicating
a session for its attached MagicDesk client. The same picker appears in the
control panel's Apps launcher and console toolbars. Closing or detaching a managed
tmux window disconnects only its client; reopening attaches to the retained session.
Explicit termination of a tmux session affects all its windows and clients and
asks for confirmation. Discovery is on demand, not an idle background query.
Ordinary Termux app tabs are not PTY streams exposed by the command API and are
not shown in this list.

For Termux-backed windows, Open tasks identifies the current PTY foreground
program, such as `mc` or `nvim`, and uses a sanitized OSC terminal title as
additional context. The same shell PID, foreground PID/process group,
executable, title, and derived task label are available to MCP terminal status.

MagicDesk installs its small versioned PTY relay atomically inside Termux's
private home through the documented `RUN_COMMAND` stdin channel. The relay
connects back only over an authenticated loopback socket. MagicDesk neither
copies Termux executables into the APK nor reads the Termux application's PTY
registry. Session pickers, Task Manager and automation query tmux's own registry
on demand.
Directories under Termux's private home cannot be opened in Files when the
authorized Android shell identity cannot read them.

A `.desktop` entry can run a command through Termux or combine it with an
Android application launch. The entry owns its command; MagicDesk does not
replace it with a package-specific startup or reconnect script. See
[Desktop Entry files](desktop-entries.md).

## Linux Applications And Desktops

MagicDesk embeds its X11 server and renderer; installing the standalone
Termux:X11 APK is unnecessary. Installed Termux graphical applications with
launchable `.desktop` entries appear in Start. They can open as independent
fullscreen applications or managed Desktop windows with native Android captions.
The X11 session manager can also launch a command, open a whole Linux desktop,
or present individual windows from a retained session.

Focused X11 windows exchange text, HTML, PNG images and files with Android's
clipboard. Copy drag-and-drop works between compatible Android and X11 windows,
including different X11 sessions. File access remains under the selected
Termux UID; container-private paths need explicit shared storage or bindings.
Desktop is not a prerequisite. See [Embedded X11](x11.md) for setup, DPI,
session lifetime, container examples and transfer limits.

## Task Manager And Desktop Controls

The desktop taskbar and Start menu operate on real Android tasks. Additional
items remain reachable through an icon-and-name overflow list when the taskbar
is full.

Start provides application, file, MagicDesk setting, and desktop-action
search. Application context menus expose supported Android shortcuts, launch
modes, new-window requests, Android application information, pinning, and
`.desktop` shortcut creation.

While another display has Desktop but the phone does not, phone HOME embeds
the same Start content and defaults to ordinary phone fullscreen launches.
Its Recent page uses Android's phone tasks; managed application history is
shared across workspaces and includes Android and X11 launch recipes.
Every Start surface offers the same destination, managed/independent placement
and new-window controls. Independent tasks stay outside Desktop's Alt+Tab and
taskbar, and can be selected from the control panel's display row actions.

Task Manager provides:

- Android tasks, retained terminals, tmux and X11 sessions in Applications;
- process trees with name, CPU, RSS memory and PID sorting, plus a Termux filter
  that also includes processes started outside MagicDesk;
- exact-task focus/close and explicit session termination through their owners;
- explicit package force-stop and identity-checked process TERM/KILL;
- a lifecycle-bound application log viewer filtered by Android UID.

Application rows can be sorted by name, CPU and memory too. CPU uses 100 percent
per core for a process/session; RSS can include shared memory. Unknown samples
are not reported as zero. X11 session figures cover the server, while its client
processes remain visible in Processes. Resource sampling stops when the tool
is no longer visible; Task Manager does not require Desktop.

The desktop also integrates:

- Tasks and Show Desktop views;
- notification center, actions, dismissal, and transient popups;
- calendar and battery panels;
- physical keyboard-layout state;
- selected-display screenshots and recording;
- media volume and connected audio-output selection;
- phone touchpad and text input;
- optional platform hardware controls such as charging separation, fan, pump,
  and temperature readings.

Recording supports `Auto`, `Microphone`, and `No audio`. Auto uses a declared
platform internal-audio backend when available and otherwise records video
without sound.

## Window And Input Behavior

Applications remain native Android tasks. MagicDesk can launch them windowed
or fullscreen, snap them, restore remembered freeform bounds, switch exact
tasks with `Alt+Tab`, and preserve live layouts across desktop sessions.

On every platform, input control associates physical device locations with a
selected display, independently of Desktop. Android delivers the original events,
including repeat, modifiers, layouts, hover and right click. Desktop selects
input after preparation; closing a workspace releases it only if that workspace
still owns the selection. A key-only Accessibility filter handles shortcuts
when the selected display has a prepared Desktop. **Release input** restores
the prior Android routing rather than forcing display 0.

The phone touchpad uses one virtual relative mouse, with Android acceleration.
The user's normal phone IME connects directly to the external editor through
Android's display IME policy. MagicDesk does not select another IME or relay
editor text. Layout cycling uses Android's physical-layout mappings and enabled
IME subtypes; shortcut filtering does not depend on a particular keyboard app.
**Show keyboard on app display** in Settings or the taskbar context menu requests
the keyboard on the editor's display instead of the phone. Support depends on
Android and the selected IME. Clicking bare Desktop requests keyboard dismissal
without making the Desktop host focusable.

## Settings

The Settings window controls persistent MagicDesk behavior, including:

- taskbar auto-hide;
- single-click file activation;
- automatic phone-touchpad startup;
- keeping an active desktop session awake;
- remembered application launch mode;
- application-specific interface scale;
- loopback and optional network MCP, with separate tokens and permission sets;
- common compatibility policies and the optional Android system desktop-mode
  setting.

Settings has section dividers and a persistent section-navigation menu.
Phone Control Panel keeps Settings in its header, followed by status, integration
summaries, the display table and its selected-display actions. Shared Wireless,
Create display and input/power controls follow; Exit requires confirmation.
**Apps** in the selected-display actions opens the common launcher for independent
tools too. The display section is hidden without shell access; unavailable actions
otherwise retain their slots and are disabled.
Status occupies one full-width row. The underlined **Access** and **Termux**
controls share the next row and open short setup/status dialogs. Access reports
the connected service identity; Termux shows Not installed, Setup required or
Ready, with details in its dialog. Termux readiness is independent of shell access.

The taskbar sliders icon opens **Quick controls**, a content-sized panel above
the taskbar with audio, interface scale, pointer speed, and available hardware
controls. Its gear opens MagicDesk settings; the explicitly labelled Android
sound action opens Android's settings. Physical output mode belongs to the
selected screen in Phone Control Panel before Desktop startup, not to global
preferences.
