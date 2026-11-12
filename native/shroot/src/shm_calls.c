#define _GNU_SOURCE
#include "sysv_shm.h"
#include "interception.h"
#include "adapter_buffer.h"
#include "resource_control.h"
#include "raw.h"
#include <errno.h>
#include <limits.h>

static long request(unsigned op, unsigned long a, unsigned long b, unsigned long c) {
    return md_guest_resource_control(MD_GUEST_SHM,op,a,b,c,0);
}
long md_shm_dispatch(long nr, const unsigned long *a) {
    if (nr == SYS_shmget) return request(MD_SHM_GET,a[0],a[1],a[2]);
    if (nr == SYS_shmat) {
        unsigned flags = (unsigned)a[2]; uintptr_t address = a[1];
        if (flags & ~(SHM_RDONLY|SHM_RND|SHM_EXEC)) return -ENOTSUP;
        if (flags & SHM_RND) address &= ~(md_page_size-1);
        if (address & (md_page_size-1)) return -EINVAL;
        long memory=md_adapter_buffer_take();
        if (memory<0) return memory;
        long mapped=md_guest_buffer_control(MD_BUFFER_MEMORY_MAP,0,0);
        long released=md_adapter_buffer_release();
        return mapped<0 ? mapped : released<0 ? released : mapped;
    }
    if (nr == SYS_shmdt) {
        int removed = 0;
        for (;;) {
            long address = request(MD_SHM_DETACH_ADDRESS,a[0],0,0);
            if (address < 0) return removed && address == -EINVAL ? 0 : address;
            long size = request(MD_SHM_DETACH_SIZE,address,0,0);
            if (size < 0) return size;
            long r = RAW2(munmap,address,size);
            if (!r) r = request(MD_SHM_DETACHED,address,0,0);
            if (r) return r;
            removed = 1;
        }
    }
    unsigned command = (unsigned)a[1] & ~IPC_64;
    if (command == IPC_RMID) return request(MD_SHM_REMOVE,a[0],0,0);
    struct shmid64_ds info;
    if (command == IPC_SET) {
        long r = md_read_memory(&info,(void *)a[2],sizeof(info));
        return r < 0 ? r : request(MD_SHM_SET,a[0],info.shm_perm.uid|((uint64_t)info.shm_perm.gid<<32),info.shm_perm.mode);
    }
    if (command != IPC_STAT) return -ENOTSUP;
    struct md_adapter_input input=md_adapter_buffer_begin(MD_BUFFER_SHM_STAT,a[0]);
    if (!input.buffer) return input.value;
    long result=input.value;
    if (!result) result=md_write_memory((void *)a[2],input.buffer,sizeof(info));
    long released=md_adapter_buffer_release();
    return result ? result : released;
}
