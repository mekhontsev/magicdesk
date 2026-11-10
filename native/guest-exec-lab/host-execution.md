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
| Robust mutexes | Managed list registration/query, atomic owner-death cleanup and native wakes under User and logical PID namespaces; Shell outside PID namespaces retains native cleanup | Priority inheritance is not emulated; native-kernel limitations and full application acceptance remain separate qualifications. |
| Optional rseq registration | Host-denied `rseq` returns ENOSYS after original guest-policy admission; permitted calls remain native | No emulated restartable sequences. |
| Optional NUMA policy | Host-denied `get_mempolicy`, `set_mempolicy` and `mbind` return ENOSYS after guest-policy admission; permitted native calls are unchanged | No emulated NUMA placement; applications must use their ordinary non-NUMA fallback. |
| Optional resource comparison | Host-denied `kcmp` returns ENOSYS after guest-policy admission; permitted calls retain PID translation and access checks | No synthetic equality verdict; applications use their ordinary fallback when the host cannot compare resources. |
| Optional clock discipline | Host-denied `adjtimex` and `clock_adjtime` return ENOSYS; permitted native calls are unchanged | No fabricated NTP synchronization or clock adjustment; ordinary clocks remain native. |
| Device workflows | Per-distribution init, container, VM, k3s, browser/GPU and development-tool receipts below; shared ABI/debugger/policy/signal suites from a real app UID | Unrun scenarios and unresolved failures in the per-distribution tables; real kernel 5.15 and other firmware |

A component test is not an end-to-end application workflow. Guest listeners
use the shared control-file provider because Linux rejects a second native
NEW_LISTENER in Shroot's existing kernel-listener chain. Standalone protocol
oracles do not substitute for the shared production notification tests.
The Alpine debugger run covers GDB, LLDB, gdbserver, strace and Callgrind.
Its toolchain lacks gcrt1.o for gprof, and Android denies perf_event; those
capabilities are recorded as unavailable, not passed debugger workflows.

### Shared Mechanism Checks

`mount-graph`, `mount-namespace`, `mount-flags`, `supervisor-state`,
`private-memory` and `fs-dispatch` pass under both User and Shell with the native
build bundled in APK
`939cbff8652a1272176a2ba8eaf9724754522f91cc0c1f1a9a81d477b6f94204`.
User runs use that installed APK; Shell stages the same native build directly.
Receipts are `build/matrix-followup-20261110/CASE-{app,shell}-subtree5.json`.
They cover subtree/ancestor retirement and complete namespace snapshots,
retained memory actors, cancellation and exactly-once file completion across
quiescence owners. Every fixture releases its executor and owned resources.

The resource/network ownership model and guest cgroup suite also pass under
both executors with the native build bundled in APK
`e28f3820fcbba7d1540db8f6ac2c7c1b9c220b0e52323da7e5c7adcd54a20300`.
Receipts are `build/matrix-followup-20261110/cgroup-{app,shell}-flow8.json`.
Coverage includes 1152 distinct retained UDP clients for each address family,
TCP admission while those UDP identities remain live, atomic conntrack flush
and descriptor cleanup. Fourteen IPv4/IPv6 lifetime cases cross the real UDP
idle deadline: private and published endpoints, connected reply-first traffic,
explicit deletion and multicast. No packet is retried to hide failed delivery.
Four further IPv4/IPv6 cases delete and recreate the peer namespace: an unbound
client retains native connected IO, while an explicit interface binding must not
silently follow the replacement link.
These component checks do not substitute for nested container network workflows.

Protected native-capture cases deliberately use an unadapted nondumpable child.
Guest `PR_SET_DUMPABLE` remains virtualized under both executors; that fixture
does not imply ordinary guest processes become natively unreadable.

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
memory mutation requires acknowledgement. Admission of that task's next
notification rechecks the packet acknowledgement, so a commit after the shared
event-loop scan cannot leave the handler's syscall behind stale entry ownership.
Failed frame construction releases
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

### Borrowing An Installed Environment

On a rooted local test phone, `borrowed_store.py` temporarily exposes an inactive
Shell installation to the real app executor without copying its rootfs. The
shared `FixtureExecutor` still owns guest execution; `su` prepares host access
only. Production User and Shell libraries remain separate.

```sh
python native/guest-exec-lab/test_borrowed_environment.py \
  --name steamos --command 'cat /etc/os-release; id' \
  --output build/borrowed-steamos
```

The driver runs the same command under Shell, User, then Shell again. `--script`
accepts a local guest shell script instead. Existing `Suite` fixtures can use
`with BorrowedStore(app_suite, library, store) as borrowed` and pass
`borrowed.store` to their ordinary raw-store checks. No duplicate app-only
functional suite is needed.

`BorrowedStore(..., retain=True)` explicitly keeps access for a later test batch.
Its mountpoints live under the app's `no_backup/borrowed-stores`, not Android's
purgeable cache. The helper then exits without restoring access; use its journal
to restore explicitly after stopping the guest. The Shell path remains inaccessible
while its host ownership/labels belong to User. Do not launch either identity
against this store outside the selected test batch.

Preparation retains the catalog lock and requires exclusive native lifetime
locks on the store and its complete source closure. Relative source paths,
physical inode identities, file modes and guest metadata remain intact. Private
bind mounts and temporary host ownership/SELinux labels provide access; no
global SELinux policy change is made. Mixed host access metadata, host file
capabilities, external physical hardlinks and existing nested mounts are rejected
before changing access. Large installations require a metadata walk; borrow once
for a batch, not once for each test case.

Without `retain=True`, completion or coordinator EOF restores host access and
removes only the empty mount scaffolding. Preparation failures restore access
even when retention was requested. The durable `borrow-ID.json` journal permits explicit
recovery after a killed helper or reboot: run the same helper as root in the
global mount namespace with `restore --journal /absolute/path/borrow-ID.json`.
A live guest blocks restoration; stop its exact launch before recovery. Retain
the journal until it records `restored: true`.

This is an access handoff, not a snapshot: guest file changes persist. Use
non-destructive checks or clean up their owned files. Root is a requirement of
this local testing helper, not of User-mode Shroot.

Between distribution batches, stop the owned guests and use the native image
helper's `maintain STORE` under the store's current executor to reclaim unused
projections and untracked physical allocations. A live owner must still fail
with EBUSY. Do not delete a borrowed mountpoint as if it were a disposable copy:
it exposes the original installed data. Restore access only after maintenance
finishes, retaining the same journal.

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

An existing SteamOS installation passes sequential Shell/User/Shell access
handoff without rootfs copies: guest identity, retained file modes and hardlinks,
SQLite updates, fork/exec, local IPC, user/PID/mount namespaces, session D-Bus
and a command inside the installed Firefox Flatpak sandbox. Document and
permission portals and the accessibility bus activate under the real app UID.
The same borrowed installation starts its existing Plasma Wayland desktop through
an App-executor desktop recipe, with Zink/Turnip hardware composition, Dolphin,
Konsole keyboard input, and AT-SPI observation. Graphics tests use an offscreen
Wayland output, without an Android viewer or a virtual Android display.

Stock Flatpak Chromium 155.0.8059.39 and Firefox 158.0.1 pass the complete GUI
workflow: keyboard/pointer input, download, file chooser, WebGL rendering and
normal closure. Chromium reports ANGLE/Zink/Turnip on Adreno 840. Document-portal
acceptance covers exported read/write/revoke, sandbox working directories,
graceful shutdown, crash unmount and service reactivation. Reports are
`build/matrix-steamos-user/session/{chromium-gui,firefox-gui}/gui-report.json`
and `chromium-portal.json`, APK build
`ceaf183e9c5540bab2df433c50cbbf98d176b625b82efca0ff71ad7ade33b104`.

VS Code 1.142.0 with cpptools 1.34.4 and GDB 14.2 passes editor save/readback,
file watches, terminal execution, window reload, a real breakpoint and step,
normal exit and the strict hardware-composition check. The renderer retains
guest UID 1000, no-new-privileges and seccomp. Report:
`build/matrix-steamos-user/session/vscode-gui/gui-report.json`, APK build
`0086fd7468d6b65ebff72eb564b2169a62e0377bc5d2c266d789d9e194f61d71`.
The admitted virtual render node is `/dev/dri/renderD129`; an inaccessible
native minor remains untouched. Admission still requires working KGSL, DMA-BUF
allocation and completion ownership, not just a free device number.

The stock time/date service answers `timedatectl status` without a host-policy
SIGSYS on APK build
`f7d6cd6968bb45225ad9c087a1d8f5a22a1c68cf7635174b2aabe6d3e4b8da29`.
Unavailable clock discipline is reported as unavailable, not synchronized.
Optional-call guest-policy checks pass in Base and lazy entry for both Android
identities (`clock-policy` and `clock-policy-shell` under the same evidence root).

The borrowed SteamOS installation also passes booted Podman 5.5.2, upstream
Docker 29.1.3 with nftables, machinectl/nspawn boot and cleanup, BuildKit 0.34.1
build/cache/secret/export/cancellation, and the seven selected LTP tests as guest
UID 1000. All execute under real Android app UID 10647; Docker and BuildKit are
the existing lab binaries, not additional installer builds. Receipts are in
`build/matrix-steamos-user/containers/`: Podman `1650c1e539e340109096b0dae9007ddd`,
Docker `70756059bd6f4e2498c4427d542838e0`, nspawn
`7426f17e606142319230fb97d2cb2cf2`, BuildKit `062754058bd94f8ca4f86ea773b90d62`
and LTP `c73f8b59197242b7b618efb37aac55bc` (each `oci-services-ID.json`).
These use APK build `52da8e8aa2243ece0febcdf18e167dc6975c42bd840358767e58d39d8531ca89`.
The retained init fixture is not a full default-target boot pass.

