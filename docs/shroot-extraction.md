# Standalone Shroot Delivery

## Scope

Shroot becomes an independently versioned native project for Android ARM64 and
ordinary ARM64 Linux. Neither its runtime nor environment management requires
Java, ART, app_process or an installed MagicDesk APK. Python test runners and
shell recipes are development/integration tools, not runtime prerequisites.

MagicDesk consumes the same public backend as third-party applications. Separate
Linux application packages own their runtime, private storage and Android UID;
their runtime and data remain usable without MagicDesk. MagicDesk supplies
optional terminal, graphics, audio and authorized content connections. Android's
application sandbox is the outer security boundary, not Shroot's logical guest
credentials. Shared mutable storage and implicit privileged command channels
must not cross that boundary.

## Implementation Blocks

1. Extract native sources and common tests with their history into the Shroot
   repository. Establish a self-contained CMake build, explicit external wire
   contracts, independent version metadata and reproducible Android/Linux bundles.
2. Move environment/catalog/OCI management from Java into the native backend,
   retaining the existing import, filesystem, lifecycle and publication owners.
   Provide `libshroot` as a native C management API and one CLI using it, with
   structured results. Keep the syscall adapter, supervisor and process-wide
   worker setup outside the embedding application. Preserve storage family
   17, environment identities, source dependencies and exact live helper bundles.
3. Make MagicDesk consume a pinned backend through that public contract. Keep
   Android authorization, PTYs, presentation and attachment grants in adapters;
   remove replaced Java management, without retaining a second implementation.
4. Extend application packaging for autonomous Linux environments. Keep package
   identity, data ownership, native runtime, updates and authorized connections
   explicit. Test two independent packages and denied cross-package access.
5. Qualify standalone Android, ordinary ARM64 Linux and MagicDesk integration
   through shared tests. Run Raspberry Pi 5 checks when available, recording the
   actual kernel and page size. Build/launch independence is required; a whole
   duplicate distribution matrix is not.

Commit completed, verified blocks rather than incremental staging changes.
Keep source relocation/NFC changes separate from behavioral fixes. No new major
syscall subsystem is part of this work; repair defects exposed by extraction and
portability through their existing owners.

## Current Boundary

The independent native checkout provides a C management API and CLI, named
environment catalogs, public HTTPS OCI registry installation, shared-layer
imports, launch/stop and portable backup/restore. Its versioned install bundle
contains runtime metadata, libraries, headers, dependency licenses and checksums.
An independent CI workflow covers client-only consumers, native ARM64 Linux
with bundled/system SQLite, and Android cross builds on Linux.
Third-party dependencies are static by default; a qualified external SQLite
provider is optional. The image utility uses dynamic host libc for DNS, while
default execution helpers retain static linking.

Native component, storage, image, registry and relocated-bundle checks run
without MagicDesk or Java. Shared resource, guest, headless-browser and OCI
service runners also have a direct local executor. MagicDesk launch identity,
MCP and log/file transport live in an optional adapter; assertions remain shared.
Local qualification covers Android execution from Termux and Raspberry Pi 5
under an ordinary Linux UID, without capabilities. On Debian 13 with kernel
6.18.60+rpt-rpi-2712 and 16 KiB pages, GCC with system SQLite and dynamic helpers
passes all 82 applicable checks. Android passes the shared 83-test set with
Clang and bundled static dependencies. Stock Alpine and Debian
run on the Pi in Base and lazy-SVC modes with either build, including hardlinks
and file IO; package-manager checks cover `apk update` and `apt-get update`.
Stock k3s qualification covers CoreDNS, cross-pod traffic, network policies,
Traefik ingress, scaling, persistent volumes and server restart. Two full runs
pass with the unmodified ten-second `kubectl exec` bound.

MagicDesk consumes the pinned `native/shroot` submodule. Its catalog adapter
invokes the native CLI rather than implementing a second OCI/catalog manager.
Application-domain and Shell acceptance cover install, metadata, DNS changes,
backup/restore, concurrent launches, targeted cancellation and removal. Existing
installed identities are retained without conversion or distribution copies.
The shared prepared-userspace runner passes 79 User and 70 Shell checks,
including package transactions, signals, descriptor transfer, process-tree
cancellation and crash recovery. User additionally verifies admission of the
exact immutable executables packaged in the APK. Native hardlink and pathname
socket controls retain the selected identity's actual host restrictions.
Graphical-session checks pass under User and Shell for X11 and Wayland, including
D-Bus, launch arguments, environment, process exit and cleanup. Device checks cover
cross-UID socket admission, descriptor handoff, scope revocation and peer cleanup;
audio checks cover concurrent leases and User, Shell and Termux clients. These
results do not substitute for acceptance of the generated autonomous applications.

Shared fixture contracts belong to Shroot's `tests/`, including the executor,
kernel-profile, borrowed-store, matrix and benchmark tests. Android-specific
transport explicitly receives a MagicDesk checkout. Its file adapter transfers
inert bytes under the selected app identity without `run-as`; it never replaces
the real Zygote-launched fixture process. This loopback file adapter requires
the runner on the same device.

The bundled mksh retains upstream sources and persistent history. Verification
covers concurrent consoles, restart and history compaction. Runtime and shell
changes remain separate from MagicDesk's terminal presentation.

## Remaining Delivery

1. Finish autonomous Linux APK acceptance: private runtime/data ownership,
   two independent package identities, updates, owner cancellation, operation
   without MagicDesk and authorized optional connections. The APK template and
   streamed signing/packaging are implemented; device results are still required.
2. Qualify the final pinned integration under User and Shell, including the
   storage-update continuity and matched performance checks. Shared assertions retain executor-specific capability
   evidence. Nested Shroot and a host-runtime build against musl remain deferred.
3. Complete final source/license audits, successful CI on the exact published
   Shroot and MagicDesk commits, and delivery documentation. Linux and
   Android/Termux are supported build hosts; Windows uses WSL2, not native builds.

Separate production archives without test hooks pass local Android and Pi
API/CLI execution and the common checksum/layout/ELF audit. The Pi also builds
and runs an external CMake consumer against a relocated installed bundle.
The current published backend passes all five hosted jobs: Android bundle,
Linux GCC with system SQLite, Linux Clang with bundled SQLite, and standalone
GCC/Clang client consumers. Subsequent shared-fixture changes and the final
MagicDesk integration still need CI on their exact published commits.

## Acceptance

- A standalone bundle builds and runs without an Android application project.
- Import, inspect, launch, stop, backup/restore and cleanup work without ART.
- Existing MagicDesk stores remain usable without reinstalling distributions.
- Android app and shell execution retain their capabilities and fast paths.
- Independent application packages retain working runtime/data without MagicDesk
  and cannot access another package's private store or privileged services.
- Linux host tests run as an ordinary user, without changing kernel policy to
  fabricate a pass. ARM64 and the existing Linux 5.15 capability contract remain
  explicit; another CPU architecture is not implied.
- Protocol/API errors, unknown storage revisions and unavailable integrations
  fail explicitly. Existing launches keep their exact runtime through updates.
- Performance and release packaging are measured on uninstrumented artifacts.

This document owns the current delivery boundaries until the standalone project's
maintained contracts replace it; run logs and experiment history stay in build
artifacts.
