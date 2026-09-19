#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

static const char token[] = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

static void launch(const char *helper, const char *fixture, unsigned scenario) {
    int listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    assert(listener >= 0);
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    snprintf(address.sun_path + 1, sizeof(address.sun_path) - 1, "magicdesk-fd-test-%ld-%u", (long)getpid(), scenario);
    socklen_t size = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(address.sun_path + 1);
    assert(bind(listener, (struct sockaddr *)&address, size) == 0);
    assert(listen(listener, 1) == 0);
    int channel[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, channel) == 0);
    int unrelated = dup(listener);
    assert(unrelated >= 0);
    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        char owner[32];
        snprintf(owner, sizeof(owner), "%lu", (unsigned long)getuid() + (scenario == 1));
        execl(helper, helper, address.sun_path + 1, token, owner, fixture, "--verify", (char *)NULL);
        _exit(99);
    }
    close(unrelated);
    int transport = accept4(listener, NULL, NULL, SOCK_CLOEXEC);
    assert(transport >= 0);
    struct ucred peer;
    socklen_t peer_size = sizeof(peer);
    assert(getsockopt(transport, SOL_SOCKET, SO_PEERCRED, &peer, &peer_size) == 0);
    assert(peer.uid == getuid() && peer.pid == child);
    char received[64];
    if (scenario == 1) {
        assert(read(transport, received, sizeof(received)) == 0);
    } else {
        assert(recv(transport, received, sizeof(received), MSG_WAITALL) == sizeof(received));
        assert(memcmp(received, token, sizeof(received)) == 0);
        char marker = scenario == 2 ? 0 : 1;
        struct iovec payload = {.iov_base = &marker, .iov_len = 1};
        union { struct cmsghdr alignment; char bytes[CMSG_SPACE(2 * sizeof(int))]; } control = {0};
        size_t descriptors = scenario == 3 ? 2 : 1;
        struct msghdr message = {.msg_iov = &payload, .msg_iovlen = 1,
            .msg_control = control.bytes, .msg_controllen = CMSG_SPACE(descriptors * sizeof(int))};
        struct cmsghdr *header = CMSG_FIRSTHDR(&message);
        header->cmsg_level = SOL_SOCKET;
        header->cmsg_type = SCM_RIGHTS;
        header->cmsg_len = CMSG_LEN(descriptors * sizeof(int));
        int values[] = {channel[1], channel[1]};
        memcpy(CMSG_DATA(header), values, descriptors * sizeof(int));
        assert(sendmsg(transport, &message, MSG_NOSIGNAL) == 1);
    }
    close(channel[1]);
    close(transport);
    close(listener);
    char result;
    assert(read(channel[0], &result, 1) == (scenario == 0 ? 1 : 0));
    if (scenario == 0) assert(result == 'W');
    close(channel[0]);
    int status;
    assert(waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status));
    assert(WEXITSTATUS(status) == (scenario == 0 ? 0 : 1));
}

int main(int argc, char **argv) {
    assert(argc == 2);
    if (strcmp(argv[1], "--verify") == 0) {
        const char *value = getenv("WAYLAND_SOCKET");
        assert(value);
        int descriptor = atoi(value);
        assert(descriptor > STDERR_FILENO);
        assert(fcntl(descriptor, F_GETFD) == 0);
        DIR *directory = opendir("/proc/self/fd");
        assert(directory);
        struct dirent *entry;
        while ((entry = readdir(directory))) {
            int open_descriptor = atoi(entry->d_name);
            assert(open_descriptor <= STDERR_FILENO || open_descriptor == descriptor || open_descriptor == dirfd(directory));
        }
        closedir(directory);
        assert(write(descriptor, "W", 1) == 1);
        close(descriptor);
        return 0;
    }
    char fixture[4096];
    assert(realpath(argv[0], fixture));
    for (unsigned scenario = 0; scenario < 4; ++scenario) launch(argv[1], fixture, scenario);
    return 0;
}