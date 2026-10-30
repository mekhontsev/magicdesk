#define _GNU_SOURCE
#include "control_epoll.h"
#include "fd_messages.h"
#include "fd_mutation.h"
#include "fd_weak.h"
#include "fd_selector.h"
#include "proc_file.h"
#include "proc_sysctl.h"
#include "uts_namespace.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/close_range.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <unistd.h>

static unsigned descriptors(void) {
    DIR *d=opendir("/proc/self/fd"); assert(d);
    unsigned n=0; while (readdir(d)) ++n;
    closedir(d); return n;
}
static void mutation(struct md_fd_origins *files,long nr,unsigned long a,unsigned long b,
        unsigned long c,long result,int *budget) {
    struct md_fd_order turn={0};
    assert(md_fd_order_begin(files,&turn,NULL)==1 && !md_fd_order_start(&turn));
    struct md_fd_mutation op={0}; unsigned long args[6]={a,b,c};
    assert(!md_fd_mutation_prepare(files,nr,args,&op));
    *budget=0;
    assert(!md_fd_mutation_finish(&op,files,result));
    assert(!md_fd_order_complete(&turn));
    *budget=-1;
}
static int prepare(struct md_fd_origins *files,unsigned flags,struct md_fd_origin_update *update) {
    struct md_control_epoll *q=md_control_epoll_new();
    if (!q) return -ENOMEM;
    int error=md_fd_origins_prepare_object(files,md_control_epoll_object(q),flags,update);
    md_control_epoll_release(q); return error;
}
static void publish(struct md_fd_origins *files,int fd,unsigned flags,int *budget) {
    struct md_fd_order turn={0};
    assert(md_fd_order_begin(files,&turn,NULL)==1 && !md_fd_order_start(&turn));
    struct md_fd_origin_update update;
    assert(!prepare(files,flags,&update));
    *budget=0; md_fd_origins_finish(&update,fd);
    assert(!md_fd_order_complete(&turn)); *budget=-1;
}
static struct md_fd_object *expect(struct md_fd_origins *files,int fd,
        struct md_fd_object *object,unsigned flags) {
    unsigned actual_flags=0;
    struct md_fd_object *actual=md_fd_origins_object(files,fd,&actual_flags);
    assert(actual==object && actual_flags==flags);
    md_fd_object_release(actual); return object;
}
static void aliases(struct md_fd_origins *files,struct md_fd_object *object,
        const int *expected,unsigned expected_count,int *budget) {
    struct md_fd_order turn={0},waiting={0};
    unsigned count=UINT_MAX; int output[16];
    assert(expected_count<=16);
    assert(md_fd_origins_aliases(&turn,object,NULL,0,&count)==-EINVAL);
    assert(md_fd_order_begin(files,&turn,NULL)==1);
    assert(md_fd_origins_aliases(&turn,object,NULL,0,&count)==-EBUSY);
    assert(!md_fd_order_start(&turn));
    assert(!md_fd_order_begin(files,&waiting,NULL));
    assert(md_fd_origins_aliases(&waiting,object,NULL,0,&count)==-EBUSY);
    *budget=0;
    assert(!md_fd_origins_aliases(&turn,object,NULL,0,&count) && count==expected_count);
    if (count) {
        output[0]=-1;
        assert(md_fd_origins_aliases(&turn,object,output,count-1,&count)==-ENOSPC
            && count==expected_count && output[0]==-1);
    }
    assert(!md_fd_origins_aliases(&turn,object,output,16,&count) && count==expected_count);
    for (unsigned i=0;i<count;++i) {
        unsigned matches=0;
        for (unsigned j=0;j<count;++j) matches+=output[j]==expected[i];
        assert(matches==1);
    }
    assert(!md_fd_order_cancel(&waiting) && !md_fd_order_complete(&turn));
    *budget=-1;
}
static struct md_fd_message *capture(struct md_fd_object *const *objects,unsigned count) {
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    int fds[253]; struct md_fd_origin_update updates[253];
    for (unsigned i=0;i<count;++i) fds[i]=(int)i+10;
    assert(!md_fd_origins_prepare_batch(files,objects,0,count,updates));
    assert(!md_fd_origins_finish_batch(updates,count,fds,count));
    struct md_fd_message *message=NULL;
    assert(!md_fd_message_capture(files,fds,count,&message));
    md_fd_origins_release(files); return message;
}
static void no_destroy(struct md_fd_object *object) { (void)object; }
static int export_failure(struct md_fd_object *object) { (void)object; return -ENOMEM; }
static void unexpected_export_end(struct md_fd_object *object,int escaped) {
    (void)object; (void)escaped; assert(0);
}
static const struct md_fd_object_type failing_export={
    .destroy=no_destroy,.export_begin=export_failure,.export_end=unexpected_export_end};
