# Shroot Performance: Alpine

Measured on 2026-10-26 on one Android phone. These results compare execution
environments, not equivalent isolation or a universal speed ranking.
Lower elapsed time is better; ratios use **chroot = 1.000x**.

## README Comparison

The five README rows divide each mode's median by chroot's median from the same
series. They are not one simultaneous run of all workloads.

| Workload | chroot | PRoot | Shroot |
| --- | ---: | ---: | ---: |
| 16,384 `stat` calls | 8.11 ms | 510.31 ms | 41.46 ms |
| 16,384 `open/close` pairs | 18.32 ms | 877.82 ms | 133.61 ms |
| 16,384 create/close/rename/unlink cycles | 251.12 ms | 2,959.39 ms | 828.75 ms |
| 128 `fork/exec/wait` cycles | 131.37 ms | 718.45 ms | 1,100.13 ms |
| SQLite CLI and shared-library build | 37.87 s | 38.02 s | 39.74 s |

- File rows: five rotating measured rounds, one excluded warmup per mode,
  CPU 0-1 and verified `nice=0`. All sixteen 1,024-operation blocks count.
  [Samples, frequencies and exact native hashes](benchmarks/alpine-compact-alias-2026-10-26.json).
- Exec and SQLite: normal two-thread runtime at `77fa1a89`, no CPU pinning,
  all eight CPUs available, verified `nice=0`. Exec uses seven measured rounds
  with packaged `/bin/true`; SQLite uses three clean `make -j8` builds per mode.
  Both rotate launch order and exclude one warmup per mode.
  [Samples, hashes and frequency observations](benchmarks/alpine-unrestricted-exec-sqlite-2026-10-26.json).

All workload checks passed; the five SQLite output hashes matched across the
measured builds. Shroot uses default lazy SVC entry and stock distribution
libraries, with runtime statistics disabled. The native hashes identify staged
test bundles, not the installed APK.

The file-cycle result is 51.80 ms per 1,024 cycles, with no payload writes or
durable `fsync`. These microbenchmarks do not predict GUI/input latency.
Shroot is faster than PRoot in these file loops, but remains slower in process
startup and compilation. There is no aggregate score combining unrelated tests.

## Additional Workloads

The same five-round file series also includes:

| Workload | chroot | PRoot | Shroot |
| --- | ---: | ---: | ---: |
| 4,096 metadata/open/read checks | 16.93 ms | 478.92 ms | 141.65 ms |
| 1,000,000 ready `ppoll` calls | 274.01 ms | 295.16 ms | 323.91 ms |
| 1,000,000 ready `epoll_wait` calls | 294.04 ms | 318.21 ms | 331.39 ms |
| 100,000 memory-operation iterations | 407.02 ms | 352.71 ms | 453.56 ms |

The optimization target is `T_shroot <= sqrt(T_chroot * T_proot)` for each
workload, not an aggregate. Meeting it does not excuse a regression in the
three file loops, application correctness or desktop responsiveness. Compare
candidate and reference bundles interleaved on the same store; fewer traced
stops alone do not establish a speedup.

## Method

- Device: nubia NX809J, Android 16/API 36, Linux 6.12.23, AArch64.
- Alpine 3.23.6, GCC 15.2.0-r2, musl 1.2.5-r24, binutils 2.45.1-r0,
  Make 4.4.1-r3 and GNU time 1.9-r1.
- Termux PRoot package: 5.1.107.92, seccomp accelerator enabled, no fake-root
  extension. It runs as the Termux UID.
- Native chroot uses host root. Shroot uses host shell UID 2000 and guest UID 0.
  Root is only a requirement for the native baseline. Android scheduling groups
  and SELinux domains differ between executor identities.
- Each series records its effective affinity, priority and power source.
  Exec/SQLite ran while charging. Governors and frequency limits were unchanged.
  SteamOS/Steam/DDNet were stopped and the phone stayed awake.
- No samples are discarded. Warmed page caches are retained; no global cache
  dropping or compiler cache is used.
- Monotonic microbenchmark timing excludes environment launch, fixture
  compilation, setup and cleanup. Compilation timing surrounds `make`, not
  source download or environment setup.

CPU frequency is not locked. Each JSON retains policy-wide residency-weighted
frequency, before/after limits and thermal observations. These cover the
command envelope and other tasks sharing the policy, not each benchmark
thread's frequency. Do not normalize elapsed time as if all work scaled
linearly with frequency. Background Android work and thermal/DVFS variation
remain possible.

## Reproduction

Use [benchmark_compile.py](../native/guest-exec-lab/benchmark_compile.py) and an
independent build-only sysroot, not a running user's desktop environment.
[Developer workflows](../native/guest-exec-lab/developer-workflows.md) describe
preparation, identity requirements and diagnostic counters.

The source is the official
[Alpine 3.23.6 AArch64 minirootfs](https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/alpine-minirootfs-3.23.6-aarch64.tar.gz),
SHA-256 `b17a57958e29735ff0e6e64254d958e65903687a70ca30cc430b33a965ad49d7`.
Install `build-base time coreutils` with stock `apk` and signature verification.
Record rootfs and package versions beside `sysroot/`; import through Shroot's
image service with hardlinks preserved and unmodified libraries/compiler.

To reproduce the file-series configuration:

```sh
python native/guest-exec-lab/benchmark_compile.py WORK_DIRECTORY \
  --sqlite SQLITE_AMALGAMATION_DIRECTORY --runtime NATIVE_BUNDLE \
  --cpu-mask 3 --workload file-stat --iterations 1024 --file-blocks 16 \
  --rounds 5 --warmups 1
```

Reuse the reported store with `--guest-store STORE` for subsequent workloads.
Repeat for `file-open`, `file-cycle` and `metadata`; use
`--iterations 1000000` for `poll`/`epoll`. The JSON `guest` mode denotes
ordinary accelerated Shroot, not Shroot Base. For unrestricted exec use
`--workload spawn --iterations 128 --rounds 7` without `--cpu-mask`.

For the SQLite series use `--workload compile --jobs 8 --rounds 3` without
`--cpu-mask`. Source:
[SQLite 3.51.3 amalgamation](https://www.sqlite.org/2026/sqlite-amalgamation-3510300.zip),
archive SHA-256 `acb1e6f5d832484bf6d32b681e858c38add8b2acdfd42ac5df24b8afb46552b4`.
Each sample removes objects and builds the CLI and PIC shared library with
`-O3 -flto` and a fixed random seed. SQL execution, shared-library execution and
byte-identical hashes of all five generated artifacts are required.

Compare only matching operation counts, CPUs, libraries, storage and timing
boundaries. Retain full samples and hashes in run artifacts; this guide records
the published comparison and its reproduction method, not an optimization diary.
