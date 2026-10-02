#ifndef MD_GUEST_COMMAND_ACCESS_H
#define MD_GUEST_COMMAND_ACCESS_H
#include "fs_mounts.h"

#define MD_COMMAND_GUEST_DIRECTORY "/run/magicdesk"
/* Returns the owner connection; only the supervisor retains it. */
int md_guest_command_access(const char *store, struct md_fs_attachment *attachment,
    char source[PATH_MAX]);
#endif
