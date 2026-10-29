# Cooperative Entry Fixtures

[Fixture guide](README.md) | [Runtime contract](../shroot/cooperative-entry.md) |
[Lazy SVC entry](svc-rewriting.md)

Shroot uses lazy entry in stock programs by default. Shroot Base uses ordinary
interception. Neither replaces package-owned libc files. The internal caller
boundary uses the version declared by `MD_ENTRY_VERSION` in
[`cooperative_entry.h`](../shroot/src/cooperative_entry.h); explicit API fixtures
exercise it independently of
machine-code discovery.

## Runtime Checks

Configure `MAGICDESK_GUEST_FIXTURES=ON` as described in the fixture guide:

```sh
python native/guest-exec-lab/test_cooperative_runtime.py BUILD --base
python native/guest-exec-lab/test_cooperative_runtime.py BUILD --machine-entry
python native/guest-exec-lab/test_resources.py BUILD
python native/guest-exec-lab/test_watch_runtime.py BUILD --contracts
```

The entry suite covers argv/environment layout, protected copies, unavailable
providers, nested entry, slot exhaustion, native FD plans, cancellation,
fork/exec, watched mutations and JIT repair with active peers. Its broker test
holds the descriptor-table owner and requires interruption to become visible
before releasing that owner, detecting a real ownership cycle rather than
relying on a longer timeout.
It also forces channel registration to reply before the supervisor retires its
delegated operation. A next request published during that interval must wake the
parked worker when retirement makes the task available, without another guest
call. This checks the readiness handoff independently of thread timing.

Shared model tests cover filesystem publication, credentials, mount branches,
network rules, FD provenance, request lifetimes and buffer ownership. The watch
suite also executes the queue, raw-read and activation controls through the
current build. Ordinary reads must retain selective interception.

## Stock Libc Workloads

`test_cooperative_libc.c` is an ordinary workload, not a libc patch. Compile it
against an existing distribution sysroot:

```sh
python native/guest-exec-lab/build_svc_glibc.py STOCK_SYSROOT OUTPUT --environment ubuntu
python native/guest-exec-lab/test_svc_libc.py BUILD OUTPUT/probe-stock --checks-only
```

The builder compiles only the probe. Dynamic checks require `--dynamic-loader`,
`--dynamic-libc` and each required `--dynamic-extra` from the same sysroot.
Stock musl needs only the workload compiled against musl. Tests hash staged
runtime and workload files and require unmodified distribution libraries.

`test_svc_libc.py` checks static/dynamic libc, thread retirement, fork/exec and
real filters; `--signal-storm` adds asynchronous signal delivery. `--checks-only`
omits timing. A successful libc workload is not full runtime or GUI coverage.

For broader measurements use `test_cooperative_libc.py BUILD STOCK_FIXTURE`.
Its fixture directory contains `probe-static`, `probe-dynamic` and `sysroot/lib`;
select `--libc-kind glibc` for a glibc fixture. Its options include:

- `--rounds N --scale N --workloads ...` for workload families.
- `--reference-build BUILD --reference-kernel` for interleaved candidate and
  reference runtimes, both with lazy entry and explicit Base.
- `--proot-prefix PREFIX` for the same-shell-UID PRoot control.
- `--sandbox` for actual argument-dependent kernel filters around timed calls.
- `--directory-mode logical|imported` for distinct directory representations.
- `--condition TEXT` for charging or concurrent activity.
- `--profile` for separate diagnostics. `--sample-statistics` instruments every
  timed sample and must not be mixed with uninstrumented timings.

Run `--help` for workload names and fixture options. File cycles, open/close,
metadata and traversal measure different paths and need separate results.

## Measurement Discipline

Measure after compilation on an otherwise idle device. Record charging,
temperature, CPU affinity, executor UID, kernel, runtime/workload hashes,
directory representation and application filter state. Retain failures and
outliers. Balance execution order over complete rotation/reversal sets and use
paired ratios, not just ratios of independently displayed medians. CPU affinity
is a diagnostic control, not a production scheduling policy.

Confirm actual entry completions separately from timing. A probe silently
falling back to Base does not establish accelerated throughput. Sandboxes revoke
lazy admission; their results measure the supported original-SVC fallback.
Counter collection adds work to hot paths and is not enabled for normal timings.

### Handoff And Startup

`benchmark_readiness.py --build BUILD --reference REFERENCE_BUILD --store STORE
--file-blocks 16 --workload file-stat --workload file-open` interleaves Base and
lazy entry with the same guest ELF. Each launch retains all consecutive
1024-operation blocks, caller CPU time, voluntary/involuntary context switches
and caller CPU endpoints. Output is deferred until the series ends. The first
block follows fixture initialization; it is not a filesystem-cache-cold test.
Full launch time and the last-half block median remain separate measurements.
Frequency observations cover host cpufreq policies across each command, not the
instantaneous frequency of every worker or caller instruction.

Compare unrestricted execution, a shared multi-CPU mask and a single-CPU mask.
A shared mask does not prove simultaneous execution on separate CPUs. Sustained
per-call context switches can distinguish a parked handoff regime from an
ordinary frequency change. A shorter cooldown or longer spin can help that
regime while regressing single-CPU execution or a different cold-start workload.
Check CPU cost and both metadata and FD plans before accepting such tuning;
neither a good warmed median nor disabling the optional synchronous seccomp
wake hint alone proves that cold-start instability is fixed.

## Correctness Boundaries

| Boundary | Required checks |
| --- | --- |
| Admission | Unsupported calls, unavailable provider, nested entry, fork/exec, slot exhaustion and pre-effect fallback |
| Caller memory | Invalid and guard-page paths, partial output, protected callers and real signal context |
| Effects | Cancellation before/after native entry, no mutation replay, exact publication and result retirement |
| FD ownership | EMFILE, close/reuse, dup/fcntl, shared tables, retained/unlinked objects, COW and mount policy |
| Filesystem | Credentials/umask, readonly mounts, DAC/Landlock, directory cookies, watches and durable publication |
| Libc | Static/dynamic glibc and musl, cancellation/unwinding, FIFO signals and unmodified userspace |
| Sandboxes | Original-site application filters and stock Chromium without disabling its sandbox |

Retain these constraints when considering new optimizations:

- A shared worker FD number is not guest ownership. Real publication and exact
  lifetime must precede application use.
- A whole-loop read lease blocks writers. Production operation windows remain
  bounded and release before parking or incompatible work.
- A host pathname is not stable authority across rename, unlink and mounts.
  Native plans retain exact object pins.
- Cancellation before a kernel effect can decline to Base; completed or unknown
  effects must not be replayed.
- Fewer stops do not establish lower elapsed time. Compare complete interleaved
  runs with the same workload, not the best individual samples.
- Kernel version or a declared flag does not prove firmware semantics. Keep
  capability failures and fallbacks in the [kernel ledger](../shroot/kernel-compatibility.md).
