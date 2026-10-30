#define _GNU_SOURCE
#include "network_tun.h"
#include "network_internal.h"
#include "network_route.h"
#include "network_stack.h"
#include "network_ethernet.h"
#include "guest_identity.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/if_tun.h>
#include <linux/if_arp.h>
#include <linux/if_ether.h>
#include <linux/magic.h>
#include <net/if.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/sysmacros.h>
#include <unistd.h>

struct packet { struct packet *next; size_t size; unsigned char data[]; };
struct queue {
    atomic_uint references;
    struct md_network_namespace *space;
    struct md_network_tun *tunnel;
    struct queue *next;
    struct packet *head, **tail;
    size_t packets, bytes;
    int event, detached, offered;
    struct md_resource_wakeup *reader;
};
struct md_network_tun {
    struct md_network_tun *next;
    struct queue *queues;
    unsigned index, flags, persist, carrier, next_queue;
    uint32_t uid, gid;
};
static const struct md_control_file_ops operations;
static struct md_network_tun *find(struct md_network_namespace *n,unsigned index) {
    for (struct md_network_tun *t=n->tunnels;t;t=t->next) if(t->index==index) return t;
    return NULL;
}
static void signal_queue(struct queue *q) {
    uint64_t one=1;
    if(q->event>=0) { ssize_t result=write(q->event,&one,sizeof(one)); (void)result; }
    md_resource_wakeup_signal(q->reader);
}
static void clear_packets(struct queue *q) {
    while(q->head) { struct packet *p=q->head; q->head=p->next; free(p); }
    q->tail=&q->head; q->bytes=q->packets=0; q->offered=0;
}
static void remove_tunnel(struct md_network_namespace *n,struct md_network_tun *t) {
    struct md_network_tun **at=&n->tunnels;
    while(*at && *at!=t) at=&(*at)->next;
    if(*at) *at=t->next;
    while(t->queues) {
        struct queue *q=t->queues; t->queues=q->next;
        q->next=NULL; q->tunnel=NULL; clear_packets(q); signal_queue(q);
    }
    free(t);
}
void md_network_tun_changed(struct md_network_namespace *n) {
    struct md_network_tun *t=n->tunnels;
    while(t) {
        struct md_network_tun *next=t->next;
        if(!md_network_interface_find(n,t->index,NULL)) remove_tunnel(n,t);
        t=next;
    }
}
void md_network_tun_retire(struct md_network_namespace *n) {
    while(n->tunnels) remove_tunnel(n,n->tunnels);
}
int md_network_tun_carrier(struct md_network_namespace *n,unsigned index) {
    struct md_network_tun *t=find(n,index);
    if(t && t->carrier) for(struct queue *q=t->queues;q;q=q->next) if(!q->detached) return 1;
    return 0;
}
static void *retain(void *context) {
    struct queue *q=context; atomic_fetch_add_explicit(&q->references,1,memory_order_relaxed); return q;
}
static void release(void *context) {
    struct queue *q=context;
    if(atomic_fetch_sub_explicit(&q->references,1,memory_order_acq_rel)!=1) return;
    struct md_network_namespace *n=q->space;
    if(n) {
        pthread_mutex_lock(&n->fabric->lock);
        struct md_network_tun *t=q->tunnel;
        if(t) {
            struct queue **at=&t->queues;
            while(*at && *at!=q) at=&(*at)->next;
            if(*at) *at=q->next;
            if(!t->queues && !t->persist) md_network_route_link_removed(n,t->index);
            else md_network_route_link_created(n,t->index);
        }
        pthread_mutex_unlock(&n->fabric->lock);
    }
    clear_packets(q); if(q->event>=0) close(q->event);
    md_resource_wakeup_release(q->reader); md_network_namespace_release(n); free(q);
}
static struct queue *new_queue(void) {
    struct queue *q=calloc(1,sizeof(*q));
    if(q) { atomic_init(&q->references,1); q->event=-1; q->tail=&q->head; }
    return q;
}
int md_tun_device_node(struct md_control_file *out) {
    struct queue *q=new_queue(); if(!q) return -ENOMEM;
    *out=(struct md_control_file){&operations,q}; return 0;
}
int md_tun_device_is_node(struct md_control_file f) {
    return f.ops==&operations && !((struct queue *)f.context)->space;
}
static void *open_device(void *context,const struct md_control_opener *opener,int flags) {
    (void)context;
    if(!opener || !opener->network) { errno=ENXIO; return NULL; }
    if(flags&O_DIRECTORY) { errno=ENOTDIR; return NULL; }
    struct queue *q=new_queue(); if(!q) return NULL;
    q->space=md_network_namespace_retain(opener->network);
    q->event=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    if(q->event<0) { int error=errno; release(q); errno=error; return NULL; }
    return q;
}
static int metadata(void *context,struct stat *st) {
    (void)context;
    *st=(struct stat){.st_mode=S_IFCHR|0666,.st_nlink=1,.st_ino=200,
        .st_dev=makedev(0,99),.st_rdev=makedev(10,200),.st_blksize=4096}; return 0;
}
static int access_device(void *context,const struct md_identity *id,unsigned mode) {
    (void)context; (void)id; return mode&X_OK ? -EACCES : 0;
}
static int descriptor(void *context,int flags) {
    struct queue *q=context;
    int fd=flags&O_PATH ? open("/dev/null",O_PATH|O_CLOEXEC) : fcntl(q->event,F_DUPFD_CLOEXEC,3);
    return fd<0 ? -errno : fd;
}
static long read_packet(void *context,const struct md_control_actor *actor,void *data,size_t size,int64_t offset) {
    (void)offset; struct queue *q=context; struct md_network_namespace *n=q->space;
    if(!n) return -EBADFD;
    pthread_mutex_lock(&n->fabric->lock);
    struct md_resource_wakeup *old=q->reader;
    q->reader=md_resource_wakeup_retain(actor ? actor->wakeup : NULL);
    md_resource_wakeup_release(old);
    long result=!q->tunnel || q->detached ? -EBADFD : !size ? 0 : !q->head ? -EAGAIN : 0;
    if(!result && size && q->head) {
        size_t bytes=q->head->size<size ? q->head->size : size;
        if(!(q->tunnel->flags&IFF_NO_PI) && bytes<sizeof(struct tun_pi)) result=-EINVAL;
        else {
            memcpy(data,q->head->data,bytes);
            if(bytes<q->head->size && !(q->tunnel->flags&IFF_NO_PI)) ((struct tun_pi *)data)->flags|=TUN_PKT_STRIP;
            q->offered=1; result=(long)bytes;
        }
    }
    pthread_mutex_unlock(&n->fabric->lock); return result;
}
static void read_commit(void *context,size_t copied) {
    struct queue *q=context;
    pthread_mutex_lock(&q->space->fabric->lock);
    if(q->offered && copied && q->head) {
        struct packet *p=q->head; q->head=p->next;
        q->bytes-=p->size; --q->packets; free(p);
        if(!q->head) {
            q->tail=&q->head;
            uint64_t value; while(read(q->event,&value,sizeof(value))>0) {}
        }
    }
    q->offered=0; pthread_mutex_unlock(&q->space->fabric->lock);
}
int md_network_tun_output(struct md_network_namespace *n,unsigned index,const void *data,size_t size) {
    struct md_network_tun *t=find(n,index);
    if(!t || !t->carrier) return -ENETDOWN;
    unsigned count=0; for(struct queue *q=t->queues;q;q=q->next) if(!q->detached) ++count;
    if(!count) return -ENETDOWN;
    unsigned selected=t->next_queue++%count;
    struct queue *q=t->queues;
    while(q) { if(!q->detached && !selected--) break; q=q->next; }
    if(!q) return -ENETDOWN;
    size_t prefix=t->flags&IFF_NO_PI ? 0 : sizeof(struct tun_pi);
    if(size>65535 || q->packets>=1024 || q->bytes+size+prefix>4*1024*1024) return -ENOBUFS;
    struct packet *p=malloc(sizeof(*p)+prefix+size); if(!p) return -ENOMEM;
    *p=(struct packet){.size=prefix+size};
    if(prefix) {
        uint16_t protocol=0;
        if((t->flags&IFF_TAP) && size>=ETH_HLEN) memcpy(&protocol,(const char *)data+12,2);
        else if(size) protocol=htons((*(const unsigned char *)data>>4)==6 ? ETH_P_IPV6 : ETH_P_IP);
        struct tun_pi pi={.proto=protocol}; memcpy(p->data,&pi,sizeof(pi));
    }
    int empty=!q->head;
    memcpy(p->data+prefix,data,size); *q->tail=p; q->tail=&p->next;
    ++q->packets; q->bytes+=p->size; if(empty) signal_queue(q); return 0;
}
static long write_packet(void *context,const struct md_control_actor *actor,const void *data,size_t size,int64_t offset) {
    (void)actor; (void)offset; struct queue *q=context;
    if(!q->space) return -EBADFD;
    pthread_mutex_lock(&q->space->fabric->lock);
    struct md_network_tun *t=q->tunnel;
    struct md_network_interface *link=t ? md_network_interface_find(q->space,t->index,NULL) : NULL;
    int error=!t || q->detached ? -EBADFD : !link ? -ENODEV : !(link->info.flags&IFF_UP) ? -EIO : 0;
    size_t skip=t && !(t->flags&IFF_NO_PI) ? sizeof(struct tun_pi) : 0;
    size_t minimum=t && (t->flags&IFF_TAP) ? ETH_HLEN : 1;
    if(!error && (size<skip+minimum || size-skip>65535)) error=-EINVAL;
    if(!error && skip && !(t->flags&IFF_TAP)) {
        struct tun_pi pi; memcpy(&pi,data,sizeof(pi));
        unsigned version=*((const unsigned char *)data+skip)>>4;
        if((pi.proto!=htons(ETH_P_IP) || version!=4) && (pi.proto!=htons(ETH_P_IPV6) || version!=6)) error=-EINVAL;
    }
    if(!error) error=t->flags&IFF_TAP ? md_network_ethernet_receive(q->space,link,(const char *)data+skip,size-skip)
        : md_network_stack_input(q->space,link,(const char *)data+skip,size-skip);
    pthread_mutex_unlock(&q->space->fabric->lock); return error ? error : (long)size;
}
static int named(struct md_network_namespace *n,char name[IFNAMSIZ],int tap) {
    if(!name[0]) snprintf(name,IFNAMSIZ,"%s%%d",tap ? "tap" : "tun");
    char *slot=strchr(name,'%');
    if(!slot) return 0;
    if(slot[1]!='d' || strchr(slot+2,'%')) return -EINVAL;
    char pattern[IFNAMSIZ]; memcpy(pattern,name,sizeof(pattern));
    size_t prefix=(size_t)(slot-name);
    for(unsigned i=0;i<65536;++i) {
        int size=snprintf(name,IFNAMSIZ,"%.*s%u%s",(int)prefix,pattern,i,pattern+prefix+2);
        if(size>=IFNAMSIZ) return -EINVAL;
        if(!md_network_interface_find(n,0,name)) return 0;
    }
    return -ENFILE;
}
static int set_interface(struct queue *q,const struct md_identity *id,struct ifreq *request) {
    if(q->tunnel || q->detached) return -EEXIST;
    struct md_network_namespace *n=q->space;
    if(!md_network_namespace_managed(n)) return -EOPNOTSUPP;
    unsigned flags=(unsigned short)request->ifr_flags;
    if((flags&(IFF_TUN|IFF_TAP))!=IFF_TUN && (flags&(IFF_TUN|IFF_TAP))!=IFF_TAP) return -EINVAL;
    if(flags&~(IFF_TUN|IFF_TAP|IFF_NO_PI|IFF_MULTI_QUEUE|IFF_TUN_EXCL|IFF_ONE_QUEUE)) return -EOPNOTSUPP;
    request->ifr_name[IFNAMSIZ-1]=0;
    int error=named(n,request->ifr_name,!!(flags&IFF_TAP)); if(error) return error;
    struct md_network_interface *link=md_network_interface_find(n,0,request->ifr_name);
    struct md_network_tun *t=link ? find(n,link->info.index) : NULL;
    int admin=md_identity_capable_in(id,n->owner,CAP_NET_ADMIN);
    if(link) {
        if(flags&IFF_TUN_EXCL) return -EBUSY;
        if(!t || ((t->flags^flags)&(IFF_TUN|IFF_TAP|IFF_NO_PI|IFF_MULTI_QUEUE))) return -EINVAL;
        if(!admin && !((t->uid!=UINT32_MAX && id->uid.effective==t->uid)
                || (t->gid!=UINT32_MAX && md_identity_in_group(id,t->gid)))) return -EPERM;
        if(!(flags&IFF_MULTI_QUEUE) && t->queues) return -EBUSY;
    } else {
        if(!admin) return -EPERM;
        error=md_network_stack_prepare(n); if(error) return error;
        t=calloc(1,sizeof(*t)); if(!t) return -ENOMEM;
        error=md_network_interface_add(n,flags&IFF_TAP ? MD_LINK_TAP : MD_LINK_TUN,request->ifr_name,&t->index);
        if(error) { free(t); return error; }
        t->flags=flags&(IFF_TUN|IFF_TAP|IFF_NO_PI|IFF_MULTI_QUEUE|IFF_ONE_QUEUE);
        t->uid=t->gid=UINT32_MAX; t->carrier=1; t->next=n->tunnels; n->tunnels=t;
        link=md_network_interface_find(n,t->index,NULL); link->info.tx_queue_length=500;
        if(flags&IFF_TUN) { link->info.type=ARPHRD_NONE; link->info.flags=IFF_POINTOPOINT|IFF_NOARP|IFF_MULTICAST; }
    }
    q->tunnel=t; q->next=t->queues; t->queues=q; signal_queue(q);
    request->ifr_flags=(short)t->flags;
    md_network_route_link_created(n,t->index);
    return 0;
}
static int ioctl_spec(void *context,unsigned command,struct md_control_ioctl *out) {
    (void)context; *out=(struct md_control_ioctl){0};
    switch(command) {
    case TUNSETIFF: case TUNSETQUEUE: *out=(struct md_control_ioctl){.size=sizeof(struct ifreq),.direction=MD_CONTROL_IN|MD_CONTROL_OUT}; return 0;
    case TUNGETIFF: *out=(struct md_control_ioctl){.size=sizeof(struct ifreq),.direction=MD_CONTROL_OUT}; return 0;
    case TUNGETFEATURES: *out=(struct md_control_ioctl){.size=sizeof(unsigned),.direction=MD_CONTROL_OUT}; return 0;
    case TUNSETPERSIST: case TUNSETOWNER: case TUNSETGROUP: case TUNSETOFFLOAD: case TUNSETNOCSUM: return 0;
    case TUNSETCARRIER: *out=(struct md_control_ioctl){.size=sizeof(int),.direction=MD_CONTROL_IN}; return 0;
    default: return -EINVAL;
    }
}
static long ioctl_locked(struct queue *q,const struct md_control_actor *actor,unsigned command,unsigned long value,void *data) {
    struct md_network_namespace *n=q->space;
    if(command==TUNGETFEATURES) { *(unsigned *)data=IFF_TUN|IFF_TAP|IFF_NO_PI|IFF_MULTI_QUEUE; return 0; }
    if(command==TUNSETIFF) return set_interface(q,actor->identity,data);
    struct md_network_tun *t=q->tunnel;
    if(!t) return -EBADFD;
    if(command==TUNGETIFF) {
        struct md_network_interface *link=md_network_interface_find(n,t->index,NULL);
        if(!link) return -ENODEV;
        struct ifreq *r=data; memset(r,0,sizeof(*r)); memcpy(r->ifr_name,link->info.name,IFNAMSIZ);
        r->ifr_flags=(short)(t->flags|IFF_NOFILTER|(q->detached ? IFF_DETACH_QUEUE : 0)|(t->persist ? IFF_PERSIST : 0)); return 0;
    }
    if(command==TUNSETQUEUE) {
        unsigned flags=(unsigned short)((struct ifreq *)data)->ifr_flags;
        if(!(t->flags&IFF_MULTI_QUEUE) || (flags!=IFF_ATTACH_QUEUE && flags!=IFF_DETACH_QUEUE)) return -EINVAL;
        int detach=flags==IFF_DETACH_QUEUE;
        if(detach==q->detached) return -EINVAL;
        q->detached=detach; if(detach) clear_packets(q);
        signal_queue(q); md_network_route_link_created(n,t->index); return 0;
    }
    if(!md_identity_capable_in(actor->identity,n->owner,CAP_NET_ADMIN)) return -EPERM;
    switch(command) {
    case TUNSETPERSIST: t->persist=!!value; return 0;
    case TUNSETOWNER: case TUNSETGROUP: {
        uint32_t id=md_user_namespace_into(actor->identity->user_namespace,command==TUNSETGROUP,(uint32_t)value);
        if(id==UINT32_MAX) return -EINVAL;
        if(command==TUNSETOWNER) t->uid=id; else t->gid=id; return 0;
    }
    case TUNSETOFFLOAD: return value ? -EOPNOTSUPP : 0;
    case TUNSETNOCSUM: return 0;
    case TUNSETCARRIER:
        if(*(int *)data!=0 && *(int *)data!=1) return -EINVAL;
        t->carrier=*(int *)data; md_network_route_link_created(n,t->index); return 0;
    default: return -EINVAL;
    }
}
static long ioctl_call(void *context,const struct md_control_actor *actor,unsigned command,unsigned long value,void *data,size_t size) {
    (void)size; struct queue *q=context;
    if(!q->space) return -EBADFD;
    pthread_mutex_lock(&q->space->fabric->lock);
    long result=ioctl_locked(q,actor,command,value,data);
    pthread_mutex_unlock(&q->space->fabric->lock); return result;
}
static long poll_queue(void *context) {
    struct queue *q=context; if(!q->space) return POLLERR;
    pthread_mutex_lock(&q->space->fabric->lock);
    long mask=!q->tunnel || q->detached ? POLLERR : POLLOUT|POLLWRNORM|(q->head ? POLLIN|POLLRDNORM : 0);
    uint64_t value; while(read(q->event,&value,sizeof(value))>0) {}
    pthread_mutex_unlock(&q->space->fabric->lock); return mask;
}
static int readable(void *context) { return !!(poll_queue(context)&(POLLIN|POLLERR)); }
static int poll_descriptor(void *context,int *fd) { *fd=((struct queue *)context)->event; return 0; }
static const struct md_control_file_ops operations={.retain=retain,.release=release,.stat=metadata,
    .access=access_device,.open=open_device,.descriptor=descriptor,.nonseekable=1,.filesystem=TMPFS_MAGIC,
    .read=read_packet,.read_commit=read_commit,.write=write_packet,.ioctl_spec=ioctl_spec,.ioctl=ioctl_call,
    .poll=poll_queue,.poll_descriptor=poll_descriptor,.readable=readable};
