#define _GNU_SOURCE
#include "storage_database.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static sqlite3_vfs anchored;
static pthread_once_t initialized=PTHREAD_ONCE_INIT;
static int status=SQLITE_ERROR;
int md_database_temporary_directory(const char *path) {
    if (path && path[0]!='/') return -EINVAL;
    char *copy=path ? sqlite3_mprintf("%s",path) : NULL;
    if (path && !copy) return -ENOMEM;
    sqlite3_free(sqlite3_temp_directory);
    sqlite3_temp_directory=copy;
    return 0;
}
static int leaf(const char *name) {
    return name && *name && strcmp(name,".") && strcmp(name,"..")
        && !strchr(name,'/') && strlen(name)<=NAME_MAX;
}
static int retained_path(const char *path, const char **name) {
    static const char prefix[]="/proc/self/fd/";
    if (strncmp(path,prefix,sizeof(prefix)-1)) return -1;
    const char *p=path+sizeof(prefix)-1, *first=p;
    unsigned fd=0;
    while (*p>='0' && *p<='9') {
        unsigned digit=(unsigned)(*p++-'0');
        if (fd>((unsigned)INT_MAX-digit)/10) return -1;
        fd=fd*10+digit;
    }
    if (p==first || *p++!='/') return -1;
    if (!leaf(p)) return -1;
    *name=p; return (int)fd;
}
static int access_path(sqlite3_vfs *vfs,const char *path,int flags,int *result) {
    (void)vfs;
    const char *name;
    int root=retained_path(path,&name);
    if (root<0) return SQLITE_IOERR_ACCESS;
    /* Keep Unix VFS existence semantics, including empty regular files.
     * Use the already-retained directory, not a repeated procfs path walk.
     * Never cache journal presence: another writer or recovery can change it. */
    if (flags==SQLITE_ACCESS_EXISTS) {
        struct stat st;
        *result=!fstatat(root,name,&st,0) && (!S_ISREG(st.st_mode) || st.st_size>0);
    } else if (flags==SQLITE_ACCESS_READ || flags==SQLITE_ACCESS_READWRITE) {
        *result=!faccessat(root,name,R_OK|(flags==SQLITE_ACCESS_READWRITE ? W_OK : 0),0);
    } else return SQLITE_IOERR_ACCESS;
    return SQLITE_OK;
}
static int full_path(sqlite3_vfs *vfs, const char *path, int capacity, char *out) {
    (void)vfs;
    /* This private VFS accepts only runtime-constructed database paths. Keep
     * the retained directory FD instead of SQLite's pathname canonicalization.
     * The stock Unix VFS still owns IO, journaling, locks and O_NOFOLLOW opens. */
    const char *name;
    if (retained_path(path,&name)<0) return SQLITE_CANTOPEN;
    size_t n=strlen(path);
    if (capacity<=0 || n>=(size_t)capacity) return SQLITE_CANTOPEN;
    memcpy(out,path,n+1); return SQLITE_OK;
}
static void initialize(void) {
    sqlite3_vfs *base=sqlite3_vfs_find("unix");
    if (!base) return;
    anchored=*base;
    anchored.zName="shroot-retained-directory";
    anchored.pNext=NULL; anchored.xFullPathname=full_path;
    anchored.xAccess=access_path;
    status=sqlite3_vfs_register(&anchored,0);
}
int md_database_result(int rc) {
    if (rc<0) return rc;
    if (rc==SQLITE_CONSTRAINT_PRIMARYKEY || rc==SQLITE_CONSTRAINT_UNIQUE) return -EEXIST;
    switch (rc&255) {
    case SQLITE_OK: case SQLITE_ROW: case SQLITE_DONE: return 0;
    case SQLITE_BUSY: case SQLITE_LOCKED: return -EAGAIN;
    case SQLITE_NOMEM: return -ENOMEM;
    case SQLITE_FULL: return -ENOSPC;
    case SQLITE_READONLY: return -EROFS;
    default: return -EIO;
    }
}
int md_database_error(sqlite3 *db, int rc) {
    if (db && rc>=0 && ((rc&255)==SQLITE_IOERR || (rc&255)==SQLITE_CANTOPEN)) {
        int error=sqlite3_system_errno(db);
        if (error>0) return -error;
    }
    return md_database_result(rc);
}
int md_database_step(sqlite3_stmt *query) {
    int rc=sqlite3_step(query);
    return rc==SQLITE_ROW || rc==SQLITE_DONE ? rc : md_database_error(sqlite3_db_handle(query),rc);
}
static int database_open(int root, const char *name, int flags, sqlite3 **out) {
    if (!out) return -EINVAL;
    *out=NULL;
    if (root<0 || !leaf(name)) return -EINVAL;
    pthread_once(&initialized,initialize);
    if (status!=SQLITE_OK) return md_database_result(status);
    char path[NAME_MAX+64]; snprintf(path,sizeof(path),"/proc/self/fd/%d/%s",root,name);
    int rc=sqlite3_open_v2(path,out,
        flags|SQLITE_OPEN_NOMUTEX|SQLITE_OPEN_NOFOLLOW,anchored.zName);
    int r=md_database_error(*out,rc);
    if (r) { sqlite3_close(*out); *out=NULL; }
    return r;
}
int md_database_open(int root, const char *name, int create, sqlite3 **out) {
    return database_open(root,name,SQLITE_OPEN_READWRITE|(create ? SQLITE_OPEN_CREATE : 0),out);
}
int md_database_open_readonly(int root, const char *name, sqlite3 **out) {
    return database_open(root,name,SQLITE_OPEN_READONLY,out);
}
