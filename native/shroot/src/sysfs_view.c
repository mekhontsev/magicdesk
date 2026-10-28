#define _GNU_SOURCE
#include "sysfs_view.h"
#include "provider_tree.h"
#include "guest_identity.h"
#include "file_permissions.h"
#include "fs_anchor.h"
#include "temporary_directory.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/if.h>
#include <linux/magic.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

enum attribute { DIRECTORY, LINK, IFINDEX, IFLINK, MTU, TYPE, ADDRESS, BROADCAST, FLAGS, OPERSTATE, CARRIER, RETIRED, BRIDGE, STP_STATE };
static const struct node { const char *path; enum attribute attribute; const char *target; } nodes[]={
    {".",DIRECTORY,NULL}, {"block",DIRECTORY,NULL}, {"bus",DIRECTORY,NULL},
    {"dev",DIRECTORY,NULL}, {"dev/block",DIRECTORY,NULL}, {"dev/char",DIRECTORY,NULL},
    {"class",DIRECTORY,NULL}, {"class/net",DIRECTORY,NULL},
    {"devices",DIRECTORY,NULL}, {"devices/virtual",DIRECTORY,NULL},
    {"devices/virtual/net",DIRECTORY,NULL},
    {"firmware",DIRECTORY,NULL},
    {"fs",DIRECTORY,NULL}, {"fs/cgroup",DIRECTORY,NULL}, {"kernel",DIRECTORY,NULL},
};
static const struct node attributes[]={
    {".",DIRECTORY,NULL}, {"subsystem",LINK,"../../../../class/net"},
    {"ifindex",IFINDEX,NULL}, {"iflink",IFLINK,NULL}, {"mtu",MTU,NULL}, {"type",TYPE,NULL},
    {"address",ADDRESS,NULL}, {"broadcast",BROADCAST,NULL}, {"flags",FLAGS,NULL},
    {"operstate",OPERSTATE,NULL}, {"carrier",CARRIER,NULL},
    {"bridge",BRIDGE,NULL}, {"bridge/stp_state",STP_STATE,NULL},
};
enum { NODE_COUNT=sizeof(nodes)/sizeof(*nodes), ATTRIBUTE_COUNT=sizeof(attributes)/sizeof(*attributes) };
struct interface {
    unsigned index;
    uint64_t lifetime;
    char name[16];
    int staged,ready;
    struct md_sysfs_node nodes[ATTRIBUTE_COUNT+1];
};
struct md_sysfs {
    atomic_uint refs;
    int root,net,classes,staging;
    struct md_provider_tree *tree;
    struct md_network_namespace *network;
    struct md_sysfs_node nodes[NODE_COUNT];
    struct interface **links;
    unsigned count;
    uint64_t generation;
    uid_t uid;
    gid_t gid;
};
struct opened {
    atomic_uint refs;
    struct md_sysfs *view;
    struct md_fs_anchor *origin;
    struct md_sysfs_node node;
};
int md_sysfs_host_visible(const char *path) {
    const char *roots[]={"/devices/system/cpu","/devices/system/node"};
    if (!strcmp(path,"/")) return 1;
    size_t size=strlen(path);
    for (unsigned i=0;i<sizeof(roots)/sizeof(*roots);++i) {
        size_t length=strlen(roots[i]);
        if (size<=length && !strncmp(path,roots[i],size)
                && (size==length || roots[i][size]=='/')) return 1;
        if (size>length && !strncmp(path,roots[i],length) && path[length]=='/') return 1;
    }
    return 0;
}
static const struct md_provider_tree_ops tree_ops={.filesystem=SYSFS_MAGIC,
    .lower_visible=md_sysfs_host_visible,.readonly=1};
