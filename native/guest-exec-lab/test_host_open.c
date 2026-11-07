#define _GNU_SOURCE
#include "host_open.h"
#include "raw.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <unistd.h>

static int unavailable;
static int native_oracle;
static int race_directory=-1,race_fd=-1,raced;
long __real_md_raw(long,long,long,long,long,long,long);
long __wrap_md_raw(long nr,long a,long b,long c,long d,long e,long f) {
    long result=unavailable && nr==SYS_openat2 ? -ENOSYS : __real_md_raw(nr,a,b,c,d,e,f);
    if (race_directory>=0 && nr==SYS_openat && a==race_directory && !strcmp((const char *)b,"race"))
        race_fd=result;
    if (race_directory>=0 && nr==SYS_fstat && a==race_fd && !result && !raced) {
        assert(!__real_md_raw(SYS_unlinkat,race_directory,(long)"race",0,0,0,0));
        assert(!__real_md_raw(SYS_symlinkat,(long)"dir/file",race_directory,(long)"race",0,0,0));
        raced=1;
    }
    return result;
}
static void compare(int directory,const char *path,unsigned flags) {
    struct open_how how={.flags=flags|O_CLOEXEC,.resolve=RESOLVE_NO_SYMLINKS};
    unavailable=0;
    long native;
    if (native_oracle) {
        native=md_host_open_resolved(directory,path,&how);
        assert(native!=-ENOSYS);
    } else if (!strcmp(path,"linkdir/file") || !strcmp(path,"linkdir/")
            || ((!strcmp(path,"linkfile") || !strcmp(path,"linkdir"))
                && (flags&(O_PATH|O_NOFOLLOW))!=(O_PATH|O_NOFOLLOW))) {
        native=-ELOOP;
    } else {
        /* These fixture paths have no intermediate symlinks. openat with
         * NOFOLLOW is an independent oracle when Android denies openat2. */
        native=openat(directory,path,flags|O_CLOEXEC|O_NOFOLLOW);
        if (native<0) native=-errno;
    }
    unavailable=1;
    long fallback=md_host_open_resolved(directory,path,&how);
    if ((native<0 || fallback<0) && native!=fallback)
        fprintf(stderr,"host open path=%s flags=%#x native=%ld fallback=%ld\n",path,flags,native,fallback);
    assert(native>=0 ? fallback>=0 : native==fallback);
    if (native>=0) {
        struct stat a,b; assert(!fstat(native,&a) && !fstat(fallback,&b));
        assert(a.st_dev==b.st_dev && a.st_ino==b.st_ino && a.st_mode==b.st_mode);
        close(native); close(fallback);
    }
}
int main(void) {
    pid_t probe=fork(); assert(probe>=0);
    if (!probe) {
        long result=__real_md_raw(SYS_openat2,AT_FDCWD,0,0,0,0,0);
        _exit(result==-ENOSYS ? 2 : result==-EINVAL ? 0 : 3);
    }
    int status; assert(waitpid(probe,&status,0)==probe);
    assert((WIFEXITED(status) && (WEXITSTATUS(status)==0 || WEXITSTATUS(status)==2))
        || (WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS));
    native_oracle=WIFEXITED(status) && WEXITSTATUS(status)==0;
    char root[]="host-open-XXXXXX"; assert(mkdtemp(root));
    int directory=open(root,O_PATH|O_DIRECTORY|O_CLOEXEC); assert(directory>=0);
    assert(!mkdirat(directory,"dir",0700));
    int file=openat(directory,"dir/file",O_CREAT|O_WRONLY|O_CLOEXEC,0600); assert(file>=0); close(file);
    assert(!symlinkat("dir",directory,"linkdir") && !symlinkat("dir/file",directory,"linkfile"));
    const char *paths[]={"", ".", "./", "dir", "dir/", "dir//file", "dir/../dir/file",
        "missing/file", "dir/file/child", "linkdir/file", "linkfile", "linkdir/", "linkdir"};
    const unsigned flags[]={O_RDONLY,O_DIRECTORY,O_PATH,O_PATH|O_NOFOLLOW,O_PATH|O_DIRECTORY};
    for (unsigned i=0;i<sizeof(paths)/sizeof(*paths);++i)
        for (unsigned j=0;j<sizeof(flags)/sizeof(*flags);++j) compare(directory,paths[i],flags[j]);
    char memory[64]; snprintf(memory,sizeof(memory),"/proc/%d/mem",getpid());
    compare(AT_FDCWD,memory,O_RDONLY|O_DIRECTORY);
    compare(-1,"dir/file",O_RDONLY);
    struct open_how unsupported={.flags=O_RDONLY,.resolve=RESOLVE_BENEATH|RESOLVE_NO_SYMLINKS};
    assert(md_host_open_resolved(directory,"dir/file",&unsupported)==-EOPNOTSUPP);
    const unsigned scopes[]={RESOLVE_BENEATH,RESOLVE_IN_ROOT,RESOLVE_NO_XDEV,
        RESOLVE_BENEATH|RESOLVE_NO_XDEV|RESOLVE_NO_SYMLINKS|RESOLVE_NO_MAGICLINKS};
    const char *self[]={".","./","././"};
    for (unsigned i=0;i<sizeof(scopes)/sizeof(*scopes);++i) {
        struct open_how scoped={.flags=O_PATH|O_CLOEXEC,.resolve=scopes[i]};
        for (unsigned j=0;j<sizeof(self)/sizeof(*self);++j) {
            long opened=md_host_open_resolved(directory,self[j],&scoped); assert(opened>=0);
            struct stat a,b; assert(!fstat(directory,&a) && !fstat(opened,&b));
            assert(a.st_dev==b.st_dev && a.st_ino==b.st_ino); close(opened);
        }
        assert(md_host_open_resolved(-1,".",&scoped)==-EBADF);
        if (scopes[i]&RESOLVE_BENEATH) {
            assert(md_host_open_resolved(directory,"/",&scoped)==-EXDEV);
            assert(md_host_open_resolved(directory,"./../dir",&scoped)==-EXDEV);
            assert(md_host_open_resolved(-1,"../dir",&scoped)==-EBADF);
        }
        if (scopes[i]&RESOLVE_IN_ROOT) {
            long opened=md_host_open_resolved(directory,"/./",&scoped); assert(opened>=0); close(opened);
        }
    }
    file=openat(directory,"race",O_CREAT|O_WRONLY|O_CLOEXEC,0600); assert(file>=0); close(file);
    race_directory=directory;
    struct open_how no_links={.flags=O_PATH|O_CLOEXEC,.resolve=RESOLVE_NO_SYMLINKS};
    file=md_host_open_resolved(directory,"race",&no_links);
    assert(file>=0 && raced);
    struct stat retained,current;
    assert(!fstat(file,&retained) && S_ISREG(retained.st_mode));
    assert(!fstatat(directory,"race",&current,AT_SYMLINK_NOFOLLOW) && S_ISLNK(current.st_mode));
    close(file); race_directory=-1;
    assert(md_host_open_resolved(directory,"race",&no_links)==-ELOOP);
    assert(!unlinkat(directory,"race",0));
    assert(!unlinkat(directory,"linkdir",0) && !unlinkat(directory,"linkfile",0));
    assert(!unlinkat(directory,"dir/file",0) && !unlinkat(directory,"dir",AT_REMOVEDIR));
    close(directory); assert(!rmdir(root));
    printf("PASS host open resolution: oracle=%s, retained final FD under replacement, no symlink relaxation and explicit unsupported scopes\n",
        native_oracle ? "openat2" : "openat+known-symlinks");
}
