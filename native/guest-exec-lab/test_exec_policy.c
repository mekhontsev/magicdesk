#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc != 2 || argv[1][0] != '/') return 2;
    int fd = syscall(SYS_memfd_create, "guest-exec-policy", 0x10 /* MFD_EXEC */);
    if (fd < 0) { printf("memfd_create errno=%d\n", errno); return 1; }
    char script[512];
    int size = snprintf(script, sizeof(script), "#!%s\nexit 42\n", argv[1]);
    if (size < 0 || size >= (int)sizeof(script) || write(fd, script, size) != size) return 2;
    struct stat st;
    if (fstat(fd, &st)) return 2;
    int access = syscall(SYS_faccessat2, fd, "", X_OK, AT_EMPTY_PATH | 0x200);
    printf("memfd mode=%o executable=%d errno=%d\n", st.st_mode, access == 0, access < 0 ? errno : 0);
    fflush(stdout);
    pid_t child = fork();
    if (child < 0) return 2;
    if (!child) {
        char *args[] = {"guest-exec-policy", NULL}, *env[] = {NULL};
        syscall(SYS_execveat, fd, "", args, env, AT_EMPTY_PATH);
        int error = errno;
        dprintf(STDOUT_FILENO, "execveat errno=%d (%s)\n", error, strerror(error));
        _exit(127);
    }
    int status;
    // EVENT_WAIT: this exact policy probe exits; the caller bounds a hung child.
    while (waitpid(child, &status, 0) < 0) if (errno != EINTR) return 2;
    close(fd);
    printf("execveat status=%d\n", status);
    return WIFEXITED(status) && WEXITSTATUS(status) == 42 ? 0 : 1;
}
