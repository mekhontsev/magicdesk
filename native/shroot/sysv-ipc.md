# SysV Semaphores And Messages

The native supervisor owns a lazy, namespace-scoped semaphore and message-queue
authority. `ipc_namespace` selects it per task. `sysv_sem.c` and `sysv_msg.c` implement the ARM64 Linux operations;
`sysv_ipc_db.c` owns semaphore/message schema and transaction policy. `ipc_database`
shares storage admission, transaction gates, boot identity and launch leases with
`sysv_shm`; permissions belong to `ipc_permissions`. No daemon,
Android service or host SysV kernel support is required. Launches that do not
use these APIs open no IPC database, create no channel and install no waits.

## Calls And Memory

Supported semaphore calls are semget, semop, semtimedop and semctl
IPC_STAT/IPC_SET/IPC_RMID, GETVAL/SETVAL, GETALL/SETALL, GETPID, GETNCNT and
GETZCNT. Operation vectors commit atomically; a blocked or rejected vector
leaves every semaphore unchanged. Permissions use the task's logical identity
and capabilities scoped to the IPC owner's user namespace. Shared-memory,
semaphore and message permissions use the same `ipc_permissions` contract.
SEM_UNDO belongs to a reference-counted group: CLONE_SYSVSEM shares it, ordinary
fork does not, exec retains it, and the last exit applies it. SETVAL/SETALL clear
the affected adjustments. Exit adjustments clamp values to the supported range.

GETPID and the PID fields in message/shared-memory IPC_STAT are relative to
the reader's PID namespace. Mutations capture the process's namespace-number
chain through `ipc_pid`, reusing `pid_namespace` snapshots. Thread operations
record the process, not the calling TID. Snapshots survive exec, reaping and
native PID reuse; a process outside the reader's namespace appears as zero.
Native-namespace readers retain native numbers. Translation never looks up a
new live process by an old IPC object's numeric PID.

Message queues support msgget, msgsnd, msgrcv and msgctl
IPC_STAT/IPC_SET/IPC_RMID. Selection includes FIFO, exact type, the lowest type
under a negative bound and MSG_EXCEPT. MSG_NOERROR permits truncation. E2BIG
retains a message; as in Linux, a selected message is consumed before copying
it to user memory, so EFAULT does not restore it to the queue.

`ipc_calls.c` copies arguments and results in the calling task through a leased
slot in the common bootstrap-mapped adapter buffer. `adapter_buffer` owns its
lifecycle; `sysv_ipc_transport` bounds the typed packet and publishes offsets,
never unrestricted guest pointers. Copy faults return EFAULT;
PR_SET_DUMPABLE(0) remains unchanged. Calls need no temporary guest FD or mapping,
including with a full descriptor table. Completion, failure and task death
release the slot. A blocked request retains its captured inputs in the authority
and releases the slot before waiting. No database transaction spans guest copying
or execution.

## Waiting And Lifetime

`ipc/v2/sysv/ipc.db` contains namespace-scoped keys, monotonic IDs, metadata, bounded message bodies,
semaphore values, undo adjustments and waiter registrations. A native file lock
serializes transactions across independent launches; SQLite uses WAL. This is
transient IPC state, separate from OCI layers, filesystem names and snapshots.
FIFO message selection and typed selection have separate ordered indexes.
Semaphore waiter and undo lookups are indexed by object as well as by owner;
removing one set must not scan all other sets' bookkeeping. These rebuildable
indexes are prepared during IPC admission, never in the per-call path.
PID snapshot columns are admitted under the same gate and transaction, without
recreating IPC objects or changing the installed environment's storage format.
The initial namespace shares objects across launches within the same store and
boot. CLONE_NEWIPC creates an empty namespace; object IDs from another namespace
fail lookup even when its key is reused locally. Unshare releases the previous
SEM_UNDO group against its original authority. Isolated objects expire when
their last owner disappears; initial objects expire on a changed kernel boot ID.
The IPC storage version is independent of filesystem/OCI formats and does not
reinterpret an earlier database. This is not a kernel IPC namespace or security boundary.

