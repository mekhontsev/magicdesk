# Namespace And Sandbox Fixtures

[Fixture guide](README.md) | [Native architecture](../shroot/README.md)

Stock namespace clients and credential workflows. Logical namespace success must not be reported as native kernel confinement.

- [Namespace Clients](#namespace-clients)
- [Guest Credential Checks](#guest-credential-checks)

## Namespace Clients

### Init And Proc Visibility

`test_systemd.py --build BUILD --store STORE` runs stock systemd as guest PID 1
under actual UID 2000. The disposable Debian-like store needs systemd, bash and
util-linux including `script`; optional `--trace` also needs strace. It creates private namespaces,
proc, `/run`, a private `/dev` with a PTY console, sysfs and cgroup2, then boots a dedicated target without distro
hardware units. Assertions cover system/user managers, oneshot/notify services,
TCP socket and timer activation, stop and normal manager exit. The supervised deadline
bounds failures; it never invokes a host reboot or shutdown target. This is not
a default-target boot or GNOME login test.
`--property KEY=VALUE` extends the tested oneshot's service restrictions.
The combined `StateDirectory=md-init-probe` and `SetCredential=md-probe:hello`
case covers filesystem metadata ioctls, read-only tmpfs reconfiguration and
Arch 262's nosymfollow credentials mount. A diagnostic `MountFlags=slave` case
can isolate other namespace policies, but does not certify default propagation.
The combined hardening case also covers `ProtectProc=invisible` and `ProcSubset=pid`.

`test_proc_policy.py BUILD STORE` checks mounted proc visibility with stock
Python/util-linux: all four hidepid modes, primary/supplementary exception groups,
fsuid, native dumpability, retained directory FDs, zombies, subset filtering,
fsconfig, bind aliases and namespace copies. Invalid creation options and
unsupported option remounts must fail, not silently relax a policy. The native
resource-owner fixture checks allocation-free credential snapshots before and
after task retirement, with exact descriptor cleanup.

`test_os_boot.py --build BUILD --store STORE` boots the packaged default target
and asserts a non-degraded result. Its observer waits for systemd's startup
jobs, checks journal write/readback, system D-Bus and logind restart, and collects
this invocation's failed units and service state. Ubuntu, Arch and Fedora pass
default boot; Debian needs an explicit `--drop-capability sys_module` container
policy. Degraded boots are failures, never whitelisted. The
[extended matrix](init-matrix.md) records additional userspaces, source identity,
init policy and the distinction between manager checks and stock default boot.
`--trace` includes pathname lookup, metadata,
both mount APIs, process/exec boundaries, application seccomp installation and
epoll creation/registration/waits, using the same syscall set as the manager
fixture. `--init openrc` uses Alpine's real
`openrc-init`, stock container service policy and a private runlevel selection.
It checks the default services, restart/stop and exact launch-registry cleanup.
The device transport is closed only after that launch has terminated.
`--init runit` retains the distribution's stock stages and default runsvdir.
A private service checks start/restart/stop with a generation-aware readiness
hook and exact PID retirement. Default hardware service status is diagnostic,
separate from the asserted init/service lifecycle.
`init_fixture.py` owns the shared private-filesystem/console setup; OpenRC's
in-guest observer does not import host-side transport dependencies.

### Control Events And Descriptor Lifetime

`test_resources.py BUILD --case control-event` runs source and virtual-interest
model fixtures as UID 2000. They cover generation coalescing, concurrent
publication/subscription/retirement, duplicate event data, open-description
identity, level/edge/one-shot delivery, bounded fairness and failed admission.
Weak authority checks race final release against temporary acquisition for
10,000 rounds. Expiry remains observable without retaining the target, and
disabled one-shot interests retire without inventing an application event.
Mixed native/virtual collection performs 100,000 rounds with allocation guards
and exact FD cleanup. This is an internal collector test, not a guest epoll
notification test. The same case runs real native and Shroot wait loops and
requires zero interception counters for `ppoll`, `pselect6`, `epoll_pwait`,
`epoll_pwait2` and registration, both before and after an application
filter denies further seccomp installation. Timings are diagnostic, not a
stable performance benchmark. Native and guest runs also retain epoll identity
through dup, forked registration changes and queued SCM_RIGHTS after all source
aliases close; both received aliases observe the same kernel interest set.
Epoll creation alone retains its expected observation count. A BPF interpreter
checks selector classes, FD argument positions, collisions, gate addresses and
native wait decisions. The native remote-admission fixture installs that same
filter through a stopped task and compares its continuation with an unmodified
ptrace-interrupt baseline, for dumpable/protected processes and allowed/denied
seccomp. Pipe reads restart; epoll reports the kernel's EINTR. Signal masks and
application filter errors must survive either outcome. This proves the cold
syscall mechanism, not guest readiness or admission across all owners. Two-thread
checks require successful TSYNC to reach both callers, and an incompatible
sibling to reject synchronization without changing either caller's filter.
The same task-filter module additionally runs at syscall-entry, seccomp and
completed-exit stops: the original pipe write must execute exactly once, with
the restored syscall-info boundary. Restart-code exits must reject admission
without changing registers or signal masks, then complete native signal restart.
Real task-filter calls borrow their stop from the common quiescence owner;
synthetic interruption and deferred syscall events retain separate resume
ownership. Model checks reject premature release, event replay and double return,
and preserve the original event's rusage.
Production proc/watch installation also uses this supervisor-owned task-filter
call. The buffer fixture fills its IO pool while admitting a reserved control
loan in the same mapping. Filter construction leaves delivery bytes unchanged;
pin/release and reuse allocate no memory or extra guest descriptor. Guest selector
regressions exercise this path through opens, dup and prepared sandbox filters.
The `ofd-order` case checks typed sysctl masks, read independence, independent
opens/shared references, UTS copies, constant providers and concurrent lazy
carrier publication. It also exercises the typed epoll authority through the
actual file-table and queued-message owners: publication failure, dup/reuse,
fork/unshare, exec CLOEXEC, transfer after source-table teardown and final
transport cleanup. Empty authorities have no wake FDs; completion and retirement
run under allocation-failure guards. Watched targets also traverse dup/fork,
queued transfer and final CLOEXEC release without manual collector retirement.
The cold admission inventory checks shared/copied tables and thread groups,
masked-selector collisions, late owners from an in-flight fork, queued table
mutations, allocation failure, positive TSYNC rejection and partial-installation
retry. Unobserved close/dup outcomes must invalidate virtual admission, including
unknown duplicate numbers, and wake a retained collector with an explicit error.
The live fixture joins that inventory to the task-filter and quiescence modules:
three real processes share one epoll through two aliases, with CLONE_FILES and
forked tables. Dumpable and protected variants require installation in every
holder. Partial application denial must abort virtual activation, retain only
confirmed selectors and allow native waits through those residual filters.
These checks do not whitelist default-boot
failures or establish guest virtual-subscription admission.

`test_journald.py --build BUILD --store STORE --probe-only --trace` starts a
private systemd target and runs a selective TSYNC-filter probe in the same
process that execs stock journald. The service retains its distribution security
policy. The probe changes only an invalid epoll descriptor's error result;
successful native waits remain native. Probe-only success establishes filter
admission, not journald readiness. Without `--probe-only`, a failed journal
service fails the fixture. Trace output uses the shared init syscall set.

The native `fd-lifecycle` case checks `KCMP_EPOLL_TFD` identity across duplicate
descriptors and queued SCM_RIGHTS, automatic removal after final release and FD
reuse. Its private observer does not retain the target OFD, but adds a nesting
level: a valid maximally nested application epoll rejects it with ELOOP. This
is a diagnostic boundary, not a production ownership mechanism. The fixture
discovers the native depth rather than requiring a particular kernel's limit.

### OpenRC And Namespace Lifecycles

`test_openrc.py --build BUILD --store STORE` needs an Alpine store with openrc
and util-linux. It runs stock sysinit/boot initialization and dedicated runlevels
inside private mounts/PIDs, checks dependency order and daemon status/restart,
then verifies stop and process cleanup. It neither creates a fake OpenRC boot
marker nor invokes reboot/shutdown. The fixture shell owns PID 1; this does not
test the separate `openrc-init` program or default distro hardware services.

`test_resources.py BUILD --case mount-namespace` includes offline network
namespace identity/authority, inherited IPC, private devpts membership and
controlling terminals, logical cgroup membership/readonly policy and native
anonymous-memfd statx. Cgroup namespace cases exercise captured roots for both
hierarchies, relative paths through existing and mounted proc, clone/setns,
retained namespace handles, removal, user-namespace authority and fsopen capture.
Namespace-file binds cover path and O_PATH sources, nsfs metadata, setns and
related ioctls, exited owners, recursive binds, independent namespace copies,
mount-cycle rejection, readonly controls and repeated lazy-unmount retirement.
The remaining mount/user/PID/UTS/IPC cases run in the same suite. Open and detached
mount descriptors also compare fdinfo mount/inode fields with statx.
`test_mount_api.py` covers tmpfs superblock reconfiguration with live/unlinked
writers, retained shared mappings, bind aliases, namespace copies and separate
per-mount restrictions. The transient-store native fixture checks independent
process connections and policy invalidation, as well as exact descriptor cleanup.
The mount-API fixture also checks nosymfollow on detached/native/proc mounts,
per-alias remount, retained origins and namespace copies. Initial-root remount
and propagation use identical pathname/proc-FD assertions; a chroot into an
ordinary directory must not turn that directory into a mount root.
Removed-mountpoint cases synchronize independent namespace views, remove and
recreate a covered directory, then read mountinfo, recursively clone or copy the
namespace. Nested mounts disappear while retained root/child FDs remain usable.
Graph tests also check native/logical deletion, rename, independent store writers,
foreign-reader authority, published revisions and exact descriptor cleanup.
Storage fixtures exhaust the native FD table during database open, journal
creation and mount copying; they require EMFILE, rollback and recovery after
descriptors are released, not a generic IO error or a partially published view.
Filesystem-attribute ioctl checks cover regular files, directories, bind aliases,
detached tmpfs, dup/fork/SCM_RIGHTS and a seccomp-filtered caller, without exposing
Android storage attributes. Native readonly attachments and pipe ioctls retain
their distinct semantics. Mount-graph checks cover local provider publication,
explicit foreign rejection, restored synchronization and allocation rollback.
File binds cover non-link readlink errors and exact mount-root paths for
hardlinked sources. Proc directory suffix tests exercise two-path rename/link,
nofollow and followed symlinks, mixed dirfd/path operands, readonly and EXDEV
rejection through direct, symlinked and bind-mounted proc views.
Pivoted non-base roots retain proc/dev providers across namespace copies.
Mounted-store image tests retain exact hardlink and symlink names alongside
per-mount executable identities.
Native storage tests bind both names of a hardlinked file, recursively and
non-recursively, with an unrelated locked mount present. They check shared
contents, independent readonly policy and exact descriptor cleanup.
Socket binds retain the selected endpoint through hardlink aliases and unlink;
readonly mounts allow connections while socket write permissions remain enforced.
Tmpfs cases cover permissive capacity requests, malformed options, native writes
and shared mappings, aliases and detached ownership. Key-service absence checks
cover protected callers, fork/exec, namespaces, chroot and application filters.

`test_namespace_apps.py BUILD STORE --case split-root` runs stock Bubblewrap
with separate `/etc` and `/usr` binds, merged-usr links, proc/dev mounts and
private tmpfs. Its guest checks realpath and atomic replacement through proc-FD
paths. The prepared store supplies Bubblewrap and ordinary shell utilities.

### Mount And Device Providers

`test_mount_api.py BUILD STORE` checks descriptor-based mounts with stock
util-linux consumers. It covers filesystem-context lifetime and authority,
SCM_RIGHTS, detached IO, recursive mount attributes and all-or-nothing rejection
of locked policies. ABI checks include empty paths, nofollow, versioned
structures, shared openat2 validation, protected callers and explicit errors
for unsupported attributes. The prepared store needs Python and util-linux.

`test_mount_capacity.py BUILD STORE` checks 192 simultaneous guest bind mounts,
distinct mountinfo identities, namespace-copy isolation, detached directory FDs
and slot reuse. The native mount-graph fixture also checks all 512 slots, copies,
exact FD cleanup and graph transport beyond one SCM_RIGHTS message.

`test_mqueue_mount.py BUILD STORE` checks the limited IPC-owned mqueue filesystem:
mount/fsopen capture, shared metadata, isolated owners, setns, copied/bound mounts,
readonly policy, detached descriptors and explicit unsupported queue operations.
It requires Python, util-linux and strace, but no compiler or host mqueue support.
The native resource-owner fixture separately checks final-owner tree/FD cleanup.

`test_device_bind.py BUILD STORE` checks logical character/block-device creation,
proc-FD bind targets, native rdev/IO, nodev and detached FD lifetime.
Regular-file/FIFO creation and non-root denial remain covered. It requires
Python and util-linux. `/dev/full` binding and metadata are required even when
Android denies data access; the bound path must preserve that denial.
Native `/dev/full` IO is conditional on Android shell
access; a denied source is reported separately, not counted as successful IO.

`test_cgroup_bpf.py BUILD STORE` checks permissive cgroup2 device-program
load/info/ID lookup, multi-attach, detach, inherited queries and removed-group
lifetime. Coverage includes dup/fork/protected exec, SCM_RIGHTS, readonly
targets, full FD tables, ENOSPC/EFAULT, versioned attributes and repeated exact
warmed FD cleanup. It verifies that return-zero programs do not enforce device
restrictions and that network hooks/maps/BTF remain unavailable. The store
needs Python and util-linux, not host BPF permission or a compiler.

### Networking And Container Engines

`test_network_sysctl.py BUILD STORE` checks namespace-owned IPv4 parameters,
bounded parsing, coordinated port ranges, namespace isolation and writer
authority. It includes retained FDs across unshare, shared dup offsets, positional
IO, O_PATH metadata and readonly remount/detach enforcement. Host sysctls are not
written. The prepared store needs Python and util-linux.

`test_routed_network.py BUILD STORE` exercises opt-in routed IPv4 with stock
iproute2: bridge/veth topology, namespace-FD moves, routes, TCP/UDP, addressed
messages and teardown. The native resource-owner tests cover atomic netlink
mutation/ACK rollback, port conflicts, credentials and endpoint ownership.

`probe_nft_runtime.py BUILD STORE` exercises stock nft from a prepared Linux
store with Python, util-linux and nftables. It checks tables, chains, rules, sets,
expression encoding, referenced-chain deletion, private namespaces, atomic
rollback and a 150-table batch larger than 4 KiB. Unsupported expressions must
fail without committing earlier changes.
`test_network_nft`, included in `test_task_resources`, additionally covers
generation conflicts, response-publication failure, insufficient buffers,
permissions, malformed attributes, control-socket framing, hook validation,
jump/return semantics, reverse-direction bypass eligibility and finite NAT publication.

`probe_nft_traffic.py BUILD STORE` adds actual TCP/UDP filtering, established-flow
policy changes, addressed UDP state, source-conditional DNAT and published-port
rollback/cleanup observed from another Android UID. It checks atomic `EBUSY`
when attempting to revoke direct native IO. Five-sample loopback direct/filter/
NAT benchmarks report TCP echo throughput and addressed UDP round trips; run
without concurrent suites for meaningful timing, not as a fixed performance gate.

`probe_podman_runtime.py BUILD STORE --exercise-ports` runs stock
Podman/crun/conmon/netavark with overlay, bridge/nftables, logical cgroups and
application seccomp. It checks DNS/HTTP, two named peers, repeated TCP/UDP port
publication, collisions and cleanup. Ordinary acceptance uses Shroot's logical
NOFILE/NPROC limits without container overrides. The optional diagnostic
`--host-limits` sets them to inherited shell ceilings; that control is not a
substitute for testing normal defaults. The store needs these
packages plus Python and nftables. Optional `--trace` records exec, DNS-service
and removal calls; its tracer drains after network teardown.
`--cache` reuses only the fixture's disposable image storage.

`probe_docker_runtime.py BUILD STORE --profile routed --cgroup v2
--storage-driver overlay2 --image python:3.13-alpine --exercise-ports` runs stock
Docker/containerd/runc under the selected executor. It requires those packages
in a disposable Debian store. Assertions cover two containers with identical
internal ports, bidirectional peer exchange, outbound DNS/HTTP, TCP/UDP `-p`
publication from an observer outside Shroot, conflict rejection, port release
and daemon shutdown. VFS is selectable independently. Diagnostic profiles
without `--exercise-ports` record capabilities, not passing port coverage.
Both engine fixtures perform twenty resolver queries per container and record
their timings. A failed query retains the failure and adds raw A/AAAA diagnostics;
it is not retried into a passing result. Internet DNS/HTTP requires a working
external network, separately from local port and peer-exchange checks.

### Proc, Sysfs And Desktop Services

`test_proc_mount_policy.py BUILD STORE` checks omission of unsupported host-kernel
controls in namespace-mounted proc, consistent lookup/enumeration, ordinary proc
binds and readonly remounts. It never writes host control files.

`test_sysfs.py BUILD STORE` checks the logical offline sysfs provider through
legacy mount and stock util-linux's descriptor API. Coverage includes live
netlink/ioctl agreement, namespace capture and authority, namespace copying,
file/directory binds, detached descriptors, cgroup nesting, metadata and
unsupported mutation errors. It also checks that native truncate cannot modify
the carriers of sysfs or cgroup controls. The prepared store needs Python,
util-linux and strace.

`test_glycin_runtime.py BUILD STORE --library libglycin-2.so.0` invokes the real
distribution loader with Bubblewrap and its original seccomp filter. It asserts
the decoded RGB pixels, not just process startup. The explicit
`--fixture-deb PATH` option extracts additional packages only into the selected
disposable test store; `--trace` retains diagnostics and strace output. It must
not be used against a user's installed environment. Glycin 1.2.1's ARM64 filter
can reject the libc uname call used for SVE detection; Glycin 2.x includes the
upstream correction. Selecting a newer loader does not disable sandboxing.
`--format png|svg` selects exact pixel checks; Fedora 44's stock Glycin 2.1.5
passes both formats under UID 2000.

`test_proc_root_runtime.py BUILD STORE` checks foreign proc-root traversal,
retained roots after target exit, credential rejection, protected readers,
live fdinfo position/flags/close state and logical pidfd fields. Foreign FD
stat/statx/readlink cover sockets, pipes, deleted files, namespace handles,
close/reuse and target exit; cwd and executable metadata select guest objects.
It runs with
implicit proc and with stock `unshare --pid --fork --mount-proc`.
Foreign mount-table checks use a different root and mount namespace, verify
openat2 constraints, O_PATH/error behavior, POLLPRI changes and retained reads
after target exit. The workflow uses stock libraries and the default lazy entry.
Its selector fixture checks proc/watch/native reuse in both filter orders,
fork/exec retention, and typed netlink duplication through dup, dup3 and both
fcntl commands. Independent child cases check flags, source closure, inherited
aliases, native-width command decoding and actual netlink response readiness.

`test_thread_identity_runtime.py BUILD STORE FIXTURE --debuggers` uses the glibc
`md-thread_identity-fixture` built by `build-libc-fixtures.sh`. Ordinary and
nondumpable callers exercise recursive pthread mutexes, shared-memory clone
parent/child TID fields and kernel child clearing. Optional installed strace/GDB
must observe normal completion inside a PID namespace.
The fixture also checks explicit PI-futex rejection in a logical PID namespace,
libc's PI-mutex admission and ordinary-mutex fallback, with private/shared and
clock flags and nondumpable callers. The native PID view retains working PI
mutexes. Both workflows use stock distribution libraries.

`test_desktop_services_runtime.py BUILD STORE` checks a stock system D-Bus daemon,
authentication from the `developer` account and PipeWire's real `pw-cli info 0`
request. It creates a private PID/proc/run view and waits on daemon output;
no systemd boot or host bus is required.

`test_gnome_runtime.py BUILD STORE` runs Fedora 44 GNOME devkit in that private
service arrangement with PipeWire/WirePlumber and llvmpipe. The disposable store
needs GNOME Shell, GNOME Text Editor, the `developer` account and their ordinary
distribution dependencies; the host needs ImageMagick. It creates its own virtual
display, checks rendered content and Alt+F2, launches the editor and verifies
both its bus registration and visible window. Screenshots and the exact launch
recipe are retained in the report. Cleanup stops the graphics session and removes
the display. `--trace` adds strace diagnostics. This is not a GDM login, booted
systemd session or GPU test.

`browser/test_browser.py --build BUILD --store STORE` checks stock Chromium's
sandbox-enabled user/PID route and JavaScript execution. These application
checks do not certify kernel-equivalent confinement or Docker Engine support.

`test_native_reads_runtime.py BUILD STORE` checks inode, bind and native reads
under shell, including protected callers and managed proc-FD observations.
Bind aliases retain the opened descriptor's exact mount provenance.
Process credentials, kernel dumpability and logical namespace handles retain
their respective access rules. The filesystem directory suite also checks
direct worker observations against FD reuse, occupied table turns and typed
provider fallback, including exact descriptor cleanup. Authenticated native
service owners use native inspection rather than guest-table admission;
nondumpable owners retain the caller-side permission check.

## Guest Credential Checks

CMake's `MAGICDESK_GUEST_FIXTURES` builds `test_credentials` for the identity,
account resolution, registry and inode contracts, plus `credentials_guest` for
intercepted calls. The device runner accepts a prepared device-side OCI layout:

```sh
python native/guest-exec-lab/test_credentials_runtime.py BUILD DEVICE_LAYOUT --packages debian
python native/guest-exec-lab/test_credentials_runtime.py BUILD DEVICE_LAYOUT --packages alpine
```

The runner requires actual UID 2000 and creates its own preserved-ownership image
and instance. It checks independent root/non-root launches, chmod/chown/access,
hardlinks, group copying and EFAULT, nondumpable processes, per-thread raw IDs,
libc set-ID coordination, fork/exec, auxv and irreversible credential drops.
Signal checks run without a PID namespace: foreign launch/guardian targets are
rejected, including pidfds, and kill/tkill/tgkill/queued sends enforce guest IDs.
A Nix-style `setuid` plus broadcast `SIGKILL` must terminate only the matching
guest user; harmless permission probes precede it so an unfixed runtime fails
without broadcasting a destructive host signal. Same-process sends remain valid
during libc's per-thread set-ID coordination. The `signals` fixture argument
runs this subset independently of the filesystem checks.
Access checks include real/effective IDs across the whole path, empty-path
descriptor/current-directory access, fchdir and inotify registration permissions.
`--fixture ELF` additionally runs a glibc/musl build of `test_credentials_guest.c`
against the matching image. Bionic's raw set-ID wrappers are intentionally
thread-local; glibc/musl coordinate the same calls between threads.
`--packages` exercises ordinary package install/reinstall/remove scripts without
force-not-root flags. Kernel privilege remains separate from virtual guest root.
Reports retain every command and exact image/build.

`test_ipc_credentials_runtime.py BUILD DEVICE_STORE` stages the native helpers
and exercises connection/group snapshots, queued SCM_CREDENTIALS, credential
drops, forgery rejection, MSG_PEEK, recvmmsg, truncation, FD passing and an external
native endpoint. Peer queries cover full ordinary/protected FD tables, empty
and maximum 65536-group snapshots, short buffers and output-copy faults.
The native RPC fixture separately checks large segmented replies, rejected
delivery and malformed/missing continuation packets without leaking FDs.
The selected prepared store supplies stock D-Bus and a root NSS
entry. Independent root clients share its bus; a different guest user is rejected.
`--installed` uses the APK's CLI/bundle instead of staging execution helpers;
only the test programs are uploaded. `--gdbus` adds a stock GIO client.
`--custody` adds strict native/guest comparisons for terminal adapter death with
a surviving CLONE_FILES peer, both ordinary and nondumpable. Guest send/receive
cases currently expose retained identity or unpublished message descriptors;
native controls release them. This opt-in reproducer remains a failing ownership
check, not a passing baseline or evidence of complete ancillary custody.
For Debian's activation/FD workflow:

```sh
sh native/guest-exec-lab/build-ipc-dbus.sh PREPARED_SYSROOT BUILD/ipc-dbus
python native/guest-exec-lab/test_ipc_credentials_runtime.py BUILD DEVICE_STORE --gdbus --activation BUILD/ipc-dbus
```

The activation fixture installs/removes only its own service file in the disposable
store. It checks a stock daemon's GetConnectionUnixUser, service activation and
usable descriptor delivery in both directions, under guest UID 0 and actual
UID 2000. No Android Desktop, real elevation or authentication override is used.

`test_guest_accounts_runtime.py BUILD DEVICE_STORE [--installed]` uses a disposable
Debian store with the stock passwd and D-Bus packages. It creates, modifies and
removes fixture-owned users/groups, verifies named-launch NSS/group resolution
and home ownership, and checks that a denied non-root groupadd leaves group files
unchanged. Pending package configuration must finish with both D-Bus packages
installed, a messagebus account and a clean dpkg audit. Package service-start
policy is not replaced. The runner's native/guest controls compare Unix, IP and
other netlink results while audit is explicitly unavailable in the guest.
CMake builds `guest_interfaces`; it checks raw calls, protected tasks and
application ERRNO/TRAP/KILL precedence under root, nobody and shell guest IDs.
