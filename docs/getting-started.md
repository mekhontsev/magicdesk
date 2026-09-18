# Getting Started

MagicDesk has independent tools and automation on Android 14+, with managed
Desktop on Android 15+. These instructions describe the current development
build. See [Compatibility](compatibility.md) for tested devices and
[runtime API levels](runtime-api-levels.md) for unverified boundaries, including
Android 14 native execution.

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

Alternatively, on a rooted phone, choose **Settings > Integrations > Privileged
service > Root (su)** and restart MagicDesk. Approve its request in the root
manager. **Settings > Limits > Maximum access** defaults to **Shell** (UID 2000)
for either direct root or root-backed Shizuku. Choose **Root** to permit UID 0,
or **App only** to disable privileged startup. These changes apply after full
Exit and reopen; Termux and Desktop have independent Limits switches. No root is required for the
normal Shizuku path. See [Privilege boundaries](privilege-modes.md).

MagicDesk does not start Shizuku itself or require root. After a reboot,
Shizuku may need restarting, depending on its startup method. Missing shell
access does not prevent ordinary UI or independently authorized Termux
sessions from opening.

The control panel shows a full-width status, followed by clickable **Access**
and **Termux** summaries. Access reports the connected service as shell, root
or none, with an explicit authorization action. Termux reports **Not installed**,
**Setup required** or **Ready**; its dialog explains permissions and external
command configuration. Ready is a prerequisite check, not a trial command launch.
The clickable **Desktop** summary also checks windowing setup and any pending
Android restart. Its dialog explains the requirements and opens setup only when
requested. It does not require an already started session, and an app restart
does not undo completed device setup. A missing privileged service or Desktop
setup does not invalidate a ready Termux integration.

## Open Tools

Open **Apps** in the control panel, then choose an application or **Terminal
sessions**. The selector beside Start search defaults to **Current**, the screen
containing Start. Select another display there to launch on it.
Without shell/root access, Apps remains available with the **Current** phone
destination and saved **Recent** entries. Global running tasks and display
selection become available when the privileged service is ready.

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
Interactive X11 launches and reopening an existing window on its current display
do not require Desktop or shell access, including Android-allowed secondary
displays. Display resource management, existing-task transfers and background
placement require the privileged service.

The **X11** tool manages retained sessions and can open a whole Linux desktop
or individual clients from that session. A proot/chroot environment must supply
its own programs and shared socket/authentication paths. See [Embedded X11](x11.md)
for setup, launchers, clipboard/drag-and-drop and container examples.

## Choose Or Create A Display

Once privileged access is ready, the control panel lists every live display
with its status. Select the radio
button for the phone, a wired/wireless display, or a MagicDesk-owned virtual
display, then use the two-column grid of labeled icon actions below the list.
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

**Create display** offers:

- **Virtual display:** a headless display, suitable for a scrcpy viewer;
  several may coexist.
- **Display with phone preview:** Android's preview surface on the phone.
  Its shared overlay configuration cannot replace an existing overlay set.

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
launches ordinary fullscreen phone apps and shows phone recent tasks. With a
phone Desktop, HOME shows that workspace. Start on each display is independent.
The control panel's **Apps** opens fullscreen Start, even without Desktop. Its
**Running applications** tab can move a specific task to the selected destination.
Every Start has its own launch-display choice; changing it does not switch input
or start a Desktop.

Notification-listener access is optional. Grant it only when MagicDesk's
notification center and popups are wanted.

## Close, Exit And Recovery

**Close desktop** closes the selected workspace, releases input if it still owns
the selection, and restores its other session-owned changes. Surviving managed
applications become independent fullscreen tasks on the same display. Only loss
of that display returns them to phone fullscreen. It records the workspace for a later session, restoring
only tasks that are still alive. It keeps independent tools, retained terminals
and owned displays available. Other Desktops keep running; only closing the last
one returns HOME to its previous role state.

**Exit MagicDesk** also clears that live workspace record, closes built-in
windows, ends retained terminal and X11 sessions, removes owned displays and
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
