# Process Resources

`task_resources` owns independently shared guest state in the supervisor.
Its lifecycle follows successful kernel process/resource transitions, not
Android Activities, named environments or launcher configuration.

| Resource | Clone sharing | Unshare | Exec |
| --- | --- | --- | --- |
| Filesystem context | `CLONE_FS` | `CLONE_FS` | Retained |
| Mount view | Shared independently of `CLONE_FS`; `CLONE_NEWNS` copies | `CLONE_NEWNS` copies and separates FS context | Retained |
| Descriptor-origin annotations | `CLONE_FILES` | `CLONE_FILES`, `CLOSE_RANGE_UNSHARE` | Detach; remove close-on-exec entries |
| Semaphore undo | `CLONE_SYSVSEM` | `CLONE_SYSVSEM` | Retained |
| UTS names | Shared; `CLONE_NEWUTS` copies | `CLONE_NEWUTS` copies | Retained |
| SysV IPC namespace | Shared; `CLONE_NEWIPC` creates an empty namespace | `CLONE_NEWIPC` creates an empty namespace and separates semaphore undo | Retained |
| User namespace | `CLONE_NEWUSER` creates a nested owner | Nested owner with immutable ID maps | Retained |
| PID identities and child namespace | New TID, shared TGID for threads; `CLONE_NEWPID` selects a new child namespace | Changes only future child admission | Retain TGID; retire former TID on nonleader exec |
| Process limits | `RLIMIT_NPROC` and `RLIMIT_NOFILE` shared by `CLONE_THREAD`, copied otherwise; each task has one UID charge | User-namespace accounting follows the selected identity | Retained; pending real-UID quota checked before exec |

The kernel owns cwd, umask, descriptor tables and open-file descriptions.
The filesystem context owns guest root selection, optional cwd provenance and the admitted proc-root
restriction. `task_registry` pins that context alongside each task's identity;
registry references do not count as task owners. Both filesystem request
transports use `md_fs_context` to capture identity, filesystem owner, root, cwd
origin, mount view and descriptor-origin table in one authenticated registry
snapshot. The file-table handle remains live; it is not a frozen copy of its
entries. A missing task mount view fails explicitly; the worker's initial view
is not a fallback for another task.
See [namespace execution](namespace-execution.md) for the launch filesystem and
[interception](interception.md) for restricted domains.

