#define _GNU_SOURCE
#include "file_permissions.h"
#include "procfs_view.h"
#include "fs_mount_internal.h"
#include "fs_continuation.h"
#include "inode_internal.h"
#include "proc_paths.h"
#include "proc_sysctl.h"
#include "kernel_policy.h"
#include "proc_static.h"
#include "network_namespace.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <linux/openat2.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <unistd.h>

static const struct { const char *name; int directory; } kernel_nodes[]={
    {"sysrq-trigger",0},{"kcore",0},{"kmsg",0},{"keys",0},{"asound",1},{"kallsyms",0},
    {"timer_list",0},{"timer_stats",0},{"latency_stats",0},{"sched_debug",0},{"interrupts",0},
    {"acpi",1},{"scsi",1}
};
const char *md_procfs_static_link(int fd) {
    struct stat pinned, named;
    if (fstat(fd,&pinned) || !S_ISLNK(pinned.st_mode)) return NULL;
    /* These root entries cannot be renamed or replaced by the application.
     * Reopen only after matching the pinned inode, without SCM_RIGHTS, which
     * Android may silently strip for an otherwise accessible proc symlink. */
    const char *names[]={"/proc/self","/proc/thread-self","/proc/mounts","/proc/net","/proc/device-tree"};
    for (size_t i=0;i<sizeof(names)/sizeof(*names);++i)
        if (!lstat(names[i],&named) && pinned.st_dev==named.st_dev && pinned.st_ino==named.st_ino)
            return names[i];
    return NULL;
}
static const struct md_procfs_policy *policy(const struct md_view_mount *mount) {
    return mount ? md_procfs_tree_policy(mount->provider) : NULL;
}
static int options(struct md_procfs_policy *p,const char *text,const struct md_identity *id) {
    *p=(struct md_procfs_policy){0};
    while (text && *text) {
        size_t size=strcspn(text,",");
        char option[128];
        if (!size || size>=sizeof(option)) return -EINVAL;
        memcpy(option,text,size); option[size]=0;
        char *value=strchr(option,'=');
        if (!value) return -EINVAL;
        *value++=0;
        if (!strcmp(option,"hidepid")) {
            if (!strcmp(value,"off") || !strcmp(value,"0")) p->hidepid=0;
            else if (!strcmp(value,"noaccess") || !strcmp(value,"1")) p->hidepid=1;
            else if (!strcmp(value,"invisible") || !strcmp(value,"2")) p->hidepid=2;
            else if (!strcmp(value,"ptraceable") || !strcmp(value,"4")) p->hidepid=4;
            else return -EINVAL;
        } else if (!strcmp(option,"gid")) {
            if (*value<'0' || *value>'9') return -EINVAL;
            char *end; errno=0;
            unsigned long gid=strtoul(value,&end,10);
            if (errno || *end || gid>=UINT32_MAX) return -EINVAL;
            p->gid=md_user_namespace_into(id ? id->user_namespace : NULL,1,(uint32_t)gid);
            if (p->gid==UINT32_MAX) return -EINVAL;
        } else if (!strcmp(option,"subset")) {
            if (*value && strcmp(value,"pid")) return -EINVAL;
            if (*value) p->pidonly=1;
        } else return -EINVAL;
        text+=size; if (*text) ++text;
    }
    return 0;
}
int md_procfs_options(const struct md_view_mount *mount,const struct md_identity *id,char *out,size_t size) {
    const struct md_procfs_policy *p=policy(mount);
    if (!size) return -ENAMETOOLONG;
    out[0]=0;
    if (!p) return 0;
    char gid[40]="",hide[24]="";
    if (p->gid) snprintf(gid,sizeof(gid),",gid=%u",id ? md_identity_visible_id(id,1,p->gid) : p->gid);
    if (p->hidepid) snprintf(hide,sizeof(hide),",hidepid=%u",p->hidepid);
    int n=snprintf(out,size,"%s%s%s",gid,hide,p->pidonly ? ",subset=pid" : "");
    return n<0 || (size_t)n>=size ? -ENAMETOOLONG : 0;
}
int md_procfs_pressure_probe(int root) {
    const char *paths[]={"pressure/cpu","pressure/memory","pressure/io"};
    for (size_t i=0;i<sizeof(paths)/sizeof(*paths);++i) {
        int fd=openat(root,paths[i],O_RDONLY|O_CLOEXEC);
        int error=fd<0 ? errno : 0;
        if (fd>=0) {
            char byte;
            ssize_t size;
            do size=read(fd,&byte,1); while (size<0 && errno==EINTR);
            error=size<0 ? errno : !size ? EIO : 0;
            close(fd);
        }
        if (error==ENOENT || error==EACCES || error==EPERM
                || error==EOPNOTSUPP || error==ENOSYS) return 0;
        if (error) return -error;
    }
    return 1;
}
int md_procfs_kernel_snapshot(int upper,int lower) {
    int native=openat(lower,"version",O_RDONLY|O_CLOEXEC);
    if (native>=0) { close(native); return 0; }
    if (errno!=EACCES && errno!=EPERM && errno!=ENOENT) return -errno;
    struct utsname kernel;
    if (uname(&kernel)) return -errno;
    /* uname supplies genuine kernel identity, not the unavailable compiler
     * and builder banner. Snapshot once; reads use an ordinary read-only FD. */
    char text[sizeof(kernel.release)+sizeof(kernel.version)+32];
    int size=snprintf(text,sizeof(text),"Linux version %s %s\n",kernel.release,kernel.version);
    if (size<0 || (size_t)size>=sizeof(text)) return -EOVERFLOW;
    int fd=openat(upper,"version",O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0444);
    if (fd<0) return -errno;
    size_t offset=0;
    int error=0;
    while (offset<(size_t)size) {
        ssize_t n=write(fd,text+offset,(size_t)size-offset);
        if (n<0 && errno==EINTR) continue;
        if (n<=0) { error=n<0 ? -errno : -EIO; break; }
        offset+=(size_t)n;
    }
    if (close(fd) && !error) error=-errno;
    return error;
}
struct md_provider_tree *md_procfs_new(int parent,const char *text,const struct md_identity *id) {
    struct md_procfs_policy p;
    int error=options(&p,text,id);
    if (error) { errno=-error; return NULL; }
    if (p.hidepid) {
        int probe=open("/proc/self/io",O_RDONLY|O_CLOEXEC);
        error=probe<0 ? (errno==ENOENT ? EOPNOTSUPP : errno) : 0;
        if (probe>=0) close(probe);
        if (error) { errno=error; return NULL; }
    }
    int lower=open("/proc",O_PATH|O_DIRECTORY|O_CLOEXEC);
    if (lower<0) return NULL;
    /* Admit the optional PSI interface as a whole, under the executor's real
     * access policy. Bind/namespace copies retain this immutable snapshot;
     * neither lookup nor directory enumeration reprobes the host. */
    int pressure=md_procfs_pressure_probe(lower);
    if (pressure<0) { close(lower); errno=-pressure; return NULL; }
    p.pressure=(unsigned)pressure;
    struct md_provider_tree *tree=md_procfs_tree_new(parent,lower,&p);
    error=tree ? 0 : errno;
    if (tree) error=-md_procfs_kernel_snapshot(md_provider_tree_root(tree),lower);
    const char *sampled[]={"uptime","stat"};
    for (size_t i=0;!error && i<sizeof(sampled)/sizeof(*sampled);++i) {
        int sample=openat(lower,sampled[i],O_RDONLY|O_CLOEXEC);
        if (sample>=0) close(sample);
        else if (errno==EACCES || errno==EPERM || errno==ENOENT) {
            sample=openat(md_provider_tree_root(tree),sampled[i],O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0444);
            if (sample<0) error=errno; else close(sample);
        } else error=errno;
    }
    close(lower);
    if (!tree || error) { md_provider_tree_release(tree); errno=error; return NULL; }
    int root=md_provider_tree_root(tree);
    if (!error && (mkdirat(root,"sys",0755) || mkdirat(root,"sys/kernel",0755) || mkdirat(root,"sys/net",0755)
            || mkdirat(root,"sys/fs",0755) || mkdirat(root,"sys/fs/inotify",0755)
            || mkdirat(root,"sys/fs/binfmt_misc",0755))) error=errno;
    for (unsigned i=MD_PROC_FILE_HOSTNAME;!error && i<=MD_PROC_FILE_INOTIFY_QUEUE;++i) {
        char path[64]; snprintf(path,sizeof(path),"%s/%s",md_proc_sysctl_directory(i),md_proc_sysctl_name(i));
        int fd=openat(root,path,O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600);
        if (fd<0) error=errno; else close(fd);
    }
    if (!error && mkdirat(root,"sys/user",0755)) error=errno;
    if (!error && (mkdirat(root,"sys/vm",0755) || mkdirat(root,"sys/kernel/keys",0755))) error=errno;
    for (unsigned i=0;!error && i<MD_KERNEL_POLICY_COUNT;++i) {
        int fd=openat(root,md_kernel_policy_specs[i].path,O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600);
        if (fd<0) error=errno; else close(fd);
    }
    const char *files[]={"sys/user/max_user_namespaces","devices","cmdline","filesystems","swaps","diskstats","modules"};
    for (size_t i=0;!error && i<sizeof(files)/sizeof(*files);++i) {
        int fd=openat(root,files[i],O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600);
        if (fd<0) error=errno; else close(fd);
    }
    /* Host-global nodes have inert, inaccessible private attachment points.
     * Container runtimes may open O_PATH before covering them with a mount. */
    for (size_t i=0;!error && i<sizeof(kernel_nodes)/sizeof(*kernel_nodes);++i) {
        if (kernel_nodes[i].directory) {
            if (mkdirat(root,kernel_nodes[i].name,0700)) error=errno;
        } else {
            int fd=openat(root,kernel_nodes[i].name,O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0000);
            if (fd<0) error=errno; else close(fd);
        }
    }
    if (error) { md_provider_tree_release(tree); errno=error; return NULL; }
    return tree;
}
int md_procfs_path(const struct md_view_mount *mount,int fd,char *out,size_t size) {
    if (!mount->provider) return md_view_fdpath(fd,out,size);
    if (size<6) return -ENAMETOOLONG;
    memcpy(out,"/proc",5);
    int result=md_provider_tree_path(mount->provider,fd,out+5,size-5);
    if (!result && !strcmp(out,"/proc/")) out[5]=0;
    return result;
}

