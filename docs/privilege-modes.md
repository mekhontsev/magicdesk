# Privilege Boundaries

The APK baseline is Android 14; managed Desktop requires Android 15. Ordinary
UI, shared services, Desktop ownership and client authorization are separate
boundaries. A missing Desktop capability does not disable an independent tool.

## Identities And Prerequisites

| Boundary | Identity and authority |
| --- | --- |
| Ordinary UI and Android content integration | MagicDesk app UID and its Android permissions |
| Privileged files, shell, display, task and input operations | One authorized command service, normally shell UID 2000; started through Shizuku or optional `su` |
| Termux commands and PTYs | Termux UID, with its external-command configuration and MagicDesk's `RUN_COMMAND` grant |
| MCP request | Listener token and grants, followed by the operation's service and Android permission checks |
| Built-in CLI | Private channel inherited by a MagicDesk-launched shell; the same service prerequisites and operation implementation as MCP |
| Optional Kernel Fixes APK | Separate application with an explicit root workflow; never a main-APK dependency |

`RuntimeCapabilities` reports prerequisites; it does not grant permissions
or certify a firmware API. MCP and built-in UI can start before the privileged
service. Files and shell-backed operations require it. Termux's terminal transport has
separate authorization. Creating a virtual display requires shell access, not
HOME or WMShell Desktop.

## Shell Service

**Settings > Integrations > Privileged service** selects Shizuku (the default)
or **Root (su)**. Shizuku uses its official authorization and UserService API;
direct root asks the installed root manager to start the same service. There
is no libsu dependency, automatic backend fallback, or root requirement.

The separate **Limit service to shell UID 2000** switch applies to either
backend. With it disabled, the service retains the selected launcher's UID
(2000 or 0). With it enabled, an initial UID 0 is reduced before the working
Java process starts. A Shizuku server already running under UID 2000 needs no
additional bootstrap. Both settings are app-private and captured at process
startup; changing them never changes a live service's identity. They do not
change the UID of Shizuku itself.

The native bootstrap establishes real/effective/saved UID and GID 2000,
shell supplementary groups, zero Linux capabilities and the shell SELinux
domain before `exec app_process`. Java verifies the resulting identity before
exposing its Binder. An unsupported identity transition fails explicitly; it
does not continue with root privileges. Reducing only the calling Java thread
would not restrict existing ART/Binder threads and is not used.

This limits the working service, not the application's permanent root-manager
authorization. Root authorization is still used during startup, and a trusted
client with arbitrary shell/input access is not confined by an application
sandbox. Root does not grant SystemUI identity, ownership of other apps' window
tokens, or a guarantee that a firmware operation works.

Both transports expose `IShellCommandService` and the same operations. The app
checks the connected service's UID and APK build before publishing readiness.
The standalone process hands its Binder to a permission-protected provider;
the app checks a one-use startup nonce, independently reported child PID,
calling UID and build. Its command endpoint accepts only the exact app UID.
The temporary root bootstrap exposes no file, input or Desktop operations.

`ShellAccess` caches immutable connection state updated by binding and
permission events. Launcher readiness is not service readiness. Failed binding
attempts report an error without repeated root prompts; an explicit connection
request or a new launcher-availability event can retry. Cancelled callbacks
cannot replace a later binding. Ordinary commands do not repeat startup probes.

Finite operations use typed AIDL or bounded shell commands. Long-lived resources
have explicit Binder/descriptor owners. Process or service death releases those
resources; cleanup journals preserve pending restoration without overwriting
state another owner changed.

The privileged service can manage tasks, displays, input associations and
accessible files only where Android's permissions, SELinux and framework APIs
allow it. Shell is not SystemUI and does not own another application's window
tokens. Optional vendor methods are separately detected and allowlisted.

## Content And Launch Authorization

Files operates on paths accessible to the connected shell UID. Other Android
apps receive bounded content-URI grants for selected files, never the shell
Binder or unrestricted filesystem authority. Directory clipboard operations
stay internal. URI grants, app identity and shell placement authority are
checked independently.

Android intent integration checks whether the target is accessible to the
MagicDesk application. Shell supplies placement authority, not permission to
launch arbitrary protected targets on behalf of the app. App-authorized
PendingIntents retain their creator identity and grants.

Retained shell and Termux terminals preserve their selected backend. A failed
Termux request is not retried as a shell command. Closing a window detaches its
view; explicit session termination or runtime exit releases the PTY. Package
replacement and process death do not preserve those terminals.

## Input And HOME Ownership

Physical keyboards and mice remain Android devices. A session journals and
changes input-location associations for its selected display. Composite devices
sharing a location share one route; hot-plug callbacks reconcile them without
reading or forwarding the physical event streams.

