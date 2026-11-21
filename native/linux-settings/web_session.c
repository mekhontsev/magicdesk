#define _GNU_SOURCE
#include "web_session.h"
#include "../magicdesk_unix_connect.h"
#include <arpa/inet.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>

/* A single event-driven adapter inside the guest's private loopback namespace.
 * The Android endpoint owns HTTP policy and the external sockets. */
enum { FLOWS=16 };
struct side { int fd, eof; size_t pos,len; char data[8192]; };
static struct side streams[FLOWS*2];

static void drop(unsigned i) {
    for (unsigned k=i&~1u;k<(i&~1u)+2;k++) {
        if (streams[k].fd>=0) close(streams[k].fd);
        streams[k]=(struct side){.fd=-1};
    }
}
static void accept_one(int listener,const char *endpoint) {
    int fd=accept4(listener,NULL,NULL,SOCK_CLOEXEC|SOCK_NONBLOCK);
    if (fd<0) return;
    for (unsigned i=0;i<FLOWS*2;i+=2) if (streams[i].fd<0) {
        int peer=md_unix_connect(endpoint);
        if (peer>=0 && fcntl(peer,F_SETFL,O_NONBLOCK)==0) {
            streams[i].fd=fd; streams[i+1].fd=peer; return;
        }
        if (peer>=0) close(peer);
        break;
    }
    close(fd);
}
static int advance(unsigned i,short events) {
    struct side *s=streams+i,*other=streams+(i^1u);
    if (events&(POLLERR|POLLNVAL)) return -1;
    if (events&POLLOUT && other->len) {
        ssize_t n=send(s->fd,other->data+other->pos,other->len,MSG_NOSIGNAL);
        if (n>0) { other->pos+=(size_t)n; other->len-=(size_t)n; }
        else if (n==0 || (errno!=EAGAIN && errno!=EINTR)) return -1;
    }
    if ((events&(POLLIN|POLLHUP)) && !s->eof && !s->len) {
        ssize_t n=read(s->fd,s->data,sizeof(s->data));
        if (n>0) { s->pos=0; s->len=(size_t)n; }
        else if (n==0) { s->eof=1; shutdown(other->fd,SHUT_WR); }
        else if (errno!=EAGAIN && errno!=EINTR) return -1;
    }
    return s->eof && other->eof ? -1 : 0;
}
static void relay(int listener,const char *endpoint) {
    for (unsigned i=0;i<FLOWS*2;i++) streams[i].fd=-1;
    for (;;) {
        struct pollfd p[1+FLOWS*2]={{.fd=listener,.events=POLLIN}};
        for (unsigned i=0;i<FLOWS*2;i++) {
            struct side *s=streams+i;
            short flags=(!s->len && !s->eof ? POLLIN : 0) | (streams[i^1u].len ? POLLOUT : 0);
            p[i+1]=(struct pollfd){.fd=flags ? s->fd : -1,.events=flags};
        }
        /* EVENT_WAIT: guest connection or stream readiness; parent death terminates the relay. */
        if (poll(p,1+FLOWS*2,-1)<0) { if (errno==EINTR) continue; break; }
        for (unsigned i=0;i<FLOWS*2;i++) if (streams[i].fd>=0 && p[i+1].revents && advance(i,p[i+1].revents)<0) drop(i);
        if (p[0].revents&POLLIN) accept_one(listener,endpoint);
        else if (p[0].revents) break;
    }
}
int md_web_session_start(void) {
    const char *endpoint=getenv("MAGICDESK_WEB_ENDPOINT");
    if (!endpoint || !*endpoint) { errno=EINVAL; return -1; }
    int listener=socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC,0);
    struct sockaddr_in addr={.sin_family=AF_INET,.sin_addr.s_addr=htonl(INADDR_LOOPBACK)};
    socklen_t length=sizeof(addr);
    if (listener<0) return -1;
    if (bind(listener,(struct sockaddr *)&addr,length) || listen(listener,FLOWS)
            || getsockname(listener,(struct sockaddr *)&addr,&length)) goto fail;
    char url[48]; snprintf(url,sizeof(url),"http://127.0.0.1:%u",ntohs(addr.sin_port));
    for (const char **k=(const char*[]){"http_proxy","https_proxy","HTTPS_PROXY",NULL};*k;k++)
        if (setenv(*k,url,1)) goto fail;
    pid_t parent=getpid(),child=fork();
    if (child<0) goto fail;
    if (!child) {
        if (prctl(PR_SET_PDEATHSIG,SIGKILL) || getppid()!=parent) _exit(1);
        close(0); close(1); close(2);
        relay(listener,endpoint); _exit(0);
    }
    unsetenv("MAGICDESK_WEB_ENDPOINT"); close(listener); return 0;
fail: {
    int error=errno; close(listener); errno=error; return -1;
}
}
