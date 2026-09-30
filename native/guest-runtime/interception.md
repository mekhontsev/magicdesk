# Guest Syscall Interception

One native supervisor owns each explicitly launched guest tree. It uses seccomp
TRACE stops for adaptation and USER_NOTIF for descriptor metadata, selected path
operations and the image-entry protocol. It never attaches to Android tasks.
The guest retains the selected real UID; neither the supervisor nor the filesystem
service changes SELinux policy or acquires root.

## Execution And State

The bootstrap publishes a private, size-checked ABI containing its gate addresses
at an explicit readiness stop. The supervisor reads it before resuming the guest;
exec republishes the same immutable bundle's ABI. No generated address header,
Java class name or environment-variable switch selects an implementation.

Data IO, common memory operations, futexes, clocks and signals pass directly to
the kernel. Adapted calls run on guarded scratch stacks leased per address space.
CLONE_VM shares the pool; fork copies mapping metadata without sharing leases;
exec replaces it. Completed calls return their lease. Thread records are reused
without a fixed thread-count ceiling. Heap work is confined to ownership changes
and new concurrency, not each ordinary intercepted call.

Regular-file seeks use a supervisor fast path after the original syscall's
seccomp admission and domain checks. A task-affine duplicated FD retains the
actual open-file description throughout inspection and seek; no FD-number
cache or namespace metadata RPC is involved. Shared positions and descriptor
reuse retain kernel semantics. Directories and unavailable remote descriptor
access use the ordinary adapter, including nondumpable processes.

`magicdesk-guest --statistics --store STORE -- PROGRAM` enables aggregate
syscall and ptrace counters, printed once when the owned tree completes. This
allocates its counter table only when explicitly requested and produces no
per-call logs. Detailed `--diagnostics` is a separate mode; neither is enabled
for ordinary launches or comparative timing runs.

The guest owns SIGSYS, signal masks and alternate stacks. Native clone and signal
return retain their register/extension ABI. Application filters are not removed:
ERRNO, TRAP and KILL retain kernel precedence over runtime TRACE/USER_NOTIF.
Register changes and same-site replay are rechecked by the kernel. Cancellation,
group stops, concurrent TSYNC and non-leader exec have dedicated fixtures.

## Protected Metadata

The supervisor first attempts kernel remote-memory/descriptor access. A
nondumpable task can deny that access; the runtime never makes it dumpable to
proceed. The task-affine fallback exports a retained descriptor through SCM_RIGHTS,
uses notification ADDFD and resumes a bounded copy at the original call site.
Instruction-only copy faults return EFAULT. Notification IDs are checked when
replying; cancellation invalidates pending work rather than authorizing a replay.

Filesystem service authorization remains based on the real executor UID, not
the browser's seccomp domain. Same-site filtering alone is not a complete
authorization boundary for every broker request or in-process syscall gate.

## Image Admission And Logical Credentials

`--admit-elf /absolute/guest/path` explicitly selects one ordinary ELF for a
launch-local sealed snapshot and logical set-ID metadata. The filesystem service
owns the catalogue: path lookup, object reopening, hardlinks and FD metadata agree
on that admitted object. It does not chmod or replace the stored source.

Initial launch and exec use the same admission contract. The supervisor tracks
logical IDs, irreversible drops and no_new_privs, publishes auxv/AT_SECURE, and
applies the corresponding nondumpable state before loader entry. Real kernel
credentials remain those of the executor. Other files retain ordinary metadata
and real permission failures; actual set-ID executable files are still rejected.

The proc-root model handles the restricted self/fd or fdinfo roots used by the
tested helper, with CLONE_FS ownership and irreversible narrowing. It is not a
general chroot or mount namespace. Namespace creation is not fabricated.

Stock Debian Chromium passes three fresh-profile headless JavaScript/DOM runs
and PNG production with its normal helper and no sandbox-disabling flags. Tests
observe installed application filters and require process completion. This is
application execution, not security equivalence to a native Linux sandbox.
Interpreter/library admission, mapped-code protection, complete descriptor/root
confinement and domain-authenticated filesystem authorization are not established.

## Lifetimes And Availability

The runner owns the filesystem service and guardian; the guardian owns the
supervisor and its descendants. Signalfd, pidfds and child/ptrace events drive
completion and cancellation. There is no ordinary launch deadline; an explicit
test deadline fails and cancels the owned tree. Supervisors close unrelated
inherited FDs while the application retains caller-supplied descriptors.
See [process lifetime](process-lifetime.md).

The lazy guest probe executes under this supervisor and tests the actual kernel
operations in the selected shell/root domain. Denial fails that guest launch;
it does not disable Android tools, PRoot, chroot, graphics or Desktop. The APK
does not load these native executables into ART or probe them during startup.
Current device coverage is UID 2000, NX809J, Android 16, Linux 6.12.23, 4 KiB pages.
Other kernels, page sizes and actual UID 0 require separate device verification.
