# Getting Started

MagicDesk has independent tools and automation on Android 14+, with managed
Desktop on Android 15+. These instructions describe the current development
build. See [Compatibility](compatibility.md) for tested devices and
[runtime API levels](runtime-api-levels.md) for per-service coverage and remaining
device checks.

## Install

Install the [stable APK](https://github.com/mekhontsev/magicdesk/releases/latest)
or the [development APK](https://github.com/mekhontsev/magicdesk/releases/download/development/MagicDesk-development.apk),
then open MagicDesk. Phone Control Panel is the starting point for tools,
display selection and Desktop.

For shell-backed operations:

1. Install official [Shizuku](https://github.com/RikkaApps/Shizuku/releases).
2. Start its server using a method from the
   [Shizuku setup guide](https://shizuku.rikka.app/guide/setup/).
3. Authorize MagicDesk when it requests Shizuku access.

[Shevery](https://github.com/HmnDev-Tech/shevery) is a compatible alternative
using the same authorization and UserService API. Before replacing Shizuku,
use **Exit MagicDesk** to close active sessions. Uninstall the Shizuku manager
before installing [Shevery](https://github.com/HmnDev-Tech/shevery/releases/latest):
their Android permissions and provider authorities conflict. Start Shevery's
server, then in MagicDesk's **Settings > Integrations** select **Shizuku** for
**Privileged service** and set **Shizuku manager package** to
`com.hamondev.shevery`. Exit and reopen MagicDesk, then grant access through
Shevery. Keep **Limits > Maximum access > Shell** for UID 2000.

Alternatively, on a rooted phone, choose **Settings > Integrations > Privileged
service > Root (su)** and restart MagicDesk. Approve its request in the root
manager. **Settings > Limits > Maximum access** defaults to **Shell** (UID 2000)
for either direct root or root-backed Shizuku. Choose **Root** to permit UID 0,
or **App only** to disable privileged startup. These changes apply after full
Exit and reopen; Termux and Desktop have independent Limits switches. No root is required for the
normal Shizuku path. See [Privilege boundaries](privilege-modes.md).

MagicDesk does not start Shizuku or Shevery itself or require root. After a reboot,
the selected server may need restarting, depending on its startup method. Missing shell
access does not prevent ordinary UI or independently authorized Termux
sessions from opening.

The control panel shows a full-width status and clickable **Access**, **Termux**
and **Desktop** summaries. Access reports the connected service as shell, root
or none, with an explicit authorization action. Termux distinguishes missing
prerequisites, an available service and a verified command connection; see
[Linux application setup](#open-linux-applications).
Desktop checks windowing setup and any pending Android restart. Its dialog
explains the requirements and opens setup only when requested. It does not
require an already started session, and an app restart
does not undo completed device setup. A missing privileged service or Desktop
setup does not invalidate a ready Termux integration.
**Limited** means the framework does not advertise its Desktop provider;
MagicDesk can still use supported freeform operations, but system captions and
window controls may be incomplete. The dialog and Diagnostics explain that
capability separately from missing setup.

## Open Tools

Open **Apps** in the control panel, then choose an application or **Terminal
sessions**. The selector beside Start search defaults to **Current**, the screen
containing Start. Select another display there to launch on it.
Without shell/root access, Apps can select displays exposed to MagicDesk by
Android and retains saved **Recent** entries. Android decides whether each app
may launch on the destination. Global running-task queries and transfers of
existing tasks require the privileged service.

Outside Desktop these are ordinary fullscreen Activities. If the destination
already has a MagicDesk Desktop session, the tools use its managed window path.
Neither action starts a session implicitly or requires Desktop provisioning.

Files and Android-shell Console need the authorized privileged service. Termux Console needs
Termux, external app commands enabled in its configuration, and MagicDesk's
Termux `RUN_COMMAND` permission. Termux and Android-shell sessions use different
UIDs and filesystem access.

Closing an ordinary terminal window detaches it. **Terminal sessions** can reopen
the same retained session; **End session** terminates it. Retention lasts only
while the MagicDesk process and transport survive. Optional tmux sessions
inside Termux provide a separate lifetime for longer-running programs. Closing
a managed tmux window releases only its client PTY; opening the session again
attaches a new client without restarting its programs.

See [Workstation tools](workstation-tools.md).

## Open Linux Applications

Enable external commands in Termux's `~/.termux/termux.properties` with
`allow-external-apps=true`, reload its settings, and grant MagicDesk the requested
Termux `RUN_COMMAND` permission. Tap **Termux** in Phone Control Panel for
**Grant permission**, **Copy setup command**, **Open Termux** and **Check connection**.
Paste and run the copied command in Termux; it preserves other settings and can
be run again. MagicDesk checks the connection once when its UI opens with these
prerequisites available. **Ready** confirms command execution and a returned result;
**Available** means execution has not been verified (including a reply timeout).
The check can start Termux's background service, but does not open its window or
create a terminal session. Use **Check connection** to retry after changing settings.
Install the X11 repository, keyboard data and
an application in Termux, for example:

```sh
pkg install x11-repo
pkg install xkeyboard-config gimp
```

Open Start and search for GIMP. MagicDesk discovers installed Termux `.desktop`
launchers when Start opens. The X server is embedded; no separate Termux:X11 APK
is required. Choose the display and window mode with Start's normal controls.
Interactive Linux graphics launches and reopening an existing window on its
current display do not require Desktop or shell access, including Android-allowed
secondary displays. Display resource management, existing-task transfers and background
placement require the privileged service.

**Linux graphics** manages retained X11 and Wayland sessions. Choose **New
graphical session**, select the protocol and executor, and enter an optional
startup command. **Application windows** opens an individual client; the desktop
viewer opens a whole X11 screen or a Wayland session created with **Nested Linux
desktop**. A Wayland desktop command must run a nested compositor with its
Wayland backend, not start another display server on Android hardware.

Installed Termux graphical launchers default to X11. For a Wayland application,
use **New command app > Termux graphics**, select Wayland and enter its command.
See [Wayland session controls](wayland.md#session-controls) for examples.
In **New command app**, choose
**Linux (Termux)** for an installed proot-distro environment or a custom entry
script. Choose **Linux (Shell / root)** for a prepared chroot without Termux.
Chroot entry requires actual root access; graphical Shell launchers also need
an Android-visible XKB data directory. MagicDesk supplies the terminal, X11 server
and Wayland compositor, while your Linux environment supplies its programs.
See [Embedded X11](x11.md), [Embedded Wayland](wayland.md) and
[Desktop Entry files](desktop-entries.md) for launch and file-exchange requirements.

## Install Linux With Shroot

Shroot runs ARM64 Linux distributions and OCI images through MagicDesk's
authorized shell service. It needs neither Termux nor Android root. Its kernel
requirements are checked when launched; unsupported kernels do not disable
MagicDesk's other tools. Shroot is experimental and not a security sandbox:
install only trusted images and programs.

Open a **Shell** console after configuring Shizuku, Shevery or direct root:

```sh
magicdesk download https://raw.githubusercontent.com/mekhontsev/magicdesk/main/scripts/install_linux.sh ~/install_linux.sh
sh ~/install_linux.sh
```

The URL is the maintained installer on `main`. Repeating the download replaces
the script only after a successful transfer, without changing installed Linux
environments. Under shell UID 2000, `~` is `/data/local/tmp/magicdesk/`; root
consoles use `/data/local/tmp/magicdesk-root/home`. Download before executing
so the script can read your answers from the terminal; do not pipe it into `sh`.
For the script shipped with a specific release, replace `main` with its tag,
for example `v2.0`.

Choose a distribution, environment name, GUI profile, graphics driver, language
and optional user. Installation and package-manager output stay in the console.
Software graphics is the default. Turnip requires a compatible Adreno device
and builds a private Mesa inside Linux; it does not replace Android drivers.
GUI recipes and distro-specific limits are listed in the
[installer guide](guest-runtime.md#linux-installer).

After installation, use **Refresh** beside Start's search field.
Installed application entries use the shared X11/Wayland launcher; whole-desktop
entries open a viewer. **Terminal sessions > New session > Shroot environments** opens a login
or lists that environment's running launches. No Desktop session is required.
Use `magicdesk-guest list` and `magicdesk-guest login NAME` from the console as well.
Existing environment names are never silently replaced;
`sh ~/install_linux.sh --name NAME --resume` explicitly resumes package setup.

## Choose Or Create A Display

The control panel lists displays exposed by Android, using the privileged
inventory when available. Select the phone, a wired/wireless display, or a
MagicDesk-owned virtual display, then use the actions below the list.
Resource creation, input control, Viewer and global task actions require
privileged access; ordinary interactive application launches do not.
Selecting a row does not
redirect input; **Control this display** does. Availability and display identity
are checked again when an action runs.
Newly connected or created displays are selected automatically without starting
Desktop or claiming input. On first opening, already connected external or virtual
displays take priority over built-in screens.
**Apps** opens the shared Start with that destination selected. Start can also
choose **Current** or another display, managed window/fullscreen or independent
placement, and request a new window where the application supports it.
Independent tasks appear in that display's application list, not Desktop Alt+Tab.

**Create display** creates a headless virtual display by default, suitable for
a scrcpy viewer; several may coexist. Enable **Preview on phone** at the bottom
of the dialog to use Android's preview surface instead. Its shared overlay
configuration cannot replace an existing overlay set. Preview does not support
protected content or availability while the phone is locked; these options are
cleared and disabled while preview is selected.

Dimensions and scale initially follow the selected display, using its saved DPI
when configured. You can override them before creation. A virtual display keeps
its own settings and the original display's profile identity, even when created
from another virtual display. Changing its scale or attaching it to a different
output does not change the original display's settings. Protection remains an
explicit choice, not an inherited setting.

Creation requires the privileged service but does not acquire
HOME or start Desktop. To view an existing display from a computer, use its ID
with the [scrcpy example](../README.md#magicdesk-on-a-computer-with-scrcpy).
MagicDesk does not bundle or start the computer-side viewer.

**Display Viewer** in Start opens another display inside a normal application
window. Select the destination and window mode using the same Start controls as
other applications, then choose the source inside Viewer. It mirrors the source
without changing its existing output attachment. In the display panel, select
an output and use **Show another display...** to choose a source by name, ID and
Desktop status. This opens an independent fullscreen Viewer or changes the
existing Viewer's source without moving applications. **Stop showing** appears
among the selected output's buttons and closes only that Viewer, retaining the source.
It is not required before unplugging the output or switching sources.

**Start desktop** on an untrusted public external display automatically uses a
portable workspace. **Start portable desktop here** does the same on other
outputs. An existing output Viewer is kept; otherwise MagicDesk reuses an available
virtual display with matching resolution and the fewest managed applications,
or creates one. Its existing scale and applications are preserved. The table
shows Desktop on the virtual source and the Viewer connection on the output.

**Connect wireless display** opens the available Android Cast settings or
platform connection UI. Complete the connection there and return to MagicDesk.
A mirroring image is not itself an active MagicDesk session: Android must first
publish a usable secondary display, which can then be selected.

The resource lifetimes are separate:

- **Close desktop** keeps the selected display.
- **Remove display** is available only for MagicDesk-owned displays. It closes
  a session on that display first and removes it after cleanup.
- Wired and wireless connections remain under system control.
- Stopping a viewer does not remove a MagicDesk-created display. Process loss
  releases MagicDesk's owned display resources.

Additional built-in screens on dual-screen devices are not yet verified
Desktop targets. A virtual display does not emulate another Android version.

## Control Display Input

Select the destination and press **Control this display** before using a
phone-attached mouse, keyboard or MagicDesk's phone touchpad there. This works
independently of Desktop, including on Android 14. **Release input** restores
the previous routing; opening an application alone never acquires input.

On Android 14, the selected screen must expose an input viewport and match
Android's shared pointer target. Follow the input setup's **System desktop mode
on external displays** guidance and reconnect the output when requested.
An output can accept applications without accepting a pointer. See
[Android 14 coverage](testing-backlog.md#android-14-shared-services) for the
tested Miracast path and the Google Cast limitation.

**Settings > Display input > Show keyboard on app display** requests the screen
keyboard beside the app instead of on the phone. It needs shell access, not a
Desktop session, and can be changed while input is active. Android and the
installed IME decide whether that placement is supported. A placement warning
does not disable the touchpad or hardware input; changing this preference does
not restart them.

## Prepare And Start Desktop

Only managed Desktop needs this preparation:

1. On Android 15+, open **Settings > Device setup**.
2. Complete the required freeform/resizable setup and any applicable platform
   checks.
3. Reboot only if setup requests it; cached framework configuration may require
   that step. MagicDesk never reboots automatically.
4. Restart Shizuku as needed and reopen MagicDesk.
5. Select a display and press **Start desktop**.

Each display can run its own Desktop. Select another display and start there
without closing an existing workspace. **Show desktop** returns to the selected workspace, keeping its managed
freeform tasks and demoting managed fullscreen tasks on that display.

MagicDesk temporarily acquires Android's HOME role for the first Desktop and
retains it until the last one closes. Without a phone Desktop, phone Start
defaults to independent fullscreen launches. With a phone Desktop, HOME reveals
its taskbar without replacing the foreground application. Each Start's Recent
history follows its selected destination and launch mode; Running applications
lists live tasks when access is available.
Start on each display is independent.
The control panel's **Apps** opens fullscreen Start, even without Desktop. Its
**Running applications** tab can move a specific task to the selected destination.
Every Start has its own launch-display choice; changing it does not switch input
or start a Desktop.

The revealed taskbar remains available while its Start or another panel is open.
An outside touch dismisses the temporary reveal.

**Settings > Desktop > New windows fullscreen on phone** makes fullscreen the
default for new phone Desktop windows. Explicit launch choices and saved window
mode or geometry take precedence; existing windows and other displays are unchanged.

**Settings > Session** has independent options for **Keep phone screen on**,
**Keep session active** (CPU wake lock), and **Disable adaptive brightness**.
They apply while any Desktop is running, including phone and simulated sessions.
Screen retention does not override explicit screen-off or lock. Disabling adaptive
brightness preserves the current brightness and still allows manual adjustment;
automatic mode is restored after the last Desktop closes unless the user changed
the mode meanwhile. These options are off by default.

**Settings > Appearance > System theme during Desktop** offers **Do not change**,
**Light** and **Dark**. It applies immediately to the whole Android system while
any Desktop is running. The previous theme returns after the last session closes,
unless it was changed in Android settings meanwhile. This is a session override,
not a separate theme for each display.

Notification-listener access is optional. Grant it only when MagicDesk's
notification center and popups are wanted.

## Customize Appearance

Open **Settings > Appearance**, choose **Global defaults** or **Current
workspace** when available, then **Choose theme**. Workbench, Material, Cupertino,
Glass Dock, Two Panels and Contours preview complete native layouts; **Keep changes** saves
the result, while cancellation restores the previous appearance.

Adjust panel edges, dimensions and components, Start's layout, colors, background
opacity and optional system blur. Use **Edit configuration** for the full JSON
document, or import a JSON/ZIP theme through Android's document picker. This
requires neither Termux nor shell access. Built-in tool styles apply without
Desktop; Desktop panels and wallpaper appear in an active workspace. Themes
affect MagicDesk, not other apps' content. MagicDesk publishes its window palette
to Android's native captions; the firmware controls their actual rendering.

**Use global defaults** removes the current workspace's overrides. Global changes
otherwise flow to workspaces that have not overridden those properties.
**Animate wallpaper** controls animated images, silent videos and AGSL shaders;
when paused, they display their poster. System power-saving and reduced-motion
policy also pause playback. Optional shader device signals collect only when
explicitly requested by a playing theme; CPU needs existing shell access.

See [Native Appearance](appearance.md) for composition, resources, wallpaper
selection, schema and automation. This is separate from **System theme during
Desktop**, which temporarily changes Android's own light/dark preference.

## Close, Exit And Recovery

**Close desktop** closes the selected workspace, releases input if it still owns
the selection, and restores its other session-owned changes. Surviving managed
applications become independent fullscreen tasks on the same display. Only loss
of that display returns them to phone fullscreen. It records the workspace for a later session, restoring
only tasks that are still alive. It keeps independent tools, retained terminals
and owned displays available. Other Desktops keep running; only closing the last
one returns HOME to its previous role state.

**Exit MagicDesk** also clears that live workspace record, closes built-in
windows, ends retained terminal and graphical sessions, removes owned displays and
stops the app process after cleanup. Neither action deletes Desktop files.
Reopening applies pending integration-package and privilege settings; Close
Desktop does not restart the app or apply those startup choices.

Unexpected display loss runs the same session cleanup. After process loss,
startup recovery relinquishes stale MagicDesk HOME ownership before waiting
for the privileged service. MagicDesk is not offered as an inactive HOME choice.
When there was no explicit HOME holder at session start, Android may show its
launcher chooser again; MagicDesk does not choose a replacement on the user's behalf.

The persistent notification opens Phone Control Panel. Its separate touchpad
action opens the phone input surface when external input control is available.
While a retained terminal exists, a **Terminal** action resumes the most recently
focused terminal even without Desktop, rather than creating another shell.

## Scale And Output

**Quick controls**, opened by the taskbar sliders icon, exposes display density,
audio, pointer speed and available hardware controls. Its gear opens **MagicDesk
settings**; **Android sound settings** opens the separate Android page.
Physical output resolution/refresh is selected with the target screen in Phone
Control Panel, before starting Desktop, and depends on Android and available
platform/SoC capabilities.
**System/native** relinquishes MagicDesk's forced output selection.

Application-specific interface scale is independent of display density.
Open the app's context menu in Desktop, choose **Application settings**, then
**Custom** and adjust **Interface scale** (50%-200%). Lower values make its UI
more compact; higher values enlarge it. **System** restores inherited density.
Use **Settings > Application profiles** to revisit saved custom profiles.
The preference applies to managed tasks, including already running windows,
and is remembered across Desktop sessions. Its active override is removed when
the task returns to ordinary phone use or Desktop closes. See
[Per-app DPI](../README.md#per-app-dpi) for behavior across window modes.

Desktop files are shared across displays; item positions and window bounds
adapt to each work area.

The optional **Settings > Android system** desktop-mode switch changes Android's
own external-display policy. It is not a MagicDesk session requirement and may
add system decorations. Follow its reconnect/restart guidance; Close does not
toggle it.

## Automation

Enable loopback MCP in Settings for a local client. Network access is a separate
opt-in with its own interface, port, token and permission set. Copy connection
data through the UI and treat it as a secret.

MCP does not require Desktop to be active. Its state reports missing service
prerequisites, and its entire command catalog remains visible when grants
change. Network HTTP is unencrypted: use only a trusted LAN or protected tunnel.

For remote file transfer, recoverable APK updates, exact self-test run tracking
and client reconnection, follow [Automation](automation.md).

## Updates And Removal

Development builds are published at the stable
[development link](https://github.com/mekhontsev/magicdesk/releases/tag/development)
with their commit, checksum and CI run. They use the release certificate but
contain unreleased changes.

Close Desktop before installing an APK. Terminal retention does not survive
package replacement. MCP's explicit APK-update protocol retains its installer
operation identity; a dropped connection is not a reason to submit the update
again.

Before uninstalling, close Desktop and remove owned displays. If Device Setup
changed system configuration, use **Device setup > Restore defaults** and
follow its reboot guidance before uninstalling.

Restore defaults removes desktop-windowing overrides and resets primary-display
size, density and scaling overrides. It restores system defaults, not an
arbitrary earlier installation's values. Android cannot run that cleanup after
removing the package; reinstall and authorize MagicDesk if it is needed.
Tools-only use does not require Desktop provisioning or its reset procedure.

## Problems

After reproducing a problem, open Diagnostics and attach the complete report
and exact steps to a [Telegram support case](telegram-support.md) or
[GitHub issue](https://github.com/mekhontsev/magicdesk/issues). The support bot
can relay AI follow-up questions and send an experimental APK with a proposed
fix; no local build is needed. Reports omit user files, account data,
notification contents and the installed-app catalog, but logs can contain
filenames and package names. Review before sending, and read the bot's privacy
and test-build precautions.

Desktop self-tests require an awake, unlocked Android 15+ phone, no other
Desktop session, and no user interaction during the run. Their guard/report
window shows progress and can stop the exact run. They do not validate
independent Android 14 tools. See the [validation plan](testing-backlog.md).
