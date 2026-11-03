#ifndef MD_PROC_STATIC_H
#define MD_PROC_STATIC_H
#include "proc_paths.h"
#include "control_file.h"
struct md_user_namespace;
struct md_network_namespace;
struct md_control_file md_proc_static_new(enum md_proc_kind,struct md_user_namespace *,struct md_fs_anchor *,struct md_network_namespace *);
ino_t md_proc_static_inode(const char *name);
#endif
