# Shroot Distribution Validation Matrix

This is the per-distribution acceptance ledger, not a supported-applications list
or a claim that all tests are automated. Keep every distribution printed by
`sh scripts/install_linux.sh --list` in every table. Results do not transfer
between distributions sharing a package manager, libc or init system.

Device evidence uses NX809J, Android 16, Linux 6.12.23 and 4 KiB pages.
The current release pass records User and Shell separately below; other tables
use Shell unless explicitly stated. Additional App-UID acceptance is recorded
in [host execution](host-execution.md). Neither executor's results certify the
other, the minimum supported kernel 5.15, other firmware or Android root execution.

- [Current Release Qualification](#current-release-qualification)
- [Reading Results](#reading-results)
- [Linux 5.15 Capability Profile](#linux-515-capability-profile)
- [Installation And Init](#installation-and-init)
- [Containers And Virtual Machines](#containers-and-virtual-machines)
- [Packaged Browsers And Portals](#packaged-browsers-and-portals)
- [Desktop And Development](#desktop-and-development)
- [Added System Workloads](#added-system-workloads)
- [K3s](#k3s)
- [Evidence And Open Work](#evidence-and-open-work)

## Current Release Qualification

Version 2.3 is in stabilization. The source baseline is
`1514781fea469b5d3f7302d6a5217bb0dae626f6`; new features are outside this pass.
The saved non-fixture APK has SHA-256
`21e764c0e3eded3a96791985c9435411b28adae85d292e8f32d374307a26aef7`.
Artifacts for this pass live in `build/release-2.3/`. Receipts retain each test's
APK identity and native hashes. Resource, policy and full machine-entry coverage
uses the optimized fixture build
`a7bd2f65418c95082a27a1b5edf5500be5106e94acfdf227eb0520ecbb651ab1`;
channel-admission follow-up uses
`f8ec4cb283b49b7c3d9ef3cee45e70fe4887664bef3f4b468965199fb276b0fb`.
Fixture results do not replace final packaged-APK acceptance or performance
measurements without test instrumentation.

Channel-lifetime acceptance uses fixture APK build ID
`e04aab43c2f2f8d532bacd153917748a0a338b05d916c84b286fb821e7107aa1`.
Its common supervisor-state checks pass under User and Shell. Full machine-entry
coverage passes under both executors, including overflow registration, native TID reuse,
paused admission, fork/exec, mappings and stock Go with/without guest seccomp.
Receipts: `results/client-lease-state-{app,shell}.json` and
`results/client-lease-svc-{app,shell}.json`. Earlier results below retain their own build
identities; they are not silently promoted to this runtime.

Stock k3s `v1.37.1+k3s1` passes the complete one-node VXLAN scenario on Alpine
under User: CoreDNS, cross-pod service traffic, NetworkPolicy updates,
deployment scaling/rolling update, PVC, Traefik/ServiceLB ingress, server restart,
workload preservation, deletion and clean shutdown. Receipt:
`k3s-alpine-app/oci-services-bda35ccb81454ea384111df170176bde.json`.
Debian User passes the same complete scenario with fixture build ID
`854f715343a3787749a4a21dd38b83af3a5a273f0e8ae0ab73aa1ac4d4add0f6`:
`k3s-debian-app/oci-services-3283cb286e1b4603b753356d86c71e6f.json`.
The shared filesystem transport and supervisor-state suites pass under both
executors, including queued open-result retirement and cancellation. Filesystem
result coverage also passes under the Linux 5.15 capability profile for both.
Receipts: `results/fs-owner-fix-{app,shell}.json`,
`results/fs-owner-state-{app,shell}.json` and
`results/fs-owner-kernel515-{app,shell}.json`.
Current per-distribution k3s coverage is listed below; unrun executor/OS pairs
and other application workloads remain pending.

- The complete resource suite passes: 461 checks under User and 472 under Shell.
  Native controls depend on the executor's capabilities; guest assertions are
  shared. Coverage includes descriptor custody in Base/lazy entry, signal-stress
  memory operations, namespaces, IPC, storage recovery and supervisor ownership.
  All 44 shared-memory signal combinations pass under each executor, including
  guest seccomp with 100-us signals and STOP/CONT, within unchanged deadlines.
  Receipts: `results/signal-owner-resources-{app,shell}.json`.
- Guest policy and signal suites pass all 258 cases per executor in Base/lazy
  entry, including TSYNC, notification listeners and signal-mask virtualization.
  Supervisor-state checks also pass independently under both identities.
  Receipts: `signal-owner-policy-{app,shell}/` and
  `results/signal-owner-state-{app,shell}.json`.
- Full machine-entry suites pass under User and Shell: original/Base/lazy
  controls, distant sites, growing channels, debugger rearm, inherited filters,
  TSYNC, mapping changes, protected process inspection and five Go rounds with
  and without guest seccomp. Receipts: `results/signal-owner-entry-{app,shell}.json`.
- Cooperative API and shared model suites pass under User and Shell, with
  independent Base/lazy runs. Coverage includes channels, TSYNC and inherited
  filters, FD effects, signal reentry, JIT, cancellation and concurrent mutations.
  Cold channel growth/reuse also passes three repeats per executor and entry
  mode; ownership tests cover queued/ready cancellation and successor progress.
  Receipts: `results/qualified-entry-cooperative-*.json`,
  `logs/channel-queue-capacity-*.log` and `results/channel-queue-state-*.json`.
- Watch suites and filesystem-result suites under the Linux 5.15 capability
  profile pass for User and Shell. They retain selective ordinary-read handling,
  parallel-read completion, descriptor counts, watch lifetimes and quotas.
  Receipts: `results/qualified-entry-watch-*.json` and
  `results/qualified-entry-kernel515-fs-*.json`.
- Stock-libc ABI suites pass on Alpine/Shell and Debian/User in lazy and Base:
  dynamic, static and freestanding execution, exec/spawn/vfork, TLS, threads,
  signals, protected pointers and failed-exec resource lifetimes. Fixtures were
  rebuilt from the current sources. Receipts: `results/abi-*.json`.
- Packaged debugger suites pass on Alpine/Shell and Debian/User in lazy and Base:
  GDB, LLDB, gdbserver, strace, Callgrind and the shared debugger lifecycle
  assertions. Alpine lacks the toolchain's `gcrt1.o`; User cannot open native
  perf events. These two explicit unavailable controls are not passes.
  Receipts: `debugger-alpine-shell/`, `debugger-debian-app-complete/` and
  `results/debugger-base-*.json`. Broader kernel-profile qualification remains
  pending.
  Current debugger-delivery acceptance uses fixture build ID
  `d2b1187a3a9ed0335bd9edc865f17583264f4553b5d1034a1d2230b0042d5440`:
  Debian/User passes 38 debugger/tool workflows (native perf remains unavailable).
  Active multithreaded IO and blocked pipe/socket/poll/epoll targets pass repeated
  GDB breakpoint/continue/detach in Alpine/Shell and Debian/User, in lazy and Base.
  Receipts: `debugger-delivery-{alpine-shell,alpine-base,debian-app,debian-base}/`.
  Shared supervisor-state checks and untraced filesystem/memory signal stress
  also pass under both executors, including STOP/CONT and guest filters:
  `results/debugger-delivery-{supervisor-state,fs-signals}-{app,shell}.json`.
  Inherited-filter image-entry acceptance additionally passes 32 combinations:
  direct TRACEME exec, syscall exec and both nonleader exec forms, with and
  without an inherited filter, on Debian/User and Alpine/Shell in Base and lazy.
  These use ordinary APK build ID
  `3b8f74366bf0f899233f47531177e22dfa2d04e025e727233e72724228013b6a`.
  Receipts: `filtered-exec-{debian-app,alpine-shell}-{base,lazy}/`.
- All 13 recipe distributions require current User and Shell application
  qualification. Existing cells below describe their recorded builds, not a
  completed 2.3 pass. Reuse installed stores sequentially; access handoffs are
  not copies and must retain their restoration journals.
- Landlock filesystem and mounted-store contracts pass on Arch/User and
  Alpine/Shell, independently in Base and lazy, on ordinary APK build ID
  `5f6872198dabcfea56eb3554c6d60c4855766fe43f21edaaab246972b68e2d02`.
  Coverage includes nested tmpfs/ramfs, bind-source ancestry boundaries,
  directory-relative/proc-FD reopening and policy-layer intersection, with
  positive and negative assertions. Receipts:
  `landlock-after-{arch-app,alpine-shell}-{base,lazy}/`.
- Default-library installation/resume/discovery and final APK checks are pending.
- Quiescence and lifecycle follow-up uses optimized fixture APK build ID
  `a4327dde84609813f7aad05d9effb6e32b5acd1f5819211d503833585f414b62`.
  Supervisor-state, robust-futex, private-memory and launch-cancellation suites
  pass under User and Shell: `results/quiesce-*-{app,shell}.json`. This covers
  unavailable interrupt targets, cleanup admission, retained FD custody,
  protected mappings and exact process-tree cancellation.
- Teardown acceptance uses fixture APK build ID
  `db8f47632bd543c15ec744dd21a68706ef3e4f7641405d01312e786cca4f73c7`.
  Supervisor-state, filesystem worker transport (504 O_PATH cases) and launch
  cancellation pass under User and Shell. Pending broker continuations and
  channels retain their ownership through worker join. Receipts:
  `supervisor-cleanup-{app,shell}.json`,
  `supervisor-cleanup-fs-{app,shell}.json` and
  `supervisor-cleanup-cancel-{app,shell}.json`. Ordinary APK build ID
  `4a3a98185f48139184853c4a687b720617f57458efdd1cdb9e83e3627340e957`
  also completes an uninstrumented, approximately 20-minute offscreen SteamOS
  Shell session with status 143 and full graphics cleanup. Browser, editor and
  portal workflows complete before shutdown. Receipt:
  `steamos-reboot-acceptance/session.json`. This is lifecycle evidence, not final
  APK performance or qualification of every desktop startup service.
- Temporary-tree recovery and the full transient-store suite pass under Shell
  and User with fixture build ID
  `6c792d8433ad8050483534ff8a48fd95a9bdfe0ad3a6032b3029d94d54f22ac7`.
  Coverage includes damaged markers, final release, admission-time collection,
  queued rights, live leases, overlay views and descriptor balance. User invokes
  the same packaged fixture through an App console; its receipt records the
  untrusted-app identity and matching native hash. Receipts:
  `results/transient-recovery-shell.json`,
  `results/transient-recovery-app-direct.json` and
  `results/transient-custody-shell.json`. These checks do not simulate arbitrary
  power-loss damage to persistent guest files.
- Uninstrumented, unpinned Shell timing compares the saved source baseline with
  the clean runtime below. Eight alternating rounds retain file-stat, open/close,
  file-cycle, poll and epoll measurements; a separate twelve-round stat/epoll
  repeat uses longer file blocks. Median paired time changes in that repeat are
  stat +1.7% and epoll -1.5%; first-series open/close, file-cycle and poll changes
  are -0.7%, -1.4% and -0.8%. Both series retain all samples, frequency observations
  and caller CPU endpoints in `results/qualification-performance.json` and
  `results/qualified-performance-repeat.json`. These are short-loop measurements,
  not final packaged-APK, User performance or SteamOS video/audio acceptance.
  Those checks and the supervisor CPU targets remain pending.
- The non-fixture native build matching APK `5f687219...` passes an eight-round
  unpinned Shell comparison: median paired loop changes are stat +1.3%,
  open/close +0.6%, file-cycle -0.5%, poll +6.3% and epoll +4.0%. A separate
  sixteen-pair poll/epoll repeat gives +0.8%/-6.0%. CPU migration and individual
  pair variation prevent a speedup claim; there is no consistent slowdown across
  the repeats. File tests retain sixteen blocks per launch; poll/epoll retain
  one million iterations. All samples, frequency residency and thermal state
  remain in `performance-landlock/oci-services-7cd51fac29cc4b9dbbeaf67f15f0ea1c.json`
  and `performance-readiness-landlock/oci-services-11315e46fbc743209a59db02fa1af124.json`.
  Both candidates use the same guest binary with verified lazy completions;
  timing runs have no runtime instrumentation or concurrent GUI workload.
  A separate counter-only run confirms native poll/epoll loops: both builds
  retain 38 startup/teardown stops per launch, not per-iteration interception.
  Receipt: `diagnostic-readiness-landlock/oci-services-8e44465e46274a9fbb62f5455e7dd9c8.json`.
- The non-fixture native build matching ordinary APK build ID
  `fc0042a5dd9aee463d2d48ff4d17821df5caa7a4c08349d4f0f44c40d7bf4763`
  passes an eight-round uninstrumented Shell comparison against the same
  source baseline. Median paired time changes are stat +5.2%, open/close -0.2%,
  file-cycle -1.6%, poll +0.8% and epoll +0.1%. The battery stays at 34 C;
  frequency residency and individual pairs vary substantially. This is a
  regression screen, not a precision speedup claim. Receipt, including all
  thermal/frequency observations:
  `performance-cleanup/oci-services-b44b51dbb8994abdac21ea87d06ef3b3.json`.

Honor changes are limited to fixes driven by remote-device feedback. Changes to
shared launch, graphics, audio or executor boundaries require affected workflow
reruns; isolated firmware-window changes do not invalidate native-only receipts.
Keep confirmed upstream failures and unavailable prerequisites distinct from
Shroot defects. The Linux 5.15 capability profile is not a physical 5.15 device.

### Current Application Pass

These cells describe the 2.3 qualification receipts, independently of the older
application tables. Each result is lazy / Base. K3s uses the complete one-node
VXLAN scenario above, not just API startup. The clean fixture APK build ID is
`b49ae4d60087cc171466ef3b34a76363c59f76d365094d4a5d1d900c6b0b7330`;
Alpine User and Debian User retain the explicitly identified builds above.

| Recipe | K3s User | K3s Shell | Receipt under `build/release-2.3/results/` |
| --- | --- | --- | --- |
| alpine | P/N | P/N | `k3s-alpine-{app,shell}.json` |
| debian | P/N | N/N | `k3s-debian-app.json` |
| arch | P/N | N/N | `k3s-arch-app.json` |
| fedora | P/N | N/N | `k3s-fedora-app.json` |
| opensuse | P/N | N/N | `k3s-opensuse-app.json` |
| ubuntu | P/N | N/N | `k3s-ubuntu-app.json` |
| armtix | P/N | N/N | `k3s-armtix-app.json` |
| manjaro | P/N | N/N | `k3s-manjaro-app.json` |
| nixos | P/N | N/N | `k3s-nixos-app.json` |
| steamos | N/N | P/N | `k3s-steamos-shell.json` |
| deepin | N/N | P/N | `k3s-deepin-shell.json` |
| openkylin | N/N | P/N | `k3s-openkylin-shell.json` |
| void | N/N | P/N | `k3s-void-shell.json` |

### Current Graphical Applications

These offscreen checks use the ordinary packaged APK, build ID
`1e277848f87be129bb4067ab084d46c2a2683269d42b7efae0c20ad65962fc97`,
SHA-256 `7503327efbb6baf0cea78d83e4718dfcff8ed76994ba785a56c2fc9f74857f2e`.
Later qualifying builds are identified below with their affected workflows.
The Armtix follow-up explicitly identifies its fixture-enabled APK.
Cells are lazy / Base; each row qualifies only its stated executor. Other
executors and missing workflows remain pending; results are not automatically
promoted to another APK.

| Recipe | Executor | VS Code | Document portal | Firefox | Chromium |
| --- | --- | --- | --- | --- | --- |
| alpine | Shell | P/N | P/N | P/N | P/N |
| debian | User | P/N | P/N | P/N | P/N |
| arch | User | P/N | P/N | P/N | P/N |
| fedora | User | P/N | P/N | P/N | P/N |
| opensuse | User | U/N | P/N | P/N | P/N |
| ubuntu | User | P/N | P/N | P/N | P/N |
| armtix | User | P/N | P/N | P/N | P/N |
| manjaro | User | P/N | P/N | P/N | P/N |
| nixos | User | P/N | P/N | P/N | P/N |
| steamos | Shell | P/N | P/N | P/N | P/N |
| deepin | Shell | P/N | U/N | P/N | P/N |
| openkylin | Shell | P/N | P/N | P/N | P/N |
| void | Shell | U/U | P/P | P/P | P/P |

Debian, Fedora and Ubuntu VS Code acceptance covers reload, editor/save, filesystem
notifications, integrated PTY, GDB breakpoint/step/continue, GPU composition,
renderer sandbox and normal editor closure. Receipts:
`results/gui-{debian,fedora,ubuntu}-app-packaged-vscode.json`.
Fedora and openSUSE portal acceptance covers ordinary-user export/read/write,
revocation, SIGTERM/SIGKILL cleanup, FUSE helper unmount and systemd
stop/restart/reactivation. Receipts:
`results/gui-fedora-app-packaged-portal.json` and
`gui-opensuse-app-status/portal.json`.

Ubuntu's Snap Firefox and Chromium pass fresh and reused profile launches:
keyboard, pointer, download, chooser/readback, WebGL pixels/context and normal
browser closure. Both Snap document-portal workflows pass export/read/write,
revocation, signal cleanup, helper unmount and systemd restart/reactivation.
The fixture activates the stock Snap mount/socket/service units; no helper
timeout or confinement flag is changed. These prepared-namespace runs do not
qualify cold Snap startup under load. Firefox's privacy-reduced WebGL renderer
string is not evidence of the physical GPU model. Receipts:
`gui-ubuntu-app-snap/{firefox,chromium}-snap{,-reuse}/gui-report.json` and
`gui-ubuntu-app-snap/portal-{firefox,chromium}.json`.

Armtix and Manjaro pass the same complete editor workflow with their installed
official Microsoft archives (1.140.0 and 1.143.1). Manjaro selects the existing
`/opt/md-fixture-code-1.143.1/bin/code` launcher explicitly. Their document-portal
export/read/write/revocation and signal/helper cleanup checks also pass; Manjaro
additionally passes systemd stop/restart/reactivation. Armtix uses runit, not
the systemd-specific variant. Receipts:
`results/gui-armtix-app-packaged-{vscode,portal}.json` and
`results/gui-manjaro-app-packaged-archive-{vscode,portal}.json`.
Manjaro retains xdg-desktop-portal 1.18.2-1: this passing run does not establish
absence of its separately documented upstream signal-handler race.

Deepin Shell passes the complete VS Code 1.143.1 workflow. Its document portal
passes export/revocation, both SIGTERM rounds and daemon-death helper unmount,
but whole-service restart/stop hangs with portal 1.22.1 and libfuse 3.14.0.
The unmount child exits on SIGTERM; the daemon then joins a libfuse loop sleeping
on its semaphore with workers still in splice. Explicit unmount releases it.
This matches the [upstream missing-wakeup mechanism](https://github.com/flatpak/xdg-desktop-portal/pull/2083),
not the older GLib signal-handler deadlock. Systemd's repeated cgroup signal scan
also covers newly forked children. The classification is based on package source,
stacks and an untraced unmount/child-status capture; this exact Deepin combination
has not been repeated under native chroot. No package or production timeout is
changed. Receipts: `results/gui-deepin-shell-packaged-vscode.json` and
`gui-deepin-shell-portal/{portal-captured.json,portal-unmount.log}`.

openKylin Shell passes the complete VS Code 1.143.1 workflow and document-portal
lifecycle, including whole-service restart/stop/reactivation. Receipts:
`results/gui-openkylin-shell-packaged-{vscode,portal}.json`.
Void Shell passes the same editor/debugger workflow with VS Code 1.140.0, and
portal grant/revocation plus signal/helper cleanup under runit. Receipts:
`results/gui-void-shell-packaged-{vscode,portal}.json`.
SteamOS Shell passes the editor/debugger and complete systemd portal lifecycle
workflows inside its installed Plasma Wayland session, with Steam autostart
left enabled. Receipts: `results/gui-steamos-shell-packaged-{vscode,portal}.json`.
These editor receipts precede the separate `SystemInfo.getInfo` GPU-crash-count
and GPU-sandbox assertions; enabled composition and a sandboxed renderer alone
do not establish GPU sandbox admission or zero recovered GPU crashes.
Void Shell and Debian User also pass the strengthened editor fixture with GPU
crash count zero at both checkpoints, hardware composition and a Zink/Turnip
renderer: `results/gui-{void-shell,debian-app}-gpu-verified-vscode.json`.
Debian's complete systemd portal lifecycle passes:
`results/gui-debian-app-gpu-verified-portal.json`.
Arch User passes the same complete portal lifecycle:
`results/gui-arch-app-packaged-portal.json`.
Its stock Flatpak VS Code 1.139.1 also passes the full workflow twice, including
GDB breakpoint/step/normal exit, renderer sandbox and zero GPU-process crashes
at both checkpoints. Those editor receipts use the newer ordinary APK build ID
`3b8f74366bf0f899233f47531177e22dfa2d04e025e727233e72724228013b6a`,
SHA-256 `a599323451985e3f041ba895b4b0b5299fd7a3bad182fa65bb6d835e594d38c0`:
`gui-arch-app-filtered-exec/vscode-stock{,-repeat}/gui-report.json`.
Flatpak 1.18.4 retains unmodified package files; these passing runs do not
invalidate the separately documented upstream descriptor-collision case.
Arch's stock Flatpak Firefox and Chromium pass all seven browser checks with
both fresh and reused profiles on ordinary APK build ID
`5f6872198dabcfea56eb3554c6d60c4855766fe43f21edaaab246972b68e2d02`,
SHA-256 `02184b12a3c5d7f81a41667621519553f6f6a3ddaba009decd583eb14c37c842`.
Chooser input uses ordinary `wl-copy` MIME detection, without a forced text type.
The complete systemd document-portal lifecycle also passes on that build.
Receipts: `gui-arch-app-landlock/{firefox,chromium}-flatpak{,-reuse}/gui-report.json`
and `gui-arch-app-landlock/portal.json`.

Alpine Shell uses the same `5f687219...` APK for stock Flatpak VS Code 1.139.1,
native Firefox 151.0.3 and native Chromium 149.0.7827.53. The editor passes reload,
PTY, GDB and normal closure with a sandboxed renderer, hardware composition and
zero GPU-process crashes at both checkpoints. Both browsers pass all seven
checks with fresh and reused profiles. Chromium's exported `chrome://gpu` report
also records `Sandboxed: true`, Zink/Turnip, hardware composition and zero GPU
crashes. Document export/read/write/revocation, two SIGTERM exits and daemon-death
helper unmount pass under OpenRC. This does not disprove the separately qualified
upstream signal-handler race in portal 1.20.3. Receipts:
`gui-alpine-shell-landlock/vscode-stock/gui-report.json`,
`gui-alpine-shell-landlock/{firefox,chromium}-native{,-reuse}/gui-report.json`,
`gui-alpine-shell-landlock/chromium-native/gpu.txt` and
`gui-alpine-shell-landlock/portal.json`.

Debian User passes the same seven browser checks for stock Flatpak Firefox 159.0
and Chromium 156.0.8078.47, with both fresh and reused profiles, on the
`5f687219...` APK. Chromium's exported GPU report confirms hardware composition,
GPU sandbox admission and zero GPU-process crashes. The complete systemd
document-portal lifecycle also passes again on this build. Receipts:
`gui-debian-app-browsers/{firefox,chromium}-flatpak{,-reuse}/gui-report.json`,
`gui-debian-app-browsers/chromium-flatpak/gpu.txt` and
`gui-debian-app-browsers/portal.json`.

Fedora User passes all seven checks for stock Flatpak Firefox 159.0 and
Chromium 156.0.8078.73 with fresh and reused profiles on the same `5f687219...`
APK. Chromium's GPU report records hardware composition, `Sandboxed: true`,
Zink/Turnip and zero GPU-process crashes. Receipts:
`gui-fedora-app-browsers/{firefox,chromium}-flatpak{,-reuse}/gui-report.json` and
`gui-fedora-app-browsers/chromium-flatpak/gpu.txt`.

openSUSE User passes all seven checks for stock Flatpak Firefox 159.0 and
Chromium 156.0.8078.47 with fresh and reused profiles on the `5f687219...` APK.
Chromium retains hardware composition, its GPU sandbox and zero GPU-process
crashes. This guest lacks `at-spi2-core` and the `org.a11y.Bus` activation file;
these pointer/key workflows do not certify AT-SPI availability. Receipts:
`gui-opensuse-app-browsers/{firefox,chromium}-flatpak{,-reuse}/gui-report.json`
and `gui-opensuse-app-browsers/chromium-flatpak/gpu.txt`.

SteamOS Shell passes all seven checks for stock Flatpak Firefox 158.0.1 and
Chromium 155.0.8059.39 with fresh and reused profiles on the `5f687219...` APK,
inside Plasma Wayland with Steam autostart enabled. Chromium retains hardware
composition, `Sandboxed: true` and zero GPU-process crashes. Document grants,
revocation, signal/helper cleanup and systemd stop/restart/reactivation also
pass. Receipts:
`gui-steamos-shell-browsers/{firefox,chromium}-flatpak{,-reuse}/gui-report.json`,
`gui-steamos-shell-browsers/chromium-flatpak/gpu.txt` and
`gui-steamos-shell-browsers/portal.json`.
The same untraced Plasma session completed cancellation with exit 143 after
about 20 minutes of browser and portal work; its `session.json` retains the
completion receipt. Current teardown qualification is recorded in the shared
mechanism checks above.
The first pointer event in a fresh Plasma session also passes the strict Chromium
workflow (11 checks, including exported GPU status) on ordinary APK
`962ce7ca...`, with the recipe's pointer-enter correction present in installed
KWin. The tested `libkwin.so.6.2.5` has SHA-256
`d5015a634a9e5204f0b2483ac4fdd4ac0439a5d52f32d1c0ca647a05c6f68076`.
Receipt: `steamos-enter-fixed/chromium-enter/gui-report.json`. This validates
pointer delivery, not installer resume or other KWin versions.

Armtix User passes all seven browser checks for stock Flatpak Chromium
156.0.8078.47 and Firefox with fresh and reused profiles on the optimized
`a4327dde...` fixture APK. Both Chromium GPU reports retain hardware composition,
`Sandboxed: true` and zero GPU-process crashes. The GL extension uses the
recipe's ABI-checked publisher with the existing verified Flatpak SDK 25.08
Mesa build; this is not fresh-install acceptance for Armtix's older native Mesa.
Document grants, revocation, two SIGTERM exits and daemon-death helper cleanup
pass under runit. The complete session closes with exit 143. Receipts:
`gui-armtix-app-quiesce/{chromium,firefox}-{current,reuse}/gui-report.json`,
`gui-armtix-app-quiesce/chromium-{current,reuse}/gpu.txt`,
`gui-armtix-app-quiesce/portal-current.json` and its `session.json`.

Manjaro User passes fresh/reused-profile checks for stock Flatpak Chromium
156.0.8078.47 and Firefox 159.0 on ordinary APK build ID
`962ce7ca91befe148eb97191999e9800de1079af5727edeff1fdde78448f0832`.
Both Chromium runs retain hardware composition, `Sandboxed: true` and zero GPU
process crashes. Document grants/read/write/revocation, signal/helper cleanup
and whole-service systemd stop/restart/reactivation pass. Receipts:
`gui-manjaro-app-browsers/{chromium,firefox}-{current,reuse}/gui-report.json`,
`gui-manjaro-app-browsers/chromium-{current,reuse}/gpu.txt` and
`gui-manjaro-app-browsers/portal-current.json`.

Deepin Shell passes all seven GUI checks for stock Flatpak Chromium 156.0.8078.47
and Firefox 159.0 with fresh and reused profiles on the `962ce7ca...` ordinary
APK. Chromium retains hardware composition, `Sandboxed: true` and zero GPU
process crashes in both runs. Browser/file-chooser acceptance does not change
the separate whole-service portal U classification above. Receipts:
`gui-deepin-shell-browsers/{chromium,firefox}-{current,reuse}/gui-report.json`
and `gui-deepin-shell-browsers/chromium-{current,reuse}/gpu.txt`.

openKylin Shell passes fresh/reused-profile checks for stock Flatpak Chromium
156.0.8078.47 and Firefox 159.0 on the `962ce7ca...` ordinary APK. Both Chromium
reports retain hardware composition, `Sandboxed: true` and zero GPU-process
crashes. The installed GL extension retains its separately qualified SDK driver;
these runs do not certify a fresh graphics installation. Document grants,
revocation, signal/helper unmount and systemd stop/restart/reactivation pass.
Receipts: `gui-openkylin-shell-browsers/{chromium,firefox}-{current,reuse}/gui-report.json`,
`gui-openkylin-shell-browsers/chromium-{current,reuse}/gpu.txt` and
`gui-openkylin-shell-browsers/portal-current.json`.

Void Shell passes fresh/reused-profile checks for stock Flatpak Chromium
156.0.8078.47 and Firefox 158.0.1 on the `962ce7ca...` ordinary APK. Chromium's
fixture verifies a fresh GPU report per launch: hardware composition, GPU sandbox
admission and zero crashes. Its GL extension uses the current ABI-checked
publisher and the existing verified SDK Mesa build, not a fresh native recipe
build. Base also passes Firefox and Chromium's hardware/sandbox workflow with
the same SDK driver. The older native driver does not satisfy GPU-sandbox
acceptance and is not interchangeable with that receipt. Document
export/read/write/revocation and signal/helper cleanup pass under runit in both
lazy and Base. Receipts:
`gui-void-shell-browsers/chromium-gpu{,-reuse}/gui-report.json`,
`gui-void-shell-browsers/firefox-{current,reuse}/gui-report.json`,
`gui-void-shell-browsers/portal-current.json` and
`gui-void-shell-base/portal-base.json`,
`gui-void-shell-base/firefox-base/gui-report.json` and
`gui-void-shell-base/chromium-base-sdk/gui-report.json`.

Void's stricter VS Code acceptance is U in both entry modes with Mesa 26.2.3.
The captured SIGSEGV is in `memcpy` from `zink_buffer_subdata`, called by
`tc_texture_subdata`: its staging upload copies `layer_stride * box->depth`
instead of the calculated source span. The inaccessible source address and
call path match the upstream buffer over-read/thread-race correction listed in
[Mesa 26.2.5](https://docs.mesa3d.org/relnotes/26.2.5.html).
Successful repeats with hardware composition and both sandboxes do not close
this upstream defect; a corrected driver has not been qualified here. These
are lab-driver checks, not fresh-install acceptance. Receipts:
`gui-void-shell-browsers/vscode-gpu-qualified/gui-report.json`,
`gui-void-shell-browsers/vscode-gpu-repeat-{0,1,2}/gui-report.json` and
`gui-void-shell-base/vscode-gpu-repeat-2/{gui-report.json,electron.log}` and
`gui-void-shell-base/vscode-gpu-gdb-2/gpu-gdb.log`.

openSUSE VS Code 1.143.1 completes reload, editing, PTY and file notifications,
but its status command fails before this fixture reaches GDB. Without `which`,
the package uses `ps.sh`; the script treats a process disappearing during its
`/proc` enumeration as fatal. The CLI hides that error unless verbose and exits
zero with no report. Native `/proc` and procps memory counters are coherent;
the package's status formatter also converts memory percentages to bytes twice.
These package diagnostics defects are U, not a passing full editor workflow.
Evidence: `gui-opensuse-app-status/vscode-diagnostic/{gui-report.json,status-diagnostic.txt}`
and `results/gui-opensuse-app-packaged-vscode.json`.

NixOS User acceptance runs the installed Plasma Wayland session.
The native Firefox and Flatpak Firefox/Chromium workflows pass fresh-profile and
reused-profile launches: keyboard, pointer, download, file chooser/readback,
Turnip WebGL pixels/context and normal browser close. Guest sandboxing and lazy
entry remain enabled. Chromium's fresh-profile diagnostic run records a netlog;
the reused-profile run uses the ordinary fixture command. KDE Wallet prompts
are dismissed through their normal UI, not bypassed by flags or service changes.

Receipts are under `browsers-nixos-app-feedback-packaged/`:
`firefox-native{,-reuse}/gui-report.json`,
`firefox-flatpak{,-reuse}/gui-report.json`, and
`chromium-flatpak-{net,reuse}/gui-report.json`. Session cancellation completes
without a supervisor crash. These receipts do not certify default-library
discovery, every document-portal grant, audio continuity or graceful init shutdown.
NixOS also passes the complete editor and portal lifecycle workflows in Plasma
on this APK: `results/gui-nixos-app-packaged-{vscode,portal}.json`. The editor
uses the installed Nixpkgs `vscode-with-extensions` 1.119.0 launcher and its
packaged debugger extension, without adding an FHS loader alias. The portal is
Nixpkgs xdg-desktop-portal 1.20.4; both graceful exits, daemon-death unmount and
systemd stop/restart/reactivation pass within the unchanged deadlines. A passing
run does not remove the separately qualified upstream signal-handler race.

### Current System Workloads

These runs use the existing installed recipe stores. Each cell is lazy / Base.
Receipts retain the exact runtime; openKylin uses the cancellation-qualified build
below, and the other workloads use the clean build above. Docker and Podman here run as guest root;
ordinary-user containers require their separate checks. QEMU is direct TCG boot;
libvirt adds managed lifecycle, pause/resume and teardown, not VM networking.
LTP covers the seven reviewed ordinary-user tests. A missing result in this pass
is N, even when another build has evidence elsewhere in the matrix.

| Recipe | Executor | Docker | Podman | QEMU | Libvirt | BuildKit | LXC | LTP | nspawn command | machinectl boot |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| alpine | Shell | P/N | P/N | P/N | P/N | P/N | P/N | P/N | N/N | N/N |
| debian | User | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N |
| arch | User | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N |
| fedora | User | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N |
| opensuse | User | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N |
| ubuntu | User | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N |
| armtix | User | P/N | P/N | P/N | P/N | P/N | P/N | P/N | N/N | N/N |
| manjaro | User | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N |
| nixos | User | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N |
| steamos | Shell | P/N | P/N | P/N | B/N | P/N | P/N | P/N | P/N | P/N |
| deepin | Shell | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N |
| openkylin | Shell | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N | P/N |
| void | Shell | P/N | P/N | P/N | P/N | P/N | P/N | P/N | N/N | N/N |

Receipts are under `build/release-2.3/results/services-*.json`; the corresponding
`services-*/` directories retain preparation, workload and shutdown logs.
Other executor/OS combinations, VM networking and graphical workflows remain
pending for this pass.

SteamOS uses the lab-installed upstream Docker Engine with nftables, overlay2
and the unchanged container sandbox; its distribution provides `podman-docker`,
not a Docker service. Libvirt remains blocked by the absence of a package in its
enabled repositories. Package inventory is retained in
`logs/services-steamos-shell-package-audit.log`.

Cancellation qualification uses fixture APK build ID
`5b4ff664ae71d2a6dd982bd785959271dbc020b272c4bbe5b6a4047686597067`.
Shared ownership checks pass under User and Shell; active/parked thread groups
with robust registration pass bounded TERM/KILL cancellation under both.
The same cancellation checks pass with the Linux 5.15 capability profile.
All 13 recipe distributions complete init-session cancellation with status 143
and final log collection under the executor selected in the workload table.
Debian/User and SteamOS/Shell also pass this workflow in Base mode.
The robust-futex component suite also passes under User and Shell.
Receipts: `results/cancellation-*.json` and
`logs/cancellation-owner-{app,shell}.log`. These do not promote earlier workload
receipts to the new build or establish graceful in-guest system shutdown.

All 13 selected executor/OS pairs also pass this launch/controller/cancellation
workflow using ordinary APK build ID
`fc0042a5dd9aee463d2d48ff4d17821df5caa7a4c08349d4f0f44c40d7bf4763`,
without injected fixture executables or runtime counters. Every launch ends
with status 143; receipts: `results/cancellation-*-lazy-cleanup.json`.
This short-session result does not establish long graphical-session stability.

Uninstrumented Shell timing for this runtime retains all four alternating rounds
of stat, open/close, file-cycle, poll and epoll in
`results/cancellation-performance.json`; steady-loop medians differ from the
saved baseline by less than 1%. A separate twelve-round stat repeat has paired
median changes of -0.1% for full launch and -7.2% for all file blocks.
The same-binary control reproduces first-block variation of approximately
3-8 ms, so the short series does not establish a cold-start regression or gain.
Receipts `results/cold-stat-{repeat,same-build}.json` retain both binary hashes,
all rounds and host frequency observations. No CPU affinity, governor changes,
warmup exclusion or test counters are used; final packaged-APK and User timing
remain pending.

### Current Rootless Containers

These checks use an ordinary guest account, independently of the Android
executor. Each cell is lazy / Base. SteamOS, Deepin, openKylin and Void use fixture APK build ID
`59e20a9aee63b8fb6f8866ff346b99bce896cfb7f34004304985e52341550de2`;
the other completed rows retain the cancellation-qualified build above.
Docker retains its stock RootlessKit launcher and bridge policy; Podman retains
its default rootless network, including packaged pasta where selected.
Sandbox policy and cgroups remain enabled. The common probes check file IO,
repeated exec, TCP/UDP publication, outbound traffic, occupied-port rejection,
continued service by existing containers after that rejection, and port release.
Docker also checks peer IP/name traffic. Both probes preserve the outer system
mounts. These local exchanges do not certify public Internet reachability.

| Recipe | Executor | Docker | Podman |
| --- | --- | --- | --- |
| alpine | Shell | P/N | P/N |
| debian | User | P/N | P/N |
| arch | User | P/N | P/N |
| fedora | User | P/N | P/N |
| opensuse | User | P/N | P/N |
| ubuntu | User | P/N | P/N |
| armtix | User | P/N | P/N |
| manjaro | User | P/N | P/N |
| nixos | User | P/N | P/N |
| steamos | Shell | P/N | P/N |
| deepin | Shell | P/N | P/N |
| openkylin | Shell | P/N | P/N |
| void | Shell | P/N | P/N |

Receipts under `build/release-2.3/results/`:
`rootless-alpine-shell-port-conflict-{docker,podman}.json`,
`rootless-debian-app-port-conflict-docker.json`,
`rootless-debian-app-packaged-pasta-podman.json`,
`rootless-arch-app-packaged-podman.json`,
`rootless-arch-app-rootless-launcher-docker.json` and
`rootless-fedora-app-qualified-{docker,podman}.json` and
`rootless-opensuse-app-qualified-{docker,podman}.json` and
`rootless-ubuntu-app-qualified-{docker,podman}.json` and
`rootless-armtix-app-prepared-{docker,podman}.json` and
`rootless-manjaro-app-stdio-owner-{docker,podman}.json` and
`rootless-nixos-app-qualified-{docker,podman}.json` and
`rootless-steamos-shell-udp-verified-{docker,podman}.json` and
`rootless-deepin-shell-udp-verified-{docker,podman}.json` and
`rootless-openkylin-shell-packaged-{docker,podman}.json` and
`rootless-void-shell-packaged-{docker,podman}.json`.
SteamOS and Deepin complete both scenarios and session cancellation without a host debugger.
SteamOS uses upstream Docker 29.1.3 and RootlessKit 2.3.5 with its packaged pasta.
The same runtime passes the complete shared resource model under User and Shell,
including IPv4/IPv6 UDP publication ownership, dynamic socket options, bind
rollback and NAT admission/removal. Receipts:
`results/udp-publication-model-{app,shell}.json`.
Private-file memory repair, directory-result delivery and the full inotify
runtime suite also pass on this build under User and Shell with the Linux 5.15
capability profile. Deadlines and ordinary-read interception assertions remain
unchanged. Receipts: `results/copy-fence-*-kernel515.json`. This profile restricts
available host operations; it is not physical-kernel or firmware coverage.
Uninstrumented Shell timing retains four alternating rounds of all five file
and readiness workloads in `results/udp-publication-performance.json`. A separate
16-pair stat/poll/epoll repeat records paired median changes against the source
baseline of +1.23%, +0.08% and +0.55%, respectively, in
`results/udp-publication-performance-repeat.json`. Neither run pins CPUs or changes
their governor; all samples and frequency observations remain in the receipts.
These loops do not qualify User timing, video/audio or the final packaged APK.
Distribution packages installed for these probes are test dependencies, not
additional installer recipe requirements.
Armtix Docker uses an unchanged lab-installed Arch RootlessKit 3.3.0 binary and
the cached upstream rootless launcher; its enabled repository lacks RootlessKit.
Dependency versions and hashes are retained in `logs/rootless-dependency-export.log`
and `logs/rootless-armtix-app-prepared-prepare.log`. This is rootless-runtime
acceptance, not evidence that Armtix ships those dependencies.
Manjaro retains its packaged RootlessKit 1.1.1 and Docker 25.0.2 with the
unchanged upstream launcher from that Docker tag, installed only for the lab.
openKylin retains Docker 24.0.7 and RootlessKit 2.0.2, with the unchanged upstream
24.0.7 launcher installed for the lab. Its packaged Docker commands require
`/opt/system/bin` in PATH. No container sandbox or network option is disabled.
Void retains Docker 29.8.0 and RootlessKit 2.2.0, using the unmodified launcher
from the upstream Docker 29.1.3 rootless-extras archive as a lab dependency.

### Current Boot Pass

The boot receipts identify their exact runtime builds and exercise each installed
recipe's own init with the common installer preparation. Systemd acceptance
includes default-target health,
actual D-Bus and journal IO, logind restart and strict shutdown checks. OpenRC
and runit retain their stock stages and check service lifecycle. NixOS activates
the selected installed generation. These checks do not certify graphical sessions
or the complete application matrix.

| Recipe | Executor | Init and service lifecycle (lazy / Base) |
| --- | --- | --- |
| alpine | Shell | P/P |
| debian | User | P/P |
| arch | User | P/P |
| fedora | User | P/P |
| opensuse | User | P/P |
| ubuntu | User | U/N |
| armtix | User | P/P |
| manjaro | User | P/P |
| nixos | User | P/P |
| steamos | Shell | P/P |
| deepin | Shell | U/N |
| openkylin | Shell | P/P |
| void | Shell | P/P |

Receipts: `results/boot-matrix-*.json`, `results/boot-alpine-shell-*.json`
and `boot-debian-app-bridge-lazy/`. Ordinary default-target runs use a private
bridge where stock services bind DHCP ports; routed publication of port 67 is
subject to Android permission checks, not an emulated permission grant.

Ubuntu and Deepin reach a healthy default target and pass the service-IO checks,
but strict shutdown retains **U**. Their packaged `podman-restart.service` invokes
`podman stop` without arguments when no always-restart containers exist, returning
125. Ubuntu's LXC 5.0.3 also returns failure for an empty autostart list. Installed
versions are Podman `4.9.3+ds1-1ubuntu0.2`, LXC `1:5.0.3-2ubuntu7.3`, and Deepin
Podman `5.2.5.2-1deepin3`. The installed units and isolated empty-list calls are
retained in `ubuntu-service-config/`, `ubuntu-empty-stop/`,
`ubuntu-lxc-version/` and `inspect-deepin/`; upstream source confirms the
[Podman stop command](https://github.com/containers/podman/blob/v4.9.3/contrib/systemd/system/podman-restart.service.in)
and [LXC empty-list verdict](https://github.com/lxc/lxc/blob/lxc-5.0.3/src/lxc/tools/lxc_autostart.c).
No units were disabled and the shutdown assertion remains strict.

## Reading Results

Each paired cell is **Shroot (lazy SVC) / Shroot Base**, with independent
results, not a ratio. `P/N` means Shroot passed and Base was not run;
`N/N` means neither mode has a qualifying result for that scenario.

| Code | Meaning |
| --- | --- |
| - | Outside the selected test scope for this distribution; no compatibility claim |
| N | Not run; includes fixtures that still need implementation |
| P | Passed the named scenario in the recorded run, not necessarily the latest build |
| I | Incomplete: some assertions passed, but acceptance remains open |
| F | Observed failure not attributed to a confirmed upstream defect; retain its reproduction until a qualifying rerun passes |
| U | Observed failure attributed to a confirmed upstream defect; not a pass or a resolved limitation |
| B | Blocked by a named prerequisite; not a pass or proof of an application defect |
| H | Historical coverage only; current acceptance and exact entry-mode evidence need reconciliation |
| NA | Not applicable, with an explicit distribution/package/hardware reason |

An N in the Base position means there is no qualifying Base receipt here; it
does not assert that nobody has ever run that workflow. H is not a pass in that
entry mode. Do not infer NA from a distribution's init choice or an unattempted
package install. Record unavailable distribution packages separately from a
Shroot implementation failure; do not silently substitute another userspace.

The current scope selects Snap browsers on Ubuntu and Steam/game workflows on
SteamOS only. Other distributions are not required to run those workloads to
complete their selected matrix. A single `-` excludes both entry modes from
that scope; it is not NA (unsupported/not applicable), N (pending coverage),
or a pass. Preserve existing failure evidence when changing scope. All other
N cells remain uncovered unless their scope is explicitly changed.

Use U only with an exact package version, failing receipt and diagnostic evidence:
a published upstream defect/fix, or an isolated reproduction outside Shroot
matching the upstream source. A similar symptom alone remains F.
Keep unaffected checks separate; an upstream fix or lab-patched pass does not
promote the installed package to P without a qualifying rerun. Most current
application receipts exercise Shroot only, so Base often remains N; shared code
and unit-test coverage do not establish an unrun Base application result.

Missing workload packages or dependencies (for example Podman or aardvark-dns)
must not add source-build fallbacks to `install_linux.sh`. Lab-only source builds
may test Shroot compatibility, but their receipts must identify the upstream
version, provenance and locally built components. Such a pass does not establish
availability from that distribution's repositories or the installer. The
explicit Turnip build remains a separate graphics recipe, not a general license
to compile missing applications during installation.

P applies only to the native hashes, package versions and configuration in its
receipt. Staged native builds do not certify the installed APK. Rerun affected
cells after common runtime changes; do not promote older receipts to the new
build or use aggregate unit-test success as application acceptance.

## Linux 5.15 Capability Profile

These results are separate from the ordinary-kernel tables below. The fixture
restricts Shroot's native owners with `linux-5.15` on the device above; it does
not emulate a 5.15 kernel, its scheduling or firmware permissions. Each receipt
retains the applied profile and staged helper hashes. The installed APK's
graphics and automation services are unchanged. Other recipes are untested
under this profile.

| Recipe ID | Init/service lifecycle | Containers/system workloads | Browsers | VS Code | Recipe graphics | Portal |
| --- | --- | --- | --- | --- | --- | --- |
| debian | P/P | P/N | I/N | P/N | P/N | P/N |
| ubuntu | N/N | N/N | N/N | N/N | N/N | N/N |
| alpine | P/P | P/N | I/N | P/N | P/N | U/N |
| fedora | P/P | P/N | P/N | P/N | N/N | P/N |
| arch | P/P | P/N | P/N | U/N | N/N | P/N |
| opensuse | N/N | N/N | N/N | N/N | N/N | N/N |
| openkylin | N/N | N/N | N/N | N/N | N/N | N/N |
| deepin | N/N | N/N | N/N | N/N | N/N | N/N |
| manjaro | N/N | N/N | N/N | N/N | N/N | N/N |
| void | N/N | N/N | N/N | N/N | N/N | N/N |
| armtix | N/N | N/N | N/N | N/N | N/N | N/N |
| nixos | N/N | N/N | N/N | N/N | N/N | N/N |
| steamos | N/N | N/N | N/N | N/N | N/N | N/N |

Container/system coverage comprises booted Docker and Podman, Libvirt/QEMU,
BuildKit, LXC and the selected LTP tests. Alpine and Debian additionally cover
ordinary-user Docker/Podman; Debian, Fedora and Arch pass nspawn/machinectl boot.
Recipe graphics covers X11/Wayland application
input/save and Weston, not full desktop acceptance.

Fedora and Arch pass the complete Firefox/Chromium GUI workflows. Chromium
also passes profile reuse with the recipe's completion patch in native and
Flatpak SDK Mesa. Those are incremental lab builds, not APK-only update evidence.
Their document-portal lifecycle checks pass. Arch VS Code passes the focused
editor/debugger run, but stock acceptance retains the independently established
Flatpak FD-allocation collision (**U**).

On Alpine and Debian, Firefox Flatpak passes, including an Alpine profile relaunch;
Alpine's native Firefox and Chromium also pass. Flatpak Chromium's focused
completion-owned graphics checks pass on both recipes: fresh-profile WebGL,
hardware composition, video, reload and normal exit with the bridge denied in
both client and compositor. Browser acceptance remains **I** until the complete
input/download/chooser workflow is rerun through the updated APK; these scoped
receipts do not replace the full GUI fixture. VS Code editor/watch/PTY/debugger and
renderer sandbox checks pass with software composition in this configuration.
Alpine's stock document portal retains the documented upstream SIGTERM deadlock
(**U**); daemon-death auto-unmount passes. Debian's two portal paths pass.
See [profile receipts](distribution-evidence.md#linux-515-capability-profile).

## Installation And Init

Recipe setup covers the maintained installer and package/graphics preparation,
not Start discovery or GUI acceptance. PID 1 uses the distribution's own manager:
systemd, Alpine OpenRC, or Void/Armtix runit. Focused service tests and default
boot remain different assertions. Turnip requires rendered/read-back pixels,
not just a completed Mesa build or successful device enumeration.

Cell order: **Shroot (lazy SVC) / Base**; N = not run.

| Recipe ID | Recipe setup | Package lifecycle | PID 1 boot | Service lifecycle | Turnip rendering |
| --- | --- | --- | --- | --- | --- |
| debian | P/N | P/N | P/P | P/P | P/N |
| ubuntu | P/N | P/N | I/N | I/N | P/N |
| alpine | P/N | P/N | P/P | P/P | P/N |
| fedora | P/N | P/N | P/P | P/P | P/N |
| arch | P/N | P/N | P/P | P/P | P/N |
| opensuse | P/N | P/N | P/N | P/N | P/N |
| openkylin | P/N | P/N | P/N | P/N | P/N |
| deepin | P/N | P/N | P/N | P/N | P/N |
| manjaro | P/N | P/N | P/N | P/N | P/N |
| void | P/N | U/N | P/N | P/N | P/N |
| armtix | P/N | P/N | P/N | P/N | P/N |
| nixos | P/N | P/N | P/N | P/N | P/N |
| steamos | P/N | I/N | I/N | I/N | P/N |

Package lifecycle means install, update/reinstall, verification and removal,
including transaction hooks. A zero package-manager exit with failed hooks is
not a pass. SteamOS default boot uses a private bridge; routed host-port
publication has the Android permission restriction documented in its
[evidence](distribution-evidence.md#steamos-recipedefault-boot). Current App-UID
boot and firewalld acceptance is recorded separately in
[host execution](host-execution.md#steamos-user-open-work); it does not promote these Shell cells.
Focused control-target tests do not certify a healthy default target.
Ubuntu reaches a healthy default target, but its packaged LXC and Podman
shutdown units return errors with empty container lists; see the
[Ubuntu boot evidence](distribution-evidence.md#ubuntu-default-boot).
See [init contracts and limits](init-matrix.md).

### Boot And IPv6 Acceptance

The 2026-10-23 staged-runtime run used each installed recipe environment, without
an init replacement. All local IPv6 suites passed. The networkd scenario checks
router solicitation/advertisement, SLAAC lifetimes, gateway installation,
wait-online, effective MTU/hop limit, TCP/UDP traffic and router withdrawal.
The binfmt scenario checks the stock boot unit, execution and service restart.
These do not certify every networkd option or external internet access.

Receipts below are `oci-services-ID.json`: boot receipts are under
`build/ipv6-binfmt-boot/`, socket receipts under `build/shroot-image-tests/`.
All runs use default lazy entry; Base remains untested in this batch.

| Recipe | Boot / binfmt / networkd | Boot receipt ID | Local IPv6 receipt ID (PASS) |
| --- | --- | --- | --- |
| debian | P / P / P | `575399e858884d1dbe1874242cae6a4c` | `f5225204674149c89d412b6345ce8e6d` |
| ubuntu | I / P / P | `ab02a8bece5d46de807eb0347bc60696` | `5288fb359718469ea76033506b3cffa6` |
| alpine | P / N / NA (OpenRC) | `f1baebe8d6554b46a002af7387a0e1ac` | `d0b867759a994e8fbc60692e5285a09e` |
| fedora | P / P / P | `2dae19fc1aa245cf8f7add26663e40fa` | `5299bce94b364f1e8aa8304d7c8638a3` |
| arch | P / P / P | `bc915121291e4f34ab51401a0269f49d` | `d69c5902538b433dba0bba1074fa618b` |
| opensuse | P / P / P | `02e150c483294a1da3bd1a317873ed57` | `32f9692fc7db4dda83a0d406a2758e67` |
| openkylin | P / P / P | `df13520ab5d741d99a59464f095608a5` | `1714ef9068d045a2a256d80be63d0859` |
| deepin | P / P / P | `40f5b5045a224a71b27437364a80cc91` | `d4f59c0e2d1d43bcbd4d22b924001072` |
| manjaro | P / P / P | `f7be656fa9da4364915e575d3af7b30f` | `28c08a0520b148459d0052b90d569b69` |
| void | P / N / NA (runit) | `c9d13ce4051c47eb8157aecc1a0f29b2` | `d7281d10f2024a239cd902720e544e31` |
| armtix | P / N / NA (runit) | `30d38d4aec124fc1835aa5e5995d0365` (*) | `2473bd1c2149472daa21744e1cbdca78` |
| nixos | P / P / P | Boot: `dc870a2ce87042b7b8a9b04be968c8da` under `build/nixos-boot-final-20261024/`; binfmt/networkd: [installed receipts](distribution-evidence.md#nixos-network-and-binfmt) | N (separate socket suite) |
| steamos | F / N / N | `fa6234c4dadc46878677e15ed535fb97` | `0aebd5e239ad43f6b1f1a62cbf5e8249` |

Fedora and openSUSE required their separately packaged networkd, installed in
the lab from their own repositories before the passing rerun. This does not
replace openSUSE's Wicked or add a test-only dependency to the user recipe.
SteamOS's current shared firewall implementation admits its inet REJECT rules
and ordered port comparisons. The App-UID boot receipt below verifies rule
addition/removal and restarts; the Shell boot row above has not been rerun.
(*) Armtix's 2026-10-24 boot receipt is under `build/compat-contract-tests/`:
stock runit stages, service start/restart/stop and launch cleanup pass without
startup failures. Its root remount uses the documented persistent-store contract.
Current service receipts:
`build/matrix-close-steamos/boot-handles/oci-services-bf17fdb74ade4dfabbfa36dd79153142.json`
verifies SteamOS default boot under App UID, Avahi mDNS over IPv4/IPv6,
firewalld nft rule addition/removal, three restart cycles, binfmt, RTKit and
clean shutdown. `build/boot-service-repair/oci-services-0553d599fba34bf2ba48e0262789fb0d.json` verifies Ubuntu
default-target startup, journal/D-Bus/logind IO and three CUPS IPP restart
cycles within the unchanged five-second readiness bound. That Ubuntu receipt
does not certify unrelated container-service shutdown.

### NixOS Recipe And Initial-Image Probes

The NixOS recipe and the separate 2026-10-23 probe use the official ARM64 LXC rootfs
`nixos-image-lxc-26.05.12530.c1ff116587e6-aarch64-linux.tar.xz` from
[Hydra build 350465271](https://hydra.nixos.org/build/350465271), SHA-256
`333b1d718db3d99147ca2b5fb6d533b4aabaac98420d48e9a5369799433352ae`.
The imported image and writable probe instance are separate; no foreign
userspace, altered activation script or disabled service substitutes for NixOS.

The console-only recipe check covers `nixos-rebuild boot`, stock activation and
repeat `--resume` with the staged runtime. Passing receipt:
`build/nixos-installer/oci-services-1670e8248a534650937601c95b4bf8c9.json`.
After activation, `shroot login` resolves the selected profile through
`/run/current-system`, finds Python 3.13.15 and retains `sandbox = true`.
The recipe prepares private API mounts for activation and builders together.
The current installer uses command-local `sandbox=false` and
`filter-syscalls=false` for its trusted Nix build, without changing the installed
Nix sandbox policy. Its job default is four cores and one concurrent derivation.
The Plasma/Turnip recipe completed on 2026-10-24, including stock activation of
`/nix/store/g83p188wxrar0nj3c9bsvhp3g3siv70l-nixos-system-nixos-lxc-26.05.12530.c1ff116587e6`.
The installer log is `/data/local/tmp/md-nixos-resume-fast-20261024.log` on the
device. The installed configuration retains `sandbox = true`, four cores and
one concurrent derivation.

This generation's default-target boot, journal/D-Bus/logind IO and restart,
and shutdown pass with the preconfigured recipe network and current native
build: `build/nixos-boot-final-20261024/oci-services-dc870a2ce87042b7b8a9b04be968c8da.json`.
Systemd 260.5 reports `running`, zero failed units and no failed shutdown unmounts.
This boot uses the native helpers built for the installed APK, staged by the
fixture; it does not certify the untested full package lifecycle or `switch`.

Both Plasma catalog entries launch on HDMI through the installed APK
`3a597e4898f89cf8cfb535754b45adfa2040455896d097dba3a2bb2d5a7b3a4d`.
The launcher smoke receipt is `build/installer-graphics-nixos-recipe.json`;
its early splash captures alone are not desktop-readiness evidence. Follow-up
`build/nixos-final-x11.json` and `build/nixos-final-wayland.json`, with matching
PNGs, record the loaded panel and desktop and KWin 6.6.6 OpenGL composition
through Zink/Turnip on Adreno 840, Mesa 26.2.3. Konsole opens through Ctrl+Alt+T;
X11 keyboard input executes `pwd`. Close/reopen retires the owned guest launches.
Full desktop acceptance remains incomplete; individual application results
are recorded separately in the evidence ledger.
The [NixOS input receipt](distribution-evidence.md#nixos-input) covers native
Wayland text entry and GUI file readback, with its remaining pointer/shortcut scope.
The separate boot results below belong to the initial image.

| Scenario | Shroot / Base | Evidence |
| --- | --- | --- |
| Store registration and forced sandboxed derivation rebuild | P/N | `build/ipv6-binfmt-boot/oci-services-08f9b5cbe6b3425ebeee4acec42b8dc7.json`; stock `nix-build --check --option sandbox true`, local inputs, substituters disabled, output readback |
| Stock activation in prepared init namespace | P/P | Boot receipts below; activation returns 0, private dev/proc/run from the common init fixture, disk-backed ramfs for `/run/keys` |
| Stock `/sbin/init`, default target and shutdown | P/P | Boot receipts below; systemd 260.5 reports running and zero failed units, D-Bus/journal/logind IO and restart pass, no failed shutdown unmounts |
| Stock dhcpcd unit, restart, resolvconf and libc DNS | P/P | Boot receipts below; real lease, address/default route, DNS lookup and bidirectional UDP with the service's non-root identity, capabilities, NNP and seccomp retained |

Boot receipts from 2026-10-24 under `build/nixos-boot/`:
Shroot `oci-services-90ef04bbc73f46cfaa3cc72f3ca94c28.json`,
Base `oci-services-31e153adba11486b92e05a4cc8bf6228.json`.
`test_nixos_boot.py` supplies a DHCP/DNS peer over a private guest veth and
namespace; it does not alter the stock dhcpcd unit or Android networking.
Firewall, network-local-commands and resolvconf start with the default target;
this does not certify every firewall rule or external DHCP connectivity.
Separate packaged dhcpcd lease/renewal, DNS hook options, route installation and
bidirectional UDP pass in both entry modes: IPv4 receipt
`build/shroot-image-tests/oci-services-ce87c905651b44e794bb3ccd0b5944c6.json`,
IPv6 receipt `build/shroot-image-tests/oci-services-c804b0fb8aa14e90a5eb28aff0d39779.json`.

The recipe keeps addressing and DNS under Shroot's launch configuration; its
preconfigured routed and bridge uplinks do not provide a DHCP server. Routed
wildcard UDP port 68 also requests an Android listener, subject to host
privileged-port policy; private
guest networking does not require that publication. Guest-owned resolvconf and
a launch-owned read-only DNS mount must not manage the same file.
Full `nixos-rebuild switch` and the wider desktop matrix remain unverified.
The ordinary-user package lifecycle uses the stock daemon and an isolated Nix
profile; see [NixOS package evidence](distribution-evidence.md#nixos-package-lifecycle).
The installed-generation container and system workloads
are recorded in the [NixOS evidence](distribution-evidence.md#nixos).
Initial-image boot retains warnings for unavailable
BPF/cgroup firewalling. The recipe-generation boot also reports missing
`accept_source_route`/`promote_secondaries` default sysctls and duplicate packaged
D-Bus service names; these do not fail a unit in this run.
Logical ramfs is disk-backed compatibility, not memory-only/non-swappable
storage; `/run/keys` therefore has no such secret-storage guarantee.

## Containers And Virtual Machines

Docker and Podman require bridge networking, published TCP/UDP, local outbound
traffic, peer IP/name resolution, binds, exec, port conflicts and released ports.
Record external DNS/internet separately from deterministic local peers. Keep
Docker nftables/no-userland-proxy and its older proxy profile distinct in the
receipt; a pass in one does not cover the other.

Libvirt requires a real managed QEMU guest, not just daemon readiness or
`virsh list`: define, start, serial output, pause/resume, shutdown and undefine.
QEMU's column is the direct TCG kernel/initrd boot and clean-exit scenario.
VM networking, firmware boot, disk persistence and KVM require separate checks;
neither a TCG pass nor guest `/dev/kvm` absence establishes host KVM availability.

Cell order: **Shroot (lazy SVC) / Base**; N = not run.

| Recipe ID | Docker | Podman | Libvirt + QEMU | QEMU TCG boot |
| --- | --- | --- | --- | --- |
| debian | P/N | P/N | P/N | P/N |
| ubuntu | P/N | P/N | P/N | P/N |
| alpine | P/N | P/N | P/N | P/N |
| fedora | P/N | P/N | P/N | P/N |
| arch | P/N | P/N | P/N | P/N |
| opensuse | P/N | P/N | P/N | P/N |
| openkylin | P/N | P/N | P/N | P/N |
| deepin | P/N | P/N | P/N | P/N |
| manjaro | P/N | P/N | P/N | P/N |
| void | P/N | P/N | P/N | P/N |
| armtix | P/N | P/N | P/N | P/N |
| nixos | P/N | P/N | P/N | P/N |
| steamos | P/N | P/N | B/N | P/N |

### K3s

K3s is required in the selected matrix for **every recipe OS**, independently
for Shell and User. Reuse `test_k3s.py`; an init system or package manager does
not exclude a recipe. Missing test prerequisites remain B, never NA by default.
The paired cells retain lazy/Base entry results. Cluster receipts below qualify
only their named topology and host profile, not untested configurations.
The process fixture uses the normal Linux service umask `022`; Shroot itself
preserves the caller's mask, including Android's private app mask.

| Recipe ID | Shell | User |
| --- | --- | --- |
| debian | P/N | P/N |
| ubuntu | N/N | P/N |
| alpine | P/N | P/N |
| fedora | N/N | P/N |
| arch | N/N | P/N |
| opensuse | N/N | P/N |
| openkylin | N/N | P/N |
| deepin | N/N | P/N |
| manjaro | N/N | P/N |
| void | N/N | N/N |
| armtix | N/N | P/N |
| nixos | N/N | P/N |
| steamos | N/N | P/N |

Distribution-specific User acceptance and separate persistent boot/recovery
results are recorded in
[host execution](host-execution.md#qualifying-evidence). A passing boot/recovery
run does not replace workload lifecycle acceptance. The following receipts are
Shell results; they do not certify User or another distribution.

The qualified binary is stock ARM64 k3s v1.37.1+k3s1, running as
guest root under shell UID 2000, with stock container seccomp enabled. Passing
requires Node Ready, CoreDNS, positive kubelet root filesystem capacity,
cross-pod DNS and ClusterIP HTTP, NetworkPolicy deny/allow/update/delete,
Deployment scale and rolling update, persistent-volume data retention, stock
Traefik and ServiceLB ingress, server restart retaining pod/Service identity,
post-restart IO and ordinary pod/Service deletion. Two-node checks additionally
require cross-node DNS/HTTP and an independent agent restart. VXLAN is the
unmodified default backend; host-gw is separately qualified. Memory limits
remain advisory and cadvisor block-device accounting is unavailable.

| Server / agent recipe | Topology | Host profile | Shroot / Base | Receipt |
| --- | --- | --- | --- | --- |
| debian | One node, host-gw | Native | P/N | `build/k3s-contract/k3s-catalogue/oci-services-857d17f45be74bb6b672a7ac7c8a1b61.json` |
| debian | One node, host-gw | `linux-5.15` | P/N | `build/k3s-contract/complete515-solo/oci-services-f1c34da7604449a99cb060a56525dde3.json` |
| debian / debian | Two nodes, VXLAN | Native | P/N | `build/vxlan-contract/two-onlink/oci-services-9f250aeb06f246359d4167f512a0d6cb.json` |
| alpine / alpine | Two nodes, VXLAN | Native | P/N | `build/vxlan-contract/alpine-lazy/oci-services-dce033276db54cf3a886683557d589bc.json` |
| debian / alpine | Two nodes, VXLAN | `linux-5.15` | N/P | `build/vxlan-contract/debian-alpine-base515-pivot/oci-services-da82544b80494027becb17cc07b7ddb8.json` |
| alpine / debian | Two nodes, VXLAN | `linux-5.15` | P/N | `build/vxlan-contract/alpine-debian-lazy515/oci-services-a712095786d94366bb3470df356312b8.json` |

Runs use staged runtimes on one device's 6.12 kernel with separate node
namespaces. Mixed clusters reuse installed recipes with a read-only agent store
and private writable runtime mounts. The 5.15 capability profile exercises
fallbacks, not a physical 5.15 kernel. Separate-device networking and other
recipe distributions have not been qualified for k3s.

The separate `test_k3s_boot.py` lifecycle check runs the upstream installer
inside the production `magicdesk-init` session. Three complete launches check
the enabled service, Node Ready, CoreDNS, pod DNS/HTTP and synced PVC writes:
initial boot, recovery after guest poweroff, then recovery after exact-supervisor
SIGKILL. Node, namespace, pod, Service, volume and credential identities must
remain unchanged. This covers process-crash recovery, not physical power loss
or every service in the distribution's init matrix.

| Recipe / init | Host profile | Shroot / Base | Receipt |
| --- | --- | --- | --- |
| debian / systemd | Native | P/N | `build/k3s-boot/debian/oci-services-ffe40f252826411882a2c44178042011.json` |
| alpine / OpenRC | Native | P/N | `build/k3s-boot/alpine/oci-services-2f73b1639b2d4d0ba268d649aebc92ce.json` |

### Managed Network Regression Coverage

The current network-contract checks retain stock engine packages, container
seccomp and cgroups. Each run covers TCP/UDP publication, named peer exchange,
local outbound traffic, conflicting ports and release. IPv6 observers use
existing Android addresses; no Android route or address is changed.

| Recipe / engine | Entry and host profile | Network profile | Receipt |
| --- | --- | --- | --- |
| alpine / Docker | Base, `linux-5.15` | nftables, no userland proxy, IPv6 `::1` | `build/shroot-image-tests/oci-services-e7f3bb00c58b40d1a9ee59827e633157.json` |
| alpine / Podman | Base, `linux-5.15` | iptables, IPv6 `::1` | `build/podman-results/oci-services-490f9f2fcd564bfd97dcb2b7dbc46592.json` |
| debian / Docker | Lazy, native | iptables-nft, no userland proxy, IPv4 | `build/shroot-image-tests/oci-services-d0dbeb2e24fe4629851ee7635eae1635.json` |
| debian / Podman | Lazy, native | nftables, IPv4 | `build/podman-results/oci-services-b6d0ca08877f41cfbf21a26a6e642475.json` |
| debian / Podman | Default lazy, native | nftables, wildcard IPv6 publication observed through Android's existing local global address | `build/podman-results/oci-services-197af6e64466468f95680b3f67b0aa97.json` |

These focused runs do not rerun the whole application matrix. Debian's Podman
IPv6 result does not qualify `::1`; see the [fixture contract](README.md).

### Local IPv6 Container Acceptance

The 2026-10-24 Arch reruns use stock container packages, their normal seccomp
policy and cgroups, with default Shroot entry. Both cover bridge networking,
TCP/UDP publication, peer IP/name exchange, local outbound traffic, conflicting
ports and port release. They do not request public-internet access.

| Engine | Profile and native observer | Receipt |
| --- | --- | --- |
| Docker 29.9.2 | nftables, no userland proxy, cgroup v2, published `::1` | `build/shroot-image-tests/oci-services-da9ad945e8ff4b0a83719c1f0650a960.json` |
| Podman 6.1.4 / Netavark 2.1.0 | overlay, stock seccomp/cgroups, wildcard IPv6 publication observed through Android's existing local global address | `build/podman-results/oci-services-ca516b0fb73343f68ac530d1031c7391.json` |

The observer runs as native Termux UID 10601, outside Shroot. These runs change
neither Android addresses nor its routes. Podman's tested address is not `::1`;
see the [fixture contract](README.md) for the separate observer/publication
options. These runs do not certify memory-limit enforcement or OOM notifications;
see the [cgroup contract](../shroot/namespace-providers.md).

### IPv6 Uplink Coverage

The following 2026-10-23 checks use the staged native runtime under Android UID
2000; they supplement, rather than replace, the booted-service matrix above.
The ordinary VPN rejects IPv6. An authorized temporary host policy rule selected
the existing cellular route only for `2606:4700:4700::1111/128` and the test UIDs.
The container publication check additionally assigned the outer guest's IPv6
address to Android loopback. Both rules and the address were removed and absence
verified after the tests; the VPN policy was not changed. This is lab setup,
not a root dependency or automatic VPN bypass in Shroot.

| Recipe | IPv6 uplink checks | Evidence |
| --- | --- | --- |
| alpine | PASS: routed and bridge, UDP DNS, TCP DNS, verified HTTPS, remote IPv6 source | `build/ipv6-binfmt-boot/oci-services-4dbeeb4aceac4edbb3cd934febf41f12.json` |
| arch | PASS: routed and bridge uplink; local ICMPv6, address/route lifecycle, dual-stack sockets, nftables and publication | `build/shroot-image-tests/oci-services-04b7301938c348ad85458cbbacedea6f.json` |
| ubuntu | PASS: routed and bridge, UDP DNS, TCP DNS, verified HTTPS, remote IPv6 source | `build/ipv6-binfmt-boot/oci-services-4dbeeb4aceac4edbb3cd934febf41f12.json` |
| arch / Docker | PASS: nftables, no userland proxy, VFS, cgroup v2; IPv6 TCP/UDP host-address publication, peer IP/names, DNS/HTTPS from both containers, conflicts and released ports | `build/shroot-image-tests/oci-services-f55084f23c3e4036a1ddff141dd841cc.json` |
| arch / Podman | PASS: overlay, stock seccomp/cgroups; IPv6 TCP/UDP host-address publication, named peers, DNS/HTTPS from both containers, conflicts and released ports | `build/podman-results/oci-services-b8cd9497a9e445488e38c39842e2b2ca.json` |

The publication client is native Termux UID 10601, outside Shroot. Its tested
address is `fd6d:6473::64`, not `::1`. These receipts do not certify public
inbound cellular reachability, every distribution's networkd boot, arbitrary
IPv6 destinations, or a different VPN's behavior.

## Packaged Browsers And Portals

Every browser cell includes its packaging runtime: Flatpak/bubblewrap or
snapd/snap-confine, install/update, ordinary-user launch, original sandbox,
input, page rendering, download and normal close/relaunch. Record hardware
composition/WebGL and crashes, not only a renderer string. Portal acceptance
also needs file selection/readback, document grants, FUSE lifetime and cleanup.
Record the packaging backend for each portal result; a Flatpak result does not
certify Snap. Snap's documented degraded mode is not AppArmor enforcement.
Portal lifecycle coverage distinguishes graceful daemon exit, auto-unmount after
daemon death and whole-service restart. The expanded Fedora receipt covers all
three; older graceful-exit receipts do not imply coverage of the other two.

Cell order: **Shroot (lazy SVC) / Base**; N = not run, U = confirmed upstream defect.
Snap is selected on Ubuntu only; `-` marks other distributions outside that scope.

| Recipe ID | Flatpak Chromium | Flatpak Firefox | Snap Chromium | Snap Firefox | Document portal |
| --- | --- | --- | --- | --- | --- |
| debian | P/N | P/N | - | - | U/N |
| ubuntu | N/N | N/N | P/N | P/N | P/N |
| alpine | P/N | P/N | NA/NA | NA/NA | U/N |
| fedora | P/N | P/N | - | - | P/N |
| arch | P/N | P/N | - | - | P/N |
| opensuse | P/N | P/N | - | - | P/N |
| openkylin | P/N | P/N | - | - | P/N |
| deepin | P/N | P/N | - | - | P/N |
| manjaro | P/N | P/N | - | - | U/N |
| void | P/N | P/N | - | - | P/N |
| armtix | P/N | P/N | - | - | P/N |
| nixos | P/N | P/N | - | - | U/N |
| steamos | P/N | P/N | - | - | P/N |

## Desktop And Development

Desktop acceptance includes recipe launch/discovery, input, ordinary clients,
close/reopen and owned-process cleanup. VS Code includes editor/save, external
file changes, integrated terminal, debugger, reload, sandbox and GPU checks.
Distribution-packaged browsers are separate from their Flatpak/Snap versions.
Audio needs audible continuity under load; counters alone are insufficient.
Gamepads need real buttons, axes and reconnect. Steam login is not gameplay.
Steam/game acceptance is selected on SteamOS only; `-` elsewhere does not
assert that games cannot run there. Audio and gamepad coverage remain separate.

Cell order: **Shroot (lazy SVC) / Base**; N = not run.

| Recipe ID | Desktop | Native Chromium | Native Firefox | VS Code | Audio | Gamepad | Steam/game |
| --- | --- | --- | --- | --- | --- | --- | --- |
| debian | I/N | N/N | N/N | P/N | N/N | N/N | - |
| ubuntu | I/N | N/N | N/N | P/N | N/N | N/N | - |
| alpine | I/N | P/N | P/N | U/N | N/N | N/N | - |
| fedora | U/N | N/N | N/N | P/N | N/N | N/N | - |
| arch | I/N | N/N | N/N | U/N | N/N | N/N | - |
| opensuse | I/N | N/N | N/N | P/N | N/N | N/N | - |
| openkylin | I/N | N/N | N/N | P/N | N/N | N/N | - |
| deepin | I/N | N/N | N/N | P/N | N/N | N/N | - |
| manjaro | P/N | F/N | P/N | I/N | I/N | N/N | - |
| void | I/N | N/N | N/N | P/N | N/N | N/N | - |
| armtix | I/N | N/N | N/N | P/N | N/N | N/N | - |
| nixos | I/N | N/N | P/N | P/N | N/N | N/N | - |
| steamos | I/N | N/N | N/N | P/N | H/N | H/N | I/N |

Preserve separate application/package versions in the receipt.
Historical Fedora GNOME devkit/llvmpipe coverage does not certify GDM or hardware
composition. Current Fedora Weston coverage is listed below. See [desktop workflows](desktop-workflows.md) and
[stock-libc acceptance](svc-rewriting.md#desktop-and-container-acceptance).

## Added System Workloads

Implement and validate in this order: **nspawn -> BuildKit -> LXC**. Run the
small LTP selection alongside common runtime changes. A package installation
alone does not satisfy a workload. The machinectl column identifies the outer
OS owning machined and the stock nspawn unit; record the nested OS separately.

Cell order: **Shroot (lazy SVC) / Base**; N = not run.

| Recipe ID | nspawn command | machinectl boot/lifecycle | BuildKit | LXC | LTP core |
| --- | --- | --- | --- | --- | --- |
| debian | P/P | P/N | P/N | P/N | P/N |
| ubuntu | P/P | P/N | P/N | P/N | P/N |
| alpine | NA/NA | NA/NA | P/N | P/N | P/N |
| fedora | P/P | P/N | P/N | P/N | P/N |
| arch | P/P | P/N | P/N | P/N | P/N |
| opensuse | P/P | P/N | P/N | P/N | P/N |
| openkylin | P/P | P/P | P/N | P/N | P/P |
| deepin | P/P | P/N | P/N | P/N | P/N |
| manjaro | P/P | P/N | P/N | P/N | P/N |
| void | N/N | N/N | P/N | P/N | P/N |
| armtix | N/N | N/N | P/N | P/N | P/N |
| nixos | P/P | P/N | P/N | P/N | P/N |
| steamos | P/P | P/N | P/N | P/N | P/N |

The command-mode results have separate [per-distribution receipts](distribution-evidence.md#nspawn-command-mode).

### Workload Contracts

- [nspawn](https://www.freedesktop.org/software/systemd/man/latest/systemd-nspawn.html):
  boot a nested distribution, use machinectl shell/service lifecycle, check
  private PID/mount/user namespaces and `/dev`/`/proc`, bind permissions, veth
  bridge networking and TCP/UDP publication. Keep command-only nspawn separate
  from the machinectl/systemd service workflow; do not replace OpenRC/runit
  merely to mark a distribution as passed.
- [BuildKit](https://github.com/moby/buildkit): build a multistage image, run
  parallel steps, verify cache reuse and invalidation, cache/secret mounts,
  exported-image execution, cancellation and cleanup. Keep OCI and containerd
  workers and guest-root/rootless configurations as separate subcases.
- [LXC](https://linuxcontainers.org/lxc/introduction/): create/start a container,
  attach/exec, verify credentials, mounts, `/dev`/`/proc`, cgroup interactions,
  original seccomp policy, bridge connectivity, restart, shutdown and destroy.
  Privileged/unprivileged refers to guest credentials, never Android elevation.
- For all three, exercise abnormal termination and repeat the same scenario.
  Verify no owned processes, mounts, sockets or reservations remain. Record
  exact missing operations; do not replace bridge with host networking, disable
  application protections, or report permissive cgroups as resource enforcement.

### Permanent LTP Selection

Start with these seven upstream test binaries, not the complete `syscalls`
suite. Pin and review the upstream source and harness before the first run;
record the revision and compiled hashes. Re-review before updating that pin.
Upstream supports [individual test execution](https://linux-test-project.readthedocs.io/en/latest/users/quick_start.html#running-single-tests).

Current reviewed pin: LTP `20250930`, commit
`d2550ffbbcfe163212cd7e9c132db65ae0fa06ed`. The official
`ltp-full-20250930.tar.xz` SHA-256 is
`048fa4d69ddbe8a94aa15da9bdc85713ab07a0abbc3de2b8bdd9757644aef1e4`.
Build only `lib/libltp.a` and the seven targets below with the distribution's
compiler. `test_booted_ltp.py` runs each once as the specified ordinary guest
user in a disposable working directory, records source/library/binary hashes,
and preserves every upstream status. Its 45-second outer failure bound kills
only that test's process group; it does not enable stress-loop options.

| Test | Initial contract |
| --- | --- |
| `dup01` | Duplicated descriptor refers to the same inode |
| `dup201` | Invalid dup2 descriptors return EBADF |
| `openat01` | Relative/absolute paths, directory FDs and error returns |
| `pipe01` | Pipe data write/readback and descriptor cleanup |
| `epoll_create1_01` | Native epoll creation, CLOEXEC policy and close on the ARM64 syscall ABI |
| `sigprocmask01` | Block, observe pending signal, unblock and deliver |
| `getuid01` | Basic guest UID syscall result; not full credential isolation |

Run bounded single iterations in test-only stores as an ordinary guest user,
with disposable temporary directories and inherited host limits. Do not expose
Android block devices, writable host proc/sys/sysfs or unrelated directories.
Exclude filesystem formatting, host-wide sysctls, time changes, module loading,
reboots and unbounded resource exhaustion. Mount/xattr and deeper
namespace/credential suites need separately reviewed fixtures; existing Shroot
tests retain that coverage and are not replaced by this small selection.

Retain every upstream subtest and status: TCONF/skip is uncovered, TBROK is a
broken run, and neither is a pass. Keep warnings and cleanup failures visible.
Compare failures with the same binaries and fixtures on a real Linux control
(a disposable VM is acceptable); native Android and Shroot Base alone are not
oracles for complete Linux behavior.

## Evidence And Open Work

This ledger retains current acceptance evidence and unresolved blockers, not
the history of repaired defects. Replace a failed status after a qualifying
rerun and remove its resolved explanation. Regression coverage belongs in the
tests. Local report paths identify evidence, not durable public downloads.
An older failure with an unknown cause does not by itself block a passing
status after qualifying reruns of the same scenario; retain its logs in run
artifacts, not as an active defect unless it recurs.

User-executor coverage uses existing recipe stores through the borrowed-store
fixture rather than creating copies. Per-distribution scope and remaining
failures are recorded in [host execution](host-execution.md#qualifying-evidence),
including init/service lifecycle, containers and virtual machines, k3s,
packaged browsers/portals, VS Code and recipe desktop workflows.
Keep User evidence separate from the Shell results above. Shared runtime
fixes require focused Shell regressions, not an implicit rerun or promotion of
the entire Shell matrix.

Detailed receipts are grouped in [Distribution acceptance evidence](distribution-evidence.md):

| Distribution | Evidence |
| --- | --- |
| Debian | [Debian scenarios](distribution-evidence.md#debian) |
| Ubuntu | [Ubuntu scenarios](distribution-evidence.md#ubuntu) |
| Alpine | [Alpine scenarios](distribution-evidence.md#alpine) |
| Fedora | [Fedora scenarios](distribution-evidence.md#fedora) |
| Arch | [Arch scenarios](distribution-evidence.md#arch) |
| openSUSE | [openSUSE scenarios](distribution-evidence.md#opensuse) |
| openKylin | [openKylin scenarios](distribution-evidence.md#openkylin) |
| Deepin | [Deepin scenarios](distribution-evidence.md#deepin) |
| Manjaro | [Manjaro scenarios](distribution-evidence.md#manjaro) |
| Void | [Void scenarios](distribution-evidence.md#void) |
| Armtix | [Armtix scenarios](distribution-evidence.md#armtix) |
| SteamOS | [SteamOS scenarios](distribution-evidence.md#steamos) |
| NixOS | [NixOS scenarios](distribution-evidence.md#nixos) and [recipe scope](#nixos-recipe-and-initial-image-probes) |
| Shared checks | [Cross-distribution fixtures](distribution-evidence.md#shared-evidence) |

Machinectl evidence does not cover nspawn command-mode bridge/publication or
external DNS. Nested shutdown reports `/tmp: not mounted` for a locked inherited
mount; retain that diagnostic without weakening mount restrictions.

Test contracts and reproduction instructions are in the [init matrix](init-matrix.md),
[desktop workflows](desktop-workflows.md) and
[SVC acceptance](svc-rewriting.md#desktop-and-container-acceptance). Reconcile
exact receipts and scope before replacing H with P.

Every receipt must identify recipe/source, installer configuration, package
versions, APK/native hashes, device/kernel/page size, Android/guest identities,
entry mode, command, assertions and cleanup. Keep backend choices and prerequisites
as subcases, not a single green result for an entire OS. Never publish retained
`session.json` files: they contain control tokens.
