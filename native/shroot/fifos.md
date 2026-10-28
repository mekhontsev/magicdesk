# Pipes

## Anonymous Pipes

`pipe2` publishes both endpoints through one descriptor-table transaction.
`descriptor_inode` records logical ownership, mode and ctime without retaining
either data endpoint. Dup, fork and typed SCM_RIGHTS preserve that metadata;
data, EOF, SIGPIPE and pipe capacity remain native. Ownership checks use the
same guest credentials as named files.

Independent-supervisor transfer uses authenticated typed parcels. Metadata is
published lazily in an O_TMPFILE carrier with OFD locking and shared generations;
ordinary pipe IO neither opens that carrier nor acquires its lock. Native peers
receive ordinary kernel pipe descriptors, not a rewritten payload.
`test_descriptor_inode.c`, `test_descriptor_metadata.py` and the origin-transfer
suite check metadata sharing, malformed imports and exact descriptor cleanup.

## Named Pipes

Guest FIFO names, ownership, modes, ACLs, hardlinks and rename/unlink use the
ordinary inode namespace. OCI layers and prepared-tree import retain FIFO
metadata without persisting stream contents. Each image instance has its own
streams. Native attached directories retain their real kernel permissions.

`inode_fifo` owns store-scoped kernel pipe pins. The backing object is regular
metadata, not a native `mknod` request: Android shell policy can prohibit native
FIFO creation. An O_PATH pin retains a pipe inode without retaining a reader or
writer. Data read/write, pipe capacity, PIPE_BUF atomicity, EOF and SIGPIPE remain
kernel operations. No daemon, payload proxy, root or SELinux modification is used.

Independent namespace workers sharing the store acquire the same pipe through
a live owner's proc descriptor. Persisted routes include native device/inode
identity and a random owner lease protected by a kernel lock; PID existence alone
does not authorize reuse. Each participating worker retains its own pin until
its supervised launch ends. Dead leases are ignored. Clean closure removes its
routes. Portable origins carry the stable channel identity. On receipt they
rejoin that channel with path-only custody, independently of the sender's worker
and SQLite connection; conflicting live stream identities fail with ESTALE.
Final imported-origin release closes its pins and lease without a database
operation. The next channel acquisition collects stale routes. No live
descriptor number is meaningful across a runtime restart.

Opening returns an authorized pin and a separate rendezvous descriptor through
the typed filesystem result. The mapped transport retains both in the supervisor;
`file_open_job` completes the open in a short-lived child with its own kernel file
table. Only those inputs and its private result channel survive initial descriptor
cleanup. This channel is never installed in the guest. The existing SIGCHLD loop
reaps completion, then atomic ADDFD_SEND publishes the single application endpoint.
An application signal requests interruption and awaits the exact helper's result
without blocking the supervisor. The shared `interruptible_call` gate preserves a
completed native open; virtual peer admission and cancellation use the same
rendezvous lock. An admitted endpoint is delivered once, whereas an unpaired
open retains the kernel's signal/restart decision. Abandoning the guest request
kills the exact child and releases its kernel table and queued rights, even with
a surviving CLONE_FILES peer. Parent death also terminates the helper.
`close_range` cleanup falls back to native proc enumeration
when that syscall is unavailable; it is not a new kernel startup requirement.

No filesystem worker, SQLite transaction, table or channel turn spans the wait.
Ordinary file opens create no helper. Native host-side engine/RPC callers retain
the synchronous `file_open` completion contract in their own table.
Blocking reader/writer opens wait on
shared futex generation changes; signals can interrupt this wait. A nonblocking
writer without readers receives ENXIO; O_RDWR does not wait for another endpoint.
Ordinary files do not allocate this state or acquire rendezvous locks. Internal
bookkeeping lives outside watched backing objects and is not published as guest IO.

## Readiness

An initially unpaired nonblocking reader retains the writer generation observed
during open. Its typed description suppresses initial POLLHUP until a writer
connects. Dup, fork, exec and authenticated SCM_RIGHTS retain that baseline.
Read/write, EOF and SIGPIPE stay native: observation retains no data endpoint.
The observer uses native epoll subscriptions and a read-only mapping of the
rendezvous generations, not periodic sampling or another pipe implementation.

Selected `poll/select` calls use the same task-affine native-wait owner as mixed
epoll. Ordinary descriptors, including signalfd, are waited on in the calling
guest thread, with its temporary signal mask. A helper observes only provider
wake sources. Typed metadata is captured under the descriptor-table turn, which
is released before blocking. After the first writer, the original FIFO descriptor
itself supplies native poll readiness; ignored data does not cause virtual-wake
spinning. Mixed epoll preserves level, edge and one-shot subscriptions.
The poll selector is admitted only in processes acquiring an initial-reader
description; other processes retain their ordinary syscall path. Variable-size
poll vectors borrow contiguous capacity from the existing shared request mapping.
No extra guest FD or per-request mmap is needed; exhausted mapping capacity is
an explicit allocation error.

## Limits

Queued rights retain the pipe endpoint, but cannot publish a proc pin route after
every channel owner exits and before a receiver imports the message. Another
launch opening the name during that gap can establish a conflicting stream;
later import rejects it rather than silently connecting the wrong channel.
Preserving name rendezvous throughout that gap needs shared channel custody.
Raw ppoll/pselect timeout writeback on signal interruption does not yet cover
the direct signal-delivery return path; libc wrappers hide that timeout mutation.

Directory/name inotify events use namespace transactions. FIFO data/open/close
events are not synthesized from the anonymous pipe. Do not infer complete FIFO
watch support from regular-file watch checks. Endpoint sharing outside supervised
guest launches is not a supported pin-lifetime contract.

## Checks

`test_service_runtime.c` exercises permissions, nonblocking open errors, data
readiness, EOF, blocking peer rendezvous, O_RDWR, hardlinks, rename, unlink/reopen
and name reuse under actual shell UID 2000. It also covers 300-entry poll vectors,
select, LT/ET/oneshot epoll, ignored interests, temporary signal masks/EINTR and
mixed native signalfd. `test_fifo_runtime.py` exchanges data between independent
launches, repeats after both owners exit, and transfers a reader between protected
processes after sender exit and across receiver exec.
`test_open_job` checks endpoint exchange, ENXIO/EOF, cancellation before and after
result queuing, interruption before and after peer admission, descriptor counts
and exclusion of unrelated inherited FDs. `test_fifo_go.go` exercises concurrent
stock-Go proc-fd opens, native IO, netpoll and EOF with normal runtime preemption.
Production resource checks forbid guest RPC syscalls, fill the guest descriptor
table, check exact result-slot use and kill protected CLONE_FILES callers during
unpaired opens without leaving private guest descriptors.
`test_import.c` checks prepared-tree FIFO import on hosts that permit source FIFO
creation; shell denial is reported explicitly. OCI checks cover FIFO metadata,
hardlinks and independent instance backing. Stock VS Code C/C++ debugging also
exercises FIFO transport through its integrated terminal.
