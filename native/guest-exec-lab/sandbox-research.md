# Guest Sandbox Boundaries

These fixtures investigate Linux browser sandbox requirements under shell UID
2000, without disabled sandbox flags or changes to Android security. Separate
controls distinguish a ptrace-free design from an explicitly selected native
hybrid with selective ptrace. Neither changes the production adapter.
They are not a replacement execution backend or browser certification. The
guest runtime itself is an execution adapter, not a security sandbox.

## Reproduction

Use a prepared glibc build/sysroot, an immutable staged runtime and a disposable
guest store. The runner checks the selected UID and creates a separate empty
store for the filesystem-policy control. Its filters affect owned children only.

```sh
sh native/guest-exec-lab/build-sandbox-fixtures.sh BUILD
python native/guest-exec-lab/test_sandbox.py BUILD \
  --runtime IMMUTABLE_STAGED_HELPERS --store DISPOSABLE_GLIBC_STORE
```

`test_sandbox.c` builds both a native Bionic control and a glibc guest fixture.
`test_policy_gate.S` supplies a test-only syscall site. The JSON report retains
APK/device/kernel identity, helper hashes, commands, output and cleanup. Exit
zero means the observations completed, including expected incompatibilities;
`sandboxSupportEstablished` remains false. No APK installation or Desktop
self-test is involved. The runner removes its guest executable but retains the
fresh native fixtures and harmless RPC store for inspection.

Device evidence: RM11/NX809J, Android 16/API 36, Linux 6.12.23, 4 KiB pages,
actual UID 2000. All 15 boundary observations completed. These are small
synthetic policies, not the complete policies of a particular browser build.
Broader notification lifecycle controls remain in `test_notifications.py`.

## Observations

| Boundary | Measured result |
| --- | --- |
| Runtime TRAP and application ERRNO | TRAP wins regardless of filter installation order. |
| Two TRAP filters | The newest matching filter supplies the data cookie. |
| USER_NOTIF and application policy | ERRNO/TRAP win without broker invocation; listener loss returns ENOSYS. |
| Cooperative SIGSYS dispatch | Tagged runtime/application traps, a nested trap and an explicit user signal work on an application-provided alternate stack with SA_NODEFER. |
| Blocked SIGSYS | A synchronous runtime trap kills the process. |
| Syscalls from the signal handler | Application filters still restrict them. |
| Two-stage TRAP/notification probe | Reissuing a no-argument syscall at a notification site lets the kernel apply an ordinary ERRNO denial. With no listener, the allowed probe fails closed. |
| Instruction-pointer policy | That reissue changes the syscall PC. A filter denying the original site but allowing the probe site produces a different decision. |
| Native user namespace | `unshare(CLONE_NEWUSER)` fails with EINVAL on this device, outside the guest adapter. |
| Filesystem service | Direct RPC can open a harmless marker despite the caller's openat denial. Denying connect stops this tested route. |
| Current guest signals | Installing SIGSYS or replacing the alternate stack returns ENOTSUP. |
| Current guest nested filters | An openat ERRNO denial and an application TRAP both still allow a directory open through the adapter. KILL_PROCESS remains effective. |

The cooperative signal test does not implement guest signal virtualization. It
does not establish correct arbitrary masks, longjmp, stack replacement or
concurrent signal/thread lifecycle handling. The two-stage probe handles one
no-argument syscall; it does not redirect exec or preserve arbitrary seccomp
policies. Its instruction-pointer counterexample rules out treating replay from
a different address as equivalent to checking the original call.

The RPC control establishes a missing policy boundary, not a demonstrated
exploit against Firefox or Chromium. A real browser filter may disallow the
socket operations. The filesystem service runs outside the caller's seccomp
filter and authenticates its UID, not a browser's per-process policy. A caller
able to reach it can request operations directly. Keeping policy in writable
guest memory, adding a secret to that same memory, or trusting a caller-supplied
syscall/PC would not protect against compromised guest code.

## Architectural Consequences

Signal ABI compatibility and sandbox enforcement are separate contracts.
Supporting SIGSYS/altstack alone may remove a startup failure without preserving
the protection the browser expects. Successful filter installation currently
does not imply preserved policy for adapted syscalls. Unsupported combinations
need an explicit failure contract, not silent sandbox success.

A ptrace-free sandbox-compatible design would need:

