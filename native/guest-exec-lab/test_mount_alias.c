#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "inode_internal.h"
#include "task_registry.h"
#include "task_resources.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static unsigned descriptors(void) {
    DIR *d=opendir("/proc/self/fd"); assert(d);
    unsigned n=0; while (readdir(d)) ++n;
    closedir(d); return n;
}
static struct md_fs_result call(struct md_filesystem *fs, unsigned op, const char *path,
        int fd, struct md_fs_anchor *origin, unsigned flags, void *data, size_t capacity,
        struct md_fs_anchor **capture) {
    struct md_fs_request q={.actor=123,.operation=op,.directory={fd,-1},.origin={origin,NULL},
        .path={path,NULL},.flags=flags,.mode=op==MD_FS_FSTAT ? MD_FS_STAT_MOUNT : 0};
    struct md_fs_output output={.data=data,.capacity=capacity,.origin=capture};
    struct md_fs_result r;
    md_fs_execute(fs,&q,&r,&output); return r;
}
static void captured_lifetime(struct md_filesystem *fs, const char *parent) {
    unsigned before=descriptors(), objects=fs->mounts->tree->objects;
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/transient",parent);
    for (unsigned i=0;i<1024;++i) {
        struct md_fs_anchor *origin=NULL;
        struct md_fs_request q={.operation=MD_FS_OPEN,.directory={-1,-1},
            .path={"/rw/transient",NULL},.flags=O_RDWR|O_CREAT|O_EXCL,.mode=0600};
        struct md_fs_result r;
        md_fs_execute(fs,&q,&r,&(struct md_fs_output){.origin=&origin});
        assert(!r.error && r.fd>=0 && origin);
        assert(fs->mounts->tree->objects==objects && descriptors()==before+2);
        assert(!md_fs_anchor_check_fd(origin,md_fs_anchor_fd(origin)));
        assert(!md_fs_anchor_check_fd(origin,r.fd));
        assert(md_fs_anchor_check_fd(origin,fs->mounts->tree->mounts[0].source)==-ESTALE);
        assert(!write(r.fd,"",0) && !unlink(path));
        int fd=r.fd;
        r=call(fs,MD_FS_FSTAT,NULL,fd,origin,0,NULL,0,NULL);
        assert(!r.error && !r.info.links && r.info.mount_id==fs->mounts->tree->mounts[0].id);
        r=call(fs,MD_FS_REOPEN,NULL,fd,origin,O_RDWR,NULL,0,NULL);
        assert(!r.error && r.fd>=0); close(r.fd);
        close(fd); md_fs_anchor_release(origin);
        assert(fs->mounts->tree->objects==objects && descriptors()==before);
    }
}
static void native_open_plans(struct md_filesystem *fs,const char *parent) {
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/planned",parent);
    int fd=open(path,O_CREAT|O_WRONLY|O_CLOEXEC,0600); assert(fd>=0);
    assert(write(fd,"plan",4)==4); close(fd);
    unsigned before=descriptors();
    for (unsigned flags_index=0;flags_index<3;++flags_index) {
        unsigned flags=flags_index==0 ? O_RDONLY : flags_index==1 ? O_PATH : O_WRONLY|O_TRUNC;
        for (unsigned readonly=0;readonly<2;++readonly) {
            struct md_fs_anchor *origin=NULL;
            struct md_fs_request q={.operation=MD_FS_OPEN,.directory={-1,-1},
                .path={readonly ? "/ro/planned" : "/rw/planned",NULL},.flags=flags};
            struct md_fs_result r;
            md_fs_execute(fs,&q,&r,&(struct md_fs_output){.origin=&origin,.native_open_plan=1});
            if (readonly && flags_index==2) { assert(r.error==-EROFS && !origin && r.fd<0); continue; }
            assert(!r.error && r.fd==-1 && origin && r.open_completion.kind==MD_OPEN_PLAN);
            assert(md_fs_anchor_policy(origin)&MD_MOUNT_READONLY ? readonly : !readonly);
            struct stat st; assert(!fstat(md_fs_anchor_fd(origin),&st) && st.st_size==4);
            assert(r.open_completion.backing_device==(uint64_t)st.st_dev
                && r.open_completion.backing_inode==(uint64_t)st.st_ino);
            /* Admission must not perform O_TRUNC. Cancellation only drops pins. */
            md_fs_anchor_release(origin);
            assert(descriptors()==before);
        }
    }
    struct md_fs_anchor *origin=NULL;
    struct md_fs_request q={.operation=MD_FS_OPEN,.directory={-1,-1},
        .path={"/rw/planned",NULL},.flags=O_RDONLY};
    struct md_fs_result r;
    md_fs_execute(fs,&q,&r,&(struct md_fs_output){.origin=&origin,.native_open_plan=1});
    assert(!r.error && origin);
    assert(!unlink(path));
    int opened=md_open_pinned(md_fs_anchor_fd(origin),O_RDONLY); assert(opened>=0);
    char data[4]; assert(read(opened,data,sizeof(data))==4 && !memcmp(data,"plan",4));
    close(opened); md_fs_anchor_release(origin);
    q.path[0]="/rw/missing-plan"; q.flags=O_CREAT|O_RDWR; q.mode=0600;
    origin=NULL;
    md_fs_execute(fs,&q,&r,&(struct md_fs_output){.origin=&origin,.native_open_plan=1});
    assert(r.error==-EXDEV && !origin && r.fd<0);
    q.flags=O_RDONLY;
    md_fs_execute(fs,&q,&r,NULL); assert(r.error==-ENOENT);
    snprintf(path,sizeof(path),"%s/no-search",parent); assert(!mkdir(path,0000));
    q.path[0]="/rw/no-search"; q.flags=O_PATH|O_NOFOLLOW;
    md_fs_execute(fs,&q,&r,&(struct md_fs_output){.origin=&origin,.native_open_plan=1});
    assert(!r.error && origin && r.fd<0 && r.open_completion.kind==MD_OPEN_PLAN);
    char source[64];
    unsigned flags=md_open_plan_path(source,0,(unsigned)md_fs_anchor_fd(origin),q.flags,r.open_completion.mode);
    opened=open(source,(int)flags);
    assert(opened>=0 && fcntl(opened,F_GETFL)==(O_PATH|O_NOFOLLOW));
    close(opened); md_fs_anchor_release(origin); origin=NULL;
    md_fs_execute(fs,&q,&r,&(struct md_fs_output){.origin=&origin});
    assert(!r.error && r.fd>=0 && origin && fcntl(r.fd,F_GETFL)==(O_PATH|O_NOFOLLOW));
    close(r.fd); md_fs_anchor_release(origin);
    assert(!chmod(path,0700) && !rmdir(path));
    assert(descriptors()==before);
    puts("PASS native mount open plans: policy, cancellation, no speculative effects and unlinked identity");
}
static void file_aliases(struct md_filesystem *fs, const char *path, int reverse) {
    int created=open(path,O_CREAT|O_WRONLY|O_CLOEXEC,0600); assert(created>=0);
    assert(write(created,"value",5)==5); close(created);
    struct md_fs_anchor *origin[2]={0};
    const char *paths[]={"/rw/value","/ro/value"};
    int fds[2]; char ids[2][33];
    for (unsigned turn=0;turn<2;++turn) {
        unsigned i=reverse ? 1-turn : turn;
        struct md_fs_result r=call(fs,MD_FS_OPEN,paths[i],-1,NULL,O_RDONLY,NULL,0,&origin[i]);
        if (r.error) fprintf(stderr,"alias open %s: %d\n",paths[i],r.error);
        assert(!r.error && r.fd>=0 && origin[i]); fds[i]=r.fd;
    }
    struct stat x,y; assert(!fstat(fds[0],&x) && !fstat(fds[1],&y));
    assert(md_view_same(&x,&y));
    struct md_fs_location root;
    assert(md_fs_root_capture(fs,fds[0],origin[0],&root)==-ENOTDIR);
    assert(md_fs_root_capture(fs,fs->mounts->tree->mounts[0].source,origin[0],&root)==-ESTALE);
    for (unsigned i=0;i<2;++i) {
        struct md_fs_result r=call(fs,MD_FS_FSTAT,NULL,fds[i],origin[i],0,NULL,0,NULL);
        assert(!r.error && r.info.mount_id==fs->mounts->tree->mounts[i].id);
        r=call(fs,MD_FS_OBJECT_ID,NULL,fds[i],origin[i],0,ids[i],sizeof(ids[i]),NULL);
        assert(!r.error && r.size==sizeof(ids[i]));
        r=call(fs,MD_FS_REOPEN,NULL,fds[i],origin[i],O_RDWR,NULL,0,NULL);
        if (i) assert(r.error==-EROFS && r.fd==-1);
        else { assert(!r.error); close(r.fd); }
        struct md_image_identity image;
        r=call(fs,MD_FS_OPEN_IMAGE,paths[i],-1,NULL,0,&image,sizeof(image),NULL);
        assert(!r.error && !strcmp(image.object,ids[i]) && !strcmp(image.path,paths[i])); close(r.fd);
        struct md_fs_anchor *reopened=NULL;
        r=call(fs,MD_FS_OPEN_OBJECT,ids[i],-1,NULL,O_RDONLY,NULL,0,&reopened);
        assert(!r.error && reopened && reopened->mount==origin[i]->mount);
        close(r.fd); md_fs_anchor_release(reopened);
    }
    assert(strcmp(ids[0],ids[1]));
    /* Neither the first opened alias nor cache insertion order determines policy. */
    for (unsigned i=0;i<2;++i) {
        struct md_fs_result r=call(fs,MD_FS_FSTAT,NULL,fds[i],NULL,0,NULL,0,NULL);
        assert(r.error==-ENOTSUP);
    }
    unsigned objects=fs->mounts->tree->objects;
    for (unsigned i=0;i<100;++i) {
        char id[33]; unsigned which=i%2;
        struct md_fs_result r=call(fs,MD_FS_OBJECT_ID,NULL,fds[which],origin[which],0,id,sizeof(id),NULL);
        assert(!r.error && !strcmp(id,ids[which]));
    }
    assert(fs->mounts->tree->objects==objects);
    assert(!unlink(path));
    for (unsigned i=0;i<2;++i) {
        struct md_fs_result r=call(fs,MD_FS_OPEN_OBJECT,ids[i],-1,NULL,O_RDONLY,NULL,0,NULL);
        assert(!r.error); char byte; assert(read(r.fd,&byte,1)==1 && byte=='v'); close(r.fd);
        r=call(fs,MD_FS_REOPEN,NULL,fds[i],origin[i],O_RDWR,NULL,0,NULL);
        if (i) assert(r.error==-EROFS);
        else { assert(!r.error); close(r.fd); }
        close(fds[i]); md_fs_anchor_release(origin[i]);
    }
}
static void root_aliases(struct md_filesystem *fs) {
    struct md_fs_anchor *origins[2]={0};
    const char *paths[]={"/rw","/ro"};
    for (unsigned i=0;i<2;++i) {
        struct md_fs_result r=call(fs,MD_FS_OPEN,paths[i],-1,NULL,O_PATH|O_DIRECTORY,NULL,0,&origins[i]);
        assert(!r.error && origins[i]); close(r.fd);
    }
    struct md_guest_domain *domain=md_domain_new(); assert(domain);
    struct md_identity identity=md_identity_new(0,0);
    fs->tasks=md_task_registry_create(); assert(fs->tasks);
    struct md_task_resources resources={.fs=domain,.mounts=fs->mounts};
    assert(!md_task_registry_publish(fs->tasks,123,123,&identity,&resources));
    for (unsigned i=0;i<2;++i) {
        assert(!md_domain_set_root(domain,&(struct md_fs_location){0}));
        struct md_fs_result r=call(fs,MD_FS_CHROOT,paths[i],-1,NULL,0,NULL,0,NULL);
        assert(!r.error);
        struct md_task_snapshot selected;
        assert(!md_task_registry_read(fs->tasks,123,123,&selected));
        assert(selected.root.mount==fs->mounts->tree->mounts[i].id);
        struct md_mount_namespace *copy=NULL;
        struct md_fs_location copied;
        assert(!md_fs_mounts_copy(fs,&selected.root,&copy,&copied));
        struct md_filesystem next={.store=fs->store,.mounts=copy};
        struct md_fs_location invalid;
        assert(md_fs_root_capture(&next,md_fs_anchor_fd(origins[i]),origins[i],&invalid)==-ESTALE);
        assert(!md_fs_root_select(&next,&copied));
        assert(next.native_root->mount==&copy->tree->mounts[i]);
        assert(md_view_readonly(next.native_root->mount)==(int)i);
        md_fs_mounts_close(&next); fs->store->root_id=NULL;
        md_task_snapshot_release(&selected);
        r=call(fs,MD_FS_OPEN,"/created",-1,NULL,O_WRONLY|O_CREAT,NULL,0,NULL);
        if (i) assert(r.error==-EROFS);
        else { assert(!r.error); close(r.fd); }
    }
    assert(!md_domain_set_root(domain,&(struct md_fs_location){0}));
    md_task_registry_destroy(fs->tasks); fs->tasks=NULL;
    md_identity_release(&identity); md_domain_release(domain);
    md_fs_anchor_release(origins[0]); md_fs_anchor_release(origins[1]);
}
void test_mount_alias(const char *base) {
    unsigned before=descriptors();
    char store[PATH_MAX], host[2][PATH_MAX], files[2][PATH_MAX];
    snprintf(store,sizeof(store),"%s/alias-store",base);
    for (unsigned i=0;i<2;++i) {
        snprintf(host[i],sizeof(host[i]),"%s/alias-source-%u",base,i); assert(!mkdir(host[i],0700));
        snprintf(files[i],sizeof(files[i]),"%s/value",host[i]);
    }
    struct md_inode_store *s; assert(!md_inode_store_open(store,1,&s));
    assert(!md_inode_mkdir(s,-1,"/rw",0700) && !md_inode_mkdir(s,-1,"/ro",0700));
    struct md_fs_attachment entries[]={{host[0],"/rw",0},{host[1],"/ro",1}};
    struct md_filesystem fs={.store=s}; assert(!md_fs_mounts_open(&fs,entries,2));
    /* Construct a duplicated bind source before exposing the view. Public
     * launch admission still rejects this until FD provenance is connected. */
    struct md_view_mount *rw=&fs.mounts->tree->mounts[0], *ro=&fs.mounts->tree->mounts[1];
    close(ro->source); ro->source=fcntl(rw->source,F_DUPFD_CLOEXEC,3); assert(ro->source>=0);
    ro->native=rw->native;
    captured_lifetime(&fs,host[0]);
    native_open_plans(&fs,host[0]);
    file_aliases(&fs,files[0],0); file_aliases(&fs,files[0],1); root_aliases(&fs);
    md_fs_mounts_close(&fs); md_inode_store_close(s);
    assert(descriptors()==before);
    puts("PASS per-mount native object IDs, readonly aliases, image identity and open-unlinked lifetime");
    puts("PASS ambiguous unannotated FDs fail explicitly; captured roots and copied trees retain exact mount policy");
    puts("PASS captured opens retain no view-lifetime object pins; unlinked origins and FD checks remain valid");
}
