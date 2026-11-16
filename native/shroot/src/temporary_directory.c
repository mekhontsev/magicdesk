#define _GNU_SOURCE
#include "temporary_directory.h"
#include "private_tree.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/file.h>
#include <sys/random.h>
#include <sys/stat.h>
#include <unistd.h>

static const char prefix[]=".md-transient-", marker[]=MD_TEMPORARY_DIRECTORY_MARKER;
struct identity { uint32_t magic, version; uint64_t device, inode; };
static int temporary_name(const char *name) {
    return strlen(name)==sizeof(prefix)-1+32 && !strncmp(name,prefix,sizeof(prefix)-1)
        && strspn(name+sizeof(prefix)-1,"0123456789abcdef")==32;
}
static int owned(int fd, struct stat *st) {
    if (fstat(fd,st)) return -errno;
    return S_ISDIR(st->st_mode) && st->st_uid==getuid() && (st->st_mode&0777)==0700 ? 0 : -EACCES;
}
int md_temporary_directory_check(int root) {
    struct stat st; int r=owned(root,&st);
    if (r) return r;
    int fd=openat(root,marker,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if (fd<0) return -errno;
    struct identity data;
    ssize_t n=pread(fd,&data,sizeof(data),0);
    if (n<0) r=-errno;
    else if (n!=sizeof(data) || data.magic!=0x4d445444U || data.version!=1
            || data.device!=(uint64_t)st.st_dev || data.inode!=(uint64_t)st.st_ino) r=-ESTALE;
    close(fd); return r;
}
static int collect(int parent) {
    int fd=openat(parent,".",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    if (fd<0) return -errno;
    DIR *dir=fdopendir(fd);
    if (!dir) { int r=-errno; close(fd); return r; }
    int r=0;
    while (!r) {
        errno=0; struct dirent *entry=readdir(dir);
        if (!entry) { if (errno) r=-errno; break; }
        const char *name=entry->d_name;
        if (!temporary_name(name)) continue;
        int root=openat(parent,name,O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
        if (root<0) { if (errno==ENOENT) continue; r=-errno; break; }
        struct stat st;
        r=owned(root,&st);
        if (!r && flock(root,LOCK_EX|LOCK_NB)) {
            if (errno!=EWOULDBLOCK) r=-errno;
        } else if (!r) {
            /* The reserved name, ownership and exclusive lease authorize
             * retirement. Markers validate live imports, not crash debris:
             * temporary writes need not survive a reboot, even partially. */
            r=md_private_tree_discard(parent,name);
        }
        close(root);
    }
    closedir(dir); return r;
}
static int parent_lock(int parent) {
    int fd=openat(parent,".",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    if (fd<0) return -errno;
    /* EVENT_WAIT: another admission/collection releases its directory lock;
     * interruption cancels before creation, without replay or a settling wait. */
    if (flock(fd,LOCK_EX)) { int r=-errno; close(fd); return r; }
    return fd;
}
int md_temporary_directory_collect(int parent) {
    int fd=parent_lock(parent);
    if (fd<0) return fd;
    int r=collect(fd); close(fd); return r;
}
int md_temporary_directory_new(int parent) {
    int gate=parent_lock(parent);
    if (gate<0) return gate;
    int r=collect(gate), root=-1, created=0;
    unsigned char random[16]; char name[sizeof(prefix)+32];
    if (!r) {
        ssize_t n=getrandom(random,sizeof(random),0);
        if (n!=sizeof(random)) r=n<0 ? -errno : -EIO;
    }
    if (!r) {
        memcpy(name,prefix,sizeof(prefix)-1);
        for (unsigned i=0;i<sizeof(random);++i) {
            name[sizeof(prefix)-1+2*i]="0123456789abcdef"[random[i]>>4];
            name[sizeof(prefix)+2*i]="0123456789abcdef"[random[i]&15];
        }
        name[sizeof(name)-1]=0;
        if (mkdirat(gate,name,0700)) r=-errno;
        else created=1;
    }
    if (!r && (root=openat(gate,name,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC))<0) r=-errno;
    if (!r && flock(root,LOCK_SH|LOCK_NB)) r=-errno;
    struct stat st;
    if (!r) r=owned(root,&st);
    if (!r) {
        int fd=openat(root,marker,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0400);
        if (fd<0) r=-errno;
        else {
            struct identity data={0x4d445444U,1,(uint64_t)st.st_dev,(uint64_t)st.st_ino};
            ssize_t n=write(fd,&data,sizeof(data));
            if (n!=sizeof(data)) r=n<0 ? -errno : -EIO;
            close(fd);
        }
    }
    if (r) {
        if (root>=0) close(root);
        if (created) md_private_tree_discard(gate,name);
    }
    close(gate); return r ? r : root;
}
void md_temporary_directory_release(int root) {
    int collector=openat(root,".",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    close(root);
    if (collector<0) return;
    struct stat retained;
    if (!owned(collector,&retained) && !flock(collector,LOCK_EX|LOCK_NB)) {
        char proc[64], path[PATH_MAX];
        snprintf(proc,sizeof(proc),"/proc/self/fd/%d",collector);
        ssize_t n=readlink(proc,path,sizeof(path)-1);
        if (n>0 && n<(ssize_t)sizeof(path)-1) {
            path[n]=0;
            char *name=strrchr(path,'/');
            int parent=openat(collector,"..",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
            struct stat named;
            if (name && temporary_name(name+1) && parent>=0
                    && !fstatat(parent,name+1,&named,AT_SYMLINK_NOFOLLOW)
                    && retained.st_dev==named.st_dev && retained.st_ino==named.st_ino)
                md_private_tree_discard(parent,name+1);
            if (parent>=0) close(parent);
        }
    }
    close(collector);
}
