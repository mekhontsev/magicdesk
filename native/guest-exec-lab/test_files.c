#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <sys/xattr.h>
#include <unistd.h>

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    assert(!mkdir("/tmp/metadata", 0700));
    int dir = open("/tmp/metadata", O_RDONLY | O_DIRECTORY);
    assert(dir >= 0);
    int fd = openat(dir, "value", O_RDWR | O_CREAT | O_EXCL, 0600);
    assert(fd >= 0 && write(fd, "payload", 7) == 7);
    struct stat st;
    assert(!chmod("/tmp/metadata/value", 0640));
    assert(!fstat(fd, &st) && (st.st_mode & 0777) == 0640);
    assert(!fchmodat(dir, "value", 0600, 0));
    assert(!stat("/tmp/metadata/value", &st) && (st.st_mode & 0777) == 0600);
    assert(!chown("/tmp/metadata/value", getuid(), getgid()));
    assert(chown("/tmp/metadata/value", 0, 0) == -1 && (errno == EPERM || errno == EACCES));
    assert(!stat("/tmp/metadata/value", &st) && st.st_uid == 2000);
    assert(!truncate("/tmp/metadata/value", 3));
    assert(!fstat(fd, &st) && st.st_size == 3);
    struct timespec times[2] = {{1700000000, 123456789}, {1700000001, 987654321}};
    assert(!utimensat(dir, "value", times, 0));
    assert(!stat("/tmp/metadata/value", &st) && st.st_mtim.tv_sec == times[1].tv_sec
            && st.st_mtim.tv_nsec == times[1].tv_nsec);
    assert(!symlink("value", "/tmp/metadata/link"));
    ++times[1].tv_sec;
    assert(!utimensat(dir, "link", times, AT_SYMLINK_NOFOLLOW));
    assert(!lstat("/tmp/metadata/link", &st) && S_ISLNK(st.st_mode) && st.st_mtim.tv_sec == times[1].tv_sec);
    assert(!stat("/tmp/metadata/value", &st) && st.st_mtim.tv_sec != times[1].tv_sec);
    assert(!futimens(fd, times));
    assert(!stat("/tmp/metadata/value", &st) && st.st_mtim.tv_sec == times[1].tv_sec);
    struct statfs by_path, by_fd;
    assert(!statfs("/tmp/metadata/value", &by_path) && !fstatfs(fd, &by_fd));
    assert(by_path.f_type == by_fd.f_type && by_path.f_bsize == by_fd.f_bsize);
    puts("PASS modes, timestamps, truncate, statfs and actual shell ownership (not fake root)");

    int control = fsetxattr(fd, "user.md-fixture", "fd", 2, 0), control_errno = errno;
    int via_path = setxattr("/tmp/metadata/link", "user.md-fixture", "path", 4, 0);
    assert(via_path == control);
    if (control < 0) {
        assert(errno == control_errno);
        printf("LIMIT user xattr path and descriptor both denied (errno=%d)\n", errno);
    } else {
        char value[16] = {0};
        assert(getxattr("/tmp/metadata/value", "user.md-fixture", value, sizeof(value)) == 4);
        assert(!strcmp(value, "path"));
        assert(listxattr("/tmp/metadata/link", NULL, 0) > 0);
        assert(!removexattr("/tmp/metadata/link", "user.md-fixture"));
        assert(fgetxattr(fd, "user.md-fixture", value, sizeof(value)) == -1 && errno == ENODATA);
        puts("PASS path xattrs share the real descriptor metadata");
    }

    int events = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    assert(events >= 0);
    int watch = inotify_add_watch(events, "/tmp/metadata", IN_CREATE | IN_DELETE);
    assert(watch >= 0);
    int other = openat(dir, "other", O_CREAT | O_RDWR | O_EXCL, 0600);
    assert(other >= 0);
    assert(!fstat(fd, &st));
    ino_t value_inode = st.st_ino;
    assert(syscall(SYS_renameat2, dir, "value", dir, "other", 1 /* RENAME_NOREPLACE */) == -1 && errno == EEXIST);
    assert(!syscall(SYS_renameat2, dir, "value", dir, "other", 2 /* RENAME_EXCHANGE */));
    assert(!fstatat(dir, "other", &st, 0) && st.st_ino == value_inode);
    assert(!unlinkat(dir, "value", 0) && !unlinkat(dir, "other", 0));
    // Events were queued synchronously by the completed filesystem operations; no settling delay.
    char buffer[4096] __attribute__((aligned(__alignof__(struct inotify_event))));
    ssize_t n = read(events, buffer, sizeof(buffer));
    assert(n > 0);
    int creates = 0, deletes = 0;
    for (ssize_t at = 0; at < n;) {
        struct inotify_event *event = (struct inotify_event *)(buffer + at);
        assert(at + (ssize_t)sizeof(*event) <= n);
        assert(at + (ssize_t)sizeof(*event) + event->len <= n);
        creates += !!(event->mask & IN_CREATE);
        deletes += !!(event->mask & IN_DELETE);
        at += sizeof(*event) + event->len;
    }
    assert(creates == 1 && deletes == 2);
    assert(!inotify_rm_watch(events, watch));
    close(events); close(other); close(fd);
    assert(!unlinkat(dir, "link", 0));
    close(dir);
    assert(!rmdir("/tmp/metadata"));
    puts("PASS atomic rename flags and inotify namespace mapping");
    return 0;
}
