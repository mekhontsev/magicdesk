# Host Execution Identities

Shroot uses one native engine for Android application and shell identities.
Every launch retains its explicitly selected executor. Shared fixtures select
the real Android identity with `--executor app|shell` or
`SHROOT_TEST_EXECUTOR`; guest credentials are a separate setting.

## Ownership

- `GuestFixtureService` runs in `:guest_probe` with the app's real UID, SELinux
  domain and inherited Android seccomp. It never loads the adapter into ART or
  changes the main process's runtime limits.
- `GuestFixtureRunner` is a bounded transport through the bundled shell. Shared
  Python drivers own test cases, environments and assertions. Receipts live in
  `cache/guest-fixtures`; the service does not own a second Linux installation.
- Native admission accepts equal real/effective application IDs with no capabilities.
  `host_policy.c` adapts host faccessat and reports unavailable
  openat2/fchmodat2 primitives to their existing owners. It does not remove the
  Android filter or implement guest permissions.
- `host_open` retains native openat2 when available. On ENOSYS, a plain open
  uses openat; NO_SYMLINKS uses a pinned, no-follow component walk. Scoped
  lookup of the retained directory itself remains atomic. Unsupported
  resolve constraints fail explicitly, never becoming an unconstrained open.
- The debug service requires MANAGE_ACTIVITY_TASKS. The driver uses the
  authorized console to start it and retrieve artifacts. `run-as` only stages
  an inert archive and reads reports; it never runs the guest.

UID 2000 does not enable host-SIGSYS translation. There is no global
syscall-entry tracing fallback: its per-syscall ptrace cost defeats the
efficiency requirement.

## Integration Status

| Boundary | Implemented in the production engine | Not established |
| --- | --- | --- |
| Guest policy | Immutable capture, native validation, successful-install publication, fork/exec ownership, TSYNC admission snapshots and original-policy dispatch/CONTINUE | General sandbox application coverage |
| Signals | Logical SIGSYS actions, private/shared pending queues, signalfd/read/readv, poll/epoll, temporary masks and kernel handler-entry frames; nested/nonlocal handlers, rt_sigreturn and proc/debugger projection | Other firmware and physical kernel 5.15; virtual epoll exclusive/wakeup policies remain explicitly unsupported |
| Notifications | Guest NEW_LISTENER installation, original-call dispatch/CONTINUE, interruption/restart, ADDFD, protected input capture and shared FD ownership | Broader concurrent exit/exec and sandbox application coverage |
| Protected proc | Task-affine descriptor/metadata capture and bounded process-directory snapshots, resumed filesystem walks, live umask reads and virtual dumpability with common inspection checks | Full foreign-target application coverage |
| Optional thread registrations | Host-denied `set_robust_list`, `get_robust_list` and `rseq` return ENOSYS after original guest-policy admission; permitted calls remain native | No robust-futex owner-death or rseq emulation when Android denies registration. Older glibc builds can assume robust-mutex support despite ENOSYS; such mutexes are not supported on that host path. |
| Device workflows | Recipe-installed Alpine, stock runc and single-node k3s acceptance; stock Chromium with software rendering, namespace and set-ID sandboxes, JavaScript and screenshot checks; common ABI/debugger/policy/signal suites from a real app UID | General distribution/container matrix and interactive browser/GPU coverage; real kernel 5.15 and other firmware |

A component test is not an end-to-end application workflow. Guest listeners
use the shared control-file provider because Linux rejects a second native
NEW_LISTENER in Shroot's existing kernel-listener chain. Standalone protocol
oracles do not substitute for the shared production notification tests.
The Alpine debugger run covers GDB, LLDB, gdbserver, strace and Callgrind.
Its toolchain lacks gcrt1.o for gprof, and Android denies perf_event; those
capabilities are recorded as unavailable, not passed debugger workflows.

## Signal And Policy Admission

`seccomp_install.h` classifies both filter installation APIs and their native
results. A listener FD, including zero, is success; a positive-TID TSYNC failure
is not. `seccomp_policy` owns immutable original instructions, bounded cBPF
evaluation and shared ancestry. Task attachments are distinct from references
held by descriptors or in-flight operations. Replacing a head first attaches
its successor so an ancestor listener cannot observe a false last-user HUP.

`seccomp_capture` borrows a peer-read-only buffer through native installation
or confirmed task death. The supervisor retains the pre-seal writer, which the
guest must not inherit. The mapping turn prevents replacement of the captured
image during installation. Original-policy and guest-memory ownership stay in
their existing modules.

Both execution identities route admitted completions through an owned service
gate while retaining application-site kernel admission. Cold Android-denied filesystem
and SysV IPC calls are admitted against the original guest policy before
dispatch. ERRNO, TRAP and KILL tests preserve their distinct outcomes.
Guest TRACE data is not an internal selector identity; without supported guest
TRACESECCOMP observation it returns ENOSYS. An immutable may-TRACE hint avoids
a BPF walk on every ordinary selector stop.

