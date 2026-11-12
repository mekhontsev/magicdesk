# Shroot Fixture Guide

This directory contains native probes, prepared Linux workloads and device
runners. Production implementation belongs to [native/shroot](../shroot/README.md).
The [user guide](../../docs/shroot.md) describes supported workflows and limits;
fixtures do not define a separate runtime or installation interface.

- [Choose A Suite](#choose-a-suite)
- [Evidence Rules](#evidence-rules)
- [Kernel Profiles](#kernel-profiles)
- [Installer Acceptance](#installer-acceptance)
- [Network Fixtures](#network-fixtures)
- [Process And Resource Fixtures](#process-and-resource-fixtures)
- [Graphical Acceptance](#graphical-acceptance)
- [Named Environment Workflows](#named-environment-workflows)
- [Prepared Runtime Fixtures](#prepared-runtime-fixtures)

## Choose A Suite

| Contract | Guide |
| --- | --- |
| Per-recipe application results, open failures and planned system workloads | [Distribution validation matrix](validation-matrix.md) |
| Bubblewrap, namespaces, credentials and application filters | [Namespace fixtures](namespaces.md) |
| SteamOS, X11/Wayland desktops, audio and GPU | [Desktop workflows](desktop-workflows.md) |
| OCI images, mounts, filesystem operations and watches | [Filesystem workflows](filesystem-workflows.md) |
| GDB, strace and matched performance measurements | [Developer workflows](developer-workflows.md) |
| Entry contracts, stock musl/glibc workloads and native/PRoot comparisons | [Cooperative entry](cooperative-entry.md), [runtime contract](../shroot/cooperative-entry.md) |
| Init systems and distribution boot | [Init matrix](init-matrix.md) |
| Browser workload and sandbox evidence | [Browser fixtures](browser/README.md) |
| Kernel seccomp/ptrace controls | [Sandbox controls](sandbox-research.md) |
| Shared regression under real Android application and shell identities | [Host execution identities](host-execution.md) |
| Lazy machine-code entry, signal boundaries and Go stress | [Lazy SVC entry](svc-rewriting.md) |
| Native namespace admission and restricted brokers | [Isolation controls](chromium-isolation.md) |
| Descriptor custody, publication and resource teardown | [Resource verification](../shroot/resource-tests.md) |

## Evidence Rules

- Guest assertions are shared across executor identities. App-UID launchers
  establish the real Zygote context; they do not define a separate Shroot ABI
  or weaker expected results. `k3s_fixture.py` and `test_proc_inspection.py`
  run unchanged under either executor. Proc inspection covers dumpable and
  protected tasks, absolute and dirfd-relative reads, and stable process start
  identity. `--busy` exercises an active task; `--fifo` exercises a task parked
  in FIFO open while its observer has not yet opened the peer.
- Record the APK/helper identity, image source, executor UID and firmware/kernel.
  A staged helper validates that helper, not APK packaging or installer delivery.
- Keep native controls, Shroot component tests and real-application workflows
  distinct. An exit code alone does not prove a rendered page, audible output,
  container connectivity or complete browser isolation.
- Wait for the exact operation's completion and cleanup. A deadline is a failure
  bound, not evidence of readiness. Retain bounded logs and reproducible inputs
  in test artifacts rather than adding each run to this guide.
- Run destructive or failure-injection cases in disposable stores. Preserve the
  user's active sessions and use the agreed display for graphical checks.
- Unsupported hardware, unavailable native authority and an implementation
  failure are different results. Do not weaken assertions or silently disable a
  client's sandbox to obtain a pass.

## Kernel Profiles

Use a native build configured with `-DMAGICDESK_GUEST_FIXTURES=ON`. The
`linux-5.15` profile removes newer host capabilities from the supervisor and its
workers, without putting a denial filter around the guest or disabling lazy SVC.
It needs neither root nor a phone reboot. Existing Linux sessions are unchanged.

```sh
python native/guest-exec-lab/test_resources.py BUILD --case kernel-baseline
python native/guest-exec-lab/test_resources.py BUILD --case fs-results --kernel-profile linux-5.15
python native/guest-exec-lab/test_watch_runtime.py BUILD --kernel-profile linux-5.15
python native/guest-exec-lab/test_systemd.py --build BUILD --store STORE --kernel-profile linux-5.15 --machine-entry
SHROOT_TEST_KERNEL_PROFILE=linux-5.15 python native/guest-exec-lab/test_clone3.py --build BUILD --name ENVIRONMENT
```

`kernel-baseline` always compares both profiles and both syscall entry modes.
Other commands select one profile; omit it or select `native` for the ordinary
host. The environment form applies to runners using the shared `Suite` launcher,
including prepared-distribution workflows. Stock native controls stay native.
For a standalone host component, use the device-side
`libmagicdesk_guest_kernel_baseline.so --host NATIVE-FIXTURE ...`; do not wrap a
whole Shroot launch in that command, because its descendants inherit the filter.

Reports retain the real kernel and verified profile separately. The runner
checks fixture support and records each observed profile activation; guest output
remains usable as JSON or text. A missing profile is a failure, not a silent
modern-kernel pass. Release binaries contain no profile hooks. See the
[scope and limits](../shroot/kernel-compatibility.md#host-kernel-profiles): this
tests missing host APIs, not old-kernel scheduling, SELinux or all native guest
semantics. It does not replace testing on a real 5.15 device.

## Installer Acceptance

A disposable-library recipe test is not end-to-end installer acceptance. Before
a release, also exercise the ordinary user path with the packaged APK:

1. Open [MagicDesk Console](../../docs/getting-started.md#open-the-android-shell-console)
   and download the maintained installer.
2. Use the default environment library, without a test-only
   `MAGICDESK_GUEST_HOME` override or staged helper bundle.
3. Exercise interactive configuration, DNS repair/cancellation before image
   work, and package-setup retry in the same environment after a resolver
   failure. Use a new independent environment name and retain an incompatible
   cached image to verify format-aware import without deleting existing stores.
4. Refresh Apps, find the installed application and whole-desktop entries in
   Start, and launch them without manually creating shortcuts.
5. Verify the selected protocol, usable content/input and applicable audio/GPU
   services. Close and relaunch through the same discovered entry.
6. Exercise `--resume` without replacing user data or silently changing DNS.

Custom-library tests remain useful for isolation. Their entries are not
automatically registered in the Android default-library catalog. Test
`--gui apps` and a whole-desktop profile separately; installing application
packages does not verify a desktop-session launcher.

`test_installer.py` records its explicit `--locale` and `--timezone` selections
(defaults: `en_US.UTF-8` and `UTC`). These controlled fixture defaults are not
the installer's Android-locale default. Test that default separately through
interactive setup, including the keep-on-resume behavior.

### Recipe Setup And Graphics

`test_installer_setup.py BUILD STORE [--dns ADDRESS]` runs the actual guest
payload emitted by `install_linux.sh --print-system-setup` in a disposable store.
It records the payload hash, uses the production offline resolver owner when DNS
is explicitly selected, and retains the recipe's package/keyring cleanup.
It defaults to `--gui none`; `--gui xfce|weston|apps` and `--fonts basic`
exercise package setup for GUI profiles, not rendered GUI acceptance or an init boot.
`--extra-packages` exercises the installer's extra-package argument. The GUI
fixture requires `pidof` and `timeout`; minimal Fedora needs
`--extra-packages procps-ng` for its component-readiness check.
`test_recipe_graphics.py BUILD STORE` then reads those installed desktop entries
and executes their commands through the production routed graphical recipe using
the selected native build. It checks X11/Wayland Mousepad input and saved-file
readback, Xfce/Weston pixels, and retained whole-desktop viewers on an isolated
display. Use `--entry` to select a case and `--lease` to retain an existing phone
wake lease. Plasma entries are `plasma-x11` and `plasma-wayland`; use
`--user steamos --home /home/steamos` for the SteamOS recipe. The store must be idle and disposable; the fixture stops its own
launches and removes its display. This does not verify named-catalog registration,
audio output or Turnip. Keep at most one long foreground package-setup request
running beside GUI observation so MCP's request workers remain available.
`test_arch_runtime.py BUILD STORE --install-package` separately checks pacman's
unmodified sandbox, Landlock denials, synchronization, package installation,
reinstallation, file verification and removal of a fixture-installed package.

### Retained Sessions And Booted Workloads

Retained recipe-installed sessions can run `test_booted_containers.py --session
build/installed-NAME/session.json --engine podman --internet` (or `--engine
docker`). Start a non-graphical session with `installed_desktop.py start --name
NAME --headless --build BUILD --network-profile routed`; add `--init openrc`
for Alpine. The container fixture's external port observer needs Android
loopback access; containers still create their own private bridges. This uses the same
init/control owner without acquiring a display, GPU, HOME role or user desktop;
it requires the guest's Python, D-Bus and init packages, not Weston. Use `run
--root` for commands and always `stop --name NAME` afterward. Default-target
boot remains a separate `test_os_boot.py` assertion. Docker defaults to its stock
service; alternate daemon profiles are
explicit diagnostic cases. Docker's `--image-archive /guest/path/image.tar`
loads a prepared image when it is absent, instead of pulling from a registry;
the requested image must be present after loading. This separates local container
acceptance from download availability without weakening the network checks.
`--trace-runtime` traces the fixture-owned rootless Docker runtime for diagnosis;
`--trace-network` attaches to its daemon after set-ID startup and image
preparation, and observes network, namespace and capability calls. Neither
is a performance run. `test_booted_nspawn.py --session SESSION --machine
MACHINE` uses an existing `/var/lib/machines/MACHINE`, while
`test_booted_lxc.py --session SESSION --archive ROOTFS.tar.gz` creates and removes
its own container from a systemd/D-Bus/iproute2/Python rootfs. The latter two
share readiness and TCP/UDP assertions and run three boot/service/network/stop
cycles, including forced termination. Retain original application sandbox
policy; successful command-only startup is not boot acceptance.
Add `--command` to the nspawn fixture to check command mode with an existing
machine image: private users/PID/mount/network namespaces, read-only and writable
binds, outbound and published TCP/UDP, normal exit, killed leader, and removal
of nftables publications and veth interfaces across three launches.

Graphical `installed_desktop.py` sessions normally hold a 30-minute awake lease on their
own virtual display, renewed by `run`, `attach` and `capture`. A phone-screen
lease does not keep that display active. Release a separately owned lease before
starting the fixture; it never replaces another owner. After lease expiry,
stop/restart the fixture before qualifying GUI results. `stop` removes the owned
display and its lease; headless sessions acquire neither. `start --offscreen`
retains a graphical output without an Android display or viewer and never
acquires or renews an awake lease. The caller must keep the device awake when
qualifying offscreen graphics or timing results.
`start --statistics` enables the native operation-cost and entry counters for
either session type; `stop` retains their shutdown output in `boot.log`.
Leave it off for ordinary timing comparisons and use `--diagnostics` separately
when a full transition trace is needed.

## Network Fixtures

### Launch Profiles

`test_launch_network.py --store STORE --build BUILD` checks the common launch
network profile under UID 2000: run/exec/login/low-level entrypoints, offline
loopback, isolated DNS views, concurrent bridge TCP/UDP publications, outgoing
TCP/UDP, a real libc DNS query, conflicts, cancellation and abnormal exit.
The store needs Python 3 and an installed resolver; `--cli-only` needs only a
shell and cat (also tested on Alpine). `--native-tests` includes the resource,
mount and filesystem-worker suites, including partial port-reservation rollback.
Traffic assertions use local native peers, independently of internet stability.
`test_os_boot.py --network bridge --dns IP` applies the same profile to stock
init boot; its default remains routed. Reports identify the native binaries.

### K3s

`test_k3s.py [BUILD] ENVIRONMENT K3S_ARM64 --machine-entry` uses an installed
recipe environment with Python 3, util-linux and a verified upstream k3s binary.
The receipt records its SHA-256, version, runtime hashes and entry/kernel profile.
Container images are imported or pulled by stock containerd. Repeatable
`--images ARCHIVE.tar` supplies verified airgap or workload archives through
each node's normal image inbox; receipts record their hashes. Missing images
still require registry access. No distribution packages are built or installed
by the fixture.

K3s is in scope for every recipe OS. Use `--executor app|shell` to select the
host identity; omitted `BUILD` tests the installed APK. `--store PATH` selects
an already accessible store instead of resolving the recipe name. This also
supports the [borrowed-store handoff](host-execution.md#borrowing-an-installed-environment)
without a second rootfs. A result applies only to its recorded OS and executor.

The default profile uses stock VXLAN; `--backend host-gw` selects the alternative.
`test_k3s.py` always uses one selected distribution. For a mixed cluster run
`test_k3s_cluster.py [BUILD] SERVER_ENVIRONMENT AGENT_ENVIRONMENT K3S_ARM64`.
It additionally accepts `--agent-store PATH` for an explicit agent store.
Both entrypoints share `k3s_fixture.py` for workloads, lifecycle and cleanup.
The mixed fixture attaches the existing agent store read-only and gives it
private configuration and runtime mounts. It does not install packages or copy
the rootfs; receipts identify both distributions and the staged runtime.
Each node retains distribution inputs under `/var`, including certificate stores
referenced from `/etc`. Kubelet, CNI, Rancher and log data have separate empty
mounts. Missing mountpoints are prepared before read-only attachment and only
those empty directories are removed afterwards; installed data is retained.
Use `test_k3s.py --nodes 2` for two nodes within one distribution; the mixed
entrypoint always uses two nodes. Server and agent have independent network,
PID, mount, UTS and cgroup namespaces, connected by a veth underlay on the same device.
Both profiles retain stock Traefik, ServiceLB and NetworkPolicy.
The fixture checks Node Ready, CoreDNS, kubelet root filesystem capacity, cross-pod
DNS/ClusterIP HTTP, policy deny/allow/update/delete, Deployment scale/rolling
update, local-path PVC retention, and ingress through the Service and node address.
With two nodes, DNS and HTTP must cross nodes, and the agent restarts independently.
Server restart must retain pod UID and Service address; ordinary deletion uses
the default termination grace. After restart, the kubelet's own inventory must
reacquire the pods before executing inside them; retained API readiness alone
does not satisfy this check.
Private PID/mount namespaces retire remaining shims and runtime data; logs and
the structured result are downloaded before deleting the fixture directory.
Use `--base` for fallback entry or `--kernel-profile linux-5.15` for host-capability
fallbacks. Neither that profile nor this scenario certifies a physical 5.15 kernel
or networking between separate devices.

`test_k3s_boot.py BUILD ENVIRONMENT K3S_ARM64 INSTALL_SH --init systemd|openrc`
tests one installed recipe through the production `magicdesk-init` helper.
Each boot uses a private bridge, so distribution services can bind guest ports
such as the DNS stub's 53 without publishing privileged Android listeners.
Supply a verified binary and its unmodified upstream installation script; their
hashes are recorded. The installer runs inside the first boot and installs the
stock enabled service. API readiness and the init service's completed activation
are checked separately; observing an activating service never restarts it.
Repeatable `--images ARCHIVE.tar` supplies verified
upstream airgap bundles or workload OCI archives through k3s's normal agent
image directory. Images absent from those archives still require registry access.
Three complete Shroot launches cover initial boot,
orderly poweroff/restart, then exact-supervisor SIGKILL and recovery. Each boot
checks Node Ready, CoreDNS, pod DNS/Service IO and a synced local-path PVC write;
recovery also compares node, namespace, pod, volume, Service and credential
identity. Killing a supervisor is distinct from a k3s-only restart or power loss.
The fixture refuses an existing k3s installation and preserves pre-existing
kubelet/CNI state. Preparation and cleanup both run inside the production init
session, including the distribution's own mounts of `/etc` and `/var`. After
test launches stop, a cleanup boot retires the stock service and restores owned
files. Interrupted launches are stopped by their exact registry identity.
It accepts the same entry/kernel profiles, `--executor app|shell`, and `--store`
for an existing store accessible to that executor, including a borrowed store.
Native control fixtures use the executor's verified executable mapping; the
guest installation does not switch Android identity. Its phase logs and receipts
are separate from the broader multi-node workload checks above.

`test_network_vxlan.py BUILD ENVIRONMENT` checks the packet/control contract
independently of k3s and registry availability. It covers an independent wire
peer, shared UDP ports and VNI isolation, MTU and outer filtering, static IPv4/IPv6
neighbors/FDB, routed `onlink` traffic, TCP streams, UDP fragmentation and teardown.
It accepts the same entry and kernel-profile options.

`test_ipset.py BUILD ENVIRONMENT` separately exercises stock ipset and iptables-nft,
IPv4/IPv6 prefix membership, exclusions, referenced-set replacement, large
restore/dump, transactional rejection and metadata-only NFLOG delivery. It accepts
the same entry and kernel-profile options. `probe_nft_runtime.py` covers TCP
tracking deletion/expiry without transport loss and namespace-scoped timeout
controls. Neither test substitutes for the real k3s policy controller.

### Container Publication And IPv6

The Docker and Podman network probes allocate paired TCP/UDP ports through an
observer outside Shroot, then check publication, collision rejection, and port
release. They do not assume fixed test ports are unused. Docker diagnostic
profiles use a private empty daemon config so their explicit flags do not collide
with `/etc/docker/daemon.json` written by the installer. This does not validate
that system daemon's service-unit startup. Host-side observer ownership checks:
`python native/guest-exec-lab/test_network_observer.py`.
The Docker probe selects a Python image automatically for `--exercise-ports`;
an explicit `--image` must also provide Python for that scenario.
`--profile nftables --no-userland-proxy` requires Docker 29+ and checks the
same local matrix as `--profile routed`, which retains the older proxy profile.
Both engine probes keep internet checks opt-in (`--internet`); local TCP/UDP,
container-name DNS, conflicts and cleanup must pass independently. A token-scoped
HTTP endpoint on the native host interface also verifies outbound request and
response routing without a public server. This catches reverse-NAT/raw-chain
ordering failures independently of external connectivity. Podman's optional
`--host-limits` is diagnostic: normal acceptance uses the common logical
NOFILE/NPROC contract without explicit container limit overrides. The physical
Android limits still apply.

Both accept `--executor shell|app` through the shared fixture executor.
`--exercise-ports` also runs the same writable-layer checks in both containers:
rename, hardlink identity/link counts, open-after-unlink, a nested exec with an
inherited seccomp filter, 128 create/rename/remove cycles and 4096 open/read/close
operations. Temporary files are removed within each container. These are
correctness checks, not isolated filesystem benchmarks. A launcher-level lazy
completion count alone does not establish acceleration inside its containers.

`--exercise-ports --ipv6` selects a dual-stack container bridge and checks IPv6
TCP/UDP publication and named peers. `--ipv6-host ADDRESS` selects an address
assigned to both the outer Shroot namespace and Android for publication and
native observation; the default is `::1`. Podman's `--ipv6-publish-host ::`
separates a guest wildcard binding from the Android observer address, which
need not be assigned to a guest interface. Both choices are retained in the
receipt. Test a non-loopback address separately: Netavark's IPv6 localhost
forwarding is not equivalent to its ordinary host-address path. Its nft backend
does not provide IPv6 loopback SNAT; a loopback source cannot enter a non-loopback
IPv6 interface. Do not relax Shroot ingress checks to make that scenario pass.
The fixtures neither add Android addresses nor modify Android routing.

`--internet-ipv6` additionally requires UDP DNS, TCP DNS and certificate-verified
HTTPS from both containers to `2606:4700:4700::1111`. All sockets explicitly use
AF_INET6, without IPv4 fallback; the HTTPS response must identify an IPv6 source.
This opt-in public test needs a working Android IPv6 uplink, not just addresses
inside Shroot. A VPN can reject IPv6 even when cellular service provides it.
Any temporary host-routing exception belongs to an explicitly authorized lab
setup, scoped to the test destination/UID and removed after the run; it is not a
recipe prerequisite or a production VPN bypass.
`test_network_ipv6.py --build BUILD --name NAME --internet` uses the same public
assertions natively and in guest `routed`/`bridge` modes, in addition to its local
address/route, ICMPv6, dual-stack socket, nftables and port-publication checks.
Omit `--internet` for deterministic local coverage.

### Multicast, DHCP And NixOS Boot

`test_network_multicast.py BUILD STORE` checks IPv4/IPv6 membership, reuse,
namespace fanout, leave, loopback, hops, filtering and unicast responses with
both entry modes. `test_network_dhcp.py BUILD STORE [--ipv6]` runs packaged
dhcpcd against a fixture server in another guest namespace. It requires a lease,
renewal, DNS hook options, routes and bidirectional traffic using the leased
address. `--python PATH --path PATH` selects packaged tools in non-FHS guests
such as NixOS; it does not substitute the client or change Android networking.
`test_nixos_boot.py --build BUILD --store STORE --system CLOSURE --python PYTHON`
boots an idle, disposable NixOS store through its stock activation and
`/sbin/init`, using the common private init mounts. A private guest DHCP/DNS
peer supplies a lease to the unmodified dhcpcd unit. Checks cover its non-root
identity, capabilities, no-new-privileges and seccomp, address/default route,
stock resolvconf, real libc DNS lookup, UDP traffic, service restart and
shutdown. `--network preconfigured` instead boots a recipe generation with
its launch-owned uplink, retaining the common systemd service and shutdown
assertions without expecting a DHCP unit. `--entry base` repeats the boot without lazy entry. The fixture
server is not a production DHCP service or an Android uplink test; neither
Android addresses nor routes, DNS or privileged ports are changed.

### Routing, Firewall And Performance

`probe_podman_runtime.py BUILD STORE --rootless-user USER --exercise-ports
--internet` runs stock Podman as an existing disposable guest user with
subuid/subgid ranges and the distribution's UID-map/network helper packages.
Only private API mounts are prepared as guest root. Containers retain normal
seccomp and cgroup settings. Rootless checks retain the installed Podman's
default network; `--network` selects an explicit diagnostic alternative.
tests cover external TCP/UDP ports, outbound IO, conflicts and teardown.
Named-peer DNS checks apply to the separate bridge mode, not isolated rootless
networks. The fixture ends its Podman pause process with `podman system migrate`.

`probe_docker_runtime.py BUILD STORE --rootless-user USER --storage-driver overlay2
--cgroup v2 --exercise-ports --internet` starts the distribution's
`dockerd-rootless.sh` (or Alpine's `dockerd-rootless`) through stock RootlessKit, slirp4netns and UID-map helpers
as an ordinary guest user. It requires rootless and seccomp security options,
then checks two containers, published TCP/UDP, peer IP/name lookup, outbound IO,
port conflicts and teardown. The named diagnostic `rootless*` profiles without
`--rootless-user` only enter a guest user namespace; they do not substitute for
this ordinary-user integration test.

`test_booted_containers.py --session SESSION --engine podman|docker --rootless`
runs the same probes through an existing ordinary-user init-session controller.
It retains that user's identity and the booted system mounts; it neither starts
a rootful daemon nor prepares another sysfs/cgroup view. Without `--rootless`,
the driver tests the booted rootful service.

`test_lwip_context` checks namespace-local protocol state and memory limits;
the `test_task_resources` target includes `test_network_tun.c` for TUN/TAP ABI,
queue ownership, virtio-net layout/checksum/segmentation, ARP ioctls,
IPv4/IPv6 TCP/UDP, fragment reassembly and raw ICMP.

`probe_packet_runtime.py` exercises real packet applications in an installed
recipe environment (Debian by default), using the staged native build as shell
UID 2000. TUN uses unmodified ARM64 tun2socks and GOST executables; place them
in `ASSETS` as `tun2socks-linux-arm64` and `gost`. TAP requires the distribution's
`qemu-system-aarch64`, a trusted Alpine ARM64 kernel/initramfs pair with
virtio-net modules, and the static `packet_vm` test target:

```sh
python native/guest-exec-lab/probe_packet_runtime.py --kind tun --assets ASSETS
cmake --build build/shroot-image-tests --target packet_vm
python native/guest-exec-lab/probe_packet_runtime.py --kind tap \
  --kernel vmlinuz-virt --initramfs initramfs-virt
python native/guest-exec-lab/probe_packet_runtime.py --kind vpn --installed
python native/guest-exec-lab/probe_packet_runtime.py --kind wireguard
python native/guest-exec-lab/probe_packet_runtime.py --kind openconnect
python native/guest-exec-lab/probe_packet_runtime.py --kind ipsec --network bridge
python native/guest-exec-lab/probe_packet_runtime.py --kind tailscale --network bridge --assets ASSETS
```

Use `--build BUILD`, `--environment NAME` and `--base` as needed. `--installed`
uses the APK runtime; TAP still takes its test executable from `--build`. The report
records native and input hashes. No package is installed by the fixture, and
no host interface is configured. TUN separates the proxy and client namespaces,
tests IPv4/IPv6 TCP/UDP with payload verification and half-close, and rejects a
route bypass before/after tun2socks runs. TAP tests the same transports in both
directions against a real VM kernel, retaining QEMU's automatic TAP feature
negotiation. VPN requires packaged OpenVPN/OpenSSL/iproute2: an ephemeral TLS
peer tests both address families, DNS hooks, route isolation and restoration.
`--container-image IMAGE` runs the OpenVPN consumer in a pre-pulled Python
Podman image, using `--container-cache` and `--container-runroot` for its test
storage. Container seccomp remains enabled. WireGuard requires packaged
wireguard-go/wireguard-tools and covers encrypted bulk traffic, SIGKILL and restart.
OpenConnect requires openconnect/ocserv and OpenSSL: certificate-authenticated
DTLS, IPv4/IPv6, DNS and disconnect restoration. IPsec uses stock strongSwan's
kernel-libipsec/kernel-netlink/VICI plugins, not kernel XFRM. Its private bridge
permits IKE's guest ports 500/4500 without binding privileged Android ports.
Tailscale assets are stock ARM64 `tailscale`, `tailscaled` and `headscale` binaries.
Its private TLS control plane, embedded DERP and two ordinary TUN clients require
no account or changes to Android's network. These application fixtures require
actual payload exchange; daemon startup alone is not a pass.
`--load` adds four concurrent TCP streams per family, small-message latency,
16 MiB per direction, slow receivers, half-close and descriptor-release checks.
Receipts retain CPU frequencies and whole-launch wall/user/system time separately
from client-only CPU and exchange timings. It is a bounded workload, not an
Internet-throughput claim. The option applies to direct consumers; an OCI image
must explicitly retain `MD_PACKET_LOAD` to run it inside that container.
`test_booted_libvirt.py --session SESSION --kernel KERNEL --initrd INITRD
--network --network-forward nat` checks a managed bridge/TAP with DHCP/DNS,
bidirectional VM traffic and cleanup. Start its headless retained session with
`installed_desktop.py start --network-profile bridge`; routed wildcard DHCP
binds otherwise request privileged Android ports. For this packet scenario,
`INITRD` is the official gzip-compressed Alpine initramfs, from which the fixture
builds its private VM image, not the raw kernel-contract CPIO used by the direct
TCG test. Modular libvirt preparation starts both the QEMU and network services.
Without `--network`, omit `--initrd` to build the shared Landlock contract
image from `--build`; `--direct` runs that same image without Libvirt.
The echo peer lives beyond a routed veth
in a separate namespace and verifies the translated IPv4 source and port range.
`--network-forward open` checks the same topology without NAT; IPv6 uses routed
traffic. ICMP REJECT is not substituted with DROP.
All services, staged files and temporary initramfs trees are cleaned
up; receipts remain under `build/packet-apps`.

`test_network_ethernet.py BUILD STORE` checks packet/raw sockets and stock
`tc qdisc ... htb` plus `u32 ... action csum` in Base and lazy modes. It sends
bad-checksum IPv4/UDP packets, checks repaired bytes and nonmatching traffic,
then deletes the filter/qdisc and verifies unchanged delivery and link cleanup.
Unsupported bandwidth classes must fail, not silently omit shaping.
`test_network_tc.c` adds branch-local copies, fragmented/option-bearing packets,
namespace lifetime, lost ACKs and atomic rollback to the native resource suite.

`test_routed_network.py` adds libc interface lookup, IPv4 packet information
with absent/truncated receive buffers, a datagram queued before sender close,
and receiver-close/rebind without reusing stale reply mappings.
`probe_nft_runtime.py BUILD STORE --network bridge --machine-entry` (or `--base`)
checks stock nft/iptables catalogue operations, atomic rollback, verdict maps,
UDP DNAT and immediate IPv4/IPv6 TCP REJECT. It also checks NAT
state aliases, randomized masquerade rule admission, CTNETLINK inventory and UDP
mapping deletion/re-admission on the same socket. TCP tracking deletion and
expiry must leave the stream usable and admit a new record on subsequent IO.
Unsupported rate-dependent forwarding rules fail without committing their
table. The default `routed` mode also
publishes the fixture's port 53 on Android and can fail with EACCES under host
bind policy; use the explicit private bridge for guest-only coverage, not as a
substitute for external publication tests. The native network-launch fixture
separately requires new and established native TCP peers to receive reset.
`benchmark_network.py BUILD STORE --sysroot GLIBC_SYSROOT` compares one static
binary under shell/native, host, routed, filtered and NAT modes with rotated
rounds. It reports application CPU separately from whole-invocation CPU;
the latter includes startup, rule preparation and cleanup. Local DNAT uses the
OUTPUT hook. Reports retain CPU-frequency observations with each sample.
`--reference BASELINE` interleaves both runtime builds against the same guest
and native executable; pairs stay adjacent and alternate their order.
`--profile nat` selects a focused comparison; repeat the option for multiple
profiles. `--output DIRECTORY` separates comparison receipts.
`benchmark_readiness.py --build BUILD --reference BASELINE --store STORE`
interleaves filesystem/readiness workloads across both runtimes and Base/lazy
entry. It does not pin CPUs by default. `--cpu-mask HEX` applies the same native
taskset mask to both runtime process trees for a controlled follow-up; reports
retain that mask and CPU-frequency observations. This changes neither governor
nor host frequency limits, and pinned results do not certify unpinned performance.
`--application-filter` measures the same workloads after guest seccomp admission.
Focused `--workload` selections include `identity` (four UID/GID reads), `uname`,
`ipc-nowait` (a SysV send/receive pair) and `socket-nowait` (a datagram pair).
`fd-bytes` reads a regular file's current available bytes with `FIONREAD`;
`fd-ioctl` instead changes an eventfd's nonblocking mode with `FIONBIO`.
These retain their normal owners and guest filters; setup is outside the timed loop.
Use `--statistics` in a separate run to record syscall, checkpoint, ptrace and
supervisor CPU counters; instrumented timings are not a performance baseline.

`test_os_boot.py --service-io avahi|cups|firewalld` supplements the stock boot
observer with protocol IO and three stop/start cycles. Avahi must answer mDNS
over both families; CUPS must answer an IPP request within the fixture's normal
readiness bound; firewalld must enforce a live rule. Unsupported service rules
remain a failure, not a reason to mask the service or enlarge its timeout.

## Process And Resource Fixtures

`test_resources.py BUILD --case cgroup` runs the native ownership model and
isolated guest cgroup tests in Base and lazy modes. It covers controller
publication/retirement, policy validation, hierarchical PID limits, threads,
clone3 target admission, zombie/reap accounting, poll notifications, sampled
CPU/RSS and cgroup namespaces. Repeat with `--kernel-profile linux-5.15` for
capability-fallback coverage; this is not a substitute for a physical 5.15 kernel.
CPU quotas, cpuset affinity and memory limits are retained policies, not enforced
controls. `memory.events`/`.local` must remain quiet after the initial read and
retire on group/controller removal. These tests keep that boundary explicit.

`test_kmsg.py BUILD STORE --machine-entry` (or `--base`) verifies the private
kernel-message device's record ABI, cursors, poll/blocking delivery, overrun and
permissions. It does not read host logs or claim OOM observation.
`test_proc_policy.py` covers private proc inventories and advisory kernel-policy
values through original and newly mounted proc views, including denied writers
and explicitly rejected strict overcommit. Repeat supported fixtures with
`--kernel-profile linux-5.15` to exercise capability fallbacks.

`test_thread_identity_runtime.py BUILD STORE [FIXTURE] --debuggers` checks libc
thread identities and multi-threaded setuid/setgid/setgroups, including strace
both inside and outside a PID namespace. The store must contain strace and GDB.
`--executor app|shell` selects the Android identity; without a prebuilt fixture,
the test compiles against that store's libc. `--case robust` independently checks
ten cases both directly and inside a logical PID namespace: list registration/
query, owner death after thread/process exit, SIGKILL, leader/nonleader exec,
childless and real-child waits, thread-specific waits, native SIGCHLD readers
and subreaper adoption. Wait coverage includes WNOWAIT, NULL waitid output and
stop/continue events. `--native-control --executor shell` builds a static fixture
and runs the same assertions directly under Android before the Shroot variants.
It distinguishes kernel limitations without weakening the managed-path checks;
libc accepting a robust attribute alone is not a pass.
`test_resources.py BUILD --case robust-futex --executor app|shell` tests the
cleanup cursor and retained-address-space executor independently of libc:
atomic owner replacement, sleeping-waiter wakeup, exit/SIGKILL/exec, private
child reaping, coalesced/directed SIGCHLD and subreaper adoption, including
concurrently blocked signalfd/waitpid readers and non-leader parent borrowing.
The same native signal queues supply those readers after retirement. Cleanup
faults retain the application's SIGSEGV disposition; malformed-list walks yield
bounded progress turns without accepting another cleanup over unfinished work.
The fixture uses `task_retained`, `task_robust` and `task_child_reap`, not its own
implementation of those operations. The runtime uses these same owners; the
separate libc fixture verifies their integration with distribution mutexes.
The supervisor-state suite checks filtered-wait selection, cancellation of
NULL-output waitid probes when their shared buffer pool is exhausted, shared
checkpoint ownership for parked signal waits, and retirement admission while
the parent or its children still have terminal events to complete. Memory-repair
stops are retained across every quiescence owner and replayed exactly once.
Provider-wait checks distinguish repeatable direct/IO preparation from committed
effects, partial transfers and splice. `test_mount_api.py` also uses libc reads
under signals without SA_RESTART while refreshing an already-open mount table;
Python's automatic EINTR retry cannot mask that assertion.
Signal information remains native while stopped and is translated separately
for debugger observation and delivery to the target.
Compile the fixture with both stock glibc and musl: `--pid-futex` also checks
explicit PI rejection at either mutex attribute selection or initialization,
followed by contended ordinary mutexes, including with `dumpable=0`.

`test_setid.py BUILD STORE [--base | --machine-entry]` builds a small ELF and two
competing shared libraries inside a disposable store with Python and a C
compiler. It checks stored setuid/setgid metadata through initial entry, path
exec, descriptor exec and an unlinked executable, without `--admit-elf`.
Assertions cover real/effective/saved/filesystem IDs, auxv, `AT_SECURE`, actual
loader rejection of an untrusted `LD_LIBRARY_PATH`, `no_new_privs`, ptrace,
unmapped user-namespace owners, `nosuid`, ignored script bits and failed exec.
Tests use stock libraries with default lazy entry or explicit Base. This is
guest credential behavior, not Android privilege or
complete kernel-equivalent isolation. The browser fixture separately verifies
stock Chromium's set-ID sandbox and its no-new-privileges rejection.

`test_nproc.py BUILD STORE` verifies per-real-UID process/thread quotas, shared
thread limits, fork copies, unreaped children, UID-change/exec admission,
protected memory, seccomp denial and `/proc/*/limits`, both directly and in a
private PID/mount namespace. `test_rtkit.py BUILD STORE` starts the distribution's
unmodified RTKit daemon with its original chroot, identity and NPROC limit,
checks the canary/watchdog threads and D-Bus responses, then releases the owned
processes. `test_os_boot.py --build BUILD --store STORE --rtkit` additionally checks the
packaged service unit through a real systemd boot, restart and stop. RTKit's
ability to obtain native realtime scheduling is a separate Android permission,
not a success implied by daemon readiness.

`test_readonly_exec.py BUILD STORE` creates fresh executable leaves, then checks
first exec, `/proc/self/exe` reexec, file-watch registration and mutation denials
on the base mount, a bind, a tmpfs bind and a read-only tmpfs superblock. This
guards systemd generator sandboxes against representation changes being mistaken
for guest writes. `test_os_boot.py` also rejects generator failures even when
systemd reaches its default target.

Root desktop recipes use an owned XDG autostart profile to omit the unnecessary
XFCE authentication agent. Packaged files and user overrides remain intact;
ordinary-user sessions retain their distribution configuration. GUI checks reject
the known empty PolicyKit error dialog as well as lost X11 authorization.

`test_filtered_fd.py BUILD STORE` compiles a small fixture with the guest's own
compiler and runs Base/lazy controls. Its application filter kills gettid,
clone and sendmsg, and constrains openat's original PC and flags. It checks
protected stdio metadata, CLOEXEC, read/mmap/close and single-effect O_EXCL.
The optional installed `file` utility is reported separately when unavailable.

`test_epoll_runtime.py --build BUILD --store STORE --trace --machine-entry`
includes exclusive FIFO/signalfd subscriptions, aliases, invalid mask/MOD
requests, retirement and application-filter coverage. The OCI nginx fixture
checks 1, 2 and automatic worker counts over routed TCP, repeated HTTP requests
and shutdown in the server's own signal domain; `--base`, `--machine-entry`
and `--kernel-profile linux-5.15` select independent admission checks.

The epoll fixture also runs mixed/native readiness, signal masks, shared-table races and denied
retirement through guest strace. Cold-admission cases add a first virtual interest
to an already-blocked finite or infinite epoll wait, including protected callers
and epoll_pwait2. Native-ready cases cover one-shot consumption, invalid output
pointers and argument-sensitive application filters. `--base` selects the same
cases without lazy entry. `test_debugger_runtime.py` also accepts
`--machine-entry`; `--case strace-detach-socket` verifies that detaching does not
need data to arrive on a blocked socket. These regressions cover common Base
owners, not just SVC publication.

## Graphical Acceptance

The GUI extension fixture `fixtures/vscode-gui.py` drives
`fixtures/vscode-debug.js` through an existing recipe desktop: editor/save and
watcher changes, window reload, integrated terminal, GDB variable update and
normal close. Browser GUI acceptance additionally requires rendered pixels,
actual keyboard/pointer input, download and portal selection, not only a
healthy browser process. The current desktop/container receipts and explicit
limits are in [SVC integration evidence](svc-rewriting.md#desktop-and-container-acceptance).

## Named Environment Workflows

`test_environment_workflows.py --tag NAME --stage all` installs fresh public
Debian and Alpine images through the installed APK's shell-owned catalog. It
checks package installation, account login, concurrent cancellable operations,
authenticated file import/export and cleanup, then simultaneous X11/Wayland
editors with keyboard input, bidirectional clipboard and saved-file readback.
Backup checks remove the original instances, restore new storage identities and
relaunch graphical clients against the restored files.

The fixture uses a virtual display without Desktop and retains its two named
environments and archives for inspection. `--stage prepare|accounts|files|graphics|backup`
can run a focused phase against the same tag. `--dns system` is the default;
an explicit address opts into that resolver for the test stores when Android's
network uses Private DNS or a VPN. `--apk PATH` installs through the normal MCP
update protocol; `--lease ID` reuses an existing wake lease without releasing it.
Reports and screenshots live under `build/environment-workflow-*` and
`build/check-*`. No Termux executor, Android root or HOME lease is required.

The file phase invokes debug-only `GuestFilesInstrumentation` with an exact
store. It exercises production shell admission, the selected-guest static helper,
Android import, independent guest read, SCM_RIGHTS export and owner-loss cleanup,
without creating a graphical session.

`test_environment_catalog.py --names NAME NAME` uses prepared named Debian and
Alpine environments with Python 3 and Mousepad. It checks independent catalog
identities, rendered X11/Wayland windows, shared terminal ownership, and exact
CLI/MCP stop without cancelling a peer. Test-only desktop entries are added to
the selected guests and removed after successful checks. The owned virtual
display and graphical sessions are released on exit.

`test_environment_recovery.py --help` documents an opt-in process-death check.
Its `--kill-app` mode terminates MagicDesk during a native restore and reopens
the exported launcher, interrupting any other work in that app process. Run it
only on an idle device. It uses a private library, preserves the default catalog,
verifies helper termination, then recovers staging and retries the restore.
Neither fixture reboots the device or requires a Desktop session. Reports are
`build/environment-catalog.json` and `build/environment-recovery.json`.

## Prepared Runtime Fixtures

### Graphics Adaptation (Paused)

This research is paused, not integrated into the installer or APK. Its goal is
to retain full Mesa builds while using distribution KWin/Weston packages with
small locally compiled fixes. The current KWin prototype admits exact builds;
automatic adaptation to previously unknown package builds is not implemented.

`test_kwin_binary_adaptation.py --name steamos` is an offline ELF-adaptation
experiment for the hash-pinned stock KWin 6.2.5 library. It compiles a small
DMA-heap allocator/fence module against installed KWin headers, rewrites a
private copy's virtual dispatch relocation and allocator entry, and checks
stock swapchain GPU rendering, mapped pixels, synchronization and object
ownership in three processes. It requires the recipe Turnip/Mesa profile,
KWin development headers, C++/binutils, Python venv and access to the pinned
LIEF wheel. Results and adapted artifacts go to
`build/kwin-binary-adaptation`; device work files are removed unless `--keep`
is supplied. `--directory` reuses only a retained directory from this fixture.
It does not replace installed libraries, start a desktop, adapt unknown builds,
or establish that the complete KWin/Mesa source patches can be removed.

`test_kwin_binary_module.py --name NAME --adapt --keep` extends that experiment
with a partial KWin module and a separate Qt platform plugin. The cached source
and compiler configurations cover `steamos` (6.2.5) and `nixos-recipe` (6.6.6).
`debian-turnip` prepares 6.3.6 from the verified archive
`build/kwin-6.3.6.tar.xz` and installed development packages. Only protocol
generation, its small code generator and selected fragments are built.
It checks shared C++ object layouts against the release headers and redirects
selected entries in a private stock ELF copy. Qt type metadata remains shared
with the stock library; NixOS retains its package-generated Qt/QML launcher.
Protected ELF entries retain BTI landing pads and the new module uses BTI/PAC
when the original library does. The original GNU property note is unchanged;
see the [AArch64 ABI requirements](https://github.com/ARM-software/abi-aa/blob/main/sysvabi64/sysvabi64.rst#982-tool-requirements-for-generating-bti-instructions).
`--rebuild` measures a fresh fragment build, not a complete KWin build.
Reports and binaries are retained under `build/kwin-binary-module/NAME`; device files
are removed by default unless `--keep` is supplied.

Run `test_kwin_binary_desktop.py --name NAME --directory WORK --case qpa` and then
`--case egl --cleanup` (SteamOS/Debian) or `--case vulkan --cleanup` (NixOS) against the
printed retained directory. The fixtures use
an independent awake virtual display, verify actual pixels, the selected modules
and Turnip renderer, and require a zero process exit status. GPU cases require
changing pixels, not just a mapped window. No other awake lease or launch of the
tested environment may be active. `--gdb PATH` is a separate diagnostic launch.
All three KWin versions pass Qt-window and animated GPU-client checks on the test device.
The tests do not alter the installed compositor or launch Android Desktop.

This is an exact-build AArch64 proof, not a signature patcher for arbitrary
packages. Full Plasma sessions, screencasting, pointer-lock/focus lifecycle and
other KWin versions need separate coverage. The added dispatch table is writable;
production integration must provide read-only relocation protection and package
update admission before replacing recipe builds. Mesa remains recipe-built.
The recipe's optional private GLVND fallback is outside this experiment.

`test_weston_child_guard.py --name NAME` checks a small public-API interposer
against the stock Weston 13 library. Its probe uses cached release headers but
the interposer itself needs only the C compiler and `libdl`; it knows no private
structure offsets. `test_weston_child_desktop.py --name NAME --stock` requires
the unguarded popup/focus crash; omit `--stock` to require visible popup windows
and a clean exit with the interposer. Both controls pass on `ubuntu-fuse` and
`manjaro-turnip` with stock Weston 13.0.0 and recipe Turnip. Results are under
`build/weston-child-guard/NAME`. These fixtures do not replace the installed
Weston or claim to implement every change in a newer Weston release.

#### Evidence and Continuation

The retained device evidence covers the following AArch64 builds on Android 16
(nubia NX809J). These are focused graphics checks, not the full distribution
application matrix.

| Environment | Stock component | Passing checks | Report directory under `build/` |
| --- | --- | --- | --- |
| `steamos` | KWin 6.2.5 | Qt window, animated EGL, clean exit | `kwin-binary-module/steamos` |
| `debian-turnip` | KWin 6.3.6 | Qt window, animated EGL, clean exit, BTI/PAC retained | `kwin-binary-module/debian-turnip` |
| `nixos-recipe` | KWin 6.6.6 | Qt window, animated Vulkan, clean exit | `kwin-binary-module/nixos-recipe` |
| `ubuntu-fuse` | Weston 13.0.0 | Stock negative control, guarded popup/focus, clean exit | `weston-child-guard/ubuntu-fuse` |
| `manjaro-turnip` | Weston 13.0.0 | Stock negative control, guarded popup/focus, clean exit | `weston-child-guard/manjaro-turnip` |

KWin's `desktop-*-state.json` contains each final client result; `module-profile.json`,
`module-result.json` and `adapt-result.json` record inputs, compilation and ELF
checks. Weston records its build in `result.json` and GUI controls separately in
`stock/` and `guarded/`. The run JSON, logs and screenshots are evidence, not
installable distributions. Alpine, Arch, Fedora, openSUSE and Armtix have not
been checked with this adaptation method.

Resume by investigating one analyzer that recognizes supported implementation
patterns from the installed ELF and, where needed, matching source/debug data.
Select a transformation by observed interfaces and code structure rather than
an exact version, hard-coded address or a whitelist of package hashes. Symbols
already locate KWin entries dynamically; the exact-build admission and manual
source profiles are the parts still to generalize. Finding an entry alone does
not establish C++ object-layout or behavioral compatibility. Reject ambiguous
matches rather than removing the current checks without a replacement.

The next qualification is the same analyzer on multiple package builds without
per-build manual edits, followed by the Qt/GPU checks above. Full desktop,
pointer-lock, screencast, package-update handling and read-only dispatch remain
separate requirements before changing recipes. No new runtime interception of
ordinary system calls is part of this approach.

A local checkpoint is saved outside disposable build output at
`~/magicdesk-research/graphics-adaptation-20261029.tar.gz`. It contains the lab
sources, installer patch definitions, retained reports/screenshots/modules and
the Debian source archive/preparation helper. Its repository baseline is
`636a8dab1ef4eb94720733dedb8a4f754c55b8ec`; extract it only into a separate checkout
of that baseline, not over an unrelated worktree. This is a local research
backup, not a commit, release or backup of installed guest environments.
Re-running requires the prepared guests and compiler/source caches described
by the fixture profiles;
the archive does not contain complete SteamOS/NixOS build environments.

### Rootfs Fixtures

`prepare.mjs` authenticates Debian archive metadata and packages, then extracts
an isolated test rootfs without maintainer scripts or host installation.
`build.sh` builds the same CMake runtime as the APK and separate Bionic/glibc
fixtures. It requires the Termux development toolchain and JDK; installed guest launches
do not require Termux. Exact inputs are retained in the build's `manifest.json`.

```sh
node native/guest-exec-lab/prepare.mjs build/guest-fixtures --gtk
sh native/guest-exec-lab/build.sh build/guest-fixtures
python native/guest-exec-lab/test_device.py build/guest-fixtures --packages
python native/guest-exec-lab/test_graphics.py build/guest-fixtures
python native/guest-exec-lab/test_graphics.py build/guest-fixtures --gtk
```

For real application workflows, use a separate prepared build:

```sh
node native/guest-exec-lab/prepare.mjs build/guest-applications --applications
sh native/guest-exec-lab/build.sh build/guest-applications
python native/guest-exec-lab/test_device.py build/guest-applications --packages
python native/guest-exec-lab/test_graphics.py build/guest-applications --application mousepad --network
python native/guest-exec-lab/test_graphics.py build/guest-applications --application galculator
python native/guest-exec-lab/test_graphics.py build/guest-applications --application mousepad --routed
python native/guest-exec-lab/test_graphics.py build/guest-applications --application mousepad --routed --protocol x11
python native/guest-exec-lab/test_shortcuts.py build/guest-applications --protocol wayland
python native/guest-exec-lab/test_shortcuts.py build/guest-applications --protocol x11
```

For Qt text-input-v3, prepare the newer Debian userspace separately:

```sh
node native/guest-exec-lab/prepare.mjs build/guest-qt --qt --suite trixie
sh native/guest-exec-lab/build.sh build/guest-qt
python native/guest-exec-lab/test_device.py build/guest-qt --packages
python native/guest-exec-lab/test_qt.py build/guest-qt
```

Bookworm is the default suite; trixie uses the same pinned signature and package
hash verification, including the signed Release's exact codename. Qt packages
belong only to the prepared fixture store, not the APK. The Qt driver temporarily
selects the debug Android IME and restores the prior keyboard on completion or
failure. It retains a protocol trace and separate strict-correction and
`--skip-correction` reports; the latter cannot establish correction support.

Application checks require a current debug APK with the guest CLI installed.
Routed checks use the production guest connection/session wrappers and keep one
protocol session across successive clients. The X11 case also verifies rejection
of an incorrect MIT cookie. Receipts retain protocol and connection mode; pixel
capture, input, process exit and cleanup remain separate assertions.
They use its runtime and the production Java graphical-session wrapper.
The fixture-only `md-prepare-applications` configures real shell NSS identity,
toolkit caches, CA certificates and an explicit public DNS resolver in the owned
test store. `--network` opts into DNS and certificate-verified HTTPS checks.
Mousepad coverage includes editing, saving, fresh-process file readback and
reopening; both applications must exit normally, not merely lose their window.
Settings checks exercise real D-Bus activation and persistence, not a substituted
keyfile backend. Application logs and hashed screenshots accompany the JSON report.

`test_shortcuts.py` encodes the shortcut editor's real `LinuxLaunchRecipe` through
a debug-only Java fixture and launches the resulting `.desktop` files through
the ordinary launch service. It checks bidirectional Unicode clipboard exchange,
guest file saving, file-dialog cancellation, opening another file, Save As,
overwrite confirmation, directory creation and inaccessible paths. It also checks
concurrent applications in one store, recipe reuse and actual process exit after
protocol close. Catalog events identify Wayland dependent windows; native-family
events identify X11 transient dialogs and Wayland subsurfaces without guessed IDs.
Inotify receipts identify
the exact command's start and exit. Screenshots are point-in-time observations,
not synchronization barriers. Input requires the selected native window's
Android host and focus acknowledgement. Opening a file awaits the exact client
title and verifies the document contents independently through the clipboard.
The test restores an existing plain-text clipboard
and refuses to replace non-text contents. Launchers and their receipts remain in
the prepared test directory for inspection; their launches participate in Recent.

The default calculator workflow checks `7+8=15`. An additional
`--repeat-digit` check sends `2+2` without a pacing delay and requires `4`.
It currently reads `2` on the tested Wayland stack and retains a separate failing
`*-shortcuts-repeat-digit-results.json`; it must not be treated as passing
input coverage or hidden by a sleep. `--trace-wayland` retains the calculator's
client-side protocol log. Both key press/release pairs arrive before Galculator's
[100 ms toggle reset](https://github.com/galculator/galculator/blob/v2.1.4/src/ui.c#L748-L760):
the second click deactivates the button and its
[digit handler](https://github.com/galculator/galculator/blob/v2.1.4/src/callbacks.c#L95-L107)
ignores it. The Debian binary has the same logic. Mousepad's repeated-digit
control passes; production input is unchanged. Calculator configurations are
unique to each run, so another run's saved notation cannot affect the result.

Device checks use the configured MagicDesk MCP connection and require selected
shell UID 2000. They do not change identity, install an APK or start Desktop.
Graphical checks use a private virtual display and clean up their own sessions.
Results retain exact device/build identities, commands, failures and known limits.
Prepared shell-owned test stores remain at the recorded path for inspection.

`test_graphical_session.py --helper APK_BUILD/libmagicdesk_guest_files.so --store STORE`
checks the packaged session launcher without a viewer or Desktop. Repeat `--store`
for multiple distributions; `--user` selects an existing guest account (default
`root`, never Android root). It uses real session D-Bus, verifies literal arguments,
X11 authority setup before bus startup, private-directory permissions, exit status
and cleanup for direct X11, routed X11 and Wayland. Temporary helper files are
removed. Host tests additionally cover the authenticated file worker and failed
bus/unmount paths. This is session-launch coverage, not rendered GUI acceptance.

Capability fault injection checks that missing kernel calls reject only guest
execution. Process fixtures cover concurrent distinct stores, cancellation and
orphan ownership. These checks do not emulate an old kernel or prove compatibility
with every distribution. See the native component's coverage and limits.
The native prepared-command fixture runs under the selected shell identity and
checks interpreter descriptor pinning across name replacement, borrowed/unlinked
executables, argument identity and descriptor cleanup. It is not run in the
Termux app domain, whose syscall restrictions differ from the guest executor.
