# Shroot Native Runtime

Shroot is a native ARM64 Linux userspace runtime with one engine for the User
(MagicDesk app UID) and Shell (authorized UID 2000 or 0) executors. Both use the
same [fixtures and contracts](../guest-exec-lab/host-execution.md), with separate
storage and retained launch identities. A root-backed Shell service is not a
separate native-root implementation.
The APK packages standalone executables, not libraries loaded into ART.
Guest-only capabilities are probed on explicit use and never become
prerequisites for other services.

Start with the [user guide](../../docs/shroot.md) for installation, named
environments, Docker/Podman, init systems and supported application workflows.
This file is the architecture map; focused contracts below own implementation
invariants. [Fixture instructions](../guest-exec-lab/README.md) own test setup
and evidence, rather than installation recipes.

- [Runtime Model](#runtime-model)
- [Ownership](#ownership)
- [Application Integration](#application-integration)
- [Contracts](#contracts)
- [Execution Policy](#execution-policy)
- [Build And Verification](#build-and-verification)

## Runtime Model

The bootstrap maps an ARM64 ELF and its optional interpreter, constructs its
initial stack/auxv, and enters the distribution's stock glibc/musl loader. Static
ELF and raw SVC programs use the same entry admission. No PRoot process, external
container manager or SELinux change is required.

Lazy machine-code entry admits observed AArch64 SVC sites in unmodified programs,
including stock libc and Go, by default for inode-store launches. It shares the
[cooperative channel](cooperative-entry.md) and syscall owners, not a second
implementation.
Admitted task operations reach the existing supervisor/resource owners over that
same channel. Their guest-thread C execution and native-effect acknowledgements
share Base's dispatcher and retirement; ordinary worker/native fast paths remain
direct. Kernel stops are retained for actual task-context requirements, not for
scalar control messaging.
Application filters fence entry and publish immutable original-policy views
before eligible calls resume acceleration. Debugger ownership and unavailable
policy views retain the common fallback. Unavailable capabilities and
unsupported sites retain Base interception; `--env SHROOT_MACHINE_ENTRY=0`
selects Base explicitly. See
[integration evidence](../guest-exec-lab/svc-rewriting.md#product-boundary).

One supervisor owns each process tree. Selective seccomp TRACE/USER_NOTIF directs
operations needing adaptation to either a task-affine adapter or a filesystem
worker. The supervisor is the sole kernel ptracer. Ordinary unselected data IO,
memory and futex operations retain native execution. Signal masks also stay
native unless inherited Android policy requires the logical SIGSYS view. Signal sending
always checks launch membership and guest credentials, even without a PID
namespace. Group/broadcast sends select only supervised guest processes, never
other Android processes sharing the executor UID. Features such as
guest debugging, routed networking and virtual descriptor readiness activate
only for their owning resources.

The runtime supplies logical Linux resources where native shell operations are
insufficient: inode-backed filesystems, guest credentials, namespaces, mounts,
IPC and selected control files. This supports real init systems, container
engines and sandbox-using applications, rather than only a translated root path.
It is not a full Linux ABI or an isolation boundary for hostile code.

```text
MagicDesk launch services
  | selected identity, store, argv, PTY and optional connections
  v
native runner -> supervisor -> bootstrap -> stock ELF / libc loader
                    |                         |
                    |<-- selected syscalls ---|
                    |
                    +-- task/resource owners and task-affine calls
                    +-- filesystem worker -> inode/native/mounted providers
                    +-- IPC, network and descriptor readiness owners

Android kernel: actual task lifetime, descriptors, data IO and native checks
```

## Ownership

| Owner | Responsibility | Does not own |
| --- | --- | --- |
| Application services | Authorization, catalog/registry downloads, launch recipes, immutable helper staging, PTY, graphics/audio/input connections | Guest syscall policy or native resource lifetime |
| `namespace_run.c` | Explicit launch configuration and native process owner | Global current distribution or automatic privilege switching |
| `bootstrap.c`, `elf.c`, `exec.c` | ELF admission/mapping, stack and exec reentry | Distribution-specific loader or libc policy |
| `supervisor.c` | Launch services, event-loop ordering, progress and cancellation | Operation-specific syscall implementations |
| `supervisor_task.c`, `supervisor_debug.c`, `supervisor_robust.c` | Task birth/exec/exit, guest debugger boundaries and retained robust-futex actors | Independent event loops |
| `supervisor_execution.c` | Shared entry, dispatch, native-effect completion and continuation selection | Duplicated resource semantics for TRACE and cooperative entry |
| `supervisor_actors.c`, `supervisor_memory.c` | Borrowed task calls, quiescence custody, mappings and private-memory repair | Unconditional process-tree stops |
| `supervisor_fs.c`, `supervisor_notifications.c`, `supervisor_proc.c` | Filesystem transport, notification reconciliation and guest proc views | Filesystem provider implementations |
| `supervisor_descriptors.c`, `supervisor_io.c`, `supervisor_domain.c` | Descriptor operations, captured IPC/control IO and namespace transitions | Interception of ordinary native read/write |
| `supervisor_identity.c` | Credential calls, capabilities and resource-limit copies | Native Android identity changes |
| `supervisor_signals.c` | Guest signal delivery, pending signals, masks, frames and signalfd publication | Independent task scheduling |
| `supervisor_seccomp.c` | Guest policy admission, listeners, TSYNC and inherited host-policy delivery | A second syscall implementation |
| `supervisor_readiness.c` | Poll/epoll admission, descriptor loans, waits and interruption | Ordinary native data IO |
| `fs_worker.c`, `namespace_broker.c`, `fs_engine.c` | Captured filesystem requests and provider dispatch | Application IO through ordinary open data descriptions |
| Inode/image owners | SQLite namespace, metadata, shared bodies, overlays, import and maintenance | Android mount privileges or kernel hardlinks |
| `fs_continuation.c`, provider continuations | Captured asynchronous filesystem stages and cancellation | Blocking the filesystem worker while a guest daemon runs |
| `fuse_device.c`, `fuse_session.c`, `fuse_view.c`, `fuse_file.c` | Guest FUSE transport, mount protocol, names and open descriptions | Native Android FUSE permission or a coherent shared page cache |
| `guest_execution.c` and operation handlers | Admitted task-affine operations, shared by ingress adapters | Register ABI, tracee resume or a second resource model |
| `interception.c`, `cooperative_entry.h`, resident SVC driver | Register adaptation, caller actions and continuation delivery | Duplicated syscall semantics, SQLite or app-side services |

The supervisor operation modules are separate translation units in the same
process and event thread. Their private headers describe continuation state and
direct calls; `supervisor_thread.h` embeds each operation owner's state in the
shared task record, without per-call allocation or another dispatch layer, and
`supervisor_internal.h` exposes coordinator services. These are not guest ABI or
worker RPC interfaces. Each operation owner handles its control messages,
continuations and cancellation; the coordinator preserves event custody and
ordering. The supervisor-state fixture compiles these same sources together for
white-box assertions, without exporting test-only entry points or duplicating
their state machines. Production builds compile each owner separately.

`GuestEnvironmentLibrary` and `GuestOciRegistry` are app-side management, not
part of this native module. Their Java command helper is short-lived; the
original shell execs the native runner to retain its PTY and graphical FDs.
The native `image` utility owns validated import, snapshots, backup, maintenance
and launch data. Standalone `service` and offline clients reuse the filesystem
engine rather than a separate filesystem implementation.

Named environments use the inode backend. A direct-rootfs backend remains a
low-level fixture/control path, never a fallback after a namespace error.
Each launch captures its identity and environment. Separate launches may share
a store, but each retains its own supervisor and process tree. Persistent store
lifetime is independent of launch lifetime.

### Shared parsing and custody

Low-level framing is shared without moving resource policy into the parser:

- `mapping_reader` consumes retained maps/smaps descriptions for image,
  protected-memory and machine-entry owners. It does not reopen a PID or cache
  mapping authority. Consumers retain their own bounded traversal and admission.
- `directory_record` owns the `getdents64` layout, bounded decoding and padded
  output for inode, proc, devpts, provider and FUSE directories. Directory
  cookies, membership, permissions and cursor publication remain provider-owned.
- `netlink_attributes` owns aligned attribute traversal for route, generic and
  nftables protocols. Their supported types, flags, nesting and transaction
  semantics remain distinct; sharing framing does not expand kernel emulation.
- `task_memory` reads guest strings in page-bounded chunks. A terminator before
  an inaccessible page remains valid; callers preserve their own error contract.
  Retained-memory reads require explicit owner-provided authority. Executable
  metadata uses it only for authorized same-process inspection, never
  to authorize a foreign process's proc access.
- `received_fds` owns exclusive parcel accumulation, immutable shared custody
  and provenance. Geometric growth transfers no FD twice and leaves both inputs
  unchanged on allocation failure.

### Process and resource lifetime

Kernel tasks and open descriptions are not replaced by simulated processes or
file data. Logical owners follow their own sharing rules: address space,
`CLONE_FILES`, `CLONE_FS`, namespace membership and `CLONE_SYSVSEM` are distinct.
Fork, unshare and exec prepare changes before native execution and publish only
confirmed results. Native rejection preserves prior ownership.

[Robust mutex cleanup](process-lifetime.md#robust-mutex-ownership) uses the shared
retained-memory, task-call and child-reaping owners under User and logical PID
namespaces. Shell otherwise retains native cleanup. Ordinary futex IO remains
native in both executors.

The event loop services ptrace, worker and descriptor completions together.
Task and memory progress uses bounded event-count batches between readiness
checks. An idle task queue enters the shared blocking wait immediately; its
readiness dispatch also satisfies the next check. No timer delays accumulate work.
Resource-specific queues avoid repeatedly scanning every guest thread. Readiness
or completion events, not settling delays, resume work; cancellation and optional
deadlines share exact tree cleanup. A request can outlive its caller, so task exit
does not authorize releasing worker storage or an unobserved descriptor result.
Releasing a memory-admission barrier also rechecks retained worker completions
and descriptor continuations: their original readiness notification may already
have been consumed while admission was closed.

See [process lifetime](process-lifetime.md), [interception](interception.md)
and [process resources](process-resources.md) for scheduler, cancellation,
quiescence and ownership rules.

### Filesystem and descriptors

SQLite records directories, shared guest names, inode identity, permissions and
layer relationships. Eligible private regular files keep native names and need
no catalogue writes for create/rename/unlink; exceptional metadata stays with the
inode. Writable overlays admit conflict-free native names in already-published
upper directories; upper and merged projections share one physical body.
Lower-name conflicts, whiteouts and copy-up retain transactional publication.
Hardlink, pathname-based execution and shared-source boundaries promote
the same native inode through a recoverable publication owner. Descriptor
execution instead retains its exact process-owned image origin. Native backing
objects carry file data. One inode can have several names without
native hardlink permission. Open data IO, mmap and file locks stay in the kernel.
Metadata transactions, copy-up and watch publication share the namespace owner.
Workers cache metadata within explicit snapshot/invalidation rules, not caller
permissions or reusable FD numbers.

A file-table turn captures exact input descriptions and orders close/dup/result
publication. Stateful inputs are never replaced by reopening a pathname. Results
use retained sources and confirmed kernel publication; cancellation releases
unpublished resources without closing an application's installed or reused FD.
No SQLite transaction or file-table turn may span an application readiness wait.
Mixed poll/epoll keeps native waiting in the calling guest's signal context.
Before an application installs seccomp, readiness selectors are prepared for
future virtual FDs, because the application may forbid further filter installation.
That sandbox path adds admission overhead even to native waits; ordinary
unfiltered workloads retain selective interception.
When all destination selectors are already prepared, descriptor duplication
uses the native mutation owner directly. It still orders shared-table changes,
retires internal loans and publishes only the kernel's actual result. Ordinary
ioctls need no watch discovery; only FIONREAD reaches that owner after typed
device, terminal and filesystem classification.
Inherited stdio can retain metadata-only O_PATH identity pins; these neither
keep pipes open for data IO nor supply filesystem authority. Filtered result
publication replays the original syscall admission and consumes the retained
result once, rather than introducing a helper syscall forbidden by the guest.

[FUSE](fuse.md) uses that same descriptor and mount model. A daemon request
suspends only its owning operation; ordinary inode/native operations do not
enter the FUSE protocol. Detached mounts retire on last-anchor release through
the filesystem event loop, not a periodic task or directory scan.

The [inode store](inode-store.md), [filesystem service](filesystem-service.md),
[descriptor contracts](descriptor-origins.md#descriptor-and-cwd-annotations) and
[ancillary transport](ipc-credentials.md#application-ancillary-transport) specify
cache validity, publication, cross-launch custody and output-fault limits.

### Guest policy

Guest users, groups, capability sets and namespace owners are inputs to shared
filesystem, IPC and process checks. They never become Android credentials.
Readonly/noexec mount policy, DAC/ACLs and supported Landlock rules enforce
actual guest access restrictions. Landlock filesystem ABI 1 works independently
of host Landlock support; it is not full native-LSM or ptrace-domain confinement.

Logical user/PID/mount/UTS/IPC/network/cgroup namespaces and private devpts views
support nested Linux workloads without requiring their host kernel equivalents.
Devpts retains native PTY numbering, IO and lifetime. Its `max` option rejects
indices outside the requested range before descriptor publication; host PTYs
share that range and can reduce available capacity. It is not an independently
numbered kernel devpts instance. O_PATH does not allocate a PTY or consume a slot.
Ordinary networking remains native; routed IPv4/IPv6 is explicit and has its own
port/endpoint ownership. Shared [launch profiles](networking.md#launch-profiles)
prepare host, offline or bridge policy and optional TCP/UDP publications/DNS;
the low-level routed mode remains available for guest-managed topology.
Namespace-owned nftables rules, logical flow routing
and event-driven forwarding are separate owners: atomic rule/port publication
does not put an interpreter on ordinary stream reads/writes. Supported filtered
and NAT flows use cached decisions and bounded relay queues. Private UDP retains
queued source identity through the same relay; unfiltered TCP and ordinary host
network IO keep their native data path. Native backing
ports are not host-firewall isolated; unsupported rule operations fail explicitly.
Cgroups enforce hierarchical pids limits and freezer state, and provide sampled
CPU/RSS accounting. CPU quotas, cpuset affinity, memory and device policies remain
compatibility state, not enforced limits. Missing network, mount or device
operations fail explicitly. See [cgroup contracts](namespace-providers.md#cgroups).

The supervisor preserves application seccomp filters. Stock Chromium runs with
installed filters and without `--no-sandbox`, and guest debuggers use a virtual
trace relationship under the same supervisor. These execution results do not
establish native-Linux sandbox equivalence. Unadapted syscalls, inherited host
descriptors and the same-UID broker retain authority outside guest policy;
interpreter/library admission and complete descriptor/root confinement remain
incomplete. Use trusted inputs.

`seccomp_install.h` classifies both installation APIs and their native results
for the shared supervisor: listener FDs are successful results, while a
positive-TID TSYNC failure does not publish a new filter. Both host identities
reuse that contract. `seccomp_policy` supplies immutable snapshots,
chain references and selective cBPF routing. `seccomp_program` is the shared
allocation-free evaluator for coordinator objects and read-only entry views;
it has no syscall, listener or scheduler ownership. View export can retain an
existing ancestor mapping instead of copying the whole chain on each install.
Each immutable chain caches only proven argument/IP-independent ALLOW decisions;
all other inputs retain interpretation. This cache is built at installation,
not in ordinary syscall handling. IP-independent chains with an allowed inert
checkpoint can publish fresh entry views after exec. IP-sensitive inherited
chains retain Base; old code-pool addresses never authorize a new image.
Owned service gates use one exact-PC/range matcher with optional syscall scope;
IP-independent constant-ALLOW exits retain the kernel's cacheable path.
IP-dependent policies route owned aliases before evaluation so original-site
recovery cannot be bypassed by a different ALLOW decision at the alias.
Application-UID
execution uses it to admit cold Android-denied calls against original guest policy;
the ordinary UID-2000 path does not enable host-SIGSYS translation.
`seccomp_capture` lends the exact routed image from a peer-read-only
`interception_buffers` pool until native completion or confirmed death. The
caller owns guest copies and the existing address-space turn against mapping
replacement; the policy module owns no guest memory, signal frames or process
scheduler. Ordinary writable request pools are unchanged. See the
[host execution contract](../guest-exec-lab/host-execution.md) for current
coverage and limitations.

The `seccomp_notify` owner supplies a virtual notification queue
through the existing control-file ioctl/readiness model. The typed listener
uses ordinary FD-table, fork and SCM_RIGHTS ownership. Its output acknowledgement
reuses terminal buffer completion, and is requested only by RECV-like operations
whose state depends on successful guest copying. The queue and last-close
semantics, last-task HUP independent of FD lifetime, receiver-relative PID views
and metadata-only O_PATH reopenings are coordinator-tested. Guest filter
installation, original-call dispatch, CONTINUE, interruption/restart and ADDFD
are connected to this owner. Typed ioctl input capture and `fd_install` retain
source OFDs, fixed-slot metadata and atomic SEND semantics, including protected
callers. Native read/write/poll paths do not consult this queue.

The engine's `signal_actions`, `signal_view` and `task_signal_control`
owners preserve logical SIGSYS actions, masks, pending state and handler-entry
frames, including nonlocal handler exits. Proc status and debugger mask views
reuse those owners; proc status does not expose an adapter's temporary mask.
Protected copying borrows the existing `task_call` owner;
ordinary native IO acquires no additional turn or observation. `signal_pending`
separates thread-private and process-shared SIGSYS queues and the shared-sighand
wait domain. Other signals retain native kernel queues. `signal_fd` integrates
signalfd/read/readv, typed FD transfer, poll and task-affine epoll through the
existing descriptor owners. Epoll publication commits logical ET/ONESHOT state
only after the caller's copy succeeds; native task events validate the actual
output buffer before consumption. The mapping turn covers that nonblocking
publication, never a blocking wait. Parked waits release shared transport slots.

Temporary masks for ppoll, pselect6, epoll_pwait/pwait2 and sigsuspend retain
kernel handler-entry and sigreturn semantics, including nonlocal handler exits.
TSYNC retains immutable admission snapshots for already admitted calls and
pending Android denials. A group barrier is limited to filter installation;
ordinary syscalls do not acquire global entry tracing. Owned completion gates
finish the original admitted request without replaying it through a later filter.
This admission/notification lifecycle is shared by app UID and UID 2000;
only translation of Android's inherited SIGSYS policy is app-UID-specific.
`guest_debugger` supplies typed seccomp events only when its host explicitly
owns guest-filter admission. The production supervisor does not enable that
TRACESECCOMP capability.

See [guest credentials and Landlock](../../docs/shroot.md#guest-credentials),
[namespace providers](process-resources.md) and
[image admission](interception.md#image-admission-and-logical-credentials).

## Application Integration

Graphics, audio, gamepads, files and command access are connections supplied by
existing MagicDesk services. The native runtime does not acquire Desktop, HOME,
an Android display or input ownership. Renderers stay under the app UID even
when the selected executor is root.

For X11/Wayland, exact pathname/abstract socket routes select an executor-owned
admission endpoint. Android authenticates the executor and transfers the connected
FD to the protocol server; protocol bytes and SCM_RIGHTS then travel directly,
without a stream proxy. X11 still requires its cookie, and independent Wayland
clients open independent connections. See [graphical connections](../../docs/shroot.md#graphical-connections).

General guest pathname sockets instead use inode names backed by unique abstract
kernel endpoints. Bind publication and peer identity use the filesystem/IPC
owners; established payloads remain native. Explicit abstract addresses retain
host namespace semantics. These sockets are not the graphics admission service.
Direct-backend pathname bind remains unsupported on the tested shell domain.

File exchange uses a helper inside the selected guest identity and filesystem,
never guessed paths to inode backing files. XKB export uses the store-derived
`guest:/absolute/store` source. Guest `.desktop` declarations cannot override
host storage identity or admission routes. Distribution session D-Bus comes from
the guest; the APK ships no D-Bus daemon.

Optional `--magicdesk` access is explicit launch-scoped delegation through the
[command client](../command-client/README.md), shared with PRoot/native Termux.
It is not an implicit consequence of guest root and is separate from syscall
adaptation.

## Contracts

| Contract | Responsibility |
| --- | --- |
| [Interception](interception.md) | Selectors, task-affine calls, protected processes, exec, application filters and private-memory repair |
| [Cooperative entry](cooperative-entry.md) | Shared exchange, guest/worker/supervisor execution ownership, bounded operation windows, fallback and filter limits |
| [Process lifetime](process-lifetime.md) | Launch ownership, cancellation, reaping and teardown |
| [Process resources](process-resources.md) | Sharing, mounts, namespace providers, descriptor custody, network, terminals and cgroups |
| [Namespace execution](namespace-execution.md) | Path/FD dispatch, cwd, metadata and proc object identity |
| [Inode store](inode-store.md) | Transactions, backing objects, logical metadata and cache invalidation |
| [Filesystem service](filesystem-service.md) | Request transport, worker ownership, cancellation and uncertain outcomes |
| [Images and views](images.md) | OCI/rootfs import, shared layers, copy-on-write, attachments and backup |
| [Watches](watches.md) and [pipes](fifos.md) | Namespace/data events, readiness, queue and rendezvous lifetime |
| [Unix IPC](ipc-credentials.md) | Peer identity, authenticated FD origins and batch publication |
| [Shared memory](shared-memory.md) and [SysV IPC](sysv-ipc.md) | Namespace scope, mappings, semaphore undo, messages and waits |
| [Debugging](debugging.md) | Same-launch GDB/LLDB/gdbserver/strace and supported trace operations |
| [Kernel compatibility](kernel-compatibility.md) | Linux 5.15 baseline, newer dependencies, probes and fallbacks |

## Execution Policy

Minimum supported Linux is **5.15**, independently of the APK's API 34 and
Desktop's API 35. Record every dependency newer than 5.15 in
[kernel compatibility](kernel-compatibility.md), including flags and semantics.
An available header or kernel version does not establish firmware permission.
Guest probe failure leaves independent MagicDesk services available.

The selected identity must be allowed to execute helpers and map guest code.
ELF segments use private RW mappings followed by final protections, never RWX.
Fixed images use `MAP_FIXED_NOREPLACE` and returned-address validation; conflicts
fail before replacement. Hardened policies may still deny RW-to-RX transitions.

Program admission checks X_OK and rejects actual host set-ID executables.
Logical guest set-ID uses the selected file's metadata and a sealed execution
snapshot, with `nosuid`, no_new_privs, tracing and user-namespace checks.
Initial entry and reexec share preparation; no helper-specific option is needed.
X_OK is not every kernel/LSM execution check. `AT_EXECVE_CHECK` is rejected, not
stripped or reported as successful. Helper bundles are immutable; exec carries
configuration explicitly and retains exact images rather than reopening names.
The internal resume entry is not an authorization boundary.

## Build And Verification

`CMakeLists.txt` builds API-34 executables with pinned, hash-verified native
dependencies. SQLite belongs to the namespace owners, not ART or the freestanding
adapter. Image archive/compression dependencies are declared in
[image-dependencies.cmake](image-dependencies.cmake). The APK packages helpers and
license notices; guest libc, desktop packages and GPU drivers remain guest-owned.

```sh
./gradlew :app:assembleDebug
```

`MAGICDESK_GUEST_FIXTURES` enables native fixture targets. The
[fixture guide](../guest-exec-lab/README.md) supplies preparation and device
commands. Kernel/ABI, filesystem, packages, browsers, debugger, init, container
and GUI/GPU workflows are separate checks; Desktop self-tests do not replace them.
Read [coverage and limits](../../docs/shroot.md#coverage-and-limits), the
[init matrix](../guest-exec-lab/init-matrix.md), and the Docker/Podman fixtures
before extending a compatibility claim. UI pixels, actual IO, access denials,
process exit and resource cleanup are distinct assertions.
