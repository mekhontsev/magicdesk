#include "descriptor_capture.h"
#include "fd_capture.h"
#include "adapter_buffer.h"
#include "raw.h"
#include "interception.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/socket.h>

long md_descriptor_capture_inputs(long state,struct md_fd_capture_frame *f,const int *numbers,unsigned count,
        const struct md_descriptor_capture_protocol *p) {
    while (state>0 && state!=p->complete) {
        if (state==MD_FD_CAPTURE_ADMIT) {
            /* Admission must not turn an invalid source slot into the channel.
             * The caller's table turn covers validation and exact export. */
            for (unsigned i=0;i<count;++i) {
                long valid=RAW2(fcntl,numbers[i],F_GETFD);
                if (valid<0) return md_guest_buffer_control(p->sent,valid,0);
            }
            long fd=p->replay ? md_guest_buffer_replay(p->replay) : md_guest_buffer_admit(p->admit);
            if (fd<0) return md_guest_buffer_control(p->sent,fd,0);
            state=md_guest_buffer_control(p->ready,0,0);
        } else if (state==MD_FD_CAPTURE_CHECK) {
            struct md_fd_channel_observation *o=&f->observation;
            *o=(struct md_fd_channel_observation){.length=sizeof(o->cookie)};
            long flags=RAW2(fcntl,f->endpoint,F_GETFD);
            o->error=flags<0 ? (int)flags : (int)RAW5(getsockopt,f->endpoint,SOL_SOCKET,SO_COOKIE,&o->cookie,&o->length);
            if (flags>=0) o->flags=(unsigned)flags;
            state=md_guest_buffer_control(p->observed,0,0);
        } else if (state==MD_FD_CAPTURE_SEND) {
            if (!f->count || f->count>253 || f->offset>count || f->count>count-f->offset) return -EPROTO;
            _Alignas(struct cmsghdr) char control[CMSG_SPACE(253*sizeof(int))];
            struct iovec data={&f->token,sizeof(f->token)};
            struct msghdr m={.msg_iov=&data,.msg_iovlen=1,.msg_control=control,
                .msg_controllen=CMSG_SPACE(f->count*sizeof(int))};
            memset(control,0,m.msg_controllen);
            struct cmsghdr *h=CMSG_FIRSTHDR(&m);
            *h=(struct cmsghdr){.cmsg_len=CMSG_LEN(f->count*sizeof(int)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
            memcpy(CMSG_DATA(h),numbers+f->offset,f->count*sizeof(int));
            long value=RAW3(sendmsg,f->endpoint,&m,MSG_DONTWAIT|MSG_NOSIGNAL);
            state=md_guest_buffer_control(p->sent,value,0);
        } else return -EPROTO;
    }
    return state;
}
