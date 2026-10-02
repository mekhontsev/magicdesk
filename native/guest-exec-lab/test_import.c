#define _GNU_SOURCE
#include "inode_store.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

#define CHECK(expr) do { if (!(expr)) { \
    fprintf(stderr,"%s:%d: %s (errno=%d)\n",__FILE__,__LINE__,#expr,errno); exit(1); \
} } while (0)
static char root[PATH_MAX];
static unsigned serial;
static const struct md_inode_import_limits limits={128*1024*1024,20000,0};
static void location(char *out,const char *suffix) {
    CHECK(snprintf(out,PATH_MAX,"%s/%s-%u",root,suffix,serial++)<PATH_MAX);
}
static struct md_inode_store *store(char *path) {
    location(path,"store"); struct md_inode_store *s;
    CHECK(md_inode_store_open(path,1,&s)==0); return s;
}
static int source(char *path) {
    location(path,"source"); CHECK(mkdir(path,0700)==0);
    int fd=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); CHECK(fd>=0); return fd;
}
static void file(int directory,const char *name) {
    int fd=openat(directory,name,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600); CHECK(fd>=0);
    CHECK(write(fd,"contents",8)==8 && fchmod(fd,0640)==0);
    struct timespec times[2]={{1000000000,123456789},{1000000001,123456789}};
    CHECK(futimens(fd,times)==0); close(fd);
}
static void imported(struct md_inode_store *s,int source,const char *path) {
    struct stat a,b;
    CHECK(fstatat(source,path,&a,0)==0 && md_inode_stat(s,-1,path,0,&b)==0);
    CHECK(a.st_size==b.st_size && a.st_mode==b.st_mode && b.st_uid==getuid());
    CHECK(a.st_mtim.tv_sec==b.st_mtim.tv_sec && a.st_mtim.tv_nsec==b.st_mtim.tv_nsec);
    int in=openat(source,path,O_RDONLY|O_CLOEXEC), out=md_inode_open(s,-1,path,O_RDONLY, 0);
    CHECK(in>=0 && out>=0); char x[4096],y[4096];
    for(;;) {
        ssize_t n=read(in,x,sizeof(x)); CHECK(n>=0 && read(out,y,sizeof(y))==n);
        if(!n) break;
        CHECK(!memcmp(x,y,(size_t)n));
    }
    close(in); close(out);
}
static unsigned names(struct md_inode_store *s) {
    struct md_inode_audit audit; CHECK(md_inode_audit(s,&audit)==0); return audit.names;
}
static void complete_tree(const char *source_path,const char *destination) {
    int fd=open(source_path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); CHECK(fd>=0);
    struct md_inode_store *s; CHECK(md_inode_store_open(destination,1,&s)==0);
    struct md_inode_import_result result;
    /* Prepared toolkit assets need a larger budget than the small fault fixtures. */
    const struct md_inode_import_limits rootfs_limits={1024ULL*1024*1024,50000,0};
    int r=md_inode_import_tree(s,fd,&rootfs_limits,&result);
    if(r) fprintf(stderr,"complete rootfs import: %d (%s)\n",r,strerror(-r));
    CHECK(!r && result.entries>100 && result.bytes>1024*1024);
    imported(s,fd,"bin/dash"); imported(s,fd,"usr/bin/dpkg"); imported(s,fd,"etc/md-guest-fixture");
    CHECK(names(s)==result.entries);
    printf("PASS prepared Debian rootfs import: entries=%llu bytes=%llu aliases=%llu\n",
        (unsigned long long)result.entries,(unsigned long long)result.bytes,(unsigned long long)result.aliases);
    md_inode_store_close(s); close(fd);
}
static void semantics(void) {
    char srcpath[PATH_MAX],dstpath[PATH_MAX]; int src=source(srcpath);
    file(src,"a"); CHECK(mkdirat(src,"d",0700)==0); file(src,"d/inside");
    CHECK(fchmodat(src,"d",0555,0)==0 && mkdirat(src,"empty",0700)==0 && symlinkat("a",src,"sym")==0);
    int aliases=linkat(src,"a",src,"b",0)==0;
    if(!aliases) { CHECK(errno==EACCES || errno==EPERM); puts("LIMIT source hard links denied by host policy; physical alias import requires another test host"); }
    else CHECK(linkat(src,"sym",src,"sym2",0)==0);
    struct md_inode_store *s=store(dstpath); struct md_inode_import_result result;
    CHECK(md_inode_import_tree(s,src,&limits,&result)==0);
    CHECK(result.entries==(aliases?7U:5U) && result.bytes==16 && result.aliases==(aliases?2U:0U));
    imported(s,src,"a"); imported(s,src,"d/inside");
    char target[8]; CHECK(md_inode_readlink(s,-1,"sym",target,sizeof(target))==1 && target[0]=='a');
    struct stat a,b; CHECK(md_inode_stat(s,-1,"a",0,&a)==0 && a.st_nlink==(aliases?2U:1U));
    if(aliases) {
        CHECK(md_inode_stat(s,-1,"b",0,&b)==0 && b.st_ino==a.st_ino && b.st_dev==a.st_dev);
        CHECK(md_inode_stat(s,-1,"sym",AT_SYMLINK_NOFOLLOW,&a)==0
            && md_inode_stat(s,-1,"sym2",AT_SYMLINK_NOFOLLOW,&b)==0 && a.st_ino==b.st_ino && a.st_nlink==2);
    }
    CHECK(md_inode_stat(s,-1,"d",0,&a)==0 && (a.st_mode&0777)==0555);
    int fd=md_inode_open(s,-1,"a",O_RDWR, 0); CHECK(fd>=0 && pwrite(fd,"changed!",8,0)==8); close(fd);
    fd=openat(src,"a",O_RDONLY); CHECK(fd>=0 && read(fd,target,8)==8 && !memcmp(target,"contents",8)); close(fd);
    CHECK(md_inode_import_tree(s,src,&limits,&result)==-ENOTEMPTY && !result.entries);
    CHECK(names(s)==(aliases?7U:5U)); md_inode_store_close(s);
    CHECK(md_inode_store_open(dstpath,0,&s)==0 && names(s)==(aliases?7U:5U)); md_inode_store_close(s); close(src);
    puts("PASS import: data, modes, timestamps, symlinks, native identity, independent source and reopen");
}
static void fifo_import(void) {
    char srcpath[PATH_MAX], dstpath[PATH_MAX]; int src = source(srcpath);
    if (mkfifoat(src, "pipe", 0640)) {
        CHECK(errno == EACCES || errno == EPERM);
        puts("LIMIT host policy denies native FIFO source creation; guest FIFO is tested separately");
        close(src); return;
    }
    CHECK(!fchmodat(src, "pipe", 0640, 0));
    struct md_inode_store *s = store(dstpath); struct md_inode_import_result result;
    CHECK(!md_inode_import_tree(s, src, &limits, &result) && result.entries == 1 && !result.bytes);
    struct stat st; CHECK(!md_inode_stat(s, -1, "pipe", 0, &st));
    CHECK(S_ISFIFO(st.st_mode) && (st.st_mode & 0777) == 0640 && st.st_size == 0);
    int fd = md_inode_open(s, -1, "pipe", O_RDWR | O_NONBLOCK, 0); CHECK(fd >= 0);
    char value[8]; CHECK(write(fd, "fifo", 4) == 4 && read(fd, value, 8) == 4 && !memcmp(value, "fifo", 4));
    close(fd); CHECK(names(s) == 1); md_inode_store_close(s); close(src);
    puts("PASS imported native FIFO metadata and kernel stream");
}
static void failures(void) {
    for(unsigned test=1;test<7;++test) {
        char srcpath[PATH_MAX],dstpath[PATH_MAX]; int src=source(srcpath); file(src,"a");
        struct md_inode_store *s=store(dstpath); struct md_inode_import_result result;
        struct md_inode_import_limits bound=limits; int error=-ENOTSUP;
        switch(test) {
        case 1: CHECK(fchmodat(src,"a",04700,0)==0); break;
        case 2: bound.bytes=1; error=-EFBIG; break;
        case 3: file(src,"b"); bound.entries=1; error=-EFBIG; break;
        case 4: {
            int fd=openat(src,"a",O_RDWR); CHECK(fd>=0);
            CHECK(fsetxattr(fd,"user.md-import","x",1,0)==0); close(fd); break;
        }
        case 5: close(src); src=open(dstpath,O_RDONLY|O_DIRECTORY); CHECK(src>=0); error=-EINVAL; break;
        case 6: {
            char path[PATH_MAX]; CHECK(snprintf(path,sizeof(path),"%s/objects",dstpath)<PATH_MAX);
            close(src); src=open(path,O_RDONLY|O_DIRECTORY); CHECK(src>=0); error=-EINVAL; break;
        }
        }
        CHECK(md_inode_import_tree(s,src,&bound,&result)==error && !result.entries && !result.bytes);
        CHECK(names(s)==0); md_inode_store_close(s);
        CHECK(md_inode_store_open(dstpath,0,&s)==0 && names(s)==0); md_inode_store_close(s); close(src);
    }
    puts("PASS import rollback: unsupported metadata/types, quotas and overlapping source/storage");
}
static void preserved_ownership(void) {
    char srcpath[PATH_MAX], dstpath[PATH_MAX]; int src=source(srcpath);
    file(src,"program"); CHECK(fchmodat(src,"program",04755,0)==0);
    CHECK(symlinkat("program",src,"link")==0 && fchmod(src,02750)==0);
    struct md_inode_store *s=store(dstpath); struct md_inode_import_result result;
    struct md_inode_import_limits preserved=limits; preserved.preserve_ownership=1;
    CHECK(md_inode_import_tree(s,src,&preserved,&result)==0 && result.entries==2);
    const char *paths[]={"/","program","link"};
    for (unsigned i=0;i<3;i++) {
        struct stat expected, actual;
        CHECK(fstatat(src,i ? paths[i] : ".",&expected,AT_SYMLINK_NOFOLLOW)==0);
        CHECK(md_inode_stat(s,-1,paths[i],AT_SYMLINK_NOFOLLOW,&actual)==0);
        CHECK(actual.st_uid==expected.st_uid && actual.st_gid==expected.st_gid
            && actual.st_mode==expected.st_mode);
    }
    int fd=md_inode_open(s,-1,"program",O_RDONLY,0); struct stat native;
    CHECK(fd>=0 && fstat(fd,&native)==0 && !(native.st_mode&06000) && native.st_uid==getuid());
    close(fd); md_inode_store_close(s);
    CHECK(md_inode_store_open(dstpath,0,&s)==0);
    CHECK(md_inode_stat(s,-1,"program",0,&native)==0 && (native.st_mode&07777)==04755);
    md_inode_store_close(s); close(src);
    puts("PASS preserved import: root, symlink, owner and set-ID metadata without kernel set-ID");
}
struct mutation { int fd; unsigned hits; };
static void mutate(enum md_inode_checkpoint point,void *context) {
    struct mutation *m=context;
    if(point==MD_OBJECT_SYNCED && !m->hits++) CHECK(pwrite(m->fd,"changed source",14,0)==14);
}
static void changing_source(void) {
    char srcpath[PATH_MAX],dstpath[PATH_MAX]; int src=source(srcpath); file(src,"a");
    struct md_inode_store *s=store(dstpath); struct md_inode_import_result result;
    struct mutation m={.fd=openat(src,"a",O_RDWR)}; CHECK(m.fd>=0);
    md_inode_observe(s,mutate,&m);
    CHECK(md_inode_import_tree(s,src,&limits,&result)==-ESTALE && !names(s));
    close(m.fd); close(src); md_inode_store_close(s);
    puts("PASS import rejects observed source mutation instead of publishing a mixed file");
}
struct crash { enum md_inode_checkpoint point; int notify,release; };
static void crash_at(enum md_inode_checkpoint point,void *context) {
    struct crash *c=context; if(point!=c->point) return;
    CHECK(write(c->notify,"x",1)==1); char byte;
    /* EVENT_WAIT: parent kills at exact transaction checkpoint; fixture timeout cancels a stuck test. */
    CHECK(read(c->release,&byte,1)==1); _exit(2);
}
static void recovery(void) {
    for(unsigned stage=0;stage<3;++stage) {
        char srcpath[PATH_MAX],dstpath[PATH_MAX]; int src=source(srcpath); file(src,"a");
        CHECK(mkdirat(src,"d",0700)==0); file(src,"d/b");
        struct md_inode_store *s=store(dstpath); md_inode_store_close(s);
        int notify[2],release[2]; CHECK(pipe2(notify,O_CLOEXEC)==0 && pipe2(release,O_CLOEXEC)==0);
        pid_t pid=fork(); CHECK(pid>=0);
        if(!pid) {
            close(notify[0]); close(release[1]); CHECK(md_inode_store_open(dstpath,0,&s)==0);
            struct crash c={(enum md_inode_checkpoint)stage,notify[1],release[0]};
            md_inode_observe(s,crash_at,&c); struct md_inode_import_result result;
            md_inode_import_tree(s,src,&limits,&result); _exit(3);
        }
        close(notify[1]); close(release[0]); char byte;
        /* EVENT_WAIT: import checkpoint, then child death; outer deadline detects a stuck fixture. */
        CHECK(read(notify[0],&byte,1)==1 && byte=='x'); CHECK(kill(pid,SIGKILL)==0); int status;
        CHECK(waitpid(pid,&status,0)==pid && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
        close(notify[0]); close(release[1]); CHECK(md_inode_store_open(dstpath,0,&s)==0);
        CHECK(names(s)==(stage==MD_NAMESPACE_COMMITTED?3U:0U));
        if(stage==MD_NAMESPACE_COMMITTED) imported(s,src,"d/b");
        else {
            struct md_inode_import_result result;
            CHECK(md_inode_import_tree(s,src,&limits,&result)==0 && names(s)==3);
        }
        md_inode_store_close(s); close(src);
    }
    puts("PASS import: three deterministic SIGKILL boundaries, all-or-empty recovery and inspected retry");
}
int main(int argc,char **argv) {
    CHECK((argc==2 || argc==4) && argv[1][0]=='/' && strlen(argv[1])<sizeof(root)); strcpy(root,argv[1]);
    if(argc==4) complete_tree(argv[2],argv[3]);
    CHECK(mkdir(root,0700)==0);
    semantics(); fifo_import(); failures(); preserved_ownership(); changing_source(); recovery();
    puts("PASS offline import fixture (not execution from the imported namespace)"); return 0;
}
