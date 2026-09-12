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
manager. **Limit service to shell UID 2000** is a separate next-start setting
for either direct root or root-backed Shizuku. No root is required for the
normal Shizuku path. See [Privilege boundaries](privilege-modes.md).

MagicDesk does not start Shizuku itself or require root. After a reboot,
Shizuku may need restarting, depending on its startup method. Missing shell
access does not prevent ordinary UI or independently authorized Termux
sessions from opening.

## Open Tools

Open **Apps** in the control panel, then choose an application or **Terminal
sessions**. The selector beside Start search defaults to **Current**, the screen
containing Start. Select another display there to launch on it.

Outside Desktop these are ordinary fullscreen Activities. If the destination
already has a MagicDesk Desktop session, the tools use its managed window path.
Neither action starts a session implicitly or requires Desktop provisioning.

Files and Android-shell Console need the authorized privileged service. Termux Console needs
Termux, external app commands enabled in its configuration, and MagicDesk's
Termux `RUN_COMMAND` permission. Termux and Android-shell sessions use different
UIDs and filesystem access.

Closing a terminal window detaches it. **Terminal sessions** can reopen the
same retained session; **End session** terminates it. Retention lasts only
while the MagicDesk process and transport survive. Optional tmux sessions
inside Termux provide a separate lifetime for longer-running programs.

See [Workstation tools](workstation-tools.md).

## Choose Or Create A Display

The control panel lists live displays by identity, source and geometry.
Select the phone, an existing wired/wireless display, or a MagicDesk-owned
virtual display. Availability is checked again when an action runs.

**Create display** offers:

- **Virtual display:** a headless display, suitable for a scrcpy viewer;
  several may coexist.
- **Display with phone preview:** Android's preview surface on the phone.
  Its shared overlay configuration cannot replace an existing overlay set.

Choose dimensions and scale. Creation requires the privileged service but does not acquire
HOME or start Desktop. **Copy scrcpy command** copies a command for viewing
that existing display from a computer; MagicDesk does not bundle or start the
computer-side viewer.

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

**Close desktop** closes the selected workspace, releases its selected input and
other session-owned changes, and returns its surviving managed applications to
phone fullscreen. It records the workspace for a later session, restoring
only tasks that are still alive. It keeps independent tools, retained terminals
and owned displays available. Other Desktops keep running; only closing the last
one returns HOME to its previous role state.

**Exit MagicDesk** also clears that live workspace record, closes built-in
windows, ends retained terminal sessions and stops the runtime. Neither action
deletes Desktop files.

Unexpected display loss runs the same session cleanup. After process loss,
startup recovery relinquishes stale MagicDesk HOME ownership before waiting
for Shizuku. MagicDesk is not offered as an inactive HOME choice. When there
was no explicit HOME holder at session start, Android may show its launcher
chooser again; MagicDesk does not choose a replacement on the user's behalf.

The persistent notification opens Phone Control Panel. Its separate touchpad
action opens the phone input surface for an active external session.

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
