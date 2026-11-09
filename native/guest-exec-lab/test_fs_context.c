#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "fs_storage.h"
#include "inode_internal.h"
#include "mount_graph.h"
#include "procfs_view.h"
#include "proc_static.h"
#include "cgroup.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/mount.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/utsname.h>
#include <time.h>
#include <unistd.h>

static unsigned descriptors(void) {
    DIR *d=opendir("/proc/self/fd"); assert(d);
    unsigned n=0; struct dirent *e;
    while ((e=readdir(d))) if (e->d_name[0]!='.') ++n;
    assert(!closedir(d)); return n;
}
static int capture(void *out,const struct md_control_file_ops *ops,void *data,
        const struct md_identity *id,int flags,struct md_fs_anchor *origin) {
    (void)id; (void)flags; (void)origin;
    if (!out) return -ENOMEM;
    *(struct md_control_file *)out=(struct md_control_file){ops,ops->retain(data)}; return 0;
}
static struct md_fs_result call(struct md_filesystem *fs,struct md_fs_request q,const struct md_fs_output *out) {
    assert(!md_fs_request_validate(&q));
    struct md_fs_result result;
    md_fs_execute(fs,&q,&result,out); return result;
}
static void temporary_remount(struct md_filesystem *fs,int context,struct md_control_file control,
        int mounted,struct md_fs_anchor *anchor,unsigned mode) {
    const char *keys[]={"mode","uid","gid","size","nr_inodes"};
    const char *values[]={"0700","123","456","25%","1024"};
    struct md_fs_request q={.operation=MD_FS_FSCONFIG,.directory={context,-1},
        .control=control,.mode=FSCONFIG_SET_STRING};
    for (unsigned i=0;i<5;++i) {
        q.path[0]=keys[i]; q.value=values[i]; q.value_size=(unsigned)strlen(values[i])+1;
        assert(!call(fs,q,NULL).error);
    }
    q.mode=FSCONFIG_CMD_RECONFIGURE; q.path[0]=NULL; q.value=NULL; q.value_size=0;
    assert(!call(fs,q,NULL).error);
    assert(!call(fs,q,NULL).error);
    const char *options="mode=0700,uid=123,gid=456,size=25%";
    struct md_fs_request legacy={.operation=MD_FS_MOUNT,.directory={mounted,-1},
        .origin={anchor,NULL},.path={"",NULL},.mode=MD_FS_MOUNT_REMOUNT,.flags=MS_REMOUNT,
        .value=options,.value_size=(unsigned)strlen(options)+1};
    assert(!call(fs,legacy,NULL).error);
    legacy.value="mode=888"; legacy.value_size=9;
    assert(call(fs,legacy,NULL).error==-EINVAL);
    struct md_fs_result root=call(fs,(struct md_fs_request){.operation=MD_FS_FSTAT,
        .directory={mounted,-1},.origin={anchor,NULL}},NULL);
    assert(!root.error && (root.info.mode&07777)==mode
        && root.info.uid==0 && root.info.gid==0);
    q.mode=FSCONFIG_SET_STRING; q.path[0]="size"; q.value="nonsense"; q.value_size=9;
    assert(!call(fs,q,NULL).error);
    q.mode=FSCONFIG_CMD_RECONFIGURE; q.path[0]=NULL; q.value=NULL; q.value_size=0;
    assert(call(fs,q,NULL).error==-EINVAL);
    puts("PASS temporary reconfigure: shared validation, unchanged root metadata, cleared parameters and invalid capacity");
}
static void persistent_readonly(const char *path,struct md_inode_store *store) {
    struct md_inode_store *peer=NULL;
    assert(!md_inode_store_open(path,0,&peer));
    peer->identity=store->identity;
    assert(!md_inode_store_readonly(peer));
    struct md_inode_store *maintenance=NULL;
    assert(!md_inode_operation_window_begin(store));
    assert(md_inode_store_open_exclusive(path,&maintenance)==-EBUSY && !maintenance);
    assert(!md_inode_operation_window_end(store));
    int fd=md_inode_open(store,-1,"/writable",O_CREAT|O_RDWR|O_CLOEXEC,0600); assert(fd>=0);
    assert(!ftruncate(fd,4096));
    char *mapping=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0); assert(mapping!=MAP_FAILED);
    int deleted=md_inode_open(store,-1,"/unlinked",O_CREAT|O_RDWR|O_CLOEXEC,0600); assert(deleted>=0);
    assert(!md_inode_unlink(store,-1,"/unlinked",0));
    assert(!md_inode_store_reconfigure(store,1));
    assert(md_inode_store_readonly(peer)==1);
    assert(md_inode_open(peer,-1,"/writable",O_WRONLY|O_CLOEXEC,0)==-EROFS);
    assert(md_inode_create(peer,-1,"/blocked",0600)==-EROFS);
    assert(md_inode_unlink(peer,-1,"/writable",0)==-EROFS);
    assert(md_inode_rename(peer,-1,"/writable",-1,"/renamed",0)==-EROFS);
    int read=md_inode_open(peer,-1,"/writable",O_RDONLY|O_CLOEXEC,0); assert(read>=0); close(read);
    assert(pwrite(fd,"fd",2,0)==2 && write(deleted,"kept",4)==4);
    mapping[2]='m'; assert(!msync(mapping,4096,MS_SYNC));
    assert(!md_inode_store_reconfigure(peer,0) && !md_inode_store_readonly(store));
    int allowed=md_inode_create(store,-1,"/allowed",0600); assert(allowed>=0); close(allowed);
    assert(!munmap(mapping,4096)); close(fd); close(deleted);
    md_inode_store_close(peer);
    puts("PASS persistent readonly: shared policy blocks new writes, retains native OFDs/mappings and restores rw");
}
static void pressure_probe(const char *base) {
    int root=open(base,O_PATH|O_DIRECTORY|O_CLOEXEC); assert(root>=0);
    assert(md_procfs_pressure_probe(root)==0);
    assert(!mkdirat(root,"pressure",0700));
    const char *paths[]={"pressure/cpu","pressure/memory","pressure/io"};
    const char sample[]="some avg10=0.00 avg60=0.00 avg300=0.00 total=0\n";
    for (unsigned i=0;i<3;++i) {
        int fd=openat(root,paths[i],O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600); assert(fd>=0);
        assert(write(fd,sample,sizeof(sample)-1)==sizeof(sample)-1);
        assert(!close(fd));
        assert(md_procfs_pressure_probe(root)==(i==2));
    }
    assert(!fchmodat(root,paths[1],0000,0));
    assert(md_procfs_pressure_probe(root)==0);
    assert(!fchmodat(root,paths[1],0600,0));
    int fd=openat(root,paths[1],O_WRONLY|O_TRUNC|O_CLOEXEC); assert(fd>=0);
    assert(!close(fd));
    assert(md_procfs_pressure_probe(root)==-EIO);
    for (unsigned i=0;i<3;++i) assert(!unlinkat(root,paths[i],0));
    assert(!unlinkat(root,"pressure",AT_REMOVEDIR));
    assert(md_procfs_pressure_probe(-1)==-EBADF);
    assert(!close(root));
    puts("PASS proc PSI admission: complete, missing, partial, denied and unexpected errors");
}
static void kernel_snapshot(const char *base) {
    int root=open(base,O_PATH|O_DIRECTORY|O_CLOEXEC); assert(root>=0);
    assert(!mkdirat(root,"kernel-native",0700) && !mkdirat(root,"kernel-snapshot",0700));
    int lower=openat(root,"kernel-native",O_PATH|O_DIRECTORY|O_CLOEXEC);
    int upper=openat(root,"kernel-snapshot",O_PATH|O_DIRECTORY|O_CLOEXEC);
    assert(lower>=0 && upper>=0);
    assert(md_procfs_kernel_snapshot(upper,-1)==-EBADF);
    assert(!md_procfs_kernel_snapshot(upper,lower));
    int fd=openat(upper,"version",O_RDONLY|O_CLOEXEC); assert(fd>=0);
    char text[256]={0}; assert(read(fd,text,sizeof(text)-1)>0); close(fd);
    struct utsname kernel; assert(!uname(&kernel));
    assert(strstr(text,kernel.release) && strstr(text,kernel.version));
    assert(!unlinkat(upper,"version",0));
    fd=openat(lower,"version",O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600); assert(fd>=0); close(fd);
    assert(!md_procfs_kernel_snapshot(upper,lower));
    assert(faccessat(upper,"version",F_OK,0)<0 && errno==ENOENT);
    assert(!fchmodat(lower,"version",0000,0));
    assert(!md_procfs_kernel_snapshot(upper,lower));
    assert(!unlinkat(upper,"version",0) && !unlinkat(lower,"version",0));
    close(upper); close(lower);
    assert(!unlinkat(root,"kernel-native",AT_REMOVEDIR) && !unlinkat(root,"kernel-snapshot",AT_REMOVEDIR));
    close(root);
    puts("PASS proc kernel identity: native admission, denied/missing fallback and unchanged errors");
}
static void host_sample(const char *base,enum md_proc_kind kind) {
    int fd=open(base,O_PATH|O_CLOEXEC); assert(fd>=0);
    struct stat node,st;
    assert(!fstat(fd,&node));
    struct md_cgroup_member *member=md_cgroup_member_new(); assert(member);
    md_cgroup_member_publish(member,gettid(),getpid(),NULL,NULL);
    struct md_control_file file=md_proc_host_sample_new(kind,NULL,NULL,fd,member); assert(file.ops);
    assert(!file.ops->stat(file.context,&st) && st.st_dev==node.st_dev && st.st_ino==node.st_ino
        && st.st_mode==(S_IFREG|0444));
    char text[192]={0},tail[192]={0};
    struct timespec before,after;
    assert(!clock_gettime(CLOCK_BOOTTIME,&before));
    long n=file.ops->read(file.context,NULL,text,sizeof(text)-1,0); assert(n>0);
    assert(!clock_gettime(CLOCK_BOOTTIME,&after));
    long long seconds; int length=0;
    if (kind==MD_PROC_UPTIME) {
        unsigned hundredths;
        assert(sscanf(text,"%lld.%u 0.00\n%n",&seconds,&hundredths,&length)==2 && length==n
            && seconds>=before.tv_sec && seconds<=after.tv_sec && hundredths<100);
    } else {
        struct timespec wall; assert(!clock_gettime(CLOCK_REALTIME,&wall));
        unsigned long long user,system,idle;
        assert(sscanf(text,"cpu %llu 0 %llu %llu 0 0 0 0 0 0\nbtime %lld\n%n",
            &user,&system,&idle,&seconds,&length)==4 && length==n && idle>0
            && seconds>=wall.tv_sec-after.tv_sec-1 && seconds<=wall.tv_sec-before.tv_sec+1);
    }
    assert(file.ops->read(file.context,NULL,tail,sizeof(tail),1)==n-1 && !memcmp(text+1,tail,n-1));
    assert(!file.ops->read(file.context,NULL,tail,sizeof(tail),n));
    assert(file.ops->read(file.context,NULL,tail,sizeof(tail),-1)==-EINVAL);
    assert(file.ops->access(file.context,NULL,W_OK)==-EACCES);
    if (kind==MD_PROC_SYSTEM_STAT) {
        uint64_t user,system,next_user,next_system;
        assert(!md_cgroup_cpu_usage(member,&user,&system));
        struct timespec start,now;
        assert(!clock_gettime(CLOCK_THREAD_CPUTIME_ID,&start));
        do {
            for (volatile unsigned i=0;i<10000;++i) {}
            assert(!clock_gettime(CLOCK_THREAD_CPUTIME_ID,&now));
        } while ((now.tv_sec-start.tv_sec)*1000000000LL+now.tv_nsec-start.tv_nsec<150000000);
        assert(!md_cgroup_cpu_usage(member,&next_user,&next_system));
        assert(next_user+next_system>=user+system+(uint64_t)sysconf(_SC_CLK_TCK)/10);
    }
    md_control_file_release(file); md_cgroup_member_release(member); close(fd);
    puts("PASS proc host sample: boot clock, stable partial reads, inode identity and read-only policy");
}
void test_fs_context(const char *base) {
    unsigned baseline=descriptors();
    pressure_probe(base);
    kernel_snapshot(base);
    host_sample(base,MD_PROC_UPTIME);
    host_sample(base,MD_PROC_SYSTEM_STAT);
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/fs-context",base);
    struct md_inode_store *store=NULL; assert(!md_inode_store_open(path,1,&store));
    struct md_identity root=md_identity_new(0,0); store->identity=&root;
    persistent_readonly(path,store);
    struct md_fs_storage_cache cache={0};
    struct md_filesystem fs={.store=store,.storage=&cache};
    assert(!md_fs_mounts_open(&fs,NULL,0));
    for (unsigned cycle=0;cycle<24;++cycle) {
        for (unsigned stage=0;stage<3;++stage) {
            struct md_control_file control={0};
            struct md_fs_output out={.control=capture,.control_context=&control};
            struct md_fs_request q={.operation=MD_FS_FSOPEN,.directory={-1,-1},
                .path={cycle&1 ? "ramfs" : "tmpfs",NULL},.flags=O_RDWR|O_CLOEXEC};
            struct md_fs_result opened=call(&fs,q,&out); assert(!opened.error && opened.fd>=0 && control.ops);
            struct md_fs_anchor *anchor=NULL;
            int mounted=-1;
            if (stage) {
                q=(struct md_fs_request){.operation=MD_FS_FSCONFIG,.directory={opened.fd,-1},
                    .control=control,.mode=FSCONFIG_CMD_CREATE};
                assert(!call(&fs,q,NULL).error);
            }
            if (stage==2) {
                q.operation=MD_FS_FSMOUNT; q.mode=0; q.flags=O_PATH|O_CLOEXEC;
                struct md_fs_result mount=call(&fs,q,&(struct md_fs_output){.origin=&anchor});
                assert(!mount.error && mount.fd>=0 && anchor);
                mounted=mount.fd;
                if (cycle<2) temporary_remount(&fs,opened.fd,control,mounted,anchor,cycle ? 0755 : 01777);
                struct md_mount_namespace *copy=NULL;
                struct md_fs_location copied_root;
                assert(!md_fs_mounts_copy(&fs,&(struct md_fs_location){0},&copy,&copied_root));
                md_mount_namespace_release(copy);
                struct md_mount_graph_parcel parcel={0};
                assert(!md_mount_graph_export(fs.mounts,store->lifetime->root,&parcel));
                struct md_mount_namespace *received=NULL;
                assert(!md_mount_graph_import(&parcel,&received));
                assert(received->tree && received->tree->count);
                for (unsigned i=0;i<received->tree->count;++i) {
                    const struct md_view_mount *m=&received->tree->mounts[i];
                    assert(!m->id || (m->detached && !m->parent));
                }
                md_mount_graph_parcel_close(&parcel); md_mount_namespace_release(received);
            }
            control.ops->release(control.context); close(opened.fd);
            if (mounted>=0) close(mounted);
            md_fs_anchor_release(anchor);
            assert(!md_view_mount_collect(&fs));
        }
    }
    unsigned before=descriptors();
    struct md_fs_result rejected=call(&fs,(struct md_fs_request){.operation=MD_FS_FSOPEN,
        .directory={-1,-1},.path={"tmpfs",NULL},.flags=O_RDWR},&(struct md_fs_output){.control=capture});
    assert(rejected.error==-ENOMEM && rejected.fd<0 && descriptors()==before);
    md_fs_storage_close(&cache); md_fs_mounts_close(&fs);
    assert(!md_inode_store_reconfigure(store,1));
    store->identity=NULL; md_identity_release(&root); md_inode_store_close(store);
    assert(!md_inode_store_open(path,0,&store));
    assert(!md_inode_store_readonly(store));
    md_inode_store_close(store);
    assert(descriptors()==baseline);
    puts("PASS fscontext ownership: cancellation, namespace copy, detached graph transfer and exact FD cleanup");
}
