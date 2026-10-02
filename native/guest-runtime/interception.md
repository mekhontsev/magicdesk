# Guest Syscall Interception

One native supervisor owns each explicitly launched guest tree. It uses seccomp
TRACE stops for adaptation and USER_NOTIF for descriptor metadata, selected path
operations, selected watch reads and the image-entry protocol. It never attaches to Android tasks.
The guest retains the selected real UID; neither the supervisor nor the filesystem
service changes SELinux policy or acquires root.

## Execution And State

The bootstrap publishes a private, size-checked ABI containing its gate addresses
at an explicit readiness stop. The supervisor reads it before resuming the guest;
exec republishes the same immutable bundle's ABI. No generated address header,
Java class name or environment-variable switch selects an implementation.

Command preparation owns the main ELF and optional interpreter descriptors,
resolved identity and argument vector. Both images survive the real kernel exec
into the bootstrap; it validates their load segments before mapping without
reopening filenames or reparsing the command. A failed exec releases both
descriptors. The opened program's object ID and canonical dentry path are captured
in the opening transaction and passed through the bootstrap handoff; descriptor
exec obtains identity from the retained FD. Bootstrap does not issue a second
object lookup. Namespace image admission and logical credential publication remain
separate from this handoff.

Data IO, common memory operations, futexes, clocks and signals pass directly to
the kernel. `MD_DOMAIN_KERNEL_CALLS` and `MD_DOMAIN_KERNEL_ARGUMENTS` describe
additional calls requiring no adaptation or observation in any guest domain.
The domain checker and seccomp filter consume these same definitions. Argument
predicates compare the full 64-bit value; other commands retain the dispatcher.
Application filters still evaluate kernel-fast-path calls. Logical credentials,
root changes, filter installation and dumpability changes retain their observers.
Watch descriptors install bounded FD-class read selectors lazily; reads in
unselected classes stay native. Kernel-object identity distinguishes watch
readers from ordinary descriptors sharing a class. Selected reads retain their
original signal context while the worker waits for records; protected readers
use task-affine copying and a native readiness wait. Filter installation ownership is separate from
address-space and descriptor ownership; see [filesystem watches](watches.md).
Adapted calls run on guarded scratch stacks leased per address space.
CLONE_VM shares the pool; fork copies mapping metadata without sharing leases;
exec replaces it. Completed calls return their lease. Thread records are reused
without a fixed thread-count ceiling. Heap work is confined to ownership changes
and new concurrency, not each ordinary intercepted call.

Regular-file seeks use a supervisor fast path after the original syscall's
seccomp admission and domain checks. A task-affine duplicated FD retains the
actual open-file description throughout inspection and seek; no FD-number
cache or namespace metadata RPC is involved. Shared positions and descriptor
reuse retain kernel semantics. Directories and unavailable remote descriptor
access use the ordinary adapter, including nondumpable processes.

`namespace_broker.c` handles ordinary openat/newfstatat/fstat/getdents64 notifications in the
namespace worker, alongside adapter RPC. The worker alone owns SQLite and the
sealed-image catalogue; `fs_engine.c` dispatches both transports. Eligible requests
copy paths page by page, retain exact-task descriptors and validate notification
IDs before execution and result publication. Successful opens use atomic ADDFD
plus response. The optional kernel synchronous-wakeup hint changes scheduling
only; unsupported kernels retain normal notification delivery.

Pidfds are owned by task lifetime, borrowed during request preparation and retired
on exit or exec. No file-number, cwd, path or metadata cache is involved. Published
supervisor state excludes restricted domains and adapter continuations from the
ordinary path. A shared-root restriction revokes every CLONE_FS member before
its caller resumes, including members not returning through a ptrace stop.
O_PATH uses SCM_RIGHTS because ADDFD rejects it. Mutating opens and
directory seeks retain the task-affine adapter and its no-replay contract.
Directory reads validate and enumerate through one namespace operation without a
preliminary metadata RPC. A reusable 64 KiB worker buffer bounds output independently
of the RPC frame, with no per-read allocation. The engine's synchronous output callback runs after
the read transaction, before advancing the actual open-file-description cursor.
Denied remote output can delegate only when no bytes or cursor position changed;
partial copies fail without automatic replay. Cancellation before publication
leaves the cursor unchanged. Cancellation after publication/advance may have an
uncertain outcome, like a lost RPC reply. Restricted domains retain their native
descriptor-read policy, and application seccomp filters still apply.

`magicdesk-guest --statistics --store STORE -- PROGRAM` enables aggregate
syscall/ptrace/stop counters, broker handling/delegation counts, RPC operation
times, SQLite costs and CPU usage, printed once on completion. Namespace-worker
CPU is a subset of supervisor process CPU, not an additional cost. Collection and clock
sampling are opt-in; there are no per-call logs. Operation times include kernel
waiting and descheduling; nested filesystem/SQLite totals must not be added as
independent CPU costs. Detailed `--diagnostics` is a separate mode; neither is
enabled for ordinary launches or comparative timing runs.

Internal sendmsg/recvmsg at the immutable raw-syscall gate go directly to the
kernel. These calls are already unconditional native operations in every guest
domain; the domain policy and gate share the explicit list. External calls still
enter their adapters. This removes redundant supervisor stops, not application
filter evaluation: ERRNO, TRAP and KILL also apply to the internal transport.
Internal fstat at the same gate reads native descriptor metadata directly; it
does not request logical inode or set-ID metadata. Application fstat retains the
namespace and image-catalogue path.

