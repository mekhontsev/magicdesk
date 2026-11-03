#define _GNU_SOURCE
#include "fs_context.h"
#include "fs_control.h"
#include "fs_mount_internal.h"
#include "fs_storage.h"
#include "inode_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <linux/mount.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

enum { MOUNTED_INODE = UINT_MAX };
struct filesystem_context {
    atomic_uint refs;
    struct md_identity opener;
    struct md_pid_identity *pid;
    struct md_cgroup_member *cgroup;
    struct md_cgroup_namespace *cgroup_namespace;
    struct md_network_namespace *network;
    struct md_ipc_namespace *ipc;
    struct md_mount_source source;
    struct stat stat;
    unsigned type,flags,flags_mask;
    enum { PARAMETERS, CREATED, MOUNTED, FAILED } phase;
    size_t size;
    char options[PATH_MAX];
};
static void *retain(void *data) {
    struct filesystem_context *c=data;
    atomic_fetch_add_explicit(&c->refs,1,memory_order_relaxed); return c;
}
static void release(void *data) {
    struct filesystem_context *c=data;
    if (atomic_fetch_sub_explicit(&c->refs,1,memory_order_acq_rel)!=1) return;
    md_mount_source_close(&c->source);
    md_identity_release(&c->opener);
    md_pid_identity_release(c->pid); md_cgroup_member_release(c->cgroup);
    md_cgroup_namespace_release(c->cgroup_namespace);
    md_network_namespace_release(c->network); md_ipc_namespace_release(c->ipc); free(c);
}
static int stat_context(void *data,struct stat *st) {
    *st=((struct filesystem_context *)data)->stat; return 0;
}
static long read_context(void *data,const struct md_control_actor *actor,void *out,size_t size,int64_t offset) {
    (void)data; (void)actor; (void)out; (void)size; (void)offset; return -ENODATA;
}
static long write_context(void *data,const struct md_control_actor *actor,const void *in,size_t size,int64_t offset) {
    (void)offset;
    (void)data; (void)actor; (void)in; (void)size; return -EINVAL;
}
static int access_context(void *data,const struct md_identity *id,unsigned mode) {
    (void)data; (void)id; return mode&X_OK ? -EACCES : 0;
}
static const struct md_control_file_ops context_ops={.retain=retain,.release=release,
    .stat=stat_context,.read=read_context,.write=write_context,.access=access_context,
    .filesystem=ANON_INODE_FS_MAGIC,.nonseekable=1};
