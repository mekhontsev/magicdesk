# Kernel Compatibility

- [Baseline](#baseline)
- [Newer Interfaces](#newer-interfaces)
- [Validation Limits](#validation-limits)

## Baseline

Shroot's minimum supported kernel is **Linux 5.15**. This is a design/support
boundary, not a claim of complete device coverage. The MagicDesk APK remains
Android 14 / API 34 and managed Desktop remains Android 15 / API 35; neither
acquires Shroot's kernel requirements. Shell tools, Termux, prepared chroot and
graphics services remain independent when guest execution is unavailable.

### Guest FUSE

Guest FUSE advertises protocol 7.31, with older daemon negotiation, through a
typed guest `/dev/fuse` and the existing descriptor transport. It does not open
the host FUSE device, mount a kernel filesystem or require CONFIG_FUSE_FS.
Requests use baseline event notification, private sealed memfd snapshots and
O_TMPFILE-backed read-only shared mappings with ordinary pwrite/ftruncate;
there is no post-5.15 host dependency. `test_fuse_transport`, `test_fuse_device`,
`test_fuse_filesystem` and the stock rofiles/portal/squashfuse fixtures cover
protocol, cancellation, publication and teardown. See [FUSE limits](fuse.md).
`fuse_snapshot` reuses sealed images across exec/mmap with baseline F_DUPFD_CLOEXEC,
FOPEN_KEEP_CACHE and explicit inode invalidation. Its bounded cache adds no host
kernel dependency or work to ordinary native-backed IO.

### Native Storage

Native-leaf storage uses baseline openat/fstat/fchmod, user xattrs, proc FD magic
links and fsync. `inode_native.c` uses the retained proc alias for O_PATH metadata,
not fchmodat2/AT_EMPTY_PATH. `inode_publication.c` uses renameat2 with
RENAME_NOREPLACE and a shared lock-free 32-bit atomic hint; unsupported native
filesystem operations return their errors. These primitives add no post-5.15
dependency or shared startup probe. Store admission tests O_TMPFILE and user
xattrs before enabling eligible native creation; denial keeps the catalogue
allocator. Mixed directory enumeration uses getdents64 and bounded OFD cookies,
with persistent mappings for large filesystem cookies. None requires a newer
kernel. `test_native_leaf`, `test_publication` and the stores
runner cover UID-2000 operations, process interruption, error propagation and
portable backup/restore; they do not establish physical power-cut durability.

Unpublished ordinary native births may use baseline `mknodat(S_IFREG)` plus
O_PATH instead of a temporary data-open description. This creates no device and
requires no CAP_MKNOD. `inode_native` returns pre-effect EOPNOTSUPP on host
EPERM/EACCES/ENOSYS/EOPNOTSUPP; `inode_leaf` retains ordinary prepare/open for
that case and for exceptional metadata/ACLs. Other errors are not retried.
`test_native_leaf` covers injected denials, exact native events, one-slot FD
capacity and EMFILE cleanup; the cooperative runtime covers guest admission,
publication, cancellation and watches. No kernel minimum or storage change.

### Input And Network Providers

Guest TUN/TAP uses namespace-owned lwIP and the typed device provider, not
host `/dev/net/tun`, Android VPN APIs or host `CAP_NET_ADMIN`. Its advertised
ioctls predate 5.15. Socketpairs, eventfd, timerfd and epoll drive bounded IO on
the existing network loop; no newer kernel primitive is required. Virtio-net
headers, endian/size controls, checksum completion and TCPv4/TCPv6 segmentation
are implemented in the packet adapter, not delegated to a host TUN driver.
Legacy bridge, interface-list and ARP ioctls also predate 5.15. Unsupported
offloads and device options fail explicitly. Coverage: `test_lwip_context`,
`test_network_tun`, `test_network_ethernet.py`, rootless Docker/Podman with stock
slirp4netns, OpenVPN and a libvirt-managed VM network.
The common socket IO owner treats `MSG_ERRQUEUE` as an immediate query, including
on blocking sockets; an empty error queue returns `EAGAIN` without an IO waiter.
`supervisor_state` covers IPv4/IPv6 error-queue admission.

The optional gamepad bus uses baseline Unix stream/datagram socketpairs,
eventfd, poll/epoll, SCM credentials and the existing typed descriptor transport.
Evdev/joydev/uinput and udev discovery are guest providers; no native uinput
permission, input-driver module, device-node creation or global kernel uevent
subscription is required. The exported input ioctls predate 5.15. Unsupported
device classes fail explicitly. `test_input_devices`, `test_input_uinput`,
`fs_dispatch --input` and the guest/SDL fixtures cover these boundaries.

The namespace-local nftables adapter implements a supported subset of the
[Linux 5.15 nfnetlink ABI](https://github.com/torvalds/linux/blob/v5.15/include/uapi/linux/netfilter/nfnetlink.h)
over the existing Unix datagram carrier. Generation, tables/chains/rules/sets and
atomic batches require neither host NETLINK_NETFILTER permission nor CONFIG_NF_TABLES.
`network_flow` and `network_forward` apply IPv4 TCP/UDP flow filtering and NAT
using ordinary sockets, epoll, eventfd and nonblocking IO, all predating 5.15.
Unsupported expressions fail before publication; direct-flow revocation returns
`EBUSY`, and native backing ports are not kernel-firewall isolated.
`test_network_nft`, `probe_nft_runtime.py`, `probe_nft_traffic.py` and the stock
Podman/Docker fixtures cover protocol, atomicity, traffic and cleanup. No
post-5.15 host primitive or new shared-startup requirement is added.
Verdict maps, rule/base-chain logical counters, revision-zero xt TCP/UDP and
DNAT/SNAT adapters, bridge hairpin control, netlink credential snapshots and
IPv4 IP_PKTINFO use baseline userspace wire formats
and existing socket/relay owners. They introduce no newer host dependency.

### Clock And Capability Admission

Cooperative-entry callers use iteration-bounded spinning followed by the existing
futex handshake, without a borrowed clock mapping or hardware timer dependency.
The separate worker retains its monotonic clock and time-bounded service budget.
These mechanisms predate 5.15. Cooperative runtime/libc fixtures cover execution,
cancellation and filter fallback; the debugger fixture also runs stock Callgrind,
whose loader removes the initial vDSO mapping. No shared-service prerequisite is
added.

Admission uses actual operations under the selected executor identity, not an
Android-version inference or `uname`-only capability check. Kernel configuration,
SELinux, application filters and vendor backports affect availability. Headers
merely allow compiling an operation; they do not make it available at runtime.

### Limits, Cancellation And Landlock

`process_limits` implements launch-local `RLIMIT_NPROC` accounting using the
existing clone/identity/exit events, task-affine ABI copies and typed proc files.
Its user-namespace accounting follows Linux 5.15 semantics and introduces no new
host syscall, kernel feature or shared-startup probe. `test_nproc.py` covers
native and PID-scoped guest views, protected copies, quota/reaping and seccomp.
The same owner virtualizes `RLIMIT_NOFILE`, applying native soft-limit changes
with baseline `prlimit64` while retaining the inherited executor hard ceiling.
Foreign-limit reads may be denied by Android SELinux and are not required.
Native write failures propagate without publishing the logical update. A
read-only guest `fs.nr_open` describes the logical ceiling, not host capacity.
The fixture also covers lower/re-raise, foreign protected targets, exec/thread
inheritance, FD exhaustion and received-rights truncation in both entry modes.
Native real-time scheduling and other host resource ceilings remain authoritative;
the packaged RTKit fixture records scheduler `EPERM` rather than masking it.

Launch cancellation uses baseline `timerfd_create(CLOCK_MONOTONIC)` and
`timerfd_settime` in the supervisor's existing poll loop. The descriptor is lazy
for unbounded launches; admission errors fail that launch, never shared services.
`test_resources.py --case launch-cancel` covers idle/busy, dumpable/protected
groups, ignored TERM, exact reaping and the distinction from cleanup failure.

Guest Landlock filesystem ABI 1 is implemented by `landlock_policy`, the task
resource owners and the filesystem worker; it does not require the host's
`CONFIG_SECURITY_LANDLOCK` or become a shared-startup prerequisite. Its syscall
numbers and filesystem rights predate the 5.15 baseline. Newer ABI rights are
rejected, not advertised as enforced. Native host queries retain the kernel's
own result. `landlock_guest` and `test_arch_runtime.py` distinguish that query
from actual guest permission checks. The same ABI 1 assertions run under UID
1000 in a reference Linux 6.18 QEMU VM; that reference does not raise Shroot's
minimum. See [filesystem restrictions](../../docs/shroot.md#filesystem-restrictions)
for provider limits and the difference from native security confinement.

### Syscall Entry And FD Publication

The baseline includes pidfds, selective ptrace, seccomp notifications,
`openat2`, `faccessat2`, `close_range` and shared/sealed memfds. The native read
provider uses `openat2(RESOLVE_NO_SYMLINKS, O_PATH|O_NOFOLLOW)` to pin admitted
task-independent paths. A missing or denied operation retains task-affine
dispatch; self aliases and private proc views do not use this optimization.
These flags predate the 5.15 baseline. In particular,
atomic `SECCOMP_ADDFD_FLAG_SEND` is a required FD-publication primitive, available
since Linux 5.14. Separate ADDFD and reply calls are not an equivalent fallback:
cancellation between them can leave an installed descriptor without a confirmed
result. `CLOSE_RANGE_CLOEXEC` is available since 5.11. Both fit the 5.15 baseline.
See the [seccomp notification contract](https://man7.org/linux/man-pages/man2/seccomp_unotify.2.html)
and [close_range flags](https://man7.org/linux/man-pages/man2/close_range.2.html).

Optional `cooperative_entry.c` / `namespace_broker.c` communication uses the
existing shared memfd, ordinary shared `FUTEX_WAIT`/`FUTEX_WAKE`, monotonic clock,
checked process-memory copying and notification wake gate. It adds no post-5.15
kernel primitive. Runtime admission failure selects the caller-owned SVC
continuation; installed application filters do not acquire an unfiltered direct
route. Kernel-carried `openat`/`fsopen` records retire at exact notification
admission. Eligible native-leaf and host-permission catalogue births use an
unpublished backing and baseline `renameat2(RENAME_NOREPLACE)` after the caller's
data-open effect; catalogue publication also uses its existing SQLite commit.
Other providers retain their original carrier. Storage format 17 is unchanged.
Existing regular-file plans, including writable/append/truncate opens, retain
one engine-owned O_PATH pin and use
a native guest `openat` through `/proc/SUPERVISOR/fd/PIN`, with exact-PC admission
in Shroot's filter. `close`, duplication, regular-file seeks and FD flags use the
same file-table owner and native result journal. Stop recovery uses existing
GET/SETREGSET on AArch64; cancellation preflight invokes libc's own check in the
caller-owned action driver, without a synthetic syscall. None requires a post-5.15 kernel
operation. Denied native reopens return their real errno; unsupported providers
decline before a data-open effect, not after it. A 112-boundary instruction-stop
fixture checks actual open/close/flag results under shell UID 2000. Deferred
FIFO completion uses the existing `ppoll`, ptrace interruption/restart and
owned-child reaping, not a new cancellation syscall or polling timer.
Its host helper shares the `interruptible_call` entry gate with native IO jobs:
baseline SIGUSR2/rt_sigaction, shared anonymous mappings and futexes close the
check/SVC race and preserve peer admission. `test_open_job` covers cancellation
before entry, while unpaired and after the peer has opened; no guest signal
disposition or per-read/write interception is added.
`test_cooperative_runtime.py` covers admission, exhausted pools, signals,
fork/exec, protected pointers, namespace changes, signal-handler memory
abandonment, argument/IP filter policy and TSYNC revocation. The isolated musl
integration additionally checks real libc threads, cancellation and exec,
cwd/dirfd replacement, FIFO signals/restarts and exact FD retirement.
The [internal entry boundary](cooperative-entry.md) uses a bounded userspace
offer-table lookup, a fixed opaque handle and caller-compiled actions with
ordinary unwind metadata.
Runtime progress/feedback reuse the existing channel;
there is no new allocation syscall, loader dependency or kernel capability.

Notification read-window batching relies on the baseline listener poll and
RECV cancellation contract, not on O_NONBLOCK (RECV does not honor that flag).
`namespace_broker.c` is the sole receiver and checks zero-time POLLIN before each
additional receive. On cancellation after readiness, Linux 5.15 consumes the
pending request count and returns ENOENT when no INIT notification remains;
it does not wait for a future guest call. The native notification-lifecycle
fixture repeats that exact readiness/cancellation/receive ordering 32 times,
holding the child before its next syscall, and checks readiness is then clear.
Batch limits, provider changes and every handoff release the same bounded store
scope as cooperative calls. No WAIT_KILLABLE_RECV or newer-kernel behavior is
required. Full watch, buffer, signal and continuous-reader/writer fixtures cover
the shared worker's cancellation and fairness paths.

Ready open results may reuse a ptrace-stopped syscall through the existing fstat
notification route without changing application registers or its return PC.
The owner is `supervisor.c`, with `namespace_broker.c`
and `fd_install.c` retaining FD custody. This uses the
[5.15 post-TRACE seccomp recheck](https://github.com/torvalds/linux/blob/v5.15/kernel/seccomp.c),
NT_ARM_SYSTEM_CALL and syscall-exit observation, not a newer notification flag.
The broker matches the complete original call context, including ARM64's
preserved orig_x0, before normal fstat decoding. Confirmed replies avoid another
register read at the retained syscall-exit stop. Interrupted notification delivery
retains the completed result and uses the existing shielded task-affine gate;
protected inputs, application filters and debuggers keep their existing route.
`test_resources.py --case fs-buffer` covers these branches, masks, CLOEXEC,
mutation signals, preserved registers/NZCV, file-table pressure and group termination.
`--case fs-dispatch` also checks both carriers' exact argument matching, rejected
replay, confirmed results and FD cleanup.

Direct worker FD replies use the same baseline ADDFD_SEND primitive. Cancellation
can report ENOENT, or ESRCH after the receiver discards an already queued ADDFD;
the latter is accepted as cancellation only after ID_VALID confirms expiry.
`notification_result.c` retains the result; `namespace_broker.c` prevents another
execution during supervisor handoff, including kernel restarts without a delivered
signal. Nonblocking metadata recovery in `supervisor.c` uses baseline ptrace
register/signal-mask access and GET_SYSCALL_INFO (5.3), not WAIT_KILLABLE_RECV
(5.19). It rechecks the original SVC through kernel seccomp before completing
the read and restoring the real signal. `--case fs-buffer` compares native and
guest shared-memory creation under frequent signals, with and without SA_RESTART,
protected callers, preserved registers/NZCV and masks, application ERRNO/TRAP
filters, and concurrent SIGSTOP/SIGCONT cycles. ARM64's temporary x7 marker at
syscall stops is never copied back into the application context.

### Cross-Process Rendezvous

Private launch rendezvous reuses the registry's flags-zero pidfd, proc-descriptor
identity and file locks, plus Unix seqpacket/SO_PEERCRED and nonblocking IO. It
adds no newer-kernel primitive or shared-startup requirement. Discovery failure
does not grant an alternative identity or enable application FD framing.
Candidate message routing uses a fixed header, `SO_COOKIE`, descriptor-free
`recvmsg(MSG_PEEK | MSG_DONTWAIT)` and a `getrandom` nonce. Scope retention shares
existing flock leases. These operations also fit the baseline; no new pidfd
flags, socket diagnostics or per-IO capability probe is required.
Creation-time ancillary-format selection uses the existing IPC metadata gate,
SQLite transaction and cookie binding. It adds no kernel primitive or probe;
ordinary read/write remain kernel-owned.

### Mount Policies And Graphs

Shared mount policy uses memfd size seals plus `F_SEAL_FUTURE_WRITE`, present in
the [Linux 5.15 seal ABI](https://github.com/torvalds/linux/blob/v5.15/include/uapi/linux/fcntl.h#L29-L41),
and lock-free ARM64 shared-memory flag loads/stores. Publication is lazy and
explicit: unavailable memfd/sealing returns an error while retaining local
policy. It adds no shared-startup requirement and never substitutes a stale copy
for the requested live policy. `--case mount-policy` checks rejected memfd,
mapping and sealing operations, including a native seccomp-injected seal denial.
Guest `MS_NOSYMFOLLOW` and `MOUNT_ATTR_NOSYMFOLLOW` use the common mount policy and
component walker, not a host mount. Their semantics and the distinct
`ST_NOSYMFOLLOW` statfs bit exist in the
[Linux 5.15 ABI](https://github.com/torvalds/linux/blob/v5.15/include/linux/statfs.h).
`test_mount_api.py` covers detached tmpfs, native attachments, proc links, bind
aliases, remount and namespace copies; `--case mount-policy` also checks a bare
inode root with no attachments. Ordinary data IO needs no additional interception.
Native `/proc/self/mountinfo` snapshots use its baseline namespace-generation
`poll(POLLPRI)` notification, with a zero timeout for observation, not a wait.
The namespace owner retains the source descriptor and invalidates cached native
entries on change or observation failure. Changes during construction prevent
reuse; guest mounts and namespace-relative IDs are generated per request.
`test_native_mounts` covers reuse, invalidation, interrupted/failed observation,
changes during reading and descriptor release, plus a real kernel-source read.
It is included in `test_resources.py --case stores`; no newer kernel primitive
or startup probe is required.
Portable attachment graphs use the same baseline memfd interface with immutable
`F_SEAL_WRITE` manifests, O_PATH directory rights and CLOEXEC duplication. They
add no newer-kernel requirement or startup probe. Graph publication/import fails
explicitly if those operations are unavailable; no pathname-reopening fallback
substitutes for retained directory identity.
Live graph publication adds nonblocking Unix seqpacket queues and the existing
bounded SCM_RIGHTS bundles. Mounted-origin lifetime uses one `O_TMPFILE` carrier
per exported graph and independent `F_OFD_SETLK` byte-range leases, all available
within the 5.15 baseline. Retirement and normal unmount acquire exclusive leases
without waiting; imported origins refresh their generation under a shared lease.
These operations run only on explicit graph publication/transfer or mount changes.
Failure does not change the selected identity or require them for other services.
Base-root relocation uses that same userspace graph, O_PATH custody and origin
leases. Guest pivot_root does not issue a privileged host mount/pivot syscall or
add a kernel capability requirement.

### Namespace Events And Readiness

Guest UTS and SysV IPC namespaces also use supervisor-owned resources, not host
namespace privileges. Their clone/unshare adapters remove only the implemented
namespace flags before the ordinary kernel transition and commit on its exact
success event. UTS argument copies reuse the mapped-buffer transport; IPC uses
the existing store locks, getrandom identities and owner leases. These add no
post-5.15 dependency. Guest CLONE_NEWUSER and typed proc map writes follow the
5.15 UID/GID mapping and CAP_SETFCAP rules without requiring host CONFIG_USER_NS.
UTS sysctl files use the same lazy descriptor-selector mechanism as map files.
Their internal notification model uses baseline eventfd, epoll, atomic references
and a source mutex; it requires no kernel change above 5.15. Independent opens
own generation cursors and lazy wake descriptors. The virtual-interest collector
is connected to selected guest epoll queues. Native `epoll_pwait2`
(Linux 5.11) shares caller-context wait ownership; unsupported or denied native
calls retain their kernel error. Pure native waits allocate no notification
helper; virtual sources use the existing cancellable IO job without sampling.
Weak descriptor authorities use lazy eventfd notifications and userspace mutexes
within the same baseline. Subscriptions borrow a strong reference only while
accessing the provider; final release publishes expiry before destruction.
`control-event` covers acquisition/retirement races and failed admission;
`ofd-order` covers table and queued-transfer custody. No native epoll nesting,
kernel OFD reference-count inspection or newer syscall is required for this
internal lifetime mechanism.
Epoll creation custody and optional native-export reservations reuse the
existing file-table terminal observation, SCM_RIGHTS and userspace atomics.
They add no post-5.15 dependency, retained native epoll duplicate or kernel
nesting level. `control-event` requires zero interception of unfiltered native
zero-time waits with lazy entry (creation alone is observed); Base blocking
waits retain supervision for cold virtual admission. `ofd-order` covers export rollback
and activation races, and `ipc-origins` covers independent receiver custody.
Selector construction and staged epoll activation add no kernel API. The cold
remote-admission fixture uses baseline ptrace register/signal-mask access and
seccomp TSYNC, preserving the application's filter decision. It compares native
interrupt/restart behavior under dumpable and protected tasks; production
cross-owner admission also has guest coverage with protected foreign readers.

### Task-Affine Calls And Checkpoints

The common task-call executor also uses baseline `PTRACE_GET_SYSCALL_INFO` and
`NT_ARM_SYSTEM_CALL` to restore syscall entry/seccomp/exit boundaries without
repeating side effects. Restart-code exits use the kernel-restarted entry to
recover the private original argument, then rearm baseline PTRACE_INTERRUPT when
restoring the exit. This preserves restart processing without a pending real
signal. Native UID-2000 fixtures cover both dumpable and protected tasks,
signal-induced and synthetic-interrupt restarts, and IP-scoped TRACE filters.
Native-call completion uses SYSCALL exit rather than executing a return BRK.
A baseline PTRACE_INTERRUPT yields a non-syscall boundary before restoring USER
registers, including ARM64's hidden-at-syscall-stop x7. The executor owns these
interrupts and its resume mode across foreign interrupts and group-stop wakes.
Injected ERESTART results wait for the kernel's restarted entry, including
restart_block via restart_syscall; they are not completed results or userspace
retries. Tests retain blocked SIGTRAP/SIG_IGN, masks, pending signalfd readiness,
original event types and STOP/LISTEN/CONT across read, ppoll and timer restarts.
EXIT reconstruction uses baseline ARM64 PTRACE_SYSEMU to recreate the original
entry/exit without executing a synthetic syscall through the filter. The
combined fixture retains KILL(-1) at every boundary. Injected native calls
remain subject to the filter. A forced SIGSYS unblock at their exit retains
native KILL_THREAD termination rather than restoring a saved blocking mask
over it; a syscall-exit stop alone does not establish successful admission.
These calls do not lend the signal-mask syscalls themselves. See the common
checkpoint owner below for SYSEMU admission and device coverage.
Borrowing a stopped checkpoint retains its decoder state. An emulated entry
drains the kernel's skipped exit before injection and returns through SYSEMU;
an ordinary entry still executes its replacement directly. Native fixtures
cover every checkpoint stage, repeated borrowing, protected tasks, filter
denials and exact-once guest IO. No newer kernel primitive is required.
The bootstrap's classic seccomp program can encode an ordinary close's low-int
FD in TRACE's 16-bit data, read through baseline PTRACE_GETEVENTMSG. Large and
negative descriptors retain full capture. Ordinary close skips an entry register
read but retains actual syscall-exit observation and table ownership. Scalar
filesystem receipts use the existing stopped-task result publication, with no
new seccomp flag, ioctl or post-5.15 dependency.
Sandbox-wide ordinary-IO admission uses that same GET_SYSCALL_INFO interface
to read the real call/arguments and resume it unchanged. It retains the installed
selectors and application filters. `test_cooperative_libc.py --sandbox` covers
valid-FD seccomp denial, EFAULT and ordinary scalar/vector IO; resource models
reject typed, unknown, provisional and shared-table shortcuts.
Production proc/watch selector installation uses this task-affine owner through
the mapped adapter frame, without guest FDs or ptrace writes to application
memory. Its payload uses reserved control capacity in the same mapping so IO
pool exhaustion cannot create a table-turn dependency cycle.
Guest selector/proc, Chromium sandbox, private-memory and native-wait fixtures
exercise that path. Single-task admission can fall back from denied seccomp
TSYNC to baseline PR_SET_SECCOMP, only after excluding siblings and private
helpers. Native fixtures cover that application-filtered path at all supported
continuation boundaries. The inventory/admission modules add no kernel API.

### Native Wait Context

Mixed-queue native waiting preserves the caller's signalfd and temporary
signal-mask context when the caller exclusively owns its file table. This uses
the existing ptrace syscall boundary and exact native descriptor, not a new
kernel operation or a guest FD. Guest tests cover pending/process signals,
virtual wakeups, simultaneous ready events/signals, equal event data and filtered
cross-owner queue activation.
Shared-table waits use the same caller context with an atomic ADDFD_SEND loan
of the captured queue. Loan retirement reuses baseline close/close_range and the
common short-call/quiescence owner. Regression coverage includes exact-number
reuse, close_range, fork/unshare while waiting, retained CLOEXEC, denied close,
policy-rejected retirement and terminal cleanup through a surviving owner. No new kernel API, nested epoll
or post-5.15 dependency is introduced.
PTRACE_SETSIGMASK clears pending kernel saved-mask restoration,
so it cannot be used blindly at an interrupted epoll/ppoll exit. This semantic
requirement is part of the 5.15 baseline, not a reason to raise the minimum.
Named-FIFO readiness uses baseline epoll, shared read-only mappings and the
existing rendezvous generation file. Poll/select native waits reuse that
task-affine ptrace boundary even for shared file tables; provider helpers never
wait on the application's ordinary descriptors. Channel rejoining uses existing
O_PATH pins, flock leases and authenticated origin parcels. These paths add no
post-5.15 requirement. `test_fifo_runtime.py` covers initial hangup suppression,
signalfd, temporary masks/EINTR and transfer across sender exit and receiver exec;
`fs-buffer` covers contiguous mapped loans and terminal cleanup.
The live inventory fixture combines those baseline operations with CLONE_FILES
and fork, covering distinct thread groups over shared/copied tables and rollback
after one group's application filter denies seccomp. Quiescence loans retain
existing ptrace event/rusage and resume ownership without another kernel API.
Logical IPv4 sysctls, including forwarding policy, use the shared typed-control transport and retained mount
anchors; numeric values and permissions are launch-owned state, not host sysctl
writes. They add no post-5.15 interface. Foreign proc executable selection reuses
the existing process-image inspection, worker preparation and anonymous-image
custody; it does not add a kernel exec or proc-FD capability requirement.
Local typed SCM_RIGHTS custody adds a pipe EOF to the existing worker event loop;
it needs no newer kernel API, polling loop or additional worker thread.

### Process Birth And Proc Views

PI futex words contain owner TIDs and cannot use native PI state inside a
logical PID namespace. The PID selector leaves ordinary futex waits and wakes
native, but rejects PI acquisition/requeue with EOPNOTSUPP and PI unlock with
ENOSYS. The distinct unlock result preserves glibc's capability probe; musl
propagates the acquisition result from `pthread_mutexattr_setprotocol`.
Neither path changes the futex word or claims priority inheritance. The
`test_thread_identity` fixture covers both libc probes, ordinary mutex fallback
and protected tasks. This does not require host support newer than 5.15.
The installer configures PipeWire clients, servers and WirePlumber with
`context.data-loops` and `loop.prio-inherit = false`, including the private
Android-audio graph and NixOS activation. This retains the data thread and
ordinary mutex synchronization, not priority inheritance or guaranteed realtime
scheduling. Guest packages remain unmodified.

Guest PID namespaces likewise use retained userspace identities, not native
CLONE_NEWPID authority. `pid_namespace` and the supervisor use baseline clone,
wait4/waitid, flags-zero pidfd_open/pidfd_send_signal, ptrace birth/exec/exit
events and PR_SET_CHILD_SUBREAPER (since 3.4) for PID 1 orphan adoption. A denied
subreaper admission fails that child namespace explicitly. Signal disposition
observation reads native proc status on demand. Logical proc mounts reuse
O_PATH/native directory cookies and typed snapshots; their creation does not
issue a host mount. No PIDFD_THREAD, PIDFD_SIGNAL_THREAD or newer pidfd ioctl is
required. `--case mount-namespace`, stock Bubblewrap/GDB and Chromium's automatic
user/PID route cover these paths under actual UID 2000; the APK's other services
do not acquire these prerequisites.
`clone_call` captures the versioned `clone3` ABI in the caller. Ordinary births
remain kernel-owned; `pid_namespace` reserves `set_tid` numbers in logical PID
views (including ancestor permissions), and `cgroup` reserves a v2 placement
before `CLONE_INTO_CGROUP` birth. Failed births release both reservations without
publishing membership. An explicitly requested native ancestor PID remains
subject to the host kernel's permission check. These interfaces predate 5.15;
they introduce no newer kernel prerequisite. `test_clone3.py` compares native
fork/thread/vfork behavior and tests logical PID reuse, placement, namespace
capture, rollback, protected callers and pidfd permissions in both entry modes.
It also checks versioned read-only argument structures ending at a guard page,
kernel-compatible admission errors, CLONE_SETTLS and CLONE_CLEAR_SIGHAND.
Pidfds obtained outside `pidfd_open` are admitted on use from an exact captured
description under the existing file-table turn. The common SCM_RIGHTS capture
transport preserves kernel object identity without a helper clone or an
application-side proc open, including protected callers with filtered
`openat`/`clone`. Capture does not require `pidfd_getfd` or reading protected
caller memory from the supervisor. Retained logical identities do not block
reuse of a reaped PID.
`setns(pidfd, namespace_mask)` (Linux 5.8) uses this capture path and prepares
one logical resource/credential transaction. It does not call native setns or
require newer pidfd ioctls. `test_resources.py --case mount-namespace` covers
native and tracked pidfds, duplication, descendant authority, dead targets and
multi-namespace rollback in Base and lazy modes. Stock RootlessKit's `nsenter`
setup is covered by the rootless Docker probe.
Nested debugger waits use baseline ppoll, PTRACE_INTERRUPT and ptrace register/
signal-mask operations. Kernel-owned syscall restart is shared with socket IO;
there is no new kernel requirement or permanent helper. Native-versus-guest
wait4/waitid signal tests and stock strace's 41-thread SIGINT detach cover it.
Mounted proc visibility policies use the Linux 5.15 `hidepid`/`gid`/`subset`
semantics in `procfs_view`, without host mount authority or newer syscalls.
Live peer inspection uses `/proc/PID/io`'s READ_FSCREDS check, including native
dumpability and LSM policy. Missing `/proc/self/io` (CONFIG_TASK_IO_ACCOUNTING)
rejects a restricted proc mount with EOPNOTSUPP; permission/resource failures
remain explicit. Guest credentials and capabilities never elevate native file
access. `test_proc_policy.py` checks credentials, groups, protected/live/zombie
tasks, retained FDs, bind/copy and both mount APIs under shell.
Shared clone-TID publication uses the existing ptrace birth stop and task-affine
memory adapter. Proc-root capture and live fdinfo use retained O_PATH/proc
descriptions and that same private-table helper. None adds a post-5.15 runtime
requirement; `test_thread_identity_runtime.py` and `test_proc_root_runtime.py`
cover ordinary/protected tasks, PID namespaces and debugger ownership.

### Memory And JIT Repair

Noexec mapping policy uses the existing seccomp/ptrace observation and
CLONE_VM ownership, not MFD_NOEXEC_SEAL or a host mount. MREMAP_DONTUNMAP is
within the baseline (5.7 for anonymous private mappings, 5.13 for additional
mapping types); rejected native mappings keep their kernel errors.
Focused tests cover execution admission, mmap/mprotect, remap, fork and failed
metadata allocation. This adds no requirement for newer memfd execution flags.
Private-memory preparation uses retained smaps/mem/pagemap descriptions, ordinary
private file mappings and pagemap's present/swap/file/soft-dirty bits, all within 5.15.
Candidate admission reads bit 55 from its untouched mapping: both populated and
absent entries include the VMA's soft-dirty flag, as implemented in
[Linux 5.15 pagemap](https://github.com/torvalds/linux/blob/v5.15/fs/proc/task_mmu.c).
This avoids a second smaps walk without requiring PROCMAP_QUERY or soft-dirty
tracking to be enabled. An unreadable entry remains a preparation failure.
It neither reads PFNs nor requires CAP_SYS_ADMIN. Proc access is captured before
nondumpable state and cannot be reused for a forked address space. Missing
access, unknown VMA flags and unsupported mapping types fail preparation without
replacing the original mapping. The kernel retains COW, truncation and DONTNEED
semantics. `test_resources.py --case private-memory` covers the memory owner and
native/guest preparation under UID 2000, including retained access after
PR_SET_DUMPABLE=0, supervisor source capture, exact child-mm ownership and partial
proc-capture FD exhaustion. Direct source capture uses the existing task-descriptor
adapter. An authenticated regular-file O_PATH pin can instead reopen the exact
inode through `/proc/self/fd`, even after unlink; repair needs positional reads,
not the application's OFD offset or status flags. Existing backing custody is
reused before opening another description. Denied access falls back to the
existing private-table SCM_RIGHTS capture. Denied
capture leaves native mmap intact without reopening a source by pathname or
changing dumpability. The namespace
worker's acknowledged pause uses its pipe-backed queue signals and pthread synchronization,
with no newer kernel dependency. The replacement transaction uses baseline mmap/munmap and
fixed mremap, retaining the original VMA separately through commit or rollback.
A failed fixed move does not prove that its destination remains mapped; see
[Linux 5.15 mremap](https://github.com/torvalds/linux/blob/v5.15/mm/mremap.c).
Focused checks cover cancellation, destructive-destination failures, failed
restoration/cleanup and lost syscall observation. Automatic repair additionally
uses baseline PTRACE_INTERRUPT, wait4 and original CONT/SYSCALL/SINGLESTEP
continuations to quiesce launch-owned writers. Existing group stops stay stopped;
real events and rusage return to the normal dispatcher. Pending syscall/debugger
interrupts retain their original event owner across nested barriers. The helper and worker
barriers add no post-5.15 dependency. A `vfork` parent remains kernel-blocked
until the baseline PTRACE_EVENT_VFORK_DONE stop; child exit/exec and concurrent
JIT compilation exercise this ordering without waiting for another interrupt.
Concurrent ordinary/protected JIT writers
with namespace IO and stock SteamOS Plasma QML exercise the integrated path.
Repair validates request geometry before acquiring a barrier. Its default
scope is the exact address space and retained remote-memory users. An admitted
`/proc/PID/mem` open temporarily widens the scope to launch-owned tasks;
confirmed failure releases that reservation. Success or lost completion keeps
the conservative scope, including O_PATH descriptors that can be reopened after
transfer. The lease pins the target mm across exit/exec. Failed openat/openat2
and transferred O_PATH/read-write descriptors have live regression coverage.
The opt-in `test_jit_memory.c` experiment uses ordinary mmap/mprotect,
`MREMAP_FIXED`, memfds and fork; these fit the baseline. Its optional
userfaultfd probe separately tests full kernel-fault handling and
`UFFD_USER_MODE_ONLY` (since 5.11), without requiring either for production
Shroot or the private-view prototype. Available user-mode faults do not imply
permission to service kernel accesses to missing pages. The experiment does not
use newer userfaultfd move/poison ioctls or change SELinux/sysctl policy.

### Executable And Descriptor Custody

Anonymous ELF custody uses baseline `F_GET_SEALS`, CLOEXEC duplication, pread
and backed mappings. It does not require `MFD_EXEC` or `MFD_NOEXEC_SEAL`.
Read-only anonymous views and task-local controlled mappings use the same 5.15
primitives, including `F_SEAL_FUTURE_WRITE` and atomic ADDFD+SEND. The optional
`MFD_NOEXEC_SEAL` variant in `test_memfd_views.py` requires Linux 6.3 and skips
that variant on EINVAL; its ordinary memfd tests and production path remain
independent of this flag.
`test_anonymous_exec.py` checks ordinary/sealed images and process-image lifetime;
firmware execution-policy rejection remains an error, not an implicit elevation.
Portable backing custody uses the same sealed-manifest primitive and transfers
existing root/lower directory descriptions through SCM_RIGHTS. CLOEXEC duplication
and queued rights preserve their existing flock leases; import never relocks
them. These semantics fit the 5.15 baseline and add no startup requirement.
`--case inode-custody` covers malformed/partial imports, allocation/FD exhaustion,
sender exit, receiver death, queued-right discard, exclusive-lock preservation
and bounded transfer of all 512 rights. Native FD/in-flight quotas remain errors.
Message-origin parcels compose these same descriptors and seals with the existing
message-route contract. Per-worker graph interning and FD-table publication add
no kernel primitive. `--case origin-transfer` checks mixed/copy/alias views,
route/store rejection, allocation rollback, exact descriptor balance and queued
transfer after an independent exec sender exits.
Self-contained `ipc_capsule` custody uses those same baseline primitives plus
SO_COOKIE, getrandom, flock and renameat. Its HMAC is userspace Mbed TLS, not a
kernel crypto API. Single-packet queue trees use MSG_PEEK without SO_PEEK_OFF;
native descriptor, in-flight and Unix graph-depth limits remain errors.
`--case origin-transfer` covers independent sender exit, fresh receiving owner,
repeated/concurrent peeks, authentication rejection, partial FD limits and final
queue/epoch/backing release. It adds no post-5.15 dependency.

The `ipc_origins` service uses the same capsule primitives and existing pipe-backed
worker queue. It adds no kernel interface, private listener or live-source RPC.
`--case ipc-origins` covers exact captured sockets after slot reuse, worker
cancellation/join, bounded admission, allocation/in-flight quota failures,
source exit before receiver creation and receiver exit with untaken custody.
Actual data capture uses baseline `recvmsg(MSG_PEEK | MSG_CMSG_CLOEXEC)`
and `fstat` through the shared bounded ancillary parser; incomplete custody
never becomes success.

Captured-send helpers use existing atomic ADDFD_SEND (Linux 5.14) only for their
private import channel and application socket. The same bounded SCM_RIGHTS
queue imports 0..253 rights, including O_PATH, which ADDFD rejects with EBADF.
Closing the import channel before socket admission preserves the 254-slot bound
for 253 outgoing rights. No pidfd-getfd or post-5.15 primitive is needed.
`--case fd-observation` covers protected/full callers, zero/one helper slot,
exact/partial import capacity, receive/close/send filters, SIGPIPE, clone
rejection and cancellation before/after import and native send.
The native control-memory fault check confirms MSG_CTRUNC rather than EFAULT;
incomplete private import fails before send and reaping closes the imported prefix.
The outgoing guest sender composes these helpers with worker preparation
and the same mapped-buffer controls. `--case ipc-ancillary` checks actual guest
sendmsg/sendmmsg/sendto, explicit credentials, mixed headers, captured pathname
anchors, binary abstract addresses, protected/full tables, 253 rights, source exit
before import, blocked-send cancellation and shared-table slot replacement. It adds no
kernel interface or capability requirement beyond the existing helper path.
Native anchors use O_PATH, SCM_RIGHTS and task-local proc FD aliases; they require
one extra private slot, never an extra application right. The import channel
occupies the original application socket number so final ADDFD_SETFD preserves
FD-specific filters without overwriting imported rights. A shell SELinux policy
may forbid fixture socket-file creation; retained native-path rejection and
cleanup are checked separately from successful virtual/abstract delivery.
The optional `--native-anchor` helper fixture verifies successful native-path
delivery under the Termux app UID, including protected callers and 253 O_PATH/mixed
rights; it does not bypass the production executor or prove shell permission.
Imported origin custody and annotated-vector return reuse the existing shared
kernel receipt, SCM_RIGHTS and destination-table ordering. They require no new
syscall, flag or startup capability. Protected terminal-receiver, truncation and
peek checks run in `--case ipc-origins`; `--case ipc-ancillary` exercises the
native application-control-memory publication with the actual guest adapter,
including imported capsule origins. Message expansion adds no kernel primitive.
Capsule queues count against native per-UID in-flight rights; an export helper
with a reduced RLIMIT_NOFILE can fail with ETOOMANYREFS despite free slots.
The actual guest checks keep this explicit, without raising limits or replay.
`fd_table_observation` uses task-captured proc-FD directory iteration, `lseek`,
`getdents64` and the existing `PTRACE_EVENT_EXIT` stop. It observes the table before
`exit_files`, not after reaping. These interfaces and ordering are present in
5.15 ([proc iteration](https://github.com/torvalds/linux/blob/v5.15/fs/proc/fd.c),
[exit ordering](https://github.com/torvalds/linux/blob/v5.15/kernel/exit.c)).
Denied capture/iteration remains an explicit guest-operation failure, not an
empty receipt or a shared-service prerequisite. `--case fd-observation` covers
the device policy, production private-table capture and cancellation, partial
control faults, protected/shared-table receivers, quotas and fatal exit. The
typed capture request reuses the same helper lifecycle, clone flags, private
channel and atomic ADDFD_SEND; it adds no kernel primitive or startup probe.
O_PATH capture can retain the stopped private helper's object through
`/proc/<tid>/fd/<n>` with O_PATH, available in 5.15. Restricted proc access falls
back to SCM_RIGHTS; nofollow and stateful descriptions retain exact transfer.
`--case fd-observation` checks regular/directory/nofollow objects, protected
callers, filters, full tables, cancellation, name replacement and `/dev/full`
custody. `test_device_bind.py` checks device binding separately from the native
data-access denial. No post-5.15 primitive or privilege change is required.
This is an alternative observation fixture, not the guest annotated-return path.
Production annotated return uses the existing mapped kernel receipt and
SCM_RIGHTS, including O_PATH, without proc-table capture or ADDFD_SETFD. Filtered
input capture reuses table-owned channels and atomic ADDFD_SEND only for channel
admission. The exact gettid notification gate still evaluates application
filters. Mapped export-channel admission, task-affine ADDFD results and private-helper
admissions use the same gettid mechanism rather than depending on application
prctl policy. A protected PID-namespace fixture denies prctl and verifies cold
metadata export, a returned mount-table FD and bidirectional native-peer
credentials. Unix PID snapshots use the existing logical process identities and
SCM transport; no kernel PID namespace or newer credential API is required.
None of these operations adds a post-5.15 dependency.
Shielded internal FD admission uses NOTIF_SEND plus PTRACE_INTERRUPT to yield
to an active task barrier. Its reserved userspace retry result is not a Linux
errno; an application seccomp denial is returned unchanged. `supervisor-state`
exercises the production gate and coordinator against real kernel notifications
arriving before/after interruption, normal EINTR and atomic FD publication.
This uses the existing 5.15 interfaces; the live device coverage is Linux 6.12.23,
not a substitute for a minimum-kernel run.
Epoll wait capture/loans reuse the same notification gate with the original
syscall number and arguments, validated against the retained owner. They do not
depend on application permission for `gettid`. `test_svc_mapping` covers
post-TSYNC epoll_pwait/epoll_pwait2 with gettid set to KILL_PROCESS, plus native
poll/select and inotify waits spanning installation. `supervisor-state` checks
replay identity and ownership; the stock Go sandbox fixture exercises concurrent
runtime waits. This changes no required kernel primitive.
Filtered socket waits use ppoll, epoll and the existing ptrace signal-mask and
syscall-register interfaces. Native WAITALL/batch jobs use pthreads, eventfd and
a supervisor-local signal with an ARM64 syscall-entry cancellation gate. These
are baseline interfaces; no newer pidfd, io_uring or kernel page-fault mediation
is required. `--case ipc-ancillary` compares signal restart, socket timeouts,
SIGPIPE, Unix/TCP peeks, large messages and batch completion under UID 2000.
Explicit source-vector export uses the same private-table clone, close_range,
atomic ADDFD_SEND and authenticated SCM_RIGHTS channel, without pidfd_getfd or
new proc operations. Its 254-input bound allows a socket plus 253 data rights;
export splits into existing 253-right packets. `--case fd-observation` checks
protected callers, source-table replacement, quotas, filter denial and partial
export cancellation. It adds no post-5.15 requirement or startup probe.

Scalar receive capture optionally uses pidfd_open/pidfd_getfd (available before
5.15) in `fd_capture.c`. The exact thread identity and file-table turn select the
OFD; EPERM/EACCES/ENOSYS and unsupported forms retain task-affine export. Partial
capture closes every duplicate. `supervisor-state` covers shared OFD offsets,
duplicate slots, EBADF rollback and a denied pidfd_getfd fallback; the IPC-origin
suite exercises both native capture and forced protected/export custody.
Early nonblocking receive preparation uses the existing process_vm_readv and
pidfd_getfd facilities opportunistically. Failed snapshots/capture retain the
task-affine adapter without performing IO; this adds no kernel minimum or probe.
`receive-entry` and `receive-vectors` compare native results and protected fallback.
Native-only poll admission, input-buffer handoff and combined FINISH add no
kernel dependency. Readiness tests cover application clock denial, mixed vectors,
NOFILE and signal delivery; limits come from the existing process owner.

### Network Configuration

Offline network namespaces, private devpts views and permissive cgroups require
no post-5.15 interface or host namespace/cgroup privilege. Their owners use the
existing resource transactions, socketpair/SCM_RIGHTS, native PTY ioctls and
mapped control-file transport. Native terminal permission denials remain errors.
Anonymous-pipe metadata carriers use O_TMPFILE and OFD locks, also within the
baseline; no reader/writer endpoint is retained. `--case mount-namespace`,
`--case origin-transfer`, `test_descriptor_metadata.py`, stock sandbox-enabled
Chromium and `test_glycin_runtime.py` cover these paths under shell identity.
Native statx may return mount ID zero for anonymous objects; the adapter retains
that result instead of treating it as a failed mount lookup.
The logical v2 freezer uses existing PTRACE_INTERRUPT stops and eventfd wakeups,
not host cgroup permissions or injected SIGSTOP. `test_cgroup_guest` covers
freeze/thaw, nested membership and newborn tasks; stock CUPS exercises Snap's
freezer workflow. No post-5.15 interface is added.

Opt-in routed networking uses the Linux 5.15 NETLINK_ROUTE wire contract in
`network_route`, retained namespace/interface/endpoint owners in `network_model`
and `network_endpoint`, and the existing baseline capture/cancellable IO path
in `network_io`. Its native sockets, accept4, eventfd and SCM_RIGHTS introduce
no post-5.15 dependency or privileged Android network operation. Unsupported
protocols return explicit errors. `test_network_{model,route,endpoint}.c`,
`test_routed_network.py` and `probe_docker_runtime.py --profile routed
--exercise-ports` cover mutation rollback, namespace moves, TCP/UDP, independent
port observation, outbound DNS/HTTP and cleanup under actual UID 2000.
`SO_BINDTODEVICE` uses the Linux 5.15 name/length and namespace-capability
contract against the logical endpoint/interface catalogue. It never binds an
Android interface. Unit and routed guest tests cover dup, accepted inheritance,
route/ingress restrictions, rename/removal and transactional idle rebind;
active-flow retargeting fails explicitly with `EBUSY`.

IPv6 endpoints use baseline AF_INET6, IPV6_V6ONLY and packet-information socket
options. Address lifetimes, route preference and single nexthop objects are
guest rtnetlink ABI implemented by `network_route_ip`/`network_nexthop`, not
host route mutations. `network_raw6` implements guest raw ICMPv6 and UDPv6 on
existing logical Ethernet queues, including the baseline `IPV6_CHECKSUM` ABI,
without host raw-socket privileges. `network_raw4` uses the same queues for
IPv4; raw UDP and multicast reuse native UDP relay sockets. DHCP and multicast
fixtures check both families and namespace boundaries without changing Android
routes or requesting newer kernel operations. IPv6 lifetime and
temporary-address maintenance reuse the forwarding owner's monotonic timerfd
and baseline getrandom; no newer kernel dependency is introduced. Unsupported
control modes fail explicitly. `test_network_ipv6.{c,py}`, resource tests and
`test_os_boot.py` networkd checks cover these owners. Optional public tests
(`test_network_ipv6.py --internet` and Docker/Podman `--internet-ipv6`) require
an IPv6 uplink allowed by Android; a kernel version alone does not prove it.
`network_transport` uses baseline IPV6_MTU, IPV6_UNICAST_HOPS and TCP_MAXSEG
to apply supported route/interface policy to captured native sockets and relays.
No Android route mutation, capability elevation or post-5.15 option is required.
Native tests verify UDP EMSGSIZE, received hop-limit ancillary data, explicit
application overrides/reset and TCP MSS; stock networkd boot tests verify the
effective route and connected socket values.

Managed netlink control also implements the baseline nlctrl family catalogue,
strict route query validation, kernel-peer connect, policy-rule queries and
SO_PASSCRED/NETLINK_PKTINFO reply metadata. These are userspace protocol owners;
they do not open privileged host netlink sockets or require newer kernel APIs.
`--case mount-namespace` and `--case ofd-order` cover validation, duplication,
ancillary metadata, membership and unsupported operations. Default systemd boot
and the Podman probe exercise stock NetworkManager and netavark clients.

Classic socket filters use baseline `SO_ATTACH_FILTER`, `SO_GET_FILTER`,
`SO_DETACH_FILTER` and `SO_LOCK_FILTER` on private AF_UNIX queues. `socket_filter`
validates and translates their payload offsets once, retaining kernel execution
and permission errors. Packet sockets in `network_ethernet` use the same queues
and virtual interface model; no host AF_PACKET or CAP_NET_RAW is acquired.
`PACKET_IGNORE_OUTGOING` is a guest setting (introduced upstream before 5.15),
not a newer host requirement. `test_socket_filter.c`, `test_netlink_filter.py`
and `test_network_ethernet.{c,py}` exercise admission, filtering, descriptor
lifetime, virtual delivery and negative namespace/capability cases. Queue buffer
force options retain the host kernel's capability checks, including EPERM under
shell identity; an emulated guest capability cannot enlarge the host limit.

Namespace-handle bind mounts use retained typed providers, baseline sealed
memfd carriers, O_PATH and descriptor duplication. They do not call host mount
or setns and introduce no post-5.15 dependency. Carrier reads are never reopened
through proc: the retained description supplies the descriptor, as for anonymous
ELF images. `--case mount-namespace` covers path/FD aliases, owner exit, recursive
bind, copy-cycle prevention, authority and repeated lazy retirement.

### Cgroups And Device Policy

Logical `CLONE_NEWCGROUP`, namespace handles/ioctls and `setns` use the same
resource transactions and control-file carriers. Captured hierarchy roots and
user-namespace authority follow the Linux 5.15 contract; no host cgroup namespace
or newer syscall is required. `--case mount-namespace` covers relative proc paths,
mounted proc, clone/unshare/setns, retained handles, removed roots, owner checks
and `fsopen` capture under UID 2000.

Named v1 membership hierarchies (`none,name=NAME`) use the same `cgroup` owner,
typed controls and pre-5.15 mount/fsconfig ABI. They require no host cgroup
mount or newer kernel. The guest cgroup tests cover name validation, independent
memberships, repeated legacy/FD mounts, fork, namespace-root capture and retired
controls. Release agents and unsupported controller combinations fail explicitly.

Logical cgroup-device BPF uses the userspace ABI available before 5.15, sealed
memfd carriers and the existing control-FD delivery/ownership contract. It never
calls host bpf or requires CAP_BPF, native cgroup hooks, BTF or a newer kernel.
`cgroup_program`, `bpf_map`, `bpf_handle`, `namespace_bpf` and `fs_bpf` own this permissive bookkeeping.
Hash-map CRUD, key iteration, map-ID lookup and program-held map references use
the pre-5.15 BPF ABI. Bounded map memory belongs to the launch's cgroup registry;
descriptor duplication and SCM_RIGHTS use the same sealed control carriers as
programs. Map lookups and stack instructions are admitted as program metadata,
not executed in Android or advertised as device-policy enforcement.
Instruction queries retain the admitted bytecode with map IDs rather than host
pointers; variable-length replies copy only the requested prefix. Metadata-only
queries do not transfer instructions. This uses the pre-5.15 program-info ABI,
not host JIT or BTF support.
Unsupported commands/features fail explicitly rather than falling through to
Android. `test_cgroup_bpf.py` covers program/attachment lifetime, protected exec,
SCM_RIGHTS, readonly policy, failed FD publication, invalid attributes and exact
warmed descriptor cleanup under UID 2000, plus map capacity/flags, key iteration,
bad-pointer returns, shared descriptor identity, program-held map lifetime and
two-stage instruction queries with short buffers, short ABI and invalid pointers.

### Mount API And Transient Filesystems

The descriptor mount API (`fsopen`, `fspick`, `fsconfig`, `fsmount`, `move_mount`,
`mount_setattr`) follows
the Linux 5.15 userspace contract through `fs_context`, `mount_source` and the
existing mount graph. It does not issue these privileged syscalls on the host.
Context carriers use sealed memfds; mount FDs use O_PATH and the common retained
origin/ADDFD/SCM_RIGHTS delivery mechanisms. No newer kernel interface is needed.
`fspick` (5.2) selects a retained mount root, with empty-path and symlink policy,
for inode, proc and sysfs filesystems. Tmpfs/ramfs/proc/sysfs support readonly
changes; persistent inode stores deny future writes without revoking existing
writable descriptions or shared mappings (see namespace-providers.md).
It never forwards privileged mount operations to Android. Other filesystem
reconfiguration is explicitly unsupported.
Provider superblock read-only policy follows aliases, namespace copies, live
control descriptors and exported host projections through the existing sealed
policy mapping. The imported mapping grants observation, not remount authority.
This is an ephemeral mount-graph protocol change, not a persistent store revision.
`test_mount_api.py` covers stock util-linux callers under UID 2000, and the
transient-store fixture covers detached graph custody and exact FD retirement.
Attribute changes use the existing single-writer policy, including recursive
preflight and locked-policy rejection; no host mount_setattr call is required.
Tmpfs/ramfs read-only reconfiguration uses baseline `F_SETLEASE/F_RDLCK` under the
existing store gate to detect native writers and shared writable mappings.
`inode_super.c` releases each probe lease before policy publication; native
unsupported/permission errors fail the requested reconfiguration only. Lease
break signals target the worker TID through baseline `F_SETOWN_EX`, with scoped
blocking and pending-signal preservation. Policy lives in the retained store,
not a new per-write syscall adapter. `test_mount_api.py` and the native
transient-store fixture cover writers, unlinked files, mappings, aliases,
cross-process snapshots and release. This does not require a post-5.15 kernel.
Native-leaf temporary-store writer discovery uses this same lease probe before unlink or
overwrite; a busy leaf is promoted so an unlinked writer still blocks read-only
remount until its description/mapping is released. Persistent-store mutation
does not acquire this temporary-store-only probe. Its readonly policy is cached
by the existing catalogue generation, shared within the retained store scope,
and cleared by cold admission after the last root lease. It adds no kernel
dependency or ordinary read/write interception. `test_fs_context.c` covers peer
visibility, retained writers/mappings, cold reset and fail-fast maintenance.
`test_inode_view.c` covers upper-policy propagation into writable overlays,
denied upper acquisition/activation and independently scoped overlay remounts.
Both temporary kinds are disk-backed, not memory-only/no-swap implementations.

### Rejection And Auxiliary Providers

TCP REJECT uses baseline SO_LINGER with zero linger on relay-owned stream FDs
(`network_forward.c`), not host netfilter or raw packet injection. Local
connection admission returns ECONNREFUSED. `test_network_nft.c` covers nft/xt
wire validation and `test_network_launch.c` checks IPv4/IPv6 native RST for new
and established peers. Unsupported UDP/ICMP rejection fails rule admission;
no host ICMP permission or newer kernel capability is assumed.

Protected proc final-link capture uses caller-side O_PATH|O_NOFOLLOW and the
existing exact-description transport. The filesystem walker verifies the captured
link and retains its original mount policy; it never changes dumpability or
requires pidfd_getfd permission. `test_proc_policy.py` covers protected metadata,
reopening, directory reads and no-follow link identity. Signal delivery after
guest ptrace uses the existing GETSIGINFO/SETSIGINFO primitives and target PID
view; `test_thread_identity_runtime.py --debuggers` covers libc's set-ID signal
handshake across debugger/target namespace boundaries.

`open_tree` and `STATX_ATTR_MOUNT_ROOT` use the same logical mount identity and
detached-origin lifetime; neither requires host mount privilege. Mounted proc
directories retain PID selection and typed UTS controls. `test_mount_api.py`
and `--case mount-namespace` cover these paths under shell identity.

Live mount-table descriptors use optional AF_UNIX out-of-band readiness
(`CONFIG_AF_UNIX_OOB`, upstream 5.15). `fs_mount_table.c` falls back to a regular
typed descriptor when the host rejects OOB: table reads remain supported, but
epoll registration may fail. PRI readiness is not a complete procfs ERR/poll
contract; typed UTS sysctl poll notification remains unsupported.

`terminal_calls.c` first tries native termios2 on the original task/FD. Firmware
EACCES or ENOTTY falls back to legacy termios for representable standard baud
rates only; arbitrary BOTHER rates return EOPNOTSUPP when native termios2 is
unavailable. `test_terminal_attributes.py` checks both ABI and terminal identity.

`sysfs_view` retains the existing logical network owner and exposes its loopback
state through typed control files, O_PATH provenance and a private native name
tree. Legacy mount and fsopen share this provider; no host sysfs mount or
post-5.15 API is used. `test_sysfs.py` covers namespace authority and capture,
live netlink updates, retained/bound files, cgroup nesting, unsupported mutations,
stock util-linux mounting and repeated detached retirement under UID 2000.

`mqueue_view` implements a limited IPC-owned directory provider through the same
mount APIs, without CONFIG_POSIX_MQUEUE or a host mount. POSIX message calls
explicitly return ENOSYS; their absence must not fall through to Android queues
on kernels which enable them. No post-5.15 API is required. `test_mqueue_mount.py`
checks both mount APIs, IPC capture and authority, bind/copy/readonly behavior
and unsupported operations. The resource-owner fixture checks final namespace
release, private-tree removal and exact native FD baseline over 40 iterations.

## Newer Interfaces

Every owned dependency newer than 5.15 belongs here, including optional
optimizations and experimental fixtures. Guest programs' unadapted syscalls
retain native errors; arbitrary new guest syscalls are not runtime requirements.

| Interface | First upstream kernel | Owner and purpose | Behavior without it |
| --- | --- | --- | --- |
| `DMA_BUF_IOCTL_EXPORT_SYNC_FILE` / `DMA_BUF_IOCTL_IMPORT_SYNC_FILE` | 6.0 | Graphics DMA-BUF fence bridge; `render_device.c` probes allocation/export/poll/import once for a Turnip-enabled launch. `test_render.py` checks admission and typed descriptor behavior; `test_egl_dmabuf.c` and `test_egl_gbm.c` verify GPU completion and exact pixels. Hosted-runtime DMA-BUF tests cover consumption. | A failed probe leaves the optional discovery node absent; the graphics importer rejects unsupported synchronization. Existing SHM paths remain independent. No Shroot startup requirement or raised 5.15 baseline. Kernel version alone does not admit the node. |
| `PIDFD_THREAD` | 6.9 | `task_descriptor.c` owns descriptor capture; `task_lifetime.c` owns exact-thread liveness for restricted proc roots. | Try flags zero for the same task. A non-leader rejected with EINVAL is reported as ENOTSUP and uses task-affine SCM_RIGHTS export. Liveness retains `/proc/<tid>/stat` instead: a flags-zero pidfd observes group death, not exact-thread death. Never substitute the leader's file table. |
| `pidfs` filesystem identity | 6.9 | `fd_filesystem.c` supplies statfs metadata for an actual retained pidfd when Android denies native fstatfs. It identifies the kernel object and compares its superblock with anon_inodefs; it does not grant process access. `test_kernel_baseline.c` compares native and denial-injected results; `test_proc_root_guest.py` covers proc aliases, duplicates, peer pidfds and protected callers. | Older pidfds retain ANON_INODE_FS_MAGIC; no pidfs ioctl, release check or new startup requirement. Non-pidfd denials remain errors. |
| `SECCOMP_IOCTL_NOTIF_SET_FLAGS` / `SECCOMP_USER_NOTIF_FD_SYNC_WAKE_UP` | 6.6 | `namespace_broker.c`: optional notification scheduling hint. | Failed ioctl leaves normal notification wakeups enabled; no permission or affinity change. Success does not guarantee CPU co-location: the scheduler still owns placement, and not every FD-install completion uses this hint. |
| `fchmodat2` | 6.5; `AT_EMPTY_PATH` in 6.6 | `namespace.c` translates store metadata through the worker. `file_calls.c` also has a native direct-backend path. | Logical store metadata does not require this host syscall. Native passthrough retains ENOSYS/flag errors; no fake success or unconditional startup requirement. |
| `SO_PEERPIDFD` | 6.5 | `test_proc_root_guest.py`: optional coverage of native peer-pidfd fields in logical fdinfo. | ENOPROTOOPT/EINVAL/ENOSYS skips only this optional acquisition; baseline pidfd_open and fdinfo assertions still run. This is not a runtime prerequisite. |
| `SO_PASSPIDFD` / `SCM_PIDFD` | 6.5 | `socket_control.h`, `ipc_io_host.c`, `task_receive.c` and `socket_ancillary.c`: receive, custody, CLOEXEC publication and cleanup of kernel-generated pidfds. An authenticated brokered sender carries its original flags-zero pidfd (baseline 5.3), never substitutes the broker's PIDFD. `ipc-ancillary` compares native/guest scalar, batch, peek, truncation, filtered receives and exact sender identity; `test_systemd.py` covers notify and FD-store restart. | The socket option retains native ENOPROTOOPT/EINVAL when unavailable; no synthetic support or startup requirement. The optional pidfd fixture reports that absence; ordinary credential and rights tests still run. Negative pidfd payloads remain errno values, not owned descriptors. |
| `PIDFD_GET_*_NAMESPACE` | 6.11 | `pid_calls.c`, `supervisor.c` and `namespace_proc.c`: guest pidfds return retained logical namespace handles through the common FD-publication path. `--case mount-namespace` and systemd directory setup cover the ABI. | No host namespace ioctl is required for managed pidfds. Time namespaces remain EOPNOTSUPP; unowned native pidfds retain native errors. |
| `STATX_MNT_ID_UNIQUE` | 6.8 | `namespace_proc.c`: distinguishes globally unique mount IDs from the runtime's ordinary mount-ID namespace. | The adapter strips the UNIQUE request and asks for `STATX_MNT_ID` (5.8); returned masks do not claim UNIQUE support. |
| `PROCMAP_QUERY` | 6.11 | `test_mapping_task.c`: optional binary mapping-observation experiment. | Production `mapping_observation.c` reads a retained maps file. The fixture records unavailable ioctl access separately and verifies the text path. |
| `MFD_EXEC` | 6.3 | `test_exec_policy.c`: explicit executable-memfd policy experiment. | This experiment reports failure; production image admission does not require this flag. |
| `MFD_NOEXEC_SEAL` | 6.3 | `test_memfd_views.py`: optional sealed non-executable memfd variant. | EINVAL skips only this variant; ordinary memfd coverage and production mapping remain available. |
| `AT_EXECVE_CHECK` | 6.14 | Execution-policy boundary, not an enabled host dependency. | The adapter rejects unsupported execveat flags rather than treating an X_OK check as equivalent. |

Version references: [pidfd_open flags](https://man7.org/linux/man-pages/man2/pidfd_open.2.html),
[Linux 6.0 DMA-BUF ABI](https://github.com/torvalds/linux/blob/v6.0/include/uapi/linux/dma-buf.h),
[Linux 6.6 seccomp ABI](https://github.com/torvalds/linux/blob/v6.6/include/uapi/linux/seccomp.h),
[Linux 6.5 socket options](https://github.com/torvalds/linux/blob/v6.5/include/uapi/asm-generic/socket.h),
[chmod flags](https://man7.org/linux/man-pages/man2/chmod.2.html),
[statx masks](https://man7.org/linux/man-pages/man2/statx.2.html),
[Linux 6.11 proc API](https://www.kernel.org/doc/html/v6.11/filesystems/proc.html),
[Linux 6.3 memfd ABI](https://github.com/torvalds/linux/blob/v6.3/include/uapi/linux/memfd.h),
and [Linux 6.14 exec flags](https://github.com/torvalds/linux/blob/v6.14/include/uapi/linux/fcntl.h).

## Validation Limits

Device coverage is actual UID 2000 on RM11/NX809J, Linux 6.12.23, API 36,
4 KiB pages. A real 5.15 kernel, API 34 and 16 KiB pages remain unverified.
Missing-operation injection on a newer kernel tests explicit failure/fallback,
not the scheduling, ABI or security policy of an older kernel.

The launch probe covers pidfds, subreaper, faccessat2, executable mappings,
ptrace, seccomp notification dispatch and atomic ADDFD through the production
mapped-buffer installation. It acquires a mapped lease and completes a production
private-table helper: clone, inherited-FD removal, channel publication, exact-task
proc capture, descriptor export and full helper reaping. Only then does the probe
release its lease and report readiness. These guest-only checks do not initialize
the filesystem worker or affect independent shell services.

`test_resources.py --case kernel-baseline` masks PIDFD_THREAD with EINVAL and
executes the resource model plus production private-thread proc capture and
mapped descriptor/data/import workflows. Exact-task handle cases include
ordinary/nondumpable tasks, independent non-leader file tables, retained handle
copies and dead-task observation after reaping. The resource model checks
restricted proc roots belonging to live non-leaders and copies of those roots.
Fallback proc handles are opened once and inspected on demand, not periodically.

The same suite injects missing pidfd, faccessat2, seccomp, close_range, private
clone and ADDFD operations. ADDFD also covers EINVAL and EACCES. Each rejected
guest probe must leave an independent shell usable. Probe success alone is not
certification of the full execution path.

### Optional Probe Coverage

`test_resources.py --case pidfd-table` is a separate 6.9+ feasibility check,
not part of the baseline suite or production adapter. Sixteen cases cover
same-group private-table getfd with ordinary/nondumpable tasks, zero to three
available result slots, shared file offset, O_PATH, helper filter death and
pipe EOF. Its fixture-owned pidfd consumes an infrastructure slot. This does
not establish descriptor publication, interruption or cancellation semantics
for production ancillary IO.

The audit covers owned runtime syscall dispatch, raw/assembly calls, pidfd and
ptrace flags, seccomp ioctls, proc observation and metadata operations. Recognizing
a guest syscall is not the same as requiring the host implementation: worker-owned
chmod handles logical objects and native attachments without fchmodat2, whereas
direct passthrough preserves the host error. It does not certify arbitrary guest
libraries or replace execution on a real 5.15 device.

`test_resources.py --case fd-messages` includes an optional exact Unix-socket
diagnostic probe. The request and inode/cookie validation are already present in
[Linux 5.15](https://github.com/torvalds/linux/blob/v5.15/net/unix/diag.c#L243-L305),
but the tested shell policy returns EACCES. This probe is not a runtime dependency;
denial remains unknown liveness, never permission to discard provenance. The
separate queued-pipe lifetime candidate uses baseline pipe/socket and poll/epoll
operations and does not depend on socket diagnostics.

The [entry/runtime fixtures](../guest-exec-lab/cooperative-entry.md) exercise
the production shared-memory channel, FD publication and cancellation owners.
They add no post-5.15 dependency or separate transport implementation.
An ordinary proc FD link is not a generic descriptor-transfer mechanism.

### Signal And Checkpoint Coverage

The shared deferred-standard-signal owner uses baseline tkill and ptrace
siginfo/mask operations, not service single-step. It queues the retained signal's
own number and restores its original metadata at a real delivery stop. Chained
stops remain inside kernel signal processing, preserving native EINTR/SA_RESTART
and exactly-once IO. Job-control signals instead remain in kernel custody with
temporary mask ownership: requeueing the original delivery does not repeat
generation-time STOP/CONT effects. Realtime signals retain kernel queue order.
Fixtures cover sigwait/signalfd, ignored/blocked SIGTRAP, original metadata,
private/shared pending signals, altstack, interrupted calls, group stops and
RLIMIT_SIGPENDING exhaustion. The kernel can lose metadata under exhaustion;
the runtime does not claim otherwise.

`task_checkpoint` owns exact bootstrap SVC sites and emits an internal typed
event. Unfiltered service markers use selective seccomp TRACE; with application
filters the owner scopes PTRACE_SYSEMU to lent bootstrap execution. It skips
only registered markers before filter evaluation. Ordinary calls in that scope
re-enter natively with their original IP and arguments and remain subject to
seccomp. A synthetic PTRACE_INTERRUPT exposes the real x7 before publishing a
marker, because ARM64 syscall stops borrow that register. Application handlers
and guest debugger execution never inherit this service scope.
The scalar MAPPED/TAKE/RELEASE and filesystem
CALL/EXEC_PREPARE/EXEC_CANCEL/PROC_LINK markers can publish at syscall entry
because their C ABI does not retain x7. FINISH and full-context operations still require the post-syscall
interrupt. Checkpoint fixtures cover TRACE/SYSEMU, dumpability and blocked SIGTRAP
for both forms. Supervisor-state covers clone/fork/vfork stops already consumed
before a cold barrier begins, including exact event and rusage replay.
The shared C-facing assembly gates explicitly supply gettid as the skipped
number. An inherited x8=-1 would overwrite x0 before tracing even on 5.15;
saving registers after that stop cannot recover the argument. Gate entry and
marker addresses are published separately. The production-gate fixture checks
invalid incoming x8, positive/negative x0, protected tasks and strict filters;
the live memfd poll/epoll/mmap sequence covers adapter cleanup after native IO.
Register-copy and native-completion markers use the same fixed number, with
separate native-return and marker addresses. Native waits and execution-state
calls can therefore precede the IP checks in the base filter without consuming
a completion as an ordinary syscall. Mixed epoll retirement validates a captured
FD generation before retiring a borrowed slot; only an uninterrupted table turn
can refresh that generation after its own close. Shared-table replacement and
filtered/unfiltered native-wait fixtures cover these paths.

ARM64 PTRACE_SYSEMU and its pre-seccomp ordering exist in Android common 5.15;
PTRACE_INTERRUPT/SEIZE and GET_SYSCALL_INFO predate the minimum. There is no
newer-than-5.15 dependency. The guest-only startup private-helper probe exercises
the restricted owner; rejection is a native runtime failure, never a reason to
disable another MagicDesk service or silently fall back to signal-forcing BRKs.
UID-2000 device coverage is Linux 6.12 on 4 KiB pages. A 5.15 device and 16 KiB
pages are not covered by these results. Strict IP/number KILL, TSYNC, protected
tasks, full register payloads, clone/exec, cancellation and actual GDB/strace
workflows have focused fixtures.

Channel registration uses the existing `gettid` USER_NOTIF gate instead of BRK,
preserving blocked SIGTRAP and its handler. A per-image hint revokes fresh
registration before application filters, including TSYNC and inherited policy
after exec; it adds no per-call syscall. Existing channels retain per-task
eligibility. The shared-memory and cooperative fixtures cover this admission.
The guest's existing filter preflight owns the local hint write; image startup
receives inherited policy through its register handshake. Protected forks do
not require PTRACE_POKEDATA or a retained writable memory descriptor for this
step. The direct and managed `fs-export` fixtures cover this boundary.

The filesystem-signal suite checks SIGTRAP disposition with ignored, blocked,
seccomp and protected variants. Actual guest
debugger single-step remains native; terminal BRKs after impossible bootstrap
or exit returns are not normal protocol markers. A separate invalid-pointer
limitation remains: fallback userspace LDR/STR faults can reset ignored/blocked
SIGSEGV before the tracer receives the fault. The checkpoint fixture retains
explicit negative controls against native EFAULT behavior; replacing completion
markers is not a fix for faulting memory access.

### Lazy SVC And Publication Coverage

The default [lazy-entry owner](../guest-exec-lab/svc-rewriting.md)
(`SHROOT_MACHINE_ENTRY=0` selects Base) uses no hardware-breakpoint experiment. `svc_runtime.c` prepares private frames and nearby RX pools;
`svc_registry.c` publishes a single SVC replacement through baseline
PTRACE_POKETEXT after actual syscall execution. It requires HWCAP_ATOMICS,
4/16 KiB-compatible layout, MAP_FIXED_NOREPLACE (4.17) and firmware permission
for private executable memory. Resource/preparation failure leaves Base entry
available. Only 4 KiB pages have device coverage. Publication does not borrow
hardware breakpoints or stop peer threads; no extra filter is installed per
pool or exec.

`svc_registry` stages unreachable gate code with one write through the address
space owner's retained `/proc/PID/mem` descriptor, without reopening it per site,
falling back to word-sized ptrace writes if the firmware denies that access.
The original SVC is still published separately through the existing word
writer, only after the complete gate is written. Partial staging is never
published. Linux 5.15 already routes [proc memory writes](https://github.com/torvalds/linux/blob/v5.15/fs/proc/base.c)
through remote-page access and [ARM64 executable-page cache maintenance](https://github.com/torvalds/linux/blob/v5.15/arch/arm64/mm/flush.c).
This adds no newer-kernel requirement. `test_svc_patch` covers RX staging and
borrowed-FD lifetime and the denied-access fallback; the runtime SVC suite covers execution, signals,
fork and protection transitions.

Private untracked memory admission uses ordinary `munmap`, `mprotect`, anonymous
private `MAP_FIXED` and nonfixed `mremap`, plus shared atomic
state, with no new host primitive. `interception_memory` owns the excluded
ranges and revokes admission on sharing/exposure; only the resident SVC driver
may use the native effect, under its existing deferred-signal contract.
Tracked/shared ranges and unsupported admission retain the ordered supervisor
path. `test_interception_buffers` checks range publication and revocation;
`test_svc_patch.py` checks accelerated completion, page rounding, invalid
addresses, resize/content preservation, forced kernel relocation, file-backed
fallback, signal reentry and filter denial. Remapping accepts only flags zero
or baseline `MREMAP_MAYMOVE`; fixed destinations and cloning keep the observed
path. Minimum kernel remains 5.15.

`svc_machine.c` uses the existing seccomp TRACE/USER_NOTIF routing and baseline
register/siginfo APIs to recover logical contexts. Deferred standard and realtime
signals use the same `signal_delivery` owner as shielded Base operations. Realtime
signals use rt_tgsigqueueinfo (2.6.31) with supervisor-authenticated carriers and
restored original siginfo to preserve FIFO against later kernel arrivals.
Carrier identity survives a queue handoff to the leader during exec; deterministic
tests also cover repeated custody of the oldest event before delivery.
A caller-owned getuid checkpoint handles pending signals; its syscall must exit
before delivery installs the recovered guest registers. It is not a service
SIGTRAP. This mechanism adds no post-5.15 dependency.
The regular-file cursor lease uses only baseline `lseek` and the existing raw
service gate. It is issued under the exclusive file-table turn and revoked by
task stops, ordinary notifications, new requests and borrowed execution. The
resident private frame retains signals through the call; the explicit entry API
and shared tables keep ordinary admission. `test_cooperative_runtime.py` checks
repeated relative seeks, invalid offsets, directory cursors, pipe replacement,
CLONE_FILES replacement, asynchronous signals and seccomp revocation.

The integrated cooperative suite, stock Go with and without seccomp, glibc
signal/churn fixtures and deterministic instruction-boundary fixture cover it.
Application filters revoke admission; passing their fallback tests does not
prove continued acceleration in a sandbox.

Interrupted native aliases use baseline PTRACE_INTERRUPT at a signal-delivery
stop. `svc_machine` translates the continuation only after ARM64 `do_signal`
has made its restart/EINTR decision and constructed the signal frame, before
the handler executes. Early PC replacement would bypass the kernel's restart-PC
comparison, including on [Linux 5.15](https://github.com/torvalds/linux/blob/v5.15/arch/arm64/kernel/signal.c#L849-L917).
`test_svc_machine_signal` covers read/futex restart classes, SA_RESTART,
altstack, filters, ignored/suppressed delivery, longjmp, a concurrent entry
drain and a native alias returning without signal delivery. Known unfiltered
aliases keep their resident return branch instead of another SETREGSET; filter
barriers also reconcile a quiet return at the next ordinary guest boundary.
Stock glibc/musl signal storms and real document-portal shutdown exercise
the integrated owner. Device execution remains 6.12/4 KiB, not a 5.15 claim.
Inherited no_new_privs conservatively selects restricted service checkpoints
before bootstrap probes; `test_capabilities` verifies denied required syscalls
produce admission errors instead of falling through a rejected marker.

Bootstrap supplies both exact program/interpreter FDs in one admission before
closing them. `interception_memory` validates each identity against the same
`/proc/PID/maps` observation and retains one backing per file across its private
segments, including initially inaccessible ones; shared mappings are excluded.
Initial ELF text therefore uses the
same retained-file repair as intercepted mmap when a later NONE/R to RX change
is denied after ptrace COW. No pathname reopen, new kernel operation or weakened
protection is required. The native/Base/machine mapping fixture covers repeated
restoration and fork inheritance.

Application filter installation and `PR_SET_DUMPABLE(0)` use `task_quiesce`
to recover or drain resident machine entries before replaying the original
syscall, including TSYNC. These kernel transitions acquire the address-space
cold barrier only when resident gates exist, including retired gates that a
peer can still be leaving. An empty registry needs no stop; publication still
does not stop peers. Dumpability clears
remote-memory authority, so source
instructions are restored and resident gates drained before committing it.
The requested protected state is retained, not emulated as dumpable.
The live UID/open/close races require actual drains and
post-filter denials, with helper gettid set to KILL_PROCESS. Failure to drain
an admitted callback within the bound returns EBUSY without applying the
transition; acceleration stays revoked. The protected-state race also verifies
fork inheritance. The initial quiescence failure retains
fail-closed cancellation. These operations are all within the 5.15 baseline.
Arbitrary externally mutable backing and untested architectures remain outside
this coverage. Entry admission falls back to Base.

The deterministic instruction-boundary fixture alone uses `svc_text.c` to
prepare a private RX memfd view with a retained shared RW writer. It uses
memfd seals and mapping operations available by 5.15, with explicit failure
when the firmware denies preparation. This is test scaffolding, not the
production loader, a global permission workaround or a startup prerequisite.
