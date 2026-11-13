# Cooperative Syscall Entry

The namespace bootstrap exposes a libc-independent entry descriptor through the
private `MD_AT_COOPERATIVE` auxiliary vector. The default lazy SVC frontend and
focused protocol fixtures use this internal versioned contract. Stock programs
need no hooks: observed, eligible syscall sites enter the same provider.
`--env SHROOT_MACHINE_ENTRY=0` selects Base without removing the shared handler.

- [Call Contract](#call-contract)
- [Stop Ownership](#stop-ownership)
- [Execution Ownership](#execution-ownership)
- [Sandboxes And Debuggers](#sandboxes-and-debuggers)
- [Machine-Code Frontend](#machine-code-frontend)
- [Verification And Performance](#verification-and-performance)

## Call Contract

`cooperative_entry.h` accepts a syscall number and six original 64-bit arguments,
with one zero-initialized opaque handle per thread. ABI version 13 supplies a
runtime-owned syscall-offer table and a caller-compiled action driver,
`md_entry_invoke`, with these terminal results:

- `COMPLETED` supplies the raw Linux result, including negative errno.
- `DECLINED` has no committed side effect; the caller may execute its original
  SVC. Unsupported numbers, nested entry and inherited clients decline before
  request publication.
- `UNKNOWN` is a terminal protocol failure, never permission to replay.

The resident driver also accepts `NATIVE_CALL`: original policy and native
routing have admitted the operation, no adapter ownership remains, and the only
remaining work is its kernel effect and return. Generic API clients do not
receive this outcome.

The size-checked descriptor also supplies `thread_identity` for callers without
libc TLS. It returns the runtime's native thread key, not a guest PID namespace
number. It must not run while helper admission is revoked. Filtered offers carry
the original post-SVC instruction pointer. Without it, only an explicitly
supplied caller-owned kernel continuation may run; otherwise the offer declines.
The runtime's internal fallback cannot substitute for that application site.
The lazy machine-entry frontend uses this callback to avoid collisions
between separate PID-1 processes.

The resident frontend's optional `try_identified` entry reuses that native
identity instead of performing a second `gettid`. For a sole address-space
owner, the supervisor publishes the identity at an owned stop, so even the
first query is unnecessary. Before fork children or CLONE_VM peers resume,
their publication replaces or clears the hint. Revocation clears it as well:
internal adapters may remain live after lazy identity publication stops.
Shared address spaces query the
kernel on every entry; generic API callers retain their own identity check.
Neither a cached guest PID nor thread-local libc layout is used. These changes
do not enlarge worker bursts or spin budgets.

The private frame pool reserves its address space at image entry, but protects
each stack's 64 KiB guard on first use, under that slot's exclusive ownership.
The 64 slots reserve 16 MiB of virtual address space; metadata and stack pages
are demand-zero, not eagerly populated. The fixed layout keeps slot selection
and stack switching independent of the host page size on the hot path.
The bootstrap uses only its small C frame before protection; no guest callback
or recursive handler runs there. A failed `mprotect` permanently declines that
slot to Base. Fork inherits both guards and their publication state. This avoids
64 protection syscalls and 129 initial VMAs in short-lived executables without
removing overflow protection from an active accelerated stack.

The runtime can yield a cancellation checkpoint, the original kernel call, or
one admitted native effect to this driver. The action executes after the runtime
C frame has returned; the driver then calls the explicit continuation. This
keeps libc's forced cancellation unwind within caller-owned unwind metadata.
The shared worker request contains only the syscall number and arguments, never
libc callbacks or continuation pointers. An abandoned continuation is not
replayed. Channel ownership and native-effect retirement remain runtime-owned.

The fixed handle is three pointer-sized opaque words, not a copy of the runtime's
transport structure. The runtime interprets its channel/owner and reentry/backoff
fields; sequence and spin feedback live in the already-owned channel. No per-call
allocation, extra mapping or thread destructor is needed. Extending that channel
does not change libc's thread layout. An active handle must not be copied or
shared across threads; fork resets the child handle before reuse.

The resident driver indexes these handles by native TID. A small inline table
uses bounded probing; collisions spill into demand-mapped bucket blocks rather
than disabling entry at a fixed thread count. Blocks are private to the address
space, so fork copies their data while `CLONE_VM` shares it. Existing handles
never move. The supervisor inventories mappings and retires dead-thread records
through the same owner-last publication as inline records. The private-frame
pool still bounds simultaneous entries, not the number of registered threads.

`syscall_catalog.h` and `entry_route.h` remain runtime-private. Bootstrap builds
the per-image offer table before guest execution. The caller checks a bounded byte flag
for ordinary or cancellation-point entry, without embedding syscall numbers or
engine routes. All numbers in the ABI table admit the common entry; routes select
optimizations, not a supported-call whitelist. Discovery publishes a suitable
executed site on its first interception, independently of that invocation's
fast-path eligibility. Syscalls which the kernel does not intercept do not cause
discovery. The table is fixed for the image lifetime. The implementation routes
offered calls as follows:

| Route | Ownership |
| --- | --- |
| Worker | Stat/statx/statfs, directory reads, readlink, mkdir/unlink/rename/link/symlink, access/ownership/mode/timestamp operations and path/link/FD xattrs use the existing filesystem dispatcher and checked task-memory copying. |
| Value | Scalar UID/GID reads use owner-published atomic values with the same user-namespace translation as the ordinary supervisor path. Publication is valid only after the complete namespace-map chain is immutable; an unfinished map retains registry-backed execution. |
| FD | Existing regular-file/directory `openat` and `openat2`, including O_PATH and writable/append/truncate flags, can use a checked native plan. Known ordinary-file close, dup/dup3, lseek and supported fcntl commands share that protocol and the existing file-table owner. Other open/fsopen calls retain their exact kernel publication carrier. |
| Memory | The resident SVC driver can execute private untracked `munmap`/nonfixed `mremap`, ordinary-range `mprotect` and anonymous private `MAP_FIXED` replacement directly in the caller, using a supervisor-published exclusion snapshot. Other mappings use ordered native effects through the common memory owner. Original-context operations retain explicit admission below. |
| Task | Default route. The guest evaluates the shared interception programs against the published selector inventory, choosing native execution, a direct error or the common owner request. The supervisor retains shared process/resource state and explicitly requests any original context it needs. |
| Readiness | An unmasked native epoll queue can execute a zero-time probe through the existing worker/native-effect journal, including under an admitted guest filter. Native poll/select classification can finish before a terminal kernel wait. Virtual queues retain their readiness owner; an empty infinite-wait probe continues to the real interruptible wait. |

An original-context task route, failed registration and a worker's explicit pre-execution decline
invoke the kernel continuation and return `COMPLETED`. The callback receives the
original number and arguments for that continuation; the broker never calls a
guest function pointer. An admitted native-open plan invokes the optional
cancellation checkpoint in the guest before publication and before the effect,
never after a successful open. Ordinary wrappers leave it NULL; cancellation
wrappers supply libc's own check. No synthetic futex or other kernel call is
needed for this checkpoint. The kernel continuation receives only actual calls.
The lazy frontend fences entry before application filter installation and can
rearm after publishing the original policy. Unadmitted calls fall back to the
original SVC, preserving instruction-pointer-sensitive policy.
Protocol fixtures may supply an explicit continuation; callers that omit it use
the adapter's ordinary SVC, not its privileged raw gate, and must not assume
their own instruction address is preserved.

Every syscall has a route, but a common entry is not a purely userspace
implementation of every family. Kernel control is retained for actual context,
descriptor, mapping and signal transitions, not used as a message transport
for an already admitted scalar control operation.

`interception_route` builds its programs from the same generators as the kernel
filters, including FD argument positions, process selectors and signal policy.
The channel publishes a monotonic selector view; a concurrent older routing hint
falls back to native interception, not to a weaker guest policy. Native routing
needs no preliminary supervisor exchange. Intercepted calls have no second
guest-side context whitelist: only the selected owner can request a stop.
`execution_context` separates native-effect requirements from the remaining
original-context C adapters. Native `WAIT` selects a signal-aware journal, not
ptrace transport. A state-dependent owner can request context before its first
effect; an already-started operation instead retains its exact continuation.
Explicit `MSG_DONTWAIT` socket calls and `IPC_NOWAIT` message operations do not
acquire a blocking-context requirement. The socket adapter and ingress share
the same flag classifier. Descriptor publication and other independent context
requirements still apply; an FD's mutable `O_NONBLOCK` flag alone is not proof.
An `ioctl` admitted as native uses the same signal-aware journal, including
commands that may block. The descriptor owner still decides whether the command
is native; classification does not itself require a stopped caller. C adapters
that retain an original-stack requirement request it separately. Internal native
effects of an admitted C adapter use that same journal, including the extended
terminal setter's legacy ABI fallback. When the service gate is admitted, native
effects publish a caller-owned journal in the shared channel and execute locally,
without request/receipt exchanges. The stopped-task observer reads that journal
only for interruption; the same admission fence covers local and coordinator-owned
effects. Every control receipt publishes whether its owner needs to account for
the native result before C continues. Descriptor duplication retains that owner
and receipt; ordinary native queries and nonblocking IO do not acquire one merely
because they came from a C adapter. Restricted gates retain the coordinator path.
Cancellation returns a restart request
to the C owner; only after its cleanup can original-context signal delivery
resume. Completed effects, including a completed `EINTR`, are never replayed.
Socket adapters use this same internal-native entry, not a separate ptrace
transport. Base restores the application mask around that kernel effect and
shields the remaining C cleanup. Terminal job-control signals therefore retain
their native meaning in both execution modes.
Stopped result consumers read or replace only the scalar result register;
restorable GPR images belong to actual continuation changes, not ordinary IO
completion.
Child waits retain the process owner's native-effect receipt: an internal
interrupt rechecks debugger/private-child selection only if the journal proves
the wait was cancelled. Completed waits, including `WNOWAIT` observations,
retain their exact result and PID translation without replay or GPR capture.
Signal cancellation restores the original wait boundary for the kernel's
`SA_RESTART` decision; it is not a completed `EINTR` receipt.
Process-group signal enumeration uses the resource channel, or the listener in
Base. Both validate the admitted selector and signal through the same process
owner before selecting each native recipient; enumeration needs no stopped GPR
context. Signal delivery itself retains the normal kernel/guest identity rules.
`set_tid_address` executes in the caller through the native journal and translates
its returned TID through the process owner when needed. The kernel retains the
clear-on-exit address, including replacement or disabling; this does not require
the stopped registration machinery used for emulated robust-list cleanup.
Addressless `sendto`/`recvfrom` use native payload IO in the shared kernel
selector program, in both Base and lazy execution. Provider FD selectors retain
virtual control sockets, including duplicated descriptors and reused numbers.
No peer address or ancillary data translation is bypassed.

UID/GID views are refreshed at credential publication. UTS namespaces publish
atomic-word snapshots to their subscribed channels; namespace replacement and
channel teardown release the subscription. Resident `uname` reads one coherent
snapshot and uses the existing checked guest copy. A concurrent publication
selects the ordinary owner instead of spinning on a descheduled writer.

The supervisor's `resource_call` routes both TRACE and channel ingress through
the same process, identity, domain, descriptor and memory owners, in that order.
Kernel-only capture requests require their exact carrier before FD publication.
Broad FD selectors classify the live object under the same file-table turn for
Base and resident requests. Classification consumes the typed call, not stopped
registers. Scalar provider results, directory cursors and admitted native
fallbacks return through the selected ingress; only the resulting operation can
request a stopped context. A context handoff releases the lookup reference
before re-entering the stopped owner.
The memory owner retains its address-space and file-table turns through the
native effect and publishes mapping provenance and SysV detachments on its exact
receipt. Base uses the same completion owner. Changes covering published SVC
sites or exclusion snapshots, task-affine provider mappings, private-memory
repair and unavailable backing capture request original context before the
effect. This handoff releases unexecuted preparation without marking the mapping
unknown. Ordinary map/unmap/protection/remap requests need no GPR capture; later
context acquisition preserves their already-admitted logical request.
`execution_begin` prepares a new C execution; an already-started operation keeps
its owner's continuation instead. Kernel entry, post-SIGSYS recovery and resident
entry retain distinct stop/mask preparation rather than inventing a common
register context.

Registration uses the existing exact-site `gettid` notification gate; the
supervisor selects the caller's channel, never a supplied task identity. It does
not use a synchronous signal: Linux can unblock a blocked SIGTRAP and reset its
disposition before ptrace observes a BRK. Eight bootstrap slots in the
per-image buffer pool are separate from its 32 ordinary response slots and
control reserve. Further channels use demand-mapped blocks sized for channel
state, not full syscall frames. The shared private-table mapper installs a
block under the address-space turn without consuming an application FD slot.
Ordinary calls retain a direct pointer; neither allocation nor block lookup
occurs per syscall. The private helper owns asynchronous signal shielding when
its caller has none, and restores the caller's registers before restoring that
mask. This applies to channel/client expansion and the other helper consumers,
not to each ordinary channel request. Allocation failure, a busy mapping turn and interrupted
registration retain the kernel route with bounded retry backoff; unsupported
admission is remembered by that client. The original syscall has not run yet.
No guest socket or persistent helper thread is created. Fork cannot reuse an inherited client;
a fresh client registers separately. Thread exit returns its slot, and exec
replaces the descriptor and pool.

## Stop Ownership

Audit `entry_context` (before effects) and `entry_require_stop` (retained
continuation) in the owning modules, not only syscall profiles. Both publish
reason counters with `--statistics`. The transport interprets the same result,
native action and C continuation at initial admission and after a wait. A
post-effect context request must never replay the syscall; it carries the
owner's final result, not the value that originally started the wait.

| Owner | Shared execution / remaining stopped boundary |
| --- | --- |
| Execution and proc lookup | Typed FD lookup, ordinary native reads and watch reads share admission. Provider IO and asynchronous completion retain their owner. No stop is needed merely because a syscall can block or a broad FD selector matched it. |
| Readiness, sockets and SysV IPC | Unmasked waits retain event/deadline callbacks or journaled native effects. Watch readiness uses a non-consuming wake before resuming the original read. Temporary signal-mask waits still use the original-frame adapter. |
| Descriptors and filesystem | Ordered slot mutations use the native-effect journal and shared completion. Provider close waits and typed device ioctls retain event-driven owners without registers. Cold selector installation, task-affine capture, table unsharing and real descriptor publication retain stopped continuations where required. |
| Identity and process | Scalar values and updates share the channel. Capabilities, groups, real/effective/saved IDs, virtualized limits, subreaper queries and managed robust-list queries use the common C executor and adapter buffer in Base and resident entry. Ordinary PID translation/inspection, PIDFD creation and native child waits use the native-effect journal and common result owners. Wait-result translation uses a typed C buffer after the exact receipt, without replay. Debugger waits and private-child selection share journaled probes and event waits; restricted inherited policy retains original-site admission. Managed robust registration needs a stop to prepare terminal observation and its retained executor. |
| Namespaces | Validation and pure state results need no register frame. Root changes use the common C filesystem executor; restricted proc-root/cwd changes use direct path copying and the domain owner. Unreadable memory retains the stopped transfer helper. Namespace joins use the common C executor and retain their resource candidate; a required PID-filter installation alone requests a stopped continuation before committing it. |
| Memory and lifecycle | Private untracked native mappings use the exclusion view. Tracked mmap/munmap/mprotect/mremap use ordered native effects and publish metadata on the exact receipt. SysV attach/detach and provider mapping preparation use the common C executor. The mapping owner checks access, retains backing and acquires its memory turn through the shared channel; only actual private-table mapping or guarded FD publication requests a stopped continuation. SVC/snapshot mutation, mapping repair, task-affine backing capture, clone/vfork birth and exec retain stopped continuations where required. |
| Signals and debugger | Ordinary signal-mask queries use a native effect when the kernel mask already matches the guest view. Translated mask and pending queries share protected C copying and a typed buffer in Base and resident entry; they do not borrow a stopped task for each copy/native query. Mask mutations, disposition exchange, actual delivery/restart, sigreturn and debugger events retain their context owner. A pending C wait is completed or cancelled before that context is exposed. |
| Policy | Guest cBPF admission runs before effects. Filter installation/TSYNC retains its commit barrier. An inherited policy without admitted helper gates requests context before entering a potentially native blocking adapter. |

The C dispatcher's remaining context list is explicit and separate from native
effect classification. Its contract test checks the whole syscall-number table:
new C handlers enter the common protocol by default. This is not a claim that
every existing handler has already eliminated register-based continuations.

Guest debugger control and wait events consume a typed call without a caller
register frame. Inferior register access and execution remain owned by the
debugger relationship. Resident candidate probes use the native-effect journal;
an empty probe parks on the existing channel until an actual child/debugger event
or signal arrives. Native fallback uses the process owner. The model retains no
machine registers, including during buffer pressure. Only the stopped transport
constructs probe registers from its retained continuation. A cancelled journal
effect or an interrupted park requests the original signal/restart boundary;
a completed error is not replayed. NULL wait4/waitid output pointers use a
temporary result buffer so reaping and PID identity retirement remain exact.

Stopped debugger parks share the provider poll-boundary and cancellation owner.
The preceding syscall exit must drain before a poll can be treated as entered;
an early wake cancels that exact entry before publishing the wait result.

An ignored signal at a resident readiness effect retires that interrupted
native phase before resampling. The same owner retains its deadline and pending
readiness, suppressing internal EINTR without leaving a kernel-restarted wait
behind an already-consumed wake marker. Base retains its syscall-exit owner.

## Execution Ownership

### Execution And Transport

`guest_execution` owns the task-affine C dispatcher. Its admitted operation
contains the syscall number, original arguments, selected policy, retained
control-file kind and optional prepared input. It does not encode ARM64 register
positions or resume a tracee. The stopped-task assembly gate constructs this
same record on its owned stack without a new mapping or handshake; existing file,
network, IPC and readiness handlers remain its owners.

The supervisor carries the logical syscall as `seccomp_data`: number, six
arguments and original instruction address. Channel requests never fabricate
an ARM64 register frame. The stopped-task adapter retains genuine registers
separately; shared handlers receive a register context only when that ingress
actually owns one. Buffer/FD commands likewise carry a typed command and three
arguments, independently of any checkpoint frame.

`execution_retain` and `descriptor_retain` accept an already admitted request;
they never decode it again from a continuation or native probe. Only the actual
stopped-task ingress uses `execution_capture`. Signal, filter, namespace,
descriptor and debugger adapters retain the same request contract.

Barrier release dispatches an intrusive queue of deferred continuations, not
the full task tree. Notification and resume ownership share one deduplicated
node per task. A still-held barrier requeues that node; recursive releases defer
to the event loop, and terminal cleanup cancels it before task storage is reused.

Admission ownership is retained separately from the current channel packet:
native-effect receipts and provider waits do not turn an admitted syscall into
a new request. Descriptor effects retire only from their own journal receipt,
not from an unrelated signal/checkpoint syscall exit. Provider close completion
releases the table turn before waiting and never replays an already closed slot.

Compact memory observation carries `seccomp_data` directly from the kernel's
syscall-info record. SVC discovery needs only its PC and number; alias admission
uses the original typed policy input. No partial register frame is stored or
restored. Error rewriting and task-affine mapping adapters acquire genuine
registers on demand, preserving the compact native path.

Signal action, debugger wait and descriptor owners retain their logical request separately
from the machine continuation. Only signal-frame entry decodes its ABI registers;
request validation, FD selection and output publication use the captured request.
The query adapter preserves the application's pending mask even when Base has
temporarily shielded execution. Native data and logical SIGSYS state are combined
by the signal owner before the common protected output copy.

`supervisor_transfer` owns bounded maintenance copies at an already stopped
boundary: newborn TID publication, metadata/path transfer and interrupted-SVC
inspection. Permission-respecting direct access is allocation-free. When that
access is denied, one reserved buffer and an existing private stack run the
shared protected C accessors in the exact task. The owner restores the full
continuation before returning the result; cancellation releases both loans.
Paths are read through their terminator without a stop per byte. These copies
do not change the logical syscall or use argument registers as payload storage.

The supervisor separates operation retirement from continuation delivery.
`execution_complete` releases operation-owned resources and chooses return,
preflight replay, clone, restart, IPC wait or native execution. The Base adapter
then publishes registers, restores the application mask and resumes at the
selected boundary. A native wait is not a completed result, and an interrupted
operation is not permission to repeat an already committed effect.
Resident completion uses the same restart owner: after cancelling provider
ownership and retiring the C operation, END requests an explicit stopped
handoff. The SVC owner restores the full original register frame and releases
its private stack; the kernel then decides `SA_RESTART`. Normal typed-provider
reads and writes do not acquire this context in advance. Handler `longjmp`
cannot strand a private frame or an OFD turn.

Terminal shared responses publish a required signal checkpoint together with the
result. The resident driver marks its existing return boundary before releasing
the application, so self-directed logical signals cannot race an asynchronous
interrupt and arrive after the sending call returns. Blocked logical signals do
not request a checkpoint. The same completion contract covers scalar handlers,
native effects and the C executor, without a new stop on ordinary returns.

The ordinary cooperative API and resident SVC driver share `md_entry_advance`
for caller actions and their continuation handshake. A terminal kernel fallback
remains transport-specific: an ordinary client invokes its supplied callback;
the resident driver restores the original instruction site. Worker operations
and admitted native plans keep their direct paths without a supervisor hop.

An admitted task request uses the same per-thread channel for BEGIN, internal
buffer/descriptor/resource controls and END. BEGIN retains the admitted syscall
description; the resident frame runs `guest_execution`, and END retires the
operation before publishing its result. Resource state is not copied into a
second guest-side implementation. Scalar controls also borrow this channel from
an existing Base C adapter when the thread already owns one; they do not require
lazy admission to be re-enabled during that adapter.
When the filesystem worker cannot serve an otherwise admitted operation, it
releases its turns and hands the unexecuted request to BEGIN. This is distinct
from a cancellation, policy fence or retained-result decline: those keep their
original kernel boundary. The worker fast path needs no extra exchange.
Buffer completion returns to a resident C frame; only its END retires the guest
operation. A Base terminal checkpoint can retire directly after acquiring the
original stopped context. The two deliveries share resource cleanup, not a
register-restoration path.
Resident internal controls reuse the client belonging to their claimed private
frame, without another thread-ID syscall or client lookup. The pointer is scoped
to that runtime stack; ordinary Base adapters retain identity-based lookup.

Provider, adapted socket and SysV IPC waits retain their exact channel request. Readiness
completes it through the response futex, without a synthetic kernel poll or a
ptrace wakeup. Only finite deadlines enter the ordered timer list; checking the
next deadline and removing a completed wait are constant-time operations.
Signals cancel the admitted operation through the same owner as Base; a real
restart or generated SIGPIPE acquires the completed original frame before kernel
delivery. Partial IO and committed ancillary transfers are never replayed.
These already-admitted waits can cross a policy publication; the next syscall
uses the new policy. The drain check applies to the retained native journal,
not a syscall-family whitelist: watch and readiness waits release preparation
turns before sleeping, while unfinished capture or publication still blocks
the fence. Native peers requiring kernel sender credentials retain an
explicit task-affine send continuation, not a blanket socket fallback.
An explicit parked-channel response publishes this admitted wait to the private
frame journal. Policy installation does not mistake its occupied stack for
unadmitted work. SysV IPC releases transport buffers before parking; the retained
operation resumes through the same C executor with captured input and deadline,
without reinterpreting policy or exhausting the buffer pool with idle waiters.

`cooperative_exchange` owns the shared publication and park handshake, separately
from channel layout and operation semantics. Filesystem requests run on the
worker; controls run directly on the supervisor. A control request does not
allocate a filesystem operation or traverse the worker completion queue.
The single notification listener remains worker-owned. An admitted control caller
can wake the native supervisor directly with `rt_tgsigqueueinfo`; its blocked
realtime signal is consumed by a supervisor-owned `signalfd`, not a guest handler.
The endpoint is created after the guest fork and before native worker creation.
No guest FD, signal mask or disposition is changed. The queued native TID is only
an enrollment hint: the broker validates the live task, channel, sequence and
stop state before execution. Unsupported endpoints, restricted service gates
and send failures (including queue exhaustion) retain the notification wake.
File requests still wake their worker directly; hot exchanges need no wake syscall.
Each owner watches only channels enrolled by an exact wake or retained pending
request. Registration does not enroll an idle channel. The existing atomic
park/request handshake removes inactive channels; retirement and resume enroll
the precise pending caller before waking its owner. Both owners share this
watch-set implementation and remove membership before task identity reuse.
Control bursts retain the existing 32-operation/100-microsecond fairness bound
and round-robin channel selection. Idle spinning is adaptive and bounded; no
additional service thread is created and native event handling keeps its turn.
During an idle control burst the supervisor polls only atomic channel states
from a snapshot, without the broker mutex. The supervisor owns mapping release;
no snapshot crosses an entry callback. A worker enrolling another control
channel publishes the existing pending hint. Admission still validates the live
watch set under lock. Busy channels leave the snapshot through the same park
handshake; their operation's retirement reenrolls a pending request. A request
for the other owner does not trigger repeated control admission scans.
The worker cannot borrow this lifetime rule: supervisor
teardown can retire a mapping while the worker is executing.

Control and terminal replies can offer a journaled native effect to the common
caller-action driver. The effect's completion is acknowledged on the same channel
before its owner releases the resource turn. A stopped caller's journal distinguishes an
unstarted action from an executed result; teardown never repeats an uncertain
effect. Native decisions with retained resource ownership also use this protocol,
without returning through the original SVC and repeating FD classification.
An admitted terminal native call instead uses a private-frame journal after
releasing its client and channel. It neither retains a file-table turn across
blocking IO nor requires ptrace on entry or completion. An actual signal restores
the original GPRs, flags and stack; the kernel decides restart before the signal
continuation is translated to the original SVC. The general-register-only
payload does not alter SIMD state. A retained signal is checked again after
terminal publication, before the call can sleep.
Policy fences exclude these already-admitted terminal operations; their next
guest call sees the new policy. Fork clears every copied claim, including native
waits excluded from a policy drain. The private frame remains occupied until
normal return or signal recovery, so frame collisions still retain Base entry.
The entry probes four distinct slots before falling back. The uncontended path
uses one atomic claim; collisions need no syscall, register snapshot or shared
channel. Signal recovery recognizes every probe boundary and releases only the
slot actually acquired, including before the original registers have been saved.
The claim word and native journal have independent meanings: collision probes
cannot change the operation state. A fresh owner clears the journal before
private-stack execution and policy admission.
The exact journal gate does not carry another syscall allow-list: the operation
owner admits the effect, and the common context classifier decides whether it
can run on the resident frame. A supervisor reply either supplies a result, an
executor operation or an explicit context requirement, never an ambiguous
"native" instruction which silently repeats admission.
Existing worker/FD/value fast paths do not acquire an extra supervisor
round trip.

Unmasked epoll/poll/select adapters retain their readiness owner and use this
same native-effect journal, without syscall-entry/exit stops for each wait.
The resident frame marks an owned effect separately from a terminal native call:
an actual signal first retires the operation, then restores the original
application boundary. The common owner retains deadlines, virtual readiness,
FD loans and output publication across service wakes and TSYNC. Ignored signals
resample interrupted waits without exposing a synthetic EINTR. Base retains an
interrupted owner until signal delivery distinguishes a handler from an ignored
signal. None of these checks adds work to an uninterrupted native data syscall.

A resident epoll wait in a shared file table duplicates its native queue in the
caller under the existing table turn. The exact journal receipt publishes an
ordinary temporary loan before that turn is released. No export to the
supervisor and import back into the same process is needed. Cancellation and
terminal cleanup reconcile the same receipt used by loan retirement; an unknown
allocation never authorizes closing a guessed slot. Exclusive tables retain the
no-duplicate path. Base and unavailable service gates retain descriptor capture;
supervisor-owned virtual queues still use their existing loan transport.

Blocking native effects and event-parked controls publish their park intent
before releasing the caller. They do not consume the supervisor's idle-spin
budget; an immediate completion still owns the ordinary wake handshake. Short
native effects retain control batching. The filesystem worker likewise excludes
supervisor-owned waits from its runnable set, while retaining its own native
open/close effect batching.

When an internal operation needs actual stopped registers, its owner retains
the request rather than requiring a checkpoint for every control message.
Resident proc-provider IO exchanges its buffers and results through the channel.
Provider/OFD admission parks on its channel; task-affine operations request
context only at their concrete effect boundary. A completion racing that
checkpoint is retained and delivered exactly once.
The handoff retains an explicit context reason and a kernel continuation. This
promotion is distinct from an unstarted BEGIN's context fallback. The caller enters the
matching internal checkpoint, whose continuation resumes that request exactly
once. It does not resubmit the original guest syscall. Buffer/FD waits remain
event driven, and task death cancels their existing ownership. Refreshing a
task's policy or execution view does not release a held kernel stop; only an
actual resume does so.
The first actual checkpoint captures the resident caller's signal mask. A helper's
temporary shield ends before returning to that resident frame, whose own signal
deferral remains authoritative. Pure channel calls neither read nor change the
kernel mask. Internal controls and effect acknowledgements survive a stop before
owner admission: they continue an owned operation, unlike a fresh BEGIN that
may still decline without an effect. Task teardown remains their cancellation owner.
An unadmitted BEGIN takes the terminal kernel continuation without reading an
execution descriptor; a previous request's action cannot cause a second effect.

Original-stack operations (clone/exec/signal return), atomic-mask
readiness waits, FD selector installation and typed descriptor
publication retain their existing kernel boundaries. The shared task entry does
not synthesize a complete application register image from six syscall arguments.
`MD_EXECUTION` distinguishes task entries/completions, promoted checkpoints and
native effects; broker controls are counted separately from guest calls.
`SVC_RUNTIME_STATS.nativeCompleted` counts terminal native returns in the guest;
signal recovery can finish or restart an operation without incrementing it.
`MD_EXECUTION_LOCAL_NATIVE` identifies guest-local native routing, while
`MD_EXECUTION_NATIVE` and `MD_EXECUTION_CONTEXT` identify decisions requiring
shared state. `MD_EXECUTION_STOP` records the reasons for promoting
already-owned operations. Native decisions are a subset of task entries, not a
separate outcome: their journaled result counts as a completion, or their explicit
original-context continuation counts in `MD_EXECUTION_CONTEXT`. For fully retired
calls, task entries equal completions plus original-context continuations.
These counters are enabled only by `--statistics`.

Checkpoint admission uses the installed policy chain's exact stable service-gate
contract: addresses, selectors and actions. When every ancestor admits that
contract, checkpoints keep the ordinary kernel path instead of SYSEMU, including
after exec reuses the same bootstrap gates. The proof is intersected once at
installation, not rebuilt per call. An unbound ancestor or changed gate contract
retains emulated checkpoints. Image-local SVC pool addresses are not this proof.

### Native Memory Effects

`interception_memory` publishes the memory admission snapshot only while the
sole address-space owner is stopped. It excludes retained file/policy ranges,
shared buffers, resident code, frames and patched source instructions. The
caller validates alignment, overflow and page-rounded overlap before invoking
native `munmap`, nonfixed `mremap`, ordinary-range `mprotect` or anonymous private `MAP_FIXED`;
neither the worker nor supervisor
is entered for that effect. Protection growth flags and unknown flags decline:
their effects cannot be bounded by the supplied interval. Discovery still
uses existing interceptions, including executable protection changes, and
does not trap previously native read/write-only protection sites.
Unknown policy, SysV attachments, exposure, or an exclusion set exceeding the
fixed capacity declines acceleration, not the application operation.

The native-memory callback receives the resident frame's original argument
vector and writes one result. An admitted effect does not initialize the larger
cooperative request/continuation object; declined effects retain that ordinary
route. This changes transport cost, not the exclusion or ownership proof.

The owner reuses the exclusion set while its mappings, buffer placement and
append-only SVC site/pool counts remain unchanged. Eligibility, sole ownership,
exposure and registry revocation are still checked at every publication. Fork
starts a separate snapshot; mapping changes and channel retirement invalidate
the cached set. This avoids rebuilding an unchanged range list on every resume.

Anonymous replacement uses exactly `MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED`.
It creates no file provenance and cannot discard an excluded range. Other mmap
flags and file-backed mappings retain their existing owners; all arguments and
native errors are still validated by Linux.

Remapping admits only a nonempty untracked source with flags zero or
`MREMAP_MAYMOVE`. Linux chooses any new address without replacing unrelated
mappings. Zero-length cloning, `MREMAP_FIXED`, `MREMAP_DONTUNMAP`, tracked sources
and unknown flags retain ordered observation. The initial mmap entry guard
rejects clearly unsuitable arguments before saving a private register frame;
that early rejection never grants authority to execute an operation.

Sharing an address space revokes the snapshot before another task can run.
Fork receives independent admission; exec and channel retirement detach the
borrowed snapshot before releasing its storage. Filter/debugger fences retain
their existing revocation owners. This route relies on the resident SVC frame
deferring asynchronous application handlers until completion, so a handler
cannot alter mappings between validation and the effect. Ordinary cooperative
API clients lack that guarantee and retain their original kernel continuation.
No signal-mask syscall, mapping query, allocation or worker exchange is added
to the accelerated call. All effects and errors still come from Linux.

### Shared Dispatch

`namespace_broker` routes kernel notifications and cooperative requests through
the same operation owner, task snapshot, argument decoder, FS/file-table ordering,
filesystem engine and result copier. Guest pointers are read/written with the
existing checked task-memory operations, not unchecked host dereferences.
Inaccessible protected memory can decline before output; a partial output fault
is a terminal `EFAULT`, not a replay. A request cannot supply credentials or a
namespace identity: the registered task selects a fresh authoritative snapshot.
Logical chroot, mount views, credentials, DAC and Landlock remain engine policy.

Temporary task barriers stop broker admission as well as native execution.
Releasing a synthetic interrupt acknowledges the actual successful resume to the
broker, preserving eligibility and waking any request published before that
acknowledgement. Both JIT memory repair and remote task actions use this shared
resume adapter. Injected helper calls and deferred real events retain their stop
ownership; they cannot re-enable ordinary admission. This adds no work to the
ordinary per-call entry path.

An interrupted internal IPC/readiness/debugger poll is marked as a service wake
and rechecks its retained operation. Quiescence must not manufacture application
EINTR. The JIT repair fixture includes a semop waiter whose pending operation
survives memory repair without retrying in the application.

Internal signal scopes save and restore masks through one supervisor boundary.
Nested adapters retain the logical application mask below their internal
protocol masks. Retained asynchronous signals are released only after all
internal owners restore the guest boundary. `signal_delivery` chains real
delivery stops without executing service instructions between them. The final
stop restores the application mask; kernel admission and the original
EINTR/SA_RESTART decision remain native. A completed syscall is never replayed.
The common signal classifier includes asynchronous kernel notifications such as
`SIGCHLD` and `SIGIO`; a positive `si_code` alone does not identify a synchronous
fault. These notifications also require the original guest stack and registers.
Job-control signals remain kernel-pending so STOP can cancel CONT without a
later replay waking the group. The no-retained-signal path adds no delivery trip.
Realtime signals use the same delivery owner in Base and accelerated entry,
including a signal interrupting metadata notification cancellation. Ordered
replay preserves original siginfo ahead of later kernel arrivals; a queue
handoff during non-leader exec retains its carrier identity.

Handler entry retains its packet until the veneer acknowledges the kernel-built
frame. Failed frame construction cancels that packet, preserves the pre-handler
mask and retains the kernel's SIGSEGV and one-shot disposition semantics. It does
not fail the supervisor or another guest process.
Entry and native-return packets retain a mapping turn, not a borrowed syscall.
A newly deliverable logical signal requests their acknowledgement through the
same targeted wake owner; it must not wait for unrelated IO or another signal.
Without pending work these packets need no additional stop.

At an ordinary seccomp-entry stop, a known native fallback transfers the
still-unexecuted call to the ordinary syscall owner without rewriting the PC.
The resident alias executes the native SVC and branches to the original
post-SVC instruction. This removes a redundant SETREGSET, not an admission
check. It does not cancel the call, single-step, or repeat entry.
With an application filter, the same transfer requires an ALLOW decision for
the original instruction and arguments. Policy-only gate stops, guest
TRACE/USER_NOTIF observation, pending policy admission, debuggers and exposed
address spaces retain original-site replay. Unknown syscall numbers can extend
the observed site's entry independently of its current Base completion. This is a
continuation translation, not permission to bypass memory or FD admission.

An interrupted native SVC alias retains its PC until the kernel has chosen
restart versus EINTR. `svc_machine` queues
PTRACE_INTERRUPT at the real signal
stop; the resulting stop precedes handler execution and owns translation of
the saved signal-frame PC (or the live PC for ignored/suppressed signals).
Filter fences wait for that boundary instead of translating early.
Only this interrupted-alias case adds a stop. Metadata notification cancellation
uses the same read-only replay owner for original SVCs and native aliases.
Signal-mask queries may use it; mask mutations retain the signal owner's native
exit and commit semantics.
A restored delivery is not requeued merely because other signals remain pending.
An alias can also return without a signal. A requested drain is reconciled at
the next observed ordinary guest boundary even with no pending signal; a
completed alias must not leave a filter barrier waiting on stale drain state.

A deferred resident return branches to one bootstrap checkpoint registered in
the common interception table. Its policy-admitted syscall number is transport,
not the completion result: the checkpoint skips it before reconstructing the
original frame. This also applies after exec with inherited application filters;
an ordinary native syscall number must not turn a private return into a kernel
call. Without deferred work the resident return never visits this checkpoint.

`task_checkpoint` owns all normal bootstrap control markers: selective TRACE
outside application filters, scoped SYSEMU inside them, and a synthetic
interrupt to recover ARM64's hidden x7 before publishing one typed event.
Native calls inside that scope still pass their original filter with their
original IP and arguments. Register copies, C adapter returns, helper custody,
memory repair and readiness use this same event; actual SIGTRAP and guest
debugger steps are not service completions. `task_call` separately owns native
call entry/exit and exact continuation restoration without a return BRK.
Every successful native resume clears the coordinator's stopped-task state.
Decoder progress also releases that state and the broker's recorded stop when
replaying an event retained by a barrier. This wakes already-published requests
without lending a running task as a stopped actor; borrowed helper execution
retains its separate stop ownership.
Marker decoding uses the PC and seccomp cookie from `PTRACE_GET_SYSCALL_INFO`;
it does not read the full register set merely to identify a known site.
Stop decoding hands each exact observation to its consumer once, without another
kernel read: registers for a completed checkpoint, or registers, syscall info
and event cookie for an ordinary TRACE inside the adapter. These observations
may be consumed in any order, once each. All caller ptrace requests use this owner; any other
request or kernel event discards the remaining snapshot. There is no register
cache across execution, signals or debugger writes.
Checkpoint registers retain the kernel's post-SVC PC. Consumers identify the
marker at PC-4 and resume from the unchanged continuation; there is no preliminary
register write just to rewind the marker. Native-call entry/exit stops remain
distinct from this typed event, including when they share a helper's code site.
The C-facing gates in `checkpoint_entry.S` and bootstrap gates in
`interception_entry.S` set x8 explicitly before the marker:
ARM64 otherwise replaces x0 with ENOSYS before tracing syscall(-1). Entry and
marker addresses are distinct. Native-call returns and their following markers have separate ABI fields;
a native syscall number never doubles as the marker's number.
MAPPED, TAKE, RELEASE and the filesystem CALL, EXEC_PREPARE,
EXEC_CANCEL and PROC_LINK commands return scalar values to C and can complete
at syscall entry, as can a ready marker whose ABI is already admitted.
Full-context completion and native-call injection retain the post-syscall
boundary needed to restore x7. FD-control commands retain that boundary too:
selector installation can lend the stopped task to a native-call owner.

The base filter resolves eligible native waits and kernel execution-state operations
before consulting service addresses. This permits the kernel's syscall-number
allow cache; stacked descriptor/PID selectors and application filters still
apply. Memory operations with mapping provenance, FD IO and service `gettid`
are not part of this unconditional path. Blocking epoll is owned from entry to
support the later addition of virtual interests. For an infinite unmasked epoll
wait, lazy entry first performs a zero-time probe through the ordinary filtered
kernel gate, not the raw service gate. Both ABIs use scalar epoll_pwait for this
probe. A validated zero timespec in epoll_pwait2 completes there even when empty;
the existing caller-owned copy context validates that argument without adding a
descriptor or memory cache. A ready result completes without a worker
exchange or descriptor cache; an empty result enters the blocking owner. Dynamic
queue selectors still collect virtual events. Positive finite waits and temporary signal
masks retain ordinary admission. Filtered readiness calls first admit the
original timeout and mask; the owned probe is an implementation operation,
not another application request with substituted arguments.
See [protocol coverage](../guest-exec-lab/sandbox-research.md#signal-neutral-checkpoints).

Known separate limitation: a fault in a fallback userspace memory copy can
reset an ignored/blocked SIGSEGV disposition before ptrace observes it. A
signal-neutral completion marker does not make that access kernel-mediated.
The fault control remains explicit; this protocol does not claim transparent
invalid-pointer handling under every protected-memory and seccomp combination.
The [kernel-copy controls](../guest-exec-lab/sandbox-research.md#invalid-pointer-copy-controls)
record safe-copy behavior, filter restrictions and isolated cost; they do not
change the ordinary copy path.

The task registry retains an immutable resource generation for repeated reads.
Each admitted read owns one reference instead of independently retaining every
namespace and credential object. Publishing changed credentials or resource
handles replaces that generation; old readers keep their exact snapshot until
release. Root/cwd are captured afresh under their filesystem owner, and resource
contents such as file-table entries and mount topology stay live. This shared
registry path also serves ordinary kernel notifications and filesystem RPC.
Retirement invalidates admission before releasing the registry reference; no
allocation is required for terminal retirement or final collection. A shared
native wait can reap before the next kernel stop observes coalesced `SIGCHLD`.
That interval retains a compact PID/UID sender snapshot, not namespace or quota
ownership; native PID snapshots need no allocation. Proc and credential queries
no longer expose the reaped task. If the namespace-number
snapshot cannot be allocated, the existing retired record stays until signal
observation permits final collection. This does not add a stop to ordinary waits.

For cooperative calls, the caller can capture two bounded path operands before
publication, using the same string-argument selectors as the worker. Bootstrap
compiles those selectors into a bounded per-image byte table; FD-only calls do
not construct a filesystem request just to discover that they have no paths.
Openat2 shares pathname capture, while the executor still reads and validates
its open_how structure. The capture is bound
to the request sequence and original addresses, never reused as a pathname or
permission cache. Uncaptured operands retain checked remote copying. The shared
`guest_copy.S` owner stops strings at the first NUL instead of copying a whole
page first; ordinary task-affine adapters use this same implementation.

Small stat/statx/statfs, readlink and xattr outputs travel in a sequence-bound
response buffer. After the engine finishes, the caller copies them to its own
destination. The supervisor converts only synchronous SIGSEGV/SIGBUS at the
published copy PCs into EFAULT, with an active copy owned by that task (or its
ordinary dispatch phase). Unrelated signals are delivered normally. A partial
copy is terminal; it never retries the operation. Directory reads retain
synchronous checked output because cursor advancement requires a successful
write. Large outputs retain the existing transport.

Except for admitted native plans below, FD-producing and task-affine calls reuse the existing kernel result-custody
protocol, file-table/OFD ordering, atomic ADDFD publication and protected-caller
transport. No second cooperative FD-delivery channel is introduced. An accepted
mutation is never retried because its result delivery was interrupted. Ordinary
blocking calls continue to wait in their guest thread, not in the shared worker.

The FD request has a distinct pending state: speculative worker scans never
execute it. The listener matches task, sequence, number and all six arguments
before adoption. A kernel denial before admission withdraws only that request;
at admission, a receipt retires the now-copied shared request record while the original
kernel continuation supplies the authoritative FD or errno. Interruption never
replays creation or truncation. Native-leaf eligible births instead use the
unpublished prepare/effect/commit protocol below; other creating endpoints retain
the original notification carrier.

### Native FD Plans

The engine can return one owned `O_PATH` anchor instead of a readable data FD for
an admitted existing regular file or directory. Resolution, credentials, DAC, Landlock and
mount checks use the same engine. Identity is checked with `fstat` on the opened
pin, not a preceding pathname lookup. That verified identity and owned pin become
the existing FD-origin object without another open or stat. The guest reopens
that exact object through the
supervisor's proc FD, so rename, unlink and pathname replacement cannot redirect
the data open. Each open creates an independent native OFD. Only the guest owns
the data FD; the worker reserves its origin update before any effect.

The same plan covers plain native mounts and selected inode objects reached
through bind or copied mount views. Reopening a selected inode shares the
catalogue's permission, readonly and COW owner; private bodies do not start a
write transaction merely to reopen. Directory plans retain O_NOFOLLOW through
`fd/`, without an additional search-permission requirement on that directory.
Non-directory O_PATH|O_NOFOLLOW opens retain exact descriptor transfer.
Ordinary and task-affine deferred directory opens use the same path/flag owner.

Native-leaf path resolution can retain the final O_PATH descriptor and its fresh
fstat together, avoiding a separate pathname stat followed by reopening. The
walk owns that descriptor until transfer or failure cleanup. Unpinned metadata
lookups still compare identity after opening an encoded-metadata file: a prior
pathname stat is not proof that the later descriptor names the same object.

Ordinary interception reuses this provider pin for eligible existing-file opens
too: the engine transfers it to the origin and opens the data OFD from that exact
pin, without another identity query or origin capture. Write/append/truncate use
the same authorization and COW preparation. Failure to allocate the data FD
releases the origin and precedes truncation. Creation keeps its existing
publication path; special endpoints decline the pin plan before effects.

A leaf assembly gate executes `openat`, ordinary-file `close`, `dup`, `dup3`,
regular-file `lseek`, admitted `fcntl` commands and nonblocking epoll actions,
and publishes a native-result journal. Shroot's filter admits those numbers only at
that exact instruction; this is still a real kernel syscall, without its ordinary
notification/ptrace round trip. Application filters are evaluated against the
original call before offering this plan. Known FD flags can be read,
or set to their current value, under the file-table turn without another kernel
effect. F_GETFL/F_SETFL use the kernel's actual open description, and
F_DUPFD/F_DUPFD_CLOEXEC use the common FD-mutation owner. Directory seeks retain
their acknowledged cursor path. Selector-owned descriptors and active FD loans
retain existing handling. FD-only operations pin only the authenticated live
task's file table, not unrelated namespace resources.

Native FD admission reads a value-only kind/flags snapshot under the running
table turn, without temporary object references or a data-OFD capture. Unknown,
uncertain, provisional, channel and typed-wrapper slots are not native authority;
regular IO additionally requires a known regular-file kind. A dup3 destination
must also have an unambiguous selector observation.

An admitted existing regular-file open, seek or native descriptor query on an
exclusively owned table can publish one value-only `md_fd_view`. Open binds it only to its
completed native result, never an expected FD or a provisional file birth.
Subsequent `lseek` calls on that FD execute in the
identified resident entry, without another worker request. Linux still owns the
shared OFD cursor and validates the offset and whence. `F_GETFD` and unchanged
`F_SETFD` use the authoritative descriptor flags; `F_GETFL` reads the actual
kernel OFD. Regular-file `FIONREAD` uses the existing admitted native-effect gate
with a private completion journal, without overwriting a preceding operation's
pending retirement. Size and available bytes are read by the kernel, not cached.
The same command on devices, pipes and watches retains its ordinary owner.
Changing flags retains the ordinary mutation owner. Every new channel request,
ordinary kernel notification, task stop/resume or borrowed execution revokes
the lease. Shared tables never receive it. The explicit entry API does not use
this shortcut: only the resident private frame defers signal handlers until the
call completes. No descriptor bitmap, cached offset, or predicted future close
is involved. Direct cursor calls use the existing raw service gate after the
same original-call policy check as other admitted effects.

The original file-table turn owns the operation until result bookkeeping and pin
retirement complete. The journal distinguishes not-started, executing, completed,
cancelled-before-effect and unknown outcomes. At a stop inside the assembly gate,
the supervisor captures the real result or skips an unexecuted effect before
delivering the signal/exit event. In particular, cancellation before `close` must
not retire a slot as if Linux had completed `close` with a late `EINTR`. Unknown
outcomes abandon the metadata claim, never replay the effect. No allocation is
required to publish an already-created native FD.

#### Cancellation And Completed Effects

A cancelled-before-effect plan returns explicit pre-execution handoff after its
owners retire, not a synthetic `EINTR` to libc. If the signal handler returns,
the caller executes its original syscall through ordinary admission. A handler
which abandons the call has no outstanding effect or borrowed file-table turn.
A completed native result, including a real kernel `EINTR`, is never retried.

After a completed native effect the caller may return its exact result before
worker bookkeeping finishes. A distinct `RETIRING` state lets it queue one next
request in the separate call record, without reusing the native journal or pin.
The worker first commits the old FD result and releases its turn, then admits
that request. An ordinary kernel notification from the same caller also drains
the pending result before route selection. A signal stop cancels any unadmitted
next request before its handler can abandon the call frame. This is bounded
one-request pipelining, not an unbounded FD cache or a second descriptor table.
The synchronous receipt remains valid when the worker wins the publication race.

#### Writable Opens And Birth Publication

Writable, append and truncate opens prepare any required copy-up in the normal
engine. Preparation never performs the application's data open or truncation:
the guest kernel open owns those effects, including `EMFILE` before truncation.
Internal COW copying keeps its existing IO and publication contract. Nonexclusive
`O_CREAT` may use a plan when the file already exists. Missing regular files in
native-leaf eligible or host-permission catalogue directories use an explicit
unpublished birth owner.

Existing-open resolution and optional birth preparation share one namespace
walk and store admission. A missing native leaf does not restart resolution in
a separate create call. Publication still revalidates catalogue occupancy; for
native destinations `RENAME_NOREPLACE` checks physical occupancy atomically,
without an additional pathname stat. Physical absence is not cached across
operations.
The owned sequence is:

1. Prepare a hidden backing and O_PATH pin with the live guest identity, umask
   and parent policy. No name or CREATE event is visible, and no database
   snapshot is held across the caller's effect.
2. The caller opens that pinned object in its own file table. `EMFILE` precedes
   publication. The prepared FD-origin update includes provisional custody,
   including the application's actual CLOEXEC flag.
3. Revalidate the parent, mount/storage write policy and destination, then
   publish with `RENAME_NOREPLACE`. Only this commit makes the FD returnable.
   The watch owner translates the hidden move to CREATE/OPEN exactly once.

Ordinary implicit-metadata births use `mknodat(S_IFREG)` followed by an O_PATH
pin. Preparation opens no data description and needs only one spare worker FD;
the guest still owns the real data open. A denied/unsupported regular-file
mknodat leaves no name and uses the existing prepare/open path. Exceptional
ownership, ACLs and catalogue directories retain that path. Failure to capture
the pin removes the hidden name before returning the original error; a cleanup
failure is reported without retry. Conflicts never remove another creator's file.

If publication fails, the existing task-action/FD-loan owner closes the exact
provisional FD under its original table turn before the error is returned.
The same retirement owner serves readiness aliases and other temporary FDs.
An available resident caller executes the close through its native-effect
journal, without a ptrace stop. Base and orphan cleanup retain the borrowed
actor path. Both use the same mutation receipt and close-range fallback;
terminal cleanup reconciles the receipt before releasing a borrowed table turn.
A withdrawn plan is distinct from a completed `close(-EINTR)`, and an unknown
effect makes the slot uncertain rather than authorizing a repeated close.
An exclusive loser preserves the winner and returns `EEXIST`; a nonexclusive
loser may resolve the winning name only after its private effect is compensated.
Cancellation before the effect removes the unpublished backing. Process death
releases kernel FDs; exclusive store maintenance collects orphaned backings.
There is no predicted close, deferred visible creation or cached negative name.
Default ACLs are inherited into the private backing before publication, using
the existing native metadata owner. They override umask exactly as ordinary
creation does, and survive later hardlink promotion. Host-permission directories
retain their real permissions and catalogue layout: a separate random backing
name becomes visible only with the namespace transaction. The staging name
never acquires a catalogue identity, so queued preparation events cannot become
application OPEN/CLOSE events. Its final move publishes CREATE/OPEN in order.
The commit receipt transfers ownership at SQLite commit, before watch-marker,
checkpoint or unlock retirement; a later error cannot authorize discard/replay.
Completion joins only the birth owner's exact operation gate, under the normal
count/time budget. A window held for another store ends before publication.
The publication transaction still commits independently before the guest's
successful return. Existing-file retirement needs no publication transaction.
Other ineligible providers retain their original creating carrier rather than
claiming native-plan semantics.
Pin-only existing-file preparation does not need an umask;
birth preparation reads it freshly through the retained proc-status descriptor.
FIFOs, devices, typed providers, executable snapshots and mutable-image reopens
retain their current route before a data open. Inode-backed and plain native
mount paths explicitly admit plans; typed providers do not inherit that
capability implicitly.
Openat2 captures and validates the exact known open_how structure, including
BENEATH/IN_ROOT/symlink restrictions; extended sizes retain the original decoder.
O_PATH symlink endpoints also retain their existing route.

#### Deferred Opens

Deferred FIFO opens retain their helper and unpublished endpoint in the buffer
service. Their guest waits in an interruptible native poll. Completion wakes the
exact task; a signal cancels and reaps the helper before its ownership disappears.
The existing syscall-restart path preserves kernel `SA_RESTART` and libc's
cancellation PC. Neither the namespace worker nor a permanently stopped guest
waits for the peer. Temporary signal shields do not become application masks.

### Wait And Wake Protocol

A release-published request has a monotonic sequence and one response receipt.
Conditional wakeups use sequentially consistent wait registration and state
publication. Each execution owner drains a round-robin burst, bounded by 32
requests and a 100 us between-request budget, then services other work. An idle
channel does not add a periodic polling loop. Each owner uses a bounded 25 us
spin; the caller uses at most 32768 state-check iterations, without reading a
clock. Four unsuccessful spins select immediate parking for 128 subsequent waits.
Caller feedback is independent for an open plan, completion of its native
effect, and an ordinary reply. A slow creation therefore cannot put unrelated
short metadata replies into the same cooldown. All caller phases retain the same
iteration limit; supervisor control bursts have independent feedback from
filesystem work. Only progress observed before parking counts as a caller hit.
Each owner also requires progress before its deadline. A reply already ready after a blocking
wake cannot reset the miss history. The notification wake is acknowledged
after its immediate work (or resource-queue admission), avoiding a redundant
client wake/park. This is local feedback, not CPU-affinity or scheduler policy.
When an FD request is already visible, an idle worker yields to its listener
instead of spending the remaining spin budget waiting for another memory request.
The worker clears its awake publication at the actual poll boundary; the
supervisor clears its control publication when leaving its bounded burst.
The shared request/native-result check and producer's wake check form a
sequentially consistent handshake: work observed during parking forces a
nonblocking poll, which still services other sources before the next burst.
Eligibility changes participate in the same protocol: retiring a delegated
operation wakes the next request's owner if the caller has already published its
next request. Registration replies may resume the caller before supervisor-side
retirement; that overlap must not require a later unrelated syscall to progress.
The broker fixture forces this ordering and waits without any further guest IO.
Queued file-table admission wakes a supervisor that previously attempted to
borrow that caller: preparation may have rejected the selector/capture borrower
before the worker published its queued turn. Ordinary file-table contention
without a pending borrower adds no supervisor wake.
That borrower can itself own the table, so neither side may await table
retirement to discover the now-borrowable caller. Base notifications and resident
requests publish this transition through the same owner.
Supervisor dispatch also reports a completed burst as progress: resource owners
must run once more before the event loop sleeps. A callback can enqueue a mapping
behind an outstanding signal-frame acknowledgement without leaving another
runnable channel request. An empty dispatch reports no progress and does not
keep the supervisor awake.
Owner clock reads are batched every eight empty iterations. The caller bounds
active work rather than wall time: iteration duration depends on CPU and cache
behavior. It never replenishes the budget after a wake or spurious futex return.
At the request/time burst limit, the worker releases the store window and may
spend at most another 25 us retiring native effects it already admitted. It does
not consume a new request or predict a following close. With no outstanding
effect this final drain neither spins nor reads the clock.

Guest spinning depends only on the owned channel state and a local counter;
application-owned clock mappings, hardware timer access and clock syscalls are
not prerequisites. The worker's clock belongs to its separate address space.
Application time syscalls, deadlines and filter admission remain unchanged.

The caller does not abandon accepted output memory on a wall-clock timeout.
Response/owner closure completes its event wait; launch cancellation terminates
the task. A kernel stop observed during admitted work is retained by the existing
broker event owner until that work and its resource turns retire. Only then can
signal/exec/exit handling release or reuse its mapping.
This includes the last terminal event: kernel `ECHILD` does not discard a
broker-owned result. Worker completion remains event-driven until that exact
event is returned; shutdown finishes only after task and helper owners drain.
If the request has been published but not admitted, the stop cancels that request
under the broker lock before signal delivery. A handler may then `siglongjmp`
without a later worker write into abandoned memory. Reentry on a busy client
keeps the ordinary syscall path; abandoning that client does not authorize reuse
of an in-flight channel.

### Bounded Resource Reuse

`execution_window` is transport-independent bounded resource reuse. The inode
provider holds only the store gate and its validated catalogue generation, not
a transaction or an unfinished read operation. Stat, FD metadata, access,
readlink, directory reads, xattrs, creation, rename, link/unlink and attribute changes can share
that gate in a single uncomplicated store. Each operation resets its own
authorization checks, watch context and transaction state; writes commit or
roll back independently before completion. Local row invalidation remains
active, and publication recovery runs before each operation when needed.
An unchanged admitted read starts directly; completion with no transaction,
event publication or pending sync does not enter the write-retirement path.
Native attributes and policy are never cached by the window. COW and promotion
may therefore complete without discarding the gate or replaying preparation.
Explicit nested read scopes retain their stricter no-write contract and the
internal pre-effect `MD_OPEN_NEEDS_WRITE` result.
Directory reads retain their exact OFD and cursor turn; a captured descriptor
must match the directory anchor before joining the same store window.
An interrupted preflight is not an executed operation: the original
signal/restart owner retains EINTR/SA_RESTART semantics instead of a saved
cancellation errno. Ready resource waiters use the same window admission as
new requests, including both operands of rename/link.
Relative requests first capture the live
cwd or dirfd anchor under the existing FS/file-table turns. Path-only operations
borrow that retained anchor's descriptor, as do fstat/fstatfs. Data/offset and
descriptor mutation operations which need the actual OFD still capture it.
A relative operation additionally requires the exact
base-mount anchor and descriptor, never a stale pathname or cached FD number.
Dynamic local mounts do not disable retention for unrelated base-store paths.
The inode walker revokes retention before entering the general mount/provider
path; it preserves the executor's lifetime reference until normal window end.
Foreign topology imports and restricted native roots retain separate admission.
Count/time expiry, provider changes,
delegation, parking and teardown end it. It caches neither stat
results nor credentials/permission decisions; native attributes are read anew.
Only the physical identity of a managed directory backing may survive between
operations under that gate; catalogue write admission invalidates it. Logical
permissions are projected from the current node and caller every time.
Catalogue lookup also has a per-query recent exact-key hint within the same
validated cache. It bypasses hashing, not row/generation validation. Local writes,
rollback and external versions invalidate the same entries; native leaf results
remain uncached. Path walking advances a cursor and materializes a new remaining
path only when following a symlink, retaining the existing root/resolve policy.
The store gate supplies the existing writer/version ordering. Kernel IO or
descheduling can extend a scope; 100 us is not a real-time latency guarantee.

Kernel notifications use this same bounded admission window when the listener
already has queued work. The worker checks readiness with a zero-time poll before
each additional receive, never spins for a future syscall, and releases the scope
before delegation, result delivery, queued resource turns or watch service.
The shared metadata catalogue selects stat/statx/statfs, access, readlink and
path/link/FD xattr reads for this route, including unmodified and filtered
programs. It also defines the read-only interrupted-SVC restart set. Cursor and
mutation calls are not added to that set. Protected memory and restricted native
roots retain the task-affine adapter when direct capture is unavailable.
Exactly one owner reads the listener. A request cancelled between readiness and
receive may return ENOENT; it does not authorize waiting for a replacement call.
The notification-batch call counter includes such receive attempts. This reuse
also applies to filtered programs, without moving their calls outside seccomp.

An inode-produced native-leaf anchor may retain a membership proof with its
exact O_PATH identity and store lifetime. Fstat can then skip reconstructing
membership through proc paths. Every call still reads live native attributes
and checks the catalogue first, so promotion to hardlinks, OCI or COW wins over
the original native representation. This retains neither modes/owners nor a
pathname result. Unknown/imported provenance uses the ordinary resolution path.

## Sandboxes And Debuggers

Before attempting a filter installation, the supervisor revokes the relevant task or
TSYNC thread group's authoritative eligibility. A rejected installation can rearm
the unchanged policy through the same drained-entry and immutable-view checks;
missing proofs retain Base. Unfiltered callers retain their unfiltered registration
mode, including after capability probes with invalid programs or flags.
Already admitted operations complete before kernel
filter installation. Revocation disables discovery immediately and retains
unfinished admission/code writes if a task's stop is lost to exit or exec.
Another stopped member of that address space completes retirement before
debugger exposure or filter admission; an incomplete write is not a drained
entry. This retry state is shared by debugger and filter transitions.
The slot claim precedes the payload's acquire read of
admission, and its release follows the final private operation. After revocation,
observing all slots empty therefore retires pre-revocation API execution; later
claims see revoked admission and cannot call the API. The caller leaves private
entry before clone or signal-handler execution. Before a copied
address space resumes, its inherited peer frame claims are cleared; CLONE_VM
peers retain their live shared frames. Ordinary adapter operations
are drained separately. A readiness wait already admitted by the kernel retains
its completion owner across TSYNC; filter installation does not wait for the
application event that may itself depend on the installer. Logical dumpability
does not revoke runtime memory access. At a terminal task stop, the same context decoder
releases that task's frame without touching live peers. New public admissions
remain held through filter commit or failure. Native sleeping threads are not
interrupted. Registration remains disabled until publication completes.
Successful installation maps an immutable original-policy view through the
existing private-table mapping helper. Views share retained ancestors and belong
to the address-space owner. Fork retains them; exec creates a new owner.
The coordinator publishes each channel's policy before rearming entry. Concurrent
installers sharing an address space finish or retire before registration is
republished, including queued preflights that can still revoke it. TSYNC
uses the same installation barrier and retained old-policy admission snapshots.
Replacing or protecting a policy mapping drains direct entries before
invalidating the view graph; unavailable views retain Base.

The pure cBPF interpreter evaluates original nr/arch/args/IP before worker,
native FD, cached-result or memory effects. ALLOW uses the existing route; ERRNO
returns directly. TRAP/KILL/TRACE/USER_NOTIF and LOG retain original-site fallback.
An immutable per-chain bitmap proves argument/IP-independent ALLOW decisions
at installation; other decisions use the same interpreter. A miss is not a
rejection. Readiness probes admit the original timeout and signal mask before
issuing an owned nonblocking probe; helper arguments are not a new guest call.
Kernel mirrors retain ordinary cold-call admission. For resident entry, owned
service gates precede the guest program, so an admitted service syscall does not
execute that program again. A syscall-number/architecture-only prefix preserves
the kernel's constant-ALLOW cache; remaining original sites evaluate the guest
program unchanged. Local native routing uses the admitted bootstrap gate only
when the entire installed chain covers its exact service-gate contract. Context recovery and
unproven inherited gates retain their original-site continuation. This does not
intercept every native read/write or remove Android's inherited filters.

All ancestors must cover the current entry image or be portable across exec
before rearming. Portability requires IP-independent policies and a provably
unconditional-ALLOW inert checkpoint. New images publish new policy views and
never reuse former code-pool addresses. Other inherited policies and excessive
mirror size retain Base. The image registration hint grants no policy authority.
Exec discards the retired address space's entry-revocation state; inherited policy
and debugger ownership still govern admission in the new image.
Debugger tracing selects Base while any inferior in the address space remains
owned. Debugger and filter suspension are independent, composable reasons owned
by the SVC registry, not permanent retirement. The common stopped-task admission
releases debugger suspension after the last relationship ends, including detach,
tracer exit and a fork inheriting a suspended registry without that relationship.
Remaining filter fences still hold admission. Re-admission preserves each valid
site's prepared gate and verifies its original instruction on the next encounter;
mapping retirement or permanent memory exposure is not undone.
Resident completion publishes a pending signal or debugger boundary with its
response, including a response that delegates a native wait. A preparation stop
cannot consume that demand and allow the subsequent wait to block ahead of it.
All context restoration uses the common continuation decoder; its service scope
and stop observations are invalidated with the register mutation.
Restricted native roots decline worker path
operations but can retain pathless value, native FD and readiness entry.

Temporary suspension preserves eligible sites for lazy re-admission after the
last owner releases it. Retirement of a published gate remains permanent. A failed cold
admission has no resident continuation: its negative lookup is invalidated by
a completed mapping change, allowing RX code to be admitted after an earlier
RWX observation without re-reading mappings on every call. Already admitted work,
including listener CONTINUE and retained pre-TSYNC calls, finishes through its
existing owner rather than re-entering an accelerated gate under a newer policy.

The shared seccomp owner retains each original classic-BPF policy and
its native validation. Owned service gates are routed separately so that
implementation syscalls do not acquire a second application admission. Original
guest calls retain their policy and denial actions; inherited Android filters
still constrain native execution. TSYNC preserves the admission snapshot of
already accepted work. See [policy ownership](README.md#guest-policy)
for the shared dispatch and notification contract. A client-writable flag is an optimization,
not authority or a security boundary; shared memory is cooperative transport for
trusted guest code, not authentication against hostile writers in an inherited
mapping. Shroot's documented isolation limits remain in force.

Kernel-admitted close uses compact bookkeeping even with an application filter:
PTRACE_GET_SYSCALL_INFO supplies the actual syscall and descriptor, never an
application-controlled TRACE cookie. The existing table turn and observed exit
result still own retirement. Filter denials do not retire a slot; debugger,
pending-loan and conflicting-owner cases retain the full path.

Kernel-admitted writes to typed control files can capture their input with
`process_vm_readv` before entering the guest adapter. Scalar and vectored calls
share the adapter's size validation and bounded payload contract. This includes
FUSE transport and provider-backed file writes, not a Snap-specific route.
Only a complete input capture admits the existing OFD operation; protected
callers, copy faults, debugger ownership and buffer exhaustion retain the guest
path without provider effects. The same IO queue, asynchronous completion,
signal/restart and cancellation owners retire both paths. Application seccomp
still admits the original call; ordinary native files do not use this capture.

Deferred file, close and control operations use one syscall-stop-aware poll
entry. A seccomp-admitted call can still owe its original exit stop before the
synthetic poll reaches entry. The wait owner drains that exit and re-arms all
poll registers before admitting readiness, cancellation or signal delivery.
This check belongs only to deferred waits; immediate completion adds no stop
query. The supervisor-state fixture exercises both entry origins against real
ptrace stops, in addition to provider/FUSE and signal-restart coverage.

## Machine-Code Frontend

`svc_runtime.c` owns per-image preparation; `svc_registry.c` admits observed
single-SVC sites. Publication does not stop neighboring threads. Signal,
mapping, fork/exec, debugger and filter transitions use the existing task and
memory owners. The frontend supplies the original syscall number, arguments and
instruction address to this protocol rather than a second syscall implementation.
Only an explicit context request restores the original machine registers at a
kernel boundary; the shared transport does not manufacture a register frame.

The first eligible observation publishes the entry independently of the
invocation's fast-path eligibility. TASK routes, invalid arguments and tracked
or shared memory are not publication exclusions. The entry selects a direct
handler or the existing Base owner for each call using its number, arguments,
policy and current resource ownership. Original-stack operations and native IO
retain the Caller route. Code admission still requires a private executable
source and a reachable resident gate; publication alone is not acceleration.

The stock musl/glibc fixtures exercise cancellation, signal abandonment,
FIFO restart, FD counts and static/dynamic linking. They compile only the small
test workload against distribution libraries. The
[SVC guide](../guest-exec-lab/svc-rewriting.md#product-boundary)
documents publication, signal semantics and verification boundaries.

## Verification And Performance

`test_cooperative_runtime.py BUILD` runs buffer/execution-window contracts, the
filesystem dispatcher and a protocol guest through the actual bootstrap and
supervisor with the selected App or Shell fixture executor. The guest checks pointer/partial-write faults,
native FD operations, signals and reentry, fork/exec, thread/slot churn,
dumpability, credentials, mounts/chroot, private PID namespaces, debugger fallback
and actual TSYNC denials. It checks kernel continuations including argument- and
instruction-pointer-sensitive filters, FD results and exclusive creation without
replay, and signal-handler `siglongjmp` with reused output memory. It also
checks that repeated native socket payloads need only cold discovery stops,
with and without a guest argument-sensitive seccomp filter. Denials must still
leave the peer queue unchanged. This checks the mechanism, not a timing limit.
It additionally exercises Landlock's distinction between metadata and file-data
access, and repeated SIGKILL/exit_group while peers use active channels.
The native journal fixture stops real `openat`, `close`, `F_SETFD`, `dup`, `dup3`,
`F_DUPFD_CLOEXEC`, `epoll_ctl`, `epoll_pwait`, `sendto` and `recvfrom` at every
instruction around the effect, including failed open. It verifies the actual
FD table, ONESHOT event consumption, exactly-once datagram transfer and restart
classification. Entry tests additionally
hold 192 live opens, replace FD flags, use dup/close_range, replace/unlink a pinned
path, cancel a pending plan, exit before its effect and check exact inotify
open/close events. These complement the existing shared-table thread tests.
Writable tests check append/truncate, exact write-watch effects, pre-effect signal
cancellation, exhausted FD capacity and bounded creating-open resampling. The
inode fixture checks sealed-source COW and hardlink aliases, including explicit
write admission from a read window. Operation-window checks prove one gate and
generation read across native cycles, independent SQLite commit visibility,
rollback, live caller permissions, physical-directory reuse, exact birth
ownership and fresh external changes after release. Deterministic SIGKILL
tests also kill owners while their operation window is still held.
Another 2000 concurrent park/publication
checks cover both requests and completed native effects.
`--statistics` reports direct completions, declines and kernel notification wakes
separately. `cooperativeWakes` counts notification wakes, not direct queued
control hints or all cross-thread exchanges. The ready handshake enables resident
payload counters only in this mode; ordinary entry performs no atomic increment
for diagnostic completions or declines. Revocation and client ownership are
independent of instrumentation.
`cooperativeDescriptors` counts FD-request adoptions and
`cooperativeNativeYields` counts avoided idle spins. FD receipts also contribute
to `cooperativeCompleted`; that total is not an SVC-free syscall count.
`cooperativeControls` counts internal channel replies separately from syscall
completions. `MD_EXECUTION` reports task entries, retired calls, explicit kernel
promotions and journaled native actions. `MD_CHECKPOINT`, `MD_BUFFER` and
`MD_PTRACE` distinguish the remaining kernel-control costs. Use
`benchmark_readiness.py --statistics` for separate instrumented runs; ordinary
paired timing runs must omit that option.
Current device qualification and its scope are recorded in the
[task-channel acceptance](../guest-exec-lab/svc-rewriting.md#task-channel-acceptance).
`nativeOpen`, `nativeClose`, `nativeFlags`, `nativeDuplicate`, `nativeSeek` and
`nativeReadiness` count admitted native plans/flag
queries, not necessarily successful effects (a stop can cancel a plan).
`nativeDeferred` counts completed effects whose caller returned before the
worker's receipt; a concurrent 1000-iteration contract verifies that retirement
cannot erase the following shared-memory or kernel-carried request.
`test_cooperative_libc.py BUILD STOCK_FIXTURE --rounds 7` uses real libc calls, verifies
that enabled/disabled modes have different direct-completion counters, and compares
relative/absolute/dirfd stat, create/rename/unlink, readonly opens with/without
CLOEXEC, writable opens, existing-file `O_CREAT`, truncation and native IO with
rotating modes. `files` uses exclusive new-file creation; `files-create` uses
nonexclusive creation of a missing name. Neither file-cycle workload writes data.
`--proot-prefix` adds a hash-verified
PRoot control under the same shell UID, CPU mask and exact static musl fixture;
native and PRoot share the physical control directory. PRoot's seccomp accelerator
must be active. Its environment is clean, without disabling seccomp or preloads.
`--profile` records a separate post-series pair; it does not explain individual
timed samples. `--sample-statistics` instead attaches the complete profile to
each timed sample. Broker prepare/capture/execute/reply elapsed times include
descheduling; execution includes the engine's nested store costs. Do not sum
those nested values. Profiling clock samples and diagnostic counter increments are off
on ordinary launches. The fixture also reports guest CPU time and context
switches; `--scale` lengthens short workloads. `--reference-build` interleaves
another entry-enabled runtime against the same stock libc/store and records verified
binary hashes. Both routes must
pass their correctness and direct-completion assertions before timing; an ABI
mismatch silently falling back to syscalls is not a valid comparison.
`--reference-kernel` also interleaves the reference runtime with entry disabled,
so shared adapter changes are measured separately from cooperative acceleration.
`--busybox PATH` runs an unmodified musl-linked BusyBox through both modes in the
isolated store: shell pipelines, traversal, archive/restore, hashes, metadata,
hardlinks and symlinks.
`--condition` records charging or other session conditions.
Reports retain both mode medians and median within-round ratios; a ratio of
independent medians can mislead when storage latency changes during the series.
Imported and logically created directories are
separate `--directory-mode` workloads; their durability work is not interchangeable.
`test_watch_runtime.py BUILD --contracts`, resource `fs-buffer` checks
and both stock Chromium sandbox routes exercise unchanged entry paths.
Resource `fs-signals` isolates notification cancellation/restart stress; its
`MD_NOTIFICATION rebounds` counter distinguishes actual token replacement from
a successful run which did not encounter that race.
`readmissionsRetired` distinguishes completed-open handoff from a duplicate,
unexecuted admission. The runtime suite explicitly runs the broker-directory
contract, including deterministic live-token, identity mismatch, ioctl failure
and interrupted-token-check cases; a successful check never installs another FD.

The stock-libc runner measures real guest pointers, task identity, lifecycle
and completion ownership. See the
[measurement workflow](../guest-exec-lab/cooperative-entry.md).

No storage-format change, runtime dependency, ART integration or kernel newer
than 5.15 is required. Extending SVC-free execution to more syscall families and
delivering additional policy actions in userspace remain separate work.
