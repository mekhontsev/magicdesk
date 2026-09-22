#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>

static void launch(const char *helper, const char *fixture, unsigned scenario) {
    int channel[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, channel) == 0);
    int unrelated = dup(channel[0]);
    assert(unrelated >= 0);
    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        char descriptor[32];
        snprintf(descriptor, sizeof(descriptor), "%d", scenario == 1 ? STDOUT_FILENO : channel[1]);
        if (scenario == 2) close(channel[1]);
        if (scenario == 3) {
            int file = open("/dev/null", O_RDONLY);
            assert(file >= 0 && dup2(file, channel[1]) == channel[1]);
        }
        execl(helper, helper, descriptor, scenario == 4 ? "/no/such/wayland-client" : fixture,
            "--verify", (char *)NULL);
        _exit(99);
    }
    close(unrelated);
    close(channel[1]);
    int status;
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status));
    assert(WEXITSTATUS(status) == (scenario == 0 ? 0 : scenario == 4 ? 127 : 2));
    char marker;
    if (scenario == 0) assert(read(channel[0], &marker, 1) == 1 && marker == 'W');
    assert(read(channel[0], &marker, 1) == 0);
    close(channel[0]);
}

int main(int argc, char **argv) {
    assert(argc == 2);
    if (!strcmp(argv[1], "--verify")) {
        const char *value = getenv("WAYLAND_SOCKET");
        assert(value);
        int descriptor = atoi(value);
        assert(descriptor > STDERR_FILENO && fcntl(descriptor, F_GETFD) == 0);
        DIR *directory = opendir("/proc/self/fd");
        assert(directory);
        struct dirent *entry;
        while ((entry = readdir(directory))) {
            int fd = atoi(entry->d_name);
            assert(fd <= STDERR_FILENO || fd == descriptor || fd == dirfd(directory));
        }
        closedir(directory);
        assert(write(descriptor, "W", 1) == 1);
        close(descriptor);
        return 0;
    }
    char fixture[4096];
    assert(realpath(argv[0], fixture));
    for (unsigned scenario = 0; scenario < 5; ++scenario) launch(argv[1], fixture, scenario);
    puts("Inherited connection, descriptor isolation, malformed FD and exec failure passed");
    return 0;
}
