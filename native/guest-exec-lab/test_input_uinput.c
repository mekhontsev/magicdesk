#define _GNU_SOURCE
#include "input_uinput.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/uinput.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/eventfd.h>
#include <unistd.h>

static int add(void *bus,unsigned slot,const struct md_input_description *d,const struct md_input_backend *backend) {
    return md_input_add_backend(bus,slot,d,backend);
}
static void remove_device(void *bus,unsigned slot) { assert(!md_input_remove(bus,slot)); }
static long control(struct md_control_file f,unsigned cmd,unsigned long value,void *data) {
    struct md_control_ioctl spec;
    long n=f.ops->ioctl_spec(f.context,cmd,&spec);
    return n ? n : f.ops->ioctl(f.context,NULL,cmd,value,data,spec.size);
}
static struct md_control_file open_node(struct md_control_file node) {
    struct md_control_file f={node.ops,node.ops->open(node.context,NULL,O_RDWR|O_NONBLOCK)};
    assert(f.context); return f;
}
static struct input_event receive(struct md_control_file f) {
    struct input_event e;
    assert(f.ops->read(f.context,NULL,&e,sizeof(e),0)==sizeof(e));
    f.ops->read_commit(f.context,sizeof(e)); return e;
}
static void configure(struct md_control_file f) {
    assert(!control(f,UI_SET_EVBIT,EV_KEY,NULL));
    assert(!control(f,UI_SET_EVBIT,EV_ABS,NULL));
    assert(!control(f,UI_SET_EVBIT,EV_FF,NULL));
    assert(!control(f,UI_SET_KEYBIT,BTN_SOUTH,NULL));
    assert(!control(f,UI_SET_FFBIT,FF_RUMBLE,NULL));
    struct uinput_setup setup={.name="Virtual gamepad",.id={BUS_VIRTUAL,1,2,3},.ff_effects_max=1};
    assert(!control(f,UI_DEV_SETUP,0,&setup));
    struct uinput_abs_setup abs={.code=ABS_X,.absinfo={.minimum=-32767,.maximum=32767}};
    assert(!control(f,UI_ABS_SETUP,0,&abs));
    char phys[128]="test/virtual"; assert(!control(f,UI_SET_PHYS,0,phys));
    assert(!control(f,UI_DEV_CREATE,0,NULL));
}
static struct md_control_pending *upload(struct md_control_file ev,int id) {
    struct ff_effect e={.type=FF_RUMBLE,.id=(int16_t)id,.u.rumble={1000,2000},.replay={50,0}};
    struct md_control_pending *pending=NULL;
    assert(ev.ops->ioctl_async(ev.context,NULL,EVIOCSFF,0,&e,sizeof(e),&pending)==-EINPROGRESS);
    assert(pending); return pending;
}
static void acknowledge(struct md_control_file ui,int result) {
    struct input_event e=receive(ui); assert(e.type==EV_UINPUT && e.code==UI_FF_UPLOAD);
    struct uinput_ff_upload u={.request_id=(unsigned)e.value};
    assert(!control(ui,UI_BEGIN_FF_UPLOAD,0,&u));
    assert(u.effect.type==FF_RUMBLE && u.effect.u.rumble.strong_magnitude==1000);
    u.retval=result; assert(!control(ui,UI_END_FF_UPLOAD,0,&u));
    assert(control(ui,UI_END_FF_UPLOAD,0,&u)==-EINVAL);
}
int main(void) {
    int wake=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(wake>=0);
    struct md_input_bus *bus=NULL; assert(!md_input_bus_new(wake,NULL,NULL,&bus));
    struct md_uinput_publisher publisher={bus,add,remove_device};
    struct md_uinput *u=NULL; assert(!md_uinput_new(bus,wake,&publisher,&u));
    struct md_control_file node={0}; assert(!md_uinput_node(u,&node));
    struct md_control_file ui=open_node(node),idle=open_node(node);
    int fd=ui.ops->descriptor(ui.context,0),idle_fd=idle.ops->descriptor(idle.context,0);
    struct pollfd poller={fd,POLLIN,0}; assert(!poll(&poller,1,0));
    unsigned version=0; assert(!control(ui,UI_GET_VERSION,0,&version) && version==UINPUT_VERSION);
    assert(control(ui,UI_SET_EVBIT,EV_REL,NULL)==-EOPNOTSUPP);
    configure(ui);
    char name[32]={0}; assert(control(ui,UI_GET_SYSNAME(sizeof(name)),0,name)>0 && !strcmp(name,"input32"));
    struct md_control_file evnode; assert(!md_input_node(bus,32,0,&evnode));
    struct md_control_file ev=open_node(evnode); md_control_file_release(evnode);
    char phys[128]; assert(control(ev,EVIOCGPHYS(sizeof(phys)),0,phys)>0 && !strcmp(phys,"test/virtual"));
    int effects=0; assert(!control(ev,EVIOCGEFFECTS,0,&effects) && effects==1);
    struct input_event frame[]={{.type=EV_KEY,.code=BTN_SOUTH,.value=1},{.type=EV_SYN,.code=SYN_REPORT}};
    assert(ui.ops->write(ui.context,NULL,frame,sizeof(frame),0)==sizeof(frame));
    assert(receive(ev).value==1); assert(receive(ev).type==EV_SYN);
    struct md_control_pending *pending=upload(ev,-1);
    assert(poll(&poller,1,0)==1 && poller.revents&POLLIN);
    struct input_event peek;
    assert(ui.ops->read(ui.context,NULL,&peek,sizeof(peek),0)==sizeof(peek));
    ui.ops->read_commit(ui.context,0);
    struct ff_effect result;
    assert(pending->resume(pending,&result,sizeof(result))==-EINPROGRESS);
    acknowledge(ui,0);
    assert(!pending->resume(pending,&result,sizeof(result)) && result.id==0);
    pending->dispose(pending); assert(!poll(&poller,1,0));
    struct ff_effect extra={.type=FF_RUMBLE,.id=-1}; pending=NULL;
    assert(ev.ops->ioctl_async(ev.context,NULL,EVIOCSFF,0,&extra,sizeof(extra),&pending)==-ENOSPC && !pending);
    struct input_event play={.type=EV_FF,.code=0,.value=1};
    assert(ev.ops->write(ev.context,NULL,&play,sizeof(play),0)==sizeof(play));
    assert(receive(ui).value==1);
    pending=upload(ev,0); acknowledge(ui,-EACCES);
    assert(pending->resume(pending,&result,sizeof(result))==-EACCES); pending->dispose(pending);
    pending=upload(ev,0); struct input_event canceled=receive(ui); pending->dispose(pending);
    struct uinput_ff_upload stale={.request_id=(unsigned)canceled.value};
    assert(control(ui,UI_BEGIN_FF_UPLOAD,0,&stale)==-EINVAL);
    pending=NULL;
    assert(ev.ops->ioctl_async(ev.context,NULL,EVIOCRMFF,0,NULL,0,&pending)==-EINPROGRESS);
    assert(receive(ui).type==EV_FF); /* Stop precedes effect removal. */
    struct input_event erase=receive(ui); assert(erase.code==UI_FF_ERASE);
    struct uinput_ff_erase er={.request_id=(unsigned)erase.value};
    assert(!control(ui,UI_BEGIN_FF_ERASE,0,&er) && !er.effect_id);
    assert(!control(ui,UI_END_FF_ERASE,0,&er));
    assert(!pending->resume(pending,NULL,0)); pending->dispose(pending);
    assert(!control(ui,UI_DEV_DESTROY,0,NULL));
    assert(!poll(&poller,1,0)); /* Destroy is not a phantom input event. */
    assert(ev.ops->read(ev.context,NULL,&peek,sizeof(peek),0)==-ENODEV);
    md_control_file_release(ev);
    configure(ui); assert(!md_input_node(bus,32,0,&evnode)); ev=open_node(evnode); md_control_file_release(evnode);
    pending=upload(ev,-1); md_control_file_release(ui);
    assert(pending->resume(pending,&result,sizeof(result))==-ENODEV); pending->dispose(pending);
    assert(ev.ops->read(ev.context,NULL,&peek,sizeof(peek),0)==-ENODEV); md_control_file_release(ev);
    md_uinput_stop(u);
    assert(idle.ops->readable(idle.context));
    assert(idle.ops->read(idle.context,NULL,&peek,sizeof(peek),0)==-ENODEV);
    struct pollfd stopped={idle_fd,POLLIN,0}; assert(poll(&stopped,1,0)==1 && stopped.revents&POLLHUP);
    assert(!node.ops->open(node.context,NULL,O_RDWR) && errno==ENODEV);
    md_control_file_release(idle); md_control_file_release(node); md_uinput_release(u);
    md_input_disconnect(bus); md_input_bus_release(bus); close(wake); close(fd); close(idle_fd);
    puts("PASS uinput: create, data, FF handshake, reject, cancel, erase, recreate, owner close, session stop");
    return 0;
}
