#define _GNU_SOURCE
#include "inode_internal.h"
#include "inode_watch.h"
#include "inode_view.h"
#include "fs_operation.h"
#include "watch_source.h"
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/inotify.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr,"store watch line=%d %s errno=%d\n",__LINE__,#x,errno); exit(1); } } while (0)
static int64_t count(struct md_inode_store *s) {
    sqlite3_stmt *q = NULL;
    CHECK(!mdi_begin(s, 0));
    CHECK(!mdi_prepare(s, "SELECT count(*) FROM events", &q));
    CHECK(mdi_step(s, q) == SQLITE_ROW);
    int64_t result = sqlite3_column_int64(q, 0);
    sqlite3_finalize(q); CHECK(!mdi_finish(s, 0));
    return result;
}
static void crash(enum md_inode_checkpoint stage, void *context) {
    (void)context;
    if (stage == MD_NAMESPACE_STAGED) kill(getpid(), SIGKILL);
}
static void expect_record(int reader, struct md_inode_store *s, int wd, unsigned mask, const char *name) {
    union { struct inotify_event align; char bytes[1024]; } bytes;
    ssize_t n=md_inode_watch_read(s,reader,bytes.bytes,sizeof(bytes.bytes),NULL,NULL);
    CHECK(n==(ssize_t)(sizeof(bytes.align)+bytes.align.len));
    CHECK(bytes.align.wd==wd && bytes.align.mask==mask);
    CHECK(name ? bytes.align.len && !strcmp(bytes.align.name,name) : !bytes.align.len);
}
static void physical_create(int parent,const char *name) {
    int fd=openat(parent,name,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600); CHECK(fd>=0); close(fd);
}
static void directory_timestamps(const char *base) {
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/directory-times",base);
    struct md_inode_store *s,*writer;
    CHECK(!md_inode_store_open(path,1,&s) && !md_inode_store_open(path,0,&writer));
    CHECK(!md_inode_mkdir(writer,-1,"/dir",0700));
    int dir=md_inode_open(s,-1,"/dir",O_PATH|O_DIRECTORY,0); CHECK(dir>=0);
    int root=md_inode_open(s,-1,"/",O_PATH|O_DIRECTORY,0); CHECK(root>=0);
    int reader=md_inode_watch_create(s,IN_NONBLOCK); CHECK(reader>=0);
    int wd=md_inode_watch_add(s,reader,dir,IN_CREATE|IN_DELETE|IN_ATTRIB|IN_MODIFY); CHECK(wd>0);
    int root_wd=md_inode_watch_add(s,reader,root,IN_ATTRIB|IN_MODIFY); CHECK(root_wd>0);
    CHECK(!md_inode_symlink(writer,"target",-1,"/dir/link"));
    expect_record(reader,s,wd,IN_CREATE,"link");
    CHECK(md_inode_watch_read(s,reader,path,sizeof(path),NULL,NULL)==-EAGAIN);
    CHECK(!md_inode_unlink(writer,-1,"/dir/link",0));
    expect_record(reader,s,wd,IN_DELETE,"link");
    CHECK(md_inode_watch_read(s,reader,path,sizeof(path),NULL,NULL)==-EAGAIN);
    CHECK(!md_inode_metadata(writer,&(struct md_fs_request){.operation=MD_FS_UTIMENS,
        .directory={MD_INODE_ROOT},.path={"/dir"},
        .attributes={.seconds={1000000000,1000000001}}}));
    union { struct inotify_event align; char bytes[1024]; } bytes;
    ssize_t n=md_inode_watch_read(s,reader,bytes.bytes,sizeof(bytes.bytes),NULL,NULL);
    unsigned seen=0;
    for (ssize_t offset=0;offset<n;) {
        struct inotify_event *e=(void *)(bytes.bytes+offset);
        CHECK(e->mask==(IN_ATTRIB|IN_ISDIR));
        if (e->wd==wd) { CHECK(!e->len && !(seen&1)); seen|=1; }
        else { CHECK(e->wd==root_wd && e->len && !strcmp(e->name,"dir") && !(seen&2)); seen|=2; }
        offset+=sizeof(*e)+e->len;
    }
    CHECK(seen==3 && md_inode_watch_read(s,reader,path,sizeof(path),NULL,NULL)==-EAGAIN);
    close(reader); close(root); close(dir); md_inode_store_close(writer); md_inode_store_close(s);
    puts("PASS directory time updates keep logical namespace notifications without duplicate native attributes");
}
static void logical_create(struct md_inode_store *s,const char *path) {
    int fd=md_inode_create(s,-1,path,0600); CHECK(fd>=0); close(fd);
}
static int crash_after_commit(unsigned kind,void *context,void *statement,void *elapsed) {
    (void)context; (void)elapsed;
    if (kind==SQLITE_TRACE_PROFILE && !strcmp(sqlite3_sql(statement),"COMMIT")) kill(getpid(),SIGKILL);
    return 0;
}
static void source_readiness(const char *base) {
    char path[PATH_MAX],foreign_path[PATH_MAX],bytes[1024];
    snprintf(path,sizeof(path),"%s/readiness",base);
    snprintf(foreign_path,sizeof(foreign_path),"%s/readiness-foreign",base);
    struct md_inode_store *s,*writer,*foreign;
    CHECK(!md_inode_store_open(path,1,&s) && !md_inode_store_open(path,0,&writer));
    CHECK(!md_inode_store_open(foreign_path,1,&foreign));
    logical_create(writer,"/base");
    int root=md_inode_open(s,-1,"/",O_PATH|O_DIRECTORY,0); CHECK(root>=0);
    int foreign_root=md_inode_open(foreign,-1,"/",O_PATH|O_DIRECTORY,0); CHECK(foreign_root>=0);
    int reader=md_inode_watch_create(s,IN_NONBLOCK); CHECK(reader>=0);
    int wd=md_inode_watch_add(s,reader,root,IN_CREATE); CHECK(wd>0);
    int foreign_wd=md_inode_watch_add_from(s,foreign,reader,foreign_root,IN_CREATE); CHECK(foreign_wd>0);
    struct md_watch_source *provider; CHECK(!md_watch_source_new(&provider));
    int provider_wd=md_inode_watch_add_provider(s,reader,provider,7,S_IFREG,IN_MODIFY); CHECK(provider_wd>0);
    CHECK(md_inode_watch_read(s,reader,bytes,sizeof(bytes),NULL,NULL)==-EAGAIN);
    struct md_inode_statistics stats={0}; md_inode_measure(s,&stats);
    for (unsigned i=0;i<64;++i) {
        CHECK(!md_inode_watch_pump(s));
        CHECK(!md_inode_watch_bytes(s,reader));
        CHECK(md_inode_watch_read(s,reader,bytes,sizeof(bytes),NULL,NULL)==-EAGAIN);
    }
    logical_create(foreign,"/foreign"); expect_record(reader,s,foreign_wd,IN_CREATE,"foreign");
    md_watch_source_emit(provider,7,IN_MODIFY,0,NULL,0);
    expect_record(reader,s,provider_wd,IN_MODIFY,NULL);
    CHECK(!stats.step.calls && !stats.transaction.calls);
    pid_t pid=fork(); CHECK(pid>=0);
    if (!pid) {
        struct md_inode_store *other; CHECK(!md_inode_store_open(path,0,&other));
        CHECK(!sqlite3_trace_v2(other->db,SQLITE_TRACE_PROFILE,crash_after_commit,NULL));
        md_inode_link(other,-1,"/base",-1,"/committed",0);
        _exit(2);
    }
    int status;
    CHECK(waitpid(pid,&status,0)==pid && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
    expect_record(reader,s,wd,IN_CREATE,"committed");
    CHECK(md_inode_watch_read(s,reader,bytes,sizeof(bytes),NULL,NULL)==-EAGAIN);
    CHECK(stats.step.calls);
    close(reader); CHECK(!md_inode_watch_pump(s) && md_inode_watch_pollfd(s)==-1);
    md_watch_source_release(provider); close(root); close(foreign_root);
    md_inode_store_close(writer); md_inode_store_close(foreign); md_inode_store_close(s);
    puts("PASS event-driven watch sources: idle/foreign/provider IO avoids SQL; WAL recovers commit without a marker");
}
static void unrelated_io(struct md_inode_store *s) {
    int noisy=md_inode_open(s,-1,"/unsubscribed",O_RDONLY,0); CHECK(noisy>=0);
    char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",noisy);
    for (unsigned n=0;n<40000;++n) {
        int opened=open(path,O_RDONLY|O_CLOEXEC); CHECK(opened>=0); close(opened);
    }
    close(noisy);
}
static void native_transitions(const char *base) {
    char path[PATH_MAX];
    snprintf(path,sizeof(path),"%s/mixed",base);
    struct md_inode_store *s,*writer;
    CHECK(!md_inode_store_open(path,1,&s) && !md_inode_store_open(path,0,&writer));
    snprintf(path,sizeof(path),"%s/native",base); CHECK(!mkdir(path,0700));
    int native=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); CHECK(native>=0);
    int root=md_inode_open(s,-1,"/",O_PATH|O_DIRECTORY,0); CHECK(root>=0);
    int reader=md_inode_watch_create(s,IN_NONBLOCK|IN_CLOEXEC); CHECK(reader>=0);
    int native_wd=md_inode_watch_add(s,reader,native,IN_CREATE); CHECK(native_wd>0);
    struct md_inode_statistics stats={0}; md_inode_measure(s,&stats);
    physical_create(native,"before"); expect_record(reader,s,native_wd,IN_CREATE,"before");
    CHECK(!stats.step.calls && !stats.transaction.calls);
    logical_create(writer,"/unsubscribed");
    unrelated_io(writer);
    CHECK(md_inode_watch_read(s,reader,path,sizeof(path),NULL,NULL)==-EAGAIN);
    CHECK(!mdi_begin(writer,1));
    CHECK(!mdi_sql(writer,"UPDATE events SET sequence=sequence+70000"));
    CHECK(!mdi_finish(writer,0));
    CHECK(md_inode_watch_read(s,reader,path,sizeof(path),NULL,NULL)==-EAGAIN);
    int logical_wd=md_inode_watch_add(s,reader,root,IN_CREATE); CHECK(logical_wd>0);
    unrelated_io(writer);
    CHECK(md_inode_watch_read(s,reader,path,sizeof(path),NULL,NULL)==-EAGAIN);
    logical_create(writer,"/mixed"); expect_record(reader,s,logical_wd,IN_CREATE,"mixed");
    physical_create(native,"mixed"); expect_record(reader,s,native_wd,IN_CREATE,"mixed");
    int second=md_inode_watch_create(s,IN_NONBLOCK|IN_CLOEXEC); CHECK(second>=0);
    int second_wd=md_inode_watch_add(s,second,root,IN_CREATE); CHECK(second_wd>0);
    CHECK(!md_inode_watch_remove(s,reader,logical_wd));
    expect_record(reader,s,logical_wd,IN_IGNORED,NULL);
    logical_create(writer,"/second"); expect_record(second,s,second_wd,IN_CREATE,"second");
    CHECK(md_inode_watch_read(s,reader,path,sizeof(path),NULL,NULL)==-EAGAIN);
    close(second); CHECK(!md_inode_watch_pump(s));
    physical_create(native,"after-close"); expect_record(reader,s,native_wd,IN_CREATE,"after-close");
    unrelated_io(writer);
    CHECK(md_inode_watch_read(s,reader,path,sizeof(path),NULL,NULL)==-EAGAIN);
    logical_create(writer,"/before-oneshot");
    logical_wd=md_inode_watch_add(s,reader,root,IN_CREATE|IN_ONESHOT); CHECK(logical_wd>0);
    logical_create(writer,"/oneshot");
    union { struct inotify_event align; char bytes[1024]; } bytes;
    ssize_t n=md_inode_watch_read(s,reader,bytes.bytes,sizeof(bytes.bytes),NULL,NULL); CHECK(n==48);
    CHECK(bytes.align.wd==logical_wd && bytes.align.mask==IN_CREATE && !strcmp(bytes.align.name,"oneshot"));
    struct inotify_event *ignored=(void *)(bytes.bytes+32);
    CHECK(ignored->wd==logical_wd && ignored->mask==IN_IGNORED);
    uint64_t steps=stats.step.calls, transactions=stats.transaction.calls;
    physical_create(native,"after-oneshot"); expect_record(reader,s,native_wd,IN_CREATE,"after-oneshot");
    CHECK(stats.step.calls==steps && stats.transaction.calls==transactions);
    logical_create(writer,"/between");
    logical_wd=md_inode_watch_add(s,reader,root,IN_CREATE); CHECK(logical_wd>0);
    CHECK(md_inode_watch_read(s,reader,path,sizeof(path),NULL,NULL)==-EAGAIN);
    CHECK(!md_inode_link(writer,-1,"/between",-1,"/alias",0));
    struct pollfd ready={.fd=md_inode_watch_pollfd(s),.events=POLLIN};
    /* EVENT_WAIT: independent writer's WAL notification; timeout fails. */
    CHECK(poll(&ready,1,5000)==1 && (ready.revents&POLLIN));
    expect_record(reader,s,logical_wd,IN_CREATE,"alias");
    close(reader); CHECK(!md_inode_watch_pump(s) && md_inode_watch_pollfd(s)==-1);
    close(root); close(native); md_inode_store_close(writer); md_inode_store_close(s);
    puts("PASS native/logical transitions, mixed observers, remove/close/oneshot, late cursor, independent hardlink wakeup");
}
static void provider_lifetime(struct md_inode_store *s) {
    struct md_watch_source *source;
    CHECK(!md_watch_source_new(&source));
    int a=md_inode_watch_create(s,IN_NONBLOCK),b=md_inode_watch_create(s,IN_NONBLOCK);
    CHECK(a>=0 && b>=0);
    int wa=md_inode_watch_add_provider(s,a,source,7,S_IFREG,IN_MODIFY);
    int wb=md_inode_watch_add_provider(s,b,source,7,S_IFREG,IN_MODIFY);
    CHECK(wa>0 && wb>0);
    CHECK(md_inode_watch_add_provider(s,a,source,7,S_IFREG,IN_MODIFY|IN_MASK_ADD)==wa);
    CHECK(md_inode_watch_add_provider(s,a,source,7,S_IFREG,IN_MODIFY|IN_MASK_CREATE)==-EEXIST);
    close(a); CHECK(!md_inode_watch_pump(s));
    md_watch_source_emit(source,7,IN_MODIFY,0,NULL,0);
    expect_record(b,s,wb,IN_MODIFY,NULL);
    CHECK(!md_inode_watch_remove(s,b,wb));
    expect_record(b,s,wb,IN_IGNORED,NULL);
    /* No subscriber remains, but the instance can acquire a fresh listener. */
    wb=md_inode_watch_add_provider(s,b,source,7,S_IFREG,IN_MODIFY|IN_ONESHOT);
    CHECK(wb>0);
    md_watch_source_emit(source,7,IN_MODIFY,0,NULL,0);
    struct inotify_event events[2];
    CHECK(md_inode_watch_read(s,b,events,sizeof(events),NULL,NULL)==sizeof(events));
    CHECK(events[0].wd==wb && events[0].mask==IN_MODIFY);
    CHECK(events[1].wd==wb && events[1].mask==IN_IGNORED);
    wb=md_inode_watch_add_provider(s,b,source,7,S_IFREG,IN_MODIFY);
    CHECK(wb>0);
    md_watch_source_emit(source,0,IN_UNMOUNT,0,NULL,0);
    CHECK(md_inode_watch_read(s,b,events,sizeof(events),NULL,NULL)==sizeof(events));
    CHECK(events[0].wd==wb && events[0].mask==IN_UNMOUNT);
    CHECK(events[1].wd==wb && events[1].mask==IN_IGNORED);
    CHECK(md_inode_watch_add_provider(s,b,source,7,S_IFREG,IN_MODIFY)==-ENODEV);
    md_watch_source_release(source);
    close(b); CHECK(!md_inode_watch_pump(s) && md_inode_watch_pollfd(s)==-1);
    puts("PASS shared provider lifetime: mask updates, last close/remove/oneshot, re-registration and unmount");
}
static void quotas(const char *base) {
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/quotas",base);
    struct md_inode_store *a,*b;
    CHECK(!md_inode_store_open(path,1,&a) && !md_inode_store_open(path,0,&b));
    struct md_identity first=md_identity_new(1000,1000),other=md_identity_new(2000,2000);
    a->identity=b->identity=&first;
    CHECK(!md_user_namespace_set_limit(NULL,MD_USER_INOTIFY_INSTANCES,1));
    int x=md_inode_watch_create(a,IN_NONBLOCK); CHECK(x>=0);
    CHECK(md_inode_watch_create(b,IN_NONBLOCK)==-EMFILE);
    b->identity=&other;
    int y=md_inode_watch_create(b,IN_NONBLOCK); CHECK(y>=0);
    int alias=dup(x); CHECK(alias>=0); close(x);
    CHECK(!md_inode_watch_pump(a));
    CHECK(md_inode_watch_create(a,IN_NONBLOCK)==-EMFILE);
    close(alias);
    x=md_inode_watch_create(a,IN_NONBLOCK); CHECK(x>=0);
    CHECK(!md_user_namespace_set_limit(NULL,MD_USER_INOTIFY_INSTANCES,2));
    b->identity=&first;
    int z=md_inode_watch_create(b,IN_NONBLOCK); CHECK(z>=0);
    CHECK(!md_user_namespace_set_limit(NULL,MD_USER_INOTIFY_INSTANCES,0));
    CHECK(md_inode_watch_create(a,IN_NONBLOCK)==-EMFILE);
    CHECK(!md_user_namespace_set_limit(NULL,MD_USER_INOTIFY_WATCHES,1));
    struct md_watch_source *source; CHECK(!md_watch_source_new(&source));
    int wd=md_inode_watch_add_provider(a,x,source,7,S_IFREG,IN_MODIFY); CHECK(wd>0);
    CHECK(md_inode_watch_add_provider(b,z,source,7,S_IFREG,IN_MODIFY)==-ENOSPC);
    CHECK(md_inode_watch_add_provider(a,x,source,7,S_IFREG,IN_MODIFY|IN_MASK_ADD)==wd);
    /* Changing the caller cannot move a descriptor's charge to another UID. */
    b->identity=&other;
    CHECK(md_inode_watch_add_provider(b,z,source,8,S_IFREG,IN_MODIFY)==-ENOSPC);
    CHECK(md_inode_watch_add_provider(b,y,source,8,S_IFREG,IN_MODIFY)>0);
    CHECK(!md_inode_watch_remove(a,x,wd));
    CHECK(md_inode_watch_add_provider(b,z,source,7,S_IFREG,IN_MODIFY)>0);
    close(x); close(y); close(z);
    CHECK(!md_inode_watch_pump(a) && !md_inode_watch_pump(b));
    CHECK(!md_user_namespace_set_limit(NULL,MD_USER_INOTIFY_INSTANCES,1));
    x=md_inode_watch_create(a,IN_NONBLOCK); CHECK(x>=0);
    CHECK(md_inode_watch_add_provider(a,x,source,7,S_IFREG,IN_MODIFY)>0);
    close(x); md_inode_store_close(a); md_inode_store_close(b);
    md_watch_source_release(source); md_identity_release(&first); md_identity_release(&other);
    CHECK(!md_user_namespace_set_limit(NULL,MD_USER_INOTIFY_INSTANCES,MD_USER_DEFAULT_INOTIFY_INSTANCES));
    CHECK(!md_user_namespace_set_limit(NULL,MD_USER_INOTIFY_WATCHES,MD_USER_DEFAULT_INOTIFY_WATCHES));
    puts("PASS shared per-user watch quotas: independent workers/UIDs, live limits, retained FD owner and last close");
}
static void collected_view_journal(const char *base) {
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/collected",base);
    struct md_inode_store *s,*writer,*view=NULL;
    CHECK(!md_inode_store_open(path,1,&s) && !md_inode_store_open(path,0,&writer));
    int root=md_inode_open(s,-1,"/",O_PATH|O_DIRECTORY,0); CHECK(root>=0);
    int reader=md_inode_watch_create(s,IN_NONBLOCK|IN_CLOEXEC); CHECK(reader>=0);
    int wd=md_inode_watch_add(s,reader,root,IN_CREATE); CHECK(wd>0);
    logical_create(writer,"/before");
    CHECK(!md_inode_view_create(writer,MD_FILESYSTEM_OVERLAY,&view));
    logical_create(view,"/temporary");
    logical_create(writer,"/after");
    md_inode_store_close(view);
    CHECK(!mdi_begin(writer,1));
    CHECK(!mdi_views_collect(writer));
    CHECK(!mdi_commit(writer,0));
    union { struct inotify_event align; char bytes[1024]; } bytes;
    ssize_t length=md_inode_watch_read(s,reader,bytes.bytes,sizeof(bytes.bytes),NULL,NULL);
    CHECK(length==64);
    const struct inotify_event *first=(void *)bytes.bytes,*second=(void *)(bytes.bytes+32);
    CHECK(first->wd==wd && first->mask==IN_CREATE && !strcmp(first->name,"before"));
    CHECK(second->wd==wd && second->mask==IN_CREATE && !strcmp(second->name,"after"));
    CHECK(md_inode_watch_read(s,reader,bytes.bytes,sizeof(bytes.bytes),NULL,NULL)==-EAGAIN);
    close(reader); close(root); md_inode_store_close(writer); md_inode_store_close(s);
    puts("PASS overlay collection retains journal continuity without unrelated overflow");
}
int main(int argc, char **argv) {
    CHECK(argc == 2);
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask, SIGPIPE); CHECK(!sigprocmask(SIG_BLOCK, &mask, NULL));
    struct md_inode_store *s, *writer;
    CHECK(!md_inode_store_open(argv[1], 1, &s));
    CHECK(!md_inode_store_open(argv[1], 0, &writer));
    int file = md_inode_create(writer, -1, "/unobserved", 0600); CHECK(file >= 0); close(file);
    CHECK(!count(s));
    int root = md_inode_open(s, -1, "/", O_PATH | O_DIRECTORY, 0); CHECK(root >= 0);
    int reader = md_inode_watch_create(s, IN_NONBLOCK | IN_CLOEXEC); CHECK(reader >= 0);
    int wd = md_inode_watch_add(s, reader, root, IN_CREATE); CHECK(wd > 0);
    file = md_inode_create(writer, -1, "/observed", 0600); CHECK(file >= 0); close(file);
    CHECK(!md_inode_watch_pump(s)); CHECK(count(s) == 1);
    char bytes[1024];
    CHECK(md_inode_watch_read(s, reader, bytes, sizeof(bytes), NULL, NULL) == 32);
    struct inotify_event *e = (void *)bytes;
    CHECK(e->wd == wd && e->mask == IN_CREATE);
    CHECK(!md_inode_watch_pump(s));
    struct pollfd ready={.fd=md_inode_watch_pollfd(s),.events=POLLIN};
    CHECK(!poll(&ready,1,0));
    /* A logical hardlink changes only the namespace journal, not the backing
     * directory. The worker must wake on its WAL write before a checkpoint. */
    CHECK(!md_inode_link(writer,-1,"/observed",-1,"/alias",0));
    /* EVENT_WAIT: committed WAL event; the timeout fails missing wakeup. */
    CHECK(poll(&ready,1,5000)==1 && (ready.revents&POLLIN));
    CHECK(md_inode_watch_read(s,reader,bytes,sizeof(bytes),NULL,NULL)==32);
    e=(void *)bytes;
    CHECK(e->wd==wd && e->mask==IN_CREATE && !strcmp(e->name,"alias"));
    pid_t pid = fork(); CHECK(pid >= 0);
    if (!pid) {
        struct md_inode_store *other;
        CHECK(!md_inode_store_open(argv[1], 0, &other));
        md_inode_observe(other, crash, NULL);
        md_inode_create(other, -1, "/rolled-back", 0600);
        _exit(2);
    }
    int status; CHECK(waitpid(pid, &status, 0) == pid && WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL);
    CHECK(!md_inode_watch_pump(s));
    CHECK(md_inode_watch_bytes(s, reader) == 0 && count(s) == 3);
    struct stat st; CHECK(md_inode_stat(s, -1, "/rolled-back", 0, &st) == -ENOENT);
    puts("PASS watcher sees committed namespace only after writer SIGKILL rollback");
    int alias = dup(reader); CHECK(alias >= 0); close(reader);
    CHECK(!md_inode_watch_pump(s)); CHECK(md_inode_watch_pollfd(s) >= 0);
    close(alias); CHECK(!md_inode_watch_pump(s)); CHECK(md_inode_watch_pollfd(s) == -1);
    file = md_inode_create(writer, -1, "/after-close", 0600); CHECK(file >= 0); close(file);
    CHECK(count(s) == 3);
    puts("PASS kernel last-reader lifetime releases subscriptions and disables journal production");
    reader = md_inode_watch_create(s, IN_NONBLOCK); CHECK(reader >= 0);
    CHECK(md_inode_watch_add(s, reader, root, IN_CREATE) > 0);
    CHECK(!mdi_begin(writer, 1));
    CHECK(!mdi_sql(writer, "INSERT INTO events(sequence,parent,object,name,mask,cookie) "
        "SELECT sequence+70000,parent,object,name,mask,cookie FROM events ORDER BY sequence LIMIT 1"));
    CHECK(!mdi_finish(writer, 0));
    CHECK(!md_inode_watch_pump(s));
    CHECK(md_inode_watch_read(s, reader, bytes, sizeof(bytes), NULL, NULL) == 48);
    e = (void *)bytes;
    CHECK(e->wd == -1 && e->mask == IN_Q_OVERFLOW);
    puts("PASS a missing journal range publishes IN_Q_OVERFLOW");
    close(reader); close(root);
    CHECK(!md_inode_watch_pump(s));
    provider_lifetime(s);
    md_inode_store_close(writer); md_inode_store_close(s);
    native_transitions(argv[1]);
    directory_timestamps(argv[1]);
    collected_view_journal(argv[1]);
    source_readiness(argv[1]);
    quotas(argv[1]);
    return 0;
}
