#define _GNU_SOURCE
#include "event_signal.h"
#include "input_uinput.h"
#include "event_wait.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <linux/uinput.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/sysmacros.h>
#include <unistd.h>

struct ui_state;
struct ui_request {
    struct md_control_pending pending;
    struct ui_request *next;
    struct ui_state *state;
    struct ff_effect effect,old;
    uint32_t id;
    int erase,claimed,done,result;
};
struct md_uinput {
    atomic_uint references;
    pthread_mutex_t lock;
    struct md_input_bus *bus;
    struct md_uinput_publisher publisher;
    struct ui_state *slots[MD_INPUT_DEVICES];
    struct ui_state *handles;
    int wake,stopped;
};
struct ui_state {
    atomic_uint references;
    struct md_uinput *owner;
    struct ui_state *next;
    pthread_mutex_t lock;
    struct md_input_description description;
    char physical_path[128];
    int setup,slot,opened,closed,signal[2],signaled,error;
    unsigned head,count,offered,staged;
    uint32_t request_id,types,effects;
    struct input_event events[MD_INPUT_QUEUE],frame[MD_INPUT_QUEUE];
    struct ui_request *requests;
};
static const struct md_control_file_ops operations;
static void wake(struct ui_state *s) {
    if ((s->count || s->error || s->closed) && !s->signaled && s->signal[1]>=0) {
        char byte=1;
        if (send(s->signal[1],&byte,1,MSG_DONTWAIT|MSG_NOSIGNAL)==1) s->signaled=1;
    }
    md_event_signal(s->owner->wake);
}
static void *state_retain(void *context) {
    struct ui_state *s=context; atomic_fetch_add_explicit(&s->references,1,memory_order_relaxed); return s;
}
void md_uinput_release(struct md_uinput *u) {
    if (!u || atomic_fetch_sub_explicit(&u->references,1,memory_order_acq_rel)!=1) return;
    close(u->wake); md_input_bus_release(u->bus); pthread_mutex_destroy(&u->lock); free(u);
}
static void state_release(void *context) {
    struct ui_state *s=context;
    if (atomic_fetch_sub_explicit(&s->references,1,memory_order_acq_rel)!=1) return;
    if (s->signal[0]>=0) close(s->signal[0]);
    if (s->signal[1]>=0) close(s->signal[1]);
    pthread_mutex_destroy(&s->lock); md_uinput_release(s->owner); free(s);
}
static struct ui_state *state_new(struct md_uinput *u,int opened) {
    struct ui_state *s=calloc(1,sizeof(*s)); if (!s) return NULL;
    atomic_init(&s->references,1); s->slot=s->signal[0]=s->signal[1]=-1;
    int error=pthread_mutex_init(&s->lock,NULL);
    if (error) { free(s); errno=error; return NULL; }
    atomic_fetch_add_explicit(&u->references,1,memory_order_relaxed); s->owner=u;
    if (opened && socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC|SOCK_NONBLOCK,0,s->signal)) {
        int error=errno; state_release(s); errno=error; return NULL;
    }
    s->opened=opened; s->types=1U<<EV_SYN;
    if (opened) { s->next=u->handles; u->handles=s; }
    return s;
}
/* Lifetime lock precedes the per-device lock. Never enter the input bus with
 * the device lock held: evdev feedback takes the reverse data-plane path. */
