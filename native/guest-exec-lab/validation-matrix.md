# Shroot Distribution Validation Matrix

This is the per-distribution acceptance ledger, not a supported-applications list
or a claim that all tests are automated. Keep every distribution printed by
`sh scripts/install_linux.sh --list` in every table. Results do not transfer
between distributions sharing a package manager, libc or init system.

Last reconciled: 2026-10-29. Current device evidence uses NX809J, Android 16,
Linux 6.12.23, 4 KiB pages and Android shell UID 2000. This does not cover the
minimum supported kernel 5.15, other firmware or Android root execution.

- [Reading Results](#reading-results)
- [Installation And Init](#installation-and-init)
- [Containers And Virtual Machines](#containers-and-virtual-machines)
- [Packaged Browsers And Portals](#packaged-browsers-and-portals)
- [Desktop And Development](#desktop-and-development)
- [Added System Workloads](#added-system-workloads)
- [Evidence And Open Work](#evidence-and-open-work)

## Reading Results

Each paired cell is **Shroot (lazy SVC) / Shroot Base**, with independent
results, not a ratio. `P/N` means Shroot passed and Base was not run;
`N/N` means neither mode has a qualifying result for that scenario.

| Code | Meaning |
| --- | --- |
| - | Outside the selected test scope for this distribution; no compatibility claim |
| N | Not run; includes fixtures that still need implementation |
| P | Passed the named scenario in the recorded run, not necessarily the latest build |
| I | Incomplete: some assertions passed, but acceptance remains open |
| F | Observed failure not attributed to a confirmed upstream defect; retain its reproduction until a qualifying rerun passes |
| U | Observed failure attributed to a confirmed upstream defect; not a pass or a resolved limitation |
| B | Blocked by a named prerequisite; not a pass or proof of an application defect |
| H | Historical coverage only; current acceptance and exact entry-mode evidence need reconciliation |
| NA | Not applicable, with an explicit distribution/package/hardware reason |

An N in the Base position means there is no qualifying Base receipt here; it
does not assert that nobody has ever run that workflow. H is not a pass in that
entry mode. Do not infer NA from a distribution's init choice or an unattempted
package install. Record unavailable distribution packages separately from a
Shroot implementation failure; do not silently substitute another userspace.

The current scope selects Snap browsers on Ubuntu and Steam/game workflows on
SteamOS only. Other distributions are not required to run those workloads to
complete their selected matrix. A single `-` excludes both entry modes from
that scope; it is not NA (unsupported/not applicable), N (pending coverage),
or a pass. Preserve existing failure evidence when changing scope. All other
N cells remain uncovered unless their scope is explicitly changed.

Use U only with an exact package version, failing receipt and diagnostic evidence:
a published upstream defect/fix, or an isolated reproduction outside Shroot
matching the upstream source. A similar symptom alone remains F.
Keep unaffected checks separate; an upstream fix or lab-patched pass does not
promote the installed package to P without a qualifying rerun. Most current
application receipts exercise Shroot only, so Base often remains N; shared code
and unit-test coverage do not establish an unrun Base application result.

Missing workload packages or dependencies (for example Podman or aardvark-dns)
must not add source-build fallbacks to `install_linux.sh`. Lab-only source builds
may test Shroot compatibility, but their receipts must identify the upstream
version, provenance and locally built components. Such a pass does not establish
availability from that distribution's repositories or the installer. The
explicit Turnip build remains a separate graphics recipe, not a general license
to compile missing applications during installation.

P applies only to the native hashes, package versions and configuration in its
receipt. Staged native builds do not certify the installed APK. Rerun affected
cells after common runtime changes; do not promote older receipts to the new
build or use aggregate unit-test success as application acceptance.

## Installation And Init

Recipe setup covers the maintained installer and package/graphics preparation,
not Start discovery or GUI acceptance. PID 1 uses the distribution's own manager:
systemd, Alpine OpenRC, or Void/Armtix runit. Focused service tests and default
boot remain different assertions. Turnip requires rendered/read-back pixels,
not just a completed Mesa build or successful device enumeration.

Cell order: **Shroot (lazy SVC) / Base**; N = not run.

| Recipe ID | Recipe setup | Package lifecycle | PID 1 boot | Service lifecycle | Turnip rendering |
| --- | --- | --- | --- | --- | --- |
| debian | P/N | P/N | P/N | P/N | P/N |
| ubuntu | P/N | P/N | I/N | I/N | P/N |
| alpine | P/N | P/N | P/N | P/N | P/N |
| fedora | P/N | P/N | P/N | P/N | P/N |
| arch | P/N | P/N | P/N | P/N | P/N |
| opensuse | P/N | P/N | P/N | P/N | P/N |
| openkylin | P/N | P/N | P/N | P/N | P/N |
| deepin | P/N | P/N | P/N | P/N | P/N |
| manjaro | P/N | P/N | P/N | P/N | P/N |
| void | P/N | U/N | P/N | P/N | P/N |
| armtix | P/N | P/N | P/N | P/N | P/N |
| nixos | P/N | P/N | P/N | P/N | P/N |
| steamos | P/N | I/N | I/N | I/N | P/N |

Package lifecycle means install, update/reinstall, verification and removal,
including transaction hooks. A zero package-manager exit with failed hooks is
not a pass. SteamOS default boot passes on a private bridge but retains the
routed host-port restriction and unsupported firewalld rules documented in its
[evidence](distribution-evidence.md#steamos-recipedefault-boot).
Focused control-target tests do not certify a healthy default target.
Ubuntu reaches a healthy default target, but its packaged LXC and Podman
shutdown units return errors with empty container lists; see the
[Ubuntu boot evidence](distribution-evidence.md#ubuntu-default-boot).
See [init contracts and limits](init-matrix.md).

### Boot And IPv6 Acceptance

The 2026-10-23 staged-runtime run used each installed recipe environment, without
an init replacement. All local IPv6 suites passed. The networkd scenario checks
router solicitation/advertisement, SLAAC lifetimes, gateway installation,
wait-online, effective MTU/hop limit, TCP/UDP traffic and router withdrawal.
The binfmt scenario checks the stock boot unit, execution and service restart.
These do not certify every networkd option or external internet access.

Receipts below are `oci-services-ID.json`: boot receipts are under
`build/ipv6-binfmt-boot/`, socket receipts under `build/shroot-image-tests/`.
All runs use default lazy entry; Base remains untested in this batch.

| Recipe | Boot / binfmt / networkd | Boot receipt ID | Local IPv6 receipt ID (PASS) |
| --- | --- | --- | --- |
| debian | P / P / P | `575399e858884d1dbe1874242cae6a4c` | `f5225204674149c89d412b6345ce8e6d` |
| ubuntu | I / P / P | `ab02a8bece5d46de807eb0347bc60696` | `5288fb359718469ea76033506b3cffa6` |
| alpine | P / N / NA (OpenRC) | `f1baebe8d6554b46a002af7387a0e1ac` | `d0b867759a994e8fbc60692e5285a09e` |
| fedora | P / P / P | `2dae19fc1aa245cf8f7add26663e40fa` | `5299bce94b364f1e8aa8304d7c8638a3` |
| arch | P / P / P | `bc915121291e4f34ab51401a0269f49d` | `d69c5902538b433dba0bba1074fa618b` |
| opensuse | P / P / P | `02e150c483294a1da3bd1a317873ed57` | `32f9692fc7db4dda83a0d406a2758e67` |
| openkylin | P / P / P | `df13520ab5d741d99a59464f095608a5` | `1714ef9068d045a2a256d80be63d0859` |
| deepin | P / P / P | `40f5b5045a224a71b27437364a80cc91` | `d4f59c0e2d1d43bcbd4d22b924001072` |
| manjaro | P / P / P | `f7be656fa9da4364915e575d3af7b30f` | `28c08a0520b148459d0052b90d569b69` |
| void | P / N / NA (runit) | `c9d13ce4051c47eb8157aecc1a0f29b2` | `d7281d10f2024a239cd902720e544e31` |
| armtix | P / N / NA (runit) | `30d38d4aec124fc1835aa5e5995d0365` (*) | `2473bd1c2149472daa21744e1cbdca78` |
| nixos | P / P / P | Boot: `dc870a2ce87042b7b8a9b04be968c8da` under `build/nixos-boot-final-20261024/`; binfmt/networkd: [installed receipts](distribution-evidence.md#nixos-network-and-binfmt) | N (separate socket suite) |
| steamos | F / N / N | `fa6234c4dadc46878677e15ed535fb97` | `0aebd5e239ad43f6b1f1a62cbf5e8249` |

Fedora and openSUSE required their separately packaged networkd, installed in
the lab from their own repositories before the passing rerun. This does not
replace openSUSE's Wicked or add a test-only dependency to the user recipe.
SteamOS firewalld still submits unsupported UDP/ICMP REJECT rules.
(*) Armtix's 2026-10-24 boot receipt is under `build/compat-contract-tests/`:
stock runit stages, service start/restart/stop and launch cleanup pass without
startup failures. Its root remount uses the documented persistent-store contract.
Current focused service receipts under `build/boot-service-repair/`:
`a90f1ba5666a4fe987381e65d4bb3f37` verifies SteamOS Avahi mDNS over IPv4/IPv6
and three restart cycles. `0553d599fba34bf2ba48e0262789fb0d` verifies Ubuntu
default-target startup, journal/D-Bus/logind IO and three CUPS IPP restart
cycles within the unchanged five-second readiness bound. These receipts do
not certify SteamOS firewalld or unrelated Ubuntu container-service shutdown.

### NixOS Recipe And Initial-Image Probes

The NixOS recipe and the separate 2026-10-23 probe use the official ARM64 LXC rootfs
`nixos-image-lxc-26.05.12530.c1ff116587e6-aarch64-linux.tar.xz` from
[Hydra build 350465271](https://hydra.nixos.org/build/350465271), SHA-256
`333b1d718db3d99147ca2b5fb6d533b4aabaac98420d48e9a5369799433352ae`.
The imported image and writable probe instance are separate; no foreign
userspace, altered activation script or disabled service substitutes for NixOS.

The console-only recipe check covers `nixos-rebuild boot`, stock activation and
repeat `--resume` with the staged runtime. Passing receipt:
`build/nixos-installer/oci-services-1670e8248a534650937601c95b4bf8c9.json`.
After activation, `shroot login` resolves the selected profile through
`/run/current-system`, finds Python 3.13.15 and retains `sandbox = true`.
The recipe prepares private API mounts for activation and builders together.
The current installer uses command-local `sandbox=false` and
`filter-syscalls=false` for its trusted Nix build, without changing the installed
Nix sandbox policy. Its job default is four cores and one concurrent derivation.
The Plasma/Turnip recipe completed on 2026-10-24, including stock activation of
`/nix/store/g83p188wxrar0nj3c9bsvhp3g3siv70l-nixos-system-nixos-lxc-26.05.12530.c1ff116587e6`.
The installer log is `/data/local/tmp/md-nixos-resume-fast-20261024.log` on the
device. The installed configuration retains `sandbox = true`, four cores and
one concurrent derivation.

This generation's default-target boot, journal/D-Bus/logind IO and restart,
and shutdown pass with the preconfigured recipe network and current native
build: `build/nixos-boot-final-20261024/oci-services-dc870a2ce87042b7b8a9b04be968c8da.json`.
Systemd 260.5 reports `running`, zero failed units and no failed shutdown unmounts.
This boot uses the native helpers built for the installed APK, staged by the
fixture; it does not certify the untested full package lifecycle or `switch`.

Both Plasma catalog entries launch on HDMI through the installed APK
`3a597e4898f89cf8cfb535754b45adfa2040455896d097dba3a2bb2d5a7b3a4d`.
The launcher smoke receipt is `build/installer-graphics-nixos-recipe.json`;
its early splash captures alone are not desktop-readiness evidence. Follow-up
`build/nixos-final-x11.json` and `build/nixos-final-wayland.json`, with matching
PNGs, record the loaded panel and desktop and KWin 6.6.6 OpenGL composition
through Zink/Turnip on Adreno 840, Mesa 26.2.3. Konsole opens through Ctrl+Alt+T;
X11 keyboard input executes `pwd`. Close/reopen retires the owned guest launches.
Full desktop acceptance remains incomplete; individual application results
are recorded separately in the evidence ledger.
The [NixOS input receipt](distribution-evidence.md#nixos-input) covers native
Wayland text entry and GUI file readback, with its remaining pointer/shortcut scope.
The separate boot results below belong to the initial image.

| Scenario | Shroot / Base | Evidence |
| --- | --- | --- |
| Store registration and forced sandboxed derivation rebuild | P/N | `build/ipv6-binfmt-boot/oci-services-08f9b5cbe6b3425ebeee4acec42b8dc7.json`; stock `nix-build --check --option sandbox true`, local inputs, substituters disabled, output readback |
| Stock activation in prepared init namespace | P/P | Boot receipts below; activation returns 0, private dev/proc/run from the common init fixture, disk-backed ramfs for `/run/keys` |
| Stock `/sbin/init`, default target and shutdown | P/P | Boot receipts below; systemd 260.5 reports running and zero failed units, D-Bus/journal/logind IO and restart pass, no failed shutdown unmounts |
| Stock dhcpcd unit, restart, resolvconf and libc DNS | P/P | Boot receipts below; real lease, address/default route, DNS lookup and bidirectional UDP with the service's non-root identity, capabilities, NNP and seccomp retained |

Boot receipts from 2026-10-24 under `build/nixos-boot/`:
Shroot `oci-services-90ef04bbc73f46cfaa3cc72f3ca94c28.json`,
Base `oci-services-31e153adba11486b92e05a4cc8bf6228.json`.
`test_nixos_boot.py` supplies a DHCP/DNS peer over a private guest veth and
namespace; it does not alter the stock dhcpcd unit or Android networking.
Firewall, network-local-commands and resolvconf start with the default target;
this does not certify every firewall rule or external DHCP connectivity.
Separate packaged dhcpcd lease/renewal, DNS hook options, route installation and
bidirectional UDP pass in both entry modes: IPv4 receipt
`build/shroot-image-tests/oci-services-ce87c905651b44e794bb3ccd0b5944c6.json`,
IPv6 receipt `build/shroot-image-tests/oci-services-c804b0fb8aa14e90a5eb28aff0d39779.json`.

The recipe keeps addressing and DNS under Shroot's launch configuration; its
preconfigured routed and bridge uplinks do not provide a DHCP server. Routed
wildcard UDP port 68 also requests an Android listener, subject to host
privileged-port policy; private
guest networking does not require that publication. Guest-owned resolvconf and
a launch-owned read-only DNS mount must not manage the same file.
Full `nixos-rebuild switch` and the wider desktop matrix remain unverified.
The ordinary-user package lifecycle uses the stock daemon and an isolated Nix
profile; see [NixOS package evidence](distribution-evidence.md#nixos-package-lifecycle).
The installed-generation container and system workloads
are recorded in the [NixOS evidence](distribution-evidence.md#nixos).
Initial-image boot retains warnings for unavailable
BPF/cgroup firewalling. The recipe-generation boot also reports missing
`accept_source_route`/`promote_secondaries` default sysctls and duplicate packaged
D-Bus service names; these do not fail a unit in this run.
Logical ramfs is disk-backed compatibility, not memory-only/non-swappable
storage; `/run/keys` therefore has no such secret-storage guarantee.

## Containers And Virtual Machines

Docker and Podman require bridge networking, published TCP/UDP, local outbound
traffic, peer IP/name resolution, binds, exec, port conflicts and released ports.
Record external DNS/internet separately from deterministic local peers. Keep
Docker nftables/no-userland-proxy and its older proxy profile distinct in the
receipt; a pass in one does not cover the other.

Libvirt requires a real managed QEMU guest, not just daemon readiness or
`virsh list`: define, start, serial output, pause/resume, shutdown and undefine.
QEMU's column is the direct TCG kernel/initrd boot and clean-exit scenario.
VM networking, firmware boot, disk persistence and KVM require separate checks;
neither a TCG pass nor guest `/dev/kvm` absence establishes host KVM availability.

Cell order: **Shroot (lazy SVC) / Base**; N = not run.

| Recipe ID | Docker | Podman | Libvirt + QEMU | QEMU TCG boot |
| --- | --- | --- | --- | --- |
| debian | P/N | P/N | P/N | P/N |
| ubuntu | P/N | P/N | P/N | P/N |
| alpine | P/N | P/N | P/N | P/N |
| fedora | P/N | P/N | P/N | P/N |
| arch | P/N | P/N | P/N | P/N |
| opensuse | P/N | P/N | P/N | P/N |
| openkylin | P/N | P/N | P/N | P/N |
| deepin | P/N | P/N | P/N | P/N |
| manjaro | P/N | P/N | P/N | P/N |
| void | P/N | P/N | P/N | P/N |
| armtix | P/N | P/N | P/N | P/N |
| nixos | P/N | P/N | P/N | P/N |
| steamos | P/N | P/N | B/N | P/N |

### Local IPv6 Container Acceptance

The 2026-10-24 Arch reruns use stock container packages, their normal seccomp
policy and cgroups, with default Shroot entry. Both cover bridge networking,
TCP/UDP publication, peer IP/name exchange, local outbound traffic, conflicting
ports and port release. They do not request public-internet access.

| Engine | Profile and native observer | Receipt |
| --- | --- | --- |
| Docker 29.9.2 | nftables, no userland proxy, cgroup v2, published `::1` | `build/shroot-image-tests/oci-services-da9ad945e8ff4b0a83719c1f0650a960.json` |
| Podman 6.1.4 / Netavark 2.1.0 | overlay, stock seccomp/cgroups, wildcard IPv6 publication observed through Android's existing local global address | `build/podman-results/oci-services-ca516b0fb73343f68ac530d1031c7391.json` |

The observer runs as native Termux UID 10601, outside Shroot. These runs change
neither Android addresses nor its routes. Podman's tested address is not `::1`;
see the [fixture contract](README.md) for the separate observer/publication
options. Docker still logs missing `memory.events`: the unimplemented memory
controller is not advertised, and the test does not certify memory limits/OOM
notifications.

### IPv6 Uplink Coverage

The following 2026-10-23 checks use the staged native runtime under Android UID
2000; they supplement, rather than replace, the booted-service matrix above.
The ordinary VPN rejects IPv6. An authorized temporary host policy rule selected
the existing cellular route only for `2606:4700:4700::1111/128` and the test UIDs.
The container publication check additionally assigned the outer guest's IPv6
address to Android loopback. Both rules and the address were removed and absence
verified after the tests; the VPN policy was not changed. This is lab setup,
not a root dependency or automatic VPN bypass in Shroot.

| Recipe | IPv6 uplink checks | Evidence |
| --- | --- | --- |
| alpine | PASS: routed and bridge, UDP DNS, TCP DNS, verified HTTPS, remote IPv6 source | `build/ipv6-binfmt-boot/oci-services-4dbeeb4aceac4edbb3cd934febf41f12.json` |
| arch | PASS: routed and bridge uplink; local ICMPv6, address/route lifecycle, dual-stack sockets, nftables and publication | `build/shroot-image-tests/oci-services-04b7301938c348ad85458cbbacedea6f.json` |
| ubuntu | PASS: routed and bridge, UDP DNS, TCP DNS, verified HTTPS, remote IPv6 source | `build/ipv6-binfmt-boot/oci-services-4dbeeb4aceac4edbb3cd934febf41f12.json` |
| arch / Docker | PASS: nftables, no userland proxy, VFS, cgroup v2; IPv6 TCP/UDP host-address publication, peer IP/names, DNS/HTTPS from both containers, conflicts and released ports | `build/shroot-image-tests/oci-services-f55084f23c3e4036a1ddff141dd841cc.json` |
| arch / Podman | PASS: overlay, stock seccomp/cgroups; IPv6 TCP/UDP host-address publication, named peers, DNS/HTTPS from both containers, conflicts and released ports | `build/podman-results/oci-services-b8cd9497a9e445488e38c39842e2b2ca.json` |

The publication client is native Termux UID 10601, outside Shroot. Its tested
address is `fd6d:6473::64`, not `::1`. These receipts do not certify public
inbound cellular reachability, every distribution's networkd boot, arbitrary
IPv6 destinations, or a different VPN's behavior.

## Packaged Browsers And Portals

Every browser cell includes its packaging runtime: Flatpak/bubblewrap or
snapd/snap-confine, install/update, ordinary-user launch, original sandbox,
input, page rendering, download and normal close/relaunch. Record hardware
composition/WebGL and crashes, not only a renderer string. Portal acceptance
also needs file selection/readback, document grants, FUSE lifetime and cleanup.
Record the packaging backend for each portal result; a Flatpak result does not
certify Snap. Snap's documented degraded mode is not AppArmor enforcement.
Portal lifecycle coverage distinguishes graceful daemon exit, auto-unmount after
daemon death and whole-service restart. The expanded Fedora receipt covers all
three; older graceful-exit receipts do not imply coverage of the other two.

Cell order: **Shroot (lazy SVC) / Base**; N = not run, U = confirmed upstream defect.
Snap is selected on Ubuntu only; `-` marks other distributions outside that scope.

| Recipe ID | Flatpak Chromium | Flatpak Firefox | Snap Chromium | Snap Firefox | Document portal |
| --- | --- | --- | --- | --- | --- |
| debian | P/N | P/N | - | - | U/N |
| ubuntu | N/N | N/N | P/N | P/N | P/N |
| alpine | P/N | P/N | NA/NA | NA/NA | U/N |
| fedora | P/N | P/N | - | - | P/N |
| arch | P/N | P/N | - | - | P/N |
| opensuse | P/N | P/N | - | - | P/N |
| openkylin | P/N | P/N | - | - | P/N |
| deepin | P/N | P/N | - | - | P/N |
| manjaro | P/N | P/N | - | - | U/N |
| void | P/N | P/N | - | - | P/N |
| armtix | P/N | P/N | - | - | P/N |
| nixos | P/N | P/N | - | - | U/N |
| steamos | P/N | P/N | - | - | P/N |

## Desktop And Development

Desktop acceptance includes recipe launch/discovery, input, ordinary clients,
close/reopen and owned-process cleanup. VS Code includes editor/save, external
file changes, integrated terminal, debugger, reload, sandbox and GPU checks.
Distribution-packaged browsers are separate from their Flatpak/Snap versions.
Audio needs audible continuity under load; counters alone are insufficient.
Gamepads need real buttons, axes and reconnect. Steam login is not gameplay.
Steam/game acceptance is selected on SteamOS only; `-` elsewhere does not
assert that games cannot run there. Audio and gamepad coverage remain separate.

Cell order: **Shroot (lazy SVC) / Base**; N = not run.

| Recipe ID | Desktop | Native Chromium | Native Firefox | VS Code | Audio | Gamepad | Steam/game |
| --- | --- | --- | --- | --- | --- | --- | --- |
| debian | I/N | N/N | N/N | P/N | N/N | N/N | - |
| ubuntu | I/N | N/N | N/N | P/N | N/N | N/N | - |
| alpine | I/N | P/N | P/N | U/N | N/N | N/N | - |
| fedora | U/N | N/N | N/N | P/N | N/N | N/N | - |
| arch | I/N | N/N | N/N | U/N | N/N | N/N | - |
| opensuse | I/N | N/N | N/N | P/N | N/N | N/N | - |
| openkylin | I/N | N/N | N/N | P/N | N/N | N/N | - |
| deepin | I/N | N/N | N/N | P/N | N/N | N/N | - |
| manjaro | P/N | F/N | P/N | I/N | I/N | N/N | - |
| void | I/N | N/N | N/N | P/N | N/N | N/N | - |
| armtix | I/N | N/N | N/N | P/N | N/N | N/N | - |
| nixos | I/N | N/N | I/N | P/N | N/N | N/N | - |
| steamos | I/N | N/N | N/N | P/N | H/N | H/N | I/N |

Preserve separate application/package versions in the receipt.
Historical Fedora GNOME devkit/llvmpipe coverage does not certify GDM or hardware
composition. Current Fedora Weston coverage is listed below. See [desktop workflows](desktop-workflows.md) and
[stock-libc acceptance](svc-rewriting.md#desktop-and-container-acceptance).

## Added System Workloads

Implement and validate in this order: **nspawn -> BuildKit -> LXC**. Run the
small LTP selection alongside common runtime changes. A package installation
alone does not satisfy a workload. The machinectl column identifies the outer
OS owning machined and the stock nspawn unit; record the nested OS separately.

Cell order: **Shroot (lazy SVC) / Base**; N = not run.

| Recipe ID | nspawn command | machinectl boot/lifecycle | BuildKit | LXC | LTP core |
| --- | --- | --- | --- | --- | --- |
| debian | P/P | P/N | P/N | P/N | P/N |
| ubuntu | P/P | P/N | P/N | P/N | P/N |
| alpine | NA/NA | NA/NA | P/N | P/N | P/N |
| fedora | P/P | P/N | P/N | P/N | P/N |
| arch | P/P | P/N | P/N | P/N | P/N |
| opensuse | P/P | P/N | P/N | P/N | P/N |
| openkylin | P/P | P/P | P/N | P/N | P/P |
| deepin | P/P | P/N | P/N | P/N | P/N |
| manjaro | P/P | P/N | P/N | P/N | P/N |
| void | N/N | N/N | P/N | P/N | P/N |
| armtix | N/N | N/N | P/N | P/N | P/N |
| nixos | P/P | P/N | P/N | P/N | P/N |
| steamos | P/P | P/N | P/N | P/N | P/N |

The command-mode results have separate [per-distribution receipts](distribution-evidence.md#nspawn-command-mode).

### Workload Contracts

- [nspawn](https://www.freedesktop.org/software/systemd/man/latest/systemd-nspawn.html):
  boot a nested distribution, use machinectl shell/service lifecycle, check
  private PID/mount/user namespaces and `/dev`/`/proc`, bind permissions, veth
  bridge networking and TCP/UDP publication. Keep command-only nspawn separate
  from the machinectl/systemd service workflow; do not replace OpenRC/runit
  merely to mark a distribution as passed.
- [BuildKit](https://github.com/moby/buildkit): build a multistage image, run
  parallel steps, verify cache reuse and invalidation, cache/secret mounts,
  exported-image execution, cancellation and cleanup. Keep OCI and containerd
  workers and guest-root/rootless configurations as separate subcases.
- [LXC](https://linuxcontainers.org/lxc/introduction/): create/start a container,
  attach/exec, verify credentials, mounts, `/dev`/`/proc`, cgroup interactions,
  original seccomp policy, bridge connectivity, restart, shutdown and destroy.
  Privileged/unprivileged refers to guest credentials, never Android elevation.
- For all three, exercise abnormal termination and repeat the same scenario.
  Verify no owned processes, mounts, sockets or reservations remain. Record
  exact missing operations; do not replace bridge with host networking, disable
  application protections, or report permissive cgroups as resource enforcement.

### Permanent LTP Selection

Start with these seven upstream test binaries, not the complete `syscalls`
suite. Pin and review the upstream source and harness before the first run;
record the revision and compiled hashes. Re-review before updating that pin.
Upstream supports [individual test execution](https://linux-test-project.readthedocs.io/en/latest/users/quick_start.html#running-single-tests).

Current reviewed pin: LTP `20250930`, commit
`d2550ffbbcfe163212cd7e9c132db65ae0fa06ed`. The official
`ltp-full-20250930.tar.xz` SHA-256 is
`048fa4d69ddbe8a94aa15da9bdc85713ab07a0abbc3de2b8bdd9757644aef1e4`.
Build only `lib/libltp.a` and the seven targets below with the distribution's
compiler. `test_booted_ltp.py` runs each once as the specified ordinary guest
user in a disposable working directory, records source/library/binary hashes,
and preserves every upstream status. Its 45-second outer failure bound kills
only that test's process group; it does not enable stress-loop options.

| Test | Initial contract |
| --- | --- |
| `dup01` | Duplicated descriptor refers to the same inode |
| `dup201` | Invalid dup2 descriptors return EBADF |
| `openat01` | Relative/absolute paths, directory FDs and error returns |
| `pipe01` | Pipe data write/readback and descriptor cleanup |
| `epoll_create1_01` | Native epoll creation, CLOEXEC policy and close on the ARM64 syscall ABI |
| `sigprocmask01` | Block, observe pending signal, unblock and deliver |
| `getuid01` | Basic guest UID syscall result; not full credential isolation |

Run bounded single iterations in test-only stores as an ordinary guest user,
with disposable temporary directories and inherited host limits. Do not expose
Android block devices, writable host proc/sys/sysfs or unrelated directories.
Exclude filesystem formatting, host-wide sysctls, time changes, module loading,
reboots and unbounded resource exhaustion. Mount/xattr and deeper
namespace/credential suites need separately reviewed fixtures; existing Shroot
tests retain that coverage and are not replaced by this small selection.

Retain every upstream subtest and status: TCONF/skip is uncovered, TBROK is a
broken run, and neither is a pass. Keep warnings and cleanup failures visible.
Compare failures with the same binaries and fixtures on a real Linux control
(a disposable VM is acceptable); native Android and Shroot Base alone are not
oracles for complete Linux behavior.

## Evidence And Open Work

This ledger retains current acceptance evidence and unresolved blockers, not
the history of repaired defects. Replace a failed status after a qualifying
rerun and remove its resolved explanation. Regression coverage belongs in the
tests. Local report paths identify evidence, not durable public downloads.

Detailed receipts are grouped in [Distribution acceptance evidence](distribution-evidence.md):

| Distribution | Evidence |
| --- | --- |
| Debian | [Debian scenarios](distribution-evidence.md#debian) |
| Ubuntu | [Ubuntu scenarios](distribution-evidence.md#ubuntu) |
| Alpine | [Alpine scenarios](distribution-evidence.md#alpine) |
| Fedora | [Fedora scenarios](distribution-evidence.md#fedora) |
| Arch | [Arch scenarios](distribution-evidence.md#arch) |
| openSUSE | [openSUSE scenarios](distribution-evidence.md#opensuse) |
| openKylin | [openKylin scenarios](distribution-evidence.md#openkylin) |
| Deepin | [Deepin scenarios](distribution-evidence.md#deepin) |
| Manjaro | [Manjaro scenarios](distribution-evidence.md#manjaro) |
| Void | [Void scenarios](distribution-evidence.md#void) |
| Armtix | [Armtix scenarios](distribution-evidence.md#armtix) |
| SteamOS | [SteamOS scenarios](distribution-evidence.md#steamos) |
| NixOS | [NixOS scenarios](distribution-evidence.md#nixos) and [recipe scope](#nixos-recipe-and-initial-image-probes) |
| Shared checks | [Cross-distribution fixtures](distribution-evidence.md#shared-evidence) |

Machinectl evidence does not cover nspawn command-mode bridge/publication or
external DNS. Nested shutdown reports `/tmp: not mounted` for a locked inherited
mount; retain that diagnostic without weakening mount restrictions.

Test contracts and reproduction instructions are in the [init matrix](init-matrix.md),
[desktop workflows](desktop-workflows.md) and
[SVC acceptance](svc-rewriting.md#desktop-and-container-acceptance). Reconcile
exact receipts and scope before replacing H with P.

Every receipt must identify recipe/source, installer configuration, package
versions, APK/native hashes, device/kernel/page size, Android/guest identities,
entry mode, command, assertions and cleanup. Keep backend choices and prerequisites
as subcases, not a single green result for an entire OS. Never publish retained
`session.json` files: they contain control tokens.
