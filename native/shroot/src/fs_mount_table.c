#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "inode_internal.h"
#include "native_mounts.h"
#include "fs_control.h"
#include "fs_storage.h"
#include "procfs_view.h"
#include "fuse_view.h"
#include "proc_paths.h"
#include "mount_graph.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/sysmacros.h>
#include <unistd.h>

struct md_mount_identity { uint64_t native[4096]; unsigned guest[4096], count; };
static int ensure_view(struct md_filesystem *fs) {
    if (!fs->mounts) {
        fs->mounts=md_mount_namespace_new();
        if (!fs->mounts) return -errno;
    }
    return 0;
}
int md_fs_base_mount(struct md_filesystem *fs, int shared_memory, uint64_t *guest) {
    int r=ensure_view(fs);
    if (!r) *guest=shared_memory ? fs->mounts->shm_id : fs->mounts->base.id;
    return r;
}
int md_fs_native_mount(struct md_filesystem *fs, uint64_t native, uint64_t *guest) {
    if (!native) return -EINVAL;
    int r=ensure_view(fs);
    if (r) return r;
    if (!fs->mounts->native_ids) {
        fs->mounts->native_ids=calloc(1,sizeof(*fs->mounts->native_ids));
        if (!fs->mounts->native_ids) return -ENOMEM;
    }
    struct md_mount_identity *ids=fs->mounts->native_ids;
    unsigned i;
    for (i=0; i<ids->count; ++i) if (ids->native[i]==native) break;
    if (i==ids->count) {
        if (i==sizeof(ids->native)/sizeof(ids->native[0])) return -ENOSPC;
        r=md_mount_id_allocate(&ids->guest[i]);
        if (r) return r;
        ids->native[ids->count++]=native;
    }
    *guest=ids->guest[i];
    return 0;
}

struct table_path { const struct md_view_mount *mount; char *path; };
static int path_order(const void *left,const void *right) {
    unsigned a=((const struct table_path *)left)->mount->id;
    unsigned b=((const struct table_path *)right)->mount->id;
    return (a>b)-(a<b);
}
static void paths_release(struct table_path *paths,unsigned count) {
    for (unsigned i=0;i<count;++i) free(paths[i].path);
    free(paths);
}
static int paths_capture(struct md_filesystem *fs,const struct md_view_mount *selected,
        struct table_path **out,unsigned *count) {
    struct md_fs_mounts *tree=fs->mounts->tree;
    *out=NULL; *count=0;
    if (!tree) return 0;
    struct table_path *paths=calloc(tree->count+1,sizeof(*paths));
    if (!paths) return -ENOMEM;
    int r=0;
    for (unsigned i=0;!r && i<=tree->count;++i) {
        const struct md_view_mount *m=i ? &tree->mounts[i-1] : &fs->mounts->base;
        if (m->detached || m==selected || !m->parent) continue;
        char path[PATH_MAX]; r=md_view_path(fs,m->target,m->parent,path,sizeof(path));
        if (r==-ENOENT && (fs->store->root_id || fs->native_root)) { r=0; continue; }
        if (r) break;
        paths[*count]=(struct table_path){.mount=m,.path=strdup(path)};
        if (!paths[*count].path) r=-ENOMEM;
        else ++*count;
    }
    if (r) { paths_release(paths,*count); *count=0; }
    else {
        /* Slots are reusable; public IDs are monotonic. Preserve namespace
         * insertion order so new mounts follow entries already seen by readers. */
        if (*count>1) qsort(paths,*count,sizeof(*paths),path_order);
        *out=paths;
    }
    return r;
}
static int native_covered(const struct md_filesystem *fs,const struct table_path *paths,
        unsigned count,const char *path) {
    for (unsigned i=0;i<count;++i) {
        if (paths[i].mount->parent!=&fs->mounts->base) continue;
        size_t n=strlen(paths[i].path);
        if (!strncmp(path,paths[i].path,n) && (!path[n] || path[n]=='/')) return 1;
    }
    return 0;
}
static int native_path(char *path) {
    char *out=path;
    for (const char *in=path;*in;++in) {
        unsigned char c=*in;
        if (c=='\\') {
            if (in[1]<'0' || in[1]>'3' || in[2]<'0' || in[2]>'7' || in[3]<'0' || in[3]>'7') return -EPROTO;
            c=(unsigned char)((in[1]-'0')*64+(in[2]-'0')*8+in[3]-'0');
            if (!c) return -EPROTO;
            in+=3;
        }
        *out++=(char)c;
    }
    *out=0; return 0;
}

