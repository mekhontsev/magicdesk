# Command Client

Standalone static ARM64 Bionic executables, targeting the APK's API-34 baseline.
No JNI, guest syscall adapter, D-Bus, libc loader or command catalog is embedded.

- `client` transfers argv to the app's CLI parser and streams stdout/stderr/status.
  File/stdin argument requests read the caller's environment. The server never
  substitutes Android paths for guest files.
- `bridge` wraps one native host invocation, preserving UID, PTY and process
  group. A registration socket owns its temporary key. Child exit, cancellation
  or wrapper death closes the channel; a bounded cancellation grace ends in
  SIGKILL. It does not turn daemonized descendants into managed sessions.
- `wire` supplies bounded loopback framing and one-shot lease registration to
  both the bridge and the guest supervisor. The supervisor retains ownership
  until its complete supervised tree ends.

The endpoint/build are inherited only from a prepared MagicDesk command
environment. Delegation replaces the inherited key with a random launch key;
clients cannot create another lease from it. No credentials are stored in
executables, rootfs files, shortcuts or the APK. Connecting sends a big-endian
magic, operation and length-prefixed key/build. Invocation adds argc/argv;
responses carry read-input, stdout, stderr and exit frames. Java constants live
in `AutomationCliWire`; the shared parser owns syntax and the tool catalog.
There is no reconnect/replay after a lost action result.

Connection and IO deadlines bound failure, not readiness. An idle owner uses
socket EOF, not heartbeat polling. The wrapper needs ordinary socket/fork/
signalfd support, not the optional guest kernel capabilities. It does not
require a shell executor merely to register a channel. Individual commands
retain their service checks and selected authority.

The [automation contract](../../docs/automation.md#linux-command-access) describes
resource lifetime and trust. Focused Java tests cover framing, parsing and
lease ownership; `native/guest-exec-lab/test_command_access.py` exercises installed
Debian/Alpine guests and native Termux/PRoot, with a separate App-only phase.
