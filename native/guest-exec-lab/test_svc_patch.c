#define _GNU_SOURCE
#include "svc_patch.h"
#include "svc_registry.h"
#include "mapping_reader.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/prctl.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

static struct md_svc_payload_state payload;
static int reads,writes,fail_write=-1,fail_read;
static void *publication_code;
static size_t publication_size;
static int publication_memory=-1;
static uint64_t revocation_code[2];
ssize_t __real_process_vm_readv(pid_t,const struct iovec *,unsigned long,const struct iovec *,unsigned long,unsigned long);
ssize_t __wrap_process_vm_readv(pid_t pid,const struct iovec *local,unsigned long ln,
        const struct iovec *remote,unsigned long rn,unsigned long flags) {
    if (publication_code && pid==getpid()) {
        ++reads;
        assert(ln==1 && rn==1 && !flags && remote->iov_len<=8);
        assert((uintptr_t)remote->iov_base>=(uintptr_t)publication_code
            && (uintptr_t)remote->iov_base+remote->iov_len<=(uintptr_t)publication_code+publication_size);
        memcpy(local->iov_base,remote->iov_base,remote->iov_len); return remote->iov_len;
    }
    if (pid==getpid()) return __real_process_vm_readv(pid,local,ln,remote,rn,flags);
    assert((pid==111 || pid==222) && ln==1 && rn==1 && !flags);
    ++reads;
    if (fail_read) { errno=ESRCH; return -1; }
    if (remote->iov_base==revocation_code || remote->iov_base==revocation_code+1) {
        assert(remote->iov_len<=8);
        memcpy(local->iov_base,remote->iov_base,remote->iov_len); return remote->iov_len;
    }
    assert(remote->iov_base==&payload && remote->iov_len==sizeof(payload));
    memcpy(local->iov_base,&payload,sizeof(payload)); return sizeof(payload);
}
long __wrap_ptrace(int request,pid_t pid,void *address,void *value) {
    if (publication_code && pid==getpid()) {
        assert(request==PTRACE_POKETEXT && (uintptr_t)address>=(uintptr_t)publication_code
            && (uintptr_t)address+8<=(uintptr_t)publication_code+publication_size);
        uint64_t word=(uintptr_t)value;
        assert(pwrite(publication_memory,&word,8,(off_t)(uintptr_t)address)==8);
        return 0;
    }
    assert(pid==111 || pid==222 || pid==INT_MAX);
    if (request==PTRACE_PEEKTEXT) { assert(fail_read); errno=ESRCH; return -1; }
    assert(request==PTRACE_POKETEXT);
    if (writes++==fail_write) { errno=ESRCH; return -1; }
    if (address==revocation_code || address==revocation_code+1) {
        uint64_t word=(uintptr_t)value; memcpy(address,&word,8); return 0;
    }
    assert((char *)address>=(char *)&payload && (char *)address+8<=(char *)(&payload+1));
    if (address==&payload.clients[0].tid) {
        struct md_entry_client zero={0};
        assert(!memcmp(&payload.clients[0].client,&zero,sizeof(zero)));
    }
    uint64_t word=(uintptr_t)value; memcpy(address,&word,8); return 0;
}
static int alive(pid_t pid,void *context) {
    assert(context==&payload); return pid==222;
}
static void client_retirement(void) {
    struct md_svc_registry r={.frames=(uintptr_t)&payload-MD_SVC_POOL_SIZE};
    assert(!md_svc_registry_collect_clients(&r,111,-1,alive,&payload) && !reads && !writes);
    /* Lose the stop before each opaque word, the final owner, and statistics. */
    for (int failure=0;failure<5;++failure) {
        payload=(struct md_svc_payload_state){.clients={
            {.tid=111,.client.opaque={11,12,13}}, {.tid=222,.client.opaque={21,22,23}}}};
        r.clients_dirty=1; writes=0; fail_write=failure;
        assert(md_svc_registry_collect_clients(&r,111,-1,alive,&payload)==-ESRCH && r.clients_dirty);
        fail_write=-1;
        assert(!md_svc_registry_collect_clients(&r,222,-1,alive,&payload) && !r.clients_dirty);
        assert(!payload.clients[0].tid && payload.clients[1].tid==222);
        assert(payload.clients[1].client.opaque[0]==21 && payload.clients[1].client.opaque[2]==23);
        int previous=reads+writes;
        assert(!md_svc_registry_collect_clients(&r,222,-1,alive,&payload) && previous==reads+writes);
    }
    r.clients_dirty=1; fail_read=1;
    assert(md_svc_registry_collect_clients(&r,111,-1,alive,&payload)==-ESRCH && r.clients_dirty);
    fail_read=0;
    assert(!md_svc_registry_collect_clients(&r,222,-1,alive,&payload));
    int fd=open("/proc/self/mem",O_RDWR|O_CLOEXEC); assert(fd>=0);
    payload.clients[0].tid=111; payload.clients[0].client.opaque[0]=123;
    r.clients_dirty=1; fail_read=1; fail_write=writes;
    assert(!md_svc_registry_collect_clients(&r,111,fd,alive,&payload) && !r.clients_dirty);
    assert(!payload.clients[0].tid && !payload.clients[0].client.opaque[0]);
    assert(payload.clients[1].tid==222 && payload.clients[1].client.opaque[0]==21);
    close(fd); fail_read=0; fail_write=-1;
    struct md_svc_registry *copy=md_svc_registry_copy(&r);
    assert(copy && copy->clients_dirty && !r.clients_dirty); md_svc_registry_free(copy);
    r.disabled=1; r.clients_dirty=1; fail_read=1; fail_write=writes;
    int previous=reads+writes;
    assert(!md_svc_registry_collect_clients(&r,111,-1,alive,&payload));
    assert(previous==reads+writes && r.clients_dirty);
    fail_read=0; fail_write=-1;
    puts("PASS SVC client retirement: lost stop, live peer, owner-last publication and idle fast path");
}

