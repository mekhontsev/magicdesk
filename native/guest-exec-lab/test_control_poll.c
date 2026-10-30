#define _GNU_SOURCE
#include "proc_file.h"
#include "proc_sysctl.h"
#include "task_resources.h"
#include "uts_namespace.h"
#include "control_epoll.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static unsigned descriptors(void) {
    DIR *dir=opendir("/proc/self/fd"); assert(dir);
    unsigned count=0;
    for (struct dirent *entry;(entry=readdir(dir));) if (entry->d_name[0]!='.') ++count;
    closedir(dir); return count;
}
static struct md_proc_file *open_sysctl(struct md_uts_namespace *uts,
        const struct md_identity *id,enum md_proc_file_kind kind) {
    struct md_control_file provider=md_proc_sysctl_new(kind,NULL,uts); assert(provider.ops);
    int flags=kind<=MD_PROC_FILE_DOMAINNAME ? O_RDWR : O_RDONLY;
    struct md_proc_file *file=md_proc_file_open_control(provider.ops,provider.context,&(struct md_control_opener){.identity=id},flags|O_CLOEXEC);
    md_control_file_release(provider); assert(file); return file;
}
static void mask(struct md_control_file f,int changed) {
    assert(f.ops->poll(f.context)==(changed ? POLLIN|POLLRDNORM|POLLERR|POLLPRI
        : POLLIN|POLLRDNORM|POLLOUT|POLLWRNORM));
}
static void ready(int fd,int expected) {
    struct pollfd p={fd,POLLIN,0};
    assert(poll(&p,1,0)==expected && p.revents==(expected ? POLLIN : 0));
}
static int interest(struct md_control_epoll *q,int fd,struct md_proc_file *f,
        uint32_t events,uint64_t data) {
    struct epoll_event e={.events=events,.data.u64=data};
    return md_control_epoll_ctl(q,EPOLL_CTL_ADD,fd,md_proc_file_object(f),md_proc_file_control(f),&e);
}
static void epoll_sysctl(void) {
    unsigned before=descriptors();
    struct md_identity id=md_identity_new(0,0);
    struct md_uts_namespace *uts=md_uts_namespace_new(); assert(uts);
    struct md_proc_file *a=open_sysctl(uts,&id,MD_PROC_FILE_HOSTNAME);
    struct md_proc_file *b=md_proc_file_reopen(a,&(struct md_control_opener){.identity=&id},O_RDONLY); assert(b);
    struct md_proc_file *constant=open_sysctl(uts,&id,MD_PROC_FILE_OSTYPE);
    struct md_control_epoll *q=md_control_epoll_new(); assert(q);
    struct epoll_event events[4];
    assert(!interest(q,10,a,0,100));
    assert(!interest(q,11,b,EPOLLPRI,101));
    assert(!md_control_epoll_collect(q,events,4));
    assert(!md_uts_namespace_set(uts,0,"epoll-test",10));
    char text[64]={0};
    assert(md_proc_file_read_at(a,uts,NULL,text,sizeof(text),0)==11);
    assert(!strcmp(text,"epoll-test\n"));
    assert(md_control_epoll_collect(q,events,4)==2);
    for (int i=0;i<2;++i) assert((events[i].data.u64==100 && events[i].events==EPOLLERR)
        || (events[i].data.u64==101 && events[i].events==(EPOLLERR|EPOLLPRI)));
    assert(!md_control_epoll_collect(q,events,4));
    /* Constant sysctls have poll semantics without a notification transport. */
    assert(!interest(q,12,constant,EPOLLIN|EPOLLET,102));
    assert(md_control_epoll_collect(q,events,4)==1 && events[0].data.u64==102
        && events[0].events==EPOLLIN);
    assert(!md_control_epoll_collect(q,events,4));
    md_control_epoll_release(q);
    md_proc_file_release(a); md_proc_file_release(b); md_proc_file_release(constant);
    md_uts_namespace_release(uts); md_identity_release(&id);
    assert(descriptors()==before);
    puts("PASS typed sysctl epoll: ERR-only/PRI interests, read independence and constant providers");
}
struct capture {
    struct md_proc_file *file;
    pthread_barrier_t *gate;
    int fd;
};
static void *capture_carrier(void *opaque) {
    struct capture *capture=opaque;
    /* EVENT_WAIT: concurrent cold captures start together; fixture deadline
     * fails the process if a participant does not arrive. */
    pthread_barrier_wait(capture->gate);
    capture->fd=md_proc_file_descriptor(capture->file); return NULL;
}
static void carriers(void) {
    unsigned before=descriptors();
    struct md_identity id=md_identity_new(0,0);
    struct md_uts_namespace *uts=md_uts_namespace_new(); assert(uts);
    struct md_proc_file *a=open_sysctl(uts,&id,MD_PROC_FILE_HOSTNAME);
    struct md_proc_file *b=md_proc_file_reopen(a,&(struct md_control_opener){.identity=&id},O_RDONLY); assert(b);
    assert(descriptors()==before);
    pthread_barrier_t gate; assert(!pthread_barrier_init(&gate,NULL,6));
    struct capture captures[6]; pthread_t threads[6];
    for (unsigned i=0;i<6;++i) {
        captures[i]=(struct capture){a,&gate,-1};
        assert(!pthread_create(&threads[i],NULL,capture_carrier,&captures[i]));
    }
    for (unsigned i=0;i<6;++i) assert(!pthread_join(threads[i],NULL));
    pthread_barrier_destroy(&gate);
    int af=md_proc_file_descriptor(a),bf=md_proc_file_descriptor(b),dup=md_proc_file_descriptor(a);
    assert(af>=0 && bf>=0 && dup>=0);
    struct stat as,bs,ds;
    assert(!fstat(af,&as) && !fstat(bf,&bs) && !fstat(dup,&ds));
    assert(as.st_ino==ds.st_ino && as.st_dev==ds.st_dev && as.st_ino!=bs.st_ino);
    assert(fcntl(af,F_GETFD)&FD_CLOEXEC);
    assert(fcntl(af,F_GET_SEALS)==(F_SEAL_SEAL|F_SEAL_GROW|F_SEAL_SHRINK|F_SEAL_WRITE));
    for (unsigned i=0;i<6;++i) {
        struct stat st; assert(captures[i].fd>=0 && !fstat(captures[i].fd,&st));
        assert(st.st_dev==as.st_dev && st.st_ino==as.st_ino); close(captures[i].fd);
    }
    close(af); close(bf); close(dup);
    md_proc_file_release(a); md_proc_file_release(b);
    md_uts_namespace_release(uts); md_identity_release(&id);
    assert(descriptors()==before);
    puts("PASS lazy sysctl carriers: no prototype FD, independent opens and shared captures");
}
void test_control_poll(void) {
    carriers(); epoll_sysctl();
    unsigned before=descriptors();
    struct md_identity id=md_identity_new(0,0);
    struct md_uts_namespace *uts=md_uts_namespace_new(); assert(uts);
    struct md_proc_file *a=open_sysctl(uts,&id,MD_PROC_FILE_HOSTNAME);
    struct md_proc_file *b=md_proc_file_reopen(a,&(struct md_control_opener){.identity=&id},O_RDONLY); assert(b);
    struct md_proc_file *dup=md_proc_file_retain(a);
    struct md_proc_file *domain=open_sysctl(uts,&id,MD_PROC_FILE_DOMAINNAME);
    struct md_control_file ac=md_proc_file_control(a),bc=md_proc_file_control(b);
    struct md_control_file dc=md_proc_file_control(domain);
    unsigned unwatched=descriptors();
    mask(ac,0); mask(bc,0); mask(dc,0);
    assert(descriptors()==unwatched);
    assert(!md_uts_namespace_set(uts,0,"change-one",10));
    int wake=-1; assert(!ac.ops->poll_descriptor(ac.context,&wake) && wake>=0);
    ready(wake,1);
    char text[64]={0};
    assert(md_proc_file_read_at(a,uts,NULL,text,sizeof(text),0)==11);
    assert(!strcmp(text,"change-one\n"));
    ready(wake,1);
    mask(ac,1); ready(wake,0);
    mask(md_proc_file_control(dup),0); mask(bc,1); mask(dc,0);
    struct md_proc_file *late=md_proc_file_reopen(a,&(struct md_control_opener){.identity=&id},O_RDONLY); assert(late);
    mask(md_proc_file_control(late),0);
    struct md_uts_namespace *child=md_uts_namespace_copy(uts,NULL); assert(child);
    assert(!md_uts_namespace_set(child,0,"child-value",11));
    mask(ac,1); mask(bc,1);
    memset(text,0,sizeof(text));
    assert(md_proc_file_read_at(a,uts,NULL,text,sizeof(text),0)==11);
    assert(!strcmp(text,"change-one\n"));
    memset(text,0,sizeof(text));
    assert(md_proc_file_read_at(a,child,NULL,text,sizeof(text),0)==12);
    assert(!strcmp(text,"child-value\n"));
    assert(!md_uts_namespace_set(uts,1,"domain",6));
    mask(ac,0); mask(dc,1);
    /* No name change is required: Linux also notifies an identical write. */
    assert(!md_uts_namespace_set(uts,0,"change-one",10));
    mask(ac,1);
    md_uts_namespace_release(child); md_uts_namespace_release(uts);
    mask(ac,0);
    md_proc_file_release(a); md_proc_file_release(dup); md_proc_file_release(b);
    md_proc_file_release(late); md_proc_file_release(domain);
    md_identity_release(&id);
    assert(descriptors()==before);
    puts("PASS typed sysctl notifications: read independence, reopen/dup, UTS copies, masks and FD custody");
}
