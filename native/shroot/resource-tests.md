# Resource Ownership Verification

[Native architecture](README.md) | [Resource model](process-resources.md)

Focused native and guest checks for the [process-resource model](process-resources.md). Run commands from the repository root; BUILD selects the prepared fixture directory.

- [Verification](#verification)
- [Resource Ordering And Publication](#resource-ordering-and-publication)
- [Descriptor Custody](#descriptor-custody)
- [Protected Export And Executable Images](#protected-export-and-executable-images)
- [Mapped Results And Message Origins](#mapped-results-and-message-origins)
- [Filesystem Dispatch And Cancellation](#filesystem-dispatch-and-cancellation)
- [Mapped Transport And Open Descriptions](#mapped-transport-and-open-descriptions)
- [Proc Custody](#proc-custody)
- [Native Handoff Controls](#native-handoff-controls)
- [Optional Parcel Experiments](#optional-parcel-experiments)
- [Message-Origin Association Candidate](#message-origin-association-candidate)
- [Root And Filesystem Checks](#root-and-filesystem-checks)

## Verification

`test_namespace_apps.py BUILD STORE` runs stock Bubblewrap in a disposable
Debian userspace under actual UID 2000. Cases cover a basic command, a read-only
root with writable tmpfs, mapped UID/GID and hostname, and nested user/mount/UTS/
IPC owners with independent temporary filesystems, PID namespaces with proc/dev
preparation, and GDB start/continue/exit inside a PID namespace. `--trace` repeats these
production paths through stock strace. Both the command exit and explicit output
are checked; this is compatibility coverage, not kernel-equivalent isolation.
Chromium automatic user/PID and explicit set-ID routes are checked separately in
[Shroot](../../docs/shroot.md#application-filters-and-browsers).
`test_resources.py BUILD --case mount-namespace` also checks PID reservations,
nested admission, signal/wait output, zombie proc lifetime, pidfd transfer to a
protected receiver, orphan adoption, init thread exit and nonleader debugger exec.
Namespace-file binds also check pathname readback through ordinary and O_PATH
descriptors, reopened aliases and another process's descriptor table, including
a freshly mounted PID-scoped proc. Typed descriptor placement remains separate
from the anonymous carrier and retains namespace IO and ioctl semantics.

The device-node checks require `/dev/full` semantics for the canonical path and
guest-created aliases, independently of host device permissions: paged zero
reads, ENOSPC writes, seeks, native readiness, metadata, descriptor transfer and
nodev policy. `test_setid.py BUILD STORE` additionally closes stdin, stdout,
stderr and all three before secure exec, including descriptor-based exec.
The probe reports through a separate inherited pipe and checks libc's standard
descriptor repair without relaxing credentials or `AT_SECURE`.

The same mount-namespace suite's `user-limits` case checks live
`max_user_namespaces` control files in initial and freshly mounted proc views:
ancestor quotas, child-limit escalation denial, clone/unshare rollback, retained
namespace FDs, final-release reuse, readonly mounts and write authorization. It
also verifies inotify limit read/write and restoration by the initial root
identity, and write denial for root mapped to an unprivileged parent UID.
Repeated quota-one creation and
final-FD release cover kernel-reaped tasks left by native fast waits; fixtures set
their own root/tmp permissions and remove their temporary proc mountpoints.
Native `test_user_namespace`
adds per-UID accounting and concurrent creation at the quota boundary;
`test_process_limits` ensures cached NPROC scopes do not retain dead namespaces.

`test_sysfs.py BUILD STORE` and the resource suite's `input-session` case check
the standard empty `block`, `bus` and `dev/block` directories in synthetic sysfs.
Flatpak can bind these directories without exposing Android hardware nodes.
The same checks cover read-only native CPU/NUMA visibility, filtered directory
entries, native inode identity, data IO/dup offsets, metadata-write denial,
retained descriptors after unmount and proc-FD reopening. Virtual network
attributes preserve their original mount authority and error semantics.
The input fixture first checks sysfs without an input connection, ordinary inode
fast-path admission, symlink/rename boundaries, recursive `/dev` composition,
nested binds, `open_tree`, and explicit nonportable-publication recovery.
The mount-graph suite sends the CPU and device projections to an exec peer,
closes the publisher, then checks retained shared data, read-only policy and
exact descriptor balance.

The mount-flags fixture also transfers a noexec origin over SCM_RIGHTS to a
nondumpable receiver with an installed application filter and an independent
executable mount alias. The sender detaches its mount before the receiver
checks mmap/mprotect: the received origin keeps its restriction while a fresh
open through the receiver's own alias remains executable.

`test_resources.py BUILD --case rpc` runs the filesystem transport fixture.
Its exchange-ownership cases hold the client socket open through cancellation
before and after reply publication, covering metadata, result/receipt FDs and
watch reservations. An event deadline shorter than the service's peer timeout
requires explicit retirement; complete teardown checks exact host FD counts.
This checks production native-host service ownership; guest descriptor custody
has separate intercepted checks. The fixture also runs in the complete resource suite.

## Resource Ordering And Publication

With `MAGICDESK_GUEST_FIXTURES=ON`, `test_task_resources` checks independent
ownership, transaction rollback, retained-root copies and descriptor leaks.
Its `test_fd_origins` cases cover descriptor replacement, unknown origins,
close-on-exec flags, ranges, cwd/root independence, all CLONE_FS/CLONE_FILES
combinations and lifetime past task/registry removal. Allocation-failure injection
checks each preparation step and allocation-free publication; concurrent readers
check coherent origin/flag pairs. An annotated pipe must still reach EOF.
Ordering checks cover shared versus copied queues, middle/tail cancellation,
running cancellation awaiting completion, successor handoff, request reuse and
table lifetime after all task owners exit, with allocation failure enabled.
Three independent event loops exercise 3000 publications and concurrent
cancellation; saturated wake descriptors and owner-loop teardown retain correct
handoff. Exchange tests check actor/table binding, owner loss, malformed receipts,
preparation failure and allocation-free publication. Cwd inputs cover unknown
rejection, mixed explicit/cwd bases, two retained cwd bases and allocation rollback.
Shared delivery checks also force receipt creation to fail under RLIMIT_NOFILE,
retry empty receipts, release the task owner before publication, and dispose
partial deliveries without closing the installed application descriptors.

Pipe-backed worker readiness is covered by `test_ofd_order` within
`test_task_resources`: empty/bounded drains, saturation, signal-after-drain and
retained cross-owner wakeups after both original descriptor numbers are reused.
`watch_store` checks native-only zero-SQL pumping, mixed/independent observers,
last-reader and oneshot retirement, late journal cursors and hardlink wakeups.

`test_resources.py BUILD --case supervisor-state` exercises the actual supervisor
coordinator with fault injection at buffer adoption and kernel-accounting
boundaries, without production test switches. It checks exclusive cold admission,
the exact mapping-retirement exception, competing repair-event retention and
replay, unrelated progress, and accounting across debugger changes. Real ptraced
children verify single standard/realtime signal delivery after exhausted buffers,
failed result adoption and successful adoption. The private-memory suite includes
this fixture alongside its concurrent guest workloads.

`test_resources.py BUILD --case fs-results` additionally exercises ready-result
custody with four concurrent threads and 32 killed children, both natively and
under Shroot. Each runs normally, nondumpable, with an installed application
filter and with both restrictions. It checks CLOEXEC, shared open descriptions,
unlink-while-open, exact errors, no guest FD growth and positive broker delivery
counters. The existing result tests retain O_PATH, exhausted tables, FIFO and
protected-caller assertions. `--case launch-cancel` separately verifies exact
process-tree reaping for idle/busy and ordinary/protected callers.
`--case fd-handoff` exercises the shared atomic installer with interrupted and
restarted notifications, blocked table turns, dead recipients and exact FD
balance. The real-application counterpart is `browser/test_browser.py`, retaining
Chromium's sandbox and checking rendered JavaScript, a PNG and installed filters.
Vector tests cover source-owner loss, repeated/unknown origins, partial delivery,
duplicate or invalid result numbers, wrong destination owners, cancellation and
allocation-free unknown delivery. Concurrent vector readers cannot observe a
partially published pair, including two origins for the same inode in different
mount views.
Receipt parsing checks all capacities from 1 through 253 against an inaccessible
guard page, with malformed lengths, duplicate/negative numbers and foreign pointer
fields. No preparation or parsing allocation is permitted by these checks.
Filesystem-order checks cover all four CLONE_FS/CLONE_FILES combinations,
independent file-table progress, running cancellation and retained cwd after
all task owners exit, with allocation failure enabled.
`resource_guest` compares native Linux and Shroot cwd/umask/FD semantics,
failed-unshare rollback, close_range splitting/marking and exec inheritance. Guest-only cases check proc-root
sharing, native descriptor mutation after proc-root restriction, 32-bit fcntl
command semantics and semaphore-undo splitting, final-owner cleanup and exec retention.
Mutation-under-signal cases also verify close/EBADF and rename/unlink register
preservation with ordinary and nondumpable callers, with and without SA_RESTART.
`--case fs-dispatch` checks scalar mutation receipts without mapped output:
owned operands, exact errors, cancellation before/after admission and FD balance.
It also checks captured-fstat and immutable snapshot requests while the worker
is paused and a file-table turn is held: asynchronous submission, cancellation,
exact output and descriptor balance. Protected process-image checks repeat
foreign cmdline reads under signals and verify byte content and EOF. The SVC
suite installs TSYNC filters during filesystem work with native poll/epoll peers,
requiring actual denials, intact waits and zero filter-fence quiescences in both
Base and lazy modes. Fork cases install filters while the parent continues
accelerated work, verifying copied-frame retirement without changing parent slots.
Shared-mm exit cases kill one accelerated peer while other threads continue,
then install a filter with no stale slot or interrupted native wait.
Mount-namespace cases retain mutation coverage through `/proc/self/fd` aliases.
Store fixtures require zero SQL transactions for native-name mutation and warm
read scopes, while preserving external-writer visibility, lazy write rollback,
hardlink/overlay promotion, exact watch events and SIGKILL recovery.
Unique-name probes in native-only directories require only version checks;
foreign catalogue insert/promotion and rollback must invalidate cached absence.
The shared close-filter program checks 32-bit argument normalization, encoded
FD boundaries and preservation of unrelated syscall decisions.
Pipe acknowledgements order observations; an alarm is only a failure bound.
Concurrent cwd cases alternate successful and failed fchdir while peers perform
relative ordinary/O_PATH opens and getcwd. Both shared and independent descriptor
tables use the same shared filesystem context; guest opens exercise direct and
task-affine transports.
The RPC cwd cases install a real seccomp filter rejecting temporary O_PATH
directory opens. Relative getcwd/create/open and mixed/two-base link/rename
still succeed, both in ordinary and nondumpable callers.

## Descriptor Custody

`test_resources.py --case fd-custody` checks the production buffered O_PATH path.
A CLONE_FILES caller opens successfully with KILL_THREAD filters on connect or
recvmsg, and dies if the filter targets the result-delivery openat instead.
After waitpid its peer checks the entire low-FD baseline; none of the six guest
cases leaves a private descriptor, with or without dumpability. Native controls
do not enter the internal delivery gate. These checks also run in the default suite.

`--case fd-custody-dirfd` repeats the production custody checks with an explicit
known dirfd, including protected input capture and death at native delivery.
All six guest cases leave the surviving table unchanged; these also run in the
default suite and `--case fs-buffer`. `--case fd-custody-nofollow` checks the
same terminal paths through stable backing-name delivery, preserving O_NOFOLLOW.
All six guest cases pass without a private socket, and run in the default suite.
This covers inode-owned objects, not arbitrary native or anonymous sources.

`--case fd-lifecycle` measures alternatives natively under UID 2000, outside
the default suite. Redirecting registers invokes cleanup at a live ptrace stop,
but not at PTRACE_EVENT_EXIT after ordinary exit, seccomp KILL_THREAD or SIGKILL.
The surviving CLONE_FILES peer still owns the socket. A live task's real close
filter can also reject cleanup with EPERM. This matches the kernel's
[irreversible exit path](https://github.com/torvalds/linux/blob/v6.12/kernel/exit.c#L826-L934);
an exit event is not an opportunity to run another userspace syscall.

The same fixture reopens retained O_PATH references through a dumpable provider's
`/proc/PID/fd/N`. Deleted regular files, directories and symlinks retain their
native device/inode/type, even for a nondumpable receiver with one free FD slot.
Zero slots return EMFILE; a protected provider returns EACCES on the tested device.
A fixed-size register request/reply prototype performs that delivery with socket,
connect and recvmsg forbidden by real KILL filters. Its control exchange consumes
no receiver FD. Source descriptors remain pinned until the exact child exit.
This is not a general descriptor transfer: reopening an ordinary data descriptor
creates an independent offset, as its negative control verifies. The prototype
does not implement worker admission, arbitrary requests, origin publication,
fork/exec lane lifetime, cancellation or exact O_NOFOLLOW status flags. Its
results do not establish production transport coverage. Results use
`resource-fd-lifecycle-results.json`, independently of both default and custody reports.

Private-table helper controls verify kernel teardown after SIGKILL even with
inherited close denial and a nondumpable source. They also demonstrate two
admission problems: the application's clone filter prevents helper creation,
and copying the source table keeps unrelated pipe writers alive until helper
exit. This is not a transparent replacement for task-local RPC. Exact Unix
socket peer diagnostics are probed separately; creation of NETLINK_SOCK_DIAG
returns EACCES under the tested shell policy. Neither mechanism is a runtime
dependency or a reason to elevate the executor.

The lifecycle fixture also checks the kernel basis for table-owned **export-only**
channel. Its guest endpoint is infrastructure retained by the file table, not a
temporary descriptor owned by a requesting thread. Rights travel only towards
the supervisor, which owns that receive queue and each captured description.
Eight cases cover repeated protected sends, SIGKILL before dequeue or after
capture, seccomp KILL_THREAD before send, and a completely filled table after
channel admission. Regular-file offsets and flock identity remain shared; pipe
EOF proves release after explicit cancellation, not merely matching FD counts.
Receiver-side SCM_CREDENTIALS identifies the actual sender rather than the
socketpair creator; a forged PID is rejected with EPERM. This authenticates the
process, not the request, TID, source-vector origins or namespace authority.

Late ADDFD_SEND admission works in a nondumpable caller for which pidfd_getfd
returns EPERM, with socket/connect/close/clone denied by actual filters. One free
slot permits subsequent export without another allocation; no free slot returns
EMFILE without raising the limit. Native fork, close_range(UNSHARE), and exec of
a CLONE_FILES peer preserve the surviving table's endpoint. Ordinary close_range
and number replacement invalidate that slot without damaging its replacement.
These are kernel lifetime controls, separate from the production checks below.

This transport costs one infrastructure FD per admitted table and the native
SCM_RIGHTS in-flight quota. Its quota control reaches ETOOMANYREFS and then
discards the owned queue, releasing pipe EOF. It does not satisfy a zero-extra-FD
contract, and neither an FD reservation nor a limit increase is hidden from the
fixture. Production `fd_channel` integrates lazy ADDFD_SEND admission with sparse
descriptor metadata, dup/close/exec and shared/copied file-table lifetimes.
Messages match the retained channel, request token, expected rights count and
kernel sender process. Cancellation drains owned rights; request disposal closes
captured descriptions; last table/channel release closes the receive queue.
Accepted input vectors have intrusive, caller-owned leases on the channel.
The channel alone drains its queue and matches packets to those leases, including
independent callers that inherited the endpoint in copied tables. Finishing or
cancelling one owner retains other owners' accepted descriptions. Unmatched,
wrong-peer, wrong-sized, duplicate and late packets are closed. There is no
per-transfer allocation or callback into another subsystem during dispatch.
Ambiguous terminal mutations preserve an uncertain infrastructure entry. Before
reuse/admission, the caller observes F_GETFD and SO_COOKIE under the retained
table turn. The supervisor compares the cookie with its own endpoint, repairs
matching flags or retires only a closed/replaced slot's annotation. It never
closes a guessed number. Denied observation, malformed output or cancellation
leaves the entry uncertain. Only this exceptional path needs observation;
ordinary admitted transfers add no syscall or allocation for reconciliation.
This is trusted adapter coordination, not general application SCM_RIGHTS
provenance or a security boundary against hostile threads.

The protected-metadata continuation uses the same channel and input-owner leases.
Register-only observation/admission works without borrowing a mapped filesystem
slot. Its file-table turn spans capture, original-site seccomp admission and
result publication. A verified restricted proc-FD directory lookup opens the
fixed task-affine path from the borrowed original descriptor; that open creates
only the application's result FD. No temporary proc-root descriptor, private
per-request socket or intermediate result is installed into the guest table.

The production metadata custody fixture covers cold EMFILE, repeated protected
fstat with a full table, proc-FD directory opens with exactly one free slot,
read-only open flag combinations (including musl's `O_LARGEFILE`, nofollow,
nonblocking, CLOEXEC on/off and O_PATH), relative metadata on that directory,
flag-specific seccomp rejection without changing dumpability, and sixteen
shared-table deaths during export. Native controls and the guest path require
exact descriptor counts. The resource model rejects write/create/truncate,
unknown flags, foreign directory anchors and non-admitted paths.

## Protected Export And Executable Images

`--case fs-export` exercises the production adapter with a nondumpable caller,
socket/connect/recvmsg forbidden by real filters, cold EMFILE, repeated exports
at a full FD table, shared directory offsets and independent opens. Sixteen
CLONE_FILES callers die at sendmsg; their parent retains exactly its one channel.
Fork, close_range(UNSHARE), shared-table exec, alias replacement and readmission
check surviving ownership and exact FD counts. Namespace exec prepares images
on the worker, retaining all temporary and final descriptions outside the old
shared table. `--case exec-custody` kills a caller at image inspection, repeats
failed exec with an invalid environment, and executes with socket/connect/sendmsg/
recvmsg forbidden. Ordinary and nondumpable CLONE_FILES callers leave their
surviving peer's exact FD set unchanged. Unit checks additionally discard malformed/truncated packets and queued
rights, checking real pipe EOF as well as allocation rollback and FD counts.
Anonymous ELF admission requires an actual regular, unlinked, seal-capable
kernel image, not a failed managed-object lookup. The supervisor retains its
readable description before exec and publishes that owner only on confirmed
image replacement. Fork shares the owner; failed exec and terminal cleanup
release candidates. A typed control-file view serves self/foreign executable
opens after the existing process-access check. It preserves native inode/seals,
per-open cursors, dup sharing and backed readonly/private mappings. Writable
opens and shared writable mappings fail explicitly. No new proc reopen or copy
of the image is needed, and store-backed executables retain their existing path.
`test_anonymous_exec.py` checks ordinary/sealed memfds, nonleader exec,
close-range, foreign executable/pipe inspection, nested fork and self re-exec.
`--case exec-custody` also checks proc pathname `readlink` errors on directories
and regular files, separately from empty-path descriptor calls and missing
paths, both in the initial view and a newly mounted proc PID namespace.
Eight production service cases also verify wrong tokens, duplicate/wrong-sized
vectors, wrong senders, cancellation before/after queueing and a send error after
queueing. Each checks exact FD counts and pipe EOF after owner retirement.
Reconciliation checks replace candidates with files and other sockets, close
them, change CLOEXEC, reject malformed observations and deny inspection. Mapped
export and result-return tests resume after matching observations and cancel at
the observation boundary. The protected export actor also installs a real
getsockopt-denying filter. Allocation-free origin repair and exact FD counts
remain required.
Input-owner checks deliver vectors in reverse order across independent consumers
and channels, cancel one consumer while preserving another, and inject duplicate,
wrong-peer/count and retired-token packets. Exact pipe EOF and descriptor balance
are required with allocations disabled.

## Mapped Results And Message Origins

`fd_return` uses the export channel for mapped O_PATH results without a stable
reopen source. A channel-owned output turn spans fork-copied tables; it prevents
one task from receiving another task's result. Destination-table ordering covers
the nonblocking kernel receive and origin publication, not filesystem execution.
`fd_delivery` can borrow an existing shared receipt rather than allocate a memfd.
The retained mapping supplies exact kernel output after normal completion or
terminal task loss. Installed descriptors belong to the caller; cancellation
drains only unreceived rights through the supervisor's retained receive endpoint.
It needs no close syscall from the dead task or a surviving peer.

The same owner accepts annotated vectors with caller-owned scratch arrays and
a retained mapped receipt. It prepares
the complete selected origin vector before
kernel delivery, then publishes only the installed prefix under the destination
turn. Completion and cancellation use the same receipt; a failed acknowledgement
cannot erase installed FD provenance. Queued cancellation discards preparation
without admitting a channel. Source descriptions need remain open only until the
packet is accepted by the kernel; publication retains origins, not data OFDs.
The receipt must be the kernel's actual output, never an acknowledgement copy.
The alternative direct-output observation fixture, `fd_table_observation`, borrows an open
`/proc/<exact tid>/fd` directory captured in the task's context. Under the same
table turn, after channel admission, it records at most 253 lowest free slots.
After native receive it observes the installed prefix without reading application
memory. A failed control/header copy can install descriptors even when `recvmsg`
returns EFAULT; those installations still receive their exact origins. The read
must precede task reaping: `PTRACE_EVENT_EXIT` is the terminal observation point.
Vector completion preserves the native output error after publishing installed
origins; successful completion returns zero, not the number of installed rights.
An invalid acknowledgement remains EPROTO without erasing those origins.
An unavailable directory or non-prefix change is an error, never evidence of
zero delivery. No close/dup/exec or other installation may interleave between the
two observations. This is bounded storage and two event-bound observations, not
periodic polling, a retained copy of the file table or additional data OFDs.
The ordinary receipt path acquires no observation directory.

`--case fd-observation` exercises this production publication owner under UID
2000 with task-local private-table capture, protected/unprotected receivers,
separate/shared tables and both CLOEXEC modes. Cases include full 253-FD output,
unwritable control/message headers, partially writable FD words, zero control
space, zero/three free slots, peek, no receive and SIGKILL before/after receive.
Publication precedes queued table mutation; pipe EOF and exact FD balance verify
release. The 96-case matrix also covers invalid acknowledgement after delivery.
Reaped-task and non-prefix observations fail explicitly. Model checks
disable allocations during observation and publication and cover preparation
rollback and cancellation before admission.

The guest receive consumer selects the exact retained origin vector before
publication. A known-origin selection lazily allocates its origin-update scratch
storage; the existing adapter mapping already contains the kernel receipt for
up to 253 rights. Scalar file results and annotated messages share `fd_return`
and `fd_delivery`, independently of the caller's filters or dumpability. No
receiver directory capture or manual destination-slot installation is involved.
Static control-memory faults limit the received prefix before kernel delivery;
the actual descriptor numbers are then copied from the receipt. A concurrent
remap during that copy can leave installed caller-owned FDs without complete
application output; the receipt still owns their provenance. It does not claim
native per-word installation ordering against concurrent mprotect/munmap.
Unknown-only selections retain direct native control output and allocate no
origin scratch. CLOEXEC policy comes from the captured receive request, not a
later mutable output frame. Ordinary FD creators need no extra ordering solely
to predict the numbers of a received vector.

The same case runs 12 checks of the production helper, including protected
callers, full caller tables, repeated capture, rejected open, fatal helper
filters and cancellation during channel admission or after export. Completion
returns the stack lease once, installs no caller FD and leaves pipe EOF and
descriptor counts unchanged. Caller-supplied directory paths/flags are rejected.

Its source-export matrix adds 24 cases: ordinary/protected callers, 1/254 rights,
repeated capture, full caller tables, unavailable helper slots, invalid inputs,
clone/send filter rejection, fatal filters and cancellation before or during
either export packet. A real CLONE_FILES sibling replaces a source with a new
open of the same inode immediately after helper clone. Export retains the old
offset and captured origins after the original annotation table is released.
Borrowed input arrays may change after admission without changing the export.
Final checks cover exact descriptor balance, returned stack leases, source
teardown and pipe EOF. These checks compose real helper capture with the common
origin snapshot and received-FD custody; they do not enable application framing.

The guest receive adapter binds authenticated origins to actual custody on
explicitly selected ORIGINS_V1 connections. Ordinary production connections
still select NATIVE. Direct application-control output remains native:
receive-to-scratch followed by copying would change partial-copy installation
semantics. Actual adapter coverage is described under
[authenticated origin transfer](mount-views.md#authenticated-origin-transfer).

`--case ipc-origins` composes authenticated import, immutable source custody and
this vector publication after source workers/registries/importers have closed.
A protected CLONE_FILES receiver exits without acknowledgement; the survivor
publishes 253 rights, a truncated prefix, a peek or no delivery from kernel
output. A queued table mutation cannot overtake publication. FD flags, O_PATH
identity, repeated/unknown origins, final maintenance leases and descriptor
balance are checked, including receiver-local limits of zero and three free FD
slots. These component checks do not select guest framing.

Six native receive controls cover zero/one result slots and receiver death
before/after recvmsg. Sixteen production `fd_return` checks combine protected
receivers, denied receives, cancellation, exact zero/one-slot limits, pipe/O_PATH
results, metadata publication and shared-channel ordering across copied tables.
Pipe EOF and FD counts are checked independently. `--case fs-return` additionally
drives the actual bootstrap and adapter with socket/connect forbidden, cold
admission failure, caller-selected CLOEXEC and repeated full-table receives.
Twelve forked callers each perform 32 nofollow opens through one inherited
channel. Sixteen CLONE_FILES receivers die at recvmsg; their surviving parent
can receive immediately with no FD growth. These are trusted runtime results,
not arbitrary application SCM_RIGHTS provenance.

Separate stream/datagram/seqpacket controls show that closing the peer and
shutdown on either endpoint leave queued SCM_RIGHTS alive. Discarding from a
retained receiving endpoint releases them. This agrees with the kernel's
[Unix socket release/shutdown paths](https://github.com/torvalds/linux/blob/v6.12/net/unix/af_unix.c):
service-side close alone is not cancellation of the guest receive queue.

## Filesystem Dispatch And Cancellation

`--case fs-dispatch` joins scalar register control and shared request buffers to
the production filesystem worker, request context and descriptor-origin
publication. Under actual UID 2000, 504 cases cover files/directories/symlinks
removed before delivery, dumpable/protected receivers, zero/one free slots,
receiver death before admission, during execution or before delivery, and
registry-entry replacement while a result is retained. A 201-byte pathname is
snapshotted and validated before submission; later shared-byte mutation cannot
retarget it. Each case runs with separate files or a surviving CLONE_FILES peer,
and with inherited memory or a real kernel exec followed by fresh mapping setup.
Successful delivery publishes the prepared origin under the original table turn;
EMFILE and pre-delivery cancellation discard it. The receiving control path
creates no private FD, with socket/connect/recvmsg forbidden by real KILL filters.
Terminal cases kill the receiver at syscall entry, after kernel return, through
an openat KILL_THREAD filter, or immediately after resuming the entry stop.
Both sign-extended and zero-extended AT_FDCWD forms use the production terminal
result decoder and mutation publisher. A surviving peer checks exact descriptor
identity, O_PATH/CLOEXEC, origin publication and its entire low-FD baseline.
The immediate-kill race records the observed outcome; it is not exhaustive
coverage of every kernel interleaving. The deterministic post-return case
requires successful installation without a receiver acknowledgement.
Provider and surviving-peer descriptor counts return to baseline. The slot stays
unavailable until both task and worker release it, then reuses a new generation
with cleared bytes. Unit checks also cover independent mapping owners, failed
allocation/setup, stale handles, bounded capacity and allocation-free reuse.

The exec fixture inherits its notification filter, but not a setup descriptor.
Its new image requests a memfd through ADDFD_SEND, maps it and closes that FD
before running the receiver. On the tested shell policy, direct O_RDWR reopening
of the provider's memfd through proc returns EACCES; descriptor injection and
mapping succeed. No permission or filter is relaxed. Setup is confined to the
new exec image's private file table. A 96-record queue test checks worker failure,
cancellation of pending work, rejected late admission and completion dispatch
past an earlier callback error.

The 504-case O_PATH fixture uses its own traced control loop, not the production
syscall adapter. It does not cover concurrent CLONE_FILES mutations, all filesystem
operation payloads, protected input-FD capture or exact O_NOFOLLOW status flags.
Production-service checks exercise buffered cwd and O_PATH requests: unknown
context, invalid lengths/fields, input snapshot, transferred result ownership,
queued cancellation and submitted-open cancellation while an event gate holds the worker.
Explicit-base capture queues behind a file-table mutation. Closing the actual
unannotated input before capture returns EBADF without execution; close/reuse and registry removal
after capture cannot replace the retained input. Request validation is common to
RPC and mapped admission; resolution flags belong only to open requests.
Mapped replies copy the result header and actual payload, not the whole slot.
The RPC fixture also checks their shared result
contract using inaccessible output pages, malformed path/capability payloads,
metadata-only replies and retained/installed descriptor counts.
Removing task/resource owners cannot recycle a pinned slot before worker
completion or deliver a late callback to that task; the cancelled result closes
and final FD counts match baseline.
Results use `resource-fs-dispatch-results.json` separately from runtime regressions.
Unannotated dirfds are captured as real kernel descriptions, then replaced before
worker execution to verify that the accepted input cannot change. Watch delivery
uses the production reservation/acknowledgement state machine: successful copy,
rejected copy, invalid acknowledgement and cancellation before/after offering the
reply. Cancellation retains the worker's slot until the reservation is retired;
rejected copies preserve the exact records, and possibly delivered records are
never replayed.

`--case fs-buffer` selects the mapped-output checks also included in the default
suite, exercising the production syscall adapter and bootstrap with native
controls. Known-cwd getcwd remains usable at EMFILE with real KILL_THREAD
filters denying socket/connect/sendmsg/recvmsg, both dumpable and protected,
after fork and real exec. ERANGE/EFAULT are checked without changing the cwd;
48 simultaneous callers share the bounded pool. Unit checks cover CLONE_VM
ownership, fork's inherited shared allocator, fresh exec owners, mapping admission
and worker pins outliving every address-space owner. Production O_PATH checks
cover absolute and cwd-relative long paths, files/directories/followed symlinks,
protected callers, zero/one available FD slot, exact F_GETFL and CLOEXEC on/off,
with all four transport syscalls forbidden. Path/metadata cases exercise mkdir,
symlink, chmod, timestamps, stat, readlink, two-dirfd rename, unlink and open-unlinked
metadata, including protected callers, output EFAULT and a full descriptor table.
Nofollow file/directory/symlink cases use the same zero/one-slot matrix, checking
the selected inode/type and exact status flags. Model checks retain backing
identity across guest rename/unlink and lower-layer copy-up. The custody cases
above also run here. The report is `resource-fs-buffer-results.json`.
`--case fs-results` includes unannotated dirfds imported through actual SCM_RIGHTS
and socket/watch data inputs while socket/connect/sendmsg/recvmsg are forbidden.
The former verifies kernel-object capture, not arbitrary mount-origin transfer.
It also isolates mapped result delivery: ordinary and protected tasks
read/seek mount tables and executable images with exactly zero or one free FD slot, both CLOEXEC values
and socket/connect/sendmsg/recvmsg forbidden by real KILL_THREAD filters. These
checks also run in both the buffer and default suites.
Directory-seek controls keep those four syscalls forbidden at a full FD table:
dup shares the cursor, independent opens do not, and a rejected negative seek
preserves position. The production-worker fixture replaces the source FD after
capture and checks that only the retained original description advances. An
O_PATH descriptor remains unseekable rather than becoming its data counterpart.
Group-exit cases terminate 24 groups of eight peers during buffered calls,
including protected processes. A confirmed terminal stop after ESRCH retires
pending work and continues toward reaping without resuming the adapter. The
diagnostic counts observed races; it does not enumerate every interleaving.
Parked and continuously active groups require explicit launch-deadline failure
(exit 125) followed by completed terminal-stop cleanup, both dumpable and
protected. Active peers ignore TERM to exercise KILL escalation. The monotonic
timerfd joins the supervisor's existing event loop; a busy ptrace queue cannot
postpone expiry. Unbounded launches allocate it only on cancellation. Internal
supervisor failure, incomplete cleanup and outer-timeout recovery fail the test;
every reported tracee must have left native procfs when the runner returns.

## Mapped Transport And Open Descriptions

The production mapped transport covers captured cwd/dirfd path identities and
metadata, O_PATH (including stable nofollow sources), and data-FD results from
creation, temporary files, ACL/xattr access, mount tables and prepared ELF images.
Socket identity, directory seeks and watch operations capture real input descriptions instead of
substituting path anchors. A mapped watch read pins its request through task-local
copying and asynchronous worker acknowledgement. Unknown explicit inputs use
kernel duplication or table-owned export without inventing mount provenance;
nofollow sources without stable backing use ordered mapped result delivery.
Directory enumeration also uses mapped copy acknowledgement. Its temporary
`ofd_order` entry owns one exact description, compares aliases with self-kcmp,
and orders read/seek across broker, mapped and RPC requests. No permanent data-FD
mirror or cursor database is maintained. Synchronous reads with no active
reservation allocate nothing; a denied kernel comparison fails explicitly.
Mapped copies hold neither a file-table turn nor a filesystem-context turn.
Peer credential queries return mapped byte snapshots, not temporary identity
FDs in the caller. The same retained socket supplies the complete supplementary
group set and its connection-time identity. Protected callers need their admitted
input channel but no result slot. Native RPC carries large byte outputs in
bounded descriptor-free continuations under the original reply deadline; EOF,
incorrect lengths and unexpected rights fail without replay. Header-prefix
clearing leaves unused mapped value pages untouched for ordinary requests.
The worker commits the next cookie only after accepted copying; cancellation
after offering commits possibly copied records without replay. Cancellation
before offering and explicit copy rejection leave the offset unchanged.
The per-launch owner does not coordinate raw bypasses or OFDs transferred to a
different launch supervisor.
The focused directory checks reproduce rejected output, then verify unchanged
position, zero free guest FD slots, forbidden RPC, and four mixed protected/
ordinary fork readers without duplicates. Worker checks cover queued alias
seeks, independently opened descriptors and cancellation of either owner.
File opens/reopens and executable-object access also use mapped results.
Metadata and path operations can identify a store object instead of supplying a
descriptor. Both mapped requests and native RPC capture that identity once;
the worker opens and releases the object under the request's retained context.
Current executable metadata and watches use this operand even after unlink,
without a temporary guest FD. Object identity cannot substitute for a stateful
open file description: directory cursors, IO, sockets and watch queues require
captured descriptors. Each slot selects one operand, never both. This contract
does not add hostile-caller authorization to the existing filesystem service.
Deferred FIFO completion uses a short-lived supervisor child with an independent
kernel file table, not a guest-side private socket. It closes unrelated inherited
FDs before waiting; only the completed endpoint is delivered to the application.
Application signals request interruption; an already admitted endpoint retains
its result, while an unpaired open retains kernel restart handling. Abandoning
the request cancels and reaps the exact child, discarding unreceived rights.
Guest-root changes retain the CLONE_FS turn through worker publication. The guest
adapter no longer contains a filesystem RPC-client fallback. Native host clients
still use RPC. Reexec image preparation and proc-image snapshot construction are
worker-owned. Snapshot chunks use the existing mapped slot; each is copied into
request-owned storage before submission. No resource turn or SQLite transaction
spans task-local copying. Cancellation before, during or between worker actions
closes the construction FD, while successful delivery transfers only the final
read-only/O_PATH result. Kernel umask queries open status on the worker and return
its current value without modifying shared state or caching it.
Kernel-path operands and unknown cwd use `task_helper`, also used for SHM
mapping. Its private table owns open results and the transport endpoint until
kernel reaping. Successful capture transfers an owned O_PATH description to the
retained filesystem request; rejection/cancellation closes it without publishing
an application FD. For ordinary O_PATH results, the supervisor opens the stopped
helper's exact proc-FD alias while its private table owns the source. This does
not repeat name resolution or require device data access. Restricted proc access
falls back to task-local SCM_RIGHTS, also used for nofollow descriptions. Stateful
inputs never use this object-only reopening. Both routes preserve caller open
filters, cancellation and terminal reaping; subsequent data opens retain native
permission checks. Two operands are captured under one request's resource turns,
with independently owned descriptions unless both select the same cwd/path.
The filesystem worker rejects unresolved kernel-path operands, and host RPC
does not accept them. Stateful inputs still require actual OFD capture.
Mount-table and proc-image results retain O_PATH provenance for subsequent
metadata even when the caller has no spare FD slot. Path chmod/chown/utimens,
statfs and managed watch registration keep their intermediate descriptors on the
worker. Native proc targets retain task-affine lookup in the private helper. Current-thread
proc-FD opens capture the original descriptor under the table turn and perform
reopening on the worker, with no private guest anchor or repeated guest-number
lookup. Native object reopening and attachment reopening use the same primitive;
attachment policy is checked before execution and FIFO completion remains deferred.
Regular-file, FIFO and logical-device mknod likewise keep creation resources on the worker.
Chdir/truncate use a retained worker target and a task-affine path syscall through
its proc descriptor lease. No guest slot is installed; the final syscall retains
the caller's fs_struct, RLIMIT_FSIZE, SIGXFSZ and filter admission. The source
address binds cwd publication to that retained origin. Completion or task death
closes the worker lease. Current-thread proc-FD aliases use the same operation.
Descriptor-based fchdir uses a retained task continuation: input capture and
permission checking keep both the file-table and CLONE_FS turns through the
original descriptor syscall and adapter acknowledgement. Received descriptors
need no preexisting origin and no temporary guest FD. The worker retains the
selected origin until confirmed success publishes it as cwd. An ordinary error,
including a filter denial before native entry, preserves the previous cwd.
Cancellation before entry likewise preserves it; terminal loss after entry
invalidates the observation rather than interpreting terminal registers as
proof of a completed syscall. Both resource turns and all worker FDs are released.
Foreign executable readlink uses mapped bytes after the supervisor's existing
process/identity/domain check. It creates no guest file, needs no free FD slot,
and copies only the caller-requested prefix. Readlink never uses a temporary
readable descriptor to transport text.
Proc-FD capture retains logical provenance across a thread alias only when the
target snapshot names the exact file table held by the request. Kernel lookup
and backing identity checks remain authoritative; foreign tables do not borrow
annotations by descriptor number. Mount checks exercise `/proc/self/fd` in a
worker thread as well as the group leader.

Xattr operations capture the path or actual input description once, then perform
logical ACL/capability or native backing work under the selected worker context.
Permission checks, copy-up and readonly attachments share the filesystem engine.
Input values are captured bytes, never borrowed guest addresses or guest memfds.
Mapped and RPC responses carry size queries or up to 64 KiB of value bytes;
output rejection is explicit and does not replay a mutation. Large mapped values
live outside the header union. Pool reuse clears only the declared header prefix,
so ordinary calls neither allocate nor overwrite a 64 KiB payload. FD-style
calls retain actual descriptions rather than accepting an O_PATH origin as proof
of readable descriptor flags. Protected capture keeps the channel admission and
application-filter limits described above.
Fork-inherited shared pages are not security isolation; successful mapped delivery
does not establish general application FD-transfer support.

## Proc Custody

`--case proc-custody` checks snapshots and umask queries with CLONE_FILES callers
killed by filters at the former task-local write/read points. Ordinary and
nondumpable callers leave the peer's exact FD set unchanged. Large-argv checks
cover chunking, rejected copying, O_PATH, CLOEXEC, read-only data, native seek and
zero/one available result slots with RPC socket calls forbidden. O_PATH keeps
the native proc inode and zero size, while readable snapshots retain all image
bytes. Protected full-table fstat/fstatat/statx checks include path/FD identity
and the translated mount ID. The native
nondumpable auxv control reports EACCES on this device; the positive guest check
remains required. Worker checks additionally cancel construction at every stage
and require its own descriptor count to return to baseline.
Resource checks also cover mount-table O_PATH flags and fstat with one result
slot, path metadata/statfs/mknod/chdir/truncate with no free slots, admitted protected watch queues,
and proc reopening of unlinked files, directories and native devices with one
slot. The native/guest controls retain exact identities and independent offsets.
Foreign executable readlink is also checked with ordinary/protected callers,
full FD tables, read/RPC kill filters, truncated output, zero length and EFAULT.
Current executable metadata, xattrs and watches run with full FD tables for both
ordinary and protected callers. Worker checks cover unlinked object identity,
mutation of request bytes after admission, queued cancellation and exact FD balance;
native RPC rejects malformed identities and stateful operations without an OFD.
Imported cwd checks use SCM_RIGHTS directory inputs with full ordinary/protected
tables and repeated relative metadata after fchdir. Worker checks require queued
FS/file-table operations to remain blocked through acknowledgement, and cover
wrong descriptors, success, native/pre-entry errors and cancellation on either
side of entry with exact provider FD balance.
Foreign cmdline/auxv/exe opens check repeated delivery with exactly zero/one free
FD, protected callers, caller-selected CLOEXEC, content and seek. Native
`fd_install` checks exercise the production publication primitive, queued
cancellation, receiver death before admission and exact origin ordering.
Xattr checks cover ordinary/protected callers with full FD tables, path and FD
forms, binary values, maximum-sized logical ACLs, nofollow, EFAULT and ERANGE.
Path-only checks forbid RPC socket calls; protected FD forms first admit the
shared export channel. Native RPC independently checks maximum ACL payloads,
rejected output and subsequent successful reads. Mount-engine checks retain
readonly rejection and unchanged worker FD counts.

Initial-cwd and proc-alias cases also run nondumpable, checking known origin at
image entry/capture, independent reopened offsets and virtual metadata. A shared
file-table case races dup3 replacement against ordinary/O_PATH proc directory
suffix opens. The native/guest kernel-contract `proc-owners` case independently
unshares filesystem and descriptor state in a pthread, checking leader versus
current-thread aliases and invalid proc path errors against the kernel.

## Native Handoff Controls

`fd_handoff` exercises native kernel publication under the selected executor,
not Shroot emulation. A real CLONE_FILES peer replaces a slot with another open
description of the same inode before a split notification reply and before an
SCM_RIGHTS receiver acknowledgement. The atomic-delivery case permits receiver
replacement before later metadata work. Signal cancellation checks the installed
FD left by split delivery and the untouched destination after cancelled atomic
delivery. O_PATH rejection and descriptor cleanup are checked separately.
Positive cases feed a competing dup3 notification into the file-table queue
for atomic ADDFD_SEND and SCM_RIGHTS delivery. They verify physical descriptor
identity before allowing replacement and publish the exact source/replacement
origins in order. The fixture's acknowledgement follows the actual dup3 result;
production publication follows its syscall-exit event, not notification SEND
success. These cases do not establish complete mutation interception in Shroot.
Receipt cases let a real CLONE_FILES receiver exit immediately after recvmsg,
without an ACK or sending FD numbers. The survivor observes the terminal event
and publishes from retained kernel output: no delivery, one/two FDs and truncated
delivery. Kernel flags, shared file position, origins and leaks are checked.
Vector handoffs transfer up to 253 native FDs after sender exit, with two mount
origins for one inode, unannotated FDs, shared offsets, repeated MSG_PEEK and
truncated consumption. Pipe EOF confirms that retained origin vectors do not
retain writable descriptions. These controlled fixtures supply the source/message
association; they do not implement arbitrary guest-message authentication or
publication interlocks for blocking application receives.
The same retained receipt also covers vectors after a CLONE_FILES receiver exits
without acknowledgement: repeated peeks, truncated delivery, 253 installed FDs
and a subsequent empty receive. A queued file-table mutation cannot start before
publication from the terminal receiver's kernel output.
Receiver-local RLIMIT_NOFILE checks allow 0, 1, 2, 3, 4, 252, 253 or 254 free
slots, with and without control-buffer truncation. They check the exact installed
prefix after receiver exit, unknown origins, peek reuse and pipe EOF. The shared
table survives; the receiver's limit does not change the parent or shell service.
Socket acknowledgements establish ordering without sleep or scheduler races;
alarms and parent-death termination bound fixture failure. `test_resources.py`
runs this alongside the model and intercepted guest checks; `--case fd-handoff`
selects only these native publication boundaries.

## Optional Parcel Experiments

The separate `--case fd-parcel` experiment queues the original rights inside a
private Unix socket, then transfers its endpoint in an outer message. It checks
253 rights, including O_PATH, over stream/datagram/seqpacket transports, sender
exit, stable SO_COOKIE, repeated inner peeks, retained outer peeks, peek discard,
truncation, partial/complete ordinary reads, queue closure and failed sends.
The remaining endpoint observes POLLHUP after the last parcel reference
disappears; pipe EOF checks release of enclosed writable descriptions. Native
self-references and two-socket parcel cycles both reach EOF in the tested kernel's
Unix GC. An expired GC observation is reported as unobserved, not collected.

Transport variants have different resource requirements, measured separately
from native controls under actual UID 2000:

| Candidate | Verified boundary |
| --- | --- |
| Direct parcel unwrap | The endpoint consumes one receiving slot; native SCM_RIGHTS delivers one more original FD under the same pressure. |
| Preconnected broker relay | Exporting the endpoint frees the receiver slot, but that reverse send can fail with ETOOMANYREFS where native receive succeeds. |
| Receiver-local reserved slot | Moving the endpoint with dup3 avoids reverse export and preserves the available payload prefix, provided an owned slot already exists below the receiver's FD limit. A retained slot above a lowered limit is rejected with EBADF. |
| pidfd_getfd pull | Dumpable providers supply regular, O_PATH and pipe FDs even to a nondumpable receiver. Protected providers return EPERM on the tested device. |
| io_uring registered-file candidate | io_uring_setup returns EACCES on the tested shell executor. The isolated probe also reports SIGSYS denial; this API is not a runtime dependency. |

The local-slot fixture receives 253 rights with 0, 1, 2, 3, 4, 252, 253 or 254
free slots. It checks distinct origins, O_PATH, protected receivers, outer peeks,
wrong-cookie rejection, pipe EOF and terminal loss before/after dup3, temporary
close and payload receive. Kernel-written outer and payload receipts survive
receiver exit; cleanup distinguishes the old endpoint number from its reuse by
an installed application FD. A queued table mutation remains blocked until
publication and private cleanup finish. The fixture preallocates its slot and
receipts: slot ownership across guest close_range, exec, table sharing and lowered
limits is not implemented by the adapter.

Admitted relay deliveries cover the same payload prefix, protected receivers,
terminal publication, cookie rejection and EOF. With 253 queued rights and 1-4
free receiver slots, the reverse export hits the native in-flight quota. The
[Linux Unix-socket check](https://github.com/torvalds/linux/blob/v6.12/net/unix/af_unix.c#L1731-L1747)
compares the real UID's in-flight rights against the sending task's RLIMIT_NOFILE.
Source-side controls also expose the extra send: with limits 64 and 252, direct
native sending of 253 rights succeeds, while enclosing those rights succeeds but
exporting the endpoint fails with ETOOMANYREFS. Limits 253 and 254 admit both on
this device. Failed-export cleanup must release the enclosed pipe writer. UID-wide
background traffic can invalidate a control; such a case is reported as
unobserved, not transport equivalence. Neither raising limits nor changing the
sender identity is used to bypass these comparisons.

These are native fixtures, not an application protocol. The fixture supplies
peer/cookie binding; native peers, cross-launch origin ownership, lazy admission
and full quota equivalence remain unestablished. pidfd_getfd additionally needs
a pidfd and terminal publication beyond its scalar syscall result. No candidate
changes application bytes, socket queues or limits in the shipped adapter.
The experiment is outside the default regression suite, and its report lists
limitations separately from successful assertions.

## Message-Origin Association Candidate

`test_resources.py --case fd-messages` tests a kernel-carried origin association,
separately from the application adapter. The native `fd_messages` owner captures an
ordered origin vector under the sender's table turn and queues the actual rights
inside a private seqpacket socket. Its kernel cookie selects the retained vector;
the outer message's bytes, delivery order and reused source FD numbers do not.
Lookup also checks the opposite side of the sender's connection key from the
production IPC authority. Sender and receiver resolve that key from their own
captured kernel sockets, not a fixture-supplied destination FD. An unknown
carrier, the sending side or a different connection cannot claim those origins.

A routed vector also retains its sending binding and registered custodian ID.
Its private header binds that ID to the actual cookie, connection, vector length
and fresh nonce. Reading the header installs no rights and consumes no packet;
the ID selects a verified private rendezvous, not the socket creator's PID.
The source-side claim verifies the actual carrier, destination store/binding and
complete issued header before pinning its local vector. The binding shares its
epoch/store lease without retaining SQLite or a socket. It survives registry
retirement until the last operation releases the vector. See the
[message routing contract](ipc-credentials.md#message-custodian-routing) for
validation, ownership and independent-process coverage.

The candidate's metadata lifetime uses a pipe writer in a second packet of the
private socket. The first packet can still hold 253 application rights; peeking
it installs no lifetime writer in the receiver. The authority retains only the
pipe reader and O_PATH origins, not writable payload descriptions or the carrier.
EOF is an event-driven retirement boundary after the last queued/duplicated
writer disappears. Each operation pins the immutable vector independently of
the registry: retirement removes lookup visibility and closes the monitor, but
the last operation releases its origins only after completion or worker join.
Reference changes belong to the supervisor loop; workers only borrow the vector.

The registry allocates no descriptors until first admission, then exposes one
epoll descriptor to its owner's event loop. Dispatch handles at most 16 ready
monitors per call without a periodic scan. It checks actual pipe EOF, not merely
the event bit or a raw record pointer. Stale readiness cannot retire a replacement
record. Unexpected monitor data is EPROTO and retains custody, not proof of
expiry. Failed admission leaves the pipe reader with its caller; successful
admission transfers it once. Explicit withdrawal requires proof that the message
did not escape. Ambiguous sends retain their record until EOF or whole-authority
teardown. Capture uses one allocation sized to the actual vector; lookup, pins
and retirement allocate and duplicate no descriptors.

A socket-only HUP is insufficient: native shutdown of both directions reports
HUP while queued rights remain readable. The fixture checks this explicitly.
The queued pipe writer instead survives RD/WR/RDWR shutdown, partial consumption
and carrier aliases, then reaches EOF on final release. Native Unix GC cases
check data-pipe EOF and metadata-pipe EOF for cycles over all three outer socket
types; timeout reports unobserved collection, not successful cleanup.

The comparisons cover reordered messages, two opens of one inode from different
mount views, unknown origins, sender exit, source-slot replacement, repeated peek,
control truncation, raw-read discard and exact descriptor balance. Publication
uses the existing `fd_delivery` and destination-table ordering, not another
annotation model. The fixture uses the runtime registry, not a second lifetime
implementation. The private `ipc_peer` agreement component separately checks
version, directional connection and features without touching application queues;
its binding retains the live IPC epoch beyond SQLite closure. Its contract and
independent-process checks are in [Unix IPC credentials](ipc-credentials.md#private-peer-agreement).
`ipc_peer_service` admits captured socket/control pairs asynchronously through
the existing filesystem worker and returns an owned peer on the supervisor's
completion dispatch. Cancellation and worker join retain pending inputs until
their last user finishes; there is no additional poll loop or database owner.
`ipc_origins` prepares and receives self-contained authenticated capsules on the
filesystem worker. Captured socket/data inputs and annotated outputs share the
same cancellation and join ownership. Independent-process checks cover custody
after source exit. Ancillary and addressed sends use the source capture and
preparation pipeline; the receive adapter expands selected ORIGINS_V1 messages
through the shared buffered worker and annotated publication contract.
Connection association
reuses `ipc_credentials`, including its store boundary and live-owner lease;
it is not a declaration that a native peer can consume a private carrier.
The authority's separate creation-time ancillary-format declaration is captured
in `md_ipc_binding`. It is immutable across aliases and owner changes; accept
inherits the selected format instead of changing an existing queue's meaning.
Carrier operations require both an `ORIGINS_V1` binding and a verified private
peer agreement. A native connection cannot be upgraded by control negotiation.
Production owners declare `NATIVE` until application adapters own that format.

`test_task_resources` checks allocation/EMFILE rollback, wrong connection sides,
duplicate cookies, stale events, malformed monitors, bounded batch retirement
and pinned vectors after owner teardown. A real worker inspects retained origins
after the source table, mount owner and registry have been released. Exact FD
balance is checked after join. These ownership tests belong to the regular model
suite, independently of the opt-in carrier experiment.

The same fixture checks the production connection contract through independent
worker processes, send-before-accept and accept-before-connect-publication,
sender exit, socket aliases, native endpoints, failed connect, listener revision
changes and last-owner retirement. Credential tokens in both directions validate
only on the opposite side; repeated inspection does not consume them. Workers
sharing a store agree on the link, but not on in-memory mount-anchor pointers.
Queued reuse of a user-bound name with the same destination and kernel PID is
explicitly ambiguous, not a latest-record lookup. Distinct destinations remain
distinguishable; a native listener replacement cannot confirm a captured peer.

This adds a real queued FD and send for the lifetime marker. With 253 payload
rights, native direct sending succeeds at tested source limits 64/252/253 where
the candidate's marker or outer export fails with ETOOMANYREFS. Limits 254/255
admit the candidate on this device. Failure cleanup releases all queued writers;
no quota or identity is changed. Receiver admission and reverse-export costs of
the other parcel experiments remain relevant. These differences block treating
the candidate as transparent native SCM_RIGHTS support.

An alternative exact `NETLINK_SOCK_DIAG` lookup checks inode and kernel cookie
without a socket-table dump, but gets EACCES under the tested UID 2000. This is
recorded as unavailable, not absence of the queried socket. The pipe-lifetime
candidate needs neither that API nor periodic diagnostics.

## Root And Filesystem Checks

`root_guest` runs the same checks with inode-backed and attached-native roots:
nested roots, absolute symlinks, root-clamped traversal,
unchanged inherited descriptors, failed-change rollback, CLONE_FS with and
without CLONE_VM, unshare and exec in ordinary/nondumpable tasks. Model checks
add root rename, enumeration, authenticated task snapshots, mount-table filtering,
nested readonly/writable attachments and retained covered-directory descriptors.
They also alternate two task mount views on one worker, check independent readonly
policy and mount-table output, retain snapshots past registry teardown, and check
coherent identity/root/mount publication under concurrent readers and writers.
Request-context checks retain authentication and roots across registry replacement
and teardown, reject actor/peer substitution, preserve captured credentials across
real/effective access checks, and commit chroot only to the captured filesystem
owner. `test_rpc` replaces the authenticated task record immediately before
dispatch: the first creation retains its original owner, the next sees the new
owner, and an unknown actor cannot create a file. Both retained mount views keep
their original readonly policy after task and registry teardown.
`test_mount_copy` checks distinct IDs, explicit native/inode root rebasing,
independent readonly policy, moved covered directories and source-view teardown.
RLIMIT_NOFILE exhaustion at every descriptor-acquisition step checks rollback
without leaks or changed outputs. A populated source cache does not grow copy
cost; pipe EOF and flock/OFD-lock release remain observable with both views alive.
`test_fs_anchor` checks old origins against copied trees with different policy,
absolute/relative links, nested parent traversal, openat2 constraints, cross-view
link/rename rejection and root restoration. It also checks inode/native mount IDs,
reopen, FD-number reuse with another inode, rename, source-view teardown, completed
FIFO identity/EOF, flock/OFD-lock release and EMFILE capture cleanup. These are
engine checks, not guest namespace-creation tests.
Message-origin checks close every source connection and retain only the message
pins, with no additional descriptors for capture. Independent maintenance probes
must remain busy for the store and its lower layer. A sequential reopened worker
checks readonly attachments, rename, copy-up/unlink and foreign/replaced-store
rejection; releasing the message permits maintenance and restores the FD count.
`test_mount_alias` constructs a candidate view with one source at writable and
readonly targets. It checks both opening orders, distinct stable object IDs,
image identity, retained opens after unlink, ambiguous unannotated descriptors,
root capture and copied-root policy. It also checks invalid origins and descriptor
cleanup. This internal fixture does not enable duplicated-source launch admission.
`test_root_userspace.py` exercises stock Debian/Alpine chroot with each
distribution's dynamic loader, shell and nested exec in disposable inode-backed
and attached-native directories.

`test_resources.py BUILD --compare-installed` stages a disposable store under
the selected shell UID and compares the same static metadata/spawn fixtures
against the installed runtime. Uninstrumented samples alternate execution order;
opt-in counters are separate runs. Reports preserve both timings and operation
counts without treating scheduler variation as a throughput guarantee.
