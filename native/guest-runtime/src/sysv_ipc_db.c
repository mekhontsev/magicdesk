#define _GNU_SOURCE
#include "sysv_ipc_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/random.h>
#include <sys/stat.h>
#include <unistd.h>

int md_ipc_sql(int r) { return r == SQLITE_OK || r == SQLITE_ROW || r == SQLITE_DONE ? 0 : -EIO; }
int md_ipc_exec(struct md_ipc *s, const char *text) {
    return md_ipc_sql(sqlite3_exec(s->db, text, NULL, NULL, NULL));
}
int md_ipc_query(struct md_ipc *s, const char *text, sqlite3_stmt **q) {
    return md_ipc_sql(sqlite3_prepare_v2(s->db, text, -1, q, NULL));
}
int md_ipc_memory(struct md_ipc_pending *p, uintptr_t address, void *data, size_t size, int write) {
    if (!size) return 0;
    struct md_ipc_transport *t=p->transport;
    size_t start=offsetof(struct md_ipc_packet,data);
    if (address<start || address-start>t->capacity || size>t->capacity-(address-start)) return -EFAULT;
    void *buffer=(char *)t->packet+address;
    if (write) {
        memcpy(buffer,data,size);
        if (t->packet->output_size<address-start+size) t->packet->output_size=address-start+size;
    } else memcpy(data,buffer,size);
    return 0;
}
int md_ipc_permission(const struct md_identity *id, const struct ipc64_perm *p, unsigned mode) {
    if (md_identity_capable(id,CAP_IPC_OWNER)) return 0;
    unsigned shift = id->uid.effective == p->uid || id->uid.effective == p->cuid ? 6
        : md_identity_in_group(id,p->gid) || md_identity_in_group(id,p->cgid) ? 3 : 0;
    return ((p->mode >> shift)&mode) == mode ? 0 : -EACCES;
}
int md_ipc_owner(const struct md_identity *id, const struct ipc64_perm *p) {
    return id->uid.effective == p->uid || id->uid.effective == p->cuid
        || md_identity_capable(id,CAP_SYS_ADMIN) ? 0 : -EPERM;
}
int md_ipc_get(struct md_ipc *s, int kind, int key, int flags, int *id) {
    *id = 0;
    sqlite3_stmt *q = NULL;
    int r = md_ipc_query(s,"SELECT id FROM objects WHERE kind=?1 AND key=?2 AND key!=0",&q);
    if (!r) {
        sqlite3_bind_int(q,1,kind); sqlite3_bind_int(q,2,key);
        int step=sqlite3_step(q);
        if (step==SQLITE_ROW) *id=sqlite3_column_int(q,0); else if (step!=SQLITE_DONE) r=-EIO;
    }
    sqlite3_finalize(q);
    if (!r && *id && (flags&(IPC_CREAT|IPC_EXCL))==(IPC_CREAT|IPC_EXCL)) r=-EEXIST;
    if (!r && !*id && key && !(flags&IPC_CREAT)) r=-ENOENT;
    return r;
}
int md_ipc_load(struct md_ipc *s, int kind, int id, void *data, size_t size) {
    sqlite3_stmt *q = NULL;
    int r=md_ipc_query(s,"SELECT info FROM objects WHERE id=?1 AND kind=?2",&q);
    if (!r) {
        sqlite3_bind_int(q,1,id); sqlite3_bind_int(q,2,kind); int step=sqlite3_step(q);
        if (step==SQLITE_DONE) r=-EINVAL;
        else if (step!=SQLITE_ROW || sqlite3_column_bytes(q,0)!=(int)size) r=-EIO;
        else memcpy(data,sqlite3_column_blob(q,0),size);
    }
    sqlite3_finalize(q); return r;
}
int md_ipc_save(struct md_ipc *s, int id, const void *data, size_t size) {
    sqlite3_stmt *q = NULL;
    int r=md_ipc_query(s,"UPDATE objects SET info=?2 WHERE id=?1",&q);
    if (!r) { sqlite3_bind_int(q,1,id); sqlite3_bind_blob(q,2,data,size,SQLITE_STATIC); r=md_ipc_sql(sqlite3_step(q)); }
    sqlite3_finalize(q); if (!r) s->changed=1; return r;
}
int md_ipc_create(struct md_ipc *s, int kind, int key, const void *data, size_t size, int *id) {
    sqlite3_stmt *q = NULL;
    int r=md_ipc_query(s,"INSERT INTO objects(kind,key,info) VALUES(?1,?2,?3)",&q);
    if (!r) {
        sqlite3_bind_int(q,1,kind); sqlite3_bind_int(q,2,key); sqlite3_bind_blob(q,3,data,size,SQLITE_STATIC);
        r=md_ipc_sql(sqlite3_step(q));
    }
    sqlite3_finalize(q);
    if (!r) {
        int64_t next=sqlite3_last_insert_rowid(s->db);
        if (next<=0 || next>INT_MAX) r=-ENOSPC; else *id=(int)next;
        s->changed=1;
    }
    return r;
}
int md_ipc_remove(struct md_ipc *s, int id) {
    sqlite3_stmt *q = NULL;
    int r=md_ipc_query(s,"DELETE FROM objects WHERE id=?1",&q);
    if (!r) { sqlite3_bind_int(q,1,id); r=md_ipc_sql(sqlite3_step(q)); }
    sqlite3_finalize(q); if (!r) s->changed=1; return r;
}
static void owner_name(char name[64], int64_t id) { snprintf(name,64,"owner-%016llx",(long long)id); }
static int remove_owner(struct md_ipc *s, int64_t owner) {
    int r=md_ipc_undo_apply(s,owner,0);
    sqlite3_stmt *q=NULL;
    if (!r) r=md_ipc_query(s,"DELETE FROM owners WHERE id=?1",&q);
    if (!r) { sqlite3_bind_int64(q,1,owner); r=md_ipc_sql(sqlite3_step(q)); }
    sqlite3_finalize(q); return r;
}
static int reap(struct md_ipc *s) {
    sqlite3_stmt *q=NULL;
    int r=md_ipc_query(s,"SELECT id FROM owners",&q);
    if (!r) {
        int step;
        while ((step=sqlite3_step(q))==SQLITE_ROW) {
            int64_t owner=sqlite3_column_int64(q,0);
            if (owner==s->owner) continue;
            char name[64]; owner_name(name,owner);
            int fd=openat(s->dir,name,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);
            int dead=fd<0 && errno==ENOENT;
            if (fd>=0) { dead=!flock(fd,LOCK_EX|LOCK_NB); if (!dead && errno!=EWOULDBLOCK) r=-errno; }
            else if (!dead) r=-errno;
            if (!r && dead) { r=remove_owner(s,owner); if (!r) unlinkat(s->dir,name,0); }
            if (fd>=0) close(fd);
            if (r) break;
        }
        if (!r && step!=SQLITE_DONE) r=-EIO;
    }
    sqlite3_finalize(q); return r;
}
int md_ipc_begin(struct md_ipc *s) {
    if (flock(s->gate,LOCK_EX)) return -errno;
    s->changed=0;
    int r=md_ipc_exec(s,"BEGIN IMMEDIATE");
    if (!r) r=reap(s);
    if (r) { md_ipc_exec(s,"ROLLBACK"); flock(s->gate,LOCK_UN); }
    return r;
}
int md_ipc_finish(struct md_ipc *s, int error) {
    if (!error) error=md_ipc_exec(s,"COMMIT");
    if (error) md_ipc_exec(s,"ROLLBACK");
    else if (s->changed && pwrite(s->events,"e",1,0)!=1) error=-EIO;
    flock(s->gate,LOCK_UN); return error;
}
int md_ipc_open(const char *store, struct md_ipc **out) {
    *out=NULL;
    struct md_ipc *s=calloc(1,sizeof(*s)); if (!s) return -ENOMEM;
    s->dir=s->gate=s->lease=s->events=-1;
    int root=open(store,O_DIRECTORY|O_RDONLY|O_CLOEXEC|O_NOFOLLOW), parent=-1;
    int r=root<0 ? -errno : 0;
    if (!r && mkdirat(root,"ipc",0700) && errno!=EEXIST) r=-errno;
    if (!r && (parent=openat(root,"ipc",O_DIRECTORY|O_RDONLY|O_CLOEXEC|O_NOFOLLOW))<0) r=-errno;
    if (!r && mkdirat(parent,"sysv",0700) && errno!=EEXIST) r=-errno;
    if (!r && (s->dir=openat(parent,"sysv",O_DIRECTORY|O_RDONLY|O_CLOEXEC|O_NOFOLLOW))<0) r=-errno;
    if (parent>=0) close(parent);
    if (root>=0) close(root);
    if (!r && (s->gate=openat(s->dir,"gate",O_CREAT|O_RDWR|O_CLOEXEC|O_NOFOLLOW,0600))<0) r=-errno;
    if (!r && (s->events=openat(s->dir,"events",O_CREAT|O_RDWR|O_CLOEXEC|O_NOFOLLOW,0600))<0) r=-errno;
    if (!r && getrandom(&s->owner,sizeof(s->owner),0)!=sizeof(s->owner)) r=-EIO;
    s->owner&=INT64_MAX;
    char name[64]; owner_name(name,s->owner);
    if (!r && (s->lease=openat(s->dir,name,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC|O_NOFOLLOW,0600))<0) r=-errno;
    if (!r && flock(s->lease,LOCK_EX|LOCK_NB)) r=-errno;
    int locked=!r && !flock(s->gate,LOCK_EX);
    if (!r && !locked) r=-errno;
    char descriptor[64], path[PATH_MAX];
    snprintf(descriptor,sizeof(descriptor),"/proc/self/fd/%d",s->dir);
    ssize_t length=r ? -1 : readlink(descriptor,path,sizeof(path)-sizeof("/ipc.db"));
    if (!r && (length<0 || length>=(ssize_t)(sizeof(path)-sizeof("/ipc.db")))) r=-ENAMETOOLONG;
    if (!r) memcpy(path+length,"/ipc.db",sizeof("/ipc.db"));
    if (!r) r=md_ipc_sql(sqlite3_open_v2(path,&s->db,SQLITE_OPEN_READWRITE|SQLITE_OPEN_CREATE|SQLITE_OPEN_NOFOLLOW,NULL));
    if (!r) r=md_ipc_exec(s,"PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA foreign_keys=ON;"
        "CREATE TABLE IF NOT EXISTS objects(id INTEGER PRIMARY KEY AUTOINCREMENT,kind INTEGER,key INTEGER,info BLOB);"
        "CREATE UNIQUE INDEX IF NOT EXISTS object_key ON objects(kind,key) WHERE key!=0;"
        "CREATE TABLE IF NOT EXISTS sems(object INTEGER REFERENCES objects ON DELETE CASCADE,n INTEGER,value INTEGER,pid INTEGER,PRIMARY KEY(object,n));"
        "CREATE TABLE IF NOT EXISTS messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,object INTEGER REFERENCES objects ON DELETE CASCADE,type INTEGER,data BLOB);"
        "CREATE INDEX IF NOT EXISTS message_order ON messages(object,type,seq);"
        "CREATE TABLE IF NOT EXISTS owners(id INTEGER PRIMARY KEY);"
        "CREATE TABLE IF NOT EXISTS undo(owner INTEGER REFERENCES owners ON DELETE CASCADE,space INTEGER,object INTEGER REFERENCES objects ON DELETE CASCADE,n INTEGER,delta INTEGER,pid INTEGER,PRIMARY KEY(owner,space,object,n));"
        "CREATE TABLE IF NOT EXISTS waiters(owner INTEGER REFERENCES owners ON DELETE CASCADE,tid INTEGER,object INTEGER REFERENCES objects ON DELETE CASCADE,n INTEGER,zero INTEGER,PRIMARY KEY(owner,tid));"
        "CREATE TABLE IF NOT EXISTS metadata(name TEXT PRIMARY KEY,value TEXT);");
    char boot[64]={0}; int fd=!r ? open("/proc/sys/kernel/random/boot_id",O_RDONLY|O_CLOEXEC) : -1;
    if (!r && (fd<0 || read(fd,boot,sizeof(boot)-1)<=0)) r=-EIO;
    if (fd>=0) close(fd);
    sqlite3_stmt *q=NULL; int changed=1;
    if (!r) r=md_ipc_exec(s,"BEGIN IMMEDIATE");
    if (!r) r=md_ipc_query(s,"SELECT value FROM metadata WHERE name='boot'",&q);
    if (!r) {
        int step=sqlite3_step(q);
        if (step==SQLITE_ROW) changed=strcmp((const char *)sqlite3_column_text(q,0),boot)!=0;
        else if (step!=SQLITE_DONE) r=-EIO;
    }
    sqlite3_finalize(q); q=NULL;
    if (!r && changed) r=md_ipc_exec(s,"DELETE FROM objects; DELETE FROM owners;");
    if (!r) r=md_ipc_query(s,"INSERT OR REPLACE INTO metadata VALUES('boot',?1)",&q);
    if (!r) { sqlite3_bind_text(q,1,boot,-1,SQLITE_STATIC); r=md_ipc_sql(sqlite3_step(q)); }
    sqlite3_finalize(q); q=NULL;
    if (!r) r=reap(s);
    if (!r) r=md_ipc_query(s,"INSERT INTO owners VALUES(?1)",&q);
    if (!r) { sqlite3_bind_int64(q,1,s->owner); r=md_ipc_sql(sqlite3_step(q)); }
    sqlite3_finalize(q);
    if (!r) r=md_ipc_exec(s,"COMMIT");
    if (r && s->db) md_ipc_exec(s,"ROLLBACK");
    if (locked) flock(s->gate,LOCK_UN);
    if (r) md_ipc_close(s); else *out=s;
    return r;
}
void md_ipc_close(struct md_ipc *s) {
    if (!s) return;
    if (s->db && s->gate>=0 && !md_ipc_begin(s)) md_ipc_finish(s,remove_owner(s,s->owner));
    if (s->lease>=0) { char name[64]; owner_name(name,s->owner); unlinkat(s->dir,name,0); close(s->lease); }
    if (s->db) sqlite3_close(s->db);
    if (s->events>=0) close(s->events);
    if (s->gate>=0) close(s->gate);
    if (s->dir>=0) close(s->dir);
    free(s);
}
