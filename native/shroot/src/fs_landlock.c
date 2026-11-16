#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "fs_storage.h"
#include "fs_control.h"
#include "inode_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>
#include "proc_paths.h"

static uint64_t requested(const struct md_fs_request *q,mode_t kind,int exists) {
    switch (q->operation) {
    case MD_FS_OPEN_IMAGE: return MD_LL_EXECUTE;
    case MD_FS_OPEN_OBJECT:
        return q->mode&MD_FS_OBJECT_EXECUTE ? MD_LL_EXECUTE : md_landlock_open_access(q->flags,kind);
    case MD_FS_REOPEN:
        return q->mode&MD_FS_REOPEN_EXECUTE ? MD_LL_EXECUTE : md_landlock_open_access(q->flags,kind);
    case MD_FS_OPEN: return md_landlock_open_access(q->flags,kind)
        | (!exists && (q->flags&O_CREAT) && !(q->flags&O_PATH) ? MD_LL_MAKE_REG : 0);
    case MD_FS_CREATE: return MD_LL_MAKE_REG|MD_LL_READ_FILE|MD_LL_WRITE_FILE;
    case MD_FS_MKDIR: return MD_LL_MAKE_DIR;
    case MD_FS_MKNOD: return md_landlock_make_access(q->mode);
    case MD_FS_SYMLINK: return MD_LL_MAKE_SYM;
    case MD_FS_SOCKET_BIND: return MD_LL_MAKE_SOCK;
    case MD_FS_UNLINK: return q->flags&AT_REMOVEDIR ? MD_LL_REMOVE_DIR : MD_LL_REMOVE_FILE;
    default: return 0;
    }
}
struct ancestry { struct md_inode_store *store; struct mdi_node node; char root[33]; };
static int ancestor_matches(void *data,const struct md_landlock_key *key) {
    struct ancestry *a=data;
    return mdi_landlock_matches(a->store,&a->node,NULL,a->root,key);
}
int md_fs_landlock_scope(struct md_filesystem *fs,const struct md_view_mount *mount,
        struct md_landlock_scope *scope) {
    const struct md_landlock_policy *policy=fs->store->landlock;
    /* Visit one catalogue at a time, before the selected inode transaction.
     * A detached tree retains local grants, not its former parent's authority. */
    for (const struct md_view_mount *m=mount;m && m->parent && !m->detached;m=m->parent) {
        if (!md_view_inode(m->parent)) continue;
        struct ancestry a={0};
        int r=md_fs_storage_get(fs,m->parent,&a.store);
        if (r) return r;
        r=mdi_begin(a.store,0);
        if (r) return r;
        if (m->parent->parent) {
            struct mdi_node root;
            r=mdi_fd(a.store,m->parent->source,&root);
            if (!r) memcpy(a.root,root.id,sizeof(a.root));
        }
        if (!r) r=mdi_fd(a.store,m->target,&a.node);
        if (!r) r=md_landlock_collect(policy,ancestor_matches,&a,scope->inherited);
        r=mdi_finish(a.store,r);
        if (r) return r;
    }
    return 0;
}
int md_fs_landlock_native(struct md_filesystem *fs,const struct md_fs_request *q,
        struct md_view_mount *mount,int fd) {
    if (!fs->store->landlock) return 0;
    if (q->operation==MD_FS_LINK || q->operation==MD_FS_RENAME) return -EOPNOTSUPP;
    struct stat st={.st_mode=S_IFREG};
    if (fd>=0 && fstat(fd,&st)) return -errno;
    uint64_t access=requested(q,st.st_mode,fd>=0);
    if (!access) return 0;
    /* Native grants need a provider-owned ancestry contract. Until present,
     * only a retained guest mountpoint can supply inherited authority; an
     * arbitrary inherited host FD is not underneath the guest root. */
    while (mount && mount->parent && !md_view_inode(mount->parent)) mount=mount->parent;
    if (!mount || !mount->parent || mount->detached) return -EACCES;
    struct md_fs_storage_scope scope;
    int r=md_fs_storage_enter(fs,mount->parent,&scope);
    if (r) return r;
    r=mdi_begin(scope.store,0);
    if (!r) {
        struct mdi_node node;
        r=mdi_fd(scope.store,mount->target,&node);
        if (!r) r=mdi_landlock_object(scope.store,&node,access);
        r=mdi_finish(scope.store,r);
    }
    md_fs_storage_leave(&scope); return r;
}
int md_fs_landlock_delegate(struct md_filesystem *fs,const struct md_fs_request *q,const char *path) {
    if (!fs->store->landlock) return 0;
    struct md_proc_path proc=md_proc_path(path);
    enum md_proc_kind kind=proc.kind==MD_PROC_FOREIGN ? proc.foreign_kind : proc.kind;
    /* Magic links are selected by the task provider and rechecked against
     * their actual target; proc-root ancestry must not authorize that target. */
    if (kind==MD_PROC_FD || kind==MD_PROC_EXE || kind==MD_PROC_ROOT || kind==MD_PROC_CWD) return 0;
    uint64_t access=requested(q,(q->flags&O_DIRECTORY) ? S_IFDIR : S_IFREG,0);
    if (!access) return 0;
    const char *provider=!strncmp(path,"/dev/",5) || !strcmp(path,"/dev") ? "/dev"
        : !strncmp(path,"/proc/",6) || !strcmp(path,"/proc") ? "/proc" : NULL;
    if (!provider) return -EACCES;
    struct md_inode_store *s=fs->store;
    int r=mdi_begin(s,0);
    if (r) return r;
    struct md_inode_boundary *boundary=s->boundary; s->boundary=NULL;
    struct mdi_location loc;
    r=mdi_walk(s,MD_INODE_ROOT,provider,MDI_FOLLOW,0,&loc);
    if (!r) r=mdi_landlock(s,&loc.node,&loc.parent,access);
    s->boundary=boundary;
    return mdi_finish(s,r);
}

