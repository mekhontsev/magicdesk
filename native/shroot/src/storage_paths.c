#define _GNU_SOURCE
#include "storage_paths.h"
#include "storage_database.h"
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int md_storage_directory_path(int directory,char *path,size_t size) {
    char proc[64],resolved[PATH_MAX];
    snprintf(proc,sizeof(proc),"/proc/self/fd/%d",directory);
    if (!realpath(proc,resolved)) return -errno;
    size_t length=strlen(resolved);
    if (length>=size) return -ENAMETOOLONG;
    memcpy(path,resolved,length+1); return 0;
}

int md_storage_relative_path(const char *directory,const char *target,char *path,size_t size) {
    if (!directory || !target || *directory!='/' || *target!='/') return -EINVAL;
    size_t common=1;
    for (size_t i=1;directory[i] && target[i] && directory[i]==target[i];++i)
        if (directory[i]=='/') common=i+1;
    size_t length=strlen(directory);
    if (!strncmp(directory,target,length) && (!target[length] || target[length]=='/'))
        common=length+(target[length]=='/');
    size_t at=0;
    for (const char *p=directory+(common>length ? length : common);*p;) {
        while (*p=='/') ++p;
        if (!*p) break;
        if (at+3>=size) return -ENAMETOOLONG;
        memcpy(path+at,"../",3); at+=3;
        while (*p && *p!='/') ++p;
    }
    const char *tail=target+common;
    size_t remaining=strlen(tail);
    if (!remaining && !at) tail=".",remaining=1;
    if (at+remaining>=size) return -ENAMETOOLONG;
    memcpy(path+at,tail,remaining+1); return 0;
}

struct source_roots { const char *previous,*destination; };
static void rebase(sqlite3_context *context,int argc,sqlite3_value **argv) {
    (void)argc;
    const struct source_roots *roots=sqlite3_user_data(context);
    const char *path=(const char *)sqlite3_value_text(argv[0]);
    if (!path || !*path) { sqlite3_result_error(context,"Invalid source path",-1); return; }
    char absolute[PATH_MAX],relative[PATH_MAX];
    int r=0;
    char joined[PATH_MAX];
    int n=*path=='/' ? snprintf(joined,sizeof(joined),"%s",path)
        : snprintf(joined,sizeof(joined),"%s/%s",roots->previous,path);
    if (n<0 || n>=(int)sizeof(joined)) r=-ENAMETOOLONG;
    else if (!realpath(joined,absolute)) r=-errno;
    if (!r) r=md_storage_relative_path(roots->destination,absolute,relative,sizeof(relative));
    if (r) sqlite3_result_error(context,strerror(-r),-1);
    else sqlite3_result_text(context,relative,-1,SQLITE_TRANSIENT);
}

int md_storage_sources_rebase(sqlite3 *db,const char *previous,const char *destination) {
    struct source_roots roots={previous,destination};
    int r=md_database_error(db,sqlite3_create_function_v2(db,"shroot_source_path",1,
        SQLITE_UTF8|SQLITE_DIRECTONLY,&roots,rebase,NULL,NULL,NULL));
    if (!r) r=md_database_error(db,sqlite3_exec(db,"UPDATE sources SET path=shroot_source_path(path)",NULL,NULL,NULL));
    int reset=md_database_error(db,sqlite3_create_function_v2(db,"shroot_source_path",1,
        SQLITE_UTF8,NULL,NULL,NULL,NULL,NULL));
    return r ? r : reset;
}