1. A policy authority outside guest-writable memory, with kernel-authenticated
   operations and complete coverage of alternate IPC/FD paths. It must preserve
   arguments, thread policy, filter inheritance and relevant call-site semantics.
2. A signal/exec contract retaining application masks, stacks and handlers.
   USER_NOTIF alone returns a result or continues the original syscall; it
   cannot replace arguments or redirect execution to the guest ELF loader.
3. Memory/FD access across fork, exec and protected processes without weakening
   dumpability. Existing controls show that remote reads/FD duplication fail
   after dumpable=0. A pre-opened memory FD belongs to its original address
   space, not a fork child or replacement image. Explicit SCM_RIGHTS/ADDFD work
   but do not supply cooperation automatically in arbitrary programs.
4. Separate checks of browser namespace/helper requirements. Virtual filesystem
   adaptation does not make unavailable kernel namespaces available.

No tested small ptrace-free change satisfies all four contracts. This is not an
impossibility proof or a reason to replace the existing adapter for ordinary
Linux applications. Keep that adapter as the baseline; evaluate any sandbox
design against the negative controls before integrating it. Interpreting filters
in a trusted external supervisor is a possible research direction, not evidence
that syscall identity, memory access and exec have been solved.

## Selective Ptrace Hybrid

```sh
sh native/guest-exec-lab/build-hybrid-fixture.sh BUILD
python native/guest-exec-lab/test_hybrid.py BUILD
# Repeat with fresh native process trees; stop at the first failed assertion.
python native/guest-exec-lab/test_hybrid.py BUILD --runs 50
```

`test_hybrid.c` is a separate static native fixture, not a new guest launcher.
It combines USER_NOTIF for selected calls with TRACE for selected register/exec
operations. Ordinary execution uses PTRACE_CONT; only a replayed operation has
one observed syscall entry and exit. Single-stepping is a test control for
delivering a signal inside a known helper instruction range, not an execution
backend. Its supervisor owns the child tree through SEIZE, observes
signalfd/notification events with failure deadlines, and enables EXITKILL.
Thread-specific INTERRUPT owns notification cancellation, without generating
an application signal. A process-directed SIGSTOP is unsuitable: repeated
multithreaded controls observed a fresh notification before the intended thread
stopped. No installed application is traced.
The report retains the exact binary upload, device, identity and kernel, and
never reports browser sandbox support.

Sixteen scenarios complete under the same device's actual UID 2000, including
100 consecutive complete runs with thread-specific cancellation:

- Mixed TRACE/USER_NOTIF preserves the fixture's original-call ERRNO, application
  TRAP and KILL decisions. Application SIGSYS executes on its own alternate
  stack and can issue a nested notified call. A thousand getpid/read/write
  cycles produce no syscall trace stops; one explicitly traced getppid produces
  exactly one. This is an interception-count check, not a performance benchmark.
- Register-only exec redirection replaces the image and preserves tracing.
  It also works when the child has set dumpable=0. The fixture supplies known
  strings and reexecutes itself; this is not arbitrary guest ELF/argv loading.
- TRACE rechecking observes the modified execveat path argument. On this ARM64
  kernel, changing x0 alone does not change seccomp's saved orig_x0: an execve
  policy on the replacement pointer is not applied as initially expected.
  A separate control cancels the syscall and reenters the same SVC instruction;
  that fresh entry correctly applies the denial to the modified first argument.
- A tracer attached before dumpable=0 retains register control, but PEEK/POKE,
  process_vm_readv and a fresh proc-mem open are denied. The protected fork
  child has the same boundary; the retained memory FD still reads its parent's
  original address space, not the child's modified copy.
- A short bounded load/store sequence executed inside the guest copies memory
  into a prearranged shared region, including the protected fork child's own
  data. It makes no syscall and does not change dumpability. A breakpoint returns
  control to the supervisor, which restores the original general registers.
- The protected notification control joins these mechanisms: stop a pending
  openat, verify that its old notification ID is invalid, copy its pathname
  inside the guest, then repeat the original SVC with the same PC and arguments.
  Only the fresh, kernel-validated notification receives a descriptor through
  ADDFD. Parent and protected fork child each receive exactly one /dev/zero FD.
  An invalid guest pointer produces EFAULT, without FD injection or delivery of
  the copy helper's SIGSEGV to guest code. A later application openat denial
  produces no notification. This fixture uses harmless fixed-path requests,
  not the production filesystem RPC or a general path broker.
