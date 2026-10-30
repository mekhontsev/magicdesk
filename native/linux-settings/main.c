#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "portal.h"
#include "bus_agent.h"
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

static int connect_preferences(void) {
    const char *endpoint = getenv("MAGICDESK_APPEARANCE_SOCKET");
    const char *token = getenv("MAGICDESK_APPEARANCE_TOKEN");
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    if (!endpoint || !token || strlen(token) != 64 || !*endpoint
            || strlen(endpoint) > sizeof(address.sun_path) - 2) return -1;
    size_t length = strlen(endpoint);
    memcpy(address.sun_path + 1, endpoint, length);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct timeval deadline = {.tv_sec = 5};
    /* EVENT_WAIT: authenticated settings subscription; expiry disables only the preference bridge. */
    if (fd < 0 || setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &deadline, sizeof(deadline))
            || setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &deadline, sizeof(deadline))
            || connect(fd, (struct sockaddr *)&address, offsetof(struct sockaddr_un, sun_path) + 1 + length)) goto fail;
    size_t offset = 0;
    while (offset < 64) {
        ssize_t count = send(fd, token + offset, 64 - offset, MSG_NOSIGNAL);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) goto fail;
        offset += (size_t)count;
    }
    return fd;
fail:
    if (fd >= 0) close(fd);
    return -1;
}

static void serve(int ready) {
    int preferences = connect_preferences();
    unsigned char scheme, status = 0;
    MdSettings settings = {0};
    if (preferences < 0 || read(preferences, &scheme, 1) != 1 || scheme > 2) goto done;
    int admitted = md_settings_open(&settings, scheme);
    if (admitted <= 0) goto done;
    int busfd;
    if (!dbus_connection_get_unix_fd(settings.bus, &busfd)) goto done;
    status = 1;
    if (write(ready, &status, 1) != 1) goto done;
    close(ready); ready = -1;
    for (;;) {
        if (settings.replaced || !dbus_connection_get_is_connected(settings.bus)) break;
        DBusDispatchStatus dispatch = dbus_connection_get_dispatch_status(settings.bus);
        struct pollfd fds[] = {
            {.fd = preferences, .events = POLLIN},
            {.fd = busfd, .events = POLLIN | (dbus_connection_has_messages_to_send(settings.bus) ? POLLOUT : 0)}
        };
        /* EVENT_WAIT: D-Bus traffic, changed preference or endpoint closure; no periodic wake-up. */
        int result = poll(fds, 2, dispatch == DBUS_DISPATCH_DATA_REMAINS ? 0 : -1);
        if (result < 0 && errno == EINTR) continue;
        if (result < 0 || (fds[0].revents & (POLLERR | POLLHUP | POLLNVAL))) break;
        if (fds[0].revents & POLLIN) {
            if (read(preferences, &scheme, 1) != 1 || scheme > 2) break;
            md_settings_changed(&settings, scheme);
        }
        if (!dbus_connection_read_write(settings.bus, 0)) break;
        for (unsigned i = 0; i < 64 && dbus_connection_get_dispatch_status(settings.bus) == DBUS_DISPATCH_DATA_REMAINS; i++)
            if (dbus_connection_dispatch(settings.bus) == DBUS_DISPATCH_NEED_MEMORY) goto done;
    }
done:
    if (ready >= 0) { (void)write(ready, &status, 1); close(ready); }
    md_settings_close(&settings);
    if (preferences >= 0) close(preferences);
}

static void launch(char **command) {
    unsetenv("MAGICDESK_APPEARANCE_SOCKET");
    unsetenv("MAGICDESK_APPEARANCE_TOKEN");
    unsetenv("MAGICDESK_APPEARANCE_HELPER");
    unsetenv("MAGICDESK_COLOR_SCHEME");
    unsetenv("MAGICDESK_APPEARANCE_BUS_STARTED");
    execvp(command[0], command);
    perror("Linux application");
    exit(127);
}

int main(int argc, char **argv) {
    if (argc == 3 && !strcmp(argv[1], "--session-agent")) {
        char *end; errno = 0;
        long fd = strtol(argv[2], &end, 10);
        if (errno || !*argv[2] || *end || fd < 3 || fd > 0x7fffffff) return 2;
        return md_bus_session_serve((int)fd);
    }
    if (argc >= 3 && !strcmp(argv[1], "--session")) {
        if (md_bus_session_start(argv[0])) {
            perror("Linux session bus unavailable"); return 1;
        }
        execvp(argv[2], argv + 2);
        perror("Linux session application"); return 127;
    }
    if (argc >= 3 && !strcmp(argv[1], "--automation")) {
        if (md_bus_agent_start()) perror("Linux automation unavailable");
        execvp(argv[2], argv + 2);
        perror("Linux automation application"); return 127;
    }
    if (argc < 3 || strcmp(argv[1], "--")) {
        fprintf(stderr, "Usage: magicdesk-linux-settings -- PROGRAM ARG...\n");
        return 2;
    }
    if (!getenv("MAGICDESK_APPEARANCE_SOCKET")) launch(argv + 2);
    if (!getenv("DBUS_SESSION_BUS_ADDRESS")) {
        if (!getenv("MAGICDESK_APPEARANCE_BUS_STARTED")) {
            char **bus = calloc((size_t)argc + 3, sizeof(*bus));
            if (bus) {
                bus[0] = "dbus-run-session"; bus[1] = "--";
                for (int i = 0; i < argc; i++) bus[i + 2] = argv[i];
                setenv("MAGICDESK_APPEARANCE_BUS_STARTED", "1", 1);
                execvp(bus[0], bus);
                free(bus);
            }
        }
        fprintf(stderr, "MagicDesk appearance: no session bus; launching without Settings portal\n");
        launch(argv + 2);
    }
    int ready[2];
    if (pipe2(ready, O_CLOEXEC)) launch(argv + 2);
    pid_t worker = fork();
    if (!worker) {
        close(ready[0]);
        DIR *descriptors = opendir("/proc/self/fd");
        if (!descriptors) _exit(1);
        struct dirent *entry;
        while ((entry = readdir(descriptors))) {
            char *end;
            long fd = strtol(entry->d_name, &end, 10);
            if (*entry->d_name && !*end && fd > 2 && fd != ready[1] && fd != dirfd(descriptors)) close((int)fd);
        }
        closedir(descriptors);
        /* Session socket and D-Bus own this worker; do not retain the launched command's pipes. */
        int null = open("/dev/null", O_RDWR | O_CLOEXEC);
        for (int fd = 0; fd < 3; fd++) { if (null >= 0) dup2(null, fd); else close(fd); }
        if (null > 2) close(null);
        serve(ready[1]);
        _exit(0);
    }
    close(ready[1]);
    if (worker < 0) { close(ready[0]); launch(argv + 2); }
    struct pollfd event = {.fd = ready[0], .events = POLLIN};
    /* EVENT_WAIT: provider registered or declined; expiry cancels the helper, not the application. */
    int result = poll(&event, 1, 5000);
    unsigned char active = 0;
    if (result <= 0 || read(ready[0], &active, 1) != 1 || active != 1) {
        kill(worker, SIGTERM);
        while (waitpid(worker, NULL, 0) < 0 && errno == EINTR) { }
    }
    close(ready[0]);
    launch(argv + 2);
    return 127;
}
