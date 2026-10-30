#define _GNU_SOURCE
#include "device_tree.h"
#include "provider_tree.h"
#include "fuse_device.h"
#include "network_tun.h"
#include "render_device.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

struct node { struct node *next; struct stat identity; struct md_control_file file; };
struct registry { atomic_uint references; pthread_mutex_t lock; struct node *nodes; int local; };
static void *retain(void *context) {
    struct registry *r=context; atomic_fetch_add_explicit(&r->references,1,memory_order_relaxed); return r;
}
static void release(void *context) {
    struct registry *r=context;
    if (atomic_fetch_sub_explicit(&r->references,1,memory_order_acq_rel)!=1) return;
    while (r->nodes) {
        struct node *n=r->nodes; r->nodes=n->next; md_control_file_release(n->file); free(n);
    }
    pthread_mutex_destroy(&r->lock); free(r);
}
static struct node **find(struct registry *r,const struct stat *st) {
    struct node **p=&r->nodes;
    while (*p && ((*p)->identity.st_dev!=st->st_dev || (*p)->identity.st_ino!=st->st_ino)) p=&(*p)->next;
    return p;
}
static int control(void *context,int fd,struct md_control_file *out) {
    struct registry *r=context; struct stat st;
    if (fstat(fd,&st)) return -errno;
    pthread_mutex_lock(&r->lock);
    struct node *n=*find(r,&st);
    if (n) *out=md_control_file_retain(n->file);
    pthread_mutex_unlock(&r->lock); return n ? 1 : 0;
}
static const struct md_provider_tree_ops operations={.retain=retain,.release=release,.control=control,.filesystem=TMPFS_MAGIC};
static struct registry *registry_new(int local) {
    struct registry *r=calloc(1,sizeof(*r)); if (!r) return NULL;
    atomic_init(&r->references,1);
    int error=pthread_mutex_init(&r->lock,NULL);
    if (error) { free(r); errno=error; return NULL; }
    r->local=local; return r;
}
struct md_provider_tree *md_device_tree_new(int parent,int hide_native_input) {
    struct registry *r=registry_new(hide_native_input); if (!r) return NULL;
    int error;
    int lower=open("/dev",O_PATH|O_DIRECTORY|O_CLOEXEC);
    if (lower<0) { error=errno; release(r); errno=error; return NULL; }
    const char *opaque[]={"/input"};
    struct md_provider_tree *tree=md_provider_tree_new(parent,lower,opaque,hide_native_input ? 1 : 0,&operations,r);
    error=errno; close(lower); release(r);
    if (!tree) { errno=error; return NULL; }
    const char *dirs[]={"shm","pts","net","input"};
    unsigned count=hide_native_input ? 4 : 3;
    for (unsigned i=0;i<count;++i) if (mkdirat(md_provider_tree_root(tree),dirs[i],0755)) {
        error=errno; md_provider_tree_release(tree); errno=error; return NULL;
    }
    return tree;
}
int md_device_tree_portable(const struct md_provider_tree *tree) {
    struct registry *r=md_provider_tree_context((struct md_provider_tree *)tree,&operations);
    if (!r) return 0;
    pthread_mutex_lock(&r->lock);
    int portable=!r->local && r->nodes;
    pthread_mutex_unlock(&r->lock); return portable;
}
static int import_node(struct registry *r,int upper,const char *path,unsigned render,int required) {
    struct stat identity;
    if(fstatat(upper,path,&identity,AT_SYMLINK_NOFOLLOW)) return !required && errno==ENOENT ? 0 : -errno;
    if(!S_ISREG(identity.st_mode)) return -EPROTO;
    struct node *n=calloc(1,sizeof(*n)); if(!n) return -ENOMEM;
    n->identity=identity;
    int error=render ? md_render_device_restore(render,&n->file)
        : !strcmp(path,"fuse") ? md_fuse_device_node(&n->file) : md_tun_device_node(&n->file);
    if(error) { free(n); return error; }
    n->next=r->nodes; r->nodes=n; return 0;
}
struct md_provider_tree *md_device_tree_import(int upper) {
    struct registry *r=registry_new(0); if (!r) return NULL;
    int error=import_node(r,upper,"fuse",0,1);
    const char *directories[]={"net","dri"};
    for(unsigned d=0;!error && d<2;++d) {
        struct stat directory;
        if(fstatat(upper,directories[d],&directory,AT_SYMLINK_NOFOLLOW)) {
            if(errno!=ENOENT) error=-errno;
            continue;
        }
        if(!S_ISDIR(directory.st_mode)) { error=-EPROTO; break; }
        if(!d) error=import_node(r,upper,"net/tun",0,0);
        else for(unsigned minor=128;!error && minor<=191;++minor) {
            char path[32]; snprintf(path,sizeof(path),"dri/renderD%u",minor);
            error=import_node(r,upper,path,minor,0);
        }
    }
    int lower=error ? -1 : open("/dev",O_PATH|O_DIRECTORY|O_CLOEXEC);
    if (!error && lower<0) error=-errno;
    struct md_provider_tree *tree=error ? NULL : md_provider_tree_import_context(upper,lower,&operations,r);
    if (!error && !tree) error=-errno;
    if (lower>=0) close(lower);
    release(r); if (error) errno=-error; return tree;
}
int md_device_tree_publish(struct md_provider_tree *tree,const char *path,struct md_control_file file) {
    struct registry *r=md_provider_tree_context(tree,&operations);
    if (!r || !file.ops || !path || !*path || *path=='/') return -EINVAL;
    struct node *n=calloc(1,sizeof(*n)); if (!n) return -ENOMEM;
    n->file=md_control_file_retain(file);
    /* Keep the lock across carrier creation: lookup cannot observe an ordinary
     * file between publication of its name and its typed authority. */
    pthread_mutex_lock(&r->lock);
    int fd=openat(md_provider_tree_root(tree),path,O_CREAT|O_EXCL|O_RDONLY|O_CLOEXEC|O_NOFOLLOW,0600);
    int error=fd<0 ? -errno : fstat(fd,&n->identity) ? -errno : 0;
    if (!error) {
        char render[32]; snprintf(render,sizeof(render),"dri/renderD%u",md_render_device_minor(file));
        if (!((!strcmp(path,"fuse") && md_fuse_device_is_node(file))
                || (!strcmp(path,"net/tun") && md_tun_device_is_node(file))
                || (!strcmp(path,render) && md_render_device_is_node(file)))) r->local=1;
        n->next=r->nodes; r->nodes=n;
    }
    if (fd>=0) {
        close(fd);
        if (error) unlinkat(md_provider_tree_root(tree),path,0);
    }
    pthread_mutex_unlock(&r->lock);
    if (error) { md_control_file_release(n->file); free(n); }
    return error;
}
int md_device_tree_remove(struct md_provider_tree *tree,const char *path) {
    struct registry *r=md_provider_tree_context(tree,&operations);
    if (!r || !path || !*path || *path=='/') return -EINVAL;
    pthread_mutex_lock(&r->lock);
    struct stat st; struct node *removed=NULL;
    int error=fstatat(md_provider_tree_root(tree),path,&st,AT_SYMLINK_NOFOLLOW) ? -errno : 0;
    if (!error) {
        struct node **p=find(r,&st);
        if (!*p) error=-EINVAL;
        else if (unlinkat(md_provider_tree_root(tree),path,0)) error=-errno;
        else { removed=*p; *p=removed->next; }
    }
    pthread_mutex_unlock(&r->lock);
    if (removed) { md_control_file_release(removed->file); free(removed); }
    return error==-ENOENT ? 0 : error ? error : 1;
}
