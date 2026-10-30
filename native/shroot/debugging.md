# Guest Debugging

- [Ownership And Admission](#ownership-and-admission)
- [Stops And Events](#stops-and-events)
- [Waits And Process Identity](#waits-and-process-identity)
- [Native Observation And Cleanup](#native-observation-and-cleanup)
- [Limitations](#limitations)
- [Regression Coverage](#regression-coverage)
- [Tool Compatibility](#tool-compatibility)

## Ownership And Admission

The launch supervisor remains the only kernel ptracer. `guest_debugger` models
an explicitly requested guest debugger/inferior relationship inside that launch;
it never attaches to Android processes or transfers the supervisor's authority
to the debugger. Ordinary execution allocates no inferior or debugger-wait records.

`PTRACE_TRACEME` selects the guest parent; `ATTACH` and `SEIZE` select an existing
inferior in the same supervised launch. Requests validate launch membership,
guest real/effective/saved UID and GID, the debugger's domain and the kernel's
remote-memory permissions. A traced image cannot gain admitted set-ID credentials
on exec. Register and memory access requires a stopped, owned inferior.
An established relationship survives the inferior's UID/GID changes: register
inspection, continuation and detach check the existing owner, not new-attach
permission. Memory peek/poke retains its independent permission check and
native kernel access ceiling.

Exec admission always selects the distribution's original `PT_INTERP`.
Stock `libthread_db` matches libc's private thread layout and realtime signal
handling. Debugger ownership revokes lazy SVC entry in the inferior; other
untraced processes retain their normal admission. This is shared by all
distributions and needs no GDB wrapper or sandbox override.

The supervisor retains interception, image admission and process lifetime.
Guest stops and kernel stops are distinct: bootstrap setup and adapter instructions
are not presented as application breakpoints. The image-entry event publishes the
guest ELF entry, stack and process-image snapshot before debugger notification.
`process_image_view` supplies another guest's executable, initial argv and auxv
through the same authority check; arbitrary foreign proc paths are not exposed.
Executable readlink returns bounded bytes in the existing mapped response slot,
not a temporary guest file. Full descriptor tables and protected callers retain
the same output truncation and rejected-copy behavior.

## Stops And Events

Supported requests include register sets, memory peek/poke, signal information
and masks, continue, single step, syscall entry/exit information, interrupt,
listen, detach, kill, event messages and fork/vfork/clone/exec/exit options.
Attach/interrupt stops are published at a guest boundary, outside adapter code.
An inferior's unmaskable SIGSTOP also waits for that boundary, after its internal
file-table and filesystem turns finish. Its original siginfo belongs to the
debugger relationship; suppressing the stop does not group-stop the application
or expose adapter registers. Untraced processes retain kernel job control.
Stop siginfo remains native while the debugger owns the stop. Both ordinary
continuation and debugger continuation use the same final-delivery owner for
recipient PID/UID translation and PID-1 signal policy. Debugger observation uses
the debugger's view separately. This preserves libc's thread-directed set-ID
handshake under strace across PID namespaces, including nondumpable targets;
signal-free resumes do not add a siginfo query.
Admitting a trace relationship activates observation in the owner's whole thread
group. A native wait entered before that relationship is interrupted and restarted
through the same debugger-aware wait path. This also covers a child requesting
TRACEME after its parent, or another parent thread, has already blocked in wait4
or waitid with SIGCHLD masked. Activation is relationship-driven; ordinary
launches do not acquire extra stops or periodic checks.
The wait status and siginfo describe the same event; internal bootstrap execs
are not separate guest exec events. Non-leader inferior exec retains its former
TID in the exec event while transferring the relationship to the surviving PID.
Descriptor-operation completion during ELF bootstrap remains internal. A traced
guest exec publishes its image-entry event before its syscall-exit stop; loader
FD operations cannot complete that pending guest call.

Syscall and single-step observation resume only at the shared guest boundary,
after metadata replay and other internal owners have restored the caller.
An internal return checkpoint is not a guest syscall, even when the adapter
phase itself is idle. Pending signal carriers wait for the native syscall-exit
record; restored signal-delivery stops then pass through ordinary debugger
admission before recipient-namespace translation. Their restored provenance
also prevents another interrupted-syscall probe: on nondumpable tasks that
probe itself needs a guest memory-read helper and must not defer the same
signal indefinitely.

## Waits And Process Identity

Guest wait4 and waitid deliver queued trace stops in event order, with rusage
and waitid WNOWAIT support. Mixed traced/untraced waits first check the native
child queue without blocking. Only that short probe defers asynchronous handlers;
it must not mistake a handler's syscall for the requested wait. With no ready
result, the calling guest thread waits in an interruptible empty native poll,
not a ptrace stop. Inferior events interrupt that exact wait before register or
event consumption; a wake racing poll entry cancels entry instead. No polling
timer, helper thread or additional descriptor is allocated. Guest signals use
the shared syscall-restart path: the original wait number, arguments and kernel
orig_x0 are restored before the kernel decides EINTR or SA_RESTART. The old wait
record is released before a handler can issue another wait. If no virtual
children remain, a blocking wait returns to the kernel with ordinary signal
semantics. The debugger's own syscall filters also apply to the wait carrier;
a denied operation remains an error. Kernel parenthood retains real-child exit
reaping; traced nonchildren have supervisor-owned exit
records. Event waits end on a trace event, owned child exit or launch cancellation,
not a settling delay. Nonleader thread termination belongs to the virtual trace
relationship: the native parent cannot reap it as an ordinary child of the
debugger while the supervisor remains the kernel ptracer. Only a process leader
with actual parenthood can use native child reaping.

A debugger may share a descriptor table with its inferior through `CLONE_FILES`,
as crash collectors do. Publishing a virtual descriptor must install its selector
in every holder, including stopped inferiors. The existing task-call machinery
may borrow an acknowledged guest-boundary stop only for a parked request from
that inferior's exact debugger TID. It restores the native continuation and signal
mask before completing the request; the guest debugger retains its original stop,
registers and siginfo. This neither runs inferior user code nor publishes internal
stops. Unrelated debugger ownership, pending stop delivery and active adapter work
remain ineligible. Ordinary launches gain no additional interception or polling.

Native-only poll/select/epoll fallback also leaves the adapter before waiting.
An inherited readiness selector can still intercept these calls after its last
virtual descriptor has closed. `MD_CALL_NATIVE` uses the existing continuation
owner to restore the caller's signal mask before entering the kernel; waiting
inside the shielded adapter would defer SIGCHLD indefinitely and strand GDB on
an inferior's fork. Ordinary unselected native waits retain their original path.
EXITKILL and stopped-inferior release belong to debugger
lifetime. Records are released when consumed or their owner exits.
Invalid wait flags are rejected before consuming an event. waitid siginfo includes
the inferior's logical UID and distinguishes normal, killed and core-dumped exits.
PTRACE_GET_SYSCALL_INFO exposes entry/exit records only with TRACESYSGOOD; short
buffers retain the kernel's full-size return convention.
NONE records still contain architecture, instruction and stack pointers.
The syscall fixture varies application x7 while inspecting native proc/dev and
logical store paths, requiring stable metadata and unchanged missing-path errors.
Unrelated application registers cannot select filesystem or network policy.
Newborn inferiors begin with their own first syscall entry, not a synthetic
exit of the parent's clone. ARM64 regsets retain the kernel entry/exit marker
in x7 while presenting the guest's registers.

Debugger targets, wait results and fork/clone/exec event messages use the
observer's PID namespace. Nonleader exec captures its former visible TID before
retiring that identity. Native parent observation accounts for orphan adoption;
it is not fixed at clone time. Terminal wait consumption releases retired PID
lookup state without revoking an application's retained pidfd.
Debugger exit notifications and real-parent reaping have separate ownership.
The debugger retains its visible number until its own notification is consumed;
consuming that notification does not remove the real parent's pending result.

## Native Observation And Cleanup

The kernel TRACEEXIT option serves two independent owners: requested inferior
events and runtime terminal descriptor publication. A task's first internal
O_PATH capture enables the latter once for its lifetime; debugger detach cannot
revoke it. Only requested events enter the guest debugger queue. Syscall stepping
observes only explicit syscall-traced inferiors and active debugger owners
(including their native waitid calls). Ordinary launches do not acquire debugger
per-syscall stepping, rusage snapshots, polling or wait records. Their internal
terminal observation is described in [interception](interception.md).
Aggregate `--statistics` counters can verify these boundaries.

ESRCH during a ptrace request invalidates that stop, not a subsequent kernel
exit event. A confirmed terminal stop permits final descriptor publication and
detaches buffered work, then resumes only toward reaping. It cannot reenter an
adapter, syscall step or guest breakpoint. Pending worker storage remains pinned
until completion; task ownership ends at the actual exit/exec event. Fatal
supervisor cleanup also continues owned terminal stops after SIGKILL, rather
than waiting indefinitely for an exit that still needs ptrace continuation.

## Limitations

This is a bounded nested-debugging implementation, not complete ptrace emulation.
Attach cannot cross supervised launches or target Android processes. TRACESECCOMP,
pidfd-based virtual waits and __WNOTHREAD wait selection are not implemented.
Non-leader debugger exec, as distinct from inferior exec, needs dedicated lifecycle coverage.
Unsupported ptrace requests remain explicit errors; the supervisor never
relinquishes its physical tracing ownership.

## Regression Coverage

The development fixture runs stock Ubuntu GDB 15.1 with breakpoints, source
stepping, locals, backtraces, a thread breakpoint, signal delivery, fork detachment,
child exec and normal exit. Reading symbols alone is not a passing debugger test.
Raw-instruction stepping and unsupported-request coverage remain separate work.

`test_thread_identity_runtime.py --debuggers` checks recursive pthread mutexes,
shared-memory clone TID publication and child clearing under stock strace and
GDB in a PID namespace, including nondumpable callers. It requires both fixture
completion and normal debugger exit.

`test_debugger_runtime.py` adds identical Shroot/PRoot attach, seize, interrupt,
exit-event, syscall-information, mixed-wait, debugger-death, EXITKILL and
non-leader inferior-exec checks. Real-tool scenarios verify GDB sibling attach
with a memory write and detach, including a 41-thread target, and repeated GDB
breakpoints while eight peer threads create files, write and read data, reopen
with O_NOATIME, rename and unlink. Interrupted FD admission retains the created
file and resumes only descriptor delivery, including a restart overtaking an
expired queued admission. They also cover
LLDB source stepping, GDB through gdbserver, strace following a child, gprof
function output and Callgrind instruction counts.
The `shared-files-debugger` case stops two `CLONE_FILES` children, then opens
virtual proc and inotify descriptors in their debugger. It requires unchanged
registers, siginfo and masks, no extra wait events, successful detach and normal
child exits. The same fixture also passes natively under Android's shell identity.
The `task_quiesce` and `fd_filter_remote` fixtures cover the underlying borrowed-stop
restoration, protected targets, restart boundaries and rejected seccomp operations.
The `syscall-exec` case requires paired entry/exit stops with the exec event
between them and a successful exec result. The strace workflow checks decoded
open arguments and child creation, and rejects stray exec-event recovery.
The `strace-detach` case attaches to 41 idle threads, observes a stable wait,
sends SIGINT and requires prompt tracer exit without killing the target. The
target must then accept input, release its workers and exit normally.
`gdb-attach-fork` inherits an admitted readiness selector, attaches to 41 threads,
waits for GDB's idle native poll, then requires four fork/exec detachments and
normal target/debugger exit. `--machine-entry --pid-namespace` checks the
same workflow through lazy entry inside a guest PID namespace; `--base` is the
explicit control. The
readiness fixture independently checks unmasked signal interruption of all four
native wait variants after selector admission, including filtered launches.
The perf capability check opens a task-clock event and requires a positive
counter; a kernel permission denial is recorded as unavailable, not a successful
profile or a runtime workaround.

`test_kernel_contract.py` runs one static binary directly under shell and inside
Shroot on the same kernel. It compares waitid WNOWAIT, invalid waits, late TRACEME
while the parent or another parent thread is in a native wait, ptrace
option errors, syscall-info sizing and signal suppression/replacement,
wait4/waitid signal interruption and SA_RESTART with SIGCHLD masked, plus
descriptor lifetimes and scoped pathname resolution under concurrent rename.
Unavailable native hardlink creation is reported separately; the guest must
execute that subcheck, not silently omit it. ELF fixtures independently check
malformed layouts, 4/16 KiB preflight and mapped BSS zeroing. Synthetic 16 KiB
preflight is not device coverage.
The PID resource fixture also checks nonleader exec event identity inside a
child namespace. Stock Debian GDB 16.3 runs starti/continue/exit in Bubblewrap
with a mounted logical proc view; a missing foreign proc mem path uses GDB's
ptrace fallback, not a fabricated readable file.

The resource suite also races exit_group against buffered O_PATH/getcwd in
24 groups of eight peers, including nondumpable callers. Pipe acknowledgements
establish that each peer entered the runtime before group exit; there are no
timed settling delays. Terminal-stop diagnostics distinguish recovery from a
lost stop, without treating that scheduling-dependent race as exhaustive coverage.
Separate parked-group cases require the supervisor's deadline failure and
completed cleanup for both ordinary and protected callers. The outer timeout
must not fire; a fixture timeout is not accepted as successful reaping.

## Tool Compatibility

On the tested NX809J/API 36 kernel 6.12, Shroot under UID 2000 passes these
workflows with Ubuntu GDB 15.1, LLDB 18.1.3, strace 6.8 and Valgrind 3.22.
Debian GDB 16.3 also completes sibling attach, all-thread backtraces and detach
for Blender with a rendered viewport and after a forced X11 client disconnect.
The gdbserver workflow uses an explicitly selected Debian 16.3 binary. Ubuntu's
gdbserver 15.1 hits the same ARM64 SVE register assertion in both Shroot and PRoot;
the corresponding [upstream fix](https://gnu.googlesource.com/binutils-gdb/+/1137625d46f3840dd7cb7f8519ec9dcba0acd53e)
is in 16.3. The runtime does not mask CPU features to accommodate that tool bug.

Termux PRoot 5.1.107.92 passes basic attach, debugger-owner exit, ordinary GDB,
LLDB, gdbserver 16.3, strace, gprof and Callgrind on the same device. Its tested
seize/interrupt, syscall-information, mixed-wait, EXITKILL, non-leader inferior-exec
and GDB sibling-attach workflows fail or time out. The task-clock perf event is
available to shell/Shroot but denied to Termux/PRoot. These observations cover
the recorded tool versions and scenarios, not every debugger or kernel.
