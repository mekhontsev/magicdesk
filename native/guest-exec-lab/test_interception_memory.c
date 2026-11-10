#define _GNU_SOURCE
#include "interception_memory.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

static int fail_malloc;
void *__real_malloc(size_t);
void *__wrap_malloc(size_t size) {
    if (fail_malloc) { errno=ENOMEM; return NULL; }
    return __real_malloc(size);
}
static void file_custody(void) {
    int fd=memfd_create("mapping-fork",MFD_CLOEXEC);
    assert(fd>=0 && !ftruncate(fd,4096));
    struct md_interception_memory *parent=md_memory_new();
    struct md_mapping_change change={0};
    assert(parent);
    assert(!md_memory_mapping_prepare(parent,&change,MD_MAPPING_MAP,0x10000,4096,0,MAP_PRIVATE,0));
    assert(!md_memory_mapping_backing(parent,&change,fd,0));
    md_memory_mapping_finish(parent,&change,0x10000);
    close(fd);
    fail_malloc=1;
    assert(!md_memory_fork(parent,0));
    fail_malloc=0;
    uint64_t offset=UINT64_MAX;
    assert(md_memory_file_view(parent,0x10000,4096,&fd,&offset)==1 && offset==0);
    struct md_interception_memory *child=md_memory_fork(parent,0);
    assert(child);
    md_memory_release(parent);
    assert(md_memory_file_view(child,0x10000,4096,&fd,&offset)==1 && offset==0);
    struct stat st;
    assert(!fstat(fd,&st) && st.st_size==4096);
    md_memory_release(child);
}
static void backing_reuse(void) {
    char temporary[]="mapping-reuse-XXXXXX";
    int fd=mkstemp(temporary);
    char denied[]="mapping-denied-XXXXXX";
    int other=mkstemp(denied);
    size_t page=(size_t)getpagesize();
    assert(fd>=0 && other>=0 && !ftruncate(fd,2*page) && !unlink(temporary));
    assert(!unlink(denied));
    char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",fd);
    int pin=open(path,O_PATH|O_CLOEXEC);
    assert(pin>=0);
    struct md_interception_memory *memory=md_memory_new();
    struct md_mapping_change first={0},next={0};
    assert(memory && !md_memory_mapping_prepare(memory,&first,MD_MAPPING_MAP,0x10000,page,0,MAP_PRIVATE,0));
    int vacant=fcntl(pin,F_DUPFD_CLOEXEC,0);
    assert(vacant>=0 && !close(vacant));
    fail_malloc=1;
    int failed=md_memory_mapping_pin(memory,&first,pin,0);
    fail_malloc=0;
    assert(failed==-ENOMEM && !first.backing);
    int available=fcntl(pin,F_DUPFD_CLOEXEC,0);
    assert(available==vacant && !close(available));
    assert(md_memory_mapping_pin(memory,&first,pin,0)==1 && first.backing);
    md_memory_mapping_finish(memory,&first,0x10000);
    int source=-1; uint64_t source_offset=UINT64_MAX;
    assert(md_memory_file_view(memory,0x10000,page,&source,&source_offset)==1 && !source_offset);
    assert(!fcntl(fd,F_SETFL,O_APPEND) && lseek(fd,17,SEEK_SET)==17);
    assert((fcntl(source,F_GETFL)&(O_ACCMODE|O_APPEND))==O_RDONLY);
    assert(fcntl(source,F_GETFD)&FD_CLOEXEC);
    assert(lseek(source,0,SEEK_CUR)==0 && lseek(fd,0,SEEK_CUR)==17);
    close(fd);
    assert(!md_memory_mapping_prepare(memory,&next,MD_MAPPING_MAP,0x20000,page,0,MAP_PRIVATE,0));
    assert(md_memory_mapping_pin(memory,&next,pin,1)==-EINVAL && !next.backing);
    int reused=md_memory_mapping_pin(memory,&next,pin,page);
    if (reused!=1) fprintf(stderr,"mapping reuse result=%d\n",reused);
    assert(reused==1 && next.backing);
    /* Native failure releases its provisional reference without removing the
     * first mapping's source or publishing the rejected mapping. */
    md_memory_mapping_finish(memory,&next,-EACCES);
    uint64_t offset;
    assert(!md_memory_file_view(memory,0x20000,page,&fd,&offset));
    assert(md_memory_file_view(memory,0x10000,page,&fd,&offset)==1 && !offset);
    assert(!md_memory_mapping_prepare(memory,&next,MD_MAPPING_MAP,0x20000,page,0,MAP_PRIVATE,0));
    assert(md_memory_mapping_pin(memory,&next,pin,page)==1);
    md_memory_mapping_finish(memory,&next,0x20000);
    assert(!md_memory_mapping_prepare(memory,&first,MD_MAPPING_UNMAP,0x10000,page,0,0,0));
    md_memory_mapping_finish(memory,&first,0);
    assert(md_memory_file_view(memory,0x20000,page,&fd,&offset)==1 && offset==page);
    assert(!md_memory_mapping_prepare(memory,&next,MD_MAPPING_UNMAP,0x20000,page,0,0,0));
    md_memory_mapping_finish(memory,&next,0);
    assert(!md_memory_mapping_prepare(memory,&next,MD_MAPPING_MAP,0x30000,page,0,MAP_PRIVATE,0));
    /* The final mapping releases its source. The independent pin still
     * authenticates the unlinked inode for a new capture. */
    assert(md_memory_mapping_pin(memory,&next,pin,0)==1);
    md_memory_mapping_finish(memory,&next,-ECANCELED);
    assert(!md_memory_mapping_prepare(memory,&next,MD_MAPPING_MAP,0x30000,page,0,MAP_PRIVATE,0));
    assert(!fchmod(other,0));
    assert(md_memory_mapping_pin(memory,&next,other,0)==-EACCES && !next.backing);
    /* A denied reopen does not prevent use of a previously opened source. */
    assert(!md_memory_mapping_backing(memory,&next,other,0) && next.backing);
    md_memory_mapping_finish(memory,&next,-ECANCELED);
    close(pin); close(other); md_memory_release(memory);
    puts("PASS mapping backing pin: unlinked identity, denied access fallback, failure, offsets and final retirement");
    puts("PASS mapping source ownership: independent read-only description and allocation-failure cleanup");
}

