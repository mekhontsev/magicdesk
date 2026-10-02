#ifndef MD_PROCESS_IMAGE_VIEW_H
#define MD_PROCESS_IMAGE_VIEW_H
#include <stdint.h>
#include <sys/types.h>
/* The supervisor authorizes identity/lifetime before taking a guest snapshot. */
int md_process_image_view(pid_t, uintptr_t image, unsigned kind, unsigned flags, const char *endpoint, pid_t caller);
#endif
