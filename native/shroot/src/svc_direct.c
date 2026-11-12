#include "svc_direct.h"
#include "svc_patch.h"
#include "svc_frame.h"
#include <errno.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>

extern const unsigned char md_svc_single_begin[],md_svc_single_prefix[],md_svc_single_dispatch[];
extern const unsigned char md_svc_single_state[],md_svc_single_owner[];
extern const unsigned char md_svc_single_origin[];
extern const unsigned char md_svc_single_done_replay[],md_svc_single_kernel_replay[],md_svc_single_busy_replay[];
extern const unsigned char md_svc_single_done_branch[],md_svc_single_kernel_branch[],md_svc_single_busy_branch[];
extern const unsigned char md_svc_single_claim[],md_svc_single_claimed[],md_svc_single_owned[];
extern const unsigned char md_svc_single_frame[],md_svc_single_private_sp[];
extern const unsigned char md_svc_single_probe1[],md_svc_single_tested1[];
extern const unsigned char md_svc_single_probe2[],md_svc_single_tested2[];
extern const unsigned char md_svc_single_probe3[],md_svc_single_tested3[],md_svc_single_probes_end[];
extern const unsigned char md_svc_single_borrow_lr[],md_svc_single_saved_gprs[],md_svc_single_saved_sp[];
extern const unsigned char md_svc_single_invoke[],md_svc_single_returned[],md_svc_single_outcome[];
extern const unsigned char md_svc_single_done[],md_svc_single_kernel[];
enum { GUARD_BYTES=64*4, MAP_GUARD=32, MAP_GUARD_END=40 };
static size_t at(const unsigned char *label) { return (size_t)(label-md_svc_single_begin); }
size_t md_svc_direct_size(void) { return GUARD_BYTES+md_svc_single_size()+16; }
int md_svc_direct_kernel(uintptr_t svc,uintptr_t destination,uint32_t output[2]) {
    if (!output || ((svc|destination)&3) || svc>UINTPTR_MAX-4 || destination>UINTPTR_MAX-4)
        return -EINVAL;
    int error=md_svc_branch(destination+4,svc+4,output+1);
    if (!error) output[0]=UINT32_C(0xd4000001);
    return error;
}
int md_svc_direct_plan(uintptr_t svc,uint64_t number,uintptr_t destination,
        uintptr_t dispatch,uintptr_t frames,struct md_svc_direct_fallback exits,void *output,size_t capacity,
        uint32_t *replacement) {
    size_t size=md_svc_direct_size(),body_size=md_svc_single_size();
    if (!output || !replacement || !dispatch || ((svc|destination|dispatch|exits.mismatch|exits.declined|exits.busy)&3)
            || svc>UINTPTR_MAX-4 || destination>UINTPTR_MAX-size) return -EINVAL;
    if (capacity<size) return -ENOSPC;
    uint32_t jump;
    int error=md_svc_branch(svc,destination,&jump);
    if (error) return error;
    unsigned char *code=output,*body=code+GUARD_BYTES;
    uintptr_t body_pc=destination+GUARD_BYTES;
    /* The ordinary x8 template needs no guest stack, TLS, or spare register.
     * Its replay slots load the syscall number established by the guard. */
    memcpy(body,md_svc_single_begin,body_size);
    error=md_svc_frame_relocate(body,body_size,frames);
    if (error) return error;
    memcpy(body+at(md_svc_single_dispatch),&dispatch,8);
    uintptr_t state=frames+MD_SVC_POOL_SIZE;
    memcpy(body+at(md_svc_single_state),&state,8);
    memcpy(body+at(md_svc_single_owner),&destination,8);
    uintptr_t original_pc=svc+4;
    memcpy(body+at(md_svc_single_origin),&original_pc,8);
    memcpy(body+body_size,&number,8);
    uint32_t miss_branch;
    error=md_svc_branch(body_pc+body_size+8,exits.mismatch,&miss_branch);
    if (error) return error;
    memcpy(body+body_size+8,&miss_branch,4);
    uint32_t nop=UINT32_C(0xd503201f);
    memcpy(body+body_size+12,&nop,4);
    if (number<=4095) {
        /* SUB (not SUBS) preserves NZCV and tests all 64 bits. The scratch
         * value is reversible even on a mismatch; no guest stack or peer
         * exclusion is needed. Signal recovery knows its exact three PCs. */
        uint32_t guard[]={UINT32_C(0xd1000108)|((uint32_t)number<<10),
            UINT32_C(0xb5000048),0,UINT32_C(0x91000108)|((uint32_t)number<<10),0};
        error=md_svc_branch(destination+8,number==SYS_mmap ? destination+MAP_GUARD : body_pc,guard+2);
        if (!error) error=md_svc_branch(destination+16,exits.mismatch,guard+4);
        if (error) return error;
        for (unsigned i=0;i<GUARD_BYTES;i+=4) memcpy(code+i,&nop,4);
        memcpy(code,guard,sizeof(guard));
        if (number==SYS_mmap) {
            /* Only anonymous nonreplacing mappings bypass interception in
             * the baseline filter. File-backed and replacing calls enter the
             * same C memory owner; this is not a second admission whitelist. */
            _Static_assert(MAP_FIXED==(1u<<4) && MAP_ANONYMOUS==(1u<<5),"AArch64 mmap flags");
            for (unsigned i=0;i<2;++i) {
                unsigned at=MAP_GUARD+4*i;
                uint32_t test=UINT32_C(0x36000003)|((5-i)<<19)|(i<<24)
                    |(((uint32_t)((GUARD_BYTES-at)/4)&0x3fff)<<5);
                memcpy(code+at,&test,4);
            }
            uint32_t enter;
            error=md_svc_branch(destination+MAP_GUARD_END,destination+12,&enter);
            if (error) return error;
            memcpy(code+MAP_GUARD_END,&enter,4);
        }
    } else for (unsigned bit=0;bit<64;++bit) {
        size_t offset=GUARD_BYTES+body_size+8-bit*4;
        if (offset>=32768) return -ERANGE;
        /* TBZ/TBNZ inspect x8 without writing a GPR or NZCV, even for values
         * with nonzero upper bits. Equality is not inferred from one sample. */
        uint32_t instruction=UINT32_C(0x36000008)|((bit>>5)<<31)|((bit&31)<<19)
            |(((number>>bit)&1) ? 0 : (1u<<24))|((uint32_t)(offset/4)<<5);
        memcpy(code+bit*4,&instruction,4);
    }
    const unsigned char *replays[]={md_svc_single_prefix,md_svc_single_done_replay,
        md_svc_single_kernel_replay,md_svc_single_busy_replay};
    for (unsigned i=0;i<4;++i) {
        uint32_t instruction=UINT32_C(0x58000008)|((uint32_t)((body_size-at(replays[i]))/4)<<5);
        memcpy(body+at(replays[i]),&instruction,4);
    }
    const unsigned char *branches[]={md_svc_single_done_branch,md_svc_single_kernel_branch,md_svc_single_busy_branch};
    for (unsigned i=0;i<3;++i) {
        uint32_t instruction;
        error=md_svc_branch(body_pc+at(branches[i]),i==2 ? exits.busy : i ? exits.declined : svc+4,&instruction);
        if (error) return error;
        memcpy(body+at(branches[i]),&instruction,4);
    }
    *replacement=jump;
    return 0;
}
size_t md_svc_direct_signal_size(void) { return md_svc_direct_size()+40; }
uintptr_t md_svc_direct_checkpoint(const struct md_svc_direct_context *c) {
    return c->checkpoint;
}
int md_svc_direct_signal_plan(const struct md_svc_direct_context *c,uintptr_t dispatch,
        struct md_svc_direct_fallback exits,void *output,size_t capacity,uint32_t *replacement) {
    if (!c || !c->checkpoint || (c->checkpoint&3) || capacity<md_svc_direct_signal_size()) return -EINVAL;
    int error=md_svc_direct_plan(c->svc,c->number,c->gate,dispatch,c->frames,exits,output,capacity,replacement);
    if (error) return error;
    size_t returned=GUARD_BYTES+at(md_svc_single_returned),size=md_svc_direct_size();
    uint32_t *tail=(void *)((char *)output+size),jump;
    memcpy(tail,(char *)output+returned,4);
    tail[1]=UINT32_C(0xf9400000)|((MD_SVC_DIRECT_PENDING/8)<<10)|(8u<<5)|30;
    tail[2]=UINT32_C(0xb5000000)|(2u<<5)|30;
    error=md_svc_branch(c->gate+size+12,c->gate+returned+4,tail+3);
    if (error) return error;
    unsigned checkpoint=c->checkpoint_number ? c->checkpoint_number : SYS_getuid;
    if (checkpoint>65535) return -EINVAL;
    tail[4]=UINT32_C(0xd2800008)|(checkpoint<<5);
    /* A chosen syscall number need not be intercepted at an arbitrary PC.
     * The fixed marker owns return admission, including inherited policies. */
    tail[5]=UINT32_C(0x5800007e); /* ldr x30, [pc, #12] */
    tail[6]=UINT32_C(0xd61f03c0); /* br x30 */
    tail[7]=UINT32_C(0xd503201f);
    memcpy(tail+8,&c->checkpoint,sizeof(c->checkpoint));
    error=md_svc_branch(c->gate+returned,c->gate+size,&jump);
    if (!error) memcpy((char *)output+returned,&jump,4);
    return error;
}
static uintptr_t frame_for_sp(const struct md_svc_direct_context *c,uintptr_t sp) {
    return c->frames+(((sp^(sp>>16))>>8)&63)*MD_SVC_SLOT_SIZE;
}
static int saved_context(struct user_pt_regs *r,uintptr_t frame,
        md_svc_direct_memory memory,void *opaque) {
    uint64_t flags;
    int error=memory(opaque,frame+MD_SVC_FRAME_OFFSET,r->regs,31*8,0);
    if (!error) error=memory(opaque,frame+MD_SVC_SAVED_SP,&r->sp,8,0);
    if (!error) error=memory(opaque,frame+MD_SVC_FRAME_OFFSET+248,&flags,8,0);
    if (!error) r->pstate=(r->pstate&~UINT64_C(0xf0000000))|(flags&UINT64_C(0xf0000000));
    return error;
}
int md_svc_direct_unwind(const struct md_svc_direct_context *c,struct user_pt_regs *r,
        md_svc_direct_memory memory,void *opaque) {
    if (!c || !r || !memory) return -EINVAL;
    if (r->sp<=c->frames || r->sp>c->frames+MD_SVC_POOL_SIZE) return -EINVAL;
    uintptr_t frame=(r->sp-1)&~(uintptr_t)(MD_SVC_SLOT_SIZE-1);
    uint64_t busy,owner,zero=0;
    int error=memory(opaque,frame,&busy,8,0);
    if (!error) error=memory(opaque,frame+MD_SVC_OWNER_OFFSET,&owner,8,0);
    if (error) return error;
    if (!busy || owner!=c->gate) return -EPROTO;
    error=saved_context(r,frame,memory,opaque);
    if (!error) error=memory(opaque,frame+MD_SVC_DIRECT_PENDING,&zero,8,1);
    if (!error) error=memory(opaque,frame,&zero,8,1);
    if (!error) { r->regs[8]=c->number; r->pc=c->svc+4; }
    return error;
}
int md_svc_direct_native_recover(const struct md_svc_direct_context *c,const struct md_native_gate *gate,
        struct user_pt_regs *r,md_svc_direct_memory memory,void *opaque) {
    if (!c || !gate || !r || !memory) return -EINVAL;
    if (r->sp<=c->frames || r->sp>c->frames+MD_SVC_POOL_SIZE) return 0;
    uintptr_t frame=(r->sp-1)&~(uintptr_t)(MD_SVC_SLOT_SIZE-1);
    uint64_t busy,owner,zero=0;
    int error=memory(opaque,frame,&busy,8,0);
    if (error || !busy) return error;
    struct md_entry_native native;
    error=memory(opaque,frame+MD_SVC_NATIVE_OFFSET,&native,sizeof(native),0);
    if (error) return error;
    unsigned state=atomic_load_explicit(&native.state,memory_order_relaxed);
    if (state==MD_NATIVE_EMPTY || state==MD_NATIVE_OWNED) return 0;
    error=memory(opaque,frame+MD_SVC_OWNER_OFFSET,&owner,8,0);
    if (error || owner!=c->gate) return error ? error : -EPROTO;
    int restart=state==MD_NATIVE_RUNNING && r->pc==gate->call;
    int done=state==MD_NATIVE_DONE || (state==MD_NATIVE_RUNNING
        && r->pc>gate->call && r->pc<=gate->end);
    if (state!=MD_NATIVE_READY && state!=MD_NATIVE_DONE
            && !(state==MD_NATIVE_RUNNING && r->pc>=gate->begin && r->pc<=gate->end)) return -EPROTO;
    long result=state==MD_NATIVE_DONE ? native.result : (long)r->regs[0];
    error=saved_context(r,frame,memory,opaque);
    if (error) return error;
    if (done || restart) r->regs[0]=(uint64_t)result;
    r->pc=restart ? gate->call : c->svc+(done ? 4 : 0);
    error=memory(opaque,frame+MD_SVC_DIRECT_PENDING,&zero,8,1);
    if (!error) error=memory(opaque,frame,&zero,8,1);
    return error ? error : restart ? MD_SVC_NATIVE_RESTART : 1;
}
int md_svc_direct_recover(const struct md_svc_direct_context *c,struct user_pt_regs *r,
        md_svc_direct_memory memory,void *opaque) {
    if (!c || !r || !memory) return -EINVAL;
    uintptr_t body=c->gate+GUARD_BYTES,pc=r->pc,frame=0;
    size_t size=md_svc_direct_size(),body_size=md_svc_single_size();
    if ((pc>=c->gate && pc<body) || pc==body+body_size+8) {
        if (c->number<=4095 && (pc==c->gate+4 || pc==c->gate+8 || pc==c->gate+12
                || (c->number==SYS_mmap && pc>=c->gate+MAP_GUARD && pc<=c->gate+MAP_GUARD_END)))
            r->regs[8]+=c->number;
        r->pc=c->svc;
        return 1;
    }
    int private_sp=r->sp>c->frames && r->sp<=c->frames+MD_SVC_POOL_SIZE;
    int tail=(pc>=c->gate+size && pc<=c->gate+size+24)
        || pc==c->checkpoint || pc==c->checkpoint+4;
    int post=pc>=body+at(md_svc_single_returned) && pc<body+at(md_svc_single_busy_replay);
    int done=0;
    if (tail || post) {
        if (pc>=body+at(md_svc_single_done_replay) && pc<=body+at(md_svc_single_done_branch)) done=1;
        else if (!(pc>=body+at(md_svc_single_kernel_replay) && pc<=body+at(md_svc_single_kernel_branch))) {
            if (tail || pc<body+at(md_svc_single_returned)+8) {
                if (!private_sp) return -EINVAL;
                frame=(r->sp-1)&~(uintptr_t)(MD_SVC_SLOT_SIZE-1);
            } else if (pc==body+at(md_svc_single_done)+8 || pc==body+at(md_svc_single_kernel)+8) {
                frame=r->regs[8];
            } else frame=r->regs[8]-MD_SVC_FRAME_OFFSET;
            if (frame<c->frames || frame>=c->frames+MD_SVC_POOL_SIZE || frame%MD_SVC_SLOT_SIZE) return -EINVAL;
            if (tail || pc<body+at(md_svc_single_outcome)) done=r->regs[0]!=0;
            else {
                uint64_t target;
                int error=memory(opaque,frame+MD_SVC_FRAME_OFFSET+272,&target,8,0);
                if (error) return error;
                if (target!=body+at(md_svc_single_done) && target!=body+at(md_svc_single_kernel)) return -EINVAL;
                done=target==body+at(md_svc_single_done);
            }
            int error=saved_context(r,frame,memory,opaque);
            if (error) return error;
        }
    } else if (pc>=body && pc<=body+at(md_svc_single_invoke)) {
        if (pc>=body+at(md_svc_single_private_sp)) {
            if (!private_sp) return -EINVAL;
            frame=(r->sp-1)&~(uintptr_t)(MD_SVC_SLOT_SIZE-1);
        } else if (pc>=body+at(md_svc_single_prefix)+4) {
            frame=r->regs[30]-(pc==body+at(md_svc_single_saved_sp)+8 ? 0 : MD_SVC_FRAME_OFFSET);
        } else if (pc>=body+at(md_svc_single_frame)) frame=r->regs[8];
        else frame=frame_for_sp(c,r->sp);
        if (pc>=body+at(md_svc_single_saved_sp)) {
            /* SP may already be private, while x30 may already be scratch. */
            if (private_sp) frame=(r->sp-1)&~(uintptr_t)(MD_SVC_SLOT_SIZE-1);
            int error=memory(opaque,frame+MD_SVC_SAVED_SP,&r->sp,8,0);
            if (error) return error;
        }
        if (pc>=body+at(md_svc_single_saved_gprs)) {
            int error=memory(opaque,frame+MD_SVC_FRAME_OFFSET,r->regs,31*8,0);
            if (error) return error;
        } else if (pc>=body+at(md_svc_single_borrow_lr)) {
            int error=memory(opaque,frame+MD_SVC_FRAME_OFFSET+240,&r->regs[30],8,0);
            if (error) return error;
        }
        if (pc<body+at(md_svc_single_claimed)
                || (pc==body+at(md_svc_single_claimed) && r->regs[8])) frame=0;
    } else if (pc>=body+at(md_svc_single_probe1) && pc<body+at(md_svc_single_probes_end)) {
        const unsigned char *begins[]={md_svc_single_probe1,md_svc_single_probe2,md_svc_single_probe3,
            md_svc_single_probes_end};
        const unsigned char *tested[]={md_svc_single_tested1,md_svc_single_tested2,md_svc_single_tested3};
        for (unsigned i=0;i<MD_SVC_SLOT_ALTERNATIVES-1;++i) {
            if (pc>=body+at(begins[i+1])) continue;
            if (pc>body+at(tested[i]) || (pc==body+at(tested[i]) && !r->regs[8]))
                frame=frame_for_sp(c,r->sp)^((uintptr_t)MD_SVC_SLOT_XOR(i+1)*MD_SVC_SLOT_SIZE);
            break;
        }
    } else if (pc==body+at(md_svc_single_busy_replay) || pc==body+at(md_svc_single_busy_branch)) {
        frame=0;
    } else if (private_sp) {
        frame=(r->sp-1)&~(uintptr_t)(MD_SVC_SLOT_SIZE-1);
        uint64_t owner,busy,one=1;
        int error=memory(opaque,frame+MD_SVC_OWNER_OFFSET,&owner,8,0);
        if (!error) error=memory(opaque,frame,&busy,8,0);
        if (error) return error;
        if (!busy || owner!=c->gate) return 0;
        error=memory(opaque,frame+MD_SVC_DIRECT_PENDING,&one,8,1);
        return error ? error : 2;
    } else return 0;
    r->regs[8]=c->number;
    r->pc=c->svc+(done ? 4 : 0);
    if (frame) {
        uint64_t zero=0;
        int error=memory(opaque,frame+MD_SVC_DIRECT_PENDING,&zero,8,1);
        if (!error) error=memory(opaque,frame,&zero,8,1);
        if (error) return error;
    }
    return 1;
}
