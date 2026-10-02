#define _GNU_SOURCE
#include "launch_registry.h"
#include <assert.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

static int visit(const char *id, const struct md_launch_info *info, void *context) {
    assert(strlen(id) == 32 && info->executor_uid == getuid());
    assert(!strcmp(info->program, "/bin/fixture"));
    ++*(int *)context; return 0;
}
static pid_t launch(const char *store, char id[33]) {
    int ready[2]; assert(!pipe(ready));
    pid_t pid = fork(); assert(pid >= 0);
    if (!pid) {
        close(ready[0]);
        struct md_launch_registration owner;
        assert(!md_launch_register(&owner, store, "/bin/fixture", "/work", 1234));
        assert(write(ready[1], owner.id, 33) == 33);
        // EVENT_WAIT: test termination signal; alarm bounds a broken parent.
        alarm(30); for (;;) pause();
    }
    close(ready[1]);
    // EVENT_WAIT: registry publication; parent alarm fails a broken child.
    assert(read(ready[0], id, 33) == 33); close(ready[0]); return pid;
}
int main(void) {
    alarm(60);
    char store[] = "./md-launch-XXXXXX";
    assert(mkdtemp(store));
    char a[33], b[33]; pid_t first = launch(store, a), second = launch(store, b);
    int count = 0; assert(!md_launch_list(store, visit, &count) && count == 2);
    assert(!md_launch_stop(store, a));
    int status; assert(waitpid(first, &status, 0) == first && WTERMSIG(status) == SIGTERM);
    count = 0; assert(!md_launch_list(store, visit, &count) && count == 1);
    assert(md_launch_stop(store, a) == -ENOENT);
    assert(!kill(second, SIGKILL)); assert(waitpid(second, &status, 0) == second);
    count = 0; assert(!md_launch_list(store, visit, &count) && count == 0);
    assert(md_launch_stop(store, "../wrong") == -EINVAL);
    char records[4096]; snprintf(records, sizeof(records), "%s/launches", store);
    assert(!rmdir(records)); assert(!rmdir(store));
    puts("PASS launch ownership, exact cancellation, independent owner and stale recovery");
}