LXC also passes the shared container lifecycle fixture under app UID 10647:
`build/matrix-steamos-user/containers/oci-services-0b12f21d84b94786b444cf3723df159c.json`,
APK build `1f5a3d63d8f63eabf699f5ca84f7e916c9773d318cc7f7a7e9b3b051e6592a82`.

Stock QEMU 9.0.0 passes direct TCG Linux boot, the unprivileged filesystem
contract inside the VM and clean poweroff under app UID 10647:
`build/matrix-steamos-user/containers/oci-services-7b4e1bfd803945cdad4b2edb4006b23e.json`,
APK build `ceaf183e9c5540bab2df433c50cbbf98d176b625b82efca0ff71ad7ade33b104`.
This does not establish managed Libvirt or VM networking acceptance.

SteamOS User also passes the complete shared single-node k3s v1.37.1+k3s1
workload with default VXLAN, stock container seccomp, NetworkPolicy, PVC,
Traefik/ServiceLB, server restart and workload deletion. The process fixture
uses Linux service umask `022`, independently of the Android executor's mask.
Receipt: `build/matrix-steamos-user/k3s/oci-services-a493ac6358904a88b6b96fb7fe1d85b4.json`,
APK build `1f5a3d63d8f63eabf699f5ca84f7e916c9773d318cc7f7a7e9b3b051e6592a82`,
real UID 10647, with 162943 completed lazy-SVC calls. This is single-launch
service restart coverage, not the separate persistent full-boot/crash fixture.

The init-managed SteamOS k3s fixture passes all three persistent boot phases
under app UID 10647: initial installation, orderly shutdown/reboot and exact
supervisor SIGKILL/recovery. Node/pod/service identities, CA/token hashes and
synced PVC contents survive; each boot checks replacement container identities,
pod DNS and Service IO. Stock service enablement and fixture cleanup pass.
Receipt: `build/matrix-steamos-user/k3s-boot-initrd-view/oci-services-bcf5a001578f4d09be5950fe8f1423bc.json`,
APK build `cdd77f5eed2c39c62e1b2e3f0fab4ce89274f823126d87e920b7fc27eb59bfe8`,
with the current installer-generated init helper. That helper prepares the
recovery image's persistent `/etc` overlay before PID 1 reads services.
The boot uses a private `bridge`: `routed` reserves the same Android port and
cannot provide an app-UID DNS stub on privileged host port 53.
This service/recovery pass does not certify every default-target service or
shutdown diagnostic below.

### Robust Mutex Qualification

On NX809J / Linux 6.12.23, app UID 10647 passes all twenty libc robust cases,
thread identity and PI admission/fallback checks in both SteamOS glibc and
Alpine musl. This includes owner death after nonleader exec, childless ECHILD,
NULL waitid output, WNOWAIT, stop/continue, __WNOTHREAD, signalfd and adoption.
The installed APK build is
`0c5f7d1ac84d34ae509650f676e0848fc630e42cac57a98575652e5a45a48ee6`.
Receipts:

- `build/matrix-close-steamos/robust-integrated-23/oci-services-9bd52cb78d294437a3259836c1634269.json`
- `build/matrix-close-alpine/thread-identity-23/oci-services-c436fb92c18943d39f9515e0f396e6c9.json`
- SteamOS strace/GDB thread-identity regression:
  `build/matrix-close-steamos/robust-debuggers-23/oci-services-687016d5ecb5481e8edf184b4399f0e9.json`

Shell's Armtix/glibc static control passes all ten managed PID-namespace cases.
Outside that namespace, the nonleader-exec case fails identically under native
Android and Shroot: the process-shared mutex times out instead of reporting
EOWNERDEAD. The other nine cases pass in both controls. This is a qualified
native-kernel limitation (**U**), not a managed-path pass or a reason to weaken
the assertion. Receipt:
`build/matrix-close-armtix/robust-native-23/oci-services-9075427615e14606a8a2ace6b89ea99b.json`.
Shell keeps native registration outside logical PID namespaces.

