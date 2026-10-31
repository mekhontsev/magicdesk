#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <linux/nsfs.h>
#include <linux/netlink.h>
#include <linux/genetlink.h>
#include <linux/fib_rules.h>
#include <linux/rtnetlink.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <net/if.h>
#include <linux/sockios.h>
#include <sys/syscall.h>
#include <sys/vfs.h>
#include <sys/wait.h>
#include <sys/time.h>
#include <unistd.h>

static void directory_handles(void) {
    int fd=open("/proc/self/ns/",O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(fd>=0);
    struct stat st; assert(!fstat(fd,&st) && S_ISDIR(st.st_mode));
    struct statfs fs; assert(!fstatfs(fd,&fs) && fs.f_type==PROC_SUPER_MAGIC);
    char bytes[256];
    assert(read(fd,bytes,sizeof(bytes))<0 && errno==EISDIR);
    assert(syscall(SYS_getdents64,fd,bytes,1)<0 && errno==EINVAL);
    assert(!lseek(fd,0,SEEK_CUR));
    assert(syscall(SYS_getdents64,fd,bytes,24)==24);
    int copy=dup(fd); off_t cursor=copy<0 ? -1 : lseek(copy,0,SEEK_CUR);
    if (copy<0 || cursor!=1) fprintf(stderr,"namespace directory dup=%d cursor=%lld errno=%d\n",copy,(long long)cursor,errno);
    assert(copy>=0 && cursor==1);
    void *bad=mmap(NULL,4096,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0); assert(bad!=MAP_FAILED);
    assert(syscall(SYS_getdents64,copy,bad,24)<0 && errno==EFAULT);
    assert(lseek(fd,0,SEEK_CUR)==1); munmap(bad,4096);
    assert(!lseek(copy,0,SEEK_SET)); close(copy);
    DIR *dir=fdopendir(fd); assert(dir);
    unsigned seen=0, count=0; ino_t inodes[10];
    for (struct dirent *e;(e=readdir(dir));) {
        assert(count<10);
        for (unsigned i=0;i<count;++i) assert(inodes[i]!=e->d_ino);
        inodes[count]=e->d_ino;
        ++count;
        if (!strcmp(e->d_name,".") || !strcmp(e->d_name,"..")) { assert(e->d_type==DT_DIR); continue; }
        const char *names[]={"user","mnt","uts","ipc","pid","pid_for_children","net","cgroup"};
        unsigned bit=0;
        for (unsigned i=0;i<8;++i) if (!strcmp(e->d_name,names[i])) bit=1U<<i;
        assert(bit && !(seen&bit) && e->d_type==DT_LNK); seen|=bit;
        int ns=openat(fd,e->d_name,O_RDONLY|O_CLOEXEC); assert(ns>=0);
        assert(ioctl(ns,NS_GET_NSTYPE)>0); close(ns);
    }
    assert(count==10 && seen==255);
    rewinddir(dir); assert(readdir(dir)); closedir(dir);
    int saved=open(".",O_RDONLY|O_DIRECTORY); assert(saved>=0);
    assert(!chdir("/proc/self/ns"));
    fd=open("user",O_RDONLY|O_CLOEXEC); assert(fd>=0 && ioctl(fd,NS_GET_NSTYPE)==CLONE_NEWUSER); close(fd);
    assert(!fchdir(saved)); close(saved);
    int ready[2], finish[2]; assert(!pipe(ready) && !pipe(finish));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(ready[0]); close(finish[1]);
        assert(write(ready[1],"x",1)==1 && read(finish[0],bytes,1)==1); _exit(0);
    }
    close(ready[1]); close(finish[0]); assert(read(ready[0],bytes,1)==1); close(ready[0]);
    char path[64]; snprintf(path,sizeof(path),"/proc/%d/ns",child);
    fd=open(path,O_RDONLY|O_DIRECTORY); assert(fd>=0);
    assert(write(finish[1],"x",1)==1); close(finish[1]);
    int status;
    /* EVENT_WAIT: owner exit before reading its retained directory; alarm bounds failure. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(syscall(SYS_getdents64,fd,bytes,sizeof(bytes))<0 && errno==ENOENT); close(fd);
}
static void process_directory(void) {
    int fd=open("/proc/self/",O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(fd>=0);
    char bytes[4096];
    assert(syscall(SYS_getdents64,fd,bytes,1)<0 && errno==EINVAL);
    assert(!lseek(fd,0,SEEK_CUR));
    assert(syscall(SYS_getdents64,fd,bytes,24)==24);
    off_t position=lseek(fd,0,SEEK_CUR); assert(position>0);
    int copy=dup(fd); assert(copy>=0 && lseek(copy,0,SEEK_CUR)==position);
    void *bad=mmap(NULL,4096,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0); assert(bad!=MAP_FAILED);
    assert(syscall(SYS_getdents64,copy,bad,sizeof(bytes))<0 && errno==EFAULT);
    assert(lseek(fd,0,SEEK_CUR)==position); munmap(bad,4096);
    assert(!lseek(copy,0,SEEK_SET)); close(copy);
    DIR *dir=fdopendir(fd); assert(dir);
    unsigned seen=0; int native=0;
    const char *names[]={"uid_map","gid_map","setgroups"};
    for (struct dirent *e;(e=readdir(dir));) {
        native+=!strcmp(e->d_name,"status");
        for (unsigned i=0;i<3;++i) if (!strcmp(e->d_name,names[i])) {
            assert(!(seen&(1U<<i)) && e->d_type==DT_REG); seen|=1U<<i;
            struct stat st;
            assert(!fstatat(fd,e->d_name,&st,0) && st.st_ino==e->d_ino);
            int file=openat(fd,e->d_name,O_RDONLY|O_CLOEXEC); assert(file>=0);
            assert(read(file,bytes,sizeof(bytes))>0); close(file);
        }
    }
    assert(seen==7 && native==1);
    rewinddir(dir); assert(readdir(dir));
    int saved=open(".",O_RDONLY|O_DIRECTORY); assert(saved>=0);
    assert(!fchdir(fd));
    int file=open("uid_map",O_RDONLY); assert(file>=0); close(file);
    assert(!fchdir(saved)); close(saved); closedir(dir);
    puts("PASS proc process directory: native entries, map discovery, identity, relative opens and fault-safe shared cursor");
}
static void retained_proc(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        int proc=open("/proc",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(proc>=0);
        int dev=open("/dev",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(dev>=0);
        assert(!unshare(CLONE_NEWNS));
        int overflow=open("/proc/sys/kernel/overflowuid",O_RDONLY); assert(overflow>=0);
        close(overflow);
        assert(!chroot("/"));
        overflow=open("/proc/sys/kernel/overflowuid",O_RDONLY); assert(overflow>=0); close(overflow);
        int ns=openat(proc,"self/ns",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(ns>=0);
        int user=openat(ns,"user",O_RDONLY|O_CLOEXEC); assert(user>=0);
        assert(ioctl(user,NS_GET_NSTYPE)==CLONE_NEWUSER); close(user); close(ns);
        int map=openat(proc,"thread-self/uid_map",O_RDONLY|O_CLOEXEC); assert(map>=0);
        char bytes[128]; assert(read(map,bytes,sizeof(bytes))>0); close(map);
        struct stat st;
        assert(!fstatat(proc,"thread-self/ns",&st,0) && S_ISDIR(st.st_mode));
        int null=openat(dev,"null",O_RDWR|O_CLOEXEC); assert(null>=0);
        assert(write(null,"x",1)==1 && !read(null,bytes,1)); close(null);
        close(dev);
        close(proc); _exit(0);
    }
    int status;
    /* EVENT_WAIT: retained-proc child exits; fixture alarm bounds a stuck lookup. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    puts("PASS retained proc dirfd: scoped root, namespace directory, UID map and metadata");
}
static int proc_root_child(void *path) {
    assert(!chroot(path) && !chdir("/"));
    return 0;
}
static void retained_proc_chroot(void) {
    for (unsigned mode=0;mode<8;++mode) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            assert(!unshare(CLONE_NEWNS));
            const char *path=mode&1 ? "/tmp/chroot-proc" : "/proc";
            if (mode&1) {
                assert(!mkdir(path,0755) || errno==EEXIST);
                assert(!mount("proc",path,"proc",0,NULL));
            }
            assert(!unshare(CLONE_NEWUSER));
            int proc=open(path,O_PATH|O_DIRECTORY|O_CLOEXEC);
            if (proc<0) fprintf(stderr,"proc chroot open mode=%u path=%s errno=%d\n",mode,path,errno);
            assert(proc>=0);
            char root[128]; snprintf(root,sizeof(root),"%s/self/%s",path,mode&2 ? "fd" : "fdinfo");
            if (mode&4) {
                void *stack=malloc(65536); assert(stack);
                pid_t helper=clone(proc_root_child,(char *)stack+65536,
                    CLONE_VM|CLONE_FS|CLONE_VFORK|SIGCHLD,(void *)root);
                assert(helper>0);
                int status;
                /* EVENT_WAIT: shared-root helper exit; fixture alarm bounds failure. */
                assert(waitpid(helper,&status,0)==helper && WIFEXITED(status) && !WEXITSTATUS(status));
                free(stack);
            } else proc_root_child((void *)root);
            int ns=openat(proc,"self/ns",O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(ns>=0); close(ns);
            assert(!prctl(PR_SET_DUMPABLE,0));
            assert(open("/proc",O_PATH|O_DIRECTORY)<0 && errno==ENOENT);
            const char *paths[]={"self","self/fd/","thread-self/fdinfo"};
            for (unsigned i=0;i<sizeof(paths)/sizeof(*paths);++i) {
                int fd=openat(proc,paths[i],O_RDONLY|O_DIRECTORY|O_CLOEXEC);
                if (fd<0) fprintf(stderr,"proc chroot mode=%u path=%s errno=%d\n",mode,paths[i],errno);
                assert(fd>=0);
                struct stat st; assert(!fstat(fd,&st) && S_ISDIR(st.st_mode));
                char bytes[4096]; assert(syscall(SYS_getdents64,fd,bytes,sizeof(bytes))>0);
                close(fd);
            }
            assert(openat(proc,"self/ns",O_RDONLY|O_DIRECTORY)<0 && errno==EACCES);
            int fd=openat(proc,"self/status",O_RDONLY|O_CLOEXEC); assert(fd>=0);
            char bytes[128]; assert(read(fd,bytes,sizeof(bytes))>0); close(fd);
            close(proc); _exit(0);
        }
        int status;
        /* EVENT_WAIT: isolated root case exits; fixture alarm bounds failure. */
        assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    }
    assert(!rmdir("/tmp/chroot-proc"));
    puts("PASS retained proc chroot: implicit/mounted proc, fd/fdinfo roots, CLONE_FS helper and protected task");
}
static void unsupported_namespaces(void) {
    assert(access("/proc/thread-self/ns/time",F_OK)<0 && errno==ENOENT);
    assert(unshare(CLONE_NEWTIME)<0 && errno==EOPNOTSUPP);
    int fd=open("/proc/thread-self/ns/mnt",O_RDONLY|O_CLOEXEC); assert(fd>=0);
    char target[128],source[128];
    snprintf(target,sizeof(target),"/tmp/namespace-bind-%d",getpid());
    int file=open(target,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600); assert(file>=0); close(file);
    assert(mount("/proc/thread-self/ns/mnt",target,NULL,MS_BIND,NULL)<0 && errno==EINVAL);
    snprintf(source,sizeof(source),"/proc/self/fd/%d",fd);
    assert(mount(source,target,NULL,MS_BIND,NULL)<0 && errno==EINVAL);
    assert(mount("/proc/self/uid_map",target,NULL,MS_BIND,NULL)<0 && errno==EOPNOTSUPP);
    for (unsigned i=0;i<2;++i) {
        int control=open("/proc/self/uid_map",i ? O_PATH : O_RDONLY); assert(control>=0);
        snprintf(source,sizeof(source),"/proc/self/fd/%d",control);
        assert(mount(source,target,NULL,MS_BIND,NULL)<0 && errno==EOPNOTSUPP); close(control);
    }
    assert(!unlink(target));
    close(fd);
    puts("PASS unsupported namespaces: absent capability, mount cycle and non-mountable control rejection");
}
static void namespace_fd_path(pid_t process,int fd,const char *expected) {
    char path[128],link[256];
    snprintf(path,sizeof(path),"/proc/%d/fd/%d",process,fd);
    ssize_t length=readlink(path,link,sizeof(link));
    if (length!=(ssize_t)strlen(expected) || (length>=0 && memcmp(link,expected,(size_t)length)))
        fprintf(stderr,"namespace FD path %s: result=%zd errno=%d got=%.*s expected=%s\n",
            path,length,errno,length>0 ? (int)length : 0,link,expected);
    assert(length==(ssize_t)strlen(expected) && !memcmp(link,expected,(size_t)length));
}
static void namespace_mounts(void) {
    assert(!unshare(CLONE_NEWNS));
    char target[128],alias[128],source[128];
    snprintf(target,sizeof(target),"/tmp/ns-mounted-%d",getpid());
    snprintf(alias,sizeof(alias),"/tmp/ns-alias-%d",getpid());
    int fd=open(target,O_CREAT|O_EXCL|O_RDWR,0600); assert(fd>=0); close(fd);
    fd=open(alias,O_CREAT|O_EXCL|O_RDWR,0600); assert(fd>=0); close(fd);
    struct stat before,after;
    assert(!stat("/proc/self/ns/net",&before));
    assert(!mount("/proc/thread-self/ns/net",target,NULL,MS_BIND|MS_REC|MS_SHARED,NULL));
    assert(!stat(target,&after) && S_ISREG(after.st_mode)
        && before.st_ino==after.st_ino && before.st_dev==after.st_dev);
    struct statfs fs; assert(!statfs(target,&fs) && fs.f_type==NSFS_MAGIC);
    fd=open(target,O_RDONLY|O_CLOEXEC); assert(fd>=0);
    namespace_fd_path(getpid(),fd,target);
    assert(ioctl(fd,NS_GET_NSTYPE)==CLONE_NEWNET);
    int owner=ioctl(fd,NS_GET_USERNS); assert(owner>=0); close(owner);
    assert(!fstat(fd,&after) && before.st_ino==after.st_ino);
    assert(!fstatfs(fd,&fs) && fs.f_type==NSFS_MAGIC);
    char bytes[128];
    assert(read(fd,bytes,sizeof(bytes))<0 && errno==EINVAL);
    assert(lseek(fd,0,SEEK_SET)<0 && errno==ESPIPE);
    assert(open(target,O_WRONLY)<0 && errno==EACCES);
    assert(chmod(target,0600)<0 && errno==EOPNOTSUPP);
    assert(open(target,O_DIRECTORY|O_RDONLY)<0 && errno==ENOTDIR);
    int path=open(target,O_PATH|O_CLOEXEC); assert(path>=0);
    namespace_fd_path(getpid(),path,target);
    snprintf(source,sizeof(source),"/proc/self/fd/%d",path);
    assert(!mount(source,alias,NULL,MS_BIND,NULL));
    int copy=open(source,O_RDONLY|O_CLOEXEC); assert(copy>=0);
    namespace_fd_path(getpid(),copy,target);
    pid_t reader=fork(); assert(reader>=0);
    if (!reader) {
        namespace_fd_path(getppid(),fd,target);
        namespace_fd_path(getppid(),path,target);
        _exit(0);
    }
    int reader_status;
    /* EVENT_WAIT: foreign namespace-FD readlink completes; fixture alarm bounds failure. */
    assert(waitpid(reader,&reader_status,0)==reader && WIFEXITED(reader_status) && !WEXITSTATUS(reader_status));
    assert(ioctl(copy,NS_GET_NSTYPE)==CLONE_NEWNET);
    assert(!unshare(CLONE_NEWNET));
    assert(!stat("/proc/self/ns/net",&after) && before.st_ino!=after.st_ino);
    assert(!setns(copy,CLONE_NEWNET)); close(copy); close(path);
    assert(!stat("/proc/self/ns/net",&after) && before.st_ino==after.st_ino);
    assert(setns(fd,CLONE_NEWUTS)<0 && errno==EINVAL);
    assert(umount(target)<0 && errno==EBUSY);
    assert(!umount2(target,MNT_DETACH));
    assert(ioctl(fd,NS_GET_NSTYPE)==CLONE_NEWNET);
    assert(!setns(fd,CLONE_NEWNET)); close(fd);
    assert(!stat(alias,&after) && before.st_ino==after.st_ino);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!unshare(CLONE_NEWNS));
        int retained=open(alias,O_RDONLY); assert(retained>=0);
        assert(ioctl(retained,NS_GET_NSTYPE)==CLONE_NEWNET); close(retained);
        assert(!umount(alias)); _exit(0);
    }
    int status;
    /* EVENT_WAIT: copied namespace exits; fixture alarm bounds failure. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!stat(alias,&after) && before.st_ino==after.st_ino);
    assert(!mount(alias,target,NULL,MS_MOVE,NULL));
    assert(!stat(target,&after) && before.st_ino==after.st_ino);
    assert(!mount(target,alias,NULL,MS_MOVE,NULL));
    assert(!mount(alias,target,NULL,MS_BIND,NULL));
    assert(!umount(alias));
    assert(!stat(target,&after) && before.st_ino==after.st_ino);
    assert(!umount(target));
    for (unsigned i=0;i<96;++i) {
        assert(!mount("/proc/self/ns/net",target,NULL,MS_BIND,NULL));
        fd=open(target,O_RDONLY); assert(fd>=0);
        assert(!umount2(target,MNT_DETACH)); close(fd);
    }
    assert(!unlink(target) && !unlink(alias));
    puts("PASS namespace mounts: typed path/FD binds, nsfs identity, setns, copy, lazy retirement and cycle rejection");
}
static void namespace_mount_lifetime(int chrooted) {
    char directory[128],network[160],mountpoint[160],source[128],copy[128];
    snprintf(directory,sizeof(directory),"/tmp/ns-lifetime-%d",getpid());
    snprintf(network,sizeof(network),"%s/net",directory);
    snprintf(mountpoint,sizeof(mountpoint),"%s/mnt",directory);
    snprintf(copy,sizeof(copy),"/tmp/ns-copy-%d",getpid());
    assert(!mkdir(directory,0755) && !mkdir(copy,0755));
    int fd=open(network,O_CREAT|O_EXCL|O_RDWR,0644); assert(fd>=0); close(fd);
    fd=open(mountpoint,O_CREAT|O_EXCL|O_RDWR,0644); assert(fd>=0); close(fd);
    struct stat empty; assert(!stat(mountpoint,&empty));
    int original=open("/proc/self/ns/mnt",O_RDONLY|O_CLOEXEC); assert(original>=0);
    int saved_root=open("/",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(saved_root>=0);
    int ready[2],finish[2]; assert(!pipe(ready) && !pipe(finish));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(ready[0]); close(finish[1]);
        assert(!unshare(CLONE_NEWNS|CLONE_NEWNET));
        assert(write(ready[1],"x",1)==1);
        char byte;
        /* EVENT_WAIT: parent captures both namespaces; fixture alarm bounds failure. */
        assert(read(finish[0],&byte,1)==1); _exit(0);
    }
    close(ready[1]); close(finish[0]);
    char byte; assert(read(ready[0],&byte,1)==1); close(ready[0]);
    snprintf(source,sizeof(source),"/proc/%d/ns/mnt",child);
    struct stat mount_id,network_id,st;
    assert(!stat(source,&mount_id));
    assert(!mount(source,mountpoint,NULL,MS_BIND,NULL));
    snprintf(source,sizeof(source),"/proc/%d/ns/net",child);
    assert(!stat(source,&network_id));
    assert(!mount(source,network,NULL,MS_BIND,NULL));
    assert(write(finish[1],"x",1)==1); close(finish[1]);
    int status;
    /* EVENT_WAIT: namespace owner exits; mounts must retain authority afterwards. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    fd=open(mountpoint,O_RDONLY); assert(fd>=0 && ioctl(fd,NS_GET_NSTYPE)==CLONE_NEWNS);
    assert(!setns(fd,CLONE_NEWNS));
    assert(!stat("/proc/self/ns/mnt",&st) && st.st_ino==mount_id.st_ino);
    assert(!setns(original,CLONE_NEWNS)); close(original); close(fd);
    /* Mount-namespace entry selects its root; restore this fixture's private
     * chroot before resolving the test paths again. */
    assert(!fchdir(saved_root) && !chroot(".") && !chdir("/")); close(saved_root);
    assert(!mount(directory,copy,NULL,MS_BIND|MS_REC,NULL));
    char copied_net[160],copied_mnt[160];
    snprintf(copied_net,sizeof(copied_net),"%s/net",copy);
    snprintf(copied_mnt,sizeof(copied_mnt),"%s/mnt",copy);
    assert(!stat(copied_net,&st) && st.st_ino==network_id.st_ino);
    assert(!stat(copied_mnt,&st) && st.st_ino==mount_id.st_ino);
    /* Imported fixture root is mode 0700 under the real shell UID. Allow the
     * deliberately unmapped user namespace to traverse it for this check. */
    assert(!chmod("/",0755));
    child=fork(); assert(child>=0);
    if (!child) {
        if (chrooted) {
            assert(unshare(CLONE_NEWUSER|CLONE_NEWNS)<0 && errno==EPERM);
            assert(!unshare(CLONE_NEWNS));
        } else assert(!unshare(CLONE_NEWUSER|CLONE_NEWNS));
        assert(!stat(mountpoint,&st) && st.st_ino==empty.st_ino);
        assert(!stat(copied_mnt,&st) && st.st_ino==empty.st_ino);
        fd=open(copied_net,O_RDONLY); assert(fd>=0);
        assert(ioctl(fd,NS_GET_NSTYPE)==CLONE_NEWNET);
        if (chrooted) assert(!setns(fd,CLONE_NEWNET));
        else assert(setns(fd,CLONE_NEWNET)<0 && errno==EPERM);
        close(fd); _exit(0);
    }
    /* EVENT_WAIT: copied namespace/authority check exits; alarm bounds failure. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!umount(copied_net) && !umount(copied_mnt) && !umount(copy));
    assert(!umount(network) && !umount(mountpoint));
    assert(!unlink(network) && !unlink(mountpoint));
    assert(!rmdir(directory) && !rmdir(copy));
    puts("PASS namespace mount lifetime: exited owner, recursive bind, acyclic copy and user authority");
}
int test_namespace_mounts_pid_guest(void) {
    alarm(60);
    assert(!unshare(CLONE_NEWPID));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(getpid()==1 && !unshare(CLONE_NEWNS));
        assert(!mkdir("/tmp/ns-root",0755));
        assert(!mkdir("/tmp/ns-root/proc",0755) && !mkdir("/tmp/ns-root/tmp",0777));
        assert(!chroot("/tmp/ns-root") && !chdir("/"));
        assert(!mount("proc","/proc","proc",0,NULL));
        namespace_mounts(); namespace_mount_lifetime(1); _exit(0);
    }
    int status;
    /* EVENT_WAIT: PID namespace init completes its mounted-proc workflows. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    puts("PASS PID-scoped namespace mounts: mounted proc, self/thread aliases and foreign owners");
    return 0;
}
static void loopback_query(int fd,unsigned flags,int index,const char *name,int expected) {
    union { struct nlmsghdr align; char bytes[128]; } request={0},answer={0};
    struct nlmsghdr *h=(void *)request.bytes;
    *h=(struct nlmsghdr){.nlmsg_len=NLMSG_LENGTH(sizeof(struct ifinfomsg)),
        .nlmsg_type=RTM_GETLINK,.nlmsg_flags=NLM_F_REQUEST|flags,.nlmsg_seq=19};
    ((struct ifinfomsg *)NLMSG_DATA(h))->ifi_index=index;
    if (name) {
        struct rtattr *a=(void *)(request.bytes+h->nlmsg_len);
        a->rta_type=IFLA_IFNAME; a->rta_len=RTA_LENGTH(strlen(name)+1);
        memcpy(RTA_DATA(a),name,strlen(name)+1); h->nlmsg_len+=RTA_ALIGN(a->rta_len);
        a=(void *)(request.bytes+h->nlmsg_len);
        a->rta_type=IFLA_EXT_MASK; a->rta_len=RTA_LENGTH(sizeof(uint32_t));
        uint32_t mask=RTEXT_FILTER_VF; memcpy(RTA_DATA(a),&mask,sizeof(mask));
        h->nlmsg_len+=RTA_ALIGN(a->rta_len);
    }
    struct sockaddr_nl kernel={.nl_family=AF_NETLINK};
    assert(sendto(fd,request.bytes,h->nlmsg_len,0,(void *)&kernel,sizeof(kernel))==h->nlmsg_len);
    ssize_t count=recv(fd,answer.bytes,sizeof(answer.bytes),0); assert(count>0);
    unsigned remaining=(unsigned)count; int links=0,done=0,acks=0;
    for (struct nlmsghdr *a=(void *)answer.bytes;NLMSG_OK(a,remaining);a=NLMSG_NEXT(a,remaining)) {
        assert(a->nlmsg_seq==19);
        if (a->nlmsg_type==NLMSG_ERROR) {
            assert(a->nlmsg_len>=NLMSG_LENGTH(sizeof(struct nlmsgerr)));
            assert(((struct nlmsgerr *)NLMSG_DATA(a))->error==expected); ++acks;
        } else if (a->nlmsg_type==NLMSG_DONE) { assert(!expected); ++done; }
        else {
            assert(!expected && a->nlmsg_type==RTM_NEWLINK);
            struct ifinfomsg *m=NLMSG_DATA(a); assert(m->ifi_index==1); ++links;
            int bytes=(int)a->nlmsg_len-(int)NLMSG_LENGTH(sizeof(*m)),named=0;
            for (struct rtattr *r=IFLA_RTA(m);RTA_OK(r,bytes);r=RTA_NEXT(r,bytes))
                if (r->rta_type==IFLA_IFNAME) {
                    assert(RTA_PAYLOAD(r)==3 && !memcmp(RTA_DATA(r),"lo",3)); ++named;
                }
            assert(!bytes && named==1);
            int control=socket(AF_UNIX,SOCK_DGRAM,0); assert(control>=0);
            struct ifreq interface={0}; memcpy(interface.ifr_name,"lo",3);
            assert(!ioctl(control,SIOCGIFFLAGS,&interface)); close(control);
            assert((m->ifi_flags&0xffff)==(unsigned short)interface.ifr_flags);
        }
    }
    assert(!remaining);
    assert(expected ? (acks==1 && !links && !done)
        : (links==1 && done==((flags&NLM_F_DUMP)==NLM_F_DUMP)
            && acks==(!(flags&NLM_F_DUMP) && !!(flags&NLM_F_ACK))));
}
static void generic_network(void) {
    int fd=socket(AF_NETLINK,SOCK_RAW|SOCK_CLOEXEC|SOCK_NONBLOCK,NETLINK_GENERIC); assert(fd>=0);
    struct sockaddr_nl kernel={.nl_family=AF_NETLINK};
    assert(!bind(fd,(void *)&kernel,sizeof(kernel)));
    int protocol=-1; socklen_t size=sizeof(protocol);
    assert(!getsockopt(fd,SOL_SOCKET,SO_PROTOCOL,&protocol,&size) && protocol==NETLINK_GENERIC);
    assert(!connect(fd,(void *)&kernel,sizeof(kernel)));
    struct sockaddr_nl remote; size=sizeof(remote);
    assert(!getpeername(fd,(void *)&remote,&size) && size==sizeof(remote)
        && remote.nl_family==AF_NETLINK && !remote.nl_pid && !remote.nl_groups);
    struct sockaddr_nl unsupported={.nl_family=AF_NETLINK,.nl_pid=123};
    assert(connect(fd,(void *)&unsupported,sizeof(unsupported))<0 && errno==EOPNOTSUPP);
    unsupported.nl_family=AF_UNSPEC;
    assert(!connect(fd,(void *)&unsupported,sizeof(sa_family_t)));
    for (unsigned attempt=0;attempt<5;++attempt) {
        union { struct nlmsghdr align; unsigned char bytes[512]; } request={0},response;
        struct nlmsghdr *h=&request.align;
        *h=(struct nlmsghdr){.nlmsg_len=NLMSG_LENGTH(GENL_HDRLEN),.nlmsg_type=GENL_ID_CTRL,
            .nlmsg_flags=NLM_F_REQUEST|NLM_F_ACK,.nlmsg_seq=44};
        struct genlmsghdr *g=NLMSG_DATA(h); g->cmd=CTRL_CMD_GETFAMILY; g->version=2;
        if (attempt==0) h->nlmsg_flags|=NLM_F_DUMP;
        else if (attempt==4) g->cmd=CTRL_CMD_NEWFAMILY;
        else {
            struct rtattr *a=(void *)(request.bytes+h->nlmsg_len);
            if (attempt==2) {
                uint16_t id=GENL_ID_CTRL;
                a->rta_type=CTRL_ATTR_FAMILY_ID; a->rta_len=RTA_LENGTH(sizeof(id));
                memcpy(RTA_DATA(a),&id,sizeof(id));
            } else {
                const char *name=attempt==1 ? "nlctrl" : "nl80211";
                a->rta_type=CTRL_ATTR_FAMILY_NAME; a->rta_len=RTA_LENGTH(strlen(name)+1);
                memcpy(RTA_DATA(a),name,strlen(name)+1);
            }
            h->nlmsg_len+=RTA_ALIGN(a->rta_len);
        }
        assert(sendto(fd,h,h->nlmsg_len,0,(void *)&kernel,sizeof(kernel))==h->nlmsg_len);
        ssize_t length=recv(fd,response.bytes,sizeof(response.bytes),0); assert(length>0);
        unsigned remaining=(unsigned)length; int found=0,acks=0,done=0;
        for (struct nlmsghdr *r=&response.align;NLMSG_OK(r,remaining);r=NLMSG_NEXT(r,remaining)) {
            assert(r->nlmsg_seq==44);
            if (r->nlmsg_type==NLMSG_ERROR) {
                int error=((struct nlmsgerr *)NLMSG_DATA(r))->error;
                assert(error==(attempt==3 ? -ENOENT : attempt==4 ? -EOPNOTSUPP : 0)); ++acks;
            } else if (r->nlmsg_type==NLMSG_DONE) { assert(attempt==0); ++done; }
            else {
                assert(attempt<3 && r->nlmsg_type==GENL_ID_CTRL);
                g=NLMSG_DATA(r); assert(g->cmd==CTRL_CMD_NEWFAMILY && g->version==2);
                int bytes=(int)r->nlmsg_len-(int)NLMSG_LENGTH(GENL_HDRLEN),named=0;
                for (struct rtattr *a=(void *)((char *)g+GENL_HDRLEN);RTA_OK(a,bytes);a=RTA_NEXT(a,bytes))
                    if (a->rta_type==CTRL_ATTR_FAMILY_NAME) {
                        assert(RTA_PAYLOAD(a)==7 && !memcmp(RTA_DATA(a),"nlctrl",7)); ++named;
                    }
                assert(!bytes && named==1); ++found;
            }
        }
        assert(!remaining && found==(attempt<3) && acks==(attempt!=0) && done==(attempt==0));
    }
    int group=16;
    assert(!setsockopt(fd,SOL_NETLINK,NETLINK_ADD_MEMBERSHIP,&group,sizeof(group)));
    group=RTNLGRP_LINK;
    assert(setsockopt(fd,SOL_NETLINK,NETLINK_ADD_MEMBERSHIP,&group,sizeof(group))<0 && errno==EOPNOTSUPP);
    close(fd);
}
static void routing_policy(int fd) {
    const unsigned queries[]={RTM_GETRULE,RTM_GETNEIGH,RTM_GETQDISC,RTM_GETTCLASS,RTM_GETTFILTER};
    const unsigned groups[]={RTNLGRP_IPV4_RULE,RTNLGRP_IPV6_RULE,RTNLGRP_NEIGH,RTNLGRP_TC};
    const unsigned families[]={AF_INET,AF_INET6,AF_UNSPEC};
    for (unsigned i=0;i<sizeof(groups)/sizeof(*groups);++i) {
        assert(!setsockopt(fd,SOL_NETLINK,NETLINK_ADD_MEMBERSHIP,&groups[i],sizeof(groups[i])));
        assert(!setsockopt(fd,SOL_NETLINK,NETLINK_DROP_MEMBERSHIP,&groups[i],sizeof(groups[i])));
    }
    struct sockaddr_nl kernel={.nl_family=AF_NETLINK};
    for (unsigned i=0;i<sizeof(queries)/sizeof(*queries);++i)
        for (unsigned family=0;family<sizeof(families)/sizeof(*families);++family) {
            struct { struct nlmsghdr header; struct rtgenmsg body; } query={
                .header={.nlmsg_len=NLMSG_LENGTH(sizeof(struct rtgenmsg)),.nlmsg_type=queries[i],
                    .nlmsg_flags=NLM_F_REQUEST|NLM_F_DUMP,.nlmsg_seq=67},.body={.rtgen_family=families[family]}};
            assert(sendto(fd,&query,query.header.nlmsg_len,0,(void *)&kernel,sizeof(kernel))==query.header.nlmsg_len);
            union { struct nlmsghdr align; unsigned char bytes[1024]; } answer;
            ssize_t count=recv(fd,answer.bytes,sizeof(answer.bytes),0); assert(count>0);
            unsigned remaining=(unsigned)count,rules=0,done=0,counts[2]={0};
            for (struct nlmsghdr *h=&answer.align;NLMSG_OK(h,remaining);h=NLMSG_NEXT(h,remaining)) {
                assert(h->nlmsg_seq==67);
                if (h->nlmsg_type==NLMSG_DONE) { ++done; continue; }
                assert(h->nlmsg_type==RTM_NEWRULE && !i);
                const struct fib_rule_hdr *rule=NLMSG_DATA(h);
                assert(rule->family==AF_INET || rule->family==AF_INET6);
                assert(!families[family] || rule->family==families[family]);
                unsigned slot=rule->family==AF_INET6, index=counts[slot]++;
                assert(index<(slot ? 2U : 3U));
                const unsigned tables[]={RT_TABLE_LOCAL,RT_TABLE_MAIN,RT_TABLE_DEFAULT};
                const unsigned priorities[]={0,32766,32767};
                assert(rule->table==tables[index] && rule->action==FR_ACT_TO_TBL);
                unsigned priority=0;
                int size=(int)h->nlmsg_len-(int)NLMSG_LENGTH(sizeof(*rule));
                for (struct rtattr *a=(void *)((char *)rule+NLMSG_ALIGN(sizeof(*rule)));RTA_OK(a,size);a=RTA_NEXT(a,size))
                    if (a->rta_type==FRA_PRIORITY) {
                        assert(RTA_PAYLOAD(a)==sizeof(priority)); memcpy(&priority,RTA_DATA(a),sizeof(priority));
                    }
                assert(!size && priority==priorities[index]); ++rules;
            }
            unsigned expected=i ? 0 : families[family]==AF_INET ? 3 : families[family]==AF_INET6 ? 2 : 5;
            assert(!remaining && done==1 && rules==expected);
        }
    struct { struct nlmsghdr header; struct fib_rule_hdr body; struct rtattr attribute; unsigned priority; } change={
        .header={.nlmsg_len=sizeof(change),.nlmsg_type=RTM_NEWRULE,.nlmsg_flags=NLM_F_REQUEST|NLM_F_ACK},
        .body={.family=AF_INET,.table=RT_TABLE_MAIN,.action=FR_ACT_TO_TBL},
        .attribute={.rta_len=RTA_LENGTH(sizeof(unsigned)),.rta_type=FRA_PRIORITY},.priority=1234};
    union { struct nlmsghdr align; char bytes[256]; } answer;
    for (unsigned step=0;step<3;++step) {
        change.header.nlmsg_type=step==1 ? RTM_DELRULE : RTM_NEWRULE;
        if (step==2) change.body.action=FR_ACT_GOTO;
        assert(sendto(fd,&change,sizeof(change),0,(void *)&kernel,sizeof(kernel))==sizeof(change));
        assert(recv(fd,answer.bytes,sizeof(answer.bytes),0)>=(ssize_t)NLMSG_LENGTH(sizeof(struct nlmsgerr)));
        assert(answer.align.nlmsg_type==NLMSG_ERROR
            && ((struct nlmsgerr *)NLMSG_DATA(&answer.align))->error==(step==2 ? -EOPNOTSUPP : 0));
    }
}
static void offline_network(void) {
    int host=open("/proc/self/ns/net",O_RDONLY|O_CLOEXEC); assert(host>=0);
    struct stat before,after; assert(!fstat(host,&before));
    int inherited[2]; assert(!socketpair(AF_UNIX,SOCK_STREAM,0,inherited));
    assert(!unshare(CLONE_NEWNET));
    generic_network();
    assert(!stat("/proc/self/ns/net",&after) && before.st_ino!=after.st_ino);
    assert(socket(AF_INET,SOCK_STREAM,0)<0 && errno==EPROTONOSUPPORT);
    int route=socket(AF_NETLINK,SOCK_RAW|SOCK_CLOEXEC,NETLINK_ROUTE); assert(route>=0);
    struct timeval timeout={.tv_sec=60}, actual={0};
    assert(!setsockopt(route,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout)));
    socklen_t length=sizeof(actual);
    int route_copy=dup(route); assert(route_copy>=0);
    const int netlink_options[]={NETLINK_BROADCAST_ERROR,NETLINK_NO_ENOBUFS};
    for (unsigned i=0;i<sizeof(netlink_options)/sizeof(netlink_options[0]);++i) {
        int value=0; socklen_t size=sizeof(value);
        assert(!getsockopt(route,SOL_NETLINK,netlink_options[i],&value,&size) && !value);
        value=7;
        assert(!setsockopt(route,SOL_NETLINK,netlink_options[i],&value,sizeof(value)));
        assert(!getsockopt(route_copy,SOL_NETLINK,netlink_options[i],&value,&size) && value==1);
        value=0;
        assert(!setsockopt(route_copy,SOL_NETLINK,netlink_options[i],&value,sizeof(value)));
        assert(!getsockopt(route,SOL_NETLINK,netlink_options[i],&value,&size) && !value);
    }
    assert(!getsockopt(route_copy,SOL_SOCKET,SO_SNDTIMEO,&actual,&length));
    assert(length==sizeof(actual) && actual.tv_sec==60 && !actual.tv_usec);
    timeout.tv_sec=0; timeout.tv_usec=20000;
    assert(!setsockopt(route_copy,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout)));
    char packet[32];
    /* EVENT_WAIT: empty queue receive; the kernel timeout must return EAGAIN. */
    assert(recv(route,packet,sizeof(packet),0)<0 && errno==EAGAIN);
    timeout.tv_usec=1000000;
    assert(setsockopt(route,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout))<0 && errno==EDOM);
    assert(setsockopt(route,SOL_SOCKET,SO_SNDTIMEO,&timeout,1)<0 && errno==EINVAL);
    assert(setsockopt(route,SOL_SOCKET,SO_SNDTIMEO,(void *)1,sizeof(timeout))<0 && errno==EFAULT);
    int bytes=65536;
    assert(!setsockopt(route,SOL_SOCKET,SO_RCVBUF,&bytes,sizeof(bytes)));
    length=sizeof(bytes); bytes=0;
    assert(!getsockopt(route_copy,SOL_SOCKET,SO_RCVBUF,&bytes,&length) && bytes>=65536);
    assert(setsockopt(route,SOL_SOCKET,SO_BINDTODEVICE,"wlan0",6)<0 && errno==EOPNOTSUPP);
    union { struct sockaddr_nl nl; struct sockaddr_storage storage; } padded={.nl.nl_family=AF_NETLINK};
    assert(!bind(route,(void *)&padded,sizeof(padded)));
    loopback_query(route,NLM_F_ACK,0,"lo",0);
    loopback_query(route,0,1,NULL,0);
    loopback_query(route,NLM_F_DUMP,0,NULL,0);
    loopback_query(route,0,0,"wlan0",-ENODEV);
    loopback_query(route,0,2,NULL,-ENODEV);
    routing_policy(route);
    int strict=1;
    assert(!setsockopt(route,SOL_NETLINK,NETLINK_GET_STRICT_CHK,&strict,sizeof(strict)));
    length=sizeof(strict); strict=0;
    assert(!getsockopt(route_copy,SOL_NETLINK,NETLINK_GET_STRICT_CHK,&strict,&length) && strict==1);
    loopback_query(route,NLM_F_DUMP,0,NULL,0);
    loopback_query(route,NLM_F_DUMP,1,NULL,-EINVAL);
    strict=0;
    assert(!setsockopt(route_copy,SOL_NETLINK,NETLINK_GET_STRICT_CHK,&strict,sizeof(strict)));
    int monitor=socket(AF_NETLINK,SOCK_RAW|SOCK_CLOEXEC|SOCK_NONBLOCK,NETLINK_ROUTE); assert(monitor>=0);
    int enabled_info=1,group=RTNLGRP_LINK,observed=0;
    assert(!setsockopt(monitor,SOL_NETLINK,NETLINK_PKTINFO,&enabled_info,sizeof(enabled_info)));
    assert(!setsockopt(monitor,SOL_NETLINK,NETLINK_ADD_MEMBERSHIP,&group,sizeof(group)));
    length=sizeof(observed);
    assert(!getsockopt(monitor,SOL_NETLINK,NETLINK_LIST_MEMBERSHIPS,&observed,&length));
    assert(observed==RTMGRP_LINK && length==sizeof(observed));
    struct { struct nlmsghdr header; struct ifinfomsg link; } up={
        .header={.nlmsg_len=sizeof(up),.nlmsg_type=RTM_NEWLINK,.nlmsg_flags=NLM_F_REQUEST|NLM_F_ACK},
        .link={.ifi_index=1,.ifi_flags=IFF_UP,.ifi_change=IFF_UP}};
    assert(sendto(route,&up,sizeof(up),0,(void *)&padded,sizeof(padded))==sizeof(up));
    struct { struct nlmsghdr header; struct nlmsgerr result; } ack;
    assert(recv(route,&ack,sizeof(ack),0)==sizeof(ack) && !ack.result.error);
    const size_t capacities[]={CMSG_SPACE(sizeof(struct nl_pktinfo)),CMSG_SPACE(sizeof(struct nl_pktinfo)),0,8,16,19,CMSG_SPACE(sizeof(struct nl_pktinfo))};
    for (unsigned attempt=0;attempt<sizeof(capacities)/sizeof(capacities[0]);++attempt) {
        char bytes[2048];
        union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(struct nl_pktinfo))]; } ancillary;
        struct sockaddr_nl source={0};
        struct iovec vector={bytes,attempt ? sizeof(bytes) : 1},many[1024];
        for (unsigned i=0;i<1024;++i) many[i]=(struct iovec){bytes+i,1};
        struct msghdr message={.msg_name=&source,.msg_namelen=sizeof(source),.msg_iov=&vector,.msg_iovlen=1,
            .msg_control=ancillary.bytes,.msg_controllen=capacities[attempt]};
        if (attempt==1) { message.msg_iov=many; message.msg_iovlen=1024; }
        int available=0; assert(!ioctl(monitor,FIONREAD,&available));
        ssize_t n=recvmsg(monitor,&message,attempt==6 ? 0 : MSG_PEEK|(attempt ? 0 : MSG_TRUNC));
        assert(n>(ssize_t)sizeof(struct nlmsghdr));
        assert(n==available);
        assert(source.nl_family==AF_NETLINK && !source.nl_pid && source.nl_groups==RTMGRP_LINK);
        size_t capacity=capacities[attempt];
        assert(message.msg_namelen==sizeof(source));
        assert(message.msg_controllen==(capacity<sizeof(struct cmsghdr) ? 0 : capacity));
        assert(!!(message.msg_flags&MSG_TRUNC)==!attempt);
        assert(!!(message.msg_flags&MSG_CTRUNC)==(capacity<CMSG_LEN(sizeof(struct nl_pktinfo))));
        struct cmsghdr *c=CMSG_FIRSTHDR(&message);
        if (capacity>=sizeof(struct cmsghdr)) {
            assert(c && c->cmsg_level==SOL_NETLINK && c->cmsg_type==NETLINK_PKTINFO);
            size_t expected=CMSG_LEN(sizeof(struct nl_pktinfo));
            assert(c->cmsg_len==(capacity<expected ? capacity : expected));
            if (capacity>=expected) assert(((struct nl_pktinfo *)CMSG_DATA(c))->group==RTNLGRP_LINK);
        } else assert(!c);
        if (attempt) {
            const struct nlmsghdr *h=(void *)bytes;
            assert(h->nlmsg_type==RTM_NEWLINK && !h->nlmsg_pid && !h->nlmsg_seq);
            assert(((const struct ifinfomsg *)NLMSG_DATA(h))->ifi_flags&IFF_UP);
        }
    }
    assert(recv(monitor,packet,sizeof(packet),0)<0 && errno==EAGAIN);
    assert(!setsockopt(monitor,SOL_NETLINK,NETLINK_DROP_MEMBERSHIP,&group,sizeof(group)));
    length=sizeof(observed);
    assert(!getsockopt(monitor,SOL_NETLINK,NETLINK_LIST_MEMBERSHIPS,&observed,&length) && !observed);
    close(monitor);
    int pass=1;
    assert(!setsockopt(route,SOL_SOCKET,SO_PASSCRED,&pass,sizeof(pass)));
    length=sizeof(pass); pass=0;
    assert(!getsockopt(route,SOL_SOCKET,SO_PASSCRED,&pass,&length) && pass==1);
    assert(sendto(route,&up,sizeof(up),0,(void *)&padded,sizeof(padded))==sizeof(up));
    union { struct cmsghdr alignment; char bytes[CMSG_SPACE(sizeof(struct ucred))]; } credentials;
    struct iovec payload={&ack,sizeof(ack)};
    struct msghdr message={.msg_iov=&payload,.msg_iovlen=1,.msg_control=&credentials,.msg_controllen=sizeof(credentials)};
    assert(recvmsg(route,&message,0)==sizeof(ack) && !ack.result.error && !(message.msg_flags&MSG_CTRUNC));
    struct cmsghdr *header=CMSG_FIRSTHDR(&message);
    assert(header && header->cmsg_level==SOL_SOCKET && header->cmsg_type==SCM_CREDENTIALS
        && header->cmsg_len==CMSG_LEN(sizeof(struct ucred)));
    struct ucred *cred=(void *)CMSG_DATA(header);
    assert(!cred->pid && !cred->uid && !cred->gid);
    loopback_query(route,0,1,NULL,0);
    close(route_copy); close(route);
    int control=socket(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0); assert(control>=0);
    struct ifreq interface={0}; memcpy(interface.ifr_name,"lo",3);
    assert(!ioctl(control,SIOCGIFINDEX,&interface) && interface.ifr_ifindex==1);
    memcpy(interface.ifr_name,"wlan0",6);
    assert(ioctl(control,SIOCGIFINDEX,&interface)<0 && errno==ENODEV); close(control);
    int local[2]; assert(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,local));
    assert(write(inherited[0],"a",1)==1);
    char byte; assert(read(inherited[1],&byte,1)==1 && byte=='a');
    assert(write(local[0],"b",1)==1 && read(local[1],&byte,1)==1 && byte=='b');
    int copy=dup(local[0]); assert(copy>=0);
    assert(!setns(host,CLONE_NEWNET));
    assert(!stat("/proc/self/ns/net",&after) && before.st_ino==after.st_ino);
    struct sockaddr_un address={.sun_family=AF_UNIX}; memcpy(address.sun_path+1,"md-offline",10);
    assert(bind(copy,(void *)&address,sizeof(address))<0 && errno==EOPNOTSUPP);
    int enabled=1;
    assert(setsockopt(copy,SOL_SOCKET,SO_PASSCRED,&enabled,sizeof(enabled))<0 && errno==EOPNOTSUPP);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!unshare(CLONE_NEWUSER|CLONE_NEWNET));
        assert(setns(host,CLONE_NEWNET)<0 && errno==EPERM);
        assert(bind(copy,(void *)&address,sizeof(address))<0 && errno==EOPNOTSUPP);
        _exit(0);
    }
    int status;
    /* EVENT_WAIT: namespace child exits; fixture alarm bounds a stuck syscall. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    close(copy); close(host);
    for (unsigned i=0;i<2;++i) { close(local[i]); close(inherited[i]); }
    puts("PASS offline network: retained identity, loopback lookup, inherited IO, anonymous IPC and namespace authority");
}
static void pidfd_namespaces(void) {
    pid_t outer=fork(); assert(outer>=0);
    if (!outer) {
        assert(!unshare(CLONE_NEWPID));
        pid_t inner=fork(); assert(inner>=0);
        if (!inner) {
            assert(getpid()==1);
            int process=syscall(SYS_pidfd_open,getpid(),0); assert(process>=0);
            const char *names[]={"cgroup","ipc","mnt","net","pid","pid_for_children",NULL,NULL,"user","uts"};
            for (unsigned i=0;i<10;++i) {
                int ns=ioctl(process,0xff01+i,0);
                if (!names[i]) { assert(ns<0 && errno==EOPNOTSUPP); continue; }
                if (ns<0) fprintf(stderr,"pidfd namespace %s: %s\n",names[i],strerror(errno));
                assert(ns>=0 && (fcntl(ns,F_GETFD)&FD_CLOEXEC));
                char path[64]; snprintf(path,sizeof(path),"/proc/self/ns/%s",names[i]);
                struct stat expected,actual;
                assert(!stat(path,&expected) && !fstat(ns,&actual));
                assert(expected.st_dev==actual.st_dev && expected.st_ino==actual.st_ino);
                assert(ioctl(ns,NS_GET_NSTYPE)>0);
                struct statfs fs; assert(!fstatfs(ns,&fs) && fs.f_type==NSFS_MAGIC);
                close(ns);
            }
            assert(ioctl(process,0xff09,1)<0 && errno==EINVAL);
            close(process);
            assert(ioctl(process,0xff09,0)<0 && errno==EBADF);
            _exit(0);
        }
        int status;
        /* EVENT_WAIT: namespace init termination, bounded by the fixture alarm. */
        assert(waitpid(inner,&status,0)==inner && WIFEXITED(status) && !WEXITSTATUS(status));
        _exit(0);
    }
    int status;
    assert(waitpid(outer,&status,0)==outer && WIFEXITED(status) && !WEXITSTATUS(status));
    puts("PASS pidfd namespaces: same identities as proc, CLOEXEC, type, arguments and closed handles");
}
int test_namespace_handles_guest(void) {
    alarm(60);
    assert(!chmod("/",0755) && !chmod("/tmp",01777));
    pidfd_namespaces();
    directory_handles();
    process_directory();
    retained_proc();
    retained_proc_chroot();
    unsupported_namespaces();
    namespace_mounts();
    namespace_mount_lifetime(0);
    offline_network();
    const char *names[]={"user","mnt","uts","ipc"};
    int types[]={CLONE_NEWUSER,CLONE_NEWNS,CLONE_NEWUTS,CLONE_NEWIPC};
    int fds[4]; struct stat before[4];
    for (unsigned i=0;i<4;++i) {
        char path[128], label[128], expected[128];
        snprintf(path,sizeof(path),"/proc/self/ns/%s",names[i]);
        assert(!stat(path,before+i) && S_ISREG(before[i].st_mode));
        fds[i]=open(path,O_RDONLY|O_CLOEXEC); assert(fds[i]>=0);
        struct stat value; struct statfs fs;
        assert(!fstat(fds[i],&value) && value.st_ino==before[i].st_ino && value.st_dev==before[i].st_dev);
        assert(!fstatfs(fds[i],&fs) && fs.f_type==NSFS_MAGIC);
        assert(!statfs(path,&fs) && fs.f_type==NSFS_MAGIC);
        assert(!lstat(path,&value) && S_ISLNK(value.st_mode));
        ssize_t n=readlink(path,label,sizeof(label)-1); assert(n>0); label[n]=0;
        snprintf(expected,sizeof(expected),"%s:[%llu]",names[i],(unsigned long long)before[i].st_ino);
        assert(!strcmp(label,expected));
        assert(open(path,O_WRONLY)<0 && errno==EACCES);
        assert(open(path,O_RDONLY|O_NOFOLLOW)<0 && errno==ELOOP);
        int linkfd=open(path,O_PATH|O_NOFOLLOW|O_CLOEXEC); assert(linkfd>=0);
        assert(!fstat(linkfd,&value) && S_ISLNK(value.st_mode));
        n=readlinkat(linkfd,"",label,sizeof(label)-1); assert(n>0); label[n]=0;
        assert(!strcmp(label,expected));
        assert(readlinkat(linkfd,"",label,1)==1 && label[0]==expected[0]);
        assert(readlinkat(linkfd,"",label,0)<0 && errno==EINVAL);
        int linkcopy=dup(linkfd); assert(linkcopy>=0); close(linkfd);
        n=readlinkat(linkcopy,"",label,sizeof(label)-1); assert(n>0); label[n]=0;
        assert(!strcmp(label,expected)); close(linkcopy);
        assert(readlinkat(fds[i],"",label,sizeof(label))<0 && errno==EINVAL);
        int type=ioctl(fds[i],NS_GET_NSTYPE);
        if (type!=types[i]) fprintf(stderr,"namespace %s ioctl type=%d errno=%d expected=%d\n",names[i],type,errno,types[i]);
        assert(type==types[i]);
        assert(read(fds[i],label,sizeof(label))<0 && errno==EINVAL);
        assert(lseek(fds[i],0,SEEK_SET)<0 && errno==ESPIPE);
        snprintf(path,sizeof(path),"/proc/self/fd/%d",fds[i]);
        n=readlink(path,label,sizeof(label)-1); assert(n>0); label[n]=0;
        assert(!strcmp(label,expected));
        int copy=open(path,O_RDONLY|O_CLOEXEC); assert(copy>=0);
        assert(ioctl(copy,NS_GET_NSTYPE)==types[i]); close(copy);
    }
    uid_t owner=1234;
    assert(!ioctl(fds[0],NS_GET_OWNER_UID,&owner) && !owner);
    assert(ioctl(fds[1],NS_GET_OWNER_UID,&owner)<0 && errno==EINVAL);
    assert(ioctl(fds[0],NS_GET_PARENT)<0 && errno==EPERM);
    for (unsigned i=1;i<4;++i) {
        int related=ioctl(fds[i],NS_GET_USERNS); assert(related>=0);
        struct stat st; assert(!fstat(related,&st));
        assert(st.st_ino==before[0].st_ino && st.st_dev==before[0].st_dev);
        assert(ioctl(related,NS_GET_NSTYPE)==CLONE_NEWUSER);
        assert(fcntl(related,F_GETFD)&FD_CLOEXEC); close(related);
        assert(ioctl(fds[i],NS_GET_PARENT)<0 && errno==EINVAL);
    }
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!unshare(CLONE_NEWUSER|CLONE_NEWNS|CLONE_NEWUTS|CLONE_NEWIPC));
        int self=open("/proc/self/ns/user",O_RDONLY); assert(self>=0);
        assert(ioctl(self,NS_GET_PARENT)<0 && errno==EPERM); close(self);
        assert(ioctl(fds[1],NS_GET_USERNS)<0 && errno==EPERM);
        int uts=open("/proc/self/ns/uts",O_RDONLY); assert(uts>=0);
        int owner=ioctl(uts,NS_GET_USERNS); assert(owner>=0);
        struct stat user, related;
        assert(!stat("/proc/self/ns/user",&user) && !fstat(owner,&related));
        assert(user.st_dev==related.st_dev && user.st_ino==related.st_ino);
        close(owner); close(uts);
        for (unsigned i=0;i<4;++i) {
            char path[128]; snprintf(path,sizeof(path),"/proc/self/ns/%s",names[i]);
            struct stat current, retained;
            assert(!stat(path,&current) && current.st_ino!=before[i].st_ino);
            assert(!fstat(fds[i],&retained) && retained.st_ino==before[i].st_ino);
            close(fds[i]);
        }
        _exit(0);
    }
    int status;
    /* EVENT_WAIT: exact child exit, bounded by the fixture alarm. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    for (unsigned i=0;i<4;++i) {
        struct stat value; assert(!fstat(fds[i],&value) && value.st_ino==before[i].st_ino);
        close(fds[i]);
    }
    assert(!access("/proc/self/ns/net",F_OK));
    assert(!access("/proc/self/ns/pid",F_OK));
    puts("PASS guest namespace handles: stable identities, retained resources, metadata, links, ioctls and independent unshare");
    return 0;
}