struct md_sysfs *md_sysfs_retain(struct md_sysfs *v) {
    if (v) atomic_fetch_add_explicit(&v->refs,1,memory_order_relaxed);
    return v;
}
void md_sysfs_release(struct md_sysfs *v) {
    if (!v || atomic_fetch_sub_explicit(&v->refs,1,memory_order_acq_rel)!=1) return;
    if (v->net>=0) close(v->net);
    if (v->classes>=0) close(v->classes);
    if (v->staging>=0) md_temporary_directory_release(v->staging);
    for (unsigned i=0;i<v->count;++i) free(v->links[i]);
    free(v->links);
    md_provider_tree_release(v->tree);
    md_network_namespace_release(v->network); free(v);
}
static void metadata(struct md_sysfs *v,struct md_sysfs_node *out,unsigned attribute) {
    struct stat *st=&out->stat;
    out->attribute=attribute;
    st->st_uid=v->uid; st->st_gid=v->gid;
    int directory=attribute==DIRECTORY || attribute==BRIDGE;
    st->st_mode=directory ? S_IFDIR|0755 : attribute==LINK ? S_IFLNK|0777 : S_IFREG|0444;
    if (attribute!=LINK) st->st_size=directory ? 0 : 4096;
    st->st_blocks=0; st->st_blksize=4096;
}
static int materialize(struct md_sysfs *v,int directory,const struct node *spec,struct md_sysfs_node *out) {
    if (spec->attribute==DIRECTORY || spec->attribute==BRIDGE) {
        if (strcmp(spec->path,".") && mkdirat(directory,spec->path,0700) && errno!=EEXIST) return -errno;
    } else if (spec->attribute==LINK) {
        if (symlinkat(spec->target,directory,spec->path) && errno!=EEXIST) return -errno;
    } else {
        int fd=openat(directory,spec->path,O_WRONLY|O_CREAT|O_CLOEXEC|O_NOFOLLOW,0600);
        if (fd<0) return -errno;
        close(fd);
    }
    if (fstatat(directory,spec->path,&out->stat,AT_SYMLINK_NOFOLLOW)) return -errno;
    metadata(v,out,spec->attribute); return 0;
}
static void staged_name(const struct interface *link,char name[32]) {
    snprintf(name,32,"%u",link->index);
}
static int remove_interface(struct md_sysfs *v,struct interface *link) {
    char name[32]; staged_name(link,name);
    int parent=link->staged ? v->staging : v->net;
    const char *path=link->staged ? name : link->name;
    if (*link->name && unlinkat(v->classes,link->name,0) && errno!=ENOENT) return -errno;
    int directory=openat(parent,path,O_DIRECTORY|O_RDONLY|O_CLOEXEC);
    if (directory<0) return errno==ENOENT ? 0 : -errno;
    int error=0;
    for (unsigned i=ATTRIBUTE_COUNT;i>1;--i)
        if (unlinkat(directory,attributes[i-1].path,attributes[i-1].attribute==BRIDGE ? AT_REMOVEDIR : 0)
                && errno!=ENOENT) { error=-errno; break; }
    close(directory);
    if (!error && unlinkat(parent,path,AT_REMOVEDIR) && errno!=ENOENT) error=-errno;
    return error;
}
static int same_interface(const struct interface *a,const struct md_network_link *b) {
    return a->index==b->index && a->lifetime==b->lifetime;
}
int md_sysfs_refresh(struct md_sysfs *v) {
    struct md_network_link snapshot[256];
    uint64_t generation=v->generation;
    int count=md_network_link_snapshot(v->network,&generation,snapshot,256);
    if (count<=0) return count;
    /* Only topology revisions materialize dentries. Open controls own values,
     * not array slots; retiring a link needs no historical tombstone inventory. */
    for (unsigned i=0;i<v->count;) {
        struct interface *link=v->links[i];
        int found=0;
        for (int j=0;j<count;++j) if (same_interface(link,&snapshot[j])) { found=1; break; }
        if (found) { ++i; continue; }
        int error=remove_interface(v,link);
        if (error) return error;
        free(link); v->links[i]=v->links[--v->count];
    }
    /* Stage all renames before publishing any, including name swaps between
     * snapshots. The sibling staging directory is outside the guest tree. */
    for (unsigned i=0;i<v->count;++i) {
        struct interface *link=v->links[i];
        for (int j=0;j<count;++j) {
            if (!same_interface(link,&snapshot[j]) || !strcmp(link->name,snapshot[j].name)) continue;
            char name[32]; staged_name(link,name);
            if (*link->name && unlinkat(v->classes,link->name,0) && errno!=ENOENT) return -errno;
            if (!link->staged) {
                if (renameat(v->net,link->name,v->staging,name)) return -errno;
                link->staged=1;
                link->name[0]=0;
            }
        }
    }
    for (int j=0;j<count;++j) {
        const struct md_network_link *wanted=&snapshot[j];
        struct interface *link=NULL;
        for (unsigned i=0;i<v->count;++i)
            if (same_interface(v->links[i],wanted)) { link=v->links[i]; break; }
        if (!link) {
            void *array=realloc(v->links,(v->count+1)*sizeof(*v->links));
            if (!array) return -ENOMEM;
            v->links=array;
            link=calloc(1,sizeof(*link)); if (!link) return -ENOMEM;
            link->index=wanted->index; link->lifetime=wanted->lifetime; link->staged=1;
            v->links[v->count++]=link;
        }
        char name[32]; staged_name(link,name);
        if (!link->ready) {
            if (mkdirat(v->staging,name,0700) && errno!=EEXIST) return -errno;
            int directory=openat(v->staging,name,O_DIRECTORY|O_RDONLY|O_CLOEXEC);
            if (directory<0) return -errno;
            int error=0;
            for (unsigned i=0;i<ATTRIBUTE_COUNT && !error;++i) {
                if ((attributes[i].attribute==BRIDGE || attributes[i].attribute==STP_STATE)
                        && wanted->kind!=MD_LINK_BRIDGE) continue;
                error=materialize(v,directory,&attributes[i],&link->nodes[i]);
                link->nodes[i].interface=link->index; link->nodes[i].lifetime=link->lifetime;
            }
            close(directory);
            if (error) return error;
            link->ready=1;
        }
        if (link->staged) {
            if (renameat(v->staging,name,v->net,wanted->name)) return -errno;
            link->staged=0;
            memcpy(link->name,wanted->name,sizeof(link->name));
        }
        char target[64]; snprintf(target,sizeof(target),"../../devices/virtual/net/%s",link->name);
        struct node alias={link->name,LINK,target};
        int error=materialize(v,v->classes,&alias,&link->nodes[ATTRIBUTE_COUNT]);
        if (error) return error;
    }
    v->generation=generation; return 0;
}
struct md_sysfs *md_sysfs_new(int parent,struct md_network_namespace *network,const struct md_identity *id) {
    struct md_network_link link;
    int r=md_network_link_info(network,1,&link);
    if (r) { errno=-r; return NULL; }
    struct md_sysfs *v=calloc(1,sizeof(*v));
    if (!v) return NULL;
    atomic_init(&v->refs,1); v->root=v->net=v->classes=v->staging=-1;
    v->uid=md_user_namespace_into(id->user_namespace,0,0);
    v->gid=md_user_namespace_into(id->user_namespace,1,0);
    v->network=md_network_namespace_retain(network);
    int lower=open("/sys",O_PATH|O_DIRECTORY|O_CLOEXEC);
    v->tree=md_provider_tree_new(parent,lower,NULL,0,&tree_ops,NULL);
    int saved=errno; if (lower>=0) close(lower); errno=saved;
    if (!v->tree) goto failed;
    md_provider_tree_set_owner(v->tree,md_network_namespace_owner(network));
    v->root=md_provider_tree_root(v->tree);
    for (unsigned i=0;i<NODE_COUNT;++i)
        if ((r=materialize(v,v->root,&nodes[i],&v->nodes[i]))) { errno=-r; goto failed; }
    v->staging=md_temporary_directory_new(parent);
    if (v->staging<0) { errno=-v->staging; goto failed; }
    v->net=openat(v->root,"devices/virtual/net",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    v->classes=openat(v->root,"class/net",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    if (v->net<0 || v->classes<0) goto failed;
    if ((r=md_sysfs_refresh(v))) { errno=-r; goto failed; }
    return v;
failed:;
    int error=errno; md_sysfs_release(v); errno=error; return NULL;
}
int md_sysfs_root(const struct md_sysfs *v) { return v->root; }
struct md_provider_tree *md_sysfs_provider(const struct md_sysfs *v) { return v->tree; }
int md_sysfs_node(struct md_sysfs *v,int fd,struct md_sysfs_node *out) {
    if (fd<0) return -ENOENT;
    int error=md_sysfs_refresh(v);
    if (error) return error;
    struct stat st;
    if (fstat(fd,&st)) return -errno;
    if (st.st_dev!=v->nodes[0].stat.st_dev) return -EXDEV;
    for (unsigned i=0;i<NODE_COUNT;++i)
        if (st.st_ino==v->nodes[i].stat.st_ino) { *out=v->nodes[i]; return 0; }
    for (unsigned i=0;i<v->count;++i) for (unsigned j=0;j<=ATTRIBUTE_COUNT;++j)
        if (st.st_ino==v->links[i]->nodes[j].stat.st_ino) { *out=v->links[i]->nodes[j]; return 0; }
    if (st.st_nlink) return -EXDEV;
    int owned=md_provider_tree_private(v->tree,fd);
    if (owned<=0) return owned ? owned : -EXDEV;
    *out=(struct md_sysfs_node){.stat=st};
    metadata(v,out,S_ISDIR(st.st_mode) ? DIRECTORY : S_ISLNK(st.st_mode) ? LINK : RETIRED);
    return 0;
}
static void *retain_open(void *data) {
    struct opened *o=data; atomic_fetch_add_explicit(&o->refs,1,memory_order_relaxed); return o;
}
static void release_open(void *data) {
    struct opened *o=data;
    if (atomic_fetch_sub_explicit(&o->refs,1,memory_order_acq_rel)!=1) return;
    md_fs_anchor_release(o->origin); md_sysfs_release(o->view); free(o);
}
static int stat_open(void *data,struct stat *st) {
    struct opened *o=data; *st=o->node.stat; return 0;
}
static long read_open(void *data,const struct md_control_actor *actor,void *out,size_t size,int64_t offset) {
    (void)actor;
    struct opened *o=data; struct md_network_link info;
    if (offset<0) return -EINVAL;
    int r=md_network_link_info(o->view->network,o->node.interface,&info);
    if (r) return r;
    if (info.lifetime!=o->node.lifetime) return -ENODEV;
    char text[64]; int length;
    switch (o->node.attribute) {
    case IFINDEX: length=snprintf(text,sizeof(text),"%u\n",info.index); break;
    case IFLINK: length=snprintf(text,sizeof(text),"%u\n",info.peer ? info.peer : info.index); break;
    case MTU: length=snprintf(text,sizeof(text),"%u\n",info.mtu); break;
    case TYPE: length=snprintf(text,sizeof(text),"%u\n",info.type); break;
    case FLAGS: length=snprintf(text,sizeof(text),"0x%x\n",info.flags&~(IFF_RUNNING|IFF_LOWER_UP|IFF_DORMANT)); break;
    case ADDRESS: case BROADCAST:
        if (o->node.attribute==BROADCAST && !(info.flags&IFF_LOOPBACK)) memset(info.address,255,sizeof(info.address));
        length=snprintf(text,sizeof(text),"%02x:%02x:%02x:%02x:%02x:%02x\n",info.address[0],info.address[1],
            info.address[2],info.address[3],info.address[4],info.address[5]); break;
    case OPERSTATE:
        length=snprintf(text,sizeof(text),"%s\n",info.state==IF_OPER_UP ? "up"
            : info.state==IF_OPER_LOWERLAYERDOWN ? "lowerlayerdown" : info.state==IF_OPER_DOWN ? "down" : "unknown"); break;
    case CARRIER:
        if (!(info.flags&IFF_UP)) return -EINVAL;
        length=snprintf(text,sizeof(text),"%u\n",!!(info.flags&IFF_LOWER_UP)); break;
    case STP_STATE:
        /* Bridges forward without STP; enabling it is not accepted by netlink. */
        length=snprintf(text,sizeof(text),"0\n"); break;
    default: return -EINVAL;
    }
    if ((uint64_t)offset>=(unsigned)length) return 0;
    size_t count=(size_t)length-(size_t)offset;
    if (count>size) count=size;
    memcpy(out,text+offset,count); return (long)count;
}
static long write_open(void *data,const struct md_control_actor *actor,const void *in,size_t size,int64_t offset) {
    (void)offset;
    (void)data; (void)actor; (void)in; (void)size; return -EOPNOTSUPP;
}
static int access_open(void *data,const struct md_identity *id,unsigned mode) {
    struct opened *o=data;
    return md_file_permissions(&o->node.stat,mode,id);
}
static const struct md_control_file_ops ops={.retain=retain_open,.release=release_open,
    .stat=stat_open,.read=read_open,.write=write_open,.access=access_open,.filesystem=SYSFS_MAGIC};
int md_sysfs_node_open(struct md_sysfs *v,const struct md_sysfs_node *node,int flags,const struct md_identity *id,
        struct md_fs_anchor *origin,const struct md_control_file_ops **out,void **context) {
    const struct stat *st=&node->stat;
    if ((flags&O_DIRECTORY) && !S_ISDIR(st->st_mode)) return -ENOTDIR;
    if (!(flags&O_PATH)) {
        if (S_ISLNK(st->st_mode)) return -ELOOP;
        if (flags&(O_ACCMODE|O_TRUNC)) return S_ISDIR(st->st_mode) ? -EISDIR : -EOPNOTSUPP;
        int r=md_file_permissions(st,R_OK,id);
        if (r) return r;
    }
    if (!S_ISREG(st->st_mode)) return 0;
    struct opened *o=calloc(1,sizeof(*o));
    if (!o) return -ENOMEM;
    atomic_init(&o->refs,1); o->view=md_sysfs_retain(v); o->node=*node;
    o->origin=md_fs_anchor_retain(origin);
    *out=&ops; *context=o; return 0;
}
