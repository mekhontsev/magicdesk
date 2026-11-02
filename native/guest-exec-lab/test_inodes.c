#define _GNU_SOURCE
#include "inode_store.h"
#include "inode_internal.h"
#include "fs_operation.h"
#include "linux_abi.h"
#include "test_sql_plan.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/fs.h>
#include <linux/openat2.h>
#include <signal.h>
#include <sqlite3.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHECK(expr) do { if (!(expr)) { \
    fprintf(stderr, "%s:%d: %s (errno=%d)\n", __FILE__, __LINE__, #expr, errno); exit(1); \
} } while (0)
static char root[PATH_MAX];
static void path(char out[PATH_MAX], const char *name) {
    CHECK(snprintf(out, PATH_MAX, "%s/%s", root, name) < PATH_MAX);
}
static struct md_inode_store *store(const char *name, int create) {
    char p[PATH_MAX]; path(p, name);
    struct md_inode_store *s = NULL;
    CHECK(md_inode_store_open(p, create, &s) == 0);
    sqlite3_stmt *q=NULL;
    CHECK(sqlite3_prepare_v2(s->db,"PRAGMA synchronous",-1,&q,NULL)==SQLITE_OK);
    CHECK(sqlite3_step(q)==SQLITE_ROW && sqlite3_column_int(q,0)==1);
    sqlite3_finalize(q);
    CHECK(mdi_sync(s,-1)==-EBADF);
    return s;
}
static struct stat state(struct md_inode_store *s, const char *name, nlink_t links) {
    struct stat st;
    CHECK(md_inode_stat(s, MD_INODE_ROOT, name, 0, &st) == 0);
    CHECK(S_ISREG(st.st_mode) && st.st_nlink == links && st.st_uid == getuid());
    return st;
}
static void content(int fd, const char *expected) {
    char bytes[32] = {0};
    CHECK(pread(fd, bytes, strlen(expected), 0) == (ssize_t)strlen(expected));
    CHECK(!memcmp(bytes, expected, strlen(expected)));
}
static void put(int fd, const char *value) {
    CHECK(pwrite(fd, value, strlen(value), 0) == (ssize_t)strlen(value));
}
static void byte(int fd, char value) { CHECK(write(fd, &value, 1) == 1); }
static char event(int fd) {
    char value;
    /* EVENT_WAIT: pipe from the peer process; runner timeout cancels the fixture
     * on a missing event. No time-based settling or readiness polling. */
    CHECK(read(fd, &value, 1) == 1);
    return value;
}
static void joined(pid_t pid, int signal) {
    int status;
    /* EVENT_WAIT: exact child termination; runner timeout cancels a stuck fixture. */
    CHECK(waitpid(pid, &status, 0) == pid);
    CHECK(signal ? WIFSIGNALED(status) && WTERMSIG(status) == signal
                 : WIFEXITED(status) && WEXITSTATUS(status) == 0);
}
static int listed(const char *name, void *context) {
    unsigned *count = context;
    CHECK(strcmp(name, "namespace.db") && strcmp(name, "objects"));
    ++*count;
    return 0;
}

static void descriptors(void) {
    struct md_inode_store *s = store("identity", 1);
    int a = md_inode_create(s, MD_INODE_ROOT, "first", 0600);
    CHECK(a >= 0);
    CHECK(ftruncate(a, 4096) == 0);
    put(a, "before");
    struct stat old = state(s, "first", 1);
    CHECK(md_inode_link(s, MD_INODE_ROOT, "first", MD_INODE_ROOT, "second", 0) == 0);
    CHECK(md_inode_link(s, MD_INODE_ROOT, "first", MD_INODE_ROOT, "second", 0) == -EEXIST);
    int b = md_inode_open(s, MD_INODE_ROOT, "second", O_RDWR, 0);
    CHECK(b >= 0);
    struct stat st = state(s, "second", 2), raw_a, raw_b;
    CHECK(st.st_ino == old.st_ino && st.st_dev == old.st_dev);
    CHECK(fstat(a, &raw_a) == 0 && fstat(b, &raw_b) == 0);
    CHECK(raw_a.st_ino == raw_b.st_ino && raw_a.st_dev == raw_b.st_dev);
    CHECK(md_inode_fstat(s, a, &st) == 0 && st.st_nlink == 2);
    content(b, "before"); put(b, "shared"); content(a, "shared");
    char *ma = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, a, 0);
    char *mb = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, b, 0);
    CHECK(ma != MAP_FAILED && mb != MAP_FAILED);
    memcpy(ma, "mapped", 6); CHECK(!memcmp(mb, "mapped", 6)); content(b, "mapped");
    CHECK(flock(a, LOCK_EX | LOCK_NB) == 0);
    CHECK(flock(b, LOCK_EX | LOCK_NB) == -1 && errno == EWOULDBLOCK);
    CHECK(flock(a, LOCK_UN) == 0 && flock(b, LOCK_EX | LOCK_NB) == 0);
    CHECK(flock(b, LOCK_UN) == 0);
    CHECK(fchmod(b, 0640) == 0);
    st = state(s, "first", 2); CHECK((st.st_mode & 0777) == 0640);
    CHECK(md_inode_unlink(s, MD_INODE_ROOT, "first", 0) == 0);
    CHECK(md_inode_stat(s, MD_INODE_ROOT, "first", 0, &st) == -ENOENT);
    CHECK(md_inode_fstat(s, a, &st) == 0 && st.st_nlink == 1);
    CHECK(md_inode_unlink(s, MD_INODE_ROOT, "second", 0) == 0);
    CHECK(md_inode_fstat(s, b, &st) == 0 && st.st_nlink == 0);
    put(b, "orphan"); content(a, "orphan"); CHECK(!memcmp(ma, "orphan", 6));
    int replacement = md_inode_create(s, MD_INODE_ROOT, "first", 0600);
    CHECK(replacement >= 0);
    st = state(s, "first", 1); CHECK(st.st_ino != old.st_ino);
    content(a, "orphan");
    CHECK(munmap(ma, 4096) == 0 && munmap(mb, 4096) == 0);
    CHECK(close(replacement) == 0 && close(b) == 0);
    /* Reopening the namespace cannot invalidate an already-open unlinked inode. */
    md_inode_store_close(s); s = store("identity", 0);
    CHECK(md_inode_fstat(s, a, &st) == 0 && st.st_nlink == 0);
    content(a, "orphan"); CHECK(close(a) == 0);
    struct md_inode_audit audit;
    CHECK(md_inode_audit(s, &audit) == 0);
    CHECK(audit.objects == 3 && audit.names == 1 && audit.detached == 1 && audit.untracked == 0);
    md_inode_store_close(s);
    puts("PASS inode identity, native FD/mmap/locks, link counts and open-unlinked lifetime");
}

static void dentry_names(void) {
    struct md_inode_store *s=store("dentry-names",1);
    CHECK(!md_inode_mkdir(s,-1,"dir",0755));
    struct md_open_completion first,alias,replacement;
    int a=md_inode_prepare_open(s,-1,"dir/a",O_CREAT|O_EXCL|O_RDWR,0644,0,&first);
    CHECK(a>=0);
    CHECK(!md_inode_link(s,-1,"dir/a",-1,"dir/b",0));
    int b=md_inode_prepare_open(s,-1,"dir/b",O_PATH|O_NOFOLLOW,0,0,&alias);
    CHECK(b>=0 && alias.dentry);
    char text[PATH_MAX];
    CHECK(!md_inode_dentry_path(s,a,first.dentry,first.native_leaf,text,sizeof(text)) && !strcmp(text,"/dir/a"));
    CHECK(!md_inode_dentry_path(s,b,alias.dentry,alias.native_leaf,text,sizeof(text)) && !strcmp(text,"/dir/b"));
    struct md_inode_store *peer=store("dentry-names",0);
    CHECK(!md_inode_rename(peer,-1,"dir/b",-1,"dir/c",0));
    CHECK(!md_inode_rename(peer,-1,"dir",-1,"moved",0));
    CHECK(!md_inode_dentry_path(s,b,alias.dentry,0,text,sizeof(text)) && !strcmp(text,"/moved/c"));
    CHECK(!md_inode_unlink(peer,-1,"moved/c",0));
    CHECK(!md_inode_link(peer,-1,"moved/a",-1,"moved/c",0));
    CHECK(!md_inode_dentry_path(s,b,alias.dentry,0,text,sizeof(text)) && !strcmp(text,"/moved/c (deleted)"));
    int c=md_inode_prepare_open(s,-1,"moved/c",O_RDONLY,0,0,&replacement);
    CHECK(c>=0 && replacement.dentry!=alias.dentry);
    CHECK(!md_inode_dentry_path(s,c,replacement.dentry,0,text,sizeof(text)) && !strcmp(text,"/moved/c"));
    int other=md_inode_prepare_open(peer,-1,"moved/d",O_CREAT|O_EXCL|O_RDWR,0644,0,&replacement);
    CHECK(other>=0);
    CHECK(!md_inode_rename(peer,-1,"moved/a",-1,"moved/d",RENAME_EXCHANGE));
    CHECK(!md_inode_dentry_path(s,a,first.dentry,first.native_leaf,text,sizeof(text)) && !strcmp(text,"/moved/d"));
    CHECK(!md_inode_dentry_path(s,other,replacement.dentry,replacement.native_leaf,text,sizeof(text)) && !strcmp(text,"/moved/a"));
    CHECK(md_inode_dentry_path(s,other,alias.dentry,0,text,sizeof(text))==-ESTALE);
    CHECK(!md_inode_rename(peer,-1,"moved/d",-1,"moved/a",0));
    CHECK(!md_inode_dentry_path(s,other,replacement.dentry,replacement.native_leaf,text,sizeof(text)) && !strcmp(text,"/moved/a (deleted)"));
    CHECK(!md_inode_dentry_path(s,a,first.dentry,first.native_leaf,text,sizeof(text)) && !strcmp(text,"/moved/a"));
    md_inode_store_close(peer); md_inode_store_close(s); s=store("dentry-names",0);
    CHECK(!md_inode_dentry_path(s,b,alias.dentry,0,text,sizeof(text)) && !strcmp(text,"/moved/c (deleted)"));
    CHECK(md_inode_path(s,b,text,sizeof(text))==-ENOTSUP);
    close(a); close(b); close(c); close(other);
    struct md_inode_audit audit; CHECK(!md_inode_audit(s,&audit));
    md_inode_store_close(s);
    puts("PASS exact dentry: hardlinks, native promotion, peer rename/exchange, unlink/relink, replacement and reopen");
}

static void namespace(void) {
    struct md_inode_store *s = store("names", 1);
    int a = md_inode_create(s, MD_INODE_ROOT, "a", 0600), b = md_inode_create(s, MD_INODE_ROOT, "b", 0600);
    CHECK(a >= 0 && b >= 0);
    put(a, "A"); put(b, "B");
    CHECK(md_inode_link(s, MD_INODE_ROOT, "a", MD_INODE_ROOT, "alias", 0) == 0);
    struct stat sa = state(s, "a", 2), sb = state(s, "b", 1);
    CHECK(md_inode_rename(s, MD_INODE_ROOT, "a", MD_INODE_ROOT, "alias", 0) == 0);
    CHECK(state(s, "a", 2).st_ino == state(s, "alias", 2).st_ino);
    CHECK(md_inode_rename(s, MD_INODE_ROOT, "a", MD_INODE_ROOT, "alias", RENAME_NOREPLACE) == -EEXIST);
    CHECK(md_inode_rename(s, MD_INODE_ROOT, "a", MD_INODE_ROOT, "a", RENAME_NOREPLACE) == -EEXIST);
    CHECK(md_inode_rename(s, MD_INODE_ROOT, "a", MD_INODE_ROOT, "b", RENAME_NOREPLACE) == -EEXIST);
    CHECK(md_inode_rename(s, MD_INODE_ROOT, "a", MD_INODE_ROOT, "absent", RENAME_EXCHANGE) == -ENOENT);
    CHECK(md_inode_rename(s, MD_INODE_ROOT, "a", MD_INODE_ROOT, "b", RENAME_EXCHANGE) == 0);
    CHECK(state(s, "a", 1).st_ino == sb.st_ino && state(s, "b", 2).st_ino == sa.st_ino);
    CHECK(md_inode_rename(s, MD_INODE_ROOT, "b", MD_INODE_ROOT, "a", 0) == 0);
    CHECK(state(s, "a", 2).st_ino == sa.st_ino);
    struct stat st;
    CHECK(md_inode_fstat(s, b, &st) == 0 && st.st_nlink == 0);
    content(a, "A"); content(b, "B");
    CHECK(md_inode_open(s, MD_INODE_ROOT, "b", O_RDONLY, 0) == -ENOENT);
    CHECK(md_inode_link(s, MD_INODE_ROOT, "absent", MD_INODE_ROOT, "anything", 0) == -ENOENT);
    CHECK(md_inode_unlink(s, MD_INODE_ROOT, "absent", 0) == -ENOENT);
    CHECK(md_inode_rename(s, MD_INODE_ROOT, "absent", MD_INODE_ROOT, "absent", 0) == -ENOENT);
    CHECK(md_inode_create(s, MD_INODE_ROOT, "a", 0600) == -EEXIST);
    int escaped = md_inode_create(s, MD_INODE_ROOT, "../escape", 0600);
    CHECK(escaped >= 0); close(escaped);
    CHECK(md_inode_unlink(s, MD_INODE_ROOT, "/escape", 0) == 0);
    CHECK(md_inode_create(s, MD_INODE_ROOT, "privileged", 04755) == -ENOTSUP);
    CHECK(md_inode_open(s, MD_INODE_ROOT, "a", O_TMPFILE | O_RDWR, 0) == -ENOTSUP);
    CHECK(md_inode_rename(s, MD_INODE_ROOT, "a", MD_INODE_ROOT, "alias", RENAME_WHITEOUT) == -EINVAL);
    CHECK(md_inode_open(s, MD_INODE_ROOT, "", O_RDONLY, 0) == -ENOENT);
    int rootfd = md_inode_open(s, MD_INODE_ROOT, "..", O_RDONLY | O_DIRECTORY, 0);
    CHECK(rootfd >= 0); close(rootfd);
    CHECK(md_inode_fstat(s, -1, &st) == -EBADF);
    int dev = open("/dev/null", O_RDONLY);
    CHECK(dev >= 0 && md_inode_fstat(s, dev, &st) == -EXDEV); close(dev);
    char non_utf8[] = {'x', (char)0xff, 0};
    CHECK(md_inode_link(s, MD_INODE_ROOT, "a", MD_INODE_ROOT, non_utf8, 0) == 0);
    CHECK(state(s, non_utf8, 3).st_ino == sa.st_ino);
    unsigned count = 0;
    CHECK(md_inode_list(s, MD_INODE_ROOT, "/", listed, &count) == 0 && count == 3);
    int trunc = md_inode_open(s, MD_INODE_ROOT, non_utf8, O_WRONLY | O_TRUNC, 0);
    CHECK(trunc >= 0 && close(trunc) == 0 && fstat(a, &st) == 0 && st.st_size == 0);
    struct md_inode_audit audit;
    CHECK(md_inode_audit(s, &audit) == 0 && audit.names == 3 && audit.detached == 2);
    CHECK(close(a) == 0 && close(b) == 0);
    md_inode_store_close(s);
    puts("PASS atomic rename/exchange/replace, same-inode no-op, byte names and explicit limits");
}

