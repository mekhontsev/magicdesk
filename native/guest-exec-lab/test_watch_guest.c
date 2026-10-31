#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <poll.h>
#include <sched.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/sendfile.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "watch line=%d %s errno=%d\n", __LINE__, #x, errno); exit(1); } } while (0)
static union { struct inotify_event align; char bytes[65536]; } buffer;
static ssize_t events(int fd) {
    struct pollfd p = {fd, POLLIN, 0};
    /* EVENT_WAIT: actual inotify readability; expiry fails the fixture. */
    CHECK(poll(&p, 1, 5000) == 1 && (p.revents & POLLIN));
    ssize_t n = read(fd, buffer.bytes, sizeof(buffer.bytes));
    CHECK(n > 0); return n;
}
static struct inotify_event *one(int fd, unsigned mask, const char *name) {
    ssize_t n = events(fd);
    struct inotify_event *e = &buffer.align;
    CHECK(n == (ssize_t)(sizeof(*e) + e->len));
    CHECK(e->mask == mask);
    CHECK(name ? e->len && !strcmp(name, e->name) : !e->len);
    return e;
}
static void create(const char *name) {
    int fd = open(name, O_CREAT | O_EXCL | O_RDWR, 0600); CHECK(fd >= 0); CHECK(!close(fd));
}
static int filter_count(FILE *status) {
    rewind(status);
    char line[256]; int count = -1;
    while (fgets(line, sizeof(line), status)) if (sscanf(line, "Seccomp_filters: %d", &count) == 1) break;
    CHECK(!ferror(status) && count > 0); return count;
}
static int filters(void) {
    FILE *status = fopen("/proc/self/status", "r"); CHECK(status);
    int count=filter_count(status);
    CHECK(!fclose(status)); return count;
}
static int exec_watch(int fd, int status_fd, int round, int expected) {
    one(fd, IN_CREATE, "exec-event");
    CHECK(!unlink("exec-event"));
    /* Exec may close infrastructure and change the lowest free slot. Reuse an
     * exact selector to test filter retention, not the incidental FD layout. */
    int transient = fcntl(fd,F_DUPFD_CLOEXEC,64); CHECK(transient == 64);
    CHECK(!close(transient));
    /* The observer is a typed proc FD too. Retain it across exec so measuring
     * filter growth cannot itself activate a different descriptor class. */
    CHECK(fcntl(status_fd,F_GETFD)==0);
    FILE *status=fdopen(status_fd,"r"); CHECK(status);
    int count=filter_count(status);
    if (expected && count!=expected)
        fprintf(stderr,"watch exec round=%d filters=%d expected=%d transient=%d\n",round,count,expected,transient);
    if (expected) CHECK(count == expected);
    if (!round) { CHECK(!fclose(status) && !close(fd)); puts("PASS inherited watch and stable filters across 64 execs"); return 0; }
    create("exec-event");
    char descriptor[24], observer[24], remaining[24], installed[24];
    snprintf(descriptor, sizeof(descriptor), "%d", fd);
    snprintf(observer, sizeof(observer), "%d", status_fd);
    snprintf(remaining, sizeof(remaining), "%d", round - 1);
    snprintf(installed, sizeof(installed), "%d", count);
    execl("/bin/watch", "watch", "exec", descriptor, observer, remaining, installed, (char *)NULL);
    CHECK(0); return 1;
}
static volatile sig_atomic_t interrupted;
static int signal_writer = -1;
static void signal_handler(int number) {
    (void)number; interrupted++;
    if (signal_writer >= 0 && write(signal_writer, "x", 1) != 1) _exit(1);
}
struct reader { int fd; char name[16]; };
static void *read_thread(void *context) {
    struct reader *r = context;
    char event[32]; CHECK(read(r->fd, event, sizeof(event)) == 32);
    struct inotify_event *e = (void *)event;
    CHECK(e->mask == IN_CREATE && strlen(e->name) < sizeof(r->name));
    strcpy(r->name, e->name); return NULL;
}
static void transfer_fds(int socket, const int *fds, unsigned count) {
    CHECK(count && count<=3);
    union { struct cmsghdr align; char bytes[CMSG_SPACE(3*sizeof(int))]; } control = {0};
    char value = 1; struct iovec vector = {&value, 1};
    struct msghdr message = {.msg_iov = &vector, .msg_iovlen = 1, .msg_control = control.bytes,
        .msg_controllen = CMSG_SPACE(count*sizeof(int))};
    struct cmsghdr *h = CMSG_FIRSTHDR(&message);
    h->cmsg_level = SOL_SOCKET; h->cmsg_type = SCM_RIGHTS; h->cmsg_len = CMSG_LEN(count*sizeof(int));
    memcpy(CMSG_DATA(h), fds, count*sizeof(int)); CHECK(sendmsg(socket, &message, 0) == 1);
}
static void transfer(int socket, int fd) { transfer_fds(socket,&fd,1); }
static int receive_fd(int socket, int batch) {
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    char value; struct iovec vector = {&value, 1};
    struct msghdr message = {.msg_iov = &vector, .msg_iovlen = 1, .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    if (batch) {
        struct mmsghdr messages[] = {{.msg_hdr = message}};
        CHECK(recvmmsg(socket, messages, 1, MSG_CMSG_CLOEXEC, NULL) == 1 && messages[0].msg_len == 1);
        message = messages[0].msg_hdr;
    } else CHECK(recvmsg(socket, &message, MSG_CMSG_CLOEXEC) == 1);
    struct cmsghdr *h = CMSG_FIRSTHDR(&message);
    CHECK(h && h->cmsg_level == SOL_SOCKET && h->cmsg_type == SCM_RIGHTS && h->cmsg_len == CMSG_LEN(sizeof(int)));
    int fd; memcpy(&fd, CMSG_DATA(h), sizeof(fd)); return fd;
}
static unsigned descriptor_count(void) {
    DIR *directory=opendir("/proc/self/fd"); CHECK(directory);
    unsigned count=0;
    for (struct dirent *entry;(entry=readdir(directory));)
        if (entry->d_name[0]!='.') ++count;
    CHECK(!closedir(directory)); return count;
}
static int receive_filtered(void) {
    unsigned before=descriptor_count();
    int sockets[2],pipefd[2];
    CHECK(!socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,sockets) && !pipe2(pipefd,O_NONBLOCK|O_CLOEXEC));
    pid_t sender=fork(); CHECK(sender>=0);
    if (!sender) {
        CHECK(!close(sockets[1]) && !close(pipefd[0]));
        int watch=inotify_init1(IN_NONBLOCK|IN_CLOEXEC); CHECK(watch>=0);
        int fds[]={pipefd[1],watch,pipefd[1]}; transfer_fds(sockets[0],fds,3);
        CHECK(!close(watch) && !close(pipefd[1]) && !close(sockets[0])); _exit(0);
    }
    CHECK(!close(sockets[0]) && !close(pipefd[1]));
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_seccomp,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_recvmsg,0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[2])),
        BPF_JUMP(BPF_JMP|BPF_JSET|BPF_K,MSG_PEEK,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    CHECK(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    CHECK(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    int installed=filters();
    CHECK(syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program)==-1 && errno==EPERM);
    union { struct cmsghdr align; char bytes[CMSG_SPACE(3*sizeof(int))]; } control={0};
    int fds[]={-1,-1,-1}; memcpy(CMSG_DATA(&control.align),fds,sizeof(fds));
    char byte=0; struct iovec vector={&byte,1};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
    int previous[1024];
    for (unsigned i=0;i<1024;++i) previous[i]=fcntl((int)i,F_GETFD);
    errno=0; CHECK(recvmsg(sockets[1],&message,MSG_CMSG_CLOEXEC|MSG_PEEK)==-1 && errno==EACCES && !byte);
    memcpy(fds,CMSG_DATA(&control.align),sizeof(fds));
    CHECK(fds[0]==-1 && fds[1]==-1 && fds[2]==-1);
    for (unsigned i=0;i<1024;++i) CHECK(fcntl((int)i,F_GETFD)==previous[i]);
    CHECK(recvmsg(sockets[1],&message,MSG_CMSG_CLOEXEC)==1 && byte==1 && !(message.msg_flags&MSG_CTRUNC));
    struct cmsghdr *header=CMSG_FIRSTHDR(&message);
    CHECK(header && header->cmsg_level==SOL_SOCKET && header->cmsg_type==SCM_RIGHTS
        && header->cmsg_len==CMSG_LEN(sizeof(fds)) && message.msg_controllen==CMSG_SPACE(sizeof(fds)));
    memcpy(fds,CMSG_DATA(header),sizeof(fds));
    for (unsigned i=0;i<3;++i) CHECK(fds[i]>=0 && fcntl(fds[i],F_GETFD)==FD_CLOEXEC);
    CHECK(fds[0]!=fds[1] && fds[0]!=fds[2] && fds[1]!=fds[2]);
    CHECK(read(fds[1],buffer.bytes,sizeof(buffer.bytes))==-1 && errno==EAGAIN);
    CHECK(write(fds[0],"a",1)==1 && write(fds[2],"b",1)==1);
    char payload[2]; CHECK(read(pipefd[0],payload,sizeof(payload))==2 && !memcmp(payload,"ab",2));
    for (unsigned i=0;i<3;++i) CHECK(!close(fds[i]));
    CHECK(filters()==installed);
    unsigned channels=0;
    for (unsigned i=0;i<1024;++i) {
        int flags=fcntl((int)i,F_GETFD);
        if (flags==previous[i]) continue;
        CHECK(previous[i]<0 && flags==FD_CLOEXEC);
        int type=0; socklen_t size=sizeof(type);
        CHECK(!getsockopt((int)i,SOL_SOCKET,SO_TYPE,&type,&size) && type==SOCK_SEQPACKET);
        ++channels;
    }
    CHECK(channels==1 && !close(sockets[1]));
    int status; CHECK(waitpid(sender,&status,0)==sender && WIFEXITED(status) && !WEXITSTATUS(status));
    CHECK(read(pipefd[0],&byte,1)==0 && !close(pipefd[0]));
    CHECK(descriptor_count()==before+channels);
    puts("PASS sandboxed receive: denied peek preserves queue and FDs, prepared rights need no new filter, final pipe EOF");
    return 0;
}
static int shared(const char *mode, const char *directory) {
    if (!strcmp(mode, "setup")) { CHECK(!mkdir(directory, 0700)); return 0; }
    CHECK(!chdir(directory));
    if (!strcmp(mode, "writer")) {
        create("payload"); CHECK(!rename("payload", "renamed")); CHECK(!unlink("renamed"));
        return 0;
    }
    CHECK(!strcmp(mode, "observer"));
    int fd = inotify_init1(IN_CLOEXEC); CHECK(fd >= 0);
    int wd = inotify_add_watch(fd, ".", IN_CREATE | IN_MOVED_FROM | IN_MOVED_TO | IN_DELETE); CHECK(wd > 0);
    puts("READY"); fflush(stdout);
    unsigned masks[] = {IN_CREATE, IN_MOVED_FROM, IN_MOVED_TO, IN_DELETE};
    const char *names[] = {"payload", "payload", "renamed", "renamed"};
    unsigned seen = 0, cookie = 0;
    while (seen < 4) {
        ssize_t n = events(fd);
        for (size_t pos = 0; pos < (size_t)n;) {
            struct inotify_event *e = (void *)(buffer.bytes + pos);
            CHECK(seen < 4 && e->wd == wd && e->mask == masks[seen] && !strcmp(e->name, names[seen]));
            if (seen == 1) { cookie = e->cookie; CHECK(cookie); }
            if (seen == 2) CHECK(e->cookie == cookie);
            seen++; pos += sizeof(*e) + e->len;
        }
    }
    CHECK(!close(fd)); puts("PASS independent launch journal"); fflush(stdout);
    return 0;
}
static void describe_data(int fd, const char *phase) {
    printf("PHASE %s\n", phase);
    for (;;) {
        ssize_t size = read(fd, buffer.bytes, sizeof(buffer.bytes));
        if (size < 0 && errno == EAGAIN) break;
        CHECK(size > 0);
        for (size_t pos = 0; pos < (size_t)size;) {
            struct inotify_event *e = (void *)(buffer.bytes + pos);
            CHECK(!(e->mask & IN_Q_OVERFLOW));
            printf("EVENT mask=%x name=%s\n", e->mask, e->len ? e->name : "-");
            pos += sizeof(*e) + e->len;
        }
    }
}
static int alias_history(const char *directory) {
    CHECK(!mkdir(directory, 0700) && !chdir(directory));
    int file = open("first", O_CREAT | O_EXCL | O_RDWR, 0600); CHECK(file >= 0);
    CHECK(!link("first", "alias"));
    int fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC); CHECK(fd >= 0);
    CHECK(inotify_add_watch(fd, ".", IN_MODIFY | IN_CLOSE_WRITE) > 0);
    CHECK(write(file, "a", 1) == 1); describe_data(fd, "linked-write");
    CHECK(!rename("first", "moved"));
    CHECK(write(file, "b", 1) == 1); describe_data(fd, "renamed-write");
    CHECK(!unlink("moved"));
    CHECK(write(file, "c", 1) == 1); describe_data(fd, "unlinked-open-write");
    CHECK(!close(file)); describe_data(fd, "last-close");
    CHECK(!close(fd));
    return 0;
}
static unsigned limit_read(int fd) {
    char text[32]={0}; CHECK(pread(fd,text,sizeof(text)-1,0)>0);
    char *end; unsigned long value=strtoul(text,&end,10);
    CHECK(end!=text && *end=='\n' && !end[1]); return (unsigned)value;
}
static void limit_write(int fd,unsigned value) {
    char text[32]; int n=snprintf(text,sizeof(text),"%u\n",value);
    CHECK(pwrite(fd,text,n,0)==n && limit_read(fd)==value);
}
static int quotas(void) {
    CHECK(!getuid());
    const char *names[]={"max_user_instances","max_user_watches","max_queued_events"};
    int limits[3]; unsigned saved[3];
    for (unsigned i=0;i<3;++i) {
        char path[128]; snprintf(path,sizeof(path),"/proc/sys/fs/inotify/%s",names[i]);
        limits[i]=open(path,O_RDWR|O_CLOEXEC); CHECK(limits[i]>=0);
        struct stat st; CHECK(!fstat(limits[i],&st) && (st.st_mode&0777)==0644);
        saved[i]=limit_read(limits[i]);
        const char *bad[]={"-1","2147483648","1x","1 2"," "};
        for (unsigned n=0;n<sizeof(bad)/sizeof(*bad);++n)
            CHECK(pwrite(limits[i],bad[n],strlen(bad[n]),0)==-1 && errno==EINVAL);
        CHECK(pwrite(limits[i],"99",2,1)==2 && limit_read(limits[i])==saved[i]);
    }
    limit_write(limits[0],2);
    int a=inotify_init1(IN_NONBLOCK),b=inotify_init1(IN_NONBLOCK); CHECK(a>=0 && b>=0);
    CHECK(inotify_init1(0)<0 && errno==EMFILE);
    pid_t child=fork(); CHECK(child>=0);
    if (!child) {
        CHECK(!unshare(CLONE_NEWNS));
        CHECK(inotify_init1(0)<0 && errno==EMFILE);
        CHECK(!setuid(1000));
        CHECK(pwrite(limits[0],"100",3,0)<0 && errno==EPERM);
        int independent=inotify_init1(0); CHECK(independent>=0);
        CHECK(!close(independent)); _exit(0);
    }
    int status; CHECK(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    CHECK(!close(a) && !close(b));
    limit_write(limits[0],8);
    CHECK(!mkdir("/tmp/watch-quota",0777) && !chdir("/tmp/watch-quota"));
    limit_write(limits[2],1);
    a=inotify_init1(IN_NONBLOCK); CHECK(a>=0);
    limit_write(limits[2],0);
    b=inotify_init1(IN_NONBLOCK); CHECK(b>=0);
    limit_write(limits[1],1);
    int wd=inotify_add_watch(a,".",IN_CREATE); CHECK(wd>0);
    CHECK(inotify_add_watch(b,".",IN_CREATE)<0 && errno==ENOSPC);
    CHECK(inotify_add_watch(a,".",IN_CREATE|IN_MASK_ADD)==wd);
    limit_write(limits[1],2);
    CHECK(inotify_add_watch(b,".",IN_CREATE)>0);
    limit_write(limits[1],0);
    create("event"); one(a,IN_CREATE,"event"); one(b,IN_Q_OVERFLOW,NULL);
    CHECK(inotify_add_watch(a,"event",IN_MODIFY)<0 && errno==ENOSPC);
    CHECK(!inotify_rm_watch(a,wd));
    CHECK(!close(a) && !close(b) && !unlink("event"));
    CHECK(!chdir("/") && !rmdir("/tmp/watch-quota"));
    for (unsigned i=0;i<3;++i) { limit_write(limits[i],saved[i]); CHECK(!close(limits[i])); }
    puts("PASS guest inotify quotas: real sysctl, UID/mount sharing, FD authorization, retained queue limits");
    return 0;
}
int main(int argc, char **argv) {
    if (argc==2 && !strcmp(argv[1],"quotas")) return quotas();
    if (argc == 2 && !strcmp(argv[1], "receive-filtered")) return receive_filtered();
    if (argc == 3 && !strcmp(argv[1], "aliases")) return alias_history(argv[2]);
    if (argc == 3 && !strcmp(argv[1], "protected-all")) {
        CHECK(!prctl(PR_SET_DUMPABLE, 0));
        argv[1] = argv[2]; argc = 2;
    }
    if (argc == 6 && !strcmp(argv[1], "exec"))
        return exec_watch(atoi(argv[2]), atoi(argv[3]), atoi(argv[4]), atoi(argv[5]));
    if (argc == 4 && !strcmp(argv[1], "shared")) return shared(argv[2], argv[3]);
    if (argc == 3 && !strcmp(argv[1], "protected")) {
        CHECK(!mkdir(argv[2], 0700) && !chdir(argv[2]));
        int fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC); CHECK(fd >= 0);
        CHECK(inotify_add_watch(fd, ".", IN_CREATE) > 0);
        CHECK(!prctl(PR_SET_DUMPABLE, 0));
        create("protected");
        one(fd, IN_CREATE, "protected");
        CHECK(prctl(PR_GET_DUMPABLE) == 0);
        int nonblocking=0;
        CHECK(!ioctl(fd,FIONBIO,&nonblocking) && !(fcntl(fd,F_GETFL)&O_NONBLOCK));
        nonblocking=1;
        CHECK(!ioctl(fd,FIONBIO,&nonblocking) && (fcntl(fd,F_GETFL)&O_NONBLOCK));
        int bytes = -1; CHECK(!ioctl(fd, FIONREAD, &bytes) && !bytes);
        bytes=-1;
        CHECK(!syscall(SYS_ioctl,fd,(UINT64_C(1)<<32)|FIONREAD,&bytes) && !bytes);
        create("vector");
        struct iovec v = {buffer.bytes, sizeof(buffer.bytes)};
        CHECK(readv(fd, &v, 1) == 32 && !strcmp(buffer.align.name, "vector"));
        CHECK(!close(fd)); puts("PASS protected read/readv/ioctl without changing dumpability"); return 0;
    }
    if (argc == 2 && !strcmp(argv[1], "churn")) {
        int fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC); CHECK(fd >= 0);
        int before = filters();
        for (int i = 512; i < 4096; ++i) {
            int alias = fcntl(fd, F_DUPFD_CLOEXEC, i);
            if (alias < 0) {
                fprintf(stderr, "CHURN failed descriptor=%d errno=%d filters=%d initial=%d\n", i, errno, filters(), before);
                return 1;
            }
            CHECK(alias == i && !close(alias));
        }
        CHECK(filters() <= before + 512);
        int count = filters();
        for (int i = 4096; i < 8192; ++i) {
            CHECK(dup3(fd, i, O_CLOEXEC) == i && !close(i));
        }
        CHECK(filters() == count);
        int ordinary = open("/dev/zero", O_RDONLY); CHECK(ordinary >= 0);
        char byte = 1; CHECK(read(ordinary, &byte, 1) == 1 && !byte);
        CHECK(!close(ordinary));
        CHECK(!close(fd)); printf("PASS distinct descriptor churn filters=%d initial=%d\n", filters(), before); return 0;
    }
    CHECK(argc == 2);
    CHECK(!mkdir(argv[1], 0700)); CHECK(!chdir(argv[1]));
    int fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC); CHECK(fd >= 0);
    CHECK(fcntl(fd, F_GETFL) & O_NONBLOCK); CHECK(fcntl(fd, F_GETFD) & FD_CLOEXEC);
    int wd = inotify_add_watch(fd, ".", IN_CREATE | IN_DELETE | IN_MOVED_FROM | IN_MOVED_TO | IN_UNMOUNT); CHECK(wd > 0);
    errno = 0; CHECK(read(fd, buffer.bytes, 0) == -1 && errno == EAGAIN);
    create("one");
    struct pollfd p = {fd, POLLIN, 0}; CHECK(poll(&p, 1, 5000) == 1);
    int available = 0; CHECK(!ioctl(fd, FIONREAD, &available) && available >= 32);
    int sink = open("/dev/null", O_WRONLY); CHECK(sink >= 0);
    CHECK(sendfile(sink, fd, NULL, 1) == -1 && errno == EINVAL); CHECK(!close(sink));
    errno = 0; CHECK(read(fd, buffer.bytes, 1) == -1 && errno == EINVAL);
    struct iovec vectors[] = {{buffer.bytes, 8}, {buffer.bytes + 8, 64}};
    errno = 0; CHECK(readv(fd, vectors, 2) == -1 && errno == EINVAL);
    errno = 0; CHECK(read(fd, (void *)1, sizeof(buffer.bytes)) == -1 && errno == EFAULT);
    CHECK(one(fd, IN_CREATE, "one")->wd == wd);
    CHECK(!ioctl(fd, FIONREAD, &available) && !available);
    CHECK(!link("one", "two")); one(fd, IN_CREATE, "two");
    CHECK(!rename("two", "three"));
    ssize_t n = events(fd);
    struct inotify_event *a = &buffer.align, *b = (void *)(buffer.bytes + sizeof(*a) + a->len);
    CHECK(n == (ssize_t)(2 * sizeof(*a) + a->len + b->len));
    CHECK(a->mask == IN_MOVED_FROM && b->mask == IN_MOVED_TO && a->cookie && a->cookie == b->cookie);
    CHECK(!strcmp(a->name, "two") && !strcmp(b->name, "three"));
    CHECK(!unlink("three")); one(fd, IN_DELETE, "three");
    CHECK(!mkdir("dir", 0700)); one(fd, IN_CREATE | IN_ISDIR, "dir");
    CHECK(!rmdir("dir")); one(fd, IN_DELETE | IN_ISDIR, "dir");
    puts("PASS logical directory create/link/rename/delete and exact read boundaries"); fflush(stdout);
    int copy = dup(fd); CHECK(copy >= 0); CHECK(!close(fd)); fd = copy;
    create("dup"); one(fd, IN_CREATE, "dup");
    copy = fcntl(fd, F_DUPFD_CLOEXEC, 100); CHECK(copy >= 100);
    CHECK(!close(fd)); fd = copy;
    create("fcntl"); one(fd, IN_CREATE, "fcntl");
    CHECK(dup3(fd, 120, O_CLOEXEC) == 120); CHECK(!close(fd)); fd = 120;
    create("dup3"); one(fd, IN_CREATE, "dup3");
    CHECK(!inotify_rm_watch(fd, wd)); one(fd, IN_IGNORED, NULL);
    wd = inotify_add_watch(fd, ".", IN_CREATE | IN_ONESHOT); CHECK(wd > 0);
    create("oneshot"); n = events(fd);
    a = &buffer.align; b = (void *)(buffer.bytes + sizeof(*a) + a->len);
    CHECK(n == (ssize_t)(2 * sizeof(*a) + a->len) && a->mask == IN_CREATE && b->mask == IN_IGNORED && b->wd == wd);
    CHECK(inotify_rm_watch(fd, wd) == -1 && errno == EINVAL);
    CHECK(!close(fd));
    puts("PASS dup/fcntl lifetime, explicit removal and one-shot"); fflush(stdout);
    fd = inotify_init1(IN_NONBLOCK); CHECK(fd >= 0);
    CHECK(!(fcntl(fd, F_GETFD) & FD_CLOEXEC));
    wd = inotify_add_watch(fd, "one", IN_MODIFY | IN_CLOSE_WRITE); CHECK(wd > 0);
    copy = open("one", O_WRONLY); CHECK(copy >= 0); CHECK(write(copy, "x", 1) == 1);
    one(fd, IN_MODIFY, NULL); CHECK(!close(copy)); one(fd, IN_CLOSE_WRITE, NULL);
    CHECK(!close(fd));
    puts("PASS native file IO events"); fflush(stdout);
    fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC); CHECK(fd >= 0);
    wd = inotify_add_watch(fd, "/dev/null", IN_ATTRIB); CHECK(wd > 0);
    CHECK(!inotify_rm_watch(fd, wd)); one(fd, IN_IGNORED, NULL); CHECK(!close(fd));
    puts("PASS explicitly mapped host object registration"); fflush(stdout);
    fd = inotify_init1(0); CHECK(fd >= 0);
    wd = inotify_add_watch(fd, ".", IN_CREATE); CHECK(wd > 0);
    int barrier[2]; CHECK(!pipe(barrier));
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        close(barrier[1]); char byte; CHECK(read(barrier[0], &byte, 1) == 1);
        create("fork"); _exit(0);
    }
    close(barrier[0]); CHECK(write(barrier[1], "x", 1) == 1); close(barrier[1]);
    CHECK(read(fd, buffer.bytes, sizeof(buffer.bytes)) >= 32);
    CHECK(buffer.align.mask == IN_CREATE && !strcmp(buffer.align.name, "fork"));
    int status; CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    struct sigaction action = {.sa_handler = signal_handler}; sigemptyset(&action.sa_mask);
    CHECK(!sigaction(SIGALRM, &action, NULL));
    /* Failure/cancellation bound for an intentionally empty blocking read. */
    alarm(1); CHECK(read(fd, buffer.bytes, sizeof(buffer.bytes)) == -1 && errno == EINTR);
    CHECK(interrupted == 1); alarm(0);
    action.sa_flags = SA_RESTART; CHECK(!sigaction(SIGALRM, &action, NULL));
    CHECK(!pipe(barrier)); child = fork(); CHECK(child >= 0);
    if (!child) {
        close(barrier[1]); char byte; CHECK(read(barrier[0], &byte, 1) == 1);
        create("restarted"); _exit(0);
    }
    close(barrier[0]); signal_writer = barrier[1];
    /* EVENT_WAIT: the delivered signal releases the writer, then SA_RESTART
     * must complete this same blocking read. Runner deadline bounds failure. */
    alarm(1); CHECK(read(fd, buffer.bytes, sizeof(buffer.bytes)) == 32);
    CHECK(interrupted == 2 && buffer.align.mask == IN_CREATE && !strcmp(buffer.align.name, "restarted"));
    signal_writer = -1; close(barrier[1]); alarm(0);
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    create("vector-a"); create("vector-b");
    vectors[0] = (struct iovec){buffer.bytes, 32}; vectors[1] = (struct iovec){buffer.bytes + 32, 32};
    CHECK(readv(fd, vectors, 2) == 64);
    CHECK(!strcmp(buffer.align.name, "vector-a") && !strcmp(((struct inotify_event *)(buffer.bytes+32))->name, "vector-b"));
    create("partial-vector"); CHECK(readv(fd, vectors, 2) == 32);
    CHECK(!strcmp(buffer.align.name, "partial-vector"));
    CHECK(!close(fd));
    puts("PASS blocking fork writer, EINTR/SA_RESTART and partial readv"); fflush(stdout);
    fd = inotify_init1(0); CHECK(fd >= 0);
    CHECK(inotify_add_watch(fd, ".", IN_CREATE) > 0);
    struct reader readers[2] = {{.fd = fd}, {.fd = fd}};
    pthread_t threads[2];
    CHECK(!pthread_create(&threads[0], NULL, read_thread, &readers[0]));
    CHECK(!pthread_create(&threads[1], NULL, read_thread, &readers[1]));
    create("thread-a"); create("thread-b");
    CHECK(!pthread_join(threads[0], NULL) && !pthread_join(threads[1], NULL));
    CHECK(strcmp(readers[0].name, readers[1].name));
    CHECK(!close(fd));
    puts("PASS concurrent readers consume each event once"); fflush(stdout);
    fd = inotify_init1(IN_NONBLOCK); CHECK(fd >= 0);
    CHECK(inotify_add_watch(fd, ".", IN_CREATE) > 0);
    int sockets[2]; CHECK(!socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sockets));
    transfer(sockets[0], fd); CHECK(!close(fd));
    create("queued-rights");
    copy = open("/dev/zero", O_RDONLY); CHECK(copy == fd);
    char zero = 1; CHECK(read(copy, &zero, 1) == 1 && !zero);
    fd = receive_fd(sockets[1], 0); CHECK(fd >= 0 && fd != copy);
    one(fd, IN_CREATE, "queued-rights");
    transfer(sockets[0], fd); CHECK(!close(fd));
    create("batch-rights");
    fd = receive_fd(sockets[1], 1); CHECK(fd >= 0);
    one(fd, IN_CREATE, "batch-rights");
    CHECK(!close(sockets[0]) && !close(sockets[1]));
    int ordinary = fcntl(copy, F_DUPFD_CLOEXEC, 200); CHECK(ordinary >= 200);
    for (int i = 0; i < 10000; ++i) CHECK(read(ordinary, &zero, 1) == 1 && !zero);
    CHECK(!close(ordinary) && !close(copy) && !close(fd));
    puts("PASS queued SCM_RIGHTS lifetime, descriptor reuse and unrelated native reads"); fflush(stdout);
    child = fork(); CHECK(child >= 0);
    if (!child) {
        fd = inotify_init1(IN_NONBLOCK); CHECK(fd >= 0);
        CHECK(inotify_add_watch(fd, ".", IN_CREATE) > 0);
        create("exec-event");
        int observer=open("/proc/self/status",O_RDONLY); CHECK(observer>=0);
        return exec_watch(fd, observer, 64, 0);
    }
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    return 0;
}