/* Native mounts retain kernel IO and authority, but IDs identify instances in
 * the selected view, as for logical mounts. statx uses this same map. */
static int native_entries(struct md_filesystem *fs, FILE *out, unsigned info, unsigned *dev_id,
        const struct table_path *paths,unsigned count) {
    const char *bytes; size_t size;
    int r=md_native_mounts_read(&fs->mounts->native_mounts,&bytes,&size);
    if (r) return r;
    FILE *in=fmemopen((void *)bytes,size,"r");
    if (!in) return -errno;
    char *line=NULL; size_t capacity=0;
    while (!r && getline(&line,&capacity,in)>=0) {
        unsigned long long raw, parent; int rest=0;
        char device[64], root[PATH_MAX], path[PATH_MAX], options[1024];
        if (sscanf(line,"%llu %llu %n%63s %4095s %4095s %1023s",
                &raw,&parent,&rest,device,root,path,options)!=6) { r=-EPROTO; break; }
        char decoded[PATH_MAX]; strcpy(decoded,path);
        r=native_path(decoded);
        if (r) break;
        /* Initial native providers are not placements below a guest overmount.
         * Do not advertise hidden host children as movable guest mounts. */
        if (native_covered(fs,paths,count,decoded)) continue;
        uint64_t id, owner=fs->mounts->base.id;
        r=md_fs_native_mount(fs,raw,&id);
        if (!r && strcmp(path,"/dev") && strcmp(path,"/proc"))
            r=md_fs_native_mount(fs,parent,&owner);
        if (r) break;
        if (!strcmp(path,"/dev")) *dev_id=(unsigned)id;
        if (info) fprintf(out,"%llu %llu %s",(unsigned long long)id,(unsigned long long)owner,line+rest);
        else {
            char type[128], source[PATH_MAX], *separator=strstr(line," - ");
            if (!separator || sscanf(separator+3,"%127s %4095s",type,source)!=2) { r=-EPROTO; break; }
            fprintf(out,"%s %s %s %s 0 0\n",source,path,type,options);
        }
    }
    if (!r && ferror(in)) r=-EIO;
    free(line); fclose(in); return r;
}