enum operation { CREATE, LINK, UNLINK, RENAME, EXCHANGE, MKDIR, SYMLINK, MOVE_DIR, SWAP_DIR, REMOVE_DIR };
struct gate { int ready, resume; enum md_inode_checkpoint point; };
static void gate(enum md_inode_checkpoint point, void *context) {
    struct gate *g = context;
    if (point != g->point) return;
    byte(g->ready, 'R'); CHECK(event(g->resume) == 'C');
}
static void apply(struct md_inode_store *s, enum operation op) {
    int r;
    switch (op) {
    case CREATE: r = md_inode_create(s, MD_INODE_ROOT, "new", 0600); CHECK(r >= 0); close(r); return;
    case LINK: r = md_inode_link(s, MD_INODE_ROOT, "a", MD_INODE_ROOT, "new", 0); break;
    case UNLINK: r = md_inode_unlink(s, MD_INODE_ROOT, "a", 0); break;
    case RENAME: r = md_inode_rename(s, MD_INODE_ROOT, "a", MD_INODE_ROOT, "b", 0); break;
    case EXCHANGE: r = md_inode_rename(s, MD_INODE_ROOT, "a", MD_INODE_ROOT, "b", RENAME_EXCHANGE); break;
    case MKDIR: r = md_inode_mkdir(s, MD_INODE_ROOT, "right/new", 0755); break;
    case SYMLINK: r = md_inode_symlink(s, "../left/sub/value", MD_INODE_ROOT, "right/new"); break;
    case MOVE_DIR: r = md_inode_rename(s, MD_INODE_ROOT, "left/sub", MD_INODE_ROOT, "right/dst", 0); break;
    case SWAP_DIR: r = md_inode_rename(s, MD_INODE_ROOT, "left/sub", MD_INODE_ROOT, "right/dst", RENAME_EXCHANGE); break;
    case REMOVE_DIR: r = md_inode_unlink(s, MD_INODE_ROOT, "right/dst", AT_REMOVEDIR); break;
    default: r = -EINVAL;
    }
    CHECK(r == 0);
}
static void killed_transaction(const char *name, enum operation op, enum md_inode_checkpoint point) {
    int ready[2], resume[2]; CHECK(pipe(ready) == 0 && pipe(resume) == 0);
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        close(ready[0]); close(resume[1]);
        struct md_inode_store *s = store(name, 0);
        struct gate g = {ready[1], resume[0], point};
        CHECK(!md_inode_operation_window_begin(s));
        md_inode_observe(s, gate, &g); apply(s, op);
        _exit(99); /* Every selected checkpoint must be observed, not skipped. */
    }
    close(ready[1]); close(resume[0]);
    CHECK(event(ready[0]) == 'R');
    if (point == MD_NAMESPACE_STAGED) {
        char journal[PATH_MAX];
        CHECK(snprintf(journal, sizeof(journal), "%s/%s/namespace.db-wal", root, name) < PATH_MAX);
        struct stat st; CHECK(stat(journal, &st) == 0 && st.st_size > 0);
    }
    CHECK(kill(child, SIGKILL) == 0); joined(child, SIGKILL);
    close(ready[0]); close(resume[1]);
}
static void recovery(enum operation op, enum md_inode_checkpoint point, unsigned serial) {
    char name[64]; snprintf(name, sizeof(name), "crash-%u", serial);
    struct md_inode_store *s = store(name, 1);
    int a = md_inode_create(s, MD_INODE_ROOT, "a", 0600), b = md_inode_create(s, MD_INODE_ROOT, "b", 0600);
    CHECK(a >= 0 && b >= 0); put(a, "A"); put(b, "B");
    CHECK(fsync(a) == 0 && fsync(b) == 0);
    struct stat sa = state(s, "a", 1), sb = state(s, "b", 1);
    close(a); close(b); md_inode_store_close(s);
    killed_transaction(name, op, point);
    s = store(name, 0);
    struct stat st;
    int committed = point == MD_NAMESPACE_COMMITTED;
    if (!committed || op == CREATE || op == LINK) {
        CHECK(state(s, "a", committed && op == LINK ? 2 : 1).st_ino == sa.st_ino);
        CHECK(state(s, "b", 1).st_ino == sb.st_ino);
    } else if (op == UNLINK) {
        CHECK(md_inode_stat(s, MD_INODE_ROOT, "a", 0, &st) == -ENOENT && state(s, "b", 1).st_ino == sb.st_ino);
    } else if (op == RENAME) {
        CHECK(md_inode_stat(s, MD_INODE_ROOT, "a", 0, &st) == -ENOENT && state(s, "b", 1).st_ino == sa.st_ino);
    } else {
        CHECK(state(s, "a", 1).st_ino == sb.st_ino && state(s, "b", 1).st_ino == sa.st_ino);
    }
    if (committed && (op == CREATE || op == LINK)) {
        st = state(s, "new", op == LINK ? 2 : 1);
        if (op == LINK) CHECK(st.st_ino == sa.st_ino);
    } else CHECK(md_inode_stat(s, MD_INODE_ROOT, "new", 0, &st) == -ENOENT);
    struct md_inode_audit audit;
    CHECK(md_inode_audit(s, &audit) == 0);
    CHECK(audit.untracked == (unsigned)(op == CREATE && !committed));
    CHECK(audit.objects == (unsigned)(3 + (committed && op == CREATE)));
    CHECK(audit.detached == (unsigned)(committed && (op == UNLINK || op == RENAME)));
    md_inode_store_close(s);
}

struct release_writer { int fd, calls; };
static void release_on_contention(enum md_inode_checkpoint point, void *context) {
    if (point != MD_STORE_CONTENDED) return;
    struct release_writer *release = context;
    CHECK(++release->calls == 1);
    byte(release->fd, 'C');
}
static void contention(void) {
    struct md_inode_store *s = store("concurrent", 1);
    int retained = md_inode_create(s, MD_INODE_ROOT, "a", 0600); CHECK(retained >= 0);
    put(retained, "shared"); md_inode_store_close(s);
    int ready[2], resume[2]; CHECK(pipe(ready) == 0 && pipe(resume) == 0);
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        close(ready[0]); close(resume[1]); close(retained);
        s = store("concurrent", 0);
        byte(ready[1], 'O'); CHECK(event(resume[0]) == 'G');
        struct gate g = {ready[1], resume[0], MD_NAMESPACE_STAGED};
        md_inode_observe(s, gate, &g); CHECK(md_inode_link(s, MD_INODE_ROOT, "a", MD_INODE_ROOT, "child", 0) == 0);
        md_inode_store_close(s); _exit(0);
    }
    close(ready[1]); close(resume[0]); CHECK(event(ready[0]) == 'O');
    s = store("concurrent", 0);
    byte(resume[1], 'G'); CHECK(event(ready[0]) == 'R');
    /* Release the writer only after a real kernel-lock conflict. The read
     * enters SQLite after commit; it cannot expose staged names or EAGAIN. */
    struct release_writer release = {resume[1], 0};
    md_inode_observe(s, release_on_contention, &release);
    struct stat st;
    CHECK(md_inode_fstat(s, retained, &st) == 0 && st.st_nlink == 2);
    CHECK(release.calls == 1);
    md_inode_observe(s, NULL, NULL);
    joined(child, 0);
    close(ready[0]); close(resume[1]);
    CHECK(md_inode_fstat(s, retained, &st) == 0 && st.st_nlink == 2);
    CHECK(md_inode_link(s, MD_INODE_ROOT, "a", MD_INODE_ROOT, "parent", 0) == 0);
    CHECK(md_inode_fstat(s, retained, &st) == 0 && st.st_nlink == 3);
    int other = md_inode_open(s, MD_INODE_ROOT, "child", O_RDWR, 0); CHECK(other >= 0);
    put(other, "across"); content(retained, "across");
    CHECK(md_inode_unlink(s, MD_INODE_ROOT, "child", 0) == 0 && md_inode_unlink(s, MD_INODE_ROOT, "a", 0) == 0);
    CHECK(md_inode_unlink(s, MD_INODE_ROOT, "parent", 0) == 0);
    CHECK(md_inode_fstat(s, retained, &st) == 0 && st.st_nlink == 0);
    close(other); close(retained); md_inode_store_close(s);
    puts("PASS cross-process commit visibility and event-driven store admission without replay");
}

static void multiple_stores(void) {
    struct md_inode_store *s = store("multiple", 1);
    int retained = md_inode_create(s, MD_INODE_ROOT, "base", 0600); CHECK(retained >= 0);
    md_inode_store_close(s);
    int start[2]; CHECK(pipe(start) == 0);
    pid_t children[4];
    for (unsigned i = 0; i < 4; ++i) {
        children[i] = fork(); CHECK(children[i] >= 0);
        if (!children[i]) {
            close(start[1]); CHECK(event(start[0]) == 'G'); close(start[0]);
            s = store("multiple", 0);
            for (unsigned n = 0; n < 24; ++n) {
                char name[64], alias[64];
                snprintf(name, sizeof(name), "file-%u-%u", i, n);
                snprintf(alias, sizeof(alias), "alias-%u-%u", i, n);
                int fd = md_inode_create(s, MD_INODE_ROOT, name, 0600); CHECK(fd >= 0);
                CHECK(md_inode_link(s, MD_INODE_ROOT, name, MD_INODE_ROOT, alias, 0) == 0);
                struct stat st; CHECK(md_inode_fstat(s, fd, &st) == 0 && st.st_nlink == 2);
                CHECK(md_inode_unlink(s, MD_INODE_ROOT, name, 0) == 0);
                CHECK(md_inode_unlink(s, MD_INODE_ROOT, alias, 0) == 0);
                CHECK(md_inode_fstat(s, fd, &st) == 0 && st.st_nlink == 0);
                CHECK(md_inode_fstat(s, retained, &st) == 0 && st.st_nlink == 1);
                close(fd);
            }
            md_inode_store_close(s); close(retained); _exit(0);
        }
    }
    close(start[0]); for (unsigned i = 0; i < 4; ++i) byte(start[1], 'G'); close(start[1]);
    for (unsigned i = 0; i < 4; ++i) joined(children[i], 0);
    s = store("multiple", 0);
    struct md_inode_audit audit; CHECK(md_inode_audit(s, &audit) == 0);
    CHECK(audit.names == 1 && audit.detached == 96 && audit.untracked == 0);
    close(retained); md_inode_store_close(s);
    puts("PASS four independent store owners: concurrent open/stat/link/unlink without transient errors");
}

