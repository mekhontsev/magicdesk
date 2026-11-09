#define _GNU_SOURCE
#include "ipc_capsule.h"
#include "ipc_scope.h"
#include "fd_bundle.h"
#include "fs_origin_transfer.h"
#include "descriptor_inode.h"
#include "fifo_description.h"
#include "descriptor_parcel.h"
#include "fs_wire.h"
#include <errno.h>
#include <fcntl.h>
#include <mbedtls/constant_time.h>
#include <mbedtls/md.h>
#include <mbedtls/platform_util.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/random.h>
#include <sys/stat.h>
#include <unistd.h>

enum { MAGIC=0x4d444643, VERSION=2, KEY_BYTES=32, CUSTODY_FDS=4,
    PORTABLE=0, LOCAL=1, PORTABLE_TYPED=2 };
struct capsule {
    uint32_t magic, version, metadata, kind;
    struct md_fd_message_route route;
    unsigned char tag[KEY_BYTES];
};
struct md_ipc_capsules {
    struct md_ipc_binding scope;
    struct md_fs_origin_imports *imports;
    unsigned char key[KEY_BYTES];
};
static int epoch_key(const struct md_ipc_scope *scope, unsigned char *key) {
    int dir=openat(scope->root,"ipc",O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
    if (dir<0) return -errno;
    int gate=openat(dir,"gate",O_RDWR|O_CLOEXEC|O_NOFOLLOW), fd=-1, created=0;
    int error=gate<0 ? -errno : 0;
    /* EVENT_WAIT: the existing epoch metadata gate releases; interruption fails
     * admission. No IPC message or application table turn is held here. */
    if (!error && flock(gate,LOCK_EX)) error=-errno;
    if (!error) {
        fd=openat(dir,"auth",O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK);
        if (fd<0 && errno!=ENOENT) error=-errno;
        else if (fd<0) {
            /* The gate has one writer. A killed creator leaves at most this
             * unpublished file; the next creator replaces it before admission. */
            if (unlinkat(dir,"auth.pending",0) && errno!=ENOENT) error=-errno;
            if (!error) {
                fd=openat(dir,"auth.pending",O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC|O_NOFOLLOW,0600);
                if (fd<0) error=-errno; else created=1;
            }
        }
    }
    if (!error && created) {
        ssize_t n=getrandom(key,KEY_BYTES,0);
        if (n!=KEY_BYTES) error=n<0 ? -errno : -EIO;
        if (!error && (n=write(fd,key,KEY_BYTES))!=KEY_BYTES) error=n<0 ? -errno : -EIO;
        if (!error && renameat(dir,"auth.pending",dir,"auth")) error=-errno;
    } else if (!error) {
        struct stat st;
        if (fstat(fd,&st)) error=-errno;
        else if (!S_ISREG(st.st_mode) || st.st_size!=KEY_BYTES || st.st_uid!=getuid()
                || (st.st_mode&0077)) error=-EPROTO;
        if (!error) {
            ssize_t n=read(fd,key,KEY_BYTES);
            if (n!=KEY_BYTES) error=n<0 ? -errno : -EIO;
        }
    }
    if (error && created) unlinkat(dir,"auth.pending",0);
    if (fd>=0) close(fd);
    if (gate>=0) close(gate);
    close(dir); return error;
}
static int selected(const struct md_ipc_binding *b) {
    if (!b || !b->scope || b->connection.side>1) return -EINVAL;
    return b->transport==MD_IPC_TRANSPORT_ORIGINS_V1 ? 0 : -ENOTSUP;
}
static int binding(struct md_ipc_capsules *s, const struct md_ipc_binding *b) {
    if (!s) return -EINVAL;
    int error=selected(b);
    if (!error && !md_ipc_binding_same_scope(&s->scope,b)) error=-EXDEV;
    return error;
}
int md_ipc_capsules_new(const struct md_ipc_binding *b, struct md_ipc_capsules **out) {
    if (!out || *out) return -EINVAL;
    int error=selected(b);
    if (error) return error;
    struct md_ipc_capsules *s=calloc(1,sizeof(*s));
    if (!s) return -ENOMEM;
    error=md_ipc_binding_copy(&s->scope,b);
    if (!error) error=epoch_key(b->scope,s->key);
    if (error) md_ipc_capsules_free(s); else *out=s;
    return error;
}
void md_ipc_capsules_free(struct md_ipc_capsules *s) {
    if (!s) return;
    md_fs_origin_imports_free(s->imports);
    md_ipc_binding_release(&s->scope);
    mbedtls_platform_zeroize(s->key,sizeof(s->key)); free(s);
}
static int authenticate(struct md_ipc_capsules *s, const struct capsule *p, unsigned char *tag) {
    int r=mbedtls_md_hmac(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256),s->key,sizeof(s->key),
        (const unsigned char *)p,offsetof(struct capsule,tag),tag);
    return r ? (r==MBEDTLS_ERR_MD_ALLOC_FAILED ? -ENOMEM : -EIO) : 0;
}
int md_ipc_capsules_publish(struct md_ipc_capsules *s, const struct md_ipc_binding *b,
        const char *launch, struct md_fd_message *message, const int *fds, unsigned count, int *out) {
    if (!message || !fds || !count || count!=md_fd_message_count(message) || !out || *out>=0) return -EINVAL;
    int error=binding(s,b);
    int local=0, anonymous=0, published=0, lifetime[2]={-1,-1};
    struct md_fd_object *const *origins=md_fd_message_origins(message);
    for (unsigned i=0;!error && i<count;++i) {
        if (fds[i]<0) error=-EBADF;
        else if (origins[i]) {
            int fifo=md_fifo_description_is(origins[i]);
            if (fifo) anonymous=1;
            struct md_fs_anchor *anchor=md_fs_anchor_from_object(origins[i]);
            /* A typed provider's placement is not its native carrier inode.
             * Keep its exact authority in local custody, never flatten it into
             * a portable path. FIFO descriptions have their own wire format. */
            if (anchor && origins[i]!=md_fs_anchor_object(anchor) && !fifo
                    && !origins[i]->type->portable_origin) local=1;
            else if (anchor) {
                error=md_fs_anchor_check_fd(anchor,fds[i]);
                if (!md_fs_anchor_portable(anchor)) local=1;
            }
            else if (md_descriptor_inode_is(origins[i])) anonymous=1;
            else if (!md_fd_message_exports_native(message,i)) local=1;
        }
    }
    int pair[2]={-1,-1}, rights[CUSTODY_FDS]={-1,-1,-1,-1};
    struct md_fs_origin_parcel parcel={0};
    struct capsule p={.magic=MAGIC,.version=VERSION};
    if (!error && socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_NONBLOCK|SOCK_CLOEXEC,0,pair)) error=-errno;
    if (!error) error=md_fd_message_bind(message,b,launch);
    if (!error && local) {
        if (!b->scope->local_messages) error=md_fd_messages_new(&b->scope->local_messages);
        if (!error && pipe2(lifetime,O_CLOEXEC|O_NONBLOCK)) error=-errno;
        struct md_ipc_connection destination=b->connection; destination.side^=1;
        if (!error) error=md_fd_messages_publish(b->scope->local_messages,message,&destination,pair[1],lifetime[0]);
        if (!error) { lifetime[0]=-1; published=1; p.kind=LOCAL; }
    } else if (!error) error=md_fd_message_issue(message,pair[1]);
    if (!error) error=md_fd_message_route(message,&p.route);
    if (!error && !local) {
        p.kind=anonymous ? PORTABLE_TYPED : PORTABLE;
        error=anonymous ? md_descriptor_parcel_export(message,fds,&parcel) : md_fs_origin_export(message,&parcel);
    }
    if (!error) error=md_fd_bundle_pack(fds,count,&rights[0]);
    if (!error && !local) error=md_fd_bundle_pack(parcel.fd,parcel.count,&rights[1]);
    if (!error && local) { rights[1]=lifetime[1]; lifetime[1]=-1; }
    if (!error) {
        p.metadata=parcel.count;
        error=authenticate(s,&p,p.tag);
    }
    if (!error) {
        /* Queued duplicates retain both the key's epoch and maintenance gate,
         * even for wholly unknown origins and after every supervisor exits. */
        rights[2]=b->scope->lease; rights[3]=b->scope->root;
        long n=md_fs_send_fds(pair[0],&p,sizeof(p),rights,CUSTODY_FDS);
        if (n!=sizeof(p)) error=n<0 ? (int)n : -EPROTO;
    }
    for (unsigned i=0;i<2;++i) if (rights[i]>=0) close(rights[i]);
    for (unsigned i=0;i<2;++i) if (lifetime[i]>=0) close(lifetime[i]);
    md_fs_origin_parcel_close(&parcel);
    if (pair[0]>=0) close(pair[0]);
    if (error) {
        if (pair[1]>=0) close(pair[1]);
        if (published) md_fd_messages_withdraw(b->scope->local_messages,message);
    }
    else *out=pair[1];
    return error;
}
static int verify(struct md_ipc_capsules *s, const struct md_ipc_binding *b,
        int carrier, const struct capsule *p) {
    if (p->magic!=MAGIC || p->version!=VERSION || p->kind>PORTABLE_TYPED
            || (p->kind!=LOCAL ? !p->metadata : p->metadata!=0)
            || p->metadata>MD_FS_ORIGIN_MAX_RIGHTS || p->route.magic!=MD_FD_ROUTE_MAGIC
            || p->route.version!=MD_FD_ROUTE_VERSION || !p->route.count
            || p->route.count>MD_FD_RECEIPT_MAX) return -EPROTO;
    uint64_t cookie=0; socklen_t length=sizeof(cookie);
    if (getsockopt(carrier,SOL_SOCKET,SO_COOKIE,&cookie,&length)) return -errno;
    if (length!=sizeof(cookie) || !cookie || cookie!=p->route.cookie
            || p->route.side!=b->connection.side
            || memcmp(p->route.link,b->connection.link,sizeof(p->route.link))) return -EXDEV;
    unsigned char tag[KEY_BYTES];
    int error=authenticate(s,p,tag);
    if (!error && mbedtls_ct_memcmp(tag,p->tag,sizeof(tag))) error=-EACCES;
    mbedtls_platform_zeroize(tag,sizeof(tag)); return error;
}
int md_ipc_capsules_receive(struct md_ipc_capsules *s, const struct md_ipc_binding *b,
        int carrier, struct md_received_fds **out) {
    if (!out || *out) return -EINVAL;
    int error=binding(s,b);
    if (error) return error;
    struct capsule p={0}, observed={0};
    struct iovec io={&p,sizeof(p)};
    struct msghdr msg={.msg_iov=&io,.msg_iovlen=1};
    /* No FD installation before authenticating the outer kernel identity. */
    ssize_t n=recvmsg(carrier,&msg,MSG_PEEK|MSG_DONTWAIT);
    /* Named datagram endpoints also accept independent native senders. Only
     * a recognized capsule enters authentication; arbitrary rights stay data. */
    if (b->receive_only && ((n<0 && (errno==ENOTSOCK || errno==EAGAIN || errno==EINVAL))
            || (n>=0 && (n<(ssize_t)sizeof(p.magic) || p.magic!=MAGIC)))) return -ENODATA;
    if (n<0) return -errno;
    if (n!=sizeof(p) || (msg.msg_flags&MSG_TRUNC)) return -EPROTO;
    error=verify(s,b,carrier,&p);
    if (error) return error;
    int rights[CUSTODY_FDS]; unsigned installed=0;
    n=md_fs_peek_fds(carrier,&observed,sizeof(observed),rights,CUSTODY_FDS,&installed);
    if (n<0) return (int)n;
    if (n!=sizeof(p) || installed!=CUSTODY_FDS || memcmp(&observed,&p,sizeof(p))) error=-ESTALE;
    struct md_received_fds *received=NULL;
    struct md_fs_origin_parcel parcel={0};
    struct md_fs_origin_vector *vector=NULL;
    struct md_fd_message *local=NULL;
    if (!error && p.kind==LOCAL) {
        error=md_fd_messages_claim(b->scope->local_messages,b,carrier,&p.route,&local);
        if (error==-ENODATA) error=-EXDEV;
    }
    if (!error && p.kind!=LOCAL) {
        parcel.fd=malloc(p.metadata*sizeof(int));
        if (!parcel.fd) error=-ENOMEM;
    }
    if (!error && p.kind!=LOCAL) {
        error=md_fd_bundle_peek(rights[1],p.metadata,parcel.fd);
        if (!error) parcel.count=p.metadata;
    }
    if (!error && p.kind!=LOCAL && !s->imports) error=md_fs_origin_imports_new(b->scope->root,&s->imports);
    if (!error && p.kind==PORTABLE) error=md_fs_origin_import(s->imports,&p.route,&parcel,&vector);
    if (!error) error=md_received_fds_reserve(&received,p.route.count);
    if (!error) {
        error=md_fd_bundle_peek(rights[0],p.route.count,received->fds);
        if (!error) received->count=p.route.count;
    }
    if (!error && p.kind==PORTABLE_TYPED) error=md_descriptor_parcel_import(s->imports,&p.route,&parcel,received);
    if (!error && p.kind!=PORTABLE_TYPED
            && (local ? md_fd_message_count(local) : md_fs_origin_vector_count(vector))!=p.route.count) error=-EPROTO;
    struct md_fs_anchor *const *entries=md_fs_origin_vector_entries(vector);
    struct md_fd_object *objects[MD_FD_RECEIPT_MAX];
    for (unsigned i=0;!error && p.kind!=PORTABLE_TYPED && i<p.route.count;++i) {
        objects[i]=local ? md_fd_message_origins(local)[i] : md_fs_anchor_object(entries[i]);
        struct md_fs_anchor *anchor=md_fs_anchor_from_object(objects[i]);
        if (anchor && (objects[i]==md_fs_anchor_object(anchor) || md_fifo_description_is(objects[i])))
            error=md_fs_anchor_check_fd(anchor,received->fds[i]);
    }
    if (!error && p.kind!=PORTABLE_TYPED) {
        error=md_received_fds_annotate(received,objects,p.route.count);
    }
    md_fd_message_release(local);
    md_fs_origin_vector_free(vector);
    md_fs_origin_imports_collect(s->imports);
    md_fs_origin_parcel_close(&parcel);
    for (unsigned i=0;i<installed;++i) close(rights[i]);
    if (error) md_received_fds_release(received); else *out=received;
    return error;
}