static void escaped(FILE *stream, const char *text) {
    for (const unsigned char *p=(const unsigned char *)text; *p; ++p) {
        if (*p==' ' || *p=='\t' || *p=='\n' || *p=='\\') fprintf(stream,"\\%03o",*p);
        else fputc(*p,stream);
    }
}
static void entry(FILE *stream, unsigned info, unsigned id, unsigned parent, dev_t device,
        const char *path, const char *type, const char *source, unsigned policy,int super_readonly,const char *super_options,
        const struct md_mount_links *links,const struct md_mount_members *members) {
    char options[64];
    snprintf(options,sizeof(options),"%s%s%s%s%s",policy&MD_MOUNT_READONLY ? "ro" : "rw",
        policy&MD_MOUNT_NOSUID ? ",nosuid" : "",policy&MD_MOUNT_NODEV ? ",nodev" : "",
        policy&MD_MOUNT_NOEXEC ? ",noexec" : "",policy&MD_MOUNT_NOSYMFOLLOW ? ",nosymfollow" : "");
    if (info) {
        fprintf(stream,"%u %u %u:%u / ",id,parent,major(device),minor(device)); escaped(stream,path);
        fprintf(stream," %s",options);
        if (links) {
            unsigned shared=md_mount_group_id(links->shared);
            struct md_mount_group *master=links->master;
            if (!md_mount_links_foreign(links)) master=md_mount_group_live(members,master,NULL);
            if (shared) fprintf(stream," shared:%u",shared);
            if (master) fprintf(stream," master:%u",md_mount_group_id(master));
            if (links->unbindable) fputs(" unbindable",stream);
        }
        fputs(" - ",stream); escaped(stream,type); fputc(' ',stream); escaped(stream,source);
        fprintf(stream," %s%s\n",super_readonly ? "ro" : "rw",super_options);
    } else {
        if (super_readonly) memcpy(options,"ro",2);
        escaped(stream,source); fputc(' ',stream); escaped(stream,path); fputc(' ',stream); escaped(stream,type);
        fprintf(stream," %s%s 0 0\n",options,super_options);
    }
}
static int super_readonly(struct md_filesystem *fs,const struct md_view_mount *mount) {
    if (mount && mount->devpts) return !!(md_mount_policy_flags(md_devpts_super_policy(mount->devpts))&MD_MOUNT_READONLY);
    if (mount && mount->provider) return !!(md_provider_tree_super_flags(mount->provider)&MD_MOUNT_READONLY);
    if (!md_view_inode(mount)) return 0;
    struct md_inode_store *store;
    int r=md_fs_storage_get(fs,mount,&store);
    return r ? r : md_inode_store_readonly(store);
}
static const char *filesystem_type(const struct md_view_mount *mount) {
    if (mount && mount->file.ops) {
        if (mount->file.ops->filesystem==NSFS_MAGIC) return "nsfs";
        if (mount->file.ops->filesystem==PROC_SUPER_MAGIC) return "proc";
    }
    if (mount && mount->backing==MD_MOUNT_PROC) return "proc";
    if (mount && mount->backing==MD_MOUNT_DEVPTS) return "devpts";
    if (mount && mount->backing==MD_MOUNT_SYSFS) return "sysfs";
    if (mount && mount->provider) {
        unsigned long type=md_provider_tree_filesystem(mount->provider);
        return type==FUSE_SUPER_MAGIC ? md_fuse_view_type(mount->provider) : type==SYSFS_MAGIC ? "sysfs"
            : type==BINFMTFS_MAGIC ? "binfmt_misc" : "tmpfs";
    }
    if (mount && mount->backing==MD_MOUNT_MQUEUE) return "mqueue";
    if (mount && mount->cgroup) return md_cgroup_legacy(mount->cgroup) ? "cgroup" : "cgroup2";
    return !md_view_inode(mount) ? "none" : md_filesystem_name(mount->storage ? mount->storage->filesystem_kind : MD_FILESYSTEM_INODE);
}
static const char *filesystem_source(const struct md_view_mount *mount) {
    if (mount && md_fuse_view_matches(mount->provider)) return md_fuse_view_label(mount->provider);
    return md_view_inode(mount) ? "guest" : "attachment";
}
static int filesystem_options(struct md_filesystem *fs,const struct md_view_mount *mount,char *out,size_t size) {
    if (mount && mount->backing==MD_MOUNT_PROC) return md_procfs_options(mount,fs->store->identity,out,size);
    if (mount && md_fuse_view_matches(mount->provider))
        return md_fuse_view_options(mount->provider,fs->store->identity,out,size);
    if (mount && mount->cgroup) return md_cgroup_options(mount->cgroup,out,size);
    snprintf(out,size,"%s","");
    return 0;
}
int md_fs_mount_table(struct md_filesystem *fs, unsigned info, unsigned flags) {
    if (info>1 || (flags&~(O_PATH|O_CLOEXEC))) return -EINVAL;
    int r=ensure_view(fs);
    if (!r) r=md_view_mount_reconcile(fs);
    if (r) return r;
    int fd=md_inode_temporary(fs->store);
    if (fd<0) return fd;
    FILE *stream=fdopen(fd,"w+");
    if (!stream) { int error=-errno; close(fd); return error; }
    struct md_mount_members members={0};
    r=md_mount_family_capture(fs->mounts,&members);
    if (r) { fclose(stream); return r; }
    struct stat root;
    int root_fd=md_view_root_fd(fs);
    r=root_fd<0 ? root_fd : fstat(root_fd,&root) ? -errno : 0;
    if (root_fd>=0) close(root_fd);
    const struct md_view_mount *selected=md_view_root_mount(fs);
    struct table_path *paths=NULL; unsigned count=0;
    if (!r) r=paths_capture(fs,selected,&paths,&count);
    unsigned dev_id=fs->mounts->base.id;
    int readonly=super_readonly(fs,selected);
    if (!r && readonly<0) r=readonly;
    if (!r) {
        if (!md_view_base(fs->mounts,selected)) {
            const struct md_view_mount *m=selected;
            char options[128]; r=filesystem_options(fs,m,options,sizeof(options));
            if (!r) entry(stream,info,m->id,m->parent ? m->parent->id : m->id,
                md_fuse_view_matches(m->provider) ? md_fuse_view_device(m->provider) : root.st_dev,"/",filesystem_type(m),
                filesystem_source(m),md_mount_policy_flags(&m->policy),readonly,options,&m->links,&members);
        } else entry(stream,info,fs->mounts->base.id,fs->mounts->base.id,root.st_dev,"/","guestfs","guest",
            md_mount_policy_flags(&fs->mounts->base.policy),readonly,"",&fs->mounts->base.links,&members);
        if (!fs->store->root_id && !fs->native_root && selected==&fs->mounts->base) {
            r=native_entries(fs,stream,info,&dev_id,paths,count);
            if (!r && !native_covered(fs,paths,count,"/dev/shm"))
                entry(stream,info,fs->mounts->shm_id,dev_id,root.st_dev,"/dev/shm","guestfs","guest",0,readonly,"",NULL,&members);
        }
    }
    for (unsigned i=0; !r && i<count; ++i) {
        const struct md_view_mount *m=paths[i].mount;
        struct stat st=m->native;
        if (md_fuse_view_matches(m->provider)) st.st_dev=md_fuse_view_device(m->provider);
        if (!r && m->file.ops) r=m->file.ops->stat(m->file.context,&st);
        if (!r) { readonly=super_readonly(fs,m); if (readonly<0) r=readonly; }
        char options[128];
        if (!r) r=filesystem_options(fs,m,options,sizeof(options));
        if (!r) entry(stream,info,m->id,m->parent ? m->parent->id : fs->mounts->base.id,
            st.st_dev,paths[i].path,filesystem_type(m),
            filesystem_source(m),md_mount_policy_flags(&m->policy),readonly,options,&m->links,&members);
    }
    paths_release(paths,count);
    if (!r && (fflush(stream) || ferror(stream))) r=-EIO;
    int readable=-1;
    if (!r) {
        char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",fd);
        readable=open(path,(int)flags|O_CLOEXEC);
        if (readable<0) r=-errno;
    }
    if (fclose(stream) && !r) r=-errno;
    md_mount_members_release(&members);
    if (r && readable>=0) close(readable);
    return r ? r : readable;
}

