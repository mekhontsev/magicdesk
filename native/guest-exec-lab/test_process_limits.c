#define _GNU_SOURCE
#include "process_limits.h"
#include "process_oom.h"
#include "task_status.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <sched.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static struct md_process_member *member(const struct md_identity *id,const struct rlimit *nproc) {
    struct md_process_limits limits;
    assert(!md_process_limits_read(&limits));
    limits.nproc=*nproc;
    return md_process_member_new(id,&limits);
}
static int nproc(struct md_process_member *m,const struct md_identity *actor,
        const struct md_identity *target,int group,const struct rlimit *next,struct rlimit *old) {
    return md_process_limit(m,actor,target,group,RLIMIT_NPROC,next,old,0);
}
static void nofile(struct md_identity *root,struct md_identity *user) {
    struct md_process_limits initial;
    assert(!md_process_limits_read(&initial));
    struct md_process_member *p=md_process_member_new(root,&initial), *thread=NULL,*child=NULL;
    assert(p && !md_process_member_fork(p,root,CLONE_THREAD,&thread));
    assert(!md_process_member_fork(p,root,0,&child));
    assert(md_process_native_nofile(p)==initial.nofile.rlim_cur);
    struct rlimit small={64,64}, old, native, large={MD_PROCESS_NOFILE_MAX,MD_PROCESS_NOFILE_MAX};
    assert(!md_process_limit(thread,root,root,1,RLIMIT_NOFILE,&small,&old,0));
    assert(old.rlim_cur==initial.nofile.rlim_cur);
    assert(md_process_limits_value(p).nofile.rlim_max==64);
    assert(md_process_limits_value(child).nofile.rlim_max==initial.nofile.rlim_max);
    assert(md_process_native_nofile(p)==64 && md_process_native_nofile(thread)==64);
    assert(md_process_native_nofile(child)==initial.nofile.rlim_cur);
    assert(!getrlimit(RLIMIT_NOFILE,&native) && native.rlim_cur==64 && native.rlim_max==initial.nofile.rlim_max);
    assert(md_process_limit(p,user,user,1,RLIMIT_NOFILE,&large,NULL,0)==-EPERM);
    assert(md_process_limit(p,root,root,1,RLIMIT_NOFILE,&large,NULL,INT_MAX)==-ESRCH);
    assert(md_process_limits_value(p).nofile.rlim_cur==64);
    assert(md_process_native_nofile(p)==64);
    assert(!md_process_limit(p,root,root,1,RLIMIT_NOFILE,&large,&old,0) && old.rlim_max==64);
    assert(!getrlimit(RLIMIT_NOFILE,&native) && native.rlim_cur==native.rlim_max);
    assert(md_process_native_nofile(p)==native.rlim_cur && md_process_native_nofile(thread)==native.rlim_cur);
    large=(struct rlimit){RLIM_INFINITY,RLIM_INFINITY};
    assert(md_process_limit(p,root,root,1,RLIMIT_NOFILE,&large,NULL,0)==-EPERM);
    struct rlimit zero={0,0};
    assert(!md_process_limit(p,root,root,1,RLIMIT_NOFILE,&zero,NULL,0));
    assert(md_process_nofile_reserve(p,INT_MAX,2)==-ESRCH);
    assert(!md_process_nofile_reserve(thread,0,2));
    assert(md_process_nofile_reserve(p,0,2)==-EBUSY);
    assert(md_process_native_nofile(p)==0 && !md_process_limits_value(thread).nofile.rlim_max);
    assert(!getrlimit(RLIMIT_NOFILE,&native) && native.rlim_cur==2);
    small=(struct rlimit){1,1};
    assert(!md_process_limit(p,root,root,1,RLIMIT_NOFILE,&small,NULL,0));
    assert(!getrlimit(RLIMIT_NOFILE,&native) && native.rlim_cur==2);
    assert(md_process_nofile_release(p,INT_MAX)==-ESRCH);
    assert(!md_process_nofile_release(thread,0));
    assert(!getrlimit(RLIMIT_NOFILE,&native) && native.rlim_cur==1);
    assert(md_process_native_nofile(p)==1);
    assert(md_process_nofile_release(p,0)==-EINVAL);
    assert(!md_process_limit(p,root,root,1,RLIMIT_NOFILE,&initial.nofile,NULL,0));
    md_process_member_release(thread); md_process_member_release(child); md_process_member_release(p);
    puts("PASS nofile policy: kernel enforcement, reversible hard limit, rollback, sharing and copy");
}
static void native_limits(struct md_identity *root,struct md_identity *user) {
    struct md_process_limits initial;
    assert(!md_process_limits_read(&initial));
    struct md_process_member *m=md_process_member_new(root,&initial); assert(m);
    int ready[2],release[2]; assert(!pipe(ready) && !pipe(release));
    struct rlimit expected[RLIM_NLIMITS];
    for (unsigned resource=0;resource<RLIM_NLIMITS;++resource)
        assert(!getrlimit(resource,&expected[resource]));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_DUMPABLE,0));
        assert(write(ready[1],"r",1)==1);
        char byte; assert(read(release[0],&byte,1)==1); _exit(0);
    }
    char byte; assert(read(ready[0],&byte,1)==1);
    for (unsigned resource=0;resource<RLIM_NLIMITS;++resource) {
        struct rlimit actual;
        long result=md_task_limit(child,resource,&actual);
        /* Native inspection has the host's permission ceiling. The guest
         * integration test requires success through task-affine capture too. */
        assert(!result || result==-EACCES || result==-EPERM);
        if (!result) assert(actual.rlim_cur==expected[resource].rlim_cur && actual.rlim_max==expected[resource].rlim_max);
        int logical=resource==RLIMIT_NPROC || resource==RLIMIT_NOFILE;
        assert(md_process_limit(m,root,root,0,resource,NULL,&actual,child)==(logical ? 0 : result));
        if (!result || logical) assert(actual.rlim_cur==expected[resource].rlim_cur && actual.rlim_max==expected[resource].rlim_max);
        assert(md_process_limit(m,user,root,0,resource,NULL,&actual,child)==-EPERM);
    }
    struct rlimit value;
    assert(md_task_limit(child,RLIM_NLIMITS,&value)==-EINVAL);
    assert(md_process_limit(m,root,root,0,RLIM_NLIMITS,NULL,&value,child)==-EINVAL);
    assert(write(release[1],"r",1)==1);
    int status; assert(waitpid(child,&status,0)==child && !status);
    assert(md_process_limit(m,root,root,0,RLIMIT_SIGPENDING,NULL,&value,child)==-ESRCH);
    close(ready[0]); close(ready[1]); close(release[0]); close(release[1]);
    md_process_member_release(m);
    puts("PASS native limits: host admission, all resources, guest permissions and disappearance");
}
static void proc_limits(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct rlimit expected[RLIM_NLIMITS];
        for (unsigned i=0;i<RLIM_NLIMITS;++i) assert(!getrlimit(i,&expected[i]));
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_prlimit64,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog filter={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&filter));
        for (unsigned i=0;i<RLIM_NLIMITS;++i) {
            struct rlimit actual;
            assert(!md_task_limit(getpid(),i,&actual));
            assert(actual.rlim_cur==expected[i].rlim_cur && actual.rlim_max==expected[i].rlim_max);
        }
        _exit(0);
    }
    int status; assert(waitpid(child,&status,0)==child && !status);
    puts("PASS limits proc fallback: all soft/hard values with native prlimit denied");
}