static unsigned filesystem_type(const char *name) {
    static const struct { const char *name; unsigned type; } types[]={
        {"tmpfs",MD_FS_MOUNT_TMPFS},{"ramfs",MD_FS_MOUNT_RAMFS},{"overlay",MD_FS_MOUNT_OVERLAY},{"proc",MD_FS_MOUNT_PROC},
        {"devpts",MD_FS_MOUNT_DEVPTS},{"sysfs",MD_FS_MOUNT_SYSFS},
        {"mqueue",MD_FS_MOUNT_MQUEUE},
        {"binfmt_misc",MD_FS_MOUNT_BINFMT},
        {"cgroup",MD_FS_MOUNT_CGROUP},{"cgroup2",MD_FS_MOUNT_CGROUP2}};
    for (unsigned i=0;i<sizeof(types)/sizeof(*types);++i) if (!strcmp(name,types[i].name)) return types[i].type;
    return 0;
}
static int capable(struct md_filesystem *fs) {
    return fs->mounts && fs->store->identity && md_identity_capable_in(fs->store->identity,
        md_mount_namespace_owner(fs->mounts),CAP_SYS_ADMIN);
}
static int publish_context(struct filesystem_context *c,const struct md_fs_request *q,
        struct md_fs_result *result,const struct md_fs_output *out) {
    int fd=memfd_create("fscontext",MFD_CLOEXEC|MFD_ALLOW_SEALING);
    int r=fd<0 ? -errno : fstat(fd,&c->stat) ? -errno : 0;
    if (!r && fcntl(fd,F_ADD_SEALS,F_SEAL_SEAL|F_SEAL_WRITE|F_SEAL_GROW|F_SEAL_SHRINK)) r=-errno;
    c->stat.st_mode=S_IFREG|0600; c->stat.st_uid=c->opener.uid.fs; c->stat.st_gid=c->opener.gid.fs;
    if (r) { if (fd>=0) close(fd); }
    else r=md_fs_control_publish(fd,(struct md_control_file){&context_ops,c},NULL,
        &c->opener,(int)q->flags,result,out);
    return r;
}
static int open_context(struct md_filesystem *fs,const struct md_fs_request *q,
        struct md_fs_result *result,const struct md_fs_output *out) {
    if (!capable(fs)) return -EPERM;
    unsigned type=filesystem_type(q->path[0]);
    if (!type) return -ENODEV;
    if (type==MD_FS_MOUNT_SYSFS && (!fs->actor || !fs->actor->network)) return -EOPNOTSUPP;
    if (type==MD_FS_MOUNT_SYSFS && !md_identity_capable_in(fs->store->identity,
            md_network_namespace_owner(fs->actor->network),CAP_SYS_ADMIN)) return -EPERM;
    if (type==MD_FS_MOUNT_MQUEUE && (!fs->actor || !fs->actor->ipc)) return -EOPNOTSUPP;
    if (type==MD_FS_MOUNT_MQUEUE && !md_identity_capable_in(fs->store->identity,
            md_ipc_namespace_owner(fs->actor->ipc),CAP_SYS_ADMIN)) return -EPERM;
    if (!out || !out->control) return -EOPNOTSUPP;
    struct filesystem_context *c=calloc(1,sizeof(*c));
    if (!c) return -ENOMEM;
    atomic_init(&c->refs,1); c->type=type; c->source.fd=-1;
    c->opener=md_identity_copy(fs->store->identity);
    c->pid=md_pid_identity_retain(fs->actor ? fs->actor->pid : NULL);
    c->cgroup=md_cgroup_member_retain(fs->actor ? fs->actor->cgroup : NULL);
    c->cgroup_namespace=md_cgroup_namespace_retain(fs->actor ? fs->actor->cgroup_namespace : NULL);
    c->network=md_network_namespace_retain(fs->actor ? fs->actor->network : NULL);
    c->ipc=md_ipc_namespace_retain(fs->actor ? fs->actor->ipc : NULL);
    int r=publish_context(c,q,result,out);
    release(c);
    return r;
}
static unsigned mounted_type(const struct md_view_mount *mount) {
    if (mount && mount->devpts) return MD_FS_MOUNT_DEVPTS;
    if (md_view_inode(mount)) {
        enum md_filesystem_kind kind=mount && mount->storage ? mount->storage->filesystem_kind : MD_FILESYSTEM_INODE;
        return kind==MD_FILESYSTEM_TMPFS ? MD_FS_MOUNT_TMPFS : kind==MD_FILESYSTEM_RAMFS ? MD_FS_MOUNT_RAMFS : MOUNTED_INODE;
    }
    unsigned long magic=mount && mount->provider ? md_provider_tree_filesystem(mount->provider) : 0;
    return magic==SYSFS_MAGIC ? MD_FS_MOUNT_SYSFS : magic==PROC_SUPER_MAGIC ? MD_FS_MOUNT_PROC
        : magic==BINFMTFS_MAGIC ? MD_FS_MOUNT_BINFMT : 0;
}
static int pick_context(struct md_filesystem *fs,const struct md_fs_request *q,
        struct md_fs_result *result,const struct md_fs_output *out) {
    if (!capable(fs)) return -EPERM;
    if (!out || !out->control) return -EOPNOTSUPP;
    struct md_view_location location={.fd=-1,.parent=-1};
    int r=*q->path[0] ? md_view_walk(fs,q->directory[0],q->origin[0],q->path[0],
        !(q->mode&AT_SYMLINK_NOFOLLOW),0,MD_VIEW_TASK_PATH,&location)
        : md_view_descriptor(fs,q->directory[0],q->origin[0],&location);
    if (!r) r=md_view_require_root(fs,&location);
    unsigned type=r ? 0 : mounted_type(location.mount);
    if (!r && !type) r=-EOPNOTSUPP;
    struct filesystem_context *c=r ? NULL : calloc(1,sizeof(*c));
    if (!r && !c) r=-ENOMEM;
    if (!r) {
        atomic_init(&c->refs,1); c->type=type; c->phase=MOUNTED;
        c->opener=md_identity_copy(fs->store->identity);
        c->source=(struct md_mount_source){.fd=location.fd,.backing=location.mount->backing,
            .storage=md_inode_lifetime_retain(location.mount->storage ? location.mount->storage
                : md_view_inode(location.mount) ? md_inode_store_lifetime(fs->store) : NULL),
            .devpts=md_devpts_retain(location.mount->devpts),
            .provider=md_provider_tree_retain(location.mount->provider)};
        location.fd=-1;
        r=publish_context(c,q,result,out);
        release(c);
    }
    md_view_location_close(&location); return r;
}
int md_fs_super_reconfigure(struct md_filesystem *fs,const struct md_view_mount *mount,int readonly,const char *options) {
    if (md_view_inode(mount)) {
        struct md_inode_lifetime *storage=mount && mount->storage
            ? mount->storage : md_inode_store_lifetime(fs->store);
        if (!md_identity_capable_in(fs->store->identity,storage->owner,CAP_SYS_ADMIN)) return -EPERM;
        if (options && *options) {
            if (!md_filesystem_temporary(storage->filesystem_kind)) return -EOPNOTSUPP;
            int r=md_fs_temporary_reconfigure(options,fs->store->identity);
            if (r) return r;
        }
        if (readonly<0) return 0;
        struct md_inode_store *store;
        int r=md_fs_storage_get(fs,mount,&store);
        return r ? r : md_inode_store_reconfigure(store,readonly);
    }
    if (mounted_type(mount)) {
        struct md_user_namespace *owner=mount->devpts ? md_devpts_owner(mount->devpts)
            : md_provider_tree_owner(mount->provider);
        if (!md_identity_capable_in(fs->store->identity,
                owner,CAP_SYS_ADMIN)) return -EPERM;
        struct md_mount_policy *policy=mount->devpts ? md_devpts_super_policy(mount->devpts)
            : md_provider_tree_super_policy(mount->provider);
        unsigned flags=readonly ? MD_MOUNT_READONLY : 0;
        int r=readonly<0 ? 0 : md_mount_policy_check(policy,flags);
        if (!r && mount->devpts) r=md_devpts_reconfigure(mount->devpts,options,fs->store->identity);
        else if (!r && options && *options) r=-EOPNOTSUPP;
        return r || readonly<0 ? r : md_mount_policy_set(policy,flags);
    }
    return -EOPNOTSUPP;
}
static int parameter(struct filesystem_context *c,const struct md_fs_request *q) {
    /* No current provider takes an FD-valued parameter. Validate the captured
     * FD at admission, then report the unsupported parameter type as EINVAL. */
    if (q->mode!=FSCONFIG_SET_FLAG && q->mode!=FSCONFIG_SET_STRING) return -EINVAL;
    const char *key=q->path[0],*value=q->value;
    int string=q->mode==FSCONFIG_SET_STRING;
    if (!strcmp(key,"source")) return !string ? -EINVAL : c->phase==MOUNTED ? -EOPNOTSUPP : 0;
    if (!strcmp(key,"ro") || !strcmp(key,"rw")) {
        if (string) return -EINVAL;
        c->flags_mask|=MS_RDONLY;
        c->flags=!strcmp(key,"ro") ? MS_RDONLY : 0; return 0;
    }
    int known=0,expect_string=1;
    switch (c->type) {
    case MD_FS_MOUNT_PROC:
        known=!strcmp(key,"hidepid") || !strcmp(key,"gid") || !strcmp(key,"subset"); break;
    case MD_FS_MOUNT_TMPFS: case MD_FS_MOUNT_RAMFS:
        known=!strcmp(key,"mode") || !strcmp(key,"uid") || !strcmp(key,"gid")
            || !strcmp(key,"size") || !strcmp(key,"nr_blocks") || !strcmp(key,"nr_inodes") || !strcmp(key,"noswap");
        expect_string=strcmp(key,"noswap")!=0; break;
    case MD_FS_MOUNT_OVERLAY:
        known=!strcmp(key,"lowerdir") || !strcmp(key,"upperdir") || !strcmp(key,"workdir") || !strcmp(key,"userxattr");
        expect_string=strcmp(key,"userxattr")!=0; break;
    case MD_FS_MOUNT_DEVPTS:
        known=!strcmp(key,"mode") || !strcmp(key,"ptmxmode") || !strcmp(key,"uid") || !strcmp(key,"gid")
            || !strcmp(key,"max") || !strcmp(key,"newinstance");
        expect_string=strcmp(key,"newinstance")!=0; break;
    case MD_FS_MOUNT_CGROUP:
        known=!strcmp(key,"devices") || !strcmp(key,"none") || !strcmp(key,"name");
        expect_string=!strcmp(key,"name"); break;
    case MD_FS_MOUNT_CGROUP2: {
        unsigned flags;
        known=*key && !strchr(key,',') && !md_cgroup_parse_options(key,&flags);
        expect_string=0; break;
    }
    default: break;
    }
    /* Unknown filesystem options are EINVAL, including optional newer-kernel
     * flags. Callers may retry without them; do not claim their semantics. */
    if (!known) return -EINVAL;
    if (string!=expect_string) return -EINVAL;
    /* The existing providers consume legacy option lists. Reject ambiguous
     * encodings instead of splitting a single fsconfig value into options. */
    if (strchr(key,',') || strchr(key,'=') || (string && strchr(value,','))) return -EOPNOTSUPP;
    size_t k=strlen(key),v=string ? strlen(value) : 0;
    size_t bytes=k+(string ? v+1 : 0)+(c->size ? 1 : 0);
    if (bytes>=sizeof(c->options)-c->size) return -E2BIG;
    char *at=c->options+c->size;
    if (c->size) *at++=',';
    memcpy(at,key,k); at+=k;
    if (string) { *at++='='; memcpy(at,value,v); at+=v; }
    *at=0; c->size+=bytes; return 0;
}
static int configure(struct md_filesystem *fs,struct filesystem_context *c,const struct md_fs_request *q) {
    if (c->phase==MOUNTED) {
        if (q->mode==FSCONFIG_CMD_CREATE) return -EBUSY;
        if (q->mode!=FSCONFIG_CMD_RECONFIGURE) return parameter(c,q);
        struct md_view_mount source={.backing=c->source.backing,.storage=c->source.storage,
            .provider=c->source.provider,.devpts=c->source.devpts};
        if (!mounted_type(&source)) return -EOPNOTSUPP;
        int r=md_fs_super_reconfigure(fs,&source,c->flags_mask ? !!(c->flags&MS_RDONLY) : -1,c->options);
        if (!r) { c->flags=0; c->flags_mask=0; c->size=0; c->options[0]=0; }
        return r;
    }
    if (q->mode==FSCONFIG_CMD_RECONFIGURE) return -EBUSY;
    if (c->phase!=PARAMETERS) return -EBUSY;
    if (q->mode!=FSCONFIG_CMD_CREATE) return parameter(c,q);
    if (!md_identity_capable_in(fs->store->identity,c->opener.user_namespace,CAP_SYS_ADMIN)) return -EPERM;
    int r=md_view_mount_collect(fs);
    if (r) return r;
    const struct md_identity *identity=fs->store->identity;
    const struct md_task_snapshot *actor=fs->actor;
    struct md_task_snapshot captured=actor ? *actor : (struct md_task_snapshot){0};
    captured.pid=c->pid; captured.cgroup=c->cgroup;
    captured.cgroup_namespace=c->cgroup_namespace;
    captured.network=c->network;
    captured.ipc=c->ipc;
    fs->store->identity=&c->opener; fs->actor=&captured;
    struct md_fs_request create={.mode=c->type,.flags=c->flags,
        .value=c->options,.value_size=(unsigned)c->size+1};
    r=md_mount_source_create(fs,&create,&c->source);
    if (!r && (c->type==MD_FS_MOUNT_TMPFS || c->type==MD_FS_MOUNT_RAMFS || c->source.provider)) c->source.policy&=~MD_MOUNT_READONLY;
    fs->store->identity=identity; fs->actor=actor;
    c->phase=r ? FAILED : CREATED;
    return r;
}
static int mount_context(struct md_filesystem *fs,struct filesystem_context *c,const struct md_fs_request *q,
        struct md_fs_result *result,const struct md_fs_output *out) {
    if (!capable(fs)) return -EPERM;
    unsigned attrs=q->mode;
    if (attrs&~(MOUNT_ATTR_RDONLY|MOUNT_ATTR_NOSUID|MOUNT_ATTR_NODEV|MOUNT_ATTR_NOEXEC
            |MOUNT_ATTR_NOSYMFOLLOW|MOUNT_ATTR__ATIME|MOUNT_ATTR_NODIRATIME)) return -EOPNOTSUPP;
    if ((attrs&MOUNT_ATTR__ATIME)!=MOUNT_ATTR_RELATIME && (attrs&MOUNT_ATTR__ATIME)!=MOUNT_ATTR_NOATIME
            && (attrs&MOUNT_ATTR__ATIME)!=MOUNT_ATTR_STRICTATIME) return -EINVAL;
    if (c->phase!=CREATED) return c->source.fd<0 ? -EINVAL : -EBUSY;
    if (!out || !out->origin) return -EOPNOTSUPP;
    struct md_mount_source source=c->source;
    source.policy|=md_fs_mount_attributes(attrs);
    int fd=fcntl(source.fd,F_DUPFD_CLOEXEC,3);
    if (fd<0) return -errno;
    struct md_view_mount *mount=NULL;
    int r=md_view_attach_source(fs,&source,NULL,&mount);
    if (r) { close(fd); return r; }
    result->fd=fd;
    md_view_capture(fs,fs->mounts,mount,result,out);
    if (result->error) {
        /* Unpublished detached output has no anchor. Normal collection also
         * handles cancellation/EMFILE after a successful worker completion. */
        md_view_mount_collect(fs); return result->error;
    }
    c->phase=MOUNTED;
    c->size=0; c->options[0]=0; c->flags=0; c->flags_mask=0;
    return 0;
}
void md_fs_context_mount(struct md_filesystem *fs,const struct md_fs_request *q,
        struct md_fs_result *result,const struct md_fs_output *out) {
    *result=(struct md_fs_result){.fd=-1};
    if (q->operation==MD_FS_FSOPEN) {
        result->error=open_context(fs,q,result,out);
        return;
    }
    if (q->operation==MD_FS_FSPICK) {
        result->error=pick_context(fs,q,result,out);
        return;
    }
    if (q->control.ops!=&context_ops) { result->error=-EINVAL; return; }
    struct filesystem_context *c=q->control.context;
    result->error=q->operation==MD_FS_FSCONFIG ? configure(fs,c,q) : mount_context(fs,c,q,result,out);
}
