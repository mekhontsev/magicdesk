#define _GNU_SOURCE
#include "interception_buffers.h"
#include "interception_memory.h"
#include "adapter_frame.h"
#include "fd_filter_program.h"
#include "execution_window.h"
#include "spin_policy.h"
#include "cooperative_channel.h"
#include "memory_admission.h"
#include "svc_registry.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <unistd.h>

static int fail_allocation;
static unsigned allocations;
void *__real_calloc(size_t,size_t);
void *__real_malloc(size_t);
void *__real_realloc(void *,size_t);
void *__wrap_calloc(size_t n, size_t size) {
    ++allocations; return fail_allocation ? NULL : __real_calloc(n,size);
}
void *__wrap_malloc(size_t size) {
    ++allocations; return fail_allocation ? NULL : __real_malloc(size);
}
void *__wrap_realloc(void *p, size_t size) {
    ++allocations; return fail_allocation ? NULL : __real_realloc(p,size);
}
static unsigned descriptors(void) {
    DIR *d=opendir("/proc/self/fd"); assert(d);
    unsigned n=0; struct dirent *e;
    while ((e=readdir(d))) if (strcmp(e->d_name,".") && strcmp(e->d_name,"..")) ++n;
    assert(!closedir(d)); return n;
}
struct scope_probe { unsigned acquired, released; int failure; };
static int scope_acquire(void *owner) {
    struct scope_probe *p=owner; ++p->acquired; return p->failure;
}
static int scope_release(void *owner) {
    struct scope_probe *p=owner; ++p->released; return p->failure;
}
static void execution_windows(void) {
    struct md_execution_window window={0};
    struct scope_probe a={0},b={0};
    assert(!md_execution_window_admit(&window,&a,scope_acquire,scope_release,10,2,10));
    assert(!md_execution_window_admit(&window,&a,scope_acquire,scope_release,11,2,10));
    assert(a.acquired==1 && !a.released);
    assert(!md_execution_window_admit(&window,&a,scope_acquire,scope_release,12,2,10));
    assert(a.acquired==2 && a.released==1);
    assert(!md_execution_window_admit(&window,&a,scope_acquire,scope_release,22,2,10));
    assert(a.acquired==3 && a.released==2);
    assert(!md_execution_window_admit(&window,&b,scope_acquire,scope_release,23,2,10));
    assert(a.released==3 && b.acquired==1);
    assert(!md_execution_window_admit(&window,NULL,NULL,NULL,24,0,0));
    assert(!window.owner && b.released==1);
    b.failure=-EIO;
    assert(md_execution_window_admit(&window,&b,scope_acquire,scope_release,25,2,10)==-EIO);
    assert(!window.owner && b.released==1);
    assert(!md_execution_window_admit(&window,&a,scope_acquire,scope_release,26,2,10));
    a.failure=-EIO;
    assert(md_execution_window_end(&window)==-EIO && !window.owner);
    assert(!md_execution_window_end(&window));
    assert(md_execution_window_admit(&window,&a,scope_acquire,scope_release,UINT64_MAX,2,10)==-EINVAL);
    assert(md_execution_window_admit(&window,&a,NULL,scope_release,0,2,10)==-EINVAL);
    assert(md_execution_window_admit(&window,&a,scope_acquire,NULL,0,2,10)==-EINVAL);
    assert(md_execution_window_admit(&window,&a,scope_acquire,scope_release,0,0,10)==-EINVAL);
    struct md_spin_policy spin={0};
    for (unsigned i=0;i<4;++i) { assert(md_spin_budget(&spin,25000)==25000); md_spin_result(&spin,0); }
    for (unsigned i=0;i<128;++i) assert(!md_spin_budget(&spin,25000));
    assert(md_spin_budget(&spin,25000)==25000);
    md_spin_result(&spin,0); md_spin_result(&spin,1); assert(!spin.misses && !spin.cooldown);
    md_spin_observed(&spin,1,0,200); assert(spin.misses==1);
    md_spin_observed(&spin,1,201,200); assert(spin.misses==2);
    md_spin_observed(&spin,1,200,200); assert(spin.misses==3);
    md_spin_observed(&spin,1,199,200); assert(!spin.misses);
    md_spin_observed(&spin,0,100,200); assert(spin.misses==1);
    struct md_entry_channel channel={0};
    assert(md_entry_wait_kind(SYS_openat,0)==MD_ENTRY_WAIT_OPEN);
    assert(md_entry_wait_kind(SYS_openat2,0)==MD_ENTRY_WAIT_OPEN);
    assert(md_entry_wait_kind(SYS_close,0)==MD_ENTRY_WAIT_REPLY);
    assert(md_entry_wait_kind(SYS_newfstatat,0)==MD_ENTRY_WAIT_REPLY);
    assert(md_entry_wait_kind(SYS_openat,1)==MD_ENTRY_WAIT_COMMIT);
    for (unsigned i=0;i<4;++i) md_spin_result(&channel.feedback[MD_ENTRY_WAIT_OPEN],0);
    assert(!md_spin_budget(&channel.feedback[MD_ENTRY_WAIT_OPEN],25000));
    assert(md_spin_budget(&channel.feedback[MD_ENTRY_WAIT_REPLY],25000)==25000);
    assert(md_spin_budget(&channel.feedback[MD_ENTRY_WAIT_COMMIT],25000)==25000);
    puts("PASS execution windows: provider/count/time boundaries, failed acquire/release, teardown, invalid bounds and adaptive parking");
}
static void slots(void) {
    struct md_interception_buffers *p=NULL;
    assert(!md_buffers_create(3,8193,8193,0,&p));
    int fd=md_buffers_fd(p), seals=F_SEAL_SHRINK|F_SEAL_GROW|F_SEAL_SEAL;
    assert((fcntl(fd,F_GETFD)&FD_CLOEXEC) && fcntl(fd,F_GET_SEALS)==seals);
    assert(ftruncate(fd,1)==-1 && errno==EPERM);
    struct md_interception_buffer a={0},b={0},c={0},extra={0},retained={0};
    struct md_buffer_span av,bv,cv;
    fail_allocation=1;
    unsigned before=allocations;
    assert(!md_buffers_take(p,&a) && !md_buffers_take(p,&b) && !md_buffers_take(p,&c));
    assert(md_buffers_take(p,&extra)==-EAGAIN && !extra.pool);
    assert(md_buffers_take(p,&a)==-EBUSY);
    assert(!md_buffer_span(&a,&av) && !md_buffer_span(&b,&bv) && !md_buffer_span(&c,&cv));
    assert(av.size==8193 && bv.size==8193 && cv.size==8193);
    assert(av.offset+av.size<=bv.offset && bv.offset+bv.size<=cv.offset);
    assert(cv.offset+cv.size<=md_buffers_size(p));
    memset(av.data,0x31,av.size); memset(bv.data,0x52,bv.size); memset(cv.data,0x73,cv.size);
    assert(!md_buffer_pin(&b,&retained) && md_buffer_pin(&a,&retained)==-EBUSY);
    struct md_interception_buffer stale=b;
    assert(!md_buffer_release(&b) && !b.pool);
    assert(md_buffers_take(p,&extra)==-EAGAIN);
    assert(!md_buffer_span(&retained,&bv) && ((unsigned char *)bv.data)[8192]==0x52);
    assert(!md_buffer_release(&retained));
    assert(!md_buffers_take(p,&extra) && extra.index==stale.index && extra.generation!=stale.generation);
    assert(md_buffer_span(&stale,&bv)==-ESTALE);
    assert(md_buffer_release(&stale)==-ESTALE);
    assert(!md_buffer_span(&extra,&bv));
    for (unsigned i=0;i<bv.size;++i) assert(!((unsigned char *)bv.data)[i]);
    for (unsigned i=0;i<av.size;++i) assert(((unsigned char *)av.data)[i]==0x31);
    for (unsigned i=0;i<cv.size;++i) assert(((unsigned char *)cv.data)[i]==0x73);
    assert(!md_buffer_release(&a) && !md_buffer_release(&c) && !md_buffer_release(&extra));
    assert(!md_buffer_release(&extra));
    assert(allocations==before);
    fail_allocation=0;
    md_buffers_release(p);
    puts("PASS request buffers: bounded slots, exact spans, sealed size, retained ownership, stale generation and allocation-free reuse");
}
static void spans(void) {
    struct md_interception_buffers *p=NULL;
    size_t page=(size_t)getpagesize(),capacity=page+1;
    assert(!md_buffers_create(4,capacity,64,1,&p));
    struct md_interception_buffer wide={0},pin={0},next={0},control={0};
    struct md_buffer_span span;
    fail_allocation=1;
    assert(md_buffers_take_sized(p,7*page+2,&wide)==-ENOMEM);
    assert(!md_buffers_take_sized(p,5*page+2,&wide));
    assert(!md_buffer_span(&wide,&span) && span.size==7*page+1);
    memset(span.data,13,span.size);
    struct md_interception_buffer interior=wide; ++interior.index;
    assert(md_buffer_span(&interior,&span)==-ESTALE);
    assert(md_buffers_take(p,&next)==-EAGAIN);
    assert(!md_buffers_take_reserved(p,&control));
    assert(!md_buffer_pin(&wide,&pin) && !md_buffer_release(&wide));
    assert(md_buffers_take(p,&next)==-EAGAIN);
    assert(!md_buffer_span(&pin,&span) && ((char *)span.data)[span.size-1]==13);
    assert(!md_buffer_release(&pin));
    assert(!md_buffers_take_sized(p,3*page+1,&wide));
    assert(!md_buffers_take_sized(p,3*page+1,&next));
    assert(!md_buffer_release(&wide) && !md_buffer_release(&next) && !md_buffer_release(&control));
    fail_allocation=0; md_buffers_release(p);
    puts("PASS contiguous request spans: alignment, pinning, reserve, exhaustion and allocation-free release");
}
static void channels(void) {
    struct md_interception_buffers *pool=NULL;
    assert(!md_buffers_create(5,4096,64,1,&pool));
    assert(!md_buffers_partition_channels(pool,2));
    assert(md_buffers_partition_channels(pool,1)==-EBUSY);
    struct md_interception_buffer ordinary[3]={0},channel[2]={0},control={0},extra={0};
    for (unsigned i=0;i<2;++i) assert(!md_buffers_take_channel(pool,&channel[i]));
    assert(md_buffers_take_channel(pool,&extra)==-EAGAIN);
    for (unsigned i=0;i<3;++i) assert(!md_buffers_take(pool,&ordinary[i]));
    assert(md_buffers_take(pool,&extra)==-EAGAIN);
    assert(!md_buffers_take_reserved(pool,&control));
    for (unsigned i=0;i<2;++i) assert(!md_buffer_release(&channel[i]));
    assert(md_buffers_take(pool,&extra)==-EAGAIN);
    for (unsigned i=0;i<3;++i) assert(!md_buffer_release(&ordinary[i]));
    assert(!md_buffer_release(&control)); md_buffers_release(pool);
    puts("PASS persistent channels: separate capacity, ordinary and control reserve, exact release");
}
static void filter_payload(void) {
    struct md_interception_buffers *pool=NULL;
    assert(!md_buffers_create(1,sizeof(union md_adapter_frame),64,1,&pool));
    struct md_interception_buffer caller={0},filter={0},extra={0};
    assert(!md_buffers_take(pool,&caller));
    struct md_buffer_span span; assert(!md_buffer_span(&caller,&span));
    unsigned char *payload=span.data;
    size_t bytes=span.size;
    memset(payload,0x7b,bytes);
    fail_allocation=1;
    unsigned before=allocations;
    assert(md_buffers_take(pool,&extra)==-EAGAIN);
    assert(!md_buffers_take_reserved(pool,&filter));
    assert(md_buffers_take_reserved(pool,&extra)==-EAGAIN);
    assert(!md_buffer_span(&filter,&span));
    assert(span.offset>=bytes && span.size>=sizeof(struct md_task_filter_image));
    struct md_task_filter_image *image=span.data;
    struct md_fd_filter_gates gates={0x100000,0x200000,0x300000,0x400000,0x500000};
    for (unsigned kind=0;kind<MD_FD_SELECTOR_KINDS;++kind) {
        assert(!md_fd_filter_build(kind,123,&gates,&image->code));
        image->program=(struct sock_fprog){image->code.count,image->code.code};
        for (size_t i=0;i<bytes;++i) assert(payload[i]==0x7b);
    }
    assert(!md_buffer_release(&caller));
    assert(!md_buffers_take(pool,&caller));
    assert(image->program.len==image->code.count && image->program.len);
    assert(md_buffers_take(pool,&extra)==-EAGAIN);
    assert(!md_buffer_pin(&filter,&extra) && !md_buffer_release(&filter));
    assert(md_buffers_take_reserved(pool,&filter)==-EAGAIN);
    assert(!md_buffer_span(&extra,&span) && span.data==image);
    assert(!md_buffer_release(&extra));
    assert(!md_buffers_take_reserved(pool,&filter));
    assert(image->program.len==image->code.count && image->program.len);
    assert(!md_buffer_release(&filter) && !md_buffer_release(&caller));
    assert(allocations==before); fail_allocation=0;
    md_buffers_release(pool);
    puts("PASS selector payload: independent control reserve, full IO pool, preserved payload, pin/reuse and no allocation");
}
static void owner_lifetime(void) {
    struct md_interception_buffers *old=NULL,*next=NULL;
    assert(!md_buffers_create(1,65536,65536,0,&old));
    struct md_interception_buffers *fork_owner=md_buffers_retain(old); assert(fork_owner==old);
    struct md_interception_buffer task={0},worker={0},blocked={0};
    assert(!md_buffers_take(old,&task) && !md_buffer_pin(&task,&worker));
    struct md_buffer_span a; assert(!md_buffer_span(&task,&a));
    memset(a.data,0x6b,a.size);
    int fd=md_buffers_fd(old);
    md_buffers_release(old);
    assert(!md_buffer_release(&task));
    assert(md_buffers_take(fork_owner,&blocked)==-EAGAIN);
    md_buffers_release(fork_owner);
    assert(!md_buffers_create(1,65536,65536,0,&next));
    assert(!md_buffers_take(next,&task));
    struct md_buffer_span b; assert(!md_buffer_span(&task,&b));
    assert(b.data!=a.data && !((unsigned char *)b.data)[0]);
    assert(!md_buffer_span(&worker,&a) && ((unsigned char *)a.data)[65535]==0x6b);
    assert(fcntl(fd,F_GETFD)>=0);
    assert(!md_buffer_release(&worker));
    assert(fcntl(fd,F_GETFD)==-1 && errno==EBADF);
    assert(!md_buffer_release(&task)); md_buffers_release(next);
    puts("PASS request buffers: request survives mapping-owner release; replacement image has independent storage");
}
static void failures(void) {
    struct md_interception_buffers *p=NULL;
    assert(md_buffers_create(0,1,1,0,&p)==-EINVAL && !p);
    assert(md_buffers_create(1,0,0,0,&p)==-EINVAL && !p);
    assert(md_buffers_create(1,SIZE_MAX,SIZE_MAX,0,&p)==-EOVERFLOW && !p);
    assert(md_buffers_create(2,PTRDIFF_MAX,PTRDIFF_MAX,0,&p)==-EOVERFLOW && !p);
    assert(md_buffers_create(1,4096,0,UINT_MAX,&p)==-EOVERFLOW && !p);
    fail_allocation=1;
    assert(md_buffers_create(1,4096,4096,0,&p)==-ENOMEM && !p);
    fail_allocation=0;
    struct rlimit saved; assert(!getrlimit(RLIMIT_NOFILE,&saved) && saved.rlim_max>=64);
    struct rlimit limit=saved; limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
    int fds[64],n=0,fd;
    while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) { assert(n<64); fds[n++]=fd; }
    assert(errno==EMFILE && n);
    assert(md_buffers_create(1,4096,4096,0,&p)==-EMFILE && !p);
    for (int i=0;i<n;++i) assert(!close(fds[i]));
    assert(!setrlimit(RLIMIT_NOFILE,&saved));
    puts("PASS request buffers: invalid sizes, allocation failure and exhausted provider FD table leave no pool");
}
static void address_spaces(void) {
    struct md_interception_memory *parent=md_memory_new(); assert(parent);
    fail_allocation=1;
    assert(md_memory_buffers_prepare(parent,2,5000,5000,0)==-ENOMEM && !md_memory_buffers(parent));
    fail_allocation=0;
    assert(!md_memory_buffers_prepare(parent,2,5000,5000,0));
    assert(md_memory_buffers_prepare(parent,2,5000,5000,0)==-EALREADY);
    struct md_interception_buffers *pool=md_memory_buffers(parent);
    size_t size=md_buffers_size(pool);
    struct md_interception_buffer a={0},b={0},worker={0},extra={0};
    assert(!md_buffers_take(pool,&a));
    assert(!md_memory_buffer_address(parent,&a));
    assert(md_memory_buffers_mapped(parent,0x40000001,size)==-EINVAL);
    assert(md_memory_buffers_mapped(parent,0x40000000,size+1)==-EINVAL);
    assert(!md_memory_buffers_mapped(parent,0x40000000,size));
    assert(md_memory_buffers_mapped(parent,0x40000000,size)==-EALREADY);
    struct md_interception_memory *open1=NULL,*open2=NULL;
    assert(!md_memory_exposed(parent));
    assert(!md_memory_exposure_begin(parent,&open1) && md_memory_exposed(parent));
    assert(md_memory_exposure_begin(parent,&open1)==-EINVAL);
    assert(!md_memory_exposure_begin(parent,&open2));
    md_memory_exposure_end(&open1,0);
    assert(!open1 && md_memory_exposed(parent));
    md_memory_exposure_end(&open2,0);
    assert(!open2 && !md_memory_exposed(parent));
    assert(!md_memory_exposure_begin(parent,&open1));
    assert(!md_memory_exposure_begin(parent,&open2));
    md_memory_exposure_end(&open1,1);
    md_memory_exposure_end(&open2,0);
    assert(md_memory_exposed(parent));
    struct md_interception_memory *thread=md_memory_fork(parent,1);
    struct md_interception_memory *child=md_memory_fork(parent,0);
    assert(thread==parent && child && child!=parent && md_memory_buffers(child)==pool);
    assert(md_memory_exposed(thread) && !md_memory_exposed(child));
    struct md_interception_memory *image=md_memory_new_image(parent); assert(image);
    assert(!md_memory_exposed(image)); md_memory_release(image);
    image=md_memory_new_image(parent); assert(image);
    assert(!md_memory_exposure_begin(image,&open1));
    md_memory_release(image);
    assert(md_memory_exposed(open1));
    md_memory_exposure_end(&open1,1);
    assert(!open1);
    assert(!md_memory_exposure_begin(NULL,&open1));
    md_memory_exposure_end(&open1,0);
    struct md_resource_order first={0}, next={0}, independent={0};
    assert(md_memory_order_begin(parent,&first,NULL)==1 && !md_resource_order_start(&first));
    assert(md_memory_order_begin(thread,&next,NULL)==0);
    assert(md_memory_order_begin(child,&independent,NULL)==1 && !md_resource_order_start(&independent));
    assert(md_resource_order_state(&next)==MD_RESOURCE_ORDER_QUEUED);
    assert(md_resource_order_cancel(&first)==1);
    assert(md_resource_order_state(&next)==MD_RESOURCE_ORDER_QUEUED);
    assert(!md_resource_order_complete(&first));
    assert(md_resource_order_state(&next)==MD_RESOURCE_ORDER_READY);
    assert(!md_resource_order_cancel(&next) && !md_resource_order_complete(&independent));
    assert(!md_buffers_take(md_memory_buffers(child),&b));
    assert(md_memory_buffer_address(parent,&a)==md_memory_buffer_address(child,&a));
    assert(md_memory_buffer_address(child,&a)!=md_memory_buffer_address(child,&b));
    assert(md_buffers_take(pool,&extra)==-EAGAIN);
    assert(!md_buffer_pin(&a,&worker));
    int fd=md_buffers_fd(pool);
    md_memory_release(parent); md_memory_release(thread); md_memory_release(child);
    assert(!md_buffer_release(&a) && !md_buffer_release(&b));
    assert(fcntl(fd,F_GETFD)>=0);
    struct md_interception_memory *exec=md_memory_new(); assert(exec);
    assert(!md_memory_buffers(exec) && !md_memory_buffer_address(exec,&worker));
    assert(!md_memory_buffers_prepare(exec,1,4096,4096,0));
    assert(md_memory_buffers(exec)!=worker.pool);
    md_memory_release(exec);
    assert(!md_buffer_release(&worker) && fcntl(fd,F_GETFD)==-1 && errno==EBADF);
    puts("PASS adapter memory: CLONE_VM owner, fork-shared allocator, exact mapping admission, exec isolation and terminal worker pins");
}
static void payload_reuse(void) {
    struct md_interception_buffers *pool=NULL;
    assert(md_buffers_create(1,64,65,0,&pool)==-EINVAL && !pool);
    assert(!md_buffers_create(1,65536,64,0,&pool));
    struct md_interception_buffer slot={0}; struct md_buffer_span span;
    assert(!md_buffers_take(pool,&slot) && !md_buffer_span(&slot,&span));
    memset(span.data,0x7a,span.size);
    assert(!md_buffer_release(&slot));
    unsigned before=allocations; fail_allocation=1;
    assert(!md_buffers_take(pool,&slot) && !md_buffer_span(&slot,&span));
    for (size_t i=0;i<span.size;++i) assert(((unsigned char *)span.data)[i]==(i<64 ? 0 : 0x7a));
    assert(allocations==before && !md_buffer_release(&slot));
    fail_allocation=0; md_buffers_release(pool);
    puts("PASS buffer payload reuse: only declared header cleared, no allocation or bulk overwrite");
}
static void mapping_policy(void) {
    struct md_interception_memory *m=md_memory_new(); assert(m);
    struct md_mapping_change c={0};
    size_t p=(size_t)getpagesize(); uintptr_t base=0x10000000;
    unsigned before=allocations;
    fail_allocation=1;
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_MAP,0,p,0,0,0));
    md_memory_mapping_finish(m,&c,(long)base);
    assert(!md_memory_has_policy(m) && !md_memory_protect_check(m,base,p,PROT_EXEC));
    assert(!md_memory_mapping_observe(m,base,p));
    assert(md_memory_mapping_observe(m,base,SIZE_MAX));
    assert(md_memory_mapping_observe(m,UINTPTR_MAX-p+1,p));
    assert(allocations==before);
    assert(md_memory_mapping_prepare(m,&c,MD_MAPPING_MAP,0,3*p,0,0,1)==-ENOMEM && !c.prepared);
    fail_allocation=0;
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_MAP,0,2*p,0,MAP_SHARED,MD_MAPPING_NOWRITE));
    md_memory_mapping_finish(m,&c,base+40*p);
    assert(!md_memory_mapping_observe(m,base,40*p));
    assert(md_memory_mapping_observe(m,base,40*p+1));
    assert(md_memory_mapping_observe(m,base+40*p,p));
    assert(!md_memory_mapping_observe(m,base+42*p,p));
    struct md_interception_memory *shared=md_memory_fork(m,1); assert(shared==m);
    assert(md_memory_mapping_observe(m,base,p));
    md_memory_release(shared);
    assert(!md_memory_mapping_observe(m,base,p));
    assert(!md_memory_protect_check(m,base+40*p,p,PROT_EXEC));
    assert(md_memory_protect_check(m,base+40*p,p,PROT_WRITE)==-EACCES);
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_MAP,0,p,0,MAP_SHARED,MD_MAPPING_NOEXEC));
    md_memory_mapping_finish(m,&c,base+42*p);
    assert(!md_memory_protect_check(m,base+42*p,p,PROT_WRITE));
    assert(md_memory_protect_check(m,base+42*p,p,PROT_EXEC)==-EACCES);
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_REMAP,base+40*p,2*p,3*p,MREMAP_MAYMOVE,0));
    md_memory_mapping_finish(m,&c,base+50*p);
    assert(!md_memory_protect_check(m,base+40*p,p,PROT_WRITE));
    assert(md_memory_protect_check(m,base+52*p,p,PROT_WRITE)==-EACCES);
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_UNMAP,base+40*p,20*p,0,0,0));
    md_memory_mapping_finish(m,&c,0);
    assert(!md_memory_has_policy(m));
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_MAP,0,3*p,0,0,1));
    md_memory_mapping_finish(m,&c,-ENOMEM);
    assert(!md_memory_has_policy(m));
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_MAP,0,3*p,0,0,1));
    md_memory_mapping_finish(m,&c,(long)base);
    assert(md_memory_protect_check(m,base,p,PROT_EXEC)==-EACCES);
    struct md_interception_memory *copy=md_memory_fork(m,0); assert(copy);
    before=allocations; fail_allocation=1;
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_UNMAP,base+p,p,0,0,0));
    md_memory_mapping_finish(m,&c,0);
    assert(md_memory_protect_check(m,base,p,PROT_EXEC)==-EACCES);
    assert(!md_memory_protect_check(m,base+p,p,PROT_EXEC));
    assert(md_memory_protect_check(m,base+2*p,p,PROT_EXEC)==-EACCES);
    assert(md_memory_protect_check(copy,base+p,p,PROT_EXEC)==-EACCES);
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_REMAP,base,p,2*p,MREMAP_MAYMOVE|MREMAP_FIXED,0));
    md_memory_mapping_finish(m,&c,(long)(base+10*p));
    assert(!md_memory_protect_check(m,base,p,PROT_EXEC));
    assert(md_memory_protect_check(m,base+11*p,p,PROT_EXEC)==-EACCES);
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_REMAP,base+10*p,0,p,MREMAP_MAYMOVE,0));
    md_memory_mapping_finish(m,&c,(long)(base+20*p));
    assert(md_memory_protect_check(m,base+10*p,p,PROT_EXEC)==-EACCES);
    assert(md_memory_protect_check(m,base+20*p,p,PROT_EXEC)==-EACCES);
    assert(allocations==before);
    fail_allocation=0;
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_MAP,base,p,0,0,1));
    md_memory_mapping_abandon(m,&c);
    assert(md_memory_mapping_observe(m,base+100*p,p));
    assert(md_memory_protect_check(m,base,p,PROT_EXEC)==-EIO);
    assert(md_memory_protect_check(copy,base,p,PROT_EXEC)==-EACCES);
    assert(md_memory_protect_check(copy,base+1,p,PROT_EXEC)==-EINVAL);
    assert(md_memory_protect_check(copy,base,SIZE_MAX,PROT_EXEC)==-ENOMEM);
    md_memory_release(m); md_memory_release(copy);
    puts("PASS mapping policy: allocation-free ordinary IO, prepare rollback, split/move/clone, fork independence and unknown terminal result");
}
static void native_memory_admission(void) {
    assert(md_memory_native_arguments(SYS_munmap,UINT64_MAX,UINT64_MAX));
    assert(md_memory_native_arguments(SYS_mprotect,PROT_NONE,0));
    assert(!md_memory_native_arguments(SYS_mprotect,PROT_READ|PROT_GROWSDOWN,0));
    assert(md_memory_native_arguments(SYS_mmap,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED));
    assert(!md_memory_native_arguments(SYS_mmap,PROT_READ,MAP_PRIVATE|MAP_FIXED));
    assert(!md_memory_native_arguments(SYS_mmap,PROT_READ,MAP_SHARED|MAP_ANONYMOUS|MAP_FIXED));
    assert(!md_memory_native_arguments(SYS_mmap,PROT_READ,MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE));
    assert(!md_memory_native_arguments(SYS_mremap,0,0));
    assert(md_memory_native_arguments(SYS_mremap,4096,0));
    assert(md_memory_native_arguments(SYS_mremap,8192,MREMAP_MAYMOVE));
    assert(!md_memory_native_arguments(SYS_mremap,8192,MREMAP_MAYMOVE|MREMAP_FIXED));
    assert(!md_memory_native_arguments(SYS_mremap,8192,MREMAP_MAYMOVE|MREMAP_DONTUNMAP));
    size_t page=(size_t)getpagesize(); uintptr_t base=0x4000000;
    struct md_interception_memory *m=md_memory_new(); assert(m);
    struct md_svc_registry *r=calloc(1,sizeof(*r)); assert(r);
    r->capacity=2; r->sites=calloc(r->capacity,sizeof(*r->sites)); assert(r->sites);
    r->native_begin=base+300*page; r->native_end=r->native_begin+page;
    r->frames=base+400*page;
    assert(!md_svc_registry_pool(r,base+10000*page));
    r->count=1; r->sites[0].direct.svc=base+4*page+128;
    r->sites[0].direct.gate=r->pools[0].address;
    assert(!md_memory_svc_set(m,r));
    struct md_memory_admission p={0};
    md_memory_admission_publish(m,&p,1);
    assert(md_memory_admission_allows(&p,base,page,page));
    assert(!md_memory_admission_allows(&p,base+1,page,page));
    assert(!md_memory_admission_allows(&p,base,0,page));
    assert(!md_memory_admission_allows(&p,base,SIZE_MAX,page));
    assert(!md_memory_admission_allows(&p,UINTPTR_MAX-page+1,page,page));
    assert(md_memory_admission_allows(&p,base,4*page,page));
    assert(!md_memory_admission_allows(&p,base,4*page+1,page));
    assert(!md_memory_admission_allows(&p,r->native_begin,page,page));
    assert(!md_memory_admission_allows(&p,r->frames,page,page));
    assert(!md_memory_admission_allows(&p,r->pools[0].address,page,page));
    md_memory_admission_publish(m,&p,0); assert(!atomic_load(&p.enabled));
    md_memory_admission_publish(m,&p,1); assert(atomic_load(&p.enabled));
    r->sites[r->count++].direct=(struct md_svc_direct_context){
        .svc=base+8*page,.gate=r->pools[0].address+2048};
    md_memory_admission_publish(m,&p,1);
    assert(!md_memory_admission_allows(&p,base+8*page,page,page));
    assert(!md_svc_registry_pool(r,base+20000*page));
    md_memory_admission_publish(m,&p,1);
    assert(!md_memory_admission_allows(&p,base+20000*page,page,page));
    r->disabled=1;
    md_memory_admission_publish(m,&p,1); assert(!atomic_load(&p.enabled));
    r->disabled=0;
    md_memory_admission_publish(m,&p,1); assert(atomic_load(&p.enabled));
    struct md_interception_memory *shared=md_memory_fork(m,1); assert(shared==m);
    assert(!atomic_load(&p.enabled));
    md_memory_admission_publish(m,&p,1); assert(!atomic_load(&p.enabled));
    md_memory_release(shared);
    md_memory_admission_publish(m,&p,1); assert(atomic_load(&p.enabled));
    struct md_interception_memory *copy=md_memory_fork(m,0); assert(copy);
    struct md_memory_admission child={0};
    md_memory_admission_publish(copy,&child,1); assert(atomic_load(&child.enabled));
    md_memory_admission_detach(copy,&child); md_memory_release(copy);
    struct md_mapping_change change={0};
    assert(!md_memory_mapping_prepare(m,&change,MD_MAPPING_MAP,0,page,0,0,MD_MAPPING_NOEXEC));
    md_memory_mapping_finish(m,&change,base);
    md_memory_admission_publish(m,&p,1);
    assert(!md_memory_admission_allows(&p,base,page,page));
    assert(md_memory_admission_allows(&p,base+page,page,page));
    assert(!md_memory_mapping_prepare(m,&change,MD_MAPPING_UNMAP,base,page,0,0,0));
    md_memory_mapping_finish(m,&change,0);
    md_memory_admission_publish(m,&p,1);
    assert(md_memory_admission_allows(&p,base,page,page));
    for (unsigned i=1;i<=MD_MEMORY_ADMISSION_RANGES;++i) {
        assert(!md_memory_mapping_prepare(m,&change,MD_MAPPING_MAP,0,page,0,0,MD_MAPPING_NOEXEC));
        md_memory_mapping_finish(m,&change,base+2*i*page);
    }
    md_memory_admission_publish(m,&p,1); assert(!atomic_load(&p.enabled));
    md_memory_admission_detach(m,&p); md_memory_release(m);
    puts("PASS native memory admission: rounded ranges, cached exclusions, mapping/site invalidation, revocation, fork and bounded fallback");
}
static void file_views(void) {
    size_t p=(size_t)getpagesize(); uintptr_t base=0x20000000;
    int fd=memfd_create("mapping-owner",MFD_CLOEXEC); assert(fd>=0 && !ftruncate(fd,8*p));
    struct md_interception_memory *m=md_memory_new(); assert(m);
    struct md_mapping_change c={0}; int source=-1; uint64_t offset=0;
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_MAP,0,3*p,0,MAP_PRIVATE,0));
    assert(!md_memory_mapping_backing(m,&c,fd,p));
    unsigned before=descriptors();
    md_memory_mapping_finish(m,&c,-ENOMEM);
    assert(descriptors()+1==before && !md_memory_has_policy(m));
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_MAP,0,3*p,0,MAP_PRIVATE,0));
    fail_allocation=1;
    assert(md_memory_mapping_backing(m,&c,fd,p)==-ENOMEM && !c.backing);
    fail_allocation=0;
    assert(md_memory_mapping_backing(m,&c,fd,UINT64_MAX)==-EINVAL);
    assert(!md_memory_mapping_backing(m,&c,fd,p));
    fail_allocation=1;
    md_memory_mapping_finish(m,&c,(long)base);
    assert(md_memory_file_view(m,base,3*p,&source,&offset)==1 && offset==p && source!=fd);
    assert(md_memory_mapping_observe(m,base-p,p+1));
    assert(md_memory_mapping_observe(m,base+2*p,p));
    assert(!md_memory_mapping_observe(m,base+3*p,p));
    assert((fcntl(source,F_GETFD)&FD_CLOEXEC) && !close(fd));
    fail_allocation=0;
    struct md_interception_memory *copy=md_memory_fork(m,0); assert(copy);
    before=descriptors();
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_UNMAP,base+p,p,0,0,0));
    fail_allocation=1; md_memory_mapping_finish(m,&c,0); fail_allocation=0;
    assert(!md_memory_file_view(m,base,3*p,&source,&offset));
    assert(!md_memory_mapping_observe(m,base+p,p));
    assert(md_memory_mapping_observe(copy,base+p,p));
    assert(md_memory_file_view(m,base,p,&source,&offset)==1 && offset==p);
    assert(md_memory_file_view(m,base+2*p,p,&source,&offset)==1 && offset==3*p);
    assert(md_memory_file_view(copy,base,3*p,&source,&offset)==1 && offset==p);
    assert(descriptors()==before);
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_REMAP,base+2*p,p,2*p,MREMAP_MAYMOVE,0));
    fail_allocation=1; md_memory_mapping_finish(m,&c,(long)(base+10*p)); fail_allocation=0;
    assert(!md_memory_file_view(m,base+2*p,p,&source,&offset));
    assert(md_memory_file_view(m,base+10*p,2*p,&source,&offset)==1 && offset==3*p);
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_REMAP,base+10*p,2*p,p,MREMAP_MAYMOVE|MREMAP_DONTUNMAP,0));
    fail_allocation=1; md_memory_mapping_finish(m,&c,(long)(base+20*p)); fail_allocation=0;
    assert(md_memory_file_view(m,base+10*p,2*p,&source,&offset)==1 && offset==3*p);
    assert(md_memory_file_view(m,base+20*p,p,&source,&offset)==1 && offset==3*p);
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_MAP,base+10*p,p,0,MAP_ANONYMOUS|MAP_PRIVATE|MAP_FIXED,0));
    fail_allocation=1; md_memory_mapping_finish(m,&c,(long)(base+10*p)); fail_allocation=0;
    assert(!md_memory_file_view(m,base+10*p,p,&source,&offset));
    assert(md_memory_file_view(m,base+11*p,p,&source,&offset)==1 && offset==4*p);
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_REMAP,base+11*p,p,p,MREMAP_MAYMOVE,0));
    md_memory_mapping_finish(m,&c,-ENOMEM);
    assert(md_memory_file_view(m,base+11*p,p,&source,&offset)==1 && offset==4*p);
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_UNMAP,base,32*p,0,0,0));
    md_memory_mapping_finish(m,&c,0);
    assert(!md_memory_has_policy(m) && fcntl(source,F_GETFD)>=0);
    md_memory_release(m);
    assert(md_memory_file_view(copy,base,3*p,&source,&offset)==1 && offset==p);
    md_memory_release(copy);
    assert(fcntl(source,F_GETFD)==-1 && errno==EBADF);
    puts("PASS private mapping provenance: closed input, fork retention, split, move/grow/shrink, DONTUNMAP, replacement, rollback and allocation-free publication");
}
static void file_view_failures(void) {
    size_t p=(size_t)getpagesize(); uintptr_t base=0x30000000;
    int fd=memfd_create("mapping-failures",MFD_CLOEXEC); assert(fd>=0 && !ftruncate(fd,8*p));
    struct md_interception_memory *m=md_memory_new(); assert(m);
    struct md_mapping_change c={0}; int source=-1; uint64_t offset=0;
    unsigned empty=descriptors();
    for (unsigned i=0;i<2;++i) {
        assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_MAP,0,p,0,MAP_PRIVATE,0));
        assert(!md_memory_mapping_backing(m,&c,fd,i*p));
        md_memory_mapping_finish(m,&c,(long)(base+i*p));
    }
    assert(md_memory_file_view(m,base,2*p,&source,&offset)==1 && offset==0);
    unsigned before=descriptors();
    fail_allocation=1;
    assert(!md_memory_fork(m,0));
    fail_allocation=0;
    struct md_interception_memory *pressure=md_memory_fork(m,0); assert(pressure);
    assert(!md_memory_mapping_prepare(pressure,&c,MD_MAPPING_MAP,0,p,0,MAP_PRIVATE,1));
    md_memory_mapping_finish(pressure,&c,(long)(base+30*p));
    fail_allocation=1;
    assert(!md_memory_mapping_prepare(pressure,&c,MD_MAPPING_REMAP,base,2*p,2*p,MREMAP_MAYMOVE,0));
    md_memory_mapping_finish(pressure,&c,(long)(base+10*p));
    fail_allocation=0;
    assert(!md_memory_file_view(pressure,base+10*p,p,&source,&offset));
    assert(!md_memory_protect_check(pressure,base+10*p,p,PROT_EXEC));
    assert(md_memory_protect_check(pressure,base+30*p,p,PROT_EXEC)==-EACCES);
    md_memory_release(pressure);
    assert(descriptors()==before && md_memory_file_view(m,base,2*p,&source,&offset)==1);
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_REMAP,base,2*p,2*p,MREMAP_MAYMOVE,0));
    fail_allocation=1; md_memory_mapping_finish(m,&c,(long)(base+10*p)); fail_allocation=0;
    assert(md_memory_file_view(m,base+10*p,2*p,&source,&offset)==1 && !offset);
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_MAP,0,p,0,MAP_PRIVATE,0));
    assert(md_memory_mapping_backing(m,&c,-1,0)==-EBADF && !c.backing);
    md_memory_mapping_finish(m,&c,-EBADF);
    assert(!md_memory_mapping_prepare(m,&c,MD_MAPPING_MAP,0,p,0,MAP_PRIVATE,0));
    assert(!md_memory_mapping_backing(m,&c,fd,0));
    md_memory_mapping_abandon(m,&c);
    assert(!md_memory_file_view(m,base+10*p,p,&source,&offset));
    assert(!md_memory_protect_check(m,base+10*p,p,PROT_EXEC));
    assert(descriptors()==empty);
    md_memory_release(m); close(fd);
    puts("PASS private mapping failures: shared same-file backing, failed fork/move/capture and unknown publication retain no unsafe provenance");
}
static void file_backing_lifetime(void) {
    unsigned initial=descriptors();
    size_t page=(size_t)getpagesize();
    uintptr_t base=0x100000;
    char path[]=".md-mapping-backing-XXXXXX";
    int fd=mkstemp(path);
    int other=memfd_create("distinct-mapping-backing",MFD_CLOEXEC);
    assert(fd>=0 && other>=0 && !ftruncate(fd,32*page) && !ftruncate(other,page));
    int reopened=open(path,O_RDWR|O_CLOEXEC);
    assert(!unlink(path) && reopened>=0);
    struct md_interception_memory *root=md_memory_new(); assert(root);
    struct md_interception_memory *images[32];
    int source=-1, shared=-1; uint64_t offset=0;
    unsigned before=descriptors();
    for (unsigned i=0;i<32;++i) {
        images[i]=md_memory_new_image(root); assert(images[i]);
        assert(!md_memory_file_view(images[i],base,page,&source,&offset));
        for (unsigned j=0;j<8;++j) {
            struct md_mapping_change c={0};
            assert(!md_memory_mapping_prepare(images[i],&c,MD_MAPPING_MAP,0,page,0,MAP_PRIVATE,0));
            assert(!md_memory_mapping_backing(images[i],&c,j&1 ? reopened : fd,j*page));
            md_memory_mapping_finish(images[i],&c,(long)(base+j*page));
        }
        assert(md_memory_file_view(images[i],base,8*page,&source,&offset) && !offset);
        if (!i) shared=source;
        assert(source==shared && descriptors()==before+1);
    }
    struct md_mapping_change failed={0};
    assert(!md_memory_mapping_prepare(root,&failed,MD_MAPPING_MAP,0,page,0,MAP_PRIVATE,0));
    assert(!md_memory_mapping_backing(root,&failed,other,0));
    assert(descriptors()==before+2);
    md_memory_mapping_finish(root,&failed,-ENOMEM);
    assert(descriptors()==before+1);
    struct md_interception_memory *child=md_memory_fork(images[0],0); assert(child);
    assert(!fcntl(fd,F_SETFL,fcntl(fd,F_GETFL)|O_APPEND|O_NONBLOCK));
    assert((fcntl(shared,F_GETFL)&(O_ACCMODE|O_APPEND|O_NONBLOCK))==O_RDONLY);
    md_memory_release(root); close(fd); close(reopened); close(other);
    for (unsigned i=0;i<32;++i) md_memory_release(images[i]);
    assert(md_memory_file_view(child,base,8*page,&source,&offset) && source==shared);
    assert(descriptors()==initial+1);
    struct md_mapping_change remove={0};
    assert(!md_memory_mapping_prepare(child,&remove,MD_MAPPING_UNMAP,base,8*page,0,0,0));
    md_memory_mapping_finish(child,&remove,0);
    assert(!md_memory_file_view(child,base,page,&source,&offset));
    assert(descriptors()==initial);
    md_memory_release(child);
    assert(descriptors()==initial);
    puts("PASS file backing ownership: independent opens, 32 exec images, 256 mappings, fork, failed mmap and final unmap retain exactly one FD per file");
}
static void image_backings(void) {
    unsigned initial=descriptors();
    size_t page=(size_t)getpagesize();
    int files[2];
    void *segments[2][3];
    for (unsigned i=0;i<2;++i) {
        char path[]=".md-image-backing-XXXXXX";
        files[i]=mkstemp(path);
        assert(files[i]>=0 && !unlink(path));
        assert(files[i]>=0 && !ftruncate(files[i],3*page));
        for (unsigned j=0;j<3;++j) {
            segments[i][j]=mmap(NULL,page,j==1 ? PROT_NONE : PROT_READ,
                j==2 ? MAP_SHARED : MAP_PRIVATE,files[i],j*page);
            assert(segments[i][j]!=MAP_FAILED);
        }
    }
    struct md_interception_memory *image=md_memory_new(); assert(image);
    unsigned before=descriptors();
    assert(md_memory_image_backings(image,getpid(),files,0)==-EINVAL);
    assert(md_memory_image_backings(image,getpid(),files,3)==-EINVAL);
    int invalid[]={files[0],-1};
    assert(md_memory_image_backings(image,getpid(),invalid,2)==-EBADF);
    fail_allocation=1;
    assert(md_memory_image_backings(image,getpid(),files,2)==-ENOMEM);
    fail_allocation=0;
    assert(descriptors()==before);
    assert(!md_memory_image_backings(image,getpid(),files,2));
    assert(descriptors()==before+2);
    for (unsigned i=0;i<2;++i) {
        int source=-1; uint64_t offset;
        for (unsigned j=0;j<2;++j) {
            assert(md_memory_file_view(image,(uintptr_t)segments[i][j],page,&source,&offset));
            assert(offset==j*page && (fcntl(source,F_GETFL)&O_ACCMODE)==O_RDONLY);
            struct stat a,b;
            assert(!fstat(source,&a) && !fstat(files[i],&b) && a.st_ino==b.st_ino && a.st_dev==b.st_dev);
        }
        assert(!md_memory_file_view(image,(uintptr_t)segments[i][2],page,&source,&offset));
        close(files[i]);
        for (unsigned j=0;j<3;++j) assert(!munmap(segments[i][j],page));
    }
    md_memory_release(image);
    assert(descriptors()==initial);
    puts("PASS ELF backing admission: one observation, two identities, protected private segments, shared exclusion and allocation failure");
}
static void memory_access_lifetime(void) {
    struct md_interception_memory *parent=md_memory_new(); assert(parent);
    int fd=memfd_create("memory-access-lifetime",MFD_CLOEXEC); assert(fd>=0);
    struct md_private_memory_access inputs={fd,fd,-1};
    unsigned before=descriptors();
    assert(md_memory_private_access_set(parent,&inputs)==-EBADF);
    assert(!md_memory_private_access(parent) && descriptors()==before);
    inputs.pagemap=fd;
    assert(!md_memory_private_access_set(parent,&inputs));
    assert(md_memory_private_access_set(parent,&inputs)==-EALREADY);
    struct md_interception_memory *shared=md_memory_fork(parent,1), *child=md_memory_fork(parent,0);
    assert(shared==parent && child && !md_memory_private_access(child));
    const struct md_private_memory_access *access=md_memory_private_access(parent);
    assert(access && access==md_memory_private_access(shared));
    int retained[]={access->maps,access->memory,access->pagemap};
    close(fd);
    md_memory_release(parent);
    for (unsigned i=0;i<3;++i) assert(fcntl(retained[i],F_GETFD)&FD_CLOEXEC);
    md_memory_release(shared);
    for (unsigned i=0;i<3;++i) assert(fcntl(retained[i],F_GETFD)==-1 && errno==EBADF);
    md_memory_release(child);
    puts("PASS private-memory authority: transactional capture, CLONE_VM retention and independent fork address space");
}
static void memory_access_capture(void) {
    struct md_interception_memory *m=md_memory_new(); assert(m);
    assert(md_memory_private_access_capture(m,0)==-EINVAL);
    struct rlimit saved; assert(!getrlimit(RLIMIT_NOFILE,&saved) && saved.rlim_max>=64);
    struct rlimit limit=saved; limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
    int fds[64], n=0, fd;
    while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) { assert(n<64); fds[n++]=fd; }
    assert(errno==EMFILE && n>=3);
    for (unsigned available=0;available<4;++available) {
        assert(md_memory_private_access_capture(m,getpid())==-EMFILE && !md_memory_private_access(m));
        if (available<3) assert(!close(fds[--n]));
    }
    for (int i=0;i<n;++i) assert(!close(fds[i]));
    assert(!setrlimit(RLIMIT_NOFILE,&saved));
    fail_allocation=1;
    assert(!md_memory_private_access_capture(m,getpid()));
    fail_allocation=0;
    assert(md_memory_private_access_capture(m,getpid())==-EALREADY);
    unsigned char *memory=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(memory!=MAP_FAILED); *memory=19;
    unsigned char value=0;
    assert(pread(md_memory_private_access(m)->memory,&value,1,(off_t)memory)==1 && value==19);
    assert(!munmap(memory,4096)); md_memory_release(m);
    puts("PASS private-memory capture: exact self mm, allocation-free admission and rollback at every FD exhaustion point");
}
static void memory_access_protected_child(void) {
    unsigned char *memory=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(memory!=MAP_FAILED); *memory=19;
    int parent[2], child[2]; assert(!pipe2(parent,O_CLOEXEC) && !pipe2(child,O_CLOEXEC));
    pid_t owner=getpid(), pid=fork(); assert(pid>=0);
    if (!pid) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==owner);
        close(parent[1]); close(child[0]);
        unsigned char event=1;
        /* EVENT_WAIT: the fixture parent controls capture/protection boundaries;
         * its alarm and PDEATHSIG terminate a failed rendezvous. */
        assert(read(parent[0],&event,1)==1);
        assert(!prctl(PR_SET_DUMPABLE,0));
        *memory=37; assert(write(child[1],&event,1)==1);
        assert(read(parent[0],&event,1)==1 && *memory==43);
        _exit(0);
    }
    close(parent[0]); close(child[1]);
    struct md_interception_memory *m=md_memory_new(); assert(m);
    assert(!md_memory_private_access_capture(m,pid));
    unsigned char event=1, value=0;
    assert(write(parent[1],&event,1)==1 && read(child[0],&event,1)==1);
    const struct md_private_memory_access *access=md_memory_private_access(m);
    assert(pread(access->memory,&value,1,(off_t)memory)==1 && value==37);
    value=43; assert(pwrite(access->memory,&value,1,(off_t)memory)==1 && *memory==19);
    assert(write(parent[1],&event,1)==1);
    int status;
    while (waitpid(pid,&status,0)<0) assert(errno==EINTR);
    assert(WIFEXITED(status) && !WEXITSTATUS(status));
    assert(pread(access->memory,&value,1,(off_t)memory)==0);
    md_memory_release(m); close(parent[1]); close(child[0]); assert(!munmap(memory,4096));
    puts("PASS private-memory capture: protected child retains exact mm, parent COW stays independent, exited mm cannot target a reused PID");
}
int main(void) {
    alarm(30);
    unsigned before=descriptors();
    execution_windows(); slots(); spans(); channels(); filter_payload(); owner_lifetime(); failures(); address_spaces(); payload_reuse(); mapping_policy(); native_memory_admission(); file_views(); file_view_failures();
    file_backing_lifetime(); image_backings(); memory_access_lifetime(); memory_access_capture(); memory_access_protected_child();
    assert(descriptors()==before);
    puts("PASS shared request buffer ownership");
    return 0;
}
