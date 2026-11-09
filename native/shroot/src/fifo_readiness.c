#define _GNU_SOURCE
#include "fifo_readiness.h"
#include "control_subscription.h"
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <sys/epoll.h>
#include <sys/mman.h>
#include <unistd.h>

struct md_fifo_readiness {
    int control,query,wake,active;
    uint32_t writers;
    const uint32_t *generations;
};
struct md_fifo_readiness *md_fifo_readiness_new(int control,uint32_t writers) {
    struct md_fifo_readiness *f=calloc(1,sizeof(*f));
    if (!f) return NULL;
    *f=(struct md_fifo_readiness){.control=-1,.query=-1,.wake=-1,.writers=writers};
    struct stat st;
    int error=fstat(control,&st) ? errno : !S_ISREG(st.st_mode) || st.st_size!=8 ? EINVAL : 0;
    if (!error && (f->control=fcntl(control,F_DUPFD_CLOEXEC,0))<0) error=errno;
    if (!error) {
        void *mapped=mmap(NULL,8,PROT_READ,MAP_SHARED,f->control,0);
        if (mapped==MAP_FAILED) error=errno; else f->generations=mapped;
    }
    if (error) { md_fifo_readiness_free(f); errno=error; return NULL; }
    return f;
}
void md_fifo_readiness_free(struct md_fifo_readiness *f) {
    if (!f) return;
    if (f->wake>=0) close(f->wake);
    if (f->query>=0) close(f->query);
    if (f->control>=0) close(f->control);
    if (f->generations) munmap((void *)f->generations,8);
    free(f);
}
int md_fifo_readiness_attach(struct md_fifo_readiness *f,int endpoint) {
    if (!f || endpoint<0) return -EINVAL;
    if (f->query>=0) return 0;
    struct stat st;
    if (fstat(endpoint,&st)) return -errno;
    if (!S_ISFIFO(st.st_mode)) return -EINVAL;
    int flags=fcntl(endpoint,F_GETFL);
    if (flags<0) return -errno;
    if ((flags&O_PATH) || (flags&O_ACCMODE)!=O_RDONLY) return -EINVAL;
    int query=epoll_create1(EPOLL_CLOEXEC),wake=-1,error=query<0 ? -errno : 0;
    if (!error && (wake=epoll_create1(EPOLL_CLOEXEC))<0) error=-errno;
    struct epoll_event event={.events=EPOLLIN|EPOLLRDNORM};
    if (!error && epoll_ctl(query,EPOLL_CTL_ADD,endpoint,&event)) error=-errno;
    event.events|=EPOLLET;
    if (!error && epoll_ctl(wake,EPOLL_CTL_ADD,endpoint,&event)) error=-errno;
    if (error) { if (query>=0) close(query); if (wake>=0) close(wake); return error; }
    f->query=query; f->wake=wake; return 0;
}
static int poll_descriptor(void *context,int *descriptor) {
    struct md_fifo_readiness *f=context;
    if (f->wake<0) return -EBADF;
    *descriptor=f->wake; return 0;
}
static long poll_value(void *context) {
    struct md_fifo_readiness *f=context;
    if (f->query<0) return -EBADF;
    struct epoll_event events[2];
    int count=epoll_wait(f->wake,events,2,0);
    if (count<0) return -errno;
    count=epoll_wait(f->query,events,1,0);
    if (count<0) return -errno;
    unsigned mask=count ? events[0].events : 0;
    if (!f->active) {
        if (__atomic_load_n(&f->generations[1],__ATOMIC_ACQUIRE)==f->writers)
            return mask&~EPOLLHUP;
        /* The first writer permanently ends initial-HUP suppression. Add a
         * level source without keeping the application's endpoint alive. */
        struct epoll_event event={.events=EPOLLIN,.data.u64=1};
        if (epoll_ctl(f->wake,EPOLL_CTL_ADD,f->query,&event)) return -errno;
        f->active=1;
    }
    return mask;
}
static int poll_native(void *context) { return ((struct md_fifo_readiness *)context)->active; }
static int subscribe(void *context,int native,uint32_t events,struct md_control_file *out) {
    return md_control_subscription(md_fifo_readiness_control(context),native,-1,events,out);
}
static const struct md_control_file_ops operations={.poll_descriptor=poll_descriptor,.poll=poll_value,
    .poll_native=poll_native,.poll_subscribe=subscribe};
struct md_control_file md_fifo_readiness_control(struct md_fifo_readiness *f) {
    return f ? (struct md_control_file){&operations,f} : (struct md_control_file){0};
}
int md_fifo_readiness_state(const struct md_fifo_readiness *f,uint32_t *writers) {
    if (!f || !writers) return -EINVAL;
    *writers=f->writers; return f->control;
}
