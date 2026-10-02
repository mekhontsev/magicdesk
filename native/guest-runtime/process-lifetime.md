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

Supervisors use signalfd, pidfds and ppoll. The caller's original signal mask and
watched dispositions are restored in the guest before exec. There are no
signal-handler locks or periodic process scans. The syscall supervisor maintains
explicit thread/address-space records on clone/exec/exit events; waiting itself
does not allocate per poll iteration.
`event_wait.c` shares freestanding monotonic descriptor waits with filesystem RPC;
deadlines remain absolute across EINTR.

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
A cleanup deadline expiring is failure, not successful termination. Service
shutdown has its own bounded join and process-failure path.

## Verification And Limits

`test_lifecycle.c` builds an Android/Bionic driver and a Debian/glibc guest fixture.
The driver observes the executor independently under UID 2000. SCM_RIGHTS passes
pidfds for exact exit checks; socket and child-stop handshakes establish checkpoints
without sleeps. Tests cover double-fork/setsid descendants using the namespace
after root exit, root exit/signal status, concurrent launches, rapid orphaning,
ignored SIGTERM, acknowledged SIGSTOP, escalation, frontend/namespace-owner SIGKILL,
failed launches, unrelated-process isolation, descriptor release and final ECHILD.

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