static void oom(struct md_identity *root,struct md_identity *user) {
    int fd=memfd_create("oom-policy",MFD_CLOEXEC); assert(fd>=0);
    assert(write(fd,"200\n",4)==4);
    struct md_process_member *p=member(root,&(struct rlimit){16,16}),*thread=NULL,*child=NULL;
    assert(p && !md_process_member_fork(p,root,CLONE_THREAD,&thread));
    struct md_process_oom *state=md_process_member_oom(p);
    assert(state==md_process_member_oom(thread));
    char data[64]={0};
    assert(md_process_oom_io(state,root,0,0,fd,-1,0,data,sizeof(data),0)==4 && !strcmp(data,"200\n"));
    assert(md_process_oom_io(state,root,0,1,fd,-1,-EIO,"-900",4,0)==-EIO);
    assert(!state->virtualized && state->value==200);
    assert(md_process_oom_io(state,root,0,1,fd,-1,-EACCES,"-900",4,0)==4);
    assert(state->virtualized && state->value==-900 && state->minimum==-900);
    assert(pread(fd,data,sizeof(data),0)==4 && !memcmp(data,"200\n",4));
    assert(!md_process_member_fork(p,root,0,&child));
    struct md_process_oom *copy=md_process_member_oom(child);
    assert(copy!=state && !memcmp(copy,state,sizeof(*copy)));
    assert(md_process_oom_io(copy,user,0,1,fd,-1,0,"-901",4,0)==-EACCES);
    assert(md_process_oom_io(copy,user,0,1,fd,-1,0,"250",3,0)==3);
    assert(md_process_oom_io(copy,user,1,1,fd,-1,0,"0",1,0)==-EACCES);
    assert(md_process_oom_io(copy,user,0,1,fd,-1,0,"-900",4,0)==4);
    assert(!md_process_member_exec(child) && copy->value==-900);
    assert(md_process_oom_io(copy,root,1,1,fd,-1,0,"-17",3,0)==3);
    assert(copy->value==-1000 && copy->minimum==-900 && state->value==-900);
    memset(data,0,sizeof(data));
    assert(md_process_oom_io(copy,root,1,0,fd,-1,0,data,sizeof(data),0)==4 && !strcmp(data,"-17\n"));
    assert(md_process_oom_io(copy,root,0,1,fd,-1,0,"1001",4,0)==-EINVAL);
    assert(md_process_oom_io(copy,root,0,1,fd,-1,0,"2147483648",10,0)==-ERANGE);
    assert(md_process_oom_io(copy,root,0,1,-1,-1,0,"0",1,0)==-EBADF);
    md_process_member_release(child); md_process_member_release(thread); md_process_member_release(p);
    close(fd);
    puts("PASS OOM policy: native denial, error preservation, thread sharing, fork, exec, legacy and credentials");
}
int main(void) {
    struct md_identity root=md_identity_new(0,0), user=md_identity_new(1000,1000), other=md_identity_new(1001,1001);
    struct rlimit limit={3,3}, old;
    nofile(&root,&user);
    native_limits(&root,&user);
    proc_limits();
    oom(&root,&user);
    struct md_process_member *p=member(&user,&limit), *a=NULL,*b=NULL,*c=NULL;
    assert(p);
    assert(!md_process_member_fork(p,&user,CLONE_THREAD,&a));
    assert(!md_process_member_fork(p,&user,0,&b));
    assert(md_process_member_fork(p,&user,0,&c)==-EAGAIN && !c);
    /* Registry custody, not live-thread custody, bounds a zombie's charge. */
    md_process_member_retain(b); md_process_member_release(b);
    assert(md_process_member_fork(p,&user,0,&c)==-EAGAIN);
    md_process_member_release(b);
    assert(!md_process_member_fork(p,&user,0,&b));
    md_process_member_release(b);
    limit=(struct rlimit){2,3};
    assert(!nproc(a,&user,&user,1,&limit,&old) && old.rlim_cur==3);
    assert(md_process_limits_value(p).nproc.rlim_cur==2);
    assert(md_process_member_fork(p,&user,0,&c)==-EAGAIN);
    assert(!md_process_member_identity(a,&other));
    assert(!md_process_member_fork(p,&user,0,&c));
    limit=(struct rlimit){1,3};
    assert(!nproc(c,&user,&user,1,&limit,NULL));
    assert(md_process_limits_value(p).nproc.rlim_cur==2);
    limit=(struct rlimit){4,3};
    assert(nproc(p,&root,&user,0,&limit,NULL)==-EINVAL);
    limit=(struct rlimit){4,4};
    assert(nproc(p,&user,&user,1,&limit,NULL)==-EPERM);
    assert(nproc(p,&other,&user,0,NULL,NULL)==-EPERM);
    assert(!nproc(p,&root,&user,0,&limit,NULL));
    /* A UID change may exceed the destination quota; the next exec refuses. */
    assert(!md_process_member_identity(c,&other));
    assert(md_process_member_exec(c)==-EAGAIN);
    md_process_member_release(a);
    assert(!md_process_member_exec(c));
    md_process_member_release(c);
    md_process_member_release(p);
    p=member(&root,&(struct rlimit){0,0}); assert(p);
    assert(!md_process_member_fork(p,&root,0,&a));
    md_process_member_release(a); md_process_member_release(p);
    /* A child namespace's creation ceiling bounds its creator's aggregate,
     * including tasks outside that child. Child capabilities cannot bypass it. */
    limit=(struct rlimit){3,10};
    p=member(&user,&limit); assert(p);
    assert(!md_process_member_fork(p,&user,0,&a));
    struct md_identity nested=md_identity_copy(&user);
    nested.user_namespace=md_user_namespace_new(NULL,1000,1000,0); assert(nested.user_namespace);
    nested.caps.effective=MD_CAP_VALID;
    assert(!md_process_member_identity(a,&nested));
    limit=(struct rlimit){10,10};
    assert(!nproc(a,&nested,&nested,1,&limit,NULL));
    assert(!md_process_member_fork(a,&nested,0,&b));
    assert(md_process_member_fork(a,&nested,0,&c)==-EAGAIN);
    md_process_member_release(p);
    assert(!md_process_member_fork(a,&nested,0,&c));
    md_process_member_release(a); md_process_member_release(b); md_process_member_release(c);
    md_identity_release(&nested);
    /* Historical nproc scopes must not keep user-namespace quotas occupied. */
    p=member(&user,&(struct rlimit){3,10}); assert(p);
    assert(!md_user_namespace_set_limit(NULL,MD_USER_NAMESPACES,1));
    for (unsigned i=0;i<8;++i) {
        nested=md_identity_copy(&user);
        assert(!md_identity_enter_user_namespace(&nested));
        assert(!md_process_member_fork(p,&user,0,&a));
        assert(!md_process_member_identity(a,&nested));
        md_process_member_release(a); md_identity_release(&nested);
    }
    md_process_member_release(p);
    assert(!md_user_namespace_set_limit(NULL,MD_USER_NAMESPACES,INT_MAX));
    md_identity_release(&root); md_identity_release(&user); md_identity_release(&other);
    puts("PASS process limits: reservation, zombie custody, thread sharing, fork copy, credentials and exec");
}
