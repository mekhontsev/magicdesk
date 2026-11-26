# Linux in MagicDesk

Install an ARM64 Linux distribution, open its terminal, or run its applications
and desktop in Android windows. MagicDesk provides the console, installer,
launchers, display and audio. [Shroot](https://github.com/mekhontsev/shroot)
runs the guest programs on the existing kernel. You do not need Termux, a VM
or a managed MagicDesk Desktop.

- [Install Linux](#linux-installer) or [resume an existing installation](#resume-an-interrupted-installation).
- [Open a terminal](#named-environments), [launch a graphical desktop](#graphical-connections) or [boot system services](#systemd-openrc-and-runit).
- [Connect Android folders](#android-folders-and-backups), [back up Linux](#android-folders-and-backups) or [install it as a separate Android app](linux-applications.md).

## Requirements

MagicDesk requires Android 14 or later. Shroot requires an ARM64 device with
Linux 5.15 or later and the necessary kernel operations available to the
selected executor. To check your device, run `shroot --probe` in the
[MagicDesk Console](getting-started.md#open-the-android-shell-console) you
intend to use. A managed Desktop is optional and requires Android 15 or later.

Choose an executor when opening the console:

| Console | When to choose it | Default Linux library |
| --- | --- | --- |
| **User** | Run under MagicDesk's own Android UID. No Shizuku or root required. | `~/linux` in MagicDesk's private app data |
| **Shell** | Use the configured privileged service. Normally Android Shell (UID 2000); called **Shell** even when the service runs as root. | `/data/local/tmp/magicdesk/linux` for UID 2000; `/data/local/tmp/magicdesk/root/linux` for UID 0 |

Install, resume and manage the distribution from the **same executor**. Two
environments called `debian` under User and Shell are independent. The picker
labels them accordingly; MagicDesk does not silently switch identity.

## Linux installer

In the selected **MagicDesk Console**, outside Linux:

```sh
magicdesk download https://raw.githubusercontent.com/mekhontsev/magicdesk/main/scripts/install_linux.sh ~/install_linux.sh
sh ~/install_linux.sh
```

Download first rather than piping the script into `sh`: the installer reads
your answers from the terminal. Use the installer from the matching release
tag for an older APK, or from `develop` when testing unreleased changes with
a development APK.

Choose a distribution, an environment name and a GUI profile. Run
`sh ~/install_linux.sh --list` for the current combinations by distribution,
or `--help` for noninteractive options. A recipe prepares Linux packages,
guest helpers and launchers; it does not open the desktop or boot system
services during installation.

Recipes are available for Debian, Ubuntu, Alpine, Fedora, Arch Linux ARM,
openSUSE, openKylin, Deepin, Manjaro ARM, Void, Armtix, [NixOS](#nixos)
and [SteamOS](#steamos-recovery-image).

| Goal | GUI profile |
| --- | --- |
| Terminal only | `none` |
| Individual windows | `apps`: Mousepad, Thunar and Xfce Terminal |
| Whole desktop | `xfce` for X11; `weston` for Wayland with Xwayland |
| KDE Plasma | `plasma` on Debian, NixOS or SteamOS |
| Fedora GNOME development kit | `gnome`; needs a Linux system bus, not just a session bus |

For example, to install Debian with Xfce:

```sh
sh ~/install_linux.sh --distro debian --name work --gui xfce
```

Use `--protocol x11|wayland|both` for recipe-created application entries.
`--create-user NAME` creates a guest account with a **locked** password. To
use it for a desktop that locks, set its password inside Linux as guest root
with `passwd NAME`, then choose that user in [Launch options](#systemd-openrc-and-runit).
Creating another account does not change the user of an already running desktop.

**DNS:** **Current network** copies the executor's Android DNS addresses once;
it does not follow later Wi-Fi or VPN changes. For Android Private DNS, choose
explicit guest DNS addresses or keep a working image resolver. No public
resolver is selected for you. To refresh an inactive environment after changing
networks, use `shroot dns work system --replace` in its MagicDesk Console.
On an IPv4-only network, Void's package downloads can be limited to IPv4 with
`FORCE_IPV4=1 sh ~/install_linux.sh --distro void`.

### Resume An Interrupted Installation

An existing name is never overwritten. After a package failure, an interrupted
install or an APK update, download the installer again if needed, open the
original executor and run:

```sh
shroot list
sh ~/install_linux.sh --name work --resume
```

Resume detects the installed distribution, reruns package setup and refreshes
recipe-owned helpers (including `magicdesk-init`) and launchers. It does not
redownload the rootfs, replace user launchers or reset the saved DNS. Package
setup may still need network access. Do not pass `--image` or `--dns` with
`--resume`. Specify a profile to add or change it, for example
`sh ~/install_linux.sh --name work --resume --gui plasma --gpu turnip`.

Closing a console **window** retains the PTY: reopen **Terminal sessions** to
return to the running installer. **End session**, process death or APK
replacement ends the PTY; use `--resume` in a new console in that case.

### NixOS

```sh
sh ~/install_linux.sh --distro nixos --name nixos --gpu software
sh ~/install_linux.sh --name nixos --resume --package ripgrep
```

The recipe imports the ARM64 LXC image, registers the Nix store and prepares a
system generation with `nixos-rebuild boot`; this does **not** boot PID 1.
Plasma is the default desktop. Software graphics avoids the private Mesa/KWin
builds; packages absent from the binary cache may still need compilation.

Edit `/etc/nixos/configuration.nix` for your settings. The installer owns
`/etc/nixos/magicdesk.nix`. If your existing configuration does not import
`./magicdesk.nix`, the installer asks you to add it rather than rewriting your
configuration. Resume accepts Nixpkgs attribute names and retains the saved
protocol and build-job count unless overridden. Manage old Nix generations
with Nix tools; `--cache clean` is not Nix garbage collection.

### SteamOS Recovery Image

```sh
sh ~/install_linux.sh --distro steamos --name steamos --yes
```

Allow **about 35 GB free** during conversion and import. The recipe uses
Valve's **ARM64 Steam Frame** recovery image, not the x86 Steam Deck image. It
does not mount or flash any Android partition. By default it keeps the supplied
applications, updates packages from Valve's configured repositories and adds
Plasma launchers with automatic GPU selection. After setup, refresh **Apps**
and select **Plasma Desktop (wayland)** or **Steam**.

`shroot login steamos` opens the supplied `steamos` account; use
`shroot login steamos --user root` for guest administration. For an x86 Linux
game using Valve's FEX tool, set Steam's **Properties > General > Launch
Options** to `magicdesk-steam-game %command%`. This selects the prepared
graphics environment without replacing Valve's files. It is not a guarantee
for every game or Proton configuration.

Verified downloads remain in `~/downloads` unless you choose `--downloads DIR`.
If conversion fails, the console prints the retained work directory. If the
environment was imported but package or graphics setup failed, use
`sh ~/install_linux.sh --name steamos --resume --gui plasma --gpu auto`.

### Graphics Drivers

Choose **software** for the distribution's software renderer. **Turnip** builds
a private Mesa inside Linux and requires a compatible Adreno GPU with accessible
KGSL. On SteamOS the default is **auto**: select Turnip when supported, otherwise
use software. An explicit Turnip request reports failure rather than silently
changing driver. A failed download or compile is not an unsupported-GPU result.

Allow several GB for a driver build. `--jobs N` controls Mesa, KWin and Weston
compiler parallelism (1–8, default 4). A failed build retains the previous
profile; successful changes apply to **new** launches. Neither profile replaces
Android's drivers. Flatpak and Snap use their own graphics packages; see
[Flatpak GPU drivers](graphics.md#flatpak-gpu-drivers) and
[Snap GPU drivers](graphics.md#snap-gpu-drivers).

## Named Environments

Choose **Console > New session > Shroot environments**, select the distribution,
then **New session** to open a Linux login shell. If Termux integration is
enabled and standalone Shroot is installed there, the picker can also ask
Termux to open its own environments without copying them into MagicDesk.

Alternatively, in **MagicDesk Console**:

```sh
shroot list
shroot inspect work
shroot login work
```

Run Linux commands such as `apt` in that login shell. Use `exit` to return to
MagicDesk Console. To run a single command instead, use
`shroot exec work -- /usr/bin/id`. Each launch starts its own process tree.
Use `shroot launches work` to list active IDs and `shroot stop work ID` to
cancel one. Stopping a launch does not remove the environment.

`shroot --print-library` shows where the selected executor stores Linux.
Start discovers applications from that executor's default library; pointing a
CLI command at a different directory does not register it in Apps. For
manual image installs, offline imports and the full command reference, see
[Shroot environment libraries](https://github.com/mekhontsev/shroot/blob/main/library.md)
and [images and attachments](https://github.com/mekhontsev/shroot/blob/main/images.md).

### Android Folders And Backups

To share files, select the User or Shell environment in **Shroot environments >
Android folders**, then choose the directory and its access mode in Android's
picker. New Linux sessions see it at the displayed path, for example
`/mnt/android/Projects`. This is a live mount, not a copy. See
[Android folders](linux-applications.md#android-folders) for permissions and removal.

To move or back up a distribution, stop its launches first. In the owning
MagicDesk Console, use a destination writable by that executor:

```sh
shroot backup work ~/work.tar.zst
shroot restore ~/work.tar.zst --name restored
```

The same archive can be restored under User or Shell, or loaded as a Linux
ARM64 image with `docker load -i work.tar.zst`. It preserves guest files
and metadata, not externally mounted Android
folders or their access grants. Do not copy individual files out of the backing
store as though it were a rootfs. To install the environment as an independent
APK with its own Android UID, see [Linux Capsules](linux-applications.md).

### Android Command Access

Ordinary launches do not give guest programs access to MagicDesk commands.
To enable the [command channel](automation.md#linux-command-access) for one launch:

```sh
shroot exec work --magicdesk -- magicdesk get_state
```

For automation from outside Linux, MCP's `guest.list` and `guest.inspect`
describe installed environments; `guest.launches` and `guest.stop` address
running launches. Disconnecting the client does not stop its work.

## Graphical Connections

1. In Control Panel, select the destination display and open **Apps**. Press
   **Refresh** after installing a distribution or changing its packages.
2. Choose an application to open an individual window, or an entry such as
   **Xfce Desktop** or **Plasma Desktop (wayland)** for a whole desktop in one
   viewer. You do not have to start an X server or compositor on Android by hand.
3. To reopen a retained desktop, open its viewer from **Graphical sessions**.
   Closing the viewer does not log out Linux; use the desktop's logout action
   to end the session.

MagicDesk supplies the authenticated X11 or Wayland connection, audio and
focused [gamepad input](gamepads.md). Linux supplies the applications,
session D-Bus and desktop packages. The selected executor admits connections
from its own UID and hands the connected sockets to MagicDesk; the X11 server
or Wayland compositor does not need the Shell executor's privileges. For display and audio setup, see
[X11](x11.md), [Wayland](wayland.md) and [Linux audio](audio.md). A separate
Linux APK connects through [Connect to MagicDesk](linux-applications.md#optional-magicdesk-connection).

## Services And Containers

### Systemd, OpenRC and runit

The installer prepares a distribution; ordinary launches start the selected
program directly. To start it **with the distribution's system services**, open
**Launch options**: long-press an application or desktop in **Apps**, or
select an environment in **New session > Shroot environments** for its terminal.

- **Linux user** selects the account for the program, desktop or terminal.
  For a Plasma desktop that locks, choose an account whose password you know.
- **Start system services** boots the installed systemd, OpenRC or runit, then
  runs the program as the chosen user. For example, enable it on the Ubuntu
  Xfce entry to use Snap services from that desktop.

Options are saved per entry and apply to its **next new session**. Reopening
a window or attaching to tmux does not apply them. Start a service boot in an
idle environment; a second entry does not join an existing init session.
The boot uses a private guest network by default; a saved offline or bridge
policy still applies. When the selected program exits, its service boot shuts down.

For a booted root console, first enter the guest from MagicDesk Console:

```sh
shroot login work --user 0:0 --network routed
```

Then, **inside Linux**:

```sh
exec magicdesk-init
```

If the helper is missing, [resume the recipe](#resume-an-interrupted-installation)
in the original MagicDesk Console. The distribution must supply its init
packages (for example `systemd-sysv` and `dbus` on Debian, or `openrc` and
`openrc-init` on Alpine). Installed services and their data persist across
boots. Shut down **inside the booted guest** with `systemctl poweroff`,
`openrc-shutdown --poweroff now` or `runit-init 0`, respectively. Exiting the
root console alone restarts its shell; it does not power off Linux or Android.

### Network Modes

For an ordinary terminal or application, select its User or Shell environment
in **Shroot environments > Network**. Choose Android networking, no external
network, or port forwarding. For example, forward phone port 8080 to guest port
80 to reach a guest web server. UI rules listen on phone loopback unless you
allow connections from other devices. Saving changes affects new launches only.
See [network access](linux-applications.md#network-access) for Android permissions.

For guests that configure their own network topology, such as Docker or k3s,
use an explicit routed launch as above. For CLI port syntax, addresses, VPNs
and TUN/TAP support, see [Shroot networking](https://github.com/mekhontsev/shroot/blob/main/networking.md).

### Docker And Podman

Install the engine and its guest-side dependencies **inside Linux**. A routed
service boot prepares the private runtime filesystems needed by container
engines. For Docker's nftables backend, install the distribution's `nftables`
package so the daemon can find `nft`. In SteamOS with Docker already running,
try an ARM64 Alpine container:

```sh
docker run --rm -it --platform linux/arm64 alpine:3.23 sh
```

Guest root is not Android root. For rootless Podman, configure the guest
account's subordinate IDs and install the UID-map helpers and `slirp4netns`.
See the [container configurations](https://github.com/mekhontsev/shroot/blob/main/tests/validation-matrix.md#containers-and-virtual-machines)
for tested engines, storage and networking backends.

### K3s

Boot Debian or Alpine with [`magicdesk-init`](#systemd-openrc-and-runit) and
`--network routed`. In that **booted guest root console**, install k3s once:

```sh
curl -fL https://get.k3s.io -o /tmp/install-k3s.sh
sh /tmp/install-k3s.sh server
k3s kubectl get nodes
k3s kubectl get pods -A
```

On later boots, start the same environment and init session. Its enabled
systemd/OpenRC service reuses the datastore, credentials and local volumes;
do not reinstall k3s. Check `systemctl status k3s` or `rc-service k3s status`.
See the [MagicDesk boot and persistence test](../tests/shroot/test_k3s_boot.py)
and [Shroot cluster coverage](https://github.com/mekhontsev/shroot/blob/main/tests/validation-matrix.md#k3s).

## Boundaries

Use trusted images. Linux guest root can administer its distribution, but
cannot grant itself additional Android permissions. Environments sharing an
Android executor are **not a security boundary for mutually untrusted code**.
A [Linux Capsule](linux-applications.md) gives one distribution an independent
Android app UID. Neither mode uses a VM or a separate kernel. Android can
still stop processes; see **Process limit** in Control Panel before starting
a desktop or long-running service.

## Coverage And Limits

A distribution recipe is not a guarantee that every application, GPU or
desktop service works. Software rendering, a successful hardware probe and
actual accelerated output are distinct results. A headless browser test does
not check presentation, input or sound. Keep the browser sandbox enabled.

- [MagicDesk recipe workflows](../tests/shroot/recipe-workflows.md): what was exercised through the Android launchers and displays.
- [Shroot distribution matrix](https://github.com/mekhontsev/shroot/blob/main/tests/validation-matrix.md): runtime, containers, distributions and limits by executor.
- [Shroot kernel compatibility](https://github.com/mekhontsev/shroot/blob/main/kernel-compatibility.md): host requirements and optional capabilities.
