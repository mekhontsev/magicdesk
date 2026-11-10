# Guest Process Lifetime

The namespace launcher retains its filesystem service for the complete guest
tree, not merely the initial executable. This native ownership contract is
independent of Android terminal windows, graphical hosts and Desktop sessions.

## Owners

`namespace_run.c` creates a guest guardian. The native supervisor starts its
namespace worker after forking the guest, before acknowledging guest readiness.
The worker opens its store, image catalogue and RPC endpoint on its own thread.
An eventfd publishes readiness or an exact startup error; the 30-second limit
is a failure bound, not a delay. No SQLite connection is inherited by the guest.

`process_owner.c` implements the guardian as a Linux child subreaper. Headless
launches create a separate session; an inherited controlling PTY is retained so
the guest shell can own foreground job control. It starts the native syscall
supervisor, which traces and reaps its own guest tree. The namespace worker is
part of that supervisor, not a child counted by the guardian's
`wait4(..., __WALL)` quiescence check. The root guest's exit
status is retained, but returned only after `ECHILD` proves that the entire tree
has ended. Background processes may intentionally keep a launch alive indefinitely.
The initial headless guest has a separate process group from its guardians, so
an application's group-wide shutdown does not cancel supervision. A controlling
PTY retains the caller's foreground group and ordinary shell job control.

The supervisor retains the namespace worker until tracees have been reaped.
Shutdown signals its stop eventfd and joins it before freeing request storage;
a ten-second failure bound terminates the supervisor instead of freeing live
worker memory. Supervisors close unrelated inherited descriptors before starting
the worker; only the guest retains caller-supplied application FDs.

The frontend catches cancellation with signalfd and forwards it to the guardian.
A parent-death signal and parent-PID race check also cover frontend SIGKILL:
the guardian remains alive to drain the tree and namespace owner. Worker failure
wakes the supervisor through an eventfd. Unexpected supervisor death cancels
its remaining descendants and reports supervision failure.

## Cancellation

`task_wait.c` owns nonblocking trace-event collection. SIGCHLD and recent TIDs
are bounded hints, not proof of readiness. In larger trees a separate round-robin
cursor checks one member on two of every three discovery passes. This bounds
discovery of unnotified stops without requiring an idle probe on every pass.
General waits collect a bounded batch before any of its tasks resumes; buffered
statuses transfer exactly once, including during cancellation. Only an empty
general wait establishes a drained queue, invalidated by SIGCHLD. Buffered
delivery needs no extra signal read; the shared event loop still services all
signal and resource sources within its progress budget.

Pending selector installations use the shared intrusive continuation queue,
not a scan of all tracees. Completion and cancellation remove their owned
nodes; callbacks can cancel peers or queue a later pass. Temporary descriptor
retirements retain their separate custody list and scheduling priority.

Supervisors use signalfd, pidfds and ppoll. The caller's original signal mask and
watched dispositions are restored in the guest before exec. There are no
signal-handler locks or periodic process scans. The syscall supervisor maintains
explicit thread/address-space records on clone/exec/exit events; waiting itself
does not allocate per poll iteration.
Guest `CLONE_UNTRACED` suppresses automatic attachment by a guest debugger,
not runtime supervision. The native clone omits that flag while the retained
guest flags still control debugger birth publication and resource inheritance.
`event_wait.c` shares freestanding monotonic descriptor waits with filesystem RPC;
deadlines remain absolute across EINTR.
Readiness snapshots are consumed before their callbacks retire descriptor owners.
The supervisor rebuilds its wait set after dispatch, so bootstrap socket HUP
cannot leave a closed descriptor in the next wait for child exit.

Cancellation sends SIGTERM followed by SIGCONT to owned children. After a
two-second grace bound it escalates to SIGKILL, with ten seconds for reaping.
Adoption and exit events drive further passes. Double-fork, setsid and changed
process groups do not remove ancestry.

Only cancellation enumerates `/proc`. `waitid(P_PIDFD)` proves direct-child
ownership before signalling each candidate; a matching UID or name is never
sufficient. Pidfds exclude PID reuse. The optional `/proc/.../children` interface
is not used. A scan never proves quiescence; only the final wait result does.

The frontend returns the root application's status after normal completion,
`128 + signal` for requested cancellation, and 125 for supervision/service failure.
The guardian records the supervisor's original fatal signal or failure status
before normalizing that result to 125.
A cleanup deadline expiring is failure, not successful termination. Service
shutdown has its own bounded join and process-failure path.

## Robust Mutex Ownership

User and logical PID namespaces use managed `set_robust_list`/`get_robust_list`:
the owner TID in a guest mutex need not match the native TID, and Android may
deny native registration. Shell outside a logical PID namespace keeps the native
kernel path. Original guest seccomp admission and process-inspection checks
still apply. Ordinary futex waits and wakes are not intercepted for this feature;
priority inheritance is a separate unsupported contract in logical PID views.