`DesktopShortcutService` is a key-only Accessibility filter. It consumes
Desktop shortcuts from confirmed desktop keyboards, not ordinary editor text,
and requests no window-content access. Its session-owned enablement preserves
other Accessibility services. Layout cycling resolves Android physical-keyboard
layouts against enabled IME subtypes and synchronizes the selected subtype.
An IME must expose those languages through Android; MagicDesk does not choose
a replacement IME.

The phone touchpad owns one virtual relative mouse. Android handles cursor
acceleration, hover, dragging and right click. The external editor connects
directly to the user's normal phone IME through Android's display IME policy.
MagicDesk does not capture or relay its text.

Close releases routing, shortcut enablement and the phone pointer before any
owned display removal. Binder death and durable ownership records cover
interrupted cleanup; unknown inventory is not treated as an empty device list.

Managed Desktop temporarily holds HOME. Close restores the previous role state
before tearing down its remaining task surfaces. Disabling HOME components is
a later cleanup phase, so Android cannot remove a live host during task parking.
Inactive MagicDesk HOME components are disabled. Startup recovery relinquishes
stale HOME ownership before either privilege backend starts.

## MCP Access

The built-in CLI is a separate local adapter, not an unauthenticated HTTP path.
Its ephemeral channel is supplied automatically to explicitly launched shell
and optional Termux processes. It binds only to loopback and requires the inherited
256-bit secret for every request. Loading CLI code from the public APK is not authority.
Child scripts inherit the user's access; this is not a sandbox for untrusted
scripts. The CLI neither starts `su` nor changes the selected service identity.

MCP is disabled by default. Loopback binds to `127.0.0.1:8765`; optional network
access binds to one selected private IPv4 interface and configured port.
Tokens and grants are independent for the two listeners. Authentication is
required even for observation.

The full catalog remains discoverable. Observation is always allowed to an
authenticated client; additional grants cover:

- Desktop/application control.
- Injected input, self-tests and force-stop.
- Screen, clipboard and notification contents.
- File reads/downloads.
- File writes/uploads.
- Shell commands, terminals and background execution.
- MagicDesk APK updates.

These are operation gates, not isolation between mutually untrusted clients.
Shell access can read and modify files; input can operate privileged UI.
Grant changes affect subsequent requests, not rollback of accepted actions.

Network transport is HTTP without TLS. Use a trusted LAN or protected VPN/tunnel,
never a directly exposed Internet endpoint. Android permissions and service
availability still apply after MCP authorization.

APK update is same-package and same-signer, using Android PackageInstaller.
A separate one-operation shell worker survives replacement, records the
installer result and resumes enabled automation. Reconnect is client-owned;
an unknown result is not permission to install again. See
[Automation](automation.md) for the exact operation protocol.

## Persistent Setup Versus Session State

Desktop setup on API 35+ manages two common global overrides:

```text
enable_freeform_support = 1
force_resizable_activities = 1
```

These are persistent provisioning, not Close-time leases. Firmware-specific
setup is owned by its extension; Nubia additionally manages the two allowlisted
desktop eligibility/rounded-corner properties described in the
[vendor audit](nubia-vendor-audit.md).

The optional `force_desktop_mode_on_external_displays` switch lives in
**Settings > Android system**. Android is its only value store. Changes require
shell access and no active Desktop; the UI warns about decorations and advises
reconnection or a firmware-dependent restart. It is not a required setup gate,
input-routing substitute or startup write. Close leaves it unchanged.

By contrast, HOME, input routes, display-default mode, external IME policy and
managed task density have session owners and restoration rules. Display
resources have their own lifetime: Close does not remove them, and removal
requires verified MagicDesk ownership.

**Restore defaults** removes Desktop setup overrides and primary-display
size/density/scaling overrides, and normalizes stale phone tasks. It restores
system defaults, not arbitrary earlier installation values.

## Optional Phone Power And Hardware

Phone-screen power control uses discovered Android display commands.
The optional Nubia provider additionally protects other desktop app UIDs through
its transient `cfreezer` heartbeat while the phone screen is off. MagicDesk's
own UID is excluded and relies on its HOME status on that inspected firmware.
This is not a general Android HOME guarantee for every power manager.

The helper restores power and releases its owned protection on normal cleanup
or owner loss. Hardware settings and caption-privacy overrides have their own
capability checks and restoration owners. Details belong in the
[Nubia vendor audit](nubia-vendor-audit.md), not in shared input policy.

## Artifact Trust

Stable and development APKs use the same signing certificate. Its SHA-256 is:

```text
3A:F3:FE:F8:95:AC:BC:9C:B7:7B:FD:BB:7E:91:79:42:
95:70:72:14:97:E3:6E:C1:E4:19:68:C9:4B:52:99:50
```

The main APK contains no kernel module or loader. The independent
**MagicDesk Kernel Fixes** APK has its own explicit root workflow and is not
discovered or launched by MagicDesk. Its exact firmware restrictions are in
[VITURE XR resolution fix](xr-resolution-fix.md).
