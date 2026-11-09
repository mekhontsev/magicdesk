#define _GNU_SOURCE
#include "devpts_view.h"
#include "directory_record.h"
#include "file_permissions.h"
#include "file_open.h"
#include "temporary_directory.h"
#include <stdatomic.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <unistd.h>

/* Worker-owned directory membership. Kernel PTYs retain all terminal IO and
 * job-control semantics. No master/slave data FD is retained by this view. */
struct attributes { uid_t uid; gid_t gid; mode_t mode; };
struct entry { struct entry *next; dev_t device; ino_t inode; unsigned number; struct attributes attributes; };
struct options {
    mode_t mode,ptmxmode;
    uid_t uid; gid_t gid;
    int fixed_uid,fixed_gid;
    unsigned maximum;
};
struct md_devpts {
    atomic_uint references;
    struct entry *entries;
    struct options options;
    struct attributes directory,ptmx;
    struct md_user_namespace *owner;
    struct md_mount_policy super_policy;
    int storage,root;
};
static int parse(const char *options,const struct md_identity *id,struct options *p) {
    *p=(struct options){.mode=0600,.maximum=1U<<20};
    for (const char *at=options;at && *at;) {
        const char *end=strchr(at,','); if (!end) end=at+strlen(at);
        size_t n=(size_t)(end-at);
        if (!n) { at=*end ? end+1 : end; continue; }
        if (n==11 && !memcmp(at,"newinstance",11)) { at=*end ? end+1 : end; continue; }
        const char *equal=memchr(at,'=',n);
        if (!equal || equal+1==end) return -EINVAL;
        unsigned key=(unsigned)(equal-at),base=10,value=0;
        int mode=key==4 && !memcmp(at,"mode",4),ptmx=key==8 && !memcmp(at,"ptmxmode",8);
        int uid=key==3 && !memcmp(at,"uid",3),gid=key==3 && !memcmp(at,"gid",3);
        int maximum=key==3 && !memcmp(at,"max",3);
        if (!mode && !ptmx && !uid && !gid && !maximum) return -EINVAL;
        if (mode || ptmx) base=8;
        for (const char *digit=equal+1;digit<end;++digit) {
            unsigned d=(unsigned char)*digit-'0';
            if (d>=base || value>(UINT32_MAX-d)/base) return -EINVAL;
            value=value*base+d;
        }
        if (mode || ptmx) {
            if (value&~0777U) return -EINVAL;
            if (mode) p->mode=value; else p->ptmxmode=value;
        } else if (maximum) {
            if (value>(1U<<20)) return -EINVAL;
            p->maximum=value;
        } else {
            value=md_user_namespace_into(id->user_namespace,gid,value);
            if (value==UINT32_MAX) return -EINVAL;
            if (uid) { p->uid=value; p->fixed_uid=1; } else { p->gid=value; p->fixed_gid=1; }
        }
        at=*end ? end+1 : end;
    }
    return 0;
}
int md_devpts_reconfigure(struct md_devpts *p,const char *options,const struct md_identity *id) {
    if (!id || !md_identity_capable_in(id,p->owner,CAP_SYS_ADMIN)) return -EPERM;
    struct options next;
    int r=parse(options,id,&next);
    if (r) return r;
    /* Remount changes future slaves and the existing ptmx mode, not the
     * attributes of already allocated slave terminals. */
    p->options=next; p->ptmx.mode=next.ptmxmode; return 0;
}
struct md_user_namespace *md_devpts_owner(struct md_devpts *p) { return p->owner; }
struct md_mount_policy *md_devpts_super_policy(struct md_devpts *p) { return &p->super_policy; }
struct md_devpts *md_devpts_new(int parent,const char *options,const struct md_identity *id) {
    if (!id) { errno=EPERM; return NULL; }
    struct md_devpts *p=calloc(1,sizeof(*p)); if (!p) return NULL;
    atomic_init(&p->references,1);
    p->storage=p->root=-1;
    int r=parse(options,id,&p->options);
    if (r) { errno=-r; goto failed; }
    p->directory=(struct attributes){id->uid.fs,id->gid.fs,0755};
    p->ptmx=(struct attributes){id->uid.fs,id->gid.fs,p->options.ptmxmode};
    p->storage=md_temporary_directory_new(parent);
    if (p->storage<0) { errno=-p->storage; goto failed; }
    if (mkdirat(p->storage,"pts",0700)) goto failed;
    p->root=openat(p->storage,"pts",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    if (p->root<0) goto failed;
    int node=openat(p->root,"ptmx",O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600);
    if (node<0) goto failed;
    close(node); p->owner=md_user_namespace_retain(id->user_namespace); return p;
failed:
    { int error=errno;
      if (p->root>=0) close(p->root);
      if (p->storage>=0) md_temporary_directory_release(p->storage);
      free(p); errno=error; return NULL; }
}
int md_devpts_root(struct md_devpts *p) { return p->root; }
struct md_devpts *md_devpts_retain(struct md_devpts *p) {
    if (p) atomic_fetch_add_explicit(&p->references,1,memory_order_relaxed); return p;
}
void md_devpts_release(struct md_devpts *p) {
    if (!p || atomic_fetch_sub_explicit(&p->references,1,memory_order_acq_rel)!=1) return;
    while (p->entries) { struct entry *e=p->entries; p->entries=e->next; free(e); }
    md_user_namespace_release(p->owner); md_mount_policy_close(&p->super_policy);
    close(p->root); md_temporary_directory_release(p->storage); free(p);
}
static int ptmx(const struct stat *st) { return S_ISCHR(st->st_mode) && major(st->st_rdev)==5 && minor(st->st_rdev)==2; }
static struct entry *find(struct md_devpts *p,const struct stat *st) {
    for (struct entry *e=p->entries;e;e=e->next) if (e->device==st->st_dev && e->inode==st->st_ino) return e;
    return NULL;
}
static struct attributes *attributes(struct md_devpts *p,const struct stat *st) {
    if (S_ISDIR(st->st_mode)) return &p->directory;
    if (ptmx(st)) return &p->ptmx;
    struct entry *e=find(p,st); return e ? &e->attributes : NULL;
}
int md_devpts_name(struct md_devpts *p,int fd,char *name,size_t size) {
    struct stat st;
    if (fstat(fd,&st)) return -errno;
    struct entry *e=find(p,&st);
    int n=S_ISDIR(st.st_mode) ? snprintf(name,size,".") : ptmx(&st) ? snprintf(name,size,"ptmx")
        : e ? snprintf(name,size,"%u",e->number) : -1;
    return n<0 ? -ENOENT : (size_t)n>=size ? -ENAMETOOLONG : 0;
}
int md_devpts_stat(struct md_devpts *p,int fd,struct stat *st) {
    if (!p) return -EINVAL;
    if (fstat(fd,st)) return -errno;
    struct attributes *a=attributes(p,st); if (!a) return -ENOENT;
    st->st_mode=(st->st_mode&S_IFMT)|a->mode; st->st_uid=a->uid; st->st_gid=a->gid; return 0;
}
int md_devpts_access(struct md_devpts *p,int fd,int mode,const struct md_identity *id) {
    struct stat st; int r=md_devpts_stat(p,fd,&st); return r ? r : md_file_permissions(&st,mode,id);
}
int md_devpts_step(struct md_devpts *p,int directory,const char *name) {
    struct stat directory_stat;
    if (fstat(directory,&directory_stat)) return -errno;
    if (!S_ISDIR(directory_stat.st_mode)) return -ENOTDIR;
    if (strchr(name,'/')) return -EINVAL;
    int fd;
    if (!strcmp(name,".") || !strcmp(name,"..")) fd=openat(p->root,".",O_PATH|O_DIRECTORY|O_CLOEXEC);
    else if (!strcmp(name,"ptmx")) fd=open("/dev/ptmx",O_PATH|O_CLOEXEC);
    else {
        struct entry *e=p->entries;
        char number[32];
        for (;e;e=e->next) { snprintf(number,sizeof(number),"%u",e->number); if (!strcmp(name,number)) break; }
        if (!e) return -ENOENT;
        char path[64]; snprintf(path,sizeof(path),"/dev/pts/%u",e->number);
        fd=open(path,O_PATH|O_NOFOLLOW|O_CLOEXEC);
        struct stat st;
        if (fd>=0 && (fstat(fd,&st) || st.st_dev!=e->device || st.st_ino!=e->inode)) { close(fd); return -ENOENT; }
    }
    if (fd<0) return -errno;
    struct stat st; int r=md_devpts_stat(p,fd,&st);
    if (r) close(fd); return r ? r : fd;
}
int md_devpts_open(struct md_devpts *p,int fd,int flags,const struct md_identity *id) {
    struct stat st; int r=md_devpts_stat(p,fd,&st); if (r) return r;
    if (!(flags&O_PATH)) {
        r=md_file_permissions(&st,(flags&O_ACCMODE)==O_RDWR ? R_OK|W_OK : (flags&O_ACCMODE)==O_WRONLY ? W_OK : R_OK,id);
        if (r) return r;
    }
    char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",fd);
    int opened=open(path,md_open_endpoint_flags(flags)|O_CLOEXEC);
    if (opened<0) return -errno;
    if (!(flags&O_PATH) && ptmx(&st)) {
        unsigned number;
        if (ioctl(opened,TIOCGPTN,&number)) { r=-errno; goto failed; }
        /* devpts max bounds the allocated index. Native numbering deliberately
         * remains shared with the host; never publish an out-of-range PTY. */
        if (number>=p->options.maximum) { r=-ENOSPC; goto failed; }
        snprintf(path,sizeof(path),"/dev/pts/%u",number);
        struct stat slave;
        if (stat(path,&slave)) { r=-errno; goto failed; }
        struct entry *e=p->entries;
        while (e && e->number!=number) e=e->next;
        if (!e) {
            e=calloc(1,sizeof(*e)); if (!e) { r=-ENOMEM; goto failed; }
            e->next=p->entries; p->entries=e;
        }
        e->number=number; e->device=slave.st_dev; e->inode=slave.st_ino;
        e->attributes=(struct attributes){p->options.fixed_uid ? p->options.uid : id->uid.fs,
            p->options.fixed_gid ? p->options.gid : id->gid.fs,p->options.mode};
        char name[32]; snprintf(name,sizeof(name),"%u",number);
        int node=openat(p->root,name,O_CREAT|O_WRONLY|O_NOFOLLOW|O_CLOEXEC,0600);
        if (node<0) { r=-errno; goto failed; }
        close(node);
    }
    return opened;
failed:
    close(opened); return r;
}
int md_devpts_chmod(struct md_devpts *p,int fd,mode_t mode,const struct md_identity *id) {
    struct stat st; int r=md_devpts_stat(p,fd,&st); if (r) return r;
    if (id->uid.fs!=st.st_uid && !md_identity_file_capable(id,st.st_uid,st.st_gid,CAP_FOWNER)) return -EPERM;
    if (!md_identity_in_group(id,st.st_gid)
            && !md_identity_file_capable(id,st.st_uid,st.st_gid,CAP_FSETID)) mode&=~S_ISGID;
    attributes(p,&st)->mode=mode&07777;
    return 0;
}
int md_devpts_chown(struct md_devpts *p,int fd,uint32_t uid,uint32_t gid,const struct md_identity *id) {
    struct stat st; int r=md_devpts_stat(p,fd,&st); if (r) return r;
    if (uid==UINT32_MAX) uid=st.st_uid;
    if (gid==UINT32_MAX) gid=st.st_gid;
    if (!md_identity_file_capable(id,st.st_uid,st.st_gid,CAP_CHOWN)
            && !(id->uid.fs==st.st_uid && uid==st.st_uid && (gid==st.st_gid || md_identity_in_group(id,gid)))) return -EPERM;
    struct attributes *a=attributes(p,&st); a->uid=uid; a->gid=gid; return 0;
}
ssize_t md_devpts_getdents(struct md_devpts *p,int fd,void *data,size_t capacity) {
    for (;;) {
        ssize_t n=syscall(SYS_getdents64,fd,data,capacity); if (n<=0) return n<0 ? -errno : 0;
        size_t read=0,written=0;
        while (read<(size_t)n) {
            struct md_directory_entry entry;
            if (!md_directory_record_read((char *)data+read,(size_t)n-read,&entry)) return -EIO;
            size_t size=entry.size;
            int next=md_devpts_step(p,fd,(char *)data+read+offsetof(struct md_directory_entry,name));
            if (next>=0) {
                struct stat st;
                if (fstat(next,&st)) { int error=-errno; close(next); return error; }
                close(next); entry.inode=st.st_ino; entry.type=S_ISDIR(st.st_mode) ? DT_DIR : DT_CHR;
                memcpy((char *)data+read,&entry,offsetof(struct md_directory_entry,name));
                memmove((char *)data+written,(char *)data+read,size); written+=size;
            }
            else if (next!=-ENOENT) return next;
            read+=size;
        }
        if (written) return (ssize_t)written;
    }
}
