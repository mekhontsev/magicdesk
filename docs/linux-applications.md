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

## Android Folders

In a Linux APK, choose **Android folders** from the terminal menu. In MagicDesk
choose **Console > New session > Shroot environments**, select the User or Shell
environment, then **Android folders**. Select a directory in Android's picker
and choose **Read only** or **Read and write**. Write access is available only
when the provider grants it. Repeat to connect additional folders.

New Linux sessions mount each tree at the path shown, for example
`/mnt/android/Projects`. This is a live view, not an import. The saved choice
survives a restart; it is private to the Android package and selected environment,
and is not part of a Linux backup. A revoked grant makes that mount inaccessible;
it does not prevent the other mounts or Linux from starting. Select the folder
again to renew its grant.
Use `umount '/mnt/android/Projects'` for the current Linux namespace. To stop
mounting a folder in future sessions, select its row in **Android folders** and
choose **Forget**. Neither action deletes provider files.

These mounts expose documents, not a POSIX backing store for a distribution.
Provider capabilities determine which edits are possible. Atomic replacement,
recursive deletion through `rmdir`, Unix ownership and executable files are not
provided. A provider returning a non-seekable descriptor may not support random IO.

## Optional MagicDesk Connection

To show a graphical program from a separate Linux APK:

1. In the **Linux APK**, open **⋮ > Connect to MagicDesk**. Leave **Audio + new X11 display**
   selected and tap **Connect**. MagicDesk provides the screen, keyboard data and sound.
   No Termux, Shizuku, second Linux installation or managed Desktop is needed.
2. In the **Linux APK**, save any work in the old terminal, choose **End session**,
   then **New terminal**. Run the installed graphical program there. No manual
   `DISPLAY` or socket configuration is needed.
3. In **MagicDesk**, long-press the console icon in Control Panel, open **Graphical sessions**,
   select the display named after the Linux APK, and open its viewer.

The consent dialog also offers ready X11/Wayland sessions and **Audio only**.
The new display closes with its connection; a borrowed display remains running.
Existing terminals keep their original environment. The connection grants neither
host shell commands nor arbitrary host file access. X11 clients sharing a display share X11's display authority;
select a separate graphical session for mutually untrusted clients.
The X11 credential is copied into a connection-private directory and attached
read-only to the guest. The host does not share its own authorization directory.

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

## Verification

Device acceptance on Android 16 / ARM64 covers two generated, non-debuggable
Alpine packages with distinct Android UIDs. Checks include independent file
changes, hardlink metadata, denied guest access to the other package's private
path, same-package runtime updates without reimport, document-picker backup and
restore, and Activity recreation while the picker is open. Closing a window
retains its PTY; ending a session or stopping its notification releases its owned
process tree without stopping the other package.

The generated application and Linux both cold-start with MagicDesk force-stopped.
Optional cross-UID checks cover X11 and Wayland offscreen rendering/input,
X11 authentication and read-only credentials, PipeWire/Pulse playback, denied
consent and connection revocation without terminating Linux. These are delivery
and integration checks, not the full distribution matrix or API 34 device
coverage. Android package sandboxing remains the isolation boundary.
