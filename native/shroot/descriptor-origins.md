# Descriptor Origins And Publication

[Native architecture](README.md) | [Resource model](process-resources.md)

File-table annotations and the publication boundary between kernel descriptors and logical filesystem state.

- [Descriptor And Cwd Annotations](#descriptor-and-cwd-annotations)

## Descriptor And Cwd Annotations

`fd_origins` is a sparse metadata table keyed by guest FD number. It retains
anchors and descriptor flags, never readable/writable descriptions or offsets.
The owner follows CLONE_FILES independently of both CLONE_FS and mount-view
ownership. A fork without CLONE_FILES copies annotations; shared owners see one
table. Registry and in-flight request pins keep it alive without counting as
task owners. An absent entry means unknown provenance, not EBADF.

`md_fd_origins_native_io` is a positive, allocation-free IO proof under a
running table turn. It requires the actual ordinary anchor object, not a typed
object that merely exposes a filesystem origin, or a metadata-only anonymous
pipe. Virtual FIFOs retain their own data provider. Loan custody and uncertain
slots decline. The supervisor additionally requires sole task ownership before
using the proof for native execution; shared-table reuse cannot borrow that
shortcut. Base and channel ingress share the same classifier and release the
table turn before IO. It never captures or retains the application's data OFD.

The owner is small; buckets and entries are allocated only for explicit
annotations. A prepared update reserves memory and retains its origin before a
kernel mutation. Publication after success needs no allocation; cancellation
leaves the old entry intact. Replacing a destination with an unknown origin
clears its previous annotation. Range closure, close-on-exec marking and exec
cleanup operate on existing entries without per-FD allocation. Locks cover only
metadata; removed anchors are released outside them.
Single-FD retirement and short close/mark/abandon ranges visit only the hash
buckets intersecting that range. Collisions still check the full descriptor
number; wide ranges and exec scan the table. Publication generations, loans,
uncertain channels and last-reference callbacks use the same retirement path.
This changes lookup cost, not the kernel-completion requirement for native close.

Origin vectors separate source capture from destination publication. Capture
retains the exact ordered origins under one metadata lock, including repeated
source FDs and explicit unknown entries. It uses caller-owned storage and retains
neither the source table nor data open-file descriptions. Source replacement or
owner exit does not change the captured vector. The source table turn must still
cover actual kernel descriptor capture; a metadata snapshot alone is not that
capture or a message-to-origin association.

`MD_TASK_HELPER_EXPORT` captures an explicit ordered vector of 1..254 descriptor
numbers in the existing private-table helper. It copies the vector before
acceptance, preserving duplicates; only the inherited keep-set is sorted and
deduplicated. The caller holds its source-table turn while taking the origin
snapshot and until the helper's exact clone callback. That callback releases
the turn before descriptor export, so later shared-table close/dup cannot
retarget the captured inputs. A failure before clone leaves that turn with its
caller until completion/cancellation.

The helper clears unrelated inherited descriptions, then uses the existing
authenticated FD channel and bounded 253-right packets. The result vector is
reserved once before helper creation; partial export stays private until full
completion and reaping. Cancellation or a rejected syscall releases partial
custody through the same owner as receive. No endpoint enters the caller's
table, and application filters and native quotas remain effective. Full caller
tables can succeed after unrelated helper slots are cleared; no available helper
slot returns EMFILE. Results preserve kernel OFDs, access flags, O_PATH, ordering
and shared offsets. Origins come from the ordered snapshot, not inode matching.
The helper does not negotiate a transport, send application bytes or enable
guest framing; those are the enclosing operation's responsibilities.

Batch preparation reserves the destination annotations before delivery and rolls
back every prepared entry on allocation failure. One retained source vector may
prepare independent deliveries, including peek copies. Completion validates all
FD numbers and table owners before changing anything, then publishes the installed
prefix in one metadata transaction and discards the undelivered suffix. Unknown
origins clear previous destination annotations. Publication allocates no memory;
retired anchors are released outside the lock. Internal filesystem RPC uses these
same primitives with its existing two-FD wire limit, without another allocation,
syscall or protocol phase.

The filesystem context similarly retains an optional cwd anchor. CLONE_FS
shares it, an independent context copies the reference, and exec retains it.
Root selection and cwd are independent: changing the root does not move cwd,
and cwd provenance alone does not activate the scoped-root adapter. The special
proc-root chdir clears ordinary cwd provenance.

Ordinary chdir resolves its directory through the filesystem transport and then
performs native fchdir. The supervisor captures that descriptor's origin and
publishes cwd provenance only after kernel success. A failed change retains the
previous origin; an unconfirmed change at task death clears it. A native host
chdir without a known origin also clears it after success. The kernel remains
the cwd owner; no pathname cache or synthetic cwd replaces it.

Relative requests capture the retained cwd anchor under the filesystem-context
turn. Direct requests duplicate its O_PATH reference in the worker; RPC exchanges
pin the same anchor without opening or sending a temporary guest descriptor.
The wire marks cwd inputs separately from SCM_RIGHTS inputs, and the service
validates that mask against the authenticated exchange. Both transports pass an
actual captured descriptor to the engine, never the worker's cwd.

An unknown origin retains native capture: `/proc/TID/cwd` for direct requests,
or `.` through the private-table task helper for mapped requests. The helper
copies the caller's FS context while both resource turns remain held, captures
the kernel directory and transfers it directly to the supervisor. No temporary
descriptor enters a surviving CLONE_FILES peer, including with a full table or
nondumpable caller. Cancellation retains the request until exact helper reaping;
failed clone/open/export does not execute or replay the filesystem operation.
Native host RPC still reports uncaptured cwd before send.
Application filters remain in force. Initial namespace cwd uses these publication and
native-fchdir completion paths after interception is ready and before guest code
loads. Exec retains the existing cwd. Direct-rootfs bootstrap cwd and
unannotated native descriptors have unknown provenance.

Capture fixtures distinguish these paths. A directory opened by the guest
retains its origin and must work when helper syscalls are denied. An inherited
native directory on stdin has no path authority; it exercises clone, table
isolation and export failure under a full descriptor table. Both preserve all
descriptors in surviving CLONE_FILES peers.

Proc aliases of the current thread's descriptor table borrow the original input
for metadata, readlink, reopen, watch registration and directory suffix traversal.
The mapped request captures its identity under the file-table turn, without a
temporary guest anchor. Current-thread cwd suffixes use the retained filesystem
context; proc-root paths resolve through the selected guest root. `/proc/self`
selects the group leader, while `/proc/thread-self` selects the calling thread.
An alias of a different thread's resources retains native lookup with unknown
provenance; it never borrows the caller's annotations, even when the owners
happen to be shared.
The mapped request describes that kernel path as a typed operand. `thread-self`
is bound to the requester before helper creation; `self` still selects the group
leader. The helper's copied file table cannot change which task the alias selects.

Filesystem opens from the direct broker and task-affine service publish their
origins automatically. Native dup/dup3, F_DUPFD/F_DUPFD_CLOEXEC, F_SETFD, close
and close_range update annotations from their actual kernel results. Fork,
unshare and exec use the same ordered ownership transition. The seccomp filter
and restricted-domain policy share the descriptor-operation list; observation
does not remove native descriptor capabilities after a proc-root restriction.

Duplicate admission is transport-independent. When all provider selectors are
already installed, Base and resident entries go directly to the ordered native
mutation. Otherwise the C adapter discovers providers before taking the table
turn, validates the discovery generation, and retains that turn across the
duplicate, selector activation and any rollback close. Resident controls and
native-effect receipts use the shared channel without fetching application
registers. A cancelled effect does not publish descriptor changes; a completed
close retains Linux's descriptor-retirement semantics even when it returns EINTR.

Application SCM_RIGHTS transfers on negotiated `ORIGINS_V1` connections carry
authenticated origin capsules through the receive adapter. Unannotated inherited
descriptors and native transports retain unknown provenance; see
[Unix transport negotiation](ipc-credentials.md). An unconfirmed native mutation
at task death forgets any known affected destination instead of claiming rollback or retaining
a potentially stale origin. An automatically assigned dup result is then unknown.
The dedicated O_PATH capture is narrower: at its exact openat return gate,
terminal registers identify a delivered FD or no delivery. Both ABI spellings of
the original AT_FDCWD argument are distinguished from descriptor numbers when
the kernel kills the call before executing it. Prepared provenance is published
under the retained table turn, before a surviving CLONE_FILES peer may mutate
that table. Unknown PC/syscall/results retain conservative abandonment; this
decoder is not applied to arbitrary native producers. The runtime requests exit
observation once on a task's first capture and retains it across clone/exec,
independently of nested debugger options. There is no cleanup syscall injection
at the terminal event and no extra per-read/write observation.
Complete provenance, including those terminal cases, is required before copied
guest namespaces can be published.

### Publication Ordering

Inherited descriptors 0-2 may retain O_PATH identity pins through
`fd_native_pin`. They provide metadata for a protected, filtered caller without
exporting an extra data description through a forbidden helper syscall. Pins
do not hold a pipe writer/reader open, retain a data offset or create guest
filesystem provenance. Capture failure leaves ordinary fallback available.
Close/dup/fork use the same file-table publication rules as other origins.
Anonymous inherited pipes also retain `descriptor_inode` guest ownership and
mode bits. Aliases share this metadata; guest chmod/chown never changes the
Android pipe. The identity pin neither delays EOF nor intercepts ordinary IO.

After an application filter is installed, a completed filesystem result may
require task-affine FD delivery. The result owner retains that completion while
the supervisor admits the original syscall PC and six arguments through the
kernel filter. A permitted replay publishes the existing result exactly once;
it does not execute open/create again. Group-stop cancellation retains the open
description and repeats only admission on kernel-internal restart results, not
on a final errno such as EINTR. A denied replay retains the filter's action.
Optional mapping-backing capture is omitted when its clone/sendmsg
helpers would introduce additional calls after filter installation.

`resource_order` provides an intrusive queue embedded independently in each
file-table and filesystem-context owner. Typed adapters pin their resource through
completion, including after its last task owner exits. The supervisor and
filesystem worker share the synchronized queues, while each record retains one
event-loop owner. Mutexes cover metadata changes, never kernel calls or event
waits. CLONE_FILES shares the file queue; CLONE_FS shares the filesystem queue.
Independent resource copies start with empty queues.

Operations needing both acquire files before filesystem context and release in
reverse order. Shared-cwd peers with independent file tables still serialize on
the same filesystem context. Cwd capture releases that turn once its directory
identity and origin are retained, before filesystem execution or RPC send. Fork captures both
resources at the kernel birth event; filesystem unshare holds both through its
kernel result. Neither waits for child completion while retaining a turn.

A zero-initialized caller-owned record is queued or immediately ready, then
explicitly started. Completion follows the exact kernel result and its metadata
publication, and signals the newly ready successor's retained eventfd token.
No successor pointer crosses event-loop ownership. The token duplicates the
nonblocking eventfd once per event loop, not per request; loop shutdown or FD
reuse cannot redirect a later wake. A saturated eventfd already has a wake pending.
Cancelling a queued or ready record removes it; cancelling a running record
only records intent. It continues to own its turn until the kernel operation
is confirmed complete. Cancellation or a timeout does not prove that a descriptor
was never installed. Requests perform no queue allocation, callbacks or blocking
waits, and unrelated resource owners can progress independently.

Retirement can outlive its requester. Its task-affine close may borrow a peer
at an exact kernel boundary, including a readiness waiter queued behind that
same table turn. A guest debugger's running inferior remains eligible through
the common task-call/quiescence owner; tracing alone is not a published stop.
Debugger-held stops and pending interrupts remain excluded, except for the
existing stopped inferior explicitly owned by the requesting debugger. The
borrow restores the exact continuation without executing application code or
consuming a guest debugger event. Excluding all traced peers would let orphan
retirement retain a table turn while every peer waits for it.

Both filesystem transports and the observed native mutations use this ordering.
A blocking receive or FIFO rendezvous waits outside the queue; only its short
descriptor capture/publication phase belongs inside it. A cancelled queued
notification is validated again before use. Unordered native producers and
readers of live FD numbers do not gain a kernel barrier from the metadata mutex
or this queue's existence. Close and replacement can release a native description
with blocking cleanup; complete peer-dependent progress for such native resources
is not established by the short-operation fixtures.

### Kernel Publication Boundaries

`ADDFD_FLAG_SEND` makes FD installation and the notification response atomic
against cancellation. It does not keep the receiver stopped until subsequent
supervisor metadata writes. A CLONE_FILES peer can replace that slot even when
installation and response are separated. Replacement with another open
description of the same inode is not detected by device/inode validation.

A split ADDFD followed by a cancelled notification can leave an installed FD
while the caller receives EINTR. A failed SEND is therefore not proof that no
descriptor was installed, and rollback cannot blindly close a reusable FD number.
SCM_RIGHTS similarly installs received FDs before an application acknowledgement;
the acknowledgement is not a shared-table publication barrier.

The direct broker prepares the annotation before atomic ADDFD_SEND, publishes
the returned FD number, then releases its table turn. An observed shared-table
mutation cannot overtake that publication. Failed atomic delivery discards the
prepared annotation, not a guessed descriptor number. Notification ADDFD rejects
O_PATH. An eligible caller-side native open can acquire its own descriptor.
O_PATH|O_NOFOLLOW directories use the retained `fd/.` target, with a native
search-permission check before offering the plan; their F_GETFL flags remain
unchanged. Non-directories retain exact descriptor custody: reopening a procfs
magic link would either open the link itself or lose NOFOLLOW. They keep the
task-affine route, as do directories that cannot admit the extra search.
An already matching O_PATH pin can be duplicated without another pathname lookup.

Task-affine RPC captures input origins under a short send turn. A launch-local
exchange ID binds them to the authenticated actor and exact table; wire values
are not pointers. The worker retains the exchange independently of its initiating
task. Metadata-only replies need no receiving table turn. FD replies first send
a sealed-size shared receipt, then a separate payload-FD parcel. The receiver
waits for readiness outside the table turn and executes nonblocking recvmsg
inside it. The kernel writes the installed FD numbers into the retained receipt.
Normal completion or the receiving task's terminal event publishes those numbers
before releasing the turn; no post-receive guest ACK is needed. Truncated delivery
publishes exactly the installed prefix before client-side error cleanup.

`fd_delivery` owns prepared destination annotations and the retained receipt,
independently of RPC actor authentication. The caller retains its operation and
capacity-sized scratch arrays; filesystem exchanges embed the two-entry arrays
in their existing allocation. Preparation pins the target table and origins,
not the sender or data descriptions. Failed preparation releases every acquired
resource. Kernel completion publishes the exact installed prefix once; an empty
receipt may be retried after readiness or disposed on final zero delivery.
Disposal releases metadata and the receipt, never installed application FDs.
The owner serializes access and retains the destination turn through publication;
the component does not acquire a queue turn or infer syscall completion itself.

Only FD-producing RPC exchanges allocate a receipt mapping. Publication needs no
allocation. Transport readiness, peer lifetime and syscall completion remain
distinct from cancellation intent.
The shared `fd_receipt` layout supports one rights parcel of 1-253 descriptors;
capacity is retained by the operation, not read from writable guest memory.
Mappings are sized to the offered capacity, so ordinary filesystem RPC retains
its one/two-FD allocation and wire limit. The kernel receives the exact control
length, not aligned spare room that could admit an extra FD. Parsing ignores
foreign pointer fields, bounds all lengths and validates every descriptor before
copying any output. Invalid or duplicate numbers leave the output unchanged.
The shared mapping is a lifetime mechanism, not protection against a malicious
guest modifying its own memory. `fd_delivery` does not authenticate source/message
association; that belongs to the transport, while retained anchors own backing
custody. Application SCM_RIGHTS provenance uses the negotiated capsule transport
described in [self-contained origin custody](mount-views.md#self-contained-origin-custody),
not `fd_delivery` alone.
See the kernel's [userspace notification contract](https://www.kernel.org/doc/html/latest/userspace-api/seccomp_filter.html#userspace-notification).
