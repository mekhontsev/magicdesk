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

The built-in **New command app** editor creates this same format from the
Desktop or Files background menu and from the Console toolbar. Creating an app
from Console preselects that Console's Android-shell or Termux backend and its
current directory. An executable file or shell script can prefill the editor
through **Add as terminal app**. Ordinary command entries are stored under
`/storage/emulated/0/Desktop` and appear in Start. Linux launchers use the save
locations described below; all remain editable Desktop Entry files.

Select **Linux (Termux)** to create a guest launcher. **PRoot (proot-distro)**
lists installed environments only when selected, through the configured Termux
application's `RUN_COMMAND` service. **Custom script (chroot or other)** instead
accepts the absolute path of an executable entry script. **Linux (Shell / root)**
uses the same script contract through the authorized command service. All offer
**Terminal** (an empty command opens the guest login shell), **X11 application**,
or **Linux desktop**. Enter a command already installed in that environment.
The optional working directory is inside Linux, not Android or Termux.
**Linux user** optionally selects an existing guest account through `--user`;
leave it empty for the launcher's default (`root` inside PRoot, not Android root). No
account is created and no password is stored in the launcher.

Termux Linux entries are saved in the selected Termux environment's
`${XDG_DATA_HOME:-$HOME/.local/share}/applications/magicdesk-NAME.desktop`,
so the editor and shared launch services need Termux integration, not MagicDesk's
shell/root service or Desktop. A custom chroot script may separately require
root authorization for its own setup and entry. Shell Linux entries are saved
as ordinary Desktop files, without Termux. Graphical Shell entries additionally
specify an Android-visible XKB data directory from the prepared environment.
Start refreshes after saving; Recent retains the launch recipe and its selected
executor, including the package identity for Termux. Identical saves do not
create duplicates, and a different entry with the same name is not overwritten.
These launchers use ordinary
`Exec`, `Terminal`, and the existing `X-MagicDesk-X11Mode` presentation hint;
there is no separate distribution registry or executor.

Long-press or right-click a user-created Termux shortcut in Start and select
**Delete shortcut**. Confirmation removes the `.desktop` file and its entries
in both Recent histories, not the installed program, distribution or running
sessions. Package-installed Termux launchers do not offer this action.

For PRoot, the installed `proot-distro` must support `list --quiet` and `login --isolated`.
Graphical launches need `dbus-run-session` in the guest. The PRoot adapter passes
its allocated `DISPLAY`, binds the session's private Xauthority and shares the
X11 socket via `--shared-tmp`. Each graphical launch gets its own D-Bus session
and temporary `XDG_RUNTIME_DIR`. This is not a security sandbox. Arbitrary
Android file arguments are not mapped into the guest; the editor hides file
associations for these recipes. MagicDesk does not install distributions or scan guest apps.

### Custom Linux Entry Scripts

The script is an entry adapter for an already prepared environment, not a
container installer. It runs as the selected Termux UID or the already-authorized
shell service UID and receives:

```text
/absolute/entry-script [--user NAME] [--work-dir /guest/path] [-- PROGRAM ARG...]
```

Options are omitted when their fields are empty. A terminal with no command
omits `-- PROGRAM ARG...`; the script should enter the guest's login shell.
Otherwise, run the supplied argv inside the guest without reparsing or joining
it as shell text. MagicDesk supplies `/bin/sh -lc` and the requested guest
command; graphical launches additionally own a D-Bus session and a temporary
guest runtime directory, just as with PRoot.

For X11, the script inherits the dynamically allocated `DISPLAY` and private
`XAUTHORITY`, `MAGICDESK_X11_RUNTIME` and `MAGICDESK_X11_TMPDIR`. It must expose the X socket and authorization file to the guest,
adjusting guest paths if necessary, and preserve those values across any
explicit privilege change. Do not use a fixed display number or disable X
authentication. The script owns mounting, root authorization and matching
cleanup; retain the launched process lifetime rather than detaching it.
MagicDesk does not implicitly switch its privileged backend, mount a rootfs,
or store passwords. Interactive authentication can use terminal mode; graphical
entry scripts must arrange authorization without a terminal prompt.

