/* Host-only regression: no Android service, socket, or user shell is started. */
#define main magicdesk_pty_bridge_main
#include "../magicdesk_pty_bridge.c"
#undef main

#include <assert.h>

static const char *marker_environment = "MAGICDESK_PTY_TEST_MARKER";
static const char *probe_command = "magicdesk-cwd-probe";

/* The test binary acts as the child executable so login profiles cannot run. */
static int child_probe(const char *mode) {
    const char *marker = getenv(marker_environment);
    const char *pwd = getenv("PWD");
    char directory[PATH_MAX];
    if (marker == NULL || pwd == NULL || getcwd(directory, sizeof(directory)) == NULL) {
        return 120;
    }
    FILE *output = fopen(marker, "w");
    if (output == NULL) {
        return 121;
    }
    const int count = fprintf(output, "%s\n%s\n%s\n", directory, pwd, mode);
    const int closed = fclose(output);
    return count < 0 || closed != 0 ? 122 : 0;
}

static void check_startup(
        const char *executable,
        const char *directory,
        const char *marker,
        int expected_error,
        int with_command) {
    (void) unlink(marker);
    assert(setenv(marker_environment, marker, 1) == 0);
    assert(setenv("PWD", "/stale-inherited-directory", 1) == 0);
    pid_t child = -1;
    const int master = open_shell_pty(
            directory, executable, executable,
            with_command ? probe_command : "",
            0, 24, 80, &child);
    const int startup_error = errno;
    if (master >= 0) {
        /* The fake executable exits after one small file write, without PTY I/O. */
        int status = 0;
        pid_t waited;
        do {
            waited = waitpid(child, &status, 0);
        } while (waited < 0 && errno == EINTR);
        close(master);
        assert(waited == child);
        assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    }
    if (expected_error != 0) {
        /* This assertion fails on the old fallback, after reaping its child. */
        assert(master == -1);
        assert(startup_error == expected_error);
        assert(child == -1);
        assert(access(marker, F_OK) == -1 && errno == ENOENT);
        return;
    }
    assert(master >= 0);
    FILE *output = fopen(marker, "r");
    assert(output != NULL);
    char actual_directory[PATH_MAX];
    char actual_pwd[PATH_MAX];
    char mode[32];
    assert(fgets(actual_directory, sizeof(actual_directory), output) != NULL);
    assert(fgets(actual_pwd, sizeof(actual_pwd), output) != NULL);
    assert(fgets(mode, sizeof(mode), output) != NULL);
    assert(fclose(output) == 0);
    actual_directory[strcspn(actual_directory, "\n")] = '\0';
    actual_pwd[strcspn(actual_pwd, "\n")] = '\0';
    char canonical[PATH_MAX];
    assert(realpath(directory, canonical) != NULL);
    assert(strcmp(actual_directory, canonical) == 0);
    assert(strcmp(actual_pwd, directory) == 0);
    assert(strcmp(mode, with_command ? "command\n" : "interactive\n") == 0);
    assert(unlink(marker) == 0);
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "-i") == 0) {
        return child_probe("interactive");
    }
    if (argc == 3 && strcmp(argv[1], "-lc") == 0) {
        const size_t length = strlen(probe_command);
        if (strncmp(argv[2], probe_command, length) != 0 || argv[2][length] != '\n') {
            return 123;
        }
        return child_probe("command");
    }
    assert(argc == 1);
    char executable[PATH_MAX];
    const ssize_t length = readlink("/proc/self/exe", executable, sizeof(executable) - 1U);
    assert(length > 0 && (size_t) length < sizeof(executable) - 1U);
    executable[length] = '\0';

    const char *tmp = getenv("TMPDIR");
    if (tmp == NULL || tmp[0] == '\0') {
        tmp = "/tmp";
    }
    char root[PATH_MAX];
    const int count = snprintf(root, sizeof(root), "%s/magicdesk-pty-cwd-XXXXXX", tmp);
    assert(count > 0 && (size_t) count < sizeof(root));
    assert(mkdtemp(root) != NULL);
    char missing[PATH_MAX];
    char not_directory[PATH_MAX];
    char marker[PATH_MAX];
    assert(strlen(root) + 16U < PATH_MAX);
    snprintf(missing, sizeof(missing), "%s/missing", root);
    snprintf(not_directory, sizeof(not_directory), "%s/not-directory", root);
    snprintf(marker, sizeof(marker), "%s/executed", root);
    const int file = open(not_directory, O_WRONLY | O_CREAT | O_EXCL, 0600);
    assert(file >= 0);
    assert(close(file) == 0);

    for (int with_command = 0; with_command <= 1; with_command++) {
        check_startup(executable, missing, marker, ENOENT, with_command);
        check_startup(executable, not_directory, marker, ENOTDIR, with_command);
        check_startup(executable, root, marker, 0, with_command);
    }
    assert(unlink(not_directory) == 0);
    assert(rmdir(root) == 0);
    puts("PTY working-directory tests passed");
    return 0;
}
