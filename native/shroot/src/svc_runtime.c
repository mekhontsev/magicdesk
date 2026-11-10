#include "svc_runtime.h"
#include "svc_payload.h"
#include "raw.h"
#include <asm/hwcap.h>
#include <stddef.h>
#include <sys/mman.h>

struct md_svc_runtime md_svc_runtime;
static uintptr_t code_pool(uintptr_t entry) {
    static const long offsets[]={0x4000000,-0x4000000,0x2000000,-0x2000000,0x1000000,-0x1000000};
    for (unsigned i=0;i<sizeof(offsets)/sizeof(*offsets);++i) {
        uintptr_t address=(entry+offsets[i])&~(uintptr_t)(MD_SVC_CODE_BYTES-1);
        if (address<MD_SVC_CODE_BYTES) continue;
        long result=RAW6(mmap,address,MD_SVC_CODE_BYTES,PROT_READ|PROT_EXEC,
            MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE,-1,0);
        if (result==(long)address) return address;
        if (result>=0) RAW2(munmap,result,MD_SVC_CODE_BYTES);
    }
    return 0;
}
void md_svc_runtime_prepare(uintptr_t program,uintptr_t loader,unsigned long capabilities,int statistics) {
    if (!(capabilities&HWCAP_ATOMICS) || md_page_size>MD_SVC_GUARD_SIZE) return;
    size_t length=2*MD_SVC_POOL_SIZE+MD_SVC_PAYLOAD_SIZE;
    long allocation=RAW6(mmap,0,length,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    if (allocation<0) return;
    uintptr_t frames=((uintptr_t)allocation+MD_SVC_POOL_SIZE-1)&~(uintptr_t)(MD_SVC_POOL_SIZE-1);
    uintptr_t first=code_pool(program),second=0;
    if (loader && (loader>program ? loader-program : program-loader)>0x2000000) second=code_pool(loader);
    if (!first && !second) { RAW2(munmap,allocation,length); return; }
    if (frames>(uintptr_t)allocation) RAW2(munmap,allocation,frames-(uintptr_t)allocation);
    uintptr_t end=frames+MD_SVC_POOL_SIZE+MD_SVC_PAYLOAD_SIZE;
    RAW2(munmap,end,(uintptr_t)allocation+length-end);
    struct md_svc_payload_state *state=(void *)(frames+MD_SVC_POOL_SIZE);
    state->api=&md_cooperative_entry; state->admitted=1;
    state->statistics=!!statistics;
    md_svc_runtime=(struct md_svc_runtime){.frames=frames,.dispatch=(uintptr_t)md_svc_payload_dispatch};
    if (first) md_svc_runtime.pools[md_svc_runtime.count++]=first;
    if (second) md_svc_runtime.pools[md_svc_runtime.count++]=second;
}
