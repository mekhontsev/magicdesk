#define _GNU_SOURCE
#include "fd_selector.h"
#include "fs_mount_internal.h"
#include "task_resources.h"
#include "task_registry.h"
#include "event_wait.h"
#include "fd_mutation.h"
#include "fd_exchange.h"
#include "fd_delivery.h"
#include "fd_receipt.h"
#include "fd_channel.h"
#include "fd_native_pin.h"
#include "descriptor_inode.h"
#include "fd_return.h"
#include "received_fds.h"
#include "fd_messages.h"
#include "control_file.h"
#include "signal_origin.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <linux/close_range.h>
#include <unistd.h>

static int allocation_budget=-1;
void *__real_malloc(size_t);
void *__real_calloc(size_t,size_t);
void *__wrap_malloc(size_t n) {
    if (!allocation_budget) { errno=ENOMEM; return NULL; }
    if (allocation_budget>0) --allocation_budget;
    return __real_malloc(n);
}
void *__wrap_calloc(size_t n, size_t size) {
    if (!allocation_budget) { errno=ENOMEM; return NULL; }
    if (allocation_budget>0) --allocation_budget;
    return __real_calloc(n,size);
}
static unsigned descriptors(void) {
    DIR *d=opendir("/proc/self/fd"); assert(d);
    unsigned n=0; while (readdir(d)) ++n;
    closedir(d); return n;
}
static int retired_gone(pid_t pid) { return pid==20003; }
static void task_reaped_sender(void) {
    uint32_t limit=md_user_namespace_limit(NULL,MD_USER_NAMESPACES);
    assert(!md_user_namespace_set_limit(NULL,MD_USER_NAMESPACES,1));
    for (unsigned namespaced=0;namespaced<2;++namespaced) for (unsigned reuse=0;reuse<2;++reuse) {
        struct md_task_registry *r=md_task_registry_create(); assert(r);
        struct md_identity id=md_identity_new(1000,1000);
        id.user_namespace=md_user_namespace_new(NULL,1000,1000,0); assert(id.user_namespace);
        struct md_task_resources resources; assert(!md_resources_init(&resources,NULL));
        if (namespaced) {
            struct md_pid_namespace *space=md_pid_namespace_new(NULL,id.user_namespace); assert(space);
            assert(!md_pid_identity_prepare(space,&resources.pid));
            assert(!md_pid_identity_publish(resources.pid,20003,20003));
            md_pid_namespace_release(space);
        }
        assert(!md_task_registry_publish(r,20003,20003,&id,&resources));
        md_pid_identity_exit(resources.pid);
        md_task_registry_retire(r,20003,20002,&id);
        assert(!md_resources_release(&resources)); md_identity_release(&id);
        assert(!md_user_namespace_new(NULL,1000,1000,0) && errno==ENOSPC);
        allocation_budget=0;
        md_task_registry_reap_deferred(r,20003);
        assert(!!md_task_registry_retired(r,20003).identity==namespaced);
        allocation_budget=-1;
        md_task_registry_reap_deferred(r,20003);
        md_task_registry_reap_deferred(r,20003);
        assert(!md_task_registry_retired(r,20003).identity);
        assert(md_task_registry_retired(r,20003).parent==20002);
        struct md_task_credentials credentials; struct md_task_snapshot proc;
        assert(md_task_registry_credentials(r,20003,&credentials)==-ESRCH);
        assert(md_task_registry_proc(r,20003,&proc)==-ESRCH);
        assert(md_task_registry_probe(r,20003,0,&proc)==-ESTALE);
        struct md_user_namespace *next=md_user_namespace_new(NULL,1000,1000,0); assert(next);
        md_user_namespace_release(next);
        siginfo_t info={.si_signo=SIGCHLD,.si_code=CLD_EXITED};
        info.si_pid=20003; info.si_uid=10647; info.si_status=7;
        struct md_signal_origin origin;
        assert(md_task_registry_signal_origin(r,&info,&origin));
        assert(md_signal_origin_matches(&origin,&info) && origin.uid==1000 && origin.count==namespaced);
        if (namespaced) assert(origin.numbers[0].number==1);
        if (reuse) {
            id=md_identity_new(2000,2000);
            assert(!md_task_registry_publish(r,20003,20003,&id,NULL));
            assert(md_task_registry_signal_origin(r,&info,&origin) && origin.uid==2000 && !origin.count);
            md_identity_release(&id);
        } else {
            allocation_budget=0; md_task_registry_collect(r,retired_gone); allocation_budget=-1;
            assert(!md_task_registry_signal_origin(r,&info,&origin));
        }
        assert(!md_task_registry_has_retired(r)); md_task_registry_destroy(r);
    }
    assert(!md_user_namespace_set_limit(NULL,MD_USER_NAMESPACES,limit));
    puts("PASS reaped sender: released namespace quotas, unpinned PID values, proc/credential retirement, allocation failure and PID reuse");
}
void test_task_registry_exit(void) {
    unsigned before=descriptors();
    struct md_task_registry *registry=md_task_registry_create(); assert(registry);
    struct md_task_resources resources; assert(!md_resources_init(&resources,NULL));
    struct md_pid_namespace *space=md_pid_namespace_new(NULL,NULL); assert(space);
    assert(!md_pid_identity_prepare(space,&resources.pid));
    assert(!md_pid_identity_publish(resources.pid,20003,20003));
    struct md_pid_identity *pid=md_pid_identity_retain(resources.pid);
    struct md_identity id=md_identity_new(2000,2000);
    struct md_process_limits limits; assert(!md_process_limits_read(&limits));
    limits.nproc=(struct rlimit){2,2};
    struct md_process_member *account=md_process_member_new(&id,&limits), *child=NULL;
    assert(account && !md_process_member_fork(account,&id,0,&resources.process));
    assert(!md_task_registry_publish(registry,20003,20003,&id,&resources));
    struct md_task_credentials observed;
    struct md_task_snapshot retained,reused;
    allocation_budget=0;
    assert(md_task_registry_read(registry,20003,0,&retained)==-ENOMEM && !retained.owner);
    assert(!md_task_registry_credentials(registry,20003,&observed));
    assert(observed.group==20003 && !observed.retired && observed.identity.uid.effective==2000);
    md_task_credentials_release(&observed);
    allocation_budget=-1;
    assert(!md_task_registry_read(registry,20003,0,&retained));
    allocation_budget=0;
    assert(!md_task_registry_read(registry,20003,0,&reused));
    assert(retained.owner && reused.owner==retained.owner);
    md_task_snapshot_release(&reused);
    allocation_budget=-1;
    id.uid.effective=1234;
    struct md_cgroup_member *member=resources.cgroup;
    allocation_budget=0;
    assert(md_pid_identity_exit(pid));
    md_task_registry_retire(registry,20003,0,&id);
    md_task_registry_forget(registry,20003);
    struct md_task_snapshot live;
    assert(md_task_registry_read(registry,20003,0,&live)==-ESRCH);
    assert(md_task_registry_probe(registry,20003,0,&live)==-ESTALE);
    struct md_task_retired_view retired=md_task_registry_retired(registry,20003);
    assert(retired.identity && retired.identity->uid.effective==1234);
    assert(retired.pid==pid && retired.cgroup==member);
    assert(!md_task_registry_credentials(registry,20003,&observed));
    assert(observed.group==20003 && observed.retired && observed.identity.uid.effective==1234);
    md_task_credentials_release(&observed);
    assert(retained.identity.uid.effective==2000 && retained.files==resources.files);
    md_task_snapshot_release(&retained);
    assert(!md_resources_release(&resources));
    assert(md_task_registry_has_retired(registry));
    assert(md_process_member_fork(account,&id,0,&child)==-EAGAIN);
    md_task_registry_collect_limits(registry,retired_gone);
    assert(md_task_registry_retired(registry,20003).identity);
    assert(!md_task_registry_retired(registry,20003).process);
    allocation_budget=-1;
    assert(!md_process_member_fork(account,&id,0,&child));
    md_process_member_release(child); md_process_member_release(account);
    md_identity_release(&id);
    allocation_budget=0;
    md_task_registry_collect(registry,retired_gone);
    assert(!md_task_registry_has_retired(registry));
    assert(!md_task_registry_retired(registry,20003).identity);
    assert(md_task_registry_credentials(registry,20003,&observed)==-ESRCH);
    assert(!md_pid_namespace_lookup(space,1));
    allocation_budget=-1;
    md_pid_identity_release(pid); md_pid_namespace_release(space);
    id=md_identity_new(2000,2000);
    assert(!md_resources_init(&resources,NULL));
    assert(!md_task_registry_publish(registry,20003,20003,&id,&resources));
    md_task_registry_retire(registry,20003,0,&id);
    assert(md_task_registry_retired(registry,20003).identity);
    assert(!md_task_registry_publish(registry,20003,20003,&id,&resources));
    assert(!md_task_registry_has_retired(registry));
    assert(!md_task_registry_read(registry,20003,0,&live)); md_task_snapshot_release(&live);
    md_task_registry_forget(registry,20003);
    assert(md_task_registry_probe(registry,20003,0,&live)==-ESRCH);
    assert(!md_resources_release(&resources)); md_identity_release(&id);
    md_task_registry_destroy(registry);
    task_reaped_sender();
    assert(descriptors()==before);
    puts("PASS task exit: retained identity/cgroup, no live authority, allocation-free retirement/reaping");
}
static struct md_fs_anchor *capture(struct md_mount_namespace *view, int fd) {
    struct md_filesystem fs={.mounts=view};
    struct md_fs_anchor *origin=NULL;
    struct md_fs_output out={.origin=&origin};
    struct md_fs_result result={.fd=fd};
    md_view_capture(&fs,view,NULL,&result,&out);
    assert(!result.error && origin); close(result.fd); return origin;
}
static void put(struct md_fd_origins *table, int fd, struct md_fs_anchor *origin, unsigned flags) {
    struct md_fd_origin_update update;
    assert(!md_fd_origins_prepare(table,origin,flags,&update));
    allocation_budget=0;
    md_fd_origins_finish(&update,fd);
    allocation_budget=-1;
    assert(!update.table && !update.entry);
}
static void expect(struct md_fd_origins *table, int fd, struct md_fs_anchor *expected, unsigned flags) {
    unsigned actual=UINT_MAX;
    struct md_fs_anchor *origin=md_fd_origins_get(table,fd,&actual);
    assert(origin==expected && actual==flags); md_fs_anchor_release(origin);
}
struct borrowed_file { struct md_fd_object object; struct md_fs_anchor *origin; };
static struct md_fs_anchor *borrowed_file_origin(struct md_fd_object *object) {
    return ((struct borrowed_file *)object)->origin;
}
static void borrowed_file_destroy(struct md_fd_object *object) { (void)object; }
static struct md_control_file borrowed_readiness(struct md_fd_object *object) {
    (void)object; return (struct md_control_file){0};
}
struct closing_file { struct md_fd_object object; unsigned closes,destroyed; };
static void closing_destroy(struct md_fd_object *object) { ++((struct closing_file *)object)->destroyed; }
static long closing_close(struct md_fd_object *object,const struct md_control_actor *actor,
        struct md_control_pending **pending) {
    assert(actor && !*pending); ++((struct closing_file *)object)->closes; return -EIO;
}
static void put_closing(struct md_fd_origins *files,int fd,struct closing_file *file) {
    struct md_fd_origin_update update;
    assert(!md_fd_origins_prepare_object(files,&file->object,0,&update));
    md_fd_origins_finish(&update,fd);
}
static unsigned drain_closed(struct md_fd_closed *closed) {
    unsigned count=0;
    while (closed->first) {
        struct md_control_pending *pending=NULL;
        assert(md_fd_closed_next(closed,&(struct md_control_actor){0},&pending)==-EIO && !pending);
        ++count;
    }
    return count;
}
static void close_lifecycle(void) {
    static const struct md_fd_object_type type={.destroy=closing_destroy,.close=closing_close};
    struct closing_file file={0}; md_fd_object_init(&file.object,&type);
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct md_fd_closed closed={0}; struct md_fd_mutation op;
    put_closing(files,10,&file);
    unsigned long args[6]={10};
    assert(!md_fd_mutation_prepare(files,SYS_close,args,&op));
    assert(!md_fd_mutation_finish_closed(&op,files,-EBADF,&closed) && !closed.first && !file.closes);
    assert(!md_fd_mutation_prepare(files,SYS_close,args,&op));
    allocation_budget=0;
    assert(!md_fd_mutation_finish_closed(&op,files,-EINTR,&closed) && closed.first);
    allocation_budget=-1;
    struct md_fd_order turn={0};
    assert(md_fd_order_begin(files,&turn,NULL)==1 && !md_fd_order_start(&turn));
    put_closing(files,10,&file); /* Reuse the released slot before provider completion. */
    assert(!md_fd_order_complete(&turn));
    assert(!file.closes && drain_closed(&closed)==1 && file.closes==1);
    put_closing(files,11,&file);
    args[1]=11; args[2]=O_CLOEXEC;
    assert(!md_fd_mutation_prepare(files,SYS_dup3,args,&op));
    assert(!md_fd_mutation_finish_closed(&op,files,-EINVAL,&closed) && !closed.first);
    assert(!md_fd_mutation_prepare(files,SYS_dup3,args,&op));
    allocation_budget=0;
    assert(!md_fd_mutation_finish_closed(&op,files,11,&closed));
    allocation_budget=-1;
    assert(drain_closed(&closed)==1 && file.closes==2);
    assert(!md_fd_origins_close_collect(files,10,10,1,&closed) && !closed.first);
    allocation_budget=0; md_fd_origins_exec_collect(files,&closed); allocation_budget=-1;
    assert(drain_closed(&closed)==2 && file.closes==4);
    put_closing(files,12,&file);
    struct md_fd_origins *copy=md_fd_origins_fork(files,0); assert(copy);
    md_fd_origins_release(copy); /* Failed clone: no guest descriptors were closed. */
    struct md_fd_origin_update unpublished;
    assert(!md_fd_origins_prepare_object(files,&file.object,0,&unpublished));
    md_fd_origins_finish_closed(&unpublished,-ENOMEM,&closed);
    assert(!closed.first && file.closes==4);
    copy=md_fd_origins_fork(files,1); assert(copy==files);
    struct md_task_resources sibling={.files=copy},last={.files=files};
    md_resources_exit_files(&sibling,&closed); assert(!sibling.files && !closed.first);
    md_resources_exit_files(&sibling,&closed); assert(!closed.first);
    md_fd_object_release(&file.object); assert(!file.destroyed);
    allocation_budget=0; md_resources_exit_files(&last,&closed); allocation_budget=-1;
    assert(!last.files && !file.destroyed);
    assert(!md_resources_release(&sibling) && !md_resources_release(&last));
    assert(drain_closed(&closed)==1 && file.closes==5 && file.destroyed==1);
    puts("PASS confirmed FD closes: allocation-free retirement, reuse, dup replacement, exec, last owner and cancelled publication");
}
static void native_io_proofs(struct md_mount_namespace *view,struct md_fs_anchor *directory) {
    struct md_fs_anchor *file=capture(view,memfd_create("native-io",MFD_CLOEXEC));
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct md_fd_order turn={0};
    struct pollfd vector[]={{8,POLLIN,0},{9,POLLOUT,0},{-1,0,0},{127,POLLIN,0}};
    assert(!md_fd_origins_native_poll_vector(&turn,vector,4));
    put(files,8,file,0); put(files,9,directory,0);
    assert(!md_fd_origins_native_poll(&turn));
    assert(!md_fd_origins_native_regular(&turn,8));
    assert(!md_fd_origins_close_untracked(&turn,127));
    assert(md_fd_order_begin(files,&turn,NULL)==1);
    assert(!md_fd_origins_native_regular(&turn,8));
    assert(!md_fd_origins_close_untracked(&turn,127));
    assert(!md_fd_order_start(&turn));
    assert(md_fd_origins_native_poll(&turn));
    assert(md_fd_origins_native_poll_vector(&turn,vector,4));
    allocation_budget=0;
    struct md_fd_native native={0};
    assert(md_fd_origins_native(&turn,8,&native) && native.kind==S_IFREG && !native.flags
        && native.origin==file);
    assert(md_fd_origins_native(&turn,9,&native) && native.kind==S_IFDIR);
    assert(md_fd_origins_native_regular(&turn,8));
    assert(!md_fd_origins_native_regular(&turn,9));
    assert(!md_fd_origins_native_regular(&turn,10));
    assert(!md_fd_origins_native_regular(&turn,-1));
    struct md_fd_observation before_close;
    assert(!md_fd_origins_observe(files,127,&before_close));
    assert(!md_fd_origins_close_untracked(&turn,8));
    assert(!md_fd_origins_close_untracked(&turn,9));
    assert(!md_fd_origins_validate(&turn,before_close.generation));
    assert(md_fd_origins_close_untracked(&turn,127));
    assert(md_fd_origins_validate(&turn,before_close.generation)==-EAGAIN);
    assert(md_fd_origins_close_untracked(&turn,-1));
    allocation_budget=-1;
    static const struct md_fd_object_type type={.destroy=borrowed_file_destroy,.origin=borrowed_file_origin};
    struct borrowed_file typed={.origin=file}; md_fd_object_init(&typed.object,&type);
    struct md_fd_origin_update update;
    assert(!md_fd_origins_prepare_object(files,&typed.object,0,&update));
    md_fd_origins_finish(&update,10);
    assert(!md_fd_origins_native(&turn,10,&native));
    assert(!md_fd_origins_native_regular(&turn,10));
    assert(!md_fd_origins_close_untracked(&turn,10));
    struct md_identity identity=md_identity_new(0,0);
    struct md_fd_object *pipe=md_descriptor_inode_new(&identity,S_IFIFO|0600); assert(pipe);
    assert(!md_fd_origins_prepare_object(files,pipe,0,&update));
    md_fd_origins_finish(&update,12);
    allocation_budget=0;
    assert(md_fd_origins_native_io(&turn,8) && md_fd_origins_native_io(&turn,12));
    assert(!md_fd_origins_native_io(&turn,9) && !md_fd_origins_native_io(&turn,10));
    assert(!md_fd_origins_native_io(&turn,-1));
    allocation_budget=-1;
    assert(!md_fd_origins_prepare_copy(files,12,0,&update));
    md_fd_origins_finish(&update,13);
    assert(md_fd_origins_native_io(&turn,13));
    assert(!md_fd_origins_abandon_range(files,12,12));
    assert(!md_fd_origins_native_io(&turn,12));
    md_fd_object_release(pipe); md_identity_release(&identity);
    struct md_fd_loan *loan=md_fd_loan_new(files); assert(loan);
    assert(!md_fd_origins_prepare(files,file,0,&update));
    assert(!md_fd_origins_prepare_custody(&update,loan,&turn));
    md_fd_origins_finish(&update,11);
    assert(!md_fd_origins_native(&turn,11,&native));
    assert(!md_fd_origins_native_regular(&turn,11));
    assert(!md_fd_origins_close_untracked(&turn,11));
    assert(!md_fd_origins_abandon_range(files,11,11));
    assert(!md_fd_origins_close_untracked(&turn,11));
    assert(!md_fd_origins_abandon_range(files,8,8));
    assert(!md_fd_origins_native(&turn,8,&native));
    assert(!md_fd_origins_native_regular(&turn,8));
    put(files,8,file,FD_CLOEXEC); assert(md_fd_origins_native_regular(&turn,8));
    assert(md_fd_origins_native(&turn,8,&native) && native.flags==FD_CLOEXEC);
    struct md_fd_origins *shared=md_fd_origins_fork(files,1); assert(shared==files);
    assert(!md_fd_origins_exclusive(&turn));
    assert(!md_fd_origins_close_untracked(&turn,127));
    md_fd_origins_release(shared);
    assert(md_fd_origins_exclusive(&turn));
    /* Count possible readiness, not its current callback result. Dynamic
     * activation must not turn a previously admitted table into a bypass. */
    static const struct md_fd_object_type ready_type={.destroy=borrowed_file_destroy,.readiness=borrowed_readiness};
    struct md_fd_object ready; md_fd_object_init(&ready,&ready_type);
    assert(!md_fd_origins_prepare_object(files,&ready,FD_CLOEXEC,&update));
    assert(md_fd_origins_native_poll(&turn));
    md_fd_origins_finish(&update,20);
    assert(!md_fd_origins_native_poll(&turn));
    allocation_budget=0;
    assert(md_fd_origins_native_poll_vector(&turn,vector,4));
    assert(md_fd_origins_native_poll_vector(&turn,NULL,0));
    assert(!md_fd_origins_native_poll_vector(&turn,NULL,1));
    vector[2].fd=20;
    assert(!md_fd_origins_native_poll_vector(&turn,vector,4));
    vector[2].events=0; /* HUP/ERR still make this descriptor relevant. */
    assert(!md_fd_origins_native_poll_vector(&turn,vector,4));
    vector[2].fd=-20;
    assert(md_fd_origins_native_poll_vector(&turn,vector,4));
    allocation_budget=-1;
    assert(!md_fd_origins_prepare_copy(files,20,0,&update));
    md_fd_origins_finish(&update,21);
    struct md_fd_origins *copy=md_fd_origins_fork(files,0); assert(copy);
    struct md_fd_order copied={0};
    assert(md_fd_order_begin(copy,&copied,NULL)==1 && !md_fd_order_start(&copied));
    assert(!md_fd_origins_native_poll(&copied));
    assert(!md_fd_origins_close_range(files,20,20,0));
    assert(!md_fd_origins_native_poll(&turn));
    vector[2].fd=20;
    assert(md_fd_origins_native_poll_vector(&turn,vector,4));
    vector[2].fd=21;
    assert(!md_fd_origins_native_poll_vector(&turn,vector,4));
    put(files,21,file,0);
    assert(md_fd_origins_native_poll(&turn) && !md_fd_origins_native_poll(&copied));
    md_fd_origins_exec(copy);
    assert(!md_fd_origins_native_poll(&copied));
    assert(!md_fd_origins_close_range(copy,0,UINT_MAX,0));
    assert(md_fd_origins_native_poll(&copied));
    assert(!md_fd_order_complete(&copied)); md_fd_origins_release(copy);
    assert(!md_fd_origins_prepare_object(files,&ready,0,&update));
    md_fd_origins_finish(&update,-EMFILE);
    assert(md_fd_origins_native_poll(&turn));
    md_fd_object_release(&ready);
    assert(!md_fd_origins_close_range(files,8,8,0));
    assert(!md_fd_origins_native_regular(&turn,8));
    assert(!md_fd_order_complete(&turn));
    md_fd_loan_end(loan); md_fd_loan_release(loan);
    md_fd_origins_release(files); md_fd_object_release(&typed.object); md_fs_anchor_release(file);
    puts("PASS native IO proof: regular origin, typed/unknown/directory rejection, custody, uncertainty, replacement and shared tables");
}
static void bounded_retirement(struct md_fs_anchor *anchor) {
    const unsigned ranges[][2]={{10,10},{60,63},{120,180},{120,181},{INT_MAX,INT_MAX},{UINT_MAX,UINT_MAX}};
    for (unsigned kind=0;kind<3;++kind) for (unsigned r=0;r<sizeof(ranges)/sizeof(*ranges);++r) {
        struct md_fd_origins *table=md_fd_origins_new(); assert(table);
        for (int fd=0;fd<256;++fd) put(table,fd,anchor,0);
        put(table,INT_MAX,anchor,0);
        struct md_fd_observation observation;
        assert(!md_fd_origins_observe(table,0,&observation));
        unsigned first=ranges[r][0],last=ranges[r][1];
        allocation_budget=0;
        assert(!(kind==2 ? md_fd_origins_abandon_range(table,first,last)
            : md_fd_origins_close_range(table,first,last,kind)));
        allocation_budget=-1;
        struct md_fd_observation after;
        assert(!md_fd_origins_observe(table,0,&after) && after.generation!=observation.generation);
        for (unsigned i=0;i<=256;++i) {
            int fd=i==256 ? INT_MAX : (int)i;
            int selected=(unsigned)fd>=first && (unsigned)fd<=last;
            expect(table,fd,selected && kind!=1 ? NULL : anchor,selected && kind==1 ? FD_CLOEXEC : 0);
        }
        md_fd_origins_release(table);
    }
    puts("PASS bounded FD retirement: collisions, wrapped/whole buckets, high ranges, flags, uncertain removal and generation");
}
static void copy_slot(struct md_fd_origins *table, int from, int to, unsigned flags) {
    struct md_fs_anchor *origin=md_fd_origins_get(table,from,NULL);
    put(table,to,origin,flags); md_fs_anchor_release(origin);
}
static void cwd_context(struct md_fs_anchor *a) {
    struct md_guest_domain *fs=md_domain_new(); assert(fs);
    struct md_fs_location root={.mount=17}, actual;
    struct md_fs_anchor *cwd=NULL;
    assert(!md_domain_set_cwd(fs,a) && !md_domain_scoped(fs));
    assert(!md_domain_set_root(fs,&root) && md_domain_scoped(fs));
    md_domain_paths(fs,&actual,&cwd);
    assert(actual.mount==17 && cwd==a); md_fs_anchor_release(cwd);
    assert(!md_domain_set_cwd(fs,NULL) && md_domain_scoped(fs));
    md_domain_paths(fs,&actual,&cwd); assert(actual.mount==17 && !cwd);
    assert(!md_domain_set_cwd(fs,a));
    root=(struct md_fs_location){0};
    assert(!md_domain_set_root(fs,&root) && !md_domain_scoped(fs));
    md_domain_paths(fs,&actual,&cwd);
    assert(!actual.mount && cwd==a); md_fs_anchor_release(cwd);
    assert(!md_domain_restrict(fs,getpid(),"/proc/self/fdinfo"));
    assert(md_domain_set_cwd(fs,a)==-EPERM);
    assert(!md_domain_chdir_root(fs));
    md_domain_paths(fs,&actual,&cwd); assert(!actual.mount && !cwd);
    md_domain_release(fs);
}
static void ownership(struct md_fs_anchor *a, struct md_fs_anchor *b) {
    for (unsigned sharing=0;sharing<4;++sharing) {
        struct md_task_resources parent, child;
        unsigned long flags=(sharing&1 ? CLONE_FS : 0) | (sharing&2 ? CLONE_FILES : 0);
        assert(!md_resources_init(&parent,a->view));
        assert(!md_domain_set_cwd(parent.fs,a));
        put(parent.files,10,a,0); put(parent.files,71,b,FD_CLOEXEC);
        assert(!md_resources_fork(&parent,flags,NULL,&child));
        assert((parent.fs==child.fs)==!!(flags&CLONE_FS));
        assert((parent.files==child.files)==!!(flags&CLONE_FILES));
        put(child.files,10,b,0);
        expect(parent.files,10,flags&CLONE_FILES ? b : a,0);
        assert(!md_domain_set_cwd(child.fs,b));
        struct md_fs_location root;
        struct md_fs_anchor *cwd=NULL;
        md_domain_paths(parent.fs,&root,&cwd);
        assert(cwd==(flags&CLONE_FS ? b : a)); md_fs_anchor_release(cwd);
        struct md_fd_origins *old=child.files;
        struct md_resource_change change;
        assert(!md_resources_unshare_prepare(&child,CLONE_FILES,NULL,&change));
        assert(!md_resources_unshare_finish(&child,NULL,&change,-EINVAL));
        assert(child.files==old);
        assert(!md_resources_exec(&child,NULL));
        assert(child.files!=parent.files);
        expect(child.files,10,b,0); expect(child.files,71,NULL,0);
        expect(parent.files,71,b,FD_CLOEXEC);
        md_domain_paths(child.fs,&root,&cwd); assert(cwd==b); md_fs_anchor_release(cwd);
        assert((parent.fs==child.fs)==!!(flags&CLONE_FS));
        assert(!md_resources_release(&child));
        md_fd_origins_pin(parent.files);
        assert(!md_resources_unshare_prepare(&parent,CLONE_FILES,NULL,&change));
        assert(change.next.files==parent.files);
        assert(!md_resources_unshare_finish(&parent,NULL,&change,0));
        md_fd_origins_unpin(parent.files);
        assert(!md_resources_release(&parent));
    }
}
static void rollback(struct md_fs_anchor *a) {
    struct md_task_resources parent, child;
    assert(!md_resources_init(&parent,a->view));
    put(parent.files,5,a,0); put(parent.files,66,a,FD_CLOEXEC);
    assert(!md_resources_fork(&parent,CLONE_FS|CLONE_FILES,NULL,&child));
    int completed=0;
    for (int n=0;n<16;++n) {
        struct md_resource_change change;
        allocation_budget=n;
        int result=md_resources_unshare_prepare(&child,CLONE_FS|CLONE_FILES|CLONE_SYSVSEM,NULL,&change);
        allocation_budget=-1;
        assert(child.files==parent.files && child.fs==parent.fs);
        if (result) {
            assert(result==-ENOMEM && !change.next.files && !change.next.fs && !change.next.sem_undo);
        } else {
            assert(change.next.files!=parent.files && change.next.fs!=parent.fs);
            assert(!md_resources_unshare_finish(&child,NULL,&change,-ECANCELED));
            completed=1; break;
        }
        expect(parent.files,5,a,0); expect(parent.files,66,a,FD_CLOEXEC);
    }
    assert(completed);
    struct md_resource_change change;
    assert(!md_resources_unshare_prepare(&child,CLONE_FILES,NULL,&change));
    assert(!md_resources_unshare_finish(&child,NULL,&change,0));
    assert(child.files!=parent.files && child.fs==parent.fs);
    assert(!md_fd_origins_close_range(child.files,0,UINT_MAX,0));
    expect(child.files,5,NULL,0); expect(parent.files,5,a,0);
    assert(!md_resources_release(&child)); assert(!md_resources_release(&parent));
}
struct readers { struct md_fd_origins *table; struct md_fs_anchor *a, *b; pthread_barrier_t start; };
static void *read_origins(void *context) {
    struct readers *r=context;
    /* EVENT_WAIT: start the writer/reader race; the fixture runner bounds a stuck barrier/join. */
    pthread_barrier_wait(&r->start);
    for (unsigned i=0;i<10000;++i) {
        unsigned flags;
        struct md_fs_anchor *origin=md_fd_origins_get(r->table,9,&flags);
        assert((origin==r->a && !flags) || (origin==r->b && flags==FD_CLOEXEC));
        md_fs_anchor_release(origin);
    }
    return NULL;
}
static void registry_and_readers(struct md_fs_anchor *a, struct md_fs_anchor *b) {
    struct md_task_resources resources;
    assert(!md_resources_init(&resources,a->view));
    assert(!md_domain_set_cwd(resources.fs,a));
    assert(!md_domain_scoped(resources.fs));
    struct md_identity identity=md_identity_new(0,0);
    struct md_task_registry *registry=md_task_registry_create(); assert(registry);
    assert(!md_task_registry_publish(registry,123,120,&identity,&resources));
    struct md_task_snapshot snapshot;
    assert(md_task_registry_read(registry,123,119,&snapshot)==-EPERM);
    assert(!snapshot.files && !snapshot.cwd);
    assert(!md_task_registry_read(registry,123,120,&snapshot));
    assert(snapshot.files==resources.files && snapshot.cwd==a && snapshot.mounts==a->view);
    assert(!md_domain_set_cwd(resources.fs,b)); assert(snapshot.cwd==a);
    md_task_registry_forget(registry,123); md_task_registry_destroy(registry);
    md_identity_release(&identity); assert(!md_resources_release(&resources));
    put(snapshot.files,9,a,0);
    struct readers readers={.table=snapshot.files,.a=a,.b=b};
    assert(!pthread_barrier_init(&readers.start,NULL,2));
    pthread_t reader; assert(!pthread_create(&reader,NULL,read_origins,&readers));
    /* EVENT_WAIT: reader and writer enter together; runner deadline is a failure bound. */
    pthread_barrier_wait(&readers.start);
    for (unsigned i=0;i<10000;++i) {
        struct md_fd_origin_update update;
        assert(!md_fd_origins_prepare(snapshot.files,i%2 ? a : b,i%2 ? 0 : FD_CLOEXEC,&update));
        md_fd_origins_finish(&update,9);
    }
    assert(!pthread_join(reader,NULL)); pthread_barrier_destroy(&readers.start);
    md_task_snapshot_release(&snapshot);
}
static void ordering(struct md_fs_anchor *origin) {
    struct md_fd_origins *table=md_fd_origins_new(); assert(table);
    struct md_fd_origins *shared=md_fd_origins_fork(table,1); assert(shared==table);
    struct md_fd_order a={0}, b={0}, c={0}, d={0}, independent={0};
    assert(md_fd_order_start(&a)==-EINVAL);
    assert(md_fd_order_complete(&a)==-EINVAL);
    struct md_fd_origin_update update;
    assert(!md_fd_origins_prepare(table,origin,0,&update));
    allocation_budget=0;
    assert(md_fd_order_begin(table,&a,NULL)==1);
    assert(md_fd_order_begin(table,&a,NULL)==-EBUSY);
    assert(md_fd_order_complete(&a)==-EINVAL);
    assert(!md_fd_order_start(&a));
    assert(md_fd_order_begin(shared,&b,NULL)==0);
    assert(md_fd_order_begin(table,&c,NULL)==0);
    assert(md_fd_order_begin(table,&d,NULL)==0);
    assert(md_fd_order_start(&b)==-EINVAL);
    assert(md_fd_order_complete(&b)==-EINVAL);
    allocation_budget=-1;
    struct md_fd_origins *copy=md_fd_origins_fork(table,0); assert(copy && copy!=table);
    allocation_budget=0;
    assert(md_fd_order_begin(copy,&independent,NULL)==1 && !md_fd_order_start(&independent));
    assert(!md_fd_order_complete(&independent));
    assert(!md_fd_order_cancel(&c) && md_fd_order_state(&c)==MD_RESOURCE_ORDER_IDLE);
    assert(!md_fd_order_cancel(&d) && md_fd_order_state(&d)==MD_RESOURCE_ORDER_IDLE);
    assert(md_fd_order_cancel(&a)==1 && md_fd_order_state(&a)==MD_RESOURCE_ORDER_CANCELLING);
    assert(md_fd_order_cancel(&a)==1);
    assert(md_fd_order_state(&b)==MD_RESOURCE_ORDER_QUEUED);
    /* Cancellation intent is not proof that the kernel did not install an FD. */
    md_fd_origins_finish(&update,23);
    assert(!md_fd_order_complete(&a) && md_fd_order_state(&b)==MD_RESOURCE_ORDER_READY);
    expect(table,23,origin,0);
    assert(md_fd_order_begin(table,&c,NULL)==0);
    assert(!md_fd_order_cancel(&b) && md_fd_order_state(&c)==MD_RESOURCE_ORDER_READY);
    assert(!md_fd_order_start(&c));
    assert(!md_fd_order_complete(&c));
    assert(md_fd_order_begin(table,&d,NULL)==1 && !md_fd_order_start(&d));
    assert(md_fd_order_begin(table,&a,NULL)==0);
    md_fd_origins_release(shared); md_fd_origins_release(table);
    expect(d.table,23,origin,0);
    assert(!md_fd_order_complete(&d) && md_fd_order_state(&a)==MD_RESOURCE_ORDER_READY && !d.table);
    expect(a.table,23,origin,0);
    assert(!md_fd_order_cancel(&a) && !a.table);
    allocation_budget=-1;
    md_fd_origins_release(copy);
}
static void filesystem_ordering(struct md_fs_anchor *origin) {
    for (unsigned bits=0;bits<4;++bits) {
        unsigned flags=(bits&1 ? CLONE_FS : 0) | (bits&2 ? CLONE_FILES : 0);
        struct md_task_resources parent={0}, child={0};
        assert(!md_resources_init(&parent,origin->view));
        assert(!md_domain_set_cwd(parent.fs,origin));
        assert(!md_resources_fork(&parent,flags,NULL,&child));
        struct md_fs_order a={0}, b={0};
        struct md_fd_order files={0};
        allocation_budget=0;
        assert(md_fs_order_begin(parent.fs,&a,NULL)==1 && !md_fs_order_start(&a));
        assert(md_fd_order_begin(child.files,&files,NULL)==1 && !md_fd_order_start(&files));
        assert(md_fs_order_begin(child.fs,&b,NULL)==!(flags&CLONE_FS));
        assert(md_fs_order_cancel(&a)==1);
        assert(!md_resources_release(&parent) && !md_resources_release(&child));
        assert(!md_fs_order_complete(&a));
        assert(md_fs_order_state(&b)==MD_RESOURCE_ORDER_READY && !md_fs_order_start(&b));
        struct md_fs_location root;
        struct md_fs_anchor *cwd=NULL;
        md_domain_paths(b.fs,&root,&cwd); assert(cwd==origin); md_fs_anchor_release(cwd);
        assert(!md_fs_order_complete(&b) && !b.fs);
        assert(!md_fd_order_complete(&files));
        allocation_budget=-1;
    }
    puts("PASS independent filesystem ordering for every CLONE_FS/CLONE_FILES combination and owner teardown");
}
struct event_owner {
    struct md_fd_origins *table;
    pthread_barrier_t *start;
    unsigned *committed;
    atomic_uint *inside;
};
static void *ordered_writer(void *opaque) {
    struct event_owner *owner=opaque;
    int fd=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(fd>=0);
    struct md_resource_wakeup *wakeup=NULL;
    assert(!md_resource_wakeup_create(fd,&wakeup));
    /* EVENT_WAIT: all producers are ready; the fixture runner bounds failure. */
    pthread_barrier_wait(owner->start);
    for (unsigned i=0;i<1000;++i) {
        struct md_fd_order op={0};
        int ready=md_fd_order_begin(owner->table,&op,wakeup); assert(ready>=0);
        if (!ready && i%3==0) {
            assert(!md_fd_order_cancel(&op));
            assert(md_fd_order_begin(owner->table,&op,wakeup)>=0);
        }
        int64_t deadline=md_event_now()+5000000000LL;
        while (md_fd_order_state(&op)!=MD_RESOURCE_ORDER_READY) {
            /* EVENT_WAIT: another owner retires its exact operation; timeout
             * fails the fixture, never grants admission. Late hints are OK. */
            assert(!md_event_wait_fd(fd,POLLIN,deadline));
            uint64_t hints;
            ssize_t n;
            do { n=read(fd,&hints,sizeof(hints)); } while (n<0 && errno==EINTR);
            assert(n==sizeof(hints));
        }
        /* READY publishes the preceding owner's writes before taking the
         * queue mutex again in start(), not merely its wakeup hint. */
        unsigned previous=*owner->committed;
        assert(!md_fd_order_start(&op));
        assert(*owner->committed==previous && atomic_fetch_add(owner->inside,1)==0);
        assert(!md_fd_origins_set_flags(owner->table,9,previous&1 ? FD_CLOEXEC : 0));
        *owner->committed=previous+1;
        assert(atomic_fetch_sub(owner->inside,1)==1);
        assert(!md_fd_order_complete(&op));
    }
    close(fd); md_resource_wakeup_release(wakeup); return NULL;
}
static void event_ordering(struct md_fs_anchor *origin) {
    struct md_resource_queue queue;
    struct md_resource_order observed={0}, next={0};
    assert(!md_resource_queue_init(&queue));
    assert(md_resource_order_begin(&queue,&observed,NULL)==1);
    assert(!md_resource_order_begin(&queue,&next,NULL));
    pthread_mutex_lock(&queue.lock);
    assert(md_resource_order_state(&observed)==MD_RESOURCE_ORDER_READY);
    assert(md_resource_order_state(&next)==MD_RESOURCE_ORDER_QUEUED);
    pthread_mutex_unlock(&queue.lock);
    assert(!md_resource_order_cancel(&observed));
    assert(md_resource_order_state(&next)==MD_RESOURCE_ORDER_READY);
    assert(!md_resource_order_cancel(&next));
    md_resource_queue_destroy(&queue);
    struct md_fd_origins *table=md_fd_origins_new(); assert(table);
    put(table,9,origin,0);
    pthread_barrier_t start; assert(!pthread_barrier_init(&start,NULL,3));
    unsigned committed=0; atomic_uint inside=0;
    struct event_owner owner={table,&start,&committed,&inside};
    pthread_t threads[3];
    for (unsigned i=0;i<3;++i) assert(!pthread_create(threads+i,NULL,ordered_writer,&owner));
    /* EVENT_WAIT: all producer loops retire their records; runner bounds joins. */
    for (unsigned i=0;i<3;++i) assert(!pthread_join(threads[i],NULL));
    assert(committed==3000 && !atomic_load(&inside));
    pthread_barrier_destroy(&start);
    int fd=eventfd(0,EFD_NONBLOCK|EFD_CLOEXEC); assert(fd>=0);
    struct md_resource_wakeup *wake=NULL;
    assert(!md_resource_wakeup_create(fd,&wake));
    struct md_fd_order a={0}, b={0};
    assert(md_fd_order_begin(table,&a,NULL)==1 && !md_fd_order_start(&a));
    assert(!md_fd_order_begin(table,&b,wake));
    uint64_t full=UINT64_MAX-1, count;
    assert(write(fd,&full,sizeof(full))==sizeof(full));
    assert(!md_fd_order_complete(&a));
    assert(read(fd,&count,sizeof(count))==sizeof(count) && count==full);
    assert(md_fd_order_state(&b)==MD_RESOURCE_ORDER_READY && !md_fd_order_cancel(&b));
    assert(md_fd_order_begin(table,&a,NULL)==1 && !md_fd_order_start(&a));
    assert(!md_fd_order_begin(table,&b,wake));
    /* The queued record retains the notification endpoint, not the loop. */
    close(fd); md_resource_wakeup_release(wake);
    assert(!md_fd_order_complete(&a));
    assert(md_fd_order_state(&b)==MD_RESOURCE_ORDER_READY && !md_fd_order_cancel(&b));
    md_fd_origins_release(table);
}
static void mutations(struct md_fs_anchor *a, struct md_fs_anchor *b) {
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    put(files,10,a,0); put(files,11,b,0);
    struct md_fd_mutation op;
    unsigned long args[6]={10,11,O_CLOEXEC};
    assert(!md_fd_mutation_prepare(files,SYS_dup3,args,&op));
    allocation_budget=0;
    assert(!md_fd_mutation_finish(&op,files,11));
    allocation_budget=-1;
    expect(files,11,a,FD_CLOEXEC);
    args[1]=(1UL<<32)|F_DUPFD_CLOEXEC;
    assert(!md_fd_mutation_prepare(files,SYS_fcntl,args,&op));
    assert(!md_fd_mutation_finish(&op,files,12)); expect(files,12,a,FD_CLOEXEC);
    args[1]=(1UL<<32)|F_SETFD; args[2]=FD_CLOEXEC;
    assert(!md_fd_mutation_prepare(files,SYS_fcntl,args,&op));
    assert(!md_fd_mutation_finish(&op,files,-EPERM)); expect(files,10,a,0);
    assert(!md_fd_mutation_prepare(files,SYS_fcntl,args,&op));
    assert(!md_fd_mutation_finish(&op,files,0)); expect(files,10,a,FD_CLOEXEC);
    const unsigned commands[]={FIONCLEX,FIOCLEX};
    for (unsigned i=0;i<2;++i) {
        args[1]=(1UL<<32)|commands[i]; args[2]=~0UL;
        assert(!md_fd_mutation_prepare(files,SYS_ioctl,args,&op));
        assert(!md_fd_mutation_finish(&op,files,-EPERM)); expect(files,10,a,i ? 0 : FD_CLOEXEC);
        assert(!md_fd_mutation_prepare(files,SYS_ioctl,args,&op));
        allocation_budget=0;
        assert(!md_fd_mutation_finish(&op,files,0)); expect(files,10,a,i ? FD_CLOEXEC : 0);
        allocation_budget=-1;
        put(files,13,b,FD_CLOEXEC);
        args[0]=13;
        assert(!md_fd_mutation_prepare(files,SYS_ioctl,args,&op));
        assert(!md_fd_mutation_abandon(&op,files)); expect(files,13,NULL,0);
        args[0]=10;
    }
    assert(!md_fd_mutation_prepare(files,SYS_close,args,&op));
    assert(!md_fd_mutation_finish(&op,files,-EPERM)); expect(files,10,a,FD_CLOEXEC);
    assert(!md_fd_mutation_prepare(files,SYS_close,args,&op));
    assert(!md_fd_mutation_finish(&op,files,0)); expect(files,10,NULL,0);
    args[0]=11; args[1]=12; args[2]=CLOSE_RANGE_CLOEXEC;
    assert(!md_fd_mutation_prepare(files,SYS_close_range,args,&op));
    assert(!md_fd_mutation_finish(&op,files,0));
    expect(files,11,a,FD_CLOEXEC); expect(files,12,a,FD_CLOEXEC);
    args[0]=11; args[1]=12; args[2]=0;
    assert(!md_fd_mutation_prepare(files,SYS_dup3,args,&op));
    assert(!md_fd_mutation_abandon(&op,files));
    expect(files,11,a,FD_CLOEXEC); expect(files,12,NULL,0);
    args[0]=11; args[1]=F_SETFD;
    assert(!md_fd_mutation_prepare(files,SYS_fcntl,args,&op));
    assert(!md_fd_mutation_abandon(&op,files)); expect(files,11,NULL,0);
    args[0]=(unsigned long)AT_FDCWD; args[2]=O_PATH|O_CLOEXEC;
    assert(!md_fd_mutation_prepare(files,SYS_openat,args,&op));
    long result=123;
    assert(!md_fd_mutation_terminal_open(&op,0,&result) && result==123);
    assert(!md_fd_origins_prepare(files,a,FD_CLOEXEC,&op.update));
    allocation_budget=0;
    assert(md_fd_mutation_terminal_open(&op,0,&result) && !result);
    assert(md_fd_mutation_terminal_open(&op,63,&result) && result==63);
    assert(md_fd_mutation_terminal_open(&op,(unsigned long)-EMFILE,&result) && result==-EMFILE);
    assert(md_fd_mutation_terminal_open(&op,(unsigned long)AT_FDCWD,&result) && result<0);
    assert(md_fd_mutation_terminal_open(&op,(unsigned)AT_FDCWD,&result) && result<0);
    result=123;
    assert(!md_fd_mutation_terminal_open(&op,1UL<<32,&result) && result==123);
    assert(!md_fd_mutation_terminal_open(&op,(unsigned long)-4096,&result) && result==123);
    op.args[0]=3;
    assert(!md_fd_mutation_terminal_open(&op,3,&result) && result==123);
    op.args[0]=(unsigned)AT_FDCWD; op.args[2]=O_RDONLY;
    assert(!md_fd_mutation_terminal_open(&op,3,&result) && result==123);
    op.args[2]=O_PATH|O_CLOEXEC;
    assert(md_fd_mutation_terminal_open(&op,63,&result) && result==63);
    assert(!md_fd_mutation_finish(&op,files,result));
    allocation_budget=-1;
    expect(files,63,a,FD_CLOEXEC);
    md_fd_origins_release(files);
}
static void exchanges(struct md_fs_anchor *a, struct md_fs_anchor *b) {
    struct md_fd_exchanges *set=md_fd_exchanges_create(); assert(set);
    struct md_fd_origins *files=md_fd_origins_new(), *other=md_fd_origins_new(); assert(files && other);
    put(files,7,a,0);
    int bases[]={7,-1}, targets[]={10,11};
    struct md_fd_exchange *owner=NULL;
    assert(!md_fd_exchange_begin(set,120,files,bases,NULL,&owner));
    uint64_t id=md_fd_exchange_id(owner); assert(id);
    assert(!md_fd_exchange_acquire(set,id,121,files));
    assert(!md_fd_exchange_acquire(set,id,120,other));
    struct md_fd_exchange *worker=md_fd_exchange_acquire(set,id,120,files); assert(worker);
    put(files,7,b,0);
    assert(md_fd_exchange_input(worker,0)==a && !md_fd_exchange_input(worker,1));
    assert(md_fd_exchange_received(owner)==-EAGAIN);
    allocation_budget=0;
    assert(md_fd_exchange_offer(worker,b,2)==-ENOMEM);
    allocation_budget=-1;
    assert(!md_fd_exchange_offer(worker,b,2));
    size_t size=md_fd_receipt_size(2);
    struct md_fd_receipt *receipt=mmap(NULL,size,PROT_READ|PROT_WRITE,
        MAP_SHARED,md_fd_exchange_receipt(worker),0);
    assert(receipt!=MAP_FAILED);
    assert(!md_fd_exchange_received(owner));
    assert(!md_fd_receipt_reset(receipt,2));
    struct cmsghdr *c=(void *)receipt->control;
    c->cmsg_len=CMSG_LEN(sizeof(targets)); c->cmsg_level=SOL_SOCKET; c->cmsg_type=SCM_RIGHTS;
    int invalid[]={10,10}; memcpy(CMSG_DATA(c),invalid,sizeof(invalid));
    assert(md_fd_exchange_received(owner)==-EPROTO);
    memcpy(CMSG_DATA(c),targets,sizeof(targets));
    assert(md_fd_exchange_offer(worker,a,1)==-EALREADY);
    md_fd_exchange_close(set,owner);
    assert(!md_fd_exchange_acquire(set,id,120,files));
    allocation_budget=0;
    assert(!md_fd_exchange_received(worker));
    allocation_budget=-1;
    expect(files,10,b,FD_CLOEXEC); expect(files,11,NULL,0);
    assert(md_fd_exchange_received(worker)==-EALREADY);
    assert(!munmap(receipt,size));
    md_fd_exchange_release(worker);
    assert(!md_fd_exchange_begin(set,120,files,bases,NULL,&owner));
    assert(md_fd_exchange_id(owner)!=id);
    md_fd_exchange_close(set,owner);
    int cwd_bases[]={AT_FDCWD,7};
    owner=NULL;
    assert(md_fd_exchange_begin(set,120,files,cwd_bases,NULL,&owner)==-ENODATA && !owner);
    allocation_budget=0;
    assert(md_fd_exchange_begin(set,120,files,cwd_bases,a,&owner)==-ENOMEM && !owner);
    allocation_budget=-1;
    assert(!md_fd_exchange_begin(set,120,files,cwd_bases,a,&owner));
    assert(md_fd_exchange_cwd(owner)==1);
    worker=md_fd_exchange_acquire(set,md_fd_exchange_id(owner),120,files); assert(worker);
    put(files,7,a,0);
    md_fd_exchange_close(set,owner);
    assert(md_fd_exchange_input(worker,0)==a && md_fd_exchange_input(worker,1)==b);
    md_fd_exchange_release(worker);
    cwd_bases[1]=AT_FDCWD;
    assert(!md_fd_exchange_begin(set,120,files,cwd_bases,b,&owner));
    assert(md_fd_exchange_cwd(owner)==3 && md_fd_exchange_input(owner,0)==b
        && md_fd_exchange_input(owner,1)==b);
    md_fd_exchange_close(set,owner);
    md_fd_origins_release(files); md_fd_origins_release(other); md_fd_exchanges_destroy(set);
}
struct batch_reader {
    struct md_fd_origins *table;
    struct md_fs_anchor *a, *b;
    pthread_barrier_t start;
};
static void *read_batch(void *opaque) {
    struct batch_reader *r=opaque;
    int fds[]={40,41};
    /* EVENT_WAIT: both metadata owners are ready; runner deadline bounds failure. */
    pthread_barrier_wait(&r->start);
    for (unsigned i=0;i<10000;++i) {
        struct md_fs_anchor *pair[2];
        assert(!md_fd_origins_capture(r->table,fds,2,pair));
        assert((pair[0]==r->a && pair[1]==r->b) || (pair[0]==r->b && pair[1]==r->a));
        md_fs_anchor_release(pair[0]); md_fs_anchor_release(pair[1]);
    }
    return NULL;
}
static void batches(struct md_fs_anchor *a, struct md_fs_anchor *b) {
    struct md_fd_origins *source=md_fd_origins_new(), *target=md_fd_origins_new();
    assert(source && target);
    put(source,7,a,0); put(source,8,b,FD_CLOEXEC);
    int fds[]={7,8,900,-1,7}, destination[]={40,41,42,43,44};
    struct md_fd_object *origins[5]={0};
    allocation_budget=0;
    assert(!md_fd_origins_capture_objects(source,fds,5,origins));
    allocation_budget=-1;
    put(source,7,b,0); md_fd_origins_release(source);
    assert(origins[0]==md_fs_anchor_object(a) && origins[1]==md_fs_anchor_object(b)
        && !origins[2] && !origins[3] && origins[4]==md_fs_anchor_object(a));
    for (unsigned i=0;i<5;++i) put(target,destination[i],b,0);
    struct md_fd_origin_update updates[5]={0};
    for (int budget=0;budget<3;++budget) {
        allocation_budget=budget;
        assert(md_fd_origins_prepare_batch(target,origins,FD_CLOEXEC,5,updates)==-ENOMEM);
        allocation_budget=-1;
        for (unsigned i=0;i<5;++i) {
            assert(!updates[i].table && !updates[i].entry);
            expect(target,destination[i],b,0);
        }
    }
    for (unsigned pass=0;pass<3;++pass) {
        if (pass==2) { put(target,43,b,0); put(target,44,b,0); }
        assert(!md_fd_origins_prepare_batch(target,origins,FD_CLOEXEC,5,updates));
        allocation_budget=0;
        int duplicate[]={40,41,40}, invalid[]={40,-1};
        assert(md_fd_origins_finish_batch(updates,5,duplicate,3)==-EPROTO);
        assert(md_fd_origins_finish_batch(updates,5,invalid,2)==-EPROTO);
        assert(md_fd_origins_finish_batch(updates,5,destination,6)==-EINVAL);
        for (unsigned i=0;i<5;++i) {
            int changed=pass && !(pass==2 && i>=3);
            struct md_fs_anchor *expected=changed ? md_fs_anchor_from_object(origins[i]) : b;
            expect(target,destination[i],expected,changed && expected ? FD_CLOEXEC : 0);
        }
        unsigned received=pass==2 ? 3 : 5;
        assert(!md_fd_origins_finish_batch(updates,5,destination,received));
        allocation_budget=-1;
        for (unsigned i=0;i<received;++i)
            expect(target,destination[i],md_fs_anchor_from_object(origins[i]),origins[i] ? FD_CLOEXEC : 0);
        for (unsigned i=received;i<5;++i) expect(target,destination[i],b,0);
        /* Peeks prepare independent deliveries from the same immutable snapshot. */
        for (unsigned i=0;i<5;++i) assert(!updates[i].table && !updates[i].entry);
    }
    struct md_fd_object *unknowns[2]={0};
    allocation_budget=0;
    assert(!md_fd_origins_prepare_batch(target,unknowns,0,2,updates));
    assert(!md_fd_origins_finish_batch(updates,2,destination,2));
    expect(target,40,NULL,0); expect(target,41,NULL,0);
    allocation_budget=-1;
    assert(!md_fd_origins_prepare_batch(target,origins,0,5,updates));
    assert(!md_fd_origins_finish_batch(updates,5,NULL,0));
    expect(target,40,NULL,0);

    struct md_fd_origins *other=md_fd_origins_new(); assert(other);
    assert(!md_fd_origins_prepare(target,a,0,updates));
    assert(!md_fd_origins_prepare(other,b,0,updates+1));
    assert(md_fd_origins_finish_batch(updates,2,destination,2)==-EINVAL);
    expect(target,40,NULL,0); expect(other,41,NULL,0);
    md_fd_origins_finish(updates,-1); md_fd_origins_finish(updates+1,-1);
    md_fd_origins_release(other);
    for (unsigned i=0;i<5;++i) md_fd_object_release(origins[i]);

    put(target,40,a,0); put(target,41,b,0);
    struct batch_reader reader={.table=target,.a=a,.b=b};
    assert(!pthread_barrier_init(&reader.start,NULL,2));
    pthread_t thread; assert(!pthread_create(&thread,NULL,read_batch,&reader));
    /* EVENT_WAIT: enter the concurrent snapshot/publication test together. */
    pthread_barrier_wait(&reader.start);
    for (unsigned i=0;i<10000;++i) {
        struct md_fd_object *pair[]={md_fs_anchor_object(i%2 ? a : b),md_fs_anchor_object(i%2 ? b : a)};
        assert(!md_fd_origins_prepare_batch(target,pair,0,2,updates));
        assert(!md_fd_origins_finish_batch(updates,2,destination,2));
    }
    assert(!pthread_join(thread,NULL)); pthread_barrier_destroy(&reader.start);
    assert(!md_fd_origins_prepare_batch(target,unknowns,0,2,updates));
    md_fd_origins_release(target);
    allocation_budget=0;
    assert(!md_fd_origins_finish_batch(updates,2,destination,2));
    allocation_budget=-1;
}
static void deliveries(struct md_fs_anchor *a, struct md_fs_anchor *b) {
    unsigned before=descriptors();
    struct md_fd_delivery delivery={0};
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct md_fd_object *origins[]={md_fs_anchor_object(a),NULL,md_fs_anchor_object(b)};
    struct md_fd_origin_update updates[3];
    int numbers[3]={-9,-9,-9};
    int fds[]={open("/",O_PATH|O_CLOEXEC),open("/",O_PATH|O_CLOEXEC),open("/",O_PATH|O_CLOEXEC)};
    for (unsigned i=0;i<3;++i) { assert(fds[i]>=0); put(files,fds[i],b,0); }
    assert(md_fd_delivery_receipt(&delivery)==-1);
    assert(md_fd_delivery_publish(&delivery)==-EINVAL);
    assert(md_fd_delivery_prepare(&delivery,files,origins,0,0,updates,numbers)==-EINVAL);
    assert(md_fd_delivery_prepare(&delivery,files,origins,0,MD_FD_RECEIPT_MAX+1,updates,numbers)==-EINVAL);
    assert(md_fd_delivery_prepare(&delivery,files,origins,2,3,updates,numbers)==-EINVAL);
    unsigned prepared_before=descriptors();
    for (int budget=0;budget<2;++budget) {
        allocation_budget=budget;
        assert(md_fd_delivery_prepare(&delivery,files,origins,FD_CLOEXEC,3,updates,numbers)==-ENOMEM);
        allocation_budget=-1;
        assert(!delivery.capacity && md_fd_delivery_receipt(&delivery)==-1);
        for (unsigned i=0;i<3;++i) {
            assert(!updates[i].table && !updates[i].entry);
            expect(files,fds[i],b,0);
        }
        assert(descriptors()==prepared_before);
    }
    struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
    int first_free=0;
    while (fcntl(first_free,F_GETFD)>=0) ++first_free;
    assert(errno==EBADF && (rlim_t)first_free<limit.rlim_cur);
    struct rlimit exhausted=limit; exhausted.rlim_cur=(rlim_t)first_free;
    assert(!setrlimit(RLIMIT_NOFILE,&exhausted));
    int error=md_fd_delivery_prepare(&delivery,files,origins,0,3,updates,numbers);
    assert(!setrlimit(RLIMIT_NOFILE,&limit));
    assert(error==-EMFILE && !delivery.capacity && descriptors()==prepared_before);
    for (unsigned i=0;i<3;++i) {
        assert(!updates[i].table && !updates[i].entry);
        expect(files,fds[i],b,0);
    }
    for (unsigned count=0;count<=3;++count) {
        assert(!md_fd_delivery_prepare(&delivery,files,origins,FD_CLOEXEC,3,updates,numbers));
        int receipt_fd=md_fd_delivery_receipt(&delivery); assert(receipt_fd>=0);
        assert(md_fd_delivery_prepare(&delivery,files,origins,0,3,updates,numbers)==-EBUSY);
        assert(md_fd_delivery_receipt(&delivery)==receipt_fd);
        allocation_budget=0;
        assert(!md_fd_delivery_publish(&delivery));
        assert(!md_fd_receipt_reset(delivery.receipt,3));
        assert(!md_fd_delivery_publish(&delivery));
        struct cmsghdr *c=(void *)delivery.receipt->control;
        *c=(struct cmsghdr){.cmsg_len=CMSG_LEN(2*sizeof(int)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
        int invalid[]={fds[0],fds[0]};
        delivery.receipt->message.msg_controllen=CMSG_LEN(sizeof(invalid));
        memcpy(CMSG_DATA(c),invalid,sizeof(invalid));
        assert(md_fd_delivery_publish(&delivery)==-EPROTO);
        for (unsigned i=0;i<3;++i) expect(files,fds[i],b,0);
        assert(numbers[0]==-9);
        assert(!md_fd_receipt_reset(delivery.receipt,3));
        c=(void *)delivery.receipt->control;
        c->cmsg_len=CMSG_LEN(count*sizeof(int)); c->cmsg_level=SOL_SOCKET; c->cmsg_type=SCM_RIGHTS;
        memcpy(CMSG_DATA(c),fds,count*sizeof(int));
        delivery.receipt->message.msg_controllen=CMSG_LEN(count*sizeof(int));
        if (count<3) delivery.receipt->message.msg_flags=MSG_CTRUNC;
        /* An admitted delivery retains the table even after its task owner exits. */
        struct md_fd_origins *pin=md_fd_origins_pin(files);
        md_fd_origins_release(files);
        assert(md_fd_delivery_publish(&delivery)==(int)count);
        if (count) assert(md_fd_delivery_publish(&delivery)==-EALREADY);
        md_fd_delivery_dispose(&delivery);
        md_fd_delivery_dispose(&delivery);
        assert(!delivery.capacity && md_fd_delivery_receipt(&delivery)==-1);
        assert(fcntl(receipt_fd,F_GETFD)==-1 && errno==EBADF);
        for (unsigned i=0;i<3;++i) {
            struct md_fs_anchor *expected=i<count ? md_fs_anchor_from_object(origins[i]) : b;
            expect(pin,fds[i],expected,i<count && expected ? FD_CLOEXEC : 0);
            assert(fcntl(fds[i],F_GETFD)==FD_CLOEXEC);
        }
        allocation_budget=-1;
        md_fd_origins_unpin(pin);
        files=md_fd_origins_new(); assert(files);
        for (unsigned i=0;i<3;++i) { put(files,fds[i],b,0); numbers[i]=-9; }
    }
    md_fd_origins_release(files);
    for (unsigned i=0;i<3;++i) assert(!close(fds[i]));
    assert(descriptors()==before);
}
static void receipts(void) {
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    assert(md_fd_receipt_size(MD_FD_RECEIPT_MAX)<page);
    void *memory=mmap(NULL,page*2,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(memory!=MAP_FAILED && !mprotect((char *)memory+page,page,PROT_NONE));
    int output[MD_FD_RECEIPT_MAX+1];
    assert(!md_fd_receipt_size(0) && !md_fd_receipt_size(MD_FD_RECEIPT_MAX+1));
    assert(md_fd_receipt_reset(NULL,1)==-EINVAL);
    for (unsigned capacity=1;capacity<=MD_FD_RECEIPT_MAX;++capacity) {
        struct md_fd_receipt *receipt=(void *)((char *)memory+page-md_fd_receipt_size(capacity));
        allocation_budget=0;
        assert(!md_fd_receipt_reset(receipt,capacity));
        for (unsigned i=0;i<=MD_FD_RECEIPT_MAX;++i) output[i]=-9;
        assert(!md_fd_receipt_read(receipt,capacity,output) && output[0]==-9);
        struct cmsghdr *c=(void *)receipt->control;
        *c=(struct cmsghdr){.cmsg_len=CMSG_LEN(capacity*sizeof(int)),
            .cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
        int *numbers=(void *)CMSG_DATA(c);
        for (unsigned i=0;i<capacity;++i) numbers[i]=10+(int)i;
        /* Pointer fields are foreign process addresses, not parser inputs. */
        receipt->message.msg_control=(void *)1; receipt->message.msg_iov=(void *)1;
        assert(md_fd_receipt_read(receipt,capacity,output)==(int)capacity);
        for (unsigned i=0;i<capacity;++i) assert(output[i]==10+(int)i);
        assert(output[capacity]==-9);
        numbers[capacity-1]=-1;
        assert(md_fd_receipt_read(receipt,capacity,output)==-EPROTO);
        assert(output[capacity-1]==10+(int)capacity-1);
        numbers[capacity-1]=10+(int)capacity-1;
        if (capacity>2) {
            numbers[capacity-1]=numbers[1];
            assert(md_fd_receipt_read(receipt,capacity,output)==-EPROTO);
            assert(output[capacity-1]==10+(int)capacity-1);
            numbers[capacity-1]=10+(int)capacity-1;
        }
        ++receipt->message.msg_controllen;
        assert(md_fd_receipt_read(receipt,capacity,output)==-EPROTO);
        --receipt->message.msg_controllen;
        --c->cmsg_len;
        assert(md_fd_receipt_read(receipt,capacity,output)==-EPROTO);
        ++c->cmsg_len;
        c->cmsg_type=SCM_CREDENTIALS;
        assert(md_fd_receipt_read(receipt,capacity,output)==-EPROTO);
        assert(output[capacity-1]==10+(int)capacity-1 && output[capacity]==-9);
        c->cmsg_type=SCM_RIGHTS;
        c->cmsg_len=CMSG_LEN(sizeof(int));
        receipt->message.msg_controllen=CMSG_LEN(sizeof(int));
        receipt->message.msg_flags=MSG_CTRUNC;
        assert(md_fd_receipt_read(receipt,capacity,output)==1 && output[0]==10);
        assert(!md_fd_receipt_reset(receipt,capacity));
        assert(!md_fd_receipt_read(receipt,capacity,output));
        allocation_budget=-1;
    }
    assert(!munmap(memory,page*2));
}
static void expect_export(struct md_fd_origins *table, struct md_fd_channel *expected, int number) {
    struct md_fd_channel *actual=NULL; int slot=-2;
    assert(!md_fd_origins_channel(table,&slot,&actual) && actual==expected && slot==number);
    md_fd_channel_release(actual);
}
static void export_messages(void) {
    unsigned before=descriptors();
    for (unsigned mode=0;mode<5;++mode) {
        struct md_fd_channel *channel=NULL; assert(!md_fd_channel_new(&channel));
        int pipefd[2]; assert(!pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
        struct md_fd_channel_capture capture={0};
        if (mode!=4) assert(!md_fd_channel_capture_begin(channel,getpid(),1,&capture));
        uint64_t token=mode==1 ? 0 : mode==4 ? 41 : capture.request;
        struct iovec vector={&token,mode==2 ? 1 : sizeof(token)};
        union { struct cmsghdr align; char data[CMSG_SPACE(3*sizeof(int))]; } control={0};
        unsigned count=mode==3 ? 3 : 1;
        struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,
            .msg_control=control.data,.msg_controllen=CMSG_SPACE(count*sizeof(int))};
        struct cmsghdr *header=CMSG_FIRSTHDR(&message);
        header->cmsg_level=SOL_SOCKET; header->cmsg_type=SCM_RIGHTS;
        header->cmsg_len=CMSG_LEN(count*sizeof(int));
        for (unsigned i=0;i<count;++i) memcpy(CMSG_DATA(header)+i*sizeof(int),pipefd+1,sizeof(int));
        assert(sendmsg(md_fd_channel_endpoint(channel),&message,MSG_NOSIGNAL)==(ssize_t)vector.iov_len);
        assert(!close(pipefd[1]));
        char byte;
        assert(read(pipefd[0],&byte,1)==-1 && errno==EAGAIN);
        if (mode!=4) {
            struct md_fd_channel_input received;
            int result=md_fd_channel_capture_finish(&capture,&received);
            if (!mode) {
                assert(!result && received.request==token && received.peer==getpid() && received.count==1);
                assert(fcntl(received.fd[0],F_GETFD)==FD_CLOEXEC);
                md_fd_channel_input_close(&received);
            } else assert(result==-EPROTO && !received.count);
            assert(!md_fd_channel_capture_begin(channel,getpid(),1,&capture));
            assert(md_fd_channel_capture_finish(&capture,&received)==-EPROTO && !received.count);
        }
        md_fd_channel_release(channel);
        assert(read(pipefd[0],&byte,1)==0 && !close(pipefd[0]) && descriptors()==before);
    }
    puts("PASS export messages: kernel sender, exact payload, malformed/truncated rights and queued-owner release preserve EOF");
}
static void capture_send(struct md_fd_channel *channel, uint64_t token, int fd, unsigned count) {
    struct iovec vector={&token,sizeof(token)};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(2*sizeof(int))]; } control={0};
    assert(count && count<=2);
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,
        .msg_control=control.bytes,.msg_controllen=CMSG_SPACE(count*sizeof(int))};
    struct cmsghdr *header=CMSG_FIRSTHDR(&message);
    header->cmsg_level=SOL_SOCKET; header->cmsg_type=SCM_RIGHTS;
    header->cmsg_len=CMSG_LEN(count*sizeof(int));
    for (unsigned i=0;i<count;++i) memcpy(CMSG_DATA(header)+i*sizeof(fd),&fd,sizeof(fd));
    assert(sendmsg(md_fd_channel_endpoint(channel),&message,MSG_NOSIGNAL)==sizeof(token));
}
static void channel_captures(void) {
    unsigned before=descriptors();
    struct md_fd_channel *channel=NULL,*other=NULL;
    assert(!md_fd_channel_new(&channel) && !md_fd_channel_new(&other));
    struct md_fd_channel_capture leases[6]={0};
    int pipes[6][2];
    for (unsigned i=0;i<6;++i) assert(!pipe2(pipes[i],O_CLOEXEC|O_NONBLOCK));
    allocation_budget=0;
    for (unsigned i=0;i<6;++i)
        assert(!md_fd_channel_capture_begin(i==5 ? other : channel,
            getpid()+(i==4),i==3 ? 2 : 1,leases+i));
    assert(leases[0].request==leases[5].request);
    assert(md_fd_channel_capture_begin(channel,getpid(),1,leases)==-EINVAL);
    /* Reverse delivery crosses independent consumers and fork-copied tables.
     * Cancelling one owner must retain packets accepted for every other one. */
    for (int i=5;i>=0;--i) capture_send(i==5 ? other : channel,leases[i].request,pipes[i][1],1);
    capture_send(channel,leases[1].request,pipes[1][1],1);
    capture_send(channel,UINT64_MAX,pipes[3][1],1);
    for (unsigned i=0;i<6;++i) assert(!close(pipes[i][1]));
    char byte;
    for (unsigned i=0;i<6;++i) assert(read(pipes[i][0],&byte,1)==-1 && errno==EAGAIN);
    md_fd_channel_capture_cancel(leases+2);
    assert(!leases[2].channel && read(pipes[2][0],&byte,1)==0);
    for (unsigned i=0;i<6;++i) {
        if (i==2) continue;
        struct md_fd_channel_input result;
        int error=md_fd_channel_capture_finish(leases+i,&result);
        assert(!leases[i].channel);
        if (i==3 || i==4) assert(error==-EPROTO && !result.count);
        else {
            assert(!error && result.count==1 && result.peer==getpid());
            assert(read(pipes[i][0],&byte,1)==-1 && errno==EAGAIN);
            md_fd_channel_input_close(&result);
        }
        assert(read(pipes[i][0],&byte,1)==0);
    }
    /* A terminal sender may never have queued anything. Its retired token
     * cannot capture a later packet or consume a new owner's valid vector. */
    assert(!md_fd_channel_capture_begin(channel,getpid(),1,leases));
    uint64_t retired=leases[0].request;
    md_fd_channel_capture_cancel(leases);
    assert(!close(pipes[2][0]));
    assert(!pipe2(pipes[2],O_CLOEXEC|O_NONBLOCK));
    assert(!md_fd_channel_capture_begin(channel,getpid(),1,leases));
    capture_send(channel,retired,pipes[2][1],1);
    capture_send(channel,leases[0].request,pipes[2][1],1);
    assert(!close(pipes[2][1]));
    struct md_fd_channel_input result;
    assert(!md_fd_channel_capture_finish(leases,&result));
    md_fd_channel_input_close(&result);
    assert(read(pipes[2][0],&byte,1)==0);
    allocation_budget=-1;
    for (unsigned i=0;i<6;++i) assert(!close(pipes[i][0]));
    md_fd_channel_release(channel); md_fd_channel_release(other);
    assert(descriptors()==before);
    puts("PASS channel captures: independent owners, reverse/duplicate delivery, wrong peer/count/channel, cancellation, late tokens and exact EOF without allocation");
}
static void vector_custody(void) {
    unsigned before=descriptors();
    for (unsigned count=1;count<=253;count+=252) for (int cancel=0;cancel<2;++cancel) {
        struct md_fd_channel *channel=NULL; assert(!md_fd_channel_new(&channel));
        int pipefd[2]; assert(!pipe2(pipefd,O_NONBLOCK|O_CLOEXEC));
        int output[253];
        struct md_fd_channel_capture capture={0};
        assert(!md_fd_channel_capture_vector_begin(channel,getpid(),count,output,&capture));
        union { struct cmsghdr align; char bytes[CMSG_SPACE(253*sizeof(int))]; } control={0};
        struct iovec vector={&capture.request,sizeof(capture.request)};
        struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,.msg_control=control.bytes,
            .msg_controllen=CMSG_SPACE(count*sizeof(int))};
        control.align=(struct cmsghdr){.cmsg_len=CMSG_LEN(count*sizeof(int)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
        for (unsigned i=0;i<count;++i) memcpy(CMSG_DATA(&control.align)+i*sizeof(int),&pipefd[1],sizeof(int));
        assert(sendmsg(md_fd_channel_endpoint(channel),&message,MSG_NOSIGNAL)==sizeof(uint64_t));
        assert(!close(pipefd[1]));
        if (cancel) md_fd_channel_capture_cancel(&capture);
        else {
            assert(!md_fd_channel_capture_vector_finish(&capture));
            for (unsigned i=0;i<count;++i) assert(!close(output[i]));
        }
        char byte; assert(read(pipefd[0],&byte,1)==0);
        close(pipefd[0]); md_fd_channel_release(channel);
        assert(descriptors()==before);
    }
    for (int delivered=0;delivered<2;++delivered) {
        struct md_fd_channel *channel=NULL; assert(!md_fd_channel_new(&channel));
        struct md_fd_origins *files=md_fd_origins_new(); assert(files);
        int endpoint=fcntl(md_fd_channel_endpoint(channel),F_DUPFD_CLOEXEC,0); assert(endpoint>=0);
        struct md_fd_origin_update update;
        assert(!md_fd_origins_prepare_channel(files,channel,FD_CLOEXEC,&update));
        md_fd_origins_finish(&update,endpoint);
        int pipefd[2]; assert(!pipe2(pipefd,O_NONBLOCK|O_CLOEXEC));
        int sources[2]={pipefd[1],pipefd[1]};
        struct md_fd_return handoff={0};
        assert(!md_fd_return_vector_begin(&handoff,files,sources,2,NULL,NULL));
        assert(md_fd_return_progress(&handoff,NULL)==MD_FD_RETURN_READY);
        assert(!close(pipefd[1]));
        int installed=-1;
        if (delivered) {
            uint64_t token=0; struct iovec vector={&token,sizeof(token)};
            union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
            struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,.msg_control=control.bytes,
                .msg_controllen=CMSG_LEN(sizeof(int))};
            assert(recvmsg(endpoint,&message,MSG_CMSG_CLOEXEC)==sizeof(token) && token==handoff.request);
            assert(message.msg_flags&MSG_CTRUNC);
            memcpy(&installed,CMSG_DATA(&control.align),sizeof(installed));
            assert(fcntl(installed,F_GETFD)==FD_CLOEXEC);
        }
        /* Terminal cancellation never rolls back the kernel-published prefix. */
        assert(!md_fd_return_cancel(&handoff));
        if (installed>=0) assert(fcntl(installed,F_GETFD)==FD_CLOEXEC && !close(installed));
        char byte; assert(read(pipefd[0],&byte,1)==0);
        close(pipefd[0]); close(endpoint); md_fd_origins_release(files); md_fd_channel_release(channel);
        assert(descriptors()==before);
    }
    puts("PASS ancillary vector custody: 1/253 captures, cancelled queues, partial kernel publication and retained caller FDs");
}
static void infrastructure(void) {
    unsigned before=descriptors();
    struct md_fd_channel *channel=NULL;
    allocation_budget=0; assert(md_fd_channel_new(&channel)==-ENOMEM && !channel);
    allocation_budget=-1; assert(!md_fd_channel_new(&channel));
    struct md_fd_origins *table=md_fd_origins_new(); assert(table);
    struct md_fd_origin_update update;
    for (int budget=0;budget<2;++budget) {
        allocation_budget=budget;
        assert(md_fd_origins_prepare_channel(table,channel,FD_CLOEXEC,&update)==-ENOMEM);
        allocation_budget=-1; expect_export(table,NULL,-1);
    }
    assert(!md_fd_origins_prepare_channel(table,channel,FD_CLOEXEC,&update));
    allocation_budget=0; md_fd_origins_finish(&update,10); allocation_budget=-1;
    expect_export(table,channel,10);
    struct md_fd_mutation duplicate={0}; unsigned long args[6]={10,11,O_CLOEXEC};
    assert(!md_fd_mutation_prepare(table,SYS_dup3,args,&duplicate));
    allocation_budget=0; assert(!md_fd_mutation_finish(&duplicate,table,11)); allocation_budget=-1;
    assert(!md_fd_origins_close_range(table,10,10,0)); expect_export(table,channel,11);
    struct md_fd_origins *copy=md_fd_origins_fork(table,0), *shared=md_fd_origins_fork(table,1);
    assert(copy && shared==table);
    md_fd_origins_exec(copy); expect_export(copy,NULL,-1); expect_export(table,channel,11);
    assert(!md_fd_origins_set_flags(table,11,0)); md_fd_origins_exec(table);
    expect_export(shared,channel,11);
    put(table,11,NULL,0); expect_export(table,NULL,-1);
    assert(!md_fd_origins_prepare_channel(table,channel,FD_CLOEXEC,&update));
    md_fd_origins_finish(&update,12);
    struct md_fd_mutation closing={0}; args[0]=12;
    assert(!md_fd_mutation_prepare(table,SYS_close,args,&closing));
    assert(!md_fd_mutation_abandon(&closing,table));
    struct md_fd_channel *unknown=NULL; int slot;
    assert(md_fd_origins_channel(table,&slot,&unknown)==-ESTALE && !unknown);
    md_fd_origins_exec(table);
    assert(md_fd_origins_channel(table,&slot,&unknown)==-ESTALE && !unknown);
    assert(!md_fd_origins_close_range(table,12,12,0)); expect_export(table,NULL,-1);
    md_fd_origins_release(shared); md_fd_origins_release(copy); md_fd_origins_release(table);
    md_fd_channel_release(channel); assert(descriptors()==before);
    puts("PASS export infrastructure: shared/copied tables, dup, replacement, flags, exec, ambiguous terminal fail-closed and allocation rollback");
}
static void channel_reconciliation(void) {
    unsigned before=descriptors();
    for (unsigned mode=0;mode<6;++mode) {
        struct md_fd_channel *channel=NULL,*different=NULL;
        assert(!md_fd_channel_new(&channel) && !md_fd_channel_new(&different));
        struct md_fd_origins *files=md_fd_origins_new(); assert(files);
        int number=fcntl(md_fd_channel_endpoint(channel),F_DUPFD_CLOEXEC,0); assert(number>=0);
        struct md_fd_origin_update update={0};
        assert(!md_fd_origins_prepare_channel(files,channel,FD_CLOEXEC,&update));
        md_fd_origins_finish(&update,number);
        assert(!md_fd_origins_abandon_range(files,number,number));
        if (mode==1) assert(!fcntl(number,F_SETFD,0));
        if (mode==2) {
            int file=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(file>=0);
            assert(dup3(file,number,O_CLOEXEC)==number && !close(file));
        }
        if (mode==3) assert(!close(number));
        if (mode==4) assert(dup3(md_fd_channel_endpoint(different),number,O_CLOEXEC)==number);
        unsigned present=descriptors();
        struct md_fd_channel *candidate=NULL; int slot=-1;
        allocation_budget=0;
        assert(!md_fd_origins_channel_candidate(files,&slot,&candidate) && candidate==channel && slot==number);
        struct md_fd_channel_observation o={.error=-EPERM};
        assert(md_fd_channel_resolve(candidate,files,number,&o)==-EPERM);
        struct md_fd_channel *unresolved=NULL;
        assert(md_fd_origins_channel(files,&slot,&unresolved)==-ESTALE && !unresolved);
        o=(struct md_fd_channel_observation){.length=sizeof(o.cookie)};
        int flags=fcntl(number,F_GETFD);
        if (flags<0) o.error=-errno;
        else {
            o.flags=(unsigned)flags;
            if (getsockopt(number,SOL_SOCKET,SO_COOKIE,&o.cookie,&o.length)) o.error=-errno;
        }
        if (mode==5) {
            struct md_fd_channel_observation invalid=o; invalid.length=1;
            assert(md_fd_channel_resolve(candidate,files,number,&invalid)==-EPROTO);
        }
        assert(!md_fd_channel_resolve(candidate,files,number,&o));
        assert(md_fd_channel_resolve(candidate,files,number,&o)==-ESTALE);
        md_fd_channel_release(candidate);
        allocation_budget=-1;
        assert(descriptors()==present);
        int same=mode<2 || mode==5;
        expect_export(files,same ? channel : NULL,same ? number : -1);
        if (mode!=3) assert(fcntl(number,F_GETFD)==flags && !close(number));
        md_fd_origins_release(files); md_fd_channel_release(channel); md_fd_channel_release(different);
        assert(descriptors()==before);
    }
    puts("PASS channel reconciliation: same/closed/replaced slot, CLOEXEC, denied observation, malformed reply, no allocation or guest close");
}
static void received_custody(void) {
    unsigned before=descriptors();
    int pipes[2]; assert(!pipe2(pipes,O_CLOEXEC|O_NONBLOCK));
    struct md_received_fds *sources=NULL;
    assert(md_received_fds_reserve(&sources,0)==-EINVAL && !sources);
    assert(md_received_fds_reserve(&sources,UINT_MAX)==-EINVAL && !sources);
    assert(!md_received_fds_reserve(&sources,1));
    sources->fds[sources->count++]=pipes[1];
    assert(!md_received_fds_reserve(&sources,254) && sources->count==1 && sources->fds[0]==pipes[1]);
    allocation_budget=0;
    struct md_received_fds *inspection=md_received_fds_retain(sources);
    assert(inspection==sources && sources->references==2);
    assert(md_received_fds_reserve(&sources,1024)==-EBUSY);
    md_received_fds_release(sources);
    char byte; assert(read(pipes[0],&byte,1)==-1 && errno==EAGAIN);
    assert(inspection->references==1 && fcntl(inspection->fds[0],F_GETFD)>=0);
    md_received_fds_release(inspection);
    assert(read(pipes[0],&byte,1)==0);
    allocation_budget=-1;
    assert(!close(pipes[0]) && descriptors()==before);
    puts("PASS received FD custody: immutable shared vector, allocation-free pins, owner cancellation and last-reader EOF");
}
static void received_origins(struct md_fs_anchor *a, struct md_fs_anchor *b) {
    unsigned before=descriptors();
    struct md_received_fds *sources=NULL;
    assert(!md_received_fds_reserve(&sources,4));
    for (unsigned i=0;i<4;++i) {
        int fd=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(fd>=0);
        sources->fds[sources->count++]=fd;
    }
    struct md_fd_object *origins[]={md_fs_anchor_object(a),NULL,md_fs_anchor_object(b),md_fs_anchor_object(a)};
    struct md_fd_object *unknown[4]={0}, *selected[4];
    assert(md_received_fds_annotate(sources,origins,3)==-EINVAL);
    allocation_budget=0;
    assert(!md_received_fds_annotate(sources,unknown,4) && !sources->origins);
    assert(md_received_fds_annotate(sources,origins,4)==-ENOMEM && !sources->origins);
    allocation_budget=1;
    assert(!md_received_fds_annotate(sources,origins,4) && !allocation_budget);
    allocation_budget=0;
    assert(md_received_fds_annotate(sources,unknown,4)==-EBUSY);
    assert(md_received_fds_reserve(&sources,4)==-EBUSY);
    int indices[]={3,2,1,3}, output[]={-1,-1,-1,-1};
    assert(md_received_fds_select(sources,indices,4,output,NULL)==-EOPNOTSUPP && output[0]==-1);
    indices[3]=4;
    assert(md_received_fds_select(sources,indices,4,output,selected)==-EINVAL && output[0]==-1);
    indices[3]=3;
    assert(!md_received_fds_select(sources,indices,4,output,selected));
    assert(selected[0]==md_fs_anchor_object(a) && selected[1]==md_fs_anchor_object(b)
        && !selected[2] && selected[3]==md_fs_anchor_object(a));
    for (unsigned i=0;i<4;++i) assert(output[i]==sources->fds[indices[i]]);
    struct md_received_fds *retained=md_received_fds_retain(sources);
    assert(md_received_fds_annotate(sources,origins,4)==-EBUSY);
    md_received_fds_release(sources);
    assert(!md_received_fds_select(retained,indices,4,output,selected));
    md_received_fds_release(retained);
    allocation_budget=-1;
    assert(descriptors()==before);
    puts("PASS received origins: one lazy allocation, unknown/repeated/reordered selections, immutable custody, fail-closed unannotated consumers and rollback");
}
static void vector_preparation(struct md_fs_anchor *a, struct md_fs_anchor *b, int observed) {
    unsigned before=descriptors();
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct md_fd_object *origins[]={md_fs_anchor_object(a),md_fs_anchor_object(b),NULL};
    struct md_fd_origin_update updates[3]; int numbers[3], source=open("/dev/null",O_RDONLY|O_CLOEXEC);
    assert(source>=0); int sources[]={source,source,source};
    union { struct cmsghdr align; char bytes[512]; } storage={0};
    struct md_fd_receipt *memory=(void *)storage.bytes;
    assert(md_fd_receipt_size(3)<=sizeof(storage) && !md_fd_receipt_reset(memory,3));
    struct md_fd_table_observation observation={0};
    struct md_fd_return_publication receipt={.origins=origins,.updates=updates,.numbers=numbers,
        .memory=observed ? NULL : memory,.observation=observed ? &observation : NULL,
        .directory=source,.flags=FD_CLOEXEC};
    /* One bucket array, one entry per known origin, no entry for unknowns. */
    for (int budget=0;budget<3;++budget) {
        struct md_fd_origins *candidate=md_fd_origins_new(); assert(candidate);
        struct md_fd_return operation={0}; allocation_budget=budget;
        assert(md_fd_return_vector_begin(&operation,candidate,sources,3,&receipt,NULL)==-ENOMEM);
        allocation_budget=-1;
        assert(!operation.files.table && !operation.delivery.capacity && !operation.vector);
        assert(!md_fd_return_cancel(&operation));
        struct md_fd_order turn={0}; assert(md_fd_order_begin(candidate,&turn,NULL)==1);
        assert(!md_fd_order_cancel(&turn));
        md_fd_origins_release(candidate);
    }
    struct md_fd_order blocking={0}; assert(md_fd_order_begin(files,&blocking,NULL)==1);
    assert(!md_fd_order_start(&blocking));
    struct md_fd_return operation={0};
    assert(!md_fd_return_vector_begin(&operation,files,sources,3,&receipt,NULL));
    assert(md_fd_return_progress(&operation,NULL)==MD_FD_RETURN_WAIT);
    allocation_budget=0;
    assert(!md_fd_return_cancel(&operation) && !md_fd_order_complete(&blocking));
    assert(!observation.capacity);
    allocation_budget=-1;
    md_fd_origins_release(files); close(source); assert(descriptors()==before);
    printf("PASS annotated return preparation observed=%d: allocation rollback, queued cancellation and no admission/observation before the destination turn\n",observed);
}
static void observed_delivery(struct md_fs_anchor *a, struct md_fs_anchor *b) {
    unsigned before=descriptors();
    int directory=open("/proc/self/fd",O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(directory>=0);
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct md_fd_object *origins[]={md_fs_anchor_object(a),md_fs_anchor_object(b),NULL};
    struct md_fd_origin_update updates[3]; int numbers[3];
    struct md_fd_table_observation observation={0};
    struct md_fd_delivery delivery={0};
    assert(!md_fd_delivery_prepare_observed(&delivery,files,origins,FD_CLOEXEC,3,updates,numbers,&observation));
    allocation_budget=0;
    assert(md_fd_delivery_publish(&delivery)==-EINVAL);
    assert(!md_fd_table_observation_begin(&observation,directory,3));
    assert(!md_fd_delivery_publish(&delivery));
    for (unsigned i=0;i<2;++i) assert(dup3(directory,observation.candidates[i],O_CLOEXEC)==observation.candidates[i]);
    assert(md_fd_delivery_publish(&delivery)==2);
    assert(md_fd_delivery_publish(&delivery)==-EALREADY);
    for (unsigned i=0;i<2;++i) {
        assert(numbers[i]==observation.candidates[i]);
        unsigned flags=0; struct md_fs_anchor *actual=md_fd_origins_get(files,numbers[i],&flags);
        assert(md_fs_anchor_object(actual)==origins[i] && flags==FD_CLOEXEC); md_fs_anchor_release(actual);
        assert(!close(numbers[i]));
    }
    md_fd_delivery_dispose(&delivery); md_fd_origins_release(files); close(directory);
    allocation_budget=-1;
    assert(descriptors()==before);
    puts("PASS observed delivery: allocation-free capture, empty retry, partial publication and once-only ownership");
}
static void observed_return_failure(struct md_fs_anchor *a) {
    unsigned before=descriptors();
    struct md_fd_channel *channel=NULL; assert(!md_fd_channel_new(&channel));
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    int endpoint=fcntl(md_fd_channel_endpoint(channel),F_DUPFD_CLOEXEC,0); assert(endpoint>=0);
    struct md_fd_origin_update update;
    assert(!md_fd_origins_prepare_channel(files,channel,FD_CLOEXEC,&update));
    md_fd_origins_finish(&update,endpoint);
    int source=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(source>=0);
    for (unsigned offered=0;offered<2;++offered) {
        int directory=offered ? open("/proc/self/fd",O_RDONLY|O_DIRECTORY|O_CLOEXEC) : source;
        assert(directory>=0);
        struct md_fd_table_observation observation={0};
        int number=-19;
        struct md_fd_object *object=md_fs_anchor_object(a);
        struct md_fd_return_publication publication={.origins=&object,.updates=&update,.numbers=&number,
            .observation=&observation,.directory=directory,.flags=FD_CLOEXEC};
        struct md_fd_return operation={0};
        assert(!md_fd_return_vector_begin(&operation,files,&source,1,&publication,NULL));
        assert(md_fd_return_progress(&operation,NULL)==(offered ? MD_FD_RETURN_READY : -ENOTDIR));
        struct md_fd_order next={0}; assert(!md_fd_order_begin(files,&next,NULL));
        if (offered) assert(!close(directory));
        allocation_budget=0;
        assert(md_fd_return_cancel(&operation)==(offered ? -EBADF : 0));
        assert(!operation.files.table && !operation.channel && !operation.delivery.capacity && number==-19);
        assert(md_fd_order_state(&next)==MD_RESOURCE_ORDER_READY && !md_fd_order_cancel(&next));
        assert(!md_fd_channel_output_discard(channel));
        allocation_budget=-1;
    }
    close(source); close(endpoint); md_fd_origins_release(files); md_fd_channel_release(channel);
    assert(descriptors()==before);
    puts("PASS observed return failure: failed arming/lost directory preserve explicit errors, drain queued rights and release table/channel turns");
}
static struct md_fd_message *message_capture(struct md_fd_origins *source, unsigned count) {
    int fds[MD_FD_RECEIPT_MAX];
    for (unsigned i=0;i<count;++i) fds[i]=7+i%3;
    struct md_fd_order turn={0};
    assert(md_fd_order_begin(source,&turn,NULL)==1 && !md_fd_order_start(&turn));
    struct md_fd_message *record=NULL;
    allocation_budget=1;
    assert(!md_fd_message_capture(source,fds,count,&record) && !allocation_budget);
    allocation_budget=-1;
    assert(!md_fd_order_complete(&turn));
    return record;
}
static void message_vector(const struct md_fd_message *record, struct md_fs_anchor *a,
        struct md_fs_anchor *b) {
    assert(md_fd_message_count(record)==MD_FD_RECEIPT_MAX);
    struct md_fd_object *const *origins=md_fd_message_origins(record);
    for (unsigned i=0;i<MD_FD_RECEIPT_MAX;++i)
        assert(origins[i]==md_fs_anchor_object(i%3==0 ? a : i%3==1 ? b : NULL));
}
static void message_custody(struct md_fs_anchor *a, struct md_fs_anchor *b) {
    unsigned before=descriptors();
    struct md_fd_origins *source=md_fd_origins_new(); assert(source);
    put(source,7,a,0); put(source,8,b,0);
    struct md_fd_messages *messages=NULL;
    struct md_fd_message *failed=NULL;
    int number=7;
    allocation_budget=0;
    assert(md_fd_messages_new(&messages)==-ENOMEM && !messages);
    assert(md_fd_message_capture(source,&number,1,&failed)==-ENOMEM && !failed);
    assert(md_fd_message_capture(source,&number,0,&failed)==-EINVAL && !failed);
    assert(md_fd_message_capture(source,&number,UINT_MAX,&failed)==-EINVAL && !failed);
    allocation_budget=-1;
    assert(!md_fd_messages_new(&messages) && md_fd_messages_fd(messages)==-1);
    assert(!md_fd_messages_dispatch(messages) && !md_fd_messages_count(messages));
    struct md_fd_message *record=message_capture(source,MD_FD_RECEIPT_MAX);
    struct md_fd_message *duplicate=message_capture(source,MD_FD_RECEIPT_MAX);
    struct md_fd_message *replacement=message_capture(source,MD_FD_RECEIPT_MAX);
    put(source,7,b,0); md_fd_origins_release(source);
    message_vector(record,a,b);
    int carrier[2], pipefd[2];
    assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,carrier)
        && !pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
    struct md_ipc_connection connection={.link={1,2,3},.side=1}, wrong=connection;
    assert(md_fd_messages_publish(messages,record,&connection,pipefd[0],pipefd[0])==-ENOTSOCK);
    assert(md_fd_messages_publish(messages,record,&connection,carrier[0],pipefd[1])==-EINVAL);
    assert(!fcntl(pipefd[0],F_SETFD,0));
    assert(md_fd_messages_publish(messages,record,&connection,carrier[0],pipefd[0])==-EINVAL);
    assert(!fcntl(pipefd[0],F_SETFD,FD_CLOEXEC));
    assert(md_fd_messages_publish(messages,record,&connection,carrier[0],-1)==-EBADF);
    assert(md_fd_messages_fd(messages)==-1 && !md_fd_messages_count(messages));
    /* Cold admission cannot hide EMFILE or consume the caller's monitor. */
    struct rlimit old, limited;
    assert(!getrlimit(RLIMIT_NOFILE,&old)); limited=old;
    if (limited.rlim_cur>64) limited.rlim_cur=64;
    assert(!setrlimit(RLIMIT_NOFILE,&limited));
    int fillers[64], n=0, fd;
    while ((fd=fcntl(carrier[0],F_DUPFD_CLOEXEC,0))>=0) { assert(n<64); fillers[n++]=fd; }
    assert(errno==EMFILE);
    assert(md_fd_messages_publish(messages,record,&connection,carrier[0],pipefd[0])==-EMFILE);
    assert(md_fd_messages_fd(messages)==-1 && !md_fd_messages_count(messages)
        && fcntl(pipefd[0],F_GETFD)==FD_CLOEXEC);
    assert(!setrlimit(RLIMIT_NOFILE,&old));
    while (n) assert(!close(fillers[--n]));
    allocation_budget=0;
    assert(!md_fd_messages_publish(messages,record,&connection,carrier[0],pipefd[0]));
    assert(md_fd_messages_publish(messages,duplicate,&connection,carrier[0],pipefd[0])==-EEXIST);
    assert(md_fd_messages_publish(messages,record,&connection,carrier[0],pipefd[0])==-ESTALE);
    struct md_fd_message *pin=NULL;
    wrong.side=0;
    assert(md_fd_messages_find(messages,&wrong,carrier[0],&pin)==-EXDEV && !pin);
    wrong=connection; ++wrong.link[0];
    assert(md_fd_messages_find(messages,&wrong,carrier[0],&pin)==-EXDEV && !pin);
    assert(md_fd_messages_find(messages,&connection,carrier[1],&pin)==-ENODATA && !pin);
    assert(!md_fd_messages_find(messages,&connection,carrier[0],&pin) && pin==record);
    md_fd_message_release(record);
    assert(!close(pipefd[1]) && md_fd_messages_dispatch(messages)==1);
    assert(!md_fd_messages_count(messages) && !md_fd_messages_dispatch(messages));
    assert(md_fd_messages_find(messages,&connection,carrier[0],&failed)==-ENODATA && !failed);
    message_vector(pin,a,b);
    assert(md_fd_messages_publish(messages,pin,&connection,carrier[0],-1)==-ESTALE);
    md_fd_message_release(pin);
    /* Queued readiness from a withdrawn record cannot expire its successor,
     * even when the same carrier cookie is reused by the authority. */
    assert(!pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
    assert(!md_fd_messages_publish(messages,duplicate,&connection,carrier[0],pipefd[0]));
    assert(!close(pipefd[1]) && !md_fd_messages_withdraw(messages,duplicate));
    assert(md_fd_messages_withdraw(messages,duplicate)==-ENOENT);
    md_fd_message_release(duplicate);
    assert(!pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
    assert(!md_fd_messages_publish(messages,replacement,&connection,carrier[0],pipefd[0]));
    assert(!md_fd_messages_dispatch(messages) && md_fd_messages_count(messages)==1);
    assert(write(pipefd[1],"x",1)==1 && md_fd_messages_dispatch(messages)==-EPROTO);
    assert(md_fd_messages_count(messages)==1 && !md_fd_messages_dispatch(messages));
    assert(!md_fd_messages_find(messages,&connection,carrier[0],&failed) && failed==replacement);
    md_fd_message_release(failed);
    assert(!close(pipefd[1]) && md_fd_messages_dispatch(messages)==1);
    md_fd_messages_free(messages);
    message_vector(replacement,a,b); md_fd_message_release(replacement);
    assert(!close(carrier[0]) && !close(carrier[1]));
    allocation_budget=-1;
    assert(descriptors()==before);
    puts("PASS message origins: exact-sized capture, allocation/EMFILE rollback, directional lookup, pinned EOF retirement, stale events and malformed monitor");
}
static void message_batches(struct md_fs_anchor *a) {
    unsigned before=descriptors();
    struct md_fd_origins *source=md_fd_origins_new(); assert(source);
    put(source,7,a,0);
    struct md_fd_messages *messages=NULL; assert(!md_fd_messages_new(&messages));
    struct md_ipc_connection connection={.link={4},.side=0};
    enum { RECORDS=80 };
    int writers[RECORDS];
    for (unsigned i=0;i<RECORDS;++i) {
        struct md_fd_message *record=message_capture(source,1);
        int carrier[2], pipefd[2];
        assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,carrier)
            && !pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
        assert(!md_fd_messages_publish(messages,record,&connection,carrier[0],pipefd[0]));
        md_fd_message_release(record);
        assert(!close(carrier[0]) && !close(carrier[1])); writers[i]=pipefd[1];
    }
    assert(md_fd_messages_count(messages)==RECORDS);
    allocation_budget=0;
    for (unsigned i=0;i<RECORDS;++i) assert(!close(writers[i]));
    for (unsigned i=0;i<RECORDS/16;++i) assert(md_fd_messages_dispatch(messages)==16);
    assert(!md_fd_messages_dispatch(messages) && !md_fd_messages_count(messages));
    allocation_budget=-1;
    struct md_fd_message *record=message_capture(source,1);
    md_fd_origins_release(source);
    int carrier[2], pipefd[2];
    assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,carrier)
        && !pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
    assert(!md_fd_messages_publish(messages,record,&connection,carrier[0],pipefd[0]));
    allocation_budget=0;
    md_fd_messages_free(messages);
    assert(md_fd_message_count(record)==1 && md_fd_message_origins(record)[0]==md_fs_anchor_object(a));
    assert(fcntl(pipefd[0],F_GETFD)==-1 && errno==EBADF);
    assert(!close(carrier[0]) && !close(carrier[1]) && !close(pipefd[1]));
    allocation_budget=-1;
    messages=NULL;
    assert(!md_fd_messages_new(&messages));
    assert(md_fd_messages_publish(messages,record,&connection,-1,-1)==-ESTALE);
    md_fd_messages_free(messages); md_fd_message_release(record);
    assert(descriptors()==before);
    puts("PASS message registry: bounded 16-event dispatch, colliding buckets, owner teardown with pinned worker vector, no carrier retention");
}
struct message_reader { struct md_fd_message *record; int ready; };
static void *read_message(void *opaque) {
    struct message_reader *reader=opaque;
    /* EVENT_WAIT: owner cancellation precedes inspection; timeout fails the
     * fixture, while the supervisor retains the pin through worker join. */
    assert(!md_event_wait_fd(reader->ready,POLLIN,md_event_now()+5000000000LL));
    uint64_t ready; assert(read(reader->ready,&ready,sizeof(ready))==sizeof(ready));
    assert(md_fd_message_count(reader->record)==1);
    struct md_fs_anchor *origin=md_fs_anchor_from_object(md_fd_message_origins(reader->record)[0]);
    struct stat identity;
    assert(origin && !fstat(md_fs_anchor_fd(origin),&identity) && S_ISDIR(identity.st_mode));
    return NULL;
}
static void message_worker(void) {
    unsigned before=descriptors();
    struct md_mount_namespace *view=md_mount_namespace_new(); assert(view);
    struct md_fs_anchor *origin=capture(view,open("/",O_PATH|O_CLOEXEC));
    struct md_fd_origins *source=md_fd_origins_new(); assert(source);
    put(source,7,origin,0);
    struct md_fd_message *record=message_capture(source,1);
    md_fd_origins_release(source); md_fs_anchor_release(origin); md_mount_namespace_release(view);
    struct md_fd_messages *messages=NULL; assert(!md_fd_messages_new(&messages));
    struct md_ipc_connection connection={.link={5},.side=1};
    int carrier[2], pipefd[2];
    assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,carrier)
        && !pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
    assert(!md_fd_messages_publish(messages,record,&connection,carrier[0],pipefd[0]));
    struct message_reader reader={.record=md_fd_message_retain(record),
        .ready=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK)};
    assert(reader.ready>=0);
    pthread_t worker; assert(!pthread_create(&worker,NULL,read_message,&reader));
    md_fd_message_release(record); md_fd_messages_free(messages);
    assert(!close(carrier[0]) && !close(carrier[1]) && !close(pipefd[1]));
    uint64_t ready=1; assert(write(reader.ready,&ready,sizeof(ready))==sizeof(ready));
    /* EVENT_WAIT: immutable inspection finishes; runner bounds a stuck join. */
    assert(!pthread_join(worker,NULL));
    md_fd_message_release(reader.record);
    assert(!close(reader.ready) && descriptors()==before);
    puts("PASS message worker: source table, mount owner and registry die before inspection; supervisor pin ends after join");
}
static void provisional_custody(struct md_fs_anchor *anchor) {
    for (unsigned flags=0;flags<=FD_CLOEXEC;++flags) {
        struct md_fd_origins *files=md_fd_origins_new(); assert(files);
        struct md_fd_loan *loan=md_fd_loan_new(files); assert(loan);
        struct md_fd_origin_update update;
        struct md_fd_order turn={0};
        assert(!md_fd_origins_prepare(files,anchor,flags,&update));
        assert(md_fd_origins_prepare_custody(&update,loan,&turn)==-EBUSY);
        assert(md_fd_order_begin(files,&turn,NULL)==1);
        assert(md_fd_origins_prepare_custody(&update,loan,&turn)==-EBUSY);
        assert(!md_fd_order_start(&turn));
        allocation_budget=0;
        assert(md_fd_origins_prepare_custody(&update,loan,&turn)==-ENOMEM);
        allocation_budget=-1;
        assert(!md_fd_origins_prepare_custody(&update,loan,&turn));
        allocation_budget=0;
        md_fd_origins_release_custody(&update);
        md_fd_origins_finish(&update,10);
        assert(!md_fd_origins_has_loans(files)); expect(files,10,anchor,flags);
        allocation_budget=-1;
        assert(!md_fd_origins_prepare(files,anchor,flags,&update));
        assert(!md_fd_origins_prepare_custody(&update,loan,&turn));
        allocation_budget=0;
        md_fd_origins_finish(&update,11);
        assert(md_fd_origins_loan_matches(&turn,11,loan)==1);
        md_fd_loan_end(loan);
        int number=-1;
        assert(md_fd_origins_loan_next(&turn,&number)==1 && number==11);
        assert(!md_fd_origins_close_range(files,11,11,0) && !md_fd_origins_has_loans(files));
        assert(!md_fd_order_complete(&turn));
        md_fd_loan_release(loan); md_fd_origins_release(files);
        allocation_budget=-1;
    }
    puts("PASS provisional FD custody: table-turn admission, actual CLOEXEC flags, allocation failure, publication and exact retirement");
}
static void native_loans(void) {
    for (int budget=0;budget<=3;++budget) {
        struct md_fd_origins *files=md_fd_origins_new(); assert(files);
        struct md_fd_loan *loan=md_fd_loan_new(files); assert(loan);
        struct md_fd_order turn={0};
        assert(md_fd_order_begin(files,&turn,NULL)==1 && !md_fd_order_start(&turn));
        struct md_fd_origin_update update={0};
        assert(!md_fd_origins_prepare(files,NULL,FD_CLOEXEC,&update));
        allocation_budget=budget;
        int result=md_fd_origins_prepare_loan(&update,loan);
        allocation_budget=-1;
        assert(result==(budget<3 ? -ENOMEM : 0));
        md_fd_origins_finish(&update,result ? result : 14);
        if (!result) {
            assert(md_fd_origins_loan_matches(&turn,14,loan)==1);
            unsigned long args[6]={14,(1UL<<32)|FIOCLEX,~0UL};
            struct md_fd_loan *held=NULL; int selected=-1;
            assert(!md_fd_mutation_loan(&turn,SYS_ioctl,args,&selected,&held) && !held);
            args[1]=(1UL<<32)|FIONCLEX;
            assert(md_fd_mutation_loan(&turn,SYS_ioctl,args,&selected,&held)==1);
            assert(selected==14 && held==loan);
            md_fd_loan_release(held);
            md_fd_loan_end(loan);
            int number=-1;
            assert(md_fd_origins_loan_next(&turn,&number)==1 && number==14);
            assert(!md_fd_origins_close_range(files,14,14,0));
        }
        assert(!md_fd_origins_has_loans(files));
        md_fd_loan_end(loan); md_fd_loan_release(loan);
        assert(!md_fd_order_complete(&turn)); md_fd_origins_release(files);
    }
    puts("PASS metadata-free native FD loans: allocation failures, publication and exact retirement");
}
static void native_input_pin(void) {
    unsigned pin_before=descriptors();
    int pipe_fds[2];
    assert(!pipe2(pipe_fds,O_NONBLOCK|O_CLOEXEC));
    struct md_fd_object *pin=md_fd_native_pin_new(pipe_fds[1],NULL);
    assert(pin);
    struct stat pinned, original;
    assert(!fstat(pipe_fds[1],&original));
    assert(!fstat(md_fd_native_pin_descriptor(pin),&pinned));
    assert(original.st_dev==pinned.st_dev && original.st_ino==pinned.st_ino);
    assert(!md_fs_anchor_from_object(pin));
    close(pipe_fds[1]);
    char byte;
    assert(read(pipe_fds[0],&byte,1)==0);
    md_fd_object_release(pin);
    allocation_budget=0;
    assert(!md_fd_native_pin_new(pipe_fds[0],NULL) && errno==ENOMEM);
    allocation_budget=-1;
    close(pipe_fds[0]);
    assert(!pipe2(pipe_fds,O_NONBLOCK|O_CLOEXEC));
    struct md_identity root=md_identity_new(0,0);
    pin=md_fd_native_pin_new(pipe_fds[1],&root);
    assert(pin && md_descriptor_inode_is(pin));
    assert(!fstat(pipe_fds[1],&original));
    assert(!md_descriptor_inode_chown(pin,&root,71,72));
    assert(!md_descriptor_inode_chmod(pin,&root,0640));
    assert(!fstat(md_fd_native_pin_descriptor(pin),&pinned));
    assert(pinned.st_uid==original.st_uid && pinned.st_gid==original.st_gid && pinned.st_mode==original.st_mode);
    assert(!md_descriptor_inode_stat(pin,&root,&pinned));
    assert(pinned.st_uid==71 && pinned.st_gid==72 && pinned.st_mode==(S_IFIFO|0640));
    close(pipe_fds[1]);
    assert(read(pipe_fds[0],&byte,1)==0);
    md_fd_object_release(pin); close(pipe_fds[0]);
    for (int budget=0;budget<2;++budget) {
        assert(!pipe2(pipe_fds,O_NONBLOCK|O_CLOEXEC));
        allocation_budget=budget;
        pin=md_fd_native_pin_new(pipe_fds[1],&root);
        allocation_budget=-1;
        if (pin) md_fd_object_release(pin); else assert(errno==ENOMEM);
        close(pipe_fds[0]); close(pipe_fds[1]);
    }
    md_identity_release(&root);
    assert(descriptors()==pin_before);
}
void test_fd_origins(void) {
    native_input_pin();
    native_loans();
    close_lifecycle();
    extern void test_task_receive(int *);
    extern void test_epoll_owner(int *);
    test_epoll_owner(&allocation_budget);
    test_task_receive(&allocation_budget);
    received_custody();
    message_worker();
    export_messages();
    infrastructure();
    channel_reconciliation();
    channel_captures();
    vector_custody();
    unsigned before=descriptors();
    struct md_mount_namespace *first=md_mount_namespace_new(), *second=md_mount_namespace_new();
    assert(first && second);
    struct md_fs_anchor *a=capture(first,open("/",O_PATH|O_CLOEXEC));
    struct md_fs_anchor *b=capture(second,open("/",O_PATH|O_CLOEXEC));
    native_io_proofs(first,a);
    provisional_custody(a);
    bounded_retirement(a);
    received_origins(a,b); vector_preparation(a,b,0); vector_preparation(a,b,1);
    observed_delivery(a,b); observed_return_failure(a);
    message_custody(a,b); message_batches(a);
    filesystem_ordering(a);
    struct stat x,y; assert(!fstat(a->fd,&x) && !fstat(b->fd,&y));
    assert(x.st_dev==y.st_dev && x.st_ino==y.st_ino && a->view!=b->view);
    struct md_fd_origins *table=md_fd_origins_new(); assert(table);
    struct md_fd_origin_update update;
    for (int n=0;n<2;++n) {
        allocation_budget=n;
        int r=md_fd_origins_prepare(table,a,0,&update);
        allocation_budget=-1;
        assert(r==-ENOMEM && !update.table && !update.entry);
        expect(table,10,NULL,0);
    }
    put(table,10,a,FD_CLOEXEC); copy_slot(table,10,71,0);
    assert(!md_fd_origins_prepare(table,b,0,&update));
    md_fd_origins_finish(&update,-EIO); expect(table,10,a,FD_CLOEXEC);
    put(table,10,b,0); expect(table,71,a,0); expect(table,10,b,0);
    copy_slot(table,999,10,0); expect(table,10,NULL,0);
    put(table,INT_MAX,b,0); put(table,10,b,0);
    assert(!md_fd_origins_close_range(table,71,UINT_MAX,1));
    expect(table,71,a,FD_CLOEXEC); expect(table,INT_MAX,b,FD_CLOEXEC);
    assert(!md_fd_origins_set_flags(table,71,0));
    md_fd_origins_exec(table);
    expect(table,71,a,0); expect(table,INT_MAX,NULL,0); expect(table,10,b,0);
    assert(md_fd_origins_close_range(table,2,1,0)==-EINVAL);
    assert(md_fd_origins_prepare(table,a,2,&update)==-EINVAL);
    assert(md_fd_origins_set_flags(table,71,2)==-EINVAL);
    assert(!md_fd_origins_close_range(table,10,10,0)); expect(table,10,NULL,0);
    int stream[2]; assert(!pipe2(stream,O_CLOEXEC|O_NONBLOCK));
    struct md_fs_anchor *pipe_origin=capture(first,stream[1]);
    put(table,12,pipe_origin,0); md_fs_anchor_release(pipe_origin);
    char byte; assert(read(stream[0],&byte,1)==0); close(stream[0]);
    md_fd_origins_release(table);
    cwd_context(a); ownership(a,b); rollback(a); registry_and_readers(a,b); ordering(a); event_ordering(a);
    mutations(a,b); exchanges(a,b); batches(a,b); deliveries(a,b); receipts();
    table=md_fd_origins_new(); assert(table);
    assert(!md_fd_origins_prepare(table,a,0,&update));
    md_fd_origins_release(table);
    md_fs_anchor_release(a); md_fs_anchor_release(b);
    md_mount_namespace_release(first); md_mount_namespace_release(second);
    md_fd_origins_finish(&update,5);
    assert(descriptors()==before);
    puts("PASS sparse FD origins: prepared publication, cancellation, alias replacement, flags, ranges and EOF");
    puts("PASS independent CLONE_FILES/CLONE_FS, unshare rollback, exec CLOEXEC and retained cwd");
    puts("PASS allocation-failure rollback, allocation-free publication and concurrent retained snapshots");
    puts("PASS per-file-table ordering: cancellation awaits kernel completion, independent owners progress, no allocations");
    puts("PASS cross-loop ordering: 3000 publications, concurrent cancellation, saturated wake and owner teardown");
    puts("PASS descriptor mutations and authenticated RPC exchange: prepare rollback, owner loss and exact publication");
    puts("PASS RPC cwd origins: retained mixed/two-base snapshots, unknown rejection and allocation rollback");
    puts("PASS origin vectors: sender lifetime, repeated/unknown sources, peek reuse, prefix publication, invalid receipts and atomic snapshots");
    puts("PASS retained receipt capacities 1..253: bounded parsing, foreign pointers, invalid/duplicate numbers and unchanged error output");
    puts("PASS shared FD delivery: preparation rollback, retry, partial publication, owner loss, once-only commit and caller FD lifetime");
}
