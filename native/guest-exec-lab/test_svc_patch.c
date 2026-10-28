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
static int publication_memory=-1;
ssize_t __real_process_vm_readv(pid_t,const struct iovec *,unsigned long,const struct iovec *,unsigned long,unsigned long);
ssize_t __wrap_process_vm_readv(pid_t pid,const struct iovec *local,unsigned long ln,
        const struct iovec *remote,unsigned long rn,unsigned long flags) {
    if (publication_code && pid==getpid()) {
        assert(ln==1 && rn==1 && !flags && remote->iov_base==publication_code && remote->iov_len<=8);
        memcpy(local->iov_base,remote->iov_base,remote->iov_len); return remote->iov_len;
    }
    if (pid==getpid()) return __real_process_vm_readv(pid,local,ln,remote,rn,flags);
    assert((pid==111 || pid==222) && ln==1 && rn==1 && !flags);
    ++reads;
    if (fail_read) { errno=ESRCH; return -1; }
    assert(remote->iov_base==&payload && remote->iov_len==sizeof(payload));
    memcpy(local->iov_base,&payload,sizeof(payload)); return sizeof(payload);
}
long __wrap_ptrace(int request,pid_t pid,void *address,void *value) {
    if (publication_code && pid==getpid()) {
        assert(request==PTRACE_POKETEXT && address==publication_code);
        uint64_t word=(uintptr_t)value;
        assert(pwrite(publication_memory,&word,8,(off_t)(uintptr_t)address)==8);
        return 0;
    }
    assert(pid==111 || pid==222 || pid==INT_MAX);
    if (request==PTRACE_PEEKTEXT) { assert(fail_read); errno=ESRCH; return -1; }
    assert(request==PTRACE_POKETEXT);
    if (writes++==fail_write) { errno=ESRCH; return -1; }
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
    assert(copy && copy->clients_dirty && !r.clients_dirty); free(copy);
    r.disabled=1; r.clients_dirty=1; fail_read=1; fail_write=writes;
    int previous=reads+writes;
    assert(!md_svc_registry_collect_clients(&r,111,-1,alive,&payload));
    assert(previous==reads+writes && r.clients_dirty);
    fail_read=0; fail_write=-1;
    puts("PASS SVC client retirement: lost stop, live peer, owner-last publication and idle fast path");
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
    free(copy);
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
    publication_memory=open("/proc/self/mem",O_RDWR|O_CLOEXEC); assert(publication_memory>=0);
    uintptr_t source=(uintptr_t)publication_code;
    uint32_t original[]={0xd4000001,0xd503201f};
    assert(pwrite(publication_memory,original,8,(off_t)source)==8);
    struct md_svc_registry r={.frames=MD_SVC_POOL_BASE,.dispatch=source+8,.pool_count=1,
        .pools={{.address=source+MD_SVC_CODE_BYTES}}};
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
    assert(!close(publication_memory) && !munmap(publication_code,size));
    publication_code=NULL; publication_memory=-1;
    puts("PASS SVC admission: armed/suspended extension, restore-only rearm and replaced-code rejection");
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
        struct md_svc_direct_context c={.svc=0x100000,.gate=0x200000,.frames=MD_SVC_POOL_BASE,.number=numbers[i]};
        struct md_svc_direct_fallback exits={.mismatch=0x300000,.declined=0x300000,.busy=0x300000};
        assert(!md_svc_direct_signal_plan(&c,0x400000,exits,code,sizeof(code),&branch));
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
    call->result=321;
    return call->args[0] ? MD_ENTRY_COMPLETED : MD_ENTRY_DECLINED;
}
static int payload_memory(struct md_entry_client *client,long tid,long number,const uint64_t *args,int64_t *result) {
    (void)client;
    assert(tid==123 && number==1 && args[0]==0);
    *result=456;
    return 1;
}
static void payload_statistics(uint64_t *registers) {
    unsigned char offers[]={MD_ENTRY_ORDINARY,MD_ENTRY_ORDINARY|MD_ENTRY_NATIVE_MEMORY};
    const struct md_entry_api api={.version=MD_ENTRY_VERSION,.size=sizeof(api),
        .try_syscall=payload_call,.offers=offers,.syscall_count=2,
        .thread_identity=payload_identity,.native_memory=payload_memory};
    for (unsigned enabled=0;enabled<2;++enabled) {
        struct md_svc_payload_state state={.api=&api,.admitted=1,.statistics=enabled};
        registers[8]=0; registers[0]=1;
        assert(md_svc_payload_dispatch(registers,&state)==1 && registers[0]==321);
        registers[0]=0;
        assert(!md_svc_payload_dispatch(registers,&state));
        registers[8]=1;
        assert(md_svc_payload_dispatch(registers,&state)==1 && registers[0]==456);
        assert(state.completed==2*enabled && state.declined==enabled && !state.unknown);
        state.admitted=0;
        assert(!md_svc_payload_dispatch(registers,&state));
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
    assert(!md_svc_payload_dispatch(registers,&state) && !*ready);
    assert(protection_at((uintptr_t)frames+MD_SVC_GUARD_OFFSET)==(PROT_READ|PROT_WRITE));
    state.admitted=1;
    assert(!md_svc_payload_dispatch(registers,&state) && *ready==1);
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
        assert(!md_svc_payload_dispatch(registers,&state) && *ready==1);
        assert(protection_at((uintptr_t)frames+MD_SVC_GUARD_OFFSET)==PROT_NONE);
        registers=(void *)(frames+MD_SVC_SLOT_SIZE+MD_SVC_FRAME_OFFSET);
        ready=(void *)(frames+MD_SVC_SLOT_SIZE+MD_SVC_GUARD_STATE);
        assert(!md_svc_payload_dispatch(registers,&state) && *ready==-1);
        assert(!md_svc_payload_dispatch(registers,&state) && *ready==-1);
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

static void revoked_frames(void) {
    char *frames=mmap(NULL,MD_SVC_POOL_SIZE,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(frames!=MAP_FAILED);
    struct md_svc_registry r={.frames=(uintptr_t)frames,.count=1,.sites={{.direct={.gate=1}}}};
    uint64_t occupied=UINT64_MAX;
    assert(md_svc_registry_occupied(&r,getpid(),&occupied)==-EINVAL);
    r.disabled=1;
    assert(!md_svc_registry_occupied(&r,getpid(),&occupied) && !occupied);
    *(uint64_t *)frames=1;
    *(uint64_t *)(frames+63*MD_SVC_SLOT_SIZE)=2;
    assert(!md_svc_registry_occupied(&r,getpid(),&occupied) && occupied==((UINT64_C(1)<<63)|1));
    *(uint64_t *)frames=0;
    assert(!md_svc_registry_occupied(&r,getpid(),&occupied) && occupied==(UINT64_C(1)<<63));
    uintptr_t last=(uintptr_t)frames+63*MD_SVC_SLOT_SIZE;
    *(uint64_t *)(last+MD_SVC_DIRECT_PENDING)=1;
    *(uint64_t *)(last+MD_SVC_GUARD_STATE)=1;
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct md_svc_registry *copy=md_svc_registry_copy(&r);
        assert(copy && copy->frames_dirty && !r.frames_dirty);
        assert(md_svc_registry_prepare_fork(copy,getpid(),INT_MAX)==-EBADF && copy->frames_dirty);
        int fd=open("/proc/self/mem",O_RDWR|O_CLOEXEC); assert(fd>=0);
        assert(!md_svc_registry_prepare_fork(copy,getpid(),fd) && !copy->frames_dirty);
        assert(!md_svc_registry_occupied(copy,getpid(),&occupied) && !occupied);
        assert(!*(uint64_t *)(last+MD_SVC_DIRECT_PENDING));
        assert(*(uint64_t *)(last+MD_SVC_GUARD_STATE)==1);
        assert(!close(fd) && !munmap(frames,MD_SVC_POOL_SIZE));
        assert(!md_svc_registry_prepare_fork(copy,getpid(),INT_MAX));
        free(copy); _exit(0);
    }
    int status=0;
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(*(uint64_t *)last==2 && *(uint64_t *)(last+MD_SVC_DIRECT_PENDING)==1);
    assert(!munmap(frames,MD_SVC_POOL_SIZE));
    r.entry_fence_drained=1;
    assert(!md_svc_registry_occupied(&r,getpid(),&occupied) && !occupied);
    assert(!md_svc_registry_occupied(NULL,getpid(),&occupied) && !occupied);
    puts("PASS SVC revoked frames: occupied slots, fork reset, exact release and retired observation");
}
int main(void) {
    client_retirement();
    code_staging();
    sole_identity();
    publication_admission();
    guard_recovery();
    lazy_stack_guards();
    revoked_frames();
    struct md_svc_registry empty={.count=2,.disabled=1};
    assert(!md_svc_registry_resident(NULL) && !md_svc_registry_resident(&empty));
    struct md_svc_registry registry={.frames=0x800000,.pool_count=1,.count=2,
        .pools={{.address=0x40000,.used=0x1000}},
        .sites={{.direct={.svc=0x10000,.gate=0x40000},.kernel_alias=0x40100},
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
    assert(md_svc_registry_instruction(&registry,0x10000,0x1400c000)==0x1400c000);
    registry.sites[0].state=MD_SVC_PUBLISHED;
    registry.disabled=1;
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
