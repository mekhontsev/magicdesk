#define _GNU_SOURCE
#include "image_maintenance.h"
#include "storage_paths.h"
#include "storage_database.h"
#include "inode_schema.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/fs.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

struct store_lease { int fd; struct store_lease *next; };
static int retain(int fd,struct store_lease **leases) {
    int r=flock(fd,LOCK_EX|LOCK_NB) ? (errno==EWOULDBLOCK ? -EBUSY : -errno) : 0;
    struct store_lease *lease=r ? NULL : malloc(sizeof(*lease));
    if (!r && !lease) r=-ENOMEM;
    if (r) { close(fd); return r; }
    *lease=(struct store_lease){fd,*leases}; *leases=lease;
    return 0;
}
static int retain_group(int library,const char *name,struct store_lease **leases) {
    int fd=openat(library,name,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if (fd<0) return -errno;
    /* Publishers hold the group during staging admission and the stage until
     * commit. Retain both levels so no new store escapes the offline barrier. */
    int r=retain(fd,leases);
    if (r) return r;
    int scan=fcntl(fd,F_DUPFD_CLOEXEC,3);
    if (scan<0) return -errno;
    DIR *group=fdopendir(scan);
    if (!group) { r=-errno; close(scan); return r; }
    for (;;) {
        errno=0;
        struct dirent *entry=readdir(group);
        if (!entry) { if (errno) r=-errno; break; }
        if (!strcmp(entry->d_name,".") || !strcmp(entry->d_name,"..")) continue;
        int child=openat(fd,entry->d_name,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
        if (child<0) { r=-errno; break; }
        r=retain(child,leases);
        if (r) break;
    }
    closedir(group); return r;
}

static int contained_sources(sqlite3 *db,const char *store,const char *library) {
    sqlite3_stmt *query=NULL;
    int r=md_database_error(db,sqlite3_prepare_v2(db,"SELECT path FROM sources",-1,&query,NULL));
    size_t length=strlen(library);
    int step=SQLITE_DONE;
    while (!r && (step=sqlite3_step(query))==SQLITE_ROW) {
        const char *path=(const char *)sqlite3_column_text(query,0);
        if (!path || !*path) { r=-EINVAL; break; }
        char joined[PATH_MAX],resolved[PATH_MAX];
        int n=*path=='/' ? snprintf(joined,sizeof(joined),"%s",path)
            : snprintf(joined,sizeof(joined),"%s/%s",store,path);
        if (n<0 || n>=(int)sizeof(joined)) r=-ENAMETOOLONG;
        else if (!realpath(joined,resolved)) r=-errno;
        else if (strncmp(resolved,library,length)
                || (resolved[length] && resolved[length]!='/')) r=-EXDEV;
    }
    if (!r && step!=SQLITE_DONE) r=md_database_error(db,step);
    sqlite3_finalize(query); return r;
}
static int relative_sources(int root,const char *library) {
    struct stat metadata;
    if (fstatat(root,"namespace.db",&metadata,AT_SYMLINK_NOFOLLOW)) return errno==ENOENT ? 0 : -errno;
    char path[PATH_MAX];
    int r=md_storage_directory_path(root,path,sizeof(path));
    sqlite3 *db=NULL;
    if (!r) r=md_database_open(root,"namespace.db",0,&db);
    if (!r) r=mdi_schema_admit_offline(root,db);
    if (!r) r=md_database_error(db,sqlite3_exec(db,"PRAGMA trusted_schema=OFF; PRAGMA synchronous=FULL; BEGIN IMMEDIATE",NULL,NULL,NULL));
    if (!r) r=contained_sources(db,path,library);
    if (!r) r=md_storage_sources_rebase(db,path,path);
    if (!r) r=md_database_error(db,sqlite3_exec(db,"COMMIT",NULL,NULL,NULL));
    if (r && db) sqlite3_exec(db,"ROLLBACK",NULL,NULL,NULL);
    if (db) sqlite3_close(db);
    if (!r && fsync(root)) r=-errno;
    return r;
}

int md_image_relocate_library(const char *source,const char *destination) {
    if (!source || !destination || *source!='/' || *destination!='/') return -EINVAL;
    char target[PATH_MAX];
    if (strlen(destination)>=sizeof(target)) return -ENAMETOOLONG;
    strcpy(target,destination);
    char *name=strrchr(target,'/');
    if (!name || !name[1] || !strcmp(name+1,".") || !strcmp(name+1,"..")) return -EINVAL;
    *name++=0;
    int parent=open(*target ? target : "/",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    if (parent<0) return -errno;
    int root=open(source,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    int r=root<0 ? -errno : 0;
    char origin[PATH_MAX],parent_path[PATH_MAX];
    if (!r) r=md_storage_directory_path(root,origin,sizeof(origin));
    if (!r) r=md_storage_directory_path(parent,parent_path,sizeof(parent_path));
    if (!r && !strncmp(origin,parent_path,strlen(origin))
            && (!parent_path[strlen(origin)] || parent_path[strlen(origin)]=='/')) r=-EINVAL;
    struct stat existing,opened;
    if (!r && !fstatat(parent,name,&existing,AT_SYMLINK_NOFOLLOW)) r=-EEXIST;
    else if (!r && errno!=ENOENT) r=-errno;
    int catalog=-1,previous_parent=-1;
    if (!r && (catalog=openat(root,"library.lock",O_RDWR|O_CREAT|O_NOFOLLOW|O_CLOEXEC,0600))<0) r=-errno;
    struct flock lock={.l_type=F_WRLCK,.l_whence=SEEK_SET,.l_start=0,.l_len=0};
    if (!r && fcntl(catalog,F_SETLK,&lock)) r=errno==EAGAIN || errno==EACCES ? -EBUSY : -errno;
    if (!r && (previous_parent=openat(root,"..",O_RDONLY|O_DIRECTORY|O_CLOEXEC))<0) r=-errno;
    struct store_lease *leases=NULL;
    const char *groups[]={"images","instances","layers"};
    for (size_t i=0;!r && i<sizeof(groups)/sizeof(*groups);++i) r=retain_group(root,groups[i],&leases);
    /* Every live source/instance blocks the move. Completed conversions remain
     * valid at the old location if interrupted before the single tree rename. */
    for (struct store_lease *p=leases;!r && p;p=p->next) r=relative_sources(p->fd,origin);
    if (!r && (fstat(root,&opened) || lstat(source,&existing)
            || !opened.st_nlink || opened.st_dev!=existing.st_dev || opened.st_ino!=existing.st_ino)) r=-ESTALE;
    if (!r && syscall(SYS_renameat2,AT_FDCWD,source,parent,name,RENAME_NOREPLACE)) r=-errno;
    if (!r && (fsync(parent) || fsync(previous_parent))) r=-errno;
    while (leases) { struct store_lease *p=leases; leases=p->next; close(p->fd); free(p); }
    if (catalog>=0) close(catalog);
    if (previous_parent>=0) close(previous_parent);
    if (root>=0) close(root);
    close(parent); return r;
}
