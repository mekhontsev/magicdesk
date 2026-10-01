# Filesystem Watches

The inode namespace provides Linux inotify directory/name notifications without
a second namespace or a polling daemon. One namespace worker owns the subscriptions,
native event source and delivery queues for its supervised guest tree. Independent
launches watching one store observe each other's committed mutations.

## Namespace Events

`inode_events.c` records create, link, unlink, rename/exchange and socket-name
publication in the same SQLite transaction as the corresponding name change.
Move pairs share a nonzero cookie; directory events retain `IN_ISDIR`. Rollback
cannot expose a name event. Registration establishes its journal cursor while
holding the store admission lock. A finite native backlog is drained before
publishing a new subscription.
Registration and watch updates require guest read permission on the logical
inode; path traversal uses the same launch-local credentials as other filesystem
operations. Native attached objects retain kernel permission checks.

The store includes `events` and `watch.lock`. Each active watch owner
holds a shared kernel lock; mutators check its presence inside store admission.
Without observers, mutations append no event rows. The last reader's closure
retires its instance; process death releases presence through kernel FD lifetime.
No persisted subscriber counter, heartbeat or timer cleans up stale owners.
Old formats are rejected without migration or deletion.

Native inotify on the database wakes the worker; it reads only committed journal
rows after acquiring store admission. The journal retains 65,536 entries plus at
most one 255-entry trimming batch. A missing range produces `IN_Q_OVERFLOW`.
Native data events come from retained backing inodes and the flat object directory;
ordinary guest file reads/writes remain native. Native event batches are bounded
so a continuous writer cannot monopolize namespace admission. SQL programs are
prepared lazily and reused; namespace rows and aliases are not cached.

Subscriptions identify logical objects, independently of current backing names.
Owners observe local and retained immutable-source directories. Copy-up prepares
and closes data outside those watched directories before publication, so internal
copying does not appear as application modification. Native attached directories
use the existing foreign-object watch path. Lower-backed data events are not
isolated from other instances sharing those same kernel inodes.

## Descriptors And Delivery

`watch_queue.c` owns bounded record queues with adjacent coalescing, complete
record boundaries and one overflow marker. Record storage is recycled to its
high-water mark. A Unix stream socket carries one readiness byte, never event payloads.
Its reader supplies native poll/epoll and last-reader lifetime, including dup,
fork, exec and queued SCM_RIGHTS. The owner retains no permanent read alias.

`watch_calls.c` activates FD-class seccomp selectors before returning a watch or
ordinary duplicate/received alias. `watch_activation.c` tracks installed filters
by thread group: pthreads share that metadata, fork copies it and exec retains it.
Selectors use the low nine descriptor bits, bounding activation to 512 filters
per retained chain even across arbitrary descriptor-number churn. Reusing a
class installs no filter. A selector
only requests interception; retained kernel device/inode identity decides whether
the descriptor still belongs to a watch. Unselected classes keep native reads.
An ordinary descriptor in an armed class takes interception, then executes its
native operation. Saturating all classes therefore mediates these operations on
every FD; this bounds filter growth, not worst-case interception cost. Kernel
filter limits and divergent TSYNC chains still return explicit errors.

The original listener declares a read gate. Selected calls replay through it
with their signal mask restored before blocking. `watch_broker.c` holds empty
blocking reads asynchronously in the existing worker, progressing on native
readiness, namespace requests or cancellation. No readiness timeout invents data.
Concurrent readers consume records at the single queue owner. `read`, `readv`
and `FIONREAD` retain record sizes, EAGAIN/EINVAL and short-result boundaries;
failed output copying does not consume the queue. Cancellation after copying
can leave delivery unconfirmed, so callers must not automatically replay reads.
Splice, tee and sendfile from a watch return EINVAL instead of consuming its marker.
Other operations on the readiness socket are not a general emulation of every
inotify-FD ABI; native recv/write/shutdown must not be used on it.

When pidfd export or remote memory access is denied, the same scratch-stack
adapter copies records in the calling task, without restoring dumpability.
The worker reserves the queue head and removes readiness until the client
acknowledges its copy. A rejected copy releases the reservation without consuming
records; successful delivery consumes them before sending confirmation. Appends
remain possible but cannot coalesce into leased records. No store/SQLite lock is
held across delivery. A missing acknowledgement consumes possibly delivered
records rather than duplicating them; the caller reports unconfirmed transport
failure and must not retry automatically. A bounded peer deadline releases the
reservation even if the client disappears.

Protected empty blocking reads wait through native `recvfrom(MSG_PEEK)` on the
readiness socket, with their original signal mask restored. This does not consume
the marker or block the namespace worker. Competing readers wait while a delivery
lease is outstanding. EINTR and SA_RESTART retain the application's policy.

Application filters remain active on replay and adapter transport. This mechanism
does not supply a new security boundary or override kernel permission denials.

## Verified Coverage

The production runner tests real UID 2000 on RM11/API 36/Linux 6.12.23 with
Bionic, Debian glibc and Alpine musl: namespace events, rename cookies, file-data
events, dup/fcntl, fork, 64 execs without filter growth, SCM_RIGHTS receive and
batch receive, concurrent readers, EINTR/SA_RESTART, readv, short/faulted output,
descriptor reuse and unrelated native reads. Separate store tests kill a writer
before commit, retire the final reader and inject a journal gap.
The same glibc/musl suite also runs with dumpability disabled, including faulted
buffers, concurrent readers and signal restart. Descriptor churn covers 7,680
distinct dup/fcntl targets with bounded filter count. RPC controls reject a copy
while another request appends, withhold acknowledgement and kill the service.

Stock Debian GIO's `GInotifyFileMonitor` receives create/rename/delete from an
independent launch; the fixture rejects a polling backend. Stock D-Bus registers
its config-directory watches and completes a session-bus request without watch
warnings. Installed-APK checks run stock Debian and Alpine Mousepad/Thunar on an
ordinary virtual display with the app-UID Wayland server. An independent guest
launch modifies the open document; Mousepad notifies and reloads the exact text.
Thunar observes an externally created file, renames its visible selection, and
returns to the parent when the viewed directory moves. Independent readback and
zero process exit are required. This is not arbitrary GUI certification.
See [fixture commands](../guest-exec-lab/README.md#production-watch-checks).

## Limits

- Native data events identify an object, not the logical dentry used to open it.
  Directory projection reports its currently linked aliases, including multiple
  hardlink names. Delayed IO around rename/unlink is not exact Linux dentry history.
  Namespace-driven ctime/parent-mtime updates remain incomplete.
- Final logical unlink emits DELETE_SELF without tracking all open references;
  native Linux may defer that notification until the last reference is gone.
- FD activation is not atomic publication to arbitrary concurrent CLONE_FILES
  users. Cross-thread-group shared tables and transfer of a watch instance to
  another independent supervisor are not supported ownership contracts. Independent
  watchers on a shared store are supported and tested.
- The 512-class selector bound shares the kernel filter budget with the
  application's filters; class collisions add interception. Application filters restricted to
  the original instruction address can reject gate replay. Older kernels and
  other firmware require their own device coverage.