- A second thread installs an openat ERRNO filter with TSYNC after pathname
  copying, before replay. The replay receives EACCES without another usable
  notification or descriptor transfer. Observing the selected syscall's exit
  retires the operation even when the new filter prevents notification.
- Unshielded helper execution exposes its temporary PC to an application
  signal handler. The masked control uses GETSIGMASK/SETSIGMASK around the
  bounded copy. A queued signal remains pending until original registers and
  the mask are restored; the handler receives its original PC/result, siginfo,
  mask and application alternate stack. It can issue a nested notified syscall.
- Notification-copy controls restore the mask at the original syscall's fresh
  entry, not while still returning from the helper. The kernel then owns the
  signal frame and EINTR/SA_RESTART decision. Both modes preserve the expected
  application PC, argument/result and mask. The handler opens its own file
  through the same copy/replay/ADDFD path; the interrupted outer operation
  transfers no descriptor. With SA_RESTART, the handler also changes the outer
  pathname. The restarted operation recopies that changed input and transfers
  exactly one descriptor, independently of the handler's own descriptor.
- A second tracer's attach request is rejected. Browser crash handlers and
  debuggers therefore need their own compatibility investigation, rather than
  assuming that ptrace is invisible to the application.

These results provide a concrete hybrid direction, not a security proof. The
copy region is guest-writable and must never carry trusted authorization state.
Production mediation would have to own kernel request identity, process-image
epochs and operation admission outside the guest, validate all copied input,
and close alternate filesystem/RPC paths. Notification cancellation is safe to
repeat here only because no external operation was performed for the old ID.
Do not generalize this into replay after an unconfirmed filesystem mutation.

The tested operation protocol has separate cancellation, copy, replay-entry,
replay-result and committed phases. A kernel signal interrupt retires the
uncommitted operation before delivering the handler: nested calls and restarted
calls receive independent kernel requests and copied input. Stale notification
IDs never authorize work. This keeps kernel restart decisions separate from
supervisor ownership instead of reconstructing application signal frames.

Remaining gates include broader concurrent policies (including TRAP/KILL during
copy/replay), arbitrary signals/masks and job control, helper/mapping loss,
complete register-state preservation, exec/fork/vfork lifecycle, image-specific
helper placement and Crashpad/other tracer coexistence. Temporary masking of
one thread does not prove transparent process-directed signal routing in a
multithreaded application. The prototype deliberately uses fixed bounds
and known native code sites; it does not implement production signal semantics,
a protected filesystem broker or missing kernel namespaces. Real browser
startup is checked separately below. The working SIGSYS adapter remains the
production path.

## Real Browser Boundaries

The [early browser probe](browser/README.md) connects the shared ELF loader and
filesystem operations to an isolated TRACE supervisor with external USER_NOTIF
fstat. Other adaptation remains in-process; this is not the protected authority
exercised by the synthetic controls. Full-browser startup checks do not relax
sandbox flags. A separately labelled renderer-only stage disables parent/zygote
isolation, while renderer children install their unchanged seccomp policies.
Neither stage changes Android security settings. Tests use fresh profiles,
headless local pages and actual UID 2000, without Android UI interaction.

