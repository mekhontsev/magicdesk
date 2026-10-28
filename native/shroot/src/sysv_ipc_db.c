#define _GNU_SOURCE
#include "sysv_ipc_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int md_ipc_sql(int r) { return md_ipc_database_sql(r); }
int md_ipc_exec(struct md_ipc *s, const char *text) {
    return md_ipc_database_exec(&s->database,text);
}
int md_ipc_query(struct md_ipc *s, const char *text, sqlite3_stmt **q) {
    return md_ipc_database_query(&s->database,text,q);
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
int md_ipc_get(struct md_ipc *s, int kind, int key, int flags, int *id) {
    *id = 0;
    sqlite3_stmt *q = NULL;
    int r = md_ipc_query(s,"SELECT id FROM objects WHERE kind=?1 AND key=?2 AND key!=0 AND namespace=?3",&q);
    if (!r) {
        sqlite3_bind_int(q,1,kind); sqlite3_bind_int(q,2,key); sqlite3_bind_int64(q,3,s->namespace_id);
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
    int r=md_ipc_query(s,"SELECT info FROM objects WHERE id=?1 AND kind=?2 AND namespace=?3",&q);
    if (!r) {
        sqlite3_bind_int(q,1,id); sqlite3_bind_int(q,2,kind); sqlite3_bind_int64(q,3,s->namespace_id);
        int step=sqlite3_step(q);
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
    int r=md_ipc_query(s,"INSERT INTO objects(kind,key,info,namespace) VALUES(?1,?2,?3,?4)",&q);
    if (!r) {
        sqlite3_bind_int(q,1,kind); sqlite3_bind_int(q,2,key); sqlite3_bind_blob(q,3,data,size,SQLITE_STATIC);
        sqlite3_bind_int64(q,4,s->namespace_id);
        r=md_ipc_sql(sqlite3_step(q));
    }
    sqlite3_finalize(q);
    if (!r) {
        int64_t next=sqlite3_last_insert_rowid(s->database.handle);
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
static int remove_owner(void *context, int64_t owner) {
    struct md_ipc *s=context;
    int r=md_ipc_undo_apply(s,owner,0);
    sqlite3_stmt *q=NULL;
    if (!r) r=md_ipc_query(s,"DELETE FROM owners WHERE id=?1",&q);
    if (!r) { sqlite3_bind_int64(q,1,owner); r=md_ipc_sql(sqlite3_step(q)); }
    sqlite3_finalize(q);
    if (!r) r=md_ipc_exec(s,"DELETE FROM objects WHERE namespace!=0 AND namespace NOT IN (SELECT namespace FROM owners)");
    return r;
}
static int reap(struct md_ipc *s) {
    return md_ipc_database_reap(&s->database,"SELECT id FROM owners",remove_owner,s);
}
int md_ipc_begin(struct md_ipc *s) {
    int r=md_ipc_database_begin(&s->database);
    if (r) return r;
    s->changed=0;
    r=reap(s);
    if (r) { md_ipc_database_commit(&s->database,r); md_ipc_database_unlock(&s->database); }
    return r;
}
int md_ipc_finish(struct md_ipc *s, int error) {
    error=md_ipc_database_commit(&s->database,error);
    if (!error && s->changed && pwrite(s->events,"e",1,0)!=1) error=-EIO;
    md_ipc_database_unlock(&s->database); return error;
}
int md_ipc_open(const char *store, uint64_t namespace_id, struct md_user_namespace *owner, struct md_ipc **out) {
    *out=NULL;
    struct md_ipc *s=calloc(1,sizeof(*s)); if (!s) return -ENOMEM;
    s->events=-1;
    s->namespace_id=namespace_id;
    s->user_namespace=md_user_namespace_retain(owner);
    int r=md_ipc_database_open(&s->database,store,"sysv","ipc.db");
    if (!r && (s->events=openat(s->database.directory,"events",O_CREAT|O_RDWR|O_CLOEXEC|O_NOFOLLOW,0600))<0) r=-errno;
    if (!r) r=md_ipc_exec(s,
        "CREATE TABLE IF NOT EXISTS objects(id INTEGER PRIMARY KEY AUTOINCREMENT,kind INTEGER,key INTEGER,info BLOB,namespace INTEGER NOT NULL);"
        "CREATE UNIQUE INDEX IF NOT EXISTS object_key ON objects(namespace,kind,key) WHERE key!=0;"
        "CREATE TABLE IF NOT EXISTS sems(object INTEGER REFERENCES objects ON DELETE CASCADE,n INTEGER,value INTEGER,pid INTEGER,PRIMARY KEY(object,n));"
        "CREATE TABLE IF NOT EXISTS messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,object INTEGER REFERENCES objects ON DELETE CASCADE,type INTEGER,data BLOB);"
        "CREATE INDEX IF NOT EXISTS message_order ON messages(object,type,seq);"
        "CREATE INDEX IF NOT EXISTS message_fifo ON messages(object,seq);"
        "CREATE TABLE IF NOT EXISTS owners(id INTEGER PRIMARY KEY,namespace INTEGER NOT NULL);"
        "CREATE TABLE IF NOT EXISTS undo(owner INTEGER REFERENCES owners ON DELETE CASCADE,space INTEGER,object INTEGER REFERENCES objects ON DELETE CASCADE,n INTEGER,delta INTEGER,pid INTEGER,PRIMARY KEY(owner,space,object,n));"
        "CREATE TABLE IF NOT EXISTS waiters(owner INTEGER REFERENCES owners ON DELETE CASCADE,tid INTEGER,object INTEGER REFERENCES objects ON DELETE CASCADE,n INTEGER,zero INTEGER,PRIMARY KEY(owner,tid));"
        "CREATE INDEX IF NOT EXISTS undo_object ON undo(object);"
        "CREATE INDEX IF NOT EXISTS waiters_object ON waiters(object,n,zero);");
    sqlite3_stmt *q=NULL;
    if (!r) r=md_ipc_exec(s,"BEGIN IMMEDIATE");
    if (!r) r=md_ipc_database_blob_column(&s->database,"sems","pids");
    if (!r) r=md_ipc_database_blob_column(&s->database,"undo","pids");
    if (!r) r=md_ipc_database_blob_column(&s->database,"objects","send_pids");
    if (!r) r=md_ipc_database_blob_column(&s->database,"objects","receive_pids");
    if (!r) r=md_ipc_database_boot(&s->database,"DELETE FROM objects; DELETE FROM owners;");
    if (!r) r=reap(s);
    if (!r) r=md_ipc_query(s,"INSERT INTO owners VALUES(?1,?2)",&q);
    if (!r) {
        sqlite3_bind_int64(q,1,s->database.owner); sqlite3_bind_int64(q,2,s->namespace_id);
        r=md_ipc_sql(sqlite3_step(q));
    }
    sqlite3_finalize(q);
    if (s->database.handle) r=md_ipc_database_commit(&s->database,r);
    if (!r) s->database.admitted=1;
    md_ipc_database_unlock(&s->database);
    if (r) md_ipc_close(s); else *out=s;
    return r;
}
void md_ipc_close(struct md_ipc *s) {
    if (!s) return;
    if (s->database.admitted && !md_ipc_begin(s)) md_ipc_finish(s,remove_owner(s,s->database.owner));
    if (s->events>=0) close(s->events);
    md_ipc_database_close(&s->database);
    md_user_namespace_release(s->user_namespace);
    free(s);
}
