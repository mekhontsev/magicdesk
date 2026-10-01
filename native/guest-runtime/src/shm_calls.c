#define _GNU_SOURCE
#include "sysv_shm.h"
#include "interception.h"
#include "raw.h"
#include <errno.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

static long observe_mappings(void) {
    long r = RAW2(prctl,MD_GUEST_SHM_FILTER,0);
    if (r) return r < 0 ? r : 0;
    uintptr_t gate = (uintptr_t)md_raw_return;
    struct sock_filter code[] = {
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)(gate>>32),0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)gate,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_munmap,5,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mremap,4,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mmap,0,2),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[3])),
        BPF_JUMP(BPF_JMP|BPF_JSET|BPF_K,MAP_FIXED,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_MEMORY)
    };
    struct sock_fprog program = {sizeof(code)/sizeof(*code),code};
    r = RAW3(seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_TSYNC,&program);
    if (r > 0) return -ENOTSUP;
    return r ? r : RAW2(prctl,MD_GUEST_SHM_FILTER,1);
}

static long request(unsigned op, unsigned long a, unsigned long b, unsigned long c) {
    return RAW5(prctl,MD_GUEST_SHM,op,a,b,c);
}
long md_shm_dispatch(long nr, const unsigned long *a) {
    if (nr == SYS_shmget) return request(MD_SHM_GET,a[0],a[1],a[2]);
    if (nr == SYS_shmat) {
        unsigned flags = (unsigned)a[2]; uintptr_t address = a[1];
        if (flags & ~(SHM_RDONLY|SHM_RND|SHM_EXEC)) return -ENOTSUP;
        if (flags & SHM_RND) address &= ~(md_page_size-1);
        if (address & (md_page_size-1)) return -EINVAL;
        long activated = observe_mappings();
        if (activated < 0) return activated;
        long fd = request(MD_SHM_OPEN,a[0],flags,0);
        if (fd < 0) return fd;
        struct stat info;
        long r = RAW2(fstat,fd,&info);
        if (!r) r = RAW6(mmap,address,info.st_size,PROT_READ|((flags&SHM_RDONLY)?0:PROT_WRITE)
            |((flags&SHM_EXEC)?PROT_EXEC:0),MAP_SHARED|(address?MAP_FIXED_NOREPLACE:0),fd,0);
        RAW1(close,fd);
        if (r < 0) { request(MD_SHM_ABORT,0,0,0); return r; }
        if (address && (uintptr_t)r != address) {
            RAW2(munmap,r,info.st_size); request(MD_SHM_ABORT,0,0,0); return -EINVAL;
        }
        long committed = request(MD_SHM_ATTACHED,(unsigned long)r,0,0);
        if (committed < 0) { RAW2(munmap,r,info.st_size); request(MD_SHM_ABORT,0,0,0); return committed; }
        return r;
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
    long fd = request(MD_SHM_STAT,a[0],0,0);
    if (fd < 0) return fd;
    long size = RAW3(read,fd,&info,sizeof(info));
    RAW1(close,fd);
    return size < 0 ? size : size != sizeof(info) ? -EIO : md_write_memory((void *)a[2],&info,sizeof(info));
}
