#define _GNU_SOURCE
#include "storage_database.h"
#include "private_tree.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <unistd.h>

static unsigned descriptors(void) {
    DIR *dir=opendir("/proc/self/fd"); assert(dir);
    unsigned count=0; struct dirent *entry;
    while ((entry=readdir(dir))) if (entry->d_name[0]!='.') ++count;
    assert(!closedir(dir)); return count;
}
static void temporary(void) {
    char path[]="database-temporary-XXXXXX"; assert(mkdtemp(path));
    char *absolute=realpath(path,NULL); assert(absolute);
    assert(md_database_temporary_directory(path)==-EINVAL);
    assert(!md_database_temporary_directory(absolute));
    assert(sqlite3_temp_directory!=absolute && !strcmp(sqlite3_temp_directory,absolute));
    sqlite3_vfs *vfs=sqlite3_vfs_find("unix"); assert(vfs);
    sqlite3_file *file=calloc(1,(size_t)vfs->szOsFile); assert(file);
    int flags=SQLITE_OPEN_READWRITE|SQLITE_OPEN_CREATE|SQLITE_OPEN_EXCLUSIVE
        |SQLITE_OPEN_DELETEONCLOSE|SQLITE_OPEN_TEMP_DB;
    assert(vfs->xOpen(vfs,NULL,file,flags,NULL)==SQLITE_OK);
    assert(file->pMethods->xWrite(file,"temp",4,0)==SQLITE_OK);
    char contents[4];
    assert(file->pMethods->xRead(file,contents,4,0)==SQLITE_OK && !memcmp(contents,"temp",4));
    assert(file->pMethods->xClose(file)==SQLITE_OK); free(file);
    assert(!md_database_temporary_directory(NULL));
    free(absolute); assert(!rmdir(path));
}
static void execute(sqlite3 *db,const char *sql) {
    int r=sqlite3_exec(db,sql,NULL,NULL,NULL);
    if (r!=SQLITE_OK) fprintf(stderr,"database: %s: %s\n",sql,sqlite3_errmsg(db));
    assert(r==SQLITE_OK);
}
static int value(sqlite3 *db) {
    sqlite3_stmt *q=NULL;
    assert(sqlite3_prepare_v2(db,"SELECT value FROM test",-1,&q,NULL)==SQLITE_OK);
    assert(sqlite3_step(q)==SQLITE_ROW);
    int n=sqlite3_column_int(q,0);
    assert(sqlite3_finalize(q)==SQLITE_OK); return n;
}
static void retained(const char *journal) {
    unsigned initial=descriptors();
    char path[]="storage-database-XXXXXX"; assert(mkdtemp(path));
    int parent=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(parent>=0);
    assert(!mkdirat(parent,"original",0700));
    int root=openat(parent,"original",O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(root>=0);
    sqlite3 *first=NULL,*second=NULL;
    assert(!md_database_open(root,"state.db",1,&first));
    execute(first,journal); execute(first,"CREATE TABLE test(value);INSERT INTO test VALUES(1)");
    assert(!renameat(parent,"original",parent,"renamed"));
    int alias=openat(parent,"renamed",O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(alias>=0);
    assert(!md_database_open(alias,"state.db",0,&second));
    assert(value(second)==1);
    execute(first,"BEGIN IMMEDIATE;UPDATE test SET value=2");
    assert(md_database_result(sqlite3_exec(second,"BEGIN IMMEDIATE",NULL,NULL,NULL))==-EAGAIN);
    assert(value(second)==1);
    execute(first,"COMMIT"); assert(value(second)==2);
    execute(second,"BEGIN IMMEDIATE;UPDATE test SET value=3;ROLLBACK");
    assert(value(first)==2);
    unsigned live=descriptors(), retained_fds=0;
    for (unsigned round=0;round<32;++round) {
        sqlite3 *peers[2];
        for (unsigned i=0;i<2;++i) {
            assert(!md_database_open(alias,"state.db",0,&peers[i]));
            assert(value(peers[i])==2);
        }
        for (unsigned i=0;i<2;++i) assert(sqlite3_close(peers[i])==SQLITE_OK);
        /* WAL keeps POSIX locks until the last connection closes. Its Unix
         * VFS defers DB close and reuses those FDs; this is a high-water mark,
         * not one retained descriptor per reconnect. */
        unsigned current=descriptors();
        assert(current>=live && current<=live+2);
        if (!round) retained_fds=current;
        else assert(current==retained_fds);
    }
    assert(sqlite3_close(first)==SQLITE_OK); close(root);
    execute(second,"UPDATE test SET value=4");
    assert(sqlite3_close(second)==SQLITE_OK);
    assert(!md_database_open(alias,"state.db",0,&first)); assert(value(first)==4);
    assert(sqlite3_close(first)==SQLITE_OK);
    assert(!symlinkat("state.db",alias,"link.db"));
    assert(md_database_open(alias,"link.db",0,&first)<0 && !first);
    assert(md_database_open(alias,"../state.db",1,&first)==-EINVAL && !first);
    assert(md_database_open(alias,"missing.db",0,&first)<0 && !first);
    close(alias); close(parent); assert(!md_private_tree_discard(AT_FDCWD,path));
    assert(descriptors()==initial);
}
struct concurrent { int directory; const char *name; pthread_barrier_t *start; };
static void *connections(void *context) {
    struct concurrent *c=context;
    /* EVENT_WAIT: both independent database owners start; fixture alarm fails a missing peer. */
    int ready=pthread_barrier_wait(c->start);
    assert(!ready || ready==PTHREAD_BARRIER_SERIAL_THREAD);
    for (unsigned i=0;i<64;++i) {
        sqlite3 *db=NULL;
        assert(!md_database_open(c->directory,c->name,1,&db));
        assert(!sqlite3_db_mutex(db));
        execute(db,"PRAGMA journal_mode=WAL;CREATE TABLE IF NOT EXISTS test(value);");
        execute(db,"BEGIN IMMEDIATE;DELETE FROM test;INSERT INTO test VALUES(1);COMMIT");
        assert(value(db)==1);
        assert(sqlite3_close(db)==SQLITE_OK);
    }
    return NULL;
}
static void concurrent(void) {
    char path[]="database-threads-XXXXXX"; assert(mkdtemp(path));
    int root=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(root>=0);
    pthread_barrier_t start; assert(!pthread_barrier_init(&start,NULL,2));
    struct concurrent owners[]={{root,"first.db",&start},{root,"second.db",&start}};
    pthread_t threads[2];
    for (unsigned i=0;i<2;++i) assert(!pthread_create(&threads[i],NULL,connections,&owners[i]));
    /* EVENT_WAIT: both owners finish their transactions; fixture alarm bounds deadlock. */
    for (unsigned i=0;i<2;++i) assert(!pthread_join(threads[i],NULL));
    assert(!pthread_barrier_destroy(&start)); close(root);
    assert(!md_private_tree_discard(AT_FDCWD,path));
}
static void exhausted(void) {
    char path[]="database-fds-XXXXXX"; assert(mkdtemp(path));
    int root=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(root>=0);
    sqlite3 *db=NULL;
    assert(!md_database_open(root,"state.db",1,&db));
    execute(db,"PRAGMA journal_mode=DELETE;CREATE TABLE test(value);INSERT INTO test VALUES(1)");
    sqlite3_stmt *query=NULL;
    assert(sqlite3_prepare_v2(db,"UPDATE test SET value=2",-1,&query,NULL)==SQLITE_OK);
    struct rlimit saved; assert(!getrlimit(RLIMIT_NOFILE,&saved));
    struct rlimit limit=saved; if (limit.rlim_cur>256) limit.rlim_cur=256;
    assert(!setrlimit(RLIMIT_NOFILE,&limit));
    int held[256]; unsigned count=0;
    for (;;) {
        int fd=open("/dev/null",O_RDONLY|O_CLOEXEC);
        if (fd<0) { assert(errno==EMFILE); break; }
        assert(count<256); held[count++]=fd;
    }
    sqlite3 *failed=NULL;
    assert(md_database_open(root,"unopened.db",1,&failed)==-EMFILE && !failed);
    assert(md_database_step(query)==-EMFILE);
    while (count) close(held[--count]);
    assert(!setrlimit(RLIMIT_NOFILE,&saved));
    sqlite3_reset(query);
    assert(value(db)==1);
    assert(md_database_step(query)==SQLITE_DONE);
    assert(sqlite3_finalize(query)==SQLITE_OK && value(db)==2);
    assert(sqlite3_close(db)==SQLITE_OK); close(root);
    assert(!md_private_tree_discard(AT_FDCWD,path));
}
void test_storage_database(void) {
    assert(sqlite3_threadsafe()!=0);
    temporary();
    concurrent();
    retained("PRAGMA journal_mode=WAL");
    retained("PRAGMA journal_mode=PERSIST");
    exhausted();
    assert(md_database_result(-EMFILE)==-EMFILE);
    assert(md_database_result(SQLITE_NOMEM)==-ENOMEM);
    assert(md_database_result(SQLITE_FULL)==-ENOSPC);
    assert(md_database_result(SQLITE_READONLY)==-EROFS);
    assert(md_database_result(SQLITE_CONSTRAINT_UNIQUE)==-EEXIST);
    puts("PASS retained database directories, concurrent owners, WAL/rollback locking, failure ownership and errno");
}