static void destroy_device(struct ui_state *s) {
    struct md_uinput *u=s->owner;
    pthread_mutex_lock(&s->lock);
    int slot=s->slot; s->slot=-1; s->staged=0;
    for (struct ui_request *r=s->requests;r;r=r->next) { r->done=1; r->result=-ENODEV; }
    wake(s); pthread_mutex_unlock(&s->lock);
    if (slot>=0) { u->publisher.remove(u->publisher.context,(unsigned)slot); u->slots[slot]=NULL; }
}
void md_uinput_stop(struct md_uinput *u) {
    if (!u) return;
    pthread_mutex_lock(&u->lock);
    if (!u->stopped) {
        u->stopped=1;
        for (unsigned i=MD_INPUT_PHYSICAL;i<MD_INPUT_DEVICES;++i) if (u->slots[i]) destroy_device(u->slots[i]);
        for (struct ui_state *s=u->handles;s;s=s->next) {
            pthread_mutex_lock(&s->lock); s->closed=1; wake(s);
            shutdown(s->signal[1],SHUT_RDWR); pthread_mutex_unlock(&s->lock);
        }
        u->publisher=(struct md_uinput_publisher){0};
    }
    pthread_mutex_unlock(&u->lock);
}
int md_uinput_new(struct md_input_bus *bus,int wake_fd,const struct md_uinput_publisher *publisher,struct md_uinput **out) {
    struct md_uinput *u=calloc(1,sizeof(*u)); if (!u) return -ENOMEM;
    atomic_init(&u->references,1); u->wake=-1;
    int error=pthread_mutex_init(&u->lock,NULL); if (error) { free(u); return -error; }
    u->bus=md_input_bus_retain(bus); u->publisher=*publisher;
    u->wake=fcntl(wake_fd,F_DUPFD_CLOEXEC,3);
    if (u->wake<0) { error=-errno; md_uinput_release(u); return error; }
    *out=u; return 0;
}
struct ui_handle { atomic_uint references; struct ui_state *state; };
static void *retain(void *context) {
    struct ui_handle *h=context; atomic_fetch_add_explicit(&h->references,1,memory_order_relaxed); return h;
}
static void release(void *context) {
    struct ui_handle *h=context;
    if (atomic_fetch_sub_explicit(&h->references,1,memory_order_acq_rel)!=1) return;
    struct ui_state *s=h->state;
    pthread_mutex_lock(&s->owner->lock);
    destroy_device(s);
    struct ui_state **p=&s->owner->handles;
    while (*p && *p!=s) p=&(*p)->next;
    if (*p) *p=s->next;
    pthread_mutex_lock(&s->lock); s->closed=1; wake(s); pthread_mutex_unlock(&s->lock);
    pthread_mutex_unlock(&s->owner->lock);
    state_release(s); free(h);
}
static struct ui_handle *handle_new(struct md_uinput *u,int opened) {
    struct ui_handle *h=calloc(1,sizeof(*h)); if (!h) return NULL;
    h->state=state_new(u,opened);
    if (!h->state) { free(h); return NULL; }
    atomic_init(&h->references,1); return h;
}
int md_uinput_node(struct md_uinput *u,struct md_control_file *out) {
    struct ui_handle *h=handle_new(u,0); if (!h) return -errno;
    *out=(struct md_control_file){&operations,h}; return 0;
}
static void *open_device(void *context,const struct md_control_opener *opener,int flags) {
    (void)opener;
    struct ui_state *s=((struct ui_handle *)context)->state;
    if (flags&(O_TRUNC|O_DIRECTORY|O_CREAT)) { errno=EINVAL; return NULL; }
    pthread_mutex_lock(&s->owner->lock);
    struct ui_handle *out=s->owner->stopped ? NULL : handle_new(s->owner,1);
    if (s->owner->stopped) errno=ENODEV;
    pthread_mutex_unlock(&s->owner->lock); return out;
}
static int stat_device(void *context,struct stat *st) {
    (void)context; *st=(struct stat){.st_mode=S_IFCHR|0660,.st_nlink=1,.st_ino=1,
        .st_dev=makedev(0,99),.st_rdev=makedev(10,223),.st_blksize=4096}; return 0;
}
static int access_device(void *context,const struct md_identity *id,unsigned mode) {
    (void)context; (void)id; return mode&1 ? -EACCES : 0;
}
static int descriptor(void *context,int flags) {
    struct ui_state *s=((struct ui_handle *)context)->state;
    int fd=flags&O_PATH ? open("/dev/null",O_PATH|O_CLOEXEC) : fcntl(s->signal[0],F_DUPFD_CLOEXEC,3);
    return fd<0 ? -errno : fd;
}
static void enqueue(struct ui_state *s,const struct input_event *event) {
    if (s->count==MD_INPUT_QUEUE) s->error=-ENOBUFS;
    else s->events[(s->head+s->count++)%MD_INPUT_QUEUE]=*event;
    wake(s);
}
static void output(void *context,const struct input_event *event) {
    struct ui_state *s=context; pthread_mutex_lock(&s->lock);
    if (!s->closed) enqueue(s,event);
    pthread_mutex_unlock(&s->lock);
}
static long request_resume(struct md_control_pending *base,void *data,size_t size) {
    (void)data; (void)size; struct ui_request *r=(void *)base;
    pthread_mutex_lock(&r->state->lock); long result=r->done ? r->result : -EINPROGRESS;
    pthread_mutex_unlock(&r->state->lock); return result;
}
static void request_dispose(struct md_control_pending *base) {
    struct ui_request *r=(void *)base; struct ui_state *s=r->state;
    pthread_mutex_lock(&s->lock);
    struct ui_request **p=&s->requests; while (*p && *p!=r) p=&(*p)->next;
    if (*p) *p=r->next;
    pthread_mutex_unlock(&s->lock); state_release(s); free(r);
}
static long effect(void *context,const struct ff_effect *value,const struct ff_effect *old,int erase,
        struct md_control_pending **out) {
    struct ui_state *s=context;
    struct ui_request *r=calloc(1,sizeof(*r)); if (!r) return -ENOMEM;
    /* EVENT_WAIT: userspace acknowledges the FF upload/erase; an unresponsive
     * owner fails the request with ETIMEDOUT, without holding an OFD turn. */
    r->pending=(struct md_control_pending){.resume=request_resume,.dispose=request_dispose,.deadline=md_event_now()+30000000000LL};
    r->effect=*value; r->old=*old; r->erase=erase;
    pthread_mutex_lock(&s->lock);
    int error=s->closed || s->slot<0 ? -ENODEV : s->count==MD_INPUT_QUEUE ? -ENOBUFS : 0;
    if (!error) {
        do { if (++s->request_id>INT32_MAX) s->request_id=1;
            r->id=s->request_id;
            struct ui_request *other=s->requests; while (other && other->id!=r->id) other=other->next;
            if (!other) break;
        } while (1);
        r->state=state_retain(s); r->next=s->requests; s->requests=r;
        struct input_event event={.type=EV_UINPUT,.code=erase ? UI_FF_ERASE : UI_FF_UPLOAD,.value=(int)r->id};
        enqueue(s,&event); *out=&r->pending;
    }
    pthread_mutex_unlock(&s->lock);
    if (error) { free(r); return error; }
    return -EINPROGRESS;
}
static int ioctl_spec(void *context,unsigned command,struct md_control_ioctl *spec) {
    (void)context; *spec=(struct md_control_ioctl){0};
    switch (command) {
    case UI_SET_PHYS: spec->size=128; spec->direction=MD_CONTROL_STRING; return 0;
    case UI_DEV_CREATE: case UI_DEV_DESTROY:
    case UI_SET_EVBIT: case UI_SET_KEYBIT: case UI_SET_ABSBIT: case UI_SET_FFBIT: case UI_SET_PROPBIT: return 0;
    case UI_DEV_SETUP: case UI_ABS_SETUP: case UI_BEGIN_FF_UPLOAD: case UI_END_FF_UPLOAD:
    case UI_BEGIN_FF_ERASE: case UI_END_FF_ERASE: case UI_GET_VERSION: break;
    default:
        if (_IOC_TYPE(command)!='U' || _IOC_NR(command)!=44 || _IOC_DIR(command)!=_IOC_READ) return -ENOTTY;
    }
    spec->size=_IOC_SIZE(command); spec->direction=(_IOC_DIR(command)&_IOC_READ ? MD_CONTROL_OUT : 0)
        |(_IOC_DIR(command)&_IOC_WRITE ? MD_CONTROL_IN : 0);
    return 0;
}
static int set_bit(uint8_t *bits,unsigned limit,unsigned long value) {
    if (value>=limit) return -EINVAL;
    bits[value/8]|=1U<<(value%8); return 0;
}
static long feedback_ioctl(struct ui_state *s,unsigned command,void *data) {
    uint32_t id; memcpy(&id,data,4);
    struct ui_request *r=s->requests; while (r && r->id!=id) r=r->next;
    if (!r || r->done) return -EINVAL;
    int erase=command==UI_BEGIN_FF_ERASE || command==UI_END_FF_ERASE;
    if (r->erase!=erase) return -EINVAL;
    if (command==UI_BEGIN_FF_UPLOAD) {
        struct uinput_ff_upload *upload=data;
        *upload=(struct uinput_ff_upload){.request_id=id,.effect=r->effect,.old=r->old}; r->claimed=1;
    } else if (command==UI_BEGIN_FF_ERASE) {
        struct uinput_ff_erase *e=data; *e=(struct uinput_ff_erase){.request_id=id,.effect_id=(unsigned)r->effect.id}; r->claimed=1;
    } else {
        int result=erase ? ((struct uinput_ff_erase *)data)->retval : ((struct uinput_ff_upload *)data)->retval;
        if (!r->claimed || result>0 || result<-4095) return -EINVAL;
        r->done=1; r->result=result; wake(s);
    }
    return 0;
}
static long ioctl_call(void *context,const struct md_control_actor *actor,unsigned command,unsigned long value,void *data,size_t size) {
    (void)actor; struct ui_state *s=((struct ui_handle *)context)->state;
    struct md_uinput *u=s->owner; struct md_control_ioctl spec;
    long result=ioctl_spec(context,command,&spec); if (result) return result;
    if (size!=spec.size || (size && !data)) return -EINVAL;
    pthread_mutex_lock(&u->lock);
    pthread_mutex_lock(&s->lock);
    if (u->stopped || s->closed || !s->opened) result=-ENODEV;
    else if (command==UI_GET_VERSION) *(unsigned *)data=UINPUT_VERSION;
    else if (_IOC_NR(command)==44) {
        if (s->slot<0) result=-ENOENT;
        else { char name[32]; int n=snprintf(name,sizeof(name),"input%d",s->slot)+1;
            size_t length=(size_t)n<size ? (size_t)n : size; memcpy(data,name,length); result=(long)length; }
    } else if (command==UI_BEGIN_FF_UPLOAD || command==UI_END_FF_UPLOAD
            || command==UI_BEGIN_FF_ERASE || command==UI_END_FF_ERASE) result=feedback_ioctl(s,command,data);
    else if (command==UI_DEV_DESTROY) {
        pthread_mutex_unlock(&s->lock); destroy_device(s); pthread_mutex_unlock(&u->lock); return 0;
    } else if (s->slot>=0) result=-EBUSY;
    else switch (command) {
    case UI_SET_PHYS:
        if (!memchr(data,0,size)) result=-EINVAL;
        else memcpy(s->physical_path,data,size);
        break;
    case UI_SET_EVBIT:
        if (value!=EV_SYN && value!=EV_KEY && value!=EV_ABS && value!=EV_FF) result=-EOPNOTSUPP;
        else s->types|=1U<<value;
        break;
    case UI_SET_KEYBIT: result=set_bit(s->description.keys,KEY_CNT,value); break;
    case UI_SET_ABSBIT: result=set_bit(s->description.axes,ABS_CNT,value); break;
    case UI_SET_FFBIT:
        if (value!=FF_RUMBLE && value!=FF_GAIN) result=-EOPNOTSUPP;
        else s->description.rumble=1;
        break;
    case UI_SET_PROPBIT: result=-EOPNOTSUPP; break;
    case UI_DEV_SETUP: {
        struct uinput_setup *setup=data;
        if (!memchr(setup->name,0,sizeof(setup->name)) || setup->ff_effects_max>MD_INPUT_EFFECTS) { result=-EINVAL; break; }
        memcpy(s->description.name,setup->name,sizeof(setup->name)); s->description.id=setup->id;
        s->setup=1; s->effects=setup->ff_effects_max; break;
    }
    case UI_ABS_SETUP: {
        struct uinput_abs_setup *abs=data;
        if (abs->code>=ABS_CNT) { result=-EINVAL; break; }
        s->description.abs[abs->code]=abs->absinfo; result=set_bit(s->description.axes,ABS_CNT,abs->code); break;
    }
    case UI_DEV_CREATE: {
        if (!s->setup || !s->description.name[0] || (s->description.rumble && (!s->effects || !(s->types&(1U<<EV_FF))))) { result=-EINVAL; break; }
        unsigned slot=MD_INPUT_PHYSICAL; while (slot<MD_INPUT_DEVICES && u->slots[slot]) ++slot;
        if (slot==MD_INPUT_DEVICES) { result=-ENOSPC; break; }
        struct md_input_description d=s->description;
        if (!(s->types&(1U<<EV_KEY))) memset(d.keys,0,sizeof(d.keys));
        if (!(s->types&(1U<<EV_ABS))) memset(d.axes,0,sizeof(d.axes));
        struct md_input_backend backend={.context=s,.retain=state_retain,.release=state_release,
            .output=output,.effect=effect,.effects=s->effects,.physical_path=s->physical_path};
        pthread_mutex_unlock(&s->lock);
        result=u->publisher.add(u->publisher.context,slot,&d,&backend);
        pthread_mutex_lock(&s->lock);
        if (!result) { s->slot=(int)slot; u->slots[slot]=s; }
        break;
    }
    default: result=-ENOTTY; break;
    }
    pthread_mutex_unlock(&s->lock); pthread_mutex_unlock(&u->lock); return result;
}
static int readable(void *context) {
    struct ui_state *s=((struct ui_handle *)context)->state;
    pthread_mutex_lock(&s->lock); int ready=s->count || s->closed || s->error;
    pthread_mutex_unlock(&s->lock); return ready;
}
static long read_events(void *context,const struct md_control_actor *actor,void *data,size_t size,int64_t offset) {
    (void)actor; (void)offset; struct ui_state *s=((struct ui_handle *)context)->state;
    if (!size) return 0;
    if (size<sizeof(struct input_event)) return -EINVAL;
    pthread_mutex_lock(&s->lock); long result=s->closed ? -ENODEV : s->error ? s->error : -EAGAIN;
    if (result==-EAGAIN && s->count) {
        size_t count=size/sizeof(struct input_event); if (count>s->count) count=s->count;
        for (size_t i=0;i<count;++i) memcpy((char *)data+i*sizeof(struct input_event),&s->events[(s->head+i)%MD_INPUT_QUEUE],sizeof(struct input_event));
        s->offered=(unsigned)count; result=(long)(count*sizeof(struct input_event));
    }
    pthread_mutex_unlock(&s->lock); return result;
}
static void read_commit(void *context,size_t copied) {
    struct ui_state *s=((struct ui_handle *)context)->state; pthread_mutex_lock(&s->lock);
    unsigned count=(unsigned)(copied/sizeof(struct input_event)); if (count>s->offered) count=s->offered;
    s->head=(s->head+count)%MD_INPUT_QUEUE; s->count-=count; s->offered=0;
    if (!s->count && s->signaled && !s->closed && !s->error) {
        char byte; (void)recv(s->signal[0],&byte,1,MSG_DONTWAIT); s->signaled=0;
    }
    pthread_mutex_unlock(&s->lock);
}
static long write_events(void *context,const struct md_control_actor *actor,const void *data,size_t size,int64_t offset) {
    (void)actor; (void)offset; struct ui_state *s=((struct ui_handle *)context)->state;
    struct md_uinput *u=s->owner; long result=0;
    pthread_mutex_lock(&u->lock); pthread_mutex_lock(&s->lock);
    if (u->stopped || s->closed) result=-ENODEV;
    else if (s->slot<0) {
        if (size!=sizeof(struct uinput_user_dev)) result=-EINVAL;
        else {
            const struct uinput_user_dev *d=data;
            if (!memchr(d->name,0,sizeof(d->name)) || d->ff_effects_max>MD_INPUT_EFFECTS) result=-EINVAL;
            else {
                memcpy(s->description.name,d->name,sizeof(d->name)); s->description.id=d->id;
                s->effects=d->ff_effects_max; s->setup=1;
                for (unsigned i=0;i<ABS_CNT;++i) s->description.abs[i]=(struct input_absinfo){
                    .minimum=d->absmin[i],.maximum=d->absmax[i],.fuzz=d->absfuzz[i],.flat=d->absflat[i]};
                result=(long)size;
            }
        }
    } else if (size%sizeof(struct input_event)) result=-EINVAL;
    else {
        const struct input_event *events=data;
        for (size_t i=0;i<size/sizeof(*events);++i) {
            if (events[i].type==EV_SYN && events[i].code==SYN_REPORT) {
                struct input_event frame[MD_INPUT_QUEUE]; unsigned count=s->staged; s->staged=0;
                memcpy(frame,s->frame,count*sizeof(*frame)); int slot=s->slot;
                pthread_mutex_unlock(&s->lock);
                int error=md_input_frame(u->bus,(unsigned)slot,frame,count);
                pthread_mutex_lock(&s->lock);
                if (error) { if (!result) result=error; break; }
            } else if (s->staged==MD_INPUT_QUEUE-1) { s->staged=0; if (!result) result=-ENOBUFS; break; }
            else if (events[i].type!=EV_KEY && events[i].type!=EV_ABS) { if (!result) result=-EINVAL; break; }
            else s->frame[s->staged++]=events[i];
            result+=sizeof(*events);
        }
    }
    pthread_mutex_unlock(&s->lock); pthread_mutex_unlock(&u->lock); return result;
}
static const struct md_control_file_ops operations={.retain=retain,.release=release,.open=open_device,
    .descriptor=descriptor,.stat=stat_device,.access=access_device,.filesystem=TMPFS_MAGIC,.nonseekable=1,
    .read=read_events,.readable=readable,.read_commit=read_commit,.write=write_events,
    .ioctl_spec=ioctl_spec,.ioctl=ioctl_call};