One private `task_retained` actor per participating address space retains the old
mm across thread exit, SIGKILL and exec, including exec from a nonleader. The
supervisor's retained-memory owner shares this actor between managed robust
cleanup and private-memory repair. It is created on demand, not for ordinary
Shell futex operations or native mappings. It is not a guest thread and has no
guest stack, TLS or shared descriptor table.
`robust_futex` bounds list traversal to 2048 nodes; `task_robust` yields after
32 read steps, atomically replaces the matching owner word with OWNER_DIED
and wakes a native waiter. Invalid lists and inaccessible words terminate their
cleanup without invoking a guest SIGSEGV handler. The supervisor owns queued
terminal events and replays each once cleanup finishes. Only pending mm owners
enter its work queue; private actor lookup uses a PID index rather than a
scan on every syscall.
Registration, cleanup and pending terminal replay retain exclusive custody of
the guest continuation. The shared task-action admission excludes that task
from filter installation, FD capture and temporary-FD retirement, even when
it originated the request. Its files remain live until normal exit processing;
another eligible table holder may still retire the descriptor.
Resource retries and filesystem completions use the same continuation gate;
terminal replay reopens completion dispatch only after normal lifecycle handling.
Deferred FIFO-open children also publish through that completion gate. Reaping
their exact PID retains the result without invoking a held caller; cancellation
can release it without waiting for caller admission.

Guest proc/debugger inventories exclude private actors. The common
[wait owner](debugging.md#waits-and-process-identity) filters them only when they
would match an application's wait. The kernel remains authoritative for real
children, wait classes, thread-specific waits and WNOWAIT.

After the final mm member detaches, `task_child_reap` retires the exact private
PID and preserves real or directed SIGCHLD. Retirement acquires its memory turn
and waits for existing helper and exit owners before stopping the real parent
group and its direct children; unrelated processes remain runnable. A resumed
exit, including a zombie leader awaiting its other threads, cannot acknowledge
a new ptrace interrupt. Retirement must not stop those remaining threads while
waiting for that leader's final event. Creation and retirement
share quiescence admission with mapping repair, policy changes and task-affine
calls. Deferred memory events retain their exact stop and terminal-event owner.
A parked signal wait lends its native continuation without transferring or
resetting the outer checkpoint ownership. A parent exit/exec cancels the borrowed
context and returns the terminal event to its owner, not to stale registers. Adoption
transfers reaping to the next real parent or the supervisor. This does not add
ordinary read/write interception, periodic scans or synthetic child notices.

`test_thread_identity_runtime.py --case robust` tests ten cases both directly
and in a logical PID namespace with stock glibc/musl. `--native-control` compares
the same static fixture outside Shroot. On the qualified 6.12.23 device, native
nonleader exec does not publish owner death for the tested process-shared mutex;
the native Shell path retains this limitation, while managed cleanup passes.
The `robust-futex` component suite additionally covers private-child birth,
reaping, blocked wait/signalfd readers, coalesced/directed notifications,
SA_NOCLDWAIT and cleanup faults. Current receipts are in
[host execution](../guest-exec-lab/host-execution.md#robust-mutex-qualification).

## Verification And Limits

`test_lifecycle.c` builds an Android/Bionic driver and a Debian/glibc guest fixture.
The driver observes the executor independently under UID 2000. SCM_RIGHTS passes
pidfds for exact exit checks; socket and child-stop handshakes establish checkpoints
without sleeps. Tests cover double-fork/setsid descendants using the namespace
after root exit, root exit/signal status, concurrent launches, rapid orphaning,
ignored SIGTERM, acknowledged SIGSTOP, escalation, frontend/namespace-owner SIGKILL,
failed launches, unrelated-process isolation, descriptor release and final ECHILD.
Capability fixtures also exercise repeated bootstrap admission failures; missing
guest-only kernel operations must not become supervisor or shared-shell failures.

Coverage is NX809J / API 36 / Linux 6.12.23 under actual UID 2000. Other kernels,
Android releases and SELinux policies need verification. This is not cgroup
containment: hostile guests, killing the guardian itself, uninterruptible kernel IO,
resource exhaustion and deliberate ancestry escape are not guaranteed to clean up.
Failures never trigger broad UID/process-group kills or privilege escalation.
Terminal ownership remains in the shared PTY service. Detaching its Android
window does not cancel a launch; explicitly closing its PTY does.

The installed CLI was exercised in the shared terminal on an ordinary virtual
display: guest tty identity, interactive job control, Ctrl+Z, foreground resume,
Ctrl+C, detached-window execution and return to the Android shell after guest
exit. These checks do not establish arbitrary shells' job-control compatibility.

References: [subreapers](https://man7.org/linux/man-pages/man2/PR_SET_CHILD_SUBREAPER.2const.html),
[pidfd-aware waits](https://man7.org/linux/man-pages/man2/waitpid.2.html),
[parent-death signals](https://man7.org/linux/man-pages/man2/PR_SET_PDEATHSIG.2const.html),
[proc children limitations](https://man7.org/linux/man-pages/man5/proc_tid_children.5.html).
