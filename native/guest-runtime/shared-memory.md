# Shared Memory

POSIX shared-memory names use the ordinary inode namespace beneath `/dev/shm`.
Execution preparation creates missing `/dev` and `/dev/shm` directories in the
writable instance, retaining existing permissions and sealed-image contents.
Already-open descriptors, mmap and descriptor passing use native data IO.

System V shared memory has a separate store-scoped authority in `sysv_shm.c`.
The supervisor opens it lazily on the first request. `shm_calls.c` adapts the
ARM64 Linux ABI in the calling task; notification ADDFD supplies backing FDs.
No payload bytes pass through SQLite, RPC or the supervisor. Host UID and kernel
capabilities do not change, and the host kernel need not implement SysV IPC.

## Ownership

`ipc/shm/segments.db` stores keys, IDs, permissions and attachment references.
Mapped files carry data. Transactions hold a native file lock only while
updating metadata, not across guest execution or waits. Each supervisor holds
an owner lease; abandoned references are reaped using nonblocking file locks,
not PID reuse or periodic polling. Each address space owns its mapping records.
CLONE_VM shares them; fork copies them; exec/exit release them.

Independent launches using the same store can attach to keyed segments.
Unremoved segments survive the last launch within the same boot. A kernel boot
ID change expires them. IDs are monotonic within the IPC database. IPC_RMID
removes a key immediately and retains bytes until the last attachment disappears.
Logical removal commits before deleting data; a garbage table permits replay of
interrupted cleanup. This is not a power-loss durability guarantee.

Files are caller-owned, disk-backed mappings, not kernel tmpfs objects. The IPC
directory is not part of an OCI image or a filesystem snapshot. It follows the
store's host access boundary, not a kernel IPC namespace or isolation boundary.

## Operations

Supported calls are shmget (private/keyed, create/exclusive), shmat
(read-only/read-write, SHM_RND, SHM_EXEC subject to native execution policy),
shmdt and shmctl IPC_STAT/IPC_SET/IPC_RMID. Permissions use guest credentials;
CAP_IPC_OWNER bypasses access checks but does not grant host authority or ownership
changes. Protected memory is copied in the guest task with EFAULT semantics.

The first attachment installs a narrow memory-observation filter for munmap,
fixed mmap and mremap. Ordinary calls with no intersecting attachment continue
natively; successful replacement/unmapping updates the same attachment owner.
Partial unmaps retain surviving fragments. shmdt removes those fragments without
unmapping an unrelated mapping inserted in a hole. Fragment counts do not model
every VMA split, such as those caused by mprotect.

SysV semaphore/message queues, SHM_REMAP, huge-page selection, locking/statistics
commands and mremap involving an attached segment are not implemented. These
operations fail explicitly; POSIX semaphores and shared memory are separate APIs.

## Checks

`test_service_runtime.c` checks bytes shared across fork and independent launches,
store separation, permissions, read-only faults, fork/exec/exit, IPC_RMID, partial
munmap and unrelated mappings in holes. `test_oci_services.py` runs stock PostgreSQL
initdb, SQL traffic, shutdown and fresh-process readback. Device scope is recorded
in [guest coverage](../../docs/guest-runtime.md#coverage-and-limits).