static pid_t decimal(const char *name,const char **end) {
    unsigned long value=0;
    const char *p=name;
    if (*p<'0' || *p>'9' || (*p=='0' && p[1])) return 0;
    while (*p>='0' && *p<='9') {
        value=value*10+(unsigned)(*p++-'0');
        if (value>INT_MAX) return 0;
    }
    if (end) *end=p;
    else if (*p) return 0;
    return (pid_t)value;
}
static pid_t visible(const struct md_view_mount *mount,pid_t native) {
    if (!mount->pid_namespace) return native;
    struct md_pid_identity *id=md_pid_namespace_find(mount->pid_namespace,native);
    pid_t result=md_pid_identity_number(id,mount->pid_namespace);
    md_pid_identity_release(id); return result;
}
int md_procfs_guest_path(const struct md_view_mount *mount,int fd,char *out,size_t size) {
    char path[PATH_MAX];
    int error=md_procfs_path(mount,fd,path,sizeof(path));
    if (error) return error;
    if (strncmp(path,"/proc",5) || (path[5] && path[5]!='/')) return -EXDEV;
    const char *relative=path[5] ? path+5 : "/", *tail;
    pid_t native=decimal(relative+1,&tail);
    int length;
    if (!native || (*tail && *tail!='/')) length=snprintf(out,size,"%s",relative);
    else {
        pid_t pid=visible(mount,native);
        if (!pid) return -ENOENT;
        const char *end;
        pid_t thread=!strncmp(tail,"/task/",6) ? decimal(tail+6,&end) : 0;
        if (thread && (!*end || *end=='/')) {
            thread=visible(mount,thread);
            if (!thread) return -ENOENT;
            length=snprintf(out,size,"/%d/task/%d%s",pid,thread,end);
        } else length=snprintf(out,size,"/%d%s",pid,tail);
    }
    return length<0 || (size_t)length>=size ? -ERANGE : 0;
}
static int inspect(struct md_filesystem *fs,pid_t native,int native_policy) {
    if (!fs->actor || !fs->store->identity) return 0;
    if (native==fs->actor->tid || native==fs->actor->tgid) return 1;
    struct md_task_credentials task;
    int r=md_task_registry_credentials(fs->tasks,native,&task);
    if (r) return r==-ESRCH ? !native_policy : r;
    const struct md_identity target=task.identity;
    const struct md_identity *from=fs->store->identity;
    int same=task.group==fs->actor->tgid;
    int privileged=md_identity_capable_in(from,target.user_namespace,CAP_SYS_PTRACE);
    int allowed=same || md_process_may_inspect(from,&target,1,task.access);
    md_task_credentials_release(&task);
    if (!native_policy || !allowed || same || privileged || task.retired) return allowed;
    /* proc io performs READ_FSCREDS at read time, including dumpability and
     * LSM checks, even for zombies whose namespace links are already gone. */
    char path[64],byte;
    snprintf(path,sizeof(path),"/proc/%d/io",native);
    int fd=open(path,O_RDONLY|O_CLOEXEC);
    int error=fd<0 ? errno : 0;
    if (fd>=0) { if (read(fd,&byte,1)<0) error=errno; close(fd); }
    return !error ? 1 : error==EACCES || error==EPERM || error==ENOENT || error==ESRCH ? 0 : -error;
}
static int permitted(struct md_filesystem *fs,const struct md_procfs_policy *p,pid_t native,unsigned minimum) {
    if (!p || !p->hidepid || (p->hidepid!=4 && (p->hidepid<minimum
            || (fs->store->identity && p->gid!=UINT32_MAX && md_identity_in_group(fs->store->identity,p->gid))))) return 1;
    return inspect(fs,native,1);
}
int md_procfs_check(struct md_filesystem *fs,const struct md_view_mount *mount,const char *path,unsigned minimum) {
    const struct md_procfs_policy *p=policy(mount);
    if (!p || !p->hidepid || strncmp(path,"/proc/",6)) return 0;
    const char *end;
    pid_t native=decimal(path+6,&end);
    if (!native || (*end && *end!='/')) return 0;
    int r=permitted(fs,p,native,minimum);
    return r<0 ? r : r ? 0 : p->hidepid==1 ? -EPERM : -ENOENT;
}
/* Returns a native TGID for a task directory, -1 for the proc root, else zero. */
static pid_t directory(const char *path) {
    if (!strcmp(path,"/proc")) return -1;
    if (strncmp(path,"/proc/",6)) return 0;
    const char *end;
    pid_t pid=decimal(path+6,&end);
    return pid && !strcmp(end,"/task") ? pid : 0;
}
static int process_directory(const char *path) {
    if (strncmp(path,"/proc/",6)) return 0;
    const char *end;
    if (!decimal(path+6,&end)) return 0;
    if (!*end) return 1;
    return !strncmp(end,"/task/",6) && decimal(end+6,&end) && !*end;
}
static const char *process_leaf(const char *path) {
    const char *leaf=strrchr(path,'/');
    if (!leaf || leaf==path) return NULL;
    char parent[PATH_MAX];
    size_t size=(size_t)(leaf-path);
    if (size>=sizeof(parent)) return NULL;
    memcpy(parent,path,size); parent[size]=0;
    return process_directory(parent) ? leaf+1 : NULL;
}
static int memory_access(struct md_filesystem *fs,const char *path,int directory) {
    const char *leaf=process_leaf(path);
    if (!leaf) return 0;
    int protected=directory ? !strcmp(leaf,"map_files")
        : !strcmp(leaf,"maps") || !strcmp(leaf,"smaps") || !strcmp(leaf,"smaps_rollup")
            || !strcmp(leaf,"numa_maps") || !strcmp(leaf,"environ") || !strcmp(leaf,"pagemap")
            || !strcmp(leaf,"auxv") || !strcmp(leaf,"mem");
    if (!protected) return 0;
    struct md_proc_path ref=md_proc_path_for(path,0,0);
    int allowed=inspect(fs,ref.process,0);
    return allowed<0 ? allowed : allowed ? 0 : -EACCES;
}
int md_procfs_content_access(struct md_filesystem *fs,const char *path) {
    return memory_access(fs,path,0);
}
static pthread_once_t native_proc_once=PTHREAD_ONCE_INIT;
static dev_t native_proc_device;
static int native_proc_error;
static void native_proc_identity(void) {
    struct stat st;
    if (stat("/proc",&st)) native_proc_error=-errno;
    else native_proc_device=st.st_dev;
}
int md_procfs_native_metadata(struct md_filesystem *fs,int fd,struct stat *st,char *path,size_t capacity) {
    if (!fs->actor) return 0;
    /* Guest mounts are virtual; the worker's native proc superblock never
     * changes. Classify the existing stat result without per-file probing. */
    pthread_once(&native_proc_once,native_proc_identity);
    if (native_proc_error) return native_proc_error;
    if (st->st_dev!=native_proc_device) return 0;
    int error=md_view_fdpath(fd,path,capacity);
    if (!error) error=md_procfs_ownership(fs,path,st);
    return error ? error : 1;
}
int md_procfs_ownership(struct md_filesystem *fs,const char *path,struct stat *st) {
    pid_t group=directory(path);
    unsigned threads=group>0 ? md_task_registry_thread_count(fs->tasks,group) : 0;
    if (threads && S_ISDIR(st->st_mode)) st->st_nlink=2+threads;
    struct md_proc_path ref=md_proc_path_for(path,0,0);
    if (ref.process) {
        struct md_task_credentials task;
        int error=md_task_registry_credentials(fs->tasks,ref.process,&task);
        if (error && error!=-ESRCH) return error;
        if (!error) {
            md_process_file_owner(&task.identity,task.access,st->st_mode,st->st_uid,&st->st_uid,&st->st_gid);
            md_task_credentials_release(&task);
        }
    }
    return 0;
}
int md_procfs_task_access(struct md_filesystem *fs,const char *path,const struct stat *st,
        unsigned mode,int effective) {
    if (mode&~7U) return -EINVAL;
    /* Linux's fd directory grants its own thread group access after set-ID.
     * Other process directories, including fdinfo, retain ordinary DAC. */
    const char *last=strrchr(path,'/');
    struct md_proc_path ref=md_proc_path_for(path,0,0);
    if (last && !strcmp(last,"/fd") && ref.process && fs->actor) {
        struct md_task_credentials target;
        int error=md_task_registry_credentials(fs->tasks,ref.process,&target);
        if (!error) {
            int own=target.group==fs->actor->tgid;
            md_task_credentials_release(&target);
            if (own) return 0;
        } else if (error!=-ESRCH) return error;
    }
    struct md_identity actor=md_identity_access(fs->store->identity,effective);
    return md_file_permissions(st,mode,&actor);
}
int md_procfs_noatime(struct md_filesystem *fs,int fd,const char *path) {
    struct stat st;
    if (fstat(fd,&st)) return -errno;
    int error=md_procfs_ownership(fs,path,&st);
    if (error) return error;
    return md_file_noatime(&st,fs->store->identity);
}
int md_procfs_open_access(struct md_filesystem *fs,const char *path,const struct stat *st,unsigned flags) {
    if (flags&O_PATH) return 0;
    unsigned mode=(flags&O_ACCMODE)==O_RDWR ? R_OK|W_OK : (flags&O_ACCMODE)==O_WRONLY ? W_OK : R_OK;
    if (flags&O_TRUNC) mode|=W_OK;
    int error=md_procfs_task_access(fs,path,st,mode,1);
    return error ? error : md_procfs_content_access(fs,path);
}
static int control(const char *path,const char *name) {
    /* Memory opens publish exposure before another process can retain the FD.
     * They need caller-side admission even through an explicitly mounted proc. */
    if (process_directory(path))
        return !strcmp(name,"uid_map") || !strcmp(name,"gid_map")
            || !strcmp(name,"setgroups") || !strcmp(name,"exe") || !strcmp(name,"cmdline")
            || !strcmp(name,"auxv") || !strcmp(name,"mem")
            || !strcmp(name,"root") || !strcmp(name,"cwd") || !strcmp(name,"mounts")
            || !strcmp(name,"mountinfo") || !strcmp(name,"cgroup")
            || !strcmp(name,"oom_score_adj") || !strcmp(name,"oom_adj");
    const char *last=strrchr(path,'/');
    if (last && (!strcmp(last,"/fd") || !strcmp(last,"/fdinfo") || !strcmp(last,"/ns") || !strcmp(last,"/net"))) {
        char parent[PATH_MAX];
        size_t size=(size_t)(last-path);
        memcpy(parent,path,size); parent[size]=0;
        /* Walk these directories inside the selected proc mount. Only their
         * entries need caller-side FD/control resolution; delegating the
         * whole suffix would lose openat2's traversal boundary. */
        if (process_directory(parent)) {
            if (!strcmp(last,"/net")) {
                char child[PATH_MAX];
                int n=snprintf(child,sizeof(child),"%s/%s",path,name);
                if (n<=0 || (size_t)n>=sizeof(child)) return 0;
                struct md_proc_path ref=md_proc_path_for(child,0,0);
                return md_proc_network_table(ref.kind==MD_PROC_FOREIGN ? ref.foreign_kind : ref.kind);
            }
            return strcmp(name,".") && strcmp(name,"..");
        }
    }
    if (!strncmp(path,"/proc/sys/",10)) {
        /* Only virtual controls cross the adapter boundary. Ordinary sysctl
         * directories and files must retain the selected proc mount. */
        char child[PATH_MAX];
        int n=snprintf(child,sizeof(child),"%s/%s",path,name);
        return n>0 && (size_t)n<sizeof(child)
            && md_proc_path_for(child,0,0).kind!=MD_PROC_NONE;
    }
    return !strcmp(path,"/proc") && (!strcmp(name,"cgroups") || md_proc_static_inode(name));
}
static int kernel_global(const char *name) {
    /* A mounted guest proc has no host reboot, raw kernel memory, kernel log,
     * kernel symbols, hardware, global timer/scheduler or key inventory provider.
     * Keep lookup and enumeration consistent;
     * never turn unrelated native permission failures into missing files. */
    for (size_t i=0;i<sizeof(kernel_nodes)/sizeof(*kernel_nodes);++i)
        if (!strcmp(name,kernel_nodes[i].name)) return 1;
    return 0;
}
int md_procfs_masked(const char *path) {
    return !strncmp(path,"/proc/",6) && kernel_global(path+6);
}
static int omitted(const struct md_view_mount *mount,const struct md_procfs_policy *p,const char *name) {
    return (!mount->provider && kernel_global(name)) || (p && !p->pressure && !strcmp(name,"pressure"))
        || (p && p->pidonly && !decimal(name,NULL)
        && strcmp(name,".") && strcmp(name,"..") && strcmp(name,"self") && strcmp(name,"thread-self"));
}
static const char network_conf[]="/proc/sys/net/ipv4/conf";
static const char network_conf6[]="/proc/sys/net/ipv6/conf";
static int host_network(struct md_filesystem *fs,const char *path) {
    static const char root[]="/proc/sys/net";
    return (!fs->actor || !md_network_namespace_managed(fs->actor->network))
        && !strncmp(path,root,sizeof(root)-1) && (!path[sizeof(root)-1] || path[sizeof(root)-1]=='/');
}
static const char *const network_ipv4_names[]={"ip_unprivileged_port_start","ping_group_range",
    "ip_local_port_range","ip_forward","tcp_keepalive_time","tcp_keepalive_intvl","tcp_keepalive_probes"};
