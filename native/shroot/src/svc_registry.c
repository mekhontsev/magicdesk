#define _GNU_SOURCE
#include "svc_registry.h"
#include "mapping_reader.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/mman.h>
#include <unistd.h>

int md_svc_registry_read(pid_t pid,uintptr_t at,void *data,size_t size) {
    if (size>UINTPTR_MAX-at) return -EFAULT;
    struct iovec local={data,size},remote={(void *)at,size};
    if (process_vm_readv(pid,&local,1,&remote,1,0)==(ssize_t)size) return 0;
    for (size_t i=0;i<size;) {
        uintptr_t aligned=(at+i)&~(uintptr_t)7;
        size_t skip=(at+i)-aligned,bytes=8-skip;
        if (bytes>size-i) bytes=size-i;
        errno=0; uint64_t value=ptrace(PTRACE_PEEKTEXT,pid,(void *)aligned,NULL);
        if (errno) return -errno;
        memcpy((char *)data+i,(char *)&value+skip,bytes); i+=bytes;
    }
    return 0;
}
int md_svc_registry_write(pid_t pid,uintptr_t at,const void *data,size_t size) {
    if (size>UINTPTR_MAX-at) return -EFAULT;
    for (size_t i=0;i<size;) {
        uintptr_t aligned=(at+i)&~(uintptr_t)7;
        size_t skip=(at+i)-aligned,bytes=8-skip;
        if (bytes>size-i) bytes=size-i;
        uint64_t value=0;
        if (skip || bytes<8) {
            int error=md_svc_registry_read(pid,aligned,&value,8);
            if (error) return error;
        }
        memcpy((char *)&value+skip,(const char *)data+i,bytes);
        if (ptrace(PTRACE_POKETEXT,pid,(void *)aligned,(void *)value)<0) return -errno;
        i+=bytes;
    }
    return 0;
}
int md_svc_registry_stage(pid_t pid,int memory_fd,uintptr_t at,const void *data,size_t size) {
    if (size>PTRDIFF_MAX || size>UINTPTR_MAX-at) return -EFAULT;
    if (!size) return 0;
    /* Borrow this mm owner's retained proc descriptor while the task is
     * stopped. Both writers maintain remote instruction caches; the block
     * write avoids reopening proc or crossing the kernel once per word. */
    if (memory_fd>=0) {
        ssize_t n;
        do n=pwrite(memory_fd,data,size,(off_t)at); while (n<0 && errno==EINTR);
        if (n==(ssize_t)size) return 0;
        if (!n) return -ESRCH;
    }
    return md_svc_registry_write(pid,at,data,size);
}
int md_svc_registry_executable(pid_t pid,uintptr_t address,size_t size) {
    if (!size || size>UINTPTR_MAX-address) return 0;
    char path[64]; snprintf(path,sizeof(path),"/proc/%d/maps",pid);
    int fd=open(path,O_RDONLY|O_CLOEXEC); if (fd<0) return 0;
    struct md_mapping_reader reader={.fd=fd}; struct md_mapping_record v;
    uintptr_t cursor=address,end=address+size;
    while (cursor<end && md_mapping_next(&reader,&v)>0) {
        if (cursor<v.first) break;
        if (cursor>=v.end) continue;
        if ((v.protection&(PROT_WRITE|PROT_EXEC))!=PROT_EXEC || v.shared) break;
        cursor=v.end<end ? v.end : end;
    }
    close(fd); return cursor==end;
}
static struct md_svc_site *head(struct md_svc_registry *r,uintptr_t svc) {
    if (r) for (unsigned i=r->count;i;--i)
        if (r->sites[i-1].direct.svc==svc && r->sites[i-1].state==MD_SVC_PUBLISHED)
            return &r->sites[i-1];
    return NULL;
}
static int reserve_site(struct md_svc_registry *r) {
    if (r->count<r->capacity) return 0;
    if (r->capacity>UINT_MAX/2) return -EOVERFLOW;
    unsigned capacity=r->capacity ? r->capacity*2 : 16;
    size_t bytes;
    if (__builtin_mul_overflow((size_t)capacity,sizeof(*r->sites),&bytes)) return -EOVERFLOW;
    struct md_svc_site *sites=realloc(r->sites,bytes);
    if (!sites) return -ENOMEM;
    r->sites=sites; r->capacity=capacity; return 0;
}
static int extend(struct md_svc_registry *r,pid_t pid,int memory_fd,uintptr_t svc,uint64_t number,
        struct md_svc_site *previous) {
    if (!r || r->disabled || (svc&3) || svc<4
            || (svc>=r->native_begin && svc<r->native_end)) return 0;
    for (unsigned i=0;i<r->pool_count;++i)
        if (svc-r->pools[i].address<MD_SVC_CODE_BYTES) return 0;
    /* Growth invalidates borrowed site pointers, including the chain head. */
    uintptr_t previous_gate=previous ? previous->direct.gate : 0;
    uint32_t previous_replacement=previous ? previous->replacement : 0;
    unsigned index=0;
    while (index<r->count && r->sites[index].direct.svc) ++index;
    int allocation=index==r->count ? reserve_site(r) : 0;
    if (allocation) return allocation;
    struct md_svc_site candidate={.state=MD_SVC_RETIRED,.direct.svc=svc};
    struct md_svc_site *s=&candidate;
    uint32_t original=UINT32_C(0xd4000001),current;
    if (md_svc_registry_read(pid,svc,&current,4)
            || (current!=original && (!previous_gate || current!=previous_replacement))
            || !md_svc_registry_executable(pid,svc,4)) {
        r->sites[index]=candidate;
        if (index==r->count) ++r->count;
        return 0;
    }
    size_t size=md_svc_direct_signal_size();
    unsigned char code[2048];
    if (size+8>sizeof(code)) return -E2BIG;
    for (unsigned i=0;i<r->pool_count;++i) {
        struct md_svc_code_pool *pool=&r->pools[i];
        if (pool->used+size+8>MD_SVC_CODE_BYTES) continue;
        uintptr_t gate=pool->address+pool->used,alias=gate+size;
        struct md_svc_direct_context context={.svc=svc,.gate=gate,.frames=r->frames,
            .number=number,.checkpoint_number=r->checkpoint_number,.checkpoint=r->checkpoint};
        struct md_svc_direct_fallback exits={previous_gate ? previous_gate : alias,alias,alias};
        uint32_t replacement;
        int error=md_svc_direct_signal_plan(&context,r->dispatch,exits,code,sizeof(code),&replacement);
        if (!error) error=md_svc_direct_kernel(svc,alias,(void *)(code+size));
        if (error) continue;
        error=md_svc_registry_stage(pid,memory_fd,gate,code,size+8);
        if (error) return error;
        /* Publish only after the complete resident gate is visible. The
         * caller is stopped at this exact SVC; no predecessor is rewritten. */
        error=md_svc_registry_write(pid,svc,&replacement,4);
        if (error) return error;
        s->direct=context;
        s->original=original; s->replacement=replacement; s->kernel_alias=alias;
        s->state=MD_SVC_PUBLISHED;
        r->sites[index]=candidate;
        if (index==r->count) ++r->count;
        pool->used+=(size+8+15)&~(size_t)15;
        return 1;
    }
    return -ENOSPC;
}
int md_svc_registry_direct(struct md_svc_registry *r,pid_t pid,int memory_fd,uintptr_t svc,uint64_t number) {
    if (!r || r->disabled) return 0;
    struct md_svc_site *s=head(r,svc);
    if (s) {
        for (unsigned i=0;i<r->count;++i)
            if (r->sites[i].state==MD_SVC_PUBLISHED && r->sites[i].direct.svc==svc
                    && r->sites[i].direct.number==number)
                return md_svc_registry_resume(r,pid,svc);
        uint32_t current;
        int error=md_svc_registry_read(pid,svc,&current,4);
        if (error || (current!=s->original && current!=s->replacement)) return error;
        error=extend(r,pid,memory_fd,svc,number,s);
        if (error) return error<0 ? error : 0;
        return md_svc_registry_resume(r,pid,svc);
    }
    /* Published retirement is permanent. Failed cold admission is cached only
     * until its mapping changes; repeated calls do not re-read proc maps. */
    for (unsigned i=0;i<r->count;++i) if (r->sites[i].direct.svc==svc) return 0;
    return extend(r,pid,memory_fd,svc,number,NULL);
}
int md_svc_registry_suspend(struct md_svc_registry *r,pid_t pid,uintptr_t svc) {
    struct md_svc_site *s=head(r,svc);
    if (!s) return 0;
    uint32_t current;
    int error=md_svc_registry_read(pid,svc,&current,4);
    if (!error && current==s->replacement)
        error=md_svc_registry_write(pid,svc,&s->original,4);
    return error;
}
uintptr_t md_svc_registry_replay(const struct md_svc_registry *r,uintptr_t pc) {
    if (r && !r->disabled) {
        for (unsigned i=r->count;i;--i) {
            const struct md_svc_site *s=&r->sites[i-1];
            if (s->state==MD_SVC_PUBLISHED && s->direct.svc+4==pc && s->kernel_alias)
                return s->kernel_alias;
        }
    }
    return pc-4;
}
int md_svc_registry_resume(struct md_svc_registry *r,pid_t pid,uintptr_t svc) {
    struct md_svc_site *s=head(r,svc);
    if (!s || r->disabled) return 0;
    uint32_t current;
    int error=md_svc_registry_read(pid,svc,&current,4);
    if (error || current!=s->original) return error;
    return md_svc_registry_write(pid,svc,&s->replacement,4);
}
int md_svc_registry_context(const struct md_svc_registry *r,uintptr_t pc,uintptr_t sp) {
    if (!r) return 0;
    if (sp>r->frames && sp-r->frames<=MD_SVC_POOL_SIZE) return 1;
    for (unsigned i=0;i<r->pool_count;++i)
        if (pc>=r->pools[i].address && pc-r->pools[i].address<=r->pools[i].used) return 1;
    return 0;
}
struct md_svc_site *md_svc_registry_direct_at(struct md_svc_registry *r,pid_t pid,uintptr_t pc,uintptr_t sp) {
    if (!md_svc_registry_context(r,pc,sp)) return NULL;
    uintptr_t owner=0;
    if (sp>r->frames && sp<=r->frames+MD_SVC_POOL_SIZE) {
        uintptr_t frame=(sp-1)&~(uintptr_t)(MD_SVC_SLOT_SIZE-1);
        uint64_t busy;
        if (md_svc_registry_read(pid,frame,&busy,8) || !busy
                || md_svc_registry_read(pid,frame+MD_SVC_OWNER_OFFSET,&owner,8)) owner=0;
    }
    for (unsigned i=0;i<r->count;++i) {
        struct md_svc_site *s=&r->sites[i];
        /* Retirement removes entry, not a peer's resident continuation. */
        if (s->direct.gate && (owner==s->direct.gate
                || (pc>=s->direct.gate && pc<=s->kernel_alias+4))) return s;
    }
    return NULL;
}
uintptr_t md_svc_registry_call_origin(const struct md_svc_registry *r,uintptr_t pc) {
    if (!r) return pc;
    int resident=0;
    for (unsigned i=0;i<r->pool_count;++i)
        resident|=pc-r->pools[i].address<r->pools[i].used;
    if (!resident) return pc;
    for (unsigned i=0;i<r->count;++i) {
        const struct md_svc_site *s=&r->sites[i];
        /* A retired gate can still hold a peer's interrupted syscall. Only
         * its exact post-SVC PC has the original call's identity. */
        if (s->direct.gate && pc==s->kernel_alias+4) return s->direct.svc+4;
    }
    return pc;
}
uint32_t md_svc_registry_instruction(const struct md_svc_registry *r,uintptr_t pc,uint32_t word) {
    if (r) for (unsigned i=0;i<r->count;++i) {
        const struct md_svc_site *s=&r->sites[i];
        if (s->state==MD_SVC_PUBLISHED && s->direct.gate && s->direct.svc==pc && s->replacement==word)
            return s->original;
    }
    return word;
}
int md_svc_registry_retire(struct md_svc_registry *r,struct md_svc_site *s,pid_t pid) {
    if (!r || !s || s->state==MD_SVC_RETIRED) return 0;
    uint32_t current;
    int error=md_svc_registry_read(pid,s->direct.svc,&current,4);
    if (!error && current==s->replacement)
        error=md_svc_registry_write(pid,s->direct.svc,&s->original,4);
    if (!error) s->state=MD_SVC_RETIRED;
    return error;
}
int md_svc_registry_resident(const struct md_svc_registry *r) {
    if (r) for (unsigned i=0;i<r->count;++i) if (r->sites[i].direct.gate) return 1;
    return 0;
}
static int frame_slots(const struct md_svc_registry *r,pid_t pid,uint64_t *out,int draining) {
    *out=0;
    _Static_assert(MD_SVC_SLOT_COUNT==64,"frame occupancy mask");
    struct { uint64_t busy,native; } slots[MD_SVC_SLOT_COUNT];
    struct iovec remote[MD_SVC_SLOT_COUNT*2],local={slots,sizeof(slots)};
    for (unsigned i=0;i<MD_SVC_SLOT_COUNT;++i)
        for (unsigned n=0;n<2;++n)
            remote[i*2+n]=(struct iovec){(void *)(r->frames+i*MD_SVC_SLOT_SIZE
                +(n ? MD_SVC_NATIVE_OFFSET : 0)),8};
    if (process_vm_readv(pid,&local,1,remote,MD_SVC_SLOT_COUNT*2,0)!=(ssize_t)sizeof(slots))
        for (unsigned i=0;i<MD_SVC_SLOT_COUNT*2;++i) {
            int error=md_svc_registry_read(pid,(uintptr_t)remote[i].iov_base,(char *)slots+i*8,8);
            if (error) return error;
        }
    /* A fresh claim clears the journal before reading admission. A stale
     * journal seen before that clear cannot have admitted another call after
     * revocation; a collision does not alter the active owner's journal. */
    for (unsigned i=0;i<MD_SVC_SLOT_COUNT;++i)
        if (slots[i].busy && (!draining || !(uint32_t)slots[i].native)) *out|=UINT64_C(1)<<i;
    return 0;
}
int md_svc_registry_occupied(const struct md_svc_registry *r,pid_t pid,uint64_t *out) {
    if (!out || (r && (!r->disabled || r->revoke_pending))) return -EINVAL;
    *out=0;
    if (!r || !md_svc_registry_resident(r) || r->entry_fence_drained) return 0;
    return frame_slots(r,pid,out,1);
}
int md_svc_registry_retire_pending(struct md_svc_registry *r,pid_t pid) {
    if (!r || (r->disabled && !r->revoke_pending)) return 0;
    r->disabled=1;
    r->revoke_pending=1;
    uint64_t zero=0;
    int error=md_svc_registry_write(pid,r->frames+MD_SVC_POOL_SIZE+
        offsetof(struct md_svc_payload_state,admitted),&zero,8);
    /* Internal adapters can outlive lazy admission. Once identity publication
     * stops, their shared client lookup must use the real current TID. */
    if (!error) error=md_svc_registry_write(pid,r->frames+MD_SVC_POOL_SIZE+
        offsetof(struct md_svc_payload_state,sole_owner),&zero,sizeof(zero));
    if (!error) { r->sole_owner=0; r->identity_known=0; }
    for (unsigned i=0;!error && i<r->count;++i) {
        struct md_svc_site *site=&r->sites[i];
        if (r->pause_reasons && site->state==MD_SVC_PAUSED) continue;
        int paused=r->pause_reasons && site->state==MD_SVC_PUBLISHED;
        error=md_svc_registry_retire(r,site,pid);
        if (!error && paused) site->state=MD_SVC_PAUSED;
    }
    if (!error) r->revoke_pending=0;
    return error;
}
int md_svc_registry_revoke(struct md_svc_registry *r,pid_t pid) {
    if (r && r->pause_reasons) { r->pause_reasons=0; r->revoke_pending=1; }
    return md_svc_registry_retire_pending(r,pid);
}
int md_svc_registry_pause(struct md_svc_registry *r,pid_t pid,enum md_svc_pause reason) {
    if (reason!=MD_SVC_PAUSE_FILTER && reason!=MD_SVC_PAUSE_DEBUGGER) return -EINVAL;
    if (r && (!r->disabled || r->pause_reasons)) r->pause_reasons|=reason;
    return md_svc_registry_retire_pending(r,pid);
}
int md_svc_registry_rearm(struct md_svc_registry *r,pid_t pid,enum md_svc_pause reason) {
    if (reason!=MD_SVC_PAUSE_FILTER && reason!=MD_SVC_PAUSE_DEBUGGER) return -EINVAL;
    if (!r || !(r->pause_reasons&reason) || r->revoke_pending
            || (reason==MD_SVC_PAUSE_FILTER && !r->entry_fence_drained)) return -EBUSY;
    if (r->pause_reasons&~reason) { r->pause_reasons&=~reason; return 0; }
    uint64_t one=1;
    int error=md_svc_registry_write(pid,r->frames+MD_SVC_POOL_SIZE+
        offsetof(struct md_svc_payload_state,admitted),&one,sizeof(one));
    if (!error) {
        /* The owner suspended these exact sites, not their mappings.
         * Re-admission remains lazy; unmap/protection retirement is permanent. */
        for (unsigned i=0;i<r->count;++i)
            if (r->sites[i].state==MD_SVC_PAUSED) r->sites[i].state=MD_SVC_PUBLISHED;
        r->disabled=0; r->pause_reasons=0; r->entry_fence_drained=0;
    }
    return error;
}
static int site_intersects(const struct md_svc_site *s,uintptr_t start,size_t size) {
    return s->state!=MD_SVC_RETIRED && s->direct.svc>=start && s->direct.svc-start<size;
}
int md_svc_registry_intersects(const struct md_svc_registry *r,uintptr_t start,size_t size) {
    if (r && size) for (unsigned i=0;i<r->count;++i)
        if (site_intersects(&r->sites[i],start,size)) return 1;
    return 0;
}
int md_svc_registry_deferred(const struct md_svc_registry *r,uintptr_t start,size_t size) {
    if (r && size) for (unsigned i=0;i<r->count;++i) {
        const struct md_svc_site *s=&r->sites[i];
        if (!s->direct.gate && s->direct.svc && s->direct.svc>=start && s->direct.svc-start<size) return 1;
    }
    return 0;
}
void md_svc_registry_mapping_changed(struct md_svc_registry *r,uintptr_t start,size_t size) {
    if (r && size) for (unsigned i=0;i<r->count;++i) {
        struct md_svc_site *s=&r->sites[i];
        /* Never reuse a published gate: peers may still own its continuation.
         * An unpublished failure has no such identity and can reuse its slot. */
        if (!s->direct.gate && s->direct.svc>=start && s->direct.svc-start<size) s->direct.svc=0;
    }
}
int md_svc_registry_unpatch(struct md_svc_registry *r,pid_t pid,uintptr_t start,size_t size) {
    if (!r || !size) return 0;
    for (unsigned i=0;i<r->count;++i) {
        struct md_svc_site *s=&r->sites[i];
        if (site_intersects(s,start,size)) {
            int error=md_svc_registry_retire(r,s,pid);
            if (error) return error;
        }
    }
    return 0;
}
static int client_memory(pid_t pid,int fd,uintptr_t at,void *data,size_t size,int write) {
    if (fd<0) return write ? md_svc_registry_write(pid,at,data,size)
        : md_svc_registry_read(pid,at,data,size);
    ssize_t n;
    do { n=write ? pwrite(fd,data,size,(off_t)at) : pread(fd,data,size,(off_t)at); } while (n<0 && errno==EINTR);
    /* The retained proc description cannot follow PID reuse. Zero means that
     * its mm has no remaining users, even if a terminal event is still queued. */
    return n==(ssize_t)size ? 0 : n<0 ? -errno : !n ? -ESRCH : -EIO;
}
int md_svc_registry_prepare_fork(struct md_svc_registry *r,pid_t pid,int memory_fd) {
    if (!r || !r->frames_dirty) return 0;
    if (md_svc_registry_resident(r)) {
        uint64_t slots,zero=0;
        int error=frame_slots(r,pid,&slots,0);
        if (error) return error;
        while (slots) {
            unsigned index=(unsigned)__builtin_ctzll(slots);
            uintptr_t frame=r->frames+index*MD_SVC_SLOT_SIZE;
            error=client_memory(pid,memory_fd,frame+MD_SVC_DIRECT_PENDING,&zero,sizeof(zero),1);
            if (!error) error=client_memory(pid,memory_fd,frame,&zero,sizeof(zero),1);
            if (error) return error;
            slots&=slots-1;
        }
    }
    r->frames_dirty=0;
    return 0;
}
int md_svc_registry_identity(struct md_svc_registry *r,pid_t pid,int memory_fd,pid_t owner) {
    if (!r || r->disabled || (r->identity_known && r->sole_owner==owner)) return 0;
    if (owner<0 || (owner && owner!=pid)) return -EINVAL;
    uint64_t identity=(uint64_t)owner;
    int error=client_memory(pid,memory_fd,r->frames+MD_SVC_POOL_SIZE+
        offsetof(struct md_svc_payload_state,sole_owner),&identity,sizeof(identity),1);
    if (!error) { r->sole_owner=owner; r->identity_known=1; }
    return error;
}
static int collect_client_slots(pid_t pid,int memory_fd,uintptr_t base,
        const struct md_svc_client_slot *slots,unsigned count,
        int (*alive)(pid_t,void *),void *context,unsigned *retired) {
    for (unsigned i=0;i<count;++i) {
        uint64_t owner=slots[i].tid;
        if (!owner || (owner<=INT32_MAX && alive((pid_t)owner,context))) continue;
        uintptr_t at=base+i*sizeof(*slots);
        uint64_t zero=0;
        /* Publish the empty owner last: another thread can then claim this
         * slot without inheriting the retiring thread's opaque handle. */
        for (size_t j=sizeof(*slots);j;j-=sizeof(zero)) {
            int error=client_memory(pid,memory_fd,at+j-sizeof(zero),&zero,sizeof(zero),1);
            if (error) return error;
        }
        ++*retired;
    }
    return 0;
}
int md_svc_registry_collect_clients(struct md_svc_registry *r,pid_t pid,int memory_fd,
        int (*alive)(pid_t,void *),void *context) {
    if (!r || r->disabled || !r->clients_dirty) return 0;
    uintptr_t base=r->frames+MD_SVC_POOL_SIZE;
    struct md_svc_payload_state state;
    int error=client_memory(pid,memory_fd,base,&state,sizeof(state),0);
    if (error) return error;
    unsigned retired=0;
    error=collect_client_slots(pid,memory_fd,base+offsetof(struct md_svc_payload_state,clients),
        state.clients,MD_SVC_INLINE_CLIENTS,alive,context,&retired);
    for (size_t i=0;!error && i<r->client_count;++i) {
        struct md_svc_client_block block;
        uintptr_t address=r->client_blocks[i].address;
        error=client_memory(pid,memory_fd,address,&block,sizeof(block),0);
        if (!error) error=collect_client_slots(pid,memory_fd,address+offsetof(struct md_svc_client_block,slots),
            block.slots,MD_SVC_CLIENT_BLOCK,alive,context,&retired);
    }
    if (error) return error;
    if (retired) {
        state.retired_clients+=retired;
        error=client_memory(pid,memory_fd,base+offsetof(struct md_svc_payload_state,retired_clients),
            &state.retired_clients,sizeof(state.retired_clients),1);
        if (error) return error;
    }
    r->clients_dirty=0;
    return 0;
}
long md_svc_registry_client_space(const struct md_svc_registry *r,pid_t pid,int fd,unsigned bucket) {
    if (!r || r->disabled || bucket>=MD_SVC_CLIENT_BUCKETS) return -EINVAL;
    for (size_t i=0;i<r->client_count;++i) if (r->client_blocks[i].bucket==bucket) {
        struct md_svc_client_block block;
        int error=client_memory(pid,fd,r->client_blocks[i].address,&block,sizeof(block),0);
        if (error) return error;
        for (unsigned j=0;j<MD_SVC_CLIENT_BLOCK;++j)
            if (!block.slots[j].tid || block.slots[j].tid==(uint64_t)pid) return (long)r->client_blocks[i].address;
    }
    return -EAGAIN;
}
int md_svc_registry_client_publish(struct md_svc_registry *r,pid_t pid,int fd,unsigned bucket,uintptr_t base) {
    if (!r || r->disabled || bucket>=MD_SVC_CLIENT_BUCKETS || !base
            || base%(size_t)getpagesize() || base>UINTPTR_MAX-(size_t)getpagesize()) return -EINVAL;
    if (r->client_count==r->client_capacity) {
        size_t count=r->client_capacity ? r->client_capacity*2 : MD_SVC_CLIENT_BUCKETS;
        if (count<=r->client_capacity || count>SIZE_MAX/sizeof(*r->client_blocks)) return -EOVERFLOW;
        void *blocks=realloc(r->client_blocks,count*sizeof(*r->client_blocks));
        if (!blocks) return -ENOMEM;
        r->client_blocks=blocks; r->client_capacity=count;
    }
    uintptr_t next=0;
    for (size_t i=r->client_count;i;--i)
        if (r->client_blocks[i-1].bucket==bucket) { next=r->client_blocks[i-1].address; break; }
    int error=client_memory(pid,fd,base,&next,sizeof(next),1);
    if (!error) error=client_memory(pid,fd,r->frames+MD_SVC_POOL_SIZE
        +offsetof(struct md_svc_payload_state,client_buckets)+bucket*sizeof(base),&base,sizeof(base),1);
    if (!error) r->client_blocks[r->client_count++]=(struct md_svc_client_mapping){base,bucket};
    return error;
}
struct md_svc_registry *md_svc_registry_new(uintptr_t frames,uintptr_t dispatch,uintptr_t checkpoint) {
    static uint64_t next_image;
    if (!frames || (frames&(MD_SVC_POOL_SIZE-1)) || !dispatch || (dispatch&3) || !checkpoint || (checkpoint&3)
            || frames>UINTPTR_MAX-MD_SVC_POOL_SIZE-MD_SVC_PAYLOAD_SIZE || next_image==UINT64_MAX) return NULL;
    struct md_svc_registry *r=calloc(1,sizeof(*r));
    if (r) {
        r->image=++next_image; r->frames=frames; r->dispatch=dispatch; r->checkpoint=checkpoint;
        if (reserve_site(r)) { free(r); return NULL; }
    }
    return r;
}
struct md_svc_registry *md_svc_registry_copy(const struct md_svc_registry *r) {
    if (!r) return NULL;
    struct md_svc_registry *copy=malloc(sizeof(*copy));
    if (copy) {
        *copy=*r; copy->sites=NULL; copy->capacity=r->count;
        copy->pools=NULL; copy->pool_capacity=r->pool_count;
        copy->client_blocks=NULL; copy->client_capacity=r->client_count;
        if (r->pool_count) {
            copy->pools=malloc(r->pool_count*sizeof(*r->pools));
            if (!copy->pools) { free(copy); return NULL; }
            memcpy(copy->pools,r->pools,r->pool_count*sizeof(*r->pools));
        }
        if (r->client_count) {
            copy->client_blocks=malloc(r->client_count*sizeof(*r->client_blocks));
            if (!copy->client_blocks) { free(copy->pools); free(copy); return NULL; }
            memcpy(copy->client_blocks,r->client_blocks,r->client_count*sizeof(*r->client_blocks));
        }
        if (r->count) {
            copy->sites=malloc(r->count*sizeof(*r->sites));
            if (!copy->sites) { free(copy->pools); free(copy->client_blocks); free(copy); return NULL; }
            memcpy(copy->sites,r->sites,r->count*sizeof(*r->sites));
        }
        copy->clients_dirty=1; copy->frames_dirty=1; copy->identity_known=0;
    }
    return copy;
}
void md_svc_registry_free(struct md_svc_registry *r) {
    if (r) { free(r->pools); free(r->client_blocks); free(r->sites); free(r); }
}
int md_svc_registry_pool_reserve(struct md_svc_registry *r) {
    if (!r) return -EINVAL;
    if (r->pool_count<r->pool_capacity) return 0;
    if (r->pool_capacity>UINT_MAX/2) return -EOVERFLOW;
    unsigned capacity=r->pool_capacity ? r->pool_capacity*2 : 4;
    size_t bytes;
    if (__builtin_mul_overflow((size_t)capacity,sizeof(*r->pools),&bytes)) return -EOVERFLOW;
    struct md_svc_code_pool *pools=realloc(r->pools,bytes);
    if (!pools) return -ENOMEM;
    r->pools=pools; r->pool_capacity=capacity; return 0;
}
int md_svc_registry_pool(struct md_svc_registry *r,uintptr_t address) {
    if (!r || !address || (address&65535) || address>UINTPTR_MAX-MD_SVC_CODE_BYTES) return -EINVAL;
    for (unsigned i=0;i<r->pool_count;++i)
        if (r->pools[i].address==address) return -EEXIST;
    int error=md_svc_registry_pool_reserve(r);
    if (error) return error;
    r->pools[r->pool_count++]=(struct md_svc_code_pool){.address=address}; return 0;
}
int md_svc_registry_pool_candidate(const struct md_svc_registry *r,pid_t pid,uintptr_t svc,uintptr_t after,uintptr_t *out) {
    if (!svc || !out || svc>UINTPTR_MAX-0x8000000) return -EINVAL;
    /* Leave one pool of margin for both the entry and return branches. The
     * monotonically advancing cursor bounds races with native mmap callers. */
    uintptr_t cursor=svc>0x8000000 ? svc-0x8000000+MD_SVC_CODE_BYTES : MD_SVC_CODE_BYTES;
    uintptr_t end=svc+0x8000000-MD_SVC_CODE_BYTES;
    /* A generic syscall wrapper can acquire another number after its first
     * pool fills. Its mismatch branch must also reach the preceding gate. */
    if (r) for (unsigned i=r->count;i;--i) {
        const struct md_svc_site *site=&r->sites[i-1];
        if (site->state!=MD_SVC_PUBLISHED || site->direct.svc!=svc) continue;
        uintptr_t gate=site->direct.gate;
        uintptr_t first=gate>0x8000000 ? gate-0x8000000+MD_SVC_CODE_BYTES : MD_SVC_CODE_BYTES;
        uintptr_t last=gate>UINTPTR_MAX-0x8000000 ? UINTPTR_MAX-MD_SVC_CODE_BYTES
            : gate+0x8000000-MD_SVC_CODE_BYTES;
        if (first>cursor) cursor=first;
        if (last<end) end=last;
        break;
    }
    if (after>cursor) cursor=after;
    if (cursor>end) return -ENOSPC;
    cursor=(cursor+MD_SVC_CODE_BYTES-1)&~(uintptr_t)(MD_SVC_CODE_BYTES-1);
    char path[64]; snprintf(path,sizeof(path),"/proc/%d/maps",pid);
    int fd=open(path,O_RDONLY|O_CLOEXEC); if (fd<0) return -errno;
    struct md_mapping_reader reader={.fd=fd}; struct md_mapping_record v;
    int result;
    while ((result=md_mapping_next(&reader,&v))>0 && cursor<=end) {
        if (v.end<=cursor) continue;
        if (v.first>=cursor+MD_SVC_CODE_BYTES) break;
        if (v.end>end) { cursor=end+1; break; }
        cursor=(v.end+MD_SVC_CODE_BYTES-1)&~(uintptr_t)(MD_SVC_CODE_BYTES-1);
    }
    close(fd);
    if (result<0) return result;
    if (cursor>end) return -ENOSPC;
    *out=cursor; return 0;
}