The embedded database owner retains its directory until SQLite closes. Admission
holds the gate through schema setup, boot validation and owner registration.
Only committed registration permits owner cleanup; failed admission releases its
provisional lease without modifying another live owner. Invalid boot metadata
returns an error. Reaping uses the same lease-liveness mechanism for SHM and
semaphores/messages, with domain-specific reference cleanup and SEM_UNDO callbacks.
Commit retains the gate for provider-specific post-commit publication or body
collection, then releases it. No additional object or allocation is required per call.

Blocked calls subscribe to inotify and recheck after subscription. Committed
mutations and closed owner leases wake them; no heartbeat or state-polling
interval is used. The supervisor owns the watcher and an epoll registration with
an exact request-generation token. An empty ppoll runs in the guest task with its
original signal mask, without inserting the watcher into its file table. Watcher
readiness interrupts that exact wait through ptrace; it does not inject an
application signal. Cancellation removes the registration before closing the
watcher. semtimedop retains its absolute deadline across
readiness retries. Expiry returns EAGAIN; interruption returns EINTR, including
with SA_RESTART. Applications must handle interrupted waits explicitly.

Kernel lifetime locks identify abandoned launch leases without trusting PID
reuse. A peer reaps a dead owner's undo adjustments and waiters transactionally.
Recovery follows the dead owner's recorded object references across IPC namespaces;
guest key and ID lookups remain restricted to the caller's namespace.
Lease-close events wake already blocked peers after supervisor SIGKILL. Ordinary
thread exit does no IPC transaction unless that undo group used SEM_UNDO.

## Limits And Checks

Limits are 32,000 semaphores per set, 500 operations per vector, values/adjustments
bounded by 32,767, 8 KiB per message and a default 16 KiB queue. Increasing queue
capacity requires logical CAP_SYS_RESOURCE and is capped at 1 MiB. Host authority
never changes. IPC_INFO/SEM_INFO/MSG_INFO, indexed enumeration and MSG_COPY are
not implemented. These are not POSIX semaphore APIs.

`test_resources.py --case mount-namespace` includes UTS/IPC clone, unshare, exec,
protected memory, invalid combined flags, key/ID separation, semaphore wakeups,
SEM_UNDO and SHM mappings retained across nested namespace changes. The same
workflows are included in the full resource suite.

`test_sysv_runtime.py` covers vector rollback, wait/wakeup, signals, removal,
timeouts, zero waiters, fork/exec/thread undo, SIGKILL, SETVAL clearing undo,
message ordering/truncation/copy errors, queue capacity, permissions, nondumpable
callers, independent launches and supervisor-death recovery.
PID-metadata cases cover nested namespaces, thread operations, protected exec,
ancestor visibility and reading a reaped child's metadata. Native model checks
also exercise unrelated namespaces and native PID reuse.
The process-group case verifies that a headless guest's group signal does not cancel its guardian.
Full-table checks cover the maximum semaphore array, 8 KiB messages, shmctl
metadata and blocked waits. Sixteen killed CLONE_FILES waiters leave the survivor's
FD baseline intact. Forty simultaneous waiters exceed the shared slot capacity
without starving the producer; a seccomp-denied ppoll returns its error and
removes the waiter.
The resource fixture also verifies failed admission, owner-ID collisions,
cross-namespace dead-owner undo recovery, malformed boot metadata and exact descriptor cleanup. Database tests cover
independent concurrent connections, retained-directory rename, WAL/rollback
locking, rollback visibility and symlink rejection.
Query-plan checks reject full-table scans and temporary sorting in queue
selection and semaphore bookkeeping lookups.

`test_sysv_apps.py` exercises stock fakeroot-sysv, Symfony SemaphoreStore,
PHP SysV message queues and Apache prefork with `Mutex sysvsem default` and two
listeners. It requires actual cross-process traffic and clean service completion,
not just initialization. The prepared Ubuntu 24.04 packages are fakeroot 1.33,
Symfony Lock 6.4.5, PHP 8.3.6 and Apache 2.4.58. Device coverage remains
NX809J/API 36/Linux 6.12 under actual UID 2000; other kernels require validation.
