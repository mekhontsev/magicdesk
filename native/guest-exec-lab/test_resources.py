#!/usr/bin/env python3
"""Compare native and Shroot resource ownership under the selected executor."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import shlex
import statistics
import tempfile
import tomllib
import uuid
from kernel_profile import KernelProfile
from fixture_executor import FixtureExecutor, probe_native_capability
from test_ipc_credentials_runtime import custody_cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    parser.add_argument("--config", type=Path, default=Path.home() / ".codex/config.toml")
    parser.add_argument("--server", default="magicdesk")
    parser.add_argument("--keep", action="store_true")
    parser.add_argument("--diagnostics", action="store_true")
    parser.add_argument("--case", choices=("all", "process-resources", "roots", "cgroup", "namespaces", "devpts", "mount-flags", "supervisor-state", "robust-futex", "control-event", "launch-cancel", "event-fairness", "kernel-baseline", "mapping-task", "private-memory", "pidfd-table", "fd-observation", "proc-custody", "exec-custody", "open-job", "ofd-order", "fd-handoff", "fd-parcel", "fd-messages", "ipc-peer", "ipc-origins", "ipc-origin-guest", "ipc-paths", "ipc-ancillary", "ipc-custody", "mount-policy", "mount-graph", "mount-namespace", "inode-custody", "origin-transfer", "transient-store", "fd-custody", "fd-custody-dirfd", "fd-custody-nofollow", "fd-lifecycle", "restricted-descriptors", "fs-dispatch", "fs-buffer", "fs-signals", "fs-results", "fs-export", "fs-return", "fs-directory", "fs-capture", "fs-provider", "xattr", "input-session", "rpc", "stores", "durability"), default="all",
                        help="Run resource regressions, FD handoff/parcel, terminal RPC custody, native FD lifecycle, or worker transport")
    parser.add_argument("--compare-installed", action="store_true",
                        help="Compare identical metadata/spawn fixtures with the installed runtime")
    KernelProfile.add_argument(parser)
    FixtureExecutor.add_argument(parser)
    args = parser.parse_args()
    profile = KernelProfile(args.kernel_profile)
    if profile.options and args.compare_installed:
        parser.error("Kernel profiles require fixture helpers, not installed APK helpers")
    if args.case != "all" and args.compare_installed:
        parser.error("--compare-installed requires the full resource suite")
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location("md_mcp", repo / "scripts/mcp-client.py")
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads(args.config.read_text())["mcp_servers"][args.server]
    client = transport.Client(config["url"], config["http_headers"]["Authorization"].removeprefix("Bearer "),
                              request_timeout=120)
    state = client.call("get_state")
    executor = FixtureExecutor(client, transport, args.build, args.executor, 'resources')
    root = executor.root
    def executable(name):
        directory = executor.libraries if args.executor == 'app' else root
        return directory + '/libmagicdesk_guest_' + name + '.so'
    guest_run = executable("run")
    supervisor_run = shlex.join([*profile.environment, executable("supervisor")])
    result = {"device": state["device"], "app": state["app"], "root": root, "case": args.case, "checks": []}
    result['kernelProfile'] = profile.evidence

    def native_capability(name):
        def observe(argv, quiet, timeout):
            return executor.execute(shlex.join(argv), timeout)
        return probe_native_capability(observe, result, executable("resource_guest"), name)

    def check(name, command, contains=None, patterns=(), exit_code=0, absent_patterns=(), kernel=None, profile_log=None):
        command = (kernel or profile).shell(command)
        entry = {"name": name, "command": command, "passed": False}
        result["checks"].append(entry)
        try:
            value = executor.execute(command)
        except Exception as error:
            entry["error"] = str(error)
            raise
        passed = value["exitCode"] == exit_code and (contains is None or contains in value["output"])
        passed = passed and all(re.search(pattern, value["output"]) for pattern in patterns)
        passed = passed and not any(re.search(pattern, value["output"]) for pattern in absent_patterns)
        entry.update(passed=passed, **value)
        active = kernel or profile
        if passed and '--test-kernel-profile' not in command and active.options:
            launching = (kernel is not None or guest_run in command and '--store ' in command
                         or supervisor_run + ' ' in command)
            try:
                output = value['output']
                if profile_log:
                    diagnostic = executor.execute(shlex.join(['head', '-c', '65536', profile_log]))
                    assert diagnostic['exitCode'] == 0, 'Cannot read redirected profile admission'
                    output += '\n' + diagnostic['output']
                    entry['profileLog'] = profile_log
                active.observe(output, required=launching)
            except AssertionError as error:
                entry.update(passed=False, profileFailure=str(error))
                raise
        print(("PASS " if passed else "FAIL ") + name + "\n" + value["output"], flush=True)
        if not passed:
            raise RuntimeError(name)
        return value

    def check_cgroups():
        for entry in (0, 1):
            for case, message in (("cgroup", "PASS guest cgroup:"),
                                  ("cgroup-pid", "PASS guest cgroup: PID namespace"),
                                  ("cgroup-namespace", "PASS guest cgroup namespace:")):
                check(f"guest {case} entry={entry}", guest_run + " --user 0:0"
                      + f" --env SHROOT_MACHINE_ENTRY={entry} --diagnostics --store "
                      + root + "/store --deadline-seconds 90 -- /bin/test " + case, message)

    def check_devpts():
        for entry in (0, 1):
            for case, message in (("devpts", "PASS guest devpts:"),
                                  ("devpts-pid", "PASS guest devpts: PID namespace")):
                check(f"guest {case} entry={entry}", guest_run + " --user 0:0 --diagnostics"
                      + f" --env SHROOT_MACHINE_ENTRY={entry} --store "
                      + root + "/store --deadline-seconds 90 -- /bin/test " + case, message)

    def check_namespaces():
        for entry in (0, 1):
            check(f"guest device nodes entry={entry}", guest_run + " --user 0:0"
                  + f" --env SHROOT_MACHINE_ENTRY={entry} --diagnostics --store "
                  + root + "/store --deadline-seconds 90 -- /bin/test device-nodes", "PASS guest device nodes:")
        check("guest keyring absence", guest_run + " --user 0:0 --diagnostics --store "
              + root + "/store --deadline-seconds 90 -- /bin/test keyring", "PASS guest keyring absence:")
        check("native memfd metadata", executable("resource_guest") + " native-metadata", "PASS native metadata:")
        check("guest memfd metadata", guest_run + " --user 0:0 --diagnostics --store "
              + root + "/store --deadline-seconds 90 -- /bin/test native-metadata", "PASS native metadata:")
        check_cgroups()
        check_devpts()
        for entry in (0, 1):
            check(f"reaped child notifications entry={entry}", guest_run + " --user 0:0"
                  + f" --env SHROOT_MACHINE_ENTRY={entry} --store "
                  + root + "/store --deadline-seconds 90 -- /bin/test pid-namespace reaped-signal",
                  "PASS debugger and recipient retain reaped SIGCHLD identity")
            check(f"clean PID fixture mountpoint entry={entry}", guest_run + " --user 0:0 --store "
                  + root + "/store -- /bin/test pid-namespace cleanup")
            check(f"guest PID namespace lifecycle entry={entry}", guest_run + " --user 0:0"
                  + f" --env SHROOT_MACHINE_ENTRY={entry} --diagnostics --store "
                  + root + "/store --deadline-seconds 90 -- /bin/test pid-namespace", "PASS guest PID namespaces:")
        for entry in (0, 1):
            check(f"guest namespace entry={entry}", guest_run + " --user 0:0"
                  + f" --env SHROOT_MACHINE_ENTRY={entry} --diagnostics --store "
                  + root + "/store --deadline-seconds 90 -- /bin/test setns", "PASS guest setns:")
        check("guest overlay filesystems", guest_run + " --user 0:0 --diagnostics --store "
              + root + "/store --deadline-seconds 90 -- /bin/test overlay", "PASS guest overlay:")
        for entry in (0, 1):
            check(f"guest namespace handles entry={entry}", guest_run + " --user 0:0"
                  + f" --env SHROOT_MACHINE_ENTRY={entry} --diagnostics --store "
                  + root + "/store --deadline-seconds 90 -- /bin/test namespace-handles", "PASS guest namespace handles:")
        check("guest PID-scoped namespace mounts", guest_run + " --user 0:0 --diagnostics --store "
              + root + "/store --deadline-seconds 90 -- /bin/test namespace-mounts-pid", "PASS PID-scoped namespace mounts:")
        check_mount_flags()
        check("guest temporary filesystems", guest_run + " --user 0:0 --diagnostics --store "
              + root + "/store --deadline-seconds 90 -- /bin/test tmpfs", "PASS guest tmpfs:")
        check("guest user namespace proc IO", guest_run + " --user 0:0 --diagnostics --store "
              + root + "/store --deadline-seconds 90 -- /bin/test user-proc", "PASS guest user proc files:")
        check("guest user namespace lifecycle", guest_run + " --user 0:0 --diagnostics --store "
              + root + "/store --deadline-seconds 90 -- /bin/test user-namespace", "PASS guest user namespaces:")
        check("guest user namespace limits", guest_run + " --user 0:0 --diagnostics --store "
              + root + "/store --deadline-seconds 90 -- /bin/test user-limits", "PASS guest user namespace limits:")
        check("direct guest UTS namespace lifecycle", supervisor_run + " --diagnostics --user 0:0 --hostname launch-name "
              + executable("bootstrap") + " " + root + "/source /bin/test uts-namespace", "PASS guest UTS namespaces:")
        runner = guest_run + " --user 0:0 --hostname launch-name --statistics --diagnostics --store " + root + "/store --deadline-seconds 90"
        for kind in ("UTS", "IPC", "mount"):
            for entry in ((1,) if kind == 'mount' else (0, 1)):
                patterns = (r'MD_EXECUTION taskEntries=[1-9][0-9]*',) if entry and kind == 'UTS' else ()
                check(f"guest {kind} namespace lifecycle entry={entry}",
                      runner + f" --env SHROOT_MACHINE_ENTRY={entry} -- /bin/test " + kind.lower() + "-namespace",
                      "PASS guest " + kind + " namespaces:", patterns=patterns)

    def check_mount_flags():
        check("guest mount flags", guest_run + " --user 0:0 --diagnostics --admit-elf /bin/test --store "
              + root + "/store --bind /dev /attached --deadline-seconds 90 -- /bin/test mount-flags", "PASS guest mount flags")

    def check_kernel_workflows():
        check("native read capture", executable("resource_guest") + " read-capture "
              + root + "/native", "PASS read capture:")
        result['kernelComparison'] = []
        for name in KernelProfile.choices:
            selected = KernelProfile(name)
            selected.prepare(lambda argv: check('kernel admission ' + name, shlex.join(argv))['output'],
                             executable("supervisor"))
            result['kernelComparison'].append(selected.evidence)
            for entry in (0, 1):
                runner = shlex.join([executable("run"), *selected.options,
                                    '--statistics', '--env', f'SHROOT_MACHINE_ENTRY={entry}',
                                    '--user', '0:0', '--store', root + '/store', '--deadline-seconds', '90',
                                    '--', '/bin/test']) + ' '
                value = check(f'guest kernel contract {name} entry={entry}', runner + 'kernel-profile',
                              'PASS kernel profile guest:', kernel=selected)
                records = [json.loads(line.removeprefix('SVC_RUNTIME_STATS '))
                           for line in value['output'].splitlines() if line.startswith('SVC_RUNTIME_STATS ')]
                completed = sum(record['completed'] for record in records)
                if (entry and not completed) or (not entry and completed):
                    raise AssertionError('Kernel profile changed the requested syscall entry mode')
                for case, message in (("read-capture", "PASS read capture:"),
                                      ("proc-thread-capture", "PASS proc task capture"),
                                      ("buffer-descriptors", "PASS buffered descriptors"),
                                      ("buffer-data", "PASS buffered data inputs"),
                                      ("buffer-import", "PASS buffered imported dirfd")):
                    check(f'guest {case} {name} entry={entry}', runner + case + ' /tmp', message, kernel=selected)

    def check_xattrs():
        for entry in (0, 1):
            for location in ("/tmp", "/attached"):
                check(f"guest xattr-at entry={entry} location={location}", guest_run
                      + f" --env SHROOT_MACHINE_ENTRY={entry} --store " + root
                      + "/store --bind " + root + "/native /attached"
                      + " --deadline-seconds 90 -- /bin/test xattr-at " + location,
                      "PASS xattr-at:")
        check("direct xattr-at", supervisor_run + " --user 0:0 "
              + executable("bootstrap") + " " + root + "/source /bin/test xattr-at /tmp",
              "PASS xattr-at:")

    def check_buffer_descriptors():
        check("native parallel reads", executable("resource_guest") + " parallel-read "
              + root + "/native", "PASS parallel read: threads=32 completed=128000")
        for entry in (0, 1):
            check(f"guest parallel reads entry={entry}", guest_run
                  + f" --env SHROOT_MACHINE_ENTRY={entry} --store "
                  + root + "/store --deadline-seconds 90 -- /bin/test parallel-read /tmp",
                  "PASS parallel read: threads=32 completed=128000")
        for mode in range(4):
            check(f"native result custody mode={mode}", executable("resource_guest") + " result-custody "
                  + root + f"/native {mode}", "PASS cancellation stress:")
            for entry in (0, 1):
                # Prove both ownership paths explicitly: lazy discovery can
                # redirect the first open before a notification result exists.
                carrier = (r"MD_BROKER nativeOpen=[1-9]\d*" if entry and not mode
                           else r"MD_BROKER resultDeliveries=[1-9]\d*")
                check(f"guest result custody mode={mode} entry={entry}",
                      guest_run + " --statistics --env SHROOT_MACHINE_ENTRY="
                      + str(entry) + " --store " + root
                      + f"/store --deadline-seconds 90 -- /bin/test result-custody /tmp {mode}",
                      "PASS cancellation stress:", patterns=(carrier,))
        check("native xattr with full table", executable("resource_guest") + " xattr-slots-native "
              + root + "/native", "PASS xattr without private FDs")
        check("guest xattr with full table", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test xattr-slots /tmp", "PASS xattr without private FDs")
        check_xattrs()
        check("native watches with full table", executable("resource_guest") + " watch-slots "
              + root + "/native", "PASS watch paths without private FDs")
        check("guest watches with full table", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test watch-slots /tmp", "PASS watch paths without private FDs")
        check("native imported dirfd", executable("resource_guest") + " buffer-import "
              + root + "/native", "PASS buffered imported dirfd")
        check("guest imported dirfd", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test buffer-import /tmp", "PASS buffered imported dirfd")
        check("native data input control", executable("resource_guest") + " buffer-data "
              + root + "/native", "PASS buffered data inputs")
        check("guest buffered data input", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test buffer-data /tmp", "PASS buffered data inputs")
        check("native buffered descriptors", executable("resource_guest") + " buffer-descriptors "
              + root + "/native", "PASS buffered descriptors")
        check("guest buffered descriptors", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test buffer-descriptors /tmp", "PASS buffered descriptors")
        check("guest mapped FIFO", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test buffer-fifo /tmp", "PASS mapped FIFO")

    def check_captures():
        check("native private-thread proc selection", executable("resource_guest") + " proc-thread-capture "
              + root + "/native", "PASS proc task capture")
        check("guest private-thread proc selection", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test proc-thread-capture /tmp", "PASS proc task capture")
        check("native capture terminal control", executable("resource_guest") + " capture-terminal-native "
              + root + "/native", "PASS capture terminal ownership")
        check("guest capture terminal ownership", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test capture-terminal /tmp", "PASS capture terminal ownership")
        # An inherited directory has no guest-open origin. Its capture may use
        # permitted native inspection; logical dumpability does not force the
        # helper path. Protected native capture is covered by fs-dispatch.
        check("native inherited capture terminal control", executable("resource_guest") + " capture-terminal-inherited-native "
              + root + "/native </dev", "PASS capture terminal ownership")
        check("guest inherited capture terminal ownership", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test capture-terminal-inherited /tmp </dev", "PASS capture terminal ownership")
        for inherited in (False, True):
            scenario="native-cwd-inherited" if inherited else "native-cwd"
            control=" </dev" if inherited else ""
            check("native cwd control inherited=" + str(inherited), executable("resource_guest") + " "
                  + scenario + " " + root + "/native" + control, "PASS native cwd")
            check("guest cwd capture inherited=" + str(inherited), guest_run + " --diagnostics --store " + root
                  + "/store --deadline-seconds 90 -- /bin/test " + scenario + " /tmp" + control, "PASS native cwd",
                  absent_patterns=() if inherited else (r"PROBE buffer-capture pid=\d+",))
        check("native proc watch full table", executable("resource_guest") + " watch-slots "
              + root + "/native", "PASS watch paths without private FDs")
        check("guest proc watch full table", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test watch-slots /tmp", "PASS watch paths without private FDs")

    def check_buffers():
        check("native path invocation", executable("resource_guest") + " path-invocation "
              + root + "/native", "PASS path invocation:")
        check("guest path invocation", guest_run + " --statistics --store "
              + root + "/store --deadline-seconds 90 -- /bin/test path-invocation /tmp", "PASS path invocation:",
              patterns=(r"MD_DIRECT status=[1-9]\d* open=0 fallback=0 admit=0 retry=0",
                        r"MD_BROKER nr=56 notifications=\d+ handled=[1-9]\d* delegated=\d+"))
        check_mutation_signals()
        check_captures()
        check("adapter stack ownership", executable("test_interception_memory"),
              "PASS adapter stacks:")
        check("adapter address-space ownership", executable("test_interception_buffers"),
              "PASS shared request buffer ownership")
        check("native buffered cwd control", executable("resource_guest") + " buffer-cwd " + root + "/native",
              "PASS buffered cwd")
        check("guest buffer lifecycle", guest_run + " --store " + root
              + "/store --deadline-seconds 90 -- /bin/test buffer-cwd /tmp", "PASS buffered cwd")
        check("native buffered open control", executable("resource_guest") + " buffer-open " + root + "/native",
              "PASS buffered O_PATH")
        for entry in (0, 1):
            check("guest buffered O_PATH entry=" + str(entry), guest_run + " --env SHROOT_MACHINE_ENTRY="
                  + str(entry) + " --store " + root + "/store --deadline-seconds 90 -- /bin/test buffer-open /tmp",
                  "PASS buffered O_PATH")
        check_buffer_descriptors()
        check_exports()
        check_returns()
        check_directories()
        for suffix in ("", "-protected"):
            check("native buffered paths" + suffix, executable("resource_guest") + " buffer-paths"
                  + suffix + " " + root + "/native", "PASS buffered paths")
            check("guest buffered paths" + suffix, guest_run + " --store " + root
                  + "/store --deadline-seconds 90 -- /bin/test buffer-paths" + suffix + " /tmp", "PASS buffered paths")
        for suffix in ("", "-dirfd", "-nofollow"):
            check("native buffered custody control" + suffix,
                  executable("resource_guest") + " custody-native" + suffix + " " + root + "/native",
                  "PASS terminal RPC custody")
            check("guest buffered custody" + suffix, guest_run + " --store " + root
                  + "/store --deadline-seconds 90 -- /bin/test custody" + suffix + " /tmp", "PASS terminal RPC custody")
        check_exec_custody()
        check_proc_custody()
        check("native buffered group exit", executable("resource_guest") + " buffer-exit " + root + "/native",
              "PASS buffered group exit")
        # EVENT_WAIT: the outer bound also covers failure inside supervisor cleanup.
        # Keep high-volume syscall diagnostics outside the bounded command output;
        # otherwise successful termination can lose its required PASS marker.
        try:
            check("guest buffered group exit", "timeout -k 5 105 " + guest_run + " --diagnostics --store " + root
                  + "/store --deadline-seconds 90 -- /bin/test buffer-exit /tmp 2>" + root + "/buffer-exit.log",
                  "PASS buffered group exit", profile_log=root + "/buffer-exit.log")
        finally:
            executor.download(root + "/buffer-exit.log",
                               args.build / "buffer-exit.log", overwrite=True)
        check_cancellation()

    def check_cancellation():
        for workload in ("park", "busy"):
            for suffix in ("", "-protected"):
                # EVENT_WAIT: idle and continuously active trees must expire
                # and reap all peers; the busy group ignores graceful TERM.
                # Outer-timeout recovery has a different exit status.
                value = check("guest terminal cleanup " + workload + suffix, "timeout -k 5 15 " + guest_run
                              + " --store " + root
                              + "/store --deadline-seconds 2 -- /bin/test buffer-" + workload + suffix + " /tmp",
                              "READY buffered parked group",
                              patterns=(r"guest-runtime: launch deadline expired",), exit_code=125,
                              absent_patterns=(r"supervisor failure", r"cleanup deadline expired"))
                match = re.search(r"^TRACEES (\d+(?: \d+){8})$", value["output"], re.MULTILINE)
                if not match:
                    raise RuntimeError("Missing exact tracee identities")
                check("all cancellation tracees reaped", "for pid in " + match[1]
                      + '; do test ! -d /proc/"$pid" || exit 1; done')

    def check_mutation_signals():
        native_metadata = native_capability('faccessat2')
        for mode in range(8):
            if native_metadata:
                check(f"native metadata signals mode={mode}", executable("resource_guest") + " metadata-signal "
                      + root + f"/native {mode}", "PASS metadata under signals:")
            check(f"guest metadata signals mode={mode}", guest_run + " --statistics --store "
                  + root + f"/store --deadline-seconds 90 -- /bin/test metadata-signal /tmp {mode}",
                  "PASS metadata under signals:")
        for protected in range(2):
            for restart in range(2):
                for guest in (False, True):
                    command = (guest_run + " --store " + root
                               + "/store --deadline-seconds 90 -- /bin/test mutation-signal /tmp"
                               if guest else executable("resource_guest") + " mutation-signal " + root + "/native")
                    # EVENT_WAIT: the launch deadline bounds signal stress and cleanup.
                    check(f"mutation signals protected={protected} restart={restart} guest={guest}",
                          "timeout -k 5 105 " + command + f" {protected} {restart}",
                          "PASS mutation under signals:")
        for protected in range(2):
            for restart in (0, 1, 2, 3, 4, 6, 8, 14, 16, 18, 22):
                for guest in (False, True):
                    command = (guest_run + " --statistics --store " + root
                               + "/store --deadline-seconds 90 -- /bin/test shared-memory /tmp"
                               if guest else executable("resource_guest") + " shared-memory " + root + "/native")
                    # EVENT_WAIT: fixed shared-memory stress, bounded by the launch deadline.
                    check(f"shared memory signals protected={protected} mode={restart} guest={guest}",
                          "timeout -k 5 105 " + command + f" {protected} {restart}",
                          "PASS shared memory under signals:")

    def check_exec_custody():
        check("native processor controls", executable("resource_guest") + " processor-state",
              "PASS processor state:")
        for entry in (0, 1):
            check(f"guest processor controls entry={entry}", guest_run
                  + f" --env SHROOT_MACHINE_ENTRY={entry} --store " + root
                  + "/store --deadline-seconds 90 -- /bin/test processor-state", "PASS processor state:")
        if native_capability('openat2'):
            check("native foreign executable", executable("resource_guest") + " proc-exec native " + root + "/native",
                  "PASS foreign executable selection")
        for entry in (0, 1):
            for image in ("direct", "memfd", "pid"):
                check(f"guest foreign executable {image} entry={entry}", guest_run
                      + f" --env SHROOT_MACHINE_ENTRY={entry} --diagnostics --store " + root
                      + "/store" + (" --user 0" if image == "pid" else "")
                      + " --deadline-seconds 90 -- /bin/test proc-exec " + image + " /tmp", "PASS foreign executable selection")
        check("native exec preparation control", executable("resource_guest") + " exec-custody-native "
              + root + "/native", "PASS exec preparation custody")
        check("guest exec preparation custody", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test exec-custody /tmp", "PASS exec preparation custody")

    def check_proc_custody():
        check("native imported cwd with full table", executable("resource_guest") + " imported-cwd "
              + root + "/native", "PASS imported cwd")
        check("guest imported cwd with full table", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test imported-cwd /tmp", "PASS imported cwd")
        if native_capability('executable-metadata'):
            check("native executable metadata with full table", executable("resource_guest") + " proc-exe-slots "
                  + root + "/native", "PASS executable metadata")
        check("guest executable metadata with full table", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test proc-exe-slots /tmp", "PASS executable metadata")
        check("native foreign proc open with one slot", executable("resource_guest") + " proc-open-slots "
              + root + "/native", "PASS foreign proc open")
        check("guest foreign proc open with one slot", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test proc-open-slots /tmp", "PASS foreign proc open")
        check("native foreign proc link with full table", executable("resource_guest") + " proc-link-slots "
              + root + "/native", "PASS foreign proc link")
        check("guest foreign proc link with full table", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test proc-link-slots /tmp", "PASS foreign proc link")
        check("native proc reopen with one slot", executable("resource_guest") + " proc-reopen-slots "
              + root + "/native", "PASS proc reopen with one slot")
        check("guest proc reopen with one slot", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test proc-reopen-slots /tmp", "PASS proc reopen with one slot")
        check("native metadata with full table", executable("resource_guest") + " metadata-slots-native "
              + root + "/native", "PASS metadata without private FDs")
        check("guest metadata with full table", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test metadata-slots /tmp", "PASS metadata without private FDs")
        for guest in (False, True):
            check(("guest" if guest else "native") + " umask custody",
                  (guest_run + " --diagnostics --store " + root
                     + "/store --deadline-seconds 90 -- /bin/test umask-custody /tmp" if guest
                     else executable("resource_guest") + " umask-custody-native " + root + "/native"),
                  "PASS umask custody")
        check("native proc snapshot control", executable("resource_guest") + " proc-custody-native "
              + root + "/native", "PASS proc snapshot custody")
        check("guest proc snapshot custody", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test proc-custody /tmp", "PASS proc snapshot custody")
        check("guest proc snapshot semantics", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test proc-snapshot /tmp", "PASS proc snapshots")
        check("native mount snapshot O_PATH", executable("resource_guest") + " proc-mount-paths "
              + root + "/native", "PASS mount snapshot O_PATH")
        check("guest mount snapshot O_PATH", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test proc-mount-paths /tmp", "PASS mount snapshot O_PATH")

    def check_exports():
        check("native metadata export control", executable("resource_guest") + " metadata-export-native "
              + root + "/native", "PASS metadata export custody")
        check("relative root alias", "ln -s source " + root + "/source-alias")
        check("direct guest protected metadata export", "MAGICDESK_TEST_CAPTURE_FALLBACK=1 "
              + supervisor_run + " --diagnostics "
              + executable("bootstrap") + " " + root + "/source-alias /bin/test metadata-export /tmp",
              "PASS metadata export custody",
              patterns=(r"SHROOT_CAPTURE_PROFILE host-only pidfd-getfd=denied descriptors=0,900",))
        for entry in (0, 1):
            check(f"managed guest protected metadata export entry={entry}", guest_run
                  + f" --env SHROOT_MACHINE_ENTRY={entry} --user 0:0 --diagnostics --store " + root
                  + "/store --deadline-seconds 90 -- /bin/test metadata-export-managed /tmp", "PASS metadata export custody")
        check("native protected export control", executable("resource_guest") + " buffer-export-native "
              + root + "/native", "PASS protected export")
        check("guest protected export", guest_run + " --env MAGICDESK_TEST_CAPTURE_FALLBACK=1 --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test buffer-export /tmp", "PASS protected export",
              patterns=(r"SHROOT_CAPTURE_PROFILE host-only pidfd-getfd=denied descriptors=0,900",))

    def check_returns():
        check("native protected return control", executable("resource_guest") + " buffer-return-native "
              + root + "/native", "PASS protected return")
        for entry in (0, 1):
            check(f"guest protected descriptor return entry={entry}", guest_run
                  + f" --env SHROOT_MACHINE_ENTRY={entry} --diagnostics --store " + root
                  + "/store --bind /dev /attached --deadline-seconds 90 -- /bin/test buffer-return /tmp", "PASS protected return")

    def check_directories():
        check("merged provider directory paging", executable("fs_dispatch") + " --provider-directory "
              + root + "/provider-directory", "PASS provider directory:")
        check("native directory copy", executable("resource_guest") + " buffer-directory "
              + root + "/native", "PASS protected directory copy")
        check("guest directory copy", guest_run + " --diagnostics --store " + root
              + "/store --deadline-seconds 90 -- /bin/test buffer-directory /tmp", "PASS protected directory copy")

    def check_origin_guest():
        check("guest capsule send and receive", shlex.join([executable("fs_dispatch"),
              "--ipc-origin-guest", root + "/ipc-store", guest_run]),
              "PASS guest capsule receive mode=14 protected=5",
              patterns=(r"SHROOT_CAPTURE_PROFILE host-only pidfd-getfd=denied descriptors=0,900",))

    def check_ipc_custody(fixture, run):
        for name, argv in custody_cases([fixture, "--custody-native"], run + ["--custody"]):
            check(name, shlex.join(argv), "PASS IPC caller custody")

    def check_ancillary(origins_only=False):
        fixture = executable("ipc_credentials_guest")
        check("native non-Unix ancillary control", fixture + " --nonunix-rights", "PASS non-Unix ancillary send")
        check("ancillary directories", "mkdir -p " + root + "/ipc-source/bin " + root + "/ipc-source/tmp " + root + "/ipc-source/attached")
        check("ancillary root permissions", "chmod 755 " + root + "/ipc-source " + root + "/ipc-source/bin")
        check("ancillary tmp permissions", "chmod 1777 " + root + "/ipc-source/tmp")
        check("ancillary image", "cp " + fixture + " " + root + "/ipc-source/bin/test")
        check("ancillary import", executable("service") + " --import "
              + root + "/ipc-source " + root + "/ipc-store --preserve-ownership")
        if origins_only:
            check_origin_guest()
            return
        run = [guest_run, "--store", root + "/ipc-store",
               "--user", "0:0", "--deadline-seconds", "90", "--", "/bin/test"]
        if args.diagnostics:
            run.insert(1, "--diagnostics")
        if args.case == "ipc-custody":
            check_ipc_custody(fixture, run)
            return
        if args.case == "ipc-paths":
            check("guest socket paths", shlex.join(run + ["--socket-paths"]), "PASS socket paths:")
            return
        for entry in (0, 1):
            claimed = run[:1] + ["--env", f"SHROOT_MACHINE_ENTRY={entry}"] + run[1:]
            check(f"guest claimed PID entry={entry}", shlex.join(claimed + ["--claimed-process"]),
                  "PASS claimed process:")
        for mode in range(34):
            for guest in (False, True):
                check(f"filtered socket IO {mode} guest={guest}",
                      shlex.join((run if guest else [fixture]) + ["--io", str(mode)]),
                      f"PASS filtered socket IO mode={mode}")
        check("guest ancillary semantics", shlex.join(run), "PASS connection snapshots:")
        check("native consumer boundary", shlex.join([fixture, "--boundary-fixture",
              guest_run, root + "/ipc-store"]),
              "PASS guest native boundary mode=2 protected=1")
        check("addressed destinations", "mkdir -p " + root + "/addressed")
        check("guest addressed sends", shlex.join([fixture, "--address-fixture", guest_run,
              root + "/ipc-store", root + "/addressed"]), "PASS addressed send custody:")
        check_origin_guest()
        check_ipc_custody(fixture, run)

    try:
        check("isolated staging", "chmod 700 " + root)
        names = ("fd_handoff",) if args.case in ("fd-handoff", "fd-parcel", "fd-messages") else (
            "bootstrap", "supervisor", "run", "service", "resource_guest", "root_guest",
            "test_task_resources", "test_inodes", "test_mounts", "test_credentials", "test_rpc", "test_snapshot", "fd_handoff", "test_ipc_peer")
        if args.case in ("launch-cancel", "mount-namespace", "proc-custody", "exec-custody", "fd-custody", "fd-custody-dirfd", "fd-custody-nofollow", "fs-buffer", "fs-signals", "fs-results", "fs-export", "fs-return", "fs-directory", "fs-capture", "xattr"):
            names = ("bootstrap", "supervisor", "run", "service", "resource_guest")
        if args.case == "restricted-descriptors":
            names = ("bootstrap", "supervisor", "run", "service", "resource_guest")
        if args.case in ("fs-buffer", "fs-directory"):
            names += ("fs_dispatch",)
        if args.case in ("all", "fs-buffer"):
            names += ("test_interception_memory", "test_interception_buffers")
        if args.case == "all":
            names += ("test_open_job", "fs_dispatch", "test_launch_registry", "ipc_credentials_guest", "ipc_io_job", "fd_observation", "task_helper_fd", "test_native_mounts", "file_metadata", "event_signal", "test_svc_machine_signal")
            names += ("control_event", "wait_guest")
        if args.case == "control-event":
            names = ("control_event", "fd_filter_remote", "wait_guest", "bootstrap", "supervisor", "run", "service")
        elif args.case == "all":
            names += ("fd_filter_remote",)
        if args.case == "fd-observation":
            names = ("fd_observation", "task_helper_fd")
        if args.case in ("ipc-ancillary", "ipc-origin-guest", "ipc-paths", "ipc-custody"):
            names = ("bootstrap", "supervisor", "run", "service", "ipc_credentials_guest", "ipc_io_job", "fs_dispatch")
        if args.case in ("all", "kernel-baseline"):
            names += ("kernel_baseline", "capabilities")
        if args.case == "open-job":
            names = ("test_open_job",)
        if args.case == "fd-lifecycle":
            names = ("fd_lifecycle",)
        if args.case == "mapping-task":
            names = ("mapping_task",)
        if args.case == "private-memory":
            names = ("bootstrap", "supervisor", "run", "service", "jit_memory", "task_quiesce", "test_interception_buffers", "test_svc_patch", "fs_dispatch")
        if args.case == "fs-dispatch":
            names = ("fs_dispatch", "test_interception_buffers")
        if args.case in ("input-session", "fs-provider"):
            names = ("fs_dispatch",)
        if args.case == "rpc":
            names = ("test_rpc",)
        if args.case == "stores":
            names = ("test_mounts", "test_inodes", "test_snapshot", "test_native_mounts", "test_durability")
        if args.case in ("all", "stores"):
            names += ("test_schema", "test_publication", "test_native_leaf", "test_image_layer", "image")
        if args.case == "durability":
            names = ("bootstrap", "supervisor", "run", "service")
        if args.case in ("all", "durability"):
            names += ("test_durability", "durability_guest")
        if args.case in ("mount-policy", "mount-graph", "inode-custody", "origin-transfer", "transient-store"):
            names = ("test_mounts",)
        if args.case == "ipc-peer":
            names = ("test_ipc_peer", "fs_dispatch", "test_launch_registry")
        if args.case == "ipc-origins":
            names = ("fs_dispatch",)
        if args.case == "ofd-order":
            names = ("test_task_resources",)
        if args.case == "cgroup":
            names = ("bootstrap", "supervisor", "run", "service", "resource_guest", "test_task_resources")
        if args.case == "pidfd-table":
            names = ("pidfd_table",)
        if args.case == "event-fairness":
            names = ("bootstrap", "supervisor", "run", "service")
        if args.case in ("all", "event-fairness"):
            names += ("event_fairness", "task_wait", "task_wait_live", "pending")
        if args.case == "supervisor-state":
            names = ()
        if args.case in ("all", "supervisor-state", "private-memory"):
            names += ("supervisor_state", "test_seccomp_policy", "test_host_open", "test_host_policy", "test_signal_delivery")
        if args.case == "robust-futex":
            names = ("test_robust_futex",)
        elif args.case == "all":
            names += ("test_robust_futex",)
        for name in names:
            file = "libmagicdesk_guest_" + name + ".so"
            executor.upload(args.build / file, root + "/" + file)
        if args.executor == 'shell':
            check("prepare fixtures", "chmod 700 " + root + "/*.so")
        if profile.options:
            if 'supervisor' not in names:
                raise ValueError('This component-only suite has no supervised launches; use kernel-baseline for host components')
            profile.prepare(lambda argv: check('kernel profile admission', shlex.join(argv))['output'],
                            executable("supervisor"))
        if args.case in ("all", "robust-futex"):
            check("robust futex cursor", executable("test_robust_futex"), "PASS robust futex cursor:")
            for case in ("exit", "kill", "exec", "exec-fault", "exec-real-first", "exec-private-first", "exec-directed",
                         "subreaper-exit", "subreaper-kill", "subreaper-ignore", "subreaper-nochildwait",
                         "subreaper-readers", "subreaper-readers-kill", "subreaper-reader-reap"):
                check("robust executor " + case, executable("test_robust_futex") + " --native " + case,
                      "PASS robust executor: " + case + ",")
            if args.case == "robust-futex":
                return
        if args.case in ("all", "supervisor-state", "private-memory"):
            check("host policy admission", executable("test_host_policy") + ' ' + shlex.quote(root),
                  "PASS app-UID admission and scoped host access")
            check("host path resolution", executable("test_host_open"),
                  "PASS host open resolution:")
            check("signal delivery", executable("test_signal_delivery"),
                  "PASS signal delivery:")
            check("seccomp policy ownership", executable("test_seccomp_policy"),
                  "PASS filter installation:", patterns=(r"PASS immutable capture transport:",))
            check("supervisor state ownership", executable("supervisor_state"),
                  "PASS supervisor completed open:", patterns=(r"PASS supervisor admission:",
                  r"PASS supervisor repair deferral:", r"PASS supervisor accounting:",
                  r"PASS memory repair preparation:",
                  r"PASS filter publication:",
                  r"PASS notification completion: six kernel cancellation/reentry orders",
                  r"PASS notification bind: live lazy patch"))
            if args.case == "supervisor-state":
                return
        if args.case in ("all", "stores"):
            check("archive hardlink dependencies", executable("test_image_layer") + " "
                  + root + "/image-layer-unit", "PASS image hardlinks: linear dependency resolution")
            check("storage schema", executable("test_schema") + " " + root + "/schema-unit",
                  "PASS maintenance fails busy; ordinary admission waits for the complete schema upgrade")
            inspection = check("all-scope source inspection", executable("image") + " inspect "
                               + root + "/schema-unit/inspection")
            sources = json.loads(inspection["output"])["sources"]
            assert len(sources) == 2 and {item["path"] for item in sources} == {
                "../fresh/objects", "../revision-1/objects"}
            spec = importlib.util.spec_from_file_location("oci_fixtures", Path(__file__).with_name("test_oci.py"))
            fixtures = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(fixtures)
            with tempfile.TemporaryDirectory(prefix="md-backup-fixture-") as staging:
                archive = Path(staging) / "frozen.tar"
                archive.write_bytes(fixtures.frozen_backup_v1())
                executor.upload(archive, root + "/frozen.tar")
            check("frozen backup v1 restore", executable("image") + " restore "
                  + root + "/frozen.tar " + root + "/frozen-restored")
            check("frozen backup v1 contents", executable("test_schema") + " --check-history-backup "
                  + root + "/frozen-restored", "PASS historical metadata survives portable backup")
            check("historical storage backup", executable("image") + " backup "
                  + root + "/schema-unit/revision-2 " + root + "/historical.tar.zst")
            check("historical storage restore", executable("image") + " restore "
                  + root + "/historical.tar.zst " + root + "/historical-restored")
            check("historical portable metadata", executable("test_schema") + " --check-history-backup "
                  + root + "/historical-restored", "PASS historical metadata survives portable backup")
            check("native inode publication", "timeout -k 5 90 " + executable("test_publication")
                  + " " + root + "/publication-unit",
                  "PASS promoted body enters existing shared-source snapshots and COW")
            check("native leaf ownership", "timeout -k 5 90 " + executable("test_native_leaf")
                  + " " + root + "/native-leaf-unit",
                  "PASS promotion captures native metadata")
            check("native metadata backup", executable("image") + " backup "
                  + root + "/native-leaf-unit/store " + root + "/native-leaf.tar.zst")
            check("native metadata restore", executable("image") + " restore "
                  + root + "/native-leaf.tar.zst " + root + "/native-leaf-restored")
            check("native metadata portable archive", executable("test_native_leaf") + " --check-backup "
                  + root + "/native-leaf-restored", "PASS real backup/restore retains logical ownership")
            check("hybrid namespace backup", executable("image") + " backup "
                  + root + "/native-leaf-unit/hybrid " + root + "/hybrid.tar.zst")
            check("hybrid namespace restore", executable("image") + " restore "
                  + root + "/hybrid.tar.zst " + root + "/hybrid-restored")
            check("hybrid namespace portable archive", executable("test_native_leaf") + " --check-hybrid-backup "
                  + root + "/hybrid-restored", "PASS backup materializes unpromoted native names")
        if args.case in ("all", "stores", "durability"):
            check("storage durability", executable("test_durability") + " " + root + "/durability-unit",
                  "PASS sealed image durability and snapshot-local allocation reserve")
        if args.case in ("all", "durability"):
            check("durability source", "mkdir -p " + root + "/durability-source/bin "
                  + root + "/durability-source/tmp " + root + "/durability-source/attached " + root + "/durability-attached")
            check("durability image", "cp " + executable("durability_guest") + " "
                  + root + "/durability-source/bin/test")
            for mode in ("plain", "protected"):
                store = root + "/durability-" + mode
                check("durability import " + mode, executable("service") + " --import "
                      + root + "/durability-source " + store)
                check("guest durability " + mode, shlex.join([guest_run, "--user", "0:0",
                      "--bind", root + "/durability-attached", "/attached", "--store", store,
                      "--deadline-seconds", "90", "--", "/bin/test", mode]), "PASS guest durability calls:")
            if args.case == "durability":
                return
        if args.case in ("all", "control-event"):
            check("remote FD filter admission", "timeout -k 5 40 " + executable("fd_filter_remote"),
                  "PASS remote FD filter:")
            check("control event ownership", "timeout -k 5 60 " + executable("control_event"),
                  "PASS mixed native wakeups and coalesced generations")
            check("wait fixture directories", "mkdir -p " + root + "/wait-source/bin")
            check("wait fixture image", "cp " + executable("wait_guest") + " " + root + "/wait-source/bin/waits")
            check("wait fixture import", executable("service") + " --import "
                  + root + "/wait-source " + root + "/wait-store")
            for filtered in range(2):
                check("native wait control " + str(filtered), executable("wait_guest") + " "
                      + str(filtered), "PASS native wait fast paths")
                expected = [r"MD_SYSCALL nr=20 trace=2 notification=0"]
                absent = [r"MD_SYSCALL nr=(?:21|22|72|73) "]
                if filtered:
                    absent = []
                value = check("guest wait fast paths " + str(filtered), guest_run + " --statistics --store "
                      + root + "/wait-store --deadline-seconds 30 " + ("--diagnostics " if args.diagnostics else "")
                      + "-- /bin/waits " + str(filtered),
                      "PASS native wait fast paths",
                      patterns=expected, absent_patterns=absent)
                counts = {int(nr): int(count) for nr, count in re.findall(
                    r'MD_SYSCALL nr=(\d+) trace=(\d+)', value['output'])}
                # epoll_pwait2 may discover a shared SVC once; repeated native
                # waits must still avoid interception under either executor.
                assert counts.get(441, 0) <= 1, counts
                if filtered:
                    # Resident poll/select finish classification before the
                    # terminal native effect; only discovery may stop.
                    assert counts.get(72, 0) <= 2 and counts.get(73, 0) <= 2, counts
                    terminal = [int(count) for count in re.findall(r'"nativeCompleted":(\d+)', value['output'])]
                    assert terminal and max(terminal) >= 19996, value['output']
                    assert counts.get(21, 0) <= 3, counts
                    # Only cold admission may stop; not one stop per loop.
                    assert counts.get(22, 0) <= 8, counts
                    native = re.search(r'nativeReadiness=(\d+)', value['output'])
                    assert native and int(native[1]) >= 19992, value['output']
                    executions = re.search(r'MD_EXECUTION taskEntries=(\d+) completed=(\d+)', value['output'])
                    contexts = sum(map(int, re.findall(r'MD_EXECUTION_CONTEXT reason=\w+ calls=(\d+)', value['output'])))
                    kernel = re.search(r'MD_EXECUTION_NATIVE calls=(\d+)', value['output'])
                    assert executions and kernel and int(executions[2]) > 0, value['output']
                    assert int(executions[1]) == int(executions[2]) + contexts, value['output']
                    assert int(kernel[1]) <= int(executions[1]), value['output']
            if args.case == "control-event":
                return
        if args.case == "all":
            check("file metadata ABI", executable("file_metadata"), "PASS file metadata:")
            check("event signals", executable("event_signal"), "PASS event signal:")
            check("native alias signal continuation", executable("test_svc_machine_signal"),
                  "PASS native alias signal commit:")
        if args.case in ("all", "event-fairness"):
            check("pending continuations", executable("pending"),
                  "PASS pending continuations")
            check("task event selection", executable("task_wait"),
                  "PASS task event selection")
            check("live task event collection", executable("task_wait_live"),
                  "PASS live wait collection")
            check("sparse task event collection", executable("task_wait_live") + " --idle",
                  "PASS live wait collection")
            check("small task event collection", executable("task_wait_live") + " --small",
                  "PASS live wait collection")
            for workload in ("", "descriptors", "birth"):
                check("native event fairness " + workload, executable("event_fairness") + " " + workload,
                      "PASS worker completions under continuous ptrace stops")
            check("event fairness source", "mkdir -p " + root + "/fair-source/bin " + root + "/fair-source/tmp")
            check("event fairness image", "cp " + executable("event_fairness") + " " + root + "/fair-source/bin/test")
            check("event fairness access", "chmod 755 " + root + "/fair-source "
                  + root + "/fair-source/bin " + root + "/fair-source/bin/test "
                  + root + "/fair-source/tmp")
            check("event fairness import", executable("service") + " --import "
                  + root + "/fair-source " + root + "/fair-store --preserve-ownership")
            for user in ("0:0", "1000:1000"):
                for workload in ("", "descriptors", "birth", "birth-namespace", "helpers"):
                    check("guest event fairness " + user + " " + workload, "timeout -k 5 25 " + guest_run
                          + " " + ("--diagnostics " if args.diagnostics else "")
                          + "--user " + user + " --store "
                          + root + "/fair-store --deadline-seconds 20 -- /bin/test " + workload,
                          "PASS worker completions under continuous ptrace stops")
            if args.case == "event-fairness":
                return
        if args.case == "private-memory":
            check("task quiescence", executable("task_quiesce"), "PASS task quiescence")
            check("resident entry admission", executable("test_svc_patch"), "PASS SVC planner")
            check("private mapping ownership", executable("test_interception_buffers"),
                  "PASS shared request buffer ownership")
            check("namespace worker quiescence", executable("fs_dispatch") + " --worker-pause " + root + "/worker-pause",
                  "PASS worker quiescence")
            check("native private-memory preparation", executable("jit_memory") + " " + root,
                  "PASS policy matrix and rejected-replacement regressions")
            check("private-memory fixture directories", "mkdir -p " + root + "/source/tmp " + root + "/source/bin")
            check("private-memory fixture image", "cp " + executable("jit_memory") + " " + root + "/source/bin/test")
            check("private-memory fixture access", "chmod 755 " + root + "/source " + root + "/source/bin " + root + "/source/bin/test")
            check("private-memory temporary directory", "chmod 1777 " + root + "/source/tmp")
            check("private-memory fixture import", executable("service") + " --import " + root + "/source " + root + "/store --preserve-ownership")
            check("guest private-memory preparation", guest_run + " --diagnostics --user 1000:1000 --store "
                  + root + "/store --deadline-seconds 60 -- /bin/test /tmp",
                  "PASS policy matrix and rejected-replacement regressions",
                  patterns=(r"PROBE memory-access pid=\d+ result=0",
                            r"PROBE memory-backing pid=\d+ fd=\d+ retained=1 result=0"))
            check("automatic private-file JIT", guest_run + " --diagnostics --user 1000:1000 --store "
                  + root + "/store --deadline-seconds 60 -- /bin/test --automatic",
                  "PASS automatic private-file JIT",
                  patterns=(r"PROBE memory-repair pid=\d+ address=0x[0-9a-f]+ size=4096 pages=1 result=0",))
            for entry in (0, 1):
                check(f"private-file JIT with SysV memory entry={entry}",
                      guest_run + " --diagnostics --user 1000:1000 --store "
                      + root + "/store --env SHROOT_MACHINE_ENTRY=" + str(entry)
                      + " --deadline-seconds 60 -- /bin/test --automatic-shm",
                      "PASS private-file JIT with SysV attachment, fork and native unmap",
                      patterns=(r"PROBE memory-repair .* result=0",))
            check("automatic concurrent private-file JIT", guest_run + " --diagnostics --user 1000:1000 --store "
                  + root + "/store --deadline-seconds 60 -- /bin/test --automatic-threads",
                  "PASS automatic concurrent private-file JIT: protected=1")
            check("automatic private-file JIT during vfork", guest_run + " --diagnostics --user 1000:1000 --store "
                  + root + "/store --deadline-seconds 60 -- /bin/test --automatic-vfork",
                  "PASS automatic private-file JIT with a kernel-blocked vfork parent: exec=1",
                  patterns=(r"vfork parent: exec=0", r"CLONE_VM process: exec=0", r"CLONE_VM process: exec=1"))
            for name, writer in (("process", 0), ("remote", 1), ("mem-fd", 2)):
                scope = "launch" if writer == 2 else "address-space"
                patterns = [rf"PROBE memory-repair .* result=0 scope={scope}"]
                if not writer:
                    patterns.append(r"PROBE memory-repair .* scope=address-space otherEvents=[1-9][0-9]*")
                elif writer == 2:
                    patterns.extend(rf"independent process: writer={mode} completed=[1-9][0-9]*"
                                    for mode in (3, 4))
                for entry in ((0, 1) if writer == 2 else (1,)):
                    check(f"automatic private-file JIT with {name} entry={entry}",
                          guest_run + " --diagnostics --user 1000:1000 --store "
                          + root + "/store --env SHROOT_MACHINE_ENTRY=" + str(entry)
                          + " --deadline-seconds 60 -- /bin/test --automatic-" + name,
                          f"PASS automatic private-file JIT with independent process: writer={writer}",
                          patterns=tuple(patterns))
            check("failed proc-memory opens do not widen JIT quiescence",
                  guest_run + " --diagnostics --user 1000:1000 --store "
                  + root + "/store --deadline-seconds 60 -- /bin/test --automatic-failed-mem",
                  "PASS failed proc-memory opens:",
                  patterns=(r"PROBE memory-repair .* result=0 scope=address-space",
                            r"PROBE memory-open-result .* result=-20 tracked=1",
                            r"PASS automatic private-file JIT with independent process: writer=0"),
                  absent_patterns=(r"scope=launch",))
            return
        if args.case in ("all", "fs-dispatch", "fs-directory"):
            check("broker directory notification ownership", executable("fs_dispatch") + " --broker-directory "
                  + root + "/broker-directory", "PASS broker directory notification ownership")
        if args.case in ("all", "fs-provider"):
            check("provider lookup and continuation ownership", executable("fs_dispatch") + " --fuse-device "
                  + root + "/provider-store", "PASS FUSE device: typed authority")
            if args.case == "fs-provider":
                return
        if args.case in ("all", "input-session"):
            check("device events and input lifetime", executable("fs_dispatch") + " --input "
                  + root + "/input-store", "PASS input transport, discovery")
            if args.case == "input-session":
                return
        if args.case in ("mount-namespace", "restricted-descriptors"):
            check("fixture directories", "mkdir -p " + root + "/source/tmp " + root + "/source/bin " + root + "/source/attached")
            check("stage guest image", "cp " + executable("resource_guest") + " " + root + "/source/bin/test")
            check("import fixture", executable("service") + " --import " + root + "/source " + root + "/store")
            if args.case == "mount-namespace":
                check_namespaces()
            else:
                check("restricted descriptor IO and prctl policy", guest_run + " --user 0:0 --store "
                      + root + "/store --deadline-seconds 30 -- /bin/test restricted-descriptors /tmp",
                      "PASS restricted proc-root preserves typed IO, reused descriptor IO, mutation and flags")
            return
        if args.case in ("all", "fd-observation"):
            check("native FD table observation", executable("fd_observation"),
                  "PASS native FD table observation and terminal custody")
            check("production FD table helper", executable("task_helper_fd") + " " + shlex.quote(root),
                  "PASS production FD table helper custody and cancellation")
            if args.case == "fd-observation":
                return
        if args.case == "ipc-origin-guest":
            check_ancillary(origins_only=True)
            return
        if args.case in ("all", "ipc-ancillary", "ipc-paths", "ipc-custody"):
            check("native IO interruption and custody", executable("ipc_io_job"),
                  "PASS native IO interruption, completion race and FD custody")
            check_ancillary()
            if args.case in ("ipc-ancillary", "ipc-paths", "ipc-custody"):
                return
        if args.case == "mount-policy":
            check("shared mount policy", executable("test_mounts") + " --mount-policy " + root + "/policy",
                  "PASS mount policy exec peers", absent_patterns=(r"assertion .* failed",))
            return
        if args.case == "transient-store":
            check("temporary filesystem custody", executable("test_mounts") + " --transient-store " + root + "/transient",
                  "PASS transient store: queued rights outlive publisher",
                  absent_patterns=(r"assertion .* failed",))
            return
        if args.case == "mount-graph":
            check("portable mount graph", executable("test_mounts") + " --mount-graph " + root + "/graph",
                  "PASS mount graph exec peers", absent_patterns=(r"assertion .* failed",))
            return
        if args.case == "inode-custody":
            check("portable inode custody", executable("test_mounts") + " --inode-custody " + root + "/custody",
                  "PASS portable inode custody", absent_patterns=(r"assertion .* failed",))
            return
        if args.case == "origin-transfer":
            check("portable message origins", executable("test_mounts") + " --origin-transfer " + root + "/origins",
                  "PASS portable message origins",
                  patterns=(r"PASS immutable FD bundles:", r"PASS authenticated capsule:",
                            r"PASS unknown capsule:", r"PASS discarded capsule:", r"PASS capsule rollback:"),
                  absent_patterns=(r"assertion .* failed",))
            return
        if args.case in ("all", "ipc-peer", "ipc-origins"):
            check("asynchronous IPC origin transfer", executable("fs_dispatch") + " --ipc-origins " + root + "/origin-store",
                  "PASS asynchronous IPC origin transfer", absent_patterns=(r"assertion .* failed",))
            if args.case == "ipc-origins":
                return
        if args.case in ("all", "ipc-peer"):
            check("launch registry lifetime", executable("test_launch_registry"),
                  "PASS launch ownership")
            check("private IPC peer negotiation", executable("test_ipc_peer") + " " + root + "/peer",
                  "PASS private IPC peer negotiation")
            check("asynchronous IPC peer admission", executable("fs_dispatch") + " --ipc-peer " + root + "/peer-store",
                  "PASS asynchronous IPC peer admission")
            if args.case == "ipc-peer":
                return
        if args.case in ("all", "stores"):
            check("native mount invalidation", executable("test_native_mounts"),
                  "PASS native mount snapshots:")
        if args.case == "stores":
            for name in ("mounts", "inodes", "snapshot"):
                check(name + " model", executable("test_" + name) + " " + root + "/model-" + name,
                      absent_patterns=(r"assertion .* failed",))
            return
        if args.case in ("all", "kernel-baseline"):
            check("mandatory capability admission", executable("capabilities") + " "
                  + executable("supervisor") + " " + executable("bootstrap"),
                  "PASS capability isolation")
            check("exact task kernel baseline", executable("kernel_baseline"),
                  "PASS exact task kernel baseline")
            check("resource model with 5.15 host profile", executable("kernel_baseline") + " --host "
                  + executable("test_task_resources"), "PASS resource ownership")
        if args.case in ("kernel-baseline", "namespaces", "devpts", "mount-flags"):
            check("fixture directories", "mkdir -p " + root + "/native " + root + "/source/tmp "
                  + root + "/source/bin " + root + "/source/attached")
            check("stage guest image", "cp " + executable("resource_guest") + " " + root + "/source/bin/test")
            check("import fixture", executable("service") + " --import " + root + "/source " + root + "/store")
            if args.case == "namespaces":
                check_namespaces()
            elif args.case == "devpts":
                check_devpts()
            elif args.case == "mount-flags":
                check_mount_flags()
            else:
                check_kernel_workflows()
            return
        if args.case == "pidfd-table":
            result["experiment"] = {"productionAdapter": False, "requiresThreadPidfd": True}
            check("same-group private-table pidfd", executable("pidfd_table") + " "
                  + root + "/pidfd", "PASS same-group private-table pidfd feasibility")
            return
        if args.case in ("all", "open-job"):
            check("deferred open ownership", executable("test_open_job"),
                  "PASS deferred open ownership")
            if args.case == "open-job":
                return
        if args.case == "ofd-order":
            check("active OFD ordering", executable("test_task_resources"),
                  "PASS resource ownership", patterns=("PASS exact OFD ordering", "PASS conservative inode ordering"))
            return
        if args.case == "rpc":
            check("filesystem RPC ownership", executable("test_rpc") + " "
                  + root + "/model-rpc", "PASS filesystem service fixture")
            return
        if args.case == "fs-dispatch":
            result["experiment"] = {"productionAdapter": False, "productionFilesystemWorker": True,
                                    "sharedRequestBuffers": True, "execBootstrap": "ADDFD_SEND"}
            check("shared request buffer ownership", executable("test_interception_buffers"),
                  "PASS shared request buffer ownership")
            value = check("filesystem worker descriptor transport", executable("fs_dispatch") + " "
                          + root + "/dispatch", "PASS filesystem worker descriptor transport experiment")
            cases = [line for line in value["output"].splitlines()
                     if line.startswith("PASS filesystem worker O_PATH ")]
            result["experiment"]["cases"] = len(cases)
            result["experiment"]["execCases"] = sum(" exec=1 " in line for line in cases)
            result["experiment"]["sharedFilesCases"] = sum(" sharedFiles=1 " in line for line in cases)
            return
        if args.case == "fd-lifecycle":
            result["experiment"] = {"productionAdapter": False}
            value = check("native private-FD lifecycle", executable("fd_lifecycle") + " "
                          + root + "/lifecycle", "PASS native private-FD lifecycle experiment")
            result["experiment"]["limitations"] = [line.removeprefix("LIMIT ")
                for line in value["output"].splitlines() if line.startswith("LIMIT ")]
            return
        if args.case == "fd-messages":
            result["experiment"] = {"productionAdapter": False, "adoptionBlocked": True,
                                    "runtimeMessageOwner": True}
            value = check("message-bound descriptor origins", executable("fd_handoff") + " "
                          + root + "/messages --origin-messages", "PASS message-origin association experiment")
            result["experiment"]["limitations"] = [line.removeprefix("LIMIT ") for line in value["output"].splitlines()
                                                     if line.startswith("LIMIT ")]
            result["experiment"]["observations"] = [line for line in value["output"].splitlines() if line.startswith("OBS ")]
            result["experiment"]["unavailable"] = [line.removeprefix("UNAVAILABLE ") for line in value["output"].splitlines()
                                                    if line.startswith("UNAVAILABLE ")]
            result["experiment"]["unobserved"] = [line.removeprefix("UNOBSERVED ") for line in value["output"].splitlines()
                                                   if line.startswith("UNOBSERVED ")]
            return
        if args.case == "fd-parcel":
            result["experiment"] = {"productionAdapter": False, "adoptionBlocked": True}
            value = check("native kernel-parcel experiment", executable("fd_handoff") + " "
                          + root + "/parcel --kernel-parcel", "PASS native kernel-parcel experiment")
            result["experiment"].update({
                "limitations": [line.removeprefix("LIMIT ") for line in value["output"].splitlines()
                                if line.startswith("LIMIT ")],
                "unobserved": [line.removeprefix("UNOBSERVED ") for line in value["output"].splitlines()
                               if line.startswith("UNOBSERVED ")]})
            return
        if args.case in ("launch-cancel", "proc-custody", "exec-custody", "fd-custody", "fd-custody-dirfd", "fd-custody-nofollow", "fs-buffer", "fs-signals", "fs-results", "fs-export", "fs-return", "fs-directory", "fs-capture", "xattr"):
            check("fixture directories", "mkdir -p " + root + "/native " + root + "/source/tmp "
                  + root + "/source/bin " + root + "/source/attached")
            check("stage guest image", "cp " + executable("resource_guest") + " " + root + "/source/bin/test")
            check("import fixture", executable("service") + " --import " + root + "/source " + root + "/store")
            if args.case == "xattr":
                check_xattrs()
                return
            if args.case == "launch-cancel":
                check_cancellation()
                return
            if args.case == "proc-custody":
                check_proc_custody()
                return
            if args.case == "exec-custody":
                check_exec_custody()
                return
            if args.case == "fs-capture":
                check_captures()
                return
            if args.case == "fs-signals":
                check_mutation_signals()
                return
            if args.case == "fs-buffer":
                check_buffers()
                return
            if args.case == "fs-results":
                check_buffer_descriptors()
                return
            if args.case == "fs-export":
                check_exports()
                return
            if args.case == "fs-return":
                check_returns()
                return
            if args.case == "fs-directory":
                check_directories()
                return
            custody = args.case.removeprefix("fd-")
            native_custody = custody.replace("custody", "custody-native", 1)
            check("native terminal custody", executable("resource_guest") + " " + native_custody + " " + root + "/native",
                  "PASS terminal RPC custody")
            check("guest terminal RPC custody", guest_run + " --store " + root
                  + "/store --deadline-seconds 60 -- /bin/test "
                  + custody + " /tmp", "PASS terminal RPC custody")
            return
        if args.case == "mapping-task":
            check("native private mapping task", executable("mapping_task"),
                  "PASS private mapping task kernel feasibility")
            return
        if args.case in ("all", "cgroup"):
            check("resource ownership model", executable("test_task_resources"))
        if args.case == "cgroup":
            check("cgroup fixture directories", "mkdir -p " + root + "/source/bin " + root + "/source/tmp")
            check("cgroup fixture image", "cp " + executable("resource_guest") + " " + root + "/source/bin/test")
            check("cgroup fixture import", executable("service") + " --import "
                  + root + "/source " + root + "/store")
            check_cgroups()
            return
        if args.case in ("all", "fd-handoff"):
            check("native descriptor publication", executable("fd_handoff") + " " + root + "/handoff",
                  "PASS native FD publication boundaries")
        if args.case == "fd-handoff":
            return
        for name in (("inodes", "mounts", "credentials", "rpc", "snapshot") if args.case == "all" else ()):
            check("filesystem model " + name, executable("test_" + name) + " "
                  + root + "/model-" + name, absent_patterns=(r"assertion .* failed",))
        check("fixture directories", "mkdir -p " + root + "/native " + root + "/source/tmp "
              + root + "/source/bin " + root + "/source/attached")
        check("stage guest image", "cp " + executable("resource_guest") + " " + root + "/source/bin/test")
        check("stage root fixture", "cp " + executable("root_guest") + " " + root + "/source/bin/root-test")
        check("import fixture", executable("service") + " --import " + root + "/source " + root + "/store")
        if args.case == "all":
            check_kernel_workflows()
            check_namespaces()
            check_buffers()
        if args.case != "roots":
            check("native kernel namespace capabilities", executable("resource_guest") + " probe " + root + "/native")
            check("native resource semantics", executable("resource_guest") + " native " + root + "/native",
                  "PASS process resources")
            runner = guest_run + " --store " + root + "/store --deadline-seconds 60 -- "
            for protected in (False, True):
                check("initial cwd and proc origins" + (" protected" if protected else ""),
                              guest_run + " --diagnostics --cwd /tmp --store "
                              + root + "/store --deadline-seconds 60 -- /bin/test "
                              + ("origins-protected" if protected else "origins") + " /tmp",
                              "PASS initial cwd and proc aliases",
                              patterns=(r"PROBE image-cwd pid=\d+ known=1",),
                              absent_patterns=(r"PROBE path-capture pid=\d+ base=\d+",))
                check("RPC cwd without private capture" + (" protected" if protected else ""),
                      runner + "/bin/test " + ("rpc-cwd-protected" if protected else "rpc-cwd")
                      + " /tmp", "PASS RPC cwd without guest directory capture")
            check("guest unprivileged chroot", runner + "/bin/test unprivileged-root /tmp",
                  "PASS unprivileged chroot denied without changing cwd")
            check("guest resource semantics", guest_run + " --user 0:0 --store "
                  + root + "/store --deadline-seconds 60 -- /bin/test guest-managed /tmp", "PASS process resources")
            check("guest namespace capabilities", runner + "/bin/test probe /tmp")
        check("guest roots", guest_run + " --user 0:0 --store "
              + root + "/store --deadline-seconds 60 -- /bin/root-test run", "PASS guest roots")
        check("native root directory", "mkdir -m 700 " + root + "/attached")
        check("guest native roots", guest_run + " --user 0:0 --store "
              + root + "/store --bind " + root + "/attached /jail --deadline-seconds 60"
              + " -- /bin/root-test attached", "PASS guest roots")
        if args.compare_installed:
            result["comparison"] = {}
            candidates = {"installed": "shroot", "candidate": guest_run}
            for workload in ("metadata", "spawn"):
                samples = {name: [] for name in candidates}
                counters = {}
                for turn in range(4):
                    order = list(candidates) if turn % 2 == 0 else list(reversed(candidates))
                    for name in order:
                        value = check(f"{workload} {name} round {turn}", candidates[name]
                                      + " --store " + root + "/store --deadline-seconds 60 -- /bin/test "
                                      + workload + " /tmp", "PASS workload=" + workload)
                        elapsed = int(re.search(r"elapsedNs=(\d+)", value["output"])[1])
                        if turn:
                            samples[name].append(elapsed)
                for name, binary in candidates.items():
                    value = check(f"{workload} {name} counters", binary
                                  + " --statistics --store " + root + "/store --deadline-seconds 60 -- /bin/test "
                                  + workload + " /tmp", "PASS workload=" + workload)
                    counters[name] = [line for line in value["output"].splitlines() if line.startswith("MD_")]
                result["comparison"][workload] = {"elapsedNs": samples, "counters": counters,
                    "medianNs": {name: statistics.median(values) for name, values in samples.items()}}
                def operations(lines):
                    return [re.sub(r"\s+(?:\w*Ns|ns)=\d+", "", line) for line in lines
                            if not line.startswith(("MD_CPU ", "MD_BROKER synchronousWake="))]
                result["comparison"][workload]["sameOperationCounts"] = (
                    operations(counters["installed"]) == operations(counters["candidate"]))
    finally:
        try:
            if not args.keep:
                check("remove owned fixtures", "rm -rf " + root)
        finally:
            try:
                executor.close()
            finally:
                result['executor'] = executor.evidence
                name = "resource-results.json" if args.case == "all" else "resource-" + args.case + "-results.json"
                (args.build / name).write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
