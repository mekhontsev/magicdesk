#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>
#include <linux/openat2.h>
#include <linux/stat.h>
#include <sys/vfs.h>
#include <linux/magic.h>
#include <limits.h>

static void write_map(const char *name, const char *value) {
    char path[128]; snprintf(path,sizeof(path),"/proc/self/%s",name);
    int fd=open(path,O_WRONLY|O_CLOEXEC); assert(fd>=0);
    ssize_t size=(ssize_t)strlen(value), result=write(fd,value,(size_t)size);
    if (result!=size) perror(name);
    assert(result==size); assert(!close(fd));
}
static void completed(pid_t child) {
    int status;
    /* EVENT_WAIT: exact child completion; fixture alarm bounds stuck progress. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
}
static const char *limit_path="/proc/sys/user/max_user_namespaces";
static unsigned read_limit(int fd) {
    char text[32]={0};
    assert(pread(fd,text,sizeof(text)-1,0)>0);
    return (unsigned)strtoul(text,NULL,10);
}
static int quota_child(void *unused) { (void)unused; return 0; }
static void namespace_limit_case(int mounted) {
    assert(!unshare(CLONE_NEWUSER|CLONE_NEWNS));
    write_map("uid_map","0 0 1\n"); write_map("setgroups","deny\n"); write_map("gid_map","0 0 1\n");
    if (mounted) {
        assert(!unshare(CLONE_NEWPID));
        pid_t child=fork(); assert(child>=0);
        if (child) { completed(child); return; }
        assert(!mkdir("/tmp/user-limit-proc",0755));
        int result=mount("proc","/tmp/user-limit-proc","proc",0,NULL);
        if (result) perror("limit proc mount");
        assert(!result);
        limit_path="/tmp/user-limit-proc/sys/user/max_user_namespaces";
    }
    const char *watch_names[]={"max_user_instances","max_user_watches","max_queued_events"};
    const unsigned watch_values[]={128,8192,16384};
    for (unsigned i=0;i<3;++i) {
        char path[96]; snprintf(path,sizeof(path),"%s/sys/fs/inotify/%s",
            mounted ? "/tmp/user-limit-proc" : "/proc",watch_names[i]);
        int watch=open(path,O_RDONLY); assert(watch>=0 && read_limit(watch)==watch_values[i]);
        assert(!close(watch));
        /* The mapping retains global UID 0. These writable sysctls use
         * global-root DAC, not namespace-local capabilities. */
        watch=open(path,O_RDWR); assert(watch>=0);
        char value[32]; int size=snprintf(value,sizeof(value),"%u\n",watch_values[i]);
        assert(pwrite(watch,value,(size_t)size,0)==size && read_limit(watch)==watch_values[i]);
        assert(!close(watch));
    }
    int fd=open(limit_path,O_RDWR|O_CLOEXEC);
    if (fd<0) perror("user namespace limit");
    assert(fd>=0 && read_limit(fd)==INT_MAX);
    struct stat a,b;
    assert(!stat(limit_path,&a) && !fstat(fd,&b) && a.st_ino==b.st_ino && a.st_dev==b.st_dev);
    assert((a.st_mode&0777)==0644 && S_ISREG(a.st_mode));
    assert(!access(limit_path,R_OK|W_OK));
    assert(access(limit_path,X_OK)<0 && errno==EACCES);
    assert(pwrite(fd,"1\n",2,0)==2 && read_limit(fd)==1);
    assert(pwrite(fd,"8",1,1)==1 && read_limit(fd)==1);
    const char *bad[]={"-1","2147483648","garbage","1 2"};
    for (unsigned i=0;i<sizeof(bad)/sizeof(*bad);++i)
        assert(pwrite(fd,bad[i],strlen(bad[i]),0)<0 && errno==EINVAL);
    int ready[2],done[2]; assert(!pipe(ready) && !pipe(done));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!close(ready[0]) && !close(done[1]));
        assert(!unshare(CLONE_NEWUSER));
        write_map("uid_map","0 0 1\n"); write_map("setgroups","deny\n"); write_map("gid_map","0 0 1\n");
        assert(pwrite(fd,"100",3,0)<0 && errno==EACCES);
        int inner=open(limit_path,O_RDWR); assert(inner>=0 && read_limit(inner)==INT_MAX);
        assert(pwrite(inner,"100",3,0)==3);
        assert(unshare(CLONE_NEWUSER)<0 && errno==ENOSPC);
        assert(!close(inner));
        assert(write(ready[1],"r",1)==1);
        char byte;
        /* EVENT_WAIT: parent pins namespace before this child exits. */
        assert(read(done[0],&byte,1)==1); _exit(0);
    }
    assert(!close(ready[1]) && !close(done[0]));
    char byte,path[64];
    /* EVENT_WAIT: child created its namespace; alarm bounds protocol failure. */
    assert(read(ready[0],&byte,1)==1);
    snprintf(path,sizeof(path),"%s/%d/ns/user",mounted ? "/tmp/user-limit-proc" : "/proc",child);
    int pin=open(path,O_RDONLY|O_CLOEXEC); assert(pin>=0);
    assert(unshare(CLONE_NEWUSER)<0 && errno==ENOSPC);
    void *stack=malloc(1024*1024); assert(stack);
    assert(clone(quota_child,(char *)stack+1024*1024,CLONE_NEWUSER|SIGCHLD,NULL)<0 && errno==ENOSPC);
    free(stack);
    assert(write(done[1],"r",1)==1); completed(child);
    assert(unshare(CLONE_NEWUSER)<0 && errno==ENOSPC);
    assert(!close(pin));
    assert(!close(ready[0]) && !close(done[1]));
    child=fork(); assert(child>=0);
    if (!child) {
        int result=unshare(CLONE_NEWUSER);
        if (result) perror("user namespace after final descriptor close");
        assert(!result); _exit(0);
    }
    completed(child);
    assert(pwrite(fd,"0",1,0)==1);
    assert(unshare(CLONE_NEWUSER)<0 && errno==ENOSPC);
    assert(!close(fd));
    if (mounted) {
        assert(!mount(NULL,"/tmp/user-limit-proc",NULL,MS_REMOUNT|MS_RDONLY,NULL));
        assert(open(limit_path,O_WRONLY)<0 && errno==EROFS);
        assert(!umount("/tmp/user-limit-proc") && !rmdir("/tmp/user-limit-proc"));
    }
}
int test_user_namespace_limits(void) {
    alarm(60);
    assert(!chmod("/",0755) && !chmod("/tmp",01777));
    for (unsigned round=0;round<16;++round) for (int mounted=0;mounted<2;++mounted) {
        pid_t child=fork(); assert(child>=0);
        if (!child) { namespace_limit_case(mounted); _exit(0); }
        completed(child);
    }
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!setgroups(0,NULL) && !setgid(1000) && !setuid(1000));
        assert(open(limit_path,O_WRONLY)<0 && errno==EACCES);
        int fd=open(limit_path,O_RDONLY); assert(fd>=0 && read_limit(fd)==INT_MAX); close(fd);
        assert(!unshare(CLONE_NEWUSER));
        write_map("uid_map","0 1000 1\n"); write_map("setgroups","deny\n"); write_map("gid_map","0 1000 1\n");
        assert(geteuid()==0);
        const char *names[]={"max_user_instances","max_user_watches","max_queued_events"};
        for (unsigned i=0;i<3;++i) {
            char path[96]; snprintf(path,sizeof(path),"/proc/sys/fs/inotify/%s",names[i]);
            assert(open(path,O_WRONLY)<0 && errno==EACCES);
        }
        _exit(0);
    }
    completed(child);
    puts("PASS guest user namespace limits: ancestor quotas, clone/unshare, retained FD lifetime, sysctl permissions and mounted proc");
    return 0;
}
static void private_user(void) {
    assert(getuid()==65534 && getgid()==65534);
    assert(setuid(0)<0 && errno==EINVAL);
    write_map("uid_map","0 1000 1\n");
    assert(getuid()==0 && getgid()==65534);
    int fd=open("/proc/self/gid_map",O_WRONLY); assert(fd>=0);
    assert(write(fd,"0 1000 1",8)<0 && errno==EPERM); assert(!close(fd));
    write_map("setgroups","deny\n");
    write_map("gid_map","0 1000 1\n");
    assert(getgid()==0 && getegid()==0);
    assert(setgroups(0,NULL)<0 && errno==EPERM);
    assert(!setresuid(0,0,0) && !setresgid(0,0,0));
    assert(setuid(1000)<0 && errno==EINVAL);
    fd=open("/proc/self/uid_map",O_WRONLY); assert(fd>=0);
    assert(write(fd,"0 1000 1",8)<0 && errno==EPERM); assert(!close(fd));
    struct stat st;
    int error=stat("/tmp/user-owned",&st);
    if (error || st.st_uid || st.st_gid)
        fprintf(stderr,"user stat: result=%d errno=%d uid=%u gid=%u\n",error,errno,st.st_uid,st.st_gid);
    assert(!error && st.st_uid==0 && st.st_gid==0);
    assert(!stat("/bin/test",&st) && st.st_uid==65534);
    assert(chown("/bin/test",0,0)<0 && errno==EPERM);
}
static void parent_mapping(void) {
    int ready[2], release[2]; assert(!pipe(ready) && !pipe(release));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!unshare(CLONE_NEWUSER));
        assert(getuid()==65534);
        assert(write(ready[1],"r",1)==1);
        char byte;
        /* EVENT_WAIT: parent publishes both maps, bounded by fixture alarm. */
        assert(read(release[0],&byte,1)==1);
        assert(!getuid() && !getgid());
        _exit(0);
    }
    char byte, path[128];
    /* EVENT_WAIT: child completed unshare; no timed settling delay. */
    assert(read(ready[0],&byte,1)==1);
    snprintf(path,sizeof(path),"/proc/%d/uid_map",child);
    int fd=open(path,O_RDWR|O_CLOEXEC); assert(fd>=0);
    assert(write(fd,"0 1000 1\n",9)==9);
    struct stat before, after;
    assert(!fstat(fd,&before) && before.st_uid==1000);
    snprintf(path,sizeof(path),"/proc/%d/setgroups",child);
    int group=open(path,O_WRONLY|O_CLOEXEC); assert(group>=0);
    assert(write(group,"deny",4)==4 && !close(group));
    snprintf(path,sizeof(path),"/proc/%d/gid_map",child);
    group=open(path,O_WRONLY|O_CLOEXEC); assert(group>=0);
    assert(write(group,"0 1000 1\n",9)==9 && !close(group));
    assert(write(release[1],"r",1)==1); completed(child);
    char map[64];
    assert(pread(fd,map,sizeof(map),0)==33 && !memcmp(map,"         0       1000          1\n",33));
    int result=fstat(fd,&after);
    if (result || before.st_ino!=after.st_ino || after.st_uid)
        fprintf(stderr,"retained uid_map: result=%d errno=%d inode=%llu/%llu uid=%u/%u\n",
            result,errno,(unsigned long long)before.st_ino,(unsigned long long)after.st_ino,
            before.st_uid,after.st_uid);
    assert(!result && before.st_ino==after.st_ino && !after.st_uid);
    assert(!close(fd));
    assert(!close(ready[0]) && !close(ready[1]) && !close(release[0]) && !close(release[1]));
}
static void receive_proc(void) {
    for (unsigned protected=0;protected<2;++protected) {
        int pair[2]; assert(!socketpair(AF_UNIX,SOCK_STREAM,0,pair));
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            close(pair[0]);
            if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
            int filler[128], count=0;
            do { filler[count]=open("/dev/null",O_RDONLY); assert(filler[count]>=0); }
                while (filler[count++]<100 && count<128);
            _Alignas(struct cmsghdr) char control[CMSG_SPACE(sizeof(int))];
            char byte; struct iovec vector={&byte,1};
            struct msghdr msg={.msg_iov=&vector,.msg_iovlen=1,.msg_control=control,.msg_controllen=sizeof(control)};
            /* EVENT_WAIT: sender creates its proc FD after this fork. The
             * fixture alarm bounds receipt without inheriting its selector. */
            assert(recvmsg(pair[1],&msg,MSG_CMSG_CLOEXEC)==1 && !(msg.msg_flags&MSG_CTRUNC));
            struct cmsghdr *header=CMSG_FIRSTHDR(&msg);
            assert(header && header->cmsg_level==SOL_SOCKET && header->cmsg_type==SCM_RIGHTS);
            int fd; memcpy(&fd,CMSG_DATA(header),sizeof(fd));
            assert(fd>100 && (fcntl(fd,F_GETFD)&FD_CLOEXEC));
            char text[64]; assert(read(fd,text,7)==7 && !memcmp(text,"       ",7));
            assert(pread(fd,text,sizeof(text),0)==33 && !memcmp(text,"         0          0 4294967295\n",33));
            assert(write(fd,"x",1)<0 && errno==EBADF);
            assert(close(fd)==0);
            while (count) close(filler[--count]);
            close(pair[1]); _exit(0);
        }
        close(pair[1]);
        int fd=open("/proc/self/uid_map",O_RDONLY); assert(fd>=0);
        _Alignas(struct cmsghdr) char control[CMSG_SPACE(sizeof(int))]={0};
        struct iovec vector={(void *)"p",1};
        struct msghdr msg={.msg_iov=&vector,.msg_iovlen=1,.msg_control=control,.msg_controllen=sizeof(control)};
        struct cmsghdr *header=CMSG_FIRSTHDR(&msg);
        *header=(struct cmsghdr){.cmsg_len=CMSG_LEN(sizeof(int)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
        memcpy(CMSG_DATA(header),&fd,sizeof(fd));
        assert(sendmsg(pair[0],&msg,0)==1); completed(child);
        assert(lseek(fd,0,SEEK_CUR)==7);
        close(fd); close(pair[0]);
    }
}
static int isolated_user(void *unused) {
    (void)unused;
    private_user();
    assert(umount2("/tmp/user-owned/covered",MNT_DETACH)<0 && errno==EINVAL);
    assert(mount(NULL,"/tmp/user-owned/covered",NULL,MS_REMOUNT|MS_BIND,NULL)<0 && errno==EPERM);
    assert(mount("/tmp/user-owned/covered","/view",NULL,MS_MOVE,NULL)<0 && errno==EINVAL);
    assert(mount("/tmp/user-owned","/view",NULL,MS_BIND,NULL)<0 && errno==EINVAL);
    assert(!mount("/tmp/user-owned","/view",NULL,MS_BIND|MS_REC,NULL));
    assert(umount2("/view/covered",MNT_DETACH)<0 && errno==EINVAL);
    assert(!umount2("/view",MNT_DETACH));
    assert(!mount("/tmp/user-owned/covered","/view",NULL,MS_BIND,NULL));
    assert(mount(NULL,"/view",NULL,MS_REMOUNT|MS_BIND,NULL)<0 && errno==EPERM);
    assert(!umount("/view"));
    pid_t pivot=fork(); assert(pivot>=0);
    if (!pivot) {
        assert(!unshare(CLONE_NEWNS));
        assert(!mount("/newroot","/newroot",NULL,MS_BIND,NULL));
        assert(!syscall(SYS_pivot_root,"/newroot","/newroot/old"));
        assert(!chdir("/"));
        assert(!umount2("/old",MNT_DETACH));
        _exit(0);
    }
    completed(pivot);
    assert(!sethostname("private",7));
    assert(!mount("/bin","/mnt","",MS_BIND,NULL));
    assert(!umount("/mnt"));
    assert(!unshare(CLONE_NEWUSER|CLONE_NEWUTS));
    assert(getuid()==65534);
    write_map("uid_map","0 0 1\n");
    write_map("gid_map","0 0 1\n");
    assert(getuid()==0 && getgid()==0);
    assert(!sethostname("nested",6));
    return 0;
}
int test_user_namespace_lifecycle(void) {
    alarm(60);
    assert(!chmod("/",0755) && !chmod("/tmp",01777));
    assert(!chmod("/bin",0755) && !chmod("/bin/test",0755));
    assert(!mkdir("/tmp/user-owned",0755));
    assert(!chown("/tmp/user-owned",1000,1000));
    assert(!mkdir("/tmp/user-owned/covered",0755));
    assert(!mkdir("/view",0755) && !mkdir("/newroot",0755) && !mkdir("/newroot/old",0755));
    assert(!mount("/bin","/tmp/user-owned/covered",NULL,MS_BIND,NULL));
    assert(!mount(NULL,"/tmp/user-owned/covered",NULL,MS_REMOUNT|MS_BIND|MS_RDONLY,NULL));
    assert(!mkdir("/mnt",0755) || errno==EEXIST);
    assert(!setgroups(0,NULL) && !setgid(1000) && !setuid(1000));
    parent_mapping();
    struct utsname before, after; assert(!uname(&before));
    void *stack=malloc(1024*1024); assert(stack);
    int flags=CLONE_NEWUSER|CLONE_NEWUTS|CLONE_NEWIPC|CLONE_NEWNS|SIGCHLD;
    pid_t child=clone(isolated_user,(char *)stack+1024*1024,flags,NULL);
    if (child<0) perror("clone user namespace");
    assert(child>=0); completed(child);
    assert(getuid()==1000 && getgid()==1000);
    assert(!uname(&after) && !strcmp(before.nodename,after.nodename));
    assert(clone(isolated_user,(char *)stack+1024*1024,flags|CLONE_FS,NULL)<0 && errno==EINVAL);
    assert(clone(isolated_user,(char *)stack+1024*1024,flags|CLONE_SIGHAND,NULL)<0 && errno==EINVAL);
    assert(getuid()==1000 && getgid()==1000);
    free(stack);
    assert(!unshare(CLONE_NEWUSER));
    private_user();
    assert(sethostname("denied",6)<0 && errno==EPERM);
    assert(mount("/tmp","/mnt","",MS_BIND,NULL)<0 && errno==EPERM);
    assert(!unshare(CLONE_NEWNS|CLONE_NEWUTS));
    assert(!sethostname("owner",5));
    assert(!mount("/bin","/mnt","",MS_BIND,NULL));
    assert(!umount("/mnt"));
    puts("PASS guest user namespaces: unprivileged clone/unshare, maps, nesting, canonical filesystem IDs and scoped authority");
    return 0;
}

int test_user_namespace_guest(int argc, char **argv) {
    alarm(60);
    const char *map="         0          0 4294967295\n";
    char data[128]={0};
    if (argc==3) {
        int fd=atoi(argv[2]);
        assert(read(fd,data,sizeof(data))==28 && !memcmp(data,map+5,28));
        assert(lseek(fd,0,SEEK_SET)==0 && close(fd)==0);
        return 0;
    }
    receive_proc();
    int fd=open("/proc/self/uid_map",O_RDONLY);
    if (fd<0) perror("uid_map open");
    assert(fd>=0);
    struct stat st, path_st;
    assert(!fstat(fd,&st) && S_ISREG(st.st_mode) && (st.st_mode&0777)==0644 && !st.st_size);
    assert(!stat("/proc/self/uid_map",&path_st) && st.st_ino==path_st.st_ino && st.st_dev==path_st.st_dev);
    struct statx extended;
    assert(!statx(fd,"",AT_EMPTY_PATH,STATX_BASIC_STATS,&extended) && extended.stx_ino==st.st_ino);
    struct statfs filesystem;
    assert(!statfs("/proc/self/uid_map",&filesystem) && filesystem.f_type==PROC_SUPER_MAGIC);
    assert(!fstatfs(fd,&filesystem) && filesystem.f_type==PROC_SUPER_MAGIC);
    assert(!access("/proc/self/uid_map",R_OK));
    assert(access("/proc/self/uid_map",X_OK)<0 && errno==EACCES);
    char exact[128]; snprintf(exact,sizeof(exact),"/proc/%d/uid_map",getpid());
    struct open_how how={.flags=O_RDONLY|O_CLOEXEC,.resolve=RESOLVE_NO_SYMLINKS};
    int resolved=(int)syscall(SYS_openat2,AT_FDCWD,exact,&how,sizeof(how));
    if (resolved<0) perror("openat2 numeric proc uid_map");
    assert(resolved>=0 && !close(resolved));
    assert(syscall(SYS_openat2,AT_FDCWD,"/proc/self/uid_map",&how,sizeof(how))<0 && errno==ELOOP);
    resolved=(int)syscall(SYS_openat2,AT_FDCWD,"/proc/../system/bin/sh",&how,sizeof(how));
    if (resolved>=0 || errno!=ENOENT) fprintf(stderr,"proc parent escape fd=%d errno=%d\n",resolved,errno);
    assert(resolved<0 && errno==ENOENT);
    resolved=(int)syscall(SYS_openat2,AT_FDCWD,"/proc/../bin/test",&how,sizeof(how));
    assert(resolved>=0 && !fstat(resolved,&st) && !stat("/bin/test",&path_st));
    assert(st.st_dev==path_st.st_dev && st.st_ino==path_st.st_ino && !close(resolved));
    assert(!fstat(fd,&st));
    int directory=open("/proc/self",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(directory>=0);
    resolved=openat(directory,"uid_map",O_RDONLY|O_CLOEXEC); assert(resolved>=0);
    assert(!fstat(resolved,&path_st) && path_st.st_ino==st.st_ino);
    assert(!close(resolved) && !close(directory));
    assert((fcntl(fd,F_GETFL)&O_ACCMODE)==O_RDONLY);
    assert(read(fd,data,5)==5 && !memcmp(data,map,5));
    snprintf(exact,sizeof(exact),"/proc/self/fd/%d",fd);
    int reopened=open(exact,O_RDONLY|O_CLOEXEC); assert(reopened>=0);
    assert(read(reopened,data,5)==5 && !memcmp(data,map,5));
    assert(read(reopened,data,3)==3 && lseek(fd,0,SEEK_CUR)==5);
    assert(!close(reopened));
    int copy=dup(fd); assert(copy>=0);
    assert(lseek(copy,0,SEEK_CUR)==5);
    assert(pread(copy,data,sizeof(data),0)==33 && !memcmp(data,map,33));
    assert(lseek(fd,0,SEEK_CUR)==5);
    int other=open("/proc/self/uid_map",O_RDONLY|O_CLOEXEC); assert(other>=0);
    assert(lseek(other,0,SEEK_CUR)==0);
    assert(dup3(fd,other,O_CLOEXEC)==other && lseek(other,0,SEEK_CUR)==5);
    assert(close(other)==0);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        char number[32]; snprintf(number,sizeof(number),"%d",copy);
        execl("/bin/test","test","user-proc",number,NULL); _exit(1);
    }
    int status;
    /* EVENT_WAIT: exact child exit; the alarm bounds a stuck protocol. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(lseek(fd,0,SEEK_CUR)==0);
    struct iovec vectors[]={{data,7},{data+7,sizeof(data)-7}};
    assert(readv(fd,vectors,2)==33 && !memcmp(data,map,33));
    assert(read(fd,data,sizeof(data))==0);
    assert(write(fd,map,33)<0 && errno==EBADF);
    assert(ioctl(fd,FIONREAD,data)<0 && errno==ENOTTY);
    assert(mmap(NULL,4096,PROT_READ,MAP_PRIVATE,fd,0)==MAP_FAILED && errno==ENODEV);
    assert(fsync(fd)<0 && errno==EINVAL);
    assert(!prctl(PR_SET_DUMPABLE,0));
    assert(!fstat(fd,&path_st) && path_st.st_ino==st.st_ino && S_ISREG(path_st.st_mode));
    assert(lseek(copy,0,SEEK_SET)==0);
    assert(read(copy,(void *)1,33)<0 && errno==EFAULT);
    assert(lseek(copy,0,SEEK_CUR)==0);
    assert(read(copy,data,sizeof(data))==33 && !memcmp(data,map,33));
    assert(close(copy)==0 && close(fd)==0);
    fd=open("/proc/self/uid_map",O_WRONLY|O_CLOEXEC); assert(fd>=0);
    assert((fcntl(fd,F_GETFL)&O_ACCMODE)==O_WRONLY);
    assert(write(fd,"0 0 1",5)<0 && errno==EPERM);
    assert(close(fd)==0);
    fd=open("/proc/self/uid_map",O_PATH|O_CLOEXEC); assert(fd>=0);
    assert(read(fd,data,1)<0 && errno==EBADF);
    assert(lseek(fd,0,SEEK_SET)<0 && errno==EBADF); assert(!close(fd));
    puts("PASS guest user proc files: mapped read/write/seek, dup/fork/exec cursor, protected copying and access checks");
    return 0;
}