The guest owns SIGSYS, signal masks and alternate stacks. Native clone and signal
return retain their register/extension ABI. Application filters are not removed:
ERRNO, TRAP and KILL retain kernel precedence over runtime TRACE/USER_NOTIF.
Register changes and same-site replay are rechecked by the kernel. Cancellation,
group stops, concurrent TSYNC and non-leader exec have dedicated fixtures.

## Guest Kernel Interfaces

Nested guest debugging uses the [debugger contract](debugging.md). The supervisor
retains physical ptrace ownership; GDB requests do not replace syscall mediation.

Native `/proc` and `/dev` descriptors remain task-affine when the operation needs
no guest inode authority. SELinux can allow local metadata access but reject
descriptor transfer to another process. An ordinary notification that cannot
borrow the descriptor delegates to the existing task adapter. A failed protected
SCM_RIGHTS export returns an error and releases received descriptors; truncated
ancillary data is not a supervisor assertion or permission to bypass a guest domain.

`guest_domain` owns the distinction between an unsupported guest interface and
permission to access a host resource. Kernel audit has no guest implementation:
AF_NETLINK/NETLINK_AUDIT creation returns EPROTONOSUPPORT before a kernel socket
is created. The rule is independent of executable name, distribution, virtual
root and actual root. It applies at the existing socket syscall stop, including
raw SVC and nondumpable tasks, without additional ptrace stops or allocations.
Other socket protocols retain their existing kernel/domain checks.

No host denial is converted to success, no audit record is discarded after
claiming delivery, and Android's global audit service is not reconfigured.
Stock [shadow account tools](https://github.com/shadow-maint/shadow/blob/4.17.4/lib/audit_help.c)
already accept an unavailable audit protocol. Software requiring audit must treat
it as unsupported. Application seccomp ERRNO/TRAP/KILL still precedes runtime
TRACE handling, including this interface rule.

## Protected Metadata

The supervisor first attempts kernel remote-memory/descriptor access. A
nondumpable task can deny that access; the runtime never makes it dumpable to
proceed. The task-affine fallback exports a retained descriptor through SCM_RIGHTS,
uses notification ADDFD and resumes a bounded copy at the original call site.
Instruction-only copy faults return EFAULT. Notification IDs are checked when
replying; cancellation invalidates pending work rather than authorizing a replay.

Filesystem service authorization remains based on the real executor UID, not
the browser's seccomp domain. Same-site filtering alone is not a complete
authorization boundary for every broker request or in-process syscall gate.

## Image Admission And Logical Credentials

`--admit-elf /absolute/guest/path` explicitly selects one ordinary ELF for a
launch-local sealed snapshot and logical set-ID metadata. The filesystem service
owns the catalogue: path lookup, object reopening, hardlinks and FD metadata agree
on that admitted object. It does not chmod or replace the stored source.

Initial launch and exec use the same admission contract. The supervisor tracks
logical IDs, irreversible drops and no_new_privs, publishes auxv/AT_SECURE, and
applies the corresponding nondumpable state before loader entry. Real kernel
credentials remain those of the executor. Ordinary stored files retain their
logical ownership/mode without acquiring automatic set-ID admission; actual
set-ID backing executables remain rejected.

`--user` and OCI User share `guest_accounts` resolution and `guest_identity` with
image admission. Each task retains real/effective/saved/filesystem UID/GID and
immutable supplementary groups across fork and exec. Raw set-ID calls remain
thread-local; libc may broadcast them. `getgroups/setgroups` use checked copies,
with task-affine bounded batches for nondumpable/protected memory. An incomplete
input does not commit a new group list. Initial no_new_privs is inherited from
the executor and can only increase. Filesystem requests use the same identities
through `credential_registry`; this is guest DAC, not seccomp-domain confinement.

The proc-root model handles the restricted self/fd or fdinfo roots used by the
tested helper, with CLONE_FS ownership and irreversible narrowing. It is not a
general chroot or mount namespace. Namespace creation is not fabricated.

Stock Debian Chromium passes three fresh-profile headless JavaScript/DOM runs
and PNG production with its normal helper and no sandbox-disabling flags. Tests
observe installed application filters and require process completion. This is
application execution, not security equivalence to a native Linux sandbox.
Interpreter/library admission, mapped-code protection, complete descriptor/root
confinement and domain-authenticated filesystem authorization are not established.

## Lifetimes And Availability

The runner owns the guardian; the guardian owns the supervisor and its descendants.
The supervisor's namespace worker is joined after tracee cleanup. Signalfd, pidfds,
eventfds and child/ptrace events drive
completion and cancellation. There is no ordinary launch deadline; an explicit
test deadline fails and cancels the owned tree. Supervisors close unrelated
inherited FDs while the application retains caller-supplied descriptors.
See [process lifetime](process-lifetime.md).

The lazy guest probe executes under this supervisor and tests the actual kernel
operations in the selected shell/root domain. Denial fails that guest launch;
it does not disable Android tools, PRoot, chroot, graphics or Desktop. The APK
does not load these native executables into ART or probe them during startup.
Current device coverage is UID 2000, NX809J, Android 16, Linux 6.12.23, 4 KiB pages.
Other kernels, page sizes and actual UID 0 require separate device verification.
