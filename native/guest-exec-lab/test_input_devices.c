#define _GNU_SOURCE
#include "input_devices.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/joystick.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/eventfd.h>
#include <unistd.h>

static unsigned outputs;
static struct md_input_rumble last;
static void output(void *context,const struct md_input_rumble *command) {
    assert(context==&outputs); outputs++; last=*command;
}
static struct md_control_file open_device(struct md_input_bus *bus,int joy) {
    struct md_control_file node={0}; assert(!md_input_node(bus,0,joy,&node));
    struct md_control_file file={node.ops,node.ops->open(node.context,NULL,O_RDWR|O_NONBLOCK)};
    assert(file.context); md_control_file_release(node); return file;
}
static long control(struct md_control_file file,unsigned command,unsigned long value,void *data) {
    struct md_control_ioctl spec; int error=file.ops->ioctl_spec(file.context,command,&spec);
    return error ? error : file.ops->ioctl(file.context,NULL,command,value,data,spec.size);
}
static long read_events(struct md_control_file file,void *data,size_t size,int commit) {
    long n=file.ops->read(file.context,NULL,data,size,0);
    if (n>=0) file.ops->read_commit(file.context,commit ? (size_t)n : 0);
    return n;
}
int main(void) {
    int wake=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(wake>=0);
    struct md_input_bus *bus=NULL; assert(!md_input_bus_new(wake,output,&outputs,&bus));
    struct md_input_description desc={.name="Test controller",.id={BUS_VIRTUAL,0x4d44,1,1},.rumble=1};
    desc.keys[BTN_SOUTH/8]|=1U<<(BTN_SOUTH%8); desc.keys[BTN_EAST/8]|=1U<<(BTN_EAST%8);
    desc.axes[0]=(1U<<ABS_X)|(1U<<ABS_Y);
    desc.abs[ABS_X]=(struct input_absinfo){.minimum=-32767,.maximum=32767,.flat=4096};
    desc.abs[ABS_Y]=desc.abs[ABS_X];
    assert(!md_input_add(bus,0,&desc)); assert(md_input_add(bus,0,&desc)==-EEXIST);
    struct md_control_file first=open_device(bus,0),second=open_device(bus,0),joy=open_device(bus,1);
    int fd=first.ops->descriptor(first.context,0); assert(fd>=0);
    struct pollfd p={fd,POLLIN,0}; assert(poll(&p,1,0)==0);
    struct input_event events[MD_INPUT_QUEUE];
    assert(read_events(first,events,sizeof(events),1)==-EAGAIN);
    assert(read_events(first,events,1,1)==-EINVAL);
    int version=0; assert(!control(first,EVIOCGVERSION,0,&version) && version==EV_VERSION);
    char name[128]; assert(control(first,EVIOCGNAME(sizeof(name)),0,name)==16 && !strcmp(name,desc.name));
    unsigned char keys[(KEY_CNT+7)/8]; assert(control(first,EVIOCGBIT(EV_KEY,sizeof(keys)),0,keys)==sizeof(keys));
    assert(keys[BTN_SOUTH/8]&(1U<<(BTN_SOUTH%8)));
    struct input_absinfo abs; assert(!control(first,EVIOCGABS(ABS_X),0,&abs) && abs.flat==4096);
    struct js_event js[64]; assert(read_events(joy,js,sizeof(js),1)==4*sizeof(*js));
    assert(js[0].type==(JS_EVENT_BUTTON|JS_EVENT_INIT));
    struct input_event frame[]={{.type=EV_KEY,.code=BTN_SOUTH,.value=1},{.type=EV_ABS,.code=ABS_X,.value=1234}};
    assert(!md_input_frame(bus,0,frame,2)); assert(poll(&p,1,0)==1 && p.revents&POLLIN);
    assert(read_events(first,events,sizeof(events),0)==3*sizeof(*events));
    assert(read_events(first,events,sizeof(events),1)==3*sizeof(*events));
    assert(events[0].code==BTN_SOUTH && events[1].value==1234 && events[2].type==EV_SYN);
    assert(poll(&p,1,0)==0);
    assert(read_events(second,events,sizeof(events),1)==3*sizeof(*events));
    assert(read_events(joy,js,sizeof(js),1)==2*sizeof(*js) && js[1].value==1234);
    assert(!control(first,EVIOCGRAB,1,NULL)); assert(control(second,EVIOCGRAB,1,NULL)==-EBUSY);
    assert(!md_input_frame(bus,0,frame,1));
    assert(read_events(second,events,sizeof(events),1)==-EAGAIN);
    assert(!control(first,EVIOCGRAB,0,NULL));
    assert(read_events(first,events,sizeof(events),1)==2*sizeof(*events));
    struct ff_effect effect={.type=FF_RUMBLE,.id=-1,.u.rumble={32000,10000},.replay={120,5}};
    assert(!control(first,EVIOCSFF,0,&effect) && effect.id==0);
    assert(control(second,EVIOCRMFF,0,NULL)==-EINVAL);
    struct input_event play={.type=EV_FF,.code=(uint16_t)effect.id,.value=1};
    assert(first.ops->write(first.context,NULL,&play,sizeof(play),0)==sizeof(play));
    assert(outputs==1 && last.strong==32000 && last.weak==10000 && last.milliseconds==120);
    md_input_neutralize(bus); assert(outputs==2 && !last.repeats);
    assert(control(first,EVIOCGKEY(sizeof(keys)),0,keys)==sizeof(keys) && !(keys[BTN_SOUTH/8]&(1U<<(BTN_SOUTH%8))));
    assert(!control(first,EVIOCGABS(ABS_X),0,&abs) && !abs.value);
    (void)read_events(first,events,sizeof(events),1);
    for (unsigned i=0;i<MD_INPUT_QUEUE;++i) assert(!md_input_frame(bus,0,frame,1));
    assert(read_events(first,events,sizeof(events),1)==sizeof(events));
    assert(read_events(first,events,sizeof(events),1)==2*sizeof(*events));
    assert(events[0].type==EV_SYN && events[0].code==SYN_DROPPED);
    struct md_control_file dup=md_control_file_retain(first); md_control_file_release(first);
    assert(!control(dup,EVIOCREVOKE,0,NULL));
    assert(read_events(dup,events,sizeof(events),1)==-ENODEV);
    assert(poll(&p,1,0)==1 && p.revents&POLLHUP);
    assert(!md_input_remove(bus,0));
    assert(read_events(second,events,sizeof(events),1)==-ENODEV);
    assert(!md_input_add(bus,0,&desc));
    struct md_control_file replacement=open_device(bus,0);
    assert(read_events(replacement,events,sizeof(events),1)==-EAGAIN);
    assert(read_events(joy,js,sizeof(js),1)==-ENODEV);
    md_input_disconnect(bus);
    assert(read_events(replacement,events,sizeof(events),1)==-ENODEV);
    md_control_file_release(dup); md_control_file_release(second); md_control_file_release(joy);
    md_control_file_release(replacement); md_input_bus_release(bus); close(fd); close(wake);
    puts("PASS input devices: evdev, joydev, independent queues, copy failure, hotplug, grab/revoke, overflow, rumble, cleanup");
    return 0;
}
