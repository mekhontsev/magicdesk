# Unix IPC Credentials

Guest identity belongs to the supervised task, not Android. `socket_identity`
adapts connection credentials and `socket_ancillary` adapts explicit credential
messages. Both use the authenticated namespace request path and the same
`guest_identity` snapshots as filesystem permissions. There is no D-Bus-specific
authentication bypass, payload proxy or second process-identity model.

## Connection Identity

`ipc_credentials` is a lazy, store-scoped metadata authority owned by each
namespace worker. Workers sharing a store serialize metadata operations with a
kernel file lock. They never hold this lock across guest connect, accept or IO.
`<store>/ipc` is transient state, separate from image/inode metadata. A shared
live-owner lease makes the first owner discard stale records, including socket
cookies retained across a device reboot. No service or kernel probe is added to
MagicDesk startup, and ordinary filesystem calls do not open this database.

Kernel `SO_COOKIE` identifies a socket across dup, fork and SCM_RIGHTS. Listen,
connect and socketpair capture effective guest UID/GID and supplementary groups.
SO_PEERCRED and SO_PEERGROUPS read copied connection snapshots, not the peer's
current credentials. A later credential drop, peer exit or FD transfer does not
rewrite them. Native peer PID is checked before publishing a snapshot.

For a connection to a registered guest listener, an otherwise unnamed client
gets a private abstract transport address. Accept resolves that exact connection
without netlink permissions or guessing by PID. The address adapter hides this
implementation name. Existing user-bound names are preserved. Connect preparation
and completion validate the listener revision; an ambiguous snapshot fails with
ESTALE, without replaying a successful connection or inventing an identity.

Unregistered/external endpoints keep kernel credentials. Abstract socket names
remain in the host's shared namespace. These mappings do not provide network
isolation or protect mutually hostile processes sharing the real executor UID.

## Explicit Credential Messages

SCM_CREDENTIALS validates the claimed guest IDs against the sending task. PID
must identify that task's process; virtual root may select guest UID/GID, while
other users may select only their real/effective/saved IDs. The kernel receives
the actual Android credentials, plus a sealed memfd capability as one extra
SCM_RIGHTS descriptor. Its random nonce, connection identity and kernel file
identity are recorded by the metadata authority. The receiving adapter removes
that descriptor and publishes the validated guest credentials instead.

This keeps payload bytes, blocking/readiness, FD transfer, partial sends and
message ordering in the kernel. Ordinary read/write and credential-free sends
do not create capabilities. Common ancillary buffers use stack scratch; larger
buffers use bounded temporary mappings. Recvmmsg uses one native batch so its
timeout and partial completion are not replaced by a userspace receive loop.
MSG_PEEK does not consume identity metadata; truncation closes unpublished FDs.

Current limits are explicit:

- Kernel-generated SO_PASSCRED messages without an explicit SCM_CREDENTIALS
  send retain native credentials. Unconnected datagram addressing is not
  virtually authenticated; registered socketpairs are supported.
- The private capability consumes one kernel descriptor slot per explicit
  credential message. It does not remove kernel ancillary/FD limits.
- Connection/message records remain until the last live owner exits. Long-lived
  workloads sending credentials continuously need lifecycle-aware reclamation;
  deleting records merely on receipt is unsafe with concurrent MSG_PEEK.
- Kernel capabilities, SELinux, resource limits, SO_PEERSEC and native attached
  resources remain real. A denied kernel operation is not reported as success.

## D-Bus And Coverage

An unmodified distribution supplies dbus-daemon, dbus-run-session, policy and
NSS entries for the selected guest user. No daemon is shipped in the APK.
Session buses use ordinary credential authentication and service activation.

The UID 2000 device suite covers Debian/glibc and Alpine/musl session buses,
independent launches sharing a store, rejection of another guest user, connection
snapshots after credential drops and peer exit, groups, short output buffers,
queued explicit messages, rejected credential forgery, MSG_PEEK, recvmmsg and
ordinary descriptor transfer. A stock Debian bus activates a GDBus fixture,
reports its caller as guest UID 0 and exchanges usable FDs in both directions.
An external native endpoint still receives UID 2000 and rejects a root claim.

A daemon may warn that its requested FD limit exceeds the executor's hard limit;
this does not justify fabricating a successful setrlimit. A working session bus
does not imply system-bus policy, arbitrary package configuration or full Linux
service startup. Device/build coverage remains that of the
[guest runtime](../../docs/guest-runtime.md).
