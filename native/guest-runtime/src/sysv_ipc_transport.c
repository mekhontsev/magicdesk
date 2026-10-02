#define _GNU_SOURCE
#include "sysv_ipc_internal.h"
#include <errno.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

/* Only bounded argument/result buffers cross this channel. The calling task
 * copies its own memory, including after PR_SET_DUMPABLE(0). No SQLite lock
 * survives a guest copy or wait. Addresses in the authority are packet offsets. */
int md_ipc_transport_open(struct md_ipc *s, struct md_ipc_transport *t,
        const struct md_ipc_request *call, int retry) {
    *t=(struct md_ipc_transport){.fd=-1,.call=*call};
    long nr=call->nr;
    const unsigned long *a=call->args;
    size_t input=0, output=0, timeout=0, base=offsetof(struct md_ipc_packet,data);
    if (nr==SYS_semop || nr==SYS_semtimedop) {
        if (!a[2]) return -EINVAL;
        if (a[2]>SEMOPM) return -E2BIG;
        input=a[2]*sizeof(struct sembuf); t->call.args[1]=base;
        if (nr==SYS_semtimedop && a[3]) {
            timeout=input; t->call.args[3]=base+timeout; input+=sizeof(struct timespec);
        }
    } else if (nr==SYS_semctl) {
        unsigned command=(unsigned)a[2]&~IPC_64;
        if (command==IPC_SET) input=sizeof(struct semid64_ds);
        if (command==IPC_STAT) output=sizeof(struct semid64_ds);
        if (command==SETALL || command==GETALL) {
            struct semid64_ds st;
            int r=md_ipc_begin(s);
            if (!r) r=md_ipc_finish(s,md_ipc_load(s,MD_IPC_SEM,a[0],&st,sizeof(st)));
            if (r) return r;
            if (st.sem_nsems>SEMMSL) return -EIO;
            if (command==SETALL) input=st.sem_nsems*sizeof(unsigned short);
            else output=st.sem_nsems*sizeof(unsigned short);
        }
        if (input || output) t->call.args[3]=base;
    } else if (nr==SYS_msgctl) {
        unsigned command=(unsigned)a[1]&~IPC_64;
        if (command==IPC_SET) input=sizeof(struct msqid64_ds);
        if (command==IPC_STAT) output=sizeof(struct msqid64_ds);
        if (input || output) t->call.args[2]=base;
    } else if (nr==SYS_msgsnd) {
        if (a[2]>MD_MSGMAX) return -EINVAL;
        input=sizeof(long)+a[2]; t->call.args[1]=base;
    } else if (nr==SYS_msgrcv) {
        if ((long)a[2]<0) return -EINVAL;
        output=sizeof(long)+(a[2]>MD_MSGMAX ? MD_MSGMAX : a[2]); t->call.args[1]=base;
    } else if (nr!=SYS_semget && nr!=SYS_msgget) return -EINVAL;
    t->capacity=input>output ? input : output; t->length=base+t->capacity;
    t->fd=memfd_create("guest-ipc",MFD_CLOEXEC);
    if (t->fd<0) return -errno;
    int r=ftruncate(t->fd,t->length) ? -errno : 0;
    if (!r) {
        void *memory=mmap(NULL,t->length,PROT_READ|PROT_WRITE,MAP_SHARED,t->fd,0);
        if (memory==MAP_FAILED) r=-errno; else t->packet=memory;
    }
    if (!r) *t->packet=(struct md_ipc_packet){.length=t->length,.input_size=retry ? 0 : input,
        .timeout_offset=retry ? 0 : timeout};
    else md_ipc_transport_close(t);
    return r;
}
void md_ipc_transport_close(struct md_ipc_transport *t) {
    if (t->packet) munmap(t->packet,t->length);
    if (t->fd>=0) close(t->fd);
    *t=(struct md_ipc_transport){.fd=-1};
}
