#define _GNU_SOURCE
#include <arpa/inet.h>
#include <assert.h>
#include <errno.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>

static int endpoint(int family,int type,const char *address,unsigned port,int server) {
    int fd=socket(family,type,0); assert(fd>=0);
    struct timeval timeout={.tv_sec=20};
    assert(!setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout)));
    assert(!setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout)));
    struct sockaddr_storage storage={0};
    struct sockaddr_in *v4=(void *)&storage;
    struct sockaddr_in6 *v6=(void *)&storage;
    storage.ss_family=family;
    socklen_t size;
    if(family==AF_INET) {
        v4->sin_port=htons(port); assert(inet_pton(family,address,&v4->sin_addr)==1); size=sizeof(*v4);
    } else {
        v6->sin6_port=htons(port); assert(inet_pton(family,address,&v6->sin6_addr)==1); size=sizeof(*v6);
    }
    int result=server ? bind(fd,(void *)&storage,size) : connect(fd,(void *)&storage,size);
    if(result) fprintf(stderr,"%s %s:%u type=%d: %s\n",server ? "bind" : "connect",address,port,type,strerror(errno));
    assert(!result);
    if(server && type==SOCK_STREAM) assert(!listen(fd,1));
    return fd;
}
static void stream_write(int fd,const unsigned char *data,size_t size) {
    while(size) {
        ssize_t count=send(fd,data,size,MSG_NOSIGNAL); assert(count>0);
        data+=count; size-=(size_t)count;
    }
}
static void stream_read(int fd,unsigned char *data,size_t size) {
    while(size) {
        ssize_t count=recv(fd,data,size,0); assert(count>0);
        data+=count; size-=(size_t)count;
    }
}
static void client(int family,const char *address) {
    unsigned char data[16000],copy[sizeof(data)];
    for(size_t i=0;i<sizeof(data);++i) data[i]=(unsigned char)(i%251);
    int fd=endpoint(family,SOCK_STREAM,address,18080,0);
    for(unsigned i=0;i<66;++i) {
        stream_write(fd,data,sizeof(data)); stream_read(fd,copy,sizeof(copy));
        assert(!memcmp(data,copy,sizeof(data)));
    }
    assert(!shutdown(fd,SHUT_WR)); assert(recv(fd,copy,1,0)==0); close(fd);
    printf("PASS VM TCP bulk and half-close %s\n",address);
    fd=endpoint(family,SOCK_DGRAM,address,18080,0);
    const size_t sizes[]={1,1200,4000,16000};
    for(unsigned i=0;i<sizeof(sizes)/sizeof(*sizes);++i) {
        size_t size=sizes[i]; assert(send(fd,data,size,0)==(ssize_t)size);
        assert(recv(fd,copy,sizeof(copy),0)==(ssize_t)size); assert(!memcmp(data,copy,size));
    }
    close(fd); printf("PASS VM UDP fragmentation %s\n",address);
}
static void server(int fd,int type) {
    unsigned char data[65535];
    if(type==SOCK_STREAM) {
        struct pollfd event={.fd=fd,.events=POLLIN};
        /* EVENT_WAIT: host connects to this exact VM listener; timeout fails. */
        assert(poll(&event,1,30000)==1);
        int peer=accept(fd,NULL,NULL); assert(peer>=0); close(fd); fd=peer;
        for(;;) {
            ssize_t size=recv(fd,data,sizeof(data),0); assert(size>=0);
            if(!size) break;
            stream_write(fd,data,(size_t)size);
        }
    } else for(unsigned i=0;i<4;++i) {
        struct sockaddr_storage peer; socklen_t length=sizeof(peer);
        ssize_t size=recvfrom(fd,data,sizeof(data),0,(void *)&peer,&length); assert(size>0);
        assert(sendto(fd,data,(size_t)size,0,(void *)&peer,length)==size);
    }
    close(fd);
}
int main(int argc,char **argv) {
    assert(argc==1 || argc==3);
    setbuf(stdout,NULL);
    client(AF_INET,argc==3 ? argv[1] : "198.51.100.1");
    client(AF_INET6,argc==3 ? argv[2] : "fd42:7::1");
    int fds[4],types[]={SOCK_STREAM,SOCK_DGRAM,SOCK_STREAM,SOCK_DGRAM};
    pid_t children[4];
    for(unsigned i=0;i<4;++i)
        fds[i]=endpoint(i<2 ? AF_INET : AF_INET6,types[i],i<2 ? "198.51.100.2" : "fd42:7::2",18081,1);
    for(unsigned i=0;i<4;++i) {
        children[i]=fork(); assert(children[i]>=0);
        if(!children[i]) {
            for(unsigned j=0;j<4;++j) if(j!=i) close(fds[j]);
            server(fds[i],types[i]); _exit(0);
        }
    }
    for(unsigned i=0;i<4;++i) close(fds[i]);
    puts("READY VM reverse traffic");
    for(unsigned i=0;i<4;++i) {
        int status=0; assert(waitpid(children[i],&status,0)==children[i]);
        assert(WIFEXITED(status) && !WEXITSTATUS(status));
    }
    puts("PASS VM bidirectional packet networking");
    return 0;
}