void md_fs_landlock(struct md_filesystem *fs,const struct md_fs_request *q,
        struct md_fs_result *out,const struct md_fs_output *output) {
    *out=(struct md_fs_result){.fd=-1};
    const struct md_identity *id=fs->store->identity;
    if (!fs->actor || !id) { out->error=-EOPNOTSUPP; return; }
    uint64_t access; memcpy(&access,q->value,sizeof(access));
    struct md_landlock_ruleset *rules=md_landlock_ruleset_control(q->control);
    int r=0;
    if (q->mode==MD_LL_CREATE) {
        if (!output || !output->control) { out->error=-EOPNOTSUPP; return; }
        rules=md_landlock_ruleset_new(access);
        if (!rules) { out->error=-errno; return; }
        const struct md_control_file_ops *ops=NULL; void *context=NULL;
        int fd=md_landlock_ruleset_open(rules,id,&ops,&context);
        if (fd<0) r=fd;
        else {
            struct md_control_file file={ops,context};
            r=md_fs_control_publish(fd,file,NULL,id,O_RDWR|O_CLOEXEC,out,output);
            md_control_file_release(file);
        }
        md_landlock_ruleset_release(rules);
    } else if (!rules) r=-EBADFD;
    else if (q->mode==MD_LL_RESTRICT) {
        if (!id->no_new_privs && !md_identity_capable_in(id,id->user_namespace,CAP_SYS_ADMIN)) r=-EPERM;
        else if (!output || !output->landlock) r=-EOPNOTSUPP;
        else {
            *output->landlock=md_landlock_restrict(fs->actor->landlock,rules);
            if (!*output->landlock) r=-errno;
        }
    } else {
        struct md_view_location loc={.fd=-1,.parent=-1};
        r=md_view_descriptor(fs,q->directory[1],q->origin[1],&loc);
        if (!r && !md_view_inode(loc.mount)) r=-EOPNOTSUPP;
        struct md_fs_storage_scope scope={0};
        if (!r) r=md_fs_storage_enter(fs,loc.mount,&scope);
        if (!r) {
            struct md_inode_store *s=scope.store;
            r=mdi_begin(s,0);
            if (!r) {
                struct mdi_node node;
                r=mdi_fd(s,loc.fd,&node);
                if (!r && node.kind!=S_IFDIR && (access&~MD_LL_FILE)) r=-EINVAL;
                if (!r) {
                    struct md_landlock_key key={.store=s->lifetime};
                    memcpy(key.object,node.id,sizeof(key.object));
                    r=md_landlock_rule_add(rules,&key,access,node.native ? loc.fd : -1);
                }
                r=mdi_finish(s,r);
            }
        }
        md_fs_storage_leave(&scope); md_view_location_close(&loc);
    }
    out->error=r;
}
