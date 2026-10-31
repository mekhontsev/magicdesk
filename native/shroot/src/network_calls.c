#define _GNU_SOURCE
#include "network_calls.h"
#include "network_endpoint.h"
#include "network_packet.h"
#include "network_ioctl.h"
#include "adapter_buffer.h"
#include "fs_buffer.h"
#include "fd_filter.h"
#include "interception.h"
#include "socket_ancillary.h"
#include "socket_calls.h"
#include "socket_payload.h"
#include "task_receive.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/netlink.h>
#include <linux/filter.h>
#include <linux/sockios.h>
#include <net/if.h>
#include <stddef.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <sys/uio.h>

_Static_assert(sizeof(((struct md_network_send *)0)->value)==sizeof(struct sockaddr_storage),"socket address transport");

long md_network_open(const unsigned long *a) {
    long memory=md_adapter_buffer_take(); if (memory<0) return memory;
    struct md_fs_buffer_frame *frame=(void *)memory;
    long result=md_guest_buffer_control(MD_BUFFER_NETWORK_OPEN,0,0);
    if (!result) result=md_adapter_receive_fd(frame,a[1]&SOCK_CLOEXEC,MD_FS_BUFFER_RIGHTS_BEGIN);
    unsigned selectors=result>=0 ? frame->reply.result.selectors : 0;
    long released=md_adapter_buffer_release();
    if (result>=0) {
        long armed=released ? released : md_fd_filter_arm_mask(selectors,(int)result);
        if (armed<0) { RAW1(close,result); return armed; }
    }
    return result;
}
static long output(void *target, void *length_address, const void *data, long size) {
    unsigned length;
    long error=md_read_memory(&length,length_address,sizeof(length));
    if (!error) error=md_write_memory(target,data,length<(unsigned)size ? length : (unsigned)size);
    if (!error) { length=(unsigned)size; error=md_write_memory(length_address,&length,sizeof(length)); }
    return error;
}
static int queue_option(unsigned long level,unsigned long option) {
    if (level!=SOL_SOCKET) return 0;
    switch (option) {
    case SO_SNDTIMEO: case SO_RCVTIMEO: case SO_SNDBUF: case SO_RCVBUF:
    case SO_SNDBUFFORCE: case SO_RCVBUFFORCE:
    /* Netlink port IDs remain exclusive; this generic socket flag only
     * needs the kernel-owned value, shared by dup and fork. */
    case SO_REUSEADDR:
        return 1;
    default: return 0;
    }
}
static long data_message(long nr,const unsigned long *a) {
    int sending=nr==SYS_sendto || nr==SYS_sendmsg;
    int header=nr==SYS_sendmsg || nr==SYS_recvmsg;
    struct iovec vector={(void *)a[1],a[2]};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,.msg_name=(void *)a[4]};
    struct md_socket_vectors vectors;
    long result=0;
    if (header) {
        result=md_read_memory(&message,(void *)a[1],sizeof(message));
        if (result) return result;
        if (message.msg_controllen>MD_IPC_IO_CONTROL) return -EMSGSIZE;
        result=md_socket_vectors_open(&vectors,&message);
        if (result) return result;
    } else if (a[4]) {
        if (sending) message.msg_namelen=a[5];
        else result=md_read_memory(&message.msg_namelen,(void *)a[5],sizeof(message.msg_namelen));
    }
    long address=result ? result : md_adapter_buffer_take();
    if (address>=0) {
        struct md_task_receive_frame *frame=(void *)address;
        frame->input=(struct md_task_receive){.socket=(int)a[0]};
        frame->io.batch_count=0;
        result=md_socket_capture_inputs(md_guest_buffer_control(MD_BUFFER_NETWORK_DATA,0,0),
            &frame->capture,&frame->input.socket,1);
        if (result==MD_IPC_TASK_IO) result=md_socket_payload_prepare(&frame->io,&message,sending);
        if (!result) result=md_guest_buffer_control(MD_FS_BUFFER_IPC_RECEIVE,0,0);
        if (!sending && result>=0) {
            result=md_socket_payload_scatter(&frame->io,&message,result,0);
            if (!message.msg_name) message.msg_namelen=0;
            long error=0;
            if (result>=0 && header) {
                error=md_write_memory((char *)a[1]+offsetof(struct msghdr,msg_namelen),
                    &message.msg_namelen,sizeof(message.msg_namelen));
                if (!error) error=md_write_memory((char *)a[1]+offsetof(struct msghdr,msg_controllen),
                    &message.msg_controllen,sizeof(message.msg_controllen));
                if (!error) error=md_write_memory((char *)a[1]+offsetof(struct msghdr,msg_flags),
                    &message.msg_flags,sizeof(message.msg_flags));
            } else if (result>=0 && a[4]) error=md_write_memory((void *)a[5],&message.msg_namelen,sizeof(message.msg_namelen));
            if (error) result=error;
        }
        long dropped=md_guest_buffer_control(MD_FS_BUFFER_RECEIVED_DROP,0,0);
        long released=md_adapter_buffer_release();
        if (result>=0 && (dropped || released)) result=dropped ? dropped : released;
    } else result=address;
    if (header) md_socket_vectors_close(&vectors);
    return result;
}
static long data_send_batch(const unsigned long *a) {
    unsigned count=(unsigned)a[2];
    if (count>1024) count=1024;
    for (unsigned i=0;i<count;++i) {
        struct mmsghdr *item=(struct mmsghdr *)a[1]+i;
        unsigned long one[6]={a[0],(uintptr_t)&item->msg_hdr,a[3]};
        long result=data_message(SYS_sendmsg,one);
        if (result<0) return i ? (long)i : result;
        unsigned length=(unsigned)result;
        long error=md_write_memory(&item->msg_len,&length,sizeof(length));
        if (error) return i ? (long)i : error;
    }
    return count;
}
long md_network_data_call(long nr,const unsigned long *a) {
    int device=(nr==SYS_setsockopt || nr==SYS_getsockopt) && a[1]==SOL_SOCKET && a[2]==SO_BINDTODEVICE;
    int transport=(nr==SYS_setsockopt || nr==SYS_getsockopt) && md_network_endpoint_option(a[1],a[2]);
    int option=device || transport;
    if (nr==SYS_read || nr==SYS_write || nr==SYS_readv || nr==SYS_writev
            || md_socket_payload_native(nr,a[4])
            || (nr==SYS_getsockopt && !option) || (nr==SYS_ioctl && (a[1]==FIONBIO || a[1]==FIONREAD || a[1]==TIOCOUTQ)))
        return md_raw(nr,a[0],a[1],a[2],a[3],a[4],a[5]);
    if (nr==SYS_setsockopt && !option) {
        return md_raw(nr,a[0],a[1],a[2],a[3],a[4],a[5]);
    }
    if (nr==SYS_sendto || nr==SYS_recvfrom || nr==SYS_sendmsg || nr==SYS_recvmsg)
        return data_message(nr,a);
    if (nr==SYS_sendmmsg) return data_send_batch(a);
    if (nr!=SYS_bind && nr!=SYS_connect && nr!=SYS_accept && nr!=SYS_accept4 && nr!=SYS_listen
            && nr!=SYS_getsockname && nr!=SYS_getpeername && !option) return -EOPNOTSUPP;
    long address=md_adapter_buffer_take(); if (address<0) return address;
    struct md_task_receive_frame *frame=(void *)address;
    frame->input=(struct md_task_receive){.socket=(int)a[0]};
    frame->io.address_size=frame->io.control_size=frame->io.flags=frame->io.batch_count=0;
    frame->io.size=0;
    long result=0;
    if (device && nr==SYS_setsockopt) {
        unsigned length=(unsigned)a[4];
        if ((int)length<0) result=-EINVAL;
        else {
            if (length>=IFNAMSIZ) length=IFNAMSIZ-1;
            frame->io.address_size=length;
            result=md_read_memory(&frame->io.address,(void *)a[3],length);
            ((char *)&frame->io.address)[length]=0;
        }
    } else if (transport && nr==SYS_setsockopt) {
        if ((int)a[4]<0 || a[4]>sizeof(frame->io.control)) result=-EINVAL;
        else {
            frame->io.address_size=a[4];
            result=md_read_memory(frame->io.control,(void *)a[3],a[4]);
        }
    } else if (option) result=md_read_memory(&frame->io.address_size,(void *)a[4],sizeof(socklen_t));
    if (nr==SYS_bind || nr==SYS_connect) {
        if (a[2]>sizeof(frame->io.address)) result=-EINVAL;
        else {
            frame->io.address_size=a[2];
            result=md_read_memory(&frame->io.address,(void *)a[1],a[2]);
        }
    }
    if (!result) result=md_socket_capture_inputs(md_guest_buffer_control(MD_BUFFER_NETWORK_DATA,0,0),
        &frame->capture,&frame->input.socket,1);
    if (result==MD_IPC_TASK_IO) result=md_guest_buffer_control(MD_FS_BUFFER_IPC_RECEIVE,0,0);
    if (!result && option && nr==SYS_getsockopt)
        result=output((void *)a[3],(void *)a[4],device ? (void *)&frame->io.address : frame->io.control,frame->io.address_size);
    if (!result && (nr==SYS_getsockname || nr==SYS_getpeername || ((nr==SYS_accept || nr==SYS_accept4) && a[1])))
        result=output((void *)a[1],(void *)a[2],&frame->io.address,frame->io.address_size);
    long dropped=md_guest_buffer_control(MD_FS_BUFFER_RECEIVED_DROP,0,0);
    if (!result && dropped) result=dropped;
    unsigned selectors=0;
    if (!result && (nr==SYS_accept || nr==SYS_accept4)) {
        struct md_fs_buffer_frame *delivery=(void *)address;
        delivery->transfer=MD_FS_BUFFER_RIGHTS;
        result=md_adapter_receive_fd(delivery,nr==SYS_accept4 ? a[3]&SOCK_CLOEXEC : 0,MD_FS_BUFFER_RIGHTS_BEGIN);
        if (result>=0) selectors=delivery->reply.result.selectors;
    }
    long released=md_adapter_buffer_release();
    if (result>=0 && (nr==SYS_accept || nr==SYS_accept4)) {
        long armed=released ? released : md_fd_filter_arm_mask(selectors,(int)result);
        if (armed<0) { RAW1(close,result); return armed; }
    }
    return result ? result : released;
}
static long device_receive_ready(void) {
    long memory=md_adapter_buffer_take(); if (memory<0) return memory;
    long result=md_guest_buffer_control(MD_BUFFER_NETWORK_CALL,0,0);
    long released=md_adapter_buffer_release(); return result ? result : released;
}
static long device_source(const struct msghdr *message,long received,struct sockaddr_nl *source) {
    char prefix[8]={0}; size_t used=0;
    size_t needed=received<8 ? (size_t)received : 8;
    for (size_t i=0;i<message->msg_iovlen && used<needed;++i) {
        struct iovec part;
        long error=md_read_memory(&part,message->msg_iov+i,sizeof(part)); if (error) return error;
        size_t n=part.iov_len<needed-used ? part.iov_len : needed-used;
        if (n) { error=md_read_memory(prefix+used,part.iov_base,n); if (error) return error; used+=n; }
    }
    unsigned match=used==8;
    for (unsigned i=0;i<8;++i) match&=prefix[i]=="libudev"[i];
    source->nl_groups=match ? 2 : 1;
    return 0;
}
static long device_credentials(const struct msghdr *message) {
    size_t offset=0;
    while (message->msg_controllen-offset>=sizeof(struct cmsghdr)) {
        struct cmsghdr c;
        long error=md_read_memory(&c,(char *)message->msg_control+offset,sizeof(c)); if (error) return error;
        if (c.cmsg_len<CMSG_LEN(0) || c.cmsg_len>message->msg_controllen-offset) return -EPROTO;
        if (c.cmsg_level==SOL_SOCKET && c.cmsg_type==SCM_CREDENTIALS && c.cmsg_len==CMSG_LEN(sizeof(struct ucred))) {
            struct ucred trusted={0};
            error=md_write_memory((char *)message->msg_control+offset+CMSG_LEN(0),&trusted,sizeof(trusted));
            if (error) return error;
        }
        size_t step=CMSG_ALIGN(c.cmsg_len);
        if (step>message->msg_controllen-offset) break;
        offset+=step;
    }
    return 0;
}
static long control_call(long nr,const unsigned long *a,int events) {
    /* These options describe the real message queue, not a host interface or
     * peer identity. Let the kernel own validation, dup sharing and timeouts. */
    if ((nr==SYS_setsockopt || nr==SYS_getsockopt) && queue_option(a[1],a[2]))
        return md_raw(nr,a[0],a[1],a[2],a[3],a[4],a[5]);
    if (events && (nr==SYS_setsockopt || nr==SYS_getsockopt) && a[1]==SOL_SOCKET
            && (a[2]==SO_PASSCRED || a[2]==SO_ATTACH_FILTER || a[2]==SO_DETACH_FILTER || a[2]==SO_RCVBUFFORCE))
        return md_raw(nr,a[0],a[1],a[2],a[3],a[4],a[5]);
    if (!events && nr==SYS_ioctl && a[1]==FIONREAD) {
        int bytes=0;
        long error=RAW3(ioctl,a[0],a[1],&bytes);
        if (error) return error;
        if (bytes>=(int)sizeof(struct md_network_packet)) bytes-=sizeof(struct md_network_packet);
        return md_write_memory((void *)a[2],&bytes,sizeof(bytes));
    }
    if (nr==SYS_ioctl && (a[1]==FIONBIO || a[1]==FIONREAD || a[1]==TIOCOUTQ))
        return RAW3(ioctl,a[0],a[1],a[2]);
    if (nr==SYS_read || nr==SYS_readv || nr==SYS_recvfrom || nr==SYS_recvmsg || nr==SYS_recvmmsg) {
        if (!events) return md_network_packet_receive(nr,a);
        if (events) { long error=device_receive_ready(); if (error) return error; }
        if (nr==SYS_recvfrom && a[4]) {
            long result=RAW6(recvfrom,a[0],a[1],a[2],a[3],0,0);
            struct sockaddr_nl source={.nl_family=AF_NETLINK};
            struct iovec vector={(void *)a[1],a[2]};
            struct msghdr message={.msg_iov=&vector,.msg_iovlen=1};
            long error=events && result>=0 ? device_source(&message,result<(long)a[2] ? result : (long)a[2],&source) : 0;
            if (!error && result>=0) error=output((void *)a[4],(void *)a[5],&source,sizeof(source));
            return error ? error : result;
        }
        if (nr==SYS_recvmsg) {
            struct msghdr original;
            long error=md_read_memory(&original,(void *)a[1],sizeof(original)); if (error) return error;
            struct msghdr native=original;
            native.msg_name=NULL; native.msg_namelen=0;
            long result=RAW3(recvmsg,a[0],&native,a[2]); if (result<0) return result;
            if (original.msg_name) {
                struct sockaddr_nl source={.nl_family=AF_NETLINK};
                if (events) { error=device_source(&native,result,&source); if (error) return error; }
                size_t size=original.msg_namelen<sizeof(source) ? original.msg_namelen : sizeof(source);
                error=md_write_memory(original.msg_name,&source,size);
                original.msg_namelen=sizeof(source);
            }
            original.msg_controllen=native.msg_controllen; original.msg_flags=native.msg_flags;
            if (!error && events) error=device_credentials(&native);
            if (!error) error=md_write_memory((void *)a[1],&original,sizeof(original));
            return error ? error : result;
        }
        if (nr==SYS_recvmmsg) return -EOPNOTSUPP;
        return md_raw(nr,a[0],a[1],a[2],a[3],a[4],a[5]);
    }
    size_t size=0; const void *input=NULL;
    unsigned filter_capacity=0;
    struct ifconf interfaces={0};
    struct ifreq bridge={0};
    unsigned long bridge_args[4]={0};
    struct md_network_send destination={0};
    struct msghdr message={0};
    int gathered=nr==SYS_sendmsg || nr==SYS_writev;
    struct md_socket_vectors vectors;
    if (nr==SYS_sendmsg) {
        long error=md_read_memory(&message,(void *)a[1],sizeof(message)); if (error) return error;
        if (a[2]&~(MSG_DONTWAIT|MSG_NOSIGNAL)) return -EOPNOTSUPP;
        if (message.msg_controllen>sizeof(destination.control)) return -EMSGSIZE;
        destination.control_size=message.msg_controllen;
        if (destination.control_size) {
            error=md_read_memory(destination.control,message.msg_control,destination.control_size);
            if (error) return error;
        }
        if (message.msg_name) {
            if (message.msg_namelen<sizeof(sa_family_t) || message.msg_namelen>sizeof(destination.value)) return -EINVAL;
            destination.size=message.msg_namelen;
            error=md_read_memory(&destination.value,message.msg_name,destination.size); if (error) return error;
        }
    } else if (nr==SYS_writev) {
        message.msg_iov=(void *)a[1]; message.msg_iovlen=a[2];
    }
    else if (nr==SYS_ioctl) {
        input=(void *)a[2];
        if (a[1]==SIOCGIFCONF) {
            long error=md_read_memory(&interfaces,input,sizeof(interfaces)); if (error) return error;
            size=sizeof(struct md_network_ifconf);
        } else if (a[1]==SIOCDEVPRIVATE) {
            long error=md_read_memory(&bridge,input,sizeof(bridge)); if (error) return error;
            error=md_read_memory(bridge_args,bridge.ifr_data,sizeof(bridge_args)); if (error) return error;
            size=sizeof(struct md_network_bridge_ioctl);
        } else size=md_network_route_ioctl(a[1]) ? sizeof(struct md_network_route_ioctl)
            : md_network_arp_ioctl(a[1]) ? sizeof(struct arpreq) : sizeof(struct ifreq);
    }
    else if (nr==SYS_bind || nr==SYS_connect) { size=a[2]; input=(void *)a[1]; }
    else if (nr==SYS_write || nr==SYS_sendto) {
        size=a[2]; input=(void *)a[1];
        if (nr==SYS_sendto) {
            if (a[3]&~(MSG_DONTWAIT|MSG_NOSIGNAL)) return -EOPNOTSUPP;
            if (a[4]) {
                if (a[5]<sizeof(sa_family_t) || a[5]>sizeof(destination.value)) return -EINVAL;
                destination.size=a[5];
                long error=md_read_memory(&destination.value,(void *)a[4],destination.size); if (error) return error;
            }
        }
    } else if (nr==SYS_setsockopt) {
        size=a[4]; input=(void *)a[3];
        if (!events && a[1]==SOL_SOCKET && a[2]==SO_ATTACH_FILTER) {
            if (size!=sizeof(struct sock_fprog)) return -EINVAL;
            struct sock_fprog program;
            long error=md_read_memory(&program,input,sizeof(program)); if (error) return error;
            if (!program.len || program.len>BPF_MAXINSNS) return -EINVAL;
            size=program.len*sizeof(*program.filter); input=program.filter;
        }
    }
    else if (nr!=SYS_getsockname && nr!=SYS_getpeername && nr!=SYS_getsockopt) return -EOPNOTSUPP;
    if (!events && nr==SYS_getsockopt && a[1]==SOL_SOCKET && a[2]==SO_GET_FILTER) {
        long error=md_read_memory(&filter_capacity,(void *)a[4],sizeof(filter_capacity));
        if (error) return error;
    }
    if (size>MD_NETWORK_BUFFER_SIZE) return -EMSGSIZE;
    if (gathered) {
        long error=md_socket_vectors_open(&vectors,&message);
        if (error) return error;
        for (size_t i=0;i<message.msg_iovlen;++i) {
            if (message.msg_iov[i].iov_len>MD_NETWORK_BUFFER_SIZE-size) {
                md_socket_vectors_close(&vectors); return -EMSGSIZE;
            }
            size+=message.msg_iov[i].iov_len;
        }
    }
    long memory=md_adapter_buffer_take();
    if (memory<0) {
        if (gathered) md_socket_vectors_close(&vectors);
        return memory;
    }
    struct md_network_request *request=(void *)memory;
    request->destination=destination;
    void *data=request->data;
    long result=0;
    if (nr==SYS_ioctl && md_network_family_ioctl(a[1])) {
        long bytes=md_guest_buffer_control(MD_BUFFER_NETWORK_CALL,0,0);
        if (bytes<=0 || bytes>MD_NETWORK_BUFFER_SIZE) {
            md_adapter_buffer_release(); return bytes<0 ? bytes : -EOVERFLOW;
        }
        size=(size_t)bytes;
    }
    if (gathered) {
        size_t offset=0;
        for (size_t i=0;!result && i<message.msg_iovlen;++i) {
            result=md_read_memory((char *)data+offset,message.msg_iov[i].iov_base,message.msg_iov[i].iov_len);
            offset+=message.msg_iov[i].iov_len;
        }
        md_socket_vectors_close(&vectors);
    } else if (nr==SYS_ioctl && a[1]==SIOCGIFCONF) {
        struct md_network_ifconf *list=data;
        unsigned maximum=MD_NETWORK_BUFFER_SIZE-size;
        list->capacity=!interfaces.ifc_buf ? -1 : interfaces.ifc_len<0 ? 0
            : (unsigned)interfaces.ifc_len<maximum ? interfaces.ifc_len : (int)maximum;
        list->length=0;
    } else if (nr==SYS_ioctl && a[1]==SIOCDEVPRIVATE) {
        struct md_network_bridge_ioctl *r=data; memset(r,0,sizeof(*r));
        memcpy(r->name,bridge.ifr_name,IFNAMSIZ); memcpy(r->args,bridge_args,sizeof(r->args));
        if (r->args[0]==BRCTL_GET_BRIDGE_INFO) r->args[1]=0;
    } else if (nr==SYS_ioctl && md_network_route_ioctl(a[1]) && size==sizeof(struct md_network_route_ioctl)) {
        struct md_network_route_ioctl *r=data; memset(r,0,sizeof(*r));
        result=md_read_memory(&r->route,input,sizeof(r->route));
        if(!result && r->route.rt_dev) {
            result=md_read_string(r->device,sizeof(r->device),r->route.rt_dev);
            if(result>=0) result=0;
        }
        r->route.rt_dev=NULL;
    } else if (size) result=md_read_memory(data,input,size);
    if (!result) result=md_guest_buffer_control(MD_BUFFER_NETWORK_CALL,size,0);
    if (!result && nr==SYS_ioctl && a[1]==SIOCGIFCONF) {
        struct md_network_ifconf *list=data;
        if (interfaces.ifc_buf && list->length) result=md_write_memory(interfaces.ifc_buf,list->entries,list->length);
        interfaces.ifc_len=list->length;
        if (!result) result=md_write_memory((void *)a[2],&interfaces,sizeof(interfaces));
    } else if (!result && nr==SYS_ioctl && a[1]==SIOCDEVPRIVATE) {
        if (bridge_args[0]==BRCTL_GET_BRIDGE_INFO)
            result=md_write_memory((void *)bridge_args[1],&((struct md_network_bridge_ioctl *)data)->info,sizeof(struct __bridge_info));
    } else if (!result && nr==SYS_ioctl && !md_network_family_ioctl(a[1]) && a[1]!=SIOCSIFHWADDR && a[1]!=SIOCBRADDIF
            && a[1]!=SIOCSARP && a[1]!=SIOCDARP)
        result=md_write_memory((void *)a[2],data,size);
    else if (result>=0 && !events && nr==SYS_getsockopt && a[1]==SOL_SOCKET && a[2]==SO_GET_FILTER) {
        unsigned count=(unsigned)result/sizeof(struct sock_filter);
        if (filter_capacity && filter_capacity<count) result=-EINVAL;
        else {
            result=filter_capacity ? md_write_memory((void *)a[3],data,(size_t)result) : 0;
            if (!result) result=md_write_memory((void *)a[4],&count,sizeof(count));
        }
    }
    else if (result>=0 && (nr==SYS_getsockname || nr==SYS_getpeername || nr==SYS_getsockopt))
        result=output((void *)a[nr==SYS_getsockopt ? 3 : 1],(void *)a[nr==SYS_getsockopt ? 4 : 2],data,result);
    long released=md_adapter_buffer_release(); return result ? result : released;
}
long md_network_call(long nr,const unsigned long *a) { return control_call(nr,a,0); }
long md_device_event_call(long nr,const unsigned long *a) { return control_call(nr,a,1); }