static void exec_child(const char *name) {
    struct md_inode_store *s = store(name, 0);
    CHECK(md_inode_link(s, MD_INODE_ROOT, "a", MD_INODE_ROOT, "exec-alias", 0) == 0);
    int fd = md_inode_open(s, MD_INODE_ROOT, "exec-alias", O_RDWR, 0); CHECK(fd >= 0);
    put(fd, "exec"); close(fd); md_inode_store_close(s);
}
static void across_exec(const char *self) {
    struct md_inode_store *s = store("exec", 1);
    int fd = md_inode_create(s, MD_INODE_ROOT, "a", 0600); CHECK(fd >= 0);
    put(fd, "init"); md_inode_store_close(s);
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        execl(self, self, root, "exec-child", (char *)NULL);
        _exit(100);
    }
    joined(child, 0);
    s = store("exec", 0); content(fd, "exec");
    struct stat st; CHECK(md_inode_fstat(s, fd, &st) == 0 && st.st_nlink == 2);
    close(fd); md_inode_store_close(s);
    puts("PASS independent exec connection and retained descriptor identity");
}
static int directory(struct md_inode_store *s, const char *name) {
    int fd = md_inode_open(s, MD_INODE_ROOT, name, O_RDONLY | O_DIRECTORY, 0);
    CHECK(fd >= 0); return fd;
}
static void expected_path(struct md_inode_store *s, int fd, const char *expected) {
    char value[PATH_MAX];
    CHECK(md_inode_path(s, fd, value, sizeof(value)) == 0 && !strcmp(value, expected));
}
static void hierarchy(void) {
    struct md_inode_store *s = store("tree", 1);
    CHECK(md_inode_mkdir(s, MD_INODE_ROOT, "left", 0755) == 0);
    CHECK(md_inode_mkdir(s, MD_INODE_ROOT, "right/", 0755) == 0);
    CHECK(md_inode_mkdir(s, MD_INODE_ROOT, "left/sub", 0755) == 0);
    int sub = directory(s, "left/sub"), left = directory(s, "left"), right = directory(s, "right");
    int file = md_inode_create(s, sub, "value", 0600); CHECK(file >= 0); put(file, "retained");
    struct stat st, initial;
    CHECK(md_inode_fstat(s, sub, &initial) == 0 && initial.st_nlink == 2);
    CHECK(md_inode_fstat(s, left, &st) == 0 && st.st_nlink == 3);
    CHECK(md_inode_stat(s, MD_INODE_ROOT, "/", 0, &st) == 0 && st.st_nlink == 4);
    expected_path(s, sub, "/left/sub");
    expected_path(s, file, "/left/sub/value");
    CHECK(md_inode_rename(s, left, "sub", right, "moved", 0) == 0);
    expected_path(s, sub, "/right/moved");
    expected_path(s, file, "/right/moved/value");
    CHECK(md_inode_fstat(s, sub, &st) == 0 && st.st_ino == initial.st_ino);
    CHECK(md_inode_fstat(s, left, &st) == 0 && st.st_nlink == 2);
    CHECK(md_inode_fstat(s, right, &st) == 0 && st.st_nlink == 3);
    CHECK(md_inode_stat(s, sub, "..", 0, &st) == 0);
    struct stat parent; CHECK(md_inode_fstat(s, right, &parent) == 0 && parent.st_ino == st.st_ino);
    int opened = md_inode_open(s, sub, "./value", O_RDONLY, 0); CHECK(opened >= 0); content(opened, "retained"); close(opened);
    CHECK(md_inode_rename(s, MD_INODE_ROOT, "right", sub, "cycle", 0) == -EINVAL);
    CHECK(md_inode_rename(s, MD_INODE_ROOT, "right", right, "moved", RENAME_EXCHANGE) == -EINVAL);
    CHECK(md_inode_link(s, right, "moved", left, "forbidden", 0) == -EPERM);
    CHECK(md_inode_unlink(s, right, "moved", AT_REMOVEDIR) == -ENOTEMPTY);
    CHECK(md_inode_unlink(s, right, "moved", 0) == -EISDIR);
    CHECK(md_inode_mkdir(s, left, "empty", 0755) == 0);
    int empty = md_inode_open(s, left, "empty", O_RDONLY | O_DIRECTORY, 0); CHECK(empty >= 0);
    CHECK(md_inode_rename(s, right, "moved", left, "empty", 0) == 0);
    expected_path(s, sub, "/left/empty");
    char value[PATH_MAX]; CHECK(md_inode_path(s, empty, value, sizeof(value)) == -ENOENT);
    CHECK(md_inode_fstat(s, empty, &st) == 0 && st.st_nlink == 0);
    CHECK(md_inode_create(s, empty, "lost", 0600) == -ENOENT);
    CHECK(md_inode_stat(s, empty, ".", 0, &st) == 0 && st.st_nlink == 0);
    CHECK(md_inode_stat(s, empty, "..", 0, &st) == 0);
    CHECK(md_inode_unlink(s, sub, "value", 0) == 0);
    expected_path(s, file, "/left/empty/value (deleted)");
    int replacement = md_inode_create(s, sub, "value", 0600); CHECK(replacement >= 0);
    expected_path(s, file, "/left/empty/value (deleted)");
    expected_path(s, replacement, "/left/empty/value");
    CHECK(md_inode_link(s, sub, "value", left, "alias", 0) == 0);
    CHECK(md_inode_path(s, replacement, value, sizeof(value)) == -ENOTSUP);
    CHECK(md_inode_unlink(s, sub, "value", 0) == 0);
    CHECK(md_inode_path(s, replacement, value, sizeof(value)) == -ENOTSUP);
    CHECK(md_inode_unlink(s, left, "alias", 0) == 0);
    close(replacement);
    CHECK(md_inode_unlink(s, left, "empty", AT_REMOVEDIR) == 0);
    CHECK(md_inode_fstat(s, sub, &st) == 0 && st.st_nlink == 0);
    CHECK(md_inode_path(s, sub, value, sizeof(value)) == -ENOENT);
    content(file, "retained");
    CHECK(md_inode_stat(s, file, "relative", 0, &st) == -ENOTDIR);
    CHECK(md_inode_stat(s, file, "/left", 0, &st) == 0);
    CHECK(md_inode_stat(s, -42, "relative", 0, &st) == -EBADF);
    CHECK(md_inode_stat(s, -42, "/left", 0, &st) == 0);
    struct md_inode_store *foreign = store("foreign", 1);
    CHECK(md_inode_stat(foreign, left, ".", 0, &st) == -EXDEV);
    md_inode_store_close(foreign);
    struct md_inode_audit audit; CHECK(md_inode_audit(s, &audit) == 0);
    close(file); close(sub); close(empty); close(left); close(right); md_inode_store_close(s);
    puts("PASS hierarchy, stable dirfd, parent moves, cycle rejection and detached directories");
}
static void symlinks(void) {
    struct md_inode_store *s = store("symlinks", 1);
    CHECK(md_inode_mkdir(s, MD_INODE_ROOT, "a", 0755) == 0);
    CHECK(md_inode_mkdir(s, MD_INODE_ROOT, "b", 0755) == 0);
    int a = directory(s, "a"), b = directory(s, "b");
    int file = md_inode_create(s, b, "value", 0600); CHECK(file >= 0); put(file, "target");
    CHECK(md_inode_symlink(s, "../b/value", a, "relative") == 0);
    CHECK(md_inode_symlink(s, "/b", a, "absolute") == 0);
    CHECK(md_inode_symlink(s, "absolute", a, "chain") == 0);
    int fd = md_inode_open(s, a, "chain/./value", O_RDONLY, 0); CHECK(fd >= 0); content(fd, "target"); close(fd);
    fd = md_inode_open(s, a, "relative", O_RDONLY, 0); CHECK(fd >= 0); content(fd, "target"); close(fd);
    struct stat link, target, st;
    CHECK(md_inode_stat(s, a, "relative", AT_SYMLINK_NOFOLLOW, &link) == 0 && S_ISLNK(link.st_mode));
    CHECK(md_inode_fstat(s, file, &target) == 0);
    CHECK(md_inode_stat(s, a, "relative", 0, &st) == 0 && st.st_ino == target.st_ino);
    CHECK(md_inode_link(s, a, "relative", b, "hard-symlink", 0) == 0);
    CHECK(md_inode_stat(s, b, "hard-symlink", AT_SYMLINK_NOFOLLOW, &st) == 0
        && st.st_ino == link.st_ino && st.st_nlink == 2);
    CHECK(md_inode_link(s, a, "relative", a, "hard-file", AT_SYMLINK_FOLLOW) == 0);
    CHECK(md_inode_stat(s, a, "hard-file", 0, &st) == 0 && st.st_ino == target.st_ino && st.st_nlink == 2);
    CHECK(md_inode_open(s, a, "relative", O_RDONLY | O_NOFOLLOW, 0) == -ELOOP);
    fd = md_inode_open(s, a, "relative", O_PATH | O_NOFOLLOW, 0); CHECK(fd >= 0);
    CHECK(md_inode_fstat(s, fd, &st) == 0 && S_ISLNK(st.st_mode) && st.st_ino == link.st_ino);
    char bytes[PATH_MAX] = {0};
    CHECK(md_inode_readlink(s, fd, "", bytes, sizeof(bytes)) == 10 && !memcmp(bytes, "../b/value", 10));
    CHECK(readlinkat(a, "", bytes, sizeof(bytes)) == -1 && errno == ENOENT);
    CHECK(md_inode_readlink(s, a, "", bytes, sizeof(bytes)) == -ENOENT);
    CHECK(readlinkat(file, "", bytes, sizeof(bytes)) == -1 && errno == ENOENT);
    CHECK(md_inode_readlink(s, file, "", bytes, sizeof(bytes)) == -ENOENT); close(fd);
    CHECK(md_inode_readlink(s, a, "relative", bytes, 3) == 3 && !memcmp(bytes, "../", 3));
    CHECK(md_inode_readlink(s, a, "relative", bytes, sizeof(bytes)) == 10 && !memcmp(bytes, "../b/value", 10));
    CHECK(md_inode_stat(s, a, "absolute/", AT_SYMLINK_NOFOLLOW, &st) == 0 && S_ISDIR(st.st_mode));
    CHECK(md_inode_unlink(s, a, "absolute/", AT_REMOVEDIR) == -ENOTDIR);
    CHECK(md_inode_unlink(s, a, "absolute/", 0) == -ENOTDIR);
    CHECK(md_inode_readlink(s, a, "absolute/", bytes, sizeof(bytes)) == -EINVAL);
    CHECK(md_inode_stat(s, a, "relative/..", 0, &st) == -ENOTDIR);
    CHECK(md_inode_stat(s, a, "absent/..", 0, &st) == -ENOENT);
    CHECK(md_inode_symlink(s, "missing", a, "dangling") == 0);
    CHECK(md_inode_create(s, a, "dangling", 0600) == -EEXIST);
    CHECK(md_inode_stat(s, a, "dangling", 0, &st) == -ENOENT);
    CHECK(md_inode_stat(s, a, "dangling", AT_SYMLINK_NOFOLLOW, &st) == 0);
    CHECK(md_inode_symlink(s, "loop-b", a, "loop-a") == 0);
    CHECK(md_inode_symlink(s, "loop-a", a, "loop-b") == 0);
    CHECK(md_inode_stat(s, a, "loop-a", 0, &st) == -ELOOP);
    for (int i = 40; i >= 0; --i) {
        char name[32], to[32]; snprintf(name, sizeof(name), "l%d", i);
        if (i == 40) strcpy(to, "/b/value"); else snprintf(to, sizeof(to), "l%d", i+1);
        CHECK(md_inode_symlink(s, to, a, name) == 0);
    }
    CHECK(md_inode_stat(s, a, "l1", 0, &st) == 0);
    CHECK(md_inode_stat(s, a, "l0", 0, &st) == -ELOOP);
    CHECK(md_inode_symlink(s, "/../../b/value", a, "rooted") == 0);
    CHECK(md_inode_stat(s, a, "rooted", 0, &st) == 0 && st.st_ino == target.st_ino);
    CHECK(md_inode_rename(s, a, "relative", a, "renamed", 0) == 0);
    CHECK(md_inode_stat(s, a, "renamed", AT_SYMLINK_NOFOLLOW, &st) == 0 && st.st_ino == link.st_ino);
    CHECK(md_inode_rename(s, MD_INODE_ROOT, "a", b, "value", RENAME_EXCHANGE) == 0);
    expected_path(s, a, "/b/value");
    CHECK(md_inode_stat(s, a, "..", 0, &st) == 0);
    struct stat bs; CHECK(md_inode_fstat(s, b, &bs) == 0 && bs.st_ino == st.st_ino);
    CHECK(md_inode_stat(s, MD_INODE_ROOT, "a", 0, &st) == 0 && st.st_ino == target.st_ino);
    struct md_inode_audit audit; CHECK(md_inode_audit(s, &audit) == 0);
    close(file); close(a); close(b); md_inode_store_close(s);
    puts("PASS relative/absolute symlinks, hard-linked symlink inodes, follow flags and 40-link bound");
}
static void permissions(void) {
    struct md_inode_store *s = store("permissions", 1);
    CHECK(md_inode_mkdir(s, MD_INODE_ROOT, "dir", 0755) == 0);
    int dir = directory(s, "dir");
    int file = md_inode_create(s, dir, "value", 0600); CHECK(file >= 0);
    for (unsigned effective = 0; effective < 2; ++effective) {
        struct md_fs_request q = {.operation=MD_FS_ACCESS, .directory={file,-1},
            .flags=effective ? MD_AT_EACCESS : 0, .mode=R_OK|W_OK};
        CHECK(md_inode_metadata(s, &q) == 0);
        q.mode = X_OK;
        CHECK(md_inode_metadata(s, &q) == -EACCES);
        CHECK(fchmod(file, 0700) == 0);
        CHECK(md_inode_metadata(s, &q) == 0);
        CHECK(fchmod(file, 0600) == 0);
    }
    close(file);
    struct stat st;
    CHECK(fchmod(dir, 0600) == 0);
    CHECK(md_inode_stat(s, dir, "value", 0, &st) == -EACCES);
    CHECK(fchmod(dir, 0500) == 0);
    CHECK(md_inode_stat(s, dir, "value", 0, &st) == 0);
    CHECK(md_inode_create(s, dir, "blocked", 0600) == -EACCES);
    CHECK(md_inode_unlink(s, dir, "value", 0) == -EACCES);
    CHECK(fchmod(dir, 0700) == 0);
    CHECK(md_inode_unlink(s, dir, "value", 0) == 0);
    CHECK(md_inode_mkdir(s, MD_INODE_ROOT, "closed", 0000) == 0);
    int closed = md_inode_open(s, MD_INODE_ROOT, "closed", O_PATH | O_DIRECTORY, 0); CHECK(closed >= 0);
    CHECK(md_inode_create(s, closed, "blocked", 0600) == -EACCES);
    CHECK(md_inode_unlink(s, MD_INODE_ROOT, "closed", AT_REMOVEDIR) == 0);
    close(closed); close(dir); md_inode_store_close(s);
    puts("PASS real directory search/write permissions and mode-zero directories");
}
static void equal_result(const char *operation, const char *name, long got, long expected) {
    if (got != expected) fprintf(stderr, "%s %s: model=%ld kernel=%ld\n", operation, name, got, expected);
    CHECK(got == expected);
}
static void kernel_reference(void) {
    char p[PATH_MAX]; path(p, "reference"); CHECK(mkdir(p, 0700) == 0);
    int native = open(p, O_RDONLY | O_DIRECTORY); CHECK(native >= 0);
    struct md_inode_store *s = store("comparison", 1);
    const char *dirs[] = {"d", "d/sub", "other", "empty"};
    for (size_t i = 0; i < sizeof(dirs)/sizeof(*dirs); ++i) {
        CHECK(mkdirat(native, dirs[i], 0755) == 0);
        CHECK(md_inode_mkdir(s, MD_INODE_ROOT, dirs[i], 0755) == 0);
    }
    int a = openat(native, "d/value", O_CREAT | O_EXCL | O_RDWR, 0600);
    int b = md_inode_create(s, MD_INODE_ROOT, "d/value", 0600);
    CHECK(a >= 0 && b >= 0); put(a, "data"); put(b, "data"); close(a); close(b);
    const char *links[][2] = {{"dlink", "d"}, {"flink", "d/value"}, {"broken", "absent"},
        {"cycle", "cycle"}, {"d/relative", "../other"}};
    for (size_t i = 0; i < sizeof(links)/sizeof(*links); ++i) {
        CHECK(symlinkat(links[i][1], native, links[i][0]) == 0);
        CHECK(md_inode_symlink(s, links[i][1], MD_INODE_ROOT, links[i][0]) == 0);
    }
    const char *paths[] = {"", ".", "./d", "d//sub/../value", "d/value/", "d/value/..",
        "dlink/", "dlink/../flink", "flink", "flink/", "broken", "broken/", "absent/..",
        "cycle", "cycle/", "d/relative/", "d/relative/../d/value", "d/value/.", "d/sub/.."};
    const int flags[] = {O_RDONLY, O_RDONLY | O_NOFOLLOW, O_RDONLY | O_DIRECTORY,
        O_PATH, O_PATH | O_NOFOLLOW, O_PATH | O_NOFOLLOW | O_DIRECTORY};
    unsigned comparisons = 0;
    for (size_t i = 0; i < sizeof(paths)/sizeof(*paths); ++i) {
        for (int nofollow = 0; nofollow <= 1; ++nofollow) {
            struct stat actual, reference;
            int f = nofollow ? AT_SYMLINK_NOFOLLOW : 0;
            int r = fstatat(native, paths[i], &reference, f) ? -errno : 0;
            equal_result("stat", paths[i], md_inode_stat(s, MD_INODE_ROOT, paths[i], f, &actual), r);
            if (!r) CHECK((actual.st_mode & S_IFMT) == (reference.st_mode & S_IFMT));
            ++comparisons;
        }
        for (size_t j = 0; j < sizeof(flags)/sizeof(*flags); ++j) {
            int fd = openat(native, paths[i], flags[j]);
            int expected = fd < 0 ? -errno : 0;
            int model = md_inode_open(s, MD_INODE_ROOT, paths[i], flags[j], 0);
            equal_result("open", paths[i], model < 0 ? model : 0, expected);
            if (fd >= 0) close(fd);
            if (model >= 0) close(model);
            ++comparisons;
        }
        char actual[64], reference[64];
        ssize_t n = readlinkat(native, paths[i], reference, sizeof(reference));
        ssize_t expected = n < 0 ? -errno : n;
        equal_result("readlink", paths[i], md_inode_readlink(s, MD_INODE_ROOT, paths[i], actual, sizeof(actual)), expected);
        if (n >= 0) CHECK(!memcmp(actual, reference, (size_t)n));
        ++comparisons;
    }
    enum { REF_MKDIR, REF_CREATE, REF_UNLINK, REF_RMDIR, REF_RENAME, REF_EXCHANGE };
    struct { int op; const char *a, *b; } changes[] = {
        {REF_MKDIR, "d/../new/", NULL}, {REF_MKDIR, "absent/../never", NULL},
        {REF_MKDIR, "d/.", NULL}, {REF_CREATE, "d/value", NULL},
        {REF_CREATE, "new/", NULL}, {REF_CREATE, "absent/", NULL},
        {REF_CREATE, "broken", NULL}, {REF_UNLINK, "dlink/", NULL},
        {REF_RMDIR, "dlink/", NULL}, {REF_RMDIR, "d/sub/.", NULL},
        {REF_RMDIR, "d/sub/..", NULL}, {REF_RENAME, "d/.", "else"},
        {REF_RENAME, "flink/", "else"}, {REF_RENAME, "new/", "renamed/"},
        {REF_RENAME, "d", "renamed"}, {REF_RENAME, "flink", "renamed"},
        {REF_EXCHANGE, "renamed", "empty"}, {REF_RMDIR, "renamed", NULL}
    };
    for (size_t i = 0; i < sizeof(changes)/sizeof(*changes); ++i) {
        const char *src = changes[i].a, *dst = changes[i].b;
        int expected, got;
        switch (changes[i].op) {
        case REF_MKDIR:
            expected = mkdirat(native, src, 0755) ? -errno : 0;
            got = md_inode_mkdir(s, MD_INODE_ROOT, src, 0755); break;
        case REF_CREATE:
            a = openat(native, src, O_CREAT | O_EXCL | O_RDWR, 0600);
            expected = a < 0 ? -errno : 0; if (a >= 0) close(a);
            b = md_inode_create(s, MD_INODE_ROOT, src, 0600);
            got = b < 0 ? b : 0; if (b >= 0) close(b); break;
        case REF_UNLINK: case REF_RMDIR: {
            int f = changes[i].op == REF_RMDIR ? AT_REMOVEDIR : 0;
            expected = unlinkat(native, src, f) ? -errno : 0;
            got = md_inode_unlink(s, MD_INODE_ROOT, src, f); break;
        }
        default: {
            unsigned f = changes[i].op == REF_EXCHANGE ? RENAME_EXCHANGE : 0;
            expected = syscall(SYS_renameat2, native, src, native, dst, f) ? -errno : 0;
            got = md_inode_rename(s, MD_INODE_ROOT, src, MD_INODE_ROOT, dst, f); break;
        }
        }
        equal_result("mutation", src, got, expected); ++comparisons;
    }
    struct md_inode_audit audit; CHECK(md_inode_audit(s, &audit) == 0);
    close(native); md_inode_store_close(s);
    printf("PASS %u path/flag/mutation comparisons against POSIX syscall reference\n", comparisons);
}
static void tree_recovery(enum operation op, enum md_inode_checkpoint point, unsigned serial) {
    char name[64]; snprintf(name, sizeof(name), "tree-crash-%u", serial);
    struct md_inode_store *s = store(name, 1);
    CHECK(md_inode_mkdir(s, MD_INODE_ROOT, "left", 0755) == 0);
    CHECK(md_inode_mkdir(s, MD_INODE_ROOT, "right", 0755) == 0);
    CHECK(md_inode_mkdir(s, MD_INODE_ROOT, "left/sub", 0755) == 0);
    CHECK(md_inode_mkdir(s, MD_INODE_ROOT, "right/dst", 0755) == 0);
    int sub = directory(s, "left/sub"), dst = directory(s, "right/dst");
    int file = md_inode_create(s, sub, "value", 0600); CHECK(file >= 0); put(file, "persist");
    CHECK(fsync(file) == 0); close(file);
    md_inode_store_close(s);
    killed_transaction(name, op, point);
    s = store(name, 0);
    int committed = point == MD_NAMESPACE_COMMITTED;
    struct stat st;
    if (committed && (op == MOVE_DIR || op == SWAP_DIR)) expected_path(s, sub, "/right/dst");
    else expected_path(s, sub, "/left/sub");
    if (committed && op == SWAP_DIR) expected_path(s, dst, "/left/sub");
    else if (committed && (op == MOVE_DIR || op == REMOVE_DIR)) {
        char p[PATH_MAX]; CHECK(md_inode_path(s, dst, p, sizeof(p)) == -ENOENT);
        CHECK(md_inode_fstat(s, dst, &st) == 0 && st.st_nlink == 0);
    } else expected_path(s, dst, "/right/dst");
    if (committed && (op == MKDIR || op == SYMLINK)) {
        CHECK(md_inode_stat(s, MD_INODE_ROOT, "right/new", AT_SYMLINK_NOFOLLOW, &st) == 0);
        CHECK(op == MKDIR ? S_ISDIR(st.st_mode) : S_ISLNK(st.st_mode));
    } else CHECK(md_inode_stat(s, MD_INODE_ROOT, "right/new", 0, &st) == -ENOENT);
    file = md_inode_open(s, sub, "value", O_RDONLY, 0); CHECK(file >= 0); content(file, "persist"); close(file);
    struct md_inode_audit audit; CHECK(md_inode_audit(s, &audit) == 0);
    CHECK(audit.untracked == (unsigned)(!committed && (op == MKDIR || op == SYMLINK)));
    close(sub); close(dst); md_inode_store_close(s);
}
static void scoped_paths(void) {
    struct md_inode_store *s = store("scoped", 1);
    CHECK(md_inode_mkdir(s, MD_INODE_ROOT, "d", 0700) == 0);
    CHECK(md_inode_mkdir(s, MD_INODE_ROOT, "d/sub", 0700) == 0);
    int file = md_inode_create(s, MD_INODE_ROOT, "d/value", 0600);
    CHECK(file >= 0); put(file, "scoped"); close(file);
    CHECK(md_inode_symlink(s, "/value", MD_INODE_ROOT, "d/absolute") == 0);
    CHECK(md_inode_symlink(s, "../value", MD_INODE_ROOT, "d/sub/relative") == 0);
    CHECK(!md_inode_symlink(s,"sub/..",-1,"d/back"));
    CHECK(!md_inode_symlink(s,"back/back",-1,"d/twice"));
    int dir = directory(s, "d");
    struct { const char *path; uint64_t resolve; int error; } cases[] = {
        {"value", RESOLVE_BENEATH, 0}, {"sub/../value", RESOLVE_BENEATH, 0},
        {"../d/value", RESOLVE_BENEATH, -EXDEV}, {"/value", RESOLVE_BENEATH, -EXDEV},
        {"absolute", RESOLVE_BENEATH, -EXDEV}, {"/value", RESOLVE_IN_ROOT, 0},
        {"../value", RESOLVE_IN_ROOT, 0}, {"absolute", RESOLVE_IN_ROOT, 0},
        {"sub/relative", RESOLVE_BENEATH, 0},
        {"twice//back/./sub/../value", RESOLVE_BENEATH, 0},
        {"twice/back/absent", 0, -ENOENT},
        {"twice/back/value/", 0, -ENOTDIR},
        {"sub/relative", RESOLVE_NO_SYMLINKS, -ELOOP},
        {"value", RESOLVE_NO_XDEV | RESOLVE_NO_MAGICLINKS, 0},
        {"value", RESOLVE_CACHED, -EAGAIN},
        {"value", RESOLVE_BENEATH | RESOLVE_IN_ROOT, -EINVAL},
        {"value", 1UL << 63, -EINVAL}
    };
    for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); ++i) {
        int fd = md_inode_open_resolved(s, dir, cases[i].path, O_RDONLY, 0, cases[i].resolve);
        CHECK((fd < 0 ? fd : 0) == cases[i].error);
        if (fd >= 0) { content(fd, "scoped"); close(fd); }
    }
    int fd = md_inode_open_resolved(s, dir, "absolute", O_PATH | O_NOFOLLOW, 0, RESOLVE_NO_SYMLINKS);
    struct stat st;
    CHECK(fd >= 0 && fstat(fd, &st) == 0 && S_ISLNK(st.st_mode)); close(fd);
    CHECK(md_inode_open_resolved(s, dir, "../forbidden", O_CREAT | O_RDWR, 0600, RESOLVE_BENEATH) == -EXDEV);
    CHECK(md_inode_stat(s, MD_INODE_ROOT, "forbidden", 0, &st) == -ENOENT);
    fd = md_inode_open_resolved(s, dir, "/created", O_CREAT | O_RDWR, 0600, RESOLVE_IN_ROOT);
    CHECK(fd >= 0); close(fd);
    CHECK(md_inode_stat(s, MD_INODE_ROOT, "d/created", 0, &st) == 0);
    char resolved[PATH_MAX];
    CHECK(!md_inode_realpath(s, dir, "sub/relative", resolved, sizeof(resolved)));
    CHECK(!strcmp(resolved, "/d/value"));
    CHECK(!md_inode_link(s, dir, "value", dir, "alias", 0));
    CHECK(!md_inode_realpath(s, dir, "alias", resolved, sizeof(resolved)));
    CHECK(!strcmp(resolved, "/d/alias"));
    CHECK(md_inode_realpath(s, dir, "absolute", resolved, sizeof(resolved)) == -ENOENT);
    CHECK(md_inode_realpath(s, dir, "value", resolved, 2) == -ERANGE);
    struct md_inode_audit before, after;
    CHECK(!md_inode_audit(s, &before));
    fd = md_inode_temporary(s); CHECK(fd >= 0);
    CHECK(!fstat(fd, &st) && st.st_nlink == 0);
    put(fd, "snapshot"); content(fd, "snapshot"); close(fd);
    CHECK(!md_inode_audit(s, &after) && !memcmp(&before, &after, sizeof(before)));
    close(dir); md_inode_store_close(s);
    puts("PASS scoped open paths: beneath, in-root, symlinks, cache-only and creation");
}
static int deny_version(void *context,int operation,const char *first,const char *second,
        const char *database,const char *trigger) {
    (void)context; (void)second; (void)database; (void)trigger;
    return operation==SQLITE_PRAGMA && first && !strcmp(first,"data_version") ? SQLITE_DENY : SQLITE_OK;
}
static void read_admission_failure(void) {
    struct md_inode_store *s=store("read-admission",1);
    struct stat st;
    CHECK(!md_inode_stat(s,-1,"/",0,&st));
    CHECK(sqlite3_set_authorizer(s->db,deny_version,NULL)==SQLITE_OK);
    CHECK(md_inode_stat(s,-1,"/",0,&st)<0);
    CHECK(!s->locked && !s->read_depth && !s->write_transaction && sqlite3_get_autocommit(s->db));
    CHECK(md_inode_operation_window_begin(s)<0);
    CHECK(!s->locked && !s->operation_window && !s->read_depth && sqlite3_get_autocommit(s->db));
    CHECK(sqlite3_set_authorizer(s->db,NULL,NULL)==SQLITE_OK);
    CHECK(!md_inode_stat(s,-1,"/",0,&st));
    CHECK(!mdi_begin(s,0) && sqlite3_get_autocommit(s->db));
    CHECK(!mdi_write(s) && !sqlite3_get_autocommit(s->db));
    CHECK(mdi_finish(s,-ECANCELED)==-ECANCELED && sqlite3_get_autocommit(s->db));
    md_inode_store_close(s);
    puts("PASS lazy catalogue admission: failed version read releases the gate; write admission and rollback own the transaction");
}
static void operation_windows(void) {
    struct md_inode_store *s=store("operation-window",1),*peer=store("operation-window",0);
    struct md_identity owner=md_identity_new(0,0),user=md_identity_new(1000,1000);
    s->identity=&owner;
    CHECK(!md_inode_mkdir(s,-1,"work",0700));
    struct md_inode_statistics costs={0}; md_inode_measure(s,&costs);
    CHECK(!md_inode_operation_window_begin(s));
    CHECK(md_inode_operation_window_begin(s)==-EDEADLK);
    CHECK(s->locked && !s->read_depth && sqlite3_get_autocommit(s->db));
    CHECK(flock(peer->objects,LOCK_EX|LOCK_NB)<0 && errno==EWOULDBLOCK);
    struct stat st;
    for (unsigned i=0;i<8;++i) {
        struct md_inode_birth *birth=NULL;
        struct md_open_completion plan;
        int pin=md_inode_plan_open(s,-1,"work/cycle",O_CREAT|O_RDWR,0600,0,&plan,1,&birth);
        CHECK(pin>=0 && birth && md_inode_birth_store(birth)==s);
        CHECK(md_inode_stat(s,-1,"work/cycle",0,&st)==-ENOENT);
        CHECK(!md_inode_birth_commit(&birth) && !birth);
        CHECK(!md_inode_rename(s,-1,"work/cycle",-1,"work/renamed",0));
        CHECK(!md_inode_unlink(s,-1,"work/renamed",0));
        CHECK(!close(pin));
        CHECK(!s->read_depth && !s->write_transaction && sqlite3_get_autocommit(s->db));
    }
    CHECK(!costs.write_admissions && costs.directory_check_misses==1);
    CHECK(!md_inode_mkdir(s,-1,"private",0700));
    CHECK(sqlite3_get_autocommit(s->db));
    /* An independent SQLite reader sees each commit before window release. */
    sqlite3_stmt *q=NULL;
    CHECK(sqlite3_prepare_v2(peer->db,"SELECT count(*) FROM names WHERE name=CAST('private' AS BLOB)",-1,&q,NULL)==SQLITE_OK);
    CHECK(sqlite3_step(q)==SQLITE_ROW && sqlite3_column_int(q,0)==1);
    sqlite3_finalize(q);
    s->identity=&user;
    CHECK(md_inode_create(s,-1,"private/denied",0600)==-EACCES);
    s->identity=&owner;
    CHECK(!mdi_begin(s,0) && !mdi_write(s));
    CHECK(md_inode_operation_window_end(s)==-EBUSY);
    CHECK(!mdi_sql(s,"INSERT INTO properties VALUES('window-rollback',1)"));
    CHECK(mdi_finish(s,-ECANCELED)==-ECANCELED);
    CHECK(sqlite3_get_autocommit(s->db));
    CHECK(sqlite3_prepare_v2(peer->db,"SELECT count(*) FROM properties WHERE key='window-rollback'",-1,&q,NULL)==SQLITE_OK);
    CHECK(sqlite3_step(q)==SQLITE_ROW && !sqlite3_column_int(q,0)); sqlite3_finalize(q);
    int fd=md_inode_create(s,-1,"private/file",0600); CHECK(fd>=0 && !close(fd));
    CHECK(!md_inode_link(s,-1,"private/file",-1,"private/alias",0));
    CHECK(!md_inode_stat(s,-1,"private/alias",0,&st) && st.st_nlink==2);
    CHECK(!md_inode_unlink(s,-1,"private/alias",0));
    CHECK(!md_inode_stat(s,-1,"private/file",0,&st) && st.st_nlink==1);
    CHECK(!md_inode_operation_window_end(s));
    CHECK(!s->locked && !s->operation_window && !s->read_depth && !s->write_transaction);
    CHECK(!flock(peer->objects,LOCK_EX|LOCK_NB) && !flock(peer->objects,LOCK_UN));
    CHECK(costs.lock.calls==1 && costs.version.calls==1);
    md_inode_measure(s,NULL);
    CHECK(!md_inode_rename(peer,-1,"private/file",-1,"private/changed",0));
    CHECK(!md_inode_operation_window_begin(s));
    CHECK(md_inode_stat(s,-1,"private/file",0,&st)==-ENOENT);
    CHECK(!md_inode_stat(s,-1,"private/changed",0,&st));
    CHECK(!md_inode_operation_window_end(s));
    s->identity=NULL; md_identity_release(&owner); md_identity_release(&user);
    md_inode_store_close(peer); md_inode_store_close(s);
    puts("PASS operation windows: one gate/version, independent commits, rollback, live authority, birth, promotion and external invalidation");
}
static void permission_snapshot(void) {
    struct md_inode_store *s=store("permission-snapshot",1);
    struct md_identity owner=md_identity_new(0,0),user=md_identity_new(1000,1000);
    s->identity=&owner;
    CHECK(!md_inode_mkdir(s,-1,"/directory",0755));
    struct mdi_location location;
    CHECK(!mdi_begin(s,0));
    CHECK(!mdi_walk(s,-1,"/directory",MDI_FOLLOW,0,&location));
    CHECK(!mdi_finish(s,0));
    struct md_inode_statistics statistics={0}; md_inode_measure(s,&statistics);
    CHECK(!mdi_begin(s,0));
    CHECK(!mdi_permission_as(s,&location.node,X_OK,&user));
    CHECK(!mdi_permission_as(s,&location.node,X_OK,&user));
    CHECK(statistics.directory_check_misses==1 && statistics.directory_check_hits==1);
    struct mdi_node restricted=location.node; restricted.mode=0700;
    CHECK(mdi_permission_as(s,&restricted,X_OK,&user)==-EACCES);
    CHECK(!mdi_permission_as(s,&restricted,X_OK,&owner));
    CHECK(!mdi_write(s) && !s->directory_check_count);
    CHECK(!mdi_permission_as(s,&location.node,X_OK,&user));
    CHECK(statistics.directory_check_misses==2);
    CHECK(!mdi_finish(s,0));
    /* Replacing the backing between operations must not reuse a successful
     * identity check from the previous operation, even for the same node. */
    CHECK(!renameat(s->objects,location.node.backing,s->objects,"saved-directory"));
    CHECK(!mkdirat(s->objects,location.node.backing,0700));
    CHECK(!mdi_begin(s,0));
    CHECK(mdi_permission_as(s,&location.node,X_OK,&user)<0);
    CHECK(!mdi_finish(s,0));
    CHECK(!unlinkat(s->objects,location.node.backing,AT_REMOVEDIR));
    CHECK(!renameat(s->objects,"saved-directory",s->objects,location.node.backing));
    CHECK(!mdi_begin(s,0));
    CHECK(!mdi_permission_as(s,&location.node,X_OK,&user));
    CHECK(!mdi_finish(s,0));
    md_inode_measure(s,NULL); s->identity=NULL;
    md_identity_release(&owner); md_identity_release(&user); md_inode_store_close(s);
    puts("PASS permission snapshot: same-operation directory validation only; credentials, mode, write admission and backing replacement rechecked");
}
static int prefix_mount(void *context,uint64_t device,uint64_t inode) {
    const struct mdi_node *node=context;
    return node->device==device && node->inode==inode;
}
static void path_prefixes(void) {
    struct md_inode_store *s=store("prefixes",1),*peer=store("prefixes",0);
    struct md_identity owner=md_identity_new(0,0),user=md_identity_new(1000,1000);
    s->identity=&owner;
    int rootfd=md_inode_open(s,-1,"/",O_RDONLY|O_DIRECTORY,0); CHECK(rootfd>=0);
    CHECK(!md_inode_import_metadata(s,rootfd,0,0,0755));
    const char *dirs[]={"/a","/a/b","/a/b/c","/proc","/proc/child"};
    for (unsigned i=0;i<sizeof(dirs)/sizeof(*dirs);++i) CHECK(!md_inode_mkdir(s,-1,dirs[i],0755));
    int fd=md_inode_create(s,-1,"/a/b/c/file",0644); CHECK(fd>=0);
    int b=md_inode_open(s,-1,"/a/b",O_RDONLY|O_DIRECTORY,0); CHECK(b>=0);
    int c=md_inode_open(s,-1,"/a/b/c",O_RDONLY|O_DIRECTORY,0); CHECK(c>=0);
    int proc=md_inode_create(s,-1,"/proc/child/file",0644); CHECK(proc>=0 && !close(proc));
    CHECK(!md_inode_symlink(s,"/a/b/c",-1,"/short"));
    struct md_inode_statistics stats={0}; md_inode_measure(s,&stats);
    struct stat st;
    CHECK(!md_inode_operation_window_begin(s));
    CHECK(!md_inode_stat(s,-1,"/a/b/c/file",0,&st));
    CHECK(!md_inode_stat(s,-1,"/a/b/c/file",0,&st) && stats.path_prefix_hits);
    CHECK(!ftruncate(fd,19));
    CHECK(!md_inode_stat(s,-1,"/a/b/c/file",0,&st) && st.st_size==19);
    CHECK(!md_inode_stat(s,-1,"/a/b/c/../c/file",0,&st));
    CHECK(!md_inode_stat(s,-1,"/short/file",0,&st));
    s->identity=&user;
    CHECK(!md_inode_stat(s,-1,"/a/b/c/file",0,&st));
    s->identity=&owner;
    CHECK(!md_inode_import_metadata(s,b,0,0,0700));
    CHECK(!md_inode_stat(s,-1,"/a/b/c/file",0,&st));
    s->identity=&user;
    CHECK(md_inode_stat(s,-1,"/a/b/c/file",0,&st)==-EACCES);
    s->identity=&owner;
    CHECK(!md_inode_import_metadata(s,b,0,0,0755));
    struct mdi_node node,child;
    CHECK(!mdi_begin(s,0) && !mdi_fd(s,b,&node) && !mdi_fd(s,c,&child) && !mdi_finish(s,0));
    CHECK(!md_inode_stat(s,-1,"/a/b/c/file",0,&st));
    struct mdi_mount_boundary boundary={.filter=md_inode_mount_bit(node.device,node.inode),
        .context=&node,.contains=prefix_mount};
    s->mount_boundary=&boundary;
    CHECK(md_inode_stat(s,-1,"/a/b/c/file",0,&st)==-EXDEV && boundary.crossed);
    s->mount_boundary=NULL;
    s->root_id=child.id;
    CHECK(md_inode_stat(s,-1,"/a/b/c/file",0,&st)==-ENOENT);
    CHECK(!md_inode_stat(s,-1,"/file",0,&st));
    s->root_id=NULL;
    CHECK(!md_inode_stat(s,-1,"/proc/child/file",0,&st));
    char delegated[PATH_MAX];
    struct md_inode_boundary host={.path=delegated,.capacity=sizeof(delegated)};
    s->boundary=&host;
    CHECK(md_inode_stat(s,-1,"/proc/child/file",0,&st)==-EREMOTE);
    CHECK(!strcmp(delegated,"/proc/child/file")); s->boundary=NULL;
    CHECK(!md_inode_operation_window_pause(s));
    CHECK(!s->locked && s->operation_window_paused);
    CHECK(!flock(peer->objects,LOCK_EX|LOCK_NB) && !flock(peer->objects,LOCK_UN));
    CHECK(!md_inode_rename(peer,-1,"/a/b",-1,"/moved",0));
    CHECK(md_inode_stat(s,-1,"/a/b/c/file",0,&st)==-ENOENT && !s->locked);
    CHECK(!md_inode_stat(s,-1,"/moved/c/file",0,&st) && !s->locked);
    CHECK(!md_inode_operation_window_end(s));
    CHECK(!s->operation_window && !s->operation_window_paused);
    CHECK(!md_inode_stat(s,b,"c/file",0,&st));
    CHECK(!close(rootfd) && !close(b) && !close(c) && !close(fd));
    md_inode_measure(s,NULL); s->identity=NULL;
    md_identity_release(&owner); md_identity_release(&user);
    md_inode_store_close(peer); md_inode_store_close(s);
    puts("PASS path prefixes: warm route, live size/credentials, chmod, root, symlinks, mount/provider boundaries and paused-window external rename");
}
static void prefix_mutations(void) {
    struct md_inode_store *s=store("prefix-mutations",1);
    struct md_identity owner=md_identity_new(0,0);
    s->identity=&owner;
    const char *dirs[]={"a","a/b","a/b/c","other"};
    for (unsigned i=0;i<sizeof(dirs)/sizeof(*dirs);++i) CHECK(!md_inode_mkdir(s,-1,dirs[i],0755));
    int fd=md_inode_create(s,-1,"a/b/c/file",0644); CHECK(fd>=0 && !close(fd));
    struct stat st,original;
    struct md_inode_statistics stats={0}; md_inode_measure(s,&stats);
    CHECK(!md_inode_stat(s,-1,"a/b/c/file",0,&original));
    /* Version-bucket collisions may conservatively miss, but unrelated rows
     * must not retire every route. Exercise committed SQL writes, not only
     * native leaves which bypass the catalogue entirely. */
    for (unsigned i=0;i<64;++i) {
        char path[64]; snprintf(path,sizeof(path),"other/link-%u",i);
        CHECK(!md_inode_symlink(s,"target",-1,path));
        CHECK(!md_inode_stat(s,-1,"a/b/c/file",0,&st));
    }
    CHECK(stats.path_prefix_hits>=32);
    CHECK(!md_inode_rename(s,-1,"a/b",-1,"other/moved",0));
    CHECK(md_inode_stat(s,-1,"a/b/c/file",0,&st)==-ENOENT);
    CHECK(!md_inode_stat(s,-1,"other/moved/c/file",0,&st) && st.st_ino==original.st_ino);
    CHECK(!md_inode_mkdir(s,-1,"a/b",0755));
    CHECK(!md_inode_mkdir(s,-1,"a/b/c",0755));
    fd=md_inode_create(s,-1,"a/b/c/file",0644); CHECK(fd>=0 && !close(fd));
    CHECK(!md_inode_stat(s,-1,"a/b/c/file",0,&st) && st.st_ino!=original.st_ino);
    CHECK(!md_inode_rename(s,-1,"a/b",-1,"other/moved",RENAME_EXCHANGE));
    CHECK(!md_inode_stat(s,-1,"a/b/c/file",0,&st) && st.st_ino==original.st_ino);
    /* A route observed inside a rolled-back transaction cannot survive it. */
    CHECK(!mdi_begin(s,1));
    struct mdi_location loc;
    CHECK(!mdi_walk(s,-1,"a/b",MDI_ENTRY,0,&loc));
    CHECK(!mdi_delete_name(s,loc.parent.id,loc.name));
    CHECK(!mdi_add_name(s,loc.parent.id,"hidden",loc.node.id));
    CHECK(!mdi_walk(s,-1,"a/hidden/c/file",MDI_FOLLOW,0,&loc));
    CHECK(mdi_finish(s,-ECANCELED)==-ECANCELED);
    CHECK(md_inode_stat(s,-1,"a/hidden/c/file",0,&st)==-ENOENT);
    CHECK(!md_inode_stat(s,-1,"a/b/c/file",0,&st) && st.st_ino==original.st_ino);
    md_inode_measure(s,NULL); s->identity=NULL;
    md_identity_release(&owner); md_inode_store_close(s);
    puts("PASS path prefix dependencies: unrelated SQL writes, parent move, name reuse, exchange and rollback");
}
static void metadata_working_set(void) {
    struct md_inode_store *s=store("metadata-working-set",1);
    enum { COUNT=512 };
    char names[COUNT][32]; struct stat st;
    for (unsigned i=0;i<COUNT;++i) {
        snprintf(names[i],sizeof(names[i]),"/directory-%u",i);
        CHECK(!md_inode_mkdir(s,-1,names[i],0755));
    }
    CHECK(!md_inode_operation_window_begin(s));
    for (unsigned round=0;round<2;++round) for (unsigned i=0;i<COUNT;++i)
        CHECK(!md_inode_stat(s,-1,names[i],0,&st));
    struct md_inode_statistics stats={0}; md_inode_measure(s,&stats);
    for (unsigned i=0;i<COUNT;++i) CHECK(!md_inode_stat(s,-1,names[i],0,&st));
    CHECK(stats.metadata_cache_hits>COUNT && stats.metadata_cache_misses<COUNT/8);
    CHECK(!md_inode_rename(s,-1,names[0],-1,"/renamed",0));
    stats=(struct md_inode_statistics){0};
    for (unsigned i=1;i<COUNT;++i) CHECK(!md_inode_stat(s,-1,names[i],0,&st));
    CHECK(stats.metadata_cache_misses<COUNT/8);
    CHECK(md_inode_stat(s,-1,names[0],0,&st)==-ENOENT);
    CHECK(!md_inode_stat(s,-1,"/renamed",0,&st));
    CHECK(!md_inode_operation_window_end(s));
    md_inode_measure(s,NULL); md_inode_store_close(s);
    puts("PASS metadata working set: 512 directories stay hot; rename invalidates dependencies without flushing siblings");
}
static void reused_queries(void) {
    struct md_inode_store *s = store("queries", 1), *writer = store("queries", 0);
    int fd = md_inode_create(writer, MD_INODE_ROOT, "value", 0600); CHECK(fd >= 0);
    struct stat st;
    CHECK(!md_inode_fstat(s, fd, &st));
    struct stat original = state(s, "value", 1);
    /* Reads retain store admission and external-version checks, not a SQL transaction. */
    CHECK(md_inode_stat(s, MD_INODE_ROOT, "absent", 0, &st) == -ENOENT);
    struct md_inode_statistics statistics = {0};
    md_inode_measure(s, &statistics);
    for (unsigned i = 0; i < 16; i++) {
        CHECK(md_inode_stat(s, MD_INODE_ROOT, "absent", 0, &st) == -ENOENT);
        CHECK(!md_inode_link(writer, MD_INODE_ROOT, "value", MD_INODE_ROOT, "alias", 0));
        state(s, "value", 2); state(s, "alias", 2);
        CHECK(!md_inode_fstat(s, fd, &st) && st.st_nlink == 2);
        CHECK(!md_inode_unlink(writer, MD_INODE_ROOT, "alias", 0));
        state(s, "value", 1);
        CHECK(!ftruncate(fd, i) && !md_inode_fstat(s, fd, &st) && st.st_size == i);
    }
    CHECK(!md_inode_unlink(writer, MD_INODE_ROOT, "value", 0));
    int next = md_inode_create(writer, MD_INODE_ROOT, "value", 0600); CHECK(next >= 0);
    CHECK(state(s, "value", 1).st_ino != original.st_ino);
    CHECK(!md_inode_fstat(s, fd, &st) && st.st_nlink == 0 && st.st_ino == original.st_ino);
    CHECK(statistics.query_reuses > 100 && statistics.prepare.calls == 0);
    CHECK(statistics.step.calls && !statistics.transaction.calls && statistics.lock.calls);
    CHECK(statistics.metadata_cache_hits && statistics.metadata_cache_misses);
    /* Local mutations and native content changes invalidate different things:
     * namespace attributes must refresh; file sizes always come from the FD. */
    CHECK(!md_inode_import_metadata(s,next,123,456,0640));
    CHECK(!md_inode_fstat(s,next,&st) && st.st_uid==123 && st.st_gid==456
        && (st.st_mode&0777)==0640);
    sqlite3_int64 changes=sqlite3_total_changes64(s->db);
    uint64_t prepares=statistics.prepare.calls;
    /* Idempotent namespace values need no database rewrite. Native chmod still
     * executes, preserving ctime and repairing the backing's required access. */
    CHECK(!fchmod(next,0400));
    CHECK(!md_inode_import_metadata(s,next,123,456,0640));
    CHECK(sqlite3_total_changes64(s->db)==changes && statistics.prepare.calls==prepares);
    CHECK(!fstat(next,&st) && (st.st_mode&0777)==0600);
    CHECK(!md_inode_import_metadata(writer,next,321,654,0750));
    CHECK(!md_inode_import_metadata(s,next,123,456,0640));
    CHECK(!md_inode_fstat(writer,next,&st) && st.st_uid==123 && st.st_gid==456
        && (st.st_mode&0777)==0640);
    CHECK(!ftruncate(next,313) && !md_inode_fstat(s,next,&st) && st.st_size==313);
    CHECK(!md_inode_import_metadata(s,next,getuid(),getgid(),0600));
    md_inode_measure(s, NULL);
    struct md_inode_statistics snapshot = statistics;
    state(s, "value", 1);
    CHECK(!memcmp(&snapshot, &statistics, sizeof(snapshot)));
    close(next); close(fd); md_inode_store_close(writer); md_inode_store_close(s);
    puts("PASS query reuse: fresh snapshots across writers, missing names, path reuse and unlinked FDs");
}
struct directory_delivery { int fd, error, calls; off_t offset; };
static int deliver_entries(void *data, const void *entries, size_t size) {
    struct directory_delivery *delivery = data;
    CHECK(lseek(delivery->fd, 0, SEEK_CUR) == delivery->offset);
    CHECK(size == 24 && !strcmp(((const struct md_directory_entry *)entries)->name, "."));
    delivery->calls++;
    return delivery->error;
}
static void directory_publication(void) {
    struct md_inode_store *s = store("delivery", 1);
    int dir = md_inode_open(s, MD_INODE_ROOT, "/", O_RDONLY | O_DIRECTORY, 0); CHECK(dir >= 0);
    struct directory_delivery delivery = {.fd = dir, .error = -EACCES};
    char entries[24];
    CHECK(md_inode_getdents_deliver(s, dir, entries, sizeof(entries), deliver_entries, &delivery) == -EACCES);
    CHECK(lseek(dir, 0, SEEK_CUR) == 0 && delivery.calls == 1);
    delivery.error = -ECANCELED;
    CHECK(md_inode_getdents_deliver(s, dir, entries, sizeof(entries), deliver_entries, &delivery) == -ECANCELED);
    CHECK(lseek(dir, 0, SEEK_CUR) == 0 && delivery.calls == 2);
    delivery.error = 0;
    CHECK(md_inode_getdents_deliver(s, dir, entries, sizeof(entries), deliver_entries, &delivery) == 24);
    CHECK(lseek(dir, 0, SEEK_CUR) == 1 && delivery.calls == 3);
    CHECK(md_inode_getdents(s, dir, entries, sizeof(entries)) == 24);
    CHECK(!strcmp(((struct md_directory_entry *)entries)->name, ".."));
    close(dir); md_inode_store_close(s);
    puts("PASS directory publication: rejected/cancelled output preserves the shared cursor");
}
static void metadata_queries(void) {
    struct md_inode_store *s = store("metadata", 1), *writer = store("metadata", 0);
    int fd = md_inode_create(writer, MD_INODE_ROOT, "value", 0600); CHECK(fd >= 0);
    struct stat st;
    CHECK(!md_inode_fstat(s, fd, &st));
    int dir = md_inode_open(s, MD_INODE_ROOT, "/", O_RDONLY | O_DIRECTORY, 0); CHECK(dir >= 0);
    int opened = md_inode_open(s, dir, "value", O_RDONLY, 0); CHECK(opened >= 0); close(opened);
    CHECK(!md_inode_fstat(s, fd, &st));
    char entries[1024];
    CHECK(md_inode_getdents(s, dir, entries, sizeof(entries)) > 0);
    CHECK(md_inode_seekdir(s, dir, 0, SEEK_SET) == 0);
    struct md_inode_statistics statistics = {0};
    md_inode_measure(s, &statistics);
    opened = md_inode_open(s, dir, "value", O_RDONLY, 0); CHECK(opened >= 0); close(opened);
    /* Only version admission; warm rows need neither SQL lookup nor BEGIN/COMMIT. */
    CHECK(statistics.step.calls == 1 && !statistics.transaction.calls);
    uint64_t before = statistics.step.calls;
    CHECK(!md_inode_fstat(s, fd, &st) && st.st_nlink == 1);
    CHECK(statistics.step.calls - before == 1);
    before = statistics.step.calls;
    CHECK(!md_inode_stat(s, dir, "value", 0, &st) && st.st_nlink == 1);
    CHECK(statistics.step.calls - before == 1);
    uint64_t links = statistics.link_count_queries;
    CHECK(md_inode_getdents(s, dir, entries, sizeof(entries)) > 0);
    CHECK(md_inode_getdents(s, dir, entries, sizeof(entries)) == 0);
    int next = md_inode_create(writer, MD_INODE_ROOT, "later", 0600); CHECK(next >= 0);
    CHECK(md_inode_getdents(s, dir, entries, sizeof(entries)) > 0);
    CHECK(!strcmp(((struct md_directory_entry *)entries)->name, "later"));
    CHECK(statistics.link_count_queries == links && statistics.membership_queries == 3);
    CHECK(!md_inode_mkdir(writer, MD_INODE_ROOT, "child", 0700));
    CHECK(!md_inode_fstat(s, dir, &st) && st.st_nlink == 3);
    CHECK(!fchmod(fd, 0640) && !ftruncate(fd, 71));
    CHECK(!md_inode_fstat(s, fd, &st) && st.st_size == 71 && (st.st_mode & 0777) == 0640);
    CHECK(statistics.prepare.calls == 0);
    md_inode_measure(s, NULL);
    close(next); close(dir); close(fd); md_inode_store_close(writer); md_inode_store_close(s);
    puts("PASS metadata programs: minimal open/fstat queries, live FD attributes and fresh directory batches");
}
static void backing_cache(void) {
    struct md_inode_store *s=store("backing-cache",1), *writer=store("backing-cache",0);
    int fd=md_inode_create(writer,-1,"value",0600); CHECK(fd>=0);
    struct md_inode_statistics stats={0}; md_inode_measure(s,&stats);
    struct stat named, opened;
    CHECK(!md_inode_stat(s,-1,"value",0,&named));
    uint64_t steps=stats.step.calls;
    CHECK(!md_inode_fstat(s,fd,&opened) && named.st_ino==opened.st_ino);
    CHECK(stats.step.calls==steps+1 && !stats.descriptor.calls && !stats.transaction.calls);
    CHECK(!ftruncate(fd,123) && !md_inode_fstat(s,fd,&opened) && opened.st_size==123);
    CHECK(!md_inode_link(writer,-1,"value",-1,"alias",0));
    CHECK(!md_inode_fstat(s,fd,&opened) && opened.st_nlink==2);
    CHECK(!md_inode_unlink(writer,-1,"value",0));
    CHECK(!md_inode_unlink(writer,-1,"alias",0));
    int replacement=md_inode_create(writer,-1,"value",0600); CHECK(replacement>=0);
    CHECK(!md_inode_stat(s,-1,"value",0,&named));
    CHECK(!md_inode_fstat(s,fd,&opened) && opened.st_nlink==0 && opened.st_ino!=named.st_ino);
    CHECK(!md_inode_fstat(s,replacement,&opened) && opened.st_nlink==1 && opened.st_ino==named.st_ino);
    md_inode_measure(s,NULL); close(replacement); close(fd);
    md_inode_store_close(writer); md_inode_store_close(s);
    puts("PASS name-to-backing cache: no duplicate FD lookup, native attributes, foreign links and open-unlinked identity");
}
static void recent_cache_keys(void) {
    struct md_inode_store *s=store("recent-keys",1), *peer=store("recent-keys",0);
    struct stat st;
    for (unsigned i=0;i<160;++i) {
        char name[32]; snprintf(name,sizeof(name),"v%u",i);
        int fd=md_inode_create(s,-1,name,0600); CHECK(fd>=0);
        CHECK(!ftruncate(fd,(off_t)i)); close(fd);
    }
    for (unsigned pass=0;pass<3;++pass) {
        CHECK(!md_inode_operation_window_begin(s));
        for (unsigned i=0;i<160;++i) {
            unsigned n=pass==1 ? 159-i : i;
            char name[32]; snprintf(name,sizeof(name),"v%u",n);
            CHECK(!md_inode_stat(s,-1,name,0,&st) && st.st_size==(off_t)n);
            CHECK(!md_inode_stat(s,-1,name,0,&st) && st.st_size==(off_t)n);
        }
        CHECK(!md_inode_operation_window_end(s));
    }
    CHECK(!md_inode_operation_window_begin(s));
    CHECK(!md_inode_stat(s,-1,"v159",0,&st));
    CHECK(!md_inode_rename(s,-1,"v0",-1,"v159",0));
    CHECK(!md_inode_stat(s,-1,"v159",0,&st) && !st.st_size);
    CHECK(md_inode_stat(s,-1,"v0",0,&st)==-ENOENT);
    CHECK(!md_inode_operation_window_end(s));
    CHECK(!md_inode_rename(peer,-1,"v1",-1,"v159",0));
    CHECK(!md_inode_stat(s,-1,"v159",0,&st) && st.st_size==1);
    md_inode_store_close(peer); md_inode_store_close(s);
    puts("PASS hot catalogue keys: collisions, slot replacement, local mutation and foreign generation");
}
static void mutation_cache(void) {
    struct md_inode_store *s=store("mutation-cache",1);
    CHECK(!md_inode_mkdir(s,-1,"/stable",0700));
    CHECK(!md_inode_mkdir(s,-1,"/busy",0700));
    int fd=md_inode_create(s,-1,"/stable/value",0600); CHECK(fd>=0); close(fd);
    struct stat st;
    CHECK(!md_inode_stat(s,-1,"/stable/value",0,&st));
    struct md_inode_statistics stats={0}; md_inode_measure(s,&stats);
    for (unsigned i=0;i<16;++i) {
        fd=md_inode_open(s,-1,"/stable/value",O_RDWR|O_CREAT,0600); CHECK(fd>=0); close(fd);
    }
    CHECK(!stats.write_admissions && stats.step.calls==16 && !stats.transaction.calls);
    for (unsigned i=0;i<16;++i) {
        CHECK(!mdi_begin(s,0));
        struct mdi_location cached;
        CHECK(!mdi_walk(s,-1,"/stable/value",MDI_FOLLOW,0,&cached));
        const char *root_id=mdi_root(s);
        struct mdi_dependency deps[]={
            mdi_cache_dependency(s,MDI_DEP_OBJECT,root_id,32,NULL,0),
            mdi_cache_dependency(s,MDI_DEP_OBJECT,cached.parent.id,32,NULL,0),
            mdi_cache_dependency(s,MDI_DEP_OBJECT,cached.node.id,32,NULL,0),
            mdi_cache_dependency(s,MDI_DEP_ENTRY,root_id,32,"stable",6),
            mdi_cache_dependency(s,MDI_DEP_ENTRY,cached.parent.id,32,"value",5)};
        CHECK(!mdi_finish(s,0));
        fd=md_inode_create(s,-1,"/busy/changed",0600); CHECK(fd>=0); close(fd);
        CHECK(!mdi_begin(s,0));
        /* The bounded dependency table intentionally retires hash collisions.
         * Require zero repeated queries whenever the actual proof survives. */
        int intact=mdi_cache_dependencies_current(s,deps,sizeof(deps)/sizeof(*deps));
        uint64_t before=stats.step.calls;
        CHECK(!md_inode_stat(s,-1,"/stable/value",0,&st));
        if (stats.step.calls!=before)
            fprintf(stderr,"mutation cache: iteration=%u queries=%llu dependencies=%s\n",
                i,(unsigned long long)(stats.step.calls-before),intact ? "current" : "invalidated");
        CHECK(!intact || stats.step.calls==before);
        CHECK(st.st_ino==cached.node.logical_inode && st.st_nlink==1);
        before=stats.step.calls;
        CHECK(!md_inode_stat(s,-1,"/stable/value",0,&st) && stats.step.calls==before);
        CHECK(!mdi_finish(s,0));
        CHECK(!md_inode_unlink(s,-1,"/busy/changed",0));
    }
    CHECK(stats.write_admissions==32);
    CHECK(!mdi_begin(s,0));
    struct mdi_location stable, busy;
    CHECK(!mdi_walk(s,-1,"/stable/value",MDI_FOLLOW,0,&stable));
    CHECK(!mdi_walk(s,-1,"/busy",MDI_FOLLOW,0,&busy));
    struct mdi_dependency collision=mdi_cache_dependency(s,MDI_DEP_OBJECT,stable.parent.id,32,NULL,0);
    char collided[64]; unsigned candidate;
    for (candidate=0;candidate<65536;++candidate) {
        snprintf(collided,sizeof(collided),"/busy/collision-%u",candidate);
        struct mdi_dependency changed=mdi_cache_dependency(s,MDI_DEP_ENTRY,busy.node.id,32,
            collided+6,strlen(collided+6));
        if (changed.slot==collision.slot) break;
    }
    CHECK(candidate<65536 && !mdi_finish(s,0));
    fd=md_inode_create(s,-1,collided,0600); CHECK(fd>=0); close(fd);
    CHECK(!mdi_begin(s,0) && !mdi_cache_dependencies_current(s,&collision,1));
    uint64_t queries=stats.step.calls;
    CHECK(!md_inode_stat(s,-1,"/stable/value",0,&st) && st.st_ino==stable.node.logical_inode);
    CHECK(stats.step.calls>queries);
    queries=stats.step.calls;
    CHECK(!md_inode_stat(s,-1,"/stable/value",0,&st) && stats.step.calls==queries);
    CHECK(!mdi_finish(s,0) && !md_inode_unlink(s,-1,collided,0));
    /* Exact negative entries, trigger-maintained counts, and rollback are
     * checked inside the same write snapshot, not only after a new admission. */
    CHECK(!mdi_begin(s,1));
    struct mdi_node root, value, absent;
    CHECK(!mdi_node(s,mdi_root(s),&root));
    CHECK(mdi_lookup(s,&root,"alias",&absent,NULL,NULL)==-ENOENT);
    struct mdi_location loc;
    CHECK(!mdi_walk(s,-1,"/stable/value",MDI_FOLLOW,0,&loc));
    CHECK(!mdi_add_name(s,root.id,"alias",loc.node.id));
    CHECK(!mdi_lookup(s,&root,"alias",&value,NULL,NULL) && value.links==2);
    CHECK(!mdi_node(s,loc.node.id,&value) && value.links==2);
    CHECK(mdi_finish(s,-ECANCELED)==-ECANCELED);
    CHECK(md_inode_stat(s,-1,"/alias",0,&st)==-ENOENT);
    CHECK(!md_inode_stat(s,-1,"/stable/value",0,&st) && st.st_nlink==1);
    CHECK(!mdi_begin(s,0));
    CHECK(!mdi_begin(s,0));
    CHECK(mdi_write(s)==-EDEADLK);
    CHECK(!mdi_finish(s,0) && !mdi_finish(s,0));
    md_inode_measure(s,NULL); md_inode_store_close(s);
    puts("PASS mutation admission: existing writable opens retain read snapshots; unrelated writes retain cache rows");
    puts("PASS row invalidation: negative names, trigger counts, rollback and rejected nested promotion");
}
static void cached_path_names(void) {
    struct md_inode_store *s=store("path-cache",1), *writer=store("path-cache",0);
    CHECK(!md_inode_mkdir(writer,-1,"/parent",0700));
    CHECK(!md_inode_mkdir(writer,-1,"/parent/child",0700));
    int fd=md_inode_create(writer,-1,"/parent/child/value",0600); CHECK(fd>=0);
    int dir=md_inode_open(s,-1,"/parent/child",O_PATH|O_DIRECTORY,0); CHECK(dir>=0);
    char path[PATH_MAX];
    for (unsigned i=0;i<8;++i) {
        CHECK(!md_inode_path(s,fd,path,sizeof(path)) && !strcmp(path,"/parent/child/value"));
        CHECK(!md_inode_path(s,dir,path,sizeof(path)) && !strcmp(path,"/parent/child"));
    }
    CHECK(!md_inode_rename(writer,-1,"/parent",-1,"/moved",0));
    CHECK(!md_inode_path(s,fd,path,sizeof(path)) && !strcmp(path,"/moved/child/value"));
    CHECK(!md_inode_path(s,dir,path,sizeof(path)) && !strcmp(path,"/moved/child"));
    CHECK(!md_inode_rename(s,-1,"/moved/child/value",-1,"/moved/child/new",0));
    CHECK(!md_inode_path(s,fd,path,sizeof(path)) && !strcmp(path,"/moved/child/new"));
    CHECK(!md_inode_unlink(writer,-1,"/moved/child/new",0));
    CHECK(!md_inode_path(s,fd,path,sizeof(path)) && !strcmp(path,"/moved/child/new (deleted)"));
    int next=md_inode_create(writer,-1,"/moved/child/new",0600); CHECK(next>=0);
    CHECK(!md_inode_path(s,next,path,sizeof(path)) && !strcmp(path,"/moved/child/new"));
    CHECK(!md_inode_link(writer,-1,"/moved/child/new",-1,"/alias",0));
    CHECK(md_inode_path(s,next,path,sizeof(path))==-ENOTSUP);
    CHECK(!md_inode_unlink(writer,-1,"/alias",0));
    CHECK(md_inode_path(s,next,path,sizeof(path))==-ENOTSUP);
    close(next); close(dir); close(fd); md_inode_store_close(writer); md_inode_store_close(s);
    puts("PASS cached path names: external parent rename, local rename, open-unlinked, reuse and hardlink ambiguity");
}
static void membership_queries(void) {
    struct md_inode_store *s = store("membership", 1), *writer = store("membership", 0);
    CHECK(!md_inode_mkdir(s, MD_INODE_ROOT, "directory", 0700));
    int dir = md_inode_open(s, MD_INODE_ROOT, "directory", O_RDONLY | O_DIRECTORY, 0); CHECK(dir >= 0);
    struct md_inode_statistics stats = {0};
    md_inode_measure(s, &stats);
    for (unsigned i = 0; i < 32; i++) {
        char name[32]; snprintf(name, sizeof(name), "child%u", i);
        CHECK(!md_inode_mkdir(s, dir, name, 0700));
    }
    char buffer[32];
    unsigned batches = 0;
    while (md_inode_getdents(s, dir, buffer, sizeof(buffer)) > 0) batches++;
    CHECK(batches == 34 && stats.link_count_queries == 0 && stats.membership_queries == 35);
    struct stat st;
    CHECK(!md_inode_fstat(s, dir, &st) && st.st_nlink == 34 && stats.link_count_queries == 1);
    for (unsigned i = 0; i < 32; i++) {
        char name[32]; snprintf(name, sizeof(name), "child%u", i);
        CHECK(!md_inode_unlink(writer, dir, name, AT_REMOVEDIR));
    }
    CHECK(!md_inode_unlink(writer, MD_INODE_ROOT, "directory", AT_REMOVEDIR));
    CHECK(md_inode_getdents(s, dir, buffer, sizeof(buffer)) == -ENOENT);
    CHECK(md_inode_create(s, dir, "forbidden", 0600) == -ENOENT);
    CHECK(stats.link_count_queries == 1);
    CHECK(!md_inode_fstat(s, dir, &st) && st.st_nlink == 0);
    close(dir); md_inode_store_close(writer); md_inode_store_close(s);
    puts("PASS directory membership: transactional counters, live unlink and unchanged full stat");
}
static void foreign_key_indexes(sqlite3 *db) {
    /* Each deleted parent probes every referencing column. An unindexed
     * child turns bulk view collection into a quadratic full-store scan. */
    sqlite3_stmt *keys=NULL;
    CHECK(sqlite3_prepare_v2(db,"SELECT s.name,f.\"from\",f.seq FROM sqlite_schema s, "
        "pragma_foreign_key_list(s.name) f WHERE s.type='table'",-1,&keys,NULL)==SQLITE_OK);
    unsigned count=0;
    int next;
    while ((next=sqlite3_step(keys))==SQLITE_ROW) {
        CHECK(sqlite3_column_int(keys,2)==0);
        char *sql=sqlite3_mprintf("EXPLAIN QUERY PLAN SELECT 1 FROM \"%w\" WHERE \"%w\"=?1",
            sqlite3_column_text(keys,0),sqlite3_column_text(keys,1));
        CHECK(sql);
        sqlite3_stmt *plan=NULL;
        CHECK(sqlite3_prepare_v2(db,sql,-1,&plan,NULL)==SQLITE_OK);
        sqlite3_free(sql);
        CHECK(sqlite3_step(plan)==SQLITE_ROW);
        const char *detail=(const char *)sqlite3_column_text(plan,3);
        if (!detail || strncmp(detail,"SEARCH ",7))
            fprintf(stderr,"Unindexed foreign key %s.%s: %s\n",sqlite3_column_text(keys,0),
                sqlite3_column_text(keys,1),detail ? detail : "missing plan");
        CHECK(detail && !strncmp(detail,"SEARCH ",7));
        CHECK(sqlite3_step(plan)==SQLITE_DONE);
        sqlite3_finalize(plan); ++count;
    }
    CHECK(next==SQLITE_DONE && count>=18);
    sqlite3_finalize(keys);
    puts("PASS every foreign-key child lookup uses an index, including view collection");
}
static void lookup_indexes(struct md_inode_store *s) {
    for (unsigned i=0;i<MDI_QUERY_COUNT;++i) {
        sqlite3_stmt *q=NULL;
        CHECK(!mdi_query_acquire(s,i,&q));
        const char *sql=sqlite3_sql(q);
        /* The reserve takes the first row of its primary-key index once;
         * other retained lookups must seek rather than scan or sort. */
        if (i!=MDI_RESERVE_FIRST && !strncmp(sql,"SELECT ",7)) test_sql_indexed_lookup(s->db,sql);
        CHECK(!mdi_query_release(q,0));
    }
    test_sql_indexed_lookup(s->db,"SELECT s.address FROM sockets s JOIN inode_channels c ON c.object=s.object "
        "WHERE c.channel=?1 LIMIT 1");
    puts("PASS retained filesystem and reverse socket lookups use indexed seeks without sorting");
}
static void directory_name_cost(void) {
    struct md_inode_store *s=store("directory-name-cost",1);
    CHECK(!md_inode_mkdir(s,-1,"parent",0700));
    int parent=md_inode_open(s,-1,"parent",O_PATH|O_DIRECTORY,0); CHECK(parent>=0);
    struct mdi_node child;
    for (unsigned i=0;i<256;++i) {
        char name[32]; snprintf(name,sizeof(name),"child%u",i);
        CHECK(!md_inode_mkdir(s,parent,name,0700));
    }
    int fd=md_inode_open(s,parent,"child255",O_PATH|O_DIRECTORY,0); CHECK(fd>=0);
    CHECK(!mdi_begin(s,0) && !mdi_fd(s,fd,&child));
    for (unsigned probe=0;probe<3;++probe) {
        sqlite3_stmt *q=NULL;
        CHECK(!mdi_query_acquire(s,MDI_DIRECTORY_NAME,&q));
        CHECK(!mdi_bind_id(q,1,probe==1 ? mdi_root(s) : child.parent));
        CHECK(!mdi_bind_id(q,2,probe==2 ? "ffffffffffffffffffffffffffffffff" : child.id));
        sqlite3_stmt_status(q,SQLITE_STMTSTATUS_VM_STEP,1);
        CHECK(mdi_step(s,q)==(probe ? SQLITE_DONE : SQLITE_ROW));
        if (!probe) {
            CHECK(sqlite3_column_bytes(q,0)==8 && !memcmp(sqlite3_column_blob(q,0),"child255",8));
            CHECK(mdi_step(s,q)==SQLITE_DONE);
        }
        /* An index range over every sibling is still reported as SEARCH, not
         * FULLSCAN. Bound actual work, including misses and wrong parents. */
        int steps=sqlite3_stmt_status(q,SQLITE_STMTSTATUS_VM_STEP,0);
        if (steps>=64) fprintf(stderr,"directory name probe %u: %d VM steps\n",probe,steps);
        CHECK(steps<64);
        CHECK(!mdi_query_release(q,0));
    }
    CHECK(!mdi_finish(s,0));
    char name[PATH_MAX];
    CHECK(!md_inode_path(s,fd,name,sizeof(name)) && !strcmp(name,"/parent/child255"));
    close(fd); close(parent); md_inode_store_close(s);
    puts("PASS reverse directory lookup work is independent of sibling count, including misses");
}
static void counter_audit(void) {
    struct md_inode_store *s = store("counter-audit", 1);
    int fd = md_inode_create(s, MD_INODE_ROOT, "file", 0600); CHECK(fd >= 0); close(fd);
    CHECK(!md_inode_mkdir(s, MD_INODE_ROOT, "dir", 0700));
    struct md_inode_audit audit;
    CHECK(!md_inode_audit(s, &audit));
    char database[PATH_MAX]; path(database, "counter-audit/namespace.db");
    sqlite3 *db;
    CHECK(sqlite3_open(database, &db) == SQLITE_OK);
    foreign_key_indexes(db);
    lookup_indexes(s);
    CHECK(sqlite3_exec(db, "UPDATE objects SET name_count=0 WHERE kind=32768", NULL, NULL, NULL) == SQLITE_OK);
    CHECK(md_inode_audit(s, &audit) == -EIO);
    CHECK(sqlite3_exec(db, "UPDATE objects SET name_count=1 WHERE kind=32768;"
        "UPDATE objects SET directory_count=0 WHERE directory_count=1", NULL, NULL, NULL) == SQLITE_OK);
    CHECK(md_inode_audit(s, &audit) == -EIO);
    CHECK(sqlite3_exec(db, "UPDATE objects SET directory_count=1 WHERE object='00000000000000000000000000000000'",
        NULL, NULL, NULL) == SQLITE_OK);
    CHECK(!md_inode_audit(s, &audit));
    md_inode_store_close(s);
    CHECK(sqlite3_exec(db,"DROP INDEX objects_parent; DROP INDEX backings_object;"
        "DROP INDEX inode_views_upper; DROP INDEX inode_views_work; DROP INDEX overlay_origins_object;"
        "DROP INDEX overlay_links_upper; DROP INDEX file_paths_parent; DROP INDEX inode_channels_channel",NULL,NULL,NULL)==SQLITE_OK);
    s=store("counter-audit",0);
    foreign_key_indexes(db);
    lookup_indexes(s);
    CHECK(!md_inode_audit(s,&audit));
    md_inode_store_close(s);
    CHECK(sqlite3_exec(db, "PRAGMA user_version=4", NULL, NULL, NULL) == SQLITE_OK);
    char directory[PATH_MAX]; path(directory, "counter-audit");
    CHECK(md_inode_store_open(directory, 0, &s) == -EPROTONOSUPPORT && !s);
    sqlite3_stmt *q;
    CHECK(sqlite3_prepare_v2(db, "PRAGMA user_version", -1, &q, NULL) == SQLITE_OK);
    CHECK(sqlite3_step(q) == SQLITE_ROW && sqlite3_column_int(q, 0) == 4);
    sqlite3_finalize(q); CHECK(sqlite3_close(db) == SQLITE_OK);
    puts("PASS independent counter audit and non-mutating unsupported-format rejection");
}
static void retained_journal(void) {
    struct md_inode_store *a=store("journal",1);
    int fd=md_inode_create(a,MD_INODE_ROOT,"file",0600);
    CHECK(fd>=0); close(fd);
    struct md_inode_store *b=store("journal",0);
    char journal[PATH_MAX]; path(journal,"journal/namespace.db-wal");
    struct stat first, next;
    CHECK(!stat(journal,&first) && first.st_size>0);
    sqlite3_stmt *settings=NULL;
    CHECK(sqlite3_prepare_v2(a->db,"PRAGMA page_size",-1,&settings,NULL)==SQLITE_OK);
    CHECK(sqlite3_step(settings)==SQLITE_ROW);
    int page_size=sqlite3_column_int(settings,0);
    sqlite3_finalize(settings);
    CHECK(sqlite3_prepare_v2(a->db,"PRAGMA wal_autocheckpoint",-1,&settings,NULL)==SQLITE_OK);
    CHECK(sqlite3_step(settings)==SQLITE_ROW && sqlite3_column_int(settings,0)==1024);
    sqlite3_finalize(settings);
    unsigned restarts=0; unsigned char salt[8]={0};
    for (unsigned i=0;i<256;i++) {
        struct md_inode_store *s=i&1 ? a : b;
        CHECK(!md_inode_link(s,MD_INODE_ROOT,"file",MD_INODE_ROOT,"link",0));
        CHECK(!md_inode_unlink(s,MD_INODE_ROOT,"link",0));
        CHECK(!stat(journal,&next) && first.st_ino==next.st_ino && first.st_dev==next.st_dev);
        /* These small commits cross repeated automatic checkpoints. Large
         * transactions may exceed the threshold, but ordinary commits must
         * reuse the WAL rather than growing it indefinitely. */
        CHECK(next.st_size>32 && next.st_size<32+(off_t)(1024+32)*(page_size+24));
        fd=open(journal,O_RDONLY|O_CLOEXEC); CHECK(fd>=0);
        unsigned char header[32];
        CHECK(read(fd,header,sizeof(header))==sizeof(header)); close(fd);
        CHECK(header[0]==0x37 && header[1]==0x7f && header[2]==0x06);
        CHECK(header[3]==0x82 || header[3]==0x83);
        if (i && memcmp(salt,header+16,sizeof(salt))) ++restarts;
        memcpy(salt,header+16,sizeof(salt));
        CHECK(state(s,"file",1).st_size==0);
    }
    CHECK(restarts>=2);
    char before[PATH_MAX], moved[PATH_MAX];
    path(before,"journal"); path(moved,"journal-moved");
    CHECK(!rename(before,moved));
    CHECK(!md_inode_link(a,MD_INODE_ROOT,"file",MD_INODE_ROOT,"after-move",0));
    CHECK(state(b,"after-move",2).st_ino==state(a,"file",2).st_ino);
    CHECK(!md_inode_unlink(b,MD_INODE_ROOT,"after-move",0));
    state(a,"file",1);
    CHECK(!rename(moved,before));
    md_inode_store_close(a); md_inode_store_close(b);
    a=store("journal",0);
    struct md_inode_audit audit;
    CHECK(!md_inode_audit(a,&audit)); md_inode_store_close(a);
    char database[PATH_MAX], directory[PATH_MAX];
    path(database,"journal/namespace.db"); path(directory,"journal");
    sqlite3 *db; CHECK(sqlite3_open(database,&db)==SQLITE_OK);
    CHECK(sqlite3_exec(db,"PRAGMA journal_mode=DELETE",NULL,NULL,NULL)==SQLITE_OK);
    CHECK(sqlite3_close(db)==SQLITE_OK);
    CHECK(md_inode_store_open_inspect(directory,&a)==-EPROTONOSUPPORT && !a);
    CHECK(sqlite3_open(database,&db)==SQLITE_OK);
    sqlite3_stmt *q;
    CHECK(sqlite3_prepare_v2(db,"PRAGMA journal_mode",-1,&q,NULL)==SQLITE_OK);
    CHECK(sqlite3_step(q)==SQLITE_ROW && !strcmp((const char *)sqlite3_column_text(q,0),"delete"));
    sqlite3_finalize(q); CHECK(sqlite3_close(db)==SQLITE_OK);
    CHECK(!md_inode_store_open(directory,0,&a));
    CHECK(state(a,"file",1).st_size==0);
    CHECK(sqlite3_prepare_v2(a->db,"PRAGMA journal_mode",-1,&q,NULL)==SQLITE_OK);
    CHECK(sqlite3_step(q)==SQLITE_ROW && !strcmp((const char *)sqlite3_column_text(q,0),"wal"));
    sqlite3_finalize(q); md_inode_store_close(a);
    puts("PASS WAL journal: shared connections, bounded checkpoint reuse, stable inode, non-mutating inspection and explicit journal preparation");
}
static void native_births(void) {
    struct md_inode_store *s=store("native-births",1);
    struct md_identity root_identity=md_identity_new(0,0);
    s->identity=&root_identity;
    CHECK(s->native_files);
    CHECK(!md_inode_mkdir(s,-1,"work",0777));
    struct md_inode_birth *birth=NULL;
    struct stat st;
    s->creation_mask=0027;
    for (unsigned exclusive=0;exclusive<2;++exclusive) {
        struct md_open_completion plan;
        int flags=O_CREAT|O_RDWR|(exclusive ? O_EXCL : 0);
        struct md_inode_statistics costs={0}; md_inode_measure(s,&costs);
        int planned=md_inode_plan_open(s,-1,"work/planned",flags,0666,0,&plan,1,&birth);
        md_inode_measure(s,NULL);
        CHECK(costs.lock.calls==1 && costs.version.calls==1 && !costs.write_admissions);
        CHECK(planned>=0 && birth && plan.kind==MD_OPEN_PLAN && plan.native_leaf);
        CHECK(!s->read_depth && !s->write_transaction);
        CHECK(md_inode_stat(s,-1,"work/planned",0,&st)==-ENOENT);
        CHECK(!md_inode_birth_commit(&birth) && !birth);
        CHECK(!md_inode_stat(s,-1,"work/planned",0,&st) && (st.st_mode&0777)==0640);
        close(planned);
        planned=md_inode_plan_open(s,-1,"work/planned",flags,0666,0,&plan,1,&birth);
        CHECK(!birth);
        if (exclusive) CHECK(planned==-EEXIST);
        else { CHECK(planned>=0 && plan.kind==MD_OPEN_PLAN); close(planned); }
        CHECK(!md_inode_unlink(s,-1,"work/planned",0));
    }
    int pin=md_inode_plan_open(s,-1,"work/new",O_CREAT|O_EXCL|O_RDWR,0666,0,&(struct md_open_completion){0},1,&birth);
    CHECK(pin>=0 && birth && !s->read_depth && !s->write_transaction);
    CHECK(md_inode_stat(s,-1,"work/new",0,&st)==-ENOENT);
    char proc[64]; snprintf(proc,sizeof(proc),"/proc/self/fd/%d",pin);
    int fd=open(proc,O_RDWR|O_CLOEXEC); CHECK(fd>=0); put(fd,"born");
    CHECK(!md_inode_birth_commit(&birth) && !birth);
    CHECK(!md_inode_stat(s,-1,"work/new",0,&st) && st.st_size==4 && (st.st_mode&0777)==0640);
    close(fd); close(pin);
    CHECK(md_inode_plan_open(s,-1,"work/new",O_CREAT|O_EXCL|O_RDWR,0600,0,&(struct md_open_completion){0},1,&birth)==-EEXIST && !birth);
    pin=md_inode_plan_open(s,-1,"work/new",O_CREAT|O_RDWR,0600,0,&(struct md_open_completion){0},1,&birth);
    CHECK(pin>=0 && !birth); close(pin);
    pin=md_inode_plan_open(s,-1,"work/cancel",O_CREAT|O_RDWR,0000,0,&(struct md_open_completion){0},1,&birth);
    CHECK(pin>=0 && birth);
    CHECK(!md_inode_birth_discard(&birth) && !birth);
    CHECK(!fstat(pin,&st) && !st.st_nlink);
    close(pin);
    CHECK(md_inode_stat(s,-1,"work/cancel",0,&st)==-ENOENT);
    pin=md_inode_plan_open(s,-1,"work/race",O_CREAT|O_EXCL|O_RDWR,0600,0,&(struct md_open_completion){0},1,&birth);
    CHECK(pin>=0 && birth);
    CHECK(!md_inode_symlink(s,"new",-1,"work/race"));
    CHECK(md_inode_birth_commit(&birth)==-EEXIST && birth);
    CHECK(!md_inode_birth_discard(&birth)); close(pin);
    CHECK(!md_inode_stat(s,-1,"work/race",AT_SYMLINK_NOFOLLOW,&st) && S_ISLNK(st.st_mode));
    pin=md_inode_plan_open(s,-1,"work/native-race",O_CREAT|O_EXCL|O_RDWR,0600,0,&(struct md_open_completion){0},1,&birth);
    CHECK(pin>=0 && birth);
    fd=md_inode_create(s,-1,"work/native-race",0600); CHECK(fd>=0); put(fd,"winner");
    CHECK(md_inode_birth_commit(&birth)==-EEXIST && birth);
    CHECK(!md_inode_birth_discard(&birth)); close(pin);
    content(fd,"winner"); close(fd);
    pin=md_inode_plan_open(s,-1,"work/moved",O_CREAT|O_RDWR,0600,0,&(struct md_open_completion){0},1,&birth);
    CHECK(pin>=0 && birth);
    CHECK(!md_inode_rename(s,-1,"work",-1,"renamed",0));
    CHECK(!md_inode_birth_commit(&birth)); close(pin);
    CHECK(!md_inode_stat(s,-1,"renamed/moved",0,&st));
    CHECK(!md_inode_mkdir(s,-1,"gone",0700));
    pin=md_inode_plan_open(s,-1,"gone/file",O_CREAT|O_RDWR,0600,0,&(struct md_open_completion){0},1,&birth);
    CHECK(pin>=0 && birth);
    CHECK(!md_inode_unlink(s,-1,"gone",AT_REMOVEDIR));
    CHECK(md_inode_birth_commit(&birth)==-ENOENT && birth);
    CHECK(!md_inode_birth_discard(&birth)); close(pin);
    s->creation_mask=0;
    struct md_identity user=md_identity_new(1234,2345);
    CHECK(!md_inode_metadata(s,&(struct md_fs_request){.operation=MD_FS_CHMOD,
        .directory={-1,-1},.path={"/"},.mode=0755}));
    CHECK(!md_inode_metadata(s,&(struct md_fs_request){.operation=MD_FS_CHMOD,
        .directory={-1,-1},.path={"renamed"},.mode=0777})); s->identity=&user;
    pin=md_inode_plan_open(s,-1,"renamed/owned",O_CREAT|O_RDWR,0000,0,&(struct md_open_completion){0},1,&birth);
    CHECK(pin>=0 && birth);
    CHECK(!md_inode_birth_commit(&birth)); close(pin);
    CHECK(!md_inode_stat(s,-1,"renamed/owned",0,&st) && st.st_uid==1234 && st.st_gid==2345 && !(st.st_mode&07777));
    CHECK(md_inode_plan_open(s,-1,"renamed/invalid",O_CREAT|O_TRUNC,0600,0,&(struct md_open_completion){0},1,&birth)==-EINVAL && !birth);
    s->identity=&root_identity;
    pin=md_inode_plan_open(s,-1,"renamed/promoted",O_CREAT|O_EXCL|O_RDWR,0600,0,&(struct md_open_completion){0},1,&birth);
    CHECK(pin>=0 && birth);
    snprintf(proc,sizeof(proc),"/proc/self/fd/%d",pin);
    fd=open(proc,O_RDWR|O_CLOEXEC); CHECK(fd>=0); put(fd,"before");
    CHECK(!md_inode_birth_commit(&birth));
    CHECK(!md_inode_link(s,-1,"renamed/promoted",-1,"renamed/alias",0));
    CHECK(!md_inode_fstat(s,fd,&st) && st.st_nlink==2);
    struct stat alias;
    CHECK(!md_inode_stat(s,-1,"renamed/alias",0,&alias) && alias.st_ino==st.st_ino);
    put(fd,"shared");
    int other=md_inode_open(s,-1,"renamed/alias",O_RDONLY,0); CHECK(other>=0);
    content(other,"shared"); close(other); close(fd); close(pin);
    pin=md_inode_plan_open(s,-1,"renamed/sealed",O_CREAT|O_RDWR,0600,0,&(struct md_open_completion){0},1,&birth);
    CHECK(pin>=0 && birth);
    CHECK(!md_inode_store_seal(s));
    CHECK(md_inode_birth_commit(&birth)==-EROFS && birth);
    CHECK(!md_inode_birth_discard(&birth)); close(pin);
    CHECK(md_inode_stat(s,-1,"renamed/sealed",0,&st)==-ENOENT);
    struct md_inode_store *copy=store("birth-snapshot",1);
    copy->identity=&root_identity;
    CHECK(!md_inode_snapshot(s,copy));
    fd=md_inode_open(copy,-1,"renamed/alias",O_RDWR|O_TRUNC,0); CHECK(fd>=0);
    put(fd,"copy"); close(fd);
    fd=md_inode_open(copy,-1,"renamed/promoted",O_RDONLY,0); CHECK(fd>=0);
    content(fd,"copy"); close(fd);
    fd=md_inode_open(s,-1,"renamed/promoted",O_RDONLY,0); CHECK(fd>=0);
    content(fd,"shared"); close(fd);
    md_inode_store_close(copy);
    s->identity=NULL; md_identity_release(&user); md_identity_release(&root_identity);
    md_inode_store_close(s);
    puts("PASS native birth preparation: unpublished pin, explicit commit/discard, live mask/identity, catalogue conflict and parent rename/removal");
}
static void native_birth_custody(void) {
    struct md_identity ids=md_identity_new(0,0);
    for (unsigned operation=0;operation<3;++operation) for (unsigned window=0;window<2;++window) {
        char name[64]; snprintf(name,sizeof(name),"birth-custody-%u-%u",operation,window);
        struct md_inode_store *s=store(name,1);
        s->identity=&ids;
        int objects=s->objects;
        struct md_inode_birth *birth=NULL;
        int pin=md_inode_plan_open(s,-1,"/born",O_CREAT|O_RDWR,0600,0,
            &(struct md_open_completion){0},1,&birth);
        CHECK(pin>=0 && birth);
        if (window) CHECK(!md_inode_operation_window_begin(s));
        md_inode_store_close(s);
        CHECK(fcntl(objects,F_GETFD)>=0);
        if (!operation) CHECK(!md_inode_birth_commit(&birth));
        else if (operation==1) CHECK(!md_inode_birth_discard(&birth));
        else md_inode_birth_forget(&birth);
        CHECK(!birth);
        if (window) {
            CHECK(fcntl(objects,F_GETFD)>=0);
            CHECK(!md_inode_operation_window_end(s));
        }
        CHECK(fcntl(objects,F_GETFD)==-1 && errno==EBADF);
        close(pin);
        s=store(name,0);
        struct stat st;
        CHECK(md_inode_stat(s,-1,"/born",0,&st)==(operation ? -ENOENT : 0));
        md_inode_store_close(s);
    }
    md_identity_release(&ids);
    puts("PASS birth custody: commit/discard/forget outlive connection owner, bounded window retains final connection and releases FD");
}
static void native_birth_recovery(void) {
    for (unsigned phase=0;phase<3;++phase) {
        char name[64], directory[PATH_MAX];
        snprintf(name,sizeof(name),"birth-recovery-%u",phase); path(directory,name);
        struct md_inode_store *s=store(name,1);
        struct md_identity identity=md_identity_new(0,0);
        s->identity=&identity;
        CHECK(!md_inode_mkdir(s,-1,"work",0700));
        md_inode_store_close(s);
        pid_t child=fork(); CHECK(child>=0);
        if (!child) {
            s=store(name,0); s->identity=&identity;
            struct md_inode_birth *birth=NULL;
            int pin=md_inode_plan_open(s,-1,"work/value",O_CREAT|O_EXCL|O_RDWR,0600,0,&(struct md_open_completion){0},1,&birth);
            CHECK(pin>=0 && birth);
            if (phase) {
                char proc[64]; snprintf(proc,sizeof(proc),"/proc/self/fd/%d",pin);
                int fd=open(proc,O_RDWR|O_CLOEXEC); CHECK(fd>=0); put(fd,"retained");
            }
            if (phase==2) CHECK(!md_inode_birth_commit(&birth) && !birth);
            CHECK(!kill(getpid(),SIGKILL)); _exit(1);
        }
        joined(child,SIGKILL);
        s=store(name,0);
        struct stat st; struct md_inode_audit audit;
        CHECK(md_inode_stat(s,-1,"work/value",0,&st)==(phase==2 ? 0 : -ENOENT));
        if (phase==2) {
            int fd=md_inode_open(s,-1,"work/value",O_RDONLY,0); CHECK(fd>=0);
            content(fd,"retained"); CHECK(!close(fd));
        }
        CHECK(!md_inode_audit(s,&audit) && audit.untracked==(phase<2));
        md_inode_store_close(s);
        CHECK(!md_inode_store_open_exclusive(directory,&s));
        CHECK(!md_inode_audit(s,&audit) && !audit.untracked);
        CHECK(md_inode_stat(s,-1,"work/value",0,&st)==(phase==2 ? 0 : -ENOENT));
        md_inode_store_close(s); md_identity_release(&identity);
    }
    puts("PASS native birth SIGKILL: preparation/effect/publication, retained contents and exclusive orphan collection");
}
static void native_birth_acl(void) {
    struct md_inode_store *s=store("native-birth-acl",1);
    struct md_identity ids=md_identity_new(0,0); s->identity=&ids;
    CHECK(!md_inode_mkdir(s,-1,"work",0755));
    int directory=md_inode_open(s,-1,"work",O_RDONLY|O_DIRECTORY,0); CHECK(directory>=0);
    struct { uint32_t version; struct md_acl_entry entries[5]; } acl={.version=2,.entries={
        {MD_ACL_USER_OBJ,7,UINT32_MAX},{MD_ACL_USER,4,1000},{MD_ACL_GROUP_OBJ,0,UINT32_MAX},
        {MD_ACL_MASK,4,UINT32_MAX},{MD_ACL_OTHER,0,UINT32_MAX}}};
    struct md_fs_request q={.operation=MD_FS_SETACL,.directory={directory,-1},.mode=MD_ACL_DEFAULT,
        .value=&acl,.value_size=sizeof(acl),.capacity=sizeof(acl)};
    struct md_fs_result result;
    md_inode_acl(s,&q,&result,NULL); CHECK(!result.error);
    s->creation_mask=0077;
    struct md_inode_birth *birth=NULL;
    int pin=md_inode_plan_open(s,directory,"prepared",O_CREAT|O_RDWR,0666,0,&(struct md_open_completion){0},1,&birth);
    CHECK(pin>=0 && birth);
    struct stat st;
    CHECK(md_inode_stat(s,directory,"prepared",0,&st)==-ENOENT);
    CHECK(!md_inode_birth_commit(&birth) && !birth);
    close(pin);
    for (unsigned staged=0;staged<2;++staged) {
        int fd=md_inode_open(s,directory,staged ? "prepared" : "ordinary",O_CREAT|O_RDWR,0666);
        CHECK(fd>=0 && !md_inode_fstat(s,fd,&st) && (st.st_mode&0777)==0640);
        CHECK(!mdi_begin(s,0));
        struct mdi_node node;
        CHECK(!mdi_fd(s,fd,&node) && node.native && (node.acl_mask&MD_ACL_ACCESS));
        CHECK(!mdi_finish(s,0));
        unsigned char bytes[sizeof(acl)];
        struct md_fs_output output={.data=bytes,.capacity=sizeof(bytes)};
        q=(struct md_fs_request){.operation=MD_FS_GETACL,.directory={fd,-1},
            .mode=MD_ACL_ACCESS,.capacity=sizeof(bytes)};
        md_inode_acl(s,&q,&result,&output);
        CHECK(!result.error && result.size==sizeof(acl));
        struct md_acl *actual=(void *)bytes;
        CHECK(md_acl_mode(actual,sizeof(bytes),0)==0640 && actual->entries[1].id==1000
            && actual->entries[1].permissions==4);
        struct md_identity reader=md_identity_new(1000,1000);
        const char *name=staged ? "prepared" : "ordinary";
        s->identity=&reader;
        struct md_open_completion plan;
        pin=md_inode_plan_open(s,directory,name,O_RDONLY,0,0,&plan,1,NULL);
        CHECK(pin>=0 && plan.kind==MD_OPEN_PLAN && plan.native_leaf && !close(pin));
        CHECK(md_inode_plan_open(s,directory,name,O_WRONLY,0,0,&plan,1,NULL)==-EACCES);
        CHECK(md_inode_plan_open(s,directory,name,O_RDONLY|O_NOATIME,0,0,&plan,1,NULL)==-EPERM);
        s->identity=&ids; md_identity_release(&reader);
        pin=md_inode_plan_open(s,directory,name,O_RDONLY|O_NOATIME,0,0,&plan,1,NULL);
        CHECK(pin>=0 && !close(pin));
        if (staged) {
            CHECK(!md_inode_link(s,directory,"prepared",directory,"alias",0));
            md_inode_acl(s,&q,&result,&output);
            CHECK(!result.error && result.size==sizeof(acl) && actual->entries[1].permissions==4);
        }
        close(fd);
    }
    pin=md_inode_plan_open(s,directory,"cancelled",O_CREAT|O_RDWR,0666,0,&(struct md_open_completion){0},1,&birth);
    CHECK(pin>=0 && birth && !md_inode_birth_discard(&birth)); close(pin);
    CHECK(md_inode_stat(s,directory,"cancelled",0,&st)==-ENOENT);
    close(directory); md_inode_store_close(s); md_identity_release(&ids);
    puts("PASS native birth ACL: default inheritance ignores umask, staged/ordinary creation, cancellation and hardlink promotion");
}
static void native_plans(void) {
    struct md_inode_store *s=store("native-plans",1);
    struct md_open_completion completion;
    int fd=md_inode_create(s,-1,"value",0600); CHECK(fd>=0); put(fd,"old"); close(fd);
    CHECK(!md_inode_mkdir(s,-1,"directory",0700));
    CHECK(!md_inode_mknod(s,-1,"fifo",S_IFIFO|0600,0));
    CHECK(!md_inode_symlink(s,"value",-1,"link"));
    const unsigned rejected[]={O_CREAT|O_EXCL,O_ASYNC};
    for (unsigned i=0;i<sizeof(rejected)/sizeof(*rejected);++i)
        CHECK(md_inode_plan_open(s,-1,"missing",rejected[i],0600,0,&completion,1,NULL)==-EXDEV);
    struct stat st; CHECK(md_inode_stat(s,-1,"missing",0,&st)==-ENOENT);
    CHECK(md_inode_plan_open(s,-1,"missing",O_CREAT|O_RDWR,0600,0,&completion,1,NULL)==-EXDEV);
    CHECK(md_inode_stat(s,-1,"missing",0,&st)==-ENOENT);
    CHECK(md_inode_plan_open(s,-1,"value",O_TRUNC,0,0,&completion,1,NULL)==-EINVAL);
    const unsigned directories[]={O_RDONLY,O_DIRECTORY,O_RDONLY|O_NOFOLLOW,O_DIRECTORY|O_NOFOLLOW,O_PATH|O_DIRECTORY,
        O_PATH|O_NOFOLLOW,O_PATH|O_DIRECTORY|O_NOFOLLOW};
    for (unsigned i=0;i<sizeof(directories)/sizeof(*directories);++i) {
        int directory=md_inode_plan_open(s,-1,"directory",directories[i],0,0,&completion,1,NULL);
        CHECK(directory>=0 && completion.kind==MD_OPEN_PLAN);
        CHECK(!fstat(directory,&st) && S_ISDIR(st.st_mode));
        char path[64]; unsigned flags=md_open_plan_path(path,0,(unsigned)directory,directories[i],st.st_mode);
        int opened=open(path,flags); CHECK(opened>=0);
        CHECK((fcntl(opened,F_GETFL)&~O_LARGEFILE)==(int)directories[i]);
        close(opened);
        CHECK(!close(directory));
        CHECK(md_inode_plan_open(s,-1,"missing",directories[i],0,0,&completion,1,NULL)==-ENOENT);
    }
    CHECK(md_inode_plan_open(s,-1,"value",O_DIRECTORY,0,0,&completion,1,NULL)==-ENOTDIR);
    CHECK(md_inode_plan_open(s,-1,"value",O_PATH|O_NOFOLLOW,0,0,&completion,1,NULL)==-EXDEV);
    CHECK(md_inode_plan_open(s,-1,"link",O_PATH|O_NOFOLLOW,0,0,&completion,1,NULL)==-EXDEV);
    CHECK(md_inode_plan_open(s,-1,"fifo",O_RDONLY,0,0,&completion,1,NULL)==-EXDEV);
    CHECK(!md_inode_read_scope_begin(s));
    CHECK(md_inode_plan_open(s,-1,"fifo",O_RDONLY,0,0,&completion,0,NULL)==-EAGAIN);
    CHECK(completion.kind==MD_OPEN_NEEDS_WRITE && s->read_depth==1 && !s->write_transaction && !s->fifos);
    CHECK(!md_inode_read_scope_end(s));
    int fifo=md_inode_plan_open(s,-1,"fifo",O_RDONLY,0,0,&completion,0,NULL);
    CHECK(fifo>=0 && completion.kind==MD_OPEN_PIPE);
    CHECK(!close(completion.control) && !close(fifo));
    CHECK(md_inode_plan_open(s,-1,"link",O_RDONLY|O_NOFOLLOW,0,0,&completion,1,NULL)==-ELOOP);
    CHECK(md_inode_plan_open(s,-1,"missing",O_RDONLY,0,0,&completion,1,NULL)==-ENOENT);
    int pin=md_inode_plan_open(s,-1,"link",O_RDONLY|O_CLOEXEC,0,0,&completion,1,NULL);
    CHECK(pin>=0 && completion.kind==MD_OPEN_PLAN && (fcntl(pin,F_GETFL)&O_PATH));
    CHECK(!md_inode_rename(s,-1,"value",-1,"renamed",0));
    CHECK(!md_inode_unlink(s,-1,"renamed",0));
    fd=md_inode_create(s,-1,"value",0600); CHECK(fd>=0); put(fd,"replacement"); close(fd);
    char proc[64]; snprintf(proc,sizeof(proc),"/proc/self/fd/%d",pin);
    fd=open(proc,O_RDONLY|O_CLOEXEC); CHECK(fd>=0); content(fd,"old"); close(fd); close(pin);
    const unsigned writable[]={O_WRONLY,O_RDWR|O_APPEND|O_CREAT,O_WRONLY|O_TRUNC};
    for (unsigned i=0;i<sizeof(writable)/sizeof(*writable);++i) {
        pin=md_inode_plan_open(s,-1,"value",writable[i],0,0,&completion,1,NULL);
        CHECK(pin>=0 && completion.kind==MD_OPEN_PLAN && (fcntl(pin,F_GETFL)&O_PATH));
        CHECK(!md_inode_stat(s,-1,"value",0,&st) && st.st_size==11+(i==2));
        snprintf(proc,sizeof(proc),"/proc/self/fd/%d",pin);
        fd=open(proc,writable[i]|O_CLOEXEC); CHECK(fd>=0);
        if (i==2) CHECK(!fstat(fd,&st) && st.st_size==0);
        CHECK(write(fd,"x",1)==1); close(fd); close(pin);
    }
    s->readonly=1;
    CHECK(md_inode_plan_open(s,-1,"value",O_WRONLY|O_TRUNC,0,0,&completion,1,NULL)==-EROFS);
    s->readonly=0;
    struct md_identity user=md_identity_new(1000,1000); s->identity=&user;
    CHECK(md_inode_plan_open(s,-1,"value",O_RDONLY,0,0,&completion,1,NULL)==-EACCES);
    s->identity=NULL; md_identity_release(&user); md_inode_store_close(s);
    struct md_inode_store *source=store("native-plan-source",1),*copy=store("native-plan-copy",1);
    fd=md_inode_create(source,-1,"shared",0600); CHECK(fd>=0); put(fd,"source"); close(fd);
    CHECK(!md_inode_link(source,-1,"shared",-1,"alias",0));
    CHECK(!md_inode_store_seal(source));
    CHECK(!md_inode_snapshot(source,copy));
    int selected=md_inode_open(copy,-1,"alias",O_PATH,0); CHECK(selected>=0);
    CHECK(!md_inode_read_scope_begin(copy));
    int spare=fcntl(copy->objects,F_DUPFD_CLOEXEC,0); CHECK(spare>=0 && !close(spare));
    for (unsigned i=0;i<64;++i) {
        CHECK(md_inode_plan_open(copy,-1,"alias",O_CREAT|O_WRONLY|O_TRUNC,0600,0,&completion,1,NULL)==-EAGAIN);
        CHECK(completion.kind==MD_OPEN_NEEDS_WRITE && copy->read_depth==1 && !copy->write_transaction);
        CHECK(md_inode_plan_reopen(copy,selected,O_WRONLY|O_TRUNC,0,&completion,1)==-EAGAIN);
        CHECK(completion.kind==MD_OPEN_NEEDS_WRITE && copy->read_depth==1 && !copy->write_transaction);
    }
    CHECK(fcntl(copy->objects,F_DUPFD_CLOEXEC,0)==spare && !close(spare));
    CHECK(!md_inode_read_scope_end(copy));
    CHECK(!md_inode_operation_window_begin(copy));
    pin=md_inode_plan_reopen(copy,selected,O_WRONLY|O_TRUNC,0,&completion,1);
    CHECK(pin>=0 && completion.kind==MD_OPEN_PLAN);
    struct stat physical; CHECK(!fstat(pin,&physical));
    CHECK(completion.backing_device==(uint64_t)physical.st_dev && completion.backing_inode==(uint64_t)physical.st_ino);
    close(selected);
    CHECK(!md_inode_stat(copy,-1,"shared",0,&st) && st.st_size==6 && st.st_nlink==2);
    snprintf(proc,sizeof(proc),"/proc/self/fd/%d",pin);
    fd=open(proc,O_WRONLY|O_TRUNC|O_CLOEXEC); CHECK(fd>=0); put(fd,"new"); close(fd); close(pin);
    fd=md_inode_open(copy,-1,"shared",O_RDONLY,0); CHECK(fd>=0); content(fd,"new"); close(fd);
    CHECK(!md_inode_operation_window_end(copy));
    fd=md_inode_open(source,-1,"shared",O_RDONLY,0); CHECK(fd>=0); content(fd,"source"); close(fd);
    CHECK(!md_inode_read_scope_begin(copy));
    pin=md_inode_plan_open(copy,-1,"shared",O_WRONLY|O_APPEND,0,0,&completion,1,NULL);
    CHECK(pin>=0 && completion.kind==MD_OPEN_PLAN && copy->read_depth==1 && !copy->write_transaction);
    selected=md_inode_plan_reopen(copy,pin,O_WRONLY|O_APPEND,0,&completion,1);
    CHECK(selected>=0 && completion.kind==MD_OPEN_PLAN && copy->read_depth==1 && !copy->write_transaction);
    close(selected);
    close(pin); CHECK(!md_inode_read_scope_end(copy));
    md_inode_store_close(copy); md_inode_store_close(source);
    puts("PASS native open plans: pin identity, deferred write/append/truncate, readonly policy, permissions and pre-effect decline");
}
static void reset_directory_time(struct md_inode_store *s, const char *name) {
    CHECK(!md_inode_metadata(s,&(struct md_fs_request){.operation=MD_FS_UTIMENS,
        .directory={MD_INODE_ROOT},.path={name},
        .attributes={.seconds={1000000000,1000000001},.nanos={123,456}}}));
}
static void directory_time(struct md_inode_store *s, const char *name, int changed) {
    struct stat st;
    CHECK(!md_inode_stat(s,MD_INODE_ROOT,name,0,&st));
    CHECK(S_ISDIR(st.st_mode));
    CHECK(st.st_atim.tv_sec==1000000000 && st.st_atim.tv_nsec==123);
    if (!(changed ? st.st_mtim.tv_sec>1000000001 :
            st.st_mtim.tv_sec==1000000001 && st.st_mtim.tv_nsec==456))
        fprintf(stderr,"directory=%s native=%d expected-changed=%d mtime=%lld.%09ld\n",
            name,s->native_files,changed,(long long)st.st_mtim.tv_sec,st.st_mtim.tv_nsec);
    CHECK(changed ? st.st_mtim.tv_sec>1000000001 :
        st.st_mtim.tv_sec==1000000001 && st.st_mtim.tv_nsec==456);
}
static void directory_times(void) {
    struct md_identity identity=md_identity_new(0,0);
    for (int native=0;native<2;++native) {
        const char *name=native ? "directory-times-native" : "directory-times-catalogue";
        struct md_inode_store *s=store(name,1),*peer=store(name,0);
        s->identity=&identity; s->native_files=native;
        CHECK(!md_inode_mkdir(s,-1,"a",0700) && !md_inode_mkdir(s,-1,"b",0700));
        reset_directory_time(s,"a"); reset_directory_time(s,"b");
        CHECK(!md_inode_symlink(s,"target",-1,"a/link"));
        directory_time(peer,"a",1); directory_time(peer,"b",0);
        reset_directory_time(s,"a");
        CHECK(md_inode_symlink(s,"target",-1,"a/link")==-EEXIST);
        CHECK(md_inode_unlink(s,-1,"a/missing",0)==-ENOENT);
        directory_time(peer,"a",0);
        CHECK(!md_inode_mkdir(s,-1,"a/child",0700)); directory_time(peer,"a",1);
        reset_directory_time(s,"a");
        int fd=md_inode_create(s,-1,"a/file",0600); CHECK(fd>=0); close(fd);
        directory_time(peer,"a",1);
        reset_directory_time(s,"a");
        CHECK(!md_inode_link(s,-1,"a/file",-1,"b/alias",0));
        directory_time(peer,"a",0); directory_time(peer,"b",1);
        reset_directory_time(s,"b");
        CHECK(!md_inode_rename(s,-1,"a/link",-1,"b/link",0));
        directory_time(peer,"a",1); directory_time(peer,"b",1);
        reset_directory_time(s,"a"); reset_directory_time(s,"b");
        CHECK(!md_inode_rename(s,-1,"a/file",-1,"b/alias",0));
        directory_time(peer,"a",0); directory_time(peer,"b",0);
        CHECK(!md_inode_rename(s,-1,"a/file",-1,"b/link",RENAME_EXCHANGE));
        directory_time(peer,"a",1); directory_time(peer,"b",1);
        reset_directory_time(s,"a"); reset_directory_time(s,"b");
        CHECK(!md_inode_unlink(s,-1,"a/child",AT_REMOVEDIR));
        CHECK(!md_inode_unlink(s,-1,"b/link",0));
        directory_time(peer,"a",1); directory_time(peer,"b",1);
        md_inode_store_close(peer); md_inode_store_close(s);
        s=store(name,0); directory_time(s,"a",1); directory_time(s,"b",1);
        md_inode_store_close(s);
    }
    struct md_inode_store *source=store("directory-times-source",1),*copy=store("directory-times-copy",1);
    CHECK(!md_inode_mkdir(source,-1,"a",0700)); reset_directory_time(source,"a");
    CHECK(!md_inode_store_seal(source) && !md_inode_snapshot(source,copy));
    CHECK(!md_inode_symlink(copy,"target",-1,"a/link"));
    directory_time(copy,"a",1); directory_time(source,"a",0);
    md_inode_store_close(copy); md_inode_store_close(source); md_identity_release(&identity);
    puts("PASS directory timestamps: logical/native names, errors, same-inode rename, exchange, peer/reopen and snapshot isolation");
}
int main(int argc, char **argv) {
    CHECK(argc == 2 || argc == 3);
    CHECK(argv[1][0] == '/' && strlen(argv[1]) < sizeof(root)); strcpy(root, argv[1]);
    if (argc == 3) { CHECK(!strcmp(argv[2], "exec-child")); exec_child("exec"); return 0; }
    CHECK(mkdir(root, 0700) == 0);
    descriptors(); dentry_names(); namespace(); directory_times(); contention(); multiple_stores(); across_exec(argv[0]);
    native_plans(); native_births(); native_birth_custody(); native_birth_recovery(); native_birth_acl();
    hierarchy(); symlinks(); permissions(); kernel_reference(); scoped_paths(); read_admission_failure(); operation_windows();
    path_prefixes(); prefix_mutations(); metadata_working_set();
    permission_snapshot(); reused_queries(); metadata_queries(); cached_path_names();
    backing_cache(); recent_cache_keys(); mutation_cache(); directory_publication(); membership_queries(); directory_name_cost(); counter_audit(); retained_journal();
    unsigned serial = 0;
    recovery(CREATE, MD_OBJECT_SYNCED, serial++);
    for (enum operation op = CREATE; op <= EXCHANGE; ++op) {
        recovery(op, MD_NAMESPACE_STAGED, serial++);
        recovery(op, MD_NAMESPACE_COMMITTED, serial++);
    }
    printf("PASS %u deterministic SIGKILL recovery boundaries, real journals and orphan accounting\n", serial);
    unsigned trees = 0;
    tree_recovery(MKDIR, MD_OBJECT_SYNCED, trees++);
    tree_recovery(SYMLINK, MD_OBJECT_SYNCED, trees++);
    for (enum operation op = MKDIR; op <= REMOVE_DIR; ++op) {
        tree_recovery(op, MD_NAMESPACE_STAGED, trees++);
        tree_recovery(op, MD_NAMESPACE_COMMITTED, trees++);
    }
    printf("PASS %u hierarchy SIGKILL boundaries, retained dirfd and consistent parent links\n", trees);
    puts("PASS inode-store fixture (hierarchical model, not guest syscall integration)");
    return 0;
}
