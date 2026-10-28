#define _GNU_SOURCE
#include "network_io.h"
#include "network_transport.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

struct packet_info_ref { void *data; size_t bytes; unsigned family; };
static int packet_info(void *control,size_t control_size,int flags,int sending,struct packet_info_ref out[2],unsigned *count,
        struct md_network_packet_info *info) {
    *count=0;
    size_t offset=0;
    while (control_size-offset>=sizeof(struct cmsghdr)) {
        struct cmsghdr *c=(void *)((char *)control+offset);
        if (c->cmsg_len<CMSG_LEN(0) || c->cmsg_len>control_size-offset) return -EINVAL;
        unsigned family=c->cmsg_level==IPPROTO_IP && c->cmsg_type==IP_PKTINFO ? AF_INET
            : c->cmsg_level==IPPROTO_IPV6 && c->cmsg_type==IPV6_PKTINFO ? AF_INET6 : 0;
        if (family) {
            size_t expected=family==AF_INET ? sizeof(struct in_pktinfo) : sizeof(struct in6_pktinfo);
            size_t size=c->cmsg_len-CMSG_LEN(0);
            if (size!=expected && (sending || !(flags&MSG_CTRUNC) || size>expected)) return -EINVAL;
            if (*count==2 || (sending && *count)) return -EINVAL;
            for (unsigned i=0;i<*count;++i) if (out[i].family==family) return -EINVAL;
            out[(*count)++]=(struct packet_info_ref){CMSG_DATA(c),size,family};
        } else if ((c->cmsg_level==IPPROTO_IP && c->cmsg_type==IP_TTL)
                || (c->cmsg_level==IPPROTO_IPV6 && c->cmsg_type==IPV6_HOPLIMIT)) {
            size_t size=c->cmsg_len-CMSG_LEN(0);
            if (sending) {
                int value;
                if (size!=sizeof(value)) return -EINVAL;
                memcpy(&value,CMSG_DATA(c),sizeof(value));
                if (value<-1 || value>255 || (c->cmsg_level==IPPROTO_IP && value<=0)) return -EINVAL;
                if (info) { info->hops=value; info->hops_set=value>=0; }
            } else if (info && info->hops_set) {
                if (size>sizeof(info->hops)) return -EINVAL;
                memcpy(CMSG_DATA(c),&info->hops,size);
            }
        } else if (sending && !(c->cmsg_level==IPPROTO_IP && (c->cmsg_type==IP_TTL || c->cmsg_type==IP_TOS))
                && !(c->cmsg_level==IPPROTO_IPV6 && (c->cmsg_type==IPV6_HOPLIMIT || c->cmsg_type==IPV6_TCLASS))) return -EOPNOTSUPP;
        size_t step=CMSG_ALIGN(c->cmsg_len);
        if (step>control_size-offset) break;
        offset+=step;
    }
    return 0;
}
static struct md_network_packet_info read_info(const struct packet_info_ref *ref) {
    struct md_network_packet_info out={.source=md_ip_any(ref->family),.destination=md_ip_any(ref->family)};
    if (ref->family==AF_INET) {
        struct in_pktinfo value={0}; memcpy(&value,ref->data,ref->bytes);
        out.interface=value.ipi_ifindex; out.source=md_ip_v4(value.ipi_spec_dst.s_addr);
        out.destination=md_ip_v4(value.ipi_addr.s_addr);
    } else {
        struct in6_pktinfo value={0}; memcpy(&value,ref->data,ref->bytes);
        out.interface=value.ipi6_ifindex; out.source=md_ip_load(AF_INET6,&value.ipi6_addr);
    }
    return out;
}
static void write_info(const struct packet_info_ref *ref,const struct md_network_packet_info *info) {
    if (ref->family==AF_INET) {
        struct in_pktinfo value={.ipi_ifindex=info->interface,.ipi_spec_dst.s_addr=info->source.v4,
            .ipi_addr.s_addr=info->destination.v4};
        memcpy(ref->data,&value,ref->bytes);
    } else {
        struct in6_pktinfo value={.ipi6_ifindex=info->interface,.ipi6_addr=md_ip_in6(info->destination)};
        memcpy(ref->data,&value,ref->bytes);
    }
}
static int receive_source(struct md_network_io *n,struct md_ipc_io_message *message,void *control) {
    if (!message->address_size) return 0;
    struct packet_info_ref refs[2]; unsigned count=0;
    struct md_network_packet_info translated;
    int error=md_network_endpoint_source_info(n->endpoint,&message->address,&message->address_size,&translated);
    if (!error) error=packet_info(control,message->control_size,message->flags,0,refs,&count,&translated);
    /* Truncated ancillary fields must not expose backing transport addresses. */
    if (!error) for (unsigned i=0;i<count;++i) write_info(&refs[i],&translated);
    return error;
}
int md_network_io_prepare_batch(struct md_ipc_io *io,struct md_ipc_io_message *message,int fd,int flags,unsigned count) {
    if (count>MD_IPC_IO_BATCH) return -EINVAL;
    /* Peer metadata is also needed for pktinfo when the caller omits msg_name. */
    for (unsigned i=0;i<count;++i) message->layouts[i].address_size=sizeof(message->address);
    return md_ipc_io_prepare_batch(io,message,fd,flags,count);
}
int md_network_io_select(struct md_network_io *n,struct md_ipc_io *io,struct md_ipc_io_message *message,unsigned index) {
    int result=md_ipc_io_select(io,message,index);
    if (result<0) return result;
    int error=receive_source(n,message,io->control);
    return error ? error : result;
}
int md_network_io_sending(const struct md_network_io *n) {
    return n->number==SYS_sendto || n->number==SYS_sendmsg;
}
int md_network_io_flags(const struct md_network_io *n) {
    return (int)n->args[n->number==SYS_sendmsg || n->number==SYS_recvmsg ? 2 : 3];
}
void md_network_io_release(struct md_network_io *n,struct md_ipc_io *io) {
    if (io->job.started) md_ipc_io_interrupt(io);
    if ((n->number==SYS_accept || n->number==SYS_accept4) && io->native_ready && io->native_result>=0) {
        close((int)io->native_result); io->native_ready=0;
    }
    md_fd_object_release(n->endpoint);
    *n=(struct md_network_io){0};
}
int md_network_io_progress(struct md_network_io *n,struct md_ipc_io *io,struct md_received_fds **fds,
        struct md_ipc_io_message *message,long *result) {
    int fd=(*fds)->fds[0];
    long nr=n->number;
    if (nr==SYS_setsockopt || nr==SYS_getsockopt) {
        if (md_network_endpoint_option(n->args[1],n->args[2]))
            *result=md_network_endpoint_sockopt(n->endpoint,fd,n->identity,nr==SYS_setsockopt,n->args[1],n->args[2],
                message->control,&message->address_size);
        else if (n->args[1]!=SOL_SOCKET || n->args[2]!=SO_BINDTODEVICE) *result=-ENOPROTOOPT;
        else if (nr==SYS_setsockopt)
            *result=md_network_endpoint_device_set(n->endpoint,n->identity,(const char *)&message->address);
        else *result=md_network_endpoint_device_get(n->endpoint,(char *)&message->address,&message->address_size);
        return 0;
    }
    if (nr==SYS_bind) {
        *result=md_network_endpoint_bind(n->endpoint,fd,n->identity,(void *)&message->address,message->address_size);
        return 0;
    }
    if (nr==SYS_getsockname || nr==SYS_getpeername) {
        *result=md_network_endpoint_name(n->endpoint,fd,nr==SYS_getpeername,&message->address,&message->address_size);
        return 0;
    }
    if (nr==SYS_listen) {
        struct md_inet_address any={.ip=md_ip_any(md_network_endpoint_family(n->endpoint))};
        struct sockaddr_storage wire; socklen_t size;
        *result=md_inet_encode(&any,0,&wire,&size);
        if (!*result) *result=md_network_endpoint_autobind(n->endpoint,fd,n->identity,(void *)&wire,size,0);
        if (!*result) *result=md_network_endpoint_listen(n->endpoint,fd,(int)n->args[1]);
        return 0;
    }
    if (nr==SYS_recvmmsg) return md_ipc_io_receive(io,fds,message,md_network_io_flags(n),result);
    if (nr==SYS_sendto || nr==SYS_recvfrom || nr==SYS_sendmsg || nr==SYS_recvmsg) {
        int sending=md_network_io_sending(n),flags=md_network_io_flags(n);
        if (!n->started) {
            struct packet_info_ref refs[2]; unsigned count=0;
            unsigned family=md_network_endpoint_family(n->endpoint);
            struct md_network_packet_info value={.source=md_ip_any(family),.destination=md_ip_any(family)},*info=NULL;
            int error=message->control_size>sizeof(message->control) ? -EMSGSIZE
                : sending ? packet_info(message->control,message->control_size,message->flags,1,refs,&count,&value) : 0;
            if (!error && count) {
                struct md_network_packet_info address=read_info(refs);
                value.interface=address.interface; value.source=address.source; value.destination=address.destination;
            }
            if (!error && (count || value.hops_set)) info=&value;
            if (!error && sending && info && !message->address_size)
                error=md_network_endpoint_name(n->endpoint,fd,1,&message->address,&message->address_size);
            if (!error && sending && message->address_size) {
                error=md_network_endpoint_autobind(n->endpoint,fd,n->identity,
                    (void *)&message->address,message->address_size,info ? info->interface : 0);
                if (!error) error=md_network_endpoint_destination_info(n->endpoint,(void *)&message->address,message->address_size,
                    info,&n->destination,&n->length);
                if (!error) { message->address=n->destination; message->address_size=n->length; }
                if (!error && count) write_info(refs,info);
            }
            /* Receive the transport peer even when the caller omits msg_name:
             * ancillary destination/interface data needs the same translation. */
            if (!sending) message->address_size=sizeof(message->address);
            if (!error && !io->initialized) error=md_ipc_io_prepare(io,message,fd,flags,sending);
            if (error) { *result=error; return 0; }
            n->started=1;
        }
        int waiting=sending ? md_ipc_io_send(io,*fds,message,flags,result)
            : md_ipc_io_receive(io,fds,message,flags,result);
        if (!waiting && !sending && *result>=0 && message->address_size) {
            int error=receive_source(n,message,message->control);
            if (error) *result=error;
        }
        return waiting;
    }
    if (nr!=SYS_connect && nr!=SYS_accept && nr!=SYS_accept4) { *result=-EOPNOTSUPP; return 0; }
    if (!n->started) {
        int error=0;
        long args[5]={fd,0,0,0,0};
        if (nr==SYS_connect) {
            if (message->address_size>=sizeof(sa_family_t) && message->address.ss_family==AF_UNSPEC) {
                n->destination=message->address; n->length=message->address_size;
            } else {
                error=md_network_endpoint_autobind(n->endpoint,fd,n->identity,
                    (void *)&message->address,message->address_size,0);
                if (!error) error=md_network_endpoint_destination(n->endpoint,(void *)&message->address,message->address_size,
                    &n->destination,&n->length);
            }
            args[1]=(long)&n->destination; args[2]=n->length;
        } else {
            args[3]=(nr==SYS_accept4 ? n->args[3] : 0)|SOCK_CLOEXEC;
            nr=SYS_accept4;
        }
        if (error) {
            if (nr==SYS_connect) md_network_endpoint_connect_reported(n->endpoint,error);
            *result=error; return 0;
        }
        io->initialized=1; io->deadline=INT64_MAX;
        if (nr==SYS_connect) {
            int flags=fcntl(fd,F_GETFL);
            if (flags<0) { *result=-errno; return 0; }
            io->nonblocking=!!(flags&O_NONBLOCK);
        }
        error=md_ipc_io_job_start(&io->job,nr,args);
        if (error) { *result=error; return 0; }
        n->started=1; return 1;
    }
    if (!io->native_ready) {
        io->native_result=md_ipc_io_job_finish(&io->job,0); io->native_ready=1;
    }
    *result=io->native_result;
    if (n->number==SYS_connect) {
        if (n->started==2 && *result>=0) *result=0;
        if (!*result || *result==-EINPROGRESS || *result==-EALREADY) {
            md_network_endpoint_connected(n->endpoint,fd,(void *)&message->address,message->address_size);
            int wait=-1;
            int upstream=md_network_endpoint_connect_result(n->endpoint,&wait);
            if (upstream==-EINPROGRESS && wait>=0 && !io->nonblocking) {
                /* EVENT_WAIT: actual destination handshake, not local relay
                 * acceptance. Cancellation retains the common IO job owner. */
                n->connect_wait=(struct pollfd){.fd=wait,.events=POLLIN};
                long args[5]={(long)&n->connect_wait,1,0,0,8};
                int error=md_ipc_io_job_start(&io->job,SYS_ppoll,args);
                if (error) { *result=error; io->native_ready=0; return 0; }
                n->started=2; io->native_ready=0; return 1;
            }
            if (upstream) *result=upstream;
        } else md_network_endpoint_connect_complete(n->endpoint,(int)*result);
        md_network_endpoint_connect_reported(n->endpoint,(int)*result);
        io->native_ready=0;
    }
    return 0;
}
