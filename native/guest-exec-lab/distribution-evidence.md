# Distribution Acceptance Evidence

The [validation matrix](validation-matrix.md) owns statuses and acceptance rules.
This companion records the exact scenario, local receipt and remaining scope,
grouped by distribution. A passing receipt certifies its recorded build and
configuration, not later code or every application on that OS.

Local artifact paths below are evidence identifiers, not public download links.
Replace superseded evidence after a qualifying rerun; keep repaired defects in
regression tests instead of accumulating an incident history.

- [Debian](#debian)
- [Ubuntu](#ubuntu)
- [Alpine](#alpine)
- [Fedora](#fedora)
- [Arch](#arch)
- [openSUSE](#opensuse)
- [openKylin](#openkylin)
- [Deepin](#deepin)
- [Manjaro](#manjaro)
- [Void](#void)
- [Armtix](#armtix)
- [SteamOS](#steamos)
- [NixOS](#nixos)
- [Shared Evidence](#shared-evidence)

NixOS recipe/activation and pending graphical acceptance are tracked in the
[matrix's NixOS section](validation-matrix.md#nixos-recipe-and-initial-image-probes).

## Debian

The 2026-10-28 system-workload and package rerun uses the installed recipe
environment, lazy entry and supervisor
`edfcac0e8f18fde5e2c3f929c0634ce36625ad697003c85b8decbe4045537d26`.
Each receipt retains its exact native hashes. The isolated systemd session is
`2b3968da10fd41eeac53fc7463f4eb8b`; NixOS KWin compilation was active separately,
so these are correctness checks, not benchmarks.
The 2026-10-27 browser, VS Code, package and portal reruns use supervisor
`729b3c3b988519fa72d1a6cbc4f038ef72c918b70358c5f2491095fef88ac578`.
These are separate build
observations, not blanket certification of subsequent edits.

### Debian default boot/services

`build/matrix-debian-alpine-20261026/debian-boot/oci-services-68a1acc8ef4d44ffb9154d4a3ea1e57b.json`;
stock systemd default target, journal/D-Bus/logind IO, restart and shutdown.

### Booted Debian Docker

`build/installed-debian-turnip/oci-services-a2cfd9b2636341c1927ae58a6e112e2c.json`;
26.1.5, overlay2, iptables, no userland proxy, TCP/UDP/peer names/local outbound/port
conflicts and release, external HTTP and repeated DNS requests

### Booted Debian Podman

`build/installed-debian-turnip/oci-services-d4f3f33eccab4815863a3eeee550a943.json`;
5.4.2, overlay, bridge/nftables, stock seccomp, enabled cgroups, no `--ulimit`
overrides; TCP/UDP/peer names/local outbound/binds/repeated exec/conflicts/port release;
external HTTP and repeated DNS requests

### Debian managed Libvirt/QEMU

`build/installed-debian-turnip/oci-services-011c5918bd524993ae10e90090ed8e51.json`;
stock libvirt service, managed TCG kernel/initrd boot, paused start/resume, serial,
poweroff, undefine and cleanup. VM networking and KVM remain untested.

### Debian direct QEMU TCG

`build/installed-debian-turnip/oci-services-0060b7593fed45d3b53a2aad41fc9b44.json`;
kernel/initrd serial boot, not KVM or VM networking

### Debian machinectl

`build/installed-debian-turnip/oci-services-41ee89b67fa446608dcf872eb94a3346.json`;
stock systemd 257.13 unit, nested Debian 257.13, same three-boot
shell/service/TCP/UDP/termination/cleanup contract as Arch

### Debian BuildKit, LXC And LTP

All receipts are under `build/installed-debian-turnip/`:

- BuildKit service: `oci-services-d6cc654ea86b400cab3ad31706f2deb9.json`;
  workload: `oci-services-eb696ddf111a440eb246ca2d17cb5fe2.json`.
  Official upstream ARM64 BuildKit 0.34.1 archive, lab-only installation;
  OCI sandbox, multistage build, cache reuse/invalidation, secrets, exported
  image execution, cancellation and cleanup. No source-build installer fallback.
- LXC: `oci-services-aaa095bf86df482fba4b7f787effbdbe.json`;
  stock common policy/seccomp, nested Debian, attach/services, mounts,
  bridge TCP/UDP and three normal/forced-stop cycles with cleanup.
- LTP: `oci-services-0503821c972440108f6dfc6e4087efca.json`;
  reviewed 20250930 sources rebuilt with the guest compiler; all seven
  selected binaries pass as ordinary guest UID 1000.

### Debian Package Lifecycle

Under `build/installed-debian-turnip/`, `b487fe79a8a644f79fab56f41bb15626.log`
and `2d3dc4cf910b47b4bc3a7c0e8900f4fc.log` cover hello 2.10-5 installation,
execution, reinstall, retained-file checksums, removal and empty `dpkg --audit`.
The five missing documentation files match the image's explicit dpkg exclusions;
`24bd6d56d0464b018e8cb78860d7bd30.log` retains the policy. No runtime files or
checksum mismatches are ignored.

Flatpak 1.16.6 update and dry-run repair validate all 21 refs in
`ca15ff8e97c5411aa50600a5cb4adc8a.log`. Uninstall, absence verification and
reinstall without deleting user data pass for Firefox 159.0 in
`d1a9093c57384f94999de7d0a0645faf.log` and Chromium 156.0.8078.47 in
`a3fdaa37df5447a7af4edf9bcf582702.log`. Post-reinstall GUI acceptance is separate.

### Debian Flatpak sandbox and portal

Under `build/installed-debian-turnip/`, Chromium 156.0.8078.47 and Firefox
159.0 pass fresh-profile and retained-profile keyboard/pointer, download,
GUI file selection/readback and normal close. The reports are
`debian-chromium-debugger-recovery/gui-report.json`,
`debian-chromium-debugger-relaunch/gui-report.json`,
`debian-firefox-debugger-recovery/gui-report.json` and
`debian-firefox-debugger-relaunch/gui-report.json`.
Each records green WebGL pixels; Chromium reports Zink/Turnip on Adreno 840,
while Firefox masks its renderer name. The Firefox relaunch records pixels
without a separate required-WebGL assertion. These runs use the current
recipe's Mesa probe with the installed driver build.
The 2026-10-28 post-reinstall fresh-profile rerun uses supervisor
`927d54d09c339524c781e4953ecb995918c86e9c9165e496b1a586e7ec8191c3`.
`debian-firefox-full-device/gui-report.json` and
`debian-chromium-full-device/gui-report.json` each pass all six assertions:
keyboard, pointer, downloaded bytes, GUI file readback, WebGL pixels and normal
exit. Chromium's `chromium-gpu-features-current.png` and
`chromium-gpu-sandbox-current.png` record hardware composition and
`Sandboxed: true` on the installed Mesa 26.2.3 Zink/Turnip driver.
`debian-firefox-full-device-relaunch/gui-report.json` and
`debian-chromium-full-device-relaunch/gui-report.json` pass the same six
assertions after reopening each retained profile on this native build.
Portal lifecycle is **U (upstream defect)**. On supervisor
`927d54d09c339524c781e4953ecb995918c86e9c9165e496b1a586e7ec8191c3`,
job `bbb976d6686e494ba271a01ab9c6e690` passes two document grant/read/write/revoke,
exact exit, FUSE removal and reactivation cycles, but repeated execution in
`fdec8ca321284111b29a6ab775e6bffa` times out on SIGTERM.
GDB receipt `64050f93e9904621af4cecc569c44e27` shows `g_main_loop_quit` inside
the signal handler waiting on the interrupted GLib context mutex, matching
[upstream shutdown deadlock #1896](https://github.com/flatpak/xdg-desktop-portal/pull/1896).
These are stock xdg-desktop-portal 1.20.3+ds-1 receipts; forced cleanup is not
a passing exit. Normal `fusermount3 --version` with closed stdin passes in
`7efe7353a74348e388049071904f5dc9`; the complete set-ID suite, including closed
standard descriptors, passes in `build/shroot-image-tests/oci-services-abc4365e35734118b8fec46b36846485.json`
(lazy) and `oci-services-40514e468aec4da7a7122037e37c4193.json` (Base).
Firefox 159 runs from the system Flatpak installation; a dry-run
repair of all 21 installed refs passes in
`e0456ff1fab340639b6add1c2f2167ee.log`. Package removal remains separate acceptance.

### Debian VS Code

Under `build/installed-debian-turnip/`, `debian-vscode-debugger-recovery/gui-report.json`
verifies VS Code 1.140.0 editor/save, external
watcher, PTY, debugger and reload. Hardware GPU composition is enabled through
Zink/Turnip; the ordinary-user renderer retains NoNewPrivs=1 and Seccomp=2.
Full desktop acceptance is separate.

## Ubuntu

Current system-workload reruns use staged Release supervisor
`729b3c3b988519fa72d1a6cbc4f038ef72c918b70358c5f2491095fef88ac578`.
All receipt IDs in this table are `build/installed-ubuntu-fuse/oci-services-ID.json`:

| Workload | Passing receipt ID |
| --- | --- |
| Docker | `527ff03454de4c5c804caa4af9e77cb7` |
| Podman | `ef6de081dd7e4b21b1b78635f2d82aaf` |
| Managed Libvirt/QEMU | `2d29757f319449b98f56a544b2a79bce` |
| Direct QEMU TCG | `324d89694f6e4f26990fd3b5138db7ea` |
| machinectl / nested Debian | `7efee948c80e4460bb2c077a94317ece` |
| BuildKit build/cache/secret/export/cancel | `181748031ed54a6cb30df9ef863738c9` |
| LXC lifecycle and bridge | `02df9ffe0831453ea079a3f538067463` |
| LTP core as ordinary user | `fdfe7bbce68649e98f6d7763d12f29ce` |

The 2026-10-26 boot, Snap and GUI reruns use staged Release supervisor
`f54519b2c71a2b55d79eed69fd9af3eaeb056a0085a787dce9f58497a57c5bc6`.
The container, VM, BuildKit, LXC and LTP receipts below use
`1ce1dd0c902e235d71f634fc83092ced45809c1d9aacd013f4e76095dbcad561`.
Neither set certifies the installed APK or Shroot Base.

### Ubuntu recipe/packages

`build/ubuntu-recipe-matrix-current/oci-services-48ed04289e704edfad13dd42656e89ba.json`
; current recipe helper refresh retains installed Turnip.
`build/ubuntu-package-lifecycle-current.log` and
`build/ubuntu-package-policy-verify-current.log` cover hello 2.10-3build1
install/reinstall, retained-file checksums and removal. Two documentation omissions
match the image's explicit dpkg exclusions, checked separately in
`build/ubuntu-package-policy-current.log`; dpkg audit is empty

### Booted Ubuntu containers

The 2026-10-27 rerun with supervisor
`729b3c3b988519fa72d1a6cbc4f038ef72c918b70358c5f2491095fef88ac578`
passes Docker and Podman through their stock services and policies:
`build/installed-ubuntu-fuse/oci-services-527ff03454de4c5c804caa4af9e77cb7.json`
and `oci-services-ef6de081dd7e4b21b1b78635f2d82aaf.json` respectively.
These cover the deterministic local network/container contract; external internet
acceptance belongs to the separate receipts below.

`build/installed-ubuntu-fuse/oci-services-cb715690645142c0a6d2c1be57eca8ee.json` (stock
Docker service),
`build/installed-ubuntu-fuse/oci-services-178f8d66ed374059b3ab8f64ade7fb73.json`
(Podman, headless systemd/control target); staged native hashes in receipts. Bridge,
published TCP/UDP, peer IP/name exchanges, binds/exec, conflicts and port release pass;
both containers in each run also pass internet access and 20 external DNS requests each

### Ubuntu Libvirt/QEMU

`build/installed-ubuntu-fuse/oci-services-2d29757f319449b98f56a544b2a79bce.json`
repeats managed QEMU TCG boot, serial output, pause/resume, shutdown and undefine
with supervisor `729b3c3b988519fa72d1a6cbc4f038ef72c918b70358c5f2491095fef88ac578`.

`build/installed-ubuntu-fuse/oci-services-7da6107782784a349c77c7534c9a4ed6.json`;
official libvirt 10.0.0/QEMU 8.2.2, managed TCG boot, paused start/resume, serial,
poweroff, undefine and cleanup. Direct TCG:
`oci-services-ec7f4b8ce0194c1f8c5d69bcf0c32388.json`. VM networking and KVM remain
untested

### Ubuntu machinectl/LXC

`build/installed-ubuntu-fuse/oci-services-d479593c542e40a49d8fa59a456e2908.json`
(systemd 255.4 machinectl) and `oci-services-3c5dc6f4141e4a2eb83e276dffb7cbb8.json` (LXC
5.0.3): nested Debian, stock policies, shell/services, namespace/mount checks, bridge
TCP/UDP, three normal/forced-stop cycles and process/veth cleanup pass

### Ubuntu BuildKit

`build/installed-ubuntu-fuse/oci-services-4f577064387343f18ce3d9fcf0b36d32.json`;
official upstream 0.34.1 archive for testing only, distribution CNI 1.1.1, OCI/overlayfs
sandbox. Multistage build, cache reuse/invalidation, secrets, export/Podman execution,
cancellation and cleanup pass. Rootless/containerd workers remain untested

### Ubuntu LTP core

`build/installed-ubuntu-fuse/oci-services-3acf3b49e1084f26bbdd2f1b68f7d549.json`;
reviewed 20250930 built with the Ubuntu compiler/glibc, all seven tests pass as the
installed ordinary user `ubuntu`, UID 1000. This 2026-10-29 run uses supervisor
`b0148a1e87610d7ccceece4054d25fa70205a25f398532aba009f98eea97a2a3`.

### Ubuntu cold Snap Chromium

`build/matrix-all-20261027/ubuntu-fuse/snap-chromium/oci-services-7d891f43f5834c4aa24b621b21b96be6.json`
: three uninstrumented discarded-namespace confined-shell launches pass in
69.23/33.62/32.50 s. CPU policy samples are retained in the receipt. These are total
command times, not isolated namespace-preparation measurements. The stock 30 s
helper deadline is unchanged; substantial cold-start margin remains open.

### Ubuntu Snap Chromium GUI

`build/installed-ubuntu-fuse/ubuntu-chromium-20261029/gui-report.json` and
`ubuntu-chromium-relaunch-20261029/gui-report.json`; stock Chromium 156.0.8078.47
revision 3572, guest UID 1000 and Wayland. Input, download/chooser readback, WebGL
pixels, normal close and same-profile relaunch pass, using Zink/Turnip on Adreno 840.
The staged session `4e76303e2d1e4239a5701813b7ae6f73` uses supervisor
`b0148a1e87610d7ccceece4054d25fa70205a25f398532aba009f98eea97a2a3`.
The first directory's `gpu.txt` records hardware composition/rasterization,
`Sandboxed: true` and zero GPU-process crashes. Recipe Mesa uses the
common deferred-queue patch and the standard GPU content publisher;
`bb4e82b4e74a479b8ecbb46e50a02424.log` checks matching installed/published library
hashes. `ab2742619b54423a994fd4edef28a78a.log` covers two package/connection switches
with automatic publication; `146c3b683c604612b3b45c5f3d9a285a.log` covers unchanged
preparation without monitor restart. `ff5b6839ccaa4e94b57de1434f0892ba.log` covers Mesa
queue lifecycle/failure tests against Ubuntu glibc. Cold-start margin and full recipe
desktop discovery remain separate.

### Ubuntu FUSE

`build/ubuntu-fuse-capture-wait/oci-services-391bc722a78e40a4962610b1c6d63c8a.json` and
`build/ubuntu-fuse-capture-wait-close/oci-services-79a6826040174c54884fb96153bb7d51.json`
; SquashFS hardlink exec/reexec, ORIGIN, set-ID/nosuid, vectored/partial IO,
shared/private mapping races and filtered close/flush/FD-reuse checks pass. This is not
document-portal GUI acceptance

### Ubuntu Snap Firefox

`build/installed-ubuntu-fuse/ubuntu-firefox-20261029/gui-report.json` and
`ubuntu-firefox-relaunch-20261029/gui-report.json` cover fresh and reused profiles
of Firefox 158.0.1-1 revision 9112. Ordinary UID 1000, input, download,
chooser/readback, WebGL green pixels and normal exit pass on the same staged
runtime as Chromium above. The first directory's `support.json` records hardware
WebRender on Zink/Turnip Adreno 840, content sandbox level 6, seccomp TSYNC and an
empty sandbox syscall log. The privacy-reduced WebGL renderer string is not GPU
identity. The fresh run opened the chooser by keyboard after two ineffective mouse
activations; repeated mouse activation, including Recent-file selection, passes in
the relaunch. Its `receipts.jsonl` retains pointer down/up/click ordering; the
initial activation discrepancy has no diagnosed cause yet.
Package refresh/hook coverage is separate in `build/ubuntu-firefox-refresh-resolved.log`.

### Ubuntu Snap portal

`build/installed-ubuntu-fuse/ubuntu-portal-disconnect-ready-20261029.json` (Firefox)
and `ubuntu-chromium-portal-20261029.json` (Chromium) use stock
xdg-desktop-portal 1.18.4-1ubuntu2.24.04.3 and libfuse3 3.14.0-5build1 on the
staged runtime above. Each passes real Snap document grant/read/write/revoke,
two SIGTERM/reactivation rounds, daemon SIGKILL with helper auto-unmount, and
whole user-service restart/stop with subsequent D-Bus activation. Exact daemon
and helper exits and FUSE mount removal are asserted. Browser GUI receipts
separately cover chooser/readback.

### Ubuntu VS Code

`build/installed-ubuntu-fuse/ubuntu-vscode-exit-20261027/gui-report.json`; Code 1.142.0,
Electron 43.7.7: editor/save, external-file watch, integrated PTY, GDB
breakpoint/step/locals, window reload and normal close. Hardware composition/WebGL and
renderer seccomp/NNP are enabled. The current proc-path runtime is staged;
pidfd observation confirms that the identified Electron editor exits, independently
of CLI lifetime.

### Ubuntu default boot

`build/ubuntu-default-debugger-recovery/oci-services-210f2db3b2a8473a8616bd8fb94d123d.json`
records stock systemd 255.4 reaching running with zero failed units on a private
bridge; D-Bus/logind, journal readback/restart, binfmt and RTKit lifecycle pass.
Supervisor is `729b3c3b988519fa72d1a6cbc4f038ef72c918b70358c5f2491095fef88ac578`.
The receipt predates the full shutdown assertion and is not complete acceptance:
its console log contains failed `lxc.service` and `podman-restart.service` stops.
Stock Podman 4.9.3's unit invokes `podman stop` without arguments when no containers
match; stock LXC 5.0.3's `lxc-autostart -a -A -s` returns 1 for an empty list
(`failed == count == 0`). The native runtime does not mask either exit status.
Reproduction and file-call trace are under `build/installed-ubuntu-fuse/` in
`09cb1da9c2de4f6eb55e8384bf775220.log` and
`cf475a4a406e488a80a92dddb2e4033f.log`. These package shutdown limitations are
separate from passing real container workflows.
Libvirt's default bridge control remains unsupported; VM-network acceptance is
separate from this boot and the managed TCG test.

### Ubuntu services

`build/ubuntu-cups-restart-current.log`: stock CUPS Snap answers lpstat before and
after service restart, then stops normally. `build/ubuntu-rtkit-current.log`: RTKit
D-Bus query/restart/stop; `build/ubuntu-logger-claim-current.log`: journal
write/sync/readback with the requested parent PID. These focused control-target runs do
not certify the default OS target

## Alpine

The broader OpenRC system-workload reruns use staged supervisor
`729b3c3b988519fa72d1a6cbc4f038ef72c918b70358c5f2491095fef88ac578`.
System receipts below are `build/installed-alpine-turnip/oci-services-ID.json`:

| Workload | Passing receipt ID |
| --- | --- |
| Managed Libvirt/QEMU | `fc0fdbdf10d9441bb2606d6a7a94e644` |
| Direct QEMU TCG | `41590da972504ea7bb4da1c136173b5b` |
| BuildKit build/cache/secret/export/cancel | `d4b8e4b89a2c46c892074ff0e8735f8f` |
| LXC lifecycle and bridge | `c73b29ff9da94cc7ba41c1565f9a75eb` |
| LTP core as ordinary user | `259c87c578e84fe98cb5163dc574cd30` |

Docker, Podman, Firefox and focused portal results below use the 2026-10-29
staged Release supervisor
`a5d46e41c30ee2af9da368e39cc55012d00106eb5649e9ec292002e52cf19ae3`.
They do not transfer the broader suite to that build.

The 2026-10-26 rerun retains the recipe-installed musl environment and OpenRC.
System receipts contain their staged native hashes; the graphical rerun uses
supervisor `1ce1dd0c902e235d71f634fc83092ced45809c1d9aacd013f4e76095dbcad561`.
It does not retroactively certify the installed APK or all system scenarios
on that final bundle.

### Alpine recipe setup

`build/installer-alpine-turnip.json`, `build/installer-alpine-turnip-graphics.json`;
official Alpine 3.23.6 minirootfs, OpenRC, ordinary user `mdtester`, Weston and
recipe-built Turnip 26.2.3. Current helper refresh:
`build/alpine-recipe-matrix-current/oci-services-b1df331f4f7341bda5a6d27757e3116a.json`
. `build/alpine-matrix-apk-complete.log` covers tree 2.2.1-r0 install/reinstall,
package-file audit, removal and BusyBox trigger restoration

### Alpine OpenRC

`build/matrix-debian-alpine-20261026/alpine-boot/oci-services-37bc0b58a4224579befb4328391d92f8.json`;
stock OpenRC 0.63 PID 1 with the fixture's explicit container runlevels and private
bridge. Boot completion, syslog/crond start/restart/stop, PID retirement and exact
launch cleanup pass. This does not certify host hardware services or unchanged
bare-metal runlevels

### Booted Alpine Docker

`build/installed-alpine-turnip/oci-services-d63a3c032c9e4334bb6fc6ffc266d683.json`;
Docker 29.5.2, stock OpenRC service without daemon.json, default containerd
snapshotter/overlayfs and iptables-nft frontend; bridge TCP/UDP, peer names, local
outbound, binds/exec, port conflicts and release pass. Both containers also pass
internet access and 20 external DNS requests each; the service remains running
after test cleanup. Explicit nftables/no-userland-proxy configuration is a
separate test profile.

### Booted Alpine Podman

`build/installed-alpine-turnip/oci-services-41f2b93881024d66af9694326c65c8d1.json`;
OpenRC, Podman 5.7.0, overlay, bridge with iptables frontend, stock seccomp and enabled
cgroups; TCP/UDP, peer names, local outbound, binds/exec, conflicts and port release
pass, including internet access and 20 external DNS requests per container. Installing its
unconditional INPUT counter while Chromium already owns direct connections remains
blocked with EBUSY; this pass starts Podman before browser traffic

### Alpine Libvirt/QEMU

`build/installed-alpine-turnip/oci-services-2011ee31bfb04e239f9570260ccf9b2e.json`;
packaged modular libvirt with virtlogd/virtqemud, managed TCG kernel/initrd boot, paused
start/resume, serial, poweroff, undefine and cleanup. Direct QEMU:
`build/installed-alpine-turnip/oci-services-97bdfdf32d05417ba4f9737bd6a49780.json`. VM
networking and KVM untested

### Alpine LXC

`build/installed-alpine-turnip/oci-services-143014e525b44c1c914e4b5f36a81388.json`;
packaged LXC 6.0.5, stock common policy/seccomp, nested Debian, attach/services, mounts,
TCP/UDP, three normal/forced boot-stop cycles and exact process/veth/cgroup cleanup.
Outer bridge control uses Alpine BusyBox ip

### Alpine BuildKit

`build/installed-alpine-turnip/oci-services-b1c37b088ac4444ba78794192e0f009c.json`
(service), `oci-services-fe060514bf6145eca2e3fc2d27c4d01b.json` (workload);
official Alpine buildkit/buildctl 0.25.1-r6 packages, CNI 1.8.0-r6, OCI worker under
OpenRC. Multistage build, cache reuse/invalidation, secret/cache mounts, exported-image
execution, cancellation and cleanup pass

### Alpine LTP core

`build/installed-alpine-turnip/oci-services-4263326873674c95b65435d33672325e.json`;
reviewed 20250930 sources built with Alpine compiler/musl; all seven binaries pass as
guest UID 1000

### Alpine unavailable packages

`build/alpine-matrix-buildctl-install.log`; enabled official v3.23 main/community
repositories supply neither systemd/nspawn nor snapd. Those package-specific workflows
are NA; OpenRC, LXC and native browsers remain separate checks, not substitute passes
for Snap or nspawn

### Alpine graphics/Chromium

`build/alpine-egl-gbm-current.log` covers 64 GBM/EGL frames.
`build/installed-alpine-turnip/alpine-chromium-current/gui-report.json` and
`alpine-chromium-relaunch/gui-report.json` cover packaged Chromium 149.0.7827.53:
ordinary-user Wayland input, download, GTK chooser/readback, WebGL green pixels, normal
close and same-profile relaunch. Both runs render green pixels through Zink/Turnip.
The `alpine-chromium-sandbox-final` GPU report separately confirms hardware
composition, `Sandboxed: true` and zero GPU-process crashes. Recipe Mesa 26.2.3
received an incremental rebuild with the common queue patch; this is not a new
full installation.
`a2c3a0ac1b9c4220abb0785a0ee36f29.log` covers the real Mesa queue lifecycle/failure
fixture. Document-portal grants and full desktop discovery remain separate

### Alpine native Firefox

Staged fixture `d29e0f07832d4464bf623237af4db900`, Firefox 151.0.3;
`build/installed-alpine-turnip/alpine-firefox-current/gui-report.json` and
`alpine-firefox-relaunch/gui-report.json` pass ordinary-user input, download,
file readback, WebGL green pixels, normal close and reuse of the same profile. Firefox
reports its privacy-masked Adreno renderer name, not the actual device model. File
selection uses the GTK fallback because the desktop portal service is absent; this does
not certify document grants or Flatpak

### Alpine Flatpak browsers and portal

The 2026-10-29 `alpine-firefox-20261029/gui-report.json` and
`alpine-firefox-relaunch-20261029/gui-report.json`, under
`build/installed-alpine-turnip/`, pass all six GUI assertions for stock Firefox
159.0, with fresh and reused profiles. The first directory's `support.json`
confirms hardware WebRender, Zink/Turnip on Adreno 840, content sandbox level 6
and an empty sandbox syscall log. No browser sandbox bypass is used.

The staged 2026-10-27 session uses stock Flatpak 1.16.6-r0 and its portal.
With supervisor `729b3c3b988519fa72d1a6cbc4f038ef72c918b70358c5f2491095fef88ac578`,
`alpine-chromium-debugger-recovery/gui-report.json` and
`alpine-chromium-debugger-relaunch/gui-report.json` pass all six GUI assertions
for Chromium 156.0.8078.47. `alpine-firefox-debugger-recovery/gui-report.json` and
`alpine-firefox-debugger-relaunch/gui-report.json` do the same for Firefox 159.0,
including a reused profile and normal exit. These paths are under
`build/installed-alpine-turnip/`. The stock Flatpak update installing Firefox
159.0 succeeds (`fc44ba9c1aac46a2bb28f59ad9897eab.log`); repository repair dry-run
validates all 27 refs (`6bb52c026e8448afaaab86b33c5b6c78.log`). No custom Flatpak
or portal binary is used in these runs.
`alpine-portal-debugger-recovery.json` passes two document grant/read/write/revoke
and teardown cycles on this build, but does not disprove the packaged shutdown
race below.

Under `build/installed-alpine-turnip/`,
`alpine-flatpak-firefox-stock-complete-20261027/gui-report.json` verifies
Firefox 158.0.1 with a reused profile; Chromium's
`alpine-flatpak-chromium-20261027/gui-report.json` and
`alpine-flatpak-chromium-relaunch-20261027/gui-report.json` verify fresh and
reused profiles. Ordinary-user input, download, GTK selection/readback, WebGL
green pixels and normal exit pass. Chromium reports ANGLE/Zink/Turnip on Adreno
840; `dc7d5f16fdc04239b3d587cef7d5f345.log` records renderer UID 1000,
NoNewPrivs=1, Seccomp=2 and nested PID views. Packaging and portal lifecycle
are independently qualified above and below.
With supervisor `89f3906ac152537a10a485acd80161755becc7d37a913387836e65419ee21181`,
`37bb4743dcb8427692bfa82eba768e4e.log` covers stock `flatpak enter` into a live
private working directory plus document grants/read/write/revoke. Portal lifecycle
is **U (upstream defect)**: `alpine-portal-20261029.json` records a second-round
SIGTERM timeout with packaged xdg-desktop-portal 1.20.3-r4 and libfuse 3.18.3-r0.
`00357410390d4f88bb3f5f825dec66e4.log` shows `g_main_loop_quit` taking its
interrupted main-context mutex from the signal handler, matching upstream
[shutdown deadlock #1896](https://github.com/flatpak/xdg-desktop-portal/pull/1896).
The current Alpine v3.23 package still contains that handler; the installed
binary passes `apk audit`. No delivery timing change in Shroot or installer
source build substitutes for the upstream fix. Forced cleanup of the failed
test process is not a passing portal exit.
The separate `alpine-portal-auto-unmount-20261029.json` passes actual sandbox
grants/read/write/revocation, daemon SIGKILL, surviving helper exit and automatic
mount removal. It does not certify graceful shutdown or change the U status.

### Alpine Flatpak VS Code

`build/installed-alpine-turnip/alpine-flatpak-vscode-portal-cdp-20261027/gui-report.json`
passes editor/save, terminal, external changes, reload, GDB breakpoint/locals,
step/continue and normal closure for VS Code 1.139.1 / Electron 43.6.0.
GPU composition uses Zink/Turnip; the renderer retains UID 1000, NNP and seccomp.
This is **lab-patched portal coverage**, not stock-package acceptance:
the FD collision described in [desktop workflows](desktop-workflows.md#vs-code--electron)
blocks the unmodified Alpine portal's Zypak spawn path.
Only `portal/flatpak-portal` was built from upstream Flatpak 1.16.6 with the
tracked lab patch and run at its test path; the packaged binary and installer
remain unchanged. The upstream archive's SHA-512 is
`698fcc892fba0d944519ad7ed4bb4b12aa809e2e2682636c7cdfbd5f6d65571fdaa1e240d02b3df641c405e0ecaeb35d22adaab50a4347521bb6ca98d6bd5b93`,
matching Alpine's v3.23 APKBUILD. The stock portal was restored for the browser
receipts above. Stock acceptance is **U (upstream FD collision)**. The 2026-10-29
rerun uses packaged Flatpak 1.16.6-r0, portal SHA-256
`fc88777dfdeda9b07a75f32dac2c5b4f0988db61cb4a7ed6c21cf312b348ff72`.
`alpine-vscode-stock-fast-20261029/gui-report.json` records launcher exit before
editor readiness; `2f600596c673461682c4043e15d8fb29.log` records the instance-ID
descriptor failure. The FD remapping collision also reproduces in a native
Termux subprocess without Shroot (`build/flatpak-portal-fd-native.py`): the
environment FD replaces the instance pipe, then closes it. Including the pipe
in the existing remapper preserves it. This source-level attribution does not
claim upstream maintainer acknowledgement; CLI lifetime alone is not GUI readiness.

## Fedora

The 2026-10-29 browser update, machinectl, LXC, Libvirt/QEMU,
BuildKit and LTP reruns use staged Release supervisor
`b0148a1e87610d7ccceece4054d25fa70205a25f398532aba009f98eea97a2a3`,
default lazy entry and Android shell UID 2000. The older receipts use
`f54519b2c71a2b55d79eed69fd9af3eaeb056a0085a787dce9f58497a57c5bc6`,
unless specified separately. Base is not covered by this batch.

### Fedora recipe and packages

`build/installer-fedora-turnip.json`; official Fedora 44 OCI rootfs, ordinary user
`mdtester`, Weston, recipe-built Mesa 26.2.3 Turnip.
`build/fedora-packages-lifecycle.log` and `build/fedora-packages-remove.log` cover hello
install/execute/reinstall/RPM verification/removal. The current recipe's `keep`
system setup refreshed desktop/audio helpers without rebuilding Mesa:
`build/installed-fedora-turnip/22a8dd63417945219d72b8c5e8b37ba4.log`.
Clean focused graphical boot on the refreshed helpers is recorded by fixture
`6d38977268c04b30852364dde620ebea`, `ca6fff4cf4fa49fcbcfbff229a802957.log`
and `fedora-clean-start-20261026.png`: Weston, authenticated user controller,
Wayland socket and user D-Bus become ready without a manual service restart.
Full recipe discovery and desktop acceptance remain separate.

### Fedora Flatpak Firefox

`build/installed-fedora-turnip/fedora-firefox-update-20261029/gui-report.json` and
`fedora-firefox-update-relaunch-20261029/gui-report.json`; Firefox 159.0 with
SDK Mesa 26.2.3, ordinary sandbox and normal vsync. Fresh and
same-profile launches pass keyboard/pointer, download, GUI file selection/readback,
WebGL pixels and normal close. `fedora-firefox-update-relaunch-20261029/support.json` confirms
hardware WebRender through Zink/Turnip on Adreno 840, Wayland and effective
content sandbox level 6. The WebGL page's privacy-masked renderer alone is not
hardware evidence. Ordinary Flatpak update from 158.0.1 to 159.0 is recorded in
`ace6f2b3e42d45a5b7d9bc61ec633859.log`.

`fedora-firefox-current-gl-20261029/gui-report.json` additionally passes all six
checks after selecting the same current immutable Mesa prefix as Chromium below.

### Fedora Flatpak Chromium

`build/installed-fedora-turnip/fedora-chromium-current-gl-fresh-20261029/gui-report.json` and
`fedora-chromium-current-gl-relaunch-20261029/gui-report.json`; Chromium 156.0.8078.73,
ordinary UID 1000, stock Flatpak launcher, no sandbox-disabling switches. Both
runs pass input, download, chooser/readback, exact green WebGL pixels and normal
close; the second reuses the profile. `fedora-chromium-current-gl-fresh-20261029/gpu.txt`
reports hardware composition through Zink/Turnip on Adreno 840, GPU sandbox
enabled and zero GPU-process crashes. This uses the current recipe's Mesa queue
patch, lab-built in the matching SDK; publication and library hashes are in
`fedora-mesa-publication.json`. The selected immutable prefix is
`/opt/magicdesk/mesa/26.2.3-d6f0e8f2b2c05a07fa1a60537509f700b018a3a97fad45fa8e615bdccebf443a`.
Current helper format 5 and the installed Flatpak trigger retain that selection
(`90cd41da371643cda837120a718e18a3.log`, `608c7809c9bb423cb572b15522492a9f.log`).
The host ABI probe also passes; the SDK build additionally requires the packaged
`xcb-util-keysyms` dependency in Fedora. This is explicit lab SDK preparation,
not a second automatic Mesa compilation in the recipe.
The ordinary Flatpak update from 156.0.8078.47 is recorded in
`ace6f2b3e42d45a5b7d9bc61ec633859.log`.
`fedora-chromium-sandbox-20261026.png` separately records
`chrome://sandbox`: Flatpak layer 1, PID/network namespaces and seccomp-BPF/TSYNC
reported active. Live renderer status in `123de3b6e6444d07ab9a73c346deb40d.log`
confirms UID 1000, NNP=1 and Seccomp=2.

### Fedora default boot/services

`build/fedora-default-debugger-recovery/oci-services-b4bfda37fbde4c44a8ce83721f52ba24.json`
passes the default target, journal/D-Bus/logind, binfmt and RTKit lifecycle,
including the full shutdown-result assertion, with supervisor
`729b3c3b988519fa72d1a6cbc4f038ef72c918b70358c5f2491095fef88ac578`, a private
bridge and explicit `CAP_SYS_MODULE` drop. Native RT scheduling remains denied;
udisks' unavailable `/proc/swaps` probe does not fail a unit in this run.

`build/matrix-ubuntu-fedora-20261026/fedora-boot/oci-services-82c1f7a99a4141c99dc90ab0750d40a0.json`; systemd
259.10, private bridge, default target running with no failed units, journal
sync/readback, D-Bus/logind and RTKit lifecycle. `build/fedora-hostnamed-fixed.log`
additionally covers hostnamectl on a later staged runtime

### Booted Fedora Docker

`build/installed-fedora-turnip/oci-services-4d3af74ecbd846d38873e493f4210927.json`;
stock Docker 29.8.2 service, containerd 2.3.5 overlayfs snapshotter, runc 1.5.2 and
recipe-selected iptables-nft frontend, supervisor `7ae767cc...` as in the nspawn
receipt below. Image availability, IPv4 bridge, TCP/UDP
publication, peer IP/names, binds/exec, conflicts/release and internet with 20 external
DNS queries per container pass; native Docker nftables backend and IPv6 are not covered

### Booted Fedora Podman

`build/installed-fedora-turnip/oci-services-121d49b963a1493faea0bd3a9e601c5b.json`;
Podman 5.8.9, stock seccomp, enabled cgroups, overlay and bridge. TCP/UDP publication, local outbound,
peer IP/names, binds/repeated exec, conflicts/release and internet with 20 external DNS
queries per container pass; supervisor `7ae767cc...` as in the nspawn receipt below

### Fedora desktop/VS Code

`build/installed-fedora-turnip/fedora-vscode-current-20261029/gui-report.json`; Weston/user
systemd session and VS Code 1.140.0 editor/save, external watcher, PTY, GDB
breakpoint/step/locals, window reload and normal close pass. Hardware composition
and WebGL are enabled; the ordinary-user renderer retains seccomp and NNP.
Recipe discovery and a full desktop lifecycle remain separate.

Stock Weston 15.0.1-2.fc44 aborts at `surface-state.c:282` on
`comp->view_list_needs_rebuild` during a Chromium surface transition
(`build/installed-fedora-turnip/fedora-weston-20261029.log`, 2026-10-29 12:14:56).
This matches the documented Chromium/aarch64 subsurface-without-views case in
[the upstream fix carried by meta-arago](https://patchwork.yoctoproject.org/project/arago/patch/20260601170844.1723390-1-t-lannynd@ti.com/)
and [Weston MR 2085](https://gitlab.freedesktop.org/wayland/weston/-/merge_requests/2085).
The installed Fedora package has no available update in its current repositories;
the desktop cell is U, independently of completed browser runs.

### Fedora Turnip

`build/fedora-gbm-pixels.log`; recipe-built Mesa 26.2.3, GBM/EGL import, Zink/Turnip on
Adreno 840, 64 exact alternating frames and real DMA-BUF fences. This does not certify a
Flatpak/Snap driver bundle

### Fedora Flatpak portal

`build/installed-fedora-turnip/fedora-firefox-portal-20261029.json` (Firefox) and
`fedora-portal-20261029.json` (Chromium); ordinary-user Flatpak export,
read/write through the live sandbox and immediate revocation pass. Each package
passes two graceful exact-process exits, SIGKILL with surviving helper
auto-unmount, whole-unit restart/stop, FUSE mount removal and D-Bus reactivation.
Jobs `eac09b93d510433caf3f6ba797956e7b` and
`8614b656c50443eba6be25081c364d6f`; stock portal 1.22.1 and libfuse 3.18.3,
supervisor `a5d46e41c30ee2af9da368e39cc55012d00106eb5649e9ec292002e52cf19ae3`.
Browser GUI receipts separately cover chooser/readback.

### Fedora machinectl

`build/installed-fedora-turnip/oci-services-42295e8715e1497fb171b3f98026040e.json`;
stock systemd 259.10 unit boots nested Debian with private users/veth, shell/services,
20 TCP and 20 UDP exchanges per round, poweroff, killed-leader restart and cleanup
across three boots

### Fedora LXC

`build/installed-fedora-turnip/oci-services-3119039b443948b7ac503d9ccf413209.json`; LXC
6.0.6, stock common policy/seccomp, guest-root nested Debian, attach/services, mounts,
20 TCP and 20 UDP bridge exchanges per round, normal/forced stop and cleanup across
three boots. Unprivileged LXC and external networking remain untested

### Fedora Libvirt/QEMU

`build/installed-fedora-turnip/oci-services-4c23de9a21e145daa6dfb4e2b82f7a26.json`;
stock modular libvirt 12.0.0 and QEMU 10.2.2, define/start paused, serial, resume, real
kernel/initrd TCG boot, poweroff/undefine and fixture cleanup. VM networking and KVM
untested

### Fedora direct QEMU

`build/installed-fedora-turnip/oci-services-802abdce85bb48ab88220927ea08a5cc.json`;
QEMU 10.2.2, ARM64 kernel/initramfs serial boot and clean exit with TCG. KVM and VM
networking are not covered

### Fedora BuildKit

`build/installed-fedora-turnip/oci-services-19dcba1953e84b3cb4499308d750ebf0.json`;
official BuildKit 0.34.1 ARM64 archive SHA-256
`c8c97b84baf89dfe0ea85063ac24ad88eb909979ea476ce3ef9e8e11e60ebef8`, Fedora CNI 1.9.1,
TOML-configured OCI/overlayfs/CNI worker with sandbox.
Multistage/cache/secret/export/run/cancel/cleanup pass; rootless/containerd workers and
external networking untested

### Fedora LTP core

`build/installed-fedora-turnip/oci-services-626fca1ec5764e338714117f9a203c67.json`;
reviewed 20250930 sources built with Fedora compiler/libc in the persistent lab-only
`/opt/md-ltp-build` tree. All seven selected binaries pass as guest UID 1000,
including the ARM64 `epoll_create1` ABI; no failed, broken or skipped checks.

## Arch

The Docker, Podman, QEMU/Libvirt, machinectl, BuildKit, LXC and LTP receipts use supervisor
`3e32c8511ca8d95bd0be31aca2a1b770a42c2077c548af079dfb7c24126f969f`.
These certify their exact
staged bundles, not the installed APK or subsequent source edits.

### Arch default boot/services

`build/arch-default-bridge-current/oci-services-3c014103a9cc4944beeb1999ff86627b.json`;
private `bridge`, default target reaches `running`, journal write/sync/readback, D-Bus
and logind restart pass; resolved is active. In `routed`, its initial-namespace bind to
port 53 is also an Android publication and is denied by the host; this pass does not
cover that mode

### Booted Arch Docker

`build/installed-arch-turnip/oci-services-b1d6e44dd86643548f53aba58e71282e.json`;
stock service, nftables, no userland proxy, local TCP/UDP/peers/port release,
external internet and 20 DNS lookups per container pass. The daemon remains
running after fixture cleanup. Missing bridge-port sysfs, memory-controller
events and unsupported conntrack deletion remain visible in its journal.

### Booted Arch Podman

`build/installed-arch-turnip/oci-services-15cee848e95d405888dbcb6cea364959.json`;
staged native hashes, overlay, bridge, stock seccomp, enabled cgroups, no `--ulimit`
overrides; published TCP/UDP, peer names, local outbound, binds/exec, conflicts and port
release, external internet and 20 DNS lookups per container pass.

### Arch managed Libvirt/QEMU

`build/installed-arch-turnip/oci-services-bc4defcf928f422c81e413bc33a23b69.json`;
libvirt 12.8.0 / QEMU 11.1.2, managed TCG kernel/initramfs boot, paused start/resume, serial, normal shutdown,
undefine and cleanup. VM networking and KVM remain untested.

### Arch direct QEMU

`build/installed-arch-turnip/oci-services-231428ab2a80491cb10037f1a20c68aa.json`;
packaged QEMU, ARM64 kernel/initramfs TCG boot, serial kernel-contract checks and normal
poweroff pass. VM networking and KVM remain untested

### Arch package lifecycle

`build/arch-recipe-current/oci-services-4ba3363dd1b243988c709225b0366c19.json` covers
current recipe setup, signed repository upgrade and tree installation.
`build/arch-package-lifecycle-current.log` and
`build/arch-package-lifecycle-runtime.json` cover booted stock pacman reinstall, all
post-transaction hooks, mkinitcpio 42.2 image generation and independent repeat, file
verification and removal. Hardware/firmware warnings remain visible; no failed hooks or
generation errors

### Arch machinectl

`build/installed-arch-turnip/oci-services-c92ff0493dd642b0b0a3b0eac5feea55.json`.
The stock systemd 262.1 unit boots nested Debian 257.13 three times. Shell,
services, private user mapping, 20 TCP and 20 UDP exchanges per round, normal
poweroff, forced leader death, restart and cleanup pass. Host networkd remains
active with its packaged container-link configuration. The fixture's static
test addresses do not certify DHCP lease exchange or external connectivity.

### Arch BuildKit

`build/installed-arch-turnip/oci-services-5b0b604b91194c4188b56b566fc62c3c.json`;
0.34.1 OCI/overlayfs worker, CNI 1.9.1 bridge, sandbox mode, multistage
build/cache/secret/export/run/cancel/cleanup, no explicit container ulimits;
rootless/containerd workers and external network untested

### Arch LXC

`build/installed-arch-turnip/oci-services-6a581630ee6a451bb78e38cc91f274f0.json`; 7.0.0
stock common policy, guest-root container, nested Debian systemd 257.13,
attach/services, devpts/proc/cgroup mounts, stock seccomp, 20 TCP + 20 UDP bridge
exchanges per round, normal shutdown, forced stop and restart across three boots;
process/veth/cgroup/root/bridge cleanup passes. Unprivileged LXC and external
DNS/internet not covered

### Arch LTP core

`build/installed-arch-turnip/oci-services-6728af19dc8d44d48f76a321cffc6662.json`;
all seven reviewed LTP 20250930 binaries pass as the ordinary recipe user
on supervisor `3e32c851...`.

### Arch network inspection and portals

Supervisor `3e32c8511ca8d95bd0be31aca2a1b770a42c2077c548af079dfb7c24126f969f`
passes stock `networkctl status eth0` with both address families and routes:
`build/installed-arch-turnip/cf4fe2901323447e86b2d6fa7b509ab8.log`.
This is inspection of the fixture uplink, not a DHCP lease test.

`build/installed-arch-turnip/portal-current.json` and
`abc0280977b540fb88bf74544e938e01.log` cover two document export/read/write/revoke
cycles as UID 1001, normal portal exit, FUSE removal and D-Bus reactivation on
that same bundle. The installed desktop/audio helpers were refreshed from the
current recipe; this is not a new distribution installation. Browser rendering
acceptance remains separate.

### Arch Flatpak prerequisites

The same staged supervisor uses Mesa 26.2.3 built from recipe identity
`160c9637c6b0b77853677c166f5aab2b0da1c0405979678593816a0078c493e5`,
including deferred worker queues. Host build receipt:
`build/installed-arch-turnip/2fbf363b6720453e8d541a2249a3145b.log`.
The explicit lab SDK build for `org.freedesktop.Sdk/aarch64/25.08` is recorded
in `91422de30c844e1798e767cc4f5202e4.log` under the same directory;
SDK build identity `d813fb77462a44591054bd7bc204aeaf1bc98733b6aa455026781bb31065cbf3`.
The host Mesa requires GLIBC_2.43, so copying it into that older runtime is not
ABI-compatible. This additional SDK build is not automatic recipe work.

Stock Flatpak 1.18.4-2 blocks VS Code's Zypak spawn through the FD collision
described in [desktop workflows](desktop-workflows.md#vs-code--electron).
`arch-portal-fd-collision.trace` under the same build directory records both
`--env-fd=22` and `--instance-id-fd=22`, followed by descriptor rejection and
exit 1. Ordinary `flatpak-spawn --sandbox /usr/bin/true` passes independently
(`4bfa8b6ef3da4263a4ed6c4566bbf9bc.log`).
The lab-only portal build uses the shared patch and upstream 1.18.4 archive,
SHA-256 `b899a7a00c48d2c626cb8ec33fe556720b376c25805d7222430c0fdca4c6ac8d`;
its build receipt is `e215b0b73bf54fb1970598b70e4d2189.log`.
The packaged portal and user installer are not replaced by that build.

### Arch browser and editor acceptance

On the `3e32c851` supervisor above, Flatpak Chromium 156.0.8078.47 and Firefox
159.0 each pass fresh-profile and same-profile GUI runs, 6/6: keyboard, pointer,
download, exact chooser readback, WebGL pixels and normal exit. Reports under
`build/installed-arch-turnip/`: `arch-chromium-mesa-current/gui-report.json`,
`arch-chromium-mesa-relaunch/gui-report.json`, `arch-firefox-current/gui-report.json`
and `arch-firefox-relaunch/gui-report.json`. Stock Flatpak 1.18.4-2 supplies the
portal in these runs. `fc20fe0a563c46b2b6245b4bc7d14e40.log` records package
update and a successful repair dry-run for the user installation.

`arch-firefox-support.json` independently records Wayland, hardware WebRender,
Zink/Turnip Adreno 840 and effective content sandbox level 6. Content proc
inspection (`ae5c0e54e573403ca5834cafaba0ba8c.log`) confirms UID 1001,
NoNewPrivs 1 and Seccomp 2. Hardware video decoding is unavailable; WebGL's
privacy-masked renderer string is not the hardware-identification evidence.
`arch-chromium-gpu-mesa-current.txt` and `arch-chromium-gpu-mesa-relaunch.txt`
record hardware composition, Zink/Turnip, GPU `Sandboxed: true` and zero GPU
crashes with the current Mesa build above. GPU and renderer proc inspection
(`5d139350ca9c40269cac00e56dce801e.log` and
`8ac40a0913754907a5e02292d04b8195.log`) confirms UID 1001, NoNewPrivs 1
and Seccomp 2. The first inspection's renderer had already exited after page
navigation; the second inspects a live renderer. Direct Vulkan/WebGPU backend
selection and hardware video decoding are not certified by these GUI runs.
Firefox and VS Code receipts retain their earlier, explicitly lab-built SDK
Mesa identity `81869f76314f8a196aa33e51a8461e6c350d14e6ed9d8ea06dea8de065ab9050`.

VS Code 1.139.1 with cpptools 1.34.4 passes two editor/save, external-file-event,
terminal, reload and GDB breakpoint/step/locals runs, hardware composition,
renderer UID 1001/NoNewPrivs 1/Seccomp 2 and normal editor exit. Reports:
`arch-vscode-patched-portal/gui-report.json` and
`arch-vscode-patched-repeat/gui-report.json`. Both use the explicitly lab-built
portal above; the second requires no manual intervention. The stock portal was
restored afterward (`d73f2c747cc848f6bc05908bfff645f6.log`). This does not qualify
the unmodified Arch VS Code/portal combination. Stock acceptance is **U (upstream
FD collision)**. The 2026-10-29 rerun retains packaged Flatpak 1.18.4-2, portal
SHA-256 `3fe55303b6a39a8a67f398a248b98c209c1d4f4d8f5b1b79b0eb237f21385951`.
`arch-vscode-stock-20261029/gui-report.json` records the editor-readiness timeout;
`b0d52e8875534ca694348347f4c6401e.log` rejects instance-ID FD 22. The native
control described under Alpine reproduces the same remapping defect without
Shroot. The user installer and packaged portal remain unchanged.

Weston 15.0.1 retains clipboard data between guest clients (`wl-paste` reads
Firefox's exact support report), but Android/guest clipboard transfer has not
passed. Its upstream Wayland backend does not bind the parent's
`wl_data_device_manager`; direct-client clipboard coverage does not certify
nested desktop forwarding. Desktop acceptance remains incomplete.

## openSUSE

### openSUSE recipe

`build/installer-opensuse-turnip.json`; official Tumbleweed LXC rootfs authenticated
with its official SHA-256 sidecar, Weston, ordinary user and Mesa 26.2.3 Turnip build.
Repeated recipe preserves user data. `build/opensuse-matrix-packages.log` covers
installation/transaction hooks of the container packages. The current receipt
`build/installed-opensuse-turnip/9f396434f1a94929a3c69220e76a79f3.log` verifies
repository hello 2.12.3-1.4 installation, execution, forced reinstall, `rpm -V`,
removal and post-transaction scripts. Current graphics evidence is recorded below.

### openSUSE default boot

Current boot, binfmt and networkd receipts are in
[Boot and IPv6 acceptance](validation-matrix.md#boot-and-ipv6-acceptance). They cover
the staged runtime, not a later installed APK or every networkd option

### Booted openSUSE Docker/Podman

`build/installed-opensuse-turnip/oci-services-c875172498814c5984657be873f05151.json`
(Docker, stock service and package-selected iptables nft backend) and
`oci-services-6c588847ae0c419380e89bb74e18b478.json` (Podman). Stock seccomp/cgroups,
overlay, bridge, TCP/UDP publication, peer IP/names, binds/exec, conflicts/release and
external internet/DNS pass

### openSUSE LXC

`build/installed-opensuse-turnip/oci-services-418cb237cd8c4685846d19dbcff736d9.json`;
LXC 7.0, stock common policy and seccomp, nested Debian, attach/services, mounts, bridge
TCP/UDP and cleanup across three normal/forced-stop boots. Unprivileged LXC and external
networking remain untested

### openSUSE direct QEMU

`build/installed-opensuse-turnip/oci-services-be69d76e499d4bae8f12335907056524.json`;
ARM64 kernel/initrd TCG boot, serial output and clean exit. Libvirt, KVM and VM
networking are separate acceptance requirements

### openSUSE machinectl

`build/installed-opensuse-turnip/oci-services-48ad21e8fa584487ad73802a7baed610.json`;
stock systemd 261.4 unit, nested Debian, private users/veth, shell/services, bridge
TCP/UDP and normal/forced-stop cleanup across three boots. Cold `machinectl start`
including ownership preparation takes 35.6 seconds; subsequent starts take 1.3
and 1.0 seconds with the unchanged 120-second service timeout. The receipt uses
supervisor `5872dd94ff03a54825bb9b3ea4244b700e2b11a0b5bda84d3a4e1e7fc05b85e5`;
these are device observations, not an arbitrary-load timing guarantee.

### openSUSE Libvirt/QEMU

`build/installed-opensuse-turnip/oci-services-51f1ebd3130945eb823ae511b4be4652.json`;
stock libvirt 12.8.0 modular daemon and QEMU 11.1.2, define/start paused, serial,
resume, kernel/initrd TCG boot, shutdown/undefine and cleanup. KVM and VM networking
remain untested

### openSUSE BuildKit

`build/installed-opensuse-turnip/oci-services-5a81c6fd9cbc44a59c563da12a3fb04c.json`;
official BuildKit 0.34.1 with distribution CNI 1.6.2, OCI sandbox and overlayfs.
Multistage/cache/secret/export/run/cancel/cleanup pass; rootless/containerd workers and
external networking untested

### openSUSE LTP core

`build/installed-opensuse-turnip/oci-services-8573673126fc46e8b03f0e60800f3b3f.json`;
reviewed LTP 20250930 built with the distribution compiler/libc, guest UID 1000.
All seven selected binaries pass, with no failed/broken checks.

### openSUSE graphics and Flatpak

The 2026-10-28 desktop session `85a29db34b9e4ee0857688f95f9de195` uses Weston
16.0, ordinary user `mdtester`, stock Flatpak 1.18.4-1.1 and the supervisor hash
recorded in the machinectl receipt. Host Mesa 26.2.3 was built by the current
recipe (`160c9637c6b0b77853677c166f5aab2b0da1c0405979678593816a0078c493e5`),
job `5e3aa30838d9431bac7ef70168e83033`.

Flatpak uses the matching lab SDK build, reused from the Arch run rather than
rebuilt here: cache identity
`d813fb77462a44591054bd7bc204aeaf1bc98733b6aa455026781bb31065cbf3`, archive
SHA-256 `b05b38e1f11a80634998bc975182d8d3b3c73ac84cf70fbc3451286605fe4281`.
Import job `beb2f41bdd1a477a9c25ca6b9a2a08c2` verifies the SDK/configuration
identity and uses the ordinary publisher's ABI checks. This is explicit lab
preparation, not a second Mesa build added to the user recipe.

Chromium 156.0.8078.47 passes all six GUI checks in
`build/installed-opensuse-turnip/opensuse-chromium-current/gui-report.json`:
keyboard, pointer, download, chooser readback, WebGL pixels and normal closure.
Fresh-profile and retained-profile reruns also pass all six checks in
`opensuse-chromium-clean/gui-report.json` and
`opensuse-chromium-clean-repeat/gui-report.json`.
Its `gpu.txt` reports Zink/Turnip Adreno 840, hardware composition,
`Sandboxed: true` and zero GPU-process crashes. This does not qualify hardware
video decoding or the native Vulkan/WebGPU backend. Stock document portal
export, sandbox read/write, live revocation and `flatpak enter` private cwd pass
in `17adf0277b504240b60eff9884887161.log` in the same directory.
`opensuse-portal-lifecycle.json` additionally verifies two exact-process exits,
FUSE unmounts and D-Bus reactivation under UID 1000, using the stock portal.

Firefox 159.0 passes the six GUI checks on initial launch and profile reuse in
`opensuse-firefox-current/gui-report.json` and
`opensuse-firefox-repeat/gui-report.json`. Its current `support.json` reports
Wayland, hardware WebRender, Zink/Turnip Adreno 840 and effective content sandbox
level 6. WebGL's privacy-reduced renderer string is not used as GPU identity.

### openSUSE VS Code

Official Microsoft ARM64 RPM 1.143.1 and cpptools 1.34.4 pass editor/save,
external file watch, integrated PTY, window reload, GDB breakpoint/continue and
normal editor exit. Receipt:
`build/installed-opensuse-turnip/opensuse-vscode-current/gui-report.json`, job
`d7cd79799b034e03a9b9934f8fa18d2c`, session
`0f930feac6764e7995e0c45f0fbbbe88`, supervisor
`5c815395c7ac577c4193dfdb5930b9e1d8523bbc0af9e90a009d932b16da71d5`.
The complete repeat also passes on that session and build:
`opensuse-vscode-repeat/gui-report.json`, job
`b43a43b891a24f9b98dc4d558844077a`.
GPU composition is enabled; the renderer is UID 1000 with NoNewPrivs=1 and
Seccomp=2. Hardware video decoding and WebGPU are not qualified by feature flags.
The package's signature verifies against Microsoft's signing key; installation
and transaction hooks are recorded in `317f3e90b28f46909237e4f9cf019b50.log`.
This native RPM result does not qualify the separately packaged Flatpak editor.

## openKylin

### openKylin recipe/default boot

`build/installer-openkylin-turnip.json`; official openKylin 3.0 image, Weston, Turnip
build, ordinary user and repeated recipe preserve settings/data.
`build/openkylin-turnip-boot-rebind/oci-services-d2318cf7d61d469ca752cbf7fa907950.json`
covers the stock default target with device-bound LXC DHCP

### openKylin package lifecycle

`build/openkylin-package-lifecycle.log` and
`build/openkylin-package-lifecycle-runtime.json`; repository hello 2.12.1-ok2 install,
execution, reinstall, dpkg verification, independent file checksums and removal pass.
`dpkg --audit` remains empty

### openKylin Podman

`build/installed-openkylin-turnip/oci-services-b4221cfb1cf84865a8e08aead3542573.json`;
Podman 5.7.0, stock seccomp/cgroups, overlay, nftables bridge, published TCP/UDP, peer
DNS/IP, internet, conflicts and released ports pass

### openKylin Docker

`build/installed-openkylin-turnip/oci-services-8242b858ed214b3fbe65ac806b17ab10.json`;
official Docker service with vendor `/opt/system` command path. Overlay, bridge, TCP/UDP
publication, peer DNS/IP, external internet, conflicts and released ports pass

### openKylin Libvirt/QEMU

`build/installed-openkylin-turnip/oci-services-7294486ccd24434085e40df5ddc75622.json`;
libvirt 12.0/QEMU 10.2.1, managed ARM64 TCG boot, pause/resume, serial, poweroff and
undefine pass. `oci-services-8ec6d36797154166a9eb8c40c0882459.json` covers direct QEMU
boot. VM networking remains untested

### openKylin BuildKit

`build/installed-openkylin-turnip/oci-services-7e09f6f7835642b991ca30a1f6b69952.json`;
official BuildKit 0.34.1, OCI/overlayfs/CNI worker, multistage build, cache, secrets,
export, Podman execution, cancellation and cleanup pass

### openKylin machinectl

`build/installed-openkylin-turnip/oci-services-bdad4f1f4bd14dbd90f5e48d7ab8a6b1.json`
(Shroot) and `oci-services-12f6221ee8f64ac99967cf47b9eb7a0a.json` (Base); stock unit,
nested Debian, private users/veth, shell/services, TCP/UDP and normal/forced-stop
cleanup pass across three boots in each mode. The Shroot receipt uses supervisor
`86f82fb0...`; the Base receipt retains its own recorded build. Timeout bounds and
unit policy are unchanged

### openKylin graphical applications

Retained fixture `ccf907a3ff53416c97de3dfdb0b300d4`, supervisor `86f82fb0...`,
ordinary UID 1000, Weston and recipe Mesa 26.2.3 (`160c9637...`). The production
graphics build and Turnip enumeration pass in job `f9fdb1822d7d479999a76c033a34703f`.
Flatpak uses an explicitly reused SDK driver, not the incompatible
host glibc build; import job `6e850dd903bb4dce88cb7e3be0c81f38` records its identity
and archive checksum. No automatic second Mesa compilation is enabled.

Under `build/installed-openkylin-turnip/`:

- `openkylin-chromium-first/gui-report.json` and
  `openkylin-chromium-repeat/gui-report.json`, jobs
  `4e349d8d52274e89815fc623708a8f8c` and `ffe2fa0851f34712b5f1220fd5c97b9d`:
  Flatpak Chromium 156.0.8078.47 on runtime 25.08 passes input, click, download,
  portal file selection/readback, Turnip/Zink WebGL pixels and normal exit on
  clean and reused profiles.
- `openkylin-firefox-first/gui-report.json` and
  `openkylin-firefox-repeat/gui-report.json`, jobs
  `06ab62d42f564e5eae9b0963a117fa60` and `d12c93b4c0524cc890e363b47233580d`:
  Flatpak Firefox 159.0 passes the same six checks on clean and reused profiles.
  `firefox-webrender.png` records hardware WebRender; the content processes
  retain UID 1000, NNP 1 and seccomp 2. WebGL's renderer name is privacy-masked.
- `openkylin-portal-lifecycle-full.json`, job `746f217f0cf840c0a1bca1e4301e1b8e`:
  packaged portal 1.21.1 passes two export, sandbox IO, revocation, SIGTERM,
  FUSE unmount and D-Bus reactivation cycles. The lab installs `libglib2.0-bin`
  for the fixture's `gdbus` observer.
- `openkylin-vscode-current/gui-report.json`, job `12753820c6d04b6699370b5771f82c7d`:
  official Code 1.143.1 ARM64 deb, cpptools 1.34.4 and packaged GDB 17.1 pass
  editor/save, external watch, integrated PTY, window reload, breakpoint,
  local-variable inspection, step, continuation and normal editor exit.
  GPU composition is enabled; the renderer retains UID 1000, NNP 1 and seccomp 2.

### openKylin LTP core

`build/installed-openkylin-turnip/oci-services-15047b71876e45ecba1c15582415ce0c.json`
(Shroot) and `oci-services-6bbf78520ce848ea8abef45a2766aa5a.json` (Base); seven pinned
upstream binaries built with this distribution's compiler/libc pass as UID 1000 in both
modes, including the ARM64 epoll_create1 ABI, with no FAIL/BROK/TCONF

### openKylin LXC

`build/installed-openkylin-turnip/oci-services-657d92e0fd5d4f67b9482a2a415f921d.json`;
packaged LXC 6.0.6, nested Debian, stock common policy/seccomp, attach/services, mounts,
bridge TCP/UDP and cleanup pass across three normal/forced-stop boots. Unprivileged LXC
and external networking remain untested

## Deepin

### Deepin recipe

`build/installer-deepin-turnip.log`, `build/installer-deepin-turnip.json`; official
`linuxdeepin/deepin:crimson-arm64`, Weston, Mesa 26.2.3 Turnip build, locale, timezone,
ordinary user and repeated recipe preserving settings/data. Current rendering
acceptance is recorded below; Start discovery and the full desktop lifecycle
remain incomplete.

Package lifecycle: jobs `26331304f7594f36805f9ee6e5661106` and
`245c217d67204ce98478ab0e5ad7312a` under `build/installed-deepin-turnip/`
install/reinstall hello 2.10-2, execute and verify it, remove it and leave
`dpkg --audit` empty. These use supervisor `86f82fb0e2d3765a70c2ea6b84e727c896501f3a2b5f20cb8bf94d60e332a03a`.

### Deepin graphical applications

The 2026-10-28 run uses the recipe's ordinary UID 1000 and stock Flatpak
1.16.6-1~deb13u1, portal 1.22.1+ds-1deepin1 and GTK portal 1.15.3-1.
The production graphics recipe builds Mesa 26.2.3, recipe identity
`160c9637c6b0b77853677c166f5aab2b0da1c0405979678593816a0078c493e5`,
and automatically prepares its compatible host-built Flatpak GL extension for
25.08. No SDK Mesa build or imported driver is needed here. Build receipt:
`build/installed-deepin-turnip/c67af939578c46a980bd1ddd33a897fc.log`.

Application receipts below use supervisor
`578fd6a284342ecefd13cd91f656f779c629b4cb6c1508b93d42626d56a1c16d`,
fixture `3890cc16978f4da5a6035a1c864c752a`. Paths are relative to
`build/installed-deepin-turnip/`.

| Scenario | Evidence | Result |
| --- | --- | --- |
| Flatpak Chromium 156.0.8078.47, first/repeat | `deepin-chromium-first/gui-report.json`, `deepin-chromium-repeat/gui-report.json`; jobs `f93c104679d441098dab1267a4cb30f8`, `2591b0bcecf448dfb433cf5316b25f58` | Input, pointer, download, chooser readback, Turnip/Zink WebGL pixels, normal exit and reused-profile relaunch pass |
| Chromium GPU/sandbox | `deepin-chromium-gpu.png`, job `cf5d076610d848beb12e716abda3693c` | Hardware composition and rasterization; renderers UID 1000, NoNewPrivs=1, Seccomp=2 |
| Flatpak Firefox 159.0, first/repeat | `deepin-firefox-first/gui-report.json`, `deepin-firefox-repeat/gui-report.json`; jobs `4c84d528e8b548a6a8c6cc234e1beb39`, `72f6e5071637418e8c105a55df2935d4` | Same six GUI checks pass, including file readback and ordinary close/relaunch |
| Firefox GPU/sandbox | `deepin-firefox-webrender.png`, job `69caebe819fb4244b4bf259c3b8ebaaa` | Hardware WebRender; content processes UID 1000, NoNewPrivs=1, Seccomp=2 |
| Document portal lifecycle | `deepin-portal-lifecycle-full.json`, job `c581b37c8bc24e6eb35510029dfe35b8` | Two export/read/write/revoke cycles, live-sandbox entry, clean exit, removed FUSE mount and D-Bus reactivation |
| Official VS Code 1.143.1 ARM64 DEB | `deepin-vscode-current/gui-report.json`, job `2586b05b4b504f0686e7e6ba8fc4939f` | Save/watch, PTY, reload, GDB breakpoint/locals/step/exit, GPU composition and sandbox pass |

Flatpak update job `bf4413ecc0d9463b8a0216ab320113fb` confirms both applications
current. Package and user-service receipt `1a793769b4584645be2e371b851f6085`
records versions, active PipeWire services and a clean package audit. Audible
continuity, native browser packages and whole-desktop acceptance remain separate.

### Deepin default boot

Default boot, binfmt and networkd pass in the
[Boot and IPv6 acceptance](validation-matrix.md#boot-and-ipv6-acceptance) batch. Use its
exact staged-runtime receipts.

### Deepin Docker

`build/installed-deepin-turnip/oci-services-7e4cef7b34da451eb5a91e2653becff4.json`;
stock Docker 26.1.5 service and separately packaged docker-cli. Overlay2, private
cgroups, bridge, published TCP/UDP, peer DNS/IP, outbound internet, conflicts and
released ports pass. This receipt uses supervisor
`386554772f99e0abdc64607cb438381da676577f69b0bcfa552daa4c9231cad6`.

### Deepin Podman

`build/installed-deepin-turnip/oci-services-f177e7a2f6fe434496d3b57055d98f08.json`;
stock Podman/netavark plus upstream aardvark-dns 1.12.2 built locally because this
repository omits its package. Original seccomp/cgroups, overlay, bridge, TCP/UDP, peer
DNS/IP, repeated exec, internet and cleanup pass. This receipt uses supervisor
`386554772f99e0abdc64607cb438381da676577f69b0bcfa552daa4c9231cad6`.

### Deepin QEMU

`build/installed-deepin-turnip/oci-services-90209b9194ce49119a03ece9bdba0d52.json`;
stock QEMU 8.2 TCG boots the ARM64 kernel/initrd, serial output and normal poweroff pass

### Deepin Libvirt/QEMU

`build/installed-deepin-turnip/oci-services-7af66e7aa810446eb66f779ebfdb4a6e.json`;
stock libvirt service, managed TCG boot, pause/resume, serial output, shutdown/undefine
and cleanup pass. VM networking and KVM remain untested

### Deepin machinectl

`build/installed-deepin-turnip/oci-services-4cde4f1175d241b7a13aec1c9e5fecf4.json`;
stock unit, nested Debian, private users/veth, shell/services, TCP/UDP and
normal/forced-stop cleanup across three boots pass

### Deepin BuildKit

`build/installed-deepin-turnip/oci-services-7aa8227fcb9848c1a8bccc1d979df520.json`;
upstream 0.34.1, OCI/CNI, multistage/cache/secret/export, Podman execution and cleanup
pass

### Deepin LTP core

`build/installed-deepin-turnip/oci-services-fb794ebaaa514526a13ea5dbe2d0c804.json`; all
seven pinned upstream binaries pass as UID 1000, including the ARM64 epoll_create1 ABI,
with no FAIL/BROK/TCONF

### Deepin LXC

`build/installed-deepin-turnip/oci-services-d57165f8b91c484b934633f5e8e267dd.json`; LXC
6.0.1, stock policy/seccomp, nested Debian, attach/services, mounts, bridge TCP/UDP and
normal/forced-stop cleanup across three boots pass. Unprivileged containers and external
networking remain untested

## Manjaro

### Manjaro recipe

`build/installer-manjaro-turnip.json`; official ARM64 rootfs, Weston, Turnip,
locale/timezone, ordinary user and resume preserving data. Full desktop discovery,
close/reopen and audio acceptance remain open.

Package lifecycle: `build/installed-manjaro-turnip/63f45355190d4987aaf7ecd505cdeae7.log`
verifies stock `ed` 1.20.1-1 installation, execution, reinstall, `pacman -Qkk`,
removal and absent-package confirmation, including transaction hooks. GDB 14.1
is installed from the same distribution repository for the editor fixture.
This 2026-10-28 receipt uses supervisor
`b4c46a039508a1fbae9aea49bef167ff76fdd40306cd1b235a4df42b222d49c5`.

### Manjaro Flatpak browsers

Retained fixture `4d45ea3d1bf64244aa6b814b29b83b4e` uses the supervisor above,
stock Weston 13.0.0 and the current production Turnip recipe. Mesa 26.2.3
preparation job `8a4ad026ccb84fbf83dce803a29cd52a` builds once on the host and
publishes GL.host for runtime 25.08; no additional SDK Mesa build is used.
Recipe identity: `160c9637c6b0b77853677c166f5aab2b0da1c0405979678593816a0078c493e5`.
All paths below are under `build/installed-manjaro-turnip/`.

Chromium 156.0.8078.47 passes input, pointer, download, chooser readback,
WebGL pixels and normal exit in `manjaro-chromium-first/gui-report.json`
(job `b4bf385c1eef49439841ce69cc7ebd0b`) and same-profile relaunch in
`manjaro-chromium-repeat-focused/gui-report.json`
(job `fa95024c3b664ba5843a7a67cc1527cf`). `manjaro-chromium-gpu.txt` records
hardware composition/rasterization, Zink/Turnip Adreno 840, `Sandboxed: true`
and zero GPU-process crashes. Renderer observations in
`43b9f0d87da34465b221244baf336a29.log` retain UID 1000, NNP=1 and seccomp=2.
One immediate click/type run lost its initial character
(`manjaro-chromium-repeat/gui-report.json`); its input-ordering cause remains
unresolved. The qualifying repeat confirms visible field focus before typing.

Firefox 159.0 passes the same six GUI checks in
`manjaro-firefox-first/gui-report.json` and `manjaro-firefox-repeat/gui-report.json`,
jobs `6d5199fbcaad4be899f1adb2d9610458` and `c04a04fa3f1d4f2285f21ce6782ffe5d`.
`manjaro-firefox-webrender.png` records hardware WebRender; content-process
receipt `59dc9e3fa85345b6843855c827bf7d01.log` verifies UID 1000, NNP=1 and
seccomp=2. WebGL's privacy-reduced renderer string is not used as GPU identity.

### Manjaro document portal

Stock xdg-desktop-portal 1.18.2-1 / Flatpak 1.15.6-2 pass two live-sandbox
export/read/write/revoke rounds and `flatpak enter` working-directory checks,
but the second SIGTERM teardown fails its unchanged 15-second bound:
`build/installed-manjaro-turnip/manjaro-portal-lifecycle.json`, job
`1d053dbc967640188da98e6cc9b2f264` on the native build above.
Guest GDB receipt `31851e80ca1643d6a7d3239a0f10789f.log` shows the signal handler
inside `g_main_loop_quit`, blocked on the main-context mutex already owned by
the interrupted `g_main_loop_run`. This matches upstream
[document-portal shutdown deadlock #1896](https://github.com/flatpak/xdg-desktop-portal/pull/1896).
Signal delivery occurred; changing Shroot delivery timing or forcing process
exit does not fix this packaged handler. Stock portal lifecycle is **U (upstream defect)**,
independently of browser GUI or FUSE IO acceptance.

### Manjaro VS Code

Official Microsoft ARM64 archive 1.143.1, cpptools 1.34.4 and packaged GDB 14.1
pass editor/save, external file events, integrated terminal, window reload,
breakpoint/locals/step/exit and normal editor closure in
`build/installed-manjaro-turnip/manjaro-vscode-protocol/gui-report.json` and
`manjaro-vscode-repeat/gui-report.json`, jobs `a024b979d3ff46f584809a8234a6b78f`
and `30f587db02a04f82a9b25626ab8dd0fc`. Hardware composition is enabled;
the renderer retains UID 1000, NNP=1 and seccomp=2. The archive SHA-256 is
`e84bf20d112fdb73c29b85b317ba50d5a48bea05c0711509669c8466639e9eb6`.

Acceptance remains incomplete: `manjaro-vscode-current/gui-report.json`, job
`ad567cc1960a4461ab7cbcb24ad92eaf`, times out on the fixture's DevTools WebSocket
inspection. HTTP on that exact Electron endpoint and a standalone Node WebSocket
client both succeed (`5488a83f59ac49efafc1aeba428ab3e1.log`,
`754ff318c6104804884549b675ee6fcf.log`). The two full reruns keep the original
timeout; they do not establish the first failure's cause. All use the retained
fixture and supervisor recorded above.
`b76b060b86e546fca7e4b501c0ef1cfc.log` independently passes TCP completion,
refusal, SO_ERROR, pre-connect subscriptions, edge/oneshot readiness, multiple
observers and native read/write for IPv4 and IPv6 in that session.

### Manjaro Docker

`build/installed-manjaro-turnip/oci-services-6f4a619b107d4dff981ab331894f4896.json`;
Docker 25.0.2 service, nft provider, bridge, TCP/UDP publication, peer DNS/IP, external
internet and port cleanup pass

### Manjaro Podman

`build/installed-manjaro-turnip/oci-services-348c9ab4bbf94162a1ba394236943a92.json`;
Podman 4.9.3/netavark 1.10.3, stock sandbox/cgroups, bridge, TCP/UDP publication, peer
DNS/IP, external DNS/HTTP, conflicts and port cleanup pass

### Manjaro machinectl

`build/installed-manjaro-turnip/oci-services-d82aa306a5c2430d82733f4136c0664a.json`;
three nested Debian boots, private users/veth, shell/services, TCP/UDP,
normal/forced-stop cleanup pass

### Manjaro Libvirt/QEMU

`build/installed-manjaro-turnip/oci-services-8043c944dec94dfda5da29608239481d.json`;
libvirt 10.0/QEMU 8.2.1 managed TCG boot, serial, pause/resume, shutdown and undefine
pass. `oci-services-ccd1b94e30fd4071ac13578e16f2591d.json` covers direct TCG boot; VM
networking/KVM remain untested

### Manjaro LXC

`build/installed-manjaro-turnip/oci-services-8be5cba3f33b4311993623f26cd2c472.json`;
LXC 5.0.3 stock sandbox, nested Debian boot, attach/services, mounts, bridge TCP/UDP and
three normal/forced-stop lifecycle rounds pass

### Manjaro LTP core

`build/installed-manjaro-turnip/oci-services-e8b36eb8d1994b1e9b7c5629e6df34a9.json`;
seven pinned upstream binaries built in Manjaro pass as UID 1000, with no
FAIL/BROK/TCONF

### Manjaro BuildKit

`build/installed-manjaro-turnip/oci-services-ae8be0b1b75f42388079823bf6ae351c.json`;
packaged BuildKit 0.12.2 with CNI, stock OCI sandbox, multistage/cache/secret/export,
Podman execution, cancellation and cleanup pass

## Void

### Void recipe

`build/installer-void-turnip.json`; official ARM64 glibc rootfs, Weston, Turnip,
ordinary user and resume preserving data. Full recipe discovery and desktop lifecycle
acceptance remain open

### Void default boot

`build/void-default-runit-current/oci-services-1c7cc9a615c84b24b1466ab42040ed5d.json`;
stock runit stages and default services, restart with a new service PID, and
owned-process cleanup pass

### Void Package Lifecycle

XBPS 0.60.7_1 verifies the official `hello-2.12.3_1` signature, installs,
executes, reinstalls, verifies and removes the package with its info-file hooks:
`build/installed-void-turnip/b82d69c342c54a4bb909c30f31e66783.log` and
`729b46e396c14b92973f64e8f124900e.log`. The archive and signature were downloaded
with curl into a private cache; this does not certify XBPS network downloads.

Stock downloads fail on this Android uplink: IPv6 `connect` returns
`EHOSTUNREACH`, and XBPS stops without trying the available IPv4 address.
`xbps-fetch.trace` and `1b06ec4481e846518b1b21ba72aa3382.log` retain the failure;
the same IPv6 error occurs in a native UID-2000 probe outside Shroot and in Base.
This matches the fatal-error branch in upstream
[Happy Eyeballs](https://github.com/void-linux/xbps/blob/master/lib/fetch/common.c)
and the published [XBPS download report](https://github.com/void-linux/xbps/issues/683).
The aggregate remains U, not a pass; Shroot does not rewrite native network errors
or disable IPv6 to accommodate the downloader.

### Void Turnip

`build/installed-void-turnip/94b76b52eef54088adf38867f75d3e0a.log`:
ordinary UID 1000, installed Mesa 26.2.3, Zink/Turnip Adreno 840, Wayland and
surfaceless EGL each pass three context/create/render/readback/teardown rounds.
Device identity stays stable and does not advertise a nonexistent DRM device.
`20d6d61952a247eeb187338632ab7242.log` separately verifies the stock softpipe
control, with the hardware-profile overrides removed. These runs use Release
supervisor `b0148a1e87610d7ccceece4054d25fa70205a25f398532aba009f98eea97a2a3`
and lazy SVC; they do not rebuild Mesa or certify a new APK.

### Void Docker

`build/installed-void-turnip/oci-services-1913fb5f08494da79807691ad344479c.json`; stock
runit service, Docker 29.8.0, containerd 2.3.2 and runc 1.5.2. Bridge TCP/UDP
publication, peer DNS/IP, binds/exec, external internet, conflicts and port cleanup pass

### Void Podman

`build/installed-void-turnip/oci-services-fa8ed9fad7d24073b2ff228139de092d.json`;
Podman 6.1.4, stock sandbox/cgroups, bridge, published TCP/UDP, peer DNS/IP, external
internet, conflicts and port cleanup pass

### Void QEMU

`build/installed-void-turnip/oci-services-74ca18689b564fe3ab2818bbf91dc475.json`;
packaged QEMU 11.1.2, ARM64 kernel/initramfs TCG boot, serial output and normal poweroff
pass

### Void Libvirt/QEMU

`build/installed-void-turnip/oci-services-23e06f44c5874105be2faf7ad425f559.json`;
packaged libvirt 12.8.0 under runit, managed ARM64 TCG boot, serial, pause/resume,
poweroff and undefine pass after emulator capability discovery. Cold all-architecture
discovery latency and VM networking remain open

### Void BuildKit

`build/installed-void-turnip/oci-services-7cdb929590b54750affb9ffda1b47c7d.json`;
official 0.34.1 under runit with packaged CNI, OCI sandbox,
multistage/cache/secret/export, Podman execution, cancellation and cleanup pass

### Void LXC

`build/installed-void-turnip/oci-services-c41e1d2807144c899cf9c4c3e4a5ed84.json`;
packaged LXC, nested Debian, stock seccomp, attach/services, bridge TCP/UDP and three
normal/forced-stop lifecycle rounds pass

### Void LTP core

`build/installed-void-turnip/oci-services-ec75bc0dbf564555be5d82ce0d0bdd52.json`; seven
pinned upstream binaries built with Void's compiler/libc pass as UID 1000, with no
FAIL/BROK/TCONF

### Void Flatpak browsers

`build/installed-void-turnip/void-firefox-gui/gui-report.json`,
`void-chromium-gui/gui-report.json` and the corresponding `void-firefox-reopen`
/`void-chromium-reopen` reports; original Flatpak sandboxes, keyboard/pointer, download,
chooser/readback, WebGL pixels, normal close and same-profile relaunch pass.
`build/void-flatpak-update-current.log` records successful stock update checks. Chromium
renders through ANGLE/Zink/Turnip

Release supervisor `b0148a1e87610d7ccceece4054d25fa70205a25f398532aba009f98eea97a2a3`
also passes all six GUI assertions in `void-chromium-20261029/gui-report.json`
and `void-firefox-relaunch-20261029/gui-report.json`: Chromium 156.0.8078.47
with a fresh profile and Firefox 158.0.1 with its retained profile, respectively.
Both keep their stock Flatpak sandbox. These narrower reruns do not refresh the
package-update assertion above.

### Void portal

`build/installed-void-turnip/void-portal-20261029.json`; stock portal 1.22.1_1,
libfuse 3.18.3_1 and UID 1000. Document export, live Flatpak Firefox sandbox
read/write, revocation denial and export cleanup pass. Two SIGTERM/D-Bus-reactivation
rounds and SIGKILL/helper auto-unmount all remove the document FUSE mount.
This uses the same Release supervisor as the Turnip receipt. GUI chooser/readback
is covered separately by the browser reports.

### Void VS Code

`build/installed-void-turnip/void-vscode-sandbox-fixed/gui-report.json`; VS Code
1.140.0, cpptools 1.34.4 and distribution GDB 18.1: editor/save, external file events,
integrated terminal, reload, breakpoint, step, normal close and hardware composition
pass. The live renderer has UID 1000, NoNewPrivs=1 and Seccomp=2. The exact fixture then
stops its launch, graphics and display without changing HOME; full recipe desktop
discovery remains separate

## Armtix

### Armtix package lifecycle

`build/installed-armtix-turnip/d36117e964b34fc49e9a1611bacd35a8.log` records successful
mkinitcpio reinstall and all four stock initramfs hooks; its subsequent unavailable
`hello` query is not acceptance. `0552cfd0fb3c493fabb64c8892bfd5bf.log` covers the
available repository package `tree`: install, execution, reinstall, package-file
verification and removal. No workload source build is added to the recipe

### Armtix default boot

`build/compat-contract-tests/oci-services-30d38d4aec124fc1835aa5e5995d0365.json`; stock
runit stages and default services, service start/restart/stop and owned-process cleanup
pass without startup failures

### Armtix Turnip

`build/installed-armtix-turnip/ad5739c8ae9a4ed4af5ddc703c9ca32b.log`:
ordinary UID 1001, installed Mesa 26.2.3, Zink/Turnip Adreno 840, three successful
EGL context/render/readback/teardown rounds each for Wayland and surfaceless.
The separate stock softpipe control also passes both platforms; hardware-profile
overrides are removed only for that control. Device identities and software flags
remain correct across teardown/reinitialization, without a fictitious DRM node.
Release supervisor is
`b0148a1e87610d7ccceece4054d25fa70205a25f398532aba009f98eea97a2a3`, lazy SVC.
No graphics rebuild is part of this rerun.

### Armtix Docker

`build/installed-armtix-turnip/oci-services-421f9571fce94263acf95d0231e6d441.json`;
stock runit Docker 29.9.1, containerd 2.4.2 and runc 1.5.2, named legacy cgroup
membership, default overlayfs snapshotter and package-selected nft frontend. Bridge
TCP/UDP publication, peer DNS/IP, binds/exec, external DNS/internet, conflicts and port
cleanup pass

### Armtix Podman

`build/installed-armtix-turnip/oci-services-e379d8ce233d490db3006d4754301aaa.json`;
stock sandbox/cgroups, bridge, TCP/UDP publication, peer DNS/IP, external internet and
cleanup pass

### Armtix Libvirt/QEMU

`build/installed-armtix-turnip/oci-services-bffac31b93ae4d60baf272173671f6ec.json`;
packaged runit libvirtd/virtlogd/virtlockd, managed TCG kernel/initrd boot, serial
output, pause/resume, poweroff and undefine pass. `build/armtix-qemu-current.log`
records direct TCG boot. VM networking and KVM remain untested

### Armtix BuildKit

`build/installed-armtix-turnip/oci-services-1be91d4c029440b99da82bf66c9711fb.json`;
official 0.34.1 archive and packaged CNI under runit, OCI/overlayfs sandbox,
multistage/cache/secret/export, Podman execution, cancellation and cleanup pass

### Armtix LXC

`build/installed-armtix-turnip/oci-services-f4a278d4280045e59e53506a9c601e69.json`;
packaged LXC, nested Debian, stock seccomp, attach/services, bridge TCP/UDP and three
normal/forced-stop lifecycle rounds pass

### Armtix LTP core

`build/installed-armtix-turnip/oci-services-308e2453181e40b3bd3f77da2d606a05.json`; all
seven pinned LTP 20250930 binaries built with the distribution compiler/libc pass as UID
1001, with no FAIL/BROK/TCONF, using the Release supervisor recorded above

### Armtix VS Code

`build/installed-armtix-turnip/armtix-vscode-gui/gui-report.json`; VS Code 1.140.0,
cpptools 1.34.4 and distribution GDB 18.1: editor/save, external file events, integrated
terminal, reload, breakpoint, step, normal close and hardware composition pass. The live
renderer has UID 1001, NoNewPrivs=1 and Seccomp=2. Full recipe desktop discovery and
close/reopen remain separate

### Armtix Flatpak browsers

`build/installed-armtix-turnip/armtix-firefox-gui/gui-report.json`,
`armtix-chromium-gui/gui-report.json` and the corresponding `armtix-firefox-reopen`
/`armtix-chromium-reopen` reports; Firefox 158.0.1 and Chromium 156.0.8078.47, original
sandboxes, keyboard/pointer, download, chooser/readback, WebGL pixels, normal close and
same-profile relaunch pass. Chromium uses ANGLE/Zink/Turnip. These tests explicitly
built Mesa for SDK 25.08 because the host libraries require a newer ABI; the automatic
recipe does not perform this second build. `build/armtix-flatpak-update-verified.log`
covers the stock update check

### Armtix portal

`build/installed-armtix-turnip/armtix-portal-20261029.json`; UID 1001, stock portal
1.22.1-2 and libfuse 3.18.3-1, using the same Release supervisor as the Turnip test.
Document export/read/write/revoke, two SIGTERM/reactivation rounds and a SIGKILL
helper auto-unmount round pass, with FUSE mount removal. Both browser reports
separately cover GUI chooser/readback.
`build/armtix-browser-complete-stop.log` records normal fixture cleanup without changing
HOME

## SteamOS

### SteamOS recipe/default boot

`build/steamos-recipe-hardware-current/oci-services-c668b2e77f3a4d77958f4f40811784f0.json`
; official recovery image, helper refresh and full pacman synchronization. Avahi
protocol/restart acceptance is recorded in
[Boot and IPv6 acceptance](validation-matrix.md#boot-and-ipv6-acceptance). Firewalld
REJECT, hardware boot and the package removal/verification lifecycle are not certified

`build/steamos-default-debugger-recovery/oci-services-a0de428d4e944cf7b64057646099f597.json`
uses supervisor `729b3c3b988519fa...` and a private bridge. Stock systemd reaches
`running` with zero failed units; journal/D-Bus/logind IO, binfmt registration
and execution, RTKit start/restart/stop and normal shutdown pass. Firewalld logs
unsupported `icmpx admin-prohibited` rules despite the manager's healthy final
state; this is not firewall acceptance. Routed automatic host-port publication
separately denies systemd-resolved's port 53 under Android shell permissions.

### SteamOS Docker

`build/installed-steamos/oci-services-4f10c975d3af4626afbeac11b248401b.json`; official
upstream Docker 29.1.3 binaries installed only for the lab, nftables/no userland proxy,
overlay2 and stock container seccomp/cgroups. The retained Plasma/systemd session
passes bridge TCP/UDP,
peer names, local outbound, binds/exec, conflicts, port release and 20 DNS requests plus
HTTP per container. Fixture `167fb8c48e574c369c467ecee7f3b926` uses supervisor
`1dc8f911dce6594d514f741295c65c3496e849e6f1a50935aa3d73c4802a18e5`.
This does not certify arbitrary simultaneous browser/container/build workloads.

### SteamOS machinectl

`build/installed-steamos/oci-services-b5a869d3634644c2bab9dbb98859e2fb.json`; stock
systemd-nspawn template, nested Debian, shell/services, private UID map, veth TCP/UDP,
normal poweroff, forced leader death, restart and process/veth cleanup pass in the
retained Plasma fixture `167fb8c48e574c369c467ecee7f3b926`.

### SteamOS BuildKit

`build/installed-steamos/oci-services-d8b37185e85e4a39bb2c820a80b51edc.json`; upstream
0.34.1 and CNI 1.8.0 binaries installed only for testing because enabled distro
repositories lack packages. Multistage build, cache reuse/invalidation, secret/cache
mounts, OCI export and execution, cancellation and cleanup pass in the same
Plasma fixture `167fb8c48e574c369c467ecee7f3b926`. Dedicated daemon preparation
is recorded in `oci-services-b433c200f4d14ca3b01bbbce714aed8e.json`.

### SteamOS LXC

`build/installed-steamos/oci-services-2667eb084f714c66842fd26f8e5fd12f.json`; packaged
LXC 6.0.0, nested Debian, stock common policy/seccomp, attach/services, mounts and
bridge TCP/UDP pass across three normal/forced-stop boots, including owned
process/veth/cgroup/root/bridge cleanup in fixture
`167fb8c48e574c369c467ecee7f3b926`, supervisor `1dc8f911dce6594d...`, without
Steam running. Concurrent startup with the stalled Steam Base client times out
and retains its root; see `lxc-1a14cb5504444e03bcc6a841e67a31fd.json`.
Unprivileged LXC and external networking
remain untested.

### SteamOS QEMU/LTP

`build/installed-steamos/oci-services-9d47cafce2b44d4ea3d6d4e05ca84722.json`: packaged
QEMU 9.0 TCG kernel/initrd serial boot and clean exit.
It uses fixture `167fb8c48e574c369c467ecee7f3b926`, supervisor `1dc8f911dce6594d...`.
`oci-services-835032dad55a45e9beaffb1e139e6b98.json`: all seven reviewed LTP 20250930
tests pass with the guest toolchain as UID 1000 in fixture
`167fb8c48e574c369c467ecee7f3b926`, supervisor `1dc8f911dce6594d...`.
Libvirt has no package in enabled repos
and remains untested.

### SteamOS Plasma/Flatpak

Recipe Plasma Wayland renders through KWin 6.2.5, Zink/Turnip and Mesa 26.2.3
(`build/installed-steamos/952fd9f23be946758c3fb4941e89b807.log`). The current
recipe-built Mesa prefix, profile and system EGL/Vulkan manifests agree;
`2326ad9426014c2aad1a4ea884d9c034.log` verifies the published build identity.
Stock Flatpak Chromium 155.0.8059.39 passes input, download, chooser readback,
WebGL pixels, normal close and same-profile relaunch:
`chromium-readiness-gui/gui-report.json` (supervisor `5b056e80930f8789...`) and
`chromium-debugger-recovery-relaunch/gui-report.json` (supervisor
`729b3c3b988519fa72d1a6cbc4f038ef72c918b70358c5f2491095fef88ac578`)
under that directory.
The GPU and sandbox screenshots retain hardware composition, Flatpak isolation,
PID/network namespaces and seccomp-BPF/TSYNC, without disabling sandbox flags.
Firefox 158.0.1 passes input, download, chooser readback, WebGL pixels and normal
close with a fresh profile in fixture `167fb8c48e574c369c467ecee7f3b926`,
supervisor `1dc8f911dce6594d...`:
`build/installed-steamos/firefox-ipc-recovery-second/gui-report.json`.
Same-profile relaunch passes in `firefox-debugger-recovery-relaunch/gui-report.json`
on supervisor `729b3c3b988519fa...`, including all six GUI assertions.
The earlier `167fb8c48e574c369c467ecee7f3b926` fixture passes VS Code
1.142.0 / Electron 43.7.7 editor/save, external file watching, terminal, reload,
GDB breakpoint/step/exit, renderer seccomp/NNP and enabled GPU composition:
`code-ipc-recovery/gui-report.json`. These are staged-native desktop results, not
acceptance of an APK or arbitrary concurrent container loads.
`build/installed-steamos/portal-debugger-recovery.json` passes document
grants, write/read, live revocation, private-CWD entry and two normal portal
exit/restart cycles with FUSE cleanup on supervisor `729b3c3b988519fa...`.

Steam client 1792618285 displays its library and accepts forwarded commands in
Plasma/systemd fixture `387451650488405386f4b56b61c07ace`, supervisor
`729b3c3b988519fa72d1a6cbc4f038ef72c918b70358c5f2491095fef88ac578`.
`steam-debugger-recovery-current.png` records the library;
`78cdceedee734e0796b4ffb41ad3040f.log` records the normal game URI.
DDNet 20.2.1 starts through FEX and pressure-vessel, starts its private local
UDP server and renders the Tutorial level (`ddnet-local-tutorial-ready.png`).
`ddnet-debugger-recovery-console-awake.png` records Vulkan/Adreno 840 and
PulseAudio initialization. `6e244d9bc9bb4cf3b5997a8e48a7eaa6.log` verifies
game-process retirement after its quit command and the exact Steam client's
exit by pidfd after forwarded `steam -shutdown`. These paths are under
`build/installed-steamos/`. This does not certify audible continuity,
relative-pointer capture or repeated full game launches on later native builds.

`build/steam-resume-repro/acceptance.json` records the 2026-10-29 retained
Plasma X11 workload with staged Release supervisor
`2dbd0085e20f3071d0892bcf4c63c8aa6f3e6b1cef9073e569ad2f3c65d0b482`.
Chromium creates and closes 400 tabs; a second 160-tab series runs with Steam
and DDNet's local Tutorial scene. Ten Android-injected letters are read back
exactly before/after that series. MCP command plus readback takes 67-100 ms;
browser input-event to next-frame callbacks take 2.1-8.2 ms. These are distinct
measurements, not physical-key-to-pixel latency. Game, Steam, KWin and Plasma
retain their process identities. Game-console quit removes its window, and
forwarded Steam shutdown is confirmed by the original client's pidfd.
The receipt includes CPU frequencies/temperature; `desktop.log` retains the
session output. Physical pointer capture, audible continuity and the installed
APK are not certified by this staged-native run.

The 30-case SysV suite separately passes, including nested PID metadata under
Base/lazy entry (`build/sysv-steamos-recovery/oci-services-aaac4757ff2447ee81a6bf1a2d971e57.json`).
The independent-launch FIFO suite passes in
`build/shroot-image-tests/oci-services-04e946cb49fc4b289e7b60706e359875.json`,
and the full epoll suite in `oci-services-9201181d82114b74a55b2c69853e34c3.json`
under the same directory. Ordinary FIFO payload reads and writes remain native.
Routed IPv4/IPv6 batch receives pass under Base/lazy entry in
`oci-services-741f57865a224643ba7544b15a3b7178.json` and
`oci-services-2a20d618d1944ecb93188cd1c1762a87.json` under `build/shroot-image-tests`.

PipeWire 1.6.8 and WirePlumber 0.5.14 run with the recipe's ordinary-mutex
data-loop policy. `build/installer-audio-steamos.json` passes the installed
APK's private graph, mixer volume/mute and silent playback to Android.
This validates recipe audio setup, not audible continuity or the staged native
build used by the retained Plasma fixture.

### Booted SteamOS Podman

`build/installed-steamos/oci-services-22115a94c8b545b3b262fab77765f883.json`; 5.5.2,
retained Plasma/systemd session, overlay, bridge, stock seccomp, enabled
cgroups, no `--ulimit` overrides; published TCP/UDP, peer names, local outbound,
binds/exec, conflicts, port release and internet with 20 external DNS requests per
container pass in fixture `167fb8c48e574c369c467ecee7f3b926`, supervisor
`1dc8f911dce6594d514f741295c65c3496e849e6f1a50935aa3d73c4802a18e5`.
Counter accounting follows the mediated-traffic scope documented in
[`networking.md`](../shroot/networking.md); existing native streams are not
retroactively relayed or counted.

## NixOS

The installed recipe environment uses stock activation of lab generation
`/nix/store/ncygaw319nz0yvd4dgw1ch6wala4z9hk-nixos-system-nixos-lxc-26.05.12530.c1ff116587e6`.
Its added test packages and BuildKit archive are lab prerequisites, not recipe
source-build fallbacks. Receipts below are under `build/installed-nixos-recipe/`;
each retains its staged native hashes and default lazy entry. Base is untested.

| Workload | Passing receipt ID |
| --- | --- |
| Podman bridge, publication, peer exchange and cleanup | `8541ece0180047afbf06f2773a99c631` |
| Docker bridge, publication, peer exchange and cleanup | `dd152f9dd99d45df904060336ea1ee52` |
| Libvirt managed QEMU TCG lifecycle | `b25abd6d772f4abfbc46099680728a9c` |
| Direct QEMU TCG boot and exit | `b0b355639d404cb0a2cbfe85c3f1ad1d` |
| BuildKit OCI, cache/secrets, export and cancellation | `604d1c0b404c4d44bd8cba06424921dc` |
| LXC stock policy, nested Debian, bridge and cleanup | `3ea8b5668ed6463ebb76b0368d683824` |
| machinectl with nested Debian and lifecycle | `528cffd4d6f345ad89c32efb5605c766` |
| Seven-test LTP core selection | `53b2bb12be5a43048898bd07efa37b33` |

BuildKit uses upstream 0.34.1 and Nix-provided runc 1.4.3/CNI. The control
fixture admits only the test peer's ephemeral TCP port in the stock NixOS
firewall and removes that rule afterwards. It does not disable the firewall.
The retained fixture was stopped through its normal cleanup path. These
system-workload results do not certify the full NixOS GUI or package lifecycle.

### NixOS Input

`nixos-mcp-input-20261029.json` records the production catalog Plasma Wayland
launch on installed APK build
`b1ea0359a5de5f3d33ce228bc16e7ed2fecebb7f60752281a75811dc66e05b74`.
Android key injection enters `nix`, newline and `Abc` in native Wayland Mousepad;
AT-SPI reads back the exact text. The GUI file chooser opens `/etc/os-release`
and its 594-character readback matches the guest file. Ctrl+Alt+T opens Konsole.
This is the existing guest-root catalog profile, KWin 6.6.6 and Mesa 26.2.3,
not ordinary-user browser or physical-keyboard acceptance. Initial Start clicks
did not activate the menu until pointer motion; the root profile has window
switching disabled. Full desktop acceptance remains incomplete.

### NixOS Network And Binfmt

Supervisor `5966b46cd7b42e89286f7e89cd4c7386a328751264f85172182a322af5fcaf76`
passes the stock networkd RA/SLAAC scenario in
`f737cc0f59524b8687929bb693192b7e.log`: solicitation/advertisement, address
lifetimes, gateway, wait-online, MTU/hop limit, TCP/UDP and router withdrawal.
The fixture retains the NixOS firewall and exercises its IPv6 reverse-path
filter with link-scoped addresses. This is local network acceptance, not an
external IPv6 internet check or support for every networkd setting.
`nixos-binfmt.json` and `0772e95926aa4dd4b9d5aa0381508699.log` pass stock
binfmt service registration, handler execution, stop/removal with ENOEXEC,
restart and execution again. Both receipts use lazy entry; Base is untested.

### NixOS Package Lifecycle

`nixos-package-lifecycle.json` and `0ea94354e53e4a90916a854012eab231.log`
record ordinary UID 1000 using the stock nix-daemon socket and `nix-env` in an
isolated test profile: hello 2.12.3 install, execution, `nix-store --verify-path`,
repeat install, removal and obsolete-profile-generation cleanup pass. This does
not change the system generation or certify `nixos-rebuild switch`.

### NixOS Graphics Preparation

The current module passes NixOS assertion evaluation for both `apps` and Plasma,
with native PipeWire configuration ownership and matching user/system Flatpak
triggers. Receipt `856ffd6e04c746568c632958042ec7a2` also builds the shared tools,
verifies the libc-neutral admission probe, proves that an audio-helper change
does not change the KWin derivation, and builds the software-only tools without
compiling a driver probe. The selected installed Mesa
26.2.3 output passes admission in Freedesktop 25.08.19, and ordinary-user
publication/reuse passes in `96c29678144f4a24baf5bf1637905251` and
`26fc509009124f8cab2e6f6a81887a6f`. No SDK driver build is involved.
`nixos-flatpak-trigger.json` and `3fe7dd1279494450b12dce10c23729cc.log` verify
two ordinary-user Chromium reinstalls with the generated Nix trigger directory:
cold GL.host publication, cached reuse and all standard cache triggers complete
without errors. No second Mesa build is started.
`nixos-session-profile.json` (`0876eadb4bb849478f1a109dba888054.log`) also verifies
that a clean non-PAM shell, PAM and the system helper select the same installed
trigger directory.
`nixos-profile-switch.json` (`1b8f641b14fb4f359271247195415138.log`) verifies
repeated profile sourcing and Turnip/software transitions using Nix-evaluated
profiles: owned library paths do not accumulate, caller paths survive, and
software selection clears previous Vulkan/Turnip overrides.
`build/nixos-recipe-final-unit-20261028.log` records 169 host checks with two
platform-dependent skips. These are preparation checks, not GUI acceptance;
the updated system generation, desktop and packaged-browser workflows remain
under validation.

### NixOS Document Portal

With supervisor `b4c46a039508a1fbae9aea49bef167ff76fdd40306cd1b235a4df42b222d49c5`
and stock xdg-desktop-portal 1.20.4, `nixos-portal-complete.json` passes ordinary-user
document export/read/write/revocation and entry into a live Flatpak sandbox.
SIGTERM teardown fails its unchanged 15-second bound. GDB receipt
`816d1c3febdc46739a4ce4a24079cc7b.log` shows `g_main_loop_quit` waiting for the
interrupted main-context mutex from the signal handler, matching upstream
[shutdown deadlock #1896](https://github.com/flatpak/xdg-desktop-portal/pull/1896).
The fixture recognizes Nix's wrapped executable while retaining exact D-Bus
owner, pidfd-exit and FUSE-unmount assertions. This is not a Shroot signal-loss
diagnosis or complete portal acceptance.

The lab-only control `nixos-portal-upstream.json`
(`16c96673f7c74e9fae4e036913c36c9c.log`) passes both complete grant/revoke,
exact-process exit, FUSE-unmount and D-Bus-reactivation rounds on the same
Shroot build. It uses portal 1.20.4 with upstream commits `a08dc798` and
`6ef423da` (include context adjusted for 1.20.4), linked to libfuse 3.18.2.
The source archive SHA-256 is
`f01de85717e20adf5f98aff324acd85dd73d61a5ca3834d573dcf0bd6e54a298`;
build receipt `16023bdd33d44fa2a20ee628be3a97ad` records the Nix outputs.
This private-bus control is not stock-package acceptance or an installer
source-build fallback. The stock portal cell is **U (upstream defect)**, not a pass.

### NixOS Native Firefox

`nixos-firefox-current/gui-report.json` and
`nixos-firefox-repeat/gui-report.json` pass native Firefox page rendering,
keyboard/pointer input, download, chooser readback, WebGL pixel readback and
normal close/relaunch using the same profile. The retained session is
`archive-2cdd8b52e3174ecca430666b8250ec62/session.json`, supervisor
`7af2e903d40db2768f142ad5c1611e82930385a91240d82710623b6525815ee7`,
with stock sandbox enabled and NixOS generation
`/nix/store/ppas5d9hgnzmr7v36363a6128p7k8m28-nixos-system-nixos-lxc-26.05.12530.c1ff116587e6`.
This does not certify Flatpak Firefox, hardware composition, or the browser's
VA-API video path. Firefox's privacy-normalized renderer string alone does not
identify the physical GPU.

### NixOS Flatpak Chromium

The installed Plasma/Turnip generation passes three sandboxed headless Chromium
WebGL cycles with the GPU watchdog enabled. Renderer: Zink Vulkan 1.4 on
Adreno 840 (MESA_TURNIP); each pixel readback is `[64,128,191,255]` with no GL
error or GPU-process exit. Receipt:
`build/installed-nixos-recipe/8d620520413a4b4c9556b3684621b903.log`.
The launch uses supervisor
`7af2e903d40db2768f142ad5c1611e82930385a91240d82710623b6525815ee7`
and bootstrap
`6cbff1cc10ee42f94692f9ddb5bb6b19685af6f40762d34ddfe5ebb257d510a7`.
Mesa comes from the recipe build, repackaged as GL.host without an SDK rebuild.
GUI acceptance also passes on supervisor
`edfcac0e8f18fde5e2c3f929c0634ce36625ad697003c85b8decbe4045537d26`:
`nixos-chromium-wallet-ready/gui-report.json` and
`nixos-chromium-pointer-repeat/gui-report.json` under
`build/installed-nixos-recipe/` retain keyboard/pointer input, page rendering,
download, chooser readback, WebGL pixel readback and normal close/relaunch.
`nixos-chromium-lazy-gpu.png` records hardware compositing, rasterization and
WebGL through Zink/Turnip. Receipt `6ff73d3eb70e499eb2889e544d888f00`
records the browser, GPU, network and renderer processes as UID 1000 with
NoNewPrivs=1 and Seccomp=2. The normal KDE Wallet setup is retained.
The KWin first-click-after-enter defect remains separate desktop acceptance;
these runs do not certify its pending source patch.

### NixOS Flatpak Firefox

`build/installed-nixos-recipe/nixos-flatpak-firefox-current/gui-report.json`
and `nixos-flatpak-firefox-repeat/gui-report.json` pass rendering, keyboard and
pointer input, download, chooser readback, WebGL readback and normal close/relaunch
on supervisor `edfcac0e8f18fde5e2c3f929c0634ce36625ad697003c85b8decbe4045537d26`.
`nixos-flatpak-firefox-support.png` records WebRender, not software WebRender.
Receipt `9b9e3327e1784b99b445ce8e8b51e475` retains parent/content UID 1000,
NoNewPrivs=1 and Seccomp=2. The chooser required Alt+Tab to expose it behind
the parent; this focus issue and stock portal shutdown remain open.

### NixOS VS Code

VS Code 1.119.0, Nixpkgs C/C++ 1.31.5 and GDB 17.2 pass editor/save, terminal,
external file notification, window reload, breakpoint/locals/step/exit, hardware
composition, renderer sandbox and normal editor closure in
`build/installed-nixos-recipe/nixos-vscode-nix-extension/gui-report.json` and
`nixos-vscode-nix-repeat/gui-report.json`, jobs
`2bfd003ff57b47209bca25559e61e4f8` and `d923aed17ab34ef68ac9d78ad71cfc48`, on supervisor
`edfcac0e8f18fde5e2c3f929c0634ce36625ad697003c85b8decbe4045537d26`.
The renderer retains UID 1000, NoNewPrivs=1 and Seccomp=2. The selected launcher
is Nixpkgs `vscode-with-extensions`, output
`/nix/store/57x6sjaakyfkfxwm2wpzy40cn7h1k2h0-vscode-with-extensions-1.119.0`.
Preparation receipts `53271a7eb6fd4e35acda95994e65ca80.log` and
`59001e3bd6dc4f8eab1881f193a39476.log` retain its packaged dependencies.
Use the Nixpkgs extension on NixOS: the Marketplace binary expects the FHS
loader `/lib/ld-linux-aarch64.so.1`, absent on this distribution. No global
loader alias or sandbox exception is part of this fixture.

## Shared Evidence

### nspawn Command Mode

The 2026-10-29 runs use Release supervisor
`7ae767cc254d330ef26a76057bbd822484b057cca175000d0767fb1ea2004b74`,
each distribution's stock nspawn and a nested Debian command image. All pass
three launches in each entry mode: normal exit, SIGKILL of the container leader
and relaunch.
Assertions retain private user/PID/mount/network namespaces, shifted root,
seccomp, writable/read-only binds, 20 outbound and published exchanges per
TCP/UDP protocol, and NAT-map/veth/port cleanup. Receipts are
`build/installed-ENV/oci-services-ID.json`.

| Recipe | ENV | systemd-nspawn | Shroot receipt ID | Base receipt ID |
| --- | --- | --- | --- | --- |
| debian | debian-turnip | 257.13 | `bd4c6e35ca594a6eba982f847aeb3451` | `64aac048014d4c84905e2455c8e0da97` |
| ubuntu | ubuntu-fuse | 255.4 | `b27fe81c8ffc416e8a65a4718e47c0f6` | `0544d3fd1fe34fd2bbf83b732b05e6b1` |
| fedora | fedora-turnip | 259.10 | `f8446c19e42e4b32a89b522fe62bba1b` | `ff054613f1fa49f09e809bf27de977f0` |
| arch | arch-turnip | 262.1 | `fa9ceec6da504330b2d49d9edb2f82b9` | `3e7f834eda92406ab6106c7ba5a01da5` |
| opensuse | opensuse-turnip | 261.4 | `fc90db30cee943a7bf6c81435ea1e6d6` | `fc5e93dd21ed4a23bed18548b20495d4` |
| openkylin | openkylin-turnip | 259.5 | `c2556f65e47445ac9fb8eb634df59c47` | `e58cb99cfddc417eb4b6807e5e094255` |
| deepin | deepin-turnip | 255.2 | `bdad9d62637b4303ae5181e1b29cfc9c` | `a36e56aa852b42dabbcf04d3b73691a1` |
| manjaro | manjaro-turnip | 255.3 | `ff9c958b41f14739a29c3e93dcf6384f` | `ee35eedc7b22410da661ce07e1c15b5a` |
| nixos | nixos-recipe | 260.5 | `d0e5fdf69e2948a493b14b8812a6138c` | `c79d04e42f0043f5a0f64e75e8259277` |
| steamos | steamos | 257.7 | `c984360eeaae4186a3c4c499b66bee0c` | `5b428392b98641bbba3b85e0cf16bd36` |

Native ownership/network tests pass in
`build/nspawn-local-route-20261029/final-units/oci-services-f1078c6db17b4fe5ae1e14f7ad6973ed.json`.
The separate IPv6 socket/nft/route suite passes in lazy mode
(`app/build/shroot/oci-services-62c2a31f7e864a71979d80e80f47866a.json`)
and Base (`oci-services-adb7ec45fcc3462881081a17c8ee1913.json` in the same directory).

Six interleaved performance pairs are retained in
`build/nspawn-local-route-20261029/performance-long/oci-services-a1215ea7886647a9b65ffeca51bf01ef.json`.
Against supervisor `b0148a1e...`, median lazy-entry elapsed time is 0.934x for
stat, 1.015x for open/close, 1.002x for file cycles, 1.057x for poll, 1.004x for
epoll and 1.075x for ready epoll. Files use 64 consecutive 1024-operation blocks;
readiness uses one million calls. CPU placement is unrestricted and frequency
evidence is retained; these device observations are not a fixed-frequency claim.

### Entry And Resource Contracts

Supervisor `edfcac0e8f18fde5e2c3f929c0634ce36625ad697003c85b8decbe4045537d26`
passes the complete SVC suite in
`build/svc-patch-results/oci-services-f14016ebac3b46c7bf25e076c49d098e.json`.
Coverage includes TSYNC across native waits, filtered zero-time and blocking
epoll admission, protected-entry draining during filesystem operations, mappings,
signal reentry and three stock-Go repetitions in each sandbox mode. The focused
native/Base/lazy protected race also passes in
`build/tsync-wait-20261028/oci-services-408fc51a14414b02bb034933706cfbb6.json`.
All 400 resource checks pass on the same supervisor in
`build/fence-completion-resources-20261028.json`. These receipts retain the
exact bootstrap and helper hashes.

A separate 12-run-per-mode stock Go sandbox
stress passes in
`build/go-sandbox-repeat-20261028/oci-services-dfc667e2e36f41949d57f4ee6cc9fac3.json`.
Its supervisor is `7af2e903d40db2768f142ad5c1611e82930385a91240d82710623b6525815ee7`.
These are correctness receipts, not performance measurements or browser acceptance.

Supervisor `b4c46a039508a1fbae9aea49bef167ff76fdd40306cd1b235a4df42b222d49c5`
passes the full eleven-mode epoll suite three repetitions each in lazy and Base
entry: receipts `ca421ba58e9a49008543be781457a68b` and
`38e8802e106447cc98f9a1e9c214ddec` under `build/shroot-image-tests/`.
`build/matrix-boot-helpers-20261028.log` passes all 29 host fixture tests.
Concurrent graphics preparation makes these correctness runs, not performance
measurements or per-distribution application acceptance.

Namespace-owned sysfs passes on supervisor `eecf3c123006900d4...` in lazy
and Base modes, respectively, in
`build/shroot-image-tests/oci-services-51ad9123074044d386bc76d1eeffdace.json`
and `oci-services-540570471bb047bd870496635f868394.json`: both mount APIs,
live controls, readonly policy, rename/removal, retained descriptors and cleanup.

Supervisor `3e32c8511ca8d95bd0be31aca2a1b770a42c2077c548af079dfb7c24126f969f`
passes the independent-launch FIFO suite on the installed Arch store, including protected descriptor transfer
after sender exit/receiver exec and six stock-Go runs of 512 concurrent opens,
native IO, netpoll and EOF, three each in Base and lazy mode:
`build/shroot-image-tests/oci-services-43e3913da43646b8b80a1b790b50faa8.json`.
Concurrent Mesa compilation was active; this is correctness coverage, not a
performance comparison.

The full resource suite passes on supervisor `729b3c3b988519fa...` in
`build/shroot-image-tests/debugger-recovery-resources.log`. The focused
`debugger-quiescence-final.log` also covers direct untraced barrier release and
attach/seize sharing a barrier interruption for CONT/SYSCALL/SINGLESTEP.
Debugger attach/seize, shared descriptor tables and GDB threads/IO/SHM pass in
`build/debugger-quiescence/oci-services-766cec3d504e4f5aa7ab32b92d11eb81.json`.
Its strace-detach/socket and GDB attach/fork cases require the root PID-namespace
fixture and pass separately in
`build/debugger-quiescence-pidns/oci-services-223f503cc60f4d4086b48b5a62229a5c.json`;
the first aggregate report is not an all-pass receipt. Accelerated-entry contracts pass
in `build/cooperative-runtime/oci-services-4ae3e286b5784075af87d1e50b3e0c66.json`.
These are runtime regressions, not substitutes for per-distribution GUI receipts.

### Foreign proc paths

`build/shroot-image-tests/oci-services-8235d74eca3649279082939ce32f74f1.json`
and `oci-services-575f2279872048858533271b8c51d2fe.json` cover Alpine musl in
lazy and Base modes; `oci-services-770fb29e581a499db62eb10033cff0a2.json` covers
Debian glibc in lazy mode. Foreign root/cwd readlink, traversal into private
mounts, retained descriptors, nested PID views and denied credentials pass.
The bad-output-pointer assertion uses the raw AArch64 fstatfs syscall;
ordinary descriptor metadata still passes through each distribution's libc.

### clone3 contract

`build/shroot-image-tests/oci-services-142abe4139bd4065a4d1285b6b225118.json`; native
control plus Base/lazy fork, thread/TLS, vfork, TIDs/pidfd, signal masks/dispositions
and seccomp; versioned read-only arguments at a guard page, admission errors, guest
logical PID selection, cgroup placement, protected callers and rollback. This does not
replace per-distribution application acceptance

### Debian/Arch recipe setup

`build/installer-debian-turnip.json`, `build/installer-arch-turnip.json`,
`build/installer-arch-turnip-render-only.log`; rendering/application acceptance remains
open

### Debian/Arch libvirt

`build/installed-debian-turnip/libvirt-report.json`,
`build/installed-arch-turnip/libvirt-report.json` and adjacent `libvirt-serial.log`;
stock daemon/device policy, explicit Cortex-A72 for the ARM64 kernel; VM networking and
KVM untested
