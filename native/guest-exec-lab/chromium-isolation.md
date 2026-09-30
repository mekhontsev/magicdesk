# Chromium And Guest Isolation

This investigation distinguishes browser ABI compatibility from real isolation.
It concerns prepared Debian ARM64 Chromium under actual shell UID 2000, not the
Android Chrome APK. Full-sandbox acceptance does not relax browser flags or
Android security policy. The separately labelled renderer-only stage below
intentionally omits layer-one protection and is not a passing full-browser test.
The working guest adapter remains an execution mechanism, not a sandbox.

## Measured Boundary

The [browser probe](browser/README.md) observes namespace-related calls without
changing their arguments or return values. Debian Chromium
`154.0.8037.57-1~deb13u1` calls ARM64 `clone` with flags `0x10000011`
(`CLONE_NEWUSER | SIGCHLD`) and receives `-EINVAL`. It then reports
`No usable sandbox` and exits one without producing the requested DOM.
The ordinary startup store has no installed chromium-sandbox helper. The matching
signed-repository package is extracted only into a disposable test directory:
its file is actually owned by UID 2000, not root, and the production loader
rejects its set-ID mode. Installing the package alone cannot supply authority.

The independent native fixture excludes the guest loader, syscall adapter,
ptrace and its seccomp filters:

```sh
sh native/guest-exec-lab/build-isolation-fixture.sh BUILD
python native/guest-exec-lab/test_isolation.py BUILD
```

It records the actual UID, capabilities, no_new_privs and seccomp state, kernel
configuration when readable, and exact results. Namespace/chroot/credential
changes occur only in owned short-lived children. An observation timeout is a
failure, never a capability result. No APK install, root or Desktop is involved.

On RM11/NX809J, Android 16, Linux 6.12.23, 4 KiB pages:

| Native check | Result |
| --- | --- |
| Entry identity | UID/EUID 2000; permitted/effective capabilities zero; no_new_privs=0; seccomp=0 |
| User namespace via unshare, clone and clone3 | EINVAL |
| PID, network and mount namespaces via those three calls | EPERM |
| Combined user/PID/network namespace | EINVAL |
| chroot to the child's own proc fdinfo directory | EPERM |
| Changing real/effective/saved UID to zero | EPERM, still UID 2000 |
| Landlock ABI query | ENOSYS |
| seccomp USER_NOTIF and TRACE actions | Available |

The readable kernel config confirms USER_NS, PID_NS and SECURITY_LANDLOCK are
not compiled in; NET_NS, SECCOMP and SECCOMP_FILTER are enabled. A PID namespace
request returning EPERM does not prove its implementation exists: another
permission check can fail before feature-specific handling. Conversely, the
user-namespace EINVAL is corroborated by config, not guessed from errno alone.
These observations apply to this kernel, not every Android device. Shell's
Android service permissions do not imply Linux CAP_SYS_ADMIN or CAP_SYS_CHROOT.

## Stock Browser Contracts

Matching upstream version sources identify two first-layer paths, separate from
the per-process seccomp layer:

