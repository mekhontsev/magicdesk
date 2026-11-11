#ifndef MD_RESOURCE_CONTROL_H
#define MD_RESOURCE_CONTROL_H
/* Internal resource operations share their supervisor owner across the
 * channel and kernel-notification transports. Not an application syscall. */
long md_guest_resource_control(unsigned service,unsigned operation,
    unsigned long first,unsigned long second,unsigned long third,unsigned long fourth);
#endif