`signal_actions` owns dispositions: CLONE_SIGHAND shares them, fork copies
them, and exec retains ignored dispositions. `signal_view` translates logical
state. `task_signal_control` performs fault-aware exchanges in the exact task.
Installed handler veneers restore original siginfo and the logical mask in the
kernel-built frame, then acknowledge their retained packet before entering the
application handler. This needs no additional ptrace stop unless a competing
memory mutation requires acknowledgement. Failed frame construction releases
the packet and preserves the kernel's SIGSEGV and disposition behavior. An
unchanged exclusive native handler also needs no extra entry stop; untranslated
handler frames use the bounded PTRACE_INTERRUPT commit when required.
Nonlocal exits do not depend on a return hook. Mask queries and
updates lend the kernel the logical mask through their already-admitted syscall
and restore the native view at syscall exit, retaining native pointer validation
and error/commit ordering. Protected copying uses
process_vm self-copy through the existing task owner when native inspection is
unavailable. Guest dumpability belongs to the common process-access owner;
runtime copying does not change that logical policy.
Metadata-only `O_PATH` opens retain proc inode access without admitting its
contents. Reopening such a pin applies the same guest permission and inspection
checks as a pathname open. Memory pins still publish address-space exposure,
including when inherited across fork, so lazy rewriting cannot treat them as
private memory.

Host-signal setup prepares the same peer-read-only pool used for filter images.
Eligible signal returns snapshot their kernel input there under the address-space
owner, without a dedicated syscall-exit stop. This is independent of whether
the application installs a filter; hosts that need no signal translation retain
their native path. Unrelocatable frames, debugger-owned returns and pending
delivery queues keep the observed kernel path. A queued carrier may borrow the
mask only after signal return has restored the application's context.

The `proc_file` status projection hides temporary service masks and the guest
debugger callback exposes logical SIGSYS state without adding a periodic query or touching ordinary file
IO. `signal_pending` owns private versus shared SIGSYS coalescing and consumption
tokens; other signals remain in their native queues. Guest sends preserve their
generation scope. External delivery already selected by the kernel retains that
task rather than inferring the target from `si_code`.

`signal_fd` shares descriptor masks through the common OFD model. Reads, vectored
reads, updates, nonblocking mode, fork and typed descriptor transfer use the same
task signal owner. Its epoll wait domain follows CLONE_SIGHAND, independently of
the CLONE_THREAD pending group. Ordinary exec retains that domain; exec detaches
it when handlers were shared with another process. Logical ET/ONESHOT events
commit after successful output publication; native events first use the kernel's
output validation. No mapping turn or shared transport slot survives a blocking
task-queue wait. Native masked/timed waits use the same private-stack argument
ownership. Temporary-mask tests cover ppoll, pselect6, epoll_pwait/pwait2
and sigsuspend, with pending/late signals, timeout, EINTR, sigreturn and longjmp.
Handler admission retains the wait's temporary delivery mask until frame entry:
the kernel's `PTRACE_GETSIGMASK` exposes the saved return mask while
`TIF_RESTORE_SIGMASK` is set, not the mask selecting that signal.

TSYNC quiesces only the affected group during installation and retains the old
immutable policy for observed admissions and pending forced host signals.
Both app UID and UID 2000 use one notification lifecycle: the supervisor
publishes a public ID only after acquiring its owned kernel carrier.
Already published notifications retain their IDs across installation. CONTINUE
and adapted output publication finish the admitted call through owned service
gates, not a second application admission. The next application call sees the
new policy. Ordinary calls incur neither a TSYNC scan nor global entry tracing.

