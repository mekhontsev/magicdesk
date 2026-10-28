#define _GNU_SOURCE
#include "shm_bridge.h"
#include "ipc_namespace.h"
#include "task_registry.h"
#include "fs_wire.h"
#include "resource_protocol.h"
#include "pid_namespace.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static unsigned descriptors(void) {
    DIR *d=opendir("/proc/self/fd"); assert(d);
    unsigned n=0; while (readdir(d)) ++n;
    closedir(d); return n;
}
static struct lorie_resource_reply request(struct md_shm_bridge *bridge,int fd,uint32_t id,
        unsigned flags,int error,int out[2]) {
    struct lorie_resource_request q={LORIE_RESOURCE_MAGIC,LORIE_RESOURCE_SHM,id,flags};
    assert(md_fs_send_fds(fd,&q,sizeof(q),NULL,0)==sizeof(q));
    assert(!md_shm_bridge_dispatch(bridge));
    struct lorie_resource_reply reply;
    unsigned count=0;
    assert(md_fs_receive_fds(fd,&reply,sizeof(reply),out,2,&count)==sizeof(reply));
    assert(reply.error==error && !reply.reserved && count==(error ? 0 : 2));
    return reply;
}
void test_shm_bridge(const char *root) {
    unsigned before=descriptors();
    struct md_task_snapshot task={.tgid=getpid(),.identity=md_identity_new(1000,1000),
        .ipc=md_ipc_namespace_new(root,1)};
    assert(task.ipc);
    struct md_shm *s=NULL;
    assert(!md_ipc_namespace_open_memory(task.ipc,&s));
    struct md_shm_space *space=md_shm_space_new(); assert(space);
    struct md_shm_mapping *pending=NULL;
    unsigned long a[4]={IPC_PRIVATE,4096,IPC_CREAT|0600,0};
    int id=(int)md_shm_call(s,space,&pending,&task.identity,task.tgid,task.pid_group,MD_SHM_GET,a,NULL); assert(id>0);
    struct md_shm_bridge *bridge=NULL;
    assert(md_shm_bridge_pollfd(bridge)==-1);
    int channel=md_shm_bridge_connect(&bridge,&task); assert(channel>=0);
    assert(md_shm_bridge_pollfd(bridge)>=0);
    struct md_ipc_namespace *original=task.ipc;
    task.ipc=md_ipc_namespace_copy(original,NULL); assert(task.ipc);
    int foreign=md_shm_bridge_connect(&bridge,&task); assert(foreign>=0);
    int rights[2]={-1,-1};
    request(bridge,foreign,id,0,-EINVAL,rights);
    /* Admission captured the old namespace, even after the creator switches. */
    assert(request(bridge,channel,id,0,0,rights).size==4096);
    assert(pwrite(rights[0],"bridge",6,0)==6);
    close(rights[0]); close(rights[1]);
    request(bridge,channel,id,2,-EINVAL,rights);
    request(bridge,channel,UINT32_MAX,0,-EINVAL,rights);
    task.identity.uid=(struct md_identity_ids){1001,1001,1001,1001};
    md_ipc_namespace_release(task.ipc); task.ipc=original;
    int denied=md_shm_bridge_connect(&bridge,&task); assert(denied>=0);
    request(bridge,denied,id,0,-EACCES,rights);
    assert(request(bridge,channel,id,LORIE_RESOURCE_READONLY,0,rights).size==4096);
    assert((fcntl(rights[0],F_GETFL)&O_ACCMODE)==O_RDONLY);
    char bytes[7]={0}; assert(pread(rights[0],bytes,6,0)==6 && !strcmp(bytes,"bridge"));
    for (unsigned i=0;i<4;++i) {
        int bad=md_shm_bridge_connect(&bridge,&task); assert(bad>=0);
        struct lorie_resource_request q={LORIE_RESOURCE_MAGIC,LORIE_RESOURCE_SHM,id,0};
        if (i==0) q.magic=0;
        int extra=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(extra>=0);
        assert(md_fs_send_fds(bad,&q,i==1 ? sizeof(q)-1 : sizeof(q),
            i==2 ? &extra : NULL,i==2 ? 1 : 0)>0);
        close(extra);
        if (i==3) close(bad); /* A queued request loses its response owner. */
        assert(!md_shm_bridge_dispatch(bridge));
        if (i!=3) {
            char byte;
            assert(recv(bad,&byte,1,MSG_DONTWAIT)==0);
            close(bad);
        }
    }
    /* A successful response's queued rights must disappear on peer close too. */
    int abandoned=md_shm_bridge_connect(&bridge,&task); assert(abandoned>=0);
    struct lorie_resource_request q={LORIE_RESOURCE_MAGIC,LORIE_RESOURCE_SHM,id,0};
    /* Use the admission that retained the authorized identity. */
    assert(md_fs_send_fds(channel,&q,sizeof(q),NULL,0)==sizeof(q));
    assert(!md_shm_bridge_dispatch(bridge));
    close(channel); channel=-1;
    close(abandoned);
    assert(!md_shm_bridge_dispatch(bridge));
    /* Already exported ownership does not depend on broker or creator life. */
    md_shm_bridge_close(bridge); bridge=NULL;
    md_shm_close(s);
    assert(pread(rights[0],bytes,6,0)==6);
    close(rights[0]); close(rights[1]);
    close(foreign); close(denied);
    md_shm_space_release(space);
    md_identity_release(&task.identity); md_ipc_namespace_release(original);
    md_pid_identity_release(task.pid_group);
    assert(descriptors()==before);
    puts("PASS SHM broker: captured namespace/credentials, readonly export, errors, broker exit and no FD leaks");
}
