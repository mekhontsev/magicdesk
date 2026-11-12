#define _GNU_SOURCE
#include "socket_calls.h"
#include "file_calls.h"
#include "socket_routes.h"
#include "socket_namespace.h"
#include "socket_identity.h"
#include "ipc_credentials.h"
#include "socket_ancillary.h"
#include "descriptor_calls.h"
#include "proc_paths.h"
#include "raw.h"
#include "fs_wire.h"
#include "resource_protocol.h"
#include "guest_execution.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/uio.h>

static long domain(int fd) {
    int family = 0;
    unsigned size = sizeof(family);
    long r = RAW5(getsockopt, fd, SOL_SOCKET, SO_DOMAIN, &family, &size);
    return r < 0 ? r : family;
}
long md_socket_invoke(long nr, const unsigned long *a) {
    if (md_socket_wait_call(nr,a[2],a[3]))
        return md_guest_native_call(nr,a);
    return md_raw(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
}
static long connect_native(int fd,const void *address,unsigned length) {
    const unsigned long args[6]={(unsigned long)fd,(uintptr_t)address,length};
    return md_socket_invoke(SYS_connect,args);
}
static long resource_connect(const struct md_fs *fs,int fd,const struct sockaddr_un *address,unsigned length) {
    if (!fs->endpoint[0]) return -ENOTSUP;
    int type=0; unsigned size=sizeof(type);
    long r=RAW5(getsockopt,fd,SOL_SOCKET,SO_TYPE,&type,&size);
    if (r<0) return r;
    if (type!=SOCK_STREAM) return -EPROTOTYPE;
    struct md_fs_request q={.operation=MD_FS_SHM_CHANNEL,.directory={-1,-1}};
    struct md_fs_response response;
    r=md_fs_call(fs->endpoint,5000,&q,&response);
    if (!r) r=response.result.error;
    if (r<0) return r;
    int channel=response.result.fd;
    r=connect_native(fd,address,length);
    if (!r) {
        char hello=LORIE_RESOURCE_HELLO;
        do { r=md_fs_send_fds(fd,&hello,1,&channel,1); } while (r==-EINTR);
        if (r==1) r=0;
        else { RAW2(shutdown,fd,SHUT_RDWR); if (r>=0) r=-EIO; }
    }
    RAW1(close,channel); return r;
}
long md_socket_pair(const struct md_fs *fs, const unsigned long *a, int offline) {
    if (offline && (int)a[0]!=AF_UNIX) return -EAFNOSUPPORT;
    long r=offline ? md_descriptor_pair(SYS_socketpair,a) : md_socket_invoke(SYS_socketpair,a);
    if (r<0 || (int)a[0]!=AF_UNIX || !fs->endpoint[0]) return r;
    int pair[2]={-1,-1};
    r=md_read_memory(pair,(void *)a[3],sizeof(pair));
    if (!r) r=md_socket_identity_call(fs,MD_IPC_PAIR,pair[0],pair[1],0,NULL,0);
    if (r<0) { RAW1(close,pair[0]); RAW1(close,pair[1]); }
    return r;
}
long md_socket_offline_error(long nr, const unsigned long *a) {
    /* Offline socketpairs have no address namespace or autobind. Payload and
     * SCM_RIGHTS on their established connection remain native. */
    if (nr==SYS_sendto) return a[4] && a[5] ? -EOPNOTSUPP : 0;
    if (nr==SYS_sendmmsg) return -EOPNOTSUPP;
    if (nr==SYS_sendmsg) {
        struct msghdr message;
        long error=md_read_memory(&message,(void *)a[1],sizeof(message));
        if (error) return error;
        return message.msg_name && message.msg_namelen ? -EOPNOTSUPP : 0;
    }
    return 0;
}
static long connect_socket(const struct md_fs *fs, int fd, const struct sockaddr_un *name, unsigned length) {
    long tracked = md_socket_identity_call(fs, MD_IPC_CONNECT_BEGIN, fd, -1, 0, name, length);
    if (tracked < 0) return tracked;
    long result = connect_native(fd,name,length);
    if (tracked) {
        /* Completion is never replayed. An unconfirmed identity publication
         * cannot change successful connect into a retryable connection error. */
        md_socket_identity_call(fs, MD_IPC_CONNECT_END, fd, -1, result, NULL, 0);
    }
    return result;
}
static int message_remaining(uintptr_t pointer, unsigned sent) {
    // import_iovec limits the kernel's iterator even for a larger user vector.
    if (md_page_size && sent == ((unsigned)INT_MAX & ~(md_page_size - 1))) return 0;
    struct msghdr message;
    if (md_read_memory(&message, (void *)pointer, sizeof(message)) < 0) return 1;
    // The successful send already validated the iovecs. A concurrent change
    // must not turn a completed message into an error or trigger a replay.
    if (message.msg_iovlen > 1024) return 1;
    size_t remaining = sent;
    for (size_t i = 0; i < message.msg_iovlen;) {
        struct iovec vectors[8];
        size_t n = message.msg_iovlen - i;
        if (n > 8) n = 8;
        uintptr_t address = (uintptr_t)message.msg_iov + i * sizeof(*vectors);
        if (address < (uintptr_t)message.msg_iov
                || md_read_memory(vectors, (void *)address, n * sizeof(*vectors)) < 0) return 1;
        for (size_t j = 0; j < n; ++j) {
            if (vectors[j].iov_len > remaining) return 1;
            remaining -= vectors[j].iov_len;
        }
        i += n;
    }
    return 0;
}
static long send_batch(const struct md_fs *fs, const char *exe, const unsigned long *a) {
    unsigned count = (unsigned)a[2];
    if (!count) return md_socket_invoke(SYS_sendmmsg,a);
    if (count > 1024) count = 1024; // Linux caps the vector at UIO_MAXIOV.
    for (unsigned i = 0; i < count; ++i) {
        uintptr_t offset = i * sizeof(struct mmsghdr), pointer = a[1] + offset;
        long r = -EFAULT;
        if (pointer >= a[1] && pointer <= UINTPTR_MAX - sizeof(struct mmsghdr)) {
            unsigned long args[6] = {a[0], pointer, a[3]};
            r = md_socket_call(fs, exe, SYS_sendmsg, args,0);
            if (r >= 0) {
                unsigned length = (unsigned)r;
                r = md_write_memory((void *)(pointer + offsetof(struct mmsghdr, msg_len)),
                    &length, sizeof(length));
                if (!r && i + 1 < count && message_remaining(pointer, length)) return i + 1;
            }
        }
        // Match the kernel's partial-success contract, including a failed length
        // write after sending the current message. Never replay that message.
        if (r < 0) return i ? (long)i : r;
    }
    return count;
}
long md_socket_address_call(const struct md_fs *fs, const char *exe, long nr, const unsigned long *a) {
    unsigned long accepted[6];
    if (nr==SYS_accept) {
        /* Android permits accept4, but may reject the equivalent legacy call.
         * Admission has already evaluated the application's original syscall. */
        accepted[0]=a[0]; accepted[1]=a[1]; accepted[2]=a[2];
        accepted[3]=accepted[4]=accepted[5]=0;
        a=accepted; nr=SYS_accept4;
    }
    if (md_socket_payload_native(nr,a[4])) return md_socket_invoke(nr,a);
    /* recvmsg's ancillary adapter owns the complete receive/publication order,
     * including names. Its native fallback must not add a getsockopt syscall. */
    if (nr == SYS_recvmsg) return md_socket_invoke(nr,a);
    if (nr == SYS_getsockopt) return md_socket_identity_option(fs, a);
    if (nr == SYS_socketpair) {
        return md_socket_pair(fs,a,0);
    }
    if (nr == SYS_listen) {
        long family = domain((int)a[0]);
        if (family != AF_UNIX || !fs->endpoint[0]) return md_socket_invoke(nr, a);
        long r = md_socket_identity_call(fs, MD_IPC_LISTEN_BEGIN, (int)a[0], -1, 0, NULL, 0);
        if (r < 0) return r;
        r = md_socket_invoke(nr, a);
        long published = md_socket_identity_call(fs, MD_IPC_LISTEN_END, (int)a[0], -1, r, NULL, 0);
        return r < 0 ? r : published;
    }
    if (nr == SYS_getsockname || nr == SYS_getpeername || nr == SYS_accept || nr == SYS_accept4
            || nr == SYS_recvfrom) {
        if ((nr == SYS_accept || nr == SYS_accept4) && !a[1]) return md_socket_invoke(nr, a);
        long family = domain((int)a[0]);
        if (family < 0) return family;
        return fs->endpoint[0] && family == AF_UNIX ? md_namespace_socket_output(fs, nr, a) : md_socket_invoke(nr, a);
    }
    if (nr == SYS_sendmmsg) {
        return fs->endpoint[0] ? send_batch(fs, exe, a) : md_socket_invoke(nr, a);
    }
    unsigned address_index = nr == SYS_sendto ? 4 : 1;
    unsigned length_index = address_index + 1;
    const void *pointer = (void *)a[address_index];
    unsigned length = (unsigned)a[length_index];
    struct msghdr message;
    if (nr == SYS_sendmsg) {
        long r = md_read_memory(&message, (void *)a[1], sizeof(message));
        if (r < 0) return r;
        pointer = message.msg_name;
        length = message.msg_namelen;
    }
    if (!pointer || length < sizeof(sa_family_t) || length > sizeof(struct sockaddr_storage))
        return md_socket_invoke(nr,a);
    sa_family_t family;
    long r = md_read_memory(&family, pointer, sizeof(family));
    if (r < 0) return r;
    if (family != AF_UNIX) return md_socket_invoke(nr,a);
    if (length > sizeof(struct sockaddr_un)) return -EINVAL;
    struct sockaddr_un address;
    r = md_read_memory(&address, pointer, length);
    if (r < 0) return r;
    int route=nr==SYS_connect ? md_socket_route_apply(fs->connections,&address,&length) : 0;
    if (route) {
        r = domain((int)a[0]);
        if (r < 0) return r;
        if (r != AF_UNIX) return md_socket_invoke(nr, a);
        return route==2 ? resource_connect(fs,(int)a[0],&address,length) : connect_native(a[0],&address,length);
    }
    /* Abstract addresses are binary names, including every supplied zero byte.
     * Family-only bind is the kernel's autobind request. Neither is a pathname. */
    if (length == offsetof(struct sockaddr_un, sun_path) || !address.sun_path[0])
        return nr == SYS_connect ? connect_socket(fs, (int)a[0], &address, length) : md_socket_invoke(nr,a);
    r = domain((int)a[0]);
    if (r < 0) return r;
    if (r != AF_UNIX) return md_socket_invoke(nr,a);
    char path[sizeof(address.sun_path) + 1];
    size_t size = length - offsetof(struct sockaddr_un, sun_path);
    memcpy(path, address.sun_path, size);
    path[size] = 0;
    if (nr == SYS_bind)
        return fs->endpoint[0] ? md_namespace_socket_bind(fs, (int)a[0], path) : -ENOTSUP;
    long fd = -1;
    if (fs->endpoint[0] && (!md_fs_host_path(fs, path) || md_proc_path(path).kind)) {
        r = md_namespace_socket_address(fs, path, &address, &length);
        if (r < 0) return r;
    } else {
    unsigned long open_args[6] = {(unsigned long)AT_FDCWD, (unsigned long)path,
                                  O_PATH | O_CLOEXEC, 0, 0, 0};
    fd = md_file_call(fs, exe, SYS_openat, open_args);
    if (fd < 0) return fd;
    /* Keep the inode alive for the entire kernel call. Path permissions were
     * checked by the file adapter; the kernel still checks socket access/type. */
    md_copy(address.sun_path, sizeof(address.sun_path), "/proc/thread-self/fd/");
    md_decimal(address.sun_path + md_length(address.sun_path), (unsigned)fd);
    length = (unsigned)(offsetof(struct sockaddr_un, sun_path) + md_length(address.sun_path) + 1);
    }
    unsigned long args[6];
    memcpy(args, a, sizeof(args));
    if (nr == SYS_sendmsg) {
        message.msg_name = &address;
        message.msg_namelen = length;
        args[1] = (unsigned long)&message;
    } else {
        args[address_index] = (unsigned long)&address;
        args[length_index] = length;
    }
    r = nr == SYS_connect && fd < 0 ? connect_socket(fs, (int)a[0], &address, length) : md_socket_invoke(nr,args);
    if (fd >= 0) RAW1(close, fd);
    return r;
}
long md_socket_call(const struct md_fs *fs, const char *exe, long nr, const unsigned long *a,unsigned long prepared) {
    long r = nr == SYS_sendmsg ? md_socket_ancillary_send(fs, exe, a)
        : nr == SYS_sendto ? md_socket_ancillary_sendto(fs,exe,a)
        : nr == SYS_recvmsg ? md_socket_ancillary_receive(fs, exe, a,prepared)
        : md_socket_address_call(fs, exe, nr, a);
    if (!r && nr==SYS_bind && fs->endpoint[0] && domain((int)a[0])==AF_UNIX) {
        long error=md_socket_identity_call(fs,MD_IPC_BIND,(int)a[0],-1,0,NULL,0);
        if (error<0) r=error;
    }
    if (r >= 0 && (nr == SYS_accept || nr == SYS_accept4) && fs->endpoint[0]) {
        long error = md_socket_identity_call(fs, MD_IPC_ACCEPT, (int)r, -1, 0, NULL, 0);
        if (error < 0 && error != -EAFNOSUPPORT && error != -EPROTOTYPE) { RAW1(close, r); return error; }
    }
    return r;
}
