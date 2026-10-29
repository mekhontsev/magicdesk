# Distribution Init Checks

These checks run native ARM64 userspace under actual Android shell UID 2000.
They use the production Shroot runtime, private API filesystems and unchanged
distribution init binaries. They are not installer recipes or hardware boots.

## Contracts

- `test_os_boot.py`: packaged systemd default target, no degraded units,
  journal write/readback, system D-Bus request, logind restart and clean exit.
  Its observer supplements the default target rather than replacing it. Generator
  failures and failed unit results across startup and shutdown are rejected
  independently of the manager's final running state. A healthy startup alone
  does not certify service teardown.
- `test_systemd.py`: a focused target with system/user managers, sandboxed
  oneshot, notify, retained FD-store restart, socket and timer activation.
  The additional properties below exercise credentials and restricted proc.
- `test_os_boot.py --init runit`: stock PID 1 and boot stages, default runsvdir
  plus a private lifecycle probe. Start/restart/stop must change and retire
  the actual service PIDs. The runit `check` hook acknowledges a new startup
  generation; second-resolution status timestamps alone are insufficient.
  Default service status is collected separately, not treated as a guarantee
  that hardware services are healthy.
- `test_os_boot.py --init openrc`: stock Alpine PID 1 with explicit container
  configuration, default runlevel services and restart/stop checks.

Every launch has a failure deadline. Service/log events establish readiness;
elapsed time never does. Runit/OpenRC cleanup cancels only the exact registered
launch and checks that the launch has retired. Systemd exits its guest manager,
not Android. Reports retain the native binary hashes and command output.

```sh
python native/guest-exec-lab/test_os_boot.py --build app/build/shroot --store STORE
python native/guest-exec-lab/test_systemd.py --build app/build/shroot --store STORE \
  --property StateDirectory=md-init-probe --property SetCredential=md-probe:hello \
  --property ProtectProc=invisible --property ProcSubset=pid
python native/guest-exec-lab/test_os_boot.py --build app/build/shroot \
  --init runit --store STORE
```

## Extended Matrix

