# Shared Memory

POSIX shared-memory names use the ordinary inode namespace beneath `/dev/shm`.
Execution preparation creates missing `/dev` and `/dev/shm` directories in the
writable instance, retaining existing permissions and sealed-image contents.
Already-open descriptors, mmap and descriptor passing use native data IO.

- [Read-Only Anonymous Views](#read-only-anonymous-views)
- [System V](#system-v)
- [Ownership](#ownership)
- [Operations](#operations)
- [Embedded X11 Attachments](#embedded-x11-attachments)
- [Checks](#checks)

## Read-Only Anonymous Views

Some host policies deny reopening a readable memfd through `/proc/self/fd`.
After such a denial, `readonly_file` can retain that exact readable, unlinked,
sealable regular file as a read-only controlled description. It is not a copy:
reads, stat, seals and shared mappings observe the original backing. Each reopen
has an independent offset, while dup and same-tree SCM_RIGHTS retain the same
description. Independent-supervisor transfer is not provided by this view.
The guest carrier is O_PATH; unadapted writes cannot acquire provider write access.
Unsupported open modes do not silently enter this fallback.

`task_mapping` maps eligible controlled backings in the stopped requesting task,
without creating a helper thread that an application sandbox may prohibit.
Native read-only regular descriptors and memfds protected by size seals and
WRITE/FUTURE_WRITE seals need no shared-table stop. A sole stopped table owner
also needs no barrier: its running FD turn excludes clone/dup publication until
the temporary descriptor is retired. Shared mutable writable backing
uses a cold barrier over the exact CLONE_FILES holders before publishing its
temporary descriptor; that barrier remains held through verified retirement. The
existing FD-turn and loan retirement owners serialize installation, mapping and
verified native close. Errors and caller death retire the exact loan, not a
guessed descriptor number. An uncertain mapping result invalidates its optional
provenance and retains conservative protection checks for surviving CLONE_VM users.
Retirement first borrows the requester's already stopped continuation. Selecting
a peer first can deadlock when that peer's deferred lifecycle or signal event
cannot be replayed until the mapping barrier is released. Surviving table holders
remain eligible after caller death. A full application FD table retains the
private-helper fallback; application seccomp denials remain errors in both paths.

Mapping policy shares one range owner for noexec and read-only restrictions.
Shared read-only views cannot gain PROT_WRITE through mprotect; MAP_PRIVATE keeps
normal COW behavior. Splits, remap, fork and exec preserve or retire the same
policy. Descriptor and readiness selectors are prepared before application
seccomp activation, so receiving a new controlled FD does not require installing
another filter inside that sandbox. Ordinary native descriptors keep their data
path; this is not coherent FUSE shared-mapping support.

`test_memfd_views.py` exercises protected callers, live backing, seals, COW,
native FD balance, failed mappings, a full descriptor table, repeated death of a
mapping caller sharing another process's FD table, concurrent mutable mappings
by four protected CLONE_FILES processes with helper creation denied, and SCM_RIGHTS to a
receiver denying clone and further seccomp installation, in Base and accelerated
execution. `test_task_mapping.c` checks backing admission; FD-origin tests cover
metadata-free loan allocation failure and retirement.

## System V

System V shared memory has a separate namespace authority in `sysv_shm.c`.
The supervisor opens it lazily through `ipc_namespace` on the first request. `shm_calls.c` adapts the
ARM64 Linux ABI in the calling task. `task_helper` owns a short-lived same-TGID
helper with CLONE_VM but without CLONE_FILES. The helper closes its inherited
descriptor copies, receives a backing FD in its private table and executes mmap
against the shared address space. Kernel thread termination closes every private
FD, including after filters, caller death or exec. No temporary backing FD or
socket enters the application's table, and a full application table is supported.
IPC_STAT uses the common adapter-buffer lease, with no temporary guest FD.
No payload bytes pass through SQLite, RPC or the supervisor. Host UID and kernel
capabilities do not change, and the host kernel need not implement SysV IPC.

## Ownership

`ipc/v2/shm/segments.db` stores namespace-scoped keys, IDs, canonical owners and attachment references.
Attachment references are indexed by segment as well as by owner/address space,
so a segment's reference count does not scan unrelated mappings. Admission
prepares this rebuildable index; IPC_STAT does not modify the schema.
Mapped files carry data. Transactions hold a native file lock only while
updating metadata, not across guest execution or waits. Each supervisor holds
an owner lease; abandoned references are reaped using nonblocking file locks,
not PID reuse or periodic polling. Each address space owns its mapping records.
CLONE_VM shares them; fork copies them; exec/exit release them.
Each mapping retains its original authority even after CLONE_NEWIPC. Fork and
address-space release batch their metadata changes per authority; they do not
open a transaction for each mapping. An isolated namespace's last retained
authority retires its objects without invalidating mappings retained by another
address space.
The interception-memory owner orders attachment publication, detach, destructive
memory calls and non-CLONE_VM fork. Its resource queue does not hold a mutex
across a guest wait. Accepted helpers pin that owner, their scratch stack and a
buffer lease through exact kernel reaping, independently of the initiating task.
Only confirmed mappings enter the attachment model. A helper opens its own maps
file and transfers it before mmap; terminal loss of acknowledgement is reconciled
against the backing device/inode, offset and complete shared range while the
helper remains at PTRACE_EVENT_EXIT. A register resembling a successful fixed
mmap is not a commit receipt. Uninspectable terminal state is a runtime failure,
not inferred success. Normal returns do not scan maps.

Independent launches using the same store's initial namespace can attach to keyed
segments. Its unremoved segments survive the last launch within the same boot. A kernel boot
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

The common memory-observation filter covers munmap, fixed mmap and mremap;
attachments do not install another seccomp filter or override private task gates.
Fork ordering is recorded on the shared address-space owner, including pending
attachments, rather than on individual threads. Calls with no intersecting attachment continue
natively; successful replacement/unmapping updates the same attachment owner.
Partial unmaps retain surviving fragments. shmdt removes those fragments without
unmapping an unrelated mapping inserted in a hole. Fragment counts do not model
every VMA split, such as those caused by mprotect.

SHM_REMAP, huge-page selection, locking/statistics
commands and mremap involving an attached segment are not implemented. These
operations fail explicitly; POSIX semaphores and shared memory are separate APIs.
Application filters and native policy can deny helper creation, close_range,
maps inspection, descriptor transfer or mmap. Such denials remain errors; the
runtime does not relax filters or dumpability. Helper-only termination uses an
exact KILL_THREAD gate, never a group-directed kill for ordinary completion.
An application KILL_PROCESS filter still takes precedence. Nested debugger
events exclude internal helper birth/exit and syscalls; kernel proc enumeration
is not a virtualized thread list.
Namespace-scoped [SysV semaphores and message queues](sysv-ipc.md) have their own
authority and task-affine argument transport.

## Embedded X11 Attachments

Legacy MIT-SHM carries a SysV segment ID, not an FD. The app-UID X server cannot
resolve that ID in Shroot's virtual IPC namespace. Explicit
`--socket-resource-path` and `--socket-resource-abstract` routes therefore attach
one private resource channel before the normal X11 authorization handshake.
`GuestGraphicalConnection` selects these routes for Shroot X11 launches; ordinary
routes, Termux and Wayland do not acquire this channel.

The authenticated filesystem request captures the connecting task's IPC namespace
and guest credentials. `shm_bridge` retains that capability for the connection;
requests cannot select another namespace, store or identity. A later credential
or namespace change does not rewrite an already established connection's grant.
The filesystem worker services bounded batches on its existing event loop and
opens a separate SHM database connection lazily, only for an actual attachment.
It never shares a supervisor-thread SQLite connection or inspects X11 traffic.
Namespace and SHM-authority lifetime pins are atomic: the last namespace holder
may be the worker after `unshare`, while mappings retain their original authority.
That does not authorize concurrent SQLite operations on one connection.

Each accepted attachment exports the real backing FD plus an independent owner
lease. Read-only requests receive an `O_RDONLY` FD; guest permissions are checked
before export. An OFD `flock` on the lease retains the logical attachment through
SCM_RIGHTS, dup and process death. The X server closes it when the last segment or
shared-pixmap reference disappears, not merely on `ShmDetach`. Creator or broker
exit does not invalidate an existing mapping. `IPC_RMID` removes the name while
retaining live attachments; a dead final lease cannot resurrect a removed ID.
Existing transactional reaping collects dead owners on subsequent authority work;
admission also removes unlocked lease files left before database registration.
Creation and recovery share the database gate, including the initial file lock.

The embedded server waits asynchronously for the attachment reply, suspending
only that X client. A five-second deadline fails the operation, not a global
server wait. Malformed packets, extra rights and closed channels release pending
FDs. Failure never falls back to a native SysV ID in another namespace. Modern
`ShmAttachFd` and `ShmCreateSegment` retain their existing FD protocol and share
the server's mapping/lifetime implementation with bridged attachments.

Pixel reads and writes use the shared mapping, with no per-frame broker request,
SQLite work, Binder copy or X11 stream proxy. All mechanisms used here predate
Linux 5.15. The renderer remains app-UID; no host SysV support, root or new
persistent storage family is required.

## Checks

`test_service_runtime.c` checks bytes shared across fork and independent launches,
store separation, permissions, read-only faults, fork/exec/exit, IPC_RMID, partial
munmap and unrelated mappings in holes. `test_oci_services.py` runs stock PostgreSQL
initdb, SQL traffic, shutdown and fresh-process readback. Device scope is recorded
in [guest coverage](../../docs/shroot.md#coverage-and-limits).

`test_ipc_database.c` and `test_shm_bridge.c` check namespace/credential capture,
read-only enforcement, invalid requests, malformed/extra ancillary FDs, queued
response cancellation, `IPC_RMID`, creator/broker/consumer death, duplicate leases,
orphan-file recovery and descriptor balance. `test_x11_shm.py BUILD STORE` uses
stock libX11/libXext/libxcb against the app-UID server: real pixel readback,
private IPC rejection, read-only rejection, shared-pixmap retention, swapped
request replay, abrupt client teardown, and modern
AttachFd/CreateSegment with both resource and ordinary X11 admission. The guest
needs Python 3, util-linux and these X libraries; no compiler is required.
`--installed` tests the APK's actual shell launcher and immutable helper staging,
rather than uploading a separate native runtime build.
`test_steamos_graphics.py --client glx --renderer lavapipe --require-shm` verifies
changing frames using the installed software profile without the `noshm` bypass.

`test_sysv_runtime.py --case shm-slots --case shm-denied` checks repeated zero-slot
and one-slot attachment/detachment and rejected shmat, with ordinary and
virtually nondumpable callers. Failed attachment leaves no logical reference or
backing FD. Filters reject guest fstat/mmap without rejecting internal attachment
work: guest policy applies to the original syscall, not its implementation.
`shm-terminal` checks that helper-only mmap/close_range/clone restrictions do not
affect shmat, while explicit shmat denial and KILL_THREAD remain effective, with
surviving CLONE_VM/CLONE_FILES peers and full tables. `shm-concurrent` checks
six shared-memory callers with repeated attachments and forked readers.
`shm-owner-death` checks caller-group cancellation during repeated mapping work;
the next address-space turn verifies both kernel mappings and logical references.
`shm-exec` replaces the image while another thread repeatedly attaches/detaches;
the new image verifies retirement of the old address space's attachments.
The debugger suite's `gdb-shm` and `strace-shm` run stock tools against actual
attachments and check that internal helper events do not appear as guest work.
`test_resources.py --case private-memory` also combines an attachment with
private-file JIT repair, fork and native unmap in Base and lazy-entry modes.

`test_resources.py --case mapping-task` is a kernel-only feasibility fixture,
not the production attachment path. A CLONE_VM helper with a private file table
can map a provider-owned file into the shared address space without changing
the caller's descriptors. Checks cover separate-process and same-TGID helpers,
nondumpable callers, full inherited and helper tables, fatal filters before mmap/close, and
caller-thread exit while another thread retains the address space. The provider
installs at a known private slot; no application slot is replaced.

The fixture also exposes two integration constraints. An application filter can
allow ordinary CLONE_FILES threads and mmap while denying this helper's clone.
For fixed mmap, terminal x0 may still contain the requested address when a fatal
filter prevented execution. On the covered kernel, the separate-process case
even publishes a syscall-exit stop with that unchanged value. Neither observation
alone proves a completed mapping. The helper can open its own maps file and
transfer it before the mapping; this file stays outside the application table.
Reading that retained file at PTRACE_EVENT_EXIT identifies the actual backing
device/inode, offset and shared range even for nondumpable callers. Checks include
death immediately after mmap, before any userspace acknowledgement. The native
PROCMAP_QUERY ioctl returns EACCES on the covered shell policy; text-map reads
pass independently and do not require that ioctl.

An explicit helper-only KILL_THREAD gate also terminates a private-table thread
without killing its surviving peers, including with a later ERRNO filter on exit.
This is not a general override of application filters: a stricter KILL_PROCESS
policy or denial of helper creation remains an independent limit. The 60-case
fixture checks exact caller FD balance and mapping bytes using the production
`mapping_observation` reader. Runtime helper admission, concurrent ownership,
exec and nested-debugger checks belong to the separate suites above.
