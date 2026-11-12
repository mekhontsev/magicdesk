#ifndef MD_CONTROL_IOCTL_H
#define MD_CONTROL_IOCTL_H
#include "control_file.h"
#include <errno.h>
#include <string.h>

static inline int md_control_ioctl_available(struct md_control_file file) {
    return file.ops && file.ops->ioctl_spec && (file.ops->ioctl || file.ops->ioctl_async);
}
static inline long md_control_ioctl_size(const struct md_control_ioctl *s) {
    if (s->size>MD_CONTROL_IOCTL_MAX
            || s->direction&~(MD_CONTROL_IN|MD_CONTROL_OUT|MD_CONTROL_STRING|MD_CONTROL_INDIRECT|MD_CONTROL_ACK|MD_CONTROL_FD|MD_CONTROL_CONST)
            || s->fields>MD_CONTROL_IOCTL_FIELDS
            || !!s->fields!=!!(s->direction&MD_CONTROL_INDIRECT)) return -EPROTO;
    if (s->fields && (!(s->direction&MD_CONTROL_IN) || (s->direction&MD_CONTROL_STRING))) return -EPROTO;
    if ((s->direction&MD_CONTROL_CONST) && (s->direction!=(MD_CONTROL_OUT|MD_CONTROL_CONST)
            || s->fields || s->size>sizeof(s->value))) return -EPROTO;
    if ((s->direction&MD_CONTROL_ACK) && !(s->direction&MD_CONTROL_OUT)) return -EPROTO;
    if ((s->direction&MD_CONTROL_FD) && (!(s->direction&MD_CONTROL_IN)
            || (s->direction&MD_CONTROL_STRING) || s->size<sizeof(int)
            || s->descriptor>s->size-sizeof(int))) return -EPROTO;
    size_t total=s->size;
    for (unsigned i=0;i<s->fields;++i) {
        const struct md_control_ioctl_field *f=&s->field[i];
        if (s->size<sizeof(uintptr_t) || f->pointer>s->size-sizeof(uintptr_t)
                || s->size<sizeof(size_t) || f->length>s->size-sizeof(size_t)
                || !f->direction || f->direction&~(MD_CONTROL_IN|MD_CONTROL_OUT)
                || f->capacity>MD_CONTROL_IOCTL_MAX-total) return -EPROTO;
        total+=f->capacity;
    }
    return (long)total;
}
static inline int md_control_ioctl_constant(const struct md_control_ioctl *s,void *data) {
    if (!(s->direction&MD_CONTROL_CONST)) return 0;
    memcpy(data,&s->value,s->size);
    return 1;
}
static inline size_t md_control_ioctl_capture_offset(const struct md_control_ioctl *s) {
    return ((size_t)md_control_ioctl_size(s)+7)&~(size_t)7;
}
static inline size_t md_control_ioctl_field_size(const struct md_control_ioctl *s,unsigned index,const void *data) {
    size_t length;
    memcpy(&length,(const char *)data+s->field[index].length,sizeof(length));
    return length<s->field[index].capacity ? length : s->field[index].capacity;
}
#endif