The component suite tests bounded traversal and fourteen retained-executor
lifecycle cases, separately from libc integration. App receipt:
`build/matrix-close-steamos/robust-components-app-23.json`; Shell receipt:
`build/matrix-close-steamos/robust-components-shell-24.json`.
Supervisor-state and event-fairness suites pass under both executors, including
exhausted wait-output buffers and worker progress under continuous ptrace stops:
`build/matrix-close-steamos/robust-{supervisor,fairness}-{app,shell}-24.json`.
These component reruns use APK
`d9c1d46c6dfb145de4830eee2bc1662bbeee865864ba9b918519361c65b38143`.
Application acceptance is separate from these runtime checks. On APK
`2a97867ea16e958af6da5ee381094be1d6f818ce5150721f1a3f5fc435df81af`,
Steam client 1792618285 and its stock webhelper render the library and selected
game details in offscreen Plasma/Wayland and accept input and forwarded commands.
Evidence is under `build/matrix-close-steamos/steam-robust-30/`, with
`steam-final.png`, `steam-details-final.png` and command logs.
Normal shutdown leaves no client or webhelper while 100 sandboxed systemd
services run four at a time; see
`build/matrix-close-steamos/steam-final-{exited,services}-30.log` and the
`steam-final-30` session artifacts.
This qualifies the client, not game execution or a sandbox claim for
Valve's own webhelper launch policy. Alpine Podman qualifies its full booted
workflow on the same APK; see [Alpine User](#alpine-user).

The shared supervisor-state suite passes under User and Shell on this APK:
`build/matrix-close-steamos/robust-supervisor-{app,shell}-30.json`.
It exercises robust creation/retirement alongside the other quiescence owners,
including retained memory-repair stops, superseding terminal events, parked
checkpoint ownership and parent/child exit admission.
The mount API suite passes under User with SteamOS glibc and Shell with Armtix
glibc, including libc mount-table rereads under signals without SA_RESTART:

- `build/matrix-close-steamos/mount-api-app-30/oci-services-00377304263345f882d4e6785f40d904.json`
- `build/matrix-close-armtix/mount-api-shell-30/oci-services-c35cf3b4f0544a5db298c7a21e66a882.json`

SteamOS also passes a four-thread mount-table stress test and 100 service
launches, four at a time, retaining ProtectHostname, PrivateTmp and
ProtectSystem=strict: `build/matrix-close-steamos/mountinfo-signals-30.log`
and `build/matrix-close-steamos/mount-stress-30.log`.
No result here establishes untested kernel/firmware compatibility.

### SteamOS User Open Work

- Close-event watches on a launch-owned PTY pass through both its device path
  and `/proc/self/fd` with the installed SteamOS Python/glibc:
  `build/matrix-close-steamos/console-watch/oci-services-cfcac18826c84b2fa89fca0d108b1831.json`,
  APK `180f55949447c35c01d4024877f2442bca1bf7ac1449b7f7663ec8b938109fda`.
  A host-denied watch on an inherited, externally owned `/dev/console` remains
  outside this contract; the runtime cannot infer external open/close events.
- Libvirt is blocked by package availability in this installation's enabled
  repositories. Direct QEMU acceptance does not substitute for Libvirt.

The shared boot fixture uses the recipe's `magicdesk-init prepare` contract.
With a private bridge, SteamOS User passes default-target startup with no failed
units, journal/system-bus IO, logind restart, binfmt execution, RTKit lifecycle,
Avahi IPv4/IPv6 mDNS and firewalld port-rule addition/removal across three
service restarts. Shutdown includes successful resolver unmount beneath the
recovery `/etc` overlay. Receipt:
`build/matrix-close-steamos/boot-handles/oci-services-bf17fdb74ade4dfabbfa36dd79153142.json`,
APK build `03ac501d7db1e1a8e49c4e96eddb2010798c489b874584b2f2d841ae727a4de2`.
Real TCP/UDP traffic in both IP families also passes denied/allowed/denied
cycles across cold start and three firewalld restarts:
`build/matrix-followup-20261110/steamos-firewalld/oci-services-403cb9e0642446a3aac1c960ab0ccb4f.json`,
build `0a58faea397c009f1afdaf2e9cf7cc2bb854f47b7022fbd37d6e9d1d3eaeb9f7`.
The shared nft tests separately check returned object handles, echo-before-ACK
ordering and rollback for set creation/update and object deletion. These checks
qualify the tested port policy, not every firewalld rule type.

### Debian User

Recipe-installed Debian 13.7 uses the same borrowed-store handoff, without a
rootfs copy. These runs use real app UID 10647, systemd 257.13, and the common
fixtures. Graphics uses offscreen Weston and the installed Turnip profile.
Pairs use the [matrix codes](validation-matrix.md#reading-results): lazy/Base.
An unrun Base workflow is not inferred from shared code or component tests.

| Workflow | Result | Checked scope |
| --- | --- | --- |
| Retained systemd services | P/N | PID 1, system bus and service lifecycle |
| Full default-target boot | P/P | Stock getty and default services, journal/system-bus IO, logind restart, binfmt and IPv6 networkd in a private bridge |
| Podman and Docker | P/N | Booted daemons, overlay storage, TCP/UDP publication, named peers and cleanup |
| Rootless Podman and Docker | P/N | Guest UID 1000, stock launchers, routed network and TCP/UDP publication; Docker with cgroup v2 |
| Libvirt/QEMU | P/N | Managed TCG Linux boot, pause/resume, guest contract, poweroff and undefine; not VM networking |
| BuildKit | P/N | Build, cache, secrets, export and cancellation |
| LXC | P/N | Three boot/network/stop cycles, including forced termination and cleanup |
| nspawn and machinectl | P/N | Command/network checks and three full container boot cycles with private users, services, shell, veth IO and restart |
| Selected LTP | P/N | Seven shared tests as guest UID 1000 |
| Flatpak Chromium and Firefox | P/N | Input, download, file chooser, WebGL, retained sandbox and normal application exit |
| VS Code | P/N | Editor, watch, terminal, reload, breakpoint/step, hardware composition and normal exit |
| Document portal IO/crash recovery | P/N | Exported read/write/revoke and crash-triggered unmount |
| Document portal graceful stop | U/N | Stock 1.20.3 signal-handler deadlock described below |
| Single-node k3s workload | P/N | Node/CoreDNS, services, NetworkPolicy, PVC, rollout/scaling, Traefik/ServiceLB, restart and deletion |
| Persistent init-managed k3s | P/N | Stock systemd installation, orderly reboot and supervisor SIGKILL/recovery; retained identities and PVC, replacement containers, DNS/Service IO and cleanup |

Container, GUI and portal receipts are under `build/matrix-debian-user/gui-fixed/`
on APK build `4ced25a711379610d9f28ecd0008274cf8eda987b22f622cce01cfa5931b60fe`:
Podman `69ae8bba63e34a7abac59bdc241bb64b`, Docker
`288e89cec051493387f347334c08154d`, Libvirt `5339993cd15c44ff9961f132ce14311e`
(each `oci-services-ID.json`), and
`{chromium-gui,firefox-gui,vscode-gui}/gui-report.json`.
The checked packages are Chromium 156.0.8078.47, Firefox 159, VS Code 1.140.0,
Docker 26.1.5+dfsg1, Libvirt 11.3 and QEMU 10.0.13.
Rootless Podman receipt: `build/podman-results/oci-services-2247b050997744b796e37a51148356ef.json`.

`build/matrix-debian-user/services-final/results.json` records successful
BuildKit, LXC, LTP, nspawn and machinectl reruns and session cleanup on APK
`e201d8aba83f45e2eeaad654a1b015f3509e9e2d7de4bfec4f8a9e8a9cd616c5`.
Rootless Docker with cgroup v2 passes on that build:
`app/build/shroot/oci-services-cb53a7436da44190931d9a85d23d2911.json`.
The single-node k3s v1.37.1+k3s1 workload receipt is
`build/matrix-debian-user/k3s-kernel-signals/oci-services-cc89335ec13149739c8bfeb8c8546cd7.json`,
APK `9d86d1738748695220954034ac0d44f533f1b513200ec29e348646a96cfc064b`.
The separate persistent three-boot fixture passes on APK
`e201d8aba83f45e2eeaad654a1b015f3509e9e2d7de4bfec4f8a9e8a9cd616c5`:
`build/matrix-debian-user/k3s-boot/oci-services-cfa12807543e4b559011f9d7e4f6c753.json`.
Its focused service target does not establish full default-target boot.

The signal component passes 77 instruction-level cases under both Android
identities. App-UID execution on APK
`9d86d1738748695220954034ac0d44f533f1b513200ec29e348646a96cfc064b`
also passes three stock-Go stress pairs with/without seccomp, including UDP IO
and concurrent child exec/reaping. The Shell component uses the same locally
built native fixture, independently of the installed APK. Receipts are
`build/matrix-debian-user/machine-signal-app/oci-services-bbd7caba1cfd48969789ce39795e02c6.json`,
`build/matrix-debian-user/machine-signal-fixed/oci-services-6a5aa3504fd845219eaebf393b425ed0.json`
and `build/svc-patch-results/oci-services-8762e6552abd4582a1ab3d2d6acbe651.json`.

The complete shared policy suite passes 242 cases across Base and lazy entry
under app UID on that APK:
`build/matrix-debian-user/policy-final/oci-services-26c5d6b566ca4f00a56d24756ad66322.json`.
APK `862d41f143e37e09ac28d2ad767d1e7098ce87248492116d0294940b97c19d46`
also passes the socket-wait selection and 34 ancillary-IO checks under both
executors (`build/matrix-debian-user-{supervisor,ipc}-final{,-shell}.log`).
VS Code's complete graphical scenario passes again in
`build/matrix-debian-user/gui-final/vscode-gui/gui-report.json`, including
guest UID 1000, seccomp, hardware composition and normal exit.

The graceful portal-stop failure uses xdg-desktop-portal 1.20.3+ds-1,
GLib 2.84.4-3~deb13u5 and FUSE 3.17.2-3. The captured stack enters
`g_main_loop_quit` from an asynchronous signal handler while the interrupted
GLib loop owns its mutex, matching
[upstream's signal-handling fix](https://github.com/flatpak/xdg-desktop-portal/pull/1896).
Evidence: `build/matrix-debian-user-portal-{fixed,stacks,auto-unmount}.log`.
Browser chooser success and crash recovery do not promote graceful stop to P.

Full default-target boot passes on APK
`c1c280a6d56c0ba48bb524255767562170db46fd0106c756cfd3c1267c1dda0b`:
`build/matrix-debian-user/boot-bridge-lazy/oci-services-4c07166eee1442b5bbee021cab9b1c7f.json`
and `build/matrix-debian-user/boot-bridge-base/oci-services-e2c2775ac1b749c9b9d51ce0cd1c536a.json`.
Both report `running` with zero failed units, actual journal/system-bus IO,
binfmt registration and networkd IPv6 RA/SLAAC, route/MTU, TCP/UDP and withdrawal.
The private bridge allows LXC's DHCP server to own guest port 67; routed mode
preserves Android port numbers and cannot grant app UID a privileged host port.
No getty unit or assertion is disabled. The shared devpts suite passes private
and host-backed PTY observation, controlling terminals and PID ownership in both
entry modes and under both Android identities:
`build/matrix-debian-user-pty-{app-v3,shell-v5}.log`.

### Arch User

The borrowed recipe-installed Arch Linux ARM uses app UID 10647 and stock
systemd 262.1. The retained graphical fixture uses offscreen Weston with the
installed Turnip profile. Pairs are lazy/Base; pending workflows remain N.

| Workflow | Result | Checked scope |
| --- | --- | --- |
| Full default-target boot | P/P | Stock services, journal/system-bus IO, logind restart, binfmt and IPv6 networkd in a private bridge |
| Retained graphical session | P/N | Ordinary guest UID 1001, user manager and offscreen Weston; rendered Chromium and input/file workflows |
| Docker | P/N | Stock service, container lifecycle, published TCP/UDP, named peers, outbound traffic and cleanup |
| Podman | P/N | Stock runc/netavark/aardvark, published TCP/UDP, named peers, repeated DNS/HTTP, port conflicts and cleanup |
| Rootless Podman and Docker | P/N | Guest UID 1001, stock pasta/RootlessKit, container lifecycle, TCP/UDP publication, conflicts, outbound DNS/HTTP and cleanup |
| Libvirt/QEMU | P/N | Managed TCG domain, paused start/resume, DHCP/DNS, IPv4/IPv6 packet IO, NAT, reverse traffic, poweroff and TAP cleanup |
| BuildKit | P/N | OCI sandbox, multistage build, cache, secrets, export/run, cancellation and cleanup |
| LXC and nspawn/machinectl | P/N | Three nested Debian boot cycles, service/network IO and graceful/forced teardown |
| Selected LTP | P/N | Shared sample as ordinary guest user |
| Flatpak Chromium | P/N | Input, pointer, download/chooser round trip, Turnip WebGL and normal close |
| Flatpak Firefox | P/N | Input, pointer, download/chooser round trip, WebGL and normal close |
| Flatpak VS Code | P/N | Save/watch, window reload, terminal, GDB breakpoint/step/locals, hardware composition, renderer sandbox and normal close |
| Document portal | P/N | Ordinary-user sandbox IO/revocation, graceful exit, forced-exit auto-unmount and user-service restart/reactivation |
| Single-node k3s | P/N | Node/CoreDNS, services, network policy, deployment/PVC, Traefik/ServiceLB, restart and deletion |
| Persistent init-managed k3s | P/N | Stock systemd service, orderly reboot and supervisor SIGKILL/recovery; retained identities/PVC, replacement containers, DNS/Service IO and cleanup |

Full boot receipts under `build/matrix-arch-user/` are
`boot-tree-attr/oci-services-95d6088dc6b5470e8a002cf718c8418b.json` and
`boot-base-tree-attr/oci-services-00b264d8d4ff4f1dbff1ad9823ba2221.json`, APK
`efb887301943b915641c8789b2d48a13cceb81e01e83d5af218926ae75fce47c`.
The shared mount API fixture passes lazy/Base under both executors on that APK,
including atomic tree attributes, namespace authority and failed-clone cleanup.
It does not substitute for application acceptance. The retained session in
`gui/session.json` uses APK
`115edfd82327b4017959ddaf463cfbb12dcbacb159801c505b2ae0ef83aa500f`.
That APK passes `optional-native-calls` and `unavailable-calls` in lazy/Base
under both executors (`build/matrix-quota-policy-{app,shell}/`).

The application checks above use `gui-access/session.json`, APK
`6e8bce8b7c200e16324bb282b265855be285f6e2778361058e55dc6316290c10`.
Receipts are `gui-access/oci-services-46767c529ed842e786e2b72f70066b78.json`
(Docker), `gui-access/chromium-flatpak/gui-report.json` and
`gui-access/portal-lifecycle.json`. The libvirt run is retained in
`build/matrix-arch-user-libvirt-basic.log`. Additional reports in `gui-access/`:
`oci-services-2bdc21ce6e5141f283015d6eadd00e9a.json` (BuildKit),
`oci-services-8776f24c566b4e29b9de6fdc62b5f2b6.json` (LXC),
`oci-services-82cb27168663408abea18e90a8680a4f.json` (nspawn),
`oci-services-c1864cf7398b4c29a931624440750f85.json` (LTP),
`firefox-flatpak/gui-report.json` and `vscode-flatpak/gui-report.json`.
Common host-access and supervisor-state tests pass under both executors in
`build/matrix-host-access-{app,shell}-current.log`; shared unavailable-call
admission also passes both entry modes under both executors on this APK.

Single-node k3s v1.37.1+k3s1 passes on APK
`957fe1309449fe3c7e77b96c5e94a5b8f7f24e8f899851953c65335aa9bd3a70`:
`k3s/oci-services-ec04eb153b4840dc9ea1ae55a0a2824f.json`.

Rootless Podman and Docker pass on APK
`8e5b30e58c1269dabf62054f325336421b18ea32692ca9bbe875584dec394d5a`:
`gui-noarp/oci-services-04f56467978b4fbda9125bc5778f6f06.json` and
`gui-noarp/oci-services-af77d05c5a0b44568c78524193f82a45.json`.
The common link/TAP contract also passes under both executors in
`build/matrix-noarp-resources-{app,shell}.log`.

Rootful Podman passes its complete uninstrumented workflow, and managed
Libvirt networking passes with the official Alpine 3.23.6 kernel and initramfs,
on APK
`eb6674f012a483473cf67626c5dd09e8c07a8f82e671e8277cccda2774073718`:
`routed-signals/oci-services-a391e321b2854583ae6b28f1679726e8.json` and
`headless-signals/oci-services-1e8b6a341c964497b0441c385358c8ce.json`.
The shared signal suite passes 134 Base/lazy cases under app UID in
`build/matrix-signal-query-all-app/oci-services-04d57c105a604db6acdc7299b1f054c8.json`;
focused mask-query, atomic-wait and longjmp checks also pass both modes under
UID 2000 in `build/matrix-signal-query-policy-shell/`.

Persistent k3s recovery passes on the same APK with the current installer-generated
init helper:
`k3s-boot-uplink/oci-services-401a152afe9443058152981dfa645021.json`.
The boot helper preserves launch-provided `eth0` configuration through networkd.
All three phases and service/data cleanup pass. Shutdown still waits for the
HTTP workload's container scope until systemd's stop timeout; its signal handling
needs separate qualification before claiming a prompt graceful shutdown.
Default-target boot, binfmt and networkd IPv6 configuration also pass with that
helper in `boot-uplink/oci-services-69bd4385ec884e18a5e8f7d579d94463.json`.

### Fedora User

The borrowed recipe-installed Fedora 44 runs under app UID 10647. Common
fixtures use stock systemd, offscreen Weston and the installed Turnip profile;
Base results are independent, not inferred from lazy-entry passes.

| Workflow | Result | Checked scope |
| --- | --- | --- |
| Default-target boot | P/P | Stock services, binfmt and IPv6 networkd in a private bridge |
| Podman and Docker | P/N | Overlay storage, TCP/UDP publication, peers, external IO and cleanup |
| Rootless Docker | P/N | Guest UID 1000, stock rootless launcher, cgroup v2 and network IO |
| Rootless Podman | P/N | Guest UID 1000, stock network policy, local TCP/UDP, publication, repeated DNS/HTTP and cleanup; routed host ports remain shared with Android |
| Libvirt/QEMU | P/N | Managed TCG boot, guest contract, IPv4/IPv6 DHCP/DNS, NAT return traffic, poweroff and TAP cleanup |
| BuildKit | P/N | Build, cache, secret, export, execution and cancellation |
| LXC | P/N | Repeated container boot/network/stop and forced cleanup |
| nspawn and machinectl | P/N | Three boot/service/network/restart cycles and cleanup |
| Selected LTP | P/N | Seven shared tests as guest UID 1000 |
| Flatpak Chromium and Firefox | P/N | Input, download, chooser, WebGL, sandbox and normal exit |
| VS Code | P/N | Editor/save/watch, terminal, reload, GDB breakpoint/step and hardware composition |
| Document portal | P/N | Export/read/write/revoke, graceful stop, crash unmount and reactivation |
| Single-node k3s | P/N | CoreDNS, services, NetworkPolicy, PVC, rollout, Traefik/ServiceLB, restart and deletion |
| Persistent init-managed k3s | P/N | Enabled systemd service, orderly reboot, supervisor SIGKILL/recovery, retained identities/PVC, replacement containers and cleanup |

Receipts are under `build/matrix-fedora-user/`. GUI reports are
`gui/{chromium-gui,firefox-gui,vscode-gui}/gui-report.json` and `gui/portal.json`,
on APK build `eb6674f012a483473cf67626c5dd09e8c07a8f82e671e8277cccda2774073718`.
The container and k3s receipts below use diagnostic APK
`7a7719bd56aebd453e62f520c0fdb39dd1e4396c05e2fc5f8ccd65044d819673`;
its receipts include Docker `gui/oci-services-b75e63356ef040d094ac86a830fc22fc.json`,
Libvirt `headless/oci-services-43c7e388c3d344a8853f2369b5594c94.json` and
k3s `k3s/oci-services-029fe9aa7853491a851ae8b640350332.json`.
Persistent recovery is
`k3s-boot/oci-services-d3ef112092cd4ee9921f92d266bda3cc.json` on the same APK.
As in Arch, systemd's HTTP container scope reaches its stop timeout during
shutdown; successful state recovery does not certify prompt graceful teardown.

Rootless Podman passes two complete runs on build
`9f7d9a47eebfe513026e7e376393f141760d85b9a5f379594c92579dbe2b7ecc`,
with receipts under `build/matrix-followup-20261110/fedora-rootless/`:
`oci-services-982d105622ee45c5beee618cf68da332.json` and
`oci-services-0ce115ac38104831ab910cd57e7e9821.json`.
The fixture retains the package's default rootless network and the selected
routed parent, including external DNS/HTTP and daemon cleanup. Private guest
DNS on port 53 also passes IPv4/IPv6 TCP/UDP while the corresponding host port
is occupied: `build/matrix-followup-20261110/fedora-network3/oci-services-cb65fd7ae7d14cc5a044ec02c3cd5a51.json`.
Routed root-namespace explicit binds share Android ports; these results do not
claim collision-free host port isolation. Private bridges and explicit
publication have a different contract.

On APK `fe8e201c71c1d18b8f6a9c1153da71b13ff5679ed935d610fddb79099156851f`,
Docker and rootless Podman pass their complete external-IO workflows again:
`bridge-fixed/oci-services-9ca76ee98ec84112ba64407e046c8fa6.json` and
`bridge-fixed/oci-services-0435e646c3944212ac4c7c8c01b246ed.json`.
Shared bridge topology/forwarding components pass under both executors;
sysfs passes both Base and lazy entry under User and Shell in
`build/matrix-bridge-{resources-app,final-shell,sysfs-app,sysfs-base-app,sysfs-shell,sysfs-base-shell}.log`.

### Ubuntu User

The borrowed recipe-installed Ubuntu 24.04.5 uses app UID 10647, systemd 255.4,
offscreen Weston and Turnip. Pairs are lazy/Base; receipts below do not certify
unrun Base workflows.

| Workflow | Result | Checked scope |
| --- | --- | --- |
| Default-target boot | U/U | Healthy startup, binfmt and IPv6 networkd; confirmed upstream empty-container shutdown failures |
| Podman and Docker | P/N | Overlay storage, TCP/UDP publication, peers, external IO and cleanup |
| Rootless Podman and Docker | P/N | Guest UID 1000, stock launchers, container IO and cleanup |
| Libvirt/QEMU | P/N | Managed TCG boot, pause/resume, private NAT bridge, IPv4/IPv6 DHCP/DNS and workload IO, poweroff and TAP cleanup |
| BuildKit | P/N | Build, cache, secret, export, execution and cancellation |
| LXC | P/N | Repeated container boot/network/stop and forced cleanup |
| nspawn and machinectl | P/N | Three boot/service/network cycles, orderly and forced stop, restart and cleanup |
| Selected LTP | P/N | Seven shared tests as guest UID 1000 |
| Snap Chromium | P/N | Input, download, chooser, WebGL, sandbox and normal exit |
| Snap Firefox | I/N | Complete GUI repeat passes; cold namespace preparation under load can exceed Snap's unchanged timeout |
| VS Code | P/N | Editor/save/watch, terminal, reload, GDB breakpoint/step and hardware composition |
| Document portal | P/N | Export/read/write/revoke, graceful stop, crash unmount and reactivation |
| Single-node k3s | P/N | CoreDNS, services, NetworkPolicy, PVC, rollout, Traefik/ServiceLB, restart and deletion |
| Persistent init-managed k3s | P/N | Enabled systemd service, orderly reboot, supervisor SIGKILL/recovery, retained identities/PVC and cleanup |

Receipts are under `build/matrix-ubuntu-user/`. Boot reports are
`boot-fixed-lazy/oci-services-54138f6f02f047028c10494a26cac040.json` and
`boot-fixed-base/oci-services-ea50c2b701c2489580b1dd414aae33ac.json`.
The shutdown qualification is the same stock-service behavior described in
[Ubuntu boot evidence](distribution-evidence.md#ubuntu-default-boot).

Container, LTP and GUI receipts under `gui/` use APK build
`978f4b2775078762bb588897063c8340c516e29eb7a6de71578db7461f20aa2e`.
GUI reports are `{chromium-gui,firefox-gui-retry,vscode-gui}/gui-report.json`
and `portal.json`. Six namespace-only cold Firefox checks pass on APK
`e28f3820fcbba7d1540db8f6ac2c7c1b9c220b0e52323da7e5c7adcd54a20300`.
The three baseline attempts take 17.8-19.9 seconds to prepare the namespace:
`build/matrix-followup-20261110/snap-flow8-baseline/oci-services-416f8ab3be104aa1a31972a6ecccba10.json`.
Three attempts with two native `sha256sum /dev/zero` workers take 16.4-17.6 seconds:
`build/matrix-followup-20261110/snap-flow8-loaded/oci-services-2b3e607ce3b04e84b9a1c828f2e97553.json`.
Both series enable diagnostics and operation counters, retain CPU frequency
residency and preserve Snap's 30-second limit. CPU limits rose during the loaded
series, so these are not equal-frequency speed comparisons. The workloads are
terminated after testing. This qualifies the stated load, not a twofold timeout
margin under arbitrary load or full browser acceptance on this APK.

Libvirt and nspawn pass on APK
`dbaa14b88e8c89ddd7ba728ec058477a7cb745a477240c0bcfb3952f38547d67`:
`network/oci-services-92d58433570e428bb37f252120cab880.json` and
`network/oci-services-3d194dc5b3c44a3992ca8f6f0dd555b4.json`.
Libvirt 10.0.0 and QEMU 8.2.2 use the distribution's `ipxe-qemu` ROM package.
The single-node k3s receipt is
`k3s/oci-services-c388fb244a3043c89c87eb6081cc771d.json` on APK
`97b31ec823c6114019fd7a89f0745e46916232134f8aee75f0710d869c25a6fe`.
Persistent recovery is
`k3s-boot/oci-services-f3ed3bfbd7a64bb6aa033dd02990c780.json` on APK
`a895a24353a4c0ad55c4a01a4cde45af3b74bf44dba631f43b6751a74fd8df30`.
Its BusyBox HTTP workload runs as container PID 1 without a SIGTERM handler;
systemd reaches the scope stop timeout before SIGKILL. Recovery passes, but this
workload does not qualify prompt graceful container shutdown.

### OpenSUSE User

The borrowed recipe-installed Tumbleweed 20261020 runs under app UID 10647,
with systemd 261.4 and offscreen Weston 16 using the installed Turnip profile.
Pairs are lazy/Base; unrun workflows remain N. These checks use APK
`a895a24353a4c0ad55c4a01a4cde45af3b74bf44dba631f43b6751a74fd8df30`.

| Workflow | Result | Checked scope |
| --- | --- | --- |
| Default-target boot | P/P | Stock services, journal/system-bus IO, logind restart, binfmt and networkd IPv6 |
| Podman and Docker | P/N | Overlay storage, TCP/UDP publication, peers, external IO and cleanup |
| Rootless Podman and Docker | P/N | Guest UID 1000, stock launchers, cgroup v2, container IO and cleanup |
| Libvirt/QEMU | P/N | Managed TCG boot, pause/resume, NAT bridge, IPv4/IPv6 DHCP/DNS and bidirectional IO, poweroff and TAP cleanup |
| BuildKit | P/N | Build, cache, secrets, export/run and cancellation |
| LXC and nspawn/machinectl | P/N | Three nested Debian boot/network cycles, orderly and forced stop, restart and cleanup |
| Selected LTP | P/N | Seven shared tests as guest UID 1000 |
| Flatpak Chromium and Firefox | P/N | Input, download, chooser, WebGL, sandbox and normal exit |
| Native VS Code | P/N | Editor/save/watch, terminal, reload, GDB breakpoint/step, renderer sandbox and hardware composition |
| Document portal | P/N | Export/read/write/revoke, graceful stop, crash unmount and service reactivation |
| Single-node k3s | P/N | CoreDNS, pod/Service IO, NetworkPolicy, Deployment/PVC, Traefik ingress, server restart and cleanup |
| Persistent init-managed k3s | P/N | Enabled systemd service, orderly reboot, supervisor SIGKILL/recovery, retained identities/PVC, replacement containers and cleanup |

Receipts are under `build/matrix-opensuse-user/`: `boot-{lazy,base}/` for full
boot, `gui/` for containers and GUI, and `network/` for Libvirt. GUI reports
are `{chromium-gui,firefox-gui,vscode-gui}/gui-report.json` and `portal.json`.
The checked applications are Flatpak Chromium 156.0.8078.47, Firefox 159,
native VS Code 1.143.1, Libvirt 12.8.0 and QEMU 11.1.2.
Rootless Docker uses the distribution's `docker-rootless-extras` and
`rootlesskit` packages; its receipt is
`gui/oci-services-827c147bd6df467a8b699d268591616d.json`.
The managed VM network receipt is
`network/oci-services-87c3fcb30bb74856b42ae0048fa99bbe.json`.
Single-node k3s v1.37.1+k3s1 uses
`k3s/oci-services-7777dcd38cdb45918e3a4296b5ee0797.json`.
Persistent recovery uses
`k3s-boot/oci-services-2212a743105c43f3996de59eb718f39c.json`.
This receipt's BusyBox HTTP process has no PID-1 SIGTERM handler; systemd reaches
the scope stop timeout. It verifies recovery, not prompt graceful workload exit.

### Deepin User

The borrowed recipe-installed Deepin 25 (crimson) runs under app UID 10647,
with systemd and offscreen Weston using its installed Turnip profile. Pairs
are lazy/Base; unrun workflows remain N. These checks use APK
`a895a24353a4c0ad55c4a01a4cde45af3b74bf44dba631f43b6751a74fd8df30`.

| Workflow | Result | Checked scope |
| --- | --- | --- |
| Default-target boot | U/U | Readiness, journal/system-bus IO, logind restart, binfmt and networkd IPv6 pass; packaged Podman shutdown command fails with no containers |
| Podman and Docker | P/N | Overlay storage, TCP/UDP publication, peers, external IO and cleanup |
| Rootless Podman and Docker | P/N | Guest UID 1000, stock launchers, cgroup v2, container IO and cleanup |
| BuildKit | P/N | Build, cache, secrets, export/run and cancellation |
| LXC and nspawn/machinectl | P/N | Nested Debian boot/network, orderly and forced stop, restart and cleanup |
| Selected LTP | P/N | Seven shared tests as guest UID 1000 |
| Flatpak Chromium and Firefox | P/N | Input, download, chooser, WebGL, sandbox and normal exit |
| Native VS Code | P/N | Editor/save/watch, terminal, reload, GDB breakpoint/step, renderer sandbox and hardware composition |
| Document portal IO/graceful exit | P/N | Export/read/write/revoke and two SIGTERM/reactivation cycles |
| Document portal crash cleanup | P/N | Five daemon-death cycles, exact helper exit, mount removal and document access after reactivation |
| Document portal whole-service restart | U/N | Packaged libfuse discards the unmount helper's SIGTERM exit and portal waits for blocked FUSE workers |
| FUSE close lifecycle | P/N | Ordinary and seccomp-filtered close, aliases, FD reuse, signals, replacement, exit and exec |
| Libvirt/QEMU | P/N | Managed TCG boot, pause/resume, NAT bridge, IPv4/IPv6 DHCP/DNS and bidirectional IO, poweroff and TAP cleanup |
| Single-node k3s | P/N | CoreDNS, pod/Service IO, NetworkPolicy, Deployment/PVC, Traefik ingress, server restart and cleanup |
| Persistent init-managed k3s | P/N | Enabled systemd service, orderly reboot, supervisor SIGKILL/recovery, retained identities/PVC, replacement containers and cleanup |

Receipts are under `build/matrix-deepin-user/`: `boot-{lazy,base}/` for full
boot and `gui/` for containers and GUI. Applications are Flatpak Chromium
156.0.8078.47, Firefox 159 and native VS Code 1.143.1; GUI reports are
`{chromium-gui,firefox-gui,vscode-gui}/gui-report.json`. Rootless Podman uses
the packaged pasta backend; Docker uses its packaged rootless launcher.
Podman's aardvark-dns 1.12.2 remains a lab-only build because the distribution
omits that package; this is not an installer source-build fallback.

The stock `podman` 5.2.5.2-1deepin3 unit invokes `podman stop` with an empty
argument list when no restart-policy containers exist, returning 125.
`build/matrix-deepin-user-podman-empty-stop.log` records the unchanged package
verification and command. Boot receipts end in `de3d47b6a0114b0b9f786a74e78b5d04`
(lazy) and `80d9b1d42d674bc485e33ec01a08dfbe` (Base).

Libvirt 9.10.0 and QEMU 8.2.0 use the distribution's `ipxe-qemu` ROM package.
Managed VM network evidence is
`network-ready/oci-services-6655f28c42104d9ab2d4df2da6972bd5.json`.
Single-node k3s v1.37.1+k3s1 uses
`k3s/oci-services-850e2e392c334b54aad6817fb4bfcd03.json`.
Persistent recovery uses
`k3s-boot/oci-services-a639135eceb3427a925f41932e39c269.json`.
This qualifies the k3s lifecycle, not every packaged shutdown service.

Portal checks use xdg-desktop-portal 1.22.1+ds-1deepin1 and libfuse
3.14.0-5deepin1. Five daemon-death cycles and two direct SIGTERM/reactivation
cycles pass on APK
`5b612340b543237c051e8c9c91f11e5872b1fb7406a455a696c6368731a3399d`:
`build/matrix-close-deepin/gui/493d7d78b3104cb1ab0fe7f11ecd1220.log` and
`build/matrix-close-deepin/gui/08a243bda94547cd9a0d0f7ce634054c.log`.
Each cycle checks real sandbox document read/write/revocation, exact daemon
and helper exit, mount removal and reactivation without instrumentation.
Whole-service restart retains the separate upstream failure below.

`portal-observer-driver.log` observes the untraced daemon's actual wait result:
its unmount helper dies from SIGTERM, and libfuse calls waitpid with a null
status pointer. The attribution follows the packaged
[unmount implementation](https://github.com/libfuse/libfuse/blob/fuse-3.14.0/lib/mount.c)
and portal's
[FUSE teardown](https://github.com/flatpak/xdg-desktop-portal/blob/1.22.1/document-portal/document-portal-fuse.c).
`portal-recheck2-driver.log` locates the resulting join and blocked splice
workers. This is a package-level shutdown race, not permission to suppress
systemd's signals or change FUSE semantics. The observation preload and runtime
unit override are test-only and removed afterward. Guest ptrace is unsuitable
for this attribution because it suppresses the helper's set-ID transition.

`fuse-close-driver.log` qualifies the common FUSE lifecycle in both filter
modes. Child-notification identity is covered by the shared PID-namespace
regression suite. The booted Deepin glibc check also passes 20 repetitions as
guest UID 1000 (`build/signal-reaped-deepin-repeat.log`). This does not qualify
the separate whole-service shutdown failure above.

### Manjaro User

The borrowed recipe installation uses app UID 10647, systemd and offscreen
Weston with Turnip. Receipts use APK
`0e7bc6b49afc0b731bbaa6a9c2b377e6f592a0939a42339472fdf66b7b756f5e`.
Pairs are lazy/Base; unrun Base workflows remain N.

| Workflow | Result | Checked scope |
| --- | --- | --- |
| Full systemd boot | P/P | Default target, journal/D-Bus/logind IO, restart, binfmt, networkd IPv6 and shutdown |
| Podman and Docker | P/N | Overlay storage, TCP/UDP publication, named peers, local outbound IO and cleanup |
| Rootless Podman | P/N | Guest UID 1000, stock sandbox/cgroups, TCP/UDP, named peers and cleanup |
| Rootless Docker | P/N | Local TCP/UDP publication, literal/named peers, outbound HTTP stress and clean daemon/session teardown |
| containerd cleanup reporting | U/N | Packaged 1.7.13 shim has competing child reapers and can report status 255 after a successful child exit |
| Libvirt/QEMU | P/N | Managed TCG boot, pause/resume, NAT bridge, IPv4/IPv6 DHCP/DNS, bidirectional IO, poweroff and TAP cleanup |
| BuildKit | P/N | Build, cache, secrets, export/run and cancellation |
| LXC and nspawn/machinectl | P/N | Nested Debian boot/network, orderly and forced stop, restart and cleanup |
| Selected LTP | P/N | Seven shared tests as guest UID 1000 |
| Flatpak Chromium and Firefox | P/N | Input, download, chooser, WebGL, context retention and normal exit |
| Native VS Code | P/N | Editor/save/watch, terminal, reload, GDB breakpoint/step, renderer sandbox and hardware composition |
| Document portal | P/N | Export/read/write/revoke, graceful stop, crash unmount, whole-service restart and reactivation |
| Debugger lifecycle | P/N | Stopped-child termination, thread exec, syscall tracing, SysV SHM and JIT tracing |
| Single-node k3s | P/N | CoreDNS, pod/Service IO, NetworkPolicy, Deployment/PVC, ingress, server restart and cleanup |
| Persistent init-managed k3s | P/N | Stock service, clean reboot and supervisor SIGKILL recovery retain cluster identity/PVC data; service DNS/IO and cleanup pass |

Receipts are under `build/matrix-manjaro-user/`: `boot-{lazy,base}/`, `gui/`,
`network-ready/`, `k3s/` and `k3s-boot/`. Persistent recovery receipt:
`k3s-boot/oci-services-01546dbc4c524f05ac2183b09c9b827b.json`. GUI reports are
`{chromium-gui,firefox-gui,vscode-gui}/gui-report.json` and
`portal-systemd.json`. Applications are Chromium 156.0.8078.47, Firefox 159 and
official Microsoft VS Code 1.143.1 with cpptools and packaged GDB 14.1.
The portal run uses stock xdg-desktop-portal 1.18.2-1; its passing receipt does
not remove the separately established intermittent upstream shutdown defect
in the [Shell evidence](distribution-evidence.md#manjaro-document-portal).

Rootless Docker uses packaged Docker 25.0.2, RootlessKit 1.1.1 and slirp4netns
1.2.3. The distribution omits `dockerd-rootless.sh`; the matching official
Moby v25.0.2 script is staged under `/opt/md-rootless-extras` for testing only
(SHA-256 `935d017538ed780371f547f836abb57d8cd811225e40c3002326aa35b6b8f697`).
The local-only stress workflow passes on APK
`e207c197f4f147a71dae756b32aef8f248fbcf35295c99705825c489a7bd72cd`:
`build/matrix-followup-20261110/manjaro-network21/oci-services-fd256228fd2047b5bae20ffaf01c8e15.json`.
It loads the cached Python image and performs 1000 TCP/UDP exchanges through
each of two published ports and in each direction between containers, using
both addresses and names: 6000 exchanges per protocol. Two containers also make
1000 new HTTP connections each to the native phone observer. Daemon exit 0,
port release, unchanged system mounts and session cleanup pass. No failed
connection is retried, and no syscall tracing is enabled.

Cleanup warnings have separate U evidence. The unmodified containerd 1.7.13
shim reports status 255 for `/bin/true` in 7/32 guest runs. The same shim outside
Shroot reproduces it when strace delays the return from `waitid` by 10 ms:
`wait4(-1)` consumes exit 0, then Go's per-command wait receives `ECHILD`.
No syscall result is replaced. The [delete path](https://github.com/containerd/containerd/blob/v1.7.13/runtime/v2/shim/shim.go)
starts the global reaper without the `runc.Monitor` assignment performed by
[task-service initialization](https://github.com/containerd/containerd/blob/v1.7.13/runtime/v2/runc/task/service.go).
Evidence: `build/matrix-manjaro-user-recheck/shim-native-delayed-probe.log` and
`gui/07d7c6b79fbf4139884817ec4406b0e0.log` in that directory. This attribution
does not classify unrelated Docker networking failures as upstream.

Debugger checks use APK
`aead0513065d121f875373f94c13078a2400f1424e9a852dfdeb179f384460db`:
`build/matrix-manjaro-user-recheck/debugger-app-c/oci-services-d4c28ad5afff4ccdac0788bee201ba20.json`.
The same SVC retirement/coordinator component checks pass under App and Shell
UIDs in `revoke-{app,shell}/`, with filter-race, waiting-peer, fork and exit
coverage in `build/svc-patch-results/oci-services-{17d060b0443b4ba18edfce6bd732f53a,c2e9f45f4bb74c3db6385d3bf0abba81}.json`.
Public DNS/HTTP acceptance remains separate: Podman's external resolver
series failed while its local-only rerun passed.

Libvirt 10.0.0, QEMU 8.2.1 and packaged dnsmasq 2.90 use
`network-ready/oci-services-790ada3361b0406cabc3b5607460db00.json`.
Single-node k3s v1.37.1+k3s1 uses
`k3s/oci-services-a94db0ce0587456c8a70915f0255dd6e.json`.

### OpenKylin User

The borrowed recipe installation uses app UID 10647, systemd 259.5 and
offscreen Weston with its installed Mesa 26.2.3/Turnip profile. Unless specified
below, receipts under `build/matrix-openkylin-user/` use APK
`a0c7de42e7f4efc092b1ebf6c5f71e575023ccd9b7eadffc92bba8ec964ec20c`.
Pairs are lazy/Base; unrun Base workflows remain N.

| Workflow | Result | Checked scope |
| --- | --- | --- |
| Full systemd boot | P/P | Default services, journal/D-Bus/logind, restart, binfmt and IPv6 networkd RA/SLAAC lifecycle |
| Podman and Docker | P/N | Stock sandboxes, TCP/UDP publication, named peers, local outbound IO and cleanup |
| Rootless Podman and Docker | P/N | Guest UID 1000, stock set-ID helpers, subordinate IDs and network checks |
| Libvirt/QEMU | P/N | Managed TCG boot, pause/resume, IPv4/IPv6 DHCP/DNS, NAT, packet IO, poweroff and TAP cleanup |
| BuildKit | P/N | Build, cache, secrets, export/run and cancellation |
| LXC and nspawn/machinectl | P/N | Nested Debian boot/network, orderly and forced stop, restart and cleanup |
| Selected LTP | P/N | Seven shared tests as guest UID 1000 |
| Flatpak Chromium and Firefox | P/N | Input, download, chooser, WebGL, context retention and normal exit |
| Native VS Code | P/N | Editor/save/watch, terminal, reload, GDB breakpoint/step, renderer sandbox and hardware composition |
| Document portal | P/N | Export/read/write/revoke, graceful stop and daemon-death auto-unmount |
| Single-node k3s | P/N | CoreDNS, pod/Service IO, NetworkPolicy, Deployment/PVC, ingress, server restart and cleanup |
| Persistent systemd-managed k3s | P/N | Stock enabled service, clean restart, supervisor SIGKILL/recovery, cluster identity/PVC retention and cleanup |

Boot receipts are in `boot-{lazy,base}/`; application receipts are
`gui/{chromium-gui,firefox-gui,vscode-gui}/gui-report.json` and `gui/portal.json`.
Applications are Flatpak Chromium 156.0.8078.47, Firefox 159, native VS Code
1.143.1 and document portal 1.21.1. Public Internet is outside these local checks.

Podman 5.7.0 uses the distribution's uidmap, passt and slirp4netns packages.
Docker 24.0.7 is installed under `/opt/system/bin`; rootless coverage uses its
official matching Moby launcher staged only for testing and packaged RootlessKit
2.0.2. No installer source-build fallback is added. Rootless Docker receipt:
`gui/oci-services-fa4b952bc0074833a5311f47183fc46a.json`.

Libvirt/QEMU uses packaged QEMU 10.2.1 and `ipxe-qemu`, with APK
`7e4e3ab7f50775c61f7890ee88bef8732fb003eed1d3a75791d4c8f9a72abba0`:
`network/oci-services-f3ca04a6a087422590e2dd58a999e1a1.json`.
Its private test bridge has STP disabled. Stock Libvirt's default bridge requests
STP, which Shroot explicitly rejects; this run does not qualify that topology.

Single-node k3s v1.37.1+k3s1 uses the same APK:
`k3s/oci-services-7c6ae6b1199c4e29854bc6f220df2bc1.json`.
Persistent service and recovery receipt:
`k3s-boot/oci-services-5c8d21d8927647179ea90d78ac3adeb8.json`.

### NixOS User

The borrowed recipe installation uses app UID 10647 and stock activation of
generation `/nix/store/ppas5d9hgnzmr7v36363a6128p7k8m28-nixos-system-nixos-lxc-26.05.12530.c1ff116587e6`.
Unless specified below, receipts under `build/matrix-nixos-user/` use APK
`0e7bc6b49afc0b731bbaa6a9c2b377e6f592a0939a42339472fdf66b7b756f5e`.
Pairs are lazy/Base; unrun Base workflows remain N.

| Workflow | Result | Checked scope |
| --- | --- | --- |
| Activated systemd boot | P/P | Default target, journal/D-Bus/logind, service restart and shutdown with the recipe's preconfigured network |
| Libvirt and direct QEMU | P/N | TCG boot, serial IO, Landlock kernel contract, pause/resume, IPv4/IPv6 DHCP/DNS, NAT, poweroff and TAP cleanup |
| Podman and Docker | P/N | Stock sandboxes, TCP/UDP publication, named peers, local outbound IO and cleanup |
| Rootless Podman | P/N | Guest UID 1000, stock set-ID helpers and sandbox/cgroup policy, same network checks |
| Rootless Docker | P/N | Cold archive import, local TCP/UDP publication, literal/named peers, outbound HTTP stress and daemon cleanup |
| Single-node k3s | P/N | CoreDNS, pod/Service IO, NetworkPolicy, Deployment/PVC, ingress, server restart and cleanup |
| Declarative init-managed k3s | P/N | Nix-packaged enabled service, initial activation, orderly reboot and supervisor SIGKILL/recovery; retained identities/PVC, DNS/Service IO and cleanup |
| BuildKit | P/N | Build, cache, secrets, export/run and cancellation |
| LXC and nspawn/machinectl | P/N | Nested Debian boot/network, orderly and forced stop, restart and cleanup |
| Selected LTP | P/N | Seven shared tests as guest UID 1000 |
| Flatpak Chromium and Firefox | P/N | Input, download, chooser, WebGL, context retention and normal exit |
| Native VS Code | P/N | Editor/save/watch, terminal, reload, GDB breakpoint/step, renderer sandbox and hardware composition |
| Document portal | P/N | Export/read/write/revoke, graceful stop, crash unmount, whole-service restart and reactivation |

Boot receipts are in `boot-{lazy,base}/`; application receipts are
`gui/{chromium-gui,firefox-gui,vscode-gui}/gui-report.json` and
`gui/portal-systemd.json`. Applications are Flatpak Chromium 156.0.8078.47,
Firefox 159 and Nix-packaged VS Code 1.119.0 with cpptools 1.31.5/GDB 17.2.
The debugger requires the Nix extension package (or a separately prepared
foreign-binary environment); the generic Marketplace binary requests an ELF
interpreter absent from this installation. The stock portal 1.20.4 passing run
does not remove its separately established intermittent upstream shutdown
defect in the [Shell evidence](distribution-evidence.md#nixos-document-portal).

Rootless Docker 29.9.1 uses the packaged `dockerd-rootless` launcher, containerd
2.3.3 and runc 1.5.2. Network assertions and daemon teardown pass. Its detached
network-namespace launcher retains an initial-user-namespace-owned host network:
after entering its child-owned network, a daemon thread cannot restore that
original namespace. Moby reports this and retires the locked OS thread; Shroot
does not grant it authority over the parent namespace. The user service delegates
cpu/memory/pids but not cpuset, so enabling the combined controller set fails
with ENOENT rather than fabricating cpuset availability. Evidence:
`build/matrix-close-nixos/{namespace-owners,cgroup-live}.log` and
`gui/oci-services-24d035eff54644429e73ff20ddc92c66.json` in that directory.
Public internet is outside these local checks.

Cold import and the full rootless network workflow pass under APK
`e207c197f4f147a71dae756b32aef8f248fbcf35295c99705825c489a7bd72cd`:
`build/matrix-followup-20261110/nixos-network-flow7/oci-services-72922f21e6fe46e58b26ff89fb0b08bf.json`.
Each published-port and literal/named peer path completes 1000 TCP/UDP exchanges;
both containers also complete 1000 outbound HTTP requests each. The daemon
exits normally and system mounts remain unchanged. The archive comes from the
installed image cache, without registry access.

Single-node k3s v1.37.1+k3s1 passes CoreDNS, pod/Service IO, NetworkPolicy,
Deployment/PVC, Traefik/ServiceLB ingress, server restart and cleanup:
`k3s/oci-services-f1086ad0518a4d48ac09bf47daa34b8b.json`.
This process fixture does not qualify a persistent declarative NixOS service.
The separate declarative boot fixture passes initial activation, orderly reboot
and supervisor SIGKILL/recovery with Nix-packaged k3s 1.35.8+k3s1:
`build/matrix-followup-20261110/nixos-k3s-boot15/oci-services-6d504415e0274a4ca907153effe7c60e.json`,
APK `0bd7944570bafedad659919cc4ef6fb58f84277443967b98780f0b79f30fae2c`.
All phases check enabled service ownership, pod DNS/Service IO and persistent
identities/PVC data without syscall or signal tracing. The fixture restores the
previous system generation and installation data and closes its executor.
Managed VM boot is `gui/oci-services-8c9934b4a5504c60bd6135673e03034c.json`;
direct QEMU is `gui/oci-services-3dacbe22158a45f993275b6773bb991c.json`.
The full managed-network run is
`network-fixed/oci-services-5d7d94b05fd44bd98c72cc48e28e0367.json`, using APK
`a0c7de42e7f4efc092b1ebf6c5f71e575023ccd9b7eadffc92bba8ec964ec20c`.
NixOS retains its stock reverse-path filter. The private test namespace allows
only the fixture controller's TCP port through INPUT; no installed firewall
configuration is changed.

### Armtix User

The borrowed recipe installation uses app UID 10647, runit and offscreen
Weston with the installed Turnip profile. Pairs are lazy/Base; unrun Base
workflows remain N. Unless specified below, receipts use APK
`a895a24353a4c0ad55c4a01a4cde45af3b74bf44dba631f43b6751a74fd8df30`.

| Workflow | Result | Checked scope |
| --- | --- | --- |
| Full runit boot | P/N | Init, service lifecycle and shutdown; optional vendor host-global AIO tuning does not claim to change Android |
| Podman and Docker | P/N | Overlay storage, TCP/UDP publication, named peers, external IO and cleanup |
| Rootless Podman and Docker | P/N | Guest UID 1001, pasta, cgroup v2, TCP/UDP, conflicts, DNS/HTTP and clean shutdown |
| Libvirt/QEMU | P/N | Managed TCG boot, pause/resume, NAT bridge, IPv4/IPv6 DHCP/DNS, bidirectional IO, poweroff and TAP cleanup |
| BuildKit | P/N | Build, cache, secrets, export/run and cancellation |
| LXC | P/N | Three nested Debian boot/network cycles, orderly and forced stop, restart and cleanup |
| nspawn/machinectl | B/N | Distribution repositories have no systemd-container package |
| Selected LTP | P/N | Seven shared tests as guest UID 1001 |
| Flatpak Chromium and Firefox | P/N | Input, download, chooser, WebGL, sandbox and normal exit |
| Native VS Code | P/N | Editor/save/watch, terminal, reload, debugger, renderer sandbox and hardware composition |
| Document portal | P/N | Export/read/write/revoke, graceful stop, crash unmount and reactivation |
| Single-node k3s | P/N | CoreDNS, pod/Service IO, NetworkPolicy, Deployment/PVC, ingress, server restart and cleanup |
| Persistent init-managed k3s | B/N | The upstream service installer supports systemd/OpenRC, not runit; this does not limit direct k3s execution |

Receipts are under `build/matrix-armtix-user/`: `boot-lazy/`, `gui/`,
`network/` and `k3s/`. GUI reports are
`{chromium-gui,firefox-gui,vscode-gui}/gui-report.json` and
`portal-lifecycle.json`. Applications include Chromium 156.0.8078.47,
Firefox 159 and VS Code 1.140.0.

Rootless Docker, Libvirt/QEMU and k3s use APK
`0e7bc6b49afc0b731bbaa6a9c2b377e6f592a0939a42339472fdf66b7b756f5e`:
`gui/oci-services-941ce1a785eb4f909cae639c8a47cbf2.json`,
`network/oci-services-ea32f83865aa42f8967947472767ebf0.json` and
`k3s/oci-services-b094bd4d0beb4b399641f1842220f089.json`.
Libvirt is 12.8.0, QEMU 11.1.2 and k3s v1.37.1+k3s1.
Docker's rootless launcher and RootlessKit 3.3.0 come from the official Docker
29.9.1 ARM64 extras archive, staged only for testing because Armtix omits these
packages. No installer source-build fallback is added.
The common namespace suite passes under both executors, including mount-table
enumeration and retained descriptors after a proc target's process exits.

Full runit boot uses
`build/matrix-followup-20261110/armtix-boot/oci-services-ac7b0032a23749198ed8ae8ceb82ec18.json`,
build `0a58faea397c009f1afdaf2e9cf7cc2bb854f47b7022fbd37d6e9d1d3eaeb9f7`.
The recipe marks only packaged `/usr/lib/sysctl.d` or `/run/sysctl.d`
`fs.aio-max-nr` assignments optional. Administrator `/etc` policy and the
host-global native value remain unchanged.

### Void User

The borrowed recipe installation uses app UID 10647, stock runit and offscreen
Weston with the installed Turnip profile. Pairs are lazy/Base; unrun Base
workflows remain N. Receipts are under `build/matrix-void-user/`.

| Workflow | Result | Checked scope |
| --- | --- | --- |
| Runit boot and service lifecycle | P/P | Stock PID 1, service start/restart/stop and launch cleanup; global hardening sysctls remain unavailable |
| Podman and Docker | P/N | Stock container sandboxes, TCP/UDP publication, named peers, outbound IO and cleanup |
| Rootless Podman and Docker | P/N | Guest UID 1000, cgroup v2, TCP/UDP, DNS/HTTP, conflicts and daemon cleanup |
| Libvirt/QEMU | P/N | Managed TCG boot, pause/resume, NAT, IPv4/IPv6 DHCP/DNS, bidirectional IO, poweroff and TAP cleanup |
| BuildKit | P/N | Build, cache, secrets, export/run and cancellation |
| LXC | P/N | Three nested Debian boot/network cycles, orderly and forced stop, restart and cleanup |
| nspawn/machinectl | B/N | No installed tools or distribution package providing systemd-container |
| Selected LTP | P/N | Seven shared tests as guest UID 1000 |
| Flatpak Chromium and Firefox | P/N | Input, download, chooser, WebGL, context retention and normal exit |
| Native VS Code | P/N | Editor/save/watch, terminal, reload, GDB breakpoint/step, renderer sandbox and hardware composition |
| Document portal | P/N | Export/read/write/revoke, graceful stop, daemon-death unmount and reactivation |
| Single-node k3s | P/N | CoreDNS, pod/Service IO, NetworkPolicy, Deployment/PVC, ingress, server restart and cleanup |
| Persistent init-managed k3s | B/N | The upstream service installer supports systemd/OpenRC, not runit; direct k3s is a separate check |

Podman 6.1.4, Docker 29.8.0 and rootless Podman use APK
`7e4e3ab7f50775c61f7890ee88bef8732fb003eed1d3a75791d4c8f9a72abba0`.
Other completed rows except single-node k3s use APK
`ef0c94d59274d0358bbf34fd3015ee0955133606099cb0d70792a15f3bb6f54f`.
Boot receipts are `boot-lazy/oci-services-4ae1a9f65f8440aa973e1bbffadd02d7.json`
and `boot-base/oci-services-b60fc8bc4b214b3e878fb2a73f901903.json`.
The kernel-log syscall reports ENOSYS; guest `/dev/kmsg` remains available.
Denied global sysctl writes are not successful guest hardening or host changes.

Rootless Docker uses packaged RootlessKit 2.2.0 and slirp4netns 1.3.3 with the
matching official Moby launcher staged only for testing. The recipe does not
gain a source-build fallback. Its receipt is
`gui/oci-services-0dd6c985d8394ff7b5f42973d3fac989.json`.
Libvirt 12.8.0/QEMU 11.1.2 uses
`network/oci-services-ac8244a71a044f6bb6edc6dfcd5ebf1b.json`; the private bridge
disables unsupported STP, so this does not qualify the default STP topology.
GUI reports are `gui/{chromium-gui,firefox-gui,vscode-gui}/gui-report.json`
and `gui/portal.json`.

Single-node k3s v1.37.1+k3s1 uses APK
`773ab03b2e972f0cec835a81dd05449e0f5db6add90939ded7c1fe2d394c112c` and
`k3s-io/oci-services-2a561678c73e4dcfad276117f2a78c10.json`.
The same build passes native IO completion/cancellation and full ancillary
coverage in `ipc-{app,shell}-results.json`. `policy-io-{app,shell}/` qualifies
pending socket TSYNC, signal/restart behavior and unavailable calls in both
Base and lazy modes; the Shell policy run uses the restored Manjaro store.
`performance/oci-services-70df60c5f0904c6b9819ab683caf97e7.json` records three
interleaved Base/lazy rounds of stat, open/close, file-cycle and poll/epoll, with
CPU frequency evidence and no affinity constraint. This is a current-runtime
check, not a paired comparison against another build.

### Alpine User

The borrowed recipe-installed Alpine 3.23 uses app UID 10647, OpenRC and
offscreen Weston with the installed Turnip profile. These results use APK
`c1c280a6d56c0ba48bb524255767562170db46fd0106c756cfd3c1267c1dda0b`.
Pairs are lazy/Base; unrun Base workflows remain N.

| Workflow | Result | Checked scope |
| --- | --- | --- |
| Full OpenRC boot | P/P | Stock services, syslog/crond IO, restart and cleanup |
| Docker | P/N | Booted daemon, overlay storage, TCP/UDP publication, named peers and cleanup |
| Rootless Docker | P/N | Stock rootless launcher as guest UID 1000, cgroup v2, overlay2, TCP/UDP, named peers and clean daemon exit |
| Podman | P/N | Stock seccomp/cgroups, overlay, TCP/UDP publication, named peers, repeated exec, external DNS/HTTP and cleanup |
| Libvirt/QEMU | P/N | Managed TCG boot, pause/resume, poweroff and undefine; not VM networking |
| BuildKit | P/N | OCI/CNI build, cache, secrets, export/run through Docker, cancellation and fresh build |
| LXC | P/N | Three systemd container boot/network/stop cycles, including forced termination and cleanup |
| Selected LTP | P/N | Seven shared tests as guest UID 1000 |
| Native and Flatpak Chromium/Firefox | P/N | Input, download, file chooser, WebGL and normal application exit |
| Flatpak VS Code | P/N | Editor, watch, terminal, reload, GDB breakpoint/step, hardware composition, renderer sandbox and normal exit |
| Document portal | P/N | Export/read/write/revoke, two graceful stops, crash-triggered unmount and reactivation |
| Single-node k3s | P/N | Full workload, five successive server restarts, retained workload identity/IO and all six clean server stops |
| Persistent init-managed k3s | P/N | Stock OpenRC installation, orderly reboot and supervisor SIGKILL/recovery; retained identities and PVC, replacement containers, DNS/Service IO and cleanup |

Receipts are under `build/matrix-alpine-user/`: `boot-lazy-fixed` and
`boot-base` for boot, and `session` for retained-session workflows.
Container receipt IDs are Docker `5aa6a8a0674548cd8366cf9b246863f7`, Libvirt
`13a11ea30e83400993a62680dd35d91e`, BuildKit
`7828ff6b880c45599c44bc2c1a2d5284`, LXC
`70d613072a6443b890d0e21f41eea8f5` and LTP
`cf033f0eb886497198cc961767f4b146` (each `oci-services-ID.json`).
GUI reports are `{chromium,firefox}-{native,flatpak}-gui/gui-report.json`
and `vscode-gui/gui-report.json`; portal evidence is `portal.json`.
Packages include native Chromium 149.0.7827.53 and Firefox 151.0.3,
Flatpak Chromium 156.0.8078.47 and Firefox 159.0, VS Code 1.139.1,
and xdg-desktop-portal 1.20.3-r4. The portal result is specific to this
package and runtime; it does not transfer the Debian upstream failure.
Rootless Docker 29.5.2 with runc 1.4.0 uses
`app/build/shroot/oci-services-fa1141bd6c3d4c699d3ed217ed86ba28.json`.

Podman's full OpenRC-booted workflow uses APK
`2a97867ea16e958af6da5ee381094be1d6f818ce5150721f1a3f5fc435df81af`:
`build/matrix-close-alpine/podman-robust-30/oci-services-1e0b993a04b642de9167cc9f32293b14.json`.
Both containers pass twenty external DNS queries and HTTP, in addition to
local peer exchange, repeated exec, TCP/UDP publication, expected port conflicts
and preserved system mounts.

Single-node k3s v1.37.1+k3s1 passes the full workload, five successive server
restarts and all six server stops on APK
`e207c197f4f147a71dae756b32aef8f248fbcf35295c99705825c489a7bd72cd`:
`build/matrix-followup-20261110/alpine-k3s-flow7/oci-services-5d63823aa1d9450db5b4096e6a925be6.json`.
The run uses locally staged airgap images and fault-signal diagnostics, not
successful-call tracing. Its executor and private service state are released.
Each restart requires a fresh kubelet Ready heartbeat and retains workload
identity and service connectivity.
Stock runc passes split create/start with the containerd seccomp profile and
guest UID 65535. These tests retain guest seccomp and guest dumpability checks.

The persistent k3s boot fixture passes all three phases without diagnostic
instrumentation on APK build
`cb710e52d0208266166a0ae5c6c326de81d12a53ad9821d30f277e0867848825`:
`build/matrix-close-alpine/k3s-boot-fresh/oci-services-d5abd16a714d4096b95974ae4fddc566.json`.
Each phase requires a kubelet Ready heartbeat newer than that init launch;
persisted API state alone cannot satisfy startup readiness.
The recipe-generated foreground console runs the explicit observer once per
boot; the stock enabled OpenRC service owns k3s. Cleanup restores the original
service configuration. This does not replace the separate workload lifecycle
check above. Separate complete app-UID filesystem-buffer and
private/host-backed PTY checks under both executors cover actual
`O_APPEND` state, permissions, input/output, watch events and controlling
terminals (`build/matrix-pty-append-fs-buffer-app.log` and
`build/matrix-pty-append-{caller-devpts-app,devpts-shell}.log`).

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
