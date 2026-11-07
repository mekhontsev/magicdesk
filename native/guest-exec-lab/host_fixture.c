#include "launch_identity.h"
#include <assert.h>
#include <sys/stat.h>
#include <unistd.h>

/* Component tests call the engine without its executable's main. Retain the
 * same real host admission; guest programs must not link this initializer. */
__attribute__((constructor)) static void admit_host(void) {
    assert(md_launch_identity(getuid(), geteuid(), getgid(), getegid()));
    /* Production filesystem owners apply the guest's captured mask explicitly. */
    umask(0);
}
