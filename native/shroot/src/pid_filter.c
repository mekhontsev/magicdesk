#include "interception.h"
#include "pid_filter_program.h"
#include "raw.h"
#include <errno.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>

extern char md_guest_task_return[];
/* Installed only when a launch first uses a PID namespace. Runtime and task
 * gates retain native IDs for owned proc access and transport operations. */
long md_pid_filter_install(void) {
    struct md_pid_filter_program code;
    md_pid_filter_build((uintptr_t)md_raw_return,(uintptr_t)md_guest_task_return,&code);
    struct sock_fprog program={code.count,code.code};
    long result=RAW3(seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_TSYNC,&program);
    if (result>0) return -ENOTSUP;
    return result ? result : RAW2(prctl,MD_GUEST_PID_FILTER,1);
}
