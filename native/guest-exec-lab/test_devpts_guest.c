#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <sched.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/inotify.h>
#include <sys/mount.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <linux/magic.h>
#include <linux/mount.h>
#include <termios.h>
#include <unistd.h>

static unsigned entries(const char *path) {
    DIR *dir=opendir(path);
    if (!dir) fprintf(stderr,"devpts opendir %s: %s\n",path,strerror(errno));
    assert(dir); unsigned count=0;
    for (struct dirent *e;(e=readdir(dir));) {
        if (!strcmp(e->d_name,".") || !strcmp(e->d_name,"..") || !strcmp(e->d_name,"ptmx")) continue;
        ++count;
    }
    closedir(dir); return count;
}
static void watch_event(int fd,int wd,unsigned mask) {
    struct pollfd pollfd={.fd=fd,.events=POLLIN};
    /* EVENT_WAIT: the exact terminal operation must produce its event;
     * timeout fails instead of assuming observation caught up. */
    assert(poll(&pollfd,1,3000)==1 && (pollfd.revents&POLLIN));
    struct inotify_event event;
    assert(read(fd,&event,sizeof(event))==sizeof(event));
    if (event.wd!=wd || event.mask!=mask)
        fprintf(stderr,"PTY event wd=%d mask=%x expected wd=%d mask=%x\n",event.wd,event.mask,wd,mask);
    assert(event.wd==wd && event.mask==mask && !event.len);
}
static void watch_empty(int fd) {
    struct inotify_event event;
    assert(read(fd,&event,sizeof(event))==-1 && errno==EAGAIN);
}
static void watches(const char *root,int private) {
    char path[96]; snprintf(path,sizeof(path),"%s/ptmx",root);
    int master=open(private ? path : "/dev/ptmx",O_RDWR|O_NOCTTY|O_CLOEXEC); assert(master>=0);
    int unlocked=0; assert(!ioctl(master,TIOCSPTLCK,&unlocked));
    unsigned index; assert(!ioctl(master,TIOCGPTN,&index));
    snprintf(path,sizeof(path),"%s/%u",root,index);
    int watch=inotify_init1(IN_CLOEXEC|IN_NONBLOCK); assert(watch>=0);
    int wd=inotify_add_watch(watch,path,IN_OPEN|IN_CLOSE|IN_DELETE_SELF);
    if (wd<0) perror("PTY inotify_add_watch");
    assert(wd>=0);
    if (private) {
        pid_t denied=fork(); assert(denied>=0);
        if (!denied) {
            assert(!setresgid(1234,1234,1234) && !setresuid(1234,1234,1234));
            assert(inotify_add_watch(watch,path,IN_CLOSE)==-1 && errno==EACCES);
            assert(open(path,O_WRONLY|O_APPEND|O_NOCTTY)==-1 && errno==EACCES);
            _exit(0);
        }
        int status;
        assert(waitpid(denied,&status,0)==denied && WIFEXITED(status) && !WEXITSTATUS(status));
    }
    int pin=open(path,O_PATH|O_CLOEXEC); assert(pin>=0); close(pin); watch_empty(watch);
    const char *alias="/tmp/pts-append-alias";
    int node=open(alias,O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600); assert(node>=0); close(node);
    assert(!mount(path,alias,NULL,MS_BIND,NULL));
    for (int variant=0;variant<8;++variant) {
        int caller=variant&1,append=variant&2;
        int slave=open(variant&4 ? alias : path,O_RDWR|O_CLOEXEC|(caller ? 0 : O_NOCTTY)|(append ? O_APPEND : 0));
        if (slave<0) fprintf(stderr,"PTY open %s caller=%d: %s\n",path,caller,strerror(errno));
        assert(slave>=0);
        assert(!!(fcntl(slave,F_GETFL)&O_APPEND)==!!append);
        if (append) {
            char byte;
            assert(write(slave,"a",1)==1);
            struct pollfd input={.fd=master,.events=POLLIN};
            /* EVENT_WAIT: terminal output must reach its master; timeout fails. */
            assert(poll(&input,1,3000)==1 && read(master,&byte,1)==1 && byte=='a');
        }
        watch_event(watch,wd,IN_OPEN);
        int duplicate=dup(slave); assert(duplicate>=0); close(slave); watch_empty(watch);
        close(duplicate); watch_event(watch,wd,IN_CLOSE_WRITE); watch_empty(watch);
    }
    assert(!umount(alias) && !unlink(alias));
    int slave=ioctl(master,TIOCGPTPEER,O_RDONLY|O_CLOEXEC|O_NOCTTY);
    if (slave<0) fprintf(stderr,"PTY peer %s: %s\n",path,strerror(errno));
    assert(slave>=0);
    watch_event(watch,wd,IN_OPEN);
    int pipefd[2]; assert(!pipe2(pipefd,O_CLOEXEC));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(pipefd[1]); close(watch); close(master);
        char byte; assert(read(pipefd[0],&byte,1)==1);
        close(slave); _exit(0);
    }
    close(pipefd[0]); close(slave); watch_empty(watch);
    assert(write(pipefd[1],"x",1)==1); close(pipefd[1]);
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    watch_event(watch,wd,IN_CLOSE_NOWRITE); watch_empty(watch);
    assert(!inotify_rm_watch(watch,wd)); watch_event(watch,wd,IN_IGNORED);
    wd=inotify_add_watch(watch,path,IN_OPEN|IN_ONESHOT); assert(wd>=0);
    slave=open(path,O_RDWR|O_NOCTTY|O_CLOEXEC); assert(slave>=0);
    watch_event(watch,wd,IN_OPEN); watch_event(watch,wd,IN_IGNORED);
    close(slave); watch_empty(watch);
    wd=inotify_add_watch(watch,path,IN_DELETE_SELF); assert(wd>=0);
    close(master); watch_event(watch,wd,IN_DELETE_SELF); watch_event(watch,wd,IN_IGNORED);
    close(watch);
    puts("PASS devpts watches: O_PATH, caller open, dup, fork, last close, oneshot and master retirement");
}
static void limits(void) {
    const char *path="/tmp/pts-limit";
    assert(!mkdir(path,0700));
    const char *invalid[]={"max=-1","max=1048577","max=4294967296","max=1x"};
    for (unsigned i=0;i<sizeof(invalid)/sizeof(*invalid);++i)
        assert(mount("devpts",path,"devpts",0,invalid[i])==-1 && errno==EINVAL);
    for (int modern=0;modern<2;++modern) {
        if (modern) {
            int context=(int)syscall(SYS_fsopen,"devpts",FSOPEN_CLOEXEC); assert(context>=0);
            assert(!syscall(SYS_fsconfig,context,FSCONFIG_SET_FLAG,"newinstance",NULL,0));
            assert(!syscall(SYS_fsconfig,context,FSCONFIG_SET_STRING,"ptmxmode","0666",0));
            assert(!syscall(SYS_fsconfig,context,FSCONFIG_SET_STRING,"max","1",0));
            assert(!syscall(SYS_fsconfig,context,FSCONFIG_CMD_CREATE,NULL,NULL,0));
            int tree=(int)syscall(SYS_fsmount,context,FSMOUNT_CLOEXEC,0); assert(tree>=0);
            assert(!syscall(SYS_move_mount,tree,"",AT_FDCWD,path,MOVE_MOUNT_F_EMPTY_PATH));
            close(tree); close(context);
        } else assert(!mount("devpts",path,"devpts",0,"newinstance,ptmxmode=0666,max=1"));
        int pin=open("/tmp/pts-limit/ptmx",O_PATH|O_CLOEXEC); assert(pin>=0);
        assert(!entries(path));
        int master=open("/tmp/pts-limit/ptmx",O_RDWR|O_NOCTTY|O_CLOEXEC);
        /* Host-native numbering may have already occupied index zero. */
        if (master>=0) {
            unsigned index; assert(!ioctl(master,TIOCGPTN,&index) && !index);
            int duplicate=dup(master); assert(duplicate>=0); close(master);
            assert(open("/tmp/pts-limit/ptmx",O_RDWR|O_NOCTTY)==-1 && errno==ENOSPC);
            close(duplicate);
        } else assert(errno==ENOSPC);
        assert(!entries(path));
        close(pin);
        assert(!umount2(path,0));
    }
    assert(!rmdir(path));
}
static void reconfigure(void) {
    const char *path="/tmp/pts-remount",*alias="/tmp/pts-remount-alias";
    assert(!mkdir(path,0700) && !mkdir(alias,0700));
    assert(!mount("devpts",path,"devpts",0,"ptmxmode=666,mode=600"));
    assert(!mount(path,alias,NULL,MS_BIND,NULL));
    int master=open("/tmp/pts-remount/ptmx",O_RDWR|O_NOCTTY|O_CLOEXEC); assert(master>=0);
    int unlock=0; assert(!ioctl(master,TIOCSPTLCK,&unlock));
    int slave=ioctl(master,TIOCGPTPEER,O_RDWR|O_NOCTTY|O_CLOEXEC); assert(slave>=0);
    int context=(int)syscall(SYS_fspick,AT_FDCWD,alias,FSPICK_CLOEXEC); assert(context>=0);
    const char *keys[]={"mode","ptmxmode","gid"},*values[]={"620","600","3"};
    for (unsigned i=0;i<3;++i)
        assert(!syscall(SYS_fsconfig,context,FSCONFIG_SET_STRING,keys[i],values[i],0));
    assert(!syscall(SYS_fsconfig,context,FSCONFIG_CMD_RECONFIGURE,NULL,NULL,0));
    struct stat st;
    assert(!stat("/tmp/pts-remount/ptmx",&st) && (st.st_mode&0777)==0600 && !st.st_gid);
    assert(!fstat(slave,&st) && (st.st_mode&0777)==0600 && !st.st_gid);
    int next=open("/tmp/pts-remount-alias/ptmx",O_RDWR|O_NOCTTY|O_CLOEXEC); assert(next>=0);
    assert(!ioctl(next,TIOCSPTLCK,&unlock));
    int peer=ioctl(next,TIOCGPTPEER,O_RDWR|O_NOCTTY|O_CLOEXEC); assert(peer>=0);
    assert(!fstat(peer,&st) && (st.st_mode&0777)==0620 && st.st_gid==3);
    close(peer); close(next);
    assert(!syscall(SYS_fsconfig,context,FSCONFIG_SET_STRING,"mode","888",0));
    assert(syscall(SYS_fsconfig,context,FSCONFIG_CMD_RECONFIGURE,NULL,NULL,0)==-1 && errno==EINVAL);
    assert(!stat("/tmp/pts-remount/ptmx",&st) && (st.st_mode&0777)==0600);
    close(context);
    assert(!mount("devpts",alias,"devpts",MS_REMOUNT,"ptmxmode=666,max=0"));
    assert(open("/tmp/pts-remount/ptmx",O_RDWR|O_NOCTTY)==-1 && errno==ENOSPC);
    assert(!fstat(slave,&st) && (st.st_mode&0777)==0600 && !st.st_gid);
    context=(int)syscall(SYS_fspick,AT_FDCWD,path,FSPICK_CLOEXEC); assert(context>=0);
    assert(!syscall(SYS_fsconfig,context,FSCONFIG_SET_FLAG,"ro",NULL,0));
    assert(!syscall(SYS_fsconfig,context,FSCONFIG_CMD_RECONFIGURE,NULL,NULL,0));
    assert(chmod("/tmp/pts-remount-alias/ptmx",0666)==-1 && errno==EROFS);
    assert(!syscall(SYS_fsconfig,context,FSCONFIG_SET_FLAG,"rw",NULL,0));
    assert(!syscall(SYS_fsconfig,context,FSCONFIG_CMD_RECONFIGURE,NULL,NULL,0));
    assert(!stat("/tmp/pts-remount/ptmx",&st) && !(st.st_mode&0777));
    assert(!chmod("/tmp/pts-remount-alias/ptmx",0666));
    next=open("/tmp/pts-remount/ptmx",O_RDWR|O_NOCTTY|O_CLOEXEC); assert(next>=0);
    close(next); close(context); close(slave); close(master);
    assert(!umount2(alias,0) && !umount2(path,0));
    assert(!rmdir(alias) && !rmdir(path));
    puts("PASS devpts remount: both APIs, shared superblock, retained slaves, defaults and invalid options");
}
static void ownership(void) {
    struct stat st;
    int directory=open("/tmp/pts-a",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(directory>=0);
    int modefd=open("/tmp/pts-a",O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(modefd>=0);
    int ptmx=open("/tmp/pts-a/ptmx",O_PATH|O_CLOEXEC); assert(ptmx>=0);
    assert(!fchownat(directory,"",1234,5678,AT_EMPTY_PATH));
    assert(!fchownat(ptmx,"",2345,6789,AT_EMPTY_PATH));
    assert(!chmod("/tmp/pts-a",0711));
    assert(!fstat(directory,&st) && st.st_uid==1234 && st.st_gid==5678 && (st.st_mode&07777)==0711);
    assert(!fstat(ptmx,&st) && st.st_uid==2345 && st.st_gid==6789 && (st.st_mode&07777)==0666);
    assert(!stat("/tmp/pts-b",&st) && !st.st_uid && !st.st_gid && (st.st_mode&07777)==0755);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!setresgid(1000,1000,1000) && !setresuid(1000,1000,1000));
        assert(fchownat(directory,"",1000,1000,AT_EMPTY_PATH)==-1 && errno==EPERM);
        assert(fchownat(ptmx,"",1000,1000,AT_EMPTY_PATH)==-1 && errno==EPERM);
        assert(fchmod(modefd,0777)==-1 && errno==EPERM);
        _exit(0);
    }
    int status;
    /* EVENT_WAIT: child checks unprivileged metadata denial; fixture alarm bounds failure. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    close(directory); close(ptmx); close(modefd);
}
static void peer_transfer(int master,int slave) {
    int sockets[2]; assert(!socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,sockets));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(sockets[0]); close(master); close(slave);
        assert(!unshare(CLONE_NEWNS));
        assert(!umount2("/tmp/pts-a",MNT_DETACH));
        assert(!mount("devpts","/tmp/pts-a","devpts",0,"newinstance,ptmxmode=0666"));
        char byte; struct iovec iov={&byte,1};
        union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control;
        struct msghdr msg={.msg_iov=&iov,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
        assert(recvmsg(sockets[1],&msg,MSG_CMSG_CLOEXEC)==1 && !(msg.msg_flags&(MSG_TRUNC|MSG_CTRUNC)));
        struct cmsghdr *c=CMSG_FIRSTHDR(&msg); assert(c && c->cmsg_type==SCM_RIGHTS);
        int received; memcpy(&received,CMSG_DATA(c),sizeof(received));
        int peer=ioctl(received,TIOCGPTPEER,O_RDWR|O_NOCTTY|O_NONBLOCK);
        if (peer<0) perror("TIOCGPTPEER");
        assert(peer>=0 && !(fcntl(peer,F_GETFD)&FD_CLOEXEC) && (fcntl(peer,F_GETFL)&O_NONBLOCK));
        struct stat st; assert(!fstat(peer,&st) && !st.st_uid && !st.st_gid && (st.st_mode&0777)==0600);
        assert(!fchown(peer,1234,5678) && !fchmod(peer,0620));
        assert(!fstat(peer,&st) && st.st_uid==1234 && st.st_gid==5678 && (st.st_mode&0777)==0620);
        assert(!entries("/tmp/pts-a"));
        close(peer); close(received); close(sockets[1]); _exit(0);
    }
    close(sockets[1]);
    char byte=0; struct iovec iov={&byte,1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    struct msghdr msg={.msg_iov=&iov,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
    struct cmsghdr *c=CMSG_FIRSTHDR(&msg);
    c->cmsg_level=SOL_SOCKET; c->cmsg_type=SCM_RIGHTS; c->cmsg_len=CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(c),&master,sizeof(master)); assert(sendmsg(sockets[0],&msg,0)==1);
    int status;
    /* EVENT_WAIT: peer metadata after namespace replacement; alarm bounds failure. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    struct stat st; assert(!fstat(slave,&st) && st.st_uid==1234 && st.st_gid==5678 && (st.st_mode&0777)==0620);
    assert(!fchown(slave,0,0) && !fchmod(slave,0600)); close(sockets[0]);
}
int test_devpts_guest(int pid_namespace) {
    alarm(45);
    if (pid_namespace) {
        assert(!unshare(CLONE_NEWPID));
        pid_t child=fork(); assert(child>=0);
        if (!child) _exit(test_devpts_guest(0));
        int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
        puts("PASS guest devpts: PID namespace terminal ownership"); return 0;
    }
    assert(!unshare(CLONE_NEWNS));
    assert(!mkdir("/tmp/pts-a",0700) && !mkdir("/tmp/pts-b",0700));
    assert(!mount("devpts","/tmp/pts-a","devpts",MS_NOSUID|MS_NOEXEC,"newinstance,ptmxmode=0666,mode=620,max=1048576"));
    assert(!mount("devpts","/tmp/pts-b","devpts",MS_NOSUID|MS_NOEXEC,"newinstance,ptmxmode=0666,mode=600"));
    assert(!entries("/tmp/pts-a") && !entries("/tmp/pts-b"));
    limits();
    reconfigure();
    watches("/tmp/pts-a",1);
    watches("/dev/pts",0);
    ownership();
    int master=open("/tmp/pts-a/ptmx",O_RDWR|O_NOCTTY|O_CLOEXEC); assert(master>=0);
    unsigned index; assert(!ioctl(master,TIOCGPTN,&index));
    int locked_peer=ioctl(master,TIOCGPTPEER,O_RDWR|O_NOCTTY);
    if (locked_peer!=-1 || errno!=EIO) fprintf(stderr,"locked peer=%d errno=%d\n",locked_peer,errno);
    assert(locked_peer==-1 && errno==EIO);
    int locked=0; assert(!ioctl(master,TIOCSPTLCK,&locked));
    char path[96]; snprintf(path,sizeof(path),"/tmp/pts-a/%u",index);
    struct stat st; assert(!stat(path,&st) && (st.st_mode&0777)==0620 && S_ISCHR(st.st_mode));
    assert(!st.st_uid && !st.st_gid);
    int slave=open(path,O_RDWR|O_NOCTTY|O_CLOEXEC); assert(slave>=0);
    assert(!chmod(path,0600) && !fstat(slave,&st) && (st.st_mode&0777)==0600);
    int peer=ioctl(master,TIOCGPTPEER,O_RDWR|O_NOCTTY|O_CLOEXEC);
    assert(peer>=0 && (fcntl(peer,F_GETFD)&FD_CLOEXEC));
    assert(!fstat(peer,&st) && (st.st_mode&0777)==0600); close(peer);
    peer_transfer(master,slave);
    struct termios term; assert(!tcgetattr(slave,&term)); cfmakeraw(&term); assert(!tcsetattr(slave,TCSANOW,&term));
    char byte;
    assert(write(master,"x",1)==1 && read(slave,&byte,1)==1 && byte=='x');
    assert(write(slave,"y",1)==1 && read(master,&byte,1)==1 && byte=='y');
    struct statfs filesystem; assert(!fstatfs(slave,&filesystem) && filesystem.f_type==DEVPTS_SUPER_MAGIC);
    for (int via_peer=0;via_peer<2;++via_peer) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(slave);
        assert(setsid()==getpid());
        int terminal=via_peer ? ioctl(master,TIOCGPTPEER,O_RDWR|O_CLOEXEC) : open(path,O_RDWR|O_CLOEXEC);
        assert(terminal>=0); close(master);
        pid_t foreground=0; assert(!ioctl(terminal,TIOCGPGRP,&foreground) && foreground==getpgrp());
        assert(!ioctl(terminal,TIOCSPGRP,&foreground));
        int controlling=open("/dev/tty",O_RDWR|O_CLOEXEC);
        if (controlling<0) perror("open controlling tty");
        assert(controlling>=0);
        assert(!ioctl(controlling,TIOCGPGRP,&foreground) && foreground==getpgrp());
        /* Persistent FD selectors survive close/reuse and must not hide PID
         * translation when this descriptor later names a terminal. */
        for (int kind=0;kind<2;++kind) {
            int reused=kind ? open("/proc/self/status",O_RDONLY|O_CLOEXEC)
                : inotify_init1(IN_CLOEXEC);
            assert(reused>=0);
            assert(dup3(controlling,reused,O_CLOEXEC)==reused);
            assert(!ioctl(reused,TIOCGPGRP,&foreground) && foreground==getpgrp());
            assert(!ioctl(reused,TIOCSPGRP,&foreground));
            pid_t session=0;
            assert(!ioctl(reused,TIOCGSID,&session) && session==getsid(0));
            close(reused);
        }
        close(controlling); close(terminal); _exit(0);
    }
    int status;
    /* EVENT_WAIT: session/controlling-terminal assertions finish in the child;
     * the fixture alarm bounds failure, with no settling delay. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    }
    snprintf(path,sizeof(path),"/tmp/pts-b/%u",index);
    assert(open(path,O_RDWR)<0 && errno==ENOENT);
    assert(entries("/tmp/pts-a")==1 && !entries("/tmp/pts-b"));
    close(slave); close(master);
    assert(!entries("/tmp/pts-a"));
    assert(!umount2("/tmp/pts-a",0) && !umount2("/tmp/pts-b",0));
    assert(!rmdir("/tmp/pts-a") && !rmdir("/tmp/pts-b"));
    puts("PASS guest devpts: private membership, native terminal IO, controlling session, logical modes and close lifetime");
    return 0;
}
