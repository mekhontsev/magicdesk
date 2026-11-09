#define _GNU_SOURCE
#include "control_subscription.h"
#include <errno.h>
#include <stdlib.h>
#include <sys/epoll.h>
#include <unistd.h>

struct subscription { struct md_control_file provider; int events; };
static void release(void *context) {
    struct subscription *s=context;
    close(s->events); free(s);
}
static int descriptor(void *context,int *out) {
    *out=((struct subscription *)context)->events; return 0;
}
static long collect(void *context) {
    struct subscription *s=context;
    struct epoll_event events[2];
    /* Drain this observer only. The shared provider revalidates readiness and
     * may suppress a native carrier while its logical connect is pending. */
    if (epoll_wait(s->events,events,2,0)<0) return -errno;
    return s->provider.ops->poll(s->provider.context);
}
static const struct md_control_file_ops operations={
    .release=release,.poll_descriptor=descriptor,.poll=collect};
int md_control_subscription(struct md_control_file provider,int native,int gate,
        uint32_t events,struct md_control_file *out) {
    if (native<0 || !provider.ops || !provider.ops->poll || !out) return -EINVAL;
    struct subscription *s=calloc(1,sizeof(*s));
    if (!s) return -ENOMEM;
    s->provider=provider; s->events=epoll_create1(EPOLL_CLOEXEC);
    int error=s->events<0 ? -errno : 0;
    /* The application policy belongs on the actual source, not an epoll FD.
     * Internal notification is edge-triggered; the outer owner retains levels. */
    struct epoll_event event={.events=events|EPOLLET};
    if (!error && epoll_ctl(s->events,EPOLL_CTL_ADD,native,&event)) error=-errno;
    event.events=EPOLLIN|EPOLLET;
    if (!error && gate>=0 && epoll_ctl(s->events,EPOLL_CTL_ADD,gate,&event)) error=-errno;
    if (error) { if (s->events>=0) close(s->events); free(s); return error; }
    *out=(struct md_control_file){&operations,s}; return 0;
}