struct md_mount_table_file {
    atomic_uint references;
    struct md_mount_table_file *next;
    struct md_mount_namespace *view;
    struct md_fs_location root;
    struct md_fs_anchor *root_anchor;
    pthread_mutex_t lock;
    struct stat metadata;
    unsigned info;
    int latest, reading, error, linked;
    int reader, writer;
};
static void *table_retain(void *context) {
    struct md_mount_table_file *f=context;
    atomic_fetch_add_explicit(&f->references,1,memory_order_relaxed); return f;
}
static void table_release(void *context) {
    struct md_mount_table_file *f=context;
    struct md_mount_namespace *view=f->view;
    pthread_mutex_lock(&view->tables_lock);
    int last=atomic_fetch_sub_explicit(&f->references,1,memory_order_acq_rel)==1;
    if (last && f->linked) {
        struct md_mount_table_file **link=&view->tables;
        while (*link && *link!=f) link=&(*link)->next;
        if (*link) *link=f->next;
    }
    pthread_mutex_unlock(&view->tables_lock);
    if (!last) return;
    if (f->latest>=0) close(f->latest);
    if (f->reading>=0) close(f->reading);
    if (f->reader>=0) close(f->reader);
    if (f->writer>=0) close(f->writer);
    md_fs_anchor_release(f->root_anchor); pthread_mutex_destroy(&f->lock);
    md_mount_namespace_release(view); free(f);
}
static int table_stat(void *context,struct stat *out) {
    *out=((struct md_mount_table_file *)context)->metadata; return 0;
}
static int table_access(void *context,const struct md_identity *id,unsigned mode) {
    (void)context; (void)id;
    return mode&~7U ? -EINVAL : mode&(W_OK|X_OK) ? -EACCES : 0;
}
static int table_descriptor(void *context,int flags) {
    struct md_mount_table_file *f=context;
    if (flags&O_PATH) return -EOPNOTSUPP;
    int fd=fcntl(f->reader>=0 ? f->reader : f->latest,F_DUPFD_CLOEXEC,3);
    return fd<0 ? -errno : fd;
}
static long table_read(void *context,const struct md_control_actor *actor,void *out,size_t size,int64_t offset) {
    (void)actor;
    struct md_mount_table_file *f=context;
    if (offset<0) return -EINVAL;
    pthread_mutex_lock(&f->lock);
    int error=f->error;
    if (!error && (!offset || f->reading<0)) {
        int fd=fcntl(f->latest,F_DUPFD_CLOEXEC,3);
        if (fd<0) error=-errno;
        else { if (f->reading>=0) close(f->reading); f->reading=fd; }
        if (!error && f->reader>=0) { char byte; recv(f->reader,&byte,1,MSG_DONTWAIT|MSG_OOB); }
    }
    long result=error ? error : pread(f->reading,out,size,offset);
    if (!error && result<0) result=-errno;
    pthread_mutex_unlock(&f->lock); return result;
}
static const struct md_control_file_ops table_ops={.retain=table_retain,.release=table_release,
    .stat=table_stat,.access=table_access,.read=table_read,.filesystem=PROC_SUPER_MAGIC,.descriptor=table_descriptor};

