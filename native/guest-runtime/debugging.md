# Guest Debugging

The launch supervisor remains the only kernel ptracer. `guest_debugger` models
an explicitly requested guest debugger/inferior relationship inside that launch;
it never attaches to Android processes or transfers the supervisor's authority
to the debugger. Ordinary execution allocates no inferior or debugger-wait records.

`PTRACE_TRACEME` selects the guest parent; `ATTACH` and `SEIZE` select an existing
inferior in the same supervised launch. Requests validate launch membership,
guest real/effective/saved UID and GID, the debugger's domain and the kernel's
remote-memory permissions. A traced image cannot gain admitted set-ID credentials
on exec. Register and memory access requires a stopped, owned inferior.

The supervisor retains interception, image admission and process lifetime.
Guest stops and kernel stops are distinct: bootstrap setup and adapter instructions
are not presented as application breakpoints. The image-entry event publishes the
guest ELF entry, stack and process-image snapshot before debugger notification.
`process_image_view` supplies another guest's executable, initial argv and auxv
through the same authority check; arbitrary foreign proc paths are not exposed.

Supported requests include register sets, memory peek/poke, signal information
and masks, continue, single step, syscall entry/exit information, interrupt,
listen, detach, kill, event messages and fork/vfork/clone/exec/exit options.
Attach/interrupt stops are published at a guest boundary, outside adapter code.
Admitting a trace relationship activates observation in the owner's whole thread
group. A native wait entered before that relationship is interrupted and restarted
through the same debugger-aware wait path. This also covers a child requesting
TRACEME after its parent, or another parent thread, has already blocked in wait4
or waitid with SIGCHLD masked. Activation is relationship-driven; ordinary
launches do not acquire extra stops or periodic checks.
The wait status and siginfo describe the same event; internal bootstrap execs
are not separate guest exec events. Non-leader inferior exec retains its former
TID in the exec event while transferring the relationship to the surviving PID.

Guest wait4 and waitid deliver queued trace stops in event order, with rusage
and waitid WNOWAIT support. Mixed traced/untraced waits first check the native
child queue without blocking. Only that short probe defers asynchronous handlers;
it must not mistake a handler's syscall for the requested wait. If no virtual
children remain, a blocking wait returns to the kernel with ordinary signal
semantics. Kernel parenthood
retains real-child exit reaping; traced nonchildren have supervisor-owned exit
records. Event waits end on a trace event, owned child exit or launch cancellation,
not a settling delay. EXITKILL and stopped-inferior release belong to debugger
lifetime. Records are released when consumed or their owner exits.
Invalid wait flags are rejected before consuming an event. waitid siginfo includes
the inferior's logical UID and distinguishes normal, killed and core-dumped exits.
PTRACE_GET_SYSCALL_INFO exposes entry/exit records only with TRACESYSGOOD; short
buffers retain the kernel's full-size return convention.

The kernel TRACEEXIT option is enabled only for inferiors requesting it. Syscall
stepping observes only explicit syscall-traced inferiors and active debugger
owners (including their native waitid calls). Ordinary launches do not acquire
per-syscall stepping, additional stop events, rusage snapshots, polling or
debugger wait records. Aggregate `--statistics` counters can verify that boundary.

This is a bounded nested-debugging implementation, not complete ptrace emulation.
Attach cannot cross supervised launches or target Android processes. TRACESECCOMP,
pidfd-based virtual waits, __WNOTHREAD wait selection and interruption of a parked
virtual wait by an arbitrary signal are not implemented. Non-leader debugger
exec, as distinct from inferior exec, needs dedicated lifecycle coverage.
Unsupported ptrace requests remain explicit errors; the supervisor never
relinquishes its physical tracing ownership.

The development fixture runs stock Ubuntu GDB 15.1 with breakpoints, source
stepping, locals, backtraces, a thread breakpoint, signal delivery, fork detachment,
child exec and normal exit. Reading symbols alone is not a passing debugger test.
Raw-instruction stepping and unsupported-request coverage remain separate work.

`test_debugger_runtime.py` adds identical Shroot/PRoot attach, seize, interrupt,
exit-event, syscall-information, mixed-wait, debugger-death, EXITKILL and
non-leader inferior-exec checks. Real-tool scenarios verify GDB sibling attach
with a memory write and detach, including a 41-thread target, LLDB source stepping,
GDB through gdbserver, strace following a child, gprof function output and
Callgrind instruction counts.
The perf capability check opens a task-clock event and requires a positive
counter; a kernel permission denial is recorded as unavailable, not a successful
profile or a runtime workaround.

`test_kernel_contract.py` runs one static binary directly under shell and inside
Shroot on the same kernel. It compares waitid WNOWAIT, invalid waits, late TRACEME
while the parent or another parent thread is in a native wait, ptrace
option errors, syscall-info sizing and signal suppression/replacement, alongside
descriptor lifetimes and scoped pathname resolution under concurrent rename.
Unavailable native hardlink creation is reported separately; the guest must
execute that subcheck, not silently omit it. ELF fixtures independently check
malformed layouts, 4/16 KiB preflight and mapped BSS zeroing. Synthetic 16 KiB
preflight is not device coverage.

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
