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
represents a literal percent sign. MagicDesk does not yet expand file and URL
field codes such as `%f`, `%F`, `%u`, or `%U`.

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
