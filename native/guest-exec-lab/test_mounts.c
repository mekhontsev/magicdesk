#define _GNU_SOURCE
#include "fs_mounts.h"
#include "fs_mount_internal.h"
#include "fs_storage.h"
#include "inode_watch.h"
#include "inode_internal.h"
#include "task_registry.h"
#include "task_resources.h"
#include "mount_instance.h"
#include "native_mounts.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/openat2.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/vfs.h>
#include <sys/xattr.h>
#include <unistd.h>

void test_mount_copy(const char *base);
void test_network_resolver(const char *base);
void test_fs_control(const char *base);
void test_path_walk(const char *base);
void test_mount_storage(const char *base);
void test_transient_store(const char *base);
void test_fs_context(const char *base);
void test_inode_view(const char *base);
int test_transient_store_child(int argc, char **argv);
void test_fs_anchor(const char *base);
void test_user_namespace_fs(const char *base);
void test_mount_alias(const char *base);
void test_mount_policy(const char *base);
int test_mount_policy_child(int argc, char **argv);
void test_mount_graph(const char *base);
int test_mount_graph_child(int argc, char **argv);
void test_inode_custody(const char *base);
int test_inode_custody_child(int argc, char **argv);
void test_origin_transfer(const char *base);
int test_origin_transfer_child(int argc, char **argv);

