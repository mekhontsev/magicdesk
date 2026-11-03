#ifndef MD_KMSG_DEVICE_H
#define MD_KMSG_DEVICE_H
#include "control_file.h"
int md_kmsg_device_node(struct md_control_file *);
int md_kmsg_device_is_node(struct md_control_file);
#endif
