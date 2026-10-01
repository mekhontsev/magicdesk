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

The format-6 store includes `events` and `watch.lock`. Each active watch owner
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

## Descriptors And Delivery

`watch_queue.c` owns bounded record queues with adjacent coalescing, complete
record boundaries and one overflow marker. Record storage is recycled to its
high-water mark. A pipe carries one readiness byte, never event payloads.
Its read end supplies native poll/epoll and last-reader lifetime, including dup,
fork, exec and queued SCM_RIGHTS. The owner retains no permanent read alias.

`watch_calls.c` activates exact-FD seccomp selectors before returning a watch or
ordinary duplicate/received alias. `watch_activation.c` tracks installed filters
by thread group: pthreads share that metadata, fork copies it and exec retains it.
Repeated reuse of an armed number does not install another filter. A selector
only requests interception; retained kernel device/inode identity decides whether
the descriptor still belongs to a watch. Unrelated FD numbers keep native reads.
After reuse, an armed number still takes interception but executes the new native
operation. Kernel filter limits and divergent TSYNC chains return errors; neither
silently enables process-wide read interception.

The original listener declares a read gate. Selected calls replay through it
with their signal mask restored before blocking. `watch_broker.c` holds empty
blocking reads asynchronously in the existing worker, progressing on native
readiness, namespace requests or cancellation. No readiness timeout invents data.
Concurrent readers consume records at the single queue owner. `read`, `readv`
and `FIONREAD` retain record sizes, EAGAIN/EINVAL and short-result boundaries;
failed output copying does not consume the queue. Cancellation after copying
can leave delivery unconfirmed, so callers must not automatically replay reads.
Splice, tee and sendfile from a watch return EINVAL instead of consuming its marker.
Other operations on the pipe are not a general emulation of every inotify-FD ABI.

Application filters remain active on replay and adapter transport. This mechanism
does not supply a new security boundary or override kernel permission denials.

## Verified Coverage

The production runner tests real UID 2000 on RM11/API 36/Linux 6.12.23 with
Bionic, Debian glibc and Alpine musl: namespace events, rename cookies, file-data
events, dup/fcntl, fork, 64 execs without filter growth, SCM_RIGHTS receive and
batch receive, concurrent readers, EINTR/SA_RESTART, readv, short/faulted output,
descriptor reuse and unrelated native reads. Separate store tests kill a writer
before commit, retire the final reader and inject a journal gap.

Stock Debian GIO's `GInotifyFileMonitor` receives create/rename/delete from an
independent launch; the fixture rejects a polling backend. Stock D-Bus registers
its config-directory watches and completes a session-bus request without watch
warnings. This is not a complete D-Bus reload or arbitrary GUI certification.
See [fixture commands](../guest-exec-lab/README.md#production-watch-checks).

## Limits

- Native data events identify an object, not the logical dentry used to open it.
  Directory projection reports its currently linked aliases, including multiple
  hardlink names. Delayed IO around rename/unlink is not exact Linux dentry history.
  Namespace-driven ctime/parent-mtime updates remain incomplete.
- Final logical unlink emits DELETE_SELF without tracking all open references;
  native Linux may defer that notification until the last reference is gone.
- Remote read/copy access can fail for a nondumpable task. Watch reads have no
  task-affine protected-copy fallback; metadata's fallback is a separate contract.
- FD activation is not atomic publication to arbitrary concurrent CLONE_FILES
  users. Cross-thread-group shared tables and transfer of a watch instance to
  another independent supervisor are not supported ownership contracts. Independent
  watchers on a shared store are supported and tested.
- Filters accumulate for distinct FD numbers, bounded by the kernel's filter
  budget and a 65,536-number selector space. Application filters restricted to
  the original instruction address can reject gate replay. Older kernels and
  other firmware require their own device coverage.