static int table_socket(struct md_mount_table_file *f) {
    int pair[2];
    if (socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC|SOCK_NONBLOCK,0,pair)) return -errno;
    char byte;
    ssize_t n=send(pair[1],"m",1,MSG_OOB|MSG_NOSIGNAL);
    int error=n==1 ? 0 : n<0 ? -errno : -EIO;
    if (!error) {
        n=recv(pair[0],&byte,1,MSG_OOB);
        if (n!=1) error=n<0 ? -errno : -EIO;
    }
    if (!error) {
        n=send(pair[1],"r",1,MSG_NOSIGNAL);
        if (n!=1) error=n<0 ? -errno : -EIO;
    }
    if (error) { close(pair[0]); close(pair[1]); return error; }
    f->reader=pair[0]; f->writer=pair[1]; return 0;
}
static int table_signal(struct md_mount_table_file *f) {
    if (f->reader<0) return 0;
    char bytes[64];
    while (recv(f->reader,bytes,sizeof(bytes),MSG_DONTWAIT)>0);
    recv(f->reader,bytes,1,MSG_DONTWAIT|MSG_OOB);
    ssize_t n=send(f->writer,"rm",2,MSG_OOB|MSG_DONTWAIT|MSG_NOSIGNAL);
    return n==2 ? 0 : n<0 ? -errno : -EIO;
}

