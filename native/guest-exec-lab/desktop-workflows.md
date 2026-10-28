# Desktop And GPU Fixtures

[Fixture guide](README.md) | [Native architecture](../shroot/README.md)

SteamOS, libc/toolkit applications and guest GPU rendering. A rendered compositor is not proof of every application, input or audio workflow.

- [Holo ARM64 Userspace](#holo-arm64-userspace)
- [Flatpak Chromium](#flatpak-chromium)
- [Ubuntu Firefox Snap](#ubuntu-firefox-snap)
- [Executable Private Memory](#executable-private-memory)
- [Audio Continuity](#audio-continuity)
- [Steam Client](#steam-client)
- [Libc, IPC And Desktop Checks](#libc-ipc-and-desktop-checks)
- [Distribution Packages And Applications](#distribution-packages-and-applications)
- [Linux GPU Fixture](#linux-gpu-fixture)

## Holo ARM64 Userspace

### Mesa Queue And Sandbox Admission

The common recipe patch defers empty `util_queue` worker creation until the
first submitted job. Initialization, worker-count selection and empty finish
must not make a process multithreaded before Chromium's GPU sandbox admission.
First submission starts the requested workers; subsequent jobs retain normal
parallel scheduling. A partial worker-creation failure keeps the workers that
started. Failure to create even one worker is explicit and fatal, not a silently
dropped job or an unsignalled fence: the submission API has no error return.

Inside a guest retaining its recipe build, run:

```sh
prefix=$(cat /etc/magicdesk/mesa-prefix)
python3 fixtures/mesa-queue.py /var/cache/magicdesk/mesa/${prefix##*/}/build
```

The fixture links the actual built Mesa utility libraries with their original
feature defines. It covers empty initialization/finish/destruction/atexit,
parallel first jobs, resizing, concurrent producers, partial creation failure
and explicit first-worker failure. A thread-creation wrapper injects failures
only into the test binary, never the installed driver.

Alpine Chromium 149.0.7827.53 passes ordinary-user Wayland input, download,
chooser/readback, WebGL pixels and same-profile relaunch with the common Mesa
26.2.3 patch. `chrome://gpu` reports hardware composition, `Sandboxed: true`
and zero GPU-process crashes. The recipe's musl system search path keeps the
selected EGL/GBM libraries consistent when Chromium rewrites argv/environment
storage. These are driver/loader changes, not Chromium flags or a browser build.
Keep GPU sandbox admission separate from renderer sandbox and hardware-renderer
checks; a hardware-rendered page alone does not certify GPU sandboxing.

The same queue fixture passes against Ubuntu glibc. Ubuntu Chromium Snap
155.0.8059.84 also passes the GUI workflow and same-profile relaunch with
hardware composition, GPU sandbox admission and no GPU crashes. This run uses
an explicitly identified lab bundle of incrementally rebuilt recipe libraries,
published by `magicdesk-snap-gpu`, not a second Mesa build or a modified browser.
The publisher additionally covers switching driver content and repeating
unchanged preparation; its consumer-side library hashes must match the build.

### Flatpak Chromium

Run `sh fixtures/flatpak-chromium-smoke.sh` from a terminal in the running guest
desktop, using its existing D-Bus session. The fixture checks the sandbox's CPU
topology view, writable `/dev/shm`, JavaScript execution and three successful
browser exits. Each launch uses a fresh sandbox-private profile; the user's installed browser profile
is untouched. Logs remain in the printed temporary directory. There are no
sandbox-disabling flags. This does not verify GPU acceleration or document portals.

With the Turnip recipe and a compatible local GL.host extension prepared, run
`sh fixtures/flatpak-chromium-zink.sh` in the same desktop terminal. Do not set
`FLATPAK_GL_DRIVERS` or pass environment-cleanup flags for the automatic-path
check. It requires three fresh-profile WebGL2 runs with a
Zink/Turnip renderer, exact pixel readback, no GL errors, normal exit and no
GPU-process crash. Neither the sandbox nor watchdog is disabled. A successful
DOM result alone is insufficient: Chromium can restart a crashed GPU process.
This headless fixture does not establish window presentation; separately verify
a Wayland window's changing frames, pointer input and normal closure. Wake both
the Android output and the guest compositor; an Android wake lease does not
override KDE's own DPMS. The install/update trigger and ordinary GL.host selection
have been checked on SteamOS with Freedesktop 25.08. Other runtimes need their own
admission and rendering checks; see [the publisher](../../docs/graphics.md#flatpak-gpu-drivers).

`sh fixtures/sandbox-document-portal.sh APP [flatpak|snap]` separately requires temporary document
export, read/write through the exported path inside Flatpak, and revocation. It
does not alter browser permissions or disable the portal. The SteamOS fixture
passes with the guest FUSE provider, including live permission revocation. This
uses the packaged `fusermount3` and ordinary stored set-ID semantics; no
per-helper launch admission is needed. It is not a substitute for checking a
desktop file chooser's UI.

`test_fuse.py --build BUILD --store STORE --case portal|session|rofiles|squashfs` stages a
private native runtime under UID 2000. Each invocation selects one case. The
rofiles case exercises concurrent append, watch events/lifetimes, cancellation
and retained directory FDs; the
SquashFS case checks ELF/script execution, dynamic loading, private mappings,
noexec, concurrent readers and a file retained through lazy unmount. Default
launches use stock libraries and lazy entry; `--base` selects ordinary interception
and `--machine-entry` requires actual accelerated completions. See
[FUSE contracts](../shroot/fuse.md) for unsupported operations.

The portal and session cases compile the production `LinuxGraphicalEnvironment`
wrapper; they do not substitute a fixture-only unmount trap. `session` retains
a writable, deletable document export and a second FUSE mount until command
exit, with both live and terminated daemons. It requires runtime-directory
removal, intact source contents, an unrelated mount remaining usable, and the
original application exit status. These checks pass on the installed SteamOS
with both Base and lazy entry, under the ordinary guest user.

An independent Shroot launch that merely inherits another launch's bus address
is not equivalent: portal authorization can depend on the requesting process's
namespace. Nor is a new `dbus-run-session` an adequate replacement for a desktop:
KDE activation can start `plasma_waitforname org.freedesktop.Notifications`, which
outlives a successfully exited browser when Plasma is absent. Shroot intentionally
retains the entire guest process tree. Record the browser's exit separately from
the supervised launch, and do not disable descendant ownership to pass a test.

### Ubuntu Firefox Snap

Use an installed Ubuntu recipe environment, stock systemd/snapd, its ordinary
user session and the recipe's Weston/Turnip profile. Stock libc is sufficient.
The [distribution matrix](validation-matrix.md) owns current pass/fail status;
[Ubuntu evidence](distribution-evidence.md#ubuntu) records exact builds, cold-start
measurements, GUI runs, refresh and portal cleanup. Earlier successful runs are
not acceptance of every later runtime or distribution.

#### Session And GPU Preparation

Use the real user manager's bus (`/run/user/UID/bus`) and import the compositor
environment into activation services. An unrelated `dbus-run-session` bus cannot
activate an already-running systemd user manager. In a focused control-target
boot, start `snapd.mounts.target`, `snapd.socket` and `snapd.service` first.
Starting one application unit alone can leave its base/content snaps absent;
normal OS boot supplies those dependencies. Do not substitute custom binds or
modified snap-confine arguments.

The recipe's `magicdesk-snap-gpu` publishes the existing compatible private Mesa
through the standard content connection; it does not build a second Mesa.
Manual preparation inside the same booted guest is:

```sh
magicdesk-snap-gpu --jobs 4
```

See [Snap GPU preparation](../../docs/graphics.md#snap-gpu-drivers) for ABI checks
and connection ownership. The shared Mesa patch and publisher serve every recipe;
an SDK-specific experiment is not an automatic installer step.

For a focused init/namespace check:

```sh
python3 test_snap.py --build BUILD --store STORE --snap firefox \
    --machine-entry --user-session --namespace-only
```

Keep the stock Snap helper timeout. Record discarded-namespace cold startup
separately from warm launches; a render after setup does not prove adequate
cold-start margin.

#### Mapping And Graphics Controls

With the content snap mounted in that guest, check XKB mapping visibility:

```sh
python3 fixtures/fuse-mapping-probe.py \
    /snap/gnome-46-2404/current/usr/share/X11/xkb/rules/evdev
```

This checks initial mapping visibility, not coherent writable shared mmap.
Native fixtures own [FUSE mapping/lifetime coverage](../shroot/fuse.md#mapping-limits).
`fixtures/fuse-squashfs.py` separately checks executable naming and a sibling
library resolved through `$ORIGIN`.

`fixtures/egl-device.c`, linked with `-lEGL -lGLESv2`, checks real green pixels,
device enumeration, repeated initialization and rejected DRM-node queries.
Run `egl-device hardware wayland`, `hardware x11` and `hardware surfaceless`,
then repeat with `software` and softpipe. Retain the X11 control with
`LIBGL_KOPPER_DISABLE=1`. These controls distinguish the driver from browser
policy; they do not replace application acceptance.

#### Interactive Browser Acceptance

For retained `installed_desktop.py` sessions, `start --awake-display phone` (or
`attach --awake-display phone` on the existing fixture) keeps an already unlocked
phone awake with a bounded lease. The default retains only the virtual display;
it does not prevent the phone from sleeping. Subsequent fixture commands renew
the selected lease, and `stop` releases only that fixture's lease. Neither mode
changes the system screen timeout or unlocks the phone.

Inside the prepared Wayland/user-bus session, with the fixtures bound into the guest:

```sh
python3 fixtures/sandbox-browser-gui.py --output /tmp/snap-gui-results
python3 fixtures/sandbox-browser-gui.py --browser chromium --package snap \
    --output /tmp/chromium-snap-gui --require-webgl
python3 fixtures/sandbox-browser-gui.py --browser firefox --package flatpak \
    --output /tmp/firefox-flatpak-gui --require-webgl
```

Type `shroot`, click the button, download the test file, and choose the printed
download path through Browse. Close normally. Acceptance requires all receipts,
exact bytes and exit status zero, followed by a same-profile relaunch. Verify
document export/read/write/revocation and portal shutdown/FUSE mount cleanup
separately; a chooser can succeed through a toolkit fallback without document grants.

On a fresh Plasma account, complete the ordinary KDE Wallet setup/unlock dialog
before judging a browser's first navigation. The dialog can be behind the browser;
pending key-store initialization can hold cookie-backed HTTP requests while
internal pages and direct network probes still work. Use the test account's
wallet, not `--password-store=basic` or a disabled secret service.
`--entry base` and `--entry lazy` select the comparison mode without changing the
acceptance checks; Flatpak receives the setting explicitly. Chromium's optional
`--net-log` writes `netlog.json` inside the fixture work directory. It is diagnostic
evidence, not a performance run.

`--require-webgl` requires the known pixel, not a particular hardware renderer.
Inspect Firefox's support report for hardware WebRender and content sandbox
level; its renderer name may be privacy-masked. For Chromium, inspect hardware
composition separately from ANGLE/Zink WebGL. The
[GBM discovery node](#gbm-discovery) enables the supported composition path
without a separate browser build or sandbox-bypass arguments.

Retain application seccomp/NoNewPrivs and normal sandbox preferences. Stock
snapd detects absent AppArmor and reports partial confinement; the recipe does
not force AppArmor off. Passing these checks is not kernel-equivalent isolation.

### Executable Private Memory

`test_jit_memory.c` covers the native policy matrix, private-memory preparation,
replacement transactions and automatic mprotect repair. Build the native
`jit_memory` and `task_quiesce` CMake fixtures. The matrix takes one writable
directory; `--automatic` and `--automatic-threads` require a Shroot launch.
`test_resources.py BUILD --case private-memory` checks memory ownership and runs
the backing-lifetime regression: independent opens, 32 exec images and 256
mappings share one file source, with fork, failed mmap, status flags and final
unmap checked against exact FD counts. It runs the JIT policy fixture natively
and in an isolated Shroot store. It asserts supervisor
proc/backing capture, independent protected-child memory, FD exhaustion cleanup,
and noexec preservation after optional provenance loss. Worker-pause checks cover
acknowledgement, queued work, independent workers, cancellation and shutdown
without allocating another transport or retaining database locks. Compare actual UID 2000
execution with execution inside the guest; a virtual guest UID is not the host
executor identity.

The matrix compares anonymous, private/shared memfd and regular-file mappings.
It detects why unconditional anonymous/shared/backed-snapshot substitutions
violate file reload or fork semantics. A separate single-threaded private-view
prototype executes generated ARM64/x86-64 code, retains the original description,
checks W^X, fork isolation, kernel IO, backing reload, relocation and failed
preparation. Its explicit reload operation models logical `MADV_DONTNEED`;
it does not transparently intercept arbitrary application syscalls. Native
private-COW execution denial is reported independently of those assertions.

The file-backed preparation checks use `native/shroot/src/private_memory.c`
directly, without interposition. They copy only private COW pages into a clean
executable view, retaining native DONTNEED and clean-page file coherence. The
fixture also covers repeated compilation, nonzero offsets, partial and PROT_NONE
views, stale provenance and retained proc access after PR_SET_DUMPABLE=0.
Fresh-candidate soft-dirty admission is checked through pagemap with smaps
deliberately unavailable, for ordinary and nondumpable callers.
The 513-page fixture crosses pagemap batch boundaries with dirty and clean pages,
checking exact copy counts and continued visibility of clean-file updates.
Contiguous COW runs cross copy-buffer boundaries; incremental copying returns to
its caller after at most 256 page entries, without copying clean gaps.
`memory_replace.c` is exercised at every cancellation boundary, with failed
reserve/save/install/restore/cleanup and missing terminal observation. Native and
guest checks execute its commands, including rollback after a simulated fixed
move removes its destination before failing. Both original COW data and native
DONTNEED survive rollback; completed transactions retain no scratch mappings.
The same suite tests the automatic supervisor path with ordinary application
`mprotect`, closed input FDs, generated code, backing reload and repeated RX.
Four concurrent JIT writers run alongside namespace IO with both ordinary and
nondumpable credentials, including proc-FD closure and immediate number reuse
for memfds. Protected source capture uses the production SCM_RIGHTS
helper. `test_task_quiesce.c` checks preserved resume modes, deferred signals and
rusage, pending syscall/debugger interrupts, both birth-event orders, helper ownership and a new barrier before all
previous events are replayed. Stock SteamOS Plasma QML also has device coverage
with this runtime, without preload or a disabled JIT; compositor GPU coverage
is separate.
Barrier coverage also includes repeated 4,096-participant generations, hash
collisions and exact readiness after the final stop.
An independent process performs namespace IO during 8 MiB repairs; diagnostics
must show events serviced inside the address-space barrier. Remote process-memory
writes and inherited proc-memory descriptions check data preservation and selection
of the conservative exposed-memory barrier. Proc-memory variants cover direct
opens, `openat2` and writable reopening of O_PATH descriptors. Exposure follows
CLONE_VM ownership, not a fork copy or the next exec image. Both `vfork` and
ordinary CLONE_VM children compile code before exit/exec; their parent continues
compilation afterwards.
Worker completion admission holds one result while another completes on the same
worker, produces no repeated wake while held, and publishes exactly once on release.

### Audio Continuity

Run `fixtures/audio-continuity.py --env-pid DESKTOP_PID --target NODE --seconds 90`
inside the selected guest session. Use a desktop/client environment rather than
PipeWire's server-only configuration. The fixture records stereo 48 kHz PCM,
reports all-zero 10 ms blocks after warmup and delivery gaps over 80 ms, and
retains monotonic observation times. `test_audio_continuity.py` checks fragmented
input, warmup, trailing bytes and quiet but nonzero samples.
For one continuous idle/motion/idle recording, replace `--seconds` with
`--phase idle-before:30 --phase motion:30 --phase idle-after:30`. Phase events
report both the intended PCM-frame boundary and its actual observation time;
the external input driver records when it starts and stops. No recorder is
reconnected between phases. Tests cover late delivery across phase boundaries.

Use a continuously sounding source. Zero spans during intentional silence are
not underruns; zero graph/xrun counters are not evidence of continuity. For a
game workload, verify pointer capture, then repeat identical relative movement.
Correlate source/sink PCM with the audio threads' scheduling, without tracing
every supervisor syscall during the measurement. Recording ends after receiving
the requested frames and sending SIGINT: pw-cat can return 1 for this intentional
stop because it reports success only for drained playback. Unexpected early EOF,
missing frames, stderr and other termination codes remain failures. See
[pw-cat](https://github.com/PipeWire/pipewire/blob/master/src/tools/pw-cat.c).

`fixtures/jit_workload.qml` measures repeated function compilation through stock
Qt QML, checking the numerical result without a machine-specific speed threshold.
Run `QT_FORCE_STDERR_LOGGING=1 QT_QPA_PLATFORM=offscreen qml --apptype core
jit_workload.qml` with JIT enabled. Alternate runtime builds against the same
userspace and compare multiple samples; this is not a compositor frame-rate test.
The automatic JIT fixture also holds a `CLONE_VM|CLONE_VFORK` child at a pipe
rendezvous while another thread compiles, then checks both child exit and exec
and subsequent parent compilation. Readiness and release use pipe events; the
alarm bounds a deadlocked writer barrier.
`test_debugger_runtime.py --case gdb-jit --case strace-jit` repeats private-file
compilation under a guest debugger. GDB observes all four generated-code versions
without helper threads; strace sees successful application mprotect calls without
the internal replacement syscalls or helper births. The same harness covers
ordinary and multithreaded attach, detach and shared-memory helpers.
`test_resources.py BUILD --case fd-handoff` also checks expired queued ADDFD
admission, notification restart before queue readiness and retained-carrier
cleanup with ordinary/protected receivers and both CLOEXEC modes.

### Steam Client

`test_steamos_install.py` exercises the complete current `install_linux.sh`
against Valve's official recovery archive, retaining the named test environment
`steamos-recipe-check`. It uses the installed APK's CLI, verified download cache,
the recipe's package update and supplied Steam bootstrap. The default test
selects Plasma with software rendering, avoiding a Mesa/KWin build. Use
`--gpu auto|turnip` for the separate hardware-build workflow, or `--resume` to
continue setup in the retained environment. The report records the exact
installer hash and store path.

`test_steamos_runtime.py BUILD STORE [--archive HOST_TAR]` checks that store with
the selected native runtime build: guest identity, SysV IPC, FIFO,
user/PID/mount namespaces and HTTPS as `steamos` (`--user` overrides it).
The optional archive imports a prepared rootfs; archive ownership is retained;
the executor must be UID 2000. It does not boot SteamOS or alter Android partitions.
`--socket-owner` checks stock `lsof` discovery for a loopback listener and an
established connection between parent and child. Queries by the client's port
must retain both owner PIDs, guest UIDs and parent PIDs; command durations are
reported separately. Neither `lsof` output nor Steam's peer verification is
replaced. Small-fixture success does not establish discovery latency in a
large graphical process tree.

`test_steamos_graphics.py BUILD STORE --client steam|glx|vulkan|gles` uses ordinary
shell graphics sessions and private virtual displays. The store must have the
installer's Steam bootstrap, GUI dependencies and selected graphics profile;
the fixture calls `/usr/bin/steam` as `steamos` with `/home/steamos`. GPU probes
use that same installed profile rather than injecting laboratory driver paths.
The default `--renderer turnip` requires a hardware profile and rejects software
rendering. `--renderer lavapipe` requires the installer's SteamOS software profile:
Vulkan must identify Mesa llvmpipe, and GLX/GLES must identify Zink over llvmpipe.
It does not substitute renderer environment variables for the installed profile.
The fixture compiles the production Java graphical recipe; the build
directory also supplies the `md-await-exit` completion fixture.
`--timeout` bounds the entire launch, including Steam's own updates;
no sandbox-disable flags are supplied. `--window-title` requires an exact native
window title so an updater cannot stand in for the login UI. Bounded pixel
observation rejects a blank frame and Steam's small initial loading indicator;
visually inspect the captured form. GPU probes capture changing frames.
Verbose native tracing is opt-in with `--diagnostics`, rather than part of the
normal graphical workload. Use `test_installer_setup.py BUILD STORE --gui keep
--gpu software` to execute the actual graphics payload before these checks;
the report records its hash. The SteamOS recipe probes Vulkan/EGL before publishing
the profile, while these separate window tests verify presentation too.
On the ARM64 0.3.0 recovery image, installed Lavapipe 24.0.6 with LLVM 17 and
the image's Zink 26.3 passes Vulkan/Wayland, GLES/Wayland and GLX/X11 changing-frame
checks. GLX/X11 also passes with `--require-shm`, which rejects the `noshm`
bypass; the embedded server attaches guest SysV segments through Shroot's scoped
resource channel. `test_x11_shm.py BUILD STORE` separately verifies real pixel
readback, private IPC rejection, permissions, `IPC_RMID` and shared-pixmap
lifetime. It also covers modern AttachFd/CreateSegment with resource admission
and ordinary X11 streams, swapped-client replay and abrupt connection loss,
without Desktop or a viewer. Both fixtures accept `--installed` to exercise
the APK's shell launcher and native-helper staging instead of uploading runtime
binaries. This does not certify the full Plasma session: its separate
recipe checks still fail X11 desktop-component startup and Wayland PolicyKit
session identification. Do not discard those assertions to report a GUI pass.
Steam mapping/capture is reported separately and is not a successful login,
game execution or complete SteamOS session. Cleanup cancels the owned runtime
and observes its exit before releasing the graphics session and display.

`test_debugger_runtime.py OUTPUT --instance STORE --build BUILD --case clone-untraced`
checks copied/shared file tables, zero/SIGCHLD exit signals, guest filesystem
access and guest-debugger non-inheritance for `CLONE_UNTRACED` children.

### Concurrent Desktop Responsiveness

Use the installed SteamOS launcher, its existing Plasma session and ordinary
guest user. Launch Flatpak Chromium and the installed Steam game through that
session, not separate supervisors. A game menu is insufficient: enter an actual
level, keep the viewer visible and verify changing frames. Compare keyboard
input and Start submenus both after launch and after their first use. Record
CPU-frequency residency, thermal limits and battery temperature alongside each
run. X11 pixel readback measures server-visible composition, not physical
display latency; Android MCP injection additionally includes RPC overhead.

The 2026-10-25 installed-build check used a 1920x1080 virtual output, Plasma X11,
Flatpak Chromium and DDNet through Steam/FEX with a local tutorial server.
Chromium retained its Flatpak/renderer seccomp sandbox and Zink/Turnip hardware
composition; this does not certify GPU-process sandboxing. At a reported
battery temperature of 41 C and CPU policy ceilings of 1.7856/1.4976 GHz,
repeated submenu changes took 37-56 ms. The earlier post-launch pass took
183-656 ms. Android-injected omnibox keys took 148-157 ms after a 351 ms first
sample; native XTest input took 114-163 ms after a 274 ms first sample. These
are workload observations, not native comparisons or a universal latency bound.
Post-launch outliers and general-wait kernel scanning remain performance work.

Creating and closing 160 disposable Chromium tabs with the game running left
the same supervisor, Plasma, browser and game alive. A bounded 180-second
SIGKILL trace recorded no signal addressed to those processes and no dropped
trace events. Keep this separate from the deterministic birth/exit, signal-mask,
descriptor-publication and fairness fixtures: a stress pass alone cannot prove
that all process-lifetime races are fixed. The relevant ownership contracts are
in [interception](../shroot/interception.md).

## Libc, IPC And Desktop Checks

`test_argument_vector` covers inline/growing argument storage, readable-page
boundaries, bad pointers, allocation bounds and cleanup. `test_image_arguments`
covers long OCI command arrays and typed/bounded JSON decoding. Both are CMake
fixtures. `test_arguments.c`, built for glibc and musl, covers execveat, shebang,
spawn, self/foreign proc snapshots, long strings, failed-exec resource lifetime,
kernel E2BIG/EFAULT and stack limits. It includes a 650000-empty-argument launch
with an enlarged soft stack limit (requiring a sufficient host hard limit).

```sh
python native/guest-exec-lab/test_arguments.py --store DISPOSABLE_HOST_STORE \
  --fixtures LIBC_FIXTURE_DIRECTORY [--build NATIVE_BUILD_DIRECTORY]
```

The device workflow also checks initial/OCI launch vectors and coreutils cp/rm
with 4096 operands, matching mkinitcpio's compressed-file move. That part needs
bash and coreutils; it is not a complete kernel-image maintenance test. Omitting
`--build` selects the installed runtime. `test_abi.py` includes the libc argument
fixture without requiring those package tools.

`build-libc-fixtures.sh glibc|musl SYSROOT OUTPUT` builds the same execution,
thread/signal and IPC tests against the selected libc, without libc-specific
runtime branches. The musl fixture uses the official Alpine 3.23 ARM64 minirootfs
and its matching `musl-dev` headers/CRT. Prepare the distribution's ordinary
dependencies and toolkit caches before importing the tree; the ELF loader does
not install packages or create accounts. `fixtures/md-prepare-alpine` configures
only disposable GUI-test data for the real shell identity. Fixtures require the
current runtime's store schema; incompatible stores are rejected without modification.

`test_ipc.c local NEW_DIRECTORY` checks namespace socket names, permissions,
SCM_RIGHTS, unlink/rebind, rename and stale listeners. `paths` checks `/dev/shm`
through absolute paths, dirfd and a host-directory cwd. The separate static
Bionic `md-ipc-launch RUNNER STORE [OTHER_STORE]` starts independent supervisors
and filesystem services, checks shared memory, file-data inotify and unlinked
lifetimes, and optionally verifies name isolation from another store.
These are not directory-inotify or abstract-socket isolation tests.

`build-static-fixtures.sh glibc|musl SYSROOT OUTPUT` additionally builds static
ET_EXEC and PIE programs at 64 KiB and 2 MiB alignment. The glibc sysroot requires
its matching `libc6-dev` and GCC 14 static support libraries; musl requires its
matching development archive and compiler builtins. `build.sh` also builds a
freestanding raw-SVC ELF without libc or mapped program headers.

```sh
python native/guest-exec-lab/test_abi.py BUILD \
  --libc glibc --libc-build BUILD --static-build STATIC_BUILD \
  --store DISPOSABLE_HOST_STORE --runtime IMMUTABLE_STAGED_HELPERS
```

Repeat with the musl outputs and Alpine store. These checks include descriptor
and dirfd exec, open-unlinked/name-reused executable identity, bounded shebang
parsing, 64 successive execs, thread/signal stacks, vfork, spawn file actions,
process-image snapshots, Unix sendmmsg partial batches and SCM_RIGHTS.
They install fixture programs only into the explicitly selected disposable store.

## Distribution Packages And Applications

`fixtures/md-import-debian-rootless` prepares a copied official Debian rootfs
for the real shell UID before import. It does not fabricate package records.
`md-package-debian`, `md-package-alpine` and `md-package-upgrade` exercise real
signed repositories, dependencies, scripts, HTTPS, removal and updates in
disposable stores. Preserve the official image hash and package-manager logs.
The Debian ucf preparation explicitly enables real configuration-file updates
in that fixture; the normal non-root dry run is not a configuration pass.
These scripts are not a product distribution installer or a container manager.

```sh
python native/guest-exec-lab/test_distribution_app.py BUILD \
  --store DISPOSABLE_HOST_STORE --runtime IMMUTABLE_STAGED_HELPERS \
  --keyboard-directory HOST_XKB --protocol wayland --application writer
```

The prepared store supplies its normal packages and caches. `writer` and `calc`
edit/save ODF documents, check clipboard text, close normally and read saved XML
in a new guest process. Both pass on Wayland; Writer also passes on X11 with
`--x11-manager`. Calc's X11 insertion check remains failing, and Writer startup
without the manager remains unverified. The fixture retains these failures,
including the expected cell contents, rather than weakening its assertions.
`gimp` checks a dialog and closure. `blender` uses its
viewport fixture; the tested Debian build requires `--protocol x11 --x11-manager`.
`gtkgl` checks actual colored pixels and an input-driven frame; `--software`
selects the llvmpipe control. `--gpu-prefix` selects an explicitly staged test
driver directory. Reports retain build identity, output, screenshots, process
status and cleanup. A trace/event acknowledgement is required where a client
publishes readiness; a screenshot or delay is not a readiness barrier.

Browser acceptance uses the [production browser fixtures](browser/README.md),
including stock Chromium JavaScript/PNG output without sandbox-disabling flags.
No completed workload establishes full isolation. `md-notification-test` is
a separate USER_NOTIF/remote-memory/FD-injection feasibility probe, including
guest signals and longjmp. It is not an alternative installed runtime.
`md-notification-lifecycle-test` adds native mask/altstack/TRAP/ERRNO/TSYNC,
thread/fork/exec, listener lifetime and cancelled-request checks without ptrace.
It deliberately asserts the shell's protected-process denials as boundary
checks, not successful access: pre-opened memory is not a capability for a fork
child or replacement image. ADDFD and explicit SCM_RIGHTS still work after
dumpable is disabled. The fixture never enables it again. Execute these native
controls separately from the guest runtime:

```sh
python native/guest-exec-lab/test_notifications.py BUILD
```

The runner checks the selected UID, stages fresh binaries, retains hashes,
kernel/build identity and exact results, and closes its console. It does not
install an APK or start Desktop. `test_process_context.c` separately checks
independent command-line/auxv snapshots and identical socket names routed by
different explicit contexts, without a process-global route fallback.

The separate [sandbox boundary fixtures](sandbox-research.md) compare native
seccomp precedence, cooperative signals and direct filesystem RPC with the
current guest adapter. They deliberately reproduce policy failures: a completed
run is not successful browser sandboxing. Build and execute them independently:

```sh
sh native/guest-exec-lab/build-sandbox-fixtures.sh BUILD
python native/guest-exec-lab/test_sandbox.py BUILD \
  --runtime IMMUTABLE_STAGED_HELPERS --store DISPOSABLE_GLIBC_STORE
```

`test_hybrid.py` separately exercises selective TRACE plus USER_NOTIF, protected
processes, register-only exec, guest-side bounded memory copying and fresh
same-site notification replay. It traces only its native fixture children and
does not alter the installed adapter. The same research note records positive
controls and unresolved security/lifecycle requirements.

```sh
sh native/guest-exec-lab/build-hybrid-fixture.sh BUILD
python native/guest-exec-lab/test_hybrid.py BUILD
```

The [browser fixtures](browser/README.md) build the production runtime, including
its supervisor, and distinguish startup from a rendered artifact. The separately
labelled renderer-only experiment uses an unsandboxed parent/zygote; it cannot
replace the stock-browser check. Filesystem broker authorization and full
isolation remain separate from application compatibility.
The [native isolation fixture](chromium-isolation.md#native-kernel-baseline) tests
namespace, chroot, credential and Landlock availability without loading a guest
or enabling ptrace/seccomp mediation. Unsupported capabilities are observations,
not passing browser tests.

The [external domain fixture](chromium-isolation.md#external-domain-fixture)
checks read-only file admission for two independent roots, default-deny syscall
confinement, inherited-FD leakage, application seccomp precedence and fail-closed
memory/listener errors. It uses no ptrace and is not virtual chroot or browser
support:

```sh
sh native/guest-exec-lab/build-domain-fixture.sh BUILD
python native/guest-exec-lab/test_domain.py BUILD --repeat 10
```

The [helper/context control](chromium-isolation.md#helper-and-protected-memory-control)
combines shared root/cwd state with protected-memory copying and original-site
kernel replay. It compiles the matching Chromium helper source without edits;
the optional full-main mode executes the stock entry point and a native fixture
client, not the browser. Source hashes and license are retained under BUILD.
The explicit namespace fallback, fixed-image exec scope and remaining set-ID/
guest-ELF gates are documented with the results:

```sh
python native/guest-exec-lab/build-suid-context.py BUILD
python native/guest-exec-lab/test_domain.py BUILD --fixture suid-context --repeat 10
python native/guest-exec-lab/test_domain.py BUILD --fixture suid-context --suid-main --repeat 10
```

`md-exec-policy-test INTERPRETER` compares executable memfd scripts under the
native shell and guest. X_OK succeeding does not override a kernel exec denial.

For a prepared GUI store:

```sh
python native/guest-exec-lab/test_userspace.py BUILD \
  --store HOST_STORE --runtime STAGED_HELPERS --recipes BUILD/recipe-classes \
  --keyboard-directory HOST_XKB --protocol wayland --installed
```

Run again with `--protocol x11`. `--installed` selects the APK's lazy
`shroot` command; omitting it selects immutable staged test binaries.
`STAGED_HELPERS` also supplies the exact-process exit watcher. Checks require
real pixels, keyboard editing, saved-file readback and process exit, not only a
mapped native window. Host/user HOME and Desktop ownership must remain unchanged.

The whole-desktop fixture uses the same connection and execution contracts:

```sh
node native/guest-exec-lab/prepare.mjs build/guest-desktop --desktop --suite trixie
sh native/guest-exec-lab/build.sh build/guest-desktop
python native/guest-exec-lab/test_desktop.py BUILD \
  --store HOST_STORE --runtime STAGED_HELPERS --keyboard-directory HOST_XKB --installed
```

Import and prepare the generated rootfs explicitly before running. Xfce readiness
has separate X11 WM/panel/desktop and D-Bus Idle acknowledgements.
`test_x11_desktop.c` checks WM activation/resize; `test_xfce_session.c` subscribes
before querying state. No sleep substitutes for readiness. Xfce uses an isolated
configuration, software GL and a retained root viewer; logout must return zero.
The fixture does not boot systemd, logind or a complete GNOME session.

## Linux GPU Fixture

`build-mesa.py SOURCE SYSROOT OUTPUT` builds an unmodified Linux Mesa Turnip ICD
with KGSL and DRM WSI, not an Android driver. The sysroot needs matching C/C++,
libdrm, Vulkan, Wayland, X11/XCB, xshmfence, expat, zstd, zlib and libelf development
packages. Supply them with repeated `prepare.mjs --package NAME`; signatures and
hashes are checked as for the base profile. Host Meson, Ninja, Python generators
and a `wayland-scanner` matching the target libwayland are build tools only.
Use a fresh output directory when changing Mesa/configuration.

Zink additionally needs `libwayland-egl-backend-dev`, `libxcb-glx0-dev` and
`libxxf86vm-dev` in the authenticated SDK. Build it with `--driver zink`.
`--wayland-zink` explicitly applies the repository's Mesa 26.2.3 non-DRM
Wayland and KGSL timestamp patch exported by `scripts/install_linux.sh --print-mesa-patch` to a
private source copy and records its hash; the original source
tree remains unchanged. Keep patched and unmodified prefixes separate, and run
the unmodified control. A patched fixture result is not upstream compatibility.

Install `libvulkan_freedreno.so` at `/opt/md-gpu/lib/` and
`fixtures/turnip.json` at `/opt/md-gpu/turnip.json` in the owned test store;
its distribution supplies `vulkan-tools`. Do not replace Android libraries.
`test_gpu.py BUILD --store HOST_STORE --runtime STAGED_HELPERS
--keyboard-directory HOST_XKB --protocol wayland --installed` checks the hardware
ICD, changing Android pixels, 600 frames and zero exit. X11 is a separate
`--protocol x11` run with Xfce's window manager: the unmodified XCB cube does not
publish an ICCCM name/class, and the manager supplies its `WM_STATE`.
Use `--display ID` for a connected HDMI output instead of creating a virtual
display; the fixture never removes a borrowed output. `--icd`, `--home` and
`--user` select the installed guest driver and account, including Nix store
paths. The preflight and rendered client both load the guest login environment.
A software compositor or successful `vulkaninfo` alone
does not establish guest GPU rendering. `--trace` accepts the optional static
`md-trace-fault` observer. The production supervisor already owns ptrace for its
guest tree; a second observer cannot attach concurrently.

`test_gpu_timestamps.c` checks calibrated CPU/device clocks against actual queue
timestamp queries. Build it inside a prepared guest with `cc -O2 -Wall -Wextra
-Werror test_gpu_timestamps.c -lvulkan -o test_gpu_timestamps`. With the private
Turnip ICD selected, run without arguments for EXT and with `khr` for KHR.
Both require 16 successful GPU submissions and calibration brackets. Run the
unmodified driver as a negative control, not as an expected success.

`test_glx_loader.c`, built with `cc -O2 -Wall -Wextra -Werror test_glx_loader.c
-lGL -lX11 -o test_glx_loader`, checks advertised swap-control callbacks, GPU
readback and exact X11 pixels after a swap. Run through the graphical socket
route with the installer's private Mesa selected and `GALLIUM_DRIVER` unset:
`LIBGL_KOPPER_DISABLE=false ./test_glx_loader 1` exercises Zink/Kopper;
`LIBGL_KOPPER_DISABLE=true ./test_glx_loader 0` exercises its software alternative.
Also run with explicit software rendering. A bounded pixel probe permits
asynchronous Vulkan presentation; expiry fails, and each invocation needs an
outer process timeout for driver hangs. With unmodified Mesa, explicitly selected
Zink plus disabled Kopper can advertise a missing callback and crash the query.
Forcing `GALLIUM_DRIVER=zink` into the software loader is not a working fallback:
GPU readback can succeed while the X11 window remains black.

Build `fixtures/kgsl_no_calibration.c` with `cc -shared -fPIC -Wall -Wextra
-Werror kgsl_no_calibration.c -ldl -o kgsl_no_calibration.so`. Its test-only
`LD_PRELOAD` denies the calibrated timestamp ioctl with `ENOTTY`; run the patched
driver's timestamp fixture with `disabled`. Both extensions must be absent and
ordinary GPU timestamp queries must still succeed. This simulates an unavailable
ioctl, not complete coverage of an older kernel.

`test_egl_dmabuf.c`, linked with `-lEGL -lGLESv2 -lwayland-client`, connects to a
retained Wayland session through the production guest socket route. It requires
the matching EGL/GLES, Wayland, libdrm and Linux development headers. It checks
no-config/surfaceless EGL, linear ARGB8888 DMA-BUF import, exact input pixels and
64 alternating GPU frames written back to the same allocation. Each frame uses
an EGL native fence plus DMA-BUF sync-file export/import; exact CPU pixels are
checked after a bounded fence wait, without `glFinish`. The DMA heap is opened read-only; the
allocated buffer FD is read/write. Run with an outer process timeout to bound a
driver hang. This fixture verifies backend building blocks, not a nested KWin
desktop or frame-presentation timing.

### GBM Discovery

The Turnip recipe writes `/etc/shroot/render-node` only after its GPU probe
succeeds; software selection removes it. `fs_render.c` admits the optional
typed device once per launch after real KGSL/DMA-heap/fence capability checks.
`render_sysfs.c` projects its matching identity. Existing DRM nodes are retained.
The device implements DRM_VERSION through bounded nested-pointer ioctl
marshalling shared with other typed controls. It does not claim unrelated DRM
operations. No preload, extra browser flags or separate Mesa build is installed.

Run `python native/guest-exec-lab/test_render.py BUILD STORE` from the repository
root with a prepared Turnip test environment.
The unit/guest fixtures cover capability admission, disabled profiles, device
and sysfs identity, FD duplication/transfer, namespaces, invalid ioctl buffers
and unsupported operations. On NX809J, UID 2000, kernel 6.12.23, the admitted
node is renderD129 alongside Android's renderD128. The following EGL fixture
also passes all 64 frames on `/dev/dri/renderD129`. Ordinary Ubuntu Snap Chromium
155.0.8059.79 reports hardware Canvas/Compositing/Rasterization/WebGL/WebGPU and
zero GPU crashes. These observations do not establish hardware video decode
or kernel-equivalent sandbox isolation.
After the common recursive-bind target fix, two cold Chromium namespaces took
22.6 and 22.2 seconds with VS Code running alongside, within Snap's unchanged
30-second helper limit. Before that fix, concurrent GUI launches reproduced
the helper timeout. These are device observations, not an arbitrary-load timing
guarantee; repeated cold setup remains part of the acceptance checks.

#### VS Code / Electron

Include the distribution's installed VS Code in the same Wayland/Turnip checks
as the browsers. Run as the ordinary desktop user through the recipe's login
environment, with no preload, GPU override or sandbox-disabling arguments:

```sh
code --new-window
code --status
```

Verify the actual editor, not just CLI exit status:

- Enter text in a new document, save through the native file chooser and read
  the resulting file independently.
- Modify that saved file from another process; the editor must display the
  external change without reopening it.
- Open the integrated terminal, run a command and inspect its output.
- Record `code --status`: `gpu_compositing` must be enabled and the renderer
  must identify Zink/Turnip, not software rendering.
- Inspect a renderer's `/proc/PID/status` for `NoNewPrivs: 1` and `Seccomp: 2`.
  These verify installed restrictions, not complete kernel-equivalent isolation.
- Close and relaunch normally; reject renderer/GPU crashes even if a recovered
  window looks correct. Keep GDB/extension testing as a separate workflow.

Current stock-library application acceptance and exact receipts are in the
[distribution matrix](validation-matrix.md) and its
[evidence ledger](distribution-evidence.md).
The automated `vscode-gui.py` fixture enables an ephemeral DevTools inspection
port to identify renderers through `SystemInfo.getProcessInfo`, then independently
checks their proc credentials and filters. A Flatpak portal can parent those
renderers outside the CLI's process tree. This inspection option belongs only
to the lab fixture, not the recipe or application launcher.
Install `ms-vscode.cpptools` with the same `code` launcher supplied to the fixture
and confirm it with `code --list-extensions`. Package wrappers may select their
own extension directory; populating `~/.vscode/extensions` directly does not
establish that the selected editor can load it. The fixture checks this
prerequisite before opening an editor.

Flatpak 1.16.6 and 1.18.4 pass the portal's instance-notification pipe outside the child
FD-remapping plan; a requested child FD can overwrite that pipe. The lab-only
[`flatpak-portal-instance-fd.patch`](fixtures/flatpak-portal-instance-fd.patch)
uses Flatpak's existing remapper for that descriptor. A receipt using this
portal must say so and cannot certify the unmodified distribution package.
Do not build or replace Flatpak in the user installer to hide this prerequisite.
Code 1.142.0's CLI process-memory table is not an acceptance metric: its bundled
`mainImpl.js` converts `%MEM` to bytes in `Xp` and applies the percentage
conversion again in `formatProcessItem`. `/proc`, `ps` and `free` remain the
independent memory checks; do not compensate for that application conversion
inside Shroot.

Hardware acceptance covers compositor and browser separately: working WebGL in
an ordinary client does not prove that a protected KWin process selected Turnip.
Use the recipe's system driver discovery for protected processes and the
[GPU publishers](../../docs/graphics.md#flatpak-gpu-drivers) for sandbox runtimes.
The common GBM build resolves its backend through `$ORIGIN/gbm`.
Keep renderer sandbox, GPU sandbox, composition and video decoding as distinct
checks; none substitutes for the others.

Debugger/collector lifecycle uses the shared
[debugger ownership contract](../shroot/debugging.md#waits-and-process-identity).
The graphical wrapper detaches its owned mounts before removing the runtime
directory, verified by the `session` workflow above. Process-tree cleanup is
separate: compositor exit alone does not prove that every Plasma background
service has exited.

`test_egl_gbm.c` checks the installed Mesa's GBM allocation, EGL import, native
GPU fences and 64 exact alternating frames. It accepts a node path; on the
tested KGSL system even `/dev/dma_heap/system` suffices for the existing
DRI/Zink fallback. No additional Mesa patch is needed for this experiment.
Use the recipe's graphics environment and compile inside the guest:

```sh
. /etc/profile.d/magicdesk-graphics.sh
p=$(cat /etc/magicdesk/mesa-prefix)
cc -O2 -Wall -Wextra -Werror -I"$p/include" -I/usr/include/libdrm \
    test_egl_gbm.c -L"$p/lib" -Wl,-rpath,"$p/lib" \
    -lgbm -lEGL -lGLESv2 -o gbm-probe
timeout 60 ./gbm-probe /dev/dma_heap/system
```

`fixtures/gbm_discovery_probe.c` isolates device discovery for an unchanged
browser. It redirects one render-node identity and its minimal sysfs tree to
the real DMA heap FD, and answers only DRM_VERSION; other DRM ioctls fail.
GBM allocation, buffer export, GPU execution and synchronization remain real.
The interposer observes GBM calls without replacing their results. Interposing
only libdrm entry points is insufficient: the tested Chromium links libdrm
statically, while Mesa uses its shared copy.

For a disposable Ubuntu Snap account with the prepared GPU content provider,
compile the interposer with `-shared -fPIC -Wall -Wextra -Werror`, the same
include paths and `-ldl`, to
`$HOME/snap/chromium/common/libgbm-discovery-probe.so`. Run
`sh fixtures/gbm_probe_setup.sh "$HOME/snap/chromium/common/gbm-probe-root"`.
Only if `~/.chromium-browser.init` does not exist, copy
`fixtures/gbm_probe_init.sh` there. Launch ordinary `snap run chromium`, then
visit `chrome://gpu` and the copied `fixtures/gbm_probe.html`.
No added Chromium flags, rebuilt browser, changed sandbox setting or global
`ld.so.preload` is involved. Close the browser normally; remove the temporary
init file before the control launch, then remove the probe library/tree/page.
Never install this interposer in recipes or shared login settings.

On NX809J, UID 2000, kernel 6.12.23, Ubuntu's stock Chromium Snap
155.0.8059.79 (3560) with recipe Mesa 26.2.3, the probe enables **hardware
composition**, not just WebGL. GBM logs record real allocation/export, including
window-sized buffers. The page completes 600 frames with zero pixel errors in
windowed and fullscreen modes; captured output also has exact green/blue pixels.
The GPU report records Adreno 840 through ANGLE/Zink/Turnip and zero GPU crashes.
Both baseline and prototype report GPU `Sandboxed: false`; observed inherited
seccomp filters must not be presented as proof of Chromium's GPU sandbox.

The interposer is retained only as the original diagnostic experiment, not as
the installed implementation. Neither it nor the production checks prove
general DRM support, strict confinement, performance or another GPU/distribution.
Zink can log an unsuccessful DRM-device match before its working fallback.
The fence fixture requires the post-5.15 sync-file bridge recorded in
[kernel compatibility](../shroot/kernel-compatibility.md#newer-interfaces).

### Allocator Rejection And Desktop Presentation

`fixtures/dma_heap_unavailable.c` is a test-only `LD_PRELOAD` control for the
nested KWin allocator. Build it with `cc -shared -fPIC -Wall -Wextra -Werror
dma_heap_unavailable.c -ldl -o dma_heap_unavailable.so`. It rejects opening
`/dev/dma_heap/system` without blocking ordinary file access. Forced OpenGL must
fail explicitly; a separately selected QPainter session must still render.
Do not install this control in a guest login profile.

For the nested Plasma check, build the exact `--print-kwin-patch` from the
installer against its pinned archive in a private prefix. Use the installer's
`magicdesk-desktop-session plasma-wayland` launcher under an ordinary guest account and the
production graphical socket route. On an owned virtual display, verify actual
desktop pixels, Dolphin, launcher input, Overview and resize. KWin's D-Bus
`supportInformation` must report OpenGL and the intended GPU; the session bus
must be accessed as the same guest user. An inner EGL client must advertise the
same hardware renderer, submit v3 DMA-BUFs (`WAYLAND_DEBUG=client`) and produce
different rendered frames. Scope traces and timeouts to test processes; a
timeout is not a successful startup or shutdown.

`test_userspace.py --environment NAME=VALUE` records explicit guest-client
driver controls; `--software` selects llvmpipe. For an isolated Turnip prefix,
use `--environment VK_DRIVER_FILES=/opt/md-gpu/turnip.json`. Installing that
manifest in the test store's `/usr/share/vulkan/icd.d` exercises ordinary loader
discovery without a driver override. These controls do not change Android or
APK drivers.

`test_installer.py --distro ID --name TEST_NAME --gui MODE` exercises the public
installer through MagicDesk's shell executor. It checks package setup, locale,
timezone, an ordinary guest account and settings persistence across resume.
GUI checks verify shared launcher syntax, PipeWire tools and SDL/libudev libraries.
The named test environment is retained. `--resume` continues that fixture.
`--library PATH` isolates its stores and cached images from other test environments.
Use separate libraries for concurrent imports; publication locks a library.
`--ipv4` selects XBPS's `FORCE_IPV4` setting on networks without usable IPv6.
The test uses explicit guest DNS, not Android's encrypted resolver.

`test_installer_audio.py --names TEST_NAME ... [--library PATH]` starts the
installed audio wrapper, checks its private default sink and volume/mute changes
during silent playback, then releases its processes. It does not change Android
volume. Stream activation and property changes are observed through Pulse events.
`--user ACCOUNT` selects an existing guest account (default `mdtester`); the
fixture loads its login profile before locating distribution tools.

`test_installer_graphics.py --names TEST_NAME ...` launches the installer's
catalog entries on an owned virtual display without Desktop. It records actual
native windows, Android hosts, screenshots and launch failures, then releases
its sessions and display. `--entry TEXT` filters desktop-file names. An optional
`--lease ID` retains an existing phone awake lease; otherwise the test owns a
virtual-display lease. `--display ID` borrows a connected, awake non-phone output
(for example HDMI) instead; it never removes that display, verifies every host
lands there and uses the phone awake lease. Package installation alone is not a
passed graphics test.

To check the Debian installer's actual login profile, use
`test_distribution_app.py BUILD --store HOST_STORE --runtime STAGED_HELPERS
--keyboard-directory guest:HOST_STORE --user root --home /root
--protocol wayland --application gtkgl --installed-profile turnip`.
Repeat with X11, Blender on X11, and `--installed-profile software` after switching the
installer profile. This mode injects no driver overrides and asserts the actual
renderer. GTK checks four color regions, an input-driven frame and zero process
exit. The test holds its virtual display awake; release any separately held MCP
awake lease before the run. `--user` selects a guest account, not Android root.

The Debian Mesa 25.0.7 fixture can block in GTK's initial GLX probe when there
is no discoverable Vulkan ICD. Its [Zink initialization](https://sources.debian.org/src/mesa/25.0.7-2%2Bdeb13u1/src/gallium/drivers/zink/zink_screen.c/)
jumps to `zink_destroy_screen` after a failed `zink_create_instance` while
holding `instance_lock`; destruction locks it again. The native stack waits in
that mutex, and `VK_LOADER_DEBUG=error,driver` reports no drivers. Supplying the
prepared Turnip manifest passes the same workflow. Keep this distribution
failure distinct from the guest syscall mechanism or Android frame transport.

Exact successful device coverage and unsupported ABI surfaces belong in
[Guest runtime](../../docs/shroot.md#coverage-and-limits), not a general
claim of distribution or driver compatibility.