| Check | Observation |
| --- | --- |
| Debian Firefox 140.16.0esr and Chromium 154.0.8037.57 versions | Twenty successive pairs complete through ordinary shell exec. Version output is not browser support. |
| Firefox child startup | Real child images, application SIGSYS handlers and seccomp TSYNC are reached without the production SIGSYS/altstack reservation. |
| Adapter self-memory read | The Firefox filter traps process_vm_readv while adapting sendmsg. The probe's instruction-only copy removes this specific requirement without changing that filter. |
| Firefox fstat, TRACE-only control | The actual filter traps the adapter's socket/connect during fstat; 60 calls return ENETUNREACH and no PNG is produced. Parent exit zero is not workload success. |
| Firefox with external metadata | Two fresh-profile launches create inspected 800x600 page images: the requested heading and a JavaScript-computed `MD-JS-39483`. Real child filters are installed; 1222 and 1154 fstat calls have zero errors. Full confinement is not established. |
| Chromium ordinary full-sandbox startup | Exits one with `No usable sandbox`; no DOM is produced. This is separate from signal ownership or the fstat transport problem. |
| Chromium renderer-only stage | Three fresh-profile runs execute JavaScript and return its computed DOM. Each run has four real renderers with successfully installed 652-instruction seccomp filters. Parent/zygote and auxiliary processes are not sandboxed. |
| External descriptor metadata in that stage | 5149, 5137 and 5062 fstat calls, zero errors; logical mode/link count still come from the existing inode store. This does not enforce a restricted root. |
| Native namespace control | Unshare of CLONE_NEWUSER still returns EINVAL outside the guest adapter. A filesystem broker cannot supply this kernel capability. |
| In-process transport isolation | A small filter allowing fstat but denying socket or connect causes fstat to return EPERM. This isolates the architectural conflict independently of Firefox. |
| External transport composition | The same allowed fstat succeeds with socket/connect denied. Adding an application fstat ERRNO denial takes precedence over notification. |
| Protected guest metadata | Dumpable=0 fstat retains inode identity and EFAULT/EBADF behavior through temporary FD export and register-only result copying. Normal and nonleader guest ELF reexec pass. Sendmsg/fstat denials leave the output untouched; dumpability stays zero. |
| Actual helper, zygote and renderer | An ordinary-mode copy of Debian's unchanged helper completes shared proc-root restriction and protected guest ELF launch. Three stock renderer runs pass thread/directory checks, install a real second filter (kernel status verified) and send nonempty Mojo startup data. No page or full confinement through this authority is certified. |
| Retained proc descriptors | Copied-path external resolution plus task-affine fd inspection preserve actual file/directory types, fork and protected EFAULT/EBADF. Negative controls reject escapes and preserve application openat/fstatat denials. |
| Full browser with unadmitted helper | Rejects the ordinary helper's ownership/mode with SIGABRT/134. No page is produced; this remains the negative admission control. |
| Guest identity contract | Model tests compare 128 unprivileged setresuid/setresgid combinations with native results per run. Opt-in lab ELF admission maps the sealed image, publishes ID/AT_SECURE and connects credential/drop/no_new_privs to guest syscalls. Three stock-helper/zygote/renderer chains and three credential controls pass under actual UID 2000. Production set-ID rejection is unchanged. |
| Immutable ELF catalogue | The lab service unifies owner/mode, stat/fstat, independent opens, hardlinks and sealed bytes. Writes, truncation and writable shared mappings are rejected; replacing a name does not transfer admission. This is bounded research storage, not general filesystem authorization. |
| Full browser with admitted helper | Three fresh-profile stock Chromium launches return JavaScript-computed DOM without sandbox-disabling flags; one produces an inspected screenshot. Each installs five application filters under actual UID 2000. Child thread groups still have SIGTRAP exits; `chrome://sandbox` times out. Page execution is demonstrated, complete confinement is not. |
| Descriptor exec offset | Readable descriptors are retained without proc reopening; positional ELF/shebang reads preserve the original caller offset. This also permits sealed memfd execution despite shell SELinux denying a proc reopen. |
| Addressless socket input | recvmsg/recvfrom without a requested source address need no SO_DOMAIN lookup. A getsockopt denial remains enforced; valid IO and EBADF/EFAULT controls pass. |
| Current-image reexec | `/proc/self/exe` retains the guest executable identity and AT_EXECFN spelling. This prevents Chromium utility processes from seeking their ICU data beside a literal proc alias. |
| Memory and process controls | Invalid input/output buffers return EFAULT and valid IO remains usable across 64 fork children and 256 pthreads, on both the probe and production adapter. |
| Concurrent group death | Two controls kill 32 four-worker groups each during ordinary and dumpable=0 adaptation. Lost register/mask access or notification interruption retains ownership until exact exit/exec. The parent must still complete valid IO. Firefox also renders the inspected `MD-JS-39483` page through this lifecycle path. |

The in-process Firefox transport failure and external-metadata rendering success
are separate controls. Fresh artifacts are checked separately from parent exit
status. Full GUI, page interaction, crash reporting and security equivalence
remain unverified. The lab supervisor resolves ESRCH during stop inspection,
register/mask changes, notification interruption or resume only through the exact
owned exit/exec event, not an assumed exit or fabricated register snapshot.

