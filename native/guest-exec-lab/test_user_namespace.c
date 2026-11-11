#define _GNU_SOURCE
#include "user_namespace.h"
#include "user_sysctl.h"
#include "proc_file.h"
#include "guest_identity.h"
#include "identity_snapshot.h"
#include "identity_view.h"
#include "sealed_manifest.h"
#include "uts_namespace.h"
#include "ipc_permissions.h"
#include "fd_messages.h"
#include "fd_filter.h"
#include "task_registry.h"
#include "task_resources.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/capability.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <unistd.h>

static int publish(struct md_user_namespace *target, int group, struct md_user_namespace *ns,
        uint32_t uid, uint32_t gid, uint64_t caps, const struct md_id_extent *ranges, unsigned count) {
    struct md_user_authority actor={ns,uid,gid,caps};
    return md_user_namespace_map(target,group,&actor,&actor,ranges,count);
}
static unsigned descriptors(void) {
    DIR *d=opendir("/proc/self/fd"); assert(d);
    unsigned count=0;
    struct dirent *entry;
    while ((entry=readdir(d))) if (entry->d_name[0]!='.') ++count;
    closedir(d); return count;
}
struct limit_race { pthread_barrier_t *barrier; struct md_user_namespace *space; int error; };
static void *limit_thread(void *data) {
    struct limit_race *r=data;
    /* EVENT_WAIT: all creators contend for one quota, then retain their charge
     * until the main thread has checked admission. The test alarm bounds it. */
    pthread_barrier_wait(r->barrier);
    r->space=md_user_namespace_new(NULL,12345,12345,0); r->error=errno;
    pthread_barrier_wait(r->barrier);
    pthread_barrier_wait(r->barrier);
    md_user_namespace_release(r->space); return NULL;
}
static void namespace_limits(void) {
    unsigned before=descriptors();
    assert(!md_user_namespace_set_limit(NULL,MD_USER_NAMESPACES,2));
    struct md_user_namespace *a=md_user_namespace_new(NULL,0,0,1); assert(a);
    struct md_id_extent map={0,0,10000};
    assert(!publish(a,0,NULL,0,0,UINT64_MAX,&map,1));
    assert(!publish(a,1,NULL,0,0,UINT64_MAX,&map,1));
    assert(!md_user_namespace_set_limit(a,MD_USER_NAMESPACES,1));
    struct md_user_namespace *b=md_user_namespace_new(a,1000,1000,1); assert(b);
    assert(md_user_namespace_limit(b,MD_USER_NAMESPACES)==INT_MAX);
    assert(!md_user_namespace_new(a,1000,1000,1) && errno==ENOSPC);
    /* Another UID still charges the same creator of the ancestor. */
    assert(!md_user_namespace_new(a,2000,2000,1) && errno==ENOSPC);
    assert(!md_user_namespace_set_limit(NULL,MD_USER_NAMESPACES,3));
    struct md_user_namespace *c=md_user_namespace_new(a,2000,2000,1); assert(c);
    md_user_namespace_release(c);
    int fds[2]; assert(md_user_namespace_export(b,fds,2)==2);
    struct md_user_namespace *copy=NULL;
    assert(!md_user_namespace_import(fds,2,&copy)); close(fds[0]); close(fds[1]);
    assert(!md_user_namespace_set_limit(b,MD_USER_NAMESPACES,7) && md_user_namespace_limit(copy,MD_USER_NAMESPACES)==7);
    assert(md_user_namespace_set_limit(copy,MD_USER_NAMESPACES,0)==-EPERM);
    md_user_namespace_release(copy);
    struct md_control_file control=md_user_sysctl_new(b,NULL); assert(control.ops);
    md_user_namespace_release(b);
    assert(!md_user_namespace_new(a,1000,1000,1) && errno==ENOSPC);
    md_control_file_release(control);
    b=md_user_namespace_new(a,1000,1000,1); assert(b);
    md_user_namespace_release(b); md_user_namespace_release(a);
    assert(md_user_namespace_set_limit(NULL,MD_USER_NAMESPACES,UINT32_MAX)==-EINVAL);
    assert(!md_user_namespace_set_limit(NULL,MD_USER_NAMESPACES,4));
    for (unsigned round=0;round<4;++round) {
        pthread_t threads[8]; pthread_barrier_t barrier;
        assert(!pthread_barrier_init(&barrier,NULL,9));
        struct limit_race race[8]={0};
        for (unsigned i=0;i<8;++i) {
            race[i].barrier=&barrier;
            assert(!pthread_create(threads+i,NULL,limit_thread,race+i));
        }
        pthread_barrier_wait(&barrier); pthread_barrier_wait(&barrier);
        unsigned accepted=0;
        for (unsigned i=0;i<8;++i) {
            if (race[i].space) ++accepted; else assert(race[i].error==ENOSPC);
        }
        assert(accepted==4);
        pthread_barrier_wait(&barrier);
        for (unsigned i=0;i<8;++i) assert(!pthread_join(threads[i],NULL));
        assert(!pthread_barrier_destroy(&barrier));
    }
    assert(!md_user_namespace_set_limit(NULL,MD_USER_NAMESPACES,INT_MAX));
    assert(descriptors()==before);
}
static void resource_limits(void) {
    unsigned before=descriptors();
    struct md_user_namespace *space=md_user_namespace_new(NULL,1000,1000,0); assert(space);
    assert(!md_user_namespace_set_limit(NULL,MD_USER_INOTIFY_INSTANCES,2));
    assert(!md_user_namespace_set_limit(NULL,MD_USER_INOTIFY_WATCHES,1));
    struct md_user_charge *a=NULL,*b=NULL,*c=NULL;
    assert(!md_user_charge_acquire(space,1000,MD_USER_INOTIFY_INSTANCES,&a));
    assert(!md_user_charge_acquire(space,2000,MD_USER_INOTIFY_INSTANCES,&b));
    /* A different UID in the child still charges the parent's creator. */
    assert(md_user_charge_acquire(space,3000,MD_USER_INOTIFY_INSTANCES,&c)==-ENOSPC && !c);
    assert(!md_user_charge_add(a,MD_USER_INOTIFY_WATCHES));
    assert(md_user_charge_add(b,MD_USER_INOTIFY_WATCHES)==-ENOSPC);
    md_user_charge_release(a,MD_USER_INOTIFY_WATCHES);
    assert(!md_user_charge_add(b,MD_USER_INOTIFY_WATCHES));
    assert(!md_user_namespace_set_limit(space,MD_USER_INOTIFY_INSTANCES,0));
    assert(md_user_charge_add(a,MD_USER_INOTIFY_INSTANCES)==-ENOSPC);
    md_user_namespace_release(space);
    md_user_charge_release(a,MD_USER_INOTIFY_INSTANCES);
    md_user_charge_release(b,MD_USER_INOTIFY_INSTANCES);
    /* A retained watch keeps its creator chain valid after the instance charge. */
    assert(md_user_charge_acquire(NULL,1000,MD_USER_INOTIFY_WATCHES,&c)==-ENOSPC);
    md_user_charge_release(b,MD_USER_INOTIFY_WATCHES);
    assert(!md_user_charge_acquire(NULL,1000,MD_USER_INOTIFY_WATCHES,&c));
    md_user_charge_release(c,MD_USER_INOTIFY_WATCHES);
    assert(!md_user_namespace_set_limit(NULL,MD_USER_INOTIFY_INSTANCES,MD_USER_DEFAULT_INOTIFY_INSTANCES));
    assert(!md_user_namespace_set_limit(NULL,MD_USER_INOTIFY_WATCHES,MD_USER_DEFAULT_INOTIFY_WATCHES));
    assert(descriptors()==before);
}
static void credential_snapshot(void) {
    unsigned before=descriptors();
    struct md_identity id=md_identity_new(2000,2000),copy={0};
    const uint32_t groups[]={3000,2000,1000};
    assert(!md_identity_groups(&id,groups,3));
    assert(!md_identity_enter_user_namespace(&id));
    int fd=md_identity_snapshot_export(&id); assert(fd>=0);
    assert(md_identity_snapshot_import(fd,NULL,&copy)==-EPROTO && !copy.user_namespace && !copy.groups);
    assert(!md_identity_snapshot_import(fd,id.user_namespace,&copy));
    assert(copy.uid.fs==2000 && md_identity_group_count(&copy)==3 && md_identity_in_group(&copy,3000));
    assert(copy.caps.effective==id.caps.effective && md_user_namespace_same(copy.user_namespace,id.user_namespace));
    assert(pwrite(fd,"x",1,0)<0 && errno==EPERM);
    unsigned char data[128]; struct md_fd_identity token; size_t size=0;
    assert(!md_sealed_manifest_read(fd,data,sizeof(data),&size,&token) && size==108);
    close(fd);
    for (unsigned test=0;test<6;++test) {
        unsigned char bad[128]; memcpy(bad,data,size);
        size_t length=size;
        if (test==0) bad[0]=99;
        if (test==1) bad[4]=255;
        if (test==2) bad[92]=2;
        if (test==3) bad[88]=255,bad[89]=255;
        if (test==4) memset(bad+16,255,4);
        if (test==5) length=8;
        int broken=md_sealed_manifest_create("bad-credentials",bad,length,&token); assert(broken>=0);
        struct md_identity rejected={0};
        assert(md_identity_snapshot_import(broken,id.user_namespace,&rejected)==-EPROTO);
        assert(!rejected.groups && !rejected.user_namespace); close(broken);
    }
    md_identity_release(&id);
    assert(md_identity_capable(&copy,CAP_SYS_ADMIN) && !md_identity_capable_in(&copy,NULL,CAP_SYS_ADMIN));
    md_identity_release(&copy);
    assert(descriptors()==before);
}
static void custody(void) {
    unsigned before=descriptors();
    const uint64_t caps=UINT64_MAX;
    for (unsigned iteration=0;iteration<16;++iteration) {
        struct md_user_namespace *outer=md_user_namespace_new(NULL,0,0,1); assert(outer);
        struct md_id_extent map={0,0,10000};
        assert(!publish(outer,0,NULL,0,0,caps,&map,1));
        assert(!publish(outer,1,NULL,0,0,caps,&map,1));
        struct md_user_namespace *inner=md_user_namespace_new(outer,1000,1000,1); assert(inner);
        int fds[MD_USER_NAMESPACE_DEPTH];
        assert(md_user_namespace_export(inner,fds,1)==-ERANGE);
        assert(md_user_namespace_export(inner,fds,MD_USER_NAMESPACE_DEPTH)==2);
        struct md_user_namespace *copy=NULL;
        int reversed[]={fds[1],fds[0]};
        assert(md_user_namespace_import(reversed,2,&copy)==-EPROTO && !copy);
        assert(!md_user_namespace_import(fds,2,&copy));
        for (unsigned i=0;i<2;++i) {
            char byte=0;
            assert(pwrite(fds[i],&byte,1,0)<0 && errno==EPERM);
            assert(mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED,fds[i],0)==MAP_FAILED);
            close(fds[i]);
        }
        assert(md_user_namespace_contains(inner,copy) && md_user_namespace_contains(copy,inner));
        assert(md_user_namespace_contains(outer,copy));
        assert(md_user_namespace_capable(inner,1000,caps,copy,CAP_DAC_OVERRIDE));
        assert(!md_user_namespace_capable(copy,1000,caps,outer,CAP_DAC_OVERRIDE));
        assert(md_user_namespace_into(copy,0,0)==UINT32_MAX);
        assert(!md_user_namespace_setgroups(inner,0));
        assert(!md_user_namespace_groups_allowed(copy));
        map=(struct md_id_extent){0,1000,10};
        assert(!publish(inner,0,outer,0,0,caps,&map,1));
        assert(!publish(inner,1,outer,0,0,caps,&map,1));
        assert(md_user_namespace_into(copy,0,5)==1005);
        assert(md_user_namespace_from(copy,1,1005)==5);
        assert(publish(copy,0,outer,0,0,caps,&map,1)==-EPERM);
        md_user_namespace_release(inner); md_user_namespace_release(outer);
        assert(md_user_namespace_into(copy,0,9)==1009);
        md_user_namespace_release(copy);
        assert(descriptors()==before);
    }
}
static void selectors(void) {
    struct md_fd_selector *a=md_fd_selector_new(); assert(a);
    assert(md_fd_selector_mark(a,MD_FD_SELECTOR_PROC,7)==-EINVAL);
    assert(!md_fd_selector_has(a,MD_FD_SELECTOR_WATCH,7));
    assert(!md_fd_selector_mark(a,MD_FD_SELECTOR_WATCH,7));
    assert(md_fd_selector_has(a,MD_FD_SELECTOR_WATCH,7)==1);
    assert(!md_fd_selector_has(a,MD_FD_SELECTOR_PROC,7));
    struct md_fd_selector *copy=md_fd_selector_fork(a,0), *shared=md_fd_selector_fork(a,1);
    assert(copy && shared==a);
    assert(!md_fd_selector_mark(shared,MD_FD_SELECTOR_PROC,7));
    assert(md_fd_selector_has(a,MD_FD_SELECTOR_PROC,7+MD_FD_SELECTOR_MASK+1)==1);
    assert(!md_fd_selector_has(copy,MD_FD_SELECTOR_PROC,7));
    assert(md_fd_selector_has(copy,MD_FD_SELECTOR_WATCH,7)==1);
    assert(md_fd_selector_has(a,MD_FD_SELECTOR_KINDS,7)==-EINVAL);
    assert(md_fd_selector_has(a,MD_FD_SELECTOR_PROC,-2)==-EINVAL);
    assert(!md_fd_selector_has(a,MD_FD_SELECTOR_PROC,-1));
    assert(!md_fd_selector_mark(a,MD_FD_SELECTOR_PROC,-1));
    assert(md_fd_selector_has(shared,MD_FD_SELECTOR_PROC,12345)==1);
    assert(md_fd_selector_has(shared,MD_FD_SELECTOR_WATCH,-1)==1);
    assert(!md_fd_selector_has(copy,MD_FD_SELECTOR_PROC,-1));
    struct md_fd_selector *prepared=md_fd_selector_fork(a,0); assert(prepared);
    assert(md_fd_selector_has(prepared,MD_FD_SELECTOR_PROC,-1)==1);
    assert(md_fd_selector_has(prepared,MD_FD_SELECTOR_WATCH,98765)==1);
    assert(!md_fd_selector_has_mask(prepared,MD_FD_SANDBOX_SELECTORS,-1));
    assert(!md_fd_selector_mark(prepared,MD_FD_SELECTOR_EPOLL_TARGET,-1));
    assert(!md_fd_selector_mark(prepared,MD_FD_SELECTOR_EPOLL,-1));
    assert(!md_fd_selector_has_mask(prepared,MD_FD_SANDBOX_SELECTORS,-1));
    assert(!md_fd_selector_mark(prepared,MD_FD_SELECTOR_POLL,-1));
    assert(md_fd_selector_has_mask(prepared,MD_FD_SANDBOX_SELECTORS,-1)==1);
    assert(!md_fd_selector_has_mask(a,MD_FD_SANDBOX_SELECTORS,-1));
    assert(md_fd_selector_has_mask(prepared,1U<<MD_FD_SELECTOR_KINDS,-1)==-EINVAL);
    md_fd_selector_release(prepared);
    md_fd_selector_release(a); md_fd_selector_release(copy); md_fd_selector_release(shared);

    a=md_fd_selector_new(); assert(a);
    const unsigned instructions=252, cost=instructions+4;
    for (unsigned i=0;i<MD_FD_SELECTOR_BUDGET/cost;++i) {
        assert(!md_fd_selector_has(a,MD_FD_SELECTOR_PROC,(int)i));
        assert(md_fd_selector_target(a,(int)i,instructions)==(int)i);
        assert(!md_fd_selector_installed(a,MD_FD_SELECTOR_PROC,(int)i,instructions));
    }
    assert(md_fd_selector_target(a,200,instructions)==-1);
    copy=md_fd_selector_fork(a,0); shared=md_fd_selector_fork(a,1);
    assert(copy && shared);
    assert(md_fd_selector_target(copy,200,instructions)==-1);
    assert(md_fd_selector_target(shared,200,instructions)==-1);
    assert(!md_fd_selector_installed(copy,MD_FD_SELECTOR_PROC,-1,instructions));
    assert(md_fd_selector_has(copy,MD_FD_SELECTOR_PROC,200)==1);
    assert(md_fd_selector_has(copy,MD_FD_SELECTOR_WATCH,200)==1);
    assert(!md_fd_selector_has(a,MD_FD_SELECTOR_PROC,200));
    assert(!md_fd_selector_installed(shared,MD_FD_SELECTOR_WATCH,-1,40));
    assert(md_fd_selector_has(a,MD_FD_SELECTOR_WATCH,200)==1);
    assert(md_fd_selector_target(a,3,0)==-EINVAL);
    assert(md_fd_selector_target(a,3,4097)==-EINVAL);
    md_fd_selector_release(a); md_fd_selector_release(copy); md_fd_selector_release(shared);

    struct md_fd_selector *required=md_fd_selector_new(),*installed=md_fd_selector_new();
    assert(required && installed);
    enum md_fd_selector_kind kind; int number;
    assert(!md_fd_selector_missing(required,installed,&kind,&number));
    const int descriptors[]={0,7,511,519};
    for (unsigned k=0;k<MD_FD_SELECTOR_KINDS;++k)
    for (unsigned i=0;i<sizeof(descriptors)/sizeof(*descriptors);++i) {
        assert(md_fd_selector_has(required,k,descriptors[i])>=0);
        assert(!md_fd_selector_mark(required,k,descriptors[i]));
    }
    unsigned count=0;
    while (md_fd_selector_missing(required,installed,&kind,&number)==1) {
        assert(!md_fd_selector_has(installed,kind,number));
        assert(!md_fd_selector_installed(installed,kind,number,40));
        assert(++count<=MD_FD_SELECTOR_KINDS*3);
    }
    assert(count==13); /* Four numbered kinds, one all-descriptor poll kind. */
    assert(!md_fd_selector_mark(required,MD_FD_SELECTOR_PROC,-1));
    assert(md_fd_selector_missing(required,installed,&kind,&number)==1);
    assert(kind==MD_FD_SELECTOR_WATCH && number==-1);
    assert(!md_fd_selector_installed(installed,MD_FD_SELECTOR_PROC,-1,40));
    assert(!md_fd_selector_missing(required,installed,&kind,&number));
    md_fd_selector_release(required); md_fd_selector_release(installed);
}
static void ordered_io(void) {
    struct md_identity id=md_identity_new(2000,2000);
    assert(!md_identity_enter_user_namespace(&id));
    struct md_proc_file *file=md_proc_file_open(id.user_namespace,MD_PROC_FILE_UID_MAP,&id,O_RDWR);
    assert(file);
    int event=eventfd(0,EFD_NONBLOCK|EFD_CLOEXEC); assert(event>=0);
    struct md_resource_wakeup *wake=NULL; assert(!md_resource_wakeup_create(event,&wake));
    struct md_proc_file_io a={0},b={0},c={0};
    char data[256];
    assert(md_proc_file_io_begin(&a,file,MD_PROC_FILE_WRITE,0,0,0,4096,wake)==1);
    assert(md_proc_file_io_execute(&a,&id,NULL,NULL,0,4096)==-EINVAL);
    assert(!md_proc_file_io_finish(&a,-EINVAL));
    strcpy(data,"0 2000 1\n");
    assert(md_proc_file_io_begin(&a,file,MD_PROC_FILE_WRITE,0,0,0,strlen(data),wake)==1);
    assert(md_proc_file_io_execute(&a,&id,NULL,data,sizeof(data),4096)==(long)strlen(data));
    assert(md_proc_file_io_execute(&a,&id,NULL,data,sizeof(data),4096)==-EINVAL);
    assert(!md_proc_file_io_cancel(&a));
    struct md_id_extent extents[1];
    assert(md_user_namespace_extents(id.user_namespace,0,extents,1)==1);
    assert(md_proc_file_io_begin(&a,file,MD_PROC_FILE_SEEK,0,0,SEEK_CUR,0,wake)==1);
    assert(md_proc_file_io_execute(&a,&id,NULL,NULL,0,4096)==9);
    assert(!md_proc_file_io_finish(&a,0));
    assert(md_proc_file_io_begin(&a,file,MD_PROC_FILE_SEEK,0,0,SEEK_SET,0,wake)==1);
    assert(!md_proc_file_io_execute(&a,&id,NULL,NULL,0,4096));
    assert(!md_proc_file_io_finish(&a,0));
    assert(md_proc_file_io_begin(&a,file,MD_PROC_FILE_READ,0,0,0,33,wake)==1);
    assert(md_proc_file_io_begin(&b,file,MD_PROC_FILE_READ,0,0,0,33,wake)==0);
    assert(md_proc_file_io_begin(&c,file,MD_PROC_FILE_READ,0,0,0,33,wake)==0);
    assert(!md_proc_file_io_cancel(&b));
    assert(md_proc_file_io_execute(&a,&id,NULL,data,sizeof(data),4096)==33);
    assert(!md_proc_file_io_offer(&a));
    assert(md_proc_file_io_finish(&a,34)==-EINVAL);
    assert(!md_proc_file_io_finish(&a,-EFAULT));
    uint64_t signal; assert(read(event,&signal,sizeof(signal))==sizeof(signal) && signal==1);
    assert(md_resource_order_state(&c.turn)==MD_RESOURCE_ORDER_READY);
    assert(md_proc_file_io_execute(&c,&id,NULL,data,sizeof(data),4096)==33);
    assert(!md_proc_file_io_offer(&c));
    assert(!md_proc_file_io_finish(&c,5));
    assert(md_proc_file_io_begin(&a,file,MD_PROC_FILE_READ,1,0,0,33,wake)==1);
    assert(md_proc_file_io_execute(&a,&id,NULL,data,sizeof(data),4096)==33);
    assert(!md_proc_file_io_offer(&a)); assert(!md_proc_file_io_finish(&a,33));
    assert(md_proc_file_io_begin(&a,file,MD_PROC_FILE_SEEK,0,0,SEEK_CUR,0,wake)==1);
    assert(md_proc_file_io_execute(&a,&id,NULL,NULL,0,4096)==5); assert(!md_proc_file_io_finish(&a,0));
    assert(md_proc_file_io_begin(&a,file,MD_PROC_FILE_SEEK,0,INT64_MAX,SEEK_CUR,0,wake)==1);
    assert(md_proc_file_io_execute(&a,&id,NULL,NULL,0,4096)==-EINVAL); assert(!md_proc_file_io_finish(&a,0));
    assert(md_proc_file_io_begin(&a,file,MD_PROC_FILE_READ,0,0,0,33,wake)==1);
    assert(md_proc_file_io_execute(&a,&id,NULL,data,sizeof(data),4096)==28);
    assert(!md_proc_file_io_cancel(&a));
    assert(md_proc_file_io_begin(&a,file,MD_PROC_FILE_READ,0,0,0,33,wake)==1);
    assert(md_proc_file_io_execute(&a,&id,NULL,data,sizeof(data),4096)==28);
    assert(!md_proc_file_io_offer(&a));
    assert(!md_proc_file_io_begin(&b,file,MD_PROC_FILE_READ,0,0,0,33,wake));
    md_identity_release(&id); md_proc_file_release(file);
    assert(!md_proc_file_io_cancel(&a));
    assert(read(event,&signal,sizeof(signal))==sizeof(signal) && signal==1);
    assert(!md_proc_file_io_execute(&b,NULL,NULL,data,sizeof(data),4096));
    assert(!md_proc_file_io_finish(&b,0));
    md_resource_wakeup_release(wake); close(event);
}
static void descriptor_lifetime(void) {
    struct md_identity id=md_identity_new(2000,2000);
    assert(!md_identity_enter_user_namespace(&id));
    struct md_proc_file *file=md_proc_file_open(id.user_namespace,MD_PROC_FILE_UID_MAP,&id,O_RDWR);
    assert(file);
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct md_fd_origin_update update;
    assert(!md_fd_origins_prepare_object(files,md_proc_file_object(file),0,&update));
    md_fd_origins_finish(&update,20);
    assert(!md_fd_origins_get(files,20,NULL));
    struct md_fd_origins *child=md_fd_origins_fork(files,0); assert(child);
    assert(!md_fd_origins_prepare_copy(files,20,FD_CLOEXEC,&update));
    md_fd_origins_finish(&update,30);
    assert(!md_fd_origins_close_range(files,20,20,0));
    struct md_fd_message *message=NULL;
    int numbers[]={20,99,20};
    assert(!md_fd_message_capture(child,numbers,3,&message));
    md_fd_origins_release(child); md_fd_origins_exec(files);
    assert(!md_fd_origins_object(files,30,NULL)); md_fd_origins_release(files);
    md_proc_file_release(file);
    struct md_fd_object *const *objects=md_fd_message_origins(message);
    assert(objects[0] && objects[0]==objects[2] && !objects[1]);
    file=md_proc_file_from_object(objects[0]); assert(file);
    assert(md_proc_file_write_at(file,&id,NULL,"0 2000 1",8,0,4096)==8);
    md_identity_release(&id);
    char text[34]={0};
    assert(md_proc_file_read_at(file,NULL,NULL,text,sizeof(text),0)==33);
    assert(!strcmp(text,"         0       2000          1\n"));
    struct md_fd_origins *receiver=md_fd_origins_new(); assert(receiver);
    struct md_fd_origin_update received[3];
    assert(!md_fd_origins_prepare_batch(receiver,objects,0,3,received));
    int destinations[]={21,22,23};
    assert(!md_fd_origins_finish_batch(received,3,destinations,2));
    md_fd_message_release(message);
    struct md_fd_object *pin=md_fd_origins_object(receiver,21,NULL);
    assert(pin && md_proc_file_from_object(pin)==file);
    md_fd_origins_release(receiver);
    assert(md_proc_file_read_at(file,NULL,NULL,text,sizeof(text),0)==33);
    md_fd_object_release(pin);
}
static void proc_files(void) {
    struct md_identity id=md_identity_new(2000,2000);
    assert(!md_identity_enter_user_namespace(&id));
    assert(md_identity_setgroups(&id,NULL,0)==-EPERM);
    struct md_proc_file *uid=md_proc_file_open(id.user_namespace,MD_PROC_FILE_UID_MAP,&id,O_RDWR);
    struct md_proc_file *gid=md_proc_file_open(id.user_namespace,MD_PROC_FILE_GID_MAP,&id,O_RDWR);
    struct md_proc_file *groups=md_proc_file_open(id.user_namespace,MD_PROC_FILE_SETGROUPS,&id,O_RDWR);
    assert(uid && gid && groups);
    char output[128]={0};
    assert(md_proc_file_read_at(uid,NULL,NULL,output,sizeof(output),0)==0);
    assert(md_proc_file_write_at(uid,&id,NULL,"0 2000 1",8,1,4096)==-EINVAL);
    assert(md_proc_file_write_at(uid,&id,NULL,"0 2000 1",8,0,8)==-EINVAL);
    const char *bad[]={"", "0", "0 2000", "0 2000 0", "0 2000 1junk", "0 2000 1\n\n",
        "0 2000 4294967296", "4294967295 2000 1", "0 2000 1\n0 2001 1"};
    for (unsigned i=0;i<sizeof(bad)/sizeof(*bad);++i)
        assert(md_proc_file_write_at(uid,&id,NULL,bad[i],strlen(bad[i]),0,4096)==-EINVAL);
    struct md_identity stripped=md_identity_copy(&id);
    stripped.caps.effective=0;
    assert(md_proc_file_write_at(uid,&stripped,NULL,"0 2000 1\n",9,0,4096)==9);
    assert(md_proc_file_write_at(uid,&id,NULL,"0 2000 1\n",9,0,4096)==-EPERM);
    const char *expected="         0       2000          1\n";
    assert(md_proc_file_read_at(uid,NULL,NULL,output,sizeof(output),0)==33);
    assert(!memcmp(output,expected,33));
    assert(md_proc_file_read_at(uid,NULL,NULL,output,4,11)==4 && !memcmp(output,expected+11,4));
    assert(md_proc_file_read_at(uid,NULL,NULL,output,sizeof(output),33)==0);
    assert(md_proc_file_write_at(gid,&id,NULL,"0 2000 1\n",9,0,4096)==-EPERM);
    assert(md_proc_file_write_at(groups,&id,NULL,"deny\n",5,0,4096)==5);
    assert(md_proc_file_read_at(groups,NULL,NULL,output,sizeof(output),0)==5 && !memcmp(output,"deny\n",5));
    assert(md_proc_file_write_at(groups,&id,NULL,"allow\n",6,0,4096)==-EPERM);
    assert(md_proc_file_write_at(gid,&id,NULL,"0 2000 1\n",9,0,4096)==9);
    assert(md_identity_setgroups(&id,NULL,0)==-EPERM);
    md_identity_release(&id); md_identity_release(&stripped);
    assert(md_proc_file_read_at(uid,NULL,NULL,output,sizeof(output),0)==33);
    md_proc_file_release(uid); md_proc_file_release(gid);
    md_proc_file_release(groups);

    struct md_identity parent=md_identity_new(0,0);
    struct md_user_namespace *child=md_user_namespace_new(NULL,0,0,1); assert(child);
    uid=md_proc_file_open(child,MD_PROC_FILE_UID_MAP,&parent,O_WRONLY);
    gid=md_proc_file_open(child,MD_PROC_FILE_GID_MAP,&parent,O_RDWR);
    groups=md_proc_file_open(child,MD_PROC_FILE_SETGROUPS,&parent,O_PATH);
    assert(uid && gid && groups);
    stripped=md_identity_copy(&parent); stripped.caps.effective=0;
    assert(md_proc_file_write_at(uid,&stripped,NULL,"0 0 1000",8,0,4096)==-EPERM);
    assert(md_proc_file_write_at(uid,&parent,NULL,"0 0 1000",8,0,4096)==8);
    assert(md_proc_file_read_at(uid,NULL,NULL,output,sizeof(output),0)==-EBADF);
    assert(md_proc_file_write_at(groups,&parent,NULL,"deny",4,0,4096)==-EBADF);
    assert(md_proc_file_read_at(groups,NULL,NULL,output,sizeof(output),0)==-EBADF);
    assert(md_proc_file_write_at(gid,&parent,NULL,"0 0 1000",8,0,4096)==8);
    assert(md_user_namespace_may_setgroups(child));
    md_proc_file_release(uid); md_proc_file_release(gid);
    md_proc_file_release(groups); md_user_namespace_release(child);
    md_identity_release(&stripped); md_identity_release(&parent);
}
static void process_backing(void) {
    unsigned before=descriptors();
    struct md_identity id=md_identity_new(1234,1235);
    struct md_task_registry *tasks=md_task_registry_create(); assert(tasks);
    struct md_task_resources resources={0};
    assert(!md_task_registry_publish(tasks,getpid(),getpid(),&id,&resources));
    const enum md_proc_file_kind kinds[]={MD_PROC_FILE_STATUS,MD_PROC_FILE_STAT,MD_PROC_FILE_LIMITS};
    for (unsigned i=0;i<sizeof(kinds)/sizeof(*kinds);++i) {
        char path[64]; snprintf(path,sizeof(path),"/proc/self/%s",md_proc_file_process_name(kinds[i]));
        int fd=open(path,O_RDONLY|O_CLOEXEC); assert(fd>=0);
        int dumpable=prctl(PR_GET_DUMPABLE); assert(dumpable>=0);
        assert(!prctl(PR_SET_DUMPABLE,0));
        struct stat direct,captured,native;
        assert(!fstat(fd,&native));
        struct md_proc_file_node a,b;
        assert(!md_proc_file_task_stat(getpid(),&id,NULL,kinds[i],&a,&direct));
        assert(!md_proc_file_task_fd_stat(fd,getpid(),&id,NULL,kinds[i],&b,&captured));
        assert(a.device==b.device && a.inode==b.inode && a.process==b.process);
        assert(direct.st_ino==captured.st_ino && direct.st_mode==captured.st_mode
            && direct.st_uid==captured.st_uid && direct.st_gid==captured.st_gid);
        struct md_proc_file *file=md_proc_file_open(NULL,kinds[i],&id,O_RDONLY); assert(file);
        md_proc_file_node(file,(struct md_proc_file_node){.inode=b.inode+1,.device=b.device,.process=b.process});
        assert(md_proc_file_backing(file,fd,&native,0)==-ESTALE);
        md_proc_file_node(file,b);
        assert(!md_proc_file_backing(file,fd,&native,0));
        assert(md_proc_file_backing(file,fd,&native,0)==-EINVAL);
        assert(lseek(fd,3,SEEK_SET)==3);
        char text[16384];
        long size=md_proc_file_read_at(file,NULL,NULL,text,sizeof(text),0); assert(size>0);
        assert(lseek(fd,0,SEEK_CUR)==3);
        close(fd);
        assert(!prctl(PR_SET_DUMPABLE,1));
        assert(!md_proc_file_retained_stat(file,tasks,0,&captured) && captured.st_ino==direct.st_ino);
        assert(captured.st_uid==1234 && captured.st_gid==1235);
        assert(!prctl(PR_SET_DUMPABLE,dumpable));
        char prefix[17];
        assert(md_proc_file_read_at(file,NULL,NULL,prefix,sizeof(prefix),1)==sizeof(prefix));
        assert(!memcmp(prefix,text+1,sizeof(prefix)));
        md_proc_file_release(file);
    }
    md_task_registry_destroy(tasks);
    md_identity_release(&id);
    assert(descriptors()==before);
}
static void process_directory_backing(void) {
    unsigned before=descriptors();
    struct md_identity id=md_identity_new(0,0);
    for (unsigned path_only=0;path_only<2;++path_only) {
        int flags=O_DIRECTORY|(path_only ? O_PATH : O_RDONLY);
        int fd=open("/proc/self",flags|O_CLOEXEC); assert(fd>=0);
        struct stat native,metadata; assert(!fstat(fd,&native)); metadata=native;
        struct md_proc_file_node node;
        md_proc_file_task_metadata(INT_MAX,&id,NULL,MD_PROC_FILE_PROCESS_DIRECTORY,&node,&metadata);
        struct md_proc_file *file=md_proc_file_open(NULL,MD_PROC_FILE_PROCESS_DIRECTORY,&id,flags); assert(file);
        md_proc_file_node(file,node);
        assert(!md_proc_file_backing(file,fd,&native,1)); close(fd);
        int published=md_proc_file_descriptor(file); assert(published>=0);
        struct stat observed; assert(!fstat(published,&observed));
        assert(observed.st_ino==native.st_ino && observed.st_dev==native.st_dev);
        assert((fcntl(published,F_GETFL)&(O_PATH|O_ACCMODE))==(flags&(O_PATH|O_ACCMODE)));
        assert(fcntl(published,F_GETFD)&FD_CLOEXEC);
        int child=openat(published,"stat",O_RDONLY|O_CLOEXEC); assert(child>=0); close(child);
        close(published);
        struct md_control_opener opener={.identity=&id};
        struct md_proc_file *copy=md_proc_file_reopen(file,&opener,flags); assert(copy);
        md_proc_file_release(file);
        published=md_proc_file_descriptor(copy); assert(published>=0); close(published);
        md_proc_file_release(copy);
    }
    md_identity_release(&id);
    assert(descriptors()==before);
}
void test_user_namespace(void) {
    process_directory_backing();
    namespace_limits();
    resource_limits();
    credential_snapshot();
    custody();
    selectors();
    ordered_io();
    descriptor_lifetime();
    proc_files();
    process_backing();
    const uint64_t caps=(UINT64_C(1)<<CAP_SETUID)|(UINT64_C(1)<<CAP_SETGID)|(UINT64_C(1)<<CAP_SETFCAP)
        |(UINT64_C(1)<<CAP_SYS_ADMIN);
    struct md_user_namespace *n=md_user_namespace_new(NULL,2000,2000,0); assert(n);
    assert(md_user_namespace_from(n,0,2000)==UINT32_MAX);
    struct md_id_extent uid={0,2000,1}, gid={0,2000,1};
    assert(publish(n,0,n,2000,2000,caps,&uid,1)==0);
    assert(md_user_namespace_into(n,0,0)==2000 && md_user_namespace_from(n,0,2000)==0);
    assert(md_user_namespace_into(n,0,1)==UINT32_MAX && md_user_namespace_from(n,0,0)==UINT32_MAX);
    assert(publish(n,0,n,2000,2000,caps,&uid,1)==-EPERM);
    assert(publish(n,1,n,2000,2000,caps,&gid,1)==-EPERM);
    assert(!md_user_namespace_setgroups(n,0));
    assert(!publish(n,1,n,2000,2000,caps,&gid,1));
    assert(md_user_namespace_setgroups(n,1)==-EPERM);
    assert(md_user_namespace_setgroups(n,0)==-EPERM);
    assert(md_user_namespace_capable(NULL,2000,0,n,CAP_SYS_ADMIN));
    assert(!md_user_namespace_capable(n,2000,UINT64_MAX,NULL,CAP_SYS_ADMIN));
    struct md_user_namespace *child=md_user_namespace_new(n,2000,2000,1); assert(child);
    assert(!md_user_namespace_groups_allowed(child));
    struct md_id_extent nested={100,0,1};
    assert(!publish(child,0,n,2000,2000,caps,&nested,1));
    assert(!publish(child,1,n,2000,2000,caps,&nested,1));
    assert(md_user_namespace_into(child,0,100)==2000 && md_user_namespace_from(child,1,2000)==100);
    assert(md_user_namespace_contains(n,child) && !md_user_namespace_contains(child,n));
    md_user_namespace_release(n);
    assert(md_user_namespace_into(child,0,100)==2000); md_user_namespace_release(child);

    n=md_user_namespace_new(NULL,0,0,1); assert(n);
    struct md_id_extent ranges[]={{10,100,10},{30,200,5},{0,0,1}};
    assert(!publish(n,0,NULL,0,0,caps,ranges,3));
    assert(!publish(n,1,NULL,0,0,caps,ranges,3));
    assert(md_user_namespace_into(n,0,19)==109 && md_user_namespace_into(n,0,20)==UINT32_MAX);
    struct md_id_extent copy[3]; assert(md_user_namespace_extents(n,0,copy,2)==-ERANGE);
    assert(md_user_namespace_extents(n,0,copy,3)==3 && copy[1].outside==200);
    child=md_user_namespace_new(n,0,0,1); assert(child);
    struct md_id_extent invalid={0,19,2};
    assert(publish(child,0,n,0,0,caps,&invalid,1)==-EPERM);
    invalid=(struct md_id_extent){UINT32_MAX,0,1};
    assert(publish(child,0,n,0,0,caps,&invalid,1)==-EINVAL);
    struct md_id_extent overlap[]={{0,10,5},{2,30,2}};
    assert(publish(child,0,n,0,0,caps,overlap,2)==-EINVAL);
    overlap[1]=(struct md_id_extent){10,12,2};
    assert(publish(child,0,n,0,0,caps,overlap,2)==-EINVAL);
    struct md_id_extent valid={0,0,1};
    assert(!publish(child,0,n,0,0,caps,&valid,1));
    md_user_namespace_release(child); md_user_namespace_release(n);

    struct md_identity identity=md_identity_new(2000,2000);
    struct md_identity_view view={0}; int64_t observed=-1;
    md_identity_view_publish(&view,&identity);
    assert(md_identity_view_read(&view,SYS_getuid,&observed) && observed==2000);
    assert(!md_identity_enter_user_namespace(&identity));
    md_identity_view_publish(&view,&identity);
    assert(!md_identity_view_read(&view,SYS_getuid,&observed));
    assert(!md_identity_view_read(&view,SYS_getgid,&observed));
    n=identity.user_namespace;
    assert(md_identity_visible_id(&identity,0,identity.uid.real)==65534);
    assert(md_identity_capable(&identity,CAP_SYS_ADMIN));
    assert(!md_identity_capable_in(&identity,NULL,CAP_DAC_OVERRIDE));
    assert(md_identity_setuid(&identity,0)==-EINVAL);
    assert(!publish(n,0,n,2000,2000,identity.caps.effective,&uid,1));
    md_identity_view_publish(&view,&identity);
    assert(md_identity_view_read(&view,SYS_getuid,&observed) && observed==0);
    assert(!md_identity_view_read(&view,SYS_getgid,&observed));
    assert(!md_user_namespace_setgroups(n,0));
    assert(!publish(n,1,n,2000,2000,identity.caps.effective,&gid,1));
    md_identity_view_publish(&view,&identity);
    assert(md_identity_view_read(&view,SYS_getgid,&observed) && observed==0);
    assert(md_identity_visible_id(&identity,0,identity.uid.real)==0);
    assert(md_identity_root(&identity)==2000);
    assert(!md_identity_setuid(&identity,0) && identity.uid.effective==2000);
    assert(md_identity_setuid(&identity,2000)==-EINVAL);
    assert(md_identity_setresgid(&identity,0,UINT32_MAX,0)==0 && identity.gid.saved==2000);
    uint32_t group_id=0;
    assert(md_identity_setgroups(&identity,&group_id,1)==-EPERM);
    assert(md_identity_setfsuid(&identity,123)==0 && identity.uid.fs==2000);
    struct ipc64_perm perm={.uid=123,.cuid=123,.gid=123,.cgid=123,.mode=0600};
    assert(md_ipc_perm_access(NULL,&identity,&perm,4)==-EACCES);
    assert(md_ipc_perm_owner(NULL,&identity,&perm)==-EPERM);
    assert(!md_ipc_perm_access(n,&identity,&perm,4));
    assert(!md_ipc_perm_owner(n,&identity,&perm));
    assert(!md_ipc_perm_set(&identity,&perm,0,0,0640) && perm.uid==2000 && perm.gid==2000);
    assert(md_ipc_perm_set(&identity,&perm,1,0,0000)==-EINVAL && perm.uid==2000 && perm.mode==0640);
    md_ipc_perm_visible(&identity,&perm);
    assert(!perm.uid && !perm.gid && perm.cuid==MD_USER_OVERFLOW_ID);
    struct md_uts_namespace *outer=md_uts_namespace_new(); assert(outer);
    struct md_uts_namespace *inner=md_uts_namespace_copy(outer,n); assert(inner);
    assert(!md_identity_capable_in(&identity,md_uts_namespace_owner(outer),CAP_SYS_ADMIN));
    assert(md_identity_capable_in(&identity,md_uts_namespace_owner(inner),CAP_SYS_ADMIN));
    md_uts_namespace_release(outer);
    struct md_identity forked=md_identity_copy(&identity);
    md_identity_release(&identity);
    assert(md_identity_visible_id(&forked,0,2000)==0);
    struct md_identity after_exec=forked;
    md_identity_exec(&forked,&after_exec);
    assert(md_identity_capable(&after_exec,CAP_SYS_ADMIN));
    assert(!md_identity_capable_in(&after_exec,NULL,CAP_SYS_ADMIN));
    md_identity_release(&forked);
    assert(md_user_namespace_into(md_uts_namespace_owner(inner),0,0)==2000);
    md_uts_namespace_release(inner);
}