- [Contracts](#contracts)
- [Clone Admission](#clone-admission)
- [Process Limits](#process-limits)
- [Request Context](#request-context)
- [Resource Changes](#resource-changes)
- [Boundaries](#boundaries)

## Contracts

| Area | Reference |
| --- | --- |
| Mount Views And Path Origins | [mount-views.md](mount-views.md) |
| Descriptor Origins And Publication | [descriptor-origins.md](descriptor-origins.md) |
| Namespace Providers | [namespace-providers.md](namespace-providers.md) |
| Guest Networking | [networking.md](networking.md) |
| Resource Ownership Verification | [resource-tests.md](resource-tests.md) |

## Clone Admission

Legacy `clone` and versioned `clone3` use the same resource, PID, file-table and
application-filter admission. `clone_call` copies and validates the `clone3`
argument structure in the caller, including protected processes. The original
application memory is unchanged. Only implemented namespace flags and
`CLONE_UNTRACED` are removed from the private kernel argument copy; the kernel
still creates the task at its original syscall site and requested child stack.
The existing signal shield covers preparation and replay, then restores the
application mask immediately before kernel creation so the child inherits it.
Denied or failed creation releases the same reservations and shield.

`pid_namespace` reserves requested `set_tid` values in logical PID views, including
ancestor permission checks. `cgroup` reserves v2 placement before a
`CLONE_INTO_CGROUP` birth. Failed creation releases both reservations; publication
follows the confirmed child. A requested native ancestor PID still requires the
host kernel's permission. Guest IDs and cgroup handles never substitute for
unrelated host objects.

Clone-created and received pidfds acquire logical identity on use through exact
descriptor capture, not a guessed FD number. `test_clone3.py` covers Base and
lazy entry, fork/vfork/thread stacks, TLS, TID writes, pidfd wait, logical PID
selection, cgroup placement, protected callers and rollback. Versioned read-only
arguments at a guard page, seccomp denial and signal-handler reentry retain
native argument, mask and disposition checks.

## Process Limits

`process_limits` owns guest `RLIMIT_NPROC`, `RLIMIT_NOFILE` and real-UID/user-namespace task counters
within one supervised launch. It reserves a charge before native clone, transfers
it only to the confirmed child and releases it on failure or task disposal.
Threads share the process limit; independent processes inherit a copy. Registry
custody retains unreaped zombies. A quota-pressure check can release kernel-reaped
charges without discarding identities still needed by a pending translated wait.
Normal file/socket IO does not inspect counters or scan tasks.
Cached accounting scopes use stable namespace identity values, including their
generation, rather than owning namespace references. Keeping an accounting
ceiling must not keep a dead user namespace charged against its ancestor quota.

`getrlimit`, `setrlimit`, `prlimit64` and typed proc `limits` agree. Foreign target
authorization uses guest credentials; raising the hard limit requires initial
user-namespace `CAP_SYS_RESOURCE`. Root/capability fork exceptions and deferred
exec rejection after a real-UID change follow the Linux model. Application
seccomp denial still wins. Task-affine memory copies preserve protected-process
and `EFAULT` behavior; a failed old-limit copy does not undo an applied update.

`NOFILE` separates the logical guest limit from the executor's physical FD budget.
An update applies `min(guest soft, executor hard)` to the native soft limit before
publishing guest state. It never lowers the native hard ceiling: a privileged
guest can lower and later raise its logical hard limit without requiring Android
root. The initial ceiling is inherited and retained per process; reading a foreign
process's native limit is not required (Android may forbid that read while allowing
the update). A failed native update leaves guest state unchanged. Native allocation,
`dup`, sockets, pipes and received rights still enforce the effective soft limit;
ordinary reads/writes acquire no new interception or accounting checks.

The read-only guest `fs.nr_open` reports a logical policy ceiling of 1048576,
independent of the host's sometimes unreadable sysctl. This is not a guarantee of
1048576 usable physical descriptors. Allocation can exhaust the Android budget
earlier, with its real error/truncation behavior; native FD-number and poll-vector
ceilings are not expanded. Existing high-numbered FDs remain usable after lowering
the limit. No per-application or container-engine limit override is needed to set
the usual OCI logical limits.

This is not a host-wide quota or a cgroup pids controller. Separate launches have
separate counters, even if they share a store. Other resource limits, Android's
physical task ceiling and real-time scheduling permissions remain native. A
logical capability does not grant Android `CAP_SYS_NICE` or raise host hard limits.
Coverage: `test_nproc.py` (NPROC and NOFILE, both entry modes), `test_process_limits`, packaged `test_rtkit.py`, and
Fedora `test_os_boot.py --rtkit` with the original service sandbox.

## Request Context

The namespace notification broker and task-affine RPC service retain the same
request context from admission through execution and delivery cleanup. It binds
the task and authenticated peer once; dispatch cannot substitute either identity
or resolve a replacement task record. Direct notifications capture it before
duplicating input descriptors; RPC captures it after packet validation and before
dispatch, including watch reads. Delegation releases the direct context; the
task-affine request authenticates independently.

Registry replacement, task exit and resource-owner release do not invalidate an
admitted request. Guest chroot publishes into its captured filesystem owner,
not a fresh lookup of the task ID. Real-ID access checks use a local credential
value without changing the captured identity. Execution borrows the retained
view/root and restores the worker's previous context on both success and failure.

Context storage belongs to the existing operation or peer pool, and retaining
resources adds no per-request heap allocation or RPC. References survive reply,
watch acknowledgement and client-release phases, and are released on completion,
failure, timeout or worker shutdown. The filesystem worker/store must outlive
them. Synchronous internal engine callers use a scoped context through
`md_fs_execute`.

Retained authentication is not cancellation rollback. Input-descriptor capture
and result publication use the
[file-table publication ordering](descriptor-origins.md#publication-ordering);
a failed reply does not undo committed IO.

The filesystem worker accepts intrusive host-side work records and publishes
results to explicit owner callbacks on the supervisor. Seccomp delegation uses
that same completion dispatch; no receiver may cast unrelated work to its own
operation type. Submitted work runs in bounded batches alongside socket and
notification readiness, with no queue lock covering filesystem execution or
completion callbacks. Queueing itself allocates no records. Stop/failure closes
admission and retires pending work with an error before the store is released.
Terminal owners retain their records through worker join and release their own
results; worker stop does not invoke supervisor callbacks during teardown.
An accepted job retired with an error has no filesystem context. Mount copy/join
and other work handlers propagate that error before accessing the store or
creating a candidate. Intrusive request ownership survives until completion or
the terminal owner's post-join cleanup.
Ordinary direct notification handling does not traverse the submission queue.

`interception_buffers` provides bounded shared payload slots for the production
syscall adapter and its transport fixture. The supervisor
alone owns allocation metadata, generations and references outside the shared
mapping. A mapping owner and each independent slot lease retain the pool.
An admitted worker pins its slot before submission and borrows only its byte
span, never the allocator. Task death or replacement of the mapping owner cannot
reuse a slot until that worker and its result release it. Exhaustion returns
EAGAIN for event-driven admission; slot reuse allocates no memory or descriptor.
The memfd has sealed dimensions, not immutable contents. Inputs are copied into
validated worker-owned requests; generation checks prevent stale host handles,
not cross-process tampering with mapped bytes or security-domain separation.

`adapter_buffer` exposes the same lease lifecycle to filesystem requests, bounded
SysV packets, shmctl metadata and private-helper control parcels. Protocols supply their own validated views;
the allocator owns no filesystem or IPC policy. A blocked SysV operation retains
its captured request outside guest memory and releases its slot before waiting.
Its watcher and epoll registration are supervisor-owned. Empty task-side ppoll
preserves the original signal mask; exact watcher events interrupt it without
an application signal or a guest wait FD.

`fd_install` owns atomic ADDFD_SEND publication for mapped filesystem results and
foreign-process image opens. The result provider retains the source FD while the
destination table turn is queued. An expired notification is distinct from a
delivered result: a live task may restart admission after a signal or debugger
stop. The provider retains its already-open result across that restart, without
repeating filesystem creation. A restarted admission may retire an expired
queued turn for the same result; a live notification cannot be replaced. Task
teardown releases the retained provider reference. After installation, the kernel result publishes origin
metadata before the next table mutation; task death cannot turn it into rollback.
An internal retirement/wakeup error is separate from the committed kernel result
and cannot produce a second notification reply. O_PATH uses its separate retained
source/rights path because the kernel rejects O_PATH in ADDFD.
SHM attachments instead use a same-address-space helper with a private file table.
Its descriptors never enter the application's table. The helper, its buffer and
mapping observation remain owned until reaping, including caller death and exec.
An address-space resource turn orders mapping publication, detach, destructive
memory calls and fork. See the [shared-memory contract](shared-memory.md).

An inode origin in the current view with no attachments uses the inode engine's
single path walk after descriptor-identity validation. It does not run a second
mount traversal or cache lookup results. Different views, native roots and
attachments retain composed traversal. Absolute paths ignore an unused base;
openat2 scoped resolution still validates it. The anchor fixture compares SQL
step/transaction counts with the unannotated path and checks stale-base errors.
## Resource Changes

`unshare` prepares candidate guest-owned resources before resuming the kernel
call. The syscall-exit observer commits them only if the kernel succeeds.
Failure, cancellation or task death releases the candidate; no partial guest
change is published. The kernel validates `CLONE_VM`, `CLONE_SIGHAND` and
`CLONE_THREAD` constraints. Unsupported namespace flags fail before changing
any resource, including when combined with otherwise supported flags.

An unshared filesystem context copies retained root descriptors only when
there are other owners. A sole owner needs no copy. Semaphore undo starts empty
when split; leaving the old undo group applies its adjustments only when the
last owner leaves. Ordinary fork starts an independent empty undo group.

Fork ownership is published from the kernel's birth event before either
participant resumes guest execution. Nonleader exec transfers the executing
thread's resources to its resulting process identity; teardown of the replaced
leader must not release the executing thread's owners. Kernel exec detaches
shared descriptor tables, but not shared filesystem context or semaphore undo.
Exec prepares its detached annotation owner before the kernel call. The confirmed
exec event commits that owner and removes close-on-exec entries before transferring
the executing nonleader's resources. Failed exec does not change the owner.
Fork copies annotations at its actual birth event and releases the turn before
waiting for vfork completion. `close_range` with CLOSE_RANGE_UNSHARE uses the same
prepare/commit/rollback transaction as `unshare(CLONE_FILES)`, then applies its
range operation only after kernel success. Ordinary close_range, close and
descriptor-flag calls use their actual results without emulating native data IO.
## Boundaries

Concurrent named environments and launch-local attachments are not kernel Linux
namespaces. Guest `CLONE_NEWNS`, `CLONE_NEWUSER`, `CLONE_NEWIPC`,
`CLONE_NEWUTS`, `CLONE_NEWPID`, `CLONE_NEWNET` and `CLONE_NEWCGROUP` have the
[provider](namespace-providers.md) and [network](networking.md) contracts. Time namespaces are not implemented; their creation is not
reported as successful. POSIX message delivery and UTS sysctl priority-change
notification are not implemented. They must not be inferred from the limited
mqueue filesystem, SysV separation or live sysctl reads. Tmpfs quotas remain open; unsupported
options fail explicitly. Saved overlay credentials do not establish
kernel-equivalent confinement for unadapted native access.

Proc mounts are selected process views, not a complete virtual kernel procfs.
The initial `/proc` uses the same provider as explicit proc mounts, with native
PID numbering until a PID-scoped mount is requested. Process directories share
the typed map-entry catalogue; network directories and sysctls select the
caller's network namespace. Mounted lookup, relative directory opens and
enumeration do not fall back to Android's interface inventory.
Host-global `sysrq-trigger`, `kcore`, `kmsg`, `keys`, `kallsyms` and `asound`
are inaccessible placeholders, not Android kernel controls. Their names and
O_PATH handles remain available as container mount targets; data access fails
with EACCES until a guest mount covers them. Other native permission errors
remain errors, not missing files.
Protected self-proc access is completed in the actual caller after the walker
admits the selected mount and path. Final no-follow magic-link metadata and
O_PATH capture retain their mount identity and openat2 restrictions. Only an
unfiltered native directory may delegate getdents to its caller; logical PID
filtering is not bypassed. None of these paths changes dumpability.
Native proc descriptors outside these mounted views are unchanged.
Unmanaged processes retain native admission for process directories and
`status`, `stat`, `limits` and `cgroup`; these reads do not invent guest identity
or cgroup membership for Android tasks.
Foreign `/proc/PID/mem` and signalfd sender translation remain outside this contract.
Inherited native proc descriptors retain their original view. Internal helper
threads are filtered in a logical PID proc mount, not in Android's native procfs.
Guest ptrace limitations are recorded in [debugging](debugging.md).

Guest filesystem calls use mapped requests, not private per-call RPC sockets.
The admitted table-owned export/return endpoints have explicit table lifetime;
ordinary requests borrow them. Sending credential capabilities stay in the
worker and a private-table helper, whose reaping bounds their lifetime. Admission
of export channels, direct ADDFD results and private-helper descriptors uses
exact gettid notification sites. Application filters still apply; denying prctl
does not prevent these admissions. A denied admission returns its error before
publishing a descriptor. Receiving
uses a private table and supervisor-owned source descriptions until kernel
publication directly into application control memory. Cancellation releases only
unpublished resources; published rights belong to the application. Batch
publication ordering and native quota differences remain explicit limits in
[Unix IPC Credentials](ipc-credentials.md). Direct-image fallback descriptors
still need terminal-custody coverage. Cleanup cannot safely close remembered
numbers after reuse, or assume a dying task can execute another syscall.

The service owns its accepted endpoint, receipt and retained request context.
Closing the authenticated exchange signals a launch-owned eventfd, independently
of client-socket EOF. The worker releases that peer on cancellation or completion,
including cancellation while dispatch is executing. No request is replayed and
an installed guest result is not closed by its former provider. A pending watch
reservation is restored before reply publication, or retired after publication
when copying is unconfirmed. Rights already queued in the guest endpoint still
belong to that kernel socket; retiring the service is not guest-slot reclamation.

This resource model does not establish security isolation from Android, other
launches or peer guest processes. Programs retain the executor's host authority;
successful browser execution is not proof of complete sandbox equivalence.
