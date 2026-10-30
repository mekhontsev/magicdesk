#define _GNU_SOURCE
#include "event_signal.h"
#include "input_devices.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/joystick.h>
#include <linux/magic.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/sysmacros.h>
#include <time.h>
#include <unistd.h>

struct input_reader;
struct effect_request;
struct input_device {
    struct md_input_description description;
    unsigned references,slot,effect_count;
    int connected;
    uint64_t inode;
    struct md_input_backend backend;
    uint8_t keys[(KEY_CNT+7)/8];
    struct input_reader *readers,*grab;
    struct { struct input_reader *owner; struct ff_effect effect; } effects[MD_INPUT_EFFECTS];
    struct effect_request *pending[MD_INPUT_EFFECTS];
};
struct md_input_bus {
    atomic_uint references;
    pthread_mutex_t lock;
    int wake,closed;
    struct input_device *devices[MD_INPUT_DEVICES];
    void (*output)(void *,const struct md_input_rumble *);
    void *context;
};
struct input_reader {
    atomic_uint references;
    struct md_input_bus *bus;
    struct input_device *device;
    struct input_reader *next;
    int joystick,opened,revoked,signal[2],signaled,dropped;
    clockid_t clock;
    unsigned head,count,offered;
    uint16_t gain;
    uint8_t axis_map[ABS_CNT];
    uint16_t button_map[KEY_CNT-BTN_MISC];
    unsigned axes,buttons;
    union { struct input_event ev; struct js_event js; } queue[MD_INPUT_QUEUE];
};
static const struct md_control_file_ops operations;
static atomic_uint_fast64_t next_inode=2;
static int bit(const uint8_t *bits,unsigned n) { return !!(bits[n/8]&(1U<<(n%8))); }
static void assign(uint8_t *bits,unsigned n,int value) {
    if (value) bits[n/8]|=1U<<(n%8); else bits[n/8]&=~(1U<<(n%8));
}
static void wake(struct md_input_bus *bus) {
    if (bus->wake>=0) md_event_signal(bus->wake);
}
static void signal_reader(struct input_reader *r) {
    if (r->signaled || !r->opened) return;
    char value=1;
    if (send(r->signal[1],&value,1,MSG_DONTWAIT|MSG_NOSIGNAL)==1) r->signaled=1;
}
static void stop_effect(struct input_reader *r,unsigned id) {
    if (r->device->backend.output) {
        struct input_event event={.type=EV_FF,.code=(uint16_t)id};
        r->device->backend.output(r->device->backend.context,&event);
    } else if (r->bus->output) {
        struct md_input_rumble stop={.device=r->device->slot,.effect=id};
        r->bus->output(r->bus->context,&stop);
    }
}
static void device_release(struct input_device *d) {
    if (!--d->references) { if (d->backend.release) d->backend.release(d->backend.context); free(d); }
}
int md_input_bus_new(int wake_fd,void (*output)(void *,const struct md_input_rumble *),void *context,struct md_input_bus **out) {
    struct md_input_bus *bus=calloc(1,sizeof(*bus));
    if (!bus) return -ENOMEM;
    bus->wake=-1;
    int error=pthread_mutex_init(&bus->lock,NULL);
    if (error) { free(bus); return -error; }
    if (wake_fd>=0) {
        bus->wake=fcntl(wake_fd,F_DUPFD_CLOEXEC,3);
        if (bus->wake<0) { error=-errno; pthread_mutex_destroy(&bus->lock); free(bus); return error; }
    }
    atomic_init(&bus->references,1); bus->output=output; bus->context=context;
    *out=bus; return 0;
}
struct md_input_bus *md_input_bus_retain(struct md_input_bus *bus) {
    if (bus) atomic_fetch_add_explicit(&bus->references,1,memory_order_relaxed);
    return bus;
}
void md_input_bus_release(struct md_input_bus *bus) {
    if (!bus || atomic_fetch_sub_explicit(&bus->references,1,memory_order_acq_rel)!=1) return;
    for (unsigned i=0;i<MD_INPUT_DEVICES;++i) if (bus->devices[i]) device_release(bus->devices[i]);
    if (bus->wake>=0) close(bus->wake);
    pthread_mutex_destroy(&bus->lock); free(bus);
}
int md_input_add_backend(struct md_input_bus *bus,unsigned slot,const struct md_input_description *description,
        const struct md_input_backend *backend) {
    if (!bus || !description || slot>=MD_INPUT_DEVICES || !memchr(description->name,0,sizeof(description->name))) return -EINVAL;
    for (unsigned i=0;i<ABS_CNT;++i) if (bit(description->axes,i)) {
        const struct input_absinfo *a=&description->abs[i];
        if (a->minimum>=a->maximum || a->value<a->minimum || a->value>a->maximum || a->flat<0 || a->fuzz<0) return -EINVAL;
    }
    struct input_device *d=calloc(1,sizeof(*d));
    if (!d) return -ENOMEM;
    d->description=*description; d->slot=slot; d->references=1; d->connected=1;
    d->effect_count=description->rumble ? (backend ? backend->effects : MD_INPUT_EFFECTS) : 0;
    if (d->effect_count>MD_INPUT_EFFECTS) { free(d); return -EINVAL; }
    d->inode=atomic_fetch_add_explicit(&next_inode,2,memory_order_relaxed);
    if (backend) { d->backend=*backend; d->backend.context=backend->retain(backend->context); }
    pthread_mutex_lock(&bus->lock);
    int error=bus->closed ? -ENODEV : bus->devices[slot] ? -EEXIST : 0;
    if (!error) bus->devices[slot]=d;
    pthread_mutex_unlock(&bus->lock);
    if (error) device_release(d); else wake(bus);
    return error;
}
int md_input_add(struct md_input_bus *bus,unsigned slot,const struct md_input_description *description) {
    return md_input_add_backend(bus,slot,description,NULL);
}
static void remove_locked(struct md_input_bus *bus,unsigned slot) {
    struct input_device *d=bus->devices[slot];
    if (!d) return;
    d->connected=0; d->grab=NULL;
    for (struct input_reader *r=d->readers;r;r=r->next) {
        for (unsigned i=0;i<MD_INPUT_EFFECTS;++i) if (d->effects[i].owner==r) stop_effect(r,i);
        shutdown(r->signal[0],SHUT_RDWR);
        shutdown(r->signal[1],SHUT_RDWR);
    }
    memset(d->effects,0,sizeof(d->effects));
    bus->devices[slot]=NULL; device_release(d);
}
int md_input_remove(struct md_input_bus *bus,unsigned slot) {
    if (!bus || slot>=MD_INPUT_DEVICES) return -EINVAL;
    pthread_mutex_lock(&bus->lock);
    int error=bus->devices[slot] ? 0 : -ENODEV;
    if (!error) remove_locked(bus,slot);
    pthread_mutex_unlock(&bus->lock); wake(bus); return error;
}
void md_input_disconnect(struct md_input_bus *bus) {
    if (!bus) return;
    pthread_mutex_lock(&bus->lock);
    bus->closed=1;
    for (unsigned i=0;i<MD_INPUT_DEVICES;++i) remove_locked(bus,i);
    bus->output=NULL; bus->context=NULL;
    pthread_mutex_unlock(&bus->lock); wake(bus);
}
int md_input_description(struct md_input_bus *bus,unsigned slot,struct md_input_description *out) {
    if (!bus || slot>=MD_INPUT_DEVICES || !out) return -EINVAL;
    pthread_mutex_lock(&bus->lock);
    int error=bus->devices[slot] ? 0 : -ENODEV;
    if (!error) *out=bus->devices[slot]->description;
    pthread_mutex_unlock(&bus->lock); return error;
}
static struct timeval timestamp(clockid_t clock) {
    struct timespec ts; clock_gettime(clock,&ts);
    return (struct timeval){ts.tv_sec,ts.tv_nsec/1000};
}
static int16_t axis_value(const struct input_absinfo *a,int value) {
    int64_t range=(int64_t)a->maximum-a->minimum;
    int64_t scaled=((int64_t)value-a->minimum)*65534/range-32767;
    return (int16_t)scaled;
}
static void queue_js(struct input_reader *r,unsigned type,unsigned number,int value,struct timeval time) {
    unsigned tail=(r->head+r->count)%MD_INPUT_QUEUE;
    if (r->count==MD_INPUT_QUEUE) { r->dropped=1; return; }
    r->queue[tail].js=(struct js_event){.time=(uint32_t)((uint64_t)time.tv_sec*1000+time.tv_usec/1000),
        .type=(uint8_t)type,.number=(uint8_t)number,.value=(int16_t)value};
    r->count++; signal_reader(r);
}
static void initialize_js(struct input_reader *r) {
    struct input_device *d=r->device;
    struct timeval time=timestamp(CLOCK_MONOTONIC);
    for (unsigned i=0;i<r->buttons;++i) queue_js(r,JS_EVENT_INIT|JS_EVENT_BUTTON,i,bit(d->keys,r->button_map[i]),time);
    for (unsigned i=0;i<r->axes;++i) {
        struct input_absinfo *a=&d->description.abs[r->axis_map[i]];
        queue_js(r,JS_EVENT_INIT|JS_EVENT_AXIS,i,axis_value(a,a->value),time);
    }
}
static void queue_event(struct input_reader *r,struct input_event event) {
    if (r->revoked || (r->device->grab && r->device->grab!=r)) return;
    event.time=timestamp(r->clock);
    if (r->joystick) {
        if (event.type==EV_KEY) for (unsigned i=0;i<r->buttons;++i)
            if (r->button_map[i]==event.code) { queue_js(r,JS_EVENT_BUTTON,i,!!event.value,event.time); break; }
        if (event.type==EV_ABS) for (unsigned i=0;i<r->axes;++i)
            if (r->axis_map[i]==event.code) {
                queue_js(r,JS_EVENT_AXIS,i,axis_value(&r->device->description.abs[event.code],event.value),event.time); break;
            }
        return;
    }
    if (r->count==MD_INPUT_QUEUE || r->dropped) { r->dropped=1; return; }
    r->queue[(r->head+r->count++)%MD_INPUT_QUEUE].ev=event; signal_reader(r);
}
static int frame_locked(struct input_device *d,const struct input_event *events,size_t count) {
    for (size_t i=0;i<count;++i) {
        const struct input_event *e=&events[i];
        if (e->type==EV_KEY) {
            if (e->code>=KEY_CNT || !bit(d->description.keys,e->code) || e->value<0 || e->value>2) return -EINVAL;
        } else if (e->type==EV_ABS) {
            if (e->code>=ABS_CNT || !bit(d->description.axes,e->code)
                    || e->value<d->description.abs[e->code].minimum || e->value>d->description.abs[e->code].maximum) return -EINVAL;
        } else return -EINVAL;
    }
    for (size_t i=0;i<count;++i) {
        struct input_event event=events[i];
        if (event.type==EV_KEY) assign(d->keys,event.code,event.value);
        else d->description.abs[event.code].value=event.value;
        for (struct input_reader *r=d->readers;r;r=r->next) queue_event(r,event);
    }
    if (count) for (struct input_reader *r=d->readers;r;r=r->next)
        queue_event(r,(struct input_event){.type=EV_SYN,.code=SYN_REPORT});
    return 0;
}
int md_input_frame(struct md_input_bus *bus,unsigned slot,const struct input_event *events,size_t count) {
    if (!bus || slot>=MD_INPUT_DEVICES || (count && !events) || count>MD_INPUT_QUEUE-1) return -EINVAL;
    pthread_mutex_lock(&bus->lock);
    int error=bus->devices[slot] ? frame_locked(bus->devices[slot],events,count) : -ENODEV;
    pthread_mutex_unlock(&bus->lock); if (!error && count) wake(bus); return error;
}
void md_input_neutralize(struct md_input_bus *bus) {
    if (!bus) return;
    pthread_mutex_lock(&bus->lock);
    for (unsigned i=0;i<MD_INPUT_DEVICES;++i) {
        struct input_device *d=bus->devices[i]; if (!d) continue;
        for (unsigned key=0;key<KEY_CNT;++key) if (bit(d->keys,key)) {
            struct input_event event={.type=EV_KEY,.code=(uint16_t)key}; frame_locked(d,&event,1);
        }
        for (unsigned axis=0;axis<ABS_CNT;++axis) if (bit(d->description.axes,axis)) {
            struct input_absinfo *a=&d->description.abs[axis];
            int center=a->minimum<0 && a->maximum>0 ? 0 : a->minimum;
            if (a->value!=center) { struct input_event event={.type=EV_ABS,.code=(uint16_t)axis,.value=center}; frame_locked(d,&event,1); }
        }
        for (unsigned id=0;id<MD_INPUT_EFFECTS;++id) if (d->effects[id].owner) stop_effect(d->effects[id].owner,id);
    }
    pthread_mutex_unlock(&bus->lock); wake(bus);
}
static void *retain(void *context) {
    struct input_reader *r=context;
    atomic_fetch_add_explicit(&r->references,1,memory_order_relaxed); return r;
}
static void release(void *context) {
    struct input_reader *r=context;
    if (atomic_fetch_sub_explicit(&r->references,1,memory_order_acq_rel)!=1) return;
    struct md_input_bus *bus=r->bus;
    pthread_mutex_lock(&bus->lock);
    struct input_device *d=r->device;
    if (r->opened) {
        struct input_reader **p=&d->readers; while (*p && *p!=r) p=&(*p)->next;
        if (*p) *p=r->next;
        if (d->grab==r) d->grab=NULL;
        for (unsigned i=0;i<MD_INPUT_EFFECTS;++i) if (d->effects[i].owner==r) {
            stop_effect(r,i); memset(&d->effects[i],0,sizeof(d->effects[i]));
        }
    }
    device_release(d); pthread_mutex_unlock(&bus->lock);
    if (r->signal[0]>=0) close(r->signal[0]);
    if (r->signal[1]>=0) close(r->signal[1]);
    free(r); md_input_bus_release(bus);
}
static struct input_reader *reader_new(struct md_input_bus *bus,struct input_device *d,int joystick) {
    struct input_reader *r=calloc(1,sizeof(*r));
    if (!r) return NULL;
    atomic_init(&r->references,1); r->signal[0]=r->signal[1]=-1;
    r->bus=md_input_bus_retain(bus); r->device=d; ++d->references;
    r->joystick=joystick; r->clock=CLOCK_REALTIME; r->gain=65535;
    return r;
}
int md_input_node(struct md_input_bus *bus,unsigned slot,int joystick,struct md_control_file *out) {
    if (!bus || slot>=MD_INPUT_DEVICES || !out) return -EINVAL;
    pthread_mutex_lock(&bus->lock);
    struct input_reader *r=bus->devices[slot] ? reader_new(bus,bus->devices[slot],joystick) : NULL;
    int error=bus->devices[slot] ? r ? 0 : -ENOMEM : -ENODEV;
    pthread_mutex_unlock(&bus->lock);
    if (!error) *out=(struct md_control_file){&operations,r};
    return error;
}
static void *open_reader(void *context,const struct md_control_opener *opener,int flags) {
    (void)opener;
    struct input_reader *node=context; struct md_input_bus *bus=node->bus;
    if (flags&(O_TRUNC|O_DIRECTORY|O_CREAT)) { errno=EINVAL; return NULL; }
    pthread_mutex_lock(&bus->lock);
    struct input_reader *r=node->device->connected ? reader_new(bus,node->device,node->joystick) : NULL;
    int error=!node->device->connected ? ENODEV : !r ? ENOMEM : 0;
    if (r) {
        if (socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC|SOCK_NONBLOCK,0,r->signal)) error=errno;
        else {
            r->opened=1; r->next=r->device->readers; r->device->readers=r;
            for (unsigned i=0;i<ABS_CNT;++i) if (bit(r->device->description.axes,i)) r->axis_map[r->axes++]=(uint8_t)i;
            for (unsigned i=BTN_MISC;i<KEY_CNT && r->buttons<255;++i)
                if (bit(r->device->description.keys,i)) r->button_map[r->buttons++]=(uint16_t)i;
            if (r->joystick) initialize_js(r);
        }
    }
    pthread_mutex_unlock(&bus->lock);
    if (error) { if (r) release(r); errno=error; return NULL; }
    return r;
}
static int access_node(void *context,const struct md_identity *id,unsigned mode) {
    (void)id; struct input_reader *r=context;
    if (mode&1U) return -EACCES;
    pthread_mutex_lock(&r->bus->lock); int error=r->device->connected ? 0 : -ENODEV; pthread_mutex_unlock(&r->bus->lock);
    return error;
}
static int stat_node(void *context,struct stat *st) {
    struct input_reader *r=context;
    memset(st,0,sizeof(*st)); st->st_mode=S_IFCHR|0660; st->st_nlink=1;
    st->st_dev=makedev(0,99); st->st_ino=r->device->inode+(r->joystick ? 1 : 0);
    st->st_rdev=makedev(13,md_input_minor(r->device->slot,r->joystick)); st->st_blksize=4096;
    return 0;
}
static int descriptor(void *context,int flags) {
    struct input_reader *r=context;
    if (flags&O_PATH) { int fd=open("/dev/null",O_PATH|O_CLOEXEC); return fd<0 ? -errno : fd; }
    if (!r->opened) return -EBADF;
    int fd=fcntl(r->signal[0],F_DUPFD_CLOEXEC,3); return fd<0 ? -errno : fd;
}
static int readable(void *context) {
    struct input_reader *r=context;
    pthread_mutex_lock(&r->bus->lock);
    int ready=r->count || r->revoked || !r->device->connected;
    pthread_mutex_unlock(&r->bus->lock); return ready;
}
static long read_events(void *context,const struct md_control_actor *actor,void *out,size_t size,int64_t offset) {
    (void)actor; (void)offset; struct input_reader *r=context;
    size_t unit=r->joystick ? sizeof(struct js_event) : sizeof(struct input_event);
    if (!size) return 0;
    if (size<unit) return -EINVAL;
    pthread_mutex_lock(&r->bus->lock);
    long result=-EAGAIN;
    if (r->revoked || !r->device->connected) result=-ENODEV;
    else if (r->count) {
        unsigned count=(unsigned)(size/unit); if (count>r->count) count=r->count;
        for (unsigned i=0;i<count;++i) memcpy((char *)out+i*unit,&r->queue[(r->head+i)%MD_INPUT_QUEUE],unit);
        r->offered=count; result=(long)(count*unit);
    }
    pthread_mutex_unlock(&r->bus->lock); return result;
}
static void read_commit(void *context,size_t copied) {
    struct input_reader *r=context; size_t unit=r->joystick ? sizeof(struct js_event) : sizeof(struct input_event);
    pthread_mutex_lock(&r->bus->lock);
    unsigned count=(unsigned)(copied/unit); if (count>r->offered) count=r->offered;
    r->head=(r->head+count)%MD_INPUT_QUEUE; r->count-=count; r->offered=0;
    if (r->dropped && r->count<MD_INPUT_QUEUE-2) {
        if (!r->joystick) {
            r->dropped=0;
            queue_event(r,(struct input_event){.type=EV_SYN,.code=SYN_DROPPED});
            queue_event(r,(struct input_event){.type=EV_SYN,.code=SYN_REPORT});
        } else if (!r->count) { r->dropped=0; initialize_js(r); }
    }
    if (!r->count && r->signaled) {
        char value; (void)recv(r->signal[0],&value,1,MSG_DONTWAIT); r->signaled=0;
    }
    pthread_mutex_unlock(&r->bus->lock);
}
static int ioctl_spec(void *context,unsigned command,struct md_control_ioctl *spec) {
    struct input_reader *r=context;
    if (_IOC_TYPE(command)!=(unsigned)(r->joystick ? 'j' : 'E')) return -ENOTTY;
    unsigned n=_IOC_NR(command),size=_IOC_SIZE(command),dir=_IOC_DIR(command);
    *spec=(struct md_control_ioctl){.size=size,
        .direction=((dir&_IOC_WRITE) ? MD_CONTROL_IN : 0)|((dir&_IOC_READ) ? MD_CONTROL_OUT : 0)};
    if (r->joystick) {
        switch (command) {
        case JSIOCGVERSION: case JSIOCGAXES: case JSIOCGBUTTONS:
        case JSIOCGAXMAP: case JSIOCGBTNMAP: return 0;
        default: return n==0x13 && dir==_IOC_READ ? 0 : -ENOTTY;
        }
    }
    switch (command) {
    case EVIOCGVERSION: case EVIOCGID: case EVIOCGEFFECTS: case EVIOCSCLOCKID: return 0;
    case EVIOCSFF: spec->direction=MD_CONTROL_IN|MD_CONTROL_OUT; return 0;
    case EVIOCRMFF: case EVIOCGRAB: case EVIOCREVOKE: spec->size=spec->direction=0; return 0;
    default:
        if (dir==_IOC_READ && (n==6 || n==7 || n==8 || n==9 || n==0x18
                || (n>=0x20 && n<0x20+EV_CNT) || (n>=0x40 && n<0x40+ABS_CNT))) return 0;
        return -ENOTTY;
    }
}
static long string_result(void *data,size_t size,const char *text) {
    size_t n=strlen(text)+1; if (n>size) n=size;
    if (n) memcpy(data,text,n);
    return (long)n;
}
static long bits_result(void *data,size_t size,const uint8_t *bits,size_t count) {
    size_t n=size<count ? size : count; if (n) memcpy(data,bits,n); return (long)n;
}
static long ioctl_locked(struct input_reader *r,unsigned command,unsigned long value,void *data,size_t size) {
    struct input_device *d=r->device; struct md_input_description *desc=&d->description;
    if (!d->connected || r->revoked) return -ENODEV;
    unsigned n=_IOC_NR(command);
    if (r->joystick) {
        switch (command) {
        case JSIOCGVERSION: *(int *)data=JS_VERSION; return 0;
        case JSIOCGAXES: *(uint8_t *)data=(uint8_t)r->axes; return 0;
        case JSIOCGBUTTONS: *(uint8_t *)data=(uint8_t)r->buttons; return 0;
        case JSIOCGAXMAP: memcpy(data,r->axis_map,sizeof(r->axis_map)); return 0;
        case JSIOCGBTNMAP: memcpy(data,r->button_map,sizeof(r->button_map)); return 0;
        default: return string_result(data,size,desc->name);
        }
    }
    switch (command) {
    case EVIOCGVERSION: *(int *)data=EV_VERSION; return 0;
    case EVIOCGID: memcpy(data,&desc->id,sizeof(desc->id)); return 0;
    case EVIOCGEFFECTS: *(int *)data=(int)d->effect_count; return 0;
    case EVIOCSCLOCKID: {
        int clock=*(int *)data;
        if (clock!=CLOCK_REALTIME && clock!=CLOCK_MONOTONIC && clock!=CLOCK_BOOTTIME) return -EINVAL;
        r->clock=clock; return 0;
    }
    case EVIOCGRAB:
        if (value && d->grab) return -EBUSY;
        if (!value && d->grab!=r) return -EINVAL;
        d->grab=value ? r : NULL; return 0;
    case EVIOCREVOKE:
        if (value) return -EINVAL;
        r->revoked=1; if (d->grab==r) d->grab=NULL;
        for (unsigned i=0;i<MD_INPUT_EFFECTS;++i) if (d->effects[i].owner==r) {
            stop_effect(r,i); memset(&d->effects[i],0,sizeof(d->effects[i]));
        }
        shutdown(r->signal[0],SHUT_RDWR); shutdown(r->signal[1],SHUT_RDWR);
        wake(r->bus); return 0;
    case EVIOCSFF: {
        struct ff_effect *e=data;
        if (!desc->rumble || e->type!=FF_RUMBLE) return -EINVAL;
        int id=e->id;
        if (id==-1) { for (id=0;id<(int)d->effect_count && (d->effects[id].owner || d->pending[id]);++id) { } if (id==(int)d->effect_count) return -ENOSPC; }
        else if (id<0 || id>=(int)d->effect_count || d->effects[id].owner!=r) return -EINVAL;
        if (d->pending[id]) return -EBUSY;
        e->id=(int16_t)id; d->effects[id].owner=r; d->effects[id].effect=*e; return 0;
    }
    case EVIOCRMFF:
        if (value>=MD_INPUT_EFFECTS || d->effects[value].owner!=r) return -EINVAL;
        if (d->pending[value]) return -EBUSY;
        stop_effect(r,(unsigned)value); memset(&d->effects[value],0,sizeof(d->effects[value])); return 0;
    default: break;
    }
    if (n==6) return string_result(data,size,desc->name);
    if (n==7) return string_result(data,size,d->backend.physical_path ? d->backend.physical_path : "magicdesk/gamepad");
    if (n==8) return string_result(data,size,"");
    if (n==9) return size<sizeof(uint32_t) ? (long)size : (long)sizeof(uint32_t);
    if (n==0x18) return bits_result(data,size,d->keys,sizeof(d->keys));
    if (n>=0x40 && n<0x40+ABS_CNT) {
        unsigned axis=n-0x40;
        if (size!=sizeof(struct input_absinfo) || !bit(desc->axes,axis)) return -EINVAL;
        memcpy(data,&desc->abs[axis],size); return 0;
    }
    if (n>=0x20 && n<0x20+EV_CNT) {
        unsigned type=n-0x20; uint8_t bits[(KEY_CNT+7)/8]={0}; size_t count=0;
        if (!type) { assign(bits,EV_SYN,1); assign(bits,EV_KEY,1); assign(bits,EV_ABS,1); assign(bits,EV_FF,desc->rumble); count=(EV_CNT+7)/8; }
        else if (type==EV_KEY) return bits_result(data,size,desc->keys,sizeof(desc->keys));
        else if (type==EV_ABS) return bits_result(data,size,desc->axes,sizeof(desc->axes));
        else if (type==EV_FF) { if (desc->rumble) { assign(bits,FF_RUMBLE,1); assign(bits,FF_GAIN,1); } count=(FF_CNT+7)/8; }
        return bits_result(data,size,bits,count);
    }
    return -ENOTTY;
}
static long ioctl_call(void *context,const struct md_control_actor *actor,unsigned command,unsigned long value,void *data,size_t size) {
    (void)actor; struct input_reader *r=context; struct md_control_ioctl spec;
    int error=ioctl_spec(r,command,&spec);
    if (error) return error;
    if (size!=spec.size || (size && !data)) return -EINVAL;
    if (spec.direction&MD_CONTROL_OUT && !(spec.direction&MD_CONTROL_IN)) memset(data,0,size);
    pthread_mutex_lock(&r->bus->lock);
    long result=ioctl_locked(r,command,value,data,size);
    pthread_mutex_unlock(&r->bus->lock); return result;
}
struct effect_request {
    struct md_control_pending pending,*backend;
    struct input_reader *reader,*old_owner;
    struct ff_effect previous,next;
    unsigned id;
    int finished,erase;
};
static void effect_settle(struct effect_request *request,int success) {
    struct input_reader *r=request->reader; struct input_device *d=r->device;
    if (d->pending[request->id]==request) {
        if (!d->connected || r->revoked) memset(&d->effects[request->id],0,sizeof(d->effects[request->id]));
        else if (!success) { d->effects[request->id].owner=request->old_owner; d->effects[request->id].effect=request->previous; }
        d->pending[request->id]=NULL;
    }
    request->finished=1;
}
static long effect_resume(struct md_control_pending *base,void *data,size_t size) {
    struct effect_request *request=(void *)base;
    long result=request->backend->resume(request->backend,NULL,0);
    if (result==-EINPROGRESS) return result;
    struct input_reader *r=request->reader;
    pthread_mutex_lock(&r->bus->lock);
    if (!r->device->connected || r->revoked) result=-ENODEV;
    if (!result && !request->erase) {
        if (size!=sizeof(request->next)) result=-EINVAL;
        else memcpy(data,&request->next,size);
    }
    effect_settle(request,result>=0);
    pthread_mutex_unlock(&r->bus->lock); return result;
}
static void effect_dispose(struct md_control_pending *base) {
    struct effect_request *request=(void *)base;
    request->backend->dispose(request->backend);
    struct input_reader *r=request->reader;
    pthread_mutex_lock(&r->bus->lock);
    if (!request->finished) effect_settle(request,0);
    pthread_mutex_unlock(&r->bus->lock);
    release(r); free(request);
}
static long ioctl_async(void *context,const struct md_control_actor *actor,unsigned command,unsigned long value,
        void *data,size_t size,struct md_control_pending **out) {
    struct input_reader *r=context; struct input_device *d=r->device;
    if (!d->backend.effect || (command!=EVIOCSFF && command!=EVIOCRMFF))
        return ioctl_call(context,actor,command,value,data,size);
    struct effect_request *request=calloc(1,sizeof(*request)); if (!request) return -ENOMEM;
    request->erase=command==EVIOCRMFF;
    struct md_control_ioctl spec;
    long result=ioctl_spec(r,command,&spec);
    if (!result && (size!=spec.size || (size && !data))) result=-EINVAL;
    if (result) { free(request); return result; }
    pthread_mutex_lock(&r->bus->lock);
    int id=request->erase ? (int)value : ((struct ff_effect *)data)->id;
    if (id>=0 && id<MD_INPUT_EFFECTS) {
        request->old_owner=d->effects[id].owner; request->previous=d->effects[id].effect;
    }
    result=ioctl_locked(r,command,value,data,size);
    if (!result) {
        request->id=request->erase ? (unsigned)value : (unsigned)((struct ff_effect *)data)->id;
        request->reader=retain(r);
        if (!request->erase) request->next=*(struct ff_effect *)data;
        else request->next=request->previous;
        d->pending[request->id]=request;
        result=d->backend.effect(d->backend.context,&request->next,&request->previous,request->erase,&request->backend);
        if (result==-EINPROGRESS && request->backend) {
            request->pending=(struct md_control_pending){.resume=effect_resume,.dispose=effect_dispose,.deadline=request->backend->deadline};
            *out=&request->pending;
        } else {
            if (result==-EINPROGRESS) result=-EPROTO;
            effect_settle(request,result>=0);
        }
    }
    pthread_mutex_unlock(&r->bus->lock);
    if (*out) return result;
    if (request->backend) request->backend->dispose(request->backend);
    if (request->reader) release(request->reader);
    free(request); return result;
}
static long write_events(void *context,const struct md_control_actor *actor,const void *data,size_t size,int64_t offset) {
    (void)actor; (void)offset; struct input_reader *r=context;
    if (r->joystick || size%sizeof(struct input_event)) return -EINVAL;
    pthread_mutex_lock(&r->bus->lock);
    long result=0;
    if (r->revoked || !r->device->connected) result=-ENODEV;
    const struct input_event *events=data;
    for (size_t i=0;result>=0 && i<size/sizeof(*events);++i) {
        const struct input_event *e=&events[i];
        if (e->type==EV_SYN && e->code==SYN_REPORT) { result+=sizeof(*e); continue; }
        if (e->type!=EV_FF || !r->device->description.rumble || e->value<0) { if (!result) result=-EINVAL; break; }
        if (e->code==FF_GAIN && e->value<=65535) {
            r->gain=(uint16_t)e->value;
            if (r->device->backend.output) r->device->backend.output(r->device->backend.context,e);
        }
        else if (e->code<MD_INPUT_EFFECTS && r->device->effects[e->code].owner==r) {
            if (r->device->pending[e->code]) { if (!result) result=-EBUSY; break; }
            struct ff_effect *effect=&r->device->effects[e->code].effect;
            struct md_input_rumble command={.device=r->device->slot,.effect=e->code,
                .strong=(uint16_t)((uint32_t)effect->u.rumble.strong_magnitude*r->gain/65535),
                .weak=(uint16_t)((uint32_t)effect->u.rumble.weak_magnitude*r->gain/65535),
                .milliseconds=effect->replay.length,.delay=effect->replay.delay,.repeats=(unsigned)e->value};
            if (r->device->backend.output) r->device->backend.output(r->device->backend.context,e);
            else if (r->bus->output) r->bus->output(r->bus->context,&command);
        } else { if (!result) result=-EINVAL; break; }
        result+=sizeof(*e);
    }
    pthread_mutex_unlock(&r->bus->lock); return result;
}
static const struct md_control_file_ops operations={.retain=retain,.release=release,.stat=stat_node,
    .read=read_events,.write=write_events,.filesystem=TMPFS_MAGIC,.access=access_node,.nonseekable=1,
    .descriptor=descriptor,.open=open_reader,.ioctl_spec=ioctl_spec,.ioctl=ioctl_call,
    .ioctl_async=ioctl_async,
    .readable=readable,.read_commit=read_commit};