static void table_open(struct md_filesystem *fs,const struct md_fs_request *q,
        struct md_fs_result *out,const struct md_fs_output *output,const struct stat *metadata) {
    int fd=md_fs_mount_table(fs,q->mode,q->flags);
    if (fd<0) { out->error=fd; return; }
    if ((q->flags&O_PATH) || !output || !output->control) {
        out->fd=fd; md_view_capture(fs,fs->mounts,fs->inode_mount,out,output); return;
    }
    struct md_mount_table_file *f=calloc(1,sizeof(*f));
    if (!f) { close(fd); out->error=-ENOMEM; return; }
    int error=-pthread_mutex_init(&f->lock,NULL);
    if (error) { close(fd); free(f); out->error=error; return; }
    atomic_init(&f->references,1); f->view=md_mount_namespace_retain(fs->mounts);
    f->latest=fd; f->reading=f->reader=f->writer=-1; f->info=q->mode;
    int root=md_view_root_fd(fs);
    error=root<0 ? root : md_view_anchor_duplicate(fs,fs->mounts,md_view_root_mount(fs),root,&f->root_anchor);
    if (!error) error=md_fs_root_capture(fs,root,f->root_anchor,&f->root);
    if (root>=0) close(root);
    if (metadata) f->metadata=*metadata;
    else if (!error && stat(q->mode ? "/proc/self/mountinfo" : "/proc/self/mounts",&f->metadata)) error=-errno;
    if (!error) {
        error=table_socket(f);
        /* Optional AF_UNIX_OOB support. Without it, reads remain live but
         * epoll explicitly rejects the regular-file carrier with EPERM. */
        if (error==-EOPNOTSUPP) error=0;
    }
    if (!error) {
        pthread_mutex_lock(&f->view->tables_lock);
        f->next=f->view->tables; f->view->tables=f; f->linked=1;
        pthread_mutex_unlock(&f->view->tables_lock);
        error=md_fs_control_publish(table_descriptor(f,(int)q->flags),
            (struct md_control_file){&table_ops,f},NULL,fs->store->identity,(int)q->flags,out,output);
    }
    table_release(f); if (error) out->error=error;
}
void md_fs_mount_table_open(struct md_filesystem *fs,const struct md_fs_request *q,
        struct md_fs_result *out,const struct md_fs_output *output) {
    if (q->directory[0]<0) { table_open(fs,q,out,output,NULL); return; }
    /* The caller opens this proc inode before delegation. Never substitute
     * the caller's mount view for another task, or read Android's mount table. */
    char path[PATH_MAX];
    int error=md_view_fdpath(q->directory[0],path,sizeof(path));
    struct md_proc_path proc=md_proc_path_for(error ? "" : path,0,0);
    if (!error && (proc.kind!=MD_PROC_FOREIGN || *proc.tail
            || proc.foreign_kind!=(q->mode ? MD_PROC_MOUNTINFO : MD_PROC_MOUNTS))) error=-EINVAL;
    struct stat captured,current;
    if (!error && fstat(q->directory[0],&captured)) error=-errno;
    struct md_task_snapshot target={0};
    if (!error) error=md_task_registry_probe(fs->tasks,proc.process,0,&target);
    if (!error && stat(path,&current)) error=-errno;
    if (!error && (captured.st_dev!=current.st_dev || captured.st_ino!=current.st_ino)) error=-ESTALE;
    struct md_filesystem selected=*fs;
    const char *root=fs->store->root_id;
    if (!error) {
        selected.mounts=target.mounts;
        error=md_mount_graph_sync(selected.mounts);
        if (!error) error=md_fs_root_select(&selected,&target.root);
        if (!error) table_open(&selected,q,out,output,&captured);
    }
    fs->store->root_id=root;
    md_task_snapshot_release(&target);
    if (error) out->error=error;
}
void md_fs_mount_table_changed(struct md_filesystem *fs) {
    struct md_mount_namespace *view=fs->mounts;
    if (!view) return;
    pthread_mutex_lock(&view->tables_lock);
    struct md_mount_table_file *f=view->tables;
    if (f) table_retain(f);
    pthread_mutex_unlock(&view->tables_lock);
    while (f) {
        /* Only the filesystem worker renders. Publication/release locks cover
         * pointer and descriptor swaps, never namespace or database work. */
        struct md_filesystem selected=*fs;
        const char *old_root=fs->store->root_id;
        int error=md_fs_root_select(&selected,&f->root);
        int fd=error ? error : md_fs_mount_table(&selected,f->info,0);
        fs->store->root_id=old_root;
        pthread_mutex_lock(&f->lock);
        if (fd>=0) { close(f->latest); f->latest=fd; }
        f->error=fd<0 ? fd : 0;
        int changed=table_signal(f);
        if (changed && !f->error) f->error=changed;
        pthread_mutex_unlock(&f->lock);
        pthread_mutex_lock(&view->tables_lock);
        struct md_mount_table_file *next=f->next;
        if (next) table_retain(next);
        pthread_mutex_unlock(&view->tables_lock);
        table_release(f); f=next;
    }
}
