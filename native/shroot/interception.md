# Guest Syscall Interception

One native supervisor owns each explicitly launched guest tree. It uses seccomp
TRACE stops for adaptation and USER_NOTIF for descriptor metadata, selected path
operations, selected watch reads and the image-entry protocol. It never attaches to Android tasks.
The guest retains the selected real UID; neither the supervisor nor the filesystem
service changes SELinux policy or acquires root.

- [Execution And State](#execution-and-state)
- [Guest Kernel Interfaces](#guest-kernel-interfaces)
- [Protected Metadata](#protected-metadata)
- [Image Admission And Logical Credentials](#image-admission-and-logical-credentials)
- [Lifetimes And Availability](#lifetimes-and-availability)

## Execution And State

### Bootstrap And Entry

The bootstrap publishes a private, size-checked ABI containing its gate addresses
at an explicit readiness stop. The supervisor reads it before resuming the guest;
exec republishes the same immutable bundle's ABI. No generated address header,
Java class name or environment-variable switch selects an implementation.
The bootstrap also publishes the internal [cooperative entry](cooperative-entry.md)
used by lazy SVC rewriting in unmodified programs. It reuses the same worker and
execution context. Unsupported sites and calls without admitted application
policy retain Base admission. Compatible filtered calls can use the cooperative
entry with their original number, arguments and instruction pointer.
The task-affine C executor and operation retirement are separate from register
encoding and resumption. Admitted calls enter this executor through the shared
channel; its internal buffer, descriptor and IPC controls retain their existing
supervisor owners. Native actions have an acknowledged completion, and a required
kernel continuation retains the pending operation rather than replaying it;
see [execution and transport](cooperative-entry.md#execution-and-transport).
The stopped-task dispatch gate builds the same `md_guest_execution` record as
resident entry, on its already owned private stack. Its machine boundary carries
policy in x9, retained typed-descriptor kind in w10 and prepared input in x11;
the C executor receives only a pointer to the record. Descriptor classification
belongs to the supervisor's ordered OFD capture; the adapter does not ask for
the same classification again or cache guest FD numbers. It does not rely on
changing x7 at an ARM64 syscall stop: the
[kernel restores that register](https://github.com/torvalds/linux/blob/v5.15/arch/arm64/kernel/ptrace.c#L1694-L1726)
after the stop. Notification cancellation, ordinary
seccomp entry and debugger syscall stepping share this entry contract.
For recvmsg, poll and managed exec adapters, the record can carry an already
leased input-buffer address. Missing mappings,
pool pressure and unsupported calls retain ordinary TAKE admission; this is not
a per-thread buffer cache. Completion releases even an unclaimed lease after
early argument failure. Exec keeps that lease through preparation until kernel
exec replaces the address space, or dispatch returns an error; neither TAKE nor
RELEASE requires a separate checkpoint when the lease was supplied at entry.
Before task-affine admission, a signal-delivery stop retires an unexecuted
notification if it wins the race with the requested ptrace interruption.
Blocking calls retain kernel EINTR and SA_RESTART semantics. For nonblocking
metadata (`fstat`, `newfstatat`, `readlinkat`), a notification wait must not add
spurious EINTR to the underlying operation. A signal can cancel that wait even
before NOTIF_RECV. The cold recovery path checks the stopped instruction,
reenters the original SVC under the existing signal shield, and observes its
actual exit or task-affine completion. Application filters still run at the
original PC with all arguments intact. The saved signal, siginfo, registers and
mask are restored at an owned checkpoint, never in a handler's replacement context.
No additional stop, ticket or ioctl is added to uninterrupted metadata reads.
Directory cursors and mutating operations cannot use this replay path.

### Image Preparation

`command_image` owns ELF/shebang inspection and returns retained program/loader
descriptors plus a pointer-free recipe. `command` assembles argv in its caller's
address space. Namespace reexec performs all image preparation on the filesystem
worker under the captured identity, root, cwd and mount view. Descriptor exec
captures the actual input OFD, including protected callers; no private image FD
is installed into the old guest table during preparation. The supervisor retains
both completed images while the guest constructs argv and enters real kernel exec.
The new bootstrap takes each lease with ADDFD_SEND
after kernel file-table separation, validates the load segments and maps them
without reopening filenames or reparsing the command. Failed exec or task loss
releases the leases; nonleader exec transfers them to the resulting task identity.
Program publication also prepares logical exec credentials from that same retained
image. The final entry checkpoint registers actual mappings from those retained
backings, commits credentials, updates both live and proc-snapshot auxv, and
enters the loader. Secure entry publishes logical dumpability at the same
checkpoint. Neither entry needs a separate image-admission or entry notification;
atomic descriptor publication, noexec, set-ID and application-filter checks remain.
Queued cancellation and task death also retire in-progress worker resources,
without leaving temporary image FDs in a surviving CLONE_FILES peer.
The native-root laboratory path retains its inherited-image handoff.
The opened program's object ID and canonical dentry path are captured
in the opening transaction and passed through the bootstrap handoff; descriptor
exec obtains identity from the retained FD. Bootstrap does not issue a second
object lookup. Namespace authority still comes from the captured image and credential
owners, not bootstrap arguments.
Same-thread descriptor aliases and the current executable are captured before
worker admission. Unresolved proc magic links are rejected rather than resolved
against a supervisor descriptor or the native bootstrap image.

### Native IO And Selectors

Adapted socket effects use a distinct native wait gate. It restores the caller's
signal mask only around the kernel operation and shields the adapter again at
the syscall exit, before address translation or accepted-FD publication resumes.
Interrupted calls unwind through the common original-syscall restart boundary;
kernel `EINTR`, receive timeouts and `SA_RESTART` remain authoritative. Internal
IPC does not use this gate. Unadapted payload IO keeps its original native path.
`MSG_DONTWAIT` operations also retain the short native path: their argument
flags prove that no socket wait is needed, without querying descriptor state.
`accept4`'s `SOCK_NONBLOCK` does not prove that its listening socket is nonblocking.
An already admitted socket wait remains in the kernel across TSYNC. Its owned
exit stop holds completion until policy publication; the pending call is not
cancelled and re-admitted against the new filter.

Supervisor-owned blocking IO uses `ipc_io_job` for completion and cancellation.
A readiness or quiescence wake only requests resampling: collection leaves an
unfinished job pending and never joins its blocking syscall. Atomic completion
publication precedes retirement; cancellation interrupts the operation before
joining. Both paths retain buffers and received-FD custody until the worker exits.

Data IO, common memory operations, futexes, clocks and signals pass directly to
the kernel. `MD_DOMAIN_KERNEL_CALLS` and `MD_DOMAIN_KERNEL_ARGUMENTS` describe
additional calls requiring no adaptation or observation in any guest domain.
The domain checker and seccomp filter consume these same definitions. Argument
predicates compare the full 64-bit value; other commands retain the dispatcher.
`MD_DOMAIN_FD_CALLS` and `MD_DOMAIN_FD_ARGUMENTS` separately select short native
descriptor mutations whose origins need ordered observation. Their fcntl command
is normalized to the kernel's 32-bit argument. Close, dup/dup3, close_range and
F_SETFD/F_DUPFD/F_DUPFD_CLOEXEC complete in the kernel; the supervisor publishes
metadata from the shared native-effect receipt or Base syscall-exit event.
Ordered memory effects use the same completion owner for mapping provenance
and SysV detachments, with refreshed fast-path exclusions before guest return.
These capabilities remain available after
the admitted proc-root restriction, with application filters still in force.
An ordinary unfiltered close with an immediately available table turn obtains
its low-int FD directly from a tagged seccomp event cookie. It needs no entry
register snapshot or rewrite; other calls retain their ordinary event decoding.
The turn still spans the native exit:
shared-table reuse, EBADF, late IO errors and terminal abandonment retain their
existing semantics. Numbers above 32767, loans, contention and debugger/filter
paths use full capture. This encoding is not an FD limit or a kernel permission.
Application filters still evaluate original-site kernel-fast-path calls. Admitted
lazy entry evaluates the original call in the shared interpreter instead.
Logical credentials,
root changes, filter installation and dumpability changes retain their observers.
Watch descriptors install bounded FD-class read selectors lazily; reads in
unselected classes stay native until an application installs its own filter.
Before that irreversible boundary, a single group-wide selector covers future
typed descriptors without later seccomp calls. The application filter itself
retains its application-site denials. Kernel-object identity distinguishes watch
readers from ordinary descriptors sharing a class. Selected reads retain their
original signal context while the worker waits for records; protected readers
use task-affine copying and a native readiness wait. Filter installation ownership is separate from
address-space and descriptor ownership; see [filesystem watches](watches.md).
When a `vfork` child shares the file table, its kernel-suspended parent records
bounded missing selector classes rather than blocking the child's publication.
The parent installs those selectors at its clone-exit boundary, before returning
to application code. Other runnable table holders retain ordinary admission.
PID-sensitive selectors use the same task-affine filter executor and are installed
once per group when PID namespace operations require them. Joining an unrelated
namespace does not add a PID filter. Service waits and filter installation use
owned task gates; a guest's direct forbidden syscall still receives its selected
seccomp denial.
Preflight releases its memory turn before replaying the application syscall.
A prepared replay at a published lazy-SVC site uses its native alias, retaining
the original policy identity and continuation without recursively entering the
payload. Clone preparation and metadata replay use the same registry lookup.
Signals may run at that boundary, including handlers that install another
filter; the next entry reacquires ownership and captures the current arguments.
With the all-FD selector active, ordinary regular-file and anonymous-pipe IO use
one classifier for Base and resident entry. Base reads the real number and
arguments from `PTRACE_GET_SYSCALL_INFO`, not the filter cookie; resident entry
supplies its admitted logical call. Admission requires a running file-table
turn, sole task ownership and explicit native-data provenance. Unknown,
provider-backed, provisional and uncertain FDs, shared tables and debugger
execution retain their owners. The table turn ends before potentially blocking
IO. Base resumes the untouched kernel call; under an application filter its
blocking continuation retains an exit observer so TSYNC does not interrupt and
re-admit the operation under a newer policy. Resident entry transfers it to the
terminal native journal without another ptrace entry/exit. Unselected native IO
and unfiltered Base IO are unchanged.
Poll and pselect can similarly resume at their original syscall when an ordered
table snapshot contains no potential readiness providers. A maintained provider
count avoids scanning the FD table or copying the vector for this decision.
Mixed tables retain typed classification. The kernel validates pointers, limits,
timeouts and masks; no table turn spans the blocking wait. Adapted poll copies
its first vector chunk with BEGIN and uses supervisor monotonic timing, without
injecting guest clock syscalls or querying foreign process limits on every poll.
In Base, when mixed-table classification finds only native descriptors in the selected
vector, it retires the input buffer and resumes the original wait directly;
there is no output to copy or intermediate adapter acknowledgement.
Short ppoll vectors can be classified directly from an ordered snapshot, without
entering the adapter. Zero-time native epoll waits use the same provider predicate
as the full path; queues preparing a virtual provider cannot take this shortcut.
Blocking epoll retains a wait owner from entry so that a first virtual interest
can wake an already-sleeping caller without losing its timeout or signal mask.
Unmasked native epoll_pwait and untimed epoll_pwait2 waits retain their frame
in the supervisor and release the shared transport slot before sleeping. Their
native arguments contain only application pointers or scalar values. Native
completion returns directly; admission of a virtual provider reacquires a slot
through the existing event-driven retry queue, retaining the deadline and exact
continuation. The adapter receives the current slot address, never a released
one. Masked waits, timed epoll_pwait2 and virtual-output waits retain their mapped
frames while the kernel or adapter needs them. Pool size is not a process-count
limit for the independent native waits.
An internal quiescence interrupt before the wait's SVC resumes the same entry
under its signal shield; it is not a syscall result. Its service wake is consumed
at that boundary, independently of subsequent application signals or FD eviction.
Resident unmasked epoll/poll/select waits execute journaled native effects on
their existing channel. Only real signals, cold descriptor admission and loan
changes need a stopped context; ordinary wait entry and completion do not.
Virtual epoll subscription changes reuse the shared owner once its transport is
admitted. Repeated MOD/DEL/ADD do not promote merely to update a virtual interest.
Ignored signal stops retain the original deadline and resample, in both Base and
resident execution. Base keeps interrupted ownership through the delivery stop,
so a syscall-exit stop cannot decide EINTR before the signal disposition is known.
After an interrupted caller-context wait, its temporary signal mask remains
kernel-owned through delivery. That task cannot lend its continuation to FD
retirement or publish retained signal carriers until its next syscall entry.
This one-entry observation preserves the signal frame without single-stepping,
stopping peers or adding observation to successful waits and ordinary data IO.
An unmasked native epoll_pwait can first probe at timeout zero in the caller.
An admitted application filter uses the shared native-effect journal, without
ptrace entry/exit for the probe; its policy is evaluated against the original
arguments before reducing the probe timeout. An empty result proceeds to the
owned wait with its original deadline. Debugger stops, unadmitted policies and
masked waits retain full kernel admission. Pure native waits publish output directly
through the kernel, including its pointer validation and event consumption.
Failed argument reads and unknown authorities retain normal adaptation.
Adapted calls run on guarded scratch stacks leased by `interception_memory`, the
address-space owner. CLONE_VM shares it; fork copies private scratch-stack mapping
metadata without sharing stack leases; exec replaces it. Completed calls return
their lease. Thread records are reused
without a fixed thread-count ceiling. Stack leases and ordering records are reused.
Origin-bearing opens and RPC exchanges allocate their own retained state; ordinary
data IO does not allocate runtime bookkeeping.

### Stop Ownership And Shared Buffers

Cold stop-owning transactions share one admission check: entry revocation,
private-memory repair, task-affine injection and guarded mapping cannot borrow
each other's stopped tasks. A mapping's own temporary-FD retirement and repair's
private executor preparation reuse their existing memory turn and writer barrier;
they do not acquire competing stop owners. A competing `mprotect`
exit is retained by the active owner, including one from an unrelated address
space, and replayed with its original status and accounting after release.
This defers that repair, not all work in the unrelated process. Ordinary events
remain runnable. Logical dumpability changes require no stopped-task barrier.
Broker-held signal-delivery, group and birth stops are not native-injection
boundaries. Their event owner publishes them before a caller can borrow a later
syscall or synthetic interrupt stop: restoring registers cannot recreate the
kernel's original signal-delivery context.
Seccomp installation revokes entry
and drains occupied private frames without interrupting native waits. New public
adapter admissions wait until the installing task completes its kernel call;
already started adapters continue to completion. This applies to Base as well
as lazy SVC. Fully admitted native poll/select and broker-owned inotify reads
can retain their wait ownership through filter installation; preparatory
MSG_PEEK and private-memory transitions still drain. The revoked-slot observation has a bounded 1 ms retry only during
that transition, because a slot's userspace release need not emit a kernel
event. No slot observation or timer is added to ordinary syscall execution.
Kernel events without debugger accounting explicitly carry zero `rusage`.
An internal FD-admission notification may wait with application signals shielded.
After requesting a real quiescence interrupt, the coordinator answers an
unpublished internal token with a reserved retry value outside Linux's errno
range. The common admission gate retries only that token after the kernel stop;
ordinary `EINTR` and application operations are not replayed. Atomic ADDFD_SEND
invalidates an already published token, so a competing barrier cannot duplicate
its FD. Both queued and newly arriving notifications follow this protocol.
Epoll capture/loan admission uses the original wait syscall and all six original
arguments at the notification gate. The supervisor validates that identity and
the live capture/loan owner; application filters still decide the call. This
does not require an unrelated `gettid` permission or change ordinary data IO.
Internal syscall-entry/exit stops are retained before checkpoint continuation;
replayed and newly collected events use the same checkpoint driver. A barrier
therefore cannot lose a stop to an internal syscall replay loop.
An undecoded stop parks broker admission without completing its native journal.
Only a normalized stop may recover that result: an entry PC is also a post-SVC
address, but its registers still contain input arguments. Barrier release replays
the retained event through decoding before handing it to operation retirement.
A barrier release returns debugger-owned tasks through normal continuation
admission: a shared kernel interrupt must publish the debugger's pending stop
before resuming guest execution. Untraced releases retain direct continuation.
The checkpoint owner records each actual successful kernel resume, including
internally selected SYSEMU and intermediate syscall replay, and clears the
coordinator's stopped state. Barrier release preserves that owner's retained
stage; it must not reset decoding by treating an unchanged continuation as a
new explicit syscall-step request. Without a retained stage, the exact saved
resume mode applies.
When a native task call borrows such a stop, the checkpoint owner suspends its
decoder until the exact boundary has been restored. SYSEMU entries retain their
kernel skip state independently of register edits and stop-decoder ownership,
including explicit caller-requested SYSEMU. Injection drains that skipped exit before executing the
borrowed call, then reconstructs an emulated entry without replaying guest IO.
Ordinary data IO retains its native path.
Parked signal waits release scratch, memory and file-table turns and can lend
their exact task boundary to protected proc inspection. Their existing decoder
suspension remains owned by the wait. A service interrupt rechecks the wait with
its original deadline; completed signal consumption and genuine application
interruption retain their ordinary results.
Deferred events retain their captured accounting across debugger attachment
changes; replay neither queries it again nor inherits another event's values.
For more than four members, kernel collection checks one round-robin TID on two
of every three discovery passes, then prefers fresh SIGCHLD hints and up to four
recently stopped TIDs (including alternating caller/helper checkpoints). These
queries share a budget of 64, or the population above 64 members, capped at 1024.
This amortizes both idle probes and the kernel's full child-list scan without
removing their discovery bounds. General collection resets the probe cadence.
A single live tracee keeps the hint/MRU fast path with an eight-query budget;
trees of two to four members collect up to four statuses directly.
The bounded round-robin is necessary:
`wait4(-1)` can repeatedly select hot children at the head of the kernel list,
even with frequent general waits, while a coalesced signal loses another TID.
Membership is updated at task admission/retirement, without a full task scan.
Private mapping/descriptor helpers and deferred FIFO-open children belong to
the same collection ring from confirmed birth through terminal reaping. They
do not acquire guest task identities or depend on the general scan for progress.
The signal source is refreshed when its hint queue is empty. A general wait
still runs at the budget boundary and is the only way to establish an empty
kernel queue; no timer or speculative sleep is added.
For larger task sets, general discovery collects at most 32 ready statuses
before resuming any of them. This prevents a hot child from immediately
reoccupying the kernel list head while the same batch is being collected.
The collector owns each status until delivery, including its original accounting;
membership changes and new signals cannot discard an already collected event.
Directed hints remain the first path: repeatedly draining the full child list
is expensive when a desktop has many sleeping threads.
Group exit can replace an already collected seccomp or syscall-entry stop with
a terminal stop even when ptrace still succeeds. An inconsistent protocol event
therefore checks the current stop's exact signal information before rejecting it.
A successful syscall-info read reporting NONE uses that same cold check;
successful ptrace access alone does not prove that a saved syscall stop survives.
A confirmed EXIT/EXEC replacement retires only the stale observation; its terminal
event keeps the normal dispatch path. Ordinary valid stops need no extra query.
Birth messages require a current-stop check before publishing any PID. If group
death replaces a collected FORK/VFORK/CLONE stop, `task_birth` recovers the child
from the completed native clone result at EXIT, not from the overwritten event
message (which now holds an exit code). The parent awaits its terminal event;
the child still receives its reserved resources. A newborn's EXIT can precede
that publication and is retained like its initial stop, preventing premature
reaping or resurrection of an already retired TID. These checks run only at birth,
not on ordinary syscall entry. Native controls cover all three birth kinds,
protected callers, nonzero exit codes and independent surviving children.
If EXIT overtakes the birth before collection, the outstanding clone reservation
is reconciled from its terminal result through the same publication owner. A
cancelled seccomp entry can retain its first argument instead of a result; that
value alone is never admitted as a child PID.
Fatal cleanup drains collected statuses before waiting in the kernel, so an
already owned EXIT stop cannot leave killed tracees blocked in ptrace forever.
Birth handling also recognizes a child's initial stop already owned by the
dispatcher before a barrier began. Its memory/files membership is not known
until the parent's clone/fork/vfork event is replayed. The barrier retains that
stop rather than waiting for a second one or resuming the child early.

Resident-entry client retirement is address-space scoped. Terminal observation
marks that address space dirty; a stopped live participant collects slots whose
exact TID/address-space owner has exited. The retained proc memory description
continues to refer to that mm across credential changes, not a reused PID.
Opaque client state is cleared before publishing its empty owner. A lost stop
leaves collection pending for another participant; ordinary resumes do no work
when the registry is clean. Forked registries collect inherited foreign owners.
Revoked registries do not collect unused slots: protected descendants may no
longer permit memory access, and their disabled entry cannot claim those slots.

Before changing mappings, memory repair yields if a stopped participant owns a
pending broker temporary-FD retirement. It releases its writer barrier and worker
pause request, retaining the original syscall-exit event. Task-affine cleanup can
then finish; replay revalidates the mapping and resumes repair, without executing
the guest syscall again. Once mapping mutation starts, this handoff is forbidden.

Candidate mapping, advice and replacement run in the address space's retained
private executor, shared with managed robust cleanup. The guest caller remains
stopped. A late caller exit/exec cancels the transaction but does not destroy its
executor: candidate cleanup and replacement rollback finish before the exact
terminal event and accounting are replayed. Completion never restores a dead
caller's registers or closes remembered guest descriptor numbers. The normal
dispatcher continues serving unrelated address spaces during helper waits and
bounded scan/copy steps. Supervisor-state tests cover terminal events at each
MAP/COPY/ADVICE/REPLACE boundary; native retained-actor and private-memory suites
cover executor lifetime and actual mapping operations in User and Shell.

Protected descriptor metadata uses the filesystem worker's captured-request
receipts, without a guest buffer or a second file-table turn. The request retains
the exported descriptor and task context through completion or cancellation.
Supervisor dispatch never synchronously queries its own filesystem worker:
an unrelated metadata request must not block a mapping owner's worker-pause
acknowledgement. Native loader validation remains a native `fstat`; guest metadata
uses the shared filesystem provider.

The same address-space owner retains shared adapter buffers. Namespace
bootstrap obtains a size-sealed memfd through ADDFD_SEND, maps it and closes the
setup FD before entering the guest image. Setup occurs once per fresh exec, not
per call; its file table is private after kernel exec. Fork retains the shared
allocator and inherited mapping, preventing overlapping leases between parent
and child. Exec creates an independent pool; outstanding workers retain old
storage until completion. The pool has 32 page-rounded response slots, allocated
only for namespace execution, not direct-rootfs launches.
Eight inline cooperative-channel slots and demand-mapped channel blocks cannot
consume response or control capacity. They are leased lazily and returned on
task retirement.
`adapter_buffer` owns the lease controls, while `adapter_frame` defines the
filesystem, SysV packet and shared-memory metadata views. Filesystem requests
clear only their bounded header, not the entire payload capacity. SysV calls
return their slot before blocking; their inotify watcher remains supervisor-owned.
Typed descriptor IO without an input payload acquires its buffer and begins the
operation in one request. Buffer exhaustion retains no OFD turn; ordinary pool
release resumes admission. Provider completion returns both the result and the
leased address, including interrupted or failed IO that still needs cleanup.
UTS queries, shared-memory status, SysV packet preparation and typed ioctl schemas use the same
combined acquisition principle: their first owner request leases and fills the
buffer. Pool exhaustion retains that request without starting the operation;
failure after acquisition still returns the lease for ordinary cleanup. UTS
mutations retain explicit input capture before their owner changes the namespace.
An ioctl schema can publish a bounded immutable scalar for the retained object
and command. The common adapter then copies it directly, with normal guest
policy and pointer checks, instead of requesting a second owner execution.
This is not a per-FD result cache. Mutable or revocable answers, asynchronous
commands, indirect outputs and descriptor transfers retain their typed owners.
TUN feature discovery uses this contract; input-device version queries do not,
because device revocation can change their result.
Typed descriptor IO combines its final copy acknowledgement, buffer release
and syscall completion in one checkpoint. Intermediate pages retain the OFD turn
and buffer; the last acknowledgement commits only confirmed bytes before releasing
the slot. Acknowledgement and cleanup errors retain their original precedence,
including partial reads. Both terminal IO and ordinary adapter returns use the
same completion owner for signal/debugger state, registers and scratch-stack
retirement. This transport is shared by proc files, FUSE and other virtual devices.
The same terminal FINISH control retires poll, epoll, typed ioctls and scalar socket receives, avoiding
a separate RELEASE followed by DONE. Only scalar TAKE may publish a checkpoint
directly at syscall entry; full-context returns keep their post-syscall boundary.
An inline recvmsg/recvmmsg error can complete at the receive owner when the adapter
explicitly reports that all its temporary storage is on the scratch stack. It
publishes no msghdr or payload changes. A batch also requires no committed message;
a late error retains publication of its successful prefix. Successful receives
and receives with heap-backed temporary storage retain their output/cleanup continuation.

### Filesystem Requests

All guest filesystem operations, including opens/reopens and root changes, use this
transport before admission when their inputs can be captured. The task-local
path leases, submits and releases a slot through the shared control channel when
available. Operations needing stopped kernel context retain signal-neutral
checkpoints and the same completion owner. No temporary guest FD, socket, mmap
or pool allocation is needed per call. Each admitted request owns one host
allocation, a pinned output slot and a
captured filesystem context. Requests copy both validated paths, scalar fields
and attributes before submission; getcwd needs no pathname allocation. Input
capture acquires the file-table turn before the CLONE_FS turn, retaining the
admitted resource owner and each cwd/explicit-dirfd anchor. Unannotated explicit
inputs use `task_descriptor` to duplicate the real kernel description. Socket,
ACL-payload, directory-seek and watch inputs always capture that description, even when a path
annotation exists: an O_PATH anchor has neither its offset nor queue state.
Close/reuse or registry
replacement after capture cannot retarget the request. Captured reads release
both short turns before worker execution. Root replacement retains the ordered
filesystem mutation through worker publication, then releases it before returning
to the guest. SQLite runs only in the worker.
Slot pressure, context admission and completion use event-driven waits, ended by
completion or launch cancellation, not the RPC connection timeout. Queued
cancellation releases the request immediately; submitted cancellation detaches
the caller while the worker retains the buffer. Kernel inspection denial selects
table-owned export, using the same captured context and ordered input admission.
Unknown cwd still requires task-local capture. Invalid descriptors retain EBADF. Mutations never fall
back to RPC after execution. A pure O_PATH lookup can explicitly select rights
transfer when no stable reopen source exists, before publishing any descriptor.
Transport framing is checked separately from
the common typed request and result contracts.

### Stopped-Task Capture

Open, unlink, rename, link and mkdir can capture their request directly while
the task is ptrace-stopped, using the same scalar decoder, buffer pool, context
admission and worker as the task-local path. Status-only calls need no guest
stack or signal-mask transition. Relative paths retain cwd/dirfd anchors under
the same ordered FS/file-table capture, not a pathname cache. Missing provenance
declines before worker execution and selects task-local capture.
Ordinary ADDFD results reuse the stopped syscall: only NT_ARM_SYSTEM_CALL changes
to fstat, whose existing notification route carries the pending result. Seccomp's
post-TRACE recheck delivers it without executing fstat or interpreting its
arguments as a metadata request. The broker first matches the exact task,
architecture, syscall, original PC and all six original arguments, including
ARM64's preserved orig_x0. The application's general registers, PC, stack and
flags remain untouched; no guest instruction or signal-mask change is needed.
The syscall-exit stop remains the completion boundary. A confirmed broker reply
supplies its result without another register read or restoration. Unconfirmed
delivery reads the stopped kernel result instead. This removes three ptrace
requests per successful direct open, not the exit stop or table-order barrier.
Cancelled admission retries only the retained FD through the signal-shielded
gate, never the completed mutation. Final CLOEXEC belongs to atomic ADDFD_SEND;
there is no follow-up fcntl. Results needing
selectors, caller-side opening or anchor transfer retain the full delivery adapter;
both paths release their lease at the terminal acknowledgement.
The broker's per-syscall notification counts include this fstat carrier;
`resultDeliveries` distinguishes completed-result delivery from filesystem work.
Creation captures the authenticated actor's umask in the worker instead of a
separate RPC. Bounded page-aware string reads replace byte-at-a-time capture.
Inaccessible task memory, application filters and debugging
retain task-local admission; an explicitly unexecuted host delegation may also
fall back. Executed mutations are never replayed. Creation/truncation opens
enter through TRACE directly, while O_PATH ignores creation flags and remains on
metadata notification admission. Ordinary data IO keeps its native path.

### FD Result Publication

For O_PATH results the worker transfers a retained source FD and origin to the
receiving task's supervisor record. The adapter reopens `/proc/PID/fd/N` through
the existing capture gate, preserving O_DIRECTORY and caller-selected CLOEXEC.
The file-table turn covers native installation and origin publication, not the
worker operation. Syscall completion or terminal observation publishes an
installed result without an adapter acknowledgement. Release or task loss closes
only the provider reference; no private service FD is left in the guest table.
When the retained descriptor carries O_NOFOLLOW, directories use `/proc/PID/fd/N/.`; inode-backed objects use their
immutable physical basename below a retained objects directory. The provider
checks device/inode identity, not a logical path that copy-up or rename can change.
An attached native-name file is promoted without copying to obtain that stable
source, preserving nofollow flags even when the application forbids socket RPC.
Store/source leases retain that name through delivery. The adapter passes the
actual flag to the kernel; fcntl is not emulated. If the provider already
normalized NOFOLLOW on a resolved non-link, delivery uses the retained proc FD
with matching internal flags, checked against supervisor-owned custody. This
avoids SCM_RIGHTS for devices whose metadata is accessible but whose native
socket receipt Android denies, including `/dev/full` under shell policy.
Other native non-directory and
anonymous nofollow sources return the retained result through SCM_RIGHTS on the
table-owned channel, without repeating the filesystem lookup.

Mapped creation, temporary-file, ACL/xattr, mount-table and prepared-image results
use atomic ADDFD_SEND for their data FD. The provider retains the original open
description until release. The destination table turn orders kernel delivery and
allocation-free origin publication; it does not cover worker execution or guest
data IO. A cancelled notification installs nothing, while a successful ioctl
publishes even if the receiving task dies immediately. Delivery consumes only the
result slot and does not add a guest service FD.

Before resuming that receiver, the supervisor registers its completed result in
the broker's indexed task record. The namespace worker admits the exact task,
adapter gate and result-purpose notification directly through `md_fd_install`;
no second worker-to-supervisor handoff or per-open record allocation is needed.
The supervisor has no parallel result-delivery record or retry path; an
unregistered result-purpose notification is rejected.
Only table-blocked admissions enter the readiness list. The broker pins the
table/origin and borrows the completed source until synchronous retirement,
which precedes provider release on acknowledgement, cancellation and exec.
Interrupted admissions retry delivery of that same result, never the filesystem
operation. Nondumpable and application-filtered callers use the same contract;
O_PATH/SCM_RIGHTS and task-affine results retain their respective transport.
For a completed open observed at a signal-delivery stop, signal custody transfers
to the retained queue only after buffer/result admission succeeds. If admission
fails, the original kernel stop remains the sole delivery owner, including for
queued realtime signals.

### Typed Provider Reads

Providers may explicitly mark asynchronous internal preparation as repeatable
after signal delivery, including without SA_RESTART. Direct control calls and
the common file-IO continuation honor the same contract. Accepted effects,
partially transferred data and splice are not replayed. Ordinary native reads
and writes do not enter this preparation path.

Mapped watch reads use the same engine reservation as RPC. The supervisor offers
the shared reply, then queues commit/rejection on the filesystem worker after the
adapter acknowledges its copy. No file-table or filesystem turn is held while
waiting. Cancellation before offering restores records; after offering it retires
possibly delivered records. The request retains its buffer, input description and
context until worker completion, independently of task lifetime.
Mapped directory reads prepare records without advancing their OFD. The same
acknowledgement commits its next cookie or leaves its old position unchanged.
`ofd_order` retains only active descriptions, matching aliases with native
`F_DUPFD_QUERY`, or self-kcmp when no inherited seccomp filter can terminate the
owner. Without safe exact comparison, it conservatively orders active operations
on the same native inode. This is ordering, not fabricated descriptor identity:
each operation retains its own captured FD and cursor.
Broker, mapped and RPC directory read/seek operations share those turns. Ordinary
synchronous reads allocate no entry when no asynchronous read is active.
With exact comparison, independent opens also retain separate queues. Queued operations
retain their captured FD, not its possibly reused guest number. Cancellation
before offering leaves the cursor untouched; after offering it commits the
possibly copied batch. The worker holds no SQLite or file-table lock over user
copying. Guest operations bypassing the adapter and OFDs shared between separate
launch owners are outside this ordering contract.
Deferred FIFO opens run in a cancellable supervisor child after the worker has
prepared their resources. Its separate kernel file table owns pin/control FDs;
only the completed endpoint reaches the guest. No worker or resource turn spans
peer rendezvous. The existing SIGCHLD loop owns reaping, including cancelled
operations. Ordinary opens create no child. The guest build contains no
filesystem RPC-client fallback; native host clients retain their RPC contract.
While the child waits, the guest is parked in interruptible native ppoll, not
held at a ptrace stop. Signals can therefore cancel the open or request kernel
restart, including libc cancellation regions. Completion interrupts that exact
wait; cancellation retains the helper and its request buffer through reaping.
Proc-image snapshots share this worker lifecycle. Task-local copying fills the
mapped slot; captured chunks build a worker-owned file. Only the completed
read-only/O_PATH result is delivered. Rejected copying and terminal loss retire
construction without a guest cleanup syscall. Kernel umask reads also stay on
the worker, retaining live kernel semantics rather than a shadow fs_struct mask.
Foreign cmdline/auxv inspection first captures authorized process bytes, then
submits an owned snapshot to the same worker. It holds no task-memory pointer or
file-table turn while queued. The supervisor continues memory repair and signal
delivery instead of synchronously waiting on a worker it may have paused.
Read-only and O_PATH snapshots publish retained origins with their result FD;
later metadata does not require another guest slot. Path metadata and statfs
likewise keep their temporary objects on the worker. Watch registration captures
the queue OFD and resolves the target in the same authenticated view. Opening a
current-thread proc-FD alias captures that descriptor directly; native reopening
uses the captured object, not a second lookup of the guest FD number.
Metadata/readlink/suffix operations borrow the same original input. Foreign
executable readlink uses the shared response mapping and existing process-access
checks, without a guest-side file or read syscall.
Authorized inspection of protected `/proc` tasks captures the native descriptor
and metadata in the target's context when external observation is denied.
Directory results retain that descriptor for relative opens; immutable path
identity does not substitute for provider-owned permissions or live attributes.
A provider-owned pending operation may lend its parked task to this capture.
The task-call owner restores the original wait; entry, interruption and signal
return transitions cannot be borrowed. This lets a FIFO opener be inspected
before its peer opens the other end, without cancelling the FIFO operation.
Mknod of regular files/FIFOs also keeps construction descriptors on the worker.
Task-affine chdir/truncate lease a worker-owned target; the caller executes the
final syscall without installing that descriptor. Kernel filesystem state and
caller RLIMIT_FSIZE/SIGXFSZ remain native. The lease closes on completion or death.
Xattr targets and ACL values stay worker-owned. Bounded mapped bytes carry up to
64 KiB; input is copied before admission and output is copied by the originating
task. No guest payload FD or scratch target FD is needed. FD-style xattr calls
capture the actual description, including its O_PATH restriction; path calls
use the retained cwd/dirfd origin. Native host RPC uses the same engine contract.
Slot reuse clears its header prefix only; unused value pages are not touched by
ordinary calls. Shared bytes do not contain ownership metadata or trusted pointers.
Inherited shared bytes are not isolation between mutually hostile guest processes.

### Descriptor Channels

`fd_channel` owns both receive queues of a seqpacket pair in the supervisor.
A file table acquires its CLOEXEC endpoint lazily via atomic ADDFD_SEND when
protected input export or a result-rights transfer needs it.
Sparse descriptor metadata follows its aliases
through dup, close/range, fork, unshare and exec. The file-table turn covers
export, not worker execution. Each message is bound to its retained channel,
request token, expected vector length and kernel SCM_CREDENTIALS process identity.
The supervisor closes malformed, duplicate and cancelled rights; final channel
release also drops any queued descriptions. It never mirrors application OFDs
between requests. Caller-owned input leases let the channel dispatch accepted
vectors independently of the filesystem service; cancellation does not discard
another owner's message. Dispatch allocates no bookkeeping and invokes no callbacks.
Ambiguous terminal mutation of an infrastructure slot requires
task-local F_GETFD/SO_COOKIE observation under its table turn. Matching socket
identity repairs the entry; closed/replaced slots lose only their annotation.
Denied or cancelled observation leaves it uncertain, without admitting another
possibly orphaned slot. Ordinary transfers do not perform these extra checks.
This costs one infrastructure FD per newly admitted table, not one per request.
Cold admission may fail with EMFILE; native SCM_RIGHTS quotas and application
seccomp denial still apply. Shared guest memory and process credentials do not
establish hostile-thread or arbitrary application message provenance.

Protected metadata uses those same input leases with register-only control. It
does not acquire a filesystem mapping slot, including when invoked inside an
adapter continuation. The table turn covers source capture and original-site
replay; terminal cancellation releases the lease, not a guessed guest FD.
Restricted `/proc/self/fd` directory opens borrow the verified original proc-root
FD under that turn and execute a fixed bootstrap pathname after admission. The
read-only flag allowlist preserves the caller's flags, including `O_LARGEFILE`,
`O_NOFOLLOW`, `O_NONBLOCK`, optional `O_CLOEXEC` and `O_PATH`. Validation is not
an exact libc-specific flag match. These opens run directly in the caller,
without a supervisor-side proc permission probe or an O_PATH ADDFD attempt;
the original-site and continuation seccomp checks both remain active. The
kernel result belongs directly to the application. There is no temporary proc
root or result FD to export/close, and no second read of an application pathname
after it has been validated.

### Result Ordering And Native Seeks

`fd_return` orders result publication by destination table and channel; the
channel turn also covers independent tables inherited through fork. One packet
is offered at a time. `fd_delivery` prepares origins against a borrowed receipt
in the existing shared request mapping, without a per-call memfd, mapping or
guest scratch FD. Kernel recvmsg writes that receipt. Completion or exact task
termination publishes installed origins before releasing the table turn. The
supervisor drains unreceived rights through its retained copy of the guest
endpoint before releasing the channel turn. No cleanup syscall is injected into
a dying task, and no installed caller FD is closed as transport cleanup.
An admitted channel needs only the result slot. Cold admission needs one extra
infrastructure slot; receive truncation at EMFILE releases queued rights rather
than retrying the lookup. Application recvmsg restrictions still apply.

Regular-file seeks use a supervisor fast path after the original syscall's
seccomp admission and domain checks. A task-affine duplicated FD retains the
actual open-file description throughout inspection and seek; no FD-number
cache or namespace metadata RPC is involved. Shared positions and descriptor
reuse retain kernel semantics. When remote descriptor access is unavailable, the
adapter holds the file-table turn through native fstat and a non-directory seek.
This needs no data-OFD mirror, transport FD or namespace metadata lookup.
Directory seeks enter the namespace engine through mapped requests when the
real description can be captured; no preliminary path anchor substitutes for
its shared kernel position. Enumeration retains a separate delivery contract.

### Broker Admission And Cancellation

`namespace_broker.c` handles ordinary openat/fsopen/newfstatat/fstat/readlinkat/getdents64 notifications in the
namespace worker, alongside adapter RPC. The worker alone owns SQLite and the
sealed-image catalogue; `fs_engine.c` also dispatches shared-buffer requests. Eligible notifications
copy paths page by page, retain exact-task descriptors and validate notification
IDs before execution and result publication. Successful opens use atomic ADDFD
plus response. The optional kernel synchronous-wakeup hint changes scheduling
only; unsupported kernels retain normal notification delivery.
Eligible opens, including create/truncate and fsopen, retain their completed
result through atomic publication. If notification cancellation wins, the
supervisor adopts the same FD, origin and provider completion without repeating
the operation. A kernel restart can occur without a signal-delivery stop in that
thread; the broker therefore retains a handoff barrier until the stopped receiver
acknowledges custody. Repeated notifications delegate to that retained result,
not another execution. Scalar mutations still complete under the task-affine
signal shield: successful NOTIF_SEND alone is not a consumption acknowledgement.
The same restart can precede an outstanding delegation/export INTERRUPT, before
any operation runs. An exact syscall/site/argument match replaces only the
cancelled notification token; the existing stop owner completes admission.
`MD_NOTIFICATION rebounds` records this rare transition under `--statistics`.
A restarted notification can also begin delegation while the original worker
still owns its completed open. At the shared stop, the retained result takes
precedence over that exact, unexecuted admission; only the duplicate admission is
retired. `MD_NOTIFICATION readmissionsRetired` counts this ordering. The completed
FD and its effects are neither discarded nor repeated.
If cancellation restarts through a newly published lazy entry, channel binding
temporarily declines while the original admission or result is retained. The
entry unwinds to its original syscall/alias before delivery. Stops inside its
private continuation remain with their ordinary owner; they cannot consume an
application result or replace the private stack with an application PC.
The same context check precedes delegation and descriptor export after a late
interrupt. It retires only an unexecuted admission; completed results and
already-running adapter operations retain their existing owners.
An identity or custody conflict fails closed with a bounded stderr snapshot:
task phase, notification IDs, raw/normalized call sites, numeric arguments and
retained result ownership. It does not read pointed-to guest data, enable syscall
tracing or wait for the worker lock. These diagnostics are available without
`--diagnostics`; ordinary successful calls do not emit them.
Directory reads use the engine's deferred-position contract. Their existing
file-table/OFD turn spans output copying, notification reply and cursor commit.
An invalid notification, rejected kernel reply or failed/partial copy cannot
consume records; only a successful accepted reply advances the retained OFD.

### Task Lifetime And Control

Pidfds are owned by task lifetime, borrowed during request preparation and retired
on exit or exec. No file-number, cwd, path or metadata cache is involved. Published
supervisor state excludes restricted domains and adapter continuations from the
ordinary path. A shared-root restriction revokes every CLONE_FS member before
its caller resumes, including members not returning through a ptrace stop.
ADDFD rejects O_PATH. Eligible adapter requests use retained proc reopening;
uncaptured inputs and nofollow sources without a stable backing use SCM_RIGHTS.
Protected or filtered opens and directory seeks retain the task-affine adapter
and its no-replay contract.
Filesystem transports order descriptor capture and publication with native
table mutations. Adapter controls reuse the shared channel where possible;
kernel-context controls use immutable bootstrap SVC checkpoints owned
by `task_checkpoint`, not service BRKs or application SIGTRAP. Selective TRACE
handles unfiltered or exact-image-admitted markers; service execution without
that admission uses scoped SYSEMU to skip registered markers while real calls
retain kernel filter admission.
Small scalar recvmsg requests prepare their vectors before ordered socket capture,
so capture and IO can complete in one exchange. When permitted, pidfd_getfd
captures the exact caller's OFD under the file-table turn; it is released after
the operation, never cached by FD number. Denied/unsupported native capture uses
the existing task-affine export. Large vectors and ancillary publication retain
their staged handling and exact copy/error semantics.
For short nonblocking receives, dispatch can snapshot the header and vectors
directly and submit the same IPC owner before entering the adapter. This uses
one real receive on the captured OFD, not a peek or a cached readiness result.
An empty/error result retires the input lease immediately; successful payload,
credentials and rights retain the common adapter publisher. Failed memory or
FD capture, blocking IO, large inputs and debugger ownership keep normal entry.
At an initial TRACE or cancelled-notification entry, an empty/error result also
avoids the adapter stack and signal shield. Pending application signals retain
their original mask; successful IO enters the normal shielded publisher without
repeating the receive.
No guest output is written before IO, and a completed receive is never replayed.
`MD_DIRECT capturedReceives` records this admission only with statistics enabled;
the `receive-entry` native comparison covers blocking, MSG_DONTWAIT and O_NONBLOCK,
credentials, rights, readonly headers, unchanged EAGAIN outputs and stream EOF.
`entryReceives` distinguishes pre-shield admission; `receive-entry-signals`
checks blocked pending signals, delivery and mask preservation through empty
and successful receives against native execution.
Only a ready supervised task uses it. FD payload delivery has a retained kernel
recvmsg receipt, so receiver death does not require a guest acknowledgement.
Native adapter chdir/fchdir and a dedicated bootstrap O_PATH capture entry point
also have completion observation. The gate captures `.`, a current-thread proc
FD alias or a retained worker result. Cwd capture orders both independently shared CLONE_FS and CLONE_FILES
owners, acquiring the file-table turn first; descriptor capture needs only that
table. The gate validates its entry address, syscall, flags and origin selector.
The adapter supplies `.` or the original parsed proc alias without rewriting
it into a canonical spelling; the kernel still validates the actual path. Like
internal RPC receipts, this is trusted adapter coordination, not isolation from
a malicious guest modifying its own memory. Kernel permissions and application
seccomp filters remain in force.
On its first O_PATH capture a task acquires terminal-event observation, retained
across clone/exec separately from nested debugger options. At the exact capture
return gate the supervisor publishes the terminal openat result before releasing
the file-table turn. A killed call may retain either ABI spelling of AT_FDCWD;
neither is an installed FD. Unrecognized terminal state remains unknown. This
adds one option update on first capture and an exit event for participating
tasks, not additional per-IO stops or cleanup injection into a dying task.
See [publication ordering](descriptor-origins.md#publication-ordering) for lifetime
rules, inherited/native provenance limits and adapted SCM_RIGHTS origin transport.
Directory reads validate and enumerate through one namespace operation without a
preliminary metadata RPC. A reusable 64 KiB worker buffer bounds output independently
of the RPC frame, with no per-read allocation. The engine's synchronous output callback runs after
the read transaction, before advancing the actual open-file-description cursor.
Denied remote output can delegate only when no bytes or cursor position changed;
partial copies fail without automatic replay. Cancellation before publication
leaves the cursor unchanged. Cancellation after publication/advance may have an
uncertain outcome, like a lost RPC reply. Restricted domains retain their native
descriptor-read policy, and application seccomp filters still apply.

### Statistics And Raw Gates

`shroot --statistics --store STORE -- PROGRAM` enables aggregate
syscall/ptrace/stop counters, broker handling/delegation counts, engine operation
times, SQLite costs and CPU usage, printed once on completion. `MD_WORKER`
separates queue wait, execution, completion-delivery wait and supervisor
completion; execution overlaps engine/SQLite times and is not an extra cost.
`MD_DIRECT` also counts cancelled fast admissions retried at the shielded gate.
Namespace-worker
CPU is a subset of supervisor process CPU, not an additional cost. Collection and clock
sampling are opt-in; there are no per-call logs. Engine counters cover direct,
mapped and RPC execution; transport selection does not hide filesystem work.
Operation times include kernel
waiting and descheduling; nested filesystem/SQLite totals must not be added as
independent CPU costs. Detailed `--diagnostics` is a separate mode.
`--diagnostics=signals` records guest faults, their instruction/stack context and
abnormal task exits without tracing successful calls. A fault record alone is
not a crash: an application handler may recover it. These modes are disabled
for ordinary launches and comparative timing runs.

Internal sendmsg/recvmsg at the immutable raw-syscall gate go directly to the
kernel. These calls are already unconditional native operations in every guest
domain; the domain policy and gate share the explicit list. External calls still
enter their adapters after guest-policy admission. When the entire installed
chain admits the runtime image, owned service gates precede the guest-policy
body in the kernel mirror. The internal transport does not repeat the original
call's policy evaluation. Unproven inherited gates retain kernel admission;
Android's inherited filters apply in either case.
Internal fstat at the same gate reads native descriptor metadata directly; it
does not request logical inode or set-ID metadata. Application fstat retains the
namespace and image-catalogue path.

The guest owns SIGSYS, signal masks and alternate stacks. Native clone and signal
return retain their register/extension ABI. Guest ERRNO, TRAP and KILL decisions
remain authoritative over runtime routing. Admitted lazy entry checks the
original number, arguments and IP locally; cold and original-site fallback
calls retain the kernel mirror. Register changes and same-site replay are
rechecked at their original boundary. Cancellation,
group stops, concurrent TSYNC and non-leader exec have dedicated fixtures.
Retained asynchronous signals cannot mask a pending synchronous exception. The
shared delivery owner admits that exception first and waits for its native
signal-frame commit before publishing a carrier. This preserves the original
syscall/fault context in both execution identities; ordinary syscalls and
deliveries without that conflict acquire no additional commit stop.
An interrupted signal-mask query retains the pre-shield application mask through
original-site replay. Its result never exposes the temporary admission mask;
ordinary unshielded queries keep their native broker continuation.

## Guest Kernel Interfaces

Nested guest debugging uses the [debugger contract](debugging.md). The supervisor
retains physical ptrace ownership; GDB requests do not replace syscall mediation.
Native descriptor completion publishes origins before delivering the inferior's
syscall-exit stop; internal adapter calls are not additional guest debugger events.

Native `/proc` and `/dev` descriptors remain task-affine when the operation needs
no guest inode authority. SELinux can allow local metadata access but reject
descriptor transfer to another process. An ordinary notification that cannot
borrow the descriptor delegates to the existing task adapter. A failed protected
SCM_RIGHTS export returns an error and releases received descriptors; truncated
ancillary data is not a supervisor assertion or permission to bypass a guest domain.

`guest_domain` owns the distinction between an unsupported guest interface and
permission to access a host resource. Kernel audit has no guest implementation:
AF_NETLINK/NETLINK_AUDIT creation returns EPROTONOSUPPORT before a kernel socket
is created. The rule is independent of executable name, distribution, virtual
root and actual root. It applies at the existing socket syscall stop, including
raw SVC and nondumpable tasks, without additional ptrace stops or allocations.
Other socket protocols retain their existing kernel/domain checks.

No host denial is converted to success, no audit record is discarded after
claiming delivery, and Android's global audit service is not reconfigured.
Stock [shadow account tools](https://github.com/shadow-maint/shadow/blob/4.17.4/lib/audit_help.c)
already accept an unavailable audit protocol. Software requiring audit must treat
it as unsupported. Application seccomp ERRNO/TRAP/KILL still precedes runtime
TRACE handling, including this interface rule.

The guest has no key-management provider. `add_key`, `request_key` and `keyctl`
return ENOSYS in the initial seccomp policy, including raw SVC, protected callers,
fork/exec and nested namespaces. They never enter Android's key service or claim
successful keyring creation. Applications may choose their existing no-key-service
path; applications requiring keys receive an explicit unavailable interface.
Later application ERRNO and higher-priority TRAP/KILL policies remain effective.
If an inherited Android TRAP outranks that ERRNO, host-policy admission returns
the same ENOSYS after evaluating guest policy. Executors without that trap keep
the kernel-only path. No persistent keyring allocation is needed.

`name_to_handle_at` and `open_by_handle_at` use the same ENOSYS admission.
Guest inode identities have no exportable file-handle provider; passing these
operations through would expose host filesystem handles outside the guest mount
model. Callers retain their unsupported-interface fallback in both executors,
including under inherited Android seccomp. Application filter decisions still
take precedence.

The legacy kernel-log `syslog` syscall also returns ENOSYS through this policy.
It has no guest provider and must not read, clear or reconfigure Android's log.
The existing guest-only `/dev/kmsg` journal remains available, with independent
reader cursors and event-driven readiness. An application requiring the legacy
API receives an explicit error, including when inherited Android seccomp would
otherwise terminate it. This does not affect userspace syslog sockets.

`io_uring_setup`, `io_uring_enter` and `io_uring_register` also return ENOSYS
through that admission. Shroot has no asynchronous submission provider for guest
paths, logical credentials or typed descriptors. Passing a native ring through
would bypass those owners; reporting its absence lets applications select their
ordinary syscall backend. This applies to both executors and does not intercept
ordinary reads or writes. Guest seccomp decisions still take precedence.

`quotactl` and `quotactl_fd` use the same ENOSYS admission: the guest filesystem
has no per-user/group/project quota provider. Neither interface reads or changes
Android backing-filesystem quotas. This is explicit absence, not successful
quota enforcement; mount-wide tmpfs limits are independent. The shared
`unavailable-calls` policy fixture checks every absent-provider syscall with
plain, ALLOW, ERRNO, TRAP and KILL admission under both executors and entry modes.

`statmount` and `listmount` likewise return ENOSYS until the guest mount provider
implements their query ABI. They must not expose Android's mount namespace.
Applications can use the existing guest `/proc/self/mountinfo` and mounts views;
this is not an empty or partially fabricated mount list.

## Protected Metadata

The supervisor first attempts kernel remote-memory/descriptor access. A
host permission denial retains the task-affine fallback, which exports a
retained descriptor through SCM_RIGHTS,
uses notification ADDFD and resumes a bounded copy at the original call site.
Instruction-only copy faults return EFAULT. Notification IDs are checked when
replying; cancellation invalidates pending work rather than authorizing a replay.

Filesystem service authorization remains based on the real executor UID, not
the browser's seccomp domain. Same-site filtering alone is not a complete
authorization boundary for every broker request or in-process syscall gate.

## Image Admission And Logical Credentials

The common exec preparation owner acquires the final ELF and interpreter once,
for both initial entry and reexec. It reads canonical ownership/mode and file
capabilities through the exact provider FD and mount provenance. Privileged files
receive a sealed execution snapshot with physical and logical metadata checks
around capture; capabilities are rechecked as well. Ordinary
files are not copied. Preparation retains that admission outside writable guest
request buffers until image entry or cancellation. Script modes and ELF loader
modes cannot substitute for the final program's credentials.
Reexec through `/proc/self/exe` retains both the captured image and its logical
executable identity, including for FUSE-backed programs. An anonymous sealed
carrier must not replace the path used by the loader for `$ORIGIN`; the retained
image is not reopened by pathname.

`--admit-elf /absolute/guest/path` can explicitly select one ordinary ELF for a
launch-local sealed snapshot and logical set-ID metadata. The filesystem service
owns the catalogue: path lookup, object reopening, hardlinks and FD metadata agree
on that admitted object. It does not chmod or replace the stored source.

Initial launch and exec use the same admission contract. The supervisor tracks
logical IDs, irreversible drops and no_new_privs, publishes auxv/AT_SECURE, and
applies the corresponding logical dumpability before loader entry. Real kernel
credentials remain those of the executor. Stored set-ID files need no explicit
admission option. File-capability revisions 1-3 use the same owner and admission,
including permitted/inheritable/bounding masks, effective and ambient transitions,
root-ID namespace scope and secure loader entry. Missing mandatory capabilities
are rejected before replacing the old image; admission revalidates the candidate.
`nosuid`, no_new_privs, guest tracing and unmapped user-namespace
IDs suppress set-ID changes. Actual set-ID backing executables remain rejected.
This does not complete native-write privilege-bit clearing;
see [storage limits](inode-store.md#limits).

Process inspection shares one guest credential check. Debugger access uses
real IDs and permitted capabilities; proc filesystem access uses filesystem
IDs and effective capabilities. Both honor scoped `CAP_SYS_PTRACE` and the
target capability subset. Callers retain their namespace, restricted-root,
dumpability and native-access checks; guest capabilities grant no Android
process access.

### Dumpability

`process_access` owns guest dumpability for both app UID and UID 2000.
`CLONE_VM` shares it; fork copies it; successful exec creates a new state.
Ordinary exec restores 1, secure exec selects 0, and committed effective/filesystem
ID changes or permitted-capability gains clear it. Invalid PR_SET_DUMPABLE values
return EINVAL without changing state. Guest seccomp still admits both prctl calls.

Native guest tasks remain dumpable so runtime memory access does not require
protected-copy round trips. Logical inspection checks enforce guest dumpability
alongside credentials for ptrace, process_vm, kcmp and proc views. Same-thread-group
inspection and scoped CAP_SYS_PTRACE retain their existing exceptions. The
voluntary `PTRACE_TRACEME` handshake checks parent/child capabilities separately;
it does not require the child to be dumpable. The dumpability capability exception
uses the address space's retained user namespace, not a namespace
entered later by its credentials. Proc inode ownership uses the same state;
public process directories retain their owner, while protected entries become
owned by the address space namespace's root. Memory-view opens check inspection
authority once; `stat`, `io`, `wchan`, `syscall` and `stack` use their existing
typed read path for field suppression or read-time refusal. Ordinary file IO
does not acquire another interception layer. This is
guest ABI policy, not an Android process-isolation boundary or a core-dump service.
The native component tests may still deliberately disable real dumpability to
exercise host-denial fallbacks; guest tests exercise the virtual contract.

`--user` and OCI User share `guest_accounts` resolution and `guest_identity` with
image admission. Each task retains real/effective/saved/filesystem UID/GID and
immutable supplementary groups across fork and exec. Raw set-ID calls remain
thread-local; libc may broadcast them. `getgroups/setgroups` use checked copies,
with task-affine bounded batches for nondumpable/protected memory. An incomplete
input does not commit a new group list. Initial no_new_privs is inherited from
the executor and can only increase. Filesystem requests use the same identities
through `task_registry`, together with their filesystem-root snapshot; this is
guest DAC and path selection, not seccomp-domain confinement.

The proc-root model handles the restricted self/fd or fdinfo roots used by the
tested helper, with CLONE_FS ownership and irreversible narrowing. Ordinary
inode-backed guest chroot uses the same resource owner and the filesystem engine;
its limits are in [process resources](mount-views.md#guest-roots).
Neither chroot mechanism creates a mount namespace. Guest `CLONE_NEWNS` separately
copies the worker-owned mount view and FS context before publication; it does
not create a host kernel namespace or change the selected Android identity.

Stock Debian Chromium passes three fresh-profile headless JavaScript/DOM runs
and PNG production with its normal helper and no sandbox-disabling flags. Tests
observe installed application filters and require process completion. This is
application execution, not security equivalence to a native Linux sandbox.
Interpreter/library admission, mapped-code protection, complete descriptor/root
confinement and domain-authenticated filesystem authorization are not established.

## Lifetimes And Availability

The runner owns the guardian; the guardian owns the supervisor and its descendants.
The supervisor's namespace worker is joined after tracee cleanup. Signalfd, pidfds,
eventfds and child/ptrace events drive
completion and cancellation. There is no ordinary launch deadline; an explicit
test deadline fails and cancels the owned tree. Supervisors close unrelated
inherited FDs while the application retains caller-supplied descriptors.
See [process lifetime](process-lifetime.md).

The lazy guest probe executes under this supervisor and tests the actual kernel
operations in the selected shell/root domain. Denial fails that guest launch;
it does not disable Android tools, PRoot, chroot, graphics or Desktop. The APK
does not load these native executables into ART or probe them during startup.
Current device coverage is UID 2000, NX809J, Android 16, Linux 6.12.23, 4 KiB pages.
Other kernels, page sizes and actual UID 0 require separate device verification.