struct export_race {
    pthread_barrier_t gate;
    struct md_fd_object *object;
    int result;
};
static void *export_worker(void *context) {
    struct export_race *race=context;
    for (unsigned n=0;n<1000;++n) {
        pthread_barrier_wait(&race->gate);
        race->result=race->object->type->export_begin(race->object);
        pthread_barrier_wait(&race->gate);
        if (!race->result) race->object->type->export_end(race->object,0);
        pthread_barrier_wait(&race->gate);
    }
    return NULL;
}
static void export_admission(int *budget) {
    unsigned before=descriptors();
    struct md_identity id=md_identity_new(0,0);
    struct md_uts_namespace *uts=md_uts_namespace_new(); assert(uts);
    struct md_control_file provider=md_proc_sysctl_new(MD_PROC_FILE_HOSTNAME,NULL,uts); assert(provider.ops);
    struct md_proc_file *source=md_proc_file_open_control(provider.ops,provider.context,&(struct md_control_opener){.identity=&id},O_RDONLY);
    md_control_file_release(provider); assert(source);
    struct md_fd_object *target=md_proc_file_object(source);
    provider=md_proc_file_control(source);
    struct epoll_event event={.events=EPOLLPRI};
    for (unsigned mode=0;mode<4;++mode) {
        struct md_control_epoll *q=md_control_epoll_new(); assert(q);
        struct md_fd_object *objects[253];
        for (unsigned i=0;i<253;++i) objects[i]=i%2 ? NULL : md_control_epoll_object(q);
        struct md_fd_message *a=capture(objects,253),*b=capture(objects,253);
        *budget=0;
        assert(!md_fd_message_export_begin(a) && !md_fd_message_export_begin(b));
        assert(md_fd_message_export_begin(a)==-EBUSY);
        for (unsigned i=0;i<253;++i) assert(md_fd_message_exports_native(a,i)==!(i%2));
        assert(!md_fd_message_exports_native(a,253));
        assert(md_control_epoll_ctl(q,EPOLL_CTL_ADD,7,target,provider,&event)==-EBUSY);
        md_fd_message_export_end(a,mode==1);
        md_fd_message_release(a);
        assert(md_control_epoll_ctl(q,EPOLL_CTL_ADD,7,target,provider,&event)
            ==(mode==1 ? -EOPNOTSUPP : -EBUSY));
        if (mode!=3) md_fd_message_export_end(b,mode==2);
        md_fd_message_release(b); /* mode 3: implicit cancellation */
        *budget=-1;
        int result=md_control_epoll_ctl(q,EPOLL_CTL_ADD,7,target,provider,&event);
        assert(result==((mode==1 || mode==2) ? -EOPNOTSUPP : 0));
        if (!result) {
            struct md_fd_object *object=md_control_epoll_object(q);
            a=capture(&object,1);
            assert(!md_fd_message_export_begin(a) && !md_fd_message_exports_native(a,0));
            md_fd_message_export_end(a,1); md_fd_message_release(a);
            assert(!md_control_epoll_ctl(q,EPOLL_CTL_DEL,7,target,provider,NULL));
            assert(object->type->export_begin(object)==-EOPNOTSUPP);
        }
        md_control_epoll_release(q);
    }
    struct md_control_epoll *q=md_control_epoll_new(); assert(q);
    struct md_fd_object failure; md_fd_object_init(&failure,&failing_export);
    struct md_fd_object *objects[]={md_control_epoll_object(q),&failure};
    struct md_fd_message *message=capture(objects,2);
    assert(md_fd_message_export_begin(message)==-ENOMEM);
    assert(!md_fd_message_exports_native(message,0));
    assert(!md_control_epoll_ctl(q,EPOLL_CTL_ADD,7,target,provider,&event));
    md_fd_message_release(message); md_fd_object_release(&failure); md_control_epoll_release(q);
    struct export_race race={0}; pthread_t worker;
    assert(!pthread_barrier_init(&race.gate,NULL,2));
    assert(!pthread_create(&worker,NULL,export_worker,&race));
    for (unsigned n=0;n<1000;++n) {
        q=md_control_epoll_new(); assert(q); race.object=md_control_epoll_object(q);
        pthread_barrier_wait(&race.gate);
        int result=md_control_epoll_ctl(q,EPOLL_CTL_ADD,7,target,provider,&event);
        pthread_barrier_wait(&race.gate);
        assert((result==-EBUSY && !race.result) || (!result && race.result==-EOPNOTSUPP));
        pthread_barrier_wait(&race.gate);
        if (result) assert(!md_control_epoll_ctl(q,EPOLL_CTL_ADD,7,target,provider,&event));
        md_control_epoll_release(q);
    }
    assert(!pthread_join(worker,NULL)); pthread_barrier_destroy(&race.gate);
    md_proc_file_release(source); md_uts_namespace_release(uts); md_identity_release(&id);
    assert(descriptors()==before);
    puts("PASS epoll export: rollback, concurrent sends, duplicate rights, lifetime latch and activation races");
}
static void target_retirement(int *budget) {
    unsigned before=descriptors();
    struct md_identity id=md_identity_new(0,0);
    struct md_uts_namespace *uts=md_uts_namespace_new(); assert(uts);
    struct md_control_file provider=md_proc_sysctl_new(MD_PROC_FILE_HOSTNAME,NULL,uts); assert(provider.ops);
    struct md_proc_file *source=md_proc_file_open_control(provider.ops,provider.context,&(struct md_control_opener){.identity=&id},O_RDONLY);
    md_control_file_release(provider); assert(source);
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct md_fd_origin_update update;
    assert(!md_fd_origins_prepare_object(files,md_proc_file_object(source),FD_CLOEXEC,&update));
    md_fd_origins_finish(&update,40);
    mutation(files,SYS_dup,40,0,0,41,budget);
    struct md_fd_origins *child=md_fd_origins_fork(files,0); assert(child);
    struct md_control_epoll *q=md_control_epoll_new(); assert(q);
    struct epoll_event event={.events=0,.data.u64=42},out;
    assert(!md_control_epoll_ctl(q,EPOLL_CTL_ADD,40,md_proc_file_object(source),md_proc_file_control(source),&event));
    struct md_fd_weak *weak=md_fd_weak_observe(md_proc_file_object(source)); assert(weak);
    md_proc_file_release(source);
    mutation(files,SYS_close,40,0,0,0,budget);
    md_fd_origins_exec(child);
    struct md_fd_message *queued=NULL; int number=41;
    assert(!md_fd_message_capture(child,&number,1,&queued));
    md_fd_origins_release(files); md_fd_origins_release(child);
    assert(!md_fd_weak_expired(weak));
    assert(!md_uts_namespace_set(uts,0,"queued-target",13));
    assert(md_control_epoll_collect(q,&out,1)==1 && out.events==EPOLLERR && out.data.u64==42);
    files=md_fd_origins_new(); assert(files);
    assert(!md_fd_origins_prepare_batch(files,md_fd_message_origins(queued),FD_CLOEXEC,1,&update));
    int received=40;
    assert(!md_fd_origins_finish_batch(&update,1,&received,1));
    md_fd_message_release(queued);
    assert(!md_fd_weak_expired(weak));
    *budget=0;
    md_fd_origins_exec(files);
    *budget=-1;
    assert(md_fd_weak_expired(weak));
    /* The collector owns no provider reference that could keep this target
     * alive, including an unread notification racing the final close. */
    assert(!md_uts_namespace_set(uts,0,"retired",7));
    assert(!md_control_epoll_collect(q,&out,1));
    md_fd_origins_release(files); md_fd_weak_release(weak); md_control_epoll_release(q);
    md_uts_namespace_release(uts); md_identity_release(&id);
    assert(descriptors()==before);
    puts("PASS watched target custody: dup/fork/exec, queued transfer and automatic final retirement");
}
static void staged_admission(int *budget) {
    unsigned before=descriptors();
    struct md_control_epoll *q=md_control_epoll_new(); assert(q);
    struct md_fd_object *object=md_control_epoll_object(q);
    struct md_fd_message *message=capture(&object,1);
    *budget=0;
    assert(!md_fd_object_selectors(object));
    assert(md_control_epoll_admit_finish(q,1)==-EINVAL);
    assert(md_control_epoll_admit_begin(q)==1);
    assert(md_control_epoll_admit_begin(q)==-EBUSY);
    assert(md_control_epoll_descriptor(q)==-1);
    assert(md_fd_object_selectors(object)==((1U<<MD_FD_SELECTOR_EPOLL)|(1U<<MD_FD_SELECTOR_EPOLL_TARGET)));
    assert(md_fd_object_selectors(md_fd_message_origins(message)[0])==((1U<<MD_FD_SELECTOR_EPOLL)|(1U<<MD_FD_SELECTOR_EPOLL_TARGET)));
    assert(!md_fd_message_export_begin(message) && !md_fd_message_exports_native(message,0));
    md_fd_message_export_end(message,0);
    assert(md_control_epoll_ctl(q,EPOLL_CTL_ADD,8,object,(struct md_control_file){0},NULL)==-EBUSY);
    assert(!md_control_epoll_admit_finish(q,0));
    assert(!md_fd_object_selectors(object));
    assert(md_control_epoll_descriptor(q)==-1);
    assert(!md_fd_message_export_begin(message) && md_fd_message_exports_native(message,0));
    assert(md_control_epoll_admit_begin(q)==-EBUSY);
    md_fd_message_export_end(message,0);
    assert(md_control_epoll_admit_begin(q)==1);
    assert(!md_control_epoll_admit_finish(q,1));
    assert(md_control_epoll_admit_begin(q)==0);
    assert(md_control_epoll_admit_finish(q,0)==-EINVAL);
    assert(md_fd_object_selectors(object)==((1U<<MD_FD_SELECTOR_EPOLL)|(1U<<MD_FD_SELECTOR_EPOLL_TARGET)));
    assert(!md_fd_message_export_begin(message) && !md_fd_message_exports_native(message,0));
    md_fd_message_export_end(message,1);
    md_fd_message_release(message); md_control_epoll_release(q);
    *budget=-1;
    assert(descriptors()==before);
    puts("PASS staged epoll admission: dynamic alias requirements, export exclusion, rollback and no allocation");
}
static void uncertain_aliases(int *budget) {
    for (int state=0;state<3;++state) for (int mutation=0;mutation<4;++mutation) {
        struct md_control_epoll *q=md_control_epoll_new(),*replaced=md_control_epoll_new();
        struct md_fd_origins *files=md_fd_origins_new(); assert(q && replaced && files);
        struct md_fd_object *object=md_control_epoll_object(q);
        struct md_fd_origin_update updates[2];
        assert(!md_fd_origins_prepare_batch(files,(struct md_fd_object *[]){object,md_control_epoll_object(replaced)},0,2,updates));
        assert(!md_fd_origins_finish_batch(updates,2,(int[]){10,11},2));
        if (state) assert(md_control_epoll_admit_begin(q)==1);
        if (state==2) assert(!md_control_epoll_admit_finish(q,1));
        struct md_fd_order turn={0};
        assert(md_fd_order_begin(files,&turn,NULL)==1 && !md_fd_order_start(&turn));
        struct md_fd_mutation op;
        long nr=mutation==0 ? SYS_close : mutation==1 ? SYS_dup3 : mutation==2 ? SYS_dup : SYS_fcntl;
        unsigned long args[6]={10,mutation==3 ? F_DUPFD_CLOEXEC : 11};
        assert(!md_fd_mutation_prepare(files,nr,args,&op));
        *budget=0;
        assert(!md_fd_mutation_abandon(&op,files));
        assert(!md_fd_order_complete(&turn));
        assert(md_control_epoll_admit_begin(q)==-ESTALE);
        if (state==1) assert(md_control_epoll_admit_finish(q,1)==-ESTALE);
        assert(md_control_epoll_admit_begin(q)==-ESTALE);
        struct epoll_event event;
        assert(md_control_epoll_collect(q,&event,1)==-ESTALE);
        assert(md_control_epoll_ctl(q,EPOLL_CTL_DEL,99,NULL,(struct md_control_file){0},NULL)==-ESTALE);
        if (mutation==1) assert(md_control_epoll_admit_begin(replaced)==-ESTALE);
        else { assert(md_control_epoll_admit_begin(replaced)==1); assert(!md_control_epoll_admit_finish(replaced,0)); }
        md_fd_origins_release(files); md_control_epoll_release(q); md_control_epoll_release(replaced);
        *budget=-1;
    }
    puts("PASS epoll uncertain mutation: close, dup replacement and unknown new aliases invalidate admission without allocation");
}
static void uncertain_wakeup(int *budget) {
    unsigned before=descriptors();
    struct md_control_epoll *q=md_control_epoll_new();
    struct md_fd_origins *files=md_fd_origins_new(); assert(q && files);
    struct md_identity id=md_identity_new(0,0);
    struct md_uts_namespace *uts=md_uts_namespace_new(); assert(uts);
    struct md_control_file provider=md_proc_sysctl_new(MD_PROC_FILE_HOSTNAME,NULL,uts); assert(provider.ops);
    struct md_proc_file *source=md_proc_file_open_control(provider.ops,provider.context,&(struct md_control_opener){.identity=&id},O_RDONLY);
    md_control_file_release(provider); assert(source);
    struct md_fd_origin_update update;
    assert(!md_fd_origins_prepare_object(files,md_control_epoll_object(q),0,&update));
    md_fd_origins_finish(&update,10);
    struct epoll_event event={.events=0};
    assert(!md_control_epoll_ctl(q,EPOLL_CTL_ADD,20,md_proc_file_object(source),md_proc_file_control(source),&event));
    assert(!md_control_epoll_collect(q,&event,1));
    struct pollfd wake={md_control_epoll_descriptor(q),POLLIN,0};
    assert(!poll(&wake,1,0));
    mutation(files,SYS_dup,10,0,0,-EMFILE,budget);
    assert(!md_control_epoll_admit_begin(q));
    struct md_fd_order turn={0};
    assert(md_fd_order_begin(files,&turn,NULL)==1 && !md_fd_order_start(&turn));
    struct md_fd_mutation op;
    assert(!md_fd_mutation_prepare(files,SYS_dup,(unsigned long[]){10,0,0,0,0,0},&op));
    *budget=0;
    assert(!md_fd_mutation_abandon(&op,files) && !md_fd_order_complete(&turn));
    assert(poll(&wake,1,0)==1 && (wake.revents&POLLIN));
    assert(md_control_epoll_collect(q,&event,1)==-ESTALE);
    md_proc_file_release(source); md_uts_namespace_release(uts); md_identity_release(&id);
    md_fd_origins_release(files); md_control_epoll_release(q); *budget=-1;
    assert(descriptors()==before);
    puts("PASS uncertain epoll wakes retained waiters with an error; observed failed dup preserves admission");
}
static void temporary_slots(int *budget) {
    unsigned before=descriptors();
    for (int mode=0;mode<5;++mode) {
        struct md_fd_origins *files=md_fd_origins_new(); assert(files);
        struct md_fd_loan *loan=md_fd_loan_new(files); assert(loan);
        struct md_fd_origin_update update;
        assert(!prepare(files,FD_CLOEXEC,&update));
        assert(!md_fd_origins_prepare_loan(&update,loan));
        assert(md_fd_origins_prepare_loan(&update,loan)==-EINVAL);
        *budget=0; md_fd_origins_finish(&update,23); *budget=-1;
        assert(md_fd_origins_has_loans(files));
        struct md_fd_origins *copy=md_fd_origins_fork(files,0); assert(copy);
        struct md_fd_origins *shared=md_fd_origins_fork(files,1); assert(shared==files);
        struct md_fd_order turn={0}; int number;
        assert(md_fd_origins_loan_next(&turn,&number)==-EINVAL);
        assert(md_fd_order_begin(files,&turn,NULL)==1);
        assert(md_fd_origins_loan_next(&turn,&number)==-EBUSY);
        assert(!md_fd_order_start(&turn));
        *budget=0;
        assert(!md_fd_origins_loan_next(&turn,&number) && number==-1);
        assert(!md_fd_order_complete(&turn)); *budget=-1;
        mutation(files,SYS_dup,23,0,0,24,budget);
        if (mode==1) mutation(files,SYS_close,23,0,0,0,budget);
        if (mode==2) publish(files,23,FD_CLOEXEC,budget);
        if (mode==3) md_fd_origins_exec(files);
        if (mode==4) assert(!md_fd_origins_abandon_range(files,23,23));
        for (int child=1;child>=0;--child) {
            struct md_fd_origins *table=child ? copy : files;
            if (!child) md_fd_loan_end(loan);
            assert(md_fd_order_begin(table,&turn,NULL)==1 && !md_fd_order_start(&turn));
            *budget=0;
            int found=md_fd_origins_loan_next(&turn,&number);
            assert(found==(child || !mode ? 1 : mode==4 ? -ESTALE : 0));
            if (found==1) {
                assert(number==23);
                assert(!md_fd_origins_close_range(table,23,23,0));
                assert(!md_fd_origins_loan_next(&turn,&number));
            }
            assert(!md_fd_order_complete(&turn)); *budget=-1;
        }
        struct md_fd_object *alias=md_fd_origins_object(files,24,NULL); assert(alias);
        md_fd_object_release(alias);
        *budget=0;
        md_fd_loan_release(loan);
        md_fd_origins_release(copy); md_fd_origins_release(shared); md_fd_origins_release(files);
        *budget=-1;
        assert(descriptors()==before);
    }
    puts("PASS operation FD loans: shared ownership, copied-table retirement, explicit dup, close/reuse/exec and uncertain cancellation");
}
static void loan_conflicts(int *budget) {
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct md_fd_loan *loan=md_fd_loan_new(files); assert(loan);
    struct md_fd_origin_update update;
    assert(!prepare(files,FD_CLOEXEC,&update));
    assert(!md_fd_origins_prepare_loan(&update,loan));
    md_fd_origins_finish(&update,23);
    struct md_fd_order turn={0};
    assert(md_fd_order_begin(files,&turn,NULL)==1 && !md_fd_order_start(&turn));
    static const struct { long nr; unsigned long args[6]; int conflict; } cases[]={
        {SYS_close,{23},1}, {SYS_close,{24},0}, {SYS_close,{ULONG_MAX},0},
        {SYS_dup3,{24,23,O_CLOEXEC},1}, {SYS_dup3,{23,24,0},0},
        {SYS_dup3,{24,23,(1UL<<32)|O_CLOEXEC},1},
        {SYS_dup3,{23,23,0},0}, {SYS_dup3,{24,23,O_NONBLOCK},0},
        {SYS_dup,{23},0}, {SYS_fcntl,{23,F_DUPFD_CLOEXEC,0},0},
        {SYS_fcntl,{23,F_SETFD,0},1}, {SYS_fcntl,{23,F_SETFD,FD_CLOEXEC},0},
        {SYS_close_range,{0,UINT_MAX,0},1}, {SYS_close_range,{23,23,0},1},
        {SYS_close_range,{24,UINT_MAX,0},0}, {SYS_close_range,{24,23,0},0},
        {SYS_close_range,{0,UINT_MAX,CLOSE_RANGE_CLOEXEC},0},
        {SYS_close_range,{0,UINT_MAX,CLOSE_RANGE_UNSHARE},0},
        {SYS_close_range,{0,UINT_MAX,UINT_MAX},0}, {SYS_execve,{0},0},
        {SYS_unshare,{0},0}, {SYS_clone,{0},0}, {SYS_openat,{0},0}
    };
    *budget=0;
    for (unsigned i=0;i<sizeof(cases)/sizeof(*cases);++i) {
        struct md_fd_loan *found=NULL; int number=-1;
        assert(md_fd_mutation_loan(&turn,cases[i].nr,cases[i].args,&number,&found)==cases[i].conflict);
        assert(number==(cases[i].conflict ? 23 : -1));
        assert(found==(cases[i].conflict ? loan : NULL));
        md_fd_loan_release(found);
    }
    assert(md_fd_origins_loan_matches(&turn,23,loan)==1);
    assert(!md_fd_origins_loan_matches(&turn,24,loan));
    assert(!md_fd_origins_abandon_range(files,23,23));
    assert(md_fd_origins_loan_matches(&turn,23,loan)==-ESTALE);
    struct md_fd_loan *found=NULL; int number=-1;
    assert(md_fd_mutation_loan(&turn,SYS_close,(unsigned long[]){23,0,0,0,0,0},&number,&found)==-ESTALE);
    assert(number==-1 && !found);
    assert(!md_fd_order_complete(&turn)); *budget=-1;
    /* Reusing the same number with the same origin is still another loan. */
    struct md_fd_loan *replacement=md_fd_loan_new(files); assert(replacement);
    assert(!prepare(files,FD_CLOEXEC,&update));
    assert(!md_fd_origins_prepare_loan(&update,replacement));
    md_fd_origins_finish(&update,23);
    assert(md_fd_order_begin(files,&turn,NULL)==1 && !md_fd_order_start(&turn));
    *budget=0;
    assert(!md_fd_origins_loan_matches(&turn,23,loan));
    assert(md_fd_origins_loan_matches(&turn,23,replacement)==1);
    md_fd_loan_end(loan);
    assert(!md_fd_origins_loan_next(&turn,&number));
    md_fd_loan_end(replacement);
    assert(md_fd_origins_loan_next(&turn,&number)==1 && number==23);
    assert(!md_fd_origins_close_range(files,23,23,0));
    assert(!md_fd_origins_has_loans(files));
    assert(!md_fd_order_complete(&turn));
    md_fd_loan_release(loan); md_fd_loan_release(replacement); md_fd_origins_release(files);
    *budget=-1;
    puts("PASS allocation-free loan conflicts: exact identity, destructive range, copy-on-unshare and stale/reused slots");
}
static void loan_inventory(int *budget) {
    unsigned before=descriptors();
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct md_fd_loan *loans[3];
    int numbers[]={23,84,145};
    for (unsigned i=0;i<3;++i) {
        loans[i]=md_fd_loan_new(files); assert(loans[i]);
        struct md_fd_origin_update update;
        assert(!prepare(files,FD_CLOEXEC,&update));
        *budget=0;
        assert(md_fd_origins_prepare_loan(&update,loans[i])==-ENOMEM);
        *budget=-1;
        assert(!md_fd_origins_prepare_loan(&update,loans[i]));
        *budget=0; md_fd_origins_finish(&update,numbers[i]); *budget=-1;
    }
    /* A failed copied table must not retain links into its source index. */
    unsigned failures=0,successes=0;
    for (int limit=0;limit<16;++limit) {
        *budget=limit;
        struct md_fd_origins *copy=md_fd_origins_fork(files,0);
        *budget=-1;
        if (!copy) { ++failures; continue; }
        ++successes;
        struct md_fd_order turn={0};
        assert(md_fd_order_begin(copy,&turn,NULL)==1 && !md_fd_order_start(&turn));
        *budget=0;
        for (unsigned i=0;i<3;++i) {
            int number=-1;
            assert(md_fd_origins_loan_next(&turn,&number)==1);
            assert(number==23 || number==84 || number==145);
            assert(!md_fd_origins_close_range(copy,number,number,0));
        }
        assert(!md_fd_origins_has_loans(copy));
        assert(!md_fd_order_complete(&turn));
        md_fd_origins_release(copy); *budget=-1;
    }
    assert(failures && successes);
    /* Retire middle, head, tail in the loan index, all in one hash bucket. */
    int order[]={1,2,0};
    for (unsigned i=0;i<3;++i) {
        struct md_fd_order turn={0};
        assert(md_fd_order_begin(files,&turn,NULL)==1 && !md_fd_order_start(&turn));
        *budget=0;
        int number=-1,index=order[i]; struct md_fd_loan *found=NULL;
        assert(md_fd_origins_loan_range(&turn,numbers[index],numbers[index],&number,&found)==1);
        assert(number==numbers[index] && found==loans[index]);
        md_fd_loan_release(found);
        assert(!md_fd_origins_close_range(files,number,number,0));
        assert(!md_fd_origins_loan_matches(&turn,number,loans[index]));
        assert(md_fd_origins_has_loans(files)==(i<2));
        assert(!md_fd_order_complete(&turn)); *budget=-1;
    }
    for (unsigned i=0;i<3;++i) { md_fd_loan_end(loans[i]); md_fd_loan_release(loans[i]); }
    md_fd_origins_release(files); assert(descriptors()==before);
    puts("PASS independent loan index: sparse ranges, multiple owners, fork allocation rollback and arbitrary retirement order");
}
void test_epoll_owner(int *budget) {
    temporary_slots(budget);
    loan_conflicts(budget);
    loan_inventory(budget);
    extern void test_fd_admission(int *);
    extern void test_fd_admission_live(void);
    test_fd_admission(budget);
    test_fd_admission_live();
    uncertain_aliases(budget);
    uncertain_wakeup(budget);
    staged_admission(budget);
    export_admission(budget);
    target_retirement(budget);
    unsigned before=descriptors();
    for (int limit=0;limit<3;++limit) {
        struct md_fd_origins *files=md_fd_origins_new(); assert(files);
        struct md_fd_origin_update update={0};
        *budget=limit;
        assert(prepare(files,FD_CLOEXEC,&update)==-ENOMEM);
        *budget=0; md_fd_origins_finish(&update,-1); md_fd_origins_release(files);
        *budget=-1;
        assert(descriptors()==before);
    }
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    publish(files,-EMFILE,FD_CLOEXEC,budget);
    assert(descriptors()==before);
    publish(files,10,FD_CLOEXEC,budget);
    struct md_fd_object *object=md_fd_origins_object(files,10,NULL); assert(object);
    struct md_control_epoll *q=md_control_epoll_from_object(object); assert(q);
    assert(md_control_epoll_descriptor(q)==-1 && descriptors()==before);
    expect(files,10,object,FD_CLOEXEC);
    mutation(files,SYS_dup,10,0,0,11,budget);
    mutation(files,SYS_dup3,11,12,O_CLOEXEC,12,budget);
    mutation(files,SYS_fcntl,11,F_DUPFD_CLOEXEC,0,13,budget);
    struct md_fd_origins *child=md_fd_origins_fork(files,0); assert(child);
    struct md_fd_origins *shared=md_fd_origins_fork(files,1); assert(shared==files);
    aliases(files,object,(int[]){10,11,12,13},4,budget);
    aliases(child,object,(int[]){10,11,12,13},4,budget);
    mutation(files,SYS_close,10,0,0,0,budget);
    expect(files,10,NULL,0); expect(child,10,object,FD_CLOEXEC);
    *budget=0; md_fd_origins_exec(child); *budget=-1;
    expect(child,10,NULL,0); expect(child,12,NULL,0); expect(child,13,NULL,0);
    expect(child,11,object,0); expect(shared,12,object,FD_CLOEXEC);
    aliases(files,object,(int[]){11,12,13},3,budget);
    aliases(child,object,(int[]){11},1,budget);
    struct md_fd_origins *private=md_fd_origins_unshare_prepare(shared); assert(private && private!=files);
    mutation(files,SYS_dup3,999,11,0,11,budget);
    expect(files,11,NULL,0); expect(private,11,object,0);
    aliases(files,object,(int[]){12,13},2,budget);
    aliases(private,object,(int[]){11,12,13},3,budget);
    publish(files,11,0,budget);
    struct md_fd_object *replacement=md_fd_origins_object(files,11,NULL);
    assert(replacement && replacement!=object && md_control_epoll_from_object(replacement));
    md_fd_object_release(replacement);
    aliases(files,object,(int[]){12,13},2,budget);

    struct md_identity id=md_identity_new(0,0);
    struct md_uts_namespace *uts=md_uts_namespace_new(); assert(uts);
    struct md_control_file provider=md_proc_sysctl_new(MD_PROC_FILE_HOSTNAME,NULL,uts); assert(provider.ops);
    struct md_proc_file *source=md_proc_file_open_control(provider.ops,provider.context,&(struct md_control_opener){.identity=&id},O_RDONLY);
    md_control_file_release(provider); assert(source);
    struct epoll_event event={.events=EPOLLPRI,.data.u64=UINT64_MAX},out;
    assert(!md_control_epoll_ctl(q,EPOLL_CTL_ADD,40,md_proc_file_object(source),md_proc_file_control(source),&event));
    int transport=md_control_epoll_descriptor(q); assert(transport>=0);
    struct md_fd_message *queued=NULL; int number=11;
    assert(!md_fd_message_capture(child,&number,1,&queued));
    assert(md_fd_message_origins(queued)[0]==object);
    md_fd_object_release(object);
    *budget=0;
    md_fd_origins_release(files); md_fd_origins_release(shared);
    md_fd_origins_release(child); md_fd_origins_release(private);
    *budget=-1;
    assert(!md_uts_namespace_set(uts,0,"queued",6));
    assert(md_control_epoll_collect(q,&out,1)==1 && out.events==(EPOLLPRI|EPOLLERR)
        && out.data.u64==UINT64_MAX);
    files=md_fd_origins_new(); assert(files);
    struct md_fd_origin_update delivery;
    assert(!md_fd_origins_prepare_batch(files,md_fd_message_origins(queued),FD_CLOEXEC,1,&delivery));
    int received=19;
    *budget=0;
    assert(!md_fd_origins_finish_batch(&delivery,1,&received,1));
    md_fd_message_release(queued);
    *budget=-1;
    object=md_fd_origins_object(files,received,NULL);
    assert(md_control_epoll_from_object(object)==q); md_fd_object_release(object);
    assert(!md_control_epoll_ctl(q,EPOLL_CTL_DEL,40,md_proc_file_object(source),(struct md_control_file){0},NULL));
    assert(md_control_epoll_descriptor(q)==transport);
    *budget=0; md_fd_origins_exec(files); md_fd_origins_release(files); *budget=-1;
    assert(fcntl(transport,F_GETFD)==-1 && errno==EBADF);
    md_proc_file_release(source); md_uts_namespace_release(uts); md_identity_release(&id);
    assert(descriptors()==before);
    puts("PASS shared epoll authority: lazy transport, allocation rollback, dup/fork/unshare/exec, queued transfer and FD reuse");
}
