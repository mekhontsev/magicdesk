# Shroot Native Runtime

Shroot is a native ARM64 Linux userspace runtime for MagicDesk's already-authorized
shell or root executor. Shell UID 2000 is the unrooted baseline. The APK packages
standalone executables, not libraries loaded into ART. Guest-only capabilities
are probed on explicit use and never become prerequisites for other services.

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
Application filters, protected-state transitions and debugger ownership revoke
entry through the common admission boundary. Unavailable capabilities and
unsupported sites retain Base interception; `--env SHROOT_MACHINE_ENTRY=0`
selects Base explicitly. See
[integration evidence](../guest-exec-lab/svc-rewriting.md#product-boundary).

One supervisor owns each process tree. Selective seccomp TRACE/USER_NOTIF directs
operations needing adaptation to either a task-affine adapter or a filesystem
worker. The supervisor is the sole kernel ptracer. Ordinary unselected data IO,
memory, futex and signal-mask operations retain native execution; signal sending
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
| `supervisor.c` and task owners | Kernel tracing, continuations, guest identity, cancellation and terminal cleanup | Android windows or a second kernel scheduler |
| `fs_worker.c`, `namespace_broker.c`, `fs_engine.c` | Captured filesystem requests and provider dispatch | Application IO through ordinary open data descriptions |
| Inode/image owners | SQLite namespace, metadata, shared bodies, overlays, import and maintenance | Android mount privileges or kernel hardlinks |
| `fs_continuation.c`, provider continuations | Captured asynchronous filesystem stages and cancellation | Blocking the filesystem worker while a guest daemon runs |
| `fuse_device.c`, `fuse_session.c`, `fuse_view.c`, `fuse_file.c` | Guest FUSE transport, mount protocol, names and open descriptions | Native Android FUSE permission or a coherent shared page cache |
| Guest adapter | Task-affine operations and protected-memory copying | SQLite, a libc dependency or app-side services |

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
  metadata uses it only for same-process inspection after `dumpable=0`, never
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
Cgroups are intentionally permissive compatibility
providers, not enforcement of CPU, memory, pids or device limits. Missing network,
mount or device operations fail explicitly.

The supervisor preserves application seccomp filters. Stock Chromium runs with
installed filters and without `--no-sandbox`, and guest debuggers use a virtual
trace relationship under the same supervisor. These execution results do not
establish native-Linux sandbox equivalence. Unadapted syscalls, inherited host
descriptors and the same-UID broker retain authority outside guest policy;
interpreter/library admission and complete descriptor/root confinement remain
incomplete. Use trusted inputs.

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
| [Cooperative entry](cooperative-entry.md) | Shared direct entry, worker ownership, bounded operation windows, fallback and filter limits |
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