static const char *const network_conf4_names[]={"route_localnet","rp_filter","src_valid_mark","arp_notify"};
static const char *const network_conf6_names[]={"forwarding","disable_ipv6","accept_dad","accept_ra",
    "autoconf","mtu","hop_limit","addr_gen_mode","use_tempaddr"};
static int network_directory(struct md_filesystem *fs,const char *path) {
    if (!fs->actor || !md_network_namespace_managed(fs->actor->network)) return 0;
    if (!strcmp(path,"/proc/sys/net")) return 5;
    if (!strcmp(path,"/proc/sys/net/bridge")) return 3;
    if (!strcmp(path,"/proc/sys/net/ipv6")) return 8;
    size_t length=sizeof(network_conf)-1;
    if (!strcmp(path,network_conf6)) return 6;
    if (!strncmp(path,network_conf6,length) && path[length]=='/') return 7;
    return !strcmp(path,network_conf) ? 1 : !strncmp(path,network_conf,length) && path[length]=='/' ? 2 : 0;
}
static int network_entry(struct md_filesystem *fs,const char *path,const char *name) {
    if (!strcmp(name,".") || !strcmp(name,"..")) return 1;
    int directory=network_directory(fs,path);
    if (!directory) return 1;
    if (directory==5) return 1;
    if (directory==8) return !strcmp(name,"conf") || !strcmp(name,"bindv6only");
    if (directory==3) return !strcmp(name,"bridge-nf-call-iptables") || !strcmp(name,"bridge-nf-call-ip6tables");
    uint64_t scope;
    int ipv6=directory==6 || directory==7;
    const char *interface=directory==1 || directory==6 ? name : path+sizeof(network_conf);
    int r=md_network_parameter_scope(fs->actor->network,ipv6 ? MD_NET_IPV6_FORWARD : MD_NET_ROUTE_LOCALNET,interface,&scope);
    if (r) return r==-ENOENT ? 0 : r;
    if (directory==1 || directory==6) return 1;
    const char *const *names=ipv6 ? network_conf6_names : network_conf4_names;
    size_t count=ipv6 ? sizeof(network_conf6_names)/sizeof(*names) : sizeof(network_conf4_names)/sizeof(*names);
    for (size_t i=0;i<count;++i) if (!strcmp(name,names[i])) return 1;
    return 0;
}
static int network_file(int root,const char *path) {
    int fd=openat(root,path,O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600);
    if (fd>=0) close(fd);
    else if (errno!=EEXIST) return -errno;
    return 0;
}
static int network_bridge_nodes(const struct md_view_mount *mount) {
    if (!mount->provider) return 0;
    int root=md_provider_tree_root(mount->provider);
    if (mkdirat(root,"sys/net/bridge",0755) && errno!=EEXIST) return -errno;
    int error=network_file(root,"sys/net/bridge/bridge-nf-call-iptables");
    return error ? error : network_file(root,"sys/net/bridge/bridge-nf-call-ip6tables");
}
static int network_tracking_nodes(const struct md_view_mount *mount) {
    if (!mount->provider) return 0;
    int root=md_provider_tree_root(mount->provider);
    if (mkdirat(root,"sys/net/netfilter",0755) && errno!=EEXIST) return -errno;
    int error=network_file(root,"sys/net/netfilter/nf_conntrack_tcp_timeout_established");
    return error ? error : network_file(root,"sys/net/netfilter/nf_conntrack_tcp_timeout_close_wait");
}
static int network_nodes(const struct md_view_mount *mount,const char *interface,int ipv6) {
    if (!mount->provider) return -EOPNOTSUPP;
    int root=md_provider_tree_root(mount->provider);
    const char *dirs[]={"sys/net",ipv6 ? "sys/net/ipv6" : "sys/net/ipv4",
        ipv6 ? "sys/net/ipv6/conf" : "sys/net/ipv4/conf"};
    for (unsigned i=0;i<sizeof(dirs)/sizeof(*dirs);++i)
        if (mkdirat(root,dirs[i],0755) && errno!=EEXIST) return -errno;
    const char *const ipv6_names[]={"bindv6only"};
    const char *const *globals=ipv6 ? ipv6_names : network_ipv4_names;
    size_t globals_count=ipv6 ? sizeof(ipv6_names)/sizeof(*ipv6_names)
        : sizeof(network_ipv4_names)/sizeof(*network_ipv4_names);
    for (size_t i=0;i<globals_count;++i) {
        char path[96];
        snprintf(path,sizeof(path),"sys/net/ipv%d/%s",ipv6 ? 6 : 4,globals[i]);
        int error=network_file(root,path);
        if (error) return error;
    }
    if (!interface) return 0;
    char path[96];
    snprintf(path,sizeof(path),"sys/net/ipv%d/conf/%s",ipv6 ? 6 : 4,interface);
    if (mkdirat(root,path,0755) && errno!=EEXIST) return -errno;
    const char *const *names=ipv6 ? network_conf6_names : network_conf4_names;
    size_t count=ipv6 ? sizeof(network_conf6_names)/sizeof(*names) : sizeof(network_conf4_names)/sizeof(*names);
    for (size_t i=0;i<count;++i) {
        snprintf(path,sizeof(path),"sys/net/ipv%d/conf/%s/%s",ipv6 ? 6 : 4,interface,names[i]);
        int error=network_file(root,path);
        if (error) return error;
    }
    return 0;
}
static int magic_link(enum md_proc_kind kind) {
    return kind==MD_PROC_FD || kind==MD_PROC_ROOT || kind==MD_PROC_CWD || kind==MD_PROC_EXE
        || (kind>=MD_PROC_NS_USER && kind<=MD_PROC_NS_CGROUP) || kind==MD_PROC_NS_UNSUPPORTED;
}
struct proc_lookup {
    struct md_provider_lookup base;
    struct md_fs_task_input input;
    char path[PATH_MAX];
};
static int proc_lookup_ready(struct md_provider_lookup *base) {
    return ((struct proc_lookup *)base)->input.completed;
}
static int proc_lookup_complete(struct md_provider_lookup *base) {
    struct proc_lookup *p=(void *)base;
    if (!p->input.completed) return -EPROTO;
    if (p->input.error) return p->input.error;
    int fd=p->input.descriptor; p->input.descriptor=-1; return fd;
}
static void proc_lookup_destroy(struct md_provider_lookup *base) {
    struct proc_lookup *p=(void *)base;
    if (p->input.descriptor>=0) close(p->input.descriptor);
    free(p);
}
int md_procfs_step(struct md_filesystem *fs,struct md_mount_namespace *view,struct md_view_mount *mount,int fd,
        const struct stat *parent,const char *name,const char *tail,int follow_final,uint64_t resolve,
        struct stat *metadata,struct md_provider_lookup **pending) {
    char path[PATH_MAX], mapped[32];
    int r=md_procfs_path(mount,fd,path,sizeof(path));
    if (r) return r;
    if (md_procfs_masked(path)) return -EACCES;
    if (network_directory(fs,path)) {
        r=network_entry(fs,path,name);
        if (r<=0) return r ? r : -ENOENT;
        int directory=network_directory(fs,path);
        if ((directory==1 || directory==6) && strcmp(name,".") && strcmp(name,"..")) {
            r=network_nodes(mount,name,directory==6);
            if (r) return r;
        } else if (network_directory(fs,path)==5 && !strcmp(name,"bridge")) {
            r=network_bridge_nodes(mount);
            if (r) return r;
        } else if (directory==5 && (!strcmp(name,"ipv4") || !strcmp(name,"ipv6"))) {
            r=network_nodes(mount,NULL,!strcmp(name,"ipv6"));
            if (r) return r;
        } else if (directory==5 && !strcmp(name,"netfilter")) {
            r=network_tracking_nodes(mount);
            if (r) return r;
        }
    } else if (!strcmp(path,"/proc/sys/net/ipv4") && !strcmp(name,"conf")
            && fs->actor && md_network_namespace_managed(fs->actor->network)) {
        r=network_nodes(mount,NULL,0);
        if (r) return r;
    }
    r=md_procfs_check(fs,mount,path,1);
    if (r) return r;
    if (strcmp(name,".") && strcmp(name,"..")) {
        r=memory_access(fs,path,1);
        if (r) return r;
    }
    pid_t group=directory(path);
    int no_links=md_view_nosymfollow(mount) || (resolve&RESOLVE_NO_SYMLINKS);
    const struct md_procfs_policy *p=policy(mount);
    if (group==-1 && omitted(mount,p,name)) return -ENOENT;
    if (group==-1 && !strcmp(name,"self")) {
        if (follow_final || *tail) {
            if (no_links) return -ELOOP;
            if (!fs->actor) return -ESRCH;
            pid_t native=fs->actor->tgid;
            if (!native || !visible(mount,native)) return -ENOENT;
            snprintf(mapped,sizeof(mapped),"%d",native); name=mapped;
        }
    } else if (group && mount->pid_namespace && decimal(name,NULL)) {
        struct md_pid_identity *id=md_pid_namespace_lookup(mount->pid_namespace,decimal(name,NULL));
        pid_t native=md_pid_identity_native(id);
        if (group>0 && md_pid_identity_group(id)!=group) native=0;
        md_pid_identity_release(id);
        if (!native) return -ENOENT;
        snprintf(mapped,sizeof(mapped),"%d",native); name=mapped;
    }
    if (group && p && p->hidepid==4 && decimal(name,NULL)) {
        int allowed=permitted(fs,p,decimal(name,NULL),1);
        if (allowed<=0) return allowed ? allowed : -ENOENT;
    }
    struct md_inode_boundary *boundary=fs->store->boundary;
    int content=process_directory(path) && (!strcmp(name,"status") || !strcmp(name,"stat") || !strcmp(name,"limits")
        || !strcmp(name,"io") || !strcmp(name,"wchan") || !strcmp(name,"syscall") || !strcmp(name,"stack"));
    if ((control(path,name) || content) && boundary && boundary->path) {
        boundary->proc_namespace=md_pid_namespace_inode(mount->pid_namespace);
        int n=snprintf(boundary->path,boundary->capacity,"%s/%s%s",path,name,tail);
        if (n<0 || (size_t)n>=boundary->capacity) return -ENAMETOOLONG;
        /* Namespace-owned controls stay on the worker so their open
         * descriptions can retain the selected mount's live policy. */
        struct md_proc_path selected=md_proc_path_for(boundary->path,0,0);
        enum md_proc_kind kind=selected.kind==MD_PROC_FOREIGN ? selected.foreign_kind : selected.kind;
        if (md_proc_file_task((enum md_proc_file_kind)(kind-MD_PROC_UID_MAP))) {
            if (*tail) return -ENOTDIR;
            md_fs_anchor_release(boundary->proc_entry); boundary->proc_entry=NULL;
            int error=md_view_anchor_child(fs,view,mount,fd,parent,name,&boundary->proc_entry);
            return error ? error : -EREMOTE;
        }
        if (no_links || (resolve&RESOLVE_NO_MAGICLINKS)) {
            if (magic_link(kind) && (follow_final || (selected.tail && *selected.tail))) return -ELOOP;
            /* Unclassified task-affine suffixes cannot discard the selected
             * mount's traversal policy when delegated to a native provider. */
            if (kind==MD_PROC_NONE && *tail) return -EOPNOTSUPP;
        }
        int sysctl=md_proc_file_sysctl((enum md_proc_file_kind)(kind-MD_PROC_UID_MAP));
        int link_inode=magic_link(kind) && !follow_final && selected.tail && !*selected.tail;
        boundary->proc_link_mount=link_inode ? mount->id : 0;
        /* Namespace links exist in the guest even when CONFIG_*_NS is absent
         * on Android. Their no-follow metadata belongs to the same provider
         * as their target, never to a coincidentally present host proc link. */
        if (kind>=MD_PROC_NS_USER && kind<=MD_PROC_NS_CGROUP) return -EREMOTE;
        if (link_inode && boundary->proc_link_descriptor) {
            int captured=(int)(boundary->proc_link_descriptor-1);
            char actual[PATH_MAX];
            int error=md_view_fdpath(captured,actual,sizeof(actual));
            if (error) return error;
            struct stat st;
            if (fstat(captured,&st)) return -errno;
            if (!S_ISLNK(st.st_mode) || strcmp(actual,boundary->path)) return -ESTALE;
            int copy=fcntl(captured,F_DUPFD_CLOEXEC,3);
            if (copy>=0) *metadata=st;
            return copy<0 ? -errno : copy;
        }
        if (!link_inode && !sysctl && kind!=MD_PROC_MAX_USER_NAMESPACES && !md_proc_static(kind) && kind!=MD_PROC_KERNEL_POLICY
                && (!fs->actor || !md_network_namespace_managed(fs->actor->network)
                || !md_proc_network_sysctl(kind))) return -EREMOTE;
    }
    int next;
    if (mount->provider) {
        int host=host_network(fs,path) || (!strcmp(path,"/proc/sys") && !strcmp(name,"net")
            && host_network(fs,"/proc/sys/net"));
        next=host ? md_provider_tree_host_step(mount->provider,fd,name)
            : md_provider_tree_step(mount->provider,fd,name);
    }
    else { next=openat(fd,name,O_PATH|O_NOFOLLOW|O_CLOEXEC); if (next<0) next=-errno; }
    if (next>=0 && fstat(next,metadata)) { int error=-errno; close(next); next=error; }
    /* Native admission is the common path. A protected launch member can
     * instead capture this exact step under guest inspection authority; the
     * saved walker still owns the mount, suffix and openat2 boundary. */
    if ((next==-ENOENT || next==-EPERM || next==-EACCES) && fs->actor
            && boundary && strcmp(name,"..")) {
        char target[PATH_MAX];
        int n=!strcmp(name,".") ? snprintf(target,sizeof(target),"%s",path)
            : snprintf(target,sizeof(target),"%s/%s",path,name);
        if (n<0 || (size_t)n>=sizeof(target)) return -ENAMETOOLONG;
        const char *end;
        pid_t native=!strncmp(target,"/proc/",6) ? decimal(target+6,&end) : 0;
        if (!native || (*end && *end!='/')) return next;
        struct md_task_credentials task;
        int error=md_task_registry_credentials(fs->tasks,native,&task);
        if (error) return error==-ESRCH ? next : error;
        /* Process dentries are public even when the image is protected.
         * Capturing only an O_PATH dentry does not admit exe/mem/fd contents;
         * the resumed walk still checks the selected leaf's guest policy. */
        int allowed=md_proc_public_task_dentry(md_proc_path_for(target,0,0))
            || task.group==fs->actor->tgid || (fs->store->identity
            && md_identity_capable_in(fs->store->identity,task.identity.user_namespace,CAP_SYS_PTRACE));
        md_task_credentials_release(&task);
        if (!allowed || task.retired) return next;
        if (!pending || !boundary->task_capture) { boundary->requires_task_capture=1; return next; }
        struct proc_lookup *lookup=calloc(1,sizeof(*lookup));
        if (!lookup) return -ENOMEM;
        memcpy(lookup->path,target,(size_t)n+1);
        lookup->input=(struct md_fs_task_input){.request={.path=lookup->path,
            .flags=O_PATH|O_NOFOLLOW|O_CLOEXEC},.subject={native,task.generation},.descriptor=-1};
        lookup->base=(struct md_provider_lookup){.task_input=&lookup->input,
            .metadata=&lookup->input.metadata,
            .ready=proc_lookup_ready,.complete=proc_lookup_complete,.destroy=proc_lookup_destroy};
        *pending=&lookup->base; return -EINPROGRESS;
    }
    return next;
}
ssize_t md_procfs_readlink(struct md_filesystem *fs,const struct md_view_mount *mount,
        int fd,void *data,size_t capacity) {
    char path[PATH_MAX], value[64];
    int r=md_procfs_path(mount,fd,path,sizeof(path));
    if (r) return r;
    char *leaf=strrchr(path,'/');
    if (leaf) {
        *leaf=0; r=memory_access(fs,path,1); *leaf='/';
        if (r) return r;
    }
    if (strcmp(path,"/proc/self") && strcmp(path,"/proc/thread-self")) {
        struct md_proc_path ref=md_proc_path_for(path,0,0);
        enum md_proc_kind kind=ref.kind==MD_PROC_FOREIGN ? ref.foreign_kind : ref.kind;
        struct md_inode_boundary *boundary=fs->store->boundary;
        if (magic_link(kind) && boundary && boundary->path) {
            /* The link inode retains this mount for stat/open(NO_FOLLOW).
             * Only its target text needs the caller's virtual FD/namespace. */
            size_t length=strlen(path)+1;
            if (length>boundary->capacity) return -ENAMETOOLONG;
            memcpy(boundary->path,path,length);
            boundary->proc_namespace=md_pid_namespace_inode(mount->pid_namespace);
            return -EREMOTE;
        }
        ssize_t n=readlinkat(fd,"",data,capacity); return n<0 ? -errno : n;
    }
    if (!fs->actor) return -ESRCH;
    pid_t group=visible(mount,fs->actor->tgid);
    if (!group) return -ENOENT;
    int n=!strcmp(path,"/proc/self") ? snprintf(value,sizeof(value),"%d",group)
        : snprintf(value,sizeof(value),"%d/task/%d",group,
            visible(mount,fs->actor->tid));
    size_t bytes=(size_t)n<capacity ? (size_t)n : capacity;
    memcpy(data,value,bytes); return (ssize_t)bytes;
}
ssize_t md_procfs_getdents(struct md_filesystem *fs,const struct md_view_mount *mount,int fd,void *data,size_t capacity) {
    char path[PATH_MAX];
    int error=md_procfs_path(mount,fd,path,sizeof(path));
    if (error) return error;
    if (md_procfs_masked(path)) return -EACCES;
    error=md_procfs_check(fs,mount,path,1);
    if (error) return error;
    error=memory_access(fs,path,1);
    if (error) return error;
    struct md_proc_path ref=md_proc_path_for(path,0,0);
    enum md_proc_kind kind=ref.kind==MD_PROC_FOREIGN ? ref.foreign_kind : ref.kind;
    struct md_task_credentials task;
    if (kind==MD_PROC_NS_DIRECTORY && !md_task_registry_credentials(fs->tasks,ref.process,&task)) {
        md_task_credentials_release(&task);
        struct stat st,current;
        if (fstat(fd,&st) || stat(path,&current)) return -errno;
        if (st.st_dev!=current.st_dev || st.st_ino!=current.st_ino) return -ENOENT;
        int64_t position=lseek(fd,0,SEEK_CUR);
        if (position<0) return -errno;
        ssize_t size=md_proc_file_namespace_entries(st.st_ino,&position,data,capacity);
        if (size>=0 && lseek(fd,position,SEEK_SET)<0) return -errno;
        return size;
    }
    const struct md_procfs_policy *p=policy(mount);
    pid_t group=directory(path);
    int net=network_directory(fs,path);
    if (!strcmp(path,"/proc/sys/net") && fs->actor && md_network_namespace_managed(fs->actor->network)) {
        if (mount->provider) {
            error=network_nodes(mount,NULL,0);
            if (!error) error=network_nodes(mount,NULL,1);
            if (error) return error;
        }
        error=network_bridge_nodes(mount);
        if (!error) error=network_tracking_nodes(mount);
        if (error) return error;
    }
    if (net==1 || net==6) {
        struct md_network_link links[256];
        int count=md_network_link_list(fs->actor->network,links,256);
        if (count<0) return count;
        for (int i=-2;i<count;++i) {
            error=network_nodes(mount,i==-2 ? "all" : i==-1 ? "default" : links[i].name,net==6);
            if (error) return error;
        }
    }
    if ((!group || (group>0 && !mount->pid_namespace)) && !net) {
        ssize_t n=mount->provider ? (host_network(fs,path)
            ? md_provider_tree_host_getdents(mount->provider,fd,data,capacity)
            : md_provider_tree_getdents(mount->provider,fd,data,capacity))
            : syscall(SYS_getdents64,fd,data,capacity);
        if (n<0 && !mount->provider) n=-errno;
        struct md_inode_boundary *boundary=fs->store->boundary;
        if ((n==-EACCES || n==-EPERM || n==-ENOENT) && boundary && boundary->path) {
            /* No namespace remapping or filtering is needed here. The native
             * cursor and access check belong to the actual reading task. */
            size_t length=strlen(path)+1;
            if (length>boundary->capacity) return -ENAMETOOLONG;
            memcpy(boundary->path,path,length);
            boundary->proc_namespace=md_pid_namespace_inode(mount->pid_namespace);
            return -EREMOTE;
        }
        return n;
    }
    /* Repack into caller storage: a namespace number may have more digits
     * than its native PID. The existing OFD turn owns cursor publication. */
    _Alignas(struct dirent) char input[4096];
    for (;;) {
        off_t cursor=lseek(fd,0,SEEK_CUR);
        if (cursor<0) return -errno;
        size_t size=capacity<sizeof(input) ? capacity : sizeof(input);
        ssize_t n=mount->provider ? md_provider_tree_getdents(mount->provider,fd,input,size)
            : syscall(SYS_getdents64,fd,input,size);
        if (n<0) return mount->provider ? n : -errno;
        if (!n) return n;
        size_t read=0,written=0;
        while (read<(size_t)n) {
            struct md_directory_entry entry;
            if (!md_directory_record_read(input+read,(size_t)n-read,&entry)) return -EIO;
            size_t length=entry.size;
            char mapped[32]; const char *name=input+read+offsetof(struct md_directory_entry,name);
            pid_t native=decimal(name,NULL), number=native;
            if (!net && native && mount->pid_namespace) {
                number=0;
                struct md_pid_identity *id=md_pid_namespace_find(mount->pid_namespace,native);
                if (md_pid_identity_group(id)==(group>0 ? group : native))
                    number=md_pid_identity_number(id,mount->pid_namespace);
                md_pid_identity_release(id);
                if (number) {
                    snprintf(mapped,sizeof(mapped),"%d",number); name=mapped;
                }
            }
            int allowed=net ? network_entry(fs,path,name) : native && number ? permitted(fs,p,native,2) : 1;
            if (allowed<0) return allowed;
            if (allowed && (!native || number) && !(group==-1 && omitted(mount,p,name))) {
                ino_t inode=group==-1 && !native ? md_proc_static_inode(name) : 0;
                size_t record=md_directory_record_write((char *)data+written,capacity-written,
                    name,strlen(name),inode ? (uint64_t)inode : entry.inode,entry.next,entry.type);
                if (!record) {
                    if (lseek(fd,cursor,SEEK_SET)<0) return -errno;
                    return written ? (ssize_t)written : -EINVAL;
                }
                written+=record;
            }
            cursor=entry.next;
            read+=length;
        }
        if (written) return (ssize_t)written;
        /* Kernel directory cookies advance while omitted entries are skipped;
         * the shared directory turn owns rollback until output publication. */
    }
}