static void client_expansion(void) {
    enum { COUNT=40 };
    size_t page=(size_t)getpagesize();
    void *memory=mmap(NULL,COUNT*page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(memory!=MAP_FAILED);
    int fd=open("/proc/self/mem",O_RDWR|O_CLOEXEC); assert(fd>=0);
    payload=(struct md_svc_payload_state){0};
    struct md_svc_registry r={.frames=(uintptr_t)&payload-MD_SVC_POOL_SIZE};
    assert(md_svc_registry_client_space(&r,getpid(),fd,0)==-EAGAIN);
    for (unsigned i=0;i<COUNT;++i) {
        struct md_svc_client_block *block=(void *)((char *)memory+i*page);
        unsigned bucket=i%MD_SVC_CLIENT_BUCKETS;
        uintptr_t previous=payload.client_buckets[bucket];
        assert(!md_svc_registry_client_publish(&r,getpid(),fd,bucket,(uintptr_t)block));
        assert(block->next==previous && payload.client_buckets[bucket]==(uintptr_t)block);
        assert(md_svc_registry_client_space(&r,getpid(),fd,bucket)==(long)block);
        for (unsigned j=0;j<MD_SVC_CLIENT_BLOCK;++j)
            block->slots[j]=(struct md_svc_client_slot){.tid=j ? 111 : 222,.client.opaque={1,2,3}};
        assert(md_svc_registry_client_space(&r,getpid(),fd,bucket)==-EAGAIN);
    }
    struct md_svc_registry *copy=md_svc_registry_copy(&r);
    assert(copy && copy->client_count==COUNT && copy->client_blocks!=r.client_blocks);
    assert(!memcmp(copy->client_blocks,r.client_blocks,COUNT*sizeof(*r.client_blocks)));
    r.clients_dirty=1;
    assert(!md_svc_registry_collect_clients(&r,getpid(),fd,alive,&payload));
    assert(payload.retired_clients==COUNT*(MD_SVC_CLIENT_BLOCK-1));
    for (unsigned i=0;i<COUNT;++i) {
        struct md_svc_client_block *block=(void *)((char *)memory+i*page);
        assert(block->slots[0].tid==222 && block->slots[0].client.opaque[2]==3);
        assert(!block->slots[1].tid && !block->slots[1].client.opaque[2]);
    }
    md_svc_registry_free(copy); free(r.client_blocks); close(fd);
    assert(!munmap(memory,COUNT*page));
    puts("PASS SVC client expansion: bucket publication, growth, fork inventory and common retirement");
}
static void entry_revocation(void) {
    for (int failure=0;failure<4;++failure) {
        payload.admitted=1; payload.sole_owner=111;
        revocation_code[0]=revocation_code[1]=UINT64_C(0xd503201f14000000);
        struct md_svc_site sites[2];
        struct md_svc_registry r={.frames=(uintptr_t)&payload-MD_SVC_POOL_SIZE,.count=2,.sites=sites};
        for (unsigned i=0;i<2;++i) r.sites[i]=(struct md_svc_site){
            .direct={.svc=(uintptr_t)(revocation_code+i),.gate=1},
            .original=0xd4000001,.replacement=0x14000000,.state=MD_SVC_PUBLISHED};
        writes=0; fail_write=failure;
        assert(md_svc_registry_revoke(&r,111)==-ESRCH && r.disabled && r.revoke_pending);
        uint64_t slots;
        assert(md_svc_registry_occupied(&r,222,&slots)==-EINVAL);
        assert(payload.admitted==(failure==0));
        assert(!md_svc_registry_direct(&r,222,-1,0x10000,SYS_getpid));
        struct md_svc_registry *copy=md_svc_registry_copy(&r);
        assert(copy && copy->disabled && copy->revoke_pending); md_svc_registry_free(copy);
        fail_write=-1;
        assert(!md_svc_registry_revoke(&r,222) && r.disabled && !r.revoke_pending);
        assert(!payload.admitted && !payload.sole_owner && !r.identity_known);
        for (unsigned i=0;i<2;++i) {
            assert(r.sites[i].state==MD_SVC_RETIRED);
            assert(revocation_code[i]==UINT64_C(0xd503201fd4000001));
        }
        int before=reads+writes;
        assert(!md_svc_registry_revoke(&r,INT_MAX) && before==reads+writes);
    }
    puts("PASS SVC revocation: lost stop, partial retirement, peer retry, disabled discovery and revoked sole identity");
    struct md_svc_site sites[2];
    struct md_svc_registry r={.frames=(uintptr_t)&payload-MD_SVC_POOL_SIZE,.sites=sites,.count=2};
    for (unsigned i=0;i<2;++i) {
        revocation_code[i]=UINT64_C(0xd503201f14000000);
        sites[i]=(struct md_svc_site){.direct={.svc=(uintptr_t)(revocation_code+i),.gate=1},
            .original=0xd4000001,.replacement=0x14000000,.state=MD_SVC_PUBLISHED};
    }
    payload.admitted=1;
    assert(!md_svc_registry_pause(&r,111,MD_SVC_PAUSE_FILTER) && r.pause_reasons && !payload.admitted);
    assert(sites[0].state==MD_SVC_PAUSED && sites[1].state==MD_SVC_PAUSED);
    int accesses=reads+writes;
    assert(!md_svc_registry_intersects(NULL,0,SIZE_MAX));
    assert(!md_svc_registry_intersects(&r,sites[1].direct.svc,0));
    assert(!md_svc_registry_intersects(&r,sites[1].direct.svc-4,4));
    assert(md_svc_registry_intersects(&r,sites[1].direct.svc,1));
    assert(md_svc_registry_intersects(&r,sites[1].direct.svc,SIZE_MAX));
    assert(accesses==reads+writes);
    assert(!md_svc_registry_unpatch(&r,111,sites[1].direct.svc,4) && sites[1].state==MD_SVC_RETIRED);
    assert(!md_svc_registry_intersects(&r,sites[1].direct.svc,4));
    assert(md_svc_registry_rearm(&r,111,MD_SVC_PAUSE_FILTER)==-EBUSY);
    r.entry_fence_drained=1; fail_write=writes;
    assert(md_svc_registry_rearm(&r,111,MD_SVC_PAUSE_FILTER)==-ESRCH && r.disabled && r.pause_reasons);
    fail_write=-1;
    assert(!md_svc_registry_rearm(&r,222,MD_SVC_PAUSE_FILTER) && payload.admitted && !r.disabled
        && !r.pause_reasons && !r.entry_fence_drained);
    assert(sites[0].state==MD_SVC_PUBLISHED && sites[1].state==MD_SVC_RETIRED);
    assert(revocation_code[0]==UINT64_C(0xd503201fd4000001));
    assert(!md_svc_registry_resume(&r,222,sites[0].direct.svc));
    assert(revocation_code[0]==UINT64_C(0xd503201f14000000));
    assert(!md_svc_registry_revoke(&r,111));
    assert(!md_svc_registry_pause(&r,111,MD_SVC_PAUSE_FILTER) && !r.pause_reasons);
    r.entry_fence_drained=1;
    assert(md_svc_registry_rearm(&r,111,MD_SVC_PAUSE_FILTER)==-EBUSY);
    puts("PASS SVC filter rearm: drained fence, retry, warm-site resumption and permanent mapping retirement");
    for (unsigned order=0;order<2;++order) for (unsigned permanent=0;permanent<2;++permanent) {
        r.disabled=r.revoke_pending=r.pause_reasons=0;
        payload.admitted=1;
        sites[0].state=MD_SVC_PUBLISHED;
        revocation_code[0]=UINT64_C(0xd503201f14000000);
        assert(!md_svc_registry_pause(&r,111,MD_SVC_PAUSE_DEBUGGER));
        assert(!md_svc_registry_pause(&r,111,MD_SVC_PAUSE_FILTER));
        assert(r.pause_reasons==(MD_SVC_PAUSE_FILTER|MD_SVC_PAUSE_DEBUGGER));
        r.entry_fence_drained=1;
        enum md_svc_pause first=order ? MD_SVC_PAUSE_DEBUGGER : MD_SVC_PAUSE_FILTER;
        enum md_svc_pause second=order ? MD_SVC_PAUSE_FILTER : MD_SVC_PAUSE_DEBUGGER;
        assert(!md_svc_registry_rearm(&r,111,first));
        assert(r.disabled && !payload.admitted && r.pause_reasons==(unsigned)second);
        assert(sites[0].state==MD_SVC_PAUSED && sites[1].state==MD_SVC_RETIRED);
        if (permanent) {
            assert(!md_svc_registry_revoke(&r,111));
            assert(r.disabled && !r.pause_reasons && sites[0].state==MD_SVC_RETIRED);
            assert(md_svc_registry_rearm(&r,111,second)==-EBUSY);
        } else {
            assert(!md_svc_registry_rearm(&r,111,second));
            assert(!r.disabled && payload.admitted && !r.pause_reasons);
            assert(sites[0].state==MD_SVC_PUBLISHED && sites[1].state==MD_SVC_RETIRED);
        }
    }
    puts("PASS SVC pause ownership: debugger/filter composition, both release orders and permanent revocation");
}

static void code_staging(void) {
    size_t page=(size_t)getpagesize();
    void *code=mmap(NULL,page,PROT_READ|PROT_EXEC,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(code!=MAP_FAILED);
    uint32_t words[32];
    for (unsigned i=0;i<32;++i) words[i]=0xd503201f;
    int previous=writes;
    int memory=open("/proc/self/mem",O_RDWR|O_CLOEXEC); assert(memory>=0);
    assert(!md_svc_registry_stage(getpid(),memory,(uintptr_t)code,words,sizeof(words)));
    assert(!memcmp(code,words,sizeof(words)) && writes==previous);
    assert(fcntl(memory,F_GETFD)==FD_CLOEXEC);
    assert(md_svc_registry_stage(getpid(),memory,UINTPTR_MAX-7,words,sizeof(words))==-EFAULT);
    assert(md_svc_registry_stage(getpid(),memory,0,words,SIZE_MAX)==-EFAULT);
    assert(!md_svc_registry_stage(getpid(),memory,0,NULL,0));
    assert(!close(memory));
    assert(!munmap(code,page));
    /* A missing proc target uses the original writer, not fictitious success. */
    fail_write=writes;
    assert(md_svc_registry_stage(INT_MAX,-1,(uintptr_t)&payload,words,sizeof(words))==-ESRCH);
    fail_write=-1;
    puts("PASS SVC unpublished code staging: RX block, overflow and ptrace fallback");
}

static void sole_identity(void) {
    struct md_svc_registry r={.frames=(uintptr_t)&payload-MD_SVC_POOL_SIZE};
    assert(md_svc_registry_identity(&r,111,-1,222)==-EINVAL);
    assert(!md_svc_registry_identity(&r,111,-1,111));
    assert(payload.sole_owner==111 && r.identity_known && r.sole_owner==111);
    int previous=writes;
    assert(!md_svc_registry_identity(&r,111,-1,111) && previous==writes);
    struct md_svc_registry *copy=md_svc_registry_copy(&r);
    assert(copy && !copy->identity_known);
    assert(!md_svc_registry_identity(copy,222,-1,222) && payload.sole_owner==222);
    md_svc_registry_free(copy);
    fail_write=writes;
    assert(md_svc_registry_identity(&r,111,-1,0)==-ESRCH && r.sole_owner==111);
    fail_write=-1;
    assert(!md_svc_registry_identity(&r,111,-1,0) && !payload.sole_owner);
    int fd=open("/proc/self/mem",O_RDWR|O_CLOEXEC); assert(fd>=0);
    previous=writes;
    assert(!md_svc_registry_identity(&r,111,fd,111) && payload.sole_owner==111 && writes==previous);
    assert(!close(fd));
    puts("PASS SVC sole identity: exact owner, fork invalidation, shared revocation and failed publication retry");
}
static void publication_admission(void) {
    size_t size=2*MD_SVC_CODE_BYTES;
    publication_code=mmap(NULL,size,PROT_READ|PROT_EXEC,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(publication_code!=MAP_FAILED);
    publication_size=size;
    publication_memory=open("/proc/self/mem",O_RDWR|O_CLOEXEC); assert(publication_memory>=0);
    uintptr_t source=(uintptr_t)publication_code;
    uint32_t original[]={0xd4000001,0xd503201f};
    assert(pwrite(publication_memory,original,8,(off_t)source)==8);
    struct md_svc_registry r={.frames=MD_SVC_POOL_BASE,.dispatch=source+8,
        .checkpoint=(uintptr_t)md_svc_signal_checkpoint,.pool_count=1,
        .pools=(struct md_svc_code_pool[]){{.address=source+MD_SVC_CODE_BYTES}}};
    r.pool_count=0;
    assert(md_svc_registry_direct(&r,getpid(),publication_memory,source,SYS_openat)==-ENOSPC);
    assert(!r.count && !memcmp(publication_code,original,8));
    r.pool_count=1;
    assert(md_svc_registry_direct(&r,getpid(),publication_memory,source,SYS_openat)==1);
    assert(r.count==1 && r.sites[0].state==MD_SVC_PUBLISHED);
    size_t used=r.pools[0].used;
    for (unsigned i=0;i<3;++i) {
        assert(!md_svc_registry_suspend(&r,getpid(),source));
        assert(!memcmp(publication_code,original,8));
        assert(!md_svc_registry_resume(&r,getpid(),source));
        assert(r.count==1 && r.pools[0].used==used);
        assert(*(uint32_t *)publication_code==r.sites[0].replacement);
    }
    /* Both an armed alias and an interrupted original can extend the chain,
     * but restoring it alone never implicitly admits another syscall. */
    const long numbers[]={SYS_close,SYS_fstat};
    for (unsigned i=0;i<2;++i) {
        if (i) assert(!md_svc_registry_suspend(&r,getpid(),source));
        assert(!md_svc_registry_direct(&r,getpid(),publication_memory,source,numbers[i]));
        assert(r.count==2+i && r.pools[0].used>used);
        assert(*(uint32_t *)publication_code==r.sites[1+i].replacement);
        assert(r.sites[1+i].original==original[0]);
        used=r.pools[0].used;
    }
    assert(!md_svc_registry_direct(&r,getpid(),publication_memory,source,SYS_close));
    assert(r.count==3 && r.pools[0].used==used);
    assert(pwrite(publication_memory,&original[1],4,(off_t)source)==4);
    assert(!md_svc_registry_direct(&r,getpid(),publication_memory,source,SYS_newfstatat));
    assert(!md_svc_registry_resume(&r,getpid(),source));
    assert(r.count==3 && *(uint32_t *)publication_code==original[1]);
    free(r.sites);
    assert(!close(publication_memory) && !munmap(publication_code,size));
    publication_code=NULL; publication_memory=-1;
    puts("PASS SVC admission: armed/suspended extension, restore-only rearm and replaced-code rejection");
}
static void registry_growth(void) {
    enum { SITES=256 };
    size_t size=(MD_SVC_CODE_POOLS+1)*MD_SVC_CODE_BYTES;
    publication_code=mmap(NULL,size,PROT_READ|PROT_EXEC,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(publication_code!=MAP_FAILED); publication_size=size;
    publication_memory=open("/proc/self/mem",O_RDWR|O_CLOEXEC); assert(publication_memory>=0);
    uintptr_t source=(uintptr_t)publication_code;
    struct md_svc_registry r={.frames=MD_SVC_POOL_BASE,.dispatch=source+8,
        .checkpoint=(uintptr_t)md_svc_signal_checkpoint,
        .pool_count=MD_SVC_CODE_POOLS,
        .pools=(struct md_svc_code_pool[MD_SVC_CODE_POOLS]){0}};
    for (unsigned i=0;i<r.pool_count;++i)
        r.pools[i].address=source+(i+1)*MD_SVC_CODE_BYTES;
    uint32_t original[]={0xd4000001,0xd503201f};
    for (unsigned i=0;i<SITES;++i) {
        uintptr_t svc=source+i*8;
        assert(pwrite(publication_memory,original,sizeof(original),(off_t)svc)==sizeof(original));
        assert(md_svc_registry_direct(&r,getpid(),publication_memory,svc,SYS_getpid)==1);
        assert(r.count==i+1 && r.sites[i].state==MD_SVC_PUBLISHED);
        assert(md_svc_registry_call_origin(&r,r.sites[i].kernel_alias+4)==svc+4);
        /* Fork owns an independent array even immediately before growth. */
        if (r.count==r.capacity) {
            struct md_svc_registry *copy=md_svc_registry_copy(&r);
            assert(copy && copy->sites!=r.sites && copy->count==r.count);
            assert(!memcmp(copy->sites,r.sites,r.count*sizeof(*r.sites)));
            copy->sites[0].state=MD_SVC_RETIRED;
            assert(r.sites[0].state==MD_SVC_PUBLISHED);
            md_svc_registry_free(copy);
        }
    }
    /* Extend an existing source exactly at a growth boundary: the prior
     * chain head must survive realloc without a dangling site pointer. */
    assert(r.capacity==r.count);
    assert(!md_svc_registry_direct(&r,getpid(),publication_memory,source,SYS_getuid));
    assert(r.count==SITES+1 && r.sites[SITES].direct.svc==source);
    assert(!md_svc_registry_suspend(&r,getpid(),source));
    assert(!memcmp(publication_code,original,sizeof(original)));
    assert(!md_svc_registry_resume(&r,getpid(),source));
    assert(*(uint32_t *)publication_code==r.sites[SITES].replacement);
    assert(!md_svc_registry_unpatch(&r,getpid(),source,SITES*8));
    for (unsigned i=0;i<SITES;++i)
        assert(!memcmp((char *)publication_code+i*8,original,sizeof(original)));
    free(r.sites);
    assert(!close(publication_memory) && !munmap(publication_code,size));
    publication_code=NULL; publication_memory=-1;
    puts("PASS SVC growing registry: 257 sites, fork ownership, chain extension and retirement");
}
static void deferred_admission(void) {
    size_t size=2*MD_SVC_CODE_BYTES;
    publication_code=mmap(NULL,size,PROT_READ|PROT_WRITE|PROT_EXEC,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(publication_code!=MAP_FAILED); publication_size=size;
    publication_memory=open("/proc/self/mem",O_RDWR|O_CLOEXEC); assert(publication_memory>=0);
    uintptr_t source=(uintptr_t)publication_code;
    uint32_t original[]={0xd4000001,0xd503201f};
    memcpy(publication_code,original,sizeof(original));
    struct md_svc_registry r={.frames=MD_SVC_POOL_BASE,.dispatch=source+8,
        .checkpoint=(uintptr_t)md_svc_signal_checkpoint,.pool_count=1,
        .pools=(struct md_svc_code_pool[]){{.address=source+MD_SVC_CODE_BYTES}}};
    assert(!md_svc_registry_direct(&r,getpid(),publication_memory,source,SYS_getpid));
    assert(r.count==1 && !r.sites[0].direct.gate && !r.pools[0].used);
    assert(md_svc_registry_deferred(&r,source,4) && !md_svc_registry_intersects(&r,source,4));
    int before=reads;
    for (unsigned i=0;i<100;++i)
        assert(!md_svc_registry_direct(&r,getpid(),publication_memory,source,SYS_getpid));
    assert(reads==before && r.count==1);
    md_svc_registry_mapping_changed(&r,source+4,4);
    assert(md_svc_registry_deferred(&r,source,4));
    assert(!mprotect(publication_code,size,PROT_READ|PROT_EXEC));
    md_svc_registry_mapping_changed(&r,source,4);
    assert(!md_svc_registry_deferred(&r,source,4));
    /* Missing gate space does not consume the invalidated slot. */
    r.pool_count=0;
    assert(md_svc_registry_direct(&r,getpid(),publication_memory,source,SYS_getpid)==-ENOSPC);
    assert(!r.sites[0].direct.svc);
    r.pool_count=1;
    assert(md_svc_registry_direct(&r,getpid(),publication_memory,source,SYS_getpid)==1);
    assert(r.count==1 && r.sites[0].state==MD_SVC_PUBLISHED);
    uintptr_t alias=r.sites[0].kernel_alias;
    assert(!md_svc_registry_unpatch(&r,getpid(),source,4));
    md_svc_registry_mapping_changed(&r,source,4);
    assert(r.sites[0].direct.gate && r.sites[0].direct.svc==source);
    assert(!md_svc_registry_direct(&r,getpid(),publication_memory,source,SYS_getpid));
    assert(md_svc_registry_call_origin(&r,alias+4)==source+4);
    free(r.sites);
    assert(!close(publication_memory) && !munmap(publication_code,size));
    publication_code=NULL; publication_memory=-1;
    puts("PASS deferred SVC admission: no hot retry, mapping invalidation, slot reuse and retained gate identity");
}
static void frame_layout(void) {
    struct md_svc_registry *r=md_svc_registry_new(MD_SVC_POOL_BASE,0x4000,0x5000);
    assert(r);
    for (unsigned i=0;i<40;++i) assert(!md_svc_registry_pool(r,0x100000+(uintptr_t)i*0x20000000));
    struct md_svc_registry *copy=md_svc_registry_copy(r);
    assert(copy && copy->pool_count==40 && copy->pools!=r->pools);
    assert(!memcmp(copy->pools,r->pools,40*sizeof(*r->pools)));
    assert(!md_svc_registry_pool(copy,0x200000));
    assert(r->pool_count==40 && copy->pool_count==41);
    uintptr_t candidate;
    assert(!md_svc_registry_pool_candidate(NULL,getpid(),(uintptr_t)frame_layout,0,&candidate));
    assert(!(candidate&(MD_SVC_CODE_BYTES-1)));
    uint32_t branch;
    assert(!md_svc_branch((uintptr_t)frame_layout,candidate,&branch));
    assert(!md_svc_branch(candidate+MD_SVC_CODE_BYTES-4,(uintptr_t)frame_layout,&branch));
    md_svc_registry_free(copy); md_svc_registry_free(r);
    puts("PASS SVC pools: growing inventory, independent fork and reachable gap selection");
    const size_t pages[]={4096,16384,65536};
    for (unsigned i=0;i<sizeof(pages)/sizeof(*pages);++i) {
        size_t page=pages[i];
        assert(!(MD_SVC_POOL_BASE%page) && !(MD_SVC_SLOT_SIZE%page));
        assert(!(MD_SVC_GUARD_OFFSET%page) && !(MD_SVC_GUARD_SIZE%page));
        assert(!(MD_SVC_POOL_SIZE%page) && !(MD_SVC_PAYLOAD_SIZE%page));
        assert(!(MD_SVC_CODE_BYTES%page));
        assert(MD_SVC_SAVED_SP+8<=MD_SVC_GUARD_OFFSET);
        assert(MD_SVC_SLOT_SIZE-MD_SVC_GUARD_OFFSET-MD_SVC_GUARD_SIZE>=32768);
        for (unsigned slot=0;slot<MD_SVC_SLOT_COUNT;++slot) {
            uintptr_t start=MD_SVC_POOL_BASE+slot*MD_SVC_SLOT_SIZE;
            assert(((start+MD_SVC_SLOT_SIZE-1)&~(uintptr_t)(MD_SVC_SLOT_SIZE-1))==start);
            assert(!(start%page));
        }
    }
    assert(sizeof(struct md_svc_payload_state)<=MD_SVC_PAYLOAD_SIZE);
    puts("PASS SVC frame layout: 4/16/64 KiB alignment, guards, stacks and recovery slots");
}
static int no_memory(void *opaque,uintptr_t at,void *data,size_t size,int write) {
    (void)opaque; (void)at; (void)data; (void)size; (void)write;
    assert(!"guard recovery must not access a private frame"); return -EIO;
}
static void guard_recovery(void) {
    const uint64_t numbers[]={0,56,172,SYS_mmap,4095,4096,UINT64_MAX};
    const uint64_t callers[]={0,56,172,4095,4096,UINT64_C(1)<<32,UINT64_MAX};
    unsigned char code[2048]; uint32_t branch;
    for (unsigned i=0;i<sizeof(numbers)/sizeof(*numbers);++i) {
        struct md_svc_direct_context c={.svc=0x100000,.gate=0x200000,.frames=MD_SVC_POOL_BASE,.number=numbers[i],
            .checkpoint_number=SYS_gettid,.checkpoint=UINT64_C(0x7200000000)};
        struct md_svc_direct_fallback exits={.mismatch=0x300000,.declined=0x300000,.busy=0x300000};
        assert(!md_svc_direct_signal_plan(&c,0x400000,exits,code,sizeof(code),&branch));
        assert(md_svc_direct_checkpoint(&c)==c.checkpoint);
        uint32_t *tail=(void *)(code+md_svc_direct_size());
        uintptr_t checkpoint;
        memcpy(&checkpoint,tail+8,sizeof(checkpoint));
        assert(checkpoint==c.checkpoint && tail[4]==(UINT32_C(0xd2800008)|(SYS_gettid<<5))
            && tail[5]==UINT32_C(0x5800007e) && tail[6]==UINT32_C(0xd61f03c0));
        for (unsigned j=0;j<sizeof(callers)/sizeof(*callers);++j) for (unsigned k=0;k<5;++k) {
            struct user_pt_regs r={.pc=c.gate+4*k,.sp=0x600000,.pstate=0xa0001000};
            for (unsigned g=0;g<31;++g) r.regs[g]=0xcafe0000+g;
            r.regs[8]=callers[j];
            struct user_pt_regs expected=r; expected.pc=c.svc;
            if (c.number<=4095 && k>=1 && k<=3) r.regs[8]-=c.number;
            assert(md_svc_direct_recover(&c,&r,no_memory,NULL)==1);
            assert(!memcmp(&r,&expected,sizeof(r)));
        }
        if (c.number==SYS_mmap) for (unsigned pc=32;pc<=40;pc+=4) {
            struct user_pt_regs r={.pc=c.gate+pc,.sp=0x600000,.pstate=0xa0001000};
            for (unsigned g=0;g<31;++g) r.regs[g]=0xcafe0000+g;
            r.regs[8]=c.number;
            struct user_pt_regs expected=r; expected.pc=c.svc;
            r.regs[8]=0;
            assert(md_svc_direct_recover(&c,&r,no_memory,NULL)==1);
            assert(!memcmp(&r,&expected,sizeof(r)));
        }
    }
    puts("PASS SVC guard recovery: full-width numbers, reversible scratch and unchanged PSTATE");
}

static int protection_at(uintptr_t address) {
    struct md_mapping_reader reader={.fd=open("/proc/self/maps",O_RDONLY|O_CLOEXEC)};
    assert(reader.fd>=0);
    struct md_mapping_record record;
    int result, protection=-1;
    while ((result=md_mapping_next(&reader,&record))>0) {
        if (address>=record.first && address<record.end) { protection=record.protection; break; }
    }
    assert(result>=0 && protection>=0);
    assert(!close(reader.fd));
    return protection;
}
static long payload_identity(void) { return 123; }
static enum md_entry_outcome payload_call(struct md_entry_client *client,struct md_entry_call *call) {
    (void)client;
    assert(call->instruction_pointer==0x1234);
    call->result=321;
    return call->args[0] ? MD_ENTRY_COMPLETED : MD_ENTRY_DECLINED;
}
static int payload_memory(struct md_entry_client *client,long tid,long number,const uint64_t *args,int64_t *result,uintptr_t pc) {
    (void)client;
    assert(tid==123 && number==1 && args[0]==0 && pc==0x1234);
    *result=456;
    return 1;
}
static unsigned action_effects,action_kernels,action_steps,action_cancels;
static void action_cancel(void) { ++action_cancels; }
static void action_effect(void *context) { assert(context==&action_effects); ++action_effects; }
static long action_kernel(long number,const uint64_t *args) {
    assert(number==0 && args[0]==42); ++action_kernels; return -EACCES;
}
static enum md_entry_outcome action_complete(struct md_entry_client *client,struct md_entry_call *call) {
    (void)client;
    assert(action_effects==1 && action_kernels==1 && call->result==-EACCES);
    ++action_steps;
    return MD_ENTRY_COMPLETED;
}
static enum md_entry_outcome action_next(struct md_entry_client *client,struct md_entry_call *call) {
    (void)client;
    assert(action_effects==1 && !action_kernels);
    ++action_steps; call->kernel=action_kernel; call->next=action_complete;
    return MD_ENTRY_KERNEL_CALL;
}
static enum md_entry_outcome action_begin(struct md_entry_client *client,struct md_entry_call *call) {
    (void)client;
    call->effect=action_effect; call->effect_context=&action_effects; call->next=action_next;
    return MD_ENTRY_NATIVE_EFFECT;
}
static enum md_entry_outcome action_terminal(struct md_entry_client *client,struct md_entry_call *call) {
    (void)client;
    call->next=NULL; call->kernel=action_kernel;
    return MD_ENTRY_KERNEL_CALL;
}
static enum md_entry_outcome action_cancelled(struct md_entry_client *client,struct md_entry_call *call) {
    (void)client; (void)call;
    assert(action_cancels==1); ++action_steps;
    return MD_ENTRY_COMPLETED;
}
static void payload_actions(uint64_t *registers) {
    unsigned char offers[]={MD_ENTRY_ORDINARY};
    struct md_entry_api api={.version=MD_ENTRY_VERSION,.size=sizeof(api),
        .try_syscall=action_begin,.offers=offers,.syscall_count=1,.thread_identity=payload_identity};
    for (unsigned resident=0;resident<2;++resident) {
        action_effects=action_kernels=action_steps=0;
        struct md_entry_client client={0};
        struct md_entry_call call={.number=0,.args={42},.instruction_pointer=0x1234};
        struct md_svc_payload_state state={.api=&api,.admitted=1};
        registers[8]=0; registers[0]=42;
        if (resident) assert(md_svc_payload_dispatch(registers,&state,0x1234)==1
            && (int64_t)registers[0]==-EACCES);
        else assert(md_entry_invoke(&api,&client,&call)==MD_ENTRY_COMPLETED && call.result==-EACCES);
        assert(action_effects==1 && action_kernels==1 && action_steps==2);
    }
    api.try_syscall=action_terminal; action_kernels=0;
    struct md_entry_client client={0};
    struct md_entry_call call={.number=0,.args={42}};
    assert(md_entry_invoke(&api,&client,&call)==MD_ENTRY_COMPLETED && action_kernels==1);
    struct md_svc_payload_state state={.api=&api,.admitted=1};
    registers[8]=0; registers[0]=42;
    assert(!md_svc_payload_dispatch(registers,&state,0x1234) && registers[0]==42 && action_kernels==1);
    /* A resident terminal fallback retains the original instruction; ordinary
     * clients execute their explicit continuation. Neither executes it twice. */
    action_effects=action_kernels=action_steps=action_cancels=0;
    call=(struct md_entry_call){.effect=action_effect,.effect_context=&action_effects};
    assert(md_entry_advance(&client,&call,MD_ENTRY_NATIVE_EFFECT)==MD_ENTRY_UNKNOWN && !action_effects);
    call.next=action_complete; call.effect=NULL;
    assert(md_entry_advance(&client,&call,MD_ENTRY_NATIVE_EFFECT)==MD_ENTRY_UNKNOWN && !action_steps);
    assert(md_entry_advance(&client,&call,MD_ENTRY_KERNEL_CALL)==MD_ENTRY_UNKNOWN && !action_kernels);
    assert(md_entry_advance(&client,&call,MD_ENTRY_CHECK_CANCEL)==MD_ENTRY_UNKNOWN && !action_cancels);
    call.cancel=action_cancel; call.next=action_cancelled;
    assert(md_entry_advance(&client,&call,MD_ENTRY_CHECK_CANCEL)==MD_ENTRY_COMPLETED);
    assert(action_cancels==1 && action_steps==1);
    puts("PASS shared entry actions: exact effects, continuations, terminal fallback and invalid-action rejection");
}
static void payload_statistics(uint64_t *registers) {
    payload_actions(registers);
    unsigned char offers[]={MD_ENTRY_ORDINARY,MD_ENTRY_ORDINARY|MD_ENTRY_NATIVE_MEMORY};
    const struct md_entry_api api={.version=MD_ENTRY_VERSION,.size=sizeof(api),
        .try_syscall=payload_call,.offers=offers,.syscall_count=2,
        .thread_identity=payload_identity,.native_memory=payload_memory};
    for (unsigned enabled=0;enabled<2;++enabled) {
        struct md_svc_payload_state state={.api=&api,.admitted=1,.statistics=enabled};
        registers[8]=0; registers[0]=1;
        assert(md_svc_payload_dispatch(registers,&state,0x1234)==1 && registers[0]==321);
        registers[0]=0;
        assert(!md_svc_payload_dispatch(registers,&state,0x1234));
        registers[8]=1;
        assert(md_svc_payload_dispatch(registers,&state,0x1234)==1 && registers[0]==456);
        assert(state.completed==2*enabled && state.declined==enabled && !state.unknown);
        state.admitted=0;
        assert(!md_svc_payload_dispatch(registers,&state,0x1234));
        assert(state.completed==2*enabled && state.declined==enabled);
    }
    puts("PASS SVC optional statistics: completed, native, declined and revoked entry");
}
static void lazy_stack_guards(void) {
    size_t size=2*MD_SVC_SLOT_SIZE;
    unsigned char *frames=mmap(NULL,size,PROT_READ|PROT_WRITE,
        MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(frames!=MAP_FAILED);
    struct md_svc_payload_state state={0};
    uint64_t *registers=(void *)(frames+MD_SVC_FRAME_OFFSET);
    int64_t *ready=(void *)(frames+MD_SVC_GUARD_STATE);
    assert(!md_svc_payload_dispatch(registers,&state,0x1234) && !*ready);
    assert(protection_at((uintptr_t)frames+MD_SVC_GUARD_OFFSET)==(PROT_READ|PROT_WRITE));
    state.admitted=1;
    assert(!md_svc_payload_dispatch(registers,&state,0x1234) && *ready==1);
    uintptr_t base=(uintptr_t)frames;
    struct md_entry_client scoped={0};
    *(struct md_entry_client **)(frames+MD_SVC_CLIENT_OFFSET)=&scoped;
    assert(md_svc_frame_client(base,base+MD_SVC_SLOT_SIZE-32)==&scoped);
    assert(!md_svc_frame_client(base,base+MD_SVC_FRAME_OFFSET));
    assert(!md_svc_frame_client(base,base+MD_SVC_GUARD_OFFSET));
    assert(!md_svc_frame_client(base,base-16));
    assert(!md_svc_frame_client(base,base+MD_SVC_POOL_SIZE));
    assert(!md_svc_payload_dispatch(registers,&state,0x1234));
    assert(!md_svc_frame_client(base,base+MD_SVC_SLOT_SIZE-32));
    assert(protection_at((uintptr_t)frames+MD_SVC_GUARD_OFFSET)==PROT_NONE);
    assert(protection_at((uintptr_t)frames)==(PROT_READ|PROT_WRITE));
    assert(protection_at((uintptr_t)frames+MD_SVC_GUARD_OFFSET+MD_SVC_GUARD_SIZE)==(PROT_READ|PROT_WRITE));
    assert(protection_at((uintptr_t)frames+MD_SVC_SLOT_SIZE+MD_SVC_GUARD_OFFSET)==(PROT_READ|PROT_WRITE));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct sock_filter filter[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mprotect,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={.len=sizeof(filter)/sizeof(*filter),.filter=filter};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program));
        assert(!md_svc_payload_dispatch(registers,&state,0x1234) && *ready==1);
        assert(protection_at((uintptr_t)frames+MD_SVC_GUARD_OFFSET)==PROT_NONE);
        registers=(void *)(frames+MD_SVC_SLOT_SIZE+MD_SVC_FRAME_OFFSET);
        ready=(void *)(frames+MD_SVC_SLOT_SIZE+MD_SVC_GUARD_STATE);
        assert(!md_svc_payload_dispatch(registers,&state,0x1234) && *ready==-1);
        assert(!md_svc_payload_dispatch(registers,&state,0x1234) && *ready==-1);
        assert(protection_at((uintptr_t)frames+MD_SVC_SLOT_SIZE+MD_SVC_GUARD_OFFSET)==(PROT_READ|PROT_WRITE));
        _exit(0);
    }
    int status;
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!*(int64_t *)(frames+MD_SVC_SLOT_SIZE+MD_SVC_GUARD_STATE));
    payload_statistics((void *)(frames+MD_SVC_FRAME_OFFSET));
    assert(!munmap(frames,size));
    puts("PASS SVC lazy stack guards: admission, isolated slots, fork inheritance and protection denial");
}

static int native_frame_memory(void *opaque,uintptr_t at,void *data,size_t size,int write) {
    uintptr_t frame=(uintptr_t)opaque;
    assert(at>=frame && at-frame+size<=MD_SVC_SLOT_SIZE);
    if (write) memcpy((void *)at,data,size); else memcpy(data,(void *)at,size);
    return 0;
}
static void native_frame_recovery(void) {
    void *allocation=mmap(NULL,2*MD_SVC_SLOT_SIZE,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(allocation!=MAP_FAILED);
    uintptr_t frame=((uintptr_t)allocation+MD_SVC_SLOT_SIZE-1)&~(uintptr_t)(MD_SVC_SLOT_SIZE-1);
    struct md_svc_direct_context c={.frames=frame,.svc=0x1000,.gate=0x4000,.number=SYS_read};
    struct md_native_gate gate={0x2000,0x2020,0x2030};
    const struct { unsigned state; uintptr_t pc; int done,restart; } cases[]={
        {MD_NATIVE_READY,0x9000,0,0},{MD_NATIVE_RUNNING,0x2000,0,0},
        {MD_NATIVE_RUNNING,0x2020,0,1},{MD_NATIVE_RUNNING,0x2024,1,0},
        {MD_NATIVE_RUNNING,0x2028,1,0},{MD_NATIVE_DONE,0x9000,1,0}};
    for (unsigned i=0;i<sizeof(cases)/sizeof(*cases);++i) {
        uint64_t *saved=(void *)(frame+MD_SVC_FRAME_OFFSET);
        for (unsigned j=0;j<31;++j) saved[j]=0xabc000+j;
        saved[8]=SYS_read; saved[31]=0xa0000000;
        *(uint64_t *)frame=frame;
        *(uint64_t *)(frame+MD_SVC_OWNER_OFFSET)=c.gate;
        *(uint64_t *)(frame+MD_SVC_SAVED_SP)=0x1000000;
        *(uint64_t *)(frame+MD_SVC_DIRECT_PENDING)=1;
        struct md_entry_native *native=(void *)(frame+MD_SVC_NATIVE_OFFSET);
        *native=(struct md_entry_native){.state=cases[i].state,.number=SYS_read,.result=17};
        struct user_pt_regs r={.sp=frame+MD_SVC_SLOT_SIZE-16,.pc=cases[i].pc,.pstate=0x60000000};
        r.regs[0]=cases[i].restart ? (uint64_t)-512 : 17;
        assert(md_svc_direct_native_recover(&c,&gate,&r,native_frame_memory,(void *)frame)
            ==(cases[i].restart ? MD_SVC_NATIVE_RESTART : 1));
        assert(r.sp==0x1000000 && r.pstate==0xa0000000);
        assert(r.pc==(cases[i].restart ? gate.call : c.svc+(cases[i].done ? 4 : 0)));
        assert(r.regs[0]==(cases[i].restart ? (uint64_t)-512 : cases[i].done ? 17 : saved[0]));
        assert(!memcmp(r.regs+1,saved+1,30*8));
        assert(!*(uint64_t *)frame && !*(uint64_t *)(frame+MD_SVC_DIRECT_PENDING));
    }
    *(uint64_t *)frame=frame;
    *(uint64_t *)(frame+MD_SVC_OWNER_OFFSET)=c.gate;
    struct md_entry_native *owned=(void *)(frame+MD_SVC_NATIVE_OFFSET);
    atomic_store(&owned->state,MD_NATIVE_OWNED);
    struct user_pt_regs waiting={.sp=frame+MD_SVC_SLOT_SIZE-16,.pc=gate.call},before=waiting;
    assert(!md_svc_direct_native_recover(&c,&gate,&waiting,native_frame_memory,(void *)frame));
    assert(!memcmp(&waiting,&before,sizeof(waiting)) && *(uint64_t *)frame==frame);
    assert(md_svc_direct_recover(&c,&waiting,native_frame_memory,(void *)frame)==2);
    assert(*(uint64_t *)(frame+MD_SVC_DIRECT_PENDING)==1);
    waiting=before;
    assert(!md_svc_direct_unwind(&c,&waiting,native_frame_memory,(void *)frame));
    assert(waiting.pc==c.svc+4 && waiting.sp==0x1000000);
    assert(!memcmp(waiting.regs,(void *)(frame+MD_SVC_FRAME_OFFSET),31*8));
    assert(!*(uint64_t *)frame && !*(uint64_t *)(frame+MD_SVC_DIRECT_PENDING));
    waiting=before;
    assert(md_svc_direct_unwind(&c,&waiting,native_frame_memory,(void *)frame)==-EPROTO);
    assert(!memcmp(&waiting,&before,sizeof(waiting)));
    assert(!munmap(allocation,2*MD_SVC_SLOT_SIZE));
    puts("PASS SVC native frame: pre-effect, restart, post-effect and exact guest context");
}
static void revoked_frames(void) {
    char *frames=mmap(NULL,MD_SVC_POOL_SIZE,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(frames!=MAP_FAILED);
    struct md_svc_registry r={.frames=(uintptr_t)frames,.count=1,
        .sites=(struct md_svc_site[]){{.direct={.gate=1}}}};
    uint64_t occupied=UINT64_MAX;
    assert(md_svc_registry_occupied(&r,getpid(),&occupied)==-EINVAL);
    r.disabled=1;
    assert(!md_svc_registry_occupied(&r,getpid(),&occupied) && !occupied);
    *(uint64_t *)frames=(uintptr_t)frames;
    *(uint64_t *)(frames+63*MD_SVC_SLOT_SIZE)=(uintptr_t)frames+63*MD_SVC_SLOT_SIZE;
    assert(!md_svc_registry_occupied(&r,getpid(),&occupied) && occupied==((UINT64_C(1)<<63)|1));
    struct md_entry_native *native=(void *)(frames+MD_SVC_NATIVE_OFFSET);
    atomic_store(&native->state,MD_NATIVE_RUNNING);
    /* The SVC-only one-register claim probes by exchange. A collision must
     * not turn an admitted native wait back into an undrained adapter. */
    assert(atomic_exchange((_Atomic uint64_t *)frames,(uintptr_t)frames)==(uintptr_t)frames);
    assert(!md_svc_registry_occupied(&r,getpid(),&occupied) && occupied==(UINT64_C(1)<<63));
    atomic_store(&native->state,MD_NATIVE_OWNED);
    assert(!md_svc_registry_occupied(&r,getpid(),&occupied) && occupied==(UINT64_C(1)<<63));
    atomic_store(&native->state,MD_NATIVE_RUNNING);
    uintptr_t last=(uintptr_t)frames+63*MD_SVC_SLOT_SIZE;
    *(uint64_t *)(last+MD_SVC_DIRECT_PENDING)=1;
    *(uint64_t *)(last+MD_SVC_GUARD_STATE)=1;
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct md_svc_registry *copy=md_svc_registry_copy(&r);
        assert(copy && copy->frames_dirty && !r.frames_dirty);
        copy->entry_fence_drained=1;
        assert(md_svc_registry_prepare_fork(copy,getpid(),INT_MAX)==-EBADF && copy->frames_dirty);
        int fd=open("/proc/self/mem",O_RDWR|O_CLOEXEC); assert(fd>=0);
        assert(!md_svc_registry_prepare_fork(copy,getpid(),fd) && !copy->frames_dirty);
        assert(!md_svc_registry_occupied(copy,getpid(),&occupied) && !occupied);
        assert(!*(uint64_t *)frames && !*(uint64_t *)last);
        assert(!*(uint64_t *)(last+MD_SVC_DIRECT_PENDING));
        assert(*(uint64_t *)(last+MD_SVC_GUARD_STATE)==1);
        assert(!close(fd) && !munmap(frames,MD_SVC_POOL_SIZE));
        assert(!md_svc_registry_prepare_fork(copy,getpid(),INT_MAX));
        md_svc_registry_free(copy); _exit(0);
    }
    int status=0;
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(*(uint64_t *)last==last && *(uint64_t *)(last+MD_SVC_DIRECT_PENDING)==1);
    assert(*(uint64_t *)frames==(uintptr_t)frames && atomic_load(&native->state)==MD_NATIVE_RUNNING);
    assert(!munmap(frames,MD_SVC_POOL_SIZE));
    r.entry_fence_drained=1;
    assert(!md_svc_registry_occupied(&r,getpid(),&occupied) && !occupied);
    assert(!md_svc_registry_occupied(NULL,getpid(),&occupied) && !occupied);
    puts("PASS SVC revoked frames: occupied slots, fork reset, exact release and retired observation");
}
int main(void) {
    native_frame_recovery();
    frame_layout();
    client_retirement();
    client_expansion();
    entry_revocation();
    code_staging();
    sole_identity();
    publication_admission();
    deferred_admission();
    registry_growth();
    guard_recovery();
    lazy_stack_guards();
    revoked_frames();
    struct md_svc_registry empty={.count=2,.disabled=1,.sites=(struct md_svc_site[2]){0}};
    assert(!md_svc_registry_resident(NULL) && !md_svc_registry_resident(&empty));
    struct md_svc_registry registry={.frames=0x800000,.pool_count=1,.count=2,
        .pools=(struct md_svc_code_pool[]){{.address=0x40000,.used=0x1000}},
        .sites=(struct md_svc_site[]){{.direct={.svc=0x10000,.gate=0x40000},.kernel_alias=0x40100},
                {.state=MD_SVC_RETIRED,.direct={.svc=0x20000,.gate=0x40200},
                 .kernel_alias=0x40300}}};
    assert(md_svc_registry_resident(&registry));
    empty.sites[1].direct.gate=0x40000;
    empty.sites[1].state=MD_SVC_RETIRED;
    assert(md_svc_registry_resident(&empty));
    assert(md_svc_registry_call_origin(NULL,0x40104)==0x40104);
    assert(md_svc_registry_call_origin(&registry,0x10004)==0x10004);
    assert(md_svc_registry_call_origin(&registry,0x40100)==0x40100);
    assert(md_svc_registry_call_origin(&registry,0x40104)==0x10004);
    assert(md_svc_registry_call_origin(&registry,0x40108)==0x40108);
    assert(md_svc_registry_call_origin(&registry,0x40304)==0x20004);
    assert(md_svc_registry_call_origin(&registry,0x50004)==0x50004);
    assert(md_svc_registry_replay(NULL,0x10004)==0x10000);
    assert(md_svc_registry_replay(&registry,0x10004)==0x40100);
    assert(md_svc_registry_replay(&registry,0x40104)==0x40100);
    assert(md_svc_registry_replay(&registry,0x50004)==0x50000);
    assert(!md_svc_registry_context(NULL,0x40104,0x800001));
    assert(!md_svc_registry_context(&registry,0x10004,0x700000));
    assert(!md_svc_registry_context(&registry,0x3ffff,0x800000));
    assert(md_svc_registry_context(&registry,0x40000,0x700000));
    assert(md_svc_registry_context(&registry,0x40304,0x700000));
    assert(md_svc_registry_context(&registry,0x41000,0x700000));
    assert(!md_svc_registry_context(&registry,0x41001,0x700000));
    assert(md_svc_registry_context(&registry,0x10004,0x800001));
    assert(md_svc_registry_context(&registry,0x10004,0x800000+MD_SVC_POOL_SIZE));
    assert(!md_svc_registry_context(&registry,0x10004,0x800001+MD_SVC_POOL_SIZE));
    assert(md_svc_registry_direct_at(&registry,0,0x40304,0x700000)==registry.sites+1);
    assert(!md_svc_registry_direct_at(&registry,0,0x10004,0x700000));
    registry.sites[0].original=0xd4000001;
    registry.sites[0].replacement=0x1400c000;
    assert(md_svc_registry_instruction(NULL,0x10000,0x1400c000)==0x1400c000);
    assert(md_svc_registry_instruction(&registry,0x10000,0x1400c000)==0xd4000001);
    assert(md_svc_registry_instruction(&registry,0x10004,0x1400c000)==0x1400c000);
    assert(md_svc_registry_instruction(&registry,0x10000,0xd503201f)==0xd503201f);
    registry.sites[0].state=MD_SVC_RETIRED;
    assert(md_svc_registry_replay(&registry,0x10004)==0x10000);
    assert(md_svc_registry_instruction(&registry,0x10000,0x1400c000)==0x1400c000);
    registry.sites[0].state=MD_SVC_PUBLISHED;
    registry.disabled=1;
    assert(md_svc_registry_replay(&registry,0x10004)==0x10000);
    assert(md_svc_registry_call_origin(&registry,0x40304)==0x20004);
    assert(md_svc_registry_context(&registry,0x40304,0x700000));
    uint32_t word=0;
    assert(!md_svc_branch(0x10000000,0x17fffffc,&word) && word==0x15ffffff);
    assert(!md_svc_branch(0x10000000,0x08000000,&word) && word==0x16000000);
    assert(md_svc_branch(0x10000000,0x18000000,&word)==-ERANGE);
    assert(md_svc_branch(0x10000000,0x07fffffc,&word)==-ERANGE);
    assert(md_svc_branch(0x10000001,0x10000000,&word)==-EINVAL);
    assert(md_svc_branch(0x10000000,0x10000002,&word)==-EINVAL);
    assert(md_svc_branch(0,0,NULL)==-EINVAL);
    puts("PASS SVC planner: branch limits, explicit rejection and syscall origin");
}
