#ifndef MD_LAUNCH_ENVIRONMENT_H
#define MD_LAUNCH_ENVIRONMENT_H
#include <linux/limits.h>

struct md_launch_environment {
    char home[PATH_MAX + 8];
    char *values[128];
};
long md_launch_environment(struct md_launch_environment *, const char *home, char **inherited);
#endif
