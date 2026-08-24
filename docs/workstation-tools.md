# Workstation Tools

MagicDesk combines native Android tasks with a desktop filesystem, built-in
Files, an interactive Console, Task Manager, and common desktop controls. This
document describes those user-facing tools. Their implementation boundaries
are documented in [Architecture](architecture.md).

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

Desktop files, widgets, pins, shortcuts, and recent applications are global
across displays. Positions are proportional to the active work area, while
output mode, Fill display, and DPI remain per-monitor settings.

The desktop supports:

- freely positioned files, folders, shortcuts, and Android widgets;
- native global drag and drop;
- folder, web, Android application, and command `.desktop` entries;
- shared file activation and context menus with built-in Files;
- a custom image or the phone's current static wallpaper.

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

Desktop and Files share one process-local copy/cut buffer and the same item
context-menu implementation. Hold `Ctrl` when starting an internal drag to
copy instead of move. Conflicting copies receive a numeric suffix rather than
silently replacing existing data.

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
- Android applications and manifest shortcuts;
- Android shell commands;
- Termux commands;
- composite Android viewer and external-process launches.

Entries can select a working directory, launch mode, execution backend, MIME
types, and standard file arguments such as `%f`, `%F`, `%u`, and `%U`.
Dropping files onto a compatible executable entry supplies those arguments.

See [Desktop Entry files](desktop-entries.md) for the complete field contract,
launch precedence, validation, and examples.

## Console

Every Console window owns an independent process attached to a real
pseudo-terminal. The built-in backend runs `/system/bin/sh` with the authorized
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

Files and Desktop items can be dropped onto Console to insert safely quoted
paths at the command cursor. A selected absolute output path can be revealed
in Files after shell-side validation.

Android-shell PTY commands, background shell Desktop Entries, and MCP shell commands share
one sanitized Android shell environment with stable `HOME`, `PATH`, temporary,
and XDG directories. The shell UID and permissions are shown in Console and
Diagnostics.

A `.sh` file can be opened in Console from its file context menu. Opening the
file prepares the command; execution remains an explicit terminal action.

## Termux And Termux:X11

When Termux is installed and external application commands are enabled,
MagicDesk can open an independent Termux-backed Console at the current Files
directory. It uses the same renderer, input, resize, selection, drag-and-drop,
current-directory tracking, task lifecycle, and MCP `terminal.*` operations as
the Android-shell Console. Multiple windows own independent shells; closing a
window closes only its PTY.

MagicDesk installs its small versioned PTY relay atomically inside Termux's
private home through the documented `RUN_COMMAND` stdin channel. The relay
connects back only over an authenticated loopback socket. MagicDesk neither
copies Termux executables into the APK nor reads Termux's session registry.
Directories under Termux's private home cannot be opened in Files when the
authorized Android shell identity cannot read them.

When Termux:X11 is available, its Start item launches or reconnects the X
server through Termux's documented command service and opens the viewer as an
ordinary MagicDesk task. Its startup command is configurable in Settings.

A `.desktop` entry can combine a Termux command with the Termux:X11 Android
package to create a named launch preset. These presets coordinate the Android
viewer and command launch; they do not claim ownership of the X server or
create a separate MagicDesk container format.

## Task Manager And Desktop Controls

The desktop taskbar and Start menu operate on real Android tasks. Additional
items remain reachable through an icon-and-name overflow list when the taskbar
is full.

Start provides application, file, MagicDesk setting, and desktop-action
search. Application context menus expose supported manifest shortcuts, launch
modes, new-window requests, Android application information, pinning, and
`.desktop` shortcut creation.

Task Manager provides:

- running application tasks;
- live CPU and memory indicators;
- exact-task focus and close;
- explicit package force-stop;
- a lifecycle-bound application log viewer filtered by Android UID.

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

The Standard Android driver leaves correct system keyboard and mouse routing
untouched. A platform extension may activate MagicDesk's input bridge only on
firmware that misroutes external devices. That bridge preserves normal key
repeat and physical layouts, routes right click as a secondary click, handles
hot-plug, and keeps `Ctrl+Space` synchronized with keyboard subtypes exposed by
the active Android input method.

## Settings

The Settings window controls persistent MagicDesk behavior, including:

- taskbar auto-hide;
- single-click file activation;
- automatic phone-touchpad startup;
- keeping an active desktop session awake;
- remembered application launch mode;
- Termux:X11 startup command;
- local MCP automation and its independent developer and shell-access gates.

Display-specific output controls and DPI remain in the System panel rather
than global Settings.
