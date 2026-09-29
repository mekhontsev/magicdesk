#define _GNU_SOURCE
#include "inode_store.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/fs.h>
#include <signal.h>
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
        md_inode_observe(s, gate, &g); apply(s, op);
        _exit(99); /* Every selected checkpoint must be observed, not skipped. */
    }
    close(ready[1]); close(resume[0]);
    CHECK(event(ready[0]) == 'R');
    if (point == MD_NAMESPACE_STAGED) {
        char journal[PATH_MAX];
        CHECK(snprintf(journal, sizeof(journal), "%s/%s/namespace.db-journal", root, name) < PATH_MAX);
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
    CHECK(md_inode_rename(s, left, "sub", right, "moved", 0) == 0);
    expected_path(s, sub, "/right/moved");
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
    CHECK(md_inode_fstat(s, fd, &st) == 0 && S_ISLNK(st.st_mode) && st.st_ino == link.st_ino); close(fd);
    char bytes[PATH_MAX] = {0};
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
    int file = md_inode_create(s, dir, "value", 0600); CHECK(file >= 0); close(file);
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
int main(int argc, char **argv) {
    CHECK(argc == 2 || argc == 3);
    CHECK(argv[1][0] == '/' && strlen(argv[1]) < sizeof(root)); strcpy(root, argv[1]);
    if (argc == 3) { CHECK(!strcmp(argv[2], "exec-child")); exec_child("exec"); return 0; }
    CHECK(mkdir(root, 0700) == 0);
    descriptors(); namespace(); contention(); multiple_stores(); across_exec(argv[0]);
    hierarchy(); symlinks(); permissions(); kernel_reference();
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
