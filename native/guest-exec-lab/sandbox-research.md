# Seccomp And Ptrace Controls

These native fixtures isolate kernel behavior under the selected shell executor.
They are not a second production backend or a browser-certification suite.
Production Shroot uses selective TRACE/USER_NOTIF interception; application
SIGSYS handlers and alternate stacks belong to the application. See the
[interception contract](../shroot/interception.md) and
[production browser workflows](browser/README.md).

- [Reproduction](#reproduction)
- [Policy Boundaries](#policy-boundaries)
- [Userspace Policy Cost](#userspace-policy-cost)
- [Selective Ptrace Hybrid](#selective-ptrace-hybrid)
- [Signal-Neutral Checkpoints](#signal-neutral-checkpoints)
- [Real Browser Boundaries](#real-browser-boundaries)
- [Primary References](#primary-references)

## Reproduction

Use a disposable prepared glibc store and an immutable staged runtime:

```sh
sh native/guest-exec-lab/build-sandbox-fixtures.sh BUILD
python native/guest-exec-lab/test_sandbox.py BUILD \
  --runtime IMMUTABLE_STAGED_HELPERS --store DISPOSABLE_GLIBC_STORE
sh native/guest-exec-lab/build-hybrid-fixture.sh BUILD
python native/guest-exec-lab/test_hybrid.py BUILD --runs 50
```

The runners record device/kernel identity, actual UID, helper hashes, output
and cleanup. Synthetic-policy controls affect owned child processes only.
A completed observation suite is not a passing arbitrary-browser workload.
`test_notifications.py` supplies separate notification-lifecycle controls.

## Policy Boundaries

| Control | Meaning |
| --- | --- |
| TRAP versus ERRNO; multiple TRAP filters | Kernel action precedence and data-cookie selection are not a runtime policy choice |
| USER_NOTIF with application ERRNO/TRAP/KILL | A denial must win without executing the external operation |
| Listener loss | Notified calls fail; they must not become unmediated host operations |
| Cooperative SIGSYS and blocked SIGSYS | Signal ownership, alternate stacks and masks are part of the application ABI |
| Replay at a different instruction address | Instruction-pointer filters can produce a different decision |
| Native namespace creation | Host capability evidence is separate from logical Shroot namespaces |
| Direct filesystem RPC | An external broker is an authority boundary, not automatically confined by its caller's seccomp filter |

Do not use observations from the in-process TRAP controls as claims about the
current runtime. In particular, reserving SIGSYS/altstack and bypassing an
application's open denial are not its supported execution contract.

Copied arguments, thread identity, filter inheritance and original call-site
semantics matter independently. A guest-writable secret or caller-supplied
syscall/PC is not trusted authorization. An external broker must also account
for alternate RPC paths, inherited descriptors and raw host access; successful
filter installation alone does not establish complete confinement.

## Userspace Policy Cost

`test_seccomp_entry` compares installed kernel policies with workload-scoped
userspace evaluation through the shared `seccomp_program` interpreter and
read-only `md_seccomp_view`. The installed-policy mode exercises production
publication and lazy SVC; the explicit userspace mode is a prototype limited to
the measured calls, not a process-wide sandbox. Neither measures browser speed.

Build with `MAGICDESK_GUEST_FIXTURES=ON`, then supply two raw AArch64
`sock_filter` arrays captured from guest `seccomp`/`prctl` installation:

```sh
cmake --build BUILD --target test_seccomp_entry
python native/guest-exec-lab/test_seccomp_entry.py BUILD FILE_FILTER FD_FILTER \
  --rounds 5 --executor shell
python native/guest-exec-lab/test_seccomp_entry.py APK_BUILD FILE_FILTER FD_FILTER \
  --rounds 5 --executor app
```

The App executor requires a fixture APK containing the exact helpers in
`APK_BUILD`. Inputs must permit the measured operations: `FILE_FILTER` allows
`newfstatat`, `openat` and `close`; `FD_FILTER` allows `fstat` and `close`.
Both must deny `renameat` with ERRNO. The runner records their SHA-256 hashes.
Do not infer a browser process role from a filter's filename.

Each launch installs the exact filter in a disposable child as a kernel oracle.
The child checks ERRNO and, when selected by the input policy, TRAP from
`process_vm_readv`, including original PC, syscall number, architecture and
data. The userspace path independently checks the rename denial without
executing it. Unsupported userspace actions fail explicitly, never become ALLOW.

The same timed loops compare native/kernel-filter, Base/kernel-filter,
lazy/kernel-filter, lazy/userspace-policy and lazy/no-policy modes. The last
two are deliberately limited to the measured calls and the denial control;
libc startup and unrelated calls are **not** covered by a virtual guest filter.
Kernel-filter modes install the real policy and exercise production admission,
including eligible direct entry after publication.
Separate instrumented controls check actual cooperative completions; timing
runs omit instrumentation. Rounds alternate forward/reverse mode order,
with CPU-frequency evidence outside each command and no affinity/frequency lock.
Native here means an Android native fixture, not a prepared chroot benchmark.

Measured on NX809J, Android 16 / Linux 6.12, with Chromium 149.0.7827.53
filters captured from a stock headless Alpine launch: `2520a873c204` (627
instructions, file operations) and `6fb69b9aeec5` (655 instructions, FD-only
workload). Values are three-round medians in milliseconds; CPU frequencies varied
and the raw reports retain the observations. Do not infer small executor or
policy-overhead differences from these samples.

| Executor | Workload | Native + kernel filter | Base + kernel filter | Lazy + installed policy | Lazy without guest filter | Userspace prototype |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Shell | 32768 path stats | 67.4 | 1475.5 | 150.9 | 154.8 | 155.7 |
| Shell | 4096 open/close pairs | 19.0 | 669.0 | 63.0 | 65.9 | 69.7 |
| Shell | 32768 FD stats | 21.5 | 1219.5 | 76.5 | 78.5 | 78.0 |
| User | 32768 path stats | 69.2 | 1454.0 | 145.2 | 121.0 | 171.4 |
| User | 4096 open/close pairs | 15.3 | 600.3 | 83.8 | 44.0 | 68.0 |
| User | 32768 FD stats | 10.9 | 1281.3 | 74.5 | 88.5 | 81.7 |

Both identities passed 12 routing controls and 45 timed samples, including
lazy/no-policy controls. Reports live under `build/seccomp-entry-results`;
their IDs are `495f84bfa6af4c05aa15ea8d9b66d538` (Shell) and
`4bf747776a154aba97e113b6511d274b` (User). No other test or build ran alongside
these series. The installed-policy path is 7.2-17.2 times faster than Base in
these samples. This supports a large reduction in interception overhead, not
a claim about browser page speed or negligible filter cost for every operation.
Device timing variation remains visible in the unfiltered and prototype controls;
these are not isolated measurements of interpreter cost.

### Integration Boundary

The prototype excludes policy publication, per-thread chain lookup, TSYNC,
filter inheritance across exec and userspace delivery of TRAP/KILL/TRACE/USER_NOTIF.
It establishes the cost of evaluating real cBPF alongside a complete accelerated
file operation, not the correctness or final speed of integrated browser entry.
Production separately publishes those views to live channels after installation.

The shared policy core exports immutable entry views without coordinator or
listener pointers. Unit controls compare full-chain and shared-ancestor views
against coordinator decisions for every input word and action precedence.
The service-gate emitter handles exact PCs and 64-bit ranges through the same
matcher. IP-dependent filters route aliases before evaluation, including ALLOW
results, so recovery uses the original instruction address. IP-independent
chains with an admitted inert checkpoint can rearm after exec; IP-sensitive
inherited chains retain Base. Non-ALLOW/non-ERRNO actions use original-site
kernel fallback. The installation fence remains required.

`test_seccomp_policy` installs generated exact-PC/range gates in native children:
the owned call is admitted while an ordinary call with the same number retains
ERRNO. Full filter sections pass under Shell and App UID on the 6.12 device
(`4379905bd5964c8f92b3982087bf690d`, `590cf56103944f9e852369a4fd0dc262`
under `build/svc-patch-results`). Coverage includes original-PC decisions,
original readiness arguments, TRAP/KILL/LOG/TRACE/USER_NOTIF fallback, inherited
exec, fork, 24-cycle TSYNC races, waiting peers and cleanup. Deterministic
instruction-boundary and queued-signal controls pass under Shell
(`build/svc-direct-signals/oci-services-ff8d4dec68c2466fa3aafe58695dc789.json`).
Guest TRACE tests cover the existing no-tracer ENOSYS contract, not guest
`PTRACE_O_TRACESECCOMP` support.

Stock Alpine Chromium passes four software-rendered, sandbox-enabled App UID
launches, JavaScript and PNG checks, with owned profiles cleaned up
(`app/build/shroot/browser-stage-results-6cae35d3f5fc44448a3cba1708187d8f.json`).
Each launch verifies `filteredCompleted > 0` in a process that published its
policy and rearmed entry. This proves actual post-filter acceleration, not just
a successful sandboxed launch; the small completion counts do not measure
browser throughput. Production statistics reuse the optional completed-call
counter and a cold publication baseline, without a new per-call counter.

The complete guest-policy runner passes 258 cases in each identity, combining
Base and lazy entry: `e0a81d781add4a7ebd998435cbcd108e` (User) and
`a5b7ba7a772542ff80b06033f9bdaa86` (Shell) under `build/guest-policy`.
This covers TSYNC, notification CONTINUE, listeners, signal masks, signalfd,
epoll and repeated PID-init delivery. The state and memory suites additionally
check packet wake ownership, read-only views, fork retention and invalidation.

The integration shares the existing owners and retains these constraints:

- Publish an immutable original-policy view through the entry ownership model;
  preserve chain precedence and original nr/arch/args/IP before any native or
  worker effect, including memory, readiness and descriptor shortcuts.
- Use the existing installation fence to publish per-thread/TSYNC policy only
  after successful validation, and retain the correct view for admitted calls,
  fork and exec. Hash-based deduplication is optional, not an admission rule.
- Preserve kernel policy on ordinary native and cold calls. Replacing it with
  a catch-all interception filter must not silently admit previously unintercepted
  operations or introduce per-read/write stops into native IO.
- Give internal helpers and original-site fallback explicit ownership. A patched
  site's kernel alias has a different PC: an IP-sensitive filter can reject it
  before the supervisor can reconstruct the original call. Reuse the owned
  checkpoint and original-site retry, not an unchecked alias.
- Keep Android's inherited filters effective. An in-process policy evaluator
  does not establish hostile-code confinement or remove host restrictions.

## Selective Ptrace Hybrid

`test_hybrid.c` is a bounded static native control. It uses USER_NOTIF for
selected operations and TRACE for register/exec changes, with ordinary
`PTRACE_CONT` between them. Single-stepping is a signal-delivery test control,
not its normal execution mechanism.

Its supervisor owns only its child tree through SEIZE/EXITKILL and event-driven
signalfd/notification observation. Thread-specific INTERRUPT cancels the exact
pending notification. Process-directed SIGSTOP does not provide that identity.

The fixture checks:

- Original ERRNO/TRAP/KILL decisions, nested application SIGSYS and an
  application-owned alternate stack.
- Register-only exec redirection, including after dumpability is disabled.
- ARM64 `orig_x0` rechecking: replacing x0 alone is not equivalent to a fresh
  entry through the original SVC instruction.
- Protected parent/fork-child memory access through bounded instruction-only
  copies, without changing dumpability or using the parent's memory FD for
  a replacement address space.
- Cancellation, copied pathname validation, original-site replay and ADDFD
  publication for a fresh kernel-validated request.
- Concurrent TSYNC denial after copying but before replay.
- Signal masks, original PC/result/siginfo, nested calls and kernel-owned
  EINTR/SA_RESTART behavior.
- Rejection of a second native tracer. Guest debugger compatibility is a
  separate [Shroot contract](../shroot/debugging.md).

Temporary copies are untrusted input. No stale notification ID authorizes work.
Cancellation before an external operation is not permission to replay a mutation
whose completion is unknown. The fixture's known code sites and bounded mappings
do not establish arbitrary exec, signal or process-lifetime compatibility.

## Signal-Neutral Checkpoints

The common `task_checkpoint` owner serves normal bootstrap control markers
through selective TRACE or scoped SYSEMU. `task_call` owns native entry/exit
without a return BRK, and `signal_delivery` chains actual delivery stops without
service single-step. Build the controls with `MAGICDESK_GUEST_FIXTURES=ON`:

```sh
cmake --build BUILD --target test_signal_checkpoint
python native/guest-exec-lab/test_signal_checkpoint.py BUILD
```

The runner uses UID 2000 and records the kernel, fixture hash, controls, timings
and cleanup in `build/signal-checkpoints`. The bounded fixture compares BRK,
single-step, syscall entry/exit, seccomp TRACE and two SYSEMU conventions. It
also compares two ways to re-deliver retained real signals: injection at
syscall exit, and a supervisor-originated `tkill` using the retained signal's
own number. Neither replay variant reserves a carrier signal. Delivery stops
restore the original `siginfo` before returning the signal under the saved mask.

Observed on the test phone's Android 16 / Linux 6.12 kernel:

| Mechanism | Result | Boundary |
| --- | --- | --- |
| BRK and hardware single-step | A blocked or ignored SIGTRAP resets its action to SIG_DFL before the tracer sees it | Restoring only the mask cannot repair disposition; CLONE_SIGHAND peers observe the change too |
| Syscall entry/exit checkpoint | Preserves action and mask | Changing the syscall number to -1 at entry still encounters application seccomp; KILL for -1 defeats cancellation |
| Seccomp TRACE checkpoint | Preserves action and mask; cancellation at the TRACE stop also works with KILL for -1 | A subsequently installed KILL filter for the checkpoint call wins before TRACE; the supervisor cannot cancel a stop it never receives |
| PTRACE_SYSEMU checkpoint | Preserves action and mask; the marker is skipped before kernel seccomp, including KILL by number/IP and concurrent TSYNC | Must be scoped to owned service execution; real syscalls encountered there need native admission, not emulation |
| Retained signal sent externally | Preserves disposition, siginfo and native restart decisions when delivery stops are chained without executing userspace between them | Combined entry/seccomp/exit and native job-stop controls pass; regenerating retained job-control signals is not equivalent to keeping them pending in the kernel |

The fixture has 168 positive/control cases and 16 expected KILL-policy rejection
controls. Coverage includes installed/ignored and blocked/unblocked SIGTRAP,
shared dispositions, protected memory, existing pending standard signals and
`RLIMIT_SIGPENDING=0`. Exhausted kernel signal storage retains native loss of
queued sender metadata rather than pretending that the kernel stored it.
A shared progress marker verifies that replay stops do not execute the original
application continuation prematurely. These are native protocol tests, not
168 passing Shroot application workloads.

The additional SYSEMU controls cover:

- 32 strict-policy cases: syscall-number and IP-only KILL, KILL for -1,
  dumpable=0, and TSYNC installed by a sibling while the actor is stopped at
  its emulated entry. Negative controls resume the same marker natively and
  must terminate with SIGSYS.
- Six native-admission cases: original IP and arguments, exactly-once write,
  denial by syscall number or IP, and protected tasks.
- Ten real-signal cases: thread/process-directed SIGUSR1 and SIGTRAP, original
  siginfo, application PC/SP/x0/x7, alternate stack and native
  SIGSTOP/group-stop/LISTEN/SIGCONT. These signals stay in kernel custody until
  application context is restored; this is not the retained-signal replay test.
- Four interrupted-read cases: real handler and rt_sigreturn, EINTR versus
  SA_RESTART, and exactly-once read completion under the original filter.
  ARM64's signal-delivery stop exposes a provisional restart; the final
  SA_RESTART decision is checked in the handler's ucontext and IO result.
- Sixteen retained-signal replay cases: SYSEMU checkpoints replace single-step,
  and their resume queues the retained signal's own number. A real delivery
  stop installs its original siginfo, then requeues it under the service mask.
  Both signals are in kernel custody before restoring application context.
  Tests cover SIG_IGN, blocked delivery/sigtimedwait, handlers/altstack,
  preexisting standard signals, exact PC/SP/x0/x7 and strict seccomp.
- Sixteen native-entry replay cases: a real signal interrupts the original
  syscall without changing its number to -1. Entry and seccomp boundaries
  retain native write-once versus read EINTR/SA_RESTART behavior, original
  metadata and a protected tracee. The original call can complete before the
  handler, as with ordinary ptrace delivery. Integration must preserve that
  ownership, not replay the call.
- Four register-copy cases: an owned load/store sequence publishes x3-x18,
  including x7/x8 payloads, through SYSEMU. An interrupt exposes the hidden x7
  before restoring application registers. The marker is identified by exact
  IP, not a chosen syscall number. Its IP-specific KILL filter proves that
  an unarmed marker cannot execute payload x8 as a native syscall.

Six additional fault controls expose a separate limitation: an invalid source
in an owned userspace load resets ignored/blocked SIGSEGV before ptrace sees
the fault, even with a signal-neutral return checkpoint. The fixture records
this as `LIMIT`, not a transparent-copy pass. A native write/copy_from_user
control returns EFAULT without changing the same signal disposition. These
faulting fallback copies need kernel-mediated access, not just replacement of
their final BRK or a racy VMA precheck.

### Invalid-Pointer Copy Controls

Run the bounded copy investigation without the complete checkpoint matrix:

```sh
python native/guest-exec-lab/test_signal_checkpoint.py BUILD --copy-fault
```

It retains the six negative LDR controls and adds 72 native self-`process_vm`
read/write controls: installed, ignored and blocked SIGSEGV/SIGBUS; dumpable=0;
valid, inaccessible, unmapped, truncated-file and read-only mappings; and an
exact partial prefix at a protection boundary. On the tested Linux 6.12 device,
kernel copies preserve both dispositions and masks. Six filter scenarios then
deny `process_vm_readv/writev` and subsequently pipe `read/write`: neither is a
universal replacement inside application seccomp. These are native controls,
not integrated Shroot copy implementations or coverage of every kernel.

The same mode compares the production `guest_copy.S` with self-`process_vm` and
a pipe round trip, rotating five rounds of 32768 copies of 128 bytes. Reports
retain raw timings, medians and CPU-frequency context over the command envelope,
not per-thread execution frequency. The recorded fixture medians are 0.19 ms for
userspace, 6.8 ms for either `process_vm` direction and 8.4 ms for the pipe.
The roughly 0.2 microsecond extra cost per kernel copy excludes PID lookup and
adapter integration; it is not a measured whole-application slowdown.

No production path is changed by this experiment. The normal supervisor shield
already unblocks synchronous faults, but cannot protect an ignored disposition
or the caller-mask cooperative path. Existing task helpers share `CLONE_SIGHAND`,
so moving a load there does not isolate the damage. A reliable conditional path
would have to account for native mask/action changes and concurrent peers;
checking VMAs before a load or repairing a handler after the fault is insufficient.
Keep this limitation explicit rather than adding a per-copy kernel trip or a
separate helper lifecycle solely for this case.

### Combined Replay Protocol

The retained-signal fixture chains real delivery stops inside the same
kernel `get_signal` invocation. Each stop installs its retained siginfo, blocks
that signal and queues/unblocks the next signal's own number. The final stop
restores the application's mask. No application register or syscall number is
rewritten, no reserved carrier is needed, and no userspace checkpoint executes
between these stops. The original syscall retains kernel admission and its
native restart decision; a completed write is not replayed.

There are 576 successful combined cases on the test phone:

- 192 entry/seccomp/exit cases with two retained signals, ignored/handled/blocked
  SIGTRAP, alternate stacks, exact siginfo and PC/SP/x0/x7, EINTR/SA_RESTART,
  existing private pending signals, protected tasks and exactly-once IO.
- 96 cases add the production `task_call` executor before publication,
  including restart-exit reconstruction. Every case retains KILL(-1): original
  entry replacement and SYSEMU-based exit reconstruction never execute a
  synthetic skipped syscall through the application filter.
- 192 cases interpose either PTRACE_INTERRUPT or native
  STOP/LISTEN/CONT between the two delivery stops. Actual CONT metadata and
  restart behavior survive. Realtime signals stay pending in the kernel and
  preserve both their values and queue order.
- 96 cases distinguish existing process-directed pending signals from the
  replay's private pending signals. Both deliveries retain their own metadata;
  private and shared queues must not be coalesced as one standard-signal slot.

Negative controls establish boundaries rather than weakening assertions:

- Six controls insert a SYSEMU userspace checkpoint between the delivery stops.
  That leaves the original kernel restart decision behind: a read restarts
  instead of returning EINTR without SA_RESTART. Register restoration cannot
  reconstruct kernel-local `do_signal` state. Thus the USER-boundary SYSEMU
  replay above is not a universal restart-boundary replacement.
- Two controls show that STOP removes a pending CONT. Regenerating a retained
  copy of that old CONT then wakes the stopped group, even with CONT blocked.
  Job-control generation effects require kernel custody, not delayed resend.

Eight positive entry/exit controls additionally check reconstruction under
KILL(-1). `test_fd_filter_remote` separately rejects a genuinely denied native
call with KILL_THREAD: an exit notification can precede termination. Restoring
the old blocked SIGSYS mask there would incorrectly revive the actor. The
executor retains native termination, including when SIGSYS was ignored and
blocked and TRACEEXIT was not enabled.

The TSYNC fixture pauses the actor with thread-directed STOP. A process-directed
STOP could select the untraced filter-installation peer, cause a group stop and
deadlock the acknowledgement protocol. Thread-directed delivery makes the
acknowledgement independent of process-directed signal selection.

### Checkpoint Cost

Two five-sample interleaved 1000-checkpoint runs on 2026-10-21 had medians of
14.8-17.3 ms for BRK, 14.9-16.3 ms for SYSEMU, 13.9-16.7 ms for the
preserved-x7 SYSEMU convention, 14.6-16.7 ms for TRACE and 28.3-31.7 ms for
syscall entry/exit. These include the fixture's ptrace register/info operations,
are scheduler-sensitive, and do not measure an end-to-end filesystem speedup.
The observation supports keeping
selective event stops, not tracing every syscall in every guest thread. They
do not establish the cost of a complete adapter or a statistically significant
advantage over BRK.
Later expanded-fixture runs had roughly 19 ms BRK/SYSEMU medians and larger
outliers. The relative checkpoint result remains comparable, but the complete
multi-stop retained-signal replay is not this one-checkpoint timing.

### Register And Admission Contract

ARM64 syscall stops temporarily expose x7 as an entry/exit marker; the kernel
restores its hidden original value after ptrace resumes. Writing x7 at that
stop does not replace the hidden value. The fixture proves two solutions:

1. A requested PTRACE_INTERRUPT after the emulated entry yields a non-syscall
   stop before any subsequent userspace instruction. Full register restoration
   is then effective, at the cost of one extra stop.
2. Owned service code preserves the application x7 and restores it before its
   checkpoint SVC. The one-stop convention then restores the correct x7 through
   the kernel. The fixture uses a register shadow in its assembly only; this is
   not authorization to reserve x18 across arbitrary guest or C adapter code.

SYSEMU entry caches its emulation decision before notifying ptrace. Merely
resuming that stop with CONT does not execute the current syscall. To admit a
real call, the prototype leaves the emulated entry skipped, re-enters the same
SVC under SYSCALL, and arms SYSEMU at the resulting ordinary entry. That native
call still passes seccomp; the following service checkpoint is emulated.
Application handlers and rt_sigreturn must not inherit service emulation.

The ARM64 SYSEMU request and the emulation-before-seccomp ordering exist in
the Android common 5.15 source, not only 6.12. A 5.15 device and vendor policy
remain untested. Production admission uses the existing guest-only helper probe;
an unavailable operation fails the native runtime, not unrelated app services.

### Production Adoption

| Owner | Current contract |
| --- | --- |
| `task_checkpoint` | Exact marker table, scoped resume modes, complete x7 payload and typed completion event; ordinary guest calls do not acquire SYSEMU |
| Register gates and C adapter returns | Registered SVC markers carry service completion; genuine SIGTRAP never means adapter completion |
| `task_helper`, readiness and memory repair | Consume the same typed event while retaining their existing clone, notification, cancellation and actor ownership |
| `task_call` | Native entry/exit and exact continuation reconstruction; kernel restart_block and orig_x0 stay authoritative |
| `signal_delivery` | Real delivery-stop chaining, first-info standard-signal coalescing and kernel-owned job-control cancellation |

The dispatcher normalizes fresh wait events before broker deferral, so a held
event is never converted twice. State lookup is PID-indexed and terminal/exec
events release the actor state. A stopped-thread register loan establishes the
service scope; restoring application registers ends it. Explicit native-call
or debugger resume modes retain ownership over their syscall boundaries.

Native-call tests cover original USER/ENTRY/EXIT/SECCOMP continuations,
protected tasks, blocked/ignored SIGTRAP, TSYNC, real denials, foreign
interrupts, and read/ppoll/timer restarts. Replaced entries restore the injected
x0 when the kernel restarts using the application's private orig_x0. Four
job-control tests verify private/shared CONT custody, cancellation by STOP,
native LISTEN and no unintended group wake.

The integration matrix includes the 142-check filesystem-signal suite with
ignored-SIGTRAP modes, protected memory, filters and metadata interruption;
helper FD custody/cancellation, buffer/control events, file results/returns,
mapping-task and private-memory tests; lazy signal recovery and thread churn;
and stock GDB/strace in Ubuntu with active IO, shared memory and JIT. They use
staged production helpers under UID 2000, not a separate implementation.

Guest-requested single-step remains a real debugger operation. The two BRKs
after impossible bootstrap/exit returns remain fail-fast guards, not normal
control messages. The invalid-pointer copy limitation above is still a distinct
defect: these results establish signal-neutral control transfer, not arbitrary
fault-free user-memory access.

For end-to-end cost, `test_svc_libc.py --baseline-build BUILD` interleaves native,
Base and lazy workloads with a second immutable runtime on the same store.
Reports retain both sets of helper hashes. Microcheckpoint timings alone do
not establish the cost of the integrated protocol.

## Real Browser Boundaries

Use [browser/README.md](browser/README.md) for stock Firefox/Chromium workloads.
It distinguishes automatic user/PID namespace startup, the explicitly selected
set-ID helper route, admission rejection and renderer-only diagnostics.
Renderer-only tests and version strings are not full-browser passes.

The [isolation controls](chromium-isolation.md) separately exercise native
namespace admission, a deliberately restricted read-only broker and
helper/protected-memory mechanics. Their reduced syscall surfaces must not be
confused with a general Linux execution environment or native sandbox equivalence.

## Primary References

- [Linux seccomp contract](https://docs.kernel.org/userspace-api/seccomp_filter.html)
- [Linux 6.12 notification implementation](https://github.com/torvalds/linux/blob/v6.12/kernel/seccomp.c)
- [Firefox signal/filter integration](https://github.com/mozilla/gecko-dev/blob/master/security/sandbox/linux/Sandbox.cpp)
- [Chromium Linux sandbox](https://github.com/chromium/chromium/blob/main/sandbox/linux/README.md)
- [Android 16 common-kernel ptrace](https://android.googlesource.com/kernel/common/+/refs/heads/android16-6.12/kernel/ptrace.c)
- [ARM64 syscall arguments](https://android.googlesource.com/kernel/common/+/refs/heads/android16-6.12/arch/arm64/include/asm/syscall.h)
- [Kernel signal and ptrace-stop implementation](https://android.googlesource.com/kernel/common/+/refs/heads/android16-6.12/kernel/signal.c)
- [Syscall-stop signal injection](https://android.googlesource.com/kernel/common/+/refs/heads/android16-6.12/include/linux/ptrace.h)
- [ARM64 signal delivery](https://android.googlesource.com/kernel/common/+/refs/heads/android16-6.12/arch/arm64/kernel/signal.c)
- [ARM64 5.15 ptrace/SYSEMU ordering and x7](https://android.googlesource.com/kernel/common/+/refs/heads/android13-5.15/arch/arm64/kernel/ptrace.c)
- [ARM64 5.15 ptrace UAPI](https://android.googlesource.com/kernel/common/+/refs/heads/android13-5.15/arch/arm64/include/uapi/asm/ptrace.h)
- [Signal generation, job control and ptrace requeue](https://android.googlesource.com/kernel/common/+/refs/heads/android16-6.12/kernel/signal.c)
