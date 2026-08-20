# Desktop Entry Files

MagicDesk reads `.desktop` files from `/storage/emulated/0/Desktop` and from
directories opened in built-in Files. It supports a bounded subset of the
[freedesktop Desktop Entry specification](https://specifications.freedesktop.org/desktop-entry/latest/)
for folder links, web links, Android applications, Android-shell commands, and
Termux commands.

## Command entries

The smallest executable entry is:

```ini
[Desktop Entry]
Type=Application
Name=List processes
Exec=ps -A
Terminal=true
```

`Exec` is limited to 4096 characters and cannot contain a NUL character. `%%`
represents a literal percent sign. MagicDesk expands these standard field
codes:

- `%f` and `%u`: the first supplied local file or URI.
- `%F` and `%U`: every supplied local file or URI as separate arguments.
- `%c`: the entry's display name.
- `%i`: `--icon` followed by the configured icon, when present.
- `%k`: the absolute path of the `.desktop` file, when known.

Files can be supplied by dropping one or more Desktop or Files items onto an
executable `.desktop` item containing `%f`, `%F`, `%u`, or `%U`. If an entry is
opened normally without supplied files, those file and URI fields are removed.
Expanded values are shell-quoted individually. Multi-value codes must occupy a
complete argument. Unknown codes, malformed quoting, and an expanded command
over the size limit reject that launch.

An executable entry placed directly in `/storage/emulated/0/Desktop` can also
act as an **Open with** target. It must accept at least one file or URI field
code and declare the standard semicolon-separated `MimeType` list:

```ini
[Desktop Entry]
Type=Application
Name=View text
Icon=utilities-terminal
Exec=/system/bin/cat %f
MimeType=text/plain;application/json;
Terminal=true
```

Exact MIME types, major-type wildcards such as `image/*`, and `*/*` are
supported. Matching is case-insensitive. Entries without `MimeType` do not
clutter the chooser. A command selected this way is a one-time launch target;
the chooser's **Always** action remains limited to Android activities because
it writes Android's real preferred-handler record rather than a MagicDesk-only
association.

Commands without field codes keep their raw shell syntax, including pipes,
redirections, and command separators. A literal percent in such a Desktop
Entry must still be written as `%%` according to the Desktop Entry format.

`Terminal` selects how the command is presented:

- `Terminal=true` opens a command window for the selected backend. The shell
  backend uses MagicDesk Console; the Termux backend uses a Termux session.
- Missing or false `Terminal` runs the command in the background and reports
  startup or failure through the desktop status UI.

The default backend is the Android shell identity authorized for MagicDesk:

```ini
X-MagicDesk-ExecBackend=shell
```

Shell commands run through `/system/bin/sh -c`. Their programs, filesystem
access, environment, and UID are those of the active shell service, not those
of a regular Android application and not those of Termux. With
`Terminal=true`, the command opens in MagicDesk Console.

The standard optional `Path` field selects an absolute working directory:

```ini
Path=/storage/emulated/0/project
```

For the shell backend it becomes Console's initial directory or a checked
`cd` before a background command. For the Termux backend it is passed as the
working directory of Termux's `RUN_COMMAND` request. A relative or malformed
path invalidates the entry.

The optional Termux backend is selected explicitly:

```ini
X-MagicDesk-ExecBackend=termux
```

It runs `Exec` through Termux's documented `RUN_COMMAND` service and
`bash -lc`, using the Termux home directory and installed Termux packages.
Termux must be installed, external app commands must be enabled in Termux, and
the `RUN_COMMAND` permission must be granted to MagicDesk. With
`Terminal=true`, MagicDesk opens or reuses a named Termux session.

Unknown backend names invalidate the entry instead of executing the command in
an unintended environment.

Backend availability and capabilities are reported in Diagnostics. MagicDesk
assigns a stable bounded session ID to each command and records its latest
`preparing`, `running`, `delegated`, `finished`, or `failed` state. `delegated`
means that Console or an external backend accepted the command but does not
provide a completion event. This state is diagnostic: MagicDesk does not claim
ownership of independently running Termux or X11 processes.

## Android applications

MagicDesk-created Android application shortcuts contain a complete serialized
Intent and an `am start` representation:

```ini
[Desktop Entry]
Type=Application
Name=Example
Icon=com.example.application
Exec=/system/bin/am start --user current "intent:#Intent;component=com.example.application/.MainActivity;end"
X-MagicDesk-Package=com.example.application
X-MagicDesk-Activity=com.example.application.MainActivity
X-MagicDesk-Action=android.intent.action.MAIN
X-MagicDesk-Intent=intent:#Intent;component=com.example.application/.MainActivity;end
X-MagicDesk-WindowMode=windowed
```

Unless `X-MagicDesk-Default=true` explicitly requests the package's current
default launcher activity, `X-MagicDesk-Intent` takes priority and `Exec` is
only a portable fallback. MagicDesk never invokes both. The Intent path is
preferred for Android applications because it preserves extras, categories,
flags, and components while allowing MagicDesk to coordinate the destination
display and window transition.

A hand-written shell command may use normal `am start` command-line options,
including `-n` for a component, `-a` for an action, `-d` for a data URI, `-t`
for a MIME type, `-c` for a category, `-f` for Intent flags, and extras such as
`--es`, `--ei`, and `--ez`:

```ini
[Desktop Entry]
Type=Application
Name=Open example URL
Exec=/system/bin/am start --user current -a android.intent.action.VIEW -d 'https://example.com/'
Terminal=false
```

This raw shell form follows Android's `am` behavior and does not by itself
provide MagicDesk with structured launch metadata or a dynamic destination
display. Use a MagicDesk-generated shortcut or `X-MagicDesk-Intent` when native
desktop window placement matters.

## Window modes

`X-MagicDesk-WindowMode` accepts:

- `auto`: restore the application's remembered MagicDesk mode and bounds.
- `windowed`: request a freeform window.
- `fullscreen`: request a true fullscreen task.

Unknown or missing values use `auto`. Window modes apply when the entry also
identifies an Android package that MagicDesk can prepare as a desktop task.
They do not alter a generic background shell process.

An entry with both `X-MagicDesk-Package` and executable `Exec`, but without
`X-MagicDesk-Intent` or `X-MagicDesk-Default=true`, is a composite launch.
MagicDesk first prepares the package's Android task, then delegates `Exec`.
This is the generic mechanism used by viewer/server integrations.

## Termux:X11 profiles

A Termux:X11 profile combines a Termux command with the Android viewer package:

```ini
[Desktop Entry]
Type=Application
Name=X11 desktop
Icon=com.termux.x11
Exec=termux-x11 :1
X-MagicDesk-Package=com.termux.x11
X-MagicDesk-ExecBackend=termux
X-MagicDesk-WindowMode=windowed
```

MagicDesk prepares the Termux:X11 viewer through its normal task-transition
path, then starts or reconnects the X server using `Exec`. Creating a desktop
shortcut for Termux:X11 captures the current startup command from Settings;
the ordinary Start-menu icon continues to use the live Settings value.

The current integration reconnects to an already running X11 server. These
entries are launch presets, not ownership records for X11 processes and not a
multi-server session manager.

## Launch precedence

For `Type=Application`, MagicDesk resolves one launch path in this order:

1. The package's default Android launch when `X-MagicDesk-Default=true`.
2. `X-MagicDesk-Intent`, when present and valid.
3. `Exec` through `X-MagicDesk-ExecBackend`, defaulting to `shell`.

Keeping these paths mutually exclusive prevents an Android shortcut's
portable `am start` fallback from launching a second copy of the task.
