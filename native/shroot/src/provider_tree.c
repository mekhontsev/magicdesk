#define _GNU_SOURCE
#include "provider_tree.h"
#include "temporary_directory.h"
#include "directory_record.h"
#include "user_namespace.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

struct directory_identity {
    dev_t device;
    ino_t inode;
    struct timespec modified,changed;
};
struct directory_checkpoint {
    char *path;
    struct directory_identity roots[2];
    off_t position,native;
    unsigned layer;
};
struct md_provider_tree {
    atomic_uint references;
    int upper,lower;
    char **opaque;
    unsigned opaque_count;
    const struct md_provider_tree_ops *ops;
    void *context;
    struct md_user_namespace *owner;
    struct md_mount_policy super_policy;
    /* Optional scan hints, not cursor ownership. Cold imports, arbitrary seeks
     * and eviction replay the ordinal from the shared OFD. No FD is retained. */
    struct directory_checkpoint directories[16];
    unsigned next_directory;
    pthread_mutex_t directory_lock;
};
static int fdpath(int fd,char *out,size_t size) {
    char proc[64]; snprintf(proc,sizeof(proc),"/proc/self/fd/%d",fd);
    ssize_t n=readlink(proc,out,size);
    if (n<0) return -errno;
    if ((size_t)n>=size) return -ENAMETOOLONG;
    out[n]=0; return 0;
}
static int below(const char *path,const char *root) {
    size_t length=strlen(root);
    return !strncmp(path,root,length) && (!path[length] || path[length]=='/');
}
struct md_provider_tree *md_provider_tree_new(int parent,int lower,const char *const *opaque,
        unsigned count,const struct md_provider_tree_ops *ops,void *context) {
    if (count>32 || (count && !opaque)) { errno=EINVAL; return NULL; }
    struct md_provider_tree *tree=calloc(1,sizeof(*tree));
    if (!tree) return NULL;
    int error=pthread_mutex_init(&tree->directory_lock,NULL);
    if (error) { free(tree); errno=error; return NULL; }
    atomic_init(&tree->references,1); tree->upper=tree->lower=-1;
    if (lower>=0) { tree->lower=fcntl(lower,F_DUPFD_CLOEXEC,3); if (tree->lower<0) goto failed; }
    tree->upper=md_temporary_directory_new(parent);
    if (tree->upper<0) { errno=-tree->upper; tree->upper=-1; goto failed; }
    tree->opaque=calloc(count ? count : 1,sizeof(*tree->opaque));
    if (!tree->opaque) goto failed;
    for (unsigned i=0;i<count;++i) {
        if (!opaque[i] || opaque[i][0]!='/' || strstr(opaque[i],"..")) { errno=EINVAL; goto failed; }
        tree->opaque[i]=strdup(opaque[i]); if (!tree->opaque[i]) goto failed;
        tree->opaque_count++;
    }
    tree->ops=ops; tree->context=ops && ops->retain ? ops->retain(context) : context;
    return tree;
failed: {
    int error=errno; md_provider_tree_release(tree); errno=error; return NULL;
}}
struct md_provider_tree *md_provider_tree_retain(struct md_provider_tree *tree) {
    if (tree) atomic_fetch_add_explicit(&tree->references,1,memory_order_relaxed);
    return tree;
}
struct md_provider_tree *md_provider_tree_import(int upper,int lower,const struct md_provider_tree_ops *ops) {
    return md_provider_tree_import_context(upper,lower,ops,NULL);
}
struct md_provider_tree *md_provider_tree_import_context(int upper,int lower,const struct md_provider_tree_ops *ops,void *context) {
    int error=md_temporary_directory_check(upper);
    if (error) { errno=-error; return NULL; }
    struct md_provider_tree *tree=calloc(1,sizeof(*tree));
    if (!tree) return NULL;
    error=pthread_mutex_init(&tree->directory_lock,NULL);
    if (error) { free(tree); errno=error; return NULL; }
    atomic_init(&tree->references,1); tree->upper=tree->lower=-1; tree->ops=ops;
    tree->context=ops && ops->retain ? ops->retain(context) : context;
    tree->upper=fcntl(upper,F_DUPFD_CLOEXEC,3);
    if (tree->upper>=0 && lower>=0) tree->lower=fcntl(lower,F_DUPFD_CLOEXEC,3);
    if (tree->upper<0 || (lower>=0 && tree->lower<0)) {
        error=errno; md_provider_tree_release(tree); errno=error; return NULL;
    }
    return tree;
}
int md_provider_tree_matches(const struct md_provider_tree *tree,const struct md_provider_tree_ops *ops) {
    return tree && tree->ops==ops;
}
void md_provider_tree_release(struct md_provider_tree *tree) {
    if (!tree || atomic_fetch_sub_explicit(&tree->references,1,memory_order_acq_rel)!=1) return;
    if (tree->ops && tree->ops->release) tree->ops->release(tree->context);
    md_user_namespace_release(tree->owner);
    md_mount_policy_close(&tree->super_policy);
    if (tree->lower>=0) close(tree->lower);
    if (tree->upper>=0) md_temporary_directory_release(tree->upper);
    for (unsigned i=0;i<tree->opaque_count;++i) free(tree->opaque[i]);
    for (unsigned i=0;i<sizeof(tree->directories)/sizeof(*tree->directories);++i) free(tree->directories[i].path);
    pthread_mutex_destroy(&tree->directory_lock);
    free(tree->opaque); free(tree);
}
int md_provider_tree_root(struct md_provider_tree *tree) { return tree->upper; }
void md_provider_tree_set_owner(struct md_provider_tree *tree,const struct md_user_namespace *owner) {
    struct md_user_namespace *previous=tree->owner;
    tree->owner=md_user_namespace_retain((struct md_user_namespace *)owner); md_user_namespace_release(previous);
}
struct md_user_namespace *md_provider_tree_owner(const struct md_provider_tree *tree) { return tree->owner; }
struct md_mount_policy *md_provider_tree_super_policy(struct md_provider_tree *tree) { return &tree->super_policy; }
unsigned md_provider_tree_super_flags(const struct md_provider_tree *tree) {
    return tree ? md_mount_policy_flags(&tree->super_policy) : 0;
}
void *md_provider_tree_context(struct md_provider_tree *tree,const struct md_provider_tree_ops *ops) {
    return tree && tree->ops==ops ? tree->context : NULL;
}
int md_provider_tree_private(struct md_provider_tree *tree,int fd) {
    if (fd<0) return 0;
    char path[PATH_MAX],root[PATH_MAX];
    int error=fdpath(fd,path,sizeof(path));
    if (!error) error=fdpath(tree->upper,root,sizeof(root));
    return error ? error : below(path,root);
}
unsigned long md_provider_tree_filesystem(struct md_provider_tree *tree) { return tree->ops ? tree->ops->filesystem : 0; }
int md_provider_tree_readonly(struct md_provider_tree *tree,int fd) {
    if (tree->ops && tree->ops->readonly_node) return tree->ops->readonly_node(tree->context,fd);
    return tree->ops && tree->ops->readonly ? 1 : md_provider_tree_private(tree,fd);
}
int md_provider_tree_path(struct md_provider_tree *tree,int fd,char *out,size_t size) {
    if (tree->ops && tree->ops->path) return tree->ops->path(tree->context,fd,out,size);
    char path[PATH_MAX],root[PATH_MAX]; int error=fdpath(fd,path,sizeof(path));
    if (error) return error;
    int roots[]={tree->upper,tree->lower};
    for (unsigned i=0;i<2;++i) {
        if (roots[i]<0) continue;
        error=fdpath(roots[i],root,sizeof(root)); if (error) return error;
        if (!below(path,root)) continue;
        const char *suffix=path+strlen(root); if (!*suffix) suffix="/";
        size_t length=strlen(suffix);
        if (length>=size) return -ENAMETOOLONG;
        memcpy(out,suffix,length+1); return 0;
    }
    return -EXDEV;
}
static int opaque(struct md_provider_tree *tree,const char *path) {
    for (unsigned i=0;i<tree->opaque_count;++i) if (below(path,tree->opaque[i])) return 1;
    return 0;
}
static const char *relative(const char *path) { return path[1] ? path+1 : "."; }
static int lower_visible(struct md_provider_tree *tree,const char *path) {
    return !tree->ops || !tree->ops->lower_visible || tree->ops->lower_visible(path);
}
static int internal(const char *directory,const char *name) {
    return !strcmp(directory,"/") && !strcmp(name,MD_TEMPORARY_DIRECTORY_MARKER);
}
int md_provider_tree_step(struct md_provider_tree *tree,int directory,const char *name) {
    if (tree->ops && tree->ops->lookup) return -EOPNOTSUPP;
    char path[PATH_MAX]; int error=md_provider_tree_path(tree,directory,path,sizeof(path));
    if (error) return error;
    if (internal(path,name)) return -ENOENT;
    if (!strcmp(name,"..")) {
        char *slash=strrchr(path,'/'); if (slash==path) path[1]=0; else *slash=0;
    } else if (strcmp(name,".")) {
        if (!*name || strchr(name,'/')) return -EINVAL;
        size_t length=strlen(path),n=strlen(name);
        if (length+n+2>sizeof(path)) return -ENAMETOOLONG;
        if (length>1) path[length++]='/';
        memcpy(path+length,name,n+1);
    }
    int fd=openat(tree->upper,relative(path),O_PATH|O_NOFOLLOW|O_CLOEXEC);
    if (fd>=0) return fd;
    if (errno!=ENOENT) return -errno;
    if (opaque(tree,path) || tree->lower<0 || !lower_visible(tree,path)) return -ENOENT;
    fd=openat(tree->lower,relative(path),O_PATH|O_NOFOLLOW|O_CLOEXEC);
    return fd<0 ? -errno : fd;
}
int md_provider_tree_lookup(struct md_provider_tree *tree,int directory,const char *name,
        const struct md_control_actor *actor,struct md_provider_lookup **pending) {
    if (tree->ops && tree->ops->lookup)
        return pending ? tree->ops->lookup(tree->context,directory,name,actor,pending) : -EOPNOTSUPP;
    return md_provider_tree_step(tree,directory,name);
}
static int directory_identity(int fd,struct directory_identity *out) {
    *out=(struct directory_identity){0};
    if (fd<0) return 1;
    struct stat st;
    if (fstat(fd,&st)) return 0;
    *out=(struct directory_identity){st.st_dev,st.st_ino,st.st_mtim,st.st_ctim};
    return 1;
}
static int same_directory(const struct directory_identity *a,const struct directory_identity *b) {
    return a->device==b->device && a->inode==b->inode
        && a->modified.tv_sec==b->modified.tv_sec && a->modified.tv_nsec==b->modified.tv_nsec
        && a->changed.tv_sec==b->changed.tv_sec && a->changed.tv_nsec==b->changed.tv_nsec;
}
ssize_t md_provider_tree_getdents(struct md_provider_tree *tree,int fd,void *out,size_t capacity) {
    char path[PATH_MAX]; int error=md_provider_tree_path(tree,fd,path,sizeof(path));
    if (error) return error;
    off_t position=lseek(fd,0,SEEK_CUR); if (position<0) return -errno;
    int upper=openat(tree->upper,relative(path),O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
    int lower=opaque(tree,path) || tree->lower<0 || !lower_visible(tree,path) ? -1
        : openat(tree->lower,relative(path),O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
    if (upper<0 && lower<0) return -errno;
    struct directory_checkpoint checkpoint={.position=position};
    int cache=directory_identity(upper,&checkpoint.roots[0]) && directory_identity(lower,&checkpoint.roots[1]);
    unsigned first=0;
    size_t used=0; off_t index=0,next=position,native=0;
    pthread_mutex_lock(&tree->directory_lock);
    for (unsigned i=0;cache && i<sizeof(tree->directories)/sizeof(*tree->directories);++i) {
        const struct directory_checkpoint *p=&tree->directories[i];
        if (!p->position || strcmp(p->path,path) || !same_directory(&p->roots[0],&checkpoint.roots[0])
                || !same_directory(&p->roots[1],&checkpoint.roots[1])) continue;
        if (position && position==p->position) { index=position; first=p->layer; native=p->native; }
        break;
    }
    pthread_mutex_unlock(&tree->directory_lock);
    int roots[]={upper,lower},stop=0;
    /* A hint must never turn a previously readable directory into an error. */
    if (native && lseek(roots[first],native,SEEK_SET)<0) { first=0; index=0; native=0; }
    for (unsigned i=first;i<2 && !stop;++i) {
        if (roots[i]<0) continue;
        int duplicate=fcntl(roots[i],F_DUPFD_CLOEXEC,3);
        DIR *dir=duplicate<0 ? NULL : fdopendir(duplicate);
        if (!dir) { if (duplicate>=0) close(duplicate); error=-errno; break; }
        off_t cursor=i==first ? native : 0;
        for (;;) {
            errno=0; struct dirent *e=readdir(dir);
            if (!e) { if (errno) error=-errno; break; }
            off_t before=cursor; cursor=e->d_off;
            if (internal(path,e->d_name)) continue;
            if (i && tree->ops && tree->ops->lower_visible
                    && strcmp(e->d_name,".") && strcmp(e->d_name,"..")) {
                char child[PATH_MAX];
                int length=snprintf(child,sizeof(child),"%s%s%s",path,strcmp(path,"/") ? "/" : "",e->d_name);
                if (length<0 || (size_t)length>=sizeof(child)) { error=-ENAMETOOLONG; break; }
                if (!lower_visible(tree,child)) continue;
            }
            struct stat st;
            if (i && upper>=0 && !fstatat(upper,e->d_name,&st,AT_SYMLINK_NOFOLLOW)) continue;
            if (index++<position) continue;
            size_t size=md_directory_record_write((char *)out+used,capacity-used,
                e->d_name,strlen(e->d_name),e->d_ino,index,e->d_type);
            if (!size) {
                if (!used) error=-EINVAL;
                checkpoint.position=next; checkpoint.layer=i; checkpoint.native=before;
                stop=1; break;
            }
            struct md_directory_entry *entry=(void *)((char *)out+used);
            int node=tree->ops && tree->ops->control
                ? openat(roots[i],e->d_name,O_PATH|O_NOFOLLOW|O_CLOEXEC) : -1;
            if (node>=0) {
                struct md_control_file control={0};
                int managed=md_provider_tree_control(tree,node,&control);
                if (managed>0) {
                    if (!control.ops->stat(control.context,&st)) {
                        entry->type=S_ISCHR(st.st_mode) ? DT_CHR : entry->type; entry->inode=st.st_ino;
                    }
                    md_control_file_release(control);
                }
                close(node);
            }
            used+=size; next=index;
        }
        closedir(dir);
    }
    if (upper>=0) close(upper);
    if (lower>=0) close(lower);
    if (!error && lseek(fd,next,SEEK_SET)<0) error=-errno;
    if (!error && cache && stop) {
        pthread_mutex_lock(&tree->directory_lock);
        unsigned slot=sizeof(tree->directories)/sizeof(*tree->directories);
        for (unsigned i=0;i<slot;++i)
            if (tree->directories[i].path && !strcmp(tree->directories[i].path,path)
                    && same_directory(&tree->directories[i].roots[0],&checkpoint.roots[0])
                    && same_directory(&tree->directories[i].roots[1],&checkpoint.roots[1])) { slot=i; break; }
        if (slot<sizeof(tree->directories)/sizeof(*tree->directories)) {
            checkpoint.path=tree->directories[slot].path;
        } else if ((checkpoint.path=strdup(path))) {
            slot=tree->next_directory++%(sizeof(tree->directories)/sizeof(*tree->directories));
            free(tree->directories[slot].path);
        }
        if (checkpoint.path) tree->directories[slot]=checkpoint;
        pthread_mutex_unlock(&tree->directory_lock);
    }
    return error ? error : (ssize_t)used;
}
int md_provider_tree_control(struct md_provider_tree *tree,int fd,struct md_control_file *out) {
    return tree->ops && tree->ops->control && fd>=0 ? tree->ops->control(tree->context,fd,out) : 0;
}
