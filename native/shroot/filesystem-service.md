# Filesystem Service

The filesystem service exposes the [inode model](inode-store.md) through explicit
local RPC and direct seccomp notifications. It is owned by a native supervisor
worker, not an Android service, mount or container manager.
The explicit [namespace executor](namespace-execution.md)
routes selected guest syscalls here. Direct-backend hard-link controls remain
separate from successful namespace package-lifecycle checks.

- [Ownership](#ownership)
- [Requests And Descriptors](#requests-and-descriptors)
- [Outcomes And Cancellation](#outcomes-and-cancellation)
- [Verification](#verification)
- [Limits](#limits)

## Ownership

- `fs_operation.h` defines borrowed requests, operation results and caller-owned
  output independently of transport framing. `fs_engine.c` dispatches these
  operations without guest pointers or transport IO. A metadata-only operation
  needs no byte buffer. Directory publication uses an optional synchronous
  callback before cursor advancement; returned size never exceeds supplied capacity.
  `fs_worker.c` owns the store and image catalogue on one thread; `fs_service.c`
  multiplexes RPC, notification and stop readiness. A client cannot
  shut down the service or acquire a different execution identity.
- `md_fs_service_open/prepare/dispatch/close` separate service state from the
  thread driving it. `md_fs_serve` retains the existing dedicated-worker loop,
  wait mechanisms, dispatch order and batch limits. A prepared poll snapshot is
  borrowed until dispatch or the next preparation; only readiness fields are
  filled by the caller's wait. A confirmed pause exposes only control and stop
  readiness. Preparation can advance deferred filesystem work and is not a
  nonblocking-IO guarantee. The supervisor and filesystem worker remain separate
  threads; this lifecycle does not add a new scheduler or inline execution mode.
  Worker runtime open/close and pending-work retirement are separate from its
  pthread entry, with the same startup publication and teardown order.
- `fs_client.c` is freestanding: no libc, SQLite, heap, mutex, shared socket or
  thread-local errno. Invocation-local scratch and raw syscalls permit nested
  calls from a signal handler. Guest-pointer validation remains the adapter's
  responsibility before calling this API. Request and reply phases reuse one
  invocation-local wire buffer after send completes, keeping both full packets
  off the stack at the same time without a shared scratch area.
- `fs_wire.c` owns bounded packets, native FD transfer, peer checks and deadline
  waits. `fs_rpc.h` is the typed client contract; `fs_wire.h` is the internal
  same-build ARM64 wire format. Unknown versions are rejected, not migrated.
  Delivery state belongs to the RPC response, not the common operation result.
  The service writes directly into its retained reply buffer. Notifications use
  one reusable 64 KiB worker buffer, so directory batches are not limited by the
  RPC frame's 4 KiB payload. Neither path allocates an output buffer per operation.
  Image-open replies additionally carry a bounded object ID and canonical path,
  captured with the descriptor in one namespace transaction.

Stopped-task unlink/rename/link/mkdir use request-owned operands and a scalar
receipt in `fs_buffer_service`, without a guest-mapped reply or FD-publication
protocol. They share the existing context capture, worker dispatch and
cancellation owners. The task remains stopped until the supervisor writes the
completed result. Missing capture authority delegates before execution; accepted
mutations are never replayed after cancellation or signals.

Metadata-only descriptor delivery uses the already admitted object's native
flags, not a second interpretation of the original pathname flags. Native
providers can normalize `O_NOFOLLOW` when reopening a non-link pin. Such results
reopen the exact retained `/proc/<supervisor>/fd/<n>` object instead of requiring
`SCM_RIGHTS`: Android may allow an `O_PATH` metadata open while denying receipt
of that device through a socket. The buffer publishes internal reopen flags;
the supervisor validates them against its own result custody. Symlink pins keep
their no-follow delivery, and neither device data permissions nor mount policy
are relaxed. This adds no work to ordinary data IO or direct regular-file opens.

`file_metadata.h` owns conversion between native stat, fixed-width metadata and
guest stat/statx, including device identity, allocated blocks and timestamps.
Only known mount IDs are advertised. Access checks use a borrowed
`md_identity_access` value for real or effective credentials; they never mutate
the request's retained identity. `file_permissions.h` supplies common mode-bit
and DAC capability rules for inode storage, virtual providers and descriptor
metadata. Inode ACLs and provider-specific restrictions remain with their owners.

Both transports accept the same operation set from `fs_operation.h`. Path and
descriptor dispatch use one ordered virtual-provider selection. `fs_control`
prepares the carrier before publishing typed behavior and origin: a failed open
cannot leave a registered control object, and every failure closes its carrier.
Anonymous Landlock/BPF/fscontext descriptors and proc fdinfo use the same
publication contract without a mount origin. Providers retain their own access,
readonly and IO semantics.

`MD_FS_SYNC` carries a validated real data/directory FD and an explicit
file/data/filesystem barrier mode. Origin metadata still selects the owning
mount/store, but never replaces a data operand with its path-only anchor.
Inode providers synchronize data before checkpointing committed namespace
metadata; native attachments delegate to the original syscall. Virtual control
files reject file/data barriers with EINVAL and have no persistent filesystem
state to synchronize. Ordinary reads, writes and mappings retain native IO.

Host readiness uses nonblocking eventfds for state signals and `queue_signal.h`
pipe pairs for the filesystem worker's request/completion queues. The latter
retain ordinary pipe wakeup scheduling without CPU affinity or spinning.
Acknowledgements are bounded; queue/state remains authoritative, not the signal
count. EINTR retries the same syscall; an empty acknowledgement or saturated
signal is harmless. Cross-owner resource wakeups retain both pipe ends, so a late
retirement after loop shutdown cannot signal a reused FD or raise SIGPIPE.
Startup/shutdown controls remain eventfds. Socket payloads and semaphore counters
do not use this contract.

The abstract Unix SOCK_SEQPACKET endpoint has no filesystem path to translate.
Both ends check SO_PEERCRED against their effective UID. Device fixtures retain
the selected app or shell executor; build-host tests use the host UID. This is
not authorization between mutually hostile processes sharing a UID, and the
executor remains unsuitable as a security sandbox.

The supervisor's credential registry publishes guest IDs and immutable groups by
TID. Direct requests use the kernel notification's TID; RPC includes an actor TID
and verifies that the kernel peer owns its thread group. The supervisor can act
on behalf of its tracee. The worker borrows one retained snapshot per operation
and releases the registry lock before IO. It never accepts caller-supplied UID/GID
as authorization. Offline fixture serving without a registry uses native authority.

Unix IPC requests use that same authenticated actor and a separate lazy
[credential authority](ipc-credentials.md). They borrow socket FDs, capture
connection identities and issue/validate sealed explicit-message capabilities.
The transient IPC database and its lock are separate from inode transactions;
guest connect/accept/data IO never run while that lock is held.

A client's seccomp filter does not restrict operations performed by this
separate service. A same-UID caller able to reach the endpoint can request an
open even if its own openat is denied. There is no per-caller browser policy
enforcement; see the [sandbox boundary controls](../guest-exec-lab/sandbox-research.md).

The namespace worker uses static Android/Bionic code whose storage IO reaches
the host filesystem directly. The standalone storage executable supplies offline
import and fixture serving. Their infrastructure is not
served through its own RPC endpoint. Preserve that nonrecursive boundary when
extending the guest syscall adapter; service bootstrap must not depend on the
namespace service it is starting.

## Requests And Descriptors

Each RPC request owns a fresh CLOEXEC, nonblocking connection, one packet and one
reply. There is no inherited persistent client connection to repair after fork
or exec, no cross-thread reply matching and no client lock that a nested signal
can deadlock. Per-call connection setup is an explicit cost of this ownership
model; ordinary brokered open/stat, data IO and regular-file seeks do not need a
filesystem RPC. Both request transports execute through the same engine and
catalogue. Queue locks only transfer ownership; they do not cover filesystem IO.

The server retains a successfully sent reply's connection until the client
closes it. Request, pending-reply and client-release are distinct event-loop
states. A nonblocking send interrupted or backpressured before delivery retries
the retained reply, never dispatches the operation again. Reply buffers and FD
ownership occupy one fixed-capacity pool allocated at service startup; no
per-request heap allocation is required. Shutdown, peer failure and deadline
expiry release retained FDs. A client that never reads or closes cannot retain
a slot indefinitely or block unrelated requests.

The service exposes create/open, mkdir, symlink/readlink, link/unlink/rename,
path stat, FD stat, guest chmod/chown/access/timestamps, directory-path reconstruction, paged directory read/seek and
socket bind/address/name operations. Bind borrows the caller's socket via
SCM_RIGHTS, preserving its open-file description; data traffic never uses RPC.
A request carries at most
two borrowed base FDs via SCM_RIGHTS; -1 explicitly denotes the namespace root.
There is no implicit service cwd. Create/open replies transfer a native file
description to the caller, received with CLOEXEC. IO, mmap and file locks do not
cross the service; they operate on the actual shared kernel object.
Image preparation uses a read-only open operation that returns both the exact
descriptor and its logical identity. A sealed catalogue image retains its original
namespace object ID; admission and executable access checks remain separate.
Xattrs use bounded captured values through the shared filesystem operation
contract. Inode-backed attributes retain guest metadata and permissions; native
attachments retain their real xattrs and kernel access checks. Both paths hide
Android's `security.selinux` label and Shroot's private backing attributes.
List size queries and data reads use the same filtered names; explicit reads
return ENODATA, and writes to host-private attributes return EOPNOTSUPP without
changing the backing label. Ordinary user attributes remain accessible. This
keeps guest copy/patch tools from trying to reproduce Android labels in Linux.

Directory reads return bounded Linux getdents64 records. Explicit capacity and
offset fields are validated, as are returned record boundaries, cookies and names.
The native directory open-file-description offset supplies shared position across
dup/fork/exec/SCM_RIGHTS and service restart. Read/seek are serialized by this
owner; raw kernel directory reads or another service must not operate on that
same description. Independent opens have independent positions. Import is an
offline store operation, not a blocking request on this event loop.

The wire contains fixed-width metadata rather than a libc-specific stat layout.
Descriptor masks, lengths, terminating NULs, operation arguments and packet
versions are validated before dispatch. Truncated packets or ancillary data are
protocol errors. Every received descriptor is either transferred to the caller
or closed, including error and malformed-message paths.

The service accepts a bounded batch into at most 32 active connections. An idle
peer cannot block other peers: readiness is event-driven, and each accepted
connection has a failure deadline. Store metadata operations serialize through
the inode model's kernel-lock gate before entering SQLite, including when
independent launches use the same store. External SQLite contention bypassing
that gate is returned as EAGAIN, without automatic replay. Kernel lock admission
and synchronous filesystem IO can still stall the service thread; the client
deadline does not cancel a sent request or promise bounded kernel IO completion.

## Outcomes And Cancellation

Transport status and remote operation status are separate:

- `NOT_SENT`: no request packet was accepted by the socket transport.
- `UNCONFIRMED`: a packet was sent, but no valid reply was received. A mutation
  may already have committed, or a directory cursor advanced; a timeout or
  disconnect does not cancel the operation.
- `REPLIED`: a validated reply supplies the remote result and any owned FD.
  A remote EIO at a commit boundary can itself have an unknown outcome, as
  documented by the inode model. A reply alone is not proof of commit success.

There is no automatic reconnect-and-replay. A failed response delivery cannot
roll back a committed operation. The caller must inspect state before deciding
what to do after an uncertain outcome. Automatic retry, exactly-once operation
IDs and broker restart discovery are not implemented. The namespace runner owns
process-tree supervision separately from this transport.

Client waits use the shared freestanding `event_wait.c` monotonic absolute
deadline primitive, including EINTR handling. Server
waits observe listener, request/reply, client-close and stop-FD readiness, with peer expiry. These
are event waits with failure bounds, not readiness polling or settling delays.

In namespace execution, the supervisor retains the worker until the complete
guest tree has ended. Frontend lifetime and initial guest exit do not determine
namespace lifetime. See [process ownership](process-lifetime.md).

## Verification

`build.sh` links the client/wire/raw primitives into a freestanding object and
requires zero undefined symbols. Host and actual shell-UID Debian fixtures cover:

- Shared native inode/data/FD/mmap/flock identity, link counts, open-unlinked
  lifetime, symlinks and directory moves with retained FDs.
- An inherited native FD used by an independently exec'd client.
- Directory paging/types/inodes, dup/fork/exec cursor sharing, independent opens,
  service restart with retained FDs, nonreused cookies, deleted directories,
  invalid capacities/seek requests and eight concurrent readers without duplicates.
- Common-engine directory output larger than an RPC payload, exact record counts,
  capacity guards, rejected publication without cursor advancement, metadata-only
  calls without output and bounded object-identity strings.
- Service death exactly after directory read advances its offset: the lost reply
  is unconfirmed, and reopening the service observes the retained position.
- 256 calls from eight threads and eight deterministic nested signal-handler
  calls while the outer RPC is pending; client errno remains unchanged.
- An external SQLite writer bypassing the store gate and holding a real transaction: an RPC reports EAGAIN without
  changing the requested name, and remains usable after the writer commits.
- Invalid lengths, versions, strings, FD masks, oversized messages and excess
  SCM_RIGHTS. Namespace and service FD counts are checked after rejection.
- Image-open replies retain one exact descriptor and its object/path identity;
  short payloads, invalid IDs, unterminated or relative paths, interior NULs and
  missing descriptors fail explicitly without leaking transferred FDs.
- An idle peer alongside a working client; reply timeout; malformed-reply FD
  cleanup; failure before sending; and service death exactly after commit.
- Explicit service lifecycle: shared-work readiness, callback order, pause
  exclusion, stop priority, partial-open cleanup and restoration of borrowed
  filesystem state without closing caller-owned listener/stop descriptors.
- A delayed reader receives its reply and FD while other clients make progress;
  the server does not close the connection ahead of the reader. Repeated FD
  metadata exchanges exercise response lifetime under load.
- Reopening the namespace after service death verifies the already-committed
  operation. The lost reply remains unconfirmed rather than being replayed.

Test checkpoint hooks are compile-time-only. Pipes identify exact checkpoints;
waitpid observes termination. The outer runner timeout cancels stuck fixtures.
No APK install, root switch, SELinux change or Desktop self-test is involved.

## Limits

The namespace adapter covers cwd, directory read/seek, stat/statx, atomic
open/create and exec. Its native owner uses umask zero after guest fork; requests
carry the guest mode and creation mask. Open/create and mkdir can request authenticated
actor-mask capture in the worker, avoiding a preliminary umask RPC; unauthenticated
requests cannot select it. The underlying model API itself retains
normal calling-process umask semantics. Real identity is unchanged; guest inode
permissions use the supervisor's explicit credential model. The offline prepared-rootfs
import does not implement live promotion, object reclamation or notifications.

Keep SQLite and its locks outside the guest process. Preserve the direct kernel data path
and truthful failure/commit outcomes as syscall coverage expands. A real fixture
install/update/purge workflow and distribution package-manager checks pass;
see [application coverage](../../docs/shroot.md#coverage-and-limits).
Those workflows do not establish a complete Linux ABI, live storage reclamation
or security isolation.

References: [Unix sockets and SCM_RIGHTS](https://man7.org/linux/man-pages/man7/unix.7.html),
[recvmsg truncation and CLOEXEC](https://man7.org/linux/man-pages/man2/recvmsg.2.html),
[ppoll](https://man7.org/linux/man-pages/man2/poll.2.html).
