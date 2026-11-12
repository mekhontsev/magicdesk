#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <linux/mount.h>
#include <linux/nsfs.h>
#include <linux/sched.h>
#include <sched.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/wait.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <stdlib.h>
#include <poll.h>
#include <sys/epoll.h>
#include <sys/inotify.h>
#include <sys/ptrace.h>
#include <sys/mman.h>
#include <stdatomic.h>
#include <time.h>

static const char *membership_proc="/proc";
static void delegation_metadata(void) {
    const char *directory="/tmp/cgv2/delegated";
    const char *path="/tmp/cgv2/delegated/cgroup.procs";
    struct stat root,tmp;
    assert(!stat("/",&root) && !stat("/tmp",&tmp));
    assert(!chmod("/",0755) && !chmod("/tmp",0755));
    assert(!mkdir(directory,0755));
    int retained=open(path,O_RDONLY|O_CLOEXEC); assert(retained>=0);
    assert(!chown(directory,1000,1000) && !chown(path,1000,1000));
    assert(!fchmod(retained,0600) && !fchown(retained,1000,1000));
    int anchor=open(path,O_PATH|O_CLOEXEC); assert(anchor>=0);
    char link[64]; snprintf(link,sizeof(link),"/proc/self/fd/%d",anchor);
    assert(!chown(link,1000,1000) && !chmod(link,0600));
    close(anchor);
    struct stat st;
    assert(!fstat(retained,&st) && st.st_uid==1000 && st.st_gid==1000 && (st.st_mode&0777)==0600);
    assert(!stat("/tmp/cgv2/delegated/cgroup.events",&st) && st.st_uid==0);
    assert(!mount(NULL,"/tmp/cgv2",NULL,MS_BIND|MS_REMOUNT|MS_RDONLY,NULL));
    assert(chmod(path,0666)<0 && errno==EROFS);
    assert(fchown(retained,0,0)<0 && errno==EROFS);
    assert(!mount(NULL,"/tmp/cgv2",NULL,MS_BIND|MS_REMOUNT,NULL));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        pid_t denied=fork(); assert(denied>=0);
        if (!denied) {
            assert(!setgid(1000) && !setuid(1000));
            int fd=open(path,O_WRONLY); assert(fd>=0);
            assert(write(fd,"0",1)<0 && errno==EACCES);
            close(fd); _exit(0);
        }
        int status;
        assert(waitpid(denied,&status,0)==denied && WIFEXITED(status) && !WEXITSTATUS(status));
        /* The delegator moves the process into the subtree before dropping
         * credentials; owning only the destination cannot authorize entry. */
        int assigned=open(path,O_WRONLY); assert(assigned>=0 && write(assigned,"0",1)==1); close(assigned);
        assert(!setgid(1000) && !setuid(1000));
        assert(chown(path,1001,(gid_t)-1)<0 && errno==EPERM);
        assert(!chmod(path,0640));
        int fd=open(path,O_WRONLY); assert(fd>=0 && write(fd,"0",1)==1); close(fd);
        assert(open("/tmp/cgv2/cgroup.procs",O_WRONLY)<0 && errno==EACCES);
        assert(!mkdir("/tmp/cgv2/delegated/child",0755));
        assert(!rmdir("/tmp/cgv2/delegated/child"));
        assert(!chmod(directory,0600));
        assert(stat(path,&st)<0 && errno==EACCES);
        assert(!chmod(directory,0755));
        _exit(0);
    }
    int status;
    /* EVENT_WAIT: child validates delegated access and denial before cleanup. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!fstat(retained,&st) && (st.st_mode&0777)==0640);
    assert(!rmdir(directory));
    assert(!fstat(retained,&st) && st.st_uid==1000 && (st.st_mode&0777)==0640 && !st.st_nlink);
    close(retained);
    assert(!chmod("/tmp",tmp.st_mode&07777) && !chmod("/",root.st_mode&07777));
}
static void read_text(const char *path,char *text,size_t size) {
    int fd=open(path,O_RDONLY|O_CLOEXEC|O_NOCTTY);
    if (fd<0) fprintf(stderr,"cgroup open %s: %s\n",path,strerror(errno));
    assert(fd>=0); ssize_t n=read(fd,text,size-1);
    if (n<0) fprintf(stderr,"cgroup read %s: %s\n",path,strerror(errno));
    assert(n>=0); text[n]=0; close(fd);
}
static int writing(const char *path,const char *text) {
    int fd=open(path,O_WRONLY|O_CLOEXEC|O_NOCTTY); assert(fd>=0);
    ssize_t n=write(fd,text,strlen(text)); int error=errno; close(fd); errno=error;
    return (int)n;
}
static struct { atomic_ulong count; atomic_uint signals; } *freeze_shared;
static void freeze_signal(int signal) { (void)signal; ++freeze_shared->signals; }
static void *freeze_worker(void *unused) {
    (void)unused;
    for (;;) atomic_fetch_add_explicit(&freeze_shared->count,1,memory_order_relaxed);
    return NULL;
}
static long long monotonic_ms(void) {
    struct timespec now; assert(!clock_gettime(CLOCK_MONOTONIC,&now));
    return (long long)now.tv_sec*1000+now.tv_nsec/1000000;
}
static void await_frozen(const char *group,int frozen) {
    char path[256]; snprintf(path,sizeof(path),"%s/cgroup.events",group);
    int fd=open(path,O_RDONLY|O_CLOEXEC); assert(fd>=0);
    long long deadline=monotonic_ms()+5000;
    for (;;) {
        char text[128]; assert(!lseek(fd,0,SEEK_SET));
        ssize_t n=read(fd,text,sizeof(text)-1); assert(n>0); text[n]=0;
        if (strstr(text,frozen ? "frozen 1\n" : "frozen 0\n")) break;
        int left=(int)(deadline-monotonic_ms()); assert(left>0);
        struct pollfd event={.fd=fd,.events=POLLPRI};
        /* EVENT_WAIT: freezer completion changes cgroup.events; expiry fails. */
        int ready=poll(&event,1,left);
        if (ready<=0) fprintf(stderr,"freezer %s wanted=%d state=%s\n",group,frozen,text);
        assert(ready==1);
    }
    close(fd);
}
static void freezer_newborn(const char *leaf) {
    int newborn[2]; assert(!pipe(newborn));
    int group=open(leaf,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(group>=0);
    struct clone_args args={.flags=CLONE_INTO_CGROUP,.exit_signal=SIGCHLD,.cgroup=(unsigned)group};
    pid_t child=(pid_t)syscall(SYS_clone3,&args,sizeof(args)); assert(child>=0);
    if (!child) { assert(write(newborn[1],"x",1)==1); _exit(0); }
    close(group); close(newborn[1]);
    await_frozen(leaf,1);
    struct pollfd pending={.fd=newborn[0],.events=POLLIN};
    assert(!poll(&pending,1,50));
    char pid[32],byte; snprintf(pid,sizeof(pid),"%d",child);
    assert(writing("/tmp/cgv2/cgroup.procs",pid)==(int)strlen(pid));
    /* EVENT_WAIT: migration out of the frozen subtree releases the newborn. */
    assert(poll(&pending,1,5000)==1 && read(newborn[0],&byte,1)==1); close(newborn[0]);
    int status;
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    await_frozen(leaf,1);
}
static void freezer_groups(void) {
    const char *base="/tmp/cgv2/freezer",*leaf="/tmp/cgv2/freezer/leaf";
    assert(!mkdir(base,0700) && !mkdir(leaf,0700));
    assert(writing("/tmp/cgv2/freezer/leaf/cgroup.freeze","1")==1);
    await_frozen(leaf,1);
    freezer_newborn(leaf);
    assert(writing("/tmp/cgv2/freezer/leaf/cgroup.freeze","0")==1);
    await_frozen(leaf,0);
    freeze_shared=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(freeze_shared!=MAP_FAILED);
    atomic_init(&freeze_shared->count,0); atomic_init(&freeze_shared->signals,0);
    int ready[2],finish[2]; assert(!pipe(ready) && !pipe(finish));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(ready[0]); close(finish[1]);
        struct sigaction action={.sa_handler=freeze_signal,.sa_flags=SA_RESTART};
        assert(!sigaction(SIGUSR1,&action,NULL));
        assert(writing("/tmp/cgv2/freezer/leaf/cgroup.procs","0")==1);
        pthread_t worker; assert(!pthread_create(&worker,NULL,freeze_worker,NULL));
        assert(write(ready[1],"x",1)==1);
        char byte; assert(read(finish[0],&byte,1)==1); _exit(0);
    }
    close(ready[1]); close(finish[0]);
    char byte; assert(read(ready[0],&byte,1)==1); close(ready[0]);
    for (unsigned i=0;i<3;++i) {
        assert(writing("/tmp/cgv2/freezer/cgroup.freeze","1")==1);
        await_frozen(base,1); await_frozen(leaf,1);
        unsigned long before=freeze_shared->count;
        unsigned signals=freeze_shared->signals;
        assert(!kill(child,SIGUSR1));
        /* Negative execution assertion over a real scheduling interval. */
        assert(!poll(NULL,0,100));
        assert(freeze_shared->count==before && freeze_shared->signals==signals);
        assert(writing("/tmp/cgv2/freezer/leaf/cgroup.freeze","1")==1);
        assert(writing("/tmp/cgv2/freezer/cgroup.freeze","0")==1);
        await_frozen(base,0); await_frozen(leaf,1);
        assert(!poll(NULL,0,30)); assert(freeze_shared->count==before);
        assert(writing("/tmp/cgv2/freezer/leaf/cgroup.freeze","0")==1);
        await_frozen(leaf,0);
        long long deadline=monotonic_ms()+5000;
        while (freeze_shared->count==before || freeze_shared->signals==signals) {
            assert(monotonic_ms()<deadline); sched_yield();
        }
    }
    int status;
    for (unsigned i=0;i<8;++i) {
        assert(!kill(child,SIGSTOP));
        assert(waitpid(child,&status,WUNTRACED)==child && WIFSTOPPED(status));
        assert(writing("/tmp/cgv2/freezer/leaf/cgroup.freeze","1")==1);
        await_frozen(leaf,1);
        assert(writing("/tmp/cgv2/freezer/leaf/cgroup.freeze","0")==1);
        await_frozen(leaf,0);
        unsigned long before=freeze_shared->count;
        assert(!poll(NULL,0,50)); assert(freeze_shared->count==before);
        assert(!kill(child,SIGCONT));
        assert(writing("/tmp/cgv2/freezer/leaf/cgroup.freeze","1")==1);
        await_frozen(leaf,1);
        if (i!=7) {
            assert(writing("/tmp/cgv2/freezer/leaf/cgroup.freeze","0")==1);
            await_frozen(leaf,0);
        }
    }
    assert(!kill(child,SIGKILL));
    assert(waitpid(child,&status,0)==child && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
    await_frozen(leaf,1);
    freezer_newborn(leaf);
    close(finish[1]); assert(!munmap(freeze_shared,4096));
    assert(!rmdir(leaf) && !rmdir(base));
    puts("PASS cgroup freezer: threads, blocked IO, nested thaw, signals, job control, fatal exit, clone3 and migration");
}
static void named_hierarchies(void) {
    const char *first="/tmp/cg-named",*alias="/tmp/cg-named-alias",*other="/tmp/cg-other";
    assert(!mkdir(first,0700) && !mkdir(alias,0700) && !mkdir(other,0700));
    int host=open("/proc/self/ns/cgroup",O_RDONLY|O_CLOEXEC); assert(host>=0);
    for (unsigned i=0;i<4;++i) {
        const char *invalid[]={"none","none,devices,name=md","none,name=bad/name","none,name=md,name=other"};
        assert(mount("none",first,"cgroup",0,invalid[i])<0 && errno==EINVAL);
    }
    assert(!mount("none",first,"cgroup",0,"none,name=md-session"));
    int context=syscall(SYS_fsopen,"cgroup",FSOPEN_CLOEXEC); assert(context>=0);
    assert(!syscall(SYS_fsconfig,context,FSCONFIG_SET_FLAG,"none",NULL,0));
    assert(!syscall(SYS_fsconfig,context,FSCONFIG_SET_STRING,"name","md-session",0));
    assert(!syscall(SYS_fsconfig,context,FSCONFIG_CMD_CREATE,NULL,NULL,0));
    int detached=syscall(SYS_fsmount,context,FSMOUNT_CLOEXEC,0); assert(detached>=0);
    assert(!syscall(SYS_move_mount,detached,"",AT_FDCWD,alias,MOVE_MOUNT_F_EMPTY_PATH));
    close(detached); close(context);
    struct stat a,b; struct statfs fs;
    assert(!stat(first,&a) && !stat(alias,&b) && a.st_ino==b.st_ino && a.st_dev==b.st_dev);
    assert(!statfs(first,&fs) && fs.f_type==CGROUP_SUPER_MAGIC);
    assert(access("/tmp/cg-named/devices.allow",F_OK)<0 && errno==ENOENT);
    assert(access("/tmp/cg-named/cgroup.controllers",F_OK)<0 && errno==ENOENT);
    assert(!mount("none",other,"cgroup",0,"name=md-independent,none"));
    assert(!mkdir("/tmp/cg-named/child",0700));
    assert(writing("/tmp/cg-named/child/cgroup.procs","0")==1);
    char text[8192]; read_text("/proc/self/cgroup",text,sizeof(text));
    assert(strstr(text,":name=md-session:/child\n") && strstr(text,":name=md-independent:/\n"));
    int retained=open("/tmp/cg-named-alias/child/tasks",O_RDONLY); assert(retained>=0);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        read_text("/proc/self/cgroup",text,sizeof(text));
        assert(strstr(text,":name=md-session:/child\n"));
        assert(!unshare(CLONE_NEWCGROUP|CLONE_NEWNS));
        read_text("/proc/self/cgroup",text,sizeof(text));
        assert(strstr(text,":name=md-session:/\n"));
        assert(!umount2(alias,0));
        assert(!mount("none",alias,"cgroup",0,"name=md-session"));
        assert(!stat(alias,&a) && !stat("/tmp/cg-named/child",&b) && a.st_ino==b.st_ino);
        assert(writing("/tmp/cg-named-alias/tasks","0")==1);
        assert(!setns(host,CLONE_NEWCGROUP));
        read_text("/proc/self/cgroup",text,sizeof(text));
        assert(strstr(text,":name=md-session:/child\n"));
        _exit(0);
    }
    int status;
    /* EVENT_WAIT: fork inherits both hierarchies and isolates namespace roots. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(rmdir("/tmp/cg-named/child")<0 && errno==EBUSY);
    assert(writing("/tmp/cg-named-alias/cgroup.procs","0")==1);
    assert(!rmdir("/tmp/cg-named/child"));
    assert(!fstat(retained,&a) && !a.st_nlink);
    assert(read(retained,text,sizeof(text))<0 && errno==ENODEV); close(retained);
    assert(!umount2(first,0) && !umount2(alias,0) && !umount2(other,0));
    assert(!rmdir(first) && !rmdir(alias) && !rmdir(other)); close(host);
    puts("PASS named cgroup hierarchies: legacy/FD mounts, independent membership, fork, namespace roots and retired controls");
}
static void namespace_delegation(void) {
    assert(!mkdir("/tmp/cg-options",0700));
    assert(mount("none","/tmp/cg-options","cgroup2",0,"nsdelegate,bogus")<0 && errno==EINVAL);
    assert(!mount("none","/tmp/cg-options","cgroup2",0,"nsdelegate,memory_recursiveprot"));
    int context=syscall(SYS_fsopen,"cgroup2",FSOPEN_CLOEXEC); assert(context>=0);
    assert(!syscall(SYS_fsconfig,context,FSCONFIG_SET_FLAG,"nsdelegate",NULL,0));
    assert(!syscall(SYS_fsconfig,context,FSCONFIG_SET_FLAG,"memory_recursiveprot",NULL,0));
    assert(syscall(SYS_fsconfig,context,FSCONFIG_SET_STRING,"nsdelegate","1",0)<0 && errno==EINVAL);
    assert(syscall(SYS_fsconfig,context,FSCONFIG_SET_FLAG,"memory_hugetlb_accounting",NULL,0)<0 && errno==EINVAL);
    assert(!syscall(SYS_fsconfig,context,FSCONFIG_CMD_CREATE,NULL,NULL,0)); close(context);
    char text[8192]; read_text("/proc/self/mountinfo",text,sizeof(text));
    assert(strstr(text,",nsdelegate,memory_recursiveprot"));
    assert(!mkdir("/tmp/cgv2/ns-delegated",0700));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        char parent[32]; snprintf(parent,sizeof(parent),"%d",getppid());
        assert(writing("/tmp/cgv2/ns-delegated/cgroup.procs","0")==1);
        int retained=open("/tmp/cgv2/ns-delegated/cgroup.freeze",O_WRONLY); assert(retained>=0);
        assert(!unshare(CLONE_NEWCGROUP|CLONE_NEWNS));
        assert(!umount2("/tmp/cg-options",0));
        assert(!mount("none","/tmp/cg-options","cgroup2",0,NULL));
        read_text("/proc/self/mountinfo",text,sizeof(text));
        assert(strstr(text,",nsdelegate,memory_recursiveprot"));
        assert(writing("/tmp/cg-options/cgroup.freeze","0")<0 && errno==EPERM);
        assert(writing("/tmp/cg-options/cgroup.procs","0")==1);
        assert(writing("/tmp/cg-options/cgroup.procs",parent)<0 && errno==ENOENT);
        assert(writing("/tmp/cgv2/cgroup.procs","0")<0 && errno==ENOENT);
        assert(write(retained,"0",1)==1); close(retained);
        assert(!umount2("/tmp/cg-options",0)); _exit(0);
    }
    int status;
    /* EVENT_WAIT: child checks namespace boundaries and retained opener authority. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!rmdir("/tmp/cgv2/ns-delegated"));
    assert(!umount2("/tmp/cg-options",0));
    assert(!mount("none","/tmp/cg-options","cgroup2",0,NULL));
    assert(!umount2("/tmp/cg-options",0) && !rmdir("/tmp/cg-options"));
    puts("PASS cgroup mount options, namespace delegation, migration and opener authority");
}
static unsigned events_read(int fd,int first,int second) {
    union { struct inotify_event event; char data[1024]; } buffer;
    ssize_t n=read(fd,buffer.data,sizeof(buffer.data));
    if (n<0) { assert(errno==EAGAIN); return 0; }
    unsigned found=0;
    for (size_t at=0;at<(size_t)n;) {
        struct inotify_event *event=(void *)(buffer.data+at);
        assert(event->wd==first || event->wd==second);
        if (event->mask&IN_MODIFY) found|=event->wd==first ? 1 : 2;
        if (event->mask&IN_DELETE_SELF) found|=4;
        if (event->mask&IN_IGNORED) found|=8;
        at+=sizeof(*event)+event->len;
    }
    return found;
}
static void event_state(int fd,int populated) {
    char data[128]; assert(!lseek(fd,0,SEEK_SET));
    ssize_t n=read(fd,data,sizeof(data)-1); assert(n>0); data[n]=0;
    assert(strstr(data,populated ? "populated 1\n" : "populated 0\n"));
    struct pollfd p={.fd=fd,.events=POLLPRI}; assert(!poll(&p,1,0));
}
static void population_events(void) {
    const char *base="/tmp/cgv2/events",*leaf="/tmp/cgv2/events/leaf";
    assert(!mkdir(base,0700) && !mkdir(leaf,0700));
    assert(!mkdir("/tmp/cgv2/events/sibling",0700));
    int watch=inotify_init1(IN_NONBLOCK|IN_CLOEXEC); assert(watch>=0);
    int first=inotify_add_watch(watch,"/tmp/cgv2/events/cgroup.events",IN_MODIFY|IN_DELETE_SELF|IN_EXCL_UNLINK);
    int second=inotify_add_watch(watch,"/tmp/cgv2/events/leaf/cgroup.events",IN_MODIFY|IN_DELETE_SELF);
    assert(first>0 && second>0 && first!=second);
    for (unsigned path_only=0;path_only<2;++path_only) {
        int target=open("/tmp/cgv2/events/cgroup.events",O_CLOEXEC|(path_only ? O_PATH : O_RDONLY));
        assert(target>=0);
        int duplicate=dup(target); assert(duplicate>=0); close(target);
        char link[64]; snprintf(link,sizeof(link),"/proc/self/fd/%d",duplicate);
        assert(inotify_add_watch(watch,link,IN_MODIFY|IN_MASK_ADD)==first);
        close(duplicate);
    }
    int fd=open("/tmp/cgv2/events/cgroup.events",O_RDONLY|O_CLOEXEC); assert(fd>=0);
    int ep=epoll_create1(EPOLL_CLOEXEC); assert(ep>=0);
    struct epoll_event event={.events=EPOLLPRI,.data.fd=fd};
    assert(!epoll_ctl(ep,EPOLL_CTL_ADD,fd,&event));
    /* New kernfs OFDs are ready before any change, including a no-op thaw. */
    assert(writing("/tmp/cgv2/events/cgroup.freeze","0")==1);
    assert(epoll_wait(ep,&event,1,0)==1 && (event.events&(EPOLLPRI|EPOLLERR))==(EPOLLPRI|EPOLLERR));
    int alias=dup(fd); assert(alias>=0);
    event_state(alias,0); close(alias);
    assert(!epoll_wait(ep,&event,1,0));
    int separate=open("/tmp/cgv2/events/cgroup.events",O_RDONLY|O_CLOEXEC); assert(separate>=0);
    struct pollfd fresh={.fd=separate,.events=POLLPRI};
    assert(poll(&fresh,1,0)==1 && (fresh.revents&POLLPRI));
    assert(!epoll_wait(ep,&event,1,0));
    event_state(separate,0); close(separate);
    assert(writing("/tmp/cgv2/events/cgroup.freeze","0")==1);
    assert(!epoll_wait(ep,&event,1,0));
    assert(writing("/tmp/cgv2/events/leaf/cgroup.procs","0")==1);
    assert(events_read(watch,first,second)==3);
    assert(epoll_wait(ep,&event,1,0)==1 && (event.events&(EPOLLPRI|EPOLLERR))==(EPOLLPRI|EPOLLERR));
    event_state(fd,1);
    assert(writing("/tmp/cgv2/events/sibling/cgroup.procs","0")==1);
    assert(events_read(watch,first,second)==2); event_state(fd,1);
    assert(writing("/tmp/cgv2/cgroup.procs","0")==1);
    assert(events_read(watch,first,second)==1); event_state(fd,0);
    int ready[2],finish[2]; assert(!pipe(ready) && !pipe(finish));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(ready[0]); close(finish[1]);
        assert(writing("/tmp/cgv2/events/leaf/cgroup.procs","0")==1);
        assert(write(ready[1],"x",1)==1);
        char byte; assert(read(finish[0],&byte,1)==1); _exit(0);
    }
    close(ready[1]); close(finish[0]);
    char byte; assert(read(ready[0],&byte,1)==1);
    assert(events_read(watch,first,second)==3); event_state(fd,1);
    assert(write(finish[1],"x",1)==1); close(finish[1]); close(ready[0]);
    /* EVENT_WAIT: actual provider wake, not just a synchronous readiness query. */
    assert(epoll_wait(ep,&event,1,5000)==1 && (event.events&EPOLLPRI));
    /* EVENT_WAIT: process exit must publish the empty subtree before reaping. */
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(events_read(watch,first,second)==3);
    assert(epoll_wait(ep,&event,1,0)==1 && (event.events&EPOLLPRI)); event_state(fd,0);
    assert(!rmdir(leaf)); assert(events_read(watch,first,second)==12);
    assert(!rmdir("/tmp/cgv2/events/sibling") && !rmdir(base));
    assert(events_read(watch,first,second)==12);
    close(ep); close(fd); close(watch);
}
static void stopped_newborn_membership(void) {
    const char *group="/tmp/cgv2/newborn";
    assert(!mkdir(group,0700));
    pid_t parent=fork(); assert(parent>=0);
    if (!parent) {
        assert(writing("/tmp/cgv2/newborn/cgroup.procs","0")==1);
        assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL));
        raise(SIGSTOP);
        pid_t child=fork(); assert(child>=0); _exit(0);
    }
    int status;
    /* EVENT_WAIT: debugger owns the initial and fork stops; alarm bounds failure. */
    assert(waitpid(parent,&status,0)==parent && WIFSTOPPED(status));
    assert(!ptrace(PTRACE_SETOPTIONS,parent,NULL,(void *)PTRACE_O_TRACEFORK));
    assert(!ptrace(PTRACE_CONT,parent,NULL,NULL));
    assert(waitpid(parent,&status,0)==parent && (unsigned)status>>16==PTRACE_EVENT_FORK);
    unsigned long child=0;
    assert(!ptrace(PTRACE_GETEVENTMSG,parent,NULL,&child) && child);
    assert(waitpid((pid_t)child,&status,__WALL)==(pid_t)child && WIFSTOPPED(status));
    assert(!ptrace(PTRACE_CONT,parent,NULL,NULL));
    assert(waitpid(parent,&status,0)==parent && WIFEXITED(status) && !WEXITSTATUS(status));
    int fd=open("/tmp/cgv2/newborn/cgroup.events",O_RDONLY); assert(fd>=0);
    event_state(fd,1);
    char text[256],pid[32]; snprintf(pid,sizeof(pid),"%lu\n",child);
    read_text("/tmp/cgv2/newborn/cgroup.procs",text,sizeof(text)); assert(strstr(text,pid));
    assert(rmdir(group)<0 && errno==EBUSY);
    assert(!ptrace(PTRACE_CONT,(pid_t)child,NULL,NULL));
    assert(waitpid((pid_t)child,&status,__WALL)==(pid_t)child && WIFEXITED(status) && !WEXITSTATUS(status));
    event_state(fd,0); close(fd); assert(!rmdir(group));
}
static void *thread_membership(void *unused) {
    (void)unused;
    char text[8192],pid[32],tid[32];
    snprintf(pid,sizeof(pid),"%d\n",getpid());
    snprintf(tid,sizeof(tid),"%ld\n",syscall(SYS_gettid));
    read_text("/tmp/cgv2/child/cgroup.procs",text,sizeof(text));
    char *first=strstr(text,pid); assert(first && !strstr(first+strlen(pid),pid));
    read_text("/tmp/cgv2/child/cgroup.threads",text,sizeof(text));
    assert(strstr(text,pid) && strstr(text,tid));
    read_text("/tmp/cgv2/child/pids.current",text,sizeof(text)); assert(atoi(text)==2);
    return NULL;
}
static unsigned long long resource_value(const char *path,const char *key) {
    char text[4096]; read_text(path,text,sizeof(text));
    char *at=key ? strstr(text,key) : text; assert(at);
    if (key) at+=strlen(key);
    return strtoull(at,NULL,10);
}
static atomic_int accounting_ready,accounting_done;
static void *limited_thread(void *context) {
    char byte; assert(read(*(int *)context,&byte,1)==1); return NULL;
}
static void pids_limit(void) {
    const char *max="/tmp/cgv2/limited/pids.max";
    assert(!mkdir("/tmp/cgv2/limited",0700));
    assert(writing(max,"0")==1);
    /* Migration is organizational, even when the destination is over limit. */
    assert(writing("/tmp/cgv2/limited/cgroup.procs","0")==1);
    assert(resource_value("/tmp/cgv2/limited/pids.current",NULL)==1);
    int events=open("/tmp/cgv2/limited/pids.events",O_RDONLY); assert(events>=0);
    struct pollfd watched={.fd=events,.events=POLLPRI}; char text[256];
    assert(poll(&watched,1,0)==1 && (watched.revents&POLLPRI));
    assert(read(events,text,sizeof(text))==6);
    assert(poll(&watched,1,0)==0);
    assert(fork()<0 && errno==EAGAIN);
    assert(poll(&watched,1,0)==1 && (watched.revents&POLLPRI));
    assert(resource_value("/tmp/cgv2/limited/pids.events","max ")==1);
    /* clone3 charges the destination, even when its caller cannot fork here. */
    assert(!mkdir("/tmp/cgv2/target",0700));
    assert(writing("/tmp/cgv2/target/pids.max","1")==1);
    int target=open("/tmp/cgv2/target",O_RDONLY|O_DIRECTORY); assert(target>=0);
    struct clone_args args={.flags=CLONE_INTO_CGROUP,.exit_signal=SIGCHLD,.cgroup=(unsigned)target};
    pid_t into=(pid_t)syscall(SYS_clone3,&args,sizeof(args)); assert(into>=0);
    if (!into) {
        assert(resource_value("/tmp/cgv2/target/pids.current",NULL)==1);
        assert(fork()<0 && errno==EAGAIN); _exit(0);
    }
    int into_status; assert(waitpid(into,&into_status,0)==into && WIFEXITED(into_status) && !WEXITSTATUS(into_status));
    assert(resource_value("/tmp/cgv2/target/pids.current",NULL)==0);
    close(target); assert(!rmdir("/tmp/cgv2/target"));
    assert(writing(max,"2")==1);
    pid_t child=fork(); assert(child>=0);
    if (!child) _exit(0);
    siginfo_t info={0};
    /* EVENT_WAIT: keep a zombie charged until the real wait/reap completes. */
    assert(!waitid(P_PID,child,&info,WEXITED|WNOWAIT) && info.si_pid==child);
    assert(resource_value("/tmp/cgv2/limited/pids.current",NULL)==2);
    assert(fork()<0 && errno==EAGAIN);
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status));
    assert(resource_value("/tmp/cgv2/limited/pids.current",NULL)==1);
    child=fork(); assert(child>=0);
    if (!child) _exit(0);
    assert(waitpid(child,&status,0)==child && WIFEXITED(status));
    assert(resource_value("/tmp/cgv2/limited/pids.events","max ")==2);
    assert(writing("/tmp/cgv2/cgroup.procs","0")==1);
    close(events);
    assert(writing("/tmp/cgv2/limited/cgroup.subtree_control","+pids")==5);
    assert(!mkdir("/tmp/cgv2/limited/leaf",0700));
    assert(writing("/tmp/cgv2/limited/leaf/cgroup.procs","0")==1);
    assert(writing(max,"1")==1);
    assert(fork()<0 && errno==EAGAIN);
    assert(resource_value("/tmp/cgv2/limited/leaf/pids.events","max ")==1);
    assert(writing(max,"3")==1);
    assert(writing("/tmp/cgv2/limited/leaf/pids.max","2")==1);
    int finish[2]; assert(!pipe(finish)); pthread_t thread,extra;
    assert(!pthread_create(&thread,NULL,limited_thread,&finish[0]));
    assert(resource_value("/tmp/cgv2/limited/pids.current",NULL)==2);
    assert(pthread_create(&extra,NULL,limited_thread,&finish[0])==EAGAIN);
    assert(write(finish[1],"x",1)==1); assert(!pthread_join(thread,NULL));
    close(finish[0]); close(finish[1]);
    assert(resource_value("/tmp/cgv2/limited/leaf/pids.current",NULL)==1);
    assert(writing("/tmp/cgv2/cgroup.procs","0")==1);
    assert(!rmdir("/tmp/cgv2/limited/leaf") && !rmdir("/tmp/cgv2/limited"));
    puts("PASS cgroup pids enforcement: hierarchy, threads, clone3, EAGAIN, events, zombie charge and reap");
}
static void *accounting_thread(void *unused) {
    (void)unused;
    atomic_fetch_add(&accounting_ready,1);
    while (!atomic_load(&accounting_done)) sched_yield();
    return NULL;
}
static void memory_events(void) {
    const char *group="/tmp/cgv2/memory-events",*subtree="/tmp/cgv2/cgroup.subtree_control";
    for (unsigned disable=0;disable<2;++disable) {
        assert(!mkdir(group,0700));
        int watch=inotify_init1(IN_NONBLOCK|IN_CLOEXEC); assert(watch>=0);
        int ep=epoll_create1(EPOLL_CLOEXEC); assert(ep>=0);
        int files[2],watches[2];
        char text[256],path[128];
        for (unsigned i=0;i<2;++i) {
            snprintf(path,sizeof(path),"%s/memory.events%s",group,i ? ".local" : "");
            watches[i]=inotify_add_watch(watch,path,IN_MODIFY|IN_DELETE_SELF); assert(watches[i]>0);
            files[i]=open(path,O_RDONLY|O_CLOEXEC); assert(files[i]>=0);
            struct epoll_event event={.events=EPOLLPRI,.data.fd=files[i]};
            assert(!epoll_ctl(ep,EPOLL_CTL_ADD,files[i],&event));
            assert(epoll_wait(ep,&event,1,0)==1 && (event.events&EPOLLPRI));
            ssize_t n=read(files[i],text,sizeof(text)-1); assert(n>0); text[n]=0;
            assert(strstr(text,"oom 0\n") && strstr(text,"oom_kill 0\n"));
            assert(!epoll_wait(ep,&event,1,0));
        }
        assert(writing("/tmp/cgv2/memory-events/memory.max","1")==1);
        struct epoll_event event;
        assert(!epoll_wait(ep,&event,1,0));
        assert(read(watch,text,sizeof(text))<0 && errno==EAGAIN);
        if (disable) assert(writing(subtree,"-memory")==7);
        else assert(!rmdir(group));
        assert(events_read(watch,watches[0],watches[1])==12);
        assert(epoll_wait(ep,&event,1,0)==1 && (event.events&EPOLLHUP));
        for (unsigned i=0;i<2;++i) {
            assert(pread(files[i],text,sizeof(text),0)<0 && errno==ENODEV); close(files[i]);
        }
        close(ep); close(watch);
        if (disable) { assert(!rmdir(group)); assert(writing(subtree,"+memory")==7); }
    }
}
static void resource_controllers(void) {
    const char *root="/tmp/cgv2/cgroup.subtree_control";
    const char *child="/tmp/cgv2/resources";
    char text[4096];
    assert(!mkdir(child,0700));
    assert(access("/tmp/cgv2/resources/cpu.max",F_OK)<0 && errno==ENOENT);
    assert(writing(root,"+cpu +cpuset +memory")==20);
    memory_events();
    read_text("/tmp/cgv2/resources/cgroup.controllers",text,sizeof(text));
    assert(!strcmp(text,"cpu cpuset memory pids\n"));
    assert(writing("/tmp/cgv2/resources/cpu.max","25000 100000")==12);
    assert(writing("/tmp/cgv2/resources/cpu.max","7 0")<0 && errno==EINVAL);
    read_text("/tmp/cgv2/resources/cpu.max",text,sizeof(text)); assert(!strcmp(text,"25000 100000\n"));
    int snapshot=open("/tmp/cgv2/resources/cpu.max",O_RDONLY); assert(snapshot>=0);
    assert(read(snapshot,text,5)==5 && !memcmp(text,"25000",5));
    assert(writing("/tmp/cgv2/resources/cpu.max","max 200000")==10);
    assert(read(snapshot,text,sizeof(text))==8 && !memcmp(text," 100000\n",8));
    assert(!lseek(snapshot,0,SEEK_SET));
    assert(read(snapshot,text,sizeof(text))==11 && !memcmp(text,"max 200000\n",11));
    close(snapshot);
    assert(writing("/tmp/cgv2/resources/cpu.weight","0")<0 && errno==EINVAL);
    assert(writing("/tmp/cgv2/resources/cpu.weight","42")==2);
    assert(writing("/tmp/cgv2/resources/memory.max","1")==1);
    assert(writing("/tmp/cgv2/resources/memory.high","max")==3);
    assert(writing("/tmp/cgv2/resources/memory.oom.group","2")<0 && errno==EINVAL);
    read_text("/tmp/cgv2/cpuset.cpus.effective",text,sizeof(text)); assert(*text && strcmp(text,"\n"));
    assert(writing("/tmp/cgv2/resources/cpuset.cpus",text)==(ssize_t)strlen(text));
    assert(writing("/tmp/cgv2/resources/cpuset.cpus","999999")<0 && errno==ERANGE);
    assert(writing("/tmp/cgv2/resources/cgroup.subtree_control","+memory")==7);
    assert(writing(root,"-memory -cpu")<0 && errno==EBUSY);
    assert(!access("/tmp/cgv2/resources/cpu.max",F_OK));
    assert(writing("/tmp/cgv2/resources/cgroup.subtree_control","-memory")==7);
    int retired=open("/tmp/cgv2/resources/cpu.max",O_RDONLY); assert(retired>=0);
    assert(writing(root,"-cpu")==4);
    assert(read(retired,text,sizeof(text))<0 && errno==ENODEV);
    assert(!access("/tmp/cgv2/resources/memory.max",F_OK));
    assert(writing(root,"+cpu")==4);
    assert(read(retired,text,sizeof(text))<0 && errno==ENODEV); close(retired);
    read_text("/tmp/cgv2/resources/cpu.max",text,sizeof(text)); assert(!strcmp(text,"max 100000\n"));
    assert(writing("/tmp/cgv2/resources/cgroup.procs","0")==1);
    unsigned long long base=resource_value("/tmp/cgv2/resources/memory.current",NULL);
    assert(base>0);
    pthread_t threads[4];
    for (unsigned i=0;i<4;++i) assert(!pthread_create(&threads[i],NULL,accounting_thread,NULL));
    while (atomic_load(&accounting_ready)!=4) sched_yield();
    unsigned long long threaded=resource_value("/tmp/cgv2/resources/memory.current",NULL);
    assert(threaded<base*2+4*1024*1024);
    atomic_store(&accounting_done,1);
    for (unsigned i=0;i<4;++i) assert(!pthread_join(threads[i],NULL));
    unsigned long long before=resource_value("/tmp/cgv2/resources/cpu.stat","usage_usec ");
    pid_t worker=fork(); assert(worker>=0);
    if (!worker) {
        struct timespec start,now;
        assert(!clock_gettime(CLOCK_PROCESS_CPUTIME_ID,&start));
        do {
            for (volatile unsigned i=0;i<10000;++i) {}
            assert(!clock_gettime(CLOCK_PROCESS_CPUTIME_ID,&now));
        } while ((now.tv_sec-start.tv_sec)*1000000000LL+now.tv_nsec-start.tv_nsec<100000000);
        _exit(0);
    }
    int status;
    /* EVENT_WAIT: final accounting must survive the child's actual exit/reap. */
    assert(waitpid(worker,&status,0)==worker && WIFEXITED(status) && !WEXITSTATUS(status));
    unsigned long long after=resource_value("/tmp/cgv2/resources/cpu.stat","usage_usec ");
    assert(after>=before+80000);
    assert(resource_value("/tmp/cgv2/cpu.stat","usage_usec ")>=after);
    assert(writing("/tmp/cgv2/cgroup.procs","0")==1);
    assert(resource_value("/tmp/cgv2/resources/memory.current",NULL)==0);
    assert(resource_value("/tmp/cgv2/resources/cpu.stat","usage_usec ")>=after);
    assert(!rmdir(child));
    assert(writing(root,"-cpu -cpuset -memory")==20);
    puts("PASS guest cgroup resources: policies, controller retirement, process RSS, terminal CPU and hierarchy");
}
static void expect_group_text(const char *path,const char *expected) {
    char text[4096]; read_text(path,text,sizeof(text)); assert(!strcmp(text,expected));
}
struct threaded_fixture { int ready[2],finish[2]; };
static void *threaded_worker(void *context) {
    struct threaded_fixture *fixture=context;
    assert(writing("/tmp/cgv2/thread-domain/worker/cgroup.threads","0")==1);
    pid_t tid=syscall(SYS_gettid);
    assert(write(fixture->ready[1],&tid,sizeof(tid))==sizeof(tid));
    char byte;
    /* EVENT_WAIT: parent validates and migrates this live thread; alarm bounds failure. */
    assert(read(fixture->finish[0],&byte,1)==1);
    char text[4096]; read_text("/proc/thread-self/cgroup",text,sizeof(text));
    assert(strstr(text,"0::/thread-domain\n"));
    return NULL;
}
static void threaded_membership(void) {
    const char *root="/tmp/cgv2/cgroup.subtree_control";
    const char *domain="/tmp/cgv2/thread-domain",*worker="/tmp/cgv2/thread-domain/worker";
    const char *leaf="/tmp/cgv2/thread-domain/worker/leaf";
    assert(access("/tmp/cgv2/cgroup.type",F_OK)<0 && errno==ENOENT);
    assert(writing(root,"+cpu +memory +pids")==18);
    assert(!mkdir(domain,0700) && !mkdir(worker,0700));
    assert(writing("/tmp/cgv2/thread-domain/cgroup.subtree_control","+cpu +pids")==10);
    assert(writing("/tmp/cgv2/thread-domain/worker/cgroup.type","threaded")==8);
    expect_group_text("/tmp/cgv2/thread-domain/cgroup.type","domain threaded\n");
    expect_group_text("/tmp/cgv2/thread-domain/worker/cgroup.type","threaded\n");
    expect_group_text("/tmp/cgv2/thread-domain/worker/cgroup.controllers","cpu pids\n");
    assert(writing("/tmp/cgv2/thread-domain/cgroup.subtree_control","+memory")<0 && errno==EOPNOTSUPP);
    assert(writing("/tmp/cgv2/thread-domain/worker/cgroup.type","domain")<0 && errno==EINVAL);
    assert(writing("/tmp/cgv2/thread-domain/worker/cgroup.type","threaded")==8);
    assert(writing("/tmp/cgv2/thread-domain/cgroup.procs","0")==1);
    assert(!mkdir(leaf,0700));
    expect_group_text("/tmp/cgv2/thread-domain/worker/leaf/cgroup.type","domain invalid\n");
    assert(writing("/tmp/cgv2/thread-domain/worker/leaf/cgroup.procs","0")<0 && errno==EOPNOTSUPP);
    assert(writing("/tmp/cgv2/thread-domain/worker/cgroup.subtree_control","+pids")==5);
    assert(writing("/tmp/cgv2/thread-domain/worker/leaf/cgroup.subtree_control","+pids")<0 && errno==EOPNOTSUPP);
    assert(writing("/tmp/cgv2/thread-domain/worker/leaf/cgroup.type","threaded")==8);
    struct threaded_fixture fixture;
    assert(!pipe(fixture.ready) && !pipe(fixture.finish));
    pthread_t thread; assert(!pthread_create(&thread,NULL,threaded_worker,&fixture));
    pid_t tid; assert(read(fixture.ready[0],&tid,sizeof(tid))==sizeof(tid));
    char number[32],text[4096]; snprintf(number,sizeof(number),"%d\n",tid);
    expect_group_text("/tmp/cgv2/thread-domain/worker/cgroup.threads",number);
    assert(writing("/tmp/cgv2/thread-domain/worker/leaf/cgroup.threads",number)==(int)strlen(number));
    expect_group_text("/tmp/cgv2/thread-domain/worker/cgroup.threads","");
    expect_group_text("/tmp/cgv2/thread-domain/worker/leaf/cgroup.threads",number);
    assert(writing("/tmp/cgv2/cgroup.threads",number)<0 && errno==EOPNOTSUPP);
    snprintf(number,sizeof(number),"%d\n",getpid());
    expect_group_text("/tmp/cgv2/thread-domain/cgroup.procs",number);
    int fd=open("/tmp/cgv2/thread-domain/worker/cgroup.procs",O_RDONLY); assert(fd>=0);
    assert(read(fd,text,sizeof(text))<0 && errno==EOPNOTSUPP); close(fd);
    assert(writing("/tmp/cgv2/thread-domain/worker/leaf/cgroup.procs","0")==1);
    expect_group_text("/tmp/cgv2/thread-domain/cgroup.threads","");
    expect_group_text("/tmp/cgv2/thread-domain/cgroup.procs",number);
    assert(resource_value("/tmp/cgv2/thread-domain/memory.current",NULL)>0);
    assert(access("/tmp/cgv2/thread-domain/worker/memory.current",F_OK)<0 && errno==ENOENT);
    assert(writing("/tmp/cgv2/thread-domain/cgroup.procs","0")==1);
    assert(write(fixture.finish[1],"x",1)==1 && !pthread_join(thread,NULL));
    for (unsigned i=0;i<2;++i) { close(fixture.ready[i]); close(fixture.finish[i]); }
    assert(!rmdir(leaf) && !rmdir(worker));
    expect_group_text("/tmp/cgv2/thread-domain/cgroup.type","domain threaded\n");
    assert(writing("/tmp/cgv2/thread-domain/cgroup.subtree_control","-cpu -pids")==10);
    expect_group_text("/tmp/cgv2/thread-domain/cgroup.type","domain\n");
    assert(writing("/tmp/cgv2/thread-domain/cgroup.subtree_control","+memory")<0 && errno==EBUSY);
    assert(writing("/tmp/cgv2/thread-domain/cgroup.type","threaded")<0 && errno==EOPNOTSUPP);
    assert(writing("/tmp/cgv2/cgroup.procs","0")==1);
    assert(!rmdir(domain));
    /* The hierarchy root may host both domains and threads. Converting a child
     * retires domain-only controls, including already opened descriptions. */
    assert(!mkdir(domain,0700));
    fd=open("/tmp/cgv2/thread-domain/memory.current",O_RDONLY); assert(fd>=0);
    assert(writing("/tmp/cgv2/thread-domain/cgroup.type","threaded")==8);
    assert(read(fd,text,sizeof(text))<0 && errno==ENODEV); close(fd);
    assert(access("/tmp/cgv2/thread-domain/memory.current",F_OK)<0 && errno==ENOENT);
    assert(writing(root,"-memory")==7 && writing(root,"+memory")==7);
    assert(access("/tmp/cgv2/thread-domain/memory.current",F_OK)<0 && errno==ENOENT);
    assert(!rmdir(domain));
    assert(writing(root,"-cpu -memory -pids")==18);
    puts("PASS cgroup threads: domain topology, per-thread and process migration, memory domain, retirement and restoration");
}
int test_cgroup_guest(int pid_namespace) {
    if (pid_namespace) {
        alarm(45); assert(!unshare(CLONE_NEWPID));
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            assert(!unshare(CLONE_NEWNS));
            membership_proc="/tmp/cg-proc";
            assert(!mkdir(membership_proc,0700));
            int mounted=mount("proc",membership_proc,"proc",0,NULL);
            if (mounted) perror("PID cgroup proc mount");
            assert(!mounted);
            int result=test_cgroup_guest(0);
            assert(!umount2(membership_proc,0) && !rmdir(membership_proc));
            _exit(result);
        }
        int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
        puts("PASS guest cgroup: PID namespace membership"); return 0;
    }
    alarm(45); assert(!unshare(CLONE_NEWNS));
    struct timespec cpu_start,cpu_now;
    assert(!clock_gettime(CLOCK_PROCESS_CPUTIME_ID,&cpu_start));
    do {
        for (volatile unsigned i=0;i<10000;++i) {}
        assert(!clock_gettime(CLOCK_PROCESS_CPUTIME_ID,&cpu_now));
    } while ((cpu_now.tv_sec-cpu_start.tv_sec)*1000000000LL+cpu_now.tv_nsec-cpu_start.tv_nsec<150000000);
    /* The restricted proc provider can start shared accounting before cgroup
     * exists. Mounting must retain that CPU history instead of charging twice. */
    int statistics=open("/proc/stat",O_RDONLY|O_CLOEXEC); assert(statistics>=0);
    char first; assert(read(statistics,&first,1)==1 && first=='c'); close(statistics);
    assert(!mkdir("/tmp/cgv2",0700) && !mkdir("/tmp/cgv1",0700));
    int r=mount("none","/tmp/cgv2","cgroup2",MS_NOSUID|MS_NOEXEC|MS_NODEV,NULL);
    if (r) perror("cgroup2 mount"); assert(!r);
    assert(resource_value("/tmp/cgv2/cpu.stat","usage_usec ")>=100000);
    assert(!umount2("/tmp/cgv2",0));
    assert(!mount("none","/tmp/cgv2","cgroup2",MS_NOSUID|MS_NOEXEC|MS_NODEV|MS_REC|MS_RELATIME,NULL));
    assert(!mount("none","/tmp/cgv1","cgroup",MS_NOSUID|MS_NOEXEC|MS_NODEV,"devices"));
    population_events();
    stopped_newborn_membership();
    delegation_metadata();
    namespace_delegation();
    named_hierarchies();
    threaded_membership();
    struct statfs st; assert(!statfs("/tmp/cgv2",&st) && st.f_type==CGROUP2_SUPER_MAGIC);
    assert(!statfs("/tmp/cgv1",&st) && st.f_type==CGROUP_SUPER_MAGIC);
    char text[8192]; read_text("/tmp/cgv2/cgroup.controllers",text,sizeof(text)); assert(!strcmp(text,"cpu cpuset memory pids\n"));
    read_text("/tmp/cgv1/devices.list",text,sizeof(text)); assert(!strcmp(text,"a *:* rwm\n"));
    assert(writing("/tmp/cgv1/devices.allow","a *:* rwm")==9);
    assert(writing("/tmp/cgv1/devices.deny","a")<0 && errno==EOPNOTSUPP);
    assert(!mkdir("/tmp/cgv2/child",0700));
    assert(access("/tmp/cgv2/child/pids.max",F_OK)<0 && errno==ENOENT);
    assert(writing("/tmp/cgv2/child/cgroup.subtree_control","+pids")<0 && errno==ENOENT);
    assert(writing("/tmp/cgv2/cgroup.subtree_control","+pids")==5);
    int terminated=open("/tmp/cgv2/cgroup.subtree_control",O_WRONLY); assert(terminated>=0);
    assert(write(terminated,"-pids",6)==6);
    read_text("/tmp/cgv2/cgroup.subtree_control",text,sizeof(text)); assert(!strcmp(text,"\n"));
    assert(write(terminated,"+pids\0ignored",13)==13); close(terminated);
    read_text("/tmp/cgv2/cgroup.subtree_control",text,sizeof(text)); assert(!strcmp(text,"pids\n"));
    read_text("/tmp/cgv2/child/pids.max",text,sizeof(text)); assert(!strcmp(text,"max\n"));
    assert(writing("/tmp/cgv2/child/pids.max","0")==1);
    read_text("/tmp/cgv2/child/pids.max",text,sizeof(text)); assert(!strcmp(text,"0\n"));
    assert(writing("/tmp/cgv2/child/pids.max","max")==3);
    assert(writing("/tmp/cgv2/child/pids.max","-1")<0 && errno==EINVAL);
    assert(writing("/tmp/cgv2/child/pids.max","18446744073709551616")<0 && errno==ERANGE);
    assert(writing("/tmp/cgv2/cgroup.subtree_control","-pids +io")<0 && errno==EOPNOTSUPP);
    read_text("/tmp/cgv2/cgroup.subtree_control",text,sizeof(text)); assert(!strcmp(text,"pids\n"));
    assert(writing("/tmp/cgv2/child/cgroup.procs","0")==1);
    read_text("/tmp/cgv2/child/pids.current",text,sizeof(text)); assert(!strcmp(text,"1\n"));
    assert(writing("/tmp/cgv2/child/cgroup.subtree_control","+pids")==5);
    expect_group_text("/tmp/cgv2/child/cgroup.type","domain threaded\n");
    assert(writing("/tmp/cgv2/child/cgroup.subtree_control","-pids")==5);
    expect_group_text("/tmp/cgv2/child/cgroup.type","domain\n");
    read_text("/proc/self/cgroup",text,sizeof(text)); assert(strstr(text,"0::/child\n"));
    read_text("/tmp/cgv2/child/cgroup.procs",text,sizeof(text));
    char pid[32]; snprintf(pid,sizeof(pid),"%d\n",getpid()); assert(strstr(text,pid));
    pthread_t worker;
    assert(!pthread_create(&worker,NULL,thread_membership,NULL));
    assert(!pthread_join(worker,NULL));
    read_text("/tmp/cgv2/child/pids.events",text,sizeof(text)); assert(!strcmp(text,"max 0\n"));
    assert(open("/tmp/cgv2/child/cgroup.procs",O_RDWR|O_CREAT|O_EXCL,0600)<0 && errno==EEXIST);
    int fd=open("/tmp/cgv2/child/cgroup.procs",O_RDWR|O_CLOEXEC); assert(fd>=0);
    assert(!fstatfs(fd,&st) && st.f_type==CGROUP2_SUPER_MAGIC);
    int copy=dup(fd); assert(copy>=0);
    char alias[64]; snprintf(alias,sizeof(alias),"/proc/self/fd/%d",copy);
    read_text(alias,text,sizeof(text)); assert(strstr(text,pid));
    assert(!mkdir("/tmp/cgv2/zombie",0700) && !mkdir("/tmp/cgv1/zombie",0700));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        read_text("/proc/self/cgroup",text,sizeof(text)); assert(strstr(text,"0::/child\n"));
        assert(write(copy,"0",1)==1);
        assert(writing("/tmp/cgv2/zombie/cgroup.procs","0")==1);
        assert(writing("/tmp/cgv1/zombie/cgroup.procs","0")==1); _exit(0);
    }
    /* EVENT_WAIT: retain the exited child until proc inspection; alarm bounds failure. */
    siginfo_t exited={0};
    assert(!waitid(P_PID,child,&exited,WEXITED|WNOWAIT) && exited.si_pid==child);
    char exited_path[64]; snprintf(exited_path,sizeof(exited_path),"%s/%d/cgroup",membership_proc,child);
    read_text(exited_path,text,sizeof(text));
    assert(strstr(text,"0::/zombie\n") && strstr(text,"1:devices:/\n"));
    int membership=open(exited_path,O_RDONLY|O_CLOEXEC); assert(membership>=0);
    assert(!rmdir("/tmp/cgv2/zombie") && !rmdir("/tmp/cgv1/zombie"));
    read_text(exited_path,text,sizeof(text)); assert(strstr(text,"0::/zombie (deleted)\n"));
    ssize_t length=read(membership,text,sizeof(text)-1); assert(length>=0); text[length]=0;
    assert(strstr(text,"0::/zombie (deleted)\n")); close(membership);
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(rmdir("/tmp/cgv2/child")<0 && errno==EBUSY);
    assert(writing("/tmp/cgv2/cgroup.freeze","1")<0 && errno==EOPNOTSUPP);
    assert(writing("/tmp/cgv2/cgroup.subtree_control","+io")<0 && errno==EOPNOTSUPP);
    assert(!mount(NULL,"/tmp/cgv2",NULL,MS_BIND|MS_REMOUNT|MS_RDONLY,NULL));
    assert(write(copy,"0",1)<0 && errno==EROFS);
    assert(!mount(NULL,"/tmp/cgv2",NULL,MS_BIND|MS_REMOUNT,NULL));
    close(copy); close(fd);
    assert(writing("/tmp/cgv2/cgroup.procs","0")==1);
    assert(writing("/tmp/cgv2/child/cgroup.subtree_control","+pids")==5);
    assert(!mkdir("/tmp/cgv2/child/nested",0700));
    assert(writing("/tmp/cgv2/cgroup.subtree_control","-pids")<0 && errno==EBUSY);
    assert(writing("/tmp/cgv2/child/nested/pids.max","123")==3);
    read_text("/tmp/cgv2/child/nested/pids.max",text,sizeof(text)); assert(!strcmp(text,"123\n"));
    int retired_pids=open("/tmp/cgv2/child/nested/pids.max",O_RDONLY); assert(retired_pids>=0);
    struct stat retired_before,retired_after;
    assert(!fstat(retired_pids,&retired_before));
    assert(writing("/tmp/cgv2/child/cgroup.subtree_control","-pids")==5);
    assert(read(retired_pids,text,sizeof(text))<0 && errno==ENODEV);
    assert(access("/tmp/cgv2/child/nested/pids.max",F_OK)<0 && errno==ENOENT);
    assert(writing("/tmp/cgv2/child/cgroup.subtree_control","+pids")==5);
    assert(!fstat(retired_pids,&retired_after) && !retired_after.st_nlink
        && retired_after.st_ino==retired_before.st_ino && retired_after.st_mode==retired_before.st_mode);
    assert(read(retired_pids,text,sizeof(text))<0 && errno==ENODEV); close(retired_pids);
    read_text("/tmp/cgv2/child/nested/pids.max",text,sizeof(text)); assert(!strcmp(text,"max\n"));
    assert(!rmdir("/tmp/cgv2/child/nested"));
    assert(!rmdir("/tmp/cgv2/child"));
    for (unsigned i=0;i<32;++i) {
        assert(!mkdir("/tmp/cgv2/reused",0700));
        int retired=open("/tmp/cgv2/reused/cgroup.procs",O_RDONLY|O_CLOEXEC); assert(retired>=0);
        assert(!rmdir("/tmp/cgv2/reused"));
        assert(!mkdir("/tmp/cgv2/reused",0700));
        assert(read(retired,text,sizeof(text))<0 && errno==ENODEV);
        close(retired); assert(!rmdir("/tmp/cgv2/reused"));
    }
    read_text("/proc/cgroups",text,sizeof(text)); assert(strstr(text,"devices\t1\t1\t1"));
    freezer_groups();
    pids_limit();
    resource_controllers();
    assert(!umount2("/tmp/cgv2",0) && !umount2("/tmp/cgv1",0));
    assert(!rmdir("/tmp/cgv2") && !rmdir("/tmp/cgv1"));
    puts("PASS guest cgroup: membership, fork, typed IO, readonly, pids limits and permissive devices"); return 0;
}
static void expect_membership(int fd,const char *v2,const char *v1) {
    char text[8192],expected[8192];
    assert(!lseek(fd,0,SEEK_SET));
    ssize_t n=read(fd,text,sizeof(text)-1); assert(n>=0); text[n]=0;
    snprintf(expected,sizeof(expected),"0::%s\n1:devices:%s\n",v2,v1);
    if (strcmp(text,expected)) fprintf(stderr,"membership: got %s expected %s",text,expected);
    assert(!strcmp(text,expected));
}
static void same_directory(const char *a,const char *b) {
    struct stat sa,sb;
    assert(!stat(a,&sa) && !stat(b,&sb));
    assert(sa.st_dev==sb.st_dev && sa.st_ino==sb.st_ino);
}
static void reap_namespace(pid_t child) {
    int status;
    /* EVENT_WAIT: exact child completion; fixture alarm bounds failure. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
}
int test_cgroup_namespace_guest(void) {
    alarm(60);
    /* The imported fixture starts as shell-owned 0700. The unmapped user
     * namespace needs search access, independently of namespace capability. */
    assert(!chmod("/",0755));
    assert(!unshare(CLONE_NEWNS));
    const char *mounts[]={"/tmp/cgn-v2","/tmp/cgn-v1","/tmp/cgn-local","/tmp/cgn-devices","/tmp/cgn-context"};
    for (unsigned i=0;i<5;++i) assert(!mkdir(mounts[i],0700));
    int host=open("/proc/self/ns/cgroup",O_RDONLY|O_CLOEXEC); assert(host>=0);
    struct stat initial; assert(!fstat(host,&initial));
    assert(ioctl(host,NS_GET_NSTYPE)==CLONE_NEWCGROUP);
    assert(ioctl(host,NS_GET_PARENT)<0 && errno==EINVAL);
    int owner=ioctl(host,NS_GET_USERNS); assert(owner>=0);
    struct stat a,b; assert(!fstat(owner,&a) && !stat("/proc/self/ns/user",&b));
    assert(a.st_ino==b.st_ino && a.st_dev==b.st_dev); close(owner);
    assert(!mount("none",mounts[0],"cgroup2",0,NULL));
    assert(!mount("none",mounts[1],"cgroup",0,"devices"));
    assert(!mkdir("/tmp/cgn-v2/child",0700) && !mkdir("/tmp/cgn-v2/sibling",0700));
    assert(!mkdir("/tmp/cgn-v1/device-child",0700));
    assert(writing("/tmp/cgn-v2/child/cgroup.procs","0")==1);
    assert(writing("/tmp/cgn-v1/device-child/cgroup.procs","0")==1);
    int membership=open("/proc/self/cgroup",O_RDONLY|O_CLOEXEC); assert(membership>=0);
    expect_membership(membership,"/child","/device-child");
    int context=syscall(SYS_fsopen,"cgroup2",FSOPEN_CLOEXEC); assert(context>=0);
    assert(!unshare(CLONE_NEWCGROUP));
    assert(!stat("/proc/self/ns/cgroup",&a) && a.st_ino!=initial.st_ino);
    expect_membership(membership,"/","/");
    assert(!mkdir("/tmp/cgn-proc",0755));
    assert(!mount("none","/tmp/cgn-proc","proc",0,NULL));
    int proc_member=open("/tmp/cgn-proc/self/cgroup",O_RDONLY); assert(proc_member>=0);
    expect_membership(proc_member,"/","/");
    char controllers[1024]; read_text("/tmp/cgn-proc/cgroups",controllers,sizeof(controllers));
    assert(strstr(controllers,"devices\t1\t2\t1"));
    int local=open("/proc/thread-self/ns/cgroup",O_RDONLY|O_CLOEXEC); assert(local>=0);
    int copy=dup(local); assert(copy>=0);
    char path[128]; snprintf(path,sizeof(path),"/proc/self/fd/%d",copy);
    int reopened=open(path,O_RDONLY|O_CLOEXEC); assert(reopened>=0); close(copy);
    assert(!fstat(reopened,&b) && a.st_ino==b.st_ino);
    assert(!mount("none",mounts[2],"cgroup2",0,NULL));
    assert(!mount("none",mounts[3],"cgroup",0,"devices"));
    same_directory(mounts[2],"/tmp/cgn-v2/child");
    same_directory(mounts[3],"/tmp/cgn-v1/device-child");
    assert(!mkdir("/tmp/cgn-local/nested",0700));
    same_directory("/tmp/cgn-local/nested","/tmp/cgn-v2/child/nested");
    assert(!syscall(SYS_fsconfig,context,FSCONFIG_CMD_CREATE,NULL,NULL,0));
    int detached=syscall(SYS_fsmount,context,FSMOUNT_CLOEXEC,0); assert(detached>=0); close(context);
    assert(!syscall(SYS_move_mount,detached,"",AT_FDCWD,mounts[4],MOVE_MOUNT_F_EMPTY_PATH)); close(detached);
    same_directory(mounts[4],mounts[0]);
    assert(writing("/tmp/cgn-local/nested/cgroup.procs","0")==1);
    expect_membership(membership,"/nested","/");
    expect_membership(proc_member,"/nested","/");
    pid_t child=syscall(SYS_clone,CLONE_NEWCGROUP|SIGCHLD,0,NULL,0,NULL); assert(child>=0);
    if (!child) {
        int self=open("/proc/self/cgroup",O_RDONLY); assert(self>=0);
        expect_membership(self,"/","/");
        assert(!stat("/proc/self/ns/cgroup",&b) && b.st_ino!=a.st_ino);
        assert(!setns(host,CLONE_NEWCGROUP));
        expect_membership(self,"/child/nested","/device-child"); close(self); _exit(0);
    }
    reap_namespace(child);
    expect_membership(membership,"/nested","/");
    assert(!setns(host,0)); expect_membership(membership,"/child/nested","/device-child");
    assert(!setns(reopened,CLONE_NEWCGROUP)); expect_membership(membership,"/nested","/");
    assert(setns(reopened,CLONE_NEWNET)<0 && errno==EINVAL);
    assert(writing("/tmp/cgn-v2/sibling/cgroup.procs","0")==1);
    expect_membership(membership,"/../sibling","/");
    assert(writing("/tmp/cgn-v2/cgroup.procs","0")==1);
    expect_membership(membership,"/..","/");
    child=fork(); assert(child>=0);
    if (!child) {
        assert(!unshare(CLONE_NEWUSER|CLONE_NEWCGROUP));
        assert(setns(host,CLONE_NEWCGROUP)<0 && errno==EPERM);
        assert(ioctl(host,NS_GET_USERNS)<0 && errno==EPERM);
        int self=open("/proc/self/ns/cgroup",O_RDONLY);
        if (self<0) perror("child cgroup namespace open");
        assert(self>=0);
        owner=ioctl(self,NS_GET_USERNS); assert(owner>=0);
        assert(!fstat(owner,&a) && !stat("/proc/self/ns/user",&b) && a.st_ino==b.st_ino);
        close(owner); close(self); _exit(0);
    }
    reap_namespace(child);
    assert(!setns(host,CLONE_NEWCGROUP));
    int ready[2],finish[2]; assert(!pipe(ready) && !pipe(finish));
    child=fork(); assert(child>=0);
    if (!child) {
        close(ready[0]); close(finish[1]);
        assert(writing("/tmp/cgn-v2/sibling/cgroup.procs","0")==1);
        assert(!unshare(CLONE_NEWCGROUP));
        assert(write(ready[1],"x",1)==1);
        char byte;
        /* EVENT_WAIT: parent captures this namespace; alarm bounds failure. */
        assert(read(finish[0],&byte,1)==1); _exit(0);
    }
    close(ready[1]); close(finish[0]);
    char byte;
    /* EVENT_WAIT: child publishes namespace identity; alarm bounds failure. */
    assert(read(ready[0],&byte,1)==1); close(ready[0]);
    snprintf(path,sizeof(path),"/proc/%d/ns/cgroup",child);
    int retained=open(path,O_RDONLY|O_CLOEXEC); assert(retained>=0);
    assert(write(finish[1],"x",1)==1); close(finish[1]); reap_namespace(child);
    assert(!setns(retained,CLONE_NEWCGROUP)); expect_membership(membership,"/..","/");
    assert(!umount2(mounts[2],0));
    assert(!mount("none",mounts[2],"cgroup2",0,NULL));
    same_directory(mounts[2],"/tmp/cgn-v2/sibling");
    assert(!umount2(mounts[2],0) && !rmdir("/tmp/cgn-v2/sibling"));
    assert(mount("none",mounts[2],"cgroup2",0,NULL)<0 && errno==ENOENT);
    assert(!setns(host,CLONE_NEWCGROUP));
    assert(writing("/tmp/cgn-v1/cgroup.procs","0")==1);
    assert(!rmdir("/tmp/cgn-v2/child/nested") && !rmdir("/tmp/cgn-v2/child"));
    assert(!umount2(mounts[3],0) && !rmdir("/tmp/cgn-v1/device-child"));
    assert(!umount2(mounts[4],0) && !umount2(mounts[0],0) && !umount2(mounts[1],0));
    for (unsigned i=0;i<5;++i) assert(!rmdir(mounts[i]));
    close(retained); close(local); close(reopened); close(membership); close(host);
    close(proc_member); assert(!umount2("/tmp/cgn-proc",0) && !rmdir("/tmp/cgn-proc"));
    puts("PASS guest cgroup namespace: captured roots, membership, clone/setns, lifetime, authority and fsopen");
    return 0;
}
