#ifndef MD_FUSE_TRANSPORT_H
#define MD_FUSE_TRANSPORT_H
#include <stddef.h>
#include <stdint.h>
#include <linux/fuse.h>
struct md_resource_wakeup;
struct md_fuse_connection;
struct md_fuse_request;
enum { MD_FUSE_PACKET_MAX=1024*1024, MD_FUSE_REQUEST_MAX=256,
    MD_FUSE_QUEUE_BYTES=8*1024*1024 };
/* One connection per open device description. The server handle owns abort;
 * retained mounts and requests cannot keep a disconnected server alive. */
int md_fuse_connection_new(struct md_fuse_connection **);
struct md_fuse_connection *md_fuse_connection_retain(struct md_fuse_connection *);
void md_fuse_connection_release(struct md_fuse_connection *);
void md_fuse_connection_abort(struct md_fuse_connection *,int error);
/* Admission status: zero while connected, -ENOTCONN after abort. Pending
 * request results retain the reason supplied to connection_abort(). */
int md_fuse_connection_error(struct md_fuse_connection *);
/* One superblock owns each device connection. Descriptor duplication is not a new connection. */
int md_fuse_connection_claim(struct md_fuse_connection *);
int md_fuse_connection_descriptor(struct md_fuse_connection *);
void md_fuse_connection_reader(struct md_fuse_connection *,struct md_resource_wakeup *);
long md_fuse_connection_poll(struct md_fuse_connection *);
/* The mount unregisters before freeing its context. Invocation and removal are
 * serialized by the transport lock; callbacks must not call the transport. */
void md_fuse_connection_notifications(struct md_fuse_connection *,
    int (*notify)(void *,unsigned,const void *,size_t),void *);
/* Header length/unique are assigned here. Inputs are copied before publication.
 * max_reply bounds the payload, not the output header. No request is replayed.
 * EAGAIN is admission failure: no request was published. */
int md_fuse_request_submit(struct md_fuse_connection *,const struct fuse_in_header *,
    const void *data,size_t size,size_t max_reply,int no_reply,
    struct md_resource_wakeup *,struct md_fuse_request **);
long md_fuse_request_result(struct md_fuse_request *,void *data,size_t capacity);
int md_fuse_request_ready(struct md_fuse_request *);
void md_fuse_request_cancel(struct md_fuse_request *);
/* A delivered request can still allocate daemon state after its caller exits.
 * Keep its bounded transport slot until reply/disconnect, then release that
 * state outside the connection lock. The callback owns its context. */
void md_fuse_request_abandon(struct md_fuse_request *,
    void (*complete)(void *,int,const void *,size_t),void *);
/* Terminal descriptor work must reach the daemon even after the task exits.
 * Unlike abandon, this retains queued work as well as delivered work. */
void md_fuse_request_retire(struct md_fuse_request *,
    void (*complete)(void *,int,const void *,size_t),void *);
void md_fuse_request_release(struct md_fuse_request *);
/* Reads offer one indivisible packet. Commit follows successful guest copy;
 * failed copies leave the packet available. Descriptor ownership serializes
 * reads. Replies may arrive on another thread while a read is being committed. */
long md_fuse_connection_read(struct md_fuse_connection *,void *,size_t);
void md_fuse_connection_read_commit(struct md_fuse_connection *,size_t);
long md_fuse_connection_write(struct md_fuse_connection *,const void *,size_t);
#endif
