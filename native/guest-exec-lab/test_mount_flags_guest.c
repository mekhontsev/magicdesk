#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static void remount(const char *path, unsigned long flags) {
    assert(!mount(NULL,path,NULL,MS_REMOUNT|MS_BIND|flags,NULL));
}
static void elevated(int fd, int expected, const char *path) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!setgroups(0,NULL));
        assert(!setresgid(1000,1000,1000) && !setresuid(1000,1000,1000));
        char value[32]; snprintf(value,sizeof(value),"%d",expected);
        char *argv[]={"test","mount-flags","identity",value,NULL};
        char *envp[]={NULL};
        if (fd>=0) syscall(SYS_execveat,fd,"",argv,envp,AT_EMPTY_PATH);
        else execve(path,argv,envp);
        perror("mount flags exec"); _exit(1);
    }
    /* EVENT_WAIT: exact child completion; the fixture alarm bounds a missing
     * exec or exit without interpreting delay as successful admission. */
    int status; assert(waitpid(child,&status,0)==child);
    assert(WIFEXITED(status) && !WEXITSTATUS(status));
}
static void nosuid(void) {
    assert(!chmod("/",0755) && !chmod("/bin",0755) && !chmod("/bin/test",0755));
    assert(!mkdir("/alias",0755));
    assert(!mount("/bin","/alias",NULL,MS_BIND,NULL));
    int retained=open("/alias/test",O_RDONLY); assert(retained>=0);
    elevated(-1,0,"/alias/test");
    remount("/alias",MS_NOSUID);
    elevated(-1,1000,"/alias/test");
    elevated(retained,1000,NULL);
    elevated(-1,0,"/bin/test");
    remount("/alias",0);
    elevated(retained,0,NULL);
    remount("/",MS_NOSUID);
    elevated(-1,1000,"/bin/test");
    elevated(-1,0,"/alias/test");
    remount("/",0);
    close(retained); assert(!umount2("/alias",0));
    puts("PASS nosuid: admitted ELF, path/fd exec, live remount and independent bind policy");
}
static void nodev(void) {
    int original=open("/attached/null",O_RDWR); assert(original>=0);
    int path=open("/attached/null",O_PATH); assert(path>=0);
    char link[64]; snprintf(link,sizeof(link),"/proc/self/fd/%d",path);
    remount("/attached",MS_NODEV);
    assert(open("/attached/null",O_RDONLY)<0 && errno==EACCES);
    assert(open(link,O_RDWR)<0 && errno==EACCES);
    int still_path=open("/attached/null",O_PATH); assert(still_path>=0); close(still_path);
    assert(write(original,"x",1)==1);
    char byte; assert(!read(original,&byte,1));
    assert(!mkdir("/device-alias",0755));
    assert(!mount("/attached","/device-alias",NULL,MS_BIND,NULL));
    assert(open("/device-alias/null",O_RDWR)<0 && errno==EACCES);
    remount("/device-alias",0);
    int alias=open("/device-alias/null",O_RDWR); assert(alias>=0); close(alias);
    assert(open("/attached/null",O_RDWR)<0 && errno==EACCES);
    remount("/attached",0);
    int reopened=open(link,O_RDWR); assert(reopened>=0); close(reopened);
    close(path); close(original); assert(!umount2("/device-alias",0));
    puts("PASS nodev: path, O_PATH, proc reopen, retained IO and independent bind policy");
}
static void readonly_creation(const char *root, const char *file, const char *absent) {
    remount(root,MS_RDONLY);
    assert(mkdir(root,0755)<0 && errno==EEXIST);
    assert(symlink("unused",file)<0 && errno==EEXIST);
    assert(mkfifo(file,0600)<0 && errno==EEXIST);
    assert(open(file,O_CREAT|O_EXCL|O_RDONLY,0600)<0 && errno==EEXIST);
    int fd=open(file,O_CREAT|O_RDONLY,0600); assert(fd>=0); close(fd);
    assert(open(file,O_WRONLY)<0 && errno==EROFS);
    assert(open(absent,O_CREAT|O_RDONLY,0600)<0 && errno==EROFS);
    assert(mkdir(absent,0755)<0 && errno==EROFS);
    assert(open(absent,O_RDONLY)<0 && errno==ENOENT);
    remount(root,0);
}
static void device_remount(void) {
    const char *devices[]={"/dev/fuse","/dev/kmsg"};
    const char *target="/control-alias";
    for (unsigned i=0;i<sizeof(devices)/sizeof(*devices);++i) {
        int placeholder=open(target,O_CREAT|O_EXCL|O_WRONLY,0600); assert(placeholder>=0); close(placeholder);
        assert(!mount(devices[i],target,NULL,MS_BIND,NULL));
        int fd=open(target,O_PATH|O_CLOEXEC); assert(fd>=0);
        char link[64]; snprintf(link,sizeof(link),"/proc/self/fd/%d",fd);
        const char *copy="/control-copy";
        placeholder=open(copy,O_CREAT|O_EXCL|O_WRONLY,0600); assert(placeholder>=0); close(placeholder);
        assert(!mount(link,copy,NULL,MS_BIND,NULL));
        for (unsigned indirect=0;indirect<2;++indirect) {
            const char *path=indirect ? link : target;
            int result=mount(NULL,path,NULL,MS_REMOUNT|MS_BIND|MS_RDONLY|MS_NOSUID|MS_NODEV,NULL);
            if (result) fprintf(stderr,"device remount %s via %s: %s\n",devices[i],path,strerror(errno));
            assert(!result);
            int opened=open(target,O_RDWR), error=errno;
            if (opened>=0 || error!=EACCES) fprintf(stderr,"device nodev %s: fd=%d error=%d\n",devices[i],opened,error);
            assert(opened<0 && error==EACCES);
            remount(path,MS_RDONLY);
            opened=open(target,O_RDWR); assert(opened>=0); close(opened);
            remount(path,0);
        }
        close(fd); assert(!umount2(target,0)); assert(!unlink(target));
        fd=open(copy,O_RDWR); assert(fd>=0); close(fd);
        assert(!umount2(copy,0)); assert(!unlink(copy));
    }
    puts("PASS control device remount: pathname, O_PATH/proc alias and bind retain mount policy");
}
static void readonly(void) {
    readonly_creation("/","/bin/test","/absent-root-node");
    assert(!mount("/bin","/alias",NULL,MS_BIND,NULL));
    readonly_creation("/alias","/alias/test","/alias/absent-node");
    assert(!umount2("/alias",0));
    puts("PASS readonly create: existing-name errors, read-only O_CREAT and absent-node rejection on base/bind");
}
static void noexec(void) {
    assert(!mount("/bin","/alias",NULL,MS_BIND,NULL));
    int retained=open("/alias/test",O_RDONLY); assert(retained>=0);
    size_t page=(size_t)getpagesize();
    void *allowed=mmap(NULL,page,PROT_READ,MAP_PRIVATE,retained,0); assert(allowed!=MAP_FAILED);
    remount("/alias",MS_NOEXEC);
    assert(access("/alias/test",X_OK)<0 && errno==EACCES);
    assert(!access("/alias",X_OK));
    assert(!access("/bin/test",X_OK));
    char *argv[]={"test",NULL}, *envp[]={NULL};
    assert(execve("/alias/test",argv,envp)<0 && errno==EACCES);
    assert(syscall(SYS_execveat,retained,"",argv,envp,AT_EMPTY_PATH)<0 && errno==EACCES);
    assert(mmap(NULL,page,PROT_READ|PROT_EXEC,MAP_PRIVATE,retained,0)==MAP_FAILED && errno==EPERM);
    void *blocked=mmap(NULL,3*page,PROT_READ,MAP_PRIVATE,retained,0); assert(blocked!=MAP_FAILED);
    assert(mprotect(blocked,page,PROT_READ|PROT_EXEC)<0 && errno==EACCES);
    assert(!mprotect(allowed,page,PROT_READ|PROT_EXEC));
    remount("/alias",0);
    assert(mprotect(blocked,page,PROT_READ|PROT_EXEC)<0 && errno==EACCES);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(mprotect(blocked,page,PROT_READ|PROT_EXEC)<0 && errno==EACCES);
        assert(!munmap(blocked,3*page)); _exit(0);
    }
    /* EVENT_WAIT: exact child completion; alarm cancels a stalled fork. */
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(mprotect(blocked,page,PROT_EXEC)<0 && errno==EACCES);
    void *middle=(char *)blocked+page;
    assert(mmap(middle,page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED,-1,0)==middle);
    assert(!mprotect(middle,page,PROT_READ|PROT_EXEC));
    assert(mprotect(blocked,page,PROT_EXEC)<0 && errno==EACCES);
    assert(mprotect((char *)blocked+2*page,page,PROT_EXEC)<0 && errno==EACCES);
    void *destination=mmap(NULL,page,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0); assert(destination!=MAP_FAILED);
    assert(mremap(blocked,page,page,MREMAP_MAYMOVE|MREMAP_FIXED,destination)==destination);
    assert(mprotect(destination,page,PROT_EXEC)<0 && errno==EACCES);
    assert(!munmap(destination,page));
    assert(!munmap(middle,2*page) && !munmap(allowed,page));
    void *next=mmap(NULL,page,PROT_READ|PROT_EXEC,MAP_PRIVATE,retained,0); assert(next!=MAP_FAILED);
    assert(!munmap(next,page));
    close(retained); assert(!umount2("/alias",0));
    remount("/",MS_NOEXEC);
    assert(access("/bin/test",X_OK)<0 && errno==EACCES);
    assert(execve("/bin/test",argv,envp)<0 && errno==EACCES);
    remount("/",0);
    puts("PASS noexec: exec/access, retained FD, mmap/mprotect, remount, fork, split/replacement and mremap");
}
static void noexec_transfer(void) {
    assert(!mount("/bin","/alias",NULL,MS_BIND,NULL));
    remount("/alias",MS_NOEXEC);
    int fd=open("/alias/test",O_RDONLY); assert(fd>=0);
    int channel[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,channel));
    pid_t child=fork(); assert(child>=0);
    char byte='r';
    if (!child) {
        close(channel[0]); close(fd);
        assert(!unshare(CLONE_NEWNS));
        remount("/alias",0);
        assert(!access("/alias/test",X_OK));
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getppid,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        };
        struct sock_fprog filter={.len=sizeof(code)/sizeof(*code),.filter=code};
        assert(!prctl(PR_SET_DUMPABLE,0) && !prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&filter));
        assert(syscall(SYS_getppid)<0 && errno==EACCES);
        assert(write(channel[1],&byte,1)==1);
        union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
        struct iovec data={&byte,1};
        struct msghdr message={.msg_iov=&data,.msg_iovlen=1,
            .msg_control=control.bytes,.msg_controllen=sizeof(control.bytes)};
        /* EVENT_WAIT: sender publishes an origin from the other mount view;
         * the fixture alarm cancels a missing message, not namespace setup. */
        ssize_t received=recvmsg(channel[1],&message,MSG_CMSG_CLOEXEC);
        if (received!=1 || (message.msg_flags&(MSG_TRUNC|MSG_CTRUNC)))
            fprintf(stderr,"noexec receive: result=%zd errno=%d flags=%x\n",received,errno,message.msg_flags);
        assert(received==1 && !(message.msg_flags&(MSG_TRUNC|MSG_CTRUNC)));
        struct cmsghdr *header=CMSG_FIRSTHDR(&message);
        assert(header && header->cmsg_level==SOL_SOCKET && header->cmsg_type==SCM_RIGHTS
            && header->cmsg_len==CMSG_LEN(sizeof(int)));
        memcpy(&fd,CMSG_DATA(header),sizeof(fd));
        /* EVENT_WAIT: sender has detached the mount and closed its descriptor. */
        assert(read(channel[1],&byte,1)==1 && byte=='g');
        size_t page=(size_t)getpagesize();
        assert(mmap(NULL,page,PROT_READ|PROT_EXEC,MAP_PRIVATE,fd,0)==MAP_FAILED && errno==EPERM);
        void *blocked=mmap(NULL,page,PROT_READ,MAP_PRIVATE,fd,0); assert(blocked!=MAP_FAILED);
        close(fd);
        assert(mprotect(blocked,page,PROT_READ|PROT_EXEC)<0 && errno==EACCES);
        assert(!munmap(blocked,page));
        fd=open("/alias/test",O_RDONLY); assert(fd>=0);
        void *allowed=mmap(NULL,page,PROT_READ|PROT_EXEC,MAP_PRIVATE,fd,0); assert(allowed!=MAP_FAILED);
        assert(!munmap(allowed,page)); close(fd); close(channel[1]); _exit(0);
    }
    close(channel[1]);
    /* EVENT_WAIT: child has an independent mount policy and installed filter;
     * the fixture alarm bounds a missing readiness event. */
    assert(read(channel[0],&byte,1)==1);
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    struct iovec data={&byte,1};
    struct msghdr message={.msg_iov=&data,.msg_iovlen=1,
        .msg_control=control.bytes,.msg_controllen=sizeof(control.bytes)};
    struct cmsghdr *header=CMSG_FIRSTHDR(&message);
    header->cmsg_level=SOL_SOCKET; header->cmsg_type=SCM_RIGHTS; header->cmsg_len=CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(header),&fd,sizeof(fd));
    assert(sendmsg(channel[0],&message,0)==1);
    close(fd);
    int detached=umount2("/alias",MNT_DETACH);
    if (detached) perror("noexec detach");
    assert(!detached);
    assert(write(channel[0],"g",1)==1); close(channel[0]);
    /* EVENT_WAIT: receiver checks mapping policy after transfer; alarm bounds failure. */
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    puts("PASS noexec transfer: protected filtered receiver, independent mount, SCM_RIGHTS and detached origin");
}
int test_mount_flags_guest(int argc, char **argv) {
    setbuf(stdout,NULL); alarm(60);
    if (argc==4 && !strcmp(argv[2],"identity")) {
        assert(getuid()==1000 && geteuid()==(uid_t)atoi(argv[3]));
        return 0;
    }
    nosuid(); nodev(); device_remount(); readonly(); noexec(); noexec_transfer();
    puts("PASS guest mount flags"); return 0;
}
