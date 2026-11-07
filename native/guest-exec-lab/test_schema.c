#define _GNU_SOURCE
#include "inode_internal.h"
#include "inode_schema.h"
#include "fixtures/storage/format17.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

/* Prospective revisions exercise the production admission path without
 * pretending that renumbered current stores are historical fixtures. */
static const char *const upgrades[]={
    "CREATE TABLE future_metadata(id INTEGER PRIMARY KEY,value TEXT) STRICT;"
    "INSERT INTO future_metadata VALUES(1,'preserved'); SELECT schema_fault();",
    "ALTER TABLE future_metadata ADD COLUMN generation INTEGER NOT NULL DEFAULT 2;"
    "SELECT schema_fault();"
};
static const char *future_steps[32];
static struct mdi_schema_plan future;
static const struct mdi_schema_plan *selected;
static int fault, commit_count, notify_fd=-1, continue_fd=-1, admissions;
static int reject_audits, audit_attempts;
static int contention_fd=-1;
enum { NO_FAULT, NO_SPACE, KILL_STAGED, KILL_COMMITTED, PAUSE_STAGED };

int __real_flock(int,int);
int __wrap_flock(int fd,int operation) {
    if (contention_fd>=0 && operation==LOCK_EX) {
        assert(__real_flock(fd,LOCK_EX|LOCK_NB)==-1 && errno==EWOULDBLOCK);
        char byte=1;
        assert(write(contention_fd,&byte,1)==1);
        close(contention_fd); contention_fd=-1;
    }
    return __real_flock(fd,operation);
}

static int no_automatic_audit(void *context,int action,const char *a,const char *b,const char *db,const char *trigger) {
    (void)context; (void)b; (void)db; (void)trigger;
    if (action==SQLITE_PRAGMA && a && (!sqlite3_stricmp(a,"integrity_check")
            || !sqlite3_stricmp(a,"quick_check") || !sqlite3_stricmp(a,"foreign_key_check"))) {
        ++audit_attempts;
        return SQLITE_DENY;
    }
    return SQLITE_OK;
}

