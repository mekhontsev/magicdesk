# Filesystem And OCI Fixtures

[Fixture guide](README.md) | [Native architecture](../shroot/README.md)

OCI images, mount views, storage semantics and file-watch workflows. Use disposable environments for mutation and failure-injection tests.

- [OCI And Filesystem View Checks](#oci-and-filesystem-view-checks)
- [Native Names And Publication](#native-names-and-publication)
- [Resource Ownership Checks](#resource-ownership-checks)
- [OCI And Service Workflows](#oci-and-service-workflows)
- [Distribution Controls](#distribution-controls)
- [Watch Transport Controls](#watch-transport-controls)
- [Production Watch Checks](#production-watch-checks)

## OCI And Filesystem View Checks

Transient-store checks retain a journal across SIGKILL at object publication,
namespace staging and commit. Reconnect and transferred custody preserve the
temporary durability policy and exact final cleanup. Persistent-store checks
assert WAL with synchronous NORMAL and explicit durability barriers independently; retained-directory journal access also
survives renaming the store while two connections remain open.
Checks include pre-checkpoint wakeup for namespace-only changes, bounded WAL
reuse, descriptor-cache admission across foreign writes, and bounded SQLite FD
retention with exact release after the final connection.

`test_resources.py BUILD --case durability` runs inode-owner and real guest
checks under the selected shell UID. It covers data/WAL sync failures, allocation
reserve publication and reuse, four concurrent writers, directory-watch events,
snapshot-local inventory and modeled loss of uncheckpointed WAL. Guest calls run
with ordinary and nondumpable/seccomp-filtered processes, including retained
unlinked FDs, O_SYNC, tmpfs, writable-overlay remounts and native attachment
barriers. Native attachments do not acquire guest hardlink/FIFO permissions.
The same checks run in `--case all`; `--case stores` includes the inode-owner
part. Process kills and the WAL-loss model do not simulate physical power loss.

## Native Names And Publication

`test_resources.py BUILD --case stores` includes the production native-leaf and
publication owners. These checks use the default representation, not a feature
flag or an alternate provider. Ordinary eligible creation under a guest-owned
virtual-permission directory must perform zero SQL writes, xattr operations and
forced synchronization. Non-root creation and exceptional initial modes use
native names with one synced sparse-metadata preparation before publication,
still without SQL writes. Physical-permission import directories, default ACLs
and overlay projections retain catalogue allocation.

The matrix covers mixed directory enumeration and transferable offsets, retained
and unlinked FDs, O_PATH metadata, ACL/capability updates, Landlock inode pins,
native and mixed watch streams, hardlink promotion, sealing, snapshot COW,
overlay admission and portable backup/restore. Publication tests interrupt each
durable phase and recovery itself, inject IO errors, and check both cold and
already-open owners. Sparse-metadata transitions get separate interruption and
error injection, including private preparation before publishing non-root names.
Stable O_PATH nofollow sources must preserve the same inode after guest
rename/unlink; the buffered-open runtime fixture uses a guest-created parent
and forbids hidden socket RPC. Format-17 revision admission must preserve existing data and
reject a metadata upgrade while another owner holds the old revision.
Frozen revision-1/revision-2 SQL and sparse-metadata literals are independent of
current creation code. Tests compare upgraded/fresh schemas, preserve identities
and hardlinks, inject ENOSPC and death around revision commits, and reject full
audits or schema admission on file-operation paths. The shell runner also checks
fixed portable backup-v1 input, native-revision export/restore and distinct source
dependencies across persistent scopes. Reopened COW generations must address
their original source bodies directly and keep independent writes.

`test_execfd.c`, built by `build-libc-fixtures.sh`, additionally copies a fresh
ELF, executes its O_PATH/CLOEXEC descriptor before and after unlink, closes all
inherited descriptors and reads the image through local and foreign proc paths.
It checks metadata, inode identity, writable-open rejection and proc-image
reexec. `test_landlock_guest.c` denies execution of attached and unlinked fresh
copies through preopened descriptors. Run both as guest `0:0` and `2000:2000`;
both exercise native-name allocation. These run under the same real shell UID,
not Android root. The fixture executable must belong to the selected guest user
for its sticky-directory link/unlink controls.

These are process-death and injected-error checks, not physical power-loss tests.
Chromium's separate browser workflow verifies the application-filter path with
its sandbox enabled; it is not replaced by filesystem component assertions.

## Resource Ownership Checks

Build `resource_guest` and `test_task_resources` with
`MAGICDESK_GUEST_FIXTURES=ON`. Run the resource owner directly for component
checks; the device runner compares actual shell UID 2000 with intercepted guests:

```sh
python native/guest-exec-lab/test_resources.py BUILD
python native/guest-exec-lab/test_resources.py BUILD --case fs-buffer
```

The runner owns disposable stores and removes them unless `--keep` is selected.
`--compare-installed` adds matched metadata/spawn controls against the installed
runtime; instrumented counters remain separate from timings. See
[resource verification](../shroot/resource-tests.md) for detailed assertions.
This guide indexes those checks rather than maintaining a second copy:

| Area | Focused cases | Contract |
| --- | --- | --- |
| Live readonly policy, graph publication, copied views and retained dirfds | `mount-policy`, `mount-graph`, `mount-namespace` | [Mount views](../shroot/mount-views.md) |
| Portable store/lower leases, authenticated origins and owner death | `inode-custody`, `origin-transfer`, `ipc-origins`, `ipc-origin-guest` | [Origin transfer](../shroot/mount-views.md#authenticated-origin-transfer) |
| Peer agreement, scoped rendezvous, ancillary formats and message custody | `ipc-peer`, `ipc-paths`, `ipc-ancillary`, `fd-messages` | [Unix IPC](../shroot/ipc-credentials.md) |
| Exact FD publication, terminal receipts, quotas and protected callers | `fd-observation`, `fd-handoff`, `fd-custody`, `fd-custody-dirfd`, `fd-custody-nofollow` | [Descriptor origins](../shroot/descriptor-origins.md) |
| Captured paths, checked output, native results and transfer cancellation | `fs-buffer`, `fs-dispatch`, `fs-results`, `fs-signals`, `fs-export`, `fs-return`, `fs-directory`, `fs-capture` | [Filesystem service](../shroot/filesystem-service.md) |
| Proc images, cwd, executable custody and restricted descriptors | `proc-custody`, `exec-custody`, `restricted-descriptors` | [Process resources](../shroot/process-resources.md) |
| Concurrent progress, ordered completion and descendant cleanup | `control-event`, `event-fairness`, `ofd-order`, `open-job`, `launch-cancel`, `rpc` | [Resource verification](../shroot/resource-tests.md) |
| Store recovery and lifetime | `stores`, `durability`, `transient-store` | [Inode store](../shroot/inode-store.md) |
| Minimum-kernel routes and mandatory-operation failure | `kernel-baseline` | [Kernel compatibility](../shroot/kernel-compatibility.md) |

The kernel-baseline case disables PIDFD_THREAD and exercises the existing fallback;
a newer kernel passing that case is not a test on Linux 5.15. Missing Shroot
capabilities must leave independent shell execution available.

Mapped request/output checks include invalid and guard-page paths, relative cwd
and dirfd, live umask, closed/reused descriptors, signal cancellation and
ordinary/protected/application-filtered callers. A fallback-only success is not
proof of the optimized route: retain the route/custody counters.

Origin and graph fixtures check malformed parcels, source exit, queued rights,
forwarding, allocation/FD-exhaustion rollback and exact final release. They do
not establish transparent application mount-origin transfer merely by importing
a component parcel. Source/message association, store authorization and receiver
publication each need their own proof.

### Optional Transport Experiments

`fd-parcel`, `pidfd-table` and `mapping-task` are isolated experiments, not
new default runtime prerequisites. Keep their reports separate from the default
resource suite. In particular:
- A private parcel consumes a receiving slot; a relay adds a send that can hit
  the actual UID's in-flight-rights quota before native direct delivery does.
- A preallocated receiver slot does not establish ownership across arbitrary
  close_range, exec, table sharing or reduced limits.
- Protected-provider pidfd_getfd denial and io_uring unavailability are capability
  results, not reasons to disable guest protection or elevate the executor.
- Carrier agreement cannot change an existing NATIVE connection into ORIGINS_V1.
  Native peers must never receive private framing.

The [resource verification guide](../shroot/resource-tests.md#message-origin-association-candidate)
retains candidate semantics, rejected alternatives and quota comparisons.

## OCI And Service Workflows

Configure `native/shroot` with `-DMAGICDESK_GUEST_FIXTURES=ON` and build
`bootstrap supervisor run service image test_snapshot test_mounts`.
`test_mounts --path-walk NEW_DIRECTORY` isolates logical path traversal and its
depth-independent FD/transaction bounds. The full mount suite includes it.
`fixtures/path-workloads.py` runs deep-path reads, existing writable opens and
create/rename/unlink, hardlink/unlink and repeated metadata updates in a disposable guest directory,
checking contents and inode identity while reporting timings. Bind its containing directory into
the same prepared guest for both runtime versions; use `--statistics` separately
for work counts. Timings are observations, not pass/fail thresholds.
The offline importer tests require Python and the `zstd` CLI:

```sh
python native/guest-exec-lab/test_oci.py BUILD_DIRECTORY -v
```

The importer must run in a native environment that permits its syscalls.
Android app-domain seccomp can terminate it on `faccessat2`; Termux execution
is not a substitute for the selected shell executor. Signals fail even negative
import tests. On-device shell import and launch checks use the MCP runner below.

`fetch_oci_fixture.py REPOSITORY REFERENCE NEW_DIRECTORY` downloads a public
Docker Hub ARM64 image into a local OCI layout. It is a development fixture,
not an installed registry client. Preserve the returned manifest digest for
reproducible inputs. Run independent Alpine and Debian layouts through actual
shell UID 2000:

```sh
python native/guest-exec-lab/test_oci_runtime.py BUILD_DIRECTORY ALPINE_LAYOUT DEBIAN_LAYOUT
```

The runner stages production helpers, tests snapshot and mount contracts, then
imports each layout and creates two instances. It checks copy-on-write,
concurrent independent launches, readonly attachments, cross-boundary symlinks,
working directories, PATH execution from an attachment and release after launch.
It retains exact commands, results, digests and device/build identity, without
starting Desktop or changing privileges. Uploaded layouts and stores remain for
inspection. The installed CLI additionally needs ordinary Shell-console checks;
standalone helper coverage alone does not verify APK packaging and dispatch.

The installed named manager has a separate workflow:

```sh
python native/guest-exec-lab/test_environment_cli.py --apk app/build/outputs/apk/debug/app-debug.apk
```

It downloads public Alpine and Debian images through the APK's registry client,
exercises independent names, run/exec/login, directory attachments, backup/restore
and explicit prune under UID 2000. A guest HTTP request holds a real launch open
while removal and backup are required to fail. The fixture's Python controller
is development tooling, not an installed runtime dependency; guest acquisition
and execution use no Termux command or service. Reports retain the exact APK,
image configuration, storage paths and command results.

Stock service-image workflows and development tools:

```sh
python native/guest-exec-lab/test_oci_services.py OUTPUT --nginx NGINX_LAYOUT --redis REDIS_LAYOUT --postgres POSTGRES_LAYOUT
python native/guest-exec-lab/test_oci_services.py OUTPUT --python PYTHON_LAYOUT --node NODE_LAYOUT --httpd HTTPD_LAYOUT --memcached MEMCACHED_LAYOUT --java TEMURIN_JDK_LAYOUT
python native/guest-exec-lab/test_development_runtime.py OUTPUT --debian DEBIAN_LAYOUT --install
python native/guest-exec-lab/test_service_runtime.py BUILD DEVICE_STORE OTHER_DEVICE_STORE --installed
```

The first two commands use packaged helpers by default; `--build BUILD` explicitly
stages a native build. MariaDB and Caddy have corresponding image arguments.
PHP, Ruby and .NET SDK images have separate arguments as well. Staged runs record
each native helper's SHA-256 and verify it after upload.
Each image gets a disposable writable instance, loopback ports and owned process
trees. Readiness comes from service events and is followed by actual requests,
shutdown and, where supported, restart/readback. `--NAME-instance` reuses an
explicit disposable instance; PostgreSQL/MariaDB initialization checks require
a fresh instance. Control overrides are never default-entrypoint passes.

The development check installs packages only with explicit `--install`, in its
selected disposable Debian store. Compiler and live-debugger results are separate;
reading symbols is not a debugger pass. Reports preserve failures, image digests
and exact commands. Current coverage and unsupported requirements are in
[image checks](../shroot/images.md#checks).

`test_proot_debugger.py REPORT.json --distribution ubuntu` runs the same GDB
sources, commands and assertions in an installed Termux PRoot distribution with
GCC, libc headers and GDB already available. It checks both default PRoot and
`PROOT_NO_SECCOMP=1`, without root or package installation. Each case has a bounded
process lifetime; temporary sources and binaries are removed afterward. The
report records versions, output, exit status and timeout failures separately.

`test_debugger_runtime.py OUTPUT --instance STORE [--build BUILD]` runs raw
debugger lifecycle checks and real GDB attach, LLDB, gdbserver, strace, gprof,
Callgrind and perf-event checks. `--proot ubuntu` selects the same cases in an
installed Termux PRoot distribution; `--case NAME` restricts a run. The
`gdb-attach-threads` case checks all 41 target threads, backtraces, a memory write,
detach and normal target exit. `gdb-active-io` requires repeated breakpoints and
normal exit while eight peer threads create, write, read, reopen, rename and
unlink files, exercising
all-stop delivery across internal adapter operations. `syscall-exec` checks image-entry ordering and
paired syscall stops across exec. `syscall-edit-close` changes the descriptor
argument at a syscall-entry stop, with continued tracing and immediate detach;
native close and virtual FD ownership must both follow the changed argument.
Strace checks decoded arguments and fork
events without stray-exec recovery. The prepared
userspace needs GCC, Python 3, GDB, LLDB, gdbserver, binutils, strace and Valgrind.
No package is installed by the test. `--diagnostics` enables supervisor diagnostics
for Shroot only. A denied perf event is recorded as unavailable, never as working
profiling. Device capability and debugger compatibility are separate results.

`--gdbserver-binary FILE` explicitly stages a standalone Linux ARM64 binary under
`/tmp`, retaining its hash in the report without replacing the installed package.
This permits a current gdbserver control when an older distribution package has
an upstream ARM64 register bug. Timeouts fail their cases and the launch owner
cancels remaining descendants. Shroot fixtures persist only in the explicitly
selected disposable store. See the [debugger contract and measured coverage](../shroot/debugging.md).

`test_kernel_contract.py OUTPUT --fixture BUILD/libmagicdesk_guest_kernel_contract.so
--instance STORE [--build BUILD]` compares normalized observations from the same
static executable directly under shell and inside Shroot. Cases cover wait and
ptrace contracts, signal delivery, descriptor lifetime, openat2 and concurrent
rename confinement. The `debug-wait{4,id}-{signal,restart}` cases compare EINTR
and SA_RESTART for idle inferiors with SIGCHLD masked. The debugger fixture's
`--case strace-detach` checks SIGINT detach from 41 idle threads and subsequent
target input/exit; `probe_podman_runtime.py --trace --exercise-ports` also detaches
from live aardvark-dns and repeats named-container exchanges before cleanup.
The ancillary cases cover SCM_RIGHTS peek, truncation,
253 descriptors, batch delivery, source addresses and partial publication faults;
pipe EOF checks descriptor cleanup. `rights-socket-error` covers a kernel
payload-copy fault, poll/epoll readiness and shared one-shot error consumption
through SO_ERROR/read/recv, duplicated FDs and a forked peer.
`rights-watch-fault` checks received inotify descriptors after late message,
control, source-address and timeout publication faults, with fresh receivers
that cannot inherit an already installed watch filter.
`rights-batch-fault` is an unresolved
comparison: private recvmmsg headers allow later packets to be consumed before
a user-header fault, without the kernel's deferred socket error. It remains a
failing default case, not a skipped or expected-pass check. `--case NAME` selects
a focused comparison. Native capability gaps are recorded separately from guest
assertions. Build the `kernel_contract` and `test_elf` CMake fixture targets;
the latter runs malformed ELF, page-size preflight and actual BSS mapping checks.

`test_sysv_runtime.py OUTPUT --instance STORE [--build BUILD]` compiles the focused
SysV fixture with the prepared distribution's GCC and checks semaphore/message
semantics, protected copying, independent launch traffic and owner-death recovery.
`test_sysv_apps.py` accepts the same arguments and tests stock fakeroot-sysv,
PHP with sysvsem/sysvmsg/pcntl, Symfony Lock and Apache prefork. These packages
must already be present; the tests do not install software. `--case NAME` selects
individual workflows. See the [SysV contract](../shroot/sysv-ipc.md).

## Distribution Controls

Distribution and emulator workflows use the same runner and per-instance policy:

```sh
python native/guest-exec-lab/test_distribution_runtime.py OUTPUT --build BUILD ubuntu --layout UBUNTU_LAYOUT --install
python native/guest-exec-lab/test_distribution_runtime.py OUTPUT --build BUILD centos --layout CENTOS_LAYOUT --install
python native/guest-exec-lab/rootfs_oci_fixture.py --help
python native/guest-exec-lab/test_qemu_runtime.py --help
```

The rootfs wrapper retains an unchanged official Arch Linux ARM tarball as one
OCI layer. Verify its detached signature before importing; wrapping alone is not
authentication. Arch package commands retain pacman's configured sandbox and
signature checks. Shroot supplies ABI 1 filesystem restrictions even when the
host lacks Landlock; `test_isolation.py` measures native availability separately.
`--arch-without-landlock` explicitly selects only pacman's filesystem-sandbox
opt-out for package checks; signatures and syscall filtering remain enabled.

`test_arch_runtime.py` is the separate **as-is** acceptance check. It rejects
effective sandbox opt-outs, retains `DownloadUser=alpm`, checks the stock account
transition, runs the filesystem restriction contract, then synchronizes pacman's
databases. `--install-package` also installs and runs `tree`, removing it only
when it was not installed before the check. Use a disposable instance with initialized signing keys and DNS;
synchronization changes its package databases. Neither missing Landlock nor a
failed synchronization counts as a pass. It compares the ABI probe with native
UID 2000, but does not mistake that probe for enforcement coverage.

```sh
cmake --build BUILD --target bootstrap supervisor run image landlock_guest vm_init
python native/guest-exec-lab/test_arch_runtime.py BUILD ARCH_STORE --install-package
python native/guest-exec-lab/test_kernel_vm.py BUILD --kernel ARM64_KERNEL \
  --fixture BUILD/libmagicdesk_guest_landlock_guest.so
```

The static `landlock_guest` fixture uses ABI 1 rights: permitted/denied paths,
symlinks, hardlink aliases, same-directory rename, rejected cross-directory
rename/link, preopened data FDs versus proc-FD reopening, ruleset-FD duplication
and mutation, stacked intersections, fork/exec, thread-local policy and retained
directory identity after rename/replacement. `--setup-only DIRECTORY` validates
only fixture setup and cleanup; it never reports enforcement success.
The same assertions run inside Shroot and under an unprivileged UID in a Linux
QEMU control VM. Native Android shell cannot create the fixture's hardlinks;
only its ABI probe runs outside Shroot on that device.

`test_kernel_vm.py` requires local `qemu-system-aarch64` and `cpio`. It creates a
minimal initramfs and records kernel/fixture/init hashes and the serial log.
The VM has no network, host filesystem attachment or Docker service. Its init
runs the contract as UID 1000 and powers off only that VM; it does not reboot
Android. A process deadline kills a hung emulator. This control is a reference
Linux implementation, not a Shroot pass or a new runtime kernel requirement.

XZ rootfs archives are transcoded without changing tar contents, including device
entries. The production importer retains logical device metadata and rejects
unsupported objects. Verify source signatures before
conversion; wrapping or transcoding alone does not authenticate a rootfs.
The QEMU fixture checks an x86-64 program's file IO and fork/wait through the
distribution's user-mode emulator, not KVM or full-system virtualization.

`test_qemu_docker.py` separately boots Alpine with QEMU TCG and exercises actual
Docker Engine inside the VM, including container/volume reuse and shutdown.
`prepare_astra_userspace.py` prepares official ARM64 packages for
`test_astra_userspace.py`; its repository checksums over HTTPS are not a pinned-GPG
authentication claim. `test_gentoo_runtime.py` checks stage3 userspace and GCC;
`--packages` adds repository synchronization and a GNU hello source-build,
installation and removal without disabling Portage's sandbox FEATURES.
`test_rootfs_oci_fixture.py` checks conversion digests, exact unfiltered tar content
and hardlinks without running an image. `test_oci.py` checks logical device import/backup,
lower-layer replacement, guest SHM, strict path/metadata handling and concurrent
inspection while another participant writes the same store.
Its helper-owner-death control needs a native FIFO: hosts denying native mkfifo
report that one fixture unavailable, not passed. Logical guest FIFO and device
checks remain required under the selected executor.

## Watch Transport Controls

```sh
python native/guest-exec-lab/test_watch_runtime.py build/shroot-image-tests --contracts
```

The production watch runner also executes the Bionic transport controls built
by CMake's `MAGICDESK_GUEST_FIXTURES`: `watch_queue`, `watch_read` and
`watch_activation`. They run through the shared executor selected by
`--executor app|shell` or `SHROOT_TEST_EXECUTOR`. App execution requires the
matching fixture-enabled debug APK; release APKs do not package these tests.

The single-owner queue retains whole inotify records, adjacent coalescing,
bounded overflow and rejected-delivery state. Its stream socket carries readiness only.
Tests cover poll/edge-triggered epoll, short buffers, descriptor flags, dup,
SCM_RIGHTS lifetime, and a stolen marker failing without blocking the owner.
The owner does not retain a guest read end between operations. Record storage
is recycled up to its high-water mark; reads use caller-owned scratch memory.

The tracer fixture compares lazy process-wide and FD-selected read interception.
It checks zero ordinary-read stops before activation, short-record errors,
notification cancellation, EINTR, SA_RESTART and the application's signal context.
The FD-selected control additionally checks zero stops on unrelated reads after
activation, and validates the retained kernel-object identity after descriptor-number
reuse. Its microtimings describe this native fixture only, not GUI latency or
production-runtime overhead. Output memory is fixture-owned shared memory;
protected memory transfer and concurrent buffer mutation are not certified.

Two blocking transports have separate limits. A predeclared notification gate
uses the existing listener, but a filter admitting only the original read site
rejects it. The native same-site read control preserves that filter and signal
context, but is single-reader only: it restores a consumed readiness marker before
reading the queue. It does not implement production concurrent-reader ownership
or empty zero-length reads. Native inotify controls establish that an empty
nonblocking zero-length read returns EAGAIN, and that a record cannot span two
short readv vectors on the tested kernel. The mediated readv fixture exercises
only error/short-result completion within the first vector.

Activation controls explicitly observe EBUSY for a second USER_NOTIF listener,
ENOSYS rather than listener inheritance for a listenerless notification filter,
and TSYNC rejection after a peer diverges its filter chain. These are verified
limitations, not successful runtime workarounds. Production integration and
its ownership limits are described in the [watch contract](../shroot/watches.md).

## Production Watch Checks

Configure `native/shroot` with `-DMAGICDESK_GUEST_FIXTURES=ON`, then build
`bootstrap supervisor run service watch_guest watch_launch watch_store
watch_queue watch_read watch_activation`.
`test_watch_runtime.py BUILD_DIRECTORY` stages immutable production binaries
through the shared fixture executor, imports a fresh disposable store owned by
that executor and checks real guest libc inotify calls.
It neither installs an APK nor changes Desktop or access settings.
`--user 0:0` selects guest root, including the independent watcher/writer
launches, so newly created regular files exercise native names. The default
`--user 2000:2000` checks non-root creation and its sparse metadata; neither
changes Android UID.

The suite covers directory/name events, rename cookies, file-data events,
read/readv/FIONREAD boundaries, concurrent readers, dup/fcntl, queued SCM_RIGHTS
through recvmsg/recvmmsg, descriptor reuse, EINTR/SA_RESTART and 64 execs with
constant filter count. The complete glibc/musl suite runs again with dumpability
disabled. The filter-count observer retains its own proc FD across exec, avoiding
new selectors introduced by the measurement itself. `receive-filtered` forbids
further seccomp installation and rejects MSG_PEEK with an application filter:
the denial must preserve both the queued message and descriptor table. An allowed
receive then publishes all three rights with no filter growth, checks watch/pipe
IO, closes every alias and verifies pipe EOF and exact retained-channel ownership.
Churn checks 7,680 distinct duplicate targets without unbounded filter
growth. An aggregate interception counter detects accidental ordinary-read tracing
in the unsaturated case. Two independent observers receive a third launch's
mutations. Store controls verify rollback after SIGKILL, last-reader retirement
and journal-overflow notification. Reports retain exact uploads, identities,
commands and results; a timeout is a failure, not completion evidence.

`build-watch-libc-fixture.sh glibc|musl SYSROOT OUTPUT` builds the same guest
fixture with that userspace's libc. Pass its `rootfs.tar.gz` as `--rootfs`.
Use `--contracts` with the additional CMake targets `test_inodes test_import
test_rpc` for namespace/import/RPC regressions under the same shell identity.
Host-policy exclusions in the import fixture remain reported as LIMIT.

The GIO fixture links the stock Linux library and requires its inotify backend,
not a fallback poller. Its readiness follows successful registration; a separate
writer creates, renames and deletes the file. The D-Bus check registers ordinary
session-config watches and completes a bus request. It checks the named
`WATCH_PATH` counter, not a hardcoded operation number. The prepared NSS files
contain both guest users. A guest-root D-Bus warning about raising the host hard
FD limit to 65536 is retained as an explicit report limit; watch and bus failures
are not ignored. Build-only prepared inputs:

```sh
node native/guest-exec-lab/prepare.mjs build/watch-gio-sysroot --package libglib2.0-dev
sh native/guest-exec-lab/build-watch-gio.sh build/watch-gio-sysroot/sysroot build/watch-gio
python native/guest-exec-lab/prepare-watch-userspace.py PREPARED_DEBIAN_ROOTFS build/watch-userspace.tar.gz
python native/guest-exec-lab/test_watch_runtime.py BUILD_DIRECTORY \
    --userspace build/watch-userspace.tar.gz --gio build/watch-gio --contracts
```

Native fixtures and Linux libraries are not shipped in the APK. These focused
checks do not certify arbitrary application filters, cross-supervisor FD
transfer, all GUI workflows or performance against PRoot.

`test_watch_apps.py BUILD --store STORE --runtime FIXTURE_DIRECTORY
--keyboard-directory HOST_XKB --recipes RECIPE_CLASSES` uses the installed APK
and a prepared Debian/Alpine system containing Mousepad, Thunar, fonts and a
session bus. The fixture directory supplies `md-await-exit`; the recipe classes
are the existing app-side `GraphicalRecipe` fixture. On its own virtual display,
the test changes a document from another launch, accepts Mousepad's reload and
checks actual clipboard text. It creates a file externally in an open Thunar
folder, renames the visible selection, verifies the file from another process,
then moves the current folder and checks Thunar's parent navigation. Both clients
must exit with status zero. It never starts Desktop.

The guest fixture's `aliases DIRECTORY` command prints data-event attribution
before/after rename, unlink and last close with two hardlink names. It is a
characterization, not an assertion of exact Linux dentry history. Native controls
must report host hardlink permission denial rather than substituting copies.