- [Zygote host selection](https://github.com/chromium/chromium/blob/154.0.8037.57/content/browser/zygote_host/zygote_host_impl_linux.cc)
  prefers a working user namespace, then considers a SUID helper; otherwise it
  stops. The Debian build adds distribution guidance to the fatal message.
- [Credentials](https://github.com/chromium/chromium/blob/154.0.8037.57/sandbox/linux/services/credentials.cc)
  probes user-namespace creation, UID/GID maps, capability dropping and a nested
  unshare. Merely returning a child PID is not the complete contract.
- [Namespace entry](https://github.com/chromium/chromium/blob/154.0.8037.57/sandbox/policy/linux/sandbox_linux.cc)
  expects a PID-namespace init when entering from the zygote, removes filesystem
  access and constrains capabilities. UID/PID/proc/SCM_CREDENTIALS and inherited
  filesystem state must agree if these operations are virtualized.
- [SUID host checks](https://github.com/chromium/chromium/blob/154.0.8037.57/sandbox/linux/suid/client/setuid_sandbox_host.cc)
  require an executable root-owned set-ID helper. The runtime rejects set-ID
  ELF files and sets no_new_privs; installing the helper does not supply real
  root authority. [Kernel no_new_privs](https://docs.kernel.org/userspace-api/no_new_privs.html)
  prevents set-ID exec from adding host privileges and cannot be undone.
- The [SUID helper](https://github.com/chromium/chromium/blob/154.0.8037.57/sandbox/linux/suid/sandbox.c)
  tries PID+network and PID-only namespaces, but explicitly continues when both
  are unsupported with EINVAL. Therefore missing PID namespaces alone are not
  an absolute blocker for every stock Chromium first-layer path. It still needs
  an effective empty-root chroot, dumpability changes, credential dropping and
  its ordinary IPC/lifecycle protocol.
- The [SUID client](https://github.com/chromium/chromium/blob/154.0.8037.57/sandbox/linux/suid/client/setuid_sandbox_client.cc)
  waits for helper completion and checks loss of filesystem access. Answering
  its handshake or concealing a single proc pathname is not equivalent to
  enforcing the promised isolation.

## Universal Runtime Direction

There is no measured native-shell workaround that preserves these contracts.
A userspace implementation is a separate design candidate, not proof of browser
support. Its common unit should be an externally owned **execution domain**,
not a browser-name exception or a fake successful namespace call.

The narrower first research target is an enforced guest filesystem root and
guest credentials. This could exercise the stock SUID path before implementing
the broader user/PID/network namespace ABI. Guest privileges would authorize
operations only within the domain; host UID 2000 and its capabilities would
remain unchanged. This requires real broker-owned permission checks, not merely
reporting UID zero or changing the helper's stat result.

Required contracts:

1. **Host confinement:** an immutable kernel filter mediates every host escape
   route. A guest-accessible raw-syscall gate, direct same-UID filesystem RPC,
   host proc aliases, unsafe descriptors or unrestricted process control would
   invalidate the boundary. The production execution adapter intentionally does
   not provide this confinement and must not be advertised as doing so.
2. **Filesystem views:** root/cwd ownership follows fork, exec, unshare and
   CLONE_FS. A child sharing fs state can restrict the intended parent's view;
   an unrelated process must retain its own view. Resolve paths, dirfds, proc
   aliases and transferred descriptors consistently, with no host-path fallback.
3. **Credentials:** virtual UID/GID/capability and set-ID transitions are bounded
   by broker-owned guest metadata and the caller's domain. They neither elevate
   Android privileges nor grant unrestricted access to backing-store files.
   no_new_privs and exec security semantics also need an explicit guest contract.
4. **Browser policy composition:** application seccomp, SIGSYS, dumpability,
   alternate stacks and thread synchronization remain meaningful. The external
   broker needs authenticated original requests and scoped resource authority;
   applying an operation on the browser's behalf must not bypass its policy.
5. **Other namespace semantics:** implement only capabilities that are actually
   enforced; unsupported ones fail. A broader user-namespace path additionally
   needs nested identity maps, capabilities, PID lifecycle and credentials in
   IPC. Successful Chromium startup alone cannot validate them.

Acceptance should start with two independent fixture domains and hostile
negative controls, not a browser window: raw SVC escape attempts, direct RPC,
outside dirfds/proc paths, descriptor transfers, sibling process access and
concurrent namespace changes. Check both denied operations and permitted native
IO, then exercise an unchanged helper and Chromium with fresh profiles and
actual rendered output. The earlier Firefox fstat conflict belongs to this same
external operation boundary, rather than a separate Firefox backend.

This is broader than the current selective-ptrace plumbing. Native computation
and safely admitted IO may remain direct, but the syscall/resource boundary
must be complete. No performance or general sandbox-equivalence claim follows
from these capability probes. A userspace-kernel design such as
[gVisor](https://github.com/google/gvisor/tree/master/pkg/sentry) is a reference
for the size of that responsibility, not a demonstrated drop-in Android solution.

## External Domain Fixture

```sh
sh native/guest-exec-lab/build-domain-fixture.sh BUILD
python native/guest-exec-lab/test_domain.py BUILD --repeat 10
```

`test_domain.c` tests a narrow read-only filesystem authority under actual UID
2000. It does not load a guest ELF or use ptrace. The trusted launcher creates
seven owned children, transfers their kernel listeners, closes inherited FDs
except the explicit test channels, and seals the bootstrap transport before
entering the test body. Guest code has no listener, root FD, direct filesystem
RPC or instruction-address bypass. Unknown syscalls return EPERM.

Two simultaneous children have different supervisor-pinned root directories.
The kernel notification PID selects the already registered domain; a pathname
or client packet cannot select another domain. The broker copies bounded guest
memory, checks the notification ID, and resolves its private copy with
`openat2(RESOLVE_IN_ROOT | RESOLVE_NO_MAGICLINKS | RESOLVE_NO_XDEV)`. Only read-only
regular files are admitted. `ADDFD_FLAG_SEND` installs the opened descriptor and
completes the request together. Subsequent data reads and safe duplication are
native. No pathname operation uses CONTINUE on mutable guest memory.

Ten consecutive runs on the device above pass. Each confined child completes
173 assertions, with exactly 149 broker requests: 134 admitted and 15 denied.
Additional child controls verify inherited-FD leakage, protected-memory refusal,
listener loss, application KILL policy, and listener replacement attempts.
The runner records the fixture binary hash, app/kernel identity, commands,
actual exit status and console cleanup; `sandboxSupportEstablished` stays false.

| Check | Observed result |
| --- | --- |
| Same `/allowed` path in independent domains | Different expected bytes; 128 repeated opens per child remain in its own root |
| Direct ARM64 SVC, absolute host path, `..`, outside symlinks | No outside file is returned; absolute inside symlinks still work |
| `/proc/self/root`, `/proc/self/fd`, `/proc/self/mem`, `/dev/fd` | Unavailable in the fixture root; no fallback to host procfs |
| Outside dirfd, directory export and writable opens | Explicitly denied, not claimed as supported Linux semantics |
| Socket/connect, SCM_RIGHTS entry points, process memory, ptrace, signals to supervisor, pidfds, alternative open APIs, io_uring, clone/exec/chroot/UID changes | Default-deny filter rejects them before execution |
| Invalid and unterminated path pointers | EFAULT and ENAMETOOLONG, with no FD transfer |
| Reuse of descriptor 100 after closing it | Reads the newly admitted guest object, not the original host object |
| Intentionally inherited host FD | **Reads the host secret despite path confinement**; the ordinary confined children have closed it |
| Added application ALLOW filter | Does not remove the original confinement |
| Application ERRNO, TRAP on its own altstack, KILL | Kernel policy wins; these calls do not reach the broker |
| `PR_SET_DUMPABLE=0` | Memory copy fails; the operation is denied and dumpability remains zero |
| Listener closure | Notified open returns ENOSYS, never direct host access |
| Application creates another listener | EBUSY; a newer USER_NOTIF filter without a listener instead makes open return ENOSYS |

The last row matches Linux's
[duplicate-listener check](https://github.com/torvalds/linux/blob/v6.12/kernel/seccomp.c).
An application-owned notification listener therefore needs a separate design;
it cannot simply be stacked on the runtime listener. This is distinct from
Firefox/Chromium's tested ERRNO/TRAP filters. The
[notification API](https://docs.kernel.org/userspace-api/seccomp_filter.html#userspace-notification)
also requires copied arguments and request-lifetime checks; the fixture does
not infer isolation merely from successful filter installation.

### Scope Of The Result

This confirms a useful primitive: external file authority can coexist with
application seccomp under UID 2000 while admitted data IO stays native. It is
not virtual chroot, a credential model, a sandboxed browser or a complete hostile
workload audit. Roots are trusted fixture directories without mounted procfs,
devices or concurrent namespace mutation. There are no writable operations,
relative dirfd support, mmap, guest process creation, sockets, FD imports or
exec. Denying all of those is not a compatible general Linux implementation.
The protected-memory case deliberately remains a failure, rather than silently
disabling dumpability or granting an in-process raw-syscall gate.

## Integration Boundary

### Real Renderer Seccomp

The browser probe can exercise stock renderer policy before the layer-one
authority is connected to the ELF loader. A test-only `renderer-cmd-prefix`
removes `--no-sandbox` from renderer children of an otherwise unsandboxed parent.
The children execute the real Debian Chromium image, install its seccomp BPF
and retain its SIGSYS handling. This is not a patched browser or an emulated
filter, but it is also **not full browser isolation**.

Three repeated fresh-profile runs under actual UID 2000 execute JavaScript that
computes a value and publishes the resulting DOM. All four renderer processes
in each run successfully install 652-instruction filters. USER_NOTIF services
5149, 5137 and 5062 fstat requests externally with zero errors; virtual inode
mode/link metadata remains intact. Focused negative controls confirm that a
direct application fstat denial still wins. Shared fixes avoid an unnecessary
getsockopt for addressless input and preserve executable identity on proc-alias
reexec; they do not add browser exceptions or relax application filters.

This proves a narrower compatibility point: real renderer seccomp and useful
page execution coexist with selective tracing and external metadata under shell
UID 2000. It does not prove equivalent protection. The parent/zygote and other
processes are unsandboxed, most adaptation still runs in the guest, and raw-gate,
RPC and descriptor escape paths are not closed. Protected output after
dumpable=0 is covered separately below. Its reporting explicitly excludes layer one;
the full-sandbox startup remains blocked.

The next decisive test is to connect the protected root/cwd and memory authority
below to the real helper/zygote/renderer chain, without the partial-stage flags.
Validate denied escape paths together with actual page execution. Metadata work
is subordinate to that end-to-end test, not a substitute for it.

### Helper And Protected Memory Control

```sh
python native/guest-exec-lab/build-suid-context.py BUILD
python native/guest-exec-lab/test_domain.py BUILD --fixture suid-context --repeat 10
python native/guest-exec-lab/test_domain.py BUILD --fixture suid-context --suid-main --repeat 10
```

`test_suid_context.c` compiles matching Chromium `154.0.8037.57` helper sources
unchanged into a static Bionic fixture. The builder pins SHA-256 checksums and
retains their license under BUILD, not in the APK. The ordinary mode calls the
original `SpawnChrootHelper()` and `DropRoot()`. `--suid-main` calls the original
entry point, including API/environment setup, and actually execs a fixture
client. This is not Debian's installed set-ID executable or its glibc loader.

The external supervisor owns refcounted root/cwd contexts. Kernel-observed
CLONE_FS shares the context; an ordinary fork copies it. The helper's chdir,
chroot and final chdir pin its actual proc fdinfo directory and restrict that
context. After the helper is reaped, kernel lookups through the retained proc
directory return ESRCH. Absolute and `..` paths cannot regain the old root.
Changing root leaves already admitted regular-file descriptors intact. An older
private fork still opens the real marker; a later fork inherits the restricted
root. The initial view is host `/` at actual shell permissions, not a prepared
distribution. General relative cwd/dirfd walking is deliberately unsupported.

Dumpability is really disabled by the unmodified helper. When process_vm copy
is denied, selective ptrace cancels that notification, copies bounded memory
without syscalls, and replays the original SVC with the same PC/arguments.
Only a fresh authenticated notification authorizes external resolution/ADDFD.
An invalid pointer returns EFAULT. The protected parent can open the marker
before restriction, so subsequent denials cannot be explained by all memory
copies failing. A protected fork also works. Its added application ERRNO filter
wins over the runtime notification filter without another broker request.

The new native image reestablishes the shared **data-only** mapping and publishes
its address through an observed startup trap; it does not depend on an old ASLR
address remaining free. Context, listener and operation authority stay in the
supervisor. The client sets dumpable back to zero after exec resets it, matching
the zygote's protected-client phase. There is no runtime SIGSYS handler or
instruction-address syscall allowgate.

Ten consecutive runs of each mode pass under UID/EUID 2000 on the measured
device. Ordinary mode observes one shared clone, two private forks, one virtual
chroot, 23 notifications, seven denied requests and eight protected copies with
eight kernel-checked returns. Full-main mode observes one native exec, 28
notifications, nine denials and nine protected copies/returns; its additional
denials are Bionic startup probes of absent property files. All owned children
exit and release their contexts. Reports retain source/binary hashes, device and
app identities and exact console cleanup. `sandboxSupportEstablished` is false.

#### Explicit Limits

The full-main control declares a guest ABI without PID/network namespaces:
those two clone requests return EINVAL, exercising the helper's own fallback.
This is **not** the native-shell result (EPERM), not a successful namespace
operation, and not implementation of PID/network isolation. Neither namespace
environment flag is reported. The helper's non-root warning remains visible;
get/setresuid/gid run natively and actual host identity stays 2000. Virtual root
changes are authorized by this fixture's launch, not a guest credential model.

The native exec handoff admits one known fixture image from a single-threaded
caller without CLONE_VM or application signal handlers. Its CONTINUE reply on
the original pathname is not safe for arbitrary hostile exec arguments; generic
image admission must bind to a trusted object and eliminate those races. The
shared memfd only transports copied bytes and is not a general protected-memory
ABI. Protected stat-output copying, concurrent root mutations/cancellation,
arbitrary signal reentry, descriptor imports and broader clone/exec lifetimes
remain unimplemented here. No Chromium renderer or browser DOM has run through
this authority. Passing the helper is necessary evidence, not browser support.

### Actual Debian Helper And Zygote

The [browser probe](browser/README.md) also exercises the actual Debian ARM64
ELF helper through the guest loader and inode store. Its SHA256 is
`3296f5fa3d33f66684ea5bbbcccf8da79e7ddc034d21704d23c036e21b8288d4`.
This test deliberately uses a separate mode-0755 copy: bytes are unchanged,
host identity remains UID/EUID 2000 and the non-root warning stays visible.
It is not set-ID admission. The production loader's set-ID rejection is intact.

An explicit proc-root domain owns shared CLONE_FS references, ordinary fork
copies and pinned root/cwd handles. The real helper restricts the shared context
to its proc directory and dies. A protected glibc client then observes pathname
denials while retained-file fstat still succeeds. This uses the real guest ELF
and store, but implements only this restricted-root case, not general chroot.

The stock Chromium zygote reaches BOOT, HELLO and sandbox-status IPC with 0x29.
That status advertises SUID-path use and seccomp/TSYNC capability, not successful
renderer sandbox initialization. A test IPC parent supplies the normal fork
request, PID oracle and descriptor mapping, including read-only shared memory
for the required pseudonymization salt. An actual renderer is forked with real
kernel-reported UID 2000. It completes `SandboxLinux::InitializeSandbox` with
real retained-proc operations: thread-count metadata, fd enumeration and actual
file/directory types. The renderer installs its own filter without rewriting
its instructions or disabling sandbox flags, starts threads and sends a nonempty
80-byte Mojo startup packet. Independent kernel status confirms UID 2000,
no_new_privs=1, seccomp=2 and two filters. Three independent runs pass together
with fifteen focused control cases. This does not supply a browser invitation or
render a page through the restricted domain.

Read-only retained paths are resolved externally from copied arguments with
openat2 BENEATH/NO_MAGICLINKS. Protected strings and output use register-only
continuations; fresh original-SVC notification gates results and ADDFD. Proc
`self` is resolved for the tracee, not the supervisor. Protected `/proc/PID/fd`
access cannot simply run remotely: dumpable=0 changes its permission boundary.
A narrowly scoped task-affine operation opens its own fd directory from the
retained proc root; numeric-entry metadata comes from the actual exported FD.
The helper consumes no arbitrary guest pathname. Temporary exports do not
appear as application-owned directories during Chromium's checks. Injected
transport occupying an originally closed number must still produce EBADF.

The control preserves directory types rather than hiding them. It tests fork,
bad pointers, closed descriptors, absolute/parent/magic-link escapes and nested
application denials. Hardened helper/memory admission and concurrent descriptor
import/replace policies remain unimplemented; the raw gate and same-UID RPC are
still not a hostile-code boundary. The separate renderer-only JavaScript/DOM
control also passes but must not be combined with this result into a claim of
a fully sandboxed page.

Without ELF admission, the explicit-helper whole-browser control reports that the helper is not
root-owned mode 4755. The supervisor preserves its SIGABRT (exit 134), including
the fatal path's raced group-stop/continue events, without a supervisor failure.
No metadata/UID falsification or disabled-sandbox retry is used. This rejection
is an explicit negative control, not successful browser startup.

Descriptor lifetime/import policy and host confinement remain requirements,
preserving the application's own close-and-seal sequence. The admitted catalogue
below supplies guest set-ID metadata and credential semantics. Namespace success
or a disabled sandbox flag is not substituted for missing protection.

Primary contracts:
[zygote fork IPC](https://github.com/chromium/chromium/blob/154.0.8037.57/content/common/zygote/zygote_communication_linux.cc),
[sandbox initialization and sealing](https://github.com/chromium/chromium/blob/154.0.8037.57/sandbox/policy/linux/sandbox_linux.cc),
[required child salt](https://github.com/chromium/chromium/blob/154.0.8037.57/content/common/pseudonymization_salt.cc).

### Common Runtime Boundary

The implementation candidate should reuse the existing inode store and ELF
loader, not grow another path translator around this host-directory fixture.
Its protected authority belongs to the supervisor:

- A kernel-observed task identity selects its execution domain, filesystem
  context, credentials and descriptor table. Guest packets cannot choose them.
- The filesystem context owns root, cwd and umask, with reference sharing for
  CLONE_FS and copies for ordinary fork/unshare. Changing root does not silently
  reset cwd or revoke open FDs: those are distinct Linux resources. Chromium's
  helper sequence must actually restrict the shared context and relinquish
  outside references, not only make `/proc/self/exe` disappear.
- Descriptor identity must survive close/dup/reuse, fork/exec, CLONE_FILES and
  SCM_RIGHTS. A numeric-FD cache without these lifetimes is insufficient,
  especially when dumpability prevents pidfd_getfd. Data IO can remain native
  only for admitted descriptors; descriptor creation and transfer need policy.
- Guest credentials and set-ID exec affect only domain-owned resources. Host
  no_new_privs stays set. Guest no_new_privs, ownership checks and capability
  transitions need their own consistent semantics, not a fake getuid result.
- Protected memory transfer uses the separately tested selective-ptrace
  primitive, followed by original-site kernel rechecking before external work.
  A shared copy buffer carries data, never authority. Keep cancellation and
  interrupted/restarted requests distinct from committed operations.
- The filesystem service must not remain callable directly by protected guests
  through its current same-UID abstract socket. Only authenticated supervisor
  requests may act with the domain's authority. Host proc aliases and unsafe
  native syscall/FD paths must not reopen that access.

Root/cwd, descriptor and credential models do not intrinsically require
USER_NOTIF; they are Linux resource semantics. The production SIGSYS/TRAP
transport is nevertheless not suitable unchanged: it owns SIGSYS and wins over
the application's ERRNO/notification actions. The researched hybrid separates
external operation authority (USER_NOTIF) from selective register/lifecycle and
protected-memory control (ptrace). USER_NOTIF is not itself a sandbox, and a
different transport would still need to preserve these same policies.

Production integration requires safe descriptor/image admission, enforced root/cwd
and coherent credential rules across the entire resource boundary. Do not relax
the loader's set-ID rejection in isolation or equate a rendered page with host
confinement. These are implementation gates, not routine packaging; production
remains the existing execution adapter.

The [identity contract](browser/README.md#external-identity-contract) tests guest
set-ID/drop/no_new_privs transitions and kernel-sealed admission under actual
UID 2000. Native differential tests cover unprivileged setresuid/setresgid;
privileged guest transitions are not native root comparisons. The opt-in
`--admit-elf` lab path connects this model to real guest syscalls and the shared
loader through link-time wrappers. It maps the exact sealed ELF, publishes
UID/GID/AT_SECURE before entering its interpreter, protects dumpability and
commits the candidate identity. Actual Debian helper privilege drop and the
stock zygote/renderer chain pass three times, as do focused failed-lookup,
no_new_privs, EFAULT, fork, drop/reacquisition and application-denial controls.

The lab service now owns an immutable object catalogue for the explicitly selected
ELF. Path/fd metadata, hardlinks, independent opens and proc executable-object
lookup refer to its sealed bytes and logical owner/mode. Replacing a name does
not confer the old object's authority. The actual store and host UID stay unchanged.
The supervisor checks object identity, seals and byte equality, rather than
trusting a guest's stat result. Because shell SELinux denies ordinary proc reopen
of the memfd, the bounded fixture retains a separate sealed copy per readable
open; see [limits](browser/README.md#admitted-elf-execution).

Three stock whole-browser launches with fresh profiles execute JavaScript and
return computed DOM, without disabled-sandbox flags. Each installs five actual
application filters; a screenshot is inspected. This crosses the page-execution
gate, not the security-equivalence gate. Child thread groups still exit with
SIGTRAP, and `chrome://sandbox` times out with child and ZIP-handle errors.

Interpreter/library admission, general catalogue authorization, mapped-code
protection and authenticated filesystem RPC remain open. A late admission failure
terminates the new bootstrap rather than restoring the replaced guest image.
A sealed file alone does not protect its mapped guest code or make same-UID RPC
authoritative. Do not promote this mechanism to the APK or claim a preserved
complete browser sandbox on the strength of these page results.