static struct md_fs_result call(struct md_filesystem *fs, unsigned op, int fd, const char *path,
        unsigned flags, unsigned mode, void *data, size_t size) {
    struct md_fs_request q = {.actor=123,.operation=op,.directory={fd,-1},.path={path,NULL},.flags=flags,.mode=mode,.capacity=(unsigned)size};
    struct md_fs_output output = {.data=data,.capacity=size};
    struct md_fs_result r;
    md_fs_execute(fs, &q, &r, &output); return r;
}
static int opened(struct md_filesystem *fs, const char *path, unsigned flags) {
    struct md_fs_result r = call(fs, MD_FS_OPEN, -1, path, flags, 0600, NULL, 0);
    if (r.error) fprintf(stderr, "open %s: %d\n", path, r.error);
    assert(!r.error && r.fd >= 0); return r.fd;
}
static int rejected(void *context, const void *data, size_t size) {
    (void)context; (void)data; (void)size; return -EFAULT;
}
static unsigned descriptors(void) {
    DIR *dir=opendir("/proc/self/fd"); assert(dir);
    unsigned count=0; struct dirent *entry;
    while ((entry=readdir(dir))) if (strcmp(entry->d_name,".") && strcmp(entry->d_name,"..")) ++count;
    closedir(dir); return count;
}
static void provider_mount_tables(const char *base) {
    char store[PATH_MAX],source[2][PATH_MAX],path[PATH_MAX];
    snprintf(store,sizeof(store),"%s/provider-tables",base);
    struct md_inode_store *s=NULL; assert(!md_inode_store_open(store,1,&s));
    const char *dirs[]={"/dev","/dev/shm","/proc","/devices"};
    for (unsigned i=0;i<4;++i) assert(!md_inode_mkdir(s,-1,dirs[i],0755));
    for (unsigned i=0;i<2;++i) {
        snprintf(source[i],sizeof(source[i]),"%s/provider-source-%u",base,i);
        assert(!mkdir(source[i],0700));
    }
    snprintf(path,sizeof(path),"%s/shm",source[0]); assert(!mkdir(path,0700));
    unsigned native_children[2]={0};
    for (unsigned mode=0;mode<6;++mode) {
        struct md_fs_storage_cache storage={0};
        struct md_filesystem fs={.store=s,.storage=&storage};
        struct md_fs_attachment mounts[]={{source[0],"/dev",0},{source[1],"/dev/shm",0}};
        if (mode==2) mounts[0].target="/proc";
        if (mode==3) mounts[0].target="/dev/shm";
        if (mode==4) mounts[0].target="/devices";
        if (mode && mode<5) {
            assert(!md_fs_mounts_open(&fs,NULL,0));
            for (unsigned i=0;i<(mode==1 ? 2U : 1U);++i) {
                struct md_view_location target;
                assert(!md_view_walk(&fs,-1,NULL,mounts[i].target,1,0,MD_VIEW_MOUNT_TARGET,&target));
                struct md_mount_source source_mount={.fd=open(mounts[i].source,O_PATH|O_DIRECTORY|O_CLOEXEC),
                    .backing=MD_MOUNT_NATIVE};
                assert(source_mount.fd>=0);
                assert(!md_view_attach_source(&fs,&source_mount,&target,NULL));
                close(source_mount.fd); md_view_location_close(&target);
            }
        }
        for (unsigned info=0;info<2;++info) {
            struct md_fs_result r=call(&fs,MD_FS_MOUNT_TABLE,-1,NULL,O_RDONLY,info,NULL,0);
            assert(!r.error && r.fd>=0);
            FILE *table=fdopen(r.fd,"r"); assert(table);
            char *line=NULL; size_t capacity=0;
            unsigned dev=0,proc=0,shm=0,children=0;
            while (getline(&line,&capacity,table)>=0) {
                assert(sscanf(line,info ? "%*u %*u %*s %*s %4095s" : "%*s %4095s",path)==1);
                dev+=!strcmp(path,"/dev"); proc+=!strcmp(path,"/proc"); shm+=!strcmp(path,"/dev/shm");
                if (!strncmp(path,"/dev/",5) && strcmp(path,"/dev/shm")) ++children;
                if (mode==2) assert(strncmp(path,"/proc/",6));
            }
            assert(!ferror(table)); free(line); fclose(table);
            assert(dev==1 && proc==1 && shm==1);
            if (!mode) native_children[info]=children;
            else if (mode==1) assert(!children);
            else assert(children==native_children[info]);
        }
        md_fs_mounts_close(&fs);
        md_fs_storage_close(&storage);
    }
    md_inode_store_close(s);
    puts("PASS mount tables hide covered native providers, retain explicit children and restore the initial view");
}
static void nested_mounts(const char *base) {
    char source[3][PATH_MAX], store[PATH_MAX], path[PATH_MAX], bytes[8192];
    snprintf(store,sizeof(store),"%s/nested-store",base);
    for (unsigned i=0; i<3; ++i) {
        snprintf(source[i],sizeof(source[i]),"%s/source-%u",base,i);
        assert(!mkdir(source[i],0700));
        snprintf(path,sizeof(path),"%s/marker",source[i]);
        int fd=open(path,O_CREAT|O_WRONLY|O_CLOEXEC,0600); assert(fd>=0);
        assert(write(fd,&"012"[i],1)==1); close(fd);
    }
    for (unsigned i=0; i<2; ++i) {
        snprintf(path,sizeof(path),"%s/child",source[i]); assert(!mkdir(path,0700));
    }
    struct md_inode_store *s=NULL; assert(!md_inode_store_open(store,1,&s));
    assert(!md_inode_mkdir(s,-1,"/tree",0700));
    assert(!md_inode_mkdir(s,-1,"/tree/child",0700));
    struct md_filesystem fs={.store=s};
    struct md_fs_attachment entries[]={{source[0],"/tree",0},
        {source[1],"/tree/child",1},{source[2],"/tree/child/child",0}};
    snprintf(path,sizeof(path),"%s/child",source[0]);
    int covered=open(path,O_PATH|O_DIRECTORY|O_CLOEXEC); assert(covered>=0);
    assert(!md_fs_mounts_open(&fs,entries,3));
    const char *names[]={"/tree","/tree/child","/tree/child/child"};
    unsigned long long ids[3];
    for (unsigned i=0; i<3; ++i) {
        snprintf(path,sizeof(path),"%s/marker",names[i]);
        int fd=opened(&fs,path,O_RDONLY); assert(read(fd,bytes,1)==1 && bytes[0]=="012"[i]);
        struct md_fs_result r=call(&fs,MD_FS_FSTAT,fd,NULL,0,0,NULL,0);
        assert(!r.error); ids[i]=r.info.mount_id;
        r=call(&fs,MD_FS_PATH,fd,NULL,0,0,bytes,sizeof(bytes));
        assert(!r.error && !strcmp(bytes,path)); close(fd);
        int dir=opened(&fs,names[i],O_PATH|O_DIRECTORY);
        r=call(&fs,MD_FS_READLINK,-1,names[i],0,0,bytes,sizeof(bytes));
        assert(r.error==-EINVAL);
        r=call(&fs,MD_FS_READLINK,dir,"",0,0,bytes,sizeof(bytes));
        assert(r.error==-ENOENT);
        r=call(&fs,MD_FS_OPEN,dir,"../marker",O_RDONLY,0,NULL,0);
        if (!i) assert(r.error==-ENOENT);
        else { assert(!r.error && read(r.fd,bytes,1)==1 && bytes[0]=="012"[i-1]); close(r.fd); }
        r=call(&fs,MD_FS_OPEN,dir,"new",O_CREAT|O_WRONLY,0600,NULL,0);
        if (i==1) assert(r.error==-EROFS);
        else { assert(!r.error); close(r.fd); }
        struct md_fs_request q={.operation=MD_FS_OPEN,.directory={dir,-1},
            .path={"../marker",NULL},.flags=O_RDONLY,.resolve=RESOLVE_NO_XDEV};
        md_fs_execute(&fs,&q,&r,NULL); assert(r.error==-EXDEV);
        q.path[0]="../marker"; q.resolve=RESOLVE_IN_ROOT;
        md_fs_execute(&fs,&q,&r,NULL); assert(!r.error && read(r.fd,bytes,1)==1 && bytes[0]=="012"[i]); close(r.fd);
        q.path[0]="../marker"; q.resolve=RESOLVE_BENEATH;
        md_fs_execute(&fs,&q,&r,NULL); assert(r.error==-EXDEV);
        close(dir);
    }
    assert(ids[0]!=ids[1] && ids[1]!=ids[2] && ids[0]!=ids[2]);
    uint64_t root_id; assert(!md_fs_base_mount(&fs,0,&root_id));
    struct md_fs_result r=call(&fs,MD_FS_MOUNT_TABLE,-1,NULL,O_RDONLY,1,NULL,0); assert(!r.error);
    ssize_t count=read(r.fd,bytes,sizeof(bytes)-1); assert(count>0); bytes[count]=0; close(r.fd);
    for (unsigned i=0; i<3; ++i) {
        char line[80]; snprintf(line,sizeof(line),"%llu %llu ",ids[i],i ? ids[i-1] : (unsigned long long)root_id);
        assert(strstr(bytes,line));
    }
    assert(!strstr(bytes,base));
    /* A descriptor opened before a covering mount keeps its original view. */
    r=call(&fs,MD_FS_OPEN,covered,"marker",O_RDONLY,0,NULL,0); assert(r.error==-ENOENT);
    r=call(&fs,MD_FS_OPEN,covered,"./marker",O_RDONLY,0,NULL,0); assert(r.error==-ENOENT);
    int leaf=opened(&fs,"/tree/child/child",O_RDONLY|O_DIRECTORY);
    snprintf(path,sizeof(path),"%s/link",source[0]); assert(!symlink("/tree/child/child",path));
    int alias=opened(&fs,"/tree/link/marker",O_RDONLY); assert(read(alias,bytes,1)==1 && bytes[0]=='2'); close(alias);
    r=call(&fs,MD_FS_UNLINK,-1,"/tree/child",AT_REMOVEDIR,0,NULL,0); assert(r.error==-EBUSY || r.error==-EROFS);
    struct md_fs_request rename={.operation=MD_FS_RENAME,.directory={-1,-1},.path={"/tree/marker","/tree/child/marker"}};
    md_fs_execute(&fs,&rename,&r,NULL); assert(r.error==-EXDEV);
    /* Covered dentries move independently of source objects and retained FDs. */
    snprintf(path,sizeof(path),"%s/child",source[0]);
    char moved[PATH_MAX]; snprintf(moved,sizeof(moved),"%s/renamed",source[0]); assert(!renameat(AT_FDCWD,path,AT_FDCWD,moved));
    r=call(&fs,MD_FS_PATH,leaf,NULL,0,0,bytes,sizeof(bytes));
    assert(!r.error && !strcmp(bytes,"/tree/renamed/child"));
    alias=opened(&fs,"/tree/renamed/child/marker",O_RDONLY); close(alias);
    r=call(&fs,MD_FS_OPEN,leaf,"../marker",O_RDONLY,0,NULL,0);
    assert(!r.error && read(r.fd,bytes,1)==1 && bytes[0]=='1'); close(r.fd);
    struct md_guest_domain *context=md_domain_new(); assert(context);
    struct md_identity identity=md_identity_new(0,0);
    fs.tasks=md_task_registry_create(); assert(fs.tasks);
    struct md_task_resources resources={.fs=context,.mounts=fs.mounts};
    assert(!md_task_registry_publish(fs.tasks,123,123,&identity,&resources));
    snprintf(path,sizeof(path),"%s/absolute",source[1]); assert(!symlink("/child/marker",path));
    r=call(&fs,MD_FS_CHROOT,-1,"/tree/renamed",0,0,NULL,0); assert(!r.error);
    alias=opened(&fs,"/../../marker",O_RDONLY); assert(read(alias,bytes,1)==1 && bytes[0]=='1'); close(alias);
    alias=opened(&fs,"/absolute",O_RDONLY); assert(read(alias,bytes,1)==1 && bytes[0]=='2'); close(alias);
    r=call(&fs,MD_FS_OPEN,-1,"/denied",O_CREAT|O_WRONLY,0600,NULL,0); assert(r.error==-EROFS);
    alias=opened(&fs,"/child/writable",O_CREAT|O_WRONLY); close(alias);
    r=call(&fs,MD_FS_PATH,leaf,NULL,0,0,bytes,sizeof(bytes)); assert(!r.error && !strcmp(bytes,"/child"));
    r=call(&fs,MD_FS_PATH,covered,NULL,0,0,bytes,sizeof(bytes)); assert(r.error==-ENOENT);
    r=call(&fs,MD_FS_OPEN,-1,"/proc/self/status",O_RDONLY,0,bytes,sizeof(bytes)); assert(r.error==-ENOENT && !r.host_path);
    r=call(&fs,MD_FS_MOUNT_TABLE,-1,NULL,O_RDONLY,1,NULL,0); assert(!r.error);
    count=read(r.fd,bytes,sizeof(bytes)-1); assert(count>0); bytes[count]=0; close(r.fd);
    /* A readonly bind does not change its backing superblock. */
    assert(strstr(bytes," / ro - none attachment rw") && strstr(bytes," /child rw - none attachment rw"));
    assert(!strstr(bytes,"/tree") && !strstr(bytes,"/proc") && !strstr(bytes,base));
    /* Path prefixes belong to one export, not the lifetime of the mount view. */
    snprintf(path,sizeof(path),"%s/child",source[1]);
    snprintf(moved,sizeof(moved),"%s/renamed-child",source[1]);
    assert(!renameat(AT_FDCWD,path,AT_FDCWD,moved));
    for (unsigned info=0;info<2;++info) {
        r=call(&fs,MD_FS_MOUNT_TABLE,-1,NULL,O_RDONLY,info,NULL,0); assert(!r.error);
        count=read(r.fd,bytes,sizeof(bytes)-1); assert(count>0); bytes[count]=0; close(r.fd);
        assert(strstr(bytes," /renamed-child ") && !strstr(bytes," /child "));
        assert(!strstr(bytes,"/tree") && !strstr(bytes,base));
    }
    assert(!renameat(AT_FDCWD,moved,AT_FDCWD,path));
    md_task_registry_destroy(fs.tasks); fs.tasks=NULL;
    md_identity_release(&identity); md_domain_release(context);
    close(leaf); close(covered); md_fs_mounts_close(&fs);
    /* Invalid topology is rejected before a partially usable tree is published. */
    struct md_fs_attachment reversed[]={{source[1],"/tree/child",0},{source[0],"/tree",0}};
    assert(md_fs_mounts_open(&fs,reversed,2)==-EBUSY && !fs.mounts);
    struct md_fs_attachment overlapping[]={{source[0],"/tree",0},{source[0],"/tree/renamed",1}};
    assert(md_fs_mounts_open(&fs,overlapping,2)==-EXDEV && !fs.mounts);
    unsigned before=descriptors();
    struct md_fs_attachment invalid[]={
        {NULL,"/tree",0},{source[0],NULL,0},{"/no-such-mount-source","/tree",0},
        {source[0],"/absent",0},{source[0],"/",0}};
    for (unsigned i=0; i<sizeof(invalid)/sizeof(invalid[0]); ++i) {
        assert(md_fs_mounts_open(&fs,&invalid[i],1)<0 && !fs.mounts);
        assert(descriptors()==before);
    }
    for (unsigned i=0; i<8; ++i) {
        assert(md_fs_mounts_open(&fs,reversed,2)==-EBUSY && !fs.mounts);
        assert(md_fs_mounts_open(&fs,overlapping,2)==-EXDEV && !fs.mounts);
        assert(descriptors()==before);
    }
    struct md_view_mount partial={.source=open(source[0],O_PATH|O_CLOEXEC),.target=-1,.generation=17};
    assert(partial.source>=0 && !md_mount_policy_set(&partial.policy,MD_MOUNT_READONLY));
    assert(md_mount_policy_share(&partial.policy)>=0);
    md_view_mount_close(&partial);
    assert(partial.source==-1 && partial.target==-1 && partial.detached && !partial.id && partial.generation==17);
    int reused=open(source[0],O_PATH|O_CLOEXEC); assert(reused>=0);
    md_view_mount_close(&partial);
    assert(fcntl(reused,F_GETFD)>=0); close(reused);
    assert(descriptors()==before);
    md_inode_store_close(s);
    puts("PASS nested mount topology, independent readonly, parent IDs, retained covered FDs and rename");
    puts("PASS native roots, root-clamped traversal, absolute symlinks and nested mount visibility");
    puts("PASS failed mount-tree construction releases partial resources without descriptor leaks");
}
static void task_mount_views(const char *base) {
    unsigned before=descriptors();
    char store[PATH_MAX],host[PATH_MAX],path[PATH_MAX],bytes[8192];
    snprintf(store,sizeof(store),"%s/task-store",base);
    snprintf(host,sizeof(host),"%s/task-source",base); assert(!mkdir(host,0700));
    snprintf(path,sizeof(path),"%s/value",host);
    int fd=open(path,O_CREAT|O_WRONLY|O_CLOEXEC,0600); assert(fd>=0);
    assert(write(fd,"native",6)==6); close(fd);
    struct md_inode_store *s=NULL; assert(!md_inode_store_open(store,1,&s));
    assert(!md_inode_mkdir(s,-1,"/left",0700) && !md_inode_mkdir(s,-1,"/right",0700));
    struct md_filesystem views[2]={{.store=s},{.store=s}};
    struct md_fs_attachment entries[]={{host,"/left",0},{host,"/right",1}};
    struct md_task_registry *tasks=md_task_registry_create(); assert(tasks);
    struct md_identity ids=md_identity_new(0,0);
    struct md_guest_domain *domains[2];
    for(unsigned i=0;i<2;++i) {
        assert(!md_fs_mounts_open(&views[i],&entries[i],1));
        domains[i]=md_domain_new(); assert(domains[i]);
        struct md_task_resources resources={.fs=domains[i],.mounts=views[i].mounts};
        assert(!md_task_registry_publish(tasks,123+i,120,&ids,&resources));
    }
    /* One worker dispatches alternating actors, never its own default view. */
    struct md_filesystem fs={.store=s,.tasks=tasks,.mounts=views[0].mounts};
    struct md_fs_request q={.actor=123,.peer=120,.operation=MD_FS_OPEN,
        .directory={-1,-1},.flags=O_RDONLY};
    struct md_fs_output output={.data=bytes,.capacity=sizeof(bytes)};
    struct md_fs_result out;
    for(unsigned round=0;round<20;++round) for(unsigned i=0;i<2;++i) {
        q.actor=123+i; q.operation=MD_FS_OPEN; q.flags=O_RDONLY; q.mode=0;
        q.path[0]=i ? "/right/value" : "/left/value";
        md_fs_execute(&fs,&q,&out,NULL);
        assert(!out.error && read(out.fd,bytes,6)==6 && !memcmp(bytes,"native",6)); close(out.fd);
        q.flags=O_RDWR; md_fs_execute(&fs,&q,&out,NULL);
        if(i) assert(out.error==-EROFS); else { assert(!out.error); close(out.fd); }
        q.flags=O_RDONLY; q.path[0]=i ? "/left/value" : "/right/value";
        md_fs_execute(&fs,&q,&out,NULL); assert(out.error==-ENOENT);
        q.operation=MD_FS_MOUNT_TABLE; q.flags=O_RDONLY; q.mode=1;
        md_fs_execute(&fs,&q,&out,NULL); assert(!out.error);
        ssize_t size=read(out.fd,bytes,sizeof(bytes)-1); assert(size>0); bytes[size]=0; close(out.fd);
        assert(strstr(bytes,i ? "/right ro" : "/left rw"));
        assert(!strstr(bytes,i ? "/left " : "/right "));
        assert(fs.mounts==views[0].mounts && !fs.native_root && !s->root_id);
    }
    q.actor=123; q.peer=121; q.operation=MD_FS_OPEN; q.path[0]="/left/value"; q.flags=O_RDONLY; q.mode=0;
    md_fs_execute(&fs,&q,&out,NULL); assert(out.error==-EPERM);
    assert(!md_task_registry_publish(tasks,125,120,&ids,NULL));
    q.actor=125; q.peer=120;
    md_fs_execute(&fs,&q,&out,NULL); assert(out.error==-ENOTSUP);
    struct md_task_snapshot snapshots[2];
    struct md_fs_context contexts[2];
    for(unsigned i=0;i<2;++i) {
        q.actor=123+i; q.operation=MD_FS_CHROOT; q.path[0]=entries[i].target; q.flags=0;
        md_fs_execute(&fs,&q,&out,NULL); assert(!out.error);
        assert(!md_task_registry_read(tasks,123+i,120,&snapshots[i]));
        assert(!md_fs_context_begin(&fs,123+i,120,&contexts[i]));
        assert(snapshots[i].root.mount && snapshots[i].mounts==views[i].mounts);
    }
    /* A mismatched retained root must fail, not find the worker's other tree. */
    struct md_task_resources mismatched={.fs=domains[0],.mounts=views[1].mounts};
    assert(!md_task_registry_publish(tasks,123,120,&ids,&mismatched));
    q.actor=123; q.operation=MD_FS_OPEN; q.path[0]="/value"; q.flags=O_RDONLY;
    md_fs_execute(&fs,&q,&out,NULL); assert(out.error==-ESTALE);
    assert(!fs.native_root && fs.mounts==views[0].mounts && !s->root_id);
    for(unsigned i=0;i<2;++i) {
        md_domain_release(domains[i]);
        md_fs_mounts_close(&views[i]);
    }
    md_task_registry_destroy(tasks);
    fs.tasks=NULL;
    for(unsigned i=0;i<2;++i) {
        q.actor=123+i; q.operation=MD_FS_OPEN; q.path[0]="/value"; q.flags=O_RDWR;
        md_fs_context_execute(&contexts[i],&q,&out,NULL);
        if(i) assert(out.error==-EROFS); else { assert(!out.error); close(out.fd); }
        q.flags=O_RDONLY;
        md_fs_context_execute(&contexts[i],&q,&out,NULL); assert(!out.error);
        assert(read(out.fd,bytes,6)==6 && !memcmp(bytes,"native",6)); close(out.fd);
        md_fs_context_end(&contexts[i]);
    }
    fs=(struct md_filesystem){.store=s};
    for(unsigned i=0;i<2;++i) {
        fs.mounts=snapshots[i].mounts;
        assert(!md_fs_root_select(&fs,&snapshots[i].root));
        q.operation=MD_FS_OPEN; q.path[0]="/value";
        md_fs_execute(&fs,&q,&out,&output); assert(!out.error); close(out.fd);
        fs.mounts=NULL; fs.native_root=NULL; s->root_id=NULL;
        md_task_snapshot_release(&snapshots[i]);
    }
    md_identity_release(&ids); md_inode_store_close(s);
    assert(descriptors()==before);
    puts("PASS task-selected mount views, readonly policy, authenticated snapshots, stale roots and retained lifetime");
}
static void native_attributes(const char *base) {
    char host[PATH_MAX],path[PATH_MAX],store[PATH_MAX],data[512],before[512],after[512];
    snprintf(host,sizeof(host),"%s/xattr-host",base); assert(!mkdir(host,0700));
    snprintf(path,sizeof(path),"%s/value",host);
    int fd=open(path,O_CREAT|O_RDWR|O_CLOEXEC,0600); assert(fd>=0);
    assert(!fsetxattr(fd,"user.fixture","value",5,0));
    ssize_t label=fgetxattr(fd,"security.selinux",before,sizeof(before));
    assert(label>=0 || errno==ENODATA || errno==ENOTSUP);
    struct md_inode_store *s=NULL;
    snprintf(store,sizeof(store),"%s/xattr-store",base); assert(!md_inode_store_open(store,1,&s));
    assert(!md_inode_mkdir(s,-1,"/attached",0700));
    struct md_filesystem fs={.store=s};
    struct md_fs_attachment attachment={host,"/attached",0};
    assert(!md_fs_mounts_open(&fs,&attachment,1));
    struct md_fs_output output={.data=data,.capacity=sizeof(data)};
    struct md_fs_result out;
    for (unsigned descriptor=0;descriptor<2;++descriptor) {
        struct md_fs_request q={.operation=descriptor ? MD_FS_FXATTR : MD_FS_XATTR,
            .directory={descriptor ? fd : -1,-1},.path={descriptor ? NULL : "/attached/value",NULL},
            .mode=MD_FS_XATTR_LIST};
        md_fs_xattr(&fs,&q,&out,NULL);
        assert(!out.error && out.position==sizeof("user.fixture") && !out.size);
        q.capacity=(unsigned)out.position;
        md_fs_xattr(&fs,&q,&out,&output);
        assert(!out.error && out.size==sizeof("user.fixture") && !strcmp(data,"user.fixture"));
        --q.capacity; md_fs_xattr(&fs,&q,&out,&output);
        assert(out.error==-ERANGE && !out.size && !out.position);
        q.capacity=sizeof(data); q.mode=MD_FS_XATTR_GET; q.path[1]="security.selinux";
        md_fs_xattr(&fs,&q,&out,&output); assert(out.error==-ENODATA);
        q.mode=MD_FS_XATTR_SET; q.value="label"; q.value_size=q.capacity=5;
        md_fs_xattr(&fs,&q,&out,NULL); assert(out.error==-EOPNOTSUPP);
        q.mode=MD_FS_XATTR_REMOVE; q.value=NULL; q.value_size=q.capacity=0;
        md_fs_xattr(&fs,&q,&out,NULL); assert(out.error==-ENODATA);
        q.mode=MD_FS_XATTR_GET; q.path[1]="user.fixture"; q.capacity=sizeof(data);
        md_fs_xattr(&fs,&q,&out,&output);
        assert(!out.error && out.size==5 && !memcmp(data,"value",5));
    }
    if (label>=0) assert(fgetxattr(fd,"security.selinux",after,sizeof(after))==label
        && !memcmp(before,after,(size_t)label));
    close(fd); md_fs_mounts_close(&fs); md_inode_store_close(s);
    puts("PASS host bind attributes: filtered size, exact buffer, path/FD parity and untouched host labels");
}
int main(int argc, char **argv) {
    if (argc==3 && !strcmp(argv[1],"--provider-tables")) {
        assert(!mkdir(argv[2],0700)); provider_mount_tables(argv[2]); return 0;
    }
    if (argc==3 && !strcmp(argv[1],"--native-attributes")) {
        assert(!mkdir(argv[2],0700)); native_attributes(argv[2]); return 0;
    }
    int transient=test_transient_store_child(argc,argv);
    if (transient>=0) return transient;
    if (argc==3 && !strcmp(argv[1],"--transient-store")) {
        assert(!mkdir(argv[2],0700)); test_transient_store(argv[2]); test_inode_view(argv[2]); test_fs_context(argv[2]); return 0;
    }
    if (argc==3 && !strcmp(argv[1],"--mount-storage")) {
        assert(!mkdir(argv[2],0700)); test_mount_storage(argv[2]); return 0;
    }
    if (argc==3 && !strcmp(argv[1],"--path-walk")) {
        assert(!mkdir(argv[2],0700)); test_path_walk(argv[2]); return 0;
    }
    setvbuf(stdout,NULL,_IONBF,0);
    int child=test_mount_policy_child(argc,argv);
    if (child>=0) return child;
    child=test_mount_graph_child(argc,argv);
    if (child>=0) return child;
    child=test_inode_custody_child(argc,argv);
    if (child>=0) return child;
    child=test_origin_transfer_child(argc,argv);
    if (child>=0) return child;
    if (argc==3 && !strcmp(argv[1],"--origin-transfer")) {
        assert(!mkdir(argv[2],0700)); test_origin_transfer(argv[2]); return 0;
    }
    if (argc==3 && !strcmp(argv[1],"--inode-custody")) {
        assert(!mkdir(argv[2],0700)); test_inode_custody(argv[2]); return 0;
    }
    if (argc==3 && !strcmp(argv[1],"--mount-graph")) {
        assert(!mkdir(argv[2],0700)); test_mount_graph(argv[2]); return 0;
    }
    if (argc==3 && !strcmp(argv[1],"--mount-policy")) {
        assert(!mkdir(argv[2],0700)); test_mount_policy(argv[2]); return 0;
    }
    assert(argc == 2 && argv[1][0] == '/'); assert(!mkdir(argv[1], 0700));
    char path[4096], host[4096], readonly[4096], store[4096];
    assert(snprintf(host, sizeof(host), "%s/host", argv[1]) < (int)sizeof(host));
    assert(snprintf(readonly, sizeof(readonly), "%s/readonly", argv[1]) < (int)sizeof(readonly));
    assert(snprintf(store, sizeof(store), "%s/store", argv[1]) < (int)sizeof(store));
    assert(!mkdir(host, 0700) && !mkdir(readonly, 0700));
    struct md_inode_store *s = NULL; assert(!md_inode_store_open(store, 1, &s));
    assert(!md_inode_mkdir(s, -1, "/volume", 0700));
    assert(!md_inode_mkdir(s, -1, "/readonly", 0700));
    int fd = md_inode_open(s, -1, "/guest", O_CREAT | O_RDWR, 0600); assert(fd >= 0);
    assert(write(fd, "guest", 5) == 5); close(fd);
    assert(!md_inode_symlink(s, "/volume", -1, "/link"));
    struct md_fs_attachment attachments[] = {{host,"/volume",0},{readonly,"/readonly",1}};
    struct md_filesystem fs = {.store=s};
    assert(!md_fs_mounts_open(&fs, attachments, 2));
    int temporary_directory=opened(&fs,"/",O_RDONLY|O_DIRECTORY);
    for (unsigned exclusive=0;exclusive<2;++exclusive) {
        unsigned flags=O_TMPFILE|O_WRONLY|O_CLOEXEC|(exclusive ? O_EXCL : 0);
        struct md_fs_result absolute=call(&fs,MD_FS_OPEN,-1,"/",flags,0600,NULL,0);
        struct md_fs_result relative=call(&fs,MD_FS_OPEN,temporary_directory,".",flags,0600,NULL,0);
        assert(absolute.error==-EOPNOTSUPP && relative.error==absolute.error
            && absolute.fd==-1 && relative.fd==-1);
    }
    assert(!close(temporary_directory));
    puts("PASS temporary-file admission: selected directory preserves unsupported creation errno for named-file fallback");
    fd = opened(&fs, "/link/file", O_RDWR | O_CREAT); assert(write(fd, "native", 6) == 6);
    snprintf(path, sizeof(path), "%s/file", host);
    struct stat st, native; assert(!fstat(fd, &st) && !stat(path, &native));
    assert(st.st_dev == native.st_dev && st.st_ino == native.st_ino);
    int directory = opened(&fs, "/volume", O_RDONLY | O_DIRECTORY);
    struct md_fs_result result = call(&fs, MD_FS_OPEN, directory, "../guest", O_RDONLY, 0, NULL, 0);
    assert(!result.error); char bytes[4096] = {0}; assert(read(result.fd, bytes, 5) == 5 && !strcmp(bytes, "guest")); close(result.fd);
    assert(!symlinkat("/guest", directory, "absolute"));
    int guest = opened(&fs, "/volume/absolute", O_RDONLY); close(guest);
    int symlink_fd = opened(&fs, "/volume/absolute", O_PATH | O_NOFOLLOW);
    result = call(&fs, MD_FS_REOPEN, symlink_fd, NULL, O_PATH | O_NOFOLLOW, 0, NULL, 0);
    assert(!result.error && !fstat(result.fd, &st) && S_ISLNK(st.st_mode)); close(result.fd); close(symlink_fd);
    assert(!symlinkat("/volume", directory, "self"));
    result = call(&fs, MD_FS_UNLINK, -1, "/volume/self/", AT_REMOVEDIR, 0, NULL, 0);
    assert(result.error == -ENOTDIR);
    result = call(&fs, MD_FS_OPEN, -1, "/volume/file", O_RDONLY | O_TRUNC, 0, NULL, 0);
    assert(result.error == -EINVAL);
    int watch = md_inode_watch_create(s, IN_NONBLOCK); assert(watch >= 0);
    int wd = md_inode_watch_add(s, watch, directory, IN_CREATE | IN_MOVED_FROM | IN_MOVED_TO); assert(wd > 0);
    int observed = opened(&fs, "/volume/observed", O_CREAT | O_WRONLY); close(observed);
    assert(!renameat(directory, "observed", directory, "renamed"));
    char events[4096]; ssize_t event_bytes = md_inode_watch_read(s, watch, events, sizeof(events), NULL, NULL);
    assert(event_bytes > 0); unsigned mask = 0;
    for (size_t at = 0; at < (size_t)event_bytes;) {
        struct inotify_event *event = (void *)(events+at);
        assert(event->wd == wd); mask |= event->mask; at += sizeof(*event)+event->len;
    }
    assert((mask & (IN_CREATE | IN_MOVED_FROM | IN_MOVED_TO)) == (IN_CREATE | IN_MOVED_FROM | IN_MOVED_TO));
    close(watch);
    result = call(&fs, MD_FS_OPEN, -1, "/readonly/new", O_CREAT | O_RDWR, 0600, NULL, 0); assert(result.error == -EROFS);
    snprintf(path, sizeof(path), "%s/file", readonly);
    int external = open(path, O_CREAT | O_WRONLY | O_CLOEXEC, 0600); assert(external >= 0); close(external);
    int ro = opened(&fs, "/readonly/file", O_RDONLY);
    /* Establish the mount object's ordinary cache entry before lease counts. */
    int ro_directory=opened(&fs,"/readonly",O_PATH|O_DIRECTORY); close(ro_directory);
    unsigned metadata_ops[]={MD_FS_CHMOD,MD_FS_CHOWN,MD_FS_UTIMENS};
    unsigned before_metadata=descriptors();
    for (unsigned i=0;i<sizeof(metadata_ops)/sizeof(*metadata_ops);++i) {
        struct md_fs_request metadata={.operation=metadata_ops[i],.directory={-1,-1},
            .path={"/volume/file",NULL},.mode=0640,
            .attributes={.uid=getuid(),.gid=getgid(),.seconds={123456789,123456790},.nanos={0,42}}};
        md_fs_execute(&fs,&metadata,&result,NULL); assert(!result.error && result.fd==-1);
        metadata.path[0]="/readonly/file";
        md_fs_execute(&fs,&metadata,&result,NULL); assert(result.error==-EROFS && result.fd==-1);
        assert(descriptors()==before_metadata);
    }
    assert(!fstat(fd,&st) && (st.st_mode&0777)==0640 && st.st_mtim.tv_nsec==42);
    struct md_fs_statfs by_path;
    struct statfs by_fd;
    result=call(&fs,MD_FS_STATFS,-1,"/readonly/file",0,0,&by_path,sizeof(by_path));
    assert(!result.error && result.fd==-1 && result.size==sizeof(by_path) && !fstatfs(ro,&by_fd));
    assert(by_path.type==(uint64_t)by_fd.f_type && by_path.block_size==(uint64_t)by_fd.f_bsize);
    assert(descriptors()==before_metadata);
    for (unsigned iteration=0;iteration<16;++iteration)
      for (unsigned kind=MD_FS_TASK_CHDIR;kind<=MD_FS_TASK_TRUNCATE;++kind) {
        for (unsigned ro_target=0;ro_target<2;++ro_target) {
            const char *target=kind==MD_FS_TASK_CHDIR ? (ro_target ? "/readonly" : "/volume")
                : ro_target ? "/readonly/file" : "/volume/file";
            struct md_fs_anchor *origin=NULL;
            struct md_fs_output output={.origin=&origin};
            struct md_fs_request prepare={.operation=MD_FS_TASK_PATH,.directory={-1,-1},
                .path={target,NULL},.mode=kind};
            md_fs_execute(&fs,&prepare,&result,&output);
            if (kind==MD_FS_TASK_TRUNCATE && ro_target)
                assert(result.error==-EROFS && result.fd==-1 && !origin);
            else {
                assert(!result.error && result.fd>=0 && origin);
                assert(!fstat(result.fd,&st) && (kind==MD_FS_TASK_CHDIR ? S_ISDIR(st.st_mode) : S_ISREG(st.st_mode)));
                assert(!close(result.fd)); md_fs_anchor_release(origin);
            }
            assert(descriptors()==before_metadata);
        }
    }
    struct md_fs_request permission={.operation=MD_FS_XATTR_OBJECT,.directory={ro,-1},
        .path={NULL,"user.test"},.mode=MD_FS_XATTR_SET};
    md_fs_execute(&fs,&permission,&result,NULL); assert(result.error==-EROFS);
    permission.mode=MD_FS_XATTR_LIST; permission.path[1]=NULL;
    md_fs_execute(&fs,&permission,&result,NULL); assert(result.error==-EXDEV);
    for (unsigned descriptor=0;descriptor<2;++descriptor) {
        struct md_fs_request attr={.operation=descriptor ? MD_FS_FXATTR : MD_FS_XATTR,
            .directory={descriptor ? fd : -1,-1},.path={descriptor ? NULL : "/volume/file","user.md-worker"},
            .mode=MD_FS_XATTR_SET,.capacity=5,.value="value",.value_size=5};
        char value[32]; struct md_fs_output output={.data=value,.capacity=sizeof(value)};
        md_fs_execute(&fs,&attr,&result,&output); assert(!result.error && result.fd==-1);
        attr.mode=MD_FS_XATTR_GET; attr.value=NULL; attr.value_size=0; attr.capacity=sizeof(value);
        md_fs_execute(&fs,&attr,&result,&output);
        assert(!result.error && result.size==5 && result.position==5 && !memcmp(value,"value",5));
        attr.mode=MD_FS_XATTR_REMOVE; attr.capacity=0;
        md_fs_execute(&fs,&attr,&result,&output); assert(!result.error);
        attr.directory[0]=descriptor ? ro : -1; attr.path[0]=descriptor ? NULL : "/readonly/file";
        md_fs_execute(&fs,&attr,&result,&output); assert(result.error==-EROFS && result.fd==-1);
        attr.mode=MD_FS_XATTR_SET; attr.capacity=attr.value_size=5; attr.value="value";
        md_fs_execute(&fs,&attr,&result,&output); assert(result.error==-EROFS && result.fd==-1);
        assert(descriptors()==before_metadata);
    }
    result = call(&fs, MD_FS_REOPEN, ro, NULL, O_PATH | O_CLOEXEC, 1, NULL, 0); assert(result.error == -EROFS);
    assert(!unlink(path));
    result = call(&fs, MD_FS_REOPEN, ro, NULL, O_RDWR, 0, NULL, 0); assert(result.error == -EROFS); close(ro);
    result = call(&fs, MD_FS_PATH, directory, NULL, 0, 0, bytes, sizeof(bytes));
    assert(!result.error && !strcmp(bytes, "/volume"));
    struct md_fs_request q = {.operation=MD_FS_OPEN,.directory={directory,-1},.path={"../guest",NULL},.flags=O_RDONLY,.resolve=RESOLVE_BENEATH};
    md_fs_execute(&fs, &q, &result, NULL); assert(result.error == -EXDEV);
    q.resolve = RESOLVE_NO_XDEV; md_fs_execute(&fs, &q, &result, NULL); assert(result.error == -EXDEV);
    q.path[0] = "/file"; q.resolve = RESOLVE_IN_ROOT;
    md_fs_execute(&fs, &q, &result, NULL); assert(!result.error); close(result.fd);
    q = (struct md_fs_request){.operation=MD_FS_RENAME,.directory={-1,-1},.path={"/guest","/volume/guest"}};
    md_fs_execute(&fs, &q, &result, NULL); assert(result.error == -EXDEV);
    result = call(&fs, MD_FS_UNLINK, -1, "/volume", AT_REMOVEDIR, 0, NULL, 0); assert(result.error == -EBUSY);
    q = (struct md_fs_request){.operation=MD_FS_GETDENTS,.directory={directory,-1},.capacity=sizeof(bytes)};
    struct md_fs_output output = {.data=bytes,.capacity=sizeof(bytes),.deliver=rejected};
    md_fs_execute(&fs, &q, &result, &output); assert(result.error == -EFAULT && lseek(directory, 0, SEEK_CUR) == 0);
    output.deliver = NULL; md_fs_execute(&fs, &q, &result, &output); assert(!result.error && result.size > 0);
    assert(!unlinkat(directory, "file", 0));
    result = call(&fs, MD_FS_REOPEN, fd, NULL, O_RDONLY | O_CLOEXEC, 0, NULL, 0); assert(!result.error);
    memset(bytes, 0, sizeof(bytes)); assert(read(result.fd, bytes, 6) == 6 && !strcmp(bytes, "native")); close(result.fd);
    assert(!md_inode_mkdir(s,-1,"/etc",0755));
    assert(!md_inode_mkdir(s,-1,"/dev",0755));
    assert(!md_inode_mkdir(s,-1,"/dev/shm",01777));
    assert(!md_inode_symlink(s,"../proc/self/mounts",-1,"/etc/mtab"));
    assert(!md_inode_symlink(s,"/dev/shm",-1,"/shared"));
    for (unsigned attached=0; attached<2; ++attached) {
        struct md_filesystem plain={.store=s};
        struct md_filesystem *view=attached ? &fs : &plain;
        result=call(view,MD_FS_OPEN,-1,"/etc/mtab",O_RDONLY,0,bytes,sizeof(bytes));
        assert(!result.error && result.host_path && result.fd==-1 && !strcmp(bytes,"/proc/self/mounts"));
        result=call(view,MD_FS_OPEN,-1,"/etc/mtab",O_PATH|O_NOFOLLOW,0,bytes,sizeof(bytes));
        assert(!result.error && !result.host_path && !fstat(result.fd,&st) && S_ISLNK(st.st_mode)); close(result.fd);
        result=call(view,MD_FS_OPEN,-1,"/shared",O_PATH|O_DIRECTORY,0,bytes,sizeof(bytes));
        assert(!result.error && !result.host_path && result.fd>=0); close(result.fd);
        q=(struct md_fs_request){.operation=MD_FS_OPEN,.directory={-1,-1},.path={"/etc/mtab",NULL},
            .flags=O_RDONLY,.resolve=RESOLVE_NO_XDEV};
        output=(struct md_fs_output){.data=bytes,.capacity=sizeof(bytes)};
        md_fs_execute(view,&q,&result,&output); assert(result.error==-EXDEV);
        q.resolve=RESOLVE_NO_SYMLINKS;
        md_fs_execute(view,&q,&result,&output); assert(result.error==-ELOOP);
        for (unsigned info=0; info<2; ++info) {
            result=call(view,MD_FS_MOUNT_TABLE,-1,NULL,O_RDONLY,info,bytes,sizeof(bytes));
            assert(!result.error && result.fd>=0);
            ssize_t count=read(result.fd,bytes,sizeof(bytes)-1); assert(count>0);
            bytes[count]=0;
            const char *type=md_native_mounts_type(view->mounts->native_mounts,s->lifetime->device);
            assert(type && strstr(bytes,type) && strstr(bytes,"guest:") && !strstr(bytes,"guestfs"));
            assert(strstr(bytes,"/proc") && strstr(bytes,"/dev/shm"));
            assert(!strstr(bytes,store) && !strstr(bytes,host));
            if (attached) assert(strstr(bytes,"/readonly") && strstr(bytes,"/volume"));
            assert((fcntl(result.fd,F_GETFL)&O_ACCMODE)==O_RDONLY);
            assert(lseek(result.fd,0,SEEK_SET)==0); close(result.fd);
        }
        md_fs_mounts_close(&plain);
    }
    struct md_guest_domain *context=md_domain_new(); assert(context);
    struct md_identity identity=md_identity_new(0,0);
    fs.tasks=md_task_registry_create(); assert(fs.tasks);
    struct md_task_resources resources={.fs=context,.mounts=fs.mounts};
    assert(!md_task_registry_publish(fs.tasks,123,123,&identity,&resources));
    int inode_root=md_inode_open(s,-1,"/",O_PATH|O_DIRECTORY,0); assert(inode_root>=0);
    result=call(&fs,MD_FS_CHROOT,-1,"/volume",0,0,NULL,0); assert(!result.error);
    result=call(&fs,MD_FS_OPEN,-1,"/renamed",O_RDONLY,0,NULL,0); assert(!result.error); close(result.fd);
    result=call(&fs,MD_FS_PATH,inode_root,NULL,0,0,bytes,sizeof(bytes)); assert(result.error==-ENOENT);
    result=call(&fs,MD_FS_PATH,directory,NULL,0,0,bytes,sizeof(bytes)); assert(!result.error && !strcmp(bytes,"/"));
    result=call(&fs,MD_FS_CHROOT,inode_root,".",0,0,NULL,0); assert(!result.error); close(inode_root);
    result=call(&fs,MD_FS_CHROOT,-1,"/etc",0,0,NULL,0); assert(!result.error);
    result=call(&fs,MD_FS_OPEN,-1,"/guest",O_RDONLY,0,NULL,0); assert(result.error==-ENOENT);
    result=call(&fs,MD_FS_OPEN,directory,"renamed",O_RDONLY,0,NULL,0);
    assert(!result.error); close(result.fd);
    result=call(&fs,MD_FS_MOUNT_TABLE,-1,NULL,O_RDONLY,1,NULL,0); assert(!result.error);
    ssize_t count=read(result.fd,bytes,sizeof(bytes)-1); assert(count>0); bytes[count]=0;
    assert(strstr(bytes,"guest:") && !strstr(bytes,"guestfs") && !strstr(bytes,"/proc") && !strstr(bytes,"/volume") && !strstr(bytes,"/readonly"));
    close(result.fd);
    md_task_registry_destroy(fs.tasks); fs.tasks=NULL;
    md_identity_release(&identity); md_domain_release(context);
    close(fd); close(directory);
    md_fs_mounts_close(&fs); md_inode_store_close(s);
    puts("PASS native attachments, cross-boundary symlinks/dirfds, readonly retained FDs, mount identity and directory publication");
    puts("PASS host-boundary symlinks, guest shared memory, resolution constraints and guest mount tables");
    puts("PASS scoped roots, retained attachments and filtered mount tables");
    nested_mounts(argv[1]);
    provider_mount_tables(argv[1]);
    test_path_walk(argv[1]);
    task_mount_views(argv[1]);
    native_attributes(argv[1]);
    test_mount_copy(argv[1]);
    test_network_resolver(argv[1]);
    test_fs_control(argv[1]);
    test_mount_storage(argv[1]);
    test_transient_store(argv[1]);
    test_fs_context(argv[1]);
    test_inode_view(argv[1]);
    test_fs_anchor(argv[1]);
    test_user_namespace_fs(argv[1]);
    test_mount_alias(argv[1]);
    test_mount_policy(argv[1]);
    test_mount_graph(argv[1]);
    test_inode_custody(argv[1]);
    test_origin_transfer(argv[1]);
    return 0;
}
