# Named Pipes

Guest FIFO names, ownership, modes, ACLs, hardlinks and rename/unlink use the
ordinary inode namespace. OCI layers and prepared-tree import retain FIFO
metadata without persisting stream contents. Each image instance has its own
streams. Native attached directories retain their real kernel permissions.

`inode_fifo` owns store-scoped kernel pipe pins. The backing object is regular
metadata, not a native `mknod` request: Android shell policy can prohibit native
FIFO creation. An O_PATH pin retains a pipe inode without retaining a reader or
writer. Data read/write, pipe capacity, PIPE_BUF atomicity, EOF and SIGPIPE remain
kernel operations. No daemon, payload proxy, root or SELinux modification is used.

Independent namespace workers sharing the store acquire the same pipe through
a live owner's proc descriptor. Persisted routes include native device/inode
identity and a random owner lease protected by a kernel lock; PID existence alone
does not authorize reuse. Each participating worker retains its own pin until
its supervised launch ends. Dead leases are ignored. Clean closure removes its
routes. No live descriptor number is meaningful across a runtime restart.

Opening returns an authorized pin and a separate rendezvous descriptor through
the typed filesystem result. `file_open` completes it in the calling task after
the namespace transaction and RPC end. Blocking reader/writer opens wait on
shared futex generation changes; signals can interrupt this wait. A nonblocking
writer without readers receives ENXIO; O_RDWR does not wait for another endpoint.
Ordinary files do not allocate this state or acquire rendezvous locks. Internal
bookkeeping lives outside watched backing objects and is not published as guest IO.

## Limits

The anonymous-pipe backing does not reproduce every named-FIFO readiness rule.
A newly opened nonblocking reader can receive POLLHUP before its first writer
connects. Native named FIFOs suppress that initial hangup; poll/epoll adaptation
of this per-open-description state is not implemented. This is a compatibility
limit, not a peer-connect notification or proof of a broken stream.

Directory/name inotify events use namespace transactions. FIFO data/open/close
events are not synthesized from the anonymous pipe. Do not infer complete FIFO
watch support from regular-file watch checks. Endpoint sharing outside supervised
guest launches is not a supported pin-lifetime contract.

## Checks

`test_service_runtime.c` exercises permissions, nonblocking open errors, data
readiness, EOF, blocking peer rendezvous, O_RDWR, hardlinks, rename, unlink/reopen
and name reuse under actual shell UID 2000. `test_fifo_runtime.py` exchanges data
between independent launches and repeats after both owners exit.
`test_import.c` checks prepared-tree FIFO import on hosts that permit source FIFO
creation; shell denial is reported explicitly. OCI checks cover FIFO metadata,
hardlinks and independent instance backing. Stock VS Code C/C++ debugging also
exercises FIFO transport through its integrated terminal.