static void readonly_inputs(void) {
    size_t page=(size_t)getpagesize();
    struct md_interception_memory *memory=md_memory_new(); assert(memory);
    assert(!md_memory_readonly_prepare(memory,2,page));
    struct md_interception_buffers *pool=md_memory_readonly_buffers(memory);
    assert(md_buffers_peer_readonly(pool));
    assert(md_memory_readonly_prepare(memory,2,page)==-EALREADY);
    assert(!md_memory_readonly_mapped(memory,0x100000,md_buffers_size(pool)));
    assert(md_memory_readonly_mapped(memory,0x200000,md_buffers_size(pool))==-EALREADY);
    assert(md_memory_mapping_observe(memory,0x100000,page));
    assert(!md_memory_readonly_intersects(memory,0x100000-page,page));
    assert(md_memory_readonly_intersects(memory,0,page+0x100000));
    struct md_interception_memory *child=md_memory_fork(memory,0),*exec=md_memory_new_image(memory);
    assert(child && exec && md_memory_readonly_base(child)==0x100000);
    assert(!md_memory_readonly_base(exec) && !md_memory_readonly_buffers(exec));
    struct md_mapping_change change={0};
    assert(!md_memory_mapping_prepare(memory,&change,MD_MAPPING_UNMAP,0x100000,page,0,0,0));
    md_memory_mapping_finish(memory,&change,-EINVAL);
    assert(md_memory_readonly_base(memory)==0x100000);
    assert(!md_memory_mapping_prepare(memory,&change,MD_MAPPING_UNMAP,0x100000,page,0,0,0));
    md_memory_mapping_finish(memory,&change,0);
    assert(!md_memory_readonly_base(memory) && md_memory_readonly_base(child)==0x100000);
    assert(!md_memory_mapping_prepare(child,&change,MD_MAPPING_REMAP,0x100000,page,page,
        MREMAP_MAYMOVE|MREMAP_DONTUNMAP,0));
    md_memory_mapping_finish(child,&change,0x300000);
    assert(!md_memory_readonly_base(child));
    struct md_interception_buffer loan={0};
    assert(!md_buffers_take(pool,&loan));
    md_memory_release(memory); md_memory_release(child); md_memory_release(exec);
    struct md_buffer_span span; assert(!md_buffer_span(&loan,&span));
    assert(span.size==page && !md_buffer_release(&loan));
    puts("PASS immutable input mappings: fork/exec, replacement, failed unmap, DONTUNMAP and outstanding loan");
}
static void entry_snapshots(void) {
    size_t page=(size_t)getpagesize();
    struct md_interception_memory *memory=md_memory_new(); assert(memory);
    struct md_interception_buffers *pool=NULL,*ordinary=NULL;
    assert(!md_buffers_create_readonly(1,page,&pool));
    assert(!md_buffers_create(1,page,0,0,&ordinary));
    assert(md_memory_snapshot_publish(memory,1,ordinary,0x100000)==-EINVAL);
    assert(md_memory_snapshot_publish(memory,0,pool,0x100000)==-EINVAL);
    assert(md_memory_snapshot_publish(memory,1,pool,1)==-EINVAL);
    fail_malloc=1;
    assert(md_memory_snapshot_publish(memory,1,pool,0x100000)==-ENOMEM);
    fail_malloc=0;
    assert(!md_memory_snapshot_address(memory,1));
    for (unsigned i=1;i<=32;++i)
        assert(!md_memory_snapshot_publish(memory,i,pool,0x100000+i*page));
    assert(md_memory_snapshot_publish(memory,1,pool,0x200000)==-EALREADY);
    assert(md_memory_snapshot_publish(memory,33,pool,0x100000+page)==-EEXIST);
    assert(md_memory_snapshot_address(memory,32)==0x100000+32*page);
    assert(md_memory_has_policy(memory));
    assert(md_memory_mapping_observe(memory,0x100000+page,page));
    assert(!md_memory_snapshot_intersects(memory,0x100000,page));
    fail_malloc=1;
    assert(!md_memory_fork(memory,0));
    fail_malloc=0;
    struct md_interception_memory *child=md_memory_fork(memory,0),*exec=md_memory_new_image(memory);
    assert(child && exec && md_memory_snapshot_address(child,32)==0x100000+32*page);
    assert(!md_memory_snapshot_address(exec,32) && !md_memory_has_policy(exec));
    struct md_mapping_change change={0};
    assert(!md_memory_mapping_prepare(memory,&change,MD_MAPPING_UNMAP,0x100000+page,page,0,0,0));
    md_memory_mapping_finish(memory,&change,-EINVAL);
    assert(md_memory_snapshots_valid(memory) && md_memory_snapshot_address(memory,1));
    assert(!md_memory_mapping_prepare(memory,&change,MD_MAPPING_UNMAP,0x100000+page,page,0,0,0));
    md_memory_mapping_finish(memory,&change,0);
    assert(!md_memory_snapshots_valid(memory) && !md_memory_snapshot_address(memory,32));
    assert(md_memory_snapshot_publish(memory,33,pool,0x300000)==-ESTALE);
    assert(md_memory_snapshots_valid(child) && md_memory_snapshot_address(child,32));
    md_memory_release(memory); md_buffers_release(pool); md_buffers_release(ordinary);
    assert(md_memory_snapshot_address(child,1));
    md_memory_release(child); md_memory_release(exec);
    puts("PASS entry snapshots: retained read-only views, unbounded identities, fork/exec and dependent invalidation");
}
static void channel_blocks(void) {
    struct md_interception_memory *parent=md_memory_new(); assert(parent);
    assert(!md_memory_buffers_prepare(parent,3,4096,0,0));
    struct md_interception_buffers *initial=md_memory_buffers(parent),*extra=NULL;
    assert(!md_buffers_partition_channels(initial,2));
    assert(!md_memory_buffers_mapped(parent,0x100000,md_buffers_size(initial)));
    assert(!md_buffers_create(32,4096,0,0,&extra));
    assert(!md_memory_channels_publish(parent,extra,0x200000));
    assert(md_memory_channels_publish(parent,extra,0x200000)==-EEXIST);
    md_buffers_release(extra);
    struct md_interception_buffer slots[34]={0},pin={0},last={0};
    for (unsigned i=0;i<34;++i) {
        assert(!md_memory_channel_take(parent,&slots[i]));
        assert(md_memory_buffer_address(parent,&slots[i]));
    }
    assert(md_memory_channel_take(parent,&last)==-EAGAIN);
    struct md_interception_memory *child=md_memory_fork(parent,0),*image=md_memory_new_image(parent);
    assert(child && image && md_memory_channel_take(child,&last)==-EAGAIN);
    assert(!md_memory_buffer_address(image,&slots[33]));
    uintptr_t address=md_memory_buffer_address(child,&slots[33]);
    assert(address==md_memory_buffer_address(parent,&slots[33]));
    assert(!md_buffer_pin(&slots[33],&pin));
    assert(!md_buffer_release(&slots[33]));
    assert(md_memory_channel_take(child,&last)==-EAGAIN);
    assert(!md_buffer_release(&pin));
    assert(!md_memory_channel_take(child,&last));
    assert(md_memory_buffer_address(child,&last)==address);
    md_memory_release(parent); md_memory_release(child); md_memory_release(image);
    for (unsigned i=0;i<34;++i) assert(!md_buffer_release(&slots[i]));
    assert(!md_buffer_release(&last));
    puts("PASS channel blocks: expansion, fork custody, exec reset and worker pin retirement");
}
int main(void) {
    channel_blocks();
    entry_snapshots();
    readonly_inputs();
    file_custody();
    backing_reuse();
    struct md_interception_memory *parent = md_memory_new();
    assert(parent && !md_memory_stack_take(parent));
    assert(!md_memory_stack_add(parent, 0x10000));
    struct md_interception_memory *thread = md_memory_fork(parent, 1);
    assert(thread == parent && !md_memory_stack_take(thread));
    assert(!md_memory_stack_add(thread, 0x20000));
    struct md_memory_page *page=md_memory_page_acquire(parent);
    assert(page && !md_memory_page_address(page));
    md_memory_page_publish(page,0x30000);
    struct md_interception_memory *child = md_memory_fork(parent, 0);
    assert(child && child != parent);
    assert(md_memory_stack_take(child) == 0x10000 && md_memory_stack_take(child) == 0x20000);
    assert(!md_memory_stack_take(child) && !md_memory_stack_take(parent));
    struct md_memory_page *child_page=md_memory_page_acquire(child);
    assert(child_page && md_memory_page_address(child_page)==0x30000);
    md_memory_page_release(page);
    assert(md_memory_page_acquire(thread)==page && md_memory_page_address(page)==0x30000);
    struct md_interception_memory *image=md_memory_new_image(parent);
    struct md_memory_page *image_page=md_memory_page_acquire(image);
    assert(image && image_page && !md_memory_page_address(image_page) && !md_memory_stack_take(image));
    md_memory_page_release(image_page);
    md_memory_page_release(child_page); md_memory_page_release(page);
    md_memory_release(image);
    md_memory_stack_return(thread, 0x20000);
    for (unsigned i = 0; i < 10000; i++) {
        assert(md_memory_stack_take(parent) == 0x20000 && !md_memory_stack_take(thread));
        md_memory_stack_return(parent, 0x20000);
    }
    md_memory_release(child);
    md_memory_release(thread);
    md_memory_stack_return(parent, 0x10000);
    assert(md_memory_stack_take(parent) == 0x20000 && md_memory_stack_take(parent) == 0x10000);
    md_memory_release(parent);
    puts("PASS adapter stacks: shared VM, independent fork, concurrent leases and bounded reuse");
    return 0;
}
