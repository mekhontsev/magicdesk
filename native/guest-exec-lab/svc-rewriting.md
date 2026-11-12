# Lazy SVC Entry

This guide describes the machine-entry contract and its regression fixtures.

- [Product Boundary](#product-boundary)
- [Regression Commands](#regression-commands)
- [Task-Channel Acceptance](#task-channel-acceptance)
- [Admission Limits](#admission-limits)

## Product Boundary

The ordinary bootstrap/supervisor enables lazy entry by default for inode-store
launches. `--env SHROOT_MACHINE_ENTRY=0` selects Base. It does not launch a
discovery debugger. Bootstrap allocates private frames and near-code pools;
`interception_memory` owns the registry across CLONE_VM, fork and exec.
Reachable code pools grow on demand: the memory owner borrows the intercepted
task for an RX anonymous `MAP_FIXED_NOREPLACE` allocation in an observed free gap.
It restores the original stop before publishing code, without stopping peers or
replacing an application's mapping. The pool inventory and broker snapshot grow
with the address space; there is no fixed pool-count limit. Cold notification
and TRACE entries use the same allocator. The distant-site fixture requires
first-call publication in 24 disjoint regions, with argument-sensitive seccomp
and fork, rather than accepting successful Base execution as evidence.
First observed execution can publish a single SVC replacement without a peer
stop, predecessor witness or hot-site threshold. Observed syscall numbers at
one instruction share a chain of immutable guarded entries.
For syscall numbers representable by an unsigned 12-bit immediate, the guard
subtracts that number from x8 without changing NZCV and branches on the full
64-bit result. A mismatch adds the number back before following the next guard;
the matching body restores the known number through its existing replay slots.
Signal recovery reverses the subtraction at the three intervening instruction
boundaries. Larger numbers retain bit-test guards. Neither path borrows the
guest stack or a second register, and publication still changes one instruction.
The instruction-boundary suite covers matching calls, mismatches and identical
low 32 bits with different high bits, including signal-edited contexts.
Unpublished gate bodies are staged with a block write through `/proc/PID/mem`,
with word-sized ptrace fallback when unavailable. Only the final source
instruction uses the existing atomic publication path; no partial gate becomes
reachable through a published branch. This reduces cold startup work without
changing the warm entry path or making proc-write permission mandatory.

A newly published worker/FD/value or admitted task entry can also serve the
first, unexecuted call. A cancelled notification already owns a post-kernel continuation; a
TRACE stop cancels its syscall and restores the complete context at exit before
entering the new gate. Existing gates and retained interrupted results keep
their ordinary completion path, so a pre-execution decline cannot loop back
through the same gate. Publication is independent of one invocation's fast-path
eligibility: the common entry selects execution from the current number,
arguments, policy and resource ownership. Site-discovery coverage includes all
first calls, not only later warmed iterations. The first-encounter task fixture
also asserts actual task-entry completions, including invalid output pointers
and a guest filter denying selected arguments.
The task-signal fixture creates, verifies and removes 512 files under a 100 us
signal timer in Base and lazy modes. Exclusive creation detects any repeated
side effect after an unadmitted BEGIN or an interrupted completion.
Channel expansion also runs with thread-directed signals, both with and without
a guest filter. Handlers check that their interrupted SP belongs to the
application stack, not a private helper stack. A quiet tail separately verifies
accelerated completion counts after signal recovery.

`svc_machine.c` owns logical signal contexts, deferred siginfo, checkpoint return
and necessary replay at the original instruction. It uses the existing interception
filter, not a filter per pool or exec. Broker notifications from resident gates
return to this owner rather than being sampled as guest code. Machine entry
uses the common per-thread channel and channel reentry guard; explicit API
fixtures exercise the same transport independently of patch discovery.
Memory changes retire overlapping entry points; resident continuations remain
until address-space teardown. Debugger ownership disables machine admission.

The common ingress evaluates the guest policy before selecting execution.
Its local routing view uses the same base, FD, PID and signal program generators
as kernel interception. Known native operations, direct errors and unconditional
context requirements need no preliminary supervisor request. Shared-state
operations reuse the existing resource owners over the task channel. A context
request names its reason and precedes effects; an in-progress operation retains
its completion owner instead of restarting from that request.
Owned service gates precede the kernel guest-policy body, so an admitted helper
does not execute it again. Original-site fallback and Android's inherited filters
remain authoritative where local admission is unavailable.

A TRACE entry at a published site's native alias can use the same
Base syscall owner even when its number is new to that chain. Discovery uses
the authenticated original PC and actual arguments; it may publish another
gate for subsequent calls without replaying the current syscall. Publication
accepts only the exact original SVC or the current owned branch, stages the
new gate first, and retains all earlier continuations. Restoring a suspended
branch never implicitly admits another number. Memory profitability checks
therefore apply equally to initial discovery, alias discovery and signal retry.
Application filters, exposed address spaces and debugger ownership retain
their original recovery and admission boundaries.
For an admitted policy-mirror gate, the shared interception classifier restores
the ordinary native or TRACE route after checking the original IP and arguments.
The alias already has the caller's stack, so it needs no cancellation/replay
solely because the mirror supplied a service cookie. Notification/error routes,
filter installation and guest observation policies retain original-site recovery.

Seccomp stops share one register snapshot between checkpoint recovery, executed
site discovery and ordinary dispatch. Discovery precedes compact Base handlers:
an inexpensive first `close` must not prevent later calls from using lazy entry.
The `--case fd-discovery` cooperative fixture uses ordinary SVC instructions,
not the explicit entry API, and checks close/dup/fcntl/lseek completion counters
as well as descriptor flags and EBADF. Base runs assert that these counters stay
zero. Calls which already go straight to the kernel need no discovery stop.

Native memory observation uses the number, PC, SP and six arguments already
returned by `PTRACE_GET_SYSCALL_INFO`, including an exact unfiltered native
alias outside private stacks. It captures full GPRs only before rewriting an
error or entering a task-affine adapter. Exit observation still owns mapping
publication, backing provenance and repair. Resident execution, filters and
debuggers retain full-context recovery; compact operands are never restored as
an application's register image. Supervisor-state and mapping/signal tests cover
these boundaries.

Terminal native-wait coverage runs the same fixture natively, under Base and
under lazy entry. It checks original PC/SP and arguments at signal delivery,
EINTR, SA_RESTART, nonlocal handler exit, subsequent frame reuse and an already
blocked read across TSYNC. The admitted read must finish while the next read
obeys the new denial. Planner checks also collide a claim with an active native
journal, then verify policy draining and fork cleanup. The resource wait fixture
requires repeated filtered poll/select to complete without per-call stops.

Private untracked `munmap` uses the same discovery ordering and an admitted
native-memory callback. The sole address-space owner validates a snapshot of
excluded mappings; it does not send a request to the filesystem worker. Shared,
exposed or tracked cases retain ordinary interception; eligible filtered calls
also check their original policy before the native effect. The mapping
fixture verifies actual accelerated completions, page-rounded unmapping,
misaligned-address rejection, signal-handler reentry and a subsequent seccomp
denial. The ownership and revocation contract is described in
[cooperative entry](../shroot/cooperative-entry.md#native-memory-effects).

Broker discovery uses a bounded recent-address cache only to suppress repeated
cold attempts. A cache miss always delegates, evicting an older address if
needed; cache capacity is not an admission limit for new SVCs. An evicted,
unpatchable site can be sampled again. The supervisor registry separately owns
patch eligibility and resident code capacity. `--case site-discovery` checks
32 executed sites; the broker fixture also checks warm suppression, eviction,
exact-address resampling and fresh discovery after task-record reuse without
installing any patches. Eligibility refreshes retain the same owner's cache.

Recovery of an unexecuted entry restores its original SVC before delivering a
deferred signal. The retry observer republishes only after that syscall enters
the kernel. A handler's unrelated syscall leaves the site suspended until later
observed execution, and its stop still reaches the normal syscall owner. This
invalidates that address's cold broker sample for the sharing address space,
so subsequent notification calls can republish it instead of remaining on Base.
This prevents signal storms from repeatedly cancelling the same native plan or
consuming an adapter's cancellation acknowledgement. It requires neither peer
stops nor a timer/backoff on ordinary calls.

Standard asynchronous signals share the Base adapter's queue and exact-siginfo
delivery protocol. They never acquire replay carriers, which can coalesce with
real arrivals and leave a blocked SIGTRAP absent from the kernel pending set.
Realtime instances retain ordered replay: an earlier held instance precedes a
newer kernel arrival of the same signal. Both policies wait for reconstruction
of the guest context, including the cancelled checkpoint's syscall-exit stop.
Retry observation runs only in guest execution, never inside adapter allocation
or another owner's native protocol.
The instruction-level signal fixture includes positive-code `SIGCHLD` delivery
at each covered entry stage, with context, altstack, restart and filter checks.
The stock Go stress combines file IO, UDP, concurrent child exec/reaping, GC,
preemption and CPU profiling, with and without an application seccomp filter.

`test_cooperative_runtime.py --machine-entry` runs the existing complete entry
contract. `test_svc_patch.py` and `test_svc_libc.py`
run the stock assembly/Go/libc fixtures directly, without the laboratory tracer.
`test_cooperative_libc.py` compares stock-libc workloads under native, optional
PRoot, Base and lazy entry. Explicit API callers are separate correctness
fixtures, not stock-libc performance measurements.

Source-instruction publication uses ptrace against private RX mappings. Bootstrap
registers the initial program and interpreter mappings with `interception_memory`
while it still owns their exact source descriptors. Device/inode and offsets,
not a later pathname lookup, identify those ranges. The existing private-memory
repair then handles a later NONE/R to RX transition if Android rejects the
ptrace-created COW page. Failure to retain the initial provenance leaves Base
entry selected; no writable-text permission is silently substituted.

Before an application filter commits, machine admission
is revoked. The existing cold task barrier, scoped to the affected address
space, recovers resident entries or requests their return checkpoint. Peers
then run to complete admitted calls under the old filter.
After successful installation, a read-only policy view and owned kernel gates
allow eligible entries to rearm. Original nr/args/IP admission precedes effects;
ERRNO can complete locally, while other non-ALLOW actions retain original-site
fallback. Fork shares immutable views. Exec publishes new views for portable
IP-independent chains with an unconditionally allowed checkpoint; other
inherited chains retain Base. Filter suspension preserves warm sites for lazy
re-admission, while mapping retirement remains permanent.
The original event is replayed only after those machine entries
and their syscall-exit continuations have drained. This barrier is for these
kernel transitions, not patch publication or ordinary accelerated calls.
Guest dumpability is virtualized for proc access and debugging. Native
dumpability remains enabled for the supervisor, so changing the guest value
does not revoke accelerated entry or require a memory barrier.
Cooperative channel revocation remains in the common owner: one caller for a local
filter, its thread group for TSYNC. A blocked callback which does not drain in
five seconds rejects the transition with EBUSY; it is not applied and
entry remains revoked. Failure to establish the initial task barrier retains
the supervisor's fail-closed cancellation behavior.

Application-filter preflight requires the complete common descriptor-selector
set. The first published PROC selector is not evidence that the remaining
epoll/poll selectors are ready: concurrent local filter installs could otherwise
make their later TSYNC preparation fail. The adapter and supervisor share the
same selector mask. This preparation remains outside ordinary syscall paths.

The integrated mapping fixture compares native, Base and lazy entry across
repeated NONE/R to RX changes of the initial ELF text and a fork child.
Its filter race runs four live UID/open/close callers through 24 TSYNC installs.
The new filter kills gettid (an entry helper), denies UID/open calls and must
take effect for every peer. The fixture requires observed accelerated
completions, revocation and actual resident-entry drains, not merely successful
installation. Signal-boundary and full cooperative tests remain separate checks.
The protected-state race repeats the same live callers across 24 dumpability
transitions and verifies the state and syscall results in the parent and child.
It requires continued accelerated completions without revocation or filter fences.
Arbitrary externally mutable backing is not certified by this coverage.

An interrupted kernel notification can restart at a newly published SVC gate.
Only a registered gate's exact post-SVC PC is normalized to the original call;
syscall number, architecture and all arguments must still match. The retained
worker result owns completion, including any opened FD. If the restart first
reaches the shared channel, recovery declines that unexecuted request so the
kernel continuation can consume the retained result. Both worker scheduling
and wait preparation use the same serviceability predicate. This avoids a
duplicate filesystem effect, a recovery deadlock and a ready-but-unserviceable
busy loop. Ordinary calls do not scan the site registry for this recovery.

Public `cmdline` access after `dumpable=0` uses guest inspection rules, independently
of Android's proc restrictions. The supervisor's exact-mm retained memory description supplies
live argument bytes when necessary; private image views such as `auxv` retain
their inspection checks. The process-image fixture verifies
post-protection argument edits and denied `auxv` natively, with Base and lazy
entry, plus a differing guest-identity control.

## Common-Entry Acceptance

The qualified device is an NX809J, Android 16, kernel 6.12.23, with Alpine 3.23.6
for stock application workloads. User and Shell use the same executable and
fixture contracts. This is not coverage of another firmware or a physical 5.15
kernel. The full SVC suite, signal-checkpoint suite, cooperative-entry contracts,
and the supervisor-state, namespace, control-event, FS-buffer, FD-observation and
task-mapping resource groups check completion, interruption, policy publication
and original-context fallback
independently of timing. Stock sandboxed headless Chromium with software video,
Docker and Podman exercise real programs;
their lazy completion counts are separate from merely starting successfully.

The interleaved Shell comparison uses the saved `a383439f` runtime as reference,
not Base. Both runtimes have lazy entry enabled. File batches contain 16 blocks
of 1,024 operations, over four rounds;
readiness contains 10,000 calls under a guest ALLOW filter, over four rounds.
Timings have statistics disabled. CPU-frequency observations, per-command CPU
usage, binary hashes and complete samples are in the run receipts. A separate
12-round readiness repeat reverses the initial four-round timing difference;
frequency changes prevent treating either aggregate as a stable speedup or
slowdown. Those samples are retained in
`build/common-entry-benchmark-readiness-repeat/`.

In three interleaved 30-second Chromium video/input pairs against that reference,
median supervisor-thread CPU was 20.63% versus 20.56%, and process CPU including
its worker was 27.23% versus 26.73%. Median input latency was 29.81 versus
25.03 ms. These counters exclude guest-process and Android compositor CPU;
this series does not establish a general browser speedup. Frequency observations
cover each command envelope, not a particular thread. Receipts are in
`build/common-entry-browser-qualified-{0,1,2}-{reference,current}/`.

Separate two-round Base/lazy video/input checks pass in both User and Shell.
Instrumented 15-second runs complete 9,273 filtered calls in Shell and 10,298
in User, with zero unknown entry outcomes. Guest-local native decisions total
5,094 and 4,556 respectively; shared task completions total 4,455 and 4,433.
These categories are not additive syscall totals. Context counters still show
substantial wait and memory ownership: acceleration is not elimination of every
ptrace stop. Browser receipts and nonblank captures are in
`build/common-entry-browser-{shell,app}/` and the corresponding `browser-counts`
directories. These are software headless checks, not GPU or HDMI qualification.

File, readiness and startup receipts are in
`build/common-entry-benchmark-{files,readiness,startup}/`. Base/lazy comparisons
measure the complete lazy mechanism; reference/current comparisons isolate this
change. Neither should be presented as the other.

## Regression Commands

Build with `MAGICDESK_GUEST_FIXTURES=ON`. Tests use the normal native artifacts,
not an alternative proof build. They run through the configured MagicDesk MCP
connection under the selected executor and retain exact receipts in `build/`.

```sh
cmake --build BUILD -j4
python native/guest-exec-lab/audit_svc_payload.py BUILD
python native/guest-exec-lab/test_cooperative_runtime.py BUILD --base
python native/guest-exec-lab/test_cooperative_runtime.py BUILD --machine-entry
python native/guest-exec-lab/test_svc_direct_signal.py BUILD
python native/guest-exec-lab/test_svc_patch.py BUILD STOCK_GO --rounds 5
python native/guest-exec-lab/test_svc_libc.py BUILD STOCK_LIBC_PROBE --checks-only --signal-storm
```

The SVC runner's `--section` selects `entry`, `channels`, `filters`, `protected`, `images`,
`mappings` or `programs` for focused diagnosis. Omit it for the complete suite;
section runs retain the same assertions and record their scope in the report.
Use `--diagnostics` to retain supervisor traces for integrated guests. These
instrumented runs diagnose ownership, not ordinary-launch performance.

`build_svc_go.py` builds stock Go/cgo fixtures; run the same SVC suite with
each artifact. `build_svc_glibc.py` compiles a small test program against a stock
sysroot. The libc runner also accepts a stock musl probe.
Use its `--dynamic-loader`, `--dynamic-libc`, `--interpreter` and
`--dynamic-extra` arguments for dynamic libc checks.

| Fixture | Required evidence |
| --- | --- |
| Cooperative runtime, Base and machine entry | Existing filesystem, FD, namespace, channel, memory, signal and debugger contracts |
| SVC direct signals | Every executed gate instruction, logical register/stack state, negative controls and eight queued-RT scenarios |
| SVC patch | Branch limits, original call identity, adjacent fault PC, raw assembly, mapping replacement, protected proc views, TSYNC and dumpability races, stock Go with/without filters |
| SVC channel expansion | Native/Base/lazy registration and reuse, bounded 100-microsecond signal waves, original interrupted stack and signal delivery in every worker, quiet-tail accelerated completions |
| Stock libc | ABI/contracts, 256 retired thread owners, optional asynchronous/blocked signal storms, dynamic loader variants |
| Payload audit | Actual bootstrap driver has no hidden relocations; object bytes equal linked bytes |

The live filter races require 24 transitions and nonzero resident drains;
a successful filter install alone is not sufficient. Dumpability races retain
direct entry with zero admission fences while checking guest protection state.

### Performance Controls

The libc runner compares native, Base and lazy entry with identical warm-up,
CPU affinity and rotated launch order. `--workload opens` measures 4,096
open/close pairs; `--workload metadata` measures 32,768 metadata calls.
Completion counters are verified in a separate untimed run. Timed runs all
disable runtime statistics, whose clocks would otherwise bias the comparison.
`--threaded` first enters libc's threaded mode. `--sandbox` installs an actual
guest filter; completion counters must establish whether its workload retains
lazy entry or uses the original-site fallback.

`test_cooperative_libc.py` also includes optional PRoot and reference-build
controls. Explicit API callers belong to the separate correctness suite, not
the stock-libc timing comparison. Record kernel, artifact hashes, device load
and thermal conditions with measurements; historical numbers are not
performance guarantees for a new build.

### Desktop and Container Acceptance

Beyond focused tests, the integration matrix uses stock distribution libc and
ordinary installed recipes on virtual displays:

- SteamOS Plasma, Dolphin, terminal, Steam login window and normal logout.
- SteamOS Flatpak Chromium/Firefox and VS Code.
- Ubuntu systemd, Snap Chromium/Firefox and VS Code.
- Alpine musl/OpenRC boot, service lifecycle and cleanup.
- Docker and Podman bridge networking, TCP/UDP publications, DNS, peer traffic,
  local outbound IO, binds, exec, conflict rejection and released ports.
- Traced resolver/socket detach and mixed epoll workflows, with Base controls.

Browser checks require input, download, portal chooser, WebGL output and normal
close; VS Code checks editor/save, watchers, terminal, debugger and reload.
The corresponding workflow owners are in [desktop workflows](desktop-workflows.md)
and the [fixture guide](README.md). A passing native unit suite does not
substitute for these application workflows.

Use the [distribution matrix](validation-matrix.md) for current application
results. Firefox's privacy-masked renderer string does not establish GPU
identity; Steam login is not gameplay and silent PCM counters do not verify
audible quality. Snap's degraded mode is not AppArmor enforcement. Passing a
focused entry fixture does not certify those independent workflows.

## Admission Limits

Arbitrary self-modifying code, externally writable aliases, simultaneous
debugger ownership and SVE/SME/MTE/PAC/BTI are not certified by these tests.
Frame, guard and code-pool alignment covers 4/16/64 KiB pages; actual device
execution coverage is 4 KiB, not proof of a complete 64 KiB host launch.
Site and pool metadata grow on publication and are independently copied on fork.
Allocation failure or an address space with no reachable free gap retains Base.
A new pool cannot be added under an IP-sensitive guest filter whose existing
kernel mirror does not recognize that pool: fallback must not evaluate a
different instruction address. Already admitted pools remain usable. This
restriction is separate from stable service-gate admission and requires a
stable original-context fallback before it can be removed.
There is no claim of general instruction rewriting or unwind metadata.
Unsupported sites retain Base without relaxing sandbox checks.