See [`chroot-entry.sh`](../scripts/examples/chroot-entry.sh) for a root-only
prepared-rootfs example with launch-scoped mounts. MagicDesk never acquires root
on an individual recipe's behalf. Shell-hosted file exchange uses only the
session's shared `/tmp/magicdesk-x11/content` directory inside the guest.

Fixed-purpose scripts can be used directly as ordinary Shell or Termux command
entries, with optional X11 presentation. They need the option/argv contract only
to use the Linux editor's shared user, directory and command fields.

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

Non-graphical commands without field codes use raw shell syntax by default,
including pipes, redirections and command separators. `X-MagicDesk-ExecSyntax=argv`
selects literal arguments; X11 presentation always uses that argument mode.
A literal percent must be written as `%%` in either mode.

`Terminal` selects how the command is presented:

- `Terminal=true` opens a command window for the selected backend. The shell
  and Termux backends both use MagicDesk Console with their respective PTY
  transports.
- Missing or false `Terminal` starts X11 presentation when configured, otherwise
  runs the command in the background. Launch completion or failure is reported
  through the calling UI or automation result.

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
`Terminal=true`, MagicDesk opens a new Termux-backed Console and owns that PTY
as a retained terminal session. Closing its window detaches the view; explicit
session termination closes the PTY. Desktop Entry command tracking does not own
that lifetime.

X11 presentation is independent of the selected `shell` or `termux` executor:

```ini
[Desktop Entry]
Type=Application
Name=Firefox (Termux)
Exec=firefox %u
Terminal=false
X-MagicDesk-ExecBackend=termux
X-MagicDesk-X11Mode=application
```

It starts an authenticated, independently owned X server and presents client
windows through the ordinary Android launcher. Start also discovers installed
Termux `.desktop` entries automatically. The standalone Termux:X11 APK is not
required. Graphical recipes follow desktop-entry argument expansion even without
field codes; shell constructs require explicit `sh -c`. Non-graphical entries
may also select literal argv with `X-MagicDesk-ExecSyntax=argv`. `Terminal=true`
omits the X11 mode and selects the executor's Console path. Shell X11 recipes
also set `X-MagicDesk-X11KeyboardDirectory=/host/path/to/X11/xkb`. See
[Embedded X11](x11.md) for retention, multiple windows and container commands.

For a complete Linux desktop, use `X-MagicDesk-X11Mode=desktop` instead of
`application`, which presents individual client windows. Desktop mode presents
the whole X screen and retains the server after its Android window closes.
The PRoot editor generates this recipe for installed `proot-distro` environments.
For custom proot/chroot setups, use an explicit `Exec` script exposing the
supplied X socket and authorization to the guest. MagicDesk does not infer
which guest application or desktop command to start.

## Recent Launches

Every Start has one context-sensitive Recent section. Two private directories,
`files/recent/desktop/` and `files/recent/independent/`, hold global histories for
managed and ordinary launches, respectively. The selected destination and launch
mode determine which is shown: Auto uses Desktop history only if that destination
has a Desktop; Independent always uses ordinary history. The host window, shell
availability and a Desktop running on another display do not select the history.
Android applications, built-in tools, commands and X11 desktops use this same
Desktop Entry format, plus `X-MagicDesk-LastUsed` (Unix milliseconds),
`X-MagicDesk-Source` (the original entry path for field expansion), and
`X-MagicDesk-TermuxPackage` when a Termux execution environment is required.
This is separate from the user's desktop folder. Android entries retain their
profile identity, not an unqualified package name.

Each history keeps the latest 24 distinct recipes, one file per semantic launch identity.
Repeated use updates that file and ordering; renamed/copied launchers do not
create duplicates unless they change the effective command. Runtime task IDs,
X session IDs, display selection and New window requests are not history keys.
Search also includes remembered recipes from the selected history absent from
the installed catalogs. Built-in tools retain reusable launches; Shell Console
and Termux Console are distinct. Session attachments do not retain transient
terminal IDs, and X11 hosts remember the actual command/application, not a generic
viewer. One-shot prompts and shell infrastructure are excluded.
Fullscreen Start's separate Running section remains based on live Android tasks,
not persisted history, and reports unavailable access explicitly.

