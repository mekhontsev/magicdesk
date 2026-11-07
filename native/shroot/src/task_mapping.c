#define _GNU_SOURCE
#include "task_mapping.h"
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>

int md_task_mapping_safe(int source) {
    int flags=fcntl(source,F_GETFL);
    struct stat st;
    if (flags<0 || (flags&O_PATH) || fstat(source,&st) || !S_ISREG(st.st_mode)) return 0;
    if ((flags&O_ACCMODE)==O_RDONLY) return 1;
    if ((flags&O_ACCMODE)!=O_RDWR) return 0;
    int seals=fcntl(source,F_GET_SEALS);
    return seals>=0 && (seals&(F_SEAL_GROW|F_SEAL_SHRINK))==(F_SEAL_GROW|F_SEAL_SHRINK)
        && (seals&(F_SEAL_WRITE|F_SEAL_FUTURE_WRITE));
}
int md_task_mapping_begin(struct md_task_mapping *m,long (*trace)(int,pid_t,void *,void *),
        pid_t actor,const struct user_pt_regs *saved,const struct md_interception_abi *abi,
        const struct md_task_mapping_request *request) {
    if (!m || !request || !abi || m->stage) return -EINVAL;
    *m=(struct md_task_mapping){.request=*request,.descriptor=-1,.stage=MD_TASK_MAPPING_INSTALL};
    struct md_task_call_arguments args={.number=SYS_gettid,.value={MD_GUEST_TASK_MAPPING}};
    return md_task_call_begin(&m->call,trace,actor,MD_TASK_CALL_USER,saved,
        abi->task_call,abi->task_return,&args);
}
int md_task_mapping_notification(const struct md_task_mapping *m,const struct seccomp_notif *q) {
    return m && m->stage==MD_TASK_MAPPING_INSTALL && m->descriptor<0
        && md_task_call_notification(&m->call,q);
}
int md_task_mapping_event(struct md_task_mapping *m,int status) {
    int result=md_task_call_event(&m->call,status);
    if (result<=0) return result;
    if (m->stage==MD_TASK_MAPPING_INSTALL && m->call.result>=0) {
        if (m->call.result!=m->descriptor) return -EPROTO;
        struct md_task_mapping_request *r=&m->request;
        struct md_task_call_arguments args={.number=SYS_mmap,
            .value={r->address,r->size,r->protection,r->flags,(unsigned)m->descriptor,r->offset}};
        m->stage=MD_TASK_MAPPING_MAP;
        return md_task_call_again(&m->call,&args);
    }
    m->result=m->call.result; m->stage=MD_TASK_MAPPING_DONE;
    return 1;
}
