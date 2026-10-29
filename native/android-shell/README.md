# Android Shell

MagicDesk packages unmodified upstream mksh R59c for its Android shell execution.
The pinned, SHA-256-verified archive is the original upstream tarball mirrored
by Debian, not Debian's patched package. This is a build adapter, not a shell fork.
Future updates must pin a release/archive and review its Android configuration;
the build never follows a floating branch.

`CMakeLists.txt` uses the upstream option-table generator and a fixed public
Android/Bionic configuration based on AOSP's `external/mksh/Android.bp`, enabling
`HAVE_PERSISTENT_HISTORY`. It produces a stripped PIE executable named
`libmagicdesk_mksh.so` for Android's native-library packaging, with 16 KiB ELF
segment alignment. It is executed, never loaded into ART. The Gradle owner
supplies the APK minimum API (34) and the existing arm64 toolchain. POSIX `sh`,
`sed`, `sort` and `tr` are build-host tools; Windows CI supplies them through MSYS2.
`MAGICDESK_BUILD_SHELL` selects an absolute host shell path, overriding discovery.
`generate-table.cmake` places its companion tools first on `PATH` before invoking
upstream `Build.sh`, so native Windows CMake cannot accidentally select Windows
`sort.exe`. Paths remain separate, quoted arguments, including build directories
with spaces; Windows CI has a dedicated NDK build check for that case.
Per-file copyright/license notices, including embedded notices, are collected into the APK's
`assets/licenses/mksh/NOTICE.txt` without rewriting the sources.

`AndroidShell` owns interpreter paths and generated-script shebangs. Shell
selection uses the installed APK's native directory, never ambient `SHELL` or
a system-shell fallback. `ShellExecutionEnvironment` supplies the same executable
to interactive PTYs, non-PTY streams and one-shot commands. UID, service ownership
and terminal lifetime are unchanged. The runtime-local `sh` and `mksh` names are
atomically published symlinks to that executable, not additional builds. New
processes receive its path as `SHELL`; generated CLI scripts carry an absolute
shebang and an explicit `MAGICDESK_ANDROID_SHELL` for their own child scripts.

The upstream `MKSH_DEFAULT_EXECSHELL` build setting uses `/proc/self/exe`:
an executable text file without a shebang re-enters the same mksh, rather than
Android's `sh`. No source patch or inherited `EXECSHELL` is needed. An explicit
interpreter path or shebang remains authoritative, including failure when absent.
The Android execution layer does not assume `/system/bin/sh` exists. This is not
a promise that Android's utilities, Shizuku or `su` work without it.

Termux and guest Linux retain their own shells and PATH. MagicDesk's CLI wrappers
installed in Termux also use the bundled interpreter, but do not install `sh` or
`mksh` aliases there or change Termux's `SHELL`. No system binary or guest libc is
replaced. Resolution is lazy; missing mksh fails its requested command, not
unrelated Binder services or app startup.

`TerminalShellIntegration` sets shell-local `HISTFILE` and `HISTSIZE` in the owned
interactive rc. History lives in `$XDG_STATE_HOME/shell_history`, normally
`~/.local/state/shell_history`. Shell and root have separate HOME/state paths.
The shell owns the binary format, command boundaries, locking, append and startup
compaction. The 2000-command setting is a recall/compaction size, not a strict
on-disk quota while sessions are active. There is no database, polling, per-command
helper process, or shutdown-only save. Power-loss durability is not promised.
History variables are not exported into other shells, especially guest Bash.
The terminal's OSC-marked history/output menu remains session-local and independent.

## Verification

Run the focused `AndroidShellTest`, `ShellExecutionEnvironmentTest`,
`TerminalShellIntegrationTest`, `HostScriptsTest`, native PTY fixtures and APK
packaging checks. Rebuild mksh before running the isolated execution/history
fixture on an Android host:

```sh
python native/android-shell/test_history.py --shell app/build/androidShell/libmagicdesk_mksh.so
```

It uses private temporary homes and new subprocesses, never a user terminal.
Do not replace the APK or terminate an existing terminal just to run this fixture.
It checks `sh`/`mksh` identity, no-shebang execution with an isolated PATH, explicit
and missing interpreters, and noninteractive rc/history exclusion.
Verify new Console sessions under UID 2000 independently of Desktop: arrow-key
recall, concurrent consoles, multiline input, process termination/reopen, and
normal installer/Ctrl+C behavior. Repeat on API 34 and with the NDK-built APK;
successful local compilation is not firmware compatibility coverage.
