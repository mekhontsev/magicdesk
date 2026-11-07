#define _GNU_SOURCE
#include "fd_mutation.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/close_range.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>

static int descriptor_flags(long nr,const unsigned long *args,unsigned *flags) {
    if (nr==SYS_fcntl && (unsigned)args[1]==F_SETFD) *flags=args[2]&FD_CLOEXEC;
    else if (nr==SYS_ioctl && (unsigned)args[1]==FIOCLEX) *flags=FD_CLOEXEC;
    else if (nr==SYS_ioctl && (unsigned)args[1]==FIONCLEX) *flags=0;
    else return 0;
    return 1;
}
int md_fd_mutation_loan(const struct md_fd_order *turn,long nr,const unsigned long a[6],
        int *number,struct md_fd_loan **loan) {
    if (!turn || !turn->table || !a || !number || !loan || *loan) return -EINVAL;
    if (md_fd_order_state(turn)!=MD_RESOURCE_ORDER_RUNNING) return -EBUSY;
    *number=-1;
    if (!md_fd_origins_has_loans(turn->table)) return 0;
    unsigned first,last,flags;
    if (nr==SYS_close) {
        if ((int)a[0]<0) return 0;
        first=last=(unsigned)(int)a[0];
    } else if (nr==SYS_dup3) {
        if ((int)a[0]<0 || (int)a[1]<0 || (int)a[0]==(int)a[1] || ((unsigned)a[2]&~O_CLOEXEC)) return 0;
        first=last=(unsigned)(int)a[1];
    } else if (nr==SYS_close_range) {
        if ((unsigned)a[0]>(unsigned)a[1] || (unsigned)a[2]) return 0;
        first=(unsigned)a[0]; last=(unsigned)a[1];
    } else if (descriptor_flags(nr,a,&flags)) {
        if ((int)a[0]<0 || flags) return 0;
        first=last=(unsigned)(int)a[0];
    } else return 0;
    return md_fd_origins_loan_range(turn,first,last,number,loan);
}
int md_fd_mutation_prepare(struct md_fd_origins *files, long nr,
        const unsigned long a[6], struct md_fd_mutation *out) {
    *out=(struct md_fd_mutation){.nr=nr,.args={a[0],a[1],a[2]}};
    unsigned descriptor;
    if (descriptor_flags(nr,a,&descriptor)) {
        /* Normalize metadata only: the original syscall still runs in the
         * caller, preserving ioctl validation and application seccomp policy. */
        out->nr=SYS_fcntl; out->args[1]=F_SETFD; out->args[2]=descriptor;
        return 0;
    }
    unsigned command=(unsigned)a[1];
    if (nr==SYS_fcntl) out->args[1]=command;
    int duplicate=nr==SYS_dup || nr==SYS_dup3
        || (nr==SYS_fcntl && (command==F_DUPFD || command==F_DUPFD_CLOEXEC));
    if (!duplicate) return 0;
    unsigned flags=(nr==SYS_dup3 && (a[2]&O_CLOEXEC))
        || (nr==SYS_fcntl && command==F_DUPFD_CLOEXEC) ? FD_CLOEXEC : 0;
    return md_fd_origins_prepare_copy(files,(int)a[0],flags,&out->update);
}
int md_fd_mutation_finish_closed(struct md_fd_mutation *op,struct md_fd_origins *files,long result,struct md_fd_closed *closed) {
    int error=0;
    if (op->update.table) md_fd_origins_finish_closed(&op->update,result<0 ? -1 : (int)result,
        op->nr==SYS_dup3 ? closed : NULL);
    else if (op->nr==SYS_close && (!result || result==-EINTR || result==-EIO || result==-ENOSPC || result==-EDQUOT)) {
        /* Linux releases a valid slot before reporting close's late IO errors. */
        if ((int)op->args[0]>=0)
            error=md_fd_origins_close_collect(files,(unsigned)op->args[0],(unsigned)op->args[0],0,closed);
    } else if (!result && op->nr==SYS_close_range)
        error=md_fd_origins_close_collect(files,(unsigned)op->args[0],(unsigned)op->args[1],
            !!(op->args[2]&CLOSE_RANGE_CLOEXEC),closed);
    else if (!result && op->nr==SYS_fcntl && op->args[1]==F_SETFD)
        error=md_fd_origins_set_flags(files,(int)op->args[0],(unsigned)op->args[2]&FD_CLOEXEC);
    *op=(struct md_fd_mutation){0}; return error;
}
int md_fd_mutation_finish(struct md_fd_mutation *op,struct md_fd_origins *files,long result) {
    return md_fd_mutation_finish_closed(op,files,result,NULL);
}
void md_fd_mutation_discard(struct md_fd_mutation *op) {
    md_fd_origins_finish(&op->update,-1);
    *op=(struct md_fd_mutation){0};
}
int md_fd_mutation_terminal_open(const struct md_fd_mutation *op, unsigned long value, long *result) {
    if (!op || !result || op->nr!=SYS_openat || (int)op->args[0]!=AT_FDCWD
            || !(op->args[2]&O_PATH) || !op->update.table) return 0;
    if (value==(unsigned long)(unsigned)AT_FDCWD) *result=-ECANCELED;
    else if (value<=INT_MAX || value>=(unsigned long)-4095L) *result=(long)value;
    else return 0;
    return 1;
}
int md_fd_mutation_abandon(struct md_fd_mutation *op, struct md_fd_origins *files) {
    int error=0;
    if (op->nr==SYS_close || (op->nr==SYS_fcntl && op->args[1]==F_SETFD)) {
        if ((int)op->args[0]>=0)
            error=md_fd_origins_abandon_range(files,(unsigned)op->args[0],(unsigned)op->args[0]);
    } else if (op->nr==SYS_dup3 && (int)op->args[1]>=0)
        error=md_fd_origins_abandon_range(files,(unsigned)op->args[1],(unsigned)op->args[1]);
    else if (op->nr==SYS_close_range && (unsigned)op->args[0]<=(unsigned)op->args[1])
        error=md_fd_origins_abandon_range(files,(unsigned)op->args[0],(unsigned)op->args[1]);
    md_fd_origins_abandon(&op->update);
    md_fd_mutation_discard(op); return error;
}