## Execution Status

Unknown backend names invalidate the entry instead of executing the command in
an unintended environment.

Backend availability and capabilities are reported in Diagnostics. MagicDesk
assigns a stable bounded session ID to each command and records its latest
`preparing`, `running`, `delegated`, `finished`, or `failed` state. `delegated`
means that Console or an external backend accepted the command but does not
provide a completion event to the launch tracker. This state is diagnostic:
Console still owns its PTY, while MagicDesk does not claim ownership of
independently running background Termux commands.

## Android applications

MagicDesk-created generic Android application shortcuts contain a complete
serialized Intent and an `am start` representation:

```ini
[Desktop Entry]
Type=Application
Name=Example
Icon=com.example.application
Exec=/system/bin/am start --user current "intent:#Intent;component=com.example.application/.MainActivity;end"
X-MagicDesk-Package=com.example.application
X-MagicDesk-AppIdentity=0|com.example.application
X-MagicDesk-Activity=com.example.application.MainActivity
X-MagicDesk-Action=android.intent.action.MAIN
X-MagicDesk-Intent=intent:#Intent;component=com.example.application/.MainActivity;end
X-MagicDesk-WindowMode=windowed
```

The serial in `X-MagicDesk-AppIdentity` identifies an Android profile, not a
display or a runtime user id. The example uses serial 0; generated entries use
the actual resolved serial. The Desktop folder itself stays shared, and file
positions remain path-based. Two entries may launch the same package in
different profiles once those profiles are supported. An explicit unavailable
profile fails before launch or Exec fallback. A portable entry without this
field is resolved in the current context.

Unless `X-MagicDesk-Default=true` explicitly requests the package's current
default launcher activity, `X-MagicDesk-Intent` takes priority and `Exec` is
only a portable fallback. MagicDesk never invokes both. The Intent path is
preferred for Android applications because it preserves extras, categories,
flags, and components while allowing MagicDesk to coordinate the destination
display and window transition.

An application action published through Android's shortcut service uses a
typed reference instead:

```ini
[Desktop Entry]
Type=Application
Name=Compose
Icon=com.example.mail
X-MagicDesk-Package=com.example.mail
X-MagicDesk-AppShortcut=compose
X-MagicDesk-WindowMode=auto
```

MagicDesk resolves `X-MagicDesk-AppShortcut` from the current published
shortcut list each time it is opened. It stores neither the shortcut's private
Intent nor a guessed `am start` fallback. If the publisher removes or disables
that id, the entry remains on disk but launch fails cleanly.

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

Unknown or missing values use `auto`. Window modes apply to an Android target
or to the Android host created for an X11 recipe. They require managed Desktop
placement for windowed mode; independent placement remains fullscreen.
They do not alter a generic background shell process. Start's explicit mode
selection can override the recipe's default for that launch.

An entry with both `X-MagicDesk-Package` and executable `Exec`, but without
`X-MagicDesk-Intent` or `X-MagicDesk-Default=true`, is a composite launch.
MagicDesk first prepares the package's Android task, then delegates `Exec`.
The command is explicit and is not rewritten according to the Android package.
An ordinary Start application icon launches only its Android application;
creating its default shortcut does not add a companion command.

## Launch precedence

For `Type=Application`, MagicDesk resolves one launch path in this order:

1. The package's default Android launch when `X-MagicDesk-Default=true`.
2. The published action identified by `X-MagicDesk-AppShortcut`.
3. `X-MagicDesk-Intent`, when present and valid.
4. `Exec` through `X-MagicDesk-ExecBackend`, defaulting to `shell`.

MagicDesk-generated entries use one semantic path. Generic Intent entries may
also carry a portable `am start` fallback, but MagicDesk never invokes both.
