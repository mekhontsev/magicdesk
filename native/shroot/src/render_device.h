#ifndef MD_RENDER_DEVICE_H
#define MD_RENDER_DEVICE_H
#include "control_file.h"
/* Admission owns a real DMA-heap FD and tests allocation/fence exchange once.
 * Restore is only for an already admitted, transferred device-tree carrier. */
int md_render_device_admit(struct md_control_file *, int completion_owned);
int md_render_device_restore(unsigned minor,struct md_control_file *);
int md_render_device_is_node(struct md_control_file);
unsigned md_render_device_minor(struct md_control_file);
int md_render_device_sysfs(struct md_control_file,int root);
#endif
