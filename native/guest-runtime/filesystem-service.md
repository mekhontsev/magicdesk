# Filesystem Service

The filesystem service exposes the [inode model](inode-store.md) through explicit
local RPC and direct seccomp notifications. It is owned by a native supervisor
worker, not an Android service, mount or container manager.
The explicit [namespace executor](namespace-execution.md)
routes selected guest syscalls here. Direct-backend hard-link controls remain
separate from successful namespace package-lifecycle checks.

## Ownership

- `fs_operation.h` defines borrowed requests, operation results and caller-owned
  output independently of transport framing. `fs_engine.c` dispatches these
  operations without guest pointers or transport IO. A metadata-only operation
  needs no byte buffer. Directory publication uses an optional synchronous
  callback before cursor advancement; returned size never exceeds supplied capacity.
  `fs_worker.c` owns the store and image catalogue on one thread; `fs_service.c`
  multiplexes RPC, notification and stop readiness. A client cannot
  shut down the service or acquire a different execution identity.
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

The abstract Unix SOCK_SEQPACKET endpoint has no filesystem path to translate.
Both ends check SO_PEERCRED against their effective UID. Device fixtures require
the already-selected shell UID 2000; build-host tests use the host UID. This is
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
Namespace path xattrs likewise operate in the client on the retained native
inode, with real kernel permission checks. Values are not serialized into the
service protocol or stored in SQLite.

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
carry the caller-masked creation mode. The underlying model API itself retains
normal calling-process umask semantics. Real identity is unchanged; guest inode
permissions use the supervisor's explicit credential model. The offline prepared-rootfs
import does not implement live promotion, object reclamation or notifications.

Keep SQLite and its locks outside the guest process. Preserve the direct kernel data path
and truthful failure/commit outcomes as syscall coverage expands. A real fixture
install/update/purge workflow and distribution package-manager checks pass;
see [application coverage](../../docs/guest-runtime.md#coverage-and-limits).
Those workflows do not establish a complete Linux ABI, live storage reclamation
or security isolation.

References: [Unix sockets and SCM_RIGHTS](https://man7.org/linux/man-pages/man7/unix.7.html),
[recvmsg truncation and CLOEXEC](https://man7.org/linux/man-pages/man2/recvmsg.2.html),
[ppoll](https://man7.org/linux/man-pages/man2/poll.2.html).
