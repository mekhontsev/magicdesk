# Shroot Native Runtime

Native-only ARM64 Linux execution for MagicDesk's already-authorized shell or
root executor. The APK packages standalone executables; none are loaded into
ART. Kernel capabilities are probed only for explicit guest operations.
Shroot is experimental, **not a security sandbox or a complete Linux ABI**.
Programs retain the selected executor's host authority.

Installation, named environments, OCI images and user-facing launch recipes
belong in the [application guide](../../docs/guest-runtime.md).

## Ownership

The freestanding bootstrap maps ARM64 ELF and its optional interpreter, supplies
the initial stack, and hands execution to the stock glibc/musl loader. Static
ELF uses the same entry path. No preload library, custom libc dynamic linker,
PRoot tracer, external container manager or SELinux modification is used.

One native supervisor owns each launched tree. Seccomp TRACE/USER_NOTIF selects
operations requiring adaptation; ordinary data IO, memory, futexes and signals
stay native. A separate namespace worker owns SQLite and filesystem requests.
The syscall adapter has no SQLite or libc dependency. Reusable guarded scratch
stacks belong to address-space lifetime, not individual syscalls.

Named Shroot environments use the inode namespace. The direct-rootfs backend
remains a low-level fixture/control path, never an automatic fallback for a
failed namespace operation. Renderer, Android windows, authorization, PTYs and
distribution-management UI belong to the existing application services.

## Contracts

- [Syscall interception](interception.md): scheduling, fast paths, protected
  processes, guest credentials, application filters and image admission.
- [Namespace execution](namespace-execution.md): paths, descriptors, cwd,
  metadata and proc object identity.
- [Inode store](inode-store.md) and [filesystem service](filesystem-service.md):
  transactions, native backing, request delivery and uncertain outcomes.
- [Images and views](images.md): OCI layers, copy-on-write, rootfs archives,
  attachments and backup.
- [Process lifetime](process-lifetime.md) and [debugging](debugging.md):
  supervision, nested GDB/LLDB/strace workflows and explicit limitations.
- [Watches](watches.md), [named pipes](fifos.md),
  [Unix credentials](ipc-credentials.md), [shared memory](shared-memory.md) and
  [SysV IPC](sysv-ipc.md): independent ownership and validation boundaries.
- [Command client](../command-client/README.md): explicit launch-scoped
  delegation to MagicDesk, separate from guest execution.

## Execution Policy

The selected executor must be allowed to execute the bootstrap and map guest
code. The mapper uses private RW mappings followed by final segment protections,
never simultaneous RWX. Hardened policies forbidding RW-to-RX transitions can
still reject execution. Fixed images use MAP_FIXED_NOREPLACE with returned-address
validation; conflicts with the runtime mapping fail before replacement.

The app stages immutable content-addressed helper bundles. A running process
tree keeps its exact bootstrap and gate addresses across APK updates. Exec carries
configuration explicitly, not through environment variables the guest must retain.
The internal resume entry is not an authorization boundary.

Program admission checks X_OK and rejects actual host set-ID executables.
Explicit sealed-image admission controls logical guest set-ID separately.
X_OK does not reproduce all kernel execution/LSM checks. The adapter rejects
`AT_EXECVE_CHECK`, rather than stripping it or reporting success; see the
[kernel execution-check contract](https://kernel.org/doc/html/next/userspace-api/check_exec.html).
Supporting a caller of that interface requires validating the actual kernel ABI
and routing checks to the backing FD under the existing identity.

Unselected syscalls retain host semantics. General mount/cgroup/container
isolation and all Linux syscall entry points are not virtualized. Guest seccomp
filters remain installed; browser execution does not establish native-Linux
sandbox equivalence. Permission denials never authorize fallback to root.
Missing guest capabilities leave other MagicDesk services available.

## Unix Client Transport

Pathname sockets in the inode namespace have logical names and unique abstract
kernel endpoints. Bind publication is transactional; connected payloads,
SCM_RIGHTS and mmap remain native. Store-scoped Unix credentials follow the
separate credential authority. Direct-backend pathname bind is unsupported on
the tested shell domain.

Explicit socket routes translate selected addresses to the executor's graphical
admission endpoint without proxying payloads or changing descriptor identity.
The [application connection owner](../../docs/guest-runtime.md#graphical-connections)
authenticates clients and transfers connections to X11/Wayland servers. An inherited
Wayland FD supplies one connection; independent child clients need a routed endpoint.

## Build And Verification

`CMakeLists.txt` builds API-34 executables with the Android toolchain and pinned,
hash-verified SQLite/image dependencies. The application packages those helpers
and their license notices. Guest libc, desktop packages and GPU drivers are not
APK dependencies.

```sh
./gradlew :app:assembleDebug
```

The [fixture guide](../guest-exec-lab/README.md) lists host prerequisites,
prepared Debian/musl inputs and device commands. Kernel, filesystem, package,
debugger, browser and GPU workflows are separate checks; Desktop self-tests do
not replace them. Lab preparation does not configure user distributions.

Device coverage and limits are recorded in the
[application guide](../../docs/guest-runtime.md#coverage-and-limits).
API 34, other firmware, 16 KiB pages and actual root execution require their own
device verification. Build success or synthetic checks do not supply that coverage.

## Software GUI

Graphics fixtures use the installed APK's shell/PTY and X11/Wayland services.
Compositors stay under the app UID; Shroot supplies client programs, not an
elevated renderer. Readiness, visible Android pixels, input, file exchange and
process exit are separate assertions.

The fixture guide covers GTK/Qt, glibc/musl userspaces, Xfce, LibreOffice, GIMP,
browser filters and guest-owned Turnip. Each result applies to its stated
scenario and device, not every desktop environment or driver.
