#include "sysv_ipc.h"
#include "interception.h"
#include "raw.h"
#include <errno.h>
#include <sys/mman.h>

long md_ipc_dispatch(long nr, const unsigned long *args) {
    long fd=RAW2(prctl,MD_GUEST_IPC,MD_IPC_OPEN);
    if (fd<0) {
        long close=RAW3(prctl,MD_GUEST_IPC,MD_IPC_CLOSE,1);
        if (close>0) RAW1(close,close-1);
        return fd;
    }
    struct md_ipc_packet header;
    long result=RAW4(pread64,fd,&header,sizeof(header),0);
    long memory=-1;
    if (result!=sizeof(header)) result=result<0 ? result : -EIO;
    else {
        memory=RAW6(mmap,0,header.length,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);
        result=memory<0 ? memory : 0;
    }
    if (!result) {
        struct md_ipc_packet *packet=(void *)memory;
        uintptr_t address=nr==SYS_semctl ? args[3] : nr==SYS_msgctl ? args[2] : args[1];
        size_t first=header.timeout_offset ? header.timeout_offset : header.input_size;
        if (first) result=md_read_memory(packet->data,(void *)address,first);
        if (!result && header.timeout_offset)
            result=md_read_memory(packet->data+first,(void *)args[3],sizeof(struct timespec));
        if (!result) result=RAW2(prctl,MD_GUEST_IPC,MD_IPC_EXECUTE);
        if (result>=0 && packet->output_size) {
            long copy=md_write_memory((void *)address,packet->data,packet->output_size);
            if (copy) result=copy;
        }
    }
    long close=RAW3(prctl,MD_GUEST_IPC,MD_IPC_CLOSE,result!=MD_IPC_WAIT);
    if (close>0) RAW1(close,close-1);
    else if (close<0) result=close;
    if (memory>=0) RAW2(munmap,memory,header.length);
    RAW1(close,fd);
    return result;
}
