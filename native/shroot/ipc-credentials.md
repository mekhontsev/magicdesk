# Unix IPC Credentials

Guest identity belongs to the supervised task, not Android. `socket_identity`
adapts connection credentials and `socket_ancillary` adapts explicit credential
messages. Both use the authenticated namespace request path and the same
`guest_identity` snapshots as filesystem permissions. There is no D-Bus-specific
authentication bypass, payload proxy or second process-identity model.

- [Connection Identity](#connection-identity)
- [Application Ancillary Transport](#application-ancillary-transport)
- [Private Peer Agreement](#private-peer-agreement)
- [Explicit Credential Messages](#explicit-credential-messages)
- [D-Bus And Coverage](#d-bus-and-coverage)

## Connection Identity

Pathname bind/connect use the filesystem namespace's task-affine proc-path
resolver. Retained and foreign `/proc/PID/fd/DIR/name` operands capture the
directory before walking the guest suffix, including after rename. They do not
interpret the worker's FD table or traverse logical names on a backing directory.
Bind stores its original socket name independently of the resolved dentry;
getsockname and accepted peer addresses preserve that name. Closed or reused
directory descriptors cannot select a previously held directory.

`ipc_credentials` is a lazy, store-scoped metadata authority owned by each
namespace worker. Workers sharing a store serialize metadata operations with a
kernel file lock. They never hold this lock across guest connect, accept or IO.
`<store>/ipc` is transient state, separate from image/inode metadata. A shared
live-owner lease makes the first owner discard stale records, including socket
cookies retained across a device reboot. Connection bindings can retain this
epoch after the worker closes SQLite. They share its scope by reference without
duplicating descriptors; the scope retains the epoch lease and the store's
shared maintenance lock. Database/gate resources close independently. The last
scope owner releases both leases; a new owner can then retire old associations.
No service or kernel probe is added to
MagicDesk startup, and ordinary filesystem calls do not open this database.

Kernel `SO_COOKIE` identifies a socket across dup, fork and SCM_RIGHTS. Listen,
connect and socketpair capture effective guest UID/GID and supplementary groups.
SO_PEERCRED and SO_PEERGROUPS read copied connection snapshots, not the peer's
current credentials. A later credential drop, peer exit or FD transfer does not
rewrite them. Native peer PID is checked before publishing a snapshot.
The snapshot also captures the TGID's numbers in each ancestor PID namespace.
Readers select their own namespace's number, or zero for an invisible peer.
These immutable values survive reaping and native PID reuse without retaining
the process. Thread senders use their TGID, not their TID. Task-registry snapshots
retain that identity through worker execution; credential reads do not allocate
another process object.
The shared connection key contains a random link identity and its side, resolved
from the captured socket's kernel cookie. Socketpair records both sides;
connect/accept publish opposite sides independently. A client may queue a
message and exit before accept, and accept may complete before the client's
connect result is published. Pending, invalid and unregistered keys are not
invented from PID, payload or FD number. The key has meaning only within the
live store authority; its last-owner retirement invalidates old associations.
It is not proof of peer liveness, transport capabilities or a cross-launch
mount-view ownership contract.

The native `fd_messages` owner uses this directional key with a captured carrier
cookie for message-origin lookup. It retains O_PATH provenance and a pipe-lifetime
monitor, never application data descriptions or the carrier. Its registry and
worker pins have independent lifetimes. This component is covered by model and
carrier-fixture checks; the application ancillary adapter does not use this
transport yet. It also does not reclaim the credential-token records described
below or transfer in-memory mount anchors between supervisors.

The worker returns that snapshot through the existing mapped byte output,
without installing an identity memfd in the application table. SO_PEERGROUPS
supports all 65536 groups; SO_PEERCRED returns only the fixed identity header.
Input capture retains the exact socket description. After channel admission,
both work with a full ordinary or protected FD table. Short buffers, ERANGE and
output-copy faults keep their syscall semantics. The unused mapped value pages
are not cleared by ordinary filesystem requests.

For a connection to a registered guest listener, an otherwise unnamed client
gets a private abstract transport address. Accept resolves that exact connection
without netlink permissions or guessing by PID. The address adapter hides this
implementation name. Existing user-bound names are preserved. Connect preparation
and completion validate the listener revision and actual peer PID. Accept selects
by the peer name, destination address and kernel peer PID. Unresolved candidates
survive name reuse; two indistinguishable queued connections fail with ESTALE
instead of selecting the newest record. Already accepted connections retain
their cookie-bound snapshots. Link indexing bounds registration lookups without
adding work to ordinary data IO. An ambiguous snapshot does not replay a
successful connection or invent an identity.

Unregistered/external endpoints keep kernel UID/GID authority. Their incoming
PID is translated into the reader's PID namespace; an external process is zero
in a child namespace. Outgoing explicit PIDs are translated back for the kernel's
credential check. External receivers see actual Android credentials, never a
private identity token. Abstract socket names
remain in the host's shared namespace. These mappings do not provide network
isolation or protect mutually hostile processes sharing the real executor UID.

## Application Ancillary Transport

An IPC authority has one immutable runtime-owner offer: `NATIVE` or
`ORIGINS_V1`. This is not an application request or a feature inferred from
received descriptors. Listen publication records the offer alongside its
revision; connect preparation selects `ORIGINS_V1` only when both declarations
match. Otherwise it selects `NATIVE`. The selection becomes usable with the
confirmed connection, before the client can send its first message. Socketpair
publishes both sides with one selection in one transaction. A conflicting
second insertion rolls back the first instead of leaving a half-pair.

Accept inherits the candidate's selection, including when it runs before the
client publishes connect completion or after the client exits. A pending
candidate requires the original ready listener revision. A completed connection,
including one already confirmed by accept, retains its selection through later
listener changes. Re-registration cannot rewrite it. Dup, inherited descriptors
and SCM_RIGHTS aliases resolve the same cookie-bound record; opening another
authority with a different offer does not upgrade existing sockets.

Named datagram receivers register their actual bound socket cookie, binary
address and transport offer. Each send resolves its destination independently;
the sender's own bound address does not select its receive association. Logical
pathname resolution retains the existing socket inode or native path anchor.
Connected and addressed sends share this lookup. An unknown destination keeps
native transport; a registered receiver also accepts ordinary native rights.
Recognized capsules still require their destination binding and authentication.

`md_ipc_binding` captures this selection with the directional identity under the
same metadata gate and retains it after SQLite closes. Unregistered, pending,
invalid and malformed associations remain explicit errors, not an invented
`NATIVE` agreement. No negotiation bytes enter application queues, and neither
ordinary read/write nor resource probes acquire a new per-data operation.

Application format and private control agreement are separate contracts.
Carrier publication, route reading and origin service admission require a
creation-time `ORIGINS_V1` binding. A successful private ORIGINS agreement alone
cannot upgrade a `NATIVE` connection. Native-binding rejection occurs before
peeking at a candidate carrier or transferring ownership of caller descriptors.
Self-contained capsules authenticate an immutable kernel queue with the store
epoch key and exact directional connection, without a live source owner.

Production filesystem owners declare `ORIGINS_V1`. Outgoing ancillary messages
and the receive adapter handle creation-time bindings through the shared buffered
worker. Inherited native sockets and sockets exported through a native connection
are latched native-only by cookie. Exporting an already adapted socket to such a
consumer fails with EXDEV before sending. This prevents an unadapted process from
receiving private capsule framing. Sender-independent capsule
coverage is described in [process resources](mount-views.md#self-contained-origin-custody).
`--case fd-messages` covers 29 transport scenarios across stream,
seqpacket and paired datagram sockets: mixed offers, both publication orders,
independent sender exit, listener revisions, aliases and transaction rollback.
`--case ipc-peer` checks carrier rejection on native bindings, including a valid
private agreement, unchanged application data and exact descriptor balance.

## Private Peer Agreement

`ipc_peer` agrees protocol features over an explicitly supplied private Unix
seqpacket control channel. It never sends negotiation bytes or descriptors to an
application socket. Worker-side admission resolves the captured application's
directional connection binding, rejects registered application sockets as control
channels, and checks the control socket's type, flags and real peer UID. The
database gate is released before control IO; the application socket is not kept.
The runtime owner supplies a verified private channel; a matching executor UID
alone does not establish its launch or application-connection identity.

Each side offers a fixed version, connection side, supported/required feature
bits and a fresh challenge. Confirmation binds both challenges and the exact
feature intersection. Opposite connection sides must match; incompatible required
features, wrong versions, reflected/stale confirmations and malformed packets
fail explicitly. Agreement with no common optional feature selects none; it
does not authorize a carrier. Confirmation is not a continued-liveness promise.
Control-channel failure cannot silently downgrade a message already framed.

One nonblocking step performs at most four packet operations using the existing
bounded `fs_wire` sender/parser. It does no SQLite work or allocation. The owner
waits for the returned POLLIN/POLLOUT interest and owns cancellation/deadlines;
timeout is failure, never implicit agreement. Errors close the private channel,
release its binding and remain terminal. Unexpected/truncated ancillary rights
are closed through the common parser. After agreement, further control packets
belong to the caller's next protocol phase and are not consumed by negotiation.

`ipc_peer_service` bridges supervisor-owned requests to the existing filesystem
worker queue. Both ordinary guest IPC and private admission obtain their lazy
authority through `md_fs_ipc_authority`. The supervisor does no SQLite work and
does not wait for the metadata gate. Successful submission transfers an already
captured application socket and the private control descriptor without duplicating
either. The worker releases the application socket immediately after checking
its binding; it performs no control-channel IO. Completion returns the prepared
peer to the event-loop owner, which drives negotiation separately. Admission is
not agreement or permission to frame application messages.

Cancellation clears the caller's handle without waiting. Pending work retains
its record until completion or worker join; a cancelled callback is never
delivered. A cancellation concurrent with metadata admission cannot free the
worker's input. A rejected submission retains caller ownership of both FDs.
Successful take transfers the peer independently of the request/service/worker;
terminal cleanup joins the worker before releasing undelivered records. The
service has no additional thread, socket, timer or event source. Neither ordinary
filesystem calls nor ordinary data IO create peer-admission requests. Request
unlinking is constant-time, including cancellation in a large admission batch.

### Launch Rendezvous

`launch_peer` resolves an explicit launch ID through `launch_registry`, using
the same locked record, retained owner descriptor and pidfd checks as launch
enumeration/cancellation. Lookups are relative to the registration's retained
directory, not a reopened store pathname. Renaming the store does not retarget
discovery; another store, even at the former path, cannot resolve its launches.

The owner lazily opens a nonblocking CLOEXEC Unix seqpacket listener under its
random launch ID. Closing it removes the abstract name; the registration outlives
listener and admission work. No discovery daemon, periodic scan, additional
thread or per-data-call work is introduced. The connecting side verifies the
kernel server PID against the pinned live registration before sending one fixed
launch-ID packet. The accepting side reads only that packet and verifies the
client ID against its kernel peer PID and the same registry. An inherited record
does not authorize another process to impersonate its owner. Failed admission
retains caller ownership of the accepted socket; a failed connect releases all
resources of that attempt. Full queues/backlogs and kernel denial remain explicit
errors, without blocking or automatic replay.

Registry IO belongs on the filesystem worker. Receiving the discovery packet is
nonblocking and separate from that lookup, so readiness/cancellation stays with
the event-loop owner. Private transport validation and ancillary cleanup reuse
`fs_wire`; malformed packets cannot leak received rights or consume a subsequent
`ipc_peer` offer. Both discovery and connection-feature agreement must complete
before a caller uses the already selected application transport. Neither protects mutually
hostile processes sharing the executor UID, nor guarantees continued peer life.

The component establishes a private channel to a known launch. The message-route
contract below supplies that ID for an already selected candidate carrier.
The socket creator's PID cannot substitute for its current custodian after
inheritance or SCM_RIGHTS transfer. No production application path opens these
listeners or opts into the candidate FD carrier yet.

### Message Custodian Routing

An unpublished `fd_messages` vector may bind the captured sending connection and
its supervisor's registered launch ID. This retains the IPC epoch/store scope
independently of SQLite, the application socket and the source descriptor table.
Reference copies allocate nothing and duplicate no descriptors. Comparing two
retained scopes uses their physical store-root identities; overlapping epoch
leases prevent association retirement while either binding remains live.

The carrier's first packet contains a fixed 88-byte `fd_message_route` beside
the actual application rights. It identifies the version, vector length, kernel
carrier cookie, receiving connection side/link, source launch and a fresh
per-message nonce. The header stays inside the private carrier and contains no
in-memory origin pointers; application bytes are unchanged. A non-consuming
`MSG_PEEK` without ancillary storage reads it without
installing rights, including with no free FD slots. Type, length, version,
count, cookie and destination mismatches fail without consuming the packet or
changing output. This read is only for a carrier selected by an agreed transport;
it is not a probe of arbitrary received application FDs.

The launch ID is a routing hint, not authentication. After private launch
verification, the source owner checks the actual carrier and destination binding
against its registry and retained store scope. The entire offered header must
match its issued record. A reused carrier cannot select a replacement vector
with an earlier nonce. Successful claim pins the immutable local vector without
SQL, allocation or descriptor duplication. Registry retirement removes lookup
visibility, while the final operation pin releases both origins and IPC scope.

The independent-process fixture creates and registers an application socket in
one process, transfers it and reaps its creator before another exec'd receiver
handles the message. The receiver learns the custodian ID only from the queued
header, verifies the private channel and presents the real socket/carrier for
source-side validation. Its reply confirms the local claim, not a transferred
mount view. This covers creator exit while the custodian supervisor remains
alive; custody after that supervisor exits remains unresolved.

`test_resources.py --case ipc-peer` covers an independent exec peer, both SQLite
connections closing before agreement, epoch/maintenance lifetime, allocation
rollback, native/foreign-side rejection, malformed/truncated packets, backpressure,
incompatible requirements, unchanged application payload/rights and exact FD
balance. Its real-worker fixture also covers independent worker admission,
event-loop callbacks, cancellation before/during/after preparation, allocation
failure, 96 admissions with mixed cancellation/take order, rejected submission
and join with undelivered completions. Launch checks cover exact cancellation,
stale records, store rename/replacement, same-UID endpoint/caller impersonation,
owner exit before admission, full backlog/FD table and rejected ancillary rights.
The independent exec check discovers and verifies its channel through launch
registration before negotiating the application binding; it does not inherit a
control socket. These checks are part of the default resource model suite.
Message-route checks additionally cover 253 rights, repeated descriptor-free
peek with a full FD table, wrong stores/sides, stale nonces, malformed headers,
last-pin epoch/maintenance lifetime and exact payload EOF. The source registry
and both SQLite owners may close before the last pin releases its scope.
The source helper can export the application socket and 253 rights at one
private-table clone, releasing its source-table turn before export. Its caller
retains the matching origin snapshot. `ipc_origins` retains that capture on the
event loop and schedules capsule preparation on the filesystem worker. The
worker resolves the actual captured socket's binding. ORIGINS_V1 validates
backing identities and returns the same socket description plus its self-contained
capsule; NATIVE or unregistered peers return the original vector without duplicating
FDs or serializing origins. Completion reports the format explicitly. The
captured-send helper accepts either result without looking up original guest FD
numbers. Its private SCM_RIGHTS import supports O_PATH and up to 253 rights;
partial import is an error before any application send.

Guest ancillary sends and addressed Unix sends use one outgoing owner for
this complete path. Source metadata and actual descriptors are captured under
the same file-table turn, which ends at helper clone rather than after IO.
Preparation follows export reaping; final send uses only retained descriptions.
Format selection runs on that captured socket; a guest filter denying SO_DOMAIN
cannot silently downgrade an agreed format. Non-Unix sockets retain native
selection and kernel ancillary validation.
The mapped adapter validates the selected native/capsule format and normalizes
split rights headers. Its buffer release cancels any prepared but unused result.
Cancellation during helper execution waits for exact reaping before freeing
custody. Explicit credentials are validated against the captured task identity;
their sealed capability joins the same prepared vector. Non-right headers retain
their bytes and relative order, including kernel rejection of unsupported headers.

Address resolution uses the captured cwd/root and mount view on the worker.
Virtual socket paths become their exact abstract transport name. Native paths
return an owned O_PATH anchor, imported separately into the private helper and
addressed through its `/proc/thread-self/fd` alias. This anchor never appears in
application SCM_RIGHTS or in the caller's table. It remains held through final
send and exact helper reaping, including failure and cancellation. `sendto`
retains its syscall and arguments; `sendmmsg` retains partial batch completion.
An addressed datagram overriding a connection's peer cannot use that connection's
capsule or identity capability. Unassociated datagrams keep native ancillary
semantics rather than guessing an identity from the destination pathname.
Peer comparison follows virtual-path resolution. An explicitly framed datagram
connection cannot authenticate a native pathname anchor as its peer and rejects
that combination with EOPNOTSUPP rather than silently changing wire format.
Payload-only non-Unix sends retain native execution without a helper or worker.

Receive accepts captured socket/carrier custody, authenticates the capsule and
returns actual data rights with imported origins. No source registry, launch
lookup or live source process is involved. Cancellation and worker join own all
pending inputs/results; repeated receive preserves the kernel queues and data
offsets. Independent exec checks receive after sender exit and reclaim untaken
results after receiver death. Source-slot reuse, bounded concurrent requests,
allocation/quota failures and protected partial publication also run under shell.
See [authenticated origin transfer](mount-views.md#authenticated-origin-transfer).

The guest receive adapter uses `ipc_received` to resolve that captured binding,
separate identity capabilities and expand one carrier into annotated custody.
Its index plan preserves coalesced SCM_RIGHTS ordering and does not expose the
carrier to adapted consumers. Native connections retain their original rights,
including a capsule deliberately sent as ordinary application data.
The receive owner retains raw batch custody and replaces only the expanded
per-message vector after publication. Cancellation and worker join release
untaken results. Known origins use the shared kernel-written receipt and the
same publication owner as scalar file results. This works with O_PATH and
application filters that forbid private helper creation.
Kernel-generated `SCM_PIDFD` also transfers descriptor ownership. The common
ancillary classifier includes it in capture, cancellation and release, with
capacity for 253 application rights plus one pidfd per message. Publication
selects raw kernel custody for native senders. A validated sender capability
instead supplies its retained original-sender PIDFD, so brokered IO does not
publish the broker's identity. Both use the same descriptor delivery/receipt mechanism. Pidfds retain
CLOEXEC even without MSG_CMSG_CLOEXEC. Negative kernel pidfd results remain
ordinary error payloads and never enter custody.

The `ipc-ancillary` case covers actual guest sends and receives, including
protected/full-table callers, 253 rights, split headers, send/receive batches,
peek, partial memory faults, credentials and quota errors.
Kernel-pidfd cases also check mixed rights, truncation, repeated FD cleanup and
process filters forbidding helper threads, when SO_PASSPIDFD is available.
Send checks import only after the sender exits. Blocked native sends additionally check caller
death, source-slot replacement and unrelated pipe EOF. Signal checks retain
SIGPIPE delivery, masking and MSG_NOSIGNAL. Native/guest UDP controls check
payload delivery without rights; denied guest SO_DOMAIN cannot change selected framing. Native
in-flight-right limits may reject helper export with ETOOMANYREFS even when
table slots are free; the runtime does not raise quotas or replay consumption.
Address checks cover binary abstract names, virtual paths, absolute/relative
native anchors, zero-right/payload messages, mixed headers, all three sending
syscalls and full/protected callers. On a shell policy denying native socket-file
creation, the native-anchor fixture verifies final kernel rejection and release;
successful delivery to a fixture-created native path is explicitly unavailable.
The same captured-send helper also has a native pathname delivery fixture
(`libmagicdesk_guest_task_helper_fd.so DIRECTORY --native-anchor`): under the
Termux app UID it delivers 253 mixed/O_PATH rights from ordinary and protected
callers with exact FD cleanup. This verifies the mechanism, not shell permission.
The native-consumer cases exercise inherited/exported sockets, rejected adapted
socket export and exact descriptor balance. The receive matrix additionally
forbids clone and SO_DOMAIN in both ordinary and nondumpable processes.

## Explicit Credential Messages

SCM_CREDENTIALS validates the claimed guest IDs against the captured sending
task. Another PID requires CAP_SYS_ADMIN in the owner of the active PID
namespace and an existing subject. A child user namespace cannot authorize
claims in its ancestor-owned PID namespace. UID/GID selection requires
CAP_SETUID/CAP_SETGID or the sender's real/effective/saved IDs. The worker pins
the claimed process and captures its namespace numbers; reaping cannot retarget
the claim to a reused native PID. On adapted connections the kernel receives
the actual Android credentials, plus an identity bundle as one extra
SCM_RIGHTS descriptor. The bundle retains a sealed identity record, the original
claimed-process PIDFD, and the IPC epoch/store leases. Its random nonce, connection identity, kernel file identity
and sending side are recorded by the metadata authority. Only the
opposite side of the same connection can validate it; replay toward the sender,
another connection or another store does not translate the token. The receiving
adapter removes that descriptor and publishes the validated guest credentials instead.
The token also binds the retained PIDFD's kernel identity and records the subject's immutable PID-number snapshot at send time.
Queued messages and peeks retain those numbers after sender exit/reaping, even
if the native PID has been assigned to another process.

External sockets use the same logical-claim validation, then send only the
actual Android process credentials. Guest root and guest users never become
host UID 0 or another host account. No identity capability crosses this boundary.
The external-peer fixture covers self claims, denied forgery after dropping
guest privileges, and protected callers inside a PID namespace.

The namespace worker retains the sending capability; it never installs that FD
in the application's shared table. The common outgoing owner captures source
descriptions and task identity, releases ordering turns before IO and imports
the prepared rights/capability into a private helper table. The application socket
keeps its original number there so FD-specific application filters still apply.
It performs the native send and is reaped before completion. Helper/caller death
therefore releases temporary descriptors without closing remembered numbers in a
surviving CLONE_FILES peer. A concurrent close/reuse cannot retarget the send,
and a blocked send does not retain unrelated pipe writers or resource turns.
Kernel-generated SIGPIPE is forwarded to the original caller with its siginfo;
MSG_NOSIGNAL and an application filter returning EPIPE do not invent a signal.
Application filters still apply to private-helper setup and IO; rejection is an
error, not permission to bypass those filters or replay a send.

This keeps payload bytes, blocking/readiness, FD transfer, partial sends and
message ordering in the kernel. Ordinary read/write and native-format
credential-free sends do not create identity capabilities. Common ancillary
buffers use stack scratch; larger buffers use bounded temporary mappings.
Recvmmsg uses one native batch so its
timeout and partial completion are not replaced by a userspace receive loop.
MSG_PEEK does not consume identity metadata; truncation closes unpublished FDs.

Unfiltered receive without an admitted channel runs in a same-process private-table helper. Its socket is captured
under the destination table turn; the turn ends at clone, before blocking IO.
Unrelated inherited descriptors are closed before receiving. The helper exports
the actual socket and received rights through an owned channel and is reaped
before the adapter resumes. An allocation-free cursor packs the socket and rights
across message/control boundaries into vectors of at most 253 descriptors. A full
vector resumes at the exact next descriptor, without visiting uncompleted native
messages. Source indices remain stable through vector splitting. The supervisor
retains those descriptions in one reference-counted vector. A single mapped
inspection per completed message classifies credential capabilities and watch
descriptors under one captured task context. It pins the immutable source vector
without duplicating its FDs or entering file-table/FS turns: its inputs are
retained descriptions, not application descriptor numbers. Reference changes
belong to the supervisor; the worker's pin ends only after completion or join.
Ordered typed results reuse the mapped value area without increasing ordinary
request clearing. The adapter keeps no per-descriptor filesystem query path.
Caller death, fatal helper filters and export failure release the private table,
queued rights and retained sources. Later reuse of the original socket number
cannot retarget the accepted receive. Classification does not authenticate an
arbitrary message's mount-origin association.

Application-filtered operations export the captured socket and source rights
through the existing table-owned channel. Ordinary operations reuse that path
when their held file-table turn finds an already admitted, unambiguous channel.
This includes a channel admitted by a filtered CLONE_FILES peer. Ordinary IO
does not create a new infrastructure FD just to select this optimization;
cold/uncertain tables retain the private-helper path and its capacity semantics.
Admission uses an exact gettid gate,
still subject to all installed application filters. A denial retires capture
and releases its ordering turn; there is no filter rewrite or retry of the send.
For authenticated peers, supervisor IO carries a validated sender token so its
own native PID/UID does not become the peer's virtual identity. Native peers
retain task-affine sending. Both paths share origin preparation and custody.

The broker IO owner uses the mapped 64 KiB data and 4 KiB control buffers
inline, not as message-size ceilings. Larger single messages allocate temporary
host storage and copy through bounded chunks; one datagram remains one native
send. Iovec inputs are captured once. Ordinary small single-message IO adds no
payload/control allocation. A batch has one metadata allocation and one aggregate
payload/control arena, owned until completion or cancellation. Nonblocking batches
and an immediately available MSG_WAITFORONE batch create no IO thread. Unreceived
entries are never replayed. Existing vector,
ancillary-parser, integer-size and native quota limits remain explicit.

Ordinary blocking IO first uses a nonblocking kernel attempt and event-driven
readiness. The caller waits in its interruptible ppoll trampoline with its actual
signal mask. No file-table turn spans that wait. Socket timeout and partial
progress determine the result; SA_RESTART is decided by the kernel against the
original syscall context. SIGPIPE is delivered only for an actual native EPIPE
without MSG_NOSIGNAL, including blocked delivery through sigwaitinfo.

Blocking stream MSG_WAITALL, SO_RCVLOWAT and filtered recvmmsg use a host-owned
native IO job. One syscall retains native stream credential/rights boundaries,
peek behavior, MSG_WAITFORONE, socket timeouts and remaining batch timeout.
Its eventfd joins the existing supervisor event loop. Cancellation interrupts
that exact host thread; an ARM64 entry gate closes the signal-before-SVC race,
while cancellation after SVC preserves its actual result and installed rights.
Join precedes releasing custody or buffers. No application thread is created and
application filters are neither disabled nor modified by this job. Unreadable
input headers/iovecs, excessive vector counts and negative source-address lengths
retain the batch's kernel-owned SO_ERROR and poll behavior after a successful prefix.

Receive publication has one owner for ancillary data, translated source address
and output header fields. Application rights use the existing table-owned return
channel. Annotated rights are installed by kernel recvmsg into a mapped receipt;
unannotated rights retain direct application-control output. A later copy fault
cannot authorize closing a kernel-published descriptor. Cancellation drains only
queued rights and releases retained sources; there is no cleanup by guessed FD
number. Static partial-control faults limit the mapped receipt's capacity and
retain MSG_CTRUNC semantics. Concurrent remapping between validation and copying
does not have native per-word installation ordering; provenance remains tied to
the kernel receipt even when application output fails. Input-only message fields
are not written back.
Received watch descriptors activate their FD-class selectors for available
destination slots under the same table turn, before kernel publication. Failure
releases only unpublished resources. Ordinary read/write does not use this path.

For recvmmsg, each private msg_len records kernel completion even if the syscall
then fails writing the remaining timeout. Such a failure does not suppress
publication of the completed messages or leak their descriptors.
Receive layouts (up to the kernel's 1024-message limit) are admitted in one
shared-buffer exchange, reusing payload storage before IO starts. Preparing an
empty batch therefore does not require a supervisor checkpoint per message.
The brokered capture admits the prepared layout and starts that receive in the
same exchange. Optional dispatch-time buffer admission also serves recvmmsg;
failed batches without committed messages release their local allocations and
retire the buffer together with the final result. Partial completion still
publishes all committed messages before releasing their custody.
Up to 128 receive entries use bounded scratch on the existing guarded adapter
stack; larger batches and large ancillary/vector inputs retain allocated storage.
When no such allocation or committed message remains, the receive completion can
finish an error directly. Repeated EAGAIN therefore needs neither temporary
mapping retirement nor a separate adapter acknowledgement. The boundary and its
fallback are covered by native/guest comparisons, including protected callers.
The host still performs one native recvmmsg; partial completion, timeout and
deferred input errors retain that native batch boundary.

Current limits are explicit:

- Filtered payloads and headers are copied through owned host storage. Output
  faults discovered during application copying occur after the kernel receive;
  they do not have native per-word copy/consumption timing. This includes payload
  faults, not only the batch-header publication boundary below. No memory
  preflight is presented as equivalent to actual kernel publication.
- Task-affine SO_PASSCRED messages without an explicit SCM_CREDENTIALS send
  retain native credentials. Brokered sends to registered destinations carry
  the sending task's identity, including unconnected datagrams and sendto.
- The private capability consumes one kernel descriptor slot per explicit
  credential message. It does not remove kernel ancillary/FD limits.
- The private receiver and return channel consume infrastructure resources.
  Cold return-channel admission needs an extra application FD slot. Export from
  a protected private table is a real SCM_RIGHTS send, subject to the real UID's
  in-flight quota and RLIMIT_NOFILE; in particular, a queued MSG_PEEK can cause
  ETOOMANYREFS where a direct native receive succeeds. Failure releases custody,
  but does not undo delivered payload or consumed messages. Limits are not raised
  and an attempted receive is not replayed. This is not full native quota parity.
- Connection/message records remain until the last live owner exits. Long-lived
  workloads sending credentials continuously need lifecycle-aware reclamation;
  deleting records merely on receipt is unsafe with concurrent MSG_PEEK.
- Recvmmsg uses a private header vector. A fault publishing a user header or
  source address within that batch can therefore occur after later packets have
  already been consumed. The adapter does not reproduce the kernel's deferred
  socket error for this case. The comparative `rights-batch-fault` and
  `rights-publication-error` checks fail
  explicitly; a writable-memory preflight or replay would not establish the
  required receive/publication ordering. The native batch path is retained:
  a userspace receive loop must not replace socket-owned errors with a local
  errno cache. Errors generated inside the kernel retain native SO_ERROR,
  read/recv and poll/epoll behavior across duplicated and inherited descriptors.
- Kernel capabilities, SELinux, resource limits, SO_PEERSEC and native attached
  resources remain real. A denied kernel operation is not reported as success.

### Batch Publication Boundary

The publication comparison isolates header outputs, source-address copying and
the per-entry msg_len field. After one successful entry and a fault in the second,
native Linux consumes two of three messages, leaves the third queued, and exposes
one EFAULT through the socket. Shroot's private batch consumes all three before
the adapter encounters that fault. This is separate from FD custody: delivered
FDs remain caller-owned and later unpublished descriptions are released.

Linux's [recvmmsg loop](https://github.com/torvalds/linux/blob/v5.15/net/socket.c#L2562-L2666)
performs publication before advancing the batch and retains a deferred error in
the socket. [SO_ERROR is read-and-clear, not writable through setsockopt](https://github.com/torvalds/linux/blob/v5.15/net/core/sock.c#L940-L945).
A correct adapted batch needs per-message receive/publication ordering plus one
socket-owned error/readiness contract shared by aliases, fork and consumers.
Stopping a userspace loop alone does not preserve SO_ERROR, read/recv, poll/epoll
or native timeout behavior. Writable-memory preflights cannot replace the actual
publication event, and replay cannot restore consumed messages.

The error contract includes send/write, not just receive. Comparative checks on
stream/datagram/seqpacket sockets establish these additional boundaries:

- A stream receive may deliver queued data before consuming its pending error;
  datagram and seqpacket receive report the error first.
- A zero-byte read retains the error; a zero-entry recvmmsg consumes it.
- SO_ERROR consumes the error before copying its value, but not before reading
  the caller's length. Faults in those two pointers have different effects.
- An epoll registration with no requested events, established before the failed
  batch, is not notified merely by setting the error. A fresh poll or epoll MOD
  observes it. An unconditional companion-eventfd notification is not equivalent.

`rights-error-consumers` verifies native kernel errors through all these paths;
`rights-publication-consumers` exposes the missing adapted publication error.
Ordinary read/write and poll/epoll remain native. Fixing the publication boundary
must not impose unconditional mediation on those hot paths. A per-task errno
cache, or only intercepting the next receive, does not satisfy this contract.

The filtered IO suite compares native and guest signal interruption/restart,
blocked SIGPIPE, socket timeouts (including SA_RESTART), SO_RCVLOWAT, stream fragmentation, matching credentials,
Unix/TCP peek behavior, a 96 KiB datagram, a 1 MiB vectored stream send and batch
partial completion/input faults. A separate native-job test exercises 256
cancellation/completion races and verifies that no received pipe writer survives
cleanup. These checks do not certify the output-fault timing limitation above.

## D-Bus And Coverage

An unmodified distribution supplies dbus-daemon, dbus-run-session, policy and
NSS entries for the selected guest user. No daemon is shipped in the APK.
Session buses use ordinary credential authentication and service activation.

The `fd-messages` fixture exercises the shared connection key with independent
workers, both connect/accept publication orders, sender exit, aliases and a
separate store. It verifies token direction, failed/pending connections,
listener revision changes and invalidation after the last authority owner exits.
Bound-name checks distinguish separate destinations, reject ambiguous queued
accepts and preserve already accepted snapshots. A native listener replacing a
registered address cannot confirm the earlier connection snapshot.
These production metadata checks do not enable the fixture's private FD carrier
for application traffic.

The UID 2000 device suite covers Debian/glibc and Alpine/musl session buses,
independent launches sharing a store, rejection of another guest user, connection
snapshots after credential drops and peer exit, groups, short output buffers,
full FD tables, maximum/empty group snapshots and inaccessible output memory,
queued explicit messages, rejected credential forgery, MSG_PEEK, recvmmsg and
ordinary descriptor transfer. Sending checks cover full ordinary/protected tables,
invalid input slots, SIGPIPE delivery/masking/suppression and filter-produced
EPIPE. A one-megabyte send into a bounded socket provides event-driven coverage of
blocking IO, concurrent table mutation, cancellation, retained message prefixes
and pipe EOF without timing-based synchronization. Receive lifecycle checks block
MSG_WAITALL on a bounded stream, replace the original socket slot, check unrelated
pipe EOF, complete or cancel the caller, and require an unchanged surviving FD set.
The shared custody matrix compares native and guest ordinary/protected callers
with fatal fstat/sendmsg filters, including recvmmsg. Each permitted application
call must succeed despite restrictions on unrelated internal export operations;
the received CLOEXEC descriptor is closed and the shared table remains unchanged.
Channel tests cover 1/253-FD capture, cancelled queues and a partially published
prefix retained after terminal cancellation. Allocation-denied collector checks
cover empty/completed prefixes, multiple controls, vector splits and 1024-message
batches. They assert exact source indices and the minimum export-vector count;
1024 messages carrying two rights require nine exports including the socket.
Worker inspection checks cover 253 ordered/repeated inputs, invalid indices,
request-memory mutation after admission and captured context after task removal.
They hold both resource turns to verify independent progress and require no
descriptor duplication. Cancellation pins the mapped buffer and source vector
until worker completion; pipe EOF and final descriptor balance verify release.
Reference retention itself is checked with allocation denied.
A stock Debian bus activates a GDBus fixture,
reports its caller as guest UID 0 and exchanges usable FDs in both directions.
An external native endpoint still receives UID 2000 and rejects a root claim.
The same static ancillary fixture runs natively under shell and inside Shroot,
comparing stream/datagram/seqpacket peek, shared offsets, close-on-exec, the
253-FD limit, truncation, batch delivery, partial control writes, readonly input
fields, source-name errors and late header/timeout faults. Pipe EOF verifies
that discarded or closed descriptors leave no hidden writer references.
`rights-socket-error` compares a kernel payload-copy fault after partial batch
success, readiness through poll/epoll and one-shot error consumption through
SO_ERROR, read, recv and a forked peer. It is separate from the unresolved
adapter-publication fault above. `rights-publication-error` repeats those four
error consumers for all three adapter publication boundaries and remains failing.

A daemon may warn that its requested FD limit exceeds the executor's hard limit;
this does not justify fabricating a successful setrlimit. A working session bus
does not imply system-bus policy, arbitrary package configuration or full Linux
service startup. Device/build coverage remains that of the
[guest runtime](../../docs/shroot.md).