The [distribution matrix](validation-matrix.md#installation-and-init) owns
current per-OS boot and service status, including SteamOS, Ubuntu, Arch and
NixOS. Its [evidence ledger](distribution-evidence.md) records package versions,
native hashes, launch policy and remaining failures. Do not maintain a second
status table here or equate a focused service target with default-target boot.

`--drop-capability sys_module` is an explicit fixture option, not an implicit
workaround. Record it when used; the test must not mask or whitelist a failed
`systemd-modules-load` unit. Dropping `CAP_SYS_MODULE` is guest container policy,
not permission to modify Android's kernel. Runit supervisor readiness likewise
does not certify every hardware probe or package hook. These recipes provide
userspace, not a replacement Android kernel; initramfs and kernel-image
maintenance need separate acceptance.

The retained `installed_desktop.py` control/GUI fixture keeps the launch's
preconfigured `eth0` addresses and routes through a private
`/run/systemd/network/00-md-fixture-uplink.network`. It uses networkd's standard
`KeepConfiguration=static` with DHCP and router solicitation disabled on that
uplink; Shroot supplies neither a DHCP server nor an RA router there. Container
links remain independently configurable. This fixture-only profile neither
modifies `/etc` nor applies to `test_os_boot.py`, whose default-target acceptance
still checks the distribution's own network configuration. Routed host-port
publication retains Android permission checks, including privileged port 53.

The stock-boot fixtures may install prerequisites beyond the selected
`install_linux.sh` profile. Session `dbus-run-session`, NixOS activation and a
booted system bus are different requirements; recipe completion alone does not
certify a persistent init session. Use `test_installer_setup.py` for the guest
setup payload, separately from boot tests. Booted Docker/Podman fixtures default
to the packaged service. Diagnostic daemon profiles and their network assertions
are described in [network fixtures](README.md#network-fixtures).

Passing boot is not full support for every daemon's resource policy. RTKit uses
per-guest-UID `RLIMIT_NPROC` accounting with its daemon protections enabled.
`test_rtkit.py` checks chroot, UID drop,
limit 3, both watchdog threads and a live D-Bus response. Fedora's stock unit
also starts and restarts under `test_os_boot.py --rtkit` (systemd 259.9).
Real-time scheduling still returns the host's `EPERM` on this shell executor;
successful daemon startup is not proof of RT priority. Raising the host `nofile`
hard limit remains denied. The recipe's PipeWire session uses ordinary scheduling
without requiring RTKit.

Astra and Pardus are outside this init matrix for now. Astra's existing fixture
contains extracted glibc/shell packages, without package configuration or
systemd; its smoke result is not a boot pass.

## Source Preparation

Use publishers' images and archives, not another runtime's modified rootfs.
Image tags are mutable; record the resolved ARM64 manifest and installed
manager version. Package setup retains each distribution's security policy.
DNS is explicit fixture configuration, never a runtime default.

| Source | Immutable input used |
| --- | --- |
| `debian:trixie-slim` | ARM64 manifest `sha256:da496358bd6934d2bd6a563a33176a2e50eff5490c54b4ac6fb051b69fef4071` |
| `registry.fedoraproject.org/fedora:44` | ARM64 manifest `sha256:7495f71c9fa2928de4f4d9deee7e3f3346cce50b59ecb6b40737e5d4aabf071a` |
| `alpine:3.23` | ARM64 manifest `sha256:ae959799024a09da597284763c417a6e820e020fad0634c5267cf4f125e682bf` |
| `opensuse/tumbleweed:latest` | ARM64 manifest `sha256:0a34722fc12ca7e27f8be0d0134e4149ca0e661a2dfaa1795d296c3eea852890` |
| `openkylin/openkylin:latest` | ARM64 manifest `sha256:c32b0f1dd748c924d982c115a619e0adf97dc4369db0ac021f575cf2244effea` |
| `linuxdeepin/deepin:crimson-arm64` | Manifest `sha256:85cb7e3ee7d4c4eaf3bc71b28de61c08331c98c3c34128518c1506a6195c32df` |
| [Manjaro ARM rootfs](https://github.com/manjaro-arm/rootfs/releases/tag/20261012) | Archive SHA256 `1ae540a80e21e1ca55e0576df9618fd2865cf4596b8204d20780f225aca5164a` |
| [Void rootfs](https://repo-default.voidlinux.org/live/20250202/) | `void-aarch64-ROOTFS-20250202.tar.xz`, SHA256 `01a30f17ae06d4d5b322cd579ca971bc479e02cc284ec1e5a4255bea6bac3ce6` |
| [Armtix rootfs](https://armtix.artixlinux.org/images/) | `armtix-runit-20260921.tar.xz`, SHA256 `8e1de041c64cb8bd8c1766d8f9048e75544de1c162769c02a626ce484aad4465` |

`fetch_oci_fixture.py` fetches registry layers. `rootfs_oci_fixture.py` preserves
archive contents and converts unsupported outer compression before importing
through the same inode importer. No source is executed on the host.

Install `systemd`, D-Bus, util-linux (`script`, `unshare`, `setpriv`) and bash in
minimal systemd images; optional syscall traces also require strace. openSUSE
names its bus package `dbus-1`; openKylin/Deepin use `dbus` and `systemd-sysv`.
Fedora additionally needs `util-linux-script`; `util-linux` alone omits `script`.
Use its `dbus-broker` package for the default system bus. Installing only
`dbus-daemon` does not select the `dbus.service` alias; an explicitly selected
daemon provider must also be enabled through its packaged unit.
Manjaro uses its packaged `archlinuxarm`, `manjaro` and `manjaro-arm` keyrings
before the ordinary pacman update. Close preparation-owned GnuPG agents.
Void's rootfs already supplies the runit and console tools. Armtix's source
rootfs omits `libutempter`, needed by its `script`; install it through pacman.
Armtix's published pacman configuration uses `SigLevel=Never`; this fixture does
not introduce that setting or claim signature verification of its packages.
Archive checksums were compared with publishers' HTTPS metadata.

For Alpine's stock OpenRC boot check, install `openrc`, `openrc-init`,
`busybox-openrc`, `util-linux` and `python3`. The `openrc` package alone does not
provide PID 1 or the packaged `syslog`/`crond` service scripts. The focused
`test_openrc.py` fixture supplies its own services and therefore does not verify
those package prerequisites.
