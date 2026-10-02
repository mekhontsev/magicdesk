# SysV Semaphores And Messages

The native supervisor owns a lazy, store-scoped semaphore and message-queue
authority. `sysv_sem.c` and `sysv_msg.c` implement the ARM64 Linux operations;
`sysv_ipc_db.c` owns transactions, permissions and launch leases. No daemon,
Android service or host SysV kernel support is required. Launches that do not
use these APIs open no IPC database, create no channel and install no waits.

## Calls And Memory

Supported semaphore calls are semget, semop, semtimedop and semctl
IPC_STAT/IPC_SET/IPC_RMID, GETVAL/SETVAL, GETALL/SETALL, GETPID, GETNCNT and
GETZCNT. Operation vectors commit atomically; a blocked or rejected vector
leaves every semaphore unchanged. Permissions use the launch's logical identity.
SEM_UNDO belongs to a reference-counted group: CLONE_SYSVSEM shares it, ordinary
fork does not, exec retains it, and the last exit applies it. SETVAL/SETALL clear
the affected adjustments. Exit adjustments clamp values to the supported range.

Message queues support msgget, msgsnd, msgrcv and msgctl
IPC_STAT/IPC_SET/IPC_RMID. Selection includes FIFO, exact type, the lowest type
under a negative bound and MSG_EXCEPT. MSG_NOERROR permits truncation. E2BIG
retains a message; as in Linux, a selected message is consumed before copying
it to user memory, so EFAULT does not restore it to the queue.

`ipc_calls.c` copies arguments and results in the calling task through a bounded
memfd packet. The authority receives offsets into that packet, never unrestricted
guest pointers. Copy faults return EFAULT; PR_SET_DUMPABLE(0) remains unchanged.
The channel is per operation and closes on completion, failure or task death.
No database transaction spans guest copying or execution.

## Waiting And Lifetime

`ipc/sysv/ipc.db` contains keys, monotonic IDs, metadata, bounded message bodies,
semaphore values, undo adjustments and waiter registrations. A native file lock
serializes transactions across independent launches; SQLite uses WAL. This is
transient IPC state, separate from OCI layers, filesystem names and snapshots.
Unremoved objects remain available within the current boot; a changed kernel
boot ID expires them. This is not a kernel IPC namespace or security boundary.

Blocked calls subscribe to inotify and recheck after subscription. Committed
mutations and closed owner leases wake them; no heartbeat or state-polling
interval is used. ppoll runs in the guest task with its original signal mask,
not in a blocked supervisor. semtimedop retains its absolute deadline across
readiness retries. Expiry returns EAGAIN; interruption returns EINTR, including
with SA_RESTART. Applications must handle interrupted waits explicitly.

Kernel lifetime locks identify abandoned launch leases without trusting PID
reuse. A peer reaps a dead owner's undo adjustments and waiters transactionally.
Lease-close events wake already blocked peers after supervisor SIGKILL. Ordinary
thread exit does no IPC transaction unless that undo group used SEM_UNDO.

## Limits And Checks

Limits are 32,000 semaphores per set, 500 operations per vector, values/adjustments
bounded by 32,767, 8 KiB per message and a default 16 KiB queue. Increasing queue
capacity requires logical CAP_SYS_RESOURCE and is capped at 1 MiB. Host authority
never changes. IPC_INFO/SEM_INFO/MSG_INFO, indexed enumeration, MSG_COPY and IPC
namespace unshare are not implemented. These are not POSIX semaphore APIs.

`test_sysv_runtime.py` covers vector rollback, wait/wakeup, signals, removal,
timeouts, zero waiters, fork/exec/thread undo, SIGKILL, SETVAL clearing undo,
message ordering/truncation/copy errors, queue capacity, permissions, nondumpable
callers, independent launches and supervisor-death recovery. The process-group
case verifies that a headless guest's group signal does not cancel its guardian.

`test_sysv_apps.py` exercises stock fakeroot-sysv, Symfony SemaphoreStore,
PHP SysV message queues and Apache prefork with `Mutex sysvsem default` and two
listeners. It requires actual cross-process traffic and clean service completion,
not just initialization. The prepared Ubuntu 24.04 packages are fakeroot 1.33,
Symfony Lock 6.4.5, PHP 8.3.6 and Apache 2.4.58. Device coverage remains
NX809J/API 36/Linux 6.12 under actual UID 2000; other kernels require validation.
