#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static void event(int fd, int send) {
    char byte = 'x';
    /* EVENT_WAIT: peer acknowledgement orders root observations. alarm bounds
     * a missing peer; elapsed time is never treated as an acknowledgement. */
    assert((send ? write(fd, &byte, 1) : read(fd, &byte, 1)) == 1);
}
static void reap(pid_t pid) {
    int status;
    assert(waitpid(pid, &status, 0) == pid);
    if (!WIFEXITED(status) || WEXITSTATUS(status)) fprintf(stderr, "child status=%#x\n", status);
    assert(WIFEXITED(status) && !WEXITSTATUS(status));
}
static void content(const char *path, const char *expected) {
    char data[64] = "";
    int fd = open(path, O_RDONLY);
    if (fd < 0) fprintf(stderr,"open %s errno=%d\n",path,errno);
    assert(fd >= 0);
    assert(read(fd, data, sizeof(data)-1) == (ssize_t)strlen(expected));
    assert(!strcmp(data, expected)); close(fd);
}
static void absent(const char *path) {
    errno=0; int fd=open(path,O_RDONLY);
    if (fd>=0 || errno!=ENOENT) fprintf(stderr,"expected absent %s fd=%d errno=%d\n",path,fd,errno);
    assert(fd == -1 && errno == ENOENT);
}
static void cwd_is(const char *expected) {
    char path[4096],*current=getcwd(path,sizeof(path));
    if (!current || strcmp(current,expected))
        fprintf(stderr,"cwd=%s expected=%s errno=%d\n",current ? current : "null",expected,errno);
    assert(current && !strcmp(current,expected));
}
static void inside(void) {
    content("/value", "inside");
    content("/absolute", "inside");
    content("/sub/../../value", "inside");
    content("/proc/sentinel", "guest proc");
    absent("/outside"); absent("/proc/self/status");
    assert(!chdir("/sub"));
    char cwd[4096]; assert(getcwd(cwd,sizeof(cwd)) && !strcmp(cwd,"/sub"));
    assert(!chdir("..") && !chdir(".."));
    assert(getcwd(cwd,sizeof(cwd)) && !strcmp(cwd,"/"));
}
static void basic(int protected) {
    int outside = open("/outside",O_RDONLY); assert(outside>=0);
    int oldroot = open("/",O_PATH|O_DIRECTORY); assert(oldroot>=0);
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    assert(!chroot("/jail"));
    char cwd[4096]; assert(!getcwd(cwd,sizeof(cwd)) && errno==ENOENT);
    inside();
    char data[8]=""; assert(read(outside,data,7)==7 && !strcmp(data,"outside"));
    assert(!fchdir(oldroot)); content("outside","outside");
    assert(!getcwd(cwd,sizeof(cwd)) && errno==ENOENT);
    assert(!chdir("/"));
    close(outside); close(oldroot);
    execl("/test","/test","exec",(char *)NULL);
    perror("root exec"); _exit(1);
}
struct sharing { int ready[2], done[2], split; };
static int change(void *opaque) {
    struct sharing *c=opaque;
    if (c->split) assert(!unshare(CLONE_FS));
    assert(!chroot("/jail"));
    event(c->ready[1],1); event(c->done[0],0);
    inside(); return 0;
}
static void shared(int split, int vm) {
    struct sharing c={.split=split};
    assert(!pipe(c.ready) && !pipe(c.done));
    void *stack=malloc(1024*1024); assert(stack);
    pid_t pid=clone(change,(char *)stack+1024*1024,SIGCHLD|CLONE_FS|(vm ? CLONE_VM : 0),&c);
    assert(pid>0); event(c.ready[0],0);
    if (split) { content("/outside","outside"); absent("/value"); }
    else { content("/value","inside"); absent("/outside"); }
    event(c.done[1],1); reap(pid); free(stack);
    close(c.ready[0]); close(c.ready[1]); close(c.done[0]); close(c.done[1]);
}
static void failure(void) {
    assert(chroot("/missing")==-1 && errno==ENOENT);
    content("/outside","outside");
    assert(chroot("/outside")==-1 && errno==ENOTDIR);
    content("/outside","outside");
    assert(!setuid(1000));
    assert(chroot("/jail")==-1 && errno==EPERM);
    content("/outside","outside");
}
static void proc_root(int protected) {
    int root=open("/",O_PATH|O_DIRECTORY); assert(root>=0);
    struct stat before,after;
    assert(!stat("/proc/self/status",&before));
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    assert(!chroot("/proc"));
    char cwd[4096]; assert(!getcwd(cwd,sizeof(cwd)) && errno==ENOENT);
    int changed=chdir("/");
    if (changed) fprintf(stderr,"proc-root chdir error=%d\n",errno);
    assert(!changed);
    cwd_is("/");
    assert(!chdir("/sys")); cwd_is("/sys");
    assert(!chdir("../../..")); cwd_is("/");
    assert(!chdir("/sys/../../..")); cwd_is("/");
    assert(!chdir("/self"));
    char process[32]; snprintf(process,sizeof(process),"/%d",getpid()); cwd_is(process);
    assert(!chdir("..")); cwd_is("/");
    assert(!stat("/self/status",&after));
    assert(before.st_dev==after.st_dev && before.st_ino==after.st_ino);
    int status=open("/self/status",O_RDONLY); assert(status>=0);
    char bytes[256]={0}; assert(read(status,bytes,sizeof(bytes)-1)>0);
    assert(strstr(bytes,"Name:") && strstr(bytes,"Pid:")); close(status);
    absent("/outside");
    assert(!fchdir(root)); content("outside","outside");
    assert(!getcwd(cwd,sizeof(cwd)) && errno==ENOENT);
    close(root);
}
static void unlinked_cwd(void) {
    assert(!chroot("/jail") && !chdir("/sub"));
    assert(!unlink("nested") && !rmdir("/sub"));
    char cwd[4096]; assert(!getcwd(cwd,sizeof(cwd)) && errno==ENOENT);
    assert(!chdir("/") && getcwd(cwd,sizeof(cwd)) && !strcmp(cwd,"/"));
}
static void nested(void) {
    assert(!chroot("/")); content("/outside","outside");
    int proc=open("/proc/self/status",O_RDONLY); assert(proc>=0); close(proc);
    assert(!chroot("/jail")); assert(!chdir("/"));
    assert(!chroot("sub")); assert(!chdir("/"));
    content("/nested","nested"); absent("/value");
}
static void write_file(const char *path, const char *value) {
    int fd=open(path,O_CREAT|O_TRUNC|O_WRONLY,0644); assert(fd>=0);
    assert(write(fd,value,strlen(value))==(ssize_t)strlen(value)); close(fd);
}
static void forbid_rpc(void) {
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
}
int main(int argc,char **argv) {
    assert(argc==2); alarm(45); setbuf(stdout,NULL);
    if (!strcmp(argv[1],"exec")) { inside(); puts("PASS root survives exec"); return 0; }
    /* The credential-drop case reads the explicitly world-readable fixture. */
    umask(0022);
    assert(!chdir("/")); assert(!chmod("/",0755));
    if (!strcmp(argv[1],"attached")) assert(!chmod("/jail",0755));
    else assert(!mkdir("/jail",0755));
    assert(!mkdir("/jail/sub",0755)); assert(!mkdir("/jail/proc",0755));
    write_file("/outside","outside"); write_file("/jail/value","inside");
    write_file("/jail/sub/nested","nested"); write_file("/jail/proc/sentinel","guest proc");
    assert(!symlink("/value","/jail/absolute"));
    int src=open(argv[0],O_RDONLY), dst=open("/jail/test",O_WRONLY|O_CREAT,0755);
    assert(src>=0 && dst>=0);
    char buffer[8192]; ssize_t n;
    while ((n=read(src,buffer,sizeof(buffer)))>0) assert(write(dst,buffer,n)==n);
    assert(!n); close(src); close(dst);
    for (int i=0;i<11;i++) {
        pid_t pid=fork(); assert(pid>=0);
        if (!pid) {
            forbid_rpc();
            if(i<2) basic(i);
            else if(i<6) shared((i-2)&1,(i-2)/2);
            else if(i==6) failure(); else if(i==7) nested();
            else if(i<10) proc_root(i-8); else unlinked_cwd();
            _exit(0);
        }
        reap(pid); content("/outside","outside"); absent("/value");
        printf("PASS root case=%d parent unchanged\n",i);
    }
    puts("PASS guest roots");
}
