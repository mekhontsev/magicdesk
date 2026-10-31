#define _GNU_SOURCE
#include "test_syscall_registers.h"
#include <sys/mman.h>
#include "fs_buffer.h"
#include "posix_acl.h"
#include <assert.h>
#include <asm/hwcap.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/stat.h>
#include <linux/close_range.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <pthread.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ipc.h>
#include <sys/auxv.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/sem.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <time.h>
#include <unistd.h>

static void put(int fd) { assert(write(fd, "x", 1) == 1); }
static void get(int fd) {
    char byte;
    /* EVENT_WAIT: pipe acknowledgement orders resource mutation/observation;
     * the fixture's alarm terminates a missing peer, never implies success. */
    assert(read(fd, &byte, 1) == 1);
}
static void reap(pid_t pid) {
    int status = 0;
    pid_t observed = waitpid(pid, &status, 0);
    if (observed != pid || !WIFEXITED(status) || WEXITSTATUS(status))
        fprintf(stderr, "wait pid=%d observed=%d status=%#x errno=%d\n", pid, observed, status, errno);
    assert(observed == pid && WIFEXITED(status) && !WEXITSTATUS(status));
}
struct context { int ready[2], done[2], file, unshare, root; };
static const char *executable;
static void processor_state_check(void) {
    int policy=prctl(PR_GET_SPECULATION_CTRL,PR_SPEC_STORE_BYPASS,0,0,0);
    if (policy<0 || !(policy&PR_SPEC_PRCTL) || !(getauxval(AT_HWCAP)&HWCAP_SSBS)) return;
    unsigned long ssbs;
    __asm__ volatile(".arch_extension ssbs\n\tmrs %0,SSBS" : "=r"(ssbs));
    if (!!ssbs!=!!(policy&PR_SPEC_ENABLE))
        fprintf(stderr,"processor policy=%#x SSBS=%#lx\n",policy,ssbs);
    assert(!!ssbs==!!(policy&PR_SPEC_ENABLE));
}
static int processor_state(const char *self,int target) {
    processor_state_check();
    if (target) return 0;
    const int policies[]={PR_SPEC_ENABLE,PR_SPEC_DISABLE,PR_SPEC_DISABLE_NOEXEC,PR_SPEC_FORCE_DISABLE};
    for (unsigned i=0;i<sizeof(policies)/sizeof(*policies);++i) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            int policy=prctl(PR_GET_SPECULATION_CTRL,PR_SPEC_STORE_BYPASS,0,0,0);
            if (policy>=0 && (policy&PR_SPEC_PRCTL) && !(policy&PR_SPEC_FORCE_DISABLE)) {
                assert(!prctl(PR_SET_SPECULATION_CTRL,PR_SPEC_STORE_BYPASS,policies[i],0,0));
                processor_state_check();
            }
            execl(self,self,"processor-state-exec",(char *)NULL);
            perror("processor state exec"); _exit(1);
        }
        reap(child);
    }
    puts("PASS processor state: inherited policy, explicit protection and exec reset");
    return 0;
}
static int mutate(void *argument) {
    struct context *c = argument;
    if (c->unshare == -2) {
        assert(unshare(CLONE_FS | CLONE_VM) == -1 && errno == EINVAL);
    }
    if (c->unshare == -4) {
        assert(unshare(CLONE_FS | CLONE_NEWTIME) == -1 && errno == ENOTSUP);
    }
    if (c->unshare == -3) {
        char ready[24], done[24], file[24];
        snprintf(ready, sizeof(ready), "%d", c->ready[1]);
        snprintf(done, sizeof(done), "%d", c->done[0]);
        snprintf(file, sizeof(file), "%d", c->file);
        execl(executable, executable, "exec-action", ready, done, file, (char *)NULL);
        perror("exec"); return 1;
    }
    if (c->unshare >= 0) assert(!unshare(c->unshare));
    if (c->root) assert(!chroot("/proc/self/fdinfo"));
    else {
        assert(!chdir("b")); umask(0077);
        assert(fcntl(c->file, F_SETFD, FD_CLOEXEC) == 0);
        assert(lseek(c->file, 9, SEEK_SET) == 9);
    }
    put(c->ready[1]); get(c->done[0]);
    return 0;
}
static void sharing(unsigned flags, int split, int root) {
    struct context c = {.unshare=split, .root=root};
    assert(!pipe(c.ready) && !pipe(c.done));
    c.file = open("value", O_RDWR | O_CREAT | O_TRUNC, 0600); assert(c.file >= 0);
    assert(!mkdir("b", 0700) || errno == EEXIST);
    char before[4096]; assert(getcwd(before, sizeof(before)));
    mode_t old = umask(0022);
    void *stack = malloc(1024 * 1024); assert(stack);
    pid_t pid = clone(mutate, (char *)stack + 1024 * 1024, SIGCHLD | flags, &c); assert(pid > 0);
    get(c.ready[0]);
    int shared_fs = (flags & CLONE_FS) && !(split >= 0 && (split & CLONE_FS));
    if (root) {
        int fd = open("value", O_RDONLY);
        if (shared_fs && root==1) assert(fd < 0 && errno == EACCES);
        else { assert(fd >= 0); close(fd); }
        if (root==2) {
            /* Managed chroot changes absolute lookup, not the existing cwd. */
            char absolute[sizeof(before)+sizeof("/value")];
            assert(snprintf(absolute,sizeof(absolute),"%s/value",before)>0);
            fd=open(absolute,O_RDONLY);
            if (shared_fs) assert(fd<0 && errno==ENOENT);
            else { assert(fd>=0); close(fd); }
        }
    } else {
        char after[4096]; assert(getcwd(after, sizeof(after)));
        assert((strcmp(before, after) != 0) == shared_fs);
        assert(umask(0022) == (shared_fs ? 0077 : 0022));
        int shared_files = (flags & CLONE_FILES) && split != -3
            && !(split >= 0 && (split & CLONE_FILES));
        assert(fcntl(c.file, F_GETFD) == (shared_files ? FD_CLOEXEC : 0));
        /* Unsharing a table does not clone its open-file descriptions. */
        assert(lseek(c.file, 0, SEEK_CUR) == 9);
    }
    put(c.done[1]); reap(pid); free(stack);
    if (!root) assert(!chdir(before));
    umask(old);
    close(c.file);
    close(c.ready[0]); close(c.ready[1]); close(c.done[0]); close(c.done[1]);
    printf("PASS sharing flags=%#x unshare=%d root=%d\n", flags, split, root);
}
static void case_sharing(unsigned flags, int split, int root) {
    pid_t pid = fork(); assert(pid >= 0);
    if (!pid) { sharing(flags, split, root); _exit(0); }
    reap(pid);
}
struct range_context { int ready[2], done[2], file, invalid; unsigned flags; };
static int range_child(void *argument) {
    struct range_context *c=argument;
    long result=syscall(SYS_close_range,(unsigned)c->file+c->invalid,(unsigned)c->file,c->flags);
    if (c->invalid) {
        assert(result==-1 && errno==EINVAL);
        assert(!fcntl(c->file,F_SETFD,FD_CLOEXEC));
    } else {
        assert(!result);
        if (c->flags & CLOSE_RANGE_CLOEXEC) assert(fcntl(c->file,F_GETFD)==FD_CLOEXEC);
        else assert(fcntl(c->file,F_GETFD)==-1 && errno==EBADF);
    }
    put(c->ready[1]); get(c->done[0]); return 0;
}
static void range_sharing(unsigned flags, int invalid) {
    struct range_context c={.flags=flags,.invalid=invalid};
    assert(!pipe(c.ready) && !pipe(c.done));
    c.file=open("value",O_RDONLY); assert(c.file>=0);
    void *stack=malloc(1024*1024); assert(stack);
    pid_t pid=clone(range_child,(char *)stack+1024*1024,SIGCHLD|CLONE_FILES,&c);
    assert(pid>0); get(c.ready[0]);
    int split=(flags & CLOSE_RANGE_UNSHARE) && !invalid;
    if (split) assert(fcntl(c.file,F_GETFD)==0);
    else if (invalid || (flags & CLOSE_RANGE_CLOEXEC)) assert(fcntl(c.file,F_GETFD)==FD_CLOEXEC);
    else assert(fcntl(c.file,F_GETFD)==-1 && errno==EBADF);
    put(c.done[1]); reap(pid); free(stack);
    if (split || invalid || (flags & CLOSE_RANGE_CLOEXEC)) assert(!close(c.file));
    close(c.ready[0]); close(c.ready[1]); close(c.done[0]); close(c.done[1]);
    printf("PASS close_range flags=%#x invalid=%d\n",flags,invalid);
}
static int import_descriptor(int original);
static void restricted_descriptors(int via_prctl) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        int file=open("value",O_RDWR|O_CREAT,0600); assert(file>=0);
        int channel[2]; assert(!pipe(channel));
        int proc=open("/proc/self/uid_map",O_RDONLY); assert(proc>=0);
        struct sock_filter rules[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_prctl,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_seccomp,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_prlimit64,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog program={sizeof(rules)/sizeof(*rules),rules};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        if (via_prctl) assert(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program));
        else assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        assert(prctl(PR_GET_DUMPABLE)==-1 && errno==EPERM);
        assert(syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program)==-1 && errno==EPERM);
        int imported=import_descriptor(proc); assert(imported>=0);
        char imported_text[128]; assert(read(imported,imported_text,sizeof(imported_text))>0);
        assert(!lseek(imported,0,SEEK_SET) && !close(imported));
        int slot=dup(proc); assert(slot>=0 && !close(slot));
        /* A proc selector outlives the descriptor which armed it. Reusing its
         * slot must retain ordinary IO, also after the root becomes restricted. */
        assert(dup3(file,slot,0)==slot);
        assert(!chroot("/proc/self/fdinfo"));
        char data[128]={0};
        int proc_copy=fcntl(proc,F_DUPFD_CLOEXEC,200); assert(proc_copy>=200);
        assert(read(proc_copy,data,sizeof(data))>0);
        assert(lseek(proc_copy,0,SEEK_SET)==0);
        assert(!close(proc_copy));
        assert(read(proc,data,sizeof(data))>0);
        assert(write(slot,"z",1)==1 && lseek(slot,0,SEEK_SET)==0);
        assert(read(slot,data,1)==1 && data[0]=='z');
        assert(!fsync(slot));
        assert(dup3(channel[1],slot,0)==slot);
        put(slot); get(channel[0]);
        assert(!close(slot) && !close(proc));
        close(channel[0]); close(channel[1]);
        int copy=dup(file); assert(copy>=0);
        int target=fcntl(file,F_DUPFD,100); assert(target>=100);
        assert(dup3(copy,target,O_CLOEXEC)==target);
        assert(fcntl(target,F_GETFD)==FD_CLOEXEC);
        /* The kernel takes a 32-bit command even when the raw ABI has high bits. */
        unsigned long high=1UL<<32;
        assert(!syscall(SYS_fcntl,target,high|F_SETFD,0));
        assert(fcntl(target,F_GETFD)==0);
        int wide=syscall(SYS_fcntl,copy,high|F_DUPFD_CLOEXEC,101); assert(wide>=101);
        assert(fcntl(wide,F_GETFD)==FD_CLOEXEC);
        assert(!syscall(SYS_close_range,(unsigned)target,(unsigned)target,
            CLOSE_RANGE_UNSHARE|CLOSE_RANGE_CLOEXEC));
        assert(fcntl(target,F_GETFD)==FD_CLOEXEC);
        assert(!syscall(SYS_close_range,(unsigned)wide,(unsigned)wide,0));
        assert(fcntl(wide,F_GETFD)==-1 && errno==EBADF);
        assert(!close(copy) && !close(target) && !close(file));
        _exit(0);
    }
    reap(child);
    printf("PASS restricted proc-root preserves typed IO, reused descriptor IO, mutation and flags prctl=%d\n",via_prctl);
}
struct cwd_context { int ready[2], go[2], directory[2], bad, writer; };
static int cwd_worker(void *argument) {
    struct cwd_context *c=argument;
    put(c->ready[1]); get(c->go[0]);
    for (unsigned i=0;i<250;++i) {
        if (c->writer) {
            assert(!fchdir(c->directory[i%2]));
            assert(fchdir(c->bad)==-1 && errno==ENOTDIR);
        } else {
            /* Ordinary open uses direct notifications; O_PATH and getcwd use
             * the task-affine transport. Either directory is a valid snapshot. */
            int fd=open("item",i%2 ? O_PATH|O_CLOEXEC : O_RDONLY|O_CLOEXEC); assert(fd>=0);
            struct stat st; assert(!fstat(fd,&st) && S_ISREG(st.st_mode));
            assert(!close(fd));
            char path[4096]; assert(getcwd(path,sizeof(path)));
            assert(strstr(path,"/cwd-a") || strstr(path,"/cwd-b"));
        }
    }
    return 0;
}
static void concurrent_cwd(unsigned flags) {
    int original=open(".",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(original>=0);
    struct cwd_context contexts[3]={0};
    struct cwd_context *base=contexts;
    assert(!pipe(base->ready) && !pipe(base->go));
    const char *names[]={"cwd-a","cwd-b"};
    for (unsigned i=0;i<2;++i) {
        assert(!mkdir(names[i],0700) || errno==EEXIST);
        base->directory[i]=open(names[i],O_PATH|O_DIRECTORY|O_CLOEXEC); assert(base->directory[i]>=0);
        int fd=openat(base->directory[i],"item",O_RDWR|O_CREAT|O_TRUNC|O_CLOEXEC,0700); assert(fd>=0);
        assert(!close(fd));
    }
    base->bad=openat(base->directory[0],"item",O_PATH|O_CLOEXEC); assert(base->bad>=0);
    assert(!fchdir(base->directory[0]));
    pid_t children[3]; void *stacks[3];
    for (unsigned i=0;i<3;++i) {
        contexts[i]=*base; contexts[i].writer=i==0;
        stacks[i]=malloc(1024*1024); assert(stacks[i]);
        children[i]=clone(cwd_worker,(char *)stacks[i]+1024*1024,SIGCHLD|CLONE_FS|flags,contexts+i);
        assert(children[i]>0);
    }
    for (unsigned i=0;i<3;++i) get(base->ready[0]);
    for (unsigned i=0;i<3;++i) put(base->go[1]);
    for (unsigned i=0;i<3;++i) { reap(children[i]); free(stacks[i]); }
    assert(!fchdir(original) && !close(original));
    assert(!close(base->directory[0]) && !close(base->directory[1]) && !close(base->bad));
    close(base->ready[0]); close(base->ready[1]); close(base->go[0]); close(base->go[1]);
    printf("PASS concurrent cwd: shared FS, files=%s, direct/RPC relative opens and failed fchdir\n",
        flags&CLONE_FILES ? "shared" : "independent");
}
struct alias_context { int ready[2], go[2], directory[2], target, writer; };
static int alias_worker(void *argument) {
    struct alias_context *c=argument;
    char path[128];
    snprintf(path,sizeof(path),"/proc/thread-self/fd/%d/item",c->target);
    put(c->ready[1]); get(c->go[0]);
    for (unsigned i=0;i<250;++i) {
        if (c->writer) assert(dup3(c->directory[i%2],c->target,O_CLOEXEC)==c->target);
        else {
            int fd=open(path,i%2 ? O_PATH|O_CLOEXEC : O_RDONLY|O_CLOEXEC); assert(fd>=0);
            struct stat st; assert(!fstat(fd,&st) && S_ISREG(st.st_mode));
            assert(st.st_size==1 || st.st_size==2);
            if (!(i%2)) {
                char bytes[3]; ssize_t n=read(fd,bytes,sizeof(bytes));
                assert((n==1 && bytes[0]=='a') || (n==2 && !memcmp(bytes,"bb",2)));
            }
            assert(!close(fd));
        }
    }
    return 0;
}
static void concurrent_aliases(void) {
    struct alias_context contexts[3]={0};
    struct alias_context *base=contexts;
    assert(!pipe(base->ready) && !pipe(base->go));
    const char *names[]={"alias-a","alias-b"}, *contents[]={"a","bb"};
    for (unsigned i=0;i<2;++i) {
        assert(!mkdir(names[i],0700) || errno==EEXIST);
        base->directory[i]=open(names[i],O_PATH|O_DIRECTORY|O_CLOEXEC); assert(base->directory[i]>=0);
        int fd=openat(base->directory[i],"item",O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC,0600); assert(fd>=0);
        assert(write(fd,contents[i],i+1)==(ssize_t)i+1 && !close(fd));
    }
    base->target=fcntl(base->directory[0],F_DUPFD_CLOEXEC,0); assert(base->target>=0);
    pid_t children[3]; void *stacks[3];
    for (unsigned i=0;i<3;++i) {
        contexts[i]=*base; contexts[i].writer=i==0;
        stacks[i]=malloc(1024*1024); assert(stacks[i]);
        children[i]=clone(alias_worker,(char *)stacks[i]+1024*1024,SIGCHLD|CLONE_FILES,contexts+i);
        assert(children[i]>0);
    }
    for (unsigned i=0;i<3;++i) get(base->ready[0]);
    for (unsigned i=0;i<3;++i) put(base->go[1]);
    for (unsigned i=0;i<3;++i) { reap(children[i]); free(stacks[i]); }
    assert(!close(base->target) && !close(base->directory[0]) && !close(base->directory[1]));
    close(base->ready[0]); close(base->ready[1]); close(base->go[0]); close(base->go[1]);
    puts("PASS concurrent proc aliases: shared files, dup3 and retained directory capture");
}
static void undo(void) {
    int sem = syscall(SYS_semget, IPC_PRIVATE, 1, 0600); assert(sem >= 0);
    assert(!syscall(SYS_semctl, sem, 0, SETVAL, 1));
    struct sembuf op = {.sem_num=0, .sem_op=-1, .sem_flg=SEM_UNDO};
    assert(!syscall(SYS_semop, sem, &op, 1));
    assert(syscall(SYS_semctl, sem, 0, GETVAL, 0) == 0);
    assert(!unshare(CLONE_SYSVSEM));
    assert(syscall(SYS_semctl, sem, 0, GETVAL, 0) == 1);
    assert(!syscall(SYS_semctl, sem, 0, IPC_RMID, 0));
    puts("PASS unshare SYSVSEM applies the last owner's adjustments");
}
struct undo_context { int sem, ready[2], done[2], exec; };
static int undo_child(void *argument) {
    struct undo_context *c = argument;
    if (c->exec) {
        char sem[24], ready[24], done[24];
        snprintf(sem, sizeof(sem), "%d", c->sem);
        snprintf(ready, sizeof(ready), "%d", c->ready[1]);
        snprintf(done, sizeof(done), "%d", c->done[0]);
        execl(executable, executable, "exec-undo", sem, ready, done, (char *)NULL);
        perror("exec undo"); return 1;
    }
    assert(!unshare(CLONE_SYSVSEM));
    assert(syscall(SYS_semctl, c->sem, 0, GETVAL, 0) == 1);
    struct sembuf op = {.sem_num=0, .sem_op=-1, .sem_flg=SEM_UNDO};
    assert(!syscall(SYS_semop, c->sem, &op, 1));
    put(c->ready[1]); get(c->done[0]); return 0;
}
static void shared_undo(int exec) {
    struct undo_context c = {.exec=exec};
    assert(!pipe(c.ready) && !pipe(c.done));
    c.sem = syscall(SYS_semget, IPC_PRIVATE, 1, 0600); assert(c.sem >= 0);
    assert(!syscall(SYS_semctl, c.sem, 0, SETVAL, 2));
    struct sembuf op = {.sem_num=0, .sem_op=-1, .sem_flg=SEM_UNDO};
    assert(!syscall(SYS_semop, c.sem, &op, 1));
    void *stack = malloc(1024 * 1024); assert(stack);
    pid_t pid = clone(undo_child, (char *)stack + 1024 * 1024, SIGCHLD | CLONE_SYSVSEM, &c);
    assert(pid > 0); get(c.ready[0]);
    assert(syscall(SYS_semctl, c.sem, 0, GETVAL, 0) == 0);
    put(c.done[1]); reap(pid); free(stack);
    assert(syscall(SYS_semctl, c.sem, 0, GETVAL, 0) == (exec ? 0 : 1));
    assert(!unshare(CLONE_SYSVSEM));
    assert(syscall(SYS_semctl, c.sem, 0, GETVAL, 0) == 2);
    assert(!syscall(SYS_semctl, c.sem, 0, IPC_RMID, 0));
    close(c.ready[0]); close(c.ready[1]); close(c.done[0]); close(c.done[1]);
    printf("PASS shared SEM_UNDO last-owner lifetime exec=%d\n", exec);
}
static void workload(const char *mode) {
    struct timespec start, finish;
    assert(!clock_gettime(CLOCK_MONOTONIC, &start));
    unsigned count;
    if (!strcmp(mode, "metadata")) {
        int fd = open("workload", O_WRONLY | O_CREAT | O_TRUNC, 0600); assert(fd >= 0);
        put(fd); assert(!close(fd));
        count = 4000;
        for (unsigned i = 0; i < count; ++i) {
            struct stat named, opened;
            assert(!stat("workload", &named));
            fd = open("workload", O_RDONLY); assert(fd >= 0);
            assert(!fstat(fd, &opened) && opened.st_ino == named.st_ino);
            get(fd); assert(!close(fd));
            assert(stat("absent-workload", &named) == -1 && errno == ENOENT);
        }
        assert(!unlink("workload"));
    } else {
        assert(!strcmp(mode, "spawn")); count = 100;
        for (unsigned i = 0; i < count; ++i) {
            pid_t pid = fork(); assert(pid >= 0);
            if (!pid) { execl(executable, executable, "noop", ".", (char *)NULL); _exit(127); }
            reap(pid);
        }
    }
    assert(!clock_gettime(CLOCK_MONOTONIC, &finish));
    long long elapsed = (finish.tv_sec-start.tv_sec)*1000000000LL + finish.tv_nsec-start.tv_nsec;
    printf("PASS workload=%s count=%u elapsedNs=%lld\n", mode, count, elapsed);
}
static void probe(void) {
    unsigned long flags[] = {0, CLONE_FS, CLONE_FILES, CLONE_SYSVSEM, CLONE_NEWUSER,
        CLONE_NEWNS, CLONE_NEWUTS, CLONE_NEWIPC, CLONE_NEWPID, CLONE_NEWNET, CLONE_NEWCGROUP};
    for (unsigned i = 0; i < sizeof(flags)/sizeof(flags[0]); i++) {
        pid_t pid = fork(); assert(pid >= 0);
        if (!pid) {
            errno = 0; long r = syscall(SYS_unshare, flags[i]);
            printf("PROBE unshare=%#lx result=%ld errno=%d\n", flags[i], r, errno); _exit(0);
        }
        reap(pid);
    }
}
static void origins(const char *cwd, int protected) {
    char actual[4096]; assert(getcwd(actual,sizeof(actual)) && !strcmp(actual,cwd));
    int fd=open("origin-file",O_RDWR|O_CREAT|O_TRUNC|O_CLOEXEC,0600); assert(fd>=0);
    assert(write(fd,"origin",6)==6 && lseek(fd,2,SEEK_SET)==2);
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    for (unsigned i=0;i<6;++i) {
        char path[128];
        switch (i) {
        case 0: snprintf(path,sizeof(path),"/proc/self/fd/%d",fd); break;
        case 1: snprintf(path,sizeof(path),"/proc/thread-self/fd/%d",fd); break;
        case 2: snprintf(path,sizeof(path),"/proc/%d/fd/%d",getpid(),fd); break;
        case 3: snprintf(path,sizeof(path),"/proc/%d/task/%ld/fd/%d",getpid(),syscall(SYS_gettid),fd); break;
        case 4: snprintf(path,sizeof(path),"/dev/fd/%d",fd); break;
        default: snprintf(path,sizeof(path),"/proc/./self/fd//./%d",fd); break;
        }
        int copy=open(path,O_RDONLY|O_CLOEXEC); char bytes[6];
        assert(copy>=0 && read(copy,bytes,sizeof(bytes))==6 && !memcmp(bytes,"origin",6));
        assert(lseek(fd,0,SEEK_CUR)==2 && !close(copy));
        struct stat original, alias;
        assert(!fstat(fd,&original) && !stat(path,&alias));
        assert(original.st_dev==alias.st_dev && original.st_ino==alias.st_ino);
    }
    int directory=open("/proc/thread-self/cwd",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(directory>=0);
    assert(!fchdir(directory) && !close(directory));
    assert(getcwd(actual,sizeof(actual)) && !strcmp(actual,cwd));
    assert(!close(fd) && !unlink("origin-file"));
    puts("PASS initial cwd and proc aliases retain native offsets and protected metadata access");
}
struct custody_context { pid_t parent; int protected, call, dirfd, flags; };
static void rpc_cwd(int protected) {
    int base=open(".",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(base>=0);
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,AUDIT_ARCH_AARCH64,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_openat,0,4),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[2])),
        BPF_STMT(BPF_ALU|BPF_AND|BPF_K,O_PATH|O_DIRECTORY),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,O_PATH|O_DIRECTORY,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EUCLEAN),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    assert(open(".",O_PATH|O_DIRECTORY|O_CLOEXEC)==-1 && errno==EUCLEAN);
    char path[4096]; assert(getcwd(path,sizeof(path)) && !strcmp(path,"/tmp"));
    int fd=open("rpc-cwd-a",O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600);
    assert(fd>=0); put(fd); assert(!close(fd));
    assert(!linkat(base,"rpc-cwd-a",AT_FDCWD,"rpc-cwd-b",0));
    assert(!renameat(AT_FDCWD,"rpc-cwd-b",base,"rpc-cwd-c"));
    assert(!rename("rpc-cwd-c","rpc-cwd-d"));
    fd=open("rpc-cwd-d",O_PATH|O_CLOEXEC); assert(fd>=0 && !close(fd));
    assert(!unlink("rpc-cwd-d") && !unlink("rpc-cwd-a") && !close(base));
    puts("PASS RPC cwd without guest directory capture: getcwd, create, O_PATH, mixed bases and two-base mutations");
}
static int custody_child(void *argument) {
    struct custody_context *c=argument;
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
    if (getppid()!=c->parent) _exit(1);
    if (c->protected) assert(!prctl(PR_SET_DUMPABLE,0));
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,AUDIT_ARCH_AARCH64,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,c->call,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    struct sock_filter delivery[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_openat,0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[4])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)MD_FS_BUFFER_SOURCE,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    if (c->call==SYS_openat) program=(struct sock_fprog){sizeof(delivery)/sizeof(*delivery),delivery};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    /* The fifth openat argument is ignored by the kernel; the internal anchor
     * gate uses it to select a retained result. Kill only that delivery, not the
     * original application open. Native controls never enter this gate. */
    int fd=syscall(SYS_openat,c->dirfd,"custody-file",c->flags,0,0,0); assert(fd>=0);
    assert(!close(fd)); return 0;
}
static unsigned terminal_custody(int guest, int protected, int call, int explicit_base, int nofollow) {
    int fd=open("custody-file",O_RDWR|O_CREAT|O_TRUNC|O_CLOEXEC,0600);
    assert(fd>=0 && fd<256 && !close(fd));
    int base=explicit_base ? open(".",O_PATH|O_DIRECTORY|O_CLOEXEC) : AT_FDCWD;
    assert(base==AT_FDCWD || base>=0);
    unsigned char before[256];
    for (unsigned i=0;i<sizeof(before);++i) before[i]=fcntl((int)i,F_GETFD)>=0;
    void *stack=malloc(1024*1024); assert(stack);
    struct custody_context context={getpid(),protected,call,base,O_PATH|O_CLOEXEC|(nofollow ? O_NOFOLLOW : 0)};
    pid_t child=clone(custody_child,(char *)stack+1024*1024,SIGCHLD|CLONE_FILES,&context);
    assert(child>0);
    int status;
    /* EVENT_WAIT: exact child termination precedes inspection of its surviving
     * shared FD table. The fixture alarm is a failure bound, not a settling delay. */
    assert(waitpid(child,&status,0)==child);
    if (guest && call==SYS_openat)
        assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS);
    else assert(WIFEXITED(status) && !WEXITSTATUS(status));
    unsigned leaked=0;
    for (unsigned i=0;i<sizeof(before);++i) {
        int flags=fcntl((int)i,F_GETFD);
        if (before[i]) assert(flags>=0);
        else if (flags>=0) {
            int type=0; socklen_t size=sizeof(type);
            int result=getsockopt((int)i,SOL_SOCKET,SO_TYPE,&type,&size);
            int error=result<0 ? errno : 0;
            printf("PROBE retained private fd=%u flags=%#x status=%#x socketType=%d socketErrno=%d\n",
                i,flags,fcntl((int)i,F_GETFL),type,error);
            ++leaked;
            assert(!close((int)i));
        }
    }
    if (base>=0) assert(!close(base));
    free(stack); assert(!unlink("custody-file"));
    printf("%s terminal RPC custody protected=%d call=%d explicitBase=%d nofollow=%d leaked=%u\n",
        leaked ? "FAIL" : "PASS",protected,call,explicit_base,nofollow,leaked);
    return leaked;
}
static void buffer_cwd_calls(const char *expected, int protected) {
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    struct rlimit saved; assert(!getrlimit(RLIMIT_NOFILE,&saved));
    struct rlimit limit=saved; limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
    int fds[64],n=0,fd;
    while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) { assert(n<64); fds[n++]=fd; }
    assert(errno==EMFILE);
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_socket,4,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_connect,3,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_sendmsg,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_recvmsg,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD)
    };
    struct sock_fprog program={sizeof(filter)/sizeof(*filter),filter};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    for (unsigned i=0;i<24;++i) {
        char value[4096]; assert(getcwd(value,sizeof(value)) && !strcmp(value,expected));
        assert(syscall(SYS_getcwd,value,1)==-1 && errno==ERANGE);
        assert(syscall(SYS_getcwd,(void *)1,sizeof(value))==-1 && errno==EFAULT);
    }
    for (int i=0;i<n;++i) assert(!close(fds[i]));
    assert(!setrlimit(RLIMIT_NOFILE,&saved));
}
static void buffer_cwd(void) {
    char expected[4096]; assert(getcwd(expected,sizeof(expected)));
    for (int exec=0;exec<2;++exec) for (int protected=0;protected<2;++protected) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            if (exec) execl(executable,executable,protected ? "buffer-exec-protected" : "buffer-exec",expected,(char *)NULL);
            else { buffer_cwd_calls(expected,protected); _exit(0); }
            _exit(99);
        }
        int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    }
    enum { children=48 };
    pid_t pids[children]; int ready[2],go[2]; assert(!pipe(ready) && !pipe(go));
    for (unsigned i=0;i<children;++i) {
        pids[i]=fork(); assert(pids[i]>=0);
        if (!pids[i]) {
            close(ready[0]); close(go[1]); put(ready[1]); get(go[0]);
            close(ready[1]); close(go[0]); buffer_cwd_calls(expected,i%2); _exit(0);
        }
    }
    close(ready[1]); close(go[0]);
    for (unsigned i=0;i<children;++i) get(ready[0]);
    for (unsigned i=0;i<children;++i) put(go[1]);
    close(ready[0]); close(go[1]);
    for (unsigned i=0;i<children;++i) {
        int status; assert(waitpid(pids[i],&status,0)==pids[i] && WIFEXITED(status) && !WEXITSTATUS(status));
    }
    assert(getcwd(expected,sizeof(expected)));
    puts("PASS buffered cwd: fork/exec, protected tasks, zero free FDs, forbidden RPC, EFAULT/ERANGE and 48 concurrent callers");
}
static void buffer_open_calls(const char *path, int flags, int protected, int slots) {
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
    limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
    int last=-1,fd;
    while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) last=fd;
    assert(errno==EMFILE && last>=0);
    if (slots) assert(!close(last));
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_socket,4,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_connect,3,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_sendmsg,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_recvmsg,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD)
    };
    struct sock_fprog program={sizeof(filter)/sizeof(*filter),filter};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    for (unsigned i=0;i<8;++i) {
        fd=open(path,flags);
        if (!slots) { assert(fd==-1 && errno==EMFILE); continue; }
        assert(fd==last && fcntl(fd,F_GETFD)==(flags&O_CLOEXEC ? FD_CLOEXEC : 0));
        assert(fcntl(fd,F_GETFL)==(flags&(O_PATH|O_DIRECTORY|O_NOFOLLOW)) && !close(fd));
    }
}
static void buffer_open(void) {
    assert(!mkdir("buffer-native-parent",0700) && !chdir("buffer-native-parent"));
    char path[202]; memset(path,'p',sizeof(path)-1); path[sizeof(path)-1]=0;
    int fd=open(path,O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600); assert(fd>=0);
    put(fd); assert(!close(fd) && !mkdir("buffer-directory",0700) && !symlink(path,"buffer-link"));
    const char *names[]={path,"buffer-directory","buffer-link","buffer-link",path,"buffer-directory"};
    for (int protected=0;protected<2;++protected) for (unsigned kind=0;kind<6;++kind) {
        int flags=O_PATH|O_CLOEXEC|((kind==1 || kind==5) ? O_DIRECTORY : 0)
            |((kind==2 || kind>=4) ? O_NOFOLLOW : 0);
        fd=open(names[kind],flags); assert(fd>=0);
        struct stat named,opened;
        assert(!(kind==3 ? stat(names[kind],&named) : lstat(names[kind],&named)) && !fstat(fd,&opened));
        assert(named.st_dev==opened.st_dev && named.st_ino==opened.st_ino && named.st_mode==opened.st_mode);
        int actual=fcntl(fd,F_GETFL), expected=flags&(O_PATH|O_DIRECTORY|O_NOFOLLOW);
        if (actual!=expected) fprintf(stderr,"buffer-open protected=%d kind=%u flags=%#x expected=%#x\n",
            protected,kind,actual,expected);
        assert(actual==expected);
        assert(!close(fd));
        for (int absolute=0;absolute<2;++absolute) for (int slots=0;slots<2;++slots)
            for (int cloexec=0;cloexec<2;++cloexec) {
                char selected[4096];
                if (absolute) {
                    char cwd[4096]; assert(getcwd(cwd,sizeof(cwd)));
                    assert(snprintf(selected,sizeof(selected),"%s/%s",cwd,names[kind])<(int)sizeof(selected));
                } else strcpy(selected,names[kind]);
                pid_t child=fork(); assert(child>=0);
                if (!child) { buffer_open_calls(selected,cloexec ? flags : flags&~O_CLOEXEC,protected,slots); _exit(0); }
                int status; pid_t waited;
                do waited=waitpid(child,&status,0); while (waited<0 && errno==EINTR);
                if (waited!=child || status)
                    fprintf(stderr,"buffer-open protected=%d kind=%u absolute=%d slots=%d cloexec=%d child=%d waited=%d status=%#x\n",
                        protected,kind,absolute,slots,cloexec,child,waited,status);
                assert(waited==child && WIFEXITED(status) && !WEXITSTATUS(status));
            }
    }
    assert(!unlink("buffer-link") && !rmdir("buffer-directory") && !unlink(path));
    assert(!chdir("..") && !rmdir("buffer-native-parent"));
    puts("PASS buffered O_PATH: absolute/cwd, long names, file/directory/symlink, protected, exact 0/1 FD slots and forbidden RPC");
}
static int import_descriptor(int original) {
    int pair[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,pair));
    char byte='x'; struct iovec data={&byte,1};
    union { struct cmsghdr alignment; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    struct msghdr message={.msg_iov=&data,.msg_iovlen=1,
        .msg_control=control.bytes,.msg_controllen=sizeof(control.bytes)};
    struct cmsghdr *header=CMSG_FIRSTHDR(&message);
    header->cmsg_level=SOL_SOCKET; header->cmsg_type=SCM_RIGHTS; header->cmsg_len=CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(header),&original,sizeof(original));
    assert(sendmsg(pair[0],&message,0)==1);
    memset(&control,0,sizeof(control));
    assert(recvmsg(pair[1],&message,MSG_CMSG_CLOEXEC)==1 && message.msg_flags==MSG_CMSG_CLOEXEC);
    header=CMSG_FIRSTHDR(&message);
    assert(header && header->cmsg_level==SOL_SOCKET && header->cmsg_type==SCM_RIGHTS
        && header->cmsg_len==CMSG_LEN(sizeof(int)));
    int received; memcpy(&received,CMSG_DATA(header),sizeof(received));
    assert(received>=0 && !close(pair[0]) && !close(pair[1]));
    return received;
}
static void imported_cwd(void) {
    assert(!mkdir("imported-cwd",0700));
    int original=open("imported-cwd",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(original>=0);
    struct stat expected; assert(!fstat(original,&expected));
    int imported=import_descriptor(original);
    assert(!close(original));
    for (unsigned protected=0;protected<2;++protected) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
            struct stat st;
            /* Protected descriptor export admits its table-owned channel
             * before the fixture deliberately exhausts descriptor slots. */
            assert(!fstat(imported,&st));
            struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
            limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
            while (fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0)>=0) { }
            assert(errno==EMFILE);
            for (unsigned i=0;i<16;++i) {
                assert(!fchdir(imported));
                assert(!stat(".",&st) && st.st_dev==expected.st_dev && st.st_ino==expected.st_ino);
                assert(fchdir(STDERR_FILENO)==-1 && errno==ENOTDIR);
                assert(!stat(".",&st) && st.st_ino==expected.st_ino);
            }
            for (int fd=0;fd<64;++fd) assert(fcntl(fd,F_GETFD)>=0);
            _exit(0);
        }
        reap(child);
    }
    assert(!close(imported) && !rmdir("imported-cwd"));
    puts("PASS imported cwd: protected input, zero free FD slots, exact directory and failed fchdir");
}
static void native_cwd(int inherited) {
    for (unsigned protected=0;protected<2;++protected) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
            assert(!chdir("/dev"));
            int directory=inherited ? fcntl(STDIN_FILENO,F_DUPFD_CLOEXEC,0)
                : open("/dev",O_PATH|O_DIRECTORY|O_CLOEXEC);
            assert(directory>=0);
            struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
            limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
            while (fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0)>=0) { }
            assert(errno==EMFILE);
            for (unsigned i=0;i<24;++i) {
                assert(!fchdir(directory));
                char path[128]={0};
                ssize_t size=readlink("/proc/thread-self/cwd",path,sizeof(path));
                if (size!=4 || memcmp(path,"/dev",4))
                    fprintf(stderr,"native cwd protected=%u size=%zd errno=%d value=%s\n",protected,size,errno,path);
                assert(size==4 && !memcmp(path,"/dev",4));
            }
            for (int fd=0;fd<64;++fd) assert(fcntl(fd,F_GETFD)>=0);
            _exit(0);
        }
        reap(child);
    }
    printf("PASS native cwd: inherited=%d, protected caller, zero free FD slots and unchanged file table\n",inherited);
}
struct capture_context { int guest, protected, stage, directory; };
static int capture_terminal_child(void *context) {
    struct capture_context *c=context;
    if (c->protected) assert(!prctl(PR_SET_DUMPABLE,0));
    assert(!fchdir(c->directory));
    unsigned calls[]={SYS_clone,SYS_close_range,SYS_openat,SYS_sendmsg};
    unsigned action=c->stage<2 ? SECCOMP_RET_ERRNO|EACCES : SECCOMP_RET_KILL_THREAD;
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,calls[c->stage],0,1),
        BPF_STMT(BPF_RET|BPF_K,action),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    char text[128];
    ssize_t result=readlink("/proc/thread-self/cwd",text,sizeof(text));
    int error=errno;
    if (c->guest && (c->stage!=3 || c->protected)
            && !(result==-1 && error==(c->stage<2 ? EACCES : ECANCELED)))
        fprintf(stderr,"capture terminal protected=%d stage=%d result=%zd errno=%d\n",
            c->protected,c->stage,result,error);
    /* Dumpable O_PATH capture no longer calls sendmsg. Protected callers
     * still exercise the exact export-thread death and terminal cleanup. */
    if (c->guest && (c->stage!=3 || c->protected))
        assert(result==-1 && error==(c->stage<2 ? EACCES : ECANCELED));
    else assert(result==4 && !memcmp(text,"/dev",4));
    return 0;
}
static void capture_terminal(int guest,int inherited) {
    int directory=inherited ? fcntl(STDIN_FILENO,F_DUPFD_CLOEXEC,0)
        : open("/dev",O_PATH|O_DIRECTORY|O_CLOEXEC);
    assert(directory>=0);
    struct rlimit saved; assert(!getrlimit(RLIMIT_NOFILE,&saved));
    struct rlimit limit=saved; limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
    int held[64],count=0,fd;
    while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) held[count++]=fd;
    assert(errno==EMFILE);
    void *stack=malloc(1024*1024); assert(stack);
    for (int protected=0;protected<2;++protected) for (int stage=0;stage<4;++stage)
        for (unsigned repeat=0;repeat<4;++repeat) {
            struct capture_context context={guest && inherited,protected,stage,directory};
            pid_t child=clone(capture_terminal_child,(char *)stack+1024*1024,SIGCHLD|CLONE_FILES,&context);
            assert(child>0); reap(child);
            for (int i=0;i<64;++i) assert(fcntl(i,F_GETFD)>=0);
        }
    free(stack);
    for (int i=0;i<count;++i) assert(!close(held[i]));
    assert(!setrlimit(RLIMIT_NOFILE,&saved) && !close(directory));
    puts("PASS capture terminal ownership: clone/clear/open failure, proc custody without sendmsg, protected export death and full-table survivors");
}
struct proc_context { int first, second; struct stat expected; char cwd[PATH_MAX]; };
static void *proc_thread(void *argument) {
    struct proc_context *c=argument;
    assert(!unshare(CLONE_FILES|CLONE_FS));
    assert(dup3(c->second,c->first,O_CLOEXEC)==c->first && !fchdir(c->second));
    int watch=inotify_init1(IN_NONBLOCK|IN_CLOEXEC); assert(watch>=0);
    int wd=inotify_add_watch(watch,".",IN_ATTRIB); assert(wd>=0 && !inotify_rm_watch(watch,wd));
    struct rlimit saved; assert(!getrlimit(RLIMIT_NOFILE,&saved));
    struct rlimit limit=saved; limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
    int held[64],count=0,fd;
    while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) held[count++]=fd;
    assert(errno==EMFILE && count);
    char parent[96],nested[128],local[96];
    snprintf(parent,sizeof(parent),"/proc/self/fd/%d",c->first);
    snprintf(nested,sizeof(nested),"%s/item",parent);
    snprintf(local,sizeof(local),"/proc/thread-self/fd/%d/item",c->first);
    for (unsigned i=0;i<8;++i) {
        struct stat st;
        int result=stat(parent,&st);
        if (result) fprintf(stderr,"proc capture path=%s iteration=%u errno=%d\n",parent,i,errno);
        assert(!result);
        if (st.st_ino!=c->expected.st_ino || st.st_dev!=c->expected.st_dev)
            fprintf(stderr,"proc capture path=%s inode=%llu/%llu expected=%llu/%llu\n",parent,
                (unsigned long long)st.st_dev,(unsigned long long)st.st_ino,
                (unsigned long long)c->expected.st_dev,(unsigned long long)c->expected.st_ino);
        assert(st.st_ino==c->expected.st_ino && st.st_dev==c->expected.st_dev);
        assert(!stat(nested,&st) && st.st_size==1);
        assert(!chmod(nested,0600|(i&1 ? 0100 : 0)));
        assert(!stat(nested,&st) && (st.st_mode&0777)==(0600|(i&1 ? 0100 : 0)));
        char name[PATH_MAX];
        ssize_t size=readlink("/proc/self/cwd",name,sizeof(name));
        assert(size==(ssize_t)strlen(c->cwd) && !memcmp(name,c->cwd,size));
        wd=inotify_add_watch(watch,parent,IN_ATTRIB|IN_ONLYDIR);
        assert(wd>=0 && !inotify_rm_watch(watch,wd));
        for (int number=0;number<64;++number) assert(fcntl(number,F_GETFD)>=0);
    }
    int slot=held[--count]; assert(!close(slot));
    for (unsigned i=0;i<8;++i) {
        fd=open(nested,i&1 ? O_CLOEXEC : 0); assert(fd==slot);
        char value=0; assert(read(fd,&value,1)==1 && value=='a');
        assert(fcntl(fd,F_GETFD)==(i&1 ? FD_CLOEXEC : 0) && !close(fd));
        fd=open(local,O_RDONLY|O_CLOEXEC); assert(fd==slot);
        assert(read(fd,&value,1)==1 && value=='b' && !close(fd));
        fd=open(parent,O_PATH|O_DIRECTORY|(i&1 ? O_CLOEXEC : 0)); assert(fd==slot);
        assert(fcntl(fd,F_GETFD)==(i&1 ? FD_CLOEXEC : 0) && !close(fd));
    }
    for (int i=0;i<count;++i) assert(!close(held[i]));
    assert(!setrlimit(RLIMIT_NOFILE,&saved) && !close(watch));
    return NULL;
}
static void proc_thread_capture(void) {
    assert(!mkdir("proc-parent",0700) && !mkdir("proc-thread",0700));
    int a=open("proc-parent/item",O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC,0600);
    int b=open("proc-thread/item",O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC,0600);
    assert(a>=0 && b>=0 && write(a,"a",1)==1 && write(b,"b",1)==1 && !close(a) && !close(b));
    struct proc_context context={.first=open("proc-parent",O_PATH|O_DIRECTORY|O_CLOEXEC),
        .second=open("proc-thread",O_PATH|O_DIRECTORY|O_CLOEXEC)};
    assert(context.first>=0 && context.second>=0 && !fstat(context.first,&context.expected));
    assert(getcwd(context.cwd,sizeof(context.cwd)));
    for (unsigned protected=0;protected<2;++protected) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
            pthread_t thread;
            assert(!pthread_create(&thread,NULL,proc_thread,&context));
            /* EVENT_WAIT: join the exact producer; the process alarm fails a
             * missing completion, never substitutes a descriptor observation. */
            assert(!pthread_join(thread,NULL));
            struct stat st; assert(!fstat(context.first,&st) && st.st_ino==context.expected.st_ino);
            _exit(0);
        }
        reap(child);
    }
    assert(!close(context.first) && !close(context.second));
    assert(!unlink("proc-parent/item") && !unlink("proc-thread/item"));
    assert(!rmdir("proc-parent") && !rmdir("proc-thread"));
    puts("PASS proc task capture: unshared thread FS/files, leader aliases, protected metadata/watch/readlink, full table and one result slot");
}
static void buffer_import(void) {
    int original=open(".",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(original>=0);
    int received=import_descriptor(original); assert(!close(original));
    struct stat before; assert(!fstat(received,&before));
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_socket,4,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_connect,3,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_sendmsg,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_recvmsg,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD)
    };
    struct sock_fprog program={sizeof(filter)/sizeof(*filter),filter};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    for (unsigned i=0;i<16;++i) {
        int fd=openat(received,".",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(fd>=0);
        struct stat after; assert(!fstat(fd,&after));
        assert(before.st_dev==after.st_dev && before.st_ino==after.st_ino && !close(fd));
    }
    assert(!close(received));
    puts("PASS buffered imported dirfd: actual kernel capture without guest RPC or fabricated origin");
}
static void buffer_data(void) {
    int directory=open(".",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    int duplicate=directory<0 ? -1 : dup(directory);
    int separate=open(".",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    assert(directory>=0 && duplicate>=0 && separate>=0);
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_socket,4,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_connect,3,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_sendmsg,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_recvmsg,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD)
    };
    struct sock_fprog program={sizeof(filter)/sizeof(*filter),filter};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    for (unsigned i=0;i<16;++i) {
        int pair[2]; assert(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,pair));
        struct ucred peer={0}; socklen_t length=sizeof(peer);
        assert(!getsockopt(pair[0],SOL_SOCKET,SO_PEERCRED,&peer,&length)
            && length==sizeof(peer) && peer.pid==getpid() && peer.uid==geteuid() && peer.gid==getegid());
        put(pair[0]); get(pair[1]);
        assert(!close(pair[0]) && !close(pair[1]));
        int fd=inotify_init1(IN_CLOEXEC|IN_NONBLOCK); assert(fd>=0);
        int wd=inotify_add_watch(fd,".",IN_CREATE); assert(wd>=0);
        assert(!inotify_rm_watch(fd,wd));
        int bytes=0; assert(!ioctl(fd,FIONREAD,&bytes) && bytes==(int)sizeof(struct inotify_event));
        struct inotify_event event;
        assert(read(fd,&event,sizeof(event))==sizeof(event)
            && event.wd==wd && event.mask==IN_IGNORED && !event.len);
        assert(read(fd,&event,sizeof(event))==-1 && errno==EAGAIN && !close(fd));
    }
    struct rlimit saved,limit; assert(!getrlimit(RLIMIT_NOFILE,&saved)); limit=saved;
    limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
    int filled[64],count=0,fd;
    while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) filled[count++]=fd;
    assert(errno==EMFILE);
    for (unsigned i=0;i<16;++i) {
        assert(lseek(directory,5,SEEK_SET)==5 && lseek(duplicate,0,SEEK_CUR)==5);
        assert(lseek(duplicate,-3,SEEK_CUR)==2 && lseek(directory,0,SEEK_CUR)==2);
        assert(lseek(separate,0,SEEK_CUR)==0);
        assert(lseek(directory,-1,SEEK_SET)==-1 && errno==EINVAL);
        assert(lseek(duplicate,0,SEEK_CUR)==2);
    }
    for (int i=0;i<count;++i) assert(!close(filled[i]));
    assert(!setrlimit(RLIMIT_NOFILE,&saved));
    assert(!close(directory) && !close(duplicate) && !close(separate));
    puts("PASS buffered data inputs: socket identities, watch registration and shared directory seeks without guest RPC or free FD slots");
}
static void buffer_directory(void) {
    assert(!mkdir("cursor",0700) && !chdir("cursor"));
    for (unsigned i=0;i<32;++i) {
        char path[16]; snprintf(path,sizeof(path),"n%02u",i);
        int file=open(path,O_CREAT|O_WRONLY|O_CLOEXEC,0600); assert(file>=0 && !close(file));
    }
    int directory=open(".",O_RDONLY|O_DIRECTORY|O_CLOEXEC), alias=dup(directory);
    assert(directory>=0 && alias>=0 && !prctl(PR_SET_DUMPABLE,0));
    assert(lseek(directory,0,SEEK_SET)==0);
    char bytes[4096];
    errno=0;
    long n=syscall(SYS_getdents64,directory,(void *)1,sizeof(bytes));
    assert(n==-1 && errno==EFAULT);
    off_t position=lseek(alias,0,SEEK_CUR);
    if (position) fprintf(stderr,"FAIL rejected directory copy advanced cursor to %lld\n",(long long)position);
    assert(position==0);
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_socket,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_connect,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD)
    };
    struct sock_fprog program={sizeof(filter)/sizeof(*filter),filter};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    struct rlimit saved,limit; assert(!getrlimit(RLIMIT_NOFILE,&saved)); limit=saved;
    limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
    int filled[64],count=0,fd;
    while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) filled[count++]=fd;
    assert(errno==EMFILE);
    for (unsigned i=0;i<32;++i) {
        assert(lseek(alias,0,SEEK_SET)==0);
        n=syscall(SYS_getdents64,directory,bytes,sizeof(bytes)); assert(n>0);
        assert(lseek(alias,0,SEEK_CUR)>0);
        assert(!strcmp(bytes+19,"."));
        assert(lseek(directory,0,SEEK_SET)==0);
        assert(syscall(SYS_getdents64,alias,(void *)1,sizeof(bytes))==-1 && errno==EFAULT);
        assert(lseek(directory,0,SEEK_CUR)==0);
    }
    for (int i=0;i<count;++i) assert(!close(filled[i]));
    assert(!setrlimit(RLIMIT_NOFILE,&saved) && lseek(alias,0,SEEK_SET)==0);
    int result[2]; assert(!pipe(result)); pid_t children[4];
    for (unsigned i=0;i<4;++i) {
        children[i]=fork(); assert(children[i]>=0);
        if (!children[i]) {
            assert(!close(result[0]) && !prctl(PR_SET_DUMPABLE,i&1));
            uint64_t mask=0;
            for (;;) {
                long count=syscall(SYS_getdents64,i&1 ? directory : alias,bytes,24);
                assert(count>=0); if (!count) break;
                assert(count==24); unsigned index;
                if (!strcmp(bytes+19,".")) index=32;
                else if (!strcmp(bytes+19,"..")) index=33;
                else assert(sscanf(bytes+19,"n%u",&index)==1 && index<32);
                assert(!(mask&(1ULL<<index))); mask|=1ULL<<index;
            }
            assert(write(result[1],&mask,sizeof(mask))==sizeof(mask)); _exit(0);
        }
    }
    assert(!close(result[1])); uint64_t combined=0;
    for (unsigned i=0;i<4;++i) {
        uint64_t part; assert(read(result[0],&part,sizeof(part))==sizeof(part) && !(part&combined));
        combined|=part;
    }
    for (unsigned i=0;i<4;++i) reap(children[i]);
    assert(combined==(1ULL<<34)-1 && !close(result[0]) && !close(alias) && !close(directory));
    for (unsigned i=0;i<32;++i) {
        char path[16]; snprintf(path,sizeof(path),"n%02u",i); assert(!unlink(path));
    }
    assert(!chdir("..") && !rmdir("cursor"));
    puts("PASS protected directory copy: EFAULT, zero free FD slots, forbidden RPC, mixed broker/adapter fork readers without duplicates");
}
static unsigned export_fds(unsigned char *snapshot) {
    unsigned count=0;
    for (unsigned i=0;i<256;++i) {
        int present=fcntl((int)i,F_GETFD)>=0;
        if (snapshot) snapshot[i]=(unsigned char)present;
        count+=(unsigned)present;
    }
    return count;
}
struct export_context { int directory, channel, mode; };
static int export_child(void *argument) {
    struct export_context *c=argument;
    if (c->mode==0) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_sendmsg,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        assert(lseek(c->directory,3,SEEK_SET)==3);
    } else if (c->mode==3) {
        char number[24]; snprintf(number,sizeof(number),"%d",c->channel);
        execl(executable,executable,"export-exec",number,(char *)NULL); return 99;
    } else {
        if (c->mode==2) assert(!syscall(SYS_close_range,c->channel,c->channel,CLOSE_RANGE_UNSHARE));
        else assert(!close(c->channel));
        assert(lseek(c->directory,7,SEEK_SET)==7);
    }
    return 0;
}
static void buffer_export(int guest) {
    int directory=open(".",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    int alias=directory<0 ? -1 : dup(directory);
    int separate=open(".",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    assert(directory>=0 && alias>=0 && separate>=0);
    assert(!prctl(PR_SET_DUMPABLE,0) && !prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_socket,3,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_connect,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_recvmsg,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    struct rlimit saved,limit; assert(!getrlimit(RLIMIT_NOFILE,&saved));
    limit=saved; limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
    int filled[64],count=0,fd;
    while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) { assert(count<64); filled[count++]=fd; }
    assert(errno==EMFILE);
    off_t position=lseek(directory,5,SEEK_SET);
    if (guest) assert(position==-1 && errno==EMFILE);
    else assert(position==5);
    for (int i=0;i<count;++i) assert(!close(filled[i]));
    assert(!setrlimit(RLIMIT_NOFILE,&saved));
    unsigned char before[256]; unsigned baseline=export_fds(before);
    assert(lseek(directory,0,SEEK_SET)==0 && export_fds(NULL)==baseline+(unsigned)guest);
    int channel=-1;
    if (guest) for (unsigned i=0;i<sizeof(before);++i)
        if (!before[i] && fcntl((int)i,F_GETFD)>=0) { assert(channel<0); channel=(int)i; }
    assert(!guest || (channel>=0 && fcntl(channel,F_GETFD)==FD_CLOEXEC));
    assert(!setrlimit(RLIMIT_NOFILE,&limit)); count=0;
    while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) { assert(count<64); filled[count++]=fd; }
    assert(errno==EMFILE);
    for (int i=0;i<32;++i) {
        assert(lseek(directory,5,SEEK_SET)==5 && lseek(alias,-3,SEEK_CUR)==2);
        assert(lseek(directory,0,SEEK_CUR)==2 && lseek(separate,0,SEEK_CUR)==0);
    }
    for (int i=0;i<count;++i) assert(!close(filled[i]));
    assert(!setrlimit(RLIMIT_NOFILE,&saved));
    assert(export_fds(NULL)==baseline+(unsigned)guest);
    void *stack=malloc(1024*1024); assert(stack);
    for (int i=0;i<16;++i) {
        struct export_context c={directory,channel,0};
        pid_t child=clone(export_child,(char *)stack+1024*1024,CLONE_FILES|SIGCHLD,&c); assert(child>0);
        /* EVENT_WAIT: exact child exit; the fixture alarm bounds a stuck export. */
        int status; assert(waitpid(child,&status,0)==child);
        if (guest) assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS);
        else assert(WIFEXITED(status) && !WEXITSTATUS(status));
        assert(lseek(directory,2,SEEK_SET)==2 && export_fds(NULL)==baseline+(unsigned)guest);
    }
    if (guest) {
        for (int mode=1;mode<=3;++mode) {
            struct export_context c={directory,channel,mode};
            pid_t child=clone(export_child,(char *)stack+1024*1024,
                SIGCHLD|(mode==1 ? 0 : CLONE_FILES),&c); assert(child>0); reap(child);
            off_t restored=lseek(directory,2,SEEK_SET);
            unsigned remaining=export_fds(NULL);
            if (restored!=2 || remaining!=baseline+1) {
                fprintf(stderr,"export lifecycle mode=%d seek=%lld errno=%d fds=%u expected=%u channel=%d\n",
                    mode,(long long)restored,errno,remaining,baseline+1,channel);
                for (unsigned i=0;i<sizeof(before);++i)
                    if (!before[i] && fcntl((int)i,F_GETFD)>=0)
                        fprintf(stderr,"export lifecycle extra fd=%u flags=%d\n",i,fcntl((int)i,F_GETFD));
            }
            assert(restored==2 && remaining==baseline+1);
        }
        int copy=fcntl(channel,F_DUPFD_CLOEXEC,0); assert(copy>=0 && !close(channel));
        int replacement=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(replacement>=0);
        assert(lseek(directory,4,SEEK_SET)==4);
        assert(!syscall(SYS_close_range,copy,copy,0));
        assert(lseek(directory,6,SEEK_SET)==6);
        struct stat st; assert(!fstat(replacement,&st) && S_ISCHR(st.st_mode));
        assert(!close(replacement) && export_fds(NULL)==baseline+1);
        for (unsigned i=0;i<sizeof(before);++i)
            if (!before[i] && fcntl((int)i,F_GETFD)>=0) assert(!close((int)i));
    }
    free(stack); assert(export_fds(NULL)==baseline);
    assert(!close(directory) && !close(alias) && !close(separate));
    puts("PASS protected export: exact shared cursor, cold EMFILE, bounded infrastructure, full table reuse, terminal cancellation and table lifecycle");
}
static int metadata_export_child(void *argument) {
    int file=*(int *)argument;
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_sendmsg,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    struct stat st; assert(!fstat(file,&st)); return 0;
}
static void retained_proc_filter(int proc) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_openat,0,3),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[2])),
            BPF_JUMP(BPF_JMP|BPF_JSET|BPF_K,O_NONBLOCK,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EBUSY),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        int flags=O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW|O_LARGEFILE;
        unsigned before=export_fds(NULL);
        assert(syscall(SYS_openat,proc,"self/fd/",flags|O_NONBLOCK,0)==-1 && errno==EBUSY);
        assert(export_fds(NULL)==before);
        int fd=syscall(SYS_openat,proc,"self/fd/",flags,0); assert(fd>=0);
        assert(fcntl(fd,F_GETFD)==FD_CLOEXEC && !close(fd));
        assert(!prctl(PR_GET_DUMPABLE));
        assert(export_fds(NULL)==before);
        _exit(0);
    }
    /* EVENT_WAIT: exact filtered child exit; the fixture alarm bounds failure. */
    reap(child);
}
static void metadata_export(int guest, int managed) {
    unsigned extra=guest && !managed;
    int file=open("metadata-export",O_RDWR|O_CREAT|O_CLOEXEC,0600);
    int proc=open("/proc",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    assert(file>=0 && proc>=0);
    put(file);
    struct rlimit saved,limit;
    assert(!getrlimit(RLIMIT_NOFILE,&saved)); limit=saved; limit.rlim_cur=64;
    assert(!setrlimit(RLIMIT_NOFILE,&limit));
    if (guest) assert(!chroot("/proc/self/fdinfo"));
    assert(!prctl(PR_SET_DUMPABLE,0) && !prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    int filled[64],count=0,fd;
    while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) filled[count++]=fd;
    assert(errno==EMFILE);
    struct stat st;
    int result=fstat(file,&st);
    if (extra) assert(result==-1 && errno==EMFILE);
    else assert(!result);
    for (int i=0;i<count;++i) assert(!close(filled[i]));
    unsigned char before[256]; unsigned baseline=export_fds(before);
    assert(!fstat(file,&st) && st.st_size==1);
    assert(export_fds(NULL)==baseline+extra);
    if (managed) {
        /* Managed file metadata needs no export channel. Admit the native
         * proc input explicitly before testing reuse at the FD limit. */
        fd=openat(proc,"self/fd/",O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(fd>=0);
        char name[32]; snprintf(name,sizeof(name),"%d",file);
        assert(!fstatat(fd,name,&st,0) && st.st_size==1);
        assert(!close(fd));
        extra=1;
        assert(export_fds(NULL)==baseline+extra);
    }
    /* Admission consumes one infrastructure slot; all subsequent metadata
     * calls and proc directory opens use only their actual result slots. */
    count=0;
    while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) filled[count++]=fd;
    assert(errno==EMFILE && count);
    for (unsigned i=0;i<16;++i) assert(!fstat(file,&st) && st.st_size==1);
    assert(!close(filled[--count]));
    const int open_flags[]={
        O_RDONLY|O_DIRECTORY|O_CLOEXEC,
        O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW,
        O_RDONLY|O_DIRECTORY|O_NOFOLLOW,
        O_RDONLY|O_DIRECTORY|O_NONBLOCK|O_CLOEXEC,
        O_RDONLY|O_NOFOLLOW|O_NONBLOCK,
        O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_LARGEFILE,
        O_RDONLY|O_DIRECTORY|O_LARGEFILE|O_NOCTTY,
        O_PATH|O_DIRECTORY|O_CLOEXEC,
        O_PATH|O_NOFOLLOW
    };
    for (unsigned i=0;i<sizeof(open_flags)/sizeof(*open_flags);++i) {
        int flags=open_flags[i];
        fd=syscall(SYS_openat,proc,"self/fd/",flags,0);
        if (fd<0) fprintf(stderr,"retained proc open flags=%#x errno=%d\n",flags,errno);
        assert(fd>=0);
        assert(fcntl(fd,F_GETFD)==((flags&O_CLOEXEC) ? FD_CLOEXEC : 0));
        int actual=fcntl(fd,F_GETFL); assert(actual>=0);
        assert((actual&(O_PATH|O_NONBLOCK|O_NOFOLLOW))==(flags&(O_PATH|O_NONBLOCK|O_NOFOLLOW)));
        char name[32]; snprintf(name,sizeof(name),"%d",file);
        assert(!fstatat(fd,name,&st,0) && st.st_size==1);
        assert(!close(fd));
    }
    for (int i=0;i<count;++i) assert(!close(filled[i]));
    retained_proc_filter(proc);
    void *stack=malloc(1024*1024); assert(stack);
    for (unsigned i=0;i<16;++i) {
        pid_t pid=clone(metadata_export_child,(char *)stack+1024*1024,CLONE_FILES|SIGCHLD,&file);
        assert(pid>0);
        /* EVENT_WAIT: exporter terminal event; alarm bounds a missing exit. */
        int status; assert(waitpid(pid,&status,0)==pid);
        if (guest && !managed) assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS);
        else assert(WIFEXITED(status) && !WEXITSTATUS(status));
        assert(export_fds(NULL)==baseline+extra);
        assert(!fstat(file,&st) && st.st_size==1);
    }
    free(stack);
    for (unsigned i=0;i<sizeof(before);++i)
        if (!before[i] && fcntl((int)i,F_GETFD)>=0) assert(!close((int)i));
    assert(export_fds(NULL)==baseline);
    assert(!close(proc) && !close(file));
    puts("PASS metadata export custody: cold EMFILE, admitted full table, proc open flags/filter/result only, shared-table sender death and bounded infrastructure");
}
static const char *return_paths[2];
static int return_child(void *unused) {
    (void)unused;
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_recvmsg,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    int fd=open(return_paths[0],O_PATH|O_NOFOLLOW|O_CLOEXEC);
    assert(fd>=0 && !close(fd)); return 0;
}
static void buffer_return(int guest) {
    return_paths[0]=guest ? "/attached/null" : "/dev/null";
    return_paths[1]=guest ? "/attached/zero" : "/dev/zero";
    int directory=open(".",O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(directory>=0);
    assert(!prctl(PR_SET_DUMPABLE,0) && !prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_socket,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_connect,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    unsigned baseline=export_fds(NULL);
    for (int slots=0;slots<2;++slots) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
            limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
            int fd,last=-1;
            while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) last=fd;
            assert(errno==EMFILE && last>=0);
            if (slots) assert(!close(last));
            fd=open(return_paths[0],O_PATH|O_NOFOLLOW|O_CLOEXEC);
            if ((guest || !slots) && !(fd==-1 && errno==EMFILE))
                fprintf(stderr,"cold protected return guest=%d slots=%d fd=%d errno=%d last=%d\n",
                    guest,slots,fd,errno,last);
            if (guest || !slots) assert(fd==-1 && errno==EMFILE);
            else assert(fd==last && !close(fd));
            _exit(0);
        }
        reap(child); assert(export_fds(NULL)==baseline);
    }
    int fd=open(return_paths[0],O_PATH|O_NOFOLLOW|O_CLOEXEC);
    assert(fd>=0 && fcntl(fd,F_GETFL)==(O_PATH|O_NOFOLLOW));
    assert(export_fds(NULL)==baseline+1+(unsigned)guest && !close(fd));
    baseline+=(unsigned)guest;
    struct rlimit saved,limit; assert(!getrlimit(RLIMIT_NOFILE,&saved));
    limit=saved; limit.rlim_cur=64;
    for (int slots=0;slots<2;++slots) for (int cloexec=0;cloexec<2;++cloexec) {
        assert(!setrlimit(RLIMIT_NOFILE,&limit));
        int filled[64],count=0;
        while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) { assert(count<64); filled[count++]=fd; }
        assert(errno==EMFILE && count);
        int expected=filled[count-1];
        if (slots) assert(!close(filled[--count]));
        for (int i=0;i<16;++i) {
            fd=open(return_paths[0],O_PATH|O_NOFOLLOW|(cloexec ? O_CLOEXEC : 0));
            if (!slots) { assert(fd==-1 && errno==EMFILE); continue; }
            assert(fd==expected && fcntl(fd,F_GETFD)==(cloexec ? FD_CLOEXEC : 0));
            assert(fcntl(fd,F_GETFL)==(O_PATH|O_NOFOLLOW) && !close(fd));
        }
        for (int i=0;i<count;++i) assert(!close(filled[i]));
        assert(!setrlimit(RLIMIT_NOFILE,&saved) && export_fds(NULL)==baseline);
    }
    pid_t children[12];
    for (unsigned i=0;i<12;++i) {
        children[i]=fork(); assert(children[i]>=0);
        if (!children[i]) {
            for (unsigned j=0;j<32;++j) {
                assert(lseek(directory,0,SEEK_SET)==0);
                int fd=open(return_paths[i%2],O_PATH|O_NOFOLLOW|O_CLOEXEC);
                struct stat st;
                assert(fd>=0 && !fstat(fd,&st) && S_ISCHR(st.st_mode));
                assert(st.st_rdev==(i%2 ? 261U : 259U) && !close(fd));
            }
            assert(export_fds(NULL)==baseline); _exit(0);
        }
    }
    for (unsigned i=0;i<12;++i) reap(children[i]);
    void *stack=malloc(1024*1024); assert(stack);
    for (int i=0;i<16;++i) {
        pid_t child=clone(return_child,(char *)stack+1024*1024,CLONE_FILES|SIGCHLD,NULL);
        assert(child>0);
        /* EVENT_WAIT: receiver death while a result is queued; alarm bounds a
         * stuck table/channel turn. The surviving peer must receive next. */
        int status; assert(waitpid(child,&status,0)==child);
        if (guest) assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS);
        else assert(WIFEXITED(status) && !WEXITSTATUS(status));
        fd=open(return_paths[1],O_PATH|O_NOFOLLOW|O_CLOEXEC);
        assert(fd>=0 && !close(fd) && export_fds(NULL)==baseline);
    }
    free(stack); assert(!close(directory));
    puts("PASS protected return: O_PATH nofollow, forbidden RPC, exact 0/1 result slots, fork channel ordering and terminal receiver cleanup");
}
static void buffer_descriptors(void) {
    for (int protected=0;protected<2;++protected) for (int slots=0;slots<2;++slots)
        for (int cloexec=0;cloexec<2;++cloexec) {
            pid_t child=fork(); assert(child>=0);
            if (!child) {
                if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
                struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
                limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
                int last=-1,fd;
                while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) last=fd;
                assert(errno==EMFILE && last>=0);
                if (slots) assert(!close(last));
                struct sock_filter filter[]={
                    BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_socket,4,0),
                    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_connect,3,0),
                    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_sendmsg,2,0),
                    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_recvmsg,1,0),
                    BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
                    BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD)
                };
                struct sock_fprog program={sizeof(filter)/sizeof(*filter),filter};
                assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
                assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
                for (unsigned i=0;i<24;++i) {
                    const char *path=i%3==2 ? "/proc/self/exe" : i%3 ? "/proc/self/mounts" : "/proc/self/mountinfo";
                    fd=open(path,
                        O_RDONLY|(cloexec ? O_CLOEXEC : 0));
                    if ((!slots && (fd!=-1 || errno!=EMFILE)) || (slots && fd!=last))
                        fprintf(stderr,"buffer descriptor protected=%d slots=%d cloexec=%d iteration=%u path=%s fd=%d last=%d errno=%d\n",
                            protected,slots,cloexec,i,path,fd,last,errno);
                    if (!slots) { assert(fd==-1 && errno==EMFILE); continue; }
                    assert(fd==last && fcntl(fd,F_GETFD)==(cloexec ? FD_CLOEXEC : 0));
                    assert((fcntl(fd,F_GETFL)&O_ACCMODE)==O_RDONLY);
                    char first[32],again[32];
                    assert(read(fd,first,sizeof(first))==sizeof(first));
                    assert(lseek(fd,0,SEEK_SET)==0 && read(fd,again,sizeof(again))==sizeof(again));
                    assert(!memcmp(first,again,sizeof(first)) && !close(fd));
                }
                _exit(0);
            }
            reap(child);
        }
    puts("PASS buffered descriptors: protected receivers, executable images, exact 0/1 FD slots, flags, data and seek without RPC");
}
static void forbid_rpc(void);
struct exec_custody_case { int protected, scenario; };
static int exec_custody_child(void *argument) {
    const struct exec_custody_case *test=argument;
    if (test->protected) assert(!prctl(PR_SET_DUMPABLE,0));
    if (test->scenario>=3) {
        struct sock_filter filter[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,test->scenario==5 ? SYS_read : SYS_write,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog program={sizeof(filter)/sizeof(*filter),filter};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        int fd=test->scenario==5 ? open("umask-custody",O_CREAT|O_EXCL|O_RDONLY|O_CLOEXEC,0644)
            : open(test->scenario==3 ? "/proc/self/cmdline" : "/proc/self/auxv",O_RDONLY|O_CLOEXEC);
        if (fd<0) _exit(errno);
        assert(!close(fd));
        if (test->scenario==5) assert(!unlink("umask-custody"));
        return 0;
    }
    if (test->scenario==1) {
        char *args[]={(char *)executable,"exec-custody-target",NULL};
        for (unsigned i=0;i<64;++i)
            assert(syscall(SYS_execve,executable,args,(char *const *)1)==-1 && errno==EFAULT);
        return 0;
    }
    if (test->scenario==2) {
        forbid_rpc();
        execl(executable,executable,"exec-custody-target",(char *)NULL);
        _exit(77);
    }
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_pread64,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(filter)/sizeof(*filter),filter};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    execl(executable,executable,"exec-custody-target",(char *)NULL);
    _exit(77);
}
static int exec_custody(int guest, int proc) {
    void *stack=malloc(1024*1024); assert(stack);
    unsigned leaked=0;
    for (int scenario=proc==2 ? 5 : proc ? 3 : 0;scenario<(proc==2 ? 6 : proc ? 5 : 3);++scenario)
        for (int protected=0;protected<2;++protected) {
        unsigned char before[256]; export_fds(before);
        struct exec_custody_case test={protected,scenario};
        pid_t child=clone(exec_custody_child,(char *)stack+1024*1024,CLONE_FILES|SIGCHLD,&test); assert(child>=0);
        int status;
        /* EVENT_WAIT: exact exec caller death/completion before inspecting its
         * surviving CLONE_FILES peer; alarm fails a missing terminal event. */
        assert(waitpid(child,&status,0)==child);
        if (proc && (!WIFEXITED(status) || WEXITSTATUS(status)))
            printf("OBS proc custody scenario=%d protected=%d guest=%d status=%#x\n",scenario,protected,guest,status);
        int native_denied=!guest && scenario==4 && protected && WIFEXITED(status) && WEXITSTATUS(status)==EACCES;
        if (native_denied) puts("UNAVAILABLE native nondumpable auxv open: EACCES; guest coverage remains required");
        else if (guest && (!scenario || proc)) assert((WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS)
            || (WIFEXITED(status) && !WEXITSTATUS(status)));
        else assert(WIFEXITED(status) && !WEXITSTATUS(status));
        for (unsigned i=0;i<sizeof(before);++i) {
            int present=fcntl((int)i,F_GETFD)>=0;
            if (before[i]) assert(present);
            else if (present) {
                printf("FAIL adapter private fd=%u protected=%d scenario=%d flags=%#x\n",
                    i,protected,scenario,fcntl((int)i,F_GETFL));
                ++leaked; assert(!close((int)i));
            }
        }
    }
    free(stack);
    printf("%s %s custody leaked=%u\n",leaked ? "FAIL" : "PASS",
        proc==2 ? "umask" : proc ? "proc snapshot" : "exec preparation",leaked);
    return !!leaked;
}
static void forbid_rpc(void) {
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_socket,4,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_connect,3,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_sendmsg,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_recvmsg,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD)
    };
    struct sock_fprog program={sizeof(filter)/sizeof(*filter),filter};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
}
static void proc_snapshot(int argc, char **argv) {
    if (argc==3) {
        char argument[32769];
        for (unsigned i=0;i<sizeof(argument)-1;++i) argument[i]=(char)('a'+i%26);
        argument[sizeof(argument)-1]=0;
        execl(argv[0],argv[0],"proc-snapshot",argv[2],argument,(char *)NULL);
        assert(0);
    }
    assert(argc==4);
    char expected[40000],actual[40000]; size_t size=0;
    for (int i=0;i<argc;++i) {
        size_t n=strlen(argv[i])+1; assert(size+n<sizeof(expected));
        memcpy(expected+size,argv[i],n); size+=n;
    }
    unsigned baseline=export_fds(NULL);
    for (int protected=0;protected<2;++protected) for (int slots=0;slots<2;++slots)
        for (int cloexec=0;cloexec<2;++cloexec) for (int path=0;path<2;++path) {
            pid_t child=fork(); assert(child>=0);
            if (!child) {
                if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
                forbid_rpc();
                struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
                limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
                int last=-1,fd;
                while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) last=fd;
                assert(last>=0 && errno==EMFILE);
                if (slots) assert(!close(last));
                for (unsigned i=0;i<8;++i) {
                    fd=open("/proc/self/cmdline",(path ? O_PATH : O_RDONLY)|(cloexec ? O_CLOEXEC : 0));
                    if (!slots) { assert(fd==-1 && errno==EMFILE); continue; }
                    assert(fd==last && fcntl(fd,F_GETFD)==(cloexec ? FD_CLOEXEC : 0));
                    struct stat snapshot;
                    assert(!fstat(fd,&snapshot) && S_ISREG(snapshot.st_mode));
                    if (path) {
                        /* O_PATH pins the proc inode, not the readable snapshot. */
                        struct stat named; assert(!stat("/proc/self/cmdline",&named));
                        assert(snapshot.st_size==0 && snapshot.st_dev==named.st_dev
                            && snapshot.st_ino==named.st_ino && snapshot.st_mode==named.st_mode);
                        assert(!fstatat(fd,"",&snapshot,AT_EMPTY_PATH) && snapshot.st_ino==named.st_ino);
                        struct statx descriptor_info,named_info;
                        assert(!syscall(SYS_statx,fd,"",AT_EMPTY_PATH,STATX_BASIC_STATS|STATX_MNT_ID,&descriptor_info));
                        assert(!syscall(SYS_statx,AT_FDCWD,"/proc/self/cmdline",0,STATX_BASIC_STATS|STATX_MNT_ID,&named_info));
                        if (descriptor_info.stx_ino!=named_info.stx_ino || descriptor_info.stx_size
                                || descriptor_info.stx_mnt_id!=named_info.stx_mnt_id)
                            fprintf(stderr,"proc statx protected=%d inode=%llu/%llu size=%llu mount=%llu/%llu\n",
                                protected,(unsigned long long)descriptor_info.stx_ino,(unsigned long long)named_info.stx_ino,
                                (unsigned long long)descriptor_info.stx_size,(unsigned long long)descriptor_info.stx_mnt_id,
                                (unsigned long long)named_info.stx_mnt_id);
                        assert(descriptor_info.stx_ino==named_info.stx_ino && descriptor_info.stx_size==0
                            && descriptor_info.stx_mnt_id==named_info.stx_mnt_id);
                        assert(fcntl(fd,F_GETFL)&O_PATH);
                        assert(read(fd,actual,sizeof(actual))==-1 && errno==EBADF);
                    }
                    else {
                        assert(snapshot.st_size==(off_t)size);
                        assert(read(fd,actual,sizeof(actual))==(ssize_t)size && !memcmp(actual,expected,size));
                        assert(lseek(fd,0,SEEK_SET)==0);
                        assert(read(fd,actual,sizeof(actual))==(ssize_t)size && !memcmp(actual,expected,size));
                        assert(write(fd,"x",1)==-1 && errno==EBADF);
                    }
                    assert(!close(fd));
                }
                _exit(0);
            }
            reap(child); assert(export_fds(NULL)==baseline);
        }
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    void *unreadable=(void *)(((uintptr_t)argv[3]+2*page)&~(page-1));
    for (unsigned i=0;i<8;++i) {
        assert(!mprotect(unreadable,page,PROT_NONE));
        int fd=open("/proc/self/cmdline",O_RDONLY);
        int error=errno;
        assert(!mprotect(unreadable,page,PROT_READ|PROT_WRITE));
        assert(fd==-1 && error==EFAULT && export_fds(NULL)==baseline);
    }
    int fd=open("/proc/self/cmdline",O_RDONLY);
    assert(fd>=0 && read(fd,actual,sizeof(actual))==(ssize_t)size && !memcmp(actual,expected,size));
    assert(!close(fd) && export_fds(NULL)==baseline);
    puts("PASS proc snapshots: large argv, protected callers, exact 0/1 result slots, read-only/O_PATH, CLOEXEC, seek and rejected copying");
}
static void proc_link_slots(void) {
    char expected[PATH_MAX],path[64],actual[PATH_MAX];
    ssize_t size=readlink("/proc/self/exe",expected,sizeof(expected));
    assert(size>1 && size<(ssize_t)sizeof(expected));
    int ready[2],done[2]; assert(!pipe2(ready,O_CLOEXEC) && !pipe2(done,O_CLOEXEC));
    pid_t target=fork(); assert(target>=0);
    if (!target) { put(ready[1]); get(done[0]); _exit(0); }
    get(ready[0]);
    snprintf(path,sizeof(path),"/proc/%d/exe",target);
    unsigned baseline=export_fds(NULL);
    for (int protected=0;protected<2;++protected) {
        pid_t reader=fork(); assert(reader>=0);
        if (!reader) {
            if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
            forbid_rpc();
            struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
            limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
            while (fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0)>=0) { }
            assert(errno==EMFILE);
            struct sock_filter filter[]={
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_read,0,1),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
            };
            struct sock_fprog program={sizeof(filter)/sizeof(*filter),filter};
            assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
            for (unsigned i=0;i<16;++i) {
                size_t capacity=i%2 ? 1 : sizeof(actual);
                ssize_t n=readlink(path,actual,capacity);
                assert(n==(i%2 ? 1 : size) && !memcmp(actual,expected,(size_t)n));
            }
            assert(syscall(SYS_readlinkat,AT_FDCWD,path,actual,0)==-1 && errno==EINVAL);
            assert(syscall(SYS_readlinkat,AT_FDCWD,path,(void *)1,sizeof(actual))==-1 && errno==EFAULT);
            _exit(0);
        }
        reap(reader); assert(export_fds(NULL)==baseline);
    }
    put(done[1]); reap(target);
    assert(!close(ready[0]) && !close(ready[1]) && !close(done[0]) && !close(done[1]));
    puts("PASS foreign proc link: protected caller, full FD table, forbidden read/RPC, truncation and rejected copying");
}
static void proc_open_slots(void) {
    const char *names[]={"cmdline","auxv","exe"};
    int ready[2], done[2]; assert(!pipe2(ready,O_CLOEXEC) && !pipe2(done,O_CLOEXEC));
    pid_t target=fork(); assert(target>=0);
    if (!target) { put(ready[1]); get(done[0]); _exit(0); }
    get(ready[0]);
    unsigned baseline=export_fds(NULL);
    for (unsigned kind=0;kind<3;++kind) {
        char path[64], expected[4096];
        snprintf(path,sizeof(path),"/proc/self/%s",names[kind]);
        int source=open(path,O_RDONLY|O_CLOEXEC); assert(source>=0);
        ssize_t size=read(source,expected,sizeof(expected)); assert(size>0 && !close(source));
        snprintf(path,sizeof(path),"/proc/%d/%s",target,names[kind]);
        for (int protected=0;protected<2;++protected) for (int cloexec=0;cloexec<2;++cloexec) {
            pid_t child=fork(); assert(child>=0);
            if (!child) {
                if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
                forbid_rpc();
                struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
                limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
                int fd, last=-1;
                while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) last=fd;
                assert(errno==EMFILE && last>=0);
                assert(open(path,O_RDONLY)==-1 && errno==EMFILE);
                assert(!close(last));
                for (unsigned i=0;i<16;++i) {
                    fd=open(path,O_RDONLY|(cloexec ? O_CLOEXEC : 0));
                    assert(fd==last && fcntl(fd,F_GETFD)==(cloexec ? FD_CLOEXEC : 0));
                    char actual[4096];
                    assert(read(fd,actual,sizeof(actual))==size && !memcmp(actual,expected,(size_t)size));
                    assert(lseek(fd,0,SEEK_SET)==0);
                    assert(read(fd,actual,sizeof(actual))==size && !memcmp(actual,expected,(size_t)size));
                    assert(!close(fd));
                }
                _exit(0);
            }
            reap(child); assert(export_fds(NULL)==baseline);
        }
    }
    put(done[1]); reap(target);
    assert(!close(ready[0]) && !close(ready[1]) && !close(done[0]) && !close(done[1]));
    puts("PASS foreign proc open: protected callers, exact 0/1 slots, CLOEXEC, contents and repeated delivery");
}
static void proc_reopen_slots(void) {
    int sources[]={open("proc-reopen-slots",O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC,0600),
        open(".",O_RDONLY|O_DIRECTORY|O_CLOEXEC),open("/dev/null",O_RDONLY|O_CLOEXEC)};
    for (unsigned i=0;i<3;++i) assert(sources[i]>=0);
    assert(write(sources[0],"payload",7)==7 && !unlink("proc-reopen-slots"));
    for (unsigned source=0;source<3;++source) for (int protected=0;protected<2;++protected) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
            char path[64]; snprintf(path,sizeof(path),"/proc/thread-self/fd/%d",sources[source]);
            int warm=open(path,O_RDONLY|O_CLOEXEC); assert(warm>=0 && !close(warm));
            struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
            limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
            int fd,last=-1;
            while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) last=fd;
            assert(last>=0 && errno==EMFILE && !close(last));
            for (unsigned i=0;i<16;++i) {
                int flags=(i&1 ? O_PATH : O_RDONLY)|(i&2 ? O_CLOEXEC : 0);
                fd=open(path,flags);
                assert(fd==last && fcntl(fd,F_GETFD)==(i&2 ? FD_CLOEXEC : 0));
                struct stat original,copy;
                assert(!fstat(sources[source],&original) && !fstat(fd,&copy));
                assert(original.st_dev==copy.st_dev && original.st_ino==copy.st_ino);
                if (!source && !(flags&O_PATH)) {
                    char text[8]; assert(read(fd,text,sizeof(text))==7 && !memcmp(text,"payload",7));
                    assert(lseek(sources[source],0,SEEK_CUR)==7);
                }
                assert(!close(fd));
            }
            _exit(0);
        }
        reap(child);
    }
    for (unsigned i=0;i<3;++i) assert(!close(sources[i]));
    puts("PASS proc reopen with one slot: unlinked file, directory, native device, protected caller, flags and independent offsets");
}
static void watch_slots(void) {
    int file=open("watch-slots",O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC,0600); assert(file>=0);
    char alias[80]; snprintf(alias,sizeof(alias),"/proc/thread-self/fd/%d",file);
    assert(!symlink("watch-slots","watch-link"));
    for (int protected=0;protected<2;++protected) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
            int watch=inotify_init1(IN_NONBLOCK|IN_CLOEXEC); assert(watch>=0);
            int wd=inotify_add_watch(watch,".",IN_ATTRIB); assert(wd>=0);
            assert(!inotify_rm_watch(watch,wd));
            struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
            limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
            while (fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0)>=0) {}
            assert(errno==EMFILE);
            unsigned before=export_fds(NULL);
            for (unsigned i=0;i<16;++i) {
                wd=inotify_add_watch(watch,"watch-link",IN_ATTRIB|(i&1 ? IN_DONT_FOLLOW : 0));
                assert(wd>=0 && !inotify_rm_watch(watch,wd));
                wd=inotify_add_watch(watch,alias,IN_ATTRIB);
                assert(wd>=0 && !inotify_rm_watch(watch,wd));
                const char *proc[]={"/proc/thread-self/status","/proc/thread-self/stat","/proc/thread-self"};
                for (unsigned p=0;p<3;++p) {
                    wd=inotify_add_watch(watch,proc[p],IN_ATTRIB|(p==2 ? IN_ONLYDIR : 0));
                    int removed=wd<0 ? -1 : inotify_rm_watch(watch,wd);
                    if (wd<0 || removed) fprintf(stderr,"watch full table: protected=%d path=%s wd=%d remove=%d errno=%d\n",
                        protected,proc[p],wd,removed,errno);
                    assert(wd>=0 && !removed);
                    if (p!=2) assert(inotify_add_watch(watch,proc[p],IN_ATTRIB|IN_ONLYDIR)==-1 && errno==ENOTDIR);
                }
                assert(inotify_add_watch(watch,"watch-slots",IN_ATTRIB|IN_ONLYDIR)==-1 && errno==ENOTDIR);
                assert(inotify_add_watch(watch,"watch-missing",IN_ATTRIB)==-1 && errno==ENOENT);
                assert(export_fds(NULL)==before);
            }
            assert(!close(watch));
            _exit(0);
        }
        reap(child);
    }
    assert(!close(file) && !unlink("watch-link") && !unlink("watch-slots"));
    puts("PASS watch paths without private FDs: protected callers, admitted transport, full table and symlink selection");
}
static volatile sig_atomic_t size_signals;
static void xattr_slots(int guest) {
    int fd=open("xattr-slots",O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600); assert(fd>=0);
    char alias[80]; snprintf(alias,sizeof(alias),"/proc/thread-self/fd/%d",fd);
    assert(!mkdir("xattr-directory",0700) && !symlink("xattr-slots","xattr-link"));
    const char *name="user.magicdesk.slots";
    unsigned char value[64]; char received[256];
    for (unsigned i=0;i<sizeof(value);++i) value[i]=(unsigned char)i;
    assert(!setxattr("xattr-slots",name,value,sizeof(value),XATTR_CREATE));
    struct md_acl *acl=malloc(MD_ACL_MAX); assert(acl);
    acl->version=2;
    const unsigned count=(MD_ACL_MAX-4)/sizeof(struct md_acl_entry);
    acl->entries[0]=(struct md_acl_entry){MD_ACL_USER_OBJ,7,UINT32_MAX};
    for (unsigned i=1;i<count-3;++i) acl->entries[i]=(struct md_acl_entry){MD_ACL_USER,4,i-1};
    acl->entries[count-3]=(struct md_acl_entry){MD_ACL_GROUP_OBJ,4,UINT32_MAX};
    acl->entries[count-2]=(struct md_acl_entry){MD_ACL_MASK,4,UINT32_MAX};
    acl->entries[count-1]=(struct md_acl_entry){MD_ACL_OTHER,0,UINT32_MAX};
    void *large=malloc(MD_ACL_MAX); assert(large);
    for (int protected=0;protected<2;++protected) for (int descriptor=0;descriptor<2;++descriptor) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
            if (descriptor) assert(fgetxattr(fd,name,NULL,0)==sizeof(value));
            else forbid_rpc();
            struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
            limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
            while (fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0)>=0) {}
            assert(errno==EMFILE);
            unsigned before=export_fds(NULL);
            for (unsigned i=0;i<16;++i) {
                value[0]=(unsigned char)i;
                const char *target=i&1 ? "xattr-link" : alias;
                assert(!(descriptor ? fsetxattr(fd,name,value,sizeof(value),XATTR_REPLACE)
                    : setxattr(target,name,value,sizeof(value),XATTR_REPLACE)));
                assert((descriptor ? fgetxattr(fd,name,NULL,0) : getxattr(target,name,NULL,0))==sizeof(value));
                assert((descriptor ? fgetxattr(fd,name,received,sizeof(received))
                    : getxattr(target,name,received,sizeof(received)))==sizeof(value));
                assert(!memcmp(received,value,sizeof(value)));
                assert((descriptor ? fgetxattr(fd,name,received,1) : getxattr(target,name,received,1))==-1 && errno==ERANGE);
                assert((descriptor ? fgetxattr(fd,name,(void *)1,sizeof(value))
                    : getxattr(target,name,(void *)1,sizeof(value)))==-1 && errno==EFAULT);
                assert((descriptor ? fsetxattr(fd,name,(void *)1,sizeof(value),0)
                    : setxattr(target,name,(void *)1,sizeof(value),0))==-1 && errno==EFAULT);
                ssize_t n=descriptor ? flistxattr(fd,NULL,0) : listxattr(target,NULL,0);
                assert(n>=(ssize_t)strlen(name)+1 && (size_t)n<sizeof(received));
                assert((descriptor ? flistxattr(fd,received,n) : listxattr(target,received,n))==n);
                int found=0;
                for (size_t at=0;at<(size_t)n;at+=strlen((char *)received+at)+1)
                    found|=!strcmp((char *)received+at,name);
                assert(found);
                assert((descriptor ? flistxattr(fd,received,1) : listxattr(target,received,1))==-1 && errno==ERANGE);
                assert(lgetxattr("xattr-link",name,received,sizeof(received))==-1 && errno==ENODATA);
                assert(!(descriptor ? fremovexattr(fd,name) : removexattr(target,name)));
                assert((descriptor ? fgetxattr(fd,name,NULL,0) : getxattr(target,name,NULL,0))==-1 && errno==ENODATA);
                assert(!(descriptor ? fsetxattr(fd,name,value,sizeof(value),XATTR_CREATE)
                    : setxattr(target,name,value,sizeof(value),XATTR_CREATE)));
                assert(export_fds(NULL)==before);
            }
            if (guest && !descriptor) {
                assert(!setxattr("xattr-directory",MD_ACL_DEFAULT_NAME,acl,MD_ACL_MAX,0));
                assert(getxattr("xattr-directory",MD_ACL_DEFAULT_NAME,NULL,0)==MD_ACL_MAX);
                assert(getxattr("xattr-directory",MD_ACL_DEFAULT_NAME,large,MD_ACL_MAX)==MD_ACL_MAX);
                assert(!memcmp(acl,large,MD_ACL_MAX));
                assert(getxattr("xattr-directory",MD_ACL_DEFAULT_NAME,large,MD_ACL_MAX-1)==-1 && errno==ERANGE);
                assert(!removexattr("xattr-directory",MD_ACL_DEFAULT_NAME));
                assert(getxattr("xattr-directory",MD_ACL_DEFAULT_NAME,NULL,0)==-1 && errno==ENODATA);
                assert(export_fds(NULL)==before);
            }
            _exit(0);
        }
        reap(child);
    }
    assert(!close(fd) && !unlink("xattr-link") && !unlink("xattr-slots") && !rmdir("xattr-directory"));
    free(acl); free(large);
    puts("PASS xattr without private FDs: path and descriptor, protected full table, native values, maximum ACL, EFAULT, ERANGE and nofollow");
}
static void size_signal(int signal) { assert(signal==SIGXFSZ); ++size_signals; }
static void metadata_slots(int guest) {
    int file=open("metadata-slots",O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC,0600);
    assert(file>=0);
    assert(!mkdir("metadata-directory",0700));
    int directory=open("metadata-directory",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(directory>=0);
    int parent=open(".",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(parent>=0);
    char directory_alias[80],file_alias[80],nested_alias[100];
    snprintf(directory_alias,sizeof(directory_alias),"/proc/thread-self/fd/%d",directory);
    snprintf(file_alias,sizeof(file_alias),"/proc/thread-self/fd/%d",file);
    snprintf(nested_alias,sizeof(nested_alias),"/proc/thread-self/fd/%d/metadata-slots",parent);
    assert(!symlink("metadata-slots","metadata-link"));
    struct stat initial; assert(!fstat(file,&initial));
    for (int protected=0;protected<2;++protected) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
            forbid_rpc();
            struct sigaction action={.sa_handler=size_signal}; sigemptyset(&action.sa_mask);
            assert(!sigaction(SIGXFSZ,&action,NULL));
            struct rlimit size_limit; assert(!getrlimit(RLIMIT_FSIZE,&size_limit));
            size_limit.rlim_cur=8; assert(!setrlimit(RLIMIT_FSIZE,&size_limit));
            struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
            limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
            while (fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0)>=0) {}
            assert(errno==EMFILE);
            for (unsigned i=0;i<16;++i) {
                assert(!chdir("metadata-directory") && !chdir(".."));
                assert(!chdir(directory_alias) && !chdir(".."));
                assert(chdir("metadata-slots")==-1 && errno==ENOTDIR);
                assert(!truncate("metadata-link",(off_t)(i%8)));
                assert(!truncate(file_alias,(off_t)(i%8)));
                struct stat sized; assert(!stat("metadata-slots",&sized) && sized.st_size==(off_t)(i%8));
                assert(truncate("metadata-slots",9)==-1 && errno==EFBIG && size_signals==(sig_atomic_t)i+1);
                assert(truncate("metadata-directory",0)==-1 && errno==EISDIR);
                mode_t mask=umask(0027);
                assert(!mknodat(AT_FDCWD,"metadata-node",(i%2 ? S_IFREG : 0)|0666,0));
                struct stat node;
                assert(!lstat("metadata-node",&node) && S_ISREG(node.st_mode) && (node.st_mode&0777)==0640);
                assert(mknodat(AT_FDCWD,"metadata-node",S_IFREG|0600,0)==-1 && errno==EEXIST);
                assert(!unlink("metadata-node"));
                int created=mknodat(AT_FDCWD,"metadata-node",S_IFIFO|0666,0);
                if (!guest && created<0 && (errno==EACCES || errno==EPERM)) {
                    if (!i) printf("UNAVAILABLE native FIFO creation errno=%d; guest check remains required\n",errno);
                } else {
                    assert(!created);
                    assert(!lstat("metadata-node",&node) && S_ISFIFO(node.st_mode) && (node.st_mode&0777)==0640);
                    assert(!unlink("metadata-node"));
                }
                umask(mask);
                mode_t mode=0600|(i&1 ? 0100 : 0);
                const char *paths[]={"metadata-link",file_alias,nested_alias,"/proc/thread-self/cwd/metadata-slots"};
                const char *metadata=paths[i%4];
                char link[PATH_MAX];
                assert(readlink(file_alias,link,sizeof(link))>0);
                assert(readlink("/proc/thread-self/cwd",link,sizeof(link))>0);
                struct stat root,current,alias;
                assert(!stat("/",&root) && !stat("/proc/thread-self/root",&alias));
                assert(root.st_ino==alias.st_ino && root.st_dev==alias.st_dev);
                assert(!stat(".",&current) && !stat("/proc/thread-self/cwd",&alias));
                assert(current.st_ino==alias.st_ino && current.st_dev==alias.st_dev);
                assert(!chmod(metadata,mode));
                assert(!fchownat(AT_FDCWD,metadata,initial.st_uid,initial.st_gid,0));
                const struct timespec times[2]={{123456789,0},{123456790+i,42}};
                assert(!utimensat(AT_FDCWD,metadata,times,0));
                struct stat st; assert(!stat(metadata,&st));
                assert(!faccessat(AT_FDCWD,metadata,R_OK|W_OK,0));
                assert((st.st_mode&0777)==mode && st.st_mtim.tv_sec==times[1].tv_sec
                    && st.st_mtim.tv_nsec==42);
                assert(!fchownat(AT_FDCWD,"metadata-link",initial.st_uid,initial.st_gid,AT_SYMLINK_NOFOLLOW));
                assert(!utimensat(AT_FDCWD,"metadata-link",times,AT_SYMLINK_NOFOLLOW));
                assert(!lstat("metadata-link",&st) && S_ISLNK(st.st_mode) && st.st_mtim.tv_nsec==42);
                struct statfs by_path,by_fd;
                assert(!statfs(metadata,&by_path) && !fstatfs(file,&by_fd));
                assert(by_path.f_type==by_fd.f_type && by_path.f_bsize==by_fd.f_bsize
                    && !memcmp(&by_path.f_fsid,&by_fd.f_fsid,sizeof(by_path.f_fsid)));
                assert(chmod("metadata-absent",0600)==-1 && errno==ENOENT);
            }
            _exit(0);
        }
        reap(child);
    }
    assert(!close(parent) && !close(directory) && !close(file) && !unlink("metadata-link")
        && !unlink("metadata-slots") && !rmdir("metadata-directory"));
    puts("PASS metadata without private FDs: protected callers, no free slots, mknod, cwd, RLIMIT_FSIZE/SIGXFSZ, modes, owners, timestamps, statfs and symlinks");
}
static void proc_exe_slots(void) {
    struct stat initial; assert(!stat("/proc/self/exe",&initial));
    for (int protected=0;protected<2;++protected) for (int watched=0;watched<2;++watched) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
            int watch=-1;
            if (!watched) forbid_rpc();
            else {
                watch=inotify_init1(IN_CLOEXEC|IN_NONBLOCK); assert(watch>=0);
                int wd=inotify_add_watch(watch,"/proc/self/exe",IN_ATTRIB); assert(wd>=0);
                assert(!inotify_rm_watch(watch,wd));
            }
            struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
            limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
            while (fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0)>=0) {}
            assert(errno==EMFILE);
            for (unsigned i=0;i<16;++i) {
                const char *path=i&1 ? "/proc/thread-self/exe" : "/proc/self/exe";
                struct stat st; assert(!stat(path,&st));
                assert(st.st_ino==initial.st_ino && st.st_dev==initial.st_dev && st.st_size==initial.st_size);
                assert(!faccessat(AT_FDCWD,path,R_OK|X_OK,0));
                assert(!chmod(path,initial.st_mode&07777));
                struct statfs info; assert(!statfs(path,&info) && info.f_bsize>0);
                assert(getxattr(path,"user.shroot-missing",NULL,0)==-1 && errno==ENODATA);
                assert(listxattr(path,NULL,0)>=0);
                if (watched) {
                    int wd=inotify_add_watch(watch,path,IN_ATTRIB); assert(wd>=0);
                    assert(!inotify_rm_watch(watch,wd));
                }
                assert(stat("/proc/self/exe/not-a-directory",&st)==-1 && errno==ENOTDIR);
                assert(chdir(path)==-1 && errno==ENOTDIR);
                for (int fd=0;fd<64;++fd) assert(fcntl(fd,F_GETFD)>=0);
            }
            _exit(0);
        }
        reap(child);
    }
    puts("PASS executable metadata: protected full tables, retained identity, attributes, watch and directory errors without guest anchors");
}
static void proc_mount_paths(void) {
    for (int protected=0;protected<2;++protected) for (int slots=0;slots<2;++slots)
        for (int cloexec=0;cloexec<2;++cloexec) {
            pid_t child=fork(); assert(child>=0);
            if (!child) {
                if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
                forbid_rpc();
                struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
                limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
                int last=-1,fd;
                while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) last=fd;
                assert(last>=0 && errno==EMFILE);
                if (slots) assert(!close(last));
                for (unsigned i=0;i<16;++i) {
                    fd=open(i%2 ? "/proc/self/mountinfo" : "/proc/self/mounts",
                        O_PATH|(cloexec ? O_CLOEXEC : 0));
                    if (!slots) { assert(fd==-1 && errno==EMFILE); continue; }
                    assert(fd==last && fcntl(fd,F_GETFD)==(cloexec ? FD_CLOEXEC : 0));
                    assert(fcntl(fd,F_GETFL)&O_PATH);
                    struct stat st; assert(!fstat(fd,&st) && S_ISREG(st.st_mode));
                    assert(!close(fd));
                }
                _exit(0);
            }
            reap(child);
        }
    puts("PASS mount snapshot O_PATH: protected callers, exact 0/1 result slots, CLOEXEC and forbidden RPC");
}
static int fifo_park(void *argument) {
    int ready=*(int *)argument;
    assert(!prctl(PR_SET_DUMPABLE,0));
    forbid_rpc(); put(ready);
    int fd=open("buffer-fifo",O_RDONLY|O_CLOEXEC);
    if (fd>=0) close(fd);
    _exit(77);
}
static void buffer_fifo(void) {
    assert(!mkfifo("buffer-fifo",0600));
    for (unsigned protected=0;protected<2;++protected) for (unsigned slots=0;slots<2;++slots) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
            struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
            limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
            int fd,last=-1;
            while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) last=fd;
            assert(errno==EMFILE && last>=0);
            if (slots) assert(!close(last));
            forbid_rpc();
            for (unsigned i=0;i<16;++i) {
                fd=open("buffer-fifo",O_RDWR|(i%2 ? O_CLOEXEC : 0));
                if (!slots) assert(fd==-1 && errno==EMFILE);
                else {
                    assert(fd==last && fcntl(fd,F_GETFD)==(i%2 ? FD_CLOEXEC : 0));
                    put(fd); get(fd); assert(!close(fd));
                }
            }
            _exit(0);
        }
        reap(child);
    }
    int ready[2]; assert(!pipe2(ready,O_CLOEXEC));
    void *stack=malloc(1024*1024); assert(stack);
    unsigned baseline=export_fds(NULL);
    for (unsigned i=0;i<32;++i) {
        pid_t child=clone(fifo_park,(char *)stack+1024*1024,CLONE_FILES|SIGCHLD,&ready[1]); assert(child>=0);
        /* EVENT_WAIT: child is entering the FIFO call. Death races preparation
         * and peer wait; an unpaired read can never publish an application FD. */
        get(ready[0]); assert(!kill(child,SIGKILL));
        int status; assert(waitpid(child,&status,0)==child && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
        assert(export_fds(NULL)==baseline);
    }
    free(stack); assert(!close(ready[0]) && !close(ready[1]));
    assert(!unlink("buffer-fifo"));
    puts("PASS mapped FIFO: protected calls, forbidden RPC, exact result slots, data and shared-table cancellation without private descriptors");
}
static void buffer_paths(int protected) {
    int base=open(".",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(base>=0);
    int fd=open("buffer-path-file",O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC,0600);
    assert(fd>=0); put(fd);
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_socket,4,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_connect,3,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_sendmsg,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_recvmsg,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD)
    };
    struct sock_fprog program={sizeof(filter)/sizeof(*filter),filter};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    assert(!mkdirat(base,"buffer-path-dir",0700));
    assert(!symlinkat("buffer-path-file",base,"buffer-path-link"));
    struct timespec times[2]={{1234567890,12345},{1234567891,23456}};
    assert(!fchmod(fd,0640) && !futimens(fd,times));
    struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
    limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
    while (fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0)>=0) { }
    assert(errno==EMFILE);
    struct stat opened,named; char link[64];
    assert(!fstat(fd,&opened) && opened.st_size==1 && (opened.st_mode&0777)==0640
        && opened.st_mtim.tv_sec==times[1].tv_sec && opened.st_mtim.tv_nsec==times[1].tv_nsec);
    assert(!fstatat(base,"buffer-path-file",&named,0) && named.st_ino==opened.st_ino);
    assert(readlinkat(base,"buffer-path-link",link,sizeof(link))==16 && !memcmp(link,"buffer-path-file",16));
    assert(syscall(SYS_readlinkat,base,"buffer-path-link",(void *)1,sizeof(link))==-1 && errno==EFAULT);
    assert(!renameat(base,"buffer-path-file",base,"buffer-path-renamed"));
    assert(!fstat(fd,&named) && named.st_ino==opened.st_ino);
    assert(fstatat(base,"buffer-path-file",&named,0)==-1 && errno==ENOENT);
    assert(!unlinkat(base,"buffer-path-renamed",0) && !unlinkat(base,"buffer-path-link",0)
        && !unlinkat(base,"buffer-path-dir",AT_REMOVEDIR));
    assert(!fstat(fd,&named) && named.st_nlink==0 && !close(fd) && !close(base));
    puts("PASS buffered paths: two paths, attributes, protected inputs, zero FD slots, output fault and forbidden RPC");
}
static void path_invocation(void) {
    char cwd[PATH_MAX]; assert(getcwd(cwd,sizeof(cwd)));
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    char *memory=mmap(NULL,page*2,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(memory!=MAP_FAILED && !mprotect(memory+page,page,PROT_NONE));
    for (unsigned kind=0;kind<4;++kind) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            if (kind&1) assert(!prctl(PR_SET_DUMPABLE,0));
            if (kind&2) {
                struct sock_filter code[]={
                    BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_unlinkat,0,3),
                    BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[2])),
                    BPF_JUMP(BPF_JMP|BPF_JSET|BPF_K,1,0,1),
                    BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
                    BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
                };
                struct sock_fprog program={sizeof(code)/sizeof(*code),code};
                assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
                assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
            }
            char name[PATH_MAX],other[PATH_MAX];
            int n=snprintf(name,sizeof(name),"%s/invocation",cwd); assert(n>0 && (size_t)n<sizeof(name));
            n=snprintf(other,sizeof(other),"%s/invocation-renamed",cwd); assert(n>0 && (size_t)n<sizeof(other));
            size_t size=strlen(name)+1; assert(size<page);
            char *edge=memory+page-size; memcpy(edge,name,size);
            umask(0077);
            int fd=test_open_registers(AT_FDCWD,edge,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0666);
            assert(fd>=0 && fcntl(fd,F_GETFD)==FD_CLOEXEC);
            struct stat st,renamed;
            assert(!fstat(fd,&st) && (st.st_mode&0777)==0600 && st.st_nlink==1);
            assert(write(fd,"body",4)==4);
            assert(syscall(SYS_unlinkat,AT_FDCWD,edge,1)==-1 && errno==((kind&2) ? EPERM : EINVAL));
            assert(!stat(edge,&renamed) && renamed.st_ino==st.st_ino);
            assert(syscall(SYS_renameat2,AT_FDCWD,edge,AT_FDCWD,other,1)==0);
            assert(!stat(other,&renamed) && renamed.st_ino==st.st_ino);
            assert(syscall(SYS_openat,AT_FDCWD,memory+page,O_RDONLY,0)==-1 && errno==EFAULT);
            assert(syscall(SYS_unlinkat,AT_FDCWD,memory+page,0)==-1 && errno==EFAULT);
            assert(syscall(SYS_renameat2,AT_FDCWD,other,AT_FDCWD,memory+page,0)==-1 && errno==EFAULT);
            assert(!stat(other,&renamed) && renamed.st_size==4);
            int retained=(int)syscall(SYS_openat,AT_FDCWD,other,O_PATH,0);
            assert(retained>=0 && !fcntl(retained,F_GETFD));
            assert(!syscall(SYS_unlinkat,AT_FDCWD,other,0));
            assert(!fstat(retained,&renamed) && !renamed.st_nlink && renamed.st_ino==st.st_ino);
            assert(!close(retained) && !close(fd));
            assert(!symlink("/dev/null",name));
            fd=(int)syscall(SYS_openat,AT_FDCWD,name,O_RDWR|O_CLOEXEC,0);
            assert(fd>=0 && fcntl(fd,F_GETFD)==FD_CLOEXEC);
            assert(write(fd,"x",1)==1 && read(fd,memory,1)==0);
            assert(!close(fd) && !unlink(name));
            assert(!mkdir("invocation-dir",0777) && !mkdir("invocation-peer",0777));
            assert(!stat("invocation-dir",&st) && (st.st_mode&0777)==0700);
            int base=open("invocation-dir",O_PATH|O_DIRECTORY|O_CLOEXEC);
            int peer=open("invocation-peer",O_PATH|O_DIRECTORY|O_CLOEXEC);
            assert(base>=0 && peer>=0 && !chdir("invocation-peer"));
            fd=test_open_registers(base,"relative",O_CREAT|O_EXCL|O_RDWR,0666);
            assert(fd>=0 && !fcntl(fd,F_GETFD));
            assert(!fstat(fd,&st) && (st.st_mode&0777)==0600 && !close(fd));
            assert(!renameat(base,"relative",AT_FDCWD,"cwd"));
            assert(!fstatat(peer,"cwd",&renamed,0) && renamed.st_ino==st.st_ino);
            assert(!renameat(AT_FDCWD,"cwd",base,"relative"));
            assert(!chdir(cwd) && !rename("invocation-dir","invocation-moved"));
            assert(!fchdir(base));
            assert(!renameat(AT_FDCWD,"relative",peer,"renamed") && !unlinkat(peer,"renamed",0));
            assert(!chdir(cwd) && !close(base));
            assert(dup2(peer,base)==base);
            fd=openat(base,"reused",O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600);
            assert(fd>=0 && fcntl(fd,F_GETFD)==FD_CLOEXEC && !close(fd));
            assert(!fstatat(peer,"reused",&st,0) && !unlinkat(base,"reused",0));
            assert(openat(base,"",O_RDONLY)==-1 && errno==ENOENT);
            assert(unlinkat(base,"",0)==-1 && errno==ENOENT);
            assert(!close(base) && !close(peer));
            fd=openat(base,"invalid",O_CREAT|O_RDWR,0600);
            if (fd!=-1 || errno!=EBADF) fprintf(stderr,"closed dirfd=%d result=%d errno=%d kind=%u\n",base,fd,errno,kind);
            assert(fd==-1 && errno==EBADF);
            assert(openat(-1,"invalid",O_CREAT|O_RDWR,0600)==-1 && errno==EBADF);
            fd=openat(-1,name,O_CREAT|O_EXCL|O_RDWR,0600);
            assert(fd>=0 && !fcntl(fd,F_GETFD) && !close(fd) && !unlinkat(-1,name,0));
            assert(!rmdir("invocation-moved") && !rmdir("invocation-peer"));
            base=open("/dev",O_PATH|O_DIRECTORY); assert(base>=0);
            fd=openat(base,"null",O_RDWR|O_CLOEXEC);
            assert(fd>=0 && write(fd,"x",1)==1 && read(fd,memory,1)==0);
            assert(!close(fd) && !close(base));
            _exit(0);
        }
        reap(child);
    }
    assert(!munmap(memory,page*2));
    puts("PASS path invocation: protected memory/filter, page edges, umask, CLOEXEC, cwd/dirfd, rename, FD reuse and host boundary");
}
struct buffer_exit_peer { int ready, parked; };
static int buffer_exit_busy(void *argument) {
    const struct buffer_exit_peer *c=argument;
    int fd=open(".",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(fd>=0 && !close(fd));
    put(c->ready);
    if (c->parked>=0) { get(c->parked); return 1; }
    for (;;) {
        char cwd[4096]; assert(getcwd(cwd,sizeof(cwd)));
        fd=open(".",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(fd>=0 && !close(fd));
    }
}
static void buffer_exit(void) {
    enum { peers=8, rounds=24 };
    for (unsigned round=0;round<rounds;++round) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            int ready[2]; assert(!pipe(ready));
            struct buffer_exit_peer context={ready[1],-1};
            if (round%2) assert(!prctl(PR_SET_DUMPABLE,0));
            for (unsigned i=0;i<peers;++i) {
                void *stack=malloc(1024*1024); assert(stack);
                assert(clone(buffer_exit_busy,(char *)stack+1024*1024,
                    CLONE_VM|CLONE_FILES|CLONE_FS|CLONE_SIGHAND|CLONE_THREAD,&context)>0);
            }
            /* EVENT_WAIT: every peer has completed an O_PATH call; exit_group
             * races their subsequent adapter work. The outer alarm bounds it. */
            for (unsigned i=0;i<peers;++i) get(ready[0]);
            _exit(0);
        }
        reap(child);
    }
    puts("PASS buffered group exit: 24 groups, 8 active peers, ordinary/protected callers");
}
static void buffer_park(int protected,int busy) {
    int ready[2],parked[2]; assert(!pipe(ready) && !pipe(parked));
    struct buffer_exit_peer context={ready[1],busy ? -1 : parked[0]};
    if (busy) assert(signal(SIGTERM,SIG_IGN)!=SIG_ERR);
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    pid_t peers[8];
    for (unsigned i=0;i<8;++i) {
        void *stack=malloc(1024*1024); assert(stack);
        peers[i]=clone(buffer_exit_busy,(char *)stack+1024*1024,
            CLONE_VM|CLONE_FILES|CLONE_FS|CLONE_SIGHAND|CLONE_THREAD,&context);
        assert(peers[i]>0);
    }
    for (unsigned i=0;i<8;++i) get(ready[0]);
    printf("TRACEES %d",getpid());
    for (unsigned i=0;i<8;++i) printf(" %d",peers[i]);
    putchar('\n');
    puts("READY buffered parked group");
    /* EVENT_WAIT: deliberately no writer; the supervisor deadline must fail
     * and reap every TRACEEXIT-observed peer without outer-timeout recovery. */
    get(parked[0]); abort();
}
void test_mount_namespace_guest(void);
void test_tmpfs_guest(void);
void test_overlay_guest(void);
void test_setns_guest(void);
int test_mount_flags_guest(int, char **);
int test_uts_namespace_guest(int, char **);
void test_ipc_namespace_guest(void);
int test_user_namespace_guest(int, char **);
int test_user_namespace_lifecycle(void);
int test_user_namespace_limits(void);
int test_namespace_handles_guest(void);
int test_namespace_mounts_pid_guest(void);
int test_devpts_guest(int);
int test_cgroup_guest(int);
int test_cgroup_namespace_guest(void);
int test_device_nodes_guest(void);
int test_keyring_guest(int, char **);
int test_native_metadata_guest(void);
int test_mutation_signal_guest(const char *, int, int);
int test_shared_memory_guest(const char *, int, int);
int test_fd_result_guest(const char *, int);
int main(int argc, char **argv) {
    if (argc==2 && (!strcmp(argv[1],"processor-state") || !strcmp(argv[1],"processor-state-exec"))) {
        alarm(40); return processor_state(argv[0],!strcmp(argv[1],"processor-state-exec"));
    }
    if (argc==4 && !strcmp(argv[1],"result-custody"))
        return test_fd_result_guest(argv[2],atoi(argv[3]));
    if (argc>=2 && !strcmp(argv[1],"keyring")) return test_keyring_guest(argc,argv);
    if (argc==5 && !strcmp(argv[1],"mutation-signal"))
        return test_mutation_signal_guest(argv[2],atoi(argv[3]),atoi(argv[4]));
    if (argc==5 && !strcmp(argv[1],"shared-memory"))
        return test_shared_memory_guest(argv[2],atoi(argv[3]),atoi(argv[4]));
    if (argc==2 && !strcmp(argv[1],"tmpfs")) { test_tmpfs_guest(); return 0; }
    if (argc==2 && !strcmp(argv[1],"overlay")) { test_overlay_guest(); return 0; }
    if (argc==2 && !strcmp(argv[1],"setns")) { test_setns_guest(); return 0; }
    if (argc>=2 && !strcmp(argv[1],"mount-flags")) return test_mount_flags_guest(argc,argv);
    if (argc>=2 && !strcmp(argv[1],"uts-namespace")) return test_uts_namespace_guest(argc,argv);
    if (argc>=2 && !strcmp(argv[1],"pid-namespace")) {
        extern int test_pid_namespace_guest(int, char **);
        return test_pid_namespace_guest(argc,argv);
    }
    if (argc>=2 && !strcmp(argv[1],"user-proc")) return test_user_namespace_guest(argc,argv);
    if (argc>=2 && !strcmp(argv[1],"user-namespace")) return test_user_namespace_lifecycle();
    if (argc>=2 && !strcmp(argv[1],"user-limits")) return test_user_namespace_limits();
    if (argc>=2 && !strcmp(argv[1],"namespace-handles")) return test_namespace_handles_guest();
    if (argc>=2 && !strcmp(argv[1],"namespace-mounts-pid")) return test_namespace_mounts_pid_guest();
    if (argc>=2 && !strcmp(argv[1],"devpts")) return test_devpts_guest(0);
    if (argc>=2 && !strcmp(argv[1],"devpts-pid")) return test_devpts_guest(1);
    if (argc==2 && !strcmp(argv[1],"cgroup")) return test_cgroup_guest(0);
    if (argc==2 && !strcmp(argv[1],"device-nodes")) return test_device_nodes_guest();
    if (argc==2 && !strcmp(argv[1],"cgroup-pid")) return test_cgroup_guest(1);
    if (argc==2 && !strcmp(argv[1],"cgroup-namespace")) return test_cgroup_namespace_guest();
    if (argc==2 && !strcmp(argv[1],"native-metadata")) return test_native_metadata_guest();
    if (argc==2 && !strcmp(argv[1],"ipc-namespace")) { test_ipc_namespace_guest(); return 0; }
    setbuf(stdout, NULL); alarm(40);
    if (argc==4 && !strcmp(argv[1],"metadata-signal")) {
        extern int test_metadata_signal_guest(int);
        assert(!chdir(argv[2])); return test_metadata_signal_guest(atoi(argv[3]));
    }
    if (argc==2 && !strcmp(argv[1],"mount-namespace")) { test_mount_namespace_guest(); return 0; }
    if (argc==2 && !strcmp(argv[1],"exec-custody-target")) return 0;
    if (argc>=2 && !strcmp(argv[1],"proc-exec")) {
        extern int test_proc_exec_guest(int, char **);
        return test_proc_exec_guest(argc,argv);
    }
    if (argc == 5 && !strcmp(argv[1], "exec-undo")) {
        struct sembuf op = {.sem_num=0, .sem_op=-1, .sem_flg=SEM_UNDO};
        assert(!syscall(SYS_semop, atoi(argv[2]), &op, 1));
        put(atoi(argv[3])); get(atoi(argv[4])); return 0;
    }
    if (argc == 5 && !strcmp(argv[1], "exec-action")) {
        assert(!chdir("b")); umask(0077);
        int fd = atoi(argv[4]);
        assert(!fcntl(fd, F_SETFD, FD_CLOEXEC) && lseek(fd, 9, SEEK_SET) == 9);
        put(atoi(argv[2])); get(atoi(argv[3])); return 0;
    }
    if (argc>=3 && !strcmp(argv[1],"proc-snapshot")) { proc_snapshot(argc,argv); return 0; }
    assert(argc == 3); executable = argv[0];
    if (!strcmp(argv[1],"export-exec")) {
        assert(fcntl(atoi(argv[2]),F_GETFD)==-1 && errno==EBADF); return 0;
    }
    if (!strcmp(argv[1],"buffer-exec") || !strcmp(argv[1],"buffer-exec-protected")) {
        buffer_cwd_calls(argv[2],!strcmp(argv[1],"buffer-exec-protected")); return 0;
    }
    if (!strcmp(argv[1],"origins") || !strcmp(argv[1],"origins-protected")) {
        origins(argv[2],!strcmp(argv[1],"origins-protected")); return 0;
    }
    assert(!chdir(argv[2]));
    if (!strcmp(argv[1],"exec-custody") || !strcmp(argv[1],"exec-custody-native"))
        return exec_custody(!strcmp(argv[1],"exec-custody"),0);
    if (!strcmp(argv[1],"proc-custody") || !strcmp(argv[1],"proc-custody-native"))
        return exec_custody(!strcmp(argv[1],"proc-custody"),1);
    if (!strcmp(argv[1],"umask-custody") || !strcmp(argv[1],"umask-custody-native"))
        return exec_custody(!strcmp(argv[1],"umask-custody"),2);
    if (!strcmp(argv[1],"proc-mount-paths")) { proc_mount_paths(); return 0; }
    if (!strcmp(argv[1],"proc-exe-slots")) { proc_exe_slots(); return 0; }
    if (!strcmp(argv[1],"proc-link-slots")) { proc_link_slots(); return 0; }
    if (!strcmp(argv[1],"proc-open-slots")) { proc_open_slots(); return 0; }
    if (!strcmp(argv[1],"metadata-slots")) { metadata_slots(1); return 0; }
    if (!strcmp(argv[1],"metadata-slots-native")) { metadata_slots(0); return 0; }
    if (!strcmp(argv[1],"xattr-slots")) { xattr_slots(1); return 0; }
    if (!strcmp(argv[1],"xattr-slots-native")) { xattr_slots(0); return 0; }
    if (!strcmp(argv[1],"watch-slots")) { watch_slots(); return 0; }
    if (!strcmp(argv[1],"proc-reopen-slots")) { proc_reopen_slots(); return 0; }
    if (!strcmp(argv[1],"buffer-cwd")) { buffer_cwd(); return 0; }
    if (!strcmp(argv[1],"imported-cwd")) { imported_cwd(); return 0; }
    if (!strcmp(argv[1],"native-cwd") || !strcmp(argv[1],"native-cwd-inherited")) {
        native_cwd(!strcmp(argv[1],"native-cwd-inherited")); return 0;
    }
    if (!strcmp(argv[1],"proc-thread-capture")) { proc_thread_capture(); return 0; }
    if (!strcmp(argv[1],"capture-terminal") || !strcmp(argv[1],"capture-terminal-native")
            || !strcmp(argv[1],"capture-terminal-inherited") || !strcmp(argv[1],"capture-terminal-inherited-native")) {
        int native=strstr(argv[1],"-native")!=NULL;
        capture_terminal(!native,strstr(argv[1],"-inherited")!=NULL); return 0;
    }
    if (!strcmp(argv[1],"buffer-open")) { buffer_open(); return 0; }
    if (!strcmp(argv[1],"path-invocation")) { path_invocation(); return 0; }
    if (!strcmp(argv[1],"buffer-import")) { buffer_import(); return 0; }
    if (!strcmp(argv[1],"buffer-data")) { buffer_data(); return 0; }
    if (!strcmp(argv[1],"buffer-directory")) { buffer_directory(); return 0; }
    if (!strcmp(argv[1],"buffer-fifo")) { buffer_fifo(); return 0; }
    if (!strcmp(argv[1],"buffer-export") || !strcmp(argv[1],"buffer-export-native")) {
        buffer_export(!strcmp(argv[1],"buffer-export")); return 0;
    }
    if (!strcmp(argv[1],"metadata-export") || !strcmp(argv[1],"metadata-export-native")
            || !strcmp(argv[1],"metadata-export-managed")) {
        metadata_export(strcmp(argv[1],"metadata-export-native")!=0,
            !strcmp(argv[1],"metadata-export-managed")); return 0;
    }
    if (!strcmp(argv[1],"buffer-return") || !strcmp(argv[1],"buffer-return-native")) {
        buffer_return(!strcmp(argv[1],"buffer-return")); return 0;
    }
    if (!strcmp(argv[1],"buffer-descriptors")) { buffer_descriptors(); return 0; }
    if (!strcmp(argv[1],"buffer-paths") || !strcmp(argv[1],"buffer-paths-protected")) {
        buffer_paths(!strcmp(argv[1],"buffer-paths-protected")); return 0;
    }
    if (!strcmp(argv[1],"buffer-exit")) { buffer_exit(); return 0; }
    if (!strcmp(argv[1],"buffer-park") || !strcmp(argv[1],"buffer-park-protected")) {
        buffer_park(!strcmp(argv[1],"buffer-park-protected"),0); return 1;
    }
    if (!strcmp(argv[1],"buffer-busy") || !strcmp(argv[1],"buffer-busy-protected")) {
        buffer_park(!strcmp(argv[1],"buffer-busy-protected"),1); return 1;
    }
    if (!strcmp(argv[1], "noop")) return 0;
    if (!strcmp(argv[1], "unprivileged-root")) {
        assert(geteuid()!=0);
        int before=open(".",O_PATH|O_DIRECTORY); assert(before>=0);
        assert(chroot("/proc/self/fdinfo")==-1 && errno==EPERM);
        struct stat old,current;
        assert(!fstat(before,&old) && !stat(".",&current));
        assert(old.st_dev==current.st_dev && old.st_ino==current.st_ino);
        assert(!close(before));
        puts("PASS unprivileged chroot denied without changing cwd"); return 0;
    }
    if (!strcmp(argv[1], "restricted-descriptors")) {
        restricted_descriptors(0); restricted_descriptors(1); return 0;
    }
    if (!strcmp(argv[1], "metadata") || !strcmp(argv[1], "spawn")) { workload(argv[1]); return 0; }
    if (!strcmp(argv[1], "probe")) { probe(); return 0; }
    if (!strcmp(argv[1],"rpc-cwd") || !strcmp(argv[1],"rpc-cwd-protected")) {
        rpc_cwd(!strcmp(argv[1],"rpc-cwd-protected")); return 0;
    }
    if (!strcmp(argv[1],"custody") || !strcmp(argv[1],"custody-native")
            || !strcmp(argv[1],"custody-dirfd") || !strcmp(argv[1],"custody-nofollow")
            || !strcmp(argv[1],"custody-native-dirfd") || !strcmp(argv[1],"custody-native-nofollow")) {
        int guest=strncmp(argv[1],"custody-native",14)!=0;
        int explicit_base=!strcmp(argv[1],"custody-dirfd") || !strcmp(argv[1],"custody-native-dirfd");
        int nofollow=!strcmp(argv[1],"custody-nofollow") || !strcmp(argv[1],"custody-native-nofollow");
        unsigned leaked=0;
        for (int protected=0;protected<2;++protected) {
            leaked+=terminal_custody(guest,protected,SYS_connect,explicit_base,nofollow);
            leaked+=terminal_custody(guest,protected,SYS_recvmsg,explicit_base,nofollow);
            leaked+=terminal_custody(guest,protected,SYS_openat,explicit_base,nofollow);
        }
        return leaked ? 1 : 0;
    }
    int managed=!strcmp(argv[1],"guest-managed");
    int guest = managed || !strcmp(argv[1], "guest");
    assert(guest || !strcmp(argv[1], "native"));
    if (managed) assert(geteuid()==0);
    if (guest) { restricted_descriptors(0); restricted_descriptors(1); }
    concurrent_cwd(0);
    concurrent_cwd(CLONE_FILES);
    concurrent_aliases();
    case_sharing(0, -1, 0);
    case_sharing(CLONE_FS | CLONE_FILES, -1, 0);
    case_sharing(CLONE_FS | CLONE_FILES, CLONE_FS, 0);
    case_sharing(CLONE_FS | CLONE_FILES, CLONE_FILES, 0);
    case_sharing(CLONE_FS | CLONE_FILES, CLONE_FS | CLONE_FILES, 0);
    case_sharing(CLONE_VM | CLONE_FS | CLONE_FILES, -2, 0);
    case_sharing(CLONE_FS | CLONE_FILES, -3, 0);
    range_sharing(0,0);
    range_sharing(CLOSE_RANGE_CLOEXEC,0);
    range_sharing(CLOSE_RANGE_UNSHARE,0);
    range_sharing(CLOSE_RANGE_UNSHARE|CLOSE_RANGE_CLOEXEC,0);
    range_sharing(CLOSE_RANGE_UNSHARE,1);
    range_sharing(CLOSE_RANGE_UNSHARE|CLOSE_RANGE_CLOEXEC,1);
    if (guest) {
        int root=managed ? 2 : 1;
        case_sharing(CLONE_FS, -1, root);
        case_sharing(CLONE_FS, CLONE_FS, root);
        case_sharing(CLONE_VM | CLONE_FS, -2, root);
        case_sharing(CLONE_FS | CLONE_FILES, -4, 0);
        case_sharing(CLONE_FS, -4, root);
        undo();
        shared_undo(0);
        shared_undo(1);
    }
    puts("PASS process resources");
    return 0;
}
