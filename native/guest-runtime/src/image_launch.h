#ifndef MD_IMAGE_LAUNCH_H
#define MD_IMAGE_LAUNCH_H
#include <stddef.h>
enum md_image_command { MD_IMAGE_RUN, MD_IMAGE_EXEC, MD_IMAGE_LOGIN };
int md_image_launch(enum md_image_command, int argc, char **argv);
int md_image_inspect(const char *);
struct md_filesystem;
struct md_identity;
struct md_guest_account;
int md_image_config_account(struct md_filesystem *, const char *json, const char *selected_user,
    struct md_identity *, struct md_guest_account *, int *guest_user);
int md_image_config_read(struct md_filesystem *, char **json);
const char *md_image_environment_value(char **entries, size_t count, const char *name);
#endif