static int scalar(struct md_inode_store *s,const char *sql) {
    sqlite3_stmt *q=NULL;
    assert(sqlite3_prepare_v2(s->db,sql,-1,&q,NULL)==SQLITE_OK);
    assert(sqlite3_step(q)==SQLITE_ROW);
    int value=sqlite3_column_int(q,0);
    assert(sqlite3_finalize(q)==SQLITE_OK); return value;
}
static void at_step(sqlite3_context *context,int argc,sqlite3_value **argv) {
    (void)argc; (void)argv;
    if (fault==NO_SPACE) { sqlite3_result_error_code(context,SQLITE_FULL); return; }
    if (fault==KILL_STAGED) {
        assert(sqlite3_db_cacheflush(sqlite3_context_db_handle(context))==SQLITE_OK);
        kill(getpid(),SIGKILL); _exit(91);
    }
    if (fault==PAUSE_STAGED) {
        char byte=1;
        assert(write(notify_fd,&byte,1)==1);
        /* EVENT_WAIT: parent releases the fixture after its contention
         * assertion; the outer test timeout bounds a failed handshake. */
        assert(read(continue_fd,&byte,1)==1);
        fault=NO_FAULT;
    }
    sqlite3_result_int(context,1);
}
static int committed(void *context,sqlite3 *db,const char *name,int pages) {
    (void)context; (void)db; (void)name; (void)pages;
    if (fault==KILL_COMMITTED && ++commit_count==1) { kill(getpid(),SIGKILL); _exit(92); }
    return SQLITE_OK;
}
int __real_mdi_schema_admit(struct md_inode_store *,int,int,const struct mdi_schema_plan *);
int __wrap_mdi_schema_admit(struct md_inode_store *s,int allow,int exclusive,const struct mdi_schema_plan *plan) {
    ++admissions;
    assert(sqlite3_create_function_v2(s->db,"schema_fault",0,SQLITE_UTF8,NULL,at_step,NULL,NULL,NULL)==SQLITE_OK);
    sqlite3_wal_hook(s->db,committed,NULL);
    if (reject_audits) assert(sqlite3_set_authorizer(s->db,no_automatic_audit,NULL)==SQLITE_OK);
    return __real_mdi_schema_admit(s,allow,exclusive,selected ? selected : plan);
}
static void initialize(const char *path) {
    struct md_inode_store *s=NULL;
    selected=NULL; fault=NO_FAULT;
    assert(!md_inode_store_open(path,1,&s));
    assert(scalar(s,"PRAGMA user_version")==17);
    assert(scalar(s,"PRAGMA application_id")==0x53485254);
    assert(scalar(s,"SELECT value FROM properties WHERE key='schema-revision'")==md_inode_store_revision());
    int fd=md_inode_create(s,-1,"file",0640); assert(fd>=0);
    assert(write(fd,"contents",8)==8);
    assert(!md_inode_link(s,-1,"file",-1,"alias",0));
    assert(!md_inode_sync(s,fd,MD_INODE_SYNC_FILE));
    close(fd); md_inode_store_close(s);
}
static void check(struct md_inode_store *s) {
    struct stat a,b;
    assert(!md_inode_stat(s,-1,"file",0,&a));
    assert(!md_inode_stat(s,-1,"alias",0,&b));
    assert(a.st_ino==b.st_ino && a.st_dev==b.st_dev && a.st_nlink==2 && a.st_size==8);
    struct md_open_completion opened;
    int fd=md_inode_prepare_open(s,-1,"alias",O_RDONLY,0,0,&opened); assert(fd>=0 && opened.dentry);
    char text[64]={0}; assert(read(fd,text,8)==8 && !strcmp(text,"contents"));
    assert(!md_inode_dentry_path(s,fd,opened.dentry,0,text,sizeof(text)) && !strcmp(text,"/alias"));
    close(fd);
    struct md_inode_audit audit; assert(!md_inode_audit(s,&audit));
}
static void historical(const char *path,int revision,struct stat *file) {
    static const char root_id[]="00000000000000000000000000000000";
    static const char file_id[]="11111111111111111111111111111111";
    assert(!mkdir(path,0700));
    int root=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(root>=0);
    assert(!mkdirat(root,"objects",0700));
    int objects=openat(root,"objects",O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(objects>=0);
    assert(!mkdirat(objects,root_id,0700));
    int fd=openat(objects,file_id,O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC,0600); assert(fd>=0);
    assert(write(fd,"contents",8)==8 && !fstat(fd,file)); close(fd);
    struct stat directory; assert(!fstatat(objects,root_id,&directory,0));
    if (revision>=2) {
        int parent=openat(objects,root_id,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(parent>=0);
        fd=openat(parent,"native",O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC,0600); assert(fd>=0);
        assert(write(fd,"native",6)==6 && !fchmod(fd,01700));
        assert(!fsetxattr(fd,"user.shroot.metadata",format17_native_metadata,sizeof(format17_native_metadata),0));
        close(fd); close(parent);
    }
    fd=openat(root,"watch.lock",O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC,0600); assert(fd>=0); close(fd);
    char database[PATH_MAX]; assert(snprintf(database,sizeof(database),"%s/namespace.db",path)<(int)sizeof(database));
    sqlite3 *db=NULL; assert(sqlite3_open(database,&db)==SQLITE_OK);
    assert(sqlite3_exec(db,"PRAGMA journal_mode=WAL; BEGIN",NULL,NULL,NULL)==SQLITE_OK);
    assert(sqlite3_exec(db,format17_revision1,NULL,NULL,NULL)==SQLITE_OK);
    if (revision>=2) assert(sqlite3_exec(db,format17_revision2,NULL,NULL,NULL)==SQLITE_OK);
    if (revision>=3) assert(sqlite3_exec(db,format17_revision3,NULL,NULL,NULL)==SQLITE_OK);
    if (revision>=4) assert(sqlite3_exec(db,format17_revision4,NULL,NULL,NULL)==SQLITE_OK);
    sqlite3_stmt *q=NULL;
    assert(sqlite3_prepare_v2(db,"INSERT INTO objects(object,kind,device,inode,parent,backing,logical_inode,logical_device,scope,mode) "
        "VALUES(?1,?2,?3,?4,?5,?1,?4,?3,?6,?7)",-1,&q,NULL)==SQLITE_OK);
    for (int i=0;i<2;++i) {
        const struct stat *st=i ? file : &directory;
        assert(sqlite3_bind_text(q,1,i ? file_id : root_id,-1,SQLITE_STATIC)==SQLITE_OK);
        assert(sqlite3_bind_int(q,2,i ? 32768 : 16384)==SQLITE_OK);
        assert(sqlite3_bind_int64(q,3,st->st_dev)==SQLITE_OK);
        assert(sqlite3_bind_int64(q,4,st->st_ino)==SQLITE_OK);
        if (!i) assert(sqlite3_bind_text(q,5,root_id,-1,SQLITE_STATIC)==SQLITE_OK);
        assert(sqlite3_bind_text(q,6,root_id,-1,SQLITE_STATIC)==SQLITE_OK);
        assert(sqlite3_bind_int(q,7,i ? 0640 : 0700)==SQLITE_OK);
        assert(sqlite3_step(q)==SQLITE_DONE && sqlite3_reset(q)==SQLITE_OK);
        assert(sqlite3_clear_bindings(q)==SQLITE_OK);
    }
    assert(sqlite3_finalize(q)==SQLITE_OK);
    assert(sqlite3_exec(db,"INSERT INTO inode_views(scope,kind) VALUES('00000000000000000000000000000000',0);"
        "INSERT INTO names(parent,name,object) VALUES('00000000000000000000000000000000',x'66696c65','11111111111111111111111111111111'),"
        "('00000000000000000000000000000000',x'616c696173','11111111111111111111111111111111');COMMIT",NULL,NULL,NULL)==SQLITE_OK);
    assert(sqlite3_close(db)==SQLITE_OK); close(objects); close(root);
}
static void native_value(struct md_inode_store *s) {
    struct stat st;
    assert(!md_inode_stat(s,-1,"native",0,&st));
    assert(st.st_uid==42 && st.st_gid==43 && (st.st_mode&07777)==06750 && st.st_size==6);
    int fd=md_inode_open(s,-1,"native",O_RDONLY,0); assert(fd>=0);
    char value[6]; assert(read(fd,value,sizeof(value))==sizeof(value) && !memcmp(value,"native",6)); close(fd);
}
static void same_schema(sqlite3 *a,sqlite3 *b) {
    const char *sql="SELECT type,name,tbl_name,sql FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name";
    sqlite3_stmt *left=NULL,*right=NULL;
    assert(sqlite3_prepare_v2(a,sql,-1,&left,NULL)==SQLITE_OK && sqlite3_prepare_v2(b,sql,-1,&right,NULL)==SQLITE_OK);
    for (;;) {
        int x=sqlite3_step(left),y=sqlite3_step(right); assert(x==y);
        if (x==SQLITE_DONE) break;
        assert(x==SQLITE_ROW);
        for (int i=0;i<4;++i) assert(!strcmp((const char *)sqlite3_column_text(left,i),(const char *)sqlite3_column_text(right,i)));
    }
    assert(sqlite3_finalize(left)==SQLITE_OK && sqlite3_finalize(right)==SQLITE_OK);
}
static void baseline(const char *path,int revision,struct md_inode_store *fresh) {
    struct stat before,after; historical(path,revision,&before);
    struct md_inode_store *s=NULL,*other=NULL;
    int lease=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(lease>=0 && !flock(lease,LOCK_SH));
    if (revision<md_inode_store_revision()) {
        assert(md_inode_store_open_inspect(path,&other)==-EPROTONOSUPPORT && !other);
        assert(md_inode_store_open(path,0,&other)==-EBUSY && !other);
    }
    close(lease);
    assert(!md_inode_store_open(path,0,&s)); check(s);
    assert(!md_inode_stat(s,-1,"file",0,&after));
    assert(before.st_dev==after.st_dev && before.st_ino==after.st_ino);
    assert(scalar(s,"SELECT value FROM properties WHERE key='schema-revision'")==md_inode_store_revision());
    assert(scalar(s,"SELECT count(*) FROM native_publication")==0);
    same_schema(fresh->db,s->db);
    if (revision>=2) native_value(s);
    md_inode_store_close(s);
    printf("PASS frozen format-17 revision-%d: contents, identities and fresh/upgraded schema equivalence\n",revision);
}
static void lightweight(const char *path) {
    initialize(path);
    reject_audits=1; audit_attempts=0;
    struct md_inode_store *s=NULL,*inspection=NULL,*worker=NULL;
    assert(!md_inode_store_open(path,0,&s));
    assert(!md_inode_store_open_inspect(path,&inspection));
    assert(!md_inode_store_reconnect(s->lifetime,&worker));
    assert(scalar(s,"PRAGMA cache_size")==-8192);
    assert(scalar(inspection,"PRAGMA cache_size")==-8192);
    assert(scalar(worker,"PRAGMA cache_size")==-8192);
    int count=admissions;
    for (int i=0;i<100;++i) {
        struct stat st;
        assert(!md_inode_stat(worker,-1,"alias",0,&st) && st.st_size==8);
        int fd=md_inode_open(worker,-1,"file",O_RDONLY,0); assert(fd>=0);
        char bytes[8]; assert(read(fd,bytes,sizeof(bytes))==sizeof(bytes)); close(fd);
        assert(!md_inode_link(worker,-1,"file",-1,"temporary",0));
        assert(!md_inode_unlink(worker,-1,"temporary",0));
    }
    assert(count==admissions && !audit_attempts);
    /* Control: explicit diagnostics still reach the guarded SQL operation. */
    struct md_inode_audit audit;
    assert(md_inode_audit(s,&audit)==-EIO && audit_attempts==1);
    md_inode_store_close(worker); md_inode_store_close(inspection); md_inode_store_close(s);
    reject_audits=0;
    puts("PASS ordinary admission, inspection, reconnect and file operations require no full integrity audit");
}
static void update(const char *path) {
    struct md_inode_store *s=NULL,*other=NULL;
    initialize(path);
    assert(!md_inode_store_open(path,0,&s));
    struct stat before,after;
    assert(!md_inode_stat(s,-1,"file",0,&before));
    selected=&future;
    assert(md_inode_store_open_inspect(path,&other)==-EPROTONOSUPPORT && !other);
    assert(md_inode_store_open(path,0,&other)==-EBUSY && !other);
    assert(scalar(s,"SELECT value FROM properties WHERE key='schema-revision'")==md_inode_store_revision());
    md_inode_store_close(s);
    assert(!md_inode_store_open(path,0,&s));
    assert(scalar(s,"SELECT value FROM properties WHERE key='schema-revision'")==md_inode_store_revision()+2);
    assert(scalar(s,"SELECT generation FROM future_metadata")==2);
    assert(!md_inode_stat(s,-1,"file",0,&after));
    assert(before.st_ino==after.st_ino && before.st_dev==after.st_dev);
    int count=admissions;
    for (int i=0;i<100;++i) check(s);
    assert(count==admissions);
    assert(!md_inode_store_open(path,0,&other)); check(other); md_inode_store_close(other);
    selected=NULL;
    assert(md_inode_store_open_inspect(path,&other)==-EPROTONOSUPPORT && !other);
    assert(md_inode_store_open(path,0,&other)==-EPROTONOSUPPORT && !other);
    md_inode_store_close(s);
    puts("PASS schema upgrade, stable identity/hardlinks, busy owner, inspect/downgrade rejection and no hot-path admission");
}
static void full(const char *path) {
    initialize(path);
    struct md_inode_store *s=NULL;
    selected=&future; fault=NO_SPACE;
    assert(md_inode_store_open(path,0,&s)==-ENOSPC && !s);
    selected=NULL; fault=NO_FAULT;
    assert(!md_inode_store_open(path,0,&s)); check(s);
    assert(scalar(s,"SELECT count(*) FROM sqlite_schema WHERE name='future_metadata'")==0);
    md_inode_store_close(s);
    selected=&future;
    assert(!md_inode_store_open(path,0,&s)); check(s); md_inode_store_close(s);
    puts("PASS ENOSPC rolls back metadata and revision together; retry keeps files");
}
static void interrupted(const char *path,int point,int rollback) {
    initialize(path);
    struct md_inode_store *s=NULL;
    if (rollback) {
        assert(!md_inode_store_open(path,0,&s));
        assert(sqlite3_exec(s->db,"PRAGMA journal_mode=DELETE",NULL,NULL,NULL)==SQLITE_OK);
        md_inode_store_close(s);
    }
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        selected=&future; fault=point; commit_count=0;
        (void)md_inode_store_open(path,0,&s); _exit(93);
    }
    int status; assert(waitpid(pid,&status,0)==pid && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
    if (point==KILL_STAGED) {
        selected=NULL;
        assert(!md_inode_store_open(path,0,&s)); check(s);
        assert(!scalar(s,"SELECT count(*) FROM sqlite_schema WHERE name='future_metadata'"));
        md_inode_store_close(s);
    }
    selected=&future;
    assert(!md_inode_store_open(path,0,&s)); check(s);
    assert(scalar(s,"SELECT value FROM properties WHERE key='schema-revision'")==md_inode_store_revision()+2);
    md_inode_store_close(s);
    puts("PASS process death before/after schema commit resumes without reapplying completed steps");
}
static void concurrent(const char *path) {
    initialize(path);
    int notify[2],resume[2]; assert(!pipe(notify) && !pipe(resume));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        close(notify[0]); close(resume[1]); notify_fd=notify[1]; continue_fd=resume[0];
        selected=&future; fault=PAUSE_STAGED;
        struct md_inode_store *s=NULL; assert(!md_inode_store_open(path,0,&s));
        md_inode_store_close(s); _exit(0);
    }
    close(notify[1]); close(resume[0]); char byte;
    /* EVENT_WAIT: child's exclusive schema admission; outer timeout fails a
     * missing notification instead of treating elapsed time as readiness. */
    assert(read(notify[0],&byte,1)==1);
    selected=&future; struct md_inode_store *s=NULL;
    assert(md_inode_store_open_exclusive(path,&s)==-EBUSY && !s);
    int contended[2]; assert(!pipe(contended));
    pid_t waiter=fork(); assert(waiter>=0);
    if (!waiter) {
        close(contended[0]); close(notify[0]); close(resume[1]);
        contention_fd=contended[1];
        assert(!md_inode_store_open(path,0,&s)); check(s);
        assert(scalar(s,"SELECT generation FROM future_metadata")==2);
        md_inode_store_close(s); _exit(0);
    }
    close(contended[1]);
    /* EVENT_WAIT: the ordinary opener reaches the held operation gate;
     * the outer test timeout fails a missing contention notification. */
    assert(read(contended[0],&byte,1)==1); close(contended[0]);
    int status; assert(waitpid(waiter,&status,WNOHANG)==0);
    assert(write(resume[1],&byte,1)==1); close(notify[0]); close(resume[1]);
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(waitpid(waiter,&status,0)==waiter && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!md_inode_store_open(path,0,&s)); check(s); md_inode_store_close(s);
    puts("PASS maintenance fails busy; ordinary admission waits for the complete schema upgrade");
}

static void invalid_steps(const char *path) {
    initialize(path);
    const char *const forbidden[]={"COMMIT", "PRAGMA user_version=18", "ATTACH ':memory:' AS other"};
    struct md_inode_store *s=NULL;
    for (unsigned i=0;i<sizeof(forbidden)/sizeof(*forbidden);++i) {
        char sql[160];
        snprintf(sql,sizeof(sql),"CREATE TABLE forbidden(value); %s;",forbidden[i]);
        const char *steps[32];
        assert(mdi_schema_current.count+1<sizeof(steps)/sizeof(*steps));
        memcpy(steps,mdi_schema_current.steps,mdi_schema_current.count*sizeof(*steps));
        steps[mdi_schema_current.count]=sql;
        const struct mdi_schema_plan plan={steps,mdi_schema_current.count+1}; selected=&plan;
        assert(md_inode_store_open(path,0,&s)==-EIO && !s);
        selected=NULL;
        assert(!md_inode_store_open(path,0,&s)); check(s);
        assert(scalar(s,"PRAGMA user_version")==17);
        assert(scalar(s,"SELECT value FROM properties WHERE key='schema-revision'")==md_inode_store_revision());
        assert(!scalar(s,"SELECT count(*) FROM sqlite_schema WHERE name='forbidden'"));
        md_inode_store_close(s);
    }
    puts("PASS invalid upgrade transaction/journal/database control rolls back without publishing a revision");
}

static void shared_source(const char *path,const char *instance) {
    initialize(path);
    struct md_inode_store *base=NULL,*copy=NULL,*other=NULL;
    assert(!md_inode_store_open(path,0,&base));
    assert(!md_inode_store_seal(base));
    assert(!md_inode_store_open(instance,1,&copy));
    assert(!md_inode_snapshot(base,copy));
    md_inode_store_close(base);
    selected=&future;
    assert(md_inode_store_open(path,0,&other)==-EBUSY && !other);
    md_inode_store_close(copy);
    assert(!md_inode_store_open(path,0,&base)); check(base); md_inode_store_close(base);
    selected=NULL;
    assert(!md_inode_store_open(instance,0,&copy)); check(copy); md_inode_store_close(copy);
    selected=&future;
    assert(!md_inode_store_open(instance,0,&copy)); check(copy);
    assert(!md_inode_store_open(path,0,&base));
    int fd=md_inode_open(copy,-1,"file",O_RDWR,0); assert(fd>=0);
    assert(pwrite(fd,"modified",8,0)==8); close(fd);
    check(base); md_inode_store_close(base); md_inode_store_close(copy);
    puts("PASS dependent source lease blocks upgrade; independent revisions preserve shared bodies and COW");
}
static void inspection(const char *root) {
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/inspection",root); initialize(path);
    struct md_inode_store *s=NULL; assert(!md_inode_store_open(path,0,&s));
    int config=openat(s->lifetime->root,"image-config.json",O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600);
    assert(config>=0 && write(config,"{}",2)==2); close(config);
    assert(!mdi_begin(s,1));
    const char *names[]={"fresh","revision-1"};
    for (int i=0;i<2;++i) {
        snprintf(path,sizeof(path),"%s/%s/objects",root,names[i]);
        int fd=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC), source=0; assert(fd>=0);
        assert(!mdi_source_register(s,fd,&source) && source==i+1); close(fd);
    }
    /* Source IDs are scope-local. The second dependency belongs only to a
     * retained projection; the first occurs in both scopes. */
    assert(!mdi_sql(s,"INSERT INTO sources SELECT id,path,device,inode,'11111111111111111111111111111111' FROM sources;"
        "DELETE FROM sources WHERE scope='00000000000000000000000000000000' AND id=2"));
    assert(!mdi_finish(s,0)); md_inode_store_close(s);
}
int main(int argc,char **argv) {
    if (argc==3 && !strcmp(argv[1],"--check-history-backup")) {
        struct md_inode_store *s=NULL; assert(!md_inode_store_open(argv[2],0,&s));
        check(s); native_value(s); assert(!scalar(s,"SELECT count(*) FROM sources")); md_inode_store_close(s);
        puts("PASS historical metadata survives portable backup without shared sources"); return 0;
    }
    assert(argc==2 && !mkdir(argv[1],0700));
    assert(mdi_schema_current.count+2<sizeof(future_steps)/sizeof(*future_steps));
    memcpy(future_steps,mdi_schema_current.steps,mdi_schema_current.count*sizeof(*future_steps));
    memcpy(future_steps+mdi_schema_current.count,upgrades,sizeof(upgrades));
    future=(struct mdi_schema_plan){future_steps,mdi_schema_current.count+2};
    char path[PATH_MAX];
    snprintf(path,sizeof(path),"%s/fresh",argv[1]); initialize(path);
    struct md_inode_store *fresh=NULL; assert(!md_inode_store_open(path,0,&fresh));
    for (int revision=1;revision<=4;++revision) {
        snprintf(path,sizeof(path),"%s/revision-%d",argv[1],revision); baseline(path,revision,fresh);
    }
    md_inode_store_close(fresh);
    snprintf(path,sizeof(path),"%s/lightweight",argv[1]); lightweight(path);
    snprintf(path,sizeof(path),"%s/update",argv[1]); update(path);
    snprintf(path,sizeof(path),"%s/full",argv[1]); full(path);
    snprintf(path,sizeof(path),"%s/staged",argv[1]); interrupted(path,KILL_STAGED,0);
    snprintf(path,sizeof(path),"%s/journal",argv[1]); interrupted(path,KILL_STAGED,1);
    snprintf(path,sizeof(path),"%s/committed",argv[1]); interrupted(path,KILL_COMMITTED,0);
    snprintf(path,sizeof(path),"%s/concurrent",argv[1]); concurrent(path);
    snprintf(path,sizeof(path),"%s/invalid",argv[1]); invalid_steps(path);
    char instance[PATH_MAX]; snprintf(instance,sizeof(instance),"%s/instance",argv[1]);
    snprintf(path,sizeof(path),"%s/base",argv[1]); shared_source(path,instance);
    inspection(argv[1]);
    return 0;
}
