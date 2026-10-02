#ifndef MD_LAUNCH_REGISTRY_H
#define MD_LAUNCH_REGISTRY_H
#include <stdint.h>
#include <sys/types.h>

struct md_launch_info {
    uint32_t version, executor_uid, guest_uid;
    int32_t pid, descriptor;
    char program[4096], cwd[4096], label[1024];
};
struct md_launch_registration { int directory, record; char id[33]; };
int md_launch_register(struct md_launch_registration *, const char *store,
    const char *program, const char *cwd, uint32_t guest_uid);
void md_launch_unregister(struct md_launch_registration *);
typedef int (*md_launch_visitor)(const char *, const struct md_launch_info *, void *);
int md_launch_list(const char *store, md_launch_visitor, void *);
int md_launch_stop(const char *store, const char *id);
#endif