Allowed fstat is externally handled in the partial browser probe, using an
exact thread pidfd rather than a thread-group FD-table assumption. Protected
processes use temporary SCM_RIGHTS export and register stores, followed by
same-site kernel rechecking. A policy-completed replay without a notification
must never trigger result copying, even when its synthetic return value is zero.
Complete descriptor admission and enforced filesystem context remain unfinished.
The next criterion is the whole real process tree under a protected resource
authority, not acceptance of page execution alone as a sandbox.
Leaving the namespace RPC client inside a sandboxed process adds syscalls that its
application policy need not permit. Moving it outside must also close direct RPC
bypasses, retain guest descriptor/offset and metadata semantics, and validate
memory, policy, cancellation and image ownership. Do not solve this by allowing
new sockets in the browser filter or replacing virtual metadata with raw fstat.
The admitted helper supplies a limited guest ABI, not native namespaces. All
browser reports explicitly retain `sandboxSupportEstablished=false`.

The [Chromium isolation investigation](chromium-isolation.md) records the exact
failed clone and native kernel capability controls. It also identifies the
stock SUID path's fallback when PID namespaces are absent, without mistaking
that fallback for unprivileged chroot support. Generic enforced filesystem
views and guest credentials are a research candidate, not an implemented
replacement for browser isolation.

The [external domain fixture](chromium-isolation.md#external-domain-fixture)
adds a bounded, default-deny read-only broker under UID 2000. Independent roots,
raw SVC path denials and original application ERRNO/TRAP/KILL policies pass;
an intentionally inherited host FD demonstrates why path mediation alone is
insufficient. Protected-memory access fails closed pending integration of the
hybrid copy primitive. Nested application USER_NOTIF listeners are separately
blocked by the kernel's duplicate-listener rule. This fixture is not a virtual
chroot, guest credential implementation or protected browser.

The separate [helper/context control](chromium-isolation.md#helper-and-protected-memory-control)
now combines shared root/cwd ownership with protected copying. Matching unchanged
Chromium helper functions pass, including real dumpable=0 and a restricted
protected fork. Full-main mode also passes the helper's environment/API protocol
and native exec to a fixture client. Each mode passes ten runs under UID 2000.
The explicit guest ABI reports PID/network namespaces unsupported; it does not
change their native EPERM into successful isolation. The test is static Bionic,
not a set-ID Debian ELF or a rendered browser. Fixed-image handoff constraints,
mutable exec arguments and arbitrary resource lifetimes remain gates before
connecting this authority to the actual browser loader/store.

## Primary References

- [Linux seccomp filter contract](https://docs.kernel.org/userspace-api/seccomp_filter.html):
  action precedence and notification semantics.
- [Linux 6.12 implementation](https://github.com/torvalds/linux/blob/v6.12/kernel/seccomp.c):
  notification lifecycle and replies.
- [Firefox sandbox implementation](https://github.com/mozilla/gecko-dev/blob/master/security/sandbox/linux/Sandbox.cpp):
  its SIGSYS handler chain and kernel filter installation. This source reference
  is not a claim of an exact-version Firefox policy audit.
- [Chromium Linux sandbox](https://github.com/chromium/chromium/blob/main/sandbox/linux/README.md):
  seccomp and namespace/helper layers are distinct requirements.
- [gVisor systrap](https://github.com/google/gvisor/blob/master/pkg/sentry/platform/systrap/README.md):
  an architectural reference for separating a userspace guest ABI from its
  supervisor. It does not demonstrate a small Android port or establish that
  every part of its lifecycle meets this project's no-ptrace constraint.
- [Android 16 common-kernel ptrace](https://android.googlesource.com/kernel/common/+/refs/heads/android16-6.12/kernel/ptrace.c):
  register tracing is not unconditional access to protected process memory.
- [ARM64 syscall arguments](https://android.googlesource.com/kernel/common/+/refs/heads/android16-6.12/arch/arm64/include/asm/syscall.h)
  and [syscall entry](https://android.googlesource.com/kernel/common/+/refs/heads/android16-6.12/arch/arm64/kernel/syscall.c):
  orig_x0 is populated at syscall entry and used by seccomp argument inspection.
- [ARM64 signal delivery](https://android.googlesource.com/kernel/common/+/refs/heads/android16-6.12/arch/arm64/kernel/signal.c):
  do_signal owns syscall restart addresses, EINTR and application signal frames.
- [Crashpad ptrace attachment](https://chromium.googlesource.com/crashpad/crashpad/+/refs/heads/main/util/linux/scoped_ptrace_attach.cc):
  an independent trace owner is an application compatibility concern.