SIGSYS must not be indiscriminately suppressed. A genuine guest TRAP can force
the kernel's default disposition when blocked or ignored; an Android denial
of an emulated operation must not alter the application's disposition.
See the baseline kernel's
[forced-signal implementation](https://github.com/torvalds/linux/blob/v5.15/kernel/signal.c).
Cancellation at a ptrace entry is also distinct from cancellation after seccomp:
an inherited filter can still reject syscall number -1.

## Notification Ownership

`seccomp_notify` supplies RECV/SEND/ID_VALID and readiness through the shared
control-file provider and wakeup. Notification IDs have a hash index and RECV
uses a ready queue. Only notification operations incur these costs.

RECV holds its message through the adapter's terminal copy acknowledgement.
EFAULT or receiver cancellation requeues an unanswered message. A committed
SEND cannot be undone by a later copy failure or sender death. Last listener
close replies ENOSYS to unanswered callers. Pending calls retain queue state,
not the listener descriptor.

Each winning policy node observes its exact listener through the shared weak
OFD owner, never an ancestor listener or a recycled descriptor number. Dup and
SCM_RIGHTS retain that OFD. O_PATH reopening retains metadata only. Caller PID
numbers are captured once and translated to the receiver's retained namespace;
an invisible caller is reported as PID zero.

ADDFD reuses `fd_install`, including atomic SEND, fixed-slot replacement,
shared OFD position and descriptor metadata. Typed ioctl admission captures
input FDs through the existing file-table owner. Denied external access uses
task-affine capture without changing dumpability; ordinary file IO is unchanged.
Borrowed waits preserve native EINTR/restart decisions without re-executing
the internal service call at the restored application boundary.

Coordinator tests cover eight concurrent receivers, 512 exact replies,
mutable sender memory, cancellation, copy/SEND races, descriptor zero, fork,
SCM_RIGHTS, listener closure, task-user HUP and receiver PID views. Production
tests exercise original native and cold adapted calls, rejected installations,
signal interruption/restart and descriptor transfer. The internal Shroot
listener is never exposed as a guest FD.

## Running Tests

Use an awake, unlocked device with MagicDesk MCP configured. Close an active
Desktop through production cleanup before replacing the APK.

```sh
./gradlew :app:assembleDebug -PMAGICDESK_GUEST_FIXTURES=true
python native/guest-exec-lab/fixture_app.py \
  --apk app/build/outputs/apk/debug/app-debug.apk --command 'id; shroot list'

export SHROOT_TEST_EXECUTOR=app
python native/guest-exec-lab/test_resources.py app/build/shroot --case all
python native/guest-exec-lab/test_resources.py app/build/shroot --case private-memory
python native/guest-exec-lab/test_guest_policy.py --build app/build/shroot --name alpine
python native/guest-exec-lab/test_clone3.py --build app/build/shroot --name alpine
```

The same drivers accept `SHROOT_TEST_EXECUTOR=shell` for UID 2000. Where exposed,
`--executor` overrides that environment setting. Select a recipe-installed
environment owned by that executor; no test silently switches identity or
copies another identity's distribution. Guest credentials remain explicit.

The shared policy driver covers original BPF admission, TSYNC, listeners,
ADDFD, handler state, signalfd and masked waits in both Lazy/Base modes.
`--group`, `--case` and `--entry` select focused checks without changing their
assertions. Debugger coverage belongs to `test_debugger_runtime.py`; memory
coverage belongs to `test_resources.py --case private-memory`, not to
another app-only suite.

Fixtures are debug-only APK executables. The app executor verifies their exact
ELF digests before use. Native tests execute immutable packaged ELFs; guest
mounts receive regular byte copies. Writable app data is never made directly
executable and `run-as` is used only for inert transfers.

Reports retain real UID, SELinux domain, kernel, firmware, build identity,
commands and results. Full logs use the existing ranged, SHA-256-verified
transfer. The transport verifies that the main MagicDesk process survives.
An observation timeout is not completion or permission to replay a command.
Recover the exact launch with `fixture_app.py --observe-run RUN_ID`.
The executor cleans that run's private artifacts after verified download and
cancels only commands it owns.

## Qualifying Evidence

Device coverage is on NX809J, Android 16/API 36, target SDK 37, kernel 6.12.23,
with the real untrusted application UID and no capabilities. It does not prove
other firmware or physical kernel-5.15 compatibility.

Both application and shell identities pass the complete shared resource suite,
including namespace/mount/cgroup contracts, filesystem capture and buffers,
descriptor transfer, store recovery and cancellation. Separate private-memory,
FD-lifecycle and restricted-descriptor gates also pass. Shared policy and signal
tests, full Base/lazy cooperative entry, watches, musl ABI, clone3 and debugger
workflows use the same assertions for both identities. Native-only capability
denials and unavailable profiling tools remain distinct from guest failures.

Recipe-installed Alpine 3.23 passes shared single-node k3s acceptance: node
and CoreDNS readiness, cross-pod DNS and Service traffic, NetworkPolicy,
Deployment scaling/updates with PVC retention, Traefik/ServiceLB ingress,
server restart and clean deletion. Stock runc passes split create/start with
the containerd seccomp profile and guest UID 65535. These tests retain guest
seccomp and guest dumpability checks.

The shared Chromium software test passes its namespace-sandbox DOM and PNG
checks under app UID. Its set-ID route also passes DOM/PNG checks and rejects
the no-new-privileges launch as required. This is workflow evidence, not a claim
of hostile-tenant isolation equivalent to a native Linux kernel. Other
distributions and graphical workflows require their own acceptance coverage.

The integrated lazy-SVC suite passes protected process-image checks in Base
and Lazy modes, mapping/protection transitions, filter races and five Go pairs
with and without guest seccomp. Native comparisons first probe the required
host operation: Android may deny native `openat2` or foreign non-dumpable proc
access. Such an unavailable oracle is reported separately; guest assertions
remain mandatory and ordinary test failures are never treated as unavailable.

```sh
SHROOT_TEST_EXECUTOR=app python native/guest-exec-lab/test_k3s.py \
  app/build/shroot alpine /path/to/k3s-arm64 --output build/app-uid-k3s
```

Component tests and standalone native protocol oracles do not substitute for
production guest tests. Preserve sandbox policy, guest dumpability and native
IO; do not trade correctness for launch success or add global syscall tracing.
