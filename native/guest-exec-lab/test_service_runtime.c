#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/capability.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <linux/securebits.h>
#include <linux/shm.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <sys/utsname.h>
#include <unistd.h>

static int get(int key, size_t size, int flags) { return syscall(SYS_shmget,key,size,flags); }
static void *attach(int id, void *address, int flags) { return (void *)syscall(SYS_shmat,id,address,flags); }
static int detach(void *address) { return syscall(SYS_shmdt,address); }
static int ctl(int id, int cmd, void *p) { return syscall(SYS_shmctl,id,cmd,p); }
static struct shmid64_ds info(int id) { struct shmid64_ds s; assert(!ctl(id,IPC_STAT,&s)); return s; }
static void child_ok(pid_t child) {
    int status; assert(waitpid(child,&status,0)==child);
    if (!WIFEXITED(status) || WEXITSTATUS(status)) fprintf(stderr,"child %d status=%#x\n",child,status);
    assert(WIFEXITED(status) && !WEXITSTATUS(status));
}
static void descriptor_path(int fd, const char *expected) {
    char path[64], value[4096];
    snprintf(path,sizeof(path),"/proc/self/fd/%d",fd);
    ssize_t n=readlink(path,value,sizeof(value)-1); assert(n>=0); value[n]=0;
    assert(!strcmp(value,expected));
}
static void descriptor_paths(const char *self) {
    assert(!mkdir("/tmp/md-fd-path",0700));
    int fd=open("/tmp/md-fd-path/file",O_CREAT|O_EXCL|O_RDWR,0600); assert(fd>=0);
    descriptor_path(fd,"/tmp/md-fd-path/file");
    int copy=dup(fd); assert(copy>=0);
    assert(!rename("/tmp/md-fd-path","/tmp/md-fd-moved"));
    descriptor_path(copy,"/tmp/md-fd-moved/file");
    assert(!fsync(fd));
    int parent=open("/tmp/md-fd-moved",O_RDONLY|O_DIRECTORY); assert(parent>=0 && !fsync(parent)); close(parent);
    pid_t child=fork(); assert(child>=0);
    if (!child) { char number[32]; snprintf(number,sizeof(number),"%d",fd);
        execl(self,self,"fd-path-exec",number,NULL); perror("fd-path exec"); _exit(90); }
    child_ok(child);
    assert(!unlink("/tmp/md-fd-moved/file"));
    descriptor_path(fd,"/tmp/md-fd-moved/file (deleted)");
    int next=open("/tmp/md-fd-moved/file",O_CREAT|O_EXCL|O_RDWR,0600); assert(next>=0);
    descriptor_path(fd,"/tmp/md-fd-moved/file (deleted)");
    descriptor_path(next,"/tmp/md-fd-moved/file");
    assert(!link("/tmp/md-fd-moved/file","/tmp/md-fd-moved/alias"));
    assert(!unlink("/tmp/md-fd-moved/file"));
    char path[64], value[4096]; snprintf(path,sizeof(path),"/proc/self/fd/%d",next);
    errno=0; assert(readlink(path,value,sizeof(value))==-1 && errno==ENOTSUP);
    close(next); close(copy); close(fd);
    assert(!unlink("/tmp/md-fd-moved/alias") && !rmdir("/tmp/md-fd-moved"));
    puts("PASS FD paths: parent rename, dup/fork/exec, deleted identity, name reuse and alias uncertainty");
}
static void descriptors(void) {
    int fd = open("/dev/stderr",O_WRONLY|O_CREAT|O_APPEND|O_CLOEXEC,0644); assert(fd>=0); close(fd);
    char path[64]; fd = open("/tmp/md-reopen",O_CREAT|O_EXCL|O_RDWR,0600); assert(fd>=0);
    assert(write(fd,"test",4)==4 && !unlink("/tmp/md-reopen"));
    snprintf(path,sizeof(path),"/proc/self/fd/%d",fd);
    int reopened = open(path,O_WRONLY|O_CREAT|O_APPEND|O_CLOEXEC,0644); assert(reopened>=0);
    assert(write(reopened,"!",1)==1); close(reopened);
    char buffer[8]={0}; assert(pread(fd,buffer,sizeof(buffer),0)==5 && !strcmp(buffer,"test!"));
    errno=0; assert(open(path,O_CREAT|O_EXCL|O_WRONLY,0600)==-1 && errno==EEXIST);
    errno=0; assert(open(path,O_NOFOLLOW|O_RDONLY)==-1 && errno==ELOOP);
    errno=0; assert(open(path,O_CREAT|O_DIRECTORY|O_RDONLY,0600)==-1 && errno==EINVAL);
    reopened=open(path,O_WRONLY|O_CREAT|O_TRUNC,0600); assert(reopened>=0); close(reopened);
    assert(pread(fd,buffer,sizeof(buffer),0)==0); close(fd);
    fd=open("/tmp/md-direct",O_CREAT|O_EXCL|O_RDWR|O_DIRECT,0600); assert(fd>=0);
    void *aligned=NULL; assert(!posix_memalign(&aligned,4096,4096)); memset(aligned,0x41,4096);
    assert(write(fd,aligned,4096)==4096); memset(aligned,0,4096);
    assert(pread(fd,aligned,4096,0)==4096 && ((char *)aligned)[0]==0x41);
    free(aligned); close(fd); assert(!unlink("/tmp/md-direct"));
    puts("PASS descriptor create/append/exclusive/nofollow/truncate/open-unlinked");
}
static void caps_read(struct __user_cap_data_struct data[2]) {
    struct __user_cap_header_struct h={.version=_LINUX_CAPABILITY_VERSION_3};
    assert(!syscall(SYS_capget,&h,data));
}
static int caps_write(struct __user_cap_data_struct data[2]) {
    struct __user_cap_header_struct h={.version=_LINUX_CAPABILITY_VERSION_3};
    return syscall(SYS_capset,&h,data);
}
static void capabilities(const char *self) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct __user_cap_data_struct c[2]={{0}};
        assert(!prctl(PR_SET_DUMPABLE,0)); caps_read(c);
        assert(c[0].effective&(1U<<CAP_SETUID));
        struct __user_cap_header_struct wrong={0};
        errno=0; assert(syscall(SYS_capget,&wrong,c)==-1 && errno==EINVAL && wrong.version==_LINUX_CAPABILITY_VERSION_3);
        errno=0; assert(syscall(SYS_capget,(void *)1,c)==-1 && errno==EFAULT);
        caps_read(c); c[0].permitted|=1U<<CAP_SYS_ADMIN;
        errno=0; assert(caps_write(c)==-1 && errno==EPERM);
        assert(!prctl(PR_SET_KEEPCAPS,1,0,0,0));
        assert(!syscall(SYS_setresuid,999,999,999)); caps_read(c);
        assert(!c[0].effective && c[0].permitted);
        errno=0; assert(syscall(SYS_setresgid,999,999,999)==-1 && errno==EPERM);
        c[0].effective=c[0].permitted; assert(!caps_write(c));
        assert(!syscall(SYS_setresgid,999,999,999) && !syscall(SYS_setgroups,0,NULL));
        c[0].inheritable=1U<<CAP_SETGID; assert(!caps_write(c));
        assert(!prctl(PR_CAP_AMBIENT,PR_CAP_AMBIENT_RAISE,CAP_SETGID,0,0));
        execl(self,self,"caps-exec",NULL); _exit(90);
    }
    child_ok(child);
    child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_SECUREBITS,SECBIT_KEEP_CAPS|SECBIT_KEEP_CAPS_LOCKED,0,0,0));
        errno=0; assert(prctl(PR_SET_KEEPCAPS,0,0,0,0)==-1 && errno==EPERM);
        assert(!prctl(PR_CAPBSET_DROP,CAP_SETGID,0,0,0));
        assert(!prctl(PR_CAPBSET_READ,CAP_SETGID,0,0,0));
        struct sock_filter code[]={BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_capget,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|ENOMSG),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        struct __user_cap_header_struct h={.version=_LINUX_CAPABILITY_VERSION_3};
        struct __user_cap_data_struct c[2]; errno=0;
        assert(syscall(SYS_capget,&h,c)==-1 && errno==ENOMSG); _exit(0);
    }
    child_ok(child); puts("PASS capabilities protected copies, drops, keepcaps, exec, ambient, securebits, seccomp precedence");
}
static void file_capabilities(const char *self) {
    const char *path="/tmp/md-filecap", *alias="/tmp/md-filecap-link";
    int source=open(self,O_RDONLY), fd=open(path,O_CREAT|O_EXCL|O_RDWR,0755);
    assert(source>=0 && fd>=0);
    assert(!fchmod(fd,0755));
    char buffer[16384]; ssize_t n;
    while ((n=read(source,buffer,sizeof(buffer)))>0) assert(write(fd,buffer,n)==n);
    assert(!n); close(source);
    uint32_t value[5]={VFS_CAP_REVISION_2|VFS_CAP_FLAGS_EFFECTIVE,1U<<CAP_SETUID,0,0,0}, copy[6];
    int set=fsetxattr(fd,"security.capability",value,sizeof(value),XATTR_CREATE);
    if (set) perror("fsetxattr security.capability");
    assert(!set);
    assert(fgetxattr(fd,"security.capability",copy,sizeof(copy))==sizeof(value) && !memcmp(value,copy,sizeof(value)));
    errno=0; assert(fsetxattr(fd,"security.capability",value,sizeof(value),XATTR_CREATE)==-1 && errno==EEXIST);
    errno=0; assert(fgetxattr(fd,"security.capability",copy,1)==-1 && errno==ERANGE);
    errno=0; assert(fgetxattr(fd,"security.capability",(void *)1,sizeof(copy))==-1 && errno==EFAULT);
    ssize_t size=flistxattr(fd,NULL,0); assert(size>0 && (size_t)size<sizeof(buffer));
    assert(flistxattr(fd,buffer,sizeof(buffer))==size);
    int found=0; for (size_t i=0;i<(size_t)size;i+=strlen(buffer+i)+1) found+=!strcmp(buffer+i,"security.capability");
    assert(found==1);
    errno=0; assert(flistxattr(fd,buffer,1)==-1 && errno==ERANGE);
    assert(!link(path,alias) && !unlink(path));
    assert(getxattr(alias,"security.capability",copy,sizeof(copy))==sizeof(value));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!syscall(SYS_setresgid,999,999,999) && !syscall(SYS_setresuid,999,999,999));
        errno=0; assert(fremovexattr(fd,"security.capability")==-1 && errno==EPERM);
        execl(alias,alias,"filecap-exec",NULL); perror("filecap exec"); _exit(90);
    }
    child_ok(child);
    assert(!fremovexattr(fd,"security.capability"));
    errno=0; assert(getxattr(alias,"security.capability",copy,sizeof(copy))==-1 && errno==ENODATA);
    assert(!setxattr(alias,"security.capability",value,sizeof(value),0));
    assert(!chown(alias,123,456));
    errno=0; assert(fgetxattr(fd,"security.capability",copy,sizeof(copy))==-1 && errno==ENODATA);
    close(fd); assert(!unlink(alias));
    puts("PASS inode file capabilities: names/FDs, hardlinks/unlink, sizes, permissions, chown and nosuid exec");
}
static void host_identity(const char *self,const char *expected) {
    struct utsname host; assert(!uname(&host) && !strcmp(host.nodename,expected));
    errno=0; assert(syscall(SYS_uname,(void *)1)==-1 && errno==EFAULT);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_DUMPABLE,0)); assert(!uname(&host) && !strcmp(host.nodename,expected));
        char *args[]={(char *)self,"hostname-exec",(char *)expected,NULL}; char *env[]={NULL};
        execve(self,args,env); _exit(90);
    }
    child_ok(child); puts("PASS launch hostname, protected copy, fork and exec without environment");
}
static void memory(const char *self) {
    size_t page=getpagesize();
    int id=get(IPC_PRIVATE,page*3,IPC_CREAT|0600);
    if (id<0) perror("shmget private");
    assert(id>=0);
    char *p=attach(id,NULL,0); assert(p!=(void *)-1); strcpy(p,"shared");
    assert(info(id).shm_nattch==1);
    int sync[2]; assert(!pipe(sync));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(sync[1]); char byte;
        /* EVENT_WAIT: parent inspected inherited mappings; fixture deadline bounds failure. */
        assert(read(sync[0],&byte,1)==1); close(sync[0]);
        assert(!strcmp(p,"shared")); strcpy(p,"child");
        execl(self,self,"empty-exec",NULL); _exit(90);
    }
    close(sync[0]); assert(info(id).shm_nattch==2); assert(write(sync[1],"g",1)==1); close(sync[1]);
    child_ok(child); assert(!strcmp(p,"child") && info(id).shm_nattch==1);
    child=fork(); assert(child>=0);
    if (!child) {
        assert(!syscall(SYS_setresgid,999,999,999) && !syscall(SYS_setresuid,999,999,999));
        errno=0; assert(attach(id,NULL,0)==(void *)-1 && errno==EACCES);
        errno=0; assert(ctl(id,IPC_RMID,NULL)==-1 && errno==EPERM); _exit(0);
    }
    child_ok(child);
    struct shmid64_ds st=info(id); st.shm_perm.mode=0644; assert(!ctl(id,IPC_SET,&st));
    child=fork(); assert(child>=0);
    if (!child) {
        assert(!syscall(SYS_setresgid,999,999,999) && !syscall(SYS_setresuid,999,999,999));
        volatile char *ro=attach(id,NULL,SHM_RDONLY); assert(ro!=(void *)-1 && *ro=='c');
        *ro='x'; _exit(91);
    }
    int status; assert(waitpid(child,&status,0)==child && WIFSIGNALED(status) && WTERMSIG(status)==SIGSEGV);
    assert(info(id).shm_nattch==1);
    assert(!munmap(p+page,page)); assert(info(id).shm_nattch==2);
    char *middle=mmap(p+page,page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED,-1,0);
    assert(middle==p+page); strcpy(middle,"unrelated");
    assert(!ctl(id,IPC_RMID,NULL)); assert(!strcmp(p,"child"));
    assert(!detach(p)); assert(!strcmp(middle,"unrelated")); assert(!munmap(middle,page));
    errno=0; assert(ctl(id,IPC_STAT,&st)==-1 && errno==EINVAL);
    id=get(IPC_PRIVATE,page,0600); assert(id>=0); p=attach(id,NULL,0); assert(p!=(void *)-1);
    assert(!ctl(id,IPC_RMID,NULL)); assert(!munmap(p,page));
    errno=0; assert(ctl(id,IPC_STAT,&st)==-1 && errno==EINVAL);
    puts("PASS SysV shared bytes, fork/exec/exit, permissions, readonly, IPC_RMID, partial munmap and holes");
}
static void stack_boundary(void) {
    size_t page=getpagesize(), size=1024UL*1024*1024;
    void *reservation=mmap(NULL,size,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(reservation!=MAP_FAILED);
    uintptr_t top=(uintptr_t)&reservation & ~(page-1);
    size_t scanned=0;
    for (; scanned<9UL*1024*1024; scanned+=page) {
        errno=0;
        void *p=mremap((void *)(top-scanned),page,2*page,0);
        assert(p==MAP_FAILED);
        if (errno==EFAULT) break;
        assert(errno==ENOMEM);
    }
    assert(scanned<9UL*1024*1024);
    assert(!munmap(reservation,size));
    printf("PASS initial stack boundary after large mmap: %zu bytes\n",scanned);
}
static void copy_attributes(void) {
    int fd=open("/etc/os-release",O_RDONLY|O_CLOEXEC); assert(fd>=0);
    const char *key="user.md-copy";
    assert(!fsetxattr(fd,key,"copy",4,0));
    char names[512],value[8]; ssize_t n=flistxattr(fd,names,sizeof(names)); assert(n>0);
    int found=0;
    for(ssize_t i=0;i<n;i+=strlen(names+i)+1) found|=!strcmp(names+i,key);
    assert(found && fgetxattr(fd,key,value,sizeof(value))==4 && !memcmp(value,"copy",4));
    assert(!fremovexattr(fd,key)); close(fd);
    puts("PASS xattr copy-up: retained lower FD reads and lists current metadata");
}
static void access_lists(void) {
    struct entry { uint16_t tag,permissions; uint32_t id; };
    struct { uint32_t version; struct entry entries[6]; } acl={2,{
        {1,7,UINT32_MAX},{2,6,999},{4,0,UINT32_MAX},{8,4,998},{16,6,UINT32_MAX},{32,0,UINT32_MAX}}};
    const char *access="system.posix_acl_access", *defaults="system.posix_acl_default";
    assert(!mkdir("/tmp/md-acl",0777));
    int dir=open("/tmp/md-acl",O_RDONLY|O_DIRECTORY); assert(dir>=0);
    assert(!fsetxattr(dir,defaults,&acl,sizeof(acl),XATTR_CREATE));
    mode_t old=umask(077);
    int fd=open("/tmp/md-acl/file",O_CREAT|O_EXCL|O_RDWR,0666); assert(fd>=0);
    assert(!mkdir("/tmp/md-acl/child",0777));
    int listener=socket(AF_UNIX,SOCK_STREAM,0); assert(listener>=0);
    struct sockaddr_un address={.sun_family=AF_UNIX,.sun_path="/tmp/md-acl/socket"};
    assert(!bind(listener,(struct sockaddr *)&address,sizeof(address))); umask(old);
    struct stat st; assert(!fstat(fd,&st) && (st.st_mode&0777)==0660);
    assert(!stat(address.sun_path,&st) && (st.st_mode&0777)==0760);
    unsigned char value[128]; ssize_t size=fgetxattr(fd,access,value,sizeof(value));
    assert(size==sizeof(acl) && fgetxattr(fd,access,NULL,0)==size);
    errno=0; assert(fgetxattr(fd,access,value,4)==-1 && errno==ERANGE);
    assert(getxattr("/tmp/md-acl/child",defaults,value,sizeof(value))==sizeof(acl));
    assert(getxattr(address.sun_path,access,value,sizeof(value))==sizeof(acl));
    assert(!close(listener) && !unlink(address.sun_path));
    assert(!write(fd,"",0)); assert(write(fd,"acl",3)==3);
    assert(!link("/tmp/md-acl/file","/tmp/md-acl/alias"));
    pid_t child=fork(); assert(child>=0);
    if(!child) {
        assert(!syscall(SYS_setresgid,999,999,999) && !syscall(SYS_setresuid,999,999,999));
        int other=open("/tmp/md-acl/alias",O_RDWR); assert(other>=0); close(other);
        errno=0; assert(fsetxattr(fd,access,&acl,sizeof(acl),0)==-1 && errno==EPERM);
        assert(!fsetxattr(fd,"user.md-acl","ok",2,0));
        assert(getxattr("/tmp/md-acl/alias","user.md-acl",value,sizeof(value))==2);
        errno=0; assert(fgetxattr(fd,"trusted.md-absent",value,sizeof(value))==-1 && errno==ENODATA);
        errno=0; assert(fsetxattr(fd,"trusted.md-absent","x",1,0)==-1 && errno==EPERM);
        errno=0; assert(setxattr("/tmp","user.md-sticky","x",1,0)==-1 && errno==EPERM);
        _exit(0);
    }
    child_ok(child);
    assert(!fchmod(fd,0600));
    child=fork(); assert(child>=0);
    if(!child) {
        assert(!syscall(SYS_setresgid,999,999,999) && !syscall(SYS_setresuid,999,999,999));
        errno=0; assert(open("/tmp/md-acl/alias",O_RDONLY)==-1 && errno==EACCES);
        errno=0; assert(fgetxattr(fd,"user.md-acl",value,sizeof(value))==-1 && errno==EACCES);
        errno=0; assert(fsetxattr(fd,"user.md-acl","no",2,0)==-1 && errno==EACCES);
        _exit(0);
    }
    child_ok(child);
    char proc[64]; snprintf(proc,sizeof(proc),"/proc/self/fd/%d",fd);
    assert(!chmod(proc,0640));
    assert(!fstat(fd,&st) && (st.st_mode&0777)==0640);
    child=fork(); assert(child>=0);
    if(!child) {
        assert(!syscall(SYS_setresgid,998,998,998) && !syscall(SYS_setresuid,1001,1001,1001));
        int other=open("/tmp/md-acl/alias",O_RDONLY); assert(other>=0); close(other);
        assert(fgetxattr(fd,"user.md-acl",value,sizeof(value))==2 && !memcmp(value,"ok",2));
        errno=0; assert(fremovexattr(fd,"user.md-acl")==-1 && errno==EACCES);
        assert(!faccessat(AT_FDCWD,proc,R_OK,0));
        errno=0; assert(faccessat(AT_FDCWD,proc,W_OK,0)==-1 && errno==EACCES);
        errno=0; assert(chmod(proc,0666)==-1 && errno==EPERM);
        errno=0; assert(chown(proc,1001,998)==-1 && errno==EPERM);
        errno=0; assert(open("/tmp/md-acl/alias",O_WRONLY)==-1 && errno==EACCES); _exit(0);
    }
    child_ok(child);
    char names[256]; ssize_t n=flistxattr(fd,names,sizeof(names)); assert(n>0);
    int found=0; for(ssize_t i=0;i<n;i+=strlen(names+i)+1) found|=!strcmp(names+i,access); assert(found);
    assert(!fsetxattr(fd,access,&acl,sizeof(acl),XATTR_CREATE));
    acl.entries[1].permissions=8;
    errno=0; assert(fsetxattr(fd,access,&acl,sizeof(acl),0)==-1 && errno==EINVAL);
    acl.entries[1].permissions=6;
    assert(!unlink("/tmp/md-acl/file") && !unlink("/tmp/md-acl/alias"));
    assert(fgetxattr(fd,access,value,sizeof(value))==sizeof(acl));
    assert(!fremovexattr(fd,access)); errno=0; assert(fgetxattr(fd,access,NULL,0)==-1 && errno==ENODATA);
    assert(!fremovexattr(fd,access));
    assert(!fsetxattr(fd,access,&acl,sizeof(acl),XATTR_REPLACE));
    assert(!fsetxattr(fd,access,NULL,0,0));
    errno=0; assert(fgetxattr(fd,access,NULL,0)==-1 && errno==ENODATA);
    assert(!fsetxattr(fd,defaults,NULL,0,0));
    close(fd); close(dir);
    assert(!rmdir("/tmp/md-acl/child") && !rmdir("/tmp/md-acl"));
    puts("PASS POSIX ACLs: named users/groups, masks, chmod, inheritance/umask, hardlinks, unlinked FDs and invalid input");
}
int main(int argc,char **argv) {
    setvbuf(stdout,NULL,_IONBF,0);
    assert(argc>=2);
    if (!strcmp(argv[1],"empty-exec")) return 0;
    if (!strcmp(argv[1],"stack")) { stack_boundary(); return 0; }
    if (!strcmp(argv[1],"fd-path-exec")) { assert(argc==3); descriptor_path(atoi(argv[2]),"/tmp/md-fd-moved/file"); return 0; }
    if (!strcmp(argv[1],"hostname")) { assert(argc==3); host_identity(argv[0],argv[2]); return 0; }
    if (!strcmp(argv[1],"hostname-exec")) {
        struct utsname host; assert(argc==3 && !uname(&host) && !strcmp(host.nodename,argv[2])); return 0;
    }
    if (!strcmp(argv[1],"filecap-exec")) {
        struct __user_cap_data_struct c[2]; caps_read(c);
        assert(getuid()==999 && !c[0].effective && !c[1].effective); return 0;
    }
    if (!strcmp(argv[1],"caps-exec")) {
        struct __user_cap_data_struct c[2]; caps_read(c);
        assert(getuid()==999 && getgid()==999 && !prctl(PR_GET_KEEPCAPS,0,0,0,0));
        assert(c[0].effective==(1U<<CAP_SETGID) && c[0].permitted==c[0].effective && !c[1].effective);
        return 0;
    }
    assert(getuid()==0);
    if (!strcmp(argv[1],"all")) { stack_boundary(); descriptors(); descriptor_paths(argv[0]); copy_attributes(); access_lists(); capabilities(argv[0]); file_capabilities(argv[0]); memory(argv[0]); return 0; }
    assert(argc==3); int key=atoi(argv[2]);
    if (!strcmp(argv[1],"publish")) {
        int id=get(key,4096,IPC_CREAT|IPC_EXCL|0600); assert(id>=0);
        char *p=attach(id,NULL,0); assert(p!=(void *)-1); strcpy(p,"independent launch");
        assert(!detach(p)); puts("PASS published keyed segment");
    } else if (!strcmp(argv[1],"consume")) {
        int id=get(key,0,0600); assert(id>=0);
        char *p=attach(id,NULL,0); assert(p!=(void *)-1 && !strcmp(p,"independent launch"));
        assert(!ctl(id,IPC_RMID,NULL) && !detach(p)); puts("PASS independent keyed segment read and removal");
    } else if (!strcmp(argv[1],"absent")) {
        errno=0; assert(get(key,0,0600)==-1 && errno==ENOENT); puts("PASS separate store IPC namespace");
    } else return 2;
    return 0;
}
