#include "fd_admission.h"
#include "control_epoll.h"
#include <fcntl.h>
#include <assert.h>
#include <errno.h>
#include <stdio.h>

static void publish(struct md_fd_origins *files,struct md_fd_object *object,int number) {
    struct md_fd_origin_update update={0};
    assert(!md_fd_origins_prepare_object(files,object,0,&update));
    md_fd_origins_finish(&update,number);
}
static void require_free(struct md_fd_origins *files) {
    struct md_fd_order turn={0};
    assert(md_fd_order_begin(files,&turn,NULL)==1);
    assert(!md_fd_order_cancel(&turn));
}
static unsigned accept(struct md_fd_admission *a,int *budget) {
    unsigned calls=0; struct md_fd_admission_request request;
    int result;
    while ((result=md_fd_admission_next(a,&request))==1) {
        *budget=0;
        struct md_fd_admission_request repeated;
        assert(md_fd_admission_next(a,&repeated)==1 && repeated.group==request.group
            && repeated.kind==request.kind && repeated.number==request.number);
        repeated.number++;
        assert(md_fd_admission_observed(a,&repeated,0)==-EINVAL);
        assert(md_fd_admission_observed(a,&request,321)==-EAGAIN);
        assert(md_fd_selector_has(request.group,request.kind,request.number)==0);
        assert(!md_fd_admission_observed(a,&request,0));
        assert(md_fd_selector_has(request.group,request.kind,request.number)==1);
        *budget=-1; ++calls;
    }
    assert(result==2); return calls;
}
static void inventory(int *budget) {
    struct md_control_epoll *q=md_control_epoll_new(); assert(q);
    struct md_fd_object *object=md_control_epoll_object(q);
    struct md_fd_origins *a=md_fd_origins_new(),*b=md_fd_origins_new(),*empty=md_fd_origins_new();
    struct md_fd_selector *g=md_fd_selector_new(),*h=md_fd_selector_new();
    assert(a && b && empty && g && h);
    publish(a,object,10); publish(a,object,522); publish(a,object,11);
    publish(b,object,10); publish(b,object,12);
    assert(md_control_epoll_admit_begin(q)==1);
    struct md_fd_admission *admit=md_fd_admission_new(object,1U<<MD_FD_SELECTOR_EPOLL); assert(admit);
    assert(!md_fd_admission_add(admit,a,g) && !md_fd_admission_add(admit,b,g)
        && !md_fd_admission_add(admit,a,h) && !md_fd_admission_add(admit,empty,h));
    *budget=0; assert(!md_fd_admission_add(admit,a,g)); *budget=-1;
    struct md_fd_order running={0};
    assert(md_fd_order_begin(b,&running,NULL)==1 && !md_fd_order_start(&running));
    assert(!md_fd_admission_begin(admit,NULL));
    assert(md_fd_admission_begin(admit,NULL)==-EBUSY);
    *budget=0;
    assert(!md_fd_admission_prepare(admit));
    assert(md_fd_admission_seal(admit)==-EBUSY);
    assert(!md_fd_order_complete(&running)); *budget=-1;
    assert(md_fd_admission_prepare(admit)==1 && !md_fd_admission_seal(admit));
    assert(md_fd_admission_add(admit,b,h)==-EBUSY);
    require_free(empty);
    /* Both groups use table a; group g additionally uses b. Selector residues
     * 10 and 522 coincide, and shared table/group pairs install only once. */
    assert(accept(admit,budget)==5);
    struct md_fd_order later={0}; assert(!md_fd_order_begin(a,&later,NULL));
    assert(md_fd_order_state(&later)==MD_RESOURCE_ORDER_QUEUED);
    assert(!md_control_epoll_admit_finish(q,1));
    *budget=0; md_fd_admission_release(admit); *budget=-1;
    assert(md_fd_order_state(&later)==MD_RESOURCE_ORDER_READY);
    assert(!md_fd_order_cancel(&later));
    require_free(a); require_free(b); require_free(empty);
    assert(md_fd_selector_has(h,MD_FD_SELECTOR_EPOLL,12)==0);
    md_fd_origins_release(a); md_fd_origins_release(b); md_fd_origins_release(empty);
    md_fd_selector_release(g); md_fd_selector_release(h); md_control_epoll_release(q);
}
static void rollback(int *budget) {
    for (int fail=0;fail<12;++fail) {
        struct md_control_epoll *q=md_control_epoll_new(); assert(q);
        struct md_fd_object *object=md_control_epoll_object(q);
        struct md_fd_origins *files=md_fd_origins_new(); assert(files);
        struct md_fd_selector *group=md_fd_selector_new(); assert(group);
        publish(files,object,10); publish(files,object,11);
        struct md_fd_order occupied={0};
        if (fail==11) assert(md_fd_order_begin(files,&occupied,NULL)==1 && !md_fd_order_start(&occupied));
        assert(md_control_epoll_admit_begin(q)==1);
        *budget=fail;
        struct md_fd_admission *admit=md_fd_admission_new(object,1U<<MD_FD_SELECTOR_EPOLL);
        int result=admit ? md_fd_admission_add(admit,files,group) : -ENOMEM;
        if (!result) result=md_fd_admission_begin(admit,NULL);
        struct md_fd_admission_request request;
        if (!result) {
            result=md_fd_admission_prepare(admit);
            if (result==1) {
                result=md_fd_admission_seal(admit);
                if (!result) result=md_fd_admission_next(admit,&request);
            }
        }
        if (result==1) {
            assert(!md_fd_admission_observed(admit,&request,0));
            result=md_fd_admission_next(admit,&request);
            if (result==1) {
                assert(md_fd_admission_observed(admit,&request,-EPERM)==-EPERM);
                assert(md_fd_admission_next(admit,&request)==-EPERM);
            }
        }
        assert(result==-ENOMEM || result==0 || result==1);
        *budget=0;
        assert(!md_control_epoll_admit_finish(q,0)); md_fd_admission_release(admit);
        if (occupied.table) assert(!md_fd_order_complete(&occupied));
        require_free(files); *budget=-1;
        /* Retry reuses any confirmed filter, but never the denied one. */
        assert(md_control_epoll_admit_begin(q)==1);
        admit=md_fd_admission_new(object,1U<<MD_FD_SELECTOR_EPOLL); assert(admit);
        assert(!md_fd_admission_add(admit,files,group) && !md_fd_admission_begin(admit,NULL));
        assert(md_fd_admission_prepare(admit)==1 && !md_fd_admission_seal(admit));
        unsigned count=accept(admit,budget); assert(count==1 || count==2);
        assert(!md_control_epoll_admit_finish(q,1));
        md_fd_admission_release(admit); require_free(files);
        md_fd_origins_release(files); md_fd_selector_release(group); md_control_epoll_release(q);
    }
}
static void late_owner(int *budget) {
    struct md_control_epoll *q=md_control_epoll_new();
    struct md_fd_origins *files=md_fd_origins_new();
    struct md_fd_selector *group=md_fd_selector_new(); assert(q && files && group);
    struct md_fd_object *object=md_control_epoll_object(q);
    publish(files,object,10);
    struct md_fd_order birth={0};
    assert(md_fd_order_begin(files,&birth,NULL)==1 && !md_fd_order_start(&birth));
    assert(md_control_epoll_admit_begin(q)==1);
    struct md_fd_admission *a=md_fd_admission_new(object,1U<<MD_FD_SELECTOR_EPOLL); assert(a);
    assert(!md_fd_admission_add(a,files,group) && !md_fd_admission_begin(a,NULL));
    assert(!md_fd_admission_prepare(a));
    struct md_fd_origins *child=md_fd_origins_fork(files,0); assert(child);
    struct md_fd_selector *child_group=md_fd_selector_fork(group,0); assert(child_group);
    assert(!md_fd_order_complete(&birth));
    assert(md_fd_admission_prepare(a)==1);
    struct md_fd_order mutation={0};
    assert(md_fd_order_begin(child,&mutation,NULL)==1 && !md_fd_order_start(&mutation));
    assert(!md_fd_admission_add(a,child,child_group));
    assert(!md_fd_admission_prepare(a) && md_fd_admission_seal(a)==-EBUSY);
    publish(child,object,12);
    assert(!md_fd_order_complete(&mutation));
    assert(md_fd_admission_prepare(a)==1 && !md_fd_admission_seal(a));
    *budget=0; assert(!md_fd_admission_add(a,child,child_group)); *budget=-1;
    assert(accept(a,budget)==3);
    assert(!md_control_epoll_admit_finish(q,1));
    md_fd_admission_release(a); require_free(files); require_free(child);
    md_fd_origins_release(files); md_fd_origins_release(child);
    md_fd_selector_release(group); md_fd_selector_release(child_group); md_control_epoll_release(q);
}
static void discovery(void) {
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct md_fd_order turn={0},queued={0};
    struct md_fd_observation view,after;
    assert(!md_fd_origins_observe(files,12,&view) && view.generation && !view.selectors);
    assert(md_fd_origins_validate(&turn,view.generation)==-EINVAL);
    assert(md_fd_order_begin(files,&turn,NULL)==1);
    assert(md_fd_origins_validate(&turn,view.generation)==-EBUSY);
    assert(!md_fd_order_start(&turn) && !md_fd_origins_validate(&turn,view.generation));
    assert(!md_fd_order_begin(files,&queued,NULL));
    /* Replacement with no annotation still invalidates prior discovery. */
    publish(files,NULL,12);
    assert(md_fd_origins_validate(&turn,view.generation)==-EAGAIN);
    assert(!md_fd_order_complete(&turn) && !md_fd_order_start(&queued));
    assert(md_fd_origins_validate(&queued,view.generation)==-EAGAIN);
    assert(!md_fd_origins_observe(files,12,&view) && !view.selectors);
    assert(!md_fd_origins_validate(&queued,view.generation));
    assert(!md_fd_order_complete(&queued));
    assert(!md_fd_origins_observe(files,12,&after) && after.generation==view.generation);
    struct md_fd_origin_update cancelled={0};
    assert(!md_fd_origins_prepare_object(files,NULL,0,&cancelled));
    md_fd_origins_finish(&cancelled,-1);
    assert(!md_fd_origins_observe(files,12,&after) && after.generation==view.generation);
    assert(!md_fd_origins_prepare_object(files,NULL,0,&cancelled));
    md_fd_origins_abandon(&cancelled);
    assert(!md_fd_origins_observe(files,12,&after) && after.generation!=view.generation);
    view=after;
    assert(!md_fd_origins_close_range(files,12,12,0));
    assert(!md_fd_origins_observe(files,12,&after) && after.generation!=view.generation);
    struct md_control_epoll *queue=md_control_epoll_new(); assert(queue);
    publish(files,md_control_epoll_object(queue),12);
    assert(!md_fd_origins_observe(files,12,&view) && !view.selectors);
    assert(md_control_epoll_admit_begin(queue)==1);
    assert(!md_fd_origins_observe(files,12,&after) && after.generation==view.generation
        && after.selectors==((1U<<MD_FD_SELECTOR_EPOLL)|(1U<<MD_FD_SELECTOR_EPOLL_TARGET)));
    assert(!md_control_epoll_admit_finish(queue,0));
    md_control_epoll_release(queue); md_fd_origins_release(files);
}
static void retired_group(int *budget) {
    struct md_fd_origins *files=md_fd_origins_new();
    struct md_fd_selector *a=md_fd_selector_new(),*b=md_fd_selector_new();
    struct md_control_epoll *queue=md_control_epoll_new();
    assert(files && a && b && queue);
    publish(files,md_control_epoll_object(queue),42);
    assert(md_control_epoll_admit_begin(queue)==1);
    struct md_fd_admission *admit=md_fd_admission_new(md_control_epoll_object(queue),1U<<MD_FD_SELECTOR_EPOLL);
    assert(admit && !md_fd_admission_add(admit,files,a) && !md_fd_admission_add(admit,files,b));
    assert(!md_fd_admission_begin(admit,NULL) && md_fd_admission_prepare(admit)==1 && !md_fd_admission_seal(admit));
    struct md_fd_admission_request request;
    assert(md_fd_admission_next(admit,&request)==1 && request.group==b);
    *budget=0;
    assert(!md_fd_admission_retire(admit,b));
    assert(!md_fd_admission_retire(admit,b));
    *budget=-1;
    assert(accept(admit,budget)==1);
    assert(!md_control_epoll_admit_finish(queue,1));
    md_fd_admission_release(admit); require_free(files);
    md_fd_origins_release(files); md_control_epoll_release(queue);
    md_fd_selector_release(a); md_fd_selector_release(b);
}
void test_fd_admission(int *budget) {
    inventory(budget); rollback(budget); late_owner(budget); discovery();
    retired_group(budget);
    puts("PASS FD admission inventory: shared/forked tables, groups, alias collisions, queued mutations and confirmed selectors");
    puts("PASS FD admission rollback: allocation failures, partial installation, TSYNC rejection, cancellation and retry");
    puts("PASS FD admission late owner: preceding fork and child mutation join the inventory before sealing");
    puts("PASS FD discovery: ordered generation validation, unannotated replacement, cancelled/uncertain publication and dynamic requirements");
}
