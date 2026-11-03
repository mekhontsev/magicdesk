#include "network_control_queue.h"
#include "network_forward.h"
#include "network_packet.h"
#include <errno.h>
#include <linux/netlink.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/socket.h>

enum { REPLY_PACKET=8192, REPLY_BUDGET=16, QUEUE_BYTES=4*1024*1024 };
struct md_control_reply {
    struct md_control_reply *next;
    size_t size, sent;
    unsigned char data[];
};
static int send_reply(int peer,const void *data,size_t size) {
    struct md_network_packet header={0};
    struct iovec vectors[]={{&header,sizeof(header)},{(void *)data,size}};
    struct msghdr message={.msg_iov=vectors,.msg_iovlen=2};
    return sendmsg(peer,&message,MSG_DONTWAIT|MSG_NOSIGNAL)<0 ? -errno : 0;
}
void md_network_control_clear(struct md_network_control_queue *q) {
    md_network_event_remove(q->event); q->event=NULL;
    while (q->head) {
        struct md_control_reply *p=q->head; q->head=p->next; free(p);
    }
    q->tail=NULL; q->bytes=0;
}
static void ready(void *context,uint32_t events) {
    struct md_network_control_queue *q=context;
    (void)events;
    for (unsigned i=0;q->head && i<REPLY_BUDGET;++i) {
        struct md_control_reply *p=q->head;
        size_t bytes=0;
        while (p->sent+bytes<p->size) {
            const struct nlmsghdr *h=(const void *)(p->data+p->sent+bytes);
            size_t next=NLMSG_ALIGN(h->nlmsg_len);
            if (bytes && bytes+next>REPLY_PACKET) break;
            bytes+=next;
        }
        int error=send_reply(q->peer,p->data+p->sent,bytes);
        if (error==-EAGAIN || error==-EWOULDBLOCK) return;
        if (error) { q->error=error; md_network_control_clear(q); return; }
        p->sent+=bytes;
        if (p->sent==p->size) {
            q->head=p->next; q->bytes-=p->size; free(p);
            if (!q->head) q->tail=NULL;
        }
    }
    if (!q->head) { md_network_event_remove(q->event); q->event=NULL; }
}
int md_network_control_publish(struct md_network_control_queue *q,struct md_network_fabric *fabric,
        int peer,const void *data,size_t size) {
    if (q->error) return q->error;
    if (!size) return 0;
    if (size>QUEUE_BYTES-q->bytes) return -ENOBUFS;
    long remaining=(long)size;
    const struct nlmsghdr *h=data;
    for (;NLMSG_OK(h,remaining);h=NLMSG_NEXT(h,remaining))
        if (NLMSG_ALIGN(h->nlmsg_len)>MD_NETWORK_PACKET_MAX) return -EMSGSIZE;
    if (remaining) return -EINVAL;
    if (!q->head && size<=REPLY_PACKET) {
        int error=send_reply(peer,data,size);
        if (error!=-EAGAIN && error!=-EWOULDBLOCK) return error;
    }
    struct md_control_reply *p=malloc(sizeof(*p)+size);
    if (!p) return -ENOMEM;
    *p=(struct md_control_reply){.size=size}; memcpy(p->data,data,size);
    if (!q->event) {
        int error=md_network_event_add(fabric,peer,EPOLLOUT,ready,q,&q->event);
        if (error) { free(p); return error; }
    }
    q->peer=peer;
    if (q->tail) q->tail->next=p; else q->head=p;
    q->tail=p; q->bytes+=size;
    /* Admission reserves the complete reply before mutation commits. The
     * existing event loop drains it without blocking the submitting task. */
    return 0;
}
