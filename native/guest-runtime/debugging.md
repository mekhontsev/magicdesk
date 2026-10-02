# Guest Debugging

The launch supervisor remains the only kernel ptracer. `guest_debugger` models
an explicitly requested guest debugger/inferior relationship inside that launch;
it never attaches to Android processes or transfers the supervisor's authority
to the debugger. Ordinary execution allocates no inferior or debugger-wait records.

`PTRACE_TRACEME` selects the guest parent. Requests validate launch membership,
guest real/effective/saved UID and GID, the debugger's domain and the kernel's
remote-memory permissions. A traced image cannot gain admitted set-ID credentials
on exec. Register and memory access requires a stopped, owned inferior.

The supervisor retains interception, image admission and process lifetime.
Guest stops and kernel stops are distinct: bootstrap setup and adapter instructions
are not presented as application breakpoints. The image-entry event publishes the
guest ELF entry, stack and process-image snapshot before debugger notification.
`process_image_view` supplies another guest's executable, initial argv and auxv
through the same authority check; arbitrary foreign proc paths are not exposed.

Supported requests include register sets, memory peek/poke, signal information,
continue, single step, detach, kill, event messages and fork/vfork/clone/exec
options. Guest wait4 delivers queued trace stops in event order. Kernel parenthood
retains real-child exit reaping; traced nonchildren have supervisor-owned exit
records. Event waits end on a trace event, owned child exit or launch cancellation,
not a settling delay. EXITKILL and stopped-inferior release belong to debugger
lifetime. Records are released when consumed or their owner exits.

This is a bounded nested-debugging implementation, not complete ptrace emulation.
Attach/seize, syscall tracing, TRACEEXIT, virtual waitid and wait4 rusage are not
implemented. Mixed traced/untraced wait sets and non-leader debugger exec need
dedicated lifecycle coverage before claiming general debugger compatibility.

The development fixture runs stock Ubuntu GDB 15.1 with breakpoints, source
stepping, locals, backtraces, a thread breakpoint, signal delivery, fork detachment,
child exec and normal exit. Reading symbols alone is not a passing debugger test.
Raw-instruction stepping and unsupported-request coverage remain separate work.
