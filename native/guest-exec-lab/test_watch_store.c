#define _GNU_SOURCE
#include "inode_internal.h"
#include "inode_watch.h"
#include <errno.h>
#include <fcntl.h>
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
    CHECK(md_inode_watch_bytes(s, reader) == 0 && count(s) == 1);
    struct stat st; CHECK(md_inode_stat(s, -1, "/rolled-back", 0, &st) == -ENOENT);
    puts("PASS watcher sees committed namespace only after writer SIGKILL rollback");
    int alias = dup(reader); CHECK(alias >= 0); close(reader);
    CHECK(!md_inode_watch_pump(s)); CHECK(md_inode_watch_pollfd(s) >= 0);
    close(alias); CHECK(!md_inode_watch_pump(s)); CHECK(md_inode_watch_pollfd(s) == -1);
    file = md_inode_create(writer, -1, "/after-close", 0600); CHECK(file >= 0); close(file);
    CHECK(count(s) == 1);
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
    md_inode_store_close(writer); md_inode_store_close(s);
    return 0;
}
