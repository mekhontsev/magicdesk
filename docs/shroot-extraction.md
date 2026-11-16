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
with bundled/system SQLite, and Android cross builds on Linux and Windows.
Third-party dependencies are static by default; a qualified external SQLite
provider is optional. The image utility uses dynamic host libc for DNS, while
default execution helpers retain static linking.

Native component, storage, image, registry and relocated-bundle checks run
without MagicDesk or Java. Shared resource, guest, headless-browser and OCI
service runners also have a direct local executor. MagicDesk launch identity,
MCP and log/file transport live in an optional adapter; assertions remain shared.
Local qualification covers Android execution from Termux and Raspberry Pi 5
under an ordinary Linux UID, without capabilities. On Debian 13 with kernel
6.18.50+rpt-rpi-2712 and 16 KiB pages, the bundled/static Clang build passes all
78 native checks; GCC with system SQLite and dynamic helpers passes all 77
applicable checks. Android passes the shared 78-test set. Stock Alpine and Debian
run on the Pi in Base and lazy-SVC modes with either build, including hardlinks
and file IO; package-manager checks cover `apk update` and `apt-get update`.
This does not replace qualification under MagicDesk's application domain and
Shell, or a full GUI/container matrix on Linux. No existing MagicDesk
distribution has been converted as part of extraction.

## Remaining Delivery

1. Qualify additional prepared-libc/application workloads through the direct
   executor and the extracted MagicDesk adapter. Graphical/installer checks
   explicitly retain their MagicDesk dependencies. Keep shared assertions and
   executor-specific capability evidence; local tests do not certify APK delivery.
   Audit optional fixture builders and their documented commands as well: some
   still assume the former `native/shroot`/`native/guest-exec-lab` layout or the
   MagicDesk prepared-rootfs build. The standalone CTest build is independent.
2. Publish the independent source/build contract and CI artifacts, then pin that
   backend in MagicDesk. Replace Java catalog/download ownership with the native
   API and remove the duplicated engine without changing installed identities.
3. Finish autonomous Linux APK packaging, private runtime/data ownership,
   authorized optional connections and cross-package denial tests.
4. Qualify the pinned integration under User and Shell, including storage-update
   continuity, GUI/services and matched performance checks. Extend ordinary
   ARM64 Linux coverage to the selected application workloads; nested Shroot
   and a host-runtime build against musl remain deferred.

Separate production archives without test hooks pass local Android and Pi
API/CLI execution and the common checksum/layout/ELF audit. The Pi also builds
and runs an external CMake consumer against a relocated installed bundle.
Hosted CI execution and publication remain separate acceptance requirements;
the workflow definition alone does not establish those results.

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
