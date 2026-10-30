#define _GNU_SOURCE
#include "network_proc.h"
#include "network_endpoint.h"
#include "network_internal.h"
#include "guest_identity.h"
#include <errno.h>
#include <linux/magic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

struct network_proc {
    atomic_uint references;
    struct md_network_namespace *network;
    enum md_network_table table;
    char *text;
    size_t size;
};
static void *retain(void *context) {
    struct network_proc *f=context;
    atomic_fetch_add_explicit(&f->references,1,memory_order_relaxed); return f;
}
static void release(void *context) {
    struct network_proc *f=context;
    if (atomic_fetch_sub_explicit(&f->references,1,memory_order_acq_rel)!=1) return;
    md_network_namespace_release(f->network); free(f->text); free(f);
}
static int metadata(void *context,struct stat *out) {
    struct network_proc *f=context;
    const struct md_user_namespace *owner=md_network_namespace_owner(f->network);
    *out=(struct stat){.st_dev=UINT32_C(0xfffc0000)+(unsigned)f->table,
        .st_ino=md_network_namespace_inode(f->network),.st_mode=S_IFREG|0444,
        .st_nlink=1,.st_blksize=getpagesize(),
        .st_uid=md_user_namespace_into(owner,0,0),.st_gid=md_user_namespace_into(owner,1,0)};
    return 0;
}
static int file_access(void *context,const struct md_identity *id,unsigned mode) {
    (void)context; (void)id;
    return mode&~7U ? -EINVAL : mode&(W_OK|X_OK) ? -EACCES : 0;
}
static int inode_order(const void *left,const void *right) {
    uint64_t a=((const struct md_network_endpoint_info *)left)->inode;
    uint64_t b=((const struct md_network_endpoint_info *)right)->inode;
    return (a>b)-(a<b);
}
static void socket_address(FILE *out,const struct md_inet_address *a,int ipv6) {
    if (ipv6) {
        struct in6_addr address=md_ip_in6(a->ip);
        for (unsigned i=0;i<4;++i) {
            uint32_t word; memcpy(&word,address.s6_addr+i*4,4); fprintf(out,"%08X",word);
        }
    } else fprintf(out,"%08X",a->ip.v4);
    fprintf(out,":%04X",ntohs(a->port));
}
static int interface_snapshot(struct network_proc *f) {
    struct md_network_namespace *n=f->network;
    char *text=NULL; size_t size=0;
    FILE *out=open_memstream(&text,&size);
    if (!out) return -errno;
    pthread_mutex_lock(&n->fabric->lock);
    for (unsigned i=0;i<n->count;++i) for (unsigned j=0;j<n->links[i].address_count;++j) {
        const struct md_network_ip_assignment *a=&n->links[i].addresses[j];
        if (a->address.family!=AF_INET6) continue;
        for (unsigned k=0;k<16;++k) fprintf(out,"%02x",a->address.bytes[k]);
        /* if_inet6 uses IPv6 scope bits, not rtnetlink's RT_SCOPE values. */
        unsigned scope=md_ip_is_loopback(a->address) ? 0x10 : md_ip_linklocal(a->address) ? 0x20 : 0;
        fprintf(out," %02x %02x %02x %02x %8s\n",n->links[i].info.index,a->prefix,scope,a->flags,n->links[i].info.name);
    }
    pthread_mutex_unlock(&n->fabric->lock);
    if (fclose(out)) { int error=-errno; free(text); return error; }
    free(f->text); f->text=text; f->size=size; return 0;
}
static int snapshot(struct network_proc *f,const struct md_identity *identity) {
    if (f->table==MD_NET_TABLE_IF_INET6) return interface_snapshot(f);
    struct md_network_endpoint_info *items=NULL;
    size_t count=0;
    int protocol=f->table>=MD_NET_TABLE_UDP ? IPPROTO_UDP : IPPROTO_TCP;
    int error=md_network_endpoint_snapshot(f->network,protocol,&items,&count);
    if (error) return error;
    char *text=NULL; size_t size=0;
    FILE *out=open_memstream(&text,&size);
    if (!out) { free(items); return -errno; }
    int ipv6=f->table==MD_NET_TABLE_TCP6 || f->table==MD_NET_TABLE_UDP6;
    const char *names[]={"tcp","tcp6","udp","udp6"};
    /* The native table supplies state/queues, never addresses or ownership.
     * Only live endpoints in this exact logical namespace are published.
     * Snapshot first, then read proc without holding the fabric lock. */
    fputs(ipv6
        ? "  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
        : "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n",out);
    if (count) {
        char path[64]; snprintf(path,sizeof(path),"/proc/self/net/%s",names[f->table]);
        FILE *in=fopen(path,"re");
        if (!in) error=-errno;
        else {
            char *line=NULL; size_t capacity=0; unsigned index=0;
            while (getline(&line,&capacity,in)>=0) {
                unsigned sl,state,tx,rx,timer,when,retry,uid,timeout;
                unsigned long long inode; int end=0;
                if (sscanf(line," %u: %*s %*s %x %x:%x %x:%x %x %u %u %llu%n",
                        &sl,&state,&tx,&rx,&timer,&when,&retry,&uid,&timeout,&inode,&end)!=10)
                    continue;
                struct md_network_endpoint_info key={.inode=inode};
                const struct md_network_endpoint_info *e=bsearch(&key,items,count,sizeof(*items),inode_order);
                if (!e) continue;
                fprintf(out,"%4u: ",index++); socket_address(out,&e->local,ipv6);
                fputc(' ',out); socket_address(out,&e->peer,ipv6);
                fprintf(out," %02X %08X:%08X %02X:%08X %08X %5u %8u %llu%s",state,tx,rx,timer,when,retry,
                    md_identity_visible_id(identity,0,e->uid),timeout,inode,line+end);
            }
            if (ferror(in)) error=-EIO;
            free(line); fclose(in);
        }
    }
    free(items);
    if (fclose(out) && !error) error=-errno;
    if (error) { free(text); return error; }
    free(f->text); f->text=text; f->size=size; return 0;
}
static long read_value(void *context,const struct md_control_actor *actor,void *out,size_t size,int64_t offset) {
    struct network_proc *f=context;
    if (offset<0) return -EINVAL;
    if (!size) return 0;
    if (!offset || !f->text) {
        int error=snapshot(f,actor->identity);
        if (error) return error;
    }
    if ((uint64_t)offset>=f->size) return 0;
    size_t n=f->size-(size_t)offset;
    if (n>size) n=size;
    memcpy(out,f->text+offset,n); return (long)n;
}
static void *open_file(void *context,const struct md_control_opener *opener,int flags) {
    (void)opener; (void)flags;
    struct network_proc *f=context;
    return md_network_proc_new(f->network,f->table).context;
}
static const struct md_control_file_ops ops={.retain=retain,.release=release,.stat=metadata,
    .access=file_access,.read=read_value,.open=open_file,.filesystem=PROC_SUPER_MAGIC};
struct md_control_file md_network_proc_new(struct md_network_namespace *network,enum md_network_table table) {
    if (!md_network_namespace_managed(network)) { errno=EXDEV; return (struct md_control_file){0}; }
    if ((unsigned)table>MD_NET_TABLE_IF_INET6) { errno=EINVAL; return (struct md_control_file){0}; }
    struct network_proc *f=calloc(1,sizeof(*f));
    if (!f) return (struct md_control_file){0};
    atomic_init(&f->references,1);
    f->network=md_network_namespace_retain(network); f->table=table;
    return (struct md_control_file){&ops,f};
}
