#define _GNU_SOURCE
#include "inode_internal.h"
#include "inode_native.h"
#include "inode_publication.h"
#include "inode_xattr.h"
#include "inode_leaf.h"
#include "inode_watch.h"
#include "inode_overlay.h"
#include "inode_view.h"
#include "landlock_policy.h"
#include "fs_operation.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <dirent.h>
#include <linux/fs.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

static int gets,sets,syncs,changes,kill_after,fail_at;
static int mknod_error;
int __real_mknodat(int,const char *,mode_t,dev_t);
int __wrap_mknodat(int directory,const char *name,mode_t mode,dev_t device) {
    if (mknod_error) { errno=mknod_error; return -1; }
    return __real_mknodat(directory,name,mode,device);
}
static unsigned leaf_stats;
static int replacement_directory=-1;
int __real_fstatat(int,const char *,struct stat *,int);
int __wrap_fstatat(int fd,const char *path,struct stat *st,int flags) {
    const char *name=strrchr(path,'/');
    if (!strcmp(name ? name+1 : path,"stat-observed")) ++leaf_stats;
    int result=__real_fstatat(fd,path,st,flags);
    if (!result && replacement_directory>=0 && !strcmp(name ? name+1 : path,"stat-race")) {
        assert(!renameat(replacement_directory,"race-replacement",replacement_directory,"stat-race"));
        replacement_directory=-1;
    }
    return result;
}
static void after_change(void) {
    if (++changes==kill_after) { kill(getpid(),SIGKILL); _exit(99); }
}
static int fail(void) {
    if (fail_at && changes+1==fail_at) { errno=EIO; return 1; }
    return 0;
}
ssize_t __real_fgetxattr(int,const char *,void *,size_t);
ssize_t __wrap_fgetxattr(int fd,const char *name,void *value,size_t size) {
    ++gets; return __real_fgetxattr(fd,name,value,size);
}
ssize_t __real_getxattr(const char *,const char *,void *,size_t);
ssize_t __wrap_getxattr(const char *path,const char *name,void *value,size_t size) {
    ++gets; return __real_getxattr(path,name,value,size);
}
int __real_fsetxattr(int,const char *,const void *,size_t,int);
int __wrap_fsetxattr(int fd,const char *name,const void *value,size_t size,int flags) {
    ++sets; if (fail()) return -1;
    int r=__real_fsetxattr(fd,name,value,size,flags);
    if (!r) after_change();
    return r;
}
int __real_fchmod(int,mode_t);
int __wrap_fchmod(int fd,mode_t mode) {
    if (fail()) return -1;
    int r=__real_fchmod(fd,mode);
    if (!r) after_change();
    return r;
}
int __real_fsync(int);
int __wrap_fsync(int fd) {
    ++syncs; if (fail()) return -1;
    int r=__real_fsync(fd);
    if (!r) after_change();
    return r;
}
static void reset(void) { gets=sets=syncs=changes=kill_after=fail_at=0; }
static void metadata(int fd,uint32_t uid,uint32_t gid,mode_t mode,nlink_t links) {
    struct stat st;
    assert(!mdi_native_stat(fd,&st));
    assert(st.st_uid==uid && st.st_gid==gid && (st.st_mode&07777)==mode && st.st_nlink==links);
}
static void identities(void) {
    uint64_t bits=UINT64_C(0x123456789abcdef0);
    for (unsigned i=0;i<4096;++i) {
        bits^=bits<<13; bits^=bits>>7; bits^=bits<<17;
        struct stat st={.st_dev=(dev_t)bits,.st_ino=(ino_t)~bits};
        char expected[33],actual[33];
        snprintf(expected,sizeof(expected),"%016" PRIx64 "%016" PRIx64,(uint64_t)st.st_dev,(uint64_t)st.st_ino);
        assert(!mdi_leaf_id(&st,MDI_ROOT,actual) && !strcmp(actual,expected));
    }
    struct stat st={.st_dev=UINT64_C(0x0123456789abcdef),.st_ino=UINT64_C(0xfedcba9876543210)};
    char id[33];
    assert(!mdi_leaf_id(&st,"ffffffffffffffffffffffffffffffff",id));
    assert(!strcmp(id,"fedcba98765432100123456789abcdef"));
    assert(mdi_leaf_id(&st,"g0000000000000000000000000000000",id)==-EIO);
    assert(mdi_leaf_id(&st,"",id)==-EIO);
    puts("PASS native identity encoding: stable format-17 IDs and scoped projection");
}
static void ordinary(int directory) {
    reset();
    for (unsigned i=0;i<1024;++i) {
        int fd=mdi_native_create(directory,"ordinary",O_RDWR,0,0,0644); assert(fd>=0);
        assert(write(fd,"body",4)==4);
        metadata(fd,0,0,0644,1);
        assert(!mdi_native_metadata(fd,0,0,0755)); metadata(fd,0,0,0755,1);
        assert(!mdi_native_metadata(fd,0,0,0644));
        assert(!renameat2(directory,"ordinary",directory,"renamed",RENAME_NOREPLACE));
        assert(!unlinkat(directory,"renamed",0));
        metadata(fd,0,0,0644,0); close(fd);
    }
    assert(!gets && !sets && !syncs);
    struct stat st;
    const mode_t modes[]={0000,0400,01000,02644,04755};
    for (unsigned i=0;i<sizeof(modes)/sizeof(*modes);++i)
        assert(mdi_native_create(directory,"ordinary",O_RDWR,0,0,modes[i])==-EOPNOTSUPP);
    assert(mdi_native_create(directory,"ordinary",O_RDWR,42,0,0644)==-EOPNOTSUPP);
    assert(mdi_native_create(directory,"ordinary",O_RDWR,0,42,0644)==-EOPNOTSUPP);
    assert(fstatat(directory,"ordinary",&st,0)==-1 && errno==ENOENT);
    assert(mdi_native_create(directory,"../escape",O_RDWR,0,0,0644)==-EINVAL);
    assert(mdi_native_create(directory,"ordinary",O_PATH,0,0,0644)==-EINVAL);
    puts("PASS 1024 native create/stat/chmod/rename/unlink cycles: zero metadata xattr calls and zero sync barriers");
}
static void pinned_birth(int directory) {
    assert(mdi_native_pin_create(directory,"../escape",0,0,0600)==-EINVAL);
    assert(mdi_native_pin_create(directory,"pinned",42,0,0600)==-EOPNOTSUPP);
    const int denied[]={EPERM,EACCES,ENOSYS,EOPNOTSUPP,EIO};
    struct stat st;
    for (unsigned i=0;i<sizeof(denied)/sizeof(*denied);++i) {
        mknod_error=denied[i];
        assert(mdi_native_pin_create(directory,"pinned",0,0,0644)==(denied[i]==EIO ? -EIO : -EOPNOTSUPP));
        assert(fstatat(directory,"pinned",&st,0)<0 && errno==ENOENT);
    }
    mknod_error=0;
    int watch=inotify_init1(IN_NONBLOCK|IN_CLOEXEC); assert(watch>=0);
    char proc[64]; snprintf(proc,sizeof(proc),"/proc/self/fd/%d",directory);
    assert(inotify_add_watch(watch,proc,IN_CREATE|IN_OPEN|IN_CLOSE)>0);
    int fd=mdi_native_pin_create(directory,"pinned",0,0,0644);
    if (fd==-EOPNOTSUPP) {
        close(watch);
        puts("SKIP native pin creation: host mknodat regular-file policy; preparation fallback retained"); return;
    }
    assert(fd>=0 && (fcntl(fd,F_GETFL)&O_PATH) && (fcntl(fd,F_GETFD)&FD_CLOEXEC));
    metadata(fd,0,0,0644,1);
    assert(mdi_native_pin_create(directory,"pinned",0,0,0600)==-EEXIST);
    close(fd);
    union { struct inotify_event align; char bytes[1024]; } event;
    ssize_t size=read(watch,event.bytes,sizeof(event.bytes));
    assert(size==(ssize_t)(sizeof(event.align)+event.align.len) && event.align.mask==IN_CREATE);
    assert(event.align.len && !strcmp(event.align.name,"pinned"));
    assert(read(watch,event.bytes,sizeof(event.bytes))<0 && errno==EAGAIN);
    close(watch); assert(!unlinkat(directory,"pinned",0));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
        limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
        int last=-1,next;
        while ((next=fcntl(directory,F_DUPFD_CLOEXEC,0))>=0) last=next;
        assert(errno==EMFILE && last>=0);
        assert(mdi_native_pin_create(directory,"pinned",0,0,0644)==-EMFILE);
        assert(fstatat(directory,"pinned",&st,0)<0 && errno==ENOENT);
        close(last);
        fd=mdi_native_pin_create(directory,"pinned",0,0,0644);
        assert(fd>=0 && (fcntl(fd,F_GETFL)&O_PATH));
        close(fd); assert(!unlinkat(directory,"pinned",0)); _exit(0);
    }
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    puts("PASS native pin creation: no data open, single-slot capacity, EMFILE cleanup and pre-effect fallback");
}
static void detached(int directory) {
    int fd=mdi_native_create(directory,"detached",O_RDWR,0,0,0644); assert(fd>=0);
    assert(!ftruncate(fd,4096));
    char *mapping=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0); assert(mapping!=MAP_FAILED);
    int path=openat(directory,"detached",O_PATH|O_NOFOLLOW|O_CLOEXEC); assert(path>=0);
    assert(!renameat(directory,"detached",directory,"moved"));
    assert(!unlinkat(directory,"moved",0));
    int replacement=mdi_native_create(directory,"detached",O_RDWR,0,0,0644); assert(replacement>=0);
    reset();
    assert(!mdi_native_metadata(path,42,43,0000));
    metadata(fd,42,43,0000,0); metadata(path,42,43,0000,0); metadata(replacement,0,0,0644,1);
    int barriers=syncs; assert(barriers>0);
    assert(!mdi_native_metadata(fd,44,45,06755));
    assert(syncs==barriers);
    metadata(path,44,45,06755,0);
    struct stat physical; assert(!fstat(fd,&physical) && !(physical.st_mode&06000));
    assert(pwrite(fd,"mapped",6,0)==6 && !memcmp(mapping,"mapped",6));
    reset(); assert(!mdi_native_metadata(fd,44,45,06755) && !sets && !syncs);
    assert(!mdi_native_metadata(path,0,0,0644));
    reset(); metadata(path,0,0,0644,0); assert(!gets && !sets && !syncs);
    assert(!mdi_native_metadata(path,46,47,0000)); metadata(fd,46,47,0000,0);
    munmap(mapping,4096); close(fd); close(path); close(replacement);
    assert(!unlinkat(directory,"detached",0));
    puts("PASS O_PATH and open-unlinked metadata retain inode/mmap/replacement isolation and can return to implicit metadata");
}
static void interrupted(int directory,int point,int error) {
    char name[64]; snprintf(name,sizeof(name),"interrupt-%d-%d",point,error);
    int fd=mdi_native_create(directory,name,O_RDWR,0,0,0644); assert(fd>=0);
    reset();
    if (error) {
        fail_at=point; assert(mdi_native_metadata(fd,42,43,0000)==-EIO); fail_at=0;
    } else {
        pid_t child=fork(); assert(child>=0);
        if (!child) { kill_after=point; (void)mdi_native_metadata(fd,42,43,0000); _exit(97); }
        int status; assert(waitpid(child,&status,0)==child && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
    }
    close(fd); fd=openat(directory,name,O_RDWR|O_CLOEXEC); assert(fd>=0);
    int committed=!error && point==4;
    metadata(fd,committed ? 42 : 0,committed ? 43 : 0,committed ? 0000 : 0644,1);
    reset(); assert(!mdi_native_metadata(fd,42,43,0000)); metadata(fd,42,43,0000,1);
    close(fd); assert(!unlinkat(directory,name,0));
}
static void staged(int directory) {
    for (int point=1;point<=3;++point) for (int error=0;error<2;++error) {
        reset();
        if (error) {
            fail_at=point;
            assert(mdi_native_prepare(directory,"staged",O_RDWR,42,43,0400)==-EIO);
            fail_at=0;
            struct stat st; assert(fstatat(directory,"staged",&st,0)<0 && errno==ENOENT);
        } else {
            pid_t child=fork(); assert(child>=0);
            if (!child) { kill_after=point; (void)mdi_native_prepare(directory,"staged",O_RDWR,42,43,0400); _exit(98); }
            int status; assert(waitpid(child,&status,0)==child && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
            assert(!unlinkat(directory,"staged",0));
        }
    }
    reset();
    int fd=mdi_native_prepare(directory,"staged",O_WRONLY,42,43,0400); assert(fd>=0);
    assert(syncs==1 && sets==1);
    assert(!renameat2(directory,"staged",directory,"published",RENAME_NOREPLACE));
    metadata(fd,42,43,0400,1); close(fd);
    fd=openat(directory,"published",O_PATH); assert(fd>=0); metadata(fd,42,43,0400,1); close(fd);
    assert(!unlinkat(directory,"published",0));
    puts("PASS staged ownership: SIGKILL/IO faults before publication; complete metadata after atomic rename");
}
static void malformed(int directory) {
    int fd=mdi_native_create(directory,"malformed",O_RDWR,0,0,0600); assert(fd>=0);
    assert(!fchmod(fd,01700));
    struct stat st;
    char id[33],scope[33];
    assert(!fstat(fd,&st) && !mdi_leaf_id(&st,MDI_ROOT,id));
    strcpy(scope,MDI_ROOT); scope[12]='g';
    assert(mdi_leaf_id(&st,scope,id)==-EIO);
    scope[12]=0; assert(mdi_leaf_id(&st,scope,id)==-EIO);
    assert(mdi_native_stat(fd,&st)==-EIO);
    assert(mdi_native_metadata(fd,1,2,0000)==-EIO);
    assert(!fsetxattr(fd,MDI_NATIVE_METADATA,"bad",3,0));
    assert(mdi_native_stat(fd,&st)==-EIO);
    uint32_t bad[4]={0x31464c53,UINT32_MAX,0,0600};
    assert(!fsetxattr(fd,MDI_NATIVE_METADATA,bad,sizeof(bad),0));
    assert(mdi_native_stat(fd,&st)==-EIO);
    bad[1]=0; bad[3]=010000;
    assert(!fsetxattr(fd,MDI_NATIVE_METADATA,bad,sizeof(bad),0));
    assert(mdi_native_stat(fd,&st)==-EIO);
    assert(mdi_native_metadata(fd,UINT32_MAX,0,0600)==-EINVAL);
    close(fd); assert(!unlinkat(directory,"malformed",0));
    puts("PASS missing, malformed and out-of-range private metadata fail explicitly");
}
static void metadata_reads(int directory) {
    int fd=mdi_native_prepare(directory,"metadata-reads",O_RDWR,42,43,0644); assert(fd>=0);
    int path=openat(directory,"metadata-reads",O_PATH|O_CLOEXEC); assert(path>=0);
    reset(); metadata(fd,42,43,0644,1); assert(gets==1);
    reset(); metadata(path,42,43,0644,1); assert(gets==2);
    struct { uint32_t version; struct md_acl_entry entries[44]; } acl={.version=2};
    acl.entries[0]=(struct md_acl_entry){MD_ACL_USER_OBJ,6,UINT32_MAX};
    for (unsigned i=1;i<=40;++i) acl.entries[i]=(struct md_acl_entry){MD_ACL_USER,4,1000+i};
    acl.entries[41]=(struct md_acl_entry){MD_ACL_GROUP_OBJ,4,UINT32_MAX};
    acl.entries[42]=(struct md_acl_entry){MD_ACL_MASK,4,UINT32_MAX};
    acl.entries[43]=(struct md_acl_entry){MD_ACL_OTHER,4,UINT32_MAX};
    assert(md_acl_valid(&acl,sizeof(acl)));
    assert(!mdi_native_attribute_set(fd,0,&acl,sizeof(acl),0644));
    reset(); metadata(fd,42,43,0644,1); assert(gets==3);
    unsigned char actual[sizeof(acl)];
    assert(mdi_native_attribute(path,0,actual,sizeof(actual))==sizeof(actual));
    assert(!memcmp(actual,&acl,sizeof(acl)));
    assert(mdi_native_attribute(fd,0,actual,sizeof(actual)-1)==-ERANGE);
    assert(!mdi_native_metadata(path,44,45,0600)); metadata(fd,44,45,0600,1);
    assert(!mdi_native_attribute_set(fd,0,NULL,0,0600));
    reset(); metadata(path,44,45,0600,1); assert(gets==2);
    assert(mdi_native_attribute(fd,0,NULL,0)==-ENODATA);
    close(path); close(fd); assert(!unlinkat(directory,"metadata-reads",0));
    puts("PASS sparse metadata: one complete read, O_PATH, large ACL spill, live changes and removal");
}
static void watch(int directory) {
    int watch=inotify_init1(IN_NONBLOCK|IN_CLOEXEC); assert(watch>=0);
    char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",directory);
    assert(inotify_add_watch(watch,path,IN_CREATE|IN_MOVED_FROM|IN_MOVED_TO|IN_DELETE)>=0);
    int fd=mdi_native_create(directory,"before",O_RDWR,0,0,0644); assert(fd>=0);
    assert(!renameat(directory,"before",directory,"after"));
    assert(!unlinkat(directory,"after",0)); close(fd);
    unsigned char events[4096] __attribute__((aligned(8)));
    ssize_t size=read(watch,events,sizeof(events)); assert(size>0);
    const unsigned masks[]={IN_CREATE,IN_MOVED_FROM,IN_MOVED_TO,IN_DELETE};
    const char *names[]={"before","before","after","after"};
    size_t at=0; uint32_t cookie=0;
    for (unsigned i=0;i<4;++i) {
        assert(at+sizeof(struct inotify_event)<=(size_t)size);
        const struct inotify_event *event=(const void *)(events+at);
        assert(event->mask==masks[i] && !strcmp(event->name,names[i]));
        if (i==1) { cookie=event->cookie; assert(cookie); }
        if (i==2) assert(event->cookie==cookie);
        at+=sizeof(*event)+event->len;
    }
    assert(at==(size_t)size); close(watch);
    puts("PASS native namespace emits CREATE, paired MOVE and DELETE without a logical event journal");
}
static int contains(const char *names,ssize_t length,const char *name) {
    for (ssize_t at=0;at<length;at+=(ssize_t)strlen(names+at)+1) if (!strcmp(names+at,name)) return 1;
    return 0;
}
static void catalogue(const char *path) {
    struct md_inode_store *s; assert(!md_inode_store_open(path,1,&s));
    int directory=md_inode_open(s,-1,"/",O_RDONLY|O_DIRECTORY,0); assert(directory>=0);
    int fd=mdi_native_create(directory,"leaf",O_RDWR,0,0,0644); assert(fd>=0);
    assert(!mdi_native_metadata(fd,42,43,0640));
    assert(!fsetxattr(fd,"user.visible","yes",3,0));
    struct stat physical; assert(!fstat(fd,&physical));
    struct mdi_node parent,node;
    assert(!mdi_begin(s,0) && !mdi_fd(s,directory,&parent));
    assert(!mdi_publication_promote(s,&parent,"leaf",&physical,&node));
    assert(!mdi_finish(s,0));
    struct stat st; assert(!md_inode_fstat(s,fd,&st));
    assert(st.st_uid==42 && st.st_gid==43 && (st.st_mode&07777)==0640 && st.st_ino==physical.st_ino);
    char names[256];
    assert(!mdi_begin(s,0));
    for (int internal=0;internal<2;++internal) {
        ssize_t n=mdi_xattr_names(s,&node,names,sizeof(names),internal); assert(n>0);
        assert(contains(names,n,"user.visible") && !contains(names,n,MDI_NATIVE_METADATA));
    }
    assert(mdi_xattr_get(s,&node,MDI_NATIVE_METADATA,NULL,0)==-ENODATA);
    assert(!mdi_finish(s,0));
    struct md_fs_request q={.operation=MD_FS_XATTR_OBJECT,.directory={fd,-1},
        .path={NULL,MDI_NATIVE_METADATA},.mode=MD_FS_XATTR_SET,.value="bad",.value_size=3,.capacity=3};
    struct md_fs_result result={0};
    md_inode_xattr(s,&q,&result,NULL); assert(result.error==-EPERM);
    q.mode=MD_FS_XATTR_REMOVE; q.value=NULL; q.value_size=q.capacity=0;
    md_inode_xattr(s,&q,&result,NULL); assert(result.error==-EPERM);
    int copy=md_inode_create(s,-1,"copy",0600); assert(copy>=0);
    assert(!mdi_copy_attributes(fd,copy));
    assert(fgetxattr(copy,MDI_NATIVE_METADATA,NULL,0)==-1 && errno==ENODATA);
    assert(fgetxattr(copy,"user.visible",names,sizeof(names))==3 && !memcmp(names,"yes",3));
    assert(!fsetxattr(copy,MDI_NATIVE_METADATA,"destination",11,0));
    assert(!mdi_replace_attributes(fd,copy));
    assert(fgetxattr(copy,MDI_NATIVE_METADATA,names,sizeof(names))==11 && !memcmp(names,"destination",11));
    assert(!mdi_overlay_replace_attributes(fd,copy));
    assert(fgetxattr(copy,MDI_NATIVE_METADATA,names,sizeof(names))==11 && !memcmp(names,"destination",11));
    assert(!mdi_replace_attributes(copy,copy) && !mdi_overlay_replace_attributes(copy,copy));
    assert(fgetxattr(copy,"user.visible",names,sizeof(names))==3 && !memcmp(names,"yes",3));
    close(copy); close(fd); close(directory); md_inode_store_close(s);
    puts("PASS promotion captures native metadata; guest/export/COW hide private attributes and preserve destination ownership");
}
static void restored(const char *path) {
    struct md_inode_store *s; assert(!md_inode_store_open_inspect(path,&s));
    int fd=md_inode_open(s,-1,"leaf",O_RDONLY,0); assert(fd>=0);
    struct stat st; assert(!md_inode_fstat(s,fd,&st));
    assert(st.st_uid==42 && st.st_gid==43 && (st.st_mode&07777)==0640);
    assert(fgetxattr(fd,MDI_NATIVE_METADATA,NULL,0)==-1 && errno==ENODATA);
    char data[16]; assert(fgetxattr(fd,"user.visible",data,sizeof(data))==3 && !memcmp(data,"yes",3));
    close(fd); md_inode_store_close(s);
    puts("PASS real backup/restore retains logical ownership and user attributes without the physical metadata record");
}
static sqlite3_int64 scalar(struct md_inode_store *s,const char *sql) {
    sqlite3_stmt *q=NULL; assert(sqlite3_prepare_v2(s->db,sql,-1,&q,NULL)==SQLITE_OK);
    assert(sqlite3_step(q)==SQLITE_ROW); sqlite3_int64 value=sqlite3_column_int64(q,0);
    assert(sqlite3_finalize(q)==SQLITE_OK); return value;
}
static int native_node(struct md_inode_store *s,int fd) {
    assert(!mdi_begin(s,0)); struct mdi_node node; assert(!mdi_fd(s,fd,&node));
    assert(!mdi_finish(s,0)); return node.native;
}
static int reject_delivery(void *context,const void *data,size_t size) {
    (void)context; (void)data; (void)size; return -EFAULT;
}
static void stable_source(struct md_inode_store *s,int dir) {
    int fd=md_inode_create(s,dir,"stable-source",0644); assert(fd>=0 && native_node(s,fd));
    struct stat before,after; assert(!fstat(fd,&before));
    char source[PATH_MAX];
    s->readonly=1;
    assert(md_inode_descriptor_source(s,fd,source,sizeof(source),1)==-EXDEV && native_node(s,fd));
    s->readonly=0;
    sqlite3_int64 changes=sqlite3_total_changes64(s->db);
    assert(md_inode_descriptor_source(s,fd,source,sizeof(source),0)==-EXDEV && native_node(s,fd));
    assert(sqlite3_total_changes64(s->db)==changes);
    assert(!md_inode_descriptor_source(s,fd,source,sizeof(source),1));
    assert(!native_node(s,fd));
    assert(!md_inode_rename(s,dir,"stable-source",dir,"renamed-source",0));
    assert(!md_inode_unlink(s,dir,"renamed-source",0));
    int reopened=open(source,O_PATH|O_NOFOLLOW|O_CLOEXEC); assert(reopened>=0);
    assert(fcntl(reopened,F_GETFL)==(O_PATH|O_NOFOLLOW));
    assert(!fstat(reopened,&after) && before.st_dev==after.st_dev && before.st_ino==after.st_ino);
    close(reopened); close(fd);
    fd=md_inode_create(s,dir,"unlinked-source",0644); assert(fd>=0);
    assert(!md_inode_unlink(s,dir,"unlinked-source",0));
    assert(md_inode_descriptor_source(s,fd,source,sizeof(source),1)==-EXDEV); close(fd);
    puts("PASS O_PATH source discovery leaves native files unchanged; catalogue sources survive rename/unlink");
}
static void creation_events(struct md_inode_store *s,int dir) {
    int observer=md_inode_watch_create(s,IN_NONBLOCK); assert(observer>=0);
    assert(md_inode_watch_add(s,observer,dir,IN_CREATE|IN_OPEN|IN_CLOSE_WRITE)>0);
    for (unsigned variant=0;variant<3;++variant) {
        const char *name=variant==2 ? "catalogue-events" : variant ? "staged-events" : "native-events";
        s->native_files=variant!=2;
        int fd=md_inode_create(s,dir,name,variant==1 ? 0400 : 0644); assert(fd>=0); close(fd);
        s->native_files=1;
        union { struct inotify_event align; char data[4096]; } events;
        ssize_t count=md_inode_watch_read(s,observer,events.data,sizeof(events.data),NULL,NULL); assert(count>0);
        unsigned n=0; const unsigned expected[]={IN_CREATE,IN_OPEN,IN_CLOSE_WRITE};
        for (size_t at=0;at<(size_t)count;) {
            struct inotify_event *e=(void *)(events.data+at); at+=sizeof(*e)+e->len;
            if (n>=3 || e->mask!=expected[n] || !e->len || strcmp(e->name,name))
                fprintf(stderr,"creation events variant=%u index=%u mask=%x name=%s\n",variant,n,e->mask,e->len ? e->name : "");
            assert(n<3 && e->mask==expected[n] && e->len && !strcmp(e->name,name)); ++n;
        }
        assert(n==3);
        assert(!md_inode_unlink(s,dir,name,0));
    }
    close(observer);
    puts("PASS CREATE precedes OPEN and CLOSE for direct-native, staged-native and catalogue allocation");
}
static void birth_fallback(struct md_inode_store *s,int dir) {
    const struct md_identity *previous=s->identity;
    struct md_identity root=md_identity_new(0,0); s->identity=&root;
    for (unsigned denied=0;denied<2;++denied) {
        struct md_inode_birth *birth=NULL;
        struct stat st;
        mknod_error=denied ? EPERM : 0;
        int pin=md_inode_plan_open(s,dir,"pin-fallback",O_CREAT|O_EXCL|O_RDWR,0644,0,
            &(struct md_open_completion){0},1,&birth);
        mknod_error=0;
        assert(pin>=0 && birth);
        assert(md_inode_stat(s,dir,"pin-fallback",0,&st)==-ENOENT);
        char proc[64]; snprintf(proc,sizeof(proc),"/proc/self/fd/%d",pin);
        int fd=open(proc,O_RDWR|O_CLOEXEC); assert(fd>=0);
        assert(!md_inode_birth_commit(&birth) && !birth);
        assert(!md_inode_stat(s,dir,"pin-fallback",0,&st) && (st.st_mode&0777)==0644);
        assert(!md_inode_unlink(s,dir,"pin-fallback",0));
        close(fd); close(pin);
    }
    s->identity=previous; md_identity_release(&root);
    puts("PASS native birth: denied metadata-only creation retains ordinary preparation and publication");
}
static void user_leaves(struct md_inode_store *s) {
    assert(!md_inode_mkdir(s,-1,"users",0777));
    int dir=md_inode_open(s,-1,"users",O_RDONLY|O_DIRECTORY,0); assert(dir>=0);
    const struct md_identity *original=s->identity;
    struct md_identity ids=md_identity_new(42,43); s->identity=&ids;
    s->creation_mask=0027;
    sqlite3_int64 writes=sqlite3_total_changes64(s->db);
    int fd=md_inode_create(s,dir,"private",0666); assert(fd>=0 && native_node(s,fd));
    metadata(fd,42,43,0640,1);
    assert(sqlite3_total_changes64(s->db)==writes);
    assert(!md_inode_rename(s,dir,"private",dir,"renamed",0));
    struct md_inode_store *cold; assert(!md_inode_store_reconnect(s->lifetime,&cold)); cold->identity=&ids;
    struct stat st; assert(!md_inode_stat(cold,dir,"renamed",0,&st) && st.st_uid==42 && st.st_gid==43 && (st.st_mode&07777)==0640);
    assert(!md_inode_link(cold,dir,"renamed",dir,"alias",0));
    assert(!native_node(cold,fd));
    assert(!md_inode_fstat(cold,fd,&st) && st.st_nlink==2 && st.st_uid==42);
    assert(!md_inode_unlink(cold,dir,"alias",0) && !md_inode_unlink(cold,dir,"renamed",0));
    close(fd); md_inode_store_close(cold);
    s->creation_mask=0;
    birth_fallback(s,dir);
    creation_events(s,dir);
    s->identity=original; md_identity_release(&ids); close(dir);
    puts("PASS guest user native birth: umask, zero SQL writes, cold reopen, hardlink promotion and watch ordering");
}
static unsigned descriptors(void) {
    DIR *dir=opendir("/proc/self/fd"); assert(dir);
    unsigned n=0;
    while (readdir(dir)) ++n;
    closedir(dir); return n;
}
static int match_key(void *context,const struct md_landlock_key *key) {
    return !strcmp(context,key->object);
}
static void pinned_rule(struct md_inode_store *s,int dir) {
    int fd=md_inode_create(s,dir,"pinned",0644); assert(fd>=0 && native_node(s,fd));
    struct md_landlock_key key={.store=s->lifetime}; assert(!md_inode_object_id(s,fd,key.object));
    unsigned before=descriptors();
    struct md_landlock_ruleset *rules=md_landlock_ruleset_new(MD_LL_FILE); assert(rules);
    assert(!md_landlock_rule_add(rules,&key,MD_LL_READ_FILE,fd));
    assert(descriptors()==before+1);
    assert(!md_landlock_rule_add(rules,&key,MD_LL_WRITE_FILE,fd));
    struct md_landlock_policy *policy=md_landlock_restrict(NULL,rules); assert(policy);
    struct md_landlock_policy *child=md_landlock_restrict(policy,rules); assert(child);
    assert(descriptors()==before+1);
    assert(!md_inode_unlink(s,dir,"pinned",0)); close(fd);
    fd=md_inode_create(s,dir,"pinned",0644); assert(fd>=0);
    char replacement[33]; assert(!md_inode_object_id(s,fd,replacement) && strcmp(key.object,replacement));
    assert(md_landlock_check(child,MD_LL_READ_FILE,match_key,replacement)==-EACCES);
    assert(!md_landlock_check(child,MD_LL_READ_FILE,match_key,key.object));
    md_landlock_ruleset_release(rules); md_landlock_release(policy);
    assert(descriptors()==before+1);
    md_landlock_release(child); assert(descriptors()==before);
    close(fd); assert(!md_inode_unlink(s,dir,"pinned",0));
    puts("PASS Landlock native inode pin: unlink/replacement isolation, immutable sharing and final FD release");
}
static void catalogue_absence(struct md_inode_store *s,int dir) {
    struct stat st;
    assert(md_inode_stat(s,dir,"absent-warm",0,&st)==-ENOENT);
    struct md_inode_statistics costs={0};
    md_inode_measure(s,&costs);
    for (unsigned i=0;i<128;++i) {
        char name[40]; snprintf(name,sizeof(name),"unique-absent-%u",i);
        assert(md_inode_stat(s,dir,name,0,&st)==-ENOENT);
    }
    assert(costs.version.calls==128 && costs.step.calls==128 && !costs.transaction.calls);
    md_inode_measure(s,NULL);
    struct md_inode_store *writer;
    assert(!md_inode_store_reconnect(s->lifetime,&writer));
    int fd=md_inode_create(writer,dir,"catalogued",0644); assert(fd>=0);
    assert(!md_inode_stat(s,dir,"catalogued",0,&st) && st.st_nlink==1);
    assert(!md_inode_unlink(s,dir,"catalogued",0)); close(fd);
    assert(md_inode_stat(s,dir,"absent-again",0,&st)==-ENOENT);
    fd=md_inode_create(s,dir,"promoted",0644); assert(fd>=0 && native_node(s,fd));
    assert(!md_inode_link(writer,dir,"promoted",dir,"promoted-alias",0));
    assert(!md_inode_stat(s,dir,"promoted-alias",0,&st) && st.st_nlink==2);
    char parent[33]; assert(!md_inode_object_id(s,dir,parent));
    assert(!mdi_begin(s,1));
    assert(!mdi_delete_name(s,parent,"promoted") && !mdi_delete_name(s,parent,"promoted-alias"));
    struct mdi_node node;
    assert(mdi_catalogue_lookup(s,parent,"promoted-alias",&node)==-ENOENT);
    assert(mdi_finish(s,-ECANCELED)==-ECANCELED);
    assert(!md_inode_stat(s,dir,"promoted-alias",0,&st) && st.st_nlink==2);
    assert(!md_inode_unlink(s,dir,"promoted",0) && !md_inode_unlink(s,dir,"promoted-alias",0));
    close(fd); md_inode_store_close(writer);
    puts("PASS catalogue absence: unique native names skip SQL lookup; foreign insert/promotion and local rollback invalidate the proof");
}
static void stat_observation(struct md_inode_store *s,int dir,const char *path) {
    int fd=md_inode_create(s,dir,"stat-observed",0644); assert(fd>=0 && native_node(s,fd));
    assert(!ftruncate(fd,11));
    struct stat first,st;
    leaf_stats=0;
    assert(!md_inode_stat(s,-1,"/work/stat-observed",0,&first) && first.st_size==11);
    assert(leaf_stats==1 && first.st_uid==0 && first.st_gid==0 && (first.st_mode&07777)==0644);
    assert(!ftruncate(fd,37));
    leaf_stats=0;
    assert(!md_inode_stat(s,dir,"stat-observed",0,&st) && st.st_size==37 && st.st_ino==first.st_ino);
    assert(leaf_stats==1);
    struct md_inode_store *writer; assert(!md_inode_store_open(path,0,&writer)); writer->identity=s->identity;
    struct md_fs_request change={.operation=MD_FS_CHOWN,.directory={fd},.attributes={.uid=42,.gid=43}};
    assert(!md_inode_metadata(writer,&change));
    change.operation=MD_FS_CHMOD; change.mode=0400;
    assert(!md_inode_metadata(writer,&change));
    assert(!md_inode_stat(s,dir,"stat-observed",0,&st) && st.st_uid==42 && st.st_gid==43
        && (st.st_mode&07777)==0400 && st.st_size==37 && st.st_ino==first.st_ino);
    assert(!md_inode_symlink(s,"stat-observed",dir,"stat-link"));
    assert(!md_inode_stat(s,dir,"stat-link",0,&st) && st.st_ino==first.st_ino && st.st_uid==42);
    assert(!md_inode_stat(s,dir,"stat-link",AT_SYMLINK_NOFOLLOW,&st) && S_ISLNK(st.st_mode));
    assert(md_inode_stat(s,dir,"stat-observed/",0,&st)==-ENOTDIR);
    struct md_identity user=md_identity_new(1000,1000);
    change=(struct md_fs_request){.operation=MD_FS_CHMOD,.directory={dir},.mode=0700};
    assert(!md_inode_metadata(writer,&change)); s->identity=&user;
    assert(md_inode_stat(s,-1,"/work/stat-observed",0,&st)==-EACCES);
    s->identity=writer->identity; md_identity_release(&user);
    change.mode=0755; assert(!md_inode_metadata(writer,&change));
    assert(!md_inode_rename(writer,dir,"stat-observed",dir,"stat-old",0));
    assert(md_inode_stat(s,dir,"stat-observed",0,&st)==-ENOENT);
    int replacement=md_inode_create(writer,dir,"stat-observed",0600); assert(replacement>=0);
    assert(!ftruncate(replacement,99));
    assert(!md_inode_stat(s,dir,"stat-observed",0,&st) && st.st_size==99 && st.st_ino!=first.st_ino && st.st_uid==0);
    assert(!md_inode_stat(s,dir,"stat-old",0,&st) && st.st_ino==first.st_ino && st.st_uid==42);
    assert(!md_inode_link(writer,dir,"stat-observed",dir,"stat-alias",0));
    assert(!md_inode_stat(s,dir,"stat-observed",0,&st) && st.st_nlink==2 && st.st_size==99);
    assert(!md_inode_unlink(writer,dir,"stat-observed",0));
    assert(md_inode_stat(s,dir,"stat-observed",0,&st)==-ENOENT);
    assert(!md_inode_unlink(writer,dir,"stat-alias",0));
    assert(!md_inode_unlink(writer,dir,"stat-old",0) && !md_inode_unlink(writer,dir,"stat-link",0));
    close(replacement); close(fd); md_inode_store_close(writer);
    puts("PASS stat observation: one leaf lookup, fresh data and foreign metadata/rename/replacement/promotion, symlinks and search permission");
}
static void stat_replacement(struct md_inode_store *s,int dir) {
    int original=md_inode_create(s,dir,"stat-race",0644);
    int replacement=md_inode_create(s,dir,"race-replacement",0644);
    assert(original>=0 && replacement>=0 && native_node(s,original) && native_node(s,replacement));
    assert(!mdi_native_metadata(original,41,42,0640));
    assert(!mdi_native_metadata(replacement,51,52,0600));
    replacement_directory=dir;
    struct stat st,actual;
    assert(md_inode_stat(s,dir,"stat-race",0,&st)==-ESTALE && replacement_directory==-1);
    assert(!fstat(replacement,&actual));
    assert(!md_inode_stat(s,dir,"stat-race",0,&st) && st.st_ino==actual.st_ino
        && st.st_uid==51 && st.st_gid==52 && (st.st_mode&07777)==0600);
    assert(!md_inode_unlink(s,dir,"stat-race",0));
    close(original); close(replacement);
    puts("PASS native metadata race: unpinned lookup rejects replacement between stat and private-attribute capture");
}
static void hybrid(const char *path) {
    struct md_inode_store *s; assert(!md_inode_store_open(path,1,&s));
    struct md_identity ids=md_identity_new(0,0); s->identity=&ids;
    assert(s->native_files);
    assert(!md_inode_mkdir(s,-1,"work",0755));
    int dir=md_inode_open(s,-1,"work",O_RDONLY|O_DIRECTORY,0); assert(dir>=0);
    stat_observation(s,dir,path);
    stat_replacement(s,dir);
    stable_source(s,dir);
    int managed=md_inode_create(s,dir,"trusted-metadata",0644); assert(managed>=0 && native_node(s,managed));
    assert(!md_inode_unlink(s,dir,"trusted-metadata",0));
    s->identity=NULL;
    struct md_fs_request trusted={.operation=MD_FS_CHMOD,.directory={managed},.mode=0000};
    assert(!md_inode_metadata(s,&trusted));
    metadata(managed,0,0,0000,0);
    trusted.operation=MD_FS_CHOWN; trusted.attributes.uid=42; trusted.attributes.gid=43;
    assert(!md_inode_metadata(s,&trusted));
    metadata(managed,42,43,0000,0);
    s->identity=&ids; close(managed);
    puts("PASS trusted metadata updates preserve native logical permissions after unlink");
    catalogue_absence(s,dir);
    sqlite3_int64 objects=scalar(s,"SELECT count(*) FROM objects"),names=scalar(s,"SELECT count(*) FROM names");
    int observer=md_inode_watch_create(s,IN_NONBLOCK); assert(observer>=0);
    int wd=md_inode_watch_add(s,observer,dir,IN_CREATE|IN_DELETE|IN_MOVED_FROM|IN_MOVED_TO); assert(wd>0);
    sqlite3_int64 changes_before=sqlite3_total_changes64(s->db);
    struct md_inode_statistics costs={0};
    md_inode_measure(s,&costs);
    for (unsigned i=0;i<128;++i) {
        int fd=md_inode_create(s,dir,"a",0644); assert(fd>=0 && native_node(s,fd));
        struct stat st; assert(!md_inode_fstat(s,fd,&st) && st.st_uid==0 && (st.st_mode&07777)==0644);
        assert(!md_inode_rename(s,dir,"a",dir,"b",RENAME_NOREPLACE));
        assert(!md_inode_unlink(s,dir,"b",0));
        assert(!md_inode_fstat(s,fd,&st) && !st.st_nlink); close(fd);
    }
    assert(sqlite3_total_changes64(s->db)==changes_before);
    assert(!costs.transaction.calls && !costs.write_admissions && costs.version.calls);
    md_inode_measure(s,NULL);
    assert(scalar(s,"SELECT count(*) FROM objects")==objects && scalar(s,"SELECT count(*) FROM names")==names);
    union { struct inotify_event align; char data[32768]; } events;
    ssize_t count=md_inode_watch_read(s,observer,events.data,sizeof(events.data),NULL,NULL); assert(count>0);
    unsigned n=0,cookie=0;
    for (size_t at=0;at<(size_t)count;) {
        struct inotify_event *e=(void *)(events.data+at); at+=sizeof(*e)+e->len;
        const unsigned masks[]={IN_CREATE,IN_MOVED_FROM,IN_MOVED_TO,IN_DELETE};
        assert(e->wd==wd && e->mask==masks[n%4]);
        assert(!strcmp(e->name,n%4<2 ? "a" : "b"));
        if (n%4==1) { cookie=e->cookie; assert(cookie); }
        if (n%4==2) assert(e->cookie==cookie);
        ++n;
    }
    assert(n==128*4);
    puts("PASS production native create/rename/unlink: zero catalogue writes, stable detached FD and exact native watch cookies");
    int ordered=md_inode_create(s,dir,"ordered",0644); assert(ordered>=0);
    assert(!md_inode_link(s,dir,"ordered",dir,"ordered-alias",0));
    assert(!md_inode_unlink(s,dir,"ordered",0));
    assert(!md_inode_unlink(s,dir,"ordered-alias",0)); close(ordered);
    count=md_inode_watch_read(s,observer,events.data,sizeof(events.data),NULL,NULL); assert(count>0);
    n=0;
    for (size_t at=0;at<(size_t)count;) {
        struct inotify_event *e=(void *)(events.data+at); at+=sizeof(*e)+e->len;
        assert(n<4 && e->mask==(n<2 ? IN_CREATE : IN_DELETE));
        assert(!strcmp(e->name,n%2 ? "ordered-alias" : "ordered")); ++n;
    }
    assert(n==4);
    puts("PASS native and catalogue events retain mutation order across in-place promotion");
    int moved=md_inode_create(s,dir,"to-legacy",0644); assert(moved>=0 && native_node(s,moved));
    assert(!md_inode_rename(s,dir,"to-legacy",-1,"legacy-leaf",0));
    assert(!native_node(s,moved)); close(moved);
    assert(!md_inode_unlink(s,-1,"legacy-leaf",0));
    int fd=md_inode_create(s,dir,"retained (deleted)",0644); assert(fd>=0 && native_node(s,fd));
    char id[33],later[33],resolved[PATH_MAX]; assert(!md_inode_object_id(s,fd,id));
    assert(!md_inode_rename(s,dir,"retained (deleted)",dir,"moved (deleted)",0));
    assert(!md_inode_path(s,fd,resolved,sizeof(resolved)) && !strcmp(resolved,"/work/moved (deleted)"));
    assert(!md_inode_unlink(s,dir,"moved (deleted)",0));
    int replacement=md_inode_create(s,dir,"moved (deleted)",0644); assert(replacement>=0);
    struct md_fs_request q={.operation=MD_FS_CHOWN,.directory={fd,-1},.attributes={.uid=42,.gid=43}};
    assert(!md_inode_metadata(s,&q));
    q.operation=MD_FS_CHMOD; q.mode=0400; assert(!md_inode_metadata(s,&q));
    struct md_inode_store *cold; assert(!md_inode_store_reconnect(s->lifetime,&cold)); cold->identity=&ids;
    struct stat st; assert(!md_inode_fstat(cold,fd,&st) && !st.st_nlink && st.st_uid==42 && (st.st_mode&07777)==0400);
    assert(!md_inode_fstat(cold,replacement,&st) && st.st_nlink==1 && st.st_uid==0);
    assert(!md_inode_object_id(cold,fd,later) && !strcmp(id,later));
    assert(!md_inode_path(cold,fd,resolved,sizeof(resolved)) && !strcmp(resolved,"/work/moved (deleted) (deleted)"));
    md_inode_store_close(cold); close(fd); close(replacement);
    puts("PASS cold native descriptor discovery preserves renamed/deleted names, ownership and replacement isolation");
    int regular=md_inode_create(s,dir,"regular",0644); assert(regular>=0);
    assert(!md_inode_symlink(s,"regular",dir,"link"));
    int duplicate=dup(dir); assert(duplicate>=0);
    char data[64]; assert(md_inode_getdents_deliver(s,dir,data,sizeof(data),reject_delivery,NULL)==-EFAULT);
    assert(lseek(duplicate,0,SEEK_CUR)==0);
    assert(!md_inode_store_reconnect(s->lifetime,&cold)); cold->identity=&ids;
    unsigned found=0;
    while ((count=md_inode_getdents(cold,duplicate,data,sizeof(data)))>0) {
        for (size_t at=0;at<(size_t)count;) {
            struct md_directory_entry *e=(void *)(data+at); at+=e->size;
            unsigned bit=!strcmp(e->name,".") ? 1 : !strcmp(e->name,"..") ? 2 : !strcmp(e->name,"link") ? 4
                : !strcmp(e->name,"regular") ? 8 : !strcmp(e->name,"moved (deleted)") ? 16 : 0;
            assert(bit && !(found&bit)); found|=bit;
        }
    }
    if (count || found!=31) fprintf(stderr,"hybrid readdir: result=%zd found=%u offset=%lld\n",count,found,(long long)lseek(duplicate,0,SEEK_CUR));
    assert(!count && found==31); close(duplicate); md_inode_store_close(cold);
    assert(md_inode_unlink(s,-1,"work",AT_REMOVEDIR)==-ENOTEMPTY);
    puts("PASS mixed directory enumeration: small buffers, rejected delivery, dup and cold shared cursor");
    assert(!md_inode_object_id(s,regular,id));
    struct { uint32_t version; struct md_acl_entry entries[5]; } acl={.version=2,.entries={
        {MD_ACL_USER_OBJ,6,UINT32_MAX},{MD_ACL_USER,4,1000},{MD_ACL_GROUP_OBJ,0,UINT32_MAX},
        {MD_ACL_MASK,4,UINT32_MAX},{MD_ACL_OTHER,0,UINT32_MAX}}};
    q=(struct md_fs_request){.operation=MD_FS_SETACL,.directory={regular,-1},.mode=MD_ACL_ACCESS,
        .value=&acl,.value_size=sizeof(acl),.capacity=sizeof(acl)};
    struct md_fs_result result={0}; md_inode_acl(s,&q,&result,NULL); assert(!result.error);
    assert(!mdi_begin(s,1)); struct mdi_node node; assert(!mdi_fd(s,regular,&node));
    assert(!mdi_xattr_virtual_set(s,&node,"user.overlay.whiteout","y",1)); assert(!mdi_finish(s,0));
    assert(!md_inode_link(s,dir,"regular",dir,"alias",0));
    assert(!native_node(s,regular)); assert(!md_inode_object_id(s,regular,later) && !strcmp(id,later));
    assert(!md_inode_fstat(s,regular,&st) && st.st_nlink==2);
    assert(!mdi_begin(s,0)); assert(!mdi_fd(s,regular,&node)); assert(node.acl_mask==MD_ACL_ACCESS);
    char value; assert(mdi_xattr_get(s,&node,"user.overlay.whiteout",&value,1)==1 && value=='y');
    assert(!mdi_finish(s,0));
    puts("PASS promotion preserves native identity, ACL and overlay attributes across virtual hardlinks");
    pinned_rule(s,dir);
    creation_events(s,dir);
    user_leaves(s);
    close(regular); close(observer); close(dir); md_inode_store_close(s); md_identity_release(&ids);
}
static void kill_birth(enum md_inode_checkpoint point,void *context) {
    if (point==*(enum md_inode_checkpoint *)context) { kill(getpid(),SIGKILL); _exit(99); }
}
static void block_birth_marker(enum md_inode_checkpoint point,void *context) {
    if (point!=MD_NAMESPACE_STAGED) return;
    struct md_inode_store *s=context;
    assert(s->event_sequence>0);
    char name[64]; snprintf(name,sizeof(name),"events.%" PRId64,(int64_t)s->event_sequence);
    assert(!mkdirat(s->lifetime->root,name,0700));
}
static void catalogue_births(const char *path) {
    struct md_inode_store *s; assert(!md_inode_store_open(path,1,&s));
    assert(!md_inode_mkdir(s,-1,"host",0700));
    struct md_identity ids=md_identity_new(0,0); s->identity=&ids;
    int dir=md_inode_open(s,-1,"host",O_RDONLY|O_DIRECTORY,0); assert(dir>=0);
    struct mdi_node parent; assert(!mdi_fd(s,dir,&parent) && parent.mode<0);
    for (unsigned attempt=0;attempt<3;++attempt) {
        struct md_inode_birth *birth=NULL;
        int pin=md_inode_plan_open(s,dir,"prepared",O_CREAT|O_EXCL|O_RDWR,0400,0,&(struct md_open_completion){0},1,&birth);
        assert(pin>=0 && birth);
        struct stat st; assert(md_inode_stat(s,dir,"prepared",0,&st)==-ENOENT);
        if (!attempt) { assert(!md_inode_birth_discard(&birth) && !birth); close(pin); continue; }
        char proc[64]; snprintf(proc,sizeof(proc),"/proc/self/fd/%d",pin);
        int fd=open(proc,O_RDWR|O_CLOEXEC); assert(fd>=0 && write(fd,"body",4)==4);
        if (attempt==2) {
            int competing=md_inode_create(s,dir,"prepared",0644); assert(competing>=0); close(competing);
            assert(md_inode_birth_commit(&birth)==-EEXIST && birth && !md_inode_birth_discard(&birth));
        } else {
            assert(!md_inode_birth_commit(&birth) && !birth && !native_node(s,fd));
            assert(!md_inode_fstat(s,fd,&st) && st.st_uid==0 && (st.st_mode&07777)==0400 && st.st_size==4);
            assert(!md_inode_link(s,dir,"prepared",dir,"alias",0));
            assert(!md_inode_unlink(s,dir,"alias",0));
        }
        assert(!md_inode_unlink(s,dir,"prepared",0));
        close(fd); close(pin);
    }
    assert(!mdi_fd(s,dir,&parent) && parent.mode<0);
    int observer=md_inode_watch_create(s,IN_NONBLOCK); assert(observer>=0);
    assert(md_inode_watch_add(s,observer,dir,IN_CREATE)>0);
    struct md_inode_birth *birth=NULL;
    int pin=md_inode_plan_open(s,dir,"marker-failure",O_CREAT|O_EXCL|O_RDWR,0600,0,&(struct md_open_completion){0},1,&birth);
    assert(pin>=0 && birth);
    md_inode_observe(s,block_birth_marker,s);
    int result=md_inode_birth_commit(&birth);
    assert(result<0 && !birth);
    md_inode_observe(s,NULL,NULL);
    char marker[64]; snprintf(marker,sizeof(marker),"events.%" PRId64,(int64_t)s->event_sequence);
    assert(!unlinkat(s->lifetime->root,marker,AT_REMOVEDIR));
    struct stat published; assert(!md_inode_stat(s,dir,"marker-failure",0,&published));
    assert(!md_inode_unlink(s,dir,"marker-failure",0));
    close(pin); close(observer);
    close(dir); md_inode_store_close(s);
    const enum md_inode_checkpoint points[]={MD_NAMESPACE_STAGED,MD_NAMESPACE_COMMITTED};
    for (unsigned i=0;i<2;++i) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            assert(!md_inode_store_open(path,0,&s)); s->identity=&ids;
            struct md_inode_birth *birth=NULL;
            int pin=md_inode_plan_open(s,-1,"host/crash",O_CREAT|O_EXCL|O_RDWR,0640,0,&(struct md_open_completion){0},1,&birth);
            assert(pin>=0 && birth);
            enum md_inode_checkpoint point=points[i]; md_inode_observe(s,kill_birth,&point);
            md_inode_birth_commit(&birth); _exit(99);
        }
        int status; assert(waitpid(child,&status,0)==child && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
        assert(!md_inode_store_open(path,0,&s)); s->identity=&ids;
        struct stat st;
        assert(md_inode_stat(s,-1,"host/crash",0,&st)==(i ? 0 : -ENOENT));
        if (i) { assert(st.st_uid==0 && (st.st_mode&07777)==0640); assert(!md_inode_unlink(s,-1,"host/crash",0)); }
        md_inode_store_close(s);
    }
    md_identity_release(&ids);
    puts("PASS catalogue birth ownership: unpublished discard/conflict, unchanged host parent, hardlinks, post-commit error and crash before/after commit");
}
static void contents(struct md_inode_store *s,const char *path,const char *text) {
    int fd=md_inode_open(s,-1,path,O_RDONLY,0); assert(fd>=0);
    char data[32]; assert(read(fd,data,sizeof(data))==(ssize_t)strlen(text) && !memcmp(data,text,strlen(text)));
    close(fd);
}
static int count_sql_work(void *data) {
    ++*(unsigned *)data;
    return 0;
}
static void materialization_cost(const char *path) {
    struct md_inode_store *s; assert(!md_inode_store_open(path,1,&s));
    struct md_identity ids=md_identity_new(0,0); s->identity=&ids;
    unsigned work[2]={0},created=0;
    for (unsigned pass=0;pass<2;++pass) {
        unsigned count=pass ? 128 : 32;
        for (;created<count;++created) {
            char name[32]; snprintf(name,sizeof(name),"directory-%u",created);
            assert(!md_inode_mkdir(s,-1,name,0755));
        }
        assert(!mdi_begin(s,1));
        sqlite3_progress_handler(s->db,100,count_sql_work,&work[pass]);
        assert(!mdi_leaf_materialize(s));
        sqlite3_progress_handler(s->db,0,NULL,NULL);
        assert(!mdi_commit(s,0));
    }
    /* Count VM work, not elapsed time: four times the directories must not
     * cause a full scope scan and sort for each next directory. */
    printf("materialization SQL work: 32=%u00 128=%u00 steps\n",work[0],work[1]);
    assert(work[0] && work[1]<=work[0]*6);
    md_inode_store_close(s); md_identity_release(&ids);
    puts("PASS directory materialization SQL work grows linearly");
}
static void sealing(const char *path) {
    struct md_inode_store *s,*copy; assert(!md_inode_store_open(path,1,&s));
    struct md_identity ids=md_identity_new(0,0); s->identity=&ids;
    assert(!md_inode_mkdir(s,-1,"data",0755));
    int fd=md_inode_create(s,-1,"data/file",0644); assert(fd>=0 && native_node(s,fd));
    assert(write(fd,"lower",5)==5);
    char id[33],later[33]; assert(!md_inode_object_id(s,fd,id));
    assert(!md_inode_store_seal(s) && !native_node(s,fd));
    assert(!md_inode_object_id(s,fd,later) && !strcmp(id,later));
    char target[PATH_MAX]; snprintf(target,sizeof(target),"%s-copy",path);
    assert(!md_inode_store_open(target,1,&copy) && !md_inode_snapshot(s,copy));
    int upper=md_inode_open(copy,-1,"data/file",O_RDWR,0); assert(upper>=0);
    assert(pwrite(upper,"upper",5,0)==5); close(upper);
    contents(copy,"data/file","upper"); contents(s,"data/file","lower");
    assert(md_inode_unlink(s,-1,"data/file",0)==-EROFS);
    assert(md_inode_rename(s,-1,"data/file",-1,"data/moved",0)==-EROFS);
    close(fd); md_inode_store_close(copy); md_inode_store_close(s); md_identity_release(&ids);
    puts("PASS automatic native materialization at seal preserves identity, snapshot isolation and COW");
}
static void overlay_events(struct md_inode_store *base,int upper,struct md_inode_store *view,int merged) {
    struct md_inode_store *stores[]={base,view}; int directories[]={upper,merged},observers[2],watches[2];
    unsigned mask=IN_CREATE|IN_MOVED_FROM|IN_MOVED_TO|IN_DELETE;
    for (unsigned i=0;i<2;++i) {
        observers[i]=md_inode_watch_create(stores[i],IN_NONBLOCK); assert(observers[i]>=0);
        watches[i]=md_inode_watch_add(stores[i],observers[i],directories[i],mask); assert(watches[i]>0);
    }
    struct md_inode_birth *birth=NULL;
    int pin=md_inode_plan_open(view,merged,"before",O_CREAT|O_EXCL|O_RDWR,0644,0,&(struct md_open_completion){0},1,&birth);
    assert(pin>=0 && birth);
    char proc[64]; snprintf(proc,sizeof(proc),"/proc/self/fd/%d",pin);
    int fd=open(proc,O_RDWR|O_CLOEXEC); assert(fd>=0);
    assert(!md_inode_birth_commit(&birth) && native_node(view,fd)); close(pin); close(fd);
    assert(!md_inode_rename(view,merged,"before",merged,"after",0));
    assert(!md_inode_unlink(view,merged,"after",0));
    for (unsigned i=0;i<2;++i) {
        union { struct inotify_event align; char data[4096]; } events;
        ssize_t count=md_inode_watch_read(stores[i],observers[i],events.data,sizeof(events.data),NULL,NULL); assert(count>0);
        const unsigned masks[]={IN_CREATE,IN_MOVED_FROM,IN_MOVED_TO,IN_DELETE};
        const char *names[]={"before","before","after","after"}; unsigned n=0,cookie=0;
        for (size_t at=0;at<(size_t)count;) {
            struct inotify_event *e=(void *)(events.data+at); at+=sizeof(*e)+e->len;
            assert(n<4 && e->wd==watches[i] && e->mask==masks[n] && e->len && !strcmp(e->name,names[n]));
            if (n==1) { cookie=e->cookie; assert(cookie); }
            else assert(e->cookie==(n==2 ? cookie : 0));
            ++n;
        }
        assert(n==4 && md_inode_watch_bytes(stores[i],observers[i])==0); close(observers[i]);
    }
    puts("PASS upper and merged native watchers retain exact create/rename/unlink events and paired cookies");
}
static void overlays(const char *path) {
    struct md_inode_store *s,*view=NULL; assert(!md_inode_store_open(path,1,&s));
    struct md_identity ids=md_identity_new(0,0); s->identity=&ids;
    assert(!md_inode_mkdir(s,-1,"unrelated",0755));
    int unrelated=md_inode_create(s,-1,"unrelated/native",0644);
    assert(unrelated>=0 && native_node(s,unrelated));
    const char *names[]={"lower","upper","work"}; int dirs[3];
    for (unsigned i=0;i<3;++i) {
        assert(!md_inode_mkdir(s,-1,names[i],0755));
        dirs[i]=md_inode_open(s,-1,names[i],O_RDONLY|O_DIRECTORY,0); assert(dirs[i]>=0);
        int fd=md_inode_create(s,dirs[i],"file",0644); assert(fd>=0 && native_node(s,fd));
        assert(write(fd,names[i],strlen(names[i]))==(ssize_t)strlen(names[i])); close(fd);
    }
    assert(!md_inode_view_create(s,MD_FILESYSTEM_OVERLAY,&view));
    int layer_error=md_inode_overlay_layer(view,s,dirs[0],1);
    if (layer_error) fprintf(stderr,"overlay lower materialization: %d\n",layer_error);
    assert(!layer_error);
    assert(native_node(s,unrelated));
    assert(md_inode_overlay_prepare_upper(view,s,dirs[1],dirs[2],1)==-ENOTEMPTY);
    md_inode_store_close(view); view=NULL;
    assert(!md_inode_unlink(s,dirs[2],"file",0));
    int fd=md_inode_create(s,dirs[0],"cow",0644); assert(fd>=0 && native_node(s,fd));
    assert(write(fd,"lower",5)==5); close(fd);
    assert(!md_inode_mkdir(s,dirs[0],"nested",0755));
    fd=md_inode_create(s,dirs[0],"nested/file",0644); assert(fd>=0 && native_node(s,fd));
    assert(write(fd,"nested",6)==6); close(fd);
    assert(!md_inode_view_create(s,MD_FILESYSTEM_OVERLAY,&view));
    layer_error=md_inode_overlay_layer(view,s,dirs[0],1);
    if (layer_error) fprintf(stderr,"overlay nested lower materialization: %d\n",layer_error);
    assert(!layer_error);
    assert(!md_inode_overlay_prepare_upper(view,s,dirs[1],dirs[2],1));
    assert(!md_inode_overlay_activate(view) && !md_inode_overlay_stash(view,&ids));
    assert(native_node(s,unrelated));
    view->identity=&ids;
    contents(view,"nested/file","nested");
    struct md_inode_store *peer=NULL;
    assert(!md_inode_store_reconnect(view->lifetime,&peer)); peer->identity=&ids;
    contents(peer,"file","upper"); md_inode_store_close(peer);
    int root=md_inode_open(view,-1,"/",O_RDONLY|O_DIRECTORY,0); assert(root>=0);
    overlay_events(s,dirs[1],view,root);
    creation_events(view,root); pinned_rule(view,root); user_leaves(view);
    contents(view,"file","upper"); contents(view,"cow","lower");
    fd=md_inode_open(view,-1,"cow",O_RDWR,0); assert(fd>=0);
    assert(pwrite(fd,"upper",5,0)==5); close(fd);
    contents(view,"cow","upper"); contents(s,"lower/cow","lower"); contents(s,"upper/cow","upper");
    int native=md_inode_create(view,-1,"native",0644); assert(native>=0 && native_node(view,native));
    assert(write(native,"new",3)==3);
    contents(s,"upper/native","new");
    int original=md_inode_open(s,dirs[1],"native",O_RDONLY,0); assert(original>=0 && native_node(s,original));
    char merged_id[33],upper_id[33],after[33];
    assert(!md_inode_object_id(view,native,merged_id) && !md_inode_object_id(s,original,upper_id));
    assert(strcmp(merged_id,upper_id));
    assert(!md_inode_rename(view,-1,"native",-1,"moved",0));
    contents(s,"upper/moved","new");
    assert(!md_inode_link(view,-1,"moved",-1,"hardlink",0));
    assert(!native_node(view,native) && !native_node(s,original));
    assert(!md_inode_object_id(view,native,after) && !strcmp(merged_id,after));
    assert(!md_inode_object_id(s,original,after) && !strcmp(upper_id,after));
    contents(s,"upper/hardlink","new");
    assert(!md_inode_unlink(view,-1,"moved",0) && !md_inode_unlink(view,-1,"hardlink",0));
    struct stat gone; assert(!md_inode_fstat(view,native,&gone) && !gone.st_nlink);
    assert(!md_inode_fstat(s,original,&gone) && !gone.st_nlink);
    close(native); close(original);
    assert(!md_inode_mkdir(view,-1,"newdir",0755));
    native=md_inode_create(view,-1,"newdir/file",0644); assert(native>=0 && native_node(view,native));
    assert(write(native,"dir",3)==3); close(native);
    contents(s,"upper/newdir/file","dir");
    assert(!md_inode_unlink(view,-1,"cow",0));
    native=md_inode_create(view,-1,"cow",0644); assert(native>=0 && !native_node(view,native));
    close(native);
    assert(!md_inode_unlink(view,-1,"cow",0));
    md_inode_store_close(view);
    close(root);
    contents(s,"upper/newdir/file","dir");
    for (unsigned i=0;i<3;++i) close(dirs[i]);
    assert(native_node(s,unrelated)); close(unrelated);
    md_inode_store_close(s); md_identity_release(&ids);
    puts("PASS native overlay upper ownership, scoped promotion, retained FDs, whiteouts and transactional copy-up");
}
static void restored_hybrid(const char *path) {
    struct md_inode_store *s; assert(!md_inode_store_open_inspect(path,&s));
    struct stat st;
    assert(!md_inode_stat(s,-1,"work/moved (deleted)",0,&st) && S_ISREG(st.st_mode));
    assert(!md_inode_stat(s,-1,"work/regular",0,&st) && st.st_nlink==2);
    md_inode_store_close(s);
    puts("PASS backup materializes unpromoted native names together with catalogue hardlinks");
}
int main(int argc,char **argv) {
    if (argc==3 && !strcmp(argv[1],"--check-backup")) { restored(argv[2]); return 0; }
    if (argc==3 && !strcmp(argv[1],"--check-hybrid-backup")) { restored_hybrid(argv[2]); return 0; }
    assert(argc==2 && argv[1][0]=='/' && !mkdir(argv[1],0700)); umask(0);
    int directory=open(argv[1],O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(directory>=0);
    identities(); ordinary(directory); pinned_birth(directory); detached(directory); staged(directory);
    for (int point=1;point<=4;++point) for (int error=0;error<2;++error) interrupted(directory,point,error);
    puts("PASS SIGKILL and IO failures at all four metadata transition steps retain old-or-new logical permissions");
    reset(); malformed(directory); metadata_reads(directory); watch(directory); close(directory);
    char store[PATH_MAX]; snprintf(store,sizeof(store),"%s/store",argv[1]); catalogue(store);
    snprintf(store,sizeof(store),"%s/hybrid",argv[1]); hybrid(store);
    snprintf(store,sizeof(store),"%s/births",argv[1]); catalogue_births(store);
    snprintf(store,sizeof(store),"%s/materialization",argv[1]); materialization_cost(store);
    snprintf(store,sizeof(store),"%s/sealed",argv[1]); sealing(store);
    snprintf(store,sizeof(store),"%s/overlay",argv[1]); overlays(store);
    return 0;
}
