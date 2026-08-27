# Getting Started

This guide covers installation, device preparation, desktop session startup,
normal shutdown, updates, and removal. See [Compatibility](compatibility.md)
for firmware requirements and issue reports.

## Requirements

MagicDesk requires:

- Android 15 / API 35 or newer;
- firmware with working Android freeform windows;
- the official Shizuku application with an authorized server running;
- one Device Setup pass and reboot before the first desktop session.

A desktop can run on the phone display without external hardware. An external
desktop additionally requires a wired or wireless secondary display that
Android accepts application tasks on. Video output, Miracast, physical input
routing, and native window behavior remain firmware capabilities.

## Install And Start Shizuku

1. Install Shizuku from its
   [official GitHub Releases](https://github.com/RikkaApps/Shizuku/releases).
2. Start the Shizuku server using wireless debugging, ADB, or a deliberately
   configured root method supported by Shizuku.
3. Confirm in Shizuku that the server is running.
4. Open MagicDesk and grant its Shizuku request.

For the standard wireless-debugging setup, follow the
[official Shizuku guide](https://shizuku.rikka.app/guide/setup/). A server
started through wireless debugging or ADB normally needs to be started again
after every phone reboot.

MagicDesk does not install, start, or configure Shizuku. It requires one live
authorized service and does not silently fall back to ordinary application
permissions when that service is unavailable.

## Prepare The Device

1. Install MagicDesk from a tagged
   [GitHub Release](https://github.com/mekhontsev/magicdesk/releases) or a
   development build.
2. Open MagicDesk on the phone.
3. Select **Prepare device**.
4. Review the detected platform and capability results.
5. Reboot when requested. Android and WMShell cache part of the desktop
   configuration during startup.
6. Restart Shizuku if its startup method requires it, then open MagicDesk.

MagicDesk has no boot receiver. A phone reboot therefore returns to ordinary
Android until the user starts MagicDesk again.

Notification access is optional. Grant it from Android settings only when the
MagicDesk notification center and notification popups are wanted.

## Optional Display Fixes Helper

Most devices do not need a companion APK. On firmware where shell UID 2000
cannot read the monitor's complete timing list, the independently signed
[`DisplayFixes-development.apk`](https://github.com/mekhontsev/magicdesk/releases/download/development/DisplayFixes-development.apk)
can apply the native wired-display mode through a direct, user-approved root
request before MagicDesk starts the desktop. It does not use Shizuku and is not
a MagicDesk runtime dependency. See [Native Display Mode Helper](native-display-mode-helper.md)
for its capability checks and operating procedure.

## Start A Desktop

MagicDesk uses the same desktop implementation for every target:

- **Phone** runs the workspace on display 0. This is useful on tablets and on
  devices whose external output is mirror-only.
- **Wired** uses an Android secondary display connected through the device's
  supported physical output.
- **Wireless** uses an already connected Miracast display. A platform-specific
  connection action is shown only when that platform exposes a verified UI.
- **Simulated** creates a temporary secondary display for development and the
  built-in self-test.

For a wired session, connect the monitor and select **Start external
desktop**. For a wireless session, connect through the system projection UI,
return to Phone Control Panel after Android reports the display, and select
**Start external desktop**. **Open desktop here** starts on the phone.

The Standard Android driver leaves connection, disconnection, mirror mode,
and output timing under system control. A supported platform driver may add
managed transitions, output modes, Fill display, and a connection shortcut.

## Normal Workflow

Applications launched from Start are ordinary Android tasks. MagicDesk can
place them in windowed or fullscreen mode, remember an explicit launch choice,
and preserve visible freeform layout and stacking order.

Use **Close desktop** to leave the desktop while retaining live application
tasks. Starting another desktop later restores tasks that Android has not
closed, including their saved modes, positions, visibility, and stacking.
Unexpected external-display removal follows the same preservation path.

Use **Exit MagicDesk** when the workspace should be discarded. Exit closes
MagicDesk windows, clears the saved live session, restores owned runtime state,
and stops MagicDesk services.

The persistent phone notification provides two direct routes while MagicDesk
is running:

- tap the notification to open Phone Control Panel;
- select **Open touchpad** to reopen the phone touchpad for the active desktop.

## Display Size And DPI

Output mode, Fill display, and DPI are stored per monitor. Desktop item and
application-window positions use relative coordinates so a global layout can
adapt to differently sized displays.

For a 1920-pixel-wide display, `160` DPI is a useful starting point. Open the
System panel from the taskbar battery indicator or with `Win+Q` to adjust it.
**Reset** removes the MagicDesk density override. **System / native** removes
a forced Android output mode and lets the connected display select its native
timing again.

Available resolutions and refresh rates depend on what Android and the active
platform or SoC backend can read under the connected shell identity. A missing
vendor timing interface disables only that control.

On verified Nubia firmware that exposes its complete EDID list only to root,
the independent [Native Display Mode Helper](native-display-mode-helper.md)
can apply the advertised native timing before MagicDesk starts. It is an
optional direct-root tool, not a Shizuku or MagicDesk runtime requirement.

## Development Builds

Every non-documentation push to `main` publishes a release-signed development
APK at a stable URL:

[Download the current development APK](https://github.com/mekhontsev/magicdesk/releases/download/development/MagicDesk-development.apk)

The [development release page](https://github.com/mekhontsev/magicdesk/releases/tag/development)
includes the exact commit, checksum, and CI run. The version shown in About
and Diagnostics contains the build number and short commit, for example
`1.9.1-dev.123.abcdef0`.

Development builds use the release signing certificate but contain unreleased
changes. Tagged releases remain available from the normal Releases page.

## Restore Defaults And Uninstall

Before uninstalling MagicDesk:

1. Open **Device Setup**.
2. Select **Restore defaults**.
3. Reboot the phone.
4. Uninstall MagicDesk.

Android does not let an application perform this cleanup while its package is
being removed. If MagicDesk was already uninstalled, reinstall it, grant
Shizuku access, run **Restore defaults**, reboot, and then remove it again.

Restore defaults removes MagicDesk's desktop-windowing overrides, resets
primary-display size, density, and scaling overrides, and normalizes stale
phone tasks. It restores firmware defaults rather than values captured by an
earlier MagicDesk installation.

## Problems

Open **Tools > Diagnostics** after reproducing a failure and attach the full
report plus exact steps to a GitHub issue. See
[Compatibility and issue reports](compatibility.md) for the report contents,
privacy boundary, support levels, and tested firmware.

The built-in desktop self-test requires an awake and unlocked phone with no
other MagicDesk desktop session running. It can target phone, simulated,
wired, or wireless displays. Its current coverage and remaining hardware work
are recorded in the [validation matrix](testing-backlog.md).
