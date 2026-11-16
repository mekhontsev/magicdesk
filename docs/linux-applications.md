# Independent Linux Applications

An installed Shroot environment can be copied into its own Android application:
open **Console > New session > Shroot environments**, select an environment,
then **Install Linux as an application**. Android confirms installation. Opening
the installed application requests confirmation before importing its backup.

Each package owns a native Shroot bundle, mksh, an interactive terminal and a
private store under its own Android UID. It needs neither MagicDesk nor Shizuku
to run. Closing the terminal window retains its session; **End session** stops
that session, and the notification's **Stop** action stops the application's
owned service and processes. Android process and battery policies still apply.

## Data And Updates

The export is a portable native backup, including guest inode metadata, not a
shared mutable directory. The source environment remains unchanged. The first
import copies the backup through an Android read grant; **Import backup** can
instead select a backup through the system document picker.

Exporting the same source identity again updates the same signed package. An
update replaces its bundled runtime without importing over its private store.
Package replacement stops that application's processes; stored files remain.
Uninstalling it deletes its Android-private data. Keep a Shroot backup before
uninstalling or clearing application data. End the terminal session, then choose
**Export backup** and a destination in Android's document picker. Export and
import use Shroot's native `tar.zst` format and preserve guest metadata. They
work without MagicDesk; cancellation releases the owned archive process,
streams and private temporary files.

Different packages have different Android UIDs. Guest root does not grant
access to another package's private files. Shroot's logical guest credentials
are not a substitute for this outer Android sandbox.

## Optional MagicDesk Connection

**Connect to MagicDesk** requests a visible, one-connection authorization dialog.
It offers audio alone or audio with one ready X11/Wayland session. An accepted
connection supplies routes to newly started terminals; existing launches retain
their original environment. It grants neither host shell commands nor arbitrary
host file access. X11 clients sharing a display share X11's display authority;
select a separate graphical session for mutually untrusted clients.

**Disconnect from MagicDesk** revokes the connection's endpoints and accepted
graphics streams. It does not stop Linux or unrelated host clients. Closing the
selected graphics server or losing either Android process also releases the
connection. Linux remains usable without reconnecting. Android document grants
remain separate from audio/graphics permission.

## Implementation Boundary

- `guest-template` owns the generated application's Android lifecycle, private
  data, terminal and optional client connection. It runs native executables;
  the syscall runtime is never loaded into ART.
- `terminal-runtime` contains the shared terminal renderer, input contract,
  PTY framing and caller-owned native relay.
- `connection-runtime` contains Binder endpoint admission and revocable Unix
  stream ownership. Sockets are accepted under the executor's UID, checked and
  transferred as descriptors. Ordinary protocol IO is direct, not a Java proxy.
- `LinuxConnections` owns host-side consent-scoped audio/graphics capabilities.
  `GraphicalConnection` borrows an existing protocol server, not its executor,
  display, viewer or command authorization.
- The standalone [Shroot project](https://github.com/mekhontsev/shroot) owns
  storage, backup/import and Linux execution. Android packaging is not part of
  its runtime or C management API.

The Binder contract is an internal integration interface, not a stable public
ABI. Runtime and persistent-storage compatibility are governed independently
by Shroot's native admission contract.
