#ifndef MD_FULL_DEVICE_H
#define MD_FULL_DEVICE_H
#include "control_file.h"
struct md_fs_anchor;
/* Called only after inode permissions and mount policy admit a device open. */
struct md_control_file md_full_device_new(const struct stat *,struct md_fs_anchor *);
#endif
