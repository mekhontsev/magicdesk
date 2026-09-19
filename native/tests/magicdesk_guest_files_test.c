#define main guest_files_main
#include "../magicdesk_guest_files.c"
#undef main
#include <assert.h>
#include <sys/wait.h>

static int request(int socketFd, const char *path, int expected) {
    uint32_t size = (uint32_t)strlen(path);
    unsigned char header[] = {size >> 24, size >> 16, size >> 8, size};
    for (size_t i = 0; i < sizeof(header); i++) assert(!transfer(socketFd, header + i, 1, 1));
    assert(!transfer(socketFd, (void *)path, size, 1));
    unsigned char status;
    struct iovec io = {&status, 1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } ancillary = {0};
    struct msghdr message = {.msg_iov = &io, .msg_iovlen = 1,
            .msg_control = ancillary.bytes, .msg_controllen = sizeof(ancillary.bytes)};
    assert(recvmsg(socketFd, &message, 0) == 1);
    assert(status == expected);
    struct cmsghdr *c = CMSG_FIRSTHDR(&message);
    if (expected) { assert(!c); return -1; }
    assert(c && c->cmsg_type == SCM_RIGHTS && c->cmsg_len == CMSG_LEN(sizeof(int)));
    int fd;
    memcpy(&fd, CMSG_DATA(c), sizeof(fd));
    return fd;
}

int main(void) {
    char directory[4096], file[4128], fifo[4128], link[4128], large[4128];
    snprintf(directory, sizeof(directory), "%s/guest-files.XXXXXX", getenv("TMPDIR") ?: "/tmp");
    assert(mkdtemp(directory));
    snprintf(file, sizeof(file), "%s/space ' file", directory);
    snprintf(fifo, sizeof(fifo), "%s/fifo", directory);
    snprintf(link, sizeof(link), "%s/link", directory);
    snprintf(large, sizeof(large), "%s/large", directory);
    int fd = open(file, O_WRONLY | O_CREAT | O_EXCL, 0600);
    assert(fd >= 0 && write(fd, "data", 4) == 4); close(fd);
    assert(!mkfifo(fifo, 0600)); assert(!symlink(file, link));
    fd = open(large, O_WRONLY | O_CREAT | O_EXCL, 0600);
    assert(fd >= 0 && !ftruncate(fd, 128LL * 1024 * 1024 + 1)); close(fd);
    int sockets[2]; assert(!socketpair(AF_UNIX, SOCK_STREAM, 0, sockets));
    pid_t worker = fork(); assert(worker >= 0);
    if (!worker) { close(sockets[0]); _exit(serve(sockets[1])); }
    close(sockets[1]);
    fd = request(sockets[0], link, 0);
    assert(!unlink(file));
    char bytes[4]; assert(read(fd, bytes, sizeof(bytes)) == 4 && !memcmp(bytes, "data", 4)); close(fd);
    request(sockets[0], file, ENOENT);
    request(sockets[0], directory, EINVAL);
    request(sockets[0], fifo, EINVAL);
    request(sockets[0], large, EINVAL);
    close(sockets[0]);
    int status; assert(waitpid(worker, &status, 0) == worker && WIFEXITED(status) && !WEXITSTATUS(status));
    unlink(fifo); unlink(link); unlink(large); rmdir(directory);
    puts("Guest file descriptors: literal paths, symlinks, unlink, nonregular/large rejection and owner loss verified");
}
