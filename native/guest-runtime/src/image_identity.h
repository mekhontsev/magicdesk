#ifndef MD_IMAGE_IDENTITY_H
#define MD_IMAGE_IDENTITY_H
#include <linux/limits.h>

/* Identity belongs to the resolved dentry and the exact opened object. It is
 * descriptive metadata, not execution permission or an image admission grant. */
struct md_image_identity { char object[33]; char path[PATH_MAX]; };
#endif
